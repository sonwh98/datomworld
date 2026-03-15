(ns yang.macro-test
  (:require
    [clojure.test :refer :all]
    [yang.clojure :as yang]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.macro :as macro]
    [yin.vm.semantic :as semantic]))


(defn compile-program-and-run
  ([forms] (compile-program-and-run forms {} {}))
  ([forms env] (compile-program-and-run forms env {}))
  ([forms env vm-opts]
   (-> (ast-walker/create-vm (merge {:env env} vm-opts))
       (vm/load-program (yang/compile-program forms))
       (vm/run)
       (vm/value))))


(defn compile-program-and-run-macros
  "Run a program through the semantic VM with bootstrap macro-registry.
   Prepends macro/stdlib-forms so defn is forced through the macro path."
  [forms]
  (let [ast    (yang/compile-program (concat macro/stdlib-forms forms))
        datoms (vec (vm/ast->datoms ast))
        root   (apply max (map first datoms))]
    (-> (semantic/create-vm {:macro-registry macro/default-macro-registry})
        (vm/load-program {:node root :datoms datoms})
        (vm/run)
        (vm/value))))


(deftest test-defn-macro
  (testing "defn defines a named function"
    (is (= 6
           (compile-program-and-run-macros
             '((defn double
                 [x]
                 (* x 2))
               (double 3))))))

  (testing "defn with multiple params"
    (is (= 7
           (compile-program-and-run-macros
             '((defn add
                 [x y]
                 (+ x y))
               (add 3 4))))))

  (testing "defn with multiple body forms (do desugaring)"
    (is (= 6
           (compile-program-and-run-macros
             '((defn f
                 [x]
                 (def y 1)
                 (+ x y))
               (f 5))))))

  (testing "defn recursive function"
    (is (= 120
           (compile-program-and-run-macros
             '((defn fact
                 [n]
                 (if (= n 0)
                   1
                   (* n (fact (- n 1)))))
               (fact 5))))))

  (testing "defn and def can coexist"
    (is (= 9
           (compile-program-and-run-macros
             '((def base 3)
               (defn square
                 [x]
                 (* x x))
               (square base))))))

  (testing "defn param name shadows outer macro name"
    ;; (defn 3) inside the body should call the param, not trigger macro-expand
    (is (= 3
           (compile-program-and-run-macros
             '((defn call
                 [defn]
                 (defn 3))
               (def id (fn [x] x))
               (call id))))))

  (testing "defn tail-recursive countdown (deep enough to overflow without TCO)"
    ;; 100,000 iterations: would stack-overflow without tail-call optimisation
    (is (= 0
           (compile-program-and-run-macros
             '((defn countdown
                 [n]
                 (if (= n 0)
                   0
                   (countdown (- n 1))))
               (countdown 100000))))))

  (testing "defn tail-recursive countdown preserves tail markers through a multi-form body"
    ;; Forces the stdlib defn macro path through yin/sequence-body
    ;; before the recursive tail call.
    (let [forms (concat macro/stdlib-forms
                        '((defn countdown
                            [n]
                            1
                            (if (= n 0)
                              0
                              (countdown (- n 1))))
                          (countdown 2)))
          [root datoms0] (vm/ast->datoms-with-root (yang/compile-program forms))
          {:keys [datoms]}
          (macro/expand-all datoms0 root macro/default-macro-registry
                            {:invoke-lambda
                             (fn [lambda-eid ctx]
                               (semantic/invoke-macro-lambda lambda-eid ctx datoms0))})
          {:keys [get-attr by-entity]} (vm/index-datoms datoms)
          countdown-calls
          (->> (keys by-entity)
               (keep (fn [eid]
                       (when (= :application (get-attr eid :yin/type))
                         (let [op (get-attr eid :yin/operator)]
                           (when (= 'countdown (get-attr op :yin/name))
                             {:eid eid :tail (get-attr eid :yin/tail?)})))))
               vec)]
      (is (= 2 (count countdown-calls)))
      (is (every? (comp true? :tail) countdown-calls)
          (str "expected all countdown call sites to be tail-marked, got " countdown-calls))))

  (testing "defn inside a lambda body (nested use via macro path)"
    (is (= 42
           (compile-program-and-run-macros
             '((def make-adder
                 (fn [n]
                   (defn adder
                     [x]
                     (+ x n))
                   adder))
               ((make-adder 2) 40))))))

  (testing "user (defmacro defn ...) overrides the native defn special case"
    ;; Reviewer finding: once (defmacro defn ...) is in scope, subsequent (defn ...)
    ;; must expand via the user macro, not the native compiler branch.
    ;; The macro returns 42 directly; (defn x [y] y) evaluates to 42 (no x binding).
    (is (= 42
           (compile-program-and-run-macros
             '((defmacro defn
                 [fn-name fn-params & body]
                 42)
               (defn x
                 [y]
                 y))))))

  (testing "user (defmacro defn ...) controls operand compilation too"
    ;; A user-defined defn macro should see its body operand as another macro call,
    ;; not as a hard-coded application compiled with built-in defn assumptions.
    (is (= :yin/macro-expand
           (compile-program-and-run-macros
             '((defmacro defn
                 [name bogus body]
                 (yin/get-attr body :yin/type))
               (defn outer
                 [defn]
                 (defn inner
                   []
                   1)))))))

  (testing "compile-program handles defn without any preamble"
    ;; Verify the public path works directly, not just via the test helper
    (let [ast    (yang/compile-program '((defn double
                                           [x]
                                           (* x 2)) (double 3)))
          datoms (vec (vm/ast->datoms ast))
          root   (apply max (map first datoms))]
      (is (= 6
             (-> (semantic/create-vm)
                 (vm/load-program {:node root :datoms datoms})
                 (vm/run)
                 (vm/value))))))

  (testing "compile-program handles nested defn without any preamble"
    (let [ast    (yang/compile-program
                   '((def make-adder
                       (fn [n]
                         (defn adder
                           [x]
                           (+ x n))
                         adder))
                     ((make-adder 2) 40)))
          datoms (vec (vm/ast->datoms ast))
          root   (apply max (map first datoms))]
      (is (= 42
             (-> (semantic/create-vm)
                 (vm/load-program {:node root :datoms datoms})
                 (vm/run)
                 (vm/value)))))))


(deftest test-nested-defn-ast-walker
  (testing "compile-program handles nested defn on the AST walker path"
    (is (= 42
           (compile-program-and-run
             '((def make-adder
                 (fn [n]
                   (defn adder
                     [x]
                     (+ x n))
                   adder))
               ((make-adder 2) 40)))))))


(deftest test-macro-builder-primitives
  (testing "yin/get-attr can inspect nodes built earlier in the same macro expansion"
    (is (= :lambda
           (compile-program-and-run-macros
             '((defmacro inspect-lambda
                 [ps]
                 (let [b (yin/sequence-body [])
                       l (yin/make-lambda ps b)]
                   (yin/get-attr l :yin/type)))
               (inspect-lambda [])))))))
