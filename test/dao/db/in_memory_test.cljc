(ns dao.db.in-memory-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.db.in-memory :as in-m]
    [datomworld :as dw]
    [yin.vm :as vm]
    [yin.vm.semantic :as semantic]))


;; =============================================================================
;; Helpers
;; =============================================================================

(defn- get-attr
  "Get a single attribute value for eid from db."
  [db eid attr]
  (:v (first (filter #(= attr (:a %))
                     (filter #(= eid (:e %)) (in-m/native-datoms db :eavt))))))


;; =============================================================================
;; Tx Pipeline
;; =============================================================================

(deftest tx-pipeline-test

  (testing "4-tuple form [:db/add e a v] — m defaults to 0"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (= "Alice" (get-attr db 1025 :name)))
      (is (= 0 (:m (first (filter #(= :name (:a %)) (in-m/native-datoms db :eavt))))))))

  (testing "5-tuple form [:db/add e a v m]"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Bob" 42]])]
      (is (= 42 (:m (first (filter #(= :name (:a %)) (in-m/native-datoms db :eavt))))))))

  (testing "map tx-data form with :db/id"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [{:db/id 1025 :name "Carol"}])]
      (is (= "Carol" (get-attr db 1025 :name)))))

  (testing "map tx-data form without :db/id — auto tempid"
    (let [db (in-m/empty-db)
          {:keys [db tempids]} (in-m/run-tx db [{:name "Dave"}])]
      (is (= 1 (count tempids)))
      (let [eid (first (vals tempids))]
        (is (= "Dave" (get-attr db eid :name))))))

  (testing "map-m expansion — map :m creates metadata entity"
    (let [db        (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Eve" {:source "test"}]])
          name-datom (first (filter #(= :name (:a %)) (in-m/native-datoms db :eavt)))
          meta-eid  (:m name-datom)]
      (is (pos? meta-eid))
      (is (= "test" (get-attr db meta-eid :source)))))

  (testing "tempid resolution — negative eids resolve to positive"
    (let [db (in-m/empty-db)
          {:keys [db tempids]} (in-m/run-tx db [[:db/add -1 :name "Fred"]
                                                [:db/add -2 :name "Gina"]])]
      (is (= 2 (count tempids)))
      (is (every? pos? (vals tempids)))
      (is (= "Fred" (get-attr db (get tempids -1) :name)))
      (is (= "Gina" (get-attr db (get tempids -2) :name)))))

  (testing "cardinality-one: second :db/add replaces first"
    (let [db     (in-m/create {:color {:db/cardinality :db.cardinality/one}})
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :color :red]])
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :color :blue]])
          colors (filter #(= :color (:a %)) (in-m/native-datoms db :eavt))]
      (is (= 1 (count colors)))
      (is (= :blue (:v (first colors))))))

  (testing "cardinality-many: multiple values accumulate"
    (let [db (in-m/create {:tags {:db/cardinality :db.cardinality/many}})
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :tags "a"]])
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :tags "b"]])
          tags (map :v (filter #(= :tags (:a %)) (in-m/native-datoms db :eavt)))]
      (is (= #{"a" "b"} (set tags)))))

  (testing ":db/retract removes the specific [e a v] from all indexes"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Holly"]])
          {:keys [db]} (in-m/run-tx db [[:db/retract 1025 :name "Holly"]])]
      (is (empty? (filter #(= :name (:a %)) (in-m/native-datoms db :eavt))))
      (is (empty? (filter #(= :name (:a %)) (in-m/native-datoms db :aevt)))))))


;; =============================================================================
;; Query Engine
;; =============================================================================

(deftest query-engine-test

  (testing "single variable — find all eids with a given attr"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]
                                        [:db/add 1026 :name "Bob"]])]
      (is (= #{[1025] [1026]}
             (in-m/q '[:find ?e :where [?e :name _]] db)))))

  (testing "two-variable join — attribute and value bound"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]
                                        [:db/add 1026 :name "Bob"]])]
      (is (= #{["Alice"] ["Bob"]}
             (in-m/q '[:find ?v :where [_ :name ?v]] db)))))

  (testing "multi-clause join — entity with two matching attrs"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]
                                        [:db/add 1025 :age 30]
                                        [:db/add 1026 :name "Bob"]])]
      (is (= #{[1025]}
             (in-m/q '[:find ?e :where [?e :name _] [?e :age _]] db)))))

  (testing ":in $ ?x — scalar input binding"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]
                                        [:db/add 1026 :name "Bob"]])]
      (is (= #{[1025]}
             (in-m/q '[:find ?e :in $ ?n :where [?e :name ?n]] db "Alice")))))

  (testing "empty result — no matching datoms"
    (let [db (in-m/empty-db)]
      (is (= #{} (in-m/q '[:find ?e :where [?e :name "Nobody"]] db))))))


;; =============================================================================
;; Record and Schema
;; =============================================================================

(deftest record-test

  (testing "empty-db has bootstrap schema entities (eids 2-13)"
    (let [db (in-m/empty-db)
          ident-datoms (filter #(= :db/ident (:a %)) (in-m/native-datoms db :eavt))]
      (is (= 12 (count ident-datoms)))
      (is (some #(= :db/ident (:v %)) ident-datoms))
      (is (some #(= :db.type/ref (:v %)) ident-datoms))))

  (testing "create with yin.vm/schema installs ref and card-many attrs"
    (let [db (in-m/create vm/schema)]
      (is (contains? (:ref-attrs db) :yin/operator))
      (is (contains? (:ref-attrs db) :yin/body))
      (is (contains? (:card-many db) :yin/operands))))

  (testing "entity-attrs returns map of attr->val"
    (let [db    (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]
                                        [:db/add 1025 :age 30]])
          attrs (in-m/native-entity-attrs db 1025)]
      (is (= "Alice" (:name attrs)))
      (is (= 30 (:age attrs)))))

  (testing "entity-attrs accumulates card-many values as vectors"
    (let [db    (in-m/create {:tags {:db/cardinality :db.cardinality/many}})
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :tags "a"]
                                        [:db/add 1025 :tags "b"]])
          attrs (in-m/native-entity-attrs db 1025)]
      (is (= #{"a" "b"} (set (:tags attrs))))))

  (testing "find-eids-by-av scans AEVT when attr not in indexed-attrs"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]
                                        [:db/add 1026 :name "Bob"]])]
      (is (= #{1025} (in-m/native-find-eids-by-av db :name "Alice")))))

  (testing "datoms :eavt sorted by e then a"
    (let [db         (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1026 :age 30]
                                        [:db/add 1025 :name "Alice"]])
          user-datoms (filter #(> (:e %) 1024) (in-m/native-datoms db :eavt))
          eids        (map :e user-datoms)]
      (is (= eids (sort eids)))))

  (testing "as-of filters datoms by t"
    (let [db  (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "v1"]])  ; t=1
          {:keys [db]} (in-m/run-tx db [[:db/add 1026 :name "v2"]])  ; t=2
          db-at-1 (in-m/as-of db 1)]
      (is (some #(= 1025 (:e %)) (in-m/native-datoms db-at-1 :eavt)))
      (is (not (some #(= 1026 (:e %)) (in-m/native-datoms db-at-1 :eavt))))))

  (testing "since filters datoms strictly after t"
    (let [db  (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "v1"]])  ; t=1
          {:keys [db]} (in-m/run-tx db [[:db/add 1026 :name "v2"]])  ; t=2
          db-after-1 (in-m/since db 1)]
      (is (not (some #(= 1025 (:e %)) (in-m/native-datoms db-after-1 :eavt))))
      (is (some #(= 1026 (:e %)) (in-m/native-datoms db-after-1 :eavt)))))

  (testing "since inherits pre-cutoff schema so VAET/AVET are populated for post-cutoff data"
    ;; :friend declared as ref at t=1 (before cutoff), data added at t=2 (after cutoff).
    ;; (since db 1) must still classify :friend as ref and populate VAET.
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add -1 :db/ident :friend]
                                        [:db/add -1 :db/valueType :db.type/ref]])  ; t=1
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :friend 1026]])              ; t=2
          snap (in-m/since db 1)]
      (is (contains? (:ref-attrs snap) :friend))
      (is (seq (in-m/native-datoms snap :vaet [1026 :friend])))))

  (testing "as-of shows retracted datom at time of assertion"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])    ; t=1
          {:keys [db]} (in-m/run-tx db [[:db/retract 1025 :name "Alice"]]) ; t=2
          db-at-1 (in-m/as-of db 1)
          db-at-2 (in-m/as-of db 2)]
      (is (some #(= 1025 (:e %)) (in-m/native-datoms db-at-1 :eavt)))
      (is (not (some #(= 1025 (:e %)) (in-m/native-datoms db-at-2 :eavt))))))

  (testing "as-of shows correct value before cardinality-one overwrite"
    (let [db (in-m/create {:color {:db/cardinality :db.cardinality/one}})
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :color :red]])    ; t=2
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :color :blue]])   ; t=3
          db-at-2 (in-m/as-of db 2)
          db-at-3 (in-m/as-of db 3)]
      (is (= :red  (:v (first (in-m/native-datoms db-at-2 :eavt [1025 :color])))))
      (is (= :blue (:v (first (in-m/native-datoms db-at-3 :eavt [1025 :color])))))))

  (testing "as-of snapshot has historical basis-t"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "v1"]])  ; t=1
          {:keys [db]} (in-m/run-tx db [[:db/add 1026 :name "v2"]])] ; t=2
      (is (= 1 (in-m/native-basis-t (in-m/as-of db 1))))
      (is (= 2 (in-m/native-basis-t (in-m/as-of db 2))))))

  (testing "as-of snapshot has historical schema caches"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add -1 :db/ident :tags]
                                        [:db/add -1 :db/cardinality :db.cardinality/many]])
          db-at-0 (in-m/as-of db 0)
          db-at-1 (in-m/as-of db 1)]
      (is (not (contains? (:card-many db-at-0) :tags)))
      (is (contains? (:card-many db-at-1) :tags))))

  (testing "as-of snapshot populates VAET/AVET using historical ref-attrs"
    ;; Declare :friend as ref at t=1, add data at t=2.
    ;; as-of at t=2 must have :friend in ref-attrs AND datoms in :vaet.
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add -1 :db/ident :friend]
                                        [:db/add -1 :db/valueType :db.type/ref]])   ; t=1
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :friend 1026]])               ; t=2
          snap (in-m/as-of db 2)]
      (is (contains? (:ref-attrs snap) :friend))
      (is (seq (in-m/native-datoms snap :vaet [1026 :friend]))))))


;; =============================================================================
;; Integration: yin.vm/schema + AST + SemanticVM
;; =============================================================================

(deftest integration-test

  (testing "load yin.vm/schema, transact (+ 1 2) AST, run SemanticVM"
    (let [;; Create db with yin.vm schema
          db (in-m/create vm/schema)
          ;; Build AST datoms for (+ 1 2)
          ast {:type     :application
               :operator {:type :variable :name '+}
               :operands [{:type :literal :value 1}
                          {:type :literal :value 2}]}
          [root-tempid ast-raw-datoms] (vm/ast->datoms-with-root ast)
          ;; Convert 5-elem datom vectors to [:db/add e a v] tx-data
          tx-data (vm/datoms->tx-data ast-raw-datoms)
          {:keys [db tempids]} (in-m/run-tx db tx-data)
          actual-root (get tempids root-tempid)
          ;; Extract all datoms as 5-elem vectors for SemanticVM
          all-datoms (mapv (fn [d] [(:e d) (:a d) (:v d) (:t d) (:m d)])
                           (in-m/native-datoms db :eavt))
          ;; Run SemanticVM
          svm (-> (semantic/create-vm)
                  (vm/load-program {:node actual-root :datoms all-datoms})
                  (vm/run))]
      (is (some? actual-root))
      (is (= 3 (vm/value svm))))))


;; =============================================================================
;; Query :in binding forms
;; =============================================================================

(deftest query-in-forms-test

  (testing "collection binding [?name ...] — only listed names returned"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]
                                        [:db/add 1026 :name "Bob"]
                                        [:db/add 1027 :name "Carol"]])]
      (is (= #{["Alice"] ["Bob"]}
             (in-m/q '[:find ?name
                       :in $ [?name ...]
                       :where [_ :name ?name]]
                     db ["Alice" "Bob"])))))

  (testing "empty collection binding returns #{}"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (= #{}
             (in-m/q '[:find ?name
                       :in $ [?name ...]
                       :where [_ :name ?name]]
                     db [])))))

  (testing "tuple binding [?name ?age] — both vars bound"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]
                                        [:db/add 1025 :age 30]
                                        [:db/add 1026 :name "Bob"]
                                        [:db/add 1026 :age 25]])]
      (is (= #{[1025]}
             (in-m/q '[:find ?e
                       :in $ [?name ?age]
                       :where [?e :name ?name]
                       [?e :age ?age]]
                     db ["Alice" 30])))))

  (testing "relation binding [[?name ?age] ...] — multi-row expansion"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]
                                        [:db/add 1025 :age 30]
                                        [:db/add 1026 :name "Bob"]
                                        [:db/add 1026 :age 25]
                                        [:db/add 1027 :name "Carol"]
                                        [:db/add 1027 :age 40]])]
      (is (= #{["Alice" 30] ["Bob" 25]}
             (in-m/q '[:find ?name ?age
                       :in $ [[?name ?age] ...]
                       :where [?e :name ?name]
                       [?e :age ?age]]
                     db [["Alice" 30] ["Bob" 25]])))))

  (testing "multi-database cross-join on shared attribute"
    (let [local  (in-m/empty-db)
          remote (in-m/empty-db)
          {:keys [db]} (in-m/run-tx local  [[:db/add 1025 :email "a@x.com"]
                                            [:db/add 1025 :role  "admin"]])
          local  db
          {:keys [db]} (in-m/run-tx remote [[:db/add 2001 :email "a@x.com"]
                                            [:db/add 2001 :score 99]])
          remote db]
      (is (= #{["admin" 99]}
             (in-m/q '[:find ?role ?score
                       :in $local $remote
                       :where [$local  ?e1 :email ?email]
                       [$local  ?e1 :role  ?role]
                       [$remote ?e2 :email ?email]
                       [$remote ?e2 :score ?score]]
                     local remote)))))

  (testing "scalar + collection cross-join — both constraints apply"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]
                                        [:db/add 1025 :role "admin"]
                                        [:db/add 1026 :name "Bob"]
                                        [:db/add 1026 :role "user"]
                                        [:db/add 1027 :name "Carol"]
                                        [:db/add 1027 :role "admin"]])]
      (is (= #{["Alice"] ["Carol"]}
             (in-m/q '[:find ?name
                       :in $ ?role [?name ...]
                       :where [?e :name ?name]
                       [?e :role ?role]]
                     db "admin" ["Alice" "Carol" "Bob"]))))))


;; =============================================================================
;; MEAT index
;; =============================================================================

(deftest meat-index-test

  (testing "datom with m=0 does NOT appear in MEAT"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (empty? (in-m/native-datoms db :meat)))))

  (testing "datom with m!=0 appears in MEAT"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice" 42]])]
      (is (= 1 (count (in-m/native-datoms db :meat))))
      (is (= 42 (:m (first (in-m/native-datoms db :meat)))))))

  (testing "seek-m — filter MEAT by m value"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice" 42]
                                        [:db/add 1026 :name "Bob"   99]
                                        [:db/add 1027 :name "Carol" 42]])]
      (let [results (in-m/native-datoms db :meat [42])]
        (is (= 2 (count results)))
        (is (every? #(= 42 (:m %)) results)))))

  (testing "seek-me — filter MEAT by m and e"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice" 42]
                                        [:db/add 1025 :age  30     42]
                                        [:db/add 1026 :name "Bob"   42]])]
      (let [results (in-m/native-datoms db :meat [42 1025])]
        (is (= 2 (count results)))
        (is (every? #(and (= 42 (:m %)) (= 1025 (:e %))) results)))))

  (testing "as-of filters MEAT by t"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "v1" 10]])
          {:keys [db]} (in-m/run-tx db [[:db/add 1026 :name "v2" 10]])
          db-at-1 (in-m/as-of db 1)]
      (is (some #(= 1025 (:e %)) (in-m/native-datoms db-at-1 :meat)))
      (is (not (some #(= 1026 (:e %)) (in-m/native-datoms db-at-1 :meat))))))

  (testing "retract removes datom from MEAT"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice" 42]])
          {:keys [db]} (in-m/run-tx db [[:db/retract 1025 :name "Alice"]])]
      (is (empty? (in-m/native-datoms db :meat))))))


;; =============================================================================
;; Three-Component Protocols
;; =============================================================================

(deftest three-protocol-test

  (testing "IDaoStorage/latest-t returns basis-t"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (= 1 (in-m/latest-t db)))))

  (testing "IDaoStorage/read-segments returns datoms grouped by t"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])
          {:keys [db]} (in-m/run-tx db [[:db/add 1026 :name "Bob"]])
          segs (in-m/read-segments db 1 2)]
      (is (= 2 (count segs)))
      (is (every? seq segs))))

  (testing "read-segments returns 3-element [t added retracted] entries"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])
          segs (in-m/read-segments db 1 1)]
      (is (= 1 (count segs)))
      (is (= 3 (count (first segs))))
      (is (= 1 (ffirst segs)))))

  (testing "write-segment! returns updated db with segment applied"
    (let [db0 (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db0 [[:db/add 1025 :name "Alice"]])
          entry (first (in-m/read-segments db 1 1))   ; [t added retracted]
          db2 (in-m/write-segment! (in-m/empty-db) entry)]
      (is (map? db2))
      (is (some #(= 1025 (:e %)) (in-m/native-datoms db2 :eavt)))))

  (testing "write-segment! advances latest-t"
    (let [db0 (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db0 [[:db/add 1025 :name "Alice"]])
          entry (first (in-m/read-segments db 1 1))
          db2 (in-m/write-segment! (in-m/empty-db) entry)]
      (is (= 1 (in-m/latest-t db2)))))

  (testing "write-segment! advances next-eid so later tempids do not reuse imported eids"
    (let [db0 (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db0 [[:db/add 1025 :name "Alice"]])
          entry (first (in-m/read-segments db 1 1))
          imported (in-m/write-segment! (in-m/empty-db) entry)
          {:keys [db tempids]} (in-m/run-tx imported [[:db/add -1 :name "Bob"]])
          bob-eid (get tempids -1)]
      (is (= 1026 bob-eid))
      (is (= "Alice" (:name (in-m/native-entity-attrs db 1025))))
      (is (= "Bob" (:name (in-m/native-entity-attrs db bob-eid))))))

  (testing "run-tx should advance next-eid past manual metadata entity ids"
    (let [db0 (in-m/empty-db)
          ;; Manually use 5000 as a metadata ID
          {:keys [db]} (in-m/run-tx db0 [[:db/add 1025 :name "Alice" 5000]])]
      (is (>= (:next-eid db) 5001)
          "Allocator should advance past the manually provided metadata EID 5000")))

  (testing "run-tx should advance next-eid when ref-attr is defined in same tx"
    (let [db0 (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db0 [[:db/add -1 :db/ident :friend]
                                         [:db/add -1 :db/valueType :db.type/ref]
                                         [:db/add 1025 :friend 5000]])]
      (is (>= (:next-eid db) 5001)
          "Allocator should advance past 5000 even if :friend was defined in this tx")))

  (testing "write-segment! advances next-eid past manual metadata entity ids"
    (let [db0 (in-m/empty-db)
          ;; manually use 5000 as a metadata ID
          entry [1 [(dw/->Datom 1025 :name "Alice" 1 5000)] []]
          imported (in-m/write-segment! db0 entry)]
      (is (>= (:next-eid imported) 5001))))

  (testing "write-segment! advances next-eid when ref-attr is defined in same segment"
    (let [db0 (in-m/empty-db)
          ;; Define :friend as ref and use it with EID 5000 in same segment
          entry [1 [(dw/->Datom 200 :db/ident :friend 1 0)
                    (dw/->Datom 200 :db/valueType :db.type/ref 1 0)
                    (dw/->Datom 1025 :friend 5000 1 0)] []]
          imported (in-m/write-segment! db0 entry)]
      (is (>= (:next-eid imported) 5001))))

  (testing "write-segment! faithfully replays retraction from read-segments"

    (let [db0 (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db0 [[:db/add 1025 :name "Alice"]])
          {:keys [db]} (in-m/run-tx db [[:db/retract 1025 :name "Alice"]])
          entries (in-m/read-segments db 1 2)
          db2 (reduce in-m/write-segment! (in-m/empty-db) entries)]
      (is (empty? (in-m/native-datoms db2 :eavt [1025 :name])))))

  (testing "write-segment! rebuilds schema caches and secondary indexes after import"
    ;; Replay schema + data entries through write-segment!; the resulting db must
    ;; have :friend in ref-attrs and the data datom in :vaet.
    (let [src (in-m/empty-db)
          {:keys [db]} (in-m/run-tx src [[:db/add -1 :db/ident :friend]
                                         [:db/add -1 :db/valueType :db.type/ref]])  ; t=1
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :friend 1026]])               ; t=2
          entries (in-m/read-segments db 1 2)
          dst (reduce in-m/write-segment! (in-m/empty-db) entries)]
      (is (contains? (:ref-attrs dst) :friend))
      (is (seq (in-m/native-datoms dst :vaet [1026 :friend])))))

  (testing "IDaoStorage/read-segments returns only txns in range"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])  ; t=1
          {:keys [db]} (in-m/run-tx db [[:db/add 1026 :name "Bob"]])    ; t=2
          {:keys [db]} (in-m/run-tx db [[:db/add 1027 :name "Carol"]])] ; t=3
      (is (= 1 (count (in-m/read-segments db 2 2))))
      (is (= 2 (count (in-m/read-segments db 1 2))))
      (is (= 0 (count (in-m/read-segments db 5 9))))))

  (testing "storage log is populated by run-tx"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])
          {:keys [db]} (in-m/run-tx db [[:db/add 1026 :name "Bob"]])]
      ;; log[0] = bootstrap (t=0), log[1] = t=1, log[2] = t=2
      (is (= 3 (count (:log db))))
      (is (= 0 (first ((:log db) 0))))
      (is (= 1 (first ((:log db) 1))))
      (is (= 2 (first ((:log db) 2))))
      ;; each entry is [t added retracted]
      (is (= 3 (count ((:log db) 1))))))

  (testing "IDaoTransactor/current-db returns the db itself"
    (let [db (in-m/empty-db)]
      (is (= db (in-m/current-db db)))))

  (testing "IDaoTransactor/transact! applies tx-data"
    (let [db (in-m/empty-db)
          {:keys [db-after]} (in-m/transact! db [[:db/add 1025 :name "Alice"]])]
      (is (= "Alice" (:v (first (in-m/native-datoms db-after :eavt [1025 :name])))))))

  (testing "IDaoQueryEngine/run-q delegates to query engine"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (= #{["Alice"]}
             (in-m/run-q db '[:find ?n :where [_ :name ?n]] [db])))))

  (testing "IDaoQueryEngine/datoms returns index contents"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (seq (in-m/datoms db :eavt nil)))))

  (testing "IDaoQueryEngine/entity-attrs returns attr map"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (= {:name "Alice"} (in-m/entity-attrs db 1025)))))

  (testing "IDaoQueryEngine/find-eids-by-av returns entity set"
    (let [db (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :name "Alice"]
                                        [:db/add 1026 :name "Bob"]])]
      (is (= #{1025} (in-m/find-eids-by-av db :name "Alice"))))))


;; =============================================================================
;; Metadata Resolution
;; =============================================================================

(deftest metadata-tempid-resolution-bug
  (testing "Metadata map referencing a tempid from the SAME transaction"
    ;; :actor must be schema-declared as :db.type/ref so the tempid rewrite applies.
    ;; Entity 2048 is used for the action to avoid colliding with the schema entity at 1025.
    (let [db (in-m/create {:actor {:db/valueType :db.type/ref}})
          tx-data [[:db/add -1 :name "Alice"]
                   [:db/add 2048 :action "create" {:actor -1}]]
          {:keys [db tempids]} (in-m/run-tx db tx-data)
          alice-eid (get tempids -1)
          action-datom (first (filter #(= :action (:a %)) (in-m/native-datoms db :eavt [2048])))
          meta-eid (:m action-datom)
          meta-attrs (in-m/native-entity-attrs db meta-eid)]
      (is (some? alice-eid) "Alice should have a permanent EID")
      (is (= alice-eid (:actor meta-attrs))
          "Metadata attribute :actor should resolve to Alice's permanent EID, not -1"))))


(deftest metadata-only-tempid-resolution-bug
  (testing "Metadata map referencing a tempid that only appears in metadata"
    (let [db (in-m/create {:actor {:db/valueType :db.type/ref}})
          ;; The tempid -1 appears ONLY inside the metadata map.
          ;; The main tx-data op uses 2048 (positive EID).
          tx-data [[:db/add 2048 :action "create" {:actor -1}]]
          {:keys [db tempids]} (in-m/run-tx db tx-data)
          action-datom (first (filter #(= :action (:a %)) (in-m/native-datoms db :eavt [2048])))
          meta-eid (:m action-datom)
          meta-attrs (in-m/native-entity-attrs db meta-eid)
          actor-val (:actor meta-attrs)]
      (is (some? actor-val) "Metadata attribute :actor should exist")
      ;; This is expected to FAIL because -1 is not collected as a tempid
      ;; if it doesn't appear in the main ops :e or ref-typed :v positions.
      (is (pos? actor-val) (str "The tempid -1 should be resolved to a positive ID, but got: " actor-val)))))


(deftest metadata-ref-schema-same-tx-bug
  (testing "Metadata ref attrs declared in the same tx should resolve tempids"
    (let [db (in-m/empty-db)
          tx-data [[:db/add -1 :db/ident :actor]
                   [:db/add -1 :db/valueType :db.type/ref]
                   [:db/add -2 :name "Alice"]
                   [:db/add 2048 :action "create" {:actor -2}]]
          {:keys [db tempids]} (in-m/run-tx db tx-data)
          alice-eid (get tempids -2)
          action-datom (first (filter #(= :action (:a %))
                                      (in-m/native-datoms db :eavt [2048])))
          meta-eid (:m action-datom)
          meta-attrs (in-m/native-entity-attrs db meta-eid)]
      (is (some? alice-eid) "Alice should have a permanent EID")
      (is (= alice-eid (:actor meta-attrs))
          "Metadata tempids should resolve when the ref attribute is introduced in the same tx"))))


(deftest metadata-negative-scalar-bug
  (testing "Metadata scalar negative integers should stay scalar values"
    (let [db (in-m/empty-db)
          tx-data [[:db/add -1 :name "Alice"]
                   [:db/add 2048 :action "create" {:error/code -1}]]
          {:keys [db]} (in-m/run-tx db tx-data)
          action-datom (first (filter #(= :action (:a %))
                                      (in-m/native-datoms db :eavt [2048])))
          meta-eid (:m action-datom)
          meta-attrs (in-m/native-entity-attrs db meta-eid)]
      (is (= -1 (:error/code meta-attrs))
          "Negative scalar metadata values should not be rewritten through tempid resolution"))))


(deftest metadata-keyword-ident-value-resolution-bug
  (testing "Metadata ref attrs should resolve keyword idents introduced in the same tx"
    (let [db (in-m/create {:actor {:db/valueType :db.type/ref}})
          tx-data [[:db/add -2 :db/ident :bob]
                   [:db/add 2048 :action "create" {:actor :bob}]]
          {:keys [db tempids]} (in-m/run-tx db tx-data)
          bob-eid (get tempids -2)
          action-datom (first (filter #(= :action (:a %))
                                      (in-m/native-datoms db :eavt [2048])))
          meta-eid (:m action-datom)
          meta-attrs (in-m/native-entity-attrs db meta-eid)]
      (is (pos? bob-eid) "Bob should have a positive permanent EID")
      (is (= bob-eid (:actor meta-attrs))
          "Metadata keyword ref values should resolve through same-tx :db/ident lookup"))))


;; =============================================================================
;; Uniqueness Constraints
;; =============================================================================

(deftest uniqueness-constraint-bug
  (testing ":db/unique :db.unique/value enforcement"
    (let [db (in-m/create {:email {:db/unique :db.unique/value}})
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :email "a@b.com"]])]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"Unique constraint violated"
            (in-m/run-tx db [[:db/add 1026 :email "a@b.com"]]))
          "Should throw when asserting a duplicate value for a unique attribute"))))


(deftest uniqueness-schema-change-same-tx-bug
  (testing "Declaring uniqueness and asserting duplicates in the same tx should fail"
    (let [db (in-m/empty-db)
          tx-data [[:db/add -1 :db/ident :email]
                   [:db/add -1 :db/unique :db.unique/value]
                   [:db/add 1025 :email "a@b.com"]
                   [:db/add 1026 :email "a@b.com"]]]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"Unique constraint violated"
            (in-m/run-tx db tx-data))
          "Same-tx schema installation should affect uniqueness checks immediately")))

  (testing "Removing uniqueness and asserting duplicates in the same tx should succeed"
    (let [db (in-m/create {:email {:db/unique :db.unique/value}})
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :email "a@b.com"]])
          outcome (try
                    {:result (in-m/run-tx db [[:db/retract :email :db/unique :db.unique/value]
                                              [:db/add 1026 :email "a@b.com"]])}
                    (catch #?(:clj Exception :cljs js/Error) e
                      {:error e}))]
      (is (map? (:result outcome))
          "Same-tx uniqueness retraction should allow the duplicate assertion"))))


(deftest uniqueness-value-transfer-same-tx-bug
  (testing "Retracting a unique value from one entity and moving it in the same tx should succeed"
    (let [db (in-m/create {:email {:db/unique :db.unique/value}})
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :email "a@b.com"]])
          outcome (try
                    {:result (in-m/run-tx db [[:db/retract 1025 :email "a@b.com"]
                                              [:db/add 1026 :email "a@b.com"]])}
                    (catch #?(:clj Exception :cljs js/Error) e
                      {:error e}))]
      (is (map? (:result outcome))
          "Unique values should be transferable within one tx when the old assertion is retracted")
      (when-let [db-after (:db-after (:result outcome))]
        (is (= "a@b.com" (:email (in-m/native-entity-attrs db-after 1026)))
            "The transferred unique value should belong to the new entity after the tx")))))


;; =============================================================================
;; Same-Tx Schema Effects
;; =============================================================================

(deftest same-tx-ref-schema-for-ordinary-datom-bug
  (testing "Same-tx :db.type/ref installation should resolve ordinary ref tempids and populate VAET"
    (let [db (in-m/empty-db)
          tx-data [[:db/add -1 :db/ident :friend]
                   [:db/add -1 :db/valueType :db.type/ref]
                   [:db/add -2 :name "Alice"]
                   [:db/add 2048 :friend -2]]
          {:keys [db tempids]} (in-m/run-tx db tx-data)
          alice-eid (get tempids -2)]
      (is (some? alice-eid) "Alice should get a permanent EID")
      (is (= alice-eid (:friend (in-m/native-entity-attrs db 2048)))
          "Ordinary ref datoms should resolve tempid values using same-tx ref schema")
      (is (seq (in-m/native-datoms db :vaet [alice-eid :friend]))
          "VAET should be populated for a ref attr installed in the same tx"))))


(deftest same-tx-indexed-attr-bug
  (testing "Same-tx :db/index installation should populate AVET for ordinary datoms"
    (let [db (in-m/empty-db)
          tx-data [[:db/add -1 :db/ident :email]
                   [:db/add -1 :db/index true]
                   [:db/add 2048 :email "a@b.com"]]
          {:keys [db]} (in-m/run-tx db tx-data)]
      (is (contains? (:indexed-attrs db) :email)
          "Schema cache should mark :email as indexed after the tx")
      (is (= #{2048} (in-m/native-find-eids-by-av db :email "a@b.com"))
          "Indexed lookup should find the entity immediately after same-tx schema installation")
      (is (seq (in-m/native-datoms db :avet [:email "a@b.com"]))
          "AVET should contain the asserted datom when :db/index is installed in the same tx"))))


(deftest same-tx-card-many-bug
  (testing "Same-tx :db.cardinality/many installation should preserve multiple asserted values"
    (let [db (in-m/empty-db)
          tx-data [[:db/add -1 :db/ident :tags]
                   [:db/add -1 :db/cardinality :db.cardinality/many]
                   [:db/add 2048 :tags "a"]
                   [:db/add 2048 :tags "b"]]
          {:keys [db]} (in-m/run-tx db tx-data)
          attrs (in-m/native-entity-attrs db 2048)
          tag-datoms (in-m/native-datoms db :eavt [2048 :tags])]
      (is (contains? (:card-many db) :tags)
          "Schema cache should mark :tags as cardinality-many after the tx")
      (is (= #{"a" "b"} (set (:tags attrs)))
          "Both asserted values should survive when cardinality-many is installed in the same tx")
      (is (= 2 (count tag-datoms))
          "EAVT should keep both datoms for a same-tx card-many installation"))))


(deftest stale-secondary-index-bug
  (testing "Implicit cardinality/one retractions must be removed from AVET/VAET/MEAT"
    (let [db (in-m/create {:ssn    {:db/index true}
                           :friend {:db/valueType :db.type/ref}})
          ;; 1. Assert initial values
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :ssn "111"]
                                        [:db/add 1025 :friend 1026 42]]) ; m=42
          ;; 2. Overwrite values (implicit retraction of "111", 1026, and m=42)
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :ssn "222"]
                                        [:db/add 1025 :friend 1027 99]])] ; m=99

      (testing "AVET cleanup"
        (is (= 1 (count (in-m/native-datoms db :avet [:ssn])))
            "AVET should only have the new SSN '222'")
        (is (empty? (in-m/native-datoms db :avet [:ssn "111"]))
            "AVET should NOT contain the old SSN '111'"))

      (testing "VAET cleanup"
        (is (= 1 (count (in-m/native-datoms db :vaet)))
            "VAET should only have one entry")
        (is (empty? (in-m/native-datoms db :vaet [1026]))
            "VAET should NOT contain the old reverse-ref from 1026"))

      (testing "MEAT cleanup"
        (is (= 1 (count (in-m/native-datoms db :meat)))
            "MEAT should only have one entry (m=99)")
        (is (empty? (in-m/native-datoms db :meat [42]))
            "MEAT should NOT contain the old metadata m=42")))))


(deftest same-tx-keyword-ident-value-resolution-bug
  (testing "Defining a ref attr and target ident in the same tx should resolve keyword values"
    (let [db (in-m/empty-db)
          tx-data [[:db/add -1 :db/ident :friend]
                   [:db/add -1 :db/valueType :db.type/ref]
                   [:db/add -2 :db/ident :bob]
                   [:db/add 1025 :friend :bob]]
          {:keys [db tempids]} (in-m/run-tx db tx-data)
          bob-eid (get tempids -2)
          friend-datom (first (in-m/native-datoms db :eavt [1025 :friend]))]
      (is (pos? bob-eid) "Bob should have a positive permanent EID")
      (is (= bob-eid (:v friend-datom))
          "The keyword :bob should resolve through same-tx :db/ident lookup to Bob's permanent EID")
      (is (seq (in-m/native-datoms db :vaet [bob-eid :friend]))
          "VAET should be populated for the resolved ref datom"))))


(deftest same-tx-ref-schema-retraction-bug
  (testing "Retracting :db.type/ref in the same tx should stop ref resolution immediately"
    (let [db (in-m/create {:friend {:db/valueType :db.type/ref}})
          {:keys [db]} (in-m/run-tx db [[:db/add 1026 :db/ident :bob]])
          {:keys [db]} (in-m/run-tx db [[:db/retract :friend :db/valueType :db.type/ref]
                                        [:db/add 1025 :friend :bob]])
          friend-datom (first (in-m/native-datoms db :eavt [1025 :friend]))
          attrs (in-m/native-entity-attrs db 1025)]
      (is (= :bob (:v friend-datom))
          "Once :db.type/ref is retracted in the tx, keyword values should remain scalar keywords")
      (is (= :bob (:friend attrs))
          "Entity attrs should expose the scalar keyword value after same-tx ref-schema retraction")
      (is (empty? (in-m/native-datoms db :vaet))
          "VAET should be entirely empty when the attribute is no longer ref-typed"))))


;; =============================================================================
;; Misc Regression
;; =============================================================================

(deftest type-rank-nil-comparison-bug
  (testing "compare-vals with nil"
    (is (zero? (dw/compare-vals nil nil)) "nil vs nil should be 0")
    (is (neg? (dw/compare-vals nil "a"))  "nil should be less than string")
    (is (pos? (dw/compare-vals "a" nil))  "string should be greater than nil")))


(deftest as-of-schema-flip-bug
  (testing "Historical state after attribute changes from :many to :one"
    ;; create runs a schema tx at t=1.
    ;; t=2: Alice gets two :tags values (card-many at this point)
    ;; t=3: :tags schema is overwritten to :one via ident lookup
    ;; as-of t=2: Alice should still have ["a" "b"] as a vector (historical :many)
    (let [db (in-m/create {:tags {:db/cardinality :db.cardinality/many}})
          {:keys [db]} (in-m/run-tx db [[:db/add 1026 :tags "a"]
                                        [:db/add 1026 :tags "b"]]) ; t=2
          ;; Explicitly change schema at t=3 using ident-based entity reference
          {:keys [db]} (in-m/run-tx db [[:db/add :tags :db/cardinality :db.cardinality/one]]) ; t=3
          snap (in-m/as-of db 2)
          attrs (in-m/native-entity-attrs snap 1026)]
      (is (vector? (:tags attrs)) "Historical attributes should respect historical cardinality")
      (is (= #{"a" "b"} (set (:tags attrs))) "Should return both values from t=2"))))


(deftest keyword-ident-value-resolution-bug
  (testing "Using a keyword ident in the value position of a ref attribute"
    (let [db (in-m/create {:friend {:db/valueType :db.type/ref}})
          ;; 1. Define Bob
          {:keys [db]} (in-m/run-tx db [[:db/add 1026 :db/ident :bob]])
          ;; 2. Link to Bob via keyword
          {:keys [db]} (in-m/run-tx db [[:db/add 1025 :friend :bob]])
          friend-datom (first (in-m/native-datoms db :eavt [1025 :friend]))]
      (is (= 1026 (:v friend-datom))
          "The keyword :bob should resolve to entity ID 1026 in the value position"))))


(deftest explicit-positive-eid-run-tx-allocation-bug
  (testing "run-tx should advance next-eid past explicit positive user entity ids"
    (let [db0 (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db0 [[:db/add 1025 :name "Alice"]])]
      (is (= 1026 (:next-eid db))
          "After asserting entity 1025 explicitly, the next allocated eid should be 1026"))

    (let [db0 (in-m/empty-db)
          {:keys [db]} (in-m/run-tx db0 [[:db/add 1025 :name "Alice"]])
          {:keys [db tempids]} (in-m/run-tx db [[:db/add -1 :name "Bob"]])
          bob-eid (get tempids -1)]
      (is (= 1026 bob-eid)
          "A later tempid should not reuse the explicitly asserted eid 1025")
      (is (= "Alice" (:name (in-m/native-entity-attrs db 1025)))
          "The original entity at 1025 should remain intact")
      (is (= "Bob" (:name (in-m/native-entity-attrs db bob-eid)))
          "The newly allocated entity should receive the next fresh eid"))))
