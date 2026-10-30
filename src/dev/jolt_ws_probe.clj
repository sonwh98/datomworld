;; bb probe:jolt-ws: the J6e gates for the jolt WebSocket host adapter
;; (dao.stream.ws.jolt over the J6d pump and substrate, with the J6b frame and
;; upgrade cores and J6c's SHA-1), one section per child jolt process so an
;; abort cannot erase another section's report:
;;
;;   closure    C1: the adapter, pump, substrate, cores and crypto are loaded
;;              with jolt.socket.native, jolt.io-poller and jolt.ffi; neither
;;              jolt.socket (the java.net shim) nor dao.stream.transit is;
;;   handshake  C2: jolt dials jolt (CBOR negotiated, the seam names it); the
;;              RFC 6455 sample key's accept value through libcrypto SHA-1;
;;              server refusals (Transit-only offer, no subprotocol, not an
;;              upgrade, a 9 KiB head, a stalled head at the overridden
;;              deadline), each with its one :upgrade-failed; client failures
;;              (426, bad accept key, unsolicited extension, oversized 101,
;;              refused connect), each exactly one :closed! 1006
;;              connect-failed; wss:// dials nothing;
;;   bind       C3: port 0 deposits :bind-succeeded with the kernel's port
;;              inside bind!; serve! reaches :serving naming it; a port in use
;;              is a transport error (::bind-failed); 0.0.0.0 by literal;
;;   address    C4: literals, [::1] and localhost dial; a host name is a
;;              transport error at attach! with the REPL's reachability text;
;;              the resolver trap is never sprung;
;;   io         C5: the transport over the real adapter: exact :queued-bytes
;;              behind a scripted short write, outbound high water (full, then
;;              ok after drain), outbound overflow (1008, closed), a message
;;              over the frame bound (frame-too-large, 1009, never delivered),
;;              EOF and reset (one :ws/closed 1006), send after failure, a
;;              150 KB message both ways;
;;   close      C6: close-ended! 4000 (:ws/ended both sides), 1000
;;              (:ws/closed), the empty close (1005, empty response), 5000
;;              (1002 both ways), a non-reading peer (1006 at the overridden
;;              deadline);
;;   shutdown   C7: (quit) through the real owner loop (main/run-owner!) with
;;              two acknowledged jolt REPL peers on a second instance, a
;;              non-reading peer holding pending bytes and a continuously
;;              readable peer: :stopped before the unconfirmed verdict, 4000 at
;;              both peers, the non-reading peer shut within the unbind grace,
;;              the registry empty before close-host!, drained/forced per
;;              connection, :unbind-closed 0, fewer rounds than stop-ticks
;;              and no timeout text; the same gate with a peer readable at
;;              every poll (R17), which receives 4000 from the composition;
;;              the 1001 slot exists only when no upgraded connection remains;
;;              then a
;;              one-tick budget: forced, and close-host! leaves nothing;
;;   isolation  C8: two instances, each a listener and a dial, interleaved;
;;              no cross-talk; close-host! on one leaves the other serving;
;;   driver     C9: driver/sleep-fds! folds the deadline: an empty registry
;;              waits the cadence, a 50 ms deadline under a 1000 ms cadence
;;              wakes at about 50 ms, a past deadline still polls and hands a
;;              ready fd and the expiry to one pump!; a --headless --port
;;              child process answers a connected peer (R11);
;;   fairness   C10: a readable connection plus runnable REPL work: the step
;;              and the delivered-frame counts both advance every round.
;;
;; The adapter sections run under the native-memory ledger of the J6d probe
;; (jolt.ffi alloc, string->ptr and free wrapped) and end with every instance's
;; registry and buffers empty. The REPL-composing sections (shutdown, driver's
;; child-process leg, fairness) check their instances' registries instead.
;; The scripted transfer seam is dao.stream.ws.tcp's single-transfer vars.
;;
;; Run: bb probe:jolt-ws (all sections), or under jolt directly:
;;   jolt -Srepro -M:jolt src/dev/jolt_ws_probe.clj <section>
;; Prints PASS/FAIL per gate and RECORD per observation; exits nonzero on any
;; FAIL.
(ns jolt-ws-probe
  (:require [jolt.ffi :as ffi]
            [jolt.io-poller :as poller]
            [jolt.socket.native :as native]))


(require '[clojure.string :as str]
         '[dao.host.text :as text]
         '[dao.stream :as stream]
         '[dao.stream.cbor :as cbor]
         '[dao.stream.codec :as codec]
         '[dao.stream.ringbuffer :as ring]
         '[dao.stream.waitset.driver :as wake]
         '[dao.stream.ws :as ws]
         '[dao.stream.ws.crypto :as crypto]
         '[dao.stream.ws.jolt :as wsj]
         '[dao.stream.ws.pump :as pump]
         '[dao.stream.ws.rfc6455 :as rfc6455]
         '[dao.stream.ws.tcp :as tcp]
         '[dao.stream.ws.upgrade :as upgrade]
         '[yin.repl.connect :as connect]
         '[yin.repl.driver :as repl-driver]
         '[yin.repl.host :as host]
         '[yin.repl.main :as main]
         '[yin.repl.serve :as serve])


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail))
  (flush))


(defn- record
  [& xs]
  (println (apply str "RECORD " (interpose " " xs)))
  (flush))


(def send-var (ns-resolve 'dao.stream.ws.tcp 'send-once))
(def orig-send @send-var)


;; =============================================================================
;; Native memory ledger (as in the J6d probe)
;; =============================================================================

(def ledger (atom {:allocs 0, :frees 0, :live #{}, :double-free 0}))
(def orig-alloc ffi/alloc)
(def orig-free ffi/free)
(def orig-string->ptr ffi/string->ptr)


(defn- counting
  [f]
  (fn [& args]
    (let [p (apply f args)]
      (swap! ledger #(-> % (update :allocs inc) (update :live conj (ffi/address p))))
      p)))


(defn- counting-free
  [p]
  (let [a (ffi/address p)]
    (swap! ledger #(if (contains? (:live %) a)
                     (-> % (update :frees inc) (update :live disj a))
                     (update % :double-free inc))))
  (orig-free p))


(defn- with-ledger
  [label f]
  (reset! ledger {:allocs 0, :frees 0, :live #{}, :double-free 0})
  (with-redefs-fn {#'ffi/alloc (counting orig-alloc), #'ffi/string->ptr (counting orig-string->ptr)
                   #'ffi/free counting-free}
    f)
  (let [{:keys [allocs frees live double-free]} @ledger]
    (check (str label ": every native allocation freed, none twice")
           (and (pos? allocs) (empty? live) (zero? double-free))
           {:allocs allocs, :frees frees, :live (count live), :double-free double-free})))


;; =============================================================================
;; Bytes, media, recorders
;; =============================================================================

(defn- bvec
  [bs]
  (mapv #(bit-and % 0xff) bs))


(defn- octets
  [v]
  (byte-array (map unchecked-byte v)))


(defn- ascii
  [v]
  (apply str (map char v)))


(def portable-admission
  {:retention :evict-oldest :capacity 256 :value-domain :portable-values})


(def handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(defn- buffer
  [capacity]
  (:dao.stream/handle (ring/create! {:dao.stream/type ring/transport-type, ring/capacity-key capacity})))


(defn- target
  [handle]
  {:dao.stream/handle handle :dao.stream/surface #{:writer}})


(defn- reader
  "A cursor over `handle` from its newest position: `(read!)` answers every
   value deposited since, advancing."
  [handle]
  (let [at (atom (:dao.stream/cursor (stream/cursor handle stream/anchor-newest)))
        seen (atom [])]
    (fn []
      (loop []
        (let [r (stream/next handle @at)]
          (when (= :dao.stream/ok (:dao.stream/outcome r))
            (reset! at (:dao.stream/cursor r))
            (swap! seen conj (:dao.stream/value r))
            (recur))))
      @seen)))


(defn- event-kinds
  [values]
  (mapv :ws/event values))


(defn- recorder
  "Composition callbacks in the dao.stream.ws/adapter shape, recording into
   `events`."
  [events]
  {:opened! #(swap! events conj [:opened])
   :message! #(swap! events conj [:text %])
   :binary! #(swap! events conj [:binary (bvec %)])
   :closed! (fn [code reason] (swap! events conj [:closed code reason]))
   :too-large! #(swap! events conj [:too-large])
   :error! #(swap! events conj [:error])})


(defn- closes
  [events]
  (filterv #(= :closed (first %)) @events))


(defn- wrap-closes
  "Callback map `cb` that also records each `:closed!` code into `codes`."
  [cb codes]
  (update cb :closed! (fn [f] (fn [code reason] (swap! codes conj code) (f code reason)))))


(def empty-registry {:fds [], :buffers? false, :notify 0})


(defn- registry
  [inst]
  (select-keys (wsj/debug-state inst) [:fds :buffers? :notify]))


;; =============================================================================
;; Driving
;; =============================================================================

(defn- drive!
  "Owner rounds through driver/sleep-fds! over every instance in `insts`
   (and `step!` after each round) until `(pred)` or `ms` pass; answers
   whether `pred` held."
  ([w insts pred ms] (drive! w insts pred ms nil))
  ([w insts pred ms step!]
   (let [hps (mapv wsj/host-pump insts)
         deadline (+ (poller/clock-ms) ms)]
     (loop []
       (cond
         (pred) true
         (> (poller/clock-ms) deadline) false
         :else (do (doseq [hp hps] (wake/sleep-fds! w 5 hp nil))
                   (when step! (step!))
                   (recur)))))))


;; =============================================================================
;; Raw TCP peers (the probe's own sockets, outside any instance)
;; =============================================================================

(defn- with-native
  [n f]
  (let [p (ffi/alloc (max 1 n))]
    (try (f p) (finally (ffi/free p)))))


(defn- write-all!
  "Write all of `bs` to nonblocking `fd`, waiting for writability."
  [fd bs]
  (with-native (alength bs)
    (fn [p]
      (ffi/write-array p bs)
      (loop [off 0]
        (if (= off (alength bs))
          off
          (let [[n e] (orig-send fd (+ p off) (- (alength bs) off))]
            (cond
              (not (neg? n)) (recur (+ off n))
              (or (native/eagain? e) (native/eintr? e))
              (do (native/poll-one fd native/pollout 2000) (recur off))
              :else off)))))))


(defn- write-some!
  "One nonblocking write of `bs`; answers the bytes accepted (0 on EAGAIN)."
  [fd bs]
  (with-native (alength bs)
    (fn [p]
      (ffi/write-array p bs)
      (let [[n _] (orig-send fd p (alength bs))] (max 0 n)))))


(defn- raw-peer!
  "A raw client socket to 127.0.0.1:`port`: {:fd :in (atom octets) :end
   (atom nil)}."
  [port]
  (let [{:keys [fd state]} (tcp/connect! (tcp/parse-address "127.0.0.1" port))]
    (when (= :connecting state)
      (native/poll-one fd native/pollout 2000)
      (tcp/connect-result fd))
    {:fd fd, :in (atom []), :end (atom nil)}))


(defn- raw-poll!
  "Read everything `peer` has available now, without waiting."
  [{:keys [fd in end]}]
  (when-not @end
    (with-native 65536
      (fn [p]
        (loop []
          (let [r (tcp/recv-chunk! fd p 65536)]
            (cond
              (= :would-block r) nil
              (= :eof r) (reset! end :eof)
              (:error r) (reset! end (:class r))
              :else (do (swap! in into (bvec (ffi/read-array p (:n r)))) (recur)))))))))


(defn- head-end
  "The index after the blank line ending a head in octets `v`, or nil."
  [v]
  (let [n (count v)]
    (loop [i 3]
      (cond
        (>= i n) nil
        (and (= 13 (nth v (- i 3))) (= 10 (nth v (- i 2))) (= 13 (nth v (- i 1))) (= 10 (nth v i)))
        (inc i)
        :else (recur (inc i))))))


(def sample-key "dGhlIHNhbXBsZSBub25jZQ==")
(def sample-accept "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")


(defn- request-head
  [port & {:keys [protocols upgrade? extra] :or {upgrade? true}}]
  (str "GET /repl HTTP/1.1\r\nHost: 127.0.0.1:" port "\r\n"
       (when upgrade? (str "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "
                           sample-key "\r\nSec-WebSocket-Version: 13\r\n"))
       (when protocols (str "Sec-WebSocket-Protocol: " protocols "\r\n"))
       extra
       "\r\n"))


(defn- raw-request!
  "Send `head` on a fresh raw peer and drive `insts` until a response head
   arrives or the peer ends; answers the peer with `:response` (text) and
   `:rest` (octets after the head)."
  [w insts port head]
  (let [peer (raw-peer! port)]
    (write-all! (:fd peer) (text/utf8-encode head))
    (drive! w insts #(do (raw-poll! peer) (or (head-end @(:in peer)) @(:end peer))) 5000)
    (if-let [e (head-end @(:in peer))]
      (assoc peer :response (ascii (subvec @(:in peer) 0 e)) :rest (subvec @(:in peer) e))
      peer)))


(def mask (byte-array [0x37 0xfa 0x21 0x3d]))


(defn- client-frame
  "A masked client frame's octets."
  [opcode payload]
  (bvec (rfc6455/encode-frame {:opcode opcode, :payload payload, :mask-key mask})))


(defn- server-frames
  "Frames a client role parses from octets `v`."
  [v]
  (second (rfc6455/feed (rfc6455/parser {:role :client}) (octets v))))


(defn- client-frames
  "Frames a server role parses from octets `v` (a raw server's view)."
  [v]
  (second (rfc6455/feed (rfc6455/parser {:role :server}) (octets v))))


(defn- raw-close
  "Close a raw peer's socket."
  [peer]
  (tcp/close! (:fd peer)))


(defn- set-linger-zero!
  "SO_LINGER {1, 0}: close sends RST."
  [fd]
  (let [so-linger (if (= native/os :macos) 0x0080 13)
        l (ffi/alloc 8)]
    (try
      (ffi/write l :int 1 0)
      (ffi/write l :int 0 4)
      (native/c-setsockopt fd native/sol-socket so-linger l 8)
      (finally (ffi/free l)))))


;; =============================================================================
;; Instances, listeners, dials
;; =============================================================================

(defn- listen!
  "A recorder listener on `inst` (127.0.0.1, port 0): every accepted
   connection gets its own events atom. Answers {:bound :port :deposits
   :accepted}."
  ([inst] (listen! inst {}))
  ([inst opts]
   (let [deposits (atom [])
         accepted (atom [])
         bound (wsj/bind! inst (merge {:bind-host "127.0.0.1", :bind-port 0
                                       :deposit! (fn [k v] (swap! deposits conj [k v]))
                                       :accept! (fn [path seam now]
                                                  (let [events (atom [])]
                                                    (swap! accepted conj {:path path, :seam seam
                                                                          :now now, :events events})
                                                    {:ws/adapter (recorder events)}))}
                                      opts))]
     {:bound bound, :port (:port (second (first (filter #(= :bind-succeeded (first %)) @deposits))))
      :deposits deposits, :accepted accepted})))


(defn- failed-codes
  [deposits]
  (mapv (comp :code second) (filter #(= :upgrade-failed (first %)) @deposits)))


(defn- dial!
  "A recorder dial from `inst` to 127.0.0.1:`port` offering `codec`'s
   subprotocol (none when nil). Answers {:seam :events}."
  ([inst port] (dial! inst port cbor/profile))
  ([inst port codec]
   (let [events (atom [])
         seam (wsj/connect! inst {:ws/host "127.0.0.1", :ws/port port, :ws/path "/repl"}
                            (cond-> (recorder events) codec (assoc :ws/codec codec)))]
     {:seam seam, :events events})))


(defn- opened?
  [events]
  (some #(= [:opened] %) @events))


;; =============================================================================
;; A full-stack pair: dao.stream.ws endpoint behind a listener, an attacher
;; =============================================================================

(defn- served-pair!
  "Server: a dao.stream.ws endpoint on instance `si` (CBOR); client: an
   attacher over instance `ci`. Answers once acknowledged:
   {:si :ci :client :server :client-read :server-read :client-codes
   :server-codes :client-seam :port}."
  [w {:keys [client-bounds server-bounds si-opts ci-opts]}]
  (let [si (wsj/create si-opts)
        ci (wsj/create ci-opts)
        d {:dao.stream/type ws/transport-type, :dao.stream/identity "jolt-ws-probe"
           :ws/host "127.0.0.1", :ws/port 0, :ws/path "/repl"}
        control (buffer 64) offer (buffer 1) ack (buffer 1)
        offer-at (atom (:dao.stream/cursor (stream/cursor offer stream/anchor-newest)))
        endpoint (ws/make-endpoint (merge {:codecs [cbor/profile], :descriptor d
                                           :control (target control), :control-admission portable-admission
                                           :slots [{:offer (target offer), :offer-admission handoff-admission
                                                    :ack (target ack), :ack-admission handoff-admission
                                                    :ack-cursor (:dao.stream/cursor
                                                                  (stream/cursor ack stream/anchor-newest))}]}
                                          server-bounds))
        deposits (atom [])
        server-codes (atom [])
        bound (wsj/bind! si {:bind-host "127.0.0.1", :bind-port 0, :codecs [cbor/profile]
                             :ws/max-frame-bytes (:ws/max-frame-bytes server-bounds)
                             :accept! (fn [path seam now] (ws/accept-connection! endpoint path seam now))
                             :ws.jolt/adapter-of #(some-> (:ws/handle %) ws/adapter (wrap-closes server-codes))
                             :deposit! (fn [k v] (swap! deposits conj [k v]))})
        port (:port (second (first @deposits)))
        client-traffic (buffer 512)
        client-read (reader client-traffic)
        client-codes (atom [])
        client-seam (atom nil)
        attach! (ws/make-attacher (merge {:codec cbor/profile, :traffic (target client-traffic)
                                          :admission portable-admission
                                          :connect! (fn [desc a]
                                                      (reset! client-seam
                                                              (wsj/connect! ci desc (wrap-closes a client-codes))))}
                                         client-bounds))
        attached (attach! (assoc d :ws/port port))
        server-traffic (buffer 512)
        server-read (reader server-traffic)
        offered (atom nil)]
    (drive! w [si ci]
            #(do (when-not @offered
                   (let [r (stream/next offer @offer-at)]
                     (when (= :dao.stream/ok (:dao.stream/outcome r))
                       (reset! offer-at (:dao.stream/cursor r))
                       (reset! offered (:dao.stream/value r))
                       (stream/append! ack {:ws/attachment (:ws/attachment (:dao.stream/value r))
                                            :ws/command :ws/accept
                                            :ws/deposit (target server-traffic)
                                            :ws/admission portable-admission})
                       (ws/endpoint-step endpoint (System/currentTimeMillis)))))
                 (and @offered (some #{:ws/opened} (event-kinds (client-read)))))
            5000)
    {:si si, :ci ci, :bound bound, :port port, :endpoint endpoint
     :client (:dao.stream/handle attached)
     :server (get-in @offered [:ws/handle :dao.stream/handle])
     :client-read client-read, :server-read server-read
     :client-codes client-codes, :server-codes server-codes, :client-seam client-seam}))


(defn- done!
  "close-host! each instance; check its registry is empty."
  [label & insts]
  (doseq [inst insts] (wsj/close-host! inst))
  (check (str label ": every instance's registry and buffers empty after close-host!")
         (every? #(= empty-registry (registry %)) insts)
         (mapv registry insts)))


(defn- payloads
  [values]
  (mapv :ws/value (filter #(= :ws/payload (:ws/event %)) values)))


(defn- encoded-len
  "The wire length of one value's masked client frame."
  [v]
  (let [n (alength ((codec/encode-fn cbor/profile) {:ws/frame :ws/value, :ws/value v}))]
    (+ n 6 (cond (< n 126) 0 (< n 65536) 2 :else 8))))


(defn- scripted-send
  "send-once answering per call for descriptors in `fds`: :eagain or
   [:short k]; others are real."
  [fds step]
  (let [calls (atom 0)]
    (fn [fd buf n]
      (if (contains? @fds fd)
        (let [s (step (swap! calls inc))]
          (cond
            (= :eagain s) [-1 native/eagain]
            (= :real s) (orig-send fd buf n)
            :else (orig-send fd buf (min n (second s)))))
        (orig-send fd buf n)))))


;; =============================================================================
;; closure (C1)
;; =============================================================================

(defn- section-closure
  []
  (let [libs (set (map str (loaded-libs)))
        want ["dao.stream.ws.jolt" "dao.stream.ws.pump" "dao.stream.ws.tcp" "dao.stream.ws.rfc6455"
              "dao.stream.ws.upgrade" "dao.stream.ws.crypto" "jolt.socket.native" "jolt.io-poller" "jolt.ffi"]]
    (record "closure: jolt.* loaded" (pr-str (sort (filter #(re-find #"^jolt\." %) libs))))
    (check "closure: the adapter, pump, substrate, frame and upgrade cores, crypto and the native socket layer are loaded"
           (every? libs want) {:missing (remove libs want)})
    (check "closure: neither jolt.socket (the java.net shim) nor dao.stream.transit is loaded (yin.repl.main included)"
           (not-any? libs ["jolt.socket" "dao.stream.transit"])
           {:jolt.socket (contains? libs "jolt.socket"), :transit (contains? libs "dao.stream.transit")})
    (check "closure: yin.repl.host is the jolt shadow (a fresh adapter map with :pump and :close-host!)"
           (let [a (host/websocket)
                 b (host/websocket)]
             (and (every? fn? ((juxt :connect! :bind! :unbind! :close-host!) a))
                  (map? (:pump a)) (not (identical? (:pump a) (:pump b)))))
           {})))


;; =============================================================================
;; handshake (C2)
;; =============================================================================

(defn- raw-server-case!
  "A raw listener answering the jolt dialer's request with `(respond
   request-text)`; answers the dial's events after the dial ended."
  [w respond]
  (let [ci (wsj/create)
        {lfd :fd port :port} (tcp/listen! (tcp/parse-address "127.0.0.1" 0))
        events (atom [])
        _ (wsj/connect! ci {:ws/host "127.0.0.1", :ws/port port, :ws/path "/repl"}
                        (assoc (recorder events) :ws/codec cbor/profile))
        acc (atom nil)
        answered? (atom false)]
    (drive! w [ci]
            #(do (when-not @acc
                   (let [r (tcp/accept! lfd)] (when (map? r) (reset! acc (assoc r :in (atom []) :end (atom nil))))))
                 (when (and @acc (not @answered?))
                   (raw-poll! @acc)
                   (when-let [e (head-end @(:in @acc))]
                     (reset! answered? true)
                     (write-all! (:fd @acc) (text/utf8-encode (respond (ascii (subvec @(:in @acc) 0 e)))))))
                 (seq (closes events)))
            5000)
    (drive! w [ci] (constantly false) 50)
    (when @acc (tcp/close! (:fd @acc)))
    (tcp/close! lfd)
    (let [result @events]
      (wsj/close-host! ci)
      {:events result, :registry (registry ci)})))


(defn- request-key
  [request]
  (second (re-find #"(?i)sec-websocket-key: *([^\r\n]+)" request)))


(defn- section-handshake
  []
  (let [w (wake/make-wake)]
    (with-ledger
      "handshake"
      (fn []
        ;; jolt dials jolt, CBOR
        (let [si (wsj/create) ci (wsj/create)
              {:keys [port accepted deposits]} (listen! si {:codecs [cbor/profile]})
              {:keys [events]} (dial! ci port)]
          (drive! w [si ci] #(opened? events) 5000)
          (check "C2: jolt dials jolt: the dial opens once and the server seam names the CBOR subprotocol"
                 (and (= [[:opened]] @events) (= 1 (count @accepted))
                      (= "dao.stream.cbor" (:ws/subprotocol (:seam (first @accepted))))
                      (= "/repl" (:path (first @accepted))))
                 {:events @events, :subprotocol (some-> (first @accepted) :seam :ws/subprotocol)})
          (check "C2: the accept! now is wall-clock milliseconds (R7)"
                 (< (Math/abs (- (:now (first @accepted)) (System/currentTimeMillis))) 60000)
                 {:now (:now (first @accepted))})
          ;; RFC 6455 section 1.3's sample key through libcrypto SHA-1
          (let [peer (raw-request! w [si] port (request-head port :protocols "dao.stream.cbor"))]
            (check "C2: 101 with the RFC 6455 sample accept value (libcrypto SHA-1) and the CBOR subprotocol"
                   (and (str/starts-with? (:response peer) "HTTP/1.1 101 ")
                        (str/includes? (:response peer) (str "Sec-WebSocket-Accept: " sample-accept))
                        (str/includes? (:response peer) "Sec-WebSocket-Protocol: dao.stream.cbor"))
                   {:response (:response peer)})
            (raw-close peer))
          ;; refusals
          (doseq [[label head status body code]
                  [["a Transit-only offer" (request-head port :protocols "dao.stream.transit-json")
                    "400" "dao.stream/subprotocol-required" :yin.repl.endpoint/subprotocol-required]
                   ["no subprotocol" (request-head port) "400" "dao.stream/subprotocol-required"
                    :yin.repl.endpoint/subprotocol-required]
                   ["a non-upgrade GET" (request-head port :upgrade? false) "400" "dao.stream/websocket-required"
                    :yin.repl.endpoint/websocket-required]
                   ["a 9 KiB head" (request-head port :protocols "dao.stream.cbor"
                                                 :extra (str "X-Pad: " (apply str (repeat 9216 "a")) "\r\n"))
                    "431" nil :yin.repl.endpoint/head-too-large]]]
            (let [before (count (failed-codes deposits))
                  peer (raw-request! w [si] port head)]
              (drive! w [si] #(do (raw-poll! peer) @(:end peer)) 2000)
              (raw-poll! peer)
              (let [text (ascii @(:in peer))
                    new (subvec (failed-codes deposits) before)]
                (check (str "C2: " label " is refused " status " then closed, one :upgrade-failed " code)
                       (and (str/starts-with? text (str "HTTP/1.1 " status " "))
                            (or (nil? body) (str/ends-with? text body))
                            (some? @(:end peer)) (= [code] new))
                       {:response text, :end @(:end peer), :upgrade-failed new}))
              (raw-close peer)))
          ;; a stalled half head at the overridden upgrade deadline
          (let [si2 (wsj/create)
                {port2 :port deposits2 :deposits} (listen! si2 {:codecs [cbor/profile]
                                                                :ws.jolt/upgrade-timeout-ms 200})
                peer (raw-peer! port2)
                t0 (poller/clock-ms)]
            (write-all! (:fd peer) (text/utf8-encode "GET /repl HTTP/1.1\r\nHost: 127"))
            (drive! w [si2] #(do (raw-poll! peer) @(:end peer)) 3000)
            (let [ms (- (poller/clock-ms) t0)]
              (check "C2: a stalled half head is closed at the overridden 200 ms upgrade deadline, one :upgrade-failed upgrade-expired"
                     (and (some? @(:end peer)) (<= 150 ms 1500)
                          (= [:yin.repl.endpoint/upgrade-expired] (failed-codes deposits2)))
                     {:ms ms, :end @(:end peer), :upgrade-failed (failed-codes deposits2)}))
            (raw-close peer)
            (done! "C2 stalled head" si2))
          (done! "C2 jolt dials jolt" si ci))
        ;; client failures: each exactly one :closed! 1006 connect-failed
        (let [accept-of #(upgrade/accept-key (request-key %) crypto/sha1)
              ok-101 (fn [req extra]
                       (str "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            "Sec-WebSocket-Accept: " (accept-of req) "\r\n"
                            "Sec-WebSocket-Protocol: dao.stream.cbor\r\n" extra "\r\n"))]
          (doseq [[label respond]
                  [["426" (fn [_] (upgrade/refusal-response 426))]
                   ["a bad accept key" (fn [_]
                                         (str "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                                              "Connection: Upgrade\r\nSec-WebSocket-Accept: "
                                              sample-accept "\r\n\r\n"))]
                   ["an unsolicited extension" (fn [req] (ok-101 req "Sec-WebSocket-Extensions: permessage-deflate\r\n"))]
                   ["an oversized 101" (fn [req] (ok-101 req (str "X-Pad: " (apply str (repeat 9216 "b")) "\r\n")))]]]
            (let [{:keys [events registry]} (raw-server-case! w respond)]
              (check (str "C2 client: " label " is exactly one :closed! 1006 connect-failed, nothing opened")
                     (and (= [[:closed 1006 "dao.stream/connect-failed"]] events) (= empty-registry registry))
                     {:events events})))
          ;; a refused connect: a port nobody listens on
          (let [ci (wsj/create)
                {lfd :fd port :port} (tcp/listen! (tcp/parse-address "127.0.0.1" 0))
                _ (tcp/close! lfd)
                {:keys [events]} (dial! ci port)]
            (drive! w [ci] #(seq (closes events)) 3000)
            (drive! w [ci] (constantly false) 50)
            (check "C2 client: a refused connect is exactly one :closed! 1006 connect-failed"
                   (= [[:closed 1006 "dao.stream/connect-failed"]] @events) {:events @events})
            (done! "C2 refused connect" ci))
          ;; wss:// dials nothing
          (let [ci (wsj/create)
                seam (wsj/connect! ci {:ws/scheme "wss", :ws/host "127.0.0.1", :ws/port 443, :ws/path "/repl"}
                                   (recorder (atom [])))
                attach! (ws/make-attacher {:codec cbor/profile, :traffic (target (buffer 8))
                                           :admission portable-admission
                                           :connect! (fn [d a] (wsj/connect! ci (assoc d :ws/scheme "wss") a))})
                r (attach! {:dao.stream/type ws/transport-type, :dao.stream/identity "x"
                            :ws/host "127.0.0.1", :ws/port 443, :ws/path "/repl"})]
            (check "C2 client: wss:// answers a non-seam value, attach! a transport error, nothing dialed"
                   (and (nil? seam) (= :dao.stream/transport-error (:dao.stream/outcome r))
                        (= [] (:fds (registry ci))))
                   {:seam seam, :attach (:dao.stream/outcome r)})
            (done! "C2 wss" ci)))))
    (wake/close! w)))


;; =============================================================================
;; bind (C3)
;; =============================================================================

(defn- section-bind
  []
  (let [w (wake/make-wake)
        calls (atom 0)
        trap (fn [& _] (swap! calls inc) (throw (ex-info "resolver called" {})))]
    (with-redefs-fn
      {#'native/resolve-addrs trap, #'native/c-getaddrinfo trap}
      (fn []
        (with-ledger
          "bind"
          (fn []
            (let [inst (wsj/create)
                  deposits (atom [])
                  inside (atom nil)
                  r (wsj/bind! inst {:bind-host "127.0.0.1", :bind-port 0
                                     :accept! (fn [& _] nil)
                                     :deposit! (fn [k v] (swap! deposits conj [k v]))})
                  _ (reset! inside @deposits)
                  port (get-in @inside [0 1 :port])]
              (check "C3: port 0 deposits :bind-succeeded with the kernel's port inside bind!, before it answers"
                     (and (= :dao.stream/ok (:dao.stream/outcome r)) (some? (:ws.jolt/listener r))
                          (identical? inst (:ws.jolt/instance r))
                          (= 1 (count @inside)) (= :bind-succeeded (ffirst @inside))
                          (int? port) (pos? port) (= {:host "127.0.0.1", :port port} (second (first @inside))))
                     {:answer (dissoc r :ws.jolt/listener :ws.jolt/instance), :deposits @inside})
              (let [before (count @deposits)
                    r2 (wsj/bind! inst {:bind-host "127.0.0.1", :bind-port port
                                        :accept! (fn [& _] nil)
                                        :deposit! (fn [k v] (swap! deposits conj [k v]))})]
                (check "C3: a port in use answers a transport error and deposits nothing"
                       (and (= {:dao.stream/outcome :dao.stream/transport-error} r2) (= before (count @deposits)))
                       {:answer r2}))
              (let [d2 (atom [])
                    r3 (wsj/bind! inst {:bind-host main/bind-all-host, :bind-port 0
                                        :accept! (fn [& _] nil) :deposit! (fn [k v] (swap! d2 conj [k v]))})]
                (check "C3: --port's bind host 0.0.0.0 binds by literal"
                       (and (= "0.0.0.0" main/bind-all-host) (= :dao.stream/ok (:dao.stream/outcome r3))
                            (= "0.0.0.0" (get-in @d2 [0 1 :host])))
                       {:deposits @d2}))
              (done! "C3 bind" inst))
            ;; serve!: :serving names the ephemeral port; a port in use is ::bind-failed
            (let [adapter (host/websocket)
                  ep (serve/serve! {:bind-port 0, :bind-host "127.0.0.1", :advertised-host "127.0.0.1"
                                    :host adapter})
                  ep (loop [ep ep i 0]
                       (if (or (= :running (:status ep)) (= i 50))
                         ep
                         (do (wake/sleep-fds! w 5 (:pump adapter) nil)
                             (recur (serve/step ep (System/currentTimeMillis)) (inc i)))))
                  url (serve/url ep)
                  port (some->> url (re-find #":(\d+)/repl$") second Long/parseLong)
                  descriptor (get-in ep [:server :descriptor])]
              (check "C3: serve! reaches :serving and its descriptor and Serving URL name the ephemeral port"
                     (and (= :running (:status ep)) (int? port) (pos? port) (= port (:ws/port descriptor)))
                     {:status (:status ep), :url url, :descriptor descriptor})
              (let [adapter2 (host/websocket)
                    ep2 (serve/serve! {:bind-port port, :bind-host "127.0.0.1", :advertised-host "127.0.0.1"
                                       :host adapter2})
                    note (:bind-note ep2)]
                ;; serve.cljc renders ::remote-channel/bind-failed as this note
                (check "C3: serve! on a port in use is refused as ::bind-failed"
                       (and (= :failed (:status ep2)) (= "the host listener failed to bind" note))
                       {:status (:status ep2), :bind-note note})
                ((:close-host! adapter2))
                (check "C3: the refused adapter's registry is empty" (= empty-registry (select-keys ((:debug-state adapter2)) [:fds :buffers? :notify])) {}))
              ((:close-host! adapter))
              (check "C3: the serving adapter's registry is empty after close-host!"
                     (= empty-registry (select-keys ((:debug-state adapter)) [:fds :buffers? :notify])) {}))))))
    (check "C3: the resolver trap was never sprung" (zero? @calls) {:calls @calls})
    (wake/close! w)))


;; =============================================================================
;; address (C4)
;; =============================================================================

(defn- section-address
  []
  (let [w (wake/make-wake)
        calls (atom 0)
        trap (fn [& _] (swap! calls inc) (throw (ex-info "resolver called" {})))]
    (with-redefs-fn
      {#'native/resolve-addrs trap, #'native/c-getaddrinfo trap}
      (fn []
        (with-ledger
          "address"
          (fn []
            (doseq [[label bind-host dial-host] [["127.0.0.1" "127.0.0.1" "127.0.0.1"]
                                                 ["[::1]" "::1" "[::1]"]
                                                 ["localhost" "127.0.0.1" "localhost"]]]
              (let [si (wsj/create) ci (wsj/create)
                    {:keys [port accepted]} (listen! si {:bind-host bind-host})
                    events (atom [])
                    seam (wsj/connect! ci {:ws/host dial-host, :ws/port port, :ws/path "/repl"} (recorder events))]
                (drive! w [si ci] #(opened? events) 5000)
                (check (str "C4: " label " dials and opens")
                       (and (some? seam) (opened? events) (= 1 (count @accepted)))
                       {:events @events})
                (done! (str "C4 " label) si ci)))
            (let [ci (wsj/create)
                  seam (wsj/connect! ci {:ws/host "example.invalid", :ws/port 80, :ws/path "/repl"} (recorder (atom [])))
                  attach! (ws/make-attacher {:codec cbor/profile, :traffic (target (buffer 8))
                                             :admission portable-admission
                                             :connect! (fn [d a] (wsj/connect! ci d a))})
                  r (attach! {:dao.stream/type ws/transport-type, :dao.stream/identity "x"
                              :ws/host "example.invalid", :ws/port 80, :ws/path "/repl"})
                  opened (connect/open {:url "daostream:ws://example.invalid:80/repl"
                                        :host {:connect! (fn [d a] (wsj/connect! ci d a))}
                                        :now 0})
                  text (pr-str opened)]
              (check "C4: a host name is a transport error at attach!, nothing dialed"
                     (and (nil? seam) (= :dao.stream/transport-error (:dao.stream/outcome r))
                          (= [] (:fds (registry ci))))
                     {:attach (:dao.stream/outcome r)})
              (check "C4: (connect ...) to a host name gives the REPL's reachability text"
                     (str/includes? text "local reachability failure") {:opened text})
              (done! "C4 host name" ci))))))
    (check "C4: the resolver trap was never sprung" (zero? @calls) {:calls @calls})
    (wake/close! w)))


;; =============================================================================
;; io (C5)
;; =============================================================================

(defn- section-io
  []
  (let [w (wake/make-wake)]
    (with-ledger
      "io"
      (fn []
        ;; exact :queued-bytes behind a 7-byte short write then EAGAIN
        (let [{:keys [si ci client client-seam] :as pr} (served-pair! w {})
              cfd (first (:fds (registry ci)))
              stuck (atom #{cfd})
              v1 (apply str (repeat 300 "q"))
              n1 (encoded-len v1)
              [a1 q1 a2 q2] (with-redefs-fn {send-var (scripted-send stuck (fn [i] (if (= i 1) [:short 7] :eagain)))}
                              (fn []
                                (let [a1 (stream/append! client v1)
                                      q1 ((:queued-bytes @client-seam))
                                      a2 (stream/append! client v1)
                                      q2 ((:queued-bytes @client-seam))]
                                  [a1 q1 a2 q2])))]
          (check "C5: :queued-bytes is exact as the transport sees it behind a short write (frame - 7, then + frame)"
                 (and (= :dao.stream/ok (:dao.stream/outcome a1)) (= (- n1 7) q1)
                      (= :dao.stream/ok (:dao.stream/outcome a2)) (= (- (* 2 n1) 7) q2))
                 {:frame n1, :q1 q1, :q2 q2})
          (drive! w [si ci] #(= 2 (count (payloads ((:server-read pr))))) 5000)
          (check "C5: after readiness both messages arrive intact and :queued-bytes is 0"
                 (and (= [v1 v1] (payloads ((:server-read pr)))) (zero? ((:queued-bytes @client-seam))))
                 {:queued ((:queued-bytes @client-seam))})
          (done! "C5 queued" si ci))
        ;; outbound high water: full while saturated, ok after drain
        (let [{:keys [si ci client client-seam] :as pr} (served-pair! w {:client-bounds {:ws/outbound-high-water 64}})
              stuck (atom #{(first (:fds (registry ci)))})
              v (apply str (repeat 200 "h"))
              [a1 a2] (with-redefs-fn {send-var (scripted-send stuck (constantly :eagain))}
                        (fn [] [(stream/append! client v) (stream/append! client v)]))]
          (check "C5: a small :ws/outbound-high-water answers full while saturated"
                 (and (= :dao.stream/ok (:dao.stream/outcome a1)) (= :dao.stream/full (:dao.stream/outcome a2)))
                 {:a1 a1, :a2 a2, :queued ((:queued-bytes @client-seam))})
          (drive! w [si ci] #(zero? ((:queued-bytes @client-seam))) 5000)
          (check "C5: ... and ok once the queue has drained"
                 (= :dao.stream/ok (:dao.stream/outcome (stream/append! client v))) {})
          (drive! w [si ci] #(= 2 (count (payloads ((:server-read pr))))) 5000)
          (done! "C5 high water" si ci))
        ;; outbound overflow: 1008 teardown, the append answers closed
        (let [{:keys [si ci client client-read server-codes]} (served-pair! w {:client-bounds {:ws/max-outbound-bytes 100}})
              stuck (atom #{(first (:fds (registry ci)))})
              v (apply str (repeat 200 "o"))
              [a1 a2] (with-redefs-fn {send-var (scripted-send stuck (constantly :eagain))}
                        (fn [] [(stream/append! client v) (stream/append! client v)]))]
          (reset! stuck #{})
          (drive! w [si ci] #(some #{:ws/closed} (event-kinds (client-read))) 5000)
          (let [vals (client-read)]
            (check "C5: :ws/max-outbound-bytes exceeded: the append answers closed, :ws/error outbound-overflow, 1008 at the peer"
                   (and (= :dao.stream/ok (:dao.stream/outcome a1)) (= :dao.stream/closed (:dao.stream/outcome a2))
                        (some #(= :ws/outbound-overflow (:ws/reason %)) vals)
                        (= [1008] @server-codes))
                   {:a2 a2, :events (event-kinds vals), :server-codes @server-codes}))
          (check "C5: a send after the teardown answers closed"
                 (= :dao.stream/closed (:dao.stream/outcome (stream/append! client v))) {})
          (done! "C5 overflow" si ci))
        ;; a message over the frame bound: frame-too-large, 1009 on the wire, never delivered
        (let [{:keys [si ci server client-read client-codes server-codes]}
              (served-pair! w {:client-bounds {:ws/max-frame-bytes 1000}})
              big (apply str (repeat 2000 "b"))]
          (stream/append! server big)
          (drive! w [si ci] #(and (seq @server-codes) (some #{:ws/closed} (event-kinds (client-read)))) 5000)
          (let [vals (client-read)]
            (check "C5: over :ws/max-frame-bytes: :ws/error frame-too-large, a 1009 close to the peer, the payload never delivered"
                   (and (some #(= :ws/frame-too-large (:ws/reason %)) vals)
                        (empty? (payloads vals)) (= [1009] @client-codes) (= [1009] @server-codes)
                        (= 1 (count (filter #{:ws/closed} (event-kinds vals)))))
                   {:events (event-kinds vals), :client-codes @client-codes, :server-codes @server-codes}))
          (done! "C5 frame bound" si ci))
        ;; abnormal EOF and reset: one :ws/closed, 1006
        (doseq [how [:eof :reset]]
          (let [{:keys [si ci client client-read client-codes]} (served-pair! w {})]
            (when (= :reset how) (set-linger-zero! (first (:fds (registry si)))))
            (wsj/close-host! si)
            (drive! w [ci] #(seq @client-codes) 3000)
            (drive! w [ci] (constantly false) 50)
            (let [vals (client-read)]
              (check (str "C5: abnormal " (name how) ": exactly one :ws/closed, code 1006 (never a clean code)")
                     (and (= [1006] @client-codes) (= 1 (count (filter #{:ws/closed} (event-kinds vals)))))
                     {:codes @client-codes, :events (event-kinds vals)}))
            (check (str "C5: a send after " (name how) " answers closed")
                   (= :dao.stream/closed (:dao.stream/outcome (stream/append! client "x"))) {})
            (done! (str "C5 " (name how)) si ci)))
        ;; a 150 KB message both ways across 64 KiB read chunks
        (let [{:keys [si ci client server client-read server-read]} (served-pair! w {})
              big (apply str (map #(char (+ 97 (mod % 26))) (range 150000)))]
          (stream/append! client big)
          (stream/append! server (str big "!"))
          (drive! w [si ci] #(and (seq (payloads (server-read))) (seq (payloads (client-read)))) 10000)
          (check "C5: a 150 KB message reassembles byte-exact in both directions"
                 (and (= [big] (payloads (server-read))) (= [(str big "!")] (payloads (client-read))))
                 {:server (map count (payloads (server-read))), :client (map count (payloads (client-read)))})
          (done! "C5 large" si ci))))
    (wake/close! w)))


;; =============================================================================
;; close (C6)
;; =============================================================================

(defn- raw-upgraded!
  "A raw client completing the CBOR upgrade against `port`."
  [w insts port]
  (let [peer (raw-request! w insts port (request-head port :protocols "dao.stream.cbor"))]
    (reset! (:in peer) (vec (:rest peer)))
    peer))


(defn- raw-dialed!
  "A raw server answering the jolt dialer from `ci` with a valid 101:
   {:peer (the accepted socket) :events :lfd}."
  [w ci]
  (let [{lfd :fd port :port} (tcp/listen! (tcp/parse-address "127.0.0.1" 0))
        events (atom [])
        _ (wsj/connect! ci {:ws/host "127.0.0.1", :ws/port port, :ws/path "/repl"}
                        (assoc (recorder events) :ws/codec cbor/profile))
        acc (atom nil)]
    (drive! w [ci]
            #(do (when-not @acc
                   (let [r (tcp/accept! lfd)] (when (map? r) (reset! acc {:fd (:fd r), :in (atom []), :end (atom nil)}))))
                 (when (and @acc (not (opened? events)))
                   (raw-poll! @acc)
                   (when-let [e (head-end @(:in @acc))]
                     (let [req (ascii (subvec @(:in @acc) 0 e))]
                       (reset! (:in @acc) [])
                       (write-all! (:fd @acc)
                                   (text/utf8-encode
                                     (str "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                                          "Connection: Upgrade\r\nSec-WebSocket-Accept: "
                                          (upgrade/accept-key (request-key req) crypto/sha1) "\r\n"
                                          "Sec-WebSocket-Protocol: dao.stream.cbor\r\n\r\n"))))))
                 (opened? events))
            5000)
    {:peer @acc, :events events, :lfd lfd}))


(defn- section-close
  []
  (let [w (wake/make-wake)]
    (with-ledger
      "close"
      (fn []
        ;; close-ended! 4000: :ws/ended on both sides
        (let [{:keys [si ci server client-read server-read client-codes server-codes]} (served-pair! w {})]
          (ws/close-ended! server)
          (drive! w [si ci] #(and (seq @client-codes) (seq @server-codes)) 5000)
          (check "C6: close-ended! 4000: the peer deposits :ws/ended and the local terminal is :ws/ended"
                 (and (= [4000] @client-codes) (= [4000] @server-codes)
                      (some #{:ws/ended} (event-kinds (client-read)))
                      (some #{:ws/ended} (event-kinds (server-read))))
                 {:client (event-kinds (client-read)), :server (event-kinds (server-read))})
          (done! "C6 4000" si ci))
        ;; the peer closes 1000: :ws/closed
        (let [{:keys [si ci client client-read server-read client-codes server-codes]} (served-pair! w {})]
          (stream/close! client)
          (drive! w [si ci] #(and (seq @client-codes) (seq @server-codes)) 5000)
          (check "C6: a peer close 1000 is :ws/closed on both sides"
                 (and (= [1000] @client-codes) (= [1000] @server-codes)
                      (some #{:ws/closed} (event-kinds (server-read))) (some #{:ws/closed} (event-kinds (client-read))))
                 {:client-codes @client-codes, :server-codes @server-codes})
          (done! "C6 1000" si ci))
        ;; an empty peer close: zero-payload response, local 1005 (:ws/closed)
        (let [si (wsj/create)
              {:keys [port accepted]} (listen! si {:codecs [cbor/profile]})
              peer (raw-upgraded! w [si] port)]
          (write-all! (:fd peer) (octets (client-frame :close (byte-array 0))))
          (drive! w [si] #(do (raw-poll! peer) (and (seq @(:in peer)) (seq (closes (:events (first @accepted)))))) 3000)
          (check "C6: an empty peer close gets a zero-payload close and a local 1005"
                 (and (= [0x88 0x00] @(:in peer)) (= [1005] (mapv second (closes (:events (first @accepted))))))
                 {:wire @(:in peer), :closes (closes (:events (first @accepted)))})
          (raw-close peer)
          (done! "C6 empty close" si))
        ;; 5000 from a peer client: 1002 response and terminal
        (let [si (wsj/create)
              {:keys [port accepted]} (listen! si {:codecs [cbor/profile]})
              peer (raw-upgraded! w [si] port)
              payload (byte-array [(unchecked-byte 0x13) (unchecked-byte 0x88)])] ; 5000
          (write-all! (:fd peer) (octets (client-frame :close payload)))
          (drive! w [si] #(do (raw-poll! peer) (and (seq @(:in peer)) (seq (closes (:events (first @accepted)))))) 3000)
          (let [frames (server-frames @(:in peer))]
            (check "C6: a peer close 5000 is answered 1002 and the server's terminal is 1002"
                   (and (= 1002 (:code (first frames))) (= [1002] (mapv second (closes (:events (first @accepted))))))
                   {:frames frames, :closes (closes (:events (first @accepted)))}))
          (raw-close peer)
          (done! "C6 5000 server" si))
        (let [ci (wsj/create)
              {:keys [peer events lfd]} (raw-dialed! w ci)]
          (write-all! (:fd peer) (octets (bvec (rfc6455/encode-frame
                                                 {:opcode :close
                                                  :payload (byte-array [(unchecked-byte 0x13) (unchecked-byte 0x88)])}))))
          (drive! w [ci] #(do (raw-poll! peer) (and (seq @(:in peer)) (seq (closes events)))) 3000)
          (let [frames (client-frames @(:in peer))]
            (check "C6: a 5000 to the jolt dialer is answered 1002 (masked) and its terminal is 1002"
                   (and (= 1002 (:code (first frames))) (= [1002] (mapv second (closes events))))
                   {:frames frames, :closes (closes events)}))
          (raw-close peer)
          (tcp/close! lfd)
          (done! "C6 5000 client" ci))
        ;; a non-reading peer: 1006 at the overridden close deadline
        (let [si (wsj/create)
              {:keys [port accepted]} (listen! si {:codecs [cbor/profile], :ws.jolt/close-deadline-ms 200})
              peer (raw-upgraded! w [si] port)
              _ (drive! w [si] #(seq @accepted) 2000)
              t0 (poller/clock-ms)]
          ((:close! (:seam (first @accepted))) 1000 nil)
          (drive! w [si] #(seq (closes (:events (first @accepted)))) 3000)
          (let [ms (- (poller/clock-ms) t0)]
            (check "C6: a peer that never answers our close ends 1006 at the overridden 200 ms deadline"
                   (and (= [1006] (mapv second (closes (:events (first @accepted))))) (<= 150 ms 1500))
                   {:ms ms, :closes (closes (:events (first @accepted)))}))
          (raw-close peer)
          (done! "C6 non-reading" si))))
    (wake/close! w)))


;; =============================================================================
;; isolation (C8)
;; =============================================================================

(defn- section-isolation
  []
  (let [w (wake/make-wake)]
    (with-ledger
      "isolation"
      (fn []
        (let [a (wsj/create) b (wsj/create)
              la (listen! a {}) lb (listen! b {})
              ab (dial! a (:port lb) nil)
              ba (dial! b (:port la) nil)]
          (drive! w [a b] #(and (opened? (:events ab)) (opened? (:events ba))
                                (seq @(:accepted la)) (seq @(:accepted lb)))
                  5000)
          ((:send! (:seam ab)) "from-a")
          ((:send! (:seam ba)) "from-b")
          ((:send! (:seam (first @(:accepted la)))) "a-serves")
          ((:send! (:seam (first @(:accepted lb)))) "b-serves")
          (drive! w [b a] #(and (seq @(:events (first @(:accepted lb)))) (seq @(:events (first @(:accepted la))))
                                (some (fn [e] (= :text (first e))) @(:events ab))
                                (some (fn [e] (= :text (first e))) @(:events ba)))
                  5000)
          (let [fa (set (:fds (registry a))) fb (set (:fds (registry b)))]
            (check "C8: two instances, each a listener and a dial: every message reaches its own peer only"
                   (and (= [[:text "from-a"]] @(:events (first @(:accepted lb))))
                        (= [[:text "from-b"]] @(:events (first @(:accepted la))))
                        (= [[:opened] [:text "b-serves"]] @(:events ab))
                        (= [[:opened] [:text "a-serves"]] @(:events ba)))
                   {:ab @(:events ab), :ba @(:events ba)})
            (check "C8: each debug-state lists only its own descriptors (3 each, disjoint)"
                   (and (= 3 (count fa)) (= 3 (count fb)) (empty? (filter fa fb))) {:a fa, :b fb})
            (wsj/close-host! a)
            (check "C8: close-host! on one leaves the other's descriptors intact"
                   (and (= empty-registry (registry a)) (= fb (set (:fds (registry b))))) {:b (registry b)})
            (drive! w [b] #(= 2 (count (:fds (registry b)))) 3000)
            (let [c (wsj/create)
                  cb (dial! c (:port lb) nil)]
              (drive! w [b c] #(opened? (:events cb)) 5000)
              (check "C8: the other instance still serves: its peers' ends are reported and a new dial opens"
                     (and (opened? (:events cb)) (= 1 (count (closes (:events ba))))
                          (= 1 (count (closes (:events (first @(:accepted lb)))))))
                     {:cb @(:events cb)})
              (done! "C8" b c))))))
    (wake/close! w)))


;; =============================================================================
;; driver (C9)
;; =============================================================================

(defn- recording-pump
  [inst calls]
  (update (wsj/host-pump inst) :pump! (fn [f] (fn [ready] (swap! calls conj ready) (f ready)))))


(defn- timed-round
  [w hp cadence]
  (let [t0 (poller/clock-ms)]
    (wake/sleep-fds! w cadence hp nil)
    (- (poller/clock-ms) t0)))


(defn- section-driver
  []
  (let [w (wake/make-wake)]
    (with-ledger
      "driver"
      (fn []
        (let [inst (wsj/create)
              calls (atom [])
              hp (recording-pump inst calls)
              ms (timed-round w hp 300)]
          (check "C9: an empty registry: :deadline nil, the round waits the cadence alone and pump! runs with []"
                 (and (nil? ((:deadline hp))) (<= 280 ms 1000) (= [[]] @calls)) {:ms ms, :calls @calls})
          (done! "C9 empty" inst))
        ;; a 50 ms upgrade deadline under a 1000 ms cadence
        (let [inst (wsj/create)
              calls (atom [])
              hp (recording-pump inst calls)
              {:keys [port deposits]} (listen! inst {:ws.jolt/upgrade-timeout-ms 50})
              peer (raw-peer! port)]
          (native/poll-one (:fd peer) native/pollout 100)
          (timed-round w hp 200)
          (reset! calls [])
          (let [ms (timed-round w hp 1000)]
            (check "C9: a 50 ms deadline under a 1000 ms cadence wakes the round at about 50 ms, pump! with []"
                   (and (<= 20 ms 400) (= [[]] @calls)
                        (= [:yin.repl.endpoint/upgrade-expired] (failed-codes deposits)))
                   {:ms ms, :calls @calls, :failed (failed-codes deposits)}))
          (raw-close peer)
          (done! "C9 50 ms" inst))
        ;; a past deadline still polls: a ready fd and the expiry in one pump!
        (let [inst (wsj/create)
              calls (atom [])
              hp (recording-pump inst calls)
              l1 (listen! inst {:ws.jolt/upgrade-timeout-ms 40})
              l2 (listen! inst {:ws.jolt/upgrade-timeout-ms 60000})
              p1 (raw-peer! (:port l1))
              p2 (raw-peer! (:port l2))
              before (set (:fds (registry inst)))]
          (dotimes [_ 3] (timed-round w hp 20))
          (let [conn2 (first (remove before (set (:fds (registry inst)))))]
            (write-all! (:fd p2) (text/utf8-encode "GET"))
            (Thread/sleep 80)
            (native/poll-one (:fd p2) native/pollout 10)
            (reset! calls [])
            (let [ms (timed-round w hp 1000)]
              (check "C9: a deadline already due still polls once: the ready fd and the expiry reach one pump!"
                     (and (< ms 200) (= 1 (count @calls))
                          (some #(= conn2 (first %)) (first @calls))
                          (= [:yin.repl.endpoint/upgrade-expired] (failed-codes (:deposits l1))))
                     {:ms ms, :calls @calls, :failed (failed-codes (:deposits l1))})))
          (raw-close p1)
          (raw-close p2)
          (done! "C9 due" inst))))
    ;; R11: a --headless --port child process answers a connected peer
    (let [out "build/j6e-logs/headless.out"
          _ (.mkdirs (java.io.File. "build/j6e-logs"))
          pid (str/trim (:out ((requiring-resolve 'jolt.process/sh)
                               "sh" "-c"
                               (str ". build/tools/jolt-env.sh && (\"$JOLT_HOME/bin/jolt\" -Srepro "
                                    "-M:jolt:jolt-yin-repl --no-state --port 0 --headless > " out
                                    " 2>&1 & echo $!)"))))
          deadline (+ (System/currentTimeMillis) 120000)
          url (loop []
                (let [text (try (slurp out) (catch Throwable _ ""))
                      m (re-find #"Serving (daostream:ws://[^\s]+)" text)]
                  (cond
                    m (second m)
                    (> (System/currentTimeMillis) deadline) nil
                    :else (do (Thread/sleep 100) (recur)))))
          adapter (host/websocket)
          state (atom (main/boot {:adapter adapter}))
          lines (atom [])]
      (record "C9 headless child pid" pid "url" url)
      (when url
        (repl-driver/submit-line! (:input @state) (str "(connect \"" url "\")"))
        (repl-driver/submit-line! (:input @state) "(+ 40 2)")
        (let [deadline (+ (poller/clock-ms) 20000)]
          (loop []
            (wake/sleep-fds! w 10 (:pump adapter) nil)
            (let [[s _ ls] (main/step-all @state nil (System/currentTimeMillis))]
              (reset! state s)
              (swap! lines into ls))
            (when (and (not (some #(= "42" %) @lines)) (< (poller/clock-ms) deadline))
              (recur)))))
      (check "C9 (R11): a --headless --port process answers a connected peer's evaluation"
             (and url (some #(= "42" %) @lines)) {:url url, :lines @lines})
      ((requiring-resolve 'jolt.process/sh) "kill" pid)
      ((:close-host! adapter))
      (check "C9 (R11): the peer adapter's registry is empty after close-host!"
             (= empty-registry (select-keys ((:debug-state adapter)) [:fds :buffers? :notify])) {}))
    (wake/close! w)))


;; =============================================================================
;; fairness (C10)
;; =============================================================================

(defn- section-fairness
  []
  (let [w (wake/make-wake)
        inst (wsj/create)
        {:keys [port accepted]} (listen! inst {})
        peer (raw-request! w [inst] port (request-head port))
        frame (octets (client-frame :binary (byte-array 1000)))
        frames (atom (vec (repeat 4000 frame)))
        ;; never waits (the server is pumped on this same thread): a frame
        ;; written in part keeps its unsent tail at the head for next time
        top-up! (fn []
                  (loop []
                    (when-let [f (first @frames)]
                      (let [n (write-some! (:fd peer) f)]
                        (cond
                          (= n (alength f)) (do (swap! frames subvec 1) (recur))
                          (pos? n) (swap! frames assoc 0 (byte-array (drop n f)))
                          :else nil)))))
        state (atom (main/boot {}))
        delivered #(count (filter (fn [e] (= :binary (first e))) @(:events (first @accepted))))]
    (drive! w [inst] #(seq @accepted) 3000)
    (record "C10 upgraded" (count @accepted))
    (top-up!)
    (record "C10 primed, frames left" (count @frames))
    (let [hp (wsj/host-pump inst)
          rounds (vec (for [_ (range 8)]
                        ;; REPL work is runnable at every round: lines are
                        ;; queued before it (a deep input queue makes
                        ;; step-all itself slow, which is not this gate)
                        (let [f0 (delivered)]
                          (dotimes [_ 4] (repl-driver/submit-line! (:input @state) "(+ 1 1)"))
                          (wake/sleep-fds! w 50 hp nil)
                          (let [[s _ ls] (main/step-all @state nil (System/currentTimeMillis))]
                            (reset! state s)
                            (top-up!)
                            {:frames (- (delivered) f0), :lines (count ls)}))))]
      (record "C10 rounds" (pr-str rounds))
      (check "C10: a readable connection plus runnable REPL work: frames and REPL steps both advance every round"
             (every? #(and (pos? (:frames %)) (pos? (:lines %))) rounds) {:rounds rounds}))
    (raw-close peer)
    (wsj/close-host! inst)
    (check "C10: registry empty after close-host!" (= empty-registry (registry inst)) (registry inst))
    (wake/close! w)))


;; =============================================================================
;; shutdown (C7)
;; =============================================================================

(defn- shutdown-run!
  "(quit) through main/run-owner! with peers on a second instance. `kind`
   :full (two REPL peers, a non-reading peer and a peer that keeps sending,
   a ping every 15 ms), :flood (the same, but the peer writes every round,
   so the server is readable at every poll; since R17 a round lasts its
   tick, so this drains as :full does, under the same gate) or :budget (one
   non-reading peer, stop-ticks 1)."
  [kind]
  (let [w (wake/make-wake)
        w2 (wake/make-wake)
        port (atom nil)
        stamps (atom [])
        unbind-at (atom nil)
        stuck (atom #{})
        stamp! (fn [k] (swap! stamps conj [k (poller/clock-ms)]))
        base (host/websocket)
        adapter (-> base
                    (update :bind! (fn [f]
                                     (fn [o]
                                       (f (update o :deposit!
                                                  (fn [d]
                                                    (fn [k v]
                                                      (when (= :bind-succeeded k) (reset! port (:port v)))
                                                      (stamp! k)
                                                      (d k v))))))))
                    (update :unbind! (fn [f]
                                       (fn [l d]
                                         (reset! unbind-at (poller/clock-ms))
                                         (f l (fn [k v] (stamp! k) (d k v))))))
                    ;; the scripted EAGAIN stands for a peer whose buffer is
                    ;; full; its kernel socket is still writable, so POLLOUT is
                    ;; masked as the kernel would for a really full one
                    (update-in [:pump :interest]
                               (fn [f]
                                 (fn []
                                   (mapv (fn [[fd ev]]
                                           [fd (if (contains? @stuck fd) (bit-and ev (bit-not native/pollout)) ev)])
                                         (f))))))
        started (main/startup ["--no-state" "--port" "0" "--headless"] {:adapter adapter})
        {:keys [state server]} started
        peer-codes (atom [])
        peer-base (host/websocket)
        peer-adapter (update peer-base :connect! (fn [f] (fn [d cb] (f d (wrap-closes cb peer-codes)))))
        peers (atom [])          ; REPL peer states
        peer-lines (atom [[] []])
        stuck-peer (atom nil)
        busy-peer (atom nil)
        stuck-gone-at (atom nil)
        phase (atom :boot)
        quit-at (atom nil)
        server-fds #(set (:fds ((:debug-state adapter))))
        fds-before (atom nil)
        step-peers! (fn []
                      (wake/sleep-fds! w2 0 (:pump peer-adapter) nil)
                      (swap! peers (fn [ps]
                                     (vec (map-indexed
                                            (fn [i s]
                                              (let [[s' _ ls] (main/step-all s nil (System/currentTimeMillis))]
                                                (swap! peer-lines update i into ls)
                                                s'))
                                            ps)))))
        last-ping (atom 0)
        rounds (atom 0)
        script! (fn []
                  (swap! rounds inc)
                  ;; :full pings every 15 ms of wall time; :flood every round,
                  ;; so the server is readable at every poll
                  (when-let [p @busy-peer]
                    (when (or (= :flood kind) (>= (- (poller/clock-ms) @last-ping) 15))
                      (reset! last-ping (poller/clock-ms))
                      (write-some! (:fd p) (octets (client-frame :ping (byte-array [1 2 3])))))
                    (raw-poll! p))
                  (when (and @stuck-peer (not @stuck-gone-at) @unbind-at
                             (not (contains? (server-fds) (:fd-server @stuck-peer))))
                    (reset! stuck-gone-at (poller/clock-ms)))
                  (case @phase
                    :boot (when @port
                            (reset! fds-before (server-fds))
                            (let [p (raw-peer! @port)]
                              (write-all! (:fd p) (text/utf8-encode (request-head @port :protocols "dao.stream.cbor")))
                              (reset! stuck-peer p))
                            (reset! phase :stuck-upgrading))
                    :stuck-upgrading (do (raw-poll! @stuck-peer)
                                         (when (head-end @(:in @stuck-peer))
                                           (let [fd (first (remove @fds-before (server-fds)))]
                                             (swap! stuck-peer assoc :fd-server fd)
                                             (reset! stuck #{fd}))
                                           (if (= :budget kind)
                                             (reset! phase :ready)
                                             (let [p (raw-peer! @port)]
                                               (write-all! (:fd p) (text/utf8-encode
                                                                     (request-head @port :protocols "dao.stream.cbor")))
                                               (reset! busy-peer p)
                                               (dotimes [i 2]
                                                 (let [s (main/boot {:adapter peer-adapter})]
                                                   (repl-driver/submit-line!
                                                     (:input s) (str "(connect \"daostream:ws://127.0.0.1:" @port "/repl\")"))
                                                   (repl-driver/submit-line! (:input s) (str "(+ 40 " i ")"))
                                                   (swap! peers conj s)))
                                               (reset! phase :peering)))))
                    :peering (do (step-peers!)
                                 (when (and (some #(= "40" %) (nth @peer-lines 0))
                                            (some #(= "41" %) (nth @peer-lines 1)))
                                   (reset! phase :ready)))
                    :ready (do (when (seq @peers) (step-peers!))
                               (reset! quit-at [(poller/clock-ms) @rounds])
                               (repl-driver/submit-line! (:input state) "(quit)")
                               (reset! phase :quitting))
                    :quitting (when (seq @peers) (step-peers!))))
        exit (atom nil)
        ticks (atom 0)
        shutdown-tick main/shutdown-tick
        output (with-out-str
                 (with-redefs-fn (cond-> {send-var (scripted-send stuck (constantly :eagain))
                                          ;; one call per drain round (R17: sleeps outnumber rounds)
                                          #'main/shutdown-tick (fn [& a] (swap! ticks inc) (apply shutdown-tick a))}
                                   (= :budget kind) (assoc #'main/stop-ticks 1))
                   (fn []
                     (main/run-owner! state server true #(System/currentTimeMillis)
                                      (fn [ms]
                                        (wake/sleep-fds! w (min ms 20) (:pump adapter) nil)
                                        (script!))
                                      (fn [status]
                                        (let [ds ((:debug-state adapter))]
                                          (reset! exit {:status status, :debug-state ds
                                                        :at (poller/clock-ms)})
                                          ((:close-host! adapter))
                                          (swap! exit assoc :after ((:debug-state adapter)))))))))]
    (record (str "C7 " (name kind) " owner output:") (pr-str (str/split-lines output)))
    ;; let the peers observe their ends
    (dotimes [_ 40]
      (wake/sleep-fds! w2 5 (:pump peer-adapter) nil)
      (when (seq @peers) (step-peers!))
      (when-let [p @busy-peer] (raw-poll! p)))
    (let [{:keys [status debug-state after at]} @exit
          forced (set (:fds debug-state))
          path #(if (seq forced) :forced :drained)
          report (cond-> {}
                   @stuck-peer (assoc :non-reading (if (contains? forced (:fd-server @stuck-peer)) :forced :drained))
                   @busy-peer (assoc :readable (path))
                   (seq @peers) (assoc :repl-peers (path)))
          stopped (some (fn [[k t]] (when (= :stopped k) t)) @stamps)
          [quit-ms quit-round] @quit-at]
      (record (str "C7 " (name kind) " report:")
              (pr-str {:connections report, :exit status, :forced-fds (sort forced)
                       :drain-ms (when (and at quit-ms) (- at quit-ms))
                       :drain-rounds @ticks, :drain-sleeps (when quit-round (- @rounds quit-round))
                       :unbind-after-quit-ms (when (and @unbind-at quit-ms) (- @unbind-at quit-ms))
                       :stuck-gone-after-unbind-ms (when (and @stuck-gone-at @unbind-at) (- @stuck-gone-at @unbind-at))
                       :stamps @stamps, :peer-codes @peer-codes, :debug-state debug-state}))
      (case kind
        (:full :flood)
        (let [l (str "C7 " (name kind) ": ")]
          (check (str l ":stopped is deposited before the unconfirmed verdict (Endpoint stopped: confirmed)")
                 (and (some? stopped) (str/includes? output "Endpoint stopped: confirmed")
                      (not (str/includes? output "unconfirmed")))
                 {:stopped stopped})
          (check (str l "both acknowledged peers receive 4000 and deposit :ws/ended")
                 (= [4000 4000] @peer-codes) {:peer-codes @peer-codes})
          (when (= :flood kind)
            (let [p @busy-peer
                  v @(:in p)
                  frames (when-let [e (head-end v)] (server-frames (subvec v e)))
                  close (some #(when (= :close (:type %)) %) frames)]
              ;; the acceptor adopts every upgraded connection on its next
              ;; tick (ws_project accept-step!), so this raw peer is an
              ;; acknowledged session by quit time, and the stop closes it
              ;; as ended (4000); endpoint-stop!'s 1001 reaches only a slot
              ;; still pending at stop
              (check (str l "the flooding peer receives the composition's close frame (4000 dao.stream/ended), not a bare drop")
                     (and (= 4000 (:code close)) (= "dao.stream/ended" (:reason close))) {:close close})))
          (check (str l "the non-reading peer (pending bytes) is shut within the 1500 ms unbind grace plus one round")
                 (and @stuck-gone-at @unbind-at (<= (- @stuck-gone-at @unbind-at) 1800))
                 {:ms (when (and @stuck-gone-at @unbind-at) (- @stuck-gone-at @unbind-at))})
          (check (str l "the registry is empty before close-host! (every connection :drained) and :unbind-closed is 0")
                 (and (= [] (:fds debug-state)) (zero? (:unbind-closed debug-state))
                      (every? #{:drained} (vals report)))
                 {:debug-state debug-state, :report report})
          (check (str l "the drain used fewer rounds than stop-ticks and the timeout text is absent")
                 (and (< @ticks main/stop-ticks) (not (str/includes? output main/stop-timeout-text)))
                 {:rounds @ticks, :stop-ticks main/stop-ticks})
          (check (str l "exit 0") (= 0 status) {:status status}))
        :budget
        (do
          (check "C7 budget: with stop-ticks 1 the budget expires first and the report says forced"
                 (and (str/includes? output main/stop-timeout-text) (= :forced (:non-reading report)))
                 {:report report})
          (check "C7 budget: exit 0" (= 0 status) {:status status})))
      (check (str "C7 " (name kind) ": close-host! leaves nothing open")
             (= empty-registry (select-keys after [:fds :buffers? :notify])) {:after after}))
    (doseq [p [@stuck-peer @busy-peer] :when p] (raw-close p))
    ((:close-host! peer-adapter))
    (check (str "C7 " (name kind) ": the peer instance's registry is empty after close-host!")
           (= empty-registry (select-keys ((:debug-state peer-adapter)) [:fds :buffers? :notify])) {})
    (wake/close! w)
    (wake/close! w2)))


(defn- section-shutdown
  []
  (shutdown-run! :full)
  (shutdown-run! :flood)
  (shutdown-run! :budget))


(def sections
  {"closure" section-closure
   "handshake" section-handshake
   "bind" section-bind
   "address" section-address
   "io" section-io
   "close" section-close
   "shutdown" section-shutdown
   "isolation" section-isolation
   "driver" section-driver
   "fairness" section-fairness})


(let [names (or (seq *command-line-args*) (sort (keys sections)))]
  (doseq [nm names]
    (if-let [f (get sections nm)]
      (try (f)
           (catch Throwable e
             (check (str nm ": section completed without throwing") false
                    {:message (ex-message e), :data (ex-data e)})))
      (check (str "unknown section " nm) false {})))
  (println "SUMMARY" (pr-str {:sections (vec names), :failures @failures}))
  (System/exit (if (zero? @failures) 0 1)))
