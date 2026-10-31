Created-GMT: 2026-10-05 09:46:00 GMT
Created-Local: 2026-10-05 16:46:00 +0700
Coding-Agent: glm (glm-5.3, plan review)

# Task: gate review, M-next D3 — the authenticated ledger reader (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted work in /Users/sto/workspace/datomworld-d3 (branch
ucf-d3-evidence, based on master 14d9f750). The engineer's report:
/Users/sto/workspace/datomworld-d3/collab/1791194500000-compiler-engineer
-ucf-d3-evidence.findings.md. Read it first, then `git -C
/Users/sto/workspace/datomworld-d3 status` and the new files
(src/cljc/yin/vm/ucf/holder/evidence.cljc and its test).

The contract (D plan r3 section 1.5 item 3 and the D3 test contract; the
plan is staged at /Users/sto/workspace/datomworld-d3/collab/
1791194000000-architect-m-next-d-plan-r3.claude-fable-5-1.findings.md):
`evidence/read-evidence` folds the arbitration identity's attributed
records from the origin with `ledger/fold-record` and derives binding
evidence (`custody/binding-evidence`), enrollment, and the replay prefix
(`input/inputs`). Complete history or nothing: a gap, a start past the
origin, a transport error, or a fold defect makes evidence unavailable
(`:yin.k/unsatisfied` or `:yin.k/awaiting-grant`), never an empty prefix
and never a missing enrollment.

## What to attack

1. Complete-history-or-nothing: does every unavailable path answer
   unavailable, and can any path mistake a partial history for a whole
   one (e.g. dense-t checks, the origin check, a fold defect swallowed
   as success)? Where is the grant's record reachability enforced?
2. Attribution: only the arbitration identity's records count; a
   foreign author's records establish nothing. Check the filter against
   how C11's resolver attribution works.
3. The honest-prefix case: r3 allows a real empty prefix when the
   history is whole but no input was ever recorded — is the Ready
   answer's empty prefix genuinely distinguishable from the
   unavailable case?
4. The namespace takes data, holds no authority handle, never reads
   `authority/projection`, and carries no transport or apply vocabulary
   (the plan's seam rules).
5. The engineer's three concerns (transport-error-as-data interface for
   D13; the transitive load of authority.input; the O(history) cost):
   are any of them a contract defect of THIS slice, or only notes for
   later slices?
6. The test rows: origin-fold equals the authority's projection (test
   only), the four unavailable rows, foreign author, the
   dao.stream.remote reflection; portability and style.

Verdict first: READY or NOT READY (with what must change), then numbered
findings with file:line evidence. Read-only: edit nothing, run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
