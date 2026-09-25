(ns yin.vm.debruijn.stack-test
  "B3 (docs/design/yin.vm.debruijn.stack.md, 'B3: de Bruijn VM kernel'):
   completion tests for `yin.vm.debruijn.stack`.

   Every program here is a hand-built instruction vector: this file
   predates B2 (the named-datom lowerer) and still requires neither
   `yin.vm.debruijn-code` nor a real lowering pass -- B2 exercises this
   kernel against real lowered images from its own test namespace instead.
   Parity fixtures pair a
   hand-lowered instruction vector with the equivalent named AST, run each
   through its own VM (a fresh instance every time -- D4, the named VM's
   environment leak, is sidestepped by construction, never reused), and
   compare under B0's normalizer (`yin.vm.debruijn-vm-contract-test/normalize`),
   reused rather than reimplemented."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Helpers
;; =============================================================================

(defn- named-value
  "Run `ast` on a fresh named VM and return its normalized value."
  [ast]
  (b0/normalize (vm/value (vm/eval (tu/create-vm) ast))))


(defn- debruijn-value
  "Run `segment` on a fresh de Bruijn VM (with the standard primitive
   registry, matching the named VM's own default -- `yin.vm/empty-state`)
   and return its normalized value."
  ([segment] (debruijn-value segment {}))
  ([segment opts]
   (b0/normalize
     (vm/value (vm/run (dvm/create-vm segment
                                      (merge {:primitives vm/primitives,
                                              :contract vm/stack-contract}
                                             opts)))))))


(defn- lit
  [v]
  {:type :literal, :value v})


(defn- variable
  [n]
  {:type :variable, :name n})


(defn- lambda
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [f & args]
  {:type :application, :operator f, :operands (vec args)})


;; =============================================================================
;; 1. Literals
;; =============================================================================

(deftest literal-test
  (testing "a bare :const, no locals, no calls"
    (is (= (named-value (lit 42))
           (debruijn-value [[:const 42] [:halt]])
           42))))


;; =============================================================================
;; 2. Variable loads: bound
;; =============================================================================

(deftest load-bound-identity-test
  (testing "a one-arg closure returning its own argument -- depth 0,
            position 0, the innermost frame"
    (let [ast (app (lambda '[x] (variable 'x)) (lit 42))
          segment [[:closure 1 4]  ; 0: closure, body at pc 4
                   [:const 42]     ; 1
                   [:call 1 false] ; 2
                   [:halt]         ; 3
                   [:load-bound 0 0]  ; 4: body
                   [:return]]         ; 5
          result (debruijn-value segment)]
      (is (= 42 result))
      (is (= (named-value ast) result)))))


;; =============================================================================
;; 3. Variable loads: free, via primitives -- and :call applying a
;;    resolved primitive host function
;; =============================================================================

(deftest load-free-primitive-test
  (testing "(+ 3 4) via :load-free '+ resolved through the primitive
            registry, then :call applying the resolved host function"
    (let [ast (app (variable '+) (lit 3) (lit 4))
          segment [[:load-free '+]   ; 0
                   [:const 3]        ; 1
                   [:const 4]        ; 2
                   [:call 2 false]   ; 3
                   [:halt]]          ; 4
          result (debruijn-value segment)]
      (is (= 7 result))
      (is (= (named-value ast) result)))))


(deftest load-free-store-fallback-test
  (testing "a free name absent from primitives and free-env resolves
            through the store -- the same env -> store -> primitives ->
            module registry order `yin.vm.engine/resolve-var` already
            implements for the named VM; positional locals never take this
            path, only :load-free does"
    (let [segment [[:load-free 'y] [:halt]]
          result (debruijn-value segment {:store {'y 55}})]
      (is (= 55 result)))))


;; =============================================================================
;; 4. Closures: nested capture, multiple arities, under/over-arity calls
;; =============================================================================

(deftest nested-closure-capture-test
  (testing "((fn [x] (fn [y] (- x y))) 3) 4) -- the inner closure's body
            reaches the outer frame at depth 1. `-` (non-commutative) is
            deliberate: a flipped-direction addressing bug would compute
            (- y x) = 1 instead of (- x y) = -1, so this pins the
            innermost-frame-is-last convention, not merely a result"
    (let [ast (app
                (app (lambda '[x] (lambda '[y] (app (variable '-)
                                                    (variable 'x)
                                                    (variable 'y))))
                     (lit 3))
                (lit 4))
          segment [[:closure 1 6]   ; 0: outer closure, body at 6
                   [:const 3]       ; 1
                   [:call 1 false]  ; 2: apply outer(3) -> inner closure
                   [:const 4]       ; 3
                   [:call 1 false]  ; 4: apply inner(4) -> -1
                   [:halt]          ; 5
                   [:closure 1 8]   ; 6: inner closure, body at 8
                   [:return]        ; 7
                   [:load-free '-]     ; 8: body of inner
                   [:load-bound 1 0]   ; 9: x, outer frame
                   [:load-bound 0 0]   ; 10: y, inner frame
                   [:call 2 false]     ; 11
                   [:return]]          ; 12
          result (debruijn-value segment)]
      (is (= -1 result))
      (is (= (named-value ast) result)))))


(deftest zero-arity-closure-test
  (testing "a thunk -- multiple arities coverage, arity 0"
    (let [ast (app (lambda [] (lit 99)))
          segment [[:closure 0 3]   ; 0
                   [:call 0 false]  ; 1
                   [:halt]          ; 2
                   [:const 99]      ; 3: body
                   [:return]]       ; 4
          result (debruijn-value segment)]
      (is (= 99 result))
      (is (= (named-value ast) result)))))


(defn- conj-pair-ast
  "(fn [a b] (conj (conj [] a) b)) -- named AST for the two-arg closure the
   under/over-arity tests below apply."
  []
  (lambda '[a b]
          (app (variable 'conj)
               (app (variable 'conj) (lit []) (variable 'a))
               (variable 'b))))


(defn- conj-pair-segment
  "The de Bruijn body for `conj-pair-ast`, laid out starting at `body-pc`
   in the caller's comments (the body has no internal jump/branch targets,
   so its absolute position does not affect these instructions themselves;
   `body-pc` documents the intended placement at each call site rather
   than being used to compute anything here): push the outer operator,
   then the inner application (its own operator, [], a, call), then b,
   then the outer call and return -- 'applications evaluate operator then
   operands' (section 3), applied recursively."
  [_body-pc]
  [[:load-free 'conj]       ; body-pc + 0: outer operator
   [:load-free 'conj]       ; body-pc + 1: inner operator
   [:const []]               ; body-pc + 2
   [:load-bound 0 0]         ; body-pc + 3: a
   [:call 2 false]           ; body-pc + 4: conj([], a)
   [:load-bound 0 1]         ; body-pc + 5: b
   [:call 2 false]           ; body-pc + 6: conj(r1, b)
   [:return]])                ; body-pc + 7


(deftest under-arity-call-nil-fills-test
  (testing "a 2-arity closure called with 1 argument nil-fills the missing
            parameter, matching engine/bind-params' zip-and-nil-fill rule"
    (let [ast (app (conj-pair-ast) (lit 1))
          segment (into [[:closure 2 4]   ; 0: closure, body at 4
                         [:const 1]        ; 1
                         [:call 1 false]   ; 2: only 1 arg for arity 2
                         [:halt]]          ; 3
                        (conj-pair-segment 4))
          result (debruijn-value segment)]
      (is (= [1 nil] result))
      (is (= (named-value ast) result)))))


(deftest over-arity-call-drops-extras-test
  (testing "a 2-arity closure called with 3 arguments drops the extra,
            matching engine/bind-params' zip-stops-at-shorter rule"
    (let [ast (app (conj-pair-ast) (lit 1) (lit 2) (lit 3))
          segment (into [[:closure 2 6]   ; 0: closure, body at 6
                         [:const 1]        ; 1
                         [:const 2]        ; 2
                         [:const 3]        ; 3
                         [:call 3 false]   ; 4: 3 args for arity 2
                         [:halt]]          ; 5
                        (conj-pair-segment 6))
          result (debruijn-value segment)]
      (is (= [1 2] result))
      (is (= (named-value ast) result)))))


;; =============================================================================
;; 5. if / :branch-false, both arms
;; =============================================================================

(deftest branch-true-arm-test
  (let [ast {:type :if, :test (lit true), :consequent (lit "T"),
             :alternate (lit "F")}
        segment [[:const true]        ; 0
                 [:branch-false 4]    ; 1
                 [:const "T"]         ; 2
                 [:jump 5]            ; 3
                 [:const "F"]         ; 4
                 [:halt]]             ; 5
        result (debruijn-value segment)]
    (is (= "T" result))
    (is (= (named-value ast) result))))


(deftest branch-false-arm-test
  (let [ast {:type :if, :test (lit false), :consequent (lit "T"),
             :alternate (lit "F")}
        segment [[:const false]       ; 0
                 [:branch-false 4]    ; 1
                 [:const "T"]         ; 2
                 [:jump 5]            ; 3
                 [:const "F"]         ; 4
                 [:halt]]             ; 5
        result (debruijn-value segment)]
    (is (= "F" result))
    (is (= (named-value ast) result))))


;; =============================================================================
;; 6. Store get/put
;; =============================================================================

(deftest store-get-put-sequenced-test
  (testing "a put, then a get of the same key from inside a called
            closure -- the named side sequences the two through
            application operand order (operator then operands, section 3),
            since the Universal AST has no explicit sequencing node"
    (let [ast (app (lambda '[ignored] {:type :vm/store-get, :key :b0/k})
                   {:type :vm/store-put, :key :b0/k, :val 123})
          segment [[:closure 1 4]              ; 0: body at 4
                   [:store-put :b0/k 123]      ; 1
                   [:call 1 false]             ; 2
                   [:halt]                     ; 3
                   [:store-get :b0/k]          ; 4: body, ignores its arg
                   [:return]]                  ; 5
          result (debruijn-value segment)]
      (is (= 123 result))
      (is (= (named-value ast) result)))))


;; =============================================================================
;; 7. Fresh-instance parity, not reuse
;; =============================================================================

(deftest fresh-instance-per-run-test
  (testing "every comparison above ran two independent fresh VMs (one
            named, one de Bruijn); this test only re-confirms that running
            the same de Bruijn segment twice from two fresh instances
            agrees with itself, the same self-parity shape
            `named-vm-self-parity-test` in the B0 contract test uses"
    (let [segment [[:const 7] [:halt]]]
      (is (= (debruijn-value segment) (debruijn-value segment) 7)))))


;; =============================================================================
;; 8. Unknown opcodes fail loudly
;; =============================================================================

(deftest unknown-opcode-test
  (testing "an opcode this dimension does not define is refused when the
            image loads: the stack loader validates (Rule R commit), so an
            undefined opcode never reaches a step"
    (let [segment [[:no-such-op]]]
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error :cljd Object)
            #"Invalid stack image: :mnemonic"
            (dvm/create-vm segment {:contract vm/stack-contract}))))))


;; =============================================================================
;; 9. environment is not implemented
;; =============================================================================

(deftest environment-not-implemented-test
  (testing "point 8: vm/IVMState/environment is not implemented at all in
            this phase, rather than returning the raw positional frame
            vector"
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (vm/environment (dvm/create-vm [[:halt]]
                                         {:contract vm/stack-contract}))))))
