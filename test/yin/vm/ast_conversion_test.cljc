(ns yin.vm.ast-conversion-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.clojure :as yang]
    [yin.vm :as vm]))


(deftest ast-datom-roundtrip-test
  (testing "Literal roundtrip"
    (let [ast {:type :literal :value 42}
          datoms (vm/ast->datoms ast)]
      (is (= ast (vm/datoms->ast datoms)))))

  (testing "Variable roundtrip"
    (let [ast {:type :variable :name 'x}
          datoms (vm/ast->datoms ast)]
      (is (= ast (vm/datoms->ast datoms)))))

  (testing "Application roundtrip"
    (let [ast {:type :application
               :operator {:type :variable :name '+}
               :operands [{:type :literal :value 1}
                          {:type :literal :value 2}]}
          datoms (vm/ast->datoms ast)]
      (is (= ast (vm/datoms->ast datoms)))))

  (testing "Lambda roundtrip"
    (let [ast {:type :lambda
               :params ['x]
               :body {:type :application
                      :operator {:type :variable :name '+}
                      :operands [{:type :variable :name 'x}
                                 {:type :literal :value 1}]}}
          datoms (vm/ast->datoms ast)]
      (clojure.pprint/pprint datoms)

      (is (= ast (vm/datoms->ast datoms)))
      (clojure.pprint/pprint ast)))

  (testing "Function definition (defn foo) roundtrip"
    (let [ast (yang/compile '(defn foo
                               [n]
                               (+ n 1)))
          datoms (vm/ast->datoms ast)]
      (is (= ast (vm/datoms->ast datoms)))))

  (testing "More complex defn roundtrip"
    (let [ast (yang/compile '(defn bar
                               [x y]
                               (println x) (+ x y)))
          datoms (vm/ast->datoms ast)]
      (is (= ast (vm/datoms->ast datoms)))))

  (testing "Recursive defn (fib) roundtrip"
    (let [ast (yang/compile '(defn fib
                               [n]
                               (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2))))))
          datoms (vm/ast->datoms ast)]
      (is (= ast (vm/datoms->ast datoms)))))

  (testing "If roundtrip"
    (let [ast {:type :if
               :test {:type :variable :name 'c}
               :consequent {:type :literal :value :yes}
               :alternate {:type :literal :value :no}}
          datoms (vm/ast->datoms ast)]
      (is (= ast (vm/datoms->ast datoms)))))

  (testing "Tail call flag roundtrip"
    (let [ast {:type :application
               :operator {:type :variable :name 'f}
               :operands []
               :tail? true}
          datoms (vm/ast->datoms ast)]
      (is (= ast (vm/datoms->ast datoms)))))

  (testing "dao.stream.apply/call roundtrip"
    (let [ast {:type :dao.stream.apply/call
               :op :op/eval
               :operands [{:type :literal :value "1+2"}]}
          datoms (vm/ast->datoms ast)]
      (is (= ast (vm/datoms->ast datoms)))))

  (testing "vm/store-get and vm/store-put roundtrip"
    (let [ast-get {:type :vm/store-get :key 'x}
          datoms-get (vm/ast->datoms ast-get)]
      (is (= ast-get (vm/datoms->ast datoms-get)))

      (let [ast-put {:type :vm/store-put :key 'y :val 123}
            datoms-put (vm/ast->datoms ast-put)]
        (is (= ast-put (vm/datoms->ast datoms-put))))))

  (testing "Stream ops roundtrip"
    (let [ast-make {:type :stream/make :buffer 64}
          datoms-make (vm/ast->datoms ast-make)]
      (is (= ast-make (vm/datoms->ast datoms-make)))

      (let [ast-put {:type :stream/put
                     :target {:type :variable :name 's}
                     :val {:type :literal :value 1}}
            datoms-put (vm/ast->datoms ast-put)]
        (is (= ast-put (vm/datoms->ast datoms-put)))))))


(comment

    [[1052 :yin/tail? true 2 0]
     [1052 :yin/type :application 2 0]
     [1053 :yin/type :variable 2 0]
     [1053 :yin/name 'yin/def 2 0]
     [1054 :yin/type :literal 2 0]
     [1054 :yin/value 'a 2 0]
     [1055 :yin/type :literal 2 0]
     [1055 :yin/value 1 2 0]
     [1052 :yin/operator 1053 2 0]
     [1052 :yin/operands 1054 2 0]
     [1052 :yin/operands 1055 2 0]
     [1056 :yin/type :variable 3 0]
     [1056 :yin/name 'a 3 0]
     [1057 :yin/tail? true 4 0]
     [1057 :yin/type :application 4 0]
     [1058 :yin/type :variable 4 0]
     [1058 :yin/name 'yin/def 4 0]
     [1059 :yin/type :literal 4 0]
     [1059 :yin/value 'f 4 0]
     [1060 :yin/type :lambda 4 0]
     [1060 :yin/params ['n] 4 0]
     [1061 :yin/type :variable 4 0]
     [1061 :yin/name 'n 4 0]
     [1060 :yin/body 1061 4 0]
     [1057 :yin/operator 1058 4 0]
     [1057 :yin/operands 1059 4 0]
     [1057 :yin/operands 1060 4 0]]


    [[1052 :yin/tail? true 2 0]]    
    
    )
