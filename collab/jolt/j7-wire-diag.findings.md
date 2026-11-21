Created-GMT: 2026-11-16 (dispatch date)
Coding-Agent: codex
Role: architect (diagnostic)
Branch: fix/j7-udp-dht
Inspected-base: 216894b0ab911489cffffd6771ff047ba3455d95
Local-master: 212c45dd96e3120f9525085f921142e3a42940f8

# J7 slice 3: JVM/jolt DHT wire diagnosis

## Finding and evidence limits

**No packet-shape divergence is established in the inspected source.** The
production DHT encoder/raw-writer path emits identical initial packets on JVM
and jolt in an offline probe. Nine representative protocol frames have identical
bytes and decode successfully in both directions. The next decisive measurement
is the first padded `:ping`, its cookie reply, and their actual UDP endpoints;
looking only for `:fetch` starts too late.

The reported symptom is consistent with delivery failure, an unstepped receive
path, a dropped handshake, or a different deployed composition/codec. It does
not identify which. `::exhausted` also follows immediate send refusals: `issue`
does not record a refused send, `issue-lookup` marks that candidate dead, and
`advance-get` can finish exhausted without a packet leaving the process
([dht.cljc](../../src/cljc/dao/jing/dht.cljc), lines 446–466, 546–565, 704–734).
Elapsed time and append/syscall outcomes distinguish that from retry exhaustion.

The named `1794...-j7-cross-host-interop-probe-not-yet.md` report is absent from
this worktree and from the local `master` tree (`git ls-tree -r --name-only
master collab/jolt`). Its exact commands, source revisions, logs and meaning of
“zero incoming fetch activity” could not be inspected. The task's account is
context, not independently verified packet evidence. No remote ref was fetched.

Live loopback reproduction was attempted but this execution sandbox prohibits
socket creation: JVM deposited `bind-failed` with `java.net.SocketException:
Operation not permitted`; jolt deposited `bind-failed` with `socket failed:
errno 1`. The temporary harness then refused its incomplete writer descriptor.
No socket traffic or pcap was obtained. This is an environment limitation, not
a reproduction of the reported failure. No source or runtime file was changed.

## The actual composition and packet contract

The host selection is in [dao.space.dht](../../src/cljc/dao/space/dht.cljc),
not a separate DHT algorithm in the shell. In this revision
[yin.shell.dht](../../src/cljc/yin/shell/dht.cljc) has no `:jolt` reader arms:
`open` passes the checked options and `:bind! (or bind! (dht/default-bind))` to
`dht/join` (lines 648–658), and `step` delegates to the node (1301).
Its head-board CBOR selection (875–887) concerns WebSockets, not UDP fetches.

The path is:

1. `dao.space.dht/default-bind` selects JVM `dao.stream.datagram.jvm/bind!`
   or jolt `dao.stream.udp/bind!` (53–60, 134–142). Despite the jolt namespace's
   name, **this entry is a raw datagram seam**, not `UdpHandle`/`send-value!`.
2. `node-over` binds with a traffic-ring deposit writer and a 1200-byte budget
   (257–291). A node without peers is solo and opens no socket.
3. `bind-step` consumes `bound`, constructs `dao.stream.datagram/writer`, and
   gives that raw writer and traffic reader to the shared `dao.jing.dht/state`
   (462–485). The DHT has its own traffic cursor.
4. `dao.space.dht/step` calls the optional seam `:step!` **before** stepping
   the DHT (1432–1442). JVM receives on its receiver thread; jolt receives only
   during this explicit native seam turn. The owner must retain successor nodes.
5. `dao.jing.dht/send-datagram!` CBOR-encodes a message; `send-bytes!` wraps
   those bytes as Base64 in a raw outbound stream value (273–316). The raw
   writer validates destination/Base64/size and decodes Base64 once. The seam
   sends the resulting bytes without another value encoding.

The [datagram contract](../../docs/design/dao.stream.datagram.md), section 2,
and [raw writer](../../src/cljc/dao/stream/datagram.cljc), lines 243–291, require:

```clojure
;; Stream-visible outbound value, NOT the UDP payload:
{:dao.stream.datagram/destination {:dao.stream.datagram/host "127.0.0.1"
                                   :dao.stream.datagram/port 40173}
 :dao.stream.datagram/bytes "<padded standard Base64 of CBOR>"}
;; Stream-visible inbound event:
{:dao.stream.datagram/socket "<socket identity>"
 :dao.stream.datagram/source {:dao.stream.datagram/host "127.0.0.1"
                              :dao.stream.datagram/port 40175}
 :dao.stream.datagram/bytes "<padded standard Base64 of exact received bytes>"}
;; Actual first-contact UDP payload decodes to:
{:dao.jing.dht/v 1 :op :ping :q <integer>
 :id <32-byte CBOR byte string> :pad <zero-filled CBOR byte string>}
```

The UDP payload is one canonical **dao.stream.cbor** value, not Transit,
`dao.jing`'s content codec, Base64 text, the raw outbound envelope, or the UDP
value-channel fragment envelope. `:id`/`:target` are raw 32-byte strings,
`:cookie` is a raw 16-byte string, and `:address` is a **keyword** such as
`:segment/blake3-...`, not its printed string. `:bytes` in a found reply/chunk
is also a CBOR byte string. DHT chunks carry `:dao.jing.dht/v`, `:op :chunk`,
`:dir`, `:q`, `:part`, `:parts`, and `:bytes`
([wire spec](../../docs/design/dao.jing.dht.md), section 7).

A cold reader first sends a padded ping. The publisher returns
`{:op :reply ... :cookie <16 bytes> :need-cookie true}` to the observed source.
The reader resends that ping with the returned cookie and receives `:ok true`;
lookup can then send `:find` and `:fetch`. Cookies are opaque to the reader:
secrets and clock epochs need not agree between hosts. A pinned manifest fetch
requires no signed-head WebSocket exchange.

**Logging blind spot:** the shared DHT has no normal per-incoming-ping/find/fetch
log or fact. `on-traffic` silently drops bad Base64/CBOR/version/op (980–998);
`on-request` silently drops invalid q/id and undersized cookie-less requests
(806–832). A lack of shell fetch lines is not evidence of zero UDP arrivals.

## Ranked hypotheses and minimal fix sketches

Ranks express diagnostic priority given the source and offline results, not
measured probabilities. Rank 1 is the necessary non-codec control; ranks 2–7
are the exact shape/codec/handshake divergences to distinguish. None is confirmed.

| Rank | Hypothesis and discriminating evidence | Minimal fix **if confirmed** |
|---|---|---|
| 1 | **No accepted packet reaches the relevant interpreter.** Wrong UDP port/address, loopback bound on another machine, family mismatch, send refusal, receiver not stepped, or wrong successor state retained. TCP head-board liveness does not prove UDP liveness at the same numeric port. Expected: a padded ping to the publisher's actual UDP bind; compare candidate list, append outcome, syscall and captures. Both protocol and seam explicitly reject family mismatches. | Correct the concrete bootstrap/bind endpoint or family. If the owner skips the native seam/DHT turn, restore the existing `dao.space.dht/step` sequence and retain its returned node; do not add a second reader or hidden receive thread. Surface the actual refusal in the diagnostic trace. |
| 2 | **Native byte or sockaddr transfer differs from the offline path.** `wire/encode` bytes and decoded raw-writer bytes are correct, but `ffi/write-array`/send length/sockaddr or receive extraction changes them. Compare pre-send bytes, native buffer readback, `sendto` n/errno and pcap bytes/endpoint. Capture before the JVM max-size filter; >1200 bytes are silently dropped. The native port helpers write bytes 2–3 in network order; no reversed-port defect was found by inspection. | Repair only the demonstrated copy width/count, sockaddr field or FFI declaration. Keep one exact-length send and one receive event per datagram; do not increase the budget to conceal a corrupt length. |
| 3 | **A deployed path puts the wrong envelope/codec on the socket.** Payload is ASCII Base64/Transit, a CBOR text/byte string containing encoded CBOR, a CBOR map with `:dao.stream.datagram/destination`, or `:dao.stream.udp/fragment`, rather than a top-level DHT map. `decode-message` then returns nil. Current default composition does not do this; a stale override, launch classpath or injected `:bind!` could. | Compose `udp/bind!` as the raw seam and `datagram/writer` as `:datagrams`. Remove the one erroneous extra serialization or value-channel adapter. Retain stream-visible Base64 and raw wire CBOR at their existing boundaries. |
| 4 | **Actual message fields differ in type, tag or canonical encoding.** Missing/wrongly resolved `:dao.jing.dht/v`; string keys/op/address; id as 64 hex characters or integer array rather than CBOR byte string; q encoded as float; noncanonical key order/tag-39 encoding. JVM catches CBOR rejection; q/id gates can drop an otherwise decoded map. Shared source and the tested frames agree, so this needs the failing packet, not a generic assertion that CBOR differs. | Correct the single identifier/type/encoder rule against the JVM canonical profile. Fix accidental `::v` resolution at the producer if observed. Add the captured failing frame as a shared golden; preserve the JVM strict decoder. |
| 5 | **Valid cookie replies arrive but jolt does not match or consume them.** Reply match is `[observed host, observed port, q]`; wrong source-port extraction, another send socket, address spelling, stale traffic cursor or late servicing can prevent the cookie resend. Expect JVM outbound `:need-cookie`, but no cookie-bearing ping from jolt. At >=500 query ticks a reply is stale. This explains zero `:fetch`, even with valid arriving pings. | Preserve the observed source tuple and send through the same bound socket; fix the demonstrated address/cursor/stepping error. Normalize literal representation consistently only if a spelling mismatch is measured. Do not match by q alone or broadly extend timeouts. |
| 6 | **First-contact padding or cookie echo is malformed on the failing path.** Too-short cookie-less packet produces silence by anti-amplification policy; a cookie echoed as Base64 text/vector, truncated bytes, or from a changed source port repeatedly fails the gate. Log request length, q/id acceptance, cookie byte length, epoch and whether the same source tuple is used. The measured production ping is 257 bytes and elicits a 124-byte challenge offline, so it is not undersized. | Restore shared `padded`/raw-byte cookie handling or stable socket source. Do not disable the cookie gate or make both nodes share a secret. |
| 7 | **Only the large reply diverges.** Small fetch succeeds, but a large found reply uses the UDP value-channel fragment envelope, bad DHT part fields/bytes, inconsistent q/dir, or reassembly/canonical-content rejection. This cannot explain absence of initial pings and is a lower-priority branch until the handshake and small fetch pass. | Use the existing DHT `chunks/split` framing and repair the demonstrated field/copy/reassembly discrepancy. Retain bounds and address verification; do not substitute `common/send-value!` fragmentation. |

A valid `:fetch` answered with `:found false` is a different branch: inspect the
**effective** publisher `::publish?`, local lookup result and exact manifest
address. In this source `dht serve` does not set `--dht-publish`; use an explicit
flag on storage peers for this reproduction. Successful publisher replication
does not prove that this reader reached that publisher. Neither a missing blob
nor an unpublished node explains the absence of raw ping arrivals.

## Concrete reproduction and instrumentation plan

### Controlled process layout

Run on a host where UDP sockets/capture are permitted, first all on loopback,
then across the original machines with real reachable IP literals. Record repo
SHA, runtime SHA and dirt, loaded namespace roots, argv and effective checked
specs (peers, bind, publish, manifest, budgets and binder identity; omit secrets).
Use new explicit directories and `--no-state` so saved CLI state cannot change
this experiment. Keep each process alive in its own terminal/PTY with the host
ticker running. Do not pipe EOF immediately after launch.

Use JVM storage peers B/C at 40171/40172, JVM publisher A at 40173, JVM control
reader at 40174 and jolt reader at 40175. Common JVM launch prefix:
`clojure -M:clj-yin-repl`; jolt prefix after sourcing
`build/tools/jolt-env.sh`: `build/tools/jolt/bin/jolt -Srepro -M:jolt:jolt-yin-repl`.
Supply the following arguments (paths are fresh directories under the run dir):

```text
B: --no-state --index-store dht:<run>/b --dht-bind 127.0.0.1 --dht-port 40171
   --dht-peer 127.0.0.1:40172 --dht-publish
C: --no-state --index-store dht:<run>/c --dht-bind 127.0.0.1 --dht-port 40172
   --dht-peer 127.0.0.1:40171 --dht-publish
A: dht init --no-state --dir <run>/a --key <run>/a.key
   --listen 127.0.0.1:40173 --peer 127.0.0.1:40171 --peer 127.0.0.1:40172
JVM reader: --no-state --index-store dht:<run>/reader-jvm
   --dht-bind 127.0.0.1 --dht-port 40174 --dht-peer 127.0.0.1:40173
   --dht-manifest :segment/<exact frozen manifest digest>
jolt reader: same with reader-jolt and --dht-port 40175
```

After A binds, evaluate `4242`, wait for its publication result, and freeze the
manifest keyword from its versioned `<run>/a/HEAD` record's `:manifest` value.
Do not pass the whole HEAD record or a signed-board token to `--dht-manifest`.
Leave A/B/C running; use that same manifest for both fresh readers. Do not keep
evaluating in A while comparing them. Also test a plain single small segment
through `dao.jing.dht/state` and production raw seams to exclude shell hydration;
then test a payload large enough to force DHT reply chunks. Finally reverse the
publisher/reader host roles. These are planned runs, not results of this report.

### What to record, before every silent gate

Use temporary, instance-scoped diagnostic wrappers and independent traffic-ring
cursors. Log to a diagnostic stream/file outside program/index content; do not
change datagram payloads, share a consuming cursor, or introduce send retries.
For the controlled fixture, save complete public packet bytes so the four byte
boundaries can be compared; never log the root cookie secret or signing key.

| Side / location | Required observations |
|---|---|
| Both owners, `dao.space.dht/step` | Step sequence, wall time and tick reading, bound identity/local endpoint, seam-turn invocation/result, DHT traffic cursor before/after, get/candidate/dead/query counts. Establish ticks advance in milliseconds and successor nodes are retained. |
| jolt DHT `issue` / `query-message` / `send-datagram!` | Owner/address, destination, purpose vs actual op, q **and type**, map keys/version and identifier types, id/cookie/target byte lengths, total encoded size, packet hash and raw append outcome. Preserve non-ok outcomes. |
| jolt raw writer and `udp.jolt` send closure (279–321) | Original Base64 length, decoded byte count/hash; fd/socket identity, local endpoint, address family, constructed sockaddr IP/port and raw sockaddr bytes; native buffer readback/hash immediately before `*sendto*`; requested length, returned n and captured errno. Read errno only on failure. |
| JVM seam, before/after `.receive` and before size drop | Actual source/destination socket, received n, payload/hash, overbudget decision and deposit append outcome. This proves kernel arrival independently of DHT decoding. See `datagram/jvm.clj` lines 68–91. |
| JVM `on-traffic` / `decode-message` / request gate | Event source port/type and Base64 validation; call `wire/decode` directly in the diagnostic wrapper and record exception/ex-data before `decode-message` swallows it. If decoded, log keys/value types, `::v`, op, safe-q/id-length acceptance, cookie-valid flag and challenge-size <= request-size decision. Log local lookup only after gate acceptance. |
| JVM reply path | q, destination **observed source**, op/body kind, encoded n/hash and append/send outcome, including the need-cookie reply. |
| jolt receive turn (323–386) and `on-reply` | `recvfrom` n/errno and source; oversize/deposit result; direct decode outcome; matched `[host port q]`, sent/now/age vs 500 ticks, id/cookie lengths, resent flag and next send. Native diagnostics already record receive errors but do not record every accepted packet or deposit refusal. |
| Chunk/content path, only once fetch is observed | q/dir/part/parts, piece count/length and reassembly key/result; whole-message decode; requested address vs accepted payload digest, and `jing/accept-bytes!` refusal. |

### Packet capture and the decisive comparisons

Start capture **before** the readers. On Linux, an operator with capture rights
can use (use `lo0` on macOS loopback):

```sh
tcpdump -i any -nn -s 0 -U -w /tmp/j7-wire.pcap \
  'udp and (port 40171 or port 40172 or port 40173 or port 40174 or port 40175)'
# Extract UDP payloads and tuples, rather than the whole Ethernet/IP frame:
tshark -r /tmp/j7-wire.pcap -Y udp -T fields \
  -e frame.number -e frame.time_epoch -e ip.src -e ipv6.src -e udp.srcport \
  -e ip.dst -e ipv6.dst -e udp.dstport -e udp.length -e udp.payload
```

For the original multi-machine probe, capture at **both** sender and receiver;
loopback capture on one machine cannot establish remote delivery. Keep UDP and
TCP board traces distinct. UDP length includes its 8-byte header.

1. Find the jolt reader's **first padded ping**, not a fetch. Compare, byte for
   byte: `wire/encode` output -> Base64-decoded writer bytes -> native send
   buffer -> captured UDP payload -> JVM predecode bytes. Compare destinations
   and observed source ports as well. First unequal boundary owns the defect.
2. With fixed q/id, the offline production ping starts
   `a5 d8 27 62 3a 71 01 d8 27 63 3a 69 64 58 20` and ends with the tagged
   keyword `:dao.jing.dht/v` and integer 1. This prefix is for the recorded q=1
   fixture; random q changes its integer head/width. The top-level map contains
   q/id/op/pad/version; printable Base64 or a raw envelope is immediately wrong.
3. If A's capture has no ping, stop codec investigation and locate the missing
   send/routing boundary. If capture has it but the seam doesn't, check actual
   bound socket, family, size drop and receiver liveness. If the seam has it but
   DHT doesn't, inspect deposit outcomes, traffic cursor and owner steps.
4. If A decodes it, follow `need-cookie -> cookie-bearing ping -> ok -> find /
   fetch -> found/chunks`. At the first absent arrow compare both sides' send,
   capture, decode and gate records. A server reply with no client receive is
   a reverse delivery/servicing problem, not a server codec diagnosis.
5. Export the failing payload as octets; on JVM and jolt independently call
   `dao.stream.cbor/decode`, then `dao.jing.dht/decode-message`, then re-encode
   and byte-compare. A direct decode exception identifies codec rejection;
   valid CBOR with no DHT map identifies envelope/discriminator rejection;
   successful decode with no reply identifies a later gate/owner issue.

## Executed offline evidence

Linux x86_64; jolt reports pinned SHA
`b389f1a7fb553f63a3494d71f8fe3251790bf4d3`. Its existing checkout is dirty in
`stdlib/jolt/socket/native.clj` (exception-safe allocation changes). That dirt
was present before this diagnosis and was neither edited nor reset. The offline
probe does not require that native socket namespace. No clean native-runtime
qualification or live interop is claimed.

The exact temporary probe is reproduced below so it can be rerun from this
artifact. It uses production DHT state/step, CBOR, Base64, raw writer and event
constructors, with **only the socket send function replaced by a byte recorder**.
It fixes random q only for comparison. Commands executed:

```sh
clojure -M /tmp/j7-wire-offline.cljc > /tmp/j7-wire-offline-jvm.log 2>&1
. build/tools/jolt-env.sh
build/tools/jolt/bin/jolt -Srepro -M:jolt /tmp/j7-wire-offline.cljc \
  > /tmp/j7-wire-offline-jolt.log 2>&1
clojure -M /tmp/j7-wire-offline.cljc /tmp/j7-wire-offline-jolt.log \
  > /tmp/j7-wire-cross-jvm.log 2>&1
build/tools/jolt/bin/jolt -Srepro -M:jolt /tmp/j7-wire-offline.cljc \
  /tmp/j7-wire-offline-jvm.log > /tmp/j7-wire-cross-jolt.log 2>&1
```

Final runs exited 0 in both runtimes on this machine. Each reported:

```text
:OFFLINE-PASS 9
:RECEIVER-GATE-PASS 257 :reply-size 124
:CROSS-DECODE-PASS 9
```

The actual get emitted one identical padded ping and retained one pending query
in each runtime. All nine printed fixture lines were identical; SHA-256 of those
EDN lines joined with newlines and a final newline:
`cfa9ce3c806a1d7943b795b1b67e24bc884af7907daa6636f83af5e8835eb83d`.
They cover ping, need-cookie, cookie-bearing ping, ping-ok, find, peers, fetch,
found payload and DHT chunk, including q=2147483646 and high-bit payload bytes.
Each runtime decoded all nine fixtures exported by the other runtime. Replaying the
actual production ping through the receiving DHT gate produced a cookie
challenge, exercising q/id/version/op dispatch and the anti-amplification gate.
This does not test kernel FFI transfer, a complete two-host cookie handshake,
live shell scheduling, a whole manifest traversal or chunk reassembly.

The initial cross-decode extension placed an aliased require inside a conditional
and failed compilation on both hosts; moving the require to the top level fixed
the temporary harness. Only the successful reruns above support the claims.
No repository runtime or test code changed; only this findings file is intended
for commit. Staging was blocked because the worktree Git metadata is read-only:
Git could not create
`/home/sto/ci/datomworld-j6e-linux/.git/worktrees/dw-j7/index.lock`.
The findings remain in the working tree; the branch has not advanced.

### Offline probe source

Save this block as `/tmp/j7-wire-offline.cljc` before running the commands above.

```clojure
(require '[clojure.edn :as edn] '[clojure.string :as str] '[dao.stream :as stream] '[dao.stream.ringbuffer :as ring]
         '[dao.stream.datagram :as dg] '[dao.stream.cbor :as wire]
         '[dao.host.bytes :as bytes] '[dao.jing :as jing] '[dao.jing.dht :as dht])
(defn medium [] (:dao.stream/handle (ring/create! {:dao.stream/type ring/transport-type ring/capacity-key 4096})))
(def sent (atom []))
(def outbound (dg/writer {:send! (fn [host port bs] (swap! sent conj [host port (bytes/to-vector bs)]) {:dao.stream/outcome :dao.stream/ok}) :close! (fn [] nil)} {:dao.stream/type :dao.stream/datagram :dao.stream/identity "diag" :dao.stream.datagram/bind-host "127.0.0.1" :dao.stream.datagram/bind-port 40178}))
(def requests (medium))
(def answers (medium))
(def traffic (medium))
(def ticks (medium))
(def facts (medium))
(def address (jing/segment-key [:j7-wire-diag]))
(def s (dht/state {:local {:get-bytes-fn (fn [_ nf] nf) :put-bytes-fn (fn [_ _] :inserted)} :requests requests :answers answers :facts facts :ticks ticks :traffic traffic :datagrams outbound ::dht/id (apply str (repeat 64 "2")) ::dht/secret (bytes/alloc 32) ::dht/max-inbound-bytes 65536 ::dht/bootstrap [{:host "127.0.0.1" :port 40177}]}))
(stream/append! requests {:jing/request "diag" :jing/get address})
(stream/append! ticks {:dao.lease/event :dao.lease/tick :dao.lease/reading 0})
(def next-q (atom 0))
(def next-s (with-redefs [clojure.core/rand-int (fn [_] (swap! next-q inc))] (dht/step s 64)))
(println :ACTUAL address :sent @sent :queries (count (:queries next-s)))
(def id (bytes/from-vector (vec (range 32))))
(def cookie (bytes/from-vector (vec (range 16))))
(def base {:dao.jing.dht/v 1 :op :ping :q 2147483646 :id id})
(def samples [[:ping (assoc base :pad (bytes/alloc 160))]
              [:need-cookie (assoc base :op :reply :cookie cookie :need-cookie true)]
              [:ping-cookie (assoc base :cookie cookie)]
              [:ping-ok (assoc base :op :reply :cookie cookie :ok true)]
              [:find (assoc base :op :find :cookie cookie :target id)]
              [:peers (assoc base :op :reply :cookie cookie :peers [[id "127.0.0.1" 40177]])]
              [:fetch (assoc base :op :fetch :cookie cookie :address address)]
              [:found (assoc base :op :reply :cookie cookie :found true :bytes (jing/canonical-bytes [:j7-wire-diag]))]
              [:chunk {:dao.jing.dht/v 1 :op :chunk :q 2147483646 :dir :reply :part 0 :parts 2 :bytes (bytes/from-vector [0 127 128 255])}]])
(doseq [[name m] samples]
  (let [bs (wire/encode m) b64 (jing/bytes->base64 bs)]
    (prn [name (bytes/to-vector bs)])
    (assert (bytes/equal? bs (jing/base64->bytes b64)))
    (assert (bytes/equal? bs (wire/encode (wire/decode bs))))))
(println :OFFLINE-PASS (count samples))
(def received (bytes/from-vector (nth (first @sent) 2)))
(reset! sent [])
(def recv-traffic (medium))
(stream/append! recv-traffic (dg/datagram-event "diag" "127.0.0.1" 40178 received))
(def recv-state (dht/state {:local {:get-bytes-fn (fn [_ nf] nf) :put-bytes-fn (fn [_ _] :inserted)} :requests (medium) :answers (medium) :facts (medium) :ticks (medium) :traffic recv-traffic :datagrams outbound ::dht/id (apply str (repeat 64 "1")) ::dht/secret (bytes/alloc 32) ::dht/max-inbound-bytes 65536 ::dht/publish? true}))
(def recv-next (dht/step recv-state 64))
(def response (wire/decode (bytes/from-vector (nth (first @sent) 2))))
(assert (true? (:need-cookie response)))
(println :RECEIVER-GATE-PASS (bytes/length received) :reply-size (count (nth (first @sent) 2)))
(when-let [other (first *command-line-args*)]
  (def imported (mapv edn/read-string (filter #(str/starts-with? % "[") (str/split-lines (slurp other)))))
  (doseq [[name octets] imported]
    (let [bs (bytes/from-vector octets) m (wire/decode bs)]
      (assert (= 1 (:dao.jing.dht/v m)))
      (assert (= (:op m) (:op (dht/decode-message bs))))
      (assert (bytes/equal? bs (wire/encode m)))))
  (println :CROSS-DECODE-PASS (count imported)))
```
