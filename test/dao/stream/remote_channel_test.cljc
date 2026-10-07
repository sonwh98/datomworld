(ns dao.stream.remote-channel-test
  "The stepped channel composition (dao.stream.remote.md 3.0, 3.1) over
   the in-process loopback net: the descriptor formatted below the
   boundary, resolve and attach, the production profile reaching every
   layer, stream-side liveness (an idle healthy channel against a
   blackholed request, a flood that cannot defer expiry, a lost close
   event recovered by the deadline), explicit stop, lifecycle gaps,
   and the session bounds."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.loopback-net :as net]
            [dao.stream.remote-channel :as rc]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.ws :as ws]
            [dao.stream.ws-project :as ws-project]))


(def ^:private spec {:host "127.0.0.1" :port 9 :path "/x"})


(def ^:private n "toy")


(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- values
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h stream/anchor-oldest))
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- identity-of
  [h]
  (:dao.stream/identity (stream/descriptor h)))


(defn- toy
  "The section-5 toy: a one-value ring."
  []
  (let [r (ring 16)]
    (stream/append! r :v)
    r))


(defn- host-of
  [lnet]
  {:connect! (net/connect-on lnet)
   :bind! (net/listen-on lnet)
   :unbind! (net/unbind-on lnet)})


(defn- serve
  ([lnet source] (serve lnet source {} (host-of lnet)))
  ([lnet source bounds] (serve lnet source bounds (host-of lnet)))
  ([_lnet source bounds host]
   (rc/serve {:spec spec :host host
              :table {(identity-of source) {:handle source :surface #{:reader}}}
              :names {n (identity-of source)}
              :bounds bounds})))


(defn- dial
  ([lnet] (dial lnet {} nil))
  ([lnet bounds events]
   (rc/dial {:spec spec :host (host-of lnet) :name n :bounds bounds
             :events events})))


(defn- world
  "A served toy and one dial, as atoms the driver steps."
  ([] (world {}))
  ([bounds]
   (let [lnet (net/loopback-net)
         source (toy)
         events (ring 64)]
     {:net lnet :source source :events events
      :server (atom (serve lnet source bounds))
      :dials [(atom (dial lnet bounds events))]})))


(defn- tick!
  "One driver turn at `now`: the net, the server, every dial, the net."
  [{:keys [net server dials]} now]
  (net/pump! net)
  (swap! server rc/serve-step now)
  (doseq [d dials] (swap! d rc/dial-step now))
  (net/pump! net))


(defn- run-until
  "Tick from `now` by `dt` until `done?` or `limit` ticks; answers the
   last `now` ticked."
  [w now dt limit done?]
  (loop [now now left limit]
    (tick! w now)
    (if (or (done?) (zero? left))
      now
      (recur (+ now dt) (dec left)))))


(defn- settle
  "Ask `op` until it answers neither blocked nor a retryable error,
   ticking the world at `now` between asks."
  [w now op]
  (loop [left 50]
    (let [r (op)]
      (if (and (pos? left)
               (or (= :dao.stream/blocked (:dao.stream/outcome r))
                   (true? (:dao.stream/retry? r))))
        (do (tick! w now) (recur (dec left)))
        r))))


(defn- first-dial
  [w]
  (first (:dials w)))


(defn- attach!
  "Drive `w` at `now` until its first dial is attached; answers the
   handle."
  [w now]
  (let [d (first-dial w)]
    (run-until w now 0 50 #(not= :resolving (:status @d)))
    (rc/handle @d)))


(defn- read-past-value
  "Read the toy's one value through `h`; answers the cursor after it."
  [w now h]
  (let [c (settle w now #(stream/cursor h stream/anchor-oldest))
        r (settle w now #(stream/next h (:dao.stream/cursor c)))]
    (is (= :v (:dao.stream/value r)) (pr-str r))
    (:dao.stream/cursor r)))


(defn- ws-handle
  [d]
  (:handle (ws-project/channel (:dial d))))


(defn- expired-events
  [events]
  (filterv #(= :dao.stream.remote/channel-expired (:dao.stream.remote/event %))
           (values events)))


(def ^:private channel-gone
  {:dao.stream/outcome :dao.stream/transport-error
   :dao.stream.remote/reason :dao.stream.remote/channel-gone})


;; =============================================================================
;; Address data and attachment
;; =============================================================================

(deftest serve-formats-the-descriptor-below-the-boundary
  (let [lnet (net/loopback-net)
        expected {:dao.stream/type :dao.stream/ws
                  :dao.stream/identity "ws://127.0.0.1:9/x"
                  :ws/host "127.0.0.1"
                  :ws/port 9
                  :ws/path "/x"}]
    (is (= expected (rc/descriptor-of spec)))
    (is (ws/descriptor? (rc/descriptor-of spec)))
    (is (= expected (:descriptor (serve lnet (toy)))))
    (is (not-any? #(= "ws" (namespace %)) (keys spec)) "no :ws/ key in the spec")
    (testing "loopback literals"
      (is (every? rc/loopback-literal? ["127.0.0.1" "127.1.2.3" "::1" "[::1]"]))
      (is (not-any? rc/loopback-literal?
                    ["0.0.0.0" "localhost" "127.0.0.1." "128.0.0.1" "" nil])))))


(deftest a-reader-resolves-and-attaches-over-the-stepped-composition
  (let [w (world)
        d (first-dial w)]
    (is (= :starting (:status @(:server w))))
    (is (= :resolving (:status @d)))
    (let [h (attach! w 0)]
      (is (= :serving (:status @(:server w))))
      (is (= :attached (:status @d)) (pr-str (:outcome @d)))
      (is (= (identity-of (:source w)) (:identity @d)))
      (is (= (identity-of (:source w)) (identity-of h)))
      (read-past-value w 0 h))))


(deftest production-bounds-reach-every-layer
  (let [lnet (net/loopback-net)
        server (rc/serve {:spec spec :host (host-of lnet)
                          :table {"s" {:handle (toy) :surface #{:reader}}}})
        endpoint (:endpoint server)
        d (rc/dial {:spec spec :host (host-of lnet) :name n})
        p rc/production-bounds]
    (is (= 20 (count p)))
    (is (= {:max-sessions 64 :idle-timeout 60000 :step-budget 64
            :mirror-budget 64 :chase-budget 32}
           (select-keys @(:acceptor server)
                        [:max-sessions :idle-timeout :step-budget
                         :mirror-budget :chase-budget])))
    (is (= {:ws/max-frame-bytes 4194304 :ws/max-pending-frames 64
            :ws/max-pending-bytes 1048576 :ws/outbound-high-water 4194304
            :ws/max-outbound-bytes 16777216}
           (:bounds endpoint)))
    (is (= 15000 (:expiry-ms (:config endpoint))))
    (is (= 8 (count (:slots (ws/endpoint-state endpoint)))))
    (is (= {:dao.stream.remote/budget 8
            :dao.stream.remote/resend-after nil
            :dao.stream.remote/drain-budget 256
            :dao.stream.remote/max-outstanding 256
            :dao.stream.remote/max-filed 1024
            :dao.stream.remote/give-up-after 15000}
           (:policy @(:dial d))))
    (is (= {:step-budget 64 :mirror-budget 64 :chase-budget 32}
           (select-keys @(:dial d) [:step-budget :mirror-budget :chase-budget])))
    (testing "an invalid bound is the validating layer's composition error"
      (let [lnet (net/loopback-net)]
        (doseq [b [{:max-sessions 0} {:ws/max-frame-bytes -1}
                   {:dao.stream.remote/give-up-after 0}]]
          (is (thrown? #?(:cljd Object :clj Exception :cljs :default)
                       (serve lnet (toy) b))
              (pr-str b)))
        (is (empty? (:listeners @lnet)) "nothing listened")))))


;; =============================================================================
;; Stream-side liveness
;; =============================================================================

(def ^:private liveness {:dao.stream.remote/give-up-after 150})


(defn- poll!
  "The reader's poll: one next from `c`."
  [h c]
  (stream/next h c))


(defn- ask!
  "Poll past the prefetched blocked the read of the value installed, so
   a next request is on the wire; answers the last poll's outcome."
  [h c]
  (poll! h c)
  (poll! h c))


(deftest idle-healthy-board-versus-blackholed-request
  (testing "healthy: every poll is answered, nothing expires"
    (let [w (world liveness)
          d (first-dial w)
          h (attach! w 0)
          c (read-past-value w 0 h)]
      (doseq [i (range 20)]
        (poll! h c)
        (tick! w (* 50 (inc i))))
      (is (= :attached (:status @d)))
      (is (empty? (expired-events (:events w))))))
  (testing "blackholed: the request expires at give-up-after"
    (let [w (world liveness)
          d (first-dial w)
          h (attach! w 0)
          c (read-past-value w 0 h)]
      (net/blackhole! (:net w) :client)
      (tick! w 1000)
      (ask! h c)
      (tick! w 1100)
      (is (= :attached (:status @d)) "not before the deadline")
      (tick! w 1150)
      (tick! w 1160)
      (is (= :lost (:status @d)))
      (is (= channel-gone (:outcome @d)))
      (let [es (expired-events (:events w))]
        (is (= 1 (count es)))
        (is (= :dao.stream/next (:dao.stream.remote/op (first es)))))
      (is (= :dao.stream/closed
             (:dao.stream/outcome (stream/append! (ws-handle @d) :x)))
          "the ws handle is closed"))))


(deftest unrelated-traffic-flood-cannot-defer-expiry
  (let [w (world (assoc liveness :dao.stream.remote/drain-budget 4))
        d (first-dial w)
        h (attach! w 0)
        c (read-past-value w 0 h)]
    (net/blackhole! (:net w) :client)
    (tick! w 1000)
    (ask! h c)
    (doseq [now [1050 1100 1149]]
      (net/flood! (:net w) 500)
      (tick! w now))
    (is (= :attached (:status @d)) "not before the deadline")
    (net/flood! (:net w) 500)
    (tick! w 1150)
    (is (= 1 (count (expired-events (:events w)))) "expired at the deadline tick")
    (tick! w 1160)
    (is (= :lost (:status @d)))
    (is (= channel-gone (:outcome @d)))))


(deftest a-lost-close-event-is-recovered-by-the-deadline
  (let [w (world (assoc liveness :capacity 8))
        d (first-dial w)
        h (attach! w 0)
        c (read-past-value w 0 h)
        traffic (:dao.stream/handle (:traffic @(:dial @d)))]
    (tick! w 1000)
    (ask! h c)
    ;; The connection closes before the request is delivered, and the
    ;; client's traffic medium is full of unread events, so :ws/closed is
    ;; evicted before the projection reads it.
    (doseq [conn (:conns @(:net w))] (net/close-conn! conn 1006 "gone"))
    (dotimes [i 8]
      (stream/append! traffic {:ws/attachment "other" :ws/event :ws/payload
                               :ws/value i}))
    (tick! w 1050)
    (is (not (ws-project/closed? (:project (ws-project/channel (:dial @d)))))
        "the ring stays open: the close event was lost")
    (is (= :attached (:status @d)))
    (tick! w 1150)
    (tick! w 1160)
    (is (= :lost (:status @d)) "the deadline recovers the lost close")
    (is (= channel-gone (:outcome @d)))
    (is (= :dao.stream.remote/channel-gone
           (:dao.stream.remote/reason (ask! h c))))))


(deftest a-connection-that-never-opens-is-lost-at-give-up-after
  (let [lnet (net/loopback-net)
        _ (swap! lnet assoc-in [:listeners (:port spec)]
                 {:accept! (fn [& _] {}) :deposit! (fn [& _] nil)})
        d (atom (dial lnet liveness nil))
        step! (fn [now] (net/pump! lnet) (swap! d rc/dial-step now)
                (net/pump! lnet))]
    (step! 1000)
    (step! 1149)
    (is (= :resolving (:status @d)) "not before the deadline")
    (is (true? (:dao.stream/retry? (:outcome @d))))
    (step! 1150)
    (is (= :lost (:status @d)))
    (is (= channel-gone (:outcome @d)))
    (is (= :closed (:status (rc/close! @d))))))


;; =============================================================================
;; Explicit stop
;; =============================================================================

(defn- counting-unbind
  [lnet calls]
  (let [f (net/unbind-on lnet)]
    (fn [listener deposit!]
      (swap! calls inc)
      (f listener deposit!))))


(deftest stop-is-explicit-and-driver-paced
  (let [lnet (net/loopback-net)
        source (ring 16)
        calls (atom 0)
        host (assoc (host-of lnet) :unbind! (counting-unbind lnet calls))
        w {:net lnet :server (atom (serve lnet source {} host))
           :dials [(atom (dial lnet))]}
        d (first-dial w)
        h (attach! w 0)
        c (:dao.stream/cursor (settle w 0 #(stream/cursor h stream/anchor-oldest)))
        reader-ws (ws-handle @d)
        late (atom (dial lnet))]
    (is (= 1 (count (rc/sessions @(:server w)))))
    ;; A second connection reaches the endpoint, unacknowledged.
    (swap! late rc/dial-step 0)
    (net/pump! lnet)
    (is (= 1 (count (filter #(= :pending (:status %))
                            (:slots (ws/endpoint-state (:endpoint @(:server w))))))))
    ;; The reader asks; the request reaches the server before stop.
    (stream/append! source :value)
    (poll! h c)
    (net/pump! lnet)
    (swap! (:server w) rc/stop!)
    (is (= :stopping (:status @(:server w))))
    (is (= {:since nil :outcome nil} (:stop @(:server w))))
    (is (= 0 @calls) "stop! performs no I/O")
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! reader-ws :still-open)))
        "the reader's connection is still open")
    (swap! (:server w) rc/serve-step 10)
    (is (= 10 (get-in @(:server w) [:stop :since])))
    (is (= 1 @calls) "unbind! called once")
    (is (every? #(= :free (:status %))
                (:slots (ws/endpoint-state (:endpoint @(:server w)))))
        "slots free")
    (is (every? :closed? (vals (rc/sessions @(:server w)))) "sessions closed")
    (net/pump! lnet)
    (swap! d rc/dial-step 10)
    (is (= :value (:dao.stream/value (poll! h c)))
        "the outstanding next was answered in the last pass")
    (is (= :lost (:status @d)) "the reader saw its connection close")
    (is (every? (comp deref :closed?) (:conns @lnet))
        "the session and the pending connection are closed")
    (is (some #(= :ws/closed (:ws/event %))
              (values (:dao.stream/handle
                        (:control (ws/endpoint-state (:endpoint @(:server w)))))))
        "the pending connection's :ws/closed reached the control medium")
    (swap! (:server w) rc/serve-step 20)
    (is (= :stopped (:status @(:server w))))
    (is (= {:since 10 :outcome :confirmed} (:stop @(:server w))))
    (is (empty? (rc/sessions @(:server w))))
    (is (= 1 @calls))))


(deftest stop-completes-without-a-close-callback
  (let [lnet (net/loopback-net)
        host (assoc (host-of lnet) :unbind! (fn [_ _] {:dao.stream/outcome :dao.stream/ok}))
        server (-> (serve lnet (toy) {:stop-grace-ms 100} host)
                   (rc/serve-step 0)
                   rc/stop!
                   (rc/serve-step 1000))]
    (is (= :stopping (:status server)))
    (is (= :stopping (:status (rc/serve-step server 1099))) "never earlier")
    (let [s (rc/serve-step server 1100)]
      (is (= :stopped (:status s)))
      (is (= {:since 1000 :outcome ::rc/unconfirmed} (:stop s)))))
  (testing "an unbind! that fails is stopped at once"
    (let [lnet (net/loopback-net)
          host (assoc (host-of lnet) :unbind! (fn [_ _] (throw (ex-info "no" {}))))
          s (-> (serve lnet (toy) {} host) (rc/serve-step 0) rc/stop! (rc/serve-step 5))]
      (is (= :stopped (:status s)))
      (is (= ::rc/unbind-failed (get-in s [:stop :outcome]))))))


(deftest stop-while-starting-and-stop-twice-are-idempotent
  (let [lnet (net/loopback-net)
        quiet (fn [opts] {:dao.stream/outcome :dao.stream/ok :port (:bind-port opts)})
        host (assoc (host-of lnet) :bind! quiet)
        server (serve lnet (toy) {} host)
        _ (is (= :starting (:status server)))
        stopping (rc/stop! server)]
    (is (= :stopping (:status stopping)))
    (is (= stopping (rc/stop! stopping)) "stop! twice")
    (let [s (rc/serve-step stopping 0)]
      (is (= s (rc/stop! s)))
      (net/pump! lnet)
      (let [s (rc/serve-step s 1)]
        (is (= :stopped (:status s)))
        (is (= s (rc/stop! s)))
        (is (= s (rc/serve-step s 2)))))
    (let [refused (rc/serve {:spec spec :host {} :table {}})]
      (is (= refused (rc/stop! refused))))))


;; =============================================================================
;; Lifecycle observation
;; =============================================================================

(deftest a-lifecycle-gap-while-serving-is-counted-and-serving-continues
  (let [w (world)
        _ (tick! w 0)
        _ (is (= :serving (:status @(:server w))))
        deposit! (:deposit! @(:server w))]
    (dotimes [i 70] (deposit! :listener-error {:i i}))
    (tick! w 1)
    (is (= :serving (:status @(:server w))))
    (is (= 1 (:lifecycle-gaps @(:server w))))
    (is (= 8 (count (:diagnostics @(:server w)))) "the last 8 diagnostics")
    (attach! w 2)
    (is (= :attached (:status @(first-dial w))) "a reader still attaches")))


(deftest a-lifecycle-gap-while-starting-is-terminal
  (let [lnet (net/loopback-net)
        calls (atom 0)
        quiet (fn [opts]
                ((net/listen-on lnet)
                 (assoc opts :deposit! (fn [k v]
                                         (when-not (= :bind-succeeded k)
                                           ((:deposit! opts) k v))))))
        host (assoc (host-of lnet)
                    :bind! quiet
                    :unbind! (counting-unbind lnet calls))
        server (serve lnet (toy) {} host)
        d (atom (dial lnet))]
    (swap! d rc/dial-step 0)
    (net/pump! lnet)
    (is (= 1 (count (filter #(= :pending (:status %))
                            (:slots (ws/endpoint-state (:endpoint server)))))))
    (let [server (rc/serve-step server 1)
          _ (net/pump! lnet)
          _ (is (= :starting (:status server)))
          _ (is (= 1 (count (rc/sessions server)))
                "a session adopted while starting, before the gap")
          _ (dotimes [i 70] ((:deposit! server) :listener-error {:i i}))
          s (rc/serve-step server 2)]
      (is (= :refused (:status s)))
      (is (= ::rc/lifecycle-lost (:reason s)))
      (is (= 1 @calls) "unbind! called")
      (is (every? #(= :free (:status %))
                  (:slots (ws/endpoint-state (:endpoint s))))
          "the endpoint has no pending slot")
      (is (every? :closed? (vals (rc/sessions s)))
          "the session adopted before the gap is closed"))))


(deftest a-host-that-stops-under-us-is-stopped-host-stopped
  (let [w (world)
        d (first-dial w)
        _ (attach! w 0)
        sessions (rc/sessions @(:server w))]
    (is (= 1 (count sessions)))
    (net/unlisten! (:net w) (:port spec))
    (swap! (:server w) rc/serve-step 1)
    (is (= :stopped (:status @(:server w))))
    (is (= ::rc/host-stopped (get-in @(:server w) [:stop :outcome])))
    (is (every? :closed? (vals (rc/sessions @(:server w)))) "sessions closed")
    (doseq [[_ s] sessions]
      (is (= :dao.stream/closed
             (:dao.stream/outcome (stream/append! (:ring s) :x)))))
    (net/pump! (:net w))
    (swap! d rc/dial-step 1)
    (is (= :lost (:status @d)))))


;; =============================================================================
;; Session bounds under the profile
;; =============================================================================

(deftest session-cap-and-idle-expiry-hold-under-the-profile
  (let [lnet (net/loopback-net)
        source (toy)
        bounds {:max-sessions 2 :idle-timeout 100}
        w {:net lnet :server (atom (serve lnet source bounds))
           :dials (vec (repeatedly 3 #(atom (dial lnet bounds nil))))}]
    (run-until w 0 0 20 (fn [] (not-any? #(= :resolving (:status @%)) (:dials w))))
    (let [by-status (group-by (comp :status deref) (:dials w))]
      (is (= 2 (count (:attached by-status))))
      (is (= 1 (count (:lost by-status))))
      (is (= :dao.stream/transport-error
             (:dao.stream/outcome (:outcome @(first (:lost by-status))))))
      (is (= 2 (count (rc/sessions @(:server w)))))
      (let [[busy idle] (:attached by-status)
            hb (rc/handle @busy)
            hi (rc/handle @idle)
            cb (read-past-value w 0 hb)
            ci (read-past-value w 0 hi)]
        (loop [now 10]
          (when (<= now 200)
            (poll! hb cb)
            (tick! w now)
            (recur (+ now 10))))
        (is (= :attached (:status @busy)) "a reader that polls is kept")
        (is (= :lost (:status @idle)) "a reader that stopped asking is reaped")
        (is (= 1 (count (rc/sessions @(:server w)))))
        (is (= :dao.stream.remote/channel-gone
               (:dao.stream.remote/reason (ask! hi ci))))))))


(deftest a-newcomer-during-stop-is-rejected
  (let [w (world)
        d (first-dial w)]
    (tick! w 0)
    (swap! (:server w) rc/stop!)
    (run-until w 1 1 10 #(not= :resolving (:status @d)))
    (is (= :lost (:status @d)))
    (is (= :dao.stream/transport-error (:dao.stream/outcome (:outcome @d))))
    (is (empty? (rc/sessions @(:server w))) "no session")))


(deftest no-transport-and-no-port-are-refusals-as-data
  (let [lnet (net/loopback-net)
        host (host-of lnet)
        table {"s" {:handle (toy) :surface #{:reader}}}]
    (is (= ::rc/no-transport
           (:reason (rc/serve {:spec (assoc spec :transport :udp) :host host :table table}))))
    (is (= ::rc/no-transport
           (:reason (rc/serve {:spec spec :host (dissoc host :bind!) :table table}))))
    (doseq [p [0 -1 nil "9"]]
      (is (= {:status :refused :reason ::rc/no-port :spec (assoc spec :port p)}
             (rc/serve {:spec (assoc spec :port p) :host host :table table}))
          (pr-str p)))
    (is (= ::rc/invalid-table
           (:reason (rc/serve {:spec spec :host host :table {"s" {}}}))))
    (is (empty? (:listeners @lnet)) "nothing listened")
    (let [d (rc/dial {:spec spec :host (dissoc host :connect!) :name n})]
      (is (= :refused (:status d)))
      (is (= ::rc/no-transport (:reason d)))
      (is (= d (rc/dial-step d 0)))
      (is (nil? (rc/handle d))))))
