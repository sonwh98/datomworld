(ns yang.python
  "Yang Python compiler - Compiles Python code into Yin VM Universal AST.

  This is the Python-specific compiler in the Yang compiler collection.
  It transforms Python syntax into the Universal AST format that the Yin VM
  can execute.

  Supported Python features:
  - Literals: numbers, strings, booleans, None
  - Variables
  - Lambda expressions: lambda x: x * 2
  - Function definitions: def f(x): return x * 2
  - Function calls: f(1, 2)
  - Conditionals: if/else expressions
  - Binary operators: +, -, *, /, ==, <, >, and, or
  - Return statements

  The compiler is written in .cljc format to run on both JVM and Node.js."
  (:require [clojure.string :as str])
  (:refer-clojure :exclude [compile]))

;; Forward declarations for mutual recursion
(declare compile-py-expr parse-py-expr)

;;; ============================================================================
;;; Python Tokenizer
;;; ============================================================================

(defn tokenize
  "Tokenize Python source code into tokens.
  Returns a sequence of [token-type value] pairs."
  [source]
  (let [source (str/trim source)
        ;; Token patterns (order matters!)
        patterns [[:number #"^-?\d+\.?\d*"]
                  [:string #"^\"([^\"]*)\""]
                  [:string #"^'([^']*)'"]
                  [:keyword #"^(def|lambda|return|if|else|and|or|not|True|False|None)\b"]
                  [:identifier #"^[a-zA-Z_][a-zA-Z0-9_]*"]
                  [:operator #"^(==|!=|<=|>=|<|>|\+|-|\*|/|=)"]
                  [:lparen #"^\("]
                  [:rparen #"^\)"]
                  [:comma #"^,"]
                  [:colon #"^:"]
                  [:newline #"^\n"]
                  [:whitespace #"^[ \t]+"]]
        tokenize-helper
        (fn tokenize-helper [s tokens]
          (if (empty? s)
            (reverse tokens)
            (let [matched
                  (some (fn [[type pattern]]
                          (when-let [match (re-find pattern s)]
                            (let [matched-str (if (string? match) match (first match))
                                  value (if (= type :string)
                                          (second (re-find pattern s))
                                          matched-str)]
                              {:type type
                               :value value
                               :length (count matched-str)})))
                        patterns)]
              (if matched
                (recur (subs s (:length matched))
                       (if (#{:whitespace :newline} (:type matched))
                         tokens  ;; Skip whitespace and newlines
                         (conj tokens [(:type matched) (:value matched)])))
                (throw (ex-info "Tokenization error"
                                {:remaining s
                                 :tokens (reverse tokens)}))))))]
    (tokenize-helper source '())))

;;; ============================================================================
;;; Python Parser - Produces Python AST
;;; ============================================================================

(defn parse-py-literal
  "Parse a Python literal token."
  [[token-type value]]
  (case token-type
    :number {:py-type :literal
             :value (if (str/includes? value ".")
                      #?(:clj (Double/parseDouble value)
                         :cljs (js/parseFloat value))
                      #?(:clj (Long/parseLong value)
                         :cljs (js/parseInt value)))}
    :string {:py-type :literal
             :value value}
    :keyword (case value
               "True" {:py-type :literal :value true}
               "False" {:py-type :literal :value false}
               "None" {:py-type :literal :value nil}
               nil)
    nil))

(defn parse-py-primary
  "Parse a primary expression (literal, variable, or parenthesized expression)."
  [tokens]
  (let [[token-type value] (first tokens)]
    (cond
      ;; Literal
      (#{:number :string} token-type)
      {:ast (parse-py-literal (first tokens))
       :remaining (rest tokens)}

      ;; Boolean/None keywords
      (and (= token-type :keyword) (contains? #{"True" "False" "None"} value))
      {:ast (parse-py-literal (first tokens))
       :remaining (rest tokens)}

      ;; Variable
      (= token-type :identifier)
      {:ast {:py-type :variable :name (symbol value)}
       :remaining (rest tokens)}

      ;; Parenthesized expression
      (= token-type :lparen)
      (let [{:keys [ast remaining]} (parse-py-expr (rest tokens))]
        (if (= :rparen (first (first remaining)))
          {:ast ast :remaining (rest remaining)}
          (throw (ex-info "Expected closing parenthesis" {:tokens remaining}))))

      :else
      (throw (ex-info "Expected primary expression"
                      {:token [token-type value]})))))

(defn parse-py-call
  "Parse a function call."
  [tokens]
  (let [{:keys [ast remaining]} (parse-py-primary tokens)]
    (if (= :lparen (first (first remaining)))
      ;; Parse arguments
      (let [parse-args
            (fn parse-args [toks args]
              (if (= :rparen (first (first toks)))
                {:args args :remaining (rest toks)}
                (let [{:keys [ast remaining]} (parse-py-expr toks)]
                  (if (= :comma (first (first remaining)))
                    (recur (rest remaining) (conj args ast))
                    (if (= :rparen (first (first remaining)))
                      {:args (conj args ast) :remaining (rest remaining)}
                      (throw (ex-info "Expected comma or closing paren in call"
                                      {:tokens remaining})))))))
            {:keys [args remaining]} (parse-args (rest remaining) [])]
        {:ast {:py-type :call
               :function ast
               :args args}
         :remaining remaining})
      ;; Not a call, just return the primary
      {:ast ast :remaining remaining})))

(defn parse-py-binary-op
  "Parse binary operations with operator precedence."
  [tokens min-precedence]
  (let [precedence {'* 3 '/ 3
                    '+ 2 '- 2
                    '== 1 '!= 1 '< 1 '> 1 '<= 1 '>= 1
                    'and 0 'or 0}
        op-symbol {"*" '* "/" '/ "+" '+ "-" '-
                   "==" '= "!=" '!= "<" '< ">" '> "<=" '<= ">=" '>=
                   "and" 'and "or" 'or}
        {:keys [ast remaining]} (parse-py-call tokens)]
    (loop [left ast
           toks remaining]
      (let [[token-type op-str] (first toks)]
        (if (and (= token-type :operator)
                 (contains? op-symbol op-str))
          (let [op (op-symbol op-str)
                prec (precedence op)]
            (if (and prec (>= prec min-precedence))
              (let [{:keys [ast remaining]}
                    (parse-py-binary-op (rest toks) (inc prec))]
                (recur {:py-type :binop
                        :op op
                        :left left
                        :right ast}
                       remaining))
              {:ast left :remaining toks}))
          {:ast left :remaining toks})))))

(defn parse-py-if-expr
  "Parse Python if expression: x if condition else y"
  [tokens]
  (let [{:keys [ast remaining]} (parse-py-binary-op tokens 0)]
    (if (and (= :keyword (first (first remaining)))
             (= "if" (second (first remaining))))
      (let [{test-ast :ast remaining :remaining}
            (parse-py-binary-op (rest remaining) 0)]
        (if (and (= :keyword (first (first remaining)))
                 (= "else" (second (first remaining))))
          (let [{alt-ast :ast remaining :remaining}
                (parse-py-binary-op (rest remaining) 0)]
            {:ast {:py-type :if
                   :test test-ast
                   :consequent ast
                   :alternate alt-ast}
             :remaining remaining})
          (throw (ex-info "Expected 'else' in if expression" {:tokens remaining}))))
      {:ast ast :remaining remaining})))

(defn parse-py-lambda
  "Parse lambda expression: lambda x, y: x + y"
  [tokens]
  (if (and (= :keyword (first (first tokens)))
           (= "lambda" (second (first tokens))))
    ;; Parse parameters
    (let [parse-params
          (fn parse-params [toks params]
            (let [[token-type value] (first toks)]
              (cond
                (= token-type :colon)
                {:params params :remaining (rest toks)}

                (= token-type :identifier)
                (let [remaining (rest toks)]
                  (if (= :comma (first (first remaining)))
                    (recur (rest remaining) (conj params (symbol value)))
                    (if (= :colon (first (first remaining)))
                      {:params (conj params (symbol value))
                       :remaining (rest remaining)}
                      (throw (ex-info "Expected comma or colon after lambda param"
                                      {:tokens remaining})))))

                :else
                (throw (ex-info "Expected parameter in lambda"
                                {:token [token-type value]})))))
          {:keys [params remaining]} (parse-params (rest tokens) [])
          {:keys [ast remaining]} (parse-py-expr remaining)]
      {:ast {:py-type :lambda
             :params params
             :body ast}
       :remaining remaining})
    ;; Not a lambda
    (parse-py-if-expr tokens)))

(defn parse-py-expr
  "Parse a Python expression."
  [tokens]
  (parse-py-lambda tokens))

(defn parse-py-statement
  "Parse a Python statement (def, return, or expression)."
  [tokens]
  (let [[token-type value] (first tokens)]
    (cond
      ;; def statement
      (and (= token-type :keyword) (= value "def"))
      (let [[_ fname] (second tokens)
            _ (when-not (= :lparen (first (nth tokens 2)))
                (throw (ex-info "Expected ( after function name" {})))
            parse-params
            (fn parse-params [toks params]
              (let [[token-type value] (first toks)]
                (cond
                  (= token-type :rparen)
                  {:params params :remaining (rest toks)}

                  (= token-type :identifier)
                  (let [remaining (rest toks)]
                    (if (= :comma (first (first remaining)))
                      (recur (rest remaining) (conj params (symbol value)))
                      (if (= :rparen (first (first remaining)))
                        {:params (conj params (symbol value))
                         :remaining (rest remaining)}
                        (throw (ex-info "Expected comma or ) in params" {})))))

                  :else
                  (throw (ex-info "Expected parameter" {:token [token-type value]})))))
            {:keys [params remaining]} (parse-params (drop 3 tokens) [])
            _ (when-not (= :colon (first (first remaining)))
                (throw (ex-info "Expected : after def params" {})))
            {:keys [ast remaining]} (parse-py-statement (rest remaining))]
        {:ast {:py-type :def
               :name (symbol fname)
               :params params
               :body ast}
         :remaining remaining})

      ;; return statement
      (and (= token-type :keyword) (= value "return"))
      (let [{:keys [ast remaining]} (parse-py-expr (rest tokens))]
        {:ast {:py-type :return :value ast}
         :remaining remaining})

      ;; expression
      :else
      (parse-py-expr tokens))))

(defn parse-python
  "Parse Python source code into Python AST.
  Returns a Python AST node."
  [source]
  (let [tokens (tokenize source)
        {:keys [ast]} (parse-py-statement tokens)]
    ast))

;;; ============================================================================
;;; Python AST to Universal AST Compiler
;;; ============================================================================

(defn compile-py-literal
  "Compile a Python literal to Universal AST."
  [node]
  {:type :literal
   :value (:value node)})

(defn compile-py-variable
  "Compile a Python variable to Universal AST."
  [node]
  {:type :variable
   :name (:name node)})

(defn compile-py-binop
  "Compile a Python binary operation to Universal AST."
  [node]
  (let [{:keys [op left right]} node
        compiled-left (compile-py-expr left)
        compiled-right (compile-py-expr right)]
    {:type :application
     :operator {:type :variable :name op}
     :operands [compiled-left compiled-right]}))

(defn compile-py-call
  "Compile a Python function call to Universal AST."
  [node]
  (let [{:keys [function args]} node
        compiled-fn (compile-py-expr function)
        compiled-args (mapv compile-py-expr args)]
    {:type :application
     :operator compiled-fn
     :operands compiled-args}))

(defn compile-py-lambda
  "Compile a Python lambda to Universal AST."
  [node]
  (let [{:keys [params body]} node
        compiled-body (compile-py-expr body)]
    {:type :lambda
     :params (vec params)
     :body compiled-body}))

(defn compile-py-if
  "Compile a Python if expression to Universal AST."
  [node]
  (let [{:keys [test consequent alternate]} node]
    {:type :if
     :test (compile-py-expr test)
     :consequent (compile-py-expr consequent)
     :alternate (compile-py-expr alternate)}))

(defn compile-py-def
  "Compile a Python def statement to Universal AST.

  In Python:
    def f(x): return x * 2

  Becomes a lambda:
    (fn [x] (* x 2))"
  [node]
  (let [{:keys [params body]} node
        ;; If body is a return statement, extract the value
        actual-body (if (= :return (:py-type body))
                      (:value body)
                      body)
        compiled-body (compile-py-expr actual-body)]
    {:type :lambda
     :params (vec params)
     :body compiled-body}))

(defn compile-py-expr
  "Compile a Python AST node to Universal AST."
  [node]
  (case (:py-type node)
    :literal (compile-py-literal node)
    :variable (compile-py-variable node)
    :binop (compile-py-binop node)
    :call (compile-py-call node)
    :lambda (compile-py-lambda node)
    :if (compile-py-if node)
    :def (compile-py-def node)
    :return (compile-py-expr (:value node))
    (throw (ex-info "Unknown Python AST node type"
                    {:node node}))))

;;; ============================================================================
;;; Public API
;;; ============================================================================

(defn compile
  "Main compiler entry point.

  Compiles Python source code into Yin VM Universal AST.

  Examples:
    (compile \"42\")
    => {:type :literal, :value 42}

    (compile \"x + 1\")
    => {:type :application
        :operator {:type :variable, :name +}
        :operands [{:type :variable, :name x}
                   {:type :literal, :value 1}]}

    (compile \"lambda x: x * 2\")
    => {:type :lambda
        :params [x]
        :body {:type :application
               :operator {:type :variable, :name *}
               :operands [{:type :variable, :name x}
                          {:type :literal, :value 2}]}}

    (compile \"def double(x): return x * 2\")
    => {:type :lambda
        :params [x]
        :body {:type :application ...}}"
  [source]
  (-> source
      parse-python
      compile-py-expr))
