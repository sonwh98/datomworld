pub mod value;
pub mod opcode;
pub mod vm;
pub mod engine;
pub mod assemble;
pub mod stream;
pub mod parser;
pub mod compiler;
pub mod yang;

pub use value::{Value, Closure, Frame, NativeFn, StreamValue, CursorValue};
pub use opcode::Opcode;
pub use vm::RegisterVM;
pub use assemble::{SymbolicInstruction, assemble};

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn test_literal() {
        let instrs = vec![
            SymbolicInstruction::Literal(0, Value::Integer(42)),
            SymbolicInstruction::Return(0),
        ];
        let artifact = assemble(&instrs);
        let mut vm = RegisterVM::new();
        vm.bytecode = Arc::new(artifact.bytecode);
        vm.pool = Arc::new(artifact.pool);
        vm.halted = false;
        
        while !vm.halted {
            vm.step();
        }
        
        assert_eq!(vm.value, Value::Integer(42));
    }

    #[test]
    fn test_arithmetic() {
        // (+ 10 20)
        let instrs = vec![
            SymbolicInstruction::Literal(1, Value::Integer(10)),
            SymbolicInstruction::Literal(2, Value::Integer(20)),
            SymbolicInstruction::LoadVar(3, Value::symbol("+")),
            SymbolicInstruction::Call(0, 3, vec![1, 2]),
            SymbolicInstruction::Return(0),
        ];
        let artifact = assemble(&instrs);
        let mut vm = RegisterVM::new().with_std_primitives();
        vm.bytecode = Arc::new(artifact.bytecode);
        vm.pool = Arc::new(artifact.pool);
        vm.halted = false;
        
        while !vm.halted {
            vm.step();
        }
        
        assert_eq!(vm.value, Value::Integer(30));
    }

    #[test]
    fn test_tco_countdown() {
        // ((fn [self n] (if (< n 1) 0 (self self (- n 1)))) <same-fn> 1000)
        let instrs = vec![
            // 0: Lambda r1 [self, n]
            SymbolicInstruction::Lambda(1, Value::Vector(im::vector![Value::symbol("self"), Value::symbol("n")]), 2, 8),
            // 1: Jump to 16 (after body)
            SymbolicInstruction::Jump(16),
            // 2: Body starts here
            // 2: LoadVar r2 <
            SymbolicInstruction::LoadVar(2, Value::symbol("<")),
            // 3: LoadVar r3 n
            SymbolicInstruction::LoadVar(3, Value::symbol("n")),
            // 4: Literal r4 1
            SymbolicInstruction::Literal(4, Value::Integer(1)),
            // 5: Call r2 r2 [r3, r4]
            SymbolicInstruction::Call(2, 2, vec![3, 4]),
            // 6: Branch r2 7 9
            SymbolicInstruction::Branch(2, 7, 9),
            // 7: Literal r0 0
            SymbolicInstruction::Literal(0, Value::Integer(0)),
            // 8: Return r0
            SymbolicInstruction::Return(0),
            // 9: LoadVar r2 self
            SymbolicInstruction::LoadVar(2, Value::symbol("self")),
            // 10: LoadVar r3 self
            SymbolicInstruction::LoadVar(3, Value::symbol("self")),
            // 11: LoadVar r4 n
            SymbolicInstruction::LoadVar(4, Value::symbol("n")),
            // 12: LoadVar r5 -
            SymbolicInstruction::LoadVar(5, Value::symbol("-")),
            // 13: Call r6 r5 [r4, Literal 1] -- need Literal 1 in a reg
            SymbolicInstruction::Literal(7, Value::Integer(1)),
            SymbolicInstruction::Call(6, 5, vec![4, 7]),
            // 14: Tailcall r0 r2 [r3, r6]
            SymbolicInstruction::Tailcall(0, 2, vec![3, 6]),
            // 15: Main starts here
            // 15: Literal r2 1000
            SymbolicInstruction::Literal(2, Value::Integer(1000)),
            // 16: Call r0 r1 [r1, r2]
            SymbolicInstruction::Call(0, 1, vec![1, 2]),
            // 17: Return r0
            SymbolicInstruction::Return(0),
        ];
        let artifact = assemble(&instrs);
        let mut vm = RegisterVM::new().with_std_primitives();
        vm.bytecode = Arc::new(artifact.bytecode);
        vm.pool = Arc::new(artifact.pool);
        vm.halted = false;
        
        // Initial setup to start execution from instruction 0
        vm.control = 0;

        let mut steps = 0;
        while !vm.halted && steps < 100000 {
            vm.step();
            steps += 1;
        }
        
        assert_eq!(vm.value, Value::Integer(0));
        assert!(steps > 1000);
    }

    #[test]
    fn test_stream_basic() {
        // (let [s (stream/make 10)]
        //   (stream/put s 42)
        //   (let [c (stream/cursor s)]
        //     (stream/next c)))
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
        vm.bytecode = Arc::new(artifact.bytecode);
        vm.pool = Arc::new(artifact.pool);
        vm.halted = false;
        
        while !vm.halted && !vm.blocked {
            vm.step();
        }
        
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
        vm.bytecode = Arc::new(artifact.bytecode);
        vm.pool = Arc::new(artifact.pool);
        vm.halted = false;
        
        while !vm.halted {
            vm.step();
        }
        
        let parked_id = vm.value.clone();
        assert!(matches!(parked_id, Value::Keyword(_)));
        
        let resume_instrs = vec![
            SymbolicInstruction::Literal(1, parked_id.clone()),
            SymbolicInstruction::Literal(2, Value::Integer(42)),
            SymbolicInstruction::Resume(1, 2),
            SymbolicInstruction::Return(0),
        ];
        let resume_artifact = assemble(&resume_instrs);
        vm.bytecode = Arc::new(resume_artifact.bytecode);
        vm.pool = Arc::new(resume_artifact.pool);
        vm.control = 0;
        vm.halted = false;
        
        while !vm.halted {
            vm.step();
        }
        
        assert_eq!(vm.value, Value::Integer(42));
    }
}
