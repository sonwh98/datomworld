(ns yang.core.prelude
  "Emitter machinery shared by every language's runtime-profile prelude
   (docs/design/yang.typescript.md §17, lift L1). A language supplies its
   module's own namespace string, its uninit sentinel, its definition list
   and its refusal namespace; everything here is a pure function of those."
  (:require
    [clojure.walk :as walk]
    [yang.core.uast :as u]
    [yin.vm :as vm]
    [yin.vm.module :as module]))


(defn define
  "`(yin/def (quote key) form)` in prelude notation: the key quoted, so
   `sexp->uast` reads it as the literal symbol Rule R requires."
  [key form]
  (list 'yin/def (list 'quote key) form))


(defn strip
  "`sym` without the module's own namespace `ns-str`; any other value as it
   is. Qualified internal store keys stay qualified."
  [ns-str sym]
  (if (and (symbol? sym) (= ns-str (namespace sym)))
    (symbol (name sym))
    sym))


(defn strip-form
  "A prelude-notation form with every symbol `strip`ped: references,
   definition keys and quoted keys alike. Keywords, strings and the
   keys of literal maps are untouched."
  [ns-str form]
  (walk/postwalk (partial strip ns-str) form))


(defn placeholder
  "The `[k uninit]` definition for runtime key `k`, so each read of it is
   discharged by an unconditional module-level definition."
  [k uninit]
  [k uninit])


(defn module-definitions
  "The module's definition list: a `uninit` placeholder for every runtime
   key (stripped of `ns-str`), then every definition, stripped."
  [ns-str uninit runtime-keys definitions]
  (into (mapv (fn [k] (placeholder (strip ns-str k) uninit)) runtime-keys)
        (map (fn [[k form]] [(strip ns-str k) (strip-form ns-str form)]))
        definitions))


(defn wide-app
  "One application whose operands are `nodes` (in order), applied to a lambda
   with one parameter per node that returns nil. Every operand runs on the main sequence before the one call, so the
   linker's scanners discharge every body read of a sibling or runtime key;
   a `then` chain would nest definitions in closure bodies."
  [nodes]
  (let [params (mapv (fn [i] (symbol (str "%d" i))) (range (count nodes)))]
    (u/mark-tails (apply u/app (u/lam params (u/lit nil)) nodes))))


(defn module-tree
  "The module tree for `defs` (`[key form]`, already stripped) followed by
   the `trailing` UAST nodes."
  [defs trailing]
  (wide-app (into (mapv (fn [[k form]] (u/def! k (u/sexp->uast form))) defs)
                  trailing)))


(defn exports
  "The module's exports: every key of `definitions` in namespace `ns-str`,
   stripped."
  [ns-str definitions]
  (set (keep (fn [[k _]] (when (= ns-str (namespace k)) (strip ns-str k)))
             definitions)))


(defn free-names
  "Every variable name some occurrence of the map AST `ast` reads
   unbound by an enclosing lambda."
  [ast]
  (letfn [(walk-node
            [acc node bound]
            (cond
              (vector? node) (reduce (fn [a n] (walk-node a n bound)) acc node)
              (not (map? node)) acc
              :else
              (case (:type node)
                :variable (if (contains? bound (:name node))
                            acc
                            (conj acc (:name node)))
                :literal acc
                :lambda (walk-node acc (:body node) (into bound (:params node)))
                (reduce (fn [a v] (walk-node a v bound)) acc (vals node)))))]
    (walk-node #{} ast #{})))


(defn defined-keys
  "Every literal key the map AST `ast` defines with `yin/def`, at any
   depth."
  [ast]
  (set (keep (fn [node]
               (when (and (map? node)
                          (= :application (:type node))
                          (= 'yin/def (get-in node [:operator :name])))
                 (get-in node [:operands 0 :value])))
             (tree-seq coll? seq ast))))


(defn free-name-vec
  "The names `ast` reads and does not define, sorted."
  [ast]
  (vec (sort-by str
                (remove (conj (defined-keys ast) 'yin/def) (free-names ast)))))


(defn- refusal
  [refusal-ns kind data]
  (merge {(keyword refusal-ns "refusal") (keyword refusal-ns kind)} data))


(defn host-declaration
  "`[n declaration]` for free name `n`: a primitive by its `vm/primitives`
   profile, a host export `ns/name` by the profile address the registry's
   host module `ns` publishes and its `:callable-effects` effect set; nil
   when neither supplies it."
  [registry n]
  (if-let [profile (vm/profile-of vm/primitives n)]
    [n profile]
    (when-let [address (module/host-export-profile registry n)]
      (let [entry (module/module-entry (:modules registry) (symbol (namespace n)))
            f (get-in entry [:slice (symbol (name n))])]
        [n {:yin.k/profile address,
            :yin.k/effects (get (:callable-effects registry) f #{})}]))))


(defn module-spec
  "`yin.vm.linker.publish/publish-module!`'s spec for module `module-name`.
   `skip?` (n -> bool) marks free names a requirement already declares;
   `requires` is the requirement map. A free name nothing supplies is
   refused with `message` under `refusal-ns` before anything is published.
   This namespace emits; it never publishes."
  [{:keys [module-name ast exports requires free-names skip? registry message
           refusal-ns]}]
  {:name module-name,
   :ast ast,
   :exports exports,
   :requires requires,
   :primitives
   (into {}
         (keep (fn [n]
                 (when-not (and skip? (skip? n))
                   (or (host-declaration registry n)
                       (throw (ex-info message
                                       (refusal refusal-ns "undeclared-free"
                                                {:name n})))))))
         free-names)})


(defn admit
  "Return `registry` when it supplies every one of `host-names`, else refuse
   it before any program runs, naming each missing export in
   `:<refusal-ns>/missing`."
  [registry host-names message refusal-ns]
  (let [missing (vec (sort-by str
                              (remove #(some? (module/resolve-module
                                                registry
                                                (symbol (str (namespace %)
                                                             "."
                                                             (name %)))))
                                      host-names)))]
    (when (seq missing)
      (throw (ex-info message
                      (refusal refusal-ns "host-names"
                               {(keyword refusal-ns "missing") missing}))))
    registry))
