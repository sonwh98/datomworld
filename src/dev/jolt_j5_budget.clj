;; J5b (known-gaps row 59; docs/design/yin.repl-jolt-implementation.md §7.6):
;; the native Python parser's budget, in process, under jolt.
;;
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME"/bin/jolt -Srepro -M:jolt:jolt-test src/dev/jolt_j5_budget.clj
;;
;; Needs the test build (bb build:jolt-native), for the seams. Legs:
;;   budget  jolt_parser_budget reports the bounds and the parse thread's
;;           16 MiB stack (C9);
;;   C1'     the review's repros: each refuses (-7) three times with
;;           identical data and the same worker then parses `x * 6`; R1c, the
;;           backslash-joined chained assignment, is flat in the grammar and
;;           parses at 10,000 within 15 s (its packet: the below leg);
;;   C2      per shape, the largest n the predicted table admits parses and
;;           n+1 refuses with exactly {:axis :nesting :bound B :measured B+1
;;           :kind k}, each on a fresh worker (cold caches); the at-bound wall
;;           is recorded (C6);
;;   C3      10x the old crash depths in process, each refused three times
;;           identically; `**` at 100,000 is flat and parses within 60 s (the
;;           R4b replacement; its packet: the below leg); 5,000 INDENT levels refuse
;;           on the input axis;
;;   input   4 MiB parses, 4 MiB + 1 refuses (mirror and native -6);
;;   REPL    a refusal is operational and the session goes on;
;;   C7      a stage whose source refuses a cursor leaves no live worker; the
;;           convenience calls leave none;
;;   C8      rule-names is a function answering the native table.
;; Prints PASS/FAIL/RECORD lines; exits nonzero on a FAIL.
(ns jolt-j5-budget
  (:require
    [clojure.string :as str]
    [dao.stream :as stream]
    [jolt.ffi :as ffi]
    [jolt-j5-shapes :as shapes :refer [rep]]
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


(check "the native parser library is available" (parser/available?) nil)


(ffi/defcfn c-budget "jolt_parser_budget" [:pointer] :int)
(ffi/defcfn c-export "jolt_parser_export" [:pointer :pointer :size_t :pointer :pointer] :int :blocking)
(ffi/defcfn c-free "jolt_parser_free" [:pointer] :void)
(ffi/defcfn c-live-workers "jolt_parser_live_workers" [] :int64)
(ffi/defcfn c-last-stats "jolt_parser_last_stats" [:pointer] :int)


(defn- last-max-rule
  "The last parse's deepest rule frame (the jolt_parser_last_stats seam)."
  []
  (let [p (ffi/alloc 56)]
    (try (c-last-stats p)
         (first (ffi/read-array p :uint64 7))
         (finally (ffi/free p)))))


(defn- ms
  [t0]
  (long (/ (- (System/nanoTime) t0) 1e6)))


;; The ruling's wall limits are laptop limits (macOS arm64). Other hosts
;; record the walls and assert only 6x the laptop limit, so a hang or a true
;; regression still fails. jolt's os.name/os.arch can answer nil (cst.jolt).
(def laptop?
  (let [os (str/lower-case (or (System/getProperty "os.name") ""))
        arch (System/getProperty "os.arch")]
    (boolean (and (str/includes? os "mac") (#{"aarch64" "arm64"} arch)))))


(defn- wall-limit
  [laptop-ms]
  (if laptop? laptop-ms (* 6 laptop-ms)))


;; =============================================================================
;; the budget the wrapper reports
;; =============================================================================

(def budget
  (let [p (ffi/alloc 40)]
    (try (let [st (c-budget p)]
           (assoc (zipmap [:input-bytes :rule :look :full :stack]
                          (vec (ffi/read-array p :uint64 5)))
                  :status st))
         (finally (ffi/free p)))))


(println "RECORD budget" (pr-str budget))


(check "the wrapper's bounds are the ruling's (input 4 MiB, rule 8192, lookahead 4096, full-LL 256), the jolt mirror equal, and a parse thread has 16 MiB of stack"
       (and (zero? (:status budget))
            (= 4194304 (:input-bytes budget) cst/input-bound)
            (= shapes/bounds (select-keys budget [:rule :look :full]))
            (>= (:stack budget) (* 16 1024 1024)))
       budget)


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
                  (try (when-not (zero? status) (String. ^bytes (ffi/read-array p len) "UTF-8"))
                       (finally (c-free p))))]
        [status msg])
      (finally (ffi/free in) (ffi/free out-cell) (ffi/free len-cell)
               (cst/close-worker w)))))


(defn- outcome
  "The parse's :yang.cst/outcome, or the refusal's ex-data (a fresh worker)."
  ([s] (try (:yang.cst/outcome (parser/parse-source s))
            (catch Exception e (or (ex-data e) {:threw (ex-message e)}))))
  ([w s] (try (:yang.cst/outcome (parser/parse-source w [:unit 0] s))
              (catch Exception e (or (ex-data e) {:threw (ex-message e)})))))


(defn- refusal
  [kind bound]
  {:diagnostic :parser-budget, :axis :nesting, :bound bound,
   :measured (inc bound), :kind kind})


;; =============================================================================
;; C1: the review's repros
;; =============================================================================

(def scaled-r3
  ;; P1-3 scaled: 1,024 nots per level, 16 levels (16,384 not_test frames)
  (str (rep 16 (str (rep 1024 "not ") "(0 or ")) "0" (rep 16 ")") "\n"))


(doseq [[label src] [["R1a CR-ended comment, 100,000 parens" ((shapes/shapes "r1a") 100000)]
                     ["R1b form-feed-ended comment, 100,000 parens" ((shapes/shapes "r1b") 100000)]
                     ["R2a 100,000 comprehension for clauses" ((shapes/shapes "r2a") 100000)]
                     ["R2b 50,000 for-if clauses" ((shapes/shapes "r2b") 50000)]
                     ["R3 not runs inside 128 brackets (the review's exact source)" ((shapes/shapes "r3") 128)]
                     ["R3 scaled: 1,024 nots per level, 16 levels" scaled-r3]
                     ["the backslash-continued ternary, 100,000 lines" ((shapes/shapes "bs-ternary") 100000)]]]
  (let [w (parser/make-worker)
        rs (vec (repeatedly 3 #(outcome w src)))
        after (outcome w "x * 6\n")]
    (parser/close-worker w)
    (println "RECORD C1" label (pr-str (first rs)))
    (check (str "C1 " label ": refuses (-7, rule) three times identically, then the same worker parses x * 6")
           (and (apply = rs) (= (refusal :rule 8192) (first rs)) (= :yang.cst/ok after))
           {:first (first rs), :after after})))


;; the flat `not` chain at 1,024 is a parse witness (14 + 1,024 rule frames,
;; under the bound): it parses three times on one worker, its deepest rule
;; frame is 1,038 after each, then x * 6 parses; its packet: the below leg
(let [src ((shapes/shapes "r3-not") 1024)
      w (parser/make-worker)
      rs (vec (repeatedly 3 #(let [r (outcome w src)] [r (last-max-rule)])))
      after (outcome w "x * 6\n")]
  (parser/close-worker w)
  (println "RECORD C1 not-1024" (pr-str {:runs rs, :after after}))
  (check "C1' not 1,024: parses three times on one worker, the deepest rule frame 1,038 after each, then x * 6 parses"
         (and (= (repeat 3 [:yang.cst/ok 1038]) rs) (= :yang.cst/ok after))
         {:runs rs, :after after}))


;; R1c is the join witness, not a nesting one (one flat chained assignment,
;; 16 frames): at 10,000 lines it parses within 15 s; its packet is compared
;; with the JVM's in the below leg
(let [src ((shapes/shapes "r1c") 10000)
      t0 (System/nanoTime)
      r (outcome src)
      wall (ms t0)]
  (println "RECORD C1 R1c" (pr-str {:outcome r, :bytes (count src), :wall-ms wall}))
  (check (str "C1' R1c at 10,000: the backslash-joined chained assignment parses within "
              (/ (wall-limit 15000) 1000) " s (" (if laptop? "the laptop limit" "6x the laptop limit") ")")
         (and (= :yang.cst/ok r) (<= wall (wall-limit 15000)))
         {:outcome r, :wall-ms wall, :laptop laptop?}))


(let [[st msg] (native-status ((shapes/shapes "r1a") 100000))]
  (check "C1 the wrapper's own answer: status -7, \"nesting 8193 8192 rule\""
         (and (= -7 st) (= "nesting 8193 8192 rule" msg)) [st msg]))


;; =============================================================================
;; C2: at and over, per shape, against the predicted table
;; =============================================================================

(doseq [shape (sort (keys shapes/predicted))]
  (let [f (shapes/shapes shape)
        {:keys [kind], expect :at, :or {expect :yang.cst/ok}} (shapes/predicted shape)
        n (shapes/largest-parsing shape)
        bound (shapes/bounds kind)
        t0 (System/nanoTime)
        at (outcome (f n))
        wall (ms t0)
        over (outcome (f (inc n)))]
    (println "RECORD at-bound" shape (pr-str {:n n, :kind kind, :outcome at, :wall-ms wall}))
    (check (str "C2 " shape ": n=" n " (predicted) is not refused (" (name expect)
                "); n+1 refuses with exactly bound "
                bound ", measured " (inc bound) ", kind " (if (= :rule kind) :rule :lookahead))
           (and (= expect at)
                (= (refusal (if (= :rule kind) :rule :lookahead) bound) over))
           {:at at, :over over, :wall-ms wall})))


;; =============================================================================
;; C3: 10x the old crash depths, deterministic, no crash
;; =============================================================================

(doseq [[label shape n kind] [["100,000 nested parens" "parens" 100000 :rule]
                              ["100,000 nots" "not" 100000 :rule]
                              ["100,000 minus signs" "unary" 100000 :full]
                              ["100,000 lambdas" "lambda" 100000 :rule]
                              ["100,000 ternaries" "ternary" 100000 :rule]
                              ["100,000 nested calls" "call" 100000 :look]
                              ["100,000 comprehension clauses" "comp-for" 100000 :rule]]]
  (let [src ((shapes/shapes shape) n)
        rs (vec (repeatedly 3 #(outcome src)))]
    (println "RECORD above" label (pr-str (first rs)))
    (check (str "C3 " label ": refused three times identically, no crash")
           (and (apply = rs)
                (= (refusal (if (= :rule kind) :rule :lookahead) (shapes/bounds kind)) (first rs)))
           (first rs))))


;; the R4b replacement: a 100,000-deep tree in linear time; its packet is
;; compared with the JVM's in the below leg
(let [src ((shapes/shapes "power") 100000)
      t0 (System/nanoTime)
      r (outcome src)
      wall (ms t0)]
  (println "RECORD above power" (pr-str {:outcome r, :wall-ms wall}))
  (check (str "C1'/C3 100,000 `**`: flat in the generated parser (an iterative loop, a 100,000-deep tree), it parses within "
              (/ (wall-limit 60000) 1000) " s (" (if laptop? "the laptop limit" "6x the laptop limit") "), no crash")
         (and (= :yang.cst/ok r) (<= wall (wall-limit 60000)))
         {:outcome r, :wall-ms wall, :laptop laptop?}))


(let [s ((shapes/shapes "indent") 5000)
      rs (vec (repeatedly 3 #(outcome s)))]
  (check "C3 a 5,000-deep INDENT source (12.5 MB): refused on the input axis, three identical refusals"
         (and (apply = rs) (= :input (:axis (first rs)))) (first rs)))


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
  (check "input: a 4 MiB source parses; 4 MiB + 1 refuses with the exact qualified :parser-budget data"
         (and (= :yang.cst/ok at-outcome)
              (= {:diagnostic :parser-budget, :axis :input, :bound cst/input-bound,
                  :measured (inc cst/input-bound)}
                 over-outcome))
         over-outcome)
  (check "input: the wrapper refuses an over-bound input itself (status -6) when the jolt mirror is bypassed"
         (and (= -6 native-st)
              (= (str "input " (inc cst/input-bound) " " cst/input-bound) native-msg))
         [native-st native-msg]))


;; =============================================================================
;; the composed REPL: a refusal is operational and the session survives
;; =============================================================================

(let [state0 (frontends/create-state {})
      steps ["(lang :yang/python)" "x = 7" ((shapes/shapes "parens") 1000) "x * 6"
             "(lang :yang/clojure)" "(+ 1 2)" "(lang :yang/php)" "1 + 2;"]
      [_ texts] (reduce (fn [[st acc] line]
                          (let [[st' text] (repl/eval-input st line)]
                            [st' (conj acc text)]))
                        [state0 []]
                        steps)]
  (println "RECORD repl" (pr-str texts))
  (check "the REPL: a budget refusal is an operational error naming parser-budget, never a syntax error; Python, Clojure and PHP evaluate after it"
         (and (= "Switched to :yang/python" (texts 0))
              (str/includes? (str (texts 2)) "parser-budget: nesting 8193 is over the bound 8192 rule frames")
              (not (str/includes? (str (texts 2)) "SyntaxError"))
              (= "42" (texts 3)) (= "3" (texts 5)) (= "3" (texts 7)))
         texts))


;; =============================================================================
;; C7: lifecycle
;; =============================================================================

(def refusing-source
  (reify stream/IDaoStreamReader
    (cursor [_ _anchor] {:dao.stream/outcome :dao.stream/closed})

    (next [_ _cursor] {:dao.stream/outcome :dao.stream/closed})))


(let [before (c-live-workers)
      r (try (parser/open-stage refusing-source nil) :opened
             (catch Exception e (ex-message e)))
      after (c-live-workers)]
  (check "C7 a stage whose source refuses a cursor: open-stage throws, and no worker was made (live workers unchanged)"
         (and (string? r) (str/includes? r "refused a cursor") (= before after))
         {:threw r, :live-before before, :live-after after}))


(let [before (c-live-workers)
      _ (parser/parse-source "x = 1\n")
      _ (parser/first-failure "x = (\n")
      _ (outcome ((shapes/shapes "parens") 1000))
      after (c-live-workers)]
  (check "C7 the convenience calls (a parse, a probe, a refusal) dispose their workers"
         (= before after) {:live-before before, :live-after after}))


;; =============================================================================
;; C8: the rule-name table is a function
;; =============================================================================

(let [names (parser/rule-names)]
  (check "C8 parser/rule-names is a function answering the native table"
         (and (fn? parser/rule-names) (vector? names) (= "single_input" (first names))
              (some #{"file_input"} names))
         {:count (count names)}))


(println (if (zero? @failures) "ALL PASS" (str @failures " FAIL")))
(System/exit (if (zero? @failures) 0 1))
