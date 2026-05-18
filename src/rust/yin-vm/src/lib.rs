pub mod value;
pub mod opcode;
pub mod vm;
pub mod engine;
pub mod assemble;
pub mod stream;
pub mod parser;
pub mod compiler;
pub mod yang;

pub use assemble::{assemble, SymbolicInstruction};
pub use opcode::Opcode;
pub use value::{Closure, CursorValue, Frame, NativeFn, StreamValue, Value};
pub use vm::RegisterVM;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::stream::{DaoStream, RingBufferStream, StreamReadResult};
    use std::sync::Arc;
    use im::HashMap;

    fn literal_node(v: Value) -> Value {
        let mut m = HashMap::new();
        m.insert(Value::keyword("type"), Value::keyword("literal"));
        m.insert(Value::keyword("value"), v);
        Value::Map(m)
    }

    fn variable_node(name: &str) -> Value {
        let mut m = HashMap::new();
        m.insert(Value::keyword("type"), Value::keyword("variable"));
        m.insert(Value::keyword("name"), Value::symbol(name));
        Value::Map(m)
    }

    fn application_node(op: Value, operands: Vec<Value>) -> Value {
        let mut m = HashMap::new();
        m.insert(Value::keyword("type"), Value::keyword("application"));
        m.insert(Value::keyword("operator"), op);
        m.insert(Value::keyword("operands"), Value::Vector(operands.into()));
        Value::Map(m)
    }

    fn load_and_run(vm: &mut RegisterVM, artifact: crate::assemble::CompiledArtifact, reg_count: usize) {
        vm.load_artifact(artifact, reg_count);
        while !vm.halted && !vm.blocked {
            vm.step();
        }
    }

    #[test]
    fn test_literal() {
        let instrs = vec![
            SymbolicInstruction::Literal(0, Value::Integer(42)),
            SymbolicInstruction::Return(0),
        ];
        let artifact = assemble(&instrs);
        let mut vm = RegisterVM::new();
        load_and_run(&mut vm, artifact, 1);
        assert_eq!(vm.value, Value::Integer(42));
    }

    #[test]
    fn test_arithmetic() {
        let instrs = vec![
            SymbolicInstruction::Literal(1, Value::Integer(10)),
            SymbolicInstruction::Literal(2, Value::Integer(20)),
            SymbolicInstruction::LoadVar(3, Value::symbol("+")),
            SymbolicInstruction::Call(0, 3, vec![1, 2]),
            SymbolicInstruction::Return(0),
        ];
        let artifact = assemble(&instrs);
        let mut vm = RegisterVM::new().with_std_primitives();
        load_and_run(&mut vm, artifact, 4);
        assert_eq!(vm.value, Value::Integer(30));
    }

    #[test]
    fn test_tco_countdown() {
        let instrs = vec![
            SymbolicInstruction::Lambda(1, Value::Vector(im::vector![Value::symbol("self"), Value::symbol("n")]), 2, 8),
            SymbolicInstruction::Jump(16),
            SymbolicInstruction::LoadVar(2, Value::symbol("<")),
            SymbolicInstruction::LoadVar(3, Value::symbol("n")),
            SymbolicInstruction::Literal(4, Value::Integer(1)),
            SymbolicInstruction::Call(2, 2, vec![3, 4]),
            SymbolicInstruction::Branch(2, 7, 9),
            SymbolicInstruction::Literal(0, Value::Integer(0)),
            SymbolicInstruction::Return(0),
            SymbolicInstruction::LoadVar(2, Value::symbol("self")),
            SymbolicInstruction::LoadVar(3, Value::symbol("self")),
            SymbolicInstruction::LoadVar(4, Value::symbol("n")),
            SymbolicInstruction::LoadVar(5, Value::symbol("-")),
            SymbolicInstruction::Literal(7, Value::Integer(1)),
            SymbolicInstruction::Call(6, 5, vec![4, 7]),
            SymbolicInstruction::Tailcall(0, 2, vec![3, 6]),
            SymbolicInstruction::Literal(2, Value::Integer(1000)),
            SymbolicInstruction::Call(0, 1, vec![1, 2]),
            SymbolicInstruction::Return(0),
        ];
        let artifact = assemble(&instrs);
        let mut vm = RegisterVM::new().with_std_primitives();
        load_and_run(&mut vm, artifact, 32);
        assert_eq!(vm.value, Value::Integer(0));
    }

    #[test]
    fn test_stream_basic() {
        let instrs = vec![
            SymbolicInstruction::StreamMake(2, Value::Integer(10)),
            SymbolicInstruction::Literal(3, Value::Integer(42)),
            SymbolicInstruction::StreamPut(3, 2),
            SymbolicInstruction::StreamCursor(4, 2),
            SymbolicInstruction::StreamNext(0, 4),
            SymbolicInstruction::Return(0),
        ];
        let artifact = assemble(&instrs);
        let mut vm = RegisterVM::new();
        load_and_run(&mut vm, artifact, 10);
        assert_eq!(vm.value, Value::Integer(42));
    }

    #[test]
    fn test_park_resume() {
        let instrs = vec![
            SymbolicInstruction::Park(0),
            SymbolicInstruction::Return(0),
        ];
        let artifact = assemble(&instrs);
        let mut vm = RegisterVM::new();
        load_and_run(&mut vm, artifact, 1);
        
        let parked_id = vm.value.clone();
        let resume_instrs = vec![
            SymbolicInstruction::Literal(1, parked_id.clone()),
            SymbolicInstruction::Literal(2, Value::Integer(42)),
            SymbolicInstruction::Resume(1, 2),
            SymbolicInstruction::Return(0),
        ];
        let resume_artifact = assemble(&resume_instrs);
        vm.load_artifact(resume_artifact, 3);
        while !vm.halted && !vm.blocked {
            vm.step();
        }
        assert_eq!(vm.value, Value::Integer(42));
    }

    #[test]
    fn test_macro_expand_executes_expansion_instead_of_returning_ast() {
        let mut vm = RegisterVM::new().with_std_primitives();
        vm.add_primitive("expand-to-add", |_args| {
            application_node(
                variable_node("+"),
                vec![literal_node(Value::Integer(1)), literal_node(Value::Integer(2))],
            )
        });

        let mut macro_expand = HashMap::new();
        macro_expand.insert(Value::keyword("type"), Value::keyword("yin/macro-expand"));
        macro_expand.insert(Value::keyword("operator"), variable_node("expand-to-add"));
        macro_expand.insert(Value::keyword("operands"), Value::Vector(im::vector![]));

        let (instrs, reg_count) = vm.compile_uat(&Value::Map(macro_expand));
        let artifact = assemble(&instrs);
        load_and_run(&mut vm, artifact, reg_count);

        assert_eq!(vm.value, Value::Integer(3));
    }

    #[test]
    fn test_dao_stream_apply_call_emits_request_to_call_in_stream() {
        let mut vm = RegisterVM::new();
        let call_in = Arc::new(RingBufferStream::new(Some(8)));
        vm.store.insert(
            Value::keyword("yin/call-in"),
            Value::Stream(StreamValue(call_in.clone())),
        );

        let instrs = vec![
            SymbolicInstruction::Literal(1, Value::Integer(41)),
            SymbolicInstruction::Literal(2, Value::Integer(1)),
            SymbolicInstruction::DaoStreamApplyCall(0, Value::keyword("op/add"), vec![1, 2]),
            SymbolicInstruction::Return(0),
        ];
        let artifact = assemble(&instrs);
        load_and_run(&mut vm, artifact, 3);

        assert!(vm.blocked);

        let (request, next_pos) = match DaoStream::next(&*call_in, 0) {
            StreamReadResult::Ok(value, pos) => (value, pos),
            other => panic!("expected bridge request on :yin/call-in, got {:?}", other),
        };
        assert_eq!(next_pos, 1);

        let parked_id = vm.get_reg(0);
        let mut expected = HashMap::new();
        expected.insert(Value::keyword("dao.stream.apply/id"), parked_id);
        expected.insert(Value::keyword("dao.stream.apply/op"), Value::keyword("op/add"));
        expected.insert(
            Value::keyword("dao.stream.apply/args"),
            Value::Vector(im::vector![Value::Integer(41), Value::Integer(1)]),
        );

        assert_eq!(request, Value::Map(expected));
    }

    #[test]
    fn test_defn_compilation_and_execution() {
        let mut vm = RegisterVM::new().with_std_primitives();
        // (defn inc [n] (+ n 1))
        let form = Value::Vector(im::vector![
            Value::symbol("defn"),
            Value::symbol("inc"),
            Value::Vector(im::vector![Value::symbol("n")]),
            Value::Vector(im::vector![
                Value::symbol("+"),
                Value::symbol("n"),
                Value::Integer(1)
            ]),
        ]);
        let uat = compiler::compile(form);
        let (instrs, reg_count) = vm.compile_uat(&uat);
        let artifact = assemble(&instrs);
        load_and_run(&mut vm, artifact, reg_count);

        // Check if 'inc' is in the store
        let inc = vm.store.get(&Value::symbol("inc")).unwrap();
        assert!(matches!(inc, Value::Closure(_)));

        // Now run (inc 10)
        let call_form = Value::Vector(im::vector![Value::symbol("inc"), Value::Integer(10)]);
        let call_uat = compiler::compile(call_form);
        let (call_instrs, call_reg_count) = vm.compile_uat(&call_uat);
        let call_artifact = assemble(&call_instrs);
        load_and_run(&mut vm, call_artifact, call_reg_count);

        assert_eq!(vm.value, Value::Integer(11));
    }
}
