Created-GMT: 2026-11-04 17:55:00 GMT
Created-Local: 2026-11-05 00:55:00 Asia/Ho_Chi_Minh
# RULING OF RECORD — the cell effect vocabulary grows `authentic?`; call routing asks the heap, never the shape

Status: OWNER-APPROVED (2026-11-04, "ok. i like this idea. lets go with your recommendation") on the orchestrator's recommendation, incorporating the gpt-6-astra second opinion (1794320000000-architect-cell-ref-two-api-ruling.gpt-6-astra.stdout.log — verdict: retain the two operations, reject projected-name-only selection, rule for the authentication effect with a precise bearer-value policy).

## The problem (record)

Cross-language references are cell-ref maps issued by the VM (`{:type :cell-ref :id :cell-N :seal …}`, engine.cljc ~:316). In Clojure, maps are callable, so the compiler's call lowering must route a callee between reference semantics (validate via the heap, apply the designated closure) and data semantics (ordinary map lookup). Three rounds routed by VALUE SHAPE at call time (r2 key-presence, r3 exact-shape, r4 minted-id-language) and all three left the same class of defect: innocent data whose shape imitates the reference language aborts at `cell/get` ("Dead or forged cell reference") instead of answering as data. The shape approach is structurally doomed: a genuine reference and a perfect imitation are field-identical maps; the distinguishing fact (did the heap issue this?) is not in the value — it is in the heap. Only the issuer can answer.

A position-only alternative (route by projected-name membership at lowering time) was proposed and REJECTED by the astra review: it breaks the landed higher-order matrix leg (`(defn clj_apply [f] (+ (f 20) 1))` receiving a TypeScript closure as a parameter — cross_lang_test.clj:100) and the written calling contract §8.10.11 (yang.antlr.md:3822/4026: functions must remain callable after being passed, returned, stored, recursed, called back).

## The ruling

1. **The cell module's published effect vocabulary grows from `{new, get, set!}` to `{new, get, set!, authentic?}`** — a declared VM API change, hereby authorized by the owner. `cell/authentic?` is a pure query: the engine answers `(authentic-ref? state :cell-ref ref)` (engine.cljc:331 — the SAME predicate `check-ref!` already uses) as a boolean value, non-fatally, state unchanged. It follows the existing cell-module pattern: a pure effect constructor (module.cljc) + one interpreter case (engine.cljc effect dispatch). The engine learns no frontend semantics.
2. **Call routing asks the heap, never the shape.** Where the callee value has the cell-ref shape, compiled code consults `cell/authentic?`: authentic → reference path (validate/get + the existing admission/apply machinery); not authentic → ordinary data semantics (map lookup). The shape remains a cheap pre-filter for WHEN to ask; it never decides meaning.
3. **Authentication is not authorization** (astra rule 3): an authentic reference whose cell lacks an admitted callable entry is NOT callable — the existing refusal stands (pinned: clojure_test.clj:2120).
4. **Bearer policy** (astra rule 4): an exact copy of a valid reference retains bearer behavior — it authenticates, therefore it resolves. No construction-history promises for identical values. A program wanting such a map's fields as data uses explicit lookup at a data position.
5. **Static specialization is an optimization, not a semantic** (astra rule 5): projected-name fast paths may exist only with proof of equivalence under shadowing and higher-order use — never as the routing default.
6. **The no-collapse invariant is respected**: the interpreter answers a generic fact about its own state; the invoke-vs-lookup DECISION remains in compiled guest code (interpretation). The engine never learns Clojure map-call semantics.
7. **The entry-key (`:yang.call/entry`) admission policy is OUT OF SCOPE** — it stays exactly as landed and gated in D2 (an unadmitted entry-shaped map refuses; pinned clojure_test.clj:2600). "Ordinary map application always remains data" is therefore true for the cell-ref language, not universally; §8.10 documents the entry-key policy as the one declared shape-routed exception.

## Required documentation

§8.10 (yang.antlr.md) gains: the `cell/authentic?` vocabulary entry; the position/semantics rule (routing consults the heap for cell-ref-shaped callees); the bearer-copy semantic (rule 4); the bag semantic clarification (a reference stored in data REMAINS a reference — storage changes nothing; what decides is the authentication query at the call); and the entry-key exception note (rule 7). The cell-profiles publication and any module docs listing the vocabulary update accordingly.

## Acceptance (all on the rebased branch, verified per the test-boundary ruling)

- The gate's collision literal `({:type :cell-ref :id :cell-7 :seal "a…64 chars"} :id)` ANSWERS `:cell-7` as data (new regression).
- The orchestrator's pinned exposure probes (full-shape literal + runtime-built, `:id :customer`) keep answering (regression retained).
- Higher-order contract: the clj_apply callback leg, `stored === phpfn` + `stored(2,3)`, let/identity/apply forms — all green (existing matrix legs).
- Genuine references resolve; genuine-but-unauthorized (authentic, no entry) refuse (existing pins); forged references at reference positions refuse (existing pins).
- Full matrix + yang.base + yang.clojure-test + the 4M kernel gate green remotely on the rebased tree; kondo clean.
