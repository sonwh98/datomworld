(ns yang.python.antlr.safepoint-test
  "Safepoint slice 1 for Python on all four VMs and every host this runs on
   (JVM, Node, Dart): the lowering's site marks reach the side table, the
   `yang.safepoint` stage derives `A'` without touching `A`, and the hook
   prelude delivers signals. Programs come from hand-built CST packets (the
   parser is JVM-only); their node ids are the parser's, so this is the
   real lowering's output. Topology: lowering envelope -> encoder projection
   -> safepoint stage -> the VM runs the hook prelude, then `A'`."
  (:require [dao.test-slow :as slow]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [yang.python.antlr.lower :as lower]
            [yang.python.antlr.lower-portable-test :refer [packet]]
            [yang.python.antlr.render :as render]
            [yang.python.antlr.safepoint :as hooks]
            [yang.python.antlr.uast :as u]
            [yang.safepoint :as safepoint]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.encoder :as encoder]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Programs: parser CSTs as nested forms
;; =============================================================================

(defn- test-chain
  "`test` down to `atom-expr`, a single-child chain."
  [atom-expr]
  [:test [:or_test [:and_test [:not_test [:comparison [:expr atom-expr]]]]]])


(defn- atom-of
  [x]
  [:atom_expr [:atom x]])


(defn- nm
  [s]
  [:name ["NAME" s]])


(defn- call1
  "`f(arg)` as an `atom_expr`."
  [f arg]
  [:atom_expr [:atom (nm f)]
   [:trailer ["OPEN_PAREN" "("]
    [:arglist [:argument (test-chain (atom-of arg))]]
    ["CLOSE_PAREN" ")"]]])


(def ^:private while-true-pass
  "while True:\n    pass\n"
  (packet
    [:file_input
     [:stmt [:compound_stmt
             [:while_stmt ["WHILE" "while"] (test-chain (atom-of ["TRUE" "True"]))
              ["COLON" ":"]
              [:block ["NEWLINE" "\n"] ["INDENT" "    "]
               [:stmt [:simple_stmts [:simple_stmt [:pass_stmt ["PASS" "pass"]]]
                       ["NEWLINE" "\n"]]]
               ["DEDENT" "<EOF>"]]]]]
     ["EOF" "<EOF>"]]))


(def ^:private caught
  "try:\n    while True:\n        pass\nexcept KeyboardInterrupt:\n
   print('caught')\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:try_stmt ["TRY" "try"] ["COLON" ":"]
        [:block ["NEWLINE" "\n"] ["INDENT" "    "]
         [:stmt [:compound_stmt
                 [:while_stmt ["WHILE" "while"] (test-chain (atom-of ["TRUE" "True"]))
                  ["COLON" ":"]
                  [:block ["NEWLINE" "\n"] ["INDENT" "        "]
                   [:stmt [:simple_stmts [:simple_stmt [:pass_stmt ["PASS" "pass"]]]
                           ["NEWLINE" "\n"]]]
                   ["DEDENT" ""]]]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (test-chain (atom-of (nm "KeyboardInterrupt")))]
        ["COLON" ":"]
        [:block ["NEWLINE" "\n"] ["INDENT" "    "]
         [:stmt [:simple_stmts
                 [:simple_stmt [:expr_stmt [:testlist_star_expr
                                            (test-chain (call1 "print" ["STRING" "'caught'"]))]]]
                 ["NEWLINE" "\n"]]]
         ["DEDENT" "<EOF>"]]]]]
     ["EOF" "<EOF>"]]))


(defn- assign
  [target value-expr]
  [:stmt [:simple_stmts
          [:simple_stmt [:expr_stmt [:testlist_star_expr (test-chain (atom-of (nm target)))]
                         ["ASSIGN" "="]
                         [:testlist_star_expr (test-chain value-expr)]]]
          ["NEWLINE" "\n"]]])


(def ^:private def-and-while
  "def f(n):\n    return n + 1\ni = 0\nwhile i < 5:\n    i = f(i)\n
   print(i)\n"
  (packet
    [:file_input
     [:stmt [:compound_stmt
             [:funcdef ["DEF" "def"] (nm "f")
              [:parameters ["OPEN_PAREN" "("] [:typedargslist [:tfpdef (nm "n")]]
               ["CLOSE_PAREN" ")"]]
              ["COLON" ":"]
              [:block ["NEWLINE" "\n"] ["INDENT" "    "]
               [:stmt [:simple_stmts
                       [:simple_stmt
                        [:flow_stmt
                         [:return_stmt ["RETURN" "return"]
                          [:testlist
                           [:test [:or_test [:and_test [:not_test
                                                        [:comparison
                                                         [:expr [:expr (atom-of (nm "n"))]
                                                          ["ADD" "+"]
                                                          [:expr (atom-of ["NUMBER" "1"])]]]]]]]]]]]
                       ["NEWLINE" "\n"]]]
               ["DEDENT" ""]]]]]
     (assign "i" (atom-of ["NUMBER" "0"]))
     [:stmt [:compound_stmt
             [:while_stmt ["WHILE" "while"]
              [:test [:or_test [:and_test [:not_test
                                           [:comparison [:expr (atom-of (nm "i"))]
                                            [:comp_op ["LESS_THAN" "<"]]
                                            [:expr (atom-of ["NUMBER" "5"])]]]]]]
              ["COLON" ":"]
              [:block ["NEWLINE" "\n"] ["INDENT" "    "]
               (assign "i" (call1 "f" (nm "i")))
               ["DEDENT" ""]]]]]
     [:stmt [:simple_stmts
             [:simple_stmt [:expr_stmt [:testlist_star_expr
                                        (test-chain (call1 "print" (nm "i")))]]]
             ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def ^:private programs
  {:while-true-pass while-true-pass, :caught caught, :def-and-while def-and-while})


;; =============================================================================
;; The composition
;; =============================================================================

(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                data/register-data-module
                module/register-stream-module)})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private runners
  "Each takes an AST and `prep`, applied to the VM before it runs, and
   answers the VM `run` left."
  {:ast-walker (fn [ast prep] (vm/eval (prep (tu/create-vm opts)) ast)),
   :semantic (fn [ast prep]
               (vm/run (load-semantic-ast (prep (semantic/create-vm opts))
                                          (vm/ast->datoms ast)))),
   :stack (fn [ast prep]
            (vm/run (prep (dvm/create-vm
                            (:image (dl/adapt (vm/ast->datoms ast)))
                            (assoc opts :contract vm/stack-contract))))),
   :register (fn [ast prep]
               (vm/run (prep (rvm/create-vm
                               (:image (rc/adapt (vm/ast->datoms ast)))
                               (assoc opts :contract vm/register-contract)))))})


(defn- on-every-vm
  "`{vm-key (f halted-or-blocked-vm)}`; a throw becomes `[:thrown message]`."
  [ast prep f]
  (into {}
        (map (fn [[k run]]
               [k (try (f (run ast prep))
                       (catch #?(:cljd Object :clj Exception :cljs :default) e
                         [:thrown (ex-message e)]))]))
        runners))


(defn- with-signals
  "A `prep` handing the task a signal stream holding `signals`."
  [signals]
  (fn [vm]
    (let [handle (tu/new-stream 8)]
      (doseq [s signals] (stream/append! handle s))
      (let [[ref vm] (engine/attach-resource vm handle)]
        (assoc-in vm [:store hooks/signals-key] ref)))))


(defn- envelope
  "The lowering's program batch for `pk`."
  [pk]
  (let [[_ [[port e]]] (lower/lower-transform {} pk)]
    (assert (= :program port))
    e))


(defn- derive*
  "`{:projected p :derived e' :record r}` for `pk` under `profile`."
  [pk profile]
  (let [projected (encoder/project (envelope pk))
        {:keys [envelope record]} (safepoint/derive-envelope projected profile
                                                             :test/derived)]
    {:projected projected, :derived envelope, :record record}))


(defn- tree-of
  [e]
  (nth (:yin/batch e) (:yin/root e)))


(defn- outcome
  "A finished run as `[halted? blocked? rendered-value]`."
  [vm]
  [(vm/halted? vm) (vm/blocked? vm) (render/output (vm/value vm))])


(defn- naive
  [pk prep]
  (on-every-vm (tree-of (envelope pk)) prep outcome))


(defn- derived
  [pk hook-prelude prep]
  (on-every-vm (hooks/program hook-prelude
                              (vm/semantic-bytecode->ast
                                (tree-of (:derived (derive* pk hooks/profile)))))
               prep
               outcome))


(defn- every=
  [expected results]
  (doseq [[k result] results]
    (is (= expected result) (str k))))


;; =============================================================================
;; Marks
;; =============================================================================

(declare nodes)


(deftest marks-reach-the-side-table-only-test
  (let [{:keys [projected]} (derive* def-and-while hooks/profile)
        tree (tree-of projected)
        marks (filter #(= :yang/site (nth % 3)) (:yin/frontend-metadata projected))]
    (testing "the function's code lambda and the loop lambda are marked"
      (is (= [:call :loop] (sort (map #(nth % 4) marks)))))
    (testing "marks are metadata: the rows are those of the unmarked tree"
      (letfn [(unmark
                [node]
                (reduce (fn [n [field kind]]
                          (case kind
                            :node (update n field unmark)
                            :nodes (update n field #(mapv unmark %))
                            n))
                        (vary-meta node dissoc :yang/site)
                        (get vm/semantic-bytecode-grammar (:type node))))]
        (let [marked (tree-of (envelope def-and-while))]
          (is (some #(:yang/site (meta %)) (nodes marked)))
          (is (not-any? #(:yang/site (meta %)) (nodes (unmark marked))))
          (is (= tree (vm/ast->semantic-bytecode (unmark marked)))))))))


;; =============================================================================
;; The stage
;; =============================================================================

(deftest empty-profile-is-the-identity-test
  (doseq [[label pk] programs]
    (let [{:keys [projected derived record]} (derive* pk {})]
      (is (= (tree-of projected) (tree-of derived)) (str label))
      (is (= (:yin.ledger/input record) (:yin.ledger/output record)) (str label)))))


(deftest canonical-untouched-test
  (let [rows (tu/new-memory-log)
        out (tu/new-memory-log)
        ledger (tu/new-memory-log)
        projected (encoder/project (envelope def-and-while))
        ;; the encoder projects what it forwards
        _ (encoder/load rows (envelope def-and-while))
        before (tu/drain rows)
        _ (safepoint/step-stage
            (safepoint/open-stage rows out ledger hooks/profile :test/derived))
        [derived] (tu/drain out)
        [record] (tu/drain ledger)
        a (tree-of projected)
        a' (tree-of derived)
        ;; the root is `(then prelude run)`: its one operand is the prelude
        prelude-id (fn [{:keys [root rows]}] (first (nth (get rows root) 3)))]
    (testing "the input batch is unchanged"
      (is (= before (tu/drain rows) [projected])))
    (testing "every derived row validates"
      (is (nil? (vm/validate-rows a'))))
    (testing "the bundled prelude keeps its id: no site is under it"
      (is (= (prelude-id a) (prelude-id a')))
      (is (contains? (:rows a') (prelude-id a))))
    (testing "the record links A to A' under the stage's function"
      (is (= {:yin.ledger/op :derive,
              :yin.ledger/input (:root a),
              :yin.ledger/output (:root a'),
              :yin.ledger/function :yang.safepoint/insert}
             (dissoc record :yin.ledger/profile)))
      (is (= hooks/profile
             (get-in record [:yin.ledger/profile :yang.safepoint/hooks]))))
    (testing "A' leaves on the stage's medium under the record's address"
      (is (= [:test/derived 0] ((juxt :yin/source-medium :yin/root) derived))))))


(deftest insertion-is-deterministic-test
  (doseq [[label pk] programs]
    (is (= (derive* pk hooks/profile) (derive* pk hooks/profile)) (str label))))


(defn- nodes
  "Every node of map AST `ast`."
  [ast]
  (tree-seq map?
            (fn [n] (filter map? (mapcat #(if (vector? %) % [%]) (vals n))))
            ast))


(defn- definitions
  "Every `(yin/def k v)` application in `ast`."
  [ast]
  (filter #(and (= :application (:type %))
                (= 'yin/def (:name (:operator %))))
          (nodes ast)))


(defn- names-in
  [ast]
  (set (keep #(when (= :variable (:type %)) (:name %)) (nodes ast))))


(deftest no-hook-under-a-prelude-definition-test
  (let [ast (vm/semantic-bytecode->ast (tree-of (:derived (derive* def-and-while hooks/profile))))
        hook-names (set (vals hooks/profile))
        defs (definitions ast)]
    (is (seq defs))
    (is (some hook-names (names-in ast)) "hooks were inserted")
    (doseq [d defs]
      (is (empty? (filter hook-names (names-in (second (:operands d)))))
          (str (:value (first (:operands d))))))))


;; =============================================================================
;; Running A'
;; =============================================================================

(deftest no-op-hooks-are-transparent-test
  (doseq [[label pk] [[:def-and-while def-and-while]]]
    (testing (str label)
      (let [expected (naive pk identity)]
        (every= [true false {:py/out ["5"], :py/exception nil}] expected)
        (every= (get expected :semantic) (derived pk hooks/noop-uast identity))))))


(deftest interrupt-test
  (testing "while True: pass with one pre-appended signal ends with
            KeyboardInterrupt"
    (every= [true false {:py/out [], :py/exception {:type "KeyboardInterrupt",
                                                    :args []}}]
            (derived while-true-pass hooks/uast (with-signals [2]))))
  (testing "wrapped in try/except KeyboardInterrupt, the handler prints"
    (every= [true false {:py/out ["caught"], :py/exception nil}]
            (derived caught hooks/uast (with-signals [2])))))


(deftest no-park-test
  (testing "with an empty signal stream the derived program finishes without
            blocking and prints what the naive one prints"
    (every= [true false {:py/out ["5"], :py/exception nil}]
            (derived def-and-while hooks/uast (with-signals [])))))


(deftest registered-handler-runs-test
  (testing "a registered Python handler is called with the signal and None,
            and the program goes on"
    ;; registered before the base prelude loads, so the function object is
    ;; built from host operations, in `py/make-function`'s shape
    (let [set-handler (u/sexp->uast
                        '(py.sp/set-handler!
                           (cell/new
                             (assoc (assoc (assoc (assoc (assoc (assoc {} :py/type :function)
                                                                :name "h")
                                                         :spec {:params ["s" "f"]})
                                                  :defaults [])
                                           :kwdefaults [])
                                    :code (fn [args] (py/print (py/conj [] (py/arg args 0))))))))
          a' (vm/semantic-bytecode->ast
               (tree-of (:derived (derive* def-and-while hooks/profile))))]
      (every= [true false {:py/out ["7" "5"], :py/exception nil}]
              (on-every-vm (hooks/program (u/then hooks/uast set-handler) a')
                           (with-signals [7])
                           outcome)))))


(deftest fail-closed-test
  (testing "the derived program without the hook prelude names the hook"
    (every= [:thrown "Unable to resolve symbol: py.sp/loop in this context"]
            (on-every-vm (vm/semantic-bytecode->ast
                           (tree-of (:derived (derive* while-true-pass hooks/profile))))
                         identity
                         outcome)))
  (testing "the naive program reads KeyboardInterrupt as a builtin its own
            base prelude defines, and names nothing in py.sp"
    (let [ast (tree-of (envelope caught))]
      (is (contains? (names-in ast) 'py.b/KeyboardInterrupt))
      (is (some #(= 'py.b/KeyboardInterrupt (:value (first (:operands %))))
                (definitions ast)))
      (is (not-any? #(= "py.sp" (namespace %)) (filter symbol? (names-in ast)))))))


(deftest hook-prelude-defines-only-py-sp-test
  (doseq [[label hook-prelude] [[:uast hooks/uast] [:noop-uast hooks/noop-uast]]]
    (let [ks (map #(:value (first (:operands %))) (definitions hook-prelude))]
      (is (seq ks) (str label))
      (is (every? #(= "py.sp" (namespace %)) ks) (str label (pr-str ks))))))


(deftest hook-prelude-loads-alone-test
  (testing "with only the signal stream and no base prelude, the hook prelude
            loads: it allocates a cell and a cursor and calls nothing else"
    (doseq [[k result] (on-every-vm hooks/uast (with-signals [])
                                    (fn [vm] [(vm/halted? vm) (vm/blocked? vm)]))]
      (is (= [true false] result) (str k)))))


;; =============================================================================
;; Tail preservation
;; =============================================================================

(def ^:private parking-hooks
  "A `:loop` hook that counts and, at safepoint `py.sp/park-at`, reads an
   empty stream with `next!`, which parks: the continuation at that point is
   what the loop has grown."
  (u/mark-tails
    (u/seq-nodes
      (map (fn [[k form]] (u/def! k (u/sexp->uast form)))
           '[[py.sp/loop
              (fn []
                (let [n (+ 1 (cell/get py.sp/count))]
                  (do (cell/set! py.sp/count n)
                      (if (= n py.sp/park-at) (stream/next! py.sp/cursor) :py/None))))]
             [py.sp/call (fn [] :py/None)]
             [py.sp/count (cell/new 0)]
             [py.sp/cursor (stream/cursor py.sp/signals)]]))))


(defn- depth
  "Frames in a parked wait entry's continuation, whatever the VM's shape."
  [vm]
  (let [entry (first (:wait-set vm))]
    (cond
      (contains? entry :continuation) (count (:continuation entry))
      (vector? (:k entry)) (count (:k entry))
      :else (count (take-while some? (iterate :next (:k entry)))))))


(deftest ^:slow tail-preservation-test
  (slow/guard "tail-preservation-test"
              (fn []
                (testing "a safepointed loop grows no continuation: parked at its 10th and
            its 100,000th safepoint, every VM holds the same frames"
                  (let [a' (vm/semantic-bytecode->ast
                             (tree-of (:derived (derive* while-true-pass hooks/profile))))
                        at (fn [n]
                             (on-every-vm (hooks/program parking-hooks a')
                                          (fn [vm]
                                            (assoc-in ((with-signals []) vm) [:store 'py.sp/park-at] n))
                                          (fn [vm] [(vm/blocked? vm) (depth vm)])))
                        shallow (at 10)
                        deep (at 100000)]
                    (doseq [[k [blocked? d]] shallow]
                      (is (true? blocked?) (str k))
                      (is (pos? d) (str k)))
                    (is (= shallow deep)))))))
