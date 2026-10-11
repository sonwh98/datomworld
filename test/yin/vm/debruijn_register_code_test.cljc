(ns yin.vm.debruijn-register-code-test
  (:require [clojure.test :refer [deftest is]]
            [yin.vm.debruijn-register-code :as rcode]))


(deftest golden-descriptor-hash-test
  (is (= "7eacba41f9f1548d18b724cbd2db87945f09ec7b4ab2f303399e9b6497007632"
         rcode/descriptor-hash)))
