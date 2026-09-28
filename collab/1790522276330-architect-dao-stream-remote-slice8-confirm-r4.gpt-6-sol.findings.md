Completed-GMT: 2026-09-27 20:07:00 GMT
Completed-Local: 2026-09-28 03:07:00 Asia/Ho_Chi_Minh

**P1 — the response route still occupies the receiver’s fixed cursor key.** The fix preserves the receiver’s call-in and call-out stream keys, and the retained retry uses a fresh reflection key ([remote.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:637)). But lowering installs the emitter’s response cell at `vm/call-out-cursor-key` ([remote.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:524)), which the engine also uses for every new call’s response wait ([semantic.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:106)). The engine resolves that wait through the cursor cell’s stream, so a new call’s response wait initially reads the emitter’s call-out ([engine.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:522)).

The two-call test reaches completion only after it manually replaces that cursor cell with the receiver’s own at lines 565–567 ([remote_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/ucf/remote_test.cljc:559)). No slice 8 code performs that handoff. Replacing the cell can also redirect a co-waiter still sharing the migrated response cell, contrary to the cell-sharing rule ([design](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:916)). The test therefore does not establish correct routing for both waits without composition intervention.

I did not edit files or rerun suites. I accept the supplied 18-test, 162-assertion results on JVM, Node, and Dart as evidence for the tested path.

Verdict: REQUEST CHANGES
Sign-off: DENIED