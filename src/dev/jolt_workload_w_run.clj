(ns jolt-workload-w-run
  "Independent watchdog and sampler for the single-threaded Workload W worker.
   Run with bb workload-w:jolt [workload-w|quick-w|cross-host|served-stream|all]."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))


(def hard-cap-ms 300000)
(def telemetry-ms 10000)


(defn- now-ms
  []
  (quot (System/nanoTime) 1000000))


(defn- p99
  [samples]
  (when (seq samples)
    (nth (sort samples) (min (dec (count samples)) (int (* 0.99 (count samples)))))))


(defn- observe-line
  [state line]
  (cond
    (str/starts-with? line "W-SAMPLE ")
    (let [sample (try (edn/read-string (subs line 9)) (catch Exception _ nil))]
      (if (and (number? sample) (<= 0 sample))
        (-> state
            (update :samples conj sample)
            (assoc :last-output-at (now-ms)))
        (assoc state :sample-error line)))

    (str/starts-with? line "W-CALL ")
    (let [call (try (edn/read-string (subs line 7)) (catch Exception _ nil))
          valid? (and (map? call) (integer? (:round call)) (keyword? (:phase call))
                      (contains? #{:enter :leave} (:event call)))]
      (if-not valid?
        (assoc state :trace-error line)
        (let [state (assoc state :last-output-at (now-ms))]
          (case (:event call)
            :enter (let [call (assoc call :observed-at (now-ms))]
                     (-> state
                         (update :call-stack (fnil conj []) call)
                         (assoc :active-call call :call-entered-at (:observed-at call))))
            :leave (if (= (select-keys call [:round :phase])
                          (select-keys (:active-call state) [:round :phase]))
                     (let [stack (pop (:call-stack state))
                           active (peek stack)]
                       (assoc state :call-stack stack :active-call active
                              :call-entered-at (:observed-at active) :last-call call))
                     (assoc state :trace-error line))))))

    :else
    (do
      (println line)
      (flush)
      (cond-> (assoc state :last-output-at (now-ms))
        (str/starts-with? line "W-PHASE ") (assoc :phase (subs line 8))
        (= line "FAIL workload-w") (assoc :reported-failure? true)))))


(defn- read-output
  "Only ready characters, at most 8192 per turn: a partial line or a noisy
   worker must not block the watchdog's clock or its ten-second sampler."
  [reader state]
  (let [chunk (StringBuilder.)
        eof? (loop [remaining 8192]
               (if (and (pos? remaining) (.ready reader))
                 (let [c (.read reader)]
                   (if (= -1 c)
                     true
                     (do (.append chunk (char c))
                         (recur (dec remaining)))))
                 false))
        lines (str/split (str (:partial state) chunk) #"\n" -1)]
    (assoc (reduce observe-line state (butlast lines))
           :partial (last lines) :eof? eof?)))


(defn- telemetry!
  [state elapsed]
  (println "WATCHDOG TELEMETRY"
           (pr-str {:elapsed-ms elapsed,
                    :samples (count (:samples state)),
                    :running-p99-ms (or (p99 (:samples state)) "ABSENT"),
                    :first-round-phase (:phase state),
                    :active-call (:active-call state),
                    :active-call-age-ms (when-let [entered (:call-entered-at state)]
                                          (- (now-ms) entered)),
                    :last-call (:last-call state),
                    :output-age-ms (- (now-ms) (:last-output-at state))}))
  (flush))


(defn- failure!
  [state elapsed reason]
  (println "Workload W Results: FAILURE")
  (println "RECORD failure reason" reason)
  (println "RECORD p99 ABSENT")
  (println "RECORD sample count" (count (:samples state)))
  (println "RECORD elapsed ms" elapsed)
  (println "RECORD active call" (pr-str (:active-call state)))
  (println "FAIL workload-w")
  (flush))


(defn- kill-tree!
  [process]
  (let [root (.toHandle process)
        tree (conj (vec (iterator-seq (.iterator (.descendants root)))) root)]
    ;; Capture descendants before signaling: launcher children may be reparented.
    (doseq [handle tree]
      (.destroyForcibly handle))))


(defn- run-watched
  [args]
  (let [started (now-ms)
        command (into ["sh" "-c"
                       ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt src/dev/jolt_workload_w.clj \"$@\""
                       "jolt"] args)
        builder (ProcessBuilder. ^java.util.List command)
        _ (.put (.environment builder) "JOLT_WORKLOAD_W_SUPERVISED" "1")
        _ (.redirectError builder java.lang.ProcessBuilder$Redirect/INHERIT)
        process (.start builder)]
    (try
      (with-open [reader (java.io.BufferedReader.
                           (java.io.InputStreamReader. (.getInputStream process) "UTF-8"))]
        (loop [state {:samples [], :partial "", :phase "loading", :last-output-at started}
               next-telemetry (+ started telemetry-ms)]
          (let [state (read-output reader state)
                now (now-ms)
                elapsed (- now started)]
            (when (>= now next-telemetry)
              (telemetry! state elapsed))
            (cond
              (:sample-error state)
              (do
                (kill-tree! process)
                (failure! state elapsed :invalid-sample)
                1)

              (:trace-error state)
              (do
                (kill-tree! process)
                (failure! state elapsed :invalid-trace)
                1)

              (>= elapsed hard-cap-ms)
              (do
                (kill-tree! process)
                (failure! state elapsed :hard-cap)
                1)

              (not (.isAlive process))
              ;; Drain already-buffered output without waiting for another line.
              (if (and (not (:eof? state)) (.ready reader))
                (recur state next-telemetry)
                (let [state (if (str/blank? (:partial state))
                              state
                              (observe-line state (:partial state)))
                      status (.exitValue process)]
                  (when (and (not (zero? status)) (not (:reported-failure? state)))
                    (failure! state elapsed :worker-failure))
                  status))

              :else
              (do
                (Thread/sleep 10)
                (recur state (if (>= now next-telemetry)
                               (+ now telemetry-ms)
                               next-telemetry)))))))
      (finally
        (when (.isAlive process) (kill-tree! process))))))


(System/exit (run-watched *command-line-args*))
