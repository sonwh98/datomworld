(ns yang.typescript.antlr.profiles
  "Addressed publication and admission of TypeScript's builtin revisions."
  (:require [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [yang.clojure.core-impl :as impl]
            [yang.clojure.core-module :as library]
            [yang.clojure.prelude :as clj]
            [yang.core.call :as call]
            [yang.core.prelude-admission :as admission]
            [yang.core.semantics :as semantics]
            [yang.rt.effect :as effect]
            [yang.rt.int :as int-rt]
            [yang.typescript.antlr.clj-runtime :as runtime]
            [yang.typescript.antlr.contract :as contract]
            [yang.typescript.antlr.prelude :as js-prelude]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.integer :as integer]
            [yin.vm.linker.publish :as publish]
            [yin.vm.module :as module]))


(def ^:private integer-limits {::integer/max-bits 100000 ::integer/max-digits 4300})
(def ^:private max-items 1048576)


(defn registry []
  (-> (module/default-registry)
      module/register-cell-module
      (data/register-data-module {::data/max-items max-items})
      (js-prelude/register-integer-module integer-limits)
      clj/admit))


(def ^:private profile-modules
  {contract/legacy-id '[yang.call js]
   contract/clojure-id '[yang.call yrt.effect yrt.int clj clojure.core.impl clojure.core clj.ts]})


(def ^:private module-pins
  '{yang.call :segment/blake3-ef21320944b651140c11dedebd8b443fb91d32f99678f9c274568fbe4d07eea4
    yrt.effect :segment/blake3-be28b1773d248e288a5a4347415ed0a15b485725931ad9102eaabcf589254a19
    yrt.int :segment/blake3-8fd992e384e0e8b897cc9fef6f780fa4940d82b5f771aad60e5042c84c507728
    clj :segment/blake3-78903e4442bfc6ae5e1ad430e11d6f43d416b6836369c8ba6db056898ccf9df7
    clojure.core.impl :segment/blake3-b985b7a158746b0f487825c890741d14b6ae80dd81bf87ceee16aa85b71254a4
    clojure.core :segment/blake3-cd4f4616373b7039d0c8f55f82cf2d1eb6098378c115e7b1c76ef01cf91fc8ce
    clj.ts :segment/blake3-34fb98a50c1f2df972892b144b057f6cb5ae43e62b4826474923946b9e077ee0
    js :segment/blake3-79a8adf19f8de8d183701a4744a55ab92aaf2700cc3469237e32dada9bbd169b})


(def ^:private revision-locks
  {:yang.typescript/ts-v1
   {:descriptor-address :segment/blake3-c1fd7379bc04a2c6f5c0f531fcaf6c416ee6bbc2ca598d786b2df6f30c1a3c3d
    :runtime-address :segment/blake3-4cfc0654781abad21217c8ddfd6b85bb012f8ad70434635bebe1721c4a0b5298}
   :yang.typescript/clojure-core-v1
   {:descriptor-address :segment/blake3-62302e924c8b29c6f2cb0be89152072f91d2b43c6abca29a82cd0ab702b7a096
    :runtime-address :segment/blake3-c27bd3dbde6cfa683c610e2437541ae1ff1e0fd5328ec33649d80030db199c45}})


(defn- trusted-registry []
  (assoc (registry) :trusted-prelude-modules (select-keys module-pins '[js clj])))


(defn- module-spec [name registry pins]
  (case name
    yang.call (call/module-spec registry)
    yrt.effect (effect/module-spec registry)
    yrt.int (int-rt/module-spec registry (pins 'yrt.effect))
    clj (clj/module-spec registry (pins 'yang.call) (pins 'yrt.effect))
    clojure.core.impl (impl/module-spec registry (pins 'yang.call) (pins 'clj) (pins 'yrt.int))
    clojure.core (library/module-spec registry (pins 'clj) (pins 'clojure.core.impl))
    clj.ts (runtime/module-spec registry pins)
    js (js-prelude/module-spec registry (pins 'yang.call))))


(defn- verify-address! [expected actual]
  (when-not (= expected actual)
    (throw (ex-info "TypeScript profile artifact differs from its revision pin"
                    {:status :refused :reason :yang.semantics/invalid-descriptor
                     :defect :address-mismatch :address expected :actual actual}))))


(defn- runtime-artifacts! [store registry modules]
  (reduce (fn [{:keys [pins] :as state} name]
            (let [spec (module-spec name registry pins)
                  {:keys [manifest address] :as result} (publish/publish-module! store spec)
                  tree (:yin.module/tree manifest)]
              (when-not manifest
                (throw (ex-info "TypeScript runtime publication refused" result)))
              (verify-address! (module-pins name) address)
              (-> state
                  (assoc-in [:pins name] address)
                  (update :artifacts assoc address manifest tree (vm/ast->semantic-bytecode (:ast spec))))))
          {:pins {} :artifacts {}} modules))


(defn- runtime-profile [id module address manifest]
  {:yang.prelude/schema 1
   :yang.prelude/id id
   :yang.prelude/revision (name id)
   :yang.prelude/module module
   :yang.prelude/root-address address
   :yang.prelude/components (mapv vec (sort-by key jing/canonical-compare (:yin.module/requires manifest)))
   :yang.prelude/host-modules
   (cond-> {:cell {:required true}
            :data {:required true :min-max-items 1048576}
            :integer {:required true :min-bits 53}}
     (= id contract/clojure-id) (assoc :float {:required true} :kind {:required true}))
   :yang.prelude/max-effects nil})


(defn admit
  "Reconcile the runtime and descriptor pins before validating the
   descriptor's syntax, closure, executable roles and host requirements."
  [descriptor runtime frontend-contract artifacts registry]
  (let [{:yang.prelude/keys [module root-address components]} runtime
        pins (into {} (map (juxt :module :address)) (:yang.semantics/ambient-modules descriptor))]
    (when-not (and (= module (:yang.semantics/prelude-module descriptor))
                   (= root-address (get pins module))
                   (every? (fn [[dependency address]] (= address (get pins dependency))) components))
      (throw (ex-info "TypeScript runtime and descriptor pins disagree"
                      {:status :refused :reason :yang.semantics/invalid-descriptor
                       :defect :runtime-profile-pins})))
    (semantics/validate-descriptor descriptor frontend-contract artifacts registry)
    (admission/admit-profile runtime artifacts registry
                             {:data {:max-items max-items}
                              :integer {:bits (::integer/max-bits integer-limits)
                                        :digits (::integer/max-digits integer-limits)}})
    descriptor))


(defn builtin [id]
  (let [closure (mapv (fn [module] [module (module-pins module)]) (profile-modules id))]
    (assoc (revision-locks id) :descriptor (contract/descriptor id closure) :closure closure)))


(defn- admitted-profile [store id pins artifacts registry frontend-contract]
  (let [modules (profile-modules id)
        closure (mapv (fn [module] [module (pins module)]) modules)
        descriptor (contract/descriptor id closure)
        module (peek modules)
        address (pins module)
        runtime (runtime-profile id module address (artifacts address))
        descriptor-address (jing/segment-key descriptor)
        runtime-address (jing/segment-key runtime)
        lock (revision-locks id)]
    (verify-address! (:descriptor-address lock) descriptor-address)
    (verify-address! (:runtime-address lock) runtime-address)
    (admit descriptor runtime frontend-contract artifacts registry)
    (jing/materialize! store descriptor)
    (jing/materialize! store runtime)
    {:descriptor descriptor :descriptor-address descriptor-address
     :runtime runtime :runtime-address runtime-address :closure closure}))


(defn prepare!
  "Publish and verify builtin profiles in the composition's content store."
  ([store] (prepare! store [contract/legacy-id contract/clojure-id]))
  ([store ids]
   (let [registry (trusted-registry)
         modules (vec (distinct (mapcat profile-modules ids)))
         {:keys [pins artifacts]} (runtime-artifacts! store registry modules)
         registry (assoc registry :trusted-prelude-modules (select-keys pins '[js clj]))
         frontend-contract (contract/frontend-contract pins pins)]
     {:profiles (into {} (map (fn [id]
                                [id (admitted-profile store id pins artifacts registry frontend-contract)]))
                      ids)
      :pins pins :artifacts artifacts :contract frontend-contract :registry registry})))


(def ^:private checked-profiles
  {contract/legacy-id (delay (prepare! (mem/create-content-mem) [contract/legacy-id]))
   contract/clojure-id (delay (prepare! (mem/create-content-mem) [contract/clojure-id]))})


(defn verify-builtin! [id]
  (get-in @(get checked-profiles id) [:profiles id]))


(defn verify-descriptor!
  "Check an explicit selection against the builtin revision's admitted
   runtime and address-bound roles before the compiler inspects a packet."
  [descriptor]
  (let [id (:yang.semantics/id descriptor)]
    (if-let [checked (get checked-profiles id)]
      (let [{:keys [profiles contract artifacts registry]} @checked
            {:keys [runtime] :as profile} (profiles id)]
        (if (= descriptor (:descriptor profile))
          descriptor
          (admit descriptor runtime contract artifacts registry)))
      (throw (ex-info "Unknown TypeScript semantics revision"
                      {:status :refused :reason :yang.semantics/invalid-descriptor
                       :defect :entry-contract :semantics-id id})))))


(defn derivation [manifest {:keys [descriptor-address runtime-address closure]} packet ast]
  (let [resolver (:yang.frontend/ambient-resolver (meta ast))]
    (cond-> {:yang.frontend/id (:yang.frontend/id manifest)
             :yang.frontend/revision (:yang.frontend/revision manifest)
             :yang.frontend/lowering-profile descriptor-address
             :yang.frontend/runtime-profile runtime-address
             :yang.frontend/runtime-closure closure
             :yang.frontend/input (jing/segment-key packet)
             :yang.frontend/output (:root (vm/ast->semantic-bytecode ast))}
      resolver (assoc :yang.frontend/ambient-resolver resolver))))
