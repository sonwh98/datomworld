(ns yin.vm.stream-poll-test
  "`stream/poll!` (safepoint slice 1): `next!` with `blocked` as a value. On
   an empty stream it answers `:dao.stream/blocked` and the VM never parks;
   `ok` advances the cursor; a callee whose profile lacks `:stream/poll` is
   refused. Each program runs on all four VMs -- the AST walker, the
   semantic VM, and the de Bruijn stack and register VMs."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as stream]
    [yin.vm :as vm]
    [yin.vm.debruijn-linearize :as dl]
    [yin.vm.debruijn-register-compile :as rc]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.engine :as engine]
    [yin.vm.linearize :as linearize]
    [yin.vm.module :as module]
    [yin.vm.semantic :as semantic]
    [yin.vm.test-utils :as tu]))


(def ^:private registry
  (module/register-host-module
    (module/register-stream-module (module/default-registry))
    'my.lib
    {'sneak (fn [c] (module/poll! c))}
    {'sneak (vm/primitive-profile 'sneak :effectful [1] #{:stream/next} :none)}))


(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules registry})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private runners
  {:ast-walker (fn [ast prep] (vm/eval (prep (tu/create-vm opts)) ast)),
   :semantic (fn [ast prep]
               (vm/run (load-semantic-ast (prep (semantic/create-vm opts))
                                          (vm/ast->datoms ast)))),
   :stack (fn [ast prep]
            (vm/run (prep (dvm/create-vm
                            (:image (dl/adapt (vm/ast->datoms ast)))
                            (assoc opts :contract vm/stack-contract))))),
   :register (fn [ast prep]
               (vm/run (prep (rvm/create-vm
                               (:image (rc/adapt (vm/ast->datoms ast)))
                               (assoc opts :contract vm/register-contract)))))})


(defn- holding
  "A `prep` binding `s` in the store to a stream holding `values`."
  [values]
  (fn [vm]
    (let [handle (tu/new-stream 8)]
      (doseq [x values] (stream/append! handle x))
      (let [[ref vm] (engine/attach-resource vm handle)]
        (assoc-in vm [:store 's] ref)))))


(defn- on-every-vm
  "`{vm-key [halted? blocked? value]}`; a throw becomes `[:thrown ex-data]`."
  [ast prep]
  (into {}
        (map (fn [[k run]]
               [k (try (let [done (run ast prep)]
                         [(vm/halted? done) (vm/blocked? done) (vm/value done)])
                       (catch #?(:cljd Object :clj Exception :cljs :default) e
                         [:thrown (ex-data e)]))]))
        runners))


(defn- v
  [s]
  {:type :variable, :name s})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(def ^:private cursor (app (v 'stream/cursor) (v 's)))


(defn- poll
  [c]
  (app (v 'stream/poll!) c))


(deftest empty-stream-answers-blocked-test
  (testing "the VM halts with :dao.stream/blocked as the value; nothing parks"
    (doseq [[k result] (on-every-vm (poll cursor) (holding []))]
      (is (= [true false :dao.stream/blocked] result) (str k)))))


(deftest ok-advances-the-cursor-test
  (testing "two values, then blocked: each ok advanced the cursor"
    (let [c (v 'c)
          ast (app (lam '[c]
                        (app (v 'conj)
                             (app (v 'conj)
                                  (app (v 'conj) {:type :literal, :value []} (poll c))
                                  (poll c))
                             (poll c)))
                   cursor)]
      (doseq [[k result] (on-every-vm ast (holding [1 2]))]
        (is (= [true false [1 2 :dao.stream/blocked]] result) (str k))))))


(deftest undeclared-poll-is-refused-test
  (testing "a callee declaring only :stream/next cannot poll"
    (doseq [[k result] (on-every-vm (app (v 'my.lib/sneak) cursor) (holding [1]))]
      (is (= [:thrown {:yin.k/status :yin.k/undeclared-effect,
                       :yin.k/effect :stream/poll,
                       :yin.k/effects #{:stream/next}}]
             result)
          (str k)))))
