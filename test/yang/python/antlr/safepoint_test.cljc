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
            [yang.python.antlr.safepoint-programs :as programs]
            [yang.python.antlr.uast :as u]
            [yang.safepoint :as safepoint]
            [yang.tails :as tails]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.integer :as integer]
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
                (integer/register-integer-module
                  {::integer/max-bits 100000, ::integer/max-digits 4300})
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

(defn- no-op-hooks-are-transparent
  []
  (doseq [[label pk] [[:def-and-while def-and-while]]]
    (testing (str label)
      (let [expected (naive pk identity)]
        (every= [true false {:py/out ["5"], :py/exception nil}] expected)
        (every= (get expected :semantic) (derived pk hooks/noop-uast identity))))))


(deftest ^:slow no-op-hooks-are-transparent-test
  (slow/guard "no-op-hooks-are-transparent-test"
              no-op-hooks-are-transparent))


(defn- interrupt
  []
  (testing "while True: pass with one pre-appended signal ends with
            KeyboardInterrupt"
    (every= [true false {:py/out [], :py/exception {:type "KeyboardInterrupt",
                                                    :args []}}]
            (derived while-true-pass hooks/uast (with-signals [2]))))
  (testing "wrapped in try/except KeyboardInterrupt, the handler prints"
    (every= [true false {:py/out ["caught"], :py/exception nil}]
            (derived caught hooks/uast (with-signals [2])))))


(deftest ^:slow interrupt-test
  (slow/guard "interrupt-test"
              interrupt))


(defn- no-park
  []
  (testing "with an empty signal stream the derived program finishes without
            blocking and prints what the naive one prints"
    (every= [true false {:py/out ["5"], :py/exception nil}]
            (derived def-and-while hooks/uast (with-signals [])))))


(deftest no-park-test
  (slow/guard "no-park-test"
              no-park))


(defn- registered-handler-runs
  []
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


(deftest registered-handler-runs-test
  (slow/guard "registered-handler-runs-test"
              registered-handler-runs))


(defn- fail-closed
  []
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


(deftest fail-closed-test
  (slow/guard "fail-closed-test"
              fail-closed))


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
             [py.sp/return (fn [v] v)]
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


;; =============================================================================
;; Recursion (slice 2): depth accounting at :call and :return
;; =============================================================================

(defn- derived-ast
  [pk]
  (vm/semantic-bytecode->ast (tree-of (:derived (derive* pk hooks/profile)))))


(defn- with-run
  "`A'`, which is `(then prelude run)`, with its run replaced by `(f run)`."
  [a' f]
  (tails/remark-tails (update-in a' [:operator :body] f)))


(defn- hooked
  "Every VM's outcome for `ast` under the real hook prelude."
  [ast]
  (on-every-vm (hooks/program hooks/uast ast) (with-signals []) outcome))


(defn- recursion-error
  []
  (testing "the default limit, exactly: f(999) is 1000 frames and completes;
            f(1000) raises RecursionError, caught by except RecursionError;
            probe(1) finds the limit at 1000 frames"
    (every= [true false {:py/out ["0" "rec" "1000"], :py/exception nil}]
            (hooked (derived-ast programs/recursion)))))


(deftest ^:slow recursion-error-test
  (slow/guard "recursion-error-test"
              recursion-error))


(defn- limited
  "`A'` for `pk` run under recursion limit `n`, set before the module runs."
  [pk n]
  (with-run (derived-ast pk)
    #(u/then (u/sexp->uast (list 'py.sp/set-recursion-limit! n)) %)))


(defn- escape-restores-depth
  []
  (testing "under limit 100: a raise through 50 frames with finally restores
            the depth saved at the catching try, so probe finds 100 frames
            again, neither fewer (depth left high) nor more (depth left
            low), and 89 from 11 frames down. A finally thunk runs at its
            frame's depth: while a RecursionError unwinds, the finally of
            each of frames 1..99 calls h() within the limit; only frame
            100's call exceeds it"
    (every= [true false {:py/out ["unwound" "100" "89" "100" "99" "100"],
                         :py/exception nil}]
            (hooked (limited programs/unwind 100)))))


(deftest ^:slow escape-restores-depth-test
  (slow/guard "escape-restores-depth-test"
              escape-restores-depth))


(defn- generator-depth
  []
  (testing "under limit 100, depth measures the current continuation. A
            generator resumed 20 frames down runs on top of its resumer
            (its own frame is the 21st), so probe inside finds 79; resumed
            again from the top it is rebased to its new resumer, so probe
            finds 99. Every crossing back restores the caller's record, so
            probe at the top finds 100. gen2 is rebased from 21 to 1 between
            its yields and then raises and catches within one activation:
            the escape restores its snapshot relative to the current base,
            so probe finds 99, not 79"
    (every= [true false {:py/out ["79" "99" "100" "79" "100" "79" "99"
                                  "100"],
                         :py/exception nil}]
            (hooked (limited programs/generators 100)))))


(deftest ^:slow generator-depth-test
  (slow/guard "generator-depth-test"
              generator-depth))


(defn- generator-admission
  []
  (testing "under limit 100, starting or resuming a generator is admitted
            only when its frame fits: the call-free generator g, reached
            100 frames down, is refused at its start and later at a resume,
            even though its body calls nothing. A refusal leaves it as it
            was (created, then suspended) and the caller's depth whole: it
            starts from 11 frames down (2), resumes from 99 frames down,
            its frame the 100th (3), then from the top (4), and probe at
            the top still finds 100"
    (every= [true false {:py/out ["start refused" "2" "resume refused" "3" "4"
                                  "100"],
                         :py/exception nil}]
            (hooked (limited programs/admission 100)))))


(deftest ^:slow generator-admission-test
  (slow/guard "generator-admission-test"
              generator-admission))


(defn- with-limit-cell
  "`ast` (`(then prelude run)`) with the base prelude's limit cell set to
   `n` before the module runs: the one way to lower it with no hook
   prelude loaded."
  [ast n]
  (with-run ast #(u/then (u/sexp->uast (list 'cell/set! 'py.rt/limit n)) %)))


(defn- admission-in-every-mode
  []
  (testing "under limit 3, admission at generator crossings is the base
            prelude's, so a naive run, a run under no-op hooks and a run
            under the real hooks refuse the same crossing. next(via(2))
            nests via(2), via(1), via(0) as active generators, so starting
            t would make a 4th frame: refused, and the caller's try catches
            it. t stays created and installs nothing: next(via(1)) starts it
            at exactly the 3rd frame (1). Suspended, it is refused again
            the same way and keeps its state: resumed from the top it
            yields 2"
    (let [expected [true false {:py/out ["refused" "1" "refused" "2"],
                                :py/exception nil}]
          pk programs/nested-admission]
      (every= expected
              (on-every-vm (with-limit-cell (tree-of (envelope pk)) 3)
                           identity
                           outcome))
      (every= expected
              (on-every-vm (hooks/program hooks/noop-uast
                                          (with-limit-cell (derived-ast pk) 3))
                           identity
                           outcome))
      (every= expected (hooked (with-limit-cell (derived-ast pk) 3))))))


(deftest ^:slow admission-in-every-mode-test
  (slow/guard "admission-in-every-mode-test"
              admission-in-every-mode))


(defn- delegation-admission
  []
  (testing "under limit 100, through top -> mid -> leaf joined by yield
            from, each active generator is one frame: from the top, probe
            in leaf finds 97 (leaf is the 3rd frame), and from 20 frames
            down 77. Resumed 100 frames down the chain is refused at top's
            own admission, so every generator stays suspended and the next
            resume from 20 frames down finds 77 again. throw and close are
            refused at the limit the same way; from the top, throw reaches
            leaf, whose finally runs at the 3rd frame (97) before
            ValueError reaches the caller, and close likewise (97); probe at
            the top finds 100"
    (every= [true false {:py/out ["97" "77" "refused" "77" "throw refused" "97"
                                  "thrown" "97" "close refused" "97" "100"],
                         :py/exception nil}]
            (hooked (limited programs/delegation 100)))))


(deftest ^:slow delegation-admission-test
  (slow/guard "delegation-admission-test"
              delegation-admission))


(defn- generator-rebase
  []
  (testing "under limit 100: resumed first from the top (99), then 20
            frames down (79), then from the top again (99); an outer
            generator resuming an inner one counts both frames, from the
            top (98) and 20 frames down (78); a try entered 20 frames down
            and left normally after a resume from the top restores the
            current base (99, not 79); probe at the top finds 100"
    (every= [true false {:py/out ["99" "79" "99" "98" "78" "79" "99" "100"],
                         :py/exception nil}]
            (hooked (limited programs/rebase 100)))))


(deftest ^:slow generator-rebase-test
  (slow/guard "generator-rebase-test"
              generator-rebase))


(defn- generator-throw-close-depth
  []
  (testing "under limit 100: a generator suspended 20 frames down (79) and
            thrown into from the top runs its finally at the current base
            (99) before the exception reaches the caller; close from the
            top likewise (99); probe at the top finds 100"
    (every= [true false {:py/out ["79" "99" "thrown" "79" "99" "100"],
                         :py/exception nil}]
            (hooked (limited programs/throwclose 100)))))


(deftest ^:slow generator-throw-close-depth-test
  (slow/guard "generator-throw-close-depth-test"
              generator-throw-close-depth))


(defn- run-module-form
  "`(py/run-module (fn [g gf] body))`."
  [body]
  (list 'py/run-module (list 'fn '[g gf] body)))


(defn- set-recursion-limit
  []
  (testing "a lower limit takes effect"
    (every= [true false {:py/out ["50"], :py/exception nil}]
            (hooked (limited programs/probe 50))))
  (testing "the limit reads back: the default, then the one set"
    (every= [true false {:py/out ["1000" "50"], :py/exception nil}]
            (hooked
              (with-run (derived-ast programs/probe)
                (fn [_]
                  (u/sexp->uast
                    (run-module-form
                      '(do (py/print (py/conj [] (py.sp/recursion-limit)))
                           (py.sp/set-recursion-limit! 50)
                           (py/print
                             (py/conj [] (py.sp/recursion-limit)))))))))))
  (testing "an invalid argument raises the Python error and keeps the limit"
    (doseq [[arg error message]
            [[0 "ValueError"
              "recursion limit must be greater or equal than 1"]
             [-3 "ValueError"
              "recursion limit must be greater or equal than 1"]
             [{:py/str "50"} "TypeError" "an integer is required"]
             [:py/None "TypeError" "an integer is required"]]]
      (every= [true false {:py/out ["1000"],
                           :py/exception {:type error, :args [message]}}]
              (hooked
                (with-run (derived-ast programs/probe)
                  (fn [_]
                    (u/sexp->uast
                      (run-module-form
                        (list 'py/try
                              (list 'fn []
                                    (list 'py.sp/set-recursion-limit! arg))
                              '(fn [e]
                                 (do (py/print
                                       (py/conj [] (py.sp/recursion-limit)))
                                     (py/raise e)))
                              '(fn [] :py/None))))))))))
  (testing "a valid change persists across an escape: the limit is outside
            the escape-restored record"
    (every= [true false {:py/out ["50"], :py/exception nil}]
            (hooked
              (with-run (derived-ast programs/probe)
                (fn [_]
                  (u/sexp->uast
                    (run-module-form
                      '(py/try
                         (fn []
                           (do (py.sp/set-recursion-limit! 50)
                               (py/raise-new py.b/ValueError {:py/str "x"})))
                         (fn [e]
                           (py/print (py/conj [] (py.sp/recursion-limit))))
                         (fn [] :py/None)))))))))
  (testing "at depth 5, a limit of 5 or below raises RecursionError and keeps
            the old limit; 6 is accepted"
    (doseq [[n expected]
            [[5 {:py/out ["1000"],
                 :py/exception
                 {:type "RecursionError",
                  :args ["cannot set the recursion limit: the limit is too low"]}}]
             [3 {:py/out ["1000"],
                 :py/exception
                 {:type "RecursionError",
                  :args ["cannot set the recursion limit: the limit is too low"]}}]
             [6 {:py/out ["6"], :py/exception nil}]]]
      (every= [true false expected]
              (hooked
                (with-run (derived-ast programs/probe)
                  (fn [_]
                    (u/sexp->uast
                      (run-module-form
                        (list 'do
                              '(py.sp/call) '(py.sp/call) '(py.sp/call)
                              '(py.sp/call) '(py.sp/call)
                              (list 'py/try
                                    (list 'fn []
                                          (list 'py.sp/set-recursion-limit! n))
                                    '(fn [e]
                                       (do (py/print
                                             (py/conj []
                                                      (py.sp/recursion-limit)))
                                           (py/raise e)))
                                    '(fn []
                                       (py/print
                                         (py/conj []
                                                  (py.sp/recursion-limit)))))))))))))))


(deftest ^:slow set-recursion-limit-test
  (slow/guard "set-recursion-limit-test"
              set-recursion-limit))
