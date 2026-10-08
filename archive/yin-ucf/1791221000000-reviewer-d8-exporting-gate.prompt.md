Created-GMT: 2026-10-05 16:45:00 GMT
Created-Local: 2026-10-05 23:45:00 +0700
Coding-Agent: glm (glm-5.3, plan review)

# Task: gate review, M-next D8 — the exporting state, prepare/encode, and the abort rule (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted work in /Users/sto/workspace/datomworld-d8
(branch ucf-d8-exporting, based on master 98c84713 with D7 landed).
The engineer's report:
/Users/sto/workspace/datomworld-d8/collab/1791216190073-compiler
-engineer-ucf-d8-exporting.findings.md. Read it first, then
`git -C /Users/sto/workspace/datomworld-d8 status` and the new files
(src/cljc/yin/vm/ucf/holder/export.cljc and its test).

The contract: D plan r3 sections 1.8/1.9 and the D8 test contract
(1791194000000-architect-m-next-d-plan-r3.claude-fable-5-1
.findings.md, staged in that worktree's collab/), astra's finding 9
(abort safety, 1791191261015-...-review...findings.md) and residual 2
(release wording, 1791192700000-...r2-confirm...), and the rulings'
export refusals (the D5 cursor ruling, the close ruling, and the
link-cursor ruling 1791203000000-... — export refusals for unminted
cells, cursorless link entries, held observations, :observe entries,
and pending closes). The rulings are all staged in the worktree's
collab/.

## What to attack

1. `enter`: sets the gate, empties the wait set, moves waits and
   reachable parked records into the export record; `:yin.k/
   not-quiescent` on a non-empty ready queue; each refusal kind fires
   on a real parked machine with the child's path reported.
2. Prepare/encode: prepare calls `serve!` exactly once per stream
   including children (the caching wrapper — verify a repeated
   prepare or a shared stream cannot double-serve); encode makes zero
   serve calls and equal bytes on repeat; the split is pure (no
   allocation, no publication at encode).
3. The abort rule against the reviewer's replacement text: legal only
   before any possibly accepted offer attempt, or on evidence the
   occurrence was never admitted and cannot still become admitted; a
   single refusal is not evidence; the engineer's `not-appended?`
   (append answered `full` or `closed`) — is that the right
   provably-not-appended set, and does the unknown-append and
   refusal-after-unknown refusal hold? The tenure bound blocks abort.
4. The export refusals' completeness against the rulings: :observe
   entries, :yin.k/held on any entry, unminted cells (the engineer's
   conservative any-unminted-cell reading — acceptable?),
   :yin.k/closes, cursorless link entries.
5. The in-:exporting behavior: nothing observed, no child advanced,
   direct resume refused, every public apply refuses (the engine's
   D4-D6 refusals driven through these paths).
6. Portability and style of the new files; the engineer's honest
   notes (not test-first, mutation-verified; the served-table handle
   keying concern for D14/D9; the abort vocabulary pending driver
   slices) — are the deferrals correctly scoped?

Verdict first: READY or NOT READY (with what must change), then
numbered findings with file:line evidence. Read-only: edit nothing,
run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
