(ns tmpgen.gen-test
  (:require [clojure.test :refer :all]))


(deftest gen
  (load-file "test/resources/yin/vm/semantic/scanner-golden-c4.generate.clj"))
