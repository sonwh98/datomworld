(ns dao.space.index-test
  "Contract tests for dao.space.index: the transactor-side indexing library
  (docs/design/dao.space.index.md). In dao.space every agent is its own
  transactor and indexes its own datoms — publish-index! builds the covered
  indexes, persists them as immutable content-addressed segments, and
  advances the stream root. dao.space.query is required here only as the
  consumer: parity assertions prove a published index answers q/match
  identically to the wholesale root it replaced."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.file :as file]
            [dao.jing.mem :as mem]
            [dao.space.index :as index]
            [dao.space.query :as query]))


(defn- seed!
  "Write datoms into a dao.jing handle under the wholesale root shape, as
  a stream owner would before publishing indexes."
  [store datoms]
  (index/register-member! store :root/test)
  (jing/cas! store :root/test 0 {:datoms datoms}))


;; publish-index! and restored-indexes run on every platform since the
;; dao.data.btree switchover (docs/design/dao.data.btree.md §6) — the
;; old "psset durability is Clojure-only" restriction that used to guard
;; the publish-side tests here no longer applies. The two exception-type
;; assertions below still branch per host (#?(:cljs js/Error :cljd Object
;; :default Exception)), since that part genuinely differs by platform.

(def ^:private many-datoms
  "Enough datoms to force a multi-node tree at :branching-factor 32."
  (vec (for [i (range 1025 1625)] [i :work/task (str "task-" i) 0 1])))


(defn- recording-store
  "Wrap an IKVStore, recording every get key into the log atom."
  [inner log]
  (reify
    jing/IKVStore
    (put! [_ k v] (jing/put! inner k v))

    (cas! [_ k old-rev v] (jing/cas! inner k old-rev v))

    (get [_ k not-found] (swap! log conj k) (jing/get inner k not-found))

    (delete! [_ k] (jing/delete! inner k))

    (close! [_] (jing/close! inner))))


(deftest publish-index-root-shape-and-parity
  (testing
    "publish advances the root to {:indexes ... :count n} and
          q/match answer identically before and after"
    (let [store (mem/create-kv-mem)
          _ (seed! store many-datoms)
          q-form '[:find ?v :where [1030 :work/task ?v]]
          before-q (query/q q-form store)
          before-m (query/match store [1030 :work/task '_])
          _ (index/publish-index! store :root/test)
          root (jing/get store :root/test nil)]
      (is (= #{:eavt :aevt :avet :vaet} (set (keys (:indexes root)))))
      (is (every? #(= "segment" (namespace %)) (vals (:indexes root)))
          "index roots are content-addressed segment keys")
      (is (= (count many-datoms) (:count root)))
      (is (nil? (:datoms root)) "the wholesale datom vector is gone")
      (is (= before-q (query/q q-form store)))
      (is (= before-m (query/match store [1030 :work/task '_]))))))


(deftest publish-index-round-trips-through-file-store
  (testing
    "segment keys survive the file backend's EDN persistence —
          an indexed root must be readable after reopen from disk"
    (let [path (str "target/index-test-" (random-uuid) ".db")
          store (file/create-kv-file path)]
      (try (index/register-member! store :root/test)
           (index/publish-index! store
                                 [[1 :work/status :todo 0 1]
                                  [2 :work/status :done 0 1]]
                                 {:key :root/test})
           (jing/close! store)
           (let [store2 (file/create-kv-file path)]
             (try (is (= #{[1]}
                         (query/q '[:find ?e :where [?e :work/status :todo]]
                                  store2)))
                  (finally (jing/close! store2))))
           (finally (jing/close! store) ; idempotent; store was likely
                    ;; already closed above
                    #?(:clj (.delete (java.io.File. path))
                       :cljs (.unlinkSync (js/require "fs") path)
                       :cljd nil))))))


(deftest publish-index-republish-is-idempotent
  (testing
    "content addressing: unchanged data yields identical root
          addresses on republish"
    (let [store (mem/create-kv-mem)
          _ (seed! store many-datoms)
          idx1 (index/publish-index! store :root/test)
          idx2 (index/publish-index! store
                                     (index/read-datoms store :root/test)
                                     {:key :root/test})]
      (is (= idx1 idx2)))))


(deftest publish-index-root-has-vaet
  (testing
    "the published root carries a :vaet segment whose node graph
          holds every datom the EAVT graph holds — VAET is a peer of
          the other three covered indexes, not an optional extra"
    (let [store (mem/create-kv-mem)
          _ (seed! store many-datoms)
          _ (index/publish-index! store :root/test)
          root (jing/get store :root/test nil)
          vaet-addr (:vaet (:indexes root))]
      (is (some? vaet-addr) ":vaet root is a content-addressed key")
      (is (= "segment" (namespace vaet-addr)))
      (is (= (set many-datoms) (set (index/walk-index-datoms store vaet-addr)))
          "VAET graph covers the same datoms as the other indexes"))))


(deftest publish-index-lazy-fetch
  (testing
    "a bound-e match over a published index loads only the seek
          path, not the whole tree"
    (let [inner (mem/create-kv-mem)
          gets (atom [])
          store (recording-store inner gets)
          _ (seed! store many-datoms)
          _ (index/publish-index! store
                                  many-datoms
                                  {:key :root/test, :branching-factor 32})
          total-segments (count (filter #(= "segment" (namespace %))
                                        (keys @(:state-atom inner))))
          _ (reset! gets [])
          result (query/match store [1300 :work/task '_])
          segment-gets (count (filter #(= "segment" (namespace %)) @gets))]
      (is (= [[1300 :work/task "task-1300" 0 1]] result))
      (is (> total-segments 15) "the tree really is multi-node")
      (is (< segment-gets 6)
          (str "a point lookup should load only root+path, got " segment-gets
               " of " total-segments)))))


(deftest publish-index-of-nothing-is-readable
  (testing
    "publishing an empty stream yields nil index roots that read
          back as no datoms, not an error"
    (let [store (mem/create-kv-mem)]
      (index/register-member! store :root/test)
      (index/publish-index! store [] {:key :root/test})
      (is (= [] (query/match store ['_ '_ '_])))
      (is (= #{} (query/q '[:find ?e :where [?e _ _]] store))))))


(deftest publish-index-2-arity-guard
  (testing "the re-aritied 2-arity rejects a datom seq on all platforms"
    (let [store (mem/create-kv-mem)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"keyword key"
            (index/publish-index! store [])))))
  (testing "the re-aritied 2-arity rejects a segment key on all platforms"
    (let [store (mem/create-kv-mem)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #":root/<name>"
            (index/publish-index! store :segment/foo)))))
  (testing "the re-aritied 2-arity rejects an empty name key on all platforms"
    (let [store (mem/create-kv-mem)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"non-empty"
            (index/publish-index! store (keyword "root" ""))))))
  (testing "the re-aritied 2-arity rejects the membership root on all platforms"
    (let [store (mem/create-kv-mem)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"membership root"
            (index/publish-index! store :root/members))))))


(deftest publish-index-3-arity-guard
  (testing "the 3-arity rejects a non-root key"
    (let [store (mem/create-kv-mem)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"keyword key"
            (index/publish-index! store [] {:key []})))))
  (testing "the 3-arity rejects a segment key"
    (let [store (mem/create-kv-mem)]
      (is (thrown-with-msg?
            #?(:cljs js/Error
               :cljd Object
               :default Exception)
            #":root/<name>"
            (index/publish-index! store [] {:key :segment/foo})))))
  (testing "the 3-arity rejects an empty name key"
    (let [store (mem/create-kv-mem)]
      (is (thrown-with-msg?
            #?(:cljs js/Error
               :cljd Object
               :default Exception)
            #"non-empty"
            (index/publish-index! store [] {:key (keyword "root" "")})))))
  (testing "the 3-arity rejects the membership root"
    (let [store (mem/create-kv-mem)]
      (is (thrown-with-msg?
            #?(:cljs js/Error
               :cljd Object
               :default Exception)
            #"membership root"
            (index/publish-index! store [] {:key :root/members})))))
  (testing "the 3-arity accepts a valid root key"
    (let [store (mem/create-kv-mem)]
      (index/register-member! store :root/datoms)
      (is (some? (index/publish-index! store [] {:key :root/datoms}))))))


(deftest register-member-guard
  (testing
    "register-member! rejects invalid, empty, or members keys on all platforms"
    (let [store (mem/create-kv-mem)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"non-empty"
            (index/register-member! store (keyword "root" ""))))
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"membership root"
            (index/register-member! store :root/members)))
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #":root/<name>"
            (index/register-member! store :segment/foo))))))


(deftest read-datoms-guard
  (testing
    "read-datoms rejects invalid, empty, or members keys on all platforms"
    (let [store (mem/create-kv-mem)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"keyword key"
            (index/read-datoms store [])))
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"non-empty"
            (index/read-datoms store (keyword "root" ""))))
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"membership root"
            (index/read-datoms store :root/members)))
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #":root/<name>"
            (index/read-datoms store :segment/foo))))))


(deftest index-root-readable-from-plain-node-blobs
  (testing
    "a hand-built node graph — plain EDN blobs, no psset involved in
           its construction — answers q/match on every platform (lazily
           restored on the JVM, eagerly walked elsewhere)"
    (let [store (mem/create-kv-mem)
          leaf-1 {:keys [[1 :a "x" 0 1] [2 :a "y" 0 1]]}
          k1 (jing/segment-key leaf-1)
          leaf-2 {:keys [[3 :b "z" 0 1]]}
          k2 (jing/segment-key leaf-2)
          branch {:level 1,
                  :keys [[2 :a "y" 0 1] [3 :b "z" 0 1]],
                  :addresses [k1 k2]}
          kb (jing/segment-key branch)]
      (jing/put! store k1 leaf-1)
      (jing/put! store k2 leaf-2)
      (jing/put! store kb branch)
      (index/register-member! store :root/test)
      (jing/cas! store
                 :root/test
                 0
                 {:indexes {:eavt kb, :aevt kb, :avet kb, :vaet kb}, :count 3})
      (is (= #{["x"]} (query/q '[:find ?v :where [1 :a ?v]] store)))
      (is (= 3 (count (query/match store ['_ '_ '_])))))))


(deftest publish-index-works-on-every-platform
  ;; historically JVM-only (psset durability was Clojure-only); the
  ;; dao.data.btree switchover made build + lazy restore universal
  (let [store (mem/create-kv-mem)
        datoms [[1 :a "x" 0 1] [2 :a "y" 0 2] [3 :b "z" 0 3]]]
    (index/register-member! store :root/test)
    (index/publish-index! store datoms {:key :root/test})
    (let [manifest (jing/get store :root/test nil)]
      (is (= 3 (:count manifest)))
      (is (= 512 (:branching-factor manifest)))
      (is (every? some? (map (:indexes manifest) [:eavt :aevt :avet :vaet])))
      (is (= (set datoms) (set (query/match store ['_ '_ '_]))))
      (is (= #{["x"]} (query/q '[:find ?v :where [1 :a ?v]] store))))))


(deftest pre-vaet-root-takes-the-eager-path
  (testing
    "a published root from before the VAET addition has only the
           original three keys; the fold guard must reject it from
           restored-indexes (which would deref a nil :vaet address) and
           fall through to the eager walk, so old data still queries"
    (let [store (mem/create-kv-mem)
          leaf {:keys [[1 :a "x" 0 1] [2 :a "y" 0 1]]}
          k (jing/segment-key leaf)]
      (jing/put! store k leaf)
      (index/register-member! store :root/test)
      (jing/cas! store
                 :root/test
                 0
                 ;; pre-VAET shape: no :vaet key
                 {:indexes {:eavt k, :aevt k, :avet k}, :count 2})
      (is (= #{[1 :a "x" 0 1] [2 :a "y" 0 1]}
             (set (query/match store ['_ '_ '_]))))
      (is (= #{["x"]} (query/q '[:find ?v :where [1 :a ?v]] store))))))
