(ns thetao.vm.examples)


(def literal-42
  {:type :literal
   :value 42})


(def simple-closure
  {:type :application
   :operator {:type :lambda
              :params ['x]
              :body {:type :application
                     :operator {:type :variable :name '+}
                     :operands [{:type :variable :name 'x}
                                {:type :literal :value 1}]}}
   :operands [{:type :literal :value 41}]})
