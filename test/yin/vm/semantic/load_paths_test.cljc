(ns yin.vm.semantic.load-paths-test
  "code-as-tuples §7.2 for the register-shaped vector: the direct vector
   load and the projection loads (datom lane, row lane) run one validator
   and yield one image."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [yin.vm :as vm]
            [yin.vm.semantic.code :as code]
            [yin.vm.semantic.corpus :as corpus]
            [yin.vm.semantic.linearize :as linearize]))


(defn- load-image
  [v]
  (code/load-vector v code/contract))


(deftest float64-literal-beyond-2-53-loads-on-every-host
  (testing "a float literal past 2^53 is float64 content on every host:
            both projection lanes load it to one image at one address"
    (let [ast (corpus/tail (corpus/lit (cbor/float64 1.0E20)))
          rows (load-image (:vector (linearize/project ast)))
          datoms (load-image (:vector (linearize/project-datoms
                                        (vm/ast->datoms ast))))]
      (is (= rows datoms))
      (is (= :segment/blake3-d034b2a44fee49a5f13bf6d2a6d186cb265f567d932d144939d3587461ed46b6
             (:address rows)))))
  #?(:cljs
     (testing "a bare integral JavaScript Number past 2^53 is not content:
               neither lane mints an address for it"
       (let [ast (corpus/tail (corpus/lit 1e20))]
         (is (thrown-with-msg? js/Error #"unsafe integral JavaScript number"
               (load-image (:vector (linearize/project ast)))))
         (is (thrown-with-msg? js/Error #"unsafe integral JavaScript number"
               (load-image (:vector (linearize/project-datoms
                                      (vm/ast->datoms ast))))))))
     :default nil))


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
