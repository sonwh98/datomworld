(ns yang.python.antlr.profiles
  "Publication and admission of the two immutable Python frontend profiles."
  (:require [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [yang.clojure.core-impl :as impl]
            [yang.clojure.core-module :as library]
            [yang.clojure.prelude :as clj]
            [yang.core.call :as call]
            [yang.core.prelude-admission :as admission]
            [yang.core.semantics :as semantics]
            [yang.python.antlr.clj-runtime :as runtime]
            [yang.python.antlr.contract :as contract]
            [yang.python.antlr.prelude :as py]
            [yang.rt.effect :as effect]
            [yang.rt.int :as int-rt]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.integer :as integer]
            [yin.vm.linker.publish :as publish]
            [yin.vm.module :as module]))


(def ^:private integer-limits {::integer/max-bits 100000 ::integer/max-digits 4300})
(def ^:private max-items 1048576)


(defn registry
  ([] (registry integer-limits))
  ([limits]
   (-> (module/default-registry)
       module/register-cell-module
       (data/register-data-module {::data/max-items max-items})
       (py/register-integer-module limits)
       clj/admit)))


(def ^:private profile-modules
  {contract/legacy-id '[yang.call py]
   contract/clojure-id '[yang.call yrt.effect yrt.int clj clojure.core.impl clojure.core clj.py]})


;; Immutable publication identities for these revisions. The manifest
;; footprint, exports, profiles and tree are re-derived from the emitters.
(def ^:private module-locks
  '{clj {:address :segment/blake3-78903e4442bfc6ae5e1ad430e11d6f43d416b6836369c8ba6db056898ccf9df7
     :derivations
     {:yin.semantic/code :segment/blake3-fe3f1b7ebe2e87aa38a80c73c7fe3fe1662b856bdeb5256cec28332c323adaf4
      :yin.debruijn.code :segment/blake3-be05caa6d07ba39d978332fc910607f65dce9b347d3fc847eaac4110d5f29d62
      :yin.debruijn.register :segment/blake3-f30324edb3d36155a3599e024a19145e273964fd10705ffbd19be0ee531dfb47}
     :index
     {:yin.debruijn.code :segment/blake3-051c6f08634953f8fcaddc0acdd6a28b0d605499038fe0509df9fa9e1adc3e44
      :yin.debruijn.register :segment/blake3-6749c17157d17301aef6d64efe2b0db618b8e3935ec09800f97e6fea5bf84968}}
    clj.py {:address :segment/blake3-f426fff9675b2a3579d70be03919963c74f417da9c187ae283d3033005e106f6
     :derivations
     {:yin.semantic/code :segment/blake3-d21d717029d0a07ab7301ba4b2fcb5f56cdac6cba9389513780ed38d8fefeac2
      :yin.debruijn.code :segment/blake3-fc661d3df21dffb07b271f3f8940adbab09c0a9bde481f300ad41cba103a7f60
      :yin.debruijn.register :segment/blake3-2329070f80ad57afe63d068e11f47b4836b7a0ec07c4b46132aaf230f5d7891d}
     :index
     {:yin.debruijn.code :segment/blake3-dff0f87e384c90a0e23e72269022f43a13b5f3625135ebbebaa7005b57179d37
      :yin.debruijn.register :segment/blake3-4344876574cd3f84f498c4a2fe746115ffb126fd913b40370a03e329e69091e1}}
    clojure.core {:address :segment/blake3-cd4f4616373b7039d0c8f55f82cf2d1eb6098378c115e7b1c76ef01cf91fc8ce
     :derivations
     {:yin.semantic/code :segment/blake3-406cb6eb600d40911362b0a988e82c1e9c553d08634c1d60e83a3be461fb8df4
      :yin.debruijn.code :segment/blake3-7a542b67250bf352181e69b61c543bd327f71d862f604715c23b759474854597
      :yin.debruijn.register :segment/blake3-00725a379b591bd77e3994e95def13eeea1374fad59837417e20a3cabb484613}
     :index
     {:yin.debruijn.code :segment/blake3-c79c804621ec2837e137b223c773831ce5a80531413cb4f358175c3c4cd1d762
      :yin.debruijn.register :segment/blake3-5ce31dfbd4e900a3d9d265fcbca5f6308e9fa6a9e6815a38a5a14d51f58696a5}}
    clojure.core.impl {:address :segment/blake3-b985b7a158746b0f487825c890741d14b6ae80dd81bf87ceee16aa85b71254a4
     :derivations
     {:yin.semantic/code :segment/blake3-0933685b59c48501f5f384b8c14ec31014094fb221ddb45f55c633b005258919
      :yin.debruijn.code :segment/blake3-dbd21ac5c3df191ddb962767e4371e81c417dcc0bbbfdf97a25e39440ad05ce9
      :yin.debruijn.register :segment/blake3-df988f642c5d56f43698eca792a09993fff3d683d29342849d3f6949713978df}
     :index
     {:yin.debruijn.code :segment/blake3-7ac80cac0e6580202d2d2e4315fdde76d7199c08caeadb01d458daf037751175
      :yin.debruijn.register :segment/blake3-2de114e4481e3f440a534251ce6b006500690645d2a724c0732cca466ecffb6a}}
    ;; py/assert thunk series + :py/missing sentinel (ruling 1795660000000):
    ;; re-minted from JVM publication after verified ANTLR generation, in two
    ;; independent JVMs.
    py {:address :segment/blake3-8fc311fa78b335654c72608dd0d5dab3ff204eb1173eba8773335064b9635136
     :derivations
     {:yin.semantic/code :segment/blake3-08550d48a7aea251d5164f0bb62ce67d7da17e65aaa4c34aff7d9e6f1375403c
      :yin.debruijn.code :segment/blake3-e74c15948d205273d87e401d8b1c6d078a5cc85cdf719137b205f0780d2f366b
      :yin.debruijn.register :segment/blake3-6ceec8d9e0add173e3261b2902bfae7c39b41bf77c1fd4d5d06211a46c13d130}
     :index
     {:yin.debruijn.code :segment/blake3-81610a024d4f4dc05f6205205f8c93ba828c826755c6411465b10234e143d428
      :yin.debruijn.register :segment/blake3-a5845f215f47c1184b7e350f6376b98f649de7be0054f7ebf45352f087eb528e}}
    yang.call {:address :segment/blake3-ef21320944b651140c11dedebd8b443fb91d32f99678f9c274568fbe4d07eea4
     :derivations
     {:yin.semantic/code :segment/blake3-59a19ffd0e95d51e34664cf1c7f9b9c24ad606f9881ab38cfdf0d1614c8a6b09
      :yin.debruijn.code :segment/blake3-f95a3859df0ba5af3fb4b25d0e5e9f60b27138224ecc9e141dc2f6a925e510e9
      :yin.debruijn.register :segment/blake3-ee76d4dfc305b69d1a044d968fb7765fe255ed7a4d03accb7d400b342560b3cd}
     :index
     {:yin.debruijn.code :segment/blake3-72be3822b4b5027e10ffb7115b825edcd6b1d43f19ae0719854764a224ad536a
      :yin.debruijn.register :segment/blake3-8277d3ae9cb0b7195c06a78f68c7b6ddc2b5d9f562e0730e3998debc1c88f8cb}}
    yrt.effect {:address :segment/blake3-be28b1773d248e288a5a4347415ed0a15b485725931ad9102eaabcf589254a19
     :derivations
     {:yin.semantic/code :segment/blake3-3f5c2ad5c820833d82689109a4775f76ddc7b1e8caba668179c9d53580562359
      :yin.debruijn.code :segment/blake3-51ce02d31a4c005eb43cf1f3286594474c89e9cadc5fcd2bea45a7ccf95f9f43
      :yin.debruijn.register :segment/blake3-c16cfe8b2fadef88beb6f48763869dd706157c197d71564c243ee26fe051c1a8}
     :index
     {:yin.debruijn.code :segment/blake3-6f237f146ac0d1f768a192b6b6b8c0d863d52a879c4ecf9e8047cbf47404d6e9
      :yin.debruijn.register :segment/blake3-4dbd2b4bc0011728f5dcfdf674ca43080d1cf8af84ea7a06909f819b68500dfe}}
    yrt.int {:address :segment/blake3-8fd992e384e0e8b897cc9fef6f780fa4940d82b5f771aad60e5042c84c507728
     :derivations
     {:yin.semantic/code :segment/blake3-a6c60af9c95b208100f66aca89afceeb6ee9786f36235a165051a2deccfeeead
      :yin.debruijn.code :segment/blake3-b96d04a308ce7d84e96a63dacd6641b9008c1542680f29744be375a63e85ea7d
      :yin.debruijn.register :segment/blake3-1bb28e39e6de814dc4ff59674a30e140229443622432e99d589f93b5f0019374}
     :index
     {:yin.debruijn.code :segment/blake3-8537e7cac3a529139d251f3d8d1b669770d5fda1eb5e3c26073a60b585a3d0c4
      :yin.debruijn.register :segment/blake3-96126491c7ee191b1a9c2ac8c10697428e7dc07d7033d6c0d06ad70eae6b37ad}}
    })


;; py-v1 descriptor and runtime re-minted from the updated module closure
;; (py/assert thunk series + :py/missing sentinel, ruling 1795660000000)
;; after verified ANTLR generation, in two independent JVMs.
(def ^:private revision-locks
  {:yang.python/py-v1 {:descriptor-address :segment/blake3-b553f906b68916f350c70d1f78821a053c3e806254a79ed7a8cdc04622bb2102
     :runtime-address :segment/blake3-97a043efc6fcdad337099a4c49d6f3ea25d6cc44a85bee0d79c53ef4ef056fc8}
    :yang.python/clojure-core-v1 {:descriptor-address :segment/blake3-40e7c28f50d14baafc3dd73269d92023f3c664b743d5b7eb510859c98ecbdea4
     :runtime-address :segment/blake3-a2cb190a9c0f659dcf8a7c48023cfb49d3c87a5d883948fd815bfc0e55205b50}})


(def ^:private pins
  (into {} (map (fn [[module lock]] [module (:address lock)])) module-locks))


(def ^:private frontend-contract
  (contract/frontend-contract (select-keys pins (profile-modules contract/legacy-id))
                              (select-keys pins (profile-modules contract/clojure-id))))


(defn- specs [registry]
  [['yang.call (fn [_] (call/module-spec registry))]
   ['yrt.effect (fn [_] (effect/module-spec registry))]
   ['yrt.int (fn [pins] (int-rt/module-spec registry (pins 'yrt.effect)))]
   ['clj (fn [pins] (clj/module-spec registry (pins 'yang.call) (pins 'yrt.effect)))]
   ['clojure.core.impl
    (fn [pins] (impl/module-spec registry (pins 'yang.call) (pins 'clj) (pins 'yrt.int)))]
   ['clojure.core
    (fn [pins] (library/module-spec registry (pins 'clj) (pins 'clojure.core.impl)))]
   ['clj.py (fn [pins] (runtime/module-spec registry pins))]
   ['py (fn [pins] (py/module-spec registry (pins 'yang.call)))]])


(defn- verify-address! [address value]
  (when-not (jing/segment-bytes-match? address (jing/canonical-bytes value))
    (throw (ex-info "Python profile artifact differs from its revision pin"
                    {:status :refused :reason :yang.semantics/invalid-descriptor
                     :defect :address-mismatch :address address}))))


(defn- runtime-artifacts! [store registry modules publish?]
  (let [specs (into {} (specs registry))]
    (reduce (fn [artifacts module]
              (let [{:keys [address derivations index]} (module-locks module)
                    spec ((specs module) pins)
                    tree (vm/ast->semantic-bytecode (:ast spec))
                    result (when publish? (publish/publish-module! store spec))
                    _ (when (and publish? (nil? (:manifest result)))
                        (throw (ex-info "Python runtime publication refused" result)))
                    manifest (if publish?
                               (:manifest result)
                               (publish/module-manifest
                                 spec (:root tree) derivations index
                                 (publish/footprint store (assoc spec :tree tree))))]
                (verify-address! address manifest)
                (when-not publish? (jing/materialize! store manifest))
                (assoc artifacts address manifest (:root tree) tree)))
            {} modules)))


(defn- profile [id modules pins artifacts]
  (let [root (peek modules)
        address (pins root)
        manifest (artifacts address)]
    {:yang.prelude/schema 1
     :yang.prelude/id id
     :yang.prelude/revision (name id)
     :yang.prelude/module root
     :yang.prelude/root-address address
     :yang.prelude/components (mapv vec (sort-by key jing/canonical-compare (:yin.module/requires manifest)))
     :yang.prelude/host-modules
     (cond-> {:cell {:required true}
              :data {:required true :min-max-items 1048576}
              :integer {:required true :min-bits 53}}
       (= id contract/clojure-id) (assoc :float {:required true} :kind {:required true}))
     :yang.prelude/max-effects nil}))


(defn admit
  "Reconcile the descriptor and runtime pins by module symbol before checking
   executable roles and runtime requirements."
  [descriptor runtime frontend-contract artifacts registry limits]
  (let [{:yang.prelude/keys [module root-address components]} runtime
        pins (into {} (map (juxt :module :address)) (:yang.semantics/ambient-modules descriptor))]
    (when-not (and (= module (:yang.semantics/prelude-module descriptor))
                   (= root-address (get pins module))
                   (every? (fn [[dependency address]] (= address (get pins dependency))) components))
      (throw (ex-info "Python runtime and descriptor pins disagree"
                      {:status :refused :reason :yang.semantics/invalid-descriptor
                       :defect :runtime-profile-pins})))
    (semantics/validate-descriptor descriptor frontend-contract artifacts registry)
    (admission/admit-profile runtime artifacts registry limits)
    descriptor))


(defn builtin
  "The descriptor and immutable addresses selected by a Python revision."
  [id]
  (let [closure (mapv (fn [module] [module (pins module)]) (profile-modules id))]
    (assoc (revision-locks id)
           :descriptor (contract/descriptor id closure)
           :closure closure)))


(defn- admitted-profile [id artifacts registry]
  (let [{:keys [descriptor descriptor-address runtime-address] :as resolved} (builtin id)
        runtime (profile id (profile-modules id) pins artifacts)
        limits {:data {:max-items max-items}
                :integer {:bits (::integer/max-bits integer-limits)
                          :digits (::integer/max-digits integer-limits)}}]
    (verify-address! descriptor-address descriptor)
    (verify-address! runtime-address runtime)
    (admit descriptor runtime frontend-contract artifacts registry limits)
    (assoc resolved :runtime runtime)))


(defn- trusted-registry []
  (assoc (registry) :trusted-prelude-modules (select-keys pins '[py clj])))


(defn prepare!
  "Publish the selected pinned profiles and their verified runtime closures
   into the composition's store. With no selection, publish both revisions."
  ([store] (prepare! store [contract/legacy-id contract/clojure-id]))
  ([store ids]
   (let [registry (trusted-registry)
         modules (vec (distinct (mapcat profile-modules ids)))
         artifacts (runtime-artifacts! store registry modules true)
         profiles (into {}
                        (map (fn [id]
                               (let [{:keys [descriptor runtime] :as resolved}
                                     (admitted-profile id artifacts registry)]
                                 (jing/materialize! store descriptor)
                                 (jing/materialize! store runtime)
                                 [id resolved])))
                        ids)]
     {:profiles profiles :pins (select-keys pins modules)
      :artifacts artifacts :contract frontend-contract :registry registry})))


(defn- validate-builtin [id]
  (let [registry (trusted-registry)
        artifacts (runtime-artifacts! (mem/create-content-mem) registry (profile-modules id) false)]
    (admitted-profile id artifacts registry)))


(def ^:private checked-profiles
  ;; Memoize the verification of fixed, immutable artifacts. No runtime
  ;; handle, resolver cell or request state is retained here.
  {contract/legacy-id (delay (validate-builtin contract/legacy-id))
   contract/clojure-id (delay (validate-builtin contract/clojure-id))})


(defn verify-builtin! [id]
  @(get checked-profiles id))


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
