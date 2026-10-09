(ns yang.python.antlr.import-test
  "C4 slice I1 (docs/design/yang.antlr.md 8.5.6): static absolute imports
   of single modules. The fast tests pin the lowering -- the hoisted
   requires, the statement-time py/import call, the refusals -- and the
   slow ones run the acceptance over `yang.python.antlr.linked-harness`:
   every guest module is published once per process beside `py`, and each
   run links over a source serving every name, so every test that
   publishes or links is slow."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.test-slow :as slow]
    [yang.python.antlr.import-programs :as programs]
    [yang.python.antlr.linked-harness :as h]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.lower-portable-test :as portable]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.render :as render]
    [yang.python.antlr.scope :as scope]
    [yang.python.antlr.uast :as u]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.module :as module]))


;; =============================================================================
;; The fast tests: the lowering, before anything links
;; =============================================================================

(defn- nodes
  "Every node of map AST `ast`."
  [ast]
  (tree-seq map?
            (fn [n] (filter map? (mapcat #(if (vector? %) % [%]) (vals n))))
            ast))


(defn- requires-of
  "The literal module names the linked program's hoisted `(require 'm)`
   applications name, in order."
  [ast]
  (vec (keep (fn [n]
               (when (and (= :application (:type n))
                          (= 'require (get-in n [:operator :name])))
                 (get-in n [:operands 0 :value])))
             (nodes ast))))


(defn- call-sites
  "The applications of `op` in map AST `ast`."
  [ast op]
  (filter #(and (= :application (:type %))
                (= op (get-in % [:operator :name])))
          (nodes ast)))


(defn- def-key
  "The literal key of a `(yin/def k v)` application node, else nil."
  [node]
  (when (and (map? node)
             (= :application (:type node))
             (= 'yin/def (get-in node [:operator :name])))
    (get-in node [:operands 0 :value])))


(defn- refusal-of
  "The ex-data of what `thunk` throws, or nil when it returns."
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (or (ex-data e) {}))))


(defn- required-modules
  "The set of module names the program's hoisted requires name."
  [ast]
  (set (requires-of ast)))


(deftest import-lowers-at-the-statement-with-a-hoisted-require-test
  (let [program (lower/lower-packet programs/d-packet {:prelude :linked})
        body (get-in (first (call-sites program 'py/run-main))
                     [:operands 0 :body])]
    (testing "the wrapper requires py, then pym.m, then initializes"
      (is (= '#{py pym.m} (required-modules program))))
    (testing "the statement is (py/import m pym.m/spec pym.m/body) and
              the binding of the module object to the global m"
      (let [vars (set (keep #(when (= :variable (:type %)) (:name %))
                            (nodes body)))]
        (is (contains? vars 'pym.m/spec))
        (is (contains? vars 'pym.m/body))
        (is (some #(= {:type :literal, :value {:py/str "m"}}
                      (get-in % [:operands 0]))
                  (call-sites body 'py/import)))
        (is (some #(= {:py/str "m"} (get-in % [:operands 1 :value]))
                  (call-sites body 'py/global-set)))))))


(deftest preseeded-imports-carry-no-delivery-test
  (let [program (lower/lower-packet programs/s-packet {:prelude :linked})
        body (get-in (first (call-sites program 'py/run-main))
                     [:operands 0 :body])]
    (testing "sys is pre-seeded: no pym.sys require, spec and body are None"
      (is (= '#{py pym.m} (required-modules program)))
      (is (some #(and (= {:type :literal, :value {:py/str "sys"}}
                         (get-in % [:operands 0]))
                      (= :py/None (get-in % [:operands 1 :value]))
                      (= :py/None (get-in % [:operands 2 :value])))
                (call-sites body 'py/import))))))


(def ^:private expr-chain
  "The single-child chain from `test` down to `atom` (linked-prelude's)."
  [:testlist_star_expr :test :or_test :and_test :not_test :comparison :expr
   :atom_expr :atom])


(defn- chain
  "Leaf wrapped in one level per rule of `rules`."
  [rules leaf]
  (reduce (fn [inner r] [r inner]) leaf (reverse rules)))


(defn- statement
  "One simple statement `s` as a file statement."
  [s]
  [:stmt [:simple_stmts [:simple_stmt s] ["NEWLINE" "\n"]]])


(defn- import-simple-stmt
  "The parser's `import m` simple statement, import-programs' shape."
  [nme]
  [:import_stmt
   [:import_name
    ["IMPORT" "import"]
    [:dotted_as_names
     [:dotted_as_name [:dotted_name [:name ["NAME" nme]]]]]]])


(deftest an-import-free-program-tree-is-unchanged-test
  (let [plain (portable/packet
                [:file_input
                 (statement [:expr_stmt
                             (chain expr-chain [:name ["NAME" "x"]])
                             ["ASSIGN" "="]
                             (chain expr-chain ["NUMBER" "1"])])
                 ["EOF" "<EOF>"]])
        program (lower/lower-packet plain {:prelude :linked})]
    (testing "no import, no extra require and no import call: the P2
              wrapper exactly, so its program-root golden does not move"
      (is (= '[py] (requires-of program)))
      (is (empty? (call-sites program 'py/import)))
      (is (nil? (vm/ast-reserved-defect program))))))


(deftest imports-need-the-linked-prelude-test
  (testing "a bundled unit importing a compiled module is refused, naming
            it; the pre-seeded sys needs no delivery and lowers bundled"
    (let [refusal (refusal-of #(lower/lower-packet programs/d-packet {}))]
      (is (= :yang.python.antlr/unsupported
             (:yang.python.antlr/diagnostic refusal)))
      (is (= "import under the bundled prelude" (:construct refusal))))
    (let [sys-only (portable/packet
                     [:file_input (statement (import-simple-stmt "sys"))
                      ["EOF" "<EOF>"]])
          program (lower/lower-packet sys-only {})]
      (is (empty? (required-modules program)))
      (is (some #(and (= {:type :literal, :value {:py/str "sys"}}
                         (get-in % [:operands 0]))
                      (= :py/None (get-in % [:operands 1 :value])))
                (call-sites program 'py/import))))))


(deftest i2-forms-are-refused-naming-the-construct-test
  (doseq [[pk construct] [[programs/dotted-packet "dotted import"]
                          [programs/aliased-packet "aliased import"]
                          [programs/from-packet "from import"]]]
    (testing construct
      (is (= construct
             (:construct (refusal-of
                           #(lower/lower-packet pk {:prelude :linked}))))))))


(deftest import-binds-the-imported-name-in-its-scope-test
  (testing "module level: m is an assigned global"
    (let [analysis (scope/analyze programs/d-packet)
          module (get (:scopes analysis) (:module analysis))]
      (is (contains? (set (:assigned module)) "m"))))
  (testing "an import in a function binds a local, and its require is
            still hoisted for the unit"
    (let [pk (portable/packet
               [:file_input
                [:stmt [:compound_stmt
                        [:funcdef ["DEF" "def"] [:name ["NAME" "f"]]
                         [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
                         ["COLON" ":"]
                         [:block ["NEWLINE" "\n"] ["INDENT" "    "]
                          (statement (import-simple-stmt "m"))
                          ["DEDENT" "<EOF>"]]]]]
                ["EOF" "<EOF>"]])
          analysis (scope/analyze pk)
          f-scope (some (fn [s] (when (contains? (set (:locals s)) "m") s))
                        (vals (:scopes analysis)))
          program (lower/lower-packet pk {:prelude :linked})]
      (is (some? f-scope) "m is a local of f")
      (is (= :cell (:kind (scope/resolve analysis (:id f-scope) "m")))
          "read inside f reaches the cell")
      (is (= '#{py pym.m} (required-modules program))))))


(deftest del-lowers-to-delattr-and-a-strict-unbind-test
  (testing "del m.y is a delattr of the module attribute"
    (let [body (lower/lower-module-body programs/del-packet)]
      (is (some (fn [n]
                  (and (= :application (:type n))
                       (= 'py/delattr (get-in n [:operator :name]))
                       (= "y" (get-in n [:operands 1 :value]))))
                (nodes body)))))
  (testing "del x uses strict py/global-del (NameError when unbound in module)"
    (let [pk (portable/packet
               [:file_input
                (statement [:del_stmt ["DEL" "del"]
                            [:exprlist
                             (chain [:expr :atom_expr :atom]
                                    [:name ["NAME" "x"]])]])
                ["EOF" "<EOF>"]])
          body (lower/lower-module-body pk)
          ops (set (map #(get-in % [:operator :name])
                        (filter #(= :application (:type %)) (nodes body))))]
      (is (contains? ops 'py/global-del))))
  (testing "scope collects del targets as local cells in function scope"
    (let [pk (portable/packet
               [:file_input
                [:stmt [:compound_stmt
                        [:funcdef ["DEF" "def"] [:name ["NAME" "f"]]
                         [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
                         ["COLON" ":"]
                         [:block ["NEWLINE" "\n"] ["INDENT" "    "]
                          (statement [:del_stmt ["DEL" "del"]
                                      [:exprlist
                                       (chain [:expr :atom_expr :atom]
                                              [:name ["NAME" "x"]])]])
                          ["DEDENT" "<EOF>"]]]]]
                ["EOF" "<EOF>"]])
          analysis (scope/analyze pk)
          f-scope (some (fn [s] (when (contains? (set (:locals s)) "x") s))
                        (vals (:scopes analysis)))]
      (is (some? f-scope) "x is treated as a local cell of f")
      (is (= :cell (:kind (scope/resolve analysis (:id f-scope) "x")))))))


;; =============================================================================
;; The module emitter
;; =============================================================================

(def ^:private stand-in-address
  "A stand-in manifest address: the spec builder only pins it, and no fast
   test may force a publication."
  :test/address)


(deftest module-packet-is-one-wide-application-test
  (let [ast (lower/module-packet programs/m-packet {:name "m"})
        operands (:operands ast)
        op (:operator ast)]
    (testing "one application: the py require, one per static import,
              then the spec and body definitions"
      (is (= :application (:type ast)))
      (is (= :lambda (:type op)))
      (is (= (count operands) (count (:params op))))
      (is (nil? (get-in op [:body :value])))
      (is (= '[py] (requires-of ast)))
      (is (= '[spec body] (mapv def-key (drop 1 operands)))))
    (testing "the spec is the literal module record; the body is the same
              %globals form the main wrapper runs"
      (is (= {:name "m", :package? false}
             (get-in (second operands) [:operands 1 :value])))
      (is (= '[%globals %globals-fn]
             (get-in (nth operands 2) [:operands 1 :params])))
      (is (= '#{spec body} lower/module-exports)))
    (testing "Rule R holds over the module tree"
      (is (nil? (vm/ast-reserved-defect ast))))))


(deftest module-packet-of-a-unit-with-imports-requires-them-test
  (let [ast (lower/module-packet programs/t-packet {:name "t"})]
    (is (= '#{py pym.k} (required-modules ast)))
    (is (contains? (set (keep #(when (= :variable (:type %)) (:name %))
                              (nodes ast)))
                   'pym.k/spec))
    (testing "a dotted module name is I2's"
      (is (= :yang.python.antlr/unsupported
             (:yang.python.antlr/diagnostic
               (refusal-of #(lower/module-packet programs/m-packet
                                                 {:name "a.b"}))))))))


(deftest module-spec-test
  (let [spec (lower/module-spec (h/registry) programs/t-packet
                                {:name "t",
                                 :py-address stand-in-address,
                                 :deps {"k" :test/k}})]
    (testing "the linker module pym.t requires py and each import pinned"
      (is (= 'pym.t (:name spec)))
      (is (= '{py :test/address, pym.k :test/k} (:requires spec)))
      (is (= '#{spec body} (:exports spec)))
      (is (= (lower/module-packet programs/t-packet {:name "t"})
             (:ast spec))))
    (testing "a py- or pym.k-qualified free name is never a primitive
              declaration: the requirements cover it"
      (is (empty? (filter #(contains? #{"py" "pym.k"} (namespace %))
                          (keys (:primitives spec))))))
    (testing "a free name no registry module or primitive supplies is
              refused before anything is published"
      (let [no-cell (-> (module/empty-registry)
                        (data/register-data-module
                          {::data/max-items 1048576})
                        (prelude/register-integer-module h/integer-limits))
            refusal (refusal-of
                      ;; m's functions allocate cells, so its tree reads
                      ;; cell names no cell module supplies
                      #(lower/module-spec no-cell programs/m-packet
                                          {:name "m",
                                           :py-address stand-in-address,
                                           :deps {}}))]
        (is (= :yang.python.antlr/undeclared-free
               (:yang.python.antlr/refusal refusal)))
        (is (= "cell" (namespace (:name refusal))))))))


;; =============================================================================
;; The linked acceptance: every guest module published beside py
;; =============================================================================

(def ^:private published-guests
  "Every guest module the acceptance imports, published once per process
   into `py`'s own store, imports bottom-up: `{name address}`."
  (delay
    (let [reg (h/registry)
          py (:address @h/published)
          publish (fn [name pk deps]
                    [name (:address
                            (h/publish-guest-module!
                              (lower/module-spec reg pk
                                                 {:name name,
                                                  :py-address py,
                                                  :deps deps})))])
          [_ k-a] (publish "k" programs/k-packet {})
          [_ m-a] (publish "m" programs/m-packet {})
          [_ t-a] (publish "t" programs/t-packet {"k" k-a})
          [_ w-a] (publish "w" programs/w-packet {})
          [_ e-a] (publish "e" programs/e-packet {})
          [_ n-a] (publish "n" programs/n-packet {})]
      {:k k-a, :m m-a, :t t-a, :w w-a, :e e-a, :n n-a})))


(defn- guest-source
  "The serving composition over py and every published guest module."
  []
  (let [a @published-guests]
    (h/source (into {'py (:address @h/published)}
                    (map (fn [[n a]] [(lower/module-symbol (name n)) a]))
                    a))))


(defn- outcome
  "A finished run as `[halted? blocked? rendered-value]`."
  [task]
  [(vm/halted? task) (vm/blocked? task) (render/output (vm/value task))])


(defn- on-every-vm
  "Every vector VM's `[halted? blocked? output]` for the linked program of
   `pk`, or the throw."
  [pk]
  (into {}
        (map (fn [k]
               [k (try (outcome
                         (h/run-linked
                           k (lower/lower-packet pk {:prelude :linked})
                           {:link-source (guest-source)}))
                       (catch #?(:cljd Object :clj Exception :cljs :default) e
                         [:thrown (ex-message e)]))]))
        (keys h/backends)))


(defn- every=
  [expected results]
  (doseq [[k result] results]
    (is (= expected result) (str k))))


(defn- import-orders-statements-test
  []
  (testing "print(\"a\"); import m; print(\"b\") orders a, m's output, b"
    (every= [true false {:py/out ["a" "m-body" "b"], :py/exception nil}]
            (on-every-vm programs/o-packet))))


(deftest ^:slow import-orders-statements-test-test
  (slow/guard "import-orders-statements-test" import-orders-statements-test))


(defn- second-import-does-not-re-execute-test
  []
  (testing "a second import does not re-execute the body: the sys.modules
            hit answers; `import m; x = m.val; print(x)` reads 7 through
            the module object"
    (every= [true false {:py/out ["m-body" "done"], :py/exception nil}]
            (on-every-vm programs/q-packet))
    (every= [true false {:py/out ["m-body" "7"], :py/exception nil}]
            (on-every-vm programs/d-packet))))


(deftest ^:slow second-import-does-not-re-execute-test-test
  (slow/guard "second-import-does-not-re-execute-test"
              second-import-does-not-re-execute-test))


(defn- module-dict-mutations-are-shared-test
  []
  (testing "m.val = 5 and del m.y reach m's dict: m.__dict__ shows them
            and m's own functions read them"
    (every= [true false {:py/out ["m-body" "5" "absent" "('gone', 5)"],
                         :py/exception nil}]
            (on-every-vm programs/c-packet))))


(deftest ^:slow module-dict-mutations-are-shared-test-test
  (slow/guard "module-dict-mutations-are-shared-test"
              module-dict-mutations-are-shared-test))


(defn- raising-body-removes-its-entry-test
  []
  (testing "a raising body propagates to the importer's handler and
            removes its sys.modules entry -- the second attempt runs the
            body again"
    (every= [true false {:py/out ["w-once" "cleaned" "w-once" "again"],
                         :py/exception nil}]
            (on-every-vm programs/r-packet))))


(deftest ^:slow raising-body-removes-its-entry-test-test
  (slow/guard "raising-body-removes-its-entry-test"
              raising-body-removes-its-entry-test))


(defn- importer-catches-imported-function-exception-test
  []
  (testing "a handler in the importer catches an exception a function of
            the imported module raises, on every VM -- cross-module raise
            through the module boundary"
    (every= [true false {:py/out ["caught-e"], :py/exception nil}]
            (on-every-vm programs/x-packet))))


(deftest ^:slow importer-catches-imported-function-exception-test-test
  (slow/guard "importer-catches-imported-function-exception-test"
              importer-catches-imported-function-exception-test))


(defn- one-image-two-names-test
  []
  (testing "pym.n's own body, the one export the import runs, prints its
            module name when imported"
    (every= [true false {:py/out ["n"], :py/exception nil}]
            (on-every-vm programs/nn-packet)))
  (testing "the same body value run by py/run-main prints __main__: one
            code image, two roles"
    (let [main (u/mark-tails
                 (u/seq-nodes
                   [(u/sexp->uast '(require (quote py)))
                    (u/sexp->uast '(require (quote pym.n)))
                    (u/sexp->uast '(py/init!))
                    (u/app (u/v 'py/run-main) (u/v 'pym.n/body))]))]
      (every= [true false {:py/out ["__main__"], :py/exception nil}]
              (into {}
                    (map (fn [k]
                           [k (try (outcome (h/run-linked
                                              k main
                                              {:link-source (guest-source)}))
                                   (catch #?(:cljd Object
                                             :clj Exception
                                             :cljs :default) e
                                     [:thrown (ex-message e)]))]))
                    (keys h/backends))))))


(deftest ^:slow one-image-two-names-test-test
  (slow/guard "one-image-two-names-test" one-image-two-names-test))


(defn- preseeded-modules-resolve-test
  []
  (testing "import sys needs no linker module, and sys.modules holds the
            imported module and sys itself"
    (every= [true false {:py/out ["m-body" "True" "True"], :py/exception nil}]
            (on-every-vm programs/s-packet))))


(deftest ^:slow preseeded-modules-resolve-test-test
  (slow/guard "preseeded-modules-resolve-test" preseeded-modules-resolve-test))


(defn- transitive-import-delivers-test
  []
  (testing "t imports k: k's body runs once inside t's own import, and t
            reads k's attribute -- the module's install child delivers
            its own imports"
    (every= [true false {:py/out ["k-body" "t-sees 3" "t-done"],
                         :py/exception nil}]
            (on-every-vm programs/tt-packet))))


(deftest ^:slow transitive-import-delivers-test-test
  (slow/guard "transitive-import-delivers-test" transitive-import-delivers-test))
