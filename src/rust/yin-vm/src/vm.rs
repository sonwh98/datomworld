use crate::assemble::{CompiledArtifact, SymbolicInstruction};
use crate::engine;
use crate::opcode::Opcode;
use crate::value::{Closure, Frame, NativeFn, Value};
use im::{HashMap, Vector};
use std::sync::Arc;

pub struct RegisterVM {
    pub blocked: bool,
    pub halted: bool,
    pub control: usize,
    pub regs: Vector<Value>,
    pub env: HashMap<Value, Value>,
    pub store: HashMap<Value, Value>,
    pub k: Option<Arc<Frame>>,
    pub bytecode: Arc<Vec<i32>>,
    pub pool: Arc<Vec<Value>>,
    pub primitives: HashMap<Value, Value>,
    pub id_counter: usize,
    pub value: Value,
    pub ready_queue: Vec<Value>,
    pub wait_set: Vec<Value>,
    pub parked: HashMap<Value, Value>,
    pub active_compiled_version: Option<u64>,
    pub program_version: u64,
    pub compile_dirty: bool,
    pub program_root_eid: Option<i64>,
    pub datoms: Vec<Value>,
}

impl RegisterVM {
    pub fn new() -> Self {
        Self {
            blocked: false,
            halted: true,
            control: 0,
            regs: Vector::new(),
            env: HashMap::new(),
            store: HashMap::new(),
            k: None,
            bytecode: Arc::new(Vec::new()),
            pool: Arc::new(Vec::new()),
            primitives: HashMap::new(),
            id_counter: 0,
            value: Value::Nil,
            ready_queue: Vec::new(),
            wait_set: Vec::new(),
            parked: HashMap::new(),
            active_compiled_version: None,
            program_version: 0,
            compile_dirty: false,
            program_root_eid: None,
            datoms: Vec::new(),
        }
    }

    pub fn set_reg(&mut self, rd: usize, val: Value) {
        while self.regs.len() <= rd {
            self.regs.push_back(Value::Nil);
        }
        self.regs.set(rd, val);
    }

    pub fn get_reg(&self, rs: usize) -> Value {
        self.regs.get(rs).cloned().unwrap_or(Value::Nil)
    }

    pub fn load_artifact(&mut self, artifact: CompiledArtifact, reg_count: usize) {
        self.bytecode = Arc::new(artifact.bytecode);
        self.pool = Arc::new(artifact.pool);
        self.regs = Vector::new();
        for _ in 0..reg_count {
            self.regs.push_back(Value::Nil);
        }
        self.control = 0;
        self.halted = false;
        self.value = Value::Nil;
        self.blocked = false;
    }

    pub fn compile_uat(&self, uat: &Value) -> (Vec<SymbolicInstruction>, usize) {
        let mut instrs = Vec::new();
        let mut reg_counter = 0;

        let result_reg = self.compile_uat_node(uat, true, &mut instrs, &mut reg_counter);
        instrs.push(SymbolicInstruction::Return(result_reg));
        (instrs, reg_counter)
    }

    fn compile_uat_node(
        &self,
        node: &Value,
        tail: bool,
        instrs: &mut Vec<SymbolicInstruction>,
        reg_counter: &mut usize,
    ) -> usize {
        let m = match node {
            Value::Map(m) => m,
            _ => panic!("UAT node must be a map"),
        };

        let node_type_val = m.get(&Value::keyword("type")).expect("type missing");
        let node_type = match node_type_val {
            Value::Keyword(s) => s.as_ref(),
            _ => panic!("type must be a keyword"),
        };
        match node_type {
            "literal" => {
                let rd = *reg_counter;
                *reg_counter += 1;
                let val = m.get(&Value::keyword("value")).cloned().unwrap_or(Value::Nil);
                instrs.push(SymbolicInstruction::Literal(rd, val));
                rd
            }
            "variable" => {
                let rd = *reg_counter;
                *reg_counter += 1;
                let name = m.get(&Value::keyword("name")).cloned().unwrap();
                instrs.push(SymbolicInstruction::LoadVar(rd, name));
                rd
            }
            "application" => {
                let op_node = m.get(&Value::keyword("operator")).unwrap();
                let operand_nodes = match m.get(&Value::keyword("operands")).unwrap() {
                    Value::Vector(v) => v,
                    _ => panic!("operands must be vector"),
                };

                let mut arg_regs = Vec::new();
                for o in operand_nodes {
                    arg_regs.push(self.compile_uat_node(o, false, instrs, reg_counter));
                }

                let rf = self.compile_uat_node(op_node, false, instrs, reg_counter);
                let rd = *reg_counter;
                *reg_counter += 1;

                if tail {
                    instrs.push(SymbolicInstruction::Tailcall(rd, rf, arg_regs));
                } else {
                    instrs.push(SymbolicInstruction::Call(rd, rf, arg_regs));
                }
                rd
            }
            "yin/macro-expand" => {
                let op_node = m.get(&Value::keyword("operator")).unwrap();
                let operands = match m.get(&Value::keyword("operands")).unwrap() {
                    Value::Vector(v) => v,
                    _ => panic!("operands must be vector"),
                };

                let expander = match op_node {
                    Value::Map(op_map) => {
                        let node_type_val = op_map.get(&Value::keyword("type")).unwrap();
                        let node_type = match node_type_val {
                            Value::Keyword(s) => s.as_ref(),
                            _ => panic!("type must be keyword"),
                        };
                        if node_type == "variable" {
                            let name = op_map.get(&Value::keyword("name")).unwrap();
                            engine::resolve_var(&self.env, &self.store, &self.primitives, name)
                        } else {
                            None
                        }
                    }
                    _ => None,
                };

                if let Some(Value::NativeFn(native)) = expander {
                    let expansion = (native.0)(operands.iter().cloned().collect());
                    self.compile_uat_node(&expansion, tail, instrs, reg_counter)
                } else {
                    panic!("Unsupported macro expander or not found: {:?}", op_node);
                }
            }
            "lambda" => {
                let params = m.get(&Value::keyword("params")).cloned().unwrap();
                let body_node = m.get(&Value::keyword("body")).unwrap();
                let rd = *reg_counter;
                *reg_counter += 1;

                let closure_idx = instrs.len();
                // Placeholder for addr and reg_count
                instrs.push(SymbolicInstruction::Lambda(rd, params, 0, 0));

                let jump_idx = instrs.len();
                instrs.push(SymbolicInstruction::Jump(0));

                let body_addr = instrs.len();

                // Nested compilation with fresh registers
                let mut sub_instrs = Vec::new();
                let mut sub_reg_counter = 0;

                let sub_res =
                    self.compile_uat_node(body_node, true, &mut sub_instrs, &mut sub_reg_counter);
                sub_instrs.push(SymbolicInstruction::Return(sub_res));

                // Splice instructions
                instrs.extend(sub_instrs);
                let after_body = instrs.len();

                // Patch
                if let SymbolicInstruction::Lambda(_, _, ref mut addr, ref mut reg_count) =
                    instrs[closure_idx]
                {
                    *addr = body_addr;
                    *reg_count = sub_reg_counter;
                }
                if let SymbolicInstruction::Jump(ref mut addr) = instrs[jump_idx] {
                    *addr = after_body;
                }

                rd
            }
            "if" => {
                let test_node = m.get(&Value::keyword("test")).unwrap();
                let cons_node = m.get(&Value::keyword("consequent")).unwrap();
                let alt_node = m.get(&Value::keyword("alternate")).unwrap();

                let test_reg = self.compile_uat_node(test_node, false, instrs, reg_counter);
                let rd = *reg_counter;
                *reg_counter += 1;

                let branch_idx = instrs.len();
                instrs.push(SymbolicInstruction::Branch(test_reg, 0, 0));

                let then_addr = instrs.len();
                let cons_reg = self.compile_uat_node(cons_node, tail, instrs, reg_counter);
                instrs.push(SymbolicInstruction::Move(rd, cons_reg));

                let jump_idx = instrs.len();
                instrs.push(SymbolicInstruction::Jump(0));

                let else_addr = instrs.len();
                let alt_reg = self.compile_uat_node(alt_node, tail, instrs, reg_counter);
                instrs.push(SymbolicInstruction::Move(rd, alt_reg));

                let end_addr = instrs.len();

                // Patch
                if let SymbolicInstruction::Branch(_, ref mut then_idx, ref mut else_idx) =
                    instrs[branch_idx]
                {
                    *then_idx = then_addr;
                    *else_idx = else_addr;
                }
                if let SymbolicInstruction::Jump(ref mut addr) = instrs[jump_idx] {
                    *addr = end_addr;
                }

                rd
            }
            "dao.stream.apply/call" => {
                let op = m.get(&Value::keyword("op")).cloned().unwrap();
                let operand_nodes = match m.get(&Value::keyword("operands")).unwrap() {
                    Value::Vector(v) => v,
                    _ => panic!("operands must be vector"),
                };

                let mut arg_regs = Vec::new();
                for o in operand_nodes {
                    arg_regs.push(self.compile_uat_node(o, false, instrs, reg_counter));
                }

                let rd = *reg_counter;
                *reg_counter += 1;
                instrs.push(SymbolicInstruction::DaoStreamApplyCall(rd, op, arg_regs));
                rd
            }
            _ => panic!("Unknown UAT node type: {}", node_type),
        }
    }

    fn snap_frame(&self, rd: Option<usize>, next_control: usize) -> Arc<Frame> {
        Arc::new(Frame {
            result_reg: rd,
            control: next_control,
            regs: self.regs.clone(),
            env: self.env.clone(),
            bytecode: self.bytecode.clone(),
            pool: self.pool.clone(),
            version: self.active_compiled_version,
            next: self.k.clone(),
        })
    }

    fn restore_frame(&mut self, frame: Arc<Frame>, value: Option<Value>) {
        self.control = frame.control;
        self.regs = frame.regs.clone();
        self.env = frame.env.clone();
        self.bytecode = frame.bytecode.clone();
        self.pool = frame.pool.clone();
        self.active_compiled_version = frame.version;
        self.k = frame.next.clone();

        if let Some(val) = value {
            if let Some(rd) = frame.result_reg {
                self.set_reg(rd, val);
            }
        }
    }

    fn bind_closure_env(
        &self,
        mut base_env: HashMap<Value, Value>,
        params: &Vector<Value>,
        arg_base: usize,
        argc: usize,
    ) -> HashMap<Value, Value> {
        for (i, param) in params.iter().enumerate() {
            let val = if i < argc {
                let arg_reg = self.bytecode[arg_base + i] as usize;
                self.get_reg(arg_reg)
            } else {
                Value::Nil
            };
            base_env.insert(param.clone(), val);
        }
        base_env
    }

    pub fn add_primitive<F>(&mut self, name: &str, f: F)
    where
        F: Fn(Vec<Value>) -> Value + Send + Sync + 'static,
    {
        self.primitives.insert(
            Value::symbol(name),
            Value::NativeFn(NativeFn(Arc::new(f))),
        );
    }

    pub fn with_std_primitives(mut self) -> Self {
        self.add_primitive("+", |args| {
            let mut sum = 0;
            for arg in args {
                if let Value::Integer(i) = arg {
                    sum += i;
                }
            }
            Value::Integer(sum)
        });
        self.add_primitive("-", |args| {
            if args.len() < 2 {
                return Value::Nil;
            }
            if let (Value::Integer(a), Value::Integer(b)) = (&args[0], &args[1]) {
                Value::Integer(a - b)
            } else {
                Value::Nil
            }
        });
        self.add_primitive("*", |args| {
            let mut prod = 1;
            for arg in args {
                if let Value::Integer(i) = arg {
                    prod *= i;
                }
            }
            Value::Integer(prod)
        });
        self.add_primitive("/", |args| {
            if args.len() < 2 {
                return Value::Nil;
            }
            if let (Value::Integer(a), Value::Integer(b)) = (&args[0], &args[1]) {
                if *b == 0 {
                    Value::Nil
                } else {
                    Value::Integer(a / b)
                }
            } else {
                Value::Nil
            }
        });
        self.add_primitive("=", |args| {
            if args.len() < 2 {
                return Value::Boolean(true);
            }
            Value::Boolean(args[0] == args[1])
        });
        self.add_primitive("==", |args| {
            if args.len() < 2 {
                return Value::Boolean(true);
            }
            Value::Boolean(args[0] == args[1])
        });
        self.add_primitive("!=", |args| {
            if args.len() < 2 {
                return Value::Boolean(false);
            }
            Value::Boolean(args[0] != args[1])
        });
        self.add_primitive("<", |args| {
            if args.len() < 2 {
                return Value::Nil;
            }
            if let (Value::Integer(a), Value::Integer(b)) = (&args[0], &args[1]) {
                Value::Boolean(a < b)
            } else {
                Value::Nil
            }
        });
        self.add_primitive(">", |args| {
            if args.len() < 2 {
                return Value::Nil;
            }
            if let (Value::Integer(a), Value::Integer(b)) = (&args[0], &args[1]) {
                Value::Boolean(a > b)
            } else {
                Value::Nil
            }
        });
        self.add_primitive("<=", |args| {
            if args.len() < 2 {
                return Value::Nil;
            }
            if let (Value::Integer(a), Value::Integer(b)) = (&args[0], &args[1]) {
                Value::Boolean(a <= b)
            } else {
                Value::Nil
            }
        });
        self.add_primitive(">=", |args| {
            if args.len() < 2 {
                return Value::Nil;
            }
            if let (Value::Integer(a), Value::Integer(b)) = (&args[0], &args[1]) {
                Value::Boolean(a >= b)
            } else {
                Value::Nil
            }
        });
        self.add_primitive("yin/def", |args| {
            if args.len() < 2 {
                return Value::Nil;
            }
            let key = args[0].clone();
            let val = args[1].clone();
            let mut effect = HashMap::new();
            effect.insert(Value::keyword("effect"), Value::keyword("vm/store-put"));
            effect.insert(Value::keyword("key"), key);
            effect.insert(Value::keyword("val"), val);
            Value::Map(effect)
        });
        self.add_primitive("stream/make", |args| {
            let cap = if !args.is_empty() {
                if let Value::Integer(i) = args[0] {
                    Some(i as usize)
                } else {
                    None
                }
            } else {
                None
            };
            let mut effect = HashMap::new();
            effect.insert(Value::keyword("effect"), Value::keyword("stream/make"));
            effect.insert(Value::keyword("capacity"), cap.map_or(Value::Nil, |c| Value::Integer(c as i64)));
            Value::Map(effect)
        });
        self.add_primitive("stream/put!", |args| {
            if args.len() < 2 { return Value::Nil; }
            let mut effect = HashMap::new();
            effect.insert(Value::keyword("effect"), Value::keyword("stream/put"));
            effect.insert(Value::keyword("stream"), args[0].clone());
            effect.insert(Value::keyword("val"), args[1].clone());
            Value::Map(effect)
        });
        self.add_primitive("stream/next!", |args| {
            if args.is_empty() { return Value::Nil; }
            let mut effect = HashMap::new();
            effect.insert(Value::keyword("effect"), Value::keyword("stream/next"));
            effect.insert(Value::keyword("cursor"), args[0].clone());
            Value::Map(effect)
        });
        self.add_primitive("print", |args| {
            for (i, arg) in args.iter().enumerate() {
                if i > 0 { print!(" "); }
                match arg {
                    Value::String(s) => print!("{}", s),
                    _ => print!("{}", arg),
                }
            }
            Value::Nil
        });
        self.add_primitive("println", |args| {
            for (i, arg) in args.iter().enumerate() {
                if i > 0 { print!(" "); }
                match arg {
                    Value::String(s) => print!("{}", s),
                    _ => print!("{}", arg),
                }
            }
            println!();
            Value::Nil
        });
        self
    }

    fn handle_native_result(&mut self, result: Value, rd: usize, next_control: usize) {
        if let Value::Map(m) = &result {
            if let Some(Value::Keyword(effect_name)) = m.get(&Value::keyword("effect")) {
                match effect_name.as_ref() {
                    "vm/store-put" => {
                        let key = m.get(&Value::keyword("key")).cloned().unwrap_or(Value::Nil);
                        let val = m.get(&Value::keyword("val")).cloned().unwrap_or(Value::Nil);
                        self.store.insert(key, val.clone());
                        self.set_reg(rd, val);
                        self.control = next_control;
                        return;
                    }
                    "stream/make" => {
                        let cap = match m.get(&Value::keyword("capacity")) {
                            Some(Value::Integer(i)) => Some(*i as usize),
                            _ => None,
                        };
                        let stream = crate::stream::RingBufferStream::new(cap);
                        let val = Value::Stream(crate::value::StreamValue(Arc::new(stream)));
                        self.set_reg(rd, val);
                        self.control = next_control;
                        return;
                    }
                    "stream/put" => {
                        let stream_val = m.get(&Value::keyword("stream")).cloned().unwrap();
                        let val = m.get(&Value::keyword("val")).cloned().unwrap_or(Value::Nil);
                        if let Value::Stream(s) = stream_val {
                            let result = s.0.put(val.clone());
                            match result {
                                crate::stream::StreamWriteResult::Ok(_woke) => {
                                    self.set_reg(rd, val);
                                    self.control = next_control;
                                }
                                crate::stream::StreamWriteResult::Full => {
                                    self.blocked = true;
                                }
                            }
                            return;
                        }
                    }
                    "stream/next" => {
                        let cursor_val = m.get(&Value::keyword("cursor")).cloned().unwrap();
                        if let Value::Cursor(mut c) = cursor_val {
                            let result = c.stream.0.next(c.position);
                            match result {
                                crate::stream::StreamReadResult::Ok(val, next_pos) => {
                                    c.position = next_pos;
                                    // In many cases we want to update the original cursor register, 
                                    // but effect-based calls usually result in a value to rd.
                                    // The high-level stream/next! usually returns the value.
                                    self.set_reg(rd, val);
                                    self.control = next_control;
                                }
                                crate::stream::StreamReadResult::Blocked => {
                                    self.blocked = true;
                                }
                                crate::stream::StreamReadResult::End => {
                                    self.set_reg(rd, Value::Nil);
                                    self.control = next_control;
                                }
                                crate::stream::StreamReadResult::Gap => panic!("Gap"),
                            }
                            return;
                        }
                    }
                    _ => panic!("Unhandled effect: {}", effect_name),
                }
            }
        }
        self.set_reg(rd, result);
        self.control = next_control;
    }

    pub fn step(&mut self) {
        if self.halted || self.blocked {
            return;
        }

        if self.control >= self.bytecode.len() {
            self.halted = true;
            return;
        }

        let op_raw = self.bytecode[self.control];
        let op = match Opcode::from_u8(op_raw as u8) {
            Some(op) => op,
            None => panic!("Unknown opcode: {} at {}", op_raw, self.control),
        };

        match op {
            Opcode::Literal => {
                let rd = self.bytecode[self.control + 1] as usize;
                let pool_idx = self.bytecode[self.control + 2] as usize;
                let val = self.pool[pool_idx].clone();
                self.set_reg(rd, val);
                self.control += 3;
            }
            Opcode::LoadVar => {
                let rd = self.bytecode[self.control + 1] as usize;
                let pool_idx = self.bytecode[self.control + 2] as usize;
                let name = &self.pool[pool_idx];
                let val = engine::resolve_var(&self.env, &self.store, &self.primitives, name)
                    .unwrap_or(Value::Nil);
                self.set_reg(rd, val);
                self.control += 3;
            }
            Opcode::Move => {
                let rd = self.bytecode[self.control + 1] as usize;
                let rs = self.bytecode[self.control + 2] as usize;
                let val = self.get_reg(rs);
                self.set_reg(rd, val);
                self.control += 3;
            }
            Opcode::Lambda => {
                let rd = self.bytecode[self.control + 1] as usize;
                let params_idx = self.bytecode[self.control + 2] as usize;
                let reg_count = self.bytecode[self.control + 3] as usize;
                let body_addr = self.bytecode[self.control + 4] as usize;

                let params = match &self.pool[params_idx] {
                    Value::Vector(v) => v.clone(),
                    _ => panic!("Lambda params must be a vector"),
                };

                let closure = Value::Closure(Arc::new(Closure {
                    params,
                    body_addr,
                    reg_count,
                    env: self.env.clone(),
                    bytecode: self.bytecode.clone(),
                    pool: self.pool.clone(),
                    version: self.active_compiled_version,
                }));
                self.set_reg(rd, closure);
                self.control += 5;
            }
            Opcode::Call => {
                let rd = self.bytecode[self.control + 1] as usize;
                let rf = self.bytecode[self.control + 2] as usize;
                let argc = self.bytecode[self.control + 3] as usize;
                let arg_base = self.control + 4;
                let fn_val = self.get_reg(rf);
                let next_control = arg_base + argc;

                match fn_val {
                    Value::NativeFn(native) => {
                        let mut args = Vec::with_capacity(argc);
                        for i in 0..argc {
                            let arg_reg = self.bytecode[arg_base + i] as usize;
                            args.push(self.get_reg(arg_reg));
                        }
                        let result = (native.0)(args);
                        self.handle_native_result(result, rd, next_control);
                    }
                    Value::Closure(clo) => {
                        let new_frame = self.snap_frame(Some(rd), next_control);
                        let new_env =
                            self.bind_closure_env(clo.env.clone(), &clo.params, arg_base, argc);

                        self.regs = Vector::new();
                        for _ in 0..clo.reg_count {
                            self.regs.push_back(Value::Nil);
                        }
                        self.k = Some(new_frame);
                        self.env = new_env;
                        self.bytecode = clo.bytecode.clone();
                        self.pool = clo.pool.clone();
                        self.active_compiled_version = clo.version;
                        self.control = clo.body_addr;
                    }
                    _ => panic!("Cannot call non-function at {}: {:?}", self.control, fn_val),
                }
            }
            Opcode::Tailcall => {
                let rf = self.bytecode[self.control + 2] as usize;
                let argc = self.bytecode[self.control + 3] as usize;
                let arg_base = self.control + 4;
                let fn_val = self.get_reg(rf);
                let next_control = arg_base + argc;

                match fn_val {
                    Value::NativeFn(native) => {
                        let mut args = Vec::with_capacity(argc);
                        for i in 0..argc {
                            let arg_reg = self.bytecode[arg_base + i] as usize;
                            args.push(self.get_reg(arg_reg));
                        }
                        let result = (native.0)(args);
                        let rd = self.bytecode[self.control + 1] as usize;
                        self.handle_native_result(result, rd, next_control);
                    }
                    Value::Closure(clo) => {
                        let new_env =
                            self.bind_closure_env(clo.env.clone(), &clo.params, arg_base, argc);

                        self.regs = Vector::new();
                        for _ in 0..clo.reg_count {
                            self.regs.push_back(Value::Nil);
                        }
                        self.env = new_env;
                        self.bytecode = clo.bytecode.clone();
                        self.pool = clo.pool.clone();
                        self.active_compiled_version = clo.version;
                        self.control = clo.body_addr;
                        // k stays the same for TCO
                    }
                    _ => panic!(
                        "Cannot call non-function (tailcall) at {}: {:?}",
                        self.control, fn_val
                    ),
                }
            }
            Opcode::Return => {
                let rs = self.bytecode[self.control + 1] as usize;
                let result = self.get_reg(rs);

                if let Some(k) = self.k.clone() {
                    self.restore_frame(k, Some(result));
                } else {
                    self.halted = true;
                    self.value = result;
                }
            }
            Opcode::Branch => {
                let rt = self.bytecode[self.control + 1] as usize;
                let then_addr = self.bytecode[self.control + 2] as usize;
                let else_addr = self.bytecode[self.control + 3] as usize;
                let test_val = self.get_reg(rt);

                if test_val.is_truthy() {
                    self.control = then_addr;
                } else {
                    self.control = else_addr;
                }
            }
            Opcode::Jump => {
                let addr = self.bytecode[self.control + 1] as usize;
                self.control = addr;
            }
            Opcode::Gensym => {
                let rd = self.bytecode[self.control + 1] as usize;
                let prefix_idx = self.bytecode[self.control + 2] as usize;
                let prefix = match &self.pool[prefix_idx] {
                    Value::String(s) => s.as_ref(),
                    Value::Symbol(s) => s.as_ref(),
                    _ => "id",
                };
                let id = format!("{}-{}", prefix, self.id_counter);
                self.id_counter += 1;
                self.set_reg(rd, Value::Keyword(Arc::from(id.as_str())));
                self.control += 3;
            }
            Opcode::StoreGet => {
                let rd = self.bytecode[self.control + 1] as usize;
                let key_idx = self.bytecode[self.control + 2] as usize;
                let key = &self.pool[key_idx];
                let val = self.store.get(key).cloned().unwrap_or(Value::Nil);
                self.set_reg(rd, val);
                self.control += 3;
            }
            Opcode::StorePut => {
                let rs = self.bytecode[self.control + 1] as usize;
                let key_idx = self.bytecode[self.control + 2] as usize;
                let key = &self.pool[key_idx];
                let val = self.get_reg(rs);
                self.store.insert(key.clone(), val);
                self.control += 3;
            }
            Opcode::StreamMake => {
                let rd = self.bytecode[self.control + 1] as usize;
                let cap_idx = self.bytecode[self.control + 2] as usize;
                let cap = match &self.pool[cap_idx] {
                    Value::Integer(i) => Some(*i as usize),
                    _ => None,
                };
                let stream = crate::stream::RingBufferStream::new(cap);
                self.set_reg(rd, Value::Stream(crate::value::StreamValue(Arc::new(stream))));
                self.control += 3;
            }
            Opcode::StreamPut => {
                let rs = self.bytecode[self.control + 1] as usize;
                let rt = self.bytecode[self.control + 2] as usize;
                let val = self.get_reg(rs);
                let stream_val = self.get_reg(rt);
                if let Value::Stream(s) = stream_val {
                    let result = s.0.put(val);
                    match result {
                        crate::stream::StreamWriteResult::Ok(_woke) => {
                            // TODO: handle woke entries
                            self.control += 3;
                        }
                        crate::stream::StreamWriteResult::Full => {
                            self.blocked = true;
                            // TODO: register waiter
                        }
                    }
                } else {
                    panic!("Target is not a stream: {:?}", stream_val);
                }
            }
            Opcode::StreamCursor => {
                let rd = self.bytecode[self.control + 1] as usize;
                let rs = self.bytecode[self.control + 2] as usize;
                let stream_val = self.get_reg(rs);
                if let Value::Stream(s) = stream_val {
                    let cursor = Value::Cursor(crate::value::CursorValue {
                        stream: s,
                        position: 0,
                    });
                    self.set_reg(rd, cursor);
                    self.control += 3;
                } else {
                    panic!("Source is not a stream: {:?}", stream_val);
                }
            }
            Opcode::StreamNext => {
                let rd = self.bytecode[self.control + 1] as usize;
                let rs = self.bytecode[self.control + 2] as usize;
                let cursor_val = self.get_reg(rs);
                if let Value::Cursor(mut c) = cursor_val {
                    let result = c.stream.0.next(c.position);
                    match result {
                        crate::stream::StreamReadResult::Ok(val, next_pos) => {
                            c.position = next_pos;
                            self.set_reg(rs, Value::Cursor(c));
                            self.set_reg(rd, val);
                            self.control += 3;
                        }
                        crate::stream::StreamReadResult::Blocked => {
                            self.blocked = true;
                            // TODO: register waiter
                        }
                        crate::stream::StreamReadResult::End => {
                            self.set_reg(rd, Value::Nil);
                            self.control += 3;
                        }
                        crate::stream::StreamReadResult::Gap => {
                            panic!("Stream gap encountered");
                        }
                    }
                } else {
                    panic!("Source is not a cursor: {:?}", cursor_val);
                }
            }
            Opcode::StreamClose => {
                let rd = self.bytecode[self.control + 1] as usize;
                let rs = self.bytecode[self.control + 2] as usize;
                let stream_val = self.get_reg(rs);
                if let Value::Stream(s) = stream_val {
                    let _woke = s.0.close();
                    // TODO: handle woke entries
                    self.set_reg(rd, Value::Nil);
                    self.control += 3;
                } else {
                    panic!("Target is not a stream: {:?}", stream_val);
                }
            }
            Opcode::Park => {
                let rd = self.bytecode[self.control + 1] as usize;
                let frame = self.snap_frame(Some(rd), self.control + 2);
                let id = format!("parked-{}", self.id_counter);
                self.id_counter += 1;
                let parked_id = Value::Keyword(Arc::from(id.as_str()));
                self.parked
                    .insert(parked_id.clone(), Value::ReifiedContinuation(frame));
                self.set_reg(rd, parked_id.clone());
                self.value = parked_id;
                self.halted = true;
            }
            Opcode::Resume => {
                let rs = self.bytecode[self.control + 1] as usize;
                let rt = self.bytecode[self.control + 2] as usize;
                let parked_id = self.get_reg(rs);
                let resume_val = self.get_reg(rt);

                if let Some(Value::ReifiedContinuation(frame)) = self.parked.remove(&parked_id) {
                    self.restore_frame(frame, Some(resume_val));
                    self.halted = false;
                } else {
                    panic!(
                        "Cannot resume: parked continuation not found: {:?}",
                        parked_id
                    );
                }
            }
            Opcode::CurrentCont => {
                let rd = self.bytecode[self.control + 1] as usize;
                let frame = self.snap_frame(Some(rd), self.control + 2);
                self.set_reg(rd, Value::ReifiedContinuation(frame));
                self.control += 2;
            }
            Opcode::DaoStreamApplyCall => {
                let rd = self.bytecode[self.control + 1] as usize;
                let op_idx = self.bytecode[self.control + 2] as usize;
                let op = self.pool[op_idx].clone();
                let argc = self.bytecode[self.control + 3] as usize;
                let arg_base = self.control + 4;

                let mut args = Vec::with_capacity(argc);
                for i in 0..argc {
                    let arg_reg = self.bytecode[arg_base + i] as usize;
                    args.push(self.get_reg(arg_reg));
                }

                let frame = self.snap_frame(Some(rd), arg_base + argc);
                let id = format!("bridge-{}", self.id_counter);
                self.id_counter += 1;
                let parked_id = Value::Keyword(Arc::from(id.as_str()));
                self.parked
                    .insert(parked_id.clone(), Value::ReifiedContinuation(frame));

                // Emit request to :yin/call-in
                if let Some(Value::Stream(s)) = self.store.get(&Value::keyword("yin/call-in")) {
                    let mut req = HashMap::new();
                    req.insert(Value::keyword("dao.stream.apply/id"), parked_id.clone());
                    req.insert(Value::keyword("dao.stream.apply/op"), op);
                    req.insert(
                        Value::keyword("dao.stream.apply/args"),
                        Value::Vector(args.into()),
                    );
                    let _ = s.0.put(Value::Map(req));
                }

                self.set_reg(rd, parked_id.clone());
                self.value = parked_id;
                self.blocked = true;
                self.halted = false;
            }
        }
    }
}
