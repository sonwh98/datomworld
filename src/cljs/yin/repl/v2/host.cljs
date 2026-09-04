(ns yin.repl.v2.host
  "The Node composition of the v2 Yin REPL host WebSocket seam.

   This CLJS namespace shadows the portable, deliberately-uncomposed `.cljc`
   namespace.  It presents the existing Node transport edge as the three
   operations the REPL driver consumes.  Host callbacks do no interpretation:
   they classify host-only values into plain lifecycle data, deposit one fact,
   and return."
  (:require [dao.stream.v2.ws.node :as node]))


(def missing-code :yin.repl.v2.host/no-websocket-package)


(def missing-text
  (str "no host WebSocket package is composed for this build: the v2 REPL "
       "boundary needs {:connect! …} for (connect …) and {:bind! … :unbind! …} "
       "for --port"))


(defn adapter?
  [x]
  (and (map? x) (fn? (:connect! x))))


(defn binder?
  [x]
  (and (map? x) (fn? (:bind! x)) (fn? (:unbind! x))))


(defn- bound-address
  "Turn Node's host address object into portable lifecycle data."
  [address fallback-host fallback-port]
  {:host (or (some-> address .-address) fallback-host)
   :port (or (some-> address .-port) fallback-port)})


(defn- bind!
  [{:keys [endpoint bind-host bind-port accept! deposit!]}]
  ;; `ws` reports bind failures through its asynchronous error callback.  This
  ;; bit belongs to the listener resource's lifecycle and exists only to
  ;; distinguish failure-to-bind from an error after the listener was live.
  (let [bound? (volatile! false)]
    (node/listen! endpoint
                  {:host bind-host
                   :port bind-port
                   :accept! accept!
                   :on-listening
                   (fn [address]
                     (vreset! bound? true)
                     (deposit! :bind-succeeded
                               (bound-address address bind-host bind-port)))
                   :on-error
                   (fn []
                     (deposit! (if @bound? :listener-error :bind-failed)
                               {:code (if @bound?
                                        :yin.repl.v2.host/listener-error
                                        :yin.repl.v2.host/bind-failed)
                                :message (if @bound?
                                           "the Node WebSocket listener failed"
                                           "the Node WebSocket listener failed to bind")}))})))


(defn- unbind!
  [resources deposit!]
  (node/stop-listening!
    resources
    (fn []
      (deposit! :stopped {:reason :requested}))))


(defn websocket
  "Return the Node host adapter used by the v2 REPL client and server."
  []
  {:connect! node/connect!
   :bind! bind!
   :unbind! unbind!})


(defn missing-message
  [what]
  (str what ": " missing-text))
