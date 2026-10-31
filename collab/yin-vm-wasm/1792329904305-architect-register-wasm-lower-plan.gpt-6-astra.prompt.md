You are the Lead System Architect for the datom.world project.
Design an implementation plan for lowering `yin.vm.debruijn.register` bytecode images to WebAssembly.

Subject of the plan — the source language:
- `src/cljc/yin/vm/debruijn/register.cljc`: the register execution kernel (23 opcodes, contract version 4, "r2"). State is `{:segment :hash :images :pc :frames :free-env :registers :continuation :store :blocked? :halted? :wait-set :ready-queue :parked :id-counter :value ...}`.
- `src/cljc/yin/vm/debruijn_register_code.cljc`: the opcode table (`:const :load-bound :load-free :closure :move :call :branch-false :jump :return :halt :store-get :store-put :define :gensym :stream-make :stream-put :stream-cursor :stream-next :stream-close :current-continuation :park :resume :ffi-call`), image shape `{:bodies [...], :instructions [...]}`, BLAKE3 image identity, `register-image-defect` validator, in-band boundary live sets (`rcode/body-liveness`, `rcode/live-slot-index`).
- `src/cljc/yin/vm/semantic/derive.cljc`: `register-image` already lowers the semantic vector A into register images (de Bruijn resolve, linear-scan allocation with L/T register banks, boundary live sets). The semantic VM is `src/cljc/yin/vm/semantic.cljc`.
- `src/cljc/yin/vm/debruijn_register_effects.cljc`: offset-table row helpers, continuation payload format (`:format :yin.debruijn.register`, `:hash`, `:image`), continuation-defect checks.
- `src/cljc/yin/vm/engine.cljc`: the shared scheduler (`run-loop`, `scheduler-round`, park/wait-set/resume), effects tier, `park-continuation`, `resume-continuation`.
- Design authorities: `docs/design/yin.vm.debruijn.register.md`, `docs/design/yin.vm.semantic.md`, `docs/design/yin.vm.linker.md` (sections 7.3 r6/r7: continuation format checks, offset tables, attach-image), `docs/design/yin.vm.engine.md`.

Essential semantics the lowering must preserve (read the sources; these are the hard parts):
1. De Bruijn frame chain: `:load-bound` reads depth/position from the runtime frame chain (innermost frame last); closures capture `:frames` plus optional `:store-of`.
2. Calls: positional binding with nil-fill/extra-drop; non-tail calls push a return frame carrying `:return-pc`, caller frames, the `:live` register window snapshot and `:dest`; tail calls reuse the caller's frame; `:halt`/final `:return` sets `:value` and halts.
3. First-class continuations: `:current-continuation` reifies a payload `{... :format :yin.debruijn.register :hash :image :pc :frames :regs :dest :continuation ...}`; `:resume` restores via `register-restore`; foreign formats/images are refused (`:continuation-format`). The payload crosses host boundaries (lift/lower in `yin.vm.linker`) as plain data — the wasm side must be able to consume/produce the same payload shape or a qualified refusal.
4. Blocking/effects: `:stream-*` and `:ffi-call` may block; the VM parks a pure-data entry into `:wait-set`/`:parked` and halts/blocks; the host resumes it later via `register-restore` (with the FFI two-step: `:request-sent`, `:call-id`). The wasm lowering must define what "blocked" means for a wasm module (trap? exported state? async embedder protocol?).
5. The store: name-keyed heap map (`:store-get :store-put :define`), per-module store via `:store-of`; gensym counters; the gc roots/heap (`gc-roots` returns continuation, frames, registers, value).
6. Modules: `attach-image`/offset tables — code spaces grow at runtime while continuations in flight keep absolute `:return-pc`s; `spawn-module` starts a fresh VM over an image.

Target constraints (from a prior architecture discussion, treat as agreed):
- WebAssembly's abstract operand stack is a validation-time type artifact; it cannot hold the machine state. The lowerer targets locals + linear memory (or an embedder-provided coremem). Register operands map to wasm locals; the register file snapshot (`:regs` at call/park boundaries) implies a shadow representation in linear memory or spilled locals — decide.
- Unstructured `:jump`/`:branch-false` and `:closure` body pcs inside one linear instruction vector: decide between per-body wasm functions (body = function, pc-precision returns) with a relooper-style structuring pass, vs. one outer interpreter loop with `br_table` dispatch over the instruction vector in memory, vs. hybrid. Analyze tradeoffs (validation stack-height discipline, code size, call/return mapping to wasm `call`/`return_call`, continuation capture across wasm frames — a reified continuation cannot reference the wasm call stack, so any wasm-native `call` lowering must be able to flatten a captured guest continuation back into a resumable state; define exactly how).
- Values are untyped machine data (integers, strings, closures, streams, continuations, nil, booleans): pick a value representation for wasm (e.g. NaN-boxing in i64? tagged i32/i64 with a handle table in linear memory? externref for host-typed values?). Host-typed values (closures/effects are opaque host types in `yin.vm.values`) and `make-stream`/bridge FFI must round-trip.
- The repo is CLJC (JVM, Node via shadow-cljs, Dart via ClojureDart). Wasm would be a new lane; state which toolchain you assume (wasm-ld/wat hand-assembly, Binaryen, a ClojureScript->wasm path, or emitting wasm from the JVM host) and what the embedder protocol looks like (JS glue for streams/FFI/wait-sets).

Deliverable — write the plan to:
collab/1792329904305-architect-register-wasm-lower-plan.gpt-6-astra.findings.md

Required plan sections:
1. Value representation and linear-memory layout (registers, frames, store, continuation payloads, handle tables for host-typed values).
2. Code generation strategy: per-opcode lowering table (all 23 opcodes), body/function mapping, control-flow structuring algorithm, tail-call treatment (note `return_call` support assumptions).
3. Continuation capture and resume: exact mapping between `{:format :yin.debruijn.register ...}` payloads and wasm-side resumable state; the refusal rules preserved (`:continuation-format`, plain-data resume values).
4. Blocking/effects/FFI protocol: what a wasm instantiation does at `:park`/`:stream-*`/`:ffi-call`, and the resume entry points the embedder calls.
5. Modules and code growth: attach-image/offset-table semantics in wasm terms (instantiation, indirect call tables, patching).
6. Phased implementation recipe with slices, per-slice acceptance tests (they must run against the existing golden corpora in `test/yin/vm/semantic/corpus.cljc` and `test/yin/vm/debruijn/register_test.cljc` — the wasm lane must produce identical `:value`/store/parking behavior), and explicit non-goals.
7. Risks and open questions.

Do not write any source code files. Read-only exploration of the repo plus writing the single findings file above.
