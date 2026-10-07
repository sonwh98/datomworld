```text
Completed-GMT: 2026-09-13 04:25:00 GMT
Completed-Local: 2026-09-13 11:25:00 +07
Coding-Agent: glm
Session-ID: dcda6fce-9c4e-4e07-8311-57fd6a7513d9
Role: VM Runtime | Model: glm-5.3
```

# Systems & VM Runtime Review — Unified Macro Architecture on `yin.vm`

**Verdict: no P1 blockers. Three P2 must-address items, all spec-completeness gaps an implementer would hit on the spec's own acceptance tests. The architecture itself is sound and unusually code-accurate: every file/line reference I checked (`ast_walker.cljc:390-500` for the node `case`, the `:m`/`:id-start` options on `ast->datoms-with-root`, the `primitives` list, the `index-datoms` heuristic, the cljd catch form) matches the tree on `dao.stream-redesign-v2`.** Baseline fact confirmed by inventory: `yin.vm.{semantic,linearize,stack,macro}` do not exist in code (v1 deleted in `d8b27a5`); the walker is the sole evaluator, so all semantic/stack sections review a spec-against-spec contract — which is what Phase gating in §7 correctly assumes.

## Findings

### [P2 — must address] 1. The walker changes have inlined twins in the hot loop that the spec never names

The spec places three walker behaviours in `cesk-transition` / `apply-function` and states the hot loop "does not gain a case" (§3.6, §4.1). That is true of the *node* case only. `ast-walker-run-active-continuation` (ast_walker.cljc:519-686) **inlines its own copies** of the exact code paths the spec modifies:

- Closure application: the empty-operands apply at ast_walker.cljc:540-562 and the complete-operands apply at ast_walker.cljc:580-605 each have an inline `cond (= :closure (:type fn-value)) / (fn? …) / :else throw` that does **not** go through `apply-function`. Adding the decision-8 rejection (`:macro?` closures → error) only to `apply-function` (ast_walker.cljc:211-224) leaves the primary execution path — `vm/run` — silently applying macro closures as functions, exactly what decision 8 exists to prevent.
- The `:lambda` arm is also inlined (ast_walker.cljc:639-646) and constructs the closure map directly; the §2.3 three-`assoc` flag copy (`:macro? :phase-policy :eid`) must land there too, or a macro defined via `yin/def` produces a closure without `:macro?` under the hot loop, and `resolve-macro`'s "must be a closure with `:macro?`" fails as `:not-a-macro`.

The spec's own Phase 2 tests would catch both (the "macro closure applied as a function → error" and "runtime macro defined and called in one program" tests run through `vm/run`), so this cannot ship silently — but the spec should name the three hot-loop sites so the implementer does not discover them as test failures. One sentence in §2.3 and one in Appendix B suffice.

### [P2 — must address] 2. No tail-marking propagation from the call site to the ephemeral segment's lowering

§4.2's splice equations handle the frame at the `:macro-call` itself (non-tail pushes `ret(seg,pc+1,E,|St|)`, tail pushes nothing) — that part is correct and matches the semantic spec's `tailcall` rule, and K-empty `:return` correctly halts with the expansion's value for a main-level tail call. But O(1) continuation depth in a loop whose tail is a macro call needs **more** than not pushing a frame at the splice: the recursive call *inside* the expansion must lower as a `tailcall`, and nothing in the spec connects the call site's `:tail?` to the lowering of the expansion root. `lower-ast` reads `:yin/tail?` off AST nodes (semantic spec §5.3); the macro's output root (built via `yin/application`, which sets no `:tail?`) is unmarked, so `lower-ast ast' {:terminator :return …}` lowers the root spine as ordinary `:call` → one frame per iteration → the Phase 3 acceptance test ("tail `:macro-call` in a tail-recursive loop keeps `k` depth 0 over 10⁵ iterations") is unsatisfiable as specified.

The fix is the spec's own medicine: v1's `mark-tail!` treatment — which §2.4 correctly demands for `yin/make-lambda` bodies (recurse through `:if` branches and immediately-applied lambdas) — must equally apply to the spliced root when the call site was `:tail?`, either as an expander-side marking pass on `out-ast`'s root spine or a `:root-tail?` option to `lower-ast`. Appendix B warns about exactly this class of bug for `yin/make-lambda` but omits the splice site, which is the one place the semantic VM cannot get TCO for free. The walker *is* immune — verified: `apply-function` runs every closure body against the post-application `k` (ast_walker.cljc:223), so the walker has unconditional structural TCO and §4.1(e)'s "tail position needs no treatment" is correct for it.

### [P2 — must address] 3. The macro-body sandbox contradicts itself on store visibility and understates its own restriction

§4.2 justifies reserving (not defaulting) memoization because "a runtime macro may read the store and must be allowed to see it change." Under the construction the spec itself defines, it cannot: `macro-eval` builds `create-vm {:env env :primitives (merge vm/primitives prelude)}` (§3.2) — a fresh store, no bridge, and `resolve-var` walks only the throwaway's env/store/primitives/nil-modules. A macro body therefore observes *nothing* of the program: not the store, and not any program-defined name — a helper function from an earlier `yin/def` is unbound at expansion (only the modules case of this restriction is stated, §2.4 "by design"). Two consequences:

- The no-memo rationale is wrong as written. The expansion is a pure function of (macro lambda, operand ASTs) — the actual reasons to re-expand are determinism-with-gensyms and authorizer liveness (a revoked `:macro-authorize` should stop expanding). If the store-read claim stands, the spec is missing a store bridge; if the sandbox stands (I think it should — it is what makes `expand-batch` pure and host-portable), the rationale must be corrected so a future implementer does not "complete" the bridge and break the parity/determinism tests.
- The restriction should be stated in full, including the runtime case where the macro value in the store is a closure *with a captured `:env`* that the `bind-params`-only `macro-eval` signature (§3.1) silently discards: a non-top-level `defmacro` referencing a lexical local fails loudly at expansion (`:unresolved`), which is acceptable only if it is documented as the contract. The prelude's constructors make small macros writable; "no helper functions ever" is a real expressive limit that authors need told about.

### [P3 — suggestion/alignment] 4. Opcode 24 is free today, but the reservation is order-dependent and `opcase` is `#?(:clj …)`-gated

Verified: `opcode-table` assigns 1-21 (v2.cljc:217-238); 22/23/24 are unused. The macro spec's `:macro-call 24` and the semantic spec's `:push 22 :halt 23` do not collide regardless of landing order — but the macro spec's Phase 0 does not state its dependency on the semantic spec's Phase 0 numbering, and §6.3's "add to `opcase`'s literal map" instruction addresses only the `#?(:clj …)` macro (v2.cljc:241-274), which does not exist on the cljs/cljd hosts at all. The "both places" rule is correct for clj and for the cljd host-eval pass; the cljs/cljd runtime dispatch story is inherited from the (unlanded) semantic spec and is the one genuinely open portability item — worth one sentence so it is not rediscovered in Phase 3. The `#?(:cljd …)`-first and catch-form guidance (§6.3) matches the codebase's actual patterns (v2.cljc:641, 668).

### [P3 — suggestion/alignment] 5. Selective `:keep-eids?` is asserted but not mechanized

Phase 0 adds a blanket `:keep-eids?` to `datoms->ast`, then requires `vm-load-program` to keep `:eid` "only for macro lambdas." No mechanism is given (predicate option, double reconstruction, or post-pass strip). The Phase 0 "no `:eid` leaks" acceptance test guards the outcome; name the intended mechanism so the walker's hot-path map shapes stay as designed.

### [P3 — suggestion/alignment] 6. Runtime eid minting needs a monotonicity rule across loads

`(:min-eid state)` is recorded per load and `:macro-eid` initialized to `(dec …)`; in a multi-batch REPL session a later batch whose minimum sits above previously minted ledger eids would reset the counter upward and re-mint eids already used by earlier ledger entries — conflating entities when the drained ledger is committed. Specify `(min existing-counter (dec min-eid))` semantics. (The `abs`-of-event-eid gensym component is fine while minting stays in tempid space.)

### [P3 — suggestion/alignment] 7. `valid-ast?` does not constrain `:literal` values to plain data

The shape walk checks node types and ref-key structure but not `:literal :value`. A macro body holding a closure or host function can emit it as a literal, putting a host object into emitted datoms and the ledger — breaking the "nothing is a host object" guarantee the Phase 2 `pr-str`/`read-string` test relies on. One `plain-data?` check closes it.

### [P3 — suggestion/alignment] 8. Deferred `:runtime` macros inside macro bodies are an unspecified corner

The compile pass (correctly, Clojure-like — it falls out of §3.3's ref-attribute walk into `:lambda` bodies) pre-expands `:compile`/`:both` calls inside bodies. A deferred `:runtime` call inside a body survives; when the outer macro's body then runs under `macro-eval`, the walker's Phase 2 transition fires against the throwaway VM — which has **no** `:macro-authorize`, so it fails `:unauthorized`, and would drop its ledger entry even if it succeeded. State the symmetric rule explicitly (compile-time work invoking a runtime macro is a `:phase-violation`, mirroring §4.1(d)) and have `macro-eval`'s throwaway VM enforce it.

### [P3 — suggestion/alignment] 9. Acceptance-criteria nits

- The 10,000-datom guard is cumulative per `expand-batch` call: a realistic file-scale batch (dozens of `defn`s × ~150 emitted datoms each plus the original prefix) can legitimately exceed it. It is configurable — either raise the default, account per-event, or add a corpus-scale test near 80% of the guard.
- Phase 2's "step count unchanged" has no counter to read (telemetry is a rejecting stub; the hot loop keeps no count) — specify driving cold `step` in a counting loop for that test.
- §4.1's "Exactly one `ASTWalkerVM` allocation, as every other arm" is two in practice (`expand-runtime` returns an updated state, then `cesk-return` re-allocates) — irrelevant next to running a body, but the sentence as written is false.

## Verified sound (per review area)

**Area 1 — CESK transitions.** `(cesk-return state' ast env k nil)` is arity- and order-correct against the private `cesk-return [vm control env k val]` (ast_walker.cljc:62-88); control non-nil keeps `:halted?` false; lexical restoration is preserved because frames carry their own `:env` and the splice reuses the caller's `env`/`k` unchanged; blocked/parked states are genuinely unaffected (the arm throws or returns synchronously; the throwaway VM's blocking is converted to `:kind :suspended` by `macro-eval`'s `blocked?`/`halted?` checks, which correctly use `halted-with-empty-queue?` semantics). The hot loop reaches the new arm only through its node-case default (ast_walker.cljc:658-667 → `cesk-transition`), exactly as §3.6 claims — modulo P2-1, which concerns *other* inlined arms. The semantic splice equations are consistent with the semantic spec's frame shape `{:segment :pc :env :stack-base}`; the ephemeral segment is continuation-shippable (id under `:code`, datoms in the ledger entry); `OP_MACRO_EXPAND`=24 integrates conceptually with the shared table (P3-4 for sequencing).

**Area 2 — expander engine.** The throwaway walker is clean and leak-free as constructed: per-invocation value, fresh store, no `:make-stream`/FFI pair (existing constructor errors fire), `:modules` nil, invocation-local gensym cell unreachable after return. Guards are computationally bounded: depth counts only nested re-expansion of output (siblings don't accumulate — verified against the §3.3 algorithm), direct recursion trips at 100 with the call-eid chain, the fixpoint claim holds (unexpanded operands + re-expansion of output ⇒ every surviving node was visited after its last rewrite), and a macro-free batch is a pure scan. Ledger under multi-turn REPL: growth is linear in expansions and strictly composition-drained — the REPL drains per evaluation, `(reset)`/`(vm …)` clear it with the store, and the only exposure is a daemon that never drains (documented in Appendix B; an optional cap is the natural hardening). The root-fact rationale is **verified against the actual heuristic** (v2.cljc:573-599): output eids mint below the batch minimum, so `(last (sort unreferenced))` selects an original entity and the unexpanded program would run silently; the event-entity exclusion is additionally load-bearing for committed/positive-eid batches where the appended event has the highest real eid.

**Area 3 — portability.** The expander is maps/vectors/`reduce` with no host branches; symbols via `symbol` not string identity; keyword-`=` only on maps the expander built; the catch and `:cljd`-first conditional guidance matches existing code; test-discovery claims match `shadow-cljs.edn` node-test and the `bb.edn` task trio. Open item is P3-4 (`opcase` gating); everything in Phases 0-2 is data-only and portable.

**Area 4 — milestones.** Phases 0-4 are realistic, correctly gated (Phase 3 on semantic Phases 1-2 — necessary, since none of it exists), and each deliverable has a named falsifiable test; Phase 4's Python workaround (scalar-signature `twice` given the missing list literal) is honest. With P2-2 fixed the Phase 3 tail test becomes passable; P3-9's items are the remaining verifiability gaps.

---

**Summary: ready to implement after the three P2 amendments (hot-loop twin sites, splice-root tail marking, sandbox rationale/restriction) — the core decisions (one expander, AST-map arguments, outermost-first fixpoint, explicit root fact, ledger-not-stream, deny-by-default authority) are correct and well-grounded in the code as it stands.**