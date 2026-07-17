(ns dao.stream.rpc.client
  "Generic RPC client: call any op a dao.stream.rpc.server's handlers map exposes.

   connect! opens a WebSocket connection and returns a client value; call! sends a
   request and waits for the matching response: (call! client :demo/add [2 3]).

   Portable across :clj, :cljs, and :cljd, following the same portable
   wait-for-response pattern already established in yin.repl (see
   yin/repl.cljc:550-608): on :clj, waiting blocks the calling thread and returns
   the result directly; on :cljs/:cljd there is no thread to block, so waiting
   instead returns a js/Promise / dart:async Future that resolves once the
   response arrives, or rejects on error/timeout."
  (:require #?(:cljd ["dart:async" :as async])
            #?(:cljd ["dart:core" :as core])
            [dao.stream :as ds]
            [dao.stream.apply :as dao-apply]
            [dao.stream.ws :as ws]))


(def ^:private default-timeout-ms 5000)
(def ^:private poll-interval-ms 10)


(defn- max-attempts
  [timeout-ms]
  (max 1 (quot timeout-ms poll-interval-ms)))


(defn- advance-cursor!
  "Move cursor-atom forward to next-cursor, never backward.

   cursor-atom is shared across every concurrent call! on one client; each
   caller's poll loop advances it as it scans past responses meant for other
   calls. A plain reset! would let a stale (earlier) snapshot from one thread
   clobber a further-along position another thread already recorded, which can
   then cause a later call! to start scanning from before its own response and
   time out despite the response having already arrived."
  [cursor-atom next-cursor]
  (swap! cursor-atom (fn [current]
                       (if (> (:position next-cursor) (:position current))
                         next-cursor
                         current))))


(defn- wait-for-response
  "Wait for a response matching req-id on stream, starting from start-cursor.

   start-cursor must be a position at or before where req-id's eventual
   response can appear (i.e. captured before the request was sent) -- starting
   from a fresher shared cursor-atom snapshot instead risks skipping past a
   response that arrives between snapshot and send.

   Returns the response value directly on :clj (blocking). Returns a js/Promise
   on :cljs or a dart:async Future on :cljd that resolves to the response value,
   or rejects with an ex-info on remote error/timeout/stream error."
  [stream cursor-atom start-cursor req-id timeout-ms]
  (let [attempts-max (max-attempts timeout-ms)
        #?@(:cljs [p-resolve (atom nil)
                   p-reject (atom nil)
                   p (js/Promise. (fn [resolve reject]
                                    (reset! p-resolve resolve)
                                    (reset! p-reject reject)))]
            :cljd [completer (async/Completer.)])]
    (letfn
      [(succeed
         [value]
         #?(:clj value
            :cljs (@p-resolve value)
            :cljd (do (.complete ^async/Completer completer value) nil)))
       (fail
         [ex]
         #?(:clj (throw ex)
            :cljs (@p-reject ex)
            :cljd (do (.completeError ^async/Completer completer ex) nil)))
       (poll
         [cursor attempts]
         (if (> attempts attempts-max)
           (fail (ex-info "Request timeout"
                          {:request-id req-id, :timeout-ms timeout-ms}))
           (let [result (dao-apply/next-response stream cursor)]
             (cond (map? result)
                   (let [response (:ok result)
                         resp-id (dao-apply/response-id response)
                         next-cursor (:cursor result)]
                     (advance-cursor! cursor-atom next-cursor)
                     (if (= resp-id req-id)
                       (let [value (dao-apply/response-value response)]
                         (if-let [error (:error value)]
                           (fail (ex-info (str "Remote error: " error)
                                          {:request-id req-id, :error error}))
                           (succeed (:ok value))))
                       (recur next-cursor (inc attempts))))
                   (= result :blocked)
                   #?(:clj (do (Thread/sleep poll-interval-ms)
                               (recur cursor (inc attempts)))
                      :cljs (js/setTimeout #(poll cursor (inc attempts))
                                           poll-interval-ms)
                      :cljd (do (.then (async/Future.delayed
                                         (core/Duration .milliseconds
                                                        poll-interval-ms))
                                       (fn [_] (poll cursor (inc attempts))))
                                nil))
                   :else (fail (ex-info
                                 "Stream error while waiting for response"
                                 {:request-id req-id, :result result}))))))]
      #?(:clj (poll start-cursor 0)
         :cljs (do (poll start-cursor 0) p)
         :cljd (do (poll start-cursor 0)
                   (.-future ^async/Completer completer))))))


(defn- await-connected
  "Poll a just-opened WebSocketStream's connection-status until :connected.

   Returns the client map directly on :clj (blocking). Returns a js/Promise on
   :cljs or a dart:async Future on :cljd that resolves to the client map, or
   rejects with an ex-info on connection failure/timeout."
  [stream client url timeout-ms]
  (let [attempts-max (max-attempts timeout-ms)
        #?@(:cljs [p-resolve (atom nil)
                   p-reject (atom nil)
                   p (js/Promise. (fn [resolve reject]
                                    (reset! p-resolve resolve)
                                    (reset! p-reject reject)))]
            :cljd [completer (async/Completer.)])]
    (letfn
      [(succeed
         []
         #?(:clj client
            :cljs (@p-resolve client)
            :cljd (do (.complete ^async/Completer completer client) nil)))
       (fail
         [ex]
         #?(:clj (throw ex)
            :cljs (@p-reject ex)
            :cljd (do (.completeError ^async/Completer completer ex) nil)))
       (poll
         [attempts]
         (let [status (ws/connection-status stream)]
           (cond (= :connected status) (succeed)
                 (= :closed status) (fail (ex-info "Connection failed"
                                                   {:url url}))
                 (> attempts attempts-max)
                 (fail (ex-info "Connection timeout"
                                {:url url, :timeout-ms timeout-ms}))
                 :else #?(:clj (do (Thread/sleep poll-interval-ms)
                                   (recur (inc attempts)))
                          :cljs (js/setTimeout #(poll (inc attempts))
                                               poll-interval-ms)
                          :cljd (do (.then (async/Future.delayed
                                             (core/Duration .milliseconds
                                                            poll-interval-ms))
                                           (fn [_] (poll (inc attempts))))
                                    nil)))))]
      #?(:clj (poll 0)
         :cljs (do (poll 0) p)
         :cljd (do (poll 0) (.-future ^async/Completer completer))))))


(defn connect!
  "Connect to an RPC server at url.

   Args:
     url  - WebSocket URL (e.g. \"ws://localhost:8080\")
     opts - optional map with:
       :timeout-ms         - connection handshake timeout in ms (default 5000)
       :request-timeout-ms - per-call! timeout in ms (default 5000), stored on
                              the returned client and used by every call! made
                              with it

   Returns a client map {:stream :response-cursor-atom :request-id-atom
   :closed-atom :request-timeout-ms} directly on :clj (blocking until
   connected). Returns a js/Promise on :cljs or a dart:async Future on :cljd
   that resolves to the same client map once connected, or rejects on
   failure/timeout."
  ([url] (connect! url {}))
  ([url opts]
   (let [timeout-ms (get opts :timeout-ms default-timeout-ms)
         request-timeout-ms (get opts :request-timeout-ms default-timeout-ms)
         stream (ws/connect! url)
         client {:stream stream,
                 :response-cursor-atom (atom {:position 0}),
                 :request-id-atom (atom 0),
                 :closed-atom (atom false),
                 :request-timeout-ms request-timeout-ms}]
     (await-connected stream client url timeout-ms))))


(defn call!
  "Send an op/args request to a connected client and wait for the response.

   client must have :stream :response-cursor-atom :request-id-atom
   :closed-atom, as produced by connect! (or any map/record with this shape).
   Uses client's :request-timeout-ms if present, else default-timeout-ms.

   Returns the result value directly on :clj (blocking). Returns a js/Promise on
   :cljs or a dart:async Future on :cljd that resolves to the result value, or
   rejects on remote error/timeout."
  [{:keys [stream response-cursor-atom request-id-atom closed-atom
           request-timeout-ms]} op args]
  (when @closed-atom (throw (ex-info "Client is closed" {:operation op})))
  ;; Snapshot the cursor before sending: the eventual response can only
  ;; appear at or after this position, so starting the scan here (rather
  ;; than re-derefing response-cursor-atom after another thread may have
  ;; advanced it further) guarantees this call's own response can't be
  ;; skipped.
  (let [start-cursor @response-cursor-atom
        req-id (swap! request-id-atom inc)]
    (dao-apply/put-request! stream req-id op args)
    (wait-for-response stream
                       response-cursor-atom
                       start-cursor
                       req-id
                       (or request-timeout-ms default-timeout-ms))))


(defn close!
  "Close a connected client. Idempotent."
  [{:keys [stream closed-atom]}]
  (when (compare-and-set! closed-atom false true) (ds/close! stream)))
