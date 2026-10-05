(ns yang.python.antlr.int-literal-test
  "C3 slice S2 (docs/design/yang.antlr.md 8.5.4): integer literals and the
   boundary, on every host and all four VMs, from hand-built CST packets
   shaped as the parser builds them, so no parser is needed.

   A literal up to 2^53 - 1 lowers to a native `:literal`; a larger one to
   `(py/int-lit \"<canonical lowercase hex>\")`. Every spelling of one
   value gives one row set, one byte string and one address (JVM goldens).
   A decimal token obeys the packet's digit budget at lowering; hex, octal
   and binary tokens have none. Evaluated literals reproduce the frozen S0
   columns, a bignum survives every container and prints exactly, and
   malformed encodings and wrong carriers keep their refusals."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.jing :as jing]
    [dao.jing.cbor :as cbor]
    [dao.jing.cbor-fixtures :as cfx]
    [yang.python.antlr.int-contract-fixtures :as f]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.lower-portable-test :refer [packet]]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.render :as render]
    [yang.python.antlr.uast :as u]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.debruijn-linearize :as dl]
    [yin.vm.debruijn-register-compile :as rc]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.integer :as integer]
    [yin.vm.integer.host :as host]
    [yin.vm.linearize :as linearize]
    [yin.vm.module :as module]
    [yin.vm.semantic :as semantic]
    [yin.vm.test-utils :as tu]))


(def ^:private fixture (delay (f/read-file)))


(def ^:private budget
  "The decimal digit budget the programs here declare."
  4300)


(defn- with-budget
  [pk]
  (assoc pk :yang.python.antlr/max-digits budget))


(defn- refusal-data
  "The ex-data of what `thunk` throws, else nil."
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (or (ex-data e) {:thrown (str e)}))))


;; =============================================================================
;; Programs: parser CSTs as nested forms
;; =============================================================================

(defn- test-of
  "`test` down to `expr`, a single-child chain."
  [expr]
  [:test [:or_test [:and_test [:not_test [:comparison expr]]]]])


(defn- atom-of
  [x]
  [:expr [:atom_expr [:atom x]]])


(defn- num-of
  [text]
  (atom-of ["NUMBER" text]))


(defn- nm
  [s]
  (atom-of [:name ["NAME" s]]))


(defn- simple
  [stmt]
  [:stmt [:simple_stmts [:simple_stmt stmt] ["NEWLINE" "\n"]]])


(defn- assign
  [target expr]
  (simple [:expr_stmt [:testlist_star_expr (test-of (nm target))]
           ["ASSIGN" "="]
           [:testlist_star_expr (test-of expr)]]))


(defn- print-stmt
  "`print(arg, ...)`."
  [exprs]
  (simple [:expr_stmt
           [:testlist_star_expr
            (test-of
              [:expr [:atom_expr [:atom [:name ["NAME" "print"]]]
                      [:trailer ["OPEN_PAREN" "("]
                       (into [:arglist]
                             (interpose ["COMMA" ","]
                                        (map (fn [e] [:argument (test-of e)])
                                             exprs)))
                       ["CLOSE_PAREN" ")"]]]])]]))


(defn- block
  [stmts]
  (-> [:block ["NEWLINE" "\n"] ["INDENT" "    "]]
      (into stmts)
      (conj ["DEDENT" ""])))


(defn- program
  [& stmts]
  (packet (-> [:file_input] (into stmts) (conj ["EOF" "<EOF>"]))))


(defn- assign-program
  "`x = <token>`."
  [token]
  (program (assign "x" (num-of token))))


(defn- print-program
  "`print(<token>, ...)`."
  [tokens]
  (program (print-stmt (mapv num-of tokens))))


(defn- caught-program
  "try:\n    x = <token>\nexcept MemoryError:\n    print('caught')\n"
  [token]
  (program
    [:stmt
     [:compound_stmt
      [:try_stmt ["TRY" "try"] ["COLON" ":"]
       (block [(assign "x" (num-of token))])
       [:except_clause ["EXCEPT" "except"] (test-of (nm "MemoryError"))]
       ["COLON" ":"]
       (block [(print-stmt [(atom-of ["STRING" "'caught'"])])])]]]))


(defn- display
  "A bracketed display: `open` `close` around `testlist_comp` or
   `dictorsetmaker` children."
  [open-type open close-type close rule kids]
  [:expr [:atom_expr [:atom [open-type open] (into [rule] kids)
                      [close-type close]]]])


(defn- containers-program
  "def f():\n    x = <token>\n    return [x, (x, 1), {1: x}, {x: 2}]\n
   print(f())\n: the literal held in a cell, a list, a tuple, a dict value
   and a dict key, returned and printed"
  [token]
  (let [x (test-of (nm "x"))
        comma ["COMMA" ","]
        colon ["COLON" ":"]
        tuple (display "OPEN_PAREN" "(" "CLOSE_PAREN" ")" :testlist_comp
                       [x comma (test-of (num-of "1"))])
        dict-v (display "OPEN_BRACE" "{" "CLOSE_BRACE" "}" :dictorsetmaker
                        [(test-of (num-of "1")) colon x])
        dict-k (display "OPEN_BRACE" "{" "CLOSE_BRACE" "}" :dictorsetmaker
                        [x colon (test-of (num-of "2"))])
        lst (display "OPEN_BRACK" "[" "CLOSE_BRACK" "]" :testlist_comp
                     [x comma (test-of tuple) comma (test-of dict-v) comma
                      (test-of dict-k)])]
    (program
      [:stmt
       [:compound_stmt
        [:funcdef ["DEF" "def"] [:name ["NAME" "f"]]
         [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]] ["COLON" ":"]
         (block [(assign "x" (num-of token))
                 (simple [:flow_stmt
                          [:return_stmt ["RETURN" "return"]
                           [:testlist (test-of lst)]]])])]]]
      (print-stmt [[:expr [:atom_expr [:atom [:name ["NAME" "f"]]]
                           [:trailer ["OPEN_PAREN" "("]
                            ["CLOSE_PAREN" ")"]]]]]))))


;; =============================================================================
;; Spellings
;; =============================================================================

(defn- text-in
  "Lowercase text of integer `n` in `radix`."
  [n radix]
  (f/call (f/module 100000 100000) 'format n radix))


(defn- grouped
  "`s` with an underscore between every `k` characters from the right."
  [s k]
  (let [n (count s)
        head (let [r (rem n k)] (if (zero? r) k r))]
    (str/join "_" (cons (subs s 0 head)
                        (mapv #(subs s % (+ % k)) (range head n k))))))


(defn- mixed-case
  [s]
  (apply str (map-indexed (fn [i c] (if (even? i) (str/upper-case c) c))
                          (mapv #(subs s % (inc %)) (range (count s))))))


(defn- spellings
  "Every Python spelling of non-negative integer `n` the tests use."
  [n]
  (let [d (text-in n 10)
        x (text-in n 16)]
    [d
     (grouped d 3)
     (str "0x" x)
     (str "0X" (mixed-case x))
     (str "0x_" (grouped x 4))
     (str "0o" (text-in n 8))
     (str "0O" (grouped (text-in n 8) 2))
     (str "0b" (text-in n 2))
     (str "0B" (grouped (text-in n 2) 8))]))


(def ^:private groups
  "Value names of the spelling groups."
  ["2^53-1" "2^53" "2^63" "2^64" "2^100"])


(def ^:private group-goldens
  "Root address of `x = <literal>`'s lowered body, per group (JVM)."
  {"2^53-1"
   :segment/blake3-cc540dcc7f75dd09fc505cf736e01a1227664102025c686382fc00524a02f609,
   "2^53"
   :segment/blake3-b66fc53bd3f2c0258e3e5f92b430fc12447656dddb725a65fdb69f7c1f68a472,
   "2^63"
   :segment/blake3-ed5bacac3f3e1a2b05262a994eb5ae523027b4db616dfbf33522f69f0c198edb,
   "2^64"
   :segment/blake3-493ee80f909800c566e96adc4c15bcc6aa48209051c9499d24ad36d7587a8568,
   "2^100"
   :segment/blake3-24aa8cbfc9a12a883f46179409508e89193a01cb303942f76f5bc82d86c272d7})


(defn- rows-of
  [token]
  (vm/ast->semantic-bytecode
    (lower/lower-module-body (with-budget (assign-program token)))))


(defn- assigned-node
  "The node `x = <expr>` assigns."
  [expr]
  (get-in (lower/lower-module-body (with-budget (program (assign "x" expr))))
          [:operands 0 :operands 2]))


(defn- literal-node
  "The node `x = <token>` assigns."
  [token]
  (assigned-node (num-of token)))


(deftest spelling-groups-test
  (testing "every spelling of a value: one row set, one byte string, one
            address, the JVM golden on every host"
    (doseq [s groups]
      (let [n (f/value s)
            results (mapv (fn [t]
                            (let [r (rows-of t)]
                              [t r (cfx/bytes->hex (jing/canonical-bytes r))]))
                          (spellings n))
            [_ rows bytes-hex] (first results)]
        (testing s
          (doseq [[t r h] results]
            (is (= rows r) t)
            (is (= bytes-hex h) t))
          (is (= (get group-goldens s) (:root rows))))))))


(deftest native-and-call-forms-test
  (testing "2^53 - 1 is a native literal on every host"
    (is (= (u/lit 9007199254740991) (literal-node "9007199254740991")))
    (is (= (u/lit 9007199254740991) (literal-node "0x1fffffffffffff"))))
  (testing "2^53 and above: py/int-lit over canonical lowercase hex"
    (is (= (u/app (u/v 'py/int-lit) (u/lit "20000000000000"))
           (literal-node "9007199254740992")))
    (is (= (u/app (u/v 'py/int-lit) (u/lit "10000000000000000"))
           (literal-node "0X0000_1_0000_0000_0000_0000")))
    (is (= (u/app (u/v 'py/int-lit) (u/lit "ffffffffffffffff"))
           (literal-node "0xFFFF_ffff_FFFF_ffff"))))
  (testing "small literals keep their native value, leading zeros dropped"
    (is (= (u/lit 0) (literal-node "0")))
    (is (= (u/lit 0) (literal-node "00")))
    (is (= (u/lit 0) (literal-node "0_0")))
    (is (= (u/lit 255) (literal-node "0x00ff")))
    (is (= (u/lit 8) (literal-node "0o10")))
    (is (= (u/lit 5) (literal-node "0b101"))))
  (testing "a sign is not part of the token: py/neg over the literal"
    (is (= (u/app (u/v 'py/neg) (u/lit 5))
           (assigned-node [:expr ["MINUS" "-"] (num-of "5")])))
    (is (= (u/app (u/v 'py/neg)
                  (u/app (u/v 'py/int-lit) (u/lit "10000000000000000")))
           (assigned-node [:expr ["MINUS" "-"]
                           (num-of "18446744073709551616")])))))


(defn- literal-defects
  "Every integer in `rows` outside +/-(2^53 - 1), or held in a big
   carrier."
  [rows]
  (filterv (fn [x]
             (or (host/big-carrier? x)
                 (and (integer? x)
                      (not (<= -9007199254740991 x 9007199254740991)))))
           (tree-seq coll? seq rows)))


(deftest lowered-rows-hold-only-safe-integers-test
  (testing "a walk over the lowered rows of every spelling and every S0
            value: no integer literal outside the safe range, no big
            carrier"
    (let [tokens (into (vec (mapcat #(spellings (f/value %)) groups))
                       (map #(text-in (f/value %) 10))
                       (remove #(str/starts-with? % "-") f/int-names))]
      (is (= [] (filterv #(seq (literal-defects (rows-of %))) tokens)))
      (is (= [] (literal-defects
                  (vm/ast->semantic-bytecode
                    (lower/lower-packet
                      (with-budget
                        (containers-program "0x1_0000_0000_0000_0000"))))))))))


;; =============================================================================
;; Digit budget and bit limit
;; =============================================================================

(defn- diagnostic-of
  [pk]
  (let [[_ [[port d]]] (lower/lower-transform {} pk)]
    (when (= :diagnostics port) d)))


(deftest decimal-digit-budget-test
  (let [hex (apply str (repeat 5000 "f"))
        n (f/call (f/module 100000 100000) 'parse hex 16)
        dec-text (text-in n 10)]
    (testing "a 5000-digit hex literal lowers under a 4300-digit budget"
      (is (= (u/app (u/v 'py/int-lit) (u/lit hex))
             (literal-node (str "0x" hex)))))
    (testing "the same value in decimal is a source diagnostic"
      (is (= 6021 (count dec-text)))
      (is (= :yang.python.antlr/syntax
             (:yang.python.antlr/diagnostic
               (diagnostic-of (with-budget (assign-program dec-text)))))))
    (testing "the budget counts digits without underscores"
      (let [at (apply str "1" (repeat (dec budget) "0"))
            past (str at "0")]
        (is (nil? (diagnostic-of (with-budget (assign-program at)))))
        (is (nil? (diagnostic-of (with-budget (assign-program
                                                (grouped at 3))))))
        (is (some? (diagnostic-of (with-budget (assign-program past)))))
        (is (some? (diagnostic-of (with-budget (assign-program
                                                 (grouped past 3))))))))
    (testing "with no budget declared a decimal literal above 2^53 - 1 is a
              diagnostic; up to it, and any hex, octal or binary, is not"
      (is (some? (diagnostic-of (assign-program "9007199254740992"))))
      (is (nil? (diagnostic-of (assign-program "9007199254740991"))))
      (is (nil? (diagnostic-of (assign-program "0x20000000000000"))))
      (is (nil? (diagnostic-of (assign-program (str "0x" hex)))))
      (is (nil? (diagnostic-of (assign-program
                                 (str "0b1" (apply str (repeat 64 "0"))))))))
    (testing "a stage's declared budget applies to a packet declaring none"
      (let [pk (assign-program "9007199254740992")
            port (fn [state pk]
                   (ffirst (second (lower/lower-transform state
                                                          pk))))]
        (is (= :program (port {:max-digits 16} pk)))
        (is (= :diagnostics (port {:max-digits 15} pk)))
        (is (= :program (port {:max-digits 15}
                              (assoc pk :yang.python.antlr/max-digits 16))))))
    (testing "a budget that is not a positive integer is refused"
      (is (some? (refusal-data
                   #(lower/lower-packet
                      (assoc (assign-program "1")
                             :yang.python.antlr/max-digits 0))))))))


(deftest malformed-literals-refuse-test
  (testing "the lowering checks Python's integer grammar itself, should a
            grammar ever pass a malformed token: CPython 3.9.6's messages"
    (doseq [[token message]
            [["0755" (str "leading zeros in decimal integer literals are "
                          "not permitted; use an 0o prefix for octal "
                          "integers")]
             ["0_7" (str "leading zeros in decimal integer literals are "
                         "not permitted; use an 0o prefix for octal "
                         "integers")]
             ["1__0" "invalid decimal literal"]
             ["1_" "invalid decimal literal"]
             ["0x" "invalid hexadecimal literal"]
             ["0x_" "invalid hexadecimal literal"]
             ["0xg" "invalid hexadecimal literal"]
             ["0x1__f" "invalid hexadecimal literal"]
             ["0o8" "invalid octal literal"]
             ["0b12" "invalid binary literal"]
             ["0b_" "invalid binary literal"]]]
      (let [d (diagnostic-of (with-budget (assign-program token)))]
        (is (= [:yang.python.antlr/syntax message]
               [(:yang.python.antlr/diagnostic d) (:message d)])
            token)))))


;; =============================================================================
;; Running: all four VMs
;; =============================================================================

(defn- opts-under
  "The composition over the real modules, `integer` under `limits`,
   installed through the Python profile's registrar."
  [limits]
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                data/register-data-module
                (prelude/register-integer-module limits))})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(defn- runners-under
  "Each VM's runner over the composition `opts`."
  [opts]
  {:ast-walker (fn [ast] (vm/value (vm/eval (tu/create-vm opts) ast))),
   :semantic (fn [ast]
               (vm/value (vm/run (load-semantic-ast (semantic/create-vm opts)
                                                    (vm/ast->datoms ast))))),
   :stack (fn [ast]
            (vm/value
              (vm/run (dvm/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                                     (assoc opts
                                            :contract vm/stack-contract))))),
   :register (fn [ast]
               (vm/value
                 (vm/run
                   (rvm/create-vm
                     (:image (rc/adapt (vm/ast->datoms ast)))
                     (assoc opts :contract vm/register-contract)))))})


(def ^:private runners
  (delay (runners-under (opts-under {::integer/max-bits 100000,
                                     ::integer/max-digits 4300}))))


(def ^:private runners-4096
  (delay (runners-under (opts-under {::integer/max-bits 4096,
                                     ::integer/max-digits 4300}))))


(defn- run-everywhere
  "The value of program packet `pk` (budget declared) on each VM."
  [runners pk]
  (let [ast (lower/lower-packet (with-budget pk))]
    (into {}
          (map (fn [[k run]]
                 [k (try (run ast)
                         (catch #?(:cljd Object :clj Exception :cljs :default) e
                           [:thrown (ex-message e)]))]))
          runners)))


(defn- canonical?
  "True when `x` is in its canonical carrier on this host."
  [x]
  (let [c (f/call (f/module 100000 100000) 'normalize x)]
    (and (= c x) (= (host/big-carrier? c) (host/big-carrier? x)))))


(deftest evaluated-literals-test
  (testing "every non-negative S0 value as a decimal literal, printed: the
            S0 dec, hex and cbor columns, in the canonical carrier"
    (let [names (filterv #(not (str/starts-with? % "-")) f/int-names)
          tokens (mapv #(f/column @fixture % "dec") names)
          col #(f/column @fixture %1 %2)]
      (doseq [[k result] (run-everywhere @runners (print-program tokens))]
        (testing (str k)
          (let [values (first (:py/out result))]
            (is (= (count names) (count values)) (pr-str result))
            (is (= (str/join " " tokens)
                   (first (:py/out (render/output result)))))
            (doseq [[s v] (map vector names values)]
              (is (= [(col s "dec") (col s "hex") (col s "cbor") true]
                     [(render/repr v) (text-in v 16) (f/cbor-hex v)
                      (canonical? v)])
                  s))))))))


(deftest containers-test
  (testing "2^64 from a hex literal in a cell, a list, a tuple, a dict
            value and a dict key, returned and printed, with the S0 bytes"
    (let [d "18446744073709551616"
          cbor-hex (f/column @fixture "2^64" "cbor")]
      (doseq [[k result] (run-everywhere @runners
                                         (containers-program
                                           "0x1_0000_0000_0000_0000"))]
        (testing (str k)
          (is (= {:py/out [(str "[" d ", (" d ", 1), {1: " d "}, {" d
                                ": 2}]")],
                  :py/exception nil}
                 (render/output result)))
          (let [[x t dv dk] (-> result :py/out first first)]
            (is (= [cbor-hex cbor-hex]
                   [(f/cbor-hex x) (f/cbor-hex (first (:py/tuple t)))]))
            (is (= [cbor-hex cbor-hex]
                   [(f/cbor-hex (second (first (:py/dict dv))))
                    (f/cbor-hex (first (first (:py/dict dk))))]))))))))


(deftest huge-hex-test
  (testing "a 5000-digit hex literal runs under max-digits 4300 and prints
            its exact decimal (no digit limit on the snapshot in S2)"
    (let [hex (apply str (repeat 5000 "f"))
          n (f/call (f/module 100000 100000) 'parse hex 16)]
      (doseq [[k result] (run-everywhere @runners
                                         (print-program [(str "0x" hex)]))]
        (is (= {:py/out [(text-in n 10)], :py/exception nil}
               (render/output result))
            (str k)))))
  (testing "0x1 and 1100 zero digits (2^4400) under max-bits 4096: a
            catchable MemoryError"
    (let [token (apply str "0x1" (repeat 1100 "0"))]
      (doseq [[k result] (run-everywhere @runners-4096
                                         (caught-program token))]
        (is (= {:py/out ["caught"], :py/exception nil}
               (render/output result))
            (str k))))))


;; =============================================================================
;; The renderer
;; =============================================================================

(deftest renderer-prints-exact-integers-test
  (testing "every S0 value, big carriers included, renders its exact
            decimal on every host"
    (doseq [s f/int-names]
      (is (= (f/column @fixture s "dec") (render/repr (f/value s))) s)))
  (testing "inside containers too"
    (is (= "[18446744073709551616, (-18446744073709551617,)]"
           (render/repr [(f/value "2^64")
                         {:py/tuple [(f/value "-2^64-1")]}])))))


;; =============================================================================
;; Encodings and carriers
;; =============================================================================

(defn- decode-refusal
  [hex]
  (try (cbor/decode (cfx/hex->bytes hex))
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (cbor/refusal e))))


(deftest malformed-encodings-refuse-test
  (testing "Jing ingress refuses every non-canonical integer encoding"
    (doseq [[label hex refusal]
            [["tag 2 over a value that fits major type 0" "c24105"
              :non-canonical]
             ["tag 2 over 2^64 - 1" "c248ffffffffffffffff" :non-canonical]
             ["tag 3 over -2^64" "c348ffffffffffffffff" :non-canonical]
             ["a leading zero byte in a bignum" "c24a00010000000000000000"
              :non-canonical]
             ["an empty bignum" "c240" :non-canonical]
             ["a non-shortest head" "1b0000000000000005" :non-canonical]
             ["tag 2 over a non-byte-string" "c201" :malformed-number]
             ["trailing bytes" "0101" :trailing-data]]]
      (is (= refusal (decode-refusal hex)) label)))
  (testing "the canonical forms decode"
    (is (nil? (decode-refusal "05")))
    (is (nil? (decode-refusal "c249010000000000000000")))))


(deftest decoded-rows-are-canonical-carriers-test
  (testing "decoding each S0 cbor row gives the carrier integer/normalize
            gives on this host"
    (doseq [s f/int-names]
      (let [v (cbor/decode (cfx/hex->bytes (f/column @fixture s "cbor")))]
        (is (canonical? v) s)
        (is (= (f/value s) v) s)))))


(defn- reason-of
  [thunk]
  (::integer/reason (refusal-data thunk)))


(deftest wrong-carriers-stay-host-failures-test
  (let [m (f/module 100000 100000)
        ;; 2^53 as a double, built by exact doubling: on JS an unsafe Number
        big-double (reduce * 0.5 (repeat 54 2))
        neg-zero (* -0.5 0)]
    (testing "an unsafe double or -0 reaching a kernel is :wrong-type, never
              recovered into an exact integer"
      (is (= :wrong-type (reason-of #(f/call m 'add big-double 1))))
      (is (= :wrong-type (reason-of #(f/call m 'normalize big-double))))
      (is (= :wrong-type (reason-of #(f/call m 'add neg-zero 1))))
      (is (false? (f/call m 'integer? big-double)))
      (is (false? (f/call m 'integer? neg-zero))))))


(deftest integral-float-is-not-an-int-test
  (testing "a float is a float by tag: an integer-only operation raises
            TypeError through py/int?, nothing converts it"
    (let [two {:py/float (cbor/float64 2)}
          form (list 'py/conj
                     (list 'py/conj [] (list 'py/int? two))
                     (list 'py/try
                           (list 'fn [] (list 'py/invert two))
                           '(fn [e]
                              (get (cell/get (get (cell/get e) :class))
                                   :name))
                           '(fn [] :value)))
          ast (u/mark-tails (u/then prelude/uast (u/sexp->uast form)))]
      (doseq [[k run] @runners]
        (is (= [false "TypeError"]
               (try (run ast)
                    (catch #?(:cljd Object :clj Exception :cljs :default) e
                      [:thrown (ex-message e)])))
            (str k))))))


;; =============================================================================
;; Admission
;; =============================================================================

(deftest python-profile-admits-53-bits-or-more-test
  (testing "the Python profile refuses an integer module under 53 bits:
            every inline literal must fit"
    (is (= :yang.python.antlr/max-bits
           (:yang.python.antlr/refusal
             (refusal-data #(prelude/register-integer-module
                              (module/empty-registry)
                              {::integer/max-bits 52,
                               ::integer/max-digits 4300})))))
    (is (some? (prelude/register-integer-module
                 (module/empty-registry)
                 {::integer/max-bits 53, ::integer/max-digits 5})))))
