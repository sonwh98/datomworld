(ns yin.bytecode-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.bytecode :as bc]))

(def primitives
  {'+ +
   '- -
   '* *
   '/ /})

(deftest literal-test
  (testing "Literal compilation and execution"
    (let [ast {:type :literal :value 42}
          [bytes pool] (bc/compile ast)]
      (is (= 42 (bc/run-bytes bytes pool))))))

(deftest variable-test
  (testing "Variable lookup"
    (let [ast {:type :variable :name 'a}
          [bytes pool] (bc/compile ast)]
      (is (= 100 (bc/run-bytes bytes pool {'a 100}))))))

(deftest application-test
  (testing "Primitive application (+ 10 20)"
    (let [ast {:type :application
               :operator {:type :variable :name '+}
               :operands [{:type :literal :value 10}
                          {:type :literal :value 20}]}
          [bytes pool] (bc/compile ast)]
      (prn bytes pool)

      (is (= 30 (bc/run-bytes bytes pool primitives))))))

(deftest lambda-test
  (testing "Lambda definition and application ((fn [x] (+ x 1)) 10)"
    (let [ast {:type :application
               :operator {:type :lambda
                          :params ['x]
                          :body {:type :application
                                 :operator {:type :variable :name '+}
                                 :operands [{:type :variable :name 'x}
                                            {:type :literal :value 1}]}}
               :operands [{:type :literal :value 10}]}
          [bytes pool] (bc/compile ast)]
      (is (= 11 (bc/run-bytes bytes pool primitives))))))

(deftest conditional-test
  (testing "If true"
    (let [ast {:type :if
               :test {:type :literal :value true}
               :consequent {:type :literal :value :yes}
               :alternate {:type :literal :value :no}}
          [bytes pool] (bc/compile ast)]
      (is (= :yes (bc/run-bytes bytes pool)))))

  (testing "If false"
    (let [ast {:type :if
               :test {:type :literal :value false}
               :consequent {:type :literal :value :yes}
               :alternate {:type :literal :value :no}}
          [bytes pool] (bc/compile ast)]
      (is (= :no (bc/run-bytes bytes pool))))))
