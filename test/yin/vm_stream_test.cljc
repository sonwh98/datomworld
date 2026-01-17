(ns yin.vm-stream-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.vm :as vm]
            [yin.test-util :refer [make-state]]))

(deftest test-stream-make
  (testing "stream/make creates a stream reference"
    (let [ast {:type :stream/make :buffer 10}
          result (vm/run (make-state {}) ast)]
      (is (= :stream-ref (:type (:value result)))
          "Should return a stream reference")
      (is (keyword? (:id (:value result)))
          "Stream reference should have a keyword id")))

  (testing "stream/make with default buffer"
    (let [ast {:type :stream/make}
          result (vm/run (make-state {}) ast)
          stream-id (:id (:value result))
          stream (get-in result [:store stream-id])]
      (is (= :stream (:type stream))
          "Store should contain stream")
      (is (= 1024 (:capacity stream))
          "Default buffer capacity should be 1024")
      (is (= [] (:buffer stream))
          "Buffer should start empty"))))

(deftest test-stream-put
  (testing "stream/put adds value to stream buffer"
    (let [;; First create a stream
          make-ast {:type :stream/make :buffer 5}
          state-with-stream (vm/run (make-state {}) make-ast)
          stream-ref (:value state-with-stream)
          stream-id (:id stream-ref)

          ;; Then put a value
          put-ast {:type :stream/put
                   :target {:type :literal :value stream-ref}
                   :val {:type :literal :value 42}}
          result (vm/run state-with-stream put-ast)
          stream (get-in result [:store stream-id])]

      (is (= 42 (:value result))
          "Put should return the value put")
      (is (= [42] (:buffer stream))
          "Stream buffer should contain the value")))

  (testing "stream/put multiple values"
    (let [make-ast {:type :stream/make :buffer 10}
          state-with-stream (vm/run (make-state {}) make-ast)
          stream-ref (:value state-with-stream)
          stream-id (:id stream-ref)

          ;; Put first value
          put1-ast {:type :stream/put
                    :target {:type :literal :value stream-ref}
                    :val {:type :literal :value 1}}
          state-after-put1 (vm/run state-with-stream put1-ast)

          ;; Put second value
          put2-ast {:type :stream/put
                    :target {:type :literal :value stream-ref}
                    :val {:type :literal :value 2}}
          state-after-put2 (vm/run state-after-put1 put2-ast)

          stream (get-in state-after-put2 [:store stream-id])]

      (is (= [1 2] (:buffer stream))
          "Stream buffer should contain both values in order"))))

(deftest test-stream-take
  (testing "stream/take retrieves value from stream buffer"
    (let [;; Create stream and put a value
          make-ast {:type :stream/make :buffer 5}
          state-with-stream (vm/run (make-state {}) make-ast)
          stream-ref (:value state-with-stream)
          stream-id (:id stream-ref)

          put-ast {:type :stream/put
                   :target {:type :literal :value stream-ref}
                   :val {:type :literal :value 99}}
          state-after-put (vm/run state-with-stream put-ast)

          ;; Take the value
          take-ast {:type :stream/take
                    :source {:type :literal :value stream-ref}}
          result (vm/run state-after-put take-ast)
          stream (get-in result [:store stream-id])]

      (is (= 99 (:value result))
          "Take should return the value from buffer")
      (is (= [] (:buffer stream))
          "Buffer should be empty after take")))

  (testing "stream/take from empty stream blocks"
    (let [make-ast {:type :stream/make :buffer 5}
          state-with-stream (vm/run (make-state {}) make-ast)
          stream-ref (:value state-with-stream)

          take-ast {:type :stream/take
                    :source {:type :literal :value stream-ref}}
          result (vm/run state-with-stream take-ast)]

      (is (= :yin/blocked (:value result))
          "Take from empty stream should block"))))

(deftest test-stream-fifo-ordering
  (testing "stream maintains FIFO order"
    (let [make-ast {:type :stream/make :buffer 10}
          state-with-stream (vm/run (make-state {}) make-ast)
          stream-ref (:value state-with-stream)
          stream-id (:id stream-ref)

          ;; Put three values
          put-val (fn [state val]
                    (vm/run state
                            {:type :stream/put
                             :target {:type :literal :value stream-ref}
                             :val {:type :literal :value val}}))

          state-after-puts (-> state-with-stream
                               (put-val :first)
                               (put-val :second)
                               (put-val :third))

          ;; Take values one by one
          take-ast {:type :stream/take
                    :source {:type :literal :value stream-ref}}

          result1 (vm/run state-after-puts take-ast)
          result2 (vm/run result1 take-ast)
          result3 (vm/run result2 take-ast)]

      (is (= :first (:value result1))
          "First take should return first value")
      (is (= :second (:value result2))
          "Second take should return second value")
      (is (= :third (:value result3))
          "Third take should return third value"))))

(deftest test-stream-with-lambda
  (testing "stream operations within lambda application"
    (let [;; AST: ((lambda (s) (stream/put s 42)) (stream/make))
          ;; Then take from the same stream
          ast {:type :application
               :operator {:type :lambda
                          :params ['s]
                          :body {:type :stream/put
                                 :target {:type :variable :name 's}
                                 :val {:type :literal :value 42}}}
               :operands [{:type :stream/make :buffer 5}]}
          result (vm/run (make-state {}) ast)]

      (is (= 42 (:value result))
          "Lambda should be able to put to stream"))))

(deftest test-stream-put-take-roundtrip
  (testing "put then take roundtrip within nested lambdas"
    (let [;; AST: ((lambda (s) ((lambda (_) (stream/take s)) (stream/put s 42))) (stream/make))
          ast {:type :application
               :operator {:type :lambda
                          :params ['s]
                          :body {:type :application
                                 :operator {:type :lambda
                                            :params ['_]
                                            :body {:type :stream/take
                                                   :source {:type :variable :name 's}}}
                                 :operands [{:type :stream/put
                                             :target {:type :variable :name 's}
                                             :val {:type :literal :value 42}}]}}
               :operands [{:type :stream/make :buffer 5}]}
          result (vm/run (make-state {}) ast)]

      (is (= 42 (:value result))
          "Should put 42 and take 42 back"))))

(comment
  ;; Run tests from REPL
  #?(:clj (require '[clojure.test :refer [run-tests]]))
  #?(:clj (run-tests 'yin.vm-stream-test)))
