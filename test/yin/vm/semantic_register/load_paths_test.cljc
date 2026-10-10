(ns yin.vm.semantic-register.load-paths-test
  "code-as-tuples §7.2 for the register-shaped vector: the direct vector
   load and the projection loads (datom lane, row lane) run one validator
   and yield one image."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.corpus :as corpus]
            [yin.vm.semantic-register.linearize :as linearize]))


(defn- load-image
  [v]
  (code/load-vector v code/contract))


(deftest both-paths-one-image
  (doseq [[n ast] corpus/programs]
    (testing n
      (let [direct (load-image (:vector (get corpus/goldens n)))
            rows (load-image (:vector (linearize/project ast)))
            datoms (load-image (:vector (linearize/project-datoms
                                          (vm/ast->datoms ast))))]
        (is (= direct rows) "direct vector vs row-lane projection")
        (is (= direct datoms) "direct vector vs datom-lane projection")
        (is (= (:address (get corpus/goldens n)) (:address direct)))))))


(deftest round-tripped-rows-load-to-the-same-image
  (doseq [[n ast] corpus/programs]
    (testing n
      (is (= (load-image (:vector (get corpus/goldens n)))
             (load-image (:vector (linearize/project-rows
                                    (vm/ast->semantic-bytecode
                                      (vm/semantic-bytecode->ast
                                        (vm/ast->semantic-bytecode ast)))))))))))
