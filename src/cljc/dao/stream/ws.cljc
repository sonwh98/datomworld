(ns dao.stream.ws
  "WebSocket-backed stream using three orthogonal protocols.

   Three entry points:
     CLJ listen!    — start an http-kit WebSocket server, return WebSocketStream
     CLJS listen!   — start a 'ws' Node.js WebSocket server, return WebSocketStream
     CLJD listen!   — start a 'dart:io' WebSocket server, return WebSocketStream
     CLJ/CLJS connect! — connect to a WebSocket server

   Both sides get back a WebSocketStream satisfying:
     IDaoStreamWriter — put! sends a datom to the remote peer
     IDaoStreamReader — next reads non-destructively from remote-stream at cursor
     IDaoStreamBound  — close! and closed? manage lifecycle

   Utilities (non-protocol):
     drain-one!      — destructive read from remote-stream (legacy support)"
  (:require #?(:cljd ["dart:async" :as async])
            #?(:cljd ["dart:io" :as io])
            [dao.stream :as ds]
            [dao.stream.link :as link]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.transit :as transit]
            #?(:clj [org.httpkit.server :as http-server])))


;; =============================================================================
;; WebSocketStream record
;; =============================================================================

(defn- do-put!
  "Apply val to link-state-atom's local side and send it over the wire if
   connected. Not safe to run concurrently on the same link-state-atom -- callers
   on platforms with real thread parallelism (:clj) must serialize calls (see
   WebSocketStream's put!, which locks on link-state-atom for :clj; :cljs/:cljd
   have no true concurrent threads calling into one stream, so no lock is
   needed there)."
  [link-state-atom send-fn-atom val]
  (let [state @link-state-atom
        [state' msg] (link/local-put state val)]
    (if-let [send-fn @send-fn-atom]
      (do (reset! link-state-atom (assoc state'
                                         :local-sent-pos (:local-pos state')))
          (send-fn (transit/encode msg)))
      (reset! link-state-atom state'))
    {:result :ok, :woke []}))


(defrecord WebSocketStream
  [link-state-atom send-fn-atom]

  ds/IDaoStreamWriter

  (put!
    [_ val]
    ;; :clj's send-fn synchronously blocks on the in-flight send
    ;; (java.net.http's WebSocket throws if a second sendText is issued
    ;; before the first completes), so concurrent put! calls on one
    ;; stream must be serialized; :cljs/:cljd have no real concurrent
    ;; threads sharing one stream.
    #?(:clj (locking link-state-atom
              (do-put! link-state-atom send-fn-atom val))
       :cljs (do-put! link-state-atom send-fn-atom val)
       :cljd (do-put! link-state-atom send-fn-atom val)))


  ds/IDaoStreamReader

  (next [_ c] (ds/next (:remote-stream @link-state-atom) c))


  ds/IDaoStreamBound

  (close!
    [_]
    (when-let [send-fn @send-fn-atom]
      (send-fn (transit/encode (link/close-msg))))
    (let [state (:remote-stream @link-state-atom)] (ds/close! state))
    (swap! link-state-atom assoc :status :closed)
    (when-let [close-fn (:socket-close-fn @link-state-atom)] (close-fn))
    {:woke []})


  (closed? [_] (= :closed (:status @link-state-atom))))


;; =============================================================================
;; Shared internal helpers
;; =============================================================================

(defn make-ws-stream
  [local]
  (->WebSocketStream (atom (assoc (link/make-link-state local)
                                  :local-sent-pos 0))
                     (atom nil)))


(defn- stream->vec-from
  [stream from-pos]
  (loop [c {:position from-pos}
         acc []]
    (let [r (ds/next stream c)]
      (if (map? r) (recur (:cursor r) (conj acc (:ok r))) acc))))


(defn- unsent-local-put-msg
  [state]
  (let [from-pos (or (:local-sent-pos state) 0)
        to-pos (:local-pos state)
        datoms (when (< from-pos to-pos)
                 (stream->vec-from (:local-stream state) from-pos))]
    (when (seq datoms) (link/put-msg datoms from-pos))))


(defn on-open!
  [ws-stream send-fn]
  (swap! (:link-state-atom ws-stream)
         (fn [state]
           (let [remote-stream (:remote-stream state)]
             (cond-> (assoc state :status :connecting)
               (ds/closed? remote-stream)
               (assoc :remote-stream
                      (ringbuffer/make-ring-buffer-stream nil (:remote-pos state)))))))
  (reset! (:send-fn-atom ws-stream) send-fn)
  (let [state @(:link-state-atom ws-stream)]
    (send-fn (transit/encode (link/connect-msg state)))
    (when-let [msg (unsent-local-put-msg state)]
      (send-fn (transit/encode msg))
      (swap! (:link-state-atom ws-stream) assoc
             :local-sent-pos
             (:local-pos state)))))


(defn on-message!
  [ws-stream raw]
  (let [msg (transit/decode raw)
        state @(:link-state-atom ws-stream)
        [state' resp] (link/dispatch state msg)]
    (reset! (:link-state-atom ws-stream) state')
    (when resp
      (when-let [send-fn @(:send-fn-atom ws-stream)]
        (send-fn (transit/encode resp))))))


(defn on-close!
  [ws-stream]
  (let [remote (:remote-stream @(:link-state-atom ws-stream))]
    (when-not (ds/closed? remote) (ds/close! remote)))
  (reset! (:send-fn-atom ws-stream) nil)
  (swap! (:link-state-atom ws-stream) assoc :status :closed))


(defn connection-status
  "Return the current link status for a WebSocketStream, or nil for other streams."
  [stream]
  (when (instance? WebSocketStream stream)
    (:status @(:link-state-atom stream))))


;; =============================================================================
;; CLJ: listen! (http-kit)
;; =============================================================================

#?(:clj
   (defn listen!
     "Start a WebSocket server on port. Returns a server handle map.
      Accepts multiple connections, exposing them via :on-connect."
     ([port] (listen! port nil))
     ([port opts]
      (let [conns (atom #{})
            stop!
            (http-server/run-server
              (fn [req]
                (http-server/as-channel
                  req
                  (let [local (ds/open! {:type :ringbuffer,
                                         :capacity (:capacity opts),
                                         :eviction-policy (:eviction-policy
                                                            opts)})
                        stream (make-ws-stream local)]
                    {:on-open
                     (fn [ch]
                       (swap! (:link-state-atom stream) assoc
                              :socket-close-fn
                              (fn [] (http-server/close ch)))
                       (on-open! stream
                                 (fn [msg] (http-server/send! ch msg)))
                       (swap! conns conj stream)
                       (when-let [oc (:on-connect opts)] (oc stream))),
                     :on-receive (fn [_ch raw] (on-message! stream raw)),
                     :on-close (fn [_ch _status]
                                 (on-close! stream)
                                 (swap! conns disj stream)
                                 (when-let [od (:on-disconnect opts)]
                                   (od stream)))})))
              {:port port})]
        {:stop-fn stop!, :conns conns}))))


;; =============================================================================
;; CLJ: connect! (Java 11 built-in WebSocket client)
;; =============================================================================

#?(:clj (defn connect!
          "Connect to a WebSocket server at url. Returns WebSocketStream."
          ([url] (connect! url nil))
          ([url opts]
           (let [local (ds/open! {:type :ringbuffer,
                                  :capacity (:capacity opts),
                                  :eviction-policy (:eviction-policy opts)})
                 stream (make-ws-stream local)
                 client (java.net.http.HttpClient/newHttpClient)
                 ws-ref (atom nil)
                 listener
                 (reify
                   java.net.http.WebSocket$Listener
                   (onOpen
                     [_ ws]
                     (reset! ws-ref ws)
                     (swap! (:link-state-atom stream) assoc
                            :socket-close-fn
                            (fn []
                              (.sendClose ws
                                          java.net.http.WebSocket/NORMAL_CLOSURE
                                          "close")))
                     ;; Java's WebSocket client sends text frames
                     ;; asynchronously. Block each send until it is
                     ;; accepted so the initial sync-request and the
                     ;; first
                     ;; REPL request cannot race.
                     (on-open!
                       stream
                       (fn [msg] (.join (.sendText ws msg true)) nil))
                     (.request ws 1))

                   (onText
                     [_ ws data _last?]
                     (on-message! stream (str data))
                     (.request ws 1)
                     (java.util.concurrent.CompletableFuture/completedFuture
                       nil))

                   (onClose
                     [_ _ws _code _reason]
                     (on-close! stream)
                     (java.util.concurrent.CompletableFuture/completedFuture
                       nil))

                   (onError [_ _ws _err] (on-close! stream)))]
             (.thenAccept (.buildAsync (.. client newWebSocketBuilder)
                                       (java.net.URI/create url)
                                       listener)
                          (fn [_ws] nil))
             stream))))


;; =============================================================================
;; CLJS: connect! (browser WebSocket)
;; =============================================================================

#?(:cljs
   (defn connect!
     "Connect to a WebSocket server at url. Returns WebSocketStream."
     ([url] (connect! url nil))
     ([url opts]
      (let [local (ds/open! {:type :ringbuffer, :capacity (:capacity opts)})
            stream (make-ws-stream local)
            ws (js/WebSocket. url)]
        (set! (.-onopen ws)
              #(do (swap! (:link-state-atom stream) assoc
                          :socket-close-fn
                          (fn [] (.close ws)))
                   (on-open! stream (fn [msg] (.send ws msg)))
                   (when-let [callback (:on-open opts)] (callback stream %))))
        (set! (.-onmessage ws)
              #(do (on-message! stream (.-data %))
                   (when-let [callback (:on-message opts)]
                     (callback stream %))))
        (set! (.-onerror ws)
              #(when-let [callback (:on-error opts)] (callback stream %)))
        (set! (.-onclose ws)
              #(do (on-close! stream)
                   (when-let [callback (:on-close opts)]
                     (callback stream %))))
        stream))))


;; =============================================================================
;; Descriptor-based open! integration
;; =============================================================================

#?(:clj (defmethod ds/open! :websocket
          [descriptor]
          (let [{:keys [mode url port capacity eviction-policy], :as opts}
                descriptor]
            (case mode
              :listen (listen! port opts)
              :connect (connect! url
                                 {:capacity capacity,
                                  :eviction-policy eviction-policy})
              (throw (ex-info
                       "websocket transport mode must be :listen or :connect"
                       {:descriptor descriptor, :mode mode}))))))


#?(:cljs
   (defn listen!
     "Start a WebSocket server on port (Node.js only). Returns a server handle map.
      Accepts multiple connections, exposing them via :on-connect."
     ([port] (listen! port nil))
     ([port opts]
      (let [WebSocket (js/require "ws")
            wss (new (.-Server WebSocket) #js {:port port})
            conns (atom #{})]
        (.on ^js wss
             "connection"
             (fn [ws]
               (let [local (ds/open! {:type :ringbuffer,
                                      :capacity (:capacity opts),
                                      :eviction-policy (:eviction-policy
                                                         opts)})
                     stream (make-ws-stream local)]
                 (swap! (:link-state-atom stream) assoc
                        :socket-close-fn
                        (fn [] (.close ^js ws)))
                 (on-open! stream (fn [msg] (.send ^js ws msg)))
                 (swap! conns conj stream)
                 (when-let [oc (:on-connect opts)] (oc stream))
                 (.on ^js ws "message" (fn [raw] (on-message! stream raw)))
                 (.on ^js ws
                      "close"
                      (fn []
                        (on-close! stream)
                        (swap! conns disj stream)
                        (when-let [od (:on-disconnect opts)] (od stream)))))))
        {:stop-fn #(.close ^js wss), :conns conns}))))


#?(:cljs (defmethod ds/open! :websocket
           [descriptor]
           (let [{:keys [mode url port capacity eviction-policy], :as opts}
                 descriptor]
             (case mode
               :listen (listen! port opts)
               :connect (connect! url
                                  {:capacity capacity,
                                   :eviction-policy eviction-policy})
               (throw (ex-info
                        "websocket transport mode must be :listen or :connect"
                        {:descriptor descriptor, :mode mode}))))))


#?(:cljd
   (defn listen!
     "Start a WebSocket server on port. Returns a server handle map.
      Accepts multiple connections, exposing them via :on-connect."
     ([port] (listen! port nil))
     ([port opts]
      (let [conns (atom #{})
            server-ref (atom nil)]
        (->
          (io/HttpServer.bind "0.0.0.0" port)
          (.then
            (fn [server]
              (let [server ^io/HttpServer server]
                (reset! server-ref server)
                (.listen
                  server
                  (fn [request]
                    (let [request ^io/HttpRequest request]
                      (when (io/WebSocketTransformer.isUpgradeRequest request)
                        (-> (io/WebSocketTransformer.upgrade request)
                            (.then
                              (fn [ws]
                                (let [ws ^io/WebSocket ws
                                      local (ds/open! {:type :ringbuffer,
                                                       :capacity (:capacity
                                                                   opts)})
                                      stream (make-ws-stream local)]
                                  (on-open! stream (fn [msg] (.add ws msg)))
                                  (swap! conns conj stream)
                                  (when-let [oc (:on-connect opts)]
                                    (oc stream))
                                  (.listen ws
                                           (fn [raw] (on-message! stream raw))
                                           :onDone
                                           (fn []
                                             (on-close! stream)
                                             (swap! conns disj stream)
                                             (when-let [od (:on-disconnect
                                                             opts)]
                                               (od stream))))))))))))))))
        {:stop-fn #(when-let [srv @server-ref] (.close ^io/HttpServer srv)),
         :conns conns}))))


;; =============================================================================
;; CLJD: connect! (dart:io WebSocket client)
;; =============================================================================

#?(:cljd (defn connect!
           "Connect to a WebSocket server at url. Returns WebSocketStream."
           ([url] (connect! url nil))
           ([url opts]
            (let [local (ds/open! {:type :ringbuffer,
                                   :capacity (:capacity opts)})
                  stream (make-ws-stream local)]
              (-> (io/WebSocket.connect url)
                  (.then (fn [ws]
                           (let [ws ^io/WebSocket ws]
                             (on-open! stream (fn [msg] (.add ws msg)))
                             (.listen ws
                                      (fn [raw] (on-message! stream raw))
                                      :onDone
                                      (fn [] (on-close! stream)))))))
              stream))))


#?(:cljd (defmethod ds/open! :websocket
           [descriptor]
           (let [{:keys [mode url port capacity], :as opts} descriptor]
             (case mode
               :listen (listen! port opts)
               :connect (connect! url {:capacity capacity})
               (throw (ex-info
                        "websocket transport mode must be :listen or :connect"
                        {:descriptor descriptor, :mode mode}))))))
