(ns yin.vm.v2-test
  "Codec tests for `yin.vm.v2`: the explicit root fact, its indexing, and the
   Phase 0 retirements of yin.vm.macro.md (§2.4, §7)."
  (:require [clojure.test :refer [deftest is testing]]
            [yang.clojure :as yang]
            [yin.vm.v2 :as vm]))


(defn- error-message
  [f]
  (try (f)
       nil
       (catch #?(:clj Exception :cljs :default) e (ex-message e))))


(def ^:private program
  "A macro-free program touching :if, :application, :lambda and tail flags."
  '(if (< 1 2) ((fn [x] (+ x 1)) 41) 0))


(def ^:private pre-root-snapshot
  "`(vm/ast->datoms (yang/compile program))` captured before the root fact
   was added."
  '[[-17 :yin/tail? true 0 1] [-17 :yin/type :if 0 1]
    [-18 :yin/type :application 0 1] [-19 :yin/type :variable 0 1]
    [-19 :yin/name < 0 1] [-20 :yin/type :literal 0 1]
    [-20 :yin/value 1 0 1] [-21 :yin/type :literal 0 1]
    [-21 :yin/value 2 0 1] [-18 :yin/operator -19 0 1]
    [-18 :yin/operands [-20 -21] 0 1] [-22 :yin/tail? true 0 1]
    [-22 :yin/type :application 0 1] [-23 :yin/type :lambda 0 1]
    [-23 :yin/params [x] 0 1] [-24 :yin/tail? true 0 1]
    [-24 :yin/type :application 0 1] [-25 :yin/type :variable 0 1]
    [-25 :yin/name + 0 1] [-26 :yin/type :variable 0 1]
    [-26 :yin/name x 0 1] [-27 :yin/type :literal 0 1]
    [-27 :yin/value 1 0 1] [-24 :yin/operator -25 0 1]
    [-24 :yin/operands [-26 -27] 0 1] [-23 :yin/body -24 0 1]
    [-28 :yin/type :literal 0 1] [-28 :yin/value 41 0 1]
    [-22 :yin/operator -23 0 1] [-22 :yin/operands [-28] 0 1]
    [-29 :yin/type :literal 0 1] [-29 :yin/value 0 0 1]
    [-17 :yin/test -18 0 1] [-17 :yin/consequent -22 0 1]
    [-17 :yin/alternate -29 0 1]])


(defn- literal-batch
  "A literal program at tempid -101 followed by an unreferenced literal at
   -1, which the structural heuristic would prefer as root."
  []
  (let [[root datoms] (vm/ast->datoms-with-root {:type :literal, :value 7}
                                                {:id-start -100})]
    [root (into datoms [[-1 :yin/type :literal 0 1] [-1 :yin/value 8 0 1]])]))


(deftest schema-carries-root-and-drops-event-attributes
  (is (= {} (:yin/root vm/schema)))
  (is (contains? vm/schema :yin/macro-name))
  (is (contains? vm/schema :yin/macro?))
  (doseq [a [:yin/phase-policy :yin/phase :yin/capability :yin/source-call
             :yin/macro :yin/expansion-root :yin/error]]
    (is (not (contains? vm/schema a)) (str a " belongs to the expander"))))


(deftest compile-output-is-unchanged-modulo-the-root-fact
  (let [ast (yang/compile program)
        [root datoms] (vm/ast->datoms-with-root ast)]
    (is (= -17 root))
    (is (= (conj pre-root-snapshot [-17 :yin/root true 0 1]) datoms)
        "exactly one row is added, and it is the last")
    (is (= datoms (vm/ast->datoms ast)))
    (is (= 1 (count (filter #(= :yin/root (nth % 1)) datoms))))
    (is (= ast (vm/datoms->ast datoms)) "the root fact does not disturb decode")))


(deftest root-fact-wins-over-the-heuristic
  (let [[root datoms] (literal-batch)
        indexed (vm/index-datoms datoms)]
    (is (= -101 root))
    (is (= -1 (:root-id (vm/index-datoms (remove #(= :yin/root (nth % 1))
                                                 datoms))))
        "without the fact the heuristic picks the other entity")
    (is (= -101 (:root-id indexed)))
    (is (nil? (:error indexed)))
    (is (= {:type :literal, :value 7} (vm/datoms->ast datoms)))))


(deftest last-root-fact-wins
  (let [[_ datoms] (literal-batch)
        datoms (conj datoms [-1 :yin/root true 0 1])]
    (is (= -1 (:root-id (vm/index-datoms datoms))))
    (is (= {:type :literal, :value 8} (vm/datoms->ast datoms)))))


(deftest explicit-root-id-overrides-the-root-fact
  (let [[_ datoms] (literal-batch)]
    (is (= -1 (:root-id (vm/index-datoms datoms {:root-id -1}))))))


(deftest dangling-root-is-recorded-not-thrown
  (let [datoms [[-1 :yin/type :literal 0 1] [-1 :yin/value 1 0 1]
                [-9 :yin/root true 0 1]]
        indexed (vm/index-datoms datoms)]
    (is (= -9 (:root-id indexed)))
    (is (= {:rule :dangling-root, :entity -9} (:error indexed)))
    (is (some? (error-message #(vm/datoms->ast datoms)))
        "decoding the dangling root is the loud failure")))


(deftest heuristic-fallback-is-unchanged
  (testing "a batch without a root fact still finds its root structurally"
    (let [indexed (vm/index-datoms pre-root-snapshot)]
      (is (= -17 (:root-id indexed)))
      (is (nil? (:error indexed)))
      (is (= (yang/compile program) (vm/datoms->ast pre-root-snapshot)))))
  (testing "the hand-written datom form v1 accepted still decodes"
    (is (= {:type :literal, :value 99}
           (vm/datoms->ast [[-1 :yin/type :literal 0 1]
                            [-1 :yin/value 99 0 1]])))))


(deftest macro-lambda-keeps-macro-flag-without-phase
  (let [ast {:type :lambda,
             :params '[x],
             :macro? true,
             :body {:type :variable, :name 'x}}
        datoms (vm/ast->datoms ast)]
    (is (= '[[-17 :yin/type :lambda 0 1] [-17 :yin/macro? true 0 1]
             [-17 :yin/params [x] 0 1] [-18 :yin/type :variable 0 1]
             [-18 :yin/name x 0 1] [-17 :yin/body -18 0 1]
             [-17 :yin/root true 0 1]]
           datoms))
    (is (= ast (vm/datoms->ast datoms)))
    (testing "a legacy :yin/phase-policy fact is not decoded"
      (is (= ast
             (vm/datoms->ast (conj datoms
                                   [-17 :yin/phase-policy :compile 0 1])))))))


(deftest macro-expand-node-type-is-retired
  (let [node {:type :yin/macro-expand,
              :operator {:type :variable, :name 'm},
              :operands []}]
    (is (= "Unknown AST node type"
           (error-message #(vm/ast->datoms node))))
    (is (= "Unknown AST node type in datoms"
           (error-message #(vm/datoms->ast [[-1 :yin/type :yin/macro-expand 0 1]
                                            [-1 :yin/root true 0 1]]))))))
