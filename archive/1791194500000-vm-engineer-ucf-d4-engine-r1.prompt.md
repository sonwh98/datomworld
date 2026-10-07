Created-GMT: 2026-10-05 09:41:00 GMT
Created-Local: 2026-10-05 16:41:00 +0700
Coding-Agent: claude (opus-5-5)
Session-ID: pending (provider-generated)

# Task: UCF M-next D4 — engine round 1: the observe/apply split of the immediate effects and the custody gate modes
Role: VM Runtime Engineer (engine seam)

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 16:41:00 +0700 | Status: active | Rationale: the engine seam is the D plan's riskiest slice; the D plan assigns it to engine rounds with a zero-call test control

Implement D4 in /Users/sto/workspace/datomworld-d4 (worktree, branch
ucf-d4-engine-r1, based on master 14d9f750). Read first: the D plan r2
(collab/1791191725340-architect-m-next-d-plan-r2.claude-fable-5-1
.findings.md — sections 1.1, 1.2 and the D4 test contract) and the
review it folded (collab/1791191261015-architect-m-next-d-plan-review
.gpt-6-astra.findings.md, finding 1). Then src/cljc/yin/vm/engine.cljc —
the immediate-effect handlers (`handle-put`, `handle-next`,
`handle-poll`, `handle-cursor`, `handle-close`, the FFI request append,
the link request append), their call sites, and the existing suites that
pin today's behavior.

The contract (r2 1.2):

**Split.** Every place the engine touches a stream becomes two
functions: observe (perform the IO, return an outcome as data) and apply
(outcome to machine value: wake, park, convert a retained request into a
sent one, advance a cell). Ungated behavior is `apply ∘ observe`,
unchanged; the existing engine, FFI, link and handoff suites are the
proof. D4 covers the immediate effects only:
`:stream/put`, `:stream/next`, `:stream/poll`, `:stream/cursor`,
`:stream/close`, the FFI request append, and the link request append.
The ordinary sweep, FFI/link response routing, child advance, direct
resume and child stamping are rounds D5/D6 — do not touch them.

**Modes.** The gate reads one key, `:yin.k/gate`, on the root machine
value: absent (ungated, today's behavior), `:running` (internal
computation proceeds; every observation parks; the driver observes and
applies), `:exporting` and `:ended` (nothing is observed, and every
public apply refuses a late result). Child stamping is D6; D4 needs only
that a gated root's own immediates park.

**Held immediates.** Under `:running`, `:stream/poll` and
`:stream/cursor` (observations with no wait variant) park as-if-blocked
and hold their exact observed value as data for the driver: apply
installs it when the driver calls it, and until then the task is not at
a liftable safepoint (export is not D4's concern; only the holding
state is). If the parked-record or wait grammar cannot represent the
held observation without inventing a new wire variant, STOP and report —
r2 forbids a new wire variant, and that is a ruling, not an engineering
choice.

Test contract (r2, D4 row):
- The existing suites pass with no gate: engine, FFI, link, handoff
  (focused JVM during iteration; orchestrator runs three lanes).
- With the gate, counting handles show ZERO calls for each immediate
  effect (`:stream/put`, `:stream/next`, `:stream/poll`,
  `:stream/cursor`, `:stream/close`, FFI call, link require): each
  parks.
- Applying a supplied outcome produces the same machine value as the
  ungated path given that outcome (the split is behavior-preserving).
- In `:exporting` and `:ended`: no observation happens for the root's
  immediates and each public apply refuses a late result.

Acceptance criteria:
- Test-first per behavior; portable `.cljc` tests; JVM during
  iteration.
- `git diff` shows only src/cljc/yin/vm/engine.cljc and its new/updated
  test files. Anything else: stop and report.
- No change to the wire, the UCF grammar, or `yin.vm.ucf.handoff`.

Constraints:
- No git writes. kondo you may run; cljstyle is the orchestrator's.
- Lessons: `#?(:cljd nil :clj ...)` order for JVM-only test branches
  (:cljd first); a 0.0 literal is the integer 0 on JS.
- The engine is serialized across D4/D5/D6: keep the diff as small and
  mechanical as the contract allows; the next round builds on it.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red and
green evidence, the full list of observe/apply pairs you split,
unresolved concerns, and any incomplete work. Do not claim edits or tests
that did not occur.
