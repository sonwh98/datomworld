(ns node-shard
  "Node lane sharded by subsystem (the bb mirror of shard-jvm.sh).

   One full shadow-cljs compile (:autorun false), then one
   `node target/node-tests.js --test=ns1,ns2,...` process per subsystem, up
   to PAR at a time. Selection is RUNTIME: shadow's :ns-regexp compile filter
   does not remove previously cached test namespaces from the bundle.

   Review fixes (gpt-6.1-sol, 2026-10-31) implemented here:
   - R1: every requested namespace is validated against the compiled
     bundle's own --list inventory before running (the --test selector
     silently ignores unknown namespaces).
   - R6: per-run immutable out dir (node-shards-$STAMP, -latest symlink);
     expected-vs-completed shard check; nonzero exit on red, missing, or
     unexplained shards; prerequisite and compile failures are fatal.
   - The Dart peer is built (its absence makes peer tests skip).

   Usage: bb src/dev/node_shard.clj <clone-dir> [parallel]
   Exit: 0 all green; 1 any failure (infra, missing, or red)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
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
  "p/shell with the mise-shims PATH forced into the child environment,
   merged over any caller-supplied :env."
  [opts & cmd]
  (apply p/shell (update opts :extra-env #(merge {"PATH" child-path} %)) cmd))


(defn ns->test-ns
  "test/yin/vm/foo_test.cljc -> yin.vm.foo-test (the namespace name)."
  [path]
  (-> path
      (str/replace #"^test/" "")
      (str/replace #"\.clj[cs]$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")))


(defn cljs-namespaces
  "The cljs test namespaces `bb test:sub:list <s>` reports for subsystem s."
  [clone s]
  (let [{:keys [out]} (psh {:dir clone :out :string :continue true}
                           "bb" "test:sub:list" s)
        section (->> (str/split-lines out)
                     (drop-while #(not (str/starts-with? % "cljs:")))
                     (rest)
                     (take-while #(re-matches #"^\s+\S.*$" %))
                     (map str/trim)
                     (remove str/blank?))]
    (vec section)))


(defn -main
  [& args]
  (let [clone (or (first args) (System/getProperty "user.dir"))
        par   (or (some->> (second args) parse-long) 6)
        stamp (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmm")
                       (java.time.LocalDateTime/now))
        out   (str clone "/target-ci/node-shards-" stamp)
        rc    (atom 0)
        summary (str out "/summary.txt")
        fail! (fn [msg]
                (reset! rc 1)
                (println "FAIL:" msg)
                (spit summary (str "FAIL: " msg "\n") :append true))
        log   (fn [msg] (println msg) (spit summary (str msg "\n") :append true))
        sh    (fn [out-file cmd]
                (let [{:keys [exit]} (apply psh {:dir clone, :out (io/writer out-file :append true), :err (io/writer out-file :append true),
                                                 :continue true}
                                            cmd)]
                  exit))]
    (fs/create-dirs out)
    (fs/delete-if-exists (str clone "/target-ci/node-shards-latest"))
    (fs/create-sym-link (str clone "/target-ci/node-shards-latest")
                        (str "node-shards-" stamp))
    (log (str "start " stamp " par=" par))
    ;; prerequisites — fatal on failure
    ;; npm always through mise exec: mise's npm wrapper execs `node` by bare
    ;; name, which fails under any caller PATH that lacks the mise node bin
    ;; (mise exec supplies it). The repo's mise.toml pins the node version.
    (let [exit (sh (str out "/npm-ci.log")
                   ["mise" "exec" "--" "npm" "ci" "--silent"])]
      (log (str "npm-ci rc=" exit))
      (when-not (zero? exit) (fail! "npm ci failed")))
    (doseq [[nm cmd] [["build-yin-repl-node" ["bb" "build:yin-repl-node"]]
                      ["build-yin-repl-peer" ["bb" "build:yin-repl-peer"]]
                      ["gen-antlr" ["bb" "gen:antlr"]]]]
      (let [exit (sh (str out "/" nm ".log") cmd)]
        (log (str nm " rc=" exit))
        (when-not (zero? exit) (fail! (str nm " failed")))))
    ;; per-subsystem cljs namespaces; the per-run files are the evidence
    (let [lists (into {} (map (fn [s] [s (cljs-namespaces clone s)]) subs))
          test-root (str (fs/canonicalize (str clone "/test")))
          all (->> (file-seq (fs/file test-root))
                   (filter #(re-find #"_test\.clj[cs]$" (str %)))
                   (map (fn [path]
                          (-> (str path)
                              (str/replace (str test-root "/") "")
                              ns->test-ns)))
                   set)
          covered (apply set/union (map set (vals lists)))
          uncovered (set/difference all covered)
          lists (cond-> lists (seq uncovered)
                        (assoc "uncovered" (vec uncovered)))]
      (log (str "all=" (count all) " covered=" (count covered)
                " uncovered=" (count uncovered)))
      (doseq [[s nss] lists]
        (spit (str out "/ns-" s ".txt") (str/join "\n" nss))))
    ;; one full compile, no autorun
    (let [t0 (System/currentTimeMillis)
          exit (sh (str out "/compile.log")
                   ["clj" "-M:cljs" "-m" "shadow.cljs.devtools.cli"
                    "compile" "test" "--config-merge" "{:autorun false}"])]
      (log (str "compile-all rc=" exit
                " secs=" (quot (- (System/currentTimeMillis) t0) 1000)))
      (when-not (zero? exit) (fail! "compile failed")))
    ;; R1: the bundle's own inventory is the authority on what can run
    (let [{listing :out} (psh {:dir clone :out :string :continue true}
                              "node" "target/node-tests.js" "--list")
          bundle (->> (str/split-lines listing)
                      (keep #(re-find #"^Namespace: (\S+)" %))
                      (map second)
                      set)
          lists (into {}
                      (map (fn [s]
                             [s (some->> (str out "/ns-" s ".txt")
                                         slurp
                                         str/split-lines
                                         (remove str/blank?)
                                         vec)])
                           subs))
          lists (cond-> lists
                  (.exists (fs/file (str out "/ns-uncovered.txt")))
                  (assoc "uncovered"
                         (->> (slurp (str out "/ns-uncovered.txt"))
                              str/split-lines
                              (remove str/blank?)
                              vec)))
          sel (into {} (map (fn [[s nss]] [s (filterv bundle nss)])) lists)
          missing (into {}
                        (remove (fn [[_ nss]] (empty? nss)))
                        (into {}
                              (map (fn [[s nss]]
                                     [s (set/difference (set nss) bundle)]))
                              lists))]
      (doseq [[s miss] missing]
        (fail! (str "shard " s " requests namespaces absent from the compiled bundle: "
                    (str/join "," miss))))
      ;; run up to PAR shards at a time (waves)
      (let [t0 (System/currentTimeMillis)
            shards (vec (filter (comp seq sel) (keys sel)))
            runs (atom {})
            run-one (fn [s]
                      (let [t0 (System/currentTimeMillis)
                            {:keys [exit]}
                            (psh {:dir clone
                                  :out (str out "/" s ".log")
                                  :err :append
                                  :extra-env {"DATOM_SLOW_TESTS" "1"}
                                  :continue true}
                                 "node" "target/node-tests.js"
                                 "--test" (str/join "," (sel s)))
                            secs (quot (- (System/currentTimeMillis) t0) 1000)]
                        (swap! runs assoc s {:rc exit :secs secs})
                        (spit (str out "/runs.txt")
                              (str s " rc=" exit " secs=" secs "\n")
                              :append true)))]
        (doseq [batch (partition-all par shards)]
          (->> batch
               (mapv (fn [s] (future (run-one s))))
               (mapv deref)))
        (let [expected (count shards)
              done (count @runs)
              red (count (filter #(not= 0 (:rc %)) (vals @runs)))
              wall (quot (- (System/currentTimeMillis) t0) 1000)]
          (doseq [[s r] (sort @runs)]
            (log (str s " rc=" (:rc r) " secs=" (:secs r))))
          (log (str "node-sharded lanes=" done " red-lanes=" red
                    " wall=" wall "s"))
          (when-not (= expected done)
            (fail! (str "expected " expected " shards, completed " done)))
          (when (pos? red)
            (fail! (str red " red shards"))))))
    (log (str "done rc=" @rc))
    (System/exit @rc)))


(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
