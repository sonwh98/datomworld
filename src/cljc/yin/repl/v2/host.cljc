(ns yin.repl.v2.host
  "The one seam between the v2 Yin REPL and a real host WebSocket package.

   `yin.repl.v2.connect` and `yin.repl.v2.serve` are complete compositions over
   the DaoStream v2 WebSocket boundary, which deliberately knows no socket
   library: `dao.stream.v2.ws/make-attacher` asks a host for `:connect!`, and a
   listener is likewise host policy.  Each supported build composes that host
   policy behind the same `{:connect! :bind! :unbind!}` value: this portable
   namespace selects the JVM adapter, while host-specific `.cljs` and `.cljd`
   shadows select Node and dart:io."
  (:require #?@(:cljd []
                :clj [[yin.repl.v2.host.jvm :as jvm]]
                :default [])))


(def missing-code :yin.repl.v2.host/no-websocket-package)


(def missing-text
  (str "no host WebSocket package is composed for this build: the v2 REPL "
       "boundary needs {:connect! …} for (connect …) and {:bind! … :unbind! …} "
       "for --port"))


(defn adapter?
  "True for a host adapter this slice can compose a client boundary from.
   `:bind!` and `:unbind!` are additionally required to serve."
  [x]
  (and (map? x) (fn? (:connect! x))))


(defn binder?
  [x]
  (and (map? x) (fn? (:bind! x)) (fn? (:unbind! x))))


(defn websocket
  "Return this build's host WebSocket adapter.

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
  #?(:cljd nil
     :clj (jvm/websocket)
     :default nil))


(defn missing-message
  [what]
  (str what ": " missing-text))
