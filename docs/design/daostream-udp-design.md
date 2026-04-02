# DaoStream UDP Transport Design

## Overview

This document defines a UDP-based transport for DaoStream, enabling datom streams over unreliable, connectionless network links. The design follows the principle that **a datom is a natural UDP datagram**: each datom `[e a v t m]` is a self‑contained, immutable fact that maps directly to a UDP payload. The transport adds a minimal reliability layer (DRDS—Datom Reliable Datagram Streams) that is **optional and interpreter‑directed**, preserving the axiom that semantics are external.

The UDP transport integrates with the existing DaoStream descriptor/realization model:
- Descriptor: `{:transport {:type :udp …}}`
- Realization: `open!` multimethod dispatches on `:type :udp`
- Transport implementation: `UdpTransport` satisfying `IDaoStreamReader`, `IDaoStreamWriter`, `IDaoStreamBound`
- Waitability: Not `IDaoStreamWaitable` (network‑transparent waiters are impractical); fallback to `check‑wait‑set` polling.

## Core Philosophy

1. **No hidden semantics**: UDP provides raw packet delivery; reliability, ordering, and causality are interpreter‑directed.
2. **Datom as datagram**: The five‑tuple `[e a v t m]` is the atomic unit of transmission; the transport never inspects its content.
3. **Channel mobility**: Descriptors are first‑class values that can be sent over streams and realized on any node with UDP connectivity.
4. **Interpreter‑directed reliability**: The transport can be configured for fire‑and‑forget, at‑least‑once, or exactly‑once delivery, as required by the stream’s interpreter.

## Descriptor Schema

A UDP stream descriptor follows the DaoStream descriptor pattern with a `:transport` section of type `:udp`.

### Basic UDP Descriptor

```clojure
{:transport {:type :udp
             :mode :create          ;; or :attach (future)
             :host "192.168.1.100"  ;; target address for writes
             :port 9000             ;; target port for writes
             :listen-port 9000      ;; local port to bind (optional, defaults to :port)
             :mtu 1450              ;; maximum transmission unit (optional, default 1450)
             :reliable? false       ;; enable DRDS layer (optional, default false)
             :ack-timeout-ms 2000   ;; DRDS ACK timeout (optional)
             :max-retries 5}}       ;; DRDS retry limit (optional)
```

**Fields**:

- `:type` – must be `:udp`.
- `:mode` – `:create` (initiate a new stream) or `:attach` (attach to an existing stream). Currently only `:create` is required.
- `:host` – IP address or hostname of the remote endpoint where datagrams are sent.
- `:port` – remote port for writes.
- `:listen-port` – local port to bind for receiving datagrams. If omitted, defaults to `:port` (same as remote). Binding to port 0 lets the OS choose an ephemeral port.
- `:mtu` – maximum datagram size in bytes. Defaults to 1450 (Ethernet MTU minus IPv4/UDP headers). Values larger than the path MTU cause fragmentation; the transport may optionally fragment/defragment.
- `:reliable?` – if `true`, enables the DRDS reliability layer (sequence numbers, ACKs, retransmission). Default `false`.
- `:ack-timeout-ms` – DRDS ACK wait timeout in milliseconds. Used when `:reliable?` is `true`.
- `:max-retries` – DRDS maximum retransmission attempts before giving up and signaling a gap.

### DRDS‑Enabled Descriptor

When `:reliable? true`, the descriptor may include additional DRDS‑specific options:

```clojure
{:transport {:type :udp
             :mode :create
             :host "10.0.0.2"
             :port 9000
             :reliable? true
             :drds {:stream-id 0x12345678   ;; 32‑bit stream identifier
                    :window-size 64         ;; sliding‑window size in datagrams
                    :fec? false             ;; forward error correction (future)
                    :encryption :none}}}    ;; :none, :noise, :dtls (future)
```

**DRDS fields**:

- `:stream-id` – 32‑bit numeric identifier for this substream. Used to multiplex multiple logical streams over a single UDP socket.
- `:window-size` – number of un‑ACKed datagrams allowed in flight.
- `:fec?` – whether to add forward‑error‑correction parity datagrams.
- `:encryption` – encryption scheme. Initially `:none`; later `:noise` (Noise Protocol) or `:dtls`.

## Transport Realization

The `open!` multimethod dispatches on `:type :udp` and returns an instance of `UdpTransport`.

### `open!` Implementation

```clojure
(defmethod dao.stream/open! :udp
  [descriptor]
  (let [{:keys [host port listen-port mtu reliable?]} (:transport descriptor)
        socket (java.net.DatagramSocket. (or listen-port port))]  ;; Clojure JVM
    (->UdpTransport socket host port mtu reliable? descriptor)))
```

**Platform notes**:

- **Clojure (JVM)**: `java.net.DatagramSocket`. Use `InetAddress.getByName` to resolve hostnames (supports IPv4 and IPv6). The socket can be bound to a local address that supports both families if the OS permits.
- **ClojureScript (browser)**: `WebSocket` or `WebRTC` (UDP not directly available; fallback to WebSocket transport)
- **ClojureScript (Node.js)**: `dgram` module (supports IPv4 and IPv6 via `'udp4'` or `'udp6'`).
- **jank**: use platform UDP APIs.

The transport must be generic; platform‑specific code is isolated behind a socket abstraction.

## UDP Transport Protocol Implementation

`UdpTransport` is a record that satisfies `IDaoStreamReader`, `IDaoStreamWriter`, `IDaoStreamBound`. It does **not** implement `IDaoStreamWaitable` because waiter registration across a network is impractical; blocked readers/writers fall back to the scheduler’s `check‑wait‑set` polling.

### Internal State

```clojure
(defrecord UdpTransport [socket
                         remote-addr
                         remote-port
                         mtu
                         reliable?
                         descriptor
                         ^java.util.concurrent.ConcurrentHashMap buffer
                         ^AtomicLong tail
                         ^AtomicLong head
                         ^AtomicBoolean closed])
```

**Fields**:

- `socket` – underlying UDP socket.
- `remote-addr` – `java.net.InetAddress` of the remote host.
- `remote-port` – remote port.
- `mtu` – maximum datagram size.
- `reliable?` – whether DRDS is active.
- `descriptor` – original descriptor (for serialization).
- `buffer` – `ConcurrentHashMap` mapping absolute position `long` → `{:seq number :data bytes}` (DRDS) or raw value (non‑DRDS).
- `tail` – `AtomicLong` indicating next write position.
- `head` – `AtomicLong` indicating oldest retained position (for gap detection).
- `closed` – `AtomicBoolean` for stream closure.

**DRDS‑specific state** (when `reliable? true`):

```clojure
(defrecord DrdsState [^long stream-id
                      ^ConcurrentHashMap pending-acks  ;; seq‑number → {position, sent-time, retry-count}
                      ^PriorityQueue ack-queue         ;; ordered by seq‑number for ACK generation
                      ^long next-send-seq
                      ^long next-expect-seq
                      ^int window-size])
```

### Reader Protocol (`IDaoStreamReader`)

`(next [this cursor])`

**Cursor shape**: `{:position n}` (same as ringbuffer).

**Behavior**:

1. If `position < @head` → return `:daostream/gap` (value evicted).
2. If `position >= @tail` and `@closed` → return `:end`.
3. If `position >= @tail` and not closed → return `:blocked`.
4. Otherwise, look up `position` in `buffer`:
   - Non‑DRDS: retrieve value, return `{:ok value :cursor {:position (inc position)}}`.
   - DRDS: retrieve `{:seq s :data bytes}`, decode bytes to value, return same.

**Decoding**: The transport must decode the UDP payload back into a Clojure value. For datoms, use Transit or EDN encoding. The default codec is Transit because it handles binary data efficiently.

**Gap detection**: If `reliable? false`, lost datagrams cause permanent gaps; `next` returns `:daostream/gap`. If `reliable? true`, the DRDS layer will retransmit missing datagrams up to `:max‑retries` before signaling a gap.

### Writer Protocol (`IDaoStreamWriter`)

`(put! [this value])`

**Encoding**: Serialize `value` to bytes using Transit. If the encoded size exceeds `mtu`, apply fragmentation (see **Fragmentation** below).

**Non‑DRDS flow**:
1. Check `@closed`; if closed, throw.
2. Encode value → `bytes`.
3. Create UDP datagram: `bytes` as payload.
4. Send via `socket.send(datagram)`.
5. Store `value` in `buffer` at position `@tail` (for local readers).
6. Increment `tail`.
7. Return `:ok`.

**DRDS flow**:
1. Assign sequence number `seq = next‑send‑seq`.
2. Build DRDS header: `stream‑id (4 bytes) | seq (4 bytes) | flags (1 byte) | fragment‑id (optional)`.
3. Append encoded payload.
4. Send datagram.
5. Store `{:seq seq :data bytes}` in `buffer` at position `@tail`.
6. Add to `pending‑acks` map with timestamp.
7. Increment `tail` and `next‑send‑seq`.
8. Return `:ok`.

If the send fails (socket error), throw an exception.

**Backpressure**: UDP has no inherent backpressure. The transport may optionally implement a bounded send queue; when full, `put!` returns `:full` and the writer parks (via scheduler). The descriptor could include a `:send‑queue‑size` limit.

### Bounding Protocol (`IDaoStreamBound`)

- `(close! [this])` – set `closed` to `true`. If DRDS active, send a final DRDS “close” datagram (optional).
- `(closed? [this])` – return `@closed`.

### Lifecycle

- On `close!`, the socket remains open (may be reused for other streams). A separate `shutdown!` method could close the socket, but the descriptor model does not require it.
- The transport should be garbage‑collectable; the socket can be closed via a finalizer or explicit `shutdown!`.

## DRDS – Datom Reliable Datagram Streams

DRDS is an optional reliability layer that runs inside the UDP transport. It provides:

- **Sequence numbers** per substream (not global).
- **Selective ACK** vectors.
- **Retransmission** with exponential backoff.
- **Sliding‑window** flow control.
- **Fragmentation/reassembly** of large datoms.

### Packet Format

A DRDS datagram has the following binary layout (big‑endian):

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         stream‑id (32)                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     sequence‑number (32)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| flags (8)     |   fragment‑id (16)        | payload‑len (16)  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        payload (variable)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**Fields**:

- `stream‑id` (32 bits): 32‑bit identifier for the logical substream.
- `sequence‑number` (32 bits): monotonic within this stream.
- `flags` (8 bits):
  - `0x01` = ACK requested
  - `0x02` = ACK packet (payload empty)
  - `0x04` = fragment
  - `0x08` = final fragment
  - `0x10` = reserved
  - `0x20` = reserved
  - `0x40` = reserved
  - `0x80` = reserved
- `fragment‑id` (16 bits): if `fragment` flag set, identifies fragment number (0‑based).
- `payload‑len` (16 bits): length of payload in bytes (max 65535).
- `payload`: serialized value (Transit).

**ACK packets**: When `ACK packet` flag is set, the payload is replaced by an ACK vector (variable length, bitmask of received sequences). The ACK vector can be compressed (run‑length encoding).

### Reliability Algorithm

**Sender**:

1. Assign sequence number `seq` to each outgoing datagram.
2. Store in `pending‑acks` with timestamp and retry‑count = 0.
3. Start retransmission timer (`ack‑timeout‑ms`).
4. On ACK receipt, remove `seq` from `pending‑acks`.
5. If timer expires, retransmit datagram, increment retry‑count, double timeout (exponential backoff).
6. If retry‑count > `max‑retries`, remove from `pending‑acks` and signal gap for the corresponding stream position.

**Receiver**:

1. Receive datagram, validate stream‑id.
2. If `seq` is expected (`next‑expect‑seq`), deliver payload to buffer, increment `next‑expect‑seq`.
3. If `seq` is out‑of‑order, buffer it until missing predecessors arrive (optional).
4. If `ACK requested` flag set, send ACK packet containing bitmask of received sequences up to `seq`.

**Sliding window**: Sender may have at most `window‑size` un‑ACKed datagrams in flight.

### Sequence Number Wrap‑Around

Sequence numbers are 32‑bit and wrap after 2³²‑1. The DRDS layer must handle wrap‑around correctly:

- **Sender**: When `next‑send‑seq` reaches `0xFFFFFFFF`, the next sequence is `0`. The sliding window must allow wrap‑around (compare using modular arithmetic).
- **Receiver**: Maintain `next‑expect‑seq` as a 32‑bit modulo value. When receiving `seq`, compute forward distance using `(seq - next‑expect‑seq) mod 2³²`. If distance < window‑size, accept.
- **ACK vectors**: ACK bitmask covers a window of 64 sequences (configurable). The receiver sends periodic ACKs to keep the window advancing.

Wrap‑around is rare (at 1 Mpps, wrap‑around every ~1 hour). For typical datom streams, wrap‑around is negligible, but the implementation must be correct.

### Fragmentation

When a serialized value exceeds `mtu - header‑size`, the DRDS layer fragments it:

1. Split payload into `fragment‑size = mtu - header‑size - fragment‑overhead`.
2. Send each fragment with same `seq` but different `fragment‑id`.
3. Set `fragment` flag on all fragments; set `final‑fragment` on last.
4. Receiver reassembles fragments using `(stream‑id, seq)` as key; deliver only when all fragments arrive.
5. If a fragment is missing after retransmission limit, signal gap for the whole datom.
## Packet Encoding of Values

The transport must serialize Clojure values to bytes for transmission. The default codec is **Transit** (in msgpack format) because it handles arbitrary Clojure data, including datoms, with good performance and binary support. The encoding is agnostic to the transport; the UDP layer treats the payload as opaque bytes.

**Encoding steps**:
1. `value` → Transit/write (output stream) → `byte[]`
2. If `byte[].length > mtu - header‑size` → fragment (DRDS) or error (non‑DRDS).
3. Prefix with a 1‑byte version flag (0 = Transit, 1 = EDN, 2 = raw) for future extensibility.

**Decoding**: When receiving, read version byte, decode accordingly.

**Datom‑specific optimization**: For streams known to carry only datoms, a custom binary encoding of `[e a v t m]` can be used (fixed‑size integers for e, t, m; variable‑length bytes for a, v). This is an interpreter‑level optimization; the transport stays generic.

## Receiver Thread

UDP sockets are blocking on receive. To provide non‑blocking `next` semantics, each `UdpTransport` must run a **background receiver thread** (JVM) or event‑driven callback (Node.js) that continuously reads datagrams and inserts them into the buffer.

**Algorithm**:
```
while not closed:
    datagram = socket.receive()
    if DRDS:
        parse header, deliver to DRDS layer
        DRDS processes ACKs, retransmissions, reassembly
        when a complete value is ready, store in buffer at position = tail.getAndIncrement()
    else:
        decode payload to value
        store in buffer at position = tail.getAndIncrement()
```

**Thread safety**: Buffer updates must be atomic (`ConcurrentHashMap`). The `tail` counter is an `AtomicLong`. Multiple receiver threads (one per socket) are safe.

**Platform variants**:
- **JVM**: `Thread` with `DatagramSocket.receive()`.
- **Node.js**: `dgram.createSocket('udp4')` with `'message'` event.
- **Browser**: Not applicable; fallback to WebSocket transport.

## Buffer Management and Capacity

The UDP transport’s buffer can be bounded by the descriptor’s `:capacity` field (same as ringbuffer). If `:capacity` is `nil`, the buffer grows indefinitely (subject to memory). If `:capacity` is an integer, the buffer evicts the oldest entries when `tail - head >= capacity`.

**Eviction policy**:
1. When a new value is stored and the buffer is at capacity, increment `head` by `(tail - head) - capacity + 1`.
2. Remove evicted entries from the `buffer` map.
3. Subsequent `next` calls with `position < head` will return `:daostream/gap`.

**Impact on readers**: Slow readers that fall behind the retention window will experience gaps. This is acceptable for real‑time streams where latency matters more than completeness.

**Capacity descriptor example**:
```clojure
{:transport {:type :udp, :host "...", :port 9000, :capacity 1000}}
```

## Security Considerations

UDP is susceptible to spoofing, flooding, and eavesdropping. The following mitigations are available:

1. **Encryption**: DRDS layer can integrate Noise Protocol or DTLS. The `:encryption` descriptor field selects the scheme.
2. **Authentication**: Stream descriptors may carry Shibi capability tokens in `:shibi`. The `open!` realization validates tokens before creating the transport.
3. **Rate limiting**: The transport can limit packets per second based on source address (optional).
4. **Network isolation**: Run UDP streams over WireGuard tunnels (as described in the blog post “Why TCP Is Too Semantic for Datom.world”). This delegates security to a proven VPN layer.

**Recommendation**: For production use over untrusted networks, layer DaoStream UDP over WireGuard or enable DRDS with Noise encryption.

## Implementation Checklist

For an LLM implementing this design, follow these steps:

1. **Create socket abstraction** (`IUdpSocket`) for JVM, Node.js, browser (fallback).
2. **Implement `UdpTransport` record** with `IDaoStreamReader`, `IDaoStreamWriter`, `IDaoStreamBound`.
3. **Implement background receiver** thread/event loop.
4. **Add DRDS layer** (separate namespace) with sequence numbers, ACKs, retransmission timers, fragmentation.
5. **Integrate with `open!`** by adding `defmethod` for `:udp`.
6. **Write unit tests** for encoding, fragmentation, DRDS header parsing.
7. **Write integration test** over loopback UDP, with and without packet loss simulation.
8. **Test with dao.stream.apply** using UDP streams for request‑response.
9. **Document descriptor options** and platform limitations.

## Gap Handling

Gaps occur when:
- Non‑DRDS: UDP datagram lost.
- DRDS: retransmission limit exceeded.
- Buffer eviction: `head` advances past cursor position (bounded retention).

**Gap policy**: The transport returns `:daostream/gap`. The interpreter decides what to do: skip, wait, or abort. The scheduler’s `check‑wait‑set` will keep the waiter in the wait‑set, retrying `next` each idle cycle. If the gap is permanent, the waiter stays blocked forever; a timeout must be applied at the application layer (e.g., `:dao.stream.apply/call‑timeout`).

## Integration with dao.stream.apply

dao.stream.apply uses two streams: `call‑in` (requests) and `call‑out` (responses). Both can be UDP streams.

**Example VM creation with UDP**:

```clojure
(create-vm
  {:call-in  {:transport {:type :udp
                          :host "10.0.0.2" :port 9000
                          :reliable? true}}
   :call-out {:transport {:type :udp
                          :host "10.0.0.2" :port 9001
                          :reliable? true}}})
```

**Bridge side**:

1. Create UDP streams with same descriptors (swap `:host`/`:port` as needed).
2. Loop: `next` on `call‑in`, process request, `put!` response to `call‑out`.
3. DRDS ensures reliable delivery; lost packets are retransmitted.

Because UDP is not `IDaoStreamWaitable`, the VM relies on `check‑wait‑set` polling. This adds latency but works across network partitions.

## Implementation Steps

### 1. Define Namespace and Records

Create `src/cljc/dao/stream/udp.cljc` (platform‑agnostic) and `src/clj/dao/stream/udp_jvm.clj` (JVM‑specific socket code). Use `^:conditional` reader tags for multi‑platform support.

### 2. Implement Socket Abstraction

```clojure
(defprotocol IUdpSocket
  (send! [this datagram remote-addr remote-port])
  (receive! [this] "Blocking receive; returns {:data bytes, :addr InetAddress, :port int}")
  (close! [this]))

;; JVM implementation
(defrecord JvmUdpSocket [^DatagramSocket socket]
  IUdpSocket
  (send! [this datagram remote-addr remote-port]
    (.send socket (DatagramPacket. datagram (alength datagram) remote-addr remote-port)))
  (receive! [this] ...)
  (close! [this] (.close socket)))
```

### 3. Implement `UdpTransport` Record

Satisfy `IDaoStreamReader`, `IDaoStreamWriter`, `IDaoStreamBound`. Store buffer as `java.util.concurrent.ConcurrentHashMap` (JVM) or Clojure atom (CLJS).

### 4. Implement DRDS Layer

Separate namespace `dao.stream.drds` with functions `wrap‑drds` that takes a raw UDP socket and returns a DRDS‑enhanced socket. The DRDS layer manages sequencing, ACKs, retransmission timers (using `java.util.concurrent.ScheduledExecutorService` or JS `setInterval`).

### 5. Register `open!` Method

In `dao.stream` namespace, add `defmethod` for `:udp`.

### 6. Test with Loopback

Create unit tests that send datoms over localhost UDP, verify loss‑tolerant and reliable modes.

## Platform Considerations

### JVM

- Use `DatagramSocket`, `ConcurrentHashMap`, `AtomicLong`.
- Threading: one receiver thread per socket that dispatches to buffer.
- Timers: `ScheduledThreadPoolExecutor` for retransmission.

### ClojureScript (Browser)

- UDP not available; fallback to WebSocket transport.
- Could use WebRTC data channels (UDP‑like). Implementation deferred.

### ClojureScript (Node.js)

- Use `dgram` module.
- Similar threading model as JVM.

### jank

- Use platform UDP APIs; buffer with persistent maps.

## Testing Plan

1. **Unit tests** for encoding/decoding, fragmentation, DRDS header parsing.
2. **Integration test**: two `UdpTransport` instances on loopback, exchange datoms.
3. **Loss simulation**: inject packet loss using a proxy socket; verify DRDS retransmission.
4. **dao.stream.apply integration**: VM ↔ bridge over UDP, verify request‑response.

## Deferred Features

- `:attach` mode (discovering an existing stream).
- Encryption (Noise Protocol, DTLS).
- Forward error correction.
- Multicast / broadcast streams.
- NAT traversal (STUN / ICE).
- Congestion control (LEDBAT‑like).
- Stream multiplexing (multiple DRDS streams over one socket).

## References

- [Why TCP Is Too Semantic for Datom.world](../public/chp/blog/why-udp-not-tcp.blog) – philosophical rationale.
- [DaoStream Design](./daostream-design.md) – base transport model.
- [dao.stream.apply Design](./daocall-design.md) – async request‑response over streams.

---

*This design provides enough detail for an LLM to implement a UDP transport for DaoStream. The key is to keep the transport dumb (no semantics) and let interpreters decide reliability needs.*