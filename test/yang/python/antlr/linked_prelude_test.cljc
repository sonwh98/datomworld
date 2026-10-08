(ns yang.python.antlr.linked-prelude-test
  "C4 slice P2 (docs/design/yang.antlr.md 8.5.6): the Python runtime
   profile as the linker module `py`. The module emitter derives one
   module tree, its exports and its publication spec from the single
   definition list; the spec declares every free name of the tree, the
   host exports by profile address; the AST walker's link stays refused
   until L-b. The published module and the linked runs are
   `yang.python.antlr.linked-harness`'s: one publication per process,
   served `:trusted`, so every test that publishes or links is slow.

   Cross-unit exception identity is tested in prelude notation over two
   units of one task: until I1 Python source has no channel that carries
   a value from one unit to another, so the Python-source form is I1's."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.jing.mem :as mem]
    [dao.test-slow :as slow]
    [yang.python.antlr.linked-harness :as h]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.lower-portable-test :as portable]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.uast :as u]
    [yin.repl.link :as link]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.engine :as engine]
    [yin.vm.integer :as integer]
    [yin.vm.linker :as linker]
    [yin.vm.linker.publish :as publish]
    [yin.vm.module :as module]))


(def ^:private limits
  {::integer/max-bits 100000, ::integer/max-digits 4300})


(def ^:private registry
  "The full host registry a Python composition registers."
  (-> (module/empty-registry)
      module/register-cell-module
      (data/register-data-module {::data/max-items 1048576})
      (prelude/register-integer-module limits)))


(defn- refusal-of
  "The ex-data of what `thunk` throws, or nil when it returns."
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (or (ex-data e) {}))))


(defn- def-key
  "The literal key of a `(yin/def k v)` application node, else nil."
  [node]
  (when (and (map? node)
             (= :application (:type node))
             (= 'yin/def (get-in node [:operator :name])))
    (get-in node [:operands 0 :value])))


(defn- module-level-keys
  "The keys the module tree defines as operands of its one application."
  [ast]
  (set (keep def-key (:operands ast))))


(defn- sexp-def-keys
  "Every key a prelude-notation form defines with `(yin/def (quote k) v)`."
  [form]
  (set (keep (fn [x]
               (when (and (seq? x) (= 'yin/def (first x)))
                 (let [k (second x)]
                   (if (seq? k) (second k) k))))
             (tree-seq coll? seq form))))


;; =============================================================================
;; The module emitter
;; =============================================================================

(deftest strip-renames-only-the-module-namespace-test
  (is (= 'add (prelude/strip 'py/add)))
  (is (= 'init! (prelude/strip 'py/init!)))
  (is (= 'py.b/list (prelude/strip 'py.b/list)))
  (is (= 'py.rt/state (prelude/strip 'py.rt/state)))
  (is (= 'cell/new (prelude/strip 'cell/new)))
  (is (= 'x (prelude/strip 'x)))
  (is (= :py/None (prelude/strip :py/None))))


(deftest module-uast-is-one-wide-application-test
  (let [ast prelude/module-uast
        operands (:operands ast)
        op (:operator ast)]
    (testing "one application of a lambda returning nil to every definition"
      (is (= :application (:type ast)))
      (is (= :lambda (:type op)))
      (is (= (count operands) (count (:params op))))
      (is (= :literal (get-in op [:body :type])))
      (is (nil? (get-in op [:body :value])))
      (is (every? some? (map def-key operands))))
    (testing "the placeholders come first, sorted, each the literal :py/uninit"
      (let [n (count prelude/runtime-keys)
            placeholders (take n operands)]
        (is (= prelude/runtime-keys (mapv def-key placeholders)))
        (is (every? #(= {:type :literal, :value :py/uninit}
                        (dissoc (get-in % [:operands 1]) :tail?))
                    placeholders))))
    (testing "then every definition, stripped, in definition order"
      (is (= (mapv (comp prelude/strip first) prelude/definitions)
             (mapv def-key (drop (count prelude/runtime-keys) operands)))))
    (testing "no symbol of the module's own namespace survives stripping"
      (is (not-any? (fn [x]
                      (and (symbol? x) (= "py" (namespace x))))
                    (tree-seq coll? seq ast))))
    (testing "Rule R holds over the module tree"
      (is (nil? (vm/ast-reserved-defect ast))))))


(deftest module-exports-test
  (testing "every py/ key of the definition list, stripped; no internal key"
    (is (= (set (keep (fn [[k _]]
                        (when (= "py" (namespace k)) (prelude/strip k)))
                      prelude/definitions))
           prelude/module-exports))
    (is (every? #(contains? prelude/module-exports %)
                '[init! run-main run-module object-class vconj lnot]))
    (is (not-any? namespace prelude/module-exports))
    (is (not (contains? prelude/module-exports 'state))))
  (testing "D4: no export is a primitive name"
    (is (empty? (filter #(contains? vm/primitives %)
                        prelude/module-exports)))))


(deftest module-spec-test
  (let [spec (prelude/module-spec registry)]
    (is (= 'py (:name spec) prelude/module-name))
    (is (= prelude/module-uast (:ast spec)))
    (is (= prelude/module-exports (:exports spec)))
    (is (= {} (:requires spec)))
    (testing "a host export is declared by its registry profile and effects"
      (is (= {:yin.k/profile (get-in module/cell-profiles
                                     ['new :yin.k/profile]),
              :yin.k/effects #{:cell/new}}
             (get-in spec [:primitives 'cell/new])))
      (is (= #{} (get-in spec [:primitives 'integer/add :yin.k/effects]))))
    (testing "a primitive is declared by its vm/primitives profile"
      (is (= (vm/profile-of vm/primitives '+)
             (get-in spec [:primitives '+])))))
  (testing "a free name no registry module or primitive supplies is refused
            before anything is published"
    (let [no-data (-> (module/empty-registry)
                      module/register-cell-module
                      (prelude/register-integer-module limits))
          refusal (refusal-of #(prelude/module-spec no-data))]
      (is (= :yang.python.antlr/undeclared-free
             (:yang.python.antlr/refusal refusal)))
      (is (= "data" (namespace (:name refusal)))))))


;; =============================================================================
;; A8. The host-export declaration is derived and complete
;; =============================================================================

(deftest module-spec-declares-every-free-name-test
  (let [declared (keys (:primitives (prelude/module-spec registry)))]
    (testing "the qualified declarations are exactly the host names (I7)"
      (is (= prelude/host-names (set (filter namespace declared)))))
    (testing "the bare declarations are primitives"
      (is (every? #(contains? vm/primitives %) (remove namespace declared))))
    (testing "the exports are no primitive and all defined at module level"
      (is (empty? (filter #(contains? vm/primitives %)
                          prelude/module-exports)))
      (is (every? (module-level-keys prelude/module-uast)
                  prelude/module-exports)))
    (testing "the runtime keys are what the generated py/init! defines,
              the ready flag aside"
      (is (= (set prelude/runtime-keys)
             (disj (sexp-def-keys (get (into {} prelude/definitions)
                                       'py/init!))
                   'py.rt/state)))
      (is (= 61 (count prelude/runtime-keys))))))


;; =============================================================================
;; A12. The module tree fits the default bounds with headroom
;; =============================================================================

(defn- deepest-occurrence
  "The longest occurrence path from the root of the canonical rows `bc`:
   every row id a row names is a child occurrence."
  [{:keys [root rows]}]
  (loop [frontier [[root 0]]
         deepest 0]
    (if (empty? frontier)
      deepest
      (let [[id depth] (peek frontier)
            kids (filter #(contains? rows %)
                         (tree-seq coll? seq (vec (rest (get rows id)))))]
        (recur (into (pop frontier) (map (fn [k] [k (inc depth)])) kids)
               (max deepest depth))))))


(deftest module-tree-fits-the-default-bounds-with-headroom-test
  (let [bc (vm/ast->semantic-bytecode prelude/module-uast)
        rows (count (:rows bc))
        depth (deepest-occurrence bc)]
    (testing "the row count is below half the parts bound"
      (is (< rows (quot (:max-parts linker/default-bounds) 2))
          (str rows " rows")))
    (testing "the deepest occurrence is below half the depth bound"
      (is (< depth (quot (:max-depth linker/default-bounds) 2))
          (str depth " deep")))))


;; =============================================================================
;; A1 and A10. `py` publishes to one pinned manifest address; the walker is
;; excluded, visibly
;; =============================================================================

(def ^:private manifest-golden
  (keyword (str "segment/blake3-"
                "8f5e6bc93e0e8960682b65c2e4254ec9"
                "59e69f0579ccda07a3189ea0e0e4b9d7")))


(def ^:private vector-formats
  [:yin.semantic/code :yin.debruijn.code :yin.debruijn.register])


(defn- manifest-address
  []
  (let [{:keys [store result address]} @h/published
        links (:links result)]
    (testing "one address on every host (JVM golden)"
      (is (= manifest-golden address) (pr-str (dissoc result :links :walk))))
    (testing "the three vector formats link :ok under :verifying"
      (doseq [f vector-formats]
        (is (= :ok (get-in links [f :status])) (str f))
        (is (= :verified (get-in links [f :trust])) (str f))))
    (testing "publishing twice into one store answers one address"
      (is (= address
             (:address (publish/publish-module!
                         store (prelude/module-spec registry))))))))


(deftest ^:slow manifest-address-test
  (slow/guard "manifest-address-test" manifest-address))


(defn- walker-is-excluded
  []
  (let [walker (get-in @h/published [:result :links :yin.ast/code])]
    (testing "the AST walker is refused :undeclared-free on a sibling read
              until L-b; L-b's landing flips this assertion"
      (is (= :refused (:status walker)))
      (is (= :undeclared-free (:reason walker)))
      (is (= 'float? (:name walker)) "the first retained py key")
      (is (contains? prelude/module-exports (:name walker))))))


(deftest ^:slow walker-is-excluded-test
  (slow/guard "walker-is-excluded-test" walker-is-excluded))


;; =============================================================================
;; A3. One source under two prelude revisions keeps one program root
;; =============================================================================

(defn- statement
  [expr-stmt]
  [:stmt [:simple_stmts [:simple_stmt expr-stmt] ["NEWLINE" "\n"]]])


(defn- chain
  [rules leaf]
  (reduce (fn [inner r] [r inner]) leaf (reverse rules)))


(def ^:private expr-chain
  [:testlist_star_expr :test :or_test :and_test :not_test :comparison :expr
   :atom_expr :atom])


(def ^:private x-equals-1
  "`x = 1`, as the parser's CST packet."
  (portable/packet [:file_input
                    (statement [:expr_stmt
                                (chain expr-chain [:name ["NAME" "x"]])
                                ["ASSIGN" "="]
                                (chain expr-chain ["NUMBER" "1"])])
                    ["EOF" "<EOF>"]]))


(def ^:private linked-x-equals-1
  (lower/lower-packet x-equals-1 {:prelude :linked}))


(def ^:private program-root-golden
  (keyword (str "segment/blake3-"
                "dbe72b291a26a4f594ad55cf52e5ffd0"
                "88fdd262b58fd26212a01a7dd983a6df")))


(defn- holds-no-prelude
  "The linked program `ast` holds no definition, no runtime key and no
   prelude row."
  [ast]
  (let [nodes (tree-seq coll? seq ast)
        symbols (filter symbol? nodes)
        prelude-rows (set (:operands prelude/module-uast))]
    (is (not-any? #{'yin/def} symbols))
    (is (not-any? #(contains? #{"py.b" "py.rt"} (namespace %))
                  (filter namespace symbols)))
    (is (not-any? #(contains? prelude-rows %) nodes))))


(defn- with-probe
  "`spec` with one more definition, `probe`, appended and exported."
  [spec]
  (-> spec
      (update-in [:ast :operator :params] conj '%probe)
      (update-in [:ast :operands] conj (u/def! 'probe (u/lit :py/uninit)))
      (update :exports conj 'probe)))


(defn- program-root-is-prelude-independent
  []
  (let [root (:root (vm/ast->semantic-bytecode linked-x-equals-1))
        revised (publish/publish-module! (mem/create-content-mem)
                                         (with-probe
                                           (prelude/module-spec registry)))]
    (testing "the linked program root is one address on every host (JVM
              golden)"
      (is (= program-root-golden root) (str root)))
    (testing "a prelude revision moves the manifest address only"
      (is (some? (:address revised)) (pr-str (dissoc revised :links :walk)))
      (is (not= (:address @h/published) (:address revised)))
      (is (= root (:root (vm/ast->semantic-bytecode
                           (lower/lower-packet x-equals-1
                                               {:prelude :linked}))))))
    (testing "the program tree holds no prelude"
      (holds-no-prelude linked-x-equals-1))))


(deftest ^:slow program-root-is-prelude-independent-test
  (slow/guard "program-root-is-prelude-independent-test"
              program-root-is-prelude-independent))


;; =============================================================================
;; A4. Cross-unit exception identity
;; =============================================================================

(def ^:private unit-1
  (u/mark-tails
    (u/sexp->uast
      '(do (require (quote py))
           (py/init!)
           (yin/def (quote u1-exc)
                    (py/try (fn []
                              (py/raise
                                (py/call (py/global-get (py/dict-new)
                                                        {:py/str "ValueError"})
                                         [])))
                            (fn [e] e)
                            (fn [] :py/None)))
           (yin/def (quote u1-cls)
                    (py/global-get (py/dict-new) {:py/str "Exception"}))))))


(def ^:private unit-2
  (u/mark-tails
    (u/sexp->uast
      '(do (require (quote py))
           (py/init!)
           (conj (conj []
                       (py/isinstance u1-exc
                                      (py/global-get (py/dict-new)
                                                     {:py/str "Exception"})))
                 (= u1-cls (py/global-get (py/dict-new)
                                          {:py/str "Exception"})))))))


(defn- exception-class-is-shared-across-units
  []
  (doseq [k (keys h/backends)]
    (testing (name k)
      (let [pair (link/make-pair)
            src (h/source)
            [t1 pair] (h/drive ((get h/backends k)
                                unit-1
                                (h/composition (h/registry) pair))
                               pair src)
            [t2 _] (h/drive ((get h/continues k) t1 unit-2) pair src)]
        (is (vm/halted? t1))
        (testing "the second require answers from the registry and the
                  second init! allocates nothing"
          (is (= [true true] (vm/value t2))))))))


(deftest ^:slow exception-class-is-shared-across-units-test
  (slow/guard "exception-class-is-shared-across-units-test"
              exception-class-is-shared-across-units))


;; =============================================================================
;; A5. An install child at `validated` has an empty heap and no lift refusal
;; =============================================================================

(defn- install-child-defines-only
  []
  (let [{:keys [result address]} @h/published
        exports (sort-by str prelude/module-exports)]
    (doseq [[k f] (map vector [:semantic :stack :register] vector-formats)]
      (testing (name k)
        (let [parent ((get h/backends k)
                      (u/lit nil)
                      (h/composition (h/registry) (link/make-pair)))
              child (vm/run (module/spawn-module
                              parent
                              (get-in result [:links f :value])
                              {:modules (:modules parent),
                               :origin :t0.0,
                               :ancestry ['py],
                               :capability-secret "child"}))
              store (vm/store child)]
          (is (vm/halted? child))
          (is (not (vm/blocked? child)))
          (is (empty? (:heap child)))
          (is (every? #(contains? store %) exports))
          (is (= :py/uninit (get store 'py.rt/state)))
          (let [lifted (engine/lift-slice child address exports)]
            (is (= [address] (keys (:stores lifted))))
            (is (empty? (:cells lifted)))))))))


(deftest ^:slow install-child-defines-only-test
  (slow/guard "install-child-defines-only-test" install-child-defines-only))


;; =============================================================================
;; A6. A wrong host-module profile is refused by name
;; =============================================================================

(defn- wrong-host-profile-refuses-the-require
  []
  (let [pure-new (vm/primitive-profile 'new :pure [1] #{} :none)
        modules (-> (module/default-registry)
                    (module/register-host-module
                      'cell module/cell-module
                      (assoc module/cell-profiles 'new pure-new))
                    (data/register-data-module {::data/max-items 1048576})
                    (prelude/register-integer-module limits))]
    (doseq [k (keys h/backends)]
      (testing (name k)
        (let [refusal (refusal-of #(h/run-linked k linked-x-equals-1
                                                 {:modules modules}))]
          (is (= :unresolved-free (:reason refusal)) (pr-str refusal))
          (is (= 'cell/new (:name refusal))))))))


(deftest ^:slow wrong-host-profile-refuses-the-require-test
  (slow/guard "wrong-host-profile-refuses-the-require-test"
              wrong-host-profile-refuses-the-require))


;; =============================================================================
;; A7. A missing name-environment entry is refused with no bundled fallback
;; =============================================================================

(defn- absent-name-refuses-with-no-fallback
  []
  (holds-no-prelude linked-x-equals-1)
  (doseq [k (keys h/backends)]
    (testing (name k)
      (let [pair (link/make-pair)
            task (vm/run ((get h/backends k)
                          linked-x-equals-1
                          (h/composition (h/registry) pair)))
            served (link/serve {:pair pair, :source (h/source {})})
            refusal (refusal-of #(vm/run task))
            held (keys (vm/store task))]
        (is (empty? (:pending served)))
        (is (= :absent (:reason refusal)) (pr-str refusal))
        (is (= 'py (:name refusal)))
        (testing "the task's store holds no prelude key"
          (is (not-any? #(contains? prelude/module-exports %) held))
          (is (not-any? #(and (symbol? %)
                              (contains? #{"py" "py.b" "py.rt"} (namespace %)))
                        held)))))))


(deftest ^:slow absent-name-refuses-with-no-fallback-test
  (slow/guard "absent-name-refuses-with-no-fallback-test"
              absent-name-refuses-with-no-fallback))
