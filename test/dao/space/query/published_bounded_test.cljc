(ns dao.space.query.published-bounded-test
  "Bounded execution over published indexes (Phase 4b-3): the checked
   candidate producer, the shared admission policy, and the guarantee that
   once a deadline passes during a node fetch, that fetch may finish, its
   node is discarded, and no later query-controlled fetch begins.

   Every source here is a normal published value (`published-db`) over an
   EXPLICIT instrumented content store, never `with-redefs`: the wrapper
   logs {:event :fetch-start|:fetch-return|:fetch-error :ordinal n :address
   a} for each get, and can run a callback DURING a chosen fetch (the tests
   use it to advance a fake clock). Constructor manifest reads are cleared
   before a query so only query reads are counted. Time comes from fake
   `now-fn`s, never sleeps, so the same assertions run on every host."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.data.btree :as bt]
            [dao.data.btree.storage :as bts]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.space.query.datomic :as dq]
            [dao.space.query.execution :as exec]))


;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private people
  (vec
    (concat
      (mapcat (fn [e]
                [[e :p/name (str "n" e) 1 1]
                 [e :p/age (+ 20 (mod e 7)) 1 1]
                 [e :p/friend (inc e) 1 1]])
              (range 1 41))
      ;; retraction and re-assertion
      [[5 :p/age 25 2 0] [5 :p/age 99 3 1]]
      ;; comparator-tied, content-distinct values: 1 vs 1.0
      [[100 :n/v 1 1 1] [101 :n/v (cbor/float64 1) 1 1] [102 :n/v 1 1 1]])))


(def ^:private others
  (mapv (fn [e] [e :q/tag (str "t" (mod e 3)) 1 1]) (range 1 25)))


(defn- build!
  "Hand-publish d5 datoms into `store` at bf 4; returns the manifest address."
  [store datoms]
  (let [storage (bts/kv-storage store {:branching-factor 4})
        tree (fn [cmp]
               (bt/store-tree (bt/from-sequential cmp datoms {:branching-factor 4})
                              storage))
        eavt (bt/from-sequential index/eavt-cmp datoms {:branching-factor 4})
        manifest {:indexes {:eavt (tree index/eavt-cmp)
                            :aevt (tree index/aevt-cmp)
                            :avet (tree index/avet-cmp)
                            :vaet (tree index/vaet-cmp)}
                  :count (bt/count eavt)
                  :branching-factor 4}]
    (jing/materialize! store manifest)))


(defn- instrumented
  "Wrap a content store: log every get / put / close as an event in the
   volatile `events`, and call (on-fetch ordinal address) DURING each get."
  [store events on-fetch]
  (let [fetch-count (fn [] (count (filter #(= :fetch-start (:event %)) @events)))]
    (assoc store
           :get-bytes-fn
           (fn [address not-found]
             (let [n (inc (fetch-count))]
               (vswap! events conj {:event :fetch-start :ordinal n :address address})
               (when on-fetch (on-fetch n address))
               (try (let [r ((:get-bytes-fn store) address not-found)]
                      (vswap! events conj {:event :fetch-return :ordinal n :address address})
                      r)
                    (catch #?(:cljd Object :clj Throwable :cljs :default) e
                      (vswap! events conj {:event :fetch-error :ordinal n :address address})
                      (throw e)))))
           :put-bytes-fn
           (fn [a bs]
             (vswap! events conj {:event :put :address a})
             ((:put-bytes-fn store) a bs))
           :close-fn
           (fn []
             (vswap! events conj {:event :close})
             nil))))


(def ^:private built
  "Datasets are published once (building trees is the slow part); every
   fixture wraps the SAME immutable base store in its own instrumentation."
  (memoize (fn [datoms]
             (let [base (mem/create-content-mem)]
               {:base base, :address (build! base datoms)}))))


(defn- fixture
  "A published source with an instrumented store. `fire` is a function of
   [clock ordinal] called during each fetch so tests can move time."
  ([datoms] (fixture datoms nil))
  ([datoms fire]
   (let [{:keys [base address]} (built datoms)
         events (volatile! [])
         clock (volatile! 0)
         armed (volatile! false)          ; the constructor's manifest read never fires
         wrapped (instrumented base events
                               (when fire (fn [n _] (when @armed (fire clock n)))))
         pub (query/published-db wrapped address)
         constructor-events @events]
     (vreset! events [])
     (vreset! armed true)
     {:pub pub
      :view (query/current pub)
      :events events
      :clock clock
      :address address
      :constructor-events constructor-events})))


(defn- fetches
  [fx]
  (count (filter #(= :fetch-start (:event %)) @(:events fx))))


(defn- reset-events!
  [fx]
  (vreset! (:events fx) []))


(defn- clock-fn
  [fx]
  (fn [] @(:clock fx)))


(defn- try-q
  "Run the adapter; {:result r} or {:error ex-data :exception e}."
  [m]
  (try {:result (dq/q m)}
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         {:error (ex-data e) :exception e})))


(defn- bounded
  "A bounded adapter call over the fixture's view (plus extra args)."
  [fx query opts & more-args]
  (try-q (merge {:query query
                 :args (into [(:view fx)] more-args)
                 :timeout 1000
                 ::dq/now-fn (clock-fn fx)}
                opts)))


(defn- steps
  "A bounded call with a work cap."
  [fx query cap & more-args]
  (try-q {:query query :args (into [(:view fx)] more-args) ::dq/max-steps cap}))


(defn- err
  [r]
  (::dq/error (:error r)))


(def ^:private materialized
  (query/current (query/relation people)))


;; ---------------------------------------------------------------------------
;; Access paths
;; ---------------------------------------------------------------------------

(def ^:private paths
  {:eavt '[:find ?v :where [20 :p/age ?v]]
   :aevt '[:find ?e :where [?e :p/age _]]
   :avet '[:find ?e :where [?e :p/age 22]]
   :vaet '[:find ?e ?a :where [?e ?a 33]]
   :full '[:find (count ?e) :where [?e _ _]]
   :join '[:find ?n :where [?e :p/age 22] [?e :p/name ?n]]})


(deftest bounded-answers-equal-unbounded-and-materialized-answers
  (let [fx (fixture people)
        queries (conj (vec (vals paths))
                      '[:find (pull ?e [:p/name :p/age]) :where [?e :p/age 24]]
                      '[:find (pull ?e [:p/name {:p/friend [:p/name]}]) :where [?e :p/age 23]]
                      '[:find (pull ?e [*]) :where [?e :p/name "n7"]]
                      '[:find ?e :where [?e :p/name _] [(missing? $ ?e :p/friend)]]
                      '[:find ?e ?f :where [?e :p/name "n3"] [(get-else $ ?e :p/nick "none") ?f]]
                      '[:find ?e :where [?e :p/name _] (not [?e :p/age 22])]
                      '[:find ?e :where (or [?e :p/age 20] [?e :p/age 26])]
                      '[:find ?age (count ?e) :where [?e :p/age ?age]]
                      '[:find ?e :where [?e :n/v 1]]
                      '[:find ?e :where [?e :p/age 25]]
                      '[:find ?e :where [?e :p/age 99]])
        cap {::dq/max-steps 100000000}]
    (doseq [q queries]
      (let [unbounded (set (dq/q q (:view fx)))
            checked (set (dq/q (merge {:query q :args [(:view fx)]} cap)))
            mat-bounded (set (dq/q (merge {:query q :args [materialized]} cap)))
            mat-unbounded (set (dq/q q materialized))]
        (is (= mat-unbounded unbounded checked mat-bounded) (pr-str q))))
    (testing "retraction and kind-strict matching, explicitly"
      (is (= #{[12] [19] [26] [33] [40]}
             (set (dq/q {:query '[:find ?e :where [?e :p/age 25]] :args [(:view fx)]
                         ::dq/max-steps 100000000})))
          "entity 5's retracted age 25 is gone (it moved to 99); the others remain")
      (is (= #{[100] [102]} (set (dq/q {:query '[:find ?e :where [?e :n/v 1]] :args [(:view fx)]
                                        ::dq/max-steps 100000000})))
          "the tied 1.0 is a candidate but not a kind-strict match"))
    (testing "the legacy rows delay was never forced"
      (is (not (realized? (:rows (:pub fx))))))))


(deftest constructor-reads-are-distinguished-from-query-reads
  (let [fx (fixture people)]
    (is (= [:fetch-start :fetch-return]
           (mapv :event (:constructor-events fx)))
        "the constructor made exactly the one manifest read")
    (is (= (:address fx) (:address (first (:constructor-events fx)))))
    (is (zero? (fetches fx)) "events were cleared; the query counts start at zero")
    (steps fx (:eavt paths) 100000)
    (is (pos? (fetches fx)))
    (is (every? #(not= (:address fx) (:address %)) @(:events fx))
        "a query never re-reads the manifest")))


;; ---------------------------------------------------------------------------
;; The overshoot guarantee, per access path and fetch ordinal
;; ---------------------------------------------------------------------------

(defn- total-fetches
  [path-query]
  (let [fx (fixture people)]
    (steps fx path-query 100000000)
    (fetches fx)))


(deftest expiry-during-fetch-n-allows-no-fetch-n-plus-1-and-discards-the-node
  (doseq [[path q] paths]
    (let [total (total-fetches q)]
      (is (pos? total) (str path))
      (doseq [n (distinct [1 (min 2 total) (max 1 (dec total)) total])]
        (let [fx (fixture people (fn [clock ordinal]
                                   (when (= ordinal n) (vreset! clock 5000))))
              r (bounded fx q {})]
          (testing (str path " fetch " n " of " total)
            (is (= :timeout (err r)) "the deadline passed during that fetch")
            (is (= n (fetches fx)) "that fetch finished and no later fetch began")
            (is (= [:fetch-start :fetch-return]
                   (mapv :event (take-last 2 @(:events fx))))
                "the in-flight read ran to completion")
            (is (nil? (:result r)) "no partial relation")
            (is (every? #(not= :fetch-error (:event %)) @(:events fx)))))))))


(deftest expiry-before-the-first-read-fetches-nothing
  (doseq [[path q] paths]
    (let [fx (fixture people)
          _ (vreset! (:clock fx) 0)
          counter (volatile! 0)
          ;; the deadline is minted at call 1; every later reading is past it
          now (fn [] (if (= 1 (vswap! counter inc)) 0 100000))
          r (try-q {:query q :args [(:view fx)] :timeout 1000 ::dq/now-fn now})]
      (is (= :timeout (err r)) (str path))
      (is (zero? (fetches fx)) (str path " read nothing")))))


(deftest work-exhaustion-before-a-node-read-fetches-nothing-for-it
  (doseq [[path q] paths]
    (let [runs (mapv (fn [cap]
                       (let [fx (fixture people)
                             r (steps fx q cap)]
                         {:cap cap :err (err r) :fetches (fetches fx) :ok (contains? r :result)}))
                     (range 1 160))
          exhausted (filterv #(= :step-budget-exhausted (:err %)) runs)
          first-with-fetch (first (filter #(pos? (:fetches %)) exhausted))]
      (is (seq exhausted) (str path))
      (is (zero? (:fetches (first exhausted))) (str path " small caps read nothing"))
      (is (some? first-with-fetch) (str path " eventually reads"))
      (testing (str path " each fetch is preceded by its own charged unit")
        (is (every? (fn [{:keys [cap fetches]}] (<= (* 2 fetches) cap)) exhausted)
            ":tree-step and :node-read are charged before every fetch")
        (is (apply <= (map :fetches runs)) "more allowance never means fewer fetches")))))


(deftest cursor-local-advancement-still-observes-expiry
  (let [q (:full paths)
        fx0 (fixture people)
        _ (bounded fx0 q {})
        outcomes (mapv (fn [k]
                         (let [fx (fixture people)
                               n (volatile! 0)
                               ;; live for the first k readings after the mint, then expired
                               now (fn [] (if (<= (vswap! n inc) (inc k)) 0 100000))
                               r (try-q {:query q :args [(:view fx)] :timeout 1000 ::dq/now-fn now})]
                           [k (err r) (fetches fx)]))
                       (range 1 200))
        timeouts (filter #(= :timeout (second %)) outcomes)
        by-fetches (group-by #(nth % 2) timeouts)]
    (is (seq timeouts))
    (is (some (fn [[f xs]] (and (pos? f) (> (count xs) 1))) by-fetches)
        "several different expiry points between two fetches: advancing inside a resident leaf is observed")))


;; ---------------------------------------------------------------------------
;; Shared allowance across scans and nested evaluation
;; ---------------------------------------------------------------------------

(defn- minimal-cap
  "The smallest work cap under which q completes (bisection: completion is
   monotone in the cap)."
  [fx-fn q & more-args]
  (let [ok? (fn [cap] (contains? (apply steps (fx-fn) q cap more-args) :result))]
    (loop [lo 1
           hi 100000]
      (if (>= lo hi)
        lo
        (let [mid (quot (+ lo hi) 2)]
          (if (ok? mid) (recur lo mid) (recur (inc mid) hi)))))))


(deftest repeated-scans-and-nested-evaluation-share-one-allowance
  (let [single (minimal-cap #(fixture people) '[:find ?e :where [?e :p/age 22]])
        join (minimal-cap #(fixture people) (:join paths))
        neg (minimal-cap #(fixture people)
                         '[:find ?e :where [?e :p/age 22] (not-join [?e] [?e :p/name "n1"])])
        rules '[[(reach ?a ?b) [?a :p/friend ?b]]
                [(reach ?a ?b) [?a :p/friend ?m] (reach ?m ?b)]]
        rule (minimal-cap #(fixture people) '[:find ?b :in $ % :where (reach 30 ?b)] rules)]
    (is (< single join) "the second scan is charged on top of the first")
    (is (< single neg) "a read inside a negation spends the caller's allowance")
    (is (pos? rule))
    (doseq [[q more cap] [[(:join paths) [] join]
                          ['[:find ?b :in $ % :where (reach 30 ?b)] [rules] rule]]]
      (let [fx (fixture people)
            below (apply steps fx q (dec cap) more)]
        (is (= :step-budget-exhausted (err below)) "one unit short is exhaustion")
        (is (contains? (apply steps (fixture people) q cap more) :result))))))


(deftest a-warmed-legacy-tree-does-not-bypass-the-checked-path
  (let [fx (fixture people)
        q (:aevt paths)
        _ (dq/q q (:view fx))                          ; warm the restored trees
        warm-reads (fetches fx)
        _ (reset-events! fx)
        r (steps fx q 100000000)
        bounded-reads (fetches fx)
        cold (fixture people)
        _ (steps cold q 100000000)]
    (is (pos? warm-reads))
    (is (contains? r :result))
    (is (= bounded-reads (fetches cold)) "the checked path read as much as it would cold")
    (is (pos? bounded-reads))))


;; ---------------------------------------------------------------------------
;; Special forms, pull-in-find, rules, multiple sources
;; ---------------------------------------------------------------------------

(deftest pull-in-find-reads-through-the-checked-cursor-and-stops-after-expiry
  (doseq [q ['[:find (pull ?e [:p/name {:p/friend [:p/name]}]) :where [?e :p/age 23]]
             '[:find (pull ?e [*]) :where [?e :p/name "n7"]]]]
    (let [total (total-fetches q)]
      (is (> total 3))
      (doseq [n [(- total 2) (dec total)]]
        (let [fx (fixture people (fn [clock ordinal] (when (= ordinal n) (vreset! clock 5000))))
              r (bounded fx q {})]
          (is (= :timeout (err r)) (str (pr-str q) " during fetch " n))
          (is (= n (fetches fx)) "no fetch after the one that crossed the deadline"))))))


(deftest get-else-and-missing-include-absence
  (let [fx (fixture people)
        cap 100000000]
    (is (= [["none"]]
           (dq/q {:query '[:find ?f :where [?e :p/name "n3"] [(get-else $ ?e :p/nick "none") ?f]]
                  :args [(:view fx)] ::dq/max-steps cap})))
    (is (= [["n3"]]
           (dq/q {:query '[:find ?f :where [?e :p/name "n3"] [(get-else $ ?e :p/name "none") ?f]]
                  :args [(:view fx)] ::dq/max-steps cap})))
    (is (= [[3]]
           (dq/q {:query '[:find ?e :where [?e :p/name "n3"] [(missing? $ ?e :p/nick)]]
                  :args [(:view fx)] ::dq/max-steps cap}))
        "absence of an attribute is observed through the cursor")
    (is (= [] (dq/q {:query '[:find ?e :where [?e :p/name "n3"] [(missing? $ ?e :p/age)]]
                     :args [(:view fx)] ::dq/max-steps cap})))
    (testing "and they honor the deadline"
      (let [fx (fixture people (fn [clock n] (when (= n 1) (vreset! clock 5000))))]
        (is (= :timeout
               (err (bounded fx '[:find ?f :where [?e :p/name "n3"]
                                  [(get-else $ ?e :p/nick "none") ?f]] {}))))))))


(deftest rules-and-negation-reading-the-source
  (let [fx (fixture people)
        rules '[[(reach ?a ?b) [?a :p/friend ?b]]
                [(reach ?a ?b) [?a :p/friend ?m] (reach ?m ?b)]]
        q '[:find ?b :in $ % :where (reach 35 ?b)]]
    (is (= (set (dq/q q materialized rules))
           (set (dq/q {:query q :args [(:view fx) rules] ::dq/max-steps 100000000}))))
    (is (= #{[36] [37] [38] [39] [40] [41]}
           (set (dq/q {:query q :args [(:view fx) rules] ::dq/max-steps 100000000}))))))


(deftest multiple-published-sources-share-one-allowance
  (let [a (fixture people)
        b (fixture others)
        q '[:find ?n ?t :in $a $b :where [$a ?e :p/name ?n] [$b ?e :q/tag ?t] [(= ?e 7)]]
        run (fn [cap] (try-q {:query q :args [(:view a) (:view b)] ::dq/max-steps cap}))
        cap (first (filter #(contains? (run %) :result) (range 1 20000)))]
    (is (= [["n7" "t1"]]
           (:result (run 100000000))))
    (is (= (set (dq/q q materialized (query/current (query/relation others))))
           (set (:result (run 100000000)))))
    (is (some? cap))
    (is (= :step-budget-exhausted (err (run (dec cap)))))
    (is (pos? (fetches a)))
    (is (pos? (fetches b)) "both sources were read")))


(deftest first-witness-stops-future-cursor-work
  (let [rules '[[(w ?e) [?e :p/name _]]
                [(w ?e) [_ _ _]]]                      ; would walk the whole tree
        q '[:find ?e :in $ % :where [?e :p/name "n3"] (not-join [?e] (w ?e))]
        fx (fixture people)
        r (try-q {:query q :args [(:view fx) rules] ::dq/max-steps 100000000})
        full (fixture people)]
    (is (= [] (:result r)))
    (steps full (:full paths) 100000000)
    (is (< (fetches fx) (fetches full))
        "the first witness ended the negation before the whole-tree alternative read anything")))


;; ---------------------------------------------------------------------------
;; Admission: everything else is refused, with zero reads
;; ---------------------------------------------------------------------------

(defn- refused-unqualified?
  [r]
  (and (= :unsupported (err r))
       (= :bounded-source-unqualified (:reason (:error r)))))


(deftest every-excluded-source-is-refused-with-zero-reads
  (let [fx (fixture people)
        good (:view fx)
        pub (:pub fx)
        q '[:find ?e :where [?e :p/name _]]
        bounds [{:timeout 100000 ::dq/now-fn (clock-fn fx)}
                {::dq/max-steps 100000000}]
        excluded {"bare published value" pub
                  "history view" (query/history pub)
                  "as-of view" (query/current pub 5)
                  "nested current view" (query/current (query/current pub))
                  "descriptor-less published value" (query/current
                                                      (dissoc pub :dao.space.query/published-read))
                  "wrong descriptor version" (query/current
                                               (assoc-in pub [:dao.space.query/published-read :version] 2))
                  "extra descriptor key" (query/current
                                           (assoc-in pub [:dao.space.query/published-read :x] 1))
                  "nil descriptor store" (query/current
                                           (assoc-in pub [:dao.space.query/published-read :store] nil))
                  "deferred rows" {:dao.space.query/relation (delay (vswap! (:events fx) conj {:event :forced}) [])}}]
    (doseq [opts bounds
            [label source] excluded]
      (reset-events! fx)
      (let [r (try-q (merge {:query q :args [source]} opts))]
        (is (refused-unqualified? r) label)
        (is (empty? @(:events fx)) (str label ": no read, no force"))))
    (testing "mixed: an eligible source is not read first, in either order"
      (let [mixed '[:find ?e :in $a $b :where [$a ?e :p/name _]]]
        (doseq [opts bounds
                args [[good pub] [pub good] [good (query/history pub)]]]
          (reset-events! fx)
          (is (refused-unqualified? (try-q (merge {:query mixed :args args} opts))))
          (is (empty? @(:events fx)) "the eligible source was not touched"))))
    (testing "a descriptor-less value keeps working unbounded"
      (is (= (set (dq/q q good))
             (set (dq/q q (query/current (dissoc pub :dao.space.query/published-read)))))))))


(deftest bounded-calls-open-close-write-and-publish-nothing
  (let [fx (fixture people (fn [clock n] (when (= n 3) (vreset! clock 5000))))]
    (bounded fx (:aevt paths) {})
    (steps fx (:aevt paths) 3)
    (try-q {:query (:aevt paths) :args [(:view fx)] ::dq/max-steps 100000000})
    (is (not-any? #(contains? #{:put :close} (:event %)) @(:events fx)))
    (is (not (realized? (:rows (:pub fx)))))))


(deftest the-caller-keeps-using-the-source-after-a-timeout
  (let [fx (fixture people (fn [clock n] (when (= n 2) (vreset! clock 5000))))
        r (bounded fx (:aevt paths) {})]
    (is (= :timeout (err r)))
    (vreset! (:clock fx) 0)
    (let [again (try-q {:query (:aevt paths) :args [(:view fx)] ::dq/max-steps 100000000})]
      (is (= (set (dq/q (:aevt paths) materialized)) (set (:result again)))))
    (is (= (set (dq/q (:aevt paths) materialized))
           (set (dq/q (:aevt paths) (:view fx)))))))


;; ---------------------------------------------------------------------------
;; Failures preserve their cause
;; ---------------------------------------------------------------------------

(defn- hand-fixture
  "A published value over hand-built nodes: a root branch over a valid leaf
   and `bad-leaf`, same root for all four indexes (queries below use only
   EAVT). `fire` runs during each fetch."
  [bad-leaf fire]
  (let [base (mem/create-content-mem)
        d1 [1 :a 1 1 1] d2 [2 :a 2 1 1] d3 [3 :a 3 1 1] d4 [4 :a 4 1 1]
        good (jing/materialize! base {:keys [d1 d2]})
        bad (when bad-leaf (jing/materialize! base (bad-leaf d3 d4)))
        child-b (or bad (jing/segment-key {:keys [:never-stored]}))
        root (jing/materialize! base {:level 1 :keys [d2 d4] :addresses [good child-b]})
        manifest {:indexes {:eavt root :aevt root :avet root :vaet root}
                  :count 4 :branching-factor 4}
        address (jing/materialize! base manifest)
        events (volatile! [])
        clock (volatile! 0)
        wrapped (instrumented base events (when fire (fn [n _] (fire clock n))))
        pub (query/published-db wrapped address)]
    (vreset! events [])
    {:pub pub :view (query/current pub) :events events :clock clock}))


(def ^:private q4 '[:find ?v :where [4 :a ?v]])


(deftest a-malformed-node-mid-traversal-is-a-source-failure-with-the-cursor-error-as-cause
  (let [fx (hand-fixture (fn [d3 d4] {:keys [d4 d3]}) nil)
        r (try-q {:query q4 :args [(:view fx)] ::dq/max-steps 100000})
        e (:exception r)
        cause (ex-cause e)]
    (is (= :source-failed (err r)))
    (is (= :cognitect.anomalies/fault (:cognitect.anomalies/category (:error r))))
    (is (some? cause) "the original is preserved as the cause")
    (is (= :unordered-keys (:dao.data.btree.cursor/error (ex-data cause)))
        "and it is the cursor's own error")
    (is (not (re-find #"published-read" (str (:error r)))) "no descriptor in the data")
    (is (= 2 (fetches fx)) "the root and the bad leaf were read, nothing after")))


(deftest a-missing-segment-is-a-source-failure-with-its-cause
  (let [fx (hand-fixture nil nil)
        r (try-q {:query q4 :args [(:view fx)] ::dq/max-steps 100000})]
    (is (= :source-failed (err r)))
    (is (= "missing index segment" (ex-message (ex-cause (:exception r)))))))


(deftest a-node-returned-after-expiry-is-never-interpreted
  (let [fire (fn [clock n] (when (= n 2) (vreset! clock 5000)))
        fx (hand-fixture (fn [d3 d4] {:keys [d4 d3]}) fire)
        r (try-q {:query q4 :args [(:view fx)] :timeout 1000
                  ::dq/now-fn (fn [] @(:clock fx))})]
    (is (= :timeout (err r))
        "the malformed node arrived after the deadline: a timeout, not a validation failure")
    (is (= 2 (fetches fx)))))


(deftest the-eager-fallback-is-refused-not-taken
  (let [fx (fixture people)
        r (try (query/collect
                 (second (query/q-with-state
                           (exec/start {:max-steps 100000}) nil
                           '[:find ?e :where [?e :p/name]]
                           (:view fx))))
               (catch #?(:cljd Object :clj Throwable :cljs :default) e e))]
    (is (= :checked-source-fallback (:reason (ex-data r))))
    (is (zero? (fetches fx)) "refused before any read")))


;; ---------------------------------------------------------------------------
;; Read budgets (bounded equivalents of the unbounded <=4 / <=15 regressions)
;; ---------------------------------------------------------------------------

(deftest bounded-point-queries-stay-within-a-selective-budget
  (let [datoms (mapcat (fn [i]
                         [[i :user/email (str "user-" i "@example.com") 0 1]
                          [i :user/age i 0 1]])
                       (range 200))
        full (fixture datoms)
        _ (steps full '[:find (count ?e) :where [?e :user/age _]] 100000000)
        total (fetches full)
        point (fixture datoms)
        r1 (steps point '[:find ?v :where [42 :user/email ?v]] 100000)
        point-reads (fetches point)
        join (fixture datoms)
        r2 (steps join '[:find ?v ?age :where [42 :user/email ?v] [42 :user/age ?age]] 100000)
        join-reads (fetches join)]
    (is (= [["user-42@example.com"]] (:result r1)))
    (is (= [["user-42@example.com" 42]] (:result r2)))
    (is (= 1 (count (filter #(= :fetch-start (:event %)) (:constructor-events point))))
        "the constructor's single manifest get is counted separately from query reads")
    (is (<= point-reads 8) (str "cold point query used " point-reads " node reads"))
    (is (<= join-reads 15) (str "two-clause query used " join-reads " node reads"))
    (is (< (* 4 point-reads) total) "far fewer than the whole tree")
    (is (< (* 2 join-reads) total))))


(deftest conflicting-d5-rows-fail-the-same-way-bounded-unbounded-and-materialized
  (let [rows [[1 :c 1 1 1] [1 :c 1 1 2] [2 :c 2 1 1]]       ; same [e a v t], different m
        fx (fixture rows)
        q '[:find ?e :where [?e :c _]]
        kind (fn [m] (let [r (try-q m)] [(err r) (ex-message (:exception r))]))
        mat (query/current (query/relation rows))]
    (is (= (kind {:query q :args [mat]})
           (kind {:query q :args [(:view fx)]})
           (kind {:query q :args [(:view fx)] ::dq/max-steps 100000})
           (kind {:query q :args [mat] ::dq/max-steps 100000}))
        "the current-state interpretation is the same code on every path")
    (is (= :invalid-request (first (kind {:query q :args [(:view fx)] ::dq/max-steps 100000}))))))


(deftest a-rootless-but-structurally-valid-descriptor-is-a-source-failure
  (let [fx (fixture people)
        forged (assoc-in (:pub fx) [:dao.space.query/published-read :manifest :indexes]
                         {:eavt nil :aevt nil :avet nil :vaet nil})
        r (try-q {:query '[:find ?v :where [20 :p/age ?v]]
                  :args [(query/current forged)]
                  ::dq/max-steps 100000})]
    (is (= :source-failed (err r)) "a cursor start failure is classified like every other cursor failure")
    (is (= :invalid-descriptor
           (:dao.data.btree.cursor/error (ex-data (ex-cause (:exception r))))))
    (is (zero? (fetches fx)) "and nothing was read")))


(deftest a-deadline-during-a-fetch-reports-the-read-phase
  (let [fx (fixture people (fn [clock n] (when (= n 2) (vreset! clock 5000))))
        r (bounded fx (:aevt paths) {})]
    (is (= :timeout (err r)))
    (is (= :read (:phase (:error r))))
    (is (some #{:read} exec/phases))))
