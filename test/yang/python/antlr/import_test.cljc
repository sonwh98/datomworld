(ns yang.python.antlr.import-test
  "C4 slices I1 and I2 (docs/design/yang.antlr.md 8.5.6): static absolute
   imports of single modules (I1); packages, mangling, dotted, aliased,
   from, relative and star imports (I2). The fast tests pin the lowering
   -- the hoisted
   requires, the statement-time py/import call, the refusals -- and the
   slow ones run the acceptance over `yang.python.antlr.linked-harness`:
   every guest module is published once per process beside `py`, and each
   run links over a source serving every name, so every test that
   publishes or links is slow."
  (:require
    [clojure.string :as str]
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


(defn- in-order
  "The nodes of map AST `ast` in evaluation order: an application after
   its operands and its operator, a lambda operator's body (run once the
   operands are bound) after the operands. `nodes` has no order."
  [ast]
  (letfn [(walk
            [n]
            (when (map? n)
              (case (:type n)
                :application (concat (mapcat walk (:operands n))
                                     (walk (:operator n))
                                     [n])
                :lambda (cons n (walk (:body n)))
                (cons n (mapcat walk
                                (filter map?
                                        (mapcat #(if (vector? %) % [%])
                                                (vals n))))))))]
    (walk ast)))


(defn- requires-of
  "The literal module names the linked program's hoisted `(require 'm)`
   applications name, in evaluation order."
  [ast]
  (vec (keep (fn [n]
               (when (and (= :application (:type n))
                          (= 'require (get-in n [:operator :name])))
                 (get-in n [:operands 0 :value])))
             (in-order ast))))


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


(defn- relative-from-stmt
  "The parser's `from <dots><tail> import <nme>` simple statement: one
   DOT token per level, `tail` nil for none."
  [level tail nme]
  [:import_stmt
   (-> [:import_from ["FROM" "from"]]
       (into (repeat level ["DOT" "."]))
       (into (when tail [[:dotted_name [:name ["NAME" tail]]]]))
       (conj ["IMPORT" "import"]
             [:import_as_names [:import_as_name [:name ["NAME" nme]]]]))])


(deftest i2-forms-need-the-linked-prelude-test
  (doseq [pk [programs/dotted-packet programs/aliased-packet
              programs/from-packet programs/dotted-main-packet]]
    (is (= "import under the bundled prelude"
           (:construct (refusal-of #(lower/lower-packet pk {}))))))
  (testing "a packageless relative import delivers nothing, yet is an I2
            form: the bundled profile refuses it by the statement, not by
            its requirements"
    (doseq [[level tail nme] [[1 nil "x"] [1 "m" "y"] [2 nil "z"]]]
      (let [pk (portable/packet
                 [:file_input (statement (relative-from-stmt level tail nme))
                  ["EOF" "<EOF>"]])]
        (is (= "import under the bundled prelude"
               (:construct (refusal-of #(lower/lower-packet pk {}))))
            (pr-str level tail nme))))))


;; -----------------------------------------------------------------------------
;; I2: packages, mangling, dotted, aliased, from, relative and star imports
;; -----------------------------------------------------------------------------

(defn- pym
  "The linker module symbol `s` names (a `$` is kept out of literals)."
  [s]
  (symbol s))


(defn- import-calls
  "Every py/import and py/import-sub application of `ast` as
   `[op python-name]`, in evaluation order."
  [ast]
  (keep (fn [n]
          (when (and (= :application (:type n))
                     (contains? '#{py/import py/import-sub}
                                (get-in n [:operator :name])))
            [(get-in n [:operator :name])
             (:py/str (get-in n [:operands 0 :value]))]))
        (in-order ast)))


(defn- ordered-call-sites
  "The applications of `op` in `ast`, in evaluation order."
  [ast op]
  (filter #(and (= :application (:type %))
                (= op (get-in % [:operator :name])))
          (in-order ast)))


(defn- main-body
  [program]
  (get-in (first (call-sites program 'py/run-main)) [:operands 0 :body]))


(deftest module-symbol-mangles-dotted-names-test
  (testing "8.5.6 ruling 3: `pym.` then the name with each dot a `$`, so
            no linker module name is a dotted prefix of another; an
            undotted name keeps I1's symbol"
    (is (= 'pym.m (lower/module-symbol "m")))
    (is (= (pym "pym.a$b$c") (lower/module-symbol "a.b.c")))
    (is (= ["pym" "a$b"] (str/split (str (lower/module-symbol "a.b")) #"\.")))))


(deftest dotted-import-hoists-and-imports-the-chain-test
  (let [program (lower/lower-packet programs/dotted-main-packet
                                    {:prelude :linked})
        calls (import-calls (main-body program))]
    (testing "import a.b.c delivers a, a.b and a.b.c, hoisted in order"
      (is (= ['py 'pym.a (pym "pym.a$b") (pym "pym.a$b$c")]
             (requires-of program))))
    (testing "the statement imports the chain in order, the root with
              py/import and each submodule with py/import-sub"
      (is (= [['py/import "a"] ['py/import-sub "a.b"]
              ['py/import-sub "a.b.c"]]
             (take 3 calls))))
    (testing "the root module object is bound to a"
      (is (some #(= {:py/str "a"} (get-in % [:operands 1 :value]))
                (call-sites (main-body program) 'py/global-set))))))


(deftest aliased-and-from-imports-bind-their-names-test
  (let [program (lower/lower-packet programs/forms-main-packet
                                    {:prelude :linked})
        body (main-body program)
        bound (set (keep #(get-in % [:operands 1 :value :py/str])
                         (call-sites body 'py/global-set)))]
    (testing "import a.b as x binds x; from-imports bind their as-names or
              names; nothing binds a for the aliased form alone"
      (is (every? bound ["x" "ww" "b" "bb" "a" "g"])))
    (testing "every from-import, and the alias's dotted component, reads
              its name through py/import-from"
      (is (= ["b" "w" "b" "b"]
             (mapv #(:py/str (get-in % [:operands 1 :value]))
                   (ordered-call-sites body 'py/import-from)))))
    (testing "from a import b hoists a only: no submodule auto-import"
      (is (= ['py 'pym.a (pym "pym.a$b") 'pym.g] (requires-of program))))
    (let [analysis (scope/analyze programs/forms-main-packet)
          module (get (:scopes analysis) (:module analysis))]
      (is (every? (set (:assigned module)) ["x" "ww" "b" "bb" "a" "g"])))))


(deftest dotted-alias-reads-each-component-as-an-attribute-test
  (testing "import a.b as x binds x as CPython's IMPORT_FROM chain does:
            b read off a through py/import-from (the attribute, else the
            sys.modules entry), not the module the chain loaded"
    (let [body (main-body (lower/lower-packet programs/alias-rebind-main-packet
                                              {:prelude :linked}))]
      (is (= ["b" "b"]
             (mapv #(:py/str (get-in % [:operands 1 :value]))
                   (ordered-call-sites body 'py/import-from)))))))


(deftest relative-imports-resolve-statically-test
  (testing "CPython's _resolve_name over the declared name and package-ness
            (8.5.6 ruling 12): a package is its own base, a module its
            parent; each extra dot drops one component"
    (doseq [[unit level tail expected]
            [[{:name "a.b", :package? true} 1 nil "a.b"]
             [{:name "a.b", :package? true} 2 "m" "a.m"]
             [{:name "a.b", :package? true} 3 nil :beyond]
             [{:name "a.b.c", :package? false} 1 "d" "a.b.d"]
             [{:name "a.b.c", :package? false} 2 nil "a"]
             [{:name "a.b.c", :package? false} 3 nil :beyond]
             [{:name "a", :package? true} 1 nil "a"]
             [{:name "g", :package? false} 1 nil :packageless]
             [nil 1 "m" :packageless]]]
      (is (= expected (lower/resolve-relative unit level tail))
          (pr-str unit level tail))))
  (testing "a.b.c's relative imports deliver a, a.b and a.b.d; the failed
            one (beyond the top level) delivers nothing and raises"
    (let [ast (lower/module-packet programs/i2-abc-packet {:name "a.b.c"})]
      (is (= ['py 'pym.a (pym "pym.a$b") (pym "pym.a$b$d")]
             (requires-of ast)))
      (is (= [{:py/str "attempted relative import beyond top-level package"}]
             (mapv #(get-in % [:operands 0 :value])
                   (call-sites ast 'py/import-fail))))))
  (testing "a packageless unit (__main__) requires nothing for its
            relative imports and raises at each executed statement"
    (let [program (lower/lower-packet programs/packageless-main-packet
                                      {:prelude :linked})]
      (is (= '[py] (requires-of program)))
      (is (= 3 (count (call-sites program 'py/import-fail))))
      (is (empty? (import-calls program)))))
  (testing "a package's own `from . import x` resolves to itself: the
            sys.modules entry answers, so it requires and names nothing
            of itself"
    (let [pk (portable/packet
               [:file_input
                (statement
                  [:import_stmt
                   [:import_from ["FROM" "from"] ["DOT" "."]
                    ["IMPORT" "import"]
                    [:import_as_names [:import_as_name
                                       [:name ["NAME" "x"]]]]]])
                ["EOF" "<EOF>"]])
          ast (lower/module-packet pk {:name "a", :package? true})]
      (is (= '[py] (requires-of ast)))
      (is (= {:name "a", :package? true}
             (get-in (second (:operands ast)) [:operands 1 :value])))
      (is (some #(= :py/None (get-in % [:operands 2 :value]))
                (call-sites ast 'py/import))))))


(deftest star-imports-lower-at-module-level-only-test
  (testing "from m import * copies into the module namespace"
    (let [body (main-body (lower/lower-packet programs/star-main-packet
                                              {:prelude :linked}))]
      (is (= 2 (count (call-sites body 'py/import-star))))))
  (testing "in a function it is CPython's syntax error"
    (let [refusal (refusal-of #(lower/lower-packet
                                 programs/star-in-function-packet
                                 {:prelude :linked}))]
      (is (= :yang.python.antlr/syntax
             (:yang.python.antlr/diagnostic refusal))))))


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
    (testing "a dotted module name is admitted (I2); a malformed one is
              refused, a `$` among them, which keeps the mangling
              injective"
      (is (= {:name "a.b", :package? false}
             (get-in (second (:operands (lower/module-packet
                                          programs/m-packet {:name "a.b"})))
                     [:operands 1 :value])))
      (doseq [bad ["" "a..b" ".a" "a." "a$b"]]
        (is (= :yang.python.antlr/unsupported
               (:yang.python.antlr/diagnostic
                 (refusal-of #(lower/module-packet programs/m-packet
                                                   {:name bad}))))
            bad)))))


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
   `pk` over `src` (default `guest-source`), or the throw."
  ([pk] (on-every-vm pk (guest-source)))
  ([pk src]
   (into {}
         (map (fn [k]
                [k (try (outcome
                          (h/run-linked
                            k (lower/lower-packet pk {:prelude :linked})
                            {:link-source src}))
                        (catch #?(:cljd Object :clj Exception :cljs :default) e
                          [:thrown (ex-message e)]))]))
         (keys h/backends))))


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


;; =============================================================================
;; The I2 acceptance: packages, dotted, aliased, from, relative, star
;; =============================================================================
;; Each expected output is CPython 3.9.6's for the same sources
;; (collab/1791580000000-engineer-c4-i2.oracle.py), the one disclosed
;; difference being `from nf import sub`: CPython would auto-import the
;; submodule, C4 raises the failed lookup's text (8.5.6 ruling 13).

(def ^:private published-i2-guests
  "The I2 guest modules, published bottom-up: `{python-name address}`.
   `a`, `a.b` and `nf` are packages."
  (delay
    (let [reg (h/registry)
          py (:address @h/published)
          publish (fn [acc nm pk package? deps]
                    (assoc acc nm
                           (:address
                             (h/publish-guest-module!
                               (lower/module-spec
                                 reg pk
                                 {:name nm,
                                  :package? package?,
                                  :py-address py,
                                  :deps (select-keys acc deps)})))))]
      (-> {}
          (publish "a" programs/i2-a-packet true [])
          (publish "a.b" programs/i2-ab-packet true [])
          (publish "a.b.d" programs/i2-abd-packet false [])
          (publish "a.b.c" programs/i2-abc-packet false ["a" "a.b" "a.b.d"])
          (publish "g" programs/i2-g-packet false [])
          (publish "sx" programs/i2-sx-packet false [])
          (publish "sy" programs/i2-sy-packet false [])
          (publish "nf" programs/i2-nf-packet true [])
          (publish "nf.sub" programs/i2-nfsub-packet false [])))))


(defn- i2-source
  "The serving composition over py and every published I2 guest; `nf.sub`
   is served but nothing requires it."
  []
  (h/source (into {'py (:address @h/published)}
                  (map (fn [[n a]] [(lower/module-symbol n) a]))
                  @published-i2-guests)))


(defn- dotted-import-runs-each-body-once-in-order-test
  []
  (testing "import a.b.c runs a, a.b, a.b.c once each, in order, and sets
            each as its parent's attribute; a second import re-runs none;
            a.b.c's relative imports resolve from its package a.b, the one
            past the top level raising ImportError"
    (every= [true false
             {:py/out ["a-body" "a.b-body" "a.b.c-body" "a.b.c a.b 2 1 3"
                       "attempted relative import beyond top-level package"
                       "a.b.c" "a.b a a.b" "3"],
              :py/exception nil}]
            (on-every-vm programs/dotted-main-packet (i2-source)))))


(deftest ^:slow dotted-import-runs-each-body-once-in-order-test-test
  (slow/guard "dotted-import-runs-each-body-once-in-order-test"
              dotted-import-runs-each-body-once-in-order-test))


(def ^:private no-parent
  "attempted relative import with no known parent package")


(defn- packageless-relative-import-raises-test
  []
  (testing "a relative import in a packageless unit raises ImportError at
            the executed statement, and except catches it"
    (every= [true false
             {:py/out [no-parent no-parent no-parent "after None"],
              :py/exception nil}]
            (on-every-vm programs/packageless-main-packet (i2-source)))))


(deftest ^:slow packageless-relative-import-raises-test-test
  (slow/guard "packageless-relative-import-raises-test"
              packageless-relative-import-raises-test))


(defn- from-package-import-unimported-submodule-raises-test
  []
  (testing "from nf import sub, with no explicit import of nf.sub and no
            attribute sub, raises the failed lookup's ImportError: no
            submodule auto-import"
    (every= [true false
             {:py/out ["nf-body"
                       "cannot import name 'sub' from 'nf' (unknown location)"
                       "cannot import name 'zz' from 'nf' (unknown location)"],
              :py/exception nil}]
            (on-every-vm programs/nosub-main-packet (i2-source)))))


(deftest ^:slow from-package-import-unimported-submodule-raises-test-test
  (slow/guard "from-package-import-unimported-submodule-raises-test"
              from-package-import-unimported-submodule-raises-test))


(defn- aliased-and-from-forms-test
  []
  (testing "import a.b as x binds a.b; from-imports read attributes, the
            submodule attribute import set included, and fall back to
            sys.modules after del a.b; a top-level module's __package__
            is ''"
    (every= [true false
             {:py/out ["a-body" "a.b-body" "a.b" "2" "a.b" "a.b" "g-body"
                       no-parent "g ''" "True"],
              :py/exception nil}]
            (on-every-vm programs/forms-main-packet (i2-source)))))


(deftest ^:slow aliased-and-from-forms-test-test
  (slow/guard "aliased-and-from-forms-test" aliased-and-from-forms-test))


(defn- star-import-test
  []
  (testing "from m import * honours __all__, else skips underscore names"
    (every= [true false
             {:py/out ["1 2" "name '_h' is not defined" "4"
                       "name 'u' is not defined"],
              :py/exception nil}]
            (on-every-vm programs/star-main-packet (i2-source)))))


(deftest ^:slow star-import-test-test
  (slow/guard "star-import-test" star-import-test))


(defn- dotted-alias-rebound-and-deleted-test
  []
  (testing "import a.b as x reads b off a: a rebound a.b is what x binds,
            and after del a.b the sys.modules entry answers"
    (every= [true false
             {:py/out ["a-body" "a.b-body" "42" "a.b"], :py/exception nil}]
            (on-every-vm programs/alias-rebind-main-packet (i2-source)))))


(deftest ^:slow dotted-alias-rebound-and-deleted-test-test
  (slow/guard "dotted-alias-rebound-and-deleted-test"
              dotted-alias-rebound-and-deleted-test))


(defn- from-import-reads-module-attributes-test
  []
  (testing "from sx import __dict__ binds the attribute py/getattr serves,
            though the module dict holds no __dict__ key"
    (every= [true false {:py/out ["1 sx"], :py/exception nil}]
            (on-every-vm programs/from-dict-main-packet (i2-source)))))


(deftest ^:slow from-import-reads-module-attributes-test-test
  (slow/guard "from-import-reads-module-attributes-test"
              from-import-reads-module-attributes-test))


(defn- star-import-names-must-be-str-test
  []
  (testing "a non-str in __all__, or a non-str key of the module dict with
            no __all__, is CPython's TypeError, the names before it
            copied"
    (every= [true false
             {:py/out ["Item in sy.__all__ must be str, not int"
                       "Key in sx.__dict__ must be str, not int" "1 2"],
              :py/exception nil}]
            (on-every-vm programs/star-bad-main-packet (i2-source)))))


(deftest ^:slow star-import-names-must-be-str-test-test
  (slow/guard "star-import-names-must-be-str-test"
              star-import-names-must-be-str-test))
