(ns yin.repl.net-fixture
  "An in-process host for the REPL's serve, embed and main tests: the
   loopback net's `:bind!`, `:unbind!` and `:connect!` seams, recorded,
   so a real `yin.repl.connect` client attaches to a real endpoint on
   every host and nothing binds a port.  The test pumps the net."
  (:require [dao.stream.loopback-net :as net]
            [dao.stream.rpc :as rpc]
            [yin.repl.connect :as connect]))


(defn host
  "A loopback host: `:adapter` is the host assembly, `:bound` every bind
   configuration, `:released` every unbind call, `:deposit` the bound
   listener's lifecycle deposit!."
  ([] (host (net/loopback-net)))
  ([lnet]
   (let [bound (atom [])
         released (atom [])
         deposit (atom nil)
         listen (net/listen-on lnet)
         unbind (net/unbind-on lnet)]
     {:net lnet
      :bound bound
      :released released
      :deposit deposit
      :adapter {:connect! (net/connect-on lnet)
                :bind! (fn [config]
                         (swap! bound conj config)
                         (reset! deposit (:deposit! config))
                         (listen config))
                :unbind! (fn [listener deposit!]
                           (swap! released conj listener)
                           (unbind listener deposit!))}})))


(defn pump!
  "Run everything in flight on the host's net."
  [h]
  (net/pump! (:net h)))


(defn open
  "`connect/open` to `url` over the host at `now`: `{:connection atom
   :client atom}`, or nil with the failure printed by the assertion that
   uses it."
  [h url now]
  (let [result (connect/open {:url url :host (:adapter h) :now now})]
    (when (= :yin.repl.connect/attached (get result connect/outcome-key))
      {:connection (atom (get result connect/connection-key))
       :client (atom (get result connect/client-key))})))


(defn client-step!
  "Step the client's connection at `now`, then poll its RPC client once."
  [c now]
  (swap! (:connection c) connect/step! now)
  (swap! (:client c) #(:dao.stream.rpc/state (rpc/poll! %))))


(defn closed-conns
  "How many of the net's connections have closed."
  [h]
  (count (filter (comp deref :closed?) (:conns @(:net h)))))
