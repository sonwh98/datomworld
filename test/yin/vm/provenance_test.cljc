(ns yin.vm.provenance-test
  "The VM provenance primitive (sol-approved, amended): a private
   :provenance sibling on heap entries, written once by the privileged mint
   (cell/dsg__new-provenance, effect :cell/new-provenance) and read only
   by the authenticated private reader (cell/dsg__provenance, effect
   :cell/provenance). The VM treats the slot as opaque data and answers it
   for nothing but a live, authenticated, marked cell. Unit tests run on all
   four VMs; the macro tests exercise the expander prelude."
  (:require [yin.vm.semantic.linearize :as sr-linearize]
            [yin.vm.semantic.derive :as derive]
            [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.macro :as m]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (module/register-cell-module (module/default-registry))})


(def ^:private images
  {:ast-walker vm/ast->semantic-bytecode,
   :semantic (fn [ast]
               (:vector (sr-linearize/project-rows (vm/ast->semantic-bytecode ast)))),
   :stack (fn [ast] (:image (derive/stack-image (:vector (sr-linearize/project ast))))),
   :register (fn [ast] (derive/register-image (:vector (sr-linearize/project ast))))})


(def ^:private builders
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
  [k ast threshold]
  (vm/run ((get builders k) ast (assoc opts :gc-threshold threshold))))


(defn- on-every-vm
  ([ast] (on-every-vm ast opts))
  ([ast o]
   (into {}
         (map (fn [k]
                [k (try (run-on k ast (get o :gc-threshold 1000000000))
                        (catch #?(:clj Exception :cljs :default :cljd Object)
                               e
                          [:thrown (ex-data e)]))]))
         (keys builders))))


(defn- every-vm=
  [expected ast]
  (doseq [threshold [1 1000000000]
          [k result] (on-every-vm ast (assoc opts :gc-threshold threshold))]
    (is (= expected (if (vector? result) result (vm/value result)))
        (str k " at gc threshold " threshold))))


(defn- refusal-of
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (or (ex-data e) {}))))


(defn- lit [x] {:type :literal, :value x})
(defn- v [sym] {:type :variable, :name sym})
(defn- lam [params body] {:type :lambda, :params params, :body body})
(defn- app [op & args] {:type :application, :operator op, :operands (vec args)})
(defn- then [a b] (app (lam ['_] b) a))
(defn- let1 [sym init body] (app (lam [sym] body) init))
(defn- pair [a b] (app (v 'conj) (app (v 'conj) (lit []) a) b))
(defn- new-cell [x] (app (v 'cell/new) x))
(defn- get-cell [c] (app (v 'cell/get) c))
(defn- set-cell! [c x] (app (v 'cell/set!) c x))
(defn- new-provenance-cell [value provenance]
  (app (v 'cell/dsg__new-provenance) value provenance))
(defn- provenance-cell [candidate]
  (app (v 'cell/dsg__provenance) candidate))


(deftest mint-installs-provenance-atomically-test
  (every-vm= {:retained 1}
             (let1 'c (new-provenance-cell (lit 42) (lit {:retained 1}))
                   (provenance-cell (v 'c)))))


(deftest an-ordinary-cell-carries-no-provenance-test
  (every-vm= nil
             (let1 'c (new-cell (lit 42))
                   (provenance-cell (v 'c)))))


(deftest provenance-is-hidden-from-get-test
  (every-vm= 42
             (let1 'c (new-provenance-cell (lit 42) (lit {:retained 1}))
                   (get-cell (v 'c)))))


(deftest set!-preserves-provenance-test
  (every-vm= {:retained 1}
             (let1 'c (new-provenance-cell (lit 42) (lit {:retained 1}))
                   (then (set-cell! (v 'c) (lit 99))
                         (provenance-cell (v 'c)))))
  (every-vm= 99
             (let1 'c (new-provenance-cell (lit 42) (lit {:retained 1}))
                   (then (set-cell! (v 'c) (lit 99))
                         (get-cell (v 'c))))))


(deftest content-copy-loses-provenance-test
  (testing "copying the visible content into a fresh cell mints no provenance"
    (every-vm= [42 nil]
               (let1 'c (new-provenance-cell (lit 42) (lit {:retained 1}))
                     (let1 'copy (new-cell (get-cell (v 'c)))
                           (pair (get-cell (v 'copy))
                                 (provenance-cell (v 'copy))))))))


(deftest ref-copy-retains-rethrow-authority-test
  (testing "copying the sealed reference still names the same marked cell"
    (every-vm= {:retained 1}
               (let1 'c (new-provenance-cell (lit 42) (lit {:retained 1}))
                     (let1 'alias (v 'c)
                           (provenance-cell (v 'alias)))))))


(deftest provenance-query-is-never-fatal-test
  (testing "a forged ref answers nil instead of refusing"
    (every-vm= nil
               (let1 'c (new-provenance-cell (lit 42) (lit {:retained 1}))
                     (provenance-cell (app (v 'assoc) (v 'c) (lit :seal)
                                           (lit "x"))))))
  (testing "a dead id answers nil"
    (every-vm= nil
               (let1 'c (new-provenance-cell (lit 42) (lit {:retained 1}))
                     (provenance-cell (app (v 'assoc) (v 'c) (lit :id)
                                           (lit :cell-999))))))
  (testing "a non-reference answers nil"
    (every-vm= nil (provenance-cell (lit 5)))
    (every-vm= nil (provenance-cell (lit {:type :cell-ref})))))



(defn- quiesce
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


(defn- minted-wrapper
  [k]
  (let [result (run-on k (new-cell (lit :payload)) 1000000)
        payload-ref (vm/value result)
        q (quiesce result)
        {:keys [state value]}
        (engine/handle-effect q (module/make-effect :cell/new-provenance
                                                     {:val :visible
                                                      :provenance payload-ref})
                              {})]
    [(quiesce state) value payload-ref]))


(deftest provenance-only-payload-survives-ordinary-collection-test
  (doseq [k (keys builders)]
    (let [[state wrapper-ref payload-ref] (minted-wrapper k)
          rooted (assoc-in state [:store 'w] wrapper-ref)
          collected (engine/collect rooted)]
      (testing (str k)
        (is (contains? (:heap collected) (:id wrapper-ref)))
        (is (contains? (:heap collected) (:id payload-ref))
            "the payload survives though only provenance names it")))))


(deftest pinned-wrapper-traces-its-provenance-test
  (doseq [k (keys builders)]
    (let [[state wrapper-ref payload-ref] (minted-wrapper k)
          pinned (assoc-in state [:gc :pinned] #{(:id wrapper-ref)})
          collected (engine/collect pinned)]
      (testing (str k)
        (is (contains? (:heap collected) (:id wrapper-ref)))
        (is (contains? (:heap collected) (:id payload-ref))
            "the pinned wrapper provenance roots the payload")))))


(deftest mint-held-provenance-survives-its-own-collection-test
  (doseq [k (keys builders)]
    (let [result (run-on k (new-cell (lit :payload)) 1000000)
          payload-ref (vm/value result)
          q (quiesce result)
          q (assoc q :gc (assoc (:gc q) :since 0 :threshold 1))
          {:keys [state]}
          (engine/handle-effect q (module/make-effect :cell/new-provenance
                                                       {:val :visible
                                                        :provenance payload-ref})
                                {})]
      (testing (str k)
        (is (zero? (:since (:gc state))) "the mint allocation collected")
        (is (contains? (:heap state) (:id payload-ref))
            "the provenance was held as an extra root through the
             allocation-triggered collection")))))


(deftest dropping-the-wrapper-reclaims-the-cluster-test
  (doseq [k (keys builders)]
    (let [[state wrapper-ref payload-ref] (minted-wrapper k)
          dropped (engine/collect state)]
      (testing (str k)
        (is (not (contains? (:heap dropped) (:id wrapper-ref))))
        (is (not (contains? (:heap dropped) (:id payload-ref)))
            "the payload cell reachable only through provenance is collected
             once its wrapper is")))))


(deftest exported-helper-refuses-the-doors-test
  (let [prelude (m/prelude identity)
        variable (get prelude 'yin/variable)
        lambda (get prelude 'yin/lambda)
        make-def (get prelude 'yin/make-def)
        body (m/ast->packet (lit 1))]
    (testing "yin/variable - the reference helper"
      (doseq [door '[cell/dsg__new-provenance cell/dsg__provenance]]
        (let [d (refusal-of #(variable door))]
          (is (= true (:yin.macro/prelude d)) (str door))
          (is (= door (:name d)) (str door)))))
    (testing "yin/lambda - the binder helper"
      (let [d (refusal-of #(lambda ['cell/dsg__provenance] body))]
        (is (= true (:yin.macro/prelude d)))
        (is (= 'cell/dsg__provenance (:name d)))))
    (testing "yin/make-def - the definition-target helper"
      (let [name-tree (m/ast->packet (lit 'cell/dsg__provenance))
            d (refusal-of #(make-def name-tree body))]
        (is (= true (:yin.macro/prelude d)))
        (is (= 'cell/dsg__provenance (:name d)))))
    (testing "a quoted name is inert data, not a refusal"
      (is (nil? (refusal-of
                  #(m/invoke (m/macro-entry
                               (m/ast->packet
                                 (lam [] (app (v 'yin/literal)
                                              (lit 'cell/dsg__new-provenance))))
                               vm/ast-contract)
                             []
                             (m/make-ctx {:token "t"}))))))))


(deftest macro-cannot-construct-the-mint-door-test
  (testing "a macro body that builds the door as a variable for indirect
            application is refused at invocation, not at expansion"
    (let [macro-ast (lam []
                         (app (v 'yin/variable)
                              (lit 'cell/dsg__new-provenance)))
          entry (m/macro-entry (m/ast->packet macro-ast) vm/ast-contract)
          d (refusal-of #(m/invoke entry [] (m/make-ctx {:token "t"})))]
      (is (= :body-error (:kind d)))
      (is (re-find #"dsg__new-provenance" (str (:message d)))))))
