(ns yang.php
  "Yang PHP compiler - Compiles PHP code into Yin VM Universal AST.

  Supports:
  - Function definitions (named and anonymous)
  - Closures with 'use' clauses
  - Conditionals (if/elseif/else)
  - Variables ($name)
  - Assignments ($x = val) and Augmented Assignments ($x *= val)
  - Post-increment ($x++)
  - For loops (desugared to recursive lambdas)
  - Basic operators and literals
  - Semicolon-terminated statements
  - Braced blocks
  - Type hints (parsed and ignored)"
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str]))


;; Forward declarations
(declare compile-stmt
         parse-php-expr
         parse-php-statement
         parse-php-block
         parse-php-primary)


;; ============================================================================
;; PHP Tokenizer
;; ============================================================================

(defn tokenize
  "Tokenize PHP source code."
  [source]
  (loop [s (str/trim source)
         tokens []]
    (if (empty? s)
      tokens
      (let
        [patterns
           [[:number #"^\d+\.?\d*"] [:string #"^\"([^\"]*)\""]
            [:string #"^'([^']*)'"]
            [:keyword
             #"^(function|return|if|else|elseif|true|false|null|use|for|int|float|string|bool|void|mixed|array|object)\b"]
            [:variable #"^\$[a-zA-Z_][a-zA-Z0-9_]*"]
            [:identifier #"^[a-zA-Z_][a-zA-Z0-9_]*"]
            [:operator
             #"^(==|!=|<=|>=|<|>|\+\+|\-\-|\+=|\-=|\*=|/=|\+|-|\*|/|&&|\|\||!|=)"]
            [:lparen #"^\("] [:rparen #"^\)"] [:lbrace #"^\{"] [:rbrace #"^\}"]
            [:comma #"^,"] [:semicolon #"^;"] [:colon #"^:"]
            [:whitespace #"^[ \t\n\r]+"]]
         matched
           (some (fn [[type pattern]]
                   (when-let [match (re-find pattern s)]
                     (let [full-match (if (string? match) match (first match))
                           value
                             (if (= type :string) (second match) full-match)]
                       {:type type, :value value, :length (count full-match)})))
                 patterns)]
        (if matched
          (recur (subs s (:length matched))
                 (if (= :whitespace (:type matched))
                   tokens
                   (conj tokens [(:type matched) (:value matched)])))
          (cond (str/starts-with? s "<?php") (recur (subs s 5) tokens)
                (str/starts-with? s "?>") (recur (subs s 2) tokens)
                :else (throw (ex-info "Tokenization error"
                                      {:remaining
                                         (subs s 0 (min 20 (count s)))}))))))))


;; ============================================================================
;; PHP Parser
;; ============================================================================

(defn- match?
  [token type & [val]]
  (and token (= (first token) type) (or (nil? val) (= (second token) val))))


(defn- expect
  [tokens type & [val]]
  (if (match? (first tokens) type val)
    (rest tokens)
    (throw (ex-info (str "Expected " type " " (or val ""))
                    {:token (first tokens), :all (take 5 tokens)}))))


(defn parse-php-literal
  [[type val]]
  (case type
    :number {:php-type :literal,
             :value (if (str/includes? val ".")
                      #?(:clj (Double/parseDouble val)
                         :cljs (js/parseFloat val))
                      #?(:clj (Long/parseLong val)
                         :cljs (js/parseInt val)))}
    :string {:php-type :literal, :value val}
    :keyword (case val
               "true" {:php-type :literal, :value true}
               "false" {:php-type :literal, :value false}
               "null" {:php-type :literal, :value nil}
               nil)
    nil))


(defn- skip-type-hint
  [tokens]
  (let [tok (first tokens)]
    (if (not tok)
      tokens
      (let [[type val] tok]
        (cond
          ;; Skip primitive type keywords
          (and (= :keyword type)
               (#{"int" "float" "string" "bool" "void" "mixed" "array" "object"}
                val))
            (rest tokens)
          ;; Skip identifiers that aren't keywords (likely class names)
          (and (= :identifier type)
               (not (#{"function" "return" "if" "else" "elseif" "use" "for"}
                     val))
               ;; Peek next: if it's a variable or closure capture, this
               ;; was a type hint
               (let [next-tok (second tokens)]
                 (or (= :variable (first next-tok))
                     (and (= :operator (first next-tok))
                          (= "&" (second next-tok))))))
            (rest tokens)
          :else tokens)))))


(defn- parse-params
  "Parse parameter list inside parentheses.
   Tokens should start AFTER the opening paren.
   Returns {:params [names] :remaining tokens-at-rparen}"
  [toks]
  (loop [params []
         t toks]
    (if (match? (first t) :rparen)
      {:params params, :remaining t}
      (let [t (skip-type-hint t)
            [pt pv] (first t)]
        (if (= :variable pt)
          (let [pname (symbol (subs pv 1))
                after-var (rest t)]
            (if (match? (first after-var) :comma)
              (recur (conj params pname) (rest after-var))
              (if (match? (first after-var) :rparen)
                {:params (conj params pname), :remaining after-var}
                (throw (ex-info "Expected , or ) in params"
                                {:token (first after-var)})))))
          (if (match? (first t) :rparen) ; Handle empty params
            {:params params, :remaining t}
            (throw (ex-info "Expected param variable" {:token (first t)}))))))))


(defn parse-php-primary
  [tokens]
  (let [[type val] (first tokens)]
    (cond (and (= :keyword type) (= "function" val))
            (let [tokens (rest tokens)
                  tokens (expect tokens :lparen)
                  {:keys [params remaining]} (parse-params tokens)
                  tokens (expect remaining :rparen)
                  ;; Optional 'use' clause
                  {:keys [use-vars rem-after-use]}
                    (if (match? (first tokens) :keyword "use")
                      (let [after-lparen (expect (rest tokens) :lparen)
                            {:keys [params remaining]}
                              (parse-params after-lparen)]
                        {:use-vars params,
                         :rem-after-use (expect remaining :rparen)})
                      {:use-vars [], :rem-after-use tokens})
                  tokens rem-after-use
                  ;; Optional return type
                  tokens (if (match? (first tokens) :colon)
                           (skip-type-hint (rest tokens))
                           tokens)
                  {:keys [ast remaining]} (parse-php-block tokens)]
              {:ast {:php-type :lambda,
                     :params params,
                     :use-vars use-vars,
                     :body ast},
               :remaining remaining})
          (#{:number :string} type) {:ast (parse-php-literal (first tokens)),
                                     :remaining (rest tokens)}
          (and (= :keyword type) (#{"true" "false" "null"} val))
            {:ast (parse-php-literal (first tokens)), :remaining (rest tokens)}
          (= :variable type) {:ast {:php-type :variable,
                                    :name (symbol (subs val 1))},
                              :remaining (rest tokens)}
          (= :identifier type) {:ast {:php-type :variable, :name (symbol val)},
                                :remaining (rest tokens)}
          (= :lparen type) (let [{:keys [ast remaining]} (parse-php-expr
                                                           (rest tokens))]
                             {:ast ast, :remaining (expect remaining :rparen)})
          :else (throw (ex-info "Expected primary expression"
                                {:token (first tokens)})))))


(defn parse-php-call
  [tokens]
  (let [{:keys [ast remaining]} (parse-php-primary tokens)]
    (loop [current-ast ast
           current-rem remaining]
      (if (match? (first current-rem) :lparen)
        (let [parse-args
                (fn [toks]
                  (loop [args []
                         t (rest toks)]
                    (if (match? (first t) :rparen)
                      {:args args, :remaining (rest t)}
                      (let [{:keys [ast remaining]} (parse-php-expr t)]
                        (if (match? (first remaining) :comma)
                          (recur (conj args ast) (rest remaining))
                          (if (match? (first remaining) :rparen)
                            {:args (conj args ast), :remaining (rest remaining)}
                            (throw (ex-info "Expected , or ) in call"
                                            {:token (first remaining)}))))))))
              {:keys [args remaining]} (parse-args current-rem)]
          (recur {:php-type :call, :function current-ast, :args args}
                 remaining))
        {:ast current-ast, :remaining current-rem}))))


(defn parse-php-unary
  [tokens]
  (let [[type val] (first tokens)]
    (cond (match? (first tokens) :operator "-")
            (let [{:keys [ast remaining]} (parse-php-unary (rest tokens))]
              (if (= (:php-type ast) :literal)
                {:ast (update ast :value -), :remaining remaining}
                {:ast {:php-type :binop,
                       :op '-,
                       :left {:php-type :literal, :value 0},
                       :right ast},
                 :remaining remaining}))
          (match? (first tokens) :operator "!")
            (let [{:keys [ast remaining]} (parse-php-unary (rest tokens))]
              {:ast {:php-type :unaryop, :op 'not, :arg ast},
               :remaining remaining})
          :else (parse-php-call tokens))))


(defn parse-php-binary-op
  [tokens min-prec]
  (let [precedence {'* 3,
                    '/ 3,
                    '+ 2,
                    '- 2,
                    '== 1,
                    '!= 1,
                    '< 1,
                    '> 1,
                    '<= 1,
                    '>= 1,
                    '&& 0,
                    '|| 0}
        {:keys [ast remaining]} (parse-php-unary tokens)]
    (loop [left ast
           toks remaining]
      (let [[type val] (first toks)]
        (if-let [prec (and (= :operator type) (get precedence (symbol val)))]
          (if (>= prec min-prec)
            (let [{right :ast, rem :remaining} (parse-php-binary-op (rest toks)
                                                                    (inc prec))]
              (recur
                {:php-type :binop, :op (symbol val), :left left, :right right}
                rem))
            {:ast left, :remaining toks})
          {:ast left, :remaining toks})))))


(defn parse-php-expr
  [tokens]
  (let [{:keys [ast remaining]} (parse-php-binary-op tokens 0)]
    (cond ; Standard assignment: $x = expr
      (and (= :variable (:php-type ast))
           (match? (first remaining) :operator "="))
        (let [{rhs :ast, rem :remaining} (parse-php-expr (rest remaining))]
          {:ast {:php-type :assignment, :name (:name ast), :value rhs},
           :remaining rem})
      ;; Augmented assignment: $x *= expr -> $x = $x * expr
      (and (= :variable (:php-type ast))
           (#{"++" "--" "+=" "-=" "*=" "/="} (second (first remaining))))
        (let [op-str (second (first remaining))
              rem (rest remaining)]
          (cond (= op-str "++") {:ast {:php-type :assignment,
                                       :name (:name ast),
                                       :value {:php-type :binop,
                                               :op '+,
                                               :left ast,
                                               :right {:php-type :literal,
                                                       :value 1}}},
                                 :remaining rem}
                (= op-str "--") {:ast {:php-type :assignment,
                                       :name (:name ast),
                                       :value {:php-type :binop,
                                               :op '-,
                                               :left ast,
                                               :right {:php-type :literal,
                                                       :value 1}}},
                                 :remaining rem}
                :else
                  (let [op (symbol (subs op-str 0 1))
                        {rhs :ast, rem2 :remaining} (parse-php-expr rem)]
                    {:ast {:php-type :assignment,
                           :name (:name ast),
                           :value
                             {:php-type :binop, :op op, :left ast, :right rhs}},
                     :remaining rem2})))
      :else {:ast ast, :remaining remaining})))


(defn parse-php-block
  [tokens]
  (let [tokens (expect tokens :lbrace)]
    (loop [stmts []
           toks tokens]
      (if (match? (first toks) :rbrace)
        {:ast {:php-type :suite, :stmts stmts}, :remaining (rest toks)}
        (let [{:keys [ast remaining]} (parse-php-statement toks)]
          (recur (conj stmts ast) remaining))))))


(defn parse-php-for
  [tokens]
  (let [tokens (expect (rest tokens) :lparen)
        {init :ast, remaining :remaining} (parse-php-expr tokens)
        remaining (expect remaining :semicolon)
        {test :ast, remaining :remaining} (parse-php-expr remaining)
        remaining (expect remaining :semicolon)
        {upd :ast, remaining :remaining} (parse-php-expr remaining)
        remaining (expect remaining :rparen)
        {body :ast, remaining :remaining} (parse-php-block remaining)]
    {:ast {:php-type :for, :init init, :test test, :update upd, :body body},
     :remaining remaining}))


(defn parse-php-statement
  [tokens]
  (let [[type val] (first tokens)]
    (cond
      ;; return
      (and (= :keyword type) (= "return" val))
        (let [{:keys [ast remaining]} (parse-php-expr (rest tokens))]
          {:ast {:php-type :return, :value ast},
           :remaining (expect remaining :semicolon)})
      ;; function definition (named)
      (and (= :keyword type)
           (= "function" val)
           (= :identifier (first (second tokens))))
        (let [name-val (second (second tokens))
              tokens (drop 2 tokens)
              tokens (expect tokens :lparen)
              {:keys [params remaining]} (parse-params tokens)
              tokens (expect remaining :rparen)
              remaining (if (match? (first tokens) :colon)
                          (skip-type-hint (rest tokens))
                          tokens)
              {:keys [ast remaining]} (parse-php-block remaining)]
          {:ast {:php-type :function,
                 :name (symbol name-val),
                 :params params,
                 :body ast},
           :remaining remaining})
      ;; if / elseif
      (and (= :keyword type) (or (= "if" val) (= "elseif" val)))
        (let [tokens (expect (rest tokens) :lparen)
              {:keys [ast remaining]} (parse-php-expr tokens)
              remaining (expect remaining :rparen)
              {cons :ast, remaining :remaining} (parse-php-block remaining)]
          (cond (match? (first remaining) :keyword "elseif")
                  (let [{alt :ast, remaining :remaining} (parse-php-statement
                                                           remaining)]
                    {:ast {:php-type :if-stmt,
                           :test ast,
                           :consequent cons,
                           :alternate alt},
                     :remaining remaining})
                (match? (first remaining) :keyword "else")
                  (let [remaining (rest remaining)
                        {alt :ast, remaining :remaining}
                          (if (match? (first remaining) :keyword "if")
                            (parse-php-statement remaining)
                            (parse-php-block remaining))]
                    {:ast {:php-type :if-stmt,
                           :test ast,
                           :consequent cons,
                           :alternate alt},
                     :remaining remaining})
                :else {:ast {:php-type :if-stmt,
                             :test ast,
                             :consequent cons,
                             :alternate nil},
                       :remaining remaining}))
      ;; for
      (and (= :keyword type) (= "for" val)) (parse-php-for tokens)
      ;; expression statement
      :else (let [{:keys [ast remaining]} (parse-php-expr tokens)]
              {:ast ast, :remaining (expect remaining :semicolon)}))))


(defn parse-program
  [tokens]
  (loop [stmts []
         toks tokens]
    (if (empty? toks)
      {:php-type :suite, :stmts stmts}
      (let [{:keys [ast remaining]} (parse-php-statement toks)]
        (recur (conj stmts ast) remaining)))))


;; ============================================================================
;; Compiler (Universal AST)
;; ============================================================================

(declare compile-suite)


(def php-op->yin
  {"==" '==,
   "!=" '!=,
   "<" '<,
   ">" '>,
   "<=" '<=,
   ">=" '>=,
   "+" '+,
   "-" '-,
   "*" '*,
   "/" '/,
   "&&" 'and,
   "||" 'or,
   "!" 'not})


(def Z-combinator
  {:type :lambda,
   :params ['f],
   :body
     {:type :application,
      :operator {:type :lambda,
                 :params ['x],
                 :body {:type :application,
                        :operator {:type :variable, :name 'f},
                        :operands
                          [{:type :lambda,
                            :params ['v],
                            :body {:type :application,
                                   :operator
                                     {:type :application,
                                      :operator {:type :variable, :name 'x},
                                      :operands [{:type :variable, :name 'x}]},
                                   :operands [{:type :variable, :name 'v}]}}]}},
      :operands
        [{:type :lambda,
          :params ['x],
          :body {:type :application,
                 :operator {:type :variable, :name 'f},
                 :operands
                   [{:type :lambda,
                     :params ['v],
                     :body {:type :application,
                            :operator {:type :application,
                                       :operator {:type :variable, :name 'x},
                                       :operands [{:type :variable, :name 'x}]},
                            :operands [{:type :variable, :name 'v}]}}]}}]}})


(defn compile-stmt
  [node]
  (case (:php-type node)
    :literal {:type :literal, :value (:value node)}
    :variable {:type :variable, :name (:name node)}
    :binop
      {:type :application,
       :operator
         {:type :variable,
          :name (get php-op->yin (clojure.core/name (:op node)) (:op node))},
       :operands [(compile-stmt (:left node)) (compile-stmt (:right node))]}
    :unaryop {:type :application,
              :operator {:type :variable,
                         :name (get php-op->yin
                                    (clojure.core/name (:op node))
                                    (:op node))},
              :operands [(compile-stmt (:arg node))]}
    :assignment (compile-stmt (:value node))
    :call {:type :application,
           :operator (compile-stmt (:function node)),
           :operands (mapv compile-stmt (:args node))}
    :lambda (let [body (compile-suite (:body node))]
              {:type :lambda, :params (:params node), :body body})
    :return (compile-stmt (:value node))
    :if-stmt {:type :if,
              :test (compile-stmt (:test node)),
              :consequent (compile-suite (:consequent node)),
              :alternate (if (:alternate node)
                           (if (= (:php-type (:alternate node)) :if-stmt)
                             (compile-stmt (:alternate node))
                             (compile-suite (:alternate node)))
                           {:type :literal, :value nil})}
    :suite (compile-suite node)
    :for (let [init (:init node)
               var-name (:name init)
               init-val (compile-stmt (:value init))
               loop-fn (gensym "loop")]
           {:type :application,
            :operator
              {:type :application,
               :operator Z-combinator,
               :operands
                 [{:type :lambda,
                   :params [loop-fn],
                   :body {:type :lambda,
                          :params [var-name],
                          :body {:type :if,
                                 :test (compile-stmt (:test node)),
                                 :consequent
                                   {:type :application,
                                    :operator
                                      {:type :lambda,
                                       :params ['_],
                                       :body {:type :application,
                                              :operator {:type :variable,
                                                         :name loop-fn},
                                              :operands [(compile-stmt
                                                           (:update node))]}},
                                    :operands [(compile-suite (:body node))]},
                                 :alternate {:type :literal, :value nil}}}}]},
            :operands [init-val]})
    (throw (ex-info "Unknown node" {:node node}))))


(defn compile-suite
  [node]
  (let [stmts (:stmts node)]
    (if (empty? stmts)
      {:type :literal, :value nil}
      (if (= 1 (count stmts))
        (compile-stmt (first stmts))
        (let [head (first stmts)
              rest-suite {:php-type :suite, :stmts (rest stmts)}]
          (if (= :assignment (:php-type head))
            {:type :application,
             :operator {:type :lambda,
                        :params [(:name head)],
                        :body (compile-suite rest-suite)},
             :operands [(compile-stmt head)]}
            {:type :application,
             :operator
               {:type :lambda, :params ['_], :body (compile-suite rest-suite)},
             :operands [(compile-stmt head)]}))))))


(defn compile-program
  [ast]
  (let [stmts (:stmts ast)]
    (if (empty? stmts)
      {:type :literal, :value nil}
      (let [head (first stmts)
            rest-prog {:php-type :suite, :stmts (rest stmts)}]
        (cond (= :function (:php-type head))
                {:type :application,
                 :operator {:type :lambda,
                            :params [(:name head)],
                            :body (compile-program rest-prog)},
                 :operands [{:type :application,
                             :operator Z-combinator,
                             :operands [{:type :lambda,
                                         :params [(:name head)],
                                         :body {:type :lambda,
                                                :params (:params head),
                                                :body (compile-suite
                                                        (:body head))}}]}]}
              (= :assignment (:php-type head))
                {:type :application,
                 :operator {:type :lambda,
                            :params [(:name head)],
                            :body (compile-program rest-prog)},
                 :operands [(compile-stmt head)]}
              (= 1 (count stmts)) (compile-stmt head)
              :else {:type :application,
                     :operator {:type :lambda,
                                :params ['_],
                                :body (compile-program rest-prog)},
                     :operands [(compile-stmt head)]})))))


(defn compile
  [source]
  (-> source
      tokenize
      parse-program
      compile-program))
