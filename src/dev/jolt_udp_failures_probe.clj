;; J7 failure-injection probe: native UDP setup failure handling,
;; exception and buffer cleanup, fatal error seam closure, and diagnostics.
;; Run with:
;;   bb probe:jolt-udp-failures
(ns jolt-udp-failures-probe
  (:require [dao.stream :as stream]
            [dao.stream.datagram :as datagram]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.udp :as udp]
            [jolt.ffi :as ffi]
            [jolt.io-poller :as poller]
            [jolt.socket.native :as native]))

(def pass-count (atom 0))
(def fail-count (atom 0))

(defn check [leg label condition data]
  (if condition
    (do
      (swap! pass-count inc)
      (println "PASS" leg label data))
    (do
      (swap! fail-count inc)
      (println "FAIL" leg label data))))

(defn traffic []
  (:dao.stream/handle (ring/create! {:dao.stream/type ring/transport-type
                                     ring/capacity-key 256})))

(defn events [r]
  (loop [c (:dao.stream/cursor (stream/cursor r stream/anchor-oldest)) out []]
    (let [v (stream/next r c)]
      (if (= :dao.stream/ok (:dao.stream/outcome v))
        (recur (:dao.stream/cursor v) (conj out (:dao.stream/value v)))
        out))))

(def closes (atom []))
(def frees (atom []))

(defn fake-host [f]
  (reset! closes [])
  (reset! frees [])
  (with-redefs [native/c-socket (fn [& _] [123 0])
                native/close-on-exec! (fn [_] true)
                native/set-blocking! (fn [& _] [0 0])
                native/set-int-option! (fn [& _] [0 0])
                native/c-bind (fn [& _] [0 0])
                native/local-address (fn [_] {:ip "127.0.0.1" :port 40001})
                native/sockaddr-ip (fn [_] "127.0.0.1")
                native/sockaddr-port (fn [_] 40002)
                native/c-close (fn [fd] (swap! closes conj fd) [0 0])
                poller/cancel! (fn [& _] nil)
                poller/forget! (fn [& _] nil)]
    (f)))

(defn bind-seam [r & {:keys [bind-host bind-port] :or {bind-host "127.0.0.1" bind-port 0}}]
  (udp/bind! {:identity "probe-test" :deposit r :bind-host bind-host :bind-port bind-port}))

(def descriptor {:dao.stream/type :dao.stream/datagram
                 :dao.stream/identity "probe-test"
                 :dao.stream.datagram/bind-host "127.0.0.1"
                 :dao.stream.datagram/bind-port 40001})

(def outbound {:dao.stream.datagram/destination
               {:dao.stream.datagram/host "127.0.0.1" :dao.stream.datagram/port 40002}
               :dao.stream.datagram/bytes "BQY="})

;; -----------------------------------------------------------------------------
;; 1. Setup Failures: close-on-exec! and IPV6_V6ONLY (Fix 1)
;; -----------------------------------------------------------------------------

(fake-host
  (fn []
    (let [r (traffic)
          seam (with-redefs [native/close-on-exec! (fn [_] false)]
                 (bind-seam r))
          evs (events r)]
      (check 1 "close-on-exec! failure closes acquired fd"
             (= [123] @closes)
             {:closes @closes})
      (check 1 "close-on-exec! failure deposits bind-failed"
             (and (= 1 (count evs))
                  (= :dao.stream.datagram/bind-failed (:dao.stream.datagram/event (first evs)))
                  (= "close-on-exec! failed" (:dao.stream.datagram/reason (first evs))))
             {:events evs})
      (check 1 "close-on-exec! returns failed-seam (inert close and step)"
             (and (= {:progressed? false} ((:step! seam)))
                  (nil? ((:close! seam))))
             {}))))

(fake-host
  (fn []
    (let [r (traffic)
          seam (with-redefs [native/set-int-option! (fn [& _] [-1 1])
                             native/local-address (fn [_] {:ip "::1" :port 40001})]
                 (bind-seam r :bind-host "::1"))
          evs (events r)]
      (check 1 "IPV6_V6ONLY failure closes acquired fd"
             (= [123] @closes)
             {:closes @closes})
      (check 1 "IPV6_V6ONLY failure deposits bind-failed with errno"
             (and (= 1 (count evs))
                  (= :dao.stream.datagram/bind-failed (:dao.stream.datagram/event (first evs)))
                  (= "IPV6_V6ONLY failed: errno 1" (:dao.stream.datagram/reason (first evs))))
             {:events evs})
      (check 1 "IPV6_V6ONLY failure returns failed-seam"
             (= {:progressed? false} ((:step! seam)))
             {}))))

;; -----------------------------------------------------------------------------
;; 2. Setup and Buffer Ownership Cleanup (Fix 2)
;; -----------------------------------------------------------------------------

(fake-host
  (fn []
    (let [r (traffic)
          seam (with-redefs [native/local-address (fn [_] (throw (ex-info "injected getsockname failure" {})))]
                 (bind-seam r))
          evs (events r)]
      (check 2 "Setup exception closes acquired fd"
             (= [123] @closes)
             {:closes @closes})
      (check 2 "Setup exception deposits bind-failed preserving original reason"
             (and (= 1 (count evs))
                  (= :dao.stream.datagram/bind-failed (:dao.stream.datagram/event (first evs)))
                  (= "injected getsockname failure" (:dao.stream.datagram/reason (first evs))))
             {:events evs})
      (check 2 "Setup exception returns failed-seam"
             (= {:progressed? false} ((:step! seam)))
             {}))))

(fake-host
  (fn []
    (let [r (traffic)
          orig-ms native/make-sockaddr
          seam (with-redefs [native/make-sockaddr
                             (fn [fam ip port]
                               (if (= port 40001)
                                 (throw (ex-info "injected make-sockaddr failure" {}))
                                 (orig-ms fam ip port)))]
                 (bind-seam r :bind-port 40001))
          evs (events r)]
      (check 2 "make-sockaddr bind failure closes acquired fd"
             (= [123] @closes)
             {:closes @closes})
      (check 2 "make-sockaddr bind failure deposits bind-failed"
             (and (= 1 (count evs))
                  (= "injected make-sockaddr failure" (:dao.stream.datagram/reason (first evs))))
             {:events evs}))))

(fake-host
  (fn []
    ;; Helper acquisition: make-sockaddr address-string allocation failure during bind
    (let [r (traffic)
          oa ffi/alloc
          of ffi/free
          ms native/make-sockaddr
          live (atom {})
          seam
          (with-redefs [ffi/alloc (fn [n] (let [p (oa n)] (swap! live assoc p n) p))
                        ffi/free (fn [p] (swap! live dissoc p) (of p))
                        native/make-sockaddr
                        (fn [fam ip port]
                          (if (= port 40001)
                            (with-redefs [ffi/string->ptr (fn [_] (throw (ex-info "injected address string allocation failure" {})))]
                              (ms fam ip port))
                            (ms fam ip port)))]
            (udp/bind! {:identity "partial-bind" :deposit r :bind-host "127.0.0.1" :bind-port 40001}))
          evs (events r)]
      (check 2 "Bind helper address-string failure leaves 0 unreleased bytes (no 16-byte leak)"
             (empty? @live)
             {:unreleased-sizes (vec (vals @live))})
      (check 2 "Bind helper address-string failure closes acquired fd"
             (= [123] @closes)
             {:closes @closes})
      (check 2 "Bind helper address-string failure deposits bind-failed"
             (and (= 1 (count evs))
                  (= :dao.stream.datagram/bind-failed (:dao.stream.datagram/event (first evs)))
                  (= "injected address string allocation failure" (:dao.stream.datagram/reason (first evs))))
             {:events evs})
      (doseq [p (keys @live)] (of p)))))

(fake-host
  (fn []
    ;; Helper acquisition: make-sockaddr address-string allocation failure during send
    (let [r (traffic)
          seam (bind-seam r)
          oa ffi/alloc
          of ffi/free
          ms native/make-sockaddr
          live (atom {})]
      (with-redefs [ffi/alloc (fn [n] (let [p (oa n)] (swap! live assoc p n) p))
                    ffi/free (fn [p] (swap! live dissoc p) (of p))
                    native/make-sockaddr
                    (fn [fam ip port]
                      (if (= port 40002)
                        (with-redefs [ffi/string->ptr (fn [_] (throw (ex-info "injected destination string allocation failure" {})))]
                          (ms fam ip port))
                        (ms fam ip port)))]
        (let [res (try ((:send! seam) "127.0.0.1" 40002 (byte-array 32))
                       (catch Throwable t (ex-message t)))]
          (check 2 "Send helper address-string failure leaves 0 unreleased bytes (no buffer or sockaddr leak)"
                 (empty? @live)
                 {:unreleased-sizes (vec (vals @live)) :res res})
          (doseq [p (keys @live)] (of p)))))))

(fake-host
  (fn []
    ;; Helper acquisition: alloc-sockaddr length-cell allocation failure during receive
    (let [r (traffic)
          seam (bind-seam r)
          oa ffi/alloc
          of ffi/free
          live (atom {})
          result
          (with-redefs [ffi/alloc (fn [n]
                                    (if (= n 4)
                                      (throw (ex-info "injected socklen allocation failure" {}))
                                      (let [p (oa n)] (swap! live assoc p n) p)))
                        ffi/free (fn [p] (swap! live dissoc p) (of p))]
            (try ((:step! seam)) (catch Throwable t (ex-message t))))]
      (check 2 "Receive helper length-cell failure leaves 0 unreleased bytes (no 128-byte leak)"
             (empty? @live)
             {:unreleased-sizes (vec (vals @live)) :result result})
      (doseq [p (keys @live)] (of p)))))

(fake-host
  (fn []
    ;; Verify send-fn buffer cleanup when second allocation throws
    (let [r (traffic)
          seam (bind-seam r)
          orig-alloc ffi/alloc
          orig-free ffi/free
          orig-ms native/make-sockaddr
          freed (atom [])]
      (with-redefs [ffi/free (fn [p] (swap! freed conj p) (orig-free p))
                    native/make-sockaddr (fn [fam ip port]
                                           (if (= port 40002)
                                             (throw (ex-info "injected destination make-sockaddr error" {}))
                                             (orig-ms fam ip port)))]
        (let [res (try ((:send! seam) "127.0.0.1" 40002 (byte-array 32))
                       (catch Throwable t (ex-message t)))]
          (check 2 "send-fn frees payload buffer when destination sockaddr fails"
                 (pos? (count @freed))
                 {:freed-count (count @freed) :res res}))))))

(fake-host
  (fn []
    ;; Verify step-fn buffer cleanup when alloc-sockaddr throws
    (let [r (traffic)
          seam (bind-seam r)
          orig-free ffi/free
          freed (atom [])]
      (with-redefs [ffi/free (fn [p] (swap! freed conj p) (orig-free p))
                    native/alloc-sockaddr (fn [] (throw (ex-info "injected alloc-sockaddr error" {})))]
        (let [res (try ((:step! seam))
                       (catch Throwable t (ex-message t)))]
          (check 2 "step-fn frees receive buffer when alloc-sockaddr throws"
                 (pos? (count @freed))
                 {:freed-count (count @freed) :res res}))))))

;; -----------------------------------------------------------------------------
;; 3. Fatal Descriptor Errors, Interruption and Diagnostics (Fix 3)
;; -----------------------------------------------------------------------------

(fake-host
  (fn []
    ;; Fatal error: ENOTSOCK (88)
    (let [r (traffic)
          seam (bind-seam r)
          calls (atom 0)]
      (binding [udp/*recvfrom* (fn [& _] (swap! calls inc) [-1 88])]
        (let [turn1 ((:step! seam))
              turn2 ((:step! seam))
              turn3 ((:step! seam))
              evs (events r)
              diag (udp/diagnostics seam)]
          (check 3 "Fatal receive error returns not progressed"
                 (and (= {:progressed? false} turn1)
                      (= {:progressed? false} turn2)
                      (= {:progressed? false} turn3))
                 {:turns [turn1 turn2 turn3]})
          (check 3 "Fatal receive error closes fd once"
                 (= [123] @closes)
                 {:closes @closes})
          (check 3 "Fatal receive error deposits bound then closed"
                 (and (= 2 (count evs))
                      (= :dao.stream.datagram/bound (:dao.stream.datagram/event (first evs)))
                      (= :dao.stream.datagram/closed (:dao.stream.datagram/event (second evs))))
                 {:events evs})
          (check 3 "Fatal receive error stops turn and ceases retrying"
                 (= 1 @calls)
                 {:calls @calls})
          (check 3 "Fatal receive error retains diagnostic with errno"
                 (and (seq diag)
                      (= 88 (:errno (last diag)))
                      (= :fatal-descriptor-error (:kind (last diag))))
                 {:diagnostics diag})
          ;; Repeated close! is idempotent
          ((:close! seam))
          (check 3 "Subsequent close! is idempotent"
                 (= [123] @closes)
                 {:closes @closes}))))))

(fake-host
  (fn []
    ;; Fatal error: EBADF (9)
    (let [r (traffic)
          seam (bind-seam r)
          calls (atom 0)]
      (binding [udp/*recvfrom* (fn [& _] (swap! calls inc) [-1 9])]
        (let [turn1 ((:step! seam))
              evs (events r)]
          (check 3 "EBADF fatal error closes fd once"
                 (= [123] @closes)
                 {:closes @closes})
          (check 3 "EBADF deposits closed event"
                 (= :dao.stream.datagram/closed (:dao.stream.datagram/event (last evs)))
                 {:events evs})
          (check 3 "EBADF retains diagnostic with errno 9"
                 (= 9 (:errno (last (udp/diagnostics seam))))
                 {:diagnostics (udp/diagnostics seam)}))))))

(fake-host
  (fn []
    ;; Recoverable interruption: EINTR (4)
    (let [r (traffic)
          seam (bind-seam r)
          calls (atom 0)]
      (binding [udp/*recvfrom* (fn [& _] (swap! calls inc) [-1 4])]
        (let [turn1 ((:step! seam))
              diag (udp/diagnostics seam)]
          (check 3 "EINTR does not progress turn"
                 (= {:progressed? false} turn1)
                 {:turn1 turn1})
          (check 3 "EINTR does not close fd"
                 (empty? @closes)
                 {:closes @closes})
          (check 3 "EINTR retains recoverable diagnostic with errno 4"
                 (and (seq diag)
                      (= 4 (:errno (last diag)))
                      (= :interrupted (:kind (last diag))))
                 {:diagnostics diag})))
      ;; Socket remains open and usable for subsequent turns
      (let [turn (atom 0)]
        (binding [udp/*recvfrom* (fn [fd buf cap flags rsa rlenp]
                                   (swap! calls inc)
                                   (if (zero? @turn)
                                     (do (swap! turn inc)
                                         (ffi/write rlenp :int 16 0)
                                         [10 0])
                                     [-1 udp/EAGAIN]))]
          (let [turn2 ((:step! seam))]
            (check 3 "Turn after EINTR recovers and receives packets"
                   (= {:progressed? true} turn2)
                   {:turn2 turn2 :calls @calls})
            (check 3 "Datagram event deposited after recovery"
                   (datagram/datagram-event? (last (events r)))
                   {:events (events r)})))))))

(fake-host
  (fn []
    ;; Silent would-block: EAGAIN (11) and ENOBUFS (105)
    (let [r (traffic)
          seam (bind-seam r)]
      (binding [udp/*recvfrom* (fn [& _] [-1 11])]
        (let [t ((:step! seam))]
          (check 3 "EAGAIN returns {:progressed? false}"
                 (= {:progressed? false} t)
                 {:turn t})
          (check 3 "EAGAIN does not close fd"
                 (empty? @closes)
                 {:closes @closes})
          (check 3 "EAGAIN stays silent with no error diagnostics"
                 (empty? (udp/diagnostics seam))
                 {:diagnostics (udp/diagnostics seam)})))
      (binding [udp/*recvfrom* (fn [& _] [-1 105])]
        (let [t ((:step! seam))]
          (check 3 "ENOBUFS returns {:progressed? false}"
                 (= {:progressed? false} t)
                 {:turn t})
          (check 3 "ENOBUFS does not close fd"
                 (empty? @closes)
                 {:closes @closes})
          (check 3 "ENOBUFS stays silent with no error diagnostics"
                 (empty? (udp/diagnostics seam))
                 {:diagnostics (udp/diagnostics seam)}))))))

;; -----------------------------------------------------------------------------
;; 4. Production Writer Contract Assertions (Preserving Reviewer Checks)
;; -----------------------------------------------------------------------------

(fake-host
  (fn []
    (doseq [[label result] [[:eagain [-1 udp/EAGAIN]] [:enobufs [-1 udp/ENOBUFS]]
                            [:emsgsize [-1 udp/EMSGSIZE]] [:eintr [-1 4]] [:short [1 0]]]]
      (let [r (traffic)
            seam (bind-seam r)
            w (datagram/writer seam descriptor)
            calls (atom 0)]
        (binding [udp/*sendto* (fn [& _] (swap! calls inc) result)
                  udp/*recvfrom* (fn [& _] [-1 udp/EAGAIN])]
          (check 4 (str label " outcome is transport-error")
                 (= :dao.stream/transport-error (:dao.stream/outcome (stream/append! w outbound)))
                 {})
          (check 4 (str label " syscall count is 1")
                 (= 1 @calls)
                 {:calls @calls})
          (check 4 (str label " failure count is 1")
                 (= 1 (count (filter #(= :dao.stream.datagram/send-failed
                                         (:dao.stream.datagram/event %)) (events r))))
                 {})
          ((:step! seam)) ((:step! seam))
          (check 4 (str label " no replay on step")
                 (= 1 @calls)
                 {:calls @calls})
          (stream/close! w) (stream/close! w)
          (check 4 (str label " writer append after close returns :dao.stream/closed")
                 (= :dao.stream/closed (:dao.stream/outcome (stream/append! w outbound)))
                 {:outcome (:dao.stream/outcome (stream/append! w outbound))})
          (check 4 (str label " step after close answers {:progressed? false}")
                 (= {:progressed? false} ((:step! seam)))
                 {}))))))

(fake-host
  (fn []
    (let [r (traffic) seam (bind-seam r) calls (atom 0)]
      (binding [udp/*recvfrom* (fn [& _] (swap! calls inc) [1201 0])]
        (check 4 "oversize progress is true"
               (= {:progressed? true} ((:step! seam)))
               {})
        (check 4 "bounded receives max 64"
               (= 64 @calls)
               {:calls @calls})
        (check 4 "oversize dropped without packet delivery"
               (= 1 (count (events r))) ; bound only
               {:events (events r)}))
      (binding [udp/*recvfrom* (fn [& _] [-1 udp/EAGAIN])]
        (check 4 "empty turn answers {:progressed? false}"
               (= {:progressed? false} ((:step! seam)))
               {}))
      ((:close! seam)) ((:close! seam))
      (check 4 "close! closes fd once"
             (= [123] @closes)
             {:closes @closes}))))

(println (str "SUMMARY " (+ @pass-count @fail-count) " assertions: "
              @pass-count " pass, " @fail-count " fail"))
(if (pos? @fail-count)
  (System/exit 1)
  (System/exit 0))
