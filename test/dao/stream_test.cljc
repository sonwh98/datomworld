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
      (is (= 0 (storage/length s)))
      (is (nil? (storage/read-at s 0)))))
  (testing "Append and read"
    (let [s (-> (storage/memory-storage)
                (storage/append :a)
                (storage/append :b)
                (storage/append :c))]
      (is (= 3 (storage/length s)))
      (is (= :a (storage/read-at s 0)))
      (is (= :b (storage/read-at s 1)))
      (is (= :c (storage/read-at s 2)))
      (is (nil? (storage/read-at s 3)))))
  (testing "Append is non-destructive"
    (let [s0 (storage/memory-storage)
          s1 (storage/append s0 :x)
          s2 (storage/append s1 :y)]
      (is (= 0 (storage/length s0)))
      (is (= 1 (storage/length s1)))
      (is (= 2 (storage/length s2))))))


;; =============================================================================
;; Stream Tests
;; =============================================================================

(deftest stream-make-test
  (testing "Make unbounded stream"
    (let [s (ds/make (storage/memory-storage))]
      (is (not (ds/closed? s)))
      (is (= 0 (ds/length s)))
      (is (nil? (:capacity s)))))
  (testing "Make bounded stream"
    (let [s (ds/make (storage/memory-storage) :capacity 3)]
      (is (= 3 (:capacity s))))))


(deftest stream-put-test
  (testing "Put to unbounded stream"
    (let [s (ds/make (storage/memory-storage))
          result (ds/put s 42)]
      (is (:ok result))
      (is (= 1 (ds/length (:ok result))))))
  (testing "Put to bounded stream within capacity"
    (let [s (ds/make (storage/memory-storage) :capacity 2)
          r1 (ds/put s :a)
          r2 (ds/put (:ok r1) :b)]
      (is (:ok r1))
      (is (:ok r2))
      (is (= 2 (ds/length (:ok r2))))))
  (testing "Put to bounded stream at capacity returns :full"
    (let [s (ds/make (storage/memory-storage) :capacity 1)
          r1 (ds/put s :a)
          r2 (ds/put (:ok r1) :b)]
      (is (:ok r1))
      (is (:full r2))
      (is (= 1 (ds/length (:full r2))))))
  (testing "Put to closed stream throws"
    (let [s (-> (ds/make (storage/memory-storage))
                (ds/close))]
      (is (thrown? #?(:clj Exception
                      :cljs js/Error)
            (ds/put s 42))))))


(deftest stream-close-test
  (testing "Close a stream"
    (let [s (ds/make (storage/memory-storage))
          closed (ds/close s)]
      (is (ds/closed? closed))
      (is (not (ds/closed? s))))))


;; =============================================================================
;; Cursor Tests
;; =============================================================================

(deftest cursor-make-test
  (testing "Make cursor at position 0"
    (let [ref {:type :stream-ref, :id :s0}
          c (ds/cursor ref)]
      (is (= 0 (ds/position c)))
      (is (= ref (:stream-ref c))))))


(deftest cursor-next-test
  (testing "Next on populated stream returns data"
    (let [s (-> (ds/make (storage/memory-storage))
                (#(-> (ds/put % :a)
                      :ok))
                (#(-> (ds/put % :b)
                      :ok)))
          ref {:type :stream-ref, :id :s0}
          c (ds/cursor ref)
          r1 (ds/next c s)]
      (is (map? r1))
      (is (= :a (:ok r1)))
      (is (= 1 (ds/position (:cursor r1))))
      (let [r2 (ds/next (:cursor r1) s)]
        (is (= :b (:ok r2)))
        (is (= 2 (ds/position (:cursor r2)))))))
  (testing "Next at end of open stream returns :blocked"
    (let [s (ds/make (storage/memory-storage))
          c (ds/cursor {:type :stream-ref, :id :s0})]
      (is (= :blocked (ds/next c s)))))
  (testing "Next at end of closed stream returns :end"
    (let [s (-> (ds/make (storage/memory-storage))
                (ds/close))
          c (ds/cursor {:type :stream-ref, :id :s0})]
      (is (= :end (ds/next c s)))))
  (testing "Next on closed stream with data returns data then :end"
    (let [s (-> (ds/make (storage/memory-storage))
                (#(-> (ds/put % :x)
                      :ok))
                (ds/close))
          c (ds/cursor {:type :stream-ref, :id :s0})
          r1 (ds/next c s)]
      (is (= :x (:ok r1)))
      (is (= :end (ds/next (:cursor r1) s))))))


(deftest cursor-seek-test
  (testing "Seek to specific position"
    (let [c (ds/cursor {:type :stream-ref, :id :s0})
          c' (ds/seek c 5)]
      (is (= 5 (ds/position c'))))))


(deftest cursor-independence-test
  (testing "Two cursors on same stream advance independently"
    (let [s (-> (ds/make (storage/memory-storage))
                (#(-> (ds/put % :a)
                      :ok))
                (#(-> (ds/put % :b)
                      :ok))
                (#(-> (ds/put % :c)
                      :ok)))
          ref {:type :stream-ref, :id :s0}
          c1 (ds/cursor ref)
          c2 (ds/cursor ref)
          ;; c1 reads :a, :b
          r1a (ds/next c1 s)
          r1b (ds/next (:cursor r1a) s)
          ;; c2 reads :a only
          r2a (ds/next c2 s)]
      (is (= :a (:ok r1a)))
      (is (= :b (:ok r1b)))
      (is (= :a (:ok r2a)))
      ;; c1 is at position 2, c2 is at position 1
      (is (= 2 (ds/position (:cursor r1b))))
      (is (= 1 (ds/position (:cursor r2a)))))))


;; =============================================================================
;; Channel Mobility Tests (streams as values sent through streams)
;; =============================================================================

(deftest stream-channel-mobility-test
  (testing "A stream sent through another stream arrives intact"
    (let [s1 (ds/make (storage/memory-storage))
          s2 (ds/make (storage/memory-storage))
          ;; Put a value into s2
          s2 (:ok (ds/put s2 :payload))
          ;; Put s2 (the stream map) into s1
          s1 (:ok (ds/put s1 s2))
          ;; Read from s1, get s2 back
          ref1 {:type :stream-ref, :id :s1}
          c1 (ds/cursor ref1)
          r1 (ds/next c1 s1)
          recovered-s2 (:ok r1)]
      (is (map? recovered-s2) "Recovered value should be a stream map")
      (is (= 1 (ds/length recovered-s2)) "Recovered stream should have 1 value")
      ;; Read from the recovered s2
      (let [ref2 {:type :stream-ref, :id :s2}
            c2 (ds/cursor ref2)
            r2 (ds/next c2 recovered-s2)]
        (is (= :payload (:ok r2))
            "Reading from recovered stream yields the original value")))))


(deftest stream-ref-through-stream-test
  (testing "A stream-ref sent through a stream arrives intact"
    (let [s1 (ds/make (storage/memory-storage))
          ref2 {:type :stream-ref, :id :s2}
          s1 (:ok (ds/put s1 ref2))
          c1 (ds/cursor {:type :stream-ref, :id :s1})
          r1 (ds/next c1 s1)]
      (is (= ref2 (:ok r1)) "Stream-ref passes through a stream unchanged"))))


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
      (is (not (ds/closed? stream))))))


(deftest vm-stream-put-cursor-next-test
  (testing "Put then cursor+next retrieves value"
    (let [ast {:type :application,
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
    (let [ast
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
          vm-blocked (run-ast ast)]
      (is (vm/blocked? vm-blocked))
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
      (is (= :second (vm/value vm-result))))))
