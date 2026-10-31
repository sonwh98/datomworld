(ns dart-shard
  "Dart lane sharded by subsystem (the bb mirror of shard-jvm.sh).

   One full cljd compile (per-namespace incremental in lib/cljd-out; cannot
   be parallelized in one clone), then per subsystem a shard directory
   emitted by cljd_agg.clj (--only / --shard-dir / --no-compile / --no-run),
   and ONE `flutter test` invocation over all emitted directories — Flutter's
   test compiler writes a project-wide build/test_cache kernel, so parallel
   flutter processes in one clone race (gpt-6.1-sol review R3, 2026-10-31).
   Per-subsystem attribution survives via the emitted directory paths in the
   output.

   Review fixes implemented here (R1, R3, R6): the uncovered residual shard
   is emitted and run; emission failures are fatal; per-run immutable out
   dir (dart-shards-$STAMP, -latest symlink); prerequisite and compile
   failures are fatal; nonzero exit on any failure.

   Usage: bb src/dev/dart_shard.clj <clone-dir> [concurrency]
   Exit: 0 all green; 1 any failure."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.set :as set]
            [clojure.string :as str]))


(def subs
  ["yang.python" "yin.repl" "yin.vm" "yin.vm.ucf" "dao.stream"
   "dao.jing" "dao.gui" "dao.space" "demo" "dao.data"
   "dao.postgraphics" "yang.base" "dao.base"])


(def ^:private child-path
  "PATH for every child process: mise shims first (npm/node/flutter/bb/clj all
   resolve through mise regardless of the caller's ambient PATH), then the
   inherited PATH, then the system dirs for env/bash."
  (str (System/getProperty "user.home") "/.local/share/mise/shims"
       ":/usr/local/bin:/usr/bin:/bin:"
       (System/getenv "PATH")))


(defn- psh
  "p/shell with the mise-shims PATH forced into the child environment."
  [opts & cmd]
  (apply p/shell (assoc opts :extra-env {"PATH" child-path}) cmd))


(defn cljd-namespaces
  "The cljd test namespaces `bb test:sub:list <s>` reports for subsystem s."
  [clone s]
  (let [{:keys [out]} (psh {:dir clone :out :string :continue true}
                           "bb" "test:sub:list" s)
        section (->> (str/split-lines out)
                     (drop-while #(not (str/starts-with? % "cljd:")))
                     (take-while #(not (re-matches #"^[A-Za-z].*" %)))
                     (map str/trim)
                     (remove str/blank?))]
    (vec section)))


(defn -main
  [& args]
  (let [clone (or (first args) (System/getProperty "user.dir"))
        conc  (or (some->> (second args) parse-long) 8)
        stamp (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmm")
                       (java.time.LocalDateTime/now))
        out   (str clone "/target-ci/dart-shards-" stamp)
        rc    (atom 0)
        summary (str out "/summary.txt")
        fail! (fn [msg]
                (reset! rc 1)
                (println "FAIL:" msg)
                (spit summary (str "FAIL: " msg "\n") :append true))
        log   (fn [msg] (println msg) (spit summary (str msg "\n") :append true))]
    (fs/create-dirs out)
    (fs/delete-if-exists (str clone "/target-ci/dart-shards-latest"))
    (fs/create-sym-link (str clone "/target-ci/dart-shards-latest")
                        (str "dart-shards-" stamp))
    (log (str "start " stamp " conc=" conc))
    ;; the Dart peer; its absence makes peer tests skip
    (let [{:keys [exit]} (psh {:dir clone
                               :out (io/writer (str out "/build-peer.log") :append true)
                               :err (io/writer (str out "/build-peer.log") :append true)
                               :continue true}
                              "bb" "build:yin-repl-peer")]
      (log (str "build-yin-repl-peer rc=" exit))
      (when-not (zero? exit) (fail! "build:yin-repl-peer failed")))
    ;; per-subsystem cljd namespaces; the per-run files are the evidence
    (let [lists (into {} (map (fn [s] [s (cljd-namespaces clone s)]) subs))
          all (->> (file-seq (fs/file (str clone "/test")))
                   (filter #(re-find #"_test\.clj[cd]$" (str %)))
                   (map (fn [path]
                          (-> (str path)
                              (str/replace #"^test/" "")
                              (str/replace #"\.clj[cd]$" "")
                              (str/replace "/" ".")
                              (str/replace "_" "-"))))
                   set)
          covered (apply set/union (map set (vals lists)))
          uncovered (set/difference all covered)
          lists (cond-> lists (seq uncovered)
                        (assoc "uncovered" (vec uncovered)))]
      (log (str "all=" (count all) " covered=" (count covered)
                " uncovered=" (count uncovered)))
      (doseq [[s nss] lists]
        (spit (str out "/ns-" s ".txt") (str/join "\n" nss))))
    ;; one full cljd compile
    (let [t0 (System/currentTimeMillis)
          allns (->> (mapcat (fn [s]
                               (some->> (str out "/ns-" s ".txt")
                                        slurp
                                        str/split-lines
                                        (remove str/blank?)))
                             subs)
                     (apply concat (some->> (str out "/ns-uncovered.txt")
                                            slurp
                                            str/split-lines
                                            (remove str/blank?)))
                     distinct
                     vec)
          {:keys [exit]}
          (apply psh {:dir clone
                      :out (io/writer (str out "/compile.log") :append true)
                      :err (io/writer (str out "/compile.log") :append true)
                      :continue true}
                 "clojure" "-M:clojuredart:cljd" "compile" allns)]
      (log (str "compile-all rc=" exit
                " secs=" (quot (- (System/currentTimeMillis) t0) 1000)))
      (when-not (zero? exit) (fail! "cljd compile failed")))
    ;; emit one shard dir per subsystem (residual included; failures fatal)
    (let [dirs (atom [])
          {:keys [continue]} nil]
      (doseq [s (conj (vec subs) "uncovered")]
        (let [f (str out "/ns-" s ".txt")]
          (when (.exists (fs/file f))
            (let [nss (str/join "," (remove str/blank? (str/split-lines (slurp f))))
                  d (str clone "/build/cljd-agg-" stamp "-" s)
                  {:keys [exit]}
                  (psh {:dir clone
                        :out (str out "/" s ".emit.log")
                        :err :append
                        :continue true}
                       "bb" "src/dev/cljd_agg.clj" "--only" nss
                       "--shard-dir" d "--no-compile" "--no-run")]
              (if (zero? exit)
                (swap! dirs conj d)
                (fail! (str "shard " s " emission failed (rc=" exit ")")))))))
      (if (empty? @dirs)
        (fail! "no shards emitted")
        ;; R3: ONE flutter invocation over every emitted directory — the
        ;; project-wide build/test_cache kernel is touched by one process.
        (let [t0 (System/currentTimeMillis)
              {:keys [exit]}
              (apply psh {:dir clone
                          :out (str out "/all.log")
                          :err :append
                          :extra-env {"DATOM_SLOW_TESTS" "1"}
                          :continue true}
                     "flutter" "test" "--concurrency" (str conc) @dirs)
              wall (quot (- (System/currentTimeMillis) t0) 1000)
              verdict (some->> (slurp (str out "/all.log"))
                               str/split-lines
                               (filter #(re-find #"All tests passed|Some tests failed" %))
                               last)]
          (log (str "flutter rc=" exit " wall=" wall "s " (or verdict "")))
          (when-not (zero? exit) (fail! (str "flutter test rc=" exit))))))
    (log (str "done rc=" @rc))
    (System/exit @rc)))


(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
