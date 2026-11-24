(ns quick-sharded
  "Quick JVM execution using affected's unchanged selection and subsystem rules."
  (:require
    [affected :as a]
    [babashka.fs :as fs]
    [babashka.process :as p]
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str]))


(defn changed-paths
  "Validate explicit/deleted paths just as affected does; otherwise use git."
  [index {:keys [changed base]}]
  (let [git-root (str/trim (:out (p/sh "git" "rev-parse" "--show-toplevel")))
        _ (when-not (= git-root (str (fs/canonicalize ".")))
            (throw (ex-info "run from the repo root" {})))
        paths (if (some? changed)
                (mapv #(a/normalize-path git-root %) changed)
                (a/changed-files base))
        base (or base (str/trim (:out (p/sh "git" "merge-base" "HEAD" "master"))))
        errors (concat
                 (when (some nil? paths) ["changed path outside the repo"])
                 (a/input-errors
                   {:index index :changed (remove nil? paths)
                    :explicit? (some? changed)
                    :exists? #(or (fs/exists? %)
                                  (zero? (:exit (p/sh "git" "cat-file" "-e"
                                                      (str base ":" %)))))}))]
    (when (seq errors) (throw (ex-info (str/join "\n" errors) {})))
    (vec paths)))


(defn shard-plan
  [index cfg nses]
  (let [selected (set nses)
        shards (into (sorted-map)
                     (for [{:keys [name]} (:subsystems cfg)
                           :let [owned (set/intersection
                                         selected (a/select-subsystems index cfg [name]))]
                           :when (seq owned)]
                       [name (vec (sort owned))]))
        ;; Like the shiva script's explicit uncovered shard: new namespaces
        ;; absent from the registry still run. Never silently drop them.
        residual (set/difference selected (set (mapcat val shards)))]
    (cond-> shards
      (seq residual) (assoc "unmapped" (vec (sort residual))))))


(defn coverage
  [selected shards]
  (let [assigned (mapcat val shards)
        covered (set assigned)]
    {:selected (count (set selected)) :covered (count covered)
     :uncovered (vec (sort (set/difference (set selected) covered)))
     :unexpected (vec (sort (set/difference covered (set selected))))
     :duplicates (vec (sort (keep (fn [[n c]] (when (> c 1) n))
                                  (frequencies assigned))))}))


(defn check-coverage!
  [selected shards out]
  (fs/create-dirs out)
  (let [{:keys [uncovered unexpected duplicates] :as result} (coverage selected shards)
        write-nses #(spit (str out "/" %1 ".txt")
                          (apply str (map (fn [n] (str n "\n")) %2)))]
    (write-nses "selected" selected)
    (write-nses "covered" (sort (set (mapcat val shards))))
    (write-nses "uncovered" uncovered)
    (doseq [[shard nses] shards] (write-nses (str "ns-" shard) nses))
    (println (format "Coverage: selected=%d covered=%d uncovered=%d unexpected=%d duplicates=%d"
                     (:selected result) (:covered result) (count uncovered)
                     (count unexpected) (count duplicates)))
    (when (some seq [uncovered unexpected duplicates])
      (throw (ex-info (str "FAIL: shard coverage mismatch " (pr-str result)) result)))
    result))


(def php-e2e 'yang.php.antlr.e2e-test)


(def heavy-quick-vars
  '[yang.php.antlr.e2e-test/permutation-corpus-quick-test
    yang.php.antlr.e2e-test/phpt-selection-runs-test])


(defn var-shards-enabled?
  "The sole authorized namespace is enabled by default; 0 disables it."
  []
  (let [setting (System/getenv "QUICK_VAR_SHARDS")]
    (cond
      (or (nil? setting) (= setting "1") (= setting (str php-e2e))) true
      (or (= setting "0") (= setting "")) false
      :else (throw (ex-info "QUICK_VAR_SHARDS permits only yang.php.antlr.e2e-test or 0"
                            {:value setting})))))


(defn quick-var-inventory!
  [out]
  (let [file (str out "/quick-php-vars.edn")
        log (str out "/quick-php-vars.log")
        expression (str "(require '" php-e2e ") "
                        "(spit " (pr-str file) " (pr-str (sort (for [[s v] (ns-publics '"
                        php-e2e ") :when (and (:test (meta v)) (not (:slow (meta v))))] "
                        "(symbol (str '" php-e2e ") (str s))))))")
        alias (pr-str {:aliases {:quick-var-inventory {:main-opts ["-e" expression]}}})
        rc (:exit @(p/process ["clojure" "-Sdeps" alias "-M:test:quick-var-inventory"]
                              {:out (fs/file log) :err :out}))]
    (when-not (zero? rc)
      (throw (ex-info "Quick var discovery failed; see log" {:exit rc :log log})))
    (edn/read-string (slurp file))))


(defn execution-plan
  "Replace only PHP e2e with three disjoint var groups; other commands stay intact."
  [shards inventory]
  (if (nil? inventory)
    shards
    (let [all (set inventory)
          heavy (set heavy-quick-vars)
          remaining (vec (remove heavy inventory))
          groups (concat (map vector heavy-quick-vars) [remaining])
          assigned (mapcat identity groups)]
      (when-not (and (contains? (set (get shards "yang.php")) php-e2e)
                     (= (count inventory) (count all))
                     (set/subset? heavy all) (seq remaining)
                     (= all (set assigned)) (= (count assigned) (count all)))
        (throw (ex-info "Quick PHP var coverage mismatch" {:inventory inventory})))
      (-> shards
          (update "yang.php" #(vec (remove #{php-e2e} %)))
          (assoc "yang.php-e2e-aggregate" {:nses [php-e2e] :vars (first groups)})
          (assoc "yang.php-e2e-phpt" {:nses [php-e2e] :vars (second groups)})
          (assoc "yang.php-e2e-rest" {:nses [php-e2e] :vars remaining})))))


(defn shard-command
  [entry]
  (let [{:keys [nses vars]} (if (map? entry) entry {:nses entry})]
    (into (into ["clojure" "-M:test" "-e" ":slow"]
                (mapcat #(vector "-n" (str %)) nses))
          (mapcat #(vector "-v" (str %)) vars))))


(defn jvm-summary
  "Only a complete final runner summary counts; exit 0 alone is insufficient."
  [text]
  (when-let [[_ tests assertions failures errors]
             (last (re-seq #"(?m)^Ran (\d+) tests containing (\d+) assertions\.\r?\n(\d+) failures, (\d+) errors\."
                           text))]
    (zipmap [:tests :assertions :failures :errors]
            (map parse-long [tests assertions failures errors]))))


(defn reconcile-counts
  "Compare completed shard summaries with the selected metadata inventory."
  [expected rows]
  (let [missing (mapv :shard (filter #(nil? (:tests %)) rows))
        actual (reduce + (keep :tests rows))
        deficit (max 0 (- expected actual))]
    {:expected expected :actual actual :deficit deficit :missing missing
     :pass? (and (empty? missing) (zero? deficit))}))


(defn quick-inventory!
  [out]
  (let [manifest (str out "/slow-inventory.edn")
        quick (str out "/quick-vars.txt")
        log (str out "/inventory.log")
        alias "{:aliases {:slow-inventory {:extra-paths [\"src/dev\"] :main-opts [\"-m\" \"slow-inventory\"]}}}"
        exit (:exit @(p/process ["clojure" "-Sdeps" alias "-M:test:slow-inventory"
                                 manifest quick (str out "/selected.txt")]
                                {:out (fs/file log) :err :out}))]
    (when-not (zero? exit)
      (throw (ex-info (str "JVM quick inventory failed; see " log) {:exit exit})))
    (count (str/split-lines (slurp quick)))))


(defn run-shard!
  [out [shard entry]]
  (let [nses (if (map? entry) (:nses entry) entry)
        start (System/nanoTime)
        log (str out "/" shard ".log")
        cmd (shard-command entry)
        exit (:exit @(p/process cmd {:out (fs/file log) :err :out}))
        summary (jvm-summary (slurp log))
        row (merge {:shard shard :namespaces (count nses) :exit exit
                    :seconds (/ (- (System/nanoTime) start) 1e9)
                    :pass? (and (zero? exit) (some? summary)
                                (zero? (+ (:failures summary) (:errors summary))))}
                   summary)]
    (println (format "%s: exit=%d tests=%s assertions=%s failures=%s errors=%s %.3fs %s (log %s)"
                     shard exit (:tests row) (:assertions row) (:failures row)
                     (:errors row) (:seconds row) (if (:pass? row) "pass" "FAIL") log))
    row))


(defn run-shards!
  [out shards workers]
  ;; Each worker owns a disjoint sequence; no more than N child JVMs.
  (let [ordered (sort-by (fn [[_ entry]]
                           (if (map? entry) -1000 (- (count entry)))) shards)
        jobs (mapv (fn [worker]
                     (future
                       (mapv #(run-shard! out %)
                             (take-nth workers (drop worker ordered)))))
                   (range (min workers (count shards))))]
    (vec (mapcat deref jobs))))


(defn parse-args
  [args]
  (loop [opts {:workers 4 :list? false} [arg & more] args]
    (case arg
      nil opts
      ("--list" "--dry-run") (recur (assoc opts :list? true) more)
      "--workers" (let [n (parse-long (or (first more) ""))]
                    (when-not (and n (pos? n))
                      (throw (ex-info "--workers needs a positive integer" {})))
                    (recur (assoc opts :workers n) (rest more)))
      "--base" (do (when (or (empty? more) (str/starts-with? (first more) "--"))
                     (throw (ex-info "--base needs a ref" {})))
                   (recur (assoc opts :base (first more)) (rest more)))
      "--changed" (let [[files flags] (split-with #(not (str/starts-with? % "--")) more)]
                    (when (empty? files)
                      (throw (ex-info "--changed needs at least one file" {})))
                    (recur (assoc opts :changed (vec files)) flags))
      (throw (ex-info (str "unknown option: " arg) {})))))


(defn verification-scope
  "Exit zero may mean feedback or a plan; only eligible executed gates complete."
  [runner scope exit dry? details]
  (merge {:type :verification-scope :runner runner :scope scope
          :exit exit :dry-run? (boolean dry?)
          :gate-complete? (boolean (and (zero? exit) (not dry?)
                                        (#{:docs-whitespace :selected-quick-tri-host
                                           :full-quick-tri-host} scope)
                                        (empty? (:unchecked details))))}
         details))


(defn print-scope!
  [record]
  (prn record)
  (:exit record))


(defn- execute-quick!
  [{:keys [workers list?] :as opts}]
  (let [start (System/nanoTime)
        index (a/scan-index)
        cfg (or (a/load-subsystems-config)
                (throw (ex-info "missing src/dev/subsystems.edn" {})))
        selection (if (or (:changed opts) (:base opts))
                    (a/select index (changed-paths index opts))
                    {:tests (into #{} (comp (filter :test?) (map :ns)) index)})
        nses (a/lane-tests index (:tests selection) :clj)
        shards (shard-plan index cfg nses)
        ;; Separate invocations never overwrite one another's evidence.
        out (str "target/profile/quick-sharded-" (System/currentTimeMillis)
                 "-" (subs (str (random-uuid)) 0 8))
        cov (check-coverage! nses shards out)
        prereqs (a/prerequisites index {:clj nses})
        _ (when (and (seq nses) (not list?))
            (doseq [task (concat (when (prereqs :build:yin-repl-node) ["deps:npm"])
                                 (keep #(when (prereqs %) (name %))
                                       [:build:yin-repl-peer :build:yin-repl-node :gen:antlr]))]
              (println "Prerequisite" task)
              (let [exit (:exit @(p/process ["bb" task] {:inherit true}))]
                (when-not (zero? exit)
                  (throw (ex-info (str "prerequisite failed: " task) {:exit exit}))))))
        inventory (when (and (contains? (set nses) php-e2e) (var-shards-enabled?))
                    (quick-var-inventory! out))
        schedule (execution-plan shards inventory)]
    (println "Quick JVM only; workers=" workers "; logs:" out)
    (when (seq (:wide selection))
      (println "Wide selection: this runs the full JVM quick lane; G3 still requires bb test."))
    (doseq [[shard entry] schedule]
      (let [nses (if (map? entry) (:nses entry) entry)]
        (println (str "Shard " shard " : " (count nses) " namespaces"
                      (when (map? entry) (str " " (count (:vars entry)) " vars")))))
      (println "Command" shard (pr-str (shard-command entry))))
    (when inventory
      (println "PHP quick var coverage:" (count inventory) "selected,"
               (count (set (mapcat :vars (filter map? (vals schedule))))) "covered"))
    (when (contains? shards "unmapped")
      (println "Registry has unowned namespaces; running them explicitly in shard unmapped:"
               (str/join ", " (get shards "unmapped"))))
    (println "Prerequisites:" (str/join ", " (sort (map name prereqs))))
    (println "G2 also requires the selected Node/Dart lanes; G3 bb test and applicable G4 remain mandatory.")
    (cond
      (empty? nses)
      (do (println "REFUSED: 0 JVM namespaces selected; no JVM feedback executed.")
          (print-scope! (verification-scope :quick-sharded :jvm-feedback 1 list?
                                            {:selected 0})))

      list?
      (print-scope! (verification-scope :quick-sharded :jvm-feedback 0 true
                                        {:selected (count nses)}))

      :else
      ;; Merge resolution (dc0c803c-era branches): r4's hoisted prerequisites and
      ;; `schedule` execution plan (var-split shards) + the discovery-filter branch's
      ;; quick-var inventory and post-run count reconciliation (silent truncation
      ;; becomes a loud lane failure).
      (let [expected (quick-inventory! out)
            lane-start (System/nanoTime)
            rows (run-shards! out schedule workers)
            reconciliation (reconcile-counts expected rows)
            _ (when-not (:pass? reconciliation)
                (println "FAIL: test count reconciliation deficit=" (:deficit reconciliation)
                         "expected=" expected "executed=" (:actual reconciliation)
                         "missing summaries=" (pr-str (:missing reconciliation))))
            summary (merge (into {} (for [k [:tests :assertions :failures :errors]]
                                      [k (reduce + (keep k rows))]))
                           {:coverage cov :shards rows :reconciliation reconciliation
                            :pass? (and (seq rows) (every? :pass? rows) (:pass? reconciliation))
                            :jvm-seconds (/ (- (System/nanoTime) lane-start) 1e9)
                            :total-seconds (/ (- (System/nanoTime) start) 1e9)})
            scope (verification-scope :quick-sharded :jvm-feedback
                                      (if (:pass? summary) 0 1) false
                                      {:selected (count nses)})
            summary (assoc summary :verification-scope scope)]
        (spit (str out "/summary.edn") (pr-str summary))
        (println (format "TOTAL: tests=%d assertions=%d failures=%d errors=%d JVM=%.3fs total=%.3fs %s"
                         (:tests summary) (:assertions summary) (:failures summary)
                         (:errors summary) (:jvm-seconds summary) (:total-seconds summary)
                         (if (:pass? summary) "pass" "FAIL (see shard logs; missing summaries also fail)")))
        (print-scope! scope)))))


(defn run-quick!
  [opts]
  (try
    (execute-quick! opts)
    (catch Exception ex
      (print-scope! (verification-scope :quick-sharded :jvm-feedback 1 (:list? opts) {}))
      (throw ex))))


(defn -main
  [& args]
  (try (System/exit (run-quick! (parse-args args)))
       (catch Exception ex
         (binding [*out* *err*] (println (ex-message ex)))
         (System/exit 1))))


(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
