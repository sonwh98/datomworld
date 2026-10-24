(ns yang.python.antlr.publication
  "C4 slices I3 and I5 (docs/design/yang.antlr.md 8.5.6): a set of compiled Python
   units published as linker modules.

   A unit set publishes bottom-up. Each unit's static imports
   (`lower/imported-module-names`, the requires `lower/module-packet`
   hoists) order it after the units it imports, and each is published
   pinning the manifest address of every import. Cyclic components are
   bundled into one unit with lazy member bodies and thin member aliases
   (ruling 9, I5), eliminating circular content pins.
   Name assertions stay the linker's (`yin.vm.linker.publish/assertion`
   and `name-envelopes`): this namespace publishes content, it signs
   nothing."
  (:require [yang.python.antlr.lower :as lower]
            [yang.python.antlr.siteset :as siteset]
            [yin.vm.linker.publish :as publish]))


(defn- refused
  [reason data]
  (merge {:status :refused, :reason reason} data))


(defn- unit-imports
  "The Python modules unit `u` statically imports."
  [{:keys [name package? packet]}]
  (lower/imported-module-names packet
                               {:name name, :package? (boolean package?)}))


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
  "Dependency-first names, retaining the I3 order for acyclic units.
   Cyclic components appear as sorted member vectors in :order, one
   publication step per SCC. External imports must be in `published`."
  [units published]
  (let [by-name (into {} (map (juxt :name identity)) units)
        imports (into {} (map (juxt :name unit-imports)) units)
        missing (some (fn [u]
                        (some (fn [m]
                                (when-not (or (contains? by-name m)
                                              (contains? published m))
                                  {:module m, :imported-by (:name u)}))
                              (get imports (:name u))))
                      units)]
    (if missing
      (refused :yang.python.antlr/unpublished-import missing)
      {:status :ok,
       :order (mapv #(if (= 1 (count %)) (first %) %)
                    (components (mapv :name units) imports))})))


(defn publish-units!
  "Publish `units` (`[{:name :package? :packet}]`) into the content store
   `handle` over the host registry `registry`, `py` pinned at
   `py-address` and each import outside the set at its address in
   `published` ({python-name address}), in `publication-order`. Answers
   `{:status :ok :addresses {python-name address}
     :bundles {bundle-symbol address}
     :sites {python-name-or-bundle-symbol site-publication}}` for the units
   of the set (`siteset/publish!`'s answer for each module published beside
   its code image, I7), or the
   first refusal: `publication-order`'s before anything is written, else
   `publish-module!`'s with a string `:unit`: the Python name for a member,
   or the bundle module name for an SCC bundle. Every bundle name must be
   asserted/bound alongside the member names for aliases to deliver.
   Optional `publish-fn` takes `[handle spec]`, defaulting to
   `publish/publish-module!`, for both bundles and members."
  ([handle registry py-address units]
   (publish-units! handle registry py-address units {}))
  ([handle registry py-address units published]
   (publish-units! handle registry py-address units published publish/publish-module!))
  ([handle registry py-address units published publish-fn]
   (let [plan (publication-order units published)
         by-name (into {} (map (juxt :name identity)) units)]
     (if (= :refused (:status plan))
       plan
       (reduce
         (fn [acc step]
           (let [members (if (vector? step) step [step])
                 known (merge published (:addresses acc))
                 deps (select-keys known
                                   (mapcat #(unit-imports (get by-name %)) members))
                 specs (if (vector? step)
                         (lower/scc-module-specs registry (mapv by-name members)
                                                 py-address deps)
                         [(lower/module-spec registry (:packet (get by-name step))
                                             {:name step,
                                              :package? (:package? (get by-name step)),
                                              :py-address py-address, :deps deps})])
                 bundle (first specs)
                 result (publish-fn handle bundle)
                 sited (fn [acc k spec r]
                         ;; a handle with no byte store (a stub publisher)
                         ;; has nowhere to put a set
                         (cond-> acc
                           (:put-bytes-fn handle)
                           (assoc-in [:sites k]
                                     (siteset/publish! handle (:address r) (:ast spec)))))]
             (if (= :refused (:status result))
               (reduced (assoc result :unit (if (vector? step) (str (:name bundle)) step)))
               (if (vector? step)
                 (let [aliases (reduce (fn [a [n spec]]
                                         (let [r (publish-fn
                                                   handle (assoc spec :requires
                                                                 {(:name bundle) (:address result)}))]
                                           (if (= :refused (:status r))
                                             (reduced (assoc r :unit n))
                                             (-> a
                                                 (assoc-in [:addresses n] (:address r))
                                                 (sited n spec r)))))
                                       (-> acc
                                           (assoc-in [:bundles (:name bundle)] (:address result))
                                           (sited (:name bundle) bundle result))
                                       (map vector members (rest specs)))]
                   (if (= :refused (:status aliases)) (reduced aliases) aliases))
                 (-> acc
                     (assoc-in [:addresses step] (:address result))
                     (sited step bundle result))))))
         {:status :ok, :addresses {}, :bundles {}}
         (:order plan))))))
