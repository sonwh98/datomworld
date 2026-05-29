(ns dao.db.file-test
  (:require
    #?@(:cljd [["dart:io" :as dart-io]]
        :cljs []
        :clj [])
    [clojure.test :refer [deftest is testing]]
    [dao.db :as dao-db]
    [dao.db.file :as file-db]))


(defn- temp-file-path
  [prefix]
  #?(:clj (let [d (System/getProperty "java.io.tmpdir")]
            (str d "/" prefix (java.util.UUID/randomUUID) ".edn"))
     :cljs (let [os (js/require "os")
                 path (js/require "path")]
             (.join path (.tmpdir os) (str prefix (random-uuid) ".edn")))
     :cljd (str "/tmp/" prefix (random-uuid) ".edn")))


(defn- cleanup-file
  [path]
  #?(:clj (let [f (java.io.File. path)] (when (.exists f) (.delete f)))
     :cljs (let [fs (js/require "fs")]
             (when (.existsSync fs path) (.unlinkSync fs path)))
     :cljd (let [f (dart-io/File. path)]
             (when (.existsSync f) (.deleteSync f)))))


(defn- attrs
  [db eid]
  (dao-db/entity-attrs db eid))


(deftest create-and-reopen-test
  (testing "create new file with schema"
    (let [path (temp-file-path "dao-ft-cr-")
          db (file-db/create path {:name {}})]
      (is (map? db))
      (is (= 1 (dao-db/basis-t db)))
      (cleanup-file path)))
  (testing "roundtrip via file"
    (let [path (temp-file-path "dao-ft-rt-")
          db1 (file-db/create path {:name {}})
          {:keys [db-after]} (dao-db/transact db1
                                              [[:db/add 2048 :name "Alice"]])
          _ (is (= 2 (dao-db/basis-t db-after)))
          db2 (file-db/create path)]
      (is (= "Alice" (:name (attrs db2 2048))))
      (is (= 2 (dao-db/basis-t db2)))
      (cleanup-file path))))


(deftest persistence-test
  (testing "multiple attrs persist"
    (let [path (temp-file-path "dao-ft-pa-")
          db (file-db/create path
                             {:name {},
                              :age {},
                              :tags {:db/cardinality :db.cardinality/many}})
          {:keys [db-after]} (dao-db/transact db
                                              [[:db/add 2048 :name "Alice"]
                                               [:db/add 2048 :age 30]
                                               [:db/add 2048 :tags "x"]
                                               [:db/add 2048 :tags "y"]])
          reopened (file-db/create path)]
      (is (= "Alice" (:name (attrs reopened 2048))))
      (is (= 30 (:age (attrs reopened 2048))))
      (is (= #{"x" "y"} (set (:tags (attrs reopened 2048)))))
      (cleanup-file path)))
  (testing "retractions persist"
    (let [path (temp-file-path "dao-ft-pr-")
          db (file-db/create path {:name {}})
          {:keys [db-after]} (dao-db/transact db [[:db/add 2048 :name "Alice"]])
          {:keys [db-after]}
          (dao-db/transact db-after [[:db/retract 2048 :name "Alice"]])
          reopened (file-db/create path)]
      (is (empty? (attrs reopened 2048)))
      (cleanup-file path)))
  (testing "cardinality-one overwrite"
    (let [path (temp-file-path "dao-ft-co-")
          db (file-db/create path
                             {:color {:db/cardinality :db.cardinality/one}})
          {:keys [db-after]} (dao-db/transact db [[:db/add 2048 :color :red]])
          {:keys [db-after]} (dao-db/transact db-after
                                              [[:db/add 2048 :color :blue]])
          reopened (file-db/create path)]
      (is (= :blue (:color (attrs reopened 2048))))
      (cleanup-file path))))


(deftest storage-protocol-test
  (testing "write-segment! persists"
    (let [path (temp-file-path "dao-ft-ws-")
          db (file-db/create path {:name {}})
          datom (dao-db/->datom 2048 :name "Alice" 9 0)
          db2 (dao-db/write-segment! db [9 [datom] []])
          reopened (file-db/create path)]
      (is (= "Alice" (:name (attrs db2 2048))))
      (is (= "Alice" (:name (attrs reopened 2048))))
      (cleanup-file path)))
  (testing "read-segments respects t-range"
    (let [path (temp-file-path "dao-ft-rs-")
          db (file-db/create path {:name {}})
          {:keys [db-after]} (dao-db/transact db [[:db/add 2048 :name "A"]])
          {:keys [db-after]} (dao-db/transact db-after
                                              [[:db/add 2049 :name "B"]])
          {:keys [db-after]} (dao-db/transact db-after
                                              [[:db/add 2050 :name "C"]])]
      (is (= 1 (count (dao-db/read-segments db-after 2 2))))
      (is (= 2 (count (dao-db/read-segments db-after 2 3))))
      (is (= 0 (count (dao-db/read-segments db-after 10 20))))
      (cleanup-file path))))


(deftest temporal-queries-test
  (testing "as-of after reopen"
    (let [path (temp-file-path "dao-ft-ao-")
          db (file-db/create path {:name {}, :age {}})
          {:keys [db-after]} (dao-db/transact db
                                              [[:db/add 2048 :name "Alice"]
                                               [:db/add 2048 :age 30]])
          {:keys [db-after]} (dao-db/transact db-after
                                              [[:db/add 2049 :name "Bob"]])
          reopened (file-db/create path)
          snap (dao-db/as-of reopened 1)]
      (is (= 3 (dao-db/basis-t reopened)))
      (is (= 1 (dao-db/basis-t snap)))
      (is (empty? (attrs snap 2048)))
      (cleanup-file path)))
  (testing "since after reopen"
    (let [path (temp-file-path "dao-ft-si-")
          db (file-db/create path {:name {}})
          {:keys [db-after]} (dao-db/transact db [[:db/add 2048 :name "v1"]])
          {:keys [db-after]} (dao-db/transact db-after
                                              [[:db/add 2049 :name "v2"]])
          reopened (file-db/create path)
          snap (dao-db/since reopened 2)]
      (is (= "v2" (:name (attrs snap 2049))))
      (is (empty? (attrs snap 2048)))
      (cleanup-file path))))


(deftest index-reconstruction-test
  (testing "MEAVT reconstructed on reopen"
    (let [path (temp-file-path "dao-ft-mi-")
          db (file-db/create path {:name {}})
          {:keys [db-after]} (dao-db/transact db
                                              [[:db/add 2048 :name "A" 42]
                                               [:db/add 2049 :name "B" 99]])
          reopened (file-db/create path)]
      (is (= 2 (count (dao-db/datoms reopened :meat))))
      (cleanup-file path)))
  (testing "VAET reconstructed on reopen"
    (let [path (temp-file-path "dao-ft-vi-")
          db (file-db/create path {:friend {:db/valueType :db.type/ref}})
          {:keys [db-after]} (dao-db/transact db [[:db/add 2048 :friend 2049]])
          reopened (file-db/create path)]
      (is (= 1 (count (dao-db/datoms reopened :vaet))))
      (cleanup-file path)))
  (testing "AVET reconstructed on reopen"
    (let [path (temp-file-path "dao-ft-ai-")
          db (file-db/create path {:email {:db/index true}})
          {:keys [db-after]} (dao-db/transact db
                                              [[:db/add 2048 :email "a@b.com"]])
          reopened (file-db/create path)]
      (is (= 1 (count (dao-db/datoms reopened :avet :email "a@b.com"))))
      (cleanup-file path))))


(deftest query-on-reopened-test
  (testing "basic query works on reopened db"
    (let [path (temp-file-path "dao-ft-q-")
          db (file-db/create path {:name {}, :age {}})
          {:keys [db-after]} (dao-db/transact db
                                              [[:db/add 2048 :name "Alice"]
                                               [:db/add 2048 :age 30]
                                               [:db/add 2049 :name "Bob"]])
          reopened (file-db/create path)]
      (is (= #{["Alice"] ["Bob"]}
             (dao-db/q '[:find ?n :where [_ :name ?n]] reopened)))
      (is (= #{[2048]}
             (dao-db/q '[:find ?e :where [?e :name "Alice"]] reopened)))
      (cleanup-file path))))


(deftest entity-api-test
  (testing "entity after reopen"
    (let [path (temp-file-path "dao-ft-e-")
          db (file-db/create path {:name {}, :age {}})
          {:keys [db-after]} (dao-db/transact db
                                              [[:db/add 2048 :name "Alice"]
                                               [:db/add 2048 :age 30]])
          reopened (file-db/create path)]
      (is (= 2048 (:db/id (dao-db/entity reopened 2048))))
      (is (= "Alice" (:name (dao-db/entity reopened 2048))))
      (cleanup-file path)))
  (testing "pull after reopen"
    (let [path (temp-file-path "dao-ft-pl-")
          db (file-db/create path {:name {}, :age {}})
          {:keys [db-after]} (dao-db/transact db
                                              [[:db/add 2048 :name "Alice"]
                                               [:db/add 2048 :age 30]])
          reopened (file-db/create path)
          pulled (dao-db/pull reopened '[*] 2048)]
      (is (= 2048 (:db/id pulled)))
      (is (= "Alice" (:name pulled)))
      (is (= 30 (:age pulled)))
      (cleanup-file path))))


(deftest tempids-test
  (testing "tempids resolve and persist"
    (let [path (temp-file-path "dao-ft-ti-")
          db (file-db/create path {:name {}})
          {:keys [db-after tempids]}
          (dao-db/transact db [[:db/add -1 :name "Alice"]])
          alice-id (get tempids -1)
          reopened (file-db/create path)]
      (is (pos? alice-id))
      (is (= "Alice" (:name (attrs db-after alice-id))))
      (is (= "Alice" (:name (attrs reopened alice-id))))
      (cleanup-file path))))


(deftest empty-db-test
  (testing "create without schema gives basis-t 0"
    (let [path (temp-file-path "dao-ft-ed-")
          db (file-db/create path)]
      (is (= 0 (dao-db/basis-t db)))
      (cleanup-file path)))
  (testing "empty log has no segments"
    (let [path (temp-file-path "dao-ft-el-")
          db (file-db/create path)]
      (is (empty? (dao-db/read-segments db 1 1000)))
      (cleanup-file path))))
