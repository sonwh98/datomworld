Created-GMT: 2026-11-10 22:05:51 GMT
Created-Local: 2026-11-11 05:05:51 +0700 (+0700)
Coding-Agent: agy
Session-ID: pending (agy conversation_id)

# Task: Architect review — the P5b2 oracle promotion (commit aefac78d)

Role: Architect (read-only review, step 7)

Implementers:
- Model: gemini-4-argon-high (agy) | Assigned: 2026-11-11 05:05:51 +0700 | Status: active | Rationale: the owner-reserved architecture-review lane; codex sol was quota-walled earlier this session

Repo: /Users/sto/workspace/datomworld-p5rec (branch feat/p5b2-oracle-promotion @ aefac78d, pushed, not merged). Review `git show aefac78d`.

## What the commit does
1. programs_p5b2.cljc: all 26 fixtures promoted from :oracle :pending to the recorded php-8.3.35 (shiva docker) folds — the recorded evidence: shiva --p5b2 = 26 targets, 0 failures, rc=0, twice (before the engine fixes the pending entries were skipped; after, pass? is fully evaluated).
2. prelude.cljc run-main: an uncaught guest throwable now terminates with :php/status 255 (the five uncaught-exception fixtures recorded exit 255 from real php; the engine previously left status nil). Region check: the surface registry (collab/exception-track/1794343000000) cedes prelude.cljc :2264/:4217/:194-197/:2384/:4085 to the PHP exception round — the edit is in the ~5630 status chain, OUTSIDE the ceded regions; verify no overlap.
3. oracle.clj errno-match?: the 512-vs-2 class-derived exemption mirrors the existing 1024-vs-8 rule (E_USER_WARNING parses as class Warning -> 2; the engine's semantic truth is 512). The handler-mask fixture keeps engine truth [[512 "w1"] [512 "w3"]].

## Questions to rule on
- Is the uncaught-throwable 255 fix semantically complete (does any OTHER termination path reach :php/status nil where php would say 255 — e.g. exceptions escaping through shutdown, the handle-uncaught handler-throws path)? file:line evidence.
- Is the 512-vs-2 exemption principled or should the exemption be generalized (and if generalized, does that weaken the check)?
- The fixture keeps engine truth 512 with the exemption on the oracle side — right call vs adopting the php-parsed 2 in the fixture?
- Engine-side gates green: p5b2-lifecycle-test 169 assertions, oracle-test, kondo, cljstyle. Merge gate (dao.stream tri-lane + focused) running on shiva.

Deliverable: write findings to collab/orch-lane/1794348351000-architect-p5promotion-review.gemini-4-argon-high.findings.md (header fields; verdicts with file:line evidence; MERGE-READY or residuals). Read-only — commit nothing.
