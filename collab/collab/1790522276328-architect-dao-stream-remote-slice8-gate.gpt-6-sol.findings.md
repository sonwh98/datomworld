Completed-GMT: 2026-09-27 15:20:36 GMT
Completed-Local: 2026-09-27 22:20:36 +07

P1 | [remote.cljc:119](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:119) | The facade accepts invented `{:handle … :position …}` entries. Parked VM waits instead use `:stream-id`, `:cursor-ref`, and private `:resources` ([semantic.cljc:106](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:106); [engine.cljc:522](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:522)). Nothing connects those states to this facade, so its round trips do not establish that a parked wait resumes. | Lift from actual parked entries and resources; lower into fresh resource entries and wait-set entries, preserving VM context.

P1 | [remote.cljc:144](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:144) | Lift copies `:position` and declares `:dao.stream.remote/v1` without checking that the cursor is portable through the codec or that the source honors it. The acceptance tests pass an in-process cursor directly ([remote_test.cljc:94](/Users/sto/workspace/datomworld/test/yin/vm/ucf/remote_test.cljc:94)). | Check the profile before publishing UCF data and test a codec round trip of the kept cursor before resume and eviction.

P1 | [remote.cljc:57](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:57) | Every lift uses `:yin.k/c-0` in a separate table. No frame-level assembly or lowering preserves two waiters sharing one cursor cell, as §7.5.3 and the §7.11 pending-state blocker require. | Mint and remap cells across the whole frame, then test shared and independent cells at the same position.

All seven named pending variants have lift and lower branches and individual round-trip tests. The new module requires only `dao.stream`; I found no circular require or network dependency in `yin.vm` core. I did not edit files or rerun suites.

Verdict: REQUEST CHANGES
Sign-off: DENIED