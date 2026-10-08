[claude-code:unrecognized_model] {"model":"glm-5.3","query_source":"sdk"}
```text
Completed-GMT: 2026-09-13 06:39:24 GMT
Completed-Local: 2026-09-13 13:39:24 +07
Coding-Agent: glm
Session-ID: dcda6fce-9c4e-4e07-8311-57fd6a7513d9
Role: VM Runtime Review | Model: glm-5.3
```

# Round-8 Confirmation — `docs/design/yin.vm.macro.md` (r8)

**Verdict at the end: APPROVE.** The catalogue/stand-in mechanism introduced by rounds 5–8 is confined, deterministic, and its divergences are enumerated; the three findings below are P3 polish, none blocking. I re-read the full document (not just the delta) against the parts of the pipeline I have verified in code across rounds 1–4 (`ast_walker.cljc`, `v2.cljc`, `stream_observer.cljc`, `engine.cljc`).

## 1. Runtime/invariant check of `:yin/macro-defined`

**Confined as a node — verified, with a genuine (unused-by-design) backstop.** The confinement chain: admission rejects macro lambdas outside definition position (so no marker enters through `program-in`); markers are created only by step 3's replacement; step 7 lowers every one to a name literal before emission; step 8's scan cannot fire and a violation is a thrown forwarder defect (`:scan-failed`, correctly in decision 11's bug class). Beyond the specified steps there is a structural backstop worth naming in the doc: `ast->datoms`/`datoms->ast` do not know the type (Phase 0 does not add it to the codec), so any marker that somehow reached the shared emitter or decoder would crash loudly rather than leak. Two derived verifications:

- **The throwaway body VM cannot execute a marker.** Macro bodies come from the source batch, which was admitted before any marker exists (markers are minted at step 3, after admission); markers reach a body only as *env values* (decoded operands). Control in the walker derives from the body AST only, so a marker can be inspected (`yin/node-type`, `yin/node-get`) and returned, never evaluated. The walker's node case would also throw on `:yin/macro-defined` — unreachable, and loud if reached.
- **No bad interaction with `:generated-macro` or the plain-data validator.** The rejection is about `:macro? true` *lambdas*; markers are not lambdas — orthogonal rows in the validator. Fabrication is closed the right way: a fabricated marker can only name a *harvested* catalogue entry (validated `k`, matching `:name`), so it can move a declaration's precedence but never introduce a body. `(yin/def m <marker>)` ranks the dissoc at the `yin/def` occurrence followed by the assoc at the marker's position inside it — deterministic, and semantically the sane reading (the tree says m's value is that declared macro).

**One real leak — [P3].** The validator's plain-data rule admits the marker *map* inside a `:literal :value` (a map of keyword/symbol/int is plain data), so a macro can do `(yin/literal {:entry <stand-in-operand>})` and the marker shape passes output validation, is invisible to step 5 (the declaration walk is over nodes, not literal payloads), is invisible to step 7's lowering (same reason), and — crucially — bypasses the codec backstop, because the codec emits a literal's `:value` verbatim. Result: `program-out` (and a running program's value) can contain the internal `{:type :yin/macro-defined …}` shape as inert data; a REPL would print it. Specify the disposition: either reject a marker nested in `:literal :value` at output validation (preferred — it keeps "no evaluator ever sees the internal shape" true in the data sense too, and quoting-style macros are the plausible trigger), or state the leak as permitted alongside the existing "marker-shaped data may well survive" sentence. One line either way.

## 2. Determinism

**Pure as specified.** The next-batch store is a function of (store-before-step-2, final tree) where the final tree is itself a pure function of (batch, incoming ctx): catalogue ordinals come from harvest order = batch (vector) order; declaration order is structural (operands-then-body for immediately-applied lambdas, fixed child order otherwise — no eids, no map iteration); last-wins over a total order; gensyms deterministic via the monotonic watermark; `:t` counts batches. Verified the two orders compute the intended source order: yang's `(do A B)` → `((fn [_] B) A)` ranks A then B, and `(let [x v] body)` → `((fn [x] body) v)` ranks v then body — recovered at any nesting.

**[P3] Two implementation cautions the doc should pin, because the determinism claim is absolute ("same inputs, same output, on every host") and both have a silent wrong-implementation available.** (a) Harvest ordinals and admission's *first reported* violation must iterate the **datom vector**, not the `group-by` index map — index-map iteration order is host-dependent (and on cljs is not insertion order), which would make catalogue ordinals and the `:eid` in a `:malformed-input` report vary across hosts. The check *set* is order-independent; the *ordinal assignment* and *error selection* are not. (b) The decode/encode at the invoke boundary are the expander's own marker-aware variants, never the shared codec (which would throw on the type) — worth one sentence, since it doubles as the documented reason the codec is a safe backstop rather than a required component of the expander's inner loop.

## 3. Silent-wrong-code sweep

Every divergence I can construct is either enumerated in step 5's consequences or loud:

- **`defmacro` inside a `let` body** (`((fn [x] (yin/def m …)) v)`): step 2 installs m for the current batch; the marker survives expansion untouched (childless node, inert in `expand-node`); declaration order ranks it inside the body at its source position → in force next batch. Consistent with the stated syntactic-harvest policy; no wrong code.
- **`defmacro` in a discarded `if` branch**: in force for the current batch (step 2), absent from the next (not in the final tree — "the final tree is the program"). This is the one *user-visible* surprise (a branch the user believes untaken defines a macro for the batch that contains it), and it is exactly the divergence the document states and Phase 1 tests ("a source macro definition discarded by an enclosing macro is absent next batch"). Stated, not silent.
- **A macro returning its stand-in operand twice**: both occurrences resolve the same catalogue entry and assoc the same lambda — idempotent under last-wins; both lower to name literals; no double-definition effect. Correct.
- Extra probes beyond the brief: `(defn m …)` then `(defmacro m …)` in one batch *matches* Clojure (marker ranks later → macro next batch); `(defmacro m …)` then `(defn m …)` removes it next batch (stated); a disconnected macro definition is in force this batch and gone next (absent from the final tree — same rule as discarded, coherent); a re-parented formed definition is caught by shape, not origin (r5's row 31 resolved for real this time — the `m`-tag scheme it replaced was genuinely broken and the stand-in is the right fix); a swapped `(def m f)`/marker ordering resolves purely by position.

The complexity cost is real — step 3/5 is now the most intricate machinery in the document (catalogue, ordinal identity, a second tree order, fabricated-marker semantics) — but every behavior it fixes was a genuine silent-wrong-code case from rounds 2–7, each consequence is enumerated, and the Phase 1 battery (keep-first/second/swap/duplicate, re-parented variants, real `do`/`let` lowerings) pins the matrix. I would flag for the *implementation* review that this section deserves the closest line-level attention, but there is nothing to change in the design.

## 4. Declaration order vs §3.2 traversal

The separation is clear and bidirectionally cross-referenced: §3.2 states its fixed order "is for allocation determinism only; it is not source order" and points at step 5; step 5 defines declaration order, explains *why* they differ (operator-first visits B before A in `((fn [_] B) A)`), and narrows its own claim with the PHP `for` exception. Distinct names, distinct definitions, named counterexample. The conflation failure mode (implementing step 5 with the §3.2 walk) inverts `do`/`let` precedence and is caught by the tests explicitly required to run "through real `compile-program`/`do`/`let` lowering, including nested sequences, not flat definition lists" — flat lists are exactly the input shape on which the two orders agree, so that test clause is the load-bearing one; keep it. Adequate; no finding.

## 5. Anything else from r5–r8

Checked and clean: step 8's downgrade to cannot-fire-plus-`:scan-failed`-as-defect is the right classification; §2.3's retirement of the inline macro-lambda call site (admission rejects it, `macro-of`'s lambda arm defensive-only) closes a dead path rather than leaving it half-supported; row 38's catalogue-lifetime statement (stand-ins resolve only against the current batch's catalogue, replayed markers select current-or-nothing) is consistent with `:declared` being batch-local and never entering `ctx`. Appendix C rows 28–38 accurately describe the current text.

## Findings summary

- **[P3-1]** Marker shape can leak into `program-out` as inert data inside a `:literal :value` (plain-data rule admits it; step 5/7 and the codec backstop all walk nodes only). Reject it at output validation or state it permitted.
- **[P3-2]** Pin the two order-sensitive implementation points to the datom vector: harvest ordinals and admission's first-error selection. Map iteration there breaks the absolute determinism claim on cljs.
- **[P3-3]** Doc nits: the validator row for `:yin/macro-defined` has no Optional keys, but `mark-tail` (step 6, which runs before step 7's lowering) marks childless leaves — markers transiently carry `:tail?`; make the row say so. And state that invoke-boundary decode/encode are the expander's own marker-aware variants (the shared codec's rejection of the type is the backstop, not a component).

## Verdict

**APPROVE.** The mechanism rounds 5–8 added is sound, confined, deterministic as specified, and its every divergence from Clojure is enumerated or loud; the three P3s are one disposition sentence and two implementation cautions, none of which change the design. Ready for Phase 0 as sequenced, with §3.1 steps 3/5 flagged as the section deserving the closest line-level scrutiny at implementation review.
