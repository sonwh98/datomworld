(ns yin.repl.embed-test
  "The embedding composition behind the Flutter REPL widget, with the host
   listener injected as in `yin.repl.serve-test`.  Nothing here binds a port."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream.apply :as apply]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            [yin.repl.embed :as embed]))


(defn- host
  []
  {:bind! (fn [config]
            ((:deposit! config) :bind-succeeded
                                {:host (:bind-host config) :port (:bind-port config)})
            {:listener :injected})
   :unbind! (fn [_resources deposit!]
              (deposit! :stopped {:reason :requested})
              nil)})


(defn- socket
  []
  (let [sent (atom [])]
    {:sent sent
     :socket {:send! (fn [text] (swap! sent conj text) nil)
              :close! (fn [_code _reason] nil)}}))


(defn- last-value
  [s]
  (->> @(:sent s)
       (map transit/decode)
       (filter #(= :ws/value (:ws/frame %)))
       last
       :ws/value))


(defn- request!
  [handle id source]
  (ws/receive! handle (transit/encode
                        {:ws/frame :ws/value
                         :ws/value (apply/request id :op/eval [source])})))


(defn- start
  []
  (embed/start {:port 7777
                :advertised-host "192.168.1.20"
                :primitives {'answer (fn [] 42)}
                :host (host)}))


(deftest a-wildcard-bind-with-an-advertised-host-binds
  (let [endpoint (start)
        [endpoint lines] (embed/step endpoint 1)
        status (embed/status endpoint)]
    (is (= :running (:status status)) "the first step observed :bind-succeeded")
    (is (some #(str/starts-with? % "Serving daostream:ws://192.168.1.20:7777/repl") lines))
    (is (= "daostream:ws://192.168.1.20:7777/repl" (:url status)))
    (is (zero? (:clients status)))
    (is (= "listening" (embed/status-text status)))))


(deftest a-host-primitive-is-served-and-survives-reset
  (let [[endpoint _] (embed/step (start) 1)
        s (socket)
        accepted (ws/accept-connection! (:ws-endpoint endpoint) (:path endpoint)
                                        (:socket s) 2)
        handle (:ws/handle accepted)
        [endpoint _] (embed/step endpoint 2)
        [endpoint _] (embed/step endpoint 3)]
    (is (= 1 (:clients (embed/status endpoint))) "the session was adopted")
    (is (= "client connected" (embed/status-text (embed/status endpoint))))
    (request! handle 0 "(answer)")
    (let [[endpoint _] (embed/step endpoint 4)]
      (is (= "42" (apply/response-ok (last-value s))))
      (testing "(reset) through the same session keeps the host primitive"
        (request! handle 1 "(reset)")
        (let [[endpoint _] (embed/step endpoint 5)]
          (request! handle 2 "(answer)")
          (embed/step endpoint 6)
          (is (= 2 (apply/response-id (last-value s))))
          (is (= "42" (apply/response-ok (last-value s)))))))))


(deftest stop-then-stepping-reaches-stopped
  (let [[endpoint _] (embed/step (start) 1)
        endpoint (embed/stop endpoint)]
    (is (= "stopping" (embed/status-text (embed/status endpoint))))
    (is (false? (embed/stopped? endpoint)))
    (let [endpoint (loop [endpoint endpoint
                          now 2]
                     (if (or (embed/stopped? endpoint) (< 10 now))
                       endpoint
                       (recur (first (embed/step endpoint now)) (inc now))))]
      (is (true? (embed/stopped? endpoint)))
      (is (= "stopped" (embed/status-text (embed/status endpoint)))))))
