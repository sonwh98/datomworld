Created-GMT: 2026-10-05 12:55:00 GMT
Created-Local: 2026-10-05 19:55:00 +0700
Coding-Agent: claude (opus-5-5)
Session-ID: pending (provider-generated)

# Task: UCF M-next D6 — engine round 3: links, install children, direct resume, child stamping, and the gate-completeness test
Role: VM Runtime Engineer (engine seam)

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 19:55:00 +0700 | Status: active | Rationale: the engine seam is serialized across rounds; this engineer family holds the D4/D5 context via the plan and rulings

Implement D6 in /Users/sto/workspace/datomworld-d6 (worktree, branch
ucf-d6-engine-r3, rebased onto master with D5 landed). Read first, in
the worktree's collab/: the D plan r3 (1791194000000-architect-m-next
-d-plan-r3.claude-fable-5-1.findings.md — sections 1.2, 1.4, 1.5 and
the D6 test contract), the D4 seam ruling (1791195500000-architect
-d4-engine-seam-ruling.claude-fable-5-1.findings.md — the link ruling
and the gate-completeness rule), the D5 cursor ruling
(1791197000000-architect-d5-cursor-register-ruling.claude-fable-5-1
.findings.md), the close ruling (1791198000000-architect-d5-close
-ruling.claude-fable-5-1.findings.md), and the D4 and D5 gate reviews
(1791198500000 and 1791199000000 reviewer files). Then
src/cljc/yin/vm/module.cljc (`append-link-request`, `require-handler`),
src/cljc/yin/vm/engine.cljc (the install-child advance path at about
line 1232, direct resume, and the D4/D5 gate state), and
test/yin/vm/engine_gate_test.cljc.

D6's scope (r4 delta + r3):

1. **The gated link append.** `module/append-link-request` takes the
   state (or its gate mode) and, when gated, returns the entry
   unchanged — its existing `full` result — with zero appends. The
   require flow's behavior is otherwise unchanged.
2. **The deferred link-response cursor.** `require-handler` mints a
   `:dao.stream/newest` cursor on the response stream before it
   appends. Under a gate that mint is not performed: the
   `:link-request` entry is built without `:cursor`. The driver mints
   it before the first send, as a recorded `:cursor` observation with
   origin `:dao.stream/newest`, and installs it on the entry. The
   cursor-before-append order is kept, by the driver. If no existing
   public apply fits installing that cursor on the entry, stop and
   report — that is a ruling, not an engineering choice.
3. **Child stamping.** The engine stamps `:yin.k/gate` on a child's
   machine value when it creates the child and again before each
   `vm/run` of it. A child carries the mode only: no counters, no
   lease, no input state, no custody map.
4. **Install-child advance under a gate.** In `:exporting` and
   `:ended` nothing is observed and no child is advanced; a direct
   resume of a parked record is refused; every public apply refuses a
   late result (D4/D5 already refuse; complete the child paths).
5. **The gate-completeness test.** After D6, code outside tests may
   set `:yin.k/gate`. The test must run the whole D4+D5+D6 zero-call
   list again on all four kernels (semantic, de Bruijn stack, de
   Bruijn register, AST walker): every immediate effect, the sweep
   with parked entries, an FFI call first attempt and retry, a
   `require` miss (zero appends, zero cursor mints, a `:link-request`
   entry with no cursor), link-response scanning, child creation and
   advance, and direct resume — zero handle calls under `:running`,
   refusal under `:exporting`/`:ended`.

Test contract:
- Test-first per row; portable `.cljc`; JVM during iteration.
- The existing suites stay green with no gate (yin.vm.*, yin.repl.*,
  dao.stream.* focused run).

Acceptance criteria:
- `git diff` touches only: src/cljc/yin/vm/engine.cljc,
  src/cljc/yin/vm/module.cljc, test/yin/vm/engine_gate_test.cljc.
  Anything else: stop and report.
- After D6, the gate is complete: say so explicitly in the report, and
  confirm nothing in the diff still observes for a gated task.

Constraints:
- No git writes. kondo you may run; cljstyle is the orchestrator's.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches; no float
  literals in test data where a host could diverge.
- The engine is serialized across D4/D5/D6: keep the diff mechanical.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red and
green evidence, unresolved concerns, and any incomplete work. Do not claim
edits or tests that did not occur.
