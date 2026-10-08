Completed-GMT: 2026-09-26 17:27:53 GMT
Completed-Local: 2026-09-27 00:27:53 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0debb-6229-79e1-890d-4d1e0b7d8565

## Rulings

### Q1. No-waiting

**RULING:** The objection is correct. Returning `blocked` while a remote `next` is outstanding misstates the source’s answer: `blocked` means nothing is at that position yet, while the proxy does not know whether the source has a value, `end`, `gap`, or an error. `transport-error` with a retry key also misstates the result: `transport-error` means the transport failed to perform the read, and the contract does not define that key as “pending.” [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:141) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:153) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:538) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:542)

**WHY:** The contract does allow an attachment to return `ok` before remote confirmation, with confirmation arriving later as deposited data. But it does not extend that exception to ordinary handle operations: they return what is true now, and are never requests whose answers arrive later. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:319) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:343) A reflection that files results and answers a later call is therefore a useful asynchronous interface, but it is not a transparent ordinary handle under the current text.

**Smallest contract change:** Add a defined pending outcome and request correlation for remote `cursor`, `next`, and `append!` operations, plus a declared result stream/channel where the source outcome arrives. Specify that pending means “the local attachment has accepted this operation; its source outcome is not yet known,” not that the source returned a `dao.stream` outcome. Update the exhaustive outcome tables accordingly. Append also needs OD-2’s treatment of unknown effect and correlation; cursors need OD-3’s serialization decision. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:851) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:896)

**RISK:** The added outcome and later-result path change the operation contract. Without that change, a compliant implementation must expose results as a separate asynchronous stream convention and must not claim its proxy is an ordinary `dao.stream` handle.

### Q2. UDP and large values

**RULING:** Fragmentation is required if “any implementation” and arbitrary `dao.stream` values apply to UDP. Narrowing UDP to one datagram contradicts that claim. The contract treats an element as a whole value and permits large media to travel as content addresses, but it does not require every caller’s values to be such addresses. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:1096)

**WHY:** Smallest transport mechanism: split an encoded message into datagram fragments `{message-id, index, count, bytes}`; reassemble by message ID before decoding or dispatch. Bound total message size, fragment count, and reassembly memory. On timeout or missing fragments, discard the incomplete message and report transport failure where the channel can do so honestly. No serve-level fragmentation frames, session protocol, or sliding window is needed. Retry and duplicate handling for writes remains governed by OD-2; fragmentation alone cannot make UDP delivery reliable or exactly-once.

**RISK:** Fragmentation increases state and exposure to loss and resource exhaustion. If the owner instead accepts a narrower claim, they must be told explicitly that UDP cannot carry values/messages above its declared datagram budget; those require a different transport or application-level content addressing.

### Q3. One design

**RULING:** Merge the mirror/reflection’s identity-addressed stateless dispatch with explicit request IDs, while leaving remote results asynchronous until the contract is amended.

| Choice | Adopt or reject |
|---|---|
| Per-stream channel vs identity in request | **Identity in request.** The transport channel can carry calls for multiple streams; a host-owned table maps identity to handle and declared surface. This avoids a wire `attach/open` exchange per stream. |
| Attach/opened/disclaim/ping frames vs none | **None at serve level.** `attach!` creates the local remote-operation interface; absence of a stream returns a correlated `not-found` result. Channel establishment/liveness belongs to the channel. |
| Peer ID vs none | **No protocol peer ID.** Reachability names and rendezvous information belong to descriptors or conventions. |
| Request ID and response ID | **Adopt both.** The answer carries the same ID as the call so independent operations can be correlated. Correlation is not deduplication. |
| `:more` batching and anchor piggyback | **Reject.** More keys and extra operations add machinery. Use ordinary cursor calls and one source outcome per call. |
| `:resend-after` vs nothing | **Reject protocol retry timer.** The caller or channel owns retry policy. Never automatically retry appends without the OD-2 dedup/unknown-effect rules. |
| `dao.stream.apply` | **Convention-over.** Its request/response service behavior remains available above exposed streams; it is not the serve protocol. |
| `dao.stream.rpc` | **Convention-over.** Correlated service behavior likewise remains a convention over streams; it is not a peer role or serve frame. |

**WHY:** The source’s full outcome map, including cursors and `gap` recovery cursor, should be carried as data without the forwarding layer interpreting it. But the reflection’s “return blocked, file source result” behavior cannot be called a conforming handle under current no-waiting rules.

**RISK:** Source cursors are not yet guaranteed to survive serialization across hosts. OD-3 explicitly leaves that decision unsettled; verbatim forwarding alone does not solve cursor portability. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:517) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:896)

### Q4. Blockers and readiness

**RULING: NOT READY TO SPECIFY as a transparent `dao.stream` handle.**

**WHY:** A stateless operation mirror can dispatch calls and return source outcomes, but a remote operation cannot synchronously produce that outcome without waiting. The current contract has no pending outcome or standard operation-result stream. Cursor portability is also explicitly TBD. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:153) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:521) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:939)

The design does fit datom.world’s stream boundaries if implemented as channel events plus interpreters: IO appears as stream emissions, host adapters deposit plain data, and the depositor does not interpret event meaning. Do not implement wire dispatch inside an adapter callback or let host types cross the boundary. [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:64) [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:93) [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:106)

**RISK:** Writing the new serve spec as if a proxy already conformed would bake in a contract violation. It is ready to specify only as a separate asynchronous remote-operation convention, or after the contract settles pending results, append correlation/unknown effects, and portable cursors.

## CONVERGED DESIGN

A peer exposes a local handle by adding `{identity → {handle, declared-surface}}` to composition data. A remote descriptor names the logical stream identity and channel reachability. Any peer may expose and attach; these are interaction roles, not protocol-level peer kinds. The handle stays local to its owner.

The channel carries portable maps in the negotiated codec. It has no serve-level open, close, ping, or liveness protocol.

**Complete serve message list:**

```clojure
;; Request
{:dao.stream/serve :dao.stream/call
 :dao.stream/id request-id
 :dao.stream/identity logical-stream-id
 :dao.stream/op operation
 :dao.stream/args arguments}

;; Reply
{:dao.stream/serve :dao.stream/result
 :dao.stream/id request-id
 :dao.stream/outcome complete-source-outcome-map}
```

The receiving interpreter looks up identity, verifies the declared surface, invokes that handle operation, and emits its complete outcome map with the request ID. Missing identities return the contract’s `not-found` result where that operation defines it; unsupported surfaces must be represented according to the contract, not by inventing outcomes. A reply’s `gap` is source retention loss; channel loss is a transport failure.

The request ID correlates messages, not peer identity and not exactly-once execution. Do not retry appends automatically unless OD-2 settles deduplication and unknown effects. Do not implement a result cache that returns `blocked` or `transport-error` to stand for an unanswered source read: the present contract defines neither as pending.

For UDP, fragment encoded channel messages below serve dispatch using `{message-id, index, count, bytes}` metadata, bounded reassembly, and incomplete-message expiry. WebSocket retains its native message boundary. NAT rendezvous, UDP hole punching, and relaying are channel/convention concerns; direct traversal cannot be guaranteed through symmetric NAT, CGNAT, blocked UDP, or for browser listeners. Where no direct path exists, peers need a reachable third peer to relay.

The source outcome and cursor pass through unchanged. Before specifying this as an ordinary remote handle, settle the contract’s asynchronous-result semantics and cursor serialization.
