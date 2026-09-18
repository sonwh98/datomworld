(ns dao.stream.serving-test
  (:require [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.serving :as serving]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]))


(def admission
  {:retention :evict-oldest :capacity 16 :value-domain :portable-values})


(def handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(def descriptor
  {:dao.stream/type :dao.stream/ws
   :dao.stream/identity "yin-repl-service"
   :ws/host "127.0.0.1"
   :ws/port 9182
   :ws/path "/yin/repl"})


(defn- buffer
  ([] (buffer 16))
  ([capacity]
   (:dao.stream/handle
     (ring/create! {:dao.stream/type ring/transport-type
                    ring/capacity-key capacity}))))


(defn- cursor
  [handle anchor]
  (:dao.stream/cursor (stream/cursor handle anchor)))


(defn- endpoint-fixture
  [served]
  (let [offer (buffer 1)
        ack (buffer 1)
        control (buffer)
        endpoint (ws/make-endpoint
                   {:served {"/yin/repl" descriptor}
                    :control {:dao.stream/handle control :dao.stream/surface #{:writer}}
                    :control-admission admission
                    :slots [{:offer {:dao.stream/handle offer :dao.stream/surface #{:writer}}
                             :offer-admission handoff-admission
                             :ack {:dao.stream/handle ack :dao.stream/surface #{:writer}}
                             :ack-admission handoff-admission
                             :ack-cursor (cursor ack stream/anchor-newest)}]
                    :expiry-ms nil})]
    {:endpoint endpoint
     :control control
     :offer offer
     :ack ack
     :slots [{:offer-reader offer
              :offer-cursor (cursor offer stream/anchor-newest)
              :ack-writer {:dao.stream/handle ack :dao.stream/surface #{:writer}}}]
     :served served}))


(defn- serving-fixture
  [extra]
  (let [service (buffer)
        {:keys [endpoint control slots served] :as fixture}
        (endpoint-fixture {"/yin/repl" {:descriptor descriptor :stream service}})
        traffic (buffer)
        started (atom [])
        stopped (atom [])
        ended (atom [])
        composition
        (serving/make-serving
          (merge {:endpoint endpoint
                  :served served
                  :control-reader control
                  :control-cursor (cursor control stream/anchor-newest)
                  :slots slots
                  :make-traffic (fn [_]
                                  {:traffic {:dao.stream/handle traffic
                                             :dao.stream/surface #{:writer}}
                                   :admission admission
                                   :reader traffic
                                   :cursor (cursor traffic stream/anchor-newest)})
                  :forward-options {:batch-budget 8 :gap-policy :terminate}
                  :start-endpoint! (fn [ep] (swap! started conj ep) {:host :started})
                  :stop-endpoint! (fn [ep] (swap! stopped conj ep) {:host :stopped})
                  :close-ended! (fn [handle]
                                  (swap! ended conj handle)
                                  {:dao.stream/outcome :dao.stream/ok})}
                 extra))]
    (assoc fixture
           :service service :traffic traffic :composition composition
           :started started :stopped stopped :ended ended)))


(deftest lifecycle-is-explicit-and-never-self-schedules
  (let [{:keys [composition started stopped]} (serving-fixture {})]
    (is (= :new (:lifecycle (serving/state composition))))
    (serving/step! composition 10)
    (is (empty? @started))
    (is (= {:host :started} (serving/start! composition)))
    (is (= :running (:lifecycle (serving/state composition))))
    (is (= 1 (count @started)))
    (is (= {:host :stopped} (serving/stop! composition)))
    (is (= :stopped (:lifecycle (serving/state composition))))
    (is (= 1 (count @stopped)))))


(deftest driver-acknowledges-offers-before-forwarding-per-attachment
  (let [{:keys [endpoint service composition ended]} (serving-fixture {})
        sent (atom [])
        accepted (ws/accept-connection! endpoint "/yin/repl"
                                        {:send! #(do (swap! sent conj %) nil)
                                         :close! (fn [& _] nil)} 10)
        attachment (:ws/attachment accepted)]
    (stream/append! service :yin/ready)
    (serving/start! composition)
    ;; First tick advances the transport then consumes the offer and writes its
    ;; acknowledgement. The socket cannot be accepted until the next tick.
    (serving/step! composition 10)
    (is (empty? @sent))
    (is (contains? (:sessions (serving/state composition)) attachment))
    ;; The next tick calls endpoint-step before the per-connection forwarder.
    (serving/step! composition 11)
    (is (= [{:ws/frame :ws/accept}
            {:ws/frame :ws/value :ws/value :yin/ready}]
           (mapv transit/decode @sent)))
    (let [session (get-in (serving/state composition) [:sessions attachment])]
      (is (= "yin-repl-service" (:identity session)))
      (is (= "/yin/repl" (:path session))))
    (stream/close! service)
    (serving/step! composition 12)
    (is (= 1 (count @ended)))
    (is (empty? (:sessions (serving/state composition))))))


(deftest terminal-attachment-traffic-retires-only-its-own-session
  (let [{:keys [endpoint composition traffic]} (serving-fixture {})
        accepted (ws/accept-connection! endpoint "/yin/repl"
                                        {:send! (fn [_]) :close! (fn [& _] nil)} 10)
        attachment (:ws/attachment accepted)]
    (serving/start! composition)
    (serving/step! composition 10)
    (serving/step! composition 11)
    (let [socket-handle (get-in (serving/state composition)
                                [:sessions attachment :socket-handle])]
      (ws/closed! socket-handle 1000 "peer-disconnected"))
    (serving/step! composition 12)
    (is (empty? (:sessions (serving/state composition))))
    ;; The traffic stream survives a detached connection; only its session is
    ;; retired by this composition.
    (is (= :dao.stream/ok
           (:dao.stream/outcome (stream/append! traffic {:host/event :still-live}))))))


(deftest rejected-acknowledgement-assembly-closes-the-offered-attachment
  (let [{:keys [endpoint composition ack]} (serving-fixture
                                             {:make-traffic (fn [_] :malformed)})
        accepted (ws/accept-connection! endpoint "/yin/repl"
                                        {:send! (fn [_]) :close! (fn [& _] nil)} 10)
        socket-handle (:ws/handle accepted)]
    (serving/start! composition)
    (serving/step! composition 10)
    (is (empty? (:sessions (serving/state composition))))
    (is (= :dao.stream/closed
           (:dao.stream/outcome (stream/append! socket-handle :late))))
    ;; No malformed acknowledgement reaches the endpoint slot.
    (is (= :dao.stream/blocked
           (:dao.stream/outcome (stream/next ack (cursor ack stream/anchor-newest)))))))
