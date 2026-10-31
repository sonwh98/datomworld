Coding-Agent: codex
Session-ID: 01a0e7c5-6b35-7e01-9081-541da3b78e92
Model: gpt-6-sol (pinned -m; verified in log)

Completed-GMT: 2026-09-28 11:36:21 GMT  
Completed-Local: 2026-09-28 18:36:21 Asia/Ho_Chi_Minh

1. **Retract the P1.** The mirror advances after attempting an answer ([remote.cljc:203](/Users/sto/workspace/datomworld/src/cljc/dao/stream/remote.cljc:203)); the signed-off design expressly keeps no answer state at the mirror ([dao.stream.remote.md:75](/Users/sto/workspace/datomworld/docs/design/dao.stream.remote.md:75)). Unanswered `descriptor`, `cursor`, and `next` requests are retried because they are idempotent ([dao.stream.remote.md:299](/Users/sto/workspace/datomworld/docs/design/dao.stream.remote.md:299)). An applied `append!` whose answer is refused remains **unknown**, not retried. Without channel loss or an explicit reflection close, its event can remain outstanding indefinitely; close or loss emits `append-unknown` ([remote.cljc:447](/Users/sto/workspace/datomworld/src/cljc/dao/stream/remote.cljc:447), [remote.cljc:658](/Users/sto/workspace/datomworld/src/cljc/dao/stream/remote.cljc:658)). That is the specified loss model, rather than a violation of it. Payload-owned correlation can settle the application call independently; it does not recover the missing remote `append!` outcome.

2. **No spec change or mirror retention is warranted for Slice 3.** The FFI composition must retain its *apply response* across a `full` call-out append using caller-owned `apply/server-state` ([apply.cljc:217](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:217), [apply.cljc:301](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:301)). It must also give unresolved remote appends an explicit lifecycle, ending them through the existing close/loss path when the binding retires. A stronger remote guarantee would change the signed-off protocol and require owner approval.

3. **Drop sub-slice 3.1.** Begin with the export-binding slice, then lease wiring, then responder and real VM/UCF acceptance. Keep remote loss/resend behavior unchanged.

4. **The export binding can be implemented now.** Accept the channel adapter, lease parameters and media, stream capacities, and handler authority gate as required options; validate them before publishing descriptors. `dao.lease/make-judge` already specifies the required wiring ([lease.cljc:2114](/Users/sto/workspace/datomworld/src/cljc/dao/lease.cljc:2114)). Deployment values can remain owner policy. None must be fixed in the architecture before coding the binding.
