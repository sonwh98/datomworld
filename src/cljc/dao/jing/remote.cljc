(ns dao.jing.remote
  "Remote content adapter over dao.stream.rpc.
   Server side: dao.jing.remote/default-handlers exposes a local dao.jing
   content handle as the :jing/put-content and :jing/get-content RPC ops.
   Client side: dao.jing.remote/content-client wraps an RPC client as a
   dao.jing content handle map {:client c :closed-atom a :put-content-fn f
   :get-content-fn g :close-fn h} that jing/materialize!, jing/get, and
   jing/close! dispatch through automatically. The synchronous WebSocket
   constructor connect-content! is JVM-only."
  (:require [dao.jing :as jing]
            #?(:clj [dao.stream.rpc.client :as rpc-client])
            #?(:clj [dao.stream.rpc.ws :as rpc-ws])))


(defn- validate-address-payload!
  "Throw unless address is a strict segment address matching the payload's
   content-derived hash."
  [address payload]
  (when-not (jing/segment-address? address)
    (throw (ex-info "content address must be a segment address"
                    {:address address, :payload payload})))
  (when-not (= (jing/segment-hash address) (jing/content-hash payload))
    (throw (ex-info "content address does not match payload hash"
                    {:address address,
                     :content-hash (jing/content-hash payload)}))))


#_{:clj-kondo/ignore [:unused-binding]}


(defn- with-lock
  [lock f]
  #?(:clj (locking lock (f))
     :default (f)))


(defn- valid-presence-envelope?
  "True only for the exact wire envelope {:found? boolean, :value value}."
  [x]
  (and (map? x)
       (= #{:found? :value} (set (keys x)))
       (let [f (:found? x)] (or (true? f) (false? f)))))


(defn default-handlers
  "Build the RPC handler map from a local dao.jing content handle."
  [handle]
  (let [put (:put-content-fn handle)
        local-missing #?(:clj (Object.)
                         :cljs (js-obj)
                         :cljd (Object.))]
    (when-not (ifn? put)
      (throw (ex-info "handle must expose a :put-content-fn" {:handle handle})))
    {:jing/put-content
     (fn [address payload]
       (validate-address-payload! address payload)
       (let [result (put address payload)]
         (if (#{:inserted :present} result)
           result
           (throw (ex-info
                    "backend returned an invalid put result"
                    {:address address, :payload payload, :result result}))))),
     :jing/get-content (fn [address]
                         (let [result (jing/get handle address local-missing)]
                           (if (identical? result local-missing)
                             {:found? false, :value nil}
                             {:found? true, :value result})))}))


(defn content-client
  "Wrap an RPC client as a dao.jing content handle.

   call-fn is invoked as (call-fn client op args) and close-fn as
   (close-fn client). Close is guarded so concurrent JVM closes call the
   underlying close-fn exactly once; if close-fn throws, the client stays
   open for retry."
  [client call-fn close-fn]
  (when-not (ifn? call-fn)
    (throw (ex-info "call-fn must be a function" {:call-fn call-fn})))
  (when-not (ifn? close-fn)
    (throw (ex-info "close-fn must be a function" {:close-fn close-fn})))
  (let [closed-atom (atom false)
        close-lock #?(:clj (Object.)
                      :default nil)
        ensure-open (fn []
                      (when @closed-atom
                        (throw (ex-info "content client is closed"
                                        {:client client}))))]
    {:client client,
     :closed-atom closed-atom,
     :put-content-fn (fn [address payload]
                       (ensure-open)
                       (call-fn client :jing/put-content [address payload])),
     :get-content-fn
     (fn [address not-found]
       (ensure-open)
       (let [resp (call-fn client :jing/get-content [address])]
         (if (valid-presence-envelope? resp)
           (if (:found? resp) (:value resp) not-found)
           (throw
             (ex-info
               "malformed RPC response: presence envelope must contain exactly :found? (boolean) and :value keys"
               {:operation :jing/get-content,
                :address address,
                :response resp}))))),
     :close-fn (fn []
                 (with-lock close-lock
                   (fn []
                     (when-not @closed-atom
                       (close-fn client)
                       (compare-and-set! closed-atom false true)))))}))


#?(:clj
   (defn connect-content!
     "Connect to a remote dao.jing content server over WebSocket and return a
      content client handle map (see content-client)."
     ([url] (connect-content! url {}))
     ([url opts]
      (let [client (rpc-ws/connect! url opts)]
        (content-client client rpc-client/call! rpc-client/close!)))))


#?(:clj (comment
          (require '[dao.jing.file :as file])
          (def store
            (file/create-content-file "target/dao-jing-remote-demo.log"))
          (def server (rpc-ws/start! (default-handlers store) 7070))
          (def client (connect-content! "ws://localhost:7070"))
          (def address (jing/materialize! client {:hello "world"}))
          (jing/get client address nil)
          (jing/close! client)
          (rpc-ws/stop! server)
          (jing/close! store)))
