Completed-GMT: 2026-09-27 06:11:19 GMT
Completed-Local: 2026-09-27 13:11:19 Indochina Time

P1 | `src/cljc/dao/stream/ws_project.cljc:112-113` | When the traffic medium ends, `step!` stops the projection but leaves its ring open. No later terminal event can be read, so a link waiting on the ring can remain blocked indefinitely. | Close the ring on medium end and test the link’s channel-loss outcome.

P1 | `src/cljc/dao/stream/ws_project.cljc:249-255` | Closed sessions remain in `:sessions` and are stepped on every tick. Repeated connections therefore retain handles, rings, and projections without a bound. | Remove a session after its projection closes and its retained requests have been handled; test slot reuse across repeated connections.

P2 | `src/cljc/dao/stream/ws_project.cljc:334-350` | A second successful `dial-attach!` silently replaces the active channel while reusing its ring and original traffic cursor. This contradicts the documented one-active-attachment reading and can mix two attachments’ state. | Reject a second attach on an active dial, or explicitly close and replace the channel with fresh media and cursors.

The other ambiguity readings are consistent with the cited contract. The `ws.cljc` diff is comments only, and deferring handshake retirement until the copy-path consumers are removed is sound; its pointers identify the dependency. The reported suite results support this static gate, while the cross-host socket proofs remain pending before commit.

Verdict: REQUEST CHANGES
Sign-off: DENIED