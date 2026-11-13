(ns yang.php.antlr.siteset
  "Publication of PHP occurrence marks beside canonical module rows."
  (:require
    [dao.jing :as jing]
    [yang.safepoint :as safepoint]
    [yin.vm :as vm]
    [yin.vm.encoder :as encoder]
    [yin.vm.linker.sign :as sign]))


(def frontend-revision "yang.php.antlr.lower/sites-1")


(def lowering-profile
  {:yang.site/unit :module, :yang.site/prelude :linked,
   :yang.site/kinds [:call :line :loop]})


(def profile-address (jing/segment-key lowering-profile))


(defn site-set-of
  "The canonical root and occurrence marks of module AST `ast`."
  [ast]
  (let [env (encoder/project (encoder/source-envelope
                               :yang.php.antlr/site-set [:site-set 0] [ast]))
        tree (nth (:yin/batch env) (:yin/root env))
        origin (vm/source-origin (:yin/source-medium env)
                                 (:yin/batch-token env) (:yin/root env))
        sites (safepoint/sites (:yin/frontend-metadata env) origin (:root tree))]
    {:root (:root tree), :set (safepoint/site-set sites)}))


(defn publish!
  "Publish the set, including an empty set, and its manifest/root binding."
  [handle manifest ast]
  (let [{:keys [root set]} (site-set-of ast)
        set-address (jing/materialize! handle (vec set))
        record {:yang.site/manifest manifest, :yang.site/root root,
                :yang.site/revision frontend-revision,
                :yang.site/profile profile-address, :yang.site/set set-address}]
    {:record record, :record-address (jing/materialize! handle record),
     :set-address set-address, :set set}))


(defn- read-at
  [handle address]
  (when (jing/segment-address? address)
    (let [bytes ((:get-bytes-fn handle) address nil)]
      (when (and bytes (jing/segment-bytes-match? address bytes))
        (jing/segment-value address bytes)))))


(defn verify
  "Read and verify a published set, or return nil for missing or mismatched
   metadata. An empty set is a valid publication."
  [handle manifest record-address]
  (let [module (read-at handle manifest)
        record (read-at handle record-address)
        sites (read-at handle (:yang.site/set record))]
    (when (and (map? module) (map? record) (vector? sites)
               (= manifest (:yang.site/manifest record))
               (= (:yin.module/tree module) (:yang.site/root record))
               (= frontend-revision (:yang.site/revision record))
               (= profile-address (:yang.site/profile record))
               (every? (fn [entry]
                         (and (vector? entry) (= 2 (count entry))
                              (vector? (first entry))
                              (let [mark (second entry)
                                    kind (if (vector? mark) (first mark) mark)]
                                (contains? #{:call :line :loop} kind))))
                       sites)
               (= sites (safepoint/site-set (into {} sites))))
      {:record record, :set sites})))


(defn signed-entry
  "Sign the association between a published manifest and its site record."
  [key manifest record-address]
  (let [env {:yang.site/op :publish,
             :yang.site/manifest manifest,
             :yang.site/record record-address,
             :yin.module/asserted-by (sign/principal (:public key))}]
    {:envelope env, :proof (sign/sign-envelope (:seed key) env)}))


(defn entry-datoms
  "Index signed site publications alongside name assertions."
  [signed]
  (vec (mapcat (fn [e {:keys [envelope proof]}]
                 [[e :yang.site/envelope envelope]
                  [e :yang.site/proof proof]])
               (range) signed)))


(defn discover
  "Find site record addresses signed by a declared public key for manifest."
  [datoms publics manifest]
  (let [proofs (reduce (fn [acc [e a v]]
                         (cond-> acc
                           (= a :yang.site/proof) (update e (fnil conj #{}) v)))
                       {} datoms)]
    (vec (distinct
           (keep (fn [[e a env]]
                   (when (and (= a :yang.site/envelope) (map? env)
                              (= :publish (:yang.site/op env))
                              (= manifest (:yang.site/manifest env)))
                     (let [ps (get proofs e)
                           sig (when (= 1 (count ps))
                                 (:yin.module/signature (first ps)))]
                       (when (and (string? sig)
                                  (some #(and (= (:yin.module/asserted-by env)
                                                 (sign/principal %))
                                              (sign/verify-envelope
                                                % (jing/canonical-bytes env) sig))
                                        publics))
                         (:yang.site/record env)))))
                 datoms)))))


(defn signed-entries
  "Sign all site publications returned by unit publication, including aliases."
  [publisher sites addresses bundles]
  (mapv (fn [[k s]]
          (signed-entry publisher (or (get addresses k) (get bundles k))
                        (:record-address s)))
        (sort-by (comp str key) sites)))


(defn derive-verified
  "Re-derive a validated module tree from its verified published occurrence set.
   The record pins both the full published set and the profile-selected set.
   Returns nil when the tree or publication does not match the manifest."
  [handle manifest record-address tree profile]
  (when-let [{:keys [record set]} (verify handle manifest record-address)]
    (when (and (= (:yang.site/root record) (:root tree))
               (nil? (vm/validate-rows tree))
               (every? (fn [[id row]]
                         (and (= id (first row))
                              (= id (jing/segment-key (subvec row 1)))))
                       (:rows tree)))
      (let [{derived :tree selected :sites} (safepoint/insert tree (into {} set) profile)
            derivation (assoc-in (safepoint/derive-record (:root tree) (:root derived)
                                                          profile selected)
                                 [:yin.ledger/profile :yang.site/published-set]
                                 (:yang.site/set record))]
        {:tree derived, :record derivation,
         :record-address (jing/segment-key derivation),
         :site-set-address (:yang.site/set record)}))))
