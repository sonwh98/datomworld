Created-GMT: 2026-10-05 09:31:00 GMT
Created-Local: 2026-10-05 16:31:00 +0700
Coding-Agent: claude (opus-5-5)
Session-ID: pending (provider-generated)

# Task: UCF M-next D2 — the front's :yin.k/renewal request
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 16:31:00 +0700 | Status: active | Rationale: implementation role, continuity with the C/D slice engineers

Implement D2 in /Users/sto/workspace/datomworld-d2 (worktree, branch
ucf-d2-renewal, based on master 14d9f750). Read first: the D plan r2
(collab/1791191725340-architect-m-next-d-plan-r2.claude-fable-5-1
.findings.md — sections 1.5 and the D2 test contract), the plan review it
folded (collab/1791191261015-architect-m-next-d-plan-review.gpt-6-astra
.findings.md, finding 5), src/cljc/yin/vm/ucf/authority/front.cljc (the
request kinds, the resolver attribution, the diagnostic family, how a
proposal and a release are carried), src/cljc/yin/vm/ucf/authority/grant
.cljc (the lease-fact medium side), and docs/design/dao.lease.md
*Carriage*.

The contract (r2 1.5 item 1): the front gains one request,
`{:yin.k/request :yin.k/renewal :yin.k/request-id r :dao.lease/lease l}`.
The front carries it to the holder's lease-fact stream exactly as it
carries a proposal and a release. Test contract (r2):
- a renewal is carried to the holder's lease-fact medium and answers
  `:carried`;
- a poisoned authority answers `:suspended`;
- an unresolved author yields a `:wrong-author` diagnostic;
- the judge counts the carried renewal (the judge-step pass sees it —
  check how carried proposals/releases reach the judge and mirror that
  for renewals).

Acceptance criteria:
- Test-first: red tests for the four behaviors, then the implementation,
  then green. Portable `.cljc`; JVM during iteration; the orchestrator
  runs the three lanes at landing.
- The version-0 wire and the existing six request kinds are untouched:
  `git diff` shows only front.cljc (plus its test file) and additive
  request handling.
- The renewal follows the landed attribution, diagnostic and reply
  conventions exactly; no new keys on DaoStream outcome maps; the
  request id is the composition's, opaque to the front.
- If the judge's renewal handling needs a dao.lease change, stop and
  report instead of editing dao.lease — that is a ruling, not a
  mechanical change.

Constraints:
- Edit only src/cljc/yin/vm/ucf/authority/front.cljc and its test file.
  Anything else: stop and report.
- No git writes. kondo you may run; cljstyle is the orchestrator's.
- Lessons: a 0.0 literal is the integer 0 on JS (use `(cbor/float64 0)`
  or 0.5); `#?(:cljd nil :clj ...)` order for JVM-only test branches
  (:cljd first); a glm -p gate needs your final message to carry the
  full report.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red and
green evidence, unresolved concerns, and any incomplete work. Do not claim
edits or tests that did not occur.
