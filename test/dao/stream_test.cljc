(ns dao.stream-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]))


;; =============================================================================
;; Test helper
;; =============================================================================

(defn- make-stream
  ([] (ds/->LazySeqStream nil (atom {:log [], :head 0, :closed false})))
  ([capacity]
   (ds/->LazySeqStream capacity (atom {:log [], :head 0, :closed false}))))


;; =============================================================================
;; LazySeqStream Tests
;; =============================================================================

(deftest lazy-seq-stream-test
  (testing "Fresh LazySeqStream is open and empty"
    (let [s (make-stream)]
      (is (false? (ds/closed? s)))
      (is (= 0 (ds/length s))))))


(deftest put-take-test
  (testing "put! / take! round trip with length tracking"
    (let [s (make-stream)]
      (is (= :ok (ds/put! s :a)))
      (is (= :ok (ds/put! s :b)))
      (is (= 2 (ds/length s)))
      (is (= {:ok :a} (ds/take! s)))
      (is (= 1 (ds/length s)))
      (is (= {:ok :b} (ds/take! s)))
      (is (= 0 (ds/length s))))))


(deftest cursor-next-test
  (testing "next is non-destructive, cursor position advances"
    (let [s (make-stream)
          _ (ds/put! s :a)
          _ (ds/put! s :b)
          r1 (ds/next s {:position 0})]
      (is (= :a (:ok r1)))
      (is (= {:position 1} (:cursor r1)))
      (is (= 2 (ds/length s)) "next does not consume")
      (let [r2 (ds/next s (:cursor r1))]
        (is (= :b (:ok r2)))
        (is (= {:position 2} (:cursor r2))))))
  (testing "next at end of open stream returns :blocked"
    (let [s (make-stream)] (is (= :blocked (ds/next s {:position 0})))))
  (testing "next at end of closed stream returns :end"
    (let [s (make-stream)]
      (ds/close! s)
      (is (= :end (ds/next s {:position 0})))))
  (testing "next on closed stream with data returns data then :end"
    (let [s (make-stream)]
      (ds/put! s :x)
      (ds/close! s)
      (let [r1 (ds/next s {:position 0})]
        (is (= :x (:ok r1)))
        (is (= :end (ds/next s (:cursor r1))))))))


(deftest close-test
  (testing "closed? false before, true after close!"
    (let [s (make-stream)]
      (is (false? (ds/closed? s)))
      (ds/close! s)
      (is (true? (ds/closed? s)))))
  (testing "put! throws on closed stream"
    (let [s (make-stream)]
      (ds/close! s)
      (is (thrown? #?(:clj Exception
                      :cljs js/Error)
            (ds/put! s 42)))))
  (testing "take! returns :end on closed empty stream"
    (let [s (make-stream)]
      (ds/close! s)
      (is (= :end (ds/take! s))))))


(deftest length-test
  (testing "length tracks puts and takes"
    (let [s (make-stream)]
      (is (= 0 (ds/length s)))
      (ds/put! s :a)
      (is (= 1 (ds/length s)))
      (ds/put! s :b)
      (is (= 2 (ds/length s)))
      (ds/take! s)
      (is (= 1 (ds/length s)))
      (ds/take! s)
      (is (= 0 (ds/length s))))))


(deftest gap-test
  (testing "cursor behind head returns :daostream/gap after take!"
    (let [s (make-stream)]
      (ds/put! s :a)
      (ds/put! s :b)
      (ds/take! s)
      (is (= :daostream/gap (ds/next s {:position 0}))
          "Cursor at pos 0 with head at 1 should return gap")
      (let [r (ds/next s {:position 1})] (is (= :b (:ok r)))))))


(deftest independent-cursors-test
  (testing "Two cursors advance independently"
    (let [s (make-stream)]
      (ds/put! s :a)
      (ds/put! s :b)
      (ds/put! s :c)
      (let [c1 {:position 0}
            c2 {:position 0}
            r1a (ds/next s c1)
            r1b (ds/next s (:cursor r1a))
            r2a (ds/next s c2)]
        (is (= :a (:ok r1a)))
        (is (= :b (:ok r1b)))
        (is (= :a (:ok r2a)))
        (is (= 2 (:position (:cursor r1b))))
        (is (= 1 (:position (:cursor r2a))))))))


(deftest capacity-test
  (testing "put! returns :full at capacity, :ok after take! frees space"
    (let [s (make-stream 2)]
      (is (= :ok (ds/put! s :a)))
      (is (= :ok (ds/put! s :b)))
      (is (= :full (ds/put! s :c)))
      (ds/take! s)
      (is (= :ok (ds/put! s :c))))))


(deftest next-sentinels-test
  (testing ":blocked on empty open stream"
    (let [s (make-stream)] (is (= :blocked (ds/next s {:position 0})))))
  (testing ":end on closed empty stream"
    (let [s (make-stream)]
      (ds/close! s)
      (is (= :end (ds/next s {:position 0}))))))


(deftest take-sentinels-test
  (testing ":empty on empty open stream"
    (let [s (make-stream)] (is (= :empty (ds/take! s)))))
  (testing ":end on closed empty stream"
    (let [s (make-stream)]
      (ds/close! s)
      (is (= :end (ds/take! s))))))


;; =============================================================================
;; Channel Mobility Tests (streams as values sent through streams)
;; =============================================================================

(deftest stream-channel-mobility-test
  (testing "A stream sent through another stream arrives intact"
    (let [s1 (make-stream)
          s2 (make-stream)
          _ (ds/put! s2 :payload)
          _ (ds/put! s1 s2)
          r1 (ds/next s1 {:position 0})
          recovered-s2 (:ok r1)]
      (is (some? recovered-s2) "Recovered value should be a stream")
      (is (= 1 (ds/length recovered-s2)) "Recovered stream should have 1 value")
      (let [r2 (ds/next recovered-s2 {:position 0})]
        (is (= :payload (:ok r2))
            "Reading from recovered stream yields the original value")))))


(deftest stream-descriptor-through-stream-test
  (testing "A descriptor map sent through a stream arrives intact"
    (let [s1 (make-stream)
          descriptor {:capacity 5, :closed false}
          _ (ds/put! s1 descriptor)
          r1 (ds/next s1 {:position 0})]
      (is (= descriptor (:ok r1))
          "Descriptor passes through a stream unchanged"))))
