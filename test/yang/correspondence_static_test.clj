(ns yang.correspondence-static-test
  (:require [clojure.test :refer [deftest is]]
            [dao.jing.mem :as mem]
            [yin.vm :as vm]
            [yang.correspondence-static :as static]))


(deftest measured-static-c1-is-root-preserving-and-exact
  (let [ast {:type :application :operator {:type :variable :name 'f}
             :operands [{:type :literal :value 1}]}
        tree (vm/ast->semantic-bytecode ast)
        origin [:source :program ["recorded" 0] 0]
        markers [[origin (:root tree) [] :yang/call-site 1]]
        result (static/measure-static! tree origin markers (mem/create-content-mem) (constantly 0))]
    (is (= :pass (:status result)))
    (is (= (:root tree) (:root result)))
    (is (= 1 (:marker-count result)))
    (is (= {:baseline-project-ms 0 :marker-derive-ms 0 :publish-ms 0 :load-ms 0 :relation-build-ms 0 :query-ms 0}
           (:phases result)))
    (is (thrown? Exception
          (static/measure-static! tree origin [] (mem/create-content-mem) (constantly 0))))
    (is (thrown? Exception
          (static/measure-static! tree [:ephemeral :x :y 0] markers
                                  (mem/create-content-mem) (constantly 0))))))


(deftest static-comparison-uses-fresh-baseline-and-c1-stores
  (let [ast {:type :application :operator {:type :variable :name 'f}
             :operands [{:type :literal :value 1}]}
        tree (vm/ast->semantic-bytecode ast)
        origin [:source :program ["recorded" 0] 0]
        markers [[origin (:root tree) [] :yang/call-site 1]]
        result (static/compare-static! tree origin markers
                                       (mem/create-content-mem) (mem/create-content-mem)
                                       (constantly 0))]
    (is (= :pass (:status result)))
    (is (= (:root tree) (:root result)))
    (is (= {:publish-ms 0 :load-ms 0} (:baseline-phases result)))
    (is (= 1 (:marker-count result)))))
