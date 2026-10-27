;; J5 (known-gaps row 59; glm-5.3 ruling collab/1793096000000 §2.4): the
;; native Python parser's budget, in process, under jolt.
;;
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME"/bin/jolt -Srepro -M:jolt:jolt-test src/dev/jolt_j5_budget.clj
;;
;; Per axis, three legs:
;;   below  every carrier shape (the r5 stack shapes) at a depth well under
;;          the bound parses, through yang.python.antlr.parser;
;;   at     the shape whose prescan measures exactly the bound parses, and
;;          bound+1 refuses with the exact qualified :parser-budget data; the
;;          input axis the same at 4 MiB and 4 MiB + 1 (the pre-FFI mirror,
;;          and the native -6 when the mirror is bypassed);
;;   above  10x the old crash depths in process: 100,000 nested parens,
;;          100,000 unary prefixes, a 5,000-deep INDENT source (over the
;;          input bound as well), each refused three times with identical
;;          data and no crash;
;; plus: a refused submission leaves the composed REPL session evaluating
;; (Python, then Clojure and PHP); the convenience calls dispose their
;; workers; the worst legal-input parse wall is recorded per shape at the
;; bound. Prints PASS/FAIL/RECORD lines and `BELOW <shape> <edn packet>` for
;; the JVM comparison (src/dev/jolt_j5_run.clj); exits nonzero on a FAIL.
(ns jolt-j5-budget
  (:require
    [clojure.string :as str]
    [jolt.ffi :as ffi]
    [yang.antlr.cst :as cst]
    [yang.python.antlr.parser :as parser]
    [yin.repl :as repl]
    [yin.repl.frontends :as frontends]))


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail))
  (flush))


(defn- rep
  [n s]
  (apply str (repeat n s)))


;; the r5 stack shapes (src/dev/jolt_j0c_stack_child.clj), the carriers the
;; prescan charges
(def shapes
  {"parens" (fn [n] (str "x = " (rep n "(") "1" (rep n ")") "\n"))
   "list" (fn [n] (str "x = " (rep n "[") "1" (rep n "]") "\n"))
   "call" (fn [n] (str "x = " (rep n "f(") "1" (rep n ")") "\n"))
   "unary" (fn [n] (str "x = " (rep n "-") "1\n"))
   "not" (fn [n] (str "x = " (rep n "not ") "1\n"))
   "power" (fn [n] (str "x = " (rep n "2 ** ") "2\n"))
   "lambda" (fn [n] (str "x = " (rep n "lambda: ") "1\n"))
   "ternary" (fn [n] (str "x = " (rep n "1 if 1 else ") "1\n"))
   "indent" (fn [n]
              (str (apply str (for [i (range n)] (str (rep i " ") "if x:\n")))
                   (rep n " ") "pass\n"))})


(ffi/defcfn c-budget "jolt_parser_budget" [:pointer] :int)
(ffi/defcfn c-nesting "jolt_parser_nesting" [:pointer :size_t] :uint64)
(ffi/defcfn c-export "jolt_parser_export" [:pointer :pointer :size_t :pointer :pointer] :int :blocking)
(ffi/defcfn c-free "jolt_parser_free" [:pointer] :void)


(check "the native parser library is available" (parser/available?) nil)


(def budget
  (let [p (ffi/alloc 40)]
    (try (c-budget p)
         (zipmap [:input-bytes :depth :depth-here :stack :basis]
                 (vec (ffi/read-array p :uint64 5)))
         (finally (ffi/free p)))))


(println "RECORD budget" (pr-str budget))


(check "the wrapper's bounds are the ruling's: 4 MiB input, depth 256, the jolt input mirror equal"
       (and (= 4194304 (:input-bytes budget) cst/input-bound) (= 256 (:depth budget))
            (<= (:depth-here budget) (:depth budget)))
       budget)


(def bound (:depth-here budget))


(defn- nesting
  [^String s]
  (let [b (.getBytes s "UTF-8")
        n (alength b)
        p (ffi/alloc (max 1 n))]
    (try (when (pos? n) (ffi/write-array p b))
         (c-nesting p n)
         (finally (ffi/free p)))))


(defn- native-status
  "jolt_parser_export alone, bypassing the jolt mirror: its status and message."
  [^String s]
  (let [w (cst/make-worker {})
        bs (.getBytes s "UTF-8")
        n (alength bs)
        in (ffi/alloc (max 1 n))
        out-cell (ffi/alloc :pointer)
        len-cell (ffi/alloc :size_t)]
    (try
      (when (pos? n) (ffi/write-array in bs))
      (let [status (c-export @(:yang.antlr.cst/native w) in n out-cell len-cell)
            p (ffi/read out-cell :pointer)
            len (ffi/read len-cell :size_t)
            msg (when-not (ffi/null? p)
                  (try (String. ^bytes (ffi/read-array p len) "UTF-8")
                       (finally (c-free p))))]
        [status msg])
      (finally (ffi/free in) (ffi/free out-cell) (ffi/free len-cell)
               (cst/close-worker w)))))


(defn- outcome
  "The parse's :yang.cst/outcome, or the refusal's ex-data."
  [s]
  (try (:yang.cst/outcome (parser/parse-source s))
       (catch Exception e (or (ex-data e) {:threw (ex-message e)}))))


(defn- depth-measuring
  "The smallest n whose shape measures exactly `target` (the prescan is
   monotone in n), or nil."
  [shape target]
  (let [f (shapes shape)]
    (loop [lo 0, hi (* 4 target)]
      (if (> lo hi)
        nil
        (let [mid (quot (+ lo hi) 2)
              m (nesting (f mid))]
          (cond (= m target) (loop [k mid] (if (and (pos? k) (= target (nesting (f (dec k))))) (recur (dec k)) k))
                (< m target) (recur (inc mid) hi)
                :else (recur lo (dec mid))))))))


(defn- ms
  [t0]
  (long (/ (- (System/nanoTime) t0) 1e6)))


;; =============================================================================
;; nesting axis: below / at / above, per shape
;; =============================================================================

(doseq [shape (sort (keys shapes))]
  (let [f (shapes shape)
        below (f 32)
        n-at (depth-measuring shape bound)
        n-over (depth-measuring shape (inc bound))
        at (when n-at (f n-at))
        over (when n-over (f n-over))
        t0 (System/nanoTime)
        at-outcome (when at (outcome at))
        wall (ms t0)
        over-outcome (when over (outcome over))]
    (println "BELOW" shape (pr-str (parser/parse-source below)))
    (println "RECORD at-bound" shape (pr-str {:n n-at, :measured (some-> at nesting),
                                              :outcome at-outcome, :wall-ms wall}))
    (check (str "below: " shape " at depth 32 parses (measured " (nesting below) ")")
           (= :yang.cst/ok (outcome below)) nil)
    (check (str "at: " shape " measured exactly " bound " parses; measured " (inc bound)
                " refuses with the exact qualified :parser-budget data")
           (and at over (= :yang.cst/ok at-outcome)
                (= {:diagnostic :parser-budget, :axis :nesting, :bound bound,
                    :measured (inc bound)}
                   over-outcome))
           {:n-at n-at, :n-over n-over, :over over-outcome})))


;; the indent shape is input-quadratic: counted, not inferred from size
(let [s ((shapes "indent") (inc bound))]
  (check "indent depth is counted, not inferred: 257 levels refuse on the nesting axis while far under the input bound"
         (and (< (count (.getBytes ^String s "UTF-8")) cst/input-bound)
              (= :nesting (:axis (outcome s))))
         {:bytes (count s)}))


;; =============================================================================
;; input axis: at / above, mirror and native
;; =============================================================================

(defn- padded
  "A legal source of exactly n UTF-8 bytes: one comment line."
  [n]
  (str "#" (rep (- n 2) "a") "\n"))


(let [at (padded cst/input-bound)
      over (padded (inc cst/input-bound))
      t0 (System/nanoTime)
      at-outcome (outcome at)
      wall (ms t0)
      over-outcome (outcome over)
      [native-st native-msg] (native-status over)]
  (println "RECORD input-at-bound" (pr-str {:bytes (count at), :outcome at-outcome, :wall-ms wall}))
  (check "at: a 4 MiB source parses; 4 MiB + 1 refuses with the exact qualified :parser-budget data"
         (and (= :yang.cst/ok at-outcome)
              (= {:diagnostic :parser-budget, :axis :input, :bound cst/input-bound,
                  :measured (inc cst/input-bound)}
                 over-outcome))
         over-outcome)
  (check "the wrapper refuses an over-bound input itself (status -6) when the jolt mirror is bypassed"
         (and (= -6 native-st)
              (= (str "input " (inc cst/input-bound) " " cst/input-bound) native-msg))
         [native-st native-msg]))


;; =============================================================================
;; above: 10x the old crash depths, deterministic, no crash
;; =============================================================================

(doseq [[label s axis] [["100,000 nested parens" ((shapes "parens") 100000) :nesting]
                        ["100,000 unary prefixes" ((shapes "unary") 100000) :nesting]
                        ["a 5,000-deep INDENT source" ((shapes "indent") 5000) :input]]]
  (let [rs (vec (repeatedly 3 #(outcome s)))]
    (println "RECORD above" label (pr-str (first rs)))
    (check (str "above: " label " refuses (" (name axis) " axis), three identical refusals, no crash")
           (and (apply = rs) (= :parser-budget (:diagnostic (first rs)))
                (= axis (:axis (first rs))))
           (first rs))))


;; the nesting refusal is decided before any ANTLR object exists: the native
;; status for an over-depth input is -7 with the measured/bound message
(let [[st msg] (native-status ((shapes "parens") 100000))]
  (check "the wrapper refuses an over-depth input itself (status -7, \"nesting <measured> <bound>\")"
         (and (= -7 st) (= (str "nesting 100000 " bound) msg))
         [st msg]))


;; =============================================================================
;; the composed REPL: a refusal is operational and the session survives
;; =============================================================================

(let [state0 (frontends/create-state {})
      steps ["(lang :yang/python)" "x = 7" ((shapes "parens") 300) "x * 6"
             "(lang :yang/clojure)" "(+ 1 2)" "(lang :yang/php)" "1 + 2;"]
      [_ texts] (reduce (fn [[st acc] line]
                          (let [[st' text] (repl/eval-input st line)]
                            [st' (conj acc text)]))
                        [state0 []]
                        steps)]
  (println "RECORD repl" (pr-str texts))
  (check "the REPL: a budget refusal is an operational error naming parser-budget, never a syntax error; Python, Clojure and PHP evaluate after it"
         (and (= "Switched to :yang/python" (texts 0))
              (str/includes? (str (texts 2)) "parser-budget: nesting 300 is over the bound")
              (not (str/includes? (str (texts 2)) "SyntaxError"))
              (= "42" (texts 3)) (= "3" (texts 5)) (= "3" (texts 7)))
         texts))


(println (if (zero? @failures) "ALL PASS" (str @failures " FAIL")))
(System/exit (if (zero? @failures) 0 1))
