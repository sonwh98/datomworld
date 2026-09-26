(ns yin.vm.linker-manifest-test
  "S4 (docs/design/yin.vm.linker.md sections 8.1 and 5.5, criteria 18 and
   22): the module manifest and the derivation record as content, the
   manifest-delivered image link, and the fallback pair reading
   derivations from the manifest. Manifests are fetched by address: the
   name check runs against the name the caller resolved the manifest
   under. The corpus stays print-stable across hosts."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.jing.remote :as remote]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.content :as content]
            #?@(:cljd [] :clj [[yin.vm.debruijn-linearize :as linearize]])
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.ledger :as ledger]
            [yin.vm.linker :as linker]
            [yin.vm.linker-test :as lt]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Fixtures: minting a four-way manifest (section 8.1)
;; =============================================================================

(def plus-profile
  "The published profile address of `+`, the one free name the corpus
   images retain."
  (get-in vm/primitives ['+ :yin.k/profile]))


(def star-profile
  "The published profile address of `*`, `other-program`'s free name."
  (get-in vm/primitives ['* :yin.k/profile]))


(defn- register-image
  "The register image `ast` lowers to, over the public datom lane (the
   linker-test helper is private)."
  [ast]
  (:image (rc/adapt (vm/ast->datoms ast))))


(defn- derive-records
  "The three derivation records leading from `tree-addr` to the images of
   `ast`, each published in `store`, keyed by format. Each record carries
   the profile its format's re-lowering pins (section 8.1)."
  [store tree-addr ast]
  (let [s (lt/publish store linker/semantic-format (lt/semantic-vector ast))
        h (lt/publish store linker/stack-format (lt/stack-image ast))
        r (lt/publish store linker/register-format (register-image ast))
        mint (fn [out profile]
               (jing/materialize!
                 store (assoc (ledger/derive-record tree-addr out)
                              :yin.ledger/profile profile)))]
    {:yin.semantic/code (mint (:identity s) ledger/lowering-profile)
     :yin.debruijn.code (mint (:identity h) linker/stack-lowering-profile)
     :yin.debruijn.register (mint (:identity r)
                                  linker/register-lowering-profile)
     :identities {:sem (:identity s), :h (:identity h), :r (:identity r)}
     :addresses {:h (:address h), :r (:address r)}}))


(defn module-manifest
  "Publish `ast` as the module `name` in `store`: its tree, its three
   images, its three derivation records, and the schema-1 manifest naming
   them. Returns the manifest map under `:manifest`, its address under
   `:address`, and the minted records' own data under `:records`."
  ([store ast] (module-manifest store ast 'my.lib))
  ([store ast name]
   (let [tree (vm/ast->semantic-bytecode ast)
         tree-addr (content/materialize-tree! store tree)
         {:keys [identities addresses] :as recs}
         (derive-records store tree-addr ast)
         manifest {:yin.module/name name
                   :yin.module/schema linker/manifest-schema
                   :yin.module/contracts
                   {:yin.ast/code vm/ast-contract
                    :yin.semantic/code vm/semantic-contract
                    :yin.debruijn.code vm/stack-contract
                    :yin.debruijn.register vm/register-contract}
                   :yin.module/tree tree-addr
                   :yin.module/derivations
                   {:yin.semantic/code (get recs :yin.semantic/code)
                    :yin.debruijn.code (get recs :yin.debruijn.code)
                    :yin.debruijn.register (get recs :yin.debruijn.register)}
                   :yin.module/index {(:h identities) (:h addresses)
                                      (:r identities) (:r addresses)}
                   :yin.module/exports #{'result}
                   :yin.module/requires {}
                   :yin.module/primitives {'+ plus-profile}
                   :yin.module/footprint {:store-keys #{} :effects #{}}}]
     {:manifest manifest
      :address (jing/materialize! store manifest)
      :records recs
      :tree tree-addr})))


(def all-formats
  "The six format records a manifest-linking composition holds."
  [linker/ast-format linker/semantic-format
   linker/stack-format linker/register-format
   linker/manifest-format linker/record-format])


(defn manifest-runtime
  "A single-process link runtime over `store` holding every format record
   and `opts` (normally `:indexes`)."
  ([store] (manifest-runtime store {}))
  ([store opts]
   (lt/local-runtime
     (remote/default-handlers store)
     (assoc opts :formats (into {} (map (fn [f] [(:format f) f]))
                                all-formats)))))


(defn requested
  "The fetch options of a requester that runs `format`'s own contract."
  [format]
  {:contract (:contract format)})


(defn linking
  "The `link-manifest` opts of a requester that runs `format`'s own
   contract under the derivation `policy`."
  ([format] (linking format :verifying))
  ([format policy]
   {:contract (:contract format), :derivation policy}))


(defn link-local
  "Link `format`'s image through the manifest at `address` over `store`,
   a fresh runtime per call."
  ([store address format]
   (link-local store address format (linking format)))
  ([store address format opts]
   (linker/link-manifest (manifest-runtime store) address (:format format)
                         lt/receiver opts)))


(defn- run-image
  "The VM that ran the verified outcome `res` of `format` to completion on
   its own backend."
  [format res]
  (vm/run
    (case (:format format)
      :yin.ast/code (ast-walker/vm-load-rows (tu/create-vm) (:value res)
                                             (:contract format))
      :yin.semantic/code (semantic/load-vector (semantic/create-vm)
                                               (:value res)
                                               (:contract format))
      :yin.debruijn.code (dvm/create-vm (:value res)
                                        (assoc lt/receiver
                                               :contract (:contract format)))
      :yin.debruijn.register (rvm/create-vm (:value res)
                                            (assoc lt/receiver
                                                   :contract
                                                   (:contract format))))))


(defn- normalized
  "The B0-normalized value of `ast` evaluated locally."
  [ast]
  (b0/normalize (vm/value (vm/eval (tu/create-vm) ast))))


;; =============================================================================
;; The two new format records (section 8.1)
;; =============================================================================

(deftest manifests-and-records-verify-as-content
  (let [store (mem/create-content-mem)
        {:keys [manifest address records tree]} (module-manifest
                                                  store lt/worked-example)
        m (lt/fetch-local store identity linker/manifest-format address {}
                          (requested linker/manifest-format))
        rec-addr (get-in manifest [:yin.module/derivations
                                   :yin.semantic/code])
        r (lt/fetch-local store identity linker/record-format rec-addr {}
                          (requested linker/record-format))]
    (is (linker/ok? m))
    (is (= manifest (:value m))
        "the manifest is the payload at its own address")
    (is (= [] (:obligations m))
        "a manifest carries no obligations of its own")
    (is (linker/ok? r))
    (is (= (assoc (ledger/derive-record tree
                                        (get-in records [:identities :sem]))
                  :yin.ledger/profile ledger/lowering-profile)
           (:value r))
        "the derivation record is the payload at its own address")
    (is (= [] (:obligations r)))
    (jing/close! store)))


(deftest another-schema-version-is-a-schema-defect
  (let [store (mem/create-content-mem)
        {:keys [manifest]} (module-manifest store lt/worked-example)
        bad (assoc manifest :yin.module/schema 99)
        addr (jing/materialize! store bad)
        res (lt/fetch-local store identity linker/manifest-format addr {}
                            (requested linker/manifest-format))]
    (is (= :descriptor-defect (:reason res)))
    (is (= {:rule :schema} (:defect res)))
    (jing/close! store)))


(deftest a-derivation-without-a-contract-entry-is-a-defect
  (let [store (mem/create-content-mem)
        {:keys [manifest]} (module-manifest store lt/worked-example)
        bad (assoc manifest
                   :yin.module/contracts
                   (dissoc (:yin.module/contracts manifest)
                           :yin.semantic/code))
        addr (jing/materialize! store bad)
        res (lt/fetch-local store identity linker/manifest-format addr {}
                            (requested linker/manifest-format))]
    (is (= :descriptor-defect (:reason res)))
    (is (= {:rule :contract-missing, :format :yin.semantic/code}
           (:defect res)))
    (jing/close! store)))


(deftest a-non-map-manifest-is-a-shape-defect
  (let [store (mem/create-content-mem)]
    (doseq [payload [[1 2] "manifest"]]
      (let [addr (jing/materialize! store payload)
            res (lt/fetch-local store identity linker/manifest-format addr
                                {} (requested linker/manifest-format))]
        (is (= :descriptor-defect (:reason res)))
        (is (= {:rule :manifest-shape} (:defect res))
            "a payload that is no map never verifies as a manifest")))
    (jing/close! store)))


(deftest a-record-of-the-wrong-op-or-shape-is-a-defect
  (let [store (mem/create-content-mem)
        {:keys [records tree]} (module-manifest store lt/worked-example)
        rec (assoc (ledger/derive-record tree (get-in records
                                                      [:identities :sem]))
                   :yin.ledger/profile ledger/lowering-profile)]
    (is (nil? (linker/record-defect rec)))
    (is (= {:rule :op, :op :other}
           (linker/record-defect (assoc rec :yin.ledger/op :other))))
    (is (= {:rule :record-shape}
           (linker/record-defect (assoc rec :extra 1))))
    (jing/close! store)))


#?(:cljd nil
   :clj
   (deftest a-tree-the-lowering-cannot-run-is-unverified-derivation
     (doseq [[format lower] [[linker/stack-format #'linearize/adapt]
                             [linker/register-format #'rc/adapt]]]
       (let [store (mem/create-content-mem)
             tree (content/materialize-tree!
                    store (vm/ast->semantic-bytecode lt/worked-example))
             profile (if (= linker/stack-format format)
                       linker/stack-lowering-profile
                       linker/register-lowering-profile)
             rec (jing/materialize!
                   store (assoc (ledger/derive-record tree "out")
                                :yin.ledger/profile profile))
             addr (jing/materialize!
                    store
                    {:yin.module/name 'my.lib
                     :yin.module/schema linker/manifest-schema
                     :yin.module/contracts {(:format format)
                                            (:contract format)}
                     :yin.module/tree tree
                     :yin.module/derivations {(:format format) rec}
                     :yin.module/index {}
                     :yin.module/exports #{}
                     :yin.module/requires {}
                     :yin.module/primitives {}
                     :yin.module/footprint {:store-keys #{}, :effects #{}}})
             res (with-redefs-fn {lower (fn [_] (throw (ex-info "no" {})))}
                   (fn [] (link-local store addr format)))]
         (is (= {:status :refused, :reason :unverified-derivation,
                 :format (:format format), :profile profile,
                 :missing :relowering}
                (select-keys res [:status :reason :format :profile
                                  :missing])))
         (jing/close! store)))))


(deftest a-manifest-stamping-another-contract-is-contract-mismatch
  (let [store (mem/create-content-mem)
        {:keys [manifest]} (module-manifest store lt/worked-example)
        stamped (jing/materialize!
                  store (assoc-in manifest
                                  [:yin.module/contracts :yin.debruijn.code]
                                  "b1"))]
    (is (= {:status :refused, :reason :contract-mismatch,
            :expected vm/stack-contract, :actual "b1"}
           (link-local store stamped linker/stack-format))
        "the requester's contract is expected, the manifest's claim actual")
    (jing/close! store)))


(deftest a-manifest-declaring-the-definition-operator-is-reserved-name
  (let [store (mem/create-content-mem)
        {:keys [manifest]} (module-manifest store lt/worked-example)
        bad (assoc manifest :yin.module/exports #{'result 'yin/def})
        addr (jing/materialize! store bad)
        res (lt/fetch-local store identity linker/manifest-format addr {}
                            (requested linker/manifest-format))]
    (is (= :descriptor-defect (:reason res)))
    (is (= {:rule :reserved-name, :name 'yin/def, :role :declaration}
           (:defect res))
        "Rule R: the definition operator is syntax, never a declared name")
    (jing/close! store)))


;; =============================================================================
;; The name check (section 8.1)
;; =============================================================================

(deftest a-manifest-declaring-another-name-is-module-name-mismatch
  (let [store (mem/create-content-mem)
        {:keys [address]} (module-manifest store lt/worked-example
                                           'other.lib)]
    (is (= {:status :refused, :reason :module-name-mismatch,
            :name 'my.lib, :declared 'other.lib}
           (link-local store address linker/semantic-format
                       (assoc (linking linker/semantic-format)
                              :name 'my.lib)))
        "a name resolves only to a manifest claiming that name")
    (is (linker/ok? (link-local store address linker/semantic-format
                                (assoc (linking linker/semantic-format)
                                       :name 'other.lib))))
    (jing/close! store)))


;; =============================================================================
;; Derivation verification (section 8.1; criterion 13's swapped images)
;; =============================================================================

(defn- swapped-manifest
  "A manifest whose tree is `main`'s but whose derivation records lead
   from `from`'s tree: every image individually valid, every root
   another."
  [store main from]
  (let [main-tree (content/materialize-tree! store
                                             (vm/ast->semantic-bytecode main))
        from-tree (content/materialize-tree! store
                                             (vm/ast->semantic-bytecode from))
        recs (derive-records store from-tree from)
        manifest {:yin.module/name 'my.lib
                  :yin.module/schema linker/manifest-schema
                  :yin.module/contracts
                  {:yin.ast/code vm/ast-contract
                   :yin.semantic/code vm/semantic-contract
                   :yin.debruijn.code vm/stack-contract
                   :yin.debruijn.register vm/register-contract}
                  :yin.module/tree main-tree
                  :yin.module/derivations
                  {:yin.semantic/code (get recs :yin.semantic/code)
                   :yin.debruijn.code (get recs :yin.debruijn.code)
                   :yin.debruijn.register (get recs :yin.debruijn.register)}
                  :yin.module/index {(get-in recs [:identities :h])
                                     (get-in recs [:addresses :h])
                                     (get-in recs [:identities :r])
                                     (get-in recs [:addresses :r])}
                  :yin.module/exports #{}
                  :yin.module/requires {}
                  :yin.module/primitives {'+ plus-profile, '* plus-profile}
                  :yin.module/footprint {:store-keys #{}, :effects #{}}}]
    {:manifest manifest
     :address (jing/materialize! store manifest)
     :tree main-tree
     :records recs}))


(deftest records-leading-from-another-tree-are-derivation-mismatch
  (doseq [format [linker/semantic-format linker/stack-format
                  linker/register-format]]
    (testing (name (:format format))
      (doseq [policy [:verifying :trusted]]
        (testing (name policy)
          (let [store (mem/create-content-mem)
                {:keys [address tree]} (swapped-manifest
                                         store lt/worked-example
                                         lt/other-program)
                refusal (link-local store address format
                                    (linking format policy))]
            (is (= {:status :refused, :reason :derivation-mismatch,
                    :format (:format format)}
                   (select-keys refusal [:status :reason :format])))
            (is (= tree (:expected refusal))
                "the evidence names the manifest's tree and the record's
                 divergent input")
            (jing/close! store)))))))


(deftest a-record-claiming-another-tree-s-image-is-derivation-mismatch
  (let [store (mem/create-content-mem)
        tree (content/materialize-tree! store
                                        (vm/ast->semantic-bytecode
                                          lt/worked-example))
        other (derive-records store tree lt/other-program)
        manifest {:yin.module/name 'my.lib
                  :yin.module/schema linker/manifest-schema
                  :yin.module/contracts
                  {:yin.ast/code vm/ast-contract
                   :yin.semantic/code vm/semantic-contract
                   :yin.debruijn.code vm/stack-contract
                   :yin.debruijn.register vm/register-contract}
                  :yin.module/tree tree
                  :yin.module/derivations
                  {:yin.semantic/code (get other :yin.semantic/code)}
                  :yin.module/index {}
                  :yin.module/exports #{}
                  :yin.module/requires {}
                  :yin.module/primitives {'+ plus-profile, '* star-profile}
                  :yin.module/footprint {:store-keys #{}, :effects #{}}}
        address (jing/materialize! store manifest)]
    (testing "verifying recomputes and refuses"
      (is (= :derivation-mismatch
             (:reason (link-local store address linker/semantic-format
                                  (linking linker/semantic-format
                                           :verifying))))))
    (testing "trusted accepts the publisher's lowering, saying so"
      (let [res (link-local store address linker/semantic-format
                            (linking linker/semantic-format :trusted))]
        (is (linker/ok? res))
        (is (= :composition (:trust res)))))
    (jing/close! store)))


;; =============================================================================
;; Criterion 18: verifying never trusts
;; =============================================================================

(deftest an-unimplemented-profile-is-unverified-derivation-when-verifying
  (let [store (mem/create-content-mem)
        tree (content/materialize-tree! store
                                        (vm/ast->semantic-bytecode
                                          lt/worked-example))
        sem (lt/publish store linker/semantic-format
                        (lt/semantic-vector lt/worked-example))
        foreign {:yin.lower/profile "v2-expander",
                 :yin.code/contract vm/semantic-contract,
                 :yin.k/version 0}
        rec-addr (jing/materialize!
                   store (assoc (ledger/derive-record tree (:identity sem))
                                :yin.ledger/profile foreign))
        manifest {:yin.module/name 'my.lib
                  :yin.module/schema linker/manifest-schema
                  :yin.module/contracts
                  {:yin.semantic/code vm/semantic-contract}
                  :yin.module/tree tree
                  :yin.module/derivations {:yin.semantic/code rec-addr}
                  :yin.module/index {}
                  :yin.module/exports #{}
                  :yin.module/requires {}
                  :yin.module/primitives {'+ plus-profile}
                  :yin.module/footprint {:store-keys #{}, :effects #{}}}
        address (jing/materialize! store manifest)]
    (is (= {:status :refused, :reason :unverified-derivation,
            :format :yin.semantic/code, :profile foreign,
            :missing :profile}
           (dissoc (link-local store address linker/semantic-format
                               (linking linker/semantic-format :verifying))
                   :implemented)))
    (let [res (link-local store address linker/semantic-format
                          (linking linker/semantic-format :trusted))]
      (is (linker/ok? res))
      (is (= :composition (:trust res))
          "the same manifest installs under trust, never silently"))
    (jing/close! store)))


;; =============================================================================
;; Criterion 22: one four-way manifest through all four kernels
;; =============================================================================

(deftest one-four-way-manifest-links-through-all-four-kernels
  (doseq [policy [:verifying :trusted]]
    (doseq [format [linker/ast-format linker/semantic-format
                    linker/stack-format linker/register-format]]
      (testing (str (name policy) " " (name (:format format)))
        (let [store (mem/create-content-mem)
              {:keys [address]} (module-manifest store lt/worked-example)
              res (link-local store address format (linking format policy))]
          (is (linker/ok? res))
          (is (not= :contract-mismatch (:reason res)))
          (is (= (normalized lt/worked-example)
                 (b0/normalize (vm/value (run-image format res)))))
          (jing/close! store))))))


(deftest the-manifest-index-resolves-the-images-it-names
  (let [store (mem/create-content-mem)
        {:keys [address]} (module-manifest store lt/worked-example)
        res (linker/link-manifest
              (manifest-runtime store {:indexes {}})
              address :yin.debruijn.code lt/receiver
              (linking linker/stack-format :verifying))]
    (is (linker/ok? res))
    (is (= (lt/stack-image lt/worked-example) (:value res)))
    (jing/close! store)))


;; =============================================================================
;; Step 5a's manifest join (section 8.1, section 4.2 step 5a)
;; =============================================================================

(deftest a-free-name-the-manifest-never-declares-is-undeclared-free
  (let [store (mem/create-content-mem)
        {:keys [manifest]} (module-manifest store lt/worked-example)
        undeclared (jing/materialize!
                     store (assoc manifest :yin.module/primitives {}))]
    (is (= {:status :refused, :reason :undeclared-free, :name '+}
           (link-local store undeclared linker/semantic-format
                       (linking linker/semantic-format))))
    (jing/close! store)))


(deftest declared-obligations-carry-their-declaration
  (let [store (mem/create-content-mem)
        {:keys [address]} (module-manifest store lt/worked-example)
        res (link-local store address linker/semantic-format)]
    (is (linker/ok? res))
    (is (= [{:name '+, :kind :primitive, :profile plus-profile}]
           (:obligations res)))
    (jing/close! store)))


(deftest declared-obligations-discharge-by-profile-and-manifest
  (let [prim {:name '+, :kind :primitive, :profile plus-profile}
        receiver {:primitives vm/primitives}]
    (is (nil? (linker/discharge receiver [prim]))
        "an equal profile discharges")
    (is (= {:status :refused, :reason :unresolved-free,
            :name '+, :kind :primitive}
           (linker/discharge receiver [(assoc prim
                                              :profile
                                              :yin.k.pp/sha256-nope)]))
        "a same-named primitive of another profile resolves nothing")
    (is (= {:status :refused, :reason :shadowed-free, :name '+}
           (linker/discharge (assoc receiver :free-env {'+ 0}) [prim])))
    (is (= {:status :refused, :reason :unresolved-free,
            :name 'x, :kind :other}
           (linker/discharge receiver [{:name 'x, :kind :other}]))
        "an unknown declared kind resolves nothing")
    (let [mod {:name 'dep/f, :kind :module, :module 'dep,
               :manifest :segment/dep-manifest}
          modules {:modules {'dep {:manifest :segment/dep-manifest}}}]
      (is (nil? (linker/discharge {:modules modules} [mod])))
      (is (= {:status :refused, :reason :unresolved-free,
              :name 'dep/f, :kind :module}
             (linker/discharge
               {:modules {:modules {'dep {:manifest
                                          :segment/another}}}}
               [mod]))
          "a linked module of another manifest address resolves nothing"))))


(deftest a-declared-obligation-without-an-address-resolves-nothing
  (is (= {:status :refused, :reason :unresolved-free,
          :name 'p, :kind :primitive}
         (linker/discharge {:primitives {'p {:fn identity}}}
                           [{:name 'p, :kind :primitive}]))
      "a nil profile never equals an unprofiled primitive")
  (is (= {:status :refused, :reason :unresolved-free,
          :name 'dep/f, :kind :module}
         (linker/discharge {:modules {:modules {'dep {}}}}
                           [{:name 'dep/f, :kind :module, :module 'dep}]))
      "a nil manifest never equals an unmanifested module"))


;; =============================================================================
;; The fallback pair reads the manifest (section 5.5)
;; =============================================================================

(deftest the-fallbacks-read-derivations-from-the-manifest
  (let [store (mem/create-content-mem)
        {:keys [address] :as minted} (module-manifest store
                                                      lt/worked-example)
        h (get-in minted [:records :identities :h])]
    (testing "trusted-fallback names composition trust"
      (let [res (linker/trusted-fallback (manifest-runtime store) address
                                         lt/receiver)]
        (is (linker/ok? res))
        (is (= :composition (:trust res)))
        (is (= h (:identity res)))
        (is (= :yin.debruijn.code (:format res)))
        (is (= {:root (:tree minted), :manifest address,
                :from :yin.debruijn.register}
               (:fallback res)))
        (is (= (lt/stack-image lt/worked-example) (:value res)))))
    (testing "verifying-fallback re-lowers the manifest's tree"
      (let [res (linker/verifying-fallback (manifest-runtime store) address
                                           lt/receiver)]
        (is (linker/ok? res))
        (is (= :verified (:trust res)))
        (is (= (lt/stack-image lt/worked-example) (:value res)))))
    (testing "a swapped derivation during a fallback is a pairing mismatch"
      (let [{:keys [address]} (swapped-manifest store lt/worked-example
                                                lt/other-program)]
        (is (= :pairing-mismatch
               (:reason (linker/trusted-fallback (manifest-runtime store)
                                                 address lt/receiver))))
        (is (= :pairing-mismatch
               (:reason (linker/verifying-fallback (manifest-runtime store)
                                                   address lt/receiver))))))
    (jing/close! store)))
