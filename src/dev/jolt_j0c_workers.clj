;; J0c round 4, step A: worker isolation for the jolt parser adapter
;; (docs/design/yin.repl-jolt-implementation.md §7.4).
;;
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME"/bin/jolt -Srepro -M:jolt src/dev/jolt_j0c_workers.clj
;;
;; Needs the test build (bb build:jolt-native, JOLT_PARSER_TEST_SEAMS=ON) for
;; the cache counters. Over every jolt_j0c_corpus.edn source:
;;
;;   1. isolated: one fresh worker per source gives the reference packet;
;;   2. two workers interleaved on ONE thread (A forward, B backward,
;;      alternating calls, three passes) must give the isolated packets;
;;   3. independent workers on separate threads (four threads, three passes
;;      each, started together) must give the isolated packets;
;;   4. the generated static caches (DFA states and edges, context-cache
;;      entries) must not grow during any of that after initialization, while
;;      the workers' own caches do (positive control), and a parse through the
;;      round-1 entry, which uses the static caches, does grow them (the
;;      counters are live);
;;   5. a worker serves one thread at a time: a second call while a long parse
;;      runs on it is refused (status -5); a closed worker refuses parses and a
;;      second close is a no-op.
;;
;; The memos the runtime fills lazily in shared state (each ATN state's
;; next-token set, each lexer action's hash, PredictionContext::EMPTY's hash)
;; must be complete when the first worker exists and must not change during
;; recognition (round 5; the wrapper fills them once in published_atns).
(ns jolt-j0c-workers
  (:require
    [clojure.edn :as edn]
    [jolt.ffi :as ffi]
    [yang.antlr.cst :as cst]))


;; J5: yang.antlr.cst loads the native library on first use; load it before
;; any raw jolt_parser_* binding below is called
(when-not (cst/available?)
  (println "FAIL the native parser library did not load")
  (System/exit 1))


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(def corpus (edn/read-string (slurp "test/yang/python/antlr/fixtures/jolt_j0c_corpus.edn")))
(def sources (mapv :source (:entries corpus)))
(def profile {:grammar-id "jolt-j0c-workers"})


(when-not (ffi/find-symbol "jolt_parser_static_cache_stats")
  (println "FAIL the library has no test seams; build it with bb build:jolt-native (JOLT_PARSER_TEST_SEAMS=ON)")
  (System/exit 1))


(ffi/defcfn c-static-stats "jolt_parser_static_cache_stats" [:pointer] :int)
(ffi/defcfn c-worker-stats "jolt_parser_worker_cache_stats" [:pointer :pointer] :int)
(ffi/defcfn c-parse-static "jolt_parser_parse" [:pointer :size_t :pointer :pointer] :int :blocking)
(ffi/defcfn c-free "jolt_parser_free" [:pointer] :void)


(defn- u64s
  [f n]
  (let [cell (ffi/alloc (* 8 n))]
    (try
      (when-not (zero? (f cell)) (throw (ex-info "stats call failed" {})))
      (mapv #(ffi/read cell :uint64 (* 8 %)) (range n))
      (finally (ffi/free cell)))))


(defn- static-stats
  []
  (let [[ls le lc ps pe pc lm pm lstates pstates ah an eh] (u64s c-static-stats 13)]
    {:caches {:lexer-dfa-states ls, :lexer-dfa-edges le, :lexer-ctx lc,
              :parser-dfa-states ps, :parser-dfa-edges pe, :parser-ctx pc},
     :memos {:lexer-next-tokens lm, :parser-next-tokens pm,
             :lexer-action-hashes ah, :empty-context-hash eh},
     :memo-sizes {:lexer-next-tokens lstates, :parser-next-tokens pstates,
                  :lexer-action-hashes an, :empty-context-hash 1}}))


(defn- worker-stats
  [w]
  (let [[ls le lc ps pe pc] (u64s #(c-worker-stats @(:yang.antlr.cst/native w) %) 6)]
    {:lexer-dfa-states ls, :lexer-dfa-edges le, :lexer-ctx lc,
     :parser-dfa-states ps, :parser-dfa-edges pe, :parser-ctx pc}))


(defn- parse
  [w source]
  (cst/parse-unit w [:unit 0] source))


;; -- initialization ------------------------------------------------------------

;; nothing has made a worker or parsed yet: the memo counters must read the
;; unfilled state (positive control: they observe the runtime's fields)
(def s-pre (static-stats))
(println "RECORD shared memos before the first worker" (pr-str (:memos s-pre)))


(def init-worker (cst/make-worker profile))
(def s0 (static-stats))
(println "RECORD static caches after initialization" (pr-str s0))


(check "the shared memo counters read empty before the first worker (they are live)"
       (every? zero? (vals (:memos s-pre)))
       (:memos s-pre))


(check "every shared lazily filled memo was filled before the first worker was published"
       (= (:memos s0) (:memo-sizes s0))
       {:filled (:memos s0), :of (:memo-sizes s0)})


;; -- 1. isolated ---------------------------------------------------------------

(def isolated
  (mapv (fn [src]
          (let [w (cst/make-worker profile)]
            (try (parse w src) (finally (cst/close-worker w)))))
        sources))


;; -- 2. two workers, one thread ------------------------------------------------

(let [a (cst/make-worker profile)
      b (cst/make-worker profile)
      n (count sources)
      out (volatile! {:a {} :b {}})]
  (dotimes [_ 3]
    (dotimes [i n]
      (let [j (- n 1 i)]
        (vswap! out update :a assoc i (parse a (sources i)))
        (vswap! out update :b assoc j (parse b (sources j))))))
  (let [{ra :a rb :b} @out
        bad (vec (keep (fn [i] (when-not (= (isolated i) (ra i) (rb i)) i)) (range n)))]
    (check "two workers interleaved on one thread (3 passes, opposite orders) give the isolated packets"
           (empty? bad) {:sources n, :differ bad}))
  (let [wa (worker-stats a)]
    (check "the workers' own caches filled (positive control)"
           (and (pos? (:lexer-dfa-states wa)) (pos? (:parser-dfa-states wa))
                (pos? (:parser-ctx wa)))
           wa))
  (cst/close-worker a)
  (cst/close-worker b))


;; -- 3. workers on separate threads --------------------------------------------

(let [n (count sources)
      threads 4
      results (vec (repeatedly threads #(atom nil)))
      gate (promise)
      ths (mapv (fn [k]
                  (Thread.
                    (fn []
                      @gate
                      (let [w (cst/make-worker profile)]
                        (try
                          (reset! (results k)
                                  (vec (for [pass (range 3)
                                             i (if (even? (+ k pass)) (range n) (reverse (range n)))]
                                         [i (parse w (sources i))])))
                          (catch Throwable e (reset! (results k) {:thrown (ex-message e)}))
                          (finally (cst/close-worker w)))))))
                (range threads))]
  (doseq [t ths] (.start t))
  (deliver gate true)
  (doseq [t ths] (.join t))
  (let [bad (vec (for [k (range threads)
                       :let [r @(results k)]
                       :when (or (map? r)
                                 (some (fn [[i p]] (not= p (isolated i))) r))]
                   [k (if (map? r) r (count (filter (fn [[i p]] (not= p (isolated i))) r)))]))]
    (check (str threads " workers on " threads " threads (3 passes each, mixed orders) give the isolated packets")
           (empty? bad) {:threads threads, :parses-each (* 3 n), :bad bad})))


;; -- 4. no static growth -------------------------------------------------------

(def s1 (static-stats))
(println "RECORD static caches after every worker parse" (pr-str s1))


(check "the generated static DFA and context caches did not grow during worker recognition"
       (= (:caches s0) (:caches s1))
       {:before (:caches s0), :after (:caches s1)})


(check "the shared memos (ATN next-token sets, lexer-action hashes, the EMPTY context's hash) did not grow during worker recognition"
       (= (:memos s0) (:memos s1))
       {:before (:memos s0), :after (:memos s1)})


(let [src (.getBytes ^String (sources 7) "UTF-8")
      n (alength src)
      in (ffi/alloc n)
      out-cell (ffi/alloc :pointer)
      len-cell (ffi/alloc :size_t)]
  (try
    (ffi/write-array in src)
    (c-parse-static in n out-cell len-cell)
    (c-free (ffi/read out-cell :pointer))
    (finally (ffi/free in) (ffi/free out-cell) (ffi/free len-cell))))


(def s2 (static-stats))


(check "a parse through the round-1 entry (static caches) does grow them: the counters are live"
       (and (> (get-in s2 [:caches :parser-dfa-states]) (get-in s1 [:caches :parser-dfa-states]))
            (> (get-in s2 [:caches :lexer-dfa-states]) (get-in s1 [:caches :lexer-dfa-states])))
       {:after-static-parse (:caches s2)})


(check "the shared memos did not change during the round-1 parse either"
       (= (:memos s0) (:memos s2))
       {:after-static-parse (:memos s2)})


;; -- 5. one thread at a time; close ---------------------------------------------

(let [w (cst/make-worker profile)
      big (apply str (repeat 20000 "x = (1 + 2) * 3\n"))
      done (atom false)
      long-result (atom nil)
      th (Thread. (fn []
                    (try (reset! long-result (count (:nodes (cst/export-records w big))))
                         (catch Throwable e (reset! long-result (ex-data e)))
                         (finally (reset! done true)))))
      _ (.start th)
      ;; let the long call reach native code first (jolt encodes the source
      ;; before the call), then poll with small parses until one is refused
      ;; or the long one ends
      _ (Thread/sleep 300)
      attempts (loop [k 0 parsed 0]
                 (let [r (try (cst/export-records w "1 + 2\n") :parsed
                              (catch Throwable e (ex-data e)))
                       parsed (if (= :parsed r) (inc parsed) parsed)]
                   (cond (= -5 (:status r)) {:refused-at-attempt k, :parsed-before parsed}
                         @done {:never-refused k, :parsed parsed}
                         :else (recur (inc k) parsed))))
      _ (.join th)]
  (check "a second call while a long parse runs on the same worker is refused with status -5, and the long parse succeeds"
         (and (contains? attempts :refused-at-attempt) (integer? @long-result))
         (assoc attempts :long-parse-records @long-result))
  (check "after the long parse the worker parses again"
         (= (isolated 0) (parse w (sources 0))) :ok)
  (cst/close-worker w)
  (cst/close-worker w)
  (check "a closed worker refuses parses; a second close was a no-op"
         (= "jolt parser worker is closed"
            (try (parse w "1\n") nil (catch Throwable e (ex-message e))))
         :closed))


;; -- 6. J5: the stage owns its worker; reset remakes it, close disposes it -------

(require '[yang.python.antlr.parser :as parser])


(let [s0 {:yang.python.antlr.parser/worker (parser/make-worker)}
      w0 (:yang.python.antlr.parser/worker s0)
      before (parse w0 (sources 0))
      s1 (parser/reset-stage s0)
      w1 (:yang.python.antlr.parser/worker s1)
      old (try (parse w0 (sources 0)) nil (catch Throwable e (ex-message e)))
      after (parse w1 (sources 0))]
  ;; the stage's worker carries the Python profile's grammar id, so its
  ;; packet is compared with itself across the reset, not with `isolated`
  (check "reset-stage disposes the stage's worker (it then refuses) and installs a fresh one that answers the same packet"
         (and (some? before) (= before after) (not (identical? w0 w1))
              (= "jolt parser worker is closed" old))
         {:old old, :same (= before after)})
  (parser/close-stage s1)
  (check "close-stage disposes the stage's worker"
         (= "jolt parser worker is closed"
            (try (parse w1 (sources 0)) nil (catch Throwable e (ex-message e))))
         :closed))


;; -- 7. J5: two composed REPL sessions, one reset, isolated ---------------------

(require '[yin.repl :as repl] '[yin.repl.frontends :as frontends])


(let [run (fn [st line] (repl/eval-input st line))
      fresh #(first (run (frontends/create-state {}) "(lang :yang/python)"))
      [a1 ta1] (run (fresh) "x = 10")
      [b1 tb1] (run (fresh) "x = 20")
      [a2 ta2] (run a1 "x + 1")
      [b2 tb2] (run b1 "x + 1")
      [a3 ta3] (run a2 "(reset)")
      [b3 tb3] (run b2 "x * 2")
      [a4 ta4] (run (first (run a3 "(lang :yang/python)")) "y = 3")
      [_ ta5] (run a4 "y + 1")
      [_ tb4] (run b3 "x")]
  (check "two composed REPL sessions interleave Python with independent results, and a reset of one leaves the other's state intact"
         (and (= "11" ta2) (= "21" tb2) (= "40" tb3) (= "4" ta5) (= "20" tb4))
         {:a [ta1 ta2 ta3 ta5], :b [tb1 tb2 tb3 tb4]}))


(cst/close-worker init-worker)
(println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
(System/exit (if (zero? @failures) 0 1))
