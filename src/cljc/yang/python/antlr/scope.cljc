(ns yang.python.antlr.scope
  "Python binding collection and scope analysis over a CST packet
   (docs/design/yang.antlr.md §8.5: scope analysis precedes lowering).

   A scope is opened by the module (`file_input`), `funcdef`, `lambdef`
   (and `lambdef_nocond`), `classdef`, and every comprehension or generator
   expression (the `testlist_comp`, `dictorsetmaker` or `argument` node
   holding the `comp_for`), and keyed by that node's CST id. A
   comprehension's first iterable belongs to the enclosing scope, as in
   Python 3. For each scope:

     :kind       :module | :function | :lambda | :class | :comprehension
     :parent     enclosing scope id (nil for the module)
     :params     parameter names in order
     :assigned   names bound in the scope, in first-occurrence order
     :globals    names declared `global`
     :nonlocals  names declared `nonlocal`
     :locals     function/lambda: params, then the other assigned names
                 that are neither global nor nonlocal. class: the class
                 namespace. module: none (module names are globals).

   Binding occurrences: the names inside `=`, augmented-assignment, `for`,
   comprehension `for` and `with ... as` targets (plain, tuple, list or
   starred, however nested), `except ... as` names, and `def`/`class` names.
   A nested scope's body is not scanned for the enclosing scope.

   `resolve` answers, for a name read or written in a scope, one of
     {:kind :cell}                     a function-local cell, reached by the
                                       Python name in the lexical env
     {:kind :global :declared? bool}   a module global; declared? when the
                                       module binds it or some scope
                                       declares it global
     {:kind :class-attr :scope id}     the class namespace being built
   and never consults anything but the analysis."
  (:refer-clojure :exclude [resolve])
  (:require
    [yang.antlr.packet :as p]))


(defn- syntax-error!
  [message node data]
  (throw (ex-info message
                  (merge {:yang.python.antlr/diagnostic
                          :yang.python.antlr/syntax,
                          :span (:span node)}
                         data))))


(def ^:private chain-rules
  "Rules that wrap a single expression when they have one child."
  #{"testlist_star_expr" "testlist" "exprlist" "test" "or_test" "and_test"
    "not_test" "comparison" "expr" "atom_expr" "atom"})


(defn simple-name
  "The identifier when `n` is nothing but a name, else nil."
  [pk n]
  (loop [n n]
    (cond
      (p/rule? n "name") (:text (first (p/children pk n)))
      (and (p/rule? n)
           (contains? chain-rules (:rule n))
           (= 1 (count (:children n))))
      (recur (first (p/children pk n)))
      :else nil)))


(defn- name-text
  [pk name-node]
  (:text (first (p/children pk name-node))))


(def ^:private tuple-rules
  "Rules that are a tuple (or list) of targets when they hold several
   elements or a trailing comma."
  #{"testlist_star_expr" "testlist" "exprlist" "testlist_comp"})


(defn target-names
  "Every name a binding target binds: a plain name, or the names inside a
   tuple, list or starred target, however nested. Subscript and attribute
   targets bind nothing."
  [pk n]
  (loop [n n]
    (cond
      (simple-name pk n) [(simple-name pk n)]
      (p/rule? n "star_expr") (target-names pk (last (p/children pk n)))
      (and (p/rule? n) (contains? tuple-rules (:rule n))
           (or (< 1 (count (:children n))) (p/has-token? pk n ",")))
      (vec (mapcat #(target-names pk %) (filter p/rule? (p/children pk n))))
      (and (p/rule? n "atom")
           (or (p/token? (first (p/children pk n)) "(")
               (p/token? (first (p/children pk n)) "[")))
      (let [inner (second (p/children pk n))]
        (if (p/rule? inner "testlist_comp")
          (vec (mapcat #(target-names pk %) (filter p/rule? (p/children pk inner))))
          []))
      (and (p/rule? n) (= 1 (count (:children n))))
      (recur (first (p/children pk n)))
      :else [])))


(defn comprehension?
  "True for the node a comprehension or generator expression scopes over:
   a `testlist_comp`, `dictorsetmaker` or `argument` with a `comp_for`."
  [pk n]
  (and (p/rule? n)
       (contains? #{"testlist_comp" "dictorsetmaker" "argument"} (:rule n))
       (boolean (some #(p/rule? % "comp_for") (p/children pk n)))))


(defn first-iterable
  "The iterable of a comprehension's first `for`: Python evaluates it in the
   enclosing scope."
  [pk comp-node]
  (first (p/child-rules pk (first (p/child-rules pk comp-node "comp_for")) "or_test")))


(defn- names-of-decl
  "The names a `global_stmt` or `nonlocal_stmt` declares."
  [pk n]
  (mapv #(name-text pk %) (p/child-rules pk n "name")))


(defn params-of
  "Parameter names of a funcdef or lambdef node, in order. Shapes the
   lowering rejects (defaults, stars, annotations) still contribute their
   names here so the analysis is total."
  [pk scope-node]
  (let [arg-list (case (:rule scope-node)
                   "funcdef" (first (p/child-rules
                                      pk
                                      (first (p/child-rules pk scope-node
                                                            "parameters"))
                                      "typedargslist"))
                   ("lambdef" "lambdef_nocond")
                   (first (p/child-rules pk scope-node "varargslist"))
                   nil)]
    (if arg-list
      (mapv #(name-text pk (first (p/child-rules pk % "name")))
            (filter #(or (p/rule? % "tfpdef") (p/rule? % "vfpdef"))
                    (p/children pk arg-list)))
      [])))


(defn- expr-stmt-targets
  [pk n]
  (let [kids (p/children pk n)]
    (cond
      (some #(p/rule? % "augassign") kids) [(first kids)]
      (some #(p/rule? % "annassign") kids) [(first kids)]
      (some #(p/token? % "=") kids)
      (let [sides (filterv #(not (p/token? % "=")) kids)]
        (pop sides))
      :else [])))


(defn- collect
  "Bindings, declarations and nested scope nodes of one scope's body. When
   the scope is itself a comprehension, `own` is its node: that node's
   `comp_for` clauses bind here and its first iterable is skipped (it
   belongs to the enclosing scope)."
  [pk body-nodes own]
  (let [acc (volatile! {:assigned [], :globals [], :nonlocals [], :nested []})
        bind! (fn [nm]
                (when nm
                  (vswap! acc update :assigned
                          #(if (some #{nm} %) % (conj % nm)))))
        skip (when own (:id (first-iterable pk own)))]
    (letfn [(walk
              [n]
              (when (and (p/rule? n) (not= skip (:id n)))
                (cond
                  ;; a nested comprehension: its own scope, except that its
                  ;; first iterable is evaluated here
                  (and (comprehension? pk n) (not= (:id own) (:id n)))
                  (do (vswap! acc update :nested conj n)
                      (walk (first-iterable pk n)))
                  :else
                  (case (:rule n)
                    ("funcdef" "classdef")
                    (do (bind! (name-text pk (first (p/child-rules pk n "name"))))
                        (vswap! acc update :nested conj n)
                        ;; class bases are evaluated in the enclosing scope
                        (when (= "classdef" (:rule n))
                          (run! walk (p/child-rules pk n "arglist"))))
                    ("lambdef" "lambdef_nocond") (vswap! acc update :nested conj n)
                    "global_stmt" (vswap! acc update :globals into
                                          (names-of-decl pk n))
                    "nonlocal_stmt" (vswap! acc update :nonlocals into
                                            (names-of-decl pk n))
                    "expr_stmt" (do (run! #(run! bind! (target-names pk %))
                                          (expr-stmt-targets pk n))
                                    (run! walk (p/children pk n)))
                    ("for_stmt" "comp_for")
                    (do (run! bind! (target-names pk (first (p/child-rules pk n
                                                                           "exprlist"))))
                        (run! walk (p/children pk n)))
                    "with_item" (do (when (p/has-token? pk n "as")
                                      (run! bind! (target-names
                                                    pk
                                                    (last (p/children pk n)))))
                                    (run! walk (p/children pk n)))
                    "except_clause" (do (when (p/has-token? pk n "as")
                                          (bind! (name-text
                                                   pk
                                                   (first (p/child-rules
                                                            pk n "name")))))
                                        (run! walk (p/children pk n)))
                    (run! walk (p/children pk n))))))]
      (run! walk body-nodes))
    @acc))


(defn- body-nodes
  [pk scope-node]
  (case (:rule scope-node)
    "file_input" (p/children pk scope-node)
    ("funcdef" "classdef") (p/child-rules pk scope-node "block")
    "lambdef" (p/child-rules pk scope-node "test")
    "lambdef_nocond" (p/child-rules pk scope-node "test_nocond")
    ;; a comprehension: its elements and clauses (first iterable skipped)
    ("testlist_comp" "dictorsetmaker" "argument") [scope-node]))


(defn- scope-kind
  [scope-node]
  (case (:rule scope-node)
    "file_input" :module
    "funcdef" :function
    ("lambdef" "lambdef_nocond") :lambda
    "classdef" :class
    ("testlist_comp" "dictorsetmaker" "argument") :comprehension))


(defn- analyze-scope
  "Records for `scope-node` and every scope nested in it."
  [pk scope-node parent-id]
  (let [kind (scope-kind scope-node)
        {:keys [assigned globals nonlocals nested]}
        (collect pk (body-nodes pk scope-node)
                 (when (= :comprehension kind) scope-node))
        params (params-of pk scope-node)
        global-set (set globals)
        nonlocal-set (set nonlocals)
        _ (when (and (= :module kind) (seq nonlocals))
            (syntax-error! "nonlocal declaration not allowed at module level"
                           scope-node
                           {:names nonlocals}))
        _ (doseq [nm params]
            (when (contains? global-set nm)
              (syntax-error! (str "name '" nm "' is parameter and global")
                             scope-node
                             {:name nm})))
        _ (doseq [nm nonlocals]
            (when (contains? global-set nm)
              (syntax-error! (str "name '" nm "' is nonlocal and global")
                             scope-node
                             {:name nm})))
        others (remove #(or (contains? global-set %)
                            (contains? nonlocal-set %)
                            (some #{%} params))
                       assigned)
        record {:id (:id scope-node),
                :kind kind,
                :parent parent-id,
                :params params,
                :assigned assigned,
                :globals global-set,
                :nonlocals nonlocal-set,
                :locals (case kind
                          :module []
                          :class (vec others)
                          (into params others))}]
    (reduce (fn [acc child]
              (merge acc (analyze-scope pk child (:id scope-node))))
            {(:id scope-node) record}
            nested)))


(defn- function-scope?
  [s]
  (contains? #{:function :lambda :comprehension} (:kind s)))


(defn- enclosing-binding
  "Resolve a free `name` starting at scope `id`: class scopes are skipped,
   the nearest function that binds it locally wins, and the module ends the
   walk."
  [scopes global-names id name]
  (loop [id id]
    (let [s (get scopes id)]
      (cond
        (= :module (:kind s)) {:kind :global,
                               :declared? (contains? global-names name)}
        (= :class (:kind s)) (recur (:parent s))
        (contains? (:globals s) name) {:kind :global, :declared? true}
        (contains? (:nonlocals s) name) (recur (:parent s))
        (some #{name} (:locals s)) {:kind :cell}
        :else (recur (:parent s))))))


(defn resolve
  "How `name`, used in scope `scope-id`, is reached (see the ns doc)."
  [{:keys [scopes global-names]} scope-id name]
  (let [s (get scopes scope-id)]
    (case (:kind s)
      :module {:kind :global, :declared? (contains? global-names name)}
      :class (if (some #{name} (:locals s))
               {:kind :class-attr, :scope scope-id}
               (enclosing-binding scopes global-names (:parent s) name))
      (cond
        (contains? (:globals s) name) {:kind :global, :declared? true}
        (contains? (:nonlocals s) name)
        (enclosing-binding scopes global-names (:parent s) name)
        (some #{name} (:locals s)) {:kind :cell}
        :else (enclosing-binding scopes global-names (:parent s) name)))))


(defn- check-nonlocals!
  [pk {:keys [scopes], :as analysis}]
  (doseq [[id s] (sort-by key scopes)
          nm (sort (:nonlocals s))]
    (let [r (enclosing-binding scopes (:global-names analysis) (:parent s) nm)]
      (when-not (= :cell (:kind r))
        (syntax-error! (str "no binding for nonlocal '" nm "' found")
                       (p/node pk id)
                       {:name nm}))))
  analysis)


(defn analyze
  "The scope analysis of a validated packet: `{:scopes {id record}
   :global-names #{...} :module id}`."
  [pk]
  (let [root (p/root pk)
        scopes (analyze-scope pk root nil)
        module (get scopes (:id root))
        global-names (into (set (:assigned module))
                           (mapcat :globals (vals scopes)))]
    (check-nonlocals! pk
                      {:scopes scopes,
                       :global-names global-names,
                       :module (:id root)})))


(defn function-scope-ids
  "Ids of function and lambda scopes, for tests and tooling."
  [analysis]
  (sort (keep (fn [[id s]] (when (function-scope? s) id))
              (:scopes analysis))))
