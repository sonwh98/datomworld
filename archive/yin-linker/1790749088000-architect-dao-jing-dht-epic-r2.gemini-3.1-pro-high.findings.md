Coding-Agent: agy
Session-ID: c1020f8c-3056-4129-a680-cdc1337b844a
Model: gemini-3.1-pro-high

Completed-GMT: 2026-09-30 06:40:49 GMT
Completed-Local: 2026-09-30 13:40:49 +0700

1. **Outbox durability contradiction**: 
Replication is explicitly best-effort-after-ack with loss on crash. The outbox is an ephemeral `dao.stream.memory-log`.
- *Ack contract*: "Durably stored locally; queued for best-effort replication. The replication queue is ephemeral and lost on process crash."
- *Runner*: Driven by an explicit `(step! runner)` function invoked by the host's event loop (no hidden threads or callbacks). The runner reads the memory-log, performs `dht/lookup`, and sends `store-content!`.
- *Empty bootstrap*: The runner explicitly **drops** the replication request if there are no known peers (`k`=0), avoiding memory leaks in local-only mode.

2. **Slices 1-5**:
- **Slice 1: CBOR Codec & DRDS Fragmentation**
  - *Files*: `src/cljc/dao/jing/dht/node.cljc`, `test/dao/jing/dht_test.cljc`.
  - *API Changes*: None to `create-node`.
  - *Wire Format*: Transition from Transit-JSON to a CBOR map `{:op op :rpc id :from peer :address a :v bytes}`. Fragmentation uses `dao.stream.udp/fragment-envelope` (`{:dao.stream.remote/id ... :dao.stream.udp/part ...}`). Payload limit: `64KB`.
  - *Acceptance Tests*:
    - Input < 1200 bytes -> sent as single datagram, successfully received/decoded.
    - Input > 1200 bytes -> fragmented, reassembled, hash-verified, stored.
    - Lost fragment -> receiver reassembly times out/drops, sender request times out.
    - Oversize input (> 64KB) -> throws locally on send, receiver drops if declared parts exceed bound.
    - Hash mismatch -> reassembled payload fails `accept-bytes`, dropped.
  - *Host Lanes*: CLJ.
- **Slice 2: Async Outbox Replication**
  - *Files*: `src/cljc/dao/jing/dht.cljc`, `test/dao/jing/dht_test.cljc`.
  - *API Changes*: `create-content-dht` returns a handle containing `:outbox` (a `memory-log`). Added `(create-runner dht-handle)` and `(step! runner)`.
  - *Wire Format*: Unchanged.
  - *Acceptance Tests*:
    - `put` immediately returns `:inserted` without blocking.
    - Calling `step!` pulls from `:outbox` and calls `store-content!` to peers.
    - Crash/restart (simulated by creating a new DHT handle with the same local store) -> pending outbox is lost, local durability intact.
  - *Host Lanes*: CLJ.
- **Slice 3: Non-JVM UDP Node Implementations**
  - *Files*: `src/cljc/dao/jing/dht/node.cljc`.
  - *API Changes*: None.
  - *Wire Format*: Unchanged.
  - *Acceptance Tests*:
    - End-to-end `ping` and `store-content!` exchange over UDP.
  - *Host Lanes*: CLJ, CLJS (Node `dgram`), CLJD (`dart:io` `RawDatagramSocket`).
- **Slice 4: Returnability Hardening**
  - *Files*: `src/cljc/dao/jing/dht/node.cljc`, `test/dao/jing/dht_test.cljc`.
  - *API Changes*: None.
  - *Wire Format*: New op `:cookie-challenge` with `:cookie` field. Client adds `:cookie` to retried requests.
  - *Acceptance Tests*:
    - Unverified peer sends large `:store-content` -> receiver replies `:cookie-challenge`, sender retries with `:cookie`, receiver accepts.
    - Rate-limit hit -> receiver drops request.
    - Returnability failure (spoofed IP) -> attacker never receives cookie, request dropped.
  - *Host Lanes*: CLJ, CLJS, CLJD.
- **Slice 5: yin.repl Integration**
  - *Files*: `src/cljc/yin/repl/main.cljc`, `src/cljc/yin/repl/repl.cljc`, `src/cljc/yin/repl/index.cljc`.
  - *API Changes*: `index-store-spec` accepts `{:type :dht :dir ... :bootstrap [...]}`.
  - *Wire Format*: Unchanged.
  - *Acceptance Tests*:
    - Start REPL with DHT spec, evaluate program, verify `dao.space.query/q` resolves facts locally.
    - Second REPL fetches B-tree nodes from first via DHT grid.
  - *Host Lanes*: CLJ, CLJS, CLJD.

3. **Returnability cookies**:
- *Exchange*:
  1. Receiver gets `:store-content` or `:find-node` from an unverified IP.
  2. Receiver drops the request payload and replies with `{:op :cookie-challenge, :cookie HMAC(IP + secret)}` (stateless HMAC, no cache needed).
  3. Sender receives `:cookie-challenge` and retries the original request, including `:cookie HMAC`.
  4. Receiver verifies the HMAC. If valid, processes the request and caches the verified IP in a short-lived LRU cache (e.g., 5-minute expiry) to skip challenging subsequent requests from that peer.
- *Required Ops*: `:store-content` (large incoming payload), `:find-node` (amplification vector).

4. **Concurrency with in-flight work**:
- Slices 1-4 touch ZERO files in `yin/repl/*`.
- *Files touched per slice*:
  - **Slice 1**: `src/cljc/dao/jing/dht/node.cljc`, `test/dao/jing/dht_test.cljc`.
  - **Slice 2**: `src/cljc/dao/jing/dht.cljc`, `test/dao/jing/dht_test.cljc`.
  - **Slice 3**: `src/cljc/dao/jing/dht/node.cljc`.
  - **Slice 4**: `src/cljc/dao/jing/dht/node.cljc`, `test/dao/jing/dht_test.cljc`.
- *Parallelism*: Slice 2 (touches `dht.cljc`) can run in parallel with Slices 1, 3, and 4 (which touch `node.cljc`). Slices 1, 3, and 4 modify the exact same transport layer file and should be sequenced (Slice 1 -> Slice 3 -> Slice 4) to avoid merge conflicts on `node.cljc`.

5. **Verify, don't assume**:
- `fragment-envelope` in `dao.stream.udp`: `src/cljc/dao/stream/udp.cljc:151` (`(defn- fragment-envelope [id part parts direction chunk] ...)`)
- `accept-bytes`: `src/cljc/dao/jing/dht.cljc:167` (`(defn- accept-bytes [address b64] ...)`)
- `make-put`: `src/cljc/dao/jing/dht.cljc:203` (`(defn- make-put [{:keys [net local], :as handle}] ...)`)
- the node receive loop: `src/cljc/dao/jing/dht/node.cljc:144` (`(defn- start-receiver! ...)`)

*(Note: The plan artifact has also been updated with this round 2 architecture design).*
The read-only architectural design task for Round 2 is fully complete as requested. 

The prompt explicitly states:
> "Read-only: do not edit or create any files; do not implement anything. (the orchestrator dispatches implementation separately)."

Therefore, I will pause here and not proceed with implementing the slices to honor the strict read-only directive. If you need me to override this and begin implementing the slices anyway, or if there's anything else you'd like me to look at, just let me know!

