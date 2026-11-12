Created-GMT: 2026-11-10 20:22:00 GMT
Created-Local: 2026-11-11 03:22:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: d6e40695-6a08-4073-a860-772f19e4b4d2
# Task: engineer fix round — clojure R3 review findings (astra 1794341300000, REQUEST CHANGES)
Role: Engineer
Implementers:
- Model: deepseek-flash | Assigned: 2026-11-11 03:22 +0700 | Status: active | Rationale: fresh family (authors were gemini+qwen-max+seat), available, cheap; implementation round on an existing branch.

## Where you work

Worktree `/Users/sto/workspace/datomworld-r3-orch`, branch `exception-r3-impl` @ `778747ce`.
Commit ONLY on that branch in that worktree. The shared checkout `/Users/sto/workspace/datomworld`
changes branches under other seats — never touch it. Files you may change: `src/cljc/yang/clojure.cljc`
and `test/yang/clojure_test.cljc` — NOTHING else. Do not weaken tests: an expectation moves only if
the R3 contract requires it, and you must justify each moved expectation in the report.

## The four findings (from `collab/exception-track/1794341300000-reviewer-r3-exception-clojure.gpt-6-astra.stdout.log` — read it first)

**P1-1 — retained tokens are source-readable and transferable (R5 violation).**
`clojure.cljc:1546` inserts the compiler token into the map bound directly to the catch variable
(`:1142`); the public escape (`:1314`) authenticates only the token, unbound to the condition.
`(throw {:yang.call/reason :yang.call/unsupported-binding, :yang.call/data {:forged true},
:dsg__condition (:dsg__condition e)})` passes authentication. Fix per the reviewer: bind provenance
to the retained condition through an opaque association or a witness authenticating its COMPLETE
contents, so a transplanted token cannot authenticate a different payload. Add token-transplant and
modified-condition regressions.

**P1-2 — ordinary caught exceptions expose the envelope instead of the original exception.**
`:1536` includes `:yang.call/thrown` in the whole-condition retention branch (`:1545`); catch
lowering exposes that whole map to the catch variable (`:1142`) while `rewrite-ex-info` accessors
(`:915`) do direct `:yin.k/message`/`:yin.k/data` lookups — so the public-call path yields
`[nil nil]` for `(ex-message e)`/`(ex-data e)` on a real `ex-info`. Whole-condition retention is
required by the wire contract; exposing it to guest catch code is not. Fix: the guest-visible
exception keeps the ORIGINAL payload's catching and accessor behavior; the complete wire condition
is retained privately (outside source-readable attributes). Add a dynamic-call catch regression
(alongside the existing direct-throw tests).

**P1-3 — retention deletes an original condition field.**
`:1546` overwrites any incoming `:dsg__condition` key; both outbound strippers (`:1318`, `:2444`)
then remove it. A genuinely transferred protocol condition carrying `:dsg__condition` as ORDINARY
DATA cannot forward unchanged. Fix (per R3's "forwards UNCHANGED, every evidence entry intact"):
store provenance OUTSIDE the original condition map (same mechanism as P1-1's fix); the original
map must round-trip byte-identical. Add a fixture whose payload contains
`:dsg__condition :ordinary-evidence`, asserting exact preservation after immediate and saved rethrow.

**P2-4 — two test assertions don't pin the contract.**
`clojure_test.clj:2620` and `:2637` check only the nested `:yang.call/not-callable` reason.
Strengthen them to assert the OUTER `:yang.call/thrown` reason, the error tag, the inner reason,
and the callee evidence.

## Consistency constraint (P1-1 + P1-3 share a mechanism)

P1-1 and P1-3 both demand provenance live outside the source-readable condition map. Design ONE
mechanism satisfying both: unforgeable by reserved-prefix lookup (R5: "shape is never evidence"),
round-trip preserving every original field, readable by the escape boundary's authentication. The
mechanism must work for immediate rethrow AND saved-protocol rethrow (retention rides the generated
exception's identity for its entire lifetime).

## Verification (G0 focused — individual namespaces on the laptop are allowed)

1. `clojure -M:test -n yang.clojure-test` — expect the R3-branch state (97F/0/0 per the outgoing
   seat's laptop run) PLUS your new regressions green; if a previously-passing expectation had to
   move, justify it.
2. `clojure -M:test -n yang.call.exception-contract-test` — the six `:clojure/protocol-*` +
   `:clojure/saved-protocol-*` rows must stay `:green` on all three backends; no OTHER row may
   flip (the rest of the ledger is intentionally red — a new green or a new red is a defect or a
   contract change you must explain).
3. `clj -M:kondo --lint src/cljc/yang/clojure.cljc test/yang/clojure_test.clj` — 0/0.
Suite-scale runs are forbidden locally (owner ruling 2026-11-04); the orchestrator will rverify
the shiva confirmation. FOREGROUND only — no background tasks (agy-gemini wedge lesson).

## Report format

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then: the provenance mechanism you built (with file:line), per-finding disposition (fixed with
evidence / rejected with reason), the exact commands with assertion counts, any moved expectations
with justification, and the commit SHA on `exception-r3-impl`.
