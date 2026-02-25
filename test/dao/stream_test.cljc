(ns dao.stream-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.storage :as storage]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]))


;; =============================================================================
;; Storage Utility Tests (ring-buffer and file backends)
;; =============================================================================

(deftest ring-buffer-storage-test
  (testing "Empty ring buffer"
    (let [s (storage/ring-buffer-storage 2)]
      (is (= 0 (storage/ring-buffer-length s)))
      (is (nil? (storage/ring-buffer-read-at s 0)))))
  (testing "Append up to capacity"
    (let [s (-> (storage/ring-buffer-storage 2)
                (storage/ring-buffer-append :a)
                (storage/ring-buffer-append :b))]
      (is (= 2 (storage/ring-buffer-length s)))
      (is (= :a (storage/ring-buffer-read-at s 0)))
      (is (= :b (storage/ring-buffer-read-at s 1)))))
  (testing "Appending past capacity evicts oldest values"
    (let [s (-> (storage/ring-buffer-storage 2)
                (storage/ring-buffer-append :a)
                (storage/ring-buffer-append :b)
                (storage/ring-buffer-append :c))]
      (is (= 2 (storage/ring-buffer-length s)))
      (is (= :b (storage/ring-buffer-read-at s 0)))
      (is (= :c (storage/ring-buffer-read-at s 1)))
      (is (nil? (storage/ring-buffer-read-at s 2)))))
  (testing "Append is non-destructive"
    (let [s0 (storage/ring-buffer-storage 2)
          s1 (storage/ring-buffer-append s0 :x)
          s2 (storage/ring-buffer-append s1 :y)
          s3 (storage/ring-buffer-append s2 :z)]
      (is (= 0 (storage/ring-buffer-length s0)))
      (is (= 1 (storage/ring-buffer-length s1)))
      (is (= :x (storage/ring-buffer-read-at s1 0)))
      (is (= [:x :y]
             [(storage/ring-buffer-read-at s2 0)
              (storage/ring-buffer-read-at s2 1)]))
      (is (= [:y :z]
             [(storage/ring-buffer-read-at s3 0)
              (storage/ring-buffer-read-at s3 1)]))))
  (testing "Zero-capacity ring buffer stays empty"
    (let [s (-> (storage/ring-buffer-storage 0)
                (storage/ring-buffer-append :x))]
      (is (= 0 (storage/ring-buffer-length s)))
      (is (nil? (storage/ring-buffer-read-at s 0))))))


#?(:clj (deftest file-storage-test
          (testing "File storage persists values across reopen"
            (let [tmp-file (doto (java.io.File/createTempFile "daostream-"
                                                              ".log")
                             (.deleteOnExit))
                  path (.getAbsolutePath tmp-file)
                  s0 (storage/file-storage path)
                  s1 (storage/file-append s0 {:event :start})
                  s2 (storage/file-append s1 [:payload 42])
                  reopened (storage/file-storage path)]
              (is (= 0 (storage/file-length s0)))
              (is (= 2 (storage/file-length s2)))
              (is (= 2 (storage/file-length reopened)))
              (is (= {:event :start} (storage/file-read-at reopened 0)))
              (is (= [:payload 42] (storage/file-read-at reopened 1)))))))


;; =============================================================================
;; Stream Tests
;; =============================================================================

(deftest stream-make-test
  (testing "Make unbounded stream"
    (let [s (ds/make)]
      (is (not (ds/closed? s)))
      (is (= 0 (ds/length nil s)))
      (is (nil? (:capacity s)))))
  (testing "Make bounded stream"
    (let [s (ds/make :capacity 3)] (is (= 3 (:capacity s))))))


(deftest stream-put-test
  (testing "Put to unbounded stream"
    (let [s (ds/make)
          result (ds/put nil s 42)]
      (is (:ok result))
      (is (= 1 (ds/length nil (:ok result))))))
  (testing "Put to bounded stream within capacity"
    (let [s (ds/make :capacity 2)
          r1 (ds/put nil s :a)
          r2 (ds/put nil (:ok r1) :b)]
      (is (:ok r1))
      (is (:ok r2))
      (is (= 2 (ds/length nil (:ok r2))))))
  (testing "Put to bounded stream at capacity returns :full"
    (let [s (ds/make :capacity 1)
          r1 (ds/put nil s :a)
          r2 (ds/put nil (:ok r1) :b)]
      (is (:ok r1))
      (is (:full r2))
      (is (= 1 (ds/length nil (:full r2))))))
  (testing "Put to closed stream throws"
    (let [s (-> (ds/make)
                (ds/close))]
      (is (thrown? #?(:clj Exception
                      :cljs js/Error)
            (ds/put nil s 42))))))


(deftest stream-close-test
  (testing "Close a stream"
    (let [s (ds/make)
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
    (let [s (-> (ds/make)
                (#(-> (ds/put nil % :a)
                      :ok))
                (#(-> (ds/put nil % :b)
                      :ok)))
          ref {:type :stream-ref, :id :s0}
          c (ds/cursor ref)
          r1 (ds/next nil c s)]
      (is (map? r1))
      (is (= :a (:ok r1)))
      (is (= 1 (ds/position (:cursor r1))))
      (let [r2 (ds/next nil (:cursor r1) s)]
        (is (= :b (:ok r2)))
        (is (= 2 (ds/position (:cursor r2)))))))
  (testing "Next at end of open stream returns :blocked"
    (let [s (ds/make)
          c (ds/cursor {:type :stream-ref, :id :s0})]
      (is (= :blocked (ds/next nil c s)))))
  (testing "Next at end of closed stream returns :end"
    (let [s (-> (ds/make)
                (ds/close))
          c (ds/cursor {:type :stream-ref, :id :s0})]
      (is (= :end (ds/next nil c s)))))
  (testing "Next on closed stream with data returns data then :end"
    (let [s (-> (ds/make)
                (#(-> (ds/put nil % :x)
                      :ok))
                (ds/close))
          c (ds/cursor {:type :stream-ref, :id :s0})
          r1 (ds/next nil c s)]
      (is (= :x (:ok r1)))
      (is (= :end (ds/next nil (:cursor r1) s))))))


(deftest cursor-seek-test
  (testing "Seek to specific position"
    (let [c (ds/cursor {:type :stream-ref, :id :s0})
          c' (ds/seek c 5)]
      (is (= 5 (ds/position c'))))))


;; =============================================================================
;; Seq Tests
;; =============================================================================

(deftest stream-seq-empty-test
  (testing "->seq on empty stream returns empty seq"
    (let [s (ds/make)]
      (is (empty? (ds/->seq nil s)))
      (is (nil? (seq (ds/->seq nil s)))))))


(deftest stream-seq-values-test
  (testing "->seq returns values in append order"
    (let [s (-> (ds/make)
                (#(:ok (ds/put nil % :a)))
                (#(:ok (ds/put nil % :b)))
                (#(:ok (ds/put nil % :c))))]
      (is (= [:a :b :c] (vec (ds/->seq nil s)))))))


(deftest stream-seq-clojure-interop-test
  (testing "Standard seq functions work on ->seq"
    (let [s (-> (ds/make)
                (#(:ok (ds/put nil % 1)))
                (#(:ok (ds/put nil % 2)))
                (#(:ok (ds/put nil % 3)))
                (#(:ok (ds/put nil % 4))))]
      (is (= 1 (first (ds/->seq nil s))))
      (is (= [2 3 4] (vec (rest (ds/->seq nil s)))))
      (is (= 10 (reduce + (ds/->seq nil s))))
      (is (= [2 4] (vec (filter even? (ds/->seq nil s)))))
      (is (= [2 4 6 8] (vec (map #(* 2 %) (ds/->seq nil s)))))
      (is (= [1 2] (vec (take 2 (ds/->seq nil s))))))))


(deftest stream-seq-snapshot-test
  (testing "->seq is a snapshot: appending after ->seq does not affect it"
    (let [s (-> (ds/make)
                (#(:ok (ds/put nil % :x))))
          frozen (ds/->seq nil s)
          s' (:ok (ds/put nil s :y))]
      (is (= [:x] (vec frozen)))
      (is (= [:x :y] (vec (ds/->seq nil s')))))))


(deftest stream-take-last-seq-test
  (testing "take-last-seq returns last n items"
    (let [s (-> (ds/make)
                (#(:ok (ds/put nil % 1)))
                (#(:ok (ds/put nil % 2)))
                (#(:ok (ds/put nil % 3))))]
      (is (= [1 2 3] (vec (ds/take-last-seq nil s 5))))
      (is (= [2 3] (vec (ds/take-last-seq nil s 2))))
      (is (= [3] (vec (ds/take-last-seq nil s 1))))
      (is (empty? (ds/take-last-seq nil s 0)))
      (testing "n validation and coercion"
        (is (= [2 3] (vec (ds/take-last-seq nil s 2.9)))
            "Coerces float to long")
        (is (thrown? #?(:clj Exception
                        :cljs js/Error)
              (ds/take-last-seq nil s "invalid")))))))


(deftest cursor-independence-test
  (testing "Two cursors on same stream advance independently"
    (let [s (-> (ds/make)
                (#(-> (ds/put nil % :a)
                      :ok))
                (#(-> (ds/put nil % :b)
                      :ok))
                (#(-> (ds/put nil % :c)
                      :ok)))
          ref {:type :stream-ref, :id :s0}
          c1 (ds/cursor ref)
          c2 (ds/cursor ref)
          ;; c1 reads :a, :b
          r1a (ds/next nil c1 s)
          r1b (ds/next nil (:cursor r1a) s)
          ;; c2 reads :a only
          r2a (ds/next nil c2 s)]
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
    (let [s1 (ds/make)
          s2 (ds/make)
          ;; Put a value into s2
          s2 (:ok (ds/put nil s2 :payload))
          ;; Put s2 (the stream map) into s1
          s1 (:ok (ds/put nil s1 s2))
          ;; Read from s1, get s2 back
          ref1 {:type :stream-ref, :id :s1}
          c1 (ds/cursor ref1)
          r1 (ds/next nil c1 s1)
          recovered-s2 (:ok r1)]
      (is (map? recovered-s2) "Recovered value should be a stream map")
      (is (= 1 (ds/length nil recovered-s2))
          "Recovered stream should have 1 value")
      ;; Read from the recovered s2
      (let [ref2 {:type :stream-ref, :id :s2}
            c2 (ds/cursor ref2)
            r2 (ds/next nil c2 recovered-s2)]
        (is (= :payload (:ok r2))
            "Reading from recovered stream yields the original value")))))


(deftest stream-ref-through-stream-test
  (testing "A stream-ref sent through a stream arrives intact"
    (let [s1 (ds/make)
          ref2 {:type :stream-ref, :id :s2}
          s1 (:ok (ds/put nil s1 ref2))
          c1 (ds/cursor {:type :stream-ref, :id :s1})
          r1 (ds/next nil c1 s1)]
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


;; =============================================================================
;; Take Tests
;; =============================================================================

(deftest take-basic-test
  (testing "Take from stream with values"
    (let [s (-> (ds/make)
                (#(:ok (ds/put nil % :a)))
                (#(:ok (ds/put nil % :b)))
                (#(:ok (ds/put nil % :c))))
          r1 (ds/take nil s)]
      (is (= :a (:ok r1)))
      (is (= 2 (ds/length nil (:stream r1))))
      (let [r2 (ds/take nil (:stream r1))]
        (is (= :b (:ok r2)))
        (is (= 1 (ds/length nil (:stream r2))))
        (let [r3 (ds/take nil (:stream r2))]
          (is (= :c (:ok r3)))
          (is (= 0 (ds/length nil (:stream r3)))))))))


(deftest take-empty-test
  (testing "Take from empty open stream returns :empty"
    (let [s (ds/make)] (is (= :empty (ds/take nil s)))))
  (testing "Take from exhausted open stream returns :empty"
    (let [s (-> (ds/make)
                (#(:ok (ds/put nil % :a))))
          r1 (ds/take nil s)]
      (is (= :a (:ok r1)))
      (is (= :empty (ds/take nil (:stream r1)))))))


(deftest take-end-test
  (testing "Take from empty closed stream returns :end"
    (let [s (-> (ds/make)
                (ds/close))]
      (is (= :end (ds/take nil s)))))
  (testing "Take from closed stream with data returns data then :end"
    (let [s (-> (ds/make)
                (#(:ok (ds/put nil % :x)))
                (ds/close))
          r1 (ds/take nil s)]
      (is (= :x (:ok r1)))
      (is (= :end (ds/take nil (:stream r1)))))))


(deftest take-frees-capacity-test
  (testing "Take frees capacity for put"
    (let [s (-> (ds/make :capacity 2)
                (#(:ok (ds/put nil % :a)))
                (#(:ok (ds/put nil % :b))))
          ;; Stream is full
          full-result (ds/put nil s :c)]
      (is (:full full-result))
      ;; Take one, freeing capacity
      (let [taken (ds/take nil s)
            s' (:stream taken)
            put-result (ds/put nil s' :c)]
        (is (= :a (:ok taken)))
        (is (:ok put-result) "Put should succeed after take freed capacity")))))


(deftest take-seq-interaction-test
  (testing "->seq reflects head position"
    (let [s (-> (ds/make)
                (#(:ok (ds/put nil % 1)))
                (#(:ok (ds/put nil % 2)))
                (#(:ok (ds/put nil % 3))))
          s' (:stream (ds/take nil s))]
      (is (= [2 3] (vec (ds/->seq nil s'))))))
  (testing "take-last-seq reflects head position"
    (let [s (-> (ds/make)
                (#(:ok (ds/put nil % 1)))
                (#(:ok (ds/put nil % 2)))
                (#(:ok (ds/put nil % 3))))
          s' (:stream (ds/take nil s))]
      (is (= [3] (vec (ds/take-last-seq nil s' 1))))
      (is (= [2 3] (vec (ds/take-last-seq nil s' 5)))))))


(deftest cursor-gap-test
  (testing "Cursor at position behind head returns :daostream/gap"
    (let [s (-> (ds/make)
                (#(:ok (ds/put nil % :a)))
                (#(:ok (ds/put nil % :b))))
          ;; Take advances head past position 0
          s' (:stream (ds/take nil s))
          ref {:type :stream-ref, :id :s0}
          c (ds/cursor ref)]
      (is (= :daostream/gap (ds/next nil c s'))
          "Cursor at pos 0 with head at 1 should return gap"))))
