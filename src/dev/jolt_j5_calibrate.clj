;; J5b calibration record (C4, docs/design/yin.repl-jolt-implementation.md
;; §7.6): what each nesting shape costs the parse thread's stack per rule
;; frame and per lookahead link, measured with the guards off.
;;
;;   bb src/dev/jolt_j5_calibrate.clj [--out path] [--build-only]
;;   bb src/dev/jolt_j5_calibrate.clj --measure [--bounded | --lib path]
;;      [--probe] [--thread] [--fresh] <shape> <n> [<n> ...]
;;   bb src/dev/jolt_j5_calibrate.clj --slopes <shape> ...
;;
;; The first form builds the calibration variant of native/jolt-parser
;; (-DJOLT_PARSER_TEST_SEAMS=ON -DJOLT_PARSER_UNBOUNDED=ON, at
;; build/native/<platform>/jolt-parser-unbounded/, never a library the REPL
;; loads), then per shape runs one child (src/dev/jolt_j5_measure.clj) over a
;; depth ladder on the 16 MiB parse thread with the stack probe on, and
;; derives from the deepest depth each child reached:
;;
;;   c_rule = high-water / deepest rule frame   (bytes per frame)
;;   c_link = high-water / longest chain         (bytes per link; SLL or
;;                                                full-LL past the outer)
;;
;; each attributing the whole high-water to that one axis, an overstatement.
;; It asserts N_rule x c_rule <= kParseStack/4 for the shapes the rule guard
;; bounds and N_look x c_link <= kParseStack/4 (N_full for full-LL) for the
;; shapes a lookahead guard bounds, and writes the record (default
;; build/j5b/calibration.edn). --measure runs one measuring child and prints
;; its lines: over the calibration build by default, the test build the
;; probes load with --bounded, or any library with --lib (an older build, for
;; time comparisons); --fresh gives each depth a cold worker. --slopes prints
;; each shape's rule/lookahead counts per level from depths 10 and 20 (the
;; source of the predicted table in src/dev/jolt_j5_shapes.cljc).
;;
;; Needs bb build:jolt-native (the runtime and the generated parser) and bb
;; bootstrap:jolt.
(ns jolt-j5-calibrate
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))


(def root (str (fs/canonicalize ".")))


(def plat
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")]
    (str (if (str/includes? os "mac") "darwin" "linux") "-"
         (if (#{"aarch64" "arm64"} arch) "arm64" "x86_64"))))


(def ext (if (str/starts-with? plat "darwin") ".dylib" ".so"))
(def native (fs/path root "build/native" plat))
(def unbounded-dir (fs/path native "jolt-parser-unbounded"))
(def unbounded-lib (str (fs/path unbounded-dir "lib" (str "libjolt_parser" ext))))


;; the wrapper's constants (jolt_parser.cpp), checked against
;; jolt_parser_budget by src/dev/jolt_j5_budget.clj
(def parse-stack (* 16 1024 1024))
(def n-rule 8192)
(def n-look 4096)
(def n-full 256)


(defn- fail!
  [& msg]
  (binding [*out* *err*] (println "calibrate FAILED:" (apply str msg)))
  (System/exit 1))


(defn- cmake-bin
  []
  (let [{:keys [cmake]} (edn/read-string (slurp "jolt-toolchain.edn"))
        r (p/shell {:out :string :err :string :continue true}
                   "mise" "exec" (str "cmake@" (:version cmake)) "--" "which" "cmake")]
    (when-not (zero? (:exit r)) (fail! "no mise cmake " (:version cmake)))
    (str/trim (:out r))))


(defn- build!
  []
  (let [cmake (cmake-bin)
        bld (str (fs/path native "jolt-parser-unbounded-build"))
        run! (fn [& cmd]
               (let [r (apply p/shell {:continue true} cmd)]
                 (when-not (zero? (:exit r)) (fail! (str/join " " cmd)))))]
    (fs/delete-tree bld)
    (run! cmake "-S" "native/jolt-parser" "-B" bld
          "-DCMAKE_BUILD_TYPE=Release"
          (str "-DANTLR4_PREFIX=" (fs/path native "antlr4-runtime"))
          (str "-DGENERATED_DIR=" (fs/absolutize "build/antlr/python3/cpp"))
          (str "-DCMAKE_INSTALL_PREFIX=" unbounded-dir)
          "-DJOLT_PARSER_TEST_SEAMS=ON" "-DJOLT_PARSER_UNBOUNDED=ON")
    (run! cmake "--build" bld "--parallel" "--config" "Release")
    (run! cmake "--install" bld "--config" "Release")
    (when-not (fs/exists? unbounded-lib) (fail! "no library at " unbounded-lib))))


(def jolt-env
  (into {} (keep #(when-let [[_ k v] (re-matches #"export (\w+)='(.*)'" %)] [k v]))
        (str/split-lines (slurp "build/tools/jolt-env.sh"))))


(defn measure
  "One measuring child: its M lines as maps, its exit, its stderr tail."
  [{:keys [lib probe thread fresh]} shape depths]
  (let [r (apply p/shell {:out :string :err :string :continue true :extra-env jolt-env}
                 (str (jolt-env "JOLT_HOME") "/bin/jolt") "-Srepro" "-M:jolt:jolt-test"
                 "src/dev/jolt_j5_measure.clj"
                 (concat (when lib ["--lib" lib]) (when probe ["--probe"])
                         (when thread ["--thread"]) (when fresh ["--fresh"])
                         [shape] (map str depths)))
        ms (keep #(when-let [[_ s n m] (re-matches #"M (\S+) (\d+) (.*)" %)]
                    (assoc (edn/read-string m) :shape s :n (parse-long n)))
                 (str/split-lines (:out r)))]
    {:exit (:exit r), :lines (vec ms),
     :done (str/includes? (:out r) "\nDONE"),
     :stderr (str/join "\n" (take-last 3 (str/split-lines (:err r))))}))


;; per shape: the guard that bounds it and a ladder that stays under 16 MiB
;; with the guards off (deeper rungs are skipped once a rung passes 20 s)
(def plan
  [["parens" :rule [400 800 1600]]
   ["list" :rule [400 800 1600]]
   ["call" :rule [400 800 1600]]
   ["not" :rule [1000 2000 4000 8000]]
   ["lambda" :rule [250 500 1000 2000]]
   ["ternary" :rule [250 500 1000 2000]]
   ["indent" :rule [250 500 1000 2000]]
   ["comp-for" :rule [1000 2000 4000]]
   ["bs-ternary" :rule [250 500 1000 2000]]
   ["parens-error" :rule [200 400 800]]
   ["not-error" :rule [1000 2000 4000 8000]]
   ["sll-nest" :look [100 200 400 800]]
   ["open-nest" :look [100 200 400 800]]
   ["unary" :full [64 128 256 384]]
   ;; the outer stack is in the full-LL chain: its pops cost stack too, so
   ;; this row is charged per rule frame (the outer stack dominates)
   ["deep-unary" :rule [16 64 128]]])


(defn- record-row
  [[shape axis ladder]]
  (let [{:keys [lines exit stderr]} (measure {:lib unbounded-lib :probe true} shape ladder)
        deepest (peek lines)
        hw (:high-water deepest)
        dim (case axis :rule (:rule deepest) :look (:look deepest) :full (:full deepest))
        c (when (and hw dim (pos? dim)) (double (/ hw dim)))
        bound (case axis :rule n-rule :look n-look :full n-full)
        quarter (quot parse-stack 4)]
    {:shape shape, :axis axis, :ladder ladder, :exit exit,
     :reached (mapv #(select-keys % [:n :status :rule :look :full :full-predictions
                                     :high-water :ms])
                    lines),
     :cost-per-unit c,
     :at-bound-bytes (when c (long (* bound c))),
     :quarter quarter,
     :ok (boolean (and (zero? exit) c (<= (* bound c) quarter))),
     :stderr (when-not (zero? exit) stderr)}))


(defn- options
  [args]
  (loop [[a b & more :as args] args, o {:rest []}]
    (cond (empty? args) o
          (= "--out" a) (recur more (assoc o :out b))
          (= "--build-only" a) (recur (rest args) (assoc o :build-only true))
          (= "--measure" a) (recur (rest args) (assoc o :measure true))
          (= "--bounded" a) (recur (rest args) (assoc o :bounded true))
          (= "--lib" a) (recur more (assoc o :lib b))
          (= "--probe" a) (recur (rest args) (assoc o :probe true))
          (= "--thread" a) (recur (rest args) (assoc o :thread true))
          (= "--fresh" a) (recur (rest args) (assoc o :fresh true))
          (= "--slopes" a) (recur (rest args) (assoc o :slopes true))
          :else (recur (rest args) (update o :rest conj a)))))


(let [{:keys [out build-only bounded probe thread] :as o} (options *command-line-args*)]
  (cond
    (:slopes o)
    ;; each axis's count at depths 10 and 20 on a cold worker, in the test build
    (doseq [shape (:rest o)]
      (let [{:keys [lines]} (measure {:fresh true} shape [10 20])
            [a b] lines]
        (prn shape (into {} (for [k [:rule :look :full]]
                              (let [per (- (k b) (k a))]
                                [k {:per-level (/ per 10), :base (- (k a) (* 10 (/ per 10)))}])))
             {:status [(:status a) (:status b)], :full-predictions (:full-predictions b)})
        (flush)))

    (:measure o)
    (let [[shape & ds] (:rest o)
          r (measure {:lib (or (:lib o) (when-not bounded unbounded-lib))
                      :probe probe :thread thread :fresh (:fresh o)}
                     shape (map parse-long ds))]
      (doseq [l (:lines r)] (prn l))
      (println "EXIT" (:exit r) (if (:done r) "DONE" "") (:stderr r))
      (System/exit 0))

    :else
    (do
      (build!)
      (when build-only (System/exit 0))
      (let [rows (vec (for [row plan]
                        (let [r (record-row row)]
                          (prn (dissoc r :reached))
                          (flush)
                          r)))
            out (or out "build/j5b/calibration.edn")
            record {:platform plat, :parse-stack parse-stack,
                    :bounds {:rule n-rule, :look n-look, :full n-full},
                    :rows rows}]
        (fs/create-dirs (fs/parent out))
        (spit out (with-out-str (pprint/pprint record)))
        (pprint/print-table [:shape :axis :exit :cost-per-unit :at-bound-bytes :quarter :ok] rows)
        (println "wrote" out)
        (System/exit (if (every? :ok rows) 0 1))))))
