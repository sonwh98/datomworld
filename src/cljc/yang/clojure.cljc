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
  - Macro definitions (defmacro)

  Macro support (docs/design/yin.vm.macro.md §10.2):
  - defmacro is a special form that lowers to ordinary (yin/def name (fn ...))
    syntax. The lambda node carries `:macro? true`, a frontend-internal fact
    the encoder (`yin.vm.encoder/program-batch`) turns into a declaration row;
    the canonical lambda row has no macro slot.
  - A macro call is an ordinary application. The frontend does no macro
    lookup: the row-native expander recognizes the call.
  - compile-program threads a compile-time macro-env naming the defmacros
    seen so far, only so the native defn lowering yields to a user defn macro.

  Stream operations (stream/make, stream/put, stream/take, >!, <!) are compiled
  as regular function applications and resolved through the module system at runtime.

  The compiler is written in .cljc format to run on both JVM and Node.js."
  (:refer-clojure :exclude [compile]))


;; Forward declaration for mutual recursion
(declare compile-form compile-lambda)


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
      (set? form)
      (map? form)))


(defn constant-form?
  "True when compiling `form` yields a fixed value independent of the
   environment: scalars, quoted forms, and collections built entirely from
   constants. A symbol is never constant (it is a variable reference). A
   `quote` counts as constant only at the top level: nested inside a
   collection it must reach compile-form so the quote is stripped and the
   inner form evaluated as data."
  ([form] (constant-form? form true))
  ([form top-level?]
   (cond
     (or (nil? form) (number? form) (string? form)
         (true? form) (false? form) (keyword? form)) true
     (and (seq? form) (empty? form)) true
     (seq? form) (and top-level? (= 2 (count form)) (= 'quote (first form)))
     (or (vector? form) (set? form)) (every? #(constant-form? % false) form)
     (map? form) (and (every? #(constant-form? % false) (keys form))
                      (every? #(constant-form? % false) (vals form)))
     :else false)))


(defn- compile-constructed-collection
  "Lower a collection literal with dynamic elements to constructor calls over
   existing portable primitives, so unquoted vectors/maps/sets evaluate their
   elements like Clojure. Constant collections stay :literal nodes.

   [e1 e2 ...]  => (conj [] e1 e2 ...)
   #{e1 e2 ...} => (conj #{} e1 e2 ...)
   {k1 v1 ...}  => (assoc {} k1 v1 k2 v2 ...)"
  [form tail? env macro-env]
  (let [ctor (cond
               (vector? form) ['conj []]
               (set? form) ['conj #{}]
               :else ['assoc {}])
        [op seed] ctor
        args (if (map? form)
               (into [] (interleave (keys form) (vals form)))
               (vec form))
        call {:type :application,
              :operator {:type :variable, :name op},
              :operands (into [{:type :literal, :value seed}]
                              (map #(compile-form % false env macro-env) args))}]
    (cond-> call tail? (assoc :tail? true))))


(def initial-macro-env
  "Compile-time map of macro names known before any defmacro: none. The
   standard forms live in the expander's store, not here."
  {})


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


(defn- body-expression
  [body-forms]
  (cond
    (empty? body-forms) nil
    (= 1 (count body-forms)) (first body-forms)
    :else (cons 'do body-forms)))


(defn- mentions-recur?
  "True when `form` holds a recur that could target the enclosing function:
   quoted data and nested fn/loop/def forms own their own recurs."
  [form]
  (cond
    (seq? form) (let [head (first form)]
                  (cond
                    (= 'recur head) true
                    (contains? #{'quote 'fn 'loop 'defn 'defn- 'defmacro} head) false
                    :else (boolean (some mentions-recur? form))))
    (coll? form) (boolean (some mentions-recur? form))
    :else false))


;; =============================================================================
;; Sequential destructuring (slice 3)
;; =============================================================================
;;
;; A destructuring pattern lowers to ordinary symbol bindings over one
;; evaluated temporary, walked with the VM's total primitives first/rest/
;; empty? (the data module's `nth` is vector-only and refuses a list, but
;; LaTTe terms are lists):
;;
;;   [a b & r :as w] against t
;;   =>  a (first t)    c1 (rest t)
;;       b (first c1)   c2 (rest c1)
;;       r (if (empty? c2) nil c2)                        ; nthnext
;;       w t
;;
;; A nil input, a missing element and an exhausted `&` tail all yield nil,
;; as in Clojure. A non-nil remainder is a vector, the VM `rest` contract.
;; Those primitives are bare names a local binding could shadow, so a
;; destructuring form is rejected wherever first, rest or empty? is bound
;; locally; a store definition of those names is not detectable here.
;; Generated names carry the reserved `dsg__` prefix and derive from the
;; binding's position, so lowering is deterministic.

(def ^:private helper-names
  (set (map symbol ["first" "rest" "empty?"])))


(defn- locals
  [env]
  (::locals env #{}))


(defn- with-locals
  [env names]
  (when (and (::convention env) (some #{'conj} names))
    (throw (ex-info "Binding conj would capture the argument-vector constructor"
                    {:names names})))
  (assoc env ::locals (into (locals env) (remove #(= '& %)) names)))


(def ^:private reserved-prefix "dsg__")


(defn- reject!
  [message data]
  (throw (ex-info message data)))


(defn- reserved-name?
  [sym]
  (= reserved-prefix (subs (name sym) 0 (min (count (name sym))
                                             (count reserved-prefix)))))


(defn- check-def-name!
  "A definition name is a plain symbol outside the reserved prefix."
  [operator sym]
  (when-not (and (symbol? sym) (nil? (namespace sym)))
    (reject! (str operator " needs a plain symbol name") {:name sym}))
  (when (reserved-name? sym)
    (reject! "Definition name uses the reserved destructuring prefix"
             {:name sym, :prefix reserved-prefix})))


(defn- check-binder!
  "A binder is a plain symbol or a nested sequential pattern; anything else
   (maps, keywords, literals) is outside the supported surface."
  [b pattern]
  (cond
    (and (symbol? b) (nil? (namespace b)) (not= '& b))
    (when (reserved-name? b)
      (reject! "Binding name uses the reserved destructuring prefix"
               {:binder b, :prefix reserved-prefix}))
    (vector? b) nil
    (map? b) (reject! "Map destructuring is not supported"
                      {:binder b, :pattern pattern})
    :else (reject! "Unsupported binding form" {:binder b, :pattern pattern})))


(defn- parse-sequential-pattern
  "Split a pattern vector into {:positional [...] :rest binder? :as sym?},
   rejecting malformed shapes."
  [pattern]
  (loop [xs (seq pattern), positional [], rest-binder ::none, as-sym nil]
    (if (nil? xs)
      {:positional positional, :rest rest-binder, :as as-sym}
      (let [x (first xs)]
        (cond
          (= '& x)
          (do (when (not= ::none rest-binder)
                (reject! "Pattern has more than one &" {:pattern pattern}))
              (when (or (nil? (next xs)) (= :as (second xs)))
                (reject! "& needs a binder" {:pattern pattern}))
              (check-binder! (second xs) pattern)
              (recur (nnext xs) positional (second xs) as-sym))
          (= :as x)
          (let [sym (second xs)]
            (when-not (and (symbol? sym) (nil? (namespace sym)) (not= '& sym))
              (reject! ":as needs a symbol" {:pattern pattern}))
            (check-binder! sym pattern)
            (when (nnext xs)
              (reject! ":as must be last in a pattern" {:pattern pattern}))
            (recur nil positional rest-binder sym))
          (keyword? x)
          (reject! "Unsupported keyword in pattern" {:keyword x, :pattern pattern})
          :else
          (do (when (not= ::none rest-binder)
                (reject! "Only :as may follow the & binder" {:pattern pattern}))
              (check-binder! x pattern)
              (recur (next xs) (conj positional x) rest-binder as-sym)))))))


(defn- first-form
  [cursor meta-map]
  (cond-> (list 'first cursor)
    meta-map (with-meta meta-map)))


(defn- rest-form
  [cursor meta-map]
  (cond-> (list 'rest cursor)
    meta-map (with-meta meta-map)))


(defn- nthnext-form
  "Clojure's nthnext at a cursor: nil once exhausted, else the remainder."
  [cursor meta-map]
  (cond-> (list 'if (list 'empty? cursor) nil cursor)
    meta-map (with-meta meta-map)))


(defn- temp-symbol
  [path meta-map]
  (cond-> (symbol (str reserved-prefix path))
    meta-map (with-meta meta-map)))


(defn- destructure-sequential
  "Flat [sym form ...] bindings destructuring `pattern` against symbol `t`.
   A chain of cursors walks the value with first/rest; `path` names the
   generated temporaries so nested patterns stay distinct."
  [pattern t path]
  (let [{:keys [positional rest as]} (parse-sequential-pattern pattern)
        m (meta pattern)
        n (count positional)
        cursor (fn [i] (if (zero? i) t (temp-symbol (str path "c" i) m)))
        bind (fn [out b form sub-path]
               (if (vector? b)
                 (let [sub (temp-symbol sub-path (meta b))]
                   (-> out (conj sub form) (into (destructure-sequential b sub sub-path))))
                 (conj out b form)))
        out (reduce (fn [out [i b]]
                      (cond-> (bind out b (first-form (cursor i) m) (str path "_" i))
                        (or (< (inc i) n) (not= ::none rest))
                        (conj (cursor (inc i)) (rest-form (cursor i) m))))
                    []
                    (map-indexed vector positional))
        out (if (= ::none rest)
              out
              (bind out rest (nthnext-form (cursor n) m) (str path "_r")))]
    (cond-> out
      as (conj as t))))


(defn- expand-bindings
  "Rewrite a let binding vector so every target is a plain symbol: each
   pattern becomes a temporary bound to its initializer, then the
   destructuring bindings. Initializers keep their position, so earlier
   bindings stay visible to later ones."
  [bindings env]
  (when-not (even? (count bindings))
    (reject! "let requires an even number of forms in binding vector"
             {:bindings bindings}))
  (loop [pairs (partition 2 bindings), k 0, out [], bound (locals env)]
    (if (empty? pairs)
      out
      (let [[target init] (first pairs)]
        (cond
          (and (symbol? target) (nil? (namespace target)))
          (do (check-binder! target target)
              (recur (rest pairs) (inc k) (conj out target init)
                     (conj bound target)))
          (vector? target)
          (let [temp (temp-symbol k (meta target))
                generated (destructure-sequential target temp (str k))
                bound' (into bound (take-nth 2) generated)]
            (when-let [hit (first (filter helper-names bound'))]
              (reject! "Destructuring would be captured by a local binding"
                       {:binder hit, :pattern target}))
            (recur (rest pairs) (inc k)
                   (-> out (conj temp init) (into generated))
                   bound'))
          :else (check-binder! target target))))))


(defn- lower-fn-params
  "Split fn params into fresh simple binders plus the let bindings that
   destructure them. Variadic `&` at the top level is unsupported."
  [params]
  (when-not (vector? params)
    (reject! "fn parameters must be a vector" {:params params}))
  (reduce (fn [acc [i p]]
            (when (= '& p)
              (reject! "Variadic fn parameters are not supported" {:params params}))
            (check-binder! p params)
            (if (vector? p)
              (let [fresh (temp-symbol (str "p" i) (meta p))]
                (-> acc
                    (update :params conj fresh)
                    (update :bindings conj p fresh)))
              (update acc :params conj p)))
          {:params [], :bindings []}
          (map-indexed vector params)))


;; =============================================================================
;; defn headers
;; =============================================================================

(defn- parse-head
  "Split (op name doc? attr-map? & more) into {:name :doc :attrs :more}. A
   string or map only counts as header when a form follows it."
  [operator operands]
  (let [[fn-name & more] operands
        _ (check-def-name! operator fn-name)
        [doc more] (if (and (string? (first more)) (next more))
                     [(first more) (rest more)]
                     [nil more])
        [attrs more] (if (and (map? (first more)) (next more))
                       [(first more) (rest more)]
                       [nil more])]
    {:name fn-name, :doc doc, :attrs attrs, :more more}))


(defn- parse-defn
  "Parse (defn name doc? attr-map? [params] body*) or
   (defn name doc? attr-map? ([params] body*)+ attr-map?). Returns
   {:name :meta :arities [{:params :body}]}. Metadata accumulates as
   Clojure's defn does: name meta (with defn-'s :private), then the
   docstring, the attr-map and the trailing attr-map, later ones winning."
  [operator operands]
  (let [{fn-name :name, :keys [doc attrs more]} (parse-head operator operands)
        header (merge (cond-> (meta fn-name)
                        (= 'defn- operator) (assoc :private true))
                      (when doc {:doc doc})
                      attrs)]
    (cond
      (vector? (first more))
      {:name fn-name, :meta header
       :arities [{:params (first more), :body (rest more)}]}

      (and (seq? (first more)) (vector? (ffirst more)))
      (let [arities (take-while seq? more)
            trailing (drop (count arities) more)]
        (when (and (seq trailing)
                   (not (and (map? (first trailing)) (nil? (next trailing)))))
          (reject! "Unexpected forms after fn arities" {:name fn-name}))
        {:name fn-name, :meta (merge header (first trailing))
         :arities (mapv (fn [a]
                          (when-not (vector? (first a))
                            (reject! "Malformed fn arity" {:name fn-name}))
                          {:params (first a), :body (rest a)})
                        arities)})

      :else
      (reject! (str "Malformed " operator " form") {:name fn-name}))))


(declare compile-fn compile-av-fn)


(defn- compile-defn
  "Compile a defn form natively to a yin/def application: the tree the
   expander's standard `defn` also produces. This path is used only when no
   defmacro named defn is in scope. Several arities need the argument-vector
   convention: a plain Yin closure nil-fills missing arguments and drops
   extras, so it cannot observe the call's argument count."
  [operator operands env tail? macro-env]
  (let [{fn-name :name, header :meta, :keys [arities]} (parse-defn operator operands)
        fn-ast (if (::convention env)
                 (compile-av-fn arities env false macro-env)
                 (do (when (< 1 (count arities))
                       (reject! "Multi-arity fn is not supported: closures cannot observe argument count"
                                {:name fn-name, :arities (count arities)}))
                     (let [{:keys [params body]} (first arities)]
                       (compile-fn params body env false macro-env))))
        ast (cond-> {:type :application
                     :operator {:type :variable, :name 'yin/def}
                     :operands [{:type :literal, :value (strip-symbol-meta fn-name)}
                                fn-ast]}
              tail? (assoc :tail? true))]
    (if (seq header)
      (vary-meta ast merge header)
      ast)))


(defn- check-macro-params!
  "Every macro parameter is a plain symbol outside the reserved prefix; an
   & must be followed by exactly one binder."
  [params]
  (doseq [p params :when (not= '& p)]
    (check-binder! p params)
    (when-not (symbol? p)
      (reject! "defmacro parameters must be plain symbols" {:params params})))
  (when-let [i (first (keep-indexed (fn [i p] (when (= '& p) i)) params))]
    (when-not (= (+ i 2) (count params))
      (reject! "& needs exactly one binder in defmacro parameters" {:params params}))))


(defn- compile-defmacro
  "Lower (defmacro name doc? attr-map? [params] body*) to ordinary
   (yin/def name (fn ...)) syntax. The lambda is flagged `:macro?` for the
   encoder's declaration row (yin.vm.macro.md §10.2); projection drops the
   flag from the row."
  [operands env tail? macro-env]
  (let [{fn-name :name, :keys [doc attrs more]} (parse-head 'defmacro operands)
        params (first more)
        _ (when-not (vector? params)
            (reject! "defmacro needs a parameter vector" {:name fn-name}))
        _ (check-macro-params! params)
        header (merge (when doc {:doc doc}) attrs)
        ast (cond-> {:type :application
                     :operator {:type :variable, :name 'yin/def}
                     :operands [{:type :literal, :value (strip-symbol-meta fn-name)}
                                (assoc (compile-lambda params (body-expression (rest more))
                                                       env false macro-env)
                                       :macro? true)]}
              tail? (assoc :tail? true))]
    (if (seq header)
      (vary-meta ast merge header)
      ast)))


(defn special-form?
  "Check if a form is a special form."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? #{'fn 'if 'let 'quote 'do 'def 'dao.stream.apply/call 'defmacro 'loop 'recur} (first form))))


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
   (check-def-name! 'def sym)
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
         ;; a source fn boundary resets the recur target
         compiled-body (compile-form body true
                                     (with-locals (dissoc env ::target) params)
                                     new-macro-env)]
     (cond-> {:type :lambda, :params (vec params), :body compiled-body}
       tail? (assoc :tail? true)))))


(defn- compile-fn
  "Compile (fn [params] body*) where params may hold sequential patterns:
   each pattern parameter becomes a fresh simple binder, and the body is
   wrapped in a let that destructures it."
  [params body-forms env tail? macro-env]
  (let [{simple :params, bindings :bindings} (lower-fn-params params)]
    (if (some mentions-recur? body-forms)
      ;; function-level recur: the body runs as a loop over the parameters,
      ;; so a pattern parameter re-destructures on every iteration
      (compile-lambda simple
                      (list* 'loop (vec (interleave params simple)) body-forms)
                      env tail? macro-env)
      (let [body (body-expression body-forms)]
        (compile-lambda simple
                        (if (seq bindings) (list 'let bindings body) body)
                        env tail? macro-env)))))


;; =============================================================================
;; Argument-vector convention (slice 3b)
;; =============================================================================
;;
;; A Yin closure nil-fills missing arguments and drops extras, so a guest
;; closure cannot observe its call's argument count. Opted in at
;; `compile-program` (`:convention :arg-vector`), every user fn and defn
;; instead becomes ONE closure taking ONE vector, branching on its count:
;;
;;   (fn ([a] e1) ([a b] e2))
;;   =>  (fn [dsg__av]
;;         (if (data/content= (data/count dsg__av) 1)
;;           (let [a (data/nth dsg__av 0)] e1)
;;           (if (data/content= (data/count dsg__av) 2)
;;             (let [a (data/nth dsg__av 0) b (data/nth dsg__av 1)] e2)
;;             (data/count))))                     ; refused: no such arity
;;
;; The dispatch names are module-qualified, which no let binder can shadow.
;; A call is (f (conj [] a b)) in tail position when the operator is not
;; statically native; a native name (a primitive or module export, not bound
;; locally and not defined by the program) is called directly, and in value
;; position becomes a fresh adapter closure over the same convention. The
;; program's own macro names stay ordinary applications: the expander
;; recognizes those calls, so their operands must stay ordinary.

(def ^:private av-sym (symbol (str reserved-prefix "av")))

(def ^:private adapter-max-arity 4)


(defn- native-name?
  "True when `sym` statically names a primitive or host-module export."
  [env sym]
  (when-let [conv (::convention env)]
    (and (symbol? sym)
         (not (contains? (locals env) sym))
         (not (contains? (::defined env) sym))
         (or (some? (namespace sym))
             (contains? (:native conv) sym)))))


(defn- macro-call?
  [env macro-env sym]
  (and (symbol? sym)
       (not (contains? (locals env) sym))
       (or (contains? macro-env sym)
           (contains? (:macros (::convention env)) sym))))


(defn- av-let
  "(let [p0 (data/nth av 0) ...] body): positional parameters of one arity.
   A body that recurs is a loop over the same bindings: one recur target per
   arity."
  [params body-forms]
  (let [bindings (into [] (mapcat (fn [i p] [p (list 'data/nth av-sym i)]) (range) params))]
    (if (some mentions-recur? body-forms)
      (list* 'loop bindings body-forms)
      (list 'let bindings (body-expression body-forms)))))


(defn- av-dispatch
  "Nested ifs over the argument vector's count, one branch per arity
   {:n count :form branch}. An unmatched count falls through to a
   zero-argument data/count, which the data module refuses with :reason
   :arity."
  [arities]
  (let [counts (map :n arities)]
    (when (not= (count counts) (count (set counts)))
      (reject! "Two arities with the same parameter count" {:counts (vec counts)}))
    (reduce (fn [else {:keys [n form]}]
              (list 'if
                    (list 'data/content= (list 'data/count av-sym) n)
                    form
                    else))
            (list 'data/count)
            (reverse arities))))


(defn- check-av-params!
  [params]
  (when-not (vector? params)
    (reject! "fn parameters must be a vector" {:params params}))
  (doseq [p params]
    (when (= '& p)
      (reject! "Variadic fn parameters are not supported" {:params params}))
    (check-binder! p params)))


(defn- compile-av-fn
  "Compile arities [{:params :body}] to one closure over an argument vector."
  [arities env tail? macro-env]
  (let [arities (mapv (fn [{:keys [params body]}]
                        (check-av-params! params)
                        {:n (count params)
                         :form (av-let params body)})
                      arities)]
    (compile-lambda [av-sym] (av-dispatch arities) env tail? macro-env)))


(defn- fn-arities
  "Arities of (fn [params] body*) or (fn ([params] body*)+) operands."
  [operands]
  (cond
    (vector? (first operands))
    [{:params (first operands), :body (rest operands)}]

    (and (seq operands) (every? #(and (seq? %) (vector? (first %))) operands))
    (mapv (fn [a] {:params (first a), :body (rest a)}) operands)

    :else (reject! "fn parameters must be a vector" {:params (first operands)})))


(defn- compile-adapter
  "A fresh closure over the convention that calls native `sym` with the
   unpacked arguments, for each admitted count."
  [sym env tail? macro-env]
  (let [arities (mapv (fn [n]
                        {:n n
                         :form (cons sym (map #(list 'data/nth av-sym %) (range n)))})
                      (range (inc adapter-max-arity)))]
    (compile-lambda [av-sym] (av-dispatch arities) env tail? macro-env)))


(defn- compile-av-application
  [operator operands env tail? macro-env]
  (cond
    ;; (:k m) / (:k m default): a static lookup
    (keyword? operator)
    (do (when-not (<= 1 (count operands) 2)
          (reject! "A keyword call takes a map and an optional default"
                   {:keyword operator, :operands (count operands)}))
        (when (contains? (locals env) 'get)
          (reject! "A local binding of get would capture a keyword lookup"
                   {:keyword operator}))
        (cond-> {:type :application
                 :operator {:type :variable, :name 'get}
                 :operands (into [(compile-form (first operands) false env macro-env)
                                  (compile-literal operator false)]
                                 (map #(compile-form % false env macro-env))
                                 (rest operands))}
          tail? (assoc :tail? true)))

    ;; a statically native name: call it directly
    (native-name? env operator)
    (cond-> {:type :application
             :operator (cond-> {:type :variable, :name operator}
                         (seq (meta operator)) (with-meta (meta operator)))
             :operands (mapv #(compile-form % false env macro-env) operands)}
      tail? (assoc :tail? true))

    ;; anything else: one guest invocation, in tail position when the call is
    :else
    (cond-> {:type :application
             :operator (compile-form operator false env macro-env)
             :operands [(compile-form (vec operands) false env macro-env)]}
      tail? (assoc :tail? true))))


(defn compile-application
  "Compile a function application to Universal AST.

  Clojure: (+ 1 2)
  AST: {:type :application
        :operator <compiled-operator>
        :operands [<compiled-operand1> <compiled-operand2>]}

  Under the argument-vector convention a user-function call passes its
  operands as one vector."
  ([operator operands env tail?] (compile-application operator operands env tail? initial-macro-env))
  ([operator operands env tail? macro-env]
   (if (and (::convention env) (not (macro-call? env macro-env operator)))
     (compile-av-application operator operands env tail? macro-env)
     (let [compiled-operator (compile-form operator false env macro-env)
           compiled-operands (mapv #(compile-form % false env macro-env) operands)]
       (cond-> {:type :application,
                :operator compiled-operator,
                :operands compiled-operands}
         tail? (assoc :tail? true))))))


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
        :alternate <compiled-alternate>}

  An explicit `false` alternate is a value, not an absence: `alternate-present?`
  distinguishes (if t c) from (if t c false)."
  ([test consequent alternate env tail?]
   (compile-if test consequent alternate true env tail? initial-macro-env))
  ([test consequent alternate alt-present? env tail?]
   (compile-if test consequent alternate alt-present? env tail? initial-macro-env))
  ([test consequent alternate alt-present? env tail? macro-env]
   (cond-> {:type :if,
            :test (compile-form test false env macro-env),
            :consequent (compile-form consequent tail? env macro-env),
            :alternate (if alt-present?
                         (compile-form alternate tail? env macro-env)
                         (compile-literal nil tail?))}
     tail? (assoc :tail? true))))


(declare compile-let-simple)


(defn compile-let
  "Compile a let binding to Universal AST.

  Transforms let into nested lambda applications:
  (let [x 1 y 2] (+ x y))
  =>
  ((fn [x] ((fn [y] (+ x y)) 2)) 1)"
  ([bindings body env tail?] (compile-let bindings body env tail? initial-macro-env))
  ([bindings body env tail? macro-env]
   (compile-let-simple (expand-bindings bindings env) body env tail? macro-env)))


(defn- compile-let-simple
  "compile-let over a binding vector whose targets are all plain symbols."
  [bindings body env tail? macro-env]
  (if (empty? bindings)
    ;; a function body is compiled in the scope the bindings built
    (if (fn? body)
      (body tail? env macro-env)
      (compile-form body tail? env macro-env))
    (let [[binding-name binding-value & rest-bindings] bindings
          compiled-value (compile-form binding-value false env macro-env)
          new-macro-env (shadow-macro-env macro-env [binding-name])
          compiled-body (compile-let-simple rest-bindings body
                                            (with-locals env [binding-name])
                                            tail? new-macro-env)]
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


;; =============================================================================
;; loop/recur through lexical self-application (slice 4)
;; =============================================================================
;;
;;   (loop [a i1 b i2] body)
;;   =>  (let [a i1 b i2]                         ; sequential initializers
;;         (let [dsg__loop0 (fn [dsg__loop0 a b] body')]
;;           (dsg__loop0 dsg__loop0 a b)))
;;
;; where body' replaces each (recur x y) with the tail application
;; (dsg__loop0 dsg__loop0 x y). The worker receives itself, so it needs no
;; fixed-point combinator, definition or cell. It is compiler-internal and
;; never first-class, so it keeps the native calling convention under the
;; argument-vector convention too. A pattern binder becomes a fresh simple
;; worker parameter and the worker body re-destructures it on each
;; iteration. The recur target rides in the env: loop installs it, a source
;; fn boundary resets it, let/do/if pass it on unchanged, and recur is legal
;; only in tail position with the target's operand count.

(defn- check-top-level!
  "Under the argument-vector convention the ::defined scan reads only
   top-level heads, so a nested definition would dispatch against a name the
   scan never saw."
  [env top? operator]
  (when (and (::convention env) (not top?))
    (reject! (str operator " is only supported at the top level under the argument-vector convention")
             {:operator operator})))


(defn- compile-loop
  [operands env tail? macro-env]
  (let [[bindings & body-forms] operands]
    (when-not (and (vector? bindings) (even? (count bindings)))
      (reject! "loop requires an even-sized binding vector" {:bindings bindings}))
    (let [outer (expand-bindings bindings env)
          depth (::loop-depth env 0)
          self (symbol (str reserved-prefix "loop" depth))
          targets (map first (partition 2 bindings))
          params (vec (map-indexed (fn [i t]
                                     (if (vector? t)
                                       (temp-symbol (str "lp" i) (meta t))
                                       t))
                                   targets))
          args (vec (map-indexed (fn [i t]
                                   (if (vector? t) (temp-symbol i (meta t)) t))
                                 targets))
          patterns (into [] (comp (map-indexed vector)
                                  (filter (comp vector? second))
                                  (mapcat (fn [[i t]] [t (params i)])))
                         targets)
          body-form (cond->> (body-expression body-forms)
                      (seq patterns) (list 'let patterns))
          worker-params (into [self] params)
          build (fn [tail? env macro-env]
                  (let [worker {:type :lambda
                                :params worker-params
                                :body (compile-form
                                        body-form true
                                        (with-locals
                                          (assoc env
                                                 ::target {:self self, :count (count params)}
                                                 ::loop-depth (inc depth))
                                          worker-params)
                                        (shadow-macro-env macro-env params))}]
                    (cond-> {:type :application
                             :operator {:type :lambda
                                        :params [self]
                                        :body (cond-> {:type :application
                                                       :operator (compile-variable self false)
                                                       :operands (into [(compile-variable self false)]
                                                                       (map #(compile-variable % false))
                                                                       args)}
                                                tail? (assoc :tail? true))}
                             :operands [worker]}
                      tail? (assoc :tail? true))))]
      (compile-let-simple outer build env tail? macro-env))))


(defn- compile-recur
  [operands env tail? macro-env]
  (let [{:keys [self], n :count} (::target env)]
    (when-not self
      (reject! "recur needs an enclosing loop or fn" {:operands operands}))
    (when-not (= n (count operands))
      (reject! "recur operand count differs from the loop bindings"
               {:expected n, :operands (count operands)}))
    (when-not tail?
      (reject! "recur is only legal in tail position" {:operands operands}))
    {:type :application
     :operator (compile-variable self false)
     :operands (into [(compile-variable self false)]
                     (map #(compile-form % false env macro-env))
                     operands)
     :tail? true}))


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


(defn- compile-form*
  "Compile a Clojure form to Universal AST.

  This is the main entry point that dispatches to specific compilers
  based on the form type.

  macro-env: compile-time map of defmacro names in scope. A macro call is an
  ordinary application; macro-env only stops the native defn lowering."
  [form tail? env macro-env top?]
  (cond
    ;; Literals: constants self-evaluate; collections with dynamic elements
    ;; lower to constructor calls so their elements evaluate like Clojure
    (literal? form)
    (if (constant-form? form)
      (compile-literal form tail?)
      (compile-constructed-collection form tail? env macro-env))
    ;; The empty list is self-evaluating data, not an application
    (and (seq? form) (empty? form)) (compile-literal form tail?)
    ;; Variables (symbols)
    (symbol? form) (if (native-name? env form)
                     (compile-adapter form env tail? macro-env)
                     (compile-variable form tail?))
    ;; Special forms and function application
    (seq? form)
    (let [[operator & operands] form]
      (cond
        ;; dao.stream.apply call: (dao.stream.apply/call :op/name arg1 arg2)
        (= operator 'dao.stream.apply/call)
        (let [[op & args] operands]
          (compile-dao-stream-apply-call op args env tail? macro-env))
        ;; Native defn fallback: used only when no defmacro defn is in scope
        ;; and the form actually matches defn syntax.
        (and (contains? #{'defn 'defn-} operator)
             (not (contains? macro-env operator)))
        (do (check-top-level! env top? operator)
            (compile-defn operator operands env tail? macro-env))

        ;; Special forms
        :else
        (case operator
          ;; Lambda: (fn [params] body)
          fn (if (::convention env)
               (compile-av-fn (fn-arities operands) env tail? macro-env)
               (let [[params & body] operands]
                 (compile-fn params body env tail? macro-env)))
          ;; Conditional: (if test consequent alternate?)
          if (let [[test consequent alternate] operands]
               (compile-if test consequent alternate (> (count operands) 2) env tail? macro-env))
          ;; Let binding: (let [bindings] body)
          let (let [[bindings & body] operands
                    body-expr (if (= 1 (count body)) (first body) (cons 'do body))]
                (compile-let bindings body-expr env tail? macro-env))
          ;; Do block: (do expr1 expr2 ...)
          do (compile-do operands env tail? macro-env)
          loop (compile-loop operands env tail? macro-env)
          recur (compile-recur operands env tail? macro-env)
          ;; Short-circuit logical: expand to if chains so unevaluated operands are never touched
          ;; (and)       → true
          ;; (and x)     → x
          ;; (and x y z) → (let [g x] (if g (and y z) g))
          and (cond
                (empty? operands) {:type :literal :value true}
                (= 1 (count operands)) (compile-form (first operands) tail? env macro-env)
                :else (let [g (gensym "and__")]
                        (compile-form (list 'let [g (first operands)]
                                            (list 'if g (cons 'and (rest operands)) g))
                                      tail? env macro-env)))
          ;; (or)        → nil
          ;; (or x)      → x
          ;; (or x y z)  → (let [g x] (if g g (or y z)))
          or  (cond
                (empty? operands) {:type :literal :value nil}
                (= 1 (count operands)) (compile-form (first operands) tail? env macro-env)
                :else (let [g (gensym "or__")]
                        (compile-form (list 'let [g (first operands)]
                                            (list 'if g g (cons 'or (rest operands))))
                                      tail? env macro-env)))
          ;; Quote: (quote form)
          quote (compile-quote (first operands) tail?)
          ;; Def: (def sym value)
          def (let [[sym value] operands]
                (check-top-level! env top? operator)
                (compile-def sym value env tail? macro-env))
          ;; Macro definition: (defmacro name params & body)
          defmacro (do (check-top-level! env top? operator)
                       (compile-defmacro operands env tail? macro-env))
          ;; Function application: (f arg1 arg2 ...)
          (compile-application operator operands env tail? macro-env))))
    ;; Unknown form type
    :else (throw (ex-info "Cannot compile unknown form type"
                          {:form form,
                           :type #?(:cljd (clojure.core/str (.-runtimeType form))
                                    :default (clojure.core/type form))}))))


(defn compile-form
  "Compile one Clojure form and retain the reader's plain metadata on the
   emitted Universal AST node. The tuple boundary removes it from content
   and writes positions and other frontend facts to occurrence side tables."
  ([form] (compile-form form false {} initial-macro-env))
  ([form env] (compile-form form false env initial-macro-env))
  ([form tail? env] (compile-form form tail? env initial-macro-env))
  ([form tail? env macro-env]
   (let [ast (compile-form* form tail? (dissoc env ::top-level) macro-env
                            (::top-level env))
         m (meta form)]
     (if (and m (seq m))
       (vary-meta ast merge m)
       ast))))


(defn- program-env
  "The compile-time env of one program. Without `opts` it is empty: the
   native path. `{:convention :arg-vector, :native #{symbol}, :macros
   #{symbol}}` opts into the argument-vector convention: `:native` names the
   primitives a bare symbol may call directly, `:macros` the expander-side
   macro names whose calls must stay ordinary applications. The program's
   own top-level definitions are never native."
  [forms opts]
  (if-not opts
    {}
    (do (when-not (= :arg-vector (:convention opts))
          (reject! "Unknown compile-program convention" {:opts opts}))
        {::convention {:native (set (:native opts)), :macros (set (:macros opts))}
         ::defined (into #{}
                         (keep (fn [f]
                                 (when (and (seq? f)
                                            (contains? #{'def 'defn 'defn- 'defmacro}
                                                       (first f))
                                            (symbol? (second f)))
                                   (strip-symbol-meta (second f)))))
                         forms)})))


(defn compile-program
  "Compile a sequence of Clojure forms into a single Universal AST.
   Top-level defs compile to explicit yin/def store writes, sequenced via lambda.
   Top-level defmacros are lowered to ordinary (yin/def name (fn ...)) syntax
   with :macro? true on the lambda; the encoder turns that flag into a
   declaration row and the expander, not this compiler, recognizes calls.
   The three-argument form takes `opts` opting into the argument-vector
   convention (see `program-env`); without it the output is the native one."
  ([forms] (compile-program forms true))
  ([forms tail?] (compile-program forms tail? nil))
  ([forms tail? opts]
   (let [env (program-env forms opts)]
     (letfn [(go
               [forms macro-env]
               (if (empty? forms)
                 (compile-literal nil tail?)
                 (let [head       (first forms)
                       rest-forms (rest forms)]
                   (cond
                     ;; defmacro: ordinary (yin/def name (fn ...)) syntax whose
                     ;; lambda the encoder declares; later forms see the name
                     ;; only so the native defn lowering can yield to it.
                     (and (seq? head) (= 'defmacro (first head)))
                     (let [defined (compile-defmacro (rest head) env false macro-env)]
                       (cond-> {:type     :application
                                :operator {:type :lambda, :params ['_]
                                           :body (go rest-forms
                                                     (assoc macro-env
                                                            (strip-symbol-meta (second head))
                                                            {:type :user}))}
                                :operands [defined]}
                         tail? (assoc :tail? true)))
                     ;; def: sequence with current macro-env
                     (and (seq? head) (= 'def (first head)))
                     (let [[_ name value] head]
                       (cond-> {:type     :application
                                :operator {:type :lambda, :params ['_]
                                           :body (go rest-forms macro-env)}
                                :operands [(compile-def name value env false macro-env)]}
                         tail? (assoc :tail? true)))
                     ;; single form
                     (= 1 (count forms))
                     (compile-form head tail? (assoc env ::top-level true) macro-env)
                     ;; sequence
                     :else
                     (cond-> {:type     :application
                              :operator {:type :lambda, :params ['_]
                                         :body (go rest-forms macro-env)}
                              :operands [(compile-form head false
                                                       (assoc env ::top-level true) macro-env)]}
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
