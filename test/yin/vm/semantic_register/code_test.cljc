(ns yin.vm.semantic-register.code-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.corpus :as corpus]
            [yin.vm.ucf :as ucf]))


(defn- throws-ex-data
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (or (ex-data e) {}))))


(def refusals
  "`[§3.4 item, expected defect, vector]`: each a minimal vector that
   passes every earlier rule and trips exactly the one named."
  '[[1 {:rule :nonempty, :pc 0} []]
    [1 {:rule :nonempty, :pc 0} ([:const 0 1] [:halt 0])]
    [2 {:rule :mnemonic, :pc 0} [[:push] [:halt 0]]]
    [2 {:rule :arity, :pc 0} [[:const 0] [:halt 0]]]
    [2 {:rule :operand-kind, :pc 0} [[:var 0 "x"] [:halt 0]]]
    [2 {:rule :operand-kind, :pc 0} [[:const -1 1] [:halt -1]]]
    [2 {:rule :operand-kind, :pc 1} [[:var 1 f] [:call 0 1 (2) false] [:halt 0]]]
    [2 {:rule :saturation, :pc 0} [[:gensym 0 nil] [:halt 0]]]
    [5 {:rule :target-bounds, :pc 0} [[:jump 9]]]
    ;; 6: main ends in :halt, every other body in :return
    [6 {:rule :body-partition, :pc 1} [[:const 0 1] [:return 0]]]
    ;; 6: a closure owning main
    [6 {:rule :body-partition, :pc 0} [[:closure 0 [] 0] [:halt 0]]]
    ;; 6: two closures owning one body
    [6 {:rule :body-partition, :pc 1}
     [[:closure 1 [x] 4] [:closure 2 [y] 4] [:call 0 1 [2] false] [:halt 0]
      [:var 0 x] [:return 0]]]
    ;; 6: an owner inside the body it owns
    [6 {:rule :body-partition, :pc 2}
     [[:const 0 1] [:halt 0] [:closure 0 [] 2] [:return 0]]]
    ;; 6: owners that never reach main
    [6 {:rule :body-partition, :pc 4}
     [[:const 0 1] [:halt 0] [:closure 0 [] 4] [:return 0] [:closure 0 [] 2]
      [:return 0]]]
    ;; 6: a jump into another body
    [6 {:rule :body-partition, :pc 2}
     [[:closure 1 [] 5] [:var 2 c] [:branch-false 2 5] [:const 0 1] [:jump 5]
      [:const 0 2] [:return 0]]]
    [6 {:rule :not-expression-structured, :pc 2} [[:const 0 1] [:const 1 2] [:halt 0]]]
    [6 {:rule :not-expression-structured, :pc 1} [[:const 0 1] [:jump 2] [:halt 0]]]
    [6 {:rule :not-expression-structured, :pc 0} [[:call 0 1 [] false] [:halt 0]]]
    [6 {:rule :not-expression-structured, :pc 1}
     [[:var 1 c] [:branch-false 1 3] [:const 0 1] [:halt 0]]]
    [7 {:rule :register-ids, :pc 0} [[:const 1 5] [:halt 1]]]
    [7 {:rule :register-ids, :pc 1} [[:var 1 f] [:const 1 2] [:call 0 1 [1] false] [:halt 0]]]
    [8 {:rule :definite-assignment, :pc 3}
     [[:var 1 h] [:var 3 g] [:var 4 a] [:call 2 3 [5] false] [:var 5 b]
      [:call 0 1 [2 4] false] [:halt 0]]]
    [8 {:rule :definite-assignment, :pc 5}
     [[:var 1 c] [:branch-false 1 4] [:const 0 1] [:jump 5] [:const 1 2] [:halt 0]]]
    [9 {:rule :exclusive-definitions, :pc 3}
     [[:var 1 c] [:branch-false 1 5] [:var 2 g] [:call 1 2 [] true] [:jump 6]
      [:const 0 2] [:halt 0]]]
    [10 {:rule :call-operands, :pc 1} [[:var 1 f] [:call 0 1 [] :yes] [:halt 0]]]
    [10 {:rule :call-operands, :pc 2}
     [[:var 1 f] [:var 2 x] [:call 0 1 [:x] false] [:halt 0]]]
    [10 {:rule :call-operands, :pc 1} [[:const 1 1] [:ffi-call 0 :op/echo [-1]] [:halt 0]]]
    [11 {:rule :reserved-name, :pc 0} [[:var 0 yin/def] [:halt 0]]]
    [11 {:rule :reserved-name, :pc 1} [[:const 1 5] [:define 0 yin/def 1] [:halt 0]]]
    [11 {:rule :reserved-name, :pc 0} [[:store-get 0 yin/def] [:halt 0]]]
    [11 {:rule :reserved-name, :pc 0}
     [[:closure 0 [yin/def] 2] [:halt 0] [:const 0 1] [:return 0]]]
    [12 {:rule :resume-operands, :pc 1} [[:const 1 7] [:resume "p" 1] [:halt 0]]]
    [12 {:rule :resume-operands, :pc 1} [[:const 1 7] [:resume :p x] [:halt 0]]]
    [13 {:rule :noncanonical-registers, :pc 0}
     [[:var 0 f] [:var 1 x] [:call 2 0 [1] false] [:halt 2]]]
    [13 {:rule :noncanonical-registers, :pc 2}
     [[:var 1 f] [:var 2 x] [:call 0 1 [1] false] [:halt 0]]]
    ;; 13 (amended): `(f (fn [x] x) (fn [y] y))` with its two bodies
    ;; swapped and the body-pcs adjusted; ids are canonical per body.
    [13 {:rule :noncanonical-layout, :pc 1}
     [[:var 1 f] [:closure 2 [x] 7] [:closure 3 [y] 5] [:call 0 1 [2 3] false]
      [:halt 0] [:var 0 y] [:return 0] [:var 0 x] [:return 0]]]
    ;; 13 (amended): a nested body discovered inside a body must follow
    ;; every body queued before it (`:body-queue-order` with the q-body
    ;; moved ahead of the r-body).
    [13 {:rule :noncanonical-layout, :pc 1}
     [[:closure 1 [p] 4] [:closure 2 [r] 10] [:call 0 1 [2] false] [:halt 0]
      [:var 1 p] [:closure 2 [q] 8] [:call 0 1 [2] true] [:return 0]
      [:var 0 q] [:return 0] [:var 0 r] [:return 0]]]])


(def label-placement-variant
  "`(stream-cursor (if c a b))` with `Lend` moved past the cursor. The
   parse delimits arms by the labels, so moved labels re-parse as another
   tree (`(if c a (stream-cursor b))`); no ids-equal label variant exists,
   and this one is refused before item 13."
  '[[:var 2 c] [:branch-false 2 4] [:var 1 a] [:jump 6] [:var 1 b]
    [:stream-cursor 0 1] [:halt 0]])


(deftest every-golden-is-accepted
  (doseq [[n {:keys [vector]}] corpus/goldens]
    (testing n
      (is (nil? (code/well-formed? vector))))))


(deftest refusal-rows-name-rule-and-pc
  (doseq [[item expected v] refusals]
    (testing (str "item " item " " (pr-str v))
      (is (= expected (code/well-formed? v))))))


(deftest every-item-has-a-refusal-row
  (is (= (set (range 1 14))
         (into #{3 4} (map first refusals)))
      "items 3-4 (dense, sorted pcs) hold by construction of the vector form")
  (is (= 14 (count code/rules)))
  (is (= #{:nonempty :mnemonic :arity :operand-kind :saturation :target-bounds
           :body-partition :not-expression-structured :register-ids
           :definite-assignment :exclusive-definitions :call-operands
           :reserved-name :resume-operands :noncanonical-registers
           :noncanonical-layout}
         (set (map (comp :rule second) refusals)))))


(deftest moved-labels-re-parse-as-another-tree
  (is (= '[[:var 2 c] [:branch-false 2 4] [:var 1 a] [:jump 5] [:var 1 b]
           [:stream-cursor 0 1] [:halt 0]]
         (assoc label-placement-variant 3 [:jump 5])))
  (is (nil? (code/well-formed? (assoc label-placement-variant 3 [:jump 5])))
      "the canonical placement is accepted")
  (is (= {:rule :definite-assignment, :pc 6}
         (code/well-formed? label-placement-variant))))


(deftest a-definition-less-id-is-legal
  (doseq [n [:all-terminal-arms :resume-body :resume-operand :resume-lambda-body]]
    (testing n
      (is (nil? (code/well-formed? (:vector (get corpus/goldens n))))))))


(deftest layout-syntax-is-not-judged-for-assignment
  (testing "the structural :return after a tail call names a register
            that no runtime path writes"
    (is (nil? (code/well-formed? '[[:var 1 f] [:call 0 1 [] true] [:halt 0]]))))
  (testing "an enclosing :call after a terminal operand"
    (is (nil? (code/well-formed?
                (:vector (get corpus/goldens :resume-operand)))))))


(deftest load-vector-checks-the-stamp-then-the-rules
  (let [v (:vector (get corpus/goldens :worked-example))]
    (is (= "v4" code/contract))
    (is (not= vm/semantic-contract code/contract)
        "the running stack-shaped machine keeps its own stamp until cutover")
    (is (= :contract-missing (:rule (throws-ex-data #(code/load-vector v nil)))))
    (is (= :contract-mismatch (:rule (throws-ex-data #(code/load-vector v "v3")))))
    (is (= {:rule :register-ids, :pc 0}
           (:defect (throws-ex-data
                      #(code/load-vector '[[:const 1 5] [:halt 1]] code/contract)))))
    (is (= {:vector v,
            :address (ucf/code-address v),
            :bodies [{:start 0, :end 7, :owner nil, :registers 6}]}
           (code/load-vector v code/contract)))))


(deftest register-counts-are-per-body
  (is (= [{:start 0, :end 4, :owner nil, :registers 3}
          {:start 4, :end 9, :owner 0, :registers 4}]
         (:bodies (code/load-vector (:vector (get corpus/goldens :lambda-application))
                                    code/contract))))
  (is (= [3 2 1 1]
         (map :registers
              (:bodies (code/load-vector
                         (:vector (get corpus/goldens :closure-in-arm-in-body))
                         code/contract))))))
