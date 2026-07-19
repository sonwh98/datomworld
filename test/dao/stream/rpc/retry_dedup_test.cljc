(ns dao.stream.rpc.retry-dedup-test
  "Tests for dao.stream.rpc.retry/call-with-retry! and
   dao.stream.rpc.dedup/wrap-dedup -- opt-in composition on top of the
   untouched dao.stream.rpc.{client,server} primitives, exercised over
   dao.stream.rpc.udp/open-pair! (the one lossy transport this exists for)
   and, for the transport-agnostic dedup case, the in-memory DuplexStream
   from dao.stream.rpc-test.

   All tests are #?(:clj ...)-gated: dao.stream.rpc.udp and the retry/dedup
   namespaces are :clj-only today."
  (:require [clojure.test :refer [deftest is]]
            [dao.stream :as ds]
            [dao.stream.apply :as dao-apply]
            [dao.stream.rpc.client :as rpc-client]
            [dao.stream.rpc.server :as rpc-server]
            [dao.stream.rpc.retry :as retry]
            [dao.stream.rpc.dedup :as dedup]
            [dao.stream.rpc.udp :as rpc-udp]
            [dao.stream.rpc-test :as rpc-test]))


;; =============================================================================
;; UDP roundtrip (no loss, no retry needed)
;; =============================================================================

(deftest udp-rpc-roundtrip-test
  #?(:clj (let [{:keys [server-stream client-stream]} (rpc-udp/open-pair!)
                handlers {:math/add +}
                stop (atom false)
                _ (rpc-server/serve-connection! handlers server-stream stop)
                client (rpc-client/init-client client-stream)]
            (try (is (= 5 (rpc-client/call! client :math/add [2 3])))
                 (finally (reset! stop true)
                          (rpc-client/close! client)
                          (ds/close! server-stream))))))


;; =============================================================================
;; Retry recovers a lost response
;; =============================================================================

(deftest udp-rpc-retry-on-lost-response-test
  #?(:clj (let [{:keys [server-stream client-stream]} (rpc-udp/open-pair!)
                handlers {:math/add +}
                stop (atom false)
                original-put-response! dao-apply/put-response!
                call-count (atom 0)
                _ (rpc-server/serve-connection! handlers server-stream stop)
                client (rpc-client/init-client client-stream
                                               {:request-timeout-ms 500})]
            (try (with-redefs [dao-apply/put-response!
                               (fn [stream response]
                                 (if (= 1 (swap! call-count inc))
                                   {:result :ok} ; drop only the first
                                   ;; response
                                   (original-put-response! stream response)))]
                   (is (= 5
                          (retry/call-with-retry! client
                                                  :math/add
                                                  [2 3]
                                                  {:tries 2}))))
                 (finally (reset! stop true)
                          (rpc-client/close! client)
                          (ds/close! server-stream))))))


;; =============================================================================
;; Retry recovers a lost request -- wires retry + dedup together over UDP
;; =============================================================================

(deftest udp-rpc-retry-on-lost-request-test
  #?(:clj
     (let [{:keys [server-stream client-stream]} (rpc-udp/open-pair!)
           handler-calls (atom 0)
           handlers {:math/add (fn [a b] (swap! handler-calls inc) (+ a b))}
           stop (atom false)
           call-count (atom 0)
           _ (rpc-server/serve-connection! handlers
                                           server-stream
                                           stop
                                           {:wrap-dispatch dedup/wrap-dedup})
           client (rpc-client/init-client client-stream
                                          {:request-timeout-ms 500})]
       (try
         ;; dao-apply/put-request!'s real 4-arity clause self-delegates
         ;; to its 2-arity clause through the var, not a direct call -- a
         ;; with-redefs replacement that just forwards to a captured
         ;; "original" value breaks that internal delegation with an
         ;; arity mismatch. Bypassing put-request! entirely (build the
         ;; request, ds/append! it directly) sidesteps the issue.
         (with-redefs [dao-apply/put-request!
                       (fn [stream req-id op args]
                         (if (= 1 (swap! call-count inc))
                           {:result :ok} ; drop only the first request
                           (ds/append!
                             stream
                             (dao-apply/request req-id op args))))]
           (is (= 5
                  (retry/call-with-retry! client :math/add [2 3] {:tries 2})))
           (is
             (= 1 @handler-calls)
             "handler executes exactly once: the retry is the first request the server ever sees"))
         (finally (reset! stop true)
                  (rpc-client/close! client)
                  (ds/close! server-stream))))))


;; =============================================================================
;; Dedup: handler executes exactly once for a resent req-id
;; =============================================================================

(deftest server-dedup-test
  #?(:clj
     (let [c2s (ds/open! {:type :ringbuffer, :capacity 1024})
           s2c (ds/open! {:type :ringbuffer, :capacity 1024})
           handler-calls (atom 0)
           handlers {:math/add (fn [a b] (swap! handler-calls inc) (+ a b))}
           stop (atom false)
           _ (rpc-server/serve-connection! handlers
                                           (rpc-test/->DuplexStream c2s s2c)
                                           stop
                                           {:wrap-dispatch dedup/wrap-dedup})
           client (rpc-client/init-client (rpc-test/->DuplexStream s2c c2s))]
       (try
         ;; First call succeeds normally.
         (is (= 5 (rpc-client/call! client :math/add [2 3])))
         (is (= 1 @handler-calls))
         ;; Simulate a retry: resend the same req-id manually.
         (dao-apply/put-request! (:stream client) 1 :math/add [2 3])
         ;; Give the single-threaded server loop time to process the
         ;; duplicate.
         (Thread/sleep 200)
         (is (= 1 @handler-calls) "handler was not called again")
         (finally (reset! stop true) (rpc-client/close! client))))))


;; =============================================================================
;; Assembled surface: serve! + connect! + rpc-udp/call! (retry and dedup
;; wired in by default, no primitive plumbing at the call site)
;; =============================================================================

(deftest udp-connect-serve-roundtrip-with-loss-test
  #?(:clj
     (let [[server-port client-port] (rpc-udp/distinct-ports)
           handler-calls (atom 0)
           handlers {:math/add (fn [a b] (swap! handler-calls inc) (+ a b))}
           server (rpc-udp/serve! handlers
                                  {:listen-port server-port,
                                   :peer-host "127.0.0.1",
                                   :peer-port client-port})
           client (rpc-udp/connect! {:peer-host "127.0.0.1",
                                     :peer-port server-port,
                                     :listen-port client-port})
           original-put-response! dao-apply/put-response!
           drop-count (atom 0)]
       (try
         ;; No loss: plain roundtrip through the assembled surface.
         (is (= 5 (rpc-udp/call! client :math/add [2 3])))
         ;; Drop the first response of the next call: connect!'s default
         ;; :tries recovers it, and serve!'s default dedup keeps the
         ;; handler at exactly one extra execution.
         (with-redefs [dao-apply/put-response!
                       (fn [stream response]
                         (if (= 1 (swap! drop-count inc))
                           {:result :ok} ; drop only the first
                           ;; response
                           (original-put-response! stream response)))]
           (is (= 12 (rpc-udp/call! client :math/add [5 7]))))
         (is
           (= 2 @handler-calls)
           "one execution per unique request; the dropped-response retry hit the dedup cache, not the handler")
         (finally (rpc-client/close! client) (rpc-udp/stop! server))))))


;; =============================================================================
;; No-retry default matches today's call! behavior
;; =============================================================================

(deftest no-retry-times-out-test
  #?(:clj (let [{:keys [server-stream client-stream]} (rpc-udp/open-pair!)
                handler-gate (promise)
                handlers {:math/add (fn [_ _] (deref handler-gate) 0)}
                stop (atom false)
                _ (rpc-server/serve-connection! handlers server-stream stop)
                client (rpc-client/init-client client-stream
                                               {:request-timeout-ms 200})]
            (try (is (thrown-with-msg?
                       Exception
                       #"Request timeout"
                       (rpc-client/call! client :math/add [2 3])))
                 (finally (deliver handler-gate nil) ; unblock the server
                          ;; handler thread
                          (reset! stop true)
                          (rpc-client/close! client)
                          (ds/close! server-stream))))))
