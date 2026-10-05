# DaoJing DHT: Content-Addressed Segment Distribution

Status: **contract frozen 2026-09-30 (DHT epic slice S0); S1 through S5 are
implemented.** Section 10 records the starting tree, the slice plan, and the
plain Clojure path S5 added. Subordinate to
[`dao.stream.md`](./dao.stream.md), [`dao.jing.md`](./dao.jing.md) and
[`datom.world.md`](./datom.world.md). In sections 2 to 9 every sentence is a
rule.

## 1. Overview and scope

`dao.jing` is deliberately dumb: it observes an explicit intake pool of
`dao.stream` values and materializes opaque payloads into content-addressed
storage (see `dao.jing.md`). This document specifies the distributed
content-store backend for that boundary: a Kademlia-style Distributed Hash
Table that routes only strict content addresses.

The DHT answers one question: given a content address, where on the network
are the bytes? It does nothing else. There are no roots, no CAS records, no
deletes, no source identity, no stream replication, and no consensus. Content
addressing stays in `dao.jing` (`segment-key`); the network layer only
distributes the payloads those addresses name.

**Strict content addresses only.** The DHT routes every registered
`:segment/<algorithm>-<digest>` address of the closed multihash registry
(`dao.jing.hash-registry.md`; `:blake3` default, `:sha256` selectable), and
nothing else. The routing target is the address's digest alone; a non-segment
address is rejected before any local or network action. Because the payload
hashes to the address, a fetching node verifies what a peer hands it against
the address it asked for.

Nothing at this layer knows or records which stream, agent, or peer carried a
payload. Distribution is by content, never by provenance.

**Root discovery is outside this epic (owner decision 3).** A reader holding
only a peer address cannot learn which manifest is current. A restart reads
the local HEAD of the durable-store epic; a remote query is given a manifest
address. Signed root facts and rendezvous are a separate later epic. The
first is now designed above this layer, in
[`yin.vm.linker.dht.head.md`](./yin.vm.linker.dht.head.md): a publisher's
HEAD is a signed trace on a stream it writes, and the DHT learns nothing
about roots.

## 2. Layering and composition

The governing direction, in the owner's words: "the dht should sit on the raw
datagram layer but expose a dao.stream interface"; "Both: A raw datagram
layer exposed as dao.stream, AND a DHT whose own API is dao.stream-shaped,
built on it."

The DHT is one interpreter, `dao.jing.dht`, between two stream-shaped
boundaries. Below it is one raw datagram socket
([`dao.stream.datagram.md`](./dao.stream.datagram.md)). Above it is the
content request medium `dao.jing.content` already defines. It is a pure
stepped state machine: no socket, thread, atom, promise, callback, scheduler
or clock, on any host.

The composition supplies, as data:

<table>
<tr>
<th>
Datum
</th>
<th>
Meaning
</th>
</tr>
<tr>
<td>
<code>:local</code>
</td>
<td>
A <code>dao.jing</code> byte-store handle: this node's own copy of what it
holds.
</td>
</tr>
<tr>
<td>
<code>:requests</code> + cursor
</td>
<td>
Reader on the request stream (section 3). An evicting ring.
</td>
</tr>
<tr>
<td>
<code>:answers</code>
</td>
<td>
Writer for the exact <code>:jing/*</code> answers (section 3). An evicting ring.
</td>
</tr>
<tr>
<td>
<code>:facts</code>
</td>
<td>
Writer for DHT-qualified facts (section 3). An evicting ring.
</td>
</tr>
<tr>
<td>
<code>:ticks</code> + cursor
</td>
<td>
Reader on a tick stream (section 5).
</td>
</tr>
<tr>
<td>
<code>:traffic</code> + cursor, <code>:datagrams</code>
</td>
<td>
Reader on the socket's raw traffic stream and its writer handle. <strong>Both
absent in solo mode.</strong>
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/id</code>
</td>
<td>
This node's id: 64 lowercase hex characters, 32 bytes of the key space (section
6).
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/secret</code>
</td>
<td>
Root secret bytes for cookies (section 8). Absent in solo mode; required with a
socket from S4.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/bootstrap</code>
</td>
<td>
Contacts <code>[{:host ip-literal :port p} ...]</code>: unproven candidates,
nothing more.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/publish?</code>
</td>
<td>
Publication declaration, default <code>false</code> (section 4.4).
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/ack-peers</code>
</td>
<td>
Peers a write must be sent to before it is acknowledged. Default 2, floor 2,
ceiling <code>k</code> (section 4).
</td>
</tr>
<tr>
<td>
limits and tick budgets
</td>
<td>
Section 9.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/bind-host</code>
</td>
<td>
Local socket bind IP literal for rejecting opposite-family peer destinations
before appending a datagram. Defaults to <code>127.0.0.1</code> in loopback S3.
</td>
</tr>
</table>

`(dao.jing.dht/state composition) -> state` validates the composition and
throws on a composition defect (an `ack-peers` below 2 or above `k`, a missing
handle, and from S4 a socket without a secret). It performs no stream
operation.
`(dao.jing.dht/step state budget) -> state'` is the only thing that advances
the DHT. One owner per state drives it, at the composition's cadence. The
state is a plain map: handles, cursors, the routing table, and the pending
tables.

**Solo mode** is a composition with no `:traffic` and no `:datagrams`. It
opens no socket, holds no pending network work, and answers as section 4.3
and 4.5 say. Configuring peers later is a new composition.

One step, in this order, each stage bounded by `budget`:

1. Drain `:ticks` to `blocked`; the newest reading is `now`.
2. Read `:traffic`: validate each datagram (section 7, 8), deliver replies to
   pending queries, serve requests.
3. Read `:requests`.
4. Advance pending work: expire deadlines against `now`, issue queries,
   retries and sends.
5. Append answers and facts.

A `gap` on `:requests`, `:traffic` or `:ticks` adopts the recovery cursor and
appends `{:dao.jing.dht/fact :dao.jing.dht/gap :dao.jing.dht/stream s}`. A
request lost to a gap is never answered; its asker re-asks. Every operation
here is idempotent by content address, so re-asking is always safe.

## 3. The stream API

**Requests and answers are the `dao.jing.content` convention**
(`src/cljc/dao/jing/content.cljc`), unchanged and exact. The DHT is one more
serving half of that convention, a sibling of `serve-step` whose answers may
arrive on a later step. The clients are the existing ones:
`dao.jing.content.step` (portable, stepped), `dao.jing.content.driver` (JVM
blocking, host policy), `dao.jing.content.async` (callback facade). No DHT
client vocabulary exists.

```clojure
;; requests
{:jing/request r :jing/get address}
{:jing/request r :jing/put address :jing/bytes b64}
{:dao.jing.dht/replicate address}
; section 4.2; no id, the address is the identity

;; answers: exact key sets, no additional keys
{:jing/request r :jing/found? b :jing/bytes b64-or-nil}
{:jing/request r :jing/result :inserted | :present}
{:jing/request r :jing/unacknowledged reason} ; the one addition, section 4.3
```

- `r` is self-minted and random, as `dao.jing.content` requires. The DHT keeps
  no record of completed ids and refuses no duplicate: correlation and
  deduplication are the payload's (`dao.stream.md`, Writing).
- Answers carry exactly the keys shown, because the stepped client matches
  exact key sets. Everything DHT-specific goes on `:facts`.
- A malformed request is dropped without answer, as `serve-step` does.
- `:jing/unacknowledged` is one new exact answer shape in the `:jing/*`
  convention. `dao.jing.content.step` completes it as
  `{:id id :error {:code :dao.jing.content/unacknowledged :reason reason}}`.
  A purely local content service never produces it.

**Facts** are plain data under `:dao.jing.dht/`, one map per fact, keyed by
address, for whoever reads them:

<table>
<tr>
<th>
<code>:dao.jing.dht/fact</code>
</th>
<th>
Also carries
</th>
<th>
Meaning
</th>
</tr>
<tr>
<td>
<code>:dao.jing.dht/sent</code>
</td>
<td>
<code>/address</code>, <code>/peers n</code>
</td>
<td>
<strong>The acknowledgement.</strong> Every datagram of the write was handed to
the socket toward <code>n</code> distinct peers, <code>n &gt;= ack-peers</code>.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/unacknowledged</code>
</td>
<td>
<code>/address</code>, <code>/reason</code>, <code>/peers m</code>,
<code>/local</code>
</td>
<td>
The write is not acknowledged. <code>m &lt; ack-peers</code> is how many peers
it was sent to; <code>/local</code> is the local verdict or <code>nil</code>.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/replicated</code>
</td>
<td>
<code>/address</code>, <code>/peers n</code>, <code>/confirmed c</code>
</td>
<td>
Replication after the acknowledgement has finished. <code>c</code> peers replied
that they stored it.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/miss</code>
</td>
<td>
<code>/address</code>, <code>/reason</code>
</td>
<td>
A get ended not found: <code>/exhausted</code>, <code>/deadline</code>,
<code>/solo</code>, <code>/busy</code>.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/gap</code>
</td>
<td>
<code>/stream</code>
</td>
<td>
Section 2.
</td>
</tr>
</table>

`:dao.stream/*` operation outcomes are untouched: these are values on
streams, not outcomes of stream operations.

## 4. Writes: local insert, then acknowledged when sent to multiple peers

The owner's contract (2026-09-30), verbatim: "i want to take the kafka route.
write is acknowledged immediately as long as it is sent out on a network. this
works because the data is written to a peer"; "sent to a peer but it must send
to multiple peers before it can be considered ack"; local copy: "Yes, local +
network". This supersedes the earlier local-verdict acknowledgement.

### 4.1 What the acknowledgement asserts

**A write is acknowledged when the local insert has succeeded and every
datagram of its store message has been handed to the socket toward at least
`ack-peers` distinct peers.**

- "Handed to the socket" is the raw writer's `append!` answering
  `:dao.stream/ok` (`dao.stream.datagram.md` section 4), for every datagram of
  the message, chunks included. A peer for which any datagram answered
  otherwise is not counted, and the next candidate is tried.
- A peer counts only if it is a **proven** routing entry (section 6) that is
  **fresh**: a matched reply from it arrived within the last
  `cookie-epoch-ticks` by this node's own ticks. That reply is also what
  carried the cookie the store uses (section 8). A peer that is not fresh is
  pinged first.
- The acknowledgement says **sent to N peers**. It never says stored by N
  peers, received by N peers, or durable anywhere but locally. The word
  "stored" appears only in `:dao.jing.dht/confirmed`, and that number is
  peers' own replies, which are claims.
- `ack-peers` is composition data: default 2, floor 2, ceiling `k`. One peer
  is never "multiple".
- A local collision or an invalid address/bytes pair fails before anything
  else, as today.

### 4.2 Two entry points, one pending write

- **The byte-store handle** (section 5.1). `put-bytes-fn` validates, inserts
  into `:local`, appends `{:dao.jing.dht/replicate address}` to the request
  stream, and returns the local verdict `:inserted` or `:present` at once.
  The return value is the byte-store contract's local verdict. It is **not**
  the acknowledgement; the acknowledgement is the `/sent` fact.
- **A `:jing/put` request.** The step validates (the one ingress check,
  `dao.jing/accept-bytes!`), inserts into `:local`, and holds the answer.
  `{:jing/request r :jing/result verdict}` is appended when the write is
  acknowledged, and in a DHT composition that answer **is** the
  acknowledgement.

Both create or join one pending write per address. A pending write records
the address, the request ids waiting on it, the peers sent to, and its
deadline. It never holds the payload; bytes are read from `:local` when sent.

### 4.3 How a write proceeds, and what the caller sees when it cannot

A pending write never waits on a lookup to acknowledge:

1. **Candidates** are the proven routing entries nearest the address's digest
   that this node already knows: a table read, no IO.
2. **Send** the store message to fresh candidates, nearest first, until
   `ack-peers` are counted. With a warm table this happens in the
   step that read the request: the acknowledgement is immediate.
3. **Too few known peers** (a cold bootstrap, a small table): the write starts
   an iterative lookup toward the digest and pings candidates; each newly
   proven peer is sent to as it appears.
4. **Acknowledge** the moment the count reaches `ack-peers`: append the
   `/sent` fact and answer every waiting `:jing/put`.
5. **Continue after the acknowledgement**, best effort: finish the lookup and
   send to the `k` nearest peers not already sent to, so that a reader's
   lookup converges on holders. When done or at the deadline, append
   `/replicated`. Nothing about the acknowledgement depends on this.

If the count has not reached `ack-peers` when `ack-ticks` have elapsed since
the write began, the write is **not acknowledged**: append the
`/unacknowledged` fact and answer every waiting `:jing/put` with
`{:jing/request r :jing/unacknowledged reason}`. The pending write is then
forgotten. The local copy stays. Trying again is the caller's policy: append
the replicate request again.

<table>
<tr>
<th>
Reason
</th>
<th>
When
</th>
<th>
Sent anything?
</th>
</tr>
<tr>
<td>
<code>:dao.jing.dht/solo</code>
</td>
<td>
No socket is composed. Answered in the step that reads the request.
</td>
<td>
No
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/unpublished</code>
</td>
<td>
<code>publish?</code> is false (section 4.4). Answered at once.
</td>
<td>
No
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/oversize</code>
</td>
<td>
The encoded store message exceeds <code>max-message-bytes</code>. Answered at
once.
</td>
<td>
No
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/absent</code>
</td>
<td>
A replicate request names an address <code>:local</code> does not hold.
</td>
<td>
No
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/busy</code>
</td>
<td>
<code>max-pending-writes</code> is reached. Answered at once.
</td>
<td>
No
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/too-few-peers</code>
</td>
<td>
The deadline passed with fewer than <code>ack-peers</code> counted.
<code>/peers m</code> says how many.
</td>
<td>
Possibly, to <code>m</code>
</td>
</tr>
</table>

So a caller always sees exactly one of `/sent` or `/unacknowledged` per
pending write, unless the request itself was lost to a gap, which the `/gap`
fact reports.

**Oversize fails explicitly and before the local insert.** A payload whose
store message would exceed `max-message-bytes` is rejected by `put-bytes-fn`
(it throws, as for any invalid put) and by the `:jing/put` path (the
`/oversize` answer, no local insert), in every mode including solo. A segment
is never silently local-only.

**No REPL round stalls.** `put-bytes-fn` returns after the local insert and
one ring append. A round that publishes and reads its manifest back
(`yin.repl.index`) completes against `:local`. The acknowledgement arrives on
`:facts` when the runner has sent; the REPL reports it when it observes it.

### 4.4 Publication (owner decision 1)

Default: never publish. `:dao.jing.dht/publish?` is a separate declaration,
distinct from configuring peers; bootstrap contacts alone declare nothing.

- **`publish?` false:** this node originates nothing and serves nothing it
  holds. Every write is `/unpublished`. An inbound `:fetch` is answered not
  found; an inbound `:store` is answered not ok. The node still answers
  `:ping` and `:find`, and still fetches and caches what it asks for.
- **`publish?` true:** everything in `:local` is public. The node replicates
  its writes, serves `:fetch` from `:local`, and accepts `:store` within its
  storage bound. The composition that sets it states what will be shared: the
  whole content of that store.

This is a declassification decision, not authorization. ShiBi is where
authorization arrives.

### 4.5 Reads

A `:jing/get` request reads `:local` first and answers a hit in the same
step. On a miss:

- Solo: answer not found, with a `/miss` fact of reason `/solo`.
- Otherwise start a pending get: an iterative lookup toward the digest, asking
  candidates with `:fetch` as they are proven, at most `alpha` outstanding.
- A fetched payload is verified before anything else: strict decode, digest
  against the requested address, canonical decode (`dao.jing/accept-bytes!`).
  A failure is discarded and the next candidate tried.
- A verified payload is inserted into `:local`, then answered found.
- Candidates exhausted, or `get-ticks` elapsed: answer not found, with a
  `/miss` fact.

**Not found from a DHT is not authoritative absence.** It says no reachable
candidate produced a verifiable value in time.

## 5. The `dao.jing` adapter, the JVM facade, and time

### 5.1 The byte-store handle (owner decision 4)

`(dao.jing.dht/store-handle {:local l :requests w :max-message-bytes n})` is
a plain-data `dao.jing` handle, portable to every host:

- `put-bytes-fn`: section 4.2. Local verdict, immediately.
- `get-bytes-fn`: **`:local` only.** Bytes or the caller's sentinel. It never
  waits, never returns a marker, and never touches the network.
- `close-fn`: closes `:local`.

It works with `dao.jing/materialize!`, `dao.jing/get` and `dao.jing/close!`,
and is a valid handle for `dao.jing.content/serve-step`.

**A remote miss is staged around the handle, not inside it.** The
composition issues `:jing/get` through `dao.jing.content.step`, drives the
DHT step until the completion lands (the verified bytes are then in
`:local`), and reads through the handle again. `dao.data.btree.storage`'s
hydration cache over `dao.jing.content.async` is the existing consumer shape.

This changes today's behavior, where a DHT `get` fetched synchronously on a
miss. The owner ratified the change.

### 5.2 The JVM blocking facade

Host policy, JVM only, optional. It owns a driver thread that is the DHT
state's one step owner and the tick adapter's cadence, and it wraps
`dao.jing.content.driver` so that a `get-bytes-fn` miss issues `:jing/get`
and waits to the driver's request deadline. Its `put-bytes-fn` is 5.1's: it
returns the local verdict and does not wait for the acknowledgement. No
portable code is written against the facade.

### 5.3 Time

Time reaches the DHT only as data. The step reads a tick stream the
composition wires and never a host clock, exactly as `dao.lease.md` (Time)
requires of lease code, and it reads the same fact:
`{:dao.lease/event :dao.lease/tick :dao.lease/reading n}`. Readings never
decrease. Units are the composition's; this contract fixes `{:ms 1}`.

Every duration in this document is a tick difference: query deadlines,
`ack-ticks`, `get-ticks`, partial-message eviction, cookie epochs. A step
that has observed no tick expires nothing. A tick gap makes expiry late, not
wrong. Tests drive time by appending ticks.

## 6. Peers and routing

- **A node id is not derived from an address.** It is 32 bytes of the key
  space, composition data. The recommended form is the self-certifying name
  of `dao.stream.remote.md` section 5 (a hash of a public key). Until
  authentication exists nothing verifies an id, and a peer may claim any id;
  that limit is stated, not hidden.
- **A routing entry is `{:id id :host h :port p}` where `h` and `p` are the
  address the socket observed**, never an address a payload claims. No wire
  message carries the sender's own address.
- **An entry is recorded only when proven returnable:** a reply arrives from
  the expected observed address matching a pending query, or a request
  arrives carrying a valid cookie. Nothing is recorded from an unvalidated
  datagram.
- Peers listed in a `:find` reply are third-party **hints**: candidates to
  ping, never entries.
- Replies go to the datagram's observed source.
- A proven entry whose id reappears from a different proven address is
  replaced: a NAT rebinding looks exactly like that.
- The table is `dao.jing.dht.kad`: `k = 20`, XOR distance, least-recently-seen
  eviction.
- **Lookup is explicit state**: target, shortlist, queried set, outstanding
  queries (at most `alpha = 3`). A candidate whose query exhausted its tries
  is dead for that lookup and is removed from the table, and the shortlist is
  refilled with the next-nearest candidate. A lookup ends when `k` peers have
  answered or candidates are exhausted.

NAT meeting is not in this epic. The DHT may share a hole-punched socket with
`dao.stream.udp` (`dao.stream.datagram.md` section 6), and the identity and
address rules above are what keep wire v1 usable once meeting exists.

## 7. The wire (v1)

One CBOR value per datagram, encoded with `dao.stream.cbor` (the stream
codec, canonical profile, available on every host). Not `dao.jing.cbor`, and
not Transit. This is a clean break from today's wire; no fleet exists.

```clojure
;; request
{:dao.jing.dht/v 1
 :op      :ping | :find | :store | :fetch
 :q       n                  ; query id: self-minted random safe integer
 :id      <32 bytes>         ; sender's node id
 :cookie  <bytes>
; the cookie this peer last gave the sender; absent on first contact
 :pad     <byte string>
; only on a request without a cookie: zero bytes, section 8
 :target  <32 bytes>         ; :find
 :address a                  ; :store, :fetch
 :bytes   <byte string>}     ; :store: the canonical payload bytes

;; reply
{:dao.jing.dht/v 1
 :op      :reply
 :q       n
 :id      <32 bytes>
 :cookie  <bytes>            ; a cookie for the requester's observed address
 ;; then exactly one of:
 :need-cookie true
 :ok      boolean            ; :ping, :store
 :peers   [[<32 bytes> host port] ...]   ; :find
 :found   boolean :bytes <byte string>}  ; :fetch

;; chunk
{:dao.jing.dht/v 1
 :op :chunk :q n :dir :request | :reply
 :part i :parts k
 :cookie <bytes>             ; :dir :request only
 :bytes  <byte string>}
```

- `:dao.jing.dht/v` is the discriminator on a shared socket and the version.
  A datagram that does not decode to a map carrying it is not the DHT's and is
  dropped silently. An unknown version is dropped without reply.
- Payload and chunk bytes are CBOR byte strings on the wire. Base64 appears
  only in stream-visible values (`:jing/bytes`, raw datagram events).
- Ids and targets are raw 32-byte strings on the wire and hex in state.
- **Peers per `:find` reply: at most 8**, as `[id host port]` tuples. The
  iterative lookup asks again for more. S3 proves by test that the largest
  legal reply (8 peers, IPv6 literals) encodes within `max-datagram`.
- **A reply is matched by `[observed source, :q]`** against a pending query
  sent to that address. `:q` alone matches nothing.
- **Chunks.** A message whose encoding exceeds the datagram budget is sent as
  `:chunk` records, each fitting the budget. Reassembly is keyed
  `[observed host, observed port, :dir, :q]`. Duplicates are ignored;
  a `:parts` disagreeing with the held count is dropped; accumulated bytes
  over `max-message-bytes` drop the partial; incomplete partials are evicted
  oldest-first past `max-partial-messages` and by age in ticks. Loss of a part
  is loss of the message, recovered by retrying the whole query.
- The total encoded size is checked against `max-message-bytes` **before any
  chunk is sent**.
- The DHT does not use `dao.stream.udp`'s fragment envelope or its
  `:dao.stream.remote/id` identity.
- **Reliability is the requester's**: each query has a deadline of
  `query-ticks` and `tries` attempts, then the peer is unreachable for that
  operation.

## 8. Hardening

Implemented where the DHT reads raw events, never in the raw layer. Non-loopback
exposure requires the S4 secret and inbound bound in the socket composition.

- **Cookie protocol.** Every reply carries a cookie for the requester's
  observed address. A requester keeps the newest cookie each peer gave it and
  echoes it in every later request and request chunk to that peer. A cookie
  is 16 bytes, is a pure function `cookie-for` of the epoch and the observed
  host and port, with `epoch = floor(now / cookie-epoch-ticks)`, and is valid
  under the current or the previous epoch. Verification recomputes and
  compares; it holds no state. The protocol, the gate below and freshness
  (4.1) are one mechanism, implemented whole in S2.
- **`cookie-for` has two definitions, and only it differs between slices.**
  - *S2 stand-in:* the first 16 bytes of `SHA-256(epoch | observed-host |
    observed-port)`. Unkeyed and deterministic, so tests can predict it, and
    forgeable, which is why exposure stays loopback-only.
  - *S4 replacement:* the first 16 bytes of `MAC(epoch-secret, observed-host |
    observed-port)` with `epoch-secret = MAC(:dao.jing.dht/secret, epoch)`.
    The MAC is HMAC-SHA-256 from each host's library; nothing is
    hand-rolled. The root secret is, per node, at least 32 random bytes
    from the host CSPRNG, minted at composition, in-memory only, never
    persisted or shared. Note that the tests share one secret for
    predictability.
    From S4 a socket composition without a secret is a composition defect.
  The wire, the state and every other rule are identical under both.
- **The gate.** A request or request chunk without a valid cookie causes no
  work: no lookup, no storage, no reassembly, no routing entry. The only
  possible response is the **need-cookie reply**, exactly
  `{:dao.jing.dht/v 1 :op :reply :q n :id <32 bytes> :cookie <16 bytes>
  :need-cookie true}` and nothing else.
- **No amplification, without exception.** The need-cookie reply is sent only
  if its encoded length is less than or equal to the length of the datagram
  that caused it. Otherwise the datagram is dropped in **silence**. There is
  no other reply to an unproven source.
- **First contact is padded.** A requester sending without a cookie adds
  `:pad`, a zero-filled byte string, so that the datagram is at least
  `first-contact-bytes` long. `first-contact-bytes` is 256, a constant of
  wire v1. S2 proves by test that the need-cookie reply never encodes to more
  than 256 bytes. A request carrying a cookie carries no `:pad`. A request
  without a cookie is only ever a padded `:ping`: a requester never sends
  `:find`, `:store`, `:fetch` or a chunk to a peer it holds no cookie for.
- **Recovery.** On a need-cookie reply the requester re-sends the query
  once carrying the new cookie; that re-send does not consume a try. The
  need-cookie reply's cookie is untrusted: it rides that one re-send, and
  the requester stores a peer's cookie only when a full reply carries it. A
  re-send the socket refuses is a failed send. A query
  that times out against a peer may have been dropped in silence (an expired
  cookie on a small datagram), so the requester discards that peer's cookie
  and sends a padded `:ping` before the next try.
- **A request chunk is verified before any partial state is allocated**, and
  an invalid one falls under the gate and the size rule above like any other
  datagram.
- **Reply chunks** are accepted only for a pending query to that observed
  address; the state was allocated by this node's own request.
- **Inbound `:store`** re-verifies address against bytes and canonicality
  before writing, and is refused (`:ok false`) when `publish?` is false or the
  inbound storage bound is reached. Refusal is explicit, never silence.
- **Bounds**, all composition data (section 9): pending queries, pending
  writes, pending gets, partial messages and bytes, globally and per observed
  source; work per step.

## 9. Limits and defaults

<table>
<tr>
<th>
Datum
</th>
<th>
Default
</th>
<th>
Notes
</th>
</tr>
<tr>
<td>
<code>:dao.jing.dht/ack-peers</code>
</td>
<td>
2
</td>
<td>
Floor 2, ceiling <code>k</code>.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/ack-ticks</code>
</td>
<td>
5000
</td>
<td>
Acknowledgement deadline per pending write.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/get-ticks</code>
</td>
<td>
5000
</td>
<td>
Deadline per pending get.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/query-ticks</code>, <code>/tries</code>
</td>
<td>
500, 3
</td>
<td>
Per query, today's figures.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/max-message-bytes</code>
</td>
<td>
65536
</td>
<td>
Encoded message bound, send and reassembly. S3 measures real covered-index nodes
at the default branching factor and confirms or raises this default before S4;
it is composition data, not a wire constant.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/max-partial-messages</code>
</td>
<td>
64
</td>
<td>
Global; per-source share is a quarter.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/max-pending-writes</code>, <code>/max-pending-gets</code>
</td>
<td>
64, 64
</td>
<td>
Beyond: <code>/busy</code>, or not found with a <code>/miss</code>.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/cookie-epoch-ticks</code>
</td>
<td>
60000
</td>
<td>
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/max-inbound-bytes</code>
</td>
<td>
composition's
</td>
<td>
Inbound <code>:store</code> bound; S4. <code>dao.space.dht</code> and the REPL
default it to 64 MiB (<code>--dht-max-inbound-bytes</code>).
</td>
</tr>
<tr>
<td>
datagram budget
</td>
<td>
1200
</td>
<td>
The socket's <code>:max-bytes</code>.
</td>
</tr>
<tr>
<td>
<code>:dao.jing.dht/bind-host</code>
</td>
<td>
<code>127.0.0.1</code>
</td>
<td>
Must match the composed socket's bind family.
</td>
</tr>
</table>

S3 measured a real `dao.space.index/publish-index!` build at the default
branching factor of 512 over 512 distinct datoms. It emitted two covered
index node blobs and one manifest; the largest canonical value was 26,497
bytes, and its maximal-cookie `:store` wire value was 26,720 bytes. The
65,536-byte default therefore covers this measured build. Larger application
values still require an explicit composition limit or refusal.

Every stream the DHT is composed over is an evicting ring: requests,
answers, facts, raw traffic. None is a complete-history log.

## 10. Today's code, and the slices that replace it

What the tree holds on 2026-09-30, all of it replaced by this epic with no
compatibility path:

<table>
<tr>
<th>
Today
</th>
<th>
Fate
</th>
</tr>
<tr>
<td>
<code>dao.jing.dht/IDhtNet</code>, <code>lookup</code>,
<code>create-content-dht</code> (a waiting transport protocol; put replicates
and get fetches synchronously)
</td>
<td>
Deleted in S3, together with <code>dao.jing.dht.node</code>, which implements
and calls them (Architect ruling on the S2 sign-off). S2's core does not use
them.
</td>
</tr>
<tr>
<td>
<code>dao.jing.dht.node</code> (<code>create-node</code>,
<code>create-content-dht-udp</code>; JVM only, own <code>DatagramSocket</code>
and receiver thread, one Transit-JSON map per datagram, node id
<code>sha256(host:port)</code>, claimed <code>:from</code> observed unvalidated,
replies matched by id alone, oversize dropped silently)
</td>
<td>
Deleted in S3.
</td>
</tr>
<tr>
<td>
<code>dao.jing.dht.kad</code>
</td>
<td>
Kept.
</td>
</tr>
<tr>
<td>
<code>test/dao/jing/dht_test.cljc</code>,
<code>test/dao/jing/dht/node_test.cljc</code>, the fake net in
<code>test/yin/vm/linker_test.cljc</code>
</td>
<td>
Rewritten in S2 and S3.
</td>
</tr>
</table>

### Slice plan

Order: **S1 and S2 -> S3 -> S4 -> S5.** S1 and S2 own disjoint files and neither
consumes anything the other produces. No slice before S5 touches
`yin/repl/*`.

**Base64 seam.** The format is one: padded standard-alphabet Base64, strict.
S2's codec is the existing `dao.jing/bytes->base64` and
`dao.jing/base64->bytes`, frozen as its seam; S2 adds no codec and waits on
none. S1 creates `dao.stream.base64` for the datagram layer, byte-for-byte
the same format, and does not touch `dao.jing`. S3, which joins the two,
repoints `dao.jing`'s two functions onto `dao.stream.base64` and proves both
agree on every test vector.

**S1: raw datagram stream.** `dao.stream.datagram` (portable), the three
host seams, `dao.stream.base64`, `dao.stream.udp`'s `port-step!`.
Acceptance:
- Exact byte and observed-source round trips over loopback on JVM, Node and
  Dart, through the writer handle and the traffic ring.
- `bound`, `bind-failed`, `send-failed` and `closed` are observed as events;
  no seam takes a function to invoke.
- Hostname destination, bad Base64 and oversize are `invalid-value` with no
  send; a closed socket is `closed`; inbound oversize is never deposited.
- A slow reader observes `gap` on the traffic ring.
- `test/dao/stream/udp_test.cljc` and every `dao.stream.remote` test pass
  unchanged; host seam tests are rewritten.
- Value-channel throughput is measured before and after and reported.

**S2: DHT core on in-memory sockets.** `dao.jing.dht` state and step,
lookup as state, pending writes and gets, the store handle, solo mode, the
JVM facade, the `:jing/unacknowledged` completion in `dao.jing.content.step`.
Peers are wired through ring buffers carrying raw datagram values; S2 needs
no socket and no S1. It speaks the section 7 wire for single-datagram
messages and implements the whole cookie protocol of section 8 (issue, echo,
verify, the gate, the size rule, padding, freshness) with the S2 stand-in
`cookie-for`. Chunks are S3; the keyed MAC is S4. Acceptance:
- An acknowledged write used, for every counted peer, the cookie that peer's
  fresh reply carried; a store with a missing or wrong cookie causes no work.
- The need-cookie reply never encodes to more than `first-contact-bytes`, and
  a cookie-less datagram shorter than that reply is answered with silence.
- Requests and answers are the exact `:jing/*` shapes; the unmodified content
  clients drive a DHT.
- With a warm table a write is acknowledged in the step that read it, after
  exactly `ack-peers` counted sends, and the fact says `/sent`.
- `put-bytes-fn` performs no lookup and no peer wait; the publish/readback
  round completes against `:local`.
- Each `/unacknowledged` reason of 4.3 is produced by a test, including cold
  bootstrap reaching `too-few-peers` at the tick deadline and then
  succeeding once peers are proven.
- Solo mode composes no socket streams and holds no pending work.
- A file-backed local verdict survives restart.
- Time advances only by appended ticks; no test sleeps.
- The JVM facade has one step owner and respects deadlines.
- `dao.jing.md`'s backend list and status are updated.

**S3: real sockets and chunks.** Depends on S1 and S2. Chunk records and
the move onto `dao.stream.datagram` sockets. Loopback only. Acceptance:
- Two and three peers exchange small queries and multi-datagram segments
  under injected loss and reordering.
- The largest legal `:find` reply fits one datagram.
- Oversize refuses before any send; malformed, duplicate and inconsistent
  chunks stay within every bound.
- A chunked write counts a peer only when every chunk answered `ok`.
- **One transport-neutral chunk split/absorb utility is used by both
  `dao.stream.udp` and the DHT**, each keeping its own wire fields and keys.
  **Declared fallback:** if the two identities resist clean parameterization,
  the DHT ships its own reassembly and `dao.stream.udp` is left untouched.
  The slice report says which was taken and why.
- Real covered-index nodes are measured against `max-message-bytes`.
- JVM<->Node and JVM<->Dart pass the same wire tests.
- `dao.jing.cbor.md` (Remote and DHT) is updated to this wire.
- `dao.jing`'s Base64 functions are repointed onto `dao.stream.base64`.

**S4: hardening, lookup repair, storage bound.** Depends on S3. The keyed
`cookie-for` replacing the S2 stand-in, the chunk path under the gate, and
every bound. Acceptance:
- A cookie cannot be computed without the secret; the S2 stand-in is gone.
- A spoofed source elicits at most one reply, never larger than the datagram
  it sent, and silence when the reply would be larger; it allocates nothing.
  Tested across datagram sizes from 1 byte to the budget, chunks included.
- A reply from an unexpected source is ignored; a claimed address never
  enters the table.
- A live next-nearest peer is tried after dead peers, and dead peers leave
  the table.
- Inbound storage at its bound refuses explicitly.
- `publish?` false neither originates nor serves.
- Non-loopback exposure is permitted only after this slice.

**S5: REPL integration.** Depends on S4 and on the durable-store epic (spec,
HEAD, lock, recovery). Acceptance:
- Default `mem`; `dht:<dir>` is explicit; with no peers it is solo and opens
  no socket.
- Publishing requires its own flag, separate from peers, and the REPL states
  what will be shared before it shares.
- The REPL shows per publication whether it is acknowledged (sent to N
  peers) or not and why; no round waits on the network.
- Two processes with separate locked directories exchange content.
- A reader given a manifest address hydrates and queries a remote index.

### The plain Clojure path (S5, owner direction 2026-10-01)

Owner, verbatim: "plain clojure code should be able to query for code in the
dht. the yin.repl should use the same path as the clojure repl via
host-functions".

`dao.space.dht` (CLJC; JVM, Node, Dart) is that path. It lives under
`dao.space` because it joins a `dao.jing.dht` node to `dao.space.index` and
`dao.space.query`; putting it under `dao.jing` would make the
payload-agnostic well aware of index structure. It has no `yin.repl`
dependency.

- `(join opts)` composes a node over `:local` (a `dao.jing` store the
  caller has opened, and locked if it is a directory) or `:dir`, which
  `join` opens as the durable directory store `dao.space.store/open`:
  exclusively, under its directory lock (OS locks on the JVM and Dart,
  claim entries on Node, and the in-process registry), so a second owner in
  this or another process is refused naming the directory; `close!`
  releases it. The REPL's `file:<dir>` and `dht:<dir>` stores open the
  same store, so a REPL and a plain node never share a directory. Options:
  `:peers`
  (none is solo: no socket, no secret), `:publish?` (default false, never
  implied by peers), `:bind-host` (default `127.0.0.1`), `:bind-port`
  (default 0), `:max-inbound-bytes` (default 64 MiB), `:bind!` (the host
  datagram seam; each build's own by default). A node with peers mints its
  root secret at join: 32 CSPRNG bytes, held only in the node value.
- `(step node now)` is the node's only advance; `now` is its owner's
  nondecreasing millisecond reading, appended as a tick. It answers
  `[node events]` (`yin.vm.linker.dht.md` 4.3 and 5.5.3):
  - `:bound`, `:bind-failed`;
  - `:published`, the first report of an announced publication, once every
    blob has an outcome: `:result` (`:acknowledged`, `:partial` or
    `:unacknowledged`), `:blobs`, `:sent`, `:peers`, `:failed` (every blob
    not sent, with its reason), `:repairing?`, and `:ended` when repair
    will not run;
  - `:republished`, the same shape, whenever that result changes after the
    first report or its automatic repair ends;
  - `:publication-unknown` (the facts were lost);
  - `:loaded` and `:load-failed`, one per load, with its `:kind` and, on
    failure, a `:reason` that is data.
- `(store node)` is the node's byte-store handle; a put records the address
  for the node's next publication and asks the DHT for nothing.
  `(announce! node manifest)` closes the publication its puts made. `step`
  paces the replicate requests below `max-pending-writes` through a bounded
  backlog, so the node's own writes never draw `/busy`. `retry!` and
  `cancel!` act on a live publication (`yin.vm.linker.dht.md` 5.5).
- `(load node address {:kind k :walk f})` starts a staged load over any
  walk; `(load-index node manifest)` is `load` with the covered-index walk,
  which reads the index through the local store with
  `dao.space.index/read-manifest`. `step` fetches each blob the walk names
  missing with a `:jing/get` through `dao.jing.content.step`, until all
  four covered indexes read back and cover the manifest's count. `forget`
  clears a terminal load.
- `(db node manifest)` is `dao.space.query/published-db` over the loaded
  index (`restored-indexes`); `(q node manifest query & inputs)` is
  `dao.space.query/q` over its current view.

`yin.repl` composes its `dht:<dir>` store with `join` (over its locked
durable store), and its `dao.space.dht` host module answers `load-index`,
`load-status` and `q` with these functions (`yin.repl.dao.space-index.md`,
"DHT store").

Out of this epic: pinning, garbage collection, availability repair and
reconciliation sweeps, NAT meeting, a browser transport, latest-root
discovery, authenticated node ids.

**Loading code for evaluation** is the next epic, specified in
[`yin.vm.linker.dht.md`](./yin.vm.linker.dht.md). It generalizes
`load-index` into `dao.space.dht/load` (a staged load over any walk, with
failure reasons as data), adds a bounded replicate backlog so a round's
many row writes never report `/busy`, replaces the `:published` event's
`:acknowledged?` with a result of `:acknowledged`, `:partial` or
`:unacknowledged` that lists every blob not sent, retries a publication
that is not acknowledged automatically while the node is open (reported by
a `:republished` event), and makes the node's store the linker's content
source. It changes nothing in sections 2 to 9.

## Relationship to the intake-pool observer

The DHT is a content-store backend, not a stream transport. The intake-pool
observer (`dao.jing/observer-state` / `observe-step!`) materializes whatever
the intake streams carry; whether the content store behind it is a DHT handle
is invisible to the observer. Conversely, the DHT replicates content, never
streams: there is no ordered, push-based replication of a writer's log through
this layer and no single-writer authority to coordinate: each node simply
stores and fetches content-addressed payloads.

## Lineage

The pull-based, content-addressed model is Datomic's storage architecture
(immutable segments fetched by digest) placed over a Kademlia grid. The
verification-by-hash property is the DHT's own trick: because the key is the
digest of the value, distribution can be adversarial without a trusted
directory. The acknowledgement follows Kafka's producer contract: a write is
acknowledged by what was sent, at a level the composition chooses.
