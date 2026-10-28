(ns yang.clojure-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [yang.clojure :as yang]
            [yang.io :as yang-io]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.encoder :as encoder]
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
                                     ([a] a) ([a b] (+ a b)))] [(g 1) (g 1 2)])]))))
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
    (let [opts (assoc av-opts :macros #{'when})
          call (av-ast '[(when 1 2)] opts)]
      (is (= {:type :variable, :name 'when} (:operator call)))
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
