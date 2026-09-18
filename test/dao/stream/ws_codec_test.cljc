(ns dao.stream.ws-codec-test
  "The codec-parameterized `dao.stream.ws` state machine: the same
   envelopes and lifecycle run unchanged under the Transit text profile and
   the `dao.stream.cbor` binary profile, an endpoint serves both through one
   served path concurrently, and negotiation never downgrades."
  (:require [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [dao.stream.cbor :as cbor]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]))


#?(:cljd nil
   :default
   (do


     (def admission
       {:retention :evict-oldest :capacity 16 :value-domain :portable-values})


     (def handoff-admission
       (assoc admission :value-domain :host-values :capacity 1))


     (def descriptor
       {:dao.stream/type :dao.stream/ws
        :dao.stream/identity "codec-1"
        :ws/host "127.0.0.1"
        :ws/port 9182
        :ws/path "/yin/repl"})


     (defn- buffer
       ([] (buffer 16))
       ([capacity]
        (:dao.stream/handle
          (ring/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity capacity}))))


     (defn- values
       [handle]
       (loop [cursor (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest))
              seen []]
         (let [next (stream/next handle cursor)]
           (if (= :dao.stream/ok (:dao.stream/outcome next))
             (recur (:dao.stream/cursor next) (conj seen (:dao.stream/value next)))
             seen))))


     (defn- fake-socket
       "A raw-socket seam recording outbound payloads, optionally negotiating a
    subprotocol for the endpoint's codec resolution."
       ([sent] (fake-socket sent nil))
       ([sent subprotocol]
        {:send! (fn [payload] (swap! sent conj payload) nil)
         :close! (fn [& _] nil)
         :ws/subprotocol subprotocol}))


     (defn- dual-endpoint
       "A two-slot endpoint over both profiles."
       [codecs]
       (ws/make-endpoint
         {:served {"/yin/repl" descriptor}
          :codecs codecs
          :control {:dao.stream/handle (buffer) :dao.stream/surface #{:writer}}
          :control-admission admission
          :slots (mapv (fn [_]
                         (let [offer (buffer 1)
                               ack (buffer 1)]
                           {:offer {:dao.stream/handle offer :dao.stream/surface #{:writer}}
                            :offer-admission handoff-admission
                            :ack {:dao.stream/handle ack :dao.stream/surface #{:writer}}
                            :ack-admission handoff-admission
                            :ack-cursor (:dao.stream/cursor
                                          (stream/cursor ack stream/anchor-newest))}))
                       (range 2))}))


     (defn- accept-and-ack!
       "Drive one full handoff: accept a socket under `subprotocol`, take the
    handle offered on the slot the endpoint claimed, and acknowledge with a
    fresh traffic medium."
       [endpoint subprotocol]
       (let [sent (atom [])
             accepted (ws/accept-connection!
                        endpoint "/yin/repl" (fake-socket sent subprotocol) 0)
             ;; Acceptance claims the first free slot; find it while pending.
             slot (some #(when (= :pending (:status %)) %)
                        (:slots (ws/endpoint-state endpoint)))
             offer-handle (:dao.stream/handle (:offer slot))
             ack-handle (:dao.stream/handle (:ack slot))
             offer-event (first (values offer-handle))
             traffic (buffer)]
         (stream/append! ack-handle {:ws/attachment (:ws/attachment offer-event)
                                     :ws/command :ws/accept
                                     :ws/deposit {:dao.stream/handle traffic
                                                  :dao.stream/surface #{:writer}}
                                     :ws/admission admission})
         (ws/endpoint-step endpoint 1)
         {:accepted accepted :sent sent :traffic traffic
          :attachment (:ws/attachment offer-event)}))


     (deftest a-cbor-attachment-sends-and-receives-typed-binary-frames
       (let [traffic (buffer)
             sent (atom [])
             adapter (atom nil)
             attacher (ws/make-attacher
                        {:traffic {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                         :admission admission
                         :codec cbor/profile
                         :connect! (fn [_ a]
                                     (reset! adapter a)
                                     (fake-socket sent))})
             result (attacher descriptor)
             handle (:dao.stream/handle result)]
         (is (= :dao.stream/ok (:dao.stream/outcome result)))
         (is (= cbor/profile (:ws/codec @adapter)))
         (is (= :dao.stream/full (:dao.stream/outcome (stream/append! handle :early))))
         ((:binary! @adapter) (cbor/encode {:ws/frame :ws/accept}))
         (is (= :ws/opened (:ws/event (first (values traffic)))))
         (let [value (with-meta [:a (list 1)] {:line 2})]
           (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! handle value))))
           (is (= [value] (mapv :ws/value (mapv cbor/decode @sent)))
               "the payload is cbor bytes, not text and not a NUL-sentinel hybrid"))
         ((:binary! @adapter) (cbor/encode {:ws/frame :ws/value :ws/value (list 1)}))
         (is (= {:ws/attachment (:dao.stream/attachment result)
                 :ws/event :ws/payload
                 :ws/value (list 1)}
                (last (values traffic))))))


     (deftest a-text-frame-on-a-binary-profile-is-a-protocol-failure
       (let [traffic (buffer)
             adapter (atom nil)
             closes (atom [])
             _ ((ws/make-attacher
                  {:traffic {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                   :admission admission
                   :codec cbor/profile
                   :connect! (fn [_ a]
                               (reset! adapter a)
                               {:send! (fn [_] nil)
                                :close! (fn [& args] (swap! closes conj args))})})
                descriptor)]
         ((:binary! @adapter) (cbor/encode {:ws/frame :ws/accept}))
         ((:message! @adapter) "a text frame the binary profile never asked for")
         ((:closed! @adapter) ws/protocol-close-code "dao.stream/protocol-error")
         (is (= [[:ws/opened nil] [:ws/error :ws/decode-failure] [:ws/closed nil]]
                (mapv (juxt :ws/event :ws/reason) (values traffic))))
         (is (= [ws/protocol-close-code "dao.stream/protocol-error"] (first @closes)))))


     (deftest a-binary-frame-on-a-text-profile-is-a-protocol-failure
       (let [traffic (buffer)
             adapter (atom nil)
             _ ((ws/make-attacher
                  {:traffic {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                   :admission admission
                   :connect! (fn [_ a]
                               (reset! adapter a)
                               {:send! (fn [_] nil) :close! (fn [& _] nil)})})
                descriptor)]
         ((:binary! @adapter) #?(:clj (byte-array [1 2 3])
                                 :cljs (js/Uint8Array.from #js [1 2 3])
                                 :cljd [1 2 3]))
         ((:closed! @adapter) ws/protocol-close-code "dao.stream/protocol-error")
         ;; Still connecting, so the host close also deposits the unresolved
         ;; attachment's transport error before the terminal event.
         (is (= [[:ws/error :ws/decode-failure] [:ws/transport-error nil] [:ws/closed nil]]
                (mapv (juxt :ws/event :ws/reason) (values traffic))))))


     (deftest one-endpoint-serves-transit-and-cbor-concurrently
       ;; One endpoint, one served path: the transit session and the cbor session
       ;; are both live at once on the same logical-stream identity, each speaking
       ;; exactly its negotiated profile.
       (let [composed (dual-endpoint [transit/profile cbor/profile])
             transit-client (accept-and-ack! composed "dao.stream.transit-json")
             cbor-client (accept-and-ack! composed "dao.stream.cbor")]
         (is (= {:ws/frame :ws/accept} (transit/decode (first @(:sent transit-client)))))
         (is (= {:ws/frame :ws/accept} (cbor/decode (first @(:sent cbor-client)))))
         (ws/receive! (:ws/handle (:accepted transit-client))
                      (transit/encode {:ws/frame :ws/value :ws/value :transit-payload}))
         (ws/receive-binary! (:ws/handle (:accepted cbor-client))
                             (cbor/encode {:ws/frame :ws/value :ws/value (with-meta [1] {:m 1})}))
         (is (= [:transit-payload] (mapv :ws/value (values (:traffic transit-client)))))
         (is (= [(with-meta [1] {:m 1})] (mapv :ws/value (values (:traffic cbor-client)))))))


     (deftest a-subprotocol-the-endpoint-does-not-speak-is-refused-never-downgraded
       (let [endpoint (dual-endpoint [transit/profile])
             closes (atom [])
             refused (ws/accept-connection!
                       endpoint "/yin/repl"
                       {:send! (fn [_] nil)
                        :close! (fn [& args] (swap! closes conj args))
                        :ws/subprotocol "dao.stream.cbor"}
                       0)]
         (is (= :ws/unsupported-subprotocol (:ws/status refused)))
         (is (= [ws/protocol-close-code "dao.stream/subprotocol-unsupported"] (first @closes)))
         ;; Refusal precedes the handoff: no slot was consumed.
         (is (= :free (:status (first (:slots (ws/endpoint-state endpoint))))))
         (is (empty? (:connections (ws/endpoint-state endpoint))))))


     (deftest endpoint-composition-validates-its-codec-table
       (let [offer (buffer 1)
             ack (buffer 1)
             slot {:offer {:dao.stream/handle offer :dao.stream/surface #{:writer}}
                   :offer-admission handoff-admission
                   :ack {:dao.stream/handle ack :dao.stream/surface #{:writer}}
                   :ack-admission handoff-admission
                   :ack-cursor (:dao.stream/cursor (stream/cursor ack stream/anchor-newest))}
             base {:served {"/yin/repl" descriptor}
                   :control {:dao.stream/handle (buffer) :dao.stream/surface #{:writer}}
                   :control-admission admission
                   :slots [slot]}]
         (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
               (ws/make-endpoint (assoc base :codecs [{:ws/subprotocol "x"}]))))
         (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
               (ws/make-endpoint (assoc base
                                        :codecs [transit/profile transit/profile])))
             "one subprotocol cannot be offered twice"))
       ;; A descriptor is portable under every offered profile or none at all.
       (is (ws/descriptor? descriptor cbor/profile)))


     (deftest the-transit-default-is-unchanged-when-no-subprotocol-is-named
       ;; Direct composition and unit tests pass a plain seam; it must keep
       ;; meaning the Transit profile, exactly as before the codec work.
       (let [client (accept-and-ack! (dual-endpoint [transit/profile cbor/profile]) nil)]
         (is (= {:ws/frame :ws/accept} (transit/decode (first @(:sent client)))))))))
