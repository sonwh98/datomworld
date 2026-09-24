(ns yin.repl.embed
  "The composition an embedding host drives to serve a Yin REPL from inside
   an application — the Flutter-free half of the Flutter REPL widget.

   No UI, no timer, no atom.  `start` composes the endpoint, `step` is the one
   state transition, and `status`/`status-text` read what a view shows.  The
   caller owns cadence and is the endpoint's only state owner; it calls `step`
   serially and only prints or displays what comes back."
  (:require [yin.repl :as repl]
            [yin.repl.host :as repl-host]
            [yin.repl.serve :as serve]))


(def default-bind-host
  "An embedded server is meant to be reached from another device on the same
   LAN, so it binds every interface.  A wildcard bind needs an advertised host."
  "0.0.0.0")


(defn start
  "Compose a served endpoint and return it immediately.

   `primitives` is a host-supplied map merged over the REPL primitives; the
   shell keeps it across `(reset)` and `(vm …)`.  `host` defaults to this
   build's WebSocket adapter."
  [{:keys [port bind-host advertised-host primitives host]}]
  (serve/serve! {:bind-port port
                 :bind-host (or bind-host default-bind-host)
                 :advertised-host advertised-host
                 :host (or host (repl-host/websocket))
                 :repl (repl/create-state {:primitives primitives})}))


(defn step
  "Advance the endpoint once at `now`.  Returns `[endpoint' lines]`."
  [endpoint now]
  (let [[entries endpoint'] (serve/take-outbox (serve/step endpoint now))]
    [endpoint' (mapv serve/text-key entries)]))


(defn status
  "`{:status … :clients n :url …}` read from the endpoint summary."
  [endpoint]
  (let [summary (serve/summary endpoint)]
    {:status (:status summary)
     :clients (count (:sessions summary))
     :url (:url summary)}))


(defn status-text
  "The status line a view shows for a `status` value."
  [{:keys [status clients]}]
  (case status
    :running (if (pos? clients) "client connected" "listening")
    (:new :starting) "starting"
    :stopping "stopping"
    :stopped "stopped"
    :failed "failed"
    "stopped"))


(defn stop
  "Initiate stop; keep stepping until `stopped?`."
  [endpoint]
  (serve/stop! endpoint))


(defn stopped?
  [endpoint]
  (serve/stopped? endpoint))
