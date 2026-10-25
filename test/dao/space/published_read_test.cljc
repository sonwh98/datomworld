(ns dao.space.published-read-test
  "The checked-read descriptor on published values (Phase 4b-2): both
   constructors carry the local, nonpersistent
   :dao.space.query/published-read {:version 1 :store :manifest-address
   :manifest}, built from the manifest the constructor ALREADY read (no
   extra read), preserved by open-published!'s reconstruction, kept out of
   results and error data, and optional for unbounded use. Reads are
   counted by an explicit wrapper store (no with-redefs), so the counts are
   the same on every host. Bounded calls over a published value are
   admitted only for exactly a `current` view (no as-of) directly over a
   value carrying a structurally valid descriptor (4b-3); every other
   published shape, and every descriptor-less value, is still refused."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.test :refer [deftest is testing]]
            [dao.data.btree :as bt]
            [dao.data.btree.storage :as bts]
            [dao.jing :as jing]
            [dao.jing.file :as jing-file]
            [dao.jing.mem :as mem]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.space.query.datomic :as dq]))


(def ^:private datoms
  (mapv (fn [i] [i :a (* 10 i) 0 1]) (range 30)))


(defn- publish!
  "Hand-publish datoms' four covered indexes and manifest into `store`
   (default: a fresh in-memory one), bf 4:
   {:store :manifest-address :manifest}."
  ([] (publish! (mem/create-content-mem)))
  ([store]
   (let [storage (bts/kv-storage store {:branching-factor 4})
         tree (fn [cmp]
                (bt/store-tree (bt/from-sequential cmp datoms {:branching-factor 4})
                               storage))
         manifest {:indexes {:eavt (tree index/eavt-cmp)
                             :aevt (tree index/aevt-cmp)
                             :avet (tree index/avet-cmp)
                             :vaet (tree index/vaet-cmp)}
                   :count (count datoms)
                   :branching-factor 4}]
     {:store store
      :manifest manifest
      :manifest-address (jing/materialize! store manifest)})))


(defn- counting
  [store log]
  (assoc store
         :get-bytes-fn
         (fn [address not-found]
           (vswap! log conj address)
           ((:get-bytes-fn store) address not-found))))


(def ^:private descriptor-key :dao.space.query/published-read)


(deftest published-db-carries-the-descriptor-from-the-manifest-it-read
  (let [{:keys [store manifest manifest-address]} (publish!)
        log (volatile! [])
        wrapped (counting store log)
        pub (query/published-db wrapped manifest-address)
        d (get pub descriptor-key)]
    (testing "shape"
      (is (= #{:version :store :manifest-address :manifest} (set (keys d))))
      (is (= 1 (:version d)))
      (is (= manifest-address (:manifest-address d)))
      (is (= manifest (:manifest d)))
      (is (identical? wrapped (:store d)) "the borrowed capability itself"))
    (testing "no extra manifest read, and no node read, at construction"
      (is (= [manifest-address] @log) "exactly the one manifest get"))
    (testing "the serializable coordinate and the persisted manifest are unchanged"
      (is (= {:manifest-address manifest-address}
             (:dao.space.query/published pub)))
      (is (= manifest (index/read-manifest store manifest-address))))
    (testing "the legacy fields are still there"
      (is (contains? pub :indexes))
      (is (delay? (:rows pub))))
    (testing "the deferred rows were not forced"
      (is (not (realized? (:rows pub)))))))


(deftest the-descriptor-is-not-part-of-the-coordinate
  (let [{:keys [store manifest-address]} (publish!)
        pub (query/published-db store manifest-address)
        coordinate (index/published-index {:dao.jing/type :dao.jing/mem} manifest-address)]
    (is (not (contains? (:dao.space.query/published pub) :store)))
    (is (not (contains? coordinate descriptor-key)))))


(deftest descriptor-and-results-and-errors
  (let [{:keys [store manifest-address]} (publish!)
        pub (query/published-db store manifest-address)
        view (query/current pub)
        mentions-descriptor? (fn [x]
                               (some #(= descriptor-key %) (tree-seq coll? seq x)))]
    (testing "results never carry it"
      (let [r (query/collect (query/q '[:find ?e ?v :where [?e :a ?v] [(> ?v 270)]] view))]
        (is (= #{[28 280] [29 290]} r))
        (is (not (mentions-descriptor? r)))))
    (testing "a core refusal over a published source leaks no descriptor"
      (let [e (try (query/q '[:find ?e :where [?e :a ?v] [(no-such-fn ?v)]] view)
                   nil
                   (catch #?(:cljd Object :clj Throwable :cljs :default) e e))]
        (is (some? e))
        (is (not (mentions-descriptor? (ex-data e))))
        (is (not (re-find #"published-read" (str (ex-data e)))))))
    (testing "a bounded call over the exact admitted shape runs, and names no descriptor"
      (let [reads (volatile! [])
            counted (query/published-db (counting store reads) manifest-address)
            _ (vreset! reads [])
            r (dq/q {:query '[:find ?v :where [7 :a ?v]]
                     :args [(query/current counted)]
                     ::dq/max-steps 100000})]
        (is (= [[70]] r))
        (is (pos? (count @reads)) "it read nodes through the checked cursor")
        (is (not (mentions-descriptor? r)))))
    (testing "a descriptor-less published value is still refused, with no read, naming no descriptor"
      (let [reads (volatile! [])
            counted (query/published-db (counting store reads) manifest-address)
            _ (vreset! reads [])
            e (try (dq/q {:query '[:find ?e :where [?e :a _]]
                          :args [(query/current (dissoc counted descriptor-key))]
                          ::dq/max-steps 100000})
                   nil
                   (catch #?(:cljd Object :clj Throwable :cljs :default) e e))
            d (ex-data e)]
        (is (= :unsupported (::dq/error d)))
        (is (= :bounded-source-unqualified (:reason d)))
        (is (not (mentions-descriptor? d)))
        (is (empty? @reads) "refusal reads nothing")))))


(deftest values-without-the-descriptor-still-work-unbounded
  (let [{:keys [store manifest-address]} (publish!)
        pub (query/published-db store manifest-address)
        old (dissoc pub descriptor-key)
        q '[:find ?v :where [7 :a ?v]]]
    (is (not (contains? old descriptor-key)))
    (is (= #{[70]} (query/collect (query/q q (query/current old)))))
    (is (= (query/collect (query/q q (query/current pub)))
           (query/collect (query/q q (query/current old)))))))


(defn- temp-path
  []
  (str "target/test-published-read-" (random-uuid) ".log"))


(defn- cleanup-file
  [path]
  #?(:clj (let [f (java.io.File. path)] (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") path) (catch :default _))
     :cljd (try (let [f (dart-io/File path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch #?(:cljd Object :default Exception) _ nil))))


(deftest open-published-preserves-the-descriptor-through-its-reconstruction
  (let [path (temp-path)
        writer (jing-file/create-content-file path)]
    (try
      (let [{:keys [manifest manifest-address]} (publish! writer)
            coordinate (index/published-index {:dao.jing/type :dao.jing/file, :path path}
                                              manifest-address)
            opened (query/open-published! coordinate)
            d (get opened descriptor-key)]
        (try
          (is (some? d) "the reconstruction kept the field")
          (is (= #{:version :store :manifest-address :manifest} (set (keys d))))
          (is (= 1 (:version d)))
          (is (= manifest-address (:manifest-address d)))
          (is (= manifest (:manifest d)))
          (is (identical? (:store opened) (:store d))
              "the descriptor's capability is the handle this value owns")
          (is (= coordinate (:dao.space.query/published opened))
              "the serializable coordinate is unchanged")
          (is (some? (:close-guard opened)) "ownership fields are unchanged")
          (is (= #{[70]} (query/collect
                           (query/q '[:find ?v :where [7 :a ?v]] (query/current opened)))))
          (finally (query/close-published! opened))))
      (finally
        (jing/close! writer)
        (cleanup-file path)))))
