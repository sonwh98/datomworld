Completed-GMT: 2026-09-26 17:27:26 GMT
Completed-Local: 2026-09-27 00:27:26 +07
Coding-Agent: claude
Session-ID: 506ecf77-3b05-43cf-9579-ad73759f7aa6

# Consensus round: rulings

## Q1. No-waiting

**RULING.** The objection is wrong that a separate asynchronous interface or a new outcome is required, and right that the current text needs one clarifying sentence beyond OD-1..3. A reflection that issues a request and returns `blocked` (or, for `cursor`, `transport-error` with `:dao.stream/retry? true`) satisfies the IO model; the issue is wording, not model.

**WHY.** The model is stated at `dao.stream.md:141-144`: "returns immediately with whatever is true now, including the answer 'nothing yet, ask again'", and `:146-147` names `blocked` as `EAGAIN`. The contract already lets an operation start transport work whose answer lands later: `attach!` returns `ok` while "establishment" is pending and the far end's answer "arrives later as data" (`:323`, `:344-346`), `create!` may return `ok` while acquisition proceeds (`:328-334`), and `cursor` "may have to consult the medium" (`:488-491`). The sentence the objector leans on, "never a request for something to happen and be reported back" (`:157-158`), forbids the *return value* being a promise, not the operation touching IO. The genuine gap is `:545`: `blocked` is glossed "Nothing at this position yet", which reads stream-relative, while `closed` and `end` are explicitly handle-relative (`:603-604`). A reflection's `blocked` is handle-relative: nothing observable at this position *through this handle* yet.

**CONTRACT TEXT TO CHANGE.** In *Reading*, `:545`, reword the `blocked` row to: "Nothing observable at this position through this handle yet; retry later. Handle-relative: a handle of deferred observation answers `blocked` for a position whose value it has not yet received." Add one sentence at the end of *Where the asynchrony goes* (`:455-471`): "An operation may initiate transport work whose answer arrives later, as `attach!` already does; it never waits for it." Plus OD-1 (`:832-845`) for `:dao.stream/retry?` on the reflection's pre-anchor `cursor`, and OD-3 decision 2 (`:939-943`) for cursors surviving the codec. OD-2 is needed for writes, not for this question.

**RISK.** Low. Three sentences, no outcome added, no consumer changes. Without the `:545` reword the design is implementable but arguable, which is what the objection exploited.

## Q2. UDP and large values

**RULING.** Fragmentation is required in v1, inside the UDP channel, invisible to the serve layer. Narrowing to one datagram would make "any implementation" false for any stream holding a value over about 1200 bytes, including the owner's own toy if the string is long, and the contract's granularity rule (`dao.stream.md:1096-1098`) is guidance for payload authors, not a bound the transport may impose.

**SMALLEST MECHANISM.** One frame, no sessions, no acks, no windows:

```clojure
{:dao.stream/id n :dao.stream/part i :dao.stream/parts k :dao.stream/bytes <chunk>}
```

Sender: encode the message with the CBOR profile (`src/cljc/dao/stream/cbor.cljc:111-119`); if it fits the 1200-byte budget (`src/cljc/dao/jing/dht/node.cljc:44-50`) send as is, else split the bytes into `k` parts each tagged with the message's own `:dao.stream/id`. Receiver: key reassembly on `[source-address direction id]`, deliver when all `k` are present, and never deliver a partial message. Loss of any part is loss of the message; the reflection's existing `:resend-after` re-sends the whole request, and the mirror recomputes the answer since reads are idempotent and appends are never re-sent (OD-2). The reassembly table is bounded by a composition datum, `:max-message-bytes` and `:max-partial-messages`, evicting the oldest partial; no clock. Replies go to the datagram's source address, never to an address in the payload (`node.cljc:144-148`).

**OWNER MUST BE TOLD.** UDP messages are bounded by a composed maximum (default 64 KiB); loss probability grows with part count, so large media still travel best as `dao.jing` addresses; there is no per-part acknowledgement, so a lost part costs a full re-send.

**RISK.** Low. Roughly forty lines in the UDP adapter, none in the serve layer.

## Q3. One design

**RULING.** Baseline: mirror and reflection (the Fable design). It has the fewest concepts (four), the only stateless serving side, many streams per channel, and two wire shapes, one of which is the contract's own outcome map. Element by element:

| Element | Decision | Why |
|---|---|---|
| Per-stream channel (deepseek) vs identity in the request (Fable, GPT) | **Identity in the request** | One socket per stream multiplies NAT mappings and relays per stream; one key in a map costs nothing. |
| `attach`/`opened`/`disclaim`/`ping`/`pong` frames (deepseek) | **Reject all five** | `attach!` sends a `descriptor` request; its `ok` is `opened`, its `not-found` is `disclaim`; a `descriptor` request with a fresh id is the liveness probe and the hole-punch probe, since a matching answer from the expected address confirms the path even when it says `not-found`. |
| `close!` and `attach!` on the wire (GPT) | **Reject** | The mirror holds no per-peer state, so there is nothing remote to open or close; a reflection's `close!` is local. |
| Peer id in the protocol (GPT, deepseek) | **None** | A peer is a channel end. Names are convention data on a meeting board, self-minted random. |
| Request id and response id | **One id, minted by the asker per channel reader, echoed on the answer** | Correlation, staleness discard, and the fragmentation key. |
| `:more` batching and anchor piggyback (Fable) | **Adopt both, optional on the mirror, ignorable by the reflection** | Without batching throughput is one value per round trip; without piggyback the first read costs two round trips and pre-anchor `cursor` returns `transport-error` longer. |
| `:resend-after k` (Fable) vs GPT's retained-results retry vs nothing (deepseek) | **`:resend-after`** | Clock-free, caller-paced, needs no server-side result retention because reads are idempotent and appends are never re-sent. |
| GPT's server-side result retention | **Reject** | It exists only to make re-sent appends idempotent, which OD-2 assigns to the payload. |
| Reachability candidates in the descriptor (deepseek) | **Reject as protocol; keep as composition policy** | The remote descriptor carries one `:dao.stream/channel`; a relay is a `:dao.stream/pair` channel descriptor of two remote descriptors; trying several descriptors in order is the attaching composition's policy. |
| Deepseek's poll-through proxy with no filed answers | **Reject** | A `next` that "returns the source's `blocked`" must wait for it; the filed-answer reflection is the non-waiting form of the same idea. |
| `dao.stream.apply` | **Retired** | Its `ok`/`error` envelope (`src/cljc/dao/stream/apply.cljc:77-97`) duplicates the outcome map; nothing of its dispatch remains once the mirror exists. |
| `dao.stream.rpc` | **Convention-over**; `rpc.ws` retired | Correlation by id in the payload over two exposed streams; its WebSocket binding is replaced by reflections. |
| UDP fragmentation (GPT) | **Adopt** per Q2. | |
| Same-socket rule and reply-to-source rule (deepseek, DHT) | **Adopt** as UDP channel facts. | |

## Q4. Blockers

Checked against the six invariants (`datom.world.md:21-28`): no hidden global state, since the table and link are composition-held closures per *Host Dispatch* (`dao.stream.md:370-375`); no callbacks, since the reflection drains by pulling on each operation and the host adapter only deposits; no shared mutable state beyond a handle's own state, as every transport has; no implicit control flow; interpretation and execution stay separate, since the mirror judges no value; no graph assumption. Against the owner invariant: met, with the two network impossibilities stated (double-unreachable peers need a third reachable peer; browsers cannot listen). Against the contract: requires the Q1 sentences, OD-1, OD-2, OD-3 decision 2.

**Verdict: READY TO SPECIFY**, on condition that the spec's first section lists those contract amendments and the spec change lands them in `dao.stream.md` in the same commit. No design blocker remains. Two implementation facts to record, not blockers: no `dao.stream.udp` adapter exists yet, and its writer must be able to send to an explicit destination from an already-bound socket for hole punching.

# CONVERGED DESIGN

**Concepts.** *Channel*: a two-ended pair of ordinary handles, a writer toward one peer and a reader positioned on what that peer sent. WebSocket attachments already are this (`src/cljc/dao/stream/ws.cljc:189-229`, `:148-174`); UDP is a new adapter of the same shape; two in-process ring buffers are one; two reflections served by a third peer are one. *Table*: `{identity {:handle h :surface S}}`, composition data on the holding peer. *Mirror step*: stateless, run by any peer. *Reflection*: the handle `attach!` returns for a remote descriptor; a *link* per channel holds the channel reader's cursor, outstanding ids, filed answers, an optional event writer, and `:resend-after k`.

**Descriptors.**

```clojure
{:dao.stream/type :dao.stream/remote :dao.stream/identity id :dao.stream/channel <channel-desc>}
;; channel-desc is a ws descriptor, a udp descriptor {:dao.stream/type :dao.stream/udp :udp/host h :udp/port p},
;; or a relay pair {:dao.stream/type :dao.stream/pair :dao.stream/in <remote-desc> :dao.stream/out <remote-desc>}
```

**Complete message list.** Two logical shapes on every channel, plus one datagram-level shape on UDP only.

```clojure
;; 1. request
{:dao.stream/identity id
 :dao.stream/op       :dao.stream/descriptor | :dao.stream/cursor | :dao.stream/next | :dao.stream/append!
 :dao.stream/args     []  | [anchor] | [cursor] | [value]
 :dao.stream/id       n                      ; asker-minted, unique per channel reader
 :dao.stream/budget   k}                     ; optional, next only

;; 2. answer: the contract outcome map for that op, verbatim, plus
{:dao.stream/id n :dao.stream/identity id
 :dao.stream/oldest <cursor> :dao.stream/newest <cursor>   ; piggyback, when the identity is served
 :dao.stream/more [<outcome-map> ...]}                     ; optional, next only, in order

;; 3. UDP fragment (channel-internal, never seen by mirror or reflection)
{:dao.stream/id n :dao.stream/part i :dao.stream/parts k :dao.stream/bytes b}
```

**Mirror step** `(mirror-step table chan-reader cursor chan-writer) -> cursor'`. Per request: identity absent from the table, or op absent from its surface, answer `{:dao.stream/outcome :dao.stream/not-found}`; else apply the op to the handle and append the outcome map with id, identity, and the two anchors. For `next` with a budget it may follow successors up to `k` times, placing later maps under `:more`; the terminating non-`ok` is included. A value that cannot be carried is answered `transport-error` with `:dao.stream/reason :oversize` in place of that element, cursor not advanced. Holds no per-peer state.

**Reflection.** `attach!` returns `ok` at once with a local `:dao.stream/attachment`, and the link sends a `descriptor` request; its answer is the deferred confirmation, appended to the event writer, and after `not-found` every op answers `transport-error` with `:dao.stream/reason :dao.stream/not-found`. Every op first drains the channel reader to `blocked`, filing answers by id and installing `:more` outcomes at their cursors. Then: `descriptor` answers locally with the remote descriptor and identity. `cursor :oldest|:newest` answers the latest piggybacked cursor; before any answer, `transport-error` with `:dao.stream/retry? true`; other anchors issue a `cursor` request and relay its map when filed. `next c` returns a filed answer for `c` and drops it, else sends a request if none is outstanding and returns `blocked`. `append! v` sends the request and returns the channel writer's outcome; the source's outcome is appended to the event writer when filed. `close!` is local. An outstanding read is re-sent after `k` further asks; appends are never re-sent; answers for ids not outstanding are dropped.

**Channels.** WebSocket: as today, either end may dial or accept; direction is forgotten once established. UDP: one CBOR message per datagram, 1200-byte budget, fragmentation per shape 3, reassembly keyed on source address, direction and id, bounded by `:max-message-bytes` and `:max-partial-messages`; replies to the datagram's source address; sends may name an explicit destination from an already-bound socket. Pair: writer is a reflection of the far peer's inbox, reader a reflection of this peer's inbox, both served by a third peer running only mirror steps over two ring buffers; an inbox `gap` is channel loss, absorbed by `:resend-after`, never reported as the source's `gap`.

**Conventions, not protocol.** Meeting board for reflexive addresses and hole punching (the punch probe is a `descriptor` request with a fresh id); relay pairs; request/response services as a writer-surface stream plus a reader-surface stream with payload ids; peer names as self-minted random values.

**Contract preconditions.** The `blocked` reword and the "may initiate transport work" sentence (Q1); OD-1; OD-2; OD-3 decision 2.

**Owner-visible.** Two peers that are both unreachable need a third reachable peer; browsers cannot listen. Everything is plaintext and ungated; a relay sees all traffic. UDP messages are bounded by the composed maximum. Reads are polled, one round trip per batch.
