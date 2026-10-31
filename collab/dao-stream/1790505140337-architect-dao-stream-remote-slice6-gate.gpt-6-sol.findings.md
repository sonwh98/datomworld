Completed-GMT: 2026-09-27 10:32:54 GMT
Completed-Local: 2026-09-27 17:32:54 Asia/Ho_Chi_Minh

P1 | [udp.cljc:295](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:295) | Reassembly rejects a message when `parts × 1200` exceeds 64 KiB, even if its actual encoded payload is within the 64 KiB limit. The 48 KiB test passes, but valid messages nearer the limit can be sent and then dropped. | Bound accumulated chunk bytes and check the completed payload’s actual length.

P1 | [jvm.clj:24](/Users/sto/workspace/datomworld/src/clj/dao/stream/udp/jvm.clj:24) | The receiver reuses one `DatagramPacket` without resetting its length. After a short datagram, a later longer datagram can be truncated, losing a message or fragment. | Reset the packet length to the buffer length before each receive.

P2 | [udp.cljc:292](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:292) | Fragment fields are used without validation. A malformed `parts`, `part`, or `bytes` value can throw during reassembly; on Node and Dart, the adapter callback does not catch that error. | Validate fragment types, ranges, and byte payloads before changing partial state; drop malformed datagrams.

The added direction field resolves the request/answer collision described by the implementer. Choosing 64 for the unspecified partial-message default and using a shared deposit medium with per-attachment forwarding are reasonable readings of §3.2. The two receive defects prevent sign-off despite the supplied passing suites.

Verdict: REQUEST CHANGES
Sign-off: DENIED