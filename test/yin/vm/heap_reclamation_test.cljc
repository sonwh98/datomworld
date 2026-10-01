(ns yin.vm.heap-reclamation-test
  "Heap reclamation slice 1 (Architect heap-reclamation design Q6): the
   deterministic, allocation-triggered, stop-the-world mark-sweep over the
   task `:heap`, on all four VMs -- the AST walker, the semantic VM, and
   the de Bruijn stack and register VMs."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; The four VMs
;; =============================================================================

(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (module/register-cell-module (module/default-registry))})


(def ^:private builders
  "Each builder takes an AST and composition opts, and answers the loaded,
   not yet run, VM."
  {:ast-walker (fn [ast o]
                 (walker/vm-load-rows (walker/create-vm o)
                                      (vm/ast->semantic-bytecode ast)
                                      vm/ast-contract)),
   :semantic (fn [ast o]
               (semantic/load-vector
                 (semantic/create-vm o)
                 (:vector (linearize/lower-rows (vm/ast->semantic-bytecode
                                                  ast)))
                 vm/semantic-contract)),
   :stack (fn [ast o]
            (dvm/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                           (assoc o :contract vm/stack-contract))),
   :register (fn [ast o]
               (rvm/create-vm (:image (rc/adapt (second
                                                  (vm/ast->datoms-with-root
                                                    ast))))
                              (assoc o :contract vm/register-contract)))})


(defn- run-on
  [k ast threshold]
  (vm/run ((get builders k) ast (assoc opts :gc-threshold threshold))))


(defn- outcome
  "The value `vm` halted with, or `[:thrown reason]`."
  [thunk]
  (try (vm/value (thunk))
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         [:thrown (:reason (ex-data e))])))


(defn- refusal-of
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (or (ex-data e) {}))))


;; =============================================================================
;; AST helpers
;; =============================================================================

(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [sym]
  {:type :variable, :name sym})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- if-node
  [t c a]
  {:type :if, :test t, :consequent c, :alternate a})


(defn- then
  "Evaluate `first-node` for effect, then `next-node`."
  [first-node next-node]
  (app (lam ['_] next-node) first-node))


(defn- let1
  [sym init body]
  (app (lam [sym] body) init))


(defn- discard
  "Evaluate `node` inside a call whose frame is dropped, answering nil:
   nothing of its value stays reachable."
  [node]
  (app (lam [] (then node (lit nil)))))


(defn- define
  [sym value]
  (app (v 'yin/def) (lit sym) value))


(defn- new-cell
  [x]
  (app (v 'cell/new) x))


(defn- get-cell
  [c]
  (app (v 'cell/get) c))


(defn- set-cell!
  [c x]
  (app (v 'cell/set!) c x))


(defn- pair
  [a b]
  (app (v 'conj) (app (v 'conj) (lit []) a) b))


(def ^:private garbage
  "Four allocations nothing keeps."
  (discard (then (new-cell (lit :g1))
                 (then (new-cell (lit :g2))
                       (then (new-cell (lit :g3)) (new-cell (lit :g4)))))))


(def ^:private spin
  "Allocate one cell per iteration, `n` times, keeping none: answers
   :done. The cell is dropped with its own frame: the de Bruijn compilers
   emit no tail call here, so a pending return frame would hold a cell
   bound in the iteration's body, and that cell is live."
  (fn [n]
    (then (define 'spin
            (lam ['n]
                 (if-node (app (v '=) (v 'n) (lit 0))
                          (lit :done)
                          (then (discard (new-cell (v 'n)))
                                (app (v 'spin)
                                     (app (v '-) (v 'n) (lit 1)))))))
          (app (v 'spin) (lit n)))))


;; =============================================================================
;; Construction
;; =============================================================================

(deftest every-vm-starts-with-an-empty-collector-test
  (doseq [[k build] builders]
    (is (= {:since 0, :base 4096, :threshold 4096, :pinned #{}}
           (:gc (build (lit 1) opts)))
        (str k " defaults to the base threshold 4096"))
    (is (= 7 (:threshold (:gc (build (lit 1) (assoc opts :gc-threshold 7)))))
        (str k " takes the base as a composition parameter"))))


(deftest a-spawned-child-starts-with-an-empty-collector-test
  (doseq [k (keys builders)]
    (let [parent (run-on k (spin 5) 2)
          image (case k
                  :ast-walker (vm/ast->semantic-bytecode (lit 1))
                  :semantic (:vector (linearize/lower-rows
                                       (vm/ast->semantic-bytecode (lit 1))))
                  :stack (:image (dl/adapt (vm/ast->datoms (lit 1))))
                  :register (:image (rc/adapt (second (vm/ast->datoms-with-root
                                                        (lit 1))))))
          child (module/spawn-module parent
                                     image
                                     {:modules (:modules parent),
                                      :origin :t0.0,
                                      :ancestry [],
                                      :capability-secret tu/secret})]
      (is (pos? (:since (:gc parent))) (str k " the parent counted"))
      (is (= {:since 0, :base 2, :threshold 2, :pinned #{}} (:gc child))
          (str k " the child holds none of it but the composition's base")))))


;; =============================================================================
;; Bounded heap
;; =============================================================================

(deftest a-loop-with-one-live-cell-keeps-the-heap-bounded-test
  (doseq [k (keys builders)]
    (let [result (run-on k (spin 200) 16)]
      (testing (str k)
        (is (= :done (vm/value result)))
        (is (<= 200 (:id-counter result)) "200 cells were allocated")
        (is (<= (count (:heap result)) (+ 16 1))
            "the heap holds at most a threshold's allocations and the live one")))))


;; =============================================================================
;; Roots
;; =============================================================================

(defn- quiesce
  "`vm` with every kernel register cleared, so only what a test places
   is a root."
  [vm]
  (assoc vm
         :value nil
         :control nil
         :env {}
         :k nil
         :stack []
         :registers []
         :frames []
         :continuation []))


(defn- kept-and-junk
  "`[vm keep junk]`: `k`'s VM after allocating two cells, `:keep` and
   `:junk`, and their refs, quiesced."
  [k]
  (let [result (run-on k (pair (new-cell (lit :keep)) (new-cell (lit :junk)))
                       1000000)
        [keep junk] (vm/value result)]
    [(quiesce result) keep junk]))


(defn- survivors
  [vm]
  (set (map :value (vals (:heap (engine/collect vm))))))


(deftest a-quiesced-vm-keeps-nothing-test
  (doseq [k (keys builders)]
    (let [[q] (kept-and-junk k)]
      (is (= #{} (survivors q)) (str k " neither cell is reachable")))))


(deftest a-cell-reachable-only-through-each-root-survives-test
  (doseq [k (keys builders)]
    (let [[q keep] (kept-and-junk k)]
      (doseq [[root placed]
              {"the store" (assoc-in q [:store 'x] keep),
               "a module store" (assoc-in q [:module-stores :m 'x] keep),
               "a wait-set entry's :datom"
               (assoc q :wait-set [{:reason :put, :stream-id :s, :datom keep}]),
               "a ready-queue entry" (assoc q :ready-queue [{:value keep}]),
               "a parked entry" (assoc-in q [:parked :p] {:env {'x keep}}),
               "the value register" (assoc q :value [keep])}]
        (is (= #{:keep} (survivors placed)) (str k ": " root))))))


(deftest a-parked-continuation-keeps-its-cells-test
  (doseq [k (keys builders)]
    (let [parked (run-on k
                         (then garbage
                               (let1 'c (new-cell (lit :keep))
                                     (then {:type :vm/park}
                                           (get-cell (v 'c)))))
                         1000000)]
      (testing (str k)
        (is (= 1 (count (:parked parked))) "the continuation parked")
        (is (= #{:keep} (survivors (quiesce parked)))
            "only :parked holds the cell")))))


(deftest a-closure-env-keeps-its-cells-test
  (doseq [k (keys builders)]
    (let [result (run-on k
                         (then garbage
                               (let1 'c (new-cell (lit :keep))
                                     (lam [] (get-cell (v 'c)))))
                         1000000)
          closure (vm/value result)]
      (is (= :closure (:type closure)) (str k))
      (is (= #{:keep} (survivors (assoc (quiesce result) :value closure)))
          (str k " the closure's captured environment holds the cell")))))


(deftest a-cell-inside-another-cell-survives-test
  (doseq [k (keys builders)]
    (let [result (run-on k
                         (pair (new-cell (new-cell (lit :inner)))
                               (new-cell (lit :junk)))
                         1000000)
          [outer] (vm/value result)
          q (assoc-in (quiesce result) [:store 'x] outer)]
      (is (= 2 (count (:heap (engine/collect q)))) (str k))
      (is (= #{:inner} (disj (survivors q) (get-in q [:heap (:id outer) :value])))
          (str k " the inner cell is reached through the outer one")))))


(deftest an-evaluated-operand-survives-a-collection-test
  (testing "the first operand's ref sits only in the pending application --
            the walker frame's :evaluated, the semantic operand stack, the
            stack VM's stack, a register VM live register -- while the
            second operand allocates and collects at every allocation"
    (doseq [k (keys builders)]
      (is (= :first
             (outcome #(run-on k
                               (app (lam ['a '_] (get-cell (v 'a)))
                                    (new-cell (lit :first))
                                    garbage)
                               1)))
          (str k " through a call: a register VM saves the live register in
                  the return frame's :regs")))
    (testing "each operand allocates twice, so at the later operand's outer
              allocation :value has moved past the earlier operand's ref,
              whatever order a compiler evaluates them in"
      (doseq [k (keys builders)]
        (is (= [:a :b]
               (outcome #(run-on k
                                 (app (lam ['a 'b]
                                           (pair (get-cell (get-cell (v 'a)))
                                                 (get-cell (get-cell (v 'b)))))
                                      (new-cell (new-cell (lit :a)))
                                      (new-cell (new-cell (lit :b))))
                                 1)))
            (str k))))))


(deftest the-allocating-effects-value-is-a-root-test
  (doseq [k (keys builders)]
    (let [[q keep] (kept-and-junk k)
          q (assoc q :gc (assoc (:gc q) :since 0 :threshold 1))
          {:keys [state value]}
          (engine/handle-effect q (module/make-effect :cell/new {:val keep}) {})]
      (testing (str k)
        (is (zero? (:since (:gc state))) "the allocation collected")
        (is (= #{:keep keep} (set (map :value (vals (:heap state)))))
            "the boxed cell survived its own allocation's collection")
        (is (= keep (get-in state [:heap (:id value) :value])))))))


;; =============================================================================
;; Swept ids
;; =============================================================================

(deftest a-swept-ref-is-refused-and-its-id-never-reused-test
  (doseq [k (keys builders)]
    (let [[q keep junk] (kept-and-junk k)
          swept (engine/collect (assoc-in q [:store 'x] keep))
          get-junk #(engine/handle-effect
                      swept
                      (module/make-effect :cell/get {:cell junk})
                      {})
          {fresh :value, :keys [state]}
          (engine/handle-effect swept (module/make-effect :cell/new {:val 1}) {})]
      (testing (str k)
        (is (not (contains? (:heap swept) (:id junk))))
        (is (= {:reason :dead-or-forged-reference,
                :effect :cell/get,
                :kind :cell-ref,
                :id (:id junk)}
               (refusal-of get-junk)))
        (is (not= (:id junk) (:id fresh)) "a new allocation takes a fresh id")
        (is (not (contains? (:heap state) (:id junk))))
        (is (= :dead-or-forged-reference
               (:reason (refusal-of #(engine/handle-effect
                                       state
                                       (module/make-effect :cell/get
                                                           {:cell junk})
                                       {}))))
            "the dead ref stays dead after the allocation")))))


;; =============================================================================
;; Pinning
;; =============================================================================

(deftest a-ref-put-on-a-stream-stays-authentic-test
  (testing "a ref appended to an in-task stream, unreachable otherwise,
            survives collections at every allocation and reads back"
    (doseq [k (keys builders)]
      (is (= :pinned
             (outcome
               #(run-on
                  k
                  (let1 's {:type :stream/make, :buffer 8}
                        (let1 'cur {:type :stream/cursor, :source (v 's)}
                              (then (discard {:type :stream/put,
                                              :target (v 's),
                                              :val (new-cell (lit :pinned))})
                                    (then garbage
                                          (get-cell {:type :stream/next,
                                                     :source (v 'cur)})))))
                  1)))
          (str k)))))


;; =============================================================================
;; Determinism and semantics
;; =============================================================================

(def ^:private linked-sum
  "Build a 30-long linked list of cells `[n next]`, then sum it: 465."
  (then (define 'build
          (lam ['n 'acc]
               (if-node (app (v '=) (v 'n) (lit 0))
                        (v 'acc)
                        (app (v 'build)
                             (app (v '-) (v 'n) (lit 1))
                             (new-cell (pair (v 'n) (v 'acc)))))))
        (then (define 'sum
                (lam ['r 's]
                     (if-node (app (v '=) (v 'r) (lit nil))
                              (v 's)
                              (let1 'p (get-cell (v 'r))
                                    (app (v 'sum)
                                         (app (v 'get) (v 'p) (lit 1))
                                         (app (v '+)
                                              (v 's)
                                              (app (v 'get)
                                                   (v 'p)
                                                   (lit 0))))))))
              (app (v 'sum) (app (v 'build) (lit 30) (lit nil)) (lit 0)))))


(def ^:private counted-loop
  "Bump one cell 50 times, allocating garbage each time: 50."
  (let1 'c (new-cell (lit 0))
        (then (define 'bump
                (lam ['n]
                     (if-node (app (v '=) (v 'n) (lit 0))
                              (lit nil)
                              (then (new-cell (v 'n))
                                    (then (set-cell!
                                            (v 'c)
                                            (app (v '+)
                                                 (get-cell (v 'c))
                                                 (lit 1)))
                                          (app (v 'bump)
                                               (app (v '-)
                                                    (v 'n)
                                                    (lit 1))))))))
              (then (app (v 'bump) (lit 50)) (get-cell (v 'c))))))


(deftest two-runs-collect-identically-test
  (doseq [k (keys builders)
          ast [(spin 100) linked-sum counted-loop]]
    (let [a (run-on k ast 3)
          b (run-on k ast 3)]
      (is (= (vm/value a) (vm/value b)) (str k))
      (is (= (set (keys (:heap a))) (set (keys (:heap b)))) (str k))
      (is (= (:gc a) (:gc b)) (str k)))))


(deftest cell-heavy-programs-agree-at-every-threshold-test
  (doseq [[ast expected] [[(spin 100) :done] [linked-sum 465]
                          [counted-loop 50]]
          k (keys builders)
          threshold [1 2 1000000000]]
    (is (= expected (outcome #(run-on k ast threshold)))
        (str k " at threshold " threshold))))
