(ns yang.python.antlr.lower
  "The lowering interpreter: CST packets in, Universal AST program batches
   out (docs/design/yang.antlr.md §3.5, §8.5). Portable.

   Per packet: validate the tree, analyze scopes (`yang.python.antlr.scope`),
   then lower with one `case` arm per grammar rule. A rule the spike does not
   support fails with `:yang.python.antlr/unsupported` naming the rule and
   construct; a rule with no arm fails with
   `:yang.python.antlr/unhandled-rule`. Nothing is silently dropped.

   Decided lowering rules (collab/1790795118566 brief):
   - Every function-local binding and every parameter is a cell, allocated
     at function entry: parameters initialised, other locals `:py/unbound`.
     Reads are `py/local-get` (unbound -> UnboundLocalError), writes
     `cell/set!`. Module names and `global` names live in the module's
     namespace dict, `%globals` (`py/global-get`, a miss is NameError;
     `py/global-set`); `yin/def` is reserved for the prelude and builtins.
   - A `def` or `lambda` is a function object (`py/make-function`) whose
     code takes one argument vector; every call is `(py/call f [args])` or,
     with keywords, `(py/call-kw f [args] [[name value] ...])`; the callee
     binds positional, keyword, default, `*args` and `**kwargs`.
   - Control flow is continuations: a function with a `return` runs its body
     under `py/call-ec`; loops are self-applied lambdas whose `break` and
     `continue` are escapes; `try` installs a handler continuation through
     `py/try`, and `raise` invokes the innermost one. `finally` is a
     `py/try-finally` frame that every exit runs; `with` is `py/with`, the
     language reference's expansion over the same two forms.
   - Tuple, list and starred targets unpack with `py/unpack` /
     `py/unpack-star`; comprehensions run in their own scope with the first
     iterable evaluated outside it; a generator expression is accepted only
     as the sole argument of a consuming builtin (list, tuple, set, sum,
     any, all), where it is a list.
   - A function whose body yields is a generator function: its code binds
     the arguments and allocates the cells, then returns
     `(py/make-generator name (fn [%gen] body))`; each `yield v` is
     `(py/yield %gen v)`. The `:gen` binder is reset in every nested scope,
     so a `yield` at module or class level, or in a comprehension's own
     scope, is a syntax error.
   - Operators, truthiness, equality, objects and exceptions are prelude
     calls (`yang.python.antlr.prelude`).

   Generated names come from CST node ids (`%brk17`, `%w17`, ...): unique per
   node, deterministic, and `%` cannot begin a Python identifier. Guest
   locals keep their Python names in the lexical env and lowered code names
   only `py/`, `py.b/`, `cell/`, `%` names and non-identifier primitives
   (`=`, `+`), so no guest name can shadow a primitive or prelude name."
  (:require
    [clojure.string :as str]
    [yang.antlr.packet :as p]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.scope :as scope]
    [yang.python.antlr.uast :as u]
    [yang.stage :as stage]
    [yin.vm.encoder :as encoder]))


;; =============================================================================
;; Diagnostics
;; =============================================================================

(defn- unsupported!
  [n construct]
  (throw (ex-info (str "Unsupported Python construct: " construct)
                  {:yang.python.antlr/diagnostic :yang.python.antlr/unsupported,
                   :rule (:rule n),
                   :construct construct,
                   :span (:span n)})))


(defn- syntax!
  [n message]
  (throw (ex-info message
                  {:yang.python.antlr/diagnostic :yang.python.antlr/syntax,
                   :rule (:rule n),
                   :span (:span n)})))


(def unsupported-rules
  "Grammar rules outside the phase-A subset, with the construct each names."
  {"single_input" "interactive input",
   "eval_input" "eval input",
   "decorator" "decorator",
   "decorators" "decorator",
   "decorated" "decorator",
   "async_funcdef" "async def",
   "async_stmt" "async statement",
   "annassign" "annotated assignment",
   "del_stmt" "del statement",
   "import_stmt" "import",
   "import_name" "import",
   "import_from" "import",
   "import_as_name" "import",
   "import_as_names" "import",
   "dotted_as_name" "import",
   "dotted_as_names" "import",
   "dotted_name" "import",
   "assert_stmt" "assert statement",
   "match_stmt" "match statement",
   "subject_expr" "match statement",
   "star_named_expressions" "match statement",
   "star_named_expression" "match statement",
   "case_block" "match statement",
   "guard" "match statement",
   "patterns" "match statement",
   "pattern" "match statement",
   "as_pattern" "match statement",
   "or_pattern" "match statement",
   "closed_pattern" "match statement",
   "literal_pattern" "match statement",
   "literal_expr" "match statement",
   "complex_number" "match statement",
   "signed_number" "match statement",
   "signed_real_number" "match statement",
   "real_number" "match statement",
   "imaginary_number" "match statement",
   "capture_pattern" "match statement",
   "pattern_capture_target" "match statement",
   "wildcard_pattern" "match statement",
   "value_pattern" "match statement",
   "attr" "match statement",
   "name_or_attr" "match statement",
   "group_pattern" "match statement",
   "sequence_pattern" "match statement",
   "open_sequence_pattern" "match statement",
   "maybe_sequence_pattern" "match statement",
   "maybe_star_pattern" "match statement",
   "star_pattern" "match statement",
   "mapping_pattern" "match statement",
   "items_pattern" "match statement",
   "key_value_pattern" "match statement",
   "double_star_pattern" "match statement",
   "class_pattern" "match statement",
   "positional_patterns" "match statement",
   "keyword_patterns" "match statement",
   "keyword_pattern" "match statement",
   "encoding_decl" "encoding declaration"})


(def consumed-rules
  "Rules read only by their parent's arm; they never reach `lower` alone."
  #{"parameters" "typedargslist" "tfpdef" "varargslist" "vfpdef"
    "augassign" "except_clause" "block" "comp_op" "trailer" "subscriptlist"
    "subscript_" "sliceop" "arglist" "argument" "testlist_comp"
    "dictorsetmaker" "strings" "comp_for" "comp_iter" "comp_if" "with_item"
    "yield_arg"})


(def handled-rules
  "Rules with their own arm in `lower`."
  #{"file_input" "stmt" "simple_stmts" "simple_stmt" "compound_stmt"
    "flow_stmt" "expr_stmt" "testlist_star_expr" "testlist" "exprlist"
    "pass_stmt" "break_stmt" "continue_stmt" "return_stmt" "raise_stmt"
    "global_stmt" "nonlocal_stmt" "if_stmt" "while_stmt" "for_stmt"
    "try_stmt" "with_stmt" "funcdef" "classdef" "test" "test_nocond"
    "lambdef" "lambdef_nocond" "or_test" "and_test" "not_test" "comparison"
    "expr" "star_expr" "atom_expr" "atom" "name" "yield_stmt" "yield_expr"})


(defn- default-arm
  [n]
  (if-let [construct (get unsupported-rules (:rule n))]
    (unsupported! n construct)
    (throw (ex-info (str "Unhandled grammar rule: " (:rule n))
                    {:yang.python.antlr/diagnostic
                     :yang.python.antlr/unhandled-rule,
                     :rule (:rule n),
                     :span (:span n)}))))


;; =============================================================================
;; Small helpers
;; =============================================================================

(defn- gen
  [prefix n]
  (symbol (str "%" prefix (:id n))))


(defn- kids
  [ctx n]
  (p/children (:pk ctx) n))


(defn- rules
  [ctx n rule-name]
  (p/child-rules (:pk ctx) n rule-name))


(defn- rule-kids
  [ctx n]
  (filterv p/rule? (kids ctx n)))


(defn- name-of
  "The identifier text of a `name` node."
  [ctx n]
  (:text (first (kids ctx n))))


(defn- site
  "`node` marked as a safepoint site of `kind` (`:loop`, `:call`). The
   mark is map metadata, so it never enters a canonical row: projection
   puts it in the frontend-metadata side table, where `yang.safepoint`
   finds it."
  [kind node]
  (vary-meta node assoc :yang/site kind))


(defn- app*
  [op-sym & args]
  (apply u/app (u/v op-sym) args))


(defn- build-vector
  [elems]
  (reduce (fn [acc e] (app* 'py/conj acc e)) (u/lit []) elems))


(defn- truthy
  [x]
  (app* 'py/truthy x))


(def ^:private none (u/lit :py/None))


(defn- contains-rule?
  "True when `rule-name` occurs below `n` without crossing any `stop` rule."
  [ctx n rule-name stop]
  (boolean
    (some (fn [k]
            (and (p/rule? k)
                 (or (= rule-name (:rule k))
                     (and (not (contains? stop (:rule k)))
                          (contains-rule? ctx k rule-name stop)))))
          (kids ctx n))))


(def ^:private scope-rules #{"funcdef" "lambdef" "lambdef_nocond" "classdef"})


(def ^:private loop-and-scope-rules
  (into scope-rules #{"while_stmt" "for_stmt"}))


;; =============================================================================
;; Literals
;; =============================================================================

(def ^:private max-exact-int
  "2^53: the prelude's integer range is [-2^53, 2^53], exact on every host."
  9007199254740992)


(defn- parse-radix
  [n digits radix]
  (reduce (fn [acc ch]
            (let [d (str/index-of "0123456789abcdef" (str/lower-case (str ch)))]
              (when (or (nil? d) (>= d radix))
                (unsupported! n "number literal"))
              (let [x (+ (* acc radix) d)]
                (when (> x max-exact-int)
                  (unsupported! n "integer literal beyond 2^53"))
                x)))
          0
          digits))


(defn- parse-number
  [n text]
  (let [t (str/replace text "_" "")
        lower (str/lower-case t)]
    (cond
      (str/ends-with? lower "j") (unsupported! n "imaginary literal")
      (str/starts-with? lower "0x") (parse-radix n (subs t 2) 16)
      (str/starts-with? lower "0o") (parse-radix n (subs t 2) 8)
      (str/starts-with? lower "0b") (parse-radix n (subs t 2) 2)
      ;; floats are tagged on every host (owner decision 3)
      (re-find #"[.eE]" t) {:py/float #?(:cljd (double/parse t)
                                         :clj (Double/parseDouble t)
                                         :cljs (js/parseFloat t))}
      ;; Python 3 reads 0755 as an error, not as octal or decimal
      (and (str/starts-with? t "0") (re-find #"[1-9]" t))
      (syntax! n (str "leading zeros in decimal integer literals are not "
                      "permitted; use an 0o prefix for octal integers"))
      :else (parse-radix n t 10))))


(defn- code-point->str
  [cp]
  #?(:cljd (let [sb (StringBuffer)] (.writeCharCode sb cp) (.toString sb))
     :clj (String. (Character/toChars (int cp)))
     :cljs (.fromCodePoint js/String cp)))


(defn- hex-value
  [n s]
  (when-not (re-matches #"[0-9a-fA-F]+" s)
    (syntax! n "malformed escape in string literal"))
  (let [cp (parse-radix n s 16)]
    (when (> cp 0x10FFFF)
      (syntax! n "illegal Unicode character in \\U escape"))
    cp))


(def ^:private simple-escapes
  {"\\" "\\", "'" "'", "\"" "\"", "n" "\n", "t" "\t", "r" "\r",
   "a" (code-point->str 7), "b" (code-point->str 8), "f" (code-point->str 12),
   "v" (code-point->str 11), "\n" ""})


(defn- unescape
  [n body]
  (let [len (count body)]
    (loop [i 0
           out []]
      (if (>= i len)
        (apply str out)
        (let [c (subs body i (inc i))]
          (if (and (= c "\\") (< (inc i) len))
            (let [e (subs body (inc i) (+ i 2))]
              (cond
                (contains? simple-escapes e)
                (recur (+ i 2) (conj out (get simple-escapes e)))
                (= e "x") (recur (+ i 4)
                                 (conj out (code-point->str
                                             (hex-value n (subs body (+ i 2) (min len (+ i 4)))))))
                (= e "u") (recur (+ i 6)
                                 (conj out (code-point->str
                                             (hex-value n (subs body (+ i 2) (min len (+ i 6)))))))
                (= e "U") (recur (+ i 10)
                                 (conj out (code-point->str
                                             (hex-value n (subs body (+ i 2) (min len (+ i 10)))))))
                (= e "N") (unsupported! n "named unicode escape")
                (re-matches #"[0-7]" e)
                (let [digits (re-find #"^[0-7]{1,3}" (subs body (inc i)))]
                  (recur (+ i 1 (count digits))
                         (conj out (code-point->str (parse-radix n digits 8)))))
                :else (recur (+ i 2) (conj out c e))))
            (recur (inc i) (conj out c))))))))


(defn- parse-string
  "The value of one STRING token: prefixes u and r only."
  [n text]
  (let [prefix (str/lower-case (re-find #"^[A-Za-z]*" text))
        quoted (subs text (count prefix))
        q3 (subs quoted 0 (min 3 (count quoted)))
        body (if (or (= q3 "'''") (= q3 "\"\"\""))
               (subs quoted 3 (- (count quoted) 3))
               (subs quoted 1 (dec (count quoted))))]
    (case prefix
      ("" "u") (unescape n body)
      "r" body
      (unsupported! n (if (str/includes? prefix "b")
                        "bytes literal"
                        "f-string")))))


;; =============================================================================
;; Names
;; =============================================================================

(defn- resolve-name
  [ctx name]
  (scope/resolve (:analysis ctx) (:scope ctx) name))


(def globals-sym
  "The module namespace dict: the module body's parameter, closed over by
   every function in the module (Python's `__globals__`)."
  '%globals)


(def globals-fn-sym
  "The module's `globals` builtin: a function object returning `%globals`,
   the module body's second parameter."
  '%globals-fn)


(defn- global-key
  [name]
  (u/lit {:py/str name}))


(defn- read-global
  "A module-level read at run time: the module dict, then, for a builtin
   name, the builtin. A present module key always wins, so
   `print(len([])); len = 1` reads the builtin first and the global after."
  [_n name]
  (cond
    (contains? prelude/builtin-names name)
    (app* 'py/global-or (u/v globals-sym) (global-key name)
          (u/v (get prelude/builtin-names name)))
    (= "globals" name)
    (app* 'py/global-or (u/v globals-sym) (global-key name) (u/v globals-fn-sym))
    :else (app* 'py/global-get (u/v globals-sym) (global-key name))))


(defn- read-name
  [ctx n name]
  (let [r (resolve-name ctx name)]
    (case (:kind r)
      :cell (app* 'py/local-get (u/v (symbol name)) (u/lit {:py/str name}))
      ;; a name the class body assigns: the class namespace at run time,
      ;; then globals and builtins, as Python's class-body lookup does
      :class-attr (app* 'py/class-ns-get (u/v (:class ctx)) (u/lit name)
                        (u/lam [] (read-global n name)))
      :global (read-global n name))))


(defn- builtin?
  "True when `name` in this scope is the builtin, not a guest binding."
  [ctx name]
  (let [r (resolve-name ctx name)]
    (and (= :global (:kind r)) (not (:declared? r)))))


(def ^:private consuming-builtins
  "Builtins that consume an iterable argument eagerly: a generator
   expression passed as their sole argument is indistinguishable from a
   list, so it is lowered as one. Any other generator expression waits for
   C2."
  #{"list" "tuple" "set" "sum" "any" "all"})


(defn- assign-name
  [ctx name value]
  (let [r (resolve-name ctx name)]
    (case (:kind r)
      :cell (app* 'cell/set! (u/v (symbol name)) value)
      :class-attr (app* 'py/setattr (u/v (:class ctx)) (u/lit name) value)
      :global (app* 'py/global-set (u/v globals-sym) (global-key name) value))))


(defn- unbind-name
  "Make `name` unbound again: a cell back to unbound, a module or class
   binding removed (quietly when already absent)."
  [ctx name]
  (let [r (resolve-name ctx name)]
    (case (:kind r)
      :cell (app* 'cell/set! (u/v (symbol name)) (u/lit :py/unbound))
      :class-attr (app* 'py/delattr-quiet (u/v (:class ctx)) (u/lit name))
      :global (app* 'py/global-del-quiet (u/v globals-sym) (global-key name)))))


;; =============================================================================
;; The dispatch: one arm per grammar rule
;; =============================================================================

(declare lower-block lower-call lower-comprehension)


(defn- elements
  "A host vector of display elements: each value conj'd, each `*iterable`
   spliced, left to right."
  [ctx elems]
  (reduce (fn [acc e]
            (if (p/rule? e "star_expr")
              (app* 'py/extend acc ((:lower ctx) ctx (last (kids ctx e))))
              (app* 'py/conj acc ((:lower ctx) ctx e))))
          (u/lit [])
          elems))


(defn- display
  "A list rule's value: its one element, or the tuple of its elements when
   it has several, a trailing comma or a starred element."
  [ctx n]
  (let [elems (rule-kids ctx n)
        single? (and (= 1 (count elems)) (not (p/has-token? (:pk ctx) n ",")))]
    (cond
      (and single? (p/rule? (first elems) "star_expr"))
      (syntax! n "can't use starred expression here")
      single? ((:lower ctx) ctx (first elems))
      :else (app* 'py/tuple (elements ctx elems)))))


(defn- fold-bool
  "`a or b or c` / `a and b and c`: short-circuit, returning an operand."
  [ctx n or?]
  (let [operands (rule-kids ctx n)]
    (reduce (fn [rest-node operand]
              (let [t (gen "t" operand)]
                (u/let1 t
                        ((:lower ctx) ctx operand)
                        (if or?
                          (u/if-node (truthy (u/v t)) (u/v t) rest-node)
                          (u/if-node (truthy (u/v t)) rest-node (u/v t))))))
            ((:lower ctx) ctx (peek operands))
            (rseq (pop operands)))))


(def ^:private binary-ops
  {"+" 'py/add, "-" 'py/sub, "*" 'py/mul, "/" 'py/truediv, "//" 'py/floordiv,
   "%" 'py/mod, "**" 'py/pow, "&" 'py/bitand, "|" 'py/bitor, "^" 'py/bitxor,
   "<<" 'py/lshift, ">>" 'py/rshift})


(def ^:private unary-ops
  {"-" 'py/neg, "+" 'py/pos, "~" 'py/invert})


(defn- lower-brace
  "`{...}`: an empty dict, a dict or set display, or a dict or set
   comprehension."
  [ctx n inner]
  (if (p/token? inner "}")
    (app* 'py/dict-from (u/lit []))
    (let [parts (kids ctx inner)]
      (cond
        (some #(p/token? % "**") parts) (unsupported! n "dict unpacking in a display")
        (scope/comprehension? (:pk ctx) inner)
        (lower-comprehension ctx inner (if (p/token? (second parts) ":") :dict :set))
        (p/token? (second parts) ":")
        (app* 'py/dict-from
              (build-vector
                (map (fn [[k v]]
                       (build-vector [((:lower ctx) ctx k) ((:lower ctx) ctx v)]))
                     (partition 2 (filter p/rule? parts)))))
        :else (app* 'py/set-from (elements ctx (filter p/rule? parts)))))))


(defn- lower-atom
  [ctx n]
  (let [ks (kids ctx n)
        head (first ks)]
    (cond
      (p/rule? head "name") (read-name ctx head (name-of ctx head))
      (p/token? head "(")
      (let [inner (second ks)]
        (cond
          (p/token? inner ")") (app* 'py/tuple (u/lit []))
          (p/rule? inner "yield_expr") ((:lower ctx) ctx inner)
          (scope/comprehension? (:pk ctx) inner)
          (unsupported! n "generator expression (phase C2)")
          :else (display ctx inner)))
      (p/token? head "[")
      (let [inner (second ks)]
        (cond
          (p/token? inner "]") (app* 'py/list (u/lit []))
          (scope/comprehension? (:pk ctx) inner) (lower-comprehension ctx inner :list)
          :else (app* 'py/list (elements ctx (rule-kids ctx inner)))))
      (p/token? head "{") (lower-brace ctx n (second ks))
      (p/token? head "None") none
      (p/token? head "True") (u/lit true)
      (p/token? head "False") (u/lit false)
      (p/token? head "...") (unsupported! n "Ellipsis")
      (= "NUMBER" (:type head)) (u/lit (parse-number n (:text head)))
      (= "STRING" (:type head))
      (u/lit {:py/str (apply str (map #(parse-string n (:text %)) ks))})
      :else (unsupported! n "atom"))))


(defn- slice-parts
  "`[start stop step]` nodes (nil where absent) of a slicing subscript_, or
   nil when it is a plain index."
  [ctx s]
  (let [sk (kids ctx s)]
    (when (some #(p/token? % ":") sk)
      (let [[before after] (split-with #(not (p/token? % ":")) sk)
            after (rest after)
            stop (first (filter #(p/rule? % "test") after))
            sliceop (first (filter #(p/rule? % "sliceop") after))]
        [(first (filter #(p/rule? % "test") before))
         stop
         (when sliceop (first (rules ctx sliceop "test")))]))))


(defn- lower-subscript
  "One subscript_: its index, or `(py/slice start stop step)`."
  [ctx s]
  (if-let [[a b c] (slice-parts ctx s)]
    (app* 'py/slice
          (if a ((:lower ctx) ctx a) none)
          (if b ((:lower ctx) ctx b) none)
          (if c ((:lower ctx) ctx c) none))
    ((:lower ctx) ctx (first (kids ctx s)))))


(defn- subscript-key
  "The key a `[...]` trailer passes: one index or slice, or the tuple of
   several indices (`d[1, 2]`)."
  [ctx trailer]
  (let [sl (first (rules ctx trailer "subscriptlist"))
        subs* (rules ctx sl "subscript_")]
    (if (or (< 1 (count subs*)) (p/has-token? (:pk ctx) sl ","))
      (do (when (some #(slice-parts ctx %) subs*)
            (unsupported! trailer "multi-dimensional slicing"))
          (app* 'py/tuple (build-vector (map #(lower-subscript ctx %) subs*))))
      (lower-subscript ctx (first subs*)))))


(defn- call-parts
  "A call's arguments as `{:args node :kwargs node-or-nil}`: positional
   values and `*iterable` splices in order, then `name=value` pairs and
   `**mapping` splices in order. CPython evaluates every positional and
   `*` argument before any keyword value, and so does this. As in the
   language reference, `*iterable` may follow `name=value` but not
   `**mapping`, and a plain positional argument may follow neither. A
   generator expression is allowed only as the sole argument of a consuming
   builtin (`:genexp` is then its node)."
  [ctx trailer]
  (let [al (first (rules ctx trailer "arglist"))
        args (if al (rules ctx al "argument") [])
        lower (:lower ctx)]
    (loop [as args
           pos (u/lit [])
           kw nil
           seen #{}
           dstar? false]
      (if-let [a (first as)]
        (let [ak (kids ctx a)]
          (cond
            (scope/comprehension? (:pk ctx) a)
            (if (= 1 (count args))
              {:genexp a}
              (syntax! a "Generator expression must be parenthesized"))
            (p/token? (first ak) "*")
            (do (when dstar?
                  (syntax! a "iterable argument unpacking follows keyword argument unpacking"))
                (recur (rest as) (app* 'py/extend pos (lower ctx (second ak))) kw seen
                       dstar?))
            (p/token? (first ak) "**")
            (recur (rest as) pos
                   (app* 'py/kw-extend (or kw (u/lit [])) (lower ctx (second ak)))
                   seen
                   true)
            (and (= 3 (count ak)) (p/token? (second ak) "="))
            (let [nm (scope/simple-name (:pk ctx) (first ak))]
              (when-not nm (syntax! a "expression cannot contain assignment"))
              (when (contains? seen nm) (syntax! a (str "keyword argument repeated: " nm)))
              (recur (rest as) pos
                     (app* 'py/conj (or kw (u/lit []))
                           (build-vector [(u/lit nm) (lower ctx (nth ak 2))]))
                     (conj seen nm)
                     dstar?))
            :else
            (do (when dstar?
                  (syntax! a "positional argument follows keyword argument unpacking"))
                (when kw (syntax! a "positional argument follows keyword argument"))
                (recur (rest as) (app* 'py/conj pos (lower ctx (first ak))) kw seen
                       dstar?))))
        {:args pos, :kwargs kw}))))


(defn- callee-builtin
  "The builtin name `receiver-atom` reads, when it reads one."
  [ctx atom-node]
  (let [head (first (kids ctx atom-node))]
    (when (p/rule? head "name")
      (let [nm (name-of ctx head)]
        (when (builtin? ctx nm) nm)))))


(defn- apply-trailer
  [ctx receiver trailer & [callee]]
  (let [head (first (kids ctx trailer))]
    (cond
      (p/token? head "(")
      (let [{:keys [args kwargs genexp]} (call-parts ctx trailer)]
        (if genexp
          (if (contains? consuming-builtins callee)
            ;; The consumer's own semantics, inline and lazy: elements are
            ;; produced one at a time, any/all stop at the first decisive
            ;; one, sum and set fold as they go. This is the builtin's
            ;; behaviour only while the name still denotes the builtin
            ;; (globals() may rebind it), which is checked at run time.
            (u/if-node (app* 'py/is receiver (u/v (get prelude/builtin-names callee)))
                       (case callee
                         "list" (lower-comprehension ctx genexp :list)
                         "set" (lower-comprehension ctx genexp :set)
                         "tuple" (app* 'py/tuple
                                       (app* 'py/to-vector
                                             (lower-comprehension ctx genexp :list)))
                         "sum" (lower-comprehension ctx genexp :sum)
                         "any" (lower-comprehension ctx genexp :any)
                         "all" (lower-comprehension ctx genexp :all))
                       (app* 'py/genexp-unsupported))
            (unsupported! genexp "generator expression (phase C2)"))
          (lower-call receiver args kwargs)))
      (p/token? head "[") (app* 'py/getitem receiver (subscript-key ctx trailer))
      (p/token? head ".") (app* 'py/getattr receiver
                                (u/lit (name-of ctx (second (kids ctx trailer))))))))


(defn- lower-call
  "`(py/call f args)`, or `(py/call-kw f args kwargs)` when the call
   passes keywords: the callee binds them."
  [f args kwargs]
  (if kwargs
    (app* 'py/call-kw f args kwargs)
    (app* 'py/call f args)))


(defn- lower-atom-expr
  [ctx n]
  (let [ks (kids ctx n)]
    (when (p/token? (first ks) "await") (unsupported! n "await"))
    (let [atom-node (first ks)
          trailers (rest ks)
          callee (callee-builtin ctx atom-node)]
      (reduce (fn [acc [i t]]
                (apply-trailer ctx acc t (when (zero? i) callee)))
              ((:lower ctx) ctx atom-node)
              (map-indexed vector trailers)))))


(defn- power-operands
  "The operands of a `**` chain, left to right. The grammar's left-
   recursive rule groups `a ** b ** c` as `(a ** b) ** c`; Python's `**` is
   right-associative, so the chain is regrouped here."
  [ctx n]
  (let [[a op b] (kids ctx n)]
    (if (and (p/token? op "**") (p/rule? a "expr")
             (= 3 (count (:children a)))
             (p/token? (second (kids ctx a)) "**"))
      (conj (power-operands ctx a) b)
      [a b])))


(defn- lower-expr
  [ctx n]
  (let [ks (kids ctx n)]
    (cond
      (= 1 (count ks)) ((:lower ctx) ctx (first ks))
      (p/token? (first ks))
      (reduce (fn [acc tok]
                (if-let [op (get unary-ops (:text tok))]
                  (app* op acc)
                  (unsupported! n (str "unary " (:text tok)))))
              ((:lower ctx) ctx (peek ks))
              (rseq (pop ks)))
      (p/token? (second ks) "**")
      (let [operands (mapv #((:lower ctx) ctx %) (power-operands ctx n))]
        (reduce (fn [acc x] (app* 'py/pow x acc))
                (peek operands)
                (rseq (pop operands))))
      :else
      (let [[a op b] ks]
        (if-let [f (get binary-ops (:text op))]
          (app* f ((:lower ctx) ctx a) ((:lower ctx) ctx b))
          (unsupported! n (str "operator " (:text op))))))))


(def ^:private comparison-ops
  {"<" 'py/lt, ">" 'py/gt, "==" 'py/eq, ">=" 'py/ge, "<=" 'py/le,
   "!=" 'py/ne, "is" 'py/is, "is not" 'py/is-not, "in" 'py/in,
   "not in" 'py/not-in})


(defn- lower-comparison
  [ctx n]
  (let [ks (kids ctx n)]
    (if (= 1 (count ks))
      ((:lower ctx) ctx (first ks))
      (let [operands (filterv #(p/rule? % "expr") ks)
            ops (mapv (fn [op]
                        (let [t (str/join " " (map :text (kids ctx op)))]
                          (or (get comparison-ops t)
                              (unsupported! op (str "comparison " t)))))
                      (filter #(p/rule? % "comp_op") ks))
            lower (:lower ctx)]
        (if (= 1 (count ops))
          (app* (first ops) (lower ctx (first operands))
                (lower ctx (second operands)))
          ;; a < b < c: each operand once, left to right, stop at false
          (letfn [(step
                    [k prev]
                    (let [cur (gen "c" (nth operands k))
                          test (app* (nth ops (dec k)) (u/v prev) (u/v cur))]
                      (u/let1 cur
                              (lower ctx (nth operands k))
                              (if (= k (count ops))
                                test
                                (u/if-node test
                                           (step (inc k) cur)
                                           (u/lit false))))))]
            (let [c0 (gen "c" (first operands))]
              (u/let1 c0 (lower ctx (first operands)) (step 1 c0)))))))))


;; -----------------------------------------------------------------------------
;; Assignment
;; -----------------------------------------------------------------------------

(defn- target-atom-expr
  "The atom_expr a target reduces to through single-child rules, or nil."
  [ctx n]
  (loop [n n]
    (cond
      (p/rule? n "atom_expr") n
      (and (p/rule? n) (= 1 (count (:children n))))
      (recur (first (kids ctx n)))
      :else nil)))


(defn- target-elements
  "The element targets when `t` is a tuple or list target, else nil."
  [ctx t]
  (loop [n t]
    (cond
      (and (p/rule? n) (contains? #{"testlist_star_expr" "testlist" "exprlist"
                                    "testlist_comp"}
                                  (:rule n))
           (or (< 1 (count (:children n))) (p/has-token? (:pk ctx) n ",")))
      (rule-kids ctx n)
      (and (p/rule? n "atom")
           (or (p/token? (first (kids ctx n)) "(")
               (p/token? (first (kids ctx n)) "[")))
      (let [inner (second (kids ctx n))]
        (cond
          (not (p/rule? inner "testlist_comp")) []
          (scope/comprehension? (:pk ctx) inner)
          (syntax! n "cannot assign to comprehension")
          ;; `[x]` is always a sequence target; `(x)` is just `x`
          (or (p/token? (first (kids ctx n)) "[")
              (< 1 (count (:children inner)))
              (p/has-token? (:pk ctx) inner ","))
          (rule-kids ctx inner)
          :else (recur (first (rule-kids ctx inner)))))
      (and (p/rule? n) (= 1 (count (:children n))))
      (recur (first (kids ctx n)))
      :else nil)))


(declare assign-target)


(defn- assign-unpacked
  "Unpack `value` into element targets: exactly as many items, or with one
   starred target the rest as a list. Items are assigned left to right."
  [ctx t elems value]
  (let [stars (keep-indexed (fn [i e] (when (p/rule? e "star_expr") i)) elems)
        u (gen "u" t)
        n (count elems)]
    (when (< 1 (count stars))
      (syntax! t "multiple starred expressions in assignment"))
    (u/let1 u
            (if-let [s (first stars)]
              (app* 'py/unpack-star value (u/lit s) (u/lit (- n s 1)))
              (app* 'py/unpack value (u/lit n)))
            (if (zero? n)
              none
              (u/seq-nodes
                (map-indexed
                  (fn [i e]
                    (assign-target ctx
                                   (if (p/rule? e "star_expr") (last (kids ctx e)) e)
                                   (app* 'py/arg (u/v u) (u/lit i))))
                  elems))))))


(defn- assign-target
  "Store `value` (a node, evaluated once here) into target `t`."
  [ctx t value]
  (if-let [nm (scope/simple-name (:pk ctx) t)]
    (assign-name ctx nm value)
    (if-let [elems (target-elements ctx t)]
      (assign-unpacked ctx t elems value)
      (let [ae (target-atom-expr ctx t)
            ks (when ae (kids ctx ae))
            trailers (rest ks)]
        (when (or (nil? ae) (empty? trailers))
          (if (p/rule? (loop [n t]
                         (if (and (p/rule? n) (= 1 (count (:children n))))
                           (recur (first (kids ctx n)))
                           n))
                       "star_expr")
            (syntax! t "starred assignment target must be in a list or tuple")
            (unsupported! t "assignment target")))
        (let [receiver (reduce #(apply-trailer ctx %1 %2)
                               ((:lower ctx) ctx (first ks))
                               (butlast trailers))
              last-t (last trailers)
              head (first (kids ctx last-t))]
          (cond
            (p/token? head "[")
            (let [sl (first (rules ctx last-t "subscriptlist"))
                  [_ _ step] (some #(slice-parts ctx %) (rules ctx sl "subscript_"))]
              (when step (unsupported! last-t "extended slice assignment"))
              (app* 'py/setitem receiver (subscript-key ctx last-t) value))
            (p/token? head ".")
            (app* 'py/setattr receiver
                  (u/lit (name-of ctx (second (kids ctx last-t))))
                  value)
            :else (unsupported! t "assignment to a call")))))))


(def ^:private augmented-ops
  {"+=" 'py/iadd, "-=" 'py/sub, "*=" 'py/imul, "/=" 'py/truediv, "//=" 'py/floordiv,
   "%=" 'py/mod, "**=" 'py/pow, "&=" 'py/bitand, "|=" 'py/bitor, "^=" 'py/bitxor,
   "<<=" 'py/lshift, ">>=" 'py/rshift})


(defn- lower-augmented
  [ctx n target aug rhs]
  (let [op (or (get augmented-ops (:text (first (kids ctx aug))))
               (unsupported! aug (str "augmented " (:text (first (kids ctx aug))))))
        _ (when (target-elements ctx target)
            (syntax! target "illegal expression for augmented assignment"))
        rhs ((:lower ctx) ctx rhs)]
    (if-let [nm (scope/simple-name (:pk ctx) target)]
      (assign-name ctx nm (app* op (read-name ctx target nm) rhs))
      (let [ae (target-atom-expr ctx target)
            ks (when ae (kids ctx ae))
            trailers (rest ks)
            _ (when (or (nil? ae) (empty? trailers))
                (unsupported! target "augmented assignment target"))
            receiver (reduce #(apply-trailer ctx %1 %2)
                             ((:lower ctx) ctx (first ks))
                             (butlast trailers))
            last-t (last trailers)
            head (first (kids ctx last-t))
            o (gen "o" n)
            k (gen "k" n)]
        (cond
          (p/token? head "[")
          (u/let1 o receiver
                  (u/let1 k (subscript-key ctx last-t)
                          (app* 'py/setitem (u/v o) (u/v k)
                                (app* op (app* 'py/getitem (u/v o) (u/v k)) rhs))))
          (p/token? head ".")
          (let [attr (u/lit (name-of ctx (second (kids ctx last-t))))]
            (u/let1 o receiver
                    (app* 'py/setattr (u/v o) attr
                          (app* op (app* 'py/getattr (u/v o) attr) rhs))))
          :else (unsupported! target "augmented assignment to a call"))))))


(defn- lower-expr-stmt
  [ctx n]
  (let [ks (kids ctx n)]
    (cond
      (= 1 (count ks)) ((:lower ctx) ctx (first ks))
      (p/rule? (second ks) "annassign") (unsupported! n "annotated assignment")
      (p/rule? (second ks) "augassign")
      (lower-augmented ctx n (first ks) (second ks) (nth ks 2))
      :else
      (let [sides (filterv p/rule? ks)
            value-node (peek sides)
            targets (pop sides)
            value ((:lower ctx) ctx value-node)]
        (if (and (= 1 (count targets))
                 (scope/simple-name (:pk ctx) (first targets)))
          (assign-target ctx (first targets) value)
          (let [tmp (gen "v" n)]
            (u/let1 tmp value
                    (u/seq-nodes (map #(assign-target ctx % (u/v tmp))
                                      targets)))))))))


;; -----------------------------------------------------------------------------
;; Functions and classes
;; -----------------------------------------------------------------------------

(defn- param-name
  [ctx param]
  (when (< 1 (count (:children param)))
    (unsupported! param "parameter annotation"))
  (name-of ctx (first (rules ctx param "name"))))


(defn- param-spec
  "The shape of a parameter list, in the order Python writes it:
   positional names with the default nodes of the trailing ones; `*name`
   (or a bare `*`); keyword-only names with their default nodes; `**name`."
  [ctx arg-list param-rule]
  (loop [ks (if arg-list (kids ctx arg-list) [])
         phase :positional
         spec {:params [], :defaults [], :star? false, :kwonly [], :kwdefaults [],
               :kwstar? false}]
    (if-let [k (first ks)]
      (cond
        (p/token? k ",") (recur (rest ks) phase spec)
        (p/token? k "**")
        (do (when (some #(p/rule? % param-rule) (drop 2 ks))
              (syntax! arg-list "arguments cannot follow var-keyword argument"))
            (param-name ctx (second ks))
            (recur (drop 2 ks) :done (assoc spec :kwstar? true)))
        (p/token? k "*")
        (if (p/rule? (second ks) param-rule)
          (do (param-name ctx (second ks))
              (recur (drop 2 ks) :kwonly (assoc spec :star? true)))
          (do (when-not (some #(p/rule? % param-rule) (rest ks))
                (syntax! arg-list "named arguments must follow bare *"))
              (recur (rest ks) :kwonly spec)))
        (p/rule? k param-rule)
        (let [nm (param-name ctx k)
              default (when (p/token? (second ks) "=") (nth ks 2))
              ks' (if default (drop 3 ks) (rest ks))]
          (if (= phase :kwonly)
            (recur ks' phase (cond-> (update spec :kwonly conj nm)
                               default (update :kwdefaults conj [nm default])))
            (do (when (and (not default) (seq (:defaults spec)))
                  (syntax! k "non-default argument follows default argument"))
                (recur ks' phase (cond-> (update spec :params conj nm)
                                   default (update :defaults conj default))))))
        :else (unsupported! arg-list "parameter list"))
      spec)))


(defn- definition-body
  "The body of a funcdef, classdef or lambda: the part its own scope runs."
  [ctx n]
  (if (contains? #{"lambdef" "lambdef_nocond"} (:rule n))
    (peek (rule-kids ctx n))
    (first (rules ctx n "block"))))


(defn- yields?
  "True when node `n`, evaluated in its scope, contains a `yield` of that
   scope. A nested definition's header (parameter defaults and
   annotations, class bases) runs in the enclosing scope and is scanned;
   its body is its own scope and is not."
  [ctx n]
  (and (p/rule? n)
       (or (= "yield_expr" (:rule n))
           (boolean
             (some #(yields? ctx %)
                   (if (contains? scope-rules (:rule n))
                     (let [body-id (:id (definition-body ctx n))]
                       (remove #(= body-id (:id %)) (rule-kids ctx n)))
                     (kids ctx n)))))))


(defn- generator-body?
  "True when the body of the funcdef or lambda `scope-node` yields."
  [ctx scope-node]
  (yields? ctx (definition-body ctx scope-node)))


(defn- function-value
  "A guest function object: `py/make-function` over code taking one
   argument vector laid out as the parameters are written (positional,
   `*args` tuple, keyword-only, `**kwargs` dict). Parameters are rebound to
   cells, other locals are allocated unbound, then `body` (a node lowered
   with the function's context). Defaults are evaluated here, at
   definition time, in the enclosing context. A body that yields makes a
   generator function: the call binds the arguments and allocates the
   cells, and returns a generator whose body, a lambda of its `:gen`
   binder, runs at the first resume."
  [ctx scope-node fname {:keys [params defaults star? kwonly kwdefaults kwstar?]}
   body-fn]
  (let [s (get-in (:analysis ctx) [:scopes (:id scope-node)])
        all-params (:params s)
        _ (when-not (= (count all-params) (count (distinct all-params)))
            (syntax! scope-node "duplicate argument in function definition"))
        args (gen "args" scope-node)
        gen-sym (when (generator-body? ctx scope-node) (gen "gen" scope-node))
        others (drop (count all-params) (:locals s))
        body (cond->> (body-fn (assoc ctx
                                      :scope (:id scope-node)
                                      :loop nil
                                      :exc nil
                                      :class nil
                                      :gen gen-sym
                                      :comp nil))
               gen-sym (u/lam [gen-sym])
               gen-sym (app* 'py/make-generator (u/lit fname)))
        cells (concat (map-indexed (fn [i _]
                                     (app* 'cell/new
                                           (app* 'py/arg (u/v args) (u/lit i))))
                                   all-params)
                      (map (fn [_] (app* 'cell/new (u/lit :py/unbound)))
                           others))]
    (app* 'py/make-function
          (u/lit fname)
          (u/lit (cond-> {:params params}
                   star? (assoc :star? true)
                   (seq kwonly) (assoc :kwonly kwonly)
                   kwstar? (assoc :kwstar? true)))
          (build-vector (map #((:lower ctx) ctx %) defaults))
          (build-vector (map (fn [[nm d]]
                               (build-vector [(u/lit nm) ((:lower ctx) ctx d)]))
                             kwdefaults))
          (site :call
                (u/lam [args]
                       (if (seq (:locals s))
                         (apply u/app (u/lam (map symbol (:locals s)) body) cells)
                         body))))))


(defn- lower-funcdef
  [ctx n]
  (let [ks (kids ctx n)
        nm (name-of ctx (first (rules ctx n "name")))
        params (first (rules ctx n "parameters"))]
    (when (some #(p/token? % "->") ks) (unsupported! n "return annotation"))
    (assign-name
      ctx nm
      (function-value
        ctx n nm
        (param-spec ctx (first (rules ctx params "typedargslist")) "tfpdef")
        (fn [fctx]
          (let [block (first (rules ctx n "block"))]
            (if (contains-rule? ctx block "return_stmt" scope-rules)
              (let [ret (gen "ret" n)]
                (app* 'py/call-ec
                      (u/lam [ret]
                             (u/seq-nodes
                               (conj (lower-block (assoc fctx :ret ret) block)
                                     none)))))
              (u/seq-nodes (conj (lower-block (assoc fctx :ret nil) block)
                                 none)))))))))


(defn- lower-lambdef
  "`lambdef` and `lambdef_nocond` (a lambda inside a comprehension's `if`)."
  [ctx n]
  (function-value ctx n "<lambda>"
                  (param-spec ctx (first (rules ctx n "varargslist")) "vfpdef")
                  (fn [fctx]
                    ((:lower ctx) (assoc fctx :ret nil) (peek (rule-kids ctx n))))))


(defn- lower-classdef
  [ctx n]
  (let [nm (name-of ctx (first (rules ctx n "name")))
        arglist (first (rules ctx n "arglist"))
        bases (if arglist (rules ctx arglist "argument") [])
        _ (when (> (count bases) 1) (unsupported! n "multiple inheritance"))
        base (if (seq bases)
               (let [ak (kids ctx (first bases))]
                 (when-not (and (= 1 (count ak)) (p/rule? (first ak) "test"))
                   (unsupported! n "class keyword arguments"))
                 ((:lower ctx) ctx (first ak)))
               (u/v 'py.b/object))
        cls (gen "cls" n)
        body (lower-block (assoc ctx
                                 :scope (:id n)
                                 :class cls
                                 :loop nil
                                 :ret nil
                                 :exc nil
                                 :gen nil
                                 :comp nil)
                          (first (rules ctx n "block")))]
    (u/let1 cls
            (app* 'py/make-class (u/lit nm) base)
            (u/seq-nodes (concat body [(assign-name ctx nm (u/v cls))])))))


;; -----------------------------------------------------------------------------
;; Control flow
;; -----------------------------------------------------------------------------

(defn- lower-if
  [ctx n]
  (let [tests (rules ctx n "test")
        blocks (rules ctx n "block")
        else-node (when (> (count blocks) (count tests))
                    (u/seq-nodes (lower-block ctx (peek blocks))))]
    (reduce (fn [alt [t b]]
              (u/if-node (truthy ((:lower ctx) ctx t))
                         (u/seq-nodes (lower-block ctx b))
                         alt))
            (or else-node none)
            (reverse (map vector tests blocks)))))


(defn- loop-body
  "The loop body, run under a continue escape when it has a `continue`."
  [ctx body-block brk cont]
  (let [has-cont? (contains-rule? ctx body-block "continue_stmt"
                                  loop-and-scope-rules)
        body (u/seq-nodes (lower-block (assoc ctx
                                              :loop {:brk brk,
                                                     :cont (when has-cont? cont)})
                                       body-block))]
    (if has-cont?
      (app* 'py/call-ec (u/lam [cont] body))
      body)))


(defn- with-break
  [ctx body-block brk node]
  (if (contains-rule? ctx body-block "break_stmt" loop-and-scope-rules)
    (app* 'py/call-ec (u/lam [brk] node))
    node))


(defn- lower-while
  [ctx n]
  (let [[body-block else-block] (rules ctx n "block")
        brk (gen "brk" n)
        w (gen "w" n)
        body (loop-body ctx body-block brk (gen "cont" n))
        orelse (if else-block (u/seq-nodes (lower-block ctx else-block)) none)]
    (with-break ctx body-block brk
      (u/let1 w
              (site :loop
                    (u/lam [w]
                           (u/if-node (truthy ((:lower ctx) ctx
                                                            (first (rules ctx n "test"))))
                                      (u/then body (u/app (u/v w) (u/v w)))
                                      orelse)))
              (u/app (u/v w) (u/v w))))))


(defn- lower-for
  [ctx n]
  (let [[body-block else-block] (rules ctx n "block")
        target (first (rules ctx n "exprlist"))
        brk (gen "brk" n)
        w (gen "w" n)
        it (gen "it" n)
        i (gen "i" n)
        x (gen "x" n)
        body (loop-body ctx body-block brk (gen "cont" n))
        orelse (if else-block (u/seq-nodes (lower-block ctx else-block)) none)]
    (with-break ctx body-block brk
      (u/let1 it
              (app* 'py/iterable (display ctx (first (rules ctx n "testlist"))))
              (u/let1 w
                      (site :loop
                            (u/lam [w i]
                                   (u/let1 x
                                           (app* 'py/iter-at (u/v it) (u/v i))
                                           (u/if-node
                                             (app* '= (u/v x) (u/lit :py/stop))
                                             orelse
                                             (u/then (assign-target ctx target (u/v x))
                                                     (u/then body
                                                             (u/app (u/v w) (u/v w)
                                                                    (app* '+ (u/v i)
                                                                          (u/lit 1)))))))))
                      (u/app (u/v w) (u/v w) (u/lit 0)))))))


(declare lower-try-except)


(defn- lower-try
  "`try` with `except` clauses (`py/try`), with `finally` (`py/try-finally`
   around the rest), or both. The finally thunk takes the exception being
   raised through it, or None, and ignores it."
  [ctx n]
  (let [ks (kids ctx n)
        blocks (rules ctx n "block")]
    (if (some #(p/token? % "finally") ks)
      (app* 'py/try-finally
            (u/lam [] (if (seq (rules ctx n "except_clause"))
                        (lower-try-except ctx n (pop blocks))
                        (u/seq-nodes (lower-block ctx (first blocks)))))
            (u/lam [(gen "fx" n)] (u/seq-nodes (lower-block ctx (peek blocks)))))
      (lower-try-except ctx n blocks))))


(defn- lower-with
  "`with a as x, b:` nests as `with a as x: with b:`; each item is
   `(py/with manager (fn [value] (assign target value) ...))`."
  [ctx n]
  (let [items (rules ctx n "with_item")
        body (u/seq-nodes (lower-block ctx (first (rules ctx n "block"))))]
    (reduce (fn [inner item]
              (let [ik (kids ctx item)
                    v (gen "wv" item)]
                (app* 'py/with
                      ((:lower ctx) ctx (first ik))
                      (u/lam [v]
                             (if (p/has-token? (:pk ctx) item "as")
                               (u/then (assign-target ctx (last ik) (u/v v)) inner)
                               inner)))))
            body
            (reverse items))))


(defn- lower-try-except
  "`try` with `except` clauses and an optional `else`: `blocks` are the try
   body, one block per clause, then the else block if any."
  [ctx n blocks]
  (let [exc (gen "exc" n)
        clauses (rules ctx n "except_clause")
        body (u/seq-nodes (lower-block ctx (first blocks)))
        handler-blocks (subvec blocks 1 (inc (count clauses)))
        else-block (get blocks (inc (count clauses)))
        hctx (assoc ctx :exc exc)
        dispatch
        (reduce
          (fn [fallthrough [clause block]]
            (let [ck (kids ctx clause)
                  cls-node (first (rules ctx clause "test"))
                  as-name (first (rules ctx clause "name"))
                  handler (if as-name
                            ;; `except E as e:` binds e for the handler and
                            ;; unbinds it on every exit, as `del e` in a
                            ;; finally would
                            (let [nm (name-of ctx as-name)]
                              (app* 'py/try-finally
                                    (u/lam [] (u/seq-nodes
                                                (cons (assign-name ctx nm (u/v exc))
                                                      (lower-block hctx block))))
                                    (u/lam [(gen "ux" clause)] (unbind-name ctx nm))))
                            (u/seq-nodes (lower-block hctx block)))]
              (when (and (nil? cls-node) (> (count ck) 1))
                (unsupported! clause "except clause"))
              (if cls-node
                (u/if-node (app* 'py/exc-matches (u/v exc)
                                 ((:lower ctx) ctx cls-node))
                           handler
                           fallthrough)
                handler)))
          (app* 'py/raise (u/v exc))
          (reverse (map vector clauses handler-blocks)))]
    (app* 'py/try
          (u/lam [] body)
          (u/lam [exc] dispatch)
          (u/lam [] (if else-block
                      (u/seq-nodes (lower-block ctx else-block))
                      none)))))


(defn- lower-flow
  [ctx n]
  (case (:rule n)
    "break_stmt" (if-let [brk (get-in ctx [:loop :brk])]
                   (u/app (u/v brk) none)
                   (syntax! n "'break' outside loop"))
    "continue_stmt" (if (:loop ctx)
                      (u/app (u/v (get-in ctx [:loop :cont])) none)
                      (syntax! n "'continue' not properly in loop"))
    "return_stmt" (if-let [ret (:ret ctx)]
                    (u/app (u/v ret)
                           (if-let [value (first (rules ctx n "testlist"))]
                             (display ctx value)
                             none))
                    (syntax! n "'return' outside function"))
    "raise_stmt"
    (let [tests (rules ctx n "test")]
      (cond
        (p/has-token? (:pk ctx) n "from") (unsupported! n "raise from")
        (empty? tests) (if-let [exc (:exc ctx)]
                         (app* 'py/raise (u/v exc))
                         (unsupported! n "bare raise outside except"))
        :else (app* 'py/raise (app* 'py/as-exception
                                    ((:lower ctx) ctx (first tests))))))))


(def ^:private comprehension-names
  {:list "list comprehension", :set "set comprehension", :dict "dict comprehension",
   :sum "generator expression", :any "generator expression",
   :all "generator expression"})


(defn- lower-yield
  "`yield v` in a generator body: `(py/yield %gen v)`, whose value is what
   the next resume sends. Outside a function, or in a comprehension's own
   scope, it is a syntax error, as in Python."
  [ctx n]
  (let [arg (first (rules ctx n "yield_arg"))]
    (cond
      (:comp ctx) (syntax! n (str "'yield' inside "
                                  (get comprehension-names (:comp ctx))))
      (nil? (:gen ctx)) (syntax! n "'yield' outside function")
      (and arg (p/has-token? (:pk ctx) arg "from")) (unsupported! n "yield from")
      :else (app* 'py/yield (u/v (:gen ctx))
                  (if arg ((:lower ctx) ctx (first (rule-kids ctx arg))) none)))))


;; -----------------------------------------------------------------------------
;; Comprehensions
;; -----------------------------------------------------------------------------

(defn- comp-clauses
  "A comprehension's clauses in order: `[:for target iterable]` and
   `[:if test]`."
  [ctx comp-node]
  (let [next-clause (fn [n]
                      (when-let [it (first (rules ctx n "comp_iter"))]
                        (first (rule-kids ctx it))))]
    (loop [n (first (rules ctx comp-node "comp_for"))
           acc []]
      (cond
        (nil? n) acc
        (p/rule? n "comp_for")
        (do (when (p/token? (first (kids ctx n)) "async")
              (unsupported! n "async comprehension"))
            (recur (next-clause n)
                   (conj acc [:for (first (rules ctx n "exprlist"))
                              (first (rules ctx n "or_test"))])))
        :else (recur (next-clause n)
                     (conj acc [:if (first (rules ctx n "test_nocond"))]))))))


(defn- lower-comprehension
  "A list, set or dict comprehension (or a generator expression consumed
   eagerly, as a list) in its own scope: the first iterable is evaluated in
   the enclosing scope; the targets are cells local to the comprehension;
   each `for` walks with `py/for-each`, each `if` filters, and the
   innermost clause adds to a fresh result object."
  [ctx comp-node kind]
  (let [s (get-in (:analysis ctx) [:scopes (:id comp-node)])
        cctx (assoc ctx
                    :scope (:id comp-node)
                    :loop nil
                    :ret nil
                    :exc nil
                    :class nil
                    :gen nil
                    :comp kind)
        clauses (comp-clauses ctx comp-node)
        elems (filterv #(not (p/rule? % "comp_for")) (rule-kids ctx comp-node))
        _ (when (some #(p/rule? % "star_expr") elems)
            (syntax! comp-node "iterable unpacking cannot be used in comprehension"))
        fst (gen "first" comp-node)
        acc (gen "acc" comp-node)
        lower (:lower ctx)
        emit (case kind
               :list (app* 'py/list-append (u/v acc) (lower cctx (first elems)))
               :set (app* 'py/set-add (u/v acc) (lower cctx (first elems)))
               :dict (app* 'py/dict-set (u/v acc) (lower cctx (first elems))
                           (lower cctx (second elems)))
               ;; generator expressions consumed by sum/any/all: a running
               ;; fold, or an escape at the first decisive element
               :sum (app* 'cell/set! (u/v acc)
                          (app* 'py/add (app* 'cell/get (u/v acc))
                                (lower cctx (first elems))))
               :any (u/if-node (truthy (lower cctx (first elems)))
                               (u/app (u/v acc) (u/lit true))
                               none)
               :all (u/if-node (truthy (lower cctx (first elems)))
                               none
                               (u/app (u/v acc) (u/lit false))))
        body (reduce (fn [inner [i [tag a b]]]
                       (if (= tag :for)
                         (let [x (gen "x" a)]
                           (app* 'py/for-each
                                 (if (zero? i) (u/v fst) (lower cctx b))
                                 (u/lam [x] (u/then (assign-target cctx a (u/v x))
                                                    inner))))
                         (u/if-node (truthy (lower cctx a)) inner none)))
                     emit
                     (reverse (map-indexed vector clauses)))
        result (case kind
                 ;; acc is the escape: (acc true) / (acc false) ends early
                 :any (app* 'py/call-ec (u/lam [acc] (u/then body (u/lit false))))
                 :all (app* 'py/call-ec (u/lam [acc] (u/then body (u/lit true))))
                 :sum (u/let1 acc (app* 'cell/new (u/lit 0))
                              (u/then body (app* 'cell/get (u/v acc))))
                 (u/let1 acc
                         (case kind
                           :list (app* 'py/list (u/lit []))
                           :set (app* 'py/set-new)
                           :dict (app* 'py/dict-new))
                         (u/then body (u/v acc))))]
    (u/let1 fst
            (lower ctx (nth (first clauses) 2))
            (if (seq (:locals s))
              (apply u/app
                     (u/lam (map symbol (:locals s)) result)
                     (map (fn [_] (app* 'cell/new (u/lit :py/unbound))) (:locals s)))
              result))))


;; -----------------------------------------------------------------------------
;; The one dispatch
;; -----------------------------------------------------------------------------

(defn- lower
  [ctx n]
  (case (:rule n)
    ;; ---- statements
    "file_input" (u/seq-nodes (conj (mapv #(lower ctx %)
                                          (rules ctx n "stmt"))
                                    none))
    "stmt" (lower ctx (first (kids ctx n)))
    "simple_stmts" (u/seq-nodes (mapv #(lower ctx %)
                                      (rules ctx n "simple_stmt")))
    "simple_stmt" (lower ctx (first (kids ctx n)))
    "compound_stmt" (lower ctx (first (kids ctx n)))
    "flow_stmt" (lower ctx (first (kids ctx n)))
    "expr_stmt" (lower-expr-stmt ctx n)
    "pass_stmt" none
    "break_stmt" (lower-flow ctx n)
    "continue_stmt" (lower-flow ctx n)
    "return_stmt" (lower-flow ctx n)
    "raise_stmt" (lower-flow ctx n)
    "yield_stmt" (lower ctx (first (kids ctx n)))
    "yield_expr" (lower-yield ctx n)
    ;; declarations were consumed by scope analysis
    "global_stmt" none
    "nonlocal_stmt" none
    "if_stmt" (lower-if ctx n)
    "while_stmt" (lower-while ctx n)
    "for_stmt" (lower-for ctx n)
    "try_stmt" (lower-try ctx n)
    "with_stmt" (lower-with ctx n)
    "funcdef" (lower-funcdef ctx n)
    "classdef" (lower-classdef ctx n)
    ;; ---- expressions
    "testlist_star_expr" (display ctx n)
    "testlist" (display ctx n)
    "exprlist" (display ctx n)
    "star_expr" (syntax! n "can't use starred expression here")
    "test_nocond" (lower ctx (first (kids ctx n)))
    "lambdef_nocond" (lower-lambdef ctx n)
    "test" (let [ks (kids ctx n)]
             (if (= 1 (count ks))
               (lower ctx (first ks))
               (u/if-node (truthy (lower ctx (nth ks 2)))
                          (lower ctx (nth ks 0))
                          (lower ctx (nth ks 4)))))
    "lambdef" (lower-lambdef ctx n)
    "or_test" (fold-bool ctx n true)
    "and_test" (fold-bool ctx n false)
    "not_test" (let [ks (kids ctx n)]
                 (if (= 1 (count ks))
                   (lower ctx (first ks))
                   (app* 'py/not (lower ctx (second ks)))))
    "comparison" (lower-comparison ctx n)
    "expr" (lower-expr ctx n)
    "atom_expr" (lower-atom-expr ctx n)
    "atom" (lower-atom ctx n)
    "name" (read-name ctx n (name-of ctx n))
    (default-arm n)))


(defn- lower-block
  "The statements of a `block` as a vector of nodes."
  [ctx block]
  (let [ks (kids ctx block)]
    (if (p/rule? (first ks) "simple_stmts")
      [(lower ctx (first ks))]
      (mapv #(lower ctx %) (rules ctx block "stmt")))))


;; =============================================================================
;; Packets
;; =============================================================================

(defn- context
  [packet]
  (let [pk (p/validate! packet)
        analysis (scope/analyze pk)]
    {:pk pk,
     :analysis analysis,
     :scope (:module analysis),
     :lower lower,
     :loop nil,
     :ret nil,
     :exc nil,
     :class nil,
     :gen nil,
     :comp nil}))


(defn lower-module-body
  "The module's statements as one node, before the runtime wrapper: what
   lowering goldens compare."
  [packet]
  (let [ctx (context packet)]
    (lower ctx (p/root (:pk ctx)))))


(defn lower-packet
  "The complete program for one ok packet: the prelude, then the module
   body run by `py/run-module`, with tail calls marked. Its value is
   `{:py/out [...] :py/exception nil-or-{:type :args}}`."
  [packet]
  (u/mark-tails
    (u/then prelude/uast
            (app* 'py/run-module
                  (u/lam [globals-sym globals-fn-sym] (lower-module-body packet))))))


;; =============================================================================
;; The lowering stage
;; =============================================================================

(defn- diagnostic
  [unit data message]
  (merge {:yang.cst/unit unit, :message message}
         (select-keys data [:yang.python.antlr/diagnostic :rule :construct
                            :span :name])))


(def program-medium
  "The program medium's logical identity when the composition names none."
  :yang.python.antlr/program)


(defn lower-transform
  "The lowering stage's transform: an ok packet becomes one program batch on
   port `:program`, a source envelope (`yin.vm.encoder/source-envelope`)
   whose one member is the map AST, so its `:yang/site` marks reach the
   frontend-metadata side table when the encoder projects it. The medium is
   the state's `:medium` and the batch token is the packet's unit. A packet
   that is not ok, or a lowering diagnostic, becomes one record on port
   `:diagnostics` and no program. Stateless."
  [state packet]
  (let [unit (:yang.cst/unit packet)]
    (if (= :yang.cst/ok (:yang.cst/outcome packet))
      (try
        [state [[:program (encoder/source-envelope
                            (get state :medium program-medium)
                            unit
                            [(lower-packet packet)])]]]
        ;; ClojureDart's ex-info type is not catchable by name portably;
        ;; anything without a diagnostic in its ex-data is rethrown below
        (catch #?(:cljd Object
                  :clj clojure.lang.ExceptionInfo
                  :cljs ExceptionInfo)
               e
          (if (:yang.python.antlr/diagnostic (ex-data e))
            [state [[:diagnostics (diagnostic unit (ex-data e) (ex-message e))]]]
            (throw e))))
      [state
       [[:diagnostics {:yang.cst/unit unit,
                       :yang.python.antlr/diagnostic
                       (keyword "yang.python.antlr"
                                (name (:yang.cst/outcome packet))),
                       :errors (:yang.cst/errors packet)}]]])))


(defn open-stage
  "A lowering stage reading CST packets from `cst-stream`, writing program
   batches to `program-stream`, whose logical identity is `medium`, and
   diagnostics to `diagnostics-stream`."
  ([cst-stream program-stream diagnostics-stream]
   (open-stage cst-stream program-stream diagnostics-stream program-medium))
  ([cst-stream program-stream diagnostics-stream medium]
   (stage/open cst-stream
               {:program program-stream, :diagnostics diagnostics-stream}
               {:medium medium})))


(defn step-stage
  [s]
  (stage/step s lower-transform))
