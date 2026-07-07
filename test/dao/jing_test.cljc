(ns dao.jing-test
  "Contract tests for the dao.jing storage boundary.

  Pins the KVStore protocol's observable contract: put!/get round-trip with
  :rev stamping, cas! optimistic-concurrency semantics (success bumps :rev by
  one, a stale rev fails without mutating the entry), delete!, and, on the JVM
  only, cas! under contention with no lost updates."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.file :as file]))


(defn run-with-stores
  [f]
  (doseq [make [(fn [] [(jing/create-kv-mem) nil])
                (fn []
                  (let [path (str "target/test-db-" (random-uuid) ".db")]
                    [(file/create-kv-file path) path]))]]
    (let [[store path] (make)]
      (try (f store)
           (finally (jing/close! store)
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
                       (is (true? (jing/put! store :a {:bytes [1 2 3]})))
                       (is (= {:bytes [1 2 3], :rev 0}
                              (jing/get store :a nil)))))))


(deftest get-absent-returns-not-found
  (testing "get returns the caller-supplied not-found value for an absent key"
    (run-with-stores (fn [store]
                       (is (= :sentinel (jing/get store :absent :sentinel)))
                       (jing/put! store :a {:x 1})
                       (is (= :sentinel (jing/get store :neighbor :sentinel))
                           "a present key leaves every other key absent")))))


(deftest put-overwrites-unconditionally
  (testing
    "put! is unconditional: a second put! replaces the value and re-stamps :rev 0"
    (run-with-stores (fn [store]
                       (jing/put! store :k {:v 1})
                       (jing/put! store :k {:v 2})
                       (is (= {:v 2, :rev 0} (jing/get store :k nil)))))))


;; ---------------------------------------------------------------------------
;; cas!
;; ---------------------------------------------------------------------------

(deftest cas-succeeds-and-bumps-rev
  (testing "cas! with the current rev returns true and advances :rev by one"
    (run-with-stores (fn [store]
                       (jing/put! store :root {:pointer "a"})
                       (is (true? (jing/cas! store :root 0 {:pointer "b"})))
                       (is (= {:pointer "b", :rev 1}
                              (jing/get store :root nil)))))))


(deftest cas-fails-on-stale-rev
  (testing "cas! with a stale rev returns false and leaves the entry unchanged"
    (run-with-stores (fn [store]
                       (jing/put! store :root {:pointer "a"})
                       (is (false? (jing/cas! store :root 99 {:pointer "b"})))
                       (is (= {:pointer "a", :rev 0}
                              (jing/get store :root nil)))))))


(deftest cas-creates-absent-key-at-rev-one
  (testing
    "cas! on a never-written key treats absence as rev 0 and lands at rev 1"
    (run-with-stores (fn [store]
                       (is (true? (jing/cas! store :fresh 0 {:pointer "a"})))
                       (is (= {:pointer "a", :rev 1}
                              (jing/get store :fresh nil)))))))


(deftest cas-replaces-value-wholesale
  (testing
    "cas! stores the new map wholesale (replace, not merge), then stamps :rev"
    (run-with-stores (fn [store]
                       (jing/put! store :k {:keep "yes", :drop "yes"})
                       (is (true? (jing/cas! store :k 0 {:keep "yes"})))
                       (is (= {:keep "yes", :rev 1}
                              (jing/get store :k nil)))))))


(deftest cas-rev-sequence-is-monotonic
  (testing "successive cas! calls advance :rev through 1, 2, 3"
    (run-with-stores (fn [store]
                       (jing/put! store :k {:n 0})
                       (doseq [r [1 2 3]]
                         (is (true? (jing/cas! store :k (dec r) {:n r})))
                         (is (= {:n r, :rev r} (jing/get store :k nil))))))))


;; ---------------------------------------------------------------------------
;; delete! / close!
;; ---------------------------------------------------------------------------

(deftest delete-removes-key
  (testing "delete! removes the entry, so a later get returns not-found"
    (run-with-stores (fn [store]
                       (jing/put! store :a {:x 1})
                       (is (true? (jing/delete! store :a)))
                       (is (= :gone (jing/get store :a :gone)))))))


(deftest delete-absent-is-noop
  (testing "delete! on an absent key returns true and leaves nothing behind"
    (run-with-stores (fn [store]
                       (is (true? (jing/delete! store :ghost)))
                       (is (= :absent (jing/get store :ghost :absent)))))))


(deftest close-releases-without-throwing
  (testing "close! returns nil and does not throw"
    (run-with-stores (fn [store] (is (nil? (jing/close! store)))))))


;; ---------------------------------------------------------------------------
;; Crash Recovery (File Only)
;; ---------------------------------------------------------------------------

(deftest file-store-crash-recovery
  (testing
    "closing a file store and reopening it recovers the exact index and revisions"
    (let [path (str "target/crash-test-" (random-uuid) ".db")
          store1 (file/create-kv-file path)]
      (jing/put! store1 :a {:x 1})
      (jing/cas! store1 :root 0 {:pointer "p1"})
      (jing/cas! store1 :root 1 {:pointer "p2"})
      (jing/delete! store1 :a)
      (jing/close! store1)
      (let [store2 (file/create-kv-file path)]
        (is (= :absent (jing/get store2 :a :absent)))
        (is (= {:pointer "p2", :rev 2} (jing/get store2 :root nil)))
        (jing/close! store2))
      #?(:clj (.delete (java.io.File. path))
         :cljs (.unlinkSync (js/require "fs") path)
         :cljd nil))))


(deftest file-store-recovers-from-torn-tail
  #?(:clj
     (testing
       "a crash mid-append leaves a partial record; recovery skips it, keeps every complete record, and truncates the tail"
       (let [path (str "target/torn-test-" (random-uuid) ".db")
             store1 (file/create-kv-file path)]
         (jing/put! store1 :a {:x 1})
         (jing/cas! store1 :root 0 {:pointer "p1"})
         (jing/close! store1)
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
           (let [store2 (file/create-kv-file path)]
             (is (= {:x 1, :rev 0} (jing/get store2 :a nil))
                 "complete records before the tear survive")
             (is (= {:pointer "p1", :rev 1} (jing/get store2 :root nil)))
             (jing/close! store2))
           (is
             (= clean-len (.length (java.io.File. path)))
             "recovery truncates the torn tail, restoring a clean append boundary")
           (.delete (java.io.File. path)))))))


(deftest file-store-recovers-from-corrupt-payload
  #?(:clj
     (testing
       "a length-consistent but unparseable record survives frame truncation; recovery stops the walk instead of crashing open"
       (let [path (str "target/corrupt-test-" (random-uuid) ".db")
             store1 (file/create-kv-file path)]
         (jing/put! store1 :a {:x 1})
         (jing/cas! store1 :root 0 {:pointer "p1"})
         (jing/close! store1)
         ;; Append a frame whose length header matches its payload
         ;; exactly (so the transport's boundary scan keeps it) but whose
         ;; bytes are not EDN, e.g. bit-rot on a complete record.
         (let [garbage (.getBytes "}}}not-edn[[[" "UTF-8")]
           (with-open [raf (java.io.RandomAccessFile. path "rw")]
             (.seek raf (.length raf))
             (.writeInt raf (alength garbage))
             (.write raf garbage)))
         (let [store2 (file/create-kv-file path)]
           (is
             (= {:x 1, :rev 0} (jing/get store2 :a nil))
             "records before the corrupt frame are recovered, not crashed on")
           (is (= {:pointer "p1", :rev 1} (jing/get store2 :root nil)))
           (jing/close! store2))
         (.delete (java.io.File. path))))))


;; ---------------------------------------------------------------------------
;; Compaction (File Only)
;; ---------------------------------------------------------------------------

(deftest file-store-compaction
  #?(:clj
     (testing
       "compaction removes dead keys, shrinks the file, and leaves live keys intact"
       (let [path (str "target/compact-test-" (random-uuid) ".db")
             store (file/create-kv-file path)]
         ;; Write a mix of keys
         (jing/put! store :live {:x 1})
         (jing/put! store :dead {:x 2})
         ;; Update a key multiple times (creates dead space)
         (jing/cas! store :root 0 {:p "1"})
         (jing/cas! store :root 1 {:p "2"})
         (jing/cas! store :root 2 {:p "3"})
         ;; Delete a key (creates dead space and a tombstone)
         (jing/delete! store :dead)
         (let [pre-size (.length (java.io.File. path))]
           (is (true? (file/compact-store! store)))
           (let [post-size (.length (java.io.File. path))]
             (is (< post-size pre-size)
                 "the file size should shrink after compaction")
             ;; Verify live keys are intact
             (is (= {:x 1, :rev 0} (jing/get store :live nil)))
             (is (= {:p "3", :rev 3} (jing/get store :root nil)))
             (is (= :gone (jing/get store :dead :gone)))
             ;; Write after compact to verify the new stream is writable
             (is (true? (jing/put! store :post {:x 3})))
             (is (= {:x 3, :rev 0} (jing/get store :post nil)))
             (jing/close! store)
             (.delete (java.io.File. path))))))))


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
                 _ (jing/put! store :counter {:n 0})
                 wins (atom 0)
                 workers
                 (doall
                   (for [_ (range n)]
                     (future
                       (loop []
                         (let [cur (jing/get store :counter nil)
                               old-rev (:rev cur)
                               proposed (assoc cur :n (inc (:n cur)))]
                           (if (jing/cas! store :counter old-rev proposed)
                             (swap! wins inc)
                             (recur)))))))]
             (run! deref workers)
             (let [final (jing/get store :counter nil)]
               (is (= n @wins) "every worker must win its cas exactly once")
               (is (= n (:rev final))
                   ":rev must equal the number of applied updates")
               (is (= n (:n final)) "the value must agree with :rev"))))))))


;; ---------------------------------------------------------------------------
;; Read-after-close and read/compact races (JVM only)
;; ---------------------------------------------------------------------------

(deftest get-after-close-terminates
  #?(:clj
     (testing
       "get on a closed store returns promptly instead of spinning in the ::closed retry loop"
       ;; After close! the stream is closed, so ds/next throws and get
       ;; catches it as ::closed. The retry re-reads the same closed
       ;; stream and loops, because it never checks the store's :closed
       ;; flag: the transient compaction-swap case and the
       ;; permanent-close case are indistinguishable to the retry. The
       ;; result is either an unbounded busy-wait (ds/next blocks) or a
       ;; StackOverflowError (ds/next throws and get self-recurses
       ;; without tail-call elimination). The deref timeout turns that
       ;; hang into a deterministic failure rather than wedging the whole
       ;; suite.
       (let [path (str "target/closed-get-test-" (random-uuid) ".db")
             store (file/create-kv-file path)]
         (try
           (jing/put! store :k {:x 1})
           (jing/close! store)
           (let [outcome (deref (future
                                  (try {:value (jing/get store :k :not-found)}
                                       (catch Throwable t
                                         {:threw (str (type t))})))
                                2000
                                {:timeout true})]
             (is
               (= {:value :not-found} outcome)
               (str
                 "get on a closed store should return the not-found sentinel "
                 "promptly; got "
                 outcome)))
           (finally (.delete (java.io.File. path))))))))


(deftest get-during-compaction-stays-correct
  #?(:clj
     (testing
       "a concurrent lock-free get never throws or observes a wrong/absent value for a live key while compact! swaps the stream"
       (let [path (str "target/compact-race-test-" (random-uuid) ".db")
             store (file/create-kv-file path)]
         (try
           ;; :root is written once and never changed, so every read must
           ;; see exactly this value; :churn is rewritten to create dead
           ;; space so each compact! actually rebuilds the log and swaps
           ;; the stream.
           (jing/put! store :root {:p "v"})
           (let [expected {:p "v", :rev 0}
                 stop (atom false)
                 seen-wrong (atom [])
                 reader (future (while (not @stop)
                                  (let [v (try
                                            (jing/get store :root :not-found)
                                            (catch Throwable t
                                              {:threw (str (type t))}))]
                                    (when (and (not= expected v)
                                               (< (count @seen-wrong) 50))
                                      (swap! seen-wrong conj v)))))]
             (dotimes [_ 30]
               (jing/put! store :churn {:n (rand-int 1000)})
               (file/compact-store! store))
             (reset! stop true)
             (deref reader 2000 :reader-timeout)
             (is (empty? @seen-wrong)
                 (str
                   "concurrent reads during compaction returned wrong/absent "
                   "values for a live key: "
                   (distinct @seen-wrong))))
           (finally (jing/close! store) (.delete (java.io.File. path))))))))


(deftest compaction-failure-leaves-store-usable
  #?(:clj
     (testing
       "a compact! that fails in the swap tail (after the old stream is closed) restores a live stream instead of wedging get"
       (let [path (str "target/compact-fail-test-" (random-uuid) ".db")
             store (file/create-kv-file path)]
         (try
           (jing/put! store :live {:x 1})
           (jing/cas! store :root 0 {:p "1"})
           (jing/cas! store :root 1 {:p "2"}) ; dead space so compaction
           ;; rewrites. Inject a failure in rename-file!, which runs
           ;; AFTER compact! Has already closed the old stream — the
           ;; exact window that used to leave state-atom pointing at a
           ;; dead stream.
           (is (thrown? Exception
                 (with-redefs [dao.jing.file/rename-file!
                               (fn [_ _]
                                 (throw (ex-info
                                          "injected rename failure"
                                          {})))]
                   (file/compact-store! store))))
           ;; The store must stay readable, promptly (no ::closed retry
           ;; hang), and no data may be lost by the aborted compaction.
           (let [outcome
                 (deref (future
                          (try {:live (jing/get store :live :not-found),
                                :root (jing/get store :root :not-found)}
                               (catch Throwable t {:threw (str (type t))})))
                        2000
                        {:timeout true})]
             (is
               (= {:live {:x 1, :rev 0}, :root {:p "2", :rev 2}} outcome)
               (str
                 "store should stay readable after a failed compaction; got "
                 outcome)))
           ;; And the restored stream must still accept writes.
           (is (true? (jing/put! store :post {:y 9})))
           (is (= {:y 9, :rev 0} (jing/get store :post nil)))
           (finally (jing/close! store) (.delete (java.io.File. path))))))))
