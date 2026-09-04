(ns yin.repl.v2.host
  "The one seam between the v2 Yin REPL and a real host WebSocket package.

   `yin.repl.v2.connect` and `yin.repl.v2.serve` are complete compositions over
   the DaoStream v2 WebSocket boundary, which deliberately knows no socket
   library: `dao.stream.v2.ws/make-attacher` asks a host for `:connect!`, and a
   listener is likewise host policy.  This namespace names what a host owes and
   answers, honestly, that no such package is wired into this seam yet — which
   is not the same as absent: `dao.stream.v2.ws.node` is a complete Node host
   adapter, and what it still owes is the glue described in `owed`.

   Nothing here fabricates a network path.  `websocket` returning nil is the
   reason `(connect …)` and `--port` report an unmet host prerequisite instead
   of appearing to work, and it is the value tests replace with a deterministic
   in-process adapter."
  (:require [clojure.string :as str]))


(def missing-code :yin.repl.v2.host/no-websocket-package)


(def missing-text
  (str "no host WebSocket package is composed for this build: the v2 REPL "
       "boundary needs {:connect! …} for (connect …) and {:bind! … :unbind! …} "
       "for --port"))


(def owed
  "What each host still owes this seam, stated per host rather than hidden
   behind one absent dependency.

   `:cljs` owes only glue: `dao.stream.v2.ws.node` already implements the host
   edge (`connect!`, `listen!` with its `:clock`, `:accept!`, `:on-listening`
   and `:on-error` options, and `stop-listening!` with its close completion).
   What is missing is a namespace that presents it as this seam's map — passing
   `serve!`'s `accept!` and turning `:on-listening`, `:on-error` and the close
   completion into `:bind-succeeded`, `:listener-error` and `:stopped`
   deposits.  The two seams are shaped to compose; nothing here does it yet."
  {:clj "http-kit client/server, or java.net.http.WebSocket plus a listener"
   :cljs "glue from dao.stream.v2.ws.node to this seam's {:connect! :bind! :unbind!}"
   :cljd "dart:io WebSocket and HttpServer, including socket-close-fn"})


(defn adapter?
  "True for a host adapter this slice can compose a client boundary from.
   `:bind!` and `:unbind!` are additionally required to serve."
  [x]
  (and (map? x) (fn? (:connect! x))))


(defn binder?
  [x]
  (and (map? x) (fn? (:bind! x)) (fn? (:unbind! x))))


(defn websocket
  "Return this build's host WebSocket adapter, or nil when none is composed.

   A conforming adapter is a map of:

   * `:connect!` — `(fn [descriptor adapter] …)`, per
     `dao.stream.v2.ws/make-attacher`: it starts connecting, never waits, and
     synchronously returns `{:send! … :close! …}`;
   * `:bind!` — `(fn [{:keys [endpoint bind-host bind-port path accept! deposit!]}] …)`,
     which binds a listener, routes each upgrade through
     `(accept! request-path socket now)` with its own clock reading, and reports
     every lifecycle fact through `deposit!` as plain data;
   * `:unbind!` — `(fn [resources deposit!] …)`, which releases the listener and
     whose close completion deposits `:stopped`."
  []
  ;; Deliberately nil.  See `owed`: no build composes a host adapter into this
  ;; seam yet, and answering with anything but nil would make it invisible.
  nil)


(defn missing-message
  [what]
  (str what ": " missing-text
       " (" (str/join "; " (map (fn [[k v]] (str (name k) " owes " v)) owed)) ")"))
