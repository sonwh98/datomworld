(ns yin.vm.cell-test
  "Cell slice 1 (Architect cell ruling Q6, mutable-objects ruling Q6): the
   `cell` module's `cell/new`, `cell/get`, `cell/set!` over the task
   `:heap`, reached by a sealed `:cell-ref`. Each program runs on all four
   VMs -- the AST walker, the semantic VM, and the de Bruijn stack and
   register VMs -- with the same expectation."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.completion :as completion]
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


(def ^:private images
  "Each lowers an AST to what its VM's loader and `spawn-module` load."
  {:ast-walker vm/ast->semantic-bytecode,
   :semantic (fn [ast]
               (:vector (linearize/lower-rows (vm/ast->semantic-bytecode
                                                ast)))),
   :stack (fn [ast] (:image (dl/adapt (vm/ast->datoms ast)))),
   :register (fn [ast]
               (:image (rc/adapt (second (vm/ast->datoms-with-root ast)))))})


(def ^:private builders
  "Each builder takes an AST and composition opts, and answers the loaded,
   not yet run, VM."
  {:ast-walker (fn [ast o]
                 (walker/vm-load-rows (walker/create-vm o)
                                      (vm/ast->semantic-bytecode ast)
                                      vm/ast-contract)),
   :semantic (fn [ast o]
               (semantic/load-vector (semantic/create-vm o)
                                     ((:semantic images) ast)
                                     vm/semantic-contract)),
   :stack (fn [ast o]
            (dvm/create-vm ((:stack images) ast)
                           (assoc o :contract vm/stack-contract))),
   :register (fn [ast o]
               (rvm/create-vm ((:register images) ast)
                              (assoc o :contract vm/register-contract)))})


(defn- run-on
  ([k ast] (run-on k ast opts))
  ([k ast o] (vm/run ((get builders k) ast o))))


(defn- on-every-vm
  "`[vm-key halted-vm]` for each VM; a throw becomes `[:thrown ex-data]`."
  ([ast] (on-every-vm ast opts))
  ([ast o]
   (into {}
         (map (fn [k]
                [k (try (run-on k ast o)
                        (catch #?(:clj Exception
                                  :cljs :default
                                  :cljd Object)
                               e
                          [:thrown (ex-data e)]))]))
         (keys builders))))


(def ^:private gc-thresholds
  "Heap reclamation must not change a result: every program runs
   collecting at every allocation and never collecting."
  [1 1000000000])


(defn- every-vm=
  [expected ast]
  (doseq [threshold gc-thresholds
          [k result] (on-every-vm ast (assoc opts :gc-threshold threshold))]
    (is (= expected (if (vector? result) result (vm/value result)))
        (str k " at gc threshold " threshold))))


(defn- refusal-of
  "The ex-data of what `thunk` throws, or nil when it returns."
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


(def ^:private capture {:type :vm/current-continuation})


(defn- then
  "Evaluate `first-node` for effect, then `next-node`."
  [first-node next-node]
  (app (lam ['_] next-node) first-node))


(defn- let1
  "Bind `sym` to `init` in `body`."
  [sym init body]
  (app (lam [sym] body) init))


(defn- with-k
  "Bind the capture point's value to `r` and evaluate `body`: on the first
   pass `r` is the continuation, on every re-entry the delivered value."
  [body]
  (app (lam ['r] body) capture))


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


(defn- bump
  "(cell/set! c (+ (cell/get c) 1))"
  [c]
  (set-cell! c (app (v '+) (get-cell c) (lit 1))))


;; =============================================================================
;; Construction
;; =============================================================================

(deftest every-vm-starts-with-an-empty-heap-test
  (doseq [[k build] builders]
    (is (= {} (:heap (build (lit 1) opts))) (str k))))


(deftest spawned-module-child-starts-with-an-empty-heap-test
  (let [ast (let1 'c (new-cell (lit 1)) (get-cell (v 'c)))]
    (doseq [[k result] (on-every-vm ast)]
      (testing (str k)
        (is (= 1 (count (:heap result))) "the parent allocated one cell")
        (is (= {}
               (:heap (module/spawn-module result
                                           ((get images k) ast)
                                           {:modules (:modules result),
                                            :origin :t0.0,
                                            :ancestry [],
                                            :capability-secret tu/secret})))
            "the child holds none of it")))))


;; =============================================================================
;; Sharing and allocation
;; =============================================================================

(deftest counter-shared-by-two-closures-test
  (testing "two closures over one cell see each other's writes"
    (every-vm= [2 2]
               (let1 'c (new-cell (lit 0))
                     (app (lam ['inc! 'read]
                               (then (app (v 'inc!))
                                     (then (app (v 'inc!))
                                           (pair (app (v 'read))
                                                 (get-cell (v 'c))))))
                          (lam [] (bump (v 'c)))
                          (lam [] (get-cell (v 'c))))))))


(deftest distinct-cells-per-activation-test
  (testing "two calls of one function allocate two cells"
    (every-vm= [2 1]
               (let1 'mk (lam [] (let1 'c (new-cell (lit 0))
                                       (lam [] (bump (v 'c)))))
                     (app (lam ['a 'b]
                               (then (app (v 'a))
                                     (pair (app (v 'a)) (app (v 'b)))))
                          (app (v 'mk))
                          (app (v 'mk)))))))


(deftest set-answers-the-written-value-test
  (every-vm= :x (let1 'c (new-cell (lit 0)) (set-cell! (v 'c) (lit :x)))))


;; =============================================================================
;; Box semantics under continuations
;; =============================================================================

(deftest mutation-survives-multi-shot-re-entry-test
  (testing "the continuation is kept in a second cell and re-entered three
            times; the counter cell is never rolled back"
    ;; c <- cell 0, kc <- cell nil
    ;; r <- capture
    ;; when c = 0: kc := r
    ;; if c < 3: c := c + 1, ((get kc) (* 10 c)) else [r c]
    (every-vm=
      [30 3]
      (let1 'c (new-cell (lit 0))
            (let1 'kc (new-cell (lit nil))
                  (with-k
                    (then (if-node (app (v '=) (get-cell (v 'c)) (lit 0))
                                   (set-cell! (v 'kc) (v 'r))
                                   (lit nil))
                          (if-node (app (v '<) (get-cell (v 'c)) (lit 3))
                                   (then (bump (v 'c))
                                         (app (get-cell (v 'kc))
                                              (app (v '*)
                                                   (lit 10)
                                                   (get-cell (v 'c)))))
                                   (pair (v 'r) (get-cell (v 'c)))))))))))


(deftest mutation-survives-an-abortive-escape-test
  (testing "a set! made before invoking a captured continuation is visible
            at the capture point"
    (every-vm= [:out :written]
               (let1 'c (new-cell (lit :init))
                     (let1 'res
                           (with-k (if-node (app (v '=) (v 'r) (lit :out))
                                            (v 'r)
                                            (then (set-cell! (v 'c)
                                                             (lit :written))
                                                  (app (v '+)
                                                       (lit 1000)
                                                       (app (v 'r)
                                                            (lit :out))))))
                           (pair (v 'res) (get-cell (v 'c))))))))


;; =============================================================================
;; Sealing
;; =============================================================================

(defn- forged-refusal
  "Every VM refuses the forged ref `forge` builds from two live refs `a`
   and `b` with the qualified `reason` (the forged-reference error unless
   given), naming `effect`."
  ([effect forge] (forged-refusal effect forge :forged-resource-reference))
  ([effect forge reason]
   (let [use (fn [f]
               (if (= :cell/get effect)
                 (get-cell f)
                 (set-cell! f (lit :stolen))))
         ast (let1 'a (new-cell (lit :secret-a))
                   (let1 'b (new-cell (lit :secret-b))
                         (use forge)))]
     (doseq [[k result] (on-every-vm ast)]
       (is (= [:thrown {:reason reason,
                        :effect effect,
                        :kind :cell-ref}]
              (update result 1 dissoc :id))
           (str k))))))


(deftest forged-cell-ref-refused-test
  (doseq [effect [:cell/get :cell/set!]]
    (testing (str effect " with a wrong seal on a live id")
      (forged-refusal effect (app (v 'assoc) (v 'a) (lit :seal) (lit "x"))))
    (testing (str effect " with another live cell's id under this seal")
      (forged-refusal effect (app (v 'assoc) (v 'a) (lit :id)
                                  (app (v 'get) (v 'b) (lit :id)))))
    (testing (str effect " with an unknown id: the heap holds no such cell")
      (forged-refusal effect
                      (app (v 'assoc) (v 'a) (lit :id) (lit :cell-999))
                      :dead-or-forged-reference))
    (testing (str effect " with a literal that has no seal")
      (forged-refusal effect (app (v 'assoc) (v 'a) (lit :seal) (lit nil))))
    (testing (str effect " with a stream-ref type tag")
      (forged-refusal effect (app (v 'assoc) (v 'a) (lit :type)
                                  (lit :stream-ref))))))


(deftest foreign-task-ref-refused-test
  (testing "a ref issued by a task under another secret is refused, though
            its id names a live cell here"
    (let [foreign (vm/value (run-on :ast-walker (new-cell (lit :theirs))
                                    (assoc opts
                                           :capability-secret
                                           "another task's secret")))
          ast (let1 'mine (new-cell (lit :mine)) (get-cell (lit foreign)))]
      (is (= :cell-ref (:type foreign)))
      (doseq [[k result] (on-every-vm ast)]
        (is (= :forged-resource-reference (:reason (second result)))
            (str k))))))


;; =============================================================================
;; Content is data
;; =============================================================================

(deftest cell-holding-nil-test
  (testing "get answers nil and the ref stays live"
    (every-vm= [nil :later]
               (let1 'c (new-cell (lit nil))
                     (pair (get-cell (v 'c))
                           (then (set-cell! (v 'c) (lit :later))
                                 (get-cell (v 'c))))))))


(deftest cell-holding-an-effect-shaped-map-test
  (let [shaped {:effect :vm/store-put, :key 'k, :val 99}]
    (testing "the map comes back as data and nothing is written"
      (doseq [[k result] (on-every-vm (let1 'c (new-cell (lit shaped))
                                            (get-cell (v 'c))))]
        (is (= shaped (vm/value result)) (str k))
        (is (not (contains? (vm/store result) 'k)) (str k))))))


;; =============================================================================
;; Identity (mutable-objects ruling Q6)
;; =============================================================================

(deftest ref-equality-is-cell-identity-test
  (testing "= on two refs is true iff they name the same cell"
    (every-vm= [true false]
               (let1 'a (new-cell (lit 0))
                     (let1 'b (new-cell (lit 0))
                           (let1 'alias (v 'a)
                                 (pair (app (v '=) (v 'a) (v 'alias))
                                       (app (v '=) (v 'a) (v 'b)))))))))


(deftest ref-as-map-key-and-nested-value-test
  (testing "a ref keys a host map, and a ref read back out of a nested
            value still reaches its cell"
    (every-vm= [:by-a :inner]
               (let1 'a (new-cell (lit :inner))
                     (let1 'b (new-cell (lit 0))
                           (let1 'm (app (v 'assoc)
                                         (lit {})
                                         (v 'a) (lit :by-a)
                                         (v 'b) (lit :by-b))
                                 (pair (app (v 'get) (v 'm) (v 'a))
                                       (get-cell
                                         (app (v 'first)
                                              (app (v 'get)
                                                   (lit {})
                                                   (lit :k)
                                                   (app (v 'conj)
                                                        (lit [])
                                                        (v 'a))))))))))))


(deftest self-referential-cell-test
  (testing "a cell whose content holds its own ref: get and set work and
            nothing loops"
    (every-vm= [true 1]
               (let1 'c (new-cell (lit 0))
                     (then (set-cell! (v 'c)
                                      (app (v 'conj) (lit [1]) (v 'c)))
                           (let1 'self (app (v 'get) (get-cell (v 'c)) (lit 1))
                                 (pair (app (v '=) (v 'self) (v 'c))
                                       (app (v 'first)
                                            (get-cell (v 'self))))))))))


;; =============================================================================
;; F3: every cell-bearing lift is refused (copy-on-lift is slice 2)
;; =============================================================================

(def ^:private closure-over-cell
  "Store `f`, a closure over a fresh cell, then answer 0."
  (then (app (v 'yin/def)
             (lit 'f)
             (let1 'c (new-cell (lit 5))
                   (lam [] (get-cell (v 'c)))))
        (lit 0)))


(deftest lift-of-a-closure-over-a-cell-is-refused-test
  (doseq [[k result] (on-every-vm closure-over-cell)]
    (testing (str k)
      (is (= 0 (vm/value result)))
      (let [r (refusal-of #(engine/lift-slice result :segment/own ['f]))]
        (is (= :yin.k/non-portable (:yin.k/status r)))
        (is (= :cell (:yin.k/kind r)))))))


(deftest lift-of-a-bare-cell-ref-is-refused-test
  (doseq [[k result] (on-every-vm
                       (then (app (v 'yin/def) (lit 'r) (new-cell (lit 5)))
                             (lit 0)))]
    (let [r (refusal-of #(engine/lift-slice result :segment/own ['r]))]
      (is (= :yin.k/non-portable (:yin.k/status r)) (str k))
      (is (= :cell (:yin.k/kind r)) (str k)))))


(deftest completion-over-a-cell-is-not-complete-test
  (let [machine (run-on :semantic
                        (let1 'c (new-cell (lit 5))
                              (lam [] (get-cell (v 'c)))))
        outcome (try {:result (completion/complete {:vm machine})}
                     (catch #?(:clj Exception :cljs :default :cljd Object) e
                       {:refusal (ex-data e)}))]
    (is (not= :complete
              (get-in outcome [:result :yin.k/requires :yin.k/discovery])))
    (is (= {:yin.k/status :yin.k/non-portable, :yin.k/kind :cell}
           (select-keys (:refusal outcome) [:yin.k/status :yin.k/kind])))))
