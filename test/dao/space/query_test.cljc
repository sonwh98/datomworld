(ns dao.space.query-test
  "Contract tests for dao.space.query over values
   (docs/design/dao.space.query.md).

   `q` accepts only query values as database inputs: a relation value, a
   datom view over one, or an opened published index. Raw vectors and raw
   maps are rejected. `q` opens nothing and closes nothing; it returns a
   local result value carrying the find spec, and `collect` materializes
   it. `current` and `history` are the explicit d5 interpreters. A live
   dao.stream handle becomes an input only through `snapshot`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [dao.data.btree :as bt]
            [dao.jing :as jing]
            [dao.jing.coordinate :as jing-coordinate]
            [dao.jing.file :as jing-file]
            [dao.jing.mem :as jing-mem]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.stream :as stream]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.linearize :as linearize]
            [yin.vm.parity-test :as parity]
            #?@(:cljd [["dart:io" :as dart-io]])))


;; ---------------------------------------------------------------------------
;; Fixtures and helpers
;; ---------------------------------------------------------------------------

(defn- rel
  "An inline relation value over arbitrary tuples."
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


(def sample-datoms
  [[1 :work/status :todo 0 1] [1 :work/task "write tests" 0 1]
   [2 :work/status :done 0 1] [2 :work/task "ship it" 0 1]])


(defn- ring-handle
  "A dao.stream ringbuffer owner handle."
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity capacity})))


;; ---------------------------------------------------------------------------
;; I: inputs — raw data is not a database input
;; ---------------------------------------------------------------------------

(deftest raw-vectors-and-maps-are-rejected
  (testing "a raw vector of datoms is not a db-value"
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"raw vectors and maps are rejected"
          (qq '[:find ?e :where [?e _ _]] [[1 :a 1 0 1]]))))
  (testing "a raw entity map is not a db-value"
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"raw vectors and maps are rejected"
          (qq '[:find ?e :where [?e :a 1]] {:db/id 1, :a 1}))))
  (testing "a bare content-store handle carries no source and is not a db-value"
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"raw vectors and maps are rejected"
          (qq '[:find ?e :where [?e _ _]] (jing-mem/create-content-mem))))))


(deftest loose-stream-type-maps-are-rejected
  (testing "a map with :dao.stream/type is neither a value query built nor an opened index"
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"raw vectors and maps are rejected"
          (qq '[:find ?e :where [?e _ _]]
              {:dao.stream/type :ringbuffer})))
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"raw vectors and maps are rejected"
          (qq '[:find ?e :where [?e _ _]]
              {:dao.stream/type :dao.stream/relation,
               :tuples [],
               :dao.stream/bound true})))))


(deftest unrecognized-host-objects-are-rejected
  ;; No legacy path exists: an arbitrary host object is not silently
  ;; classified as a v1 reader and drained — it receives the same I1
  ;; rejection as raw vectors and raw maps.
  (testing "an atom is not a source"
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"raw vectors and maps are rejected"
          (query/history (atom [[1 :a 1 0 1]]))))
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"raw vectors and maps are rejected"
          (query/current (atom nil)))))
  (testing "a delay is not a source"
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"raw vectors and maps are rejected"
          (query/current (delay [[1 :a 1 0 1]])))))
  #?(:clj
     (testing "a host object (a Date) is not a source"
       (is (thrown-with-msg?
             Exception
             #"raw vectors and maps are rejected"
             (query/history (java.util.Date.)))))))


(deftest relation-and-entity-map-relation-are-accepted
  (is (= #{[1] [2]}
         (qq '[:find ?e :where [?e :work/status _]]
             (query/current (rel sample-datoms)))))
  (is (query/value? (rel sample-datoms)))
  (is (query/value? (query/current (rel sample-datoms))))
  (is (nil? (:dao.stream/type (rel sample-datoms)))
      "a relation value claims no transport type")
  (is (not (query/value? [[1 :a 1]])))
  (is (not (query/value? {:db/id 1}))))


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


;; ---------------------------------------------------------------------------
;; V: views — current / history over values
;; ---------------------------------------------------------------------------

(deftest current-is-a-view-value-over-a-relation
  (let [d (rel sample-datoms)
        v (query/current d)]
    (is (= :current (:dao.space.query/view v)))
    (is (= d (:source v)))
    (is (nil? (:as-of v)))
    (is (nil? (:dao.stream/type v)) "a view value claims no transport type")
    (testing "and is interpreted by q into current d3 facts"
      (is (= #{[1 "write tests"] [2 "ship it"]}
             (qq '[:find ?id ?task :where [?id :work/task ?task]] v))))))


(deftest history-is-a-view-value-over-a-relation
  (let [d (rel sample-datoms)
        v (query/history d)]
    (is (= :history (:dao.space.query/view v)))
    (is (= d (:source v)))
    (is (nil? (:dao.stream/type v)) "a view value claims no transport type")
    (testing "history exposes exact d5 rows"
      (is (= #{[1 :work/status :todo 0 1] [2 :work/status :done 0 1]}
             (qq '[:find ?e ?a ?v ?t ?m :where [?e ?a ?v ?t ?m]
                   [(= ?a :work/status)]]
                 v))))))


(deftest rows-resolves-a-view
  (let [datoms [[1 :color "red" 1 1]       ; assert
                [1 :color "red" 2 0]       ; retract
                [1 :color "blue" 2 1]      ; assert
                [2 :status "active" 1 1] [2 :status "active" 3 0]]]
    (is (= [[1 :color "blue"]]
           (query/rows (query/current (rel datoms)))))
    (is (= (mapv vec datoms)
           (query/rows (query/history (rel datoms)))))
    (testing "rows over a plain relation value returns its tuples"
      (is (= sample-datoms (query/rows (rel sample-datoms)))))))


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
  (testing "same [e a v t] with different m throws when the view is interpreted"
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
    (is (= 0 (:as-of (query/current (rel datoms) 0))))
    (is (= #{[:todo]}
           (qq '[:find ?v :where [1 :work/status ?v]]
               (query/current (rel datoms) 0))))
    (is (= #{[:todo] [:done]}
           (qq '[:find ?v :where [1 :work/status ?v]]
               (query/current (rel datoms) 5))))))


;; ---------------------------------------------------------------------------
;; R: results — a tagged local value, materialized by collect
;; ---------------------------------------------------------------------------

(deftest q-returns-a-result-value
  (let [result (query/q '[:find ?e ?task :where [?e :work/task ?task]]
                        (query/current (rel sample-datoms)))]
    (is (map? result))
    (is (contains? result :dao.space.query/result))
    (is (= :relation (:spec result)))
    (is (= #{[1 "write tests"] [2 "ship it"]}
           (:dao.space.query/result result)
           (query/collect result)))
    (testing "a result is not a coordinate and claims no stream type"
      (is (not (contains? result :dao.stream/type)))
      (is (not (contains? result :dao.space.query/published))))))


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
;; O: ownership — the caller owns every handle (proved with the published
;; helpers further down; deftest bodies resolve at run time)
;; ---------------------------------------------------------------------------


;; ---------------------------------------------------------------------------
;; S: snapshot — the one dao.stream interpreter
;; ---------------------------------------------------------------------------

(deftest snapshot-of-an-open-buffer-is-blocked
  (let [h (ring-handle 4096)]
    (doseq [v [:a :b :c]] (stream/append! h v))
    (let [snap (query/snapshot h)]
      (is (= :blocked (:status snap)) "an open stream caught up")
      (is (= [:a :b :c] (:dao.space.query/relation (:relation snap))))
      (is (= :dao.stream/blocked
             (:dao.stream/outcome (stream/next h (:cursor snap))))
          "the snapshot's cursor sits after the third value"))))


(deftest snapshot-of-a-closed-buffer-ends
  (let [h (ring-handle 4096)]
    (doseq [v [:a :b]] (stream/append! h v))
    (stream/close! h)
    (let [snap (query/snapshot h)]
      (is (= :ended (:status snap)))
      (is (= [:a :b] (:dao.space.query/relation (:relation snap)))))))


(deftest snapshot-of-a-gap-is-data
  ;; A snapshot mints at :dao.stream/oldest, which by construction never
  ;; trails a quiescent ringbuffer's first retained position, so a real
  ;; values-then-hole gap is pinned with a scripted reader: :a is read and
  ;; retained, then the position is evicted.
  (let [h (reify stream/IDaoStreamReader
            (cursor
              [_ _]
              {:dao.stream/outcome :dao.stream/ok
               :dao.stream/cursor {::pos 0}})

            (next
              [_ c]
              (case (::pos c)
                0 {:dao.stream/outcome :dao.stream/ok
                   :dao.stream/value :a
                   :dao.stream/cursor {::pos 1}}
                1 {:dao.stream/outcome :dao.stream/gap
                   :dao.stream/cursor {::pos 2}})))
        snap (query/snapshot h)]
    (is (= :gap (:status snap)))
    (is (= [:a] (:dao.space.query/relation (:relation snap)))
        "the values read before the hole are retained")
    (is (= {::pos 1} (:cursor snap)) "the last retained position")
    (is (= {::pos 2} (:recovery snap)) "the recovery cursor")))


(deftest snapshot-of-a-defect-carries-the-raw-answer
  (let [h (reify stream/IDaoStreamReader
            (cursor
              [_ _]
              {:dao.stream/outcome :dao.stream/ok
               :dao.stream/cursor {::pos 0}})

            (next
              [_ _]
              {:dao.stream/outcome :dao.stream/cursor-mismatch}))
        snap (query/snapshot h)]
    (is (= :defect (:status snap)))
    (is (= :dao.stream/cursor-mismatch
           (:dao.stream/outcome (:read snap))))))


(deftest snapshot-never-closes-the-handle
  (let [h (ring-handle 4096)]
    (stream/append! h :a)
    (query/snapshot h)
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! h :b)))
        "the handle still accepts appends after a snapshot")))


(deftest snapshot-relation-feeds-current-and-q
  (let [h (ring-handle 4096)]
    (doseq [d [[1 :sensor/x 1.0 0 1] [2 :sensor/x 2.0 0 1]]]
      (stream/append! h d))
    (let [snap (query/snapshot h)]
      (is (= [[1 :sensor/x 1.0] [2 :sensor/x 2.0]]
             (query/rows (query/current (:relation snap)))))
      (is (= #{[1.0] [2.0]}
             (query/collect
               (query/q '[:find ?x :where [_ :sensor/x ?x]]
                        (query/current (:relation snap)))))))))


;; ---------------------------------------------------------------------------
;; E: the evaluator surface (match / pull / :in / negation / aggregation)
;; ---------------------------------------------------------------------------

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


(deftest match-materializes-over-a-current-view
  (let [src (query/current (rel sample-datoms))]
    (is (= [[1 :work/status :todo] [2 :work/status :done]]
           (query/match src ['_ :work/status '_])))))


(deftest match-accepts-a-relation-value-and-a-view
  (is (= [[1 :work/status :todo]]
         (query/match (query/current (rel sample-datoms)) [1 :work/status '_])))
  (is (= [[1 :work/status :todo]]
         (query/match (rel [[1 :work/status :todo]]) [1 :work/status '_]))))


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
;; :in bindings and the full evaluator surface
;; ---------------------------------------------------------------------------

(deftest in-bindings-scalar-and-relation
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


(deftest negation-and-aggregation
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
;; L: the lazy published path over opened covered indexes
;; ---------------------------------------------------------------------------

(defn- counting-content-store
  [store]
  (let [gets (atom 0)
        get-fn (:get-bytes-fn store)]
    {:store (assoc store
                   :get-bytes-fn (fn [address not-found]
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
  (let [s (:dao.stream/handle
            (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))]
    (doseq [d datoms] (stream/append! s d))
    s))


(defn- open-intake
  "A dao.stream ringbuffer intake writer."
  []
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity 4096})))


(defn- pool-state
  "Observer state over one v2 intake handle, entered at its oldest cursor."
  [s]
  (jing/observer-state
    [{:stream s
      :cursor (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))}]))


(defn- publish-into-file
  "Publish datoms into a fresh file content store and return
   {:store scratch-store :path path :manifest-address addr
    :coordinate published-index :opened opened-query-value}. The caller
   closes :opened through query/close-published!, :store through
   jing/close!, and removes :path."
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
                          (case (:signal r)
                            :dao.stream/ok (drain (:state r))
                            (:dao.stream/blocked :dao.stream/end) nil
                            (throw (ex-info "test observer hit a gap or defect" r)))))]
            (drain (pool-state intake))
            (let [coordinate (index/published-index
                               {:dao.jing/type :dao.jing/file, :path path}
                               manifest-address)]
              {:store store,
               :path path,
               :manifest-address manifest-address,
               :coordinate coordinate,
               :opened (query/open-published! coordinate)}))
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
        lazy-cur (query/current (:opened fx))]
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
         (finally (query/close-published! (:opened fx))
                  (jing/close! (:store fx))
                  (cleanup-file (:path fx))))))


(deftest opened-published-index-stays-open-across-queries
  (let [fx (publish-into-file [[42 :user/email "ada@example.com" 1 1]])
        form '[:find ?v :where [$ 42 :user/email ?v]]]
    (try
      #?(:cljd
         (do (is (= #{["ada@example.com"]}
                    (query/collect (query/q form (query/current (:opened fx))))))
             (is (= #{["ada@example.com"]}
                    (query/collect (query/q form (query/current (:opened fx)))))
                 "the same opened index answers a second query"))
         :default
         (let [closes (atom 0)
               orig-close jing/close!]
           (with-redefs [jing/close! (fn [handle]
                                       (swap! closes inc)
                                       (orig-close handle))]
             (is (= #{["ada@example.com"]}
                    (query/collect (query/q form (query/current (:opened fx))))))
             (is (zero? @closes)
                 "neither q nor collect closed the caller's store")
             (is (= #{["ada@example.com"]}
                    (query/collect (query/q form (query/current (:opened fx)))))
                 "the same opened index answers a second query after q and collect"))))
      (finally (query/close-published! (:opened fx))
               (jing/close! (:store fx))
               (cleanup-file (:path fx))))))


(deftest close-published-closes-once-and-is-idempotent
  (let [fx (publish-into-file [[1 :work/task "Code" 10 1]])]
    (try
      #?(:cljd
         (is true
             "the close-count spy needs with-redefs, unavailable on ClojureDart; the opened-value flow is covered by the equivalence tests")
         :default
         (let [closes (atom 0)
               orig-close jing/close!]
           (with-redefs [jing/close! (fn [handle]
                                       (swap! closes inc)
                                       (orig-close handle))]
             (query/close-published! (:opened fx))
             (is (= 1 @closes)
                 "close-published! closes the store it opened, exactly once")
             (query/close-published! (:opened fx))
             (is (= 1 @closes)
                 "a second close is a no-op"))))
      (finally (jing/close! (:store fx))
               (cleanup-file (:path fx))))))


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
                      (let [opened (query/open-published! (:coordinate fx))
                            query-form '[:find ?v :where [$ 42 :user/email ?v]]
                            res (query/collect (query/q query-form
                                                        (query/current
                                                          opened)))
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
                            "strictly fewer gets than reading full index")
                        (query/close-published! opened)))))
      (finally (query/close-published! (:opened fx))
               (jing/close! (:store fx))
               (cleanup-file (:path fx))))))


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
             (let [opened (query/open-published! (:coordinate fx))
                   query-form '[:find ?v ?age :where [$ 42 :user/email ?v]
                                [$ 42 :user/age ?age]]
                   res (query/collect (query/q query-form
                                               (query/current opened)))
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
                 "a full drain of this ~135-node tree would exceed 50 gets")
               (query/close-published! opened)))))
      (finally (query/close-published! (:opened fx))
               (jing/close! (:store fx))
               (cleanup-file (:path fx))))))


(deftest lazy-published-rest-pattern-regression
  (testing
    "a non-3-fixed / rest pattern clause falls back to the deferred rows and answers"
    (let [test-datoms [[1 :person/name "Ada" 0 1] [2 :person/name "Grace" 0 1]]
          fx (publish-into-file test-datoms)
          eager-cur (query/current (rel test-datoms))]
      (try
        (let [lazy-cur (query/current (:opened fx))
              query-form '[:find ?e ?name :where
                           [?e :person/name ?name & ?rest]]]
          (is
            (= #{[1 "Ada"] [2 "Grace"]}
               (query/collect (query/q query-form lazy-cur)))
            "literal anchor: the lazy fallback returns real rows, not an empty parity")
          (is (= (query/collect (query/q query-form eager-cur))
                 (query/collect (query/q query-form lazy-cur)))))
        (testing
          "match over a lazy current view forces the deferred rows and returns real tuples"
          (let [lazy-cur (query/current (:opened fx))
                pattern ['_ :person/name "Ada"]]
            (is (= [[1 :person/name "Ada"]] (query/match lazy-cur pattern))
                "literal anchor for match, not an eager/lazy-only parity")
            (is (= (query/match eager-cur pattern)
                   (query/match lazy-cur pattern)))))
        (finally (query/close-published! (:opened fx))
                 (jing/close! (:store fx))
                 (cleanup-file (:path fx)))))))


(deftest lazy-published-mixed-multi-source-join
  (testing
    "one lazy published current + one eager query/relation in a single q with :in $ $2"
    (let [users [[1 :user/name "Alice" 0 1] [2 :user/name "Bob" 0 1]]
          roles [[1 :user/role :admin] [2 :user/role :member]]
          fx (publish-into-file users)
          lazy-users (query/current (:opened fx))
          eager-roles (query/relation roles)]
      (try (let [query-form '[:find ?name ?role :in $users $roles :where
                              [$users ?e :user/name ?name]
                              [$roles ?e :user/role ?role]]]
             (is (= #{["Alice" :admin] ["Bob" :member]}
                    (query/collect
                      (query/q query-form lazy-users eager-roles)))))
           (finally (query/close-published! (:opened fx))
                    (jing/close! (:store fx))
                    (cleanup-file (:path fx)))))))


(deftest lazy-published-history-and-as-of-stay-eager-and-correct
  (let [test-datoms [[1 :item/status :draft 1 1] [1 :item/status :draft 2 0]
                     [1 :item/status :published 2 1]
                     [1 :item/status :archived 3 1]]
        fx (publish-into-file test-datoms)
        opened (:opened fx)
        eager-source (rel test-datoms)]
    (try
      (testing "history view over the opened index matches eager history"
        (let [q-hist '[:find ?v ?t ?op :where [1 :item/status ?v ?t ?op]]]
          (is (= (query/collect (query/q q-hist (query/history eager-source)))
                 (query/collect (query/q q-hist (query/history opened)))))))
      (testing
        "as-of current view over the opened index matches eager as-of"
        (let [q-cur '[:find ?v :where [1 :item/status ?v]]]
          (is (= (query/collect (query/q q-cur (query/current eager-source 2)))
                 (query/collect (query/q q-cur (query/current opened 2)))))
          (is (= #{[:published]}
                 (query/collect (query/q q-cur (query/current opened 2)))))))
      (testing
        "as-of history view over the opened index matches eager as-of history"
        (let [q-hist '[:find ?v ?t ?op :where [1 :item/status ?v ?t ?op]]]
          (is (= (query/collect (query/q q-hist (query/history eager-source 2)))
                 (query/collect (query/q q-hist (query/history opened 2)))))))
      (finally (query/close-published! opened)
               (jing/close! (:store fx))
               (cleanup-file (:path fx))))))


(deftest lazy-published-datoms-projections-match-eager
  (let [test-datoms [[1 :work/task "Code" 10 1] [1 :work/owner "Ada" 11 1]
                     [2 :work/task "Review" 12 1]]
        fx (publish-into-file test-datoms)
        lazy-cur (query/current (:opened fx))
        eager-cur (query/current (rel test-datoms))]
    (try
      (let [{lazy-idx ::query/fact-index}
            (query/realize-db-value! lazy-cur)
            {eager-idx ::query/fact-index}
            (query/realize-db-value! eager-cur)]
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
                   (project-eav (query/datoms lazy-idx '_ '_ "Ada")))))))
      (finally (query/close-published! (:opened fx))
               (jing/close! (:store fx))
               (cleanup-file (:path fx))))))


;; ---------------------------------------------------------------------------
;; L2: open-published!'s own contract — coordinate rejection, ownership of
;; the store it opens, and the opened value's shape. Moved here from the
;; index_test published-adapter deftests when the v1 adapter was deleted
;; (each property keeps its pin; the adapter's protocol vocabulary died
;; with it).
;; ---------------------------------------------------------------------------

(deftest open-published-rejects-unresolvable-and-malformed-coordinates
  (let [empty-manifest-addr (jing/segment-key
                              {:indexes
                               {:eavt nil, :aevt nil, :avet nil, :vaet nil},
                               :count 0,
                               :branching-factor 512})]
    (testing "an unsupported coordinate type fails closed at open"
      (let [c (index/published-index {:dao.jing/type :dao.jing/missing,
                                      :path "x"}
                                     empty-manifest-addr)]
        (is (thrown-with-msg? #?(:cljs js/Error
                                 :cljd Object
                                 :default Exception)
                              #"unsupported DaoJing content-store coordinate"
              (query/open-published! c))
            "the store coordinate is resolved explicitly, never inferred")))
    (testing "a malformed published-index coordinate is rejected at open"
      (let [bogus (assoc (index/published-index {:dao.jing/type :dao.jing/file,
                                                 :path "x"}
                                                empty-manifest-addr)
                         :extra/key :noise)]
        (is (thrown-with-msg? #?(:cljs js/Error
                                 :cljd Object
                                 :default Exception)
                              #"invalid published-index coordinate"
              (query/open-published! bogus)))))))


(deftest open-published-of-an-empty-manifest-yields-an-empty-relation
  (let [fx (publish-into-file [])]
    (try (let [opened (:opened fx)]
           (is (= [] (query/rows opened))
               "an empty manifest reads as no datoms, not an error")
           (is (= #{} (query/collect
                        (query/q '[:find ?e :where [?e _ _]]
                                 (query/current opened))))))
         (finally (query/close-published! (:opened fx))
                  (jing/close! (:store fx))
                  (cleanup-file (:path fx))))))


(deftest open-published-fetches-only-the-manifest
  (let [datoms (mapv (fn [i] [i :user/email (str "user-" i "@example.com") 0 1])
                     (range 64))
        fx (publish-into-file datoms)]
    (try
      (let [counter (counting-content-store (:store fx))]
        #?(:cljd
           (is true
               "the counting harness needs with-redefs, unavailable on ClojureDart")
           :default
           (with-redefs [jing-coordinate/open! (fn [_] (:store counter))]
             (let [opened (query/open-published! (:coordinate fx))]
               (is (= 1 ((:gets counter)))
                   "opening a published coordinate performs exactly one content fetch after open and before any read: the manifest, with zero tree nodes faulted")
               (query/close-published! opened)))))
      (finally (query/close-published! (:opened fx))
               (jing/close! (:store fx))
               (cleanup-file (:path fx))))))


(deftest open-published-carries-the-four-covered-sets
  (let [datoms (mapv (fn [i]
                       [i (keyword "work" (str "a" (mod i 7)))
                        (str "task-" i) 0 1])
                     (range 8))
        fx (publish-into-file datoms)]
    (try (let [idx (index/covered-indexes (:opened fx))]
           (is (map? idx))
           (is (= #{:eavt :aevt :avet :vaet} (set (keys idx))))
           (doseq [order [:eavt :aevt :avet :vaet]]
             (is (= (count datoms) (bt/count (order idx)))
                 (str order " covers the snapshot"))))
         (finally (query/close-published! (:opened fx))
                  (jing/close! (:store fx))
                  (cleanup-file (:path fx))))))


(deftest open-published-rows-match-the-eager-walk
  (let [datoms (vec (reverse
                      (mapv (fn [i]
                              [i :user/email
                               (str "user-" i "@example.com") 0 1])
                            (range 24))))         ; non-EAVT insertion order
        fx (publish-into-file datoms)]
    (try
      (let [eager (index/read-datoms (:store fx) (:manifest-address fx))]
        (is (= eager (query/rows (:opened fx)))
            "the opened index's deferred rows yield the eager EAVT walk")
        (is (= (sort index/eavt-cmp datoms) (query/rows (:opened fx)))
            "rows are EAVT order regardless of insertion order"))
      (finally (query/close-published! (:opened fx))
               (jing/close! (:store fx))
               (cleanup-file (:path fx))))))


(deftest published-index-is-transportable-plain-data
  (let [path (temp-content-path "descriptor")
        source-datoms [[2 :work/status :done 1 1] [1 :work/status :todo 0 1]]
        local (open-local source-datoms)
        intake (open-intake)
        store (jing-file/create-content-file path)]
    (try (let [{:keys [manifest-address]} (index/publish-index! local [intake])
               _ (loop [state (pool-state intake)]
                   (let [{:keys [signal state] :as r} (jing/observe-step! store
                                                                          state)]
                     (case signal
                       :dao.stream/ok (recur state)
                       (:dao.stream/blocked :dao.stream/end) nil
                       (throw (ex-info "test observer hit a gap or defect" r)))))
               coordinate (index/published-index {:dao.jing/type :dao.jing/file,
                                                  :path path}
                                                 manifest-address)
               transported (edn/read-string (pr-str coordinate))]
           (is (= coordinate transported) "the coordinate is plain EDN")
           (is (= {:dao.stream/type :dao.space.index/published,
                   :dao.stream/bound {:manifest-address manifest-address},
                   :dao.stream/comparator :dao.space.index/eavt,
                   :content-store {:dao.jing/type :dao.jing/file, :path path},
                   :manifest-address manifest-address}
                  transported)
               "the complete exact coordinate map — an explicit finite bound
                carried as data, not a lifecycle flag")
           (testing "the coordinate survives a stream round-trip unchanged"
             (let [carrier (ring-handle 4)]
               (is (= :dao.stream/ok
                      (:dao.stream/outcome (stream/append! carrier transported))))
               (let [r (stream/next carrier
                                    (:dao.stream/cursor
                                      (stream/cursor carrier
                                                     :dao.stream/oldest)))]
                 (is (= :dao.stream/ok (:dao.stream/outcome r)))
                 (is (= transported (:dao.stream/value r))))))
           (let [opened (query/open-published! transported)]
             (try (is (= (sort index/eavt-cmp source-datoms) (query/rows opened))
                      "the transported coordinate opens and reads normally")
                  (finally (query/close-published! opened)))))
         (finally (jing/close! store) (cleanup-file path)))))


(deftest failed-open-closes-the-store-it-opened
  (let [fx (publish-into-file [[1 :work/task "Code" 10 1]])]
    (try
      #?(:cljd
         (is true
             "the close-count harness needs with-redefs, unavailable on ClojureDart")
         :default
         (let [closes (atom 0)
               boom (ex-info "scripted manifest fetch failure"
                             {:mode :scripted})
               base (:store fx)
               base-close (:close-fn base)
               store (assoc base
                            :get-bytes-fn (fn [_address _not-found]
                                            (throw boom))
                            :close-fn (fn []
                                        (swap! closes inc)
                                        (base-close)))]
           (with-redefs [jing-coordinate/open! (fn [_] store)]
             (let [thrown (try (query/open-published! (:coordinate fx))
                               (catch #?(:clj Throwable
                                         :cljs :default
                                         :cljd Object)
                                      error
                                 error))]
               (is (identical? boom thrown)
                   "the original error propagates, not a wrapper")
               (is (= 1 @closes)
                   "the store opened during the failed open is closed exactly once before the error propagates")))))
      (finally (query/close-published! (:opened fx))
               (jing/close! (:store fx))
               (cleanup-file (:path fx))))))


;; ---------------------------------------------------------------------------
;; Recursive rules: free variables are queried, not tagged
;; ---------------------------------------------------------------------------
;; yin.vm.code-as-tuples.md §4.5/§7.7: whether a :variable row is free or
;; bound by an enclosing :lambda is derivable from the row structure alone,
;; so no :global tag exists anywhere in the rows. These tests run a
;; recursive rule set through the real q engine over the actual output of
;; yin.vm/ast->semantic-bytecode — the committed codec, not hand-typed
;; row literals.

(def ^:private member?
  "Membership over a :nodes/:syms slot vector. §6.3: predicate arguments
   must already be bound, so it is supplied under :fns."
  (fn [coll x] (boolean (some #(= % x) coll))))


(def ^:private free-name-rules
  "§2.3 slot kinds hardcoded for the fixture tags: :lambda's 3rd slot is
   the body, :application's 3rd the operator and 4th the operands vector.
   The operands edge joins every row id ([?c _ & _] matches any row, ids
   are always first) before member? filters, because a predicate cannot
   bind its own argument."
  '[[(edge ?p ?c) [?p :lambda _ ?c]]
    [(edge ?p ?c) [?p :application ?c _ _]]
    [(edge ?p ?c) [?p :application _ ?ops _] [?c _ & _] [(member? ?ops ?c)]]
    [(anc ?a ?d) (edge ?a ?d)]
    [(anc ?a ?d) (edge ?p ?d) (anc ?a ?p)]
    [(depth ?a ?d ?n) (edge ?a ?d) [(ground 1) ?n]]
    [(depth ?a ?d ?n) (edge ?p ?d) (depth ?a ?p ?m) [(inc ?m) ?n]]
    [(bound? ?v ?name) (anc ?l ?v) [?l :lambda ?params _] [(member? ?params ?name)]]])


(defn- ast-row-db
  "A relation value over the flat rows of ast->semantic-bytecode."
  [ast]
  (rel (vals (:rows (vm/ast->semantic-bytecode ast)))))


(defn- ast-row-id
  "The row id of the first row matching [id tag slot1]."
  [ast tag slot]
  (some (fn [row]
          (when (and (= tag (nth row 1)) (= slot (nth row 2)))
            (nth row 0)))
        (vals (:rows (vm/ast->semantic-bytecode ast)))))


(deftest recursive-rules-compute-free-names-not-tags
  ;; (fn [x] (+ x 1)) — §2.1: + is free, x is bound by the only :lambda.
  (let [ast {:type :lambda,
             :params ['x],
             :body {:type :application,
                    :operator {:type :variable, :name '+},
                    :operands [{:type :variable, :name 'x}
                               {:type :literal, :value 1}]}}
        db (ast-row-db ast)
        free (qq '[:find ?name :in $ %
                   :where
                   [?v :variable ?name]
                   (not (bound? ?v ?name))]
                 db free-name-rules
                 {:fns {'member? member?}})]
    (is (= #{['+]} free)
        "the free-name set is exactly + ; the bound param x is absent")))


(deftest recursive-rules-resolve-the-nearest-enclosing-binder
  ;; (fn [x y] (fn [x] (+ x y))) — the inner :lambda shadows x; y is bound
  ;; only by the outer :lambda, two enclosing :lambdas up.
  (let [ast {:type :lambda,
             :params ['x 'y],
             :body {:type :lambda,
                    :params ['x],
                    :body {:type :application,
                           :operator {:type :variable, :name '+},
                           :operands [{:type :variable, :name 'x}
                                      {:type :variable, :name 'y}]}}}
        db (ast-row-db ast)
        opts {:fns {'member? member?}}
        x (ast-row-id ast :variable 'x)
        y (ast-row-id ast :variable 'y)
        outer (ast-row-id ast :lambda ['x 'y])
        inner (ast-row-id ast :lambda ['x])
        binders (fn [v nm]
                  (qq '[:find ?lam :in $ % ?v ?name
                        :where
                        (anc ?lam ?v)
                        [?lam :lambda ?params _]
                        [(member? ?params ?name)]]
                      db free-name-rules v nm opts))
        binder-depths (fn [v nm]
                        (qq '[:find ?lam ?n :in $ % ?v ?name
                              :where
                              (anc ?lam ?v)
                              [?lam :lambda ?params _]
                              [(member? ?params ?name)]
                              (depth ?lam ?v ?n)]
                            db free-name-rules v nm opts))]
    (is (= outer (:root (vm/ast->semantic-bytecode ast)))
        "sanity: the params [x y] :lambda is the projected root")
    (is (= #{['+]}
           (qq '[:find ?name :in $ %
                 :where
                 [?v :variable ?name]
                 (not (bound? ?v ?name))]
               db free-name-rules opts))
        "x stays bound under shadowing and y stays bound by the outer :lambda")
    (is (= #{[inner] [outer]} (binders x 'x))
        "the x reference is enclosed by both shadowing :lambdas")
    (is (= #{[outer]} (binders y 'y))
        "y's only enclosing binder is the outer :lambda the rule had to
          walk past the inner one to reach")
    (is (= inner (first (apply min-key second (binder-depths x 'x))))
        "the nearest binder of x is the inner :lambda")
    (is (= outer (first (apply min-key second (binder-depths y 'y))))
        "the nearest binder of y is the outer :lambda, not the immediate
          :application parent")))


;; ---------------------------------------------------------------------------
;; Occurrence-aware rules: the mixed free/bound case the row-only query
;; cannot resolve
;; ---------------------------------------------------------------------------
;; §4.5: when a name is free at one occurrence and bound at another
;; (((fn [x] x) x)), content addressing collapses both :variable nodes to
;; one row with parent edges from both places, and the row-level bound?
;; above finds a binder through either edge — calling the whole row bound
;; and silently dropping the free occurrence. The fix is a places-not-
;; contents query over the occurrence relation (§2.5/§6.1): free/bound is
;; decided per occurrence by walking that occurrence's own path to its
;; ancestors. The relation is emitted by `yin.vm/occurrences` (U7, the
;; AST indexer's structural half) and the rules are the root-scoped
;; production set `yin.vm/occurrence-rules`. A path is a vector of slot
;; steps from the root in §2.5's encoding — a step is the slot's row
;; position (id 0, tag 1, first slot 2), an index into a nodes slot being
;; a single pair step `[position i]` (which is what makes every pop of a
;; real path a real path, so the recursive walk never leaves the tree).

(def ^:private unscoped-occurrence-rules
  "The occurrence rule set WITHOUT the ?root threading — kept as the
   contrast fixture for root-scoping, the exact gap §4.5 names: over a
   many-tree relation it can resolve one tree's occurrence against
   another tree's binder. Needs `member?`/`path-pop` under :fns
   (`yin.vm/occurrence-fns`)."
  '[[(p-up ?child ?parent) [(path-pop ?child) ?parent]]
    [(occ-anc ?a ?d) (p-up ?d ?a)]
    [(occ-anc ?a ?d) (p-up ?d ?m) (occ-anc ?a ?m)]
    [(occ-bound? ?path ?name)
     (occ-anc ?lam-path ?path)
     [$occ ?r ?lam-path ?lam]
     [?lam :lambda ?params _]
     [(member? ?params ?name)]]])


(deftest occurrence-aware-rules-resolve-mixed-free-and-bound-rows
  ;; ((fn [x] x) x) — the lambda body's x is bound, the operand x is free,
  ;; and both are the SAME :variable node in the map AST.
  (let [ast {:type :application,
             :operator {:type :lambda, :params ['x],
                        :body {:type :variable, :name 'x}},
             :operands [{:type :variable, :name 'x}]}
        bc (vm/ast->semantic-bytecode ast)
        db (ast-row-db ast)
        x (ast-row-id ast :variable 'x)
        lam (ast-row-id ast :lambda '[x])
        ;; the emitted relation, not a hand fixture: [root path row] for
        ;; the application at [], its operator :lambda at [2] (row
        ;; position 2, the first slot after id and tag), the lambda's
        ;; body x at [2 3] (body is :lambda's row-position-3 slot), and
        ;; the operand x at [[3 0]] (first of the operands' nodes slot).
        occ (rel (vm/occurrences bc))
        opts {:fns vm/occurrence-fns}
        free-occ-paths (qq '[:find ?path :in $ $occ % ?root
                             :where
                             [$occ ?root ?path ?v]
                             [?v :variable ?name]
                             (not (occ-bound? ?root ?path ?name))]
                           db occ vm/occurrence-rules (:root bc) opts)
        bound-occ-paths (qq '[:find ?path :in $ $occ % ?root ?v ?name
                              :where
                              [$occ ?root ?path ?v]
                              (occ-bound? ?root ?path ?name)]
                            db occ vm/occurrence-rules (:root bc) x 'x opts)]
    (is (= 2 (count (filter #(= {:type :variable, :name 'x} %)
                            (tree-seq coll? seq ast))))
        "premise, map side: the fixture really has two :variable x nodes")
    (is (= #{[x]}
           (qq '[:find ?v :in $ ?name :where [?v :variable ?name]]
               db 'x))
        "premise, row side: both collapse to one shared row id (§4.4)")
    (is (= #{[(:root bc) [] (:root bc)]
             [(:root bc) [2] lam]
             [(:root bc) [2 3] x]
             [(:root bc) [[3 0]] x]}
           (vm/occurrences bc))
        "the emitter walks the same places the old hand fixture named:
          one [root path node] per place, two tuples over the one shared
          row")
    (is (= #{[[2 3]]} bound-occ-paths)
        "of the shared row's two occurrences, only the lambda-body one is
          bound — the walk went up [2 3] → [2] and found the :lambda")
    (is (= #{[[[3 0]]]} free-occ-paths)
        "the operand occurrence [[3 0]] is free — its walk [[3 0]] → []
          reaches only the :application, never a binding :lambda")
    (is (= #{'x} (vm/free-names db occ (:root bc)))
        "the §7.7 free-name extraction over the emitted relation
          conservatively includes x because at least one occurrence of it
          is free (§7.6.1)")
    (is (= #{}
           (qq '[:find ?name :in $ %
                 :where
                 [?v :variable ?name]
                 (not (bound? ?v ?name))]
               db free-name-rules
               {:fns {'member? member?}}))
        "contrast: on this same db the row-only rule finds the :lambda
          through either of the shared row's parent edges and drops x
          from the free-name set entirely — the §4.5 undercount")))


(deftest occurrence-rules-are-root-scoped-across-trees
  ;; §4.5's named gap, closed: two trees whose relations are unioned (§6.1)
  ;; carry the SAME literal path [3] — :lambda's body slot — with x bound
  ;; in A ((fn [x] x)) and free in B ((fn [y] x)). Each tree's occurrence
  ;; must classify against its own binder.
  (let [bcA (vm/ast->semantic-bytecode
              {:type :lambda, :params ['x],
               :body {:type :variable, :name 'x}})
        bcB (vm/ast->semantic-bytecode
              {:type :lambda, :params ['y],
               :body {:type :variable, :name 'x}})
        rootA (:root bcA)
        rootB (:root bcB)
        x (ast-row-id {:type :variable, :name 'x} :variable 'x)
        db (rel (concat (vals (:rows bcA)) (vals (:rows bcB))))
        occ (rel (concat (vm/occurrences bcA) (vm/occurrences bcB)))]
    (is (not= rootA rootB)
        "premise: the two trees differ in params, so their roots are
          distinct addresses")
    (is (= x (nth (get (:rows bcB) rootB) 3))
        "premise: B's body :variable row is the same shared x row as A's")
    (is (contains? (vm/occurrences bcA) [rootA [3] x])
        "premise: path [3] in tree A names the shared x row")
    (is (contains? (vm/occurrences bcB) [rootB [3] x])
        "premise: the SAME literal path [3] in tree B names it too")
    (is (= #{} (vm/free-names db occ rootA))
        "A's x is bound by A's own [x] :lambda")
    (is (= #{'x} (vm/free-names db occ rootB))
        "B's x stays free: B's own binder is the [y] :lambda, and A's
          binder — reachable only by leaving B's root — never classifies
          it, which is exactly what the ?root threading prevents")
    (is (= #{}
           (qq '[:find ?name :in $ $occ %
                 :where
                 [$occ ?r ?path ?v]
                 [?v :variable ?name]
                 (not (occ-bound? ?path ?name))]
               db occ unscoped-occurrence-rules
               {:fns vm/occurrence-fns}))
        "contrast: the unscoped rule set finds A's :lambda through the
          root occurrence both trees share and calls B's free x bound —
          the cross-tree misclassification, demonstrated not narrated")))


(deftest occurrence-walk-covers-the-whole-grammar
  ;; Grammar-genericity, tested rather than argued (§4.5): binding is
  ;; resolved through :if / :stream/put / :stream/cursor edges — tags the
  ;; row-only edge rules have no clause for — and freeness is walked
  ;; through :vm/resume / :stream/close / :stream/next.
  (let [ast {:type :if,
             :test {:type :vm/store-get, :key :flag},
             :consequent {:type :lambda, :params ['s],
                          :body {:type :stream/put,
                                 :target {:type :stream/cursor,
                                          :source {:type :variable, :name 's}},
                                 :val {:type :stream/make}}},
             :alternate {:type :vm/resume, :parked-id :parked/echo,
                         :val {:type :stream/close,
                               :source {:type :stream/next,
                                        :source {:type :variable, :name 'k}}}}}
        bc (vm/ast->semantic-bytecode ast)
        db (ast-row-db ast)
        occ (rel (vm/occurrences bc))
        s (ast-row-id ast :variable 's)
        k (ast-row-id ast :variable 'k)
        opts {:fns vm/occurrence-fns}
        bound-paths (fn [v nm]
                      (qq '[:find ?path :in $ $occ % ?root ?v ?name
                            :where
                            [$occ ?root ?path ?v]
                            (occ-bound? ?root ?path ?name)]
                          db occ vm/occurrence-rules (:root bc) v nm opts))]
    (is (= 11 (count (vm/occurrences bc)))
        "one tuple per place across :if, :vm/store-get, :lambda,
          :stream/put, :stream/cursor, :stream/make, :vm/resume,
          :stream/close, :stream/next, and the two :variable rows")
    (is (contains? (vm/occurrences bc) [(:root bc) [3 3 2 2] s])
        "s sits four node steps deep: :if consequent [3], :lambda body
          [3 3], :stream/put target [3 3 2], :stream/cursor source
          [3 3 2 2]")
    (is (contains? (vm/occurrences bc) [(:root bc) [4 3 2 2] k])
        "k sits at the same depth the other side: :if alternate [4],
          :vm/resume val [4 3], :stream/close source [4 3 2],
          :stream/next source [4 3 2 2]")
    (is (= #{[[3 3 2 2]]} (bound-paths s 's))
        "the path walk finds s's binder through tags no row-level edge
          clause covers — :if → :lambda → :stream/put → :stream/cursor")
    (is (= #{} (bound-paths k 'k))
        "k's only ancestors are :stream/next, :stream/close, :vm/resume,
          and :if — no binder anywhere above it")
    (is (= #{'k} (vm/free-names db occ (:root bc)))
        "the §7.7 free-name extraction query over the emitted relation")
    (is (= #{['k] ['s]}
           (qq '[:find ?name :in $ %
                 :where
                 [?v :variable ?name]
                 (not (bound? ?v ?name))]
               db free-name-rules
               {:fns {'member? member?}}))
        "contrast: the row-only rules have no :if/:stream edge clauses,
          so they cannot reach s's binder and overcount s as free —
          §4.5's conservative-but-imprecise failure mode, now tested")))


;; ---------------------------------------------------------------------------
;; §7.7 syntactic extraction and the §7.7.1 footprint table (U6)
;; ---------------------------------------------------------------------------
;; The code part of UCF §7.6.1's conservative fixed point: queries over
;; $ast (flat rows of the reachable trees) and $code (segment-qualified
;; rows of the reachable segments). §7.7.1's conformance obligation: for
;; every corpus tree, the requirement set from the tree equals the one
;; from its lowered segment in every field the syntactic queries fill.

(def ^:private requirements-kitchen-sink
  "One tree touching every requirement the syntactic queries fill: both
   store directions, FFI, a parked id, every effect-raising tag, and the
   numeric-key criterion (item 2) in the same tree."
  {:type :if,
   :test {:type :vm/store-get, :key 'k},
   :consequent {:type :stream/put,
                :target {:type :stream/make, :buffer 8},
                :val {:type :stream/next,
                      :source {:type :stream/cursor,
                               :source {:type :variable, :name 's}}}},
   :alternate {:type :vm/resume,
               :parked-id :p1,
               :val {:type :dao.stream.apply/call,
                     :op :op/echo,
                     :operands [{:type :vm/store-put, :key 99, :val 1}
                                {:type :vm/gensym, :prefix "g"}
                                {:type :vm/current-continuation}
                                {:type :vm/park}
                                {:type :stream/close,
                                 :source {:type :variable, :name 's}}]}}})


(def ^:private requirements-corpus
  "`parity/corpus` plus the kitchen sink and single-node key trees, so the
   conformance equality runs over programs that fill every field and
   programs that fill none."
  (into (map (fn [[name ast]] [name ast]) parity/corpus)
        [["kitchen sink" requirements-kitchen-sink]
         ["numeric store key" {:type :vm/store-put, :key 99, :val 1}]
         ["keyword store key" {:type :vm/store-get, :key :k}]
         ["park" {:type :vm/park}]]))


(defn- tree-db
  "The `$ast` relation of one tree: its flat rows."
  [ast]
  (rel (vals (:rows (vm/ast->semantic-bytecode ast)))))


(defn- segment-db
  "The `$code` relation of one tree's lowered segment: its
   segment-qualified rows."
  [ast]
  (rel (code/project-segment-qualified
         (:vector (linearize/lower-rows (vm/ast->semantic-bytecode ast))))))


(deftest footprint-table-has-a-row-for-every-tag-and-mnemonic
  (let [table (get vm/footprint-table "v2")]
    (is (= (set (keys vm/semantic-bytecode-grammar)) (set (keys (:tags table))))
        "every §2.3 tag: no external effect is an explicit #{}, never an
          absence (§7.7.1)")
    (is (= code/mnemonics (set (keys (:mnemonics table))))
        "every §2.4 mnemonic")
    (is (= #{:stream/make :stream/put :stream/cursor :stream/next
             :stream/close}
           (into #{} (mapcat val) (:tags table)))
        "the identifiers contributed are exactly the effects the machine
          raises (`{:effect …}` in semantic.cljc's hot loop); FFI, park,
          resume, gensym, store, and current-continuation contribute none")))


(deftest ast-requirements-extract-each-field
  (is (= {:store-keys #{99 'k},
          :ffi-ops #{:op/echo},
          :parked-ids #{:p1},
          :effects #{:stream/make :stream/put :stream/cursor :stream/next
                     :stream/close}}
         (vm/ast-requirements (tree-db requirements-kitchen-sink)))
      "both store directions, the FFI op, the parked id, and every
        effect-raising tag normalized by the footprint table — an FFI call
        contributes no effect identifier"))


(deftest requirement-sets-from-tree-and-segment-are-equal
  (doseq [[name ast] requirements-corpus]
    (testing name
      (is (= (vm/ast-requirements (tree-db ast))
             (vm/segment-requirements (segment-db ast)))
          "§7.7.1's conformance obligation: equal in store keys, FFI ops,
            parked ids, and normalized effects — the tree's tags and the
            segment's mnemonics normalize to the same vocabulary"))))


(deftest code-joins-store-put-key-to-ast-by-value
  ;; U6's criteria: a $code query joining a :store-put key to a
  ;; :vm/store-put row by value across the two relations — tree rows and
  ;; segment rows share operand values even though they share no ids
  (let [ast requirements-kitchen-sink
        joined (qq '[:find ?key :in $ast $code
                     :where [$ast _ :vm/store-put ?key _]
                     [$code _ _ :store-put ?key _]]
                   (tree-db ast) (segment-db ast))]
    (is (= #{[99]} joined)
        "the numeric store-put key joins its tree row to its segment row by
          value")))
