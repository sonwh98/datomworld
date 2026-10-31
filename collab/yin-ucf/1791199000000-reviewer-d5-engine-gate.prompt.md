Created-GMT: 2026-10-05 12:30:00 GMT
Created-Local: 2026-10-05 19:30:00 +0700
Coding-Agent: glm (glm-5.3, plan review)

# Task: gate review, M-next D5 — the unminted cursor cell, the close queue, the fenced FFI request and the sweep gating (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted work in /Users/sto/workspace/datomworld-d5 (branch
ucf-d5-engine-r2, based on master ee3498de with D1 to D4 landed). The
engineer's report: /Users/sto/workspace/datomworld-d5/collab/
1791194940441-vm-engineer-ucf-d5-engine-r2.findings.md (two rounds: the
main round and the walker round appended). Read it first, then
`git -C /Users/sto/workspace/datomworld-d5 status` and `git -C
/Users/sto/workspace/datomworld-d5 diff`.

The contract: the D plan r3 section 1.2 as amended by three architect
rulings, all staged in that worktree's collab/: the D4 seam ruling
(1791195500000-architect-d4-engine-seam-ruling), the D5 cursor ruling
(1791197000000-architect-d5-cursor-register-ruling — the unminted cell,
apply-mint, the nine zero-call rows, no kernel edit for cursor), and the
close ruling (1791198000000-architect-d5-close-ruling — the
:yin.k/closes queue, :yin.k/issue stamps, apply-close, no park). Plus
the D4 gate's carry-forwards (the ordinary-sweep gating; the
engine.cljc:2236 reflow) and the orchestrator's walker authorization
(add :yin.k/gate, :yin.k/closes, :yin.k/issued as ASTWalkerVM record
fields so cesk-return preserves them; gated rows on all four kernels).

## What to attack

1. Behavior preservation with no gate: every split/pair in engine.cljc,
   ffi.cljc and the four kernel `:ffi-call` sites is `apply ∘ observe`
   of today. The ungated close, cursor and sweep must be byte-identical
   in effect. The engineer reports yin.vm.* + yin.repl.* + dao.stream.*
   1874 tests / 17899 assertions green (JVM).
2. The unminted cell: creation makes zero handle calls on every kernel;
   reads on an unminted cell park without touching it; apply-mint
   refuses non-unminted cells and :exporting/:ended, makes zero calls,
   seeds as the lower seeds, and a second apply-mint on one cell is
   refused; selection is by lowest :seq; cursor rows 1 to 9 of the
   cursor ruling are pinned.
3. The close queue: under :running, zero handle calls; issue stamps on
   gated parked puts and closes rise in issue order; apply-close
   removes exactly the record and refuses in the closed modes; closes
   are never inputs and never fenced; ungated close unchanged.
4. The sweep: under :running, zero handle calls for a gated task with
   parked puts/nexts (entries kept, not polled); ungated sweeps retry
   exactly as today. The engineer's note that wait-set order can
   change on a gated sweep (entries returned after other waiters) — is
   that acceptable within the machine semantics, or does any test or
   invariant depend on strict order?
5. The FFI fence: ffi/put-request with no gate calls today's
   apply2/put-request!; with any gate it answers :full with no handle
   call; each kernel's :ffi-call site changed by exactly that one
   call swap; the retained :ffi-request entry equals the ungated full
   branch's; no issue stamp on FFI entries.
6. The walker record fields: the three keys default falsy, ungated
   behavior unchanged, cesk-return preserves them, and a test proves
   the gate survives a rebuild. Check the record addition is minimal
   and touches no other walker logic.
7. Scope: the diff touches only engine.cljc, ffi.cljc, the four
   kernels, ast_walker.cljc and engine_gate_test.cljc. No wire change,
   no :yin.k/held writes (D12's), nothing outside tests sets
   :yin.k/gate. Portability and style of the new tests; the engineer's
   cell-for! concern (D8/D9's refusal) is out of scope for D5.

Verdict first: READY or NOT READY (with what must change), then numbered
findings with file:line evidence. Read-only: edit nothing, run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
