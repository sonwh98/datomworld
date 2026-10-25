(ns dao.data.btree.storage-read-test
  "dao.data.btree.storage/read-node-blob (Phase 4b-2): exactly ONE verified
   `dao.jing/get` per call, the decoded blob back, the missing-segment error
   with its address, failures propagated with their cause, and no
   traversal. Reads are counted by an explicit wrapper store (no
   with-redefs), so the proof runs on every host."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.data.btree :as bt]
            [dao.data.btree.storage :as bts]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]))


(defn- counting
  "A wrapper over a content store that logs every get address."
  [store log]
  (assoc store
         :get-bytes-fn
         (fn [address not-found]
           (vswap! log conj address)
           ((:get-bytes-fn store) address not-found))))


(defn- persisted
  "A bf-4 tree of (range n) stored in a fresh in-memory content store:
   {:store :address}."
  [n]
  (let [store (mem/create-content-mem)
        storage (bts/kv-storage store {:branching-factor 4})
        s (into (bt/restore-tree compare nil storage 0) (range n))]
    {:store store, :address (bt/store-tree s storage)}))


(defn- thrown
  [f]
  (try (f)
       nil
       (catch #?(:cljd Object :clj Throwable :cljs :default) e e)))


(deftest one-get-per-call-and-the-decoded-blob
  (let [{:keys [store address]} (persisted 100)
        log (volatile! [])
        wrapped (counting store log)
        blob (bts/read-node-blob wrapped address)]
    (is (= [address] @log) "exactly one get, for the requested address")
    (is (vector? (:keys blob)))
    (is (contains? blob :addresses) "the root of a 100-key bf-4 tree is a branch")
    (is (= (count (:keys blob)) (count (:addresses blob))))
    (testing "no hydration of descendants: a second call is a second get"
      (bts/read-node-blob wrapped address)
      (is (= [address address] @log)))
    (testing "children are read only when asked, one get each"
      (vreset! log [])
      (let [child (first (:addresses blob))
            cblob (bts/read-node-blob wrapped child)]
        (is (= [child] @log))
        (is (vector? (:keys cblob)))))))


(deftest the-blob-is-what-the-storage-restores
  (let [{:keys [store address]} (persisted 40)
        storage (bts/kv-storage store {:branching-factor 4})
        blob (bts/read-node-blob store address)
        node (bt/-restore storage address)]
    (is (= blob (bt/node->blob node)))))


(deftest a-missing-segment-throws-with-its-address-after-one-get
  (let [{:keys [store]} (persisted 10)
        absent (jing/segment-key {:keys [123456 654321]})
        log (volatile! [])
        e (thrown #(bts/read-node-blob (counting store log) absent))]
    (is (some? e))
    (is (= "missing index segment" (ex-message e)))
    (is (= {:address absent} (ex-data e)))
    (is (= [absent] @log) "one get, no retry")))


(deftest a-failing-read-propagates-unchanged-after-one-get
  (let [{:keys [store address]} (persisted 10)
        log (volatile! [])
        forged (assoc store
                      :get-bytes-fn
                      (fn [a _]
                        (vswap! log conj a)
                        (jing/canonical-bytes {:keys [9 9 9]})))
        e (thrown #(bts/read-node-blob forged address))]
    (is (some? e) "bytes that do not hash to the address are refused by jing/get")
    (is (not= "missing index segment" (ex-message e)))
    (is (= [address] @log))))


(deftest a-non-segment-address-is-refused-before-any-get
  (let [{:keys [store]} (persisted 10)
        log (volatile! [])
        e (thrown #(bts/read-node-blob (counting store log) "not-an-address"))]
    (is (some? e))
    (is (empty? @log))))
