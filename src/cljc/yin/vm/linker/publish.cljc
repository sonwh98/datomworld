(ns yin.vm.linker.publish
  "Publish a complete four-format module closure into a DaoJing handle."
  (:require [dao.jing :as jing]
            [dao.space.query :as query]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.ledger :as ledger]
            [yin.vm.linearize :as linearize]
            [yin.vm.linker :as linker]))


(defn- refusal
  [reason data]
  (merge {:status :refused :reason reason} data))


(defn- definitions
  [ast]
  (letfn [(scan
            [node]
            (when (map? node)
              (case (:type node)
                :lambda #{}
                :application
                (let [xs (:operands node)
                      own (when (and (vm/definition-operator? (:operator node))
                                     (= 2 (count xs))
                                     (= :literal (:type (first xs)))
                                     (symbol? (:value (first xs))))
                            #{(:value (first xs))})]
                  (into (or own #{}) (mapcat scan)
                        (concat (when (= :lambda (:type (:operator node)))
                                  [(:body (:operator node))])
                                xs)))
                #{})))]
    (scan ast)))


(defn footprint
  "Derive the manifest footprint from tree rows, declared profiles and dependencies."
  [handle {:keys [ast tree primitives requires]}]
  (let [tree (or tree (vm/ast->semantic-bytecode ast))
        own (vm/ast-requirements (query/relation (vals (:rows tree))))
        bad-primitive (some (fn [[name profile]]
                              (when-not (and (map? profile)
                                             (keyword? (:yin.k/profile profile))
                                             (set? (:yin.k/effects profile)))
                                name))
                            primitives)
        missing (some (fn [[name address]]
                        (let [manifest (jing/get handle address ::absent)]
                          (when (= ::absent manifest) [name address])))
                      requires)]
    (cond
      (seq (:ffi-ops own))
      (refusal :yin.link.publish/ffi-op {:ffi-ops (:ffi-ops own)})

      (seq (:parked-ids own))
      (refusal :yin.link.publish/parked-id {:parked-ids (:parked-ids own)})

      bad-primitive
      (refusal :yin.link.publish/unprofiled-primitive
               {:primitive bad-primitive})

      missing
      (refusal :yin.link.publish/missing-requirement
               {:name (first missing) :manifest (second missing)})

      :else
      {:store-keys (:store-keys own)
       :effects (into (or (:effects own) #{})
                      (concat (mapcat :yin.k/effects (vals primitives))
                              (mapcat (fn [address]
                                        (get-in (jing/get handle address nil)
                                                [:yin.module/footprint :effects]))
                                      (vals requires))))})))


(defn publish-module!
  "Mint all four code formats, derivation records, and one schema-1 manifest."
  [handle {:keys [name ast exports requires primitives] :as spec}]
  (let [undefined (first (remove (definitions ast) exports))
        tree (when-not undefined
               (try (vm/ast->semantic-bytecode ast)
                    (catch #?(:cljd Object :clj Exception :cljs :default) _
                      (refusal :yin.link.publish/malformed-tree {}))))
        defect (when (and tree (not= :refused (:status tree)))
                 (vm/validate-rows tree))
        invalid-requirement
        (when (and tree (not defect))
          (some (fn [[n address]]
                  (let [manifest (jing/get handle address ::absent)]
                    (when (and (not= ::absent manifest)
                               (linker/manifest-defect manifest))
                      n)))
                requires))
        fp (when (and tree (not defect) (not invalid-requirement)
                      (not= :refused (:status tree)))
             (footprint handle (assoc spec :tree tree)))]
    (cond
      undefined
      (refusal :yin.link.publish/undefined-export {:export undefined})

      (= :refused (:status tree)) tree

      defect
      (refusal :yin.link.publish/malformed-tree {:defect defect})

      invalid-requirement
      (refusal :yin.link.publish/invalid-requirement
               {:name invalid-requirement})

      (= :refused (:status fp)) fp

      :else
      (let [tree-address (vm/materialize-tree! handle tree)
            sem-vector (:vector (linearize/lower-rows tree))
            sem (code/materialize-vector! handle sem-vector)
            h-image (:image (dl/adapt (vm/ast->datoms ast)))
            r-image (:image (rc/adapt (second (vm/ast->datoms-with-root ast))))
            h (dcode/image-hash h-image)
            r (rcode/register-hash r-image)
            h-address (jing/materialize! handle h-image)
            r-address (jing/materialize! handle r-image)
            mint (fn [output profile]
                   (jing/materialize!
                     handle
                     (assoc (ledger/derive-record tree-address output)
                            :yin.ledger/profile profile)))
            manifest {:yin.module/name name
                      :yin.module/schema linker/manifest-schema
                      :yin.module/contracts
                      {:yin.ast/code vm/ast-contract
                       :yin.semantic/code vm/semantic-contract
                       :yin.debruijn.code vm/stack-contract
                       :yin.debruijn.register vm/register-contract}
                      :yin.module/tree tree-address
                      :yin.module/derivations
                      {:yin.semantic/code (mint sem ledger/lowering-profile)
                       :yin.debruijn.code
                       (mint h linker/stack-lowering-profile)
                       :yin.debruijn.register
                       (mint r linker/register-lowering-profile)}
                      :yin.module/index {h h-address r r-address}
                      :yin.module/exports (set exports)
                      :yin.module/requires (or requires {})
                      :yin.module/primitives
                      (into {} (map (fn [[n p]] [n (:yin.k/profile p)])
                                    primitives))
                      :yin.module/footprint fp}
            address (jing/materialize! handle manifest)
            formats [linker/ast-format linker/semantic-format
                     linker/stack-format linker/register-format]
            runtime-opts {:formats
                          (into {} (map (fn [f] [(:format f) f]))
                                (concat formats
                                        [linker/manifest-format
                                         linker/record-format]))}
            links (into {}
                        (map (fn [f]
                               [(:format f)
                                (linker/link-manifest
                                  (linker/local-runtime handle runtime-opts)
                                  address (:format f)
                                  {:primitives vm/primitives}
                                  {:contract (:contract f)
                                   :derivation :verifying
                                   :defer-discharge true})])
                             formats))]
        {:address address :manifest manifest
         :identities {:yin.semantic/code sem
                      :yin.debruijn.code h
                      :yin.debruijn.register r}
         :links links}))))
