# DaoStream BLE: The Raw Bluetooth Layer and the Flooding Mesh

Status: S1 design. Subordinate to [`dao.stream.md`](./dao.stream.md), which
is the contract and wins on any disagreement, to the Host Boundaries section
of [`datom.world.md`](./datom.world.md), and to
[`dao.stream.datagram.md`](./dao.stream.datagram.md), whose shape this
document follows section for section. Every sentence below is a rule.

## 1. Place in the stack

The governing request, in the owner's words:

> dao.stream over bluetooth BLE so that when there is no 5G or WiFi,
> datom.world can continue to work over bluetooth using mesh p2p networking

```
BLE radio (CoreBluetooth | Blue | flutter_blue_plus + flutter_ble_peripheral)
  └─ radio adapter   dao.stream.ble/dart (cljd): plugin streams → radio events
       └─ dao.stream.ble.links   host-free link state machine over radio events
            └─ dao.stream.ble     one radio node as dao.stream values: this document
                 └─ dao.stream.ble.mesh   flooding interpreter: origin, seq, TTL, dedup
```

A BLE radio node is exposed as two ordinary stream ends, the same shape as a
datagram socket: a **writer handle** whose `append!` hands one addressed
frame to the radio, and a **traffic stream**, supplied by the composition,
onto which the host adapter deposits every received frame and every host
fact about the node and its links as one event. The radio retains nothing,
so it has no reader surface (`dao.stream.md`, Surfaces); readers hold their
own cursors on the traffic stream.

The raw layer carries bytes, peer addresses and link facts and nothing
else. It does not decode, retry, order, deduplicate beyond link redundancy,
fragment, reassemble, or route. The mesh layer above it is the first
interpreter, and flooding is its whole routing strategy.

## 2. Values

All keys are under `:dao.stream.ble/`. Every value is plain data and
survives any `dao.stream` codec, Transit-JSON included.

```clojure
;; outbound: the value append! takes
{:dao.stream.ble/destination "<peer address>"
 :dao.stream.ble/bytes       "<base64>"}

;; inbound: one event per received frame
{:dao.stream.ble/node   id          ; the radio node's logical identity
 :dao.stream.ble/source "<peer>"    ; the link the frame arrived on
 :dao.stream.ble/bytes  "<base64>"}

;; lifecycle: one event per host fact about the node or its links
{:dao.stream.ble/node   id
 :dao.stream.ble/event  :dao.stream.ble/bound      ; | /bind-failed
                                                   ; | /peer-seen  | /peer-lost
                                                   ; | /link-up    | /link-down
                                                   ; | /send-failed
                                                   ; | /closed
 :dao.stream.ble/self   "<node identity>"          ; bound only
 :dao.stream.ble/peer   "<peer>"                   ; peer/link events
 :dao.stream.ble/direction :central | :peripheral  ; link-up only
 :dao.stream.ble/rssi   -71                        ; peer-seen only
 :dao.stream.ble/reason "<text>"}                  ; failures only
```

- **A peer address is an opaque, link-scoped string.** Android reports a
  MAC; iOS reports a per-local-device UUID. No structure is assigned, no
  global meaning is claimed, equality is string equality, and the same
  physical device may present different peer addresses on different nodes,
  on the two roles of one node, or across rediscovery. A peer address names
  one directional GATT link as this radio's state observes it. Nothing
  above this layer may treat a peer address as a node's name: the mesh
  layer's origin identity (section 7) is the name, and flooding is the
  whole of routing, which is why.
- **A node identity (`:dao.stream.ble/self`) is a composition-minted
  string**, the same kind of value a datagram socket's `id` is. It names
  this radio node's logical stream to every observer of the traffic
  stream. It is not authenticated and not exchanged on the radio by this
  layer; the mesh layer carries an origin identity of its own in-band
  (section 7), which is the one interpreters correlate on.
- `:dao.stream.ble/bytes` is padded standard-alphabet Base64 of the exact
  frame payload: no whitespace, no URL-safe alphabet. A zero-length frame
  is the empty string. Host byte arrays never cross onto a stream
  (`datom.world.md`, Host Boundaries).
- A frame event carries `:dao.stream.ble/bytes` and `:dao.stream.ble/source`;
  a lifecycle event carries `:dao.stream.ble/event`. A value carrying
  neither is not this layer's.

## 3. The node, its identity and its descriptor

Binding is a host act, not `create!`. Radio acquisition — turning on
advertising and scanning, being granted permissions — answers late on
every host, so identity and reachability cannot both be settled at once,
which `dao.stream.md` (Creation and Attachment) requires of `create!`. The
composition therefore proceeds in two explicit moves, the datagram moves.

1. **Bind.** The host seam (`dao.stream.ble/dart`; more seams may follow)
   takes `{:identity id :deposit <writer> :service-uuid u
   :max-frame-bytes n :max-links n :announce-window-ms ms :now-ms f}` — a
   deposit writer and nothing else, no function to invoke — and returns at
   once `{:send! f :close! f}`. `id` is a string the composition mints.
   The seam is handed the deposit writer and its own configuration and
   nothing else from above.
2. **Observe `bound`, then compose the writer.** The seam deposits
   `:dao.stream.ble/bound` with the node identity, on every host,
   synchronous start or not. The composition reads that event and calls
   the portable constructor `dao.stream.ble/writer` with the seam and the
   complete descriptor. A failed start deposits `:bind-failed` and there
   is no writer.

The descriptor:

```clojure
{:dao.stream/type      :dao.stream/ble
 :dao.stream/identity  id
 :dao.stream.ble/self  "<node identity>"}
```

- The descriptor names the **local radio node**. It is reachability data
  in the honest, reduced sense: a BLE node is reachable by radio
  proximity and by advertising the service UUID, never by dialing the
  descriptor. Nothing above this layer may construct a route from it.
- `attach!` on this descriptor off its host is `:dao.stream/not-found`, as
  a datagram socket's is. On its own host it resolves only against nodes a
  composition chose to keep in its host-composed attach closure;
  `make-attacher` is that closure over a composition-owned resolver, never
  a registry. There is no `:dao.stream/create` entry.

The seam's configuration, all composition data:

- `:service-uuid` — the GATT service the mesh speaks on. Defaults to the
  datom.world mesh profile: service `7daf6c80-3f2a-4e11-9d47-1b2a3c4d5e6f`
  with a TX characteristic (`7daf6c81-…`, notify — the node's frames out
  to a subscribed central) and an RX characteristic (`7daf6c82-…`, write —
  a central's frames in). The TX/RX pair is the shape every GATT server
  plugin serves; one characteristic cannot be both directions on some
  platforms. A composition that changes these changes who can hear it.
- `:max-frame-bytes` — the symmetric frame budget, default **180**. This
  clears one notification payload under the smallest ATT MTU hosts
  commonly negotiate (iOS has granted as little as 185); a composition
  that observes a larger negotiated MTU may raise it. Symmetric: an
  inbound frame longer than it is dropped below the transform as protocol
  validation, never deposited.
- `:max-links` — the ceiling on concurrent GATT links, default 8 (an iOS
  central holds around ten). The seam's link policy (section 6) refuses
  beyond it.
- `:announce-window-ms` — the discovery throttle (section 5), default
  2000.
- `:now-ms` — the clock the throttle reads, `(f)` → integer
  milliseconds. A host seam supplies the host clock; the state machine
  (section 6) takes it as data, so tests and the simulation supply a
  deterministic clock. **The clock is the seam's own operational fact**,
  like the socket a datagram seam binds: no protocol state below the seam
  reads it, and `dao.stream.udp`'s no-clock rule for protocol state
  stands.

## 4. The writer handle

Declared surface: `#{:writer :closable}`. `descriptor` answers `ok` with
the descriptor above and its identity, before and after close.

`append!` outcomes:

| Outcome | When |
|---|---|
| `:dao.stream/ok` | The frame was handed to the radio toward the destination link. It asserts nothing about the air, the peer, or delivery. |
| `:dao.stream/invalid-value` | Not an outbound value of section 2: a malformed destination (not a non-empty string), bytes that are not strict Base64, or a decoded length over `:max-frame-bytes`. Nothing was sent. |
| `:dao.stream/closed` | The node was closed. Nothing was sent. |
| `:dao.stream/transport-error` | The send failed synchronously: no link to the destination peer at this moment, or the host refused the write. Failures are clean: nothing was sent. |

Excluded outcomes, with reasons: `full`, because this layer holds no
outbound queue and a radio under pressure loses frames, which is the
medium's own declared nature, not a refusal; `refused`, because no policy
is composed in this layer (a gate composed over the handle may produce it,
`dao.stream.middleware.md`).

Every send failure the seam observes deposits
`:dao.stream.ble/send-failed` on the traffic stream. A synchronous failure
also answers `transport-error` to the caller; a failure reported later has
only the event channel (`dao.stream.md`, Writing). These events carry the
destination peer and no correlation id, so a caller must not count both
the answer and the event as separate failures. "No link to the peer" is a
normal BLE fact — links come and go with proximity — and is reported the
same way as any other send failure; the writer of a flooding mesh does not
retry it, because the mesh's redundancy is the retry.

`close!` answers `ok`, stops advertising and scanning, tears down the
node's links, and is idempotent. The seam deposits
`:dao.stream.ble/closed` when it still can; the irreducible corner of
`dao.stream.md` (Surfaces, observably gone) applies when it cannot.

## 5. The traffic stream

- The deposit destination is supplied by the composition and **must be an
  evicting ring** (`dao.stream.ringbuffer`), for the datagram reasons: any
  broadcaster on the air could grow a complete-history log without bound.
- A ring never refuses, so the deposit-admission rule of `dao.stream.md`
  (Surfaces) holds by construction. A `:dao.stream/gap` on the traffic
  stream means frames and link facts were lost to this reader, and link
  facts lost are link facts the reader must not pretend to hold.
- **The discovery throttle.** Advertisements repeat many times a second.
  Each observed advertisement of the service UUID deposits at most one
  `:dao.stream.ble/peer-seen` event per peer per `:announce-window-ms` —
  the latest RSSI wins. This is a host-side throttle over the medium's own
  repetition, the same class of fact as the negotiated MTU: composition
  data declares it, and no protocol state below the seam depends on it.
  A peer that stops advertising for the window's worth of missed
  advertisements may deposit `:dao.stream.ble/peer-lost`; S1 seams
  deposit `peer-lost` only when a link goes down, never from silence
  alone, because silence is not a fact a radio reports.
- Capacity is counted in events. One event is at most `:max-frame-bytes`
  of payload as Base64, so memory is bounded by
  `capacity * ceil(max-frame-bytes * 4 / 3)` plus envelope.

## 6. The link state machine (`dao.stream.ble.links`)

A seam's judgment is all link bookkeeping, and link bookkeeping is the
same on every host, so it lives host-free: `dao.stream.ble.links` is a
pure state machine over **radio events**, and `seam` is the generic
host-free adapter that composes the machine into the seam contract of
section 3. A host seam (section 8) is then only a translator: plugin
streams in, radio events out; actions out, plugin calls in. The dart
seam holds no policy of its own; a seam cannot drift from the tests
that gate the machine.

Radio events, host-local vectors (not dao.stream values, never
deposited as such): `[:adv peer rssi]`, `[:link-up peer direction]`,
`[:link-down peer]`, `[:link-failed peer]`, `[:frame peer bytes]`,
`[:send-fail peer reason]`, `[:radio-up self]`, `[:radio-down reason]`,
`[:bind-failed reason]`. Directions: `:central` (this node connected
out) and `:peripheral` (the peer connected in).

For each radio event the machine answers
`{:state s' :deposits [events...] :actions [radio calls...]}`:

- `:deposits` are dao.stream values of section 2 the adapter appends to
  the deposit writer in order — the adapter invokes nothing else, the
  machine interprets nothing above itself.
- `:actions` are the radio calls the policy owns: `[:connect peer]` when
  an advertisement names a peer with no link and `:max-links` allows;
  nothing when it does not — an advertisement from a linkless peer past
  the ceiling deposits `peer-seen` and connects to nothing, and the
  composition that wants more links raises `:max-links`. The ceiling
  counts established links and pending dials both, so a burst of
  advertisements from different peers issues at most `:max-links`
  dials; it governs this seam's own dials only — what the OS accepts
  on its side (an inbound `:peripheral` link) always deposits, whatever
  the ceiling says. The connect policy rides the same window as the
  discovery throttle, so the medium's own repetition is the retry
  cadence; a failed attempt is `[:link-failed ...]`, which deposits
  nothing — a never-up link is not a lost one. A `[:link-down ...]`
  for a peer address the machine never saw come up deposits nothing
  either: it is the stale duplicate of a teardown already processed.

The send rule: one frame crosses the **one directional GATT link the
peer address names** — a `[:send-frame peer bytes]` action — or, when no
such link exists at this moment, it is the clean `transport-error` of
section 4 plus a `send-failed` deposit. A send the host reports as
failed **later** arrives as a `[:send-fail ...]` radio event and is the
send-failed channel of section 4; it is never retried here. Duplicate
directional links are a BLE fact — two nodes may each connect out to
the other — and the mesh's dedup, not the seam's, eats the redundancy
(section 7): the machine cannot correlate two opaque peer addresses,
and does not pretend to.

A radio that stops on its own — permissions revoked, bluetooth
switched off, the OS suspending the app — is a `[:radio-down ...]`
event: every live link is lost (`link-down` deposits), the node is
observably gone, and sends answer `transport-error` from there on
until the composition re-binds. `close!` is the composition's own
stop: idempotent, one `closed` deposit followed by a `link-down` for
every live link — the mesh above learns its neighbors from those
events, and a link fact the reader held must not outlive the reader's
evidence for it (section 5) — one `[:stop-radio]` action, and a closed
machine answers every later radio event with nothing.

`seam` takes the bind data of section 3 and a radio map
`{:perform! (fn [action])}` — machine to radio — and answers
`{:send! f :close! f :radio! f :state f}`: the seam contract, one radio
event in, and the machine observed for tests. The simulation of the
test suite is one radio map; the dart seam is another.

## 7. `dao.stream.ble.mesh` — the flooding interpreter

The mesh is to the raw layer what `dao.stream.udp` is to the datagram
layer: a value channel rebuilt on it, host-free, driven by the
composition. One CBOR value, canonical profile (`dao.stream.cbor`), rides
one mesh frame, which rides one raw frame — no fragmentation in S1; an
encoded value over the raw budget is refused at `send!` as the raw
writer's `invalid-value`.

A mesh frame, channel-internal, is a CBOR map keyed under
`:dao.stream.ble.mesh/`:

```clojure
{:dao.stream.ble.mesh/v      1      ; the discriminator this layer owns
 :dao.stream.ble.mesh/origin "<node identity, composition-minted>"
 :dao.stream.ble.mesh/seq    <non-negative integer>
 :dao.stream.ble.mesh/ttl    <non-negative integer>
 :dao.stream.ble.mesh/value  <portable value>}
```

The discriminator follows the shared-medium convention of
`dao.stream.datagram.md` 6: on a radio other interpreters share, the mesh
owns exactly the raw frames whose decoded value is a map carrying
`:dao.stream.ble.mesh/v`, and drops maps carrying no such key; a further
interpreter on the same node declares its own discriminator there. A frame
with the key but malformed fields is foreign the same way — a newer or
broken dialect, not a defect to diagnose on any shared stream.

The mesh value, `make-mesh`, is an atom-backed protocol state, the
`dao.stream.udp` port shape: `{:identity origin :raw-writer w
:raw-traffic ring :raw-cursor c :deposit w2 :ttl n :max-remembered n
:next-seq n :neighbors #{}}`. `:origin` is the node identity frames
carry — a composition-minted string, unauthenticated, whose only office
is loop prevention and correlation; self-certifying identities live in
the payload, above the stream (`dao.stream.md`, Recorded, not proposed).
`:ttl` bounds a send's hops (default 6, three to five hops of margin
around the Bluetooth Mesh default without the thundering herd a large
ttl makes of a dense mesh), `:max-remembered` bounds the dedup cache
(default 4096 `[origin seq]` keys, oldest evicted).

`send!` appends one origin frame: encode the value, wrap it with the
origin, the next `:next-seq`, and the configured `:ttl`, and fan it out
to every current neighbor through the raw writer, answering the raw
writer's first non-`ok` outcome. An unencodable value is refused as
`invalid-value` before the raw writer is touched, nothing was appended.
Every append happens, whatever the one before it answered — a lazy
sequence here would let one stale neighbor silently skip the append to
every neighbor after it, and the loss of a flood is exactly what
flooding's redundancy is not allowed to multiply. `:next-seq` advances
when at least one link accepted the frame — a seq no link accepted was
heard by nobody — and the spend is **remembered**: the origin's own send
occupies the dedup cache exactly as a received frame would, so the copy
a redundant link brings back delivers nothing and relays nothing. A seq
that left the machine is never reused, whatever the air did with the
frame; a seq no link accepted is forgotten by everyone, ourselves
included.

`mesh-step!` is the reading and relaying side, the `port-step!` shape: one
step reading the raw traffic ring from the mesh's own raw cursor to
`blocked`, bounded by a budget (default 64 events), skipping lifecycle
events and foreign frames, decoding each frame event's Base64 and CBOR.
A raw `gap` adopts the recovery cursor and the step continues — those
events are lost, indistinguishable from radio loss, and flooding's
redundancy recovers exactly as it does for network loss. For each owned
frame, in arrival order:

1. **Dedup.** A frame whose `[origin seq]` is already remembered is
   dropped — this is the loop breaker and the redundancy eater, and it is
   bounded: `:max-remembered` `[origin seq]` keys, oldest evicted first.
   There is no clock and no freshness judgment in the cache: a replayed
   old sequence outside the cache window is re-delivered and re-relayed,
   and idempotence of the payload is the payload's, the same rule the
   Writing section of `dao.stream.md` states for retries.
2. **Local delivery.** One event deposited on the mesh's own deposit
   medium (the composition wires an evicting ring, for the datagram
   reasons): `{:dao.stream.ble.mesh/source peer :dao.stream.ble.mesh/origin
   o :dao.stream.ble.mesh/seq n :dao.stream.ble.mesh/value v}`. Readers
   hold their own cursors on it; a `gap` there is mesh values lost, to be
   handled by the reader's loss policy.
3. **Relay.** When the received `ttl` is positive, re-emit the frame with
   `ttl` decremented — clamped to this mesh's own configured `:ttl`, so
   a frame claiming a large budget amplifies only as far as the
   configuration this composition chose — toward **every current
   neighbor except the source peer**. The neighbor set is the raw
   layer's: the mesh reads it from the traffic stream's
   `link-up`/`link-down` lifecycle events as it steps, holding its own
   neighbor table as data. A `closed` from the raw layer clears it —
   the node itself is gone. A `gap` on the raw ring may leave the set
   stale, and stale is the honest choice: a dead neighbor's relays
   answer `send-failed` and cost nothing, a cleared set would cost the
   mesh every relay it has, and a stable link emits no `link-up` to
   refill what a clear emptied. The next `link-down` corrects the
   staleness. There is no route computation — flooding is the whole of
   routing, which section 2's peer-address rule makes honest rather
   than lazy. Relay sends answer non-`ok` to nothing: a failed relay is
   a lost frame, flooding tolerates loss, and the raw traffic stream's
   `send-failed` events are the data a composition that wants to
   observe loss observes. A frame received with `ttl` 0 delivers
   locally and relays nothing.

Foreign frames — not this layer's values — are skipped, never answered,
never diagnosed on any shared stream (`dao.stream.datagram.md` 6).

## 8. The dart seam (`dao.stream.ble/dart`)

The first host seam targets the Flutter app (Android, iOS, macOS). Two
plugins, both pinned exact and both BSD-3:

- **Central role:** `flutter_blue_plus` **1.36.8** — scan, connect,
  discover the mesh service, subscribe to its TX characteristic's
  notifications, write its RX one. The 2.x line changed to a license
  that is not free for for-profit use, and this repository is
  GPL-2.0-only with a linking exception, so the pin is exact and a
  2.x upgrade is an owner decision, not a dependency bump.
- **Peripheral role:** `flutter_ble_peripheral` **3.1.0** — advertise
  the service UUID, serve GATT: receive writes, notify subscribers.
  Android, iOS, macOS and Windows only — no Linux implementation — so a
  Linux host runs the central role alone: it scans, dials, and
  exchanges frames with every advertising peer, but never advertises or
  serves. The seam handles the missing role honestly rather than dying:
  a refused start deposits `bind-failed` as data and the node runs.

The adapter holds no policy: it maps plugin callbacks into radio events,
hands them to `dao.stream.ble.links`, appends the returned deposits in
order, performs the returned actions, and exposes `{:send! f :close! f}`
whose `send!` is the send rule of section 6. Platform facts it must
classify, not hide: the peripheral role's GattWrite carries no central
identity, so that whole role is one logical link at one synthetic peer
address (`peripheral-peer`) — the mesh's dedup eats the copies, and
   the relay rule's exclusion of the source has a hub consequence a
   composition must know: a frame that arrived through the peripheral
   role is never relayed back out through it, so a node whose neighbors
   are all centrals of its own GATT server does not route between them;
iOS cannot advertise manufacturer data, so the advertisement carries the
service UUID alone and every in-band identity travels in frames;
background restrictions suspend advertising and scanning, so a
suspended node loses its links and binds again only when the
composition re-binds. Every plugin future is caught: a platform refusal
the composition should see as data (`bind-failed`, `send-fail`,
`radio-down`, `link-failed`) is deposited or fed to the machine, never
thrown across the seam. Permissions are the app's: Android needs
`BLUETOOTH_SCAN` (with `neverForLocation` — the mesh profile scans for
its own service UUID, never for beacon-derived location),
`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`; iOS needs
`NSBluetoothAlwaysUsageDescription`; a sandboxed macOS app needs
`com.apple.security.device.bluetooth` in both entitlements files plus
the same usage string in `macos/Runner/Info.plist`.

The seam is explicitly local and non-migratable (`datom.world.md`, Host
Boundaries): it is the one piece of this stack that cannot run on a host
with no radio, and a composition on such a host composes the same
portable layers over a different seam.

**The device-test plan.** The sandbox cannot reach a plugin call, so
these facts are proven on real radios, in this order: (1) two devices
bind — each traffic stream shows `bound`, then `peer-seen` per peer,
then `link-up` in both roles; (2) a mesh send from one device delivers
once on the other, over however many links carried it; (3) a third
device out of range of the first receives through the middle one — the
relay; (4) bluetooth switched off mid-run deposits `closed` and
`link-down` for every peer, and sends answer `transport-error`; (5)
airplane-mode recovery re-binds and re-learns; (6) an Android peripheral
and an iOS central exchange frames — the profile's cross-platform half.

## 9. Exposure

A raw writer handle entered in a mirror table
(`dao.stream.remote.md` 2) lets whoever reaches it send arbitrary frames
from this radio. No composition does so without a gate on that entry.

## 10. What S2 holds

- **Fragmentation**: the udp fragment envelope over mesh frames, so one
  value may exceed one raw frame's budget, with the two reassembly bounds
  and their eviction rules.
- **`dao.stream.remote` over the mesh**: mirror, reflection and link
  composed over the mesh value medium, which is what makes a `yin.repl`
  session survive the loss of every IP route.
- **Gateway nodes**: a composition with both a radio seam and an IP seam
  forwarding between the mesh medium and a `dao.stream.datagram` medium —
  an ordinary forwarder interpreter, no new machinery.
- **Gossip reduction**: S1 floods every frame to every neighbor; a
  probability- or frontier-based relay policy is an interpreter change,
  not a wire change, because the wire carries only origin, seq, ttl,
  value.
