(ns yin.repl.serve-connect-wire-test
  "The demo REPL flow over a real JVM WebSocket: `serve!` and `connect`
   composed together, over `dao.stream.remote`'s requests/answers
   reflection pair, with no fake transport anywhere in the path."
  (:require [clojure.test :refer [deftest is]]
            [yin.repl.adapter :as adapter]
            [yin.repl.connect :as connect]
            [yin.repl.host.jvm :as host]
            [yin.repl.serve :as serve])
  (:import [java.net ServerSocket]))


(defn- free-port
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))


(defn- await-pred
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 5)
            (recur))))))


(defn- start-server!
  [port]
  (let [state (atom (serve/serve! {:bind-port port :host (host/websocket)}))
        running (atom true)
        ticker (doto (Thread.
                       (fn []
                         (while @running
                           (swap! state serve/step (System/currentTimeMillis))
                           (Thread/sleep 5)))
                       "serve-connect-wire-test")
                 (.setDaemon true)
                 (.start))]
    {:state state
     :stop! (fn [] (reset! running false) (.join ticker 1000))}))


(defn- warm-up!
  "Drive the connection's dial and poll its RPC client for a while before
   anything is submitted: the answers reflection's `:dao.stream/newest`
   mint (docs/design/dao.stream.remote.md section 5's Linda mint) and the
   underlying WebSocket handshake both settle asynchronously, and only
   `poll!` retries the mint.  Submitting before it settles is exactly the
   ordering the section-5 toy warns against: a mint that resolves after a
   request already answered would orphan that answer."
  [connection adapter-state]
  (await-pred
    (fn []
      (swap! connection connect/step! (System/currentTimeMillis))
      (let [polled (adapter/poll-responses @adapter-state 8)]
        (reset! adapter-state (:yin.repl.adapter/state polled))
        (not= :dao.stream/newest
              (:cursor (:yin.repl.adapter/rpc @adapter-state)))))))


(defn- submit-with-retry!
  "Retry `adapter/submit-input` until the request lands: the requests
   reflection's writer may answer `:dao.stream/full` for a moment right
   after attach, while the underlying WebSocket handshake is still
   settling.  `rpc/request!` retains the same allocated envelope across
   calls while `:unsent`, ignoring `input` on a retry, exactly as
   `yin.repl.driver`'s own `retry-unsent` does."
  [connection adapter-state input]
  (await-pred
    (fn []
      (swap! connection connect/step! (System/currentTimeMillis))
      (let [result (adapter/submit-input @adapter-state input)]
        (reset! adapter-state (:yin.repl.adapter/state result))
        (not= :yin.repl.adapter/pending-request
              (:yin.repl.adapter/outcome result))))))


(defn- poll-until-event!
  [connection adapter-state]
  (let [events (atom [])]
    (await-pred
      (fn []
        (swap! connection connect/step! (System/currentTimeMillis))
        (let [polled (adapter/poll-responses @adapter-state 8)]
          (reset! adapter-state (:yin.repl.adapter/state polled))
          (swap! events into (:yin.repl.adapter/events polled))
          (seq @events))))
    @events))


(deftest eval-round-trips-then-disconnect-and-reattach-over-a-real-socket
  (let [port (free-port)
        {:keys [state stop!]} (start-server! port)]
    (try
      (is (await-pred #(= :running (:status @state))))
      (let [url (str "daostream:ws://127.0.0.1:" port "/repl")
            opened (connect/open {:url url :host (host/websocket)
                                  :now (System/currentTimeMillis)})]
        (is (= :yin.repl.connect/attached (get opened connect/outcome-key)))
        (let [connection (atom (get opened connect/connection-key))
              adapter-state (atom (adapter/state (get opened connect/client-key)))]
          (is (= :established (:status @connection)))

          (is (warm-up! connection adapter-state))
          (is (submit-with-retry! connection adapter-state "(+ 1 2)"))
          (let [events (poll-until-event! connection adapter-state)]
            (is (= :yin.repl.adapter/response
                   (:yin.repl.adapter/event (first events))))
            (is (= "3" (:yin.repl.adapter/value (first events)))))

          (swap! connection connect/close!)
          (is (await-pred
                #(do
                   (swap! connection connect/step! (System/currentTimeMillis))
                   (let [polled (adapter/poll-responses @adapter-state 8)]
                     (reset! adapter-state (:yin.repl.adapter/state polled))
                     (= :dao.stream.rpc/detached
                        (:terminal (:yin.repl.adapter/rpc @adapter-state)))))))

          (let [reattached (connect/reattach
                             @connection (:yin.repl.adapter/rpc @adapter-state)
                             (System/currentTimeMillis))]
            (is (= :yin.repl.connect/reattached
                   (get reattached connect/outcome-key)))
            (reset! connection (get reattached connect/connection-key))
            (swap! adapter-state assoc :yin.repl.adapter/rpc
                   (get reattached connect/client-key)))
          (is (= :established (:status @connection)))

          (is (submit-with-retry! connection adapter-state "(+ 40 2)"))
          (let [events (poll-until-event! connection adapter-state)]
            (is (= "42" (:yin.repl.adapter/value (first events)))))))
      (finally (stop!)))))
