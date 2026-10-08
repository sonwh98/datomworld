Coding-Agent: agy
Session-ID: c1020f8c-3056-4129-a680-cdc1337b844a
Model: gemini-3.1-pro-high

Completed-GMT: 2026-09-30 09:06:07 GMT
Completed-Local: 2026-09-30 16:06:07 +0700

**R1. The raw datagram layer:**
- **Namespace**: `dao.stream.datagram`. We split the raw socket host seams out of `dao.stream.udp` (`udp.cljc:264`, `udp.cljc:225`) and rebuild `dao.stream.udp`’s value/fragment layer on top of it. *(Changes consensus D1: isolates the raw layer)*.
- **dao.stream surface**: A `(create-port)` returns a reader handle and a writer handle. The reader yields `{:dao.stream.datagram/bytes b, :dao.stream.datagram/source source-addr}` events. Bytes are portably represented as canonical byte arrays (`byte-array` / `Uint8Array` / `Uint8List`).
- **Sending**: Appending to the writer handle expects `{:dao.stream.datagram/bytes b, :dao.stream.datagram/destination dest}`.
- **Bounds**: `max-datagram` is strictly enforced (e.g., 1200 B). If a payload exceeds this, `append!` returns `{:dao.stream/outcome :dao.stream/invalid-value}`.
- **Loss/Retention**: The reader is a zero-retention traffic stream (drops on lag); `append!` is best-effort and returns `:dao.stream/blocked` if the host's underlying socket buffer is full.
- **Host Seams**: `DatagramSocket` (CLJ), `dgram` (CLJS), `RawDatagramSocket` (CLJD) are fully isolated inside this layer. `dao.stream.udp` and `dao.stream.remote` continue working seamlessly by taking `dao.stream.datagram` ports as dependencies.

**R2. The DHT on raw datagrams:**
- **Wire/Codec**: Canonical CBOR. Kademlia RPCs (`ping`, `find-node`) are mapped to small CBOR arrays/maps fitting in one 1200 B datagram. *(Changes consensus D2: confirms CBOR, but re-frames directly on raw datagrams)*.
- **Chunking**: Because the DHT sits directly on the RAW layer, it CANNOT reuse `dao.stream.udp`'s fragment envelope (`udp.cljc:151`). The DHT MUST implement its own DRDS chunking logic for transferring segment payloads > 1200 B. *(Changes consensus D1/D5)*.
- **Hardening**: Returnability cookies, token-bucket rate limits, and amplification bounds are implemented by the DHT at its raw datagram processing step, dropping abusive `{:dao.stream.datagram/source ...}` events before they trigger DHT logic or fragmentation reassembly.

**R3. The DHT's dao.stream-shaped public API:**
- **Streams**: The DHT exposes a `request-stream` (for operations) and a `result-stream` (for outcomes).
- **Value Shapes**: `append!` to the request stream takes `{:dao.jing.dht/op :put, :dao.jing/address addr}` or `{:dao.jing.dht/op :get, :dao.jing/address addr}`. The result stream yields correlated `{:dao.jing/address addr, :dao.jing.dht/status :inserted}` or `{:dao.jing/address addr, :dao.jing/bytes bytes}` outcomes.
- **Adapter Contract**: A `create-store-adapter` wraps these streams to satisfy `dao.jing`'s existing contract (`put-bytes-fn`, `get-bytes-fn`, `dht.cljc:255-278`). The adapter appends to the request stream and spin-polls/blocks on the result stream to return the synchronous values expected by `yin.repl.index` and `dao.jing.content`.
- **Invariants**: The DHT has no hidden threads. It is explicit state advanced by a `(step! dht-state)` function pumped by the host event loop, fulfilling the caller-stepped invariant.

**R4. Revised slice plan:**
- **S0 (Datagram Layer)**: Extract host seams into `dao.stream.datagram` and refactor `dao.stream.udp` to sit on it. *AC*: existing `dao.stream.udp` tests pass unchanged.
- **S1 (DHT Stream API & Local)**: Rewrite `dao.jing.dht` to expose the request/result stream API, implement the `dao.jing` store adapter, and define solo-net local mode.
- **S2 (DHT Raw Wire & Chunking)**: Implement the CBOR wire protocol, DHT-owned chunking, and raw datagram port integration. *AC*: >1200B `put`/`get` succeeds across two raw peers.
- **S3 (Hardening)**: Add returnability cookies and rate limits to the DHT raw datagram processing loop.
- **S4 (yin.repl Integration)**: Wire `--dht-publish` flag and `:dht` spec (dependent on the durable-store epic).
*Concurrency*: S0 blocks S2. S1 blocks S2. S0 and S1 can run concurrently. S2 blocks S3. S4 waits on durable-store.

**R5. Items for the OWNER:**
- By moving `get-bytes-fn` to an adapter over an async stream, the adapter must synchronously block (or spin-poll) to satisfy the legacy interface, unless `yin.repl` and `dao.jing.content` are also refactored to consume the async `result-stream` directly. Does the OWNER accept the synchronous adapter bridge, or require a cascading refactor of `dao.jing` to async streams?

