(ns yin.vm.debruijn-register-code-test
  (:require [clojure.test :refer [deftest is]]
            [yin.vm.debruijn-register-code :as rcode]))


(deftest golden-descriptor-hash-test
  (is (= "aa757659a28f9787e44cd04d02d9f4412eba1088d46a1b07adf9fa820d07e74c"
         rcode/descriptor-hash)))
