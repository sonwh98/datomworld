Created-GMT: 2026-09-23 07:53:00 GMT
Created-Local: 2026-09-23 14:53:00 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed: your de Bruijn VM & register VM design thread)

# Task: architect-register-r2-design — author formal specification for Phase R2 (effects and stream forms) of the de Bruijn Register VM

Role: Lead System Architect

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-23 14:53:00 +07 | Status: active | Rationale: owner-directed dispatch to gpt-5.6-sol via Codex to author the formal architectural specification for Register VM Phase R2 before the remaining quota resets

Work in /Users/sto/workspace/datomworld (launch directory; branch master).
This is a DESIGN task: author the comprehensive, normative architectural specification for Phase R2 (effects and stream forms) of the de Bruijn register VM.

## Read first, in full

1. `docs/design/yin.vm.debruijn.register.md`: your own register VM architecture design! R0 and R1 are now fully implemented and verified on the `register-r0` branch (in `/Users/sto/workspace/worktree-register-r0`). Note that Section 6's description of Phase R2 is currently only a high-level 10-line placeholder:
   ```markdown
   ### R2: effects and stream forms

       Existing source: src/cljc/yin/vm/debruijn_register_compile.cljc
       New: test/yin/vm/debruijn_register_effects_test.cljc
       Existing edits: none
       Must not change: dao.stream, lease, waitset, B0-B3 semantics

   Lower stream, gensym, FFI, park, and resume shapes with explicit registers.
   Completion compares effect descriptors and blocked outcomes with the stack
   path without executing a register VM.
   ```
2. `docs/design/yin.vm.debruijn.stack.md`: specifically §4.1 (Engine seam and suspension protocol) and §6 (Phase B4: effects and continuations). Phase B4 has just been implemented in an isolated worktree! Its engine seam additions in `yin.vm.engine` (additive `scheduler-round`, 3-arity `restore-fn [base entry value]`) and `yin.vm.ffi` (`response-wait-entry`) are the exact engine primitives the register VM must share.
3. `docs/design/yin.vm.engine.md`: engine loop, scheduler round, waitset, parking, and continuation restoration protocol.
4. `src/cljc/yin/vm/debruijn_register_code.cljc` (in `/Users/sto/workspace/worktree-register-r0` or referenced from design): the register descriptor `:yin.debruijn.register/*`, opcode table, register-hash (R), and validator.
5. `src/cljc/yin/vm/debruijn_register_compile.cljc` (in `/Users/sto/workspace/worktree-register-r0`): the deterministic linear-scan allocator, resolved-tuples lowerer, and live-set tracking (Section 4.5).

## Architectural Mandate for Phase R2

The owner has authorized authoring the full, normative architectural specification for Phase R2 so that compiler and VM engineers have an unambiguous specification to implement and verify against.

You must design and specify:

### 1. Register-Model Effect and Stream Instruction Set
Define the exact opcodes, operands, and register conventions for all effect-producing and stream operations:
- Stream lifecycle:
  - Stream creation (`:stream-open` / `:stream-make`)
  - Stream consumption (`:stream-next` / `:stream-take`, `:stream-cursor`)
  - Stream production (`:stream-put` / `:stream-write`)
  - Stream teardown (`:stream-close`)
- Primitives & Effects:
  - `:gensym` (destination register, optional prefix operand)
  - `:ffi-call` (target module/method descriptor, argument registers/slice, destination register, error handling)
  - Effect dispatch: how primitive calls that return effect descriptors (e.g. `stream/next!`) are lowered and dispatched.
- Continuations:
  - `:current-continuation` (destination register capturing the active continuation)
  - `:park` (suspension with effect descriptor or waitset entry)
  - `:resume` (resuming a continuation with value or error)

### 2. Register Allocation Across Effect Suspension & Yield Points
- How does the deterministic linear-scan allocator (`yin.vm.debruijn-register-compile`) allocate registers across effect calls that may suspend or yield?
- Register banks: how do argument registers, result registers, and temporaries interact with the live register set at yield points?
- Determinism: ensure that effect lowering maintains the identical alpha-invariance, strict determinism, and cross-host reproducibility mandated in Section 1 and Section 4 of `yin.vm.debruijn.register.md`.

### 3. Continuation Representation & Live-Register-Set Tracking
- In the Stack VM (Phase B4), continuation frames capture the operand stack slice and return PC.
- In the Register VM, specify the precise structure of a reified continuation frame:
  - Frame record shape: does it carry the entire virtual register file or ONLY the live register set (`live`, per Section 4.5 liveness analysis)?
  - Specify the exact serialization/in-memory representation of the register continuation:
    `{:segment <vector> :pc <int> :frames <vector> :regs <map-or-vector> :live <set> :format :yin.debruijn.register :hash <R>}`
  - Justify why discarding dead registers at suspension points prevents memory leaks in long-running stream pipelines and actor loops.

### 4. Engine Seam Integration & Restoration Protocol
- Specification of `restore-fn` for the register machine:
  - How does `(restore-fn base entry val)` restore the register machine?
  - Where is `val` (the resumption value from an FFI response, timer tick, or stream item) written in the restored register file? (Which destination register receives the result of the parked operation?)
  - How are the engine's transient wake stamps (`:value`, `:status`, `:cursor`, `:store-updates`) handled?
  - Handling of error resumptions (when `restore-fn` receives an error or exception).
- Blocked states and waitset entries:
  - Format of blocked records (`:yin/blocked`, `:stream-blocked`, `:ffi-wait`).
  - Integration with `ffi/response-wait-entry` and `engine/scheduler-round`.

### 5. Lowering Rules & Validator Extensions
- Specify how `lower-register` translates resolved AST effect nodes into the new register opcodes.
- Specify how `validate-register-image` must validate effect opcodes (shape, register bounds, operand kinds, live sets).
- Ensure that pure compilation and lowering validation can be tested completely in Phase R2 without requiring the Phase R4 execution kernel to exist yet.

### 6. Acceptance Criteria & Test Obligations for R2
- Define the completion checklist for Phase R2:
  - Effect descriptor equivalence: effect descriptors generated by lowered register code must match the stack VM under the B0 normalizer.
  - Continuation frame structure tests.
  - Deterministic R hashing and cross-host parity for effect-bearing programs.
  - Refusal tests: invalid effect arities, out-of-bounds registers, ill-formed waitset entries refused with named diagnostics.

## Deliverable

Update `docs/design/yin.vm.debruijn.register.md` to incorporate the complete, normative Phase R2 architectural specification (expanding Section 6.2 and adding any necessary specification subsections or opcode table entries), OR produce the complete specification in your report for immediate integration.

Maintain project doc style: 80 columns max, ASCII only, no em dashes, clear section numbering, strict adherence to `datom.world.md` invariants.

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a

Followed by your executive architectural summary and the normative specification.
