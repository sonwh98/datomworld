(ns dao.stream.rpc-test
  "Tests for the generic dao.stream.rpc RPC layer -- jing-agnostic, exercised
   directly with arbitrary caller-supplied handlers maps."
  (:require [clojure.test :as t :refer [deftest is]]
            [dao.stream.apply :as dao-apply]
            [dao.stream.rpc.client :as rpc-client]
            [dao.stream.rpc.server :as rpc-server]))


(defn- random-port
  []
  (+ 10000 (rand-int 50000)))


(defn- with-rpc-server
  [handlers f]
  #?(:clj (let [port (random-port)
                server (rpc-server/start! handlers port)]
            (Thread/sleep 100)
            (try (f port) (finally (rpc-server/stop! server))))))


(deftest custom-op-end-to-end-test
  #?(:clj
     (with-rpc-server
       {:demo/add +, :demo/echo identity}
       (fn [port]
         (let [client (rpc-client/connect! (str "ws://localhost:" port))]
           (try
             (is
               (= 5 (rpc-client/call! client :demo/add [2 3]))
               "call! should invoke the registered handler and return its result")
             (is (= "hi" (rpc-client/call! client :demo/echo ["hi"])))
             (finally (rpc-client/close! client))))))))


(deftest unknown-op-error-test
  #?(:clj (with-rpc-server
            {:demo/add +}
            (fn [port]
              (let [client (rpc-client/connect! (str "ws://localhost:" port))]
                (try (is (thrown-with-msg?
                           Exception
                           #"No dao.stream.apply handler for op"
                           (rpc-client/call! client :bogus/op []))
                         "call! should surface the server's unknown-op error")
                     (finally (rpc-client/close! client))))))))


(deftest concurrent-calls-test
  #?(:clj
     (with-rpc-server
       {:demo/add +}
       (fn [port]
         (let [client (rpc-client/connect! (str "ws://localhost:" port))]
           (try
             (let [n 20
                   results
                   (->> (range n)
                        (pmap (fn [i]
                                (rpc-client/call! client :demo/add [i i])))
                        doall)]
               (is
                 (= (map #(* 2 %) (range n)) results)
                 "concurrent calls on one client should each get their own matching response"))
             (finally (rpc-client/close! client))))))))


(deftest close-is-idempotent-test
  #?(:clj (with-rpc-server
            {:demo/add +}
            (fn [port]
              (let [client (rpc-client/connect! (str "ws://localhost:" port))]
                (rpc-client/close! client)
                (rpc-client/close! client)
                (is (true? true) "multiple close! calls should not throw"))))))


(deftest call-after-close-throws-test
  #?(:clj (with-rpc-server
            {:demo/add +}
            (fn [port]
              (let [client (rpc-client/connect! (str "ws://localhost:" port))]
                (rpc-client/close! client)
                (is (thrown? Exception
                      (rpc-client/call! client :demo/add [1 2]))
                    "call! on a closed client should throw"))))))


(deftest start-preserves-caller-supplied-connect-and-disconnect-callbacks-test
  #?(:clj
     (let [port (random-port)
           connect-count (atom 0)
           disconnect-count (atom 0)
           server (rpc-server/start!
                    {:demo/add +}
                    port
                    {:on-connect (fn [_stream] (swap! connect-count inc)),
                     :on-disconnect (fn [_stream]
                                      (swap! disconnect-count inc))})]
       (Thread/sleep 100)
       (try
         (let [client (rpc-client/connect! (str "ws://localhost:" port))]
           (is (= 5 (rpc-client/call! client :demo/add [2 3])))
           (is
             (= 1 @connect-count)
             "start! should still invoke the caller-supplied :on-connect alongside its own connection bookkeeping, not silently discard it")
           (rpc-client/close! client)
           (Thread/sleep 100)
           (is
             (= 1 @disconnect-count)
             "start! should still invoke the caller-supplied :on-disconnect alongside its own connection bookkeeping, not silently discard it"))
         (finally (rpc-server/stop! server))))))


(deftest serve-connection-retries-after-transient-end-test
  #?(:clj
     (with-rpc-server
       {:demo/add +}
       (fn [port]
         (let [client (rpc-client/connect! (str "ws://localhost:" port)
                                           {:request-timeout-ms 500})
               calls (atom 0)
               real-next-request dao-apply/next-request]
           (with-redefs [dao-apply/next-request
                         (fn [stream cursor]
                           (if (= 1 (swap! calls inc))
                             :end
                             (real-next-request stream cursor)))]
             (try
               (is
                 (= 5 (rpc-client/call! client :demo/add [2 3]))
                 "a transient :end read from the underlying stream should not permanently stop the connection's request loop -- it should retry like :blocked does")
               (finally (rpc-client/close! client)))))))))
