(ns yang.typescript.antlr.lower-portable-test
  "The TypeScript lowering on every host from hand-built CST packets: no
   parser needed, so this runs on the JVM, Node and Dart. Covers the
   unknown-rule and typed refusals, tree validation, goldens, the
   canonical-renaming law, site-mark survival through the projection and
   the stage transform's routing."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.jing.cbor :as cbor]
    [yang.core.names :as names]
    [yang.core.uast :as u]
    [yang.typescript.antlr.lower :as lower]))


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


(defn program
  "A program packet over the statement forms `stmts`."
  [& stmts]
  (packet (into [:program
                 (into [:sourceElements]
                       (map (fn [s] [:sourceElement [:statement s]]) stmts))]
                [["EOF" "<EOF>"]])))


(defn num-expr
  [text]
  [:singleExpression [:literal [:numericLiteral ["DecimalLiteral" text]]]])


(defn name-expr
  [text]
  [:singleExpression [:identifierName [:identifier ["Identifier" text]]]])


(defn var-stmt
  [modifier id init]
  [:variableStatement
   [:varModifier [(str modifier) modifier]]
   [:variableDeclarationList
    [:variableDeclaration
     [:identifierOrKeyWord [:identifier ["Identifier" id]]]
     ["Assign" "="]
     init]]
   ["SemiColon" ";"]])


(defn log-stmt
  "The expression statement `console.log(arg);` over singleExpression form
   `arg`."
  [arg]
  [:expressionStatement
   [:expressionSequence
    [:singleExpression
     [:singleExpression
      (name-expr "console")
      ["Dot" "."]
      [:identifierName [:identifier ["Identifier" "log"]]]]
     [:arguments
      ["OpenParen" "("]
      [:argumentList [:argument arg]]
      ["CloseParen" ")"]]]]
   ["SemiColon" ";"]])


(def ^:private var-x
  (program (var-stmt "var" "x" (num-expr "1"))))


(def ^:private while-loop
  (program [:iterationStatement
            ["While" "while"] ["OpenParen" "("]
            [:expressionSequence (name-expr "x")]
            ["CloseParen" ")"]
            [:statement [:emptyStatement_ ["SemiColon" ";"]]]]))


(def ^:private function-f
  (program [:functionDeclaration
            ["Function_" "function"]
            [:identifier ["Identifier" "f"]]
            [:callSignature ["OpenParen" "("] ["CloseParen" ")"]]
            ["OpenBrace" "{"]
            [:functionBody]
            ["CloseBrace" "}"]]))


(def ^:private class-a
  "class A { m() { return 1; } }"
  [:classDeclaration
   ["Class" "class"]
   [:identifier ["Identifier" "A"]]
   [:classHeritage]
   [:classTail
    ["OpenBrace" "{"]
    [:classElement
     [:propertyMemberDeclaration
      [:propertyMemberBase]
      [:classElementName
       [:propertyName [:identifierName [:identifier ["Identifier" "m"]]]]]
      [:callSignature ["OpenParen" "("] ["CloseParen" ")"]]
      ["OpenBrace" "{"]
      [:functionBody
       [:sourceElements
        [:sourceElement
         [:statement
          [:returnStatement
           ["Return" "return"]
           [:expressionSequence (num-expr "1")]
           [:eos ["SemiColon" ";"]]]]]]]
      ["CloseBrace" "}"]]]
    ["CloseBrace" "}"]]])


(def ^:private class-b
  "class B extends A { constructor() { super(); } }"
  [:classDeclaration
   ["Class" "class"]
   [:identifier ["Identifier" "B"]]
   [:classHeritage
    [:classExtendsClause ["Extends" "extends"] (name-expr "A")]]
   [:classTail
    ["OpenBrace" "{"]
    [:classElement
     [:constructorDeclaration
      ["Constructor" "constructor"]
      ["OpenParen" "("]
      ["CloseParen" ")"]
      ["OpenBrace" "{"]
      [:functionBody
       [:sourceElements
        [:sourceElement
         [:statement
          [:expressionStatement
           [:expressionSequence
            [:singleExpression
             [:singleExpression
              [:identifierName [:reservedWord [:keyword ["Super" "super"]]]]]
             [:arguments ["OpenParen" "("] ["CloseParen" ")"]]]]
           ["SemiColon" ";"]]]]]]
      ["CloseBrace" "}"]]]
    ["CloseBrace" "}"]]])


(def ^:private classes
  (program class-a class-b))


(defn- diagnostic
  [pk]
  (try (lower/lower-module-body pk)
       nil
       (catch #?(:cljd Object
                 :clj clojure.lang.ExceptionInfo
                 :cljs ExceptionInfo)
              e
         (select-keys (ex-data e)
                      [:yang.ts/diagnostic :rule :construct :slice]))))


(defn- nodes
  [tree]
  (tree-seq #(or (map? %) (sequential? %))
            #(if (map? %) (vals %) %)
            tree))


(deftest hand-packet-golden-test
  (is (= (u/app (u/lam ['x]
                       (u/then (u/then (u/app (u/v 'cell/set!)
                                              (u/v 'x)
                                              (u/lit (cbor/float64 1.0)))
                                       (u/lit :js/undefined))
                               (u/lit :js/undefined)))
                (u/app (u/v 'cell/new) (u/lit :js/undefined)))
         (lower/lower-module-body var-x))))


(deftest unknown-rule-fails-qualified-test
  (testing "a rule with no arm is never dropped: the error names it"
    (is (= {:yang.ts/diagnostic :yang.ts/unhandled-rule, :rule "no_such_rule"}
           (diagnostic (program [:no_such_rule])))))
  (testing "an unsupported grammar rule names its construct and slice"
    (is (= {:yang.ts/diagnostic :yang.ts/unsupported,
            :rule "namespaceDeclaration",
            :construct "namespace",
            :slice :T9}
           (diagnostic (program [:namespaceDeclaration])))))
  (testing "a type-only rule refuses as type syntax"
    (is (= {:yang.ts/diagnostic :yang.ts/unsupported,
            :rule "interfaceDeclaration",
            :construct "type syntax",
            :slice :T8}
           (diagnostic (program [:interfaceDeclaration]))))))


(deftest malformed-tree-is-refused-test
  (doseq [[label pk]
          [["a child edge pointing back"
            (assoc-in var-x [:yang.cst/nodes 1 :children] [0])]
           ["a node reached twice"
            (assoc-in var-x [:yang.cst/nodes 0 :children] [1 1])]
           ["an id that is not its index"
            (assoc-in var-x [:yang.cst/nodes 3 :id] 7)]
           ["no root" (dissoc var-x :yang.cst/root)]]]
    (testing label
      (is (= :yang.ts/malformed-cst
             (:yang.ts/diagnostic (diagnostic pk)))))))


(deftest canonical-renaming-test
  (doseq [pk [while-loop function-f var-x]]
    (let [p (lower/lower-packet pk)]
      (is (= p (names/canonicalize p)) "idempotent on a lowered program")
      (is (every? (fn [s]
                    (or (= '%_ s) (re-matches #"%\d+" (name s))))
                  (filter #(and (symbol? %) (= \% (first (name %))))
                          (nodes p)))
          "every generated name is canonical")))
  (testing "the names are numbered by first occurrence"
    (let [p (lower/lower-packet while-loop)]
      (is (some #{'%0} (nodes p)))))
  (testing "the same packet lowers to the same program"
    (is (= (lower/lower-packet while-loop) (lower/lower-packet while-loop)))))


(deftest site-marks-survive-test
  (let [marks (fn [pk]
                (keep #(:yang/site (meta %)) (nodes (lower/lower-packet pk))))]
    (is (= [:loop] (vec (marks while-loop))))
    (is (= [[:call "f" 0]] (vec (marks function-f))))))


(deftest class-fixtures-test
  (testing "the two class programs lower; the renaming law and site marks hold"
    (let [p (lower/lower-packet classes)
          marks (set (keep #(:yang/site (meta %)) (nodes p)))]
      (is (= p (names/canonicalize p)) "idempotent on a lowered program")
      (is (every? (fn [s]
                    (or (= '%_ s) (re-matches #"%\d+" (name s))))
                  (filter #(and (symbol? %) (= \% (first (name %))))
                          (nodes p)))
          "every generated name is canonical")
      (is (contains? marks [:call "m" 0]) "the method is a call site")
      (is (contains? marks [:call "B" 0]) "the constructor is a call site")
      (is (contains? marks [:call "A" 0]) "so is the default constructor")
      (is (some #(and (map? %) (= (u/v 'js/super-call) (:operator %)))
                (nodes p)))
      (is (some #(and (map? %) (= (u/v 'js/make-class) (:operator %)))
                (nodes p)))))
  (testing "the same packet lowers to the same program"
    (is (= (lower/lower-packet classes) (lower/lower-packet classes)))))


(deftest linked-and-bundled-profiles-test
  (let [bundled (lower/lower-packet var-x)
        linked (lower/lower-packet var-x {:prelude :linked})]
    (is (not= bundled linked))
    (is (< (count (pr-str linked)) (count (pr-str bundled)))
        "the linked program carries no prelude tree")
    (is (some #(= (u/lit 'js) %) (nodes linked)) "it requires the module js")
    (is (some #(and (map? %) (= (u/v 'js/init!) (:operator %))) (nodes linked))
        "and allocates the task's state")
    (is (some #(and (map? %) (= (u/v 'js/run-main) (:operator %)))
              (nodes bundled)))))


(deftest transform-routes-programs-and-diagnostics-test
  (let [[_ [[port envelope]]] (lower/lower-transform {} var-x)]
    (is (= :program port))
    (is (= {:yin/source-medium lower/program-medium,
            :yin/batch-token [:hand 0],
            :yin/batch [(lower/lower-packet var-x)],
            :yin/root 0}
           envelope)
        "a source envelope whose one member is the map AST"))
  (let [[_ out] (lower/lower-transform
                  {}
                  {:yang.cst/unit [:hand 1],
                   :yang.cst/outcome :yang.cst/syntax-error,
                   :yang.cst/errors [{:kind :syntax-error, :message "m"}]})]
    (is (= [[:diagnostics {:yang.cst/unit [:hand 1],
                           :yang.ts/diagnostic :yang.ts/syntax-error,
                           :errors [{:kind :syntax-error, :message "m"}]}]]
           out)))
  (let [[_ [[port d]]] (lower/lower-transform
                         {}
                         (program [:namespaceDeclaration]))]
    (is (= :diagnostics port))
    (is (= :yang.ts/unsupported (:yang.ts/diagnostic d)))
    (is (= "namespace" (:construct d))))
  (let [[_ [[port d]]] (lower/lower-transform
                         {}
                         (program [:breakStatement ["Break" "break"]]))]
    (is (= :diagnostics port))
    (is (= :yang.ts/early-error (:yang.ts/diagnostic d)))))
