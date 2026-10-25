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
     :this?           true when the scope reads `$this`

   P1 consumes `:kind :params :assigned :statics`; the rest are computed here
   so later slices read them without a second walk. A nested function's body
   is not scanned for the enclosing scope."
  (:require
    [yang.antlr.packet :as p]))


(def ^:private scope-rules
  #{"functionDeclaration"})


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
   :this? false})


(defn- note-name
  [scope k name]
  (if (some #{name} (get scope k)) scope (update scope k conj name)))


(defn- var-name
  "The variable name of a `variableInitializer`, sigil included."
  [pk n]
  (:text (first (filter #(p/token? %) (p/children pk n)))))


(defn- assign-targets
  "Variable names an assignment expression writes: the base variable of its
   `assignable` chain."
  [pk n]
  (let [target (first (p/children pk n))]
    (when (p/rule? target "assignable")
      (let [chain (first (p/children pk target))]
        (when (p/rule? chain "chain")
          (let [origin (first (p/children pk chain))
                base (when (p/rule? origin "chainOrigin")
                       (first (p/children pk origin)))
                keyed (when (p/rule? base "chainBase")
                        (first (p/children pk base)))
                kids (when (p/rule? keyed "keyedVariable") (p/children pk keyed))]
            (when (and (= 1 (count kids)) (= "VarName" (:type (first kids))))
              [(:text (first kids))])))))))


(declare analyze-fn)


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
        (contains? scope-rules (:rule k)) (let [[s ss] (analyze-fn pk k scopes)]
                                            [scope (assoc ss (:id k) s)])
        :else
        (let [scope (case (:rule k)
                      "staticVariableStatement"
                      (reduce (fn [sc v]
                                (let [nm (var-name pk v)]
                                  (-> sc
                                      (note-name :statics nm)
                                      (note-name :assigned nm))))
                              scope
                              (p/child-rules pk k "variableInitializer"))
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
                      (reduce #(note-name %1 :assigned %2)
                              scope
                              (assign-targets pk k))
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
