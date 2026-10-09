(ns yang.python.antlr.linked-safepoint-test
  "C4 slice P3 (docs/design/yang.antlr.md 8.5.6): the hook prelude as the
   linker module `pysp`. The module emitter derives one module tree, its
   exports and its publication spec from the same definition list the
   bundled hook prelude loads; the spec declares every free name the tree
   reads but the `py/*` exports, which its pinned `py` requirement covers.

   Safepoint slice 1's signal acceptance and slice 2's recursion acceptance
   re-run under the linked profile on the three vector VMs (the walker's
   link stays refused until L-b), with the lowering's site marks preserved
   through the stage, the stage's input carrying no prelude row, and a
   derived program with no `pysp` binding failing closed on the unresolved
   hook name. Both modules are `yang.python.antlr.linked-harness`'s: one
   publication per process, served `:trusted`, so every test that publishes
   or links is slow."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.test-slow :as slow]
            [dao.stream :as stream]
            [yang.python.antlr.linked-harness :as h]
            [yang.python.antlr.lower :as lower]
            [yang.python.antlr.prelude :as prelude]
            [yang.python.antlr.render :as render]
            [yang.python.antlr.safepoint :as hooks]
            [yang.python.antlr.safepoint-programs :as programs]
            [yang.python.antlr.uast :as u]
            [yang.safepoint :as safepoint]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.encoder :as encoder]
            [yin.vm.engine :as engine]
            [yin.vm.linker.publish :as publish]
            [yin.vm.module :as module]
            [yin.vm.test-utils :as tu]))


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


(defn- nodes
  "Every node of map AST `ast`."
  [ast]
  (tree-seq map?
            (fn [n] (filter map? (mapcat #(if (vector? %) % [%]) (vals n))))
            ast))


;; =============================================================================
;; The linked stage: marks in, no prelude rows, hooks out
;; =============================================================================

(defn- envelope
  "The lowering's linked program batch for `pk`."
  [pk]
  (let [[_ [[port e]]] (lower/lower-transform {:prelude :linked} pk)]
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


(defn- holds-no-prelude
  "The linked program `ast` holds no definition, no runtime key and no
   prelude or hook-prelude row."
  [ast]
  (let [all (nodes ast)
        symbols (filter symbol? (tree-seq coll? seq ast))
        module-rows (into (set (:operands prelude/module-uast))
                          (:operands hooks/module-uast))]
    (is (not-any? #{'yin/def} symbols))
    (is (not-any? #(contains? #{"py.b" "py.rt" "py.sp"} (namespace %))
                  (filter namespace symbols)))
    (is (not-any? #(contains? module-rows %) all))))


(defn- hook-names-in
  "The `pysp` hook names the derived tree applies."
  [ast]
  (set (keep #(when (and (= :variable (:type %))
                         (= "pysp" (namespace (:name %))))
                (:name %))
             (nodes ast))))


(deftest stage-input-contains-no-prelude-row-test
  (doseq [[pk expected] [[programs/while-true-pass #{'pysp/loop}]
                         [programs/caught #{'pysp/loop}]
                         [programs/def-and-while
                          #{'pysp/loop 'pysp/call 'pysp/return}]
                         [programs/recursion #{'pysp/call 'pysp/return}]]]
    (let [{:keys [projected derived]} (derive* pk hooks/linked-profile)]
      (testing "the stage's input and output carry no prelude row"
        (holds-no-prelude (vm/semantic-bytecode->ast (tree-of projected)))
        (holds-no-prelude (vm/semantic-bytecode->ast (tree-of derived))))
      (testing "the derived tree applies exactly the module's hooks"
        (is (= expected
               (hook-names-in (vm/semantic-bytecode->ast
                                (tree-of derived)))))))))


(deftest insertion-is-deterministic-test
  (doseq [pk [programs/while-true-pass programs/def-and-while]]
    (is (= (derive* pk hooks/linked-profile)
           (derive* pk hooks/linked-profile)))))


(deftest marks-reach-the-side-table-test
  (let [{:keys [projected derived]} (derive* programs/def-and-while
                                             hooks/linked-profile)]
    (testing "the linked lowering marks the function's code lambda and the
              loop lambda exactly as the bundled one does"
      (let [marks (filter #(= :yang/site (nth % 3))
                          (:yin/frontend-metadata projected))]
        (is (= [:call :loop] (sort (map #(nth % 4) marks))))))
    (testing "every derived row validates"
      (is (nil? (vm/validate-rows (tree-of derived)))))))


;; =============================================================================
;; The module emitter
;; =============================================================================

(deftest pysp-module-uast-is-one-wide-application-test
  (let [ast hooks/module-uast
        operands (:operands ast)
        op (:operator ast)]
    (testing "one application of a lambda returning nil to every definition"
      (is (= :application (:type ast)))
      (is (= :lambda (:type op)))
      (is (= (count operands) (count (:params op))))
      (is (= :literal (get-in op [:body :type])))
      (is (nil? (get-in op [:body :value])))
      (is (every? some? (map def-key operands))))
    (testing "the placeholders come first, sorted, each the literal
              :py/uninit"
      (let [n (count hooks/runtime-keys)
            placeholders (take n operands)]
        (is (= (mapv hooks/strip hooks/runtime-keys)
               (mapv def-key placeholders)))
        (is (every? #(= {:type :literal, :value :py/uninit}
                        (dissoc (get-in % [:operands 1]) :tail?))
                    placeholders))))
    (testing "then every definition, stripped, in definition order"
      (is (= (mapv (comp hooks/strip first) hooks/function-definitions)
             (mapv def-key (drop (count hooks/runtime-keys) operands)))))
    (testing "no symbol of the module's own namespace survives stripping"
      (is (not-any? (fn [x]
                      (and (symbol? x) (= "py.sp" (namespace x))))
                    (tree-seq coll? seq ast))))
    (testing "`py/*` references stay qualified: they are the base prelude's
              exports, resolved through the registry"
      (is (some (fn [x] (and (symbol? x) (= "py" (namespace x))))
                (tree-seq coll? seq ast))))
    (testing "Rule R holds over the module tree"
      (is (nil? (vm/ast-reserved-defect ast))))))


(deftest pysp-module-exports-test
  (testing "every py.sp/ key of the definition list, stripped; no internal
            key"
    (is (= (set (map (comp hooks/strip first) hooks/function-definitions))
           hooks/module-exports))
    (is (every? #(contains? hooks/module-exports %)
                '[attach! loop call return set-handler!
                  set-recursion-limit! recursion-limit class]))
    (is (not-any? namespace hooks/module-exports))
    (is (not (contains? hooks/module-exports 'handler)))
    (is (not (contains? hooks/module-exports 'cursor))))
  (testing "no export is a primitive name"
    (is (empty? (filter #(contains? vm/primitives %)
                        hooks/module-exports)))))


(def ^:private py-address
  "A stand-in manifest address: the spec builder only pins it, and no fast
   test may force a publication."
  :test/py-address)


(deftest pysp-module-spec-test
  (let [spec (hooks/module-spec (h/registry) py-address)]
    (is (= 'pysp (:name spec) hooks/module-name))
    (is (= hooks/module-uast (:ast spec)))
    (is (= hooks/module-exports (:exports spec)))
    (testing "the pinned requirement is py at its manifest address"
      (is (= {'py py-address} (:requires spec))))
    (testing "a py-qualified free name is never a primitive declaration:
              the requirement covers it"
      (is (empty? (filter #(= "py" (namespace %))
                          (keys (:primitives spec))))))
    (testing "the qualified declarations are exactly the host names"
      (is (= hooks/host-names
             (set (filter namespace (keys (:primitives spec)))))))
    (testing "a host export is declared by its registry profile and effects"
      (is (every? #(and (contains? % :yin.k/profile)
                        (contains? % :yin.k/effects))
                  (keep #(get-in spec [:primitives %])
                        (filter namespace (keys (:primitives spec))))))))
  (testing "a free name no registry module or primitive supplies is refused
            before anything is published"
    (let [no-cell (-> (module/empty-registry)
                      (data/register-data-module {::data/max-items 1048576})
                      (prelude/register-integer-module h/integer-limits))
          refusal (refusal-of #(hooks/module-spec no-cell py-address))]
      (is (= :yang.python.antlr/undeclared-free
             (:yang.python.antlr/refusal refusal)))
      (is (= "cell" (namespace (:name refusal)))))))


;; =============================================================================
;; A1. `pysp` publishes to one pinned manifest address
;; =============================================================================

(def ^:private pysp-manifest-golden
  "I1 moved this once: the pinned `py` requirement is py's own manifest
   address, which I1's module runtime moved."
  (keyword (str "segment/blake3-"
                "a842d2ad662f1d0a0dd7fb9292222134"
                "3603472bac591073ac23ba82c9772d54")))


(defn- pysp-manifest-address
  []
  (let [{:keys [store result address]} @h/published-pysp
        links (:links result)]
    (testing "one address on every host (JVM golden)"
      (is (= pysp-manifest-golden address) (pr-str (dissoc result :links))))
    (testing "the three vector formats link :ok under :verifying"
      (doseq [f [:yin.semantic/code :yin.debruijn.code
                 :yin.debruijn.register]]
        (is (= :ok (get-in links [f :status])) (str f))))
    (testing "publishing twice into one store answers one address"
      (is (= address
             (:address (publish/publish-module!
                         store (hooks/module-spec (h/registry)
                                                  (:address @h/published)))))))))


(deftest ^:slow pysp-manifest-address-test
  (slow/guard "pysp-manifest-address-test" pysp-manifest-address))


;; =============================================================================
;; Running A' linked
;; =============================================================================

(defn- outcome
  "A finished run as `[halted? blocked? rendered-value]`."
  [task]
  [(vm/halted? task) (vm/blocked? task) (render/output (vm/value task))])


(defn- with-signals
  "A linked `prep` handing the task a signal stream holding `signals`."
  [signals]
  (fn [task]
    (let [handle (tu/new-stream 8)]
      (doseq [s signals] (stream/append! handle s))
      (let [[ref task] (engine/attach-resource task handle)]
        (assoc-in task [:store hooks/signals-key] ref)))))


(defn- derived-ast
  "`A'` for `pk` under the linked profile."
  [pk]
  (vm/semantic-bytecode->ast
    (tree-of (:derived (derive* pk hooks/linked-profile)))))


(defn- on-every-vm
  "Every vector VM's outcome for `ast` under `signals`, or the throw; the
   composition serves both modules."
  ([ast] (on-every-vm ast []))
  ([ast signals]
   (into {}
         (map (fn [k]
                [k (try (outcome (h/run-linked
                                   k ast
                                   {:link-source (h/safepoint-source),
                                    :prep (with-signals signals)}))
                        (catch #?(:cljd Object :clj Exception :cljs :default) e
                          [:thrown (ex-message e)]))]))
         (keys h/backends))))


(defn- every=
  [expected results]
  (doseq [[k result] results]
    (is (= expected result) (str k))))


(defn- linked-run
  "The wrapped derived program of `pk` on every VM under `signals`."
  [pk signals]
  (on-every-vm (hooks/linked-program (derived-ast pk)) signals))


(defn- limited-run
  "The wrapped derived program of `pk` under recursion limit `n`."
  [pk n signals]
  (on-every-vm
    (hooks/linked-program
      (u/then (u/sexp->uast (list 'pysp/set-recursion-limit! n))
              (derived-ast pk)))
    signals))


(defn- fail-closed
  []
  (testing "the derived linked program with no `pysp` binding names the
            hook"
    (every= [:thrown "Unable to resolve symbol: pysp/loop in this context"]
            (on-every-vm (derived-ast programs/while-true-pass)))))


(deftest ^:slow linked-fail-closed-test
  (slow/guard "linked-fail-closed-test" fail-closed))


(defn- interrupt
  []
  (testing "while True: pass with one pre-appended signal ends with
            KeyboardInterrupt"
    (every= [true false {:py/out [], :py/exception {:type "KeyboardInterrupt",
                                                    :args []}}]
            (linked-run programs/while-true-pass [2])))
  (testing "wrapped in try/except KeyboardInterrupt, the handler prints"
    (every= [true false {:py/out ["caught"], :py/exception nil}]
            (linked-run programs/caught [2]))))


(deftest ^:slow linked-interrupt-test
  (slow/guard "linked-interrupt-test" interrupt))


(defn- no-park
  []
  (testing "with an empty signal stream the derived program finishes without
            blocking and prints what the naive one prints"
    (every= [true false {:py/out ["5"], :py/exception nil}]
            (linked-run programs/def-and-while []))))


(deftest ^:slow linked-no-park-test
  (slow/guard "linked-no-park-test" no-park))


(defn- registered-handler-runs
  []
  (testing "a registered Python handler is called with the signal and None,
            and the program goes on; registered after `pysp/attach!`, so
            `py/make-function` builds the function object"
    (let [set-handler (u/sexp->uast
                        '(pysp/set-handler!
                           (py/make-function "h" {:params ["s" "f"]} [] []
                                             (fn [args] (py/print (py/vconj [] (py/arg args 0)))))))
          ast (hooks/linked-program
                (u/then set-handler (derived-ast programs/def-and-while)))]
      (every= [true false {:py/out ["7" "5"], :py/exception nil}]
              (on-every-vm ast [7])))))


(deftest ^:slow linked-registered-handler-test
  (slow/guard "linked-registered-handler-test" registered-handler-runs))


(defn- recursion-error
  []
  (testing "the default limit through the module boundary: f(999) is 1000
            frames and completes; f(1000) raises RecursionError, caught by
            except; probe(1) finds the limit at 1000 frames"
    (every= [true false {:py/out ["0" "rec" "1000"], :py/exception nil}]
            (linked-run programs/recursion []))))


(deftest ^:slow linked-recursion-error-test
  (slow/guard "linked-recursion-error-test" recursion-error))


(defn- escape-restores-depth
  []
  (testing "under limit 100: a raise through 50 frames with finally
            restores the depth saved at the catching try (probe finds 100
            frames again), and a finally thunk runs at its frame's depth"
    (every= [true false {:py/out ["unwound" "100" "89" "100" "99" "100"],
                         :py/exception nil}]
            (limited-run programs/unwind 100 []))))


(deftest ^:slow linked-escape-restores-depth-test
  (slow/guard "linked-escape-restores-depth-test" escape-restores-depth))


(defn- generator-depth
  []
  (testing "under limit 100: depth measures the current continuation across
            the module boundary as it does bundled — a generator resumed 20
            frames down runs on top of its resumer, is rebased by a later
            resume, and every crossing back restores the caller's record"
    (every= [true false {:py/out ["79" "99" "100" "79" "100" "79" "99"
                                  "100"],
                         :py/exception nil}]
            (limited-run programs/generators 100 []))))


(deftest ^:slow linked-generator-depth-test
  (slow/guard "linked-generator-depth-test" generator-depth))
