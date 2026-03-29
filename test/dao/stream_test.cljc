(ns dao.stream-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]))


;; =============================================================================
;; Test helper
;; =============================================================================

(defn- make-stream
  ([] (ds/make-ring-buffer-stream nil))
  ([capacity]
   (ds/make-ring-buffer-stream capacity)))


;; =============================================================================
;; RingBufferStream Tests
;; =============================================================================

(deftest lazy-seq-stream-test
  (testing "Fresh RingBufferStream is open and empty"
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


(deftest ->seq-test
  (testing
    "->seq produces a lazy seq of available values without mutating the stream"
    (let [s (make-stream)
          _ (ds/put! s :alpha)
          _ (ds/put! s :beta)
          values (vec (ds/->seq nil s))]
      (is (= [:alpha :beta] values))
      (is (= 2 (ds/length s))
          "Calling next via ->seq must not consume values"))))


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


;; =============================================================================
;; Edge Case Tests
;; =============================================================================

(deftest nil-value-round-trip-test
  (testing "nil is a valid value for put!/take!"
    (let [s (make-stream)]
      (is (= :ok (ds/put! s nil)))
      (is (= {:ok nil} (ds/take! s)))))
  (testing "nil is a valid value for put!/next"
    (let [s (make-stream)]
      (ds/put! s nil)
      (let [r (ds/next s {:position 0})]
        (is (= {:ok nil, :cursor {:position 1}} r))))))


(deftest close-idempotent-test
  (testing "close! called twice does not throw and closed? stays true"
    (let [s (make-stream)]
      (ds/close! s)
      (ds/close! s)
      (is (true? (ds/closed? s))))))


(deftest take-on-closed-stream-with-data-test
  (testing "take! drains remaining data from a closed stream before returning :end"
    (let [s (make-stream)]
      (ds/put! s :x)
      (ds/put! s :y)
      (ds/close! s)
      (is (= {:ok :x} (ds/take! s)))
      (is (= {:ok :y} (ds/take! s)))
      (is (= :end (ds/take! s))))))


(deftest memory-reclamation-test
  (testing "take! removes consumed entries from the buffer map"
    (let [s (ds/make-ring-buffer-stream nil)]
      (ds/put! s :a)
      (ds/put! s :b)
      (ds/take! s)
      (let [state @(:state-atom s)]
        (is (not (contains? (:buffer state) 0)) "Entry at index 0 should be removed after take!")
        (is (contains? (:buffer state) 1) "Entry at index 1 should still be present")))))


(deftest zero-capacity-test
  (testing "capacity=0 rejects every put!"
    (let [s (ds/make-ring-buffer-stream 0)]
      (is (= :full (ds/put! s :a))))))


(deftest capacity-one-boundary-test
  (testing "capacity=1: full after one put!, freed after take!"
    (let [s (ds/make-ring-buffer-stream 1)]
      (is (= :ok (ds/put! s :a)))
      (is (= :full (ds/put! s :b)))
      (is (= {:ok :a} (ds/take! s)))
      (is (= :ok (ds/put! s :b)))
      (is (= {:ok :b} (ds/take! s))))))


(deftest put-take-cycle-index-continuity-test
  (testing "absolute indices advance monotonically across multiple put!/take! cycles"
    (let [s (ds/make-ring-buffer-stream nil)]
      (ds/put! s :a)
      (ds/take! s)
      (ds/put! s :b)
      (let [state @(:state-atom s)]
        (is (= 1 (:head state)) "head should be 1 after one take!")
        (is (= 2 (:tail state)) "tail should be 2 after two puts!"))
      (is (= {:ok :b} (ds/take! s))))))


(deftest next-beyond-tail-test
  (testing "next with position beyond tail returns :blocked on open stream"
    (let [s (make-stream)]
      (ds/put! s :a)
      (is (= :blocked (ds/next s {:position 99})))))
  (testing "next with position beyond tail returns :end on closed stream"
    (let [s (make-stream)]
      (ds/put! s :a)
      (ds/close! s)
      (is (= :end (ds/next s {:position 99}))))))


(deftest seq-stops-at-gap-test
  (testing "->seq starting at 0 returns empty when head > 0 (cursor behind head)"
    (let [s (make-stream)]
      (ds/put! s :a)
      (ds/put! s :b)
      (ds/take! s)
      ;; head is now 1; ->seq starts cursor at 0 which is a gap
      (is (= [] (vec (ds/->seq nil s)))))))


(deftest open-descriptor-capacity-test
  (testing "open! with :capacity propagates to make-ring-buffer-stream"
    (let [s (ds/open! {:transport {:type :ringbuffer :mode :create :capacity 3}})]
      (is (= :ok (ds/put! s 1)))
      (is (= :ok (ds/put! s 2)))
      (is (= :ok (ds/put! s 3)))
      (is (= :full (ds/put! s 4)))))
  (testing "open! with nil :capacity is unbounded"
    (let [s (ds/open! {:transport {:type :ringbuffer :mode :create :capacity nil}})]
      (dotimes [i 1000] (ds/put! s i))
      (is (= 1000 (ds/length s))))))
