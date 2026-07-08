(ns dao.stream.rpc.server
  "Generic RPC server: exposes a caller-supplied handlers map over a WebSocket via
   dao.stream.apply.

   handlers is a plain {op-keyword fn} map built in trusted process code and passed
   into start!. The op keyword arriving over the wire is only ever used as a lookup
   key into this pre-registered map -- the same closed-map dispatch
   dao.stream.apply/dispatch-request already implements. There is no dynamic
   symbol/var resolution of wire-supplied input, on any platform: a remote peer can
   only invoke functions this process explicitly registered in handlers.

   Portable across :clj, :cljs, and :cljd. Each client connection gets its own
   request loop that reads requests and sends responses over that connection's
   stream: on :clj this is a real background thread (a future) blocking briefly
   between polls; on :cljs/:cljd there is no blocking thread, so the loop
   self-reschedules via js/setTimeout / dart:async Future.delayed instead."
  (:require #?(:cljd ["dart:async" :as async])
            #?(:cljd ["dart:core" :as core])
            [dao.stream :as ds]
            [dao.stream.apply :as dao-apply]
            [dao.stream.ws :as ws]))


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
  "Read and dispatch a single request at cursor.

   Returns {:cursor next-cursor} on success, the underlying ds/next sentinel
   (:blocked, :end, :daostream/gap) unchanged when nothing was available, or
   {:cursor next-cursor :malformed? true} when reading/decoding the request itself
   threw (the cursor is advanced defensively past the bad position so the loop
   cannot retry the same broken entry forever)."
  [handlers stream cursor]
  (try (let [req-result (dao-apply/next-request stream cursor)]
         (if (map? req-result)
           (let [request (:ok req-result)
                 req-id (dao-apply/request-id request)
                 op (dao-apply/request-op request)
                 args (dao-apply/request-args request)
                 result (dispatch-and-wrap handlers op args)
                 response (dao-apply/response req-id result)]
             (dao-apply/put-response! stream response)
             {:cursor (:cursor req-result)})
           req-result))
       (catch #?(:clj Exception
                 :cljs :default
                 :cljd Object)
              _
         {:cursor (update cursor :position (fnil inc 0)), :malformed? true})))


(defn- serve-connection!
  "Serve requests from a single client connection until the connection closes or
   stop-atom is set."
  [handlers stream stop-atom]
  (letfn
    [(step
       [cursor consecutive-errors]
       (when-not (or @stop-atom (ds/closed? stream))
         (let [result (handle-one! handlers stream cursor)]
           (cond (:malformed? result)
                 (let [errors' (inc consecutive-errors)]
                   (if (> errors' max-consecutive-errors)
                     (ds/close! stream)
                     #?(:clj (do (Thread/sleep 10)
                                 (recur (:cursor result) errors'))
                        :cljs (js/setTimeout #(step (:cursor result) errors')
                                             10)
                        :cljd (.then (async/Future.delayed
                                       (core/Duration .milliseconds 10))
                                     (fn [_]
                                       (step (:cursor result) errors'))))))
                 ;; No async boundary is crossed here (unlike the :blocked
                 ;; and :malformed? branches, which must hand off to
                 ;; setTimeout/Future.delayed), so recur is valid -- and
                 ;; required -- on every platform: a plain (step ...) call
                 ;; on :cljs/:cljd would grow the stack on every request in
                 ;; a back-to-back burst with no :blocked gap between them.
                 (map? result) (recur (:cursor result) 0)
                 (= result :blocked)
                 #?(:clj (do (Thread/sleep 10) (recur cursor 0))
                    :cljs (js/setTimeout #(step cursor 0) 10)
                    :cljd (.then (async/Future.delayed
                                   (core/Duration .milliseconds 10))
                                 (fn [_] (step cursor 0))))
                 ;; Stream ended (:end / :daostream/gap) -- exit the loop.
                 :else nil))))]
    #?(:clj (future (step {:position 0} 0))
       :cljs (step {:position 0} 0)
       :cljd (step {:position 0} 0))))


(defn start!
  "Start a WebSocket server exposing handlers.

   Args:
     handlers - a {op-keyword fn} map built by the caller in trusted process code.
                The op keyword arriving over the wire is only ever used as a lookup
                key into this map; there is no dynamic resolution of wire-supplied
                input, so a remote peer can only invoke what handlers registers.
     port     - port number to listen on
     opts     - optional map passed to ws/listen!

   Returns:
     {:port <port> :stop! <fn> :server <handle> :conns <atom-of-streams>}"
  ([handlers port] (start! handlers port {}))
  ([handlers port opts]
   (let [stop-atom (atom false)
         conns-atom (atom #{})
         server-handle
         (ws/listen!
           port
           (assoc opts
                  :on-connect (fn [stream]
                                (swap! conns-atom conj stream)
                                (serve-connection! handlers stream stop-atom))
                  :on-disconnect (fn [stream] (swap! conns-atom disj stream))))]
     {:port port,
      :stop! (fn []
               (reset! stop-atom true)
               ;; Best-effort grace period for in-flight polling loops to
               ;; observe stop-atom before their stream is closed out from
               ;; under them. Only :clj has a real blocking sleep;
               ;; :cljs/:cljd close immediately.
               #?(:clj (Thread/sleep 100))
               (doseq [stream @conns-atom] (ds/close! stream))
               ((:stop-fn server-handle))),
      :server server-handle,
      :conns conns-atom})))


(defn stop!
  "Stop a running RPC server."
  [server]
  ((:stop! server)))
