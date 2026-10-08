(ns yang.python.antlr.lower-portable-test
  "The lowering on every host from hand-built CST packets: no parser needed,
   so this runs on the JVM and on Node. Covers the unknown-rule refusal,
   tree validation, a golden, and the stage transform's routing."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.uast :as u]))


(defn packet
  "A CST packet from a nested form: `[rule child ...]` for a rule and
   `[type text]` (type a string) for a token. Spans are zero-width: the
   lowering reads them only for diagnostics. Ids are preorder, as the
   parser numbers them, so generated names match a parsed packet's."
  [form]
  (let [out (volatile! [])]
    (letfn [(walk
              [f]
              (let [id (count @out)]
                (if (string? (first f))
                  (vswap! out conj {:id id, :kind :token, :type (first f),
                                    :text (second f), :span [0 0]})
                  (do (vswap! out conj nil)
                      (let [kids (mapv walk (rest f))]
                        (vswap! out assoc id {:id id, :kind :rule,
                                              :rule (name (first f)),
                                              :children kids, :span [0 0]}))))
                id))]
      (walk form))
    {:yang.cst/version 1,
     :yang.cst/unit [:hand 0],
     :yang.cst/grammar "hand",
     :yang.cst/outcome :yang.cst/ok,
     :yang.cst/root 0,
     :yang.cst/nodes @out,
     :yang.cst/complete true}))


(defn- chain
  "Single-child rule chain from `rules` down to `leaf`."
  [rules leaf]
  (reduce (fn [inner r] [r inner]) leaf (reverse rules)))


(def ^:private expr-chain
  [:testlist_star_expr :test :or_test :and_test :not_test :comparison :expr
   :atom_expr :atom])


(defn- statement
  [expr-stmt]
  [:stmt [:simple_stmts [:simple_stmt expr-stmt] ["NEWLINE" "\n"]]])


(def ^:private x-equals-1
  (packet [:file_input
           (statement [:expr_stmt
                       (chain expr-chain [:name ["NAME" "x"]])
                       ["ASSIGN" "="]
                       (chain expr-chain ["NUMBER" "1"])])
           ["EOF" "<EOF>"]]))


(deftest hand-packet-golden-test
  (is (= (u/then (u/app (u/v 'py/global-set) (u/v '%globals)
                        (u/lit {:py/str "x"}) (u/lit 1))
                 (u/lit :py/None))
         (lower/lower-module-body x-equals-1))))


(defn- refusal
  [pk]
  (try (lower/lower-module-body pk)
       nil
       (catch #?(:cljd Object :clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (select-keys (ex-data e) [:yang.python.antlr/diagnostic :rule]))))


(deftest unknown-rule-fails-qualified-test
  (testing "a rule with no arm is never dropped: the error names it"
    (is (= {:yang.python.antlr/diagnostic :yang.python.antlr/unhandled-rule,
            :rule "no_such_rule"}
           (refusal (packet [:file_input [:stmt [:no_such_rule]]
                             ["EOF" "<EOF>"]])))))
  (testing "a consumed rule reaching the dispatch alone is unhandled too"
    (is (= {:yang.python.antlr/diagnostic :yang.python.antlr/unhandled-rule,
            :rule "trailer"}
           (refusal (packet [:file_input [:stmt [:trailer]]
                             ["EOF" "<EOF>"]])))))
  (testing "an unsupported grammar rule names its construct"
    (is (= {:yang.python.antlr/diagnostic :yang.python.antlr/unsupported,
            :rule "match_stmt"}
           (refusal (packet [:file_input [:stmt [:compound_stmt [:match_stmt]]]
                             ["EOF" "<EOF>"]]))))))


(deftest malformed-tree-is-refused-test
  (doseq [[label pk]
          [["a child edge pointing back"
            (assoc-in x-equals-1 [:yang.cst/nodes 1 :children] [0])]
           ["a node reached twice"
            (assoc-in x-equals-1 [:yang.cst/nodes 0 :children] [1 1])]
           ["an id that is not its index"
            (assoc-in x-equals-1 [:yang.cst/nodes 3 :id] 7)]
           ["no root" (dissoc x-equals-1 :yang.cst/root)]]]
    (testing label
      (is (= :yang.python.antlr/malformed-cst
             (:yang.python.antlr/diagnostic (refusal pk)))))))


(deftest transform-routes-programs-and-diagnostics-test
  (let [[_ [[port envelope]]] (lower/lower-transform {} x-equals-1)]
    (is (= :program port))
    (is (= {:yin/source-medium lower/program-medium,
            :yin/batch-token [:hand 0],
            :yin/batch [(lower/lower-packet x-equals-1)],
            :yin/root 0}
           envelope)
        "a source envelope whose one member is the map AST"))
  (let [[_ out] (lower/lower-transform
                  {}
                  {:yang.cst/unit [:hand 1],
                   :yang.cst/outcome :yang.cst/syntax-error,
                   :yang.cst/errors [{:kind :syntax-error, :message "m"}]})]
    (is (= [[:diagnostics {:yang.cst/unit [:hand 1],
                           :yang.python.antlr/diagnostic
                           :yang.python.antlr/syntax-error,
                           :errors [{:kind :syntax-error, :message "m"}]}]]
           out)))
  (let [[_ [[port d]]] (lower/lower-transform
                         {}
                         (packet [:file_input [:stmt [:no_such_rule]]
                                  ["EOF" "<EOF>"]]))]
    (is (= :diagnostics port))
    (is (= :yang.python.antlr/unhandled-rule (:yang.python.antlr/diagnostic d)))
    (is (= "no_such_rule" (:rule d)))))


(deftest linked-wrapper-test
  (let [main (u/app (u/v 'py/run-main)
                    (u/lam ['%globals '%globals-fn]
                           (lower/lower-module-body x-equals-1)))
        linked (lower/lower-packet x-equals-1 {:prelude :linked})
        symbols (filter symbol? (tree-seq coll? seq linked))]
    (testing "require py, allocate the task's runtime state, run the body"
      (is (= (u/mark-tails
               (u/seq-nodes [(u/app (u/v 'require) (u/lit 'py))
                             (u/app (u/v 'py/init!))
                             main]))
             linked)))
    (testing "the linked program holds no definition and no runtime key"
      (is (not-any? #{'yin/def} symbols))
      (is (not-any? #(#{"py.b" "py.rt"} (namespace %)) symbols)))
    (testing "the bundled profile is the default"
      (is (= (lower/lower-packet x-equals-1)
             (lower/lower-packet x-equals-1 {:prelude :bundled}))))
    (testing "the stage state selects the profile"
      (let [[_ [[_ envelope]]] (lower/lower-transform {:prelude :linked}
                                                      x-equals-1)]
        (is (= [linked] (:yin/batch envelope)))))))
