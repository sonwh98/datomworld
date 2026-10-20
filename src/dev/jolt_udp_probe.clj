;; J0b: raw nonblocking SOCK_DGRAM over jolt.ffi on macOS arm64 and Linux x86_64.
;; Probe only: no datagram/DHT conformance. Run from the repo root with
;;   bb probe:jolt-udp
(ns jolt-udp-probe
  (:require [jolt.ffi :as ffi]
            [jolt.socket.native :as native]
            [jolt.io-poller :as poller]
            [jolt.fibers :as fibers]
            [clojure.string :as str]))


;; -- constants per platform ---------------------------------------------------
;; The platform is jolt.socket.native/os (:macos, :linux, :windows), the same
;; selection jolt's socket layer and sockaddr helpers use; os.name is never
;; read here (jolt's System/getProperty shim can answer nil).
;;
;; :macos from MacOSX.sdk: sys/socket.h:112,155,156,574; netinet/in.h:121;
;;   sys/errno.h:96,129-130,137,170.
;; :linux x86_64 from shiva's headers (asm-generic/errno-base.h, errno.h,
;;   x86_64-linux-gnu/asm/socket.h, bits/socket.h), checked by the
;;   orchestrator: EAGAIN = EWOULDBLOCK = 11.
(def platform native/os)


(def consts
  (case platform
    :macos {:sock-dgram 2 :ipproto-udp 17 :so-sndbuf 0x1001 :so-rcvbuf 0x1002
            :msg-trunc 0x10 :eagain 35 :emsgsize 40 :enobufs 55 :ebadf 9}
    :linux {:sock-dgram 2 :ipproto-udp 17 :so-sndbuf 7 :so-rcvbuf 8
            :msg-trunc 0x20 :eagain 11 :emsgsize 90 :enobufs 105 :ebadf 9}
    (throw (ex-info "J0b probe: unsupported platform" {:platform platform}))))


(def SOCK-DGRAM (:sock-dgram consts))
(def IPPROTO-UDP (:ipproto-udp consts))
(def SO-SNDBUF (:so-sndbuf consts))
(def SO-RCVBUF (:so-rcvbuf consts))
(def MSG-TRUNC (:msg-trunc consts)) ; not used: N+1 sentinel instead
(def EAGAIN (:eagain consts))
(def EMSGSIZE (:emsgsize consts))
(def ENOBUFS (:enobufs consts))
(def EBADF (:ebadf consts))


;; ssize_t sendto(int, const void*, size_t, int, const struct sockaddr*, socklen_t)
;; ssize_t recvfrom(int, void*, size_t, int, struct sockaddr*, socklen_t*)
;; socklen_t is 32-bit unsigned on both (arm/_types.h:117; glibc
;; __socklen_t is __U32_TYPE). Not :blocking: the fd is
;; nonblocking, so neither call can park the carrier.
(ffi/defcfn c-sendto "sendto" [:int :pointer :size_t :int :pointer :uint] :ssize_t
            {:capture-native-error true})


(ffi/defcfn c-recvfrom "recvfrom" [:int :pointer :size_t :int :pointer :pointer] :ssize_t
            {:capture-native-error true})


;; -- injection seam -------------------------------------------------------------
;; The probe's sendto goes through this var. Injected runs rebind it and every
;; observation they produce is tagged :injected, never :natural.
(def ^:dynamic *sendto* c-sendto)


(defn- classify-send-error
  [e]
  (cond (or (= e EAGAIN) (= e ENOBUFS)) :would-block
        (= e EMSGSIZE) :emsgsize
        :else :error))


;; -- raw surface ------------------------------------------------------------------
(defn udp-open
  "A nonblocking, close-on-exec SOCK_DGRAM fd bound to 127.0.0.1:0."
  []
  (let [[fd e] (native/c-socket native/af-inet SOCK-DGRAM IPPROTO-UDP)]
    (when (neg? fd) (throw (ex-info "socket failed" {:errno e})))
    (native/close-on-exec! fd)
    (let [[r e] (native/set-blocking! fd false)]
      (when (neg? r) (throw (ex-info "O_NONBLOCK failed" {:errno e}))))
    (let [[sa len] (native/make-sockaddr native/af-inet "127.0.0.1" 0)]
      (try
        (let [[r e] (native/c-bind fd sa len)]
          (when (neg? r) (throw (ex-info "bind failed" {:errno e}))))
        (finally (ffi/free sa))))
    fd))


(defn send!
  "One sendto of the whole byte array to addr ({:ip :port}). Answers
   {:result :sent :n} | {:result :would-block :errno} | {:result :emsgsize :errno}
   | {:result :error :errno} | {:result :short-send :n :len}. A short send is an
   error and is never completed by a second packet."
  [fd ^bytes payload {:keys [ip port]}]
  (let [len (alength payload)
        buf (ffi/alloc (max 1 len))
        [sa salen] (native/make-sockaddr native/af-inet ip port)]
    (try
      (when (pos? len) (ffi/write-array buf payload))
      (let [[n e] (*sendto* fd buf len 0 sa salen)]
        (cond
          (neg? n) {:result (classify-send-error e) :errno e}
          (not= n len) {:result :short-send :n n :len len}
          :else {:result :sent :n n}))
      (finally (ffi/free buf) (ffi/free sa)))))


(defn recv!
  "One recvfrom with a buffer of limit+1 bytes. Answers
   {:result :packet :bytes :from} | {:result :oversize :received :limit :from}
   | {:result :would-block :errno} | {:result :error :errno}. A packet longer
   than limit is an error; its prefix is never returned."
  [fd limit]
  (let [cap (inc limit)
        buf (ffi/alloc cap)
        [sa lenp] (native/alloc-sockaddr)]
    (try
      (let [[n e] (c-recvfrom fd buf cap 0 sa lenp)]
        (cond
          (neg? n) {:result (if (= e EAGAIN) :would-block :error) :errno e}
          :else
          (let [from {:ip (native/sockaddr-ip sa) :port (native/sockaddr-port sa)}]
            (if (> n limit)
              {:result :oversize :received n :limit limit :from from}
              {:result :packet :bytes (if (zero? n) (byte-array 0) (ffi/read-array buf n))
               :from from}))))
      (finally (ffi/free buf) (ffi/free sa) (ffi/free lenp)))))


(defn local
  [fd]
  (native/local-address fd))


(defn close!
  [fd]
  (poller/cancel! fd)
  (poller/forget! fd)
  (native/c-close fd))


;; -- harness ------------------------------------------------------------------------
(def results (atom []))


(defn check
  [item label ok? detail]
  (swap! results conj {:item item :label label :ok? (boolean ok?) :detail detail})
  (println (if ok? "PASS" "FAIL") item label (pr-str detail)))


(defn record
  "An observation that cannot fail: printed as RECORD, never counted as PASS."
  [item label detail]
  (swap! results conj {:item item :label label :record? true :detail detail})
  (println "RECORD" item label (pr-str detail)))


(defn drain!
  "recv! until :would-block; answers the packet count. A :error result is a
   FAIL and stops the drain instead of looping on it."
  [item fd limit]
  (loop [n 0]
    (let [r (recv! fd limit)]
      (case (:result r)
        :would-block n
        :error (do (check item "drain stopped on recv error" false r) n)
        (recur (inc n))))))


(defn ->bytes
  [xs]
  (byte-array (map unchecked-byte xs)))


(defn pattern
  [n seed]
  (->bytes (map #(bit-and (+ seed (* 31 %)) 0xff) (range n))))


(defn recv-wait
  "recv! until a non-would-block result, waiting on the poller between tries.
   Counts the wakeups. Thread or fiber, whichever the caller is."
  [fd limit]
  (loop [waits 0]
    (let [r (recv! fd limit)]
      (if (= :would-block (:result r))
        (do (poller/wait-ready fd :read) (recur (inc waits)))
        (assoc r :waits waits)))))


(defn same-bytes?
  [a b]
  (= (vec a) (vec b)))


;; ---------------------------------------------------------------------------------
;; Item 1: bind, readback, whole-packet semantics
(defn item-1
  [a b N]
  (let [la (local a) lb (local b)
        to-b {:ip "127.0.0.1" :port (:port lb)}]
    (check 1 "bound ports read back" (and (pos? (:port la)) (pos? (:port lb))
                                          (not= (:port la) (:port lb)))
           {:a la :b lb})
    (check 1 "empty socket answers :would-block" (= :would-block (:result (recv! b N)))
           (recv! b N))
    (doseq [[label payload] [["zero-length" (byte-array 0)]
                             ["embedded-NUL" (->bytes [0 1 0 0 255 0 65 0])]
                             ["limit-sized N" (pattern N 7)]]]
      (let [s (send! a payload to-b)
            r (recv-wait b N)]
        (check 1 (str label " round-trip")
               (and (= :sent (:result s)) (= (alength payload) (:n s))
                    (= :packet (:result r)) (same-bytes? payload (:bytes r))
                    (= (:from r) {:ip "127.0.0.1" :port (:port la)}))
               {:len (alength payload) :send s :recv (dissoc r :bytes)
                :recv-len (some-> (:bytes r) alength)})))
    ;; distinct consecutive packets: each receive consumes exactly one
    (let [ps (mapv #(pattern (+ 10 %) %) (range 5))
          sends (mapv #(send! a % to-b) ps)
          recvs (vec (repeatedly 5 #(recv-wait b N)))
          after (recv! b N)]
      (check 1 "5 consecutive packets, one per receive, in order, then empty"
             (and (every? #(= :sent (:result %)) sends)
                  (= (mapv vec ps) (mapv (comp vec :bytes) recvs))
                  (= :would-block (:result after)))
             {:lens (mapv alength ps) :recv-lens (mapv (comp alength :bytes) recvs)
              :after after}))
    ;; short send: sendto on SOCK_DGRAM is all-or-nothing (macOS, Linux); a short
    ;; count cannot be produced naturally, so inject one through the seam.
    (let [p (pattern 100 3)
          s (binding [*sendto* (fn [fd buf len fl sa sl] [(dec len) 0])]
              (send! a p to-b))
          r (recv! b N)]
      (check 1 "[injected] short send surfaces :short-send, nothing completes it"
             (and (= :short-send (:result s)) (= :would-block (:result r)))
             {:send s :recv-after r}))))


;; Item 2: sizes, EMSGSIZE, oversize
(defn item-2
  [a b N]
  (let [to-b {:ip "127.0.0.1" :port (:port (local b))}]
    (doseq [[label len expect] [["N" N :packet] ["N+1" (inc N) :oversize]
                                ["N+100 (> recv buffer N+1)" (+ N 100) :oversize]]]
      (let [p (pattern len 11)
            s (send! a p to-b)
            r (recv-wait b N)]
        (check 2 (str "N=" N " send " label " (" len " bytes) -> " expect)
               (and (= :sent (:result s)) (= expect (:result r))
                    (if (= expect :packet) (same-bytes? p (:bytes r)) (not (contains? r :bytes))))
               {:send s :recv (dissoc r :bytes)})))
    ;; the oversize packet was consumed whole: nothing of it is left
    (check 2 (str "N=" N " oversize consumed whole, queue empty") (= :would-block (:result (recv! b N)))
           (recv! b N))))


(defn emsgsize
  [a b]
  (let [to-b {:ip "127.0.0.1" :port (:port (local b))}
        sndbuf (native/get-int-option a native/sol-socket SO-SNDBUF)
        rcvbuf (native/get-int-option b native/sol-socket SO-RCVBUF)
        probe (fn [len] (send! a (pattern len 1) to-b))
        ;; largest len that sends, by bisection over [1, 65507]
        ;; (65507 is the IPv4 UDP payload limit; macOS stops lower, at
        ;; net.inet.udp.maxdgram / SO_SNDBUF)
        max-ok (loop [lo 1 hi 65507]
                 (if (>= lo hi) lo
                     (let [mid (quot (+ lo hi 1) 2)]
                       (if (= :sent (:result (probe mid))) (recur mid hi) (recur lo (dec mid))))))
        over (probe (inc max-ok))]
    ;; drain what the bisection sent
    (drain! 2 b 65536)
    (check 2 "above platform max: sendto fails EMSGSIZE (an error, nothing sent)"
           (and (= :emsgsize (:result over)) (= EMSGSIZE (:errno over))
                (= :would-block (:result (recv! b 65536))))
           {:so-sndbuf sndbuf :so-rcvbuf rcvbuf :max-sendable max-ok
            :send-max+1 over})
    max-ok))


;; Item 3: would-block
(defn item-3
  [a b]
  (let [to-b {:ip "127.0.0.1" :port (:port (local b))}
        ;; natural: blast sends with nothing reading and a small SO_SNDBUF
        set-r (native/set-int-option! a native/sol-socket SO-SNDBUF 2048)
        sndbuf-actual (native/get-int-option a native/sol-socket SO-SNDBUF)
        p (pattern 1024 5)
        outcomes (frequencies (map #(select-keys % [:result :errno])
                                   (repeatedly 20000 #(send! a p to-b))))
        nat-wb (some #(when (= :would-block (:result (key %))) %) outcomes)]
    (record 3 "[natural] 20000 x 1024B sends, SO_SNDBUF requested 2048, no reader: outcome histogram"
            {:setsockopt set-r :so-sndbuf-read-back sndbuf-actual
             :outcomes outcomes :would-block-observed? (boolean nat-wb)})
    (record 3 "[natural] drained receiver" {:packets-delivered (drain! 3 b 2048)})
    (let [r (native/set-int-option! a native/sol-socket SO-SNDBUF 9216)
          restored (native/get-int-option a native/sol-socket SO-SNDBUF)]
      (record 3 "SO_SNDBUF restored" {:setsockopt r :so-sndbuf-read-back restored})
      ;; Linux documents socket(7): the kernel doubles the requested SO_SNDBUF
      ;; (bookkeeping overhead), clamped by net.core.wmem_max and floored at
      ;; SOCK_MIN_SNDBUF; getsockopt answers the doubled value. So the Linux
      ;; assertion is "at least twice the request (both requests are below
      ;; wmem_max 212992 on shiva)", never equality. macOS keeps its read-back
      ;; as a RECORD (unchanged: requested value read back as set).
      (when (= platform :linux)
        (check 3 "[linux] SO_SNDBUF read-back follows socket(7): >= 2 x requested"
               (and (zero? (first set-r)) (>= sndbuf-actual (* 2 2048))
                    (zero? (first r)) (>= restored (* 2 9216)))
               {:requested [2048 9216] :read-back [sndbuf-actual restored]})))
    ;; injected: the seam answers -1 with each errno; nothing reaches the peer
    (doseq [e [EAGAIN ENOBUFS]]
      (let [s (binding [*sendto* (fn [& _] [-1 e])] (send! a p to-b))]
        (check 3 (str "[injected] sendto -1/errno " e " -> :would-block, no packet accepted")
               (and (= {:result :would-block :errno e} s) (= :would-block (:result (recv! b 2048))))
               {:send s})))
    ;; real successful syscall after the injected ones
    (let [s (send! a p to-b) r (recv-wait b 2048)]
      (check 3 "[natural] real sendto/recvfrom succeed after injection"
             (and (= :sent (:result s)) (same-bytes? p (:bytes r))) {:send s :recv-len (some-> (:bytes r) alength)}))
    ;; receive side would-block is natural on loopback
    (let [r (recv! b 2048)]
      (check 3 (str "[natural] recvfrom on empty socket -> :would-block errno " EAGAIN)
             (= {:result :would-block :errno EAGAIN} r) r))))


;; Item 4: poller, fibers, cancellation, close
(defn item-4
  [a b N]
  (let [to-b {:ip "127.0.0.1" :port (:port (local b))}
        trace (atom [])
        note! (fn [x] (swap! trace conj [(long (poller/clock-ms)) x]))
        ticks (atom 0)
        waiter (fibers/spawn (fn []
                               (note! :waiter-start)
                               (let [r (recv-wait b N)] (note! [:waiter-got (:result r) (:waits r)]) r)))
        worker (fibers/spawn (fn []
                               (dotimes [i 50] (swap! ticks inc) (Thread/sleep 2))
                               (note! [:worker-ticked @ticks :waiter-state (fibers/state waiter)])
                               (note! [:worker-sends (send! a (pattern 77 9) to-b)])))
        _ (fibers/join worker)
        r (fibers/join waiter 5000 :timeout)]
    (check 4 "empty receive parks the fiber; another fiber progresses; packet wakes the waiter"
           (and (map? r) (= :packet (:result r)) (= 77 (alength (:bytes r)))
                (>= (:waits r) 1)
                (some #(= [:worker-ticked 50 :waiter-state :parked] (second %)) @trace))
           {:trace @trace :recv (dissoc r :bytes)}))
  ;; cancellation: a parked waiter is released by cancel!, takes no packet
  (let [c (udp-open)
        out (atom nil)
        f (fibers/spawn (fn []
                          (poller/wait-ready c :read)
                          (reset! out {:after-wake (recv! c N)}) :released))
        _ (Thread/sleep 50)
        st (fibers/state f)
        _ (poller/cancel! c)
        jr (fibers/join f 2000 :timeout)]
    (check 4 "cancel! releases a parked receive; retry is :would-block (no packet)"
           (and (= st :parked) (= jr :released) (= :would-block (get-in @out [:after-wake :result])))
           {:state-before-cancel st :join jr :out @out})
    ;; a wait on a cancelled fd returns at once
    (let [t0 (poller/clock-ms) _ (poller/wait-ready c :read) dt (- (poller/clock-ms) t0)]
      (check 4 "wait on cancelled fd returns immediately" (< dt 100) {:ms dt}))
    (poller/forget! c)
    (let [[r e] (native/c-close c)]
      (check 4 "close after forget!" (zero? r) {:close [r e]}))
    (let [r (recv! c N)]
      (check 4 "recvfrom on closed fd is an error (EBADF), not a packet/would-block"
             (= {:result :error :errno EBADF} r) r))
    (check 4 "poller table holds no entry for the closed fd"
           (not (contains? (:fds (poller/debug-state)) c)) {:debug (poller/debug-state)})))


(defn -main
  []
  (println "jolt UDP probe, platform=" platform "os.name=" (System/getProperty "os.name")
           "os.arch=" (System/getProperty "os.arch") "constants=" (pr-str consts))
  (let [a (udp-open) b (udp-open)
        max-ok (emsgsize a b)
        big (- (* 8 1024) 0)]  ; configured larger N, below max-ok
    (println "platform max sendable =" max-ok "configured larger N =" big)
    (check 2 "configured larger N is below the observed platform send maximum"
           (< 1200 big max-ok) {:N 1200 :larger-N big :max-sendable max-ok})
    (item-1 a b 1200)
    (item-2 a b 1200)
    (item-1 a b big)
    (item-2 a b big)
    (item-3 a b)
    (item-4 a b 1200)
    (let [ra (close! a) rb (close! b)]
      (check 5 "cleanup: both sockets closed" (and (zero? (first ra)) (zero? (first rb)))
             {:a ra :b rb})
      (let [d (poller/debug-state)]
        (check 5 "cleanup: poller table has no fd, pending or waiter left"
               (and (empty? (:fds d)) (empty? (:pending d))) {:debug d}))))
  (let [rs @results]
    (let [asserts (remove :record? rs) recs (filter :record? rs)]
      (println "SUMMARY" (count asserts) "assertions:" (count (filter :ok? asserts)) "pass,"
               (count (remove :ok? asserts)) "fail;" (count recs) "records")
      (System/exit (if (every? :ok? asserts) 0 1)))))


(-main)
