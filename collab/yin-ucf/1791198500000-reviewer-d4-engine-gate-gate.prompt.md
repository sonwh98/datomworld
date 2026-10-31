Created-GMT: 2026-10-05 10:02:00 GMT
Created-Local: 2026-10-05 17:02:00 +0700
Coding-Agent: glm (glm-5.3, plan review)

# Task: gate review, M-next D4 — engine round 1, the observe/apply split and the custody gate (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted work in /Users/sto/workspace/datomworld-d4 (branch
ucf-d4-engine-r1, based on master 14d9f750). The engineer's report:
/Users/sto/workspace/datomworld-d4/collab/1791194500000-vm-engineer-ucf
-d4-engine-r1.findings.md. Read it first, then `git -C
/Users/sto/workspace/datomworld-d4 status` and `git -C
/Users/sto/workspace/datomworld-d4 diff` (src/cljc/yin/vm.cljc,
src/cljc/yin/vm/engine.cljc, test/yin/vm/engine_gate_test.cljc).

The contract (D plan r3 section 1.2 as amended by the architect's two
rulings, both staged in that worktree's collab/: the D4 ruling
1791195500000-architect-d4-engine-seam-ruling and the D5 cursor ruling
1791197000000-architect-d5-cursor-register-ruling): D4 covers the
immediate effects that live in engine.cljc. Landed this round: put and
next split into observe/apply halves (ungated behavior `apply ∘ observe`
unchanged); under `:running` put parks as its `:put` entry and next as
its `:next` entry with zero handle calls; poll parks as a machine-only
`:observe` entry (`:op :poll`); `check-wait-set` skips `:observe`
entries; public `engine/apply-observation` applies a held poll outcome
(ok/blocked/gap) with no stream call and refuses in `:exporting` and
`:ended`; `yin.vm/gate-mode` reads `:yin.k/gate`. Deferred to D5/D6 by
ruling: gated close (still calls close! under :running; refused in
:exporting/:ended), gated cursor (the unminted-cell design), the FFI and
link appends. Nothing outside tests may set `:yin.k/gate` until D6.

## What to attack

1. Behavior preservation with no gate: the diff's ungated path is
   byte-for-byte `apply ∘ observe` of today. Check each split pair and
   every call site for a changed answer, a lost park, or a reordered
   effect. The engineer reports the existing suites green on the JVM
   (yin.vm.* 1221 tests / 12873 assertions; yin.repl.* + datomworld.*
   306 / 2410).
2. The gate's completeness for what it claims: under `:running`, do
   put/next/poll truly make zero handle calls? Can any path observe for
   a gated root that the diff missed (including the wait-set sweep
   reaching a `:put`/`:next` entry of a gated task — retrying appends
   and reads on every sweep is today's behavior; what happens now)?
3. The `:observe` entry: machine-only (no wire variant), skipped by the
   sweep in every mode, and its fields (op, stream-id, cursor-ref)
   well-formed from the caller's builders. Can it leak to the wire
   (observed-wire) or be resumed by the ordinary sweep?
4. `apply-observation`: equivalence with the ungated poll for ok,
   blocked and gap; refusal in :exporting/:ended; no handle call; the
   entry removed and the continuation queued through the existing
   woken-entry path.
5. Deferred items are genuinely deferred, not dropped: close under
   :running still calls close! (documented, D5's), :exporting/:ended
   refuse it with no handle call; cursor is untouched.
6. Portability and style of the new tests (.cljc, :cljd-first, no
   host-number traps, ASCII, <= 80 columns); no git writes happened;
   the diff touches only the three named files.

Verdict first: READY or NOT READY (with what must change), then numbered
findings with file:line evidence. Read-only: edit nothing, run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
