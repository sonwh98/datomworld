(ns dao.stream.rpc.ws
  "WebSocket convenience layer over the transport-agnostic dao.stream.rpc
   primitives. Use this when your transport is a WebSocket; use
   dao.stream.rpc.client/init-client and dao.stream.rpc.server/serve-connection!
   directly for any other IDaoStream transport."
  (:require [dao.stream :as ds]
            [dao.stream.rpc.client :as rpc-client]
            [dao.stream.rpc.server :as rpc-server]
            [dao.stream.ws :as ws]))


(defn connect!
  "Connect to an RPC server at url. Returns an rpc-client client map
   directly on :clj (blocking until the WebSocket handshake completes).
   Returns a js/Promise on :cljs or a dart:async Future on :cljd that
   resolves to the same client map, or rejects on connection
   failure/timeout.

   Args:
     url  - WebSocket URL (e.g. \"ws://localhost:8080\")
     opts - optional map:
       :timeout-ms         - WebSocket handshake timeout (default 5000)
       :request-timeout-ms - per-call! timeout (default 5000), stored on
                              the returned client
       :capacity           - ringbuffer capacity, passed through to ws/connect!
       :eviction-policy    - ringbuffer eviction policy, passed through to
                              ws/connect!"
  ([url] (connect! url {}))
  ([url opts]
   (let [timeout-ms (get opts :timeout-ms 5000)
         stream (ws/connect! url opts)
         client (rpc-client/init-client stream opts)]
     #?(:clj (do (ws/await-connected stream timeout-ms {:url url}) client)
        :cljs (-> (ws/await-connected stream timeout-ms {:url url})
                  (.then (fn [_] client)))
        :cljd (-> (ws/await-connected stream timeout-ms {:url url})
                  (.then (fn [_] client)))))))


(defn start!
  "Start a WebSocket RPC server exposing handlers.

   Args:
     handlers - a {op-keyword fn} map built by the caller in trusted
                process code. The op keyword arriving over the wire is
                only ever used as a lookup key into this map.
     port     - port number to listen on
     opts     - optional map passed to ws/listen!, plus :on-connect /
                :on-disconnect callbacks that run after the RPC layer
                has accepted the connection

   Returns:
     {:port <port> :stop! <fn> :server <handle> :conns <atom-of-streams>}"
  ([handlers port] (start! handlers port {}))
  ([handlers port opts]
   (let [stop-atom (atom false)
         conns-atom (atom #{})
         caller-on-connect (:on-connect opts)
         caller-on-disconnect (:on-disconnect opts)
         server-handle
         (ws/listen!
           port
           (assoc opts
                  :on-connect
                  (fn [stream]
                    (swap! conns-atom conj stream)
                    (rpc-server/serve-connection! handlers stream stop-atom)
                    (when caller-on-connect (caller-on-connect stream)))
                  :on-disconnect (fn [stream]
                                   (swap! conns-atom disj stream)
                                   (when caller-on-disconnect
                                     (caller-on-disconnect stream)))))]
     {:port port,
      :stop! (fn []
               (reset! stop-atom true)
               ;; Grace period so in-flight polling loops observe stop-atom
               ;; before their stream is closed out from under them.
               #?(:clj (Thread/sleep 100))
               (doseq [stream @conns-atom] (ds/close! stream))
               ((:stop-fn server-handle))),
      :server server-handle,
      :conns conns-atom})))


(defn stop!
  "Stop a running WebSocket RPC server."
  [server]
  ((:stop! server)))
