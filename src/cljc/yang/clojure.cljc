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
  - Recursion via Z-combinator for def

  Stream operations (stream/make, stream/put, stream/take, >!, <!) are compiled
  as regular function applications and resolved through the module system at runtime.

  The compiler is written in .cljc format to run on both JVM and Node.js."
  (:refer-clojure :exclude [compile]))


;; Forward declaration for mutual recursion
(declare compile-form)


;; The Z combinator (strict fixed-point combinator) for recursion.
;; Z = λf. (λx. f (λv. x x v)) (λx. f (λv. x x v))
(def Z-combinator
  {:type :lambda,
   :params ['f],
   :body
     {:type :application,
      :operator {:type :lambda,
                 :params ['x],
                 :body {:type :application,
                        :operator {:type :variable, :name 'f},
                        :operands
                          [{:type :lambda,
                            :params ['v],
                            :body {:type :application,
                                   :operator
                                     {:type :application,
                                      :operator {:type :variable, :name 'x},
                                      :operands [{:type :variable, :name 'x}]},
                                   :operands [{:type :variable, :name 'v}]}}]}},
      :operands
        [{:type :lambda,
          :params ['x],
          :body {:type :application,
                 :operator {:type :variable, :name 'f},
                 :operands
                   [{:type :lambda,
                     :params ['v],
                     :body {:type :application,
                            :operator {:type :application,
                                       :operator {:type :variable, :name 'x},
                                       :operands [{:type :variable, :name 'x}]},
                            :operands [{:type :variable, :name 'v}]}}]}}]}})


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
       (contains? #{'fn 'if 'let 'quote 'do 'def} (first form))))


(defn compile-literal
  "Compile a literal value to Universal AST."
  [form _tail?]
  {:type :literal, :value form})


(defn compile-variable
  "Compile a variable reference (symbol) to Universal AST."
  [sym _tail?]
  {:type :variable, :name sym})


(defn compile-def
  "Compile a def form to Universal AST.
   Note: This is for non-top-level defs or when not using compile-program.
   It emits a yin/def application which requires environment support."
  [sym value env tail?]
  (cond-> {:type :application,
           :operator {:type :variable, :name 'yin/def},
           :operands [{:type :literal, :value sym}
                      (compile-form value false env)]}
    tail? (assoc :tail? true)))


(defn compile-lambda
  "Compile a lambda (fn) expression to Universal AST.

  Clojure: (fn [x y] (+ x y))
  AST: {:type :lambda
        :params [x y]
        :body <compiled-body>}"
  [params body env tail?]
  (let [compiled-body (compile-form body true env)]
    (cond-> {:type :lambda, :params (vec params), :body compiled-body}
      tail? (assoc :tail? true))))


(defn compile-application
  "Compile a function application to Universal AST.

  Clojure: (+ 1 2)
  AST: {:type :application
        :operator <compiled-operator>
        :operands [<compiled-operand1> <compiled-operand2>]}"
  [operator operands env tail?]
  (let [compiled-operator (compile-form operator false env)
        compiled-operands (mapv #(compile-form % false env) operands)]
    (cond-> {:type :application,
             :operator compiled-operator,
             :operands compiled-operands}
      tail? (assoc :tail? true))))


(defn compile-if
  "Compile a conditional (if) expression to Universal AST.

  Clojure: (if test consequent alternate)
  AST: {:type :if
        :test <compiled-test>
        :consequent <compiled-consequent>
        :alternate <compiled-alternate>}"
  [test consequent alternate env tail?]
  (cond-> {:type :if,
           :test (compile-form test false env),
           :consequent (compile-form consequent tail? env),
           :alternate (if alternate
                        (compile-form alternate tail? env)
                        (compile-literal nil tail?))}
    tail? (assoc :tail? true)))


(defn compile-let
  "Compile a let binding to Universal AST.

  Transforms let into nested lambda applications:
  (let [x 1 y 2] (+ x y))
  =>
  ((fn [x] ((fn [y] (+ x y)) 2)) 1)"
  [bindings body env tail?]
  (if (empty? bindings)
    (compile-form body tail? env)
    (let [[binding-name binding-value & rest-bindings] bindings
          compiled-value (compile-form binding-value false env)
          compiled-body (compile-let rest-bindings body env tail?)]
      (cond-> {:type :application,
               :operator
                 {:type :lambda, :params [binding-name], :body compiled-body},
               :operands [compiled-value]}
        tail? (assoc :tail? true)))))


(defn compile-do
  "Compile a do block to Universal AST.

  Transforms do into nested let expressions that evaluate and discard
  intermediate results:
  (do expr1 expr2 expr3)
  =>
  (let [_ expr1 _ expr2] expr3)"
  [exprs env tail?]
  (if (empty? exprs)
    (compile-literal nil tail?)
    (if (= 1 (count exprs))
      (compile-form (first exprs) tail? env)
      (let [[first-expr & rest-exprs] exprs
            compiled-first (compile-form first-expr false env)
            compiled-rest (compile-do rest-exprs env tail?)]
        (cond-> {:type :application,
                 :operator {:type :lambda, :params ['_], :body compiled-rest},
                 :operands [compiled-first]}
          tail? (assoc :tail? true))))))


(defn compile-quote
  "Compile a quoted form to Universal AST.

  Quoted forms become literals."
  [form tail?]
  (compile-literal form tail?))


(defn resolve-primitive
  "Resolve primitive operations in the environment.

  Primitives like +, -, *, /, =, <, > are built into the Yin VM
  and are accessed as variables."
  [sym]
  (when (contains? #{'+ '- '* '/ '= '< '>} sym) sym))


(defn compile-form
  "Compile a Clojure form to Universal AST.

  This is the main entry point that dispatches to specific compilers
  based on the form type."
  ([form] (compile-form form false {}))
  ([form env] (compile-form form false env))
  ([form tail? env]
   (cond
     ;; Literals
     (literal? form) (compile-literal form tail?)
     ;; Variables (symbols)
     (symbol? form) (compile-variable form tail?)
     ;; Special forms and function application
     (seq? form)
       (let [[operator & operands] form]
         (case operator
           ;; Lambda: (fn [params] body)
           fn (let [[params & body] operands
                    ;; Handle multi-expression body with implicit do
                    body-expr
                      (if (= 1 (count body)) (first body) (cons 'do body))]
                (compile-lambda params body-expr env tail?))
           ;; Conditional: (if test consequent alternate?)
           if (let [[test consequent alternate] operands]
                (compile-if test consequent alternate env tail?))
           ;; Let binding: (let [bindings] body)
           let (let [[bindings & body] operands
                     ;; Handle multi-expression body with implicit do
                     body-expr
                       (if (= 1 (count body)) (first body) (cons 'do body))]
                 (compile-let bindings body-expr env tail?))
           ;; Do block: (do expr1 expr2 ...)
           do (compile-do operands env tail?)
           ;; Quote: (quote form)
           quote (compile-quote (first operands) tail?)
           ;; Def: (def sym value)
           def (let [[sym value] operands] (compile-def sym value env tail?))
           ;; Function application: (f arg1 arg2 ...)
           ;; This includes stream operations (stream/make, stream/put,
           ;; stream/take, >!, <!)
           ;; which are resolved through the module system at runtime
           (compile-application operator operands env tail?)))
     ;; Unknown form type
     :else (throw (ex-info "Cannot compile unknown form type"
                           {:form form,
                            :type #?(:cljd (clojure.core/str (.-runtimeType
                                                               form))
                                     :default (clojure.core/type form))})))))


(defn compile-program
  "Compile a sequence of Clojure forms into a single Universal AST.
   Handles top-level defs by wrapping the rest of the program in a let/lambda.
   Uses Z-combinator for recursive definitions."
  ([forms] (compile-program forms true))
  ([forms tail?]
   (if (empty? forms)
     (compile-literal nil tail?)
     (let [head (first forms)]
       (if (and (seq? head) (= 'def (first head)))
         (let [[_ name value] head]
           ;; It's a definition: let name = (Z (fn [name] value)) in rest
           (cond-> {:type :application,
                    :operator {:type :lambda,
                               :params [name],
                               :body (compile-program (rest forms) tail?)},
                    :operands [{:type :application,
                                :operator Z-combinator,
                                :operands [{:type :lambda,
                                            :params [name],
                                            :body
                                              (compile-form value false {})}]}]}
             tail? (assoc :tail? true)))
         ;; Not a definition
         (if (= 1 (count forms))
           (compile-form head tail? {})
           ;; Sequence
           (cond-> {:type :application,
                    :operator {:type :lambda,
                               :params ['_],
                               :body (compile-program (rest forms) tail?)},
                    :operands [(compile-form head false {})]}
             tail? (assoc :tail? true))))))))


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
  (compile-form form true {}))
