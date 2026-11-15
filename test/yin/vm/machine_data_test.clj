(ns yin.vm.machine-data-test
  (:require [clojure.test :refer [deftest is]]
            [yin.vm :as vm]))


(deftest shared-machine-data-is-validated-once
  (let [shared (nth (iterate (fn [x] [x x]) {:value 1}) 35)]
    (is (vm/machine-data? shared))
    (is (not (vm/machine-data? [shared {:bad (Object.)}])))))
