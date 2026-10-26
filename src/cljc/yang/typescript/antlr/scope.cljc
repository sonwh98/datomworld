(ns yang.typescript.antlr.scope
  "TypeScript scope analysis (docs/design/yang.typescript.md section 7):
   hoisting of `var` and function declarations, block scopes for
   `let`/`const`, TDZ classification, `for (let ...)` and catch scopes, and
   the early errors of the T1 plan (D10). A pure function of the CST
   packet.

   A scope is opened by the module (`program`), a function (a
   `functionDeclaration`, or an `anonymousFunction` that is a plain
   function expression), an `arrowFunctionDeclaration`, a `block`, a
   `caseBlock`, a `catchProduction` and a `for (let|const ...;;)`
   `iterationStatement`, and keyed by that node's CST id. For each scope:

     :kind         :module | :function | :arrow | :block | :catch | :for
     :parent       enclosing scope id (nil for the module)
     :params       parameter names in order
     :vars         `var` names hoisted here, first occurrence order
     :functions    function declaration names declared here (block scoped
                   in strict code, hoisted to the top of their scope)
     :lexical      {name :let|:const}
     :self         a named function expression's own name, or nil
     :uses-this?   true when `this` occurs in the scope or in an arrow
                   nested in it

   `var` names hoist to the nearest function, arrow or module scope.

   Early errors (`:yang.ts/early-error`, anchored at the node's span): a
   duplicate `let`/`const` (or one clashing with a `var`, function or
   parameter of the same scope), `const` without an initializer, `break`
   or `continue` outside a loop (`break` also inside a `switch`) or to an
   unknown label, `continue` to a label that is not a loop's, `return`
   outside a function and a duplicate label.

   `resolve` answers `{:kind :cell :tdz? bool :const? bool}` for a declared
   name and `{:kind :global}` for a free one."
  (:refer-clojure :exclude [resolve])
  (:require
    [yang.antlr.packet :as p]))


(defn early-error!
  [message node data]
  (throw (ex-info message
                  (merge {:yang.ts/diagnostic :yang.ts/early-error,
                          :rule (:rule node),
                          :span (:span node)}
                         data))))


(defn first-token-text
  "The text of the first token below `n` (or `n` itself when a token)."
  [pk n]
  (if (p/token? n)
    (:text n)
    (some #(first-token-text pk %) (p/children pk n))))


(defn token-texts
  "The texts of the direct token children of `n`."
  [pk n]
  (mapv :text (filterv p/token? (p/children pk n))))


(defn- rule-child
  [pk n rule-name]
  (first (p/child-rules pk n rule-name)))


(defn declaration-kind
  "`:var`, `:let` or `:const` for a `varModifier` node, or nil."
  [pk modifier]
  (when modifier
    (case (first-token-text pk modifier)
      "var" :var
      "let" :let
      "const" :const
      nil)))


(defn declarations
  "The `variableDeclaration` nodes of a `variableStatement` or of a
   C-style `for` head, with the declaration kind: `[kind [decl ...]]`, or
   nil when the node declares nothing the analysis names."
  [pk n]
  (let [modifier (rule-child pk n "varModifier")
        lst (rule-child pk n "variableDeclarationList")]
    (when lst
      [(or (declaration-kind pk modifier) :var)
       (p/child-rules pk lst "variableDeclaration")])))


(defn declaration-name
  "The identifier a `variableDeclaration` binds, or nil for a pattern."
  [pk decl]
  (let [target (first (p/children pk decl))]
    (when (p/rule? target "identifierOrKeyWord")
      (first-token-text pk target))))


(defn declaration-initializer
  "The initializer expression node of a `variableDeclaration`, or nil."
  [pk decl]
  (when (p/has-token? pk decl "=")
    (last (p/child-rules pk decl "singleExpression"))))


(defn- function-name
  [pk n]
  (first-token-text pk (rule-child pk n "identifier")))


(defn- param-entries
  "The identifiers of a parameter list node: a `callSignature` (declared
   functions), a `formalParameterList` (arrow and expression functions) or
   an `arrowFunctionParameters`. Patterns and rest parameters contribute
   what a name-only scan finds; the lowering refuses them. Each entry is
   `[name node]`."
  [pk n]
  (let [out (volatile! [])]
    (letfn [(go
              [m]
              (cond
                (p/token? m) nil
                (contains? #{"requiredParameter" "optionalParameter"
                             "formalParameterArg" "lastFormalParameterArg"
                             "restParameter"}
                           (:rule m))
                (when-let [id (some (fn [c]
                                      (when (p/rule? c)
                                        (when (contains? #{"identifierOrPattern"
                                                           "assignable"
                                                           "identifier"}
                                                         (:rule c))
                                          c)))
                                    (p/children pk m))]
                  (when (or (p/rule? id "identifier")
                            (every? #(or (p/rule? % "identifierName")
                                         (p/rule? % "identifier"))
                                    (p/children pk id)))
                    (vswap! out conj [(first-token-text pk id) id])))
                (contains? #{"singleExpression" "initializer" "typeAnnotation"}
                           (:rule m))
                nil
                :else (run! go (p/children pk m))))]
      (go n))
    @out))


(defn- parameter-entries
  [pk n]
  (case (:rule n)
    "functionDeclaration" (param-entries pk (rule-child pk n "callSignature"))
    "anonymousFunction" (if-let [l (rule-child pk n "formalParameterList")]
                          (param-entries pk l)
                          [])
    "arrowFunctionDeclaration"
    (let [ps (rule-child pk n "arrowFunctionParameters")]
      (if (p/has-token? pk ps "(")
        (if-let [l (rule-child pk ps "formalParameterList")]
          (param-entries pk l)
          [])
        [[(first-token-text pk ps) ps]]))))


(defn parameters
  "The parameter names of function-like node `n`."
  [pk n]
  (mapv first (parameter-entries pk n)))


(defn- new-scope
  [id kind parent]
  {:id id,
   :kind kind,
   :parent parent,
   :params [],
   :vars [],
   :functions [],
   :lexical {},
   :var-through #{},
   :self nil,
   :uses-this? false})


(defn- declared?
  [s name]
  (boolean (or (some #{name} (:params s))
               (some #{name} (:vars s))
               (some #{name} (:functions s))
               (contains? (:lexical s) name)
               (contains? (:var-through s) name))))


(defn- hoist-scope?
  [s]
  (contains? #{:module :function :arrow} (:kind s)))


(defn- conj-new
  [xs x]
  (if (some #{x} xs) xs (conj xs x)))


(defn analyze
  "The scope analysis of a validated packet: `{:scopes {id record}
   :module id}`."
  [pk]
  (let [scopes (volatile! {})
        root (p/root pk)]
    (letfn [(open!
              [n kind parent]
              (vswap! scopes assoc (:id n) (new-scope (:id n) kind parent))
              (:id n))
            (update!
              [id f & args]
              (vswap! scopes update id #(apply f % args)))
            (hoist-target
              [id]
              (loop [id id]
                (let [s (get @scopes id)]
                  (if (hoist-scope? s) id (recur (:parent s))))))
            (declare-lexical!
              [scope-id node name kind]
              (let [s (get @scopes scope-id)]
                (when (declared? s name)
                  (early-error! (str "Identifier '" name
                                     "' has already been declared")
                                node
                                {:name name}))
                (update! scope-id assoc-in [:lexical name] kind)))
            (declare-var!
              [scope-id node name]
              (let [target (hoist-target scope-id)]
                ;; a lexical binding of the name in any scope from here up
                ;; to the target clashes with the hoisted var
                (loop [id scope-id]
                  (let [s (get @scopes id)]
                    (when (contains? (:lexical s) name)
                      (early-error! (str "Identifier '" name
                                         "' has already been declared")
                                    node
                                    {:name name}))
                    (update! id update :var-through conj name)
                    (when-not (= id target) (recur (:parent s)))))
                (update! target update :vars conj-new name)))
            (declare-function!
              [scope-id node name]
              (let [s (get @scopes scope-id)]
                (when (contains? (:lexical s) name)
                  (early-error! (str "Identifier '" name
                                     "' has already been declared")
                                node
                                {:name name}))
                (update! scope-id update :functions conj-new name)))
            (mark-this!
              [scope-id]
              (loop [id scope-id]
                (update! id assoc :uses-this? true)
                (when (= :arrow (:kind (get @scopes id)))
                  (recur (:parent (get @scopes id))))))
            (variable-statement
              [n env]
              (when-let [[kind decls] (declarations pk n)]
                (doseq [d decls]
                  (when-let [nm (declaration-name pk d)]
                    (let [init? (some? (declaration-initializer pk d))]
                      (when (and (= :const kind) (not init?)
                                 (not (and (:for-head env)
                                           (or (p/has-token? pk n "of")
                                               (p/has-token? pk n "in")))))
                        (early-error! "Missing initializer in const declaration"
                                      d
                                      {:name nm}))
                      (case kind
                        :var (declare-var! (:scope env) d nm)
                        (declare-lexical! (:scope env) d nm kind)))))))
            (for-head-declaration!
              [d kind env]
              (when-let [nm (declaration-name pk d)]
                (when (declaration-initializer pk d)
                  (early-error! "for-in loop variable declaration may not have an initializer"
                                d
                                {:name nm}))
                (if (= :var kind)
                  (declare-var! (:scope env) d nm)
                  (declare-lexical! (:scope env) d nm kind))))
            (walk-children
              [n env]
              (run! #(walk % env) (p/children pk n)))
            (function-scope
              [n kind env]
              (let [id (open! n kind (:scope env))
                    env' {:scope id, :fn? true, :labels {}, :loop? false,
                          :switch? false}]
                (loop [seen #{}
                       [[nm node] & more] (parameter-entries pk n)]
                  (when node
                    (when (contains? seen nm)
                      (early-error! (str "Duplicate parameter name '" nm "'")
                                    node
                                    {:name nm}))
                    (recur (conj seen nm) more)))
                (update! id assoc :params (parameters pk n))
                env'))
            (walk
              [n env]
              (when (p/rule? n)
                (case (:rule n)
                  "variableStatement" (do (variable-statement n env)
                                          (walk-children n env))
                  "functionDeclaration"
                  (let [name (function-name pk n)
                        expr? (:fn-expr env)]
                    (when-not expr?
                      (declare-function! (:scope env) n name))
                    (let [env' (function-scope n :function env)]
                      (when expr?
                        (update! (:scope env') assoc :self name))
                      (walk-children n (assoc env' :fn-expr false))))
                  "anonymousFunction"
                  (let [c (first (p/children pk n))]
                    (cond
                      (p/rule? c "functionDeclaration")
                      (walk c (assoc env :fn-expr true))
                      (p/rule? c "arrowFunctionDeclaration") (walk c env)
                      :else (let [env' (function-scope n :function env)]
                              (walk-children n env'))))
                  "arrowFunctionDeclaration"
                  (let [env' (function-scope n :arrow env)]
                    (walk-children n env'))
                  "block" (let [id (open! n :block (:scope env))]
                            (walk-children n (assoc env :scope id)))
                  "caseBlock" (let [id (open! n :block (:scope env))]
                                (walk-children n
                                               (assoc env
                                                      :scope id
                                                      :switch? true)))
                  "catchProduction"
                  (let [id (open! n :catch (:scope env))
                        ident (rule-child pk n "identifier")]
                    (when ident
                      (update! id assoc
                               :params [(first-token-text pk ident)]))
                    (walk-children n (assoc env :scope id)))
                  "iterationStatement"
                  (let [lst (rule-child pk n "variableDeclarationList")
                        kind (declaration-kind pk
                                               (rule-child pk n "varModifier"))
                        vd (rule-child pk n "variableDeclaration")
                        loop-env (assoc env :loop? true)]
                    (cond
                      (and lst (contains? #{:let :const} kind))
                      (let [id (open! n :for (:scope env))
                            env' (assoc loop-env :scope id :for-head true)]
                        (variable-statement n env')
                        (walk-children n (assoc env' :for-head false)))
                      (and vd (contains? #{:let :const} kind))
                      (let [id (open! n :for (:scope env))
                            env' (assoc loop-env :scope id)]
                        (for-head-declaration! vd kind env')
                        (walk-children n env'))
                      :else
                      (do (when lst (variable-statement n env))
                          (when vd (for-head-declaration! vd :var env))
                          (walk-children n loop-env))))
                  "labelledStatement"
                  (let [chain (loop [m n, acc []]
                                (let [label (first-token-text
                                              pk
                                              (rule-child pk m "identifier"))
                                      body (rule-child pk m "statement")
                                      next-l (first (p/children pk body))
                                      acc' (conj acc [m label])]
                                  (if (p/rule? next-l "labelledStatement")
                                    (recur next-l acc')
                                    {:labels acc', :body body})))
                        body (:body chain)
                        ;; a label chain on a loop gives each label continue
                        loop? (p/rule? (first (p/children pk body))
                                       "iterationStatement")]
                    (walk body
                          (reduce (fn [env' [m label]]
                                    (when (contains? (:labels env') label)
                                      (early-error!
                                        (str "Label '" label
                                             "' has already been declared")
                                        m
                                        {:name label}))
                                    (assoc-in env'
                                              [:labels label]
                                              (boolean loop?)))
                                  env
                                  (:labels chain))))
                  "breakStatement"
                  (let [ident (rule-child pk n "identifier")]
                    (if ident
                      (let [label (first-token-text pk ident)]
                        (when-not (contains? (:labels env) label)
                          (early-error! (str "Undefined label '" label "'")
                                        n
                                        {:name label})))
                      (when-not (or (:loop? env) (:switch? env))
                        (early-error! "Illegal break statement" n {}))))
                  "continueStatement"
                  (let [ident (rule-child pk n "identifier")]
                    (if ident
                      (let [label (first-token-text pk ident)]
                        (when-not (get (:labels env) label)
                          (early-error! (str "Undefined label '" label
                                             "' for continue")
                                        n
                                        {:name label})))
                      (when-not (:loop? env)
                        (early-error! "Illegal continue statement" n {}))))
                  "returnStatement"
                  (do (when-not (:fn? env)
                        (early-error! "Illegal return statement" n {}))
                      (walk-children n env))
                  "functionBody" (walk-children n (assoc env :fn? true))
                  "singleExpression"
                  (do (when (and (= 1 (count (p/children pk n)))
                                 (p/token? (first (p/children pk n)) "this"))
                        (mark-this! (:scope env)))
                      (walk-children n env))
                  (walk-children n env))))]
      (let [id (open! root :module nil)]
        (walk-children root {:scope id, :fn? false, :labels {}, :loop? false,
                             :switch? false}))
      {:scopes @scopes, :module (:id root)})))


(defn resolve
  "How `name`, used in scope `scope-id`, is reached (see the ns doc)."
  [{:keys [scopes]} scope-id name]
  (loop [id scope-id]
    (if (nil? id)
      {:kind :global}
      (let [s (get scopes id)
            lexical (get (:lexical s) name)]
        (cond
          (some? lexical) {:kind :cell, :tdz? true, :const? (= :const lexical)}
          (or (some #{name} (:params s))
              (some #{name} (:vars s))
              (some #{name} (:functions s)))
          {:kind :cell, :tdz? false, :const? false}
          ;; a named function expression's own name is immutable
          (= name (:self s)) {:kind :cell, :tdz? false, :const? true}
          :else (recur (:parent s)))))))
