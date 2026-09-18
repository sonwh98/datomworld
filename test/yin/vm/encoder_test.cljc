(ns yin.vm.encoder-test
  "U16 Phase 2 (`docs/design/yin.vm.macro.md` §2.1, §10.2): the encoder
   projects frontend output to the expander's row-native input batch, with a
   complete harvest catalogue in source declaration order and one
   declaration row per macro definition."
  (:require [clojure.test :refer [deftest is testing]]
            [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.encoder :as encoder]
            [yin.vm.macro :as m]))


(defn- program
  [forms]
  (yang/compile-program forms))


(defn- harvested
  "`[name declared?]` per definition record, in harvest order, as the
   expander's admission reads the batch."
  [batch]
  (mapv (juxt :name :declared?) (m/definitions batch)))


(deftest the-batch-is-canonical-rows-with-a-complete-catalogue
  (let [ast (program '((def a 1)
                       (defmacro m [x] x)
                       (if false (def b 2) nil)
                       (def a 3)))
        [tag trees run-index decls harvest :as batch] (encoder/program-batch ast)]
    (is (= :yin.program/batch tag))
    (is (= 0 run-index))
    (is (= (m/packet->row-set (m/ast->packet ast)) (m/packet->row-set (first trees)))
        "each member is the canonical tree packet of its AST; row order is free")
    (is (nil? (m/valid-tree? (first trees))))
    (is (= [[:yin.macro/definition 0 (nth (nth harvest 1) 3)]] decls)
        "only the defmacro occurrence is declared")
    (is (= [['a false] ['m true] ['b false] ['a false]] (harvested batch))
        "every definition, including one in an unexecuted branch, in source order")))


(deftest macro-ness-is-an-occurrence-declaration-not-row-content
  (let [plain (encoder/program-batch (yang/compile '(def m (fn [x] x))))
        macro (encoder/program-batch (yang/compile '(defmacro m [x] x)))]
    (is (= (nth plain 1) (nth macro 1))
        "the same lambda content projects to the same rows")
    (is (= [] (nth plain 3)))
    (is (= [[:yin.macro/definition 0 []]] (nth macro 3)))
    (is (= (nth plain 4) (nth macro 4)))))


(deftest a-datom-member-declares-what-its-map-ast-declares
  (let [ast (program '((defmacro m [x] x) (m 1)))]
    (is (= (encoder/program-batch ast)
           (encoder/program-batch (vec (vm/ast->datoms ast))))
        "the :yin/macro? datom carries the frontend's declaration fact")))


(deftest disconnected-members-are-harvested-by-tree-index
  (let [run (yang/compile '(m 5))
        support (yang/compile '(defmacro m [x] x))
        batch (encoder/program-batch [run support] 0)]
    (is (= 2 (count (nth batch 1))))
    (is (= [[:yin.macro/definition 1 []]] (nth batch 3)))
    (is (= [[:yin.macro/harvest 0 1 []]] (nth batch 4)))
    (is (= (m/ast->packet {:type :literal, :value 5})
           (m/mark-tail (m/expand batch (m/make-ctx {:token :t :source-medium :s}))))
        "the supporting tree's macro expands the run tree, and is not forwarded")))


(deftest an-unsupported-member-fails-at-the-boundary
  (is (thrown? #?(:cljd Object :clj Exception :cljs :default)
               (encoder/program-batch 42))))
