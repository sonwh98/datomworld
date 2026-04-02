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
  - Top-level definitions via explicit yin/def store effects
  - Macro definitions (defmacro) and macro call sites (:yin/macro-expand)

  Macro support:
  - defmacro is a special form that lowers to (def name (lambda params body)) with
    :yin/macro? true. It is NOT a regular application.
  - compile-program threads a compile-time macro-env set. When (defmacro name ...)
    is processed, name is added to the env for subsequent forms.
  - Macro call sites emit {:type :yin/macro-expand :operator {:type :variable :name op} ...}
    The yin.vm macro expander resolves the variable reference to the lambda EID.

  Stream operations (stream/make, stream/put, stream/take, >!, <!) are compiled
  as regular function applications and resolved through the module system at runtime.

  The compiler is written in .cljc format to run on both JVM and Node.js."
  (:refer-clojure :exclude [compile]))


;; Forward declaration for mutual recursion
(declare compile-form compile-lambda)


;; Strict fixed-point combinator specialized by function arity.
;; For arity=1 this is the classic Z combinator:
;; Z = λf. (λx. f (λv. x x v)) (λx. f (λv. x x v))
(defn arity->z-combinator
  [arity]
  (let [params (mapv (fn [idx] (symbol (str "arg" idx))) (range arity))
        recur-operands (mapv (fn [p] {:type :variable, :name p}) params)
        recur-call {:type :application,
                    :operator {:type :application,
                               :operator {:type :variable, :name 'x},
                               :operands [{:type :variable, :name 'x}]},
                    :operands recur-operands}
        recur-lambda {:type :lambda, :params params, :body recur-call}
        x-lambda {:type :lambda,
                  :params ['x],
                  :body {:type :application,
                         :operator {:type :variable, :name 'f},
                         :operands [recur-lambda]}}]
    {:type :lambda,
     :params ['f],
     :body {:type :application, :operator x-lambda, :operands [x-lambda]}}))


(def Z-combinator
  (arity->z-combinator 1))


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


(def initial-macro-env
  "Compile-time map of macro names to metadata known before any defmacro.
   Seeds compile-program so (defmacro ...) forms emit :yin/macro-expand.
   User-space macros like defn are defined via defmacro in stdlib-forms."
  {'defmacro {:type :bootstrap}})


(defn- shadow-macro-env
  "Remove shadowed names from the macro environment."
  [macro-env names]
  (apply dissoc macro-env names))


(defn- strip-symbol-meta
  "Return an equal symbol without reader metadata."
  [sym]
  (if (namespace sym)
    (symbol (namespace sym) (name sym))
    (symbol (name sym))))


(defn- compile-macro-operands
  "Compile macro operands, optionally shadowing macro names in later operands.
   Macros opt into this by setting metadata on the defining symbol:
   ^{:yang/shadow-params-operand 1 :yang/shadow-body-start 2}"
  [operands env macro-env macro-info]
  (if-let [shadow-operand (:yang/shadow-params-operand macro-info)]
    (let [params-form (nth operands shadow-operand nil)
          body-start  (or (:yang/shadow-body-start macro-info)
                          (inc shadow-operand))]
      (if (vector? params-form)
        (let [body-macro-env (shadow-macro-env macro-env params-form)]
          (vec
            (map-indexed (fn [idx operand]
                           (compile-form operand
                                         false
                                         env
                                         (if (>= idx body-start)
                                           body-macro-env
                                           macro-env)))
                         operands)))
        (mapv #(compile-form % false env macro-env) operands)))
    (mapv #(compile-form % false env macro-env) operands)))


(defn- compile-defn
  "Compile a defn form natively to a yin/def application.
   This path is used only when no defmacro named defn is in scope."
  [fn-name params body-forms env tail? macro-env]
  (let [body-expr (cond
                    (empty? body-forms) nil
                    (= 1 (count body-forms)) (first body-forms)
                    :else (cons 'do body-forms))]
    (cond-> {:type :application
             :operator {:type :variable, :name 'yin/def}
             :operands [{:type :literal, :value fn-name}
                        (compile-lambda params body-expr env false macro-env)]}
      tail? (assoc :tail? true))))


(defn special-form?
  "Check if a form is a special form."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? #{'fn 'if 'let 'quote 'do 'def 'dao.stream.apply/call 'defmacro} (first form))))


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
   Emits a yin/def application which requires environment support."
  ([sym value env tail?] (compile-def sym value env tail? initial-macro-env))
  ([sym value env tail? macro-env]
   (cond-> {:type :application,
            :operator {:type :variable, :name 'yin/def},
            :operands [{:type :literal, :value sym}
                       (compile-form value false env macro-env)]}
     tail? (assoc :tail? true))))


(defn compile-lambda
  "Compile a lambda (fn) expression to Universal AST.

  Clojure: (fn [x y] (+ x y))
  AST: {:type :lambda
        :params [x y]
        :body <compiled-body>}"
  ([params body env tail?] (compile-lambda params body env tail? initial-macro-env))
  ([params body env tail? macro-env]
   (let [new-macro-env (shadow-macro-env macro-env params)
         compiled-body (compile-form body true env new-macro-env)]
     (cond-> {:type :lambda, :params (vec params), :body compiled-body}
       tail? (assoc :tail? true)))))


(defn compile-application
  "Compile a function application to Universal AST.

  Clojure: (+ 1 2)
  AST: {:type :application
        :operator <compiled-operator>
        :operands [<compiled-operand1> <compiled-operand2>]}"
  ([operator operands env tail?] (compile-application operator operands env tail? initial-macro-env))
  ([operator operands env tail? macro-env]
   (let [compiled-operator (compile-form operator false env macro-env)
         compiled-operands (mapv #(compile-form % false env macro-env) operands)]
     (cond-> {:type :application,
              :operator compiled-operator,
              :operands compiled-operands}
       tail? (assoc :tail? true)))))


(defn compile-dao-stream-apply-call
  "Compile a dao.stream.apply call form to Universal AST.

  Clojure: (dao.stream.apply/call :op/name arg1 arg2)
  AST: {:type :dao.stream.apply/call
        :op :op/name
        :operands [<compiled-arg1> <compiled-arg2>]}"
  ([op operands env tail?] (compile-dao-stream-apply-call op operands env tail? initial-macro-env))
  ([op operands env tail? macro-env]
   (when-not (keyword? op)
     (throw (ex-info "dao.stream.apply/call op must be a keyword" {:op op})))
   (let [compiled-operands (mapv #(compile-form % false env macro-env) operands)]
     (cond-> {:type :dao.stream.apply/call,
              :op op,
              :operands compiled-operands}
       tail? (assoc :tail? true)))))


(defn compile-if
  "Compile a conditional (if) expression to Universal AST.

  Clojure: (if test consequent alternate)
  AST: {:type :if
        :test <compiled-test>
        :consequent <compiled-consequent>
        :alternate <compiled-alternate>}"
  ([test consequent alternate env tail?] (compile-if test consequent alternate env tail? initial-macro-env))
  ([test consequent alternate env tail? macro-env]
   (cond-> {:type :if,
            :test (compile-form test false env macro-env),
            :consequent (compile-form consequent tail? env macro-env),
            :alternate (if alternate
                         (compile-form alternate tail? env macro-env)
                         (compile-literal nil tail?))}
     tail? (assoc :tail? true))))


(defn compile-let
  "Compile a let binding to Universal AST.

  Transforms let into nested lambda applications:
  (let [x 1 y 2] (+ x y))
  =>
  ((fn [x] ((fn [y] (+ x y)) 2)) 1)"
  ([bindings body env tail?] (compile-let bindings body env tail? initial-macro-env))
  ([bindings body env tail? macro-env]
   (if (empty? bindings)
     (compile-form body tail? env macro-env)
     (let [[binding-name binding-value & rest-bindings] bindings
           compiled-value (compile-form binding-value false env macro-env)
           new-macro-env (shadow-macro-env macro-env [binding-name])
           compiled-body (compile-let rest-bindings body env tail? new-macro-env)]
       (cond-> {:type :application,
                :operator
                {:type :lambda, :params [binding-name], :body compiled-body},
                :operands [compiled-value]}
         tail? (assoc :tail? true))))))


(defn compile-do
  "Compile a do block to Universal AST.

  Transforms do into nested let expressions that evaluate and discard
  intermediate results:
  (do expr1 expr2 expr3)
  =>
  (let [_ expr1 _ expr2] expr3)"
  ([exprs env tail?] (compile-do exprs env tail? initial-macro-env))
  ([exprs env tail? macro-env]
   (if (empty? exprs)
     (compile-literal nil tail?)
     (if (= 1 (count exprs))
       (compile-form (first exprs) tail? env macro-env)
       (let [[first-expr & rest-exprs] exprs
             compiled-first (compile-form first-expr false env macro-env)
             compiled-rest (compile-do rest-exprs env tail? macro-env)]
         (cond-> {:type :application,
                  :operator {:type :lambda, :params ['_], :body compiled-rest},
                  :operands [compiled-first]}
           tail? (assoc :tail? true)))))))


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
  based on the form type.

  macro-env: compile-time set of macro names. Calls to names in this set emit
  :yin/macro-expand nodes instead of :application nodes."
  ([form] (compile-form form false {} initial-macro-env))
  ([form env] (compile-form form false env initial-macro-env))
  ([form tail? env] (compile-form form tail? env initial-macro-env))
  ([form tail? env macro-env]
   (cond
     ;; Literals
     (literal? form) (compile-literal form tail?)
     ;; Variables (symbols)
     (symbol? form) (compile-variable form tail?)
     ;; Special forms and function application
     (seq? form)
     (let [[operator & operands] form]
       (cond
         ;; dao.stream.apply call: (dao.stream.apply/call :op/name arg1 arg2)
         (= operator 'dao.stream.apply/call)
         (let [[op & args] operands]
           (compile-dao-stream-apply-call op args env tail? macro-env))
         ;; Macro call: operator is a known macro name — emit :yin/macro-expand.
         ;; User macros embed the compiled lambda AST directly as the operator so that
         ;; the expander uses the EID without a name lookup (avoids synthetic names in
         ;; canonical datoms and correctly handles multiple definitions of the same name).
         ;; Bootstrap macros (no :lambda-ast) fall back to a :variable node resolved via
         ;; the name-registry in the expander.
         (get macro-env operator)
         (let [macro-info   (get macro-env operator)
               operator-ast (if-let [la (:lambda-ast macro-info)]
                              la
                              {:type :variable, :name operator})
               compiled-operands (compile-macro-operands operands env macro-env macro-info)]
           (cond-> {:type     :yin/macro-expand
                    :operator operator-ast
                    :operands compiled-operands}
             tail? (assoc :tail? true)))
         ;; Native defn fallback: used only when no defmacro defn is in scope
         ;; and the form actually matches defn syntax.
         (and (= operator 'defn)
              (<= 2 (count operands))
              (vector? (second operands)))
         (let [[fn-name params & body-forms] operands]
           (compile-defn fn-name params body-forms env tail? macro-env))

         ;; Special forms
         :else
         (case operator
           ;; Lambda: (fn [params] body)
           fn (let [[params & body] operands
                    body-expr (if (= 1 (count body)) (first body) (cons 'do body))]
                (compile-lambda params body-expr env tail? macro-env))
           ;; Conditional: (if test consequent alternate?)
           if (let [[test consequent alternate] operands]
                (compile-if test consequent alternate env tail? macro-env))
           ;; Let binding: (let [bindings] body)
           let (let [[bindings & body] operands
                     body-expr (if (= 1 (count body)) (first body) (cons 'do body))]
                 (compile-let bindings body-expr env tail? macro-env))
           ;; Do block: (do expr1 expr2 ...)
           do (compile-do operands env tail? macro-env)
           ;; Quote: (quote form)
           quote (compile-quote (first operands) tail?)
           ;; Def: (def sym value)
           def (let [[sym value] operands] (compile-def sym value env tail? macro-env))
           ;; Function application: (f arg1 arg2 ...)
           (compile-application operator operands env tail? macro-env))))
     ;; Unknown form type
     :else (throw (ex-info "Cannot compile unknown form type"
                           {:form form,
                            :type #?(:cljd (clojure.core/str (.-runtimeType
                                                               form))
                                     :default (clojure.core/type form))})))))


(defn compile-program
  "Compile a sequence of Clojure forms into a single Universal AST.
   Top-level defs compile to explicit yin/def store writes, sequenced via lambda.
   Top-level defmacros are lowered directly to (yin/def name (lambda ...)) with
   :macro? true on the lambda, bypassing the defmacro bootstrap macro.  The lambda
   AST carries a pre-allocated :eid so that call sites, which embed the same map
   object as their :yin/macro-expand operator, resolve to the same entity in the
   datom stream (ast->datoms shared-reference deduplication).  This guarantees that
   :yin/macro in expansion provenance points to the stored yin/def lambda."
  ([forms] (compile-program forms true))
  ([forms tail?]
   (let [macro-eid-counter (atom -1000000)]
     (letfn [(go
               [forms macro-env]
               (if (empty? forms)
                 (compile-literal nil tail?)
                 (let [head       (first forms)
                       rest-forms (rest forms)]
                   (cond
                     ;; defmacro: lower directly to (yin/def name (lambda ...)).
                     ;; The lambda gets a pre-allocated :eid so that the same object
                     ;; in macro-env and at each call site resolves to one entity.
                     (and (seq? head) (= 'defmacro (first head)))
                     (let [macro-name-form (second head)
                           macro-name  (strip-symbol-meta macro-name-form)
                           macro-hints (select-keys (meta macro-name-form)
                                                    [:yang/shadow-params-operand
                                                     :yang/shadow-body-start])
                           params      (nth head 2)
                           body-forms  (nthrest head 3)
                           body-expr   (cond (empty? body-forms) nil
                                             (= 1 (count body-forms)) (first body-forms)
                                             :else (cons 'do body-forms))
                           ;; Pre-allocate an EID for the lambda so that ast->datoms
                           ;; assigns the same entity ID to both the yin/def operand
                           ;; and each call-site operator (shared-reference deduplication).
                           lambda-eid  (swap! macro-eid-counter dec)
                           lambda-ast  (assoc (compile-lambda params body-expr {} false macro-env)
                                              :eid lambda-eid
                                              :macro? true
                                              :phase-policy :compile)
                           ;; Emit (yin/def name lambda) directly — same structure as
                           ;; defmacro-fn bootstrap output, without going through the
                           ;; bootstrap :yin/macro-expand expansion step.
                           compiled    {:type     :application
                                        :operator {:type :variable, :name 'yin/def}
                                        :operands [{:type :literal, :value macro-name}
                                                   lambda-ast]}
                           new-env     (assoc macro-env macro-name
                                              (merge {:type :user
                                                      :lambda-ast lambda-ast}
                                                     macro-hints))]
                       (cond-> {:type     :application
                                :operator {:type :lambda, :params ['_]
                                           :body (go rest-forms new-env)}
                                :operands [compiled]}
                         tail? (assoc :tail? true)))
                     ;; def: sequence with current macro-env
                     (and (seq? head) (= 'def (first head)))
                     (let [[_ name value] head]
                       (cond-> {:type     :application
                                :operator {:type :lambda, :params ['_]
                                           :body (go rest-forms macro-env)}
                                :operands [(compile-def name value {} false macro-env)]}
                         tail? (assoc :tail? true)))
                     ;; single form
                     (= 1 (count forms))
                     (compile-form head tail? {} macro-env)
                     ;; sequence
                     :else
                     (cond-> {:type     :application
                              :operator {:type :lambda, :params ['_]
                                         :body (go rest-forms macro-env)}
                              :operands [(compile-form head false {} macro-env)]}
                       tail? (assoc :tail? true))))))]
       (go forms initial-macro-env)))))


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
