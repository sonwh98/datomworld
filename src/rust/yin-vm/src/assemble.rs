use crate::value::Value;
use crate::opcode::Opcode;
use im::HashMap;

pub enum SymbolicInstruction {
    Literal(usize, Value),
    LoadVar(usize, Value),
    Move(usize, usize),
    Lambda(usize, Value, usize, usize), // rd, params, addr, reg_count
    Call(usize, usize, Vec<usize>),
    Tailcall(usize, usize, Vec<usize>),
    Return(usize),
    Branch(usize, usize, usize),
    Jump(usize),
    Gensym(usize, Value),
    StoreGet(usize, Value),
    StorePut(usize, Value),
    StreamMake(usize, Value),
    StreamPut(usize, usize),
    StreamCursor(usize, usize),
    StreamNext(usize, usize),
    StreamClose(usize, usize),
    CurrentCont(usize),
    DaoStreamApplyCall(usize, Value, Vec<usize>),
    Park(usize),
    Resume(usize, usize),
}

pub struct CompiledArtifact {
    pub bytecode: Vec<i32>,
    pub pool: Vec<Value>,
}

pub fn assemble(instructions: &[SymbolicInstruction]) -> CompiledArtifact {
    let mut pool = Vec::new();
    let mut pool_index = HashMap::new();
    let mut bytecode = Vec::new();
    let mut instr_offsets = Vec::with_capacity(instructions.len());
    let mut fixups = Vec::new();

    let mut intern = |v: Value| -> usize {
        if let Some(&idx) = pool_index.get(&v) {
            idx
        } else {
            let idx = pool.len();
            pool.push(v.clone());
            pool_index.insert(v, idx);
            idx
        }
    };

    for instr in instructions {
        instr_offsets.push(bytecode.len());
        match instr {
            SymbolicInstruction::Literal(rd, v) => {
                bytecode.push(Opcode::Literal as i32);
                bytecode.push(*rd as i32);
                bytecode.push(intern(v.clone()) as i32);
            }
            SymbolicInstruction::LoadVar(rd, name) => {
                bytecode.push(Opcode::LoadVar as i32);
                bytecode.push(*rd as i32);
                bytecode.push(intern(name.clone()) as i32);
            }
            SymbolicInstruction::Move(rd, rs) => {
                bytecode.push(Opcode::Move as i32);
                bytecode.push(*rd as i32);
                bytecode.push(*rs as i32);
            }
            SymbolicInstruction::Lambda(rd, params, addr, reg_count) => {
                bytecode.push(Opcode::Lambda as i32);
                bytecode.push(*rd as i32);
                bytecode.push(intern(params.clone()) as i32);
                bytecode.push(*reg_count as i32);
                fixups.push((bytecode.len(), *addr));
                bytecode.push(0);
            }
            SymbolicInstruction::Call(rd, rf, args) => {
                bytecode.push(Opcode::Call as i32);
                bytecode.push(*rd as i32);
                bytecode.push(*rf as i32);
                bytecode.push(args.len() as i32);
                for &arg in args {
                    bytecode.push(arg as i32);
                }
            }
            SymbolicInstruction::Tailcall(rd, rf, args) => {
                bytecode.push(Opcode::Tailcall as i32);
                bytecode.push(*rd as i32);
                bytecode.push(*rf as i32);
                bytecode.push(args.len() as i32);
                for &arg in args {
                    bytecode.push(arg as i32);
                }
            }
            SymbolicInstruction::Return(rs) => {
                bytecode.push(Opcode::Return as i32);
                bytecode.push(*rs as i32);
            }
            SymbolicInstruction::Branch(rt, then_idx, else_idx) => {
                bytecode.push(Opcode::Branch as i32);
                bytecode.push(*rt as i32);
                fixups.push((bytecode.len(), *then_idx));
                bytecode.push(0);
                fixups.push((bytecode.len(), *else_idx));
                bytecode.push(0);
            }
            SymbolicInstruction::Jump(idx) => {
                bytecode.push(Opcode::Jump as i32);
                fixups.push((bytecode.len(), *idx));
                bytecode.push(0);
            }
            SymbolicInstruction::Gensym(rd, prefix) => {
                bytecode.push(Opcode::Gensym as i32);
                bytecode.push(*rd as i32);
                bytecode.push(intern(prefix.clone()) as i32);
            }
            SymbolicInstruction::StoreGet(rd, key) => {
                bytecode.push(Opcode::StoreGet as i32);
                bytecode.push(*rd as i32);
                bytecode.push(intern(key.clone()) as i32);
            }
            SymbolicInstruction::StorePut(rs, key) => {
                bytecode.push(Opcode::StorePut as i32);
                bytecode.push(*rs as i32);
                bytecode.push(intern(key.clone()) as i32);
            }
            SymbolicInstruction::StreamMake(rd, buf) => {
                bytecode.push(Opcode::StreamMake as i32);
                bytecode.push(*rd as i32);
                bytecode.push(intern(buf.clone()) as i32);
            }
            SymbolicInstruction::StreamPut(rs, rt) => {
                bytecode.push(Opcode::StreamPut as i32);
                bytecode.push(*rs as i32);
                bytecode.push(*rt as i32);
            }
            SymbolicInstruction::StreamCursor(rd, rs) => {
                bytecode.push(Opcode::StreamCursor as i32);
                bytecode.push(*rd as i32);
                bytecode.push(*rs as i32);
            }
            SymbolicInstruction::StreamNext(rd, rs) => {
                bytecode.push(Opcode::StreamNext as i32);
                bytecode.push(*rd as i32);
                bytecode.push(*rs as i32);
            }
            SymbolicInstruction::StreamClose(rd, rs) => {
                bytecode.push(Opcode::StreamClose as i32);
                bytecode.push(*rd as i32);
                bytecode.push(*rs as i32);
            }
            SymbolicInstruction::CurrentCont(rd) => {
                bytecode.push(Opcode::CurrentCont as i32);
                bytecode.push(*rd as i32);
            }
            SymbolicInstruction::DaoStreamApplyCall(rd, op, args) => {
                bytecode.push(Opcode::DaoStreamApplyCall as i32);
                bytecode.push(*rd as i32);
                bytecode.push(intern(op.clone()) as i32);
                bytecode.push(args.len() as i32);
                for &arg in args {
                    bytecode.push(arg as i32);
                }
            }
            SymbolicInstruction::Park(rd) => {
                bytecode.push(Opcode::Park as i32);
                bytecode.push(*rd as i32);
            }
            SymbolicInstruction::Resume(rs, rt) => {
                bytecode.push(Opcode::Resume as i32);
                bytecode.push(*rs as i32);
                bytecode.push(*rt as i32);
            }
        }
    }

    for (pos, instr_idx) in fixups {
        bytecode[pos] = instr_offsets[instr_idx] as i32;
    }

    CompiledArtifact { bytecode, pool }
}
