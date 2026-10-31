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
   "namespaceDeclaration" {:construct "namespace", :slice :T9},
   "generatorFunctionDeclaration" {:construct "generator function",
                                   :slice :T5},
   "iteratorBlock" {:construct "iterator block", :slice :T5},
   "generatorBlock" {:construct "generator block", :slice :T5},
   "decoratorList" {:construct "decorator", :slice :never},
   "decorator" {:construct "decorator", :slice :never},
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
    "arrayLiteral" "objectLiteral"
    "arrowFunctionDeclaration" "literal" "templateStringLiteral"
    "enumDeclaration" "enumBody" "enumMemberList" "enumMember"
    "classDeclaration" "classTail" "classElement" "classHeritage"
    "classExtendsClause" "constructorDeclaration" "staticBlock"
    "propertyMemberDeclaration" "propertyMemberBase" "classElementName"
    "privateIdentifier" "initializer"})


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
    "setAccessor" "generatorMethod" "constructorDeclaration" "staticBlock"})


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


(declare lower-expr lower-seq lower-named member-expression? member-parts
         lower-name-update lower-name-assign lower-class simple-name)


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
    (cond
      (= :cell (:kind r))
      (if (:tdz? r)
        (app* 'js/lexical-get (u/v (symbol nm)) (u/lit (jstr nm)))
        (app* 'cell/get (u/v (symbol nm))))
      (and (= nm "arguments") (:arguments ctx))
      (u/v (:arguments ctx))
      :else (app* 'js/global-get (u/lit (jstr nm))))))


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
    ("generatorMethod" "getAccessor" "setAccessor")
    (do (when (token-kid ctx n "async") (refuse! n "async function" :T6))
        (when (token-kid ctx n "*") (refuse! n "generator function" :T5))
        (check-consumed! ctx n)
        {:plist (first (rules ctx n "formalParameterList")),
         :body (first (rules ctx n "functionBody"))})
    "propertyMemberDeclaration"
    (let [sig (first (rules ctx n "callSignature"))]
      (check-consumed! ctx sig)
      (when-not (first (rules ctx n "functionBody"))
        (refuse! n "type syntax" :T8))
      {:plist (first (rules ctx sig "parameterList")),
       :body (first (rules ctx n "functionBody"))})
    "constructorDeclaration"
    (do (check-consumed! ctx n)
        (when-not (first (rules ctx n "functionBody"))
          (refuse! n "type syntax" :T8))
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


(defn- function-code
  "`{:code :arity :nt?}` of function-like node `n`: the code lambda and the
   count of its parameters. `opts` carries what the function's place gives
   it: `:ctor` (`:base` or `:derived` for a class constructor), `:nt?`,
   `:home` (the symbol of the object `super` property access starts from)
   and `:class-cell` (the symbol of the cell holding the class constructor).
   A non-arrow function replaces the lexical `this`, `new.target`, home and
   class of its context; an arrow keeps them."
  [ctx n scope-n fname opts]
  (let [{:keys [plist body arrow? expr-body]} (function-parts ctx n)
        _ (check-params! ctx plist)
        s (scope-of ctx scope-n)
        ctor (:ctor opts)
        nt? (boolean (or (:nt? opts)
                         ctor
                         (and (not arrow?) (:uses-new-target? s))))
        this-sym (if arrow? (:this ctx) (gen "this" n))
        args-sym (gen "args" n)
        nt-sym (gen "nt" n)
        thisc (when (= :derived ctor) (gen "thisc" n))
        ret (gen "ret" n)
        mentions-arguments? (fn mentions-arguments?
                              [node]
                              (or (and (p/token? node)
                                       (= "Identifier" (:type node))
                                       (= "arguments" (:text node)))
                                  (some mentions-arguments? (kids ctx node))))
        args-object (when (and (not arrow?) body (mentions-arguments? body))
                      (gen "arguments" n))
        fctx (cond-> (assoc ctx
                            :scope (:id scope-n)
                            :this this-sym
                            :arguments (if arrow? (:arguments ctx) args-object)
                            :ret ret
                            :loop nil
                            :labels {}
                            :pending-labels nil)
               (not arrow?) (assoc :this-cell thisc
                                   :nt (if nt? nt-sym :undefined)
                                   :home (:home opts)
                                   :class-cell (:class-cell opts)))
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
        body-core (if thisc
                    (app* 'js/derived-result (u/v thisc) inner)
                    inner)
        body-node (bind-cells pairs
                              body-core)
        body-node (if args-object
                    (u/let1 args-object
                            (app* 'js/args-object (u/v args-sym))
                            body-node)
                    body-node)
        body-node (if thisc
                    (u/let1 thisc (app* 'cell/new tdz) body-node)
                    body-node)]
    {:code (site [:call fname (first (:span n))]
                 (u/lam (cond-> [(if arrow? (gen "athis" n) this-sym) args-sym]
                          nt? (conj nt-sym))
                        body-node)),
     :arity (count params),
     :nt? nt?}))


(defn- function-value
  "The `js/make-function` node of function-like node `n`, named `fname`
   (a string). `scope-n` is the node that owns the function's scope. `opts`
   as for `function-code`, with `:kind` overriding the function kind."
  ([ctx n scope-n fname] (function-value ctx n scope-n fname {}))
  ([ctx n scope-n fname opts]
   (let [arrow? (= "arrowFunctionDeclaration" (:rule n))
         s (scope-of ctx scope-n)
         {:keys [code arity nt?]} (function-code ctx n scope-n fname opts)
         args [(u/lit (jstr fname))
               (u/lit arity)
               (u/lit (if arrow? :lexical :strict))
               (u/lit (or (:kind opts)
                          (cond
                            arrow? :arrow
                            (= "generatorMethod" (:rule n)) :method
                            (contains? #{"getAccessor" "setAccessor"} (:rule n))
                            :accessor
                            :else :normal)))
               code]
         value (if nt?
                 (apply app* 'js/make-function-nt (conj args (u/lit true)))
                 (apply app* 'js/make-function args))]
     (if-let [self (:self s)]
       (let [cell-sym (symbol self)]
         (u/app (u/lam [cell-sym]
                       (u/then (app* 'cell/set! (u/v cell-sym) value)
                               (app* 'cell/get (u/v cell-sym))))
                (app* 'cell/new undefined)))
       value))))


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
      (syntax! n "Invalid left-hand side in assignment")))


(defn- one-for
  "The unit of `++`/`--` for numeric value node `old`."
  [old]
  (u/if-node (app* '= (app* 'js/kind-of old) (u/lit :bigint))
             (u/lit 1)
             (u/lit (cbor/float64 1.0))))


(defn- lower-this-expr
  "The node reading `this`: the cell-backed binding of a derived
   constructor (and the arrows inside it), checked for its TDZ, else the
   value-backed parameter."
  [ctx]
  (if-let [thisc (:this-cell ctx)]
    (app* 'js/lexical-get (u/v thisc) (u/lit (jstr "this")))
    (u/v (:this ctx))))


(defn- nt-node
  "The node reading `new.target`."
  [ctx]
  (let [nt (:nt ctx)]
    (if (or (nil? nt) (= :undefined nt)) undefined (u/v nt))))


(defn- home-node
  [ctx]
  (u/v (:home ctx)))


(defn- super-expr?
  "True when `n` is the bare `super` (the pinned grammar parses it as an
   identifierName)."
  [ctx n]
  (let [ks (kids ctx n)]
    (and (= 1 (count ks))
         (p/rule? (first ks) "identifierName")
         (= "super" (text-of ctx (first ks))))))


(defn- private-sym
  [ctx n nm]
  (or (get (:privates ctx) nm)
      (syntax! n (str "Private field '" nm
                      "' must be declared in an enclosing class"))))


(defn- member-ref
  "How member expression `n` is reached: `{:kind :value :obj :key}`, or
   `{:kind :private :obj :pn}` for `o.#x`, or `{:kind :super :key}` for
   `super.x` and `super[e]`. With `raw?` an index key is left unconverted."
  [ctx n raw?]
  (let [ks (kids ctx n)
        obj (first ks)
        dot? (boolean (some #(p/token? % ".") ks))]
    (cond
      (and dot? (some #(p/token? % "#") ks) (p/rule? (peek ks) "identifierName"))
      (do (when (some #(p/token? % "?.") ks) (refuse! n "optional chaining" :T4))
          {:kind :private,
           :obj obj,
           :pn (u/v (private-sym ctx n (str "#" (text-of ctx (peek ks)))))})
      (super-expr? ctx obj)
      (do (when (some #(p/token? % "?.") ks) (refuse! n "optional chaining" :T4))
          (when (some #(p/token? % "!") ks) (refuse! n "type syntax" :T8))
          {:kind :super,
           :key (if (and dot? (p/rule? (peek ks) "identifierName"))
                  (u/lit (jstr (text-of ctx (peek ks))))
                  (let [k (lower-seq ctx (nth ks (- (count ks) 2)))]
                    (if raw? k (app* 'js/to-property-key k))))})
      :else (let [[o k] (member-parts ctx n raw?)]
              {:kind :value, :obj o, :key k}))))


(defn- lower-member-read
  [ctx n]
  (let [ref (member-ref ctx n false)]
    (case (:kind ref)
      :value (app* 'js/get-value (lower-expr ctx (:obj ref)) (:key ref))
      :private (app* 'js/priv-get (lower-expr ctx (:obj ref)) (:pn ref))
      :super (app* 'js/super-get (home-node ctx) (:key ref) (lower-this-expr ctx)))))


(defn- lower-member-update
  "`++o.p` and friends: receiver and key evaluated once, the key converted
   once before the read."
  [ctx n target op prefix?]
  (let [ref (member-ref ctx target false)
        o (gen "mo" n)
        kk (gen "mk" n)
        old (gen "o" n)
        nv (gen "n" n)
        f (if (= "++" op) 'js/add 'js/sub)
        [read write]
        (case (:kind ref)
          :value [(app* 'js/get-value (u/v o) (u/v kk))
                  (fn [v] (app* 'js/put-value (u/v o) (u/v kk) v))]
          :private [(app* 'js/priv-get (u/v o) (:pn ref))
                    (fn [v] (u/then (app* 'js/priv-set (u/v o) (:pn ref) v) v))]
          :super [(app* 'js/super-get (home-node ctx) (u/v kk) (u/v o))
                  (fn [v] (app* 'js/super-set (home-node ctx) (u/v kk) v (u/v o)))])]
    (u/let1 o
            (if (= :super (:kind ref))
              (lower-this-expr ctx)
              (lower-expr ctx (:obj ref)))
            (u/let1 kk
                    (or (:key ref) undefined)
                    (u/let1 old
                            (app* 'js/to-numeric read)
                            (u/let1 nv
                                    (app* f (u/v old) (one-for (u/v old)))
                                    (u/then (write (u/v nv))
                                            (u/v (if prefix? nv old)))))))))


(defn- lower-update
  "`++x`, `--x`, `x++`, `x--` on an identifier or a member."
  [ctx n target op prefix?]
  (if (member-expression? ctx target)
    (lower-member-update ctx n target op prefix?)
    (lower-name-update ctx n target op prefix?)))


(defn- lower-name-update
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


(defn- lower-member-assign
  "`o.p = v` (the key converted after the right-hand side) and `o.p op= v`
   (the key converted once, before the read)."
  [ctx n lhs op rhs]
  (let [ref (member-ref ctx lhs (= op "="))
        o (gen "mo" n)
        kk (gen "mk" n)
        v (gen "v" n)
        bin (get compound-ops op)]
    (when-not (or (= op "=") bin)
      (refuse! n (str "assignment operator " op) :T4))
    (case (:kind ref)
      :value
      (u/let1 o
              (lower-expr ctx (:obj ref))
              (u/let1 kk
                      (:key ref)
                      (if (= op "=")
                        (u/let1 v
                                (lower-expr ctx rhs)
                                (app* 'js/put-value
                                      (u/v o)
                                      (app* 'js/to-property-key (u/v kk))
                                      (u/v v)))
                        (u/let1 v
                                (app* (get binary-ops bin)
                                      (app* 'js/get-value (u/v o) (u/v kk))
                                      (lower-expr ctx rhs))
                                (app* 'js/put-value (u/v o) (u/v kk) (u/v v))))))
      :private
      (u/let1 o
              (lower-expr ctx (:obj ref))
              (u/let1 v
                      (if (= op "=")
                        (lower-expr ctx rhs)
                        (app* (get binary-ops bin)
                              (app* 'js/priv-get (u/v o) (:pn ref))
                              (lower-expr ctx rhs)))
                      (u/then (app* 'js/priv-set (u/v o) (:pn ref) (u/v v))
                              (u/v v))))
      :super
      (u/let1 o
              (lower-this-expr ctx)
              (u/let1 kk
                      (:key ref)
                      (u/let1 v
                              (if (= op "=")
                                (lower-expr ctx rhs)
                                (app* (get binary-ops bin)
                                      (app* 'js/super-get (home-node ctx)
                                            (u/v kk) (u/v o))
                                      (lower-expr ctx rhs)))
                              (app* 'js/super-set
                                    (home-node ctx)
                                    (if (= op "=")
                                      (app* 'js/to-property-key (u/v kk))
                                      (u/v kk))
                                    (u/v v)
                                    (u/v o))))))))


(defn- lower-assign
  [ctx n lhs op rhs]
  (if (member-expression? ctx lhs)
    (lower-member-assign ctx n lhs op rhs)
    (lower-name-assign ctx n lhs op rhs)))


(defn- lower-name-assign
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
    (reduce (fn [out a]
              (let [e (first (rules ctx a "singleExpression"))]
                (if (p/has-token? (:pk ctx) a "...")
                  (app* 'js/args-spread out (lower-expr ctx e))
                  (app* 'js/vconj out (if e (lower-expr ctx e)
                                          (read-name ctx (text-of ctx a)))))))
            (u/lit []) args)))


(defn- member-parts
  "`[object-node key-node]` of a member read `n` (dot or index form). With
   `raw?` an index key is left unconverted (plain `=` converts it after the
   right-hand side)."
  ([ctx n] (member-parts ctx n false))
  ([ctx n raw?]
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
       (let [k (lower-seq ctx (nth ks (- (count ks) 2)))]
         [obj (if raw? k (app* 'js/to-property-key k))])))))


(defn- member-expression?
  [ctx n]
  (let [ks (kids ctx n)]
    (and (< 2 (count ks))
         (or (p/rule? (first ks) "singleExpression")
             (p/rule? (first ks) "newOperand"))
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


(defn- lower-super-call
  "`super(args)` in a derived constructor, or an arrow inside it."
  [ctx args]
  (app* 'js/super-call
        (app* 'cell/get (u/v (:class-cell ctx)))
        (nt-node ctx)
        (u/v (:this-cell ctx))
        args))


(defn- lower-call
  [ctx n]
  (let [ks (kids ctx n)
        callee (first ks)
        args (lower-args ctx (second ks))]
    (if (super-expr? ctx callee)
      (lower-super-call ctx args)
      (do
        (refuse-global! ctx callee "eval")
        (if (member-expression? ctx callee)
          (let [o (gen "o" n)
                ref (member-ref ctx callee false)]
            (case (:kind ref)
              :value (u/let1 o
                             (lower-expr ctx (:obj ref))
                             (app* 'js/call
                                   (app* 'js/get-value (u/v o) (:key ref))
                                   (u/v o)
                                   args))
              :private (u/let1 o
                               (lower-expr ctx (:obj ref))
                               (app* 'js/call
                                     (app* 'js/priv-get (u/v o) (:pn ref))
                                     (u/v o)
                                     args))
              :super (u/let1 o
                             (lower-this-expr ctx)
                             (app* 'js/call
                                   (app* 'js/super-get (home-node ctx)
                                         (:key ref) (u/v o))
                                   (u/v o)
                                   args))))
          (app* 'js/call (lower-expr ctx callee) undefined args))))))


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


(defn- lower-delete
  [ctx n operand]
  (cond
    (and (member-expression? ctx operand)
         (super-expr? ctx (first (kids ctx operand))))
    (app* 'js/throw-reference-error
          (u/lit (jstr "Unsupported reference to 'super'")))
    (member-expression? ctx operand)
    (let [[obj key] (member-parts ctx operand)]
      (app* 'js/delete-value (lower-expr ctx obj) key))
    (simple-name ctx operand)
    (syntax! n "Delete of an unqualified identifier in strict mode.")
    :else (u/then (lower-expr ctx operand) (u/lit true))))


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
    "delete" (lower-delete ctx n operand)
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
      "??" (let [t (gen "nullish" n)]
             (u/let1 t (lower-expr ctx a)
                     (u/if-node (app* 'js/strict-eq (u/v t) undefined)
                                (lower-expr ctx b)
                                (u/if-node (app* 'js/strict-eq (u/v t) (u/lit :js/null))
                                           (lower-expr ctx b)
                                           (u/v t)))))
      "?." (let [t (gen "optional" n)
                 key (if-let [nm (simple-name ctx b)]
                       (u/lit (jstr nm))
                       (app* 'js/to-property-key (lower-expr ctx b)))]
             (u/let1 t (lower-expr ctx a)
                     (u/if-node (app* 'js/strict-eq (u/v t) undefined)
                                undefined
                                (u/if-node (app* 'js/strict-eq (u/v t) (u/lit :js/null))
                                           undefined
                                           (app* 'js/get-value (u/v t) key)))))
      "instanceof" (app* 'js/instanceof (lower-expr ctx a) (lower-expr ctx b))
      "in" (app* 'js/in-op (lower-expr ctx a) (lower-expr ctx b))
      (if-let [f (get binary-ops op)]
        (app* f (lower-expr ctx a) (lower-expr ctx b))
        (default-arm n)))))


(defn- lower-array
  [ctx k]
  (let [el (first (rules ctx k "elementList"))
        items (loop [cs (kids ctx el), out [], prev? false]
                (if-let [c (first cs)]
                  (cond
                    (p/token? c ",")
                    (recur (rest cs) (if prev? out (conj out [:hole nil])) false)
                    (p/rule? c "arrayElement")
                    (let [e (first (rules ctx c "singleExpression"))
                          item [(if (p/has-token? (:pk ctx) c "...") :spread :value)
                                (if e
                                  (lower-expr ctx e)
                                  (read-name ctx (text-of ctx c)))]]
                      (recur (rest cs) (conj out item)
                             (not (some #(p/token? % ",") (kids ctx c)))))
                    :else (recur (rest cs) out prev?))
                  out))]
    (app* 'js/new-array
          (reduce (fn [out [kind value]]
                    (case kind
                      :hole (app* 'js/vconj out (u/lit :js/hole))
                      :spread (app* 'js/args-spread out value)
                      (app* 'js/vconj out value)))
                  (u/lit []) items))))


(defn- property-key
  "`{:key node :hint name-or-nil :proto? bool}` of `propertyName` `pn`."
  [ctx pn]
  (let [c (first (kids ctx pn))]
    (cond
      (p/token? c "[")
      {:key (app* 'js/to-property-key
                  (lower-expr ctx (first (rules ctx pn "singleExpression"))))}
      (p/rule? c "identifierName")
      (let [t (text-of ctx c)]
        {:key (u/lit (jstr t)), :hint t, :proto? (= t "__proto__")})
      (p/token? c)
      (let [lit (string-literal pn (:text c))]
        {:key (u/lit lit),
         :hint (:js/str lit),
         :proto? (= (:js/str lit) "__proto__")})
      :else {:key (app* 'js/to-property-key
                        (number-literal c (text-of ctx c)))})))


(defn- lower-object-member
  [ctx o pa]
  (let [c (first (kids ctx pa))]
    (cond
      (p/rule? c "propertyName")
      (let [{:keys [key hint proto?]} (property-key ctx c)
            sep (second (kids ctx pa))
            v (nth (kids ctx pa) 2)]
        (when (p/token? sep "=") (refuse! pa "destructuring" :T4))
        (if proto?
          (app* 'js/literal-proto! (u/v o) (lower-expr ctx v))
          (app* 'js/create-data-prop!
                (u/v o)
                key
                (if hint (lower-named ctx v hint) (lower-expr ctx v)))))
      (p/token? c "[")
      (app* 'js/create-data-prop!
            (u/v o)
            (app* 'js/to-property-key
                  (lower-expr ctx (first (rules ctx pa "singleExpression"))))
            (lower-expr ctx (nth (kids ctx pa) 4)))
      (p/rule? c "identifierOrKeyWord")
      (let [t (text-of ctx c)]
        (app* 'js/create-data-prop! (u/v o) (u/lit (jstr t)) (read-name ctx t)))
      (p/rule? c "restParameter") (refuse! pa "destructuring" :T4)
      (p/rule? c "generatorMethod")
      (let [{:keys [key hint]} (property-key ctx (first (rules ctx c "propertyName")))]
        (app* 'js/create-data-prop!
              (u/v o)
              key
              (function-value ctx c c (or hint "") {:home o})))
      (or (p/rule? c "getAccessor") (p/rule? c "setAccessor"))
      (let [get? (p/rule? c "getAccessor")
            half (first (rules ctx c (if get? "getter" "setter")))
            cen (first (rules ctx half "classElementName"))]
        (when-not (first (rules ctx cen "propertyName"))
          (refuse! cen "private name" :T3))
        (let [{:keys [key hint]} (property-key ctx (first (rules ctx cen "propertyName")))]
          (app* 'js/define-accessor-half!
                (u/v o)
                key
                (u/lit (if get? :get :set))
                (function-value ctx c c
                                (str (if get? "get " "set ") (or hint ""))
                                {:home o})
                (u/lit true))))
      :else (refuse! pa "spread property" :T4))))


(defn- lower-object
  [ctx k]
  (let [o (gen "obj" k)]
    (u/let1 o
            (app* 'js/new-object)
            (u/seq-nodes
              (conj (mapv #(lower-object-member ctx o %)
                          (rules ctx k "propertyAssignment"))
                    (u/v o))))))


;; =============================================================================
;; Classes
;; =============================================================================

(defn- class-key
  "`{:key node :hint name-or-nil :computed? bool :private? bool}` of
   `classElementName` `cen`: a private name is the variable of its
   per-class private-name cell."
  [ctx cen]
  (let [c (first (kids ctx cen))]
    (if (p/rule? c "privateIdentifier")
      (let [nm (str "#" (text-of ctx (second (kids ctx c))))]
        {:key (u/v (private-sym ctx cen nm)), :hint nm, :private? true})
      (assoc (property-key ctx c)
             :computed? (p/token? (first (kids ctx c)) "[")))))


(defn- element-ctx
  "`ctx` for code running as a class element whose scope node is `scope-n`:
   `this` is `this-sym`, the home object is `home`, and there is no
   enclosing function's `new.target` or derived `this` cell."
  [ctx cx scope-n this-sym static?]
  (assoc ctx
         :scope (:id scope-n)
         :this this-sym
         :this-cell nil
         :nt :undefined
         :home (if static? (:F cx) (:proto cx))
         :class-cell (:Fc cx)
         :ret nil
         :loop nil
         :labels {}
         :pending-labels nil))


(defn- lower-class-field
  [ctx cx m kinfo static?]
  (when (first (rules ctx m "typeAnnotation")) (refuse! m "type syntax" :T8))
  (when (token-kid ctx m "?") (refuse! m "type syntax" :T8))
  (let [init (first (rules ctx m "initializer"))
        expr (when init (first (rules ctx init "singleExpression")))
        hint (when-not (:computed? kinfo) (:hint kinfo))
        this-sym (when init (gen "this" init))
        ictx (when init (element-ctx ctx cx init this-sym static?))
        value (when init
                (if hint (lower-named ictx expr hint) (lower-expr ictx expr)))
        site-of (fn [lam] (site [:call (or hint "") (first (:span m))] lam))]
    (if static?
      (let [computed? (= :application (:type (:key kinfo)))
            ksym (gen "fk" m)
            key (if computed? (u/v ksym) (:key kinfo))]
        {:steps (if computed? [[:let ksym (:key kinfo)]] []),
         :statics [(site-of
                     (u/lam [(or this-sym (gen "this" m))]
                            (app* 'js/define-field!
                                  (u/v (or this-sym (gen "this" m)))
                                  key
                                  (or value undefined))))]})
      {:steps [[:do (app* 'js/add-field!
                          (u/v (:F cx))
                          (:key kinfo)
                          (if init (site-of (u/lam [this-sym] value)) undefined))]],
       :statics []})))


(defn- lower-class-member
  "The `{:steps :statics}` of one `propertyMemberDeclaration`."
  [ctx cx m]
  (let [pmb (first (rules ctx m "propertyMemberBase"))
        _ (check-consumed! ctx pmb)
        _ (when (token-kid ctx pmb "async") (refuse! m "async function" :T6))
        _ (when (token-kid ctx pmb "readonly") (refuse! m "type syntax" :T8))
        static? (boolean (token-kid ctx pmb "static"))
        acc (or (first (rules ctx m "getAccessor"))
                (first (rules ctx m "setAccessor")))
        half (when acc
               (or (first (rules ctx acc "getter")) (first (rules ctx acc "setter"))))
        cen (first (rules ctx (or half m) "classElementName"))
        kinfo (class-key ctx cen)
        hint (or (:hint kinfo) "")
        F (u/v (:F cx))
        target (u/v (if static? (:F cx) (:proto cx)))
        home (if static? (:F cx) (:proto cx))
        install
        (fn [kind fnode]
          (cond
            (and (:private? kinfo) static?)
            (app* 'js/def-static-private! F (:key kinfo) (u/lit kind) fnode)
            (:private? kinfo)
            (app* 'js/add-priv-method! F (:key kinfo) (u/lit kind) fnode)
            (= kind :method)
            (app* 'js/def-class-method! target (:key kinfo) fnode)
            :else
            (app* 'js/def-class-accessor! target (:key kinfo) (u/lit kind) fnode)))]
    (cond
      acc
      (let [kind (if (p/rule? acc "getAccessor") :get :set)]
        {:steps [[:do (install kind
                               (function-value ctx acc acc
                                               (str (if (= :get kind) "get " "set ")
                                                    hint)
                                               {:kind :accessor, :home home}))]],
         :statics []})
      (first (rules ctx m "callSignature"))
      {:steps [[:do (install :method
                             (function-value ctx m m hint
                                             {:kind :method, :home home}))]],
       :statics []}
      :else (lower-class-field ctx cx m kinfo static?))))


(defn- lower-static-block
  [ctx cx sb]
  (let [s (scope-of ctx sb)
        this-sym (gen "this" sb)
        sctx (element-ctx ctx cx sb this-sym true)
        stmts (statements-of sctx sb)]
    {:steps [],
     :statics [(site [:call "static" (first (:span sb))]
                     (u/lam [this-sym]
                            (bind-cells (block-pairs s)
                                        (seq-of (lower-stmts sctx stmts)))))]}))


(defn- lower-class-element
  [ctx cx ce]
  (let [c (first (kids ctx ce))]
    (when (first (rules ctx ce "decoratorList"))
      (refuse! ce "decorator" :never))
    (case (:rule c)
      "staticBlock" (lower-static-block ctx cx c)
      "propertyMemberDeclaration"
      (do (when (first (rules ctx c "abstractDeclaration"))
            (refuse! c "type syntax" :T8))
          (lower-class-member ctx cx c))
      "indexMemberDeclaration" (refuse! c "type syntax" :T8)
      (default-arm c))))


(defn- default-constructor-code
  [n name-str derived? Fc]
  (let [this-sym (gen "this" n)
        args-sym (gen "args" n)
        nt-sym (gen "nt" n)
        thisc (gen "thisc" n)]
    (site [:call name-str (first (:span n))]
          (u/lam [this-sym args-sym nt-sym]
                 (if derived?
                   (u/let1 thisc
                           (app* 'cell/new tdz)
                           (u/then (app* 'js/super-call
                                         (app* 'cell/get (u/v Fc))
                                         (u/v nt-sym)
                                         (u/v thisc)
                                         (u/v args-sym))
                                   (app* 'js/this-after-super (u/v thisc))))
                   (u/v this-sym))))))


(defn- lower-class
  "A class declaration or expression `n`: the constructor cell with its
   elements installed in source order, then the static initializers, with
   the class's inner name in TDZ through the heritage and the elements."
  [ctx n hint]
  (when (token-kid ctx n "export") (refuse! n "module syntax" :T7))
  (when (token-kid ctx n "abstract") (refuse! n "type syntax" :T8))
  (when (first (rules ctx n "decoratorList")) (refuse! n "decorator" :never))
  (check-consumed! ctx n)
  (let [ident (first (rules ctx n "identifier"))
        cname (when ident (text-of ctx ident))
        name-str (or cname hint "")
        heritage (first (rules ctx n "classHeritage"))
        _ (when heritage (check-consumed! ctx heritage))
        ext (when heritage (first (rules ctx heritage "classExtendsClause")))
        _ (when (and ext (first (rules ctx ext "typeGeneric")))
            (refuse! n "generic class heritage" :T8))
        derived? (boolean ext)
        tail (first (rules ctx n "classTail"))
        cs (scope-of ctx n)
        pn-syms (into {}
                      (map-indexed (fn [i nm] [nm (symbol (str "%pn" (:id n) "_" i))]))
                      (sort (keys (:privates cs))))
        cctx (assoc ctx
                    :scope (:id n)
                    :privates (merge (:privates ctx) pn-syms))
        F (gen "F" n)
        Fc (gen "Fc" n)
        proto (gen "proto" n)
        h (gen "h" n)
        cx {:F F, :proto proto, :Fc Fc}
        elements (rules ctx tail "classElement")
        ctor-ce (first (filter #(p/rule? (first (kids ctx %)) "constructorDeclaration")
                               elements))
        ctor-n (when ctor-ce (first (kids ctx ctor-ce)))
        {ccode :code, carity :arity}
        (if ctor-n
          (function-code cctx ctor-n ctor-n name-str
                         {:ctor (if derived? :derived :base),
                          :home proto,
                          :class-cell Fc})
          {:code (default-constructor-code n name-str derived? Fc),
           :arity 0})
        results (mapv #(lower-class-element cctx cx %)
                      (remove #(= % ctor-ce) elements))
        statics (vec (mapcat :statics results))
        steps (vec (concat [[:do (app* 'cell/set! (u/v Fc) (u/v F))]]
                           (mapcat :steps results)
                           (when cname
                             [[:do (app* 'cell/set! (u/v (symbol cname)) (u/v F))]])
                           [[:do (app* 'js/static-init! (u/v F) (build-vector statics))]]))
        body (reduce (fn [acc [kind a b]]
                       (case kind
                         :do (u/then a acc)
                         :let (u/let1 a b acc)))
                     (u/v F)
                     (reverse steps))
        bindings (vec (concat
                        (map (fn [[nm sym]]
                               [sym (app* 'js/private-name (u/lit (jstr nm)))])
                             pn-syms)
                        [[Fc (app* 'cell/new undefined)]
                         [h (app* 'js/class-heritage
                                  (if ext
                                    (lower-expr cctx (first (rules ctx ext "singleExpression")))
                                    (u/lit :js/none)))]
                         [proto (app* 'js/make-object
                                      (app* 'get (u/v h) (u/lit :proto-parent)))]
                         [F (app* 'js/make-class
                                  (u/lit (jstr name-str))
                                  (u/lit carity)
                                  (u/lit derived?)
                                  (app* 'get (u/v h) (u/lit :ctor-parent))
                                  (u/v proto)
                                  ccode)]]))
        inner (reduce (fn [acc [sym node]] (u/let1 sym node acc))
                      body
                      (reverse bindings))]
    (if cname
      (bind-cells [[cname tdz]] inner)
      inner)))


(defn- lower-primary
  [ctx k]
  (cond
    (p/rule? k "arrayLiteral") (lower-array ctx k)
    (p/rule? k "objectLiteral") (lower-object ctx k)
    (p/token? k "this") (lower-this-expr ctx)
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
      (= c 1) (lower-primary ctx k0)
      (p/token? k0 "class") (lower-class ctx n nil)
      (p/rule? k0 "typeArguments") (refuse! n "type syntax" :T8)
      (and (= c 3) (p/token? k0 "(")) (lower-seq ctx k1)
      (and (p/token? k0 "new") (= c 3) (p/token? k1 "."))
      (nt-node ctx)
      (and (= c 3) (p/rule? k0 "privateIdentifier") (p/token? k1 "in"))
      (app* 'js/priv-has
            (lower-expr ctx (nth ks 2))
            (u/v (private-sym ctx k0 (str "#" (text-of ctx (second (kids ctx k0)))))))
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
      (member-expression? ctx n) (lower-member-read ctx n)
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
    (cond
      (and (= 1 (count ks)) (p/rule? (first ks) "anonymousFunction"))
      (let [f (first (kids ctx (first ks)))]
        (if (p/rule? f "functionDeclaration")
          (lower-expr ctx n)
          (function-node ctx (first ks) nm)))
      (and (p/token? (first ks) "class")
           (nil? (first (rules ctx n "identifier"))))
      (lower-class ctx n nm)
      :else (lower-expr ctx n))))


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


(defn- lower-enum
  "Lower a runtime enum object with ordered forward and numeric reverse
   mappings. `const enum` intentionally follows the same object path under
   the pinned preserveConstEnums/isolatedModules profile."
  [ctx n]
  (let [id (first (rules ctx n "identifier"))
        nm (text-of ctx id)
        body (first (rules ctx n "enumBody"))
        members (when body (rules ctx body "enumMemberList"))
        members (if-let [lst (first members)] (rules ctx lst "enumMember") [])
        init (init-name nm (app* 'js/make-object))]
    (letfn [(emit-member
              [i previous]
              (if (= i (count members))
                undefined
                (let [member (nth members i)
                      pn (first (rules ctx member "propertyName"))
                      {:keys [key hint]} (property-key ctx pn)
                      val-node (first (rules ctx member "singleExpression"))
                      value (gen "enumv" member)
                      value-expr (if val-node
                                   (if hint (lower-named ctx val-node hint)
                                       (lower-expr ctx val-node))
                                   (if previous
                                     (app* 'js/add (u/v previous)
                                           (u/lit (cbor/float64 1.0)))
                                     (u/lit (cbor/float64 0.0))))
                      forward (app* 'js/create-data-prop!
                                    (read-name ctx nm) key (u/v value))
                      reverse (app* 'js/create-data-prop!
                                    (read-name ctx nm)
                                    (app* 'js/to-string (u/v value))
                                    (app* 'js/to-string key))]
                  (u/let1 value value-expr
                          (u/then forward
                                  (u/then
                                    (u/if-node
                                      (app* '= (app* 'js/typeof (u/v value))
                                            (u/lit (jstr "number")))
                                      reverse
                                      undefined)
                                    (emit-member (inc i) value)))))))]
      (u/then init (emit-member 0 nil)))))


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


(defn- lower-for-in
  "`for (head in subject) body`: each turn pulls the next key from the
   `js/for-in-*` walk; `let`/`const` heads get fresh cells per turn."
  [ctx n labels]
  (let [pk (:pk ctx)
        body (last (rules ctx n "statement"))
        subject (first (rules ctx n "expressionSequence"))
        vd (first (rules ctx n "variableDeclaration"))
        kind (scope/declaration-kind pk (first (rules ctx n "varModifier")))
        lexical? (contains? #{:let :const} kind)
        sctx (if lexical? (assoc ctx :scope (:id n)) ctx)
        it (gen "it" n)
        k (gen "k" n)
        w (gen "w" n)
        {:keys [brk? brk] b :body} (loop-parts ctx n labels sctx body)
        head (if vd
               (let [nm (scope/declaration-name pk vd)]
                 (when-not nm (refuse! vd "destructuring" :T4))
                 (if lexical?
                   (init-name nm (u/v k))
                   (assign-name ctx nm k)))
               (let [target (first (rules ctx n "singleExpression"))]
                 (if (member-expression? ctx target)
                   (let [ref (member-ref ctx target false)]
                     (case (:kind ref)
                       :value (app* 'js/put-value
                                    (lower-expr ctx (:obj ref))
                                    (:key ref)
                                    (u/v k))
                       :private (app* 'js/priv-set
                                      (lower-expr ctx (:obj ref))
                                      (:pn ref)
                                      (u/v k))
                       :super (app* 'js/super-set
                                    (home-node ctx)
                                    (:key ref)
                                    (u/v k)
                                    (lower-this-expr ctx))))
                   (assign-name ctx (target-name! ctx target) k))))
        turn (u/then head b)
        turn (if lexical?
               (bind-cells (block-pairs (scope-of ctx n)) turn)
               turn)]
    (wrap-escape
      brk?
      brk
      (u/let1 it
              (app* 'js/for-in-start (lower-seq ctx subject))
              (spin w
                    []
                    (u/let1 k
                            (app* 'js/for-in-next (u/v it))
                            (u/if-node (app* '= (u/v k) (u/lit :js/done))
                                       undefined
                                       (u/then turn (u/app (u/v w) (u/v w)))))
                    [])))))


(defn- lower-iteration
  [ctx n labels]
  (let [ks (kids ctx n)
        k0 (first ks)]
    (cond
      (p/token? k0 "do") (lower-do ctx n labels)
      (p/token? k0 "while") (lower-while ctx n labels)
      (token-kid ctx n "in") (lower-for-in ctx n labels)
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
      "enumDeclaration" (lower-enum ctx n)
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
      "classDeclaration" (let [nm (text-of ctx (first (rules ctx n "identifier")))]
                           (init-name nm (lower-class ctx n nm)))
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
     :class nil,
     :this-cell nil,
     :nt :undefined,
     :home nil,
     :class-cell nil,
     :privates {}}))


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


(defn lower-unit
  "One separately scoped module body as a function of its shared global
   receiver. Keeping this seam lets a host compose independently parsed
   units without concatenating source or reinitializing the runtime."
  [packet]
  (let [ctx (context packet)]
    (u/lam [(:this ctx)] (module-body ctx))))


(defn lower-units
  "A complete program for multiple separately parsed units. The runtime is
   installed and initialized once; each unit receives its own lexical body
   while sharing the same global object and output stream."
  ([packets] (lower-units packets {}))
  ([packets {:keys [prelude], :or {prelude :bundled}}]
   (let [units (mapv lower-unit packets)
         main (names/canonicalize
                (app* 'js/run-main
                      (u/lam ['this]
                             (if (seq units)
                               (u/seq-nodes
                                 (mapv (fn [unit] (u/app unit (u/v 'this))) units))
                               (u/lit :js/undefined)))))]
     (u/mark-tails
       (case prelude
         :bundled (u/then prelude/uast main)
         :linked (u/seq-nodes [(u/sexp->uast (list 'require
                                                   (list 'quote 'yang.call)))
                               (u/sexp->uast (list 'require
                                                   (list 'quote prelude/module-name)))
                               (app* 'js/init!)
                               main]))))))


(defn lower-packet
  "The complete program for one ok packet: the module body run by
   `js/run-main`, generated names canonicalized, tail calls marked. Its
   value is `{:js/out [...] :js/uncaught nil-or-snapshot}`.

   `:prelude` selects how the runtime profile arrives: `:bundled` (the
   default) carries the prelude tree ahead of the body; `:linked` requires
   the modules `yang.call` and `js` from the task's link pair, then allocates
   the task's runtime state with `(js/init!)`."
  ([packet] (lower-packet packet {}))
  ([packet {:keys [prelude], :or {prelude :bundled}}]
   (let [main (names/canonicalize (app* 'js/run-main (lower-unit packet)))]
     (u/mark-tails
       (case prelude
         :bundled (u/then prelude/uast main)
         :linked (u/seq-nodes [(u/sexp->uast (list 'require
                                                   (list 'quote
                                                         'yang.call)))
                               (u/sexp->uast (list 'require
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
