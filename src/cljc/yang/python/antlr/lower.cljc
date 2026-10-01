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
     code takes one argument vector; every call is `(py/call f [args])`,
     which checks arity, fills defaults and packs `*args`.
   - Control flow is continuations: a function with a `return` runs its body
     under `py/call-ec`; loops are self-applied lambdas whose `break` and
     `continue` are escapes; `try` installs a handler continuation through
     `py/try`, and `raise` invokes the innermost one. `finally` is rejected.
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
    [yang.python.antlr.stage :as stage]
    [yang.python.antlr.uast :as u]
    [yin.vm :as vm]))


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
   "yield_stmt" "yield",
   "yield_expr" "yield",
   "yield_arg" "yield",
   "import_stmt" "import",
   "import_name" "import",
   "import_from" "import",
   "import_as_name" "import",
   "import_as_names" "import",
   "dotted_as_name" "import",
   "dotted_as_names" "import",
   "dotted_name" "import",
   "assert_stmt" "assert statement",
   "with_stmt" "with statement",
   "with_item" "with statement",
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
   "star_expr" "starred expression",
   "sliceop" "slice",
   "comp_iter" "comprehension",
   "comp_for" "comprehension",
   "comp_if" "comprehension",
   "test_nocond" "comprehension",
   "lambdef_nocond" "comprehension",
   "encoding_decl" "encoding declaration"})


(def consumed-rules
  "Rules read only by their parent's arm; they never reach `lower` alone."
  #{"parameters" "typedargslist" "tfpdef" "varargslist" "vfpdef"
    "augassign" "except_clause" "block" "comp_op" "trailer" "subscriptlist"
    "subscript_" "arglist" "argument" "testlist_comp" "dictorsetmaker"
    "strings"})


(def handled-rules
  "Rules with their own arm in `lower`."
  #{"file_input" "stmt" "simple_stmts" "simple_stmt" "compound_stmt"
    "flow_stmt" "expr_stmt" "testlist_star_expr" "testlist" "exprlist"
    "pass_stmt" "break_stmt" "continue_stmt" "return_stmt" "raise_stmt"
    "global_stmt" "nonlocal_stmt" "if_stmt" "while_stmt" "for_stmt"
    "try_stmt" "funcdef" "classdef" "test" "lambdef" "or_test" "and_test"
    "not_test" "comparison" "expr" "atom_expr" "atom" "name"})


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


(def ^:private scope-rules #{"funcdef" "lambdef" "classdef"})


(def ^:private loop-and-scope-rules
  (into scope-rules #{"while_stmt" "for_stmt"}))


;; =============================================================================
;; Literals
;; =============================================================================

(def ^:private max-exact-int 9007199254740991)


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
  (parse-radix n s 16))


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


(defn- global-key
  [name]
  (u/lit {:py/str name}))


(defn- read-global
  "A module-level read at run time: the module dict, then, for a builtin
   name, the builtin. A present module key always wins, so
   `print(len([])); len = 1` reads the builtin first and the global after."
  [n name]
  (cond
    (contains? prelude/builtin-names name)
    (app* 'py/global-or (u/v globals-sym) (global-key name)
          (u/v (get prelude/builtin-names name)))
    (= "globals" name) (unsupported! n "builtin globals used as a value")
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


(defn- assign-name
  [ctx name value]
  (let [r (resolve-name ctx name)]
    (case (:kind r)
      :cell (app* 'cell/set! (u/v (symbol name)) value)
      :class-attr (app* 'py/setattr (u/v (:class ctx)) (u/lit name) value)
      :global (app* 'py/global-set (u/v globals-sym) (global-key name) value))))


;; =============================================================================
;; The dispatch: one arm per grammar rule
;; =============================================================================

(declare lower-block lower-call)


(defn- single-child
  "Lower a list rule that must hold exactly one element."
  [ctx lower n construct]
  (let [elems (rule-kids ctx n)]
    (if (and (= 1 (count elems)) (not (p/has-token? (:pk ctx) n ",")))
      (lower ctx (first elems))
      (unsupported! n construct))))


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
  {"+" 'py/add, "-" 'py/sub, "*" 'py/mul, "/" 'py/truediv})


(def ^:private unary-ops
  {"-" 'py/neg, "+" 'py/pos})


(defn- lower-atom
  [ctx n]
  (let [ks (kids ctx n)
        head (first ks)]
    (cond
      (p/rule? head "name") (read-name ctx head (name-of ctx head))
      (p/token? head "(")
      (let [inner (second ks)]
        (cond
          (p/token? inner ")") (unsupported! n "tuple")
          (p/rule? inner "yield_expr") (unsupported! n "yield")
          (some #(p/rule? % "comp_for") (kids ctx inner))
          (unsupported! n "generator expression")
          :else (single-child ctx (:lower ctx) inner "tuple")))
      (p/token? head "[")
      (let [inner (second ks)]
        (if (p/token? inner "]")
          (app* 'py/list (u/lit []))
          (let [elems (rule-kids ctx inner)]
            (when (some #(p/rule? % "comp_for") elems)
              (unsupported! n "list comprehension"))
            (app* 'py/list
                  (build-vector (map #((:lower ctx) ctx %) elems))))))
      (p/token? head "{")
      (let [inner (second ks)]
        (if (p/token? inner "}")
          (app* 'py/dict-from (u/lit []))
          (let [parts (kids ctx inner)
                ok? (and (p/token? (second parts) ":")
                         (every? #(or (p/rule? % "test") (p/token? % ":")
                                      (p/token? % ","))
                                 parts))]
            (when-not ok? (unsupported! n "set, dict comprehension or unpacking"))
            (app* 'py/dict-from
                  (build-vector
                    (map (fn [[k v]]
                           (build-vector [((:lower ctx) ctx k)
                                          ((:lower ctx) ctx v)]))
                         (partition 2 (filter p/rule? parts))))))))
      (p/token? head "None") none
      (p/token? head "True") (u/lit true)
      (p/token? head "False") (u/lit false)
      (p/token? head "...") (unsupported! n "Ellipsis")
      (= "NUMBER" (:type head)) (u/lit (parse-number n (:text head)))
      (= "STRING" (:type head))
      (u/lit {:py/str (apply str (map #(parse-string n (:text %)) ks))})
      :else (unsupported! n "atom"))))


(defn- subscript-key
  [ctx trailer]
  (let [sl (first (rules ctx trailer "subscriptlist"))
        subs* (rules ctx sl "subscript_")]
    (when (or (not= 1 (count subs*)) (p/has-token? (:pk ctx) sl ","))
      (unsupported! trailer "tuple subscript"))
    (let [s (first subs*)
          sk (kids ctx s)]
      (when-not (and (= 1 (count sk)) (p/rule? (first sk) "test"))
        (unsupported! trailer "slice"))
      ((:lower ctx) ctx (first sk)))))


(defn- call-args
  [ctx trailer]
  (let [al (first (rules ctx trailer "arglist"))
        args (if al (rules ctx al "argument") [])]
    (mapv (fn [a]
            (let [ak (kids ctx a)]
              (when-not (and (= 1 (count ak)) (p/rule? (first ak) "test"))
                (unsupported! a "keyword, starred or generator argument"))
              ((:lower ctx) ctx (first ak))))
          args)))


(defn- apply-trailer
  [ctx receiver trailer]
  (let [head (first (kids ctx trailer))]
    (cond
      (p/token? head "(") (lower-call receiver (call-args ctx trailer))
      (p/token? head "[") (app* 'py/getitem receiver (subscript-key ctx trailer))
      (p/token? head ".") (app* 'py/getattr receiver
                                (u/lit (name-of ctx (second (kids ctx trailer))))))))


(defn- lower-call
  "Every call is `(py/call f [args...])`: the callee checks the count."
  [f args]
  (app* 'py/call f (build-vector args)))


(defn- builtin-call
  "`globals()` is the module namespace itself; nil for any other call."
  [ctx atom-node trailer]
  (let [head (first (kids ctx atom-node))]
    (when (and (p/rule? head "name")
               (p/token? (first (kids ctx trailer)) "(")
               (= "globals" (name-of ctx head))
               (builtin? ctx "globals"))
      (when (seq (call-args ctx trailer))
        (unsupported! trailer "globals() with arguments"))
      (u/v globals-sym))))


(defn- lower-atom-expr
  [ctx n]
  (let [ks (kids ctx n)]
    (when (p/token? (first ks) "await") (unsupported! n "await"))
    (let [atom-node (first ks)
          trailers (rest ks)]
      (if-let [special (and (seq trailers)
                            (builtin-call ctx atom-node (first trailers)))]
        (reduce #(apply-trailer ctx %1 %2) special (rest trailers))
        (reduce #(apply-trailer ctx %1 %2)
                ((:lower ctx) ctx atom-node)
                trailers)))))


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
      :else
      (let [[a op b] ks]
        (if-let [f (get binary-ops (:text op))]
          (app* f ((:lower ctx) ctx a) ((:lower ctx) ctx b))
          (unsupported! n (str "operator " (:text op))))))))


(def ^:private comparison-ops
  {"<" 'py/lt, ">" 'py/gt, "==" 'py/eq, ">=" 'py/ge, "<=" 'py/le,
   "!=" 'py/ne, "is" 'py/is, "is not" 'py/is-not})


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


(defn- assign-target
  "Store `value` (already evaluated: a variable node) into target `t`."
  [ctx t value]
  (if-let [nm (scope/simple-name (:pk ctx) t)]
    (assign-name ctx nm value)
    (let [ae (target-atom-expr ctx t)
          ks (when ae (kids ctx ae))
          trailers (rest ks)]
      (when (or (nil? ae) (empty? trailers))
        (unsupported! t "assignment target"))
      (let [receiver (reduce #(apply-trailer ctx %1 %2)
                             ((:lower ctx) ctx (first ks))
                             (butlast trailers))
            last-t (last trailers)
            head (first (kids ctx last-t))]
        (cond
          (p/token? head "[")
          (app* 'py/setitem receiver (subscript-key ctx last-t) value)
          (p/token? head ".")
          (app* 'py/setattr receiver
                (u/lit (name-of ctx (second (kids ctx last-t))))
                value)
          :else (unsupported! t "assignment to a call"))))))


(def ^:private augmented-ops
  {"+=" 'py/add, "-=" 'py/sub, "*=" 'py/mul, "/=" 'py/truediv})


(defn- lower-augmented
  [ctx n target aug rhs]
  (let [op (or (get augmented-ops (:text (first (kids ctx aug))))
               (unsupported! aug (str "augmented " (:text (first (kids ctx aug))))))
        rhs (if (p/rule? rhs "yield_expr")
              (unsupported! rhs "yield")
              ((:lower ctx) ctx rhs))]
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
            _ (when (p/rule? value-node "yield_expr")
                (unsupported! value-node "yield"))
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

(defn- param-spec
  "The shape of a parameter list: positional parameters, the default value
   nodes of the trailing ones, and whether a `*name` follows. Keyword-only
   parameters, `**kwargs` and annotations are refused."
  [ctx arg-list param-rule]
  (loop [ks (if arg-list (kids ctx arg-list) [])
         spec {:defaults [], :star? false}]
    (if-let [k (first ks)]
      (cond
        (p/token? k ",") (recur (rest ks) spec)
        (p/token? k "**") (unsupported! arg-list "**kwargs parameter")
        (p/token? k "*")
        (let [target (second ks)]
          (when-not (p/rule? target param-rule)
            (unsupported! arg-list "keyword-only parameters"))
          (when (< 1 (count (:children target)))
            (unsupported! target "parameter annotation"))
          (when (some #(p/rule? % param-rule) (drop 2 ks))
            (unsupported! arg-list "keyword-only parameters"))
          (recur (drop 2 ks) (assoc spec :star? true)))
        (p/rule? k param-rule)
        (do (when (< 1 (count (:children k)))
              (unsupported! k "parameter annotation"))
            (if (p/token? (second ks) "=")
              (recur (drop 3 ks) (update spec :defaults conj (nth ks 2)))
              (do (when (seq (:defaults spec))
                    (syntax! k "non-default argument follows default argument"))
                  (recur (rest ks) spec))))
        :else (unsupported! arg-list "parameter list"))
      spec)))


(defn- function-value
  "A guest function object: `py/make-function` over code taking one
   argument vector. Parameters (the `*args` tuple last) are rebound to
   cells, other locals are allocated unbound, then `body` (a node lowered
   with the function's context). Defaults are evaluated here, at
   definition time, in the enclosing context."
  [ctx scope-node fname {:keys [defaults star?]} body-fn]
  (let [s (get-in (:analysis ctx) [:scopes (:id scope-node)])
        params (:params s)
        _ (when-not (= (count params) (count (distinct params)))
            (syntax! scope-node "duplicate argument in function definition"))
        args (gen "args" scope-node)
        others (drop (count params) (:locals s))
        body (body-fn (assoc ctx
                             :scope (:id scope-node)
                             :loop nil
                             :exc nil
                             :class nil))
        cells (concat (map-indexed (fn [i _]
                                     (app* 'cell/new
                                           (app* 'py/arg (u/v args) (u/lit i))))
                                   params)
                      (map (fn [_] (app* 'cell/new (u/lit :py/unbound)))
                           others))]
    (app* 'py/make-function
          (u/lit fname)
          (u/lit (if star? (dec (count params)) (count params)))
          (build-vector (map #((:lower ctx) ctx %) defaults))
          (u/lit star?)
          (u/lam [args]
                 (if (seq (:locals s))
                   (apply u/app (u/lam (map symbol (:locals s)) body) cells)
                   body)))))


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
  [ctx n]
  (function-value ctx n "<lambda>"
                  (param-spec ctx (first (rules ctx n "varargslist")) "vfpdef")
                  (fn [fctx]
                    ((:lower ctx) (assoc fctx :ret nil)
                                  (first (rules ctx n "test"))))))


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
                                 :exc nil)
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
              (u/lam [w]
                     (u/if-node (truthy ((:lower ctx) ctx
                                                      (first (rules ctx n "test"))))
                                (u/then body (u/app (u/v w) (u/v w)))
                                orelse))
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
    (when-not (scope/simple-name (:pk ctx) target)
      (unsupported! target "for target other than a name"))
    (with-break ctx body-block brk
      (u/let1 it
              (single-child ctx (:lower ctx) (first (rules ctx n "testlist"))
                            "tuple")
              (u/let1 w
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
                                                                    (u/lit 1))))))))
                      (u/app (u/v w) (u/v w) (u/lit 0)))))))


(defn- lower-try
  [ctx n]
  (let [ks (kids ctx n)]
    (when (some #(p/token? % "finally") ks)
      (unsupported! n "finally"))
    (let [exc (gen "exc" n)
          blocks (rules ctx n "block")
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
                    handler (u/seq-nodes
                              (concat (when as-name
                                        [(assign-name ctx (name-of ctx as-name)
                                                      (u/v exc))])
                                      (lower-block hctx block)))]
                (when (and (nil? cls-node) (> (count ck) 1))
                  (unsupported! clause "except clause"))
                (if cls-node
                  (u/if-node (app* 'py/isinstance (u/v exc)
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
                        none))))))


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
                             (single-child ctx (:lower ctx) value "tuple")
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
    ;; declarations were consumed by scope analysis
    "global_stmt" none
    "nonlocal_stmt" none
    "if_stmt" (lower-if ctx n)
    "while_stmt" (lower-while ctx n)
    "for_stmt" (lower-for ctx n)
    "try_stmt" (lower-try ctx n)
    "funcdef" (lower-funcdef ctx n)
    "classdef" (lower-classdef ctx n)
    ;; ---- expressions
    "testlist_star_expr" (single-child ctx lower n "tuple")
    "testlist" (single-child ctx lower n "tuple")
    "exprlist" (single-child ctx lower n "tuple")
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
     :class nil}))


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
                  (u/lam [globals-sym] (lower-module-body packet))))))


;; =============================================================================
;; The lowering stage
;; =============================================================================

(defn- diagnostic
  [unit data message]
  (merge {:yang.cst/unit unit, :message message}
         (select-keys data [:yang.python.antlr/diagnostic :rule :construct
                            :span :name])))


(defn lower-transform
  "The lowering stage's transform: an ok packet becomes one program batch
   (datoms) on port `:program`; a packet that is not ok, or a lowering
   diagnostic, becomes one record on port `:diagnostics` and no program.
   Stateless."
  [state packet]
  (let [unit (:yang.cst/unit packet)]
    (if (= :yang.cst/ok (:yang.cst/outcome packet))
      (try
        [state [[:program (vec (vm/ast->datoms (lower-packet packet)))]]]
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
   batches to `program-stream` and diagnostics to `diagnostics-stream`."
  [cst-stream program-stream diagnostics-stream]
  (stage/open cst-stream
              {:program program-stream, :diagnostics diagnostics-stream}
              {}))


(defn step-stage
  [s]
  (stage/step s lower-transform))
