Completed-GMT: 2026-09-27 17:29:05 GMT
Completed-Local: 2026-09-28 00:29:05 Asia/Ho_Chi_Minh

**Slice 8 verdict: request changes.** The two files contain the reported real-entry lift, codec round-trip check, and frame-wide cell sharing. The supplied 16-test/106-assertion result is green, but two lowered waits cannot resume correctly through the current engine:

- **Link waits use keys the engine does not read.** [remote.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:535) lowers the link pair under identity-derived resource IDs. The engine polls the response at the fixed `module/link-response-resource` key ([engine.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1215)), and request retry uses the fixed `link-request-resource` key ([module.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc:403)). The link round-trip tests check fields and resource presence, but never poll a lowered link wait ([remote_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/ucf/remote_test.cljc:604)).
- **A retained FFI request loses its response route.** [remote.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:292) lifts only the request stream and creates no response cell; [remote.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:520) lowers that request accordingly. After the retry succeeds, `semantic-restore` creates a response wait on the receiver’s fixed local call-out keys ([semantic.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:132)). A response to the migrated request arrives on the emitter’s pair, so that wait is stranded. The test checks the rebuilt envelope but does not resume the retry ([remote_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/ucf/remote_test.cljc:577)).

I did not edit files or rerun suites. The reported full-lane slice-5 and slice-peer failures are outside this gate.

Verdict: REQUEST CHANGES
Sign-off: DENIED