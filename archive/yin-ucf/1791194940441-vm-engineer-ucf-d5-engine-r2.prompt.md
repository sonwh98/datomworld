Created-GMT: 2026-10-05 10:09:00 GMT
Created-Local: 2026-10-05 17:09:00 +0700
Coding-Agent: claude (opus-5-5)
Session-ID: pending (provider-generated)

# Task: UCF M-next D5 — engine round 2: the unminted cursor cell, the close queue, and the fenced FFI request
Role: VM Runtime Engineer (engine seam)

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 17:09:00 +0700 | Status: active | Rationale: the engine seam is serialized across rounds; this engineer family holds the D4 context via the plan and rulings

Implement D5 in /Users/sto/workspace/datomworld-d5 (worktree, branch
ucf-d5-engine-r2, rebased onto master). Read first, in the worktree's
collab/: the D plan r3 (1791194000000-architect-m-next-d-plan-r3
.claude-fable-5-1.findings.md — sections 1.2, 1.3, 1.5 and the D5 test
contract), the D4 ruling (1791195500000-architect-d4-engine-seam-ruling
.claude-fable-5-1.findings.md), the D5 cursor ruling
(1791197000000-architect-d5-cursor-register-ruling.claude-fable-5-1
.findings.md — sections 1 to 3 and the r5 delta), and the close ruling
(1791198000000-architect-d5-close-ruling.claude-fable-5-1.findings.md).
Then src/cljc/yin/vm/engine.cljc (the D4 state is on master's D4 branch
only if it landed; otherwise read the D4 diff in the d4 worktree at
/Users/sto/workspace/datomworld-d4), src/cljc/yin/vm/ffi.cljc, the four
kernels' :ffi-call sites, and the D4 test file test/yin/vm/
engine_gate_test.cljc (it will already be on your branch if D4 landed
first; the orchestrator lands D4 before you start).

D5's scope, from the rulings:

1. **The unminted cursor cell** (cursor ruling): under any gate mode,
   `:stream/cursor` in handle-effect verifies the reference, takes the
   id, issues the sealed cursor reference, and installs an unminted
   cell `{:stream-id id :yin.k/unminted {:origin ... :seq n}}` — no
   handle call, not blocked, the kernels see an ordinary immediate. No
   kernel is edited for cursor. Add `engine/apply-mint
   [state cell-id position]` (refuses a cell that is not unminted,
   refuses in :exporting/:ended, zero handle calls, seeding as the
   lower seeds a cursor). Selection among equal sources is by lowest
   `:seq`. Reads on an unminted cell park without touching it.
2. **The close queue** (close ruling): under `:running`,
   `:stream/close` verifies the reference, appends
   `{:stream-id id :yin.k/issue n}` to `:yin.k/closes` on the machine
   value, and returns nil — no park, no kernel edit, zero handle
   calls. `handle-effect` stamps `:yin.k/issue n` (counter
   `:yin.k/issued`, machine-only) on every `:put` entry it parks and
   on every close record. FFI/link entries are not stamped. Add
   `engine/apply-close [state stream-id issue]` (removes the record,
   refuses in :exporting/:ended). Closes are never inputs and are not
   fenced.
3. **The fenced FFI request** (D4 ruling): new
   `yin.vm.ffi/put-request [state call-in request]` — gate mode nil
   calls today's `apply2/put-request!`; any gate mode answers
   `{:dao.stream/outcome :dao.stream/full}` with no handle call. Each
   of the four kernels (semantic.cljc, debruijn/stack.cljc,
   debruijn/register.cljc, ast_walker.cljc) replaces its one
   `apply2/put-request!` call at the `:ffi-call` site with this
   function; nothing else at the site changes.
4. **The ordinary sweep** (r3's D5 row; D4 gate finding 3): under
   `:running`, `check-wait-set`'s ordinary sweep must no longer poll a
   gated task's parked `:put`/`:next` entries — today the sweep
   retries their `append!`/`next` on every pass, a live handle call
   under custody. Filter them out (or move them beside the
   engine-polled class) so a sweep makes zero handle calls for a
   gated task; the driver owns the retries through the writer (D11).
   Ungated sweeps are byte-identical to today.
5. **Gated close/cursor in :exporting and :ended**: the site is not
   reached (the task does not run); the D4 guards stay.

Test contract (the rulings' zero-call rows plus the sweep):
- Sweep rows: under `:running`, a parked put and a parked next are not
  polled by any sweep (zero handle calls, entries kept); ungated
  sweeps still retry them exactly as today.
- Cursor rows 1 to 9 of the cursor ruling's list (per kernel where it
  says so; rows 8 and 9 once on any kernel).
- Close rows: under `:running`, close makes zero handle calls, queues
  the record, returns nil, and the program continues; `:yin.k/issued`
  stamps rise across a parked put and a close in issue order;
  apply-close removes exactly the record and refuses in
  :exporting/:ended; ungated close is byte-identical to today.
- FFI rows: on each of the four kernels, under `:running`, the first
  attempt of an FFI call makes zero `put-request!` calls and parks a
  retained `:ffi-request` entry identical to the ungated `full`
  branch's; the retained request's retry behaves as the D4 ruling's
  D5 list says.
- The existing suites stay green with no gate.

Acceptance criteria:
- Test-first per row; portable `.cljc` tests; JVM during iteration.
- `git diff` touches only: src/cljc/yin/vm/engine.cljc,
  src/cljc/yin/vm/ffi.cljc, the four kernels' `:ffi-call` sites,
  test/yin/vm/engine_gate_test.cljc (and yin/vm.cljc if gate-mode needs
  a companion). Anything else: stop and report.
- The D5 stop condition (cursor ruling): if any engine path other than
  handle-next, handle-poll and the sweep reads a cursor cell's cursor
  while a gate is set, stop and report it.

Constraints:
- No git writes. kondo you may run; cljstyle is the orchestrator's.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches; no float
  literals in test data where a host could diverge.
- Carry-forwards from D4's gate: reflow engine.cljc:2236 (81 columns,
  the poll branch) to <= 80; the sweep gating above is D5's own
  obligation, not optional.
- The engine is serialized across D4/D5/D6: keep the diff mechanical.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red and
green evidence, unresolved concerns, and any incomplete work. Do not claim
edits or tests that did not occur.
