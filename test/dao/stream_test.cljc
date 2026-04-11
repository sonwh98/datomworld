(ns dao.stream-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.transport.ringbuffer]))


;; =============================================================================
;; Test helper
;; =============================================================================

(defn- make-stream
  ([] (ds/open! {:transport {:type :ringbuffer, :capacity nil}}))
  ([capacity]
   (ds/open! {:transport {:type :ringbuffer, :capacity capacity}})))


(defn- ringbuffer-state-atom
  [stream]
  #?(:clj  (.-state-atom ^dao.stream.transport.ringbuffer.RingBufferStream stream)
     :cljs (.-state-atom ^dao.stream.transport.ringbuffer/RingBufferStream stream)
     :cljd (.-state-atom stream)))


;; =============================================================================
;; RingBufferStream Tests
;; =============================================================================

(deftest ring-buffer-stream-test
  (testing "Fresh RingBufferStream is open and empty"
    (let [s (make-stream)]
      (is (false? (ds/closed? s)))
      (is (= 0 (count s))))))


(deftest put-take-test
  (testing "put! / take! round trip with length tracking"
    (let [s (make-stream)]
      (is (= :ok (:result (ds/put! s :a))))
      (is (= :ok (:result (ds/put! s :b))))
      (is (= 2 (count s)))
      (is (= :a (:ok (ds/drain-one! s))))
      (is (= 1 (count s)))
      (is (= :b (:ok (ds/drain-one! s))))
      (is (= 0 (count s))))))


(deftest cursor-next-test
  (testing "next is non-destructive, cursor position advances"
    (let [s (make-stream)
          _ (ds/put! s :a)
          _ (ds/put! s :b)
          r1 (ds/next s {:position 0})]
      (is (= :a (:ok r1)))
      (is (= {:position 1} (:cursor r1)))
      (is (= 2 (count s)) "next does not consume")
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
                      :cljs js/Error
                      :cljd Object)
            (ds/put! s 42)))))
  (testing "take! returns :end on closed empty stream"
    (let [s (make-stream)]
      (ds/close! s)
      (is (= :end (ds/drain-one! s))))))


(deftest length-test
  (testing "length tracks puts and takes"
    (let [s (make-stream)]
      (is (= 0 (count s)))
      (ds/put! s :a)
      (is (= 1 (count s)))
      (ds/put! s :b)
      (is (= 2 (count s)))
      (ds/drain-one! s)
      (is (= 1 (count s)))
      (ds/drain-one! s)
      (is (= 0 (count s))))))


(deftest gap-test
  (testing "cursor behind head returns :daostream/gap after take!"
    (let [s (make-stream)]
      (ds/put! s :a)
      (ds/put! s :b)
      (ds/drain-one! s)
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
      (is (= 2 (count s))
          "Calling next via ->seq must not consume values"))))


(deftest capacity-test
  (testing "put! returns :full at capacity, :ok after take! frees space"
    (let [s (make-stream 2)]
      (is (= :ok (:result (ds/put! s :a))))
      (is (= :ok (:result (ds/put! s :b))))
      (is (= :full (:result (ds/put! s :c))))
      (ds/drain-one! s)
      (is (= :ok (:result (ds/put! s :c)))))))


(deftest next-sentinels-test
  (testing ":blocked on empty open stream"
    (let [s (make-stream)] (is (= :blocked (ds/next s {:position 0})))))
  (testing ":end on closed empty stream"
    (let [s (make-stream)]
      (ds/close! s)
      (is (= :end (ds/next s {:position 0}))))))


(deftest take-sentinels-test
  (testing ":empty on empty open stream"
    (let [s (make-stream)] (is (= :empty (ds/drain-one! s)))))
  (testing ":end on closed empty stream"
    (let [s (make-stream)]
      (ds/close! s)
      (is (= :end (ds/drain-one! s))))))


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
      (is (= 1 (count recovered-s2)) "Recovered stream should have 1 value")
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
      (is (= :ok (:result (ds/put! s nil))))
      (is (= nil (:ok (ds/drain-one! s))))))
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
      (is (= :x (:ok (ds/drain-one! s))))
      (is (= :y (:ok (ds/drain-one! s))))
      (is (= :end (ds/drain-one! s))))))


(deftest memory-reclamation-test
  (testing "take! removes consumed entries from the buffer map"
    (let [s (ds/open! {:transport {:type :ringbuffer, :capacity nil}})]
      (ds/put! s :a)
      (ds/put! s :b)
      (ds/drain-one! s)
      (let [state @(ringbuffer-state-atom s)]
        (is (not (contains? (:buffer state) 0)) "Entry at index 0 should be removed after take!")
        (is (contains? (:buffer state) 1) "Entry at index 1 should still be present")))))


(deftest zero-capacity-test
  (testing "capacity=0 rejects every put!"
    (let [s (ds/open! {:transport {:type :ringbuffer, :capacity 0}})]
      (is (= :full (:result (ds/put! s :a)))))))


(deftest capacity-one-boundary-test
  (testing "capacity=1: full after one put!, freed after take!"
    (let [s (ds/open! {:transport {:type :ringbuffer, :capacity 1}})]
      (is (= :ok (:result (ds/put! s :a))))
      (is (= :full (:result (ds/put! s :b))))
      (is (= :a (:ok (ds/drain-one! s))))
      (is (= :ok (:result (ds/put! s :b))))
      (is (= :b (:ok (ds/drain-one! s)))))))


(deftest put-take-cycle-index-continuity-test
  (testing "absolute indices advance monotonically across multiple put!/take! cycles"
    (let [s (ds/open! {:transport {:type :ringbuffer, :capacity nil}})]
      (ds/put! s :a)
      (ds/drain-one! s)
      (ds/put! s :b)
      (let [state @(ringbuffer-state-atom s)]
        (is (= 1 (:head state)) "head should be 1 after one take!")
        (is (= 2 (:tail state)) "tail should be 2 after two puts!"))
      (is (= :b (:ok (ds/drain-one! s)))))))


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
      (ds/drain-one! s)
      ;; head is now 1; ->seq starts cursor at 0 which is a gap
      (is (= [] (vec (ds/->seq nil s)))))))


(deftest open-descriptor-capacity-test
  (testing "open! with :capacity propagates to ringbuffer transport"
    (let [s (ds/open! {:transport {:type :ringbuffer :mode :create :capacity 3}})]
      (is (= :ok (:result (ds/put! s 1))))
      (is (= :ok (:result (ds/put! s 2))))
      (is (= :ok (:result (ds/put! s 3))))
      (is (= :full (:result (ds/put! s 4))))))
  (testing "open! with nil :capacity is unbounded"
    (let [s (ds/open! {:transport {:type :ringbuffer :mode :create :capacity nil}})]
      (dotimes [i 1000] (ds/put! s i))
      (is (= 1000 (count s))))))


;; =============================================================================
;; Writer Parking Tests (transport-local waking)
;; =============================================================================

(deftest writer-waiter-woken-by-drain-test
  (testing "drain-one! wakes a registered writer-waiter and writes its datom"
    (let [s (ds/open! {:transport {:type :ringbuffer, :capacity 1}})]
      ;; Fill the stream
      (is (= :ok (:result (ds/put! s :value1))))
      ;; Try to put another but it's full
      (is (= :full (:result (ds/put! s :value2))))
      ;; Register a writer-waiter with a datom
      (ds/register-writer-waiter! s {:reason :put, :datom :value2, :k {:type :test}})
      ;; Drain frees space
      (let [drain-result (ds/drain-one! s)]
        ;; Consumed value should be value1
        (is (= :value1 (:ok drain-result)))
        ;; Writer should be woken with its datom
        (is (= 1 (count (:woke drain-result))))
        (let [woken-entry (first (:woke drain-result))]
          (is (= :value2 (:value woken-entry)))
          (is (= {:type :test} (:k (:entry woken-entry))))))
      ;; Verify value2 is now in the stream
      (is (= :value2 (:ok (ds/drain-one! s)))))))


(deftest drain-one-no-writer-waiters-test
  (testing "drain-one! returns empty :woke when no writers are registered"
    (let [s (ds/open! {:transport {:type :ringbuffer, :capacity nil}})]
      (ds/put! s :x)
      (let [result (ds/drain-one! s)]
        (is (= :x (:ok result)))
        (is (= [] (:woke result)))))))


(deftest close-wakes-writer-waiters-test
  (testing "close! wakes both reader-waiters and writer-waiters with :value nil"
    (let [s (ds/open! {:transport {:type :ringbuffer, :capacity nil}})]
      ;; Register a reader-waiter
      (ds/register-reader-waiter! s 0 {:reason :next, :cursor-ref {:type :cursor-ref, :id :c1}})
      ;; Register a writer-waiter
      (ds/register-writer-waiter! s {:reason :put, :datom :val, :k {:type :write}})
      ;; Close the stream
      (let [close-result (ds/close! s)]
        ;; Should have 2 woken entries (1 reader, 1 writer)
        (is (= 2 (count (:woke close-result))))
        ;; All should have :value nil
        (doseq [entry (:woke close-result)]
          (is (= nil (:value entry))))))))


(deftest close-does-not-append-writer-datom-test
  (testing "close! resolves parked writers without letting drain-one! append them later"
    (let [s (ds/open! {:transport {:type :ringbuffer, :capacity 1}})]
      (is (= :ok (:result (ds/put! s :value1))))
      (ds/register-writer-waiter! s {:reason :put, :datom :value2, :k {:type :write}})
      (let [close-result (ds/close! s)
            woken (first (:woke close-result))]
        (is (= 1 (count (:woke close-result))))
        (is (nil? (:value woken))))
      (let [drain-result (ds/drain-one! s)]
        (is (= :value1 (:ok drain-result)))
        (is (= [] (:woke drain-result))
            "drain-one! should not wake or append a writer after close!"))
      (is (= :end (ds/next s {:position 1}))
          "no writer datom should appear after the original closed-stream value")
      (is (= :end (ds/drain-one! s))
          "closed stream should be drained after its original value"))))


(deftest clojure-core-count-test
  (testing "RingBufferStream supports clojure.core/count"
    (let [s (make-stream)]
      (is (= 0 (count s)) "Empty stream has count 0")
      (ds/put! s :a)
      (is (= 1 (count s)) "Stream with 1 item has count 1")
      (ds/put! s :b)
      (is (= 2 (count s)) "Stream with 2 items has count 2")
      (ds/drain-one! s)
      (is (= 1 (count s)) "After draining 1, count is 1")
      (ds/drain-one! s)
      (is (= 0 (count s)) "After draining all, count is 0"))))
