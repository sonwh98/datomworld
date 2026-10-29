;; bb probe:jolt-j5 [leg ...] — the J5/J5b acceptance gates for the native
;; Python parser (known-gaps row 59; docs/design/yin.repl-jolt-implementation.md
;; §7.6), each in a child process so a native crash fails its gate and keeps
;; its report. Legs (all when none is named): budget below crash inject
;; abort unavailable.
;;
;;   1. budget, in process (src/dev/jolt_j5_budget.clj): the review's repros
;;      (C1), at/over per shape against the predicted table (C2), 10x the old
;;      crash depths (C3), the input axis, the REPL session surviving a
;;      refusal, the lifecycle (C7) and the rule-name accessor (C8);
;;   2. below: the carrier shapes at depth 32, the 20,000-operator `1+`
;;      chain, R1c at 10,000 and 100,000 `**` parse to the JVM's packets
;;      (src/dev/jolt_j5_below.cljc on both hosts, compared as data, the
;;      walls recorded; C1', C10);
;;   3. the old crash depths (r5 stack table) in child processes on the jolt
;;      thread (512 KiB stack): the property is no crash; each parses or
;;      refuses on the nesting axis as the bounds give, and 10x them refuse
;;      (C3);
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


(def legs
  (let [named (set *command-line-args*)]
    (if (empty? named)
      #{"budget" "below" "crash" "inject" "abort" "unavailable"}
      named)))


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
(when (legs "budget")
  (let [r (jolt root "src/dev/jolt_j5_budget.clj")]
    (relay r)
    (check "budget gates: child exited 0" (zero? (:exit r))
           {:exit (:exit r), :err (when-not (zero? (:exit r)) (:err r))})))


;; 2. below: the JVM's packets
(when (legs "below")
  (let [jvm (p/shell {:dir root :out :string :err :string :continue true}
                     ;; the JVM exporter recurses per tree level: the
                     ;; 20,000-deep 1+ tree overflows its default stack
                     ;; (StackOverflowError at yang.antlr.cst/ops), so the
                     ;; oracle runs with a 1 GiB thread stack
                     "clojure" "-J-Xss1g" "-M" "src/dev/jolt_j5_below.cljc")
        jolt-below (jolt root "src/dev/jolt_j5_below.cljc")
        read-below (fn [out]
                     (into {} (map (fn [l]
                                     (let [[_ shape packet] (re-matches #"BELOW (\S+) (.*)" l)]
                                       [shape (edn/read-string packet)])))
                           (lines out "BELOW ")))
        j (read-below (:out jvm))
        k (read-below (:out jolt-below))
        times (fn [out]
                (into {} (map (fn [l]
                                (let [[_ s t] (re-matches #"TIME (\S+) (\d+)" l)]
                                  [s (parse-long t)])))
                      (lines out "TIME ")))
        jt (times (:out jolt-below))]
    (println "RECORD below-walls" (pr-str {:jvm (times (:out jvm)), :jolt jt}))
    (check "below: each carrier shape at depth 32, the 20,000-operator 1+ chain, R1c at 10,000, 100,000 `**` and the flat 1,024-not chain parse on jolt to the JVM's packet (strict equality)"
           (and (= 13 (count j)) (= j k))
           {:jvm (count j), :jolt (count k), :jvm-exit (:exit jvm), :jolt-exit (:exit jolt-below),
            :jvm-err (when-not (zero? (:exit jvm))
                       (str/join "\n" (take 3 (str/split-lines (:err jvm))))),
            :differ (vec (for [s (keys j) :when (not= (j s) (k s))] s))})
    ;; the ruling's wall limits are laptop (macOS arm64) limits; other hosts
    ;; record the walls and assert 6x them, so a hang or a regression fails
    (let [laptop? (= "darwin-arm64" plat)
          limit (fn [ms] (if laptop? ms (* 6 ms)))]
      (check (str "below walls on jolt: R1c at 10,000 within " (/ (limit 15000) 1000)
                  " s, 100,000 `**` within " (/ (limit 60000) 1000) " s ("
                  (if laptop? "the laptop limits" "6x the laptop limits, walls recorded") ")")
             (and (some-> (jt "r1c-10000") (<= (limit 15000)))
                  (some-> (jt "power-100000") (<= (limit 60000))))
             (assoc jt :plat plat)))))


;; 3. the old crash depths, in children on the jolt thread (536 KiB): the
;; parse runs on the wrapper's 16 MiB thread, so the r5 crash depths now
;; parse (those the bounds admit) and 10x them refuse
(when (legs "crash")
  ;; call is lookahead-bound (n <= 408), so its old crash depths now refuse;
  ;; 12,800 INDENT levels are a 12.5 MB source, refused on the input axis (-6)
  (doseq [[shape parse refuse] [["parens" [544 576] [1024 5440]] ["call" [400] [512 544 5120]]
                                ["list" [544 576] [1024 5440]] ["indent" [1280 1344] [2046 12800]]
                                ["unary" [] [512 1024]] ["not" [5376] [53760]]]]
    (let [depths (concat parse refuse)
          r (apply p/shell {:dir root :out :string :err :string :continue true}
                   "sh" "-c"
                   ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt src/dev/jolt_j0c_stack_child.clj \"$@\""
                   "jolt" shape "thread" "native" "60000" (map str depths))
          info (some #(when (str/starts-with? % "INFO ") (subs % 5)) (str/split-lines (:out r)))
          oks (lines (:out r) "OK ")
          errs (lines (:out r) "ERR ")]
      (println "RECORD crash-depth" shape (pr-str {:parse parse, :refuse refuse, :exit (:exit r),
                                                   :info info, :ok oks, :err errs}))
      (check (str "the old crash depths of " shape " " parse " parse and " refuse
                  " refuse (status -7, or -6 over the input bound) on the jolt thread, no crash")
             (and (zero? (:exit r)) (= (count parse) (count oks))
                  (= (count refuse) (count errs))
                  (every? #(re-find #"status -[67]$" %) errs))
             {:exit (:exit r), :ok oks, :err errs}))))


;; 4. injected C++ exceptions in a jolt child
(doseq [mode (if (legs "inject") ["1" "2" "3"] [])]
  (let [r (jolt root "src/dev/jolt_j5_child.clj" "inject" mode)
        res (result (:out r))]
    (println "RECORD inject" mode (pr-str res))
    (check (str "injected C++ fault mode " mode ": the parse throws the wrapper status, no packet; the worker parses again")
           (and (zero? (:exit r)) (map? (:threw res)) (#{-2 -3} (:status (:threw res)))
                (= :yang.cst/ok (:again res)))
           res)))


;; 5. the harness property: a crash is a FAIL verdict with its report kept
(when (legs "abort")
  (let [r (jolt root "src/dev/jolt_j5_child.clj" "abort")
        verdict (if (zero? (:exit r)) :pass :fail)
        report (vec (lines (:out r) "REPORT "))]
    (println "RECORD forced-abort" (pr-str {:exit (:exit r), :verdict verdict, :report report}))
    (check "the harness property: a child that aborts is judged a failure (exit 134, SIGABRT), and the report it printed before the crash is kept"
           (and (= :fail verdict) (= 134 (:exit r))
                (= ["REPORT before-abort parsed :yang.cst/ok"] report))
           {:exit (:exit r), :report report})))


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


(when (legs "unavailable")
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
           res)
    (check "C8 rule-names answers unavailable-parser before the library exists and the native table once it appears"
           (and (zero? (:exit r)) (false? (:loaded-after-require res))
                (= :unavailable-parser (:names-before res))
                (= "single_input" (:names-after res)))
           (select-keys res [:loaded-after-require :names-before :names-after])))

  (let [r (jolt root "src/dev/jolt_j5_child.clj" "require")
        res (result (:out r))]
    (println "RECORD require" (pr-str res))
    (check "C8 with the library present, requiring the parser, frontends and REPL namespaces loads nothing; the first rule-names call loads it"
           (and (zero? (:exit r)) (false? (:loaded-after-require res))
                (= "single_input" (:names res)) (true? (:loaded-after-ask res)))
           {:exit (:exit r), :res res, :err (when-not (zero? (:exit r)) (:err r))})))


(println (if (zero? @failures) "ALL PASS" (str @failures " FAIL")))
(System/exit (if (zero? @failures) 0 1))
