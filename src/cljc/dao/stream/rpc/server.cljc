(ns dao.stream.rpc.server
  "Transport-agnostic RPC server: exposes a caller-supplied handlers map over
   any IDaoStream via dao.stream.apply.

   For WebSocket convenience (connect!, start!, stop!), see dao.stream.rpc.ws.

   Portable across :clj, :cljs, and :cljd. Each client connection gets its own
   request loop that reads requests and sends responses over that connection's
   stream: on :clj this is a real background thread (a future) blocking briefly
   between polls; on :cljs/:cljd there is no blocking thread, so the loop
   self-reschedules via js/setTimeout / dart:async Future.delayed instead."
  (:require #?(:cljd ["dart:async" :as async])
            #?(:cljd ["dart:core" :as core])
            [dao.stream :as ds]
            [dao.stream.apply :as dao-apply]))


(def ^:private max-consecutive-errors
  "Stop retrying and close the connection after this many consecutive malformed
   requests, rather than retrying the same broken stream forever."
  20)


(defn- dispatch-and-wrap
  "Dispatch a request op through handlers and return a wire-safe response value.

   Reuses dao.stream.apply/dispatch-request for the handler lookup/apply. Unwraps
   its response envelope back down to the raw result and catches handler
   exceptions, since dispatch-request itself does not catch."
  [handlers op args]
  (try (let [request (dao-apply/request ::dispatch op args)
             response (dao-apply/dispatch-request handlers request)]
         {:ok (dao-apply/response-value response)})
       (catch #?(:clj Exception
                 :cljs :default
                 :cljd Object)
              e
         {:error (ex-message e)})))


(defn- handle-one!
  "Read a request at cursor and dispatch it through dispatch-fn, a
   (req-id, op, args) -> {:ok val} | {:error msg} function -- the same
   envelope shape dao.stream.apply/response expects as its result argument.
   The default dispatch-fn serve-connection! builds just wraps
   dispatch-and-wrap over handlers; a caller-supplied dispatch-fn (e.g.
   dao.stream.rpc.dedup/wrap-dedup around that default) can short-circuit
   without re-invoking the handler, which is what makes client-side retries
   safe for non-idempotent handlers.

   Returns {:cursor next-cursor} on success, the underlying ds/next sentinel
   (:blocked, :end, :daostream/gap) unchanged when nothing was available, or
   {:cursor next-cursor :malformed? true} when reading/decoding the request itself
   threw (the cursor is advanced defensively past the bad position so the loop
   cannot retry the same broken entry forever)."
  [stream cursor dispatch-fn]
  (try (let [req-result (dao-apply/next-request stream cursor)]
         (if (map? req-result)
           (let [request (:ok req-result)
                 req-id (dao-apply/request-id request)
                 op (dao-apply/request-op request)
                 args (dao-apply/request-args request)
                 result (dispatch-fn req-id op args)
                 response (dao-apply/response req-id result)]
             (dao-apply/put-response! stream response)
             {:cursor (:cursor req-result)})
           req-result))
       (catch #?(:clj Exception
                 :cljs :default
                 :cljd Object)
              _
         {:cursor (update cursor :position (fnil inc 0)), :malformed? true})))


(defn serve-connection!
  "Serve requests from a single client connection until the connection closes or
   stop-atom is set.

   opts:
     :wrap-dispatch — a (fn [dispatch-fn] dispatch-fn') that wraps the
                      default dispatch before serving, e.g.
                      dao.stream.rpc.dedup/wrap-dedup for a lossy transport
                      where the same req-id may be resent. Omitted by
                      default: the reliable-transport path never allocates a
                      cache (every request still goes through one
                      dispatch-fn closure call either way).

   Example transport-agnostic usage (e.g. over an in-memory stream):
   ```clojure
   (let [handlers {:demo/add +}
         stream (ds/open! {:type :ringbuffer, :capacity 1024})
         stop-atom (atom false)]
     (serve-connection! handlers stream stop-atom))
   ```"
  ([handlers stream stop-atom] (serve-connection! handlers stream stop-atom {}))
  ([handlers stream stop-atom {:keys [wrap-dispatch]}]
   (let [dispatch-fn (cond-> (fn [_req-id op args]
                               (dispatch-and-wrap handlers op args))
                       wrap-dispatch wrap-dispatch)]
     (letfn
       [(step
          [cursor consecutive-errors]
          (when-not (or @stop-atom (ds/closed? stream))
            (let [result (handle-one! stream cursor dispatch-fn)]
              (cond (:malformed? result)
                    (let [errors' (inc consecutive-errors)]
                      (if (> errors' max-consecutive-errors)
                        (ds/close! stream)
                        #?(:clj (do (Thread/sleep 10)
                                    (recur (:cursor result) errors'))
                           :cljs (js/setTimeout #(step (:cursor result)
                                                       errors')
                                                10)
                           :cljd (.then (async/Future.delayed
                                          (core/Duration .milliseconds 10))
                                        (fn [_]
                                          (step (:cursor result) errors'))))))
                    ;; No async boundary is crossed here (unlike the
                    ;; :blocked and :malformed? branches, which must hand
                    ;; off to setTimeout/Future.delayed), so recur is valid
                    ;; -- and required -- on every platform: a plain (step
                    ;; ...) call on :cljs/:cljd would grow the stack on
                    ;; every request in a back-to-back burst with no
                    ;; :blocked gap between them.
                    (map? result) (recur (:cursor result) 0)
                    (or (= result :blocked)
                        (= result :end)
                        (= result :daostream/gap))
                    #?(:clj (do (Thread/sleep 10) (recur cursor 0))
                       :cljs (js/setTimeout #(step cursor 0) 10)
                       :cljd (.then (async/Future.delayed
                                      (core/Duration .milliseconds 10))
                                    (fn [_] (step cursor 0))))
                    ;; Anything else (unrecognized sentinel/empty loop
                    ;; exit) -- exit the loop.
                    :else nil))))]
       #?(:clj (future (step {:position 0} 0))
          :cljs (step {:position 0} 0)
          :cljd (step {:position 0} 0))))))
