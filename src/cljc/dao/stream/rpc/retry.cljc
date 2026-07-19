(ns dao.stream.rpc.retry
  "Opt-in retry wrapper for dao.stream.rpc.client/call!, for transports where
   a request or its response can be silently dropped (dao.stream.udp today).
   Pairs with dao.stream.rpc.dedup on the server so a resent request is safe
   for non-idempotent handlers.

   call! itself never sees :tries and pays nothing for this -- an
   interpreter that doesn't need it never requires this namespace.

   :clj-only: the one lossy transport this exists for (dao.stream.udp) is
   JVM-only today. When a lossy :cljs/:cljd transport exists, extending this
   is a local addition here, not a change to call!."
  (:require [dao.stream.apply :as dao-apply]
            [dao.stream.rpc.client :as rpc-client]))


#?(:clj
   (defn call-with-retry!
     "Like dao.stream.rpc.client/call!, but resends the same request (same
      req-id, so the server can dedup) up to `tries` - 1 additional times if
      the wait times out. Each attempt waits client's :request-timeout-ms
      (or dao.stream.rpc.client/default-timeout-ms).

      opts:
        :tries — total attempts including the first (default 1, i.e.
                 identical to call!'s current behavior: no retries).

      Returns the result value, blocking. Throws if every attempt times out,
      or immediately on any non-timeout error (a remote/handler error is not
      retried -- resending a definite error response would not help)."
     ([client op args] (call-with-retry! client op args {}))
     ([client op args {:keys [tries], :or {tries 1}}]
      (let [{:keys [stream response-cursor-atom request-id-atom closed-atom
                    request-timeout-ms]}
            client]
        (when @closed-atom
          (throw (ex-info "Client is closed" {:operation op})))
        (let [start-cursor @response-cursor-atom
              req-id (swap! request-id-atom inc)
              timeout-ms (or request-timeout-ms
                             rpc-client/default-timeout-ms)]
          (dao-apply/put-request! stream req-id op args)
          (loop [attempt 1]
            (let [result (try (rpc-client/wait-for-response
                                stream
                                response-cursor-atom
                                start-cursor
                                req-id
                                timeout-ms)
                              (catch clojure.lang.ExceptionInfo e
                                (if (and (< attempt tries)
                                         (= "Request timeout" (ex-message e)))
                                  ::retry
                                  (throw e))))]
              (if (= result ::retry)
                (do (dao-apply/put-request! stream req-id op args)
                    (recur (inc attempt)))
                result))))))))
