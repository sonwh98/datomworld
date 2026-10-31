Created-GMT: 2026-10-05 21:13:07 GMT
Created-Local: 2026-10-06 04:13:07 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: reviewer-head-link

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-06 04:13:07 +07 | Status: active | Rationale: independent family (GPT) from the Claude engineers that authored the change

Perform a read-only review of one small change in
/Users/sto/workspace/datomworld/.claude/worktrees/head-link (UNCOMMITTED; `git diff`
shows all of it): `src/cljc/yin/repl/link.cljc` (`dht-attempt`),
`test/yin/repl/dht_test.cljc` (three new tests, two helpers) and
`docs/design/yin.vm.linker.dht.md` (a bullet in the link-attempt order and a section 9
response row). It sits on top of head-trace slices H0 and H1 (reviewed, committed).
Do not edit anything. The engineer's report is untrusted:
`collab/1791234307600-storage-engineer-head-link-r2.claude-opus-5-5.stdout.log`. Note
the work was started by one engineer, interrupted at a usage limit, and finished by
another; check that nothing half-done survived.

## The ruling it implements (Architect, verbatim)

"The real defect is older: `dht-attempt` is kind-blind. It also forgets a user's
failed index load made by hand, and waits on or tries to link records of other kinds.
D3 makes that wrong." Correction: "In `dht-attempt`, after the `(nil? status)` branch,
add a branch for `(not= linker.dht/module-kind (:kind status))`. It answers
`{:status :refused :reason :dao.space.dht/kind-conflict :address address :recorded
(:kind status)}` and forgets nothing ... and one test: a `require` resolving to a
failed candidate's address leaves the record in place and the retry delay intact."
Governing design: `docs/design/yin.vm.linker.dht.head.md` 5.5 ("Kinds do not mix
(D3)", the Unloadable rule) and `docs/design/yin.vm.linker.dht.md` section 9.

## Verified by the orchestrator (do not repeat)

JVM: `yin.repl.require-test`, `yin.repl.dht-test`, `yin.vm.linker.dht-test`,
`yin.vm.linker.head-follow-test`, `yin.vm.linker.dht-end-to-end-test`: 109 tests, 1632
assertions, 0 failures; kondo clean; no `and false` left in `link.cljc`; the added doc
lines are ASCII and at most 80 columns. Node and Dart lanes have not run.

## What to attack

- Is the branch correct and in the right place? Can any record of another kind, in
  any status, still be forgotten, waited on or linked by a `require`? Does a
  module-kind record behave exactly as before?
- The engineer claims `linker.dht/module-status` passes through to `load-status` and
  never hides a non-module record, so `(nil? status)` means no record at all and
  `load-module` cannot throw H1's kind-conflict. Verify it in the code. Is there any
  other path by which a `require` can throw through the host interpreter (a race
  between the status read and `load-module` within one step; another caller of
  `load-module` or `dht/load` in `link.cljc` or `query.cljc`)?
- Is the refusal's shape the one `dht-attempt`'s callers and `serve` expect, and does
  the REPL print a sensible line for it ("Module link refused: kind-conflict")? Is the
  pending/parked require handling right (never parked on a foreign record)?
- The tests: does each of the four cases really fail without the branch (the engineer
  reports 36 failures with it disabled)? Is the retry-delay assertion in the
  failed-candidate test meaningful, or can it pass vacuously?
- Do the doc lines say what the code does, and do they contradict anything nearby in
  section 9 or the link-attempt order?
- Portability CLJ, CLJS, CLJD of anything added.

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: <exact thread id>

Report actionable findings as `P0-P3 | file:line | evidence | concrete fix`, or "No
actionable findings". End with one line: ready to commit once the Node and Dart lanes
pass, or the specific blockers.
