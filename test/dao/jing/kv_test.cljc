(ns dao.jing.kv-test
  "Contract tests for the dao.jing.kv storage boundary.

  Pins the KVStore protocol's observable contract: put!/get round-trip with
  :rev stamping, cas! optimistic-concurrency semantics (success bumps :rev by
  one, a stale rev fails without mutating the entry), delete!, and, on the JVM
  only, cas! under contention with no lost updates."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.jing.kv :as kv]
    [dao.jing.kv.file :as kv.file]))


(defn run-with-stores
  [f]
  (doseq [make [(fn [] [(kv/create-kv-mem) nil])
                (fn []
                  (let [path (str "target/test-db-" (random-uuid) ".db")]
                    [(kv.file/create-kv-file path) path]))]]
    (let [[store path] (make)]
      (try (f store)
           (finally (kv/close! store)
                    (when path
                      #?(:clj (.delete (java.io.File. path))
                         :cljs (.unlinkSync (js/require "fs") path)
                         :cljd nil)))))))


;; ---------------------------------------------------------------------------
;; put! / get
;; ---------------------------------------------------------------------------

(deftest put-then-get-round-trips
  (testing "put! stores the value and get retrieves it with :rev stamped 0"
    (run-with-stores (fn [store]
                       (is (true? (kv/put! store :a {:bytes [1 2 3]})))
                       (is (= {:bytes [1 2 3], :rev 0}
                              (kv/get store :a nil)))))))


(deftest get-absent-returns-not-found
  (testing "get returns the caller-supplied not-found value for an absent key"
    (run-with-stores (fn [store]
                       (is (= :sentinel (kv/get store :absent :sentinel)))
                       (kv/put! store :a {:x 1})
                       (is (= :sentinel (kv/get store :neighbor :sentinel))
                           "a present key leaves every other key absent")))))


(deftest put-overwrites-unconditionally
  (testing
    "put! is unconditional: a second put! replaces the value and re-stamps :rev 0"
    (run-with-stores (fn [store]
                       (kv/put! store :k {:v 1})
                       (kv/put! store :k {:v 2})
                       (is (= {:v 2, :rev 0} (kv/get store :k nil)))))))


;; ---------------------------------------------------------------------------
;; cas!
;; ---------------------------------------------------------------------------

(deftest cas-succeeds-and-bumps-rev
  (testing "cas! with the current rev returns true and advances :rev by one"
    (run-with-stores (fn [store]
                       (kv/put! store :root {:pointer "a"})
                       (is (true? (kv/cas! store :root 0 {:pointer "b"})))
                       (is (= {:pointer "b", :rev 1}
                              (kv/get store :root nil)))))))


(deftest cas-fails-on-stale-rev
  (testing "cas! with a stale rev returns false and leaves the entry unchanged"
    (run-with-stores (fn [store]
                       (kv/put! store :root {:pointer "a"})
                       (is (false? (kv/cas! store :root 99 {:pointer "b"})))
                       (is (= {:pointer "a", :rev 0}
                              (kv/get store :root nil)))))))


(deftest cas-creates-absent-key-at-rev-one
  (testing
    "cas! on a never-written key treats absence as rev 0 and lands at rev 1"
    (run-with-stores (fn [store]
                       (is (true? (kv/cas! store :fresh 0 {:pointer "a"})))
                       (is (= {:pointer "a", :rev 1}
                              (kv/get store :fresh nil)))))))


(deftest cas-replaces-value-wholesale
  (testing
    "cas! stores the new map wholesale (replace, not merge), then stamps :rev"
    (run-with-stores (fn [store]
                       (kv/put! store :k {:keep "yes", :drop "yes"})
                       (is (true? (kv/cas! store :k 0 {:keep "yes"})))
                       (is (= {:keep "yes", :rev 1} (kv/get store :k nil)))))))


(deftest cas-rev-sequence-is-monotonic
  (testing "successive cas! calls advance :rev through 1, 2, 3"
    (run-with-stores (fn [store]
                       (kv/put! store :k {:n 0})
                       (doseq [r [1 2 3]]
                         (is (true? (kv/cas! store :k (dec r) {:n r})))
                         (is (= {:n r, :rev r} (kv/get store :k nil))))))))


;; ---------------------------------------------------------------------------
;; delete! / close!
;; ---------------------------------------------------------------------------

(deftest delete-removes-key
  (testing "delete! removes the entry, so a later get returns not-found"
    (run-with-stores (fn [store]
                       (kv/put! store :a {:x 1})
                       (is (true? (kv/delete! store :a)))
                       (is (= :gone (kv/get store :a :gone)))))))


(deftest delete-absent-is-noop
  (testing "delete! on an absent key returns true and leaves nothing behind"
    (run-with-stores (fn [store]
                       (is (true? (kv/delete! store :ghost)))
                       (is (= :absent (kv/get store :ghost :absent)))))))


(deftest close-releases-without-throwing
  (testing "close! returns nil and does not throw"
    (run-with-stores (fn [store] (is (nil? (kv/close! store)))))))


;; ---------------------------------------------------------------------------
;; Crash Recovery (File Only)
;; ---------------------------------------------------------------------------

(deftest file-store-crash-recovery
  (testing
    "closing a file store and reopening it recovers the exact index and revisions"
    (let [path (str "target/crash-test-" (random-uuid) ".db")
          store1 (kv.file/create-kv-file path)]
      (kv/put! store1 :a {:x 1})
      (kv/cas! store1 :root 0 {:pointer "p1"})
      (kv/cas! store1 :root 1 {:pointer "p2"})
      (kv/delete! store1 :a)
      (kv/close! store1)
      (let [store2 (kv.file/create-kv-file path)]
        (is (= :absent (kv/get store2 :a :absent)))
        (is (= {:pointer "p2", :rev 2} (kv/get store2 :root nil)))
        (kv/close! store2))
      #?(:clj (.delete (java.io.File. path))
         :cljs (.unlinkSync (js/require "fs") path)
         :cljd nil))))


(deftest file-store-recovers-from-torn-tail
  #?(:clj
     (testing
       "a crash mid-append leaves a partial record; recovery skips it, keeps every complete record, and truncates the tail"
       (let [path (str "target/torn-test-" (random-uuid) ".db")
             store1 (kv.file/create-kv-file path)]
         (kv/put! store1 :a {:x 1})
         (kv/cas! store1 :root 0 {:pointer "p1"})
         (kv/close! store1)
         (let [clean-len (.length (java.io.File. path))]
           ;; Simulate a torn write: a length header claiming 999 bytes
           ;; followed by only a few, exactly what a crash mid-append
           ;; would leave behind.
           (with-open [raf (java.io.RandomAccessFile. path "rw")]
             (.seek raf (.length raf))
             (.writeInt raf 999)
             (.write raf (.getBytes "partial" "UTF-8")))
           (is (> (.length (java.io.File. path)) clean-len)
               "the torn tail is on disk before recovery")
           (let [store2 (kv.file/create-kv-file path)]
             (is (= {:x 1, :rev 0} (kv/get store2 :a nil))
                 "complete records before the tear survive")
             (is (= {:pointer "p1", :rev 1} (kv/get store2 :root nil)))
             (kv/close! store2))
           (is
             (= clean-len (.length (java.io.File. path)))
             "recovery truncates the torn tail, restoring a clean append boundary")
           (.delete (java.io.File. path)))))))


(deftest file-store-recovers-from-corrupt-payload
  #?(:clj
     (testing
       "a length-consistent but unparseable record survives frame truncation; recovery stops the walk instead of crashing open"
       (let [path (str "target/corrupt-test-" (random-uuid) ".db")
             store1 (kv.file/create-kv-file path)]
         (kv/put! store1 :a {:x 1})
         (kv/cas! store1 :root 0 {:pointer "p1"})
         (kv/close! store1)
         ;; Append a frame whose length header matches its payload
         ;; exactly (so the transport's boundary scan keeps it) but whose
         ;; bytes are not EDN, e.g. bit-rot on a complete record.
         (let [garbage (.getBytes "}}}not-edn[[[" "UTF-8")]
           (with-open [raf (java.io.RandomAccessFile. path "rw")]
             (.seek raf (.length raf))
             (.writeInt raf (alength garbage))
             (.write raf garbage)))
         (let [store2 (kv.file/create-kv-file path)]
           (is
             (= {:x 1, :rev 0} (kv/get store2 :a nil))
             "records before the corrupt frame are recovered, not crashed on")
           (is (= {:pointer "p1", :rev 1} (kv/get store2 :root nil)))
           (kv/close! store2))
         (.delete (java.io.File. path))))))


;; ---------------------------------------------------------------------------
;; Concurrency (JVM only; cljs is single-threaded, so real contention is clj)
;; ---------------------------------------------------------------------------

(deftest cas-contention-loses-no-updates
  #?(:clj
     (testing
       "concurrent cas! retries apply exactly one bump per worker with no lost updates"
       (run-with-stores
         (fn [store]
           (let [n 200
                 _ (kv/put! store :counter {:n 0})
                 wins (atom 0)
                 workers (doall
                           (for [_ (range n)]
                             (future
                               (loop []
                                 (let [cur (kv/get store :counter nil)
                                       old-rev (:rev cur)
                                       proposed (assoc cur :n (inc (:n cur)))]
                                   (if
                                     (kv/cas! store :counter old-rev proposed)
                                     (swap! wins inc)
                                     (recur)))))))]
             (run! deref workers)
             (let [final (kv/get store :counter nil)]
               (is (= n @wins) "every worker must win its cas exactly once")
               (is (= n (:rev final))
                   ":rev must equal the number of applied updates")
               (is (= n (:n final)) "the value must agree with :rev"))))))))
