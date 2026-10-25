(ns yang.core.publication
  "Language-neutral publication of a set of compiled units as linker modules
   (docs/design/yang.typescript.md §17, lift L3; the Python instance is
   docs/design/yang.antlr.md 8.5.6). A unit set publishes bottom-up: each
   unit's static imports order it after the units it imports, and each is
   published pinning the manifest address of every import. Cyclic components
   are bundled into one unit with lazy member bodies and thin member aliases,
   eliminating circular content pins. Name assertions stay the linker's: this
   namespace publishes content, it signs nothing.

   A frontend supplies a config map:
   `:imported-names`    `(fn [unit])` -> the module names `unit` imports
   `:module-spec`       `(fn [registry unit runtime-address deps])`
   `:scc-module-specs`  `(fn [registry units runtime-address deps])`
   `:site-set`          `(fn [handle address ast])` -> site publication, or nil
                        (Python's I7 mark set; none for a frontend without)
   `:binder-table`      `(fn [handle address ast])` -> table publication, or
                        nil (frontend-supplied; Python passes none)
   `:refusal-ns`        the namespace string of the refusal keywords
   `:publish-fn`        `[handle spec]`, default `publish/publish-module!`."
  (:require
    [yin.vm.linker.publish :as publish]))


(defn- refused
  [reason data]
  (merge {:status :refused, :reason reason} data))


(defn- components
  "Kosaraju's two passes, with immutable search state. Members are sorted;
   singleton components keep the old depth-first publication order."
  [names imports]
  (let [names-set (set names)
        edges (into {} (map (fn [n] [n (filterv names-set (get imports n))])) names)
        reverse-edges (reduce (fn [acc [n deps]]
                                (reduce #(update %1 %2 (fnil conj []) n) acc deps))
                              {} edges)]
    (letfn [(finish
              [acc n]
              (if (contains? (:seen acc) n)
                acc
                (update (reduce finish (update acc :seen conj n) (get edges n))
                        :order conj n)))
            (collect
              [acc n]
              (if (contains? (:seen acc) n)
                acc
                (reduce collect (-> acc (update :seen conj n) (update :members conj n))
                        (get reverse-edges n))))]
      (let [finished (:order (reduce finish {:seen #{}, :order []} names))
            partition (reduce (fn [acc n]
                                (if (contains? (:seen acc) n)
                                  acc
                                  (let [r (collect {:seen (:seen acc), :members []} n)]
                                    {:seen (:seen r),
                                     :groups (conj (:groups acc) (vec (sort (:members r))))})))
                              {:seen #{}, :groups []} (reverse finished))
            group-of (into {} (mapcat (fn [g] (map #(vector % g) g))) (:groups partition))]
        (letfn [(order-group
                  [acc g]
                  (if (contains? (:done acc) g)
                    acc
                    (let [deps (distinct (map group-of (mapcat edges g)))
                          next (reduce order-group (update acc :done conj g)
                                       (remove #{g} deps))]
                      (update next :order conj g))))]
          (:order (reduce order-group {:done #{}, :order []} (map group-of names))))))))


(defn publication-order
  "Dependency-first names, retaining the input order for acyclic units.
   Cyclic components appear as sorted member vectors in :order, one
   publication step per SCC. External imports must be in `published`."
  [{:keys [imported-names refusal-ns]} units published]
  (let [by-name (into {} (map (juxt :name identity)) units)
        imports (into {} (map (juxt :name imported-names)) units)
        missing (some (fn [u]
                        (some (fn [m]
                                (when-not (or (contains? by-name m)
                                              (contains? published m))
                                  {:module m, :imported-by (:name u)}))
                              (get imports (:name u))))
                      units)]
    (if missing
      (refused (keyword refusal-ns "unpublished-import") missing)
      {:status :ok,
       :order (mapv #(if (= 1 (count %)) (first %) %)
                    (components (mapv :name units) imports))})))


(defn publish-units!
  "Publish `units` into the content store `handle` over the host registry
   `registry`, the runtime module pinned at `runtime-address` and each
   import outside the set at its address in `published`, in
   `publication-order`, per the config `cfg` (see the namespace doc). Answers
   `{:status :ok :addresses {name address} :bundles {bundle-symbol address}
     :sites {name-or-bundle-symbol publication}
     :binders {name-or-bundle-symbol publication}}`, or the first refusal:
   `publication-order`'s before anything is written, else the publisher's
   with a string `:unit`. An SCC member alias carries no marks and no
   binder table: its bundle does."
  [cfg handle registry runtime-address units published]
  (let [{:keys [imported-names module-spec scc-module-specs site-set
                binder-table refusal-ns publish-fn]
         :or {publish-fn publish/publish-module!}}
        cfg
        plan (publication-order cfg units published)
        by-name (into {} (map (juxt :name identity)) units)]
    (if (= :refused (:status plan))
      plan
      (reduce
        (fn [acc step]
          (let [members (if (vector? step) step [step])
                known (merge published (:addresses acc))
                deps (select-keys known
                                  (mapcat #(imported-names (get by-name %)) members))
                specs (if (vector? step)
                        (scc-module-specs registry (mapv by-name members)
                                          runtime-address deps)
                        [(module-spec registry (get by-name step)
                                      runtime-address deps)])
                bundle (first specs)
                result (publish-fn handle bundle)
                emit (fn [acc k r]
                       ;; a handle with no byte store (a stub publisher)
                       ;; has nowhere to put a set; a backend failure is
                       ;; a refusal, not a throw
                       (if-not (:put-bytes-fn handle)
                         acc
                         (try (let [emit1 (fn [acc slot f]
                                            (if-let [pub (when f
                                                           (f handle (:address r)
                                                              (:ast bundle)))]
                                              (assoc-in acc [slot k] pub)
                                              acc))]
                                (-> acc
                                    (emit1 :sites site-set)
                                    (emit1 :binders binder-table)))
                              (catch #?(:cljd Object :clj Exception :cljs :default) e
                                (reduced
                                  (refused (keyword refusal-ns "site-set-unpublished")
                                           {:unit k,
                                            :message (ex-message e)}))))))]
            (if (= :refused (:status result))
              (reduced (assoc result :unit (if (vector? step) (str (:name bundle)) step)))
              (if (vector? step)
                ;; a member alias holds no marks: the marks live in the
                ;; bundle, so the bundle alone carries a site set and an
                ;; alias import under a hook profile is refused as having none
                (let [base (-> acc
                               (assoc-in [:bundles (:name bundle)] (:address result))
                               (emit (:name bundle) result))]
                  (if (reduced? base)
                    base
                    (let [aliases (reduce (fn [a [n spec]]
                                            (let [r (publish-fn
                                                      handle (assoc spec :requires
                                                                    {(:name bundle) (:address result)}))]
                                              (if (= :refused (:status r))
                                                (reduced (assoc r :unit n))
                                                (assoc-in a [:addresses n] (:address r)))))
                                          base
                                          (map vector members (rest specs)))]
                      (if (= :refused (:status aliases)) (reduced aliases) aliases))))
                (-> acc
                    (assoc-in [:addresses step] (:address result))
                    (emit step result))))))
        {:status :ok, :addresses {}, :bundles {}}
        (:order plan)))))
