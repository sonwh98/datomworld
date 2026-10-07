(ns yin.repl.embed-test
  "The embedding composition behind the Flutter REPL widget, with the host
   listener injected, or the in-process loopback net of
   yin.repl.net-fixture when a client attaches.  Nothing here binds a port."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.rpc :as rpc]
            [yin.repl.embed :as embed]
            [yin.repl.net-fixture :as fixture]))


(defn- host
  []
  {:bind! (fn [config]
            ((:deposit! config) :bind-succeeded
                                {:host (:bind-host config) :port (:bind-port config)})
            {:listener :injected})
   :unbind! (fn [_resources deposit!]
              (deposit! :stopped {:reason :requested})
              nil)})


(defn- last-answer
  "The last answer appended to the endpoint's shared answers medium."
  [endpoint]
  (loop [cursor (:dao.stream/cursor (stream/cursor (:answers endpoint)
                                                   stream/anchor-oldest))
         last-value nil]
    (let [r (stream/next (:answers endpoint) cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (:dao.stream/value r))
        last-value))))


(defn- request!
  "Append one eval request directly onto the endpoint's requests medium,
   as the mirror's own `:dao.stream/append!` answer would once a
   reflection's write reaches it."
  [endpoint id source]
  (stream/append! (:requests endpoint) (rpc/request-value id :op/eval [source])))


(defn- start
  ([] (start (host)))
  ([h]
   (embed/start {:port 7777
                 :advertised-host "192.168.1.20"
                 :primitives {'answer (fn [] 42)}
                 :host h})))


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
  (let [h (fixture/host)
        [endpoint _] (embed/step (start (:adapter h)) 1)
        c (fixture/open h (:url (embed/status endpoint)) 2)
        _ (fixture/pump! h)
        [endpoint _] (embed/step endpoint 2)
        _ (fixture/pump! h)
        [endpoint _] (embed/step endpoint 3)]
    (is (some? c) "a client attached")
    (is (= 1 (:clients (embed/status endpoint))) "the session was adopted")
    (is (= "client connected" (embed/status-text (embed/status endpoint))))
    (request! endpoint 0 "(answer)")
    (let [[endpoint _] (embed/step endpoint 4)]
      (is (= "42" (rpc/answer-ok (last-answer endpoint))))
      (testing "(reset) through the same session keeps the host primitive"
        (request! endpoint 1 "(reset)")
        (let [[endpoint _] (embed/step endpoint 5)]
          (request! endpoint 2 "(answer)")
          (let [[endpoint _] (embed/step endpoint 6)]
            (is (= 2 (rpc/answer-id (last-answer endpoint))))
            (is (= "42" (rpc/answer-ok (last-answer endpoint))))))))))


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
