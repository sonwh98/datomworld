(ns yin.repl.slice-peer
  "The cross-process, cross-host peer of the yin.repl Phase R5 slice.

   Phase R5 is deliberately two operating-system processes rather than two
   compositions in one: the REPL contract's promises — that `(connect …)`
   returns immediately, that a killed connection is reported rather than timed
   out, and that a dropped connection reattaches to the same served stream —
   are only falsifiable from a process that shares nothing with the server but
   bytes on a socket.  This namespace is a whole program, not a fixture, in
   two roles:

   * **Client role** (no arguments) — a whole REPL client.  It is driven
     exactly the way an operator drives the real REPL: every input arrives as
     a typed line appended to the composition-owned input medium, and one
     ticker is the sole owner of the driver state.  The parent observes what
     the driver publishes plus the serializable probes this program exposes
     for facts a printed line cannot carry.

   * **Server role** (`--serve <bind-host> <port>`) — a headless REPL
     endpoint composed through the real `serve!` and stepped by one ticker,
     its notices on stdout as plain lines and `{:cmd :stop}` on stdin as the
     parent's stop trigger.  This is the role the cross-host pair plays
     against a Node client: the JVM parent tests spawn the client role
     against their own in-process server, and the Node parent test spawns
     this server role so the same wire is spoken by a Dart process.

   The client-role command/reply protocol is line-oriented Transit JSON on
   stdin/stdout: `{:cmd :line :line \"…\"}` appends a typed line; `{:cmd
   :events}` drains what the driver has published since the last ask;
   `{:cmd :probe}` reports connection state; `{:cmd :quit}` exits.  Replies
   carry `{:reply <kind>}`.  The protocol is identical on every host, which is
   what lets one parent drive a JVM peer and a compiled Dart peer with the
   same code."
  (:require [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.transit :as transit]
            [yin.repl.main :as repl]
            [yin.repl.connect :as connect]
            [yin.repl.driver :as driver]
            [yin.repl.host :as host]
            [yin.repl.serve :as serve]
            ;; `:cljd` first: the ClojureDart host-eval pass also matches
            ;; `:clj`, so a `:clj` branch reached first would pull a JVM-only
            ;; namespace into the Dart build.
            #?@(:cljd [["dart:async" :as async]
                       ["dart:convert" :as convert]
                       ["dart:core" :as dart-core]
                       ["dart:io" :as io]]
                :default [])))


(def tick-millis
  "Cadence of this peer's own single step owner, in either role."
  5)


(def ^:private history
  "Append-only record of everything the driver ever published in the client
   role.  The `:events` reply is drain-and-clear, so this is the account a
   probe can audit against."
  (atom []))


(defn- take-atom!
  "Atomically exchange `a` for `empty-value`, returning what it held.  A plain
   deref-then-reset is a race: the ticker records into the window between the
   two, and the record is cleared without ever being sent."
  ([a]
   (take-atom! a []))
  ([a empty-value]
   (loop []
     (let [current @a]
       (if (compare-and-set! a current empty-value)
         current
         (recur))))))


(defn- now
  []
  #?(:cljd (.-millisecondsSinceEpoch (dart-core/DateTime.now))
     :cljs (js/Date.now)
     :clj (System/currentTimeMillis)
     :default 0))


(defn- parse-port
  [text]
  #?(:cljd (int/parse text)
     :cljs (js/parseInt text 10)
     :clj (Long/parseLong (str text))
     :default 0))


(defn- emit!
  "Write one line on stdout: a Transit reply in the client role, a plain
   notice in the server role."
  [text]
  #?(;; Dart's stdout is a sink, not a printer: one call writes the line and
     ;; its terminator, which is the whole framing rule of the protocol.
     :cljd (.writeln ^io/Stdout io/stdout text)
     :clj (do (println text) (flush))
     ;; shadow's :node-script target does not install a print-fn that is
     ;; guaranteed to reach stdout unbuffered; write the stream directly.
     :cljs (.write js/process.stdout (str text "\n"))
     :default nil))


(defn- emit-reply!
  [value]
  (emit! (transit/encode value)))


(defn- exit!
  []
  #?(:cljd (.then (.flush ^io/Stdout io/stdout) (fn [_] (io/exit 0)))
     :clj (System/exit 0)
     :cljs (.exit js/process 0)
     :default nil))


(defn- decode
  [line]
  (try (transit/decode line)
       (catch #?(:cljd Object :clj Exception :cljs :default) _
         nil)))


(defn- feed-lines!
  "Split one stdin chunk into whole lines, invoking `on-line` for each
   complete line and returning the unterminated remainder."
  [pending chunk on-line]
  (let [parts (str/split (str pending chunk) #"\n" -1)]
    (doseq [line (butlast parts)
            :when (not (str/blank? line))]
      (on-line line))
    (last parts)))


(defn- read-lines!
  "Consume stdin one line at a time, host idiomatically.  A blocking read is
   safe only on the JVM, where socket callbacks run on host threads: Node and
   Dart have one event loop, so stdin must stay a turn on it or the socket
   callbacks that deposit into the media would never run."
  [on-line on-done]
  #?(:clj (do (loop [pending ""]
                (if-let [chunk (read-line)]
                  (recur (feed-lines! pending (str chunk "\n") on-line))
                  (on-done))))
     :cljs (let [pending (atom "")]
             (.setEncoding js/process.stdin "utf8")
             (.on js/process.stdin "data"
                  (fn [chunk]
                    (reset! pending (feed-lines! @pending chunk on-line))))
             (.on js/process.stdin "end" on-done))
     :cljd (-> io/stdin
               (.transform (.-decoder convert/utf8))
               (.transform (convert/LineSplitter.))
               (.listen (fn [line] (feed-lines! "" (str line "\n") on-line))
                        .onDone on-done))
     :default nil))


;; =============================================================================
;; The client role
;; =============================================================================

(defn- record-events!
  [events entries]
  (when (seq entries)
    (let [recorded (mapv (fn [entry]
                           {:event (get entry driver/event-key)
                            :text (get entry driver/text-key)
                            :at (now)})
                         entries)]
      (swap! events into recorded)
      (swap! history into recorded))))


(defn- start-client-ticker!
  "One ticker owns the client's driver state, exactly as each REPL host's own
   cadence owner does.  Returns the stop function.  `health` is diagnostic: a
   probe can tell a live ticker from a dead one, and a step that throws is
   recorded there rather than silently ending the cadence."
  [box events health]
  (let [step! (fn []
                (swap! health update :ticks inc)
                (let [stepped (try
                                (driver/repl-step @box (now))
                                (catch #?(:cljd Object :clj Exception :cljs :default) e
                                  (swap! health assoc :error (str e))
                                  @box))
                      [entries next] (driver/take-outbox stepped)]
                  (reset! box next)
                  (record-events! events entries)))]
    #?(:clj (let [running (atom true)]
              (doto (Thread. (fn []
                               (while @running
                                 (step!)
                                 (Thread/sleep (long tick-millis))))
                             "yin-repl-slice-peer")
                (.setDaemon true)
                (.start))
              (fn [] (reset! running false)))
       :cljs (let [timer (js/setInterval step! tick-millis)]
               (fn [] (js/clearInterval timer)))
       :cljd (let [timer (async/Timer.periodic
                           (dart-core/Duration .milliseconds tick-millis)
                           (fn [_timer] (step!)))]
               (fn [] (.cancel timer)))
       :default (fn [] nil))))


(defn- record-first-traffic!
  "Remember the first connection's deposit medium, so a later probe can prove
   the same object survived the socket's death and carries the reattachment."
  [box first-traffic]
  (when (and (:connection @box) (nil? @first-traffic))
    (reset! first-traffic (:traffic (:connection @box))))
  first-traffic)


(defn- probe
  "One serializable observation of the client composition.

   `:handle-outcome` exists for Phase R5's second fact: once the boundary has
   reported a terminal status, appending through the attachment handle answers
   `:dao.stream/closed` — the client-side proof that the connection died.  A
   live connection is never probed this way, because the append would inject a
   payload frame into the protocol."
  [box first-traffic health]
  (let [connection (:connection @box)]
    {:reply :probe
     :connection (when connection (connect/summary connection))
     :outstanding (:outstanding @box)
     :health @health
     :undriven-outbox (count (:outbox @box))
     :traffic-retained?
     (boolean (when (and connection @first-traffic)
                (identical? @first-traffic (:traffic connection))))
     :traffic-events
     (when connection
       (let [h (:traffic connection)]
         (loop [c (:dao.stream/cursor (stream/cursor h stream/anchor-oldest))
                acc []]
           (let [r (stream/next h c)]
             (if (= :dao.stream/ok (:dao.stream/outcome r))
               (recur (:dao.stream/cursor r)
                      (conj acc (select-keys (:dao.stream/value r)
                                             [:ws/event :ws/attachment])))
               (vec acc))))))
     :handle-outcome
     (when (and connection
                (:handle connection)
                (contains? connect/terminal-statuses (:status connection)))
       (:dao.stream/outcome (stream/append! (:handle connection) ::closed-probe)))}))


(defn- handle-command!
  "Interpret one parent command and emit exactly one reply.  Returns false
   only for `:quit`, which stops the ticker and exits."
  [box events first-traffic health stop-ticker command]
  (case (:cmd command)
    :events (do (emit-reply! {:reply :events :events (take-atom! events)})
                true)
    :line (do (driver/submit-line! (:input @box) (:line command))
              (emit-reply! {:reply :line :accepted true})
              true)
    :probe (do (record-first-traffic! box first-traffic)
               (emit-reply! (probe box first-traffic health))
               true)
    :trace (do
             ;; Read-only: nothing here advances a cursor or steps the
             ;; composition, because the ticker is the one state owner.  A
             ;; probe may read; only the ticker may drive.
             (let [connection (:connection @box)]
               (emit-reply!
                 {:reply :trace
                  :summary (when connection (connect/summary connection))
                  :history @history
                  :lifecycle-cursor (when connection (:lifecycle-cursor connection))
                  :response-cursor (when connection (:response-cursor connection))}))
             true)
    :quit (do (stop-ticker)
              (exit!)
              false)
    (do (emit-reply! {:reply :error :command (pr-str (:cmd command))})
        true)))


(defn- client-mode!
  []
  (let [box (atom (repl/boot {}))
        events (atom [])
        first-traffic (atom nil)
        health (atom {:ticks 0 :error nil})
        stop-ticker (start-client-ticker! box events health)]
    (emit-reply! {:reply :ready :booted true})
    (read-lines!
      (fn [line]
        (when-let [command (decode line)]
          (handle-command! box events first-traffic health stop-ticker command)))
      (fn []
        (stop-ticker)
        (exit!)))))


;; =============================================================================
;; The server role
;; =============================================================================

(defn- serve-mode!
  "One headless REPL endpoint behind the real host listener.  `serve!`
   returns immediately; the bound fact arrives as a notice this program
   prints, which is the parent's readiness signal.  `{:cmd :stop}` on stdin
   initiates `stop!`; the ticker keeps stepping until the endpoint reports
   `:stopped`, and only the host close completion makes that true."
  [bind-host port]
  (let [box (atom (serve/serve! {:bind-host (str bind-host)
                                 :bind-port port
                                 :host (host/websocket)}))
        step! (fn []
                (let [stepped (serve/step @box (now))
                      [entries next] (serve/take-outbox stepped)]
                  (reset! box next)
                  (doseq [entry entries]
                    (emit! (get entry serve/text-key)))
                  (when (serve/stopped? next)
                    (exit!))))]
    #?(:clj (doto (Thread. (fn []
                             (while true
                               (step!)
                               (Thread/sleep (long tick-millis))))
                           "yin-repl-slice-server")
              (.setDaemon true)
              (.start))
       :cljs (js/setInterval step! tick-millis)
       :cljd (async/Timer.periodic
               (dart-core/Duration .milliseconds tick-millis)
               (fn [_timer] (step!)))
       :default nil)
    (read-lines!
      (fn [line]
        (when-let [command (decode line)]
          (when (= :stop (:cmd command))
            (swap! box serve/stop!))))
      exit!)))


(defn -main
  "Client role with no arguments; server role with `--serve <host> <port>`."
  [& args]
  (if (and (seq args) (= "--serve" (first args)))
    (serve-mode! (or (second args) "127.0.0.1") (parse-port (nth args 2 nil)))
    (client-mode!)))


#?(:cljd
   ;; The Dart VM starts a program at its top-level `main`; `-main` munges to
   ;; a name the VM will never look for.  This is the whole difference between
   ;; this peer as a library and as a process.
   (defn ^{:dart/name main} peer-main
     [args]
     (apply -main (vec args))))
