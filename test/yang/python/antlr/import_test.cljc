(ns yang.python.antlr.import-test
  "C4 slices I1, I2, I4-a and I6 (docs/design/yang.antlr.md 8.5.6): static
   absolute imports of single modules (I1); packages, mangling, dotted,
   aliased, from, relative and star imports (I2); statement-time delivery
   and catchable linker refusals (I4-a); literal import_module, retained
   image reload and import-hook refusals (I6). The fast tests pin the lowering
   -- eager py delivery, statement-time guest delivery, the refusals -- and the
   slow ones run the acceptance over `yang.python.antlr.linked-harness`:
   every guest module is published once per process beside `py`, and each
   run links over a source serving every name, so every test that
   publishes or links is slow."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.test-slow :as slow]
    [dao.stream :as stream]
    [yang.python.antlr.import-programs :as programs]
    [yang.python.antlr.linked-harness :as h]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.lower-portable-test :as portable]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.publication :as publication]
    [yang.python.antlr.render :as render]
    [yang.python.antlr.scope :as scope]
    [yang.python.antlr.uast :as u]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.module :as module]
    [yin.vm.linker.interpreter :as link]))


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


(deftest import-lowers-at-the-statement-with-a-delivery-thunk-test
  (let [program (lower/lower-packet programs/d-packet {:prelude :linked})
        body (get-in (first (call-sites program 'py/run-main))
                     [:operands 0 :body])]
    (testing "the wrapper requires the call and py modules, then initializes"
      (is (= '#{yang.call py} (required-modules program))))
    (testing "the statement passes a delivery thunk to py/import and
              the binding of the module object to the global m"
      (let [vars (set (keep #(when (= :variable (:type %)) (:name %))
                            (nodes body)))]
        (is (contains? vars 'pym.m/spec))
        (is (contains? vars 'pym.m/body))
        (let [delivery (get-in (first (call-sites body 'py/import)) [:operands 1])
              branch (:body (:operator (:body delivery)))]
          (is (= :lambda (:type delivery)))
          (is (= ['pym.m]
                 (mapv #(get-in % [:operands 0 :value])
                       (call-sites delivery 'try-require))))
          (is (= :if (:type branch)))
          (is (= '#{pym.m/spec pym.m/body}
                 (set (filter #(= "pym.m" (namespace %))
                              (keep :name (nodes (:consequent branch)))))))
          (is (= {:type :variable, :name 'r} (:alternate branch))))
        (is (some #(= {:type :literal, :value {:py/str "m"}}
                      (get-in % [:operands 0]))
                  (call-sites body 'py/import)))
        (is (some #(= {:py/str "m"} (get-in % [:operands 1 :value]))
                  (call-sites body 'py/global-set)))))))


(deftest preseeded-imports-carry-no-delivery-test
  (let [program (lower/lower-packet programs/s-packet {:prelude :linked})
        body (get-in (first (call-sites program 'py/run-main))
                     [:operands 0 :body])]
    (testing "sys is pre-seeded: delivery is None"
      (is (= '#{yang.call py} (required-modules program)))
      (is (some #(and (= {:type :literal, :value {:py/str "sys"}}
                         (get-in % [:operands 0]))
                      (= :py/None (get-in % [:operands 1 :value])))
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
      (is (= '[yang.call py] (requires-of program)))
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


(deftest dotted-import-delivers-and-imports-the-chain-test
  (let [program (lower/lower-packet programs/dotted-main-packet
                                    {:prelude :linked})
        calls (import-calls (main-body program))]
    (testing "import a.b.c requires the call ABI and py, with no guest module eagerly required"
      (is (= '[yang.call py]
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
    (testing "from a import b has no eager guest require or submodule auto-import"
      (is (= '[yang.call py] (requires-of program))))
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
      (is (= '[py]
             (requires-of ast)))
      (is (= [{:py/str "attempted relative import beyond top-level package"}]
             (mapv #(get-in % [:operands 0 :value])
                   (call-sites ast 'py/import-fail))))))
  (testing "a packageless unit (__main__) requires nothing for its
            relative imports and raises at each executed statement"
    (let [program (lower/lower-packet programs/packageless-main-packet
                                      {:prelude :linked})]
      (is (= '[yang.call py] (requires-of program)))
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
      (is (some #(= :py/None (get-in % [:operands 1 :value]))
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
  (testing "an import in a function binds a local and delivers when executed"
    (let [pk (portable/packet
               [:file_input
                [:stmt [:compound_stmt
                        [:funcdef ["DEF" "def"] [:name ["NAME" "f"]]
                         [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
                         ["COLON" ":"]
                         [:block ["NEWLINE" "\n"] ["INDENT" "    "]
                          (statement (import-simple-stmt "m"))
                          ["DEDENT" ""]]]]]
                ["EOF" "<EOF>"]])
          analysis (scope/analyze pk)
          f-scope (some (fn [s] (when (contains? (set (:locals s)) "m") s))
                        (vals (:scopes analysis)))
          program (lower/lower-packet pk {:prelude :linked})]
      (is (some? f-scope) "m is a local of f")
      (is (= :cell (:kind (scope/resolve analysis (:id f-scope) "m")))
          "read inside f reaches the cell")
      (is (= '#{yang.call py} (required-modules program))))))


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
                          ["DEDENT" ""]]]]]
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
    (is (= '#{py} (required-modules ast)))
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
      (is (= (vm/profile-of vm/primitives 'try-require)
             (get-in spec [:primitives 'try-require])))
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


(defn- star-import-all-is-indexed-test
  []
  (testing "__all__ is read by index, as CPython's PySequence_GetItem
            reads it, until IndexError: a generator, a set and a dict are
            each CPython 3.9.6's TypeError; a tuple copies each item
            before it reads the next, so r is bound before 3 raises"
    (every= [true false
             {:py/out ["'generator' object does not support indexing"
                       "'set' object does not support indexing"
                       "dict is not a sequence"
                       "Item in sy.__all__ must be str, not int" "4"],
              :py/exception nil}]
            (on-every-vm programs/star-all-main-packet (i2-source)))))


(deftest ^:slow star-import-all-is-indexed-test-test
  (slow/guard "star-import-all-is-indexed-test"
              star-import-all-is-indexed-test))


(defn- star-import-all-range-test
  []
  (testing "a range __all__ is a sequence: an empty one copies nothing,
            a nonempty one reaches the item check with an int, as
            CPython 3.9.6"
    (every= [true false
             {:py/out ["r unbound" "Item in sy.__all__ must be str, not int"],
              :py/exception nil}]
            (on-every-vm programs/star-range-main-packet (i2-source)))))


(deftest ^:slow star-import-all-range-test-test
  (slow/guard "star-import-all-range-test" star-import-all-range-test))


;; =============================================================================
;; I4-a: delivery refusals are catchable at the import statement
;; =============================================================================

(defn- retry-packet
  "I1's two try/import statements, with the handler class replaced."
  [exception]
  (update programs/r-packet :yang.cst/nodes
          (fn [ns]
            (mapv (fn [n]
                    (if (and (= :token (:kind n))
                             (= "ValueError" (:text n)))
                      (assoc n :text exception)
                      n))
                  ns))))


(defn- run-refused
  "Load py from its real publisher, then answer each guest link request
   with refusal data. The responder counts retries without host redefs."
  [backend ast refusal]
  (let [pair (link/make-pair)
        initial ((get h/backends backend)
                 (u/sexp->uast '(require (quote py)))
                 (h/composition (h/registry) pair))
        [loaded pair] (h/drive initial pair (h/source))
        task ((get h/continues backend) loaded ast)]
    (loop [task (vm/run task)
           cursor (:cursor pair)
           requests []
           rounds 0]
      (if (and (vm/blocked? task) (< rounds 10))
        (let [read (stream/next (:requests pair) cursor)]
          (is (= :dao.stream/ok (:dao.stream/outcome read)))
          (let [request (:dao.stream/value read)]
            (stream/append! (:responses pair)
                            (assoc refusal :yin.link/id (:yin.link/id request)))
            (recur (vm/run task) (:dao.stream/cursor read)
                   (conj requests request) (inc rounds))))
        [task requests]))))


(defn- catchable-delivery-refusals-test
  []
  (doseq [backend (keys h/backends)
          [refusal exception]
          [[{:status :refused, :reason :absent, :name 'pym.w}
            "ModuleNotFoundError"]
           [{:status :refused, :reason :absent, :name 'pym.w}
            "ImportError"]
           [{:status :refused, :reason :absent, :address :test/missing}
            "ImportError"]
           [{:status :refused, :reason :dependency-pin-mismatch,
             :expected :test/pin, :actual :test/other}
            "ImportError"]
           [{:status :refused, :reason :untrusted-principal,
             :principal "test-principal"}
            "ImportError"]
           [{:status :refused, :reason :ambiguous-name, :name 'pym.w}
            "ImportError"]]]
    (testing (str backend " " refusal " caught by " exception)
      (let [[done requests]
            (run-refused backend
                         (lower/lower-packet (retry-packet exception)
                                             {:prelude :linked})
                         refusal)]
        (is (= [true false {:py/out ["cleaned" "again"], :py/exception nil}]
               (outcome done)))
        (is (= ['pym.w 'pym.w] (mapv :yin.link/name requests))
            "failed delivery leaves no cache entry; the next import retries")))))


(deftest ^:slow catchable-delivery-refusals-test-test
  (slow/guard "catchable-delivery-refusals-test"
              catchable-delivery-refusals-test))


(defn- refusal-details-test
  []
  (doseq [backend (keys h/backends)
          refusal [{:status :refused, :reason :absent, :name 'pym.w}
                   {:status :refused, :reason :absent, :address :test/missing}
                   {:status :refused, :reason :untrusted-principal}
                   {:status :refused, :reason :dependency-pin-mismatch}
                   {:status :refused, :reason :ambiguous-name}
                   {:status :refused, :reason :require-cycle}
                   {:status :lost}]]
    (let [program
          (u/mark-tails
            (u/sexp->uast
              '(do (py/init!)
                   (py/try
                     (fn []
                       (py/import {:py/str "w"}
                                  (fn [] (try-require (quote pym.w)))))
                     (fn [e]
                       (conj [] (py/isinstance e (py/global-get (py/dict-new)
                                                                {:py/str "ModuleNotFoundError"}))
                             (py/isinstance e (py/global-get (py/dict-new)
                                                             {:py/str "ImportError"}))
                             (py/getattr e "refusal")
                             (py/getattr e "args")
                             (= :py/missing (py/modules-ref {:py/str "w"}))))
                     (fn [] :py/None)))))
          [done requests] (run-refused backend program refusal)
          [missing? import? diagnostics args uncached?] (vm/value done)]
      (is (vm/halted? done) (str backend))
      (is (= (and (= :absent (:reason refusal)) (contains? refusal :name))
             missing?))
      (is (= true import?))
      (is (= refusal (dissoc diagnostics :yin.link/id :module :link-id)))
      (when (= :lost (:status refusal))
        (is (contains? diagnostics :link-id)))
      (when missing?
        (is (= {:py/str "No module named 'w'"} (first (:items args)))))
      (is (= true uncached?))
      (is (= 1 (count requests))))))


(deftest ^:slow refusal-details-test-test
  (slow/guard "refusal-details-test" refusal-details-test))


(defn- cached-import-skips-delivery-test
  []
  (let [program (u/mark-tails
                  (u/sexp->uast
                    '(do (require (quote py))
                         (py/init!)
                         (let [name {:py/str "cached"}
                               parent (py/module-new {:py/str "parent"}
                                                     {:py/str "parent"})
                               m (py/module-new name {:py/str ""})
                               delivery (fn []
                                          (py/raise-new (py/global-get (py/dict-new)
                                                                       {:py/str "ValueError"})
                                                        {:py/str "called"}))]
                           (do (py/modules-put! name m)
                               (conj []
                                     (= m (py/import name delivery))
                                     (= m (py/import-sub name delivery parent
                                                         {:py/str "child"}))
                                     (= :py/missing
                                        (py/dict-ref (py/module-dict parent)
                                                     {:py/str "child"}))))))))]
    (doseq [backend (keys h/backends)]
      (is (= [true true true]
             (vm/value (h/run-linked backend program)))
          (str backend)))))


(deftest ^:slow cached-import-skips-delivery-test-test
  (slow/guard "cached-import-skips-delivery-test"
              cached-import-skips-delivery-test))


(defn- optional-dependency-does-not-abort-install-test
  []
  (let [py (:address @h/published)
        spec (lower/module-spec (h/registry) (retry-packet "ModuleNotFoundError")
                                {:name "optional", :py-address py,
                                 :deps {"w" (:w @published-guests)}})
        published (h/publish-guest-module! spec)
        pk (portable/packet [:file_input
                             (statement (import-simple-stmt "optional"))
                             ["EOF" "<EOF>"]])
        src (h/source {'py py, 'pym.optional (:address published)})]
    (testing "a concretely pinned dependency absent from the reader does
              not abort module installation; its body catches and retries"
      (every= [true false {:py/out ["cleaned" "again"], :py/exception nil}]
              (on-every-vm pk src)))))


(deftest ^:slow optional-dependency-does-not-abort-install-test-test
  (slow/guard "optional-dependency-does-not-abort-install-test"
              optional-dependency-does-not-abort-install-test))


;; I5 fixtures use the same portable CST constructors as the earlier tests.
(defn- i5-assignment
  [n value]
  (statement [:expr_stmt (chain expr-chain [:name ["NAME" n]])
              ["ASSIGN" "="] (chain expr-chain ["NUMBER" (str value)])]))


(defn- i5-print-attributes
  [pairs]
  (statement
    [:expr_stmt
     (chain (take 7 expr-chain)
            [:atom_expr [:atom [:name ["NAME" "print"]]]
             [:trailer ["OPEN_PAREN" "("]
              (into [:arglist]
                    (interpose ["COMMA" ","]
                               (map (fn [[m n]]
                                      [:argument
                                       (chain (take 6 (rest expr-chain))
                                              (into [:atom_expr [:atom [:name ["NAME" m]]]]
                                                    (map (fn [attr] [:trailer ["DOT" "."] [:name ["NAME" attr]]])
                                                         (str/split n #"\."))))]) pairs)))
              ["CLOSE_PAREN" ")"]]])]))


(defn- i5-packet
  [statements]
  (portable/packet (into [:file_input] (conj (vec statements) ["EOF" "<EOF>"]))))


(defn- i5-unit
  [n statements]
  {:name n, :packet (i5-packet statements)})


(defn- i5-source
  [units]
  (let [result (publication/publish-units! (:store @h/published) (h/registry)
                                           (:address @h/published) units)]
    (is (= :ok (:status result)) (pr-str result))
    (h/source (merge {'py (:address @h/published)}
                     (into {} (map (fn [[n a]] [(lower/module-symbol n) a])) (:addresses result))
                     (:bundles result)))))


(deftest ^:slow mutually-importing-modules-test
  (slow/guard "mutually-importing-modules"
              (fn []
                (let [units [(i5-unit "a" [(i5-assignment "x" 1)
                                           (statement (import-simple-stmt "b"))])
                             (i5-unit "b" [(i5-assignment "x" 2)
                                           (statement (import-simple-stmt "a"))])]
                      source (i5-source units)]
                  (doseq [order [["a" "b"] ["b" "a"]]]
                    (every= [true false {:py/out ["1 2 2 1"], :py/exception nil}]
                            (on-every-vm
                              (i5-packet (concat (map #(statement (import-simple-stmt %)) order)
                                                 [(i5-print-attributes [["a" "x"] ["b" "x"]
                                                                        ["a" "b.x"] ["b" "a.x"]])]))
                              source)))))))


(deftest ^:slow cyclic-partial-initialization-test
  (slow/guard "cyclic-partial-initialization"
              (fn []
                (let [source (i5-source
                               [(i5-unit "a" [(statement (relative-from-stmt 0 "b" "x"))])
                                (i5-unit "b" [(statement (import-simple-stmt "a"))
                                              (i5-assignment "x" 2)])])]
                  (every= [true false
                           {:py/out [],
                            :py/exception {:type "ImportError",
                                           :args ["cannot import name 'x' from partially initialized module 'b' (most likely due to a circular import)"]}}]
                          (on-every-vm (i5-packet [(statement (import-simple-stmt "b"))]) source))))))


(deftest ^:slow three-member-cycle-test
  (slow/guard "three-member-cycle"
              (fn []
                (let [source (i5-source
                               [(i5-unit "a" [(statement (import-simple-stmt "b")) (i5-assignment "x" 1)])
                                (i5-unit "b" [(statement (import-simple-stmt "c")) (i5-assignment "x" 2)])
                                (i5-unit "c" [(statement (import-simple-stmt "a")) (i5-assignment "x" 3)])])]
                  (every= [true false {:py/out ["1 2 3"], :py/exception nil}]
                          (on-every-vm
                            (i5-packet [(statement (import-simple-stmt "a"))
                                        (statement (import-simple-stmt "b"))
                                        (statement (import-simple-stmt "c"))
                                        (i5-print-attributes [["a" "x"] ["b" "x"] ["c" "x"]])]) source))))))


(deftest i5-fixtures-and-initialization-lowering-test
  (let [a (i5-unit "a" [(statement (relative-from-stmt 0 "b" "x"))])
        b (i5-unit "b" [(statement (import-simple-stmt "a")) (i5-assignment "x" 2)])
        [bundle] (lower/scc-module-specs (h/registry) [a b] :test/py {})
        main (lower/lower-packet
               (i5-packet [(statement (import-simple-stmt "a"))
                           (i5-print-attributes [["a" "b.x"]])]) {:prelude :linked})]
    (is (= ["b"] (lower/imported-module-names (:packet a))))
    (is (= ["a"] (lower/imported-module-names (:packet b))))
    (is (= 2 (count (call-sites (:ast bundle) 'py/try-finally))))
    (is (= 1 (count (call-sites (:ast bundle) 'py/import-fail))))
    (is (= #{true false}
           (set (keep #(when (= :yang.python.antlr/initializing
                                (get-in % [:operands 1 :value]))
                         (get-in % [:operands 2 :value]))
                      (call-sites (:ast bundle) 'assoc)))))
    (is (= 2 (count (call-sites main 'py/getattr))))
    (is (nil? (vm/ast-reserved-defect main)))))


(deftest ^:slow cyclic-from-import-present-name-test
  (slow/guard "cyclic-from-import-present-name"
              (fn []
                (let [source (i5-source
                               [(i5-unit "a" [(i5-assignment "x" 1)
                                              (statement (import-simple-stmt "b"))])
                                (i5-unit "b" [(statement (relative-from-stmt 0 "a" "x"))])])]
                  (doseq [order [["a" "b"] ["b" "a"]]]
                    (every= [true false {:py/out ["1 1"], :py/exception nil}]
                            (on-every-vm
                              (i5-packet (concat (map #(statement (import-simple-stmt %)) order)
                                                 [(i5-print-attributes [["a" "x"] ["b" "x"]])]))
                              source)))))))


(deftest ^:slow cyclic-from-import-after-initialization-test
  (slow/guard "cyclic-from-import-after-initialization"
              (fn []
                (let [print-message
                      (statement
                        [:expr_stmt
                         (chain (take 7 expr-chain)
                                [:atom_expr [:atom [:name ["NAME" "print"]]]
                                 [:trailer ["OPEN_PAREN" "("]
                                  [:arglist
                                   [:argument
                                    (chain (take 6 (rest expr-chain))
                                           [:atom_expr [:atom [:name ["NAME" "error"]]]
                                            [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                                            [:trailer ["OPEN_BRACK" "["]
                                             [:subscriptlist
                                              [:subscript_ (chain (rest expr-chain) ["NUMBER" "0"])]]
                                             ["CLOSE_BRACK" "]"]]])]]
                                  ["CLOSE_PAREN" ")"]]])])
                      caught-import
                      [:stmt [:compound_stmt
                              [:try_stmt ["TRY" "try"] ["COLON" ":"]
                               [:block ["NEWLINE" "\n"] ["INDENT" "    "]
                                (statement (relative-from-stmt 0 "b" "missing")) ["DEDENT" ""]]
                               [:except_clause ["EXCEPT" "except"]
                                (chain (rest expr-chain) [:name ["NAME" "ImportError"]])
                                ["AS" "as"] [:name ["NAME" "error"]]]
                               ["COLON" ":"]
                               [:block ["NEWLINE" "\n"] ["INDENT" "    "] print-message ["DEDENT" ""]]]]]
                      ;; a imports b to completion, then checks its missing
                      ;; name inside a bundled body, where the flag matters.
                      source (i5-source
                               [(i5-unit "a" [(statement (import-simple-stmt "b")) caught-import])
                                (i5-unit "b" [(statement (import-simple-stmt "a"))])])]
                  (every= [true false
                           {:py/out ["cannot import name 'missing' from 'b' (unknown location)"],
                            :py/exception nil}]
                          (on-every-vm (i5-packet [(statement (import-simple-stmt "a"))]) source))))))


;; I6 packets stay portable: no parser is needed on Node or Dart.
(defn- import-module-packet
  ([argument]
   (import-module-packet argument (import-simple-stmt "importlib")
                         [:atom_expr [:atom [:name ["NAME" "importlib"]]]
                          [:trailer ["DOT" "."] [:name ["NAME" "import_module"]]]]))
  ([argument import-stmt callee]
   (portable/packet
     [:file_input
      (statement import-stmt)
      (statement
        [:expr_stmt
         (chain (butlast expr-chain)
                (conj callee
                      [:trailer ["OPEN_PAREN" "("]
                       [:arglist [:argument (chain (rest (butlast expr-chain))
                                                   [:atom_expr [:atom argument]])]]
                       ["CLOSE_PAREN" ")"]]))])
      ["EOF" "<EOF>"]])))


(deftest literal-import-module-delivery-test
  (let [pk (import-module-packet ["STRING" "\"a.b\""])
        ast (lower/lower-packet pk {:prelude :linked})]
    (is (= ["a" "a.b"] (lower/imported-module-names pk)))
    (is (= [['py/import "importlib"] ['py/import "a"]
            ['py/import-sub "a.b"]]
           (vec (import-calls (main-body ast)))))
    (is (= 1 (count (call-sites ast 'py/import-module?))))
    (is (= '[yang.call py] (requires-of ast)))
    (is (nil? (vm/ast-reserved-defect ast)))))


(deftest dynamic-import-module-has-no-delivery-test
  (let [pk (import-module-packet [:name ["NAME" "dynamic"]])
        ast (lower/lower-packet pk {:prelude :linked})]
    (is (empty? (lower/imported-module-names pk)))
    (is (empty? (call-sites ast 'try-require)))
    (is (seq (call-sites ast 'py/call)))))


(deftest imported-import-module-aliases-test
  (doseq [[import-stmt callee]
          [[[:import_stmt
             [:import_name ["IMPORT" "import"]
              [:dotted_as_names
               [:dotted_as_name [:dotted_name [:name ["NAME" "importlib"]]]
                ["AS" "as"] [:name ["NAME" "il"]]]]]]
            [:atom_expr [:atom [:name ["NAME" "il"]]]
             [:trailer ["DOT" "."] [:name ["NAME" "import_module"]]]]]
           [[:import_stmt
             [:import_from ["FROM" "from"]
              [:dotted_name [:name ["NAME" "importlib"]]] ["IMPORT" "import"]
              [:import_as_names [:import_as_name [:name ["NAME" "import_module"]]
                                 ["AS" "as"] [:name ["NAME" "im"]]]]]]
            [:atom_expr [:atom [:name ["NAME" "im"]]]]]]]
    (let [pk (import-module-packet ["STRING" "\"m\""] import-stmt callee)
          ast (lower/lower-packet pk {:prelude :linked})]
      (is (= ["m"] (lower/imported-module-names pk)))
      (is (= 1 (count (call-sites ast 'py/import-module?)))))))


(deftest import-module-recognition-boundaries-test
  (let [pk (import-module-packet ["STRING" "\"m\""])
        unrelated (update pk :yang.cst/nodes
                          (fn [ns]
                            (mapv #(if (and (= :token (:kind %))
                                            (= "importlib" (:text %)))
                                     (assoc % :text "unrelated") %) ns)))]
    (is (= "import under the bundled prelude"
           (:construct (refusal-of #(lower/lower-packet pk {})))))
    (is (= ["unrelated"] (lower/imported-module-names unrelated)))
    (is (empty? (call-sites (lower/lower-packet unrelated {:prelude :linked})
                            'py/import-module?)))))


(defn- i6-runtime-test
  []
  (let [ast (u/mark-tails
              (u/sexp->uast
                '(do (require (quote py))
                     (py/init!)
                     (let [body (fn [d gf]
                                  (do (py/dict-set d {:py/str "x"}
                                                   (+ (if (= (py/dict-ref d {:py/str "x"}) :py/missing)
                                                        0 (py/dict-ref d {:py/str "x"})) 1))
                                      (if (= (py/dict-ref d {:py/str "fail"}) true)
                                        (py/raise-new (py/global-get d {:py/str "ValueError"})
                                                      {:py/str "reload failed"})
                                        :py/None)))
                           delivery (fn [] (conj [] {:package? false} body))
                           m (py/import {:py/str "foo"} delivery)
                           d (py/module-dict m)
                           errors (cell/new [])
                           catch-error (fn [thunk]
                                         (py/try thunk
                                                 (fn [e]
                                                   (do (cell/set! errors (conj (cell/get errors)
                                                                               (if (py/isinstance e (py/global-get d {:py/str "NotImplementedError"}))
                                                                                 (let [msg (get (get (py/getattr e "args") :items) 0)]
                                                                                   (if (= msg {:py/str "finders/loaders/sys.path not supported in datom.world content-addressed modules; __import__ override is not supported"})
                                                                                     true
                                                                                     (= msg {:py/str "importlib.import_module requires a statically recognized literal absolute module name; dynamic or aliased calls are not supported"})))
                                                                                 false)))
                                                       :py/None))
                                                 (fn [] :py/None)))]
                       (do
                         (py/dict-set d {:py/str "extra"} 99)
                         (let [lib (py/import {:py/str "importlib"} :py/None)
                               reload (py/getattr lib "reload")
                               before (get m :id)
                               external (py/getattr m "x")
                               result (py/call reload (conj [] m))]
                           (do
                             (py/dict-set d {:py/str "fail"} true)
                             (py/try (fn [] (py/call reload (conj [] m)))
                                     (fn [e]
                                       (py/dict-set d {:py/str "caught"}
                                                    (py/isinstance e (py/global-get d {:py/str "ValueError"}))))
                                     (fn [] :py/None))
                             (catch-error (fn []
                                            (py/call (py/getattr lib "import_module")
                                                     (conj [] {:py/str "foo"}))))
                             (let [sys (py/import {:py/str "sys"} :py/None)
                                   sd (py/module-dict sys)
                                   builtins (py/import {:py/str "builtins"} :py/None)]
                               (do
                                 (catch-error (fn [] (py/getattr sys "meta_path")))
                                 (catch-error (fn [] (py/setattr sys "path_hooks" :py/None)))
                                 (catch-error (fn [] (py/getitem sd {:py/str "path"})))
                                 (catch-error (fn [] (py/setitem sd {:py/str "meta_path"} :py/None)))
                                 (catch-error (fn [] (py/delattr sys "path")))
                                 (catch-error (fn [] (py/setattr builtins "__import__" :py/None)))
                                 (catch-error (fn [] (py/global-set d {:py/str "__import__"} :py/None)))))
                             (conj [] (= m result) (= before (get m :id))
                                   (= d (py/module-dict m))
                                   (py/getattr m "x") (py/getattr m "extra") external
                                   (py/getattr m "caught")
                                   (= m (py/modules-ref {:py/str "foo"}))
                                   (cell/get errors)))))))))]
    (doseq [backend (keys h/backends)]
      (is (= [true true true 3 99 1 true true
              [true true true true true true true true]]
             (vm/value (h/run-linked backend ast)))
          (str backend)))))


(deftest ^:slow i6-runtime-test-test
  (slow/guard "i6-runtime-test" i6-runtime-test))


(defn- literal-import-module-callable-test
  []
  (let [ast (lower/lower-packet (import-module-packet ["STRING" "\"m\""])
                                {:prelude :linked})
        guarded (first (filter #(and (= :application (:type %))
                                     (= 'py/import-module?
                                        (get-in % [:operator :body :test :operator :name])))
                               (nodes ast)))
        ;; Execute the actual lowered expression, under its module globals.
        expr (u/lam [lower/globals-sym lower/globals-fn-sym] guarded)
        program (u/mark-tails
                  (u/then
                    (u/sexp->uast '(do (require (quote py)) (py/init!)))
                    (u/let1 'invoke expr
                            (u/sexp->uast
                              '(let [g (py/dict-new)
                                     gf (py/globals-fn g)
                                     lib (py/import {:py/str "importlib"} :py/None)]
                                 (do (py/global-set g {:py/str "importlib"} lib)
                                     (let [m (invoke g gf)
                                           static (py/import {:py/str "m"} :py/None)
                                           replacement (py/make-function "replacement"
                                                                         {:params ["name"]} [] []
                                                                         (fn [args] (do args 73)))]
                                       (do (py/setattr lib "import_module" replacement)
                                           (conj [] (= m static) (invoke g gf))))))))))]
    (is (some? guarded))
    (doseq [backend (keys h/backends)]
      (is (= [true 73]
             (vm/value (h/run-linked backend program {:link-source (guest-source)})))
          (str backend)))))


(deftest ^:slow literal-import-module-callable-test-test
  (slow/guard "literal-import-module-callable-test"
              literal-import-module-callable-test))
