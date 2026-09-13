(ns yin.vm.v2.code-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [yin.vm.v2.code :as code]))


;; =============================================================================
;; Hand assembly
;; =============================================================================

(def ^:private seg -1)


(defn- eid
  "The instruction entity at pc in the fixtures below."
  [pc]
  (- -10 pc))


(defn- entity-datoms
  [e attr-values]
  (mapv (fn [[a v]] [e a v 0 datom/default-op]) attr-values))


(defn- segment
  ([length] (segment seg length))
  ([e length]
   (entity-datoms e [[:yin.code/type :segment] [:yin.code/length length]])))


(defn- instruction
  [pc op & operands]
  (entity-datoms (eid pc)
                 (into [[:yin.code/segment seg] [:yin.code/pc pc]
                        [:yin.code/op op]]
                       (partition 2 operands))))


(defn- assemble
  [& parts]
  (vec (apply concat parts)))


(defn- without
  "Remove every datom of entity e with attribute a."
  [datoms e a]
  (filterv (fn [d] (not (and (= e (nth d 0)) (= a (nth d 1))))) datoms))


(defn- with-value
  "Replace the value of every datom of entity e with attribute a."
  [datoms e a v]
  (mapv (fn [d] (if (and (= e (nth d 0)) (= a (nth d 1))) (assoc d 2 v) d))
        datoms))


(defn- worked-segment
  "`((fn [x] (+ x 1)) 10)` as lowered in yin.vm.semantic.md §2.7."
  []
  (assemble (segment 14)
            (instruction 0 :closure :yin.code/params '[x] :yin.code/body (eid 6))
            (instruction 1 :push)
            (instruction 2 :const :yin.code/value 10)
            (instruction 3 :push)
            (instruction 4 :call :yin.code/argc 1 :yin.code/tail? false)
            (instruction 5 :halt)
            (instruction 6 :var :yin.code/name '+)
            (instruction 7 :push)
            (instruction 8 :var :yin.code/name 'x)
            (instruction 9 :push)
            (instruction 10 :const :yin.code/value 1)
            (instruction 11 :push)
            (instruction 12 :call :yin.code/argc 2 :yin.code/tail? true)
            (instruction 13 :return)))


(defn- branching-segment
  "`(if true 1 2)` in the §5.3 shape: pc 4 falls into the labelled pc 5."
  []
  (assemble (segment 6)
            (instruction 0 :const :yin.code/value true)
            (instruction 1 :branch-false :yin.code/target (eid 4))
            (instruction 2 :const :yin.code/value 1)
            (instruction 3 :jump :yin.code/target (eid 5))
            (instruction 4 :const :yin.code/value 2)
            (instruction 5 :halt)))


(defn- ffi-segment
  [argc]
  (assemble (segment 2)
            (instruction 0 :ffi-call :yin.code/ffi-op :op/echo :yin.code/argc argc)
            (instruction 1 :halt)))


(defn- defect
  [rule entity]
  {:rule rule, :entity entity})


;; =============================================================================
;; Well-formed segments
;; =============================================================================

(deftest well-formed-segments-test
  (testing "The worked segment of §2.7 is well formed"
    (is (nil? (code/well-formed? (worked-segment)))))
  (testing "Branches, a jump, and a labelled fall-in are well formed"
    (is (nil? (code/well-formed? (branching-segment)))))
  (testing "An ffi-call with no arguments is well formed"
    (is (nil? (code/well-formed? (ffi-segment 0))))))


;; =============================================================================
;; Rule 1: exactly one segment entity
;; =============================================================================

(deftest one-segment-test
  (testing "Passing: one segment entity"
    (is (nil? (code/well-formed? (worked-segment)))))
  (testing "A batch with no segment entity names no entity"
    (is (= (defect :one-segment nil)
           (code/well-formed? (without (without (worked-segment)
                                                seg
                                                :yin.code/type)
                                       seg
                                       :yin.code/length)))))
  (testing "A second segment entity is named"
    (is (= (defect :one-segment -2)
           (code/well-formed? (assemble (worked-segment) (segment -2 1)))))))


(deftest instruction-shape-test
  (testing "An instruction naming another segment is named"
    (is (= (defect :instruction-shape (eid 1))
           (code/well-formed?
             (with-value (worked-segment) (eid 1) :yin.code/segment -2)))))
  (testing "An instruction with no op is named"
    (is (= (defect :instruction-shape (eid 1))
           (code/well-formed? (without (worked-segment) (eid 1) :yin.code/op)))))
  (testing "An op outside the §2.4 vocabulary is named"
    (is (= (defect :instruction-shape (eid 1))
           (code/well-formed?
             (with-value (worked-segment) (eid 1) :yin.code/op :frobnicate))))))


;; =============================================================================
;; Rule 2: pcs are exactly 0 .. length-1, each once
;; =============================================================================

(deftest dense-pcs-test
  (testing "Passing: fourteen instructions at pcs 0..13"
    (is (nil? (code/well-formed? (worked-segment)))))
  (testing "A duplicate pc names the later instruction"
    (is (= (defect :dense-pcs (eid 13))
           (code/well-formed?
             (with-value (worked-segment) (eid 13) :yin.code/pc 12)))))
  (testing "A pc at or past length is named"
    (is (= (defect :dense-pcs (eid 13))
           (code/well-formed?
             (with-value (worked-segment) (eid 13) :yin.code/pc 14)))))
  (testing "A negative pc is named"
    (is (= (defect :dense-pcs (eid 0))
           (code/well-formed?
             (with-value (worked-segment) (eid 0) :yin.code/pc -1)))))
  (testing "An instruction with no pc is named"
    (is (= (defect :dense-pcs (eid 5))
           (code/well-formed? (without (worked-segment) (eid 5) :yin.code/pc)))))
  (testing "A length larger than the instruction count names the segment"
    (is (= (defect :dense-pcs seg)
           (code/well-formed?
             (with-value (worked-segment) seg :yin.code/length 15)))))
  (testing "A missing length names the segment"
    (is (= (defect :dense-pcs seg)
           (code/well-formed?
             (without (worked-segment) seg :yin.code/length))))))


;; =============================================================================
;; Rule 3: the batch is sorted by pc
;; =============================================================================

(deftest sorted-by-pc-test
  (testing "Passing: the segment entity may follow its instructions"
    (let [datoms (worked-segment)
          seg? (fn [d] (= seg (nth d 0)))]
      (is (nil? (code/well-formed? (into (filterv (complement seg?) datoms)
                                         (filter seg? datoms)))))))
  (testing "The first instruction out of pc order is named"
    (let [datoms (worked-segment)
          of (fn [pc] (filterv #(= (eid pc) (nth % 0)) datoms))
          swapped (assemble (segment 14)
                            (of 0)
                            (of 1)
                            (of 3)
                            (of 2)
                            (mapcat of (range 4 14)))]
      (is (= (defect :sorted-by-pc (eid 3)) (code/well-formed? swapped))))))


;; =============================================================================
;; Rule 4: targets and bodies resolve to instructions of the segment
;; =============================================================================

(deftest dangling-target-test
  (testing "Passing: targets and bodies name instructions"
    (is (nil? (code/well-formed? (branching-segment))))
    (is (nil? (code/well-formed? (worked-segment)))))
  (testing "A body naming no entity in the batch is named"
    (is (= (defect :dangling-target (eid 0))
           (code/well-formed?
             (with-value (worked-segment) (eid 0) :yin.code/body -99)))))
  (testing "A target naming the segment entity, not an instruction, is named"
    (is (= (defect :dangling-target (eid 1))
           (code/well-formed?
             (with-value (branching-segment) (eid 1) :yin.code/target seg)))))
  (testing "A jump with no target is named"
    (is (= (defect :dangling-target (eid 3))
           (code/well-formed?
             (without (branching-segment) (eid 3) :yin.code/target)))))
  (testing "A closure with no body is named"
    (is (= (defect :dangling-target (eid 0))
           (code/well-formed?
             (without (worked-segment) (eid 0) :yin.code/body))))))


;; =============================================================================
;; Rule 5: blocks end in a terminator or fall into a labelled successor
;; =============================================================================

(deftest missing-terminator-test
  (testing "Passing: return, halt, and a trailing jump are terminators"
    (is (nil? (code/well-formed? (worked-segment))))
    (is (nil? (code/well-formed?
                (assemble (segment 2)
                          (instruction 0 :const :yin.code/value 1)
                          (instruction 1 :jump :yin.code/target (eid 0)))))))
  (testing "Passing: a block falls into a labelled successor"
    (is (nil? (code/well-formed? (branching-segment)))))
  (testing "A final instruction that is not a terminator is named"
    (is (= (defect :missing-terminator (eid 13))
           (code/well-formed?
             (with-value (worked-segment) (eid 13) :yin.code/op :push)))))
  (testing "A conditional branch is not a terminator"
    (is (= (defect :missing-terminator (eid 1))
           (code/well-formed?
             (assemble (segment 2)
                       (instruction 0 :const :yin.code/value false)
                       (instruction 1 :branch-false :yin.code/target (eid 0)))))))
  (testing "An empty segment has no terminator and names the segment"
    (is (= (defect :missing-terminator seg)
           (code/well-formed? (segment 0))))))


;; =============================================================================
;; Rule 6: calls carry a non-negative argc
;; =============================================================================

(deftest negative-argc-test
  (testing "Passing: argc 0 and positive argc"
    (is (nil? (code/well-formed? (ffi-segment 0))))
    (is (nil? (code/well-formed? (worked-segment)))))
  (testing "A negative call argc is named"
    (is (= (defect :negative-argc (eid 4))
           (code/well-formed?
             (with-value (worked-segment) (eid 4) :yin.code/argc -1)))))
  (testing "A call with no argc is named"
    (is (= (defect :negative-argc (eid 4))
           (code/well-formed? (without (worked-segment) (eid 4) :yin.code/argc)))))
  (testing "A non-integer argc is named"
    (is (= (defect :negative-argc (eid 4))
           (code/well-formed?
             (with-value (worked-segment) (eid 4) :yin.code/argc "1")))))
  (testing "A negative ffi-call argc is named"
    (is (= (defect :negative-argc (eid 0))
           (code/well-formed? (ffi-segment -1))))))
