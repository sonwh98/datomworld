(ns yang.core.uast
  "Universal AST constructors shared by every language lowering and prelude. Every
   function returns a canonical map node (`:literal`, `:variable`, `:lambda`,
   `:application`, `:if`, `:vm/current-continuation`); nothing here adds a
   node type.

   `sexp->uast` reads a small s-expression notation into those same nodes so
   the prelude can be written legibly:

     symbol            -> :variable
     (fn [params] b)   -> :lambda
     (if t c a)        -> :if
     (do a b ... z)    -> sequencing through one-parameter lambdas
     (let [x v ...] b) -> nested immediately applied lambdas
     (quote x)         -> :literal x
     (%capture)        -> :vm/current-continuation
     (yin/def k v)     -> the definition application
     (f a ...)         -> :application
     any other value   -> :literal"
  (:require
    [yang.tails :as tails]
    [yin.vm :as vm]))


(defn lit
  [x]
  {:type :literal, :value x})


(defn v
  [sym]
  {:type :variable, :name sym})


(defn lam
  [params body]
  {:type :lambda, :params (vec params), :body body})


(defn app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn if-node
  [t c a]
  {:type :if, :test t, :consequent c, :alternate a})


(def capture {:type :vm/current-continuation})


(defn def!
  "`(yin/def key value)`: key is a literal symbol."
  [key value]
  (app (v 'yin/def) (lit key) value))


(def discard
  "The parameter a sequencing lambda binds and ignores. `%` cannot begin a
   Python identifier, so it never shadows a guest name."
  '%_)


(defn then
  "Evaluate `a` for effect, then `b`."
  [a b]
  (app (lam [discard] b) a))


(defn seq-nodes
  "Evaluate every node in order; the value is the last one's."
  [nodes]
  (reduce (fn [acc node] (then acc node)) (first nodes) (rest nodes)))


(defn let1
  [sym value body]
  (app (lam [sym] body) value))


(defn sexp->uast
  [form]
  (cond
    (symbol? form) (v form)
    (seq? form)
    (let [[head & args] form]
      (case head
        fn (lam (first args) (sexp->uast (second args)))
        if (if-node (sexp->uast (first args))
                    (sexp->uast (second args))
                    (sexp->uast (nth args 2)))
        do (seq-nodes (map sexp->uast args))
        let (let [[bindings body] args]
              (reduce (fn [inner [sym value]]
                        (let1 sym (sexp->uast value) inner))
                      (sexp->uast body)
                      (reverse (partition 2 bindings))))
        quote (lit (first args))
        %capture capture
        (apply app (sexp->uast head) (map sexp->uast args))))
    :else (lit form)))


(def mark-tails
  "`yang.tails/mark-tails`: the lowering marks its tree with the same
   function a rewriting stage recomputes the marks with."
  tails/mark-tails)


;; =============================================================================
;; Call-site occurrence contract C1 (Addendum A, design §2)
;; =============================================================================
;;
;; Every final `:application` occurrence of a produced tree carries the
;; reserved frontend-metadata key `:yang/call-site` with version value `1`
;; as a plain node key. Projection (`yin.vm/ast-side-tables`) drops the key
;; from canonical rows and emits it into the frontend-metadata side table
;; as `[origin root-address path :yang/call-site 1]`, where the first
;; three coordinates are the site id. The key carries no callee identity,
;; source arity, or ambient classification. It is distinct from
;; `:yang/site`, the safepoint mark, which travels as node metadata.
;; Dedicated node kinds (`:dao.stream.apply/call`, stream and
;; continuation nodes) are not `:application` nodes and are never marked.
;; The walk lives here, beside the other shared tree transforms, so every
;; frontend's module trees finalize the same way; `yang.clojure` owns the
;; contract's validation over canonical rows.

(defn annotate-call-sites
  "The finished map AST `node` with `:yang/call-site 1` on every
   `:application` node, at every depth the row grammar knows. Literal
   payloads are data, never syntax, and are not walked. Marking is
   idempotent and preserves node metadata and every other key, so
   canonical rows, roots, and executable outcomes are unchanged."
  [node]
  (let [tag (:type node)
        slots (get vm/semantic-bytecode-grammar tag)]
    (when-not slots
      (throw (ex-info "Unknown AST node type" {:type tag, :node node})))
    (let [walked (reduce (fn [n [field kind]]
                           (case kind
                             :node (update n field annotate-call-sites)
                             :nodes (update n field
                                            (fn [children]
                                              (mapv annotate-call-sites
                                                    children)))
                             n))
                         node
                         slots)]
      (cond-> walked
        (= :application tag) (assoc :yang/call-site 1)))))
