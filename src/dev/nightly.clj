(ns nightly
  "The nightly suite driver — ONE script for every host.

   The lane assignment is an argument, not a different script: shiva and
   jupiter run the same file from the same commit, so a fix to the harness
   never has to be copied between hosts. (Before 2026-11-01 each host had its
   own hand-written ~/ci/nightly-*.sh; they drifted — jupiter's bypassed mise
   entirely and pinned its PATH to `latest` symlinks, so babashka and flutter
   resolved to different versions on the two hosts.)

   Usage:
     bb src/dev/nightly.clj <clone-dir> <lanes> [parallel] [--now]
                               [--pin-publish | --pin-read]

     lanes       comma-separated subset of: jvm,node,dart
     parallel    per-lane parallelism (default: 12)

   Per-clone lanes (2026-11-03, kimi-reviewed): when more than one lane is
   requested, the driver fans the lanes out CONCURRENTLY, each in its own
   clone — jvm keeps <clone-dir>, node gets <clone-dir>-node, dart gets
   <clone-dir>-dart. One clone per lane deletes the shared-build-state race
   class (build:yin-repl-peer, npm ci vs readers, gen:antlr vs cljd compile)
   by construction instead of gating lane starts. The SHA is resolved once
   and checked out in every lane clone, and a cache-warm prep pass
   (clojure -P -M:test: ~/.m2 + ~/.gitlibs) runs in the primary before
   fan-out so the lanes never fetch into the shared caches concurrently.
   Per-lane summaries are appended serially after the futures join.
     --list-slow [clone-dir]  discover and print slow commands without running them
     NIGHTLY_SLOW_VAR_SHARDS=1 opts in to the measured namespace set; a
                     comma-separated namespace list selects a custom set.
                     Unset or blank keeps whole-namespace scheduling.
     --now       ignore the 04:00 Vietnam window (cron fires hourly)
     --pin-publish   resolve origin/master, publish {run-id, sha, ts} for the
                     other host (the pin authority; shiva)
     --pin-read      check out the pinned SHA, refusing a pin whose Vietnam
                     date is not today (advisory + nonzero exit otherwise)
     --pin-peer H    with --pin-read: the host holding the pin (the authority,
                     e.g. shiva.stigmergy.systems). Without it the pin is read
                     from this host's own file, which only the publisher writes.

   Exit: 0 all lanes green; 1 any red, missing, or infrastructure failure.

   Toolchain: mise owns it. `mise install` runs first so a pin change in
   mise.toml actually reaches the host — the step whose absence let the two
   hosts drift (build-n-test.md documents this order)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]))


(load-file (str (fs/parent (fs/absolutize *file*)) "/lane_timing.clj"))
(require '[lane-timing :as timing])

(def home (System/getProperty "user.home"))
(def results-dir (or (System/getenv "NIGHTLY_RESULTS") (str home "/ci/nightly-results")))
(def pin-file (str home "/ci/nightly-pin.txt"))


(def child-path
  (str home "/.local/share/mise/shims:/usr/local/bin:/usr/bin:/bin:"
       (System/getenv "PATH")))


(defn- psh
  "p/shell with the mise shims on the child PATH (never replaces the env)."
  [opts & cmd]
  (apply p/shell (update opts :extra-env #(merge {"PATH" child-path} %)) cmd))


(defn- run-cmd!
  "Run cmd to a fresh log with stdout/stderr merged; return the exit code."
  [dir out-file cmd]
  (:exit (apply psh {:dir dir
                     :out (io/file out-file)
                     :err :out
                     :continue true}
                cmd)))


(defn- vn-date
  []
  (str/trim (:out (psh {:out :string :extra-env {"TZ" "Asia/Ho_Chi_Minh"}}
                       "date" "+%Y%m%d"))))


(defn- vn-hour
  []
  (str/trim (:out (psh {:out :string :extra-env {"TZ" "Asia/Ho_Chi_Minh"}}
                       "date" "+%H"))))


(defn- git-out
  [dir & args]
  (str/trim (:out (apply psh {:dir dir :out :string :err :string :continue true} "git" args))))


(defn- slow-manifest
  "Load JVM test vars and record slow counts; fail the lane if inventory fails."
  [clone out]
  (let [manifest (str out "/jvm-slow-manifest.edn")
        rc (run-cmd! clone (str out "/jvm-slow-discovery.log")
                     ["clojure" "-Sdeps"
                      "{:aliases {:slow-inventory {:extra-paths [\"src/dev\"] :main-opts [\"-m\" \"slow-inventory\"]}}}"
                      "-M:test:slow-inventory" manifest])]
    (when-not (zero? rc)
      (throw (ex-info "JVM slow discovery failed" {:rc rc})))
    (edn/read-string (slurp manifest))))


(def ^:private default-var-shards
  '#{yang.php.antlr.e2e-test yang.typescript.antlr.corpus-test
     yang.php.antlr.include-test yang.python.antlr.e2e-test
     yin.vm.semantic.safepoint-harness-test})


(defn- var-shard-namespaces
  []
  (let [setting (System/getenv "NIGHTLY_SLOW_VAR_SHARDS")]
    (cond
      (or (nil? setting) (str/blank? setting)) #{}
      (= "1" setting) default-var-shards
      :else (set (map (comp symbol str/trim) (str/split setting #","))))))


;; JVM namespace wall seconds from Jupiter 20261123-0018-slow-runs.txt.
;; PHP e2e uses the 29,887s observed lower bound at audit time (audit
;; 1795470000000), rather than the later terminal row. Red rows are estimates:
;; failures may have shortened the work. Unknown/new namespaces use 500s.
;; Refresh from NDJSON once 10fccf2e nightly timing data exists; per-var
;; durations and repeated JVM setup costs are not measured by this table.
(def ^:private slow-weights
  {"dao.test-slow" 8
   "dao.star.driver-test" 15
   "yang.clojure.stream-eval-test" 33
   "dao.space.dht-test" 48
   "yang.python.antlr.dht-import-test" 134
   "yang.python.antlr.float-address-test" 40
   "yang.python.antlr.float-text-test" 8
   "yang.php.antlr.generators-test" 358
   "yang.python.antlr.int-contract-test" 36
   ;; Newly discovered metadata-style slow vars were absent from the receipt.
   "yang.call.exception-contract-test" 500
   "yang.python.antlr.exception-call-test" 500
   "yang.call.exception-source-test" 746
   "yang.python.antlr.e2e-c1-test" 768
   "yang.python.antlr.int-heap-test" 60
   "yang.python.antlr.int-literal-test" 27
   "yang.call.python-exception-test" 931
   "yang.python.antlr.c3-gate-test" 995
   "yang.python.antlr.c3-gate-parser-test" 1005
   "yang.python.antlr.prelude-parity-test" 136
   "yang.python.antlr.linked-prelude-test" 316
   "yang.python.antlr.int-conv-test" 908
   "yang.python.antlr.publication-test" 176
   "yang.python.antlr.import-test" 1187
   "yang.python.antlr.linked-safepoint-test" 412
   "yang.typescript.antlr.class-prelude-test" 112
   "yang.python.antlr.int-ops-test" 703
   "yang.python.antlr.int-ops-parser-test" 717
   "yang.python.antlr.siteset-test" 349
   "yang.typescript.antlr.linked-prelude-test" 298
   "yang.typescript.antlr.prelude-parity-test" 85
   "yang.typescript.antlr.prelude-test" 52
   "yang.typescript.antlr.safepoint-test" 270
   "yang.python.antlr.e2e-c2-test" 1995
   "yang.typescript.antlr.string-forms-test" 49
   "yin.repl.ast-query-e2e-test" 38
   "yang.typescript.antlr.lower-test" 575
   "yin.repl.dht-head-test" 56
   "yang.python.antlr.safepoint-test" 904
   "yin.repl.dht-test" 101
   "yin.repl.main-test" 104
   "yin.repl.query-test" 84
   "yin.vm.linker.cross-host-transfer-test" 69
   "yin.vm.linker.dht-end-to-end-test" 30
   "yin.vm.linker-test" 83
   "yang.call.php-exception-test" 2501
   "yang.typescript.antlr.source-call-test" 761
   "yin.vm.ucf.compose-rows-test" 13
   "yin.vm.ucf.holder.driver-test" 20
   "yin.vm.ucf.rows-test" 91
   "yin.vm.semantic.derive-test" 362
   "yin.vm.semantic.scan-test" 183
   "yin.vm.ucf.stage-e-rows-test" 51
   "yin.repl.dht-process-test" 738
   "yang.python.antlr.e2e-test" 3109
   "yin.vm.ucf.stage-e-process-test" 698
   "yin.repl.python-antlr-test" 1457
   "yang.typescript.antlr.object-e2e-test" 2271
   "yang.typescript.antlr.e2e-test" 2754
   "yin.vm.semantic.safepoint-harness-test" 5078
   "yang.php.antlr.include-test" 9130
   "yang.typescript.antlr.corpus-test" 8959
   "yang.php.antlr.e2e-test" 29887})

(defn- slow-weight
  [manifest {:keys [ns vars]}]
  (let [whole (get slow-weights ns 500)]
    (if (seq vars)
      (* whole (/ (count vars) (get manifest (symbol ns))))
      whole)))

(defn- slow-schedule
  [clone out manifest]
  (let [configured (disj (var-shard-namespaces) 'yang.call.exception-contract-test)
        eligible (into (sorted-map) (filter (fn [[n c]] (and (configured n) (> c 1)))) manifest)
        inventory (str out "/jvm-slow-vars.edn")
        expression (str "(let [nses '" (pr-str (vec (keys eligible))) "] "
                        "(doseq [n nses] (require n)) "
                        "(spit " (pr-str inventory) " (pr-str (into {} (for [n nses] "
                        "[n (sort (for [[s v] (ns-publics n) "
                        ":when (and (:test (meta v)) (:slow (meta v)))] "
                        "(symbol (str n) (str s))))])))))")
        alias (pr-str {:aliases {:nightly-var-inventory {:main-opts ["-e" expression]}}})
        rc (if (seq eligible)
             (run-cmd! clone (str out "/jvm-slow-vars.log")
                       ["clojure" "-Sdeps" alias "-M:test:nightly-var-inventory"])
             0)
        vars (if (seq eligible)
               (do (when-not (zero? rc)
                     (throw (ex-info "JVM slow var discovery failed" {:rc rc})))
                   (edn/read-string (slurp inventory)))
               {})]
    (doseq [[n expected-count] eligible]
      (when-not (= expected-count (count (get vars n)))
        (throw (ex-info "JVM slow var inventory differs from manifest" {:namespace n}))))
    (->> manifest
         (mapcat (fn [[n _]]
                   (if-let [vs (seq (get vars n))]
                     (let [k (min 4 (count vs))]
                       (mapv (fn [i]
                               {:ns (str n) :shard (inc i)
                                :vars (mapv str (take-nth k (drop i vs)))})
                             (range k)))
                     [{:ns (str n)}])))
         (sort-by (fn [{:keys [ns shard] :as item}]
                    [(- (slow-weight manifest item)) ns (or shard 0)]))
         vec)))


(defn- slow-command
  [{:keys [ns vars]}]
  (into ["clojure" "-M:test" "-i" :slow "-n" ns]
        (mapcat (fn [v] ["-v" v]) vars)))


(defn- run-pool
  "Run f over items with at most n in flight; return {item exit}."
  [n f items]
  (let [queue (atom (vec items))
        take-one (fn []
                   (locking queue
                     (let [c @queue]
                       (when (seq c)
                         (reset! queue (subvec c 1))
                         (first c)))))
        results (atom {})]
    (->> (range (min n (count items)))
         (mapv (fn [i]
                 (future
                   (loop []
                     (if-let [x (take-one)]
                       (do (swap! results assoc x (f i x)) (recur))
                       i)))))
         (mapv deref))
    @results))


(defn- assigned-subsystems
  "The subsystem subset this host runs, from NIGHTLY_SUBSYSTEMS (comma list).
   nil = run everything (single-host mode)."
  []
  ;; Registry names keep their dots (test:sub:clj matches against
  ;; subsystems.edn's dotted names). Only folder names get dash-normalized.
  (some->> (str/split (or (System/getenv "NIGHTLY_SUBSYSTEMS") "") #",")
           (map str/trim)
           (remove str/blank?)
           seq))


(defn- in-subset?
  "True when a test namespace belongs to one of the assigned subsystem
   prefixes (dots normalized to dashes for comparison)."
  [nss prefixes]
  (some #(str/starts-with? nss %) prefixes))


(defn- log-lane
  [summary msg]
  (println msg)
  (spit summary (str msg "\n") :append true))


(defn- ensure-lane-clone!
  "Guarantee clone-dir exists at exactly sha (per-clone lane fan-out).
   A missing clone is created from the primary's origin URL; an existing one
   fetches. The sha is always checked out detached — never trust a previous
   HEAD, and a failed checkout is a failure: a lane on stale HEAD must not
   report ready. Returns 0 or the first failed git step's exit code."
  [primary clone-dir sha log]
  (when-not (fs/exists? (str clone-dir "/.git"))
    (let [url (git-out primary "remote" "get-url" "origin")
          rc (:exit (psh {:dir home :out :string :err :string :continue true}
                         "git" "clone" "-q" url clone-dir))]
      (if (zero? rc)
        (log (str "lane clone created: " clone-dir))
        (throw (ex-info (str "lane clone failed: " clone-dir) {:rc rc})))))
  (let [frc (:exit (psh {:dir clone-dir :continue true} "git" "fetch" "origin"))
        crc (:exit (psh {:dir clone-dir :continue true}
                        "git" "checkout" "-q" "--detach" sha))
        ;; a fresh lane clone is untrusted; the first bb shim call inside it
        ;; refuses until trusted (measured on shiva 2026-11-05: both lanes
        ;; died in 0s on exactly this)
        trc (if (fs/which "mise")
              (:exit (psh {:dir clone-dir :continue true} "mise" "trust"))
              0)]
    (when-not (zero? frc) (log (str "FAIL: fetch in " clone-dir)))
    (when-not (zero? crc) (log (str "FAIL: checkout " sha " in " clone-dir)))
    (when-not (zero? trc) (log (str "FAIL: mise trust in " clone-dir)))
    (if (zero? frc) (max crc trc) frc)))


(defn- lane-clones
  "Per-clone lane fan-out map: {lane clone-path}. Single-lane runs always
   keep the primary clone — the fan-out exists to isolate lanes from each
   other, and one lane has nothing to be isolated from. Multi-lane runs
   give each lane its own tree — jvm keeps the primary, node and dart get
   suffixed clones — so their build states never share a directory."
  [primary lanes]
  (if (= 1 (count lanes))
    (zipmap lanes (repeat primary))
    (into {}
          (map (fn [lane]
                 [lane (case lane
                         "jvm" primary
                         (str primary "-" lane))]))
          lanes)))


(defn slow-reconciliation
  "Check each slow child has a summary and the pool ran its manifest count."
  [manifest rows]
  (let [expected (reduce + (vals manifest))
        missing (mapv :item (filter #(nil? (:tests %)) rows))
        actual (reduce + (keep :tests rows))
        deficit (max 0 (- expected actual))]
    {:expected expected :actual actual :deficit deficit :missing missing
     :pass? (and (empty? missing) (zero? deficit))}))


(defn- slow-summary
  [text]
  (when-let [[_ tests] (last (re-seq #"(?m)^Ran (\d+) tests containing \d+ assertions\.\r?\n\d+ failures, \d+ errors\." text))]
    (parse-long tests)))


(defn run-slow-job!
  [clone out record-lock worker {:keys [ns shard vars] :as item}]
  (let [log (str out "/slow-" ns (when shard (str "-shard-" shard)) ".log")
        start (timing/now)
        exit (run-cmd! clone log (slow-command item))
        end (timing/now)]
    (timing/append! record-lock (str out "/runs-ndjson.txt")
                    (timing/record {:job-id (str ns (when shard (str "-shard-" shard)))
                                    :namespaces [ns] :vars (or vars [])
                                    :worker-id (str "jvm-" worker) :timing-scope "job"}
                                   start end exit))
    {:item item :exit exit :tests (slow-summary (slurp log))}))


(defn- lane-jvm
  [clone par out summary]
  (let [subset (assigned-subsystems)
        ;; prerequisites the bare-metal hosts got from prior rounds: the
        ;; antlr-generated parser classes, the Node REPL, and the Jolt
        ;; toolchain and native libraries for the DHT process tests. A fresh
        ;; checkout has none.
        ;; Fail here with the prerequisite's log rather than letting a child
        ;; REPL exit before the test can communicate with it.
        _ (doseq [[nm cmd] [["gen:antlr" ["bb" "gen:antlr"]]
                            ["build:yin-repl-node" ["bb" "build:yin-repl-node"]]
                            ["bootstrap:jolt" ["bb" "bootstrap:jolt"]]
                            ["build:jolt-native" ["bb" "build:jolt-native"]]]
                  :let [t0 (System/currentTimeMillis)
                        exit (run-cmd! clone (str out "/" nm ".log") cmd)]]
            (log-lane summary (str nm " rc=" exit
                                   " secs=" (quot (- (System/currentTimeMillis) t0) 1000)))
            (when-not (zero? exit) (throw (ex-info (str nm " failed") {}))))
        manifest (slow-manifest clone out)
        selected (into (sorted-map)
                       (filter (fn [[n _]]
                                 (or (nil? subset)
                                     (in-subset? (str n) subset))))
                       manifest)
        _ (log-lane summary (str "jvm-slow manifest " (pr-str selected)))
        slow-nss (map str (keys selected))
        schedule (slow-schedule clone out selected)
        _ (log-lane summary (str "jvm-slow schedule " (pr-str schedule)))
        ;; quick tier: subsystem-sharded on the full set; per-subsystem
        ;; test:sub:clj runs pooled when a disjoint subset is assigned
        quick-log (str out "/jvm-quick.log")
        quick (if subset
                (let [results (run-pool par
                                        (fn [_ s]
                                          (run-cmd! clone
                                                    (str out "/jvm-quick-" s ".log")
                                                    ["bb" "test:sub:clj" s]))
                                        subset)]
                  (reduce + (vals results)))
                (run-cmd! clone quick-log
                          ["bb" "test:quick-sharded"]))
        record-lock (Object.)
        slow (run-pool par
                       (fn [worker item] (run-slow-job! clone out record-lock worker item))
                       schedule)
        slow-red (count (remove #(zero? (:exit %)) (vals slow)))
        reconciliation (slow-reconciliation selected (vals slow))
        _ (when-not (:pass? reconciliation)
            (log-lane summary (str "FAIL: jvm-slow test count reconciliation deficit="
                                   (:deficit reconciliation) " expected=" (:expected reconciliation)
                                   " executed=" (:actual reconciliation)
                                   " missing summaries=" (pr-str (:missing reconciliation)))))]
    (spit summary (str "jvm-quick rc=" quick
                       (when subset (str " (subset: " (str/join "," (sort subset)) ")"))
                       "\njvm-slow namespaces=" (count slow-nss)
                       " red=" slow-red "\n")
          :append true)
    (when-not (zero? quick) (throw (ex-info "jvm quick tier red" {:rc quick})))
    (when (or (pos? slow-red) (not (:pass? reconciliation)))
      (throw (ex-info "jvm slow tier red" {:red slow-red :reconciliation reconciliation})))))


(defn- lane-node
  [clone par out summary]
  (let [rc (run-cmd! clone (str out "/node-shard.log")
                     ["bb" "src/dev/node_shard.clj" clone (str par)])]
    (spit summary (str "node-shard rc=" rc "\n") :append true)
    (when-not (zero? rc) (throw (ex-info "node sharded lane red" {:rc rc})))))


(defn- lane-dart
  [clone par out summary]
  (let [rc (run-cmd! clone (str out "/dart-shard.log")
                     ["bb" "src/dev/dart_shard.clj" clone (str par)])]
    (spit summary (str "dart-shard rc=" rc "\n") :append true)
    (when-not (zero? rc) (throw (ex-info "dart sharded lane red" {:rc rc})))))


(defn -main
  [& args]
  ;; NB: never pad args with (repeat nil) — destructuring `& rest` off an
  ;; infinite lazy seq makes (remove nil? rest) loop forever (2026-11-01).
  (let [positional (take-while #(not (str/starts-with? % "--")) args)
        flag-args (drop (count positional) args)
        flags (set (filter #(str/starts-with? % "--") flag-args))
        pin-peer (some (fn [[a b]] (when (= a "--pin-peer") b))
                       (partition 2 1 flag-args))
        pin-sha (some (fn [[a b]] (when (= a "--pin-sha") b))
                      (partition 2 1 flag-args))
        [clone lanes par] positional
        clone (or clone (str home "/ci/datomworld"))
        lanes (set (str/split (or lanes "jvm,node,dart") #","))
        par (or (some->> par parse-long) 12)
        now? (or (contains? flags "--now") (= "1" (System/getenv "NOW")))
        publish? (contains? flags "--pin-publish")
        read-pin? (contains? flags "--pin-read")
        stamp (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmm")
                       (java.time.LocalDateTime/now))
        out (str results-dir "/" stamp)
        summary (str out "/summary.txt")
        rc (atom 0)
        log (fn [s] (println s) (spit summary (str s "\n") :append true))]
    ;; Owner ruling 2026-11-05: the full suite runs once a day, 04:00 Vietnam
    ;; time, as a disjoint lane pair: jupiter the jvm lane, shiva the node and
    ;; dart lanes (shiva publishes the pin, jupiter reads it). Everything else
    ;; (the pre-merge gate) runs the affected tests only.
    (when-not (or now? (= "04" (vn-hour)))
      (println "outside the 04:00 Vietnam window; pass --now to force")
      (System/exit 0))
    (fs/create-dirs out)
    ;; mise owns the toolchain on the hosts; the nightly container ships the
    ;; same pinned tools on PATH instead, so mise steps are conditional.
    (when (fs/which "mise")
      (when-not (= 0 (:exit (psh {:dir clone :continue true} "mise" "trust")))
        (println "mise trust failed"))
      (let [ti (run-cmd! clone (str out "/mise-install.log") ["mise" "install"])]
        (log (str "mise-install rc=" ti))
        (when-not (zero? ti) (log "FAIL: toolchain install"))))
    ;; pin protocol
    (when-not (zero? (:exit (psh {:dir clone :continue true} "git" "fetch" "origin")))
      (log "FAIL: git fetch"))
    (let [sha (or pin-sha (git-out clone "rev-parse" "origin/master"))
          ;; the sha every lane (including the lane clones) must run at; the
          ;; pin-read branch below rebinds it to the validated pin's sha, so
          ;; a multi-lane pin-reader never splits lanes across commits
          run-sha (atom sha)]
      (cond
        publish?
        (let [line (str "run-id=" (vn-date) "-" (vn-hour) " sha=" sha " ts="
                        (str/trim (:out (psh {:out :string} "date" "-u" "+%Y-%m-%dT%H:%M:%SZ"))))]
          (spit (str pin-file ".tmp") line)
          (fs/move (str pin-file ".tmp") pin-file {:replace-existing true})
          (log (str "pin published: " line))
          (psh {:dir clone :continue true} "git" "checkout" "-q" "--detach" sha))
        read-pin?
        (let [;; R4 fix (codex re-review): the reader requires the pin whose
              ;; run-id carries TODAY's VN date AND the window hour it is
              ;; currently inside (04). Yesterday's pin, or the morning
              ;; pin read at a later slot, is refused — no cross-slot pairing.
              slot (vn-hour)
              line (loop [tries 15]
                     (let [l (if pin-peer
                               (str/trim (:out (psh {:out :string :continue true}
                                                    "ssh" "-o" "BatchMode=yes"
                                                    "-o" "ConnectTimeout=8"
                                                    pin-peer "cat" pin-file)))
                               (when (fs/exists? pin-file) (str/trim (slurp pin-file))))
                           prid (some->> l (re-find #"run-id=(\S+)") second)]
                       (if (and l prid (= (subs prid 0 8) (vn-date))
                                (= (subs prid 9 11) slot))
                         l
                         (when (pos? tries)
                           (Thread/sleep 12000)
                           (recur (dec tries))))))]
          (when (nil? line) (log (str "no pin for the " slot ":00 " (vn-date) " slot after 3 min")))
          (let [prid (some->> line (re-find #"run-id=(\S+)") second)
                psha (some->> line (re-find #"sha=([0-9a-f]{7,40})") second)]
            (if (and psha (= (subs prid 0 8) (vn-date))
                     (= (subs prid 9 11) slot)
                     (zero? (:exit (psh {:dir clone :continue true} "git" "cat-file" "-e" psha))))
              (do (psh {:dir clone :continue true} "git" "checkout" "-q" "--detach" psha)
                  (reset! run-sha psha)
                  (log (str "pin read: " psha " (run-id " prid ")")))
              (do (reset! rc 1)
                  (log (str "FAIL: pin absent/stale/unverifiable (" (or prid "none") ") — advisory run at " sha))
                  (psh {:dir clone :continue true} "git" "checkout" "-q" "--detach" sha)))))
        :else (psh {:dir clone :continue true} "git" "checkout" "-q" "--detach" sha))
      (log (str "nightly " stamp " at " (git-out clone "log" "--oneline" "-1")
                " lanes=" (str/join "," (sort lanes)) " par=" par))
      ;; ---- per-clone fan-out ----
      (let [multi? (> (count lanes) 1)
            clones (lane-clones clone lanes)
            lane-summary (fn [lane] (str out "/summary-" lane ".txt"))
            ;; Warm the shared per-host caches (m2, gitlibs) from the primary
            ;; BEFORE the lanes run, across every alias a lane will resolve:
            ;; :test (jvm), :cljs (node/shadow), :clojuredart:cljd (dart). Once
            ;; two clones resolve deps in parallel, a cold shared-cache fetch
            ;; is the one race per-clone cannot remove.
            _ (when multi?
                (let [t0 (System/currentTimeMillis)
                      wrc (run-cmd! clone (str out "/cache-warm.log")
                                    ["clojure" "-P" "-M:test:cljs:clojuredart:cljd"])]
                  (log (str "cache-warm rc=" wrc
                            " secs=" (quot (- (System/currentTimeMillis) t0) 1000)))
                  (when-not (zero? wrc)
                    (reset! rc 1)
                    (log "FAIL: cache-warm"))))
            ;; every lane clone rides the exact sha resolved once above
            _ (doseq [[lane lc] (sort-by key clones) :when (not= lc clone)]
                (try
                  (let [crc (ensure-lane-clone! clone lc @run-sha log)]
                    (log (str "lane-clone " lane " rc=" crc " (" lc " @ " @run-sha ")"))
                    (when-not (zero? crc) (reset! rc 1)))
                  (catch Exception e
                    (reset! rc 1)
                    (log (str "FAIL lane-clone " lane ": [" (some-> e class .getSimpleName)
                              "] " (ex-message e))))))
            run-lane (fn [lane]
                       (let [t0 (System/currentTimeMillis)]
                         (try
                           ((case lane
                              "jvm" lane-jvm
                              "node" lane-node
                              "dart" lane-dart)
                            (clones lane) par out (lane-summary lane))
                           {:lane lane :lane-rc 0
                            :secs (quot (- (System/currentTimeMillis) t0) 1000)}
                           (catch Exception e
                             {:lane lane :lane-rc 1
                              :secs (quot (- (System/currentTimeMillis) t0) 1000)
                              :fail (str "[" (some-> e class .getSimpleName) "] "
                                         (ex-message e))}))))
            ordered-lanes (filter clones ["jvm" "node" "dart"])
            results (if multi?
                      (->> ordered-lanes
                           (mapv (fn [l] (future (run-lane l))))
                           (mapv deref))
                      (mapv run-lane ordered-lanes))]
        ;; summary appends are serialized HERE, after the join — concurrent
        ;; spit :append from lane futures is not one atomic syscall
        (doseq [{:keys [lane secs fail]} results]
          (let [ls (lane-summary lane)]
            (when (fs/exists? ls)
              (spit summary (slurp ls) :append true)))
          (if fail
            (do (reset! rc 1)
                (log (str "FAIL " lane ": " fail " secs=" secs)))
            (log (str lane " rc=0 secs=" secs))))
        (log (str "done rc=" @rc))
        (System/exit @rc)))))


(when (= *file* (System/getProperty "babashka.file"))
  (if (= "--list-slow" (first *command-line-args*))
    (let [clone (or (second *command-line-args*) ".")
          out (str (fs/create-temp-dir {:prefix "nightly-slow-list-"}))
          manifest (slow-manifest clone out)]
      (doseq [item (slow-schedule clone out manifest)]
        (prn (slow-command item))))
    (apply -main *command-line-args*)))
