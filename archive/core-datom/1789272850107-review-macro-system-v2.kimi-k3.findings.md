I'll review the macro architecture spec. Let me start by reading the target document and the key companion files.The spec is comprehensive. Now let me check sizes of the companion files and verify claims against the actual code.Shell is restricted; I'll read the files directly instead.Now let me read the schema, emission, and indexing sections of `yin.vm`.Now let me read the walker's transition function, loader, and the full macro-related sections of yang.clojure.The walker's node case confirms there's no macro arm today. Now let me check the hot loop's default arm, `apply-function` (grep didn't find it), the loader, and yang.clojure's helper sections.The walker details check out with notable findings on the hot loop. Now the Python/PHP frontends and engine's `resolve-var`.Semantic spec confirms opcode 24 is free (`:push 22`, `:halt 23` reserved). Let me read the rest of the semantic spec for the call/closure mechanics and continuation handoff the macro spec depends on.Semantic spec cross-checks pass so far. Now the original macro design doc and the cross-language doc, plus the exact opcode table.`resolve-module` on a nil registry returns nil, so unknown/module-requiring symbols fail cleanly in the throwaway VM — spec claim holds. Now the test corpus, quote compilation, and the axiom list.Let me check the invariant names in datom.world.md and look for the countdown benchmark base for the Phase 2 test.Let me verify the test-suite files the phases reference, and check the `ast_walker_test` and `v2` suites.I have everything needed. Verification is complete across the spec, both companion specs, the walker, the three frontends, the REPL composition, and the stream observer. Writing the review now.

```text
Completed-GMT: 2026-09-13 04:42:00 GMT
Completed-Local: 2026-09-13 11:42:00 +07
Coding-Agent: cmd
Session-ID: e7240c03-5e8a-4467-8cfb-60a6797528cb
Role: Compiler & AST | Model: kimi-k3
```

# Independent Review: Unified Macro Architecture on Yin VM v2 (Compiler & AST)

Method: every load-bearing claim in the target document was verified against the working tree on branch `dao.stream-redesign-v2` (`yin.vm`, `ast_walker`, `engine`, `module`, `stream_observer`, `yang.clojure/python/php`, `yin.repl.core`), both companion docs (`macro-design.md`, `cross-language-macro.md`), the semantic VM spec, and `datom.world.md`'s invariants.

**Verdict: no [P1-blocking] findings.** The core design (one expander, AST-map arguments, outermost-first fixpoint, explicit root fact, supplied authority, evaluator-owned splice) is sound and faithfully mapped onto the actual code. The findings below are correctness and precision defects in the specification text; each has a small, local resolution. The design is implementable once the P2s are resolved.

---

## [P2 — must address]

### P2-1. The loader compositions `comp` a binary loader with a unary pass; every normative example throws an arity error as written

`stream-observer/run-on-stream` calls the loader as `(load-program vm batch)` (stream_observer.cljc:188), and both `ast-walker/vm-load-program` (ast_walker.cljc:719) and the future `semantic/vm-load-program` are binary `[vm datoms]`. Clojure's `comp` gives the rightmost function *all* arguments. Therefore:

- §3.6 `(comp ast-walker/vm-load-program (macro/expand-with opts))` invokes the unary `batch → batch'` as `(expand vm batch)` → `ArityException`.
- §6.2 `(comp <evaluator-loader> (macro/expand-with …))`, Phase 3 `(comp semantic/vm-load-program linearize/lower (macro/expand-with …))` — same defect.
- The semantic spec carries the identical bug independently: §3.1/§7.3 `(comp semantic/vm-load-program linearize/lower)` calls unary `lower` as `(lower vm batch)`.

This is the design's central delivery mechanism, so it will be the first thing every implementer trips over. Fix once, normatively: specify a loader adapter, e.g. `macro/loader` / `linearize/loader`:

```clojure
(fn [vm batch] (vm-load-program vm (expand batch)))
;; or curried: (macro/with-expansion opts vm-load-program) → [vm batch] → vm
```

and use that shape in §3.6, §6.2, Phase 3, and the semantic spec's two composition examples.

### P2-2. Decision 8 ("macro closure applied as a function is an error") leaks through the walker's inlined hot loop in two places

Verified against ast_walker.cljc:

1. `apply-function` (line 211) is only consulted from `cesk-transition`'s continuation arms. The inlined hot loop `ast-walker-run-active-continuation` applies closures **inline at two sites** (lines 540–543 and 581–585) without touching `apply-function`. A macro closure applied through the hot path bypasses the guard.
2. Worse: the hot loop's `:lambda` case (lines 639–646) constructs the closure map itself (`{:type :closure :params :body :env}`) and would **not** copy `:macro?`/`:phase-policy`/`:eid`, so even a guard added only to `apply-function` would never see the flag on hot-created closures. The failure mode is exactly what decision 8 exists to prevent: operands meant to be syntax get evaluated as ordinary arguments, silently.

Required edits (small, and they contradict three spec claims): hot-loop `:lambda` copies the three flags; both inline apply sites reject `(:macro? fn-value)`. Consequently the spec's claims "the hot path for functions is untouched" (§2.3), "the walker's inlined hot loop does not gain a case" (§3.6), and the Phase 2 acceptance parenthetical "(the inlined loop did not change)" are inaccurate. Keep the zero-per-op-cost framing if desired (nil checks on a map key), but state the three edits, since Phase 2's test "Hot-loop parity: step count and value unchanged" otherwise reads as "no hot-loop edits". (The `:yin/macro-expand` node-arm claim *is* accurate: the node falls through the hot loop's default to `cesk-transition`, whose node case at lines 390–500 ends in `throw "Unknown AST node type"`, line 500.)

### P2-3. The datom guard's unit is internally contradictory, and both candidate units have a hole

- §0 decision 4 and `macro-design.md` §Guards say "emitted datoms **per expansion transaction** at 10,000" (per single expansion).
- §3.4's table says "emitted datoms **per `expand-batch` call** | 10,000".

These are different guards. Per-batch cumulative 10k rejects legitimate whole-file builds (a few hundred ordinary `defn`/macro call sites in one compiled unit will exceed it; the REPL's per-input batches stay small, a build tool's won't). Per-event alone misses breadth explosion: one macro emitting N sibling macro calls whose outputs each emit N more stays at low *depth* (depth only counts nesting, correctly, per §3.3) while datoms explode exponentially across many events. Resolution: specify **both** bounds explicitly, e.g. `:max-datoms-per-expansion` 10,000 and `:max-datoms-per-batch` with a much higher default (or unlimited), and align §0.4, §3.4, and the Phase 1 guard test (which currently tests only the 10,001-node single-macro case) with the dual rule.

### P2-4. Tail-position metadata for macro-generated code is only obtainable through one constructor, and its purpose is misattributed

Findings:

1. The walker never reads `:yin/tail?` — its TCO is structural (closure body runs against caller's `k`). `:yin/tail?` is consumed only by `linearize` (`:call` vs `:tailcall`, semantic spec §5.3 "Tail position is read from `:yin/tail?` ... nothing new is inferred") and the future stack VM. The spec's phrase "so tail calls in macro-generated bodies still get the walker's TCO" (§2.4, `yin/make-lambda`) misattributes the consumer; Appendix B's "run it on the walker too" is vacuous.
2. Tail-ness is contextual, so the context-free prelude constructors (`yin/if`, `yin/application`, `yin/lambda`, `yin/sequence-body`) **cannot** mark it; only `yin/make-lambda`'s post-construction mark pass (ported from v1's `mark-tail!`) can. A macro written against the native constructors produces an unmarked tree; on the semantic VM that silently degrades a tail-recursive loop into `K` growth per iteration — precisely the failure Appendix B warns about, and precisely what Phase 3's "tail :macro-call in a tail-recursive loop keeps `k` depth 0 over 10⁵ iterations" tests, but only if that test's macro goes through `yin/make-lambda`.

Resolution (pick one, state it): (a) preferred — make the expander mark tail positions over the complete output tree in `emit`/§3.3 before emission (one deterministic tree pass, subsumes `mark-tail!`, works for every constructor), and say so in §2.4/§3.3; or (b) declare "macro authors must route output through `yin/make-lambda`" as an explicit contract and add a negative test (unmarked macro-generated tail loop on the semantic VM documents the known degradation). Do not leave it implicit.

---

## [P3 — suggestion/alignment]

1. **Runtime `:variable`-operator resolution error kind.** `engine/resolve-var` *throws* `ex-info "Unable to resolve symbol"` (engine.cljc:46–64); it does not return nil. §4.1(a)'s "`{:kind :unresolved}`" therefore needs a membership pre-check or a wrapped call, and the `{:kind :not-a-macro}` check needs the resolved value inspected before application. Pure implementation note, but the spec currently reads as if resolve-var were total.

2. **`valid-ast?` over-promises with the `:vm/*` glob (§2.4).** `ast->datoms-with-root` (v2.cljc:384–461) supports exactly `:vm/gensym :vm/store-get :vm/store-put :vm/park :vm/resume :vm/current-continuation`; `:vm/store-update` exists only as a walker *transition* (ast_walker.cljc:441), is absent from `ast->datoms`/`datoms->ast`, and is rejected by the semantic lowering. A macro emitting `:vm/store-update` would pass a glob-shaped `valid-ast?` and then fail in `emit` with "Unknown AST node type". Enumerate the exact emitter-supported set in §2.4.

3. **Decision 3's sharing claim is compile-time-only; say so.** At runtime the loader keeps `:eid` only on macro lambdas (deliberately, per §3.5 hot-path discipline), so runtime operands are eid-free maps and `expand-runtime` must re-emit unchanged operand subtrees into the ledger — they are *not* shared by reference. Functionally fine, but §0 decision 3 ("Unchanged operand subtrees are still *shared*, not copied") should be qualified, and §4.4's ledger-growth note should count copied operand subtrees toward ledger size (relevant to the 10³-iteration loop test's documented cost).

4. **`stdlib-forms` "unchanged in text" — the text no longer exists in the tree.** Grep confirms the only in-tree references to `stdlib-forms` are yang.clojure's docstring; the source was deleted with `yin.vm.macro` in d8b27a5. Phase 1 should say "recovered from git history at `d8b27a5^:src/cljc/yin/vm/macro.cljc`", since the Phase 1 `defn`-parity test depends on that exact text.

5. **REPL single-form path bypasses the macro-env.** `yin.repl.core/compile-clojure-forms` (core.cljc:459–463) compiles single forms through 1-arity `yang.clojure/compile`, which hardcodes `initial-macro-env`. §6.2 names only the `compile-program` arity, so a single-form macro call at the prompt would not see the shell's `:macro-env`. The env must thread through the single-form path too (new `compile` arity or always-`compile-program`). Related: `eval-datoms` hardcodes `ast-walker/vm-load-program` (core.cljc:439); after the "one program path" change the loader should come from the session so `:semantic` gets its composed loader in the same place.

6. **Fixpoint definition drift not flagged.** `macro-design.md` defines fixpoint as "the last pass emits zero new `:yin/macro-expand` datoms" (multi-sweep); the new design's fixpoint is recursive re-expansion of each expansion's own output. Appendix A documents the outermost-first *order* change but not the *fixpoint definition* change; Phase 0's promotion of §2–§5 into macro-design.md must explicitly replace that sentence, including its Test Plan bullet ("Fixpoint test: termination occurs when no new `:yin/macro-expand` datoms are emitted in a pass").

7. **Fixture realities the phases assume but don't deliver.** (a) Phase 2's "countdown benchmark program" exists only as `src/cljd/yin/register_bench_cljd.cljd`; there is no `.cljc` countdown corpus fixture, so the hot-loop parity test needs one created (it can double as the semantic spec's Phase 4 benchmark). (b) Phase 0 says "`yin.vm-test` additions" but no such test namespace exists (v2 has no dedicated `ast->datoms`/`datoms->ast`/`index-datoms` unit suite today); it must be created, and it is worth having anyway — the root-fact and `:existing`/`:keep-eids?` tests belong there. (c) The ast-walker ns docstring (ast_walker.cljc:20–21, "There is no `macro-expand` branch here") must be updated in Phase 2.

8. **Python/PHP macro-env threading is more pervasive than §2.6/Phase 4 state.** `compile-stmt`/`compile-suite`/`compile-program` in both files are 1–2 arities with recursive self-calls throughout; every call site gains the env argument (mechanical but total). Shadowing scope is larger than "lambda/def parameter": PHP additionally binds via `function` names (php.cljc:638), assignments-introduced lets (php.cljc:653, 606), and `for`-loop variables including the host-`gensym`ed `loop-fn` (php.cljc:573); Python binds `def` names in `compile-program`'s wrapping lambdas (python.cljc:483–511) and `lambda` params. The removal rule should be stated as "any frontend-introduced lexical binder", not "lambda/def parameter". Confirmed accurate: the Python tokenizer has no list-literal production (tokenize-line, python.cljc:23–54), so Phase 4's scalar-only `twice` test design and the deferral of Python `defn` are correct.

9. **`expand-with`'s return shape.** §3.1 has `expand` returning only datoms; `expand-with` (datoms→datoms') therefore drops `:root-eid`/`:events`/`:deferred` — fine for the loader, but say explicitly that compositions needing the event list use `expand-batch`, so nobody "fixes" `expand-with` into returning a map and breaks the (fixed, see P2-1) loader composition.

---

## Alignment confirmations (verified, no action)

- **Opcode 24 is free and stable under either landing order**: current table tops out at `:dao.stream.apply/call 21` with `:move 3` unused (v2.cljc:228–238); the semantic spec reserves `:push 22`/`:halt 23`. The `opcase` literal-map duplication (cljd host-eval trap, v2.cljc:241–274) is exactly as both specs describe.
- **Root-fact design is verified against the real heuristic**: `index-datoms` picks the numeric max of unreferenced typed entities (v2.cljc:573–599). Tempids mint decreasing, so the *original* root outranks expansion output and the unexpanded program would indeed be selected; the `:yin/root` fact and last-fact-wins rule are necessary and sufficient. The spec's claim that event entities "would otherwise win the heuristic" is false for the expand-batch output (the old root wins there) but **true for drained-ledger batches** (event minted before its output, and no higher unreferenced entity) — the exclusion is justified; consider adding that one-sentence justification.
- **Multi-pass idempotence holds**: a second `expand-batch` over an expanded batch finds no enabled nodes, returns the input unchanged (Phase 1 acceptance), and the pre-existing last `:yin/root` still points at the expanded root.
- **Sharing plumbing exists**: `ast->datoms-with-root` already has `:m`, `:id-start`, and pre-allocated-`:eid` dedup (`seen-eids`, v2.cljc:365–382) — `:existing` is a clean additive third option; yang.clojure's `-1000000` pre-allocation counter (clojure.cljc:409,434) composes with the strictly-below-batch-min rule.
- **Macro representation round-trips today** (`:yin/macro?`, `:yin/phase-policy` with `:compile` default; embedded-lambda operators deduped to one entity) exactly as §2.1 states.
- **The throwaway-VM error story is real**: `create-vm` without `:make-stream` yields precisely the "constructed without :make-stream" error §2.4 relies on (v2.cljc:180–182, 637+); nil module registry fails module resolution cleanly via `resolve-module` returning nil and `resolve-var` failing.
- **`resolve-var` order (env → store → primitives → modules)** makes `bind-params` shadowing and the prelude merge behave as §2.4/§3.2 describe; variadic `&` binding works because the macro body is env-evaluated, never closure-applied.
- **Cross-language argument shapes verified**: Python string → `:literal` string, identifier → `:variable`, and the Phase 4 `(defmacro twice [f] …)` example composes correctly from Python and PHP call sites; yang.clojure compiles vectors as literals (clojure.cljc:62–72), so the bootstrap `defmacro`'s params operand arrives as the `:literal` vector that §2.4's `yin/make-lambda` compatibility note assumes.
- **Semantic-VM splice arithmetic is consistent**: the ephemeral segment's `:return` interacts with empty-`K` halt and tail/no-frame rules exactly as the walker's structural TCO does; `:yin.code/call-ast`/`:macro-ast` as inline map values are permitted by the segment constants rule (semantic spec §2.5); the semantic spec's current "reject `:yin/macro-expand` at lowering" (§2.4/Phase 2) is the exact extension point §4.2 replaces.
- **The six invariants cited in §1.2 match `datom.world.md` §Non-Negotiable Invariants** one for one; the spec's per-invariant compliance rows are consistent with the code I read.
- **parity_test.cljc's docstring quote** ("the corpus is macro-free because neither evaluator has a `macro-expand` branch") is verbatim accurate.

Net: adopt after the four P2s are folded back into the document (loader composition shape, hot-loop flag/guard sites, dual datom guards, tail-marking rule) and the P3 precision notes are applied.