(ns yin.vm.linker.publish-test
  (:require [clojure.test :refer [deftest is]]
            [dao.jing.mem :as mem]
            [dao.jing :as jing]
            [dao.space.query :as query]
            [yin.vm :as vm]
            [yin.vm.completion :as completion]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.repl.require-test :as require-test]
            [yin.vm.linker.publish :as publish]))


(defn- lit
  [x]
  {:type :literal :value x})


(defn- var-node
  [x]
  {:type :variable :name x})


(defn- app
  [f & xs]
  {:type :application :operator f :operands (vec xs)})


(def module-ast (app (var-node 'yin/def) (lit 'f) (lit 42)))


(deftest undefined-export-writes-nothing
  (let [store (mem/create-content-mem)
        result (publish/publish-module!
                 store {:name 'example :ast module-ast
                        :exports #{'missing} :requires {} :primitives {}})]
    (is (= :yin.link.publish/undefined-export (:reason result)))
    (is (nil? (jing/get store (:root (vm/ast->semantic-bytecode module-ast)) nil)))
    (let [nested {:type :lambda :params [] :body module-ast}
          root (:root (vm/ast->semantic-bytecode nested))
          res (publish/publish-module!
                store {:name 'example :ast nested :exports #{'f}
                       :requires {} :primitives {}})]
      (is (= :yin.link.publish/undefined-export (:reason res)))
      (is (nil? (jing/get store root nil))))))


(deftest malformed-definition-refuses-as-data
  (let [store (mem/create-content-mem)
        malformed (app (assoc (var-node 'yin/def) :source :extra)
                       (lit 'f) (lit 42) (lit 43))
        result (publish/publish-module!
                 store {:name 'bad :ast malformed :exports #{}
                        :requires {} :primitives {}})]
    (is (= :refused (:status result)))
    (is (= :yin.link.publish/malformed-tree (:reason result)))))


(deftest invalid-required-manifest-refuses-before-writing
  (let [store (mem/create-content-mem)
        address (jing/materialize! store {:not :a-manifest})
        root (:root (vm/ast->semantic-bytecode module-ast))
        result (publish/publish-module!
                 store {:name 'bad :ast module-ast :exports #{'f}
                        :requires {'dep address} :primitives {}})]
    (is (= {:status :refused :reason :yin.link.publish/invalid-requirement
            :name 'dep}
           result))
    (is (nil? (jing/get store root nil)))))


(deftest publishes-four-formats
  (let [store (mem/create-content-mem)
        spec {:name 'example :ast module-ast :exports #{'f}
              :requires {} :primitives {}}
        result (publish/publish-module! store spec)]
    (is (jing/segment-address? (:address result)))
    (is (= #{:yin.ast/code :yin.semantic/code
             :yin.debruijn.code :yin.debruijn.register}
           (set (keys (:links result)))))
    (is (every? #(= :ok (:status %)) (vals (:links result))))
    (is (= (:address result)
           (:address (publish/publish-module! store spec))))))


(deftest footprint-derives-store-effects-and-dependencies
  (let [store (mem/create-content-mem)
        dependency (jing/materialize!
                     store {:yin.module/footprint
                            {:store-keys #{'foreign} :effects #{:remote}}})
        ast (app {:type :lambda :params '[_]
                  :body {:type :vm/store-get :key 'k}}
                 {:type :vm/store-put :key 'k :val (lit 1)})
        own (vm/ast-requirements
              (query/relation
                (vals (:rows (vm/ast->semantic-bytecode ast)))))
        fp (publish/footprint
             store {:ast ast :primitives
                    {'p {:yin.k/profile :profile/p
                         :yin.k/effects #{:primitive}}}
                    :requires {'foreign dependency}})]
    (is (= (:store-keys own) (:store-keys fp)))
    (is (= #{'k} (:store-keys fp)))
    (is (= #{:primitive :remote} (:effects fp)))
    (is (= #{:stream/make}
           (:effects (publish/footprint
                       store {:ast {:type :stream/make :buffer 4}
                              :primitives {} :requires {}}))))))


(deftest footprint-refuses-unrepresentable-requirements-before-writing
  (let [store (mem/create-content-mem)
        cases [[module-ast {'p nil} {}
                :yin.link.publish/unprofiled-primitive]
               [module-ast {} {'dep (jing/segment-key {:missing true})}
                :yin.link.publish/missing-requirement]
               [{:type :dao.stream.apply/call :op :op/echo
                 :operands [(lit 1)]} {} {}
                :yin.link.publish/ffi-op]
               [{:type :vm/resume :parked-id :p1 :val (lit 1)} {} {}
                :yin.link.publish/parked-id]]]
    (doseq [[ast primitives requires reason] cases]
      (let [root (:root (vm/ast->semantic-bytecode ast))
            res (publish/publish-module!
                  store {:name 'example :ast ast :exports #{}
                         :primitives primitives :requires requires})]
        (is (= reason (:reason res)))
        (is (nil? (jing/get store root nil)))))))


(deftest link-results-describe-closed-and-store-corpora
  (let [profile {'+ (vm/profile-of vm/primitives '+)}
        closed (publish/publish-module!
                 (mem/create-content-mem)
                 {:name 'closed :ast module-ast :exports #{'f}
                  :requires {} :primitives {}})
        stored (publish/publish-module!
                 (mem/create-content-mem)
                 {:name 'stored :ast require-test/store-module
                  :exports require-test/store-exports
                  :requires {} :primitives profile})]
    (is (every? #(= :ok (:status %)) (vals (:links closed))))
    (is (= :ok (get-in stored [:links :yin.debruijn.code :status])))
    (is (= :ok (get-in stored [:links :yin.debruijn.register :status])))
    (is (= {:status :refused :reason :undeclared-free :name 'n}
           (get-in stored [:links :yin.ast/code])))
    (is (= :ok (get-in stored [:links :yin.semantic/code :status])))))


(deftest published-footprint-completes-module-discovery
  (let [store (mem/create-content-mem)
        {:keys [address manifest]}
        (publish/publish-module!
          store {:name 'stream :ast module-ast :exports #{'f}
                 :requires {} :primitives {}})
        modules (module/register-stream-module (module/default-registry))
        machine (semantic/load-vector
                  (semantic/create-vm {:modules modules})
                  (:vector (linearize/lower-rows
                             (vm/ast->semantic-bytecode
                               (var-node 'stream/make))))
                  vm/semantic-contract)
        result (completion/complete
                 {:vm machine
                  :modules {'stream
                            {:yin.k/manifest address
                             :yin.k/effects
                             (get-in manifest
                                     [:yin.module/footprint :effects])}}})]
    (is (empty? (get-in result [:yin.k/missing :footprints])))))
