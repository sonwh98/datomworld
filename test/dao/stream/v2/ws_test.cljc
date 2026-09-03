(ns dao.stream.v2.ws-test
  (:require [clojure.test :refer [deftest is]]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ring]
            [dao.stream.v2.transit :as transit]
            [dao.stream.v2.ws :as ws]))


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
           (:dao.stream/outcome (stream/append! handle #?(:clj (Object.) :cljs (js-obj))))))
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
                                (stream/append! handle #?(:clj (Object.) :cljs (js-obj))))))
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
  [offer ack control]
  (ws/make-endpoint
    {:served {"/yin/repl" descriptor}
     :control {:dao.stream/handle control :dao.stream/surface #{:writer}}
     :control-admission admission
     :slots [{:offer {:dao.stream/handle offer :dao.stream/surface #{:writer}}
              :offer-admission (assoc admission :value-domain :host-values :capacity 1)
              :ack {:dao.stream/handle ack :dao.stream/surface #{:writer}}
              :ack-admission (assoc admission :value-domain :host-values :capacity 1)
              :ack-cursor (:dao.stream/cursor (stream/cursor ack stream/anchor-newest))}]
     :expiry-ms nil}))


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
                   {:served {"/yin/repl" descriptor}
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
    (is (= {:ws/frame :ws/accept} (transit/decode (first @sent))))
    (ws/receive! (:ws/handle accepted) (transit/encode {:ws/frame :ws/value :ws/value :inbound}))
    (is (= [{:ws/attachment attachment :ws/event :ws/payload :ws/value :inbound}]
           (values traffic)))))
