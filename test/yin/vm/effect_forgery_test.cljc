(ns yin.vm.effect-forgery-test
  "D4 (finding F1): an effect is a host type minted only by
   `module/make-effect`. A map a `:pure` primitive returns is data, whatever
   keys it carries, and a profiled callee's effect is checked against its
   declared effect set. Each program runs on all four VMs -- the AST walker,
   the semantic VM, and the de Bruijn stack and register VMs."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.effect :as effect]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; The four VMs
;; =============================================================================

(def ^:private base-opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private runners
  "Each runner takes `opts`, an AST, and `prep`, applied to the VM once it
   is built and before it runs, and answers the halted VM."
  {:ast-walker (fn [opts ast prep] (vm/eval (prep (tu/create-vm opts)) ast)),
   :semantic (fn [opts ast prep]
               (vm/run (load-semantic-ast (prep (semantic/create-vm opts))
                                          (vm/ast->datoms ast)))),
   :stack (fn [opts ast prep]
            (vm/run (prep (dvm/create-vm
                            (:image (dl/adapt (vm/ast->datoms ast)))
                            (assoc opts :contract vm/stack-contract))))),
   :register (fn [opts ast prep]
               (vm/run (prep (rvm/create-vm
                               (:image (rc/adapt (second
                                                   (vm/ast->datoms-with-root
                                                     ast))))
                               (assoc opts
                                      :contract vm/register-contract)))))})


(defn- on-every-vm
  "`[vm-key halted-vm]` for each VM; a throw becomes `[:thrown ex-data]`."
  ([ast] (on-every-vm {} ast))
  ([opts ast] (on-every-vm opts ast identity))
  ([opts ast prep]
   (into {}
         (map (fn [[k run]]
                [k (try (run (merge base-opts opts) ast prep)
                        (catch #?(:clj Exception
                                  :cljs :default
                                  :cljd Object)
                               e
                          [:thrown (ex-data e)]))]))
         runners)))


;; =============================================================================
;; AST helpers
;; =============================================================================

(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [sym]
  {:type :variable, :name sym})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


;; =============================================================================
;; The host type
;; =============================================================================

(deftest effects-are-a-host-type-test
  (testing "A map is never an effect, whatever keys it carries"
    (is (not (module/effect? {:effect :vm/store-put, :key 'k, :val 1})))
    (is (not (module/effect? {:effect :stream/make}))))
  (testing "make-effect mints the type; keyword lookup reads the descriptor"
    (let [e (module/make-effect :stream/put {:stream :s, :val 1})]
      (is (module/effect? e))
      (is (not (map? e)))
      (is (= :stream/put (:effect e)))
      (is (= 1 (:val e)))
      (is (= {:effect :stream/put, :stream :s, :val 1}
             (effect/descriptor e)))))
  (testing "The constructor is no guest primitive and no stream-module export"
    (is (not-any? #(identical? module/make-effect (vm/primitive-function %))
                  (vals vm/primitives)))
    (is (not-any? #(identical? module/make-effect %)
                  (vals module/stream-module)))))


;; =============================================================================
;; F1: pure primitives cannot forge engine effects
;; =============================================================================

(deftest assoc-built-store-put-is-data-test
  (testing "(assoc {:key 'k :val 99} :effect :vm/store-put) returns the map
            and writes nothing to the store"
    (doseq [[k result] (on-every-vm (app (v 'assoc)
                                         (lit {:key 'k, :val 99})
                                         (lit :effect)
                                         (lit :vm/store-put)))]
      (is (= {:key 'k, :val 99, :effect :vm/store-put} (vm/value result))
          (str k))
      (is (not (contains? (vm/store result) 'k)) (str k)))))


(deftest get-returned-store-put-is-data-test
  (testing "(get {:d {:effect :vm/store-put :key 'k2 :val 7}} :d) returns the
            inner map and writes nothing to the store"
    (doseq [[k result] (on-every-vm
                         (app (v 'get)
                              (lit {:d {:effect :vm/store-put,
                                        :key 'k2,
                                        :val 7}})
                              (lit :d)))]
      (is (= {:effect :vm/store-put, :key 'k2, :val 7} (vm/value result))
          (str k))
      (is (not (contains? (vm/store result) 'k2)) (str k)))))


(deftest literal-stream-make-map-is-data-test
  (testing "A map carrying :effect :stream/make, built by assoc or read out
            of a literal, is returned as data and makes no stream"
    (doseq [[k result] (on-every-vm (app (v 'assoc)
                                         (lit {:capacity 4})
                                         (lit :effect)
                                         (lit :stream/make)))]
      (is (= {:capacity 4, :effect :stream/make} (vm/value result)) (str k)))
    (doseq [[k result] (on-every-vm (app (v 'first)
                                         (lit [{:effect :stream/make,
                                                :capacity 4}])))]
      (is (= {:effect :stream/make, :capacity 4} (vm/value result))
          (str k)))))


;; =============================================================================
;; The layered check: an effect against its callee's declared profile
;; =============================================================================

(defn- store-put
  [key val]
  (module/make-effect :vm/store-put {:key key, :val val}))


(def ^:private host-registry
  (module/register-host-module
    (module/default-registry)
    'my.lib
    {'sneak (fn [] (store-put 'k3 1)), 'declared (fn [] (store-put 'k4 2))}
    {'sneak (vm/primitive-profile 'sneak :pure [0] #{} :none),
     'declared (vm/primitive-profile 'declared :effectful [0]
                                     #{:vm/store-put} :none)}))


(deftest host-module-effect-outside-its-profile-is-refused-test
  (testing "A host export profiled :pure that returns an effect is refused
            with a qualified error, and writes nothing"
    (doseq [[k result] (on-every-vm {:modules host-registry}
                                    (app (v 'my.lib/sneak)))]
      (is (= [:thrown
              {:yin.k/status :yin.k/undeclared-effect,
               :yin.k/effect :vm/store-put,
               :yin.k/effects #{}}]
             result)
          (str k))))
  (testing "An export declaring the effect passes"
    (doseq [[k result] (on-every-vm {:modules host-registry}
                                    (app (v 'my.lib/declared)))]
      (is (= 2 (vm/value result)) (str k))
      (is (= 2 (get (vm/store result) 'k4)) (str k)))))


(deftest profiled-primitive-effect-outside-its-profile-is-refused-test
  (let [entry (fn [name class effects f]
                (assoc (vm/primitive-profile name class [0] effects :none)
                       :yin.k/function f))
        prims (assoc vm/primitives
                     'sneak (entry 'sneak :pure #{} #(store-put 'k5 1))
                     'declared (entry 'declared :effectful #{:vm/store-put}
                                      #(store-put 'k6 2)))]
    (testing "A primitive profiled :pure that returns an effect is refused"
      (doseq [[k result] (on-every-vm {:primitives prims}
                                      (app (v 'sneak)))]
        (is (= :yin.k/undeclared-effect
               (:yin.k/status (second result)))
            (str k))))
    (testing "A primitive declaring the effect passes"
      (doseq [[k result] (on-every-vm {:primitives prims}
                                      (app (v 'declared)))]
        (is (= 2 (get (vm/store result) 'k6)) (str k))))))


(defn- profiled-entry
  "A primitive registry entry for `f` under a UCF 7.5.2 profile."
  [name class effects f]
  (assoc (vm/primitive-profile name class [0 1] effects :none)
         :yin.k/function f))


(deftest primitive-and-module-declarations-are-united-test
  (let [both (fn [kind] (module/make-effect kind {:key 'kb, :val 5}))
        prims (assoc vm/primitives
                     'both (profiled-entry 'both :effectful #{:vm/store-put}
                                           both))
        registry (-> (module/register-host-module
                       (module/default-registry)
                       'my.lib
                       {'both both}
                       {'both (vm/primitive-profile 'both :effectful [1]
                                                    #{:test/ping} :none)})
                     (module/register-effect-handler
                       :test/ping
                       (fn [s _e _o] {:state s, :value :pong, :blocked? false})))
        opts {:primitives prims, :modules registry}]
    (testing "The kind the primitive declares passes"
      (doseq [[k result] (on-every-vm opts (app (v 'both)
                                                (lit :vm/store-put)))]
        (is (= 5 (get (vm/store result) 'kb)) (str k))))
    (testing "The kind the module export declares passes"
      (doseq [[k result] (on-every-vm opts (app (v 'my.lib/both)
                                                (lit :test/ping)))]
        (is (= :pong (vm/value result)) (str k))))
    (testing "A kind neither declares is refused, naming the union"
      (doseq [[k result] (on-every-vm opts (app (v 'both) (lit :test/nope)))]
        (is (= [:thrown
                {:yin.k/status :yin.k/undeclared-effect,
                 :yin.k/effect :test/nope,
                 :yin.k/effects #{:vm/store-put :test/ping}}]
               result)
            (str k))))))


(deftest primitive-added-to-a-live-vm-is-checked-test
  (let [add-late (fn [vm]
                   (update vm
                           :primitives
                           assoc
                           'late (profiled-entry 'late :pure #{}
                                                 #(store-put 'k7 1))
                           'late-ok (profiled-entry 'late-ok :effectful
                                                    #{:vm/store-put}
                                                    #(store-put 'k8 2))))]
    (testing "A profiled primitive assoc'd onto a built VM that returns an
              undeclared effect is refused, and writes nothing"
      (doseq [[k result] (on-every-vm {} (app (v 'late)) add-late)]
        (is (= [:thrown
                {:yin.k/status :yin.k/undeclared-effect,
                 :yin.k/effect :vm/store-put,
                 :yin.k/effects #{}}]
               result)
            (str k))))
    (testing "One declaring the effect runs"
      (doseq [[k result] (on-every-vm {} (app (v 'late-ok)) add-late)]
        (is (= 2 (get (vm/store result) 'k8)) (str k))))))
