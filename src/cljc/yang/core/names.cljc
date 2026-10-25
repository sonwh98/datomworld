(ns yang.core.names
  "Canonical renaming of generated names (docs/design/yang.typescript.md
   §6.3, lift L2). A lowering names its temporaries `%<prefix><cst-id>`; those
   names are canonical row slots, so the CST ids behind them would leak into
   content addresses. `canonicalize` renames every generated name to `%<n>`
   by first occurrence, a pure, idempotent and host-independent function of
   the finished tree."
  (:require
    [yin.vm :as vm]))


(def ^:private reserved
  "Generated-looking names that are not renamed: the sequencing discard and
   the safepoint stage's reserved binder."
  #{'%_ 'yang.safepoint/_})


(defn- generated?
  [sym]
  (and (symbol? sym)
       (nil? (namespace sym))
       (= \% (first (name sym)))
       (not (contains? reserved sym))))


(defn- rename
  "`[sym' table]`: `sym`'s canonical name, allocated on its first occurrence."
  [table sym]
  (if (generated? sym)
    (let [table (if (contains? table sym)
                  table
                  (assoc table sym (symbol (str "%" (count table)))))]
      [(with-meta (get table sym) (meta sym)) table])
    [sym table]))


(defn- walk
  "`[node' table]`: `node` renamed in preorder over the row grammar's slots."
  [table node]
  (reduce (fn [[n table] [field kind]]
            (case kind
              :sym (let [[s table] (rename table (get n field))]
                     [(assoc n field s) table])
              :syms (let [[ss table] (reduce (fn [[out table] s]
                                               (let [[s table] (rename table s)]
                                                 [(conj out s) table]))
                                             [[] table]
                                             (get n field))]
                      [(assoc n field ss) table])
              :node (let [[c table] (walk table (get n field))]
                      [(assoc n field c) table])
              :nodes (let [[cs table] (reduce (fn [[out table] c]
                                                (let [[c table] (walk table c)]
                                                  [(conj out c) table]))
                                              [[] table]
                                              (get n field))]
                       [(assoc n field cs) table])
              [n table]))
          [node table]
          (get vm/semantic-bytecode-grammar (:type node))))


(defn canonicalize
  "`node` with every generated name (a symbol beginning with `%`, except
   `%_` and `yang.safepoint/_`) renamed to `%<n>`, `n` counting from 0 in
   the order of first occurrence in a preorder walk over the row grammar's
   slots. Metadata, including safepoint site marks, survives."
  [node]
  (first (walk {} node)))
