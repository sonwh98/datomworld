Created-GMT: 2026-10-06 13:12:00 GMT
Created-Local: 2026-10-07 20:12:00 +0700
Coding-Agent: agy (gemini-3.1-pro-high, plan review)

# Task: gate review, M-next D12 — the recorded reader and replay (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted work in /Users/sto/workspace/datomworld-d12
(branch ucf-d12-reader, based on master 4c4764f7 with D11 and the
ClojureDart var fix landed). The engineer's report:
/Users/sto/workspace/datomworld-d12/collab/1791284000000-compiler
-engineer-ucf-d12-reader.findings.md. Read it first, then the two new
files (src/cljc/yin/vm/ucf/holder/reader.cljc and its test) — the diff
is two untracked files; nothing tracked changed.

The contract: D plan r3 section 1.4 and the D12 test contract
(/Users/sto/workspace/datomworld-d12/collab/1791194000000-architect
-m-next-d-plan-r3.claude-fable-5-1.findings.md), astra's findings 2-3
and residual 1 (1791191261015-...review... and
1791192700000-...r2-confirm...), the link-cursor ruling's D12
obligations (1791203000000-...: the driver mints unminted cells in
:seq order before observing reads on them; the recorded :newest
observation for link cursors; replay applies the recorded position
without live minting), the close ruling's four retained states under
:yin.k/held, and the D10 inputs ruling's prefix shape. The engine's
public applies (apply-observation, apply-mint, apply-link-cursor,
apply-put, apply-next, apply-ffi-read) are the only application path.

## What to attack

1. Apply-only-after-acknowledgment: no observation is applied before
   its `:recorded`/`:replayed` (counted in the tests); the four
   retained states transition exactly observed -> requested ->
   acknowledged -> applied, and the held state is gone after apply.
2. The held-observation rule: one observation in flight per entry;
   ten failed recording attempts leave one observation and one k; an
   unknown recording acceptance resends the IDENTICAL request
   (source, sequence, observed value unchanged).
3. Replay: while k is below the frontier nothing live is observed;
   record k applies to the first matching entry ordered by task path
   then wait-set order; aliasing is kept (the first acknowledged read
   advances the shared cell before the next entry's source is
   computed); two waiters on one cell replay successive positions;
   root and child with equal call ids replay to the right task.
4. Mints before reads: every unminted cell is minted in :seq order
   before a read on it is observed; minting is a recorded :cursor
   observation; replay applies the recorded position via apply-mint
   with zero live mints (residual 1: a :cursor source carries the
   origin, not the resulting position).
5. Divergence: declared only when the ready queues are empty, no
   fenced write awaits, no input is in states 1-3, no control request
   is outstanding, and no waiting entry matches record k. Waiting for
   a fenced-write reply is never divergence (the D11 writer's
   retained sends are the case to check).
6. :input-conflict ends the run with NO quarantine and is never
   translated to :intent-conflict.
7. The empty-path defect the engineer found in their own code
   (update-in with the root's empty path corrupting held-state
   transitions): verify the fix and sweep reader.cljc for any other
   empty-path assoc/update hazard (the D11 defect class).
8. Seam rules: the input appender and handle observer are
   composition-supplied functions; replay is pure (no observer); no
   transport vocabulary; the engineer's two judgment calls (the row-5
   divergent leg is test-authored; the input-conflict fixture's
   opaque request id) — accept or flag. Portability and style
   (:cljd-first, no host-number traps, kondo/cljstyle clean per the
   orchestrator's runs).

Verdict first: READY or NOT READY (with what must change), then
numbered findings with file:line evidence. Read-only: edit nothing,
run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
