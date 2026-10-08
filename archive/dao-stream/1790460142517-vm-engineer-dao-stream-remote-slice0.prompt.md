Created-GMT: 2026-09-27 03:55:00 GMT
Created-Local: 2026-09-27 10:55:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 0)

# Task: dao.stream.remote Implementation — Slice 0 (contract and source alignment)

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master @ d81e50ad,
clean tracked tree).

The owner has accepted the dao.stream.remote spec set (final gate READY).
Implement Slice 0 exactly as the plan defines it. Read first:
- docs/design/dao.stream.remote.implementation-plan.md section 2 (the
  contract boundary: OD-1, OD-2, OD-3, the :dao.stream/refused rows on
  cursor, next and append!, the composed-handle sentence) and the
  slice-0 row of section 3
- docs/design/dao.stream.md (the amended contract: the :dao.stream/refused
  outcome row and the amended OD decision text — these amendments are
  already in the committed dao.stream.md)
- docs/agents/build-n-test.md (verification commands)

Work items:
1. Add :dao.stream/refused to the closed outcome sets for cursor, next
   and append! in src/cljc/dao/stream.cljc, matching the committed
   dao.stream.md amendments exactly (names, positions, doc strings).
2. Make dao.stream.observe/step and yin.vm.engine treat an unrecognized
   outcome as refused rather than throwing (the unrecognized-outcome
   rule), per the plan's Proves column.
3. Tests: the outcome sets admit the refusal; the observe/step path and
   the engine handle an unrecognized outcome as refused. Add tests to
   the existing test files for those namespaces.

Constraints:
- Touch only src/cljc/dao/stream.cljc, src/cljc/yin/vm/engine.cljc (if
  the rule applies there), src/cljc/dao/stream/observe.cljc (if it
  exists as such — locate the actual observe/step source first), and
  their existing test files. If the rule turns out to touch other
  consumers, stop and report BLOCKED with the list.
- Pure ASCII, <= 80 columns on every added/edited line; cljstyle and
  kondo clean; no commit/stage/checkout/reset/stash; no leftover
  diagnostics.
- Verify: JVM full suite green (baseline 2,057/180,912/0 plus your new
  tests), Node green, Dart green. Sequential, solo. Report exact
  counts.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
