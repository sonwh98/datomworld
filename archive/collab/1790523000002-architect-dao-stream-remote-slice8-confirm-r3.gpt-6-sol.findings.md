Completed-GMT: 2026-09-27 19:09:01 GMT
Completed-Local: 2026-09-28 02:09:01 Asia/Ho_Chi_Minh

The two original P1s are addressed: lowered link waits use the engine’s fixed keys, and the retained FFI request now carries a response route. The supplied focused results are green; I did not rerun suites or edit files.

**P1 — retained-call routing overwrites the receiver’s call pair.** `lower-frame` installs the emitter’s reflected call streams and route cell at the receiver’s fixed keys ([remote.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:531), [remote.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:562), [remote.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:737)). Those are also the keys used by calls the resumed program makes later. The [UCF routing rule](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:614) requires the outstanding call to use the emitter’s pair while future calls use the receiver’s own pair. The new resume test ([remote_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/ucf/remote_test.cljc:499)) ends after the migrated call, so it does not exercise that collision.

Verdict: REQUEST CHANGES
Sign-off: DENIED