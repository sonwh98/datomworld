(ns dao.jing.remote
  "Remote IKVStore adapter over the generic dao.stream.rpc transport.

   Server side: `default-handlers` builds the {:jing/put! ... :jing/cas! ...
   :jing/get ... :jing/delete!} handlers map from a local store and hands it
   to `dao.stream.rpc.server/start!`. Transport, dispatch, and request/
   response framing belong entirely to `dao.stream.rpc.server`, which knows
   nothing about `dao.jing`.

   Client side: `connect-kv!` returns a `RemoteKVStore` whose every
   `IKVStore` method calls `dao.stream.rpc.client/call!` against the
   matching `:jing/*` op. `RemoteKVStore` itself is transport-agnostic
   (parameterized over `call-fn`/`close-fn`); `connect-kv!` is the
   convenience that wires it to a real RPC client.

   `IKVStore`'s synchronous-return contract on every existing implementation
   (mem/file/dht included) pairs with `rpc.client/call!` directly only on
   `:clj` (it returns a Promise/Future on `:cljs`/`:cljd`). Reconciling a
   synchronous protocol with an async client on those platforms is a
   separate, unscoped problem, so `connect-kv!` stays `:clj`-only here --
   the `RemoteKVStore` record and `default-handlers` it pairs with are
   portable across clj/cljs/cljd regardless."
  (:require [dao.jing :as jing]
            #?(:clj [dao.stream.rpc.client :as rpc-client])))


(defn default-handlers
  "The default handlers map exposing a dao.jing IKVStore's 4 operations."
  [store]
  {:jing/put! (partial jing/put! store),
   :jing/cas! (partial jing/cas! store),
   :jing/get (partial jing/get store),
   :jing/delete! (partial jing/delete! store)})


(defrecord RemoteKVStore
  [call-fn close-fn]

  jing/IKVStore

  (put! [_ k v-map] (call-fn :jing/put! [k v-map]))


  (cas! [_ k old-rev v-map] (call-fn :jing/cas! [k old-rev v-map]))


  (get [_ k not-found] (call-fn :jing/get [k not-found]))


  (delete! [_ k] (call-fn :jing/delete! [k]))


  (close! [_] (close-fn)))


#?(:clj
   (defn connect-kv!
     "Connect to a remote dao.jing server and wrap it as an IKVStore.

      Args:
        url  - WebSocket URL (e.g., \"ws://localhost:8080\")
        opts - optional map passed to dao.stream.rpc.client/connect!:
                 :timeout-ms         - connection handshake timeout (default 5000)
                 :request-timeout-ms - per-call! timeout (default 5000)

      Returns:
        A RemoteKVStore implementing IKVStore. The underlying RPC client's
        own close! is idempotent, so multiple close! calls on the returned
        store are safe."
     ([url] (connect-kv! url {}))
     ([url opts]
      (let [client (rpc-client/connect! url opts)]
        (->RemoteKVStore (fn [op args] (rpc-client/call! client op args))
                         (fn [] (rpc-client/close! client)))))))


(comment
  (require '[dao.jing :as jing]
           '[dao.jing.file :as jing.file]
           '[dao.stream.rpc.server :as rpc-server])
  ;; Start once and leave the server running for multiple client
  ;; connections, including from other processes.
  (def store (jing.file/create-kv-file "target/dao/store.jing"))
  (def server (rpc-server/start! (default-handlers store) 7070))
  ;; Connect as many clients as needed.
  (def client-1 (connect-kv! "ws://localhost:7070"))
  (jing/put! client-1 :hello {:v "world"})
  (jing/get client-1 :hello nil)
  (def client-2 (connect-kv! "ws://localhost:7070"))
  (jing/get client-2 :hello nil)
  ;; Explicit cleanup when the server should stop.
  ;; (rpc-server/stop! server)
  ;; (jing/close! store)
)
