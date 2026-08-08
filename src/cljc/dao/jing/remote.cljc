(ns dao.jing.remote
  "Remote storage adapter over dao.stream.rpc.
   Returns a handle map {:call-fn f :close-fn g} that jing/cas!, jing/get,
   jing/delete!, and jing/close! dispatch through automatically."
  (:require [dao.jing :as jing]
            #?(:clj [dao.stream.rpc.client :as rpc-client])
            #?(:clj [dao.stream.rpc.ws :as rpc-ws])))


(defn default-handlers
  "Build the RPC handlers map from a local jing handle."
  [handle]
  {:jing/cas! (fn [k expected v] (jing/cas! handle k expected v)),
   :jing/get (fn [k not-found] (jing/get handle k not-found)),
   :jing/delete! (fn [k] (jing/delete! handle k))})


#?(:clj
   (defn connect-kv!
     "Connect to a remote dao.jing server over WebSocket and return a handle
      {:call-fn f :close-fn g} that works with jing/cas!, jing/get,
      jing/delete!, and jing/close!."
     ([url] (connect-kv! url {}))
     ([url opts]
      (let [client (rpc-ws/connect! url opts)]
        {:call-fn (fn [op args] (rpc-client/call! client op args)),
         :close-fn (fn [] (rpc-client/close! client))}))))


(comment
  (require '[dao.jing.file :as jing.file] '[dao.stream.rpc.ws :as rpc-ws])
  (def store (jing.file/create-kv-file "target/dao/store.jing"))
  (def server (rpc-ws/start! (default-handlers store) 7070))
  (def client (connect-kv! "ws://localhost:7070"))
  (jing/cas! client :hello jing/absent {:v "world"})
  (jing/get client :hello nil)
  (def client-2 (connect-kv! "ws://localhost:7070"))
  (jing/get client-2 :hello nil))
