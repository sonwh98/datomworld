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
  {"gotoStatement" :goto,
   "declareStatement" :declare,
   "classDeclaration" :class,
   "useDeclaration" :use,
   "namespaceDeclaration" :namespace,
   "globalConstantDeclaration" :const,
   "enumDeclaration" :enum,
   "importStatement" :import,
   "newExpr" :new,
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
          {"SORT_REGULAR" 0, "SORT_NUMERIC" 1, "SORT_STRING" 2, "SORT_FLAG_CASE" 8,
           "SORT_NATURAL" 6, "COUNT_NORMAL" 0, "COUNT_RECURSIVE" 1,
           "E_ERROR" 1, "E_WARNING" 2, "E_PARSE" 4, "E_NOTICE" 8,
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


(defn- put-var
  "Write back a container the variable already owns: through a marker, with
   no counting."
  [ctx name node]
  (if (:fn ctx)
    (app* 'php/local-put! (u/v (symbol name)) node)
    (app* 'php/global-put! (u/lit (bare name)) node)))


(defn- make-ref-var
  "The reference marker of the variable, made on first use."
  [ctx name]
  (if (:fn ctx)
    (app* 'php/make-ref-cv (u/v (symbol name)))
    (app* 'php/global-make-ref! (u/lit (bare name)))))


(defn- bind-var
  "Bind the variable to the marker `m` (`$x =& ...`)."
  [ctx name m]
  (if (:fn ctx)
    (app* 'php/bind-marker (u/v (symbol name)) m)
    (app* 'php/global-bind! (u/lit (bare name)) m)))


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

(declare lower lower-assign-value lower-assign-target lower-lambda lower-destructure pattern-of)


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


(defn- args-vector
  "A vector of the nodes `nodes`, evaluated left to right."
  [nodes]
  (reduce (fn [acc node] (app* 'php/vconj acc node)) (u/lit []) nodes))


;; -----------------------------------------------------------------------------
;; Locations (docs/design/yang.php.md section 4.2, plan P2 section 1.3)
;; -----------------------------------------------------------------------------
;; A location is `{:var "$a" :path [key ...]}`: a variable and the offsets
;; applied to it. A key is an `expression` node, `:php/append` for `[]`, and,
;; once lowered, a node. Every array read and write goes through the
;; prelude's `php/dim-get`, `php/dim-peek`, `php/path-assign` and
;; `php/path-unset`; the lowering never looks inside an array, and the only
;; store into a holder is `write-var`, so the handle branches of P3 land in
;; the prelude without touching this section.

(defn- chain-location
  "The location of `chain`, when it is one variable and its `[k]`, `[]` and
   `{k}` offsets, else nil."
  [ctx chain]
  (let [ks (core/kids ctx chain)]
    (when (= 1 (count ks))
      (let [o (first (core/kids ctx (first ks)))]
        (when (p/rule? o "chainBase")
          (let [bks (core/kids ctx o)]
            (when (and (= 1 (count bks)) (p/rule? (first bks) "keyedVariable"))
              (let [[v & offsets] (core/kids ctx (first bks))]
                (when (and (p/token? v) (= "VarName" (:type v)))
                  {:var (:text v),
                   :path (mapv (fn [sq] (or (first (core/rules ctx sq "expression")) :php/append))
                               offsets)})))))))))


(defn- expression-location
  "The location of an `expression` that is exactly one chain."
  [ctx e]
  (let [ks (core/kids ctx e)]
    (when (and (= 1 (count ks)) (p/rule? (first ks) "chain"))
      (chain-location ctx (first ks)))))


(defn- chain-loc
  "The location of the `chain` `c`, or a refusal naming `construct`."
  [ctx n c construct]
  (let [loc (or (chain-location ctx c) (unsupported! n construct))]
    (checked-var n (:var loc))
    loc))


(defn- forbid-append!
  [n loc message]
  (when (some #{:php/append} (:path loc))
    (compile-error! n message)))


(defn- lower-path
  "`loc` with its keys lowered in place: nested applications evaluate them
   left to right, interleaved with the fetches, as Zend does for a read."
  [ctx loc]
  (update loc :path (fn [path] (mapv #(if (= :php/append %) % (lower ctx %)) path))))


(defn- with-keys
  "`(f loc')` where `loc'` holds each key as a variable bound once, left to
   right, before anything else runs (Zend's delayed compile of a write)."
  [ctx loc f]
  (let [[bindings path]
        (reduce (fn [[bindings path] k]
                  (if (= :php/append k)
                    [bindings (conj path k)]
                    (let [node (lower ctx k)]
                      (if (= :literal (:type node))
                        [bindings (conj path node)]
                        (let [sym (core/gen "k" k)]
                          [(conj bindings [sym node]) (conj path (u/v sym))])))))
                [[] []]
                (:path loc))]
    (reduce (fn [inner [sym node]] (u/let1 sym node inner))
            (f (assoc loc :path path))
            (reverse bindings))))


(defn- path-node
  "The key vector of a lowered path."
  [path]
  (args-vector (mapv #(if (= :php/append %) (u/lit :php/append) %) path)))


(defn- read-loc
  [ctx loc]
  (reduce (fn [acc k] (app* 'php/dim-get acc k)) (read-var ctx (:var loc)) (:path loc)))


(defn- peek-loc
  "The value at `loc`, null when anything on the way is missing; no diagnostic."
  [ctx loc]
  (reduce (fn [acc k] (app* 'php/dim-peek acc k)) (peek-var ctx (:var loc)) (:path loc)))


(defn- write-loc
  "Store `node` at `loc`: read the holder, update it purely, store it back.
   The expression's value is the stored value."
  [ctx n loc node]
  (if (empty? (:path loc))
    (write-var ctx (:var loc) node)
    (let [w (core/gen "w" n)]
      (u/let1 w
              node
              (u/then (put-var ctx
                               (:var loc)
                               (app* 'php/path-assign
                                     (peek-var ctx (:var loc))
                                     (path-node (:path loc))
                                     (u/v w)))
                      (u/v w))))))


(defn- isset-loc
  [ctx loc]
  (if (empty? (:path loc))
    (isset-var ctx (:var loc))
    (app* 'not (app* '= (peek-loc ctx loc) (u/lit :php/null)))))


(defn- unset-loc
  "Remove the offset: never vivifies; a holder that is not an array is not
   written back."
  [ctx n loc]
  (if (empty? (:path loc))
    (unset-var ctx (:var loc))
    (let [o (core/gen "o" n)
          pruned (app* 'php/path-unset (u/v o) (path-node (:path loc)))]
      (u/let1 o
              (read-var ctx (:var loc))
              (u/if-node (app* 'php/array? (u/v o))
                         (put-var ctx (:var loc) pruned)
                         pruned)))))


(defn- lower-null-coalesce
  "`a ?? b`: a plain variable on the left is fetched without a warning; any
   other operand is evaluated as written."
  [ctx n a b]
  (let [t (core/gen "t" n)
        loc (expression-location ctx a)]
    (when loc
      (checked-var n (:var loc))
      (forbid-append! n loc "Cannot use [] for reading"))
    (u/let1 t
            (if loc (peek-loc ctx (lower-path ctx loc)) (lower ctx a))
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


(defn- lower-isset
  [ctx n chains]
  (let [one (fn [c]
              (let [loc (chain-loc ctx n c :isset-target)]
                (forbid-append! n loc "Cannot use [] for reading")
                (isset-loc ctx (lower-path ctx loc))))]
    (reduce (fn [acc c] (u/if-node acc (one c) (u/lit false)))
            (one (first chains))
            (rest chains))))


(defn- lower-empty
  [ctx n c]
  (let [loc (chain-loc ctx n c :empty-target)]
    (forbid-append! n loc "Cannot use [] for reading")
    (app* 'not (truthy (peek-loc ctx (lower-path ctx loc))))))


(defn- lower-unset
  [ctx n chains]
  (u/then (u/seq-nodes (mapv (fn [c]
                               (let [loc (chain-loc ctx n c :unset-target)]
                                 (forbid-append! n loc "Cannot use [] for unsetting")
                                 (with-keys ctx loc #(unset-loc ctx n %))))
                             chains))
          (u/lit :php/null)))


(defn- lower-incdec
  "`++$x`, `$x--`, `$a[k]++`: the location is read, stepped and written back,
   its keys evaluated once; the postfix form answers the old value."
  [ctx n c op-type prefix?]
  (let [loc (chain-loc ctx n c :inc-dec-target)
        f (if (= "Inc" op-type) 'php/inc 'php/dec)]
    (forbid-append! n loc "Cannot use [] for reading")
    (with-keys ctx
      loc
      (fn [l]
        (if prefix?
          (write-loc ctx n l (app* f (read-loc ctx l)))
          (let [old (core/gen "old" n)]
            (u/let1 old
                    (read-loc ctx l)
                    (u/then (write-loc ctx n l (app* f (u/v old))) (u/v old)))))))))


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
      "Throw" (app* 'php/throw-value (lower ctx operand))
      "List" (lower-destructure ctx n
                                (pattern-of ctx n (first (core/rules ctx n "assignmentList")))
                                (last ks))
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


(defn- fresh-value?
  "True when expression `e` is syntactically a literal or constant, so the
   value it answers is not another holder's (no ADDREF needed)."
  [ctx e]
  (let [ks (core/kids ctx e)]
    (and (= 1 (count ks))
         (p/rule? (first ks))
         (contains? #{"constant" "string" "arrayCreation"} (:rule (first ks))))))


(defn- acquired
  "The lowered `e`, ADDREFed unless it is syntactically fresh."
  [ctx e]
  (if (fresh-value? ctx e) (lower ctx e) (app* 'php/addref (lower ctx e))))


(defn- ref-source-loc
  "A node that answers the reference marker of location `l` (keys already
   lowered), made on first use."
  [ctx n l]
  (if (empty? (:path l))
    (make-ref-var ctx (:var l))
    (let [p (core/gen "p" n)]
      (u/let1 p
              (app* 'php/path-ref (peek-var ctx (:var l)) (path-node (:path l)))
              (u/then (put-var ctx (:var l) (app* 'get (u/v p) (u/lit 0)))
                      (app* 'get (u/v p) (u/lit 1)))))))


(defn- ref-source
  "A node that answers the reference marker of the location `chain`, made on
   first use: a variable's own, or the slot's after the path is fetched for
   writing (separated, vivified, upgraded) and written back."
  [ctx n chain]
  (let [loc (chain-loc ctx n chain :reference-source)]
    (with-keys ctx loc (fn [l] (ref-source-loc ctx n l)))))


;; -----------------------------------------------------------------------------
;; Destructuring
;; -----------------------------------------------------------------------------
;; `[$a, $b] = e`, `list($a, , $c) = e`, `['k' => $v] = e`, nested, and one
;; level of `&` targets. A pattern is a vector of entries `{:key expr-or-nil
;; :idx n :ref? bool :target chain-or-pattern}`; holes only advance `:idx`.
;; The source is evaluated once; a missing key warns and assigns null, a
;; non-array source assigns null silently. The expression's value is the
;; source.

(declare pattern-of)


(defn- destructure-target
  "A chain node, or the nested pattern of an array literal / `list(...)`."
  [ctx n e ref?]
  (let [e (if (p/rule? e "expression")
            (let [ks (core/kids ctx e)]
              (if (and (= 1 (count ks)) (p/rule? (first ks) "chain")) (first ks) e))
            e)]
    (cond
      (p/rule? e "chain") e
      ref? (unsupported! n :list)
      :else
      (let [ks (core/kids ctx e)
            inner (first ks)]
        (if (and (= 1 (count ks)) (p/rule? inner "arrayCreation"))
          (pattern-of ctx n inner)
          (unsupported! n :list))))))


(defn- sequence-entries
  "Entries from a stream of `[:item x]` / `:comma` events: a comma that does
   not follow an item is a hole; trailing holes are dropped."
  [events]
  (let [[out _ _]
        (reduce (fn [[out idx pending?] ev]
                  (if (= ev :comma)
                    (if pending? [out idx false] [(conj out nil) (inc idx) false])
                    [(conj out ev) (inc idx) true]))
                [[] 0 false]
                events)
        trimmed (loop [v out] (if (and (seq v) (nil? (peek v))) (recur (pop v)) v))]
    (vec (keep-indexed (fn [i e] (when e (assoc e :idx i))) trimmed))))


(defn- pattern-of
  "The pattern of an `arrayDestructuring`, `arrayCreation` or `assignmentList`."
  [ctx n node]
  (let [entry (fn [key ref? target] {:key key, :ref? ref?, :target target})]
    (sequence-entries
      (case (:rule node)
        "arrayDestructuring"
        (mapcat (fn [k]
                  (cond
                    (p/token? k ",") [:comma]
                    (p/rule? k "indexedDestructItem")
                    (let [ref? (boolean (some #(p/token? % "&") (core/kids ctx k)))]
                      [(entry nil ref? (destructure-target ctx n (first (core/rules ctx k "chain")) ref?))])
                    (p/rule? k "keyedDestructItem")
                    (let [ref? (boolean (some #(p/token? % "&") (core/kids ctx k)))]
                      [(entry (first (core/rules ctx k "expression")) ref?
                              (destructure-target ctx n (first (core/rules ctx k "chain")) ref?))])
                    :else []))
                (core/kids ctx node))
        "arrayCreation"
        (mapcat (fn [k]
                  (cond
                    (p/token? k ",") [:comma]
                    (p/rule? k "arrayItem")
                    (let [kids (core/kids ctx k)
                          ref? (boolean (some #(p/token? % "&") kids))
                          exprs (core/rules ctx k "expression")
                          keyed? (boolean (some #(p/token? % "=>") kids))
                          key (when keyed? (first exprs))
                          target (if ref? (first (core/rules ctx k "chain")) (last exprs))]
                      [(entry key ref? (destructure-target ctx n target ref?))])
                    :else []))
                (mapcat #(core/kids ctx %) (core/rules ctx node "arrayItemList")))
        "assignmentList"
        (mapcat (fn [k]
                  (cond
                    (p/token? k ",") [:comma]
                    (p/rule? k "assignmentListElement")
                    (let [kids (core/kids ctx k)
                          c (first kids)]
                      (cond
                        (p/rule? c "chain") [(entry nil false c)]
                        (p/rule? c "arrayItem")
                        (let [ref? (boolean (some #(p/token? % "&") (core/kids ctx c)))
                              exprs (core/rules ctx c "expression")
                              keyed? (boolean (some #(p/token? % "=>") (core/kids ctx c)))
                              target (if ref? (first (core/rules ctx c "chain")) (last exprs))]
                          [(entry (when keyed? (first exprs)) ref?
                                  (destructure-target ctx n target ref?))])
                        :else [(entry nil false (pattern-of ctx n (first (core/rules ctx k "assignmentList"))))]))
                    :else []))
                (core/kids ctx node))))))


(defn- destructure-nodes
  "Statements storing each entry of `pattern` from the value bound to `src-sym`.
   `src-loc` is the source location (keys lowered) when it is one, so a
   by-reference entry can bind the slot."
  [ctx n pattern src-sym src-loc]
  (mapv
    (fn [{:keys [key idx ref? target]}]
      (let [keynode (if key (lower ctx key) (u/lit idx))]
        (cond
          ref?
          (do (when-not src-loc (compile-error! n "Cannot assign reference to non referenceable value"))
              (when (vector? target) (unsupported! n :nested-destructure-by-ref))
              (let [loc (chain-loc ctx n target :assignment-target)
                    k (core/gen "dk" n)]
                (u/let1 k
                        keynode
                        (bind-var ctx (:var loc)
                                  (ref-source-loc ctx n (update src-loc :path conj (u/v k)))))))
          (vector? target)
          (let [t (core/gen "dt" n)]
            (u/let1 t
                    (app* 'php/addref (app* 'php/list-get (u/v src-sym) keynode))
                    (u/seq-nodes (destructure-nodes ctx n target t nil))))
          :else
          (let [loc (chain-loc ctx n target :assignment-target)]
            (with-keys ctx loc
              (fn [l]
                (write-loc ctx n l (app* 'php/addref (app* 'php/list-get (u/v src-sym) keynode)))))))))
    pattern))


(defn- lower-destructure
  "Lower `pattern-node-or-pattern = rhs`."
  [ctx n pattern rhs]
  (let [src (core/gen "ds" n)
        rhs-chain (when (p/rule? rhs "expression")
                    (let [ks (core/kids ctx rhs)]
                      (when (and (= 1 (count ks)) (p/rule? (first ks) "chain")) (first ks))))
        src-loc (when rhs-chain (chain-location ctx rhs-chain))
        body (fn [sl]
               (u/let1 src
                       (lower ctx rhs)
                       (u/then (u/seq-nodes (destructure-nodes ctx n pattern src sl))
                               (u/v src))))]
    (if (and src-loc (some :ref? pattern))
      (with-keys ctx (lower-path ctx src-loc) body)
      (body nil))))


(defn- lower-assign-ref
  "`$x =& $y`, `$x =& $a[k]`, `$a[k] =& $y`: the source's marker first (its
   keys, then the fetch), then the target's keys, then the binding."
  [ctx n target src]
  (let [chain (first (core/kids ctx target))
        loc (if (p/rule? chain "chain")
              (chain-loc ctx n chain :assignment-target)
              (unsupported! n :list))
        _ (when-not (p/rule? src "chain") (unsupported! n :reference))
        m (core/gen "m" n)]
    (u/let1 m
            (ref-source ctx n src)
            (with-keys ctx
              loc
              (fn [l]
                (if (empty? (:path l))
                  (u/then (bind-var ctx (:var l) (u/v m)) (app* 'php/ref-value (u/v m)))
                  (let [p (core/gen "p" n)]
                    (u/let1 p
                            (app* 'php/path-bind
                                  (peek-var ctx (:var l))
                                  (path-node (:path l))
                                  (u/v m))
                            (u/then (put-var ctx (:var l) (u/v p))
                                    (app* 'php/ref-value (u/v m)))))))))))


(defn- lower-assign
  "`$x = e`, `$a[k] = e`, `$x op= e`, `$x ??= e`. The keys run first, left to
   right and once, then the right side, as in PHP; a compound assignment then
   reads the location."
  [ctx n [target op :as ks]]
  (if (and (p/token? op) (= 4 (count ks)) (p/token? (nth ks 2) "&"))
    (lower-assign-ref ctx n target (nth ks 3))
    (lower-assign-value ctx n ks)))


(defn- lower-assign-value
  [ctx n [target op :as ks]]
  (when (p/token? op) (unsupported! n :reference))
  (when (not= 3 (count ks)) (unsupported! n :attribute))
  (if (and (p/rule? (first (core/kids ctx target)) "arrayCreation")
           (= "Eq" (:type (first (core/kids ctx op)))))
    (lower-destructure ctx n (pattern-of ctx n (first (core/kids ctx target))) (nth ks 2))
    (lower-assign-target ctx n ks)))


(defn- lower-assign-target
  [ctx n [target op :as ks]]
  (let [chain (first (core/kids ctx target))
        loc (if (p/rule? chain "chain")
              (chain-loc ctx n chain :assignment-target)
              (unsupported! n :list))
        rhs (nth ks 2)
        optype (:type (first (core/kids ctx op)))]
    (when (not= "Eq" optype) (forbid-append! n loc "Cannot use [] for reading"))
    (with-keys
      ctx
      loc
      (fn [l]
        (case optype
          "Eq" (write-loc ctx n l (acquired ctx rhs))
          "NullCoalescingEqual"
          (let [t (core/gen "t" n)]
            (u/let1 t
                    (peek-loc ctx l)
                    (u/if-node (app* '= (u/v t) (u/lit :php/null))
                               (write-loc ctx n l (lower ctx rhs))
                               (u/v t))))
          (if-let [f (get assignment-ops optype)]
            (let [r (core/gen "r" n)]
              (u/let1 r
                      (lower ctx rhs)
                      (write-loc ctx n l (app* f (read-loc ctx l) (u/v r)))))
            (unsupported! n (keyword (str/lower-case optype)))))))))


(defn- lower-arguments
  "`[positional-node named-node]` of a call's `arguments` rule. Positional
   values and `...` spreads fold left to right into one vector; `name: e`
   arguments collect as `[name value]` pairs (nil when there are none). A
   position in `refs` (a by-reference parameter of a unit function) that holds
   a variable or element sends the reference marker."
  ([ctx args] (lower-arguments ctx args nil))
  ([ctx args refs]
   (let [items (core/rules ctx args "actualArgument")
         [pos named]
         (reduce
           (fn [[pos named] [i a]]
             (let [ks (core/kids ctx a)
                   an (first (filter #(p/rule? % "argumentName") ks))
                   spread? (boolean (some #(p/token? % "...") ks))
                   e (first (core/rules ctx a "expression"))]
               (when (or (nil? e) (p/token? (first ks) "&")) (unsupported! a :argument-form))
               (cond
                 an [pos (conj named
                               (args-vector [(u/lit (:text (first (core/kids ctx (first (core/kids ctx an))))))
                                             (lower ctx e)]))]
                 spread? [(app* 'php/args-spread pos (lower ctx e)) named]
                 :else
                 (let [chain (when (some #{i} refs) (first (core/rules ctx e "chain")))]
                   [(app* 'php/vconj
                          pos
                          (if (and chain (chain-location ctx chain))
                            (ref-source ctx a chain)
                            (lower ctx e)))
                    named]))))
           [(u/lit []) []]
           (map-indexed vector items))]
     [pos (when (seq named) (args-vector named))])))


(defn- call-node
  "Call function cell node `f` with the arguments of `arguments`."
  [ctx f arguments refs]
  (let [[pos named] (lower-arguments ctx arguments refs)]
    (core/site :call
               (if (seq refs)
                 (app* 'php/call-with-binding-profile
                       f pos (or named (u/lit []))
                       (u/lit "sha256:171656777d777865312132fe18cbc7a057a4721844a4dc450c0800381d2223eb"))
                 (if named
                   (app* 'php/call-named f pos named)
                   (app* 'php/call f pos))))))


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


;; A by-reference builtin (docs plan P2 section 1.9): the value at each
;; by-reference position goes in, `[ret outs]` comes out, and each out is
;; stored back through the argument's location.

(defn- argument-expressions
  [ctx args]
  (mapv (fn [a]
          (let [ks (core/kids ctx a)]
            (when (or (p/token? (first ks)) (not= 1 (count ks)))
              (unsupported! a :argument-form))
            (first ks)))
        (core/rules ctx args "actualArgument")))


(defn- lower-by-ref-call
  [ctx n f positions arguments]
  (let [exprs (argument-expressions ctx arguments)
        locs (into {}
                   (keep (fn [i]
                           (when-let [loc (some->> (get exprs i) (expression-location ctx))]
                             (checked-var n (:var loc))
                             [i loc])))
                   positions)
        r (core/gen "r" n)
        ;; Binds all by-ref keys before any argument runs; equal to argument
        ;; order while every by-ref builtin has `:by-ref [0]`. When a builtin
        ;; with a later by-ref position arrives (P9), bind each key at that
        ;; argument's place instead.
        wrap-keys (fn wrap
                    [idxs loc-map body-fn]
                    (if (empty? idxs)
                      (body-fn loc-map)
                      (with-keys ctx
                        (get loc-map (first idxs))
                        (fn [loc'] (wrap (rest idxs) (assoc loc-map (first idxs) loc') body-fn)))))]
    (wrap-keys
      (vec (sort (keys locs)))
      locs
      (fn [locs]
        (let [temps (vec (map-indexed
                           (fn [i e]
                             (when-not (contains? locs i)
                               [(core/gen "a" e)
                                (if (some #{i} positions)
                                  (u/then (app* 'php/warn
                                                (u/lit 8)
                                                (u/lit "Only variables should be passed by reference"))
                                          (lower ctx e))
                                  (lower ctx e))]))
                           exprs))
              ;; By-ref locations are read at call time, after the other
              ;; arguments have run (Zend's SEND_REF reads at the call).
              nodes (vec (map-indexed
                           (fn [i t] (if t (u/v (first t)) (peek-loc ctx (get locs i))))
                           temps))
              writes (keep-indexed
                       (fn [j i]
                         (when-let [loc (get locs i)]
                           (write-loc ctx
                                      n
                                      loc
                                      (app* 'get (app* 'get (u/v r) (u/lit 1)) (u/lit j)))))
                       positions)]
          (reduce (fn [inner [sym node]] (u/let1 sym node inner))
                  (u/let1 r
                          (core/site :call (app* 'php/call (u/v f) (args-vector nodes)))
                          (u/seq-nodes (conj (vec writes) (app* 'get (u/v r) (u/lit 0)))))
                  (reverse (keep identity temps))))))))


(defn- base-location
  "The location of a `chainBase` that is one variable and its offsets."
  [ctx base]
  (let [bks (core/kids ctx base)]
    (when (and (= 1 (count bks)) (p/rule? (first bks) "keyedVariable"))
      (let [[v & offsets] (core/kids ctx (first bks))]
        (when (and (p/token? v) (= "VarName" (:type v)))
          {:var (:text v),
           :path (mapv (fn [sq] (or (first (core/rules ctx sq "expression")) :php/append))
                       offsets)})))))


(defn- callee-value
  "The node of a dynamic callee: a variable or element, or a parenthesised
   expression."
  [ctx n fname]
  (let [k (first (core/kids ctx fname))]
    (cond
      (p/rule? k "chainBase")
      (let [loc (or (base-location ctx k) (unsupported! n :dynamic-call))]
        (checked-var n (:var loc))
        (forbid-append! n loc "Cannot use [] for reading")
        (read-loc ctx (lower-path ctx loc)))
      (p/rule? k "parentheses") (lower ctx k)
      :else (unsupported! n :dynamic-call))))


(defn- lower-call-plain
  [ctx n call]
  (let [[fname args] (core/rule-kids ctx call)
        groups (core/rules ctx args "arguments")
        static? (let [ks (core/kids ctx fname)]
                  (and (= 1 (count ks)) (p/rule? (first ks) "qualifiedNamespaceName")))
        [lc written] (when static? (function-name ctx fname))
        arguments (first groups)
        chain-dynamic (fn [callee-node more]
                        (reduce (fn [acc g]
                                  (let [f (core/gen "f" g)]
                                    (u/let1 f
                                            (app* 'php/callable-of acc)
                                            (call-node ctx (u/v f) g nil))))
                                callee-node
                                more))]
    (when-not (seq groups) (unsupported! n :dynamic-call))
    (if-not static?
      (let [f (core/gen "f" n)]
        (chain-dynamic
          (u/let1 f
                  (app* 'php/callable-of (callee-value ctx n fname))
                  (app* 'php/tmp! (call-node ctx (u/v f) arguments nil)))
          (rest groups)))
      (chain-dynamic
        (case lc
          "isset" (lower-isset ctx n (argument-chains ctx n arguments))
          "unset" (lower-unset ctx n (argument-chains ctx n arguments))
          "empty" (let [cs (argument-chains ctx n arguments)]
                    (when (not= 1 (count cs)) (unsupported! n :argument-form))
                    (lower-empty ctx n (first cs)))
          (let [f (core/gen "f" n)
                by-ref (when-not (contains? (:declared ctx) lc)
                         (:by-ref (get builtins/supported lc)))]
            (note-unknown-builtin! ctx n lc written)
            (u/let1 f
                    (app* 'php/resolve-fn (u/lit lc) (u/lit written))
                    (if by-ref
                      (lower-by-ref-call ctx n f by-ref arguments)
                      (let [call (call-node ctx (u/v f) arguments (get (:declared ctx) lc))]
                        (if (contains? (:declared ctx) lc) (app* 'php/tmp! call) call))))))
        (rest groups)))))


;; -----------------------------------------------------------------------------
;; try, throw, match, member calls
;; -----------------------------------------------------------------------------

(defn- leftmost-token-text
  "The text of the first token under `n`."
  [ctx n]
  (if (p/token? n) (:text n) (leftmost-token-text ctx (first (core/kids ctx n)))))


(defn- catch-clause-node
  "`(if matches (do bind body) rest)` for one `catch` clause over error `e`."
  [ctx e clause rest]
  (let [types (mapv #(str/lower-case (leftmost-token-text ctx %))
                    (core/rules ctx clause "qualifiedStaticTypeRef"))
        var (some #(when (and (p/token? %) (= "VarName" (:type %))) (:text %))
                  (core/kids ctx clause))
        test (reduce (fn [acc t]
                       (u/if-node acc (u/lit true) (app* 'php/instance-of e (u/lit t))))
                     (u/lit false)
                     types)
        body (lower ctx (first (core/rules ctx clause "blockStatement")))]
    (u/if-node test
               (u/then (if var (write-var ctx var e) (u/lit :php/null)) body)
               rest)))


(defn- lower-try
  "`try { } catch (T $e) { } finally { }`: `php/try` delivers the error to the
   catch clauses in order, an unmatched one is raised again; `php/try-finally`
   runs the finally block on every exit, escapes and errors included."
  [ctx n]
  (let [block (first (core/rules ctx n "blockStatement"))
        clauses (core/rules ctx n "catchClause")
        fin (first (core/rules ctx n "finallyStatement"))
        e (core/gen "e" n)
        mark (core/gen "mk" n)
        body (lower ctx block)
        guarded (if (seq clauses)
                  (u/let1 mark
                          (app* 'php/tmp-mark)
                          (app* 'php/try
                                (u/lam [] body)
                                (u/lam [e]
                                       (u/then (app* 'php/release-tmps (u/v mark))
                                               (reduce (fn [rest clause]
                                                         (catch-clause-node ctx (u/v e) clause rest))
                                                       (app* 'php/raise (u/v e))
                                                       (reverse clauses))))
                                (u/lam [] (u/lit :php/null))))
                  body)]
    (u/then (if fin
              (app* 'php/try-finally
                    (u/lam [] guarded)
                    (u/lam [(core/gen "x" n)]
                           (lower ctx (first (core/rules ctx fin "blockStatement")))))
              guarded)
            (u/lit :php/null))))


(defn- lower-match
  "`match (e) { a, b => r, default => d }`: strict comparison in order, the
   default last, else `UnhandledMatchError`."
  [ctx n]
  (let [subject (first (core/rules ctx n "expression"))
        m (core/gen "m" n)
        items (core/rules ctx n "matchItem")
        default? (fn [item]
                   (let [es (core/rules ctx item "expression")]
                     (and (= 2 (count es))
                          (= "default" (str/lower-case (leftmost-token-text ctx (first es)))))))
        default-item (first (filter default? items))
        arms (remove default? items)]
    (u/let1 m
            (lower ctx subject)
            (reduce (fn [rest item]
                      (let [es (core/rules ctx item "expression")
                            conds (butlast es)
                            result (last es)]
                        (u/if-node (reduce (fn [acc c]
                                             (u/if-node acc
                                                        (u/lit true)
                                                        (app* 'php/identical (u/v m) (lower ctx c))))
                                           (u/lit false)
                                           conds)
                                   (lower ctx result)
                                   rest)))
                    (if default-item
                      (lower ctx (last (core/rules ctx default-item "expression")))
                      (app* 'php/unhandled-match (u/v m)))
                    (reverse arms)))))


(defn- member-call
  "Fold one `memberAccess` (`->name(args)`) over the object node `o`."
  [ctx n o ma]
  (let [fname (first (core/rules ctx ma "keyedFieldName"))
        simple (when fname (first (core/kids ctx fname)))
        ident (when (and simple (p/rule? simple "keyedSimpleFieldName"))
                (first (core/kids ctx simple)))
        args (first (core/rules ctx (first (core/rules ctx ma "actualArguments")) "arguments"))]
    (when-not (and ident (p/rule? ident "identifier") args)
      (unsupported! n :member-access))
    (let [recv (core/gen "o" n)
          [pos _] (lower-arguments ctx args)]
      (u/let1 recv o
              (app* 'php/tmp!
                    (app* 'php/method-call (u/v recv)
                          (u/lit (leftmost-token-text ctx ident)) pos))))))


(defn- closure-static
  "[\"Closure\" method] when `fname` is `Closure::method`, else nil."
  [ctx fname]
  (let [cc (first (core/kids ctx fname))]
    (when (p/rule? cc "classConstant")
      (let [ks (core/kids ctx cc)
            ref (first ks)
            ident (last ks)]
        (when (and (p/rule? ref "qualifiedStaticTypeRef")
                   (p/rule? ident "identifier")
                   (= "closure" (str/lower-case (leftmost-token-text ctx ref))))
          ["Closure" (leftmost-token-text ctx ident)])))))


(defn- lower-call
  [ctx n call]
  (let [[fname args] (core/rule-kids ctx call)]
    (if-let [[_ m] (closure-static ctx fname)]
      (let [[pos _] (lower-arguments ctx (first (core/rules ctx args "arguments")))]
        (app* 'php/closure-static (u/lit (str/lower-case m)) (u/lit m) pos))
      (lower-call-plain ctx n call))))


(defn- lower-chain
  [ctx n]
  (let [ks (core/kids ctx n)
        origin (first ks)
        o (first (core/kids ctx origin))
        base (cond
               (p/rule? o "functionCall") (lower-call ctx n o)
               (p/rule? o "chainBase")
               (if-let [loc (base-location ctx o)]
                 (do (checked-var n (:var loc))
                     (forbid-append! n loc "Cannot use [] for reading")
                     (read-loc ctx (lower-path ctx loc)))
                 (unsupported! n :variable))
               :else (unsupported! n :new))]
    (reduce (fn [acc ma]
              (if (p/rule? ma "memberAccess")
                (member-call ctx n acc ma)
                (unsupported! n :member-access)))
            base
            (rest ks))))


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


(defn- lower-array
  "`[...]` / `array(...)`: items fold over the empty array left to right, key
   before value, so a duplicate key keeps its first position and the last
   value. A trailing `[e]` reads the literal."
  [ctx n]
  (let [items (mapcat #(core/rules ctx % "arrayItem") (core/rules ctx n "arrayItemList"))
        arr (reduce (fn [acc item]
                      (let [kids (core/kids ctx item)
                            [k v] (core/rules ctx item "expression")]
                        (cond
                          (some #(p/token? % "&") kids)
                          (let [src (first (core/rules ctx item "chain"))
                                m (ref-source ctx item src)]
                            (if k
                              (app* 'php/array-put-ref acc (lower ctx k) m)
                              (app* 'php/array-append-ref acc m)))
                          (p/token? (first kids) "...")
                          (app* 'php/array-spread acc (lower ctx k))
                          v (app* 'php/array-put acc (lower ctx k) (lower ctx v))
                          :else (app* 'php/array-append acc (lower ctx k)))))
                    (app* 'php/empty-array)
                    items)
        deref (first (core/rules ctx n "expression"))]
    (if deref (app* 'php/dim-get arr (lower ctx deref)) arr)))


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
        "arrayCreation" (lower-array ctx k0)
        "lambdaFunctionExpr" (lower-lambda ctx k0)
        "matchExpr" (lower-match ctx k0)
        "parentheses" (lower ctx (first (core/rule-kids ctx k0)))
        (core/default-arm diagnostic-ns "PHP" unsupported-rules k0))
      (and (= 1 cnt) (p/token? k0)) (constant-node n (:text k0))
      (p/token? k0) (lower-prefix ctx n ks)
      (p/rule? k0 "assignable") (lower-assign ctx n ks)
      (p/rule? k0 "arrayDestructuring")
      (lower-destructure ctx n (pattern-of ctx n k0) (nth ks 2))
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


(defn- with-temps
  "`node` followed by the release of the temporaries it registered, when the
   statement `n` holds a call that can leave one (row 37)."
  [ctx n node]
  (if (core/contains-rule? ctx n "functionCall" #{})
    (let [mark (core/gen "tm" n)]
      (u/let1 mark (app* 'php/tmp-mark) (u/then node (app* 'php/release-tmps (u/v mark)))))
    node))


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
;; foreach (by value)
;; -----------------------------------------------------------------------------

(defn- foreach-target
  "The location a `foreach` writes: an `assignable` or `chain` node that is a
   variable and its offsets, else a refusal."
  [ctx n t]
  (let [c (if (p/rule? t "assignable") (first (core/kids ctx t)) t)]
    (when-not (p/rule? c "chain") (unsupported! n :list))
    (chain-loc ctx n c :list)))


(defn- foreach-store
  "Store `node` into the target `loc`; its keys are bound once per iteration,
   before the holder is fetched, as for any location write."
  [ctx n loc node]
  (with-keys ctx loc (fn [loc'] (write-loc ctx n loc' node))))


(defn- lower-foreach-ref
  "`foreach ($a as &$v)` / `as $k => &$v`: each iteration re-reads the array
   at its location, binds `$v` to the marker of the current slot (the array is
   separated and upgraded by the fetch) and resumes after the current key, so
   appends are visited and an unset element is skipped. The iterator is the
   ordinal `i`; nothing is registered, so `break` and exceptions leave nothing
   to free."
  [ctx n kids]
  (let [chains (filter #(p/rule? % "chain") kids)
        arrow? (boolean (some #(p/token? % "=>") kids))
        src-chain (first chains)
        src-loc (chain-loc ctx n src-chain :foreach-source)
        assignable (first (filter #(p/rule? % "assignable") kids))
        v-node (if arrow? (last chains) assignable)
        k-loc (when arrow? (foreach-target ctx n assignable))
        v-loc (foreach-target ctx n v-node)
        _ (when (seq (:path v-loc)) (unsupported! n :reference))
        fr (loop-frame n false)
        [tbl w i key m] (map #(core/gen % n) ["tbl" "w" "i" "key" "m"])
        body (wrap-escape fr :cont (lower (push-frame ctx fr) (body-of ctx n)))]
    (with-keys
      ctx
      (lower-path ctx src-loc)
      (fn [sl]
        (let [elem (update sl :path conj (u/v key))
              iteration
              (u/let1 key
                      (app* 'php/fe-key (u/v tbl) (u/v i))
                      (u/let1 m
                              (ref-source-loc ctx n elem)
                              (u/then (bind-var ctx (:var v-loc) (u/v m))
                                      (u/then (if k-loc
                                                (foreach-store ctx n k-loc (u/v key))
                                                (u/lit :php/null))
                                              (u/then body
                                                      (u/app (u/v w) (u/v w)
                                                             (app* 'php/fe-next
                                                                   (peek-loc ctx sl)
                                                                   (u/v key)
                                                                   (u/v i))))))))]
          (u/then
            (app* 'php/foreach-array (peek-loc ctx sl))
            (wrap-escape
              fr
              :brk
              (u/let1 w
                      (core/site :loop
                                 (u/lam [w i]
                                        (u/let1 tbl
                                                (peek-loc ctx sl)
                                                (u/if-node (app* 'php/fe-has-next (u/v tbl) (u/v i))
                                                           iteration
                                                           (u/lit :php/null)))))
                      (u/app (u/v w) (u/v w) (u/lit 0))))))))))


(defn- lower-foreach-value
  "`foreach (e as $v)` / `as $k => $v`: the operand is evaluated once and
   `php/foreach-array` answers an array (a snapshot, since arrays are values)
   or warns and answers the empty one. The loop walks `php/array-keys`."
  [ctx n]
  (let [kids (core/kids ctx n)
        pattern (cond
                  (some #(p/rule? % "arrayDestructuring") kids)
                  (pattern-of ctx n (first (filter #(p/rule? % "arrayDestructuring") kids)))
                  (some #(p/token? % "List") kids)
                  (pattern-of ctx n (first (filter #(p/rule? % "assignmentList") kids))))
        src (first (filter #(or (p/rule? % "expression") (p/rule? % "chain")) kids))
        [t1 t2] (concat (filter #(p/rule? % "assignable") kids)
                        (filter #(p/rule? % "chain") (rest (drop-while #(not (p/token? % "=>")) kids))))
        [k-loc v-loc] (cond
                        pattern [nil nil]
                        (some #(p/token? % "=>") kids)
                        [(foreach-target ctx n t1) (foreach-target ctx n t2)]
                        :else [nil (foreach-target ctx n t1)])
        fr (loop-frame n false)
        [arr ord w i key] (map #(core/gen % n) ["arr" "ord" "w" "i" "key"])
        body (wrap-escape fr :cont (lower (push-frame ctx fr) (body-of ctx n)))
        node (if (p/rule? src "chain") (lower-chain ctx src) (lower ctx src))
        dsym (core/gen "fd" n)
        stores (cond-> [(if pattern
                          (u/let1 dsym
                                  (app* 'php/addref (app* 'php/array-get (u/v arr) (u/v key)))
                                  (u/seq-nodes (destructure-nodes ctx n pattern dsym nil)))
                          (foreach-store ctx n v-loc
                                         (app* 'php/array-get (u/v arr) (u/v key))))]
                 k-loc (conj (foreach-store ctx n k-loc (u/v key))))
        iteration (u/let1 key
                          (app* 'get (u/v ord) (u/v i))
                          (u/then (u/seq-nodes stores)
                                  (u/then body
                                          (u/app (u/v w) (u/v w) (app* '+ (u/v i) (u/lit 1))))))]
    (u/let1 arr
            (app* 'php/foreach-array node)
            (u/let1 ord
                    (app* 'php/array-keys (u/v arr))
                    (wrap-escape
                      fr
                      :brk
                      (u/let1 w
                              (core/site :loop
                                         (u/lam [w i]
                                                (u/if-node (app* '< (u/v i) (app* 'data/count (u/v ord)))
                                                           iteration
                                                           (u/lit :php/null))))
                              (u/app (u/v w) (u/v w) (u/lit 0))))))))


(defn- lower-foreach
  [ctx n]
  (let [kids (core/kids ctx n)]
    (if (some #(p/token? % "&") kids)
      (lower-foreach-ref ctx n kids)
      (lower-foreach-value ctx n))))


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
                (p/rule? k "attributes") :attribute
                (p/rule? k "memberModifier") :promoted-parameter))
            (core/kids ctx fp))
      :parameter-form))


(defn- formal-params
  "`[{:name :default :ref? :variadic?}]` of a function's parameters; `:default`
   is the `constantInitializer` node or nil."
  [ctx n]
  (mapv (fn [fp]
          (let [all (core/kids ctx fp)
                ref? (boolean (some #(p/token? % "&") all))
                variadic? (boolean (some #(p/token? % "...") all))
                ks (remove #(or (p/token? % "&") (p/token? % "...")) all)]
            (when (not= 1 (count ks)) (unsupported! fp (parameter-refusal ctx fp)))
            (when (and ref? variadic?) (unsupported! fp :variadic-reference))
            (let [vks (core/kids ctx (first ks))]
              {:name (:text (first vks)),
               :ref? ref?,
               :variadic? variadic?,
               :default (when (> (count vks) 1) (nth vks 2))})))
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
              (app* 'php/static-bind!
                    (u/v '%fn)
                    (u/lit (bare (:text (first vks))))
                    (u/v (symbol (:text (first vks))))
                    (u/lam [] init))))
          (core/rules ctx n "staticInitializer"))))


(defn- lower-global
  "`global $x;`: inside a function the CV binds to the global's marker (made
   on first use, null); at the top level it is the same variable, a no-op."
  [ctx n]
  (sequence-node
    (mapv (fn [gv]
            (let [v (first (core/kids ctx gv))]
              (when-not (and (p/token? v) (= "VarName" (:type v)))
                (unsupported! gv :variable-variable))
              (if (:fn ctx)
                (app* 'php/bind-marker
                      (u/v (symbol (:text v)))
                      (app* 'php/global-make-ref! (u/lit (bare (:text v)))))
                (u/lit :php/null))))
          (core/rules ctx n "globalVar"))))


(defn- lower-return
  [ctx n]
  (when-not (:ret ctx) (unsupported! n :return))
  (let [e (first (core/rules ctx n "expression"))]
    (u/app (u/v (:ret ctx))
           (if e
             (app* 'php/addref (lower ctx e))
             (u/lit :php/null)))))


(defn- mentions-reference?
  "True when the subtree of `n` holds a `&` token (a reference construct)."
  [ctx n]
  (some (fn [k] (if (p/token? k) (p/token? k "&") (mentions-reference? ctx k)))
        (core/kids ctx n)))


(defn- param-binding
  "The cell of parameter `i`: the argument when passed, else the default
   (evaluated here, in the callee), else an `ArgumentCountError`. A hole left
   by named arguments is `:php/undef`; a variadic parameter gathers the rest."
  [ctx n written required exact? i {:keys [name default ref? variadic?]}]
  (if variadic?
    (app* 'cell/new (app* 'php/gather-variadic (u/v '%args) (u/lit i)))
    (let [a (core/gen (str "a" i) n)
          count-node (app* 'data/count (u/v '%args))]
      (app* 'cell/new
            (u/let1 a
                    (u/if-node (app* '< (u/lit i) count-node)
                               (app* 'get (u/v '%args) (u/lit i))
                               (u/lit :php/undef))
                    (u/if-node (app* '= (u/v a) (u/lit :php/undef))
                               (if default
                                 (lower-initializer ctx default)
                                 (app* 'php/not-passed
                                       (u/lit written)
                                       (u/lit (inc i))
                                       (u/lit (bare name))
                                       count-node
                                       (u/lit required)
                                       (u/lit exact?)))
                               (cond
                                 ref? (app* 'php/param-ref
                                            (u/v a)
                                            (u/v '%binding-profile))
                                 :else (app* 'php/addref (u/v a)))))))))


(defn- function-lam
  "`[lam spec]` for the function node `n`. `captures` is a vector of
   `{:name :ref? :sym}` bound before the parameters (closures); `arrow?`
   makes the body the value of an expression."
  [ctx n written {:keys [captures arrow?]}]
  (let [ks (core/kids ctx n)
        _ (when (some #(p/token? % ":") ks) (unsupported! n :return-type))
        _ (when (some #(p/token? % "&") (take-while #(not (p/token? % "(")) ks))
            (unsupported! n :reference))
        _ (when (seq (core/rules ctx n "attributes")) (unsupported! n :attribute))
        _ (when (seq (core/rules ctx n "typeParameterListInBrackets"))
            (unsupported! n :generics))
        params (formal-params ctx n)
        scope (get-in (:analysis ctx) [:scopes (:id n)])
        param-names (set (map :name params))
        capture-names (set (map :name captures))
        static-names (set (:statics scope))
        static-names-v (vec (:statics scope))
        locals (remove #(or (contains? param-names %) (contains? static-names %)
                            (contains? capture-names %) (= "$this" %))
                       (:vars scope))
        block (first (core/rules ctx n "blockStatement"))
        has-return? (and block
                         (core/contains-rule? ctx
                                              block
                                              "returnStatement"
                                              #{"functionDeclaration" "lambdaFunctionExpr"}))
        counted? (boolean (or (mentions-reference? ctx n) (seq (:statics scope))
                              (some :ref? captures)))
        fctx (assoc ctx :fn true :loops [] :ret (when has-return? '%ret) :counted counted?)
        body (if arrow?
               (lower fctx (last (core/rules ctx n "expression")))
               (u/then (lower fctx block) (u/lit :php/null)))
        body (if has-return? (app* 'php/call-ec (u/lam ['%ret] body)) body)
        cvs (concat (map :name params) (map :name captures) static-names-v locals)
        body (if counted?
               (app* 'php/try-finally
                     (u/lam [] body)
                     (u/lam ['%x]
                            (app* 'php/release-cvs
                                  (args-vector (map #(u/v (symbol %)) cvs))
                                  (u/lit 0)
                                  (u/lit (count cvs)))))
               (if (seq cvs)
                 ;; no references or statics: release on the normal exit only (an
                 ;; error leaves the counts high, which is safe)
                 (let [rv (core/gen "rv" n)]
                   (u/let1 rv
                           body
                           (u/then (app* 'php/release-cvs
                                         (args-vector (map #(u/v (symbol %)) cvs))
                                         (u/lit 0)
                                         (u/lit (count cvs)))
                                   (u/v rv))))
                 body))
        variadic? (boolean (some :variadic? params))
        fixed (remove :variadic? params)
        total (count fixed)
        required (inc (or (last (keep-indexed (fn [i p] (when-not (:default p) i)) fixed)) -1))
        bindings (concat
                   (map-indexed (fn [i p]
                                  [(symbol (:name p))
                                   (param-binding fctx n written required (= required total) i p)])
                                params)
                   (map (fn [c]
                          [(symbol (:name c))
                           (app* 'cell/new
                                 (if (:ref? c)
                                   (app* 'php/param-ref
                                         (u/v (:sym c))
                                         (u/lit "sha256:171656777d777865312132fe18cbc7a057a4721844a4dc450c0800381d2223eb"))
                                   (app* 'php/copy-capture (u/v (:sym c)))))])
                        captures)
                   (map (fn [nm] [(symbol nm) (app* 'cell/new (u/lit :php/undef))])
                        (:statics scope))
                   (map (fn [nm] [(symbol nm) (app* 'cell/new (u/lit :php/undef))]) locals))
        wrapped (reduce (fn [inner [sym value]] (u/let1 sym value inner))
                        body
                        (reverse bindings))]
    [(u/lam ['%fn '%args '%binding-profile] wrapped)
     {:required required,
      :total total,
      :variadic variadic?,
      :names (mapv #(bare (:name %)) fixed)}
     (mapv bare (:statics scope))]))


(defn- lower-function
  [ctx n]
  (let [written (function-written ctx n)
        [lam spec statics] (function-lam ctx n written {})]
    (app* 'php/declare-function!
          (u/lit (str/lower-case written))
          (app* 'php/make-function (u/lit written) (u/lit spec) lam (u/lit statics)))))


(defn- use-vars
  "`[{:name :ref?}]` of a closure's `use` clause."
  [ctx n]
  (vec (mapcat (fn [uvs]
                 (map (fn [uv]
                        (let [ks (core/kids ctx uv)]
                          {:name (:text (last ks)), :ref? (boolean (some #(p/token? % "&") ks))}))
                      (core/rules ctx uvs "lambdaFunctionUseVar")))
               (core/rules ctx n "lambdaFunctionUseVars"))))


(defn- capture-source
  "The node that produces the captured value or marker, in the enclosing scope."
  [ctx {:keys [name ref? arrow?]}]
  (cond
    ref? (app* 'php/addref (make-ref-var ctx name))
    arrow? (if (:fn ctx)
             (app* 'php/local-capture (u/v (symbol name)))
             (app* 'php/global-capture (u/lit (bare name))))
    :else (app* 'php/addref (read-var ctx name))))


(defn- lower-lambda
  "A `function (...) use (...) {...}` closure or an `fn (...) => e` arrow
   function: a function cell made at run time over its captured values,
   wrapped as a Closure value."
  [ctx n]
  (let [arrow? (boolean (some #(and (p/token? %) (= "LambdaFn" (:type %))) (core/kids ctx n)))
        scope (get-in (:analysis ctx) [:scopes (:id n)])
        params (formal-params ctx n)
        pnames (set (map :name params))
        caps (if arrow?
               (->> (:vars scope)
                    (remove #(or (contains? pnames %) (= "$this" %)))
                    (mapv (fn [nm] {:name nm, :ref? false, :arrow? true})))
               (mapv #(assoc % :arrow? false) (use-vars ctx n)))
        caps (mapv #(assoc % :sym (symbol (str "%cap" (subs (:name %) 1) (:id n)))) caps)
        _ (doseq [c caps] (checked-var n (:name c)))
        [lam spec statics] (function-lam ctx n "{closure}" {:captures caps, :arrow? arrow?})
        make (app* 'php/make-closure
                   (app* 'php/make-function (u/lit "{closure}") (u/lit spec) lam (u/lit statics)))]
    (reduce (fn [inner c] (u/let1 (:sym c) (capture-source ctx c) inner))
            make
            (reverse caps))))


(defn- lower
  "The node of CST rule node `n`."
  [ctx n]
  (case (:rule n)
    "inlineHtml" (html-node ctx n)
    "phpBlock" (sequence-node (block-nodes ctx n))
    ("topStatement" "innerStatement" "statement")
    (lower ctx (first (core/rule-kids ctx n)))
    "echoStatement" (with-temps ctx n
                      (sequence-node (echo-nodes ctx (first (core/rules ctx n "expressionList")))))
    "expressionStatement" (with-temps ctx n (lower ctx (first (core/rules ctx n "expression"))))
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
    "foreachStatement" (lower-foreach ctx n)
    "switchStatement" (lower-switch ctx n)
    "breakStatement" (lower-escape ctx n :break)
    "continueStatement" (lower-escape ctx n :continue)
    "returnStatement" (lower-return ctx n)
    "tryCatchFinally" (lower-try ctx n)
    "throwStatement" (app* 'php/throw-value (lower ctx (first (core/rules ctx n "expression"))))
    "staticVariableStatement" (lower-static ctx n)
    "globalStatement" (lower-global ctx n)
    "unsetStatement" (lower-unset ctx n (core/rules ctx (first (core/rules ctx n "chainList")) "chain"))
    "functionDeclaration" (lower-function ctx n)
    "constant" (lower-constant ctx n)
    "arrayCreation" (lower-array ctx n)
    "newExpr" (if (some #(seq (core/rules ctx % "anonymousClass")) (core/rules ctx n "typeRef"))
                (unsupported! n :anonymous-class)
                (core/default-arm diagnostic-ns "PHP" unsupported-rules n))
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
  "Every function the unit declares: lower-cased name to the vector of its
   by-reference parameter positions."
  [ctx]
  (letfn [(refs
            [n]
            (let [fps (core/rules ctx (first (core/rules ctx n "formalParameterList"))
                                  "formalParameter")]
              (vec (keep-indexed (fn [i fp] (when (some #(p/token? % "&") (core/kids ctx fp)) i))
                                 fps))))
          (walk
            [n]
            (into (if (p/rule? n "functionDeclaration")
                    {(str/lower-case (function-written ctx n)) (refs n)}
                    {})
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
