(ns yang.core.prelude
  "Emitter machinery shared by every language's runtime-profile prelude
   (docs/design/yang.typescript.md §17, lift L1). A language supplies its
   module's own namespace string, its uninit sentinel, its definition list
   and its refusal namespace; everything here is a pure function of those."
  (:require
    [clojure.walk :as walk]
    [yang.core.uast :as u]
    [yin.vm :as vm]))


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


(defn- strip-form
  "A prelude-notation form with every symbol `strip`ped: references,
   definition keys and quoted keys alike. Keywords, strings and the
   keys of literal maps are untouched."
  [ns-str form]
  (walk/postwalk (partial strip ns-str) form))


(defn- placeholder
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


(defn balanced-definitions->uast
  "Balanced definition-to-UAST emission: each [key form] pair maps to
   u/def! and the vector splits at its midpoint, halving sequential depth
   versus the linear u/seq-nodes chain. The tree shape is content-addressed:
   empty input answers nil, and callers choose balanced versus linear
   deliberately - never migrate between the two for style."
  [defs]
  (letfn [(sequence-tree [nodes]
            (if (<= (count nodes) 1)
              (first nodes)
              (let [middle (quot (count nodes) 2)]
                (u/then (sequence-tree (subvec nodes 0 middle))
                        (sequence-tree (subvec nodes middle))))))]
    (sequence-tree (mapv (fn [[k form]] (u/def! k (u/sexp->uast form))) defs))))


(defn definitions->uast
  [defs]
  (u/seq-nodes (map (fn [[k form]] (u/def! k (u/sexp->uast form))) defs)))


(defn protect-primitives
  "Pin primitive reads in bundled runtime code sharing the source store."
  [node]
  (if (= :variable (:type node))
    (let [alias (when-not (namespace (:name node))
                  (symbol "dsg__primitive" (name (:name node))))]
      (cond-> node
        (contains? vm/compiler-primitive-aliases alias) (assoc :name alias)))
    (reduce (fn [n [field kind]]
              (case kind
                :node (update n field protect-primitives)
                :nodes (update n field #(mapv protect-primitives %))
                n))
            node
            (get vm/semantic-bytecode-grammar (:type node)))))
