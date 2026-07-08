(ns dao.jing.remote.server
  "dao.jing adapter over the generic dao.stream.rpc RPC layer.

   Exposes a local dao.jing IKVStore over the network by building the default
   handlers map ({:jing/put! ... :jing/cas! ... :jing/get ... :jing/delete! ...})
   and delegating everything else -- transport, dispatch, request/response
   framing -- to dao.stream.rpc.server, which knows nothing about dao.jing.

   Passing a custom :handlers map to start! replaces the jing default map
   entirely (no implicit merge). Callers who want both jing ops and custom ops
   should merge explicitly:
     (merge (default-handlers store) {:my/op my-fn})"
  (:require [dao.jing :as jing]
            [dao.stream.rpc.server :as rpc-server]))


(defn default-handlers
  "The default handlers map exposing a dao.jing IKVStore's 4 operations."
  [store]
  {:jing/put! (partial jing/put! store),
   :jing/cas! (partial jing/cas! store),
   :jing/get (partial jing/get store),
   :jing/delete! (partial jing/delete! store)})


(defn start!
  "Start a WebSocket server exposing store's IKVStore operations.

   Args:
     store - an IKVStore implementation (e.g., from jing/create-kv-mem or jing.file/create-kv-file)
     port  - port number to listen on
     opts  - optional map passed to dao.stream.rpc.server/start!, plus:
       :handlers - a handlers map that REPLACES the jing default map entirely

   Returns:
     {:port <port> :stop! <fn> :server <handle>}"
  ([store port] (start! store port {}))
  ([store port opts]
   (let [handlers (get opts :handlers (default-handlers store))]
     (rpc-server/start! handlers port (dissoc opts :handlers)))))


(defn stop!
  "Stop a running remote server."
  [server]
  (rpc-server/stop! server))
