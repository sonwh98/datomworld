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
     :captures     the lexical dependencies the scope reads from its owner:
                   a subset of #{:this :super-call :super-prop :new-target};
                   every arrow between a use and the non-arrow function that
                   owns it carries it, an unrelated function does not
     :uses-super-call? :uses-super-prop? :uses-new-target?
                   set on the owning (non-arrow) function scope

   A class opens a `:class` scope keyed by the class node: `:class-self` the
   inner const-like name (nil for an anonymous expression), `:derived?`, and
   `:privates` {\"#x\" {:kind :field|:method|:accessor, :static? bool}}. The
   outer name of a class declaration is a `:lexical` entry of kind `:class`
   (TDZ, mutable). Each constructor, method, accessor and static block is a
   `:function` scope with `:home?` (it has a lexical home object, so `super`
   property access is allowed) and, where it applies, `:ctor`
   (`:base`|`:derived`), `:method?`, `:static?`, `:static-block?`; each
   field initializer is a `:function` scope with `:initializer? true`.

   `var` names hoist to the nearest function, arrow or module scope.

   Early errors (`:yang.ts/early-error`, anchored at the node's span): a
   duplicate `let`/`const` (or one clashing with a `var`, function or
   parameter of the same scope), `const` without an initializer, `break`
   or `continue` outside a loop (`break` also inside a `switch`) or to an
   unknown label, `continue` to a label that is not a loop's, `return`
   outside a function and a duplicate label. For classes: a second
   constructor, `super()` outside a derived constructor, `super.x` outside
   a method (or accessor, constructor, initializer, static block), an
   undeclared or duplicate `#x` (a getter and setter pair is not a
   duplicate), `delete` of any `.#x`, a field or accessor named
   `constructor`, a static member named `prototype`, `arguments` in an
   initializer or static block, and `new.target` outside every function.

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
    ("functionDeclaration" "propertyMemberDeclaration")
    (param-entries pk (rule-child pk n "callSignature"))
    "constructorDeclaration"
    (if-let [l (rule-child pk n "formalParameterList")]
      (param-entries pk l)
      [])
    ("anonymousFunction" "generatorMethod" "getAccessor" "setAccessor")
    (if-let [l (rule-child pk n "formalParameterList")]
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
   :uses-this? false,
   :captures #{},
   :uses-super-call? false,
   :uses-super-prop? false,
   :uses-new-target? false})


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
            ;; The scopes from `scope-id` up to and including the nearest
            ;; function or module: the arrows (and blocks) between a use and
            ;; the non-arrow scope that owns the meaning of `this`, `super`
            ;; and `new.target`.
            (owner-chain
              [scope-id]
              (loop [id scope-id, acc []]
                (let [s (get @scopes id)
                      acc' (conj acc id)]
                  (if (contains? #{:function :module} (:kind s))
                    acc'
                    (recur (:parent s) acc')))))
            (capture!
              [scope-id what]
              (let [ids (owner-chain scope-id)]
                (run! #(update! % update :captures conj what) ids)
                (peek ids)))
            (super-expr?
              ;; the pinned grammar parses `super` as an identifierName
              ;; (IdentifierExpression precedes SuperExpression and a keyword
              ;; is an identifierName), so it is told by its text
              [m]
              (and (contains? #{"singleExpression" "newOperand"} (:rule m))
                   (= 1 (count (p/children pk m)))
                   (let [c (first (p/children pk m))]
                     (or (p/token? c "super")
                         (and (p/rule? c "identifierName")
                              (= "super" (first-token-text pk c)))))))
            (super-use!
              [n kind env]
              (let [owner-id (capture! (:scope env)
                                       (if (= :call kind)
                                         :super-call
                                         :super-prop))
                    owner (get @scopes owner-id)]
                (if (= :call kind)
                  (when-not (= :derived (:ctor owner))
                    (early-error! "'super' keyword unexpected here" n {}))
                  (when-not (:home? owner)
                    (early-error! "'super' keyword unexpected here" n {})))
                (update! owner-id assoc
                         (if (= :call kind)
                           :uses-super-call?
                           :uses-super-prop?)
                         true)))
            (private-reference
              [n]
              ;; the `#name` a member access or `#name in o` mentions, or nil
              (let [ks (p/children pk n)
                    k0 (first ks)]
                (cond
                  (p/rule? k0 "privateIdentifier")
                  (str "#" (first-token-text
                             pk
                             (rule-child pk k0 "identifierName")))
                  (and (some #(p/token? % "#") ks)
                       (p/rule? (peek ks) "identifierName"))
                  (str "#" (first-token-text pk (peek ks))))))
            (check-private!
              [n env]
              (when-let [nm (private-reference n)]
                (when-not (some #(contains? % nm) (:privates env))
                  (early-error! (str "Private field '" nm
                                     "' must be declared in an enclosing class")
                                n
                                {:name nm}))))
            (unparenthesize
              [m]
              (let [ks (p/children pk m)]
                (if (and (= 3 (count ks))
                         (p/token? (first ks) "(")
                         (p/rule? (second ks) "expressionSequence"))
                  (let [inner (p/child-rules pk (second ks) "singleExpression")]
                    (if (= 1 (count inner)) (unparenthesize (first inner)) m))
                  m)))
            (arguments-reference?
              [n]
              (let [ks (p/children pk n)]
                (and (= 1 (count ks))
                     (p/rule? (first ks) "identifierName")
                     (= "arguments" (first-token-text pk (first ks))))))
            (expression
              [n env]
              (let [ks (p/children pk n)
                    c (count ks)
                    k0 (first ks)
                    k1 (when (< 1 c) (second ks))]
                (cond
                  (p/token? k0 "class") (class-node n env)
                  (and (= 3 c) (p/token? k0 "new") (p/token? k1 ".")
                       (p/token? (nth ks 2) "target"))
                  (let [owner-id (capture! (:scope env) :new-target)]
                    (when (= :module (:kind (get @scopes owner-id)))
                      (early-error! "new.target expression is not allowed here"
                                    n
                                    {}))
                    (update! owner-id assoc :uses-new-target? true))
                  (super-expr? n)
                  (early-error! "'super' keyword unexpected here" n {})
                  (and k0 (super-expr? k0) k1 (p/rule? k1 "arguments"))
                  (do (super-use! n :call env)
                      (run! #(walk % env) (rest ks)))
                  (and k0 (super-expr? k0) k1
                       (contains? #{"." "[" "?."} (:text k1)))
                  (do (super-use! n :prop env)
                      (run! #(walk % env) (rest ks)))
                  :else
                  (do (when (and (= 1 c) (p/token? k0 "this"))
                        (mark-this! (:scope env))
                        (capture! (:scope env) :this))
                      (when (arguments-reference? n)
                        (let [owner (get @scopes
                                         (peek (owner-chain (:scope env))))]
                          (when (or (:initializer? owner)
                                    (:static-block? owner))
                            (early-error!
                              "'arguments' is not allowed in class field initializer or static initialization block"
                              n
                              {}))))
                      (when (or (private-reference n) (p/rule? k0 "privateIdentifier"))
                        (check-private! n env))
                      (when (and (= 2 c) (p/token? k0 "delete"))
                        (let [operand (unparenthesize k1)]
                          (when (some #(p/token? % "#") (p/children pk operand))
                            (early-error!
                              "Private fields can not be deleted"
                              n
                              {:name (private-reference operand)}))))
                      (walk-children n env)))))
            (element-info
              [m]
              ;; nil for an abstract member
              (when-not (rule-child pk m "abstractDeclaration")
                (let [acc (or (rule-child pk m "getAccessor")
                              (rule-child pk m "setAccessor"))
                      g (when acc
                          (or (rule-child pk acc "getter")
                              (rule-child pk acc "setter")))]
                  {:static? (p/has-token? pk
                                          (rule-child pk m "propertyMemberBase")
                                          "static"),
                   :acc acc,
                   :cen (rule-child pk (or g m) "classElementName"),
                   :kind (cond
                           acc (if (rule-child pk acc "getter") :get :set)
                           (rule-child pk m "callSignature") :method
                           :else :field)})))
            (element-key
              [cen]
              (let [c (first (p/children pk cen))]
                (if (p/rule? c "privateIdentifier")
                  {:private? true,
                   :name (str "#" (first-token-text
                                    pk
                                    (rule-child pk c "identifierName")))}
                  (let [k0 (first (p/children pk c))]
                    (cond
                      (p/rule? k0 "identifierName")
                      {:name (first-token-text pk k0)}
                      (and (p/token? k0)
                           (re-find #"^['\"]" (:text k0)))
                      {:name (subs (:text k0) 1 (dec (count (:text k0))))}
                      :else {})))))
            (check-key!
              [cen {:keys [private? name]} kind static?]
              (cond
                (and private? (= name "#constructor"))
                (early-error! "Classes may not have a private field named '#constructor'"
                              cen
                              {:name name})
                (and (not private?) (= name "constructor")
                     (or (= :field kind) (and (not static?) (#{:get :set} kind))))
                (early-error! "Classes may not have a field or accessor named 'constructor'"
                              cen
                              {:name name})
                (and (not private?) static? (= name "prototype"))
                (early-error! "Classes may not have a static property named 'prototype'"
                              cen
                              {:name name})))
            (collect-privates
              [tail]
              ;; {"#x" {:kind :field|:method|:accessor, :static? bool}}; a
              ;; getter and a setter of the same staticness pair up
              (reduce
                (fn [privs m]
                  (let [{:keys [static? cen kind] :as info} (element-info m)
                        key (when info (element-key cen))]
                    (if-not (:private? key)
                      privs
                      (let [nm (:name key)
                            prev (get privs nm)
                            pair? (and prev
                                       (= static? (:static? prev))
                                       (or (and (= :get (:kind prev))
                                                (= :set kind))
                                           (and (= :set (:kind prev))
                                                (= :get kind))))]
                        (when (and prev (not pair?))
                          (early-error! (str "Identifier '" nm
                                             "' has already been declared")
                                        cen
                                        {:name nm}))
                        (assoc privs nm
                               {:kind (if pair? :accessor kind),
                                :static? static?})))))
                {}
                (keep (fn [ce]
                        (first (p/child-rules pk ce "propertyMemberDeclaration")))
                      (p/child-rules pk tail "classElement"))))
            (member-scope
              [n env extra skip-rules]
              (let [env' (function-scope n :function env)]
                (update! (:scope env') assoc :home? true :method? true)
                (update! (:scope env') merge extra)
                (run! #(when-not (contains? skip-rules (:rule %))
                         (walk % env'))
                      (p/children pk n))))
            (class-member
              [m ctor-seen env]
              (when-let [{:keys [static? acc cen kind]} (element-info m)]
                (let [key (element-key cen)]
                  (check-key! cen key kind static?)
                  (walk-children cen env)
                  (case kind
                    :method
                    (do (when (and (= "constructor" (:name key)) (not static?))
                          (when @ctor-seen
                            (early-error! "A class may only have one constructor"
                                          m
                                          {}))
                          (vreset! ctor-seen true))
                        (member-scope m env {:static? static?}
                                      #{"propertyMemberBase" "classElementName"}))
                    (:get :set)
                    (member-scope acc env {:static? static?}
                                  #{"getter" "setter"})
                    :field
                    (when-let [init (rule-child pk m "initializer")]
                      (let [id (open! init :function (:scope env))]
                        (update! id assoc
                                 :initializer? true
                                 :home? true
                                 :static? static?)
                        (walk-children init
                                       {:scope id, :fn? false, :labels {},
                                        :loop? false, :switch? false,
                                        :privates (:privates env)})))))))
            (class-element
              [ce derived? ctor-seen env]
              (let [c (first (p/children pk ce))]
                (cond
                  (p/rule? c "staticBlock")
                  (let [id (open! c :function (:scope env))]
                    (update! id assoc :static-block? true :home? true
                             :static? true)
                    (walk-children c {:scope id, :fn? false, :labels {},
                                      :loop? false, :switch? false,
                                      :privates (:privates env)}))
                  (p/rule? c "constructorDeclaration")
                  (do (when @ctor-seen
                        (early-error! "A class may only have one constructor" c {}))
                      (vreset! ctor-seen true)
                      (member-scope c env
                                    {:ctor (if derived? :derived :base)}
                                    #{}))
                  (p/rule? c "propertyMemberDeclaration")
                  (class-member c ctor-seen env)
                  :else (walk c env))))
            (class-node
              [n env]
              (let [ident (rule-child pk n "identifier")
                    nm (when ident (first-token-text pk ident))
                    decl? (= "classDeclaration" (:rule n))
                    heritage (rule-child pk n "classHeritage")
                    derived? (boolean (and heritage
                                           (rule-child pk heritage
                                                       "classExtendsClause")))
                    tail (rule-child pk n "classTail")]
                (when (and decl? nm)
                  (declare-lexical! (:scope env) n nm :class))
                (let [id (open! n :class (:scope env))
                      privs (collect-privates tail)
                      env-h (assoc env :scope id)]
                  (update! id assoc
                           :class-self nm
                           :derived? derived?
                           :privates privs)
                  (when heritage (walk heritage env-h))
                  (let [env-c (assoc env-h
                                     :privates (conj (vec (:privates env))
                                                     (set (keys privs))))
                        ctor-seen (volatile! false)]
                    (run! #(class-element % derived? ctor-seen env-c)
                          (p/child-rules pk tail "classElement"))))))
            (function-scope
              [n kind env]
              (let [id (open! n kind (:scope env))
                    env' {:scope id, :fn? true, :labels {}, :loop? false,
                          :switch? false, :privates (:privates env)}]
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
                  ("generatorMethod" "getAccessor" "setAccessor")
                  (let [env' (function-scope n :function env)]
                    (update! (:scope env') assoc :home? true)
                    (walk-children n env'))
                  "classDeclaration" (class-node n env)
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
                  ("singleExpression" "newOperand") (expression n env)
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
          ;; a class's inner name is const-like and in TDZ until the class
          ;; is defined; assigning it is a runtime TypeError, not early
          (= name (:class-self s)) {:kind :cell, :tdz? true, :const? true}
          :else (recur (:parent s)))))))
