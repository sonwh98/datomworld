Created-GMT: 2026-11-03 00:56:40 GMT
Created-Local: 2026-11-03 07:56:40 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a19ee4-c9a4-7ab1-ae0b-872f6505296c

Completed-GMT: 2026-11-03 00:56:40 GMT
Completed-Local: 2026-11-03 07:56:40 Asia/Ho_Chi_Minh

NOT CONFIRMED (missing startup fix; send/cleanup defects; documentation contradictions; unexplained assertion delta; unavailable r1–r4 baseline).

- **P1 — Startup sequencing is broken.** [dart.cljd:300](/Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd:300) calls async `start-peripheral!` without `await`, stores its Future as the peripheral, then emits `radio-up`. Generated Dart confirms this. Peripheral sends and shutdown subsequently cast that Future to `FlutterBlePeripheral`. Additionally, the starter catches failure and returns `p`, contradicting “no bound is ever deposited.”
- **P1 — TX-only peers silently drop sends.** [dart.cljd:257](/Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd:257) does nothing when RX is absent, while the machine answers `ok`. The docstring promises a `send-fail` event.
- **P2 — Shutdown leaves subscriptions behind.** [dart.cljd:349](/Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd:349) discards adapter and scan subscriptions instead of registering them in `!subs`. [Cancellation:281](/Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd:281) also leaves asynchronous cancellation failures uncaught.
- **Documentation contradicts code.** [links.cljc:280](/Users/sto/workspace/datomworld-ble2/src/cljc/dao/stream/ble/links.cljc:280) says link-down comes first; code deposits closed first. [mesh.cljc:302](/Users/sto/workspace/datomworld-ble2/src/cljc/dao/stream/ble/mesh.cljc:302) incorrectly equates closed handling with gap handling.
- **Counts do not reconcile.** Independently reproduced **51 tests / 203 assertions**, all passing. The [new inbound test](/Users/sto/workspace/datomworld-ble2/test/dao/stream/ble/links_test.cljc:136) correctly states the exemption law but contributes **3 assertions**. Excluding it yields **50/200**, not **50/198**.

The other explicitly listed fixes are present, and all seven shared files match the rescue patch. No surviving r1–r4 records or source baseline were found, so all 16 historical dispositions and additional reconstruction drift cannot be certified. No files edited.
