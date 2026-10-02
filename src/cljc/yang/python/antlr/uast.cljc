(ns yang.python.antlr.uast
  "Universal AST constructors for the Python lowering and its prelude. Every
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
    [yang.tails :as tails]))


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
