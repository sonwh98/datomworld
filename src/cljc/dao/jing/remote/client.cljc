(ns dao.jing.remote.client
  "dao.jing adapter over the generic dao.stream.rpc RPC layer.

   RemoteKVStore implements dao.jing/IKVStore by delegating each operation to
   dao.stream.rpc.client/call! against a fixed :jing/* op keyword -- transport,
   request/response framing, and waiting for the matching response are all
   handled by dao.stream.rpc.client, which knows nothing about dao.jing.

   dao.jing/IKVStore methods are a synchronous-return contract on every existing
   implementation (mem/file included); dao.stream.rpc.client/call! returns that
   value directly only on :clj (it returns a Promise/Future on :cljs/:cljd).
   Reconciling a synchronous protocol with an async client on those platforms is
   a separate, unscoped problem, so RemoteKVStore/connect! stay :clj-only here --
   the generic dao.stream.rpc layer underneath is portable across clj/cljs/cljd
   regardless."
  (:require [dao.jing :as jing]
            [dao.stream :as ds]
            #?(:clj [dao.stream.rpc.client :as rpc-client])))


#?(:clj (defrecord RemoteKVStore
          [stream response-cursor-atom request-id-atom
           closed-atom]

          jing/IKVStore

          (put! [this k v-map] (rpc-client/call! this :jing/put! [k v-map]))


          (cas!
            [this k old-rev v-map]
            (rpc-client/call! this :jing/cas! [k old-rev v-map]))


          (get
            [this k not-found]
            (rpc-client/call! this :jing/get [k not-found]))


          (delete! [this k] (rpc-client/call! this :jing/delete! [k]))


          (close!
            [_]
            (when (compare-and-set! closed-atom false true)
              (ds/close! stream)))))


#?(:clj
   (defn connect!
     "Connect to a remote dao.jing server.

      Args:
        url - WebSocket URL (e.g., \"ws://localhost:8080\")
        opts - optional map with:
          :timeout-ms - connection timeout (default 5000)

      Returns:
        A RemoteKVStore implementing IKVStore"
     ([url] (connect! url {}))
     ([url opts]
      (let [client (rpc-client/connect! url opts)]
        (map->RemoteKVStore client)))))
