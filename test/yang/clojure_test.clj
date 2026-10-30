(ns yang.clojure-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [dao.stream :as stream]
            [dao.stream.observer :as observer]
            [yang.clojure :as yang]
            [yang.core.uast :as u]
            [yang.io :as yang-io]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.data :as data]
            [yin.vm.encoder :as encoder]
            [yin.vm.engine :as engine]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]
            [yin.vm.values :as values]))


;; v1 registered the stream module globally at load time; the v2 composition
;; registers it in the registry it hands the VM.
(def ^:private base-vm-opts
  {:modules (module/register-stream-module (module/default-registry))})


(defn compile-and-run
  ([form] (compile-and-run form {} {}))
  ([form env] (compile-and-run form env {}))
  ([form env vm-opts]
   (-> (tu/make-observer-session
         (tu/create-vm (merge base-vm-opts {:env env} vm-opts)))
       (tu/queue-ast! (yang/compile form))
       tu/run-session
       :consumer
       vm/value)))


(deftest reader-metadata-becomes-occurrence-side-tables
  (let [form (with-meta '(+ 1 2)
               {:file "sample.clj" :line 9 :column 3 :note :source-form})
        ast (yang/compile form)
        projected (encoder/project
                    (encoder/source-envelope :program "batch-1" [ast]))
        tree (first (:yin/batch projected))
        origin [:source :program "batch-1" 0]]
    (is (= {:file "sample.clj" :line 9 :column 3 :note :source-form}
           (meta ast)))
    (is (some #{[origin (:root tree) [] "sample.clj" 9 3]}
              (:yin/source-positions projected)))
    (is (some #{[origin (:root tree) [] :note :source-form]}
              (:yin/frontend-metadata projected)))))


(deftest indexing-reader-injects-nested-source-positions
  (let [ast (first (yang-io/compile-string "(+ x\n   (* x 2))"))]
    (is (= {:file "<string>" :line 1 :column 1
            :end-line 2 :end-column 12}
           (select-keys (meta ast)
                        [:file :line :column :end-line :end-column])))
    (is (= {:file "<string>" :line 2 :column 4
            :end-line 2 :end-column 11}
           (select-keys (meta (second (:operands ast)))
                        [:file :line :column :end-line :end-column])))
    (is (= {:file "<string>" :line 2 :column 5
            :end-line 2 :end-column 6}
           (select-keys (meta (:operator (second (:operands ast))))
                        [:file :line :column :end-line :end-column])))))


(defn compile-program-and-run
  ([forms] (compile-program-and-run forms {} {}))
  ([forms env] (compile-program-and-run forms env {}))
  ([forms env vm-opts]
   (-> (tu/make-observer-session
         (tu/create-vm (merge base-vm-opts {:env env} vm-opts)))
       (tu/queue-ast! (yang/compile-program forms))
       tu/run-session
       :consumer
       vm/value)))


(deftest test-compile-literals
  (testing "Compiling literal values"
    (testing "Numbers"
      (is (= {:type :literal, :value 42} (yang/compile 42)))
      (is (= {:type :literal, :value 3.14} (yang/compile 3.14)))
      (is (= {:type :literal, :value -10} (yang/compile -10))))
    (testing "Strings"
      (is (= {:type :literal, :value "hello"} (yang/compile "hello"))))
    (testing "Booleans"
      (is (= {:type :literal, :value true} (yang/compile true)))
      (is (= {:type :literal, :value false} (yang/compile false))))
    (testing "Nil" (is (= {:type :literal, :value nil} (yang/compile nil))))
    (testing "Keywords"
      (is (= {:type :literal, :value :foo} (yang/compile :foo))))
    (testing "Vectors"
      (is (= {:type :literal, :value [1 2 3]} (yang/compile [1 2 3]))))
    (testing "Maps"
      (is (= {:type :literal, :value {:a 1, :b 2}}
             (yang/compile {:a 1, :b 2}))))
    (testing "Sets"
      (is (= {:type :literal, :value #{1 2 3}}
             (yang/compile #{1 2 3}))))))


(deftest test-set-literal-round-trips
  ;; the semantic lane carries a set literal as plain data (vm/plain-data?
  ;; accepts collections of scalars) and the ast-walker returns it unchanged
  (is (= #{1 2 3} (compile-and-run #{1 2 3})))
  (is (= #{1 2 3} (compile-and-run '(quote #{1 2 3})))))


(deftest test-set-literal-row-lane
  ;; the row lane: equal sets under different orders mint one address (the
  ;; code-as-tuples rule that literal maps and sets hash order-insensitively)
  (is (= (get (encoder/project (encoder/source-envelope
                                 :program "batch-1" [(yang/compile #{1 2 3})]))
              :root)
         (get (encoder/project (encoder/source-envelope
                                 :program "batch-1" [(yang/compile #{3 2 1})]))
              :root)))
  ;; a set carrying a host function is not plain data and must be refused at
  ;; the row boundary, exactly as for vectors/maps
  (is (thrown? Exception
        (encoder/project
          (encoder/source-envelope
            :program "batch-2"
            [{:type :literal, :value #{clojure.core/+}}])))))


(deftest test-set-in-application-position
  ;; a set literal in operator position compiles (like the keyword/vector
  ;; precedent) and fails at run time, not compile time
  (is (thrown-with-msg? Exception #"apply"
        (compile-and-run '((quote #{1 2}) 1)))))


(deftest test-compile-variables
  (testing "Compiling variable references"
    (is (= {:type :variable, :name 'x} (yang/compile 'x)))
    (is (= {:type :variable, :name 'foo} (yang/compile 'foo)))))


(deftest test-compile-lambda
  (testing "Compiling lambda expressions"
    (testing "Simple lambda"
      (let [ast (yang/compile '(fn [x] x))]
        (is (= :lambda (:type ast)))
        (is (= ['x] (:params ast)))
        (is (= {:type :variable, :name 'x} (:body ast)))))
    (testing "Lambda with computation"
      (let [ast (yang/compile '(fn [x y] (+ x y)))]
        (is (= :lambda (:type ast)))
        (is (= ['x 'y] (:params ast)))
        (is (= :application (get-in ast [:body :type])))))))


(deftest test-compile-application
  (testing "Compiling function applications"
    (testing "Simple addition"
      (let [ast (yang/compile '(+ 1 2))]
        (is (= :application (:type ast)))
        (is (= {:type :variable, :name '+} (:operator ast)))
        (is (= [{:type :literal, :value 1} {:type :literal, :value 2}]
               (:operands ast)))))
    (testing "Nested application"
      (let [ast (yang/compile '(+ (* 2 3) 4))]
        (is (= :application (:type ast)))
        (is (= :application (get-in ast [:operands 0 :type])))))))


(deftest test-compile-dao-stream-apply-call
  (testing "Compiling dao.stream.apply/call special form"
    (let [ast (yang/compile '(dao.stream.apply/call :op/echo 1 2))]
      (is (= :dao.stream.apply/call (:type ast)))
      (is (= :op/echo (:op ast)))
      (is (= [{:type :literal, :value 1} {:type :literal, :value 2}]
             (:operands ast))))))


(deftest test-compile-if
  (testing "Compiling conditional expressions"
    (testing "If with alternate"
      (let [ast (yang/compile '(if true 1 2))]
        (is (= :if (:type ast)))
        (is (= {:type :literal, :value true} (:test ast)))
        (is (= {:type :literal, :value 1} (:consequent ast)))
        (is (= {:type :literal, :value 2} (:alternate ast)))))
    (testing "If without alternate"
      (let [ast (yang/compile '(if true 1))]
        (is (= :if (:type ast)))
        (is (= {:type :literal, :value nil} (:alternate ast)))))
    (testing "Explicit false alternate is a value, not an absence"
      (let [ast (yang/compile '(if true 1 false))]
        (is (= {:type :literal, :value false} (:alternate ast))))
      (is (false? (compile-and-run '(if false 1 false)))))))


(deftest test-dynamic-collection-literals
  ;; unquoted collections evaluate their elements like Clojure; constant
  ;; collections stay literal nodes; quoted collections stay data
  (testing "Constant collections stay literals"
    (is (= {:type :literal, :value [1 2]} (yang/compile '[1 2])))
    (is (= {:type :literal, :value '(1 x 2)} (yang/compile '(quote (1 x 2))))))
  (testing "Dynamic vectors lower to conj over []"
    (let [ast (yang/compile '(let [x 7] [x 2]))]
      (is (= :application (:type ast)))
      (is (= 'conj (get-in ast [:operator :body :operator :name])))
      (is (= {:type :literal, :value []} (first (get-in ast [:operator :body :operands])))))
    (is (= [7 2] (compile-and-run '(let [x 7] [x 2])))))
  (testing "Dynamic sets lower to conj over #{}"
    (is (= #{7 2} (compile-and-run '(let [x 7] #{x 2})))))
  (testing "Dynamic maps evaluate keys and values"
    (is (= {7 8} (compile-and-run '(let [x 7] {x (+ x 1)})))
        "map keys are evaluated, not taken as data"))
  (testing "Nested collections evaluate recursively"
    (is (= [7 [8] {:k #{9}}]
           (compile-and-run '(let [x 7] [x [(+ x 1)] {:k #{(+ x 2)}}])))))
  (testing "Quoted dynamic collections remain data"
    (is (= '[x y] (compile-and-run '(quote [x y])))))
  (testing "Empty lists are self-evaluating data, constant or nested"
    (is (= [()] (compile-and-run '[()])))
    (is (= [() 1] (compile-and-run '(let [x 1] [() x])))))
  (testing "Nested quote is stripped and the inner form evaluated"
    ;; (quote x) counts as constant only at top level; nested inside a
    ;; collection it must reach compile-form so the quote is stripped
    (is (= '[x] (compile-and-run (list 'let '[x 7] ['(quote x)]))))))


(deftest test-compile-let
  (testing "Compiling let bindings"
    (testing "Single binding"
      (let [ast (yang/compile '(let [x 1] x))]
        (is (= :application (:type ast)))
        (is (= :lambda (get-in ast [:operator :type])))
        (is (= ['x] (get-in ast [:operator :params])))
        (is (= [{:type :literal, :value 1}] (:operands ast)))))
    (testing "Multiple bindings"
      (let [ast (yang/compile '(let [x 1 y 2] (+ x y)))]
        (is (= :application (:type ast)))
        ;; Outer lambda for x
        (is (= :lambda (get-in ast [:operator :type])))
        (is (= ['x] (get-in ast [:operator :params])))
        ;; Inner lambda for y
        (is (= :application (get-in ast [:operator :body :type])))
        (is (= :lambda (get-in ast [:operator :body :operator :type])))
        (is (= ['y] (get-in ast [:operator :body :operator :params])))))))


(deftest test-compile-do
  (testing "Compiling do blocks"
    (testing "Empty do"
      (let [ast (yang/compile '(do))]
        (is (= {:type :literal, :value nil} ast))))
    (testing "Single expression do"
      (let [ast (yang/compile '(do 42))]
        (is (= {:type :literal, :value 42} ast))))
    (testing "Multiple expressions do"
      (let [ast (yang/compile '(do 1 2 3))]
        (is (= :application (:type ast)))
        ;; Should evaluate and discard intermediate results
        (is (= :lambda (get-in ast [:operator :type])))))))


(deftest test-compile-quote
  (testing "Compiling quoted forms"
    (is (= {:type :literal, :value 'x} (yang/compile '(quote x))))
    (is (= {:type :literal, :value '(+ 1 2)} (yang/compile '(quote (+ 1 2)))))))


(deftest test-end-to-end-compilation-and-execution
  (testing "Compile and execute with Yin VM"
    (testing "Simple literal" (is (= 42 (compile-and-run 42))))
    (testing "Variable lookup" (is (= 100 (compile-and-run 'x {'x 100}))))
    (testing "Simple addition" (is (= 3 (compile-and-run '(+ 1 2)))))
    (testing "Lambda application"
      (is (= 42 (compile-and-run '((fn [x] (* x 2)) 21)))))
    (testing "Nested application" (is (= 10 (compile-and-run '(+ (* 2 3) 4)))))
    (testing "Conditional - true branch"
      (is (= 1 (compile-and-run '(if true 1 2)))))
    (testing "Conditional - false branch"
      (is (= 2 (compile-and-run '(if false 1 2)))))
    (testing "Let binding" (is (= 8 (compile-and-run '(let [x 5] (+ x 3))))))
    (testing "Multiple let bindings"
      (is (= 6 (compile-and-run '(let [x 2 y 3] (* x y))))))
    (testing "Lambda with multiple parameters"
      (is (= 30 (compile-and-run '((fn [x y] (+ x y)) 10 20)))))
    (testing "Higher-order functions"
      (is (= 42 (compile-and-run '((fn [f x] (f x)) (fn [n] (* n 2)) 21)))))
    (testing "Nested let bindings"
      (is (= 3 (compile-and-run '(let [x 1] (let [y 2] (+ x y)))))))
    (testing "FFI call"
      (is (= 42
             (compile-and-run '(dao.stream.apply/call :op/echo 42)
                              {}
                              {:bridge {:op/echo identity}})))
      (is (nil? (compile-and-run
                  '(dao.stream.apply/call :clj/println "FFI hello world")
                  {}
                  {:bridge {:clj/println println}}))))
    (testing "Complex expression"
      (is
        (= 22
           (compile-and-run '(let
                               [double (fn [x] (* x 2)) triple (fn [x] (* x 3))]
                               (+ (double 5) (triple 4)))))))))


(deftest test-compile-errors
  (testing "Error handling for invalid forms"
    (testing "Unknown form type"
      (is (thrown? Exception (yang/compile (Object.)))))))


;; Stream compilation tests
;; Streams are now compiled as regular function applications and resolved
;; through the module system at runtime.

(deftest test-compile-stream-make
  (testing "Compiling stream/make as application"
    (testing "With buffer size"
      (let [ast (yang/compile '(stream/make 10))]
        (is (= :application (:type ast)))
        (is (= {:type :variable, :name 'stream/make} (:operator ast)))
        (is (= [{:type :literal, :value 10}] (:operands ast)))))
    (testing "Without buffer size"
      (let [ast (yang/compile '(stream/make))]
        (is (= :application (:type ast)))
        (is (= {:type :variable, :name 'stream/make} (:operator ast)))
        (is (= [] (:operands ast)))))))


(deftest test-compile-stream-put
  (testing "Compiling stream/put as application"
    (let [ast (yang/compile '(stream/put! s 42))]
      (is (= :application (:type ast)))
      (is (= {:type :variable, :name 'stream/put!} (:operator ast)))
      (is (= [{:type :variable, :name 's} {:type :literal, :value 42}]
             (:operands ast)))))
  (testing "Compiling >! as application"
    (let [ast (yang/compile '(>! s 42))]
      (is (= :application (:type ast)))
      (is (= {:type :variable, :name '>!} (:operator ast)))
      (is (= [{:type :variable, :name 's} {:type :literal, :value 42}]
             (:operands ast))))))


(deftest test-compile-stream-cursor
  (testing "Compiling stream/cursor as application"
    (let [ast (yang/compile '(stream/cursor s))]
      (is (= :application (:type ast)))
      (is (= {:type :variable, :name 'stream/cursor} (:operator ast)))
      (is (= [{:type :variable, :name 's}] (:operands ast))))))


(deftest test-compile-stream-next
  (testing "Compiling stream/next! as application"
    (let [ast (yang/compile '(stream/next! c))]
      (is (= :application (:type ast)))
      (is (= {:type :variable, :name 'stream/next!} (:operator ast)))
      (is (= [{:type :variable, :name 'c}] (:operands ast))))))


(deftest test-stream-end-to-end
  (testing "Compile and execute stream operations via module system"
    (testing "Simple put and cursor+next"
      (is
        (= 42
           (compile-and-run '(let
                               [s (stream/make 5) _ (stream/put! s 42) c
                                (stream/cursor s)]
                               (stream/next! c))))))
    (testing "Without buffer size (uses default)"
      (is
        (= 99
           (compile-and-run '(let
                               [s (stream/make) _ (stream/put! s 99) c
                                (stream/cursor s)]
                               (stream/next! c))))))
    (testing "Multiple values, cursor reads in order"
      (is
        (= 1
           (compile-and-run '(let
                               [s (stream/make 10) _ (stream/put! s 1) _
                                (stream/put! s 2) _ (stream/put! s 3) c
                                (stream/cursor s)]
                               (stream/next! c))))))))


(deftest test-lambda-with-multi-expression-body
  (testing "Lambda with multiple expressions in body"
    (let [result (compile-and-run '(fn [x] (+ x 1) (+ x 2) (* x 3)))]
      ;; Should create a closure
      (is (values/closure? result)))))


(deftest test-let-with-multi-expression-body
  (testing "Let with multiple expressions in body"
    (let [result (compile-and-run '(let [x 5] (+ x 1) (+ x 2) (* x 3)))]
      ;; Should evaluate to the last expression
      (is (= 15 result)))))


(deftest test-compile-program-top-level-recursion-arity
  (testing "Top-level def recursion preserves function arity"
    (testing "Two-argument recursive function"
      (is (= 3
             (compile-program-and-run
               '((def walk (fn [x max] (if (> x max) x (walk (+ x 1) max))))
                 (walk -2 2))))))
    (testing "Three-argument recursive function"
      (is
        (= 11
           (compile-program-and-run
             '((def add-until
                 (fn [x y max] (if (> x max) y (add-until (+ x 1) (+ y 1) max))))
               (add-until 0 5 5))))))))


(deftest test-compile-program-top-level-redefinition
  (testing "Top-level def redefinition updates subsequent lookups"
    (is (= 8
           (compile-program-and-run '((def f (fn [x] (* x x)))
                                      (def use-f (fn [x] (f x)))
                                      (def f (fn [x] (* x (* x x))))
                                      (use-f 2)))))))


(deftest test-nested-defn-ast-walker
  (testing "compile-program handles nested defn on the AST walker path"
    (is (= 42
           (compile-program-and-run
             '((def make-adder
                 (fn [n]
                   (defn adder
                     [x]
                     (+ x n)) adder))
               ((make-adder 2) 40)))))))


;; =============================================================================
;; Slice 3: sequential destructuring and defn headers
;; =============================================================================

(defn- rejected?
  [form]
  (try (yang/compile form) false
       (catch clojure.lang.ExceptionInfo _ true)))


(deftest test-sequential-destructuring-let
  (testing "positional, missing elements and nil input"
    (is (= [1 2] (compile-and-run '(let [[a b] [1 2]] [a b])))
        "elements bind in order")
    (is (= [1 nil] (compile-and-run '(let [[a b] [1]] [a b])))
        "missing element is nil")
    (is (= [nil nil] (compile-and-run '(let [[a b] nil] [a b])))
        "nil input binds nils"))
  (testing "& rest follows Clojure nil/sequence behavior"
    (is (= [1 [2 3]] (compile-and-run '(let [[a & r] [1 2 3]] [a r]))))
    (is (nil? (second (compile-and-run '(let [[a & r] [1]] [a r]))))
        "exhausted tail is nil")
    (is (nil? (second (compile-and-run '(let [[a & r] nil] [a r]))))))
  (testing ":as keeps the whole value"
    (is (= [1 [1 2]] (compile-and-run '(let [[a :as w] [1 2]] [a w]))))
    (is (= [1 [2] [1 2]]
           (compile-and-run '(let [[a & r :as w] [1 2]] [a r w])))))
  (testing "nested patterns, including a nested & pattern"
    (is (= [1 2 3] (compile-and-run '(let [[a [b c]] [1 [2 3]]] [a b c]))))
    (is (= [nil nil] (compile-and-run '(let [[[a] [b]] nil] [a b]))))
    (is (= [2 3] (compile-and-run '(let [[_ & [b c]] [1 2 3]] [b c])))))
  (testing "initializer is evaluated exactly once"
    (is (= [1 1] (compile-and-run
                   '(let [n 0
                          [a b] (do [(+ n 1) (+ n 1)])]
                      [a b])))))
  (testing "initializers see earlier bindings; shadowing"
    (is (= [3 4] (compile-and-run '(let [[a b] [1 2] [c d] [(+ a b) (+ b 2)]] [c d]))))
    (is (= [2 1] (compile-and-run '(let [x 1 y 2 [x y] [y x]] [x y]))))
    (is (= 5 (compile-and-run '(let [[a] [1] a (+ a 4)] a)))))
  (testing "user bindings named like core functions are not rewritten"
    (is (= [1 2] (compile-and-run '(let [[count nth] [1 2]] [count nth]))))
    (is (= [2 1] (compile-and-run '(let [nth 1 [a b] [nth 2]] [b a]))))
    (is (= 7 (compile-and-run '(let [nth 7] nth)))
        "ordinary use of a shadowed core name is untouched")
    (is (= 7 (compile-and-run '(let [first 7] first)))
        "binding a name the lowering uses is fine when nothing destructures"))
  (testing "a local binding the lowering would depend on is rejected, not captured"
    (is (rejected? '(let [first 1 [a] [2]] a)) "outer let local")
    (is (rejected? '(let [[a first] [1 2]] a)) "bound by the pattern itself")
    (is (rejected? '(fn [rest [a]] a)) "fn param")
    (is (rejected? '(let [empty? 1] (let [[a] [2]] a))) "enclosing scope")))


(deftest test-sequential-destructuring-fn-params
  (testing "fn params destructure after fresh simple binders"
    (is (= 3 (compile-and-run '((fn [[a b]] (+ a b)) [1 2]))))
    (is (= [1 2 [3]] (compile-and-run '((fn [x [y & z]] [x y z]) 1 [2 3]))))
    (is (= [nil nil] (compile-and-run '((fn [[a b]] [a b]) nil)))))
  (testing "argument order survives mixed simple and pattern params"
    (is (= [1 2 3 4] (compile-and-run '((fn [a [b c] d] [a b c d]) 1 [2 3] 4)))))
  (testing "defn params destructure"
    (is (= 6 (compile-program-and-run
               '((defn add3
                   [[a b] c]
                   (+ a b c))
                 (add3 [1 2] 3)))))))


(deftest test-destructuring-rejections
  (testing "malformed and unsupported patterns are rejected explicitly"
    (is (rejected? '(let [{:keys [a]} {:a 1}] a)) "map destructuring")
    (is (rejected? '(let [[a &] [1]] a)) "& without binder")
    (is (rejected? '(let [[a & b c] [1]] a)) "binder after rest")
    (is (rejected? '(let [[a & b & c] [1]] a)) "two &")
    (is (rejected? '(let [[a :as] [1]] a)) ":as without symbol")
    (is (rejected? '(let [[:as w a] [1]] a)) ":as not last")
    (is (rejected? '(let [[a :foo] [1]] a)) "unknown keyword")
    (is (rejected? '(let [[1] [1]] 1)) "literal target")
    (is (rejected? '(fn [a & r] a)) "variadic fn params")
    (is (rejected? '(fn [{:keys [a]}] a)) "map param")
    (is (rejected? '(let [dsg__0 1] dsg__0)) "reserved prefix")))


(deftest test-defn-headers
  (testing "docstring, attr-map and defn- compile and run"
    (is (= 3 (compile-program-and-run
               '((defn f
                   "adds one"
                   {:added 1}
                   [x]
                   (+ x 1))
                 (f 2)))))
    (is (= 3 (compile-program-and-run
               '((defn- g
                   [x]
                   (+ x 1))
                 (g 2)))))
    (is (= 3 (compile-program-and-run
               '((defn h
                   ([x] (+ x 1)))
                 (h 2))))
        "a single-arity list form is accepted"))
  (testing "header metadata is retained on the definition node"
    (let [ast (yang/compile '(defn f
                               "adds one"
                               {:added 1}
                               [x]
                               x))]
      (is (= "adds one" (:doc (meta ast))))
      (is (= 1 (:added (meta ast)))))
    (is (true? (:private (meta (yang/compile '(defn- g
                                                [x]
                                                x)))))))
  (testing "multi-arity is rejected explicitly"
    (is (rejected? '(defn f
                      ([x] x)
                      ([x y] y))))
    (is (rejected? '(defn f))))
  (testing "a user defmacro named defn still takes precedence"
    (is (some? (yang/compile-program
                 '((defmacro defn
                     [& xs]
                     xs) (defn f
                           [x]
                           x)))))))


(deftest test-defn-header-metadata-and-reservations
  (testing "a trailing attr-map after a single-arity list is merged"
    (is (= 1 (:added (meta (yang/compile '(defn f
                                            ([x] x)
                                            {:added 1})))))))
  (testing "an attr-map wins over the docstring, as in Clojure"
    (let [m (meta (yang/compile '(defn f
                                   "docstring"
                                   {:doc "attr-doc", :k 1}
                                   [x]
                                   x)))]
      (is (= "attr-doc" (:doc m)))
      (is (= 1 (:k m)))))
  (testing "an attr-map :private wins over defn-"
    (is (false? (:private (meta (yang/compile '(defn- g
                                                 {:private false} [x]
                                                 x))))))
    (is (true? (:private (meta (yang/compile '(defn- g
                                                [x]
                                                x)))))))
  (testing "the reserved prefix is refused on definition names"
    (is (rejected? '(def dsg__0 5)))
    (is (rejected? '(defn dsg__0
                      [x]
                      x)))
    (is (rejected? '(defn- dsg__0
                      [x]
                      x)))
    (is (rejected? '(defmacro dsg__0
                      [x]
                      x))))
  (testing "defmacro parses its header and requires a parameter vector"
    (is (= "doc" (:doc (meta (yang/compile '(defmacro m
                                              "doc"
                                              [x]
                                              x))))))
    (is (= '[x] (-> (yang/compile '(defmacro m
                                     "doc"
                                     [x]
                                     x))
                    :operands second :params)))
    (is (rejected? '(defmacro m
                      "doc")))
    (is (rejected? '(defmacro m
                      (x)
                      x)))))


(deftest test-destructuring-rejections-more
  (is (rejected? '(let [[a & :as w] [1]] a)) "& followed by :as")
  (is (rejected? '(let [[a :as x/w] [1]] a)) ":as with a qualified symbol")
  (is (rejected? '(let [[[a {:keys [b]}]] [[1 {:b 2}]]] a)) "nested map pattern")
  (is (rejected? '(fn [[a] rest] a)) "helper bound as a later fn param")
  (is (rejected? '(fn [[a] first] a)))
  (is (rejected? '(fn [[a] empty?] a))))


;; =============================================================================
;; Slice 3b: the argument-vector convention
;; =============================================================================

(def ^:private av-opts
  {:convention :arg-vector, :native (set (keys vm/primitives))})


(def ^:private av-modules
  (data/register-data-module (module/register-stream-module (module/default-registry))))


(defn- av-ast
  ([forms] (av-ast forms av-opts))
  ([forms opts] (yang/compile-program forms true opts)))


(defn- run-av
  [forms]
  (-> (tu/make-observer-session (tu/create-vm {:modules av-modules}))
      (tu/queue-ast! (av-ast forms))
      tu/run-session
      :consumer
      vm/value))


(defn- av-thrown
  "The ex-data of the exception running `forms` raises, or nil."
  [forms]
  (try (run-av forms) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))


(defn- rejected-av?
  [forms]
  (try (av-ast forms) false
       (catch clojure.lang.ExceptionInfo _ true)))


(deftest test-multi-arity-argument-vector
  (testing "arity dispatch, with a missing argument distinct from nil"
    (is (= [:none [:one nil] [1 2]]
           (run-av '[(defn f
                       ([] :none)
                       ([x] [:one x])
                       ([x y] [x y]))
                     [(f) (f nil) (f 1 2)]]))))
  (testing "a default-argument style arity calls the longer one"
    (is (= 11 (run-av '[(defn add
                          ([x] (add x 10))
                          ([x y] (+ x y)))
                        (add 1)]))))
  (testing "fn takes arities too"
    (is (= [1 3] (run-av '[(let [g (fn
                                     ([a] a)
                                     ([a b] (+ a b)))] [(g 1) (g 1 2)])]))))
  (testing "a wrong count is refused as an arity refusal, never nil-filled"
    (doseq [forms ['[(defn f
                       ([x] x)
                       ([x y] y)) (f)]
                   '[(defn f
                       ([x] x)
                       ([x y] y)) (f 1 2 3)]
                   '[(defn f
                       [x]
                       x) (f 1 2)]
                   '[(defn f
                       [x]
                       x) (f)]
                   '[((fn [x] x))]]]
      (is (= :arity (:yin.vm.data/reason (av-thrown forms))) (pr-str forms))))
  (testing "destructured params unpack positionally"
    (is (= [1 2 3] (run-av '[(defn f
                               ([[a b] c] [a b c]))
                             (f [1 2] 3)]))))
  (testing "dispatch is immune to user bindings named like core functions"
    (is (= [7 [7 1]] (run-av '[(defn f
                                 ([count] count)
                                 ([count nth] [count nth]))
                               [(f 7) (f 7 1)]])))))


(deftest test-argument-vector-operator-positions
  (testing "native names are called directly and keep the native AST"
    (is (= (yang/compile-form '(+ 1 2))
           (yang/compile-form '(+ 1 2) false
                              {::yang/convention
                               {:native (:native av-opts), :macros #{}}})))
    (is (= 3 (run-av '[(+ 1 2)]))))
  (testing "a native name in value position becomes a fresh adapter closure"
    (is (= [3 3 12]
           (run-av '[(defn ap
                       [f a b]
                       (f a b))
                     [(ap + 1 2) (let [g +] (g 1 2)) (ap * 3 4)]])))
    (is (= :arity (:yin.vm.data/reason (av-thrown '[(let [g first] (g 1 2 3 4 5))])))
        "an adapter admits the counts it was built for only"))
  (testing "a keyword in operator position is a static lookup"
    (is (= [1 nil 9] (run-av '[[(:a {:a 1}) (:b {:a 1}) (:b {:a 1} 9)]])))
    (is (= 'get (-> (av-ast '[(:a m)]) :operator :name)))
    (is (rejected-av? '[(:a)]))
    (is (rejected-av? '[(:a 1 2 3)]))
    (is (rejected-av? '[(let [get 1] (:a {:a 1}))])))
  (testing "a program-defined name is never native, even over a primitive name"
    (is (= :mine (run-av '[(defn first
                             [x]
                             :mine) (first [1 2])]))))
  (testing "a local shadows a native name"
    (is (= 5 (run-av '[(let [+ (fn [a b] 5)] (+ 1 2))]))))
  (testing "a user guest call is one tail application"
    (let [ast (av-ast '[(defn f
                          [x]
                          (g x))])
          lam (-> ast :operands second)
          call (-> lam :body :consequent :operator :body)]
      (is (= :application (:type call)))
      (is (true? (:tail? call)))
      (is (= 1 (count (:operands call))) "one argument vector")))
  (testing "a macro name keeps its call an ordinary application"
    (let [opts (assoc av-opts :macros #{'unless})
          call (av-ast '[(unless 1 2)] opts)]
      (is (= {:type :variable, :name 'unless} (:operator call)))
      (is (= 2 (count (:operands call)))))))


(deftest test-argument-vector-rejections
  (is (rejected-av? '[(defn f
                        [a & r]
                        a)]) "variadic stays rejected")
  (is (rejected-av? '[(fn [a & r] a)]))
  (is (rejected-av? '[(defn f
                        ([x] x)
                        ([y] y))]) "two arities with one count")
  (is (rejected-av? '[(let [conj 1] conj)])
      "the argument-vector constructor cannot be captured")
  (is (rejected-av? '[(fn [conj] conj)]))
  (is (rejected-av? '[(defn f
                        [dsg__av]
                        1)]) "reserved prefix")
  (is (thrown? clojure.lang.ExceptionInfo
        (yang/compile-program '[1] true {:convention :bogus})))
  (testing "the native path still rejects multi-arity"
    (is (rejected? '(defn f
                      ([x] x)
                      ([x y] y))))))


(deftest test-argument-vector-native-path-unchanged
  (let [forms '[(def f (fn [x] (* x x))) (defn g
                                           [a b]
                                           (+ a b)) (g (f 2) 1)]]
    (is (= (yang/compile-program forms)
           (yang/compile-program forms true nil)))
    (is (= 5 (compile-program-and-run forms)))))


(deftest test-argument-vector-semantic-lane-recursion
  (testing "a recursive portable function completes on the semantic VM"
    (let [session (tu/make-encoder-session
                    (semantic/create-vm {:make-stream tu/make-stream
                                         :capability-secret tu/secret
                                         :modules av-modules}))]
      (stream/append! (:program session)
                      (av-ast '[(defn cnt
                                  [n acc]
                                  (if (= n 0) acc (cnt (- n 1) (+ acc 1))))
                                (cnt 5000 0)]))
      (is (= 5000 (vm/value (:consumer (:evaluator (tu/run-encoder-session session)))))))))


;; =============================================================================
;; Slice 4: loop/recur through lexical self-application
;; =============================================================================

(defn- run-native
  [forms]
  (compile-program-and-run forms))


(deftest test-loop-recur-values
  (doseq [form ['(loop [x 5] x)
                '(loop [] 1)
                '(loop [a 1 b 2 n 0] (if (< n 3) (recur b a (+ n 1)) [a b]))
                '(loop [a 1 b (+ a 1)] [a b])
                '(loop [[x & more] [1 2 3] acc 0]
                   (if x (recur more (+ acc x)) acc))
                '(loop [[a b] [1 2] n 0] (if (< n 3) (recur [b a] (+ n 1)) [a b]))
                '(+ 1 (loop [n 4] (if (= n 0) 10 (recur (- n 1)))))
                '(loop [i 0 out []]
                   (if (< i 3)
                     (recur (+ i 1)
                            (loop [j 0 o out]
                              (if (< j 2) (recur (+ j 1) (conj o [i j])) o)))
                     out))
                '(loop [i 0] (if (< i 2)
                               (recur (+ i 1))
                               ((fn [n] (if (> n 0) (recur (- n 1)) :done)) 3)))
                '(let [x 10] (loop [n x] (if (= n 7) n (recur (- n 1)))))
                '(loop [n 3] (do (if (= n 0) :end (recur (- n 1)))))]]
    (is (= (eval form) (compile-and-run form)) (pr-str form))
    (is (= (eval form) (run-av [form])) (str "argument-vector: " (pr-str form)))))


(deftest test-function-level-recur
  (is (= 55 (run-native '[(defn sum-to
                            [n acc]
                            (if (= n 0) acc (recur (- n 1) (+ acc n))))
                          (sum-to 10 0)])))
  (is (= [2 1] (run-native '[(defn flip
                               [[a b] n]
                               (if (> n 0) (recur [b a] (- n 1)) [a b]))
                             (flip [1 2] 3)])))
  (is (= 4 (compile-and-run '((fn [n] (if (> n 3) n (recur (+ n 1)))) 0))))
  (testing "a recur-free fn keeps its plain lowering"
    (is (= {:type :lambda, :params '[x], :body {:type :variable, :name 'x}}
           (select-keys (yang/compile '(fn [x] x)) [:type :params :body]))))
  (testing "multi-arity: one recur target per arity branch"
    (is (= 5 (run-av '[(defn f
                         ([n] (f n 0))
                         ([n acc] (if (= n 0) acc (recur (- n 1) (+ acc 1)))))
                       (f 5)])))
    (is (= 6 (run-av '[(defn g
                         ([n] (if (> n 5) n (recur (+ n 1))))
                         ([a b] (+ a b)))
                       (g 0)])))
    (is (rejected-av? '[(defn h
                          ([n] (recur 1 2))
                          ([a b] a))]))))


(deftest test-non-tail-recursion-controls
  (is (= 120 (run-native '[(defn fact
                             [n]
                             (if (= n 0) 1 (* n (fact (- n 1))))) (fact 5)])))
  (is (= 120 (run-av '[(defn fact
                         [n]
                         (if (= n 0) 1 (* n (fact (- n 1))))) (fact 5)])))
  (is (= 6 (run-native '[(def walk (fn [x max] (if (> x max) x (walk (+ x 1) max))))
                         (walk -2 5)]))))


(deftest test-recur-rejections
  (testing "no target"
    (is (rejected? '(recur 1)))
    (is (rejected? '(let [x 1] (recur x))))
    (is (rejected? '(if true (recur) 1))))
  (testing "wrong operand count"
    (is (rejected? '(loop [a 1 b 2] (recur 1))))
    (is (rejected? '(loop [a 1] (recur 1 2))))
    (is (rejected? '(fn [a b] (recur 1))))
    (is (rejected? '(loop [] (recur 1)))))
  (testing "non-tail position"
    (is (rejected? '(loop [n 1] (+ 1 (recur n)))))
    (is (rejected? '(loop [n 1] (do (recur n) 2))))
    (is (rejected? '(loop [n 1] (if (recur n) 1 2))))
    (is (rejected? '(loop [n 1] (let [x (recur n)] x))))
    (is (rejected? '(loop [n 1] (recur (recur n)))))
    (is (rejected? '(loop [n (recur 1)] n))))
  (testing "a fn boundary resets the target"
    (is (rejected? '(loop [n 1] (fn [] (recur n)))))
    (is (rejected? '(loop [n 1] ((fn [] (recur n))))))
    (is (rejected? '(fn [a] (fn [] (recur a)))))
    (is (rejected-av? '[(loop [n 1] (fn [] (recur n)))])))
  (testing "malformed loop and reserved names"
    (is (rejected? '(loop [a] a)))
    (is (rejected? '(loop a a)))
    (is (rejected? '(loop [dsg__x 1] dsg__x)))
    (is (rejected? '(loop [{:keys [a]} {}] a)))
    (is (not (rejected? '(loop [] 1))))))


(deftest test-loop-recur-are-special-forms
  (is (yang/special-form? '(loop [] 1)))
  (is (yang/special-form? '(recur)))
  (is (nil? (ns-resolve 'yang.clojure 'Z-combinator)))
  (is (nil? (ns-resolve 'yang.clojure 'arity->z-combinator))))


(deftest test-nested-defs-refused-under-convention
  (testing "a nested def or defn dispatches against a name the program scan never saw"
    (is (rejected-av? '[(do (def a 1)) a]))
    (is (rejected-av? '[(defn f
                          []
                          (defn +
                            [x]
                            x))]))
    (is (rejected-av? '[(fn []
                          (defmacro m
                            []
                            1))]))
    (is (rejected-av? '[(if true (defn g
                                   []
                                   1) nil)])))
  (testing "top-level definitions are unaffected, and the native path is unchanged"
    (is (not (rejected-av? '[(def a 1) (defn f
                                         []
                                         a) (f)])))
    (is (= 1 (run-native '[(do (def a 1)) a])))))


(deftest test-defmacro-param-checks
  (is (rejected? '(defmacro m
                    [dsg__x]
                    1)))
  (is (rejected? '(defmacro m
                    [[a b]]
                    1)))
  (is (rejected? '(defmacro m
                    [& a b]
                    1)))
  (is (rejected? '(defmacro m
                    [&]
                    1)))
  (is (rejected? '(defmacro m
                    [a & b & c]
                    1)))
  (is (rejected? '(defmacro m
                    [:k]
                    1)))
  (is (not (rejected? '(defmacro m
                         [a & b]
                         1))))
  (is (not (rejected? '(defmacro defn
                         [& xs]
                         1)))))


;; --- tail-position audit -----------------------------------------------------

(defn- ast-nodes
  [ast]
  (tree-seq coll? #(if (map? %) (vals %) (seq %)) ast))


(defn- applications
  "[operator-name-or-:lambda tail?] of every application node."
  [ast]
  (->> (ast-nodes ast)
       (filter #(and (map? %) (= :application (:type %))))
       (map (fn [a]
              [(if (= :variable (get-in a [:operator :type]))
                 (get-in a [:operator :name])
                 (get-in a [:operator :type]))
               (boolean (:tail? a))]))))


(deftest test-loop-tail-audit
  (let [form '(loop [i 0] (if (< i 3) (recur (+ i 1)) i))]
    (testing "exactly the let chain, the initial application and the recur are tail"
      (is (= {[:lambda true] 2        ; initializer let, worker-binding let
              ['dsg__loop0 true] 2    ; initial application, recur
              ['< false] 1            ; the test
              ['+ false] 1}           ; a recur operand is never tail
             (frequencies (applications (yang/compile form))))))
    (testing "a loop in operand position is not itself tail; its recur still is"
      (is (= {[:lambda false] 2, ['dsg__loop0 false] 1, ['dsg__loop0 true] 1
              ['< false] 1, ['+ false] 1}
             (frequencies (applications (yang/compile-form form false {}))))))))


;; --- continuation-depth gates -----------------------------------------------

(defn- walker-depth
  "Length of the :next chain from the VM's k."
  [vm]
  (loop [k (vm/continuation vm), n 0]
    (if k (recur (:next k) (inc n)) n)))


(defn- semantic-depth
  [vm]
  (count (vm/continuation vm)))


(defn- stepper
  "A run function stepping the VM one instruction at a time, recording the
   maximum depth `depth-of` reports in :max-depth."
  [depth-of]
  (fn [vm]
    (loop [v vm, mx (depth-of vm)]
      (if (or (vm/halted? v) (vm/blocked? v))
        (assoc v :max-depth mx)
        (let [v' (vm/step v)]
          (recur v' (max mx (depth-of v'))))))))


(defn- walker-run
  "[value max-depth] of `ast` on the walker lane."
  [ast]
  (let [session (-> (tu/make-observer-session (tu/create-vm {:modules av-modules}))
                    (tu/queue-ast! ast))
        done (observer/run-on-stream
               session engine/ready-for-ingress?
               (vm/fresh-code-loader ast-walker/vm-load-program vm/ast-contract)
               (stepper walker-depth))]
    [(vm/value (:consumer done)) (:max-depth (:consumer done))]))


(defn- semantic-run
  "[value max-depth] of `ast` on the semantic lane."
  [ast]
  (let [session (tu/make-encoder-session
                  (semantic/create-vm {:make-stream tu/make-stream
                                       :capability-secret tu/secret
                                       :modules av-modules}))
        load-rows (vm/fresh-code-loader
                    (fn [machine rows stamp]
                      (vm/check-contract! vm/ast-contract stamp)
                      (semantic/load-vector
                        machine
                        (:vector (if (contains? rows :yin/batch)
                                   (linearize/project-rows
                                     (nth (:yin/batch rows) (:yin/root rows))
                                     (vm/source-origin (:yin/source-medium rows)
                                                       (:yin/batch-token rows)
                                                       (:yin/root rows)))
                                   (linearize/project-rows rows)))
                        vm/semantic-contract))
                    vm/ast-contract)]
    (stream/append! (:program session) ast)
    (let [encoded (assoc session :encoder (encoder/forward-on-stream (:encoder session)))
          done (observer/run-on-stream (:evaluator encoded) engine/ready-for-ingress?
                                       load-rows (stepper semantic-depth))]
      [(vm/value (:consumer done)) (:max-depth (:consumer done))])))


(defn- countdown-ast
  "A program counting down from n through a loop or a function-level recur,
   in tail position or as an operand of conj."
  [kind n shape]
  (let [form (walk/postwalk-replace
               {'N n}
               (case kind
                 :loop '(loop [i N] (if (= i 0) :done (recur (- i 1))))
                 :fn '((fn [i] (if (= i 0) :done (recur (- i 1)))) N)))]
    (yang/compile-program [(if (= shape :tail) form (list 'conj [] form))] true)))


(defn- strip-self-tail
  "Remove :tail? from every application of a worker self name."
  [ast]
  (walk/postwalk
    (fn [node]
      (if (and (map? node)
               (= :application (:type node))
               (= :variable (get-in node [:operator :type]))
               (re-find #"^dsg__loop" (str (get-in node [:operator :name]))))
        (dissoc node :tail?)
        node))
    ast))


(deftest test-continuation-depth-gates
  (doseq [[lane run-lane] [[:walker walker-run] [:semantic semantic-run]]
          kind [:loop :fn]
          shape [:tail :operand]]
    (let [run (fn [n] (run-lane (countdown-ast kind n shape)))
          [_ d0] (run 0)
          [v100 d100] (run 100)
          [v1000 d1000] (run 1000)
          label (str lane " " kind " " shape)]
      (is (= (if (= shape :tail) :done [:done]) v100 v1000) label)
      (is (= d100 d1000) (str label ": depth is constant in the iteration count"))
      (when (= lane :semantic)
        (is (<= d1000 (+ d0 2)) (str label ": at most 2 above the pre-loop depth")))))
  ;; The walker lane masks a missing flag (its depth stays flat either way),
  ;; so only the semantic lane can serve as the flag-sensitive control.
  (testing "negative control: without :tail? on the self-application depth grows"
    (let [run (fn [n] (semantic-run (strip-self-tail (countdown-ast :loop n :tail))))
          [_ d0] (run 0)
          [_ d100] (run 100)]
      (is (> d100 (+ d0 50)) "the semantic gate detects the missing flag"))))


;; =============================================================================
;; Slice 5: the outcome model
;; =============================================================================

(def ^:private effect-log (atom []))


(def ^:private outcome-modules
  (let [host (fn [name entries]
               (fn [registry]
                 (module/register-host-module
                   registry name
                   (into {} (map (fn [[op f _]] [op f])) entries)
                   (into {} (map (fn [[op _ p]] [op p])) entries))))]
    (-> av-modules
        ((host 'portable
               [['nth
                 (yang/outcome-adapter data/refusal-message (get data/data-module 'nth))
                 (get data/data-profiles 'nth)]]))
        ((host 'probe
               [['note
                 (fn [x] (swap! effect-log conj x) x)
                 (vm/primitive-profile 'note :pure [1] #{} :none)]])))))


(def ^:private outcome-opts
  {:convention :arg-vector, :outcomes true
   :native (set (keys vm/primitives))
   :adapters {'data/nth 'portable/nth}})


(defn- outcome-ast
  [forms]
  (yang/compile-program forms true outcome-opts))


(defn- run-outcome
  "The raw outcome the last form of `forms` returns."
  [forms]
  (reset! effect-log [])
  (-> (tu/make-observer-session (tu/create-vm {:modules outcome-modules}))
      (tu/queue-ast! (outcome-ast forms))
      tu/run-session
      :consumer
      vm/value))


(defn- run-profile-outcome
  [forms]
  (reset! effect-log [])
  (let [ast (walk/postwalk
              (fn [node]
                (if (= node (u/v 'profile-raise)) (u/v 'probe/note) node))
              (outcome-ast forms))]
    (-> (tu/make-observer-session (tu/create-vm {:modules outcome-modules}))
        (tu/queue-ast! ast)
        tu/run-session
        :consumer
        vm/value)))


(deftest test-outcome-success-and-throw
  (is (= [:yin.k/ok 2] (run-outcome '[(defn f
                                        [x]
                                        (+ x 1)) (f 1)])))
  (is (= [:yin.k/ok [:ok 1]] (run-outcome '[(defn f
                                              [x]
                                              [:ok x]) (f 1)]))
      "LaTTe's own [:ok ...] stays an ordinary value inside the payload")
  (is (= [:yin.k/error {:yin.k/message "boom", :yin.k/data {:x 1}}]
         (run-outcome '[(defn f
                          [x]
                          (throw (ex-info "boom" {:x x}))) (f 1)])))
  (testing "an untaken throw branch leaves the success outcome"
    (is (= [:yin.k/ok :survived]
           (run-outcome '[(defn f
                            [x]
                            (if x (throw (ex-info "t" {})) :survived))
                          (f false)])))))


(deftest test-common-call-descriptor-publication
  (is (= [:yin.k/ok [:function true 2 3]]
         (run-outcome '[(defn f
                          [x]
                          (+ x 1))
                        [(get f :clj/type)
                         (data/callable? (yang.call/entry f))
                         (f 1)
                         (let [plain +] (plain 1 2))]])))
  (testing "condition-shaped return data is still a successful value"
    (let [value {:yang.call/reason :yang.call/thrown
                 :yang.call/value :ordinary-data}]
      (is (= [:yin.k/ok value]
             (run-outcome [(list 'defn 'f [] value) '(f)]))))))


(deftest test-common-call-hand-constructed-descriptor
  (testing "explicit UAST/profile construction admits a foreign body"
    ;; The constructor runs inside the compiled profile's private bindings.
    ;; No Clojure source map receives admission from its entry spelling.
    (let [descriptor (#'yang/admit-call-descriptor
                      (u/sexp->uast
                        '(assoc {} :foreign/type :function
                                :yang.call/entry
                                (fn [record] (get record :yang.call/positional))))
                      false)]
      (doseq [[form expected] [['(profile-function 40 2) [:yin.k/ok [40 2]]]
                               ['[(profile-function 40 2)] [:yin.k/ok [[40 2]]]]]
              run [walker-run semantic-run]]
        (let [ast (walk/postwalk
                    (fn [node]
                      (if (= node (u/v 'profile-function)) descriptor node))
                    (outcome-ast [form]))]
          (is (= expected (first (run ast)))))))))


(deftest test-common-call-profile-eligibility
  (doseq [eligible? [true false]
          run [walker-run semantic-run]]
    (let [descriptor (#'yang/admit-call-descriptor
                      (u/app (u/v 'assoc) (u/lit {})
                             (u/lit :yang.call/entry) (u/lam ['record] (u/lit :called))
                             (u/lit :yang.call/eligible?) (u/lam ['callee] (u/lit eligible?)))
                      false)
          profile-ast (fn [form]
                        (walk/postwalk
                          (fn [node]
                            (cond (= node (u/v 'profile-function)) descriptor
                                  (= node (u/v 'profile-raise)) (u/lam ['condition]
                                                                      (u/v 'condition))
                                  :else node))
                          (outcome-ast [form])))]
      (is (= [:yin.k/ok eligible?]
             (first (run (profile-ast '(yang.call/eligible? profile-function))))))
      (doseq [form '[(profile-function)
                     (yang.call/invoke profile-function [] [] profile-raise)
                     (yang.call/invoke-with profile-function [] [] profile-raise {})]]
        (let [result (first (run (profile-ast form)))]
          (if eligible?
            (is (= [:yin.k/ok :called] result))
            (is (= :yang.call/not-callable (:yang.call/reason (second result))))))))))


(deftest test-common-call-invocation-source-admission
  (doseq [invoke-form '[(yang.call/invoke h [21] [] profile-raise)
                        (yang.call/invoke-with h [21] [] profile-raise {})]
          entry-form '[(get f :yang.call/entry) 5 (fn [invocation] :forged)]]
    (let [forged (list 'assoc {} :yang.call/entry entry-form)
          result (run-profile-outcome
                   ['(defn f
                       [x]
                       (+ x 21))
                    (list 'let ['h forged]
                          (list 'get invoke-form :yang.call/reason))])]
      (is (= [:yin.k/ok :yang.call/not-callable] result)
          (pr-str invoke-form))))
  (is (= [:yin.k/ok [42 42]]
         (run-profile-outcome
           '[(defn f
               [x]
               (+ x 21))
             [(yang.call/invoke f [21] [] profile-raise)
              (yang.call/invoke-with f [21] [] profile-raise {})]]))))


(deftest test-common-call-invocation-operand-order
  (with-redefs [av-modules outcome-modules]
    (doseq [run [walker-run semantic-run]
            [operator options expected] '[[yang.call/invoke nil [[21] []]]
                                          [yang.call/invoke-with {} [[21] [] {}]]]]
      (doseq [admitted? [false true]]
        (let [callee (if admitted? 'f 5)
              invoke (if options
                       (list operator callee '(probe/note [21]) '(probe/note [])
                             'profile-raise (list 'probe/note options))
                       (list operator callee '(probe/note [21]) '(probe/note [])
                             'profile-raise))
              ast (walk/postwalk
                    (fn [node]
                      (if (= node (u/v 'profile-raise))
                        (u/lam ['condition] (u/lit :refused))
                        node))
                    (outcome-ast (if admitted?
                                   ['(defn f [x] (+ x 21)) invoke]
                                   [invoke])))
              _ (reset! effect-log [])
              [result] (run ast)]
          (is (= expected @effect-log) (str operator " admitted=" admitted?))
          (when admitted?
            (is (= [:yin.k/ok 42] result) (str operator))))))))


(deftest test-common-call-shadowed-helpers
  (doseq [binding '[get = assoc empty?]]
    (testing (str "construction and invocation with a local " binding)
      (is (= [:yin.k/ok 21]
             (run-outcome [(list 'let [binding 5]
                                 '(let [f (fn [x] x)] (f 21)))]))))
    (testing (str "a later source definition of " binding)
      (is (= [:yin.k/ok 21]
             (run-outcome [(list 'def binding 5) '(defn f
                                                    [x]
                                                    x) '(f 21)]))))
    (testing (str "raise through a parameter named " binding)
      (is (= [:yin.k/error {:yin.k/message "boom", :yin.k/data {:x 21}}]
             (run-outcome
               ['(defn g
                   []
                   (throw (ex-info "boom" {:x 21})))
                (list 'defn 'f [binding] '(g))
                '(+ 1 (f 5))])))))
  (is (= [:yin.k/ok 1]
         (run-outcome '[(def f (let [get 5] (fn [x] x))) (f 1)]))))


(deftest test-common-call-source-admission
  (doseq [form '[(let [x 5] (x 1))
                 (let [x {:ordinary :map}] (x 1))
                 (let [x {:yang.call/entry 5}] (x 1))
                 (let [x {:py/type :function :code :legacy}] (x 1))
                 (let [x {:yang.call/entry 5}] ((if true x x) 1))
                 (let [x {:yang.call/entry (yang.call/entry f)}] (x 1))
                 (let [x (assoc {} :yang.call/entry (get f :yang.call/entry))] (x 1))
                 (let [x (assoc f :extra :forged)] (x 1))
                 (let [x (assoc f :yang.call/entry (yang.call/entry g))] (x 1))
                 (let [x (assoc {} :yang.call/entry (yang.call/entry f)
                                :clj/admission (get f :clj/admission))] (x 1))
                 (let [x {:yang.call/entry (yang.call/entry f)
                          :clj/admission (fn [x] true)}] (x 1))]]
    (doseq [wrap [identity #(list '+ 1 %)]]
      (let [result (run-outcome ['(defn f
                                    [x]
                                    x)
                                 '(defn g
                                    [x]
                                    (+ x 1)) (wrap form)])]
        (is (= :yin.k/error (first result)) (pr-str form))
        (is (= :yang.call/not-callable (:yang.call/reason (second result)))
            (pr-str form)))))
  (is (= [:yin.k/ok [false false true 21]]
         (run-outcome '[(defn f
                          [x]
                          x)
                        [(yang.call/eligible? {:yang.call/entry (yang.call/entry f)})
                         (yang.call/eligible? (assoc f :extra :forged))
                         (yang.call/eligible? f)
                         (f 21)]]))))


(deftest test-common-call-invalid-continuation-refuses
  (let [ast (walk/postwalk
              (fn [node]
                (if (= node u/capture) (u/lit :invalid-continuation) node))
              (outcome-ast '[(defn f
                               []
                               1) (+ 1 (f))]))]
    (doseq [run [walker-run semantic-run]]
      (let [result (first (run ast))]
        (is (= :yin.k/error (first result)))
        (is (= {:yang.call/reason :yang.call/invalid-invocation
                :yang.call/data {:yang.call/reason :missing-raise-exit}}
               (second result)))))))


(deftest test-common-call-rejects-named-arguments
  (let [forms '[(defn f
                  [x]
                  x)
                (let [condition (yang.call/invoke f [] [["x" 1]] profile-raise)
                      value (get condition :yang.call/value)]
                  [(get condition :yang.call/reason)
                   (get value :yin.k/message)
                   (get (get value :yin.k/data) :yang.call/named)])]
        raw-raise (fn [node]
                    (if (= node (u/v 'profile-raise)) (u/v 'probe/note) node))
        ast (walk/postwalk raw-raise (outcome-ast forms))
        result (-> (tu/make-observer-session (tu/create-vm {:modules outcome-modules}))
                   (tu/queue-ast! ast)
                   tu/run-session
                   :consumer
                   vm/value)]
    (is (= [:yin.k/ok
            [:yang.call/thrown
             "Clojure functions do not accept named arguments"
             [["x" 1]]]]
           result))))


(deftest test-outcome-safe-get
  (let [forms '[(defn safe-get
                  [coll key]
                  (let [val (get coll key)]
                    (if val val (throw (ex-info "No such value in collection" {:coll coll, :key key})))))]]
    (is (= [:yin.k/ok 1] (run-outcome (conj forms '(safe-get {:a 1} :a)))))
    (is (= [:yin.k/error {:yin.k/message "No such value in collection"
                          :yin.k/data {:coll {:a 1}, :key :b}}]
           (run-outcome (conj forms '(safe-get {:a 1} :b)))))))


(deftest test-outcome-nested-call-operand
  (let [defs '[(defn g
                 [x]
                 (if x x (throw (ex-info "g failed" {:x x}))))
               (defn h
                 [x]
                 x)]
        failed [:yin.k/error {:yin.k/message "g failed", :yin.k/data {:x nil}}]]
    (testing "an error in an operand of a nested call ends the caller"
      (is (= failed (run-outcome (into defs '[(defn f
                                                [x]
                                                (+ 1 (g x))) (f nil)]))))
      (is (= [:yin.k/ok 6] (run-outcome (into defs '[(defn f
                                                       [x]
                                                       (+ 1 (g x))) (f 5)]))))
      (is (= failed (run-outcome (into defs '[(defn f
                                                [x]
                                                (h (h (g x)))) (f nil)])))
          "through two enclosing calls"))
    (testing "operands evaluate left to right and stop at the first failure"
      (is (= [failed [:a]]
             [(run-outcome (into defs '[(defn f
                                          [x]
                                          [(probe/note :a) (g x) (probe/note :b)])
                                        (f nil)]))
              @effect-log])))))


(deftest test-outcome-mid-do-stops-later-effects
  (let [forms '[(defn boom
                  []
                  (throw (ex-info "stop" {})))
                (defn run
                  []
                  (do (probe/note :before) (boom) (probe/note :after) :done))
                (run)]]
    (is (= [[:yin.k/error {:yin.k/message "stop", :yin.k/data {}}] [:before]]
           [(run-outcome forms) @effect-log]))
    (is (= [[:yin.k/ok :done] [:before :after]]
           [(run-outcome (assoc forms 0 '(defn boom
                                           []
                                           nil))) @effect-log]))))


(deftest test-outcome-loop-and-control
  (testing "loop and function-level recur return the outcome of their exit"
    (is (= [:yin.k/ok 55]
           (run-outcome '[(defn sum
                            [n acc]
                            (if (= n 0) acc (recur (- n 1) (+ acc n)))) (sum 10 0)])))
    (is (= [:yin.k/ok :found]
           (run-outcome '[(defn find-it
                            [xs]
                            (loop [xs xs] (if (= (first xs) 3) :found (recur (rest xs)))))
                          (find-it [1 2 3])])))
    (is (= [:yin.k/error {:yin.k/message "bad 3", :yin.k/data {}}]
           (run-outcome '[(defn chk
                            [n]
                            (if (= n 3) (throw (ex-info "bad 3" {})) n))
                          (defn all
                            [n]
                            (loop [i 0] (if (< i n) (do (chk i) (recur (+ i 1))) :ok)))
                          (all 5)]))))
  (testing "and / or / let / destructuring around may-fail calls"
    (is (= [:yin.k/ok [2 1]]
           (run-outcome '[(defn id
                            [x]
                            x)
                          (defn f
                            [[a b]]
                            (let [[c d] [(id b) (id a)]] [c d]))
                          (f [1 2])])))
    (is (= [:yin.k/ok :y]
           (run-outcome '[(defn id
                            [x]
                            x) (defn f
                                 [a]
                                 (or (id nil) (and (id a) :y))) (f 1)])))))


(deftest test-outcome-arity-names-the-function
  (let [forms '[(defn f
                  ([x] x)
                  ([x y] y))]]
    (is (= [:yin.k/error {:yin.vm.data/reason :arity, :yin.k/fn 'f, :yin.k/argc 3}]
           (run-outcome (conj forms '(f 1 2 3)))))
    (is (= [:yin.k/ok 2] (run-outcome (conj forms '(f 1 2)))))))


(deftest test-outcome-primitive-adapter
  (testing "an adapted refusal is an error outcome with the refusal's op and reason"
    (let [out (run-outcome '[(data/nth [1 2] 5)])]
      (is (= :yin.k/error (first out)))
      (is (= 'nth (:yin.vm.data/op (second out))))
      (is (= :out-of-range (:yin.vm.data/reason (second out))))))
  (is (= [:yin.k/ok 2] (run-outcome '[(data/nth [1 2] 1)])))
  (testing "the adapter catches only the module's own refusal"
    (let [other (yang/outcome-adapter
                  data/refusal-message
                  (fn [] (throw (ex-info "other" {:yin.vm.data/op 'x, :yin.vm.data/reason :y}))))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"other" (other))))
    (let [npe (yang/outcome-adapter data/refusal-message
                                    (fn [] (throw (NullPointerException.))))]
      (is (thrown? NullPointerException (npe))))))


(deftest test-outcome-native-path-emits-none
  (let [forms '[(defn f
                  [x]
                  (throw (ex-info "t" {}))) (defn g
                                              [x]
                                              (+ x 1)) (g 1)]]
    (is (not-any? #(= :yin.k/ok %) (ast-nodes (yang/compile-program forms true av-opts)))
        "the convention without :outcomes carries no outcome tags")
    (is (not-any? #(= :yin.k/ok %) (ast-nodes (yang/compile-program '[(defn g
                                                                        [x]
                                                                        (+ x 1)) (g 1)]))))
    (is (some #(= :yin.k/ok %) (ast-nodes (yang/compile-program forms true outcome-opts))))))


(deftest test-outcome-common-call-depth
  (let [prog (fn [defs n] (yang/compile-program (conj defs (list 'cnt n)) true outcome-opts))
        three '[(defn c
                  [n]
                  (if (= n 0) :done (a (- n 1))))
                (defn b
                  [n]
                  (c n))
                (defn a
                  [n]
                  (b n))
                (defn cnt
                  [n]
                  (a n))]
        direct '[(defn cnt
                   [n]
                   (if (= n 0) :done (cnt (- n 1))))]
        wrapped '[(defn cnt
                    [n]
                    (if (= n 0) 0 (+ 1 (cnt (- n 1)))))]]
    (doseq [[label defs] [["self tail call" direct] ["three tail calls" three]]
            [lane run-lane] [[:walker walker-run] [:semantic semantic-run]]]
      (let [[v0 d0] (run-lane (prog defs 0))
            [v100 d100] (run-lane (prog defs 100))
            [v1000 d1000] (run-lane (prog defs 1000))
            tag (str lane " " label)]
        (is (= [:yin.k/ok :done] v0 v100 v1000) tag)
        (is (= d100 d1000)
            (str tag ": depth is constant in the iteration count"))
        (is (<= d1000 (+ d0 2))
            (str tag ": at most two frames above the initial call"))))
    (testing "a non-tail unwrap also grows with the iteration count"
      (let [[_ d10] (semantic-run (prog wrapped 10))
            [_ d100] (semantic-run (prog wrapped 100))]
        (is (> d100 (+ d10 50)))))))


(deftest test-outcome-refusals
  (let [opts (assoc outcome-opts :macros #{'unless})
        refused (fn [forms]
                  (try (yang/compile-program forms true opts) nil
                       (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (testing "a macro call with an operand that may fail is refused, naming the macro"
      (is (re-find #"Macro call unless"
                   (refused '[(defn g
                                [x]
                                x) (defn f
                                     [x]
                                     (unless (g x) 1 :else 2))])))
      (is (re-find #"Macro call unless"
                   (refused '[(defn g
                                [x]
                                x) (defn f
                                     [x]
                                     (+ 1 (unless :else (g x))))]))
          "in operand position too"))
    (testing "a macro call whose operands cannot fail is still opaque and accepted"
      (is (nil? (refused '[(defn f
                             [x]
                             (unless (= x 1) 1 :else 2))]))))
    (testing "cond is a compiler form: a may-fail operand is lowered, not refused"
      (is (nil? (refused '[(defn g
                             [x]
                             x) (defn f
                                  [x]
                                  (cond (g x) 1 :else 2))]))))
    (testing "a non-final top-level form that may fail is refused"
      (is (re-find #"non-final top-level"
                   (refused '[(defn g
                                [x]
                                x) (g 1) (g 2)])))
      (is (nil? (refused '[(defn g
                             [x]
                             x) (g 1)])) "the final form is the harness's")
      (is (nil? (refused '[(defn g
                             [x]
                             x) (+ 1 2) (g 2)])) "a non-failing form is fine"))))


(deftest test-program-pre-pass
  (testing "a reserved-prefix symbol is refused in any position"
    (doseq [forms ['[(def x dsg__y)]
                   '[(defn f
                       []
                       (g dsg__av))]
                   '[(let [a dsg__z] a)]
                   '[(def dsg__w 1)]
                   '[[dsg__q]]
                   '[(fn [x] {:k dsg__v})]]]
      (is (thrown? clojure.lang.ExceptionInfo (yang/compile-program forms)) (pr-str forms))))
  (testing "bare loop and recur are refused outside operator position"
    (doseq [forms ['[(map recur [1])]
                   '[[loop]]
                   '[(let [loop 1] loop)]
                   '[(fn [recur] recur)]
                   '[(def x loop)]]]
      (is (thrown? clojure.lang.ExceptionInfo (yang/compile-program forms)) (pr-str forms))))
  (testing "quoted data is exempt and operator position stays legal"
    (is (= '(loop recur dsg__x) (compile-program-and-run '[(quote (loop recur dsg__x))])))
    (is (some? (yang/compile-program '[(loop [i 0] (if (< i 2) (recur (+ i 1)) i))])))))


(deftest test-recur-count-message-names-its-target
  (letfn [(message
            [thunk]
            (try (thunk) nil
                 (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (is (re-find #"loop bindings" (message #(yang/compile '(loop [a 1 b 2] (recur 1))))))
    (is (re-find #"fn parameters" (message #(yang/compile '(fn [a b] (recur 1))))))
    (is (re-find #"fn parameters"
                 (message #(av-ast '[(defn h
                                       ([n] (recur 1 2))
                                       ([a b] a))]))))))


;; =============================================================================
;; case and the standard control forms (slice 6)
;; =============================================================================

(defn- fails?
  [form]
  (try (compile-and-run form) false
       (catch Throwable _ true)))


(defn- rejects?
  [form]
  (try (yang/compile form) false
       (catch clojure.lang.ExceptionInfo _ true)))


(defn- f-calls
  [ast]
  (filter #(and (map? %)
                (= :application (:type %))
                (= 'f (get-in % [:operator :name])))
          (ast-nodes ast)))


(deftest test-case-constants-and-default
  (testing "number, keyword, string, nil and boolean constants"
    (is (= :a (compile-and-run '(case 1 1 :a 2 :b :z))))
    (is (= :b (compile-and-run '(case :k :j :a :k :b :z))))
    (is (= :b (compile-and-run '(case "s" "t" :a "s" :b :z))))
    (is (= :b (compile-and-run '(case nil 1 :a nil :b :z))))
    (is (= :a (compile-and-run '(case true true :a false :b :z)))))
  (testing "symbol constants are data, never variable references"
    (is (= :pi (compile-and-run '(case (quote Π) λ :lam Π :pi :z))))
    (is (= :z (compile-and-run '(case (quote q) λ :lam Π :pi :z)))))
  (testing "a symbol constant does not read a same-named local"
    (is (= :z (compile-and-run '(let [λ 1] (case λ x :x :z))))))
  (testing "grouped constants"
    (is (= :ab (compile-and-run '(case :b (:a :b) :ab :c :c :z))))
    (is (= :c (compile-and-run '(case :c (:a :b) :ab :c :c :z)))))
  (testing "default taken when nothing matches"
    (is (= :z (compile-and-run '(case 9 1 :a 2 :b :z)))))
  (testing "missing default fails on no match and succeeds on a match"
    (is (= :a (compile-and-run '(case 1 1 :a 2 :b))))
    (is (fails? '(case 9 1 :a 2 :b))))
  (testing "only default"
    (is (= :z (compile-and-run '(case 1 :z)))))
  (testing "duplicate constants are rejected at compile time"
    (is (rejects? '(case 1 1 :a 1 :b :z)))
    (is (rejects? '(case 1 1 :a (2 1) :b :z))))
  (testing "malformed case"
    (is (rejects? '(case)))))


(deftest test-case-evaluates-once
  (testing "the scrutinee is evaluated exactly once"
    (let [n (atom 2)]
      (is (= :c (compile-and-run
                  '(case (dao.stream.apply/call :op/tick) 1 :a 2 :b 3 :c :z)
                  {}
                  {:bridge {:op/tick #(swap! n inc)}}))
          "a re-evaluating lowering would see 3, 4, 5 and fall to :z")
      (is (= 3 @n) "one evaluation")))
  (testing "only the selected branch evaluates"
    (let [hits (atom [])]
      (is (= 2 (compile-and-run
                 '(case 2
                    1 (dao.stream.apply/call :op/hit 1)
                    2 (dao.stream.apply/call :op/hit 2)
                    (dao.stream.apply/call :op/hit 3))
                 {}
                 {:bridge {:op/hit #(do (swap! hits conj %) %)}})))
      (is (= [2] @hits)))))


(deftest test-case-tail-position
  (testing "the selected branches compile with the form's tail flag"
    (doseq [tail? [true false]]
      (let [calls (f-calls (yang/compile-form '(case x 1 (f 1) 2 (f 2) (f 3))
                                              tail? {}))]
        (is (= 3 (count calls)))
        (is (every? #(= tail? (boolean (:tail? %))) calls) (str "tail? " tail?))))))


(deftest test-control-forms
  (testing "cond"
    (is (nil? (compile-and-run '(cond))))
    (is (= :b (compile-and-run '(cond false :a true :b :else :c))))
    (is (= :c (compile-and-run '(cond false :a nil :b :else :c))))
    (is (nil? (compile-and-run '(cond false :a))))
    (is (rejects? '(cond true)))
    (let [hits (atom [])]
      (compile-and-run '(cond false (dao.stream.apply/call :op/hit 1)
                              true (dao.stream.apply/call :op/hit 2)
                              true (dao.stream.apply/call :op/hit 3))
                       {} {:bridge {:op/hit #(swap! hits conj %)}})
      (is (= [2] @hits))))
  (testing "when"
    (is (= 3 (compile-and-run '(when true 1 2 3))))
    (is (nil? (compile-and-run '(when false 1))))
    (is (nil? (compile-and-run '(when true)))))
  (testing "if-let"
    (is (= 6 (compile-and-run '(if-let [x 5] (+ x 1) 0))))
    (is (= 0 (compile-and-run '(if-let [x nil] (+ x 1) 0))))
    (is (= 0 (compile-and-run '(if-let [x false] 1 0))))
    (is (nil? (compile-and-run '(if-let [x nil] 1))))
    (is (= 2 (compile-and-run '(if-let [[a b] [1 2]] b 0))))
    (is (= 7 (compile-and-run '(let [x 7] (if-let [y nil] 0 x))))
        "else sees the outer scope")
    (let [n (atom 0)]
      (compile-and-run '(if-let [x (dao.stream.apply/call :op/tick)] x 0)
                       {} {:bridge {:op/tick #(swap! n inc)}})
      (is (= 1 @n))))
  (testing "when-let"
    (is (= 6 (compile-and-run '(when-let [x 5] 1 (+ x 1)))))
    (is (nil? (compile-and-run '(when-let [x nil] 1)))))
  (testing "if-some keeps false"
    (is (= :some (compile-and-run '(if-some [x false] :some :none))))
    (is (= :none (compile-and-run '(if-some [x nil] :some :none))))
    (is (nil? (compile-and-run '(if-some [x nil] :some))))
    (is (= 1 (compile-and-run '(when-some [x 1] x))))
    (let [n (atom 0)]
      (compile-and-run '(if-some [x (dao.stream.apply/call :op/tick)] x 0)
                       {} {:bridge {:op/tick #(swap! n inc)}})
      (is (= 1 @n))))
  (testing "a binding vector must hold one binding"
    (is (rejects? '(if-let [x 1 y 2] x 0))))
  (testing "tail position passes through the expansion"
    (doseq [form ['(cond t (f 1) true (f 2)) '(when t (f 1))
                  '(if-let [a t] (f a) (f 2)) '(if-some [a t] (f a) (f 2))
                  '(when-let [a t] (f a))]
            tail? [true false]]
      (let [calls (f-calls (yang/compile-form form tail? {}))]
        (is (seq calls))
        (is (every? #(= tail? (boolean (:tail? %))) calls) (pr-str form tail?))))))


(deftest test-and-or-generated-names-are-reserved
  (testing "and/or temporaries carry the dsg__ prefix, not a host gensym"
    (let [names (fn [form]
                  (->> (ast-nodes (yang/compile form))
                       (filter map?)
                       (mapcat :params)
                       (map str)
                       (remove #{"_"})))]
      (is (seq (names '(and a b c))))
      (is (every? #(re-find #"^dsg__" %) (names '(and a b c))))
      (is (every? #(re-find #"^dsg__" %) (names '(or a b c))))
      (is (= (yang/compile '(and a b c)) (yang/compile '(and a b c)))
          "lowering is deterministic")))
  (testing "semantics unchanged"
    (is (= 3 (compile-and-run '(and 1 2 3))))
    (is (false? (compile-and-run '(and 1 false 3))))
    (is (= 2 (compile-and-run '(or nil false 2 3))))
    (is (= 5 (compile-and-run '(and (or nil 5) (or false 5)))))))


(deftest test-case-quoted-constants
  (testing "a quoted symbol is one constant, not a group"
    (is (= :hit (compile-and-run '(case (quote a) (quote a) :hit :miss))))
    (is (= :miss (compile-and-run '(case (quote quote) (quote a) :hit :miss))))
    (is (= :hit (compile-and-run '(case (quote b) (quote a) :x (quote b) :hit :miss))))
    (is (= :miss (compile-and-run '(case (quote c) (quote a) :x (quote b) :hit :miss)))))
  (testing "a quoted list is one list constant"
    (is (= :hit (compile-and-run '(case (quote (1 2)) (quote (1 2)) :hit :miss)))))
  (testing "a quoted constant still collides with the same bare constant"
    (is (rejects? '(case (quote a) (quote a) :x (a) :y :miss)))))


(deftest test-control-form-shape-rejections
  (testing "if-let and if-some take two or three operands"
    (is (rejects? '(if-let [x 1] 1 2 3)))
    (is (rejects? '(if-some [x 1] 1 2 3)))
    (is (rejects? '(if-let [x 1])))
    (is (= 1 (compile-and-run '(if-let [x 1] x))))
    (is (= 2 (compile-and-run '(if-some [x nil] 1 2)))))
  (testing "a :macros entry naming a compiler form is an error"
    (doseq [m '[case cond when if-let if-some when-let when-some]]
      (is (thrown? clojure.lang.ExceptionInfo
            (yang/compile-program '[1] true (assoc av-opts :macros #{m})))
          (str m)))))


(def ^:private fails-on-nil
  '(defn g
     [x]
     (if x x (throw (ex-info "bad" {})))))


(defn- outcome-of-call
  [body call & more-defs]
  (run-outcome (into [fails-on-nil (list 'defn 'f '[x] body)] (concat more-defs [call]))))


(defn- error?
  [out]
  (= :yin.k/error (first out)))


(deftest test-outcome-control-forms
  (testing "case: a may-fail scrutinee unwraps once, in order"
    (is (= [:yin.k/ok :one] (outcome-of-call '(case (g (probe/note x)) 1 :one :z) '(f 1))))
    (is (= [1] @effect-log) "the scrutinee evaluates once")
    (is (error? (outcome-of-call '(case (g x) 1 :one :z) '(f nil))))
    (is (= [:yin.k/ok :z] (outcome-of-call '(case (g x) 1 :one :z) '(f 5)))))
  (testing "case: a may-fail branch in non-tail position"
    (is (= [:yin.k/ok [:z]] (outcome-of-call '[(case x 1 (g nil) :z)] '(f 2))))
    (is (error? (outcome-of-call '[(case x 1 (g nil) :z)] '(f 1))))
    (is (= [:yin.k/ok [5]] (outcome-of-call '[(case x 1 (g 5) :z)] '(f 1)))))
  (testing "case: a may-fail branch in tail position forwards the callee's outcome"
    (is (= [:yin.k/ok 5] (outcome-of-call '(case x 1 (g 5) (g nil)) '(f 1))))
    (is (error? (outcome-of-call '(case x 1 (g 5) (g nil)) '(f 2)))))
  (testing "case without a default: no match is a named error outcome"
    (is (= [:yin.k/ok :a] (outcome-of-call '(case x 1 :a) '(f 1))))
    (let [out (outcome-of-call '(case x 1 :a) '(f 9))]
      (is (error? out))
      (is (= 'case (get-in (second out) [:yin.k/data :yin.k/form])))
      (is (= 9 (get-in (second out) [:yin.k/data :yin.k/value])))
      (is (re-find #"case" (:yin.k/message (second out))))))
  (testing "case: duplicates and arity rejections are unchanged"
    (is (thrown? clojure.lang.ExceptionInfo
          (outcome-ast '[(defn f
                           [x]
                           (case x 1 :a 1 :b :z))])))
    (is (thrown? clojure.lang.ExceptionInfo (outcome-ast '[(defn f
                                                             [x]
                                                             (case x))]))))
  (testing "cond: may-fail tests and branches"
    (is (= [:yin.k/ok :t] (outcome-of-call '(cond (g x) :t :else :e) '(f 1))))
    (is (error? (outcome-of-call '(cond (g x) :t :else :e) '(f nil))))
    (is (= [:yin.k/ok [:e]] (outcome-of-call '[(cond (= x 1) :t :else :e)] '(f 2))))
    (is (= [:yin.k/ok [4]] (outcome-of-call '[(cond (= x 1) (g 4) :else :e)] '(f 1))))
    (is (error? (outcome-of-call '[(cond (= x 1) (g nil) :else :e)] '(f 1))))
    (is (= [:yin.k/ok :e] (outcome-of-call '(cond (= x 1) (g nil) :else :e) '(f 2)))
        "an untaken failing branch never runs")
    (is (= [:yin.k/ok nil] (outcome-of-call '(cond (= x 1) :t) '(f 2)))))
  (testing "when"
    (is (= [:yin.k/ok 3] (outcome-of-call '(when (g x) (g 3)) '(f 1))))
    (is (error? (outcome-of-call '(when (g x) :t) '(f nil))))
    (is (error? (outcome-of-call '[(when true (g nil))] '(f 1))))
    (is (= [:yin.k/ok nil] (outcome-of-call '(when (= x 1) (g nil)) '(f 2)))))
  (testing "if-let and when-let: a may-fail initializer"
    (is (= [:yin.k/ok 2] (outcome-of-call '(if-let [y (g x)] (+ y 1) :none) '(f 1))))
    (is (error? (outcome-of-call '(if-let [y (g x)] y :none) '(f nil))))
    (is (= [:yin.k/ok :none]
           (outcome-of-call '(if-let [y (= x 1)] :yes :none) '(f 2))))
    (is (= [:yin.k/ok 2] (outcome-of-call '(when-let [y (g x)] (+ y 1)) '(f 1))))
    (is (error? (outcome-of-call '(when-let [y (g x)] y) '(f nil))))
    (is (error? (outcome-of-call '[(if-let [y x] (g nil) :none)] '(f 1)))))
  (testing "if-some and when-some: a may-fail initializer, false is some"
    (is (= [:yin.k/ok :some] (outcome-of-call '(if-some [y (g x)] :some :none) '(f 1))))
    (is (error? (outcome-of-call '(if-some [y (g x)] :some :none) '(f nil))))
    (is (= [:yin.k/ok :some] (outcome-of-call '(if-some [y (= x 1)] :some :none) '(f 2))))
    (is (= [:yin.k/ok 2] (outcome-of-call '(when-some [y (g x)] (+ y 1)) '(f 1))))
    (is (error? (outcome-of-call '(when-some [y (g x)] y) '(f nil)))))
  (testing "the initializer evaluates once"
    (is (= [:yin.k/ok 1] (outcome-of-call '(if-let [y (g (probe/note x))] y 0) '(f 1))))
    (is (= [1] @effect-log)))
  (testing "a user macro operand that may fail is still refused, naming the macro"
    (is (re-find #"Macro call unless"
                 (try (yang/compile-program
                        [fails-on-nil '(defn f
                                         [x]
                                         (unless (g x) 1 2))]
                        true (assoc outcome-opts :macros #{'unless}))
                      nil
                      (catch clojure.lang.ExceptionInfo e (ex-message e)))))))


;; =============================================================================
;; Slice 7: the guest collection and higher-order library
;; =============================================================================

(def ^:private library-modules
  "The library's whole host surface: the `kind` module and the `portable`
   adapters of the data exports the collection predicates read."
  (let [host (fn [registry module-name fns arities]
               (module/register-host-module
                 registry module-name fns
                 (into {} (map (fn [[op _]]
                                 [op (vm/primitive-profile op :pure (get arities op [1]) #{} :none)]))
                       fns)))
        portable (into {}
                       (map (fn [[source _]]
                              (let [op (symbol (name source))]
                                [op (yang/outcome-adapter data/refusal-message
                                                          (get data/data-module op))])))
                       yang/library-adapters)]
    (-> av-modules
        (host 'kind yang/kind-module yang/kind-arities)
        (host 'portable portable
              (into {} (map (fn [op] [op (:yin.k/arities (get data/data-profiles op))]))
                    (keys portable))))))


(def ^:private library-opts
  {:convention :arg-vector, :outcomes true
   :adapters yang/library-adapters
   :native vm/primitive-profiles})


(defn run-guest-ast
  "Run an AST through either observer composition with a finite instruction
   budget. Shared by the library and upstream LaTTe acceptance fixtures."
  [ast modules lane step-limit]
  (let [run-bounded (fn [machine]
                      (loop [v machine, n 0]
                        (cond
                          (or (vm/halted? v) (vm/blocked? v)) v
                          (= n step-limit) (throw (ex-info "Guest instruction budget exhausted"
                                                           {:lane lane :steps n}))
                          :else (recur (vm/step v) (inc n)))))
        semantic? (= lane :semantic)
        machine (if semantic?
                  (semantic/create-vm {:make-stream tu/make-stream
                                       :capability-secret tu/secret :modules modules})
                  (tu/create-vm {:modules modules}))
        session (tu/make-observer-session machine)
        load-program (if semantic?
                       (fn [v rows stamp]
                         (vm/check-contract! vm/ast-contract stamp)
                         (semantic/load-vector v (:vector (linearize/project-rows rows))
                                               vm/semantic-contract))
                       ast-walker/vm-load-program)
        queued ((if semantic? tu/queue-rows! tu/queue-ast!) session ast)]
    (-> (observer/run-on-stream queued engine/ready-for-ingress?
                                (vm/fresh-code-loader load-program vm/ast-contract)
                                run-bounded)
        :consumer vm/value)))


(defn- run-library
  "The raw outcome of the last of `forms`, compiled with the library
   definitions the program needs."
  ([forms] (run-library forms library-opts))
  ([forms opts]
   (let [walker (run-library forms opts :walker 1000000)
         semantic (run-library forms opts :semantic 1000000)]
     (is (= walker semantic) (str "library lanes agree: " (pr-str forms)))
     walker))
  ([forms opts lane step-limit]
   (run-guest-ast (yang/compile-program (into (yang/library-for forms) forms) true opts)
                  library-modules lane step-limit)))


(defn- lib
  "The value of the single expression `form`, or the raw error outcome."
  [form]
  (let [[tag payload] (run-library [form])]
    (if (= :yin.k/ok tag) payload [tag payload])))


(deftest test-library-closes-over-its-own-names
  (testing "library-for returns the definitions a program mentions, transitively"
    (let [names (set (map second (yang/library-for '[(seq? x)])))]
      (is (every? names '[seq? sequential? vector? map? set? coll?])
          "seq? pulls sequential?, vector?, map?, set? and coll?")
      (is (not (contains? names 'map))))
    (is (= [] (yang/library-for '[(+ 1 2)])))
    (is (= [] (yang/library-for '[(quote (map f xs))])) "quoted data mentions nothing")
    (is (= [] (yang/library-for '[(defn map
                                    [f c]
                                    c) (map 1 2)]))
        "a program definition shadows the library's")))


(deftest test-library-kind-predicates
  (testing "collection predicates are derived from the data module's refusals"
    (is (= [true false false false false false]
           (lib '[(vector? [1]) (vector? '(1)) (vector? {}) (vector? #{}) (vector? 1) (vector? nil)])))
    (is (= [true false false false false]
           (lib '[(map? {}) (map? [1]) (map? #{}) (map? nil) (map? "s")])))
    (is (= [true false false]
           (lib '[(set? #{1}) (set? [1]) (set? nil)])))
    (is (= [true true true true false false false]
           (lib '[(coll? []) (coll? '()) (coll? {}) (coll? #{}) (coll? nil) (coll? 1) (coll? "s")])))
    (is (= [true false false false false]
           (lib '[(seq? '(1 2)) (seq? []) (seq? {}) (seq? nil) (seq? 1)])))
    (is (= [true true false false]
           (lib '[(sequential? []) (sequential? '(1)) (sequential? {}) (sequential? 1)]))))
  (testing "boolean?, fn?, identity, not=, inc, dec"
    (is (= [true true false false] (lib '[(boolean? true) (boolean? false) (boolean? nil) (boolean? 0)])))
    (is (= [true true false] (lib '[(fn? first) (fn? (fn [x] x)) (fn? 1)])))
    (is (= [5 6 4 true false] (lib '[(identity 5) (inc 5) (dec 5) (not= 1 2) (not= 1 1)]))))
  (testing "symbol?, keyword?, name, symbol, keyword, str, int? are the kind module's"
    (is (= [true false true false] (lib '[(symbol? 'a) (symbol? :a) (keyword? :a) (keyword? 'a)])))
    (is (= ["a" "b" 'a/b :k :a/b true false]
           (lib '[(name 'a) (name :b) (symbol "a" "b") (keyword "k") (keyword "a" "b") (int? 1) (int? 1.5)])))
    (is (= ["" "a1:kx" "abcdef"]
           (lib '[(str) (str "a" 1 :k 'x) (str "a" "b" "c" "d" "e" "f")])))
    (is (= ['G__5 'p7] (lib '[(gensym 5) (gensym "p" 7)]))
        "gensym takes its supply explicitly; there is no hidden counter")))


(deftest test-library-sequences
  (is (= [nil nil [1 2] [1 2] [[:a 1]] nil]
         (lib '[(seq nil) (seq []) (seq [1 2]) (seq '(1 2)) (seq {:a 1}) (seq #{})])))
  (is (= [3 0 3 true false]
         (lib '[(count [1 2 3]) (count nil) (count {:a 1 :b 2 :c 3}) (contains? {:a 1} :a) (contains? #{1} 2)])))
  (is (= [2 nil] (lib '[(second [1 2 3]) (second [1])])))
  (testing "rem on integers, with the dividend's sign"
    (is (= [1 0 2 -1 1 0] (lib '[(rem 7 2) (rem 8 2) (rem 2 5) (rem -7 2) (rem 7 -2) (rem 0 3)]))))
  (testing "unsupported rem operands fail within a finite instruction budget"
    (doseq [lane [:walker :semantic]
            form '[(rem 7 0) (rem 7 0.0) (rem 7 -0.0) (rem 7.5 2)
                   (rem 7 2.5) (rem nil 2) (rem 7 :x) (rem "7" 2)]
            :let [[tag payload] (run-library [form] library-opts lane 10000)]]
      (is (= :yin.k/error tag) (str lane " " form))
      (is (= (if (= form '(rem 7 0)) "Divide by zero" "rem requires integer operands")
             (:yin.k/message payload)))))
  (testing "seq refuses a string or a number rather than returning it"
    (doseq [form ['(seq "ab") '(seq 5)]]
      (let [[tag payload] (run-library [form])]
        (is (= :yin.k/error tag) (pr-str form))
        (is (= "seq of a non-collection is not supported" (:yin.k/message payload))))))
  (testing "a program cannot define the constructors the compiler emits"
    (doseq [form '[(defn vec
                     [x]
                     x) (def conj 1)]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"would capture"
            (yang/compile-program [form '(vec [1])] true library-opts))
          (pr-str form))))
  (testing "reverse, list, cons, vector: lists are lists"
    (is (= [[3 2 1] [] true true]
           (lib '[(reverse [1 2 3]) (reverse nil) (seq? (reverse [1 2])) (seq? (list 1 2))])))
    (is (= ['() '(1) '(1 2) '(1 2 3) '(1 2 3 4 5 6)]
           (lib '[(list) (list 1) (list 1 2) (list 1 2 3) (list 1 2 3 4 5 6)])))
    (is (= ['(0 1 2) '(0) true [] [1 2 3]]
           (lib '[(cons 0 [1 2]) (cons 0 nil) (seq? (cons 0 [1])) (vector) (vector 1 2 3)])))))


(deftest test-library-higher-order
  (testing "map, mapv, filter, remove over a guest closure"
    (is (= [[2 3 4] [2 3 4] [1 3] [2] [5 7 9]]
           (lib '[(map inc [1 2 3]) (mapv inc '(1 2 3)) (filter (fn [x] (not= x 2)) [1 2 3])
                  (remove (fn [x] (not= x 2)) [1 2 3]) (map + [1 2 3] [4 5 6])])))
    (is (= [[] []] (lib '[(map inc nil) (map + [] [1])])))
    (is (= [[2 3]] (lib '[(map (partial + 1) [1 2])]))))
  (testing "reduce, with and without an initial value, and reduced"
    (is (= [6 10 0 7 :done]
           (lib '[(reduce + [1 2 3]) (reduce + 4 [1 2 3]) (reduce + []) (reduce + 7 nil)
                  (reduce (fn [acc x] (if (= x 3) (reduced :done) acc)) 0 [1 2 3 4 5])])))
    (is (= [:early] (lib '[(reduce (fn [acc x] (reduced :early)) 0 [1 2 3])]))
        "a reduced value ends the reduction and is unwrapped")
    (is (= [false true :v] (lib '[(reduced? 1) (reduced? (reduced 1)) (unreduced (reduced :v))]))))
  (testing "into, concat, merge"
    (is (= [[1 2 3] #{1 2} {:a 1 :b 2} [] [1 2 3 4] [1 2 3]]
           (lib '[(into [1] [2 3]) (into #{} [1 2 1]) (into {} [[:a 1] [:b 2]]) (concat)
                  (concat [1 2] '(3 4)) (concat [1] nil [2] [3])])))
    (is (= [nil {:a 1} {:a 2 :b 3} {:a 1 :b 2 :c 3}]
           (lib '[(merge) (merge nil {:a 1}) (merge {:a 1} {:a 2 :b 3}) (merge {:a 1} {:b 2} nil {:c 3})]))))
  (testing "comp and partial"
    (is (= [2 6 7 5 12 9]
           (lib '[((comp inc inc) 0) ((comp inc +) 2 3) ((partial + 1) 6) ((partial + 1 1) 3)
                  ((partial * 3) 4) ((comp inc inc inc) 6)])))
    (is (= [7] (lib '[((comp) 7)])))))


(deftest test-library-sequence-shapes-and-downstream-operations
  (testing "finite eager sequence results prepend with conj and retain traversal order"
    (doseq [producer '[(seq [1 2]) (seq '(1 2)) (map identity '(1 2))
                       (map + [0 1] [1 1 99]) (filter identity [1 2])
                       (remove nil? [1 nil 2]) (concat [1] '(2))]]
      (let [form (list 'let ['s producer]
                       '[[(seq? s) (list? s) (vector? s)]
                         (conj s 0) (count s) (first s) (rest s)
                         (seq? (rest s)) (conj (rest s) 0)])]
        (is (= [[true true false] '(0 1 2) 2 1 '(2) true '(0 2)]
               (lib form)) (pr-str producer)))))
  (testing "empty eager sequences, maps and sets have sequence shape"
    (is (= [true true true true true true true]
           (lib '[(seq? (map identity [])) (seq? (filter identity []))
                  (seq? (remove identity [])) (seq? (concat))
                  (seq? (seq {:a 1})) (seq? (seq #{1})) (list? (rest nil))]))))
  (testing "mapv keeps vector shape and appends with conj, on both arities"
    (doseq [producer '[(mapv identity '(1 2)) (mapv + [0 1] [1 1 9])]]
      (is (= [false false true [1 2 0] 2 1 '(2)]
             (lib (list 'let ['s producer]
                        '[(seq? s) (list? s) (vector? s) (conj s 0)
                          (count s) (first s) (rest s)])))))
    (is (= [true []] (lib '[(vector? (mapv identity [])) (mapv identity [])])))))


(deftest test-library-collections-carry-guest-closures-and-metadata
  (testing "closures remain invocable after sequence and vector transformations"
    (is (= [[11 12] [13 14] 15 16]
           (lib '(let [n 10, f (fn [x] (+ n x)), fs (list f (partial + n 1))]
                   [(map (fn [g] (g 1)) fs)
                    (mapv (fn [g] (g 3)) (concat (filter fn? fs) []))
                    ((first (remove nil? [nil f])) 5)
                    ((first (rest (conj (map identity fs) nil))) 6)])))))
  (testing "metadata on elements and collection targets survives guest operations"
    (let [x (with-meta 'x {:name 'original :note :element})
          target (with-meta [] {:note :target})
          form (list 'let ['x (list 'quote x), 'to target]
                     '[(meta (first (map identity [x])))
                       (meta (first (filter identity [x])))
                       (meta (first (remove nil? [x])))
                       (meta (first (concat [x] [])))
                       (meta (first (mapv identity [x])))
                       (meta (into to [x]))])]
      (is (= (conj (vec (repeat 5 (meta x))) (meta target)) (lib form))))))


(deftest test-library-rest-does-not-capture-compiler-destructuring
  (testing "parameter and loop cursors stay raw when source rest returns outcomes"
    (is (= [2 3 '(3)]
           (lib '(let [f (fn [[a b] n]
                           (if (< n 3)
                             (recur [b (+ a b)] (inc n))
                             [a b (rest [a b])]))]
                   (f [0 1] 0)))))
    (is (= [2 3 '(3)]
           (lib '(loop [[a b] [0 1], n 0]
                   (if (< n 3)
                     (recur [b (+ a b)] (inc n))
                     [a b (rest [a b])])))))
    (is (= '(3 7)
           (lib '(map (fn [[a b]] (+ a b)) [[1 2] [3 4]]))))))


(deftest test-library-failures-flow-through-closures
  (let [bad [:yin.k/error {:yin.k/message "bad two", :yin.k/data {:x 2}}]
        g '(fn [x] (if (= x 2) (throw (ex-info "bad two" {:x x})) x))]
    (testing "a closure's error outcome ends map and reduce with that outcome"
      (is (= bad (run-library [(list 'map g [1 2 3])])))
      (is (= bad (run-library [(list 'reduce (list 'fn '[a x] (list g 'x)) 0 [1 2 3])]))))
    (testing "an untaken failure leaves the success outcome"
      (is (= [:yin.k/ok [1]] (run-library [(list 'map g [1])]))))))


(deftest test-apply
  (testing "apply assembles one argument vector and applies once"
    (is (= [6 10 3 11 1]
           (lib '[(apply + [1 2 3]) (apply + 4 [1 2 3]) (apply inc [2])
                  (apply (fn [a b c] (+ a b c)) 1 2 [8]) (apply (fn [x] x) '(1))]))
        "native names in value position and convention closures alike")
    (is (= [0 [1 2] 6] (lib '[(apply + nil) (apply vector [1 2]) (apply + '(1 2 3))]))
        "nil, a list and a vector as the collection"))
  (testing "a wrong count is the callee's own arity error"
    (let [out (run-library '[(defn f
                               [a b]
                               a) (apply f [1])])]
      (is (= :yin.k/error (first out)))
      (is (= {:yin.vm.data/reason :arity, :yin.k/fn 'f, :yin.k/argc 1} (second out)))))
  (testing "a local named apply is an ordinary guest call"
    (is (= [:yin.k/ok :mine]
           (run-library '[(let [apply (fn [f c] :mine)] (apply 1 2))]))))
  (testing "apply needs a function and a collection"
    (is (thrown? clojure.lang.ExceptionInfo
          (yang/compile-program '[(apply f)] true library-opts)))))


(deftest test-anonymous-fn-arity-error
  (testing "an anonymous fn is :anonymous and carries its source position"
    (let [form (with-meta '(fn [x] x) {:line 7, :column 3, :file "ignored.clj"})]
      (is (= [:yin.k/error {:yin.vm.data/reason :arity, :yin.k/argc 2, :yin.k/kind :anonymous
                            :yin.k/source {:line 7, :column 3}}]
             (run-library [(list form 1 2)])))))
  (testing "without reader metadata there is no source key"
    (is (= [:yin.k/error {:yin.vm.data/reason :arity, :yin.k/argc 0, :yin.k/kind :anonymous}]
           (run-library [(list (list 'fn '[x] 'x))]))))
  (testing "a named function still names itself and has no :anonymous kind"
    (is (= [:yin.k/error {:yin.vm.data/reason :arity, :yin.k/fn 'f, :yin.k/argc 2}]
           (run-library '[(defn f
                            [x]
                            x) (f 1 2)])))))


(deftest test-adapter-counts-from-profiles
  (testing "an adapter admits exactly the profile's fixed arities"
    (is (= [:yin.k/ok false] (run-library '[(let [g not] (g true))])))
    (is (= [:yin.k/error {:yin.vm.data/reason :arity, :yin.k/fn 'not, :yin.k/argc 0}]
           (run-library '[(let [g not] (g))])))
    (is (= [:yin.k/error {:yin.vm.data/reason :arity, :yin.k/fn 'not, :yin.k/argc 2}]
           (run-library '[(let [g not] (g 1 2))]))))
  (testing "a variadic profile extends to the stated bound, not a magic 4"
    (is (= [:yin.k/ok 28] (run-library '[(let [g +] (g 1 2 3 4 5 6 7))])))
    (is (= [:yin.k/ok 36] (run-library '[(let [g +] (g 1 2 3 4 5 6 7 8))])))
    (is (= :arity (:yin.vm.data/reason
                    (second (run-library '[(let [g +] (g 1 2 3 4 5 6 7 8 9))]))))))
  (testing "a set of native names keeps the default bound"
    (let [opts (assoc library-opts :native (set (keys vm/primitives)))]
      (is (= [:yin.k/ok 10] (run-library '[(let [g +] (g 1 2 3 4))] opts)))
      (is (= :arity (:yin.vm.data/reason
                      (second (run-library '[(let [g +] (g 1 2 3 4 5))] opts))))))))
