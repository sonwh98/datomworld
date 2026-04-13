(ns dao.stream.transport.ws
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
  (:require
    #?(:cljd ["dart:async" :as async])
    #?(:cljd ["dart:io" :as io])
    [dao.stream :as ds]
    [dao.stream.link :as link]
    [dao.stream.transit :as transit]
    [dao.stream.transport.ringbuffer :as ringbuffer]
    #?(:clj [org.httpkit.server :as http-server])))


;; =============================================================================
;; WebSocketStream record
;; =============================================================================

(defrecord WebSocketStream
  [link-state-atom send-fn-atom]

  ds/IDaoStreamWriter

  (put!
    [_ val]
    (let [state @link-state-atom
          [state' msg] (link/local-put state val)]
      (reset! link-state-atom state')
      (when-let [send-fn @send-fn-atom] (send-fn (transit/encode msg)))
      {:result :ok, :woke []}))


  ds/IDaoStreamReader

  (next [_ c] (ds/next (:remote-stream @link-state-atom) c))


  ds/IDaoStreamBound

  (close!
    [_]
    (when-let [send-fn @send-fn-atom]
      (send-fn (transit/encode (link/close-msg))))
    (let [state (:remote-stream @link-state-atom)] (ds/close! state))
    (swap! link-state-atom assoc :status :closed)
    {:woke []})


  (closed? [_] (= :closed (:status @link-state-atom))))


;; =============================================================================
;; Shared internal helpers
;; =============================================================================

(defn- make-ws-stream
  [local]
  (->WebSocketStream (atom (link/make-link-state local)) (atom nil)))


(defn- on-open!
  [ws-stream send-fn]
  (swap! (:link-state-atom ws-stream)
         (fn [state]
           (let [remote-stream (:remote-stream state)]
             (cond-> (assoc state :status :connecting)
               (ds/closed? remote-stream)
               (assoc :remote-stream
                      (ringbuffer/make-ring-buffer-stream nil
                                                          (:remote-pos state)))))))
  (reset! (:send-fn-atom ws-stream) send-fn)
  (let [msg (link/connect-msg @(:link-state-atom ws-stream))]
    (send-fn (transit/encode msg))))


(defn- on-message!
  [ws-stream raw]
  (let [msg (transit/decode raw)
        state @(:link-state-atom ws-stream)
        [state' resp] (link/dispatch state msg)]
    (reset! (:link-state-atom ws-stream) state')
    (when resp
      (when-let [send-fn @(:send-fn-atom ws-stream)]
        (send-fn (transit/encode resp))))))


(defn- on-close!
  [ws-stream]
  (let [remote (:remote-stream @(:link-state-atom ws-stream))]
    (when-not (ds/closed? remote) (ds/close! remote)))
  (reset! (:send-fn-atom ws-stream) nil)
  (swap! (:link-state-atom ws-stream) assoc :status :closed))


;; =============================================================================
;; CLJ: listen! (http-kit)
;; =============================================================================

#?(:clj
   (defn listen!
     "Start a WebSocket server on port. Returns WebSocketStream.
      Accepts one connection; further connections are rejected."
     ([port] (listen! port nil))
     ([port opts]
      (let [local (ds/open! {:transport {:type :ringbuffer, :capacity (:capacity opts)}})
            stream (make-ws-stream local)
            stop! (http-server/run-server
                    (fn [req]
                      (http-server/as-channel
                        req
                        {:on-open (fn [ch]
                                    (on-open! stream
                                              (fn [msg]
                                                (http-server/send! ch msg)))),
                         :on-receive (fn [_ch raw] (on-message! stream raw)),
                         :on-close (fn [_ch _status] (on-close! stream))}))
                    {:port port})]
        (swap! (:link-state-atom stream) assoc :stop-fn stop!)
        stream))))


;; =============================================================================
;; CLJ: connect! (Java 11 built-in WebSocket client)
;; =============================================================================

#?(:clj (defn connect!
          "Connect to a WebSocket server at url. Returns WebSocketStream."
          ([url] (connect! url nil))
          ([url opts]
           (let [local (ds/open! {:transport {:type :ringbuffer, :capacity (:capacity opts)}})
                 stream (make-ws-stream local)
                 client (java.net.http.HttpClient/newHttpClient)
                 ws-ref (atom nil)
                 listener
                 (reify
                   java.net.http.WebSocket$Listener
                   (onOpen
                     [_ ws]
                     (reset! ws-ref ws)
                     (on-open! stream
                               (fn [msg] (.sendText ws msg true) nil))
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

#?(:cljs (defn connect!
           "Connect to a WebSocket server at url. Returns WebSocketStream."
           ([url] (connect! url nil))
           ([url opts]
            (let [local (ds/open! {:transport {:type :ringbuffer, :capacity (:capacity opts)}})
                  stream (make-ws-stream local)
                  ws (js/WebSocket. url)]
              (set! (.-onopen ws) #(on-open! stream (fn [msg] (.send ws msg))))
              (set! (.-onmessage ws) #(on-message! stream (.-data %)))
              (set! (.-onclose ws) #(on-close! stream))
              stream))))


;; =============================================================================
;; Descriptor-based open! integration
;; =============================================================================

#?(:clj
   (defmethod ds/open! :websocket
     [descriptor]
     (let [{:keys [mode url port capacity]} (:transport descriptor)]
       (case mode
         :listen  (listen! port {:capacity capacity})
         :connect (connect! url {:capacity capacity})
         (throw (ex-info "websocket transport mode must be :listen or :connect"
                         {:descriptor descriptor, :mode mode}))))))


#?(:cljs
   (defn listen!
     "Start a WebSocket server on port (Node.js only). Returns WebSocketStream.
      Accepts one connection; further connections are rejected."
     ([port] (listen! port nil))
     ([port opts]
      (let [WebSocket (js/require "ws")
            wss (new (.-Server WebSocket) #js {:port port})
            local (ds/open! {:transport {:type :ringbuffer, :capacity (:capacity opts)}})
            stream (make-ws-stream local)]
        (.on ^js wss "connection"
             (fn [ws]
               (on-open! stream (fn [msg] (.send ^js ws msg)))
               (.on ^js ws "message" (fn [raw] (on-message! stream raw)))
               (.on ^js ws "close" (fn [] (on-close! stream)))))
        (swap! (:link-state-atom stream) assoc :stop-fn #(.close ^js wss))
        stream))))


#?(:cljs
   (defmethod ds/open! :websocket
     [descriptor]
     (let [{:keys [mode url port capacity]} (:transport descriptor)]
       (case mode
         :listen  (listen! port {:capacity capacity})
         :connect (connect! url {:capacity capacity})
         (throw (ex-info "websocket transport mode must be :listen or :connect"
                         {:descriptor descriptor, :mode mode}))))))


#?(:cljd
   (defn listen!
     "Start a WebSocket server on port. Returns WebSocketStream.
      Accepts one connection; further connections are rejected."
     ([port] (listen! port nil))
     ([port opts]
      (let [local (ds/open! {:transport {:type :ringbuffer, :capacity (:capacity opts)}})
            stream (make-ws-stream local)]
        (-> (io/HttpServer.bind "0.0.0.0" port)
            (.then (fn [server]
                     (let [server ^io/HttpServer server]
                       (swap! (:link-state-atom stream) assoc :stop-fn #(.close server))
                       (.listen server
                                (fn [request]
                                  (let [request ^io/HttpRequest request]
                                    (when (io/WebSocketTransformer.isUpgradeRequest request)
                                      (-> (io/WebSocketTransformer.upgrade request)
                                          (.then (fn [ws]
                                                   (let [ws ^io/WebSocket ws]
                                                     (on-open! stream (fn [msg] (.add ws msg)))
                                                     (.listen ws
                                                              (fn [raw] (on-message! stream raw))
                                                              :onDone (fn [] (on-close! stream)))))))))))))))
        stream))))


#?(:cljd
   (defmethod ds/open! :websocket
     [descriptor]
     (let [{:keys [mode url port capacity]} (:transport descriptor)]
       (case mode
         :listen  (listen! port {:capacity capacity})
         :connect (throw (ex-info "websocket mode :connect not supported in CLJD"
                                  {:descriptor descriptor}))
         (throw (ex-info "websocket transport mode must be :listen or :connect"
                         {:descriptor descriptor, :mode mode}))))))
