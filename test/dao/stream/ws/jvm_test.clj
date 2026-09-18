(ns dao.stream.ws.jvm-test
  "The JVM send seam: acceptance is not delivery, and failure is once.

   `send!` must return as soon as the host has taken the message and never
   join its future, or a pending send parks inside dao.stream's `append!`
   and no interpreter deadline can bound it. Every future in a chain completes
   exceptionally when its predecessor does, so a lost socket must still abort
   and report exactly once, even when the report re-enters through the
   adapter. These tests drive a reified java.net.http.WebSocket whose futures
   the test completes by hand and record host calls and adapter deposits in
   one ordered trace, so nothing here touches a network or a clock.

   One asymmetry to know: the scripted socket's `abort` only records, while a
   real `WebSocket.abort()` invokes the listener's `onError` and so deposits a
   second `:ws/error`. That extra deposit is a diagnostic the blocking client
   drops, and the terminal event stays once-only through `ws/closed!`'s own
   guard, so J5 holds either way -- but the abort-to-onError re-entry is not
   what these tests exercise."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.cbor :as cbor]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            [dao.stream.ws.jvm :as jvm])
  (:import [java.io ByteArrayOutputStream]
           [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.nio ByteBuffer]
           [java.util.concurrent CompletableFuture]))


(defn- scripted
  "A connection whose socket records into one ordered `trace` and hands back
   futures the test completes by hand. `adapter-fn` receives the trace atom
   and returns the adapter, so a test can re-enter the seam from a deposit."
  ([] (scripted nil))
  ([adapter-fn]
   (let [trace (atom [])
         futures (atom [])
         socket (reify WebSocket
                  (sendText
                    [_ data _last?]
                    (let [f (CompletableFuture.)]
                      (swap! trace conj [:send-text (str data)])
                      (swap! futures conj f)
                      f))

                  (sendClose
                    [_ code reason]
                    (let [f (CompletableFuture.)]
                      (swap! trace conj [:send-close code reason])
                      (swap! futures conj f)
                      f))

                  (abort [_] (swap! trace conj [:abort]))

                  (request [_ _n])

                  (isOutputClosed [_] false)

                  (isInputClosed [_] false)

                  (getSubprotocol [_] "")

                  (sendBinary [_ _d _l] (CompletableFuture/completedFuture nil))

                  (sendPing [_ _d] (CompletableFuture/completedFuture nil))

                  (sendPong [_ _d] (CompletableFuture/completedFuture nil)))
         connection (atom {:socket socket :future nil :close-request nil
                           :pending nil :failed? false})
         seam-box (atom nil)
         adapter (if adapter-fn
                   (adapter-fn trace seam-box)
                   {:message! (fn [m] (swap! trace conj [:message! m]))
                    :error! (fn [] (swap! trace conj [:error!]))
                    :closed! (fn [code reason]
                               (swap! trace conj [:closed! code reason]))})
         seam (jvm/client-socket connection adapter)]
     (reset! seam-box seam)
     {:trace trace :futures futures :connection connection :seam seam})))


(deftest send-returns-on-acceptance-and-serializes-behind-a-pending-future
  (testing "the submitter is never parked, and one message is in flight"
    (let [{:keys [trace futures seam]} (scripted)
          send! (:send! seam)]
      (is (nil? (send! "first"))
          "send! returns immediately rather than joining the future")
      (is (= [[:send-text "first"]] @trace))
      (is (nil? (send! "second"))
          "a second send returns immediately too")
      (is (= [[:send-text "first"]] @trace)
          "the second message is chained, not issued: java.net.http.WebSocket
           fails an overlapping sendText")
      (.complete ^CompletableFuture (first @futures) nil)
      (is (= [[:send-text "first"] [:send-text "second"]] @trace)
          "completing the first send releases the second, in submission order")
      (.complete ^CompletableFuture (second @futures) nil)
      (is (= [[:send-text "first"] [:send-text "second"]] @trace)
          "the chain is idle once both have completed"))))


(deftest a-failed-send-reports-error-then-abort-then-closed-exactly-once
  (testing "one lost socket reports once, however many sends were queued"
    (let [{:keys [trace futures seam]} (scripted)]
      ((:send! seam) "one")
      ((:send! seam) "two")
      ((:send! seam) "three")
      (is (= [[:send-text "one"]] @trace)
          "only the head was issued; the rest are chained behind it")
      (.completeExceptionally ^CompletableFuture (first @futures)
                              (ex-info "socket went away" {}))
      (is (= [[:send-text "one"]
              [:error!]
              [:abort]
              [:closed! 1006 "dao.stream/send-failed"]]
             @trace)
          "error, then abort, then the terminal event -- exactly once, though
           all three chained futures completed exceptionally, and the queued
           messages are never issued to a socket that is gone"))))


(deftest a-failed-connection-accepts-no-further-sends
  (testing "the connection stays failed rather than resuming a dead chain"
    (let [{:keys [trace futures seam]} (scripted)]
      ((:send! seam) "one")
      (.completeExceptionally ^CompletableFuture (first @futures)
                              (ex-info "gone" {}))
      (let [after @trace]
        (is (= {:dao.stream/outcome :dao.stream/closed}
               ((:send! seam) "after the failure"))
            "a later send answers closed, not the retryable full that false
             would mean: the connection is permanently gone, and a request
             retained as unsent would wait for a socket never coming back")
        (is (nil? ((:close! seam) 1000 "bye"))
            "closing something already gone is satisfied, not refused")
        (is (= after @trace)
            "and neither touches the socket or deposits anything further")))))


(deftest close-waits-its-turn-behind-a-pending-send
  (testing "a close cannot race an in-flight send into IllegalStateException"
    (let [{:keys [trace futures seam]} (scripted)]
      ((:send! seam) "in flight")
      (is (nil? ((:close! seam) 1000 "bye"))
          "close! returns immediately rather than waiting for the send")
      (is (= [[:send-text "in flight"]] @trace)
          "sendClose is chained behind the pending send")
      (.complete ^CompletableFuture (first @futures) nil)
      (is (= [[:send-text "in flight"] [:send-close 1000 "bye"]] @trace)
          "and issues once the send completes"))))


(deftest an-inline-failure-reports-without-holding-the-submission-lock
  (testing "an already-failed send runs its observer inline, off the monitor"
    (let [trace (atom [])
          holds (atom [])
          connection (atom nil)
          socket (reify WebSocket
                   (sendText
                     [_ data _last?]
                     (swap! trace conj [:send-text (str data)])
                     ;; already exceptional: whenComplete runs inline, on the
                     ;; submitting thread
                     (doto (CompletableFuture.)
                       (.completeExceptionally (ex-info "refused" {}))))

                   (sendClose
                     [_ code reason]
                     (swap! trace conj [:send-close code reason])
                     (CompletableFuture/completedFuture nil))

                   (abort [_] (swap! trace conj [:abort]))

                   (request [_ _n])

                   (isOutputClosed [_] false)

                   (isInputClosed [_] false)

                   (getSubprotocol [_] "")

                   (sendBinary [_ _d _l] (CompletableFuture/completedFuture nil))

                   (sendPing [_ _d] (CompletableFuture/completedFuture nil))

                   (sendPong [_ _d] (CompletableFuture/completedFuture nil)))
          record-hold! (fn [tag]
                         (swap! holds conj
                                [tag (Thread/holdsLock @connection)]))
          adapter {:message! (fn [m] (swap! trace conj [:message! m]))
                   :error! (fn []
                             (swap! trace conj [:error!])
                             (record-hold! :error!))
                   :closed! (fn [code reason]
                              (swap! trace conj [:closed! code reason])
                              (record-hold! :closed!))}]
      (reset! connection (atom {:socket socket :future nil :close-request nil
                                :pending nil :failed? false}))
      (let [seam (jvm/client-socket @connection adapter)]
        ((:send! seam) "doomed")
        (is (= [[:send-text "doomed"]
                [:error!]
                [:abort]
                [:closed! 1006 "dao.stream/send-failed"]]
               @trace)
            "the inline failure reports in order")
        (is (= [[:error! false] [:closed! false]] @holds)
            "and does so without holding the submission monitor, so other
             submitters are not blocked across the adapter's deposits")))))


(deftest teardown-that-re-enters-through-the-adapter-reports-once
  (testing "an adapter whose error deposit closes the seam cannot recurse"
    (let [{:keys [trace futures seam]}
          (scripted
            (fn [trace seam-box]
              {:message! (fn [m] (swap! trace conj [:message! m]))
               ;; ws/deposit! invokes the socket's close function when a
               ;; deposit itself fails; model that re-entry directly.
               :error! (fn []
                         (swap! trace conj [:error!])
                         ((:close! @seam-box) 1006 "teardown"))
               :closed! (fn [code reason]
                          (swap! trace conj [:closed! code reason]))}))]
      ((:send! seam) "one")
      (.completeExceptionally ^CompletableFuture (first @futures)
                              (ex-info "gone" {}))
      (is (= [[:send-text "one"]
              [:error!]
              [:abort]
              [:closed! 1006 "dao.stream/send-failed"]]
             @trace)
          "the re-entrant close finds the connection already failed, so it
           issues no sendClose and triggers no second report"))))


(deftest before-open-send-answers-full
  (testing "with no socket installed the seam answers false, which is full"
    (let [connection (atom {:socket nil :future nil :close-request nil
                            :pending nil :failed? false})
          trace (atom [])
          seam (jvm/client-socket
                 connection
                 {:message! (fn [m] (swap! trace conj [:message! m]))
                  :error! (fn [] (swap! trace conj [:error!]))
                  :closed! (fn [c r] (swap! trace conj [:closed! c r]))})]
      (is (false? ((:send! seam) "too early")))
      (is (nil? ((:close! seam) 1000 "bye"))
          "a close before open is retained as a close-request")
      (is (= [1000 "bye"] (:close-request @connection)))
      (is (= [] @trace) "nothing is deposited"))))


;; =============================================================================
;; Dual-subprotocol negotiation
;; =============================================================================


(deftest offered-subprotocols-split-and-trim
  (is (= ["dao.stream.cbor" "dao.stream.transit-json"]
         (jvm/offered-subprotocols
           {:headers {"sec-websocket-protocol"
                      "dao.stream.cbor, dao.stream.transit-json"}})))
  (is (= [] (jvm/offered-subprotocols {})))
  (is (= [] (jvm/offered-subprotocols
              {:headers {"sec-websocket-protocol" " , "}}))))


(deftest negotiate-picks-endpoint-order-deterministically-or-nothing
  (let [transit {:ws/subprotocol "dao.stream.transit-json"}
        cbor {:ws/subprotocol "dao.stream.cbor"}]
    ;; The endpoint's table order wins regardless of the client's offer order.
    (is (= transit (jvm/negotiate [transit cbor]
                                  ["dao.stream.cbor" "dao.stream.transit-json"])))
    (is (= cbor (jvm/negotiate [cbor transit]
                               ["dao.stream.transit-json" "dao.stream.cbor"])))
    ;; No shared subprotocol is a failed handshake, never a downgrade.
    (is (nil? (jvm/negotiate [transit] ["dao.stream.cbor"])))
    (is (nil? (jvm/negotiate [transit cbor] ["something.else"])))))


;; =============================================================================
;; The dual-subprotocol wire over a live http-kit listener
;; =============================================================================


(def ^:private dual-admission
  {:retention :evict-oldest :capacity 16 :value-domain :portable-values})


(def ^:private dual-handoff
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(def ^:private dual-descriptor
  {:dao.stream/type :dao.stream/ws
   :dao.stream/identity "jvm-dual-codec"
   :ws/host "127.0.0.1"
   :ws/port 1
   :ws/path "/yin/repl"})


(defn- dual-buffer
  [capacity]
  (:dao.stream/handle (ring/create! {:dao.stream/type :dao.stream/ringbuffer
                                     :dao.stream.ringbuffer/capacity capacity})))


(defn- dual-values
  [handle]
  (loop [cursor (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest))
         seen []]
    (let [next (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome next))
        (recur (:dao.stream/cursor next) (conj seen (:dao.stream/value next)))
        seen))))


(defn- eventually
  "Poll pred every 10ms until truthy or the deadline; nil on timeout."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (> deadline (System/currentTimeMillis))
            (Thread/sleep 10)
            (recur))))))


(defn- collecting-listener
  "A java.net.http WebSocket listener assembling fragmented messages into
   whole text and binary messages on two atoms."
  []
  (let [text (atom [])
        binary (atom [])
        sb (StringBuilder.)
        out (ByteArrayOutputStream.)]
    {:text text
     :binary binary
     :listener
     (reify WebSocket$Listener
       (onOpen [_ socket] (.request ^WebSocket socket 1000) nil)

       (onText
         [_ socket data last?]
         (.append sb ^CharSequence data)
         (when last?
           (swap! text conj (str sb))
           (.setLength sb 0))
         (.request ^WebSocket socket 1000)
         nil)

       (onBinary
         [_ socket data last?]
         (let [chunk (byte-array (.remaining ^ByteBuffer data))]
           (.get ^ByteBuffer data chunk)
           (.write out chunk)
           (when last?
             (swap! binary conj (.toByteArray out))
             (.reset out)))
         (.request ^WebSocket socket 1000)
         nil))}))


(defn- connect-raw
  "Connect one raw client offering exactly `subprotocol`."
  [port subprotocol listener]
  (-> (HttpClient/newHttpClient)
      (.newWebSocketBuilder)
      (.subprotocols subprotocol (make-array String 0))
      (.buildAsync (URI/create (str "ws://127.0.0.1:" port "/yin/repl"))
                   ^WebSocket$Listener (:listener listener))
      (.join)))


(defn- dual-composition
  "One two-slot endpoint over both profiles, plus the parallel vector of its
   slots' raw offer/ack handles keyed by endpoint slot index."
  []
  (let [slots (mapv (fn [_]
                      (let [offer (dual-buffer 1)
                            ack (dual-buffer 1)]
                        {:offer-handle offer
                         :ack-handle ack
                         :slot {:offer {:dao.stream/handle offer
                                        :dao.stream/surface #{:writer}}
                                :offer-admission dual-handoff
                                :ack {:dao.stream/handle ack
                                      :dao.stream/surface #{:writer}}
                                :ack-admission dual-handoff
                                :ack-cursor (:dao.stream/cursor
                                              (stream/cursor ack stream/anchor-newest))}}))
                    (range 2))]
    [(ws/make-endpoint
       {:served {"/yin/repl" dual-descriptor}
        :codecs [transit/profile cbor/profile]
        :control {:dao.stream/handle (dual-buffer 16)
                  :dao.stream/surface #{:writer}}
        :control-admission dual-admission
        :slots (mapv :slot slots)
        :expiry-ms nil})
     slots]))


(defn- accept-and-ack-slot!
  "Synchronously drive one handoff: take the pending slot's offer, ack it
   with a fresh traffic medium, and step the endpoint so the accept frame
   is sent.  Returns the traffic handle."
  [endpoint slots]
  (let [index (some (fn [[i slot]] (when (= :pending (:status slot)) i))
                    (map-indexed vector (:slots (ws/endpoint-state endpoint))))
        entry (nth slots index)
        offer-event (first (dual-values (:offer-handle entry)))
        traffic (dual-buffer 16)]
    (stream/append! (:ack-handle entry)
                    {:ws/attachment (:ws/attachment offer-event)
                     :ws/command :ws/accept
                     :ws/deposit {:dao.stream/handle traffic
                                  :dao.stream/surface #{:writer}}
                     :ws/admission dual-admission})
    (ws/endpoint-step endpoint (System/currentTimeMillis))
    traffic))


(deftest transit-and-cbor-sessions-share-one-live-jvm-listener
  ;; Dual-client compatibility on the JVM host edge: one http-kit listener
  ;; negotiates both subprotocols, both sessions complete their handoff over
  ;; typed frames (text for Transit, binary for CBOR), and each profile's
  ;; value domain survives its own wire.
  (let [[endpoint slots] (dual-composition)
        deposits (atom [])
        listener (jvm/listen!
                   {:bind-host "127.0.0.1" :bind-port 0
                    :accept! (fn [path socket now]
                               (ws/accept-connection! endpoint path socket now))
                    :deposit! (fn [kind detail] (swap! deposits conj [kind detail]))
                    :codecs [transit/profile cbor/profile]})
        port (try
               (let [bound (eventually #(org.httpkit.server/server-port
                                          (:ws.jvm/server listener)) 5000)]
                 (is (some? bound) "listener never bound")
                 bound)
               (catch Throwable _
                 (jvm/stop-listening! listener (fn []))
                 (throw (ex-info "listener setup failed" {}))))]
    (try
      (let [transit-client (collecting-listener)
            cbor-client (collecting-listener)
            transit-socket (connect-raw port "dao.stream.transit-json" transit-client)
            transit-traffic (accept-and-ack-slot! endpoint slots)]
        (is (= {:ws/frame :ws/accept}
               (transit/decode (eventually #(first @(:text transit-client)) 5000))))
        (.join (.sendText ^WebSocket transit-socket
                          (transit/encode {:ws/frame :ws/value :ws/value [:text :frame]})
                          true))
        (is (= [[:text :frame]]
               (mapv :ws/value (eventually #(seq (dual-values transit-traffic)) 5000))))
        (let [cbor-socket (connect-raw port "dao.stream.cbor" cbor-client)
              cbor-traffic (accept-and-ack-slot! endpoint slots)
              value (with-meta [:binary :frame] {:line 9})]
          (is (= {:ws/frame :ws/accept}
                 (cbor/decode (eventually #(first @(:binary cbor-client)) 5000))))
          (.join (.sendBinary ^WebSocket cbor-socket
                              (ByteBuffer/wrap ^bytes (cbor/encode
                                                        {:ws/frame :ws/value :ws/value value}))
                              true))
          (let [deposited (mapv :ws/value
                                (eventually #(seq (dual-values cbor-traffic)) 5000))]
            (is (= [value] deposited))
            (is (= {:line 9} (meta (first deposited)))
                "the CBOR session's metadata survived its own binary wire; =
                 alone cannot see it, a downgrade would strip it"))
          (.join (.sendClose ^WebSocket cbor-socket 1000 "done")))
        (.join (.sendClose ^WebSocket transit-socket 1000 "done")))
      (finally
        (jvm/stop-listening! listener (fn []))))))


(deftest a-cbor-client-offering-to-a-transit-only-jvm-listener-fails-its-upgrade
  ;; Mixed-version handshake failure: no silent downgrade to the text wire.
  (let [[endpoint _slots] (dual-composition)
        deposits (atom [])
        listener (jvm/listen!
                   {:bind-host "127.0.0.1" :bind-port 0
                    :accept! (fn [path socket now]
                               (ws/accept-connection! endpoint path socket now))
                    :deposit! (fn [kind detail] (swap! deposits conj [kind detail]))})
        port (eventually #(org.httpkit.server/server-port
                            (:ws.jvm/server listener)) 5000)]
    (is (some? port) "listener never bound")
    (try
      (let [future (-> (HttpClient/newHttpClient)
                       (.newWebSocketBuilder)
                       (.subprotocols "dao.stream.cbor" (make-array String 0))
                       (.buildAsync (URI/create (str "ws://127.0.0.1:" port "/yin/repl"))
                                    ^WebSocket$Listener (:listener (collecting-listener))))]
        (is (thrown? java.util.concurrent.CompletionException (.join future))
            "the handshake fails rather than completing and downgrading")
        (is (some #(= :upgrade-failed (first %)) @deposits)
            "the refusal is reported through the endpoint deposit")
        (is (= :free (:status (first (:slots (ws/endpoint-state endpoint)))))
            "no handoff slot was consumed"))
      (finally
        (jvm/stop-listening! listener (fn []))))))
