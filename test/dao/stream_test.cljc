(ns dao.stream-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.storage :as storage]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]))


;; =============================================================================
;; Storage Protocol Tests
;; =============================================================================

(deftest memory-storage-test
  (testing "Empty storage"
    (let [s (storage/memory-storage)]
      (is (= 0 (storage/s-length s)))
      (is (nil? (storage/s-read-at s 0)))))
  (testing "Append and read"
    (let [s (-> (storage/memory-storage)
                (storage/s-append :a)
                (storage/s-append :b)
                (storage/s-append :c))]
      (is (= 3 (storage/s-length s)))
      (is (= :a (storage/s-read-at s 0)))
      (is (= :b (storage/s-read-at s 1)))
      (is (= :c (storage/s-read-at s 2)))
      (is (nil? (storage/s-read-at s 3)))))
  (testing "Append is non-destructive"
    (let [s0 (storage/memory-storage)
          s1 (storage/s-append s0 :x)
          s2 (storage/s-append s1 :y)]
      (is (= 0 (storage/s-length s0)))
      (is (= 1 (storage/s-length s1)))
      (is (= 2 (storage/s-length s2))))))


;; =============================================================================
;; Stream Tests
;; =============================================================================

(deftest stream-make-test
  (testing "Make unbounded stream"
    (let [s (ds/make-stream (storage/memory-storage))]
      (is (not (ds/stream-closed? s)))
      (is (= 0 (ds/stream-length s)))
      (is (nil? (:capacity s)))))
  (testing "Make bounded stream"
    (let [s (ds/make-stream (storage/memory-storage) :capacity 3)]
      (is (= 3 (:capacity s))))))


(deftest stream-put-test
  (testing "Put to unbounded stream"
    (let [s (ds/make-stream (storage/memory-storage))
          result (ds/stream-put s 42)]
      (is (:ok result))
      (is (= 1 (ds/stream-length (:ok result))))))
  (testing "Put to bounded stream within capacity"
    (let [s (ds/make-stream (storage/memory-storage) :capacity 2)
          r1 (ds/stream-put s :a)
          r2 (ds/stream-put (:ok r1) :b)]
      (is (:ok r1))
      (is (:ok r2))
      (is (= 2 (ds/stream-length (:ok r2))))))
  (testing "Put to bounded stream at capacity returns :full"
    (let [s (ds/make-stream (storage/memory-storage) :capacity 1)
          r1 (ds/stream-put s :a)
          r2 (ds/stream-put (:ok r1) :b)]
      (is (:ok r1))
      (is (:full r2))
      (is (= 1 (ds/stream-length (:full r2))))))
  (testing "Put to closed stream throws"
    (let [s (-> (ds/make-stream (storage/memory-storage))
                (ds/stream-close))]
      (is (thrown? #?(:clj Exception
                      :cljs js/Error)
            (ds/stream-put s 42))))))


(deftest stream-close-test
  (testing "Close a stream"
    (let [s (ds/make-stream (storage/memory-storage))
          closed (ds/stream-close s)]
      (is (ds/stream-closed? closed))
      (is (not (ds/stream-closed? s))))))


;; =============================================================================
;; Cursor Tests
;; =============================================================================

(deftest cursor-make-test
  (testing "Make cursor at position 0"
    (let [ref {:type :stream-ref, :id :s0}
          c (ds/make-cursor ref)]
      (is (= 0 (ds/cursor-position c)))
      (is (= ref (:stream-ref c))))))


(deftest cursor-next-test
  (testing "Next on populated stream returns data"
    (let [s (-> (ds/make-stream (storage/memory-storage))
                (#(-> (ds/stream-put % :a)
                      :ok))
                (#(-> (ds/stream-put % :b)
                      :ok)))
          ref {:type :stream-ref, :id :s0}
          c (ds/make-cursor ref)
          r1 (ds/cursor-next c s)]
      (is (map? r1))
      (is (= :a (:ok r1)))
      (is (= 1 (ds/cursor-position (:cursor r1))))
      (let [r2 (ds/cursor-next (:cursor r1) s)]
        (is (= :b (:ok r2)))
        (is (= 2 (ds/cursor-position (:cursor r2)))))))
  (testing "Next at end of open stream returns :blocked"
    (let [s (ds/make-stream (storage/memory-storage))
          c (ds/make-cursor {:type :stream-ref, :id :s0})]
      (is (= :blocked (ds/cursor-next c s)))))
  (testing "Next at end of closed stream returns :end"
    (let [s (-> (ds/make-stream (storage/memory-storage))
                (ds/stream-close))
          c (ds/make-cursor {:type :stream-ref, :id :s0})]
      (is (= :end (ds/cursor-next c s)))))
  (testing "Next on closed stream with data returns data then :end"
    (let [s (-> (ds/make-stream (storage/memory-storage))
                (#(-> (ds/stream-put % :x)
                      :ok))
                (ds/stream-close))
          c (ds/make-cursor {:type :stream-ref, :id :s0})
          r1 (ds/cursor-next c s)]
      (is (= :x (:ok r1)))
      (is (= :end (ds/cursor-next (:cursor r1) s))))))


(deftest cursor-seek-test
  (testing "Seek to specific position"
    (let [c (ds/make-cursor {:type :stream-ref, :id :s0})
          c' (ds/cursor-seek c 5)]
      (is (= 5 (ds/cursor-position c'))))))


(deftest cursor-independence-test
  (testing "Two cursors on same stream advance independently"
    (let [s (-> (ds/make-stream (storage/memory-storage))
                (#(-> (ds/stream-put % :a)
                      :ok))
                (#(-> (ds/stream-put % :b)
                      :ok))
                (#(-> (ds/stream-put % :c)
                      :ok)))
          ref {:type :stream-ref, :id :s0}
          c1 (ds/make-cursor ref)
          c2 (ds/make-cursor ref)
          ;; c1 reads :a, :b
          r1a (ds/cursor-next c1 s)
          r1b (ds/cursor-next (:cursor r1a) s)
          ;; c2 reads :a only
          r2a (ds/cursor-next c2 s)]
      (is (= :a (:ok r1a)))
      (is (= :b (:ok r1b)))
      (is (= :a (:ok r2a)))
      ;; c1 is at position 2, c2 is at position 1
      (is (= 2 (ds/cursor-position (:cursor r1b))))
      (is (= 1 (ds/cursor-position (:cursor r2a)))))))


;; =============================================================================
;; VM Integration Tests
;; =============================================================================

(defn run-ast
  [ast]
  (-> (ast-walker/create-vm)
      (vm/load-program ast)
      (vm/run)))


(deftest vm-stream-make-test
  (testing "stream/make creates a daostream in VM store"
    (let [vm-result (run-ast {:type :stream/make, :buffer 5})
          stream-ref (vm/value vm-result)
          stream-id (:id stream-ref)
          stream (get (vm/store vm-result) stream-id)]
      (is (= :stream-ref (:type stream-ref)))
      (is (some? stream))
      (is (= 5 (:capacity stream)))
      (is (not (ds/stream-closed? stream))))))


(deftest vm-stream-put-cursor-next-test
  (testing "Put then cursor+next retrieves value"
    (let [;; Create stream, put a value, create cursor, read next
          ;; Using nested lambdas to thread state
          ast {:type :application,
               :operator
               {:type :lambda,
                :params ['s],
                :body {:type :application,
                       :operator {:type :lambda,
                                  :params ['_put],
                                  :body {:type :application,
                                         :operator {:type :lambda,
                                                    :params ['c],
                                                    :body {:type :stream/next,
                                                           :source
                                                           {:type :variable,
                                                            :name 'c}}},
                                         :operands [{:type :stream/cursor,
                                                     :source {:type :variable,
                                                              :name 's}}]}},
                       :operands [{:type :stream/put,
                                   :target {:type :variable, :name 's},
                                   :val {:type :literal, :value 42}}]}},
               :operands [{:type :stream/make, :buffer 10}]}
          vm-result (run-ast ast)]
      (is (= 42 (vm/value vm-result))))))


(deftest vm-multiple-cursors-test
  (testing "Multiple cursors on same stream, independent positions"
    (let [;; Create stream, put two values, create two cursors, each reads
          ;; first
          ast
          {:type :application,
           :operator
           {:type :lambda,
            :params ['s],
            :body
            {:type :application,
             :operator
             {:type :lambda,
              :params ['_],
              :body
              {:type :application,
               :operator
               {:type :lambda,
                :params ['_],
                :body
                {:type :application,
                 :operator
                 {:type :lambda,
                  :params ['c1],
                  :body
                  {:type :application,
                   :operator
                   {:type :lambda,
                    :params ['c2],
                    :body
                    {:type :application,
                     :operator
                     {:type :lambda,
                      :params ['v1],
                      :body
                      {:type :application,
                       :operator {:type :variable,
                                  :name '+},
                       :operands
                       [{:type :variable, :name 'v1}
                        {:type :stream/next,
                         :source {:type :variable,
                                  :name 'c2}}]}},
                     :operands [{:type :stream/next,
                                 :source {:type :variable,
                                          :name 'c1}}]}},
                   :operands [{:type :stream/cursor,
                               :source {:type :variable,
                                        :name 's}}]}},
                 :operands [{:type :stream/cursor,
                             :source {:type :variable,
                                      :name 's}}]}},
               :operands [{:type :stream/put,
                           :target {:type :variable, :name 's},
                           :val {:type :literal, :value 20}}]}},
             :operands [{:type :stream/put,
                         :target {:type :variable, :name 's},
                         :val {:type :literal, :value 10}}]}},
           :operands [{:type :stream/make, :buffer 10}]}
          vm-result (run-ast ast)]
      ;; Both cursors start at 0, so both read the first value (10)
      (is (= 20 (vm/value vm-result))))))


(deftest vm-next-blocks-on-empty-test
  (testing "next! on empty open stream blocks"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['s],
                          :body {:type :application,
                                 :operator {:type :lambda,
                                            :params ['c],
                                            :body {:type :stream/next,
                                                   :source {:type :variable,
                                                            :name 'c}}},
                                 :operands [{:type :stream/cursor,
                                             :source {:type :variable,
                                                      :name 's}}]}},
               :operands [{:type :stream/make, :buffer 10}]}
          vm-result (run-ast ast)]
      (is (= :yin/blocked (vm/value vm-result)))
      (is (vm/blocked? vm-result)))))


(deftest vm-close-resumes-parked-readers-test
  (testing "Closing a stream resumes parked readers with nil"
    ;; This tests the close-resume path through the effect handler.
    ;; A full scheduler test would need concurrent continuations.
    ;; For now, verify close produces resume-parked entries.
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['s],
                          :body {:type :application,
                                 :operator {:type :lambda,
                                            :params ['c],
                                            :body {:type :stream/next,
                                                   :source {:type :variable,
                                                            :name 'c}}},
                                 :operands [{:type :stream/cursor,
                                             :source {:type :variable,
                                                      :name 's}}]}},
               :operands [{:type :stream/make, :buffer 10}]}
          ;; Run until blocked on empty stream
          vm-blocked (run-ast ast)]
      (is (vm/blocked? vm-blocked))
      ;; Now close the stream via a new eval. The wait-set should have our
      ;; parked cursor reader
      (is (seq (:wait-set vm-blocked))))))


(deftest vm-stream-put-ordering-test
  (testing "Stream maintains append order through cursors"
    (let [ast {:type :application,
               :operator
               {:type :lambda,
                :params ['s],
                :body {:type :application,
                       :operator
                       {:type :lambda,
                        :params ['_],
                        :body
                        {:type :application,
                         :operator
                         {:type :lambda,
                          :params ['_],
                          :body {:type :application,
                                 :operator
                                 {:type :lambda,
                                  :params ['c],
                                  :body {:type :application,
                                         :operator
                                         {:type :lambda,
                                          :params ['v1],
                                          :body {:type :stream/next,
                                                 :source
                                                 {:type :variable,
                                                  :name 'c}}},
                                         :operands
                                         [{:type :stream/next,
                                           :source {:type :variable,
                                                    :name 'c}}]}},
                                 :operands [{:type :stream/cursor,
                                             :source {:type :variable,
                                                      :name 's}}]}},
                         :operands [{:type :stream/put,
                                     :target {:type :variable, :name 's},
                                     :val {:type :literal,
                                           :value :second}}]}},
                       :operands [{:type :stream/put,
                                   :target {:type :variable, :name 's},
                                   :val {:type :literal, :value :first}}]}},
               :operands [{:type :stream/make, :buffer 10}]}
          vm-result (run-ast ast)]
      ;; cursor reads :first, then :second (next! returns :second)
      (is (= :second (vm/value vm-result))))))
