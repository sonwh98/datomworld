(ns yang.php.antlr.siteset
  "Publication of PHP occurrence marks beside canonical module rows."
  (:require
    [dao.jing :as jing]
    [yang.safepoint :as safepoint]
    [yang.core.module :as core-module]
    [yang.core.uast :as u]
    [yin.vm.linker.publish :as publish]
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
    (let [bytes ((:get-bytes-fn handle) address ::absent)]
      (cond
        (or (nil? bytes) (= ::absent bytes)) ::absent
        (jing/segment-bytes-match? address bytes) (jing/segment-value address bytes)))))


(defn verify
  "Read and verify a published set, or return nil for missing or mismatched
   metadata. An empty set is a valid publication."
  [handle manifest record-address]
  (let [module (read-at handle manifest)
        record (read-at handle record-address)
        sites (read-at handle (when (map? record) (:yang.site/set record)))]
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


(defn- absent?
  [x]
  (= ::absent x))


(defn- missing
  [address]
  {:status :missing, :address address})


(defn- load-tree
  "The canonical row set `{:root :rows}` of the tree at `root` in `handle`,
   every row fetched by its address, depth-first: `{:status :missing
   :address a}` for the first row the handle does not hold, nil when a row
   is not its address or no tree row."
  [handle root]
  (loop [todo [root]
         rows {}]
    (if-let [id (peek todo)]
      (if (contains? rows id)
        (recur (pop todo) rows)
        (let [body (read-at handle id)
              slots (when (vector? body)
                      (get vm/semantic-bytecode-grammar (first body)))]
          (cond
            (absent? body) (missing id)
            slots (recur (into (pop todo)
                               (mapcat (fn [[_ kind] v]
                                         (case kind
                                           :node [v]
                                           :nodes v
                                           nil))
                                       slots
                                       (rest body)))
                         (assoc rows id (into [id] body)))
            :else nil)))
      {:root root, :rows rows})))


(defn- overlay
  "A write-buffering handle over `base`: reads see the buffer first, then
   `base`; puts land in the buffer only, in put order. `flush!` writes the
   buffer to `base`; dropping the overlay writes nothing."
  [base]
  (let [buf (atom {:bytes {}, :order []})
        sentinel #?(:cljs (js-obj)
                    :default (Object.))]
    {:handle (assoc base
                    :get-bytes-fn (fn [address not-found]
                                    (let [bs (get-in @buf [:bytes address])]
                                      (if (nil? bs)
                                        ((:get-bytes-fn base) address not-found)
                                        bs)))
                    :put-bytes-fn (fn [address bs]
                                    (if (or (contains? (:bytes @buf) address)
                                            (let [existing ((:get-bytes-fn base)
                                                            address
                                                            sentinel)]
                                              (and (some? existing)
                                                   (not (identical? sentinel
                                                                    existing)))))
                                      :present
                                      (do (swap! buf (fn [b]
                                                       (-> b
                                                           (assoc-in [:bytes address]
                                                                     bs)
                                                           (update :order conj
                                                                   address))))
                                          :inserted))))
     :flush! (fn []
               (let [{:keys [bytes order]} @buf]
                 (doseq [a order] ((:put-bytes-fn base) a (get bytes a)))))}))


(defn- stage-refusal
  [reason manifest]
  {:status :refused, :reason reason, :manifest manifest})


(defn- stage-admit
  [handle manifest records]
  (let [m (read-at handle manifest)]
    (cond
      (absent? m) (missing manifest)
      (not (map? m)) (stage-refusal :yang.php.antlr/site-set-mismatch manifest)
      (empty? records) (stage-refusal :yang.php.antlr/missing-site-set manifest)
      :else
      (loop [[a & more] records]
        (if-not a
          (stage-refusal :yang.php.antlr/site-set-mismatch manifest)
          (let [record (read-at handle a)
                set-address (when (map? record) (:yang.site/set record))
                sites (when set-address (read-at handle set-address))]
            (cond
              (absent? record) (missing a)
              (absent? sites) (missing set-address)
              (verify handle manifest a) {:status :ok, :record a}
              :else (recur more))))))))


(defn- derive-closure
  [ctx registry requirement records manifest derived]
  (let [handle (:store ctx)
        runtime (or (:runtime requirement) '#{php php.sp yang.call})
        candidates (into (vec (get records manifest))
                         (discover (:datoms ctx) (:principals ctx) manifest))
        admitted (stage-admit handle manifest candidates)]
    (if (not= :ok (:status admitted)) admitted
        (let [m (read-at handle manifest)
              deps (sort-by (comp str key)
                            (remove (comp runtime key) (:yin.module/requires m)))
              below (reduce (fn [acc [name address]]
                              (if (contains? (:derived acc) address) acc
                                  (let [r (derive-closure ctx registry requirement records
                                                          address (:derived acc))]
                                    (if (= :ok (:status r))
                                      (assoc acc :derived (:derived r))
                                      (reduced (cond-> r
                                                 (nil? (:name r)) (assoc :name name)
                                                 true (assoc :via manifest)))))))
                            {:derived derived} deps)]
          (if (:status below) below
              (let [tree (load-tree handle (:yin.module/tree m))]
                (cond
                  (= :missing (:status tree)) tree
                  (nil? tree) (stage-refusal :yang.php.antlr/instrumentation-failed manifest)
                  :else
                  (try
                    (let [d (derive-verified handle manifest (:record admitted) tree
                                             (:hooks-profile requirement))
                          ast (when d (u/then (core-module/require-node 'php.sp)
                                              (vm/semantic-bytecode->ast (:tree d))))
                          requires (assoc (into {} (map (fn [[n a]]
                                                          [n (if (contains? runtime n) a
                                                                 (get (:derived below) a a))]))
                                                (:yin.module/requires m))
                                          'php.sp (:php-sp requirement))
                          primitives (when ast
                                       (core-module/free-primitives
                                         :yang.php/error :yang.php/undeclared registry
                                         (set (keys requires)) ast))
                          out (when ast (publish/publish-module!
                                          handle {:name (:yin.module/name m), :ast ast,
                                                  :exports (:yin.module/exports m),
                                                  :requires requires, :primitives primitives}))]
                      (if (not= :ok (:status out))
                        (stage-refusal :yang.php.antlr/instrumentation-failed manifest)
                        (let [record (assoc (:record d) :yin.ledger/output
                                            (:yin.module/tree (:manifest out)))]
                          {:status :ok, :address (:address out),
                           :derivation (jing/materialize! handle record),
                           :derived (assoc (:derived below) manifest (:address out))})))
                    (catch #?(:cljd Object :clj Exception :cljs :default) e
                      (assoc (stage-refusal :yang.php.antlr/instrumentation-failed manifest)
                             :message (ex-message e)))))))))))


(defn stage
  "A write-buffered, bottom-up stage over verified PHP site publications."
  [registry requirement records]
  (let [runtime (or (:runtime requirement) '#{php php.sp yang.call})]
    (fn [ctx name address]
      (cond
        (contains? runtime name) {:status :ok, :address address}
        (:noninterruptible requirement) {:status :ok, :address address}
        (not (:hooks requirement)) (stage-refusal :yang.php.antlr/profile-undeclared address)
        :else
        (let [{:keys [handle flush!]} (overlay (:store ctx))
              result (derive-closure (assoc ctx :store handle) registry requirement
                                     records address {})]
          (if (= :ok (:status result))
            (do (flush!) (select-keys result [:status :address :derivation]))
            result))))))
