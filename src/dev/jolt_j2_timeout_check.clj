;; Self-check of the J2 lane driver's watchdog (src/dev/jolt_j2_lane.clj
;; run-watched / kill-tree!), from the repository root:
;;
;;   bb src/dev/jolt_j2_timeout_check.clj [scratch-dir]
;;
;; Two cases, each under a 5-second deadline; scratch-dir (default
;; j2-scratch/timeout-check) holds every log and summary, never build/j2-logs.
;;
;; tree: a fake child shell that ignores SIGTERM, with a grandchild shell
;;   that ignores SIGTERM too, both sleeping; run-watched must answer
;;   :timed-out and leave neither process alive (SIGTERM, then SIGKILL).
;;
;; lane: the real core mode on one namespace with the runner swapped for
;;   src/dev/jolt_j2_sleep_child.clj (a jolt process that sleeps 600 s);
;;   the lane must exit nonzero within the deadline plus the kill grace,
;;   record the step with :status :timeout in core-summary.edn, and leave
;;   no sleep-child process behind.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])


(load-file "src/dev/jolt_j2_lane.clj")


(def scratch (str (fs/absolutize (or (first *command-line-args*) "j2-scratch/timeout-check"))))


(defn- alive?
  [pid]
  (zero? (:exit (p/shell {:continue true :err :string} "kill" "-0" (str pid)))))


(defn- check-tree
  []
  (let [pids (str scratch "/tree.pids")
        _ (fs/delete-if-exists pids)
        script (str "trap '' TERM; echo $$ >> " pids "; "
                    "sh -c 'trap \"\" TERM; echo $$ >> " pids "; "
                    "while :; do sleep 1; done' & "
                    "while :; do sleep 1; done")
        t0 (System/currentTimeMillis)
        r ((resolve 'jolt-j2-lane/run-watched) ["sh" "-c" script] 5000)
        ms (- (System/currentTimeMillis) t0)
        ids (mapv parse-long (str/split-lines (slurp pids)))
        live (filterv alive? ids)
        ok? (and (:timed-out r) (= 2 (count ids)) (empty? live) (< ms 20000))]
    (println (if ok? "PASS" "FAIL") "tree:"
             (pr-str {:result r :elapsed-ms ms :pids ids :alive-after live}))
    ok?))


(defn- sleep-children
  []
  (->> (:out (p/shell {:out :string :continue true} "ps" "-eo" "pid,args"))
       str/split-lines
       ;; the jolt launcher and the scheme it execs, not any other process
       ;; whose command line merely mentions the file (an invoking shell)
       (filter #(and (str/includes? % "jolt_j2_sleep_child")
                     (str/includes? % "-M:jolt:jolt-test")))
       vec))


(defn- check-lane
  "The lane case at `timeout-ms`; `started?` also requires the child to have
   printed its start line (jolt booted and is in the sleep when killed)."
  [timeout-ms started?]
  (let [logs (str scratch "/lane-" timeout-ms)
        _ (fs/delete-tree logs)
        done (promise)
        ;; the sleep-child processes seen while the lane runs
        seen (future (loop [acc #{}]
                       (if (realized? done)
                         acc
                         (do (Thread/sleep 250)
                             (recur (into acc (sleep-children)))))))
        t0 (System/currentTimeMillis)
        r (p/shell {:continue true
                    :extra-env {"JOLT_J2_LOGS" logs
                                "JOLT_J2_CORE_RUNNER" "src/dev/jolt_j2_sleep_child.clj"}}
                   "bb" "src/dev/jolt_j2_lane.clj" "core"
                   "--only" "^dao\\.host\\.bytes-test$" "--timeout-ms" (str timeout-ms))
        ms (- (System/currentTimeMillis) t0)
        _ (deliver done true)
        seen @seen
        summary (edn/read-string (slurp (str logs "/core-summary.edn")))
        step (first (:results summary))
        step-log (slurp (:log step))
        left (sleep-children)
        ok? (and (pos? (:exit r)) (= :timeout (:status step))
                 (= 1 (get-in summary [:summary :timeouts]))
                 (seq seen)
                 (or (not started?) (str/includes? step-log "SLEEP-CHILD started"))
                 (not (str/includes? step-log "woke up"))
                 (empty? left) (< ms (+ timeout-ms 20000)))]
    (println (if ok? "PASS" "FAIL") (str "lane " timeout-ms " ms:")
             (pr-str {:lane-exit (:exit r) :elapsed-ms ms
                      :step (select-keys step [:ns :status :exit :timed-out :survivors :counts])
                      :summary (:summary summary)
                      :child-log step-log
                      :sleep-children-seen-while-running (vec seen)
                      :sleep-children-left left}))
    ok?))


(fs/create-dirs scratch)


(let [results [(check-tree) (check-lane 5000 false) (check-lane 30000 true)]
      ok? (every? true? (map boolean results))]
  (println (if ok? "TIMEOUT-CHECK PASS" "TIMEOUT-CHECK FAIL"))
  (System/exit (if ok? 0 1)))
