;; bb probe:jolt-j5 — the J5 acceptance gates for the native Python parser
;; (known-gaps row 59; glm-5.3 ruling archive/jolt/1793096000000-architect-mob-row59-j5-scope.glm-5.3.findings.md §2.1-§2.4), each
;; in a child process so a native crash fails its gate and keeps its report:
;;
;;   1. the budget, in process (src/dev/jolt_j5_budget.clj): below/at/above
;;      on both axes, 10x the old crash depths, deterministic refusals, the
;;      REPL session surviving a refusal, the worst at-bound walls;
;;   2. below: the carrier shapes at depth 32 parse to the JVM's packets
;;      (src/dev/jolt_j5_below.cljc on both hosts, compared as data);
;;   3. the old crash depths (r5 stack table) in child processes on the jolt
;;      thread (512 KiB stack): each refuses with status -7, no SIGSEGV;
;;   4. injected C++ exceptions (modes 1-3) in a jolt child: the parse
;;      throws, no packet, the same worker parses again;
;;   5. the harness property: a child that aborts is a FAIL verdict with its
;;      report kept, never a pass and never an erased report;
;;   6. unavailable library: a root with no parser library, and one with a
;;      wrong-layout library: Python answers unavailable-parser, Clojure and
;;      PHP evaluate in the same session; the library appearing later is
;;      picked up by the next selection.
;;
;; Needs bb bootstrap:jolt and bb build:jolt-native (the test build, for the
;; fault seam). Prints PASS/FAIL/RECORD; exits nonzero on any FAIL.
(ns jolt-j5-run
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail))
  (flush))


(def root (str (fs/canonicalize ".")))
(def scratch (fs/path root "build/j5-probe"))
(fs/delete-tree scratch)
(fs/create-dirs scratch)


(def plat
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")]
    (str (if (str/includes? os "mac") "darwin" "linux") "-"
         (if (#{"aarch64" "arm64"} arch) "arm64" "x86_64"))))


(def ext (if (str/starts-with? plat "darwin") ".dylib" ".so"))


(defn- jolt
  [dir & args]
  (let [r (apply p/shell {:dir dir :out :string :err :string :continue true}
                 "sh" "-c"
                 ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt:jolt-test \"$@\""
                 "jolt" args)]
    {:exit (:exit r) :out (:out r) :err (:err r)}))


(defn- lines
  [out prefix]
  (filter #(str/starts-with? % prefix) (str/split-lines (str out))))


(defn- result
  [out]
  (some-> (first (lines out "RESULT ")) (subs 7) edn/read-string))


(defn- relay
  [{:keys [out]}]
  (doseq [l (str/split-lines (str out))
          :when (re-find #"^(RECORD|PASS|FAIL) " l)]
    (when (str/starts-with? l "FAIL ") (swap! failures inc))
    (println l)))


;; 1. the budget, in process
(let [r (jolt root "src/dev/jolt_j5_budget.clj")]
  (relay r)
  (check "budget gates: child exited 0" (zero? (:exit r)) {:exit (:exit r)})

  ;; 2. below: the JVM's packets
  (let [jvm (p/shell {:dir root :out :string :err :string :continue true}
                     "clojure" "-M" "src/dev/jolt_j5_below.cljc")
        jolt-below (jolt root "src/dev/jolt_j5_below.cljc")
        read-below (fn [out]
                     (into {} (map (fn [l]
                                     (let [[_ shape packet] (re-matches #"BELOW (\S+) (.*)" l)]
                                       [shape (edn/read-string packet)])))
                           (lines out "BELOW ")))
        j (read-below (:out jvm))
        k (read-below (:out jolt-below))]
    (check "below: each carrier shape at depth 32 parses on jolt to the JVM's packet (strict equality)"
           (and (= 9 (count j)) (= j k))
           {:jvm (count j), :jolt (count k),
            :differ (vec (for [s (keys j) :when (not= (j s) (k s))] s))})))


;; 3. the old crash depths, in children on the jolt thread
(doseq [[shape depths] [["parens" [544 576 1024]] ["call" [512 544]] ["list" [544 576]]
                        ["indent" [1280 1344]] ["unary" [512 1024]]]]
  (let [r (apply p/shell {:dir root :out :string :err :string :continue true}
                 "sh" "-c"
                 ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt src/dev/jolt_j0c_stack_child.clj \"$@\""
                 "jolt" shape "thread" "native" "20000" (map str depths))
        info (some #(when (str/starts-with? % "INFO ") (subs % 5)) (str/split-lines (:out r)))
        oks (lines (:out r) "ERR ")]
    (println "RECORD crash-depth" shape (pr-str {:depths depths, :exit (:exit r), :info info, :lines oks}))
    (check (str "the old crash depths of " shape " " depths " on the jolt thread refuse (status -7), no crash")
           (and (zero? (:exit r)) (= (count depths) (count oks))
                (every? #(str/includes? % "status -7") oks))
           {:exit (:exit r), :lines oks})))


;; 4. injected C++ exceptions in a jolt child
(doseq [mode ["1" "2" "3"]]
  (let [r (jolt root "src/dev/jolt_j5_child.clj" "inject" mode)
        res (result (:out r))]
    (println "RECORD inject" mode (pr-str res))
    (check (str "injected C++ fault mode " mode ": the parse throws the wrapper status, no packet; the worker parses again")
           (and (zero? (:exit r)) (map? (:threw res)) (#{-2 -3} (:status (:threw res)))
                (= :yang.cst/ok (:again res)))
           res)))


;; 5. the harness property: a crash is a FAIL verdict with its report kept
(let [r (jolt root "src/dev/jolt_j5_child.clj" "abort")
      verdict (if (zero? (:exit r)) :pass :fail)
      report (vec (lines (:out r) "REPORT "))]
  (println "RECORD forced-abort" (pr-str {:exit (:exit r), :verdict verdict, :report report}))
  (check "the harness property: a child that aborts is judged a failure (exit 134, SIGABRT), and the report it printed before the crash is kept"
         (and (= :fail verdict) (= 134 (:exit r))
              (= ["REPORT before-abort parsed :yang.cst/ok"] report))
         {:exit (:exit r), :report report}))


;; 6. unavailable library: missing, wrong layout, appearing later
(defn- fake-root
  [nm parser-lib]
  (let [dir (fs/path scratch nm)
        native (fs/path dir "build/native" plat)]
    (fs/create-dirs native)
    (doseq [f ["deps.edn" "src" "test" "jolt-toolchain.edn"]]
      (fs/create-sym-link (fs/path dir f) (fs/path root f)))
    (fs/create-sym-link (fs/path dir "build/tools") (fs/real-path (fs/path root "build/tools")))
    (doseq [entry (fs/list-dir (fs/path root "build/native" plat))
            :when (not (str/starts-with? (str (fs/file-name entry)) "jolt-parser"))]
      (fs/create-sym-link (fs/path native (fs/file-name entry)) (fs/real-path entry)))
    (when parser-lib
      (fs/create-dirs (fs/path native "jolt-parser/lib"))
      (fs/copy parser-lib (fs/path native "jolt-parser/lib" (str "libjolt_parser" ext))))
    (str dir)))


(def unavailable-texts
  ["Error: unavailable-parser: Yin REPL language :yang/python is not in the frontend catalog; installed: :clojure :php (ids :yang/clojure :yang/php)"
   "3" "Switched to :yang/php" "3" "Switched to :yang/clojure" "42"])


(let [r (jolt (fake-root "missing" nil) "src/dev/jolt_j5_child.clj" "unavailable")
      res (result (:out r))]
  (println "RECORD unavailable missing" (pr-str res))
  (check "library absent: the REPL starts, Python answers unavailable-parser (never another parser), Clojure and PHP evaluate in the same session"
         (and (zero? (:exit r)) (false? (:available res)) (= unavailable-texts (:texts res)))
         {:exit (:exit r), :res res, :err (when-not (zero? (:exit r)) (:err r))}))


(let [lib (str (fs/path scratch (str "wrong_layout" ext)))
      src (str (fs/path scratch "wrong_layout.c"))]
  (spit src "__attribute__((visibility(\"default\"))) int jolt_parser_export_layout_version(void) { return 99; }\n")
  (p/shell {:out :string :err :string} "cc" "-shared" "-fPIC" "-o" lib src)
  (let [r (jolt (fake-root "wrong" lib) "src/dev/jolt_j5_child.clj" "unavailable")
        res (result (:out r))]
    (println "RECORD unavailable wrong-layout" (pr-str res))
    (check "wrong layout version: Python answers unavailable-parser, Clojure and PHP evaluate in the same session"
           (and (zero? (:exit r)) (false? (:available res)) (= unavailable-texts (:texts res)))
           {:exit (:exit r), :res res})))


(let [real (str (fs/path root "build/native" plat "jolt-parser/lib" (str "libjolt_parser" ext)))
      r (jolt (fake-root "retry" nil) "src/dev/jolt_j5_child.clj" "retry" real)
      res (result (:out r))]
  (println "RECORD unavailable retry" (pr-str res))
  (check "a later selection retries the load: unavailable first, then the built library is picked up and Python evaluates"
         (and (zero? (:exit r)) (false? (:before res))
              (str/starts-with? (str (first (:first res))) "Error: unavailable-parser")
              (true? (:after res)) (= ["Switched to :yang/python" "3"] (:then res)))
         res))


(println (if (zero? @failures) "ALL PASS" (str @failures " FAIL")))
(System/exit (if (zero? @failures) 0 1))
