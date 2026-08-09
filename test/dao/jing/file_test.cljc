(ns dao.jing.file-test
  "Unit and integration tests for dao.jing.file stream materializer."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.file :as jing-file]
            [dao.stream :as ds]
            [dao.stream.file]))


(defn- temp-path
  [prefix]
  (str "target/test-jing-file-" prefix "-" (random-uuid) ".log"))


(defn- cleanup-file
  [path]
  #?(:clj (let [f (java.io.File. path)]
            (when (.exists f) (.delete f))
            (let [c (java.io.File. (str path ".compact"))]
              (when (.exists c) (.delete c))))
     :cljs (try (.unlinkSync (js/require "fs") path) (catch :default _))
     :cljd nil))


(deftest record-serialization-test
  (testing "encode-record and decode-record round trip :cas tuples"
    (let [rec [:my-key :cas jing/absent {:a 1, :b [2 3]}]]
      (is (= rec (jing-file/decode-record (jing-file/encode-record rec)))))))


(deftest file-stream-reduction-test
  (testing "reducing a file stream of :cas records projects into a target map"
    (let [path (temp-path "reduce")
          stream (ds/open! {:type :file, :path path})]
      (try (ds/append! stream
                       (jing-file/encode-record [:k1 :cas jing/absent "val1"]))
           (ds/append! stream
                       (jing-file/encode-record [:k2 :cas jing/absent "val2"]))
           (ds/append! stream
                       (jing-file/encode-record [:k1 :cas "val1"
                                                 "val1-updated"]))
           (ds/append! stream
                       (jing-file/encode-record [:k2 :cas "val2" jing/absent]))
           (let [target (jing-file/reduce-file-stream stream)]
             (is (= "val1-updated" (get target :k1)))
             (is (= jing/absent (get target :k2 jing/absent))))
           (finally (ds/close! stream) (cleanup-file path))))))


(deftest file-stream-incremental-step-test
  (testing "step-incremental-file! advances cursor and updates target atom"
    (let [path (temp-path "incremental")
          stream (ds/open! {:type :file, :path path})
          target-atom (atom {})
          cursor-atom (atom {:position 0})]
      (try
        (ds/append! stream (jing-file/encode-record [:k1 :cas jing/absent 100]))
        (is
          (= :ok
             (jing-file/step-incremental-file! target-atom cursor-atom stream)))
        (is (= 100 (get @target-atom :k1)))
        ;; Next read when blocked returns :wait
        (is
          (= :wait
             (jing-file/step-incremental-file! target-atom cursor-atom stream)))
        (finally (ds/close! stream) (cleanup-file path))))))


(deftest file-stream-malformed-recovery-test
  (testing "reduce-file-stream skips malformed EDN frames and recovers"
    (let [path (temp-path "malformed")
          stream (ds/open! {:type :file, :path path})]
      (try (ds/append! stream
                       (jing-file/encode-record [:k1 :cas jing/absent "val1"]))
           ;; Append a malformed EDN frame
           (ds/append! stream
                       (jing-file/->bytes "[\"this is\" \"not a valid cas\"]"))
           (ds/append! stream
                       (jing-file/encode-record [:k2 :cas jing/absent "val2"]))
           (let [target (jing-file/reduce-file-stream stream)]
             (is (= "val1" (get target :k1)))
             (is (= "val2" (get target :k2))))
           (finally (ds/close! stream) (cleanup-file path))))))


(deftest file-kv-store-contract-test
  (testing "create-kv-file satisfies IKVStore over dao.stream.file"
    (let [path (temp-path "kv")
          store (jing-file/create-kv-file path)]
      (try
        ;; cas! minting
        (is (true? (jing/cas! store :a jing/absent "hello")))
        (is (= "hello" (jing/get store :a nil)))
        ;; cas! update
        (is (true? (jing/cas! store :a "hello" "world")))
        (is (= "world" (jing/get store :a nil)))
        ;; cas! losing race
        (is (false? (jing/cas! store :a "stale" "new")))
        (is (= "world" (jing/get store :a nil)))
        ;; delete!
        (is (true? (jing/delete! store :a)))
        (is (= ::missing (jing/get store :a ::missing)))
        (finally (jing/close! store) (cleanup-file path))))))


(deftest file-kv-store-durability-recovery-test
  (testing "reopening a file store recovers state from log replay"
    (let [path (temp-path "durability")
          s1 (jing-file/create-kv-file path)]
      (jing/cas! s1 :x jing/absent {:data 42})
      (jing/cas! s1 :y jing/absent "persist")
      (jing/close! s1)
      (let [s2 (jing-file/create-kv-file path)]
        (try (is (= {:data 42} (jing/get s2 :x nil)))
             (is (= "persist" (jing/get s2 :y nil)))
             (finally (jing/close! s2) (cleanup-file path)))))))


(deftest file-kv-store-compaction-test
  (testing "compact-store! reclaims space and preserves live keys"
    (let [path (temp-path "compact")
          store (jing-file/create-kv-file path)]
      (try (jing/cas! store :a jing/absent 1)
           (jing/cas! store :b jing/absent 2)
           (jing/cas! store :a 1 3)
           (jing/delete! store :b)
           (let [store (jing-file/compact-store! store)]
             (is (some? store))
             (is (= 3 (jing/get store :a nil)))
             (is (= ::missing (jing/get store :b ::missing)))
             (jing/close! store))
           (finally (cleanup-file path))))))


#?(:cljd nil
   :clj
   (deftest file-kv-store-concurrency-test
     (testing "durability CAS result is serialized with memory CAS result"
       (let [path (temp-path "concurrency")
             store (jing-file/create-kv-file path)
             orig-materialize jing/materialize-step
             first-append (promise)
             unblock-f1 (promise)]
         (try
           (with-redefs [jing/materialize-step
                         (fn [m rec]
                           (when (= "A" (nth rec 3 nil))
                             (deliver first-append true)
                             (deref unblock-f1 3000 nil))
                           (orig-materialize m rec))]
             (let [f1 (future (jing/cas! store :race jing/absent "A"))
                   _ (deref first-append 1000 nil)
                   f2 (future (jing/cas! store :race jing/absent "B"))]
               (try #?(:clj (Thread/sleep 50))
                    (finally (deliver unblock-f1 true)))
               (let [res-a (deref f1 3000 ::timeout)
                     res-b (deref f2 3000 ::timeout)
                     mem-val (jing/get store :race nil)]
                 (is (not= ::timeout res-a) "f1 completed without timing out")
                 (is (not= ::timeout res-b) "f2 completed without timing out")
                 (jing/close! store)
                 (let [store2 (jing-file/create-kv-file path)]
                   (try (let [recovered-val (jing/get store2 :race nil)]
                          (is (= mem-val recovered-val)
                              "Execution state must match recovered state"))
                        (finally (jing/close! store2)))))))
           (finally (deliver first-append false)
                    (deliver unblock-f1 true)
                    (cleanup-file path)))))))
