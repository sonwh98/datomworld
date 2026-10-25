(ns yang.php.antlr.lower
  "The PHP lowering interpreter: CST packets in, Universal AST program
   batches out (docs/design/yang.php.md sections 3, 7). Portable.

   Per packet: validate the tree, analyze scopes (`yang.php.antlr.scope`),
   then lower with one `case` arm per grammar rule. A rule P1 does not lower
   fails with `:yang.php/unsupported` naming the rule and construct; a rule
   with no arm fails with `:yang.php/unhandled-rule`. Nothing is silently
   dropped.

   Decided lowering rules (collab/1792905000000 plan):
   - The unit is its hoisted top-level function declarations, then its
     statements in order, run under `php/run-main`.
   - Inline HTML is `(php/echo \"bytes\")`; `echo a, b` is
     `(php/echo (php/to-string a))` per operand; a PHP string is a byte
     carrier (a latin-1 string), literal text resolved here to UTF-8 bytes.
   - `&&`, `||`, `and`, `or`, `?:` are `:if` nodes over `php/to-bool`, never
     applications, so they short circuit. Every other operator is one
     application of a `php/` function of the prelude.
   - A call `f(a, b)` resolves `f` before any argument runs:
     `(let [%f (php/resolve-fn \"f\" \"F\")] (php/call %f [a b]))`.
   - Generated names are `%<prefix><cst-id>`; `%` cannot begin a PHP name, so
     no guest name shadows a name the lowering emits. The lowering never
     names a runtime key (`php.rt/*`).

   Diagnostic namespace: \"yang.php\"."
  (:require
    [clojure.string :as str]
    [dao.host.text :as text]
    [dao.jing.cbor :as cbor]
    [yang.antlr.packet :as p]
    [yang.core.lower :as core]
    [yang.core.stage :as core-stage]
    [yang.core.uast :as u]
    [yang.php.antlr.prelude :as prelude]
    [yang.php.antlr.scope :as scope]
    [yin.vm.integer :as integer]))


;; =============================================================================
;; Diagnostics
;; =============================================================================

(def ^:private diagnostic-ns "yang.php")


(defn- unsupported!
  [n construct]
  (core/unsupported! diagnostic-ns "PHP" n construct))


(def unsupported-rules
  "Grammar rules P1 does not lower, with the construct each names."
  {"ifStatement" :if,
   "whileStatement" :while,
   "doWhileStatement" :do-while,
   "forStatement" :for,
   "switchStatement" :switch,
   "breakStatement" :break,
   "continueStatement" :continue,
   "returnStatement" :return,
   "globalStatement" :global,
   "staticVariableStatement" :static,
   "unsetStatement" :unset,
   "foreachStatement" :foreach,
   "tryCatchFinally" :try,
   "throwStatement" :throw,
   "gotoStatement" :goto,
   "declareStatement" :declare,
   "functionDeclaration" :function-declaration,
   "classDeclaration" :class,
   "useDeclaration" :use,
   "namespaceDeclaration" :namespace,
   "globalConstantDeclaration" :const,
   "enumDeclaration" :enum,
   "importStatement" :import,
   "arrayCreation" :array,
   "newExpr" :new,
   "lambdaFunctionExpr" :closure,
   "matchExpr" :match,
   "yieldExpression" :yield,
   "scriptText" :inline-script,
   "classConstant" :class-constant,
   "magicConstant" :magic-constant})


(def ^:private prefix-refusals
  "Expression forms led by a keyword or operator token."
  {"SuppressWarnings" :silence,
   "Clone" :clone,
   "New" :new,
   "IsSet" :isset,
   "Empty" :empty,
   "Exit" :exit,
   "Eval" :eval,
   "Include" :include,
   "IncludeOnce" :include,
   "Require" :include,
   "RequireOnce" :include,
   "Throw" :throw,
   "Yield" :yield,
   "List" :list,
   "Inc" :inc-dec,
   "Dec" :inc-dec})


;; =============================================================================
;; Bytes
;; =============================================================================

(defn- bytes->str
  "The latin-1 carrier of the byte values `bs` (a PHP string)."
  [bs]
  (text/units->string (map #(bit-and % 255) bs)))


(defn- utf8-of-code-point
  "The UTF-8 bytes of one code point."
  [cp]
  (cond
    (< cp 0x80) [cp]
    (< cp 0x800) [(bit-or 0xC0 (bit-shift-right cp 6))
                  (bit-or 0x80 (bit-and cp 0x3F))]
    (< cp 0x10000) [(bit-or 0xE0 (bit-shift-right cp 12))
                    (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F))
                    (bit-or 0x80 (bit-and cp 0x3F))]
    :else [(bit-or 0xF0 (bit-shift-right cp 18))
           (bit-or 0x80 (bit-and (bit-shift-right cp 12) 0x3F))
           (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F))
           (bit-or 0x80 (bit-and cp 0x3F))]))


(defn- source-bytes
  "The UTF-8 bytes of source text `s`, as a vector of byte values."
  [s]
  (mapv #(bit-and % 255) (seq (text/utf8-encode s))))


(defn- byte-string
  "The PHP string of source text `s`."
  [s]
  (bytes->str (source-bytes s)))


(defn- hex-value
  [digits]
  (reduce (fn [acc ch] (+ (* acc 16) (str/index-of "0123456789abcdef" (str/lower-case (str ch)))))
          0
          digits))


(defn- octal-value
  [digits]
  (reduce (fn [acc ch] (+ (* acc 8) (str/index-of "01234567" (str ch)))) 0 digits))


(def ^:private simple-escapes
  {"n" 10, "t" 9, "r" 13, "v" 11, "e" 27, "f" 12, "\\" 92, "$" 36, "\"" 34})


(defn- unescape-double
  "The bytes of the double-quoted string body `body` after PHP's escapes."
  [body]
  (let [len (count body)]
    (loop [i 0
           out []]
      (if (>= i len)
        out
        (let [c (subs body i (inc i))
              e (when (< (inc i) len) (subs body (inc i) (+ i 2)))]
          (cond
            (not (and (= c "\\") e)) (recur (inc i) (into out (source-bytes c)))
            (contains? simple-escapes e)
            (recur (+ i 2) (conj out (get simple-escapes e)))
            (re-matches #"[0-7]" e)
            (let [digits (re-find #"^[0-7]{1,3}" (subs body (inc i)))]
              (recur (+ i 1 (count digits))
                     (conj out (bit-and (octal-value digits) 255))))
            (and (= e "x") (re-find #"^[0-9a-fA-F]{1,2}" (subs body (+ i 2))))
            (let [digits (re-find #"^[0-9a-fA-F]{1,2}" (subs body (+ i 2)))]
              (recur (+ i 2 (count digits)) (conj out (hex-value digits))))
            (and (= e "u") (re-find #"^\{([0-9a-fA-F]+)\}" (subs body (+ i 2))))
            (let [[whole digits] (re-find #"^\{([0-9a-fA-F]+)\}" (subs body (+ i 2)))]
              (recur (+ i 2 (count whole)) (into out (utf8-of-code-point (hex-value digits)))))
            :else (recur (+ i 2) (into out (source-bytes (str c e))))))))))


(defn- unescape-single
  "The text of the single-quoted string body `body`: only `\\\\` and `\\'`
   are escapes."
  [body]
  (str/replace (str/replace (str/replace body "\\\\" "\u0000") "\\'" "'")
               "\u0000"
               "\\"))


;; =============================================================================
;; Literals and constants
;; =============================================================================

(defn- app*
  [op-sym & args]
  (apply core/app* op-sym args))


(def ^:private max-safe-int 9007199254740991)


(defn- int-literal
  "The node of an integer token with `digits` in `radix`: a native literal up
   to 2^53 - 1, otherwise an application of `php/int-lit` (decimal) or
   `php/int-lit-radix`, which the AST can carry on every host and which
   demote to float past PHP_INT_MAX."
  [digits radix]
  (let [c (count digits)]
    (if (and (= 10 radix) (> c 19))
      (app* 'php/int-lit (u/lit digits))
      (let [m (integer/integer-module {::integer/max-bits (* 4 (max c 1)),
                                       ::integer/max-digits (max c 1)})
            v ((get m 'parse) digits radix)]
        (if (<= ((get m 'compare) v max-safe-int) 0)
          (u/lit v)
          (if (= 10 radix)
            (app* 'php/int-lit (u/lit digits))
            (app* 'php/int-lit-radix (u/lit digits) (u/lit radix))))))))


(defn- numeric-constant
  [ctx n]
  (let [t (first (core/kids ctx n))
        text (str/replace (:text t) "_" "")]
    (case (:type t)
      "Decimal" (int-literal text 10)
      "Hex" (int-literal (subs text 2) 16)
      "Binary" (int-literal (subs text 2) 2)
      "Octal" (int-literal (if (re-find #"^0[oO]" text) (subs text 2) (subs text 1)) 8))))


(defn- float-literal
  [text]
  (let [t (str/replace text "_" "")]
    (u/lit (cbor/float64 #?(:cljd (double/parse t)
                            :clj (Double/parseDouble t)
                            :cljs (js/parseFloat t))))))


(def ^:private constants
  "The constants P1 resolves at lowering time (P6 owns `define`)."
  (merge
    {"PHP_INT_MAX" (fn [] (app* 'php/int-lit (u/lit "9223372036854775807"))),
     "PHP_INT_MIN" (fn []
                     (app* 'php/int-lit-radix
                           (u/lit "-9223372036854775808")
                           (u/lit 10))),
     "PHP_INT_SIZE" (fn [] (u/lit 8)),
     "PHP_EOL" (fn [] (u/lit "\n")),
     "NAN" (fn [] (app* 'php/nan-const)),
     "INF" (fn [] (app* 'php/inf-const)),
     "M_PI" (fn [] (float-literal "3.141592653589793"))}
    (into {}
          (map (fn [[k v]] [k (fn [] (u/lit v))]))
          {"E_ERROR" 1, "E_WARNING" 2, "E_PARSE" 4, "E_NOTICE" 8,
           "E_CORE_ERROR" 16, "E_CORE_WARNING" 32, "E_COMPILE_ERROR" 64,
           "E_COMPILE_WARNING" 128, "E_USER_ERROR" 256, "E_USER_WARNING" 512,
           "E_USER_NOTICE" 1024, "E_STRICT" 2048, "E_RECOVERABLE_ERROR" 4096,
           "E_DEPRECATED" 8192, "E_USER_DEPRECATED" 16384, "E_ALL" 32767})))


(defn- constant-node
  [n name]
  (if-let [f (get constants name)]
    (f)
    (unsupported! n :constant)))


;; =============================================================================
;; Strings
;; =============================================================================

(defn- string-node
  "The node of a `string` rule: single and double quoted literals whose
   double-quoted parts are text only (interpolation is P2/P3)."
  [ctx n]
  (let [ks (core/kids ctx n)
        k0 (first ks)]
    (case (:type k0)
      "SingleQuoteString"
      (u/lit (byte-string (unescape-single (let [t (:text k0)]
                                             (subs t 1 (dec (count t)))))))
      "DoubleQuote"
      (let [parts (filterv #(p/rule? % "interpolatedStringPart") ks)]
        (when (some #(p/rule? (first (core/kids ctx %)) "chain") parts)
          (unsupported! n :interpolation))
        (u/lit (bytes->str (unescape-double
                             (apply str (map #(:text (first (core/kids ctx %))) parts))))))
      ("StartHereDoc" "StartNowDoc") (unsupported! n :heredoc))))


;; =============================================================================
;; Expressions
;; =============================================================================

(declare lower)


(def ^:private binary-ops
  {"Plus" 'php/add,
   "Minus" 'php/sub,
   "Asterisk" 'php/mul,
   "Divide" 'php/div,
   "Percent" 'php/mod,
   "Pow" 'php/pow,
   "Dot" 'php/concat,
   "IsEqual" 'php/loose-eq,
   "IsIdentical" 'php/identical,
   "IsNotEq" 'php/loose-ne,
   "IsNoidentical" 'php/not-identical,
   "Less" 'php/lt,
   "IsSmallerOrEqual" 'php/le,
   "Greater" 'php/gt,
   "IsGreaterOrEqual" 'php/ge,
   "Spaceship" 'php/spaceship,
   "Ampersand" 'php/bit-and,
   "Pipe" 'php/bit-or,
   "Caret" 'php/bit-xor,
   "ShiftLeft" 'php/shl,
   "ShiftRight" 'php/shr})


(defn- truthy
  [node]
  (app* 'php/to-bool node))


(defn- lower-binary
  [ctx n [a op b]]
  (case (:type op)
    "NullCoalescing" (unsupported! n :coalesce)
    "InstanceOf" (unsupported! n :instanceof)
    (let [x (lower ctx a)
          y (lower ctx b)]
      (case (:type op)
        ("BooleanAnd" "LogicalAnd") (u/if-node (truthy x) (truthy y) (u/lit false))
        ("BooleanOr" "LogicalOr") (u/if-node (truthy x) (u/lit true) (truthy y))
        "LogicalXor" (app* 'not (app* '= (truthy x) (truthy y)))
        (if-let [f (get binary-ops (:type op))]
          (app* f x y)
          (unsupported! n (keyword (str/lower-case (:type op)))))))))


(defn- lower-conditional
  [ctx n ks]
  (let [c (lower ctx (first ks))]
    (if (= 5 (count ks))
      (u/if-node (truthy c) (lower ctx (nth ks 2)) (lower ctx (nth ks 4)))
      (let [t (core/gen "t" n)]
        (u/let1 t c (u/if-node (truthy (u/v t)) (u/v t) (lower ctx (nth ks 3))))))))


(defn- lower-cast
  [ctx n [_ op _ operand]]
  (let [t (first (core/kids ctx op))
        e (lower ctx operand)]
    (case (:type t)
      "IntType" (app* 'php/to-int e)
      ("FloatCast" "DoubleType") (app* 'php/to-float e)
      "StringType" (app* 'php/to-string e)
      "BoolType" (app* 'php/to-bool e)
      (unsupported! n (keyword "cast" (str/lower-case (:type t)))))))


(defn- lower-prefix
  [ctx n [op operand :as ks]]
  (let [t (:type op)]
    (case t
      "Minus" (app* 'php/neg (lower ctx operand))
      "Plus" (app* 'php/plus (lower ctx operand))
      "Bang" (app* 'not (truthy (lower ctx operand)))
      "Tilde" (app* 'php/bit-not (lower ctx operand))
      "Print" (u/then (app* 'php/echo (app* 'php/to-string (lower ctx operand)))
                      (u/lit 1))
      "OpenRoundBracket" (lower-cast ctx n ks)
      (if-let [construct (get prefix-refusals t)]
        (unsupported! n construct)
        (unsupported! n (keyword (str/lower-case t)))))))


(defn- lower-arguments
  "The nodes of a call's `arguments` rule: positional expressions only."
  [ctx args]
  (mapv (fn [a]
          (let [ks (core/kids ctx a)]
            (when (or (p/token? (first ks)) (not= 1 (count ks)))
              (unsupported! a :argument-form))
            (lower ctx (first ks))))
        (core/rules ctx args "actualArgument")))


(defn- args-vector
  "A vector of the nodes `nodes`, evaluated left to right."
  [nodes]
  (reduce (fn [acc node] (app* 'php/vconj acc node)) (u/lit []) nodes))


(defn- function-name
  "[lower-cased written] for a call's `functionCallName`, or a refusal."
  [ctx n]
  (let [ks (core/kids ctx n)
        q (first ks)]
    (when-not (and (= 1 (count ks)) (p/rule? q "qualifiedNamespaceName"))
      (unsupported! n :dynamic-call))
    (let [qks (core/kids ctx q)
          names (core/kids ctx (first (filter #(p/rule? % "namespaceNameList") qks)))
          ids (filterv #(p/rule? % "identifier") names)]
      (when (or (some #(p/token? % "namespace") qks) (not= 1 (count ids)) (> (count names) 1))
        (unsupported! n :namespace))
      (let [written (:text (first (core/kids ctx (first ids))))]
        [(str/lower-case written) written]))))


(defn- lower-call
  [ctx n call]
  (let [[fname args] (core/rule-kids ctx call)
        [lc written] (function-name ctx fname)
        f (core/gen "f" n)
        arguments (first (core/rules ctx args "arguments"))]
    (when (not= 1 (count (core/rules ctx args "arguments")))
      (unsupported! n :dynamic-call))
    (u/let1 f
            (app* 'php/resolve-fn (u/lit lc) (u/lit written))
            (core/site :call
                       (app* 'php/call (u/v f)
                             (args-vector (lower-arguments ctx arguments)))))))


(defn- lower-chain
  [ctx n]
  (let [ks (core/kids ctx n)
        origin (first ks)]
    (when (> (count ks) 1) (unsupported! n :member-access))
    (let [o (first (core/kids ctx origin))]
      (cond
        (p/rule? o "functionCall") (lower-call ctx n o)
        (p/rule? o "chainBase") (unsupported! n :variable)
        :else (unsupported! n :new)))))


(defn- lower-constant
  [ctx n]
  (let [k (first (core/kids ctx n))]
    (cond
      (p/token? k) (u/lit :php/null)
      (p/rule? k "literalConstant")
      (let [l (first (core/kids ctx k))]
        (cond
          (p/token? l)
          (case (:type l)
            "Real" (float-literal (:text l))
            "BooleanConstant" (u/lit (= "true" (str/lower-case (:text l)))))
          (p/rule? l "numericConstant") (numeric-constant ctx l)
          (p/rule? l "stringConstant")
          (constant-node n (:text (first (core/kids ctx l))))))
      (p/rule? k "qualifiedNamespaceName")
      (let [names (core/kids ctx (first (core/kids ctx k)))]
        (if (and (= 1 (count (core/kids ctx k))) (= 1 (count names)))
          (constant-node n (:text (first (core/kids ctx (first names)))))
          (unsupported! n :namespace)))
      :else (core/default-arm diagnostic-ns "PHP" unsupported-rules k))))


(defn- lower-expression
  [ctx n]
  (let [ks (core/kids ctx n)
        k0 (first ks)
        cnt (count ks)]
    (cond
      (and (= 1 cnt) (p/rule? k0))
      (case (:rule k0)
        "chain" (lower-chain ctx k0)
        "constant" (lower ctx k0)
        "string" (string-node ctx k0)
        "parentheses" (lower ctx (first (core/rule-kids ctx k0)))
        (core/default-arm diagnostic-ns "PHP" unsupported-rules k0))
      (and (= 1 cnt) (p/token? k0)) (constant-node n (:text k0))
      (p/token? k0) (lower-prefix ctx n ks)
      (p/rule? k0 "assignable") (unsupported! n :assignment)
      (p/rule? k0 "arrayDestructuring") (unsupported! n :list)
      (p/rule? k0 "chain") (unsupported! n :inc-dec)
      (p/rule? k0 "stringConstant") (unsupported! n :index)
      (and (p/rule? k0 "expression") (p/token? (second ks) "?"))
      (lower-conditional ctx n ks)
      (and (p/rule? k0 "expression") (= 3 cnt)) (lower-binary ctx n ks)
      :else (unsupported! n :expression))))


;; =============================================================================
;; Statements
;; =============================================================================

(defn- echo-nodes
  [ctx expression-list]
  (mapv (fn [e] (app* 'php/echo (app* 'php/to-string (lower ctx e))))
        (core/rules ctx expression-list "expression")))


(defn- html-node
  "`(php/echo bytes)` of an `inlineHtml` rule. Only plain text is lowered:
   the grammar's markup tokens drop the whitespace between them, so their
   bytes are not recoverable from the packet."
  [ctx n]
  (let [tokens (mapcat #(core/kids ctx %) (core/rules ctx n "htmlElement"))]
    (when (or (empty? tokens) (not-every? #(= "HtmlText" (:type %)) tokens))
      (unsupported! n :inline-html-markup))
    (app* 'php/echo (u/lit (byte-string (apply str (map :text tokens)))))))


(defn- block-nodes
  [ctx n]
  (mapv #(lower ctx %) (core/rule-kids ctx n)))


(defn- sequence-node
  [nodes]
  (if (seq nodes) (u/seq-nodes nodes) (u/lit :php/null)))


(defn- lower
  "The node of CST rule node `n`."
  [ctx n]
  (case (:rule n)
    "inlineHtml" (html-node ctx n)
    "phpBlock" (sequence-node (block-nodes ctx n))
    ("topStatement" "innerStatement" "statement")
    (lower ctx (first (core/rule-kids ctx n)))
    "echoStatement" (sequence-node (echo-nodes ctx (first (core/rules ctx n "expressionList"))))
    "expressionStatement" (lower ctx (first (core/rules ctx n "expression")))
    "emptyStatement_" (u/lit :php/null)
    "inlineHtmlStatement" (sequence-node (block-nodes ctx n))
    "blockStatement" (lower ctx (first (core/rules ctx n "innerStatementList")))
    "innerStatementList" (sequence-node (block-nodes ctx n))
    "expression" (lower-expression ctx n)
    "constant" (lower-constant ctx n)
    (core/default-arm diagnostic-ns "PHP" unsupported-rules n)))


;; =============================================================================
;; Packets
;; =============================================================================

(defn- hoisted?
  "True for a top-level statement that is an unconditional function
   declaration: PHP declares it before the unit's first statement runs."
  [ctx item]
  (and (p/rule? item "topStatement")
       (p/rule? (first (core/rule-kids ctx item)) "functionDeclaration")))


(defn- unit-items
  "The unit's top-level items in source order: inline HTML runs and the
   statements of its PHP blocks."
  [ctx]
  (mapcat (fn [k]
            (cond
              (p/rule? k "inlineHtml") [k]
              (p/rule? k "phpBlock") (core/rule-kids ctx k)
              :else []))
          (core/kids ctx (p/root (:pk ctx)))))


(defn- context
  [packet]
  (let [pk (p/validate! packet {:diagnostic-ns diagnostic-ns})]
    {:pk pk, :analysis (scope/analyze pk)}))


(defn lower-module-body
  "The unit's statements as one node, before the runtime wrapper: what
   lowering goldens compare. Hoisted function declarations come first."
  [packet]
  (let [ctx (context packet)
        items (unit-items ctx)]
    (sequence-node (mapv #(lower ctx %)
                         (concat (filter #(hoisted? ctx %) items)
                                 (remove #(hoisted? ctx %) items))))))


(defn lower-packet
  "The complete program for one ok packet: the unit body run by
   `php/run-main` after the bundled prelude, tail calls marked. Its value is
   `{:php/out s :php/diag [...] :php/uncaught nil-or-{:class :message}}`."
  ([packet] (lower-packet packet {}))
  ([packet {:keys [prelude], :or {prelude :bundled}}]
   (when-not (= :bundled prelude)
     (throw (ex-info "the linked PHP profile is not available yet"
                     {:yang.php/diagnostic :yang.php/unsupported,
                      :construct :linked-prelude})))
   (u/mark-tails
     (u/then prelude/uast
             (app* 'php/run-main (u/lam [] (lower-module-body packet)))))))


;; =============================================================================
;; The lowering stage
;; =============================================================================

(def program-medium
  "The program medium's logical identity when the composition names none."
  :yang.php/program)


(def ^:private stage-config
  {:diagnostic-ns diagnostic-ns,
   :program-medium program-medium,
   :ok? #(= :yang.cst/ok (:yang.cst/outcome %)),
   :lower (fn [state packet]
            (lower-packet packet {:prelude (get state :prelude :bundled)})),
   :option-keys [:prelude]})


(defn lower-transform
  "The lowering stage's transform (`yang.core.stage/lower-transform`).
   Stateless."
  [state packet]
  (core-stage/lower-transform stage-config state packet))


(defn open-stage
  "A lowering stage reading CST packets from `cst-stream`, writing program
   batches to `program-stream`, whose logical identity is `medium`, and
   diagnostics to `diagnostics-stream`. The fourth argument may instead be
   an options map `{:medium :prelude}`."
  ([cst-stream program-stream diagnostics-stream]
   (core-stage/open-stage stage-config cst-stream program-stream diagnostics-stream))
  ([cst-stream program-stream diagnostics-stream medium-or-opts]
   (core-stage/open-stage stage-config
                          cst-stream
                          program-stream
                          diagnostics-stream
                          medium-or-opts)))


(defn step-stage
  [s]
  (core-stage/step-stage stage-config s))
