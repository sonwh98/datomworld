(ns dao.db.in-memory-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.db.in-memory :as native]
    [yin.vm :as vm]
    [yin.vm.semantic :as semantic]))


;; =============================================================================
;; Helpers
;; =============================================================================

(defn- get-attr
  "Get a single attribute value for eid from db."
  [db eid attr]
  (:v (first (filter #(= attr (:a %))
                     (filter #(= eid (:e %)) (native/native-datoms db :eavt))))))


;; =============================================================================
;; Tx Pipeline
;; =============================================================================

(deftest tx-pipeline-test

  (testing "4-tuple form [:db/add e a v] — m defaults to 0"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (= "Alice" (get-attr db 1025 :name)))
      (is (= 0 (:m (first (filter #(= :name (:a %)) (native/native-datoms db :eavt))))))))

  (testing "5-tuple form [:db/add e a v m]"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Bob" 42]])]
      (is (= 42 (:m (first (filter #(= :name (:a %)) (native/native-datoms db :eavt))))))))

  (testing "map tx-data form with :db/id"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [{:db/id 1025 :name "Carol"}])]
      (is (= "Carol" (get-attr db 1025 :name)))))

  (testing "map tx-data form without :db/id — auto tempid"
    (let [db (native/empty-db)
          {:keys [db tempids]} (native/run-tx db [{:name "Dave"}])]
      (is (= 1 (count tempids)))
      (let [eid (first (vals tempids))]
        (is (= "Dave" (get-attr db eid :name))))))

  (testing "map-m expansion — map :m creates metadata entity"
    (let [db        (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Eve" {:source "test"}]])
          name-datom (first (filter #(= :name (:a %)) (native/native-datoms db :eavt)))
          meta-eid  (:m name-datom)]
      (is (pos? meta-eid))
      (is (= "test" (get-attr db meta-eid :source)))))

  (testing "tempid resolution — negative eids resolve to positive"
    (let [db (native/empty-db)
          {:keys [db tempids]} (native/run-tx db [[:db/add -1 :name "Fred"]
                                                  [:db/add -2 :name "Gina"]])]
      (is (= 2 (count tempids)))
      (is (every? pos? (vals tempids)))
      (is (= "Fred" (get-attr db (get tempids -1) :name)))
      (is (= "Gina" (get-attr db (get tempids -2) :name)))))

  (testing "cardinality-one: second :db/add replaces first"
    (let [db     (native/create {:color {:db/cardinality :db.cardinality/one}})
          {:keys [db]} (native/run-tx db [[:db/add 1025 :color :red]])
          {:keys [db]} (native/run-tx db [[:db/add 1025 :color :blue]])
          colors (filter #(= :color (:a %)) (native/native-datoms db :eavt))]
      (is (= 1 (count colors)))
      (is (= :blue (:v (first colors))))))

  (testing "cardinality-many: multiple values accumulate"
    (let [db (native/create {:tags {:db/cardinality :db.cardinality/many}})
          {:keys [db]} (native/run-tx db [[:db/add 1025 :tags "a"]])
          {:keys [db]} (native/run-tx db [[:db/add 1025 :tags "b"]])
          tags (map :v (filter #(= :tags (:a %)) (native/native-datoms db :eavt)))]
      (is (= #{"a" "b"} (set tags)))))

  (testing ":db/retract removes the specific [e a v] from all indexes"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Holly"]])
          {:keys [db]} (native/run-tx db [[:db/retract 1025 :name "Holly"]])]
      (is (empty? (filter #(= :name (:a %)) (native/native-datoms db :eavt))))
      (is (empty? (filter #(= :name (:a %)) (native/native-datoms db :aevt)))))))


;; =============================================================================
;; Query Engine
;; =============================================================================

(deftest query-engine-test

  (testing "single variable — find all eids with a given attr"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]
                                          [:db/add 1026 :name "Bob"]])]
      (is (= #{[1025] [1026]}
             (native/q '[:find ?e :where [?e :name _]] db)))))

  (testing "two-variable join — attribute and value bound"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]
                                          [:db/add 1026 :name "Bob"]])]
      (is (= #{["Alice"] ["Bob"]}
             (native/q '[:find ?v :where [_ :name ?v]] db)))))

  (testing "multi-clause join — entity with two matching attrs"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]
                                          [:db/add 1025 :age 30]
                                          [:db/add 1026 :name "Bob"]])]
      (is (= #{[1025]}
             (native/q '[:find ?e :where [?e :name _] [?e :age _]] db)))))

  (testing ":in $ ?x — scalar input binding"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]
                                          [:db/add 1026 :name "Bob"]])]
      (is (= #{[1025]}
             (native/q '[:find ?e :in $ ?n :where [?e :name ?n]] db "Alice")))))

  (testing "empty result — no matching datoms"
    (let [db (native/empty-db)]
      (is (= #{} (native/q '[:find ?e :where [?e :name "Nobody"]] db))))))


;; =============================================================================
;; Record and Schema
;; =============================================================================

(deftest record-test

  (testing "empty-db has bootstrap schema entities (eids 2-13)"
    (let [db (native/empty-db)
          ident-datoms (filter #(= :db/ident (:a %)) (native/native-datoms db :eavt))]
      (is (= 12 (count ident-datoms)))
      (is (some #(= :db/ident (:v %)) ident-datoms))
      (is (some #(= :db.type/ref (:v %)) ident-datoms))))

  (testing "create with yin.vm/schema installs ref and card-many attrs"
    (let [db (native/create vm/schema)]
      (is (contains? (:ref-attrs db) :yin/operator))
      (is (contains? (:ref-attrs db) :yin/body))
      (is (contains? (:card-many db) :yin/operands))))

  (testing "entity-attrs returns map of attr->val"
    (let [db    (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]
                                          [:db/add 1025 :age 30]])
          attrs (native/native-entity-attrs db 1025)]
      (is (= "Alice" (:name attrs)))
      (is (= 30 (:age attrs)))))

  (testing "entity-attrs accumulates card-many values as vectors"
    (let [db    (native/create {:tags {:db/cardinality :db.cardinality/many}})
          {:keys [db]} (native/run-tx db [[:db/add 1025 :tags "a"]
                                          [:db/add 1025 :tags "b"]])
          attrs (native/native-entity-attrs db 1025)]
      (is (= #{"a" "b"} (set (:tags attrs))))))

  (testing "find-eids-by-av scans AEVT when attr not in indexed-attrs"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]
                                          [:db/add 1026 :name "Bob"]])]
      (is (= #{1025} (native/native-find-eids-by-av db :name "Alice")))))

  (testing "datoms :eavt sorted by e then a"
    (let [db         (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1026 :age 30]
                                          [:db/add 1025 :name "Alice"]])
          user-datoms (filter #(> (:e %) 1024) (native/native-datoms db :eavt))
          eids        (map :e user-datoms)]
      (is (= eids (sort eids)))))

  (testing "as-of filters datoms by t"
    (let [db  (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "v1"]])  ; t=1
          {:keys [db]} (native/run-tx db [[:db/add 1026 :name "v2"]])  ; t=2
          db-at-1 (native/as-of db 1)]
      (is (some #(= 1025 (:e %)) (native/native-datoms db-at-1 :eavt)))
      (is (not (some #(= 1026 (:e %)) (native/native-datoms db-at-1 :eavt))))))

  (testing "since filters datoms strictly after t"
    (let [db  (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "v1"]])  ; t=1
          {:keys [db]} (native/run-tx db [[:db/add 1026 :name "v2"]])  ; t=2
          db-after-1 (native/since db 1)]
      (is (not (some #(= 1025 (:e %)) (native/native-datoms db-after-1 :eavt))))
      (is (some #(= 1026 (:e %)) (native/native-datoms db-after-1 :eavt))))))


;; =============================================================================
;; Integration: yin.vm/schema + AST + SemanticVM
;; =============================================================================

(deftest integration-test

  (testing "load yin.vm/schema, transact (+ 1 2) AST, run SemanticVM"
    (let [;; Create db with yin.vm schema
          db (native/create vm/schema)
          ;; Build AST datoms for (+ 1 2)
          ast {:type     :application
               :operator {:type :variable :name '+}
               :operands [{:type :literal :value 1}
                          {:type :literal :value 2}]}
          [root-tempid ast-raw-datoms] (vm/ast->datoms-with-root ast)
          ;; Convert 5-elem datom vectors to [:db/add e a v] tx-data
          tx-data (vm/datoms->tx-data ast-raw-datoms)
          {:keys [db tempids]} (native/run-tx db tx-data)
          actual-root (get tempids root-tempid)
          ;; Extract all datoms as 5-elem vectors for SemanticVM
          all-datoms (mapv (fn [d] [(:e d) (:a d) (:v d) (:t d) (:m d)])
                           (native/native-datoms db :eavt))
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
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]
                                          [:db/add 1026 :name "Bob"]
                                          [:db/add 1027 :name "Carol"]])]
      (is (= #{["Alice"] ["Bob"]}
             (native/q '[:find ?name
                         :in $ [?name ...]
                         :where [_ :name ?name]]
                       db ["Alice" "Bob"])))))

  (testing "empty collection binding returns #{}"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (= #{}
             (native/q '[:find ?name
                         :in $ [?name ...]
                         :where [_ :name ?name]]
                       db [])))))

  (testing "tuple binding [?name ?age] — both vars bound"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]
                                          [:db/add 1025 :age 30]
                                          [:db/add 1026 :name "Bob"]
                                          [:db/add 1026 :age 25]])]
      (is (= #{[1025]}
             (native/q '[:find ?e
                         :in $ [?name ?age]
                         :where [?e :name ?name]
                         [?e :age ?age]]
                       db ["Alice" 30])))))

  (testing "relation binding [[?name ?age] ...] — multi-row expansion"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]
                                          [:db/add 1025 :age 30]
                                          [:db/add 1026 :name "Bob"]
                                          [:db/add 1026 :age 25]
                                          [:db/add 1027 :name "Carol"]
                                          [:db/add 1027 :age 40]])]
      (is (= #{["Alice" 30] ["Bob" 25]}
             (native/q '[:find ?name ?age
                         :in $ [[?name ?age] ...]
                         :where [?e :name ?name]
                         [?e :age ?age]]
                       db [["Alice" 30] ["Bob" 25]])))))

  (testing "multi-database cross-join on shared attribute"
    (let [local  (native/empty-db)
          remote (native/empty-db)
          {:keys [db]} (native/run-tx local  [[:db/add 1025 :email "a@x.com"]
                                              [:db/add 1025 :role  "admin"]])
          local  db
          {:keys [db]} (native/run-tx remote [[:db/add 2001 :email "a@x.com"]
                                              [:db/add 2001 :score 99]])
          remote db]
      (is (= #{["admin" 99]}
             (native/q '[:find ?role ?score
                         :in $local $remote
                         :where [$local  ?e1 :email ?email]
                         [$local  ?e1 :role  ?role]
                         [$remote ?e2 :email ?email]
                         [$remote ?e2 :score ?score]]
                       local remote)))))

  (testing "scalar + collection cross-join — both constraints apply"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]
                                          [:db/add 1025 :role "admin"]
                                          [:db/add 1026 :name "Bob"]
                                          [:db/add 1026 :role "user"]
                                          [:db/add 1027 :name "Carol"]
                                          [:db/add 1027 :role "admin"]])]
      (is (= #{["Alice"] ["Carol"]}
             (native/q '[:find ?name
                         :in $ ?role [?name ...]
                         :where [?e :name ?name]
                         [?e :role ?role]]
                       db "admin" ["Alice" "Carol" "Bob"]))))))


;; =============================================================================
;; MEAT index
;; =============================================================================

(deftest meat-index-test

  (testing "datom with m=0 does NOT appear in MEAT"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (empty? (native/native-datoms db :meat)))))

  (testing "datom with m!=0 appears in MEAT"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice" 42]])]
      (is (= 1 (count (native/native-datoms db :meat))))
      (is (= 42 (:m (first (native/native-datoms db :meat)))))))

  (testing "seek-m — filter MEAT by m value"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice" 42]
                                          [:db/add 1026 :name "Bob"   99]
                                          [:db/add 1027 :name "Carol" 42]])]
      (let [results (native/native-datoms db :meat [42])]
        (is (= 2 (count results)))
        (is (every? #(= 42 (:m %)) results)))))

  (testing "seek-me — filter MEAT by m and e"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice" 42]
                                          [:db/add 1025 :age  30     42]
                                          [:db/add 1026 :name "Bob"   42]])]
      (let [results (native/native-datoms db :meat [42 1025])]
        (is (= 2 (count results)))
        (is (every? #(and (= 42 (:m %)) (= 1025 (:e %))) results)))))

  (testing "as-of filters MEAT by t"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "v1" 10]])
          {:keys [db]} (native/run-tx db [[:db/add 1026 :name "v2" 10]])
          db-at-1 (native/as-of db 1)]
      (is (some #(= 1025 (:e %)) (native/native-datoms db-at-1 :meat)))
      (is (not (some #(= 1026 (:e %)) (native/native-datoms db-at-1 :meat))))))

  (testing "retract removes datom from MEAT"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice" 42]])
          {:keys [db]} (native/run-tx db [[:db/retract 1025 :name "Alice"]])]
      (is (empty? (native/native-datoms db :meat))))))


;; =============================================================================
;; Three-Component Protocols
;; =============================================================================

(deftest three-protocol-test

  (testing "IDaoStorage/latest-t returns basis-t"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (= 1 (native/latest-t db)))))

  (testing "IDaoStorage/read-segments returns datoms grouped by t"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]])
          {:keys [db]} (native/run-tx db [[:db/add 1026 :name "Bob"]])
          segs (native/read-segments db 1 2)]
      (is (= 2 (count segs)))
      (is (every? seq segs))))

  (testing "IDaoStorage/read-segments returns only txns in range"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]])  ; t=1
          {:keys [db]} (native/run-tx db [[:db/add 1026 :name "Bob"]])    ; t=2
          {:keys [db]} (native/run-tx db [[:db/add 1027 :name "Carol"]])] ; t=3
      (is (= 1 (count (native/read-segments db 2 2))))
      (is (= 2 (count (native/read-segments db 1 2))))
      (is (= 0 (count (native/read-segments db 5 9))))))

  (testing "storage log is populated by run-tx"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]])
          {:keys [db]} (native/run-tx db [[:db/add 1026 :name "Bob"]])]
      ;; log[0] = bootstrap (t=0), log[1] = t=1, log[2] = t=2
      (is (= 3 (count (:log db))))
      (is (= 0 (first ((:log db) 0))))
      (is (= 1 (first ((:log db) 1))))
      (is (= 2 (first ((:log db) 2))))))

  (testing "IDaoTransactor/current-db returns the db itself"
    (let [db (native/empty-db)]
      (is (= db (native/current-db db)))))

  (testing "IDaoTransactor/transact! applies tx-data"
    (let [db (native/empty-db)
          {:keys [db-after]} (native/transact! db [[:db/add 1025 :name "Alice"]])]
      (is (= "Alice" (:v (first (native/native-datoms db-after :eavt [1025 :name])))))))

  (testing "IDaoQueryEngine/run-q delegates to query engine"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (= #{["Alice"]}
             (native/run-q db '[:find ?n :where [_ :name ?n]] [db])))))

  (testing "IDaoQueryEngine/datoms returns index contents"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (seq (native/datoms db :eavt nil)))))

  (testing "IDaoQueryEngine/entity-attrs returns attr map"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]])]
      (is (= {:name "Alice"} (native/entity-attrs db 1025)))))

  (testing "IDaoQueryEngine/find-eids-by-av returns entity set"
    (let [db (native/empty-db)
          {:keys [db]} (native/run-tx db [[:db/add 1025 :name "Alice"]
                                          [:db/add 1026 :name "Bob"]])]
      (is (= #{1025} (native/find-eids-by-av db :name "Alice"))))))
