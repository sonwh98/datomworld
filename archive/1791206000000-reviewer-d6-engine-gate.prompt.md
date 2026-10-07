Created-GMT: 2026-10-05 14:05:00 GMT
Created-Local: 2026-10-05 21:05:00 +0700
Coding-Agent: glm (glm-5.3, plan review)

# Task: gate review, M-next D6 — gated links, child stamping, direct resume, and the driver's public applies (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted-then-committed work in
/Users/sto/workspace/datomworld-d6 (branch ucf-d6-engine-r3, based on
master 8efde902 with D1 to D5 landed; the work is the branch's single
commit). The engineer's report (three rounds):
/Users/sto/workspace/datomworld-d6/collab/1791200000000-vm-engineer
-ucf-d6-engine-r3.findings.md. Read it first, then
`git -C /Users/sto/workspace/datomworld-d6 show HEAD` (engine.cljc,
module.cljc, engine_gate_test.cljc).

The contract: D plan r3 section 1.2 as amended by the architect's
rulings (all staged in that worktree's collab/): the D4 seam ruling,
the D5 cursor ruling, the close ruling, and the link-cursor ruling
(1791203000000-architect-d6-link-cursor-ruling — apply-link-cursor,
apply-link-sent, apply-link-read, the wider-gap confirmation). D6's
scope: the gated link append (`module/append-link-request`'s new 3-arity
returns the entry unchanged, zero appends), the skipped
`:dao.stream/newest` mint, child stamping (the root's gate and nothing
else), install children and direct resume refused in the closed modes,
the gated sweep extended to links/FFI/closed-mode puts/nexts, and the
driver's public applies: apply-link-cursor, apply-link-sent,
apply-link-read, plus apply-put, apply-next, apply-ffi-sent,
apply-ffi-read (the four the ruling's wider-gap check confirmed
missing). The engineer flagged three deviations, all in the report:
entries identified by value not id; apply-ffi-read wakes another live
waiter rather than skipping (a skip would lose a response — the router's
own behavior); apply-ffi-sent covers :ok only, with the terminal-outcome
gap deferred to D11.

## What to attack

1. Ungated behavior unchanged: the diff's every ungated path, including
   the private apply-put/apply-next renames to -outcome, the extracted
   ffi-read-step loop body, and the 2-arity append-link-request kept for
   yin.vm.ucf.remote. The engineer reports 89 tests / 1212 assertions
   green across the focused engine namespaces.
2. The ruled rows: apply-link-cursor's rows 1 to 8 (install, entry
   unchanged except :cursor, order and queue untouched, zero calls,
   double install and unknown id refused, two pending requires, gated
   sweep after install), apply-link-sent (no cursor refused, cursor
   kept, second send refused), apply-link-read (blocked waits, foreign
   ok skips and advances, own refused response wakes :link-refused, gap
   and end refuse the entry, unknown/unsent refused).
3. The four non-link applies: equivalence with the ungated apply given
   the same outcome; refusals on ungated machines and closed modes;
   apply-ffi-read's shared-reader routing matches poll-ffi-responses'
   router (including the deviation's wake-don't-skip).
4. Gate completeness, now as protocol: every observation a gated task
   parks has a public apply; nothing in src sets :yin.k/gate; the
   closed-mode refusals are total (cursor creation included, the D5
   hardening).
5. Scope and style: only the three named files; kondo 0/0; cljstyle
   clean (the orchestrator ran it); the tests' portability.
6. The three flagged deviations and the deferred terminal-outcome gap:
   are the deferrals correctly scoped to D11, or is anything a defect
   of this slice?

Verdict first: READY or NOT READY (with what must change), then numbered
findings with file:line evidence. Read-only: edit nothing, run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
