;; bb probe:jolt-tcp: the J6d gates for the jolt TCP substrate and owner-thread
;; pump (J6 plan, J6d row; rulings A1-A5 and their amendments), one section
;; per child jolt process so an abort cannot erase another section's report:
;;
;;   closure    the substrate loads jolt.socket.native, jolt.io-poller and
;;              jolt.ffi, never jolt.socket;
;;   address    parse-address over IPv4, IPv6, bracketed IPv6, localhost, a
;;              malformed literal, a host name and a bad port, plus listen!
;;              and connect! by literal, all under a resolver trap
;;              (native/resolve-addrs and c-getaddrinfo rebound to throw);
;;   substrate  port 0 reports the actual port; an accepted fd is nonblocking
;;              (Linux does not inherit it); nonblocking connect and its
;;              SO_ERROR outcome, refused included; EOF and reset are distinct
;;              classes; a write to a gone peer is an error and the process
;;              survives;
;;   ordered    A3: M1/M2/M3 behind a 7-byte short write then EAGAIN, exact
;;              :queued-bytes, drain in order byte-exact at the peer; a drain
;;              failure on M2 is one terminal, :queued-bytes 0, send! of M4
;;              answers closed; an immediate write failure is reported on the
;;              next turn;
;;   fairness   A1 (ii) A endlessly readable (scripted) and B with pending
;;              bytes and a writable socket: B drains to zero in one turn, A
;;              consumes exactly one chunk per turn; (iv) a flooding peer (real
;;              bytes, then scripted): one chunk per visit per turn, never
;;              more;
;;   stdin      A1 (i) through driver/sleep-fds! with fd 0 replaced by a pipe:
;;              a held partial line plus a readable connection delivers the
;;              connection's bytes and keeps the partial line; a later newline
;;              delivers exactly one line; end of input delivers the
;;              unterminated tail and then nil;
;;   deadline   A2: :deadline is nil on an empty registry and a round waits the
;;              cadence; an upgrade deadline 50 ms before a 1000 ms cadence
;;              wakes the round at about 50 ms and pump! runs with ready []
;;              (each round through driver/sleep-fds!, which folds the
;;              deadline since J6e);
;;   control    A3 both event orders (ping then M2, M2 then ping) with exact
;;              :queued-bytes; A4 ten pings whole and octet-wise, a ping behind
;;              a short-written pong, 17 pings against a non-writable socket
;;              (16 pongs, read interest cleared, resume before read); D1 the
;;              reserved close slot under saturation, ended by readiness and by
;;              the deadline;
;;   close      A5 scripted close cases, D2 peer-first deadline and its
;;              readiness companion, D3 the five close payloads and the
;;              1005/1006 refusal, J6e R12 a parser failure after a requested
;;              close (still queued, already sent);
;;   listener   the 64 pending-upgrade cap against 80 clients (completion and
;;              expiry each admit exactly one), lower-deadlines!, drained?.
;;
;; Every section but closure, address and substrate runs under a native-memory
;; ledger (jolt.ffi alloc, string->ptr and free wrapped) and ends with the pump
;; registry and native buffers empty and every allocation freed. The
;; :ws-mode cases use a stand-in for the J6b frame core (see below). The scripted transfer seam is
;; dao.stream.ws.tcp's single-transfer vars (send-once, recv-once), redefined
;; here; production has no fault mode.
;;
;; Run: bb probe:jolt-tcp (all sections), or under jolt directly:
;;   jolt -Srepro -M:jolt src/dev/jolt_tcp_probe.clj <section>
;; Prints PASS/FAIL per gate and RECORD per observation; exits nonzero on any
;; FAIL.
(ns jolt-tcp-probe
  (:require [jolt.ffi :as ffi]
            [jolt.io-poller :as poller]
            [jolt.socket.native :as native]))


(require '[dao.host.text :as text]
         '[dao.stream.ws.tcp :as tcp]
         '[dao.stream.ws.pump :as pump]
         '[dao.stream.waitset.driver :as driver])


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(defn- record
  [& xs]
  (println (apply str "RECORD " (interpose " " xs))))


(ffi/load-library)
(ffi/defcfn c-dup "dup" [:int] :int)
(ffi/defcfn c-dup2 "dup2" [:int :int] :int)
(ffi/defcfn c-pipe "pipe" [:pointer] :int)


(def send-var (ns-resolve 'dao.stream.ws.tcp 'send-once))
(def recv-var (ns-resolve 'dao.stream.ws.tcp 'recv-once))
(def orig-send @send-var)
(def orig-recv @recv-var)


;; =============================================================================
;; Native memory ledger
;; =============================================================================

(def ledger (atom {:allocs 0, :frees 0, :live #{}, :double-free 0}))
(def orig-alloc ffi/alloc)
(def orig-free ffi/free)


(defn- counting-alloc
  [& args]
  (let [p (apply orig-alloc args)]
    (swap! ledger #(-> % (update :allocs inc) (update :live conj (ffi/address p))))
    p))


;; jolt.socket.native/make-sockaddr copies the address text with string->ptr
;; and releases it with free, so both allocation entries are counted
(def orig-string->ptr ffi/string->ptr)


(defn- counting-string->ptr
  [& args]
  (let [p (apply orig-string->ptr args)]
    (swap! ledger #(-> % (update :allocs inc) (update :live conj (ffi/address p))))
    p))


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
  (with-redefs-fn {#'ffi/alloc counting-alloc, #'ffi/string->ptr counting-string->ptr
                   #'ffi/free counting-free}
    f)
  (let [{:keys [allocs frees live double-free]} @ledger]
    (check (str label ": every native allocation freed, none twice")
           (and (pos? allocs) (empty? live) (zero? double-free))
           {:allocs allocs, :frees frees, :live (count live), :double-free double-free})))


;; =============================================================================
;; Helpers
;; =============================================================================

(defn- loopback
  []
  (tcp/parse-address "127.0.0.1" 0))


(defn- wait-fd
  [fd events ms]
  (native/poll-one fd events ms))


(defn- pair!
  "A real loopback connection through tcp/listen!, connect! and accept!:
   {:a dialer fd, :b accepted fd}, both nonblocking; the listener closed."
  []
  (let [{lfd :fd, port :port} (tcp/listen! (loopback))
        {:keys [fd state]} (tcp/connect! (tcp/parse-address "127.0.0.1" port))]
    (wait-fd lfd native/pollin 2000)
    (let [acc (tcp/accept! lfd)]
      (tcp/close! lfd)
      (when (= state :connecting)
        (wait-fd fd native/pollout 2000)
        (assert (= :connected (tcp/connect-result fd))))
      {:a fd, :b (:fd acc)})))


(defn- pattern
  "A distinguishable message: k, then n-1 bytes of a k-seeded ramp."
  [k n]
  (let [bs (byte-array n)]
    (dotimes [i n] (aset bs i (unchecked-byte (if (zero? i) k (+ (* k 31) i)))))
    bs))


(defn- bvec
  [bs]
  (mapv #(bit-and % 0xff) bs))


(defn- with-native
  [n f]
  (let [p (ffi/alloc (max 1 n))]
    (try (f p) (finally (ffi/free p)))))


(defn- write-all!
  "Write all of `bs` to nonblocking `fd`, waiting for writability; answers
   the bytes written."
  [fd bs]
  (with-native (alength bs)
    (fn [p]
      (ffi/write-array p bs)
      (loop [off 0]
        (if (= off (alength bs))
          off
          ;; the peer's own writes bypass the scripted seam
          (let [[n e] (orig-send fd (+ p off) (- (alength bs) off))]
            (cond
              (not (neg? n)) (recur (+ off n))
              (or (native/eagain? e) (native/eintr? e))
              (do (wait-fd fd native/pollout 2000) (recur off))
              :else off)))))))


(defn- read-n!
  "Read until `n` bytes or end of stream or `ms` pass; answers {:bytes v
   :end r} where :end is the terminating condition."
  [fd n ms]
  (with-native 65536
    (fn [p]
      (let [deadline (+ (poller/clock-ms) ms)]
        (loop [acc []]
          (if (>= (count acc) n)
            {:bytes acc, :end :count}
            (let [left (long (- deadline (poller/clock-ms)))]
              (if (<= left 0)
                {:bytes acc, :end :timeout}
                (do (wait-fd fd native/pollin left)
                    (let [r (tcp/recv-chunk! fd p 65536)]
                      (cond
                        (= :would-block r) (recur acc)
                        (= :eof r) {:bytes acc, :end :eof}
                        (:error r) {:bytes acc, :end r}
                        :else (recur (into acc (bvec (ffi/read-array p (:n r))))))))))))))))


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


(defn- scripted-send
  "send-once that answers per call from `script` (a fn of the call number
   and the request size returning :real, [:short k], :eagain or :epipe),
   counting calls into `calls`."
  [calls script]
  (fn [fd buf n]
    (let [i (swap! calls inc)
          step (script i n)]
      (cond
        (= :real step) (orig-send fd buf n)
        (= :eagain step) [-1 native/eagain]
        (= :epipe step) [-1 (or native/epipe native/econnreset)]
        :else (orig-send fd buf (min n (second step)))))))


(defn- drain-rounds!
  "Drive `p` through driver/sleep-fds! (real poll) until `h`'s queue is empty
   or `max` rounds; answers the rounds used."
  [w p h max]
  (loop [i 0]
    (if (or (zero? (pump/queued-bytes h)) (= i max))
      i
      (do (driver/sleep-fds! w 200 (pump/host-pump p) nil) (recur (inc i))))))


;; =============================================================================
;; address (A6)
;; =============================================================================

(defn- section-address
  []
  (let [calls (atom 0)
        trap (fn [& _] (swap! calls inc) (throw (ex-info "resolver called" {})))]
    (with-redefs-fn
      {#'native/resolve-addrs trap, #'native/c-getaddrinfo trap}
      (fn []
        (let [cases [["127.0.0.1" 80 {:family native/af-inet, :ip "127.0.0.1", :port 80}]
                     ["::1" 443 {:family native/af-inet6, :ip "::1", :port 443}]
                     ["[::1]" 8080 {:family native/af-inet6, :ip "::1", :port 8080}]
                     ["0:0:0:0:0:0:0:1" 1 {:family native/af-inet6, :ip "::1", :port 1}]
                     ["localhost" 9000 {:family native/af-inet, :ip "127.0.0.1", :port 9000}]
                     ["LocalHost" 0 {:family native/af-inet, :ip "127.0.0.1", :port 0}]
                     ["256.1.1.1" 80 {:error :malformed-address}]
                     ["1.2.3" 80 {:error :malformed-address}]
                     [":::1" 80 {:error :malformed-address}]
                     ["example.com" 80 {:error :host-name-unsupported}]
                     ["my-host" 80 {:error :host-name-unsupported}]
                     ["127.0.0.1" 70000 {:error :invalid-port}]
                     ["127.0.0.1" -1 {:error :invalid-port}]]]
          (doseq [[host port want] cases]
            (let [got (tcp/parse-address host port)]
              (check (str "address: parse-address " (pr-str host) " " port)
                     (= want got) {:got got}))))
        (let [{lfd :fd port :port :as l} (tcp/listen! (tcp/parse-address "localhost" 0))
              c (tcp/connect! (tcp/parse-address "localhost" port))
              c6 (tcp/connect! (tcp/parse-address "::1" port))]
          (check "address: listen! and connect! on localhost by literal"
                 (and lfd (pos? port) (:fd c)) {:listen l, :connect c})
          (record "address: ::1 dial to a v4 listener" (pr-str (select-keys c6 [:state :error :class])))
          (some-> (:fd c) tcp/close!)
          (some-> (:fd c6) tcp/close!)
          (some-> lfd tcp/close!))))
    (check "address: the resolver trap was never sprung" (zero? @calls) {:calls @calls})))


;; =============================================================================
;; substrate
;; =============================================================================

(defn- section-substrate
  []
  (record "platform" native/os "accept-inherits-nonblocking?" native/accept-inherits-nonblocking?)
  ;; port 0
  (let [{lfd :fd port :port} (tcp/listen! (loopback))
        c (tcp/connect! (tcp/parse-address "127.0.0.1" port))]
    (check "substrate: port 0 reports the actual port"
           (and (pos? port) (= port (tcp/local-port lfd))) {:port port})
    (check "substrate: the listener fd is nonblocking" (tcp/nonblocking? lfd) {})
    (record "substrate: connect state" (:state c))
    (wait-fd lfd native/pollin 2000)
    (let [acc (tcp/accept! lfd)]
      (check "substrate: accept! answers a connection on the reported port"
             (and (:fd acc) (= "127.0.0.1" (get-in acc [:peer :ip]))) (dissoc acc :fd))
      (check "substrate: the accepted fd is nonblocking (explicitly, Linux included)"
             (tcp/nonblocking? (:fd acc)) {:platform native/os})
      (check "substrate: a second accept! with none pending answers :would-block"
             (= :would-block (tcp/accept! lfd)) {})
      (wait-fd (:fd c) native/pollout 2000)
      (check "substrate: the dial's SO_ERROR outcome is :connected"
             (= :connected (tcp/connect-result (:fd c))) {})
      (check "substrate: the dialed fd is nonblocking" (tcp/nonblocking? (:fd c)) {})
      (tcp/close! (:fd acc)))
    (tcp/close! (:fd c))
    (tcp/close! lfd))
  ;; refused: a port nobody listens on
  (let [{lfd :fd port :port} (tcp/listen! (loopback))
        _ (tcp/close! lfd)
        c (tcp/connect! (tcp/parse-address "127.0.0.1" port))
        outcome (if (:error c)
                  c
                  (do (wait-fd (:fd c) native/pollout 2000)
                      (let [r (tcp/connect-result (:fd c))]
                        (tcp/close! (:fd c))
                        r)))]
    (check "substrate: a refused dial is a :connect-failed of class :refused, never a wait"
           (and (= :connect-failed (:error outcome)) (= :refused (:class outcome)))
           (select-keys outcome [:error :class :errno])))
  ;; EOF versus reset
  (let [{:keys [a b]} (pair!)]
    (tcp/close! b)
    (let [r (read-n! a 1 2000)]
      (check "substrate: an orderly peer close reads as :eof" (= :eof (:end r)) r))
    (tcp/close! a))
  (let [{:keys [a b]} (pair!)]
    (set-linger-zero! b)
    (tcp/close! b)
    (let [r (read-n! a 1 2000)]
      (check "substrate: an aborted peer (RST) reads as :recv-failed of class :reset, distinct from :eof"
             (and (map? (:end r)) (= :recv-failed (:error (:end r))) (= :reset (:class (:end r))))
             (:end r)))
    (tcp/close! a))
  ;; a write to a gone peer
  (let [{:keys [a b]} (pair!)
        payload (pattern 9 4096)]
    (set-linger-zero! b)
    (tcp/close! b)
    (wait-fd a native/pollin 2000)
    (let [r (with-native 4096
              (fn [p]
                (ffi/write-array p payload)
                (loop [i 0]
                  (let [r (tcp/send-chunk! a p 4096)]
                    (if (or (:error r) (= i 50)) r (recur (inc i)))))))]
      (check "substrate: a write to a gone peer is a :send-failed error (broken pipe or reset)"
             (and (= :send-failed (:error r)) (#{:broken-pipe :reset} (:class r)))
             (select-keys r [:error :class :errno]))
      (check "substrate: the process survives the write to a gone peer (no SIGPIPE)" true {}))
    (tcp/close! a)))


;; =============================================================================
;; ordered (A3)
;; =============================================================================

(defn- events-cbs
  [events]
  {:on-error #(swap! events conj [:error (select-keys % [:error :class])])
   :on-eof #(swap! events conj [:eof])
   :on-drained #(swap! events conj [:drained])})


(defn- section-ordered
  []
  (let [w (driver/make-wake)]
    (with-ledger
      "ordered"
      (fn []
        ;; M1/M2/M3 behind a 7-byte short write, then drain
        (let [{:keys [a b]} (pair!)
              p (pump/create)
              events (atom [])
              h (pump/connection! p a (events-cbs events))
              m1 (pattern 1 3000) m2 (pattern 2 5000) m3 (pattern 3 1200)
              calls (atom 0)
              answers (with-redefs-fn
                        {send-var (scripted-send calls (fn [i _] (if (= i 1) [:short 7] :eagain)))}
                        (fn [] [(pump/send! p h m1) (pump/send! p h m2) (pump/send! p h m3)]))
              queued (pump/queued-bytes h)]
          (check "ordered: three sends behind a short write are accepted (nil each)"
                 (= [nil nil nil] answers) answers)
          (check "ordered: only M1 attempted a write (M2, M3 queued behind pending bytes)"
                 (= 1 @calls) {:send-calls @calls})
          (check "ordered: :queued-bytes = len(M1)+len(M2)+len(M3)-7"
                 (= queued (- (+ 3000 5000 1200) 7)) {:queued queued})
          (let [rounds (drain-rounds! w p h 50)
                got (read-n! b 9200 3000)]
            (check "ordered: readiness drains the queue to 0"
                   (zero? (pump/queued-bytes h)) {:rounds rounds})
            (check "ordered: the peer reads M1 M2 M3 byte-exact, in order"
                   (= (:bytes got) (vec (concat (bvec m1) (bvec m2) (bvec m3))))
                   {:read (count (:bytes got)), :end (:end got)})
            (check "ordered: :on-drained once, no terminal"
                   (= [[:drained]] @events) @events))
          (pump/discard! p h)
          (check "ordered: after discard! :queued-bytes 0 and send! answers closed"
                 (and (zero? (pump/queued-bytes h))
                      (= pump/closed-outcome (pump/send! p h m1)))
                 {})
          (check "ordered: registry and buffers empty after close"
                 (= {:fds [], :buffers? false, :notify 0} (pump/debug-state p))
                 (pump/debug-state p))
          (tcp/close! b))
        ;; a failure on M2's drain
        (let [{:keys [a b]} (pair!)
              p (pump/create)
              events (atom [])
              h (pump/connection! p a (events-cbs events))
              m1 (pattern 1 3000) m2 (pattern 2 5000) m3 (pattern 3 1200) m4 (pattern 4 100)
              calls (atom 0)
              script (fn [i _] (case i 1 [:short 7] 2 :real :epipe))]
          (with-redefs-fn
            {send-var (scripted-send calls script)}
            (fn []
              (pump/send! p h m1)
              (pump/send! p h m2)
              (pump/send! p h m3)
              (dotimes [_ 4] (pump/pump! p [[a native/pollout]]))))
          (check "ordered: a drain failure on M2 is exactly one terminal"
                 (= 1 (count (filter #(= :error (first %)) @events))) @events)
          (check "ordered: after the failure :queued-bytes is 0"
                 (zero? (pump/queued-bytes h)) {:queued (pump/queued-bytes h)})
          (check "ordered: send! of M4 after the failure answers closed"
                 (= pump/closed-outcome (pump/send! p h m4)) {})
          (dotimes [_ 2] (pump/pump! p []))
          (check "ordered: no second terminal on later turns"
                 (= 1 (count (filter #(= :error (first %)) @events))) @events)
          (let [got (read-n! b 100000 2000)]
            (check "ordered: the peer read exactly M1, then end of stream"
                   (and (= (:bytes got) (bvec m1)) (= :eof (:end got)))
                   {:read (count (:bytes got)), :end (:end got)}))
          (check "ordered: registry and buffers empty after the failure"
                 (= {:fds [], :buffers? false, :notify 0} (pump/debug-state p))
                 (pump/debug-state p))
          (tcp/close! b))
        ;; a failure inside send! is reported on the next turn, not from send!
        (let [{:keys [a b]} (pair!)
              p (pump/create)
              events (atom [])
              h (pump/connection! p a (events-cbs events))
              calls (atom 0)
              answer (with-redefs-fn {send-var (scripted-send calls (fn [_ _] :epipe))}
                       (fn [] (pump/send! p h (pattern 5 10))))
              before @events
              owed (pump/next-deadline p)]
          (pump/pump! p [])
          (check "ordered: an immediate write failure answers closed and sends nothing"
                 (and (= pump/closed-outcome answer) (zero? (pump/queued-bytes h))) {})
          (check "ordered: its terminal is owed (deadline now) and delivered on the next turn, once"
                 (and (empty? before) (some? owed) (= 1 (count @events))) {:before before, :after @events})
          (tcp/close! b))
        ;; :on-drained and :on-connected are owed reports: delivered by the
        ;; same pump! turn, after its visits, never from inside an entry
        (let [{:keys [a b]} (pair!)
              p (pump/create)
              events (atom [])
              h (pump/connection! p a (events-cbs events))
              calls (atom 0)]
          (with-redefs-fn {send-var (scripted-send calls (fn [i _] (if (= i 1) [:short 3] :eagain)))}
            (fn [] (pump/send! p h (pattern 6 100))))
          (pump/send! p h (pattern 7 10))
          (let [after-send @events]
            (pump/pump! p [[a native/pollout]])
            (let [after-first @events]
              (pump/pump! p [[a native/pollout]])
              (check "ordered: :on-drained is not called from send!, and is delivered within the draining pump! turn"
                     (and (empty? after-send) (empty? after-first) (= [[:drained]] @events))
                     {:after-send after-send, :after-first after-first, :after @events})))
          (pump/discard! p h)
          (tcp/close! b))
        (let [{lfd :fd port :port} (tcp/listen! (loopback))
              p (pump/create)
              connected (atom [])
              {:keys [fd state]} (tcp/connect! (tcp/parse-address "127.0.0.1" port))
              h (pump/connection! p fd {:connecting? true
                                        :on-connected #(swap! connected conj :connected)})]
          (record "ordered: dial state at registration" state)
          (wait-fd fd native/pollout 2000)
          (pump/pump! p [[fd native/pollout]])
          (check "ordered: :on-connected is delivered once within the turn that observed the connect"
                 (and (= [:connected] @connected) (pump/open? h)) {:connected @connected})
          (pump/pump! p [])
          (check "ordered: :on-connected is not repeated" (= [:connected] @connected) {:connected @connected})
          (pump/discard! p h)
          (tcp/close! lfd))))
    (driver/close! w)))


;; =============================================================================
;; fairness (A1 ii, iv)
;; =============================================================================

(defn- section-fairness
  []
  (with-ledger
    "fairness"
    (fn []
      ;; (ii) A endlessly readable, B pending and writable
      (let [pa (pair!) pb (pair!)
            a (:a pa) b (:a pb)
            p (pump/create)
            per-turn (atom 0)
            ha (pump/connection! p a {:on-data (fn [chunk] (swap! per-turn + (alength chunk)))})
            hb (pump/connection! p b {})
            calls (atom 0)]
        (with-redefs-fn
          {send-var (scripted-send calls (fn [_ _] :eagain))}
          (fn [] (pump/send! p hb (pattern 7 8000))))
        (check "fairness: B holds 8000 pending bytes before the turn"
               (= 8000 (pump/queued-bytes hb)) {:queued (pump/queued-bytes hb)})
        (let [turns (with-redefs-fn
                      {recv-var (fn [fd buf n]
                                  (if (= fd a) [n 0] (orig-recv fd buf n)))}
                      (fn []
                        (vec (for [i (range 5)]
                               (do (reset! per-turn 0)
                                   (pump/pump! p (if (even? i)
                                                   [[a native/pollin] [b native/pollout]]
                                                   [[b native/pollout] [a native/pollin]]))
                                   {:a @per-turn, :b-queued (pump/queued-bytes hb)})))))]
          (check "fairness (ii): B's queue drains to zero within the first turn"
                 (zero? (:b-queued (first turns))) (first turns))
          (check "fairness (ii): A consumes exactly one chunk per turn"
                 (every? #(= pump/chunk-bytes (:a %)) turns) turns))
        (let [got (read-n! (:b pb) 8000 3000)]
          (check "fairness (ii): B's peer received all 8000 bytes in order"
                 (= (:bytes got) (bvec (pattern 7 8000))) {:read (count (:bytes got))}))
        (pump/discard! p ha)
        (pump/discard! p hb)
        (tcp/close! (:b pa))
        (tcp/close! (:b pb))
        (check "fairness: registry and buffers empty after close"
               (= {:fds [], :buffers? false, :notify 0} (pump/debug-state p)) (pump/debug-state p)))
      ;; (iv) a flooding peer: real bytes
      (let [{:keys [a b]} (pair!)
            p (pump/create)
            per-turn (atom 0)
            h (pump/connection! p a {:on-data (fn [chunk] (swap! per-turn + (alength chunk)))})
            flood (pattern 3 65536)
            sent (with-native 65536
                   (fn [buf]
                     (ffi/write-array buf flood)
                     (loop [total 0]
                       (let [r (tcp/send-chunk! b buf 65536)]
                         (if (and (map? r) (:n r) (< total (* 8 1024 1024)))
                           (recur (+ total (:n r)))
                           total)))))
            turns (vec (for [_ (range 8)]
                         (do (reset! per-turn 0)
                             (wait-fd a native/pollin 1000)
                             (pump/pump! p [[a native/pollin]])
                             @per-turn)))]
        (record "fairness (iv): flood bytes buffered by the kernel" sent "per-turn" (pr-str turns))
        (check "fairness (iv): a real flood is consumed at most one chunk per visit per turn"
               (and (> sent pump/chunk-bytes) (every? #(<= 0 % pump/chunk-bytes) turns)
                    (pos? (first turns)) (<= (reduce + turns) sent))
               {:sent sent, :turns turns})
        (pump/discard! p h)
        (tcp/close! b))
      ;; (iv) scripted endless flood on two connections: one chunk each per turn
      (let [pa (pair!) pb (pair!)
            p (pump/create)
            seen (atom {})
            cb (fn [k] {:on-data (fn [chunk] (swap! seen update k (fnil + 0) (alength chunk)))})
            ha (pump/connection! p (:a pa) (cb :a))
            hb (pump/connection! p (:a pb) (cb :b))
            turns (with-redefs-fn
                    {recv-var (fn [_ _ n] [n 0])}
                    (fn []
                      (vec (for [_ (range 6)]
                             (do (reset! seen {})
                                 (pump/pump! p [[(:a pa) native/pollin] [(:a pb) native/pollin]])
                                 @seen)))))]
        (check "fairness (iv): two flooding peers each consume exactly one chunk per turn"
               (every? #(= {:a pump/chunk-bytes, :b pump/chunk-bytes} %) turns) turns)
        (pump/discard! p ha)
        (pump/discard! p hb)
        (tcp/close! (:b pa))
        (tcp/close! (:b pb))))))


;; =============================================================================
;; stdin (A1 i)
;; =============================================================================

(defn- pipe!
  []
  (with-native 8
    (fn [fds]
      (assert (zero? (c-pipe fds)))
      [(ffi/read fds :int 0) (ffi/read fds :int 4)])))


(defn- write-str!
  [fd s]
  (let [bs (text/utf8-encode s)]
    (with-native (alength bs)
      (fn [p] (ffi/write-array p bs) (poller/c-write fd p (alength bs))))))


(defn- section-stdin
  []
  (let [saved (c-dup 0)
        [r wr] (pipe!)]
    (c-dup2 r 0)
    (poller/c-close r)
    (try
      (with-ledger
        "stdin"
        (fn []
          (let [w (driver/make-wake)
                {:keys [a b]} (pair!)
                p (pump/create)
                data (atom [])
                lines (atom [])
                line! #(swap! lines conj %)
                h (pump/connection! p a {:on-data #(swap! data into (bvec %))})
                round! (fn []
                         (let [t0 (poller/clock-ms)]
                           (driver/sleep-fds! w 3000 (pump/host-pump p) line!)
                           (- (poller/clock-ms) t0)))]
            (write-str! wr "(+ 1")
            (write-all! b (text/utf8-encode "hello"))
            (let [ms (loop [i 0 ms 0]
                       (if (or (= 5 (count @data)) (= i 5)) ms (recur (inc i) (+ ms (round!)))))]
              (check "stdin (i): with a held partial line the connection's bytes are delivered promptly"
                     (and (= (bvec (text/utf8-encode "hello")) @data) (< ms 2000))
                     {:data (count @data), :ms ms})
              (check "stdin (i): the partial line stays buffered, nothing deposited"
                     (empty? @lines) {:lines @lines}))
            (write-all! b (text/utf8-encode " world"))
            (round!)
            (check "stdin (i): the connection keeps progressing while the line is held"
                   (and (= 11 (count @data)) (empty? @lines)) {:data (count @data), :lines @lines})
            (write-str! wr " 2)\n")
            (round!)
            (check "stdin (i): a later newline delivers exactly one line"
                   (= ["(+ 1 2)"] @lines) {:lines @lines})
            (write-str! wr "a\r\nb\nc")
            (round!)
            (check "stdin: several complete lines in one read are each deposited; CRLF trimmed; tail held"
                   (= ["(+ 1 2)" "a" "b"] @lines) {:lines @lines})
            (poller/c-close wr)
            (round!)
            (check "stdin: end of input deposits the unterminated tail, then nil"
                   (= ["(+ 1 2)" "a" "b" "c" nil] @lines) {:lines @lines})
            (pump/discard! p h)
            (tcp/close! b)
            (driver/close! w)
            (check "stdin: registry and buffers empty after close"
                   (= {:fds [], :buffers? false, :notify 0} (pump/debug-state p))
                   (pump/debug-state p)))))
      (finally
        (c-dup2 saved 0)
        (poller/c-close saved)))))


;; =============================================================================
;; main
;; =============================================================================

;; =============================================================================
;; Stand-in for the J6b frame core (until the slices merge)
;; =============================================================================
;; The interface assumed is J6b's resumable parser, (parse-frame state chunk
;; offset) => [event-or-nil state' offset'], with :message, :ping, :pong,
;; :close {:code nil|n :reason} and :fail {:code :reason} events, where a
;; failure decided by a close frame's payload carries :close? true and code
;; 1002 (plan D3); plus (encode-frame {:opcode :payload :mask-key}) and
;; (close-payload code reason). This stand-in parses unmasked final frames
;; with payloads under 64 KiB, octet by octet, so any split works.

(def ^:private sb-opcodes {:continuation 0 :text 1 :binary 2 :close 8 :ping 9 :pong 10})


(defn- sb-close-payload
  [code reason]
  (if (nil? code)
    (byte-array 0)
    (let [r (text/utf8-encode (or reason ""))
          out (byte-array (+ 2 (alength r)))]
      (aset out 0 (unchecked-byte (quot code 256)))
      (aset out 1 (unchecked-byte (mod code 256)))
      (dotimes [i (alength r)] (aset out (+ 2 i) (aget r i)))
      out)))


(defn- sb-encode-frame
  [{:keys [opcode payload]}]
  (let [op (get sb-opcodes opcode opcode)
        n (alength payload)
        head (if (< n 126)
               [(bit-or 0x80 op) n]
               [(bit-or 0x80 op) 126 (quot n 256) (mod n 256)])
        hn (count head)
        out (byte-array (+ hn n))]
    (dotimes [i hn] (aset out i (unchecked-byte (nth head i))))
    (dotimes [i n] (aset out (+ hn i) (aget payload i)))
    out))


(defn- sb-close-event
  [payload]
  (let [n (alength payload)
        accepted? #(or (<= 1000 % 1003) (<= 1007 % 1014) (<= 3000 % 4999))]
    (if (zero? n)
      {:type :close, :code nil, :reason ""}
      (if (= n 1)
        {:type :fail, :close? true, :code 1002, :reason "close payload of one byte"}
        (let [code (+ (* 256 (bit-and (aget payload 0) 0xff)) (bit-and (aget payload 1) 0xff))
              reason (try (text/utf8-decode payload 2 (- n 2)) (catch Throwable _ ::bad))]
          (cond
            (not (accepted? code)) {:type :fail, :close? true, :code 1002, :reason "close code"}
            (= ::bad reason) {:type :fail, :close? true, :code 1002, :reason "close reason"}
            :else {:type :close, :code code, :reason reason}))))))


(defn- sb-event
  [op payload]
  (case op
    9 {:type :ping, :payload payload}
    10 {:type :pong, :payload payload}
    8 (sb-close-event payload)
    1 {:type :message, :opcode :text, :payload payload}
    2 {:type :message, :opcode :binary, :payload payload}
    {:type :fail, :code 1002, :reason "stand-in: unsupported opcode"}))


(defn- sb-parse-frame
  [state chunk offset]
  (let [n (alength chunk)]
    (if (:done? state)
      [nil state n]
      (loop [buf (:buf state) i offset]
        (if (>= i n)
          [nil (assoc state :buf buf) n]
          (let [buf (conj buf (bit-and (aget chunk i) 0xff))
                c (count buf)
                hl (when (>= c 2) (if (= 126 (bit-and (nth buf 1) 127)) 4 2))
                len (when (and hl (>= c hl))
                      (if (= hl 4) (+ (* 256 (nth buf 2)) (nth buf 3)) (bit-and (nth buf 1) 127)))]
            (if (and len (= c (+ hl len)))
              (let [ev (sb-event (bit-and (nth buf 0) 15)
                                 (byte-array (map unchecked-byte (subvec buf hl))))]
                [ev (assoc state :buf [] :done? (contains? #{:close :fail} (:type ev))) (inc i)])
              (recur buf (inc i)))))))))


(def stand-in
  {:parser {:buf [], :done? false}
   :parse-frame sb-parse-frame
   :encode-frame sb-encode-frame
   :close-payload sb-close-payload})


(defn- wire
  "Concatenated wire bytes of stand-in frames, as an octet vector."
  [& frames]
  (vec (mapcat #(bvec (sb-encode-frame %)) frames)))


(defn- ping
  [k]
  {:opcode :ping, :payload (pattern k 5)})


(defn- pong
  [k]
  {:opcode :pong, :payload (pattern k 5)})


(defn- close-f
  [code reason]
  {:opcode :close, :payload (sb-close-payload code reason)})


(defn- ws-conn!
  "A :ws connection on `fd` through the stand-in, recording into `events`."
  ([p fd events] (ws-conn! p fd events {}))
  ([p fd events opts]
   (let [h (pump/connection! p fd (merge {:on-message #(swap! events conj [:message (bvec (:payload %))])
                                          :on-closed #(swap! events conj [:closed (:code %) (:cause %)])}
                                         opts))]
     (pump/upgraded! p h stand-in nil)
     h)))


(defn- terminals
  [events]
  (filterv #(= :closed (first %)) @events))


(defn- feed!
  "The peer writes `octets` and the pump takes one readable turn."
  [p a b octets]
  (write-all! b (byte-array (map unchecked-byte octets)))
  (wait-fd a native/pollin 2000)
  (pump/pump! p [[a native/pollin]]))


(def empty-registry {:fds [], :buffers? false, :notify 0})


;; =============================================================================
;; deadline (A2 seam)
;; =============================================================================

(defn- owner-round!
  "One owner round through the real driver: driver/sleep-fds! polls the
   pump's interest until min(cadence, :deadline) (the J6e folding) and
   pump!s the observed readiness, [] on a timeout; `calls` records it."
  [p cadence-ms calls]
  (let [w (driver/make-wake)
        hp (pump/host-pump p)
        d ((:deadline hp))
        t0 (poller/clock-ms)]
    (try
      (driver/sleep-fds! w cadence-ms
                         (assoc hp :pump! (fn [ready] (swap! calls conj ready) ((:pump! hp) ready)))
                         nil)
      {:ms (- (poller/clock-ms) t0), :ready (last @calls), :deadline d}
      (finally (driver/close! w)))))


(defn- section-deadline
  []
  (with-ledger
    "deadline"
    (fn []
      (let [p (pump/create)
            calls (atom [])]
        (check "deadline: :deadline answers nil with an empty registry"
               (nil? ((:deadline (pump/host-pump p)))) {})
        (let [r (owner-round! p 300 calls)]
          (check "deadline: with no adapter deadline the round waits the cadence alone and pump! runs with []"
                 (and (<= 280 (:ms r)) (= [] (:ready r))) (dissoc r :deadline)))
        ;; a pending upgrade that expires with no fd readiness
        (let [{lfd :fd} (tcp/listen! (loopback))
              expired (atom 0)
              lh (pump/listener! p lfd {:on-accept (fn [_])})
              {:keys [a b]} (pair!)
              h (pump/connection! p b {:listener lh, :upgrading? true, :upgrade-timeout-ms 50
                                       :on-upgrade-expired #(swap! expired inc)})
              d ((:deadline (pump/host-pump p)))
              _ (reset! calls [])
              r (owner-round! p 1000 calls)]
          (check "deadline: an adapter deadline 50 ms before a 1000 ms cadence wakes the round at about 50 ms"
                 (and (some? d) (<= 30 (:ms r) 500)) (dissoc r :deadline))
          (check "deadline: pump! ran with ready [] on that timeout"
                 (= [[]] @calls) @calls)
          (check "deadline: the pending upgrade expired with no fd readiness, reported once"
                 (and (= 1 @expired) (not (pump/open? h))) {:expired @expired})
          (pump/pump! p [])
          (check "deadline: no second expiry report" (= 1 @expired) {:expired @expired})
          (pump/discard! p lh)
          (tcp/close! a))
        (check "deadline: registry and buffers empty after close" (= empty-registry (pump/debug-state p))
               (pump/debug-state p))))))


;; =============================================================================
;; control (A3 event order, A4 control budget, D1 reserved close slot)
;; =============================================================================

(defn- eagain-after
  "A send script: the first call writes `k` bytes, later calls answer
   EAGAIN."
  [k]
  (fn [i _] (if (= i 1) [:short k] :eagain)))


(defn- section-control
  []
  (let [w (driver/make-wake)]
    (with-ledger
      "control"
      (fn []
        ;; A3 (a): M1 short-written, ping arrives, then M2 submitted
        (doseq [order [:ping-then-m2 :m2-then-ping]]
          (let [{:keys [a b]} (pair!)
                p (pump/create)
                events (atom [])
                h (ws-conn! p a events)
                m1 (pattern 1 3000) m2 (pattern 2 2000)
                pong-len (count (wire (pong 7)))
                calls (atom 0)
                steps (with-redefs-fn
                        {send-var (scripted-send calls (eagain-after 7))}
                        (fn []
                          (pump/send! p h m1)
                          (let [s1 (pump/queued-bytes h)]
                            (if (= order :ping-then-m2)
                              (do (feed! p a b (wire (ping 7)))
                                  (let [s2 (pump/queued-bytes h)]
                                    (pump/send! p h m2)
                                    [s1 s2 (pump/queued-bytes h)]))
                              (do (pump/send! p h m2)
                                  (let [s2 (pump/queued-bytes h)]
                                    (feed! p a b (wire (ping 7)))
                                    [s1 s2 (pump/queued-bytes h)]))))))
                want-steps (if (= order :ping-then-m2)
                             [2993 (+ 2993 pong-len) (+ 2993 pong-len 2000)]
                             [2993 (+ 2993 2000) (+ 2993 2000 pong-len)])
                want-wire (if (= order :ping-then-m2)
                            (vec (concat (bvec m1) (wire (pong 7)) (bvec m2)))
                            (vec (concat (bvec m1) (bvec m2) (wire (pong 7)))))]
            (check (str "control A3 " order ": :queued-bytes exact at each step")
                   (= want-steps steps) {:steps steps, :want want-steps})
            (drain-rounds! w p h 50)
            (let [got (read-n! b (count want-wire) 3000)]
              (check (str "control A3 " order ": the peer reads the frames byte-exact in generation order")
                     (= want-wire (:bytes got)) {:read (count (:bytes got))}))
            (pump/discard! p h)
            (tcp/close! b)))
        ;; A4: ten pings in one chunk, then octet-wise: identical pongs
        (let [pings (apply wire (map ping (range 10 20)))
              want (apply wire (map pong (range 10 20)))
              run (fn [octet-wise?]
                    (let [{:keys [a b]} (pair!)
                          p (pump/create)
                          events (atom [])
                          h (ws-conn! p a events)]
                      (if octet-wise?
                        (with-redefs-fn {recv-var (fn [fd buf n] (orig-recv fd buf (min n 1)))}
                          (fn []
                            (write-all! b (byte-array (map unchecked-byte pings)))
                            (dotimes [_ (count pings)]
                              (wait-fd a native/pollin 1000)
                              (pump/pump! p [[a native/pollin]]))))
                        (feed! p a b pings))
                      (drain-rounds! w p h 50)
                      (let [got (read-n! b (count want) 3000)]
                        (pump/discard! p h)
                        (tcp/close! b)
                        (:bytes got))))
              whole (run false)
              octets (run true)]
          (check "control A4: ten pings in one chunk answer ten pongs with matching payloads, in order"
                 (= want whole) {:read (count whole)})
          (check "control A4: the same pings one octet per chunk give identical wire output"
                 (= whole octets) {:read (count octets)}))
        ;; A4: a ping while a pong is short-written (3 bytes then EAGAIN)
        (let [{:keys [a b]} (pair!)
              p (pump/create)
              events (atom [])
              h (ws-conn! p a events)
              plen (count (wire (pong 1)))
              calls (atom 0)
              steps (with-redefs-fn
                      {send-var (scripted-send calls (eagain-after 3))}
                      (fn []
                        (feed! p a b (wire (ping 1)))
                        (let [s1 [(pump/queued-bytes h) (pump/pending-tags h)]]
                          (feed! p a b (wire (ping 2)))
                          [s1 [(pump/queued-bytes h) (pump/pending-tags h)]])))]
          (check "control A4: a pong short-written by 3 bytes then a second ping: exact accounting, head offset kept"
                 (= steps [[(- plen 3) [[:pong 3 plen]]]
                           [(+ (- plen 3) plen) [[:pong 3 plen] [:pong 0 plen]]]])
                 steps)
          (drain-rounds! w p h 50)
          (let [want (wire (pong 1) (pong 2))
                got (read-n! b (count want) 3000)]
            (check "control A4: the first pong's tail is written unchanged, the second follows"
                   (= want (:bytes got)) {:read (count (:bytes got))}))
          (pump/discard! p h)
          (tcp/close! b))
        ;; A4: 17 pings against a non-writable socket
        (let [{:keys [a b]} (pair!)
              p (pump/create)
              events (atom [])
              h (ws-conn! p a events)
              plen (count (wire (pong 1)))
              calls (atom 0)
              reads (atom 0)
              counted-recv (fn [fd buf n] (swap! reads inc) (orig-recv fd buf n))]
          (with-redefs-fn
            {send-var (scripted-send calls (fn [_ _] :eagain)), recv-var counted-recv}
            (fn [] (feed! p a b (apply wire (map ping (range 1 18))))))
          (check "control A4: 17 pings against a non-writable socket queue 16 pongs; :queued-bytes = 16 pong lengths"
                 (and (= 16 (count (pump/pending-tags h))) (= (* 16 plen) (pump/queued-bytes h)))
                 {:pending (count (pump/pending-tags h)), :queued (pump/queued-bytes h)})
          (check "control A4: read interest is cleared and the 17th ping stays unparsed"
                 (and (not (pump/read-interest? h))
                      (not-any? (fn [[fd ev]] (and (= fd a) (pos? (bit-and ev native/pollin))))
                                (pump/interest p)))
                 {:interest (pump/interest p)})
          (reset! reads 0)
          (reset! calls 0)
          (with-redefs-fn
            {send-var (scripted-send calls (fn [i _] (if (= i 1) :real :eagain))), recv-var counted-recv}
            (fn [] (pump/pump! p [[a native/pollout]])))
          (check "control A4: after one pong drains, the 17th pong is queued before any read"
                 (and (= 16 (count (pump/pending-tags h))) (zero? @reads)
                      (= (* 16 plen) (pump/queued-bytes h)))
                 {:pending (count (pump/pending-tags h)), :reads @reads})
          (drain-rounds! w p h 50)
          (let [want (apply wire (map pong (range 1 18)))
                got (read-n! b (count want) 3000)]
            (check "control A4: all 17 pongs reach the peer in ping order"
                   (= want (:bytes got)) {:read (count (:bytes got))}))
          (pump/discard! p h)
          (tcp/close! b))
        ;; D1: 16 pongs pending (head 3 bytes written), close! takes the reserved slot
        (doseq [ending [:readiness :deadline]]
          (let [{:keys [a b]} (pair!)
                p (pump/create)
                events (atom [])
                h (ws-conn! p a events {:close-deadline-ms 150})
                plen (count (wire (pong 1)))
                close-len (count (wire (close-f 1001 nil)))
                calls (atom 0)
                [before after1 after2]
                (with-redefs-fn
                  {send-var (scripted-send calls (eagain-after 3))}
                  (fn []
                    (feed! p a b (apply wire (map ping (range 1 18))))
                    (let [before [(pump/pending-tags h) (pump/queued-bytes h)]]
                      (pump/close! p h 1001 nil)
                      (let [after1 [(pump/pending-tags h) (pump/queued-bytes h)]]
                        (pump/close! p h 1000 nil)
                        [before after1 [(pump/pending-tags h) (pump/queued-bytes h)]]))))
                [tags0 q0] before
                [tags1 q1] after1]
            (check (str "D1 " ending ": 16 pongs queued (head 3 bytes written) and read interest cleared")
                   (and (= 16 (count tags0)) (= [:pong 3 plen] (first tags0)) (not (pump/read-interest? h)))
                   {:tags (count tags0)})
            (check (str "D1 " ending ": close! 1001 leaves the 16 pongs unchanged and appends one close frame")
                   (and (= tags0 (vec (butlast tags1))) (= [:close 0 close-len] (last tags1))) {:tags1 (last tags1)})
            (check (str "D1 " ending ": :queued-bytes rises by exactly the close frame's wire length")
                   (= q1 (+ q0 close-len)) {:q0 q0, :q1 q1, :close-len close-len})
            (check (str "D1 " ending ": a second close! during saturation changes nothing")
                   (= after1 after2) {})
            (if (= ending :readiness)
              (do (drain-rounds! w p h 50)
                  (let [want (vec (concat (apply wire (map pong (range 1 17))) (wire (close-f 1001 nil))))
                        got (read-n! b (count want) 3000)]
                    (check "D1 readiness: the 16 pongs then the close reach the peer byte-exact; no 17th pong"
                           (= want (:bytes got)) {:read (count (:bytes got))})
                    (check "D1 readiness: :queued-bytes reaches 0" (zero? (pump/queued-bytes h)) {}))
                  (pump/discard! p h))
              (do (Thread/sleep 200)
                  (pump/pump! p [])
                  (check "D1 deadline: at the deadline exactly one terminal 1006, :queued-bytes 0, registry empty"
                         (and (= [[:closed 1006 :deadline]] (terminals events)) (zero? (pump/queued-bytes h))
                              (= empty-registry (pump/debug-state p)))
                         {:terminals (terminals events), :state (pump/debug-state p)})))
            (tcp/close! b)))))
    (driver/close! w)))


;; =============================================================================
;; close (A5 scripted, D2, D3)
;; =============================================================================

(defn- section-close
  []
  (let [w (driver/make-wake)
        fresh (fn [& [opts]]
                (let [{:keys [a b]} (pair!)
                      p (pump/create)
                      events (atom [])
                      h (ws-conn! p a events (or opts {}))]
                  {:a a, :b b, :p p, :events events, :h h}))
        done (fn [{:keys [p h b]}] (pump/discard! p h) (tcp/close! b))]
    (with-ledger
      "close"
      (fn []
        ;; A5: close! with EAGAIN, then the peer's close; written on readiness
        (let [{:keys [a b p events h] :as c} (fresh)
              calls (atom 0)]
          (with-redefs-fn {send-var (scripted-send calls (fn [_ _] :eagain))}
            (fn []
              (pump/close! p h 4000 "ended")
              (feed! p a b (wire (close-f 4000 "ended")))))
          (check "A5: close! pending (EAGAIN) then a peer close: no terminal before the response is written"
                 (empty? (terminals events)) @events)
          (drain-rounds! w p h 50)
          (pump/pump! p [])
          (let [got (read-n! b 100 1000)]
            (check "A5: the queued close is written on readiness; one terminal with the local code"
                   (and (= (wire (close-f 4000 "ended")) (:bytes got))
                        (= [[:closed 4000 :handshake]] (terminals events)))
                   {:terminals (terminals events), :read (count (:bytes got))}))
          (done c))
        ;; A5: close! then raw EOF
        (let [{:keys [a b p events h]} (fresh)
              calls (atom 0)]
          (with-redefs-fn {send-var (scripted-send calls (fn [_ _] :eagain))}
            (fn []
              (pump/close! p h 1000 nil)
              (tcp/close! b)
              (wait-fd a native/pollin 2000)
              (pump/pump! p [[a native/pollin]])
              (pump/pump! p [])))
          (check "A5: close! then raw EOF: terminal 1006, pending close released, accounting 0"
                 (and (= [[:closed 1006 :eof]] (terminals events)) (zero? (pump/queued-bytes h))
                      (= empty-registry (pump/debug-state p)))
                 {:terminals (terminals events)})
          (pump/discard! p h))
        ;; A5: peer close 4000, our response short-written, then EOF
        (let [{:keys [a b p events h]} (fresh)
              calls (atom 0)]
          (with-redefs-fn {send-var (scripted-send calls (eagain-after 1))}
            (fn []
              (feed! p a b (wire (close-f 4000 "ended")))
              (tcp/close! b)
              (wait-fd a native/pollin 2000)
              (pump/pump! p [[a native/pollin]])
              (pump/pump! p [])))
          ;; the peer's close with our response bytes unread may surface as
          ;; EOF or reset; A5 treats both alike
          (check "A5: peer close 4000, response short-written, then EOF/reset: one terminal 4000 (:ws/ended)"
                 (let [ts (terminals events)]
                   (and (= 1 (count ts)) (= 4000 (second (first ts)))
                        (#{:eof :reset} (nth (first ts) 2))))
                 {:terminals (terminals events)})
          (pump/discard! p h))
        ;; A5: non-reading peer: 1006 at the deadline
        (let [{:keys [p events h] :as c} (fresh {:close-deadline-ms 100})
              calls (atom 0)]
          (with-redefs-fn {send-var (scripted-send calls (fn [_ _] :eagain))}
            (fn []
              (pump/close! p h 1000 nil)
              (Thread/sleep 150)
              (pump/pump! p [])))
          (check "A5: a non-reading peer: terminal 1006 at the deadline, registry empty"
                 (and (= [[:closed 1006 :deadline]] (terminals events)) (= empty-registry (pump/debug-state p)))
                 {:terminals (terminals events)})
          (done c))
        ;; A5: 4000 requested, then 1001: 4000 on the wire and in the terminal
        (let [{:keys [a b p events h] :as c} (fresh)]
          (pump/close! p h 4000 "ended")
          (pump/close! p h 1001 nil)
          (drain-rounds! w p h 50)
          (let [got (read-n! b 100 1000)]
            (feed! p a b (wire (close-f 4000 "ended")))
            (pump/pump! p [])
            (check "A5: 4000 requested, then 1001: 4000 preserved on the wire and in the terminal"
                   (and (= (wire (close-f 4000 "ended")) (:bytes got))
                        (= [[:closed 4000 :handshake]] (terminals events)))
                   {:terminals (terminals events)}))
          (done c))
        ;; D2: peer-first close 1000, response EAGAIN, no readiness: 1006 at the deadline
        (let [{:keys [a b p events h] :as c} (fresh {:close-deadline-ms 100})
              calls (atom 0)]
          (with-redefs-fn {send-var (fn [_fd _buf _n] (swap! calls inc) [-1 native/eagain])}
            (fn []
              (feed! p a b (wire (close-f 1000 nil)))
              (let [rec (pump/close-record h)
                    tags (pump/pending-tags h)]
                (check "D2: a peer-first close queues the response in the reserved slot and starts the deadline at receipt"
                       (and (= :received (:peer rec)) (= :queued (:local rec)) (some? (:deadline rec))
                            (= :peer (:initiator rec)) (= [[:close 0 4]] tags))
                       {:rec (dissoc rec :deadline), :tags tags}))
              (Thread/sleep 150)
              (pump/pump! p [])))
          (let [got (read-n! b 100 300)]
            (check "D2: at the deadline the socket is shut, one terminal 1006, :queued-bytes 0, registry empty, no close bytes written"
                   (and (= [[:closed 1006 :deadline]] (terminals events)) (zero? (pump/queued-bytes h))
                        (= empty-registry (pump/debug-state p)) (empty? (:bytes got)))
                   {:terminals (terminals events), :read (:bytes got), :end (:end got)}))
          (done c))
        ;; RFC 6455 5.5.1: no data after the peer's close
        (let [{:keys [a b p events h] :as c} (fresh)
              calls (atom 0)
              answer (with-redefs-fn {send-var (scripted-send calls (fn [_ _] :eagain))}
                       (fn []
                         (feed! p a b (wire (close-f 1000 nil)))
                         (pump/send! p h (pattern 9 50))))]
          (check "close: send! after the peer's close answers closed and queues nothing behind the response"
                 (and (= pump/closed-outcome answer) (= [[:close 0 4]] (pump/pending-tags h))
                      (= 4 (pump/queued-bytes h)))
                 {:answer answer, :tags (pump/pending-tags h)})
          (drain-rounds! w p h 50)
          (pump/pump! p [])
          (let [got (read-n! b 100 500)]
            (check "close: only the close response reached the peer, then one terminal 1000"
                   (and (= (wire (close-f 1000 nil)) (:bytes got))
                        (= [[:closed 1000 :handshake]] (terminals events)))
                   {:read (:bytes got), :terminals (terminals events)}))
          (done c))
        ;; D2 companion: readiness before the deadline
        (let [{:keys [a b p events h] :as c} (fresh {:close-deadline-ms 2000})
              calls (atom 0)]
          (with-redefs-fn {send-var (scripted-send calls (fn [_ _] :eagain))}
            (fn [] (feed! p a b (wire (close-f 1000 nil)))))
          (drain-rounds! w p h 50)
          (pump/pump! p [])
          (let [got (read-n! b 100 1000)]
            (check "D2 companion: readiness before the deadline writes the response; one terminal with the peer's status 1000"
                   (and (= (wire (close-f 1000 nil)) (:bytes got))
                        (= [[:closed 1000 :handshake]] (terminals events)))
                   {:terminals (terminals events)}))
          (done c))
        ;; D3: the five close payloads
        (doseq [[label peer-octets want-wire want-code]
                [["zero-payload close" (wire (close-f nil nil)) (wire (close-f nil nil)) 1005]
                 ["payload length 1" (wire {:opcode :close, :payload (byte-array [3])})
                  (wire (close-f 1002 "dao.stream/protocol-error")) 1002]
                 ["1000 with a valid reason" (wire (close-f 1000 "bye")) (wire (close-f 1000 nil)) 1000]
                 ["1000 with an invalid UTF-8 reason"
                  (wire {:opcode :close, :payload (byte-array (map unchecked-byte [3 0xE8 0xC3 0x28]))})
                  (wire (close-f 1002 "dao.stream/protocol-error")) 1002]
                 ["5000" (wire (close-f 5000 nil)) (wire (close-f 1002 "dao.stream/protocol-error")) 1002]]]
          (let [{:keys [a b p events h] :as c} (fresh)]
            (feed! p a b peer-octets)
            (drain-rounds! w p h 50)
            (pump/pump! p [])
            (let [got (read-n! b 200 1000)]
              (check (str "D3 " label ": response " (pr-str want-wire) ", one terminal " want-code)
                     (and (= want-wire (:bytes got)) (= [[:closed want-code :handshake]] (terminals events)))
                     {:wire (:bytes got), :terminals (terminals events)}))
            (done c)))
        ;; A5 amendment: a parser :fail without :close? fails the connection
        (let [fail-wire (wire (close-f 1002 "stand-in: unsupported opcode"))
              {:keys [a b p events h] :as c} (fresh)]
          (feed! p a b (into [0x83 0x00] (wire (ping 1))))
          (check "A5 fail: the unparsed rest is dropped; only the close frame is queued or sent"
                 (and (every? #(= :close (first %)) (pump/pending-tags h)))
                 {:tags (pump/pending-tags h)})
          (drain-rounds! w p h 50)
          (pump/pump! p [])
          (let [got (read-n! b 100 500)]
            (check "A5 fail: the close frame with the local code, then exactly one terminal 1002 :fail, no handshake wait"
                   (and (= fail-wire (:bytes got))
                        (= [[:closed 1002 :fail]] (terminals events)))
                   {:wire (:bytes got), :terminals (terminals events)}))
          (done c))
        ;; blocked close write, then the peer's reply: never parsed
        (let [{:keys [a b p events h] :as c} (fresh)
              calls (atom 0)]
          (with-redefs-fn {send-var (scripted-send calls (fn [_ _] :eagain))}
            (fn [] (feed! p a b [0x83 0x00])))
          (check "A5 fail: a blocked close frame waits in the reserved slot, failed record, no terminal yet"
                 (and (= 1 (count (pump/pending-tags h)))
                      (= :queued (:local (pump/close-record h)))
                      (true? (:failed? (pump/close-record h)))
                      (empty? (terminals events)))
                 {:rec (pump/close-record h)})
          (write-all! b (byte-array (map unchecked-byte (wire (close-f 1002 nil)))))
          (drain-rounds! w p h 50)
          (pump/pump! p [])
          (check "A5 fail: the peer's reply does not change the terminal: one 1002 :fail"
                 (= [[:closed 1002 :fail]] (terminals events))
                 {:terminals (terminals events)})
          (done c))
        ;; a reset before the close frame is written: 1006 (A5 unchanged)
        (let [{:keys [a b p events h] :as c} (fresh)
              calls (atom 0)]
          (with-redefs-fn {send-var (scripted-send calls (fn [i _] (if (= i 1) :eagain :epipe)))}
            (fn []
              (feed! p a b [0x83 0x00])
              (drain-rounds! w p h 5)
              (pump/pump! p [])))
          (check "A5 fail: a reset before the close frame is written reports 1006"
                 (let [t (terminals events)] (and (= 1 (count t)) (= 1006 (second (first t)))))
                 {:terminals (terminals events)})
          (done c))
        ;; EOF before the close frame is written: 1006 (A5 unchanged)
        (let [{:keys [a b p events h] :as c} (fresh)
              calls (atom 0)]
          (with-redefs-fn {send-var (scripted-send calls (fn [_ _] :eagain))
                           recv-var (let [n (atom 0)]
                                      (fn [fd buf cnt]
                                        (if (= 1 (swap! n inc)) (orig-recv fd buf cnt) [0 0])))}
            (fn []
              (feed! p a b [0x83 0x00])
              (check "A5 fail: still reading for EOF with the close frame unwritten"
                     (and (pump/read-interest? h) (= 1 (count (pump/pending-tags h)))) {})
              (pump/pump! p [[a native/pollin]])))
          (check "A5 fail: EOF before the close frame is written reports 1006 (:eof), pending released"
                 (and (= [[:closed 1006 :eof]] (terminals events)) (zero? (pump/queued-bytes h)))
                 {:terminals (terminals events)})
          (done c))
        ;; R12 (i): a parser failure after a requested close still queued:
        ;; that frame keeps the slot, the connection fails once it is written
        (let [{:keys [a b p events h] :as c} (fresh)
              calls (atom 0)]
          (with-redefs-fn {send-var (scripted-send calls (fn [_ _] :eagain))}
            (fn []
              (pump/close! p h 4000 "ended")
              (feed! p a b [0x83 0x00])
              (let [tags (pump/pending-tags h)
                    rec (pump/close-record h)]
                (check "R12 (i): close! 4000 under EAGAIN then a failing frame: still exactly one :close pending, :failed? true, no terminal yet"
                       (and (= [:close] (mapv first tags)) (= :queued (:local rec)) (true? (:failed? rec))
                            (= 4000 (:code rec)) (empty? (terminals events)))
                       {:tags tags, :rec (dissoc rec :deadline)}))
              (feed! p a b (wire (ping 3)))
              (check "R12 (i): bytes the peer writes afterwards are not parsed: no pong"
                     (= [:close] (mapv first (pump/pending-tags h))) {:tags (pump/pending-tags h)})))
          (drain-rounds! w p h 50)
          (pump/pump! p [])
          (let [got (read-n! b 100 500)]
            (check "R12 (i): on readiness the wire holds the 4000 frame only; one terminal 4000 :fail, :queued-bytes 0, registry empty"
                   (and (= (wire (close-f 4000 "ended")) (:bytes got))
                        (= [[:closed 4000 :fail]] (terminals events)) (zero? (pump/queued-bytes h))
                        (= empty-registry (pump/debug-state p)))
                   {:wire (:bytes got), :terminals (terminals events)}))
          (done c))
        ;; R12 (ii): the same with the 4000 frame already sent: fails at once
        (let [{:keys [a b p events h] :as c} (fresh)]
          (pump/close! p h 4000 "ended")
          (check "R12 (ii): the 4000 frame is sent" (= :sent (:local (pump/close-record h))) {})
          (feed! p a b [0x83 0x00])
          (check "R12 (ii): a failing frame after our close was sent ends the connection at once: one terminal 4000 :fail, registry empty"
                 (and (= [[:closed 4000 :fail]] (terminals events)) (= empty-registry (pump/debug-state p)))
                 {:terminals (terminals events)})
          (let [got (read-n! b 100 500)]
            (check "R12 (ii): the wire holds the 4000 frame only"
                   (= (wire (close-f 4000 "ended")) (:bytes got)) {:wire (:bytes got)}))
          (done c))
        (let [{:keys [h] :as c} (fresh)]
          (check "D3: the close-frame builder refuses 1005 and 1006"
                 (every? (fn [code] (try (pump/close-frame h code nil) false (catch Throwable _ true)))
                         [1005 1006])
                 {})
          (done c))))
    (driver/close! w)))


;; =============================================================================
;; listener (cap, drained?, lower-deadlines!)
;; =============================================================================

(defn- section-listener
  []
  (with-ledger
    "listener"
    (fn []
      (let [p (pump/create)
            {lfd :fd port :port} (tcp/listen! (loopback))
            accepted (atom [])
            expired (atom 0)
            drained (atom 0)
            lh (atom nil)
            on-accept (fn [{:keys [fd]}]
                        (swap! accepted conj
                               (pump/connection! p fd {:listener @lh, :upgrading? true
                                                       ;; the first accepted expires early
                                                       :upgrade-timeout-ms (if (empty? @accepted) 150 60000)
                                                       :on-upgrade-expired #(swap! expired inc)})))
            _ (reset! lh (pump/listener! p lfd {:on-accept on-accept, :on-drained #(swap! drained inc)}))
            clients (vec (for [_ (range 80)] (:fd (tcp/connect! (tcp/parse-address "127.0.0.1" port)))))
            turn! (fn [] (wait-fd lfd native/pollin 200) (pump/pump! p [[lfd native/pollin]]))
            listening? #(boolean (some (fn [[fd _]] (= fd lfd)) (pump/interest p)))]
        (dotimes [_ 10] (turn!))
        (check "listener: 80 clients against the cap: exactly 64 pending sockets accepted"
               (= 64 (count @accepted)) {:accepted (count @accepted)})
        (check "listener: at the cap the listener fd is absent from :interest" (not (listening?)) {})
        (pump/upgraded! p (nth @accepted 1) stand-in nil)
        (dotimes [_ 3] (turn!))
        (check "listener: completing one upgrade admits exactly one more"
               (= 65 (count @accepted)) {:accepted (count @accepted)})
        (Thread/sleep 200)
        (pump/pump! p [])
        (dotimes [_ 3] (turn!))
        (check "listener: expiring one upgrade admits exactly one more (expiry reported once)"
               (and (= 66 (count @accepted)) (= 1 @expired)) {:accepted (count @accepted), :expired @expired})
        ;; lower-deadlines!: an upgraded connection's deadline is lowered, never raised
        (let [ws (nth @accepted 1)
              now (poller/clock-ms)]
          (pump/lower-deadlines! p @lh (+ now 100))
          (pump/lower-deadlines! p @lh (+ now 100000))
          (check "listener: lower-deadlines! sets a deadline where none exists and never raises it"
                 (<= (:deadline (pump/close-record ws)) (+ now 100)) {}))
        (pump/close-listener! p @lh)
        (check "listener: drained? is false while owned connections remain" (not (pump/drained? p @lh)) {})
        (Thread/sleep 150)
        (pump/pump! p [])
        (check "listener: the lowered deadline ends the owned connections"
               (not (pump/open? (nth @accepted 1))) {})
        (doseq [h @accepted] (pump/discard! p h))
        (pump/pump! p [])
        (check "listener: drained? turns true once no owned connection is left; :on-drained once"
               (and (pump/drained? p @lh) (= 1 @drained)) {:drained @drained})
        (pump/pump! p [])
        (check "listener: :on-drained not repeated" (= 1 @drained) {:drained @drained})
        (doseq [fd clients] (when fd (tcp/close! fd)))
        (check "listener: registry and buffers empty after close" (= empty-registry (pump/debug-state p))
               (pump/debug-state p)))
      ;; :deadline during a closed listener's drain, through a folding driver
      (let [p (pump/create)
            {lfd :fd port :port} (tcp/listen! (loopback))
            drained (atom 0)
            lh (atom nil)
            owned (atom nil)
            _ (reset! lh (pump/listener! p lfd {:on-accept #(reset! owned (pump/connection!
                                                                            p (:fd %)
                                                                            {:listener @lh
                                                                             :upgrading? true
                                                                             :upgrade-timeout-ms 60000}))
                                                :on-drained #(swap! drained inc)}))
            {cfd :fd} (tcp/connect! (tcp/parse-address "127.0.0.1" port))
            calls (atom [])]
        (wait-fd lfd native/pollin 2000)
        (pump/pump! p [[lfd native/pollin]])
        (pump/close-listener! p @lh)
        (let [now (poller/clock-ms)
              d (pump/next-deadline p)
              r (owner-round! p 300 calls)]
          (check "listener drain: while the closed listener still owns a connection, :deadline is that connection's, not now"
                 (and (some? @owned) (some? d) (> d (+ now 1000))) {:in-ms (when d (- d now))})
          (check "listener drain: a folding driver round waits its cadence instead of busy-polling"
                 (<= 280 (:ms r)) (dissoc r :deadline)))
        (pump/discard! p @owned)
        (let [now (poller/clock-ms)
              d (pump/next-deadline p)]
          (check "listener drain: once the owned set is empty the owed drain report makes :deadline now"
                 (and (some? d) (<= d (+ now 1))) {:in-ms (when d (- d now))}))
        (pump/pump! p [])
        (check "listener drain: :on-drained delivered once and :deadline is nil afterwards"
               (and (= 1 @drained) (nil? (pump/next-deadline p))) {:drained @drained})
        (tcp/close! cfd)
        (check "listener drain: registry and buffers empty after close" (= empty-registry (pump/debug-state p))
               (pump/debug-state p))))))


(defn- section-closure
  []
  (let [libs (set (map str (loaded-libs)))]
    (record "closure: jolt.* loaded" (pr-str (sort (filter #(re-find #"^jolt\." %) libs))))
    (check "closure: the substrate, pump and driver are loaded and jolt.socket (the java.net shim) is not"
           (and (every? libs ["dao.stream.ws.tcp" "dao.stream.ws.pump" "dao.stream.waitset.driver"
                              "jolt.socket.native" "jolt.io-poller" "jolt.ffi"])
                (not (contains? libs "jolt.socket")))
           {:jolt.socket (contains? libs "jolt.socket")})))


(def sections
  {"closure" section-closure
   "address" section-address
   "substrate" section-substrate
   "ordered" section-ordered
   "fairness" section-fairness
   "stdin" section-stdin
   "deadline" section-deadline
   "control" section-control
   "close" section-close
   "listener" section-listener})


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
