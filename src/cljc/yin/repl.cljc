(ns yin.repl
  "Host entry points for the DaoStream v2 Yin REPL.

   The one thing this namespace adds to `yin.repl.driver` is cadence, which
   the DaoStream contract leaves to the runtime.  Per host, a line producer
   appends what was typed to the input medium and returns; one ticker owns the
   state value and calls `repl-step` serially.  There is no second state owner,
   and no line handler evaluates, requests, polls, or prints.

   `yin.repl` is untouched and keeps its aliases; this is a second REPL beside
   it."
  (:require #?@(:cljd [["dart:async" :as async]
                       ["dart:convert" :as convert]
                       ["dart:core" :as dart-core]
                       ["dart:io" :as io]])
            [yin.repl.driver :as driver]
            [yin.repl.host :as host]
            [yin.repl.serve :as serve]))


(def tick-millis
  "Cadence of the single step owner.  Slow enough to cost nothing while idle,
   fast enough that a remote result prints without waiting for a keystroke."
  25)


(def prompt "yin> ")


(def telemetry-text
  (str "--telemetry and --telemetry-stream are not part of the DaoStream v2 "
       "REPL slice: the v2 emit path is built (yin.vm.telemetry, opt-in via "
       "the :telemetry {:stream ...} construction option — "
       "yin.vm.telemetry.implementation-plan.md), but composing a sink into "
       "this shell is not, so the flags are rejected rather than ignored"))


(defn parse-args
  "Parse the host arguments.  `--telemetry` and `--telemetry-stream` are
   rejected rather than ignored, and the rejection names the v1 REPL."
  [args]
  (loop [args (seq args)
         opts {:headless? false :host nil :port nil :rejected []}]
    (if-let [arg (first args)]
      (case arg
        "--port" (recur (nnext args)
                        (assoc opts :port (when-let [p (second args)]
                                            #?(:cljd (int/parse p)
                                               :cljs (js/parseInt p 10)
                                               :clj (Long/parseLong p)))))
        "--host" (recur (nnext args) (assoc opts :host (second args)))
        "--headless" (recur (next args) (assoc opts :headless? true))
        "--telemetry" (recur (next args) (update opts :rejected conj arg))
        "--telemetry-stream" (recur (nnext args) (update opts :rejected conj arg))
        (recur (next args) (update opts :extra (fnil conj []) arg)))
      opts)))


(defn boot
  "Create the composition: one shell, one input medium, one cursor held only by
   the step owner, and the host WebSocket adapter `(connect …)` attaches
   through."
  ([] (boot {}))
  ([opts] (driver/create-state {:host (or (:adapter opts) (host/websocket))})))


(defn boot-server
  "Compose the served endpoint for `--port`, or nil when no port was asked for.

   `serve!` returns immediately.  Whether a listener bound is a fact the
   endpoint's lifecycle medium reports, and the ticker prints it."
  [opts]
  (when (:port opts)
    (serve/serve! {:bind-port (:port opts)
                   :bind-host (or (:host opts) serve/default-bind-host)
                   :host (or (:adapter opts) (host/websocket))})))


(defn- entry-text
  [entry]
  (or (get entry driver/text-key) (get entry serve/text-key)))


(defn banner
  "Text the composition prints before the first prompt, given parsed options."
  [opts]
  (cond-> []
    (seq (:rejected opts)) (conj telemetry-text)
    (and (:headless? opts) (not (:port opts)))
    (conj "--headless has nothing to attend without a served endpoint")))


(defn step-all
  "One tick of the single step owner: the local shell first, then the served
   endpoint — against the same shell value.  A `--port` process serves one
   shared shell, as v1's atom made it: the driver evaluates this tick's local
   lines first, so a definition typed at the local prompt is already in the
   shell the endpoint evaluates remote requests against in the same tick, and
   the endpoint's shell — remote definitions included — is threaded back before
   the next tick.  Returns `[state server lines]`; the caller only prints."
  [state server now]
  (let [stepped (driver/repl-step state now)
        [entries state'] (driver/take-outbox stepped)
        server' (when server (serve/step (assoc-in server [:repl] (:repl state'))
                                         now))
        [server-entries server''] (if server'
                                    (serve/take-outbox server')
                                    [[] nil])
        state'' (if server'' (assoc state' :repl (:repl server'')) state')]
    [state'' server'' (mapv entry-text (into (vec entries) server-entries))]))


;; =============================================================================
;; Shutdown
;; =============================================================================

(def stop-ticks
  "How many ticks a host steps a stopping endpoint before exiting anyway.  The
   endpoint's `:stopped` fact comes from the host's own close completion, which
   a wedged listener may never deposit; the shell says so and exits rather than
   hanging on it."
  200)


(def stop-timeout-text
  ";; the endpoint did not report :stopped; exiting anyway")


(def stop-join-millis
  "How long a host thread may wait for the step owner to finish shutdown.

   The drain performs `stop-ticks + 1` steps with a sleep between them, so it
   needs strictly more than `tick-millis × stop-ticks`; a join of exactly that
   expires mid-drain and the announced timeout never prints."
  (* tick-millis (+ stop-ticks 2)))


(defn request-stop!
  "The explicit stop trigger: append the shell's own quit line to the input
   medium.

   This is the whole of what a host signal handler, a headless supervisor, or a
   test does to stop the composition.  It is a line producer like any other, so
   the one step owner still performs the shutdown — `(quit)` stops the shell,
   and the shell stopping is what stops the endpoint."
  [state]
  (driver/submit-line! (:input state) "(quit)"))


(defn stop-tick
  "One shutdown tick for an endpoint that has already been asked to stop.
   Returns `[server lines stopped?]`.

   `serve/stopped?` is the whole of the exit condition: an endpoint that never
   bound is done on the first tick, because no host exists to report a
   `:stopped` fact for it."
  [server now]
  (let [server' (serve/step server now)
        [entries server''] (serve/take-outbox server')]
    [server'' (mapv entry-text entries) (serve/stopped? server'')]))


;; =============================================================================
;; clj — a reader thread that only appends, and one owned poller thread
;; =============================================================================

#?(:cljd nil
   :clj
   (do
     (defn- print-prompt!
       []
       (print prompt)
       (flush))

     (defn- drain-server!
       "The shell has quit, so the endpoint stops before the host exits: ask it
        once, then keep stepping until it reports `:stopped` or the bounded
        budget runs out.  A connected client must observe `:ws/ended`, not the
        `:ws/closed` a process exit would leave behind."
       [server]
       (when server
         (loop [server (serve/stop! server)
                remaining stop-ticks]
           (let [[server' lines stopped?] (stop-tick server
                                                     (System/currentTimeMillis))]
             (doseq [line lines]
               (println line))
             (cond
               stopped? nil
               (zero? remaining) (println stop-timeout-text)
               :else (do (Thread/sleep ^long tick-millis)
                         (recur server' (dec remaining))))))))

     (defn poll-loop!
       "The sole owner of REPL and endpoint state on the JVM.  It carries both
        values through serial steps; nothing is shared with the reader.

        It also owns the exit.  The reader is parked in `read-line` and cannot
        observe that the shell has quit, so a typed `(quit)` would otherwise
        stop the endpoint and then wait for end-of-input that only Ctrl-D
        produces.  `exit!` is called once, by this thread, after the endpoint
        has drained."
       ([state server headless?]
        (poll-loop! state server headless? #(.halt (Runtime/getRuntime) 0)))
       ([state server headless? exit!]
        (loop [state state
               server server]
          (let [[state' server' lines] (step-all state server
                                                 (System/currentTimeMillis))]
            (doseq [line lines]
              (println line))
            (if (:running? state')
              (do (when (and (seq lines) (not headless?))
                    (print-prompt!))
                  (Thread/sleep ^long tick-millis)
                  (recur state' server'))
              (do (drain-server! server')
                  (println)
                  (exit!)))))))

     (defn- read-loop!
       "The reader parks in `read-line` and appends.  It reads no state."
       [input]
       (loop []
         (if-let [line (read-line)]
           (do (driver/submit-line! input line)
               (recur))
           (driver/submit-line! input "(quit)"))))

     (defn -main
       [& args]
       (let [opts (parse-args args)
             state (boot opts)
             server (boot-server opts)
             headless? (boolean (:headless? opts))]
         (doseq [line (banner opts)]
           (println line))
         (let [poller (Thread. ^Runnable (fn [] (poll-loop! state server headless?)))]
           (.setDaemon poller true)
           (.start poller)
           ;; Headless attends the endpoint only: there is no reader, so the
           ;; step owner is joined until it stops, and the explicit stop trigger
           ;; is the host signal a shutdown hook observes.  The hook appends a
           ;; line like any producer and then waits for the one step owner.
           (if headless?
             (do (.addShutdownHook
                   (Runtime/getRuntime)
                   (Thread. ^Runnable (fn []
                                        (request-stop! state)
                                        (.join poller ^long stop-join-millis))))
                 (.join poller))
             (do (print-prompt!)
                 ;; End-of-input is one way to stop; a typed `(quit)` is the
                 ;; other, and the step owner has already exited the process by
                 ;; the time this join is reached in that case.
                 (read-loop! (:input state))
                 (.join poller ^long stop-join-millis))))
         (.halt (Runtime/getRuntime) 0)))))


;; =============================================================================
;; cljs (Node) — a readline handler that only appends, and one interval owner
;; =============================================================================

#?(:cljs
   (do
     (defn- run-node!
       [state server rl]
       ;; `repl-step` is synchronous and the Node event loop is single threaded,
       ;; so this interval callback cannot overlap itself.  The box is host
       ;; cadence plumbing: the interval is the only reader and writer of it.
       ;; `:stopping` is nil while the shell runs and a tick budget afterwards:
       ;; the endpoint is asked to stop once and stepped until it reports it.
       (let [box (atom {:state state :server server :stopping nil})
             timer (atom nil)
             finish! (fn []
                       (js/clearInterval @timer)
                       (when rl (.close rl))
                       (js/process.exit 0))]
         (reset! timer
                 (js/setInterval
                   (fn []
                     (let [{:keys [state server stopping]} @box]
                       (if (nil? stopping)
                         (let [[state' server' lines] (step-all state server
                                                                (js/Date.now))]
                           (reset! box {:state state' :server server' :stopping nil})
                           (doseq [line lines]
                             (js/console.log line))
                           (cond
                             (:running? state')
                             (when (and (seq lines) rl) (.prompt rl))

                             server'
                             (swap! box assoc
                                    :server (serve/stop! server')
                                    :stopping stop-ticks)

                             :else (finish!)))
                         (let [[server' lines stopped?] (stop-tick server
                                                                   (js/Date.now))]
                           (doseq [line lines]
                             (js/console.log line))
                           (cond
                             stopped? (finish!)
                             (zero? stopping) (do (js/console.log stop-timeout-text)
                                                  (finish!))
                             :else (swap! box assoc
                                          :server server'
                                          :stopping (dec stopping)))))))
                   tick-millis))))

     (defn -main
       [& args]
       (let [opts (parse-args args)
             state (boot opts)
             server (boot-server opts)
             readline (when-not (:headless? opts) (js/require "readline"))
             rl (when readline
                  (.createInterface readline
                                    #js {:input (.-stdin js/process)
                                         :output (.-stdout js/process)
                                         :prompt prompt}))]
         (doseq [line (banner opts)]
           (js/console.log line))
         (if rl
           (do (.on rl "line" (fn [line] (driver/submit-line! (:input state) line)))
               (.on rl "close" (fn [] (driver/submit-line! (:input state) "(quit)")))
               (.prompt rl))
           ;; Headless has no reader, so the explicit stop trigger is the host
           ;; signal: it appends a line and returns, like any producer.
           (doseq [signal ["SIGINT" "SIGTERM"]]
             (.on js/process signal (fn [] (request-stop! state)))))
         (run-node! state server rl)))))


;; =============================================================================
;; cljd — a stdin listener that only appends, and one periodic timer owner
;; =============================================================================

#?(:cljd
   (do
     (defn- write-line!
       [message]
       (println message))

     (defn- print-prompt!
       []
       (.write io/stdout prompt)
       (.flush io/stdout))

     (defn- run-dart!
       "One `Timer.periodic` owns both values.  A synchronous poll loop would
        deadlock the Dart event loop: IO never progresses, so `blocked` never
        clears.

        `:stopping` is nil while the shell runs and a tick budget afterwards:
        the endpoint is asked to stop once and stepped until it reports it, so
        the host does not exit with a live listener."
       [state server headless?]
       (let [box (atom {:state state :server server :stopping nil})]
         (async/Timer.periodic
           (dart-core/Duration .milliseconds tick-millis)
           (fn [^async/Timer timer]
             (let [{:keys [state server stopping]} @box
                   now (.-millisecondsSinceEpoch (dart-core/DateTime.now))
                   finish! (fn []
                             (.cancel timer)
                             (io/exit 0)
                             nil)]
               (if (nil? stopping)
                 (let [[state' server' lines] (step-all state server now)]
                   (reset! box {:state state' :server server' :stopping nil})
                   (doseq [line lines]
                     (write-line! line))
                   (cond
                     (:running? state')
                     (when (and (seq lines) (not headless?))
                       (print-prompt!))

                     server'
                     (do (swap! box assoc
                                :server (serve/stop! server')
                                :stopping stop-ticks)
                         nil)

                     :else (finish!)))
                 (let [[server' lines stopped?] (stop-tick server now)]
                   (doseq [line lines]
                     (write-line! line))
                   (cond
                     stopped? (finish!)
                     (zero? stopping) (do (write-line! stop-timeout-text)
                                          (finish!))
                     :else (do (swap! box assoc
                                      :server server'
                                      :stopping (dec stopping))
                               nil)))))))))

     (defn -main
       [& args]
       (let [opts (parse-args args)
             state (boot opts)
             server (boot-server opts)
             headless? (boolean (:headless? opts))]
         (doseq [line (banner opts)]
           (write-line! line))
         (if headless?
           ;; Headless has no reader, so the explicit stop trigger is the host
           ;; signal: it appends a line and returns, like any producer.
           (-> (.watch io/ProcessSignal.sigint)
               (.listen (fn [_signal] (request-stop! state))))
           (do (-> io/stdin
                   (.transform (.-decoder convert/utf8))
                   (.transform (convert/LineSplitter.))
                   (.listen (fn [line] (driver/submit-line! (:input state) line))
                            .onDone (fn [] (driver/submit-line! (:input state) "(quit)"))))
               (print-prompt!)))
         (run-dart! state server headless?)))

     (defn ^{:dart/name main} run-main
       [args]
       (apply -main args))))
