(ns dao.space.query-test
  "Contract tests for dao.space.query: the reader-side DaoStream consumer
   (docs/design/dao.space.query.md).

   `q` accepts only bounded DaoStreams as database inputs: an exact-bound
   descriptor (`:dao.stream/type` + `:dao.stream/bound`) or an already-opened,
   closed realization. Raw vectors and maps are rejected. `q` returns a local
   bounded distinct-result DaoStream realization and `collect` materializes it
   into the legacy relation/scalar/tuple/coll/return-map shapes. `current` and
   `history` are the explicit d5 interpreters."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.coordinate :as jing-coordinate]
            [dao.jing.file :as jing-file]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]
            #?@(:cljd [["dart:io" :as dart-io]]))
  #?(:cljs (:require-macros [dao.stream])))


;; ---------------------------------------------------------------------------
;; Fixtures and helpers
;; ---------------------------------------------------------------------------

(defn- rel
  "An inline bounded relation descriptor over arbitrary tuples."
  [tuples]
  (query/relation tuples))


(defn- qq
  "Collect a q result: (qq form & inputs)."
  [form & inputs]
  (query/collect (apply query/q form inputs)))


(defn- qcur
  "Collect a q result over a current view of raw d5 datoms: (qcur form datoms & inputs)."
  [form datoms & inputs]
  (apply qq form (query/current (rel datoms)) inputs))


(defn- open-closed
  "An already-opened, closed, fully-retained ringbuffer realization pre-loaded
   with tuples — the borrowed-input path."
  [tuples]
  (let [s (ds/open! {:dao.stream/type :ringbuffer})]
    (doseq [t tuples] (ds/append! s t))
    (ds/close! s)
    s))


(defn- drain
  "Drain a realization to a set of values via cursor reads."
  [stream]
  (loop [cursor {:position 0}
         acc #{}]
    (let [r (ds/next stream cursor)]
      (if (map? r) (recur (:cursor r) (conj acc (:ok r))) acc))))


(def sample-datoms
  [[1 :work/status :todo 0 1] [1 :work/task "write tests" 0 1]
   [2 :work/status :done 0 1] [2 :work/task "ship it" 0 1]])


;; ---------------------------------------------------------------------------
;; R1: structural input dispatch — realization first, then descriptor
;; ---------------------------------------------------------------------------

(deftest raw-vectors-and-maps-are-rejected
  (testing "a raw vector of datoms is not a db-value"
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"raw vectors and maps are rejected|descriptor|realization"
          (qq '[:find ?e :where [?e _ _]] [[1 :a 1 0 1]]))))
  (testing "a raw entity map is not a db-value"
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"raw vectors and maps are rejected|descriptor|realization"
          (qq '[:find ?e :where [?e :a 1]] {:db/id 1, :a 1})))))


(deftest descriptors-require-type-and-bound
  (testing "a descriptor without :dao.stream/type is rejected"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #":dao.stream/type"
          (qq '[:find ?e :where [?e _ _]]
              {:dao.stream/bound {:count 0}}))))
  (testing
    "a create-only/unbounded descriptor without :dao.stream/bound is rejected"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #":dao.stream/bound"
          (qq '[:find ?e :where [?e _ _]]
              {:dao.stream/type :ringbuffer}))))
  (testing "a boolean :dao.stream/bound is rejected (bound is never a boolean)"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #":dao.stream/bound"
          (qq '[:find ?e :where [?e _ _]]
              {:dao.stream/type :dao.stream/relation,
               :tuples [],
               :dao.stream/bound true})))))


(deftest descriptors-require-an-exact-not-symbolic-bound
  (doseq [bound [:open :closed false]]
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"exact :dao.stream/bound"
          (qq '[:find ?e :where [?e _ _]]
              {:dao.stream/type :dao.stream/relation,
               :tuples [],
               :dao.stream/bound bound})))))


(deftest borrowed-realizations-require-bound-and-closed
  (testing "an open (not closed) borrowed realization is rejected"
    (let [s (ds/open! {:dao.stream/type :ringbuffer})]
      (ds/append! s [1 :a 1 0 1])
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"closed"
            (qq '[:find ?e :where [?e _ _]]
                (query/current s))))))
  (testing "a closed borrowed realization is accepted"
    (is (= #{[1] [2]}
           (qq '[:find ?e :where [?e :work/status _]]
               (query/current (open-closed sample-datoms)))))))


(deftest dispatch-checks-realization-before-descriptor
  (testing
    "a value satisfying IDaoStreamReader is treated as a borrowed realization"
    (let [s (open-closed [[1 :name "Ada" 0 1]])]
      (is (= #{["Ada"]}
             (qq '[:find ?n :where [1 :name ?n]] (query/current s)))))))


;; ---------------------------------------------------------------------------
;; R2: current / history — pure derived descriptors and derived realizations
;; ---------------------------------------------------------------------------

(deftest current-is-a-derived-descriptor-for-descriptor-input
  (let [d (rel sample-datoms)
        v (query/current d)]
    (is (= :dao.space/current (:dao.stream/type v)))
    (is (= d (:source v)))
    (is (= (:dao.stream/bound d) (:dao.stream/bound v)))
    (testing "and is openable by q into current d3 facts"
      (is (= #{[1 "write tests"] [2 "ship it"]}
             (qq '[:find ?id ?task :where [?id :work/task ?task]] v))))))


(deftest history-is-a-derived-descriptor-for-descriptor-input
  (let [d (rel sample-datoms)
        v (query/history d)]
    (is (= :dao.space/history (:dao.stream/type v)))
    (is (= d (:source v)))
    (testing "history exposes exact d5 rows"
      (is (= #{[1 :work/status :todo 0 1] [2 :work/status :done 0 1]}
             (qq '[:find ?e ?a ?v ?t ?m :where [?e ?a ?v ?t ?m]
                   [(= ?a :work/status)]]
                 v))))))


(deftest current-over-a-realization-is-a-borrowed-derived-realization
  (let [s (open-closed [[1 :color "red" 1 1] [1 :color "blue" 2 1]])
        v (query/current s)]
    (is (satisfies? ds/IDaoStreamReader v))
    (is (ds/closed? v) "the derived realization is closed and bounded")
    (is (= #{[1 :color "red"] [1 :color "blue"]} (drain v))
        "the derived realization carries the resolved current d3 facts")
    (testing "the borrowed source is left untouched" (is (ds/closed? s)))))


(deftest current-resolves-retractions-and-supersessions
  (let [datoms [[1 :color "red" 1 1]  ; assert
                [1 :color "red" 2 0]  ; retract
                [1 :color "blue" 2 1] ; assert
                [2 :status "active" 1 1] [2 :status "active" 3 0]]]
    (is (= #{["blue"]} (qcur '[:find ?c :where [1 :color ?c]] datoms)))
    (is (= #{} (qcur '[:find ?s :where [2 :status ?s]] datoms)))
    (testing "history exposes the exact d5 history"
      (is (= #{["red" 1 1] ["red" 2 0] ["blue" 2 1]}
             (qq '[:find ?c ?t ?m :where [1 :color ?c ?t ?m]]
                 (query/history (rel datoms))))))))


(deftest current-flattens-transaction-envelopes
  (let [elements [[1 :a "x" 0 1]
                  {:dao.space/transaction
                   {:t 5, :datoms [[1 :a "x" 5 0] [2 :a "z" 5 1]]}}]]
    (is (= #{[2 "z"]}
           (qq '[:find ?e ?v :where [?e :a ?v]]
               (query/current (rel elements)))))))


(deftest conflicting-d5-rows-are-rejected
  (testing "same [e a v t] with different m throws when the view is opened"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"conflicting d5"
          (qq '[:find ?e :where [?e :a "x"]]
              (query/current (rel [[1 :a "x" 1 1]
                                   [1 :a "x" 1 0]]))))))
  (testing "current-state-seq rejects the conflict too"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"conflicting d5"
          (query/current-state-seq [[1 :a "x" 1 1]
                                    [1 :a "x" 1 0]])))))


(deftest as-of-is-an-explicit-view-bound
  (let [datoms [[1 :work/status :todo 0 1] [1 :work/status :done 5 1]]]
    (is (= #{[:todo]}
           (qq '[:find ?v :where [1 :work/status ?v]]
               (query/current (rel datoms) 0))))
    (is (= #{[:todo] [:done]}
           (qq '[:find ?v :where [1 :work/status ?v]]
               (query/current (rel datoms) 5))))))


;; ---------------------------------------------------------------------------
;; R3: q returns a bounded distinct result stream; collect materializes
;; ---------------------------------------------------------------------------

(deftest q-returns-a-result-stream
  (let [result (query/q '[:find ?e ?task :where [?e :work/task ?task]]
                        (query/current (rel sample-datoms)))]
    (is (satisfies? ds/IDaoStreamReader result))
    (is (ds/closed? result) "the result is a closed bounded snapshot")
    (is (= #{[1 "write tests"] [2 "ship it"]} (drain result)))))


(deftest q-result-is-a-bounded-stream-value
  (let [result (query/q '[:find ?e :where [?e :work/status _]]
                        (query/current (rel sample-datoms)))]
    (is (ds/realization? result))
    (is (ds/closed? result) "the result is a closed bounded snapshot")
    (is
      (nil? (ds/descriptor result))
      "the result advertises no descriptor; it is a local realization, not a reopenable transport")
    (is (nil? (ds/bound result))
        "no descriptor means no external bound claim")))


(deftest collect-materializes-the-relation-shape
  (is (= #{[1 "write tests"] [2 "ship it"]}
         (qcur '[:find ?id ?task :where [?id :work/task ?task]]
               sample-datoms))))


(deftest collect-materializes-scalar-tuple-coll-shapes
  (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]]]
    (testing "scalar"
      (is (= "Alice" (qcur '[:find ?n . :where [1 :name ?n]] datoms)))
      (is (nil? (qcur '[:find ?n . :where [99 :name ?n]] datoms))))
    (testing "tuple"
      (is (= [1 "Alice"]
             (qcur '[:find [?e ?n] :where [?e :name ?n] [(< ?e 2)]] datoms))))
    (testing "collection"
      (is (= #{"Alice" "Bob"}
             (set (qcur '[:find [?n ...] :where [_ :name ?n]] datoms))))
      (is (= [] (qcur '[:find [?n ...] :where [_ :name ?n]] []))))))


(deftest collect-materializes-return-map-shapes
  (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [2 :name "Bob" 1 1]
                [2 :age 40 1 1]]]
    (testing ":keys"
      (is (= #{{:e 1, :n "Alice", :a 30} {:e 2, :n "Bob", :a 40}}
             (set (qcur '[:find ?e ?n ?a :keys e n a :where [?e :name ?n]
                          [?e :age ?a]]
                        datoms)))))
    (testing ":syms"
      (is (= #{[1 "Alice"] [2 "Bob"]}
             (set (map (juxt 'e 'n)
                       (qcur '[:find ?e ?n :syms e n :where [?e :name ?n]
                               [?e :age ?a]]
                             datoms))))))
    (testing ":strs"
      (is (= #{[1 "Alice"] [2 "Bob"]}
             (set (map (juxt #(get % "e") #(get % "n"))
                       (qcur '[:find ?e ?n :strs e n :where [?e :name ?n]
                               [?e :age ?a]]
                             datoms))))))))


(deftest results-are-distinct
  (let [datoms [[1 :kind :cat 1 1] [1 :pet true 1 1]]]
    (is (= #{[1]}
           (qcur '[:find ?e :where (or [?e :kind :cat] [?e :pet true])]
                 datoms)))))


;; ---------------------------------------------------------------------------
;; R4: ownership — opened descriptors are owned and closed; borrowed are not
;; ---------------------------------------------------------------------------

(deftype OwnedSource
  [values closed?]

  ds/IDaoStreamReader

  (next
    [_ cursor]
    (let [pos (:position cursor)]
      (if (< pos (count values))
        {:ok (nth values pos), :cursor {:position (inc pos)}}
        :end)))


  ds/IDaoStreamBound

  (close! [_] (reset! closed? true) {:woke []})


  (closed? [_] @closed?))


(def owned-close-state (atom nil))


(ds/defopen :test/owned-source
            [d]
            (let [closed? (atom false)]
              (reset! owned-close-state closed?)
              (->OwnedSource (vec (:tuples d)) closed?)))


(deftest q-closes-owned-sources-and-never-borrowed
  (testing "q opens and closes an owned descriptor"
    (reset! owned-close-state nil)
    (is (= #{[1]}
           (qq '[:find ?e :where [?e :work/status :todo]]
               (query/current {:dao.stream/type :test/owned-source,
                               :dao.stream/bound {:count 1},
                               :tuples [[1 :work/status :todo 0 1]]}))))
    (is (true? @@owned-close-state) "q closed the realization it opened"))
  (testing "a borrowed realization is never closed by q"
    (let [s (open-closed [[1 :work/status :todo 0 1]])]
      (is (= #{[1]}
             (qq '[:find ?e :where [?e :work/status :todo]] (query/current s))))
      (is (ds/closed? s)
          "the borrowed stream is still the caller's to manage"))))


(deftype BlockedOwnedSource
  [closed?]

  ds/IDaoStreamReader

  (next [_ _cursor] :blocked)


  ds/IDaoStreamBound

  (close! [_] (reset! closed? true) {:woke []})


  (closed? [_] @closed?))


(def blocked-close-state (atom nil))


(ds/defopen :test/blocked-source
            [_]
            (let [closed? (atom false)]
              (reset! blocked-close-state closed?)
              (->BlockedOwnedSource closed?)))


(deftest descriptor-open-is-exception-safe
  (testing "an owned realization opened by q is closed when strict-vec fails"
    (reset! blocked-close-state nil)
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #":blocked"
          (qq '[:find ?e :where [?e _ _]]
              {:dao.stream/type :test/blocked-source,
               :dao.stream/bound {:count 1}})))
    (is (true? @@blocked-close-state)
        "q closed the realization even though traversal threw :blocked")))


(deftest open-db-inputs-is-exception-safe
  (testing
    "previously opened owned inputs are closed when a later db input fails"
    (reset! owned-close-state nil)
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #":blocked"
          (qq '[:find ?e :in $a $b :where [$a ?e _ _]]
              {:dao.stream/type :test/owned-source,
               :dao.stream/bound {:count 1},
               :tuples [[1 :work/status :todo 0 1]]}
              {:dao.stream/type :test/blocked-source,
               :dao.stream/bound {:count 1}})))
    (is (true? @@owned-close-state)
        "the earlier opened input was closed when the later one failed")))


;; ---------------------------------------------------------------------------
;; R5: stream control — validation throws synchronously; never error tuples
;; ---------------------------------------------------------------------------

(deftest validation-throws-synchronously-from-q
  (testing "a malformed descriptor throws before any traversal"
    (is (thrown? #?(:cljs js/Error
                    :cljd Object
                    :default Exception)
          (query/q '[:find ?e :where [?e _ _]] 42)))))


(deftest blocked-and-gap-throw-during-traversal
  (let [blocked (reify
                  ds/IDaoStreamReader
                  (next [_ _cursor] :blocked)


                  ds/IDaoStreamBound

                  (close! [_] {:woke []})

                  (closed? [_] true))]
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #":blocked"
          (qq '[:find ?e :where [?e _ _]] blocked))))
  (let [gapped (reify
                 ds/IDaoStreamReader
                 (next [_ _cursor] :daostream/gap)


                 ds/IDaoStreamBound

                 (close! [_] {:woke []})

                 (closed? [_] true))]
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #":daostream/gap"
          (qq '[:find ?e :where [?e _ _]] gapped)))))


;; ---------------------------------------------------------------------------
;; R6: entity-map relation + generic relation descriptors (raw ontology gone)
;; ---------------------------------------------------------------------------

(deftest entity-map-relation-projects-to-d3-facts
  (let [maps [{:db/id 1, :work/status :todo, :work/task "a"}
              {:db/id 2, :work/status :done, :work/task "b"}]]
    (is (= #{["a"] ["b"]}
           (qq '[:find ?task :where [_ :work/task ?task]]
               (query/entity-map-relation maps)))))
  (testing "a map without :db/id throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"explicit :db/id"
          (qq '[:find ?task :where [_ :work/task ?task]]
              (query/entity-map-relation [{:work/task
                                           "a"}]))))))


(deftest generic-relation-tuples-carry-arbitrary-dimensions
  (let [tuples [[42] [1 :edge/to 2] [7 :sensor/x 1.0 2.0]]]
    (is (= #{[42]} (qq '[:find ?v :where [?v]] (rel tuples))))
    (is (= #{[1 2]}
           (qq '[:find ?from ?to :where [?from :edge/to ?to]] (rel tuples))))
    (is (= #{[7 1.0 2.0]}
           (qq '[:find ?id ?x ?y :where [?id :sensor/x ?x ?y]] (rel tuples))))))


(deftest special-function-clauses-reject-more-than-one-result-binding
  (let [source (query/current (query/relation [[1 :name "Ada" 1 1]]))]
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"one binding form"
          (qq '[:find ?v :where
                [(get-else $ 1 :name "unknown") ?v ?extra]]
              source)))
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"one binding form"
          (qq '[:find ?missing :where
                [(missing? $ 1 :age) ?missing ?extra]]
              source)))))


;; ---------------------------------------------------------------------------
;; R7: match / pull are ergonomic materializers over bounded streams
;; ---------------------------------------------------------------------------

(deftest match-materializes-over-a-current-view
  (let [src (query/current (rel sample-datoms))]
    (is (= [[1 :work/status :todo] [2 :work/status :done]]
           (query/match src ['_ :work/status '_])))))


(deftest match-accepts-a-descriptor-and-a-borrowed-realization
  (is (= [[1 :work/status :todo]]
         (query/match (query/current (rel sample-datoms)) [1 :work/status '_])))
  (is (= [[1 :work/status :todo]]
         (query/match (query/current (open-closed sample-datoms))
           [1 :work/status '_]))))


(deftest pull-materializes-over-a-current-view
  (let [datoms [[1 :person/name "Alice" 1 1] [1 :person/age 30 1 1]
                [2 :person/name "Bob" 1 1]]]
    (is (= {:db/id 1, :person/name "Alice", :person/age 30}
           (query/pull (query/current (rel datoms))
                       1
                       [:person/name :person/age])))
    (is (= [{:db/id 1, :person/name "Alice"} {:db/id 2, :person/name "Bob"}
            {:db/id 999}]
           (query/pull-many (query/current (rel datoms))
                            [1 2 999]
                            [:person/name])))))


(deftest fact-only-verbs-require-a-current-fact-view
  (let [raw (rel [[1 :person/name "Ada" 1 1]])
        hist (query/history raw)]
    (doseq [db-value [raw hist]]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"current fact-shaped"
            (query/pull db-value 1 [:person/name]))))))


;; ---------------------------------------------------------------------------
;; Full evaluator behaviors survive the stream migration
;; ---------------------------------------------------------------------------

(deftest in-bindings-still-work
  (testing "scalar binding"
    (is (= #{[1]}
           (qcur '[:find ?e :in $ ?name :where [?e :name ?name]]
                 [[1 :name "Alice" 0 1] [2 :name "Bob" 0 1]]
                 "Alice"))))
  (testing "relation binding"
    (is (= #{[1] [2]}
           (qcur '[:find ?e :in $ [[?name ?age]] :where [?e :name ?name]
                   [?e :age ?age]]
                 [[1 :name "Alice" 0 1] [1 :age 30 0 1] [2 :name "Bob" 0 1]
                  [2 :age 40 0 1] [3 :name "Charlie" 0 1] [3 :age 50 0 1]]
                 [["Alice" 30] ["Bob" 40]])))))


(deftest multi-source-queries-keep-sources-separate
  (let [a (query/current (rel [[1 :work/status :todo 0 1]
                               [1 :work/task "a" 0 1]]))
        b (query/current (rel [[2 :work/status :done 0 1]
                               [2 :work/task "b" 0 1]]))]
    (is (= #{[1 "a"] [2 "b"]}
           (qq '[:find ?id ?task :in $a $b :where
                 (or [$a ?id :work/task ?task] [$b ?id :work/task ?task])]
               a
               b)))))


(deftest negation-and-aggregation-still-work
  (let [datoms [[1 :work/posted true 1 1] [1 :work/task "Clean" 1 1]
                [2 :work/posted true 1 1] [2 :work/task "Buy" 1 1]
                [2 :work/claims "u1" 1 1]]]
    (is (= #{[1 "Clean"]}
           (qcur '[:find ?w ?task :where [?w :work/posted true]
                   [?w :work/task ?task] (not [?w :work/claims _])]
                 datoms))))
  (let [datoms [[1 :task/status :open 1 1] [2 :task/status :open 1 1]
                [3 :task/status :done 1 1]]]
    (is (= #{[:open 2] [:done 1]}
           (qcur '[:find ?status (count ?e) :where [?e :task/status ?status]]
                 datoms)))))


;; ---------------------------------------------------------------------------
;; Step 3: Lazy covered-index query pushdown tests
;; ---------------------------------------------------------------------------

(defn- counting-content-store
  [store]
  (let [gets (atom 0)
        get-fn (:get-content-fn store)]
    {:store (assoc store
                   :get-content-fn (fn [address not-found]
                                     (swap! gets inc)
                                     (get-fn address not-found))),
     :gets (fn [] @gets)}))


(defn- temp-content-path
  [prefix]
  (str "target/test-query-stream-" prefix "-" (random-uuid) ".log"))


(defn- cleanup-file
  [path]
  #?(:clj (let [f (java.io.File. path)] (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") path) (catch :default _))
     :cljd (try (let [f (dart-io/File path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch Object _ nil))))


(defn- open-local
  [datoms]
  (let [s (ds/open! {:dao.stream/type :ringbuffer})]
    (doseq [d datoms] (ds/append! s d))
    s))


(defn- open-intake
  []
  (ds/open! {:dao.stream/type :ringbuffer, :capacity 4096}))


(defn- publish-into-file
  ([datoms] (publish-into-file datoms "pub"))
  ([datoms prefix] (publish-into-file datoms prefix nil))
  ([datoms prefix opts]
   (let [path (temp-content-path prefix)
         store (jing-file/create-content-file path)]
     (try (let [local (open-local datoms)
                intake (open-intake)
                {:keys [manifest-address]}
                (index/publish-index! local [intake] opts)
                drain (fn drain
                        [state]
                        (let [r (jing/observe-step! store state)]
                          (when (= :ok (:signal r)) (drain (:state r)))))]
            (drain (jing/observer-state [intake]))
            {:store store,
             :path path,
             :manifest-address manifest-address,
             :descriptor (index/published-index {:dao.jing/type :dao.jing/file,
                                                 :path path}
                                                manifest-address)})
          (catch #?(:clj Throwable
                    :cljs :default
                    :cljd Object)
                 e
            (jing/close! store)
            (cleanup-file path)
            (throw e))))))


(deftest lazy-published-point-clause-and-entity-projection-equivalence
  (let [test-datoms
        [[42 :user/email "ada@example.com" 1 1] [42 :user/name "Ada" 1 1]
         [42 :user/role 100 1 1] [100 :role/name :admin 1 1]
         [43 :user/email "grace@example.com" 1 1] [43 :user/name "Grace" 1 1]
         ;; Add an asserted and then retracted datom (matching e a v)
         [42 :user/status :active 1 1] [42 :user/status :active 2 0]]
        fx (publish-into-file test-datoms)
        eager-cur (query/current (rel test-datoms))
        lazy-cur (query/current (:descriptor fx))]
    (try (testing "q point-clause equivalence: lazy current matches eager"
           (let [query-form '[:find ?v :where [$ 42 :user/email ?v]]]
             (is (= (query/collect (query/q query-form eager-cur))
                    (query/collect (query/q query-form lazy-cur))))
             (is (= #{["ada@example.com"]}
                    (query/collect (query/q query-form lazy-cur))))))
         (testing "get-else / missing? equivalence over lazy current view"
           (let [q-get-else '[:find ?name :where
                              [(get-else $ 42 :user/name "default") ?name]]
                 q-get-else-missing
                 '[:find ?val :where
                   [(get-else $ 42 :user/nonexistent "default") ?val]]
                 q-missing-false '[:find ?e :where [$ ?e :user/name _]
                                   [(missing? $ ?e :user/name)]]
                 q-missing-true '[:find ?e :where [$ ?e :user/name _]
                                  [(missing? $ ?e :user/status)]]]
             (is (= (query/collect (query/q q-get-else eager-cur))
                    (query/collect (query/q q-get-else lazy-cur))))
             (is (= #{["Ada"]} (query/collect (query/q q-get-else lazy-cur))))
             (is (= (query/collect (query/q q-get-else-missing eager-cur))
                    (query/collect (query/q q-get-else-missing lazy-cur))))
             (is (= #{["default"]}
                    (query/collect (query/q q-get-else-missing lazy-cur))))
             (is (= (query/collect (query/q q-missing-false eager-cur))
                    (query/collect (query/q q-missing-false lazy-cur))))
             (is (= #{} (query/collect (query/q q-missing-false lazy-cur))))
             (is (= (query/collect (query/q q-missing-true eager-cur))
                    (query/collect (query/q q-missing-true lazy-cur))))
             (is (= #{[42] [43]}
                    (query/collect (query/q q-missing-true lazy-cur))))))
         (testing "pull and pull-many equivalence over lazy current view"
           (let [pattern [:db/id :user/email :user/name
                          {:user/role [:db/id :role/name]}]]
             (is (= (query/pull eager-cur 42 pattern)
                    (query/pull lazy-cur 42 pattern)))
             (is (= {:db/id 42,
                     :user/email "ada@example.com",
                     :user/name "Ada",
                     :user/role {:db/id 100, :role/name :admin}}
                    (query/pull lazy-cur 42 pattern)))
             (is (= (query/pull-many eager-cur [42 43 99] pattern)
                    (query/pull-many lazy-cur [42 43 99] pattern)))))
         (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))


(deftest lazy-published-node-budget-is-strictly-bounded
  (let [datoms (mapv (fn [i] [i :user/email (str "user-" i "@example.com") 0 1])
                     (range 200))
        fx (publish-into-file datoms)]
    (try
      (let [counter (counting-content-store (:store fx))]
        #?(:cljd
           (is
             true
             "counting harness requires with-redefs, unavailable on ClojureDart")
           :default (with-redefs [jing-coordinate/open! (fn [_]
                                                          (:store counter))]
                      (let [query-form '[:find ?v :where [$ 42 :user/email ?v]]
                            res (query/collect (query/q query-form
                                                        (query/current
                                                          (:descriptor fx))))
                            total-gets ((:gets counter))]
                        (is (= #{["user-42@example.com"]} res))
                        ;; Total gets includes: 1 (manifest) + seek path to
                        ;; leaf + 1 (take-while bound leaf). For 200
                        ;; elements, tree height is <= 2. Max gets <= 4.
                        ;; Eager read-datoms would have read all node
                        ;; blobs.
                        (is (<= total-gets 4)
                            (str "lazy point query used "
                                 total-gets
                                 " gets, strictly bounded <= 4"))
                        (is (< total-gets 10)
                            "strictly fewer gets than reading full index")))))
      (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))


(deftest lazy-published-multi-clause-budget-is-strictly-bounded
  (let [datoms (mapcat (fn [i]
                         [[i :user/email (str "user-" i "@example.com") 0
                           1] [i :user/age i 0 1]])
                       (range 200))
        ;; branching-factor 4 makes a real multi-node tree (~135 nodes for
        ;; 400 rows), so a full drain is distinguishable from a seek path.
        ;; At the default 512 every row fits one leaf and even a drain
        ;; looks lazy.
        fx (publish-into-file datoms "pub-multi" {:branching-factor 4})]
    (try
      (let [counter (counting-content-store (:store fx))]
        #?(:cljd
           (is
             true
             "counting harness requires with-redefs, unavailable on ClojureDart")
           :default
           (with-redefs [jing-coordinate/open! (fn [_] (:store counter))]
             ;; Two pattern clauses over one source: plan-where runs the
             ;; clause-cost estimator on both. The estimator must not
             ;; force the deferred relation, or planning alone would
             ;; drain the whole source and the lazy path would exist only
             ;; for single-clause queries.
             (let [query-form '[:find ?v ?age :where [$ 42 :user/email ?v]
                                [$ 42 :user/age ?age]]
                   res (query/collect (query/q query-form
                                               (query/current (:descriptor
                                                                fx))))
                   total-gets ((:gets counter))]
               (is (= #{["user-42@example.com" 42]} res))
               ;; 1 manifest + two seek paths (tree height ~5 at bf 4) +
               ;; boundary leaves.
               (is
                 (<= total-gets 15)
                 (str
                   "two-clause lazy query used "
                   total-gets
                   " gets, bounded <= 15: the planner must not drain the source"))
               (is
                 (< total-gets 50)
                 "a full drain of this ~135-node tree would exceed 50 gets")))))
      (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))


(deftest lazy-published-rest-pattern-regression
  (testing
    "a non-3-fixed / rest pattern clause falls back to non-nil shared delay ::relation"
    (let [test-datoms [[1 :person/name "Ada" 0 1] [2 :person/name "Grace" 0 1]]
          fx (publish-into-file test-datoms)
          lazy-cur (query/current (:descriptor fx))
          eager-cur (query/current (rel test-datoms))]
      (try
        (let [query-form '[:find ?e ?name :where
                           [?e :person/name ?name & ?rest]]]
          (is
            (= #{[1 "Ada"] [2 "Grace"]}
               (query/collect (query/q query-form lazy-cur)))
            "literal anchor: the lazy fallback returns real rows, not an empty parity")
          (is (= (query/collect (query/q query-form eager-cur))
                 (query/collect (query/q query-form lazy-cur)))))
        (testing
          "match over a lazy current view forces the shared relation and returns real tuples"
          (let [pattern ['_ :person/name "Ada"]]
            (is (= [[1 :person/name "Ada"]] (query/match lazy-cur pattern))
                "literal anchor for match, not an eager/lazy-only parity")
            (is (= (query/match eager-cur pattern)
                   (query/match lazy-cur pattern)))))
        (finally (jing/close! (:store fx)) (cleanup-file (:path fx)))))))


(deftest lazy-published-mixed-multi-source-join
  (testing
    "one lazy published current + one eager query/relation in a single q with :in $ $2"
    (let [users [[1 :user/name "Alice" 0 1] [2 :user/name "Bob" 0 1]]
          roles [[1 :user/role :admin] [2 :user/role :member]]
          fx (publish-into-file users)
          lazy-users (query/current (:descriptor fx))
          eager-roles (query/relation roles)]
      (try (let [query-form '[:find ?name ?role :in $users $roles :where
                              [$users ?e :user/name ?name]
                              [$roles ?e :user/role ?role]]]
             (is (= #{["Alice" :admin] ["Bob" :member]}
                    (query/collect
                      (query/q query-form lazy-users eager-roles)))))
           (finally (jing/close! (:store fx)) (cleanup-file (:path fx)))))))


(deftest lazy-published-history-and-as-of-stay-eager-and-correct
  (let [test-datoms [[1 :item/status :draft 1 1] [1 :item/status :draft 2 0]
                     [1 :item/status :published 2 1]
                     [1 :item/status :archived 3 1]]
        fx (publish-into-file test-datoms)
        desc (:descriptor fx)
        eager-source (rel test-datoms)]
    (try
      (testing "history view over published descriptor matches eager history"
        (let [q-hist '[:find ?v ?t ?op :where [1 :item/status ?v ?t ?op]]]
          (is (= (query/collect (query/q q-hist (query/history eager-source)))
                 (query/collect (query/q q-hist (query/history desc)))))))
      (testing
        "as-of current view over published descriptor matches eager as-of"
        (let [q-cur '[:find ?v :where [1 :item/status ?v]]]
          (is (= (query/collect (query/q q-cur (query/current eager-source 2)))
                 (query/collect (query/q q-cur (query/current desc 2)))))
          (is (= #{[:published]}
                 (query/collect (query/q q-cur (query/current desc 2)))))))
      (testing
        "as-of history view over published descriptor matches eager as-of history"
        (let [q-hist '[:find ?v ?t ?op :where [1 :item/status ?v ?t ?op]]]
          (is (= (query/collect (query/q q-hist (query/history eager-source 2)))
                 (query/collect (query/q q-hist (query/history desc 2)))))))
      (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))


(deftest lazy-published-datoms-projections-match-eager
  (let [test-datoms [[1 :work/task "Code" 10 1] [1 :work/owner "Ada" 11 1]
                     [2 :work/task "Review" 12 1]]
        fx (publish-into-file test-datoms)
        lazy-cur (query/current (:descriptor fx))
        eager-cur (query/current (rel test-datoms))]
    (try
      (let [{lazy-idx ::query/fact-index, lazy-owned ::query/owned}
            (query/realize-db-value! lazy-cur)
            {eager-idx ::query/fact-index, eager-owned ::query/owned}
            (query/realize-db-value! eager-cur)]
        (try
          (testing
            "datoms [e a v] projections match eager (note: t/m differ by design: eager has synthetic t=0 m=1, lazy has real provenance t/m)"
            (let [project-eav (fn [d-seq]
                                (mapv (fn [d]
                                        [(index/datom-e d)
                                         (index/datom-a d)
                                         (index/datom-v d)])
                                      d-seq))]
              (is (= (project-eav (query/datoms eager-idx 1 '_ '_))
                     (project-eav (query/datoms lazy-idx 1 '_ '_))))
              (is (= (project-eav (query/datoms eager-idx '_ :work/task '_))
                     (project-eav (query/datoms lazy-idx '_ :work/task '_))))
              (is (= (project-eav (query/datoms eager-idx '_ '_ "Ada"))
                     (project-eav (query/datoms lazy-idx '_ '_ "Ada"))))))
          (finally (query/close-owned! lazy-owned)
                   (query/close-owned! eager-owned))))
      (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))


(deftest lazy-published-current-closes-owned-store-once
  (let [test-datoms [[1 :work/task "Code" 10 1]]
        fx (publish-into-file test-datoms)]
    (try
      #?(:cljd
         (is
           true
           "the close-once spy needs with-redefs, unavailable on ClojureDart; equivalence tests cover correctness there")
         :default
         (let [closes (atom 0)
               orig-close jing/close!]
           (with-redefs [jing/close! (fn [handle]
                                       (swap! closes inc)
                                       (orig-close handle))]
             (is (= #{[1]}
                    (query/collect (query/q
                                     '[:find ?e :where [?e :work/task "Code"]]
                                     (query/current (:descriptor fx))))))
             (is
               (= 1 @closes)
               "index-routed query: one owned realization means exactly one store close")
             (reset! closes 0)
             (is (= #{[1 :work/task "Code"]}
                    (query/collect (query/q
                                     '[:find ?e ?a ?v :where [?e ?a ?v & _]]
                                     (query/current (:descriptor fx))))))
             (is (= 1 @closes)
                 "scan query over the lazy view: also exactly one close"))))
      (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))
