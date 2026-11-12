Created-GMT: 2026-11-10 23:52:00 GMT
Created-Local: 2026-11-11 06:52:00 +07 (+0700)
Coding-Agent: muse
Session-ID: bd5850dc-fa99-47a1-89c6-db0bd69d1d3b
# Task: engineer — R3 per the mob's (A) design: raw payloads for thrown, marked wrapper for protocol
Role: Engineer
Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-11 06:52 +0700 | Status: active | Rationale: it authored the (B) exhaustion argument and so is the strongest possible implementer of the (A) design that dissolves it — if it cannot make (A) pass astra's probes, (B) is proven by the adversarial implementer, not merely argued.

## Read first

1. `collab/exception-track/1794369000000-mob-record-r3-retention-design.md` — the mob record.
2. `collab/exception-track/1794366000000-architect-mob-r3-retention-design.glm-5.3.findings.md`
   — the (A) design with file:line citations. Implement it faithfully.
3. `collab/exception-track/1794365000000-reviewer-r3-identity-confirm.gpt-6-astra.stdout.log`
   — the probes your work MUST pass (astra's observation table + replay/mutation).

## The design in one paragraph

Stop minting a wrapper for ordinary `:yang.call/thrown` conditions: the error-outcome slot carries
`(:yang.call/value condition)` RAW, so the caught value IS the payload and every observation
(nested `=`, map key, `count`, `assoc`, truthiness, aliased predicates, cross-unit passage) is
correct by construction. Keep a wrapper ONLY for qualified protocol conditions
(`:yang.call/not-callable`, `:invalid-invocation`, `:unsupported-binding`) as clojure's
documented local exception form (py/php's ForeignException stance), and bind its retention to
heap identity: an issuer-only `:retained` mark that guest `cell/new` cannot mint, `cell/set!`
refusing marked entries (frozen contents for the wrapper's lifetime), and a pure
`cell/retained?`-family query. Retire the witness closure and every per-call-site
visible/predicate router that existed only for the ordinary case.

## Worktree / scope

`/Users/sto/workspace/datomworld-r3-orch` on `exception-r3-impl` at `c9c5533c`. Files:
`src/cljc/yang/clojure.cljc`, `test/yang/clojure_test.clj`. If the design genuinely needs an
engine change for the mark (e.g. `src/cljc/yin/vm/engine.cljc`), say so FIRST in your report as a
blocker for the orchestrator to route — do not silently widen scope; `cell/set!`'s refusal
convention may already suffice.

## Acceptance tests (astra's probes, mandatory, both walker and semantic lanes)

For an ordinary payload `orig` caught across a DYNAMIC call: `(= e orig)`, `(= [e] [orig])`,
`(get {orig :found} e :missing)`, `(count e)`, `(assoc e :a 2)` then read `:a` = 2, caught `nil`
is falsey, `(vector? e)` AND `((fn [p] (p e)) vector?)` both true for a vector payload, and
`(= e original)` with `original` = `(cell/new :original)` (exception_legs.clj:44).
For protocol conditions: `(:yang.call/reason e)` reads it, `ex-message`/`ex-data` work, saved
retain→rethrow forwards the condition byte-identical, and forged retention fails:
`(throw (cell/new (cell/get e)))` yields a fresh `:yang.call/thrown` with the CELL as payload,
and `(cell/set! e ...)` refuses.

## Verification

`clojure -M:test -n yang.clojure-test` (focused only) + `clj -M:kondo --lint` on your files.
**Do NOT run the contract test or any suite-scale test locally** — shiva-only (owner ruling
2026-11-04); one round already lost four hours to that. Commit and push `exception-r3-impl`.
Foreground only; no background server tasks.

Begin the final response with Completed-GMT/Completed-Local; end with the commit SHA and one line:
**(A) DESIGN HOLDS** or **(A) DESIGN FAILS: <exact probe and result>**.
