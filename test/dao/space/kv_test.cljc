(ns dao.space.kv-test
  "Contract tests for the dao.space.kv storage boundary.

  Pins the KVStore protocol's observable contract: put!/get round-trip with
  :rev stamping, cas! optimistic-concurrency semantics (success bumps :rev by
  one, a stale rev fails without mutating the entry), delete!, and, on the JVM
  only, cas! under contention with no lost updates."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.space.kv :as kv]))


;; ---------------------------------------------------------------------------
;; put! / get
;; ---------------------------------------------------------------------------

(deftest put-then-get-round-trips
  (testing "put! stores the value and get retrieves it with :rev stamped 0"
    (let [store (kv/create-kv-mem)]
      (is (true? (kv/put! store :a {:bytes [1 2 3]})))
      (is (= {:bytes [1 2 3], :rev 0} (kv/get store :a nil))))))


(deftest get-absent-returns-not-found
  (testing "get returns the caller-supplied not-found value for an absent key"
    (let [store (kv/create-kv-mem)]
      (is (= :sentinel (kv/get store :absent :sentinel)))
      (kv/put! store :a {:x 1})
      (is (= :sentinel (kv/get store :neighbor :sentinel))
          "a present key leaves every other key absent"))))


(deftest put-overwrites-unconditionally
  (testing
    "put! is unconditional: a second put! replaces the value and re-stamps :rev 0"
    ;; Documented behavior, not a guard. Callers reserve put! for fresh,
    ;; content-derived keys (immutable segments); mutable references go
    ;; through cas!.
    (let [store (kv/create-kv-mem)]
      (kv/put! store :k {:v 1})
      (kv/put! store :k {:v 2})
      (is (= {:v 2, :rev 0} (kv/get store :k nil))))))


;; ---------------------------------------------------------------------------
;; cas!
;; ---------------------------------------------------------------------------

(deftest cas-succeeds-and-bumps-rev
  (testing "cas! with the current rev returns true and advances :rev by one"
    (let [store (kv/create-kv-mem)]
      (kv/put! store :root {:pointer "a"})
      (is (true? (kv/cas! store :root 0 {:pointer "b"})))
      (is (= {:pointer "b", :rev 1} (kv/get store :root nil))))))


(deftest cas-fails-on-stale-rev
  (testing "cas! with a stale rev returns false and leaves the entry unchanged"
    (let [store (kv/create-kv-mem)]
      (kv/put! store :root {:pointer "a"})
      (is (false? (kv/cas! store :root 99 {:pointer "b"})))
      (is (= {:pointer "a", :rev 0} (kv/get store :root nil))))))


(deftest cas-creates-absent-key-at-rev-one
  (testing
    "cas! on a never-written key treats absence as rev 0 and lands at rev 1"
    (let [store (kv/create-kv-mem)]
      (is (true? (kv/cas! store :fresh 0 {:pointer "a"})))
      (is (= {:pointer "a", :rev 1} (kv/get store :fresh nil))))))


(deftest cas-replaces-value-wholesale
  (testing
    "cas! stores the new map wholesale (replace, not merge), then stamps :rev"
    ;; Mirrors Datomic's put: the val-map row is replaced. Keys absent from
    ;; the new map are dropped, as in a rev-gated UPDATE of the whole row.
    (let [store (kv/create-kv-mem)]
      (kv/put! store :k {:keep "yes", :drop "yes"})
      (is (true? (kv/cas! store :k 0 {:keep "yes"})))
      (is (= {:keep "yes", :rev 1} (kv/get store :k nil))))))


(deftest cas-rev-sequence-is-monotonic
  (testing "successive cas! calls advance :rev through 1, 2, 3"
    (let [store (kv/create-kv-mem)]
      (kv/put! store :k {:n 0})
      (doseq [r [1 2 3]]
        (is (true? (kv/cas! store :k (dec r) {:n r})))
        (is (= {:n r, :rev r} (kv/get store :k nil)))))))


;; ---------------------------------------------------------------------------
;; delete! / close!
;; ---------------------------------------------------------------------------

(deftest delete-removes-key
  (testing "delete! removes the entry, so a later get returns not-found"
    (let [store (kv/create-kv-mem)]
      (kv/put! store :a {:x 1})
      (is (true? (kv/delete! store :a)))
      (is (= :gone (kv/get store :a :gone))))))


(deftest delete-absent-is-noop
  (testing "delete! on an absent key returns true and leaves nothing behind"
    (let [store (kv/create-kv-mem)]
      (is (true? (kv/delete! store :ghost)))
      (is (= :absent (kv/get store :ghost :absent))))))


(deftest close-releases-without-throwing
  (testing "close! returns nil and does not throw"
    (is (nil? (kv/close! (kv/create-kv-mem))))))


;; ---------------------------------------------------------------------------
;; Concurrency (JVM only; cljs is single-threaded, so real contention is clj)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest cas-contention-loses-no-updates
     (testing
       "concurrent cas! retries apply exactly one bump per worker with no lost updates"
       (let [store (kv/create-kv-mem)
             n 200
             _ (kv/put! store :counter {:n 0})
             wins (atom 0)
             workers (doall
                       (for [_ (range n)]
                         (future
                           (loop []
                             (let [cur (kv/get store :counter nil)
                                   old-rev (:rev cur)
                                   proposed (assoc cur :n (inc (:n cur)))]
                               (if (kv/cas! store :counter old-rev proposed)
                                 (swap! wins inc)
                                 (recur)))))))]
         (run! deref workers)
         (let [final (kv/get store :counter nil)]
           (is (= n @wins) "every worker must win its cas exactly once")
           (is (= n (:rev final))
               ":rev must equal the number of applied updates")
           (is (= n (:n final)) "the value must agree with :rev"))))))
