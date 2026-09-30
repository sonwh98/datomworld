# DaoStream Datagram: The Raw Datagram Layer

Status: design target, not implemented (DHT epic slice S1). Subordinate to
[`dao.stream.md`](./dao.stream.md), which is the contract and wins on any
disagreement, and to the Host Boundaries section of
[`datom.world.md`](./datom.world.md). Every sentence below is a rule.

## 1. Place in the stack

The governing direction, in the owner's words:

> udp datagram api should be foundational and dao.stream on top and the dht
> is built on a udp base dao.stream

```
host socket (DatagramSocket | dgram | RawDatagramSocket)
  └─ dao.stream.datagram        one bound socket as dao.stream values: this document
       ├─ dao.stream.udp        decoded values, fragmentation, projection (dao.stream.remote.md 3.2)
       │    └─ dao.stream.remote   mirror, reflection, link
       └─ dao.jing.dht          Kademlia wire, chunks, hardening (dao.jing.dht.md)
```

A datagram socket is exposed as two ordinary stream ends: a **writer handle**
whose `append!` hands one addressed datagram to the socket, and a
**traffic stream**, supplied by the composition, onto which the host adapter
deposits every received datagram as one event. The socket retains nothing, so
it has no reader surface (`dao.stream.md`, Surfaces); readers hold their own
cursors on the traffic stream.

This layer carries bytes and addresses and nothing else. It does not decode,
retry, order, deduplicate, fragment, reassemble, resolve names, or traverse
NATs. Each of those is an interpreter above it.

## 2. Values

All keys are under `:dao.stream.datagram/`. Every value is plain data and
survives any `dao.stream` codec, Transit-JSON included.

```clojure
;; outbound: the value append! takes
{:dao.stream.datagram/destination {:dao.stream.datagram/host "203.0.113.7"
                                   :dao.stream.datagram/port 4100}
 :dao.stream.datagram/bytes       "<base64>"}

;; inbound: one event per received datagram
{:dao.stream.datagram/socket id                ; the socket's logical identity
 :dao.stream.datagram/source {:dao.stream.datagram/host "198.51.100.2"
                              :dao.stream.datagram/port 53122}
 :dao.stream.datagram/bytes  "<base64>"}

;; lifecycle: one event per host fact about the socket itself
{:dao.stream.datagram/socket id
 :dao.stream.datagram/event  :dao.stream.datagram/bound        ; | /bind-failed
                                                               ; | /send-failed
                                                               ; | /closed
 :dao.stream.datagram/local  {:dao.stream.datagram/host h      ; bound only
                              :dao.stream.datagram/port p}
 :dao.stream.datagram/reason "<text>"}                         ; failures only
```

- `:dao.stream.datagram/bytes` is padded standard-alphabet Base64 of the
  exact datagram payload: no whitespace, no URL-safe alphabet. A zero-length
  datagram is the empty string. Host byte arrays never cross onto a stream
  (`datom.world.md`, Host Boundaries); Base64 is what lets a traffic stream be
  served over any channel.
- `:dao.stream.datagram/host` is always an **IP literal** in its textual form,
  IPv4 or IPv6. A source host is the address the socket observed. A
  destination host that is not an IP literal is `invalid-value`: name
  resolution can wait, and no operation waits. Resolution is a separate
  interpreter or host policy that produces literals.
- `:dao.stream.datagram/port` is an integer in 1..65535.
- A datagram event carries `:dao.stream.datagram/bytes` and
  `:dao.stream.datagram/source`; a lifecycle event carries
  `:dao.stream.datagram/event`. A value carrying neither is not this layer's.

The strict Base64 codec lives at or below `dao.stream` (one portable helper
namespace, `dao.stream.base64`, created in S1). This layer never requires
`dao.jing`: a boundary depends on nothing above it. `dao.jing` keeps its own
identical-format functions until S3 repoints them onto this helper
(`dao.jing.dht.md` section 10, Base64 seam).

## 3. The socket, its identity and its descriptor

Binding is a host act, not `create!`. On Node and Dart the bound port of an
ephemeral bind is not knowable until the host answers, so identity and
reachability cannot both be settled at once, which `dao.stream.md` (Creation
and Attachment) requires of `create!`. The composition therefore proceeds in
two explicit moves.

1. **Bind.** The host seam (`dao.stream.datagram.jvm`, `.node`, `.dart`)
   takes `{:identity id :deposit <writer> :bind-host h :bind-port p
   :max-bytes n}` and returns at once `{:send! f :close! f}`. `id` is a
   string the composition mints. `:bind-host` is the local interface address
   (the wildcard address is legal); `:bind-port` 0 asks for an ephemeral
   port. The seam takes the deposit writer and nothing else from above: it is
   handed no function to invoke.
2. **Observe `bound`, then compose the writer.** The seam deposits
   `:dao.stream.datagram/bound` with the actual local address, on every host,
   synchronous bind or not. The composition reads that event and calls the
   portable constructor `dao.stream.datagram/writer` with the seam and the
   complete descriptor. A failed bind deposits `:bind-failed` and there is no
   writer.

The descriptor:

```clojure
{:dao.stream/type                :dao.stream/datagram
 :dao.stream/identity            id
 :dao.stream.datagram/bind-host  h     ; the LOCAL interface this socket is bound on
 :dao.stream.datagram/bind-port  p}    ; the LOCAL port actually bound, never 0
```

- The keys say `bind-` because they name the **local** socket. The UDP value
  channel's descriptor keys, `:dao.stream.udp/host` and `:dao.stream.udp/port`
  (`dao.stream.remote.md` 3.2), name the **remote destination** its writer
  sends toward. The two descriptors appear side by side in one composition and
  must never be read as the same kind of address.
- A bind address is not a reachable address. Behind a NAT the address peers
  observe differs from both keys; nothing here claims otherwise, and no
  consumer advertises `bind-host`/`bind-port` as where it can be reached.
- `attach!` on this descriptor off its host is `:dao.stream/not-found`, as a
  ring buffer's is. On its own host it resolves only against sockets a
  composition chose to keep in its host-composed attach closure; there is no
  registry. There is no `:dao.stream/create` entry.

## 4. The writer handle

Declared surface: `#{:writer :closable}`. `descriptor` answers `ok` with the
descriptor above and its identity, before and after close.

`append!` outcomes:

| Outcome | When |
|---|---|
| `:dao.stream/ok` | The datagram was handed to the socket. It asserts nothing about the wire, the path, or the destination. |
| `:dao.stream/invalid-value` | Not an outbound value of section 2: a malformed destination, a host that is not an IP literal, bytes that are not strict Base64, or a decoded length over `:max-bytes`. Nothing was sent. |
| `:dao.stream/closed` | The socket was closed. Nothing was sent. |
| `:dao.stream/transport-error` | The host send failed synchronously. Failures are clean: nothing was sent. |

Excluded outcomes, with reasons: `full`, because this layer holds no outbound
queue and a socket under pressure loses datagrams, which is the medium's own
declared nature, not a refusal; `refused`, because no policy is composed in
this layer (a gate composed over the handle may produce it,
`dao.stream.middleware.md`).

A host whose send reports failure only later (Node, Dart) deposits
`:dao.stream.datagram/send-failed` on the traffic stream; that is the channel
this transport declares for an answer it cannot give at call time
(`dao.stream.md`, Writing).

`close!` answers `ok`, closes the host socket, and is idempotent. The seam
deposits `:dao.stream.datagram/closed` when it still can; the irreducible
corner of `dao.stream.md` (Surfaces, observably gone) applies when it cannot.

`:max-bytes` is composition data, default 1200, and is symmetric: an inbound
datagram longer than it is dropped below the transform as protocol
validation, counted and never deposited. 1200 clears the IPv6 minimum path
MTU with headers to spare.

## 5. The traffic stream

- The deposit destination is supplied by the composition and **must be an
  evicting ring** (`dao.stream.ringbuffer`). A complete-history log under a
  network-facing socket is a host assembly defect: any sender on the network
  could grow it without bound.
- A ring never refuses, so the deposit-admission rule of `dao.stream.md`
  (Surfaces) holds by construction.
- A `:dao.stream/gap` on the traffic stream means datagrams were lost to this
  reader. It is indistinguishable from network loss and is handled the same
  way, by whatever loss policy the reading interpreter already has.
- Capacity is counted in events. One event is at most `:max-bytes` of payload
  as Base64, so memory is bounded by `capacity * ceil(max-bytes * 4 / 3)` plus
  envelope.
- The seam's transform deposits and returns. It judges no datagram and
  invokes nothing above itself.

## 6. Peer observers on one socket

Any number of interpreters may read one traffic stream, each with its own
cursor, and may write through the one writer handle. None knows the others
exist. A datagram that is not an interpreter's own is dropped by that
interpreter without reply and without diagnostics on any shared stream.

Two interpreters are specified today and may share a socket, which is what
lets a hole-punched mapping (`dao.stream.remote.md` section 4) serve both:

- `dao.jing.dht` owns exactly the datagrams whose decoded value is a map
  carrying `:dao.jing.dht/v` (`dao.jing.dht.md`, The wire).
- `dao.stream.udp` owns every other decodable value, and drops a map carrying
  `:dao.jing.dht/v` before its deposit.

A further interpreter on a shared socket declares its own discriminator here.

## 7. `dao.stream.udp` rebuilt on this layer

`dao.stream.udp` keeps its public surface: the descriptor, `make-port`,
`send-value!`, `send-to!`, `receive!`, `projection`, `step!`,
`make-attacher`, the fragment envelope, and both reassembly bounds. What
changes is what feeds it.

- `make-port`'s `:send!` is composed as a closure that encodes the bytes as
  Base64 and appends one outbound value to the raw writer. `send-value!`
  answers the raw writer's first non-`ok` outcome instead of discarding it.
- **`port-step!`** is new: one step that reads the raw traffic stream from
  the port's own raw cursor to `blocked`, bounded by a budget, skips lifecycle
  events and `:dao.jing.dht/v` maps, decodes each datagram event's Base64, and
  calls `receive!` with the source address and bytes. A raw `gap` adopts the
  recovery cursor: those datagrams are lost, and the link's resend rule
  recovers exactly as it does for network loss.
- One owner per port drives `port-step!`, and the composition drives it
  before the projections' `step!`. The reassembled-value traffic medium and
  everything above it, `dao.stream.remote` included, is unchanged.

The host-free protocol tests (`test/dao/stream/udp_test.cljc`) pass
unchanged. The host seam tests are rewritten against section 3's seam,
because the seam's signature changes from a function to invoke to a deposit
writer.

## 8. Exposure

A raw writer handle entered in a mirror table
(`dao.stream.remote.md` 2) lets whoever reaches it send arbitrary datagrams
from this host. No composition does so without a gate on that entry.
