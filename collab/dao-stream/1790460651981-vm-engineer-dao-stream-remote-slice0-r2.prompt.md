Created-GMT: 2026-09-27 06:10:00 GMT
Created-Local: 2026-09-27 13:10:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 0, scope-extended)

# Task: dao.stream.remote Implementation — Slice 0, Round 2 (widened scope)

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master @ d81e50ad,
clean tracked tree).

Round 2 of slice 0. Round 1 (brief
collab/1790460142517-vm-engineer-dao-stream-remote-slice0.prompt.md)
correctly stopped BLOCKED: the outcome-set widening deterministically
breaks four consumer test files outside its allowance. The orchestrator
accepts that analysis and widens the scope accordingly. This brief is
the round-2 contract; the resumed agent carries it plus round 1's
findings (its BLOCKED report is the evidence base).

## Allowed files (complete set for this round)

Source:
- src/cljc/dao/stream.cljc (the outcome sets)
- src/cljc/dao/stream/observe.cljc (refused classification in step)
- src/cljc/yin/vm/engine.cljc (handle-put / handle-next refused rule)

Tests:
- test/dao/stream/ringbuffer_test.cljc (ringbuffer-manifest partitions)
- test/dao/stream/memory_log_test.cljc (memory-log-manifest partitions)
- test/dao/jing_test.cljc (pool-signals total-over-outcomes table)
- test/dao/stream/waitset_test.cljc (next/put outcome-plan pins)
- test/dao/stream_test.cljc (exact-set assertions; valid-manifest fixture)
- test/dao/stream/observe_test.cljc (the "fails if it grows" tables)
- test/yin/vm/engine_test.cljc (new refused-rule tests)

## Work items

1. Add :dao.stream/refused to outcomes-cursor / outcomes-next /
   outcomes-append in src/cljc/dao/stream.cljc, exactly matching the
   committed dao.stream.md amendments.
2. Conformance manifests (ringbuffer, memory-log): add refused to the
   partition it can produce (with a reason), or an exclusion with a
   reason, per how test/dao/stream/conformance.cljc models partitions;
   the partition assertion must pass.
3. Pinned tables (jing_test, waitset_test): add the refused key with
   each pool's/plan's actual refused semantics.
4. stream_test exact-set assertions and the valid-manifest fixture:
   update to the widened sets.
5. observe.cljc: classify a refused outcome as its own refused status in
   step (not the :defect default), once stream/valid-outcome? admits it.
6. engine.cljc: handle-put and handle-next treat refused as a shaped
   refusal (mirror poll-link-response's :status :refused pattern), not
   an ex-info throw. observe_test/engine_test: pin the new behaviors.
7. New tests for every behavior above, in the listed test files.

## Constraints

- Only the listed files. Pure ASCII, <= 80 columns on added/edited
  lines; cljstyle and kondo clean on touched files; no
  commit/stage/checkout/reset/stash; no leftover diagnostics.
- Verify: JVM full suite green (baseline 2,057/180,912/0 plus your new
  tests), Node green, Dart green — sequential, solo. Report exact
  counts.
- If any table's semantics cannot be updated without inventing
  behavior, STOP and report BLOCKED with the specific conflict.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
