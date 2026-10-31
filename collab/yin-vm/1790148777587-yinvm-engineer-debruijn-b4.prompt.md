Created-GMT: 2026-09-23 07:32:57 GMT
Created-Local: 2026-09-23 14:32:57 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: fe146f5f-4241-4570-a6a5-b2321369425c

# Task: debruijn-vm-b4 — Effects and Continuations for the de Bruijn Stack VM

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-23 14:32:57 +07 | Status: active | Rationale: Phase B4 (effects, streams, and continuations across the engine seam) is the de Bruijn VM epic's longest pole; requires deep CESK machine semantics and exact continuation layout.

Work in /Users/sto/workspace/worktree-debruijn-b4 (your launch directory; branch debruijn-b4, HEAD ba88f769). Do NOT stage, commit, merge, or push.

---

## Context & Governing Documents

Read these governing documents completely first:
1. `docs/design/yin.vm.debruijn.stack.md` (authoritative, especially Section 4.1 "Engine seam (B4)", Section 5 "Effects and equivalence boundaries", and Section 6.4 "B4: effects and continuations")
2. `docs/design/yin.vm.engine.md` (authoritative, especially Section 4 "FFI two-step", Section 6 "Why not a protocol", and Section 7 "Proposed engine edits")
3. `docs/design/datom.world.md` (architectural invariants, CESK state discipline)

This is Phase B4 ("Effects and continuations") of the de Bruijn Stack VM epic. B3 implemented the pure-program kernel (`src/cljc/yin/vm/debruijn/stack.cljc`). B4 extends this machine to support streams, primitives, FFI, gensym, `:vm/current-continuation`, `:vm/park`, and resume against the shared `yin.vm.engine` scheduler.

---

## File Box (Strictly Bounded)

Allowed edits and new files:
- **EDIT**: `src/cljc/yin/vm/debruijn/stack.cljc` (effects, stream operations, primitives, FFI, gensym, continuation/park/resume)
- **EDIT**: `src/cljc/yin/vm/engine.cljc` (strictly additive: `scheduler-round`, 3-arity `restore-fn` in `resume-from-run-queue` per `engine.md` Section 7)
- **EDIT**: `src/cljc/yin/vm/ffi.cljc` (strictly additive: `response-wait-entry` per `engine.md` Section 7)
- **EDIT**: `test/yin/vm/engine_test.cljc` (fake restore 3-arity parameter; tests for the two additive engine functions)
- **NEW**: `test/yin/vm/debruijn/stack_effects_test.cljc` (comprehensive B4 effect, stream, FFI, and continuation test suite)

Must **NOT** change:
- `dao.stream` protocols, lease, waitset
- Named effect rules
- Merged projection namespace (`yin.vm.debruijn` / `yin.vm.pipeline`)
- `yin.vm.semantic` and `yin.vm.ast_walker` (these are B4's parity oracles; their migration to the engine additions is a separate commit after B4)
- Existing engine outcomes, ready-entry keys, or wait-entry keys
- Do NOT edit files outside this file box.

---

## Directives & Specifications

### 1. Engine Edits (`src/cljc/yin/vm/engine.cljc` & `src/cljc/yin/vm/ffi.cljc`)
Implement exactly the three additive edits from `docs/design/yin.vm.engine.md` Section 7:
1. **`engine/scheduler-round [state restore-fn]`**:
   `check-wait-set`, then `resume-from-run-queue` with `restore-fn`, returning the polled state when nothing woke. (This is `yin.vm.semantic/scheduler-round` moved verbatim with its restore made a parameter).
2. **Three-arity restore in `resume-from-run-queue`**:
   `resume-from-run-queue` calls `(restore-fn base entry (:value entry))` so that both engine call sites pass three arguments `[base entry val]`. Update fake restore functions in `test/yin/vm/engine_test.cljc` to accept 3 args.
3. **`ffi/response-wait-entry [entry call-id]`**:
   The call-out reader entry for an arbitrary register payload, built by removing `:request-sent`, `:op`, and `:datom` and adding `:call-id`, `:reason :next`, the call-out `:cursor-ref`, and `:stream-id`.
4. **Engine Tests**:
   Add tests in `test/yin/vm/engine_test.cljc` verifying:
   - `scheduler-round` returns polled state when nothing wakes, and restored state when something wakes.
   - `response-wait-entry` preserves arbitrary register payload verbatim.

### 2. Stack VM Effects & Continuations (`src/cljc/yin/vm/debruijn/stack.cljc`)
Implement the engine seam per `docs/design/yin.vm.debruijn.stack.md` Section 4.1:
1. **Bookkeeping**:
   `:status` is replaced by the engine's `:halted?` and `:blocked?`. The VM record/state carries `:wait-set`, `:ready-queue`, `:parked`, `:id-counter`, `:value`, and `:make-stream`. `halted?` becomes `engine/halted-with-empty-queue?`, `blocked?` becomes `engine/vm-blocked?`, and `run` becomes `engine/run-loop` with `engine/active-continuation?`, the step function, and `engine/scheduler-round` bound to this VM's restore.
2. **Value**:
   `value` returns `(:value vm)`. Halting and returning on empty continuation writes stack top into `:value`.
3. **Register Payload**:
   Every parked record and reified continuation must carry:
   `{:segment segment, :pc pc, :frames frames, :stack stack, :continuation continuation, :format :yin.debruijn.code, :hash hash}`
   - `:format` is `:yin.debruijn.code`
   - `:hash` is the loaded image's H (SHA-256)
   - `:segment` is the image segment/identity.
4. **Restore (`stack-restore [base entry val]`)**:
   - Validates that entry's `:format` equals `:yin.debruijn.code` and `:hash` equals the loaded image's H. Refuses with qualified `:continuation-format` outcome if they do not match.
   - Restores registers from entry and conjes `val` onto the restored `:stack`.
   - Handles the FFI two-step on entry keys `:request-sent` and `:call-id` using `ffi/call-result` and `ffi/response-wait-entry`.
5. **Builders**:
   Per blocking instruction, `:stream/put` and `:stream/next` builders closing over post-instruction registers (`pc` advanced, operands popped), returning payload merged with `:reason` and `:stream-id`/`:cursor-ref`.
6. **Continuation Opcodes**:
   - `:current-continuation` pushes `{:type :reified-continuation ...payload}`.
   - `:park` delegates through `engine/park-continuation` with the payload.
   - `:resume` delegates through `engine/resume-continuation` with `stack-restore`.
7. **Stream Operations, Primitives, FFI, Gensym**:
   Implement execution parity with the named semantic VM for all effect instructions.

### 3. Test Suite (`test/yin/vm/debruijn/stack_effects_test.cljc`)
Provide comprehensive tests:
- Engine additions and 3-arity restore.
- Stream put/take/next effect round-trips and blocking/wake behavior.
- FFI two-step calls and responses.
- Gensym determinism and counter behavior.
- `:current-continuation`, `:park`, and `:resume` within the stack VM.
- Refusal of cross-model or mismatched `:hash` continuations with `:continuation-format`.
- Parity with `yin.vm.semantic` on effects and stream outcomes (using B0 normalizer conventions).

---

## Verification

Before declaring completion:
1. Run focused tests in the worktree:
   ```sh
   clojure -M:test -n yin.vm.debruijn.stack-effects-test
   clojure -M:test -n yin.vm.debruijn.stack-test
   clojure -M:test -n yin.vm.engine-test
   ```
2. Lint:
   ```sh
   clj -M:kondo --lint src/cljc/yin/vm/debruijn/stack.cljc src/cljc/yin/vm/engine.cljc src/cljc/yin/vm/ffi.cljc test/yin/vm/debruijn/stack_effects_test.cljc test/yin/vm/engine_test.cljc
   ```
3. Inspect `git status` and `git diff` inside `/Users/sto/workspace/worktree-debruijn-b4` to ensure strictly bounded edits.

---

## Response Format

Begin your response exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: fe146f5f-4241-4570-a6a5-b2321369425c
```

Report:
1. Changed and new files with diffstat.
2. Exact test outcomes and assertion counts across all test suites.
3. Details of the engine seam implementation and continuation payload structure.
4. Parity verification results against oracle semantics.
5. Any notes or follow-ups for Phase B5.
