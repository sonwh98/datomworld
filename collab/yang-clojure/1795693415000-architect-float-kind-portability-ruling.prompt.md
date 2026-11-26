Created-GMT: 2026-11-26 11:43:35 GMT
Created-Local: 2026-11-26 18:43:35 +0700
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: Architect ruling — integral-float kind loss on JS: impact, policy, and fix layer (NO implementation authorized)

Role: Architect (compiler provenance / numeric semantics)

## The defect (owner-flagged serious; evidence verified by the orchestrator)

The r3 corpus gate caught it (evidence: /Users/sto/workspace/datomworld/collab/yang-clojure/1795104744000-fixorder-r3-evidence/fixorder-r3-integral-float-boundary.md + the archived divergent digests): a bare integral-valued float literal (0.0, 1.0) compiles to INTEGER content on Node and FLOAT64 content on JVM. Your sign-off r2 accepted the narrowed corpus with the boundary "never claim portable equality for bare integral-valued floating-point inputs" and left this dependency to its owner. The owner has now escalated: this is a serious problem for yin.vm on Node/JS.

## The orchestrator's verified chain (re-derive, then build on)

1. **Encoding**: `src/cljc/dao/jing/cbor.cljc:207-220` — on cljs, `host-integer?` is true for any integral JS Number (incl. 1.0), so the encoder emits integer content; `float64?` on cljs is false for bare integral Numbers. `float64` (:242+) exists as the carrier — the obligation is satisfiable.
2. **Semantics**: `src/cljc/yin/vm/data.cljc:513-521` `data-equiv` — the float64 and exact-integer branches are MUTUALLY EXCLUSIVE: float(1.0) and int(1) are NOT data-equiv in the VM model. Numeric op dispatch follows kind. So this is a RUNTIME SEMANTICS divergence (map keys, op selection, equality), not just canonical addresses.
3. **Root cause layer**: on cljs the READER collapses 1 and 1.0 to the same bare Number — by the time yang.clojure's compile-literal sees the literal, the source-level float syntax distinction is gone. The yang.typescript frontend does NOT have this problem: its ANTLR path knows the token and wraps every numeric literal as (cbor/float64 v) (lower.cljc:429-440; prelude comment :13-18 "the prelude holds no integral float literal: 1 is written (data/float64 1)"). So the producer obligation is satisfiable — but the yang.clojure/cljs path currently cannot honor it without help from the reader layer.
4. **Precedent**: ts prelude's workaround (write (data/float64 1) instead of 1) is source-discipline, not a fix.
5. Prior art: r3's divergence evidence; your sign-off r2 boundary paragraph; dao.jing.cbor.md's producer-obligation clause.

## Rule on (deliver RULING entries with normative text; READ-ONLY, no implementation authorized)

1. **Impact classification**: is this a correctness defect for the JS portability claim of yin.vm (programs behaving differently per host), or only a canonical-address/traceability defect? Enumerate the affected surfaces concretely (literals in source forms; host-Data values passed through the public API; prelude/library code; stored artifacts compiled on JS and reloaded on JVM; canonical addresses; content= semantics).
2. **The fix layer** — rule ONE with reasons, considering at least: (a) reader-level tagging on cljs (float-syntax literals → Float64 carrier; requires touching the reader/tooling — note what the cljs reader actually is in this repo and whether it's ours), (b) compiler-level reconstruction (yang.clojure records original source text per literal token, e.g. via ANTLR or a source-text pass, and wraps integral doubles — the ts precedent generalized), (c) codec-level change (a float marker for JS integral Numbers in canonical CBOR — changes dao.jing's contract and every consumer; the earlier ruling already rejected incidental codec change, but a deliberate codec round is not forbidden — rule on whether it's the RIGHT layer), (d) documented source-discipline only. Weigh portability of PAST artifacts (the re-mint consequences), cross-frontend uniformity (ts already wraps — does the chosen layer unify all four frontends?), and blast radius.
3. **Migration**: if the fix changes canonical bytes for JS-compiled artifacts (or JVM ones), state the one-time shift, legacy-load expectations, and whether a frontend revision bump is required (which frontends, which profiles).
4. **The portability claim**: pending the fix, what is the precise honest statement of yin.vm's JS portability (what source constructs are safe)? Where must that statement live (docs/design/dao.jing.cbor.md? the frontend docs? the corpus gate's docstring?)?
5. **Fix-round contract**: scope/bounds/acceptance for a FUTURE authorized round (not dispatched now): which files, which required tests (cross-host corpus rows with integral floats restored; the runtime-semantics oracle — data-equiv and op dispatch — not just digests), what must be re-pinned.

## Deliverable

READ-ONLY except your findings: collab/yang-clojure/1795693415000-architect-float-kind-portability-ruling.gpt-6-astra.findings.md in /Users/sto/workspace/datomworld/collab/yang-clojure/. The orchestrator holds dispatch until the owner reads your ruling.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
