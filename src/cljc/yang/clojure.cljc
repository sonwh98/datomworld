(ns yang.clojure
  "Yang Clojure compiler - Compiles Clojure code into Yin VM Universal AST.

  This is the Clojure-specific compiler in the Yang compiler collection.
  It transforms Clojure s-expressions into the Universal AST format that
  the Yin VM can execute. It handles:
  - Literals (numbers, strings, booleans, nil, collections)
  - Variables (symbols)
  - Lambda expressions (fn)
  - Function application (including stream operations via module system)
  - Conditionals (if)
  - Let bindings (let)

  Stream operations (stream/make, stream/put, stream/take, >!, <!) are compiled
  as regular function applications and resolved through the module system at runtime.

  The compiler is written in .cljc format to run on both JVM and Node.js."
  (:refer-clojure :exclude [compile]))

;; Forward declaration for mutual recursion
(declare compile-form)

(defn literal?
  "Check if a value is a literal (self-evaluating)."
  [form]
  (or (number? form)
      (string? form)
      (true? form)
      (false? form)
      (nil? form)
      (keyword? form)
      (vector? form)
      (map? form)))

(defn special-form?
  "Check if a form is a special form."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? #{'fn 'if 'let 'quote 'do 'def}
                  (first form))))

(defn compile-literal
  "Compile a literal value to Universal AST."
  [form]
  {:type :literal
   :value form})

(defn compile-variable
  "Compile a variable reference (symbol) to Universal AST."
  [sym]
  {:type :variable
   :name sym})

(defn compile-def
  "Compile a def form to Universal AST.

  Clojure: (def x 10)
  AST: {:type :application
        :operator {:type :variable :name 'yin/def}
        :operands [{:type :literal :value 'x} <compiled-value>]}"
  [sym value env]
  {:type :application
   :operator {:type :variable :name 'yin/def}
   :operands [{:type :literal :value sym}
              (compile-form value env)]})

(defn compile-lambda
  "Compile a lambda (fn) expression to Universal AST.

  Clojure: (fn [x y] (+ x y))
  AST: {:type :lambda
        :params [x y]
        :body <compiled-body>}"
  [params body env]
  (let [compiled-body (compile-form body env)]
    {:type :lambda
     :params (vec params)
     :body compiled-body}))

(defn compile-application
  "Compile a function application to Universal AST.

  Clojure: (+ 1 2)
  AST: {:type :application
        :operator <compiled-operator>
        :operands [<compiled-operand1> <compiled-operand2>]}"
  [operator operands env]
  (let [compiled-operator (compile-form operator env)
        compiled-operands (mapv #(compile-form % env) operands)]
    {:type :application
     :operator compiled-operator
     :operands compiled-operands}))

(defn compile-if
  "Compile a conditional (if) expression to Universal AST.

  Clojure: (if test consequent alternate)
  AST: {:type :if
        :test <compiled-test>
        :consequent <compiled-consequent>
        :alternate <compiled-alternate>}"
  [test consequent alternate env]
  {:type :if
   :test (compile-form test env)
   :consequent (compile-form consequent env)
   :alternate (if alternate
                (compile-form alternate env)
                (compile-literal nil))})

(defn compile-let
  "Compile a let binding to Universal AST.

  Transforms let into nested lambda applications:
  (let [x 1 y 2] (+ x y))
  =>
  ((fn [x] ((fn [y] (+ x y)) 2)) 1)"
  [bindings body env]
  (if (empty? bindings)
    (compile-form body env)
    (let [[binding-name binding-value & rest-bindings] bindings
          compiled-value (compile-form binding-value env)
          compiled-body (compile-let rest-bindings body env)]
      {:type :application
       :operator {:type :lambda
                  :params [binding-name]
                  :body compiled-body}
       :operands [compiled-value]})))

(defn compile-do
  "Compile a do block to Universal AST.

  Transforms do into nested let expressions that evaluate and discard
  intermediate results:
  (do expr1 expr2 expr3)
  =>
  (let [_ expr1 _ expr2] expr3)"
  [exprs env]
  (if (empty? exprs)
    (compile-literal nil)
    (if (= 1 (count exprs))
      (compile-form (first exprs) env)
      (let [[first-expr & rest-exprs] exprs
            compiled-first (compile-form first-expr env)
            compiled-rest (compile-do rest-exprs env)]
        {:type :application
         :operator {:type :lambda
                    :params ['_]
                    :body compiled-rest}
         :operands [compiled-first]}))))

(defn compile-quote
  "Compile a quoted form to Universal AST.

  Quoted forms become literals."
  [form]
  (compile-literal form))

(defn resolve-primitive
  "Resolve primitive operations in the environment.

  Primitives like +, -, *, /, =, <, > are built into the Yin VM
  and are accessed as variables."
  [sym]
  (when (contains? #{'+ '- '* '/ '= '< '>} sym)
    sym))

(defn compile-form
  "Compile a Clojure form to Universal AST.

  This is the main entry point that dispatches to specific compilers
  based on the form type."
  ([form] (compile-form form {}))
  ([form env]
   (cond
     ;; Literals
     (literal? form)
     (compile-literal form)

     ;; Variables (symbols)
     (symbol? form)
     (compile-variable form)

     ;; Special forms and function application
     (seq? form)
     (let [[operator & operands] form]
       (case operator
         ;; Lambda: (fn [params] body)
         fn
         (let [[params & body] operands
               ;; Handle multi-expression body with implicit do
               body-expr (if (= 1 (count body))
                           (first body)
                           (cons 'do body))]
           (compile-lambda params body-expr env))

         ;; Conditional: (if test consequent alternate?)
         if
         (let [[test consequent alternate] operands]
           (compile-if test consequent alternate env))

         ;; Let binding: (let [bindings] body)
         let
         (let [[bindings & body] operands
               ;; Handle multi-expression body with implicit do
               body-expr (if (= 1 (count body))
                           (first body)
                           (cons 'do body))]
           (compile-let bindings body-expr env))

         ;; Do block: (do expr1 expr2 ...)
         do
         (compile-do operands env)

         ;; Quote: (quote form)
         quote
         (compile-quote (first operands))

         ;; Def: (def sym value)
         def
         (let [[sym value] operands]
           (compile-def sym value env))

         ;; Function application: (f arg1 arg2 ...)
         ;; This includes stream operations (stream/make, stream/put, stream/take, >!, <!)
         ;; which are resolved through the module system at runtime
         (compile-application operator operands env)))

     ;; Unknown form type
     :else
     (throw (ex-info "Cannot compile unknown form type"
                     {:form form
                      :type #?(:cljd (clojure.core/str (.-runtimeType form))
                               :default (clojure.core/type form))})))))

(defn compile
  "Main compiler entry point.

  Compiles a Clojure form into Yin VM Universal AST.

  Examples:
    (compile 42)
    => {:type :literal, :value 42}

    (compile '(+ 1 2))
    => {:type :application
        :operator {:type :variable, :name +}
        :operands [{:type :literal, :value 1}
                   {:type :literal, :value 2}]}

    (compile '(fn [x] (* x 2)))
    => {:type :lambda
        :params [x]
        :body {:type :application
               :operator {:type :variable, :name *}
               :operands [{:type :variable, :name x}
                          {:type :literal, :value 2}]}}"
  [form]
  (compile-form form {}))
