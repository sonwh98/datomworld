(ns jolt-shell-session
  "Foreground process transcripts, one corpus and two host lanes. A held
   stdin pipe proves typed quit without EOF. Every wait has a deadline;
   mismatches fail, including unlisted reader differences. Raw output and
   timings remain under build/j3-logs/shell. Not a deployed sidecar."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))


(def prompt "yin> ")
(def timeout-ms 180000)


(defn now
  []
  (System/currentTimeMillis))


(defn fail!
  [message data]
  (throw (ex-info message data)))


(defn await!
  [pred process deadline description]
  (loop []
    (cond
      (pred) true
      (not (.isAlive process)) (fail! (str "process ended waiting for " description) {})
      (> (now) deadline) (fail! (str "deadline waiting for " description) {})
      :else (do (Thread/sleep 5) (recur)))))


(defn expected
  [host value]
  (if (and (map? value) (contains? value host)) (get value host) value))


(defn matches?
  [host wanted actual]
  (let [wanted (expected host wanted)]
    (if (and (map? wanted) (:re wanted))
      (boolean (re-matches (re-pattern (:re wanted)) actual))
      (= wanted actual))))


(defn normalized
  [text]
  (-> text (str/replace #"\r\n" "\n")
      (str/replace #"Host: (JVM|jolt) [^\n]*" "<host>") str/trim))


(defn command
  [host args]
  (case host
    :jolt (into ["sh" "-c"
                 ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt:jolt-yin-repl \"$@\""
                 "jolt"] args)
    :jvm (into ["clj" "-M:clj-yin-repl" "--no-state"] args)))


(defn run-session!
  [host {:keys [id args in out quit exit startup-only idle-ms] :as case}]
  (let [t0 (now)
        process (.start (ProcessBuilder. ^java.util.List (command host args)))
        stdout (atom "") stderr (atom "")
        capture (fn [stream sink]
                  (future
                    (with-open [r (java.io.InputStreamReader. stream "UTF-8")]
                      (loop []
                        (let [c (.read r)]
                          (when (not= -1 c)
                            (swap! sink str (char c)) (recur)))))))
        out-reader (capture (.getInputStream process) stdout)
        err-reader (capture (.getErrorStream process) stderr)
        writer (java.io.OutputStreamWriter. (.getOutputStream process) "UTF-8")
        send! (fn [line] (.write writer (str line "\n")) (.flush writer))
        path (str "build/j3-logs/shell/" (name host) "-" (name id))
        first-prompt (atom nil) latencies (atom [])]
    (try
      (when-not startup-only
        (await! #(str/includes? @stdout prompt) process (+ t0 timeout-ms) "first prompt")
        (reset! first-prompt (- (now) t0))
        (println "PROMPT" (name host) id @first-prompt "ms")
        (flush)
        (when (= host :jolt)
          (when-not (and (str/includes? @stdout "Host: jolt ")
                         (str/includes? @stdout " / Chez ")
                         (str/includes? @stdout "python: not installed on this host until J5"))
            (fail! "missing jolt banner" {:stdout @stdout})))
        (when idle-ms (Thread/sleep idle-ms))
        (doseq [[i line] (map-indexed vector in)]
          (let [offset (count @stdout) sent (now)]
            (send! line)
            (await! #(str/includes? (subs @stdout offset) prompt)
                    process (+ (now) timeout-ms) (str "response " i))
            (swap! latencies conj (- (now) sent))
            (let [actual (normalized (str/replace (subs @stdout offset) prompt ""))
                  wanted (nth out i)]
              (when-not (matches? host wanted actual)
                (fail! "transcript mismatch" {:id id :line line :expected (expected host wanted)
                                              :actual actual})))
            (when (and idle-ms (zero? i))
              (let [before @stdout]
                (Thread/sleep idle-ms)
                (when-not (= before @stdout)
                  (fail! "duplicate output while pending/idle" {:id id :before before :after @stdout}))))))
        (if (= quit :eof) (.close writer) (send! "(quit)")))
      (when-not (.waitFor process timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (fail! "deadline waiting for exit (stdin stays open for typed quit)" {:id id}))
      @out-reader @err-reader
      (when-not (= exit (.exitValue process))
        (fail! "exit mismatch" {:id id :expected exit :actual (.exitValue process)
                                :stderr @stderr :stdout @stdout}))
      (if startup-only
        (when-not (matches? host out (normalized @stdout))
          (fail! "startup transcript mismatch" {:id id :expected out :actual (normalized @stdout)}))
        (when-not (str/ends-with? (str/trim @stdout) "Bye")
          (fail! "missing clean quit" {:id id :stdout @stdout})))
      {:host host :id id :status :pass :first-prompt-ms @first-prompt
       :started-at (str (java.time.Instant/ofEpochMilli t0))
       :elapsed-ms (- (now) t0) :submission-ms @latencies :exit (.exitValue process)
       :jolt-divergence (:jolt-divergence case) :capability (:capability case)}
      (finally
        (when (.isAlive process) (.destroyForcibly process))
        (spit (str path ".out") @stdout)
        (spit (str path ".err") @stderr)))))


(defn corpus
  []
  (mapcat (fn [case]
            (if-let [vms (:vms case)]
              (for [vm vms]
                (-> case (assoc :id (keyword (str (name (:id case)) "-" (name vm))))
                    (assoc :args ["--vm" (name vm)])))
              [case]))
          (edn/read-string (slurp "test/resources/yin/repl/transcripts/local.edn"))))


(defn demo!
  [host]
  (let [cmd (case host
              :jolt ["sh" "-c" ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt -m yin.demo"]
              :jvm ["clj" "-M" "-m" "yin.demo"])
        t0 (now) r @(apply p/process {:out :string :err :string :shutdown nil} cmd)]
    (spit (str "build/j3-logs/demo-" (name host) ".log") (str (:out r) (:err r)))
    (when-not (and (zero? (:exit r))
                   (str/includes? (:out r) "SUCCESS: sum(0..100) = 5050"))
      (fail! "demo failed" {:host host :result (select-keys r [:exit :out :err :cmd])}))
    {:host host :elapsed-ms (- (now) t0) :exit (:exit r)}))


(defn probe!
  [ns-name]
  (let [cmd ["sh" "-c"
             ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt src/dev/jolt_j2_probe.clj \"$@\""
             "jolt" ns-name]
        t0 (now)
        proc (apply p/process {:out :string :err :string :shutdown nil} cmd)
        r (deref proc timeout-ms ::timeout)]
    (when (= ::timeout r)
      (p/destroy-tree proc)
      (fail! "tracked-root probe timeout" {:ns ns-name}))
    (spit (str "build/j3-logs/probe-" ns-name ".log") (str (:out r) (:err r)))
    (when-not (and (zero? (:exit r))
                   (str/includes? (:out r) (str "PROBE " ns-name " load-ok"))
                   (str/includes? (:out r) "TRANSIT absent"))
      (fail! "tracked-root load failed" {:ns ns-name :exit (:exit r) :out (:out r) :err (:err r)}))
    {:ns ns-name :status :pass :elapsed-ms (- (now) t0)
     :probe (second (re-find #"(?m)^(PROBE .*load-ok .*?)$" (:out r)))
     :transit :absent :roots ["src/jolt" "src/cljc" "src/clj"]}))


(let [args *command-line-args*
      host (some-> (second (drop-while #(not= "--host" %) args)) keyword)
      only (second (drop-while #(not= "--only" %) args))
      probe? (some #{"--probe"} args)
      hosts (if probe? [:jolt] (if (and host (not= :all host)) [host] [:jvm :jolt]))
      demo? (some #{"--demo"} args)]
  (fs/create-dirs "build/j3-logs/shell")
  (let [results (for [h hosts
                      case (cond probe? (mapv #(hash-map :id (keyword %))
                                              ["yin.repl" "yin.repl.dht" "yin.repl.main" "yin.repl.frontends" "yin.repl.host"])
                                 demo? [{:id :demo}]
                                 :else (corpus))
                      :when (and (or (nil? only) (re-find (re-pattern only) (name (:id case))))
                                 (or (nil? (:hosts case)) (some #{h} (:hosts case))))]
                  (try
                    (let [r (cond probe? (probe! (name (:id case)))
                                  demo? (demo! h)
                                  :else (run-session! h case))]
                      (println "PASS" (name h) (:id case) (pr-str r)) (flush) r)
                    (catch Exception e
                      (let [r {:host h :id (:id case) :status :fail
                               :message (ex-message e) :detail (ex-data e)}]
                        (println "FAIL" (pr-str r)) (flush) r))))
        results (vec results)
        path (str "build/j3-logs/" (cond probe? "probe" demo? "demo" :else "shell") "-" (or (some-> host name) "all") "-summary.edn")]
    (spit path (pr-str results))
    (shutdown-agents)
    (System/exit (if (some #(= :fail (:status %)) results) 1 0))))
