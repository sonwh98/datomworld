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
            [clojure.string :as str]))


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


(defn- slow-namespaces
  "The ^:slow test namespaces, derived from the tree (never a static list)."
  [clone]
  (->> (apply psh {:dir clone :out :string :continue true}
              ["grep" "-rl" "\\^:slow" "test" "--include=*.clj" "--include=*.cljc"])
       :out
       str/split-lines
       (remove str/blank?)
       (map (fn [path]
              (-> path
                  (str/replace #"^test/" "")
                  (str/replace #"\.clj[cd]?$" "")
                  (str/replace "/" ".")
                  (str/replace "_" "-"))))
       sort))


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
                       (do (swap! results assoc x (f x)) (recur))
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
                        "git" "checkout" "-q" "--detach" sha))]
    (when-not (zero? frc) (log (str "FAIL: fetch in " clone-dir)))
    (when-not (zero? crc) (log (str "FAIL: checkout " sha " in " clone-dir)))
    (if (zero? frc) crc frc)))


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


(defn- lane-jvm
  [clone par out summary]
  (let [subset (assigned-subsystems)
        ;; prerequisites the bare-metal hosts got from prior rounds: the
        ;; antlr-generated parser classes and the Node REPL. A fresh
        ;; container has neither, and yang.*.antlr tests fail with
        ;; ClassNotFoundException without the first. gen:antlr is cheap
        ;; when its cache is warm.
        _ (doseq [[nm cmd] [["gen:antlr" ["bb" "gen:antlr"]]
                            ["build:yin-repl-node" ["bb" "build:yin-repl-node"]]]
                  :let [t0 (System/currentTimeMillis)
                        exit (run-cmd! clone (str out "/" nm ".log") cmd)]]
            (log-lane summary (str nm " rc=" exit
                                   " secs=" (quot (- (System/currentTimeMillis) t0) 1000)))
            (when-not (zero? exit) (throw (ex-info (str nm " failed") {}))))
        slow-nss (cond->> (slow-namespaces clone)
                   subset (filter #(in-subset? % subset)))
        ;; quick tier: subsystem-sharded on the full set; per-subsystem
        ;; test:sub:clj runs pooled when a disjoint subset is assigned
        quick-log (str out "/jvm-quick.log")
        quick (if subset
                (let [results (run-pool par
                                        (fn [s]
                                          (run-cmd! clone
                                                    (str out "/jvm-quick-" s ".log")
                                                    ["bb" "test:sub:clj" s]))
                                        subset)]
                  (reduce + (vals results)))
                (run-cmd! clone quick-log
                          ["bb" "test:quick-sharded" "--workers" (str par)]))
        slow (run-pool par
                       (fn [nss]
                         (run-cmd! clone (str out "/slow-" nss ".log")
                                   ["clojure" "-M:test" "-i" :slow "-n" nss]))
                       slow-nss)
        slow-red (count (remove zero? (vals slow)))]
    (spit summary (str "jvm-quick rc=" quick
                       (when subset (str " (subset: " (str/join "," (sort subset)) ")"))
                       "\njvm-slow namespaces=" (count slow-nss)
                       " red=" slow-red "\n")
          :append true)
    (when-not (zero? quick) (throw (ex-info "jvm quick tier red" {:rc quick})))
    (when (pos? slow-red) (throw (ex-info "jvm slow tier red" {:red slow-red})))))


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
        (doseq [{:keys [lane lane-rc secs fail]} results]
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
  (apply -main *command-line-args*))
