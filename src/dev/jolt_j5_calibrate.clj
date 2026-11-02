;; J5b calibration record (C4, docs/design/yin.repl-jolt-implementation.md
;; §7.6): what each nesting shape costs the parse thread's stack per rule
;; frame and per lookahead link, measured with the guards off. J5c adds the
;; work record: per-shape counts of the work metric and the corpus sweep the
;; work bound derives from.
;;
;;   bb src/dev/jolt_j5_calibrate.clj [--out path] [--build-only]
;;   bb src/dev/jolt_j5_calibrate.clj --measure [--bounded | --lib path]
;;      [--probe] [--thread] [--fresh] <shape> <n> [<n> ...]
;;   bb src/dev/jolt_j5_calibrate.clj --slopes <shape> ...
;;   bb src/dev/jolt_j5_calibrate.clj --work [--bound n] [--out path]
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
;; --work builds the calibration variant, then records the work metric's
;; count (rule entries + closure steps + consumed tokens; the guard's three
;; units, read through jolt_parser_last_stats) for: the growth ladders of the
;; slow flat shapes (unbounded build, a cold worker per rung), every at-bound
;; nesting shape and the gated flat sizes the below leg parses (the bound's
;; floor), the work the bounded build charges up to each existing nesting
;; refusal (the axis must not flip), and the parsing corpus (the J0c oracle
;; corpus and its probe texts, the input-policy corpus, the REPL transcript
;; sources, the sanitizer harness sources, and the 4 MiB flat inputs). It
;; derives the bound the same measured-wall way the stack bounds used —
;; every must-parse work below it, the adversarial shapes' works above it —
;; asserts the ordering for the bound it is given (the wrapper's kMaxWork;
;; default the compiled constant below), and writes build/j5b/work.edn.
;;
;; Needs bb build:jolt-native (the runtime and the generated parser) and bb
;; bootstrap:jolt.
(ns jolt-j5-calibrate
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [jolt-j5-shapes :as shapes]))


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
(def n-work 600000000)  ; kMaxWork (J5c; --work asserts this by default)


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
  [{:keys [lib probe thread fresh sources]} shape depths]
  (let [r (apply p/shell {:out :string :err :string :continue true :extra-env jolt-env}
                 (str (jolt-env "JOLT_HOME") "/bin/jolt") "-Srepro" "-M:jolt:jolt-test"
                 "src/dev/jolt_j5_measure.clj"
                 (concat (when lib ["--lib" lib]) (when probe ["--probe"])
                         (when thread ["--thread"]) (when fresh ["--fresh"])
                         (when sources ["--sources" sources])
                         (if sources [] (cons (str shape) (map str depths)))))
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
                                     :work :high-water :ms])
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
          (= "--bound" a) (recur more (assoc o :bound (parse-long b)))
          (= "--build-only" a) (recur (rest args) (assoc o :build-only true))
          (= "--measure" a) (recur (rest args) (assoc o :measure true))
          (= "--work" a) (recur (rest args) (assoc o :work true))
          (= "--bounded" a) (recur (rest args) (assoc o :bounded true))
          (= "--lib" a) (recur more (assoc o :lib b))
          (= "--probe" a) (recur (rest args) (assoc o :probe true))
          (= "--thread" a) (recur (rest args) (assoc o :thread true))
          (= "--fresh" a) (recur (rest args) (assoc o :fresh true))
          (= "--slopes" a) (recur (rest args) (assoc o :slopes true))
          :else (recur (rest args) (update o :rest conj a)))))


;; --- the work record (J5c) ------------------------------------------------------

;; The growth ladders of the shapes whose time the nesting axes do not bound
;; (unbounded build, a cold worker per rung): the `1+` chain (prediction
;; re-consumes its statement, quadratic), the `1*` chain (closure over the
;; lookahead, faster), `**` and the backslash join (linear), and the flat
;; list/lines controls.
(def work-ladders
  {"r4" [500 2000 8000 20000]
   "r4-mul" [500 1000 2000]
   "power" [25000 100000]
   "r1c" [2500 10000]
   "flat-list" [5000 20000 50000]
   "flat-lines" [10000 50000]})


;; What the bound's floor must cover: every at-bound nesting shape (the
;; predicted table's largest parsing n), the parse witnesses the below leg
;; and the budget leg run (not at 1,024; the carrier shapes at depth 32; the
;; gated flat sizes r4-20000, r1c-10000, power-100000), over the unbounded
;; build (they parse there; the bound must let the bounded build parse them).
(def work-floor
  (concat
    (for [shape (sort (keys shapes/predicted))]
      [shape (shapes/largest-parsing shape)])
    [["not" 1024] ["parens" 32] ["list" 32] ["call" 32] ["unary" 32]
     ["not" 32] ["power" 32] ["lambda" 32] ["ternary" 32] ["indent" 32]
     ["r4" 20000] ["r1c" 10000] ["power" 100000]]))


;; The nesting refusals the gates assert by axis (bounded build): the work
;; charged up to each refusal must stay under the work bound, or the refusal
;; flips to :work and the exact-data legs fail.
(def work-refusals
  [["r1a" 100000] ["r1b" 100000] ["r2a" 100000] ["r2b" 50000] ["r3" 128]
   ["bs-ternary" 100000] ["parens" 100000] ["not" 100000] ["unary" 100000]
   ["lambda" 100000] ["ternary" 100000] ["call" 100000] ["comp-for" 100000]
   ["indent" 5000]])


;; The shapes the work bound must refuse (their work comes from the ladder
;; fits, not from parsing them: the 1+ chain at 100,000 costs hours and the
;; 1* chain at 4,000 about 8 minutes today).
(def work-must-refuse
  {"r4" 100000, "r4-mul" 4000})


(defn- work-corpus
  "The parsing corpus the bound must admit: the J0c oracle corpus and its
   probe texts, the input-policy corpus, the REPL transcript Python lines,
   the sanitizer harness's fixed sources, and the 4 MiB flat inputs."
  []
  (let [oracle (edn/read-string
                 (slurp "test/yang/python/antlr/fixtures/jolt_j0c_corpus.edn"))
        input (edn/read-string
                (slurp "test/yang/python/antlr/fixtures/jolt_j0c_input_corpus.edn"))
        transcripts (edn/read-string
                      (slurp "test/resources/yin/repl/transcripts/local.edn"))
        python-lines
        (fn [entry]
          (when (some #{"(lang :yang/python)"} (:in entry))
            (->> (:in entry)
                 (drop-while #(not= "(lang :yang/python)" %))
                 rest
                 (take-while #(not (str/starts-with? % "(lang :"))))))
        flat-comment
        {:id :flat-comment-4mib
         :source (str "#" (apply str (repeat (- (* 4 1024 1024) 2) "a")) "\n")}]
    (concat
      (for [{:keys [id source]} (:entries oracle)] {:id id, :source source})
      (map-indexed (fn [i s] {:id (keyword "probe" (str i)), :source s})
                   (:probe oracle))
      (for [{:keys [id source]} (:entries input)] {:id id, :source source})
      (mapcat (fn [entry]
                (map-indexed
                  (fn [i s]
                    {:id (keyword (str "transcript-" (name (:id entry)) "-" i)),
                     :source s})
                  (python-lines entry)))
              transcripts)
      ;; the sanitizer harness's fixed sources
      [{:id :harness-ok
        :source "def f(a):\n    if a:\n        return [a, {1: 'x'}]\n    return 2\nf(3)\n"}
       {:id :harness-bad, :source "def f(:\n    return 1\n"}
       {:id :harness-lexbad, :source "x = $\n"}
       {:id :harness-bom, :source "\uFEFFx = 1\n"}
       {:id :harness-nul, :source "s = 'a\u0000b'\n"}
       {:id :harness-empty, :source ""}]
      [flat-comment])))


(defn- quad-fit
  "The exact quadratic through three [n y] points, as a function of n
   (Lagrange form; the work ladders' growth curves are exactly quadratic)."
  [[[x1 y1] [x2 y2] [x3 y3]]]
  (let [term (fn [xi xj xk y n]
               (* y (/ (* (- n xj) (- n xk))
                       (* (- xi xj) (- xi xk)))))]
    (fn [n]
      (+ (term x1 x2 x3 y1 n) (term x2 x1 x3 y2 n) (term x3 x1 x2 y3 n)))))


(defn- work-record
  "Run the whole work sweep and write the record."
  [{:keys [out bound]}]
  (let [corpus-path "build/j5b/work-corpus-sources.edn"
        corpus (work-corpus)
        _ (do (fs/create-dirs (fs/parent corpus-path))
              (spit corpus-path (pr-str (vec corpus))))
        run (fn [opts shape depths]
              (:lines (measure (assoc opts :fresh true) shape depths)))
        ladders (into {}
                      (for [[shape ladder] work-ladders]
                        (do (prn :ladder shape ladder) (flush)
                            [shape (run {:lib unbounded-lib} shape ladder)])))
        floors (do (prn :floors) (flush)
                   (mapv (fn [[shape n]]
                           (let [[l] (run {:lib unbounded-lib} shape [n])]
                             {:shape shape, :n n,
                              :work (:work l), :status (:status l), :ms (:ms l)}))
                         work-floor))
        refusals (do (prn :refusals) (flush)
                     (mapv (fn [[shape n]]
                             (let [[l] (run {} shape [n])]
                               {:shape shape, :n n, :work (:work l),
                                :status (:status l), :message (:message l)}))
                           work-refusals))
        corpus-lines (do (prn :corpus (count corpus)) (flush)
                         (:lines (measure {:lib unbounded-lib
                                           :sources corpus-path}
                                          "corpus" [])))
        corpus-rows (mapv (fn [{:keys [shape work ms status bytes]}]
                            {:id (keyword shape), :bytes bytes, :work work,
                             :status status, :ms ms})
                          corpus-lines)
        fits (into {}
                   (for [[shape ladder] work-ladders
                         :when (>= (count ladder) 3)
                         :let [points (mapv (juxt :n :work)
                                            (take-last 3 (get ladders shape)))]]
                     [shape {:points points,
                             :at (into {}
                                       (for [[s n] work-must-refuse
                                             :when (= s shape)]
                                         [n (long (Math/ceil ((quad-fit points) n)))]))}]))
        must-refuse (into {}
                          (for [[shape {:keys [at]}] fits, [n w] at]
                            [(keyword (str shape "-" n)) w]))
        worst-corpus (apply max-key :work corpus-rows)
        floor-max (apply max-key :work floors)
        refusal-max (apply max-key :work refusals)
        per-byte (apply max (map (fn [{:keys [work bytes]}]
                                   (if (pos? (or bytes 0)) (/ work bytes) 0.0))
                                 corpus-rows))
        over-floor (vec (filter #(and (:work %) (> (:work %) bound))
                                (concat floors
                                        (map (fn [{:keys [id work]}]
                                               {:shape id, :work work})
                                             corpus-rows))))
        under-refuse (vec (filter #(and (:work %) (>= (:work %) bound)) refusals))
        record {:platform plat,
                :metric {:units [:rule-entries :closure-steps :consumed-tokens]},
                :bound bound,
                :corpus-worst worst-corpus,
                :floor-max floor-max,
                :refusal-max refusal-max,
                :work-per-byte {:worst per-byte,
                                :at-4mib-input-bound (long (* per-byte (* 4 1024 1024)))},
                :ladders ladders,
                :fits fits,
                :must-refuse must-refuse,
                :floors floors,
                :refusals refusals,
                :corpus corpus-rows}]
    (fs/create-dirs (fs/parent out))
    (spit out (with-out-str (pprint/pprint record)))
    (pprint/print-table
      [:shape :n :work :status]
      (sort-by (comp - :work) (concat floors refusals)))
    (println "corpus:" (count corpus-rows) "items, worst"
             (pr-str (select-keys worst-corpus [:id :work :bytes])))
    (println "floors over bound:" (pr-str over-floor))
    (println "refusals at/over bound:" (pr-str under-refuse))
    (println "must-refuse works:" (pr-str must-refuse))
    (println "wrote" out)
    (System/exit
      (if (and (empty? over-floor) (empty? under-refuse)
               (every? #(> (val %) bound) must-refuse))
        0 1))))


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

    (:work o)
    (do
      (build!)
      (work-record {:out (or out "build/j5b/work.edn")
                    :bound (or (:bound o) n-work)}))

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
                    :bounds {:rule n-rule, :look n-look, :full n-full,
                             :work n-work},
                    :rows rows}]
        (fs/create-dirs (fs/parent out))
        (spit out (with-out-str (pprint/pprint record)))
        (pprint/print-table [:shape :axis :exit :cost-per-unit :at-bound-bytes :quarter :ok] rows)
        (println "wrote" out)
        (System/exit (if (every? :ok rows) 0 1))))))
