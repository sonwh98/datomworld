(ns dao.stream.ws-test
  (:require [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]))


(defn- buffer
  []
  (:dao.stream/handle (ring/create! {:dao.stream/type :dao.stream/ringbuffer
                                     :dao.stream.ringbuffer/capacity 16})))


(def admission
  {:retention :evict-oldest :capacity 16 :value-domain :portable-values})


(def descriptor
  {:dao.stream/type :dao.stream/ws
   :dao.stream/identity "logical-1"
   :ws/host "127.0.0.1"
   :ws/port 9182
   :ws/path "/yin/repl"})


(defn- values
  [handle]
  (loop [cursor (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest))
         seen []]
    (let [next (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome next))
        (recur (:dao.stream/cursor next) (conj seen (:dao.stream/value next)))
        seen))))


(deftest client-attachment-is-a-writer-only-nonwaiting-handle
  (let [traffic (buffer)
        adapter (atom nil)
        sent (atom [])
        attacher (ws/make-attacher
                   {:traffic {:dao.stream/handle traffic
                              :dao.stream/surface #{:writer}}
                    :admission admission
                    :connect! (fn [_ adapter-map]
                                (reset! adapter adapter-map)
                                {:send! #(do (swap! sent conj %) nil) :close! (fn [& _] nil)})})
        result (attacher descriptor)
        handle (:dao.stream/handle result)]
    (is (= :dao.stream/ok (:dao.stream/outcome result)))
    (is (string? (:dao.stream/attachment result)))
    (is (stream/writer? handle))
    (is (stream/closable? handle))
    (is (not (stream/reader? handle)))
    (is (= :dao.stream/full (:dao.stream/outcome (stream/append! handle {:x 1}))))
    ((:opened! @adapter))
    (is (= :ws/opened (:ws/event (first (values traffic)))))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! handle {:x 1}))))
    (is (= {:ws/frame :ws/value :ws/value {:x 1}}
           (transit/decode (first @sent))))))


(deftest established-attachments-reject-bad-wire-values-and-close-on-protocol-errors
  (let [traffic (buffer)
        adapter (atom nil)
        closed (atom [])
        handle (:dao.stream/handle
                 ((ws/make-attacher
                    {:traffic {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                     :admission admission
                     :connect! (fn [_ a]
                                 (reset! adapter a)
                                 {:send! (fn [_])
                                  :close! (fn [& args] (swap! closed conj args))})}) descriptor))]
    ((:opened! @adapter))
    (is (= :dao.stream/invalid-value
           (:dao.stream/outcome
             (stream/append! handle #?(:clj (Object.)
                                       :cljs (js-obj)
                                       :cljd (Object.))))))
    ((:message! @adapter) "not-transit")
    ((:closed! @adapter) ws/protocol-close-code "dao.stream/protocol-error")
    (is (= [[:ws/opened nil] [:ws/error :ws/decode-failure] [:ws/closed nil]]
           (mapv (juxt :ws/event :ws/reason) (values traffic))))
    (is (= ws/protocol-close-code (ffirst @closed)))))


(deftest close-ended-is-distinct-from-generic-detachment
  (let [traffic (buffer)
        adapter (atom nil)
        close-calls (atom [])
        handle (:dao.stream/handle
                 ((ws/make-attacher
                    {:traffic {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                     :admission admission
                     :connect! (fn [_ a]
                                 (reset! adapter a)
                                 {:send! (fn [_])
                                  :close! (fn [& args] (swap! close-calls conj args))})}) descriptor))]
    ((:opened! @adapter))
    (is (= :dao.stream/ok (:dao.stream/outcome (ws/close-ended! handle))))
    (is (= [ws/ended-close-code "dao.stream/ended"] (first @close-calls)))
    ((:closed! @adapter) ws/ended-close-code "dao.stream/ended")
    (is (= :ws/ended (:ws/event (last (values traffic)))))))


(deftest inbound-values-and-terminal-events-are-deposited-on-the-composed-medium
  (let [traffic (buffer)
        adapter (atom nil)
        attacher (ws/make-attacher
                   {:traffic {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                    :admission admission
                    :connect! (fn [_ a]
                                (reset! adapter a)
                                {:send! (fn [_]) :close! (fn [& _] nil)})})
        result (attacher descriptor)
        handle (:dao.stream/handle result)
        id (:dao.stream/attachment result)]
    ((:opened! @adapter))
    ((:message! @adapter) (transit/encode {:ws/frame :ws/value :ws/value [:yin :ready]}))
    ((:closed! @adapter) 4000 "dao.stream/ended")
    ((:closed! @adapter) 4000 "dao.stream/ended")
    (is (= [{:ws/attachment id :ws/event :ws/opened}
            {:ws/attachment id :ws/event :ws/payload :ws/value [:yin :ready]}
            {:ws/attachment id :ws/event :ws/ended}]
           (values traffic)))
    (is (= :dao.stream/closed (:dao.stream/outcome (stream/append! handle :late))))))


(deftest invalid-descriptors-and-nonportable-values-never-open-or-send
  (let [traffic (buffer)
        sends (atom 0)
        attacher (ws/make-attacher
                   {:traffic {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                    :admission admission
                    :connect! (fn [_ _] {:send! #(swap! sends inc) :close! (fn [& _] nil)})})]
    (is (= :dao.stream/invalid-descriptor
           (:dao.stream/outcome (attacher (dissoc descriptor :ws/path)))))
    (let [handle (:dao.stream/handle (attacher descriptor))]
      ;; No accept control has arrived, and a nonportable value must not send.
      (is (= :dao.stream/full (:dao.stream/outcome
                                (stream/append! handle #?(:clj (Object.)
                                                          :cljs (js-obj)
                                                          :cljd (Object.))))))
      (is (zero? @sends)))))


(deftest descriptor-bootstrap-keeps-independent-attachments-distinct
  ;; Model the process boundary explicitly: the second client receives only
  ;; the Transit descriptor value, then owns a fresh traffic medium and
  ;; attacher state. No handle or cursor is shared between the clients.
  (let [descriptor-b (transit/decode (transit/encode descriptor))
        traffic-a (buffer)
        traffic-b (buffer)
        adapters (atom [])
        make-client (fn [traffic]
                      (ws/make-attacher
                        {:traffic {:dao.stream/handle traffic
                                   :dao.stream/surface #{:writer}}
                         :admission admission
                         :connect! (fn [_ adapter]
                                     (swap! adapters conj adapter)
                                     {:send! (fn [_])
                                      :close! (fn [& _] nil)})}))
        a ((make-client traffic-a) descriptor)
        b ((make-client traffic-b) descriptor-b)
        id-a (:dao.stream/attachment a)
        id-b (:dao.stream/attachment b)]
    (is (= descriptor descriptor-b))
    (is (not= id-a id-b))
    ((:opened! (first @adapters)))
    ((:opened! (second @adapters)))
    ((:message! (first @adapters)) (transit/encode {:ws/frame :ws/value :ws/value :a}))
    ((:message! (second @adapters)) (transit/encode {:ws/frame :ws/value :ws/value :b}))
    (is (= [{:ws/attachment id-a :ws/event :ws/opened}
            {:ws/attachment id-a :ws/event :ws/payload :ws/value :a}]
           (values traffic-a)))
    (is (= [{:ws/attachment id-b :ws/event :ws/opened}
            {:ws/attachment id-b :ws/event :ws/payload :ws/value :b}]
           (values traffic-b)))))


(deftest nil-payloads-keep-an-explicit-value-key
  (let [traffic (buffer)
        adapter (atom nil)
        attacher (ws/make-attacher
                   {:traffic {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                    :admission admission
                    :connect! (fn [_ a]
                                (reset! adapter a)
                                {:send! (fn [_]) :close! (fn [& _] nil)})})
        handle (:dao.stream/handle (attacher descriptor))]
    ((:opened! @adapter))
    ((:message! @adapter) (transit/encode {:ws/frame :ws/value :ws/value nil}))
    (let [payload (last (values traffic))]
      (is (= :ws/payload (:ws/event payload)))
      ;; nil is in the portable domain: "value nil" must stay distinguishable
      ;; from "no value" for a consumer using contains?.
      (is (contains? payload :ws/value))
      (is (nil? (:ws/value payload))))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! handle nil))))))


(defn- one-slot-endpoint
  ([offer ack control] (one-slot-endpoint offer ack control {}))
  ([offer ack control bounds]
   (ws/make-endpoint
     (merge
       {:descriptor descriptor
        :control {:dao.stream/handle control :dao.stream/surface #{:writer}}
        :control-admission admission
        :slots [{:offer {:dao.stream/handle offer :dao.stream/surface #{:writer}}
                 :offer-admission (assoc admission :value-domain :host-values :capacity 1)
                 :ack {:dao.stream/handle ack :dao.stream/surface #{:writer}}
                 :ack-admission (assoc admission :value-domain :host-values :capacity 1)
                 :ack-cursor (:dao.stream/cursor (stream/cursor ack stream/anchor-newest))}]
        :expiry-ms nil}
       bounds))))


(deftest pre-accept-peer-loss-releases-its-handoff-slot
  (let [endpoint (one-slot-endpoint (buffer) (buffer) (buffer))
        socket {:send! (fn [_]) :close! (fn [& _] nil)}
        first-accept (ws/accept-connection! endpoint "/yin/repl" socket)]
    (is (= :ws/pending (:ws/status first-accept)))
    ;; Admission stays bounded while the offer is outstanding.
    (is (= :ws/full (:ws/status (ws/accept-connection! endpoint "/yin/repl" socket))))
    ;; The peer disappears before any acknowledgement.
    (ws/closed! (:ws/handle first-accept) 1006 "peer-loss")
    (ws/endpoint-step endpoint 1)
    (let [slot (first (:slots (ws/endpoint-state endpoint)))]
      (is (= :free (:status slot)))
      (is (empty? (:connections (ws/endpoint-state endpoint)))))
    (is (= :ws/pending (:ws/status (ws/accept-connection! endpoint "/yin/repl" socket))))))


(deftest a-lost-pending-connection-cannot-be-accepted-later
  (let [offer (buffer)
        ack (buffer)
        endpoint (one-slot-endpoint offer ack (buffer))
        sent (atom [])
        socket {:send! #(do (swap! sent conj %) nil) :close! (fn [& _] nil)}
        accepted (ws/accept-connection! endpoint "/yin/repl" socket)
        attachment (:ws/attachment accepted)
        traffic (buffer)]
    (ws/closed! (:ws/handle accepted) 1006 "peer-loss")
    (ws/endpoint-step endpoint 1)
    ;; A stale acknowledgement for the released slot must not open a wire.
    (stream/append! ack {:ws/attachment attachment :ws/command :ws/accept
                         :ws/deposit {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                         :ws/admission admission})
    (ws/endpoint-step endpoint 2)
    (is (empty? @sent))
    (is (empty? (values traffic)))))


(deftest endpoint-holds-offer-until-a-matching-acknowledgement
  (let [offer (buffer)
        ack (buffer)
        control (buffer)
        sent (atom [])
        endpoint (ws/make-endpoint
                   {:descriptor descriptor
                    :control {:dao.stream/handle control :dao.stream/surface #{:writer}}
                    :control-admission admission
                    :slots [{:offer {:dao.stream/handle offer :dao.stream/surface #{:writer}}
                             :offer-admission (assoc admission :value-domain :host-values :capacity 1)
                             :ack {:dao.stream/handle ack :dao.stream/surface #{:writer}}
                             :ack-admission (assoc admission :value-domain :host-values :capacity 1)
                             :ack-cursor (:dao.stream/cursor (stream/cursor ack stream/anchor-newest))}]
                    :expiry-ms nil})
        accepted (ws/accept-connection! endpoint "/yin/repl"
                                        {:send! #(do (swap! sent conj %) nil) :close! (fn [& _] nil)})
        offer-event (first (values offer))
        attachment (:ws/attachment offer-event)
        traffic (buffer)]
    (is (= :ws/pending (:ws/status accepted)))
    (is (= :ws/accepted (:ws/event offer-event)))
    (is (empty? @sent))
    ;; A stale acknowledgement cannot consume the single outstanding offer.
    (stream/append! ack {:ws/attachment "stale" :ws/command :ws/accept
                         :ws/deposit {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                         :ws/admission admission})
    (ws/endpoint-step endpoint 10)
    (is (empty? @sent))
    (stream/append! ack {:ws/attachment attachment :ws/command :ws/accept
                         :ws/deposit {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                         :ws/admission admission})
    (ws/endpoint-step endpoint 11)
    ;; The endpoint sends no admission wire frame any more: the accepted
    ;; handle is already `:open` once its acknowledgement is consumed.
    (is (empty? @sent))
    (ws/receive! (:ws/handle accepted) (transit/encode {:ws/frame :ws/value :ws/value :inbound}))
    (is (= [{:ws/attachment attachment :ws/event :ws/payload :ws/value :inbound}]
           (values traffic)))))


(deftest a-frame-before-acknowledgement-is-queued-and-replays-in-order
  (let [offer (buffer)
        ack (buffer)
        control (buffer)
        endpoint (one-slot-endpoint offer ack control)
        sent (atom [])
        accepted (ws/accept-connection! endpoint "/yin/repl"
                                        {:send! #(do (swap! sent conj %) nil)
                                         :close! (fn [& _] nil)})
        offer-event (first (values offer))
        attachment (:ws/attachment offer-event)
        traffic (buffer)]
    (is (= :ws/pending (:ws/status accepted)))
    ;; The client's own socket is open and its append! gate is live from
    ;; that moment, ahead of this endpoint's own acceptor tick -- there
    ;; is no admission handshake to hold it back any more, so a value
    ;; frame can reach this handle while the server side is still
    ;; pending.
    (ws/receive! (:ws/handle accepted)
                 (transit/encode {:ws/frame :ws/value :ws/value :early}))
    (is (empty? (values traffic))
        "nothing to deposit yet: the real target exists only after the ack")
    (stream/append! ack {:ws/attachment attachment :ws/command :ws/accept
                         :ws/deposit {:dao.stream/handle traffic
                                      :dao.stream/surface #{:writer}}
                         :ws/admission admission})
    (ws/endpoint-step endpoint 1)
    ;; A frame received after the ack must not deposit ahead of the
    ;; queued one: the grab-and-replay is atomic with the phase flip.
    (ws/receive! (:ws/handle accepted)
                 (transit/encode {:ws/frame :ws/value :ws/value :late}))
    (is (= [:early :late] (mapv :ws/value (values traffic))))))


(deftest a-frame-arriving-during-the-replay-deposits-after-older-frames
  (let [offer (buffer)
        ack (buffer)
        endpoint (one-slot-endpoint offer ack (buffer))
        accepted (ws/accept-connection! endpoint "/yin/repl"
                                        {:send! (fn [_] nil)
                                         :close! (fn [& _] nil)})
        handle (:ws/handle accepted)
        attachment (:ws/attachment (first (values offer)))
        traffic (buffer)
        injected (atom false)
        frame #(transit/encode {:ws/frame :ws/value :ws/value %})
        ;; The first deposit of the replay itself delivers a new frame,
        ;; forcing delivery to overlap the acknowledgement's replay.
        target (reify stream/IDaoStreamWriter
                 (append!
                   [_ event]
                   (when-not @injected
                     (reset! injected true)
                     (ws/receive! handle (frame :mid)))
                   (stream/append! traffic event)))]
    (ws/receive! handle (frame :early))
    (stream/append! ack {:ws/attachment attachment :ws/command :ws/accept
                         :ws/deposit {:dao.stream/handle target
                                      :dao.stream/surface #{:writer}}
                         :ws/admission admission})
    (ws/endpoint-step endpoint 1)
    (ws/receive! handle (frame :late))
    (is (true? @injected))
    (is (= [:early :mid :late] (mapv :ws/value (values traffic))))))


(defn- bounded-client
  "An opened client attachment composed with `bounds`, over a seam that
   records sends and close requests and merges `seam` (e.g. a
   `:queued-bytes`)."
  ([bounds] (bounded-client bounds {} transit/profile))
  ([bounds seam codec]
   (let [traffic (buffer)
         adapter (atom nil)
         sent (atom [])
         closes (atom [])
         handle (:dao.stream/handle
                  ((ws/make-attacher
                     (merge {:traffic {:dao.stream/handle traffic
                                       :dao.stream/surface #{:writer}}
                             :admission admission
                             :codec codec
                             :connect! (fn [_ a]
                                         (reset! adapter a)
                                         (merge {:send! #(do (swap! sent conj %) nil)
                                                 :close! (fn [& args] (swap! closes conj (vec args)))}
                                                seam))}
                            bounds))
                   descriptor))]
     ((:opened! @adapter))
     {:handle handle :adapter @adapter :traffic traffic :sent sent :closes closes})))


(defn- frame
  [v]
  (transit/encode {:ws/frame :ws/value :ws/value v}))


(deftest a-frame-over-max-frame-bytes-is-a-protocol-failure-before-decode
  (let [decodes (atom 0)
        codec (update transit/profile :ws/decode
                      (fn [decode] (fn [p] (swap! decodes inc) (decode p))))
        small (frame 1)
        {:keys [handle adapter traffic closes]}
        (bounded-client {:ws/max-frame-bytes (count small)} {} codec)]
    ((:message! adapter) small)
    (is (= 1 @decodes) "a frame at the bound decodes")
    ((:message! adapter) (frame "an oversize value"))
    (is (= 1 @decodes) "the oversize frame is refused before decode")
    (is (= [[1009 "dao.stream/frame-too-large"]] @closes))
    (is (= [[:ws/opened nil] [:ws/payload nil] [:ws/error :ws/frame-too-large]]
           (mapv (juxt :ws/event :ws/reason) (values traffic))))
    (is (= :dao.stream/closed (:dao.stream/outcome (stream/append! handle :late))))
    (is (= 1009 (do ((:too-large! adapter)) (first (last @closes))))
        "a host that stops reassembly reaches the same teardown")
    (is (thrown? #?(:cljd Object :clj Exception :cljs js/Error)
          (bounded-client {:ws/max-frame-bytes 0}))
        "a bound that is not a positive integer is a composition error")))


(defn- pending-overflow
  "Accept one connection on an endpoint with `bounds`, receive `frames`
   before any acknowledgement, then acknowledge late."
  [bounds frames]
  (let [offer (buffer)
        ack (buffer)
        control (buffer)
        endpoint (one-slot-endpoint offer ack control bounds)
        closes (atom [])
        accepted (ws/accept-connection! endpoint "/yin/repl"
                                        {:send! (fn [_] nil)
                                         :close! (fn [& args] (swap! closes conj (vec args)))})
        attachment (:ws/attachment accepted)
        traffic (buffer)]
    (doseq [f frames]
      (ws/receive! (:ws/handle accepted) f))
    (let [closes-before-step @closes
          control-events (mapv (juxt :ws/event :ws/reason) (values control))]
      (ws/endpoint-step endpoint 1)
      (let [slot-status (:status (first (:slots (ws/endpoint-state endpoint))))]
        (stream/append! ack {:ws/attachment attachment :ws/command :ws/accept
                             :ws/deposit {:dao.stream/handle traffic
                                          :dao.stream/surface #{:writer}}
                             :ws/admission admission})
        (ws/endpoint-step endpoint 2)
        {:closes closes-before-step
         :control control-events
         :slot-status slot-status
         :traffic (values traffic)}))))


(deftest pending-frames-over-count-tear-down-before-acceptance
  (let [r (pending-overflow {:ws/max-pending-frames 2} [(frame :a) (frame :b) (frame :c)])]
    (is (= [[1013 "dao.stream/pending-overflow"]] (:closes r)))
    (is (= [[:ws/error :ws/pending-overflow]] (:control r))
        "the diagnostic lands on the endpoint's control medium")
    (is (= :free (:slot-status r)) "endpoint-step released the slot")
    (is (empty? (:traffic r)) "the late acknowledgement is stale")))


(deftest pending-frames-over-bytes-tear-down-before-acceptance
  (let [f (frame :a)
        r (pending-overflow {:ws/max-pending-bytes (inc (count f))} [f (frame :b)])]
    (is (= [[1013 "dao.stream/pending-overflow"]] (:closes r)))
    (is (= [[:ws/error :ws/pending-overflow]] (:control r)))
    (is (= :free (:slot-status r)))
    (is (empty? (:traffic r))))
  (let [f (frame :a)
        r (pending-overflow {:ws/max-pending-bytes (* 2 (count f))} [f (frame :b)])]
    (is (empty? (:closes r)) "two frames that fit the byte bound stay queued")))


(deftest outbound-high-water-answers-full-and-max-tears-down
  (let [queued (atom 0)
        {:keys [handle traffic sent closes]}
        (bounded-client {:ws/outbound-high-water 100 :ws/max-outbound-bytes 200}
                        {:queued-bytes #(deref queued)} transit/profile)
        outcome #(:dao.stream/outcome (stream/append! handle %))]
    (is (= :dao.stream/ok (outcome :a)))
    (reset! queued 100)
    (is (= :dao.stream/full (outcome :b)) "at high-water the append is refused, transiently")
    (reset! queued 199)
    (is (= :dao.stream/full (outcome :b)))
    (is (= 1 (count @sent)) "nothing crossed while full")
    (reset! queued 0)
    (is (= :dao.stream/ok (outcome :b)) "a drained backlog accepts again")
    (reset! queued 200)
    (is (= :dao.stream/closed (outcome :c)) "at max the connection is torn down")
    (is (= [[1008 "dao.stream/outbound-overflow"]] @closes))
    (is (= [:ws/error :ws/outbound-overflow]
           ((juxt :ws/event :ws/reason) (last (values traffic)))))
    (reset! queued 0)
    (is (= :dao.stream/closed (outcome :d)) "closed afterwards")
    (is (= 2 (count @sent)))))


(deftest a-seam-without-queued-bytes-uses-the-cumulative-quota
  (let [n (count (frame :x))
        {:keys [handle sent closes]}
        (bounded-client {:ws/outbound-high-water 1 :ws/max-outbound-bytes (* 2 n)})
        outcome #(:dao.stream/outcome (stream/append! handle %))]
    (is (= :dao.stream/ok (outcome :x))
        "high-water needs the seam's count; the quota alone applies")
    (is (= :dao.stream/ok (outcome :y)))
    (is (= :dao.stream/closed (outcome :z)) "the quota is spent")
    (is (= [[1008 "dao.stream/outbound-overflow"]] @closes))
    (is (= 2 (count @sent)))))


(defn- handoff-slot
  []
  (let [offer (buffer)
        ack (buffer)]
    {:offer {:dao.stream/handle offer :dao.stream/surface #{:writer}}
     :offer-admission (assoc admission :value-domain :host-values :capacity 1)
     :ack {:dao.stream/handle ack :dao.stream/surface #{:writer}}
     :ack-admission (assoc admission :value-domain :host-values :capacity 1)
     :ack-cursor (:dao.stream/cursor (stream/cursor ack stream/anchor-newest))}))


(deftest endpoint-stop-closes-pending-connections-and-frees-slots
  (let [control (buffer)
        slots [(handoff-slot) (handoff-slot)]
        endpoint (ws/make-endpoint
                   {:descriptor descriptor
                    :control {:dao.stream/handle control :dao.stream/surface #{:writer}}
                    :control-admission admission
                    :slots slots
                    :expiry-ms nil})
        closes (atom [])
        sent (atom [])
        socket (fn [tag]
                 {:send! #(do (swap! sent conj %) nil)
                  :close! (fn [code reason] (swap! closes conj [tag code reason]))})
        a (ws/accept-connection! endpoint "/yin/repl" (socket :a))
        b (ws/accept-connection! endpoint "/yin/repl" (socket :b))]
    (is (= [:ws/pending :ws/pending] (mapv :ws/status [a b])))
    (is (= endpoint (ws/endpoint-stop! endpoint)))
    (is (= #{[:a 1001 "dao.stream/endpoint-stopped"]
             [:b 1001 "dao.stream/endpoint-stopped"]}
           (set @closes))
        "both seams saw 1001 endpoint-stopped")
    (is (= #{[(:ws/attachment a) :ws/closed] [(:ws/attachment b) :ws/closed]}
           (set (map (juxt :ws/attachment :ws/event) (values control))))
        "both terminal events on the control medium")
    (is (= [:free :free] (mapv :status (:slots (ws/endpoint-state endpoint)))))
    (is (empty? (:connections (ws/endpoint-state endpoint))))
    ;; A late acknowledgement for a stopped connection is stale.
    (let [traffic (buffer)]
      (stream/append! (get-in slots [0 :ack :dao.stream/handle])
                      {:ws/attachment (:ws/attachment a) :ws/command :ws/accept
                       :ws/deposit {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                       :ws/admission admission})
      (ws/endpoint-step endpoint 1)
      (is (empty? (values traffic)))
      (is (= :dao.stream/closed
             (:dao.stream/outcome (stream/append! (:ws/handle a) :late))))
      (is (empty? @sent)))
    ;; A second endpoint-stop! is a no-op.
    (ws/endpoint-stop! endpoint)
    (is (= 2 (count @closes)))
    (is (= 2 (count (values control))))))


(def ^:private port-zero
  (assoc descriptor :dao.stream/identity "ws://127.0.0.1:0/yin/repl" :ws/port 0))


(deftest a-served-descriptor-may-name-port-zero-and-a-dialed-one-may-not
  (is (ws/servable-descriptor? port-zero))
  (is (not (ws/descriptor? port-zero)))
  (is (ws/servable-descriptor? descriptor))
  (is (not (ws/servable-descriptor? (assoc descriptor :ws/port -1))))
  (is (some? (one-slot-endpoint (buffer) (buffer) (buffer) {:descriptor port-zero})))
  (let [attacher (ws/make-attacher
                   {:traffic {:dao.stream/handle (buffer) :dao.stream/surface #{:writer}}
                    :admission admission
                    :connect! (fn [_ _] {:send! (fn [_]) :close! (fn [& _] nil)})})]
    (is (= :dao.stream/invalid-descriptor
           (:dao.stream/outcome (attacher port-zero))))))


(deftest endpoint-bound-renames-later-session-handles
  (let [endpoint (one-slot-endpoint (buffer) (buffer) (buffer) {:descriptor port-zero})
        _ (is (identical? endpoint (ws/endpoint-bound! endpoint 4567)))
        accepted (ws/accept-connection! endpoint "/yin/repl"
                                        {:send! (fn [_]) :close! (fn [& _] nil)})
        d (stream/descriptor (:ws/handle accepted))]
    (is (= :ws/pending (:ws/status accepted)))
    (is (= "ws://127.0.0.1:4567/yin/repl" (:dao.stream/identity d)))
    (is (= 4567 (get-in d [:dao.stream/descriptor :ws/port])))))
