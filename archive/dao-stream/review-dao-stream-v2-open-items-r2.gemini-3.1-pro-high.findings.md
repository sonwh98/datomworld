Completed-GMT: 2026-09-02 19:20:02 GMT
Completed-Local: 2026-09-03 02:20:02 Asia/Ho_Chi_Minh

### 1. Answers

1. **A2 (Stranding finding):** The revision successfully closes the stranding hole. By moving acceptance handoffs from the shared traffic medium to fixed, capacity-one offer and acknowledgement slots, it guarantees an offer cannot be evicted by subsequent traffic before being read. Boundedness is strictly maintained without unbound queues, callbacks, or blocking waiters. Deadlock is impossible because operations on evict-oldest media never block and rely on polling. Identity collision is prevented as identities are not reused. All specified failure cases (offer-deposit failure, ack-deposit failure, peer loss, endpoint stop, admission expiry) leave a clear owner to close the connection, and the client-side appropriately resolves `:ws/transport-error` on premature closure.
2. **B3 (Concurrency oracle):** Concede. The architect is correct: operations that do not overlap in wall-clock time are not concurrent. Because the IO model requires operations to reflect what is true "when called," an operation's effect must be fully realized before it returns. Sequential consistency would wrongly permit a later, non-overlapping operation to appear before it, violating the contract. By restricting real-time edges strictly to harness-observable non-overlap, the bounded linearizability oracle faithfully enforces the IO model without imposing uncontracted global synchronization across independent writers.
3. **A1 (Server-side attachment identity):** Yes, the amendment is faithful and sufficient. By amending `dao.stream.md` directly, it correctly places the authorization for non-`attach!` identities in the general contract, while properly delegating the delivery mechanism (e.g., deposited event) to the transport spec.
4. **A3 (Wire frames and close handling):** Accept. Renaming the wire frames to `:ws/accept` and `:ws/disclaim` perfectly resolves the vocabulary collision with the local `:ws/accepted` event. The adopted close code wording is exact, and the explicit `:ws/transport-error` rule for premature closures cleanly covers the edge case.

### 2. Residual Findings

None. All items are fully resolved.

### 3. Final Verdict Table

| Item | Verdict |
|---|---|
| A1 | ACCEPT |
| A2 | ACCEPT |
| A3 | ACCEPT |
| B1 | ACCEPT |
| B2 | ACCEPT |
| B3 | ACCEPT |
| B4 | ACCEPT |

### 4. Conclusion

Yes, the amendment set is ready for the orchestrator to apply.
