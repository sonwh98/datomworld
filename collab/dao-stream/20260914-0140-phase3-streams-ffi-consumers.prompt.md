Created-GMT: 2026-09-13 18:40:00 GMT
Created-Local: 2026-09-14 01:40:00 +07:00

# Task: Semantic VM Phase 3 (Streams, FFI, observer, consumers)

Role: VM & Integration Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-14 01:40:00 +07:00 | Status: active

The core interpreter (Phase 1) and lowering compiler (Phase 2) are fully complete, approved by the Architect, and merged. 
Phase 1 (by glm-5.3) already implemented the opcodes (`stream-put`, `stream-cursor`, `stream-next`, `stream-close`, `ffi-call`), `park-and-call`, and wait-entry pure-data restoration.

You are tasked with implementing the remainder of Phase 3 deliverables (as defined in `docs/design/yin.vm.semantic.md` §8 Phase 3):
1. **VM tests**: Write the semantic twins of:
   - `ffi_test.cljc` (round trip, missing handler error, correlation mismatch, `full` retention, gap at bridge fatal, history before cursor not skipped) -> `semantic_ffi_test.cljc`
   - `engine_test.cljc` blocked-read and put cases -> `semantic_engine_test.cljc`
   - `stream_observer_test.cljc` driven with the semantic loader -> `semantic_stream_observer_test.cljc`
2. **`yin.repl.core` composition**: 
   - Add `:semantic` to `vm-constructors` (`yin.vm.semantic/create-vm`) and `vm-labels` (`"SemanticVM"`). 
   - In `make-session`, compose `yin.vm.linearize/ast-loader` into the observer's program loader when `:vm-type` is `:semantic` (because the REPL medium carries AST datoms).
3. **REPL shell test**: Add a REPL test selecting `:semantic` in `test/yin/repl/core_test.cljc` (or its twin `semantic_core_test.cljc`).
4. **Continuation-handoff demo**: Update the demo variant (`src/cljs/datomworld/demo/continuation_stream.cljs`) to truly physically ship the segment + parked frame between the two VMs over the stream (instead of just ping-ponging ticks locally), leveraging the new pure-data Universal Continuation Format.

Run tests on JVM and Node.
