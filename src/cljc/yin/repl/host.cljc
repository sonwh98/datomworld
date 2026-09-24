(ns yin.repl.host
  "The one seam between the Yin REPL and a real host WebSocket package.

   `yin.repl.connect` and `yin.repl.serve` are complete compositions over
   the DaoStream WebSocket boundary, which deliberately knows no socket
   library: `dao.stream.ws/make-attacher` asks a host for `:connect!`, and a
   listener is likewise host policy.  Each supported build composes that host
   policy behind the same `{:connect! :bind! :unbind!}` value: this portable
   namespace selects the JVM adapter, while host-specific `.cljs` and `.cljd`
   shadows select Node and dart:io.

   A shadow replaces this namespace wholesale, so it holds only `websocket` —
   the one thing that differs per build.  The portable contract every build
   shares lives in `yin.repl.host.common`."
  (:require #?@(:cljd []
                :clj [[yin.repl.host.jvm :as jvm]]
                :default [])))


(defn websocket
  "Return this build's host WebSocket adapter.

   A conforming adapter is a map of:

   * `:connect!` — `(fn [descriptor adapter] …)`, per
     `dao.stream.ws/make-attacher`: it starts connecting, never waits, and
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
