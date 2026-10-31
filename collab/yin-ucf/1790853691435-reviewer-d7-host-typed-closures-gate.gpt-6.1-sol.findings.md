Completed-GMT: 2026-10-01 11:23:38 GMT
Completed-Local: 2026-10-01 18:23:38 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0f732-d45c-7302-8cf2-744f7958d3d1


- **P1 | src/cljc/yin/repl.cljc:398 | Captured payload leaks through guest printing.** `quote-symbols` unwraps closures and continuations recursively. Guest `print`, `println`, and `prn` use this formatter and emit the environment, frames, and captured references onto the output stream. JVM `toString` also exposes payloads at `values.cljc:42,69`. **Fix:** render opaque kind markers on guest-accessible paths, including nested values and string conversion. Keep any detailed inspection behind an explicitly trusted host interface.

- **P1 | src/cljc/yin/vm/ast_walker.cljc:1223; src/cljc/yin/vm/semantic.cljc:1036 | Lowering bypasses binder validation.** Both named kernels mint receiver-owned closures using unchecked `:yin.k/params` from the wire marker. A marker containing `[:yin.k/store-of]` can therefore produce an applicable closure whose argument overwrites lexical store context. Normal AST validation does not validate these marker parameters. **Fix:** validate parameters before minting and require them to match the attached lambda’s parameters. This is separate from the deferred slice B origin/store check.

- **P2 | src/cljc/yin/vm.cljc:150 | Non-symbol refusal misses `nil` and `false`.** `some` returns the invalid parameter itself; falsy parameters consequently escape `when-let`. **Fix:** return a truthy wrapper identifying the invalid parameter, then classify its contained value. Add coverage for both falsy binders.

Concerns 1–8:

1. **CLJD:** unresolved pending the orchestrator’s lane. No four-host sign-off without evidence for protocols, field access, and non-lookup `get`.
2. **GC protocol:** acceptable coordinated internal change. The four kernels use the new contract; external implementations require migration.
3. **`machine-data?`:** accepting a foreign typed value as inert resume data is sound provided application and lift continue refusing it. Acceptance does not grant ownership.
4. **Raw-state transfer:** acceptable only as trusted state restoration within the same owner domain. Different-owner transfer requires supported lowering.
5. **Same-secret ownership:** matches the accepted design, including shared `nil` ownership. Composition must treat equal secrets as one ownership domain.
6. **`py/numeric?`:** approved narrowing; implements the owner’s accepted `data/number?` recommendation.
7. **Unused walker path:** no additional actionable finding; using the classifier there is consistent.
8. **Realized primitive results:** accepted architectural rule; this patch adds no enforcement.

**Owner decisions:** structural equality/hash, sealed data references, parameter refusal now/store-context move later, and both data predicates remain accepted. The plural namespace deviation is justified by the reported CLJS clash. Payload-based printing follows the brief but conflicts with the requested security opacity; that conflict needs correction.

The shared classifier blocks forged maps and different-owner raw application. Constructor/spawn ownership propagation and two-mode tracing show no additional actionable issue by inspection. Structural equality/hash agree within each implemented host block. Slice B’s known marker origin/store vulnerability remains deferred; isolation is incomplete until it lands.

Read-only review completed. No files edited; orchestrator checks were not rerun.

Verdict: REQUEST CHANGES  
Sign-off: WITHHELD
