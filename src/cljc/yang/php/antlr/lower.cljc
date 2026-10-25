(ns yang.php.antlr.lower
  "The PHP lowering interpreter: CST packets in, Universal AST program
   batches out (docs/design/yang.php.md sections 3, 7). Portable.

   Per packet: validate the tree, analyze scopes (`yang.php.antlr.scope`),
   then lower with one `case` arm per grammar rule. A rule P1 does not lower
   fails with `:yang.php/unsupported` naming the rule and construct; a rule
   with no arm fails with `:yang.php/unhandled-rule`. Nothing is silently
   dropped.

   Decided lowering rules (collab/1792905000000 plan):
   - A top-level `$x` is `(php/global-get \"x\")` / `(php/global-set! \"x\" v)`;
     inside a function it is a lexical cell `$x` allocated at entry holding
     `:php/undef`. Loops are self-applied `:loop`-marked lambdas whose
     `break`/`continue` are `php/call-ec` escapes bound to `%brk<id>` and
     `%cont<id>`, created only when the body uses them; the `:loops` stack of
     the context resolves `break n`. `switch` is one stack level: a chain of
     lambdas whose bodies tail-call the next case.
   - A function is `(php/declare-function! name (php/make-function name spec
     (fn [%fn %args] body) statics))`; top-level unconditional declarations
     are hoisted. `return` is an escape from `php/call-ec`, bound only when
     the body has a `return`.
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
    [yang.php.antlr.builtins :as builtins]
    [yang.php.antlr.prelude :as prelude]
    [yang.php.antlr.scope :as scope]
    [yang.stage :as stage]
    [yin.vm.integer :as integer]))


;; =============================================================================
;; Diagnostics
;; =============================================================================

(def ^:private diagnostic-ns "yang.php")


(defn- unsupported!
  [n construct]
  (core/unsupported! diagnostic-ns "PHP" n construct))


(defn- compile-error!
  "A PHP compile-time error: the program is refused, never run."
  [n message]
  (throw (ex-info message
                  {:yang.php/diagnostic :yang.php/compile-error,
                   :rule (:rule n),
                   :span (:span n)})))


(defn- warn!
  "Record a warning-severity lowering diagnostic: the program still lowers."
  [ctx n kind message data]
  (swap! (:warnings ctx)
         conj
         (merge {:yang.php/diagnostic kind,
                 :severity :warning,
                 :message message,
                 :span (:span n)}
                data)))


(def unsupported-rules
  "Grammar rules P1 does not lower, with the construct each names."
  {"globalStatement" :global,
   "foreachStatement" :foreach,
   "tryCatchFinally" :try,
   "throwStatement" :throw,
   "gotoStatement" :goto,
   "declareStatement" :declare,
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
  {"Clone" :clone,
   "New" :new,
   "Exit" :exit,
   "Eval" :eval,
   "Include" :include,
   "IncludeOnce" :include,
   "Require" :include,
   "RequireOnce" :include,
   "Throw" :throw,
   "Yield" :yield,
   "List" :list})


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
            (not (and (= c "\\") e))
            (let [cp (first (text/code-points (subs body i (min len (+ i 2)))))]
              (recur (+ i (if (> cp 0xFFFF) 2 1)) (into out (utf8-of-code-point cp))))
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
            :else (recur (inc i) (conj out 92))))))))


(defn- unescape-single
  "The text of the single-quoted string body `body`: only `\\\\` and `\\'`
   are escapes, resolved in one left-to-right scan."
  [body]
  (let [len (count body)]
    (loop [i 0
           out (transient [])]
      (if (>= i len)
        (apply str (persistent! out))
        (let [c (subs body i (inc i))
              e (when (< (inc i) len) (subs body (inc i) (+ i 2)))]
          (if (and (= c "\\") (contains? #{"\\" "'"} e))
            (recur (+ i 2) (conj! out e))
            (recur (inc i) (conj! out c))))))))


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
;; Variables
;; =============================================================================

(defn- simple-var
  "The variable name, sigil included, of a `chain` that is exactly one plain
   variable, else nil."
  [ctx chain]
  (let [ks (core/kids ctx chain)]
    (when (= 1 (count ks))
      (let [o (first (core/kids ctx (first ks)))]
        (when (p/rule? o "chainBase")
          (let [bks (core/kids ctx o)]
            (when (and (= 1 (count bks)) (p/rule? (first bks) "keyedVariable"))
              (let [vks (core/kids ctx (first bks))]
                (when (and (= 1 (count vks))
                           (p/token? (first vks))
                           (= "VarName" (:type (first vks))))
                  (:text (first vks)))))))))))


(defn- expression-var
  "The variable name of an `expression` that is exactly one plain variable."
  [ctx e]
  (let [ks (core/kids ctx e)]
    (when (and (= 1 (count ks)) (p/rule? (first ks) "chain"))
      (simple-var ctx (first ks)))))


(defn- bare
  [name]
  (subs name 1))


(defn- checked-var
  "`name` unless it is `$this`, which classes own (P4)."
  [n name]
  (if (= "$this" name) (unsupported! n :this) name))


(defn- read-var
  [ctx name]
  (if (:fn ctx)
    (app* 'php/local-get (u/v (symbol name)) (u/lit (bare name)))
    (app* 'php/global-get (u/lit (bare name)))))


(defn- peek-var
  "The variable's value, null when unset, with no warning."
  [ctx name]
  (if (:fn ctx)
    (app* 'php/local-peek (u/v (symbol name)))
    (app* 'php/global-peek (u/lit (bare name)))))


(defn- write-var
  "Assign `node` to the variable; the expression's value is the value."
  [ctx name node]
  (if (:fn ctx)
    (app* 'php/local-set! (u/v (symbol name)) node)
    (app* 'php/global-set! (u/lit (bare name)) node)))


(defn- isset-var
  [ctx name]
  (if (:fn ctx)
    (app* 'php/local-isset? (u/v (symbol name)))
    (app* 'php/global-isset? (u/lit (bare name)))))


(defn- unset-var
  [ctx name]
  (if (:fn ctx)
    (app* 'php/local-unset! (u/v (symbol name)))
    (app* 'php/global-unset! (u/lit (bare name)))))


;; =============================================================================
;; Strings
;; =============================================================================

(defn- interpolation-follows?
  "True when the text part `part` would continue a simple-syntax variable
   into an index or a property: `$a[0]`, `$a->b`."
  [part]
  (let [t (:text part)]
    (boolean (or (str/starts-with? t "[")
                 (re-find #"^->[A-Za-z_\x7f-\xff]" t)))))


(defn- interpolated-node
  "The concatenation of a double-quoted string's parts: text runs resolved
   to bytes, plain variables read in order. `end` is where the opening quote
   ends, to tell `{$x}` from `$x` by the gap the braces leave."
  [ctx n end parts]
  (let [texts (fn [run]
                (u/lit (bytes->str (unescape-double (apply str (map :text run))))))
        pieces
        (loop [ps parts, prev-end end, run [], out []]
          (if (empty? ps)
            (if (seq run) (conj out (texts run)) out)
            (let [part (first ps)
                  k (first (core/kids ctx part))
                  span-end (second (:span part))]
              (if (p/rule? k "chain")
                (let [name (or (simple-var ctx k) (unsupported! n :interpolation))
                      braced? (not= prev-end (first (:span part)))
                      nxt (second ps)
                      nk (when nxt (first (core/kids ctx nxt)))]
                  (when (and (not braced?) nk (p/token? nk) (interpolation-follows? nk))
                    (unsupported! n :interpolation))
                  (recur (rest ps)
                         span-end
                         []
                         (conj (if (seq run) (conj out (texts run)) out)
                               (app* 'php/to-string (read-var ctx (checked-var n name))))))
                (recur (rest ps) span-end (conj run k) out)))))]
    (case (count pieces)
      0 (u/lit "")
      1 (first pieces)
      (reduce (fn [acc piece] (app* 'php/concat acc piece)) pieces))))


(defn- string-node
  "The node of a `string` rule: single and double quoted literals; a
   double-quoted string interpolates plain scalar variables only (`$x`,
   `{$x}`; indexes, properties and `${}` are P2/P4)."
  [ctx n]
  (let [ks (core/kids ctx n)
        k0 (first ks)]
    (case (:type k0)
      "SingleQuoteString"
      (u/lit (byte-string (unescape-single (let [t (:text k0)]
                                             (subs t 1 (dec (count t)))))))
      "DoubleQuote"
      (interpolated-node ctx
                         n
                         (second (:span k0))
                         (filterv #(p/rule? % "interpolatedStringPart") ks))
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


(defn- lower-null-coalesce
  "`a ?? b`: a plain variable on the left is fetched without a warning; any
   other operand is evaluated as written."
  [ctx n a b]
  (let [t (core/gen "t" n)
        name (expression-var ctx a)]
    (u/let1 t
            (if name (peek-var ctx (checked-var n name)) (lower ctx a))
            (u/if-node (app* '= (u/v t) (u/lit :php/null)) (lower ctx b) (u/v t)))))


(defn- lower-binary
  [ctx n [a op b]]
  (case (:type op)
    "NullCoalescing" (lower-null-coalesce ctx n a b)
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


(defn- chain-var
  "The variable of the `chain` `c`, or a refusal naming `construct`."
  [ctx n c construct]
  (checked-var n (or (simple-var ctx c) (unsupported! n construct))))


(defn- lower-isset
  [ctx n chains]
  (reduce (fn [acc c] (u/if-node acc (isset-var ctx (chain-var ctx n c :isset-target)) (u/lit false)))
          (isset-var ctx (chain-var ctx n (first chains) :isset-target))
          (rest chains)))


(defn- lower-empty
  [ctx n c]
  (app* 'not (truthy (peek-var ctx (chain-var ctx n c :empty-target)))))


(defn- lower-unset
  [ctx n chains]
  (u/then (u/seq-nodes (mapv #(unset-var ctx (chain-var ctx n % :unset-target)) chains))
          (u/lit :php/null)))


(defn- lower-incdec
  "`++$x`, `$x--`: the variable is read, stepped and written back; the
   postfix form answers the old value."
  [ctx n c op-type prefix?]
  (let [name (chain-var ctx n c :inc-dec-target)
        f (if (= "Inc" op-type) 'php/inc 'php/dec)]
    (if prefix?
      (write-var ctx name (app* f (read-var ctx name)))
      (let [old (core/gen "old" n)]
        (u/let1 old
                (read-var ctx name)
                (u/then (write-var ctx name (app* f (u/v old))) (u/v old)))))))


(defn- lower-silence
  "`@e`: `e` runs with error_reporting reduced (`php/silence`)."
  [ctx operand]
  (app* 'php/silence (u/lam [] (lower ctx operand))))


(defn- lower-prefix
  [ctx n [op operand :as ks]]
  (let [t (:type op)]
    (case t
      "Minus" (app* 'php/neg (lower ctx operand))
      "Plus" (app* 'php/plus (lower ctx operand))
      "Bang" (app* 'not (truthy (lower ctx operand)))
      "Tilde" (app* 'php/bit-not (lower ctx operand))
      "SuppressWarnings" (lower-silence ctx operand)
      ("Inc" "Dec") (lower-incdec ctx n operand t true)
      "IsSet" (lower-isset ctx n (core/rules ctx (nth ks 2) "chain"))
      "Empty" (lower-empty ctx n (nth ks 2))
      "Print" (u/then (app* 'php/echo (app* 'php/to-string (lower ctx operand)))
                      (u/lit 1))
      "OpenRoundBracket" (lower-cast ctx n ks)
      (if-let [construct (get prefix-refusals t)]
        (unsupported! n construct)
        (unsupported! n (keyword (str/lower-case t)))))))


(def ^:private assignment-ops
  {"PlusEqual" 'php/add,
   "MinusEqual" 'php/sub,
   "MulEqual" 'php/mul,
   "PowEqual" 'php/pow,
   "DivEqual" 'php/div,
   "Concaequal" 'php/concat,
   "ModEqual" 'php/mod,
   "ShiftLeftEqual" 'php/shl,
   "ShiftRightEqual" 'php/shr,
   "AndEqual" 'php/bit-and,
   "OrEqual" 'php/bit-or,
   "XorEqual" 'php/bit-xor})


(defn- lower-assign
  "`$x = e`, `$x op= e`, `$x ??= e` on a plain variable. The right side runs
   first, as in PHP; a compound assignment then reads the variable."
  [ctx n [target op :as ks]]
  (when (p/token? op) (unsupported! n :reference))
  (when (not= 3 (count ks)) (unsupported! n :attribute))
  (let [chain (first (core/kids ctx target))
        name (if (p/rule? chain "chain")
               (chain-var ctx n chain :assignment-target)
               (unsupported! n :list))
        rhs (nth ks 2)
        optype (:type (first (core/kids ctx op)))]
    (case optype
      "Eq" (write-var ctx name (lower ctx rhs))
      "NullCoalescingEqual"
      (let [t (core/gen "t" n)]
        (u/let1 t
                (peek-var ctx name)
                (u/if-node (app* '= (u/v t) (u/lit :php/null))
                           (write-var ctx name (lower ctx rhs))
                           (u/v t))))
      (if-let [f (get assignment-ops optype)]
        (let [r (core/gen "r" n)]
          (u/let1 r (lower ctx rhs) (write-var ctx name (app* f (read-var ctx name) (u/v r)))))
        (unsupported! n (keyword (str/lower-case optype)))))))


(defn- lower-arguments
  "The nodes of a call's `arguments` rule: positional expressions only."
  [ctx args]
  (mapv (fn [a]
          (let [ks (core/kids ctx a)]
            (when (or (p/token? (first ks)) (not= 1 (count ks)))
              (unsupported! a :argument-form))
            (lower ctx (first ks))))
        (core/rules ctx args "actualArgument")))


(defn- argument-chains
  "The `chain` of each argument of `arguments`, for the language constructs
   that take variables (`isset`, `empty`, `unset`)."
  [ctx n args]
  (mapv (fn [a]
          (let [ks (core/kids ctx a)
                e (first ks)
                c (when (and (= 1 (count ks)) (p/rule? e "expression"))
                    (first (core/rules ctx e "chain")))]
            (or c (unsupported! n :argument-form))))
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


(defn- note-unknown-builtin!
  "Warn when `lc` names a catalogue function that neither the unit declares
   nor the prelude supplies."
  [ctx n lc written]
  (when (and (contains? builtins/catalogue lc)
             (not (contains? prelude/builtin-names lc))
             (not (contains? (:declared ctx) lc)))
    (warn! ctx
           n
           :yang.php/unknown-builtin
           (str "PHP builtin " written "() is not supplied by this runtime")
           {:name written})))


(defn- lower-call
  [ctx n call]
  (let [[fname args] (core/rule-kids ctx call)
        [lc written] (function-name ctx fname)
        arguments (first (core/rules ctx args "arguments"))]
    (when (not= 1 (count (core/rules ctx args "arguments")))
      (unsupported! n :dynamic-call))
    (case lc
      "isset" (lower-isset ctx n (argument-chains ctx n arguments))
      "unset" (lower-unset ctx n (argument-chains ctx n arguments))
      "empty" (let [cs (argument-chains ctx n arguments)]
                (when (not= 1 (count cs)) (unsupported! n :argument-form))
                (lower-empty ctx n (first cs)))
      (let [f (core/gen "f" n)]
        (note-unknown-builtin! ctx n lc written)
        (u/let1 f
                (app* 'php/resolve-fn (u/lit lc) (u/lit written))
                (core/site :call
                           (app* 'php/call (u/v f)
                                 (args-vector (lower-arguments ctx arguments)))))))))


(defn- lower-chain
  [ctx n]
  (let [ks (core/kids ctx n)
        origin (first ks)]
    (when (> (count ks) 1) (unsupported! n :member-access))
    (let [o (first (core/kids ctx origin))]
      (cond
        (p/rule? o "functionCall") (lower-call ctx n o)
        (p/rule? o "chainBase")
        (if-let [name (simple-var ctx n)]
          (read-var ctx (checked-var n name))
          (unsupported! n :variable))
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
      (p/rule? k0 "assignable") (lower-assign ctx n ks)
      (p/rule? k0 "arrayDestructuring") (unsupported! n :list)
      (p/rule? k0 "chain") (lower-incdec ctx n k0 (:type (second ks)) false)
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
  "`(php/echo bytes)` of an `inlineHtml` rule: the `:text` of every token
   under it, in preorder (markup tokens included)."
  [ctx n]
  (letfn [(texts
            [x]
            (mapcat (fn [k] (if (p/rule? k) (texts k) [(:text k)]))
                    (core/kids ctx x)))]
    (app* 'php/echo (u/lit (byte-string (apply str (texts n)))))))


(defn- block-nodes
  [ctx n]
  (mapv #(lower ctx %) (core/rule-kids ctx n)))


(defn- sequence-node
  [nodes]
  (if (seq nodes) (u/seq-nodes nodes) (u/lit :php/null)))


;; -----------------------------------------------------------------------------
;; Loops and escapes
;; -----------------------------------------------------------------------------

(defn- loop-frame
  "A level of the `:loops` stack. `:used` collects the escapes the body
   takes (`:brk`, `:cont`), so only those are bound."
  [n switch?]
  {:brk (core/gen "brk" n),
   :cont (core/gen "cont" n),
   :used (atom #{}),
   :switch? switch?})


(defn- push-frame
  [ctx frame]
  (update ctx :loops conj frame))


(defn- wrap-escape
  "`node` under a `php/call-ec` that binds the frame's `kind` escape, when the
   body used it."
  [frame kind node]
  (if (contains? @(:used frame) kind)
    (app* 'php/call-ec (u/lam [(get frame kind)] node))
    node))


(defn- digits-value
  [digits]
  (reduce (fn [acc ch] (+ (* acc 10) (str/index-of "0123456789" (str ch)))) 0 digits))


(defn- decimal-literal
  "The value of expression `e` when it is one decimal integer literal."
  [ctx e]
  (loop [x e
         names ["expression" "constant" "literalConstant" "numericConstant"]]
    (cond
      (not (p/rule? x (first names))) nil
      (= 1 (count names)) (let [t (first (core/kids ctx x))]
                            (when (and (p/token? t) (= "Decimal" (:type t)))
                              (digits-value (str/replace (:text t) "_" ""))))
      :else (let [ks (core/kids ctx x)]
              (when (= 1 (count ks)) (recur (first ks) (rest names)))))))


(defn- lower-escape
  "`break n` / `continue n`: the escape of the n-th enclosing level. A level
   count that is not a positive literal, or that is deeper than the loops
   around the statement, is a compile error."
  [ctx n kind]
  (let [word (if (= :break kind) "break" "continue")
        e (first (core/rules ctx n "expression"))
        level (if e
                (let [v (decimal-literal ctx e)]
                  (cond
                    (nil? v) (compile-error!
                               n
                               (str "'" word
                                    "' operator with non-integer operand is no longer supported"))
                    (< v 1) (compile-error! n
                                            (str "'" word
                                                 "' operator accepts only positive integers"))
                    :else v))
                1)
        loops (:loops ctx)
        depth (count loops)]
    (when (zero? depth)
      (compile-error! n (str "'" word "' not in the 'loop' or 'switch' context")))
    (when (> level depth)
      (compile-error! n (str "Cannot '" word "' " level " level" (when (> level 1) "s"))))
    (let [frame (nth loops (- depth level))
          switch-continue? (and (= :continue kind) (:switch? frame))
          which (if (or switch-continue? (= :break kind)) :brk :cont)]
      (when switch-continue?
        (warn! ctx
               n
               :yang.php/compile-warning
               "\"continue\" targeting switch is equivalent to \"break\""
               {:errno 2}))
      (swap! (:used frame) conj which)
      (u/app (u/v (get frame which)) (u/lit :php/null)))))


(defn- body-of
  "The body of a loop or `if`: a statement, or the list of the alternative
   syntax."
  [ctx n]
  (or (first (core/rules ctx n "statement"))
      (first (core/rules ctx n "innerStatementList"))))


(defn- condition
  [ctx n]
  (truthy (lower ctx (first (core/rules ctx n "parentheses")))))


(defn- loop-lambda
  "The `:loop` site: `body-fn` builds the lambda body from the loop symbol."
  [w body]
  (core/site :loop (u/lam [w] body)))


(defn- lower-while
  [ctx n]
  (let [fr (loop-frame n false)
        w (core/gen "w" n)
        c (condition ctx n)
        body (wrap-escape fr :cont (lower (push-frame ctx fr) (body-of ctx n)))]
    (wrap-escape fr
                 :brk
                 (u/let1 w
                         (loop-lambda w
                                      (u/if-node c
                                                 (u/then body (u/app (u/v w) (u/v w)))
                                                 (u/lit :php/null)))
                         (u/app (u/v w) (u/v w))))))


(defn- lower-do-while
  [ctx n]
  (let [fr (loop-frame n false)
        w (core/gen "w" n)
        body (wrap-escape fr :cont (lower (push-frame ctx fr) (body-of ctx n)))
        c (condition ctx n)]
    (wrap-escape fr
                 :brk
                 (u/let1 w
                         (loop-lambda w
                                      (u/then body
                                              (u/if-node c
                                                         (u/app (u/v w) (u/v w))
                                                         (u/lit :php/null))))
                         (u/app (u/v w) (u/v w))))))


(defn- expression-list-nodes
  [ctx el]
  (if el (mapv #(lower ctx %) (core/rules ctx el "expression")) []))


(defn- lower-for
  "`for (init; cond; update)`: each part is a comma list; the last condition
   decides and an empty one is true."
  [ctx n]
  (let [fr (loop-frame n false)
        w (core/gen "w" n)
        init (expression-list-nodes
               ctx
               (first (core/rules ctx (first (core/rules ctx n "forInit")) "expressionList")))
        conds (expression-list-nodes ctx (first (core/rules ctx n "expressionList")))
        update (expression-list-nodes
                 ctx
                 (first (core/rules ctx (first (core/rules ctx n "forUpdate")) "expressionList")))
        c (if (seq conds)
            (u/seq-nodes (conj (vec (butlast conds)) (truthy (last conds))))
            (u/lit true))
        body (wrap-escape fr :cont (lower (push-frame ctx fr) (body-of ctx n)))
        step (u/app (u/v w) (u/v w))
        iteration (u/then body (if (seq update) (u/then (u/seq-nodes update) step) step))]
    (u/then (sequence-node init)
            (wrap-escape fr
                         :brk
                         (u/let1 w
                                 (loop-lambda w (u/if-node c iteration (u/lit :php/null)))
                                 step)))))


;; -----------------------------------------------------------------------------
;; if and switch
;; -----------------------------------------------------------------------------

(defn- label-type
  "The token type of `item`, an `innerStatement` that is only a label
   (`else:`, `default:`: the grammar reads the keyword as a label), else nil."
  [ctx item]
  (let [s (first (core/rule-kids ctx item))]
    (when (p/rule? s "statement")
      (let [ks (core/kids ctx s)]
        (when (and (= 2 (count ks))
                   (p/rule? (first ks) "identifier")
                   (p/token? (second ks) ":"))
          (:type (first (core/kids ctx (first ks)))))))))


(defn- split-at-label
  "[before after] of the items of list `lst` around the first `label`
   statement; `after` is nil without one."
  [ctx lst label]
  (let [items (core/rule-kids ctx lst)
        i (first (keep-indexed (fn [i item] (when (= label (label-type ctx item)) i)) items))]
    (if i [(subvec items 0 i) (subvec items (inc i))] [items nil])))


(defn- items-node
  [ctx items]
  (sequence-node (mapv #(lower ctx %) items)))


(defn- alt-branches
  "[branches else-items] of an alternative-syntax `if`: the `else:` label is
   a statement inside the preceding list, so each list is split at it."
  [ctx n]
  (let [[before after] (split-at-label ctx (first (core/rules ctx n "innerStatementList")) "Else")
        first-branch [(condition ctx n) (items-node ctx before)]]
    (loop [branches [first-branch]
           else-items after
           more (core/rules ctx n "elseIfColonStatement")]
      (if (or else-items (empty? more))
        [branches else-items]
        (let [e (first more)
              [b a] (split-at-label ctx (first (core/rules ctx e "innerStatementList")) "Else")]
          (recur (conj branches [(condition ctx e) (items-node ctx b)]) a (rest more)))))))


(defn- lower-if
  [ctx n]
  (let [alt? (some #(p/token? % ":") (core/kids ctx n))
        [branches else-node]
        (if alt?
          (let [[bs else-items] (alt-branches ctx n)
                colon-else (first (core/rules ctx n "elseColonStatement"))]
            [bs (cond
                  else-items (items-node ctx else-items)
                  colon-else (lower ctx (first (core/rules ctx colon-else "innerStatementList")))
                  :else (u/lit :php/null))])
          [(into [[(condition ctx n) (lower ctx (first (core/rules ctx n "statement")))]]
                 (map (fn [e] [(condition ctx e) (lower ctx (first (core/rules ctx e "statement")))]))
                 (core/rules ctx n "elseIfStatement"))
           (if-let [e (first (core/rules ctx n "elseStatement"))]
             (lower ctx (first (core/rules ctx e "statement")))
             (u/lit :php/null))])]
    (reduce (fn [alt [c b]] (u/if-node c b alt)) else-node (reverse branches))))


(defn- switch-entries
  "The cases of a `switch` in source order: `{:test expression-node-or-nil
   :default? bool :stmts [innerStatement ...]}`. `case 1: case 2:` is two
   cases, the first empty. The grammar leaves a `default:` after a case's
   first statement as a label inside the list, so lists split there."
  [ctx n]
  (letfn [(add-body
            [entries lst]
            (reduce (fn [es item]
                      (if (= "Default" (label-type ctx item))
                        (conj es {:default? true, :node item, :stmts []})
                        (update-in es [(dec (count es)) :stmts] conj item)))
                    entries
                    (core/rule-kids ctx lst)))]
    (reduce
      (fn [entries block]
        (reduce (fn [es k]
                  (cond
                    (p/token? k) (if (= "Default" (:type k))
                                   (conj es {:default? true, :node k, :stmts []})
                                   es)
                    (p/rule? k "expression") (conj es {:test k, :stmts []})
                    (p/rule? k "innerStatementList") (add-body es k)
                    :else es))
                entries
                (core/kids ctx block)))
      []
      (core/rules ctx n "switchBlock"))))


(defn- lower-switch
  "The subject is bound once; entry picks the first case that loosely equals
   it (`default` when none does); each case body ends by calling the next, so
   fallthrough is a tail call. The switch is one level of the `:loops` stack,
   and `continue` aimed at it is a `break`."
  [ctx n]
  (let [fr (loop-frame n true)
        sw (core/gen "sw" n)
        entries (vec (switch-entries ctx n))
        _ (when-let [dup (second (filter :default? entries))]
            (compile-error! (or (:node dup) n)
                            "Switch statements may only contain one default clause"))
        case-sym (fn [i] (symbol (str "%case" (:id n) "_" i)))
        bctx (push-frame ctx fr)
        bodies (mapv #(items-node bctx (:stmts %)) entries)
        last-i (dec (count entries))
        default-i (first (keep-indexed (fn [i e] (when (:default? e) i)) entries))
        dispatch (reduce (fn [alt i]
                           (let [e (nth entries i)]
                             (if (:default? e)
                               alt
                               (u/if-node (app* 'php/loose-eq (u/v sw) (lower ctx (:test e)))
                                          (u/app (u/v (case-sym i)))
                                          alt))))
                         (if default-i (u/app (u/v (case-sym default-i))) (u/lit :php/null))
                         (reverse (range (count entries))))
        chain (reduce (fn [inner i]
                        (u/let1 (case-sym i)
                                (u/lam []
                                       (if (= i last-i)
                                         (nth bodies i)
                                         (u/then (nth bodies i) (u/app (u/v (case-sym (inc i)))))))
                                inner))
                      dispatch
                      (range (count entries)))]
    (u/let1 sw
            (lower ctx (first (core/rule-kids ctx (first (core/rules ctx n "parentheses")))))
            (wrap-escape fr :brk chain))))


;; -----------------------------------------------------------------------------
;; Functions
;; -----------------------------------------------------------------------------

(defn- function-written
  [ctx n]
  (:text (first (core/kids ctx (first (core/rules ctx n "identifier"))))))


(defn- parameter-refusal
  "The construct a `formalParameter` carries beyond a bare variable."
  [ctx fp]
  (or (some (fn [k]
              (cond
                (p/rule? k "typeHint") :type-declaration
                (p/token? k "?") :type-declaration
                (p/token? k "&") :reference
                (p/token? k "...") :variadic
                (p/rule? k "attributes") :attribute
                (p/rule? k "memberModifier") :promoted-parameter))
            (core/kids ctx fp))
      :parameter-form))


(defn- formal-params
  "`[{:name :default}]` of a function's parameters; `:default` is the
   `constantInitializer` node or nil."
  [ctx n]
  (mapv (fn [fp]
          (let [ks (core/kids ctx fp)]
            (when (not= 1 (count ks)) (unsupported! fp (parameter-refusal ctx fp)))
            (let [vks (core/kids ctx (first ks))]
              {:name (:text (first vks)), :default (when (> (count vks) 1) (nth vks 2))})))
        (core/rules ctx (first (core/rules ctx n "formalParameterList")) "formalParameter")))


(defn- lower-initializer
  "A parameter default: a `constantInitializer`."
  [ctx n]
  (let [ks (core/kids ctx n)
        k0 (first ks)]
    (cond
      (and (= 1 (count ks)) (p/rule? k0 "constant")) (lower ctx k0)
      (and (= 1 (count ks)) (p/rule? k0 "string")) (string-node ctx k0)
      (and (= 2 (count ks)) (p/token? k0))
      (case (:type k0)
        "Plus" (app* 'php/plus (lower-initializer ctx (second ks)))
        "Minus" (app* 'php/neg (lower-initializer ctx (second ks)))
        (unsupported! n :constant-expression))
      :else (unsupported! n :array))))


(defn- lower-static
  "`static $n = e;` binds nothing new: the lexical `$n` is the function's
   slot, and the initializer runs when execution first reaches the
   statement (`php/static-init!`)."
  [ctx n]
  (when-not (:fn ctx) (unsupported! n :static))
  (sequence-node
    (mapv (fn [vi]
            (let [vks (core/kids ctx vi)
                  init (if (> (count vks) 1) (lower ctx (nth vks 2)) (u/lit :php/null))]
              (app* 'php/static-init!
                    (u/v '%fn)
                    (u/lit (bare (:text (first vks))))
                    (u/lam [] init))))
          (core/rules ctx n "staticInitializer"))))


(defn- lower-return
  [ctx n]
  (when-not (:ret ctx) (unsupported! n :return))
  (let [e (first (core/rules ctx n "expression"))]
    (u/app (u/v (:ret ctx)) (if e (lower ctx e) (u/lit :php/null)))))


(defn- param-binding
  "The cell of parameter `i`: the argument when passed, else the default
   (evaluated here, in the callee), else an `ArgumentCountError`."
  [ctx written required exact? i {:keys [default]}]
  (app* 'cell/new
        (u/if-node (app* '< (u/lit i) (app* 'data/count (u/v '%args)))
                   (app* 'get (u/v '%args) (u/lit i))
                   (if default
                     (lower-initializer ctx default)
                     (app* 'php/too-few
                           (u/lit written)
                           (app* 'data/count (u/v '%args))
                           (u/lit required)
                           (u/lit exact?))))))


(defn- lower-function
  [ctx n]
  (let [ks (core/kids ctx n)
        _ (when (some #(p/token? % ":") ks) (unsupported! n :return-type))
        _ (when (some #(p/token? % "&") ks) (unsupported! n :reference))
        _ (when (seq (core/rules ctx n "attributes")) (unsupported! n :attribute))
        _ (when (seq (core/rules ctx n "typeParameterListInBrackets"))
            (unsupported! n :generics))
        written (function-written ctx n)
        lc (str/lower-case written)
        params (formal-params ctx n)
        scope (get-in (:analysis ctx) [:scopes (:id n)])
        param-names (set (map :name params))
        static-names (set (:statics scope))
        locals (remove #(or (contains? param-names %) (contains? static-names %) (= "$this" %))
                       (:vars scope))
        block (first (core/rules ctx n "blockStatement"))
        has-return? (core/contains-rule? ctx
                                         block
                                         "returnStatement"
                                         #{"functionDeclaration" "lambdaFunctionExpr"})
        fctx (assoc ctx :fn true :loops [] :ret (when has-return? '%ret))
        body (u/then (lower fctx block) (u/lit :php/null))
        body (if has-return? (app* 'php/call-ec (u/lam ['%ret] body)) body)
        total (count params)
        required (inc (or (last (keep-indexed (fn [i p] (when-not (:default p) i)) params)) -1))
        bindings (concat
                   (map-indexed (fn [i p]
                                  [(symbol (:name p))
                                   (param-binding fctx written required (= required total) i p)])
                                params)
                   (map (fn [nm] [(symbol nm) (app* 'php/static-slot (u/v '%fn) (u/lit (bare nm)))])
                        (:statics scope))
                   (map (fn [nm] [(symbol nm) (app* 'cell/new (u/lit :php/undef))]) locals))
        wrapped (reduce (fn [inner [sym value]] (u/let1 sym value inner))
                        body
                        (reverse bindings))]
    (app* 'php/declare-function!
          (u/lit lc)
          (app* 'php/make-function
                (u/lit written)
                (u/lit {:required required, :total total})
                (u/lam ['%fn '%args] wrapped)
                (u/lit (mapv bare (:statics scope)))))))


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
    "parentheses" (lower ctx (first (core/rule-kids ctx n)))
    "ifStatement" (lower-if ctx n)
    "whileStatement" (lower-while ctx n)
    "doWhileStatement" (lower-do-while ctx n)
    "forStatement" (lower-for ctx n)
    "switchStatement" (lower-switch ctx n)
    "breakStatement" (lower-escape ctx n :break)
    "continueStatement" (lower-escape ctx n :continue)
    "returnStatement" (lower-return ctx n)
    "staticVariableStatement" (lower-static ctx n)
    "unsetStatement" (lower-unset ctx n (core/rules ctx (first (core/rules ctx n "chainList")) "chain"))
    "functionDeclaration" (lower-function ctx n)
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


(defn- declared-functions
  "The lower-cased names of every function the unit declares."
  [ctx]
  (letfn [(walk
            [n]
            (into (if (p/rule? n "functionDeclaration")
                    #{(str/lower-case (function-written ctx n))}
                    #{})
                  (mapcat walk)
                  (core/rule-kids ctx n)))]
    (walk (p/root (:pk ctx)))))


(defn- context
  [packet]
  (let [pk (p/validate! packet {:diagnostic-ns diagnostic-ns})
        ctx {:pk pk,
             :analysis (scope/analyze pk),
             :loops [],
             :warnings (atom [])}]
    (assoc ctx :declared (declared-functions ctx))))


(defn- check-hoisted!
  "A top-level function declared twice, or over a builtin, is a compile
   error."
  [ctx hoisted]
  (reduce (fn [seen item]
            (let [f (first (core/rule-kids ctx item))
                  lc (str/lower-case (function-written ctx f))]
              (when (or (contains? seen lc) (contains? prelude/builtin-names lc))
                (compile-error! f (str "Cannot redeclare " (function-written ctx f) "()")))
              (conj seen lc)))
          #{}
          hoisted))


(defn- unit-body
  "The unit's statements as one node, hoisted function declarations first."
  [ctx]
  (let [items (unit-items ctx)
        hoisted (filterv #(hoisted? ctx %) items)]
    (check-hoisted! ctx hoisted)
    (sequence-node (mapv #(lower ctx %) (concat hoisted (remove #(hoisted? ctx %) items))))))


(defn lower-module-body
  "The unit's statements as one node, before the runtime wrapper: what
   lowering goldens compare. Hoisted function declarations come first."
  [packet]
  (unit-body (context packet)))


(defn lower-program
  "`{:ast a :warnings [d ...]}` for one ok packet: the unit body run by
   `php/run-main` after the bundled prelude, tail calls marked. Its value is
   `{:php/out s :php/diag [...] :php/uncaught nil-or-{:class :message}}`.
   `:warnings` are the lowering's warning-severity diagnostics
   (`:yang.php/unknown-builtin`, `:yang.php/compile-warning`)."
  ([packet] (lower-program packet {}))
  ([packet {:keys [prelude], :or {prelude :bundled}}]
   (when-not (= :bundled prelude)
     (throw (ex-info "the linked PHP profile is not available yet"
                     {:yang.php/diagnostic :yang.php/unsupported,
                      :construct :linked-prelude})))
   (let [ctx (context packet)
         body (unit-body ctx)]
     {:ast (u/mark-tails (u/then prelude/uast (app* 'php/run-main (u/lam [] body)))),
      :warnings @(:warnings ctx)})))


(defn lower-packet
  "The complete program for one ok packet (`lower-program`'s `:ast`)."
  ([packet] (lower-packet packet {}))
  ([packet opts] (:ast (lower-program packet opts))))


;; =============================================================================
;; The lowering stage
;; =============================================================================

(def program-medium
  "The program medium's logical identity when the composition names none."
  :yang.php/program)


(defn- stage-config
  "The shared stage config; `warned` receives the packet's warnings."
  [warned]
  {:diagnostic-ns diagnostic-ns,
   :program-medium program-medium,
   :ok? #(= :yang.cst/ok (:yang.cst/outcome %)),
   :lower (fn [state packet]
            (let [{:keys [ast warnings]}
                  (lower-program packet {:prelude (get state :prelude :bundled)})]
              (reset! warned warnings)
              ast)),
   :option-keys [:prelude]})


(defn lower-transform
  "The lowering stage's transform (`yang.core.stage/lower-transform`):
   an ok packet is one program batch, then one record on port `:diagnostics`
   per warning (`:severity :warning`); a refusal is a diagnostic and no
   program. Stateless."
  [state packet]
  (let [warned (atom [])
        [state' outputs] (core-stage/lower-transform (stage-config warned) state packet)]
    [state'
     (into outputs
           (map (fn [w] [:diagnostics (assoc w :yang.cst/unit (:yang.cst/unit packet))]))
           @warned)]))


(defn open-stage
  "A lowering stage reading CST packets from `cst-stream`, writing program
   batches to `program-stream`, whose logical identity is `medium`, and
   diagnostics to `diagnostics-stream`. The fourth argument may instead be
   an options map `{:medium :prelude}`."
  ([cst-stream program-stream diagnostics-stream]
   (core-stage/open-stage (stage-config (atom [])) cst-stream program-stream diagnostics-stream))
  ([cst-stream program-stream diagnostics-stream medium-or-opts]
   (core-stage/open-stage (stage-config (atom []))
                          cst-stream
                          program-stream
                          diagnostics-stream
                          medium-or-opts)))


(defn step-stage
  [s]
  (stage/step s lower-transform))
