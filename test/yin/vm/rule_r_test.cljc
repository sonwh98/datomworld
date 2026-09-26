(ns yin.vm.rule-r-test
  "Rule R: `yin/def` is syntax, never a name. A variable naming it is
   legal only as the operator of a two-operand definition whose first
   operand is a literal symbol other than itself; it is never a binder, a
   store key, a definition key, a macro name, or a primitive. Every
   executing lookup refuses it (`engine/resolve-var`), every definition
   transition writes its literal key without resolving its operator, and
   every persistent-code loader requires and compares a contract stamp.

   Each role is exercised on every loader (walker datoms and rows, semantic
   datoms and vectors, stack, register), at transition time on raw walker
   control, and at construction; the four backends agree on the stores a
   definition program writes."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.space.query :as query]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.code :as code]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.macro :as m]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf :as ucf]))


;; =============================================================================
;; Helpers
;; =============================================================================

(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [s]
  {:type :variable, :name s})


(defn- app
  [f & args]
  {:type :application, :operator f, :operands (vec args)})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- def!
  [k val]
  (app (v 'yin/def) (lit k) val))


(defn- then
  "`first` for its effect, then `second`: `((fn [_] second) first)`."
  [first-ast second-ast]
  (app (lam '[_] second-ast) first-ast))


(defn- refusal
  "The ex-data of what `thunk` throws, unwrapping a loader's `:defect`, or
   nil when it returns."
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (let [d (ex-data e)]
           (or (:defect d) d {})))))


(defn- rule-of
  [thunk]
  (:rule (refusal thunk)))


(defn- segment-batch
  "A `:yin.code/*` segment batch whose instructions are `tuples`, positional
   operands named by `code/vector-operand-table` (no `:pc` operands)."
  [tuples]
  (into [[-1 :yin.code/type :segment 0 0]
         [-1 :yin.code/length (count tuples) 0 0]]
        (mapcat (fn [pc t]
                  (let [e (- -2 pc)]
                    (into [[e :yin.code/segment -1 0 0]
                           [e :yin.code/pc pc 0 0]
                           [e :yin.code/op (nth t 0) 0 0]]
                          (map (fn [[attr _] x] [e attr x 0 0])
                               (get code/vector-operand-table (nth t 0))
                               (rest t)))))
                (range)
                tuples)))


(defn- register-image
  [n instructions]
  {:bodies [{:locals 0, :registers n, :start 0,
             :end (dec (count instructions))}],
   :instructions instructions})


(defn- walker-run
  [ast]
  (vm/eval (tu/create-vm) ast))


(defn- semantic-run
  [ast]
  (vm/run (semantic/vm-load-program
            (semantic/create-vm {:make-stream tu/make-stream,
                                 :capability-secret tu/secret})
            (linearize/lower-ast ast)
            vm/semantic-contract)))


(defn- stack-run
  [ast]
  (vm/run (dvm/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                         {:primitives vm/primitives,
                          :make-stream tu/make-stream,
                          :capability-secret tu/secret
                          :contract vm/stack-contract})))


(defn- register-run
  [ast]
  (vm/run (rvm/create-vm (:image (rc/adapt (vm/ast->datoms ast)))
                         {:primitives vm/primitives,
                          :make-stream tu/make-stream,
                          :capability-secret tu/secret
                          :contract vm/register-contract})))


(def ^:private backends
  {:ast-walker walker-run,
   :semantic semantic-run,
   :stack stack-run,
   :register register-run})


;; =============================================================================
;; The resolver refuses the name before env or store
;; =============================================================================

(deftest resolve-var-refuses-before-env-and-store
  (let [d (refusal #(engine/resolve-var {'yin/def 1} {'yin/def 2}
                                        vm/primitives nil 'yin/def))]
    (is (= :reserved-name (:rule d)))
    (is (= :variable (:role d)))
    (is (= 'yin/def (:name d))))
  (is (= 1 (engine/resolve-var {'require 1} {} vm/primitives nil 'require))
      "require stays an ordinary name: an env binding shadows it"))


(deftest yin-def-is-not-a-primitive
  (is (not (contains? vm/primitives 'yin/def)))
  (is (not (contains? vm/primitive-profiles 'yin/def)))
  (is (contains? vm/primitives 'require)))


;; =============================================================================
;; Definitions: identical stores on all four backends
;; =============================================================================

(def ^:private definition-programs
  "label -> [ast value store-slice]."
  {"define then read" [(then (def! 'x (lit 1))
                             (def! 'y (app (v '+) (v 'x) (lit 1))))
                       2
                       {'x 1, 'y 2}],
   "redefine x twice reads the second value"
   [(then (def! 'x (lit 1)) (then (def! 'x (lit 2)) (v 'x)))
    2
    {'x 2}],
   "a definition's value is its own value" [(def! 'z (lit 9)) 9 {'z 9}],
   "a stored closure is called by name"
   [(then (def! 'f (lam '[n] (app (v '*) (v 'n) (lit 2))))
          (then (def! 'r (app (v 'f) (lit 5))) (v 'r)))
    10
    {'r 10}],
   "the reserved symbol as a literal value is data"
   [(def! 'q (lit 'yin/def)) 'yin/def {'q 'yin/def}]})


(deftest definitions-agree-across-the-four-backends
  (doseq [[label [ast value slice]] definition-programs
          [backend run] backends]
    (testing (str label " on " (name backend))
      (let [done (run ast)]
        (is (= value (vm/value done)))
        (is (= slice (select-keys (vm/store done) (keys slice))))))))


(deftest a-definition-never-resolves-its-operator
  (testing "an env or store cannot redirect a definition: both are refused
            at construction, and the operator is never looked up"
    (is (= :reserved-name
           (rule-of #(ast-walker/create-vm {:env {'yin/def (fn [_ _] 0)}}))))
    (is (= 1 (get (vm/store (walker-run (def! 'x (lit 1)))) 'x)))))


;; =============================================================================
;; A parked read inside a definition's value resumes and writes the store
;; =============================================================================

(def ^:private parked-definition
  "`((fn [s] (yin/def 'x (stream/next (stream/cursor s)))) (stream/make 4))`
   -- the read blocks on the empty stream."
  (app (lam '[s] (def! 'x {:type :stream/next,
                           :source {:type :stream/cursor,
                                    :source (v 's)}}))
       {:type :stream/make, :buffer 4}))


(deftest a-parked-read-in-a-definition-resumes-and-writes
  (doseq [[backend run] (select-keys backends [:ast-walker :semantic])]
    (testing (name backend)
      (let [blocked (run parked-definition)
            handle (get (:resources blocked) :stream-0)]
        (is (vm/blocked? blocked))
        (is (not (contains? (vm/store blocked) 'x)))
        (stream/append! handle 7)
        (let [done (vm/run blocked)]
          (is (= 7 (vm/value done)))
          (is (= 7 (get (vm/store done) 'x))))))))


;; =============================================================================
;; Every reserved role, refused at load on every loader
;; =============================================================================

(def ^:private reserved-asts
  "label -> [ast role]: one tree per reserved role."
  {"a variable" [(v 'yin/def) :variable],
   "an operand" [(app (v 'f) (v 'yin/def)) :variable],
   "an operator of another arity" [(app (v 'yin/def) (lit 'x))
                                   :definition-shape],
   "a non-literal key" [(app (v 'yin/def) (v 'k) (lit 1)) :definition-shape],
   "a definition key" [(def! 'yin/def (lit 1)) :definition-key],
   "a binder" [(lam '[yin/def] (lit 1)) :binder],
   "a store-get key" [{:type :vm/store-get, :key 'yin/def} :store-key],
   "a store-put key" [{:type :vm/store-put, :key 'yin/def, :val 1}
                      :store-key]})


(deftest reserved-roles-are-refused-by-the-walker-datom-and-row-loaders
  (doseq [[label [ast role]] reserved-asts]
    (testing label
      (is (= role (:role (vm/ast-reserved-defect ast))))
      (is (= :reserved-name
             (rule-of #(ast-walker/vm-load-program
                         (tu/create-vm) (vm/ast->datoms ast)
                         vm/ast-contract)))
          "the walker's datom loader validates the whole tree")
      (is (= :reserved-name
             (rule-of #(ast-walker/vm-load-rows
                         (tu/create-vm) (vm/ast->semantic-bytecode ast)
                         vm/ast-contract)))
          "the row loader's S7.4 validation includes Rule R")
      (is (= :reserved-name (rule-of #(linearize/lower-ast ast)))
          "the datom lane lowerer refuses")
      (is (= :reserved-name
             (rule-of #(dl/adapt (vm/ast->datoms ast))))
          "the de Bruijn resolver refuses"))))


(deftest a-shared-row-is-judged-at-every-occurrence
  (testing "one `[:variable yin/def]` row, legal as an operator and
            illegal as an operand of the same tree"
    (let [ast (then (def! 'x (lit 1)) (app (v 'f) (v 'yin/def)))
          d (vm/validate-rows (vm/ast->semantic-bytecode ast))]
      (is (= :reserved-name (:rule d)))
      (is (some? (:path d))))))


(deftest reserved-operands-are-refused-by-the-semantic-loaders
  (doseq [[label v] {"a :var" [[:var 'yin/def] [:halt]],
                     "the old call shape" [[:var 'yin/def] [:push]
                                           [:const 'x] [:push]
                                           [:const 1] [:push]
                                           [:call 2 false] [:halt]],
                     "a :define key" [[:const 1] [:define 'yin/def] [:halt]],
                     "a binder" [[:closure '[yin/def] 2] [:halt]
                                 [:const 1] [:return]],
                     "a store-get key" [[:store-get 'yin/def] [:halt]],
                     "a store-put key" [[:store-put 'yin/def 1] [:halt]]}]
    (testing label
      (is (= :reserved-name (:rule (code/well-formed-vector? v))))
      (is (= :reserved-name
             (rule-of #(semantic/load-vector (semantic/create-vm) v
                                             vm/semantic-contract)))
          "the vector loader")))
  (doseq [[label tuples] {"a :var" [[:var 'yin/def] [:halt]],
                          "a :define key" [[:const 1] [:define 'yin/def]
                                           [:halt]],
                          "a store-put key" [[:store-put 'yin/def 1]
                                             [:halt]]}]
    (testing (str label " (datom loader)")
      (is (= :reserved-name
             (rule-of #(semantic/vm-load-program
                         (semantic/create-vm)
                         (segment-batch tuples)
                         vm/semantic-contract)))))))


(deftest reserved-operands-are-refused-by-the-stack-loader
  (doseq [[label image] {"the old call shape" [[:load-free 'yin/def] [:push]
                                               [:const 'x] [:push]
                                               [:const 1] [:push]
                                               [:call 2 false] [:halt]],
                         "a :define key" [[:const 1] [:define 'yin/def]
                                          [:halt]],
                         "a store-get key" [[:store-get 'yin/def] [:halt]]}]
    (testing label
      (is (= :reserved-name
             (rule-of #(dvm/create-vm image {:contract vm/stack-contract}))))
      (is (= :reserved-name
             (rule-of #(dvm/load-image (dvm/create-vm []) image
                                       vm/stack-contract)))))))


(deftest reserved-operands-are-refused-by-the-register-loader
  (doseq [[label image]
          {"the old call shape" (register-image
                                  3 [[:load-free 0 'yin/def] [:const 1 'x]
                                     [:const 2 1] [:call 0 0 [1 2] false []]
                                     [:halt 0]]),
           "a :define key" (register-image 1 [[:const 0 1]
                                              [:define 0 'yin/def 0]
                                              [:halt 0]]),
           "a store-put key" (register-image 1 [[:store-put 0 'yin/def 1]
                                                [:halt 0]])}]
    (testing label
      (is (= :reserved-name
             (rule-of #(rvm/create-vm image
                                      {:contract vm/register-contract})))))))


;; =============================================================================
;; Raw control: refused at transition time
;; =============================================================================

(defn- run-raw
  "Step a walker through supplied control: no load event exists."
  [control]
  (vm/run (assoc (tu/create-vm) :control control :halted? false)))


(deftest raw-walker-control-is-refused-at-transition
  (doseq [[label [control role]]
          {"a variable" [(v 'yin/def) :variable],
           "a binder" [(lam '[yin/def] (lit 1)) :binder],
           "a store-get key" [{:type :vm/store-get, :key 'yin/def} :store-key],
           "a store-put key" [{:type :vm/store-put, :key 'yin/def, :val 1}
                              :store-key],
           "a store-update key" [{:type :vm/store-update, :key 'yin/def,
                                  :fn (fn [x] x), :args []}
                                 :store-key],
           "a definition key" [(def! 'yin/def (lit 1)) :definition-key],
           "a malformed definition" [(app (v 'yin/def) (lit 'x))
                                     :definition-shape]}]
    (testing label
      (let [d (refusal #(run-raw control))]
        (is (= :reserved-name (:rule d)))
        (is (= role (:role d))))))
  (testing "a raw definition continuation cannot write the reserved key"
    (let [vm (assoc (tu/create-vm)
                    :control nil
                    :k {:type :eval-define, :name 'yin/def, :next nil}
                    :value 1
                    :halted? false)]
      (is (= :store-key (:role (refusal #(vm/step vm))))))))


;; =============================================================================
;; Store writes: one engine/store-put
;; =============================================================================

(deftest store-put-refuses-the-reserved-key
  (is (= :store-key (:role (refusal #(engine/store-put {} 'yin/def 1)))))
  (is (= {'x 1} (engine/store-put {} 'x 1)))
  (testing "the :vm/store-put effect goes through it"
    (is (= :reserved-name
           (rule-of #(engine/handle-effect
                       (tu/create-vm)
                       {:effect :vm/store-put, :key 'yin/def, :val 1}
                       {}))))))


(deftest a-ready-entry-carries-only-minted-keys
  (is (= :resource-update-key
         (rule-of #(engine/resume-from-run-queue
                     {:ready-queue [{:resource-updates {'yin/def 1}}],
                      :store {}}
                     (fn [base _ _] base)))))
  (let [resumed (engine/resume-from-run-queue
                  {:ready-queue [{:resource-updates {:c 1}}], :store {}}
                  (fn [base _ _] base))]
    (is (= {:c 1} (:resources resumed)))
    (is (= {} (:store resumed)) "a resource update never reaches the store")))


;; =============================================================================
;; Construction: no supplied env, store, or registry binds the name
;; =============================================================================

(deftest construction-refuses-a-binding-of-the-name
  (let [bound {'yin/def 1}
        registry (assoc vm/primitives 'yin/def (fn [_ _] nil))]
    (doseq [[label thunk]
            {"walker env" #(ast-walker/create-vm {:env bound}),
             "walker registry" #(ast-walker/create-vm {:primitives registry}),
             "semantic env" #(semantic/create-vm {:env bound}),
             "semantic registry" #(semantic/create-vm {:primitives registry}),
             "stack env" #(dvm/create-vm [] {:free-env bound}),
             "stack store" #(dvm/create-vm [] {:store bound}),
             "stack registry" #(dvm/create-vm [] {:primitives registry}),
             "register env" #(rvm/create-vm nil {:free-env bound}),
             "register store" #(rvm/create-vm nil {:store bound}),
             "register registry" #(rvm/create-vm nil {:primitives registry})}]
      (testing label
        (is (= :reserved-name (rule-of thunk)))))))


(deftest construction-refuses-a-module-registry-binding-the-name
  (let [profile (vm/primitive-profile 'def :pure [0] #{} :none)
        by-path (module/register-host-module (module/empty-registry)
                                             'yin {'def (fn [] nil)}
                                             {'def profile})
        by-key (module/register-host-module (module/empty-registry)
                                            'lib {'yin/def (fn [] nil)}
                                            {'yin/def profile})
        clean (module/register-host-module (module/empty-registry)
                                           'lib {'f (fn [] nil)}
                                           {'f profile})]
    (doseq [[label registry] {"the yin.def path" by-path,
                              "a slice key" by-key}
            [kernel thunk]
            {"walker" #(ast-walker/create-vm {:modules registry}),
             "semantic" #(semantic/create-vm {:modules registry}),
             "stack" #(dvm/create-vm [] {:modules registry}),
             "register" #(rvm/create-vm nil {:modules registry})}]
      (testing (str kernel ", " label)
        (is (= :reserved-name (rule-of thunk)))
        (is (= :registry (:role (refusal thunk))))))
    (testing "a registry binding no reserved name is accepted"
      (is (some? (dvm/create-vm [] {:modules clean}))))))


(deftest the-expander-refuses-the-name
  (let [macro (m/macro-entry (m/ast->packet (lam '[x] (v 'x)))
                             vm/ast-contract)]
    (testing "a seeded macro named yin/def is refused at context
              construction"
      (is (= :macro (:role (refusal #(m/make-ctx {:token "t",
                                                  :store {'yin/def
                                                          macro}}))))))
    (testing "batch expansion refuses a context store binding it"
      (let [ctx (assoc (m/make-ctx {:token "t"}) :store {'yin/def macro})
            batch [:yin.program/batch [(m/ast->packet (lit 1))] 0 [] []]]
        (is (= :reserved-name (rule-of #(m/expand-batch batch ctx))))))
    (testing "harvest refuses it as a definition key"
      (let [tree (m/ast->packet (app (v 'yin/def) (lit 'yin/def) (lit 1)))
            batch [:yin.program/batch [tree] 0 []
                   [[:yin.macro/harvest 0 0 []]]]]
        (is (= :definition-key
               (:role (refusal #(m/expand-batch
                                  batch (m/make-ctx {:token "t"}))))))))
    (testing "the transformer runner loads under the verified stamp"
      (is (= (m/ast->packet (lit 1))
             (m/invoke macro [(m/ast->packet (lit 1))]
                       (m/make-ctx {:token "t"})))))))


(deftest a-macro-packet-runs-only-under-its-verified-stamp
  (let [packet (m/ast->packet (lam '[x] (v 'x)))
        operand (m/ast->packet (lit 1))
        call-batch [:yin.program/batch [(m/ast->packet (app (v 'm) (lit 1)))]
                    0 [] []]
        cases {"unstamped" [packet :contract-missing],
               "old-stamped" [(m/macro-entry packet "v2") :contract-mismatch],
               "a stamp but no contract" [(m/macro-entry packet nil)
                                          :contract-missing]}]
    (doseq [[label [entry rule]] cases]
      (testing label
        (is (= rule (rule-of #(m/make-ctx {:token "t", :store {'m entry}})))
            "a seeded store is verified at context construction")
        (let [ctx (assoc (m/make-ctx {:token "t"}) :store {'m entry})]
          (is (= rule (rule-of #(m/expand-batch call-batch ctx)))
              "a store supplied past make-ctx is verified by expand-batch"))
        (let [ctx (m/make-ctx {:token "t"})]
          (is (= rule (rule-of #(m/invoke entry [operand] ctx)))
              "direct invoke verifies before it runs anything"))))
    (testing "the runner itself loads only under the stamp it is handed"
      (let [req {:macro-tree packet, :env {'x operand},
                 :prelude (m/prelude identity), :max-steps 1000}]
        (is (= :contract-missing
               (rule-of #(m/bounded-row-evaluator req))))
        (is (= :contract-mismatch
               (rule-of #(m/bounded-row-evaluator
                           (assoc req :contract "v2")))))
        (is (= {:value operand}
               (m/bounded-row-evaluator
                 (assoc req :contract vm/ast-contract))))))
    (testing "a current-stamped packet expands and runs"
      (let [entry (m/macro-entry packet vm/ast-contract)
            ctx (m/make-ctx {:token "t", :store {'m entry}})]
        (is (= :ok (:status (m/expand-batch call-batch ctx))))
        (is (= operand (m/invoke entry [operand] ctx)))))
    (testing "a harvested macro is stamped by the expander that produced it"
      (let [tree (m/ast->packet (app (v 'yin/def) (lit 'm)
                                     (lam '[x] (v 'x))))
            batch [:yin.program/batch [tree] 0
                   [[:yin.macro/definition 0 []]]
                   [[:yin.macro/harvest 0 0 []]]]
            r (m/expand-batch batch (m/make-ctx {:token "t"}))]
        (is (= vm/ast-contract
               (:yin.macro/contract (get-in r [:ctx :store 'm]))))))))


;; =============================================================================
;; Contract stamps
;; =============================================================================

(deftest every-persistent-code-loader-requires-and-compares-a-stamp
  (let [ast (app (v '+) (lit 1) (lit 2))
        datoms (vm/ast->datoms ast)
        rows (vm/ast->semantic-bytecode ast)
        batch (linearize/lower-ast ast)
        v1 (:vector (linearize/lower-rows rows))
        stack-image (:image (dl/adapt datoms))
        register-image* (:image (rc/adapt datoms))
        loaders
        {"walker datoms" [#(ast-walker/vm-load-program (tu/create-vm)
                                                       datoms %)
                          "v2"],
         "walker rows" [#(ast-walker/vm-load-rows (tu/create-vm) rows %)
                        "v2"],
         "semantic datoms" [#(semantic/vm-load-program (semantic/create-vm)
                                                       batch %)
                            "v2"],
         "semantic vector" [#(semantic/load-vector (semantic/create-vm) v1 %)
                            "v2"],
         "stack" [#(dvm/create-vm stack-image
                                  {:primitives vm/primitives, :contract %})
                  "b1"],
         "register" [#(rvm/create-vm register-image*
                                     {:primitives vm/primitives,
                                      :contract %})
                     "r1"]}]
    (doseq [[label [load old]] loaders]
      (testing label
        (is (= :contract-missing (rule-of #(load nil)))
            "an unstamped load is refused")
        (let [d (refusal #(load old))]
          (is (= :contract-mismatch (:rule d))
              "an old-stamped image with no definition fails by stamp")
          (is (= old (:actual d))))))
    (is (= [vm/ast-contract vm/semantic-contract vm/stack-contract
            vm/register-contract]
           ["v3" "v3" "b2" "r2"]))
    (testing "vm/eval supplies the current stamp itself"
      (is (= 3 (vm/value (vm/eval (tu/create-vm) ast)))))))


(deftest the-lowering-adapters-verify-the-incoming-stamp
  (testing "an adapter lowers only AST code whose own stamp it verified;
            it never assigns one on the caller's behalf"
    (let [ast (app (v '+) (lit 1) (lit 2))
          datoms (vm/ast->datoms ast)
          rows (vm/ast->semantic-bytecode ast)
          load-ast (linearize/ast-loader semantic/vm-load-program)
          load-rows (linearize/rows-loader semantic/load-vector)
          adapters {"ast-loader" #(load-ast (semantic/create-vm) datoms %)
                    "rows-loader" #(load-rows (semantic/create-vm) rows %)}]
      (doseq [[label load] adapters]
        (testing label
          (is (= :contract-missing (rule-of #(load nil)))
              "an unstamped input is refused before lowering")
          (let [d (refusal #(load "v2"))]
            (is (= :contract-mismatch (:rule d))
                "an old-stamped input is refused before lowering")
            (is (= "v2" (:actual d))))
          (is (= 3 (vm/value (vm/run (load vm/ast-contract))))
              "a current-stamped input lowers, loads, and runs")))))
  (testing "the explicitly trusted fresh-producer path supplies the stamp"
    (let [load (vm/fresh-code-loader
                 (linearize/ast-loader semantic/vm-load-program)
                 vm/ast-contract)]
      (is (= 3 (vm/value (vm/run (load (semantic/create-vm)
                                       (vm/ast->datoms
                                         (app (v '+) (lit 1) (lit 2)))))))))))


(deftest canonicalize-verifies-the-batch-stamp
  (let [batch (segment-batch [[:const 1] [:halt]])]
    (is (= :contract-missing (rule-of #(ucf/canonicalize batch nil))))
    (is (= :contract-mismatch (rule-of #(ucf/canonicalize batch "v2"))))
    (is (= {:yin.code/contract "v3", :yin.k/version 0}
           (:yin.k/contract (ucf/canonicalize batch vm/semantic-contract)))
        "the stamp on the outcome is the one verified, never assigned")))


;; =============================================================================
;; Free names
;; =============================================================================

(deftest free-names-exclude-the-definition-operator
  (let [ast (then (def! 'x (lit 1)) (app (v 'f) (v 'x)))
        bc (vm/ast->semantic-bytecode ast)
        db (query/relation (vals (:rows bc)))
        occ (query/relation (vm/occurrences bc))]
    (is (= #{'f 'x} (vm/free-names db occ (:root bc))))))
