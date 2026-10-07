Created-GMT: 2026-10-06 12:40:00 GMT
Created-Local: 2026-10-07 19:40:00 +0700
Coding-Agent: glm (glm-5.3)
Session-ID: f5504dbd-5803-46a4-a5f5-517f21a41161

# Task: UCF M-next D12 — the recorded reader and replay
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-10-07 19:40:00 +0700 | Status: active | Rationale: the holder-side reader, mirroring D11's writer

Implement D12 in /Users/sto/workspace/datomworld-d12 (worktree, branch
ucf-d12-reader, based on master with D11 landed). Read first, in the
worktree's collab/: the D plan r3 (1791194000000-architect-m-next
-d-plan-r3.claude-fable-5-1.findings.md — section 1.4 and the D12 test
contract), the astra review's findings 2-3 (held immediates, replay
identity) and its confirmation residual 1 (cursor sources), and the
rulings' D12 obligations: the link-cursor ruling (1791203000000-... —
the driver mints unminted cells in :seq order before observing reads
on them; the recorded :newest observation for link cursors; replay
applies the recorded position without minting), the close ruling
(1791198000000-... — the four retained states on entries under
:yin.k/held), and the D10 inputs ruling's prefix/input shapes. Then
src/cljc/yin/vm/ucf/holder/ (writer.cljc as the sibling pattern,
evidence.cljc's :yin.k/prefix), src/cljc/yin/vm/engine.cljc (the
public applies: apply-observation, apply-mint, apply-link-cursor,
apply-put, apply-next, apply-ffi-read), and UCF 7.7.7/7.7.8's input
protocol and 14.2.2's source structure.

The contract (r3 1.4 as amended):

1. **The source** of every observation lives inside `:yin.k/name`:
   read kinds `{:yin.k/op :next|:poll|:cursor, :yin.k/task path,
   :yin.k/stream identity, :yin.k/position p}` (for :cursor the
   origin — :dao.stream/oldest or :dao.stream/newest — replaces the
   position); FFI result `{:yin.k/op :ffi-result, :yin.k/task path,
   :yin.k/call-id id}`; link result `{:yin.k/op :link-result,
   :yin.k/task path, :yin.k/link-id id}`. No cell id, host cursor or
   sealed reference appears in a source or an observed value.
2. **Live**: observe the handle, send `:yin.k/input` with the source
   and the observed outcome, apply only after `:recorded` or
   `:replayed` (through the public applies; `:yin.k/held` holds the
   four retained states on the entry or cell).
3. **The held-observation rule**: a held observation remains local and
   unexportable until its exact portable observation is durably
   acknowledged and applied once; recording retries neither repeat
   the observation nor change its source, sequence, or observed value
   (a held poll survives ten failed recording attempts with one
   observation and one k).
4. **Replay**: while k is below the frontier the reader observes
   nothing live; record k is applied to the selected entry (order by
   task path, then wait-set order, first match; aliasing kept — the
   first acknowledged read advances the shared cell before the next
   entry's source is computed). Divergence ends the run only when the
   ready queues are empty, no fenced write awaits, no input is in
   states 1-3, no control request is outstanding, and no waiting entry
   matches record k.
5. **Unminted cells**: the driver mints every unminted cell in :seq
   order before observing reads on them (D5 ruling; the conservative
   export refusal becomes permanent otherwise). Minting is a recorded
   :cursor observation; replay applies the recorded position via
   apply-mint without live minting.
6. **An input conflict** (`:input-conflict`) ends the run with no
   quarantine (never translated to :intent-conflict).

Test contract (r3's D12 row):
- Nothing is applied before its acknowledgment (counted).
- A held poll survives ten failed recording attempts with one
  observation and one k.
- Unknown recording acceptance resends the identical request.
- Two waiters on one cell replay successive positions.
- Root and child with equal call ids replay to the right task.
- Divergence is not declared while a fenced write awaits its outcome.
- :input-conflict ends the run with no quarantine.
- 14.2.4 row 5: an evicted value, recovered with records and without.
- Cursor replay without live minting (residual 1: the recorded
  position is applied via apply-mint; the source carries the origin).

Acceptance criteria:
- Test-first per behavior; portable `.cljc`; JVM during iteration.
- New namespace src/cljc/yin/vm/ucf/holder/reader.cljc and its test
  file; engine.cljc only if a public apply needs a small addition
  (say so). Anything else: stop and report.
- No transport vocabulary: the input appender and the handle observer
  are composition-supplied functions, mirroring the writer's seam.

Constraints:
- No git writes. kondo/cljstyle may be sandbox-blocked; note it.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); a 0.0 literal is the integer 0 on JS.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.
