(ns yang.python.antlr.descriptor-profile-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.host.integer :as host-int]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [yang.clojure :as clojure]
            [yang.clojure.prelude :as clj]
            [yang.core.semantics :as semantics]
            [yang.core.uast :as u]
            [yang.frontend :as frontend]
            [yang.python.antlr.clj-runtime :as runtime]
            [yang.python.antlr.contract :as contract]
            [yang.python.antlr.linked-harness :as harness]
            [yang.python.antlr.lower :as lower]
            [yang.python.antlr.parser :as parser]
            [yang.python.antlr.profiles :as profiles]
            [yin.repl :as repl]
            [yin.repl.frontends :as frontends]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.integer :as integer]
            [yin.vm.linker.interpreter :as link]
            [yin.vm.module :as module]))


(def ^:private composition
  (delay (let [store (mem/create-content-mem)
               prepared (profiles/prepare! store [contract/clojure-id])]
           (assoc prepared :link-source
                  (link/composition {:content-store store :name-env (:pins prepared)
                                     :derivation :trusted})))))


(defn- descriptor []
  (get-in @composition [:profiles contract/clojure-id :descriptor]))


(defn- packet [source] (parser/parse-source (str source "\n")))


(defn- refusal [f]
  (try (f) nil
       (catch #?(:cljd Object :clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (ex-data e))))


(defn- run-program [backend ast registry extra-primitives]
  (let [pair (link/make-pair)
        task ((get harness/backends backend)
              ast
              (update (harness/composition registry pair) :primitives merge extra-primitives))]
    (vm/value (first (harness/drive task pair (:link-source @composition))))))


(def ^:private engines [:semantic :stack :register])
(def ^:private modes [:bundled :linked])


(defn- lower-test [mode pk]
  (lower/lower-packet pk {:descriptor (descriptor) :prelude mode}))


(defn- entry [mode body resolver]
  (let [main (u/app (u/v 'clj.py/run-main) body resolver)]
    (if (= :bundled mode)
      (runtime/bundled main)
      (u/annotate-call-sites
        (u/mark-tails
          (u/seq-nodes
            (conj (mapv (fn [{:keys [module]}] (u/app (u/v 'require) (u/lit module)))
                        (:yang.semantics/ambient-modules (descriptor)))
                  main)))))))


(defn- module-body [pk]
  (u/lam [lower/globals-sym lower/globals-fn-sym]
         (lower/lower-module-body pk nil true (descriptor))))


(deftest descriptor-validation-test
  (let [{:keys [artifacts registry contract]} @composition
        d (descriptor)
        validate #(semantics/validate-descriptor % contract artifacts registry)
        replace-add #(assoc-in d [:yang.semantics/hooks :op/add] %)]
    (is (= d (validate d)))
    (doseq [[target defect]
            [['clj.py/neg :arity]
             ['clj.py/sub :export-role]
             ['py/add :export]
             ['cell/dsg__provenance :reserved-door]
             ['clj/dsg__invoke :reserved-door]
             ['yin/def :reserved-door]
             ['clj.py.rt/session :reserved-door]
             ['py.b/builtins :reserved-door]
             ['yang.safepoint/poll :reserved-door]
             ['clojure.core.impl/impl-seq :reserved-door]]]
      (is (= defect (:defect (refusal #(validate (replace-add target))))) (str target)))
    (is (= :yang.semantics/unqualified-hook
           (:reason (refusal #(validate (replace-add 'add))))))
    (is (= :export-role
           (:defect (refusal #(validate (assoc-in d [:yang.semantics/literals :int :overflow-op]
                                                  'clj.py/neg))))))
    (is (= :export-role
           (:defect (refusal #(validate (assoc-in d [:yang.semantics/ambient-projections "inc" :export]
                                                  'dec))))))
    (is (= :projection-signature
           (:defect (refusal #(validate (assoc-in d [:yang.semantics/ambient-projections "inc"]
                                                  {:module 'clj.py :export 'neg}))))))
    (is (= :arity
           (:defect (refusal #(semantics/validate-descriptor
                               d (assoc-in contract [:entry-lowering contract/clojure-id :call-shapes] #{3})
                               artifacts registry)))))
    (let [root (get-in @composition [:pins 'clj.py])
          manifest (artifacts root)
          tree (update runtime/module-uast :operands
                       (fn [operands]
                         (mapv (fn [node]
                                 (if (= 'add (get-in node [:operands 0 :value]))
                                   (assoc-in node [:operands 1] (u/lit 42))
                                   node))
                               operands)))
          tree-address (:root (vm/ast->semantic-bytecode tree))
          manifest (assoc manifest :yin.module/tree tree-address)
          address (jing/segment-key manifest)
          pinned (update d :yang.semantics/ambient-modules
                         #(mapv (fn [pin] (if (= 'clj.py (:module pin))
                                            (assoc pin :address address) pin)) %))]
      (is (= :tree-address
             (:defect (refusal #(semantics/validate-descriptor
                                 d contract (assoc artifacts (:yin.module/tree (artifacts root)) tree)
                                 registry)))))
      (is (= :non-lambda
             (:defect (refusal #(semantics/validate-descriptor
                                 pinned (update contract :roles
                                                (fn [roles] (into roles (map (fn [[a target role]]
                                                                              [(if (= a root) address a)
                                                                               target role])) roles)))
                                 (assoc artifacts address manifest tree-address tree) registry)))))
      (is (= :manifest-address
             (:defect (refusal #(semantics/validate-descriptor
                                 pinned contract (assoc artifacts address (artifacts root)) registry)))))
      (is (= :runtime-profile-pins
             (:defect (refusal #(profiles/admit
                                 pinned (get-in @composition [:profiles contract/clojure-id :runtime])
                                 contract artifacts registry {}))))))))


(deftest immutable-projection-export-presence-test
  (let [registry (module/assoc-module nil 'test.values
                                     {:manifest {:yin.module/name 'test.values}
                                      :bindings {'empty nil 'off false 'sentinel :clj/unbound}})]
    (is (nil? (engine/resolve-var {} {} {} registry 'test.values/empty)))
    (is (false? (engine/resolve-var {} {} {} registry 'test.values/off)))
    (is (= :clj/unbound (engine/resolve-var {} {} {} registry 'test.values/sentinel)))
    (is (= 'test.values/missing
           (:symbol (refusal #(engine/resolve-var {} {} {} registry 'test.values/missing)))))))


(deftest capabilities-refuse-before-emission-test
  (doseq [[source capacity construct]
          [["try:\n    pass\nexcept E:\n    pass" :control/except-typed "typed except"]
           ["(x for x in [1, 2])" :control/generators "genexpr"]
           ["f(x for x in [1, 2])" :control/generators "genexpr"]
           ["def f():\n    yield 1" :control/generators "yield_expr"]
           ["class A:\n    pass" :oo/classes "classdef"]
           ["with a:\n    pass" :control/with "with_stmt"]
           ["del x" :mutation/delete "del_stmt"]
           ["x[0] = 1" :mutation/subscript "subscript mutation"]
           ["x[0] += 1" :mutation/subscript "subscript mutation"]
           ["f(x=1)" :calls/kwargs "kwargs"]
           ["def f(x=1):\n    pass" :calls/defaults "defaults"]]]
    (let [result (refusal #(lower-test :bundled (packet source)))]
      (is (= {:status :refused :reason :yang.semantics/unsupported-capability
              :capability capacity :construct construct :semantics-id contract/clojure-id}
             (select-keys result [:status :reason :capability :construct :semantics-id]))
          source)
      (is (int? (:node result)) source))))


(def ^:private numeric-source
  (str "[1 + 2, 4 - 1, -2 * +3, 7 / 2, -7 // 2, -7 % 2, 2 ** 4, 2 ** -3, "
       "1 + 2.5, 2.1 // 0.7, -0.0, 1 < 1.5, 1.5 >= 1, "
       "1 == 1.0, 1 == True, 0.0 == -0.0, (1e999 - 1e999) == (1e999 - 1e999), "
       "[None, 1] == [None, 1], {'a': 1} != {'a': 1.0}, "
       "not 0, not None, [] or 99, 'a' + 'b']"))

(def ^:private numeric-result
  [3 3 -6 (cbor/float64 3.5) -4 1 16 (cbor/float64 0.125)
   (cbor/float64 3.5) (cbor/float64 3.0) (cbor/float64 -0.0) true true
   false false true false true true false true [] "ab"])


(def ^:private collections-source
  (str "from clojure.core import str as render\n"
       "import clojure.core as core\n"
       "def sum_even_squares(xs):\n"
       "    evens = filter(lambda n: n % 2 == 0, xs)\n"
       "    squares = map(lambda n: n * n, evens)\n"
       "    return reduce(lambda a, b: a + b, 0, squares)\n"
       "def varargs(x, *xs):\n"
       "    return (x, xs)\n"
       "m = {None: 'nil', 0: 'zero'}\n"
       "deep = assoc_in({}, ['x', 'y'], 8)\n"
       "[sum_even_squares([1,2,3,4]), [p for p in m], render(m), {x for x in [2,1,2]}, "
       "{x: x*2 for x in [1,2]}, varargs(*[7,8,9]), core.inc(4), "
       "get_in(update_in(deep, ['x', 'y'], inc), ['x', 'y']), is_empty([]), is_nil(None)]"))

(def ^:private collections-result
  [20 [[0 "zero"] [nil "nil"]] "{0 \"zero\", nil \"nil\"}" #{1 2}
   {1 2 2 4} [7 [8 9]] 5 9 true true])


(deftest persistent-profile-e2e-test
  (let [registry (:registry @composition)]
    (doseq [[source expected] [[numeric-source numeric-result] [collections-source collections-result]]
            :let [pk (packet source)]
            mode modes
            :let [ast (lower-test mode pk)]
            backend engines]
      (is (cbor/equiv expected (run-program backend ast registry {}))
          (str mode " " backend)))))


(deftest policy-traps-and-guest-cleanup-test
  (let [source (str "seen = []\n"
                    "try:\n    bad = 9007199254740991 + 1\n"
                    "except:\n    seen = conj(seen, 'bits')\n"
                    "finally:\n    seen = conj(seen, 'finally')\n"
                    "try:\n    bad = 9007199254740992\n"
                    "except:\n    seen = conj(seen, 'literal')\n"
                    "try:\n    bad = 3.0 / 0.0\n"
                    "except:\n    seen = conj(seen, 'zero')\n"
                    "try:\n    bad = {[1, 2.0]: 5}\n"
                    "except:\n    seen = conj(seen, 'key')\n"
                    "try:\n    bad = {2.0 for k in [1]}\n"
                    "except:\n    seen = conj(seen, 'set')\n"
                    "try:\n    bad = 1 + True\n"
                    "except:\n    seen = conj(seen, 'type')\n"
                    "seen")
        registry (profiles/registry {::integer/max-bits 53 ::integer/max-digits 4300})
        pk (packet source)]
    (doseq [mode modes :let [ast (lower-test mode pk)] backend engines]
      (is (= ["bits" "finally" "literal" "zero" "key" "set" "type"]
             (run-program backend ast registry {}))
          (str mode " " backend)))))


(def ^:private regression-source
  (str "def use_local(x):\n"
       "    val = x\n"
       "    return val\n"
       "def missing_local():\n"
       "    if False:\n        val = 5\n"
       "    return val\n"
       "try:\n    missing_local()\n    local_missing = False\n"
       "except:\n    local_missing = True\n"
       "try:\n    missing_global\n    global_missing = False\n"
       "except:\n    global_missing = True\n"
       "m = {key: 9, 0: 'native first', boxed(): 'boxed second'}\n"
       "[m[key], m[boxed()], m[0], m == {key: 9, 0: 'boxed second'}, "
       "sentinel, use_local(sentinel), local_missing, global_missing]"))


(deftest cross-carrier-keys-and-binding-presence-test
  (let [body (module-body (packet regression-source))
        resolver (u/sexp->uast
                   '(fn [name]
                      (if (= name "sentinel") {:value :clj/unbound}
                        (if (= name "key") {:value :k}
                          (if (= name "boxed")
                            (assoc {} :value (clj/make-callable {} (fn [args] (boxed-key))))
                            {})))))
        expected [9 "boxed second" "boxed second" true :clj/unbound :clj/unbound true true]
        registry (:registry @composition)]
    (doseq [mode modes :let [ast (entry mode body resolver)] backend engines]
      (is (= expected (run-program backend ast registry {'boxed-key #(host-int/promote 0)}))
          (str mode " " backend)))))


(def ^:private unwind-source
  (str "def bridge(boom, record):\n"
       "    try:\n"
       "        return boom()\n"
       "    finally:\n"
       "        record('python')\n"
       "bridge"))


(deftest clojure-python-clojure-unwind-test
  (let [python (module-body (packet unwind-source))
        clj (clojure/lower-unit
              '[(defn boom []
                  (try (throw "escape")
                       (finally (record "clojure-inner"))))
                (defn outer []
                  (try (bridge boom record)
                       (finally (record "clojure-outer"))))
                outer]
              '[record bridge])
        driver (fn [make-python]
                 (u/let1 '%make-python make-python
                   (u/let1 '%make-clojure clj
                     (u/sexp->uast
                       '(let [trace (cell/new [])
                              record (clj/make-callable
                                       {} (fn [args] (cell/set! trace (conj (cell/get trace) (data/nth args 0)))))
                              bridge (%make-python)
                              ctx (clj/rt-ctx)]
                          (do (clj/define-global! "record" record)
                              (clj/define-global! "bridge" bridge)
                              (cell/set! ctx (yrt.effect/ctx nil 4 9 nil))
                              (let [outer (clj/run-main %make-clojure)
                                    before (cell/get ctx)
                                    call (fn []
                                           (clj/try
                                             (fn []
                                               (yrt.effect/handle ctx :unrelated
                                                 (fn [payload k] (record "unexpected"))
                                                 (fn [] (clj/invoke-callable outer [] []))))
                                             (fn [e] e)))
                                    a (call)
                                    first-restored (data/content= before (cell/get ctx))
                                    b (call)]
                                (conj [] a b first-restored
                                      (data/content= before (cell/get ctx)) (cell/get trace)))))))))]
    (doseq [mode modes
            :let [program (driver (u/lam [] (u/app (u/v 'clj.py/run-main) python
                                                   (u/lam ['name] (u/lit {})))))
                  ast (if (= mode :bundled)
                        (runtime/bundled program)
                        (u/annotate-call-sites
                          (u/mark-tails
                            (u/seq-nodes
                              (conj (mapv (fn [{:keys [module]}] (u/app (u/v 'require) (u/lit module)))
                                          (:yang.semantics/ambient-modules (descriptor)))
                                    (u/then (u/app (u/v 'clj/init!)) program))))))]
            backend engines]
      (is (= ["escape" "escape" true true
              ["clojure-inner" "python" "clojure-outer" "clojure-inner" "python" "clojure-outer"]]
             (run-program backend ast (:registry @composition) {}))
          (str mode " " backend)))))


(deftest entry-preserves-verbatim-values-and-shared-gensym-test
  (let [body (u/sexp->uast
               '(fn [globals globals-fn]
                  (conj [] :yin.k/ok
                        (clj.py/call (clojure.core/gensym) [17])
                        (clj.py/call (clojure.core/gensym) ["temp" 17]))))
        resolver (u/lam ['name] (u/lit {}))
        registry (:registry @composition)]
    (doseq [mode modes :let [ast (entry mode body resolver)] backend engines]
      (is (= [:yin.k/ok 'G__17 'temp17] (run-program backend ast registry {}))
          (str mode " " backend)))))


(deftest default-selection-and-snapshot-test
  (let [catalog (frontends/standard)
        state (repl/create-state {:frontends catalog :lang :yang/python})
        legacy-catalog (assoc catalog :yang.frontend/preferred [:yang/python "py-v1"])
        legacy (repl/create-state {:frontends legacy-catalog :lang :yang/python})
        explicit (repl/create-state {:frontends catalog :lang [:yang/python "py-v1"]})
        [advanced result] (repl/eval-input state "1 == 1.0")
        derivation (val (first (:frontend-derivations advanced)))]
    (is (= ["clojure-core-v1" "py-v1"] (frontend/revisions catalog :yang/python)))
    (is (= "false" result))
    (is (= "True" (second (repl/eval-input legacy "1 == 1.0"))))
    (is (= "True" (second (repl/eval-input explicit "1 == 1.0"))))
    (is (= "false" (second (repl/eval-input advanced "1 == 1.0"))))
    (is (= "clojure-core-v1" (:yang.frontend/revision derivation)))
    (is (every? jing/segment-address?
                (map derivation [:yang.frontend/lowering-profile :yang.frontend/runtime-profile
                                 :yang.frontend/input :yang.frontend/output
                                 :yang.frontend/ambient-resolver])))
    (is (= (get-in @composition [:profiles contract/clojure-id :closure])
           (:yang.frontend/runtime-closure derivation)))))


(deftest ambient-composition-swap-test
  (let [{:keys [artifacts registry contract pins profiles]} @composition
        {:keys [descriptor runtime] :as original} (get profiles contract/clojure-id)
        alias "identity"
        alternate {:module 'clj.py :export 'ex-info :kind :cell-accessor}
        swapped (assoc-in descriptor [:yang.semantics/ambient-projections alias] alternate)
        grant [(pins 'clj.py) 'clj.py/ex-info [:projection alias :cell-accessor]]
        admitted-contract (update contract :roles conj grant)
        admit #(profiles/admit swapped runtime % artifacts registry
                               {:data {:max-items 1048576}
                                :integer {:bits 100000 :digits 4300}})
        profile (assoc original :descriptor (admit admitted-contract)
                               :descriptor-address (jing/segment-key swapped))
        source "identity('swapped', 17)"
        pk (packet source)
        compile-packet (fn [pk] (lower/lower-packet pk {:descriptor (:descriptor profile)}))
        manifest (-> (frontend/select (frontends/standard)
                                      {:yang.frontend/id :yang/python})
                     :manifest
                     (assoc :yang.frontend/lowering-profile (:descriptor-address profile)
                            :yang.frontend/support-profile (:descriptor-address profile)))
        catalog (frontend/install frontend/empty-catalog manifest
                                  {:parse packet :lower compile-packet :admit clj/admit
                                   :derive #(profiles/derivation manifest profile %1 %2)})
        [state _] (repl/eval-input (repl/create-state {:frontends catalog :lang :yang/python}) source)
        derivation (val (first (:frontend-derivations state)))
        expected {:yin.k/message "swapped" :yin.k/data 17}]
    (is (= swapped (:descriptor profile)))
    (is (= {:defect :export-role :target 'clj.py/ex-info :address (pins 'clj.py)
            :role [:projection alias :cell-accessor]}
           (select-keys (refusal #(admit contract)) [:defect :target :address :role])))
    (doseq [mode modes backend engines]
      (is (= expected (run-program backend (lower/lower-packet pk {:descriptor swapped :prelude mode})
                                  registry {}))
          (str mode " " backend)))
    (is (= expected (:last-value state)))
    (is (= (:descriptor-address profile) (:yang.frontend/lowering-profile derivation)))
    (is (= (pins 'clj.py) (get (into {} (:yang.frontend/runtime-closure derivation)) 'clj.py)))
    (is (= (:yang.frontend/ambient-resolver (meta (compile-packet pk)))
           (:yang.frontend/ambient-resolver derivation)))
    (is (not= (:yang.frontend/ambient-resolver (meta (lower-test :bundled pk)))
              (:yang.frontend/ambient-resolver derivation)))))

(deftest default-repl-reports-guest-errors-and-keeps-the-session-test
  (let [state (frontends/create-state {:lang :yang/python})
        [primed missing] (repl/eval-input state
                           "even_squares = [x**2 for x in range(1, 11) if x % 2 == 0]")
        [assigned quiet] (repl/eval-input primed
                           "even_squares = [x**2 for x in [1,2,3,4,5,6,7,8,9,10] if x % 2 == 0]")
        [failed zero] (repl/eval-input assigned "1/0")
        [done displayed] (repl/eval-input failed "even_squares")]
    (is (str/starts-with? missing "Error: "))
    (is (str/includes? missing ":name-error"))
    (is (str/includes? missing "\"range\""))
    (is (= :primed (:frontend-session primed)))
    (is (= "" quiet))
    (is (str/starts-with? zero "Error: "))
    (is (str/includes? zero ":zero-division"))
    (is (= "[4 16 36 64 100]" displayed))
    (is (= [4 16 36 64 100] (:last-value done)))))

(deftest profile-program-root-goldens-test
  (doseq [[revision source roots]
          [[contract/clojure-id numeric-source
            [:segment/blake3-d6d83f256c058f53aaed1dae86f6e16a7d308554ff205331232bee829c7b9632
             :segment/blake3-85504583b2faeabf1193097aa86d29ffa554c846ad4cf5fdf903c318bed29556]]
           [contract/clojure-id collections-source
            [:segment/blake3-23b3da145be32363cb53505e28e059dbd91e0c06a9e753bf025c6bd713c32b38
             :segment/blake3-79c079fc94a05964de723967abd14ab8de45b0043fc207e3846095866ff88799]]
           [contract/clojure-id unwind-source
            [:segment/blake3-c993472e551d5da051ec6a2fccb373953906bf69b111bacca6ac7c9a9496ec40
             :segment/blake3-51459075501c128d95feef0356e1c9fa6f7a037a629eb42d587a4f1a72bd12a7]]
           ;; py/assert thunk prelude + :py/missing sentinel (ruling 1795660000000):
           ;; re-minted after verified ANTLR generation in two independent JVMs.
           [contract/legacy-id "print(1 + 2, 1 == 1.0)"
            [:segment/blake3-f63b272e99adfeeeb8a5b11e040f558a09d5e5651c32b760853731fbb241a1cb
             :segment/blake3-40df036d1e1f6d2681d451d9c632fa73924c1ff20a0ed46abdaef4f26ded0d59]]]
          :let [pk (packet source)
                descriptor (:descriptor (profiles/verify-builtin! revision))]
          [mode root] (map vector modes roots)]
    (is (= root (:root (vm/ast->semantic-bytecode
                         (lower/lower-packet pk {:descriptor descriptor :prelude mode}))))
        (str revision " " mode))))
