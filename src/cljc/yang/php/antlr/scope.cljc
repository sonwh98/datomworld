(ns yang.php.antlr.scope
  "PHP scope analysis over a CST packet (docs/design/yang.php.md section 7.3).

   A scope is opened by the unit and by each function declaration, keyed by
   that node's CST id. For each scope:

     :kind            :unit | :function
     :params          parameter variable names in order, sigil included
     :assigned        variable names written in the scope, first occurrence
                      first (parameters and statics included)
     :vars            every variable name the scope mentions, first occurrence
                      first (what a function allocates local cells for)
     :statics         names declared `static`
     :globals         names declared `global`
     :uses            closure `use` names (closures are refused in P1)
     :arrow-captures  arrow-function captures (refused in P1)
     :dynamic?        true when the scope names a variable dynamically
                      (`$$x`, `${...}`)
     :includes?       true when the scope holds an include/require expression
                      (outside nested functions): the dynamic-scope profile
                      of plan P6 R5
     :this?           true when the scope reads `$this`

   P1 consumes `:kind :params :assigned :statics`; the rest are computed here
   so later slices read them without a second walk. A nested function's body
   is not scanned for the enclosing scope."
  (:require
    [yang.antlr.packet :as p]))


(def ^:private scope-rules
  #{"functionDeclaration" "lambdaFunctionExpr"})


(defn- method?
  "A class member that is a method: a `classStatement` with `function`."
  [pk k]
  (and (p/rule? k "classStatement")
       (boolean (some #(and (p/token? %) (= "Function_" (:type %))) (p/children pk k)))))


(defn- empty-scope
  [kind]
  {:kind kind,
   :params [],
   :assigned [],
   :vars [],
   :statics [],
   :globals [],
   :uses [],
   :arrow-captures [],
   :dynamic? false,
   :includes? false,
   :this? false})


(defn- note-name
  [scope k name]
  (if (some #{name} (get scope k)) scope (update scope k conj name)))


(defn- var-name
  "The variable name of a `variableInitializer`, sigil included."
  [pk n]
  (:text (first (filter #(p/token? %) (p/children pk n)))))


(defn- chain-base
  "The base variable of `chain`, sigil included: `$a` for `$a`, `$a[0][]`,
   `$a{1}`; nil when the chain is not a variable with offsets."
  [pk chain]
  (when (p/rule? chain "chain")
    (let [origin (first (p/children pk chain))
          base (when (p/rule? origin "chainOrigin") (first (p/children pk origin)))
          keyed (when (p/rule? base "chainBase") (first (p/children pk base)))
          [v & offsets] (when (p/rule? keyed "keyedVariable") (p/children pk keyed))]
      (when (and v
                 (p/token? v)
                 (= "VarName" (:type v))
                 (every? #(p/rule? % "squareCurlyExpression") offsets))
        (:text v)))))


(defn- assign-targets
  "Variable names an assignment expression writes: the base variable of its
   `assignable` chain (`$a[k] = v` writes `$a`)."
  [pk n]
  (let [target (first (p/children pk n))]
    (when (p/rule? target "assignable")
      (some-> (chain-base pk (first (p/children pk target))) vector))))


(defn- foreach-targets
  "Base variables a `foreach` writes: the value target and the key target."
  [pk n]
  (let [kids (p/children pk n)
        after-arrow (rest (drop-while #(not (p/token? % "=>")) kids))]
    (keep identity
          (concat (map #(chain-base pk (first (p/children pk %)))
                       (filter #(p/rule? % "assignable") kids))
                  (map #(chain-base pk %) (filter #(p/rule? % "chain") after-arrow))))))


(defn- include-expr?
  "True for `include`/`include_once`/`require`/`require_once <operand>`."
  [pk n]
  (let [kids (p/children pk n)]
    (and (= 2 (count kids))
         (p/token? (first kids))
         (contains? #{"Include" "IncludeOnce" "Require" "RequireOnce"} (:type (first kids))))))


(declare analyze-fn)


(defn- fold-closure
  "The enclosing `scope` plus what a closure takes from it: the `use`
   variables (assigned when by reference), or, for an arrow function, every
   variable its body mentions that is not its own parameter."
  [pk k inner scope]
  (if (some #(and (p/token? %) (= "LambdaFn" (:type %))) (p/children pk k))
    (reduce #(note-name %1 :vars %2)
            scope
            (remove (set (:params inner)) (:vars inner)))
    (reduce (fn [sc uv]
              (let [kids (p/children pk uv)
                    nm (:text (last kids))
                    sc (note-name sc :vars nm)]
                (if (some #(p/token? % "&") kids) (note-name sc :assigned nm) sc)))
            scope
            (mapcat #(p/child-rules pk % "lambdaFunctionUseVar")
                    (p/child-rules pk k "lambdaFunctionUseVars")))))


(defn- walk
  "Add the bindings below `n` to `scope`; a nested scope-opening rule is
   recorded in `scopes` and not walked into."
  [pk n [scope scopes]]
  (reduce
    (fn [[scope scopes :as acc] k]
      (cond
        (not (p/rule? k)) (if (= "VarName" (:type k))
                            [(note-name scope :vars (:text k)) scopes]
                            acc)
        (or (contains? scope-rules (:rule k)) (method? pk k))
        (let [[s ss] (analyze-fn pk k scopes)]
          [(if (= "lambdaFunctionExpr" (:rule k))
             (fold-closure pk k s scope)
             scope)
           (assoc ss (:id k) s)])
        :else
        (let [scope (case (:rule k)
                      "staticVariableStatement"
                      (reduce (fn [sc v]
                                (let [nm (var-name pk v)]
                                  (-> sc
                                      (note-name :statics nm)
                                      (note-name :assigned nm))))
                              scope
                              (p/child-rules pk k "staticInitializer"))
                      "globalStatement"
                      (reduce (fn [sc v]
                                (if (p/token? v)
                                  (if (= "VarName" (:type v))
                                    (note-name sc :globals (:text v))
                                    sc)
                                  sc))
                              scope
                              (mapcat #(p/children pk %)
                                      (p/child-rules pk k "globalVar")))
                      "expression"
                      (cond-> (reduce #(note-name %1 :assigned %2)
                                      scope
                                      (assign-targets pk k))
                        (include-expr? pk k) (assoc :includes? true))
                      "foreachStatement"
                      (reduce #(note-name %1 :assigned %2)
                              scope
                              (foreach-targets pk k))
                      scope)
              scope (if (and (p/rule? k "keyedVariable")
                             (some #(p/token? % "$") (p/children pk k)))
                      (assoc scope :dynamic? true)
                      scope)
              scope (if (some #(p/token? % "$this") (p/children pk k))
                      (assoc scope :this? true)
                      scope)]
          (walk pk k [scope scopes]))))
    [scope scopes]
    (p/children pk n)))


(defn- analyze-fn
  [pk fn-node scopes]
  (let [params (->> (p/child-rules pk fn-node "formalParameterList")
                    (mapcat #(p/child-rules pk % "formalParameter"))
                    (keep (fn [fp]
                            (some->> (p/child-rules pk fp "variableInitializer")
                                     first
                                     (var-name pk)))))
        base (reduce #(note-name (note-name %1 :assigned %2) :vars %2)
                     (assoc (empty-scope :function) :params (vec params))
                     params)]
    (walk pk fn-node [base scopes])))


(defn analyze
  "The analysis of packet `pk`: `{:unit scope, :scopes {fn-node-id scope}}`."
  [pk]
  (let [[unit scopes] (walk pk (p/root pk) [(empty-scope :unit) {}])]
    {:unit unit, :scopes scopes}))
