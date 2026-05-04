(ns dao.runtime-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.runtime :as rt]
    [dao.stream :as ds]
    [dao.stream.ringbuffer :as rb]))


(deftest basic-scheduling-test
  (testing "reader on empty stream parks and is resumed after later put"
    (let [seen (atom [])
          stream (ds/open! {:type :ringbuffer, :capacity 10})
          task {:resume (fn [rt _entry value] (swap! seen conj value) rt)}
          rt0 (rt/initial-state)
          ;; Attempt read -> should block
          {:keys [state result]} (rt/handle-read rt0 stream {:position 0} task)
          _ (is (= :blocked result))
          _ (is (empty? @seen))
          ;; Write to stream -> should wake task
          {:keys [state]} (rt/handle-write state stream :hello)
          ;; Run loop -> task should be resumed
          rt-final (rt/run-loop state)]
      (is (= [:hello] @seen))
      (is (empty? (:ready-queue rt-final)))
      (is (empty? (:wait-set rt-final)))))
  (testing "writer on full stream parks and is resumed after space is freed"
    (let [resumed-vals (atom [])
          stream (ds/open! {:type :ringbuffer, :capacity 1})
          _ (ds/put! stream :first)
          task {:resume
                (fn [rt _entry value] (swap! resumed-vals conj value) rt)}
          rt0 (rt/initial-state)
          ;; Attempt write to full stream -> should block
          {:keys [state result]} (rt/handle-write rt0 stream :second task)
          _ (is (= :full result))
          _ (is (empty? @resumed-vals))
          ;; Drain one -> should wake writer
          {:keys [state]} (rt/handle-take state stream nil)
          ;; Run loop -> task should be resumed
          rt-final (rt/run-loop state)]
      (is (= [:second] @resumed-vals))
      (is (empty? (:ready-queue rt-final)))
      (is (empty? (:wait-set rt-final)))))
  (testing "taker on empty stream parks and is resumed after later write"
    (let [seen (atom [])
          stream (ds/open! {:type :ringbuffer, :capacity 10})
          task {:resume (fn [rt _entry value] (swap! seen conj value) rt)}
          rt0 (rt/initial-state)
          ;; Attempt take -> should block
          {:keys [state result]} (rt/handle-take rt0 stream task)
          _ (is (= :empty result))
          ;; Write to stream -> should wake task
          {:keys [state]} (rt/handle-write state stream :val)
          ;; Run loop
          rt-final (rt/run-loop state)]
      (is (= [:val] @seen))
      (is (empty? (:ready-queue rt-final)))
      (is (empty? (:wait-set rt-final)))))
  (testing "close wakes parked reader with nil"
    (let [seen (atom [])
          stream (ds/open! {:type :ringbuffer, :capacity 10})
          task {:resume (fn [rt _entry value] (swap! seen conj value) rt)}
          rt0 (rt/initial-state)
          ;; Attempt read -> should block
          {:keys [state]} (rt/handle-read rt0 stream {:position 0} task)
          ;; Close stream -> should wake task with nil
          {:keys [state]} (rt/handle-close state stream)
          ;; Run loop
          _ (rt/run-loop state)]
      (is (= [nil] @seen))))
  (testing "close wakes parked writer with nil"
    (let [seen (atom [])
          stream (ds/open! {:type :ringbuffer, :capacity 1})
          _ (ds/put! stream :full)
          task {:resume (fn [rt _entry value] (swap! seen conj value) rt)}
          rt0 (rt/initial-state)
          ;; Attempt write -> should block
          {:keys [state]} (rt/handle-write rt0 stream :waiting task)
          ;; Close stream -> should wake task with nil
          {:keys [state]} (rt/handle-close state stream)
          ;; Run loop
          _ (rt/run-loop state)]
      (is (= [nil] @seen)))))


(deftest check-wait-set-closed-taker-does-not-corrupt-ready-queue-test
  (testing
    "closed takers wake without copying existing ready entries into the wait-set"
    (let [stream (ds/open! {:type :ringbuffer, :capacity 10})
          parked-task {:resume (fn [rt _entry value]
                                 (assoc rt :parked-value value))}
          existing-ready {:resume (fn [rt _entry _value] rt),
                          :value :already-ready}
          rt0 (assoc (rt/initial-state) :ready-queue [existing-ready])
          {:keys [state result]} (rt/handle-take rt0 stream parked-task)
          _ (is (= :empty result))
          _ (ds/close! stream)
          checked (rt/check-wait-set state)]
      (is (empty? (:wait-set checked)))
      (is (= 2 (count (:ready-queue checked))))
      (is (= :already-ready (:value (first (:ready-queue checked)))))
      (is (nil? (:value (second (:ready-queue checked))))))))
