Completed-GMT: 2026-09-25 11:53:55 GMT
Completed-Local: 2026-09-25 18:53:55 Asia/Ho_Chi_Minh

The direct `:vm/store-put` and computed-key cases are now guarded, and the earlier invocation-order and bounds fixes remain intact. One alias path still blocks sign-off.

P1 | [linker.cljc:401](/Users/sto/workspace/datomworld-ucf-phase2/src/cljc/yin/vm/linker.cljc:401) | The guard recognizes calls whose operator is visibly `yin/def`, but primitives are first-class values ([UCF:597](/Users/sto/workspace/datomworld-ucf-phase2/docs/design/yin.vm.universal-continuation-format.md:597)). A lambda can receive `yin/def` as `setter` and call `(setter 'yin/def 0)` before `(yin/def 'x 1)`. That first call writes the active store, yet matches neither the direct-key check nor the computed-key query; the scanner then incorrectly discharges a subsequent read of `x` ([linker.cljc:474](/Users/sto/workspace/datomworld-ucf-phase2/src/cljc/yin/vm/linker.cljc:474)). | Account for effectful primitive aliases when proving `yin/def` unshadowed, retaining obligations wherever a prior call may write its slot. Add a nested alias-call regression test.

The AST `:vm/store-put` key is a direct `:key` slot, not an evaluated child ([yin.vm.cljc:807](/Users/sto/workspace/datomworld-ucf-phase2/src/cljc/yin/vm.cljc:807)). The independent tri-host runs and style check are green; clj-kondo was not available. They do not cover the alias case.

Verdict: REQUEST CHANGES
Sign-off: DENIED
