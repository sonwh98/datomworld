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
  (if (str/starts-with? line "W-SAMPLE ")
    (let [sample (edn/read-string (subs line 9))]
      (when-not (and (number? sample) (<= 0 sample))
        (throw (ex-info "Invalid worker sample" {:line line})))
      (-> state
          (update :samples conj sample)
          (assoc :last-output-at (now-ms))))
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
  (let [chunk (StringBuilder.)]
    (loop [remaining 8192]
      (when (and (pos? remaining) (.ready reader))
        (let [c (.read reader)]
          (when (not= -1 c)
            (.append chunk (char c))
            (recur (dec remaining))))))
    (let [lines (str/split (str (:partial state) chunk) #"\n" -1)]
      (assoc (reduce observe-line state (butlast lines)) :partial (last lines)))))


(defn- telemetry!
  [state elapsed]
  (println "WATCHDOG TELEMETRY"
           (pr-str {:elapsed-ms elapsed,
                    :samples (count (:samples state)),
                    :running-p99-ms (p99 (:samples state)),
                    :first-round-phase (:phase state),
                    :output-age-ms (- (now-ms) (:last-output-at state))}))
  (flush))


(defn- failure!
  [state elapsed reason]
  (println "Workload W Results: FAILURE")
  (println "RECORD failure reason" reason)
  (println "RECORD p99 ABSENT")
  (println "RECORD sample count" (count (:samples state)))
  (println "RECORD elapsed ms" elapsed)
  (println "FAIL workload-w")
  (flush))


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
              (>= elapsed hard-cap-ms)
              (do
                ;; Kill directly: the shell execs jolt, so no worker is left behind.
                (.destroyForcibly process)
                (failure! state elapsed :hard-cap)
                1)

              (not (.isAlive process))
              ;; Drain already-buffered output without waiting for another line.
              (if (.ready reader)
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
        (when (.isAlive process) (.destroyForcibly process))))))


(System/exit (run-watched *command-line-args*))
