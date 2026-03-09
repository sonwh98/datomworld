(ns yang.python
  "Yang Python compiler - Compiles Python code into Yin VM Universal AST.

  Supports:
  - Indentation-based blocks
  - Function definitions (def)
  - Conditionals (if/else)
  - Recursion (via let-binding transformation)
  - Basic operators and literals"
  (:refer-clojure :exclude [compile])
  (:require
    [clojure.string :as str]))


;; Forward declarations
(declare compile-py-expr parse-py-expr parse-py-statement)


;; ============================================================================
;; Python Tokenizer (Indentation Aware)
;; ============================================================================

(defn- tokenize-line
  [line]
  (loop [s line
         tokens []]
    (if (empty? s)
      tokens
      (let [patterns
            [[:number #"^\d+\.?\d*"] [:string #"^\"([^\"]*)\""]
             [:string #"^'([^']*)'"]
             [:keyword
              #"^(def|lambda|return|if|else|and|or|not|True|False|None)\b"]
             ;; Allow dotted names (e.g. ffi.call) so frontends can map to
             ;; namespaced operations during compilation.
             [:identifier
              #"^[a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)*"]
             [:operator #"^(==|!=|<=|>=|<|>|\+|-|\*|/|=)"] [:lparen #"^\("]
             [:rparen #"^\)"] [:comma #"^,"] [:colon #"^:"]
             [:whitespace #"^[ \t]+"]]
            matched
            (some
              (fn [[type pattern]]
                (when-let [match (re-find pattern s)]
                  (let [full-match (if (string? match) match (first match))
                        value (if (= type :string) (second match) full-match)]
                    {:type type, :value value, :length (count full-match)})))
              patterns)]
        (if matched
          (recur (subs s (:length matched))
                 (if (= :whitespace (:type matched))
                   tokens
                   (conj tokens [(:type matched) (:value matched)])))
          (throw (ex-info "Tokenization error" {:remaining s})))))))


(defn tokenize
  "Tokenize Python source code handling indentation."
  [source]
  (let [lines (str/split-lines source)
        indents (atom [0])
        tokens (atom [])]
    (doseq [line lines]
      (let [trimmed (str/triml line)]
        ;; Skip empty lines and comments
        (when (and (seq trimmed) (not (str/starts-with? trimmed "#")))
          (let [indent (count (subs line 0 (str/index-of line trimmed)))
                current-indent (peek @indents)]
            (cond (> indent current-indent) (do (swap! indents conj indent)
                                                (swap! tokens conj
                                                       [:indent nil]))
                  (< indent current-indent) (loop []
                                              (let [curr (peek @indents)]
                                                (when (> curr indent)
                                                  (swap! indents pop)
                                                  (swap! tokens conj
                                                         [:dedent nil])
                                                  (recur))))
                  :else nil)
            (swap! tokens into (tokenize-line trimmed))
            (swap! tokens conj [:newline nil])))))
    ;; Close remaining indents
    (while (> (count @indents) 1)
      (swap! indents pop)
      (swap! tokens conj [:dedent nil]))
    @tokens))


;; ============================================================================
;; Python Parser
;; ============================================================================

(defn- match?
  [token type & [val]]
  (and (= (first token) type) (or (nil? val) (= (second token) val))))


(defn- expect
  [tokens type & [val]]
  (if (match? (first tokens) type val)
    (rest tokens)
    (throw (ex-info (str "Expected " type " " val) {:token (first tokens)}))))


(defn parse-py-literal
  [[type val]]
  (case type
    :number {:py-type :literal,
             :value (if (str/includes? val ".")
                      #?(:clj (Double/parseDouble val)
                         :cljs (js/parseFloat val))
                      #?(:clj (Long/parseLong val)
                         :cljs (js/parseInt val)))}
    :string {:py-type :literal, :value val}
    :keyword (case val
               "True" {:py-type :literal, :value true}
               "False" {:py-type :literal, :value false}
               "None" {:py-type :literal, :value nil}
               nil)
    nil))


(defn parse-py-primary
  [tokens]
  (let [[type val] (first tokens)]
    (cond (#{:number :string} type) {:ast (parse-py-literal (first tokens)),
                                     :remaining (rest tokens)}
          (and (= :keyword type) (#{"True" "False" "None"} val))
          {:ast (parse-py-literal (first tokens)), :remaining (rest tokens)}
          (= :identifier type) {:ast {:py-type :variable, :name (symbol val)},
                                :remaining (rest tokens)}
          (= :lparen type) (let [{:keys [ast remaining]} (parse-py-expr
                                                           (rest tokens))]
                             {:ast ast, :remaining (expect remaining :rparen)})
          :else (throw (ex-info "Expected primary" {:token (first tokens)})))))


(defn parse-py-call
  [tokens]
  (let [{:keys [ast remaining]} (parse-py-primary tokens)]
    (if (= :lparen (first (first remaining)))
      (let [parse-args
            (fn parse-args
              [toks args]
              (if (match? (first toks) :rparen)
                {:args args, :remaining (rest toks)}
                (let [{:keys [ast remaining]} (parse-py-expr toks)]
                  (if (match? (first remaining) :comma)
                    (recur (rest remaining) (conj args ast))
                    (if (match? (first remaining) :rparen)
                      {:args (conj args ast), :remaining (rest remaining)}
                      (throw (ex-info "Expected , or )"
                                      {:token (first remaining)})))))))
            {:keys [args remaining]} (parse-args (rest remaining) [])]
        {:ast {:py-type :call, :function ast, :args args},
         :remaining remaining})
      {:ast ast, :remaining remaining})))


(defn parse-py-unary
  [tokens]
  (if (match? (first tokens) :operator "-")
    (let [{:keys [ast remaining]} (parse-py-unary (rest tokens))]
      (if (= (:py-type ast) :literal)
        {:ast (update ast :value -), :remaining remaining}
        {:ast {:py-type :binop,
               :op '-,
               :left {:py-type :literal, :value 0},
               :right ast},
         :remaining remaining}))
    (parse-py-call tokens)))


(defn parse-py-binary-op
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
                    'and 0,
                    'or 0}
        {:keys [ast remaining]} (parse-py-unary tokens)]
    (loop [left ast
           toks remaining]
      (let [[type val] (first toks)]
        (if-let [prec (and (= :operator type) (get precedence (symbol val)))]
          (if (>= prec min-prec)
            (let [{right :ast, rem :remaining} (parse-py-binary-op (rest toks)
                                                                   (inc prec))]
              (recur
                {:py-type :binop, :op (symbol val), :left left, :right right}
                rem))
            {:ast left, :remaining toks})
          {:ast left, :remaining toks})))))


(defn parse-py-if-expr
  [test-expr tokens]
  ;; This is slightly wrong: Python is 'consequent if test else alternate'
  ;; The caller passed the consequent as 'test-expr'
  (let [tokens (rest tokens) ; skip 'if'
        {:keys [ast remaining]} (parse-py-expr tokens) ; parse test
        remaining (expect remaining :keyword "else")
        {alt :ast, remaining :remaining} (parse-py-expr remaining)]
    {:ast {:py-type :if, :test ast, :consequent test-expr, :alternate alt},
     :remaining remaining}))


(defn parse-py-lambda
  [tokens]
  (let [tokens (rest tokens) ; skip 'lambda'
        parse-params (fn [toks]
                       (loop [params []
                              t toks]
                         (if (match? (first t) :colon)
                           {:params params, :remaining (rest t)}
                           (let [[pt pv] (first t)]
                             (if (= :identifier pt)
                               (if (match? (first (rest t)) :comma)
                                 (recur (conj params (symbol pv)) (drop 2 t))
                                 (if (match? (first (rest t)) :colon)
                                   {:params (conj params (symbol pv)),
                                    :remaining (rest (rest t))}
                                   (throw (ex-info "Expected , or :"
                                                   {:token (first (rest t))}))))
                               (throw (ex-info "Expected param"
                                               {:token (first t)})))))))
        {:keys [params remaining]} (parse-params tokens)
        {:keys [ast remaining]} (parse-py-expr remaining)]
    {:ast {:py-type :lambda, :params params, :body ast}, :remaining remaining}))


(defn parse-py-expr
  [tokens]
  (if (match? (first tokens) :keyword "lambda")
    (parse-py-lambda tokens)
    (let [{:keys [ast remaining]} (parse-py-binary-op tokens 0)]
      (if (match? (first remaining) :keyword "if")
        (parse-py-if-expr ast remaining)
        {:ast ast, :remaining remaining}))))


(defn parse-py-suite
  [tokens]
  (if (match? (first tokens) :newline)
    (let [tokens (expect tokens :newline)
          tokens (expect tokens :indent)]
      (loop [stmts []
             toks tokens]
        (if (match? (first toks) :dedent)
          {:ast {:py-type :suite, :stmts stmts}, :remaining (rest toks)}
          (let [{:keys [ast remaining]} (parse-py-statement toks)]
            (recur (conj stmts ast) remaining)))))
    (let [{:keys [ast remaining]} (parse-py-statement tokens)]
      {:ast {:py-type :suite, :stmts [ast]}, :remaining remaining})))


(defn parse-py-statement
  [tokens]
  (let [[type val] (first tokens)]
    (cond
      ;; return
      (and (= :keyword type) (= "return" val))
      (let [{:keys [ast remaining]} (parse-py-expr (rest tokens))]
        {:ast {:py-type :return, :value ast},
         :remaining (expect remaining :newline)})
      ;; def
      (and (= :keyword type) (= "def" val))
      (let [tokens (rest tokens)
            [name-type name-val] (first tokens)
            _ (when-not (= :identifier name-type)
                (throw (ex-info "Expected func name"
                                {:token (first tokens)})))
            tokens (rest tokens)
            tokens (expect tokens :lparen)
            ;; Parse params
            parse-params
            (fn [toks]
              (loop [params []
                     t toks]
                (if (match? (first t) :rparen)
                  {:params params, :remaining (rest t)}
                  (let [[pt pv] (first t)]
                    (if (= :identifier pt)
                      (if (match? (first (rest t)) :comma)
                        (recur (conj params (symbol pv)) (drop 2 t))
                        (if (match? (first (rest t)) :rparen)
                          {:params (conj params (symbol pv)),
                           :remaining (drop 2 t)}
                          (throw (ex-info "Expected , or )"
                                          {:token (first (rest t))}))))
                      (throw (ex-info "Expected param"
                                      {:token (first t)})))))))
            {:keys [params remaining]} (parse-params tokens)
            remaining (expect remaining :colon)
            {:keys [ast remaining]} (parse-py-suite remaining)]
        {:ast {:py-type :def,
               :name (symbol name-val),
               :params params,
               :body ast},
         :remaining remaining})
      ;; if (statement)
      (and (= :keyword type) (= "if" val))
      (let [{:keys [ast remaining]} (parse-py-expr (rest tokens))
            remaining (expect remaining :colon)
            {cons :ast, remaining :remaining} (parse-py-suite remaining)]
        (if (and (match? (first remaining) :keyword "else")
                 (match? (first (rest remaining)) :colon))
          (let [{alt :ast, remaining :remaining} (parse-py-suite
                                                   (drop 2 remaining))]
            {:ast {:py-type :if-stmt,
                   :test ast,
                   :consequent cons,
                   :alternate alt},
             :remaining remaining})
          {:ast
           {:py-type :if-stmt, :test ast, :consequent cons, :alternate nil},
           :remaining remaining}))
      ;; expression stmt
      :else (let [{:keys [ast remaining]} (parse-py-expr tokens)]
              {:ast ast, :remaining (expect remaining :newline)}))))


(defn parse-program
  [tokens]
  (loop [stmts []
         toks tokens]
    (if (empty? toks)
      {:py-type :suite, :stmts stmts}
      (let [{:keys [ast remaining]} (parse-py-statement toks)]
        (recur (conj stmts ast) remaining)))))


;; ============================================================================
;; Compiler (Universal AST)
;; ============================================================================

(declare compile-stmt)


(defn compile-suite
  ([node] (compile-suite node false))
  ([node tail?]
   (let [stmts (:stmts node)]
     (if (= 1 (count stmts))
       (compile-stmt (first stmts) tail?)
       (let [s1 (compile-stmt (first stmts) false)
             s2 (compile-suite {:stmts (rest stmts)} tail?)]
         (cond-> {:type :application,
                  :operator {:type :lambda, :params ['_], :body s2},
                  :operands [s1]}
           tail? (assoc :tail? true)))))))


(def py-op->yin
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
   "and" 'and,
   "or" 'or,
   "not" 'not})


(defn- compile-ffi-call
  "Compile a Python ffi.call form to the Universal AST :ffi/call node.

   Python: ffi.call(\"op/echo\", 1, 2)
   AST:    {:type :ffi/call, :op :op/echo, :operands [...]}"
  [args tail?]
  (let [[op-node & ffi-args] args]
    (when-not op-node
      (throw (ex-info "ffi.call requires op as first argument"
                      {:args args})))
    (let [op-value (when (= :literal (:py-type op-node))
                     (:value op-node))]
      (when-not (and (string? op-value) (not (str/blank? op-value)))
        (throw (ex-info "ffi.call op must be a non-empty string literal"
                        {:op op-node})))
      (cond-> {:type :ffi/call,
               :op (keyword op-value),
               :operands (mapv #(compile-stmt % false) ffi-args)}
        tail? (assoc :tail? true)))))


(defn compile-stmt
  ([node] (compile-stmt node false))
  ([node tail?]
   (case (:py-type node)
     :literal {:type :literal, :value (:value node)}
     :variable {:type :variable, :name (:name node)}
     :binop (cond-> {:type :application,
                     :operator {:type :variable,
                                :name (get py-op->yin
                                           (clojure.core/name (:op node))
                                           (:op node))},
                     :operands [(compile-stmt (:left node) false)
                                (compile-stmt (:right node) false)]}
              tail? (assoc :tail? true))
     :call (if (and (= :variable (:py-type (:function node)))
                    (= 'ffi.call (:name (:function node))))
             (compile-ffi-call (:args node) tail?)
             (cond-> {:type :application,
                      :operator (compile-stmt (:function node) false),
                      :operands (mapv #(compile-stmt % false) (:args node))}
               tail? (assoc :tail? true)))
     :lambda (let [body (compile-stmt (:body node) true)]
               (cond-> {:type :lambda, :params (:params node), :body body}
                 tail? (assoc :tail? true)))
     :return (compile-stmt (:value node) tail?)
     :if (cond-> {:type :if,
                  :test (compile-stmt (:test node) false),
                  :consequent (compile-stmt (:consequent node) tail?),
                  :alternate (compile-stmt (:alternate node) tail?)}
           tail? (assoc :tail? true))
     :if-stmt (cond-> {:type :if,
                       :test (compile-stmt (:test node) false),
                       :consequent (compile-suite (:consequent node) tail?),
                       :alternate (if (:alternate node)
                                    (compile-suite (:alternate node) tail?)
                                    {:type :literal, :value nil})}
                tail? (assoc :tail? true))
     (throw (ex-info "Unknown node" {:node node})))))


(def Z-combinator
  "The Z combinator (strict fixed-point combinator) for recursion.
   Z = λf. (λx. f (λv. x x v)) (λx. f (λv. x x v))"
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


(defn compile-program
  ([ast] (compile-program ast true))
  ([ast tail?]
   ;; Handle top-level definitions by wrapping in let/lambda application.
   ;; [def f..., call f] -> ((fn [f] call f) (Z (fn [f] (fn [args] body))))
   (let [stmts (:stmts ast)]
     (if (empty? stmts)
       (cond-> {:type :literal, :value nil} tail? (assoc :tail? true))
       (let [head (first stmts)]
         (if (= :def (:py-type head))
           (if (empty? (rest stmts))
             ;; Last statement is a def, return the bound value (the
             ;; lambda)
             (cond-> {:type :application,
                      :operator Z-combinator,
                      :operands [{:type :lambda,
                                  :params [(:name head)],
                                  :body {:type :lambda,
                                         :params (:params head),
                                         :body (compile-suite (:body head)
                                                              true)}}]}
               tail? (assoc :tail? true))
             ;; It's a definition: let name = (Z ...) in rest
             (cond-> {:type :application,
                      :operator {:type :lambda,
                                 :params [(:name head)],
                                 :body (compile-program {:stmts (rest stmts)}
                                                        tail?)},
                      :operands [{:type :application,
                                  :operator Z-combinator,
                                  :operands [{:type :lambda,
                                              :params [(:name head)],
                                              :body {:type :lambda,
                                                     :params (:params head),
                                                     :body (compile-suite
                                                             (:body head)
                                                             true)}}]}]}
               tail? (assoc :tail? true)))
           ;; Not a definition
           (if (= 1 (count stmts))
             (compile-stmt head tail?)
             ;; Sequence
             (cond-> {:type :application,
                      :operator {:type :lambda,
                                 :params ['_],
                                 :body (compile-program {:stmts (rest stmts)}
                                                        tail?)},
                      :operands [(compile-stmt head false)]}
               tail? (assoc :tail? true)))))))))


(defn compile
  [source]
  (-> source
      tokenize
      parse-program
      compile-program))
