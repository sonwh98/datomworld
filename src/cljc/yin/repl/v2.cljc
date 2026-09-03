(ns yin.repl.v2
  "Host entry points for the DaoStream v2 Yin REPL.

   The one thing this namespace adds to `yin.repl.v2.driver` is cadence, which
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
            [yin.repl.v2.driver :as driver]))


(def tick-millis
  "Cadence of the single step owner.  Slow enough to cost nothing while idle,
   fast enough that a remote result prints without waiting for a keystroke."
  25)


(def prompt "yin> ")


(def serve-text
  (str "--port is not served by this slice: the v2 REPL endpoint arrives with "
       "dao.stream.v2.rpc.ws and the serving composition"))


(def telemetry-text
  (str "--telemetry and --telemetry-stream are not part of the DaoStream v2 "
       "REPL slice; run yin.repl for telemetry"))


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
   the step owner."
  ([] (boot {}))
  ([_opts] (driver/create-state)))


(defn- entry-text
  [entry]
  (get entry driver/text-key))


(defn banner
  "Text the composition prints before the first prompt, given parsed options."
  [opts]
  (cond-> []
    (seq (:rejected opts)) (conj telemetry-text)
    (:port opts) (conj serve-text)
    (and (:headless? opts) (not (:port opts)))
    (conj "--headless has nothing to attend without a served endpoint")))


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

     (defn- poll-loop!
       "The sole owner of REPL state on the JVM.  It carries the state value
        through serial `repl-step` calls; nothing is shared with the reader."
       [state]
       (loop [state state]
         (let [stepped (driver/repl-step state (System/currentTimeMillis))
               [entries state'] (driver/take-outbox stepped)]
           (doseq [entry entries]
             (println (entry-text entry)))
           (if (:running? state')
             (do (when (seq entries)
                   (print-prompt!))
                 (Thread/sleep ^long tick-millis)
                 (recur state'))
             (println)))))

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
             state (boot opts)]
         (doseq [line (banner opts)]
           (println line))
         (let [poller (Thread. ^Runnable (fn [] (poll-loop! state)))]
           (.setDaemon poller true)
           (.start poller)
           (print-prompt!)
           (read-loop! (:input state))
           (.join poller 2000))
         (.halt (Runtime/getRuntime) 0)))))


;; =============================================================================
;; cljs (Node) — a readline handler that only appends, and one interval owner
;; =============================================================================

#?(:cljs
   (do
     (defn- run-node!
       [state rl]
       ;; `repl-step` is synchronous and the Node event loop is single threaded,
       ;; so this interval callback cannot overlap itself.  The box is host
       ;; cadence plumbing: the interval is the only reader and writer of it.
       (let [box (atom state)]
         (js/setInterval
           (fn []
             (let [stepped (driver/repl-step @box (js/Date.now))
                   [entries state'] (driver/take-outbox stepped)]
               (reset! box state')
               (doseq [entry entries]
                 (js/console.log (entry-text entry)))
               (if (:running? state')
                 (when (seq entries)
                   (.prompt rl))
                 (do (.close rl)
                     (js/process.exit 0)))))
           tick-millis)))

     (defn -main
       [& args]
       (let [opts (parse-args args)
             state (boot opts)
             readline (js/require "readline")
             rl (.createInterface readline
                                  #js {:input (.-stdin js/process)
                                       :output (.-stdout js/process)
                                       :prompt prompt})]
         (doseq [line (banner opts)]
           (js/console.log line))
         (.on rl "line" (fn [line] (driver/submit-line! (:input state) line)))
         (.on rl "close" (fn [] (driver/submit-line! (:input state) "(quit)")))
         (.prompt rl)
         (run-node! state rl)))))


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
       "One `Timer.periodic` owns the state.  A synchronous poll loop would
        deadlock the Dart event loop: IO never progresses, so `blocked` never
        clears."
       [state]
       (let [box (atom state)]
         (async/Timer.periodic
           (dart-core/Duration .milliseconds tick-millis)
           (fn [^async/Timer timer]
             (let [stepped (driver/repl-step @box (.-millisecondsSinceEpoch
                                                    (dart-core/DateTime.now)))
                   [entries state'] (driver/take-outbox stepped)]
               (reset! box state')
               (doseq [entry entries]
                 (write-line! (entry-text entry)))
               (if (:running? state')
                 (when (seq entries)
                   (print-prompt!))
                 (do (.cancel timer)
                     (io/exit 0)
                     nil)))))))

     (defn -main
       [& args]
       (let [opts (parse-args args)
             state (boot opts)]
         (doseq [line (banner opts)]
           (write-line! line))
         (-> io/stdin
             (.transform (.-decoder convert/utf8))
             (.transform (convert/LineSplitter.))
             (.listen (fn [line] (driver/submit-line! (:input state) line))
                      .onDone (fn [] (driver/submit-line! (:input state) "(quit)"))))
         (print-prompt!)
         (run-dart! state)))

     (defn run-main
       [args]
       (apply -main args))))
