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
;; The shared ATNs' next-token memo is counted and reported (RECORD), not
;; required to stay flat; see the round-4 report.
(ns jolt-j0c-workers
  (:require
    [clojure.edn :as edn]
    [jolt.ffi :as ffi]
    [yang.antlr.cst :as cst]))


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
  (let [[ls le lc ps pe pc lm pm] (u64s c-static-stats 8)]
    {:caches {:lexer-dfa-states ls, :lexer-dfa-edges le, :lexer-ctx lc,
              :parser-dfa-states ps, :parser-dfa-edges pe, :parser-ctx pc},
     :atn-memo {:lexer lm, :parser pm}}))


(defn- worker-stats
  [w]
  (let [[ls le lc ps pe pc] (u64s #(c-worker-stats @(:yang.antlr.cst/native w) %) 6)]
    {:lexer-dfa-states ls, :lexer-dfa-edges le, :lexer-ctx lc,
     :parser-dfa-states ps, :parser-dfa-edges pe, :parser-ctx pc}))


(defn- parse
  [w source]
  (cst/parse-unit w [:unit 0] source))


;; -- initialization ------------------------------------------------------------

(def init-worker (cst/make-worker profile))
(def s0 (static-stats))
(println "RECORD static caches after initialization" (pr-str s0))


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


(println "RECORD shared ATN next-token memo (lazily filled by the runtime, ATN::nextTokens)"
         (pr-str {:before (:atn-memo s0), :after (:atn-memo s1)}))


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


(cst/close-worker init-worker)
(println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
(System/exit (if (zero? @failures) 0 1))
