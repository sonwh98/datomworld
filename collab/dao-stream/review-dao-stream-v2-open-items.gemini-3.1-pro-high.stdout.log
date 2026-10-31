Completed-GMT: 2026-09-02 19:09:16 GMT
Completed-Local: 2026-09-03 02:09:16 Asia/Ho_Chi_Minh

### 1. Findings

- **Severity:** High | **Item:** A1 | **Evidence:** A1 changes the origin of `:dao.stream/attachment` to include server-minted accepted handles, doing so within `dao.stream.ws.md`. The contract (`dao.stream.md:254-261`) explicitly restricts this key to the `attach!` success map. | **Correction:** Target the amendment at `dao.stream.md` to authorize transport-minted identities upon connection acceptance, rather than disguising a contract amendment as a transport spec clarification.
- **Severity:** Critical | **Item:** A2 | **Evidence:** A2 relies on the evict-oldest medium for `:ws/accepted` notifications. If the medium is under pressure, `:ws/accepted` may be evicted before the composition reads it. Because the deposit still returns `:dao.stream/ok`, the transport will enable inbound messages and send the wire frame, but the server composition will permanently lose the writer handle and the ability to close or respond to the connection. | **Correction:** Lifecycle and handle-delivery events cannot safely be subjected to evict-oldest retention without a resource leak. The transport must either hold the socket in a pre-acceptance state until the composition explicitly acknowledges receipt of the handle, or a separate guaranteed-delivery channel is required.
- **Severity:** Medium | **Item:** A3 | **Evidence:** `{:ws/frame :ws/accepted}` (wire) and `{:ws/event :ws/accepted}` (local) reuse the same keyword for fundamentally different contexts. This introduces the exact vocabulary collision and logging ambiguity the proposal warns against. | **Correction:** Rename the wire frames to `{:ws/frame :ws/accept}` and `{:ws/frame :ws/disclaim}`.
- **Severity:** High | **Item:** B3 | **Evidence:** B3 mandates linearizability, which requires real-time precedence across independent operations. The contract (`dao.stream.md:580-586`) intentionally disclaims cross-writer real-time coordination, stating "DaoStream guarantees nothing further about concurrent operations." | **Correction:** Downgrade the contract-wide conformance oracle from linearizability to sequential consistency. Linearizability is too strong for the general contract and would falsely fail valid conforming transports.
- **Severity:** Medium | **Item:** A3 (and A1/A2) | **Evidence:** Claims that `dao.stream.ws.md:202-204`, `yin.repl.implementation-plan.md:348`, and `355-371` are invalidated or settled, but provides no replacement text. | **Correction:** Supply the missing drafted wording (see Section 4) to ensure no unresolved defects remain in the documents.

### 2. Answers to Questions

1. **A1 (Server-minted identity):** It is a contract amendment smuggled in as a spec clarification. The contract (`dao.stream.md:254-261`) strictly bounds `:dao.stream/attachment` to the `attach!` success map. To be faithful, `dao.stream.md` must be amended to state: "A transport whose attachments are distinguishable carries `:dao.stream/attachment` in its `attach!` success map, or provides it via a transport-specific mechanism when the transport creates handles for accepted connections." This change is out of scope for a subordinate document like `dao.stream.ws.md`.
2. **A2 + A3 (Consistency and deadlock):** There is no deadlock because the evict-oldest medium never returns `full` or blocks. However, there is a fatal ordering/stranding hole: if the deposit medium is full, the `:ws/accepted` event could be evicted before the composition reads it. Since evict-oldest still returns `:dao.stream/ok`, the transport proceeds to send the wire acceptance frame and enables inbound delivery. The client is now connected, but the server composition never receives the writer handle, permanently stranding the connection and leaking resources.
3. **A3 (Wire vs Local vocabulary):** It is a real hazard. Using identical keyword values (`:ws/accepted`) for fundamentally different concepts (a local envelope event containing a live handle vs. a wire protocol control frame) invites correlation bugs and breaks grep-ability. The wire frames should be renamed to `:ws/accept` and `:ws/disclaim`.
4. **A3 (Wire constraints):** There are no limitations. Node's `ws` and Dart's `dart:io` `WebSocket` fully support application-defined close codes in the 4000-4999 range, standard text frames for Transit-JSON, and custom subprotocols like `dao.stream.transit-json`. The safe integer bounds map directly to JavaScript's `Number.MAX_SAFE_INTEGER`/`MIN_SAFE_INTEGER`, so no precision is lost across JS and Dart.
5. **B3 (Concurrency oracle):** Linearizability is not faithful to the general contract; it asserts more than the contract promises. Linearizability requires real-time precedence (if append A completes in wall-clock time before append B is invoked, A must precede B). However, `dao.stream.md:580-586` explicitly disclaims cross-client real-time guarantees. The strongest faithful oracle for the contract harness is sequential consistency, which preserves program order but permits real-time reordering across independent writers.
6. **B4 (Exclusion reasons):** Yes, every reason accurately maps to one of the three contract-allowed classes. The `create!` exclusion of `not-found` is entirely consistent with the contract's `create!` outcome table. The contract specifies that `:dao.stream/not-found` is returned when "no transport here matches", meaning it is rightfully produced by the host dispatch layer, not by the transport's own `create!` handler.
7. **A3 (Draft missing wording):** See section 4 below.

### 3. Verdict Table

| Item | Verdict | Justification |
|---|---|---|
| A1 | REJECT | Subordinate spec cannot amend the contract's identity rules; the amendment must target `dao.stream.md`. |
| A2 | REJECT | Evict-oldest on `:ws/accepted` permanently strands the connection if the event is evicted before the composition reads the handle. |
| A3 | ACCEPT-WITH-CHANGES | The `{:ws/frame :ws/accepted}` wire frame collides with the local event; rename wire frames to `:ws/accept` and `:ws/disclaim`. |
| B1 | ACCEPT | Correctly localizes attachment handling to Phase 2 and directory to Phase 3. |
| B2 | ACCEPT | One codec prevents divergence and accurately tests the descriptor round-trip. |
| B3 | REJECT | Linearizability asserts real-time precedence, which the contract explicitly disclaims; the faithful oracle is sequential consistency. |
| B4 | ACCEPT | Exclusions correctly map to contract-allowed classes and accurately reflect host dispatch boundaries. |

### 4. Drafted Wording

Replacement for `dao.stream.ws.md:202-204`:
> "(The exact code is `4000`, mapping to `:ws/ended`, while `4002` is protocol-error and `4004` is the authoritative not-found disclaimer; see Elements and Serialization.)"

Replacement for `yin.repl.implementation-plan.md:348`:
> "- **The decision gate**: the descriptor key set. (The wire contract is settled in `dao.stream.ws.md`)."

Replacement for `yin.repl.implementation-plan.md:355-371`:
> "**Unblocked.** The previously blocking spec gaps (server-side attachment identity, accept notification, and wire contract) are settled in `dao.stream.ws.md`. R1 through R5 are now executable and are gated only on their named prerequisites."
