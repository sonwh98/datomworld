(ns yang.typescript.antlr.lower
  "TypeScript CST packet to Universal AST (docs/design/yang.typescript.md
   section 6): lowering arms over `yang.core.uast`, `lower-packet` for the
   bundled and linked profiles, canonical renaming, site marks, and the
   refusal and consumed tables. Portable.

   Decided lowering rules (T1 plan, section 5):
   - A scope's bindings are one immediately applied lambda over cells: a
     `var` cell starts `:js/undefined`, a `let`, `const` and function cell
     `:js/tdz`; hoisted function declarations are initialized before any
     statement of their scope. Reads are `cell/get` (`var`, parameter,
     function), `js/lexical-get` (TDZ-capable) or `js/global-get` (a free
     name); a write is `cell/set!`, `js/const-assign!` for a `const`.
   - Numbers are float64 content at the producer: the literal `1` is
     `(cbor/float64 1.0)`. Strings are `{:js/str s}` when well-formed UTF-16
     and `{:js/units [u ...]}` otherwise, normalized here the way
     `js/mk-str` does at run time. A BigInt is the bare exact integer.
   - Every call is `(js/call f this args)`, every construct
     `(js/construct F args)`; a function is
     `(js/make-function name length this-mode kind (lam [this args] body))`. A
     function with a `return` runs its body under `js/call-ec`.
   - Loops are self-applied lambdas marked as `:loop` safepoint sites;
     `break` and `continue` are escapes through `js/call-ec`, bound only
     when the loop body holds a jump that targets the loop. `for (let ...)`
     copies its cells before the update, so closures capture one cell per
     iteration. `try` is `js/try` and `finally` `js/try-finally`.
   - A construct outside slice T1 refuses with
     `:yang.ts/unsupported` carrying `:construct` and `:slice`; type-only
     rules refuse as `\"type syntax\"` (slice T8). Nothing is silently
     dropped, and no rule reaches `:yang.ts/unhandled-rule` from a T0
     fixture.

   Generated names come from CST node ids (`%brk17`) and are renamed to
   `%<n>` by `yang.core.names/canonicalize` before tail marking."
  (:require
    [clojure.string :as str]
    [dao.jing.cbor :as cbor]
    [yang.antlr.packet :as p]
    [yang.core.lower :as core]
    [yang.core.names :as names]
    [yang.core.stage :as core-stage]
    [yang.core.uast :as u]
    [yang.typescript.antlr.prelude :as prelude]
    [yang.typescript.antlr.scope :as scope]))


;; =============================================================================
;; Diagnostics and the refusal tables
;; =============================================================================

(def ^:private diagnostic-ns "yang.ts")


(defn- refuse!
  "Throw `:yang.ts/unsupported` for node `n`: `construct` names it and
   `slice` the slice that lands it."
  [n construct slice]
  (throw (ex-info (str "Unsupported TypeScript construct: " construct)
                  {:yang.ts/diagnostic :yang.ts/unsupported,
                   :rule (:rule n),
                   :construct construct,
                   :slice slice,
                   :span (:span n)})))


(defn- syntax!
  [n message]
  (core/syntax! diagnostic-ns n message))


(def unsupported-rules
  "Rule name to `{:construct :slice}`: the out-of-slice constructs of
   section 1 that have a rule of their own."
  {"importStatement" {:construct "module syntax", :slice :T7},
   "exportStatement" {:construct "module syntax", :slice :T7},
   "withStatement" {:construct "with statement", :slice :never},
   "yieldStatement" {:construct "yield", :slice :T5},
   "classDeclaration" {:construct "class", :slice :T3},
   "enumDeclaration" {:construct "enum", :slice :T9},
   "namespaceDeclaration" {:construct "namespace", :slice :T9},
   "generatorFunctionDeclaration" {:construct "generator function",
                                   :slice :T5},
   "arrayLiteral" {:construct "array literal", :slice :T2},
   "objectLiteral" {:construct "object literal", :slice :T2},
   "iteratorBlock" {:construct "iterator block", :slice :T5},
   "generatorBlock" {:construct "generator block", :slice :T5},
   "decoratorList" {:construct "decorator", :slice :never},
   "decorator" {:construct "decorator", :slice :never},
   "constructorDeclaration" {:construct "class", :slice :T3},
   "importAliasDeclaration" {:construct "module syntax", :slice :T7}})


(def consumed-rules
  "Type-only rules (design section 5.5 and the `ts-g4-1` additions): the
   erasure of these lands in slice T8; until then each refuses as type
   syntax."
  #{"typeAnnotation" "typeParameters" "typeParameterList" "typeParameter"
    "typeArguments" "typeArgumentList" "typeArgument" "typeGeneric" "type_"
    "constraint" "interfaceDeclaration" "typeAliasDeclaration"
    "asExpression" "abstractDeclaration" "indexSignature" "constructSignature"
    "methodSignature" "accessibilityModifier" "interfaceExtendsClause"
    "implementsClause" "classOrInterfaceTypeList" "optionalParameter"})


(def handled-rules
  "Rules the lowering has an arm for."
  #{"program" "sourceElements" "sourceElement" "statement" "block"
    "statementList" "variableStatement" "variableDeclarationList"
    "variableDeclaration" "emptyStatement_" "expressionStatement"
    "ifStatement" "iterationStatement" "continueStatement" "breakStatement"
    "returnStatement" "switchStatement" "labelledStatement" "throwStatement"
    "tryStatement" "debuggerStatement" "functionDeclaration"
    "expressionSequence" "singleExpression" "anonymousFunction"
    "arrowFunctionDeclaration" "literal" "templateStringLiteral"})


(defn- default-arm
  "The arm of a rule with no lowering: a typed refusal for the tables,
   `:yang.ts/unhandled-rule` otherwise."
  [n]
  (if-let [{:keys [construct slice]} (get unsupported-rules (:rule n))]
    (refuse! n construct slice)
    (if (contains? consumed-rules (:rule n))
      (refuse! n "type syntax" :T8)
      (core/default-arm diagnostic-ns "TypeScript" {} n))))


(defn- check-consumed!
  "Refuse type syntax among the direct children of `n`."
  [ctx n]
  (doseq [k (core/rule-kids ctx n)]
    (when (contains? consumed-rules (:rule k))
      (refuse! k "type syntax" :T8))))


;; =============================================================================
;; Small helpers
;; =============================================================================

(def ^:private gen core/gen)


(def ^:private kids core/kids)


(def ^:private rules core/rules)


(def ^:private rule-kids core/rule-kids)


(def ^:private site core/site)


(def ^:private app* core/app*)


(def ^:private undefined (u/lit :js/undefined))


(defn- jstr
  [s]
  {:js/str s})


(defn- text-of
  [ctx n]
  (scope/first-token-text (:pk ctx) n))


(defn- truthy
  [x]
  (app* 'js/to-boolean x))


(defn- build-vector
  [elems]
  (reduce (fn [acc e] (app* 'js/vconj acc e)) (u/lit []) elems))


(defn- seq-of
  "The nodes in order, ending in `:js/undefined`."
  [nodes]
  (u/seq-nodes (conj (vec nodes) undefined)))


(defn- token-kid
  [ctx n text]
  (some #(when (p/token? % text) %) (kids ctx n)))


(def ^:private function-rules
  #{"functionDeclaration" "anonymousFunction" "arrowFunctionDeclaration"
    "classDeclaration" "propertyMemberDeclaration" "getAccessor"
    "setAccessor" "generatorMethod"})


(defn- label-of
  [ctx n]
  (when-let [id (first (rules ctx n "identifier"))]
    (text-of ctx id)))


(defn- targets?
  "True when `body` holds a jump statement `rule` (`breakStatement` or
   `continueStatement`) that targets the construct owning `own-labels`: a
   labelled one naming a label of `own-labels`, or, when `unlabeled?`, an
   unlabelled one not captured by a nested loop (or, when `switch-nests?`,
   a nested switch). Functions are never entered."
  [ctx body rule own-labels unlabeled? switch-nests?]
  (letfn [(go
            [n nested?]
            (boolean
              (some (fn [k]
                      (when (p/rule? k)
                        (let [r (:rule k)]
                          (cond
                            (contains? function-rules r) false
                            (= r rule)
                            (if-let [l (label-of ctx k)]
                              (contains? own-labels l)
                              (and unlabeled? (not nested?)))
                            :else (go k
                                      (or nested?
                                          (= r "iterationStatement")
                                          (and switch-nests?
                                               (= r "switchStatement"))))))))
                    (kids ctx n))))]
    (go body false)))


(defn- wrap-escape
  "`node` run under a `js/call-ec` that binds escape `sym`, when `bound?`."
  [bound? sym node]
  (if bound? (app* 'js/call-ec (u/lam [sym] node)) node))


;; =============================================================================
;; Literals
;; =============================================================================

(defn- code-point->str
  [cp]
  #?(:cljd (let [sb (StringBuffer)] (.writeCharCode sb cp) (.toString sb))
     :clj (String. (Character/toChars (int cp)))
     :cljs (.fromCodePoint js/String cp)))


(defn- text-units
  "The UTF-16 code units of host string `s`."
  [s]
  #?(:cljd (vec (.-codeUnits ^String s))
     :clj (mapv int s)
     :cljs (mapv #(.charCodeAt s %) (range (.-length s)))))


(defn- units->text
  "The host string of well-formed UTF-16 code units `us`."
  [us]
  (let [n (count us)]
    (loop [i 0
           out []]
      (if (>= i n)
        (apply str out)
        (let [u0 (nth us i)]
          (if (and (<= 0xD800 u0 0xDBFF) (< (inc i) n))
            (let [u1 (nth us (inc i))]
              (recur (+ i 2)
                     (conj out
                           (code-point->str (+ 0x10000
                                               (* (- u0 0xD800) 0x400)
                                               (- u1 0xDC00))))))
            (recur (inc i) (conj out (code-point->str u0)))))))))


(defn- well-formed?
  [us]
  (let [n (count us)]
    (loop [i 0]
      (if (>= i n)
        true
        (let [u0 (nth us i)]
          (cond
            (<= 0xD800 u0 0xDBFF)
            (if (and (< (inc i) n) (<= 0xDC00 (nth us (inc i)) 0xDFFF))
              (recur (+ i 2))
              false)
            (<= 0xDC00 u0 0xDFFF) false
            :else (recur (inc i))))))))


(defn- string-value
  "The normalized string value of code units `us`: the lowering's own
   `js/mk-str`."
  [us]
  (if (well-formed? us)
    {:js/str (units->text us)}
    {:js/units (vec us)}))


(defn- hex-value
  [n s]
  (when-not (re-matches #"[0-9a-fA-F]+" s)
    (syntax! n "Invalid escape sequence"))
  (reduce (fn [acc ch]
            (+ (* acc 16) (str/index-of "0123456789abcdef" (str/lower-case (str ch)))))
          0
          s))


(defn- cp-units
  [cp]
  (if (< cp 0x10000)
    [cp]
    (let [c (- cp 0x10000)]
      [(+ 0xD800 (quot c 0x400)) (+ 0xDC00 (rem c 0x400))])))


(defn- unescape
  "The code units of string `body`, escapes resolved."
  [n body]
  (let [len (count body)]
    (loop [i 0
           out []]
      (if (>= i len)
        out
        (let [j (or (str/index-of body "\\" i) len)
              out (if (< i j) (into out (text-units (subs body i j))) out)]
          (if (>= j len)
            out
            (let [e (if (< (inc j) len) (subs body (inc j) (+ j 2)) "")]
              (cond
                (= e "") (syntax! n "Invalid escape sequence")
                (contains? {"n" 10, "t" 9, "r" 13, "b" 8, "f" 12, "v" 11} e)
                (recur (+ j 2) (conj out (get {"n" 10, "t" 9, "r" 13, "b" 8,
                                               "f" 12, "v" 11}
                                              e)))
                (and (= e "0")
                     (not (re-matches #"[0-9]"
                                      (if (< (+ j 2) len)
                                        (subs body (+ j 2) (+ j 3))
                                        ""))))
                (recur (+ j 2) (conj out 0))
                (re-matches #"[0-9]" e)
                (syntax! n "Octal escape sequences are not allowed in strict mode")
                (= e "x")
                (recur (+ j 4)
                       (conj out (hex-value n (subs body
                                                    (min len (+ j 2))
                                                    (min len (+ j 4))))))
                (= e "u")
                (if (and (< (+ j 2) len) (= "{" (subs body (+ j 2) (+ j 3))))
                  (let [close (str/index-of body "}" j)]
                    (when (nil? close) (syntax! n "Invalid Unicode escape sequence"))
                    (let [cp (hex-value n (subs body (+ j 3) close))]
                      (when (> cp 0x10FFFF)
                        (syntax! n "Undefined Unicode code-point"))
                      (recur (inc close) (into out (cp-units cp)))))
                  (recur (+ j 6)
                         (conj out (hex-value n (subs body
                                                      (min len (+ j 2))
                                                      (min len (+ j 6)))))))
                (= e "\r") (recur (if (and (< (+ j 2) len)
                                           (= "\n" (subs body (+ j 2) (+ j 3))))
                                    (+ j 3)
                                    (+ j 2))
                                  out)
                (contains? #{"\n" " " " "} e) (recur (+ j 2) out)
                :else (recur (+ j 2) (into out (text-units e)))))))))))


(defn- string-literal
  "The value of one StringLiteral token text."
  [n text]
  (let [body (subs text 1 (dec (count text)))]
    (if (str/includes? body "\\")
      (string-value (unescape n body))
      {:js/str body})))


(defn- host-double
  [t]
  #?(:cljd (double/parse t)
     :clj (Double/parseDouble t)
     :cljs (js/parseFloat t)))


(defn- radix-double
  [n digits radix]
  (when-not (re-matches (case radix
                          16 #"[0-9a-fA-F]+"
                          8 #"[0-7]+"
                          2 #"[01]+")
                        digits)
    (syntax! n "Invalid or unexpected token"))
  (reduce (fn [acc ch]
            (+ (* acc (double radix))
               (double (str/index-of "0123456789abcdef"
                                     (str/lower-case (str ch))))))
          0.0
          digits))


(defn- radix-int
  [n digits radix]
  (when-not (re-matches (case radix
                          16 #"[0-9a-fA-F]+"
                          8 #"[0-7]+"
                          2 #"[01]+"
                          10 #"[0-9]+")
                        digits)
    (syntax! n "Invalid or unexpected token"))
  (reduce (fn [acc ch]
            (+ (* acc radix)
               (str/index-of "0123456789abcdef" (str/lower-case (str ch)))))
          0
          digits))


(defn- decimal-text
  "Host-parseable decimal text: a digit on each side of the point."
  [t]
  (let [t (if (str/starts-with? t ".") (str "0" t) t)
        t (str/replace t ".e" ".0e")
        t (str/replace t ".E" ".0E")]
    (if (str/ends-with? t ".") (str t "0") t)))


(defn- number-literal
  "The node of one numeric literal token: float64 content."
  [n text]
  (let [t (str/replace text "_" "")
        lower (str/lower-case t)
        v (cond
            (str/starts-with? lower "0x") (radix-double n (subs lower 2) 16)
            (str/starts-with? lower "0o") (radix-double n (subs lower 2) 8)
            (str/starts-with? lower "0b") (radix-double n (subs lower 2) 2)
            (re-find #"^0[0-9]" lower)
            (syntax! n "Legacy octal literals are not allowed in strict mode")
            :else (host-double (decimal-text t)))]
    (u/lit (cbor/float64 v))))


(def ^:private max-native-digits
  "Digits of a BigInt literal that always fit 2^53 - 1, per radix."
  {10 15, 16 13, 8 17, 2 52})


(defn- bigint-literal
  "The node of one BigInt literal token: native when it fits 2^53 - 1,
   else `(js/string->bigint text)`."
  [n text]
  (let [t (str/replace (subs text 0 (dec (count text))) "_" "")
        lower (str/lower-case t)
        [radix digits] (cond
                         (str/starts-with? lower "0x") [16 (subs lower 2)]
                         (str/starts-with? lower "0o") [8 (subs lower 2)]
                         (str/starts-with? lower "0b") [2 (subs lower 2)]
                         :else [10 lower])]
    (when-not (re-matches (case radix
                            16 #"[0-9a-f]+"
                            8 #"[0-7]+"
                            2 #"[01]+"
                            10 #"(0|[1-9][0-9]*)")
                          digits)
      (syntax! n "Invalid BigInt literal"))
    (if (<= (count digits) (get max-native-digits radix))
      (u/lit (radix-int n digits radix))
      (app* 'js/string->bigint (u/lit (jstr lower))))))


(defn- template-parts
  "The pieces of a `templateStringLiteral`: `[:text units]` runs (escapes
   cooked over each run of adjacent text atoms) and `[:expr node]`."
  [ctx n]
  (let [atoms (rules ctx n "templateStringAtom")
        flush (fn [parts raw]
                (if (seq raw)
                  (conj parts [:text (unescape n (apply str raw))])
                  parts))
        [parts raw]
        (reduce (fn [[parts raw] a]
                  (let [ks (kids ctx a)]
                    (if (= 1 (count ks))
                      [parts (conj raw (:text (first ks)))]
                      [(conj (flush parts raw)
                             [:expr (first (rules ctx a "singleExpression"))])
                       []])))
                [[] []]
                atoms)]
    (flush parts raw)))


(declare lower-expr lower-seq lower-named)


(defn- lower-template
  [ctx n]
  (let [nodes (mapv (fn [[kind v]]
                      (case kind
                        :text (u/lit (string-value v))
                        :expr (app* 'js/to-string (lower-expr ctx v))))
                    (template-parts ctx n))]
    (if (empty? nodes)
      (u/lit (jstr ""))
      (reduce (fn [acc x] (app* 'js/str-concat acc x)) nodes))))


(defn- lower-literal
  [ctx n]
  (let [k (first (kids ctx n))]
    (cond
      (p/token? k)
      (case (:type k)
        "NullLiteral" (u/lit :js/null)
        "BooleanLiteral" (u/lit (= "true" (:text k)))
        "StringLiteral" (u/lit (string-literal n (:text k)))
        "RegularExpressionLiteral" (refuse! n "regular expression literal" :never)
        (default-arm n))
      (p/rule? k "numericLiteral") (number-literal k (text-of ctx k))
      (p/rule? k "bigintLiteral") (bigint-literal k (text-of ctx k))
      (p/rule? k "templateStringLiteral") (lower-template ctx k)
      :else (default-arm k))))


;; =============================================================================
;; Names and cells
;; =============================================================================

(defn- resolve-name
  [ctx nm]
  (scope/resolve (:analysis ctx) (:scope ctx) nm))


(defn- read-name
  [ctx nm]
  (let [r (resolve-name ctx nm)]
    (if (= :cell (:kind r))
      (if (:tdz? r)
        (app* 'js/lexical-get (u/v (symbol nm)) (u/lit (jstr nm)))
        (app* 'cell/get (u/v (symbol nm))))
      (app* 'js/global-get (u/lit (jstr nm))))))


(defn- init-name
  "The write that initializes binding `nm` (a declaration)."
  [nm value]
  (app* 'cell/set! (u/v (symbol nm)) value))


(defn- assign-name
  "Assignment `nm = value-sym`, the value of which is the value."
  [ctx nm value-sym]
  (let [r (resolve-name ctx nm)
        v (u/v value-sym)]
    (cond
      (not= :cell (:kind r))
      ;; strict code: an undeclared name is a ReferenceError; a declared
      ;; global is a known gap (T2 owns the global object's writes)
      (u/then (app* 'js/global-get (u/lit (jstr nm)))
              (app* 'js/throw-type-error
                    (u/lit (jstr (str "Cannot assign to read only property '"
                                      nm "' of object")))))
      (:const? r) (u/then (app* 'js/lexical-get (u/v (symbol nm)) (u/lit (jstr nm)))
                          (app* 'js/const-assign!))
      (:tdz? r) (u/then (app* 'js/lexical-get (u/v (symbol nm)) (u/lit (jstr nm)))
                        (u/then (app* 'cell/set! (u/v (symbol nm)) v) v))
      :else (u/then (app* 'cell/set! (u/v (symbol nm)) v) v))))


(defn- binding-names
  "`{:params :vars :tdz}` of scope record `s`: parameter cells come from
   the arguments, `var` cells start undefined, the rest start in TDZ."
  [s]
  (let [params (vec (distinct (:params s)))
        pset (set params)
        fns (remove pset (:functions s))
        vars (remove (set (concat pset fns)) (:vars s))
        lex (remove pset (keys (:lexical s)))]
    {:params params,
     :vars (vec vars),
     :tdz-names (vec (sort (concat fns lex)))}))


(defn- bind-cells
  "`body` under one lambda binding each `[name init-node]` to a fresh cell."
  [pairs body]
  (if (empty? pairs)
    body
    (apply u/app
           (u/lam (mapv (comp symbol first) pairs) body)
           (mapv (fn [[_ init]] (app* 'cell/new init)) pairs))))


(def ^:private tdz (u/lit :js/tdz))


(defn- block-pairs
  [s]
  (let [{:keys [params vars tdz-names]} (binding-names s)]
    (vec (concat (map (fn [nm] [nm undefined]) (concat params vars))
                 (map (fn [nm] [nm tdz]) tdz-names)))))


(defn- scope-of
  [ctx n]
  (get-in ctx [:analysis :scopes (:id n)]))


;; =============================================================================
;; Functions
;; =============================================================================

(defn- check-params!
  "Refuse every parameter form past plain names (defaults, rest,
   patterns: slice T4) and any type syntax."
  [ctx plist]
  (when plist
    (letfn [(go
              [m]
              (when (p/rule? m)
                (case (:rule m)
                  ("requiredParameter" "formalParameterArg")
                  (do (check-consumed! ctx m)
                      (when (p/has-token? (:pk ctx) m "=")
                        (refuse! m "default parameter" :T4))
                      (when (p/has-token? (:pk ctx) m "?")
                        (refuse! m "type syntax" :T8))
                      (doseq [k (rule-kids ctx m)]
                        (case (:rule k)
                          ("identifierOrPattern" "assignable")
                          (let [inner (first (rule-kids ctx k))]
                            (when-not (and inner
                                           (contains? #{"identifierName" "identifier"}
                                                      (:rule inner)))
                              (refuse! k "destructuring" :T4)))
                          nil)))
                  ("restParameter" "lastFormalParameterArg")
                  (refuse! m "rest parameter" :T4)
                  "optionalParameter"
                  (if (p/has-token? (:pk ctx) m "?")
                    (refuse! m "type syntax" :T8)
                    (refuse! m "default parameter" :T4))
                  ("arrayLiteral" "objectLiteral")
                  (refuse! m "destructuring" :T4)
                  (run! go (rule-kids ctx m)))))]
      (go plist))))


(defn- function-parts
  "`{:plist :body :arrow? :expr-body}` of function-like node `n`, after
   refusing async, generator and type syntax."
  [ctx n]
  (case (:rule n)
    "functionDeclaration"
    (let [sig (first (rules ctx n "callSignature"))]
      (when (token-kid ctx n "async") (refuse! n "async function" :T6))
      (when (token-kid ctx n "*") (refuse! n "generator function" :T5))
      (check-consumed! ctx sig)
      (when-not (first (rules ctx n "functionBody"))
        (refuse! n "type syntax" :T8))
      {:plist (first (rules ctx sig "parameterList")),
       :body (first (rules ctx n "functionBody"))})
    "anonymousFunction"
    (do (when (token-kid ctx n "async") (refuse! n "async function" :T6))
        (when (token-kid ctx n "*") (refuse! n "generator function" :T5))
        (check-consumed! ctx n)
        {:plist (first (rules ctx n "formalParameterList")),
         :body (first (rules ctx n "functionBody"))})
    "arrowFunctionDeclaration"
    (let [params (first (rules ctx n "arrowFunctionParameters"))
          body (first (rules ctx n "arrowFunctionBody"))]
      (when (token-kid ctx n "async") (refuse! n "async function" :T6))
      (check-consumed! ctx n)
      {:arrow? true,
       :plist (first (rules ctx params "formalParameterList")),
       :body (if (token-kid ctx body "{")
               (first (rules ctx body "functionBody"))
               nil),
       :expr-body (first (rules ctx body "singleExpression"))})))


(declare lower-stmts statements-of)


(defn- function-value
  "The `js/make-function` node of function-like node `n`, named `fname`
   (a string). `scope-n` is the node that owns the function's scope."
  [ctx n scope-n fname]
  (let [{:keys [plist body arrow? expr-body]} (function-parts ctx n)
        _ (check-params! ctx plist)
        s (scope-of ctx scope-n)
        this-sym (if arrow? (:this ctx) (gen "this" n))
        args-sym (gen "args" n)
        ret (gen "ret" n)
        fctx (assoc ctx
                    :scope (:id scope-n)
                    :this this-sym
                    :ret ret
                    :loop nil
                    :labels {}
                    :pending-labels nil)
        stmts (when body (statements-of fctx body))
        has-return? (and body
                         (core/contains-rule? fctx body "returnStatement" function-rules))
        inner (if expr-body
                (lower-expr fctx expr-body)
                (let [b (seq-of (lower-stmts fctx stmts))]
                  (if has-return?
                    (app* 'js/call-ec (u/lam [ret] b))
                    b)))
        {:keys [params vars tdz-names]} (binding-names s)
        param-pairs (map-indexed (fn [i nm]
                                   [nm (app* 'js/arg
                                             (u/v args-sym)
                                             (u/lit i))])
                                 params)
        pairs (vec (concat param-pairs
                           (map (fn [nm] [nm undefined]) vars)
                           (map (fn [nm] [nm tdz]) tdz-names)))
        code (site [:call fname (first (:span n))]
                   (u/lam [(if arrow? (gen "athis" n) this-sym) args-sym]
                          (bind-cells pairs inner)))
        value (app* 'js/make-function
                    (u/lit (jstr fname))
                    (u/lit (count params))
                    (u/lit (if arrow? :lexical :strict))
                    (u/lit (if arrow? :arrow :normal))
                    code)]
    (if-let [self (:self s)]
      (let [cell-sym (symbol self)]
        (u/app (u/lam [cell-sym]
                      (u/then (app* 'cell/set! (u/v cell-sym) value)
                              (app* 'cell/get (u/v cell-sym))))
               (app* 'cell/new undefined)))
      value)))


(defn- function-node
  "The function value of an expression-position function node `fn-n` (an
   `anonymousFunction` or `arrowFunctionDeclaration` node)."
  [ctx fn-n hint]
  (let [c (first (kids ctx fn-n))]
    (cond
      (p/rule? c "functionDeclaration")
      (function-value ctx c c (text-of ctx (first (rules ctx c "identifier"))))
      (p/rule? c "arrowFunctionDeclaration")
      (function-value ctx c c (or hint ""))
      :else (function-value ctx fn-n fn-n (or hint "")))))


;; =============================================================================
;; Expressions
;; =============================================================================

(def ^:private binary-ops
  {"+" 'js/add, "-" 'js/sub, "*" 'js/mul, "/" 'js/div, "%" 'js/mod,
   "**" 'js/pow, "&" 'js/bit-and, "|" 'js/bit-or, "^" 'js/bit-xor,
   "<<" 'js/shl, ">>" 'js/sar, ">>>" 'js/shr, "<" 'js/lt, ">" 'js/gt,
   "<=" 'js/le, ">=" 'js/ge, "==" 'js/loose-eq, "===" 'js/strict-eq})


(def ^:private compound-ops
  {"+=" "+", "-=" "-", "*=" "*", "/=" "/", "%=" "%", "**=" "**",
   "<<=" "<<", ">>=" ">>", ">>>=" ">>>", "&=" "&", "|=" "|", "^=" "^"})


(defn- negate
  [x]
  (u/if-node x (u/lit false) (u/lit true)))


(defn- simple-name
  "The identifier when `n` is a `singleExpression` that is only a name."
  [ctx n]
  (let [ks (kids ctx n)]
    (when (and (= 1 (count ks)) (p/rule? (first ks) "identifierName"))
      (let [t (text-of ctx (first ks))]
        (when-not (contains? #{"null" "true" "false"} t) t)))))


(defn- member-target?
  [ctx n]
  (let [ks (kids ctx n)]
    (and (< 1 (count ks))
         (or (and (p/token? (nth ks 1) ".") (p/rule? (peek ks) "identifierName"))
             (and (some #(p/token? % "[") ks)
                  (p/rule? (nth ks (- (count ks) 2)) "expressionSequence"))))))


(defn- target-name!
  "The identifier an assignment or update writes; refuse member targets."
  [ctx n]
  (or (simple-name ctx n)
      (let [inner (when (and (= 3 (count (kids ctx n)))
                             (p/token? (first (kids ctx n)) "("))
                    (nth (kids ctx n) 1))]
        (when inner
          (when-let [e (first (rules ctx inner "singleExpression"))]
            (simple-name ctx e))))
      (if (member-target? ctx n)
        (refuse! n "property write" :T2)
        (syntax! n "Invalid left-hand side in assignment"))))


(defn- one-for
  "The unit of `++`/`--` for numeric value node `old`."
  [old]
  (u/if-node (app* '= (app* 'js/kind-of old) (u/lit :bigint))
             (u/lit 1)
             (u/lit (cbor/float64 1.0))))


(defn- lower-update
  "`++x`, `--x`, `x++`, `x--` on an identifier."
  [ctx n target op prefix?]
  (let [nm (target-name! ctx target)
        old (gen "o" n)
        nv (gen "n" n)
        f (if (= "++" op) 'js/add 'js/sub)]
    (u/let1 old
            (app* 'js/to-numeric (read-name ctx nm))
            (u/let1 nv
                    (app* f (u/v old) (one-for (u/v old)))
                    (u/then (assign-name ctx nm nv)
                            (u/v (if prefix? nv old)))))))


(defn- lower-assign
  [ctx n lhs op rhs]
  (let [nm (target-name! ctx lhs)
        v (gen "v" n)]
    (case op
      "=" (u/let1 v (if (p/rule? rhs "singleExpression")
                      (lower-named ctx rhs nm)
                      (lower-expr ctx rhs))
                  (assign-name ctx nm v))
      (let [bin (get compound-ops op)]
        (when-not bin
          (refuse! n (str "assignment operator " op) :T4))
        (u/let1 v
                (app* (get binary-ops bin) (read-name ctx nm) (lower-expr ctx rhs))
                (assign-name ctx nm v))))))


(defn- lower-args
  [ctx args-node]
  (let [al (when args-node (first (rules ctx args-node "argumentList")))
        args (when al (rules ctx al "argument"))]
    (build-vector
      (mapv (fn [a]
              (when (p/has-token? (:pk ctx) a "...")
                (refuse! a "spread argument" :T4))
              (if-let [e (first (rules ctx a "singleExpression"))]
                (lower-expr ctx e)
                (read-name ctx (text-of ctx a))))
            args))))


(defn- member-parts
  "`[object-node key-node]` of a member read `n` (dot or index form)."
  [ctx n]
  (let [ks (kids ctx n)
        obj (first ks)]
    (when (some #(p/token? % "?.") ks) (refuse! n "optional chaining" :T4))
    (cond
      (and (some #(p/token? % ".") ks)
           (p/rule? (peek ks) "identifierName"))
      (do (when (some #(p/token? % "?") ks) (refuse! n "optional chaining" :T4))
          (when (some #(p/token? % "!") ks) (refuse! n "type syntax" :T8))
          (when (some #(p/token? % "#") ks) (refuse! n "private name" :T3))
          [obj (u/lit (jstr (text-of ctx (peek ks))))])
      (and (some #(p/token? % ".") ks) (p/rule? (peek ks) "typeGeneric"))
      (refuse! n "type syntax" :T8)
      :else
      [obj (app* 'js/to-property-key
                 (lower-seq ctx (nth ks (- (count ks) 2))))])))


(defn- member-expression?
  [ctx n]
  (let [ks (kids ctx n)]
    (and (< 2 (count ks))
         (p/rule? (first ks) "singleExpression")
         (or (and (some #(p/token? % ".") ks)
                  (contains? #{"identifierName" "typeGeneric"} (:rule (peek ks))))
             (and (p/token? (peek ks) "]")
                  (some #(p/token? % "[") ks)
                  (p/rule? (nth ks (- (count ks) 2)) "expressionSequence"))))))


(defn- refuse-global!
  "Permanently refuse (design section 8) the bare identifier `nm` at
   `callee` when it resolves to the global, not to a declared binding."
  [ctx callee nm]
  (when (and (= nm (simple-name ctx callee))
             (= :global (:kind (resolve-name ctx nm))))
    (refuse! callee nm :never)))


(defn- lower-call
  [ctx n]
  (let [ks (kids ctx n)
        callee (first ks)
        args (lower-args ctx (second ks))]
    (refuse-global! ctx callee "eval")
    (if (member-expression? ctx callee)
      (let [o (gen "o" n)
            [obj key] (member-parts ctx callee)]
        (u/let1 o
                (lower-expr ctx obj)
                (app* 'js/call (app* 'js/get-value (u/v o) key) (u/v o) args)))
      (app* 'js/call (lower-expr ctx callee) undefined args))))


(defn- lower-new
  [ctx n]
  (let [ks (kids ctx n)
        callee (second ks)
        args (first (rules ctx n "arguments"))]
    (when (first (rules ctx n "typeArguments")) (refuse! n "type syntax" :T8))
    (refuse-global! ctx callee "Function")
    (app* 'js/construct (lower-expr ctx callee) (lower-args ctx args))))


(defn- lower-typeof
  [ctx operand]
  (if-let [nm (simple-name ctx operand)]
    (let [r (resolve-name ctx nm)]
      (if (= :cell (:kind r))
        (app* 'js/typeof (read-name ctx nm))
        (app* 'js/global-typeof (u/lit (jstr nm)))))
    (app* 'js/typeof (lower-expr ctx operand))))


(defn- lower-prefix
  [ctx n op operand]
  (case op
    "+" (app* 'js/pos (lower-expr ctx operand))
    "-" (app* 'js/neg (lower-expr ctx operand))
    "~" (app* 'js/bit-not (lower-expr ctx operand))
    "!" (negate (truthy (lower-expr ctx operand)))
    "typeof" (lower-typeof ctx operand)
    "void" (u/then (lower-expr ctx operand) undefined)
    "++" (lower-update ctx n operand op true)
    "--" (lower-update ctx n operand op true)
    "delete" (refuse! n "delete" :T2)
    "await" (refuse! n "await" :T6)
    (default-arm n)))


(defn- operator-text
  [ks]
  (apply str (map :text (butlast (rest ks)))))


(defn- lower-binary
  [ctx n ks]
  (let [op (operator-text ks)
        a (first ks)
        b (peek ks)]
    (case op
      "&&" (let [t (gen "a" n)]
             (u/let1 t (lower-expr ctx a)
                     (u/if-node (truthy (u/v t)) (lower-expr ctx b) (u/v t))))
      "||" (let [t (gen "a" n)]
             (u/let1 t (lower-expr ctx a)
                     (u/if-node (truthy (u/v t)) (u/v t) (lower-expr ctx b))))
      "!=" (negate (app* 'js/loose-eq (lower-expr ctx a) (lower-expr ctx b)))
      "!==" (negate (app* 'js/strict-eq (lower-expr ctx a) (lower-expr ctx b)))
      "??" (refuse! n "nullish coalescing" :T4)
      "?." (refuse! n "optional chaining" :T4)
      "instanceof" (refuse! n "instanceof" :T3)
      "in" (refuse! n "in operator" :T2)
      (if-let [f (get binary-ops op)]
        (app* f (lower-expr ctx a) (lower-expr ctx b))
        (default-arm n)))))


(defn- lower-primary
  [ctx n k]
  (cond
    (p/token? k "this") (u/v (:this ctx))
    (p/token? k "super") (refuse! n "super" :T3)
    (p/token? k "class") (refuse! n "class" :T3)
    (p/rule? k "literal") (lower-literal ctx k)
    (p/rule? k "identifierName")
    (let [t (text-of ctx k)]
      (case t
        "null" (u/lit :js/null)
        "true" (u/lit true)
        "false" (u/lit false)
        (read-name ctx t)))
    (p/rule? k "anonymousFunction") (function-node ctx k nil)
    :else (default-arm k)))


(defn- lower-expr
  "The node of `singleExpression` `n`."
  [ctx n]
  (let [ks (kids ctx n)
        c (count ks)
        k0 (first ks)
        k1 (when (< 1 c) (second ks))]
    (cond
      (= c 1) (lower-primary ctx n k0)
      (p/token? k0 "class") (refuse! n "class" :T3)
      (p/rule? k0 "typeArguments") (refuse! n "type syntax" :T8)
      (and (= c 3) (p/token? k0 "(")) (lower-seq ctx k1)
      (p/token? k0 "new") (lower-new ctx n)
      (and (= c 2) (p/token? k0)) (lower-prefix ctx n (:text k0) k1)
      (and (= c 2) (p/rule? k1 "arguments")) (lower-call ctx n)
      (and (= c 3) (p/rule? k1 "typeArguments")) (refuse! n "type syntax" :T8)
      (and (= c 2) (p/rule? k1 "templateStringLiteral"))
      (refuse! n "tagged template" :T5)
      (and (= c 2) (p/token? k1 "!")) (refuse! n "type syntax" :T8)
      (and (= c 2) (p/token? k1))
      (lower-update ctx n k0 (:text k1) false)
      (and (= c 3) (or (p/token? k1 "as") (p/token? k1 "satisfies")))
      (refuse! n "type syntax" :T8)
      (and (= c 3) (p/token? k1 "=")) (lower-assign ctx n k0 "=" (nth ks 2))
      (and (= c 3) (p/rule? k1 "assignmentOperator"))
      (lower-assign ctx n k0 (text-of ctx k1) (nth ks 2))
      (member-expression? ctx n)
      (let [[obj key] (member-parts ctx n)]
        (app* 'js/get-value (lower-expr ctx obj) key))
      (and (= c 5) (p/token? k1 "?") (p/token? (nth ks 3) ":"))
      (u/if-node (truthy (lower-expr ctx k0))
                 (lower-expr ctx (nth ks 2))
                 (lower-expr ctx (nth ks 4)))
      (and (<= 3 c)
           (p/rule? k0 "singleExpression")
           (p/rule? (peek ks) "singleExpression")
           (every? p/token? (butlast (rest ks))))
      (lower-binary ctx n ks)
      :else (default-arm n))))


(defn- lower-named
  "`lower-expr` with function-name hint `nm` for an anonymous function."
  [ctx n nm]
  (let [ks (kids ctx n)]
    (if (and (= 1 (count ks)) (p/rule? (first ks) "anonymousFunction"))
      (let [f (first (kids ctx (first ks)))]
        (if (p/rule? f "functionDeclaration")
          (lower-expr ctx n)
          (function-node ctx (first ks) nm)))
      (lower-expr ctx n))))


(defn- lower-seq
  "The node of an `expressionSequence`: comma sequencing."
  [ctx n]
  (u/seq-nodes (mapv #(lower-expr ctx %) (rules ctx n "singleExpression"))))


;; =============================================================================
;; Statements
;; =============================================================================

(declare lower-stmt lower-block)


(defn statements-of
  "The `statement` nodes below a program, function body, block or case
   clause."
  [ctx n]
  (let [holder (or (first (rules ctx n "sourceElements"))
                   (first (rules ctx n "statementList")))]
    (if holder
      (mapv (fn [el]
              (if (p/rule? el "sourceElement")
                (do (when (token-kid ctx el "export")
                      (refuse! el "module syntax" :T7))
                    (first (rules ctx el "statement")))
                el))
            (rule-kids ctx holder))
      [])))


(defn- function-statement?
  [ctx st]
  (p/rule? (first (rule-kids ctx st)) "functionDeclaration"))


(defn- hoisted-init
  [ctx st]
  (let [f (first (rule-kids ctx st))
        nm (text-of ctx (first (rules ctx f "identifier")))]
    (app* 'cell/set! (u/v (symbol nm)) (function-value ctx f f nm))))


(defn lower-stmts
  "The nodes of statements `stmts`: hoisted function declarations first."
  [ctx stmts]
  (vec (concat (map #(hoisted-init ctx %) (filter #(function-statement? ctx %) stmts))
               (map #(lower-stmt ctx %) stmts))))


(defn- lower-block
  "A `block` as one node, opening its scope."
  [ctx n]
  (let [s (scope-of ctx n)
        bctx (assoc ctx :scope (:id n))
        stmts (statements-of bctx n)]
    (bind-cells (block-pairs s) (seq-of (lower-stmts bctx stmts)))))


(defn- lower-var
  [ctx n]
  (when (or (token-kid ctx n "declare") (first (rules ctx n "bindingPattern")))
    (refuse! n "destructuring" :T4))
  (check-consumed! ctx n)
  (let [[kind decls] (scope/declarations (:pk ctx) n)]
    (when-not decls (default-arm n))
    (seq-of
      (mapv (fn [d]
              (check-consumed! ctx d)
              (let [nm (scope/declaration-name (:pk ctx) d)]
                (when-not nm (refuse! d "destructuring" :T4))
                (if-let [init (scope/declaration-initializer (:pk ctx) d)]
                  (init-name nm (lower-named ctx init nm))
                  (if (= :var kind)
                    undefined
                    (init-name nm undefined)))))
            decls))))


(defn- lower-if
  [ctx n]
  (let [test (first (rules ctx n "expressionSequence"))
        [then else] (rules ctx n "statement")]
    (u/if-node (truthy (lower-seq ctx test))
               (lower-stmt ctx then)
               (if else (lower-stmt ctx else) undefined))))


(defn- loop-parts
  "The shared parts of a loop over statement `body`: its escapes and the
   body lowered under them. `labels` are the loop's own labels."
  [ctx n labels body-ctx body]
  (let [lset (set labels)
        brk (gen "brk" n)
        cont (gen "cont" n)
        brk? (targets? ctx body "breakStatement" lset true true)
        cont? (targets? ctx body "continueStatement" lset true false)
        target {:brk brk, :cont (when cont? cont)}
        lctx (-> body-ctx
                 (assoc :loop target)
                 (update :labels merge
                         (into {} (map (fn [l] [l target]) labels))))
        b (lower-stmt lctx body)]
    {:brk? brk?,
     :brk brk,
     :body (wrap-escape cont? cont b)}))


(defn- spin
  "The self-applied loop `w` over lambda parameters `params` (a vector of
   symbols after `w`), marked as a `:loop` site, started with `start`
   operand nodes."
  [w params step start]
  (u/let1 w
          (site :loop (u/lam (into [w] params) step))
          (apply u/app (u/v w) (u/v w) start)))


(defn- lower-while
  [ctx n labels]
  (let [test (first (rules ctx n "expressionSequence"))
        body (first (rules ctx n "statement"))
        {:keys [brk? brk] b :body} (loop-parts ctx n labels ctx body)
        w (gen "w" n)]
    (wrap-escape brk? brk
                 (spin w
                       []
                       (u/if-node (truthy (lower-seq ctx test))
                                  (u/then b (u/app (u/v w) (u/v w)))
                                  undefined)
                       []))))


(defn- lower-do
  [ctx n labels]
  (let [test (first (rules ctx n "expressionSequence"))
        body (first (rules ctx n "statement"))
        {:keys [brk? brk] b :body} (loop-parts ctx n labels ctx body)
        w (gen "w" n)]
    (wrap-escape brk? brk
                 (spin w
                       []
                       (u/then b
                               (u/if-node (truthy (lower-seq ctx test))
                                          (u/app (u/v w) (u/v w))
                                          undefined))
                       []))))


(defn- for-sections
  "`{:init :test :update}` of a C-style `for` node: the child nodes before,
   between and after its two semicolons."
  [ctx n]
  (let [ks (kids ctx n)]
    (loop [ks ks
           section 0
           out {}]
      (if (empty? ks)
        out
        (let [k (first ks)]
          (cond
            (p/token? k ";") (recur (rest ks) (inc section) out)
            (p/token? k ")") out
            (and (p/rule? k "expressionSequence") (= 0 section))
            (recur (rest ks) section (assoc out :init k))
            (and (p/rule? k "expressionSequence") (= 1 section))
            (recur (rest ks) section (assoc out :test k))
            (and (p/rule? k "expressionSequence") (= 2 section))
            (recur (rest ks) section (assoc out :update k))
            :else (recur (rest ks) section out)))))))


(defn- lower-for
  [ctx n labels]
  (let [pk (:pk ctx)
        body (last (rules ctx n "statement"))
        {:keys [init test update]} (for-sections ctx n)
        decl-list (first (rules ctx n "variableDeclarationList"))
        kind (scope/declaration-kind pk (first (rules ctx n "varModifier")))
        lexical? (and decl-list (contains? #{:let :const} kind))
        sctx (if lexical? (assoc ctx :scope (:id n)) ctx)
        w (gen "w" n)
        init-node (cond
                    (not decl-list) (if init (lower-seq sctx init) undefined)
                    :else (lower-var sctx n))
        test-node (if test (truthy (lower-seq sctx test)) (u/lit true))
        {:keys [brk? brk] b :body} (loop-parts ctx n labels sctx body)
        update-node (if update (lower-seq sctx update) undefined)]
    (wrap-escape
      brk?
      brk
      (if lexical?
        (let [names (mapv symbol (sort (keys (:lexical (scope-of ctx n)))))
              next-iteration (apply u/app
                                    (u/lam names
                                           (u/then update-node
                                                   (apply u/app (u/v w) (u/v w) (map u/v names))))
                                    (map (fn [nm]
                                           (app* 'cell/new (app* 'cell/get (u/v nm))))
                                         names))]
          (bind-cells (block-pairs (scope-of ctx n))
                      (u/then init-node
                              (spin w
                                    names
                                    (u/if-node test-node
                                               (u/then b next-iteration)
                                               undefined)
                                    (map (fn [nm]
                                           (app* 'cell/new (app* 'cell/get (u/v nm))))
                                         names)))))
        (u/then init-node
                (spin w
                      []
                      (u/if-node test-node
                                 (u/then b (u/then update-node (u/app (u/v w) (u/v w))))
                                 undefined)
                      []))))))


(defn- lower-iteration
  [ctx n labels]
  (let [ks (kids ctx n)
        k0 (first ks)]
    (cond
      (p/token? k0 "do") (lower-do ctx n labels)
      (p/token? k0 "while") (lower-while ctx n labels)
      (token-kid ctx n "in") (refuse! n "for-in" :T2)
      (= 2 (count (filter #(p/token? % ";") ks))) (lower-for ctx n labels)
      :else (refuse! n "for-of" :T5))))


(defn- lower-jump
  [ctx n]
  (let [label (label-of ctx n)
        break? (p/rule? n "breakStatement")
        target (if label
                 (get-in ctx [:labels label])
                 (:loop ctx))
        sym (if break? (:brk target) (:cont target))]
    (when-not sym
      (syntax! n "Illegal jump statement"))
    (u/app (u/v sym) undefined)))


(defn- lower-return
  [ctx n]
  (let [e (first (rules ctx n "expressionSequence"))]
    (u/app (u/v (:ret ctx)) (if e (lower-seq ctx e) undefined))))


(defn- lower-labelled
  [ctx n]
  (let [chain (loop [m n, labels []]
                (let [body (first (rules ctx m "statement"))
                      inner (first (rule-kids ctx body))
                      labels' (conj labels (label-of ctx m))]
                  (if (p/rule? inner "labelledStatement")
                    (recur inner labels')
                    [labels' body inner])))
        [labels body inner] chain]
    (if (p/rule? inner "iterationStatement")
      (lower-stmt (update ctx :pending-labels (fnil into []) labels) body)
      (let [brk (gen "lbrk" n)
            lctx (reduce (fn [c l] (assoc-in c [:labels l] {:brk brk})) ctx labels)
            b (lower-stmt lctx body)]
        (wrap-escape (targets? ctx body "breakStatement" (set labels) false false)
                     brk
                     b)))))


(defn- case-clauses
  "The clauses of a `caseBlock` in source order: `{:test node-or-nil
   :stmts [...]}`."
  [ctx cb]
  (vec (mapcat (fn [k]
                 (case (:rule k)
                   "caseClauses" (map (fn [c]
                                        {:test (first (rules ctx c "expressionSequence")),
                                         :stmts (statements-of ctx c)})
                                      (rules ctx k "caseClause"))
                   "defaultClause" [{:test nil, :stmts (statements-of ctx k)}]
                   nil))
               (rule-kids ctx cb))))


(defn- lower-switch
  [ctx n]
  (let [disc (first (rules ctx n "expressionSequence"))
        cb (first (rules ctx n "caseBlock"))
        clauses (case-clauses ctx cb)
        d (gen "d" n)
        brk (gen "sbrk" n)
        sctx (-> ctx
                 (assoc :scope (:id cb))
                 (assoc-in [:loop :brk] brk))
        fns (mapv (fn [i] (symbol (str "%case" (:id n) "_" i)))
                  (range (inc (count clauses))))
        all-stmts (vec (mapcat :stmts clauses))
        hoisted (map #(hoisted-init sctx %)
                     (filter #(function-statement? sctx %) all-stmts))
        bodies (mapv (fn [c]
                       (u/seq-nodes
                         (conj (vec (map #(lower-stmt sctx %) (:stmts c)))
                               undefined)))
                     clauses)
        dispatch (reduce (fn [acc i]
                           (let [c (nth clauses i)]
                             (if (:test c)
                               (u/if-node (app* 'js/strict-eq
                                                (u/v d)
                                                (lower-seq sctx (:test c)))
                                          (u/app (u/v (nth fns i)))
                                          acc)
                               acc)))
                         (if-let [di (first (keep-indexed
                                              (fn [i c] (when-not (:test c) i))
                                              clauses))]
                           (u/app (u/v (nth fns di)))
                           undefined)
                         (reverse (range (count clauses))))
        chain (reduce (fn [acc i]
                        (u/let1 (nth fns i)
                                (u/lam [] (u/then (nth bodies i)
                                                  (u/app (u/v (nth fns (inc i))))))
                                acc))
                      dispatch
                      (range (count clauses)))
        run (u/let1 (peek fns) (u/lam [] undefined) chain)]
    (u/let1 d
            (lower-seq ctx disc)
            (bind-cells (block-pairs (scope-of ctx cb))
                        (u/seq-nodes
                          (concat hoisted
                                  [(wrap-escape
                                     (targets? ctx cb "breakStatement" #{} true true)
                                     brk
                                     run)]))))))


(defn- lower-try
  [ctx n]
  (let [block (first (rules ctx n "block"))
        catch-node (first (rules ctx n "catchProduction"))
        fin (first (rules ctx n "finallyProduction"))
        body (lower-block ctx block)
        guarded (if catch-node
                  (let [_ (check-consumed! ctx catch-node)
                        ident (first (rules ctx catch-node "identifier"))
                        blk (first (rules ctx catch-node "block"))
                        e (gen "e" catch-node)
                        cctx (assoc ctx :scope (:id catch-node))]
                    (app* 'js/try
                          (u/lam [] body)
                          (u/lam [e]
                                 (if ident
                                   (u/let1 (symbol (text-of ctx ident))
                                           (app* 'cell/new (u/v e))
                                           (lower-block cctx blk))
                                   (lower-block cctx blk)))))
                  body)]
    (if fin
      (app* 'js/try-finally
            (u/lam [] guarded)
            (u/lam [(gen "fx" n)]
                   (lower-block ctx (first (rules ctx fin "block")))))
      guarded)))


(defn- lower-stmt
  "The node of `statement` `st`."
  [ctx st]
  (let [labels (:pending-labels ctx)
        ctx (dissoc ctx :pending-labels)
        n (first (rule-kids ctx st))]
    (when (token-kid ctx st "export") (refuse! st "module syntax" :T7))
    (case (:rule n)
      "block" (lower-block ctx n)
      "variableStatement" (lower-var ctx n)
      "emptyStatement_" undefined
      "expressionStatement" (lower-seq ctx (first (rules ctx n "expressionSequence")))
      "ifStatement" (lower-if ctx n)
      "iterationStatement" (lower-iteration ctx n labels)
      "continueStatement" (lower-jump ctx n)
      "breakStatement" (lower-jump ctx n)
      "returnStatement" (lower-return ctx n)
      "labelledStatement" (lower-labelled ctx n)
      "switchStatement" (lower-switch ctx n)
      "throwStatement" (app* 'js/throw (lower-seq ctx (first (rules ctx n "expressionSequence"))))
      "tryStatement" (lower-try ctx n)
      "debuggerStatement" undefined
      ;; hoisted to its scope's entry
      "functionDeclaration" undefined
      "arrowFunctionDeclaration" (function-value ctx n n "")
      (default-arm n))))


;; =============================================================================
;; Packets
;; =============================================================================

(defn- context
  [packet]
  (let [pk (p/validate! packet {:diagnostic-ns diagnostic-ns})
        analysis (scope/analyze pk)]
    {:pk pk,
     :analysis analysis,
     :scope (:module analysis),
     :this (gen "this" (p/root pk)),
     :ret nil,
     :loop nil,
     :labels {},
     :pending-labels nil,
     :gen nil,
     :async nil,
     :class nil}))


(defn- module-body
  [ctx]
  (let [root (p/root (:pk ctx))
        s (scope-of ctx root)
        stmts (statements-of ctx root)]
    (bind-cells (let [{:keys [vars tdz-names]} (binding-names s)]
                  (vec (concat (map (fn [nm] [nm undefined]) vars)
                               (map (fn [nm] [nm tdz]) tdz-names))))
                (seq-of (lower-stmts ctx stmts)))))


(defn lower-module-body
  "The module's statements as one node, before the runtime wrapper: what
   lowering goldens compare."
  [packet]
  (module-body (context packet)))


(defn lower-packet
  "The complete program for one ok packet: the module body run by
   `js/run-main`, generated names canonicalized, tail calls marked. Its
   value is `{:js/out [...] :js/uncaught nil-or-snapshot}`.

   `:prelude` selects how the runtime profile arrives: `:bundled` (the
   default) carries the prelude tree ahead of the body; `:linked` requires
   the module `js` from the task's link pair, then allocates the task's
   runtime state with `(js/init!)`."
  ([packet] (lower-packet packet {}))
  ([packet {:keys [prelude], :or {prelude :bundled}}]
   (let [ctx (context packet)
         main (names/canonicalize
                (app* 'js/run-main (u/lam [(:this ctx)] (module-body ctx))))]
     (u/mark-tails
       (case prelude
         :bundled (u/then prelude/uast main)
         :linked (u/seq-nodes [(u/sexp->uast (list 'require
                                                   (list 'quote
                                                         prelude/module-name)))
                               (app* 'js/init!)
                               main]))))))


(def program-medium
  "The program medium's logical identity when the composition names none."
  :yang.ts/program)


(def ^:private stage-config
  {:diagnostic-ns diagnostic-ns,
   :program-medium program-medium,
   :ok? #(= :yang.cst/ok (:yang.cst/outcome %)),
   :lower (fn [state packet]
            (lower-packet packet {:prelude (get state :prelude :bundled)})),
   :option-keys [:prelude :dialect]})


(defn lower-transform
  "The lowering stage's transform (`yang.core.stage/lower-transform`)."
  [state packet]
  (core-stage/lower-transform stage-config state packet))


(defn open-stage
  "A lowering stage reading CST packets from `cst-stream`, writing program
   batches to `program-stream`, whose logical identity is `medium`, and
   diagnostics to `diagnostics-stream`. The fourth argument may instead be
   an options map `{:medium :prelude :dialect}`."
  ([cst-stream program-stream diagnostics-stream]
   (core-stage/open-stage stage-config
                          cst-stream
                          program-stream
                          diagnostics-stream))
  ([cst-stream program-stream diagnostics-stream medium-or-opts]
   (core-stage/open-stage stage-config
                          cst-stream
                          program-stream
                          diagnostics-stream
                          medium-or-opts)))


(defn step-stage
  [s]
  (core-stage/step-stage stage-config s))
