Completed-GMT: 2026-09-27 03:20:25 GMT
Completed-Local: 2026-09-27 10:20:25 Asia/Ho_Chi_Minh

P1 | [remote.cljc:751](/Users/sto/workspace/datomworld/src/cljc/dao/stream/remote.cljc:751) | A `full` refusal leaves the attach descriptor probe unregistered, as intended by [send-request!](/Users/sto/workspace/datomworld/src/cljc/dao/stream/remote.cljc:279). But [drain!](/Users/sto/workspace/datomworld/src/cljc/dao/stream/remote.cljc:446) retries only *outstanding* probes. Once the writer recovers, no later operation sends that probe: remote confirmation, the learned surface, and its confirmation event can remain absent indefinitely. The new test at [remote_test.cljc:583](/Users/sto/workspace/datomworld/test/dao/stream/remote_test.cljc:583) checks cursor recovery but not probe recovery. | Retain a pending unsent probe and retry it on a later drain; add a test that refuses the probe, then confirms its answer after writer recovery.

The cursor and next refusal fix is sound on inspection. Installed outcomes use the answer’s served identity and cursor, matching the consuming reflection’s identity and cursor for normal mirror answers. I accepted the supplied JVM, Node, and Dart results without rerunning suites.

Verdict: REQUEST CHANGES
Sign-off: DENIED