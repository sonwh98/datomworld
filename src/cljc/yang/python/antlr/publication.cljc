(ns yang.python.antlr.publication
  "C4 slice I3 (docs/design/yang.antlr.md 8.5.6): a set of compiled Python
   units published as linker modules, and the import admission a Python
   link source applies until L-e.

   A unit set publishes bottom-up. Each unit's static imports
   (`lower/imported-module-names`, the requires `lower/module-packet`
   hoists) order it after the units it imports, and each is published
   pinning the manifest address of every import. A delivery cycle cannot
   be pinned by content, so a cyclic set is refused before anything is
   written, naming the cycle (ruling 9's first step; SCC units are I5).
   Name assertions stay the linker's (`yin.vm.linker.publish/assertion`
   and `name-envelopes`): this namespace publishes content, it signs
   nothing.

   Foreign principals (ruling 19): until D7 slice B (L-e) verifies closure
   origin and store ownership through every export and dependency slice,
   a module only another principal vouches for could forge a `:store-of`
   reaching `py`'s state. `principal-admission` is the explicit refusal
   a Python link source applies to every name it resolves meanwhile."
  (:require [yang.python.antlr.lower :as lower]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.sign :as sign]))


(defn- refused
  [reason data]
  (merge {:status :refused, :reason reason} data))


(defn- unit-imports
  "The Python modules unit `u` statically imports."
  [{:keys [name package? packet]}]
  (lower/imported-module-names packet
                               {:name name, :package? (boolean package?)}))


(defn- rotate-least
  "The cycle `names` rotated to start at its least name, so one cycle has
   one diagnostic whatever unit the search entered it by."
  [names]
  (let [least (first (sort names))
        i (first (keep-indexed (fn [i n] (when (= least n) i)) names))]
    (vec (concat (drop i names) (take i names)))))


(defn publication-order
  "The names of `units` (`[{:name :package? :packet}]`) in an order that
   publishes every unit after the units of the set it imports, input order
   otherwise; an import outside the set must be in `published`
   ({python-name address}). Answers `{:status :ok :order [...]}`, or a
   refusal: `:yang.python.antlr/import-cycle` with the `:cycle`'s names,
   least first, in import order; `:yang.python.antlr/unpublished-import`
   naming the `:module` no unit and no publication supplies and the unit
   `:imported-by`."
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
      ;; depth first; `path` is the chain being visited, so meeting a name
      ;; on it closes a cycle
      (letfn [(visit
                [acc path n]
                (cond
                  (:cycle acc) acc
                  (some #{n} path) (assoc acc
                                          :cycle
                                          (rotate-least
                                            (drop-while #(not= n %) path)))
                  (contains? (:done acc) n) acc
                  (not (contains? by-name n)) acc
                  :else (let [a (reduce #(visit %1 (conj path n) %2)
                                        acc
                                        (get imports n))]
                          (if (:cycle a)
                            a
                            (-> a
                                (update :done conj n)
                                (update :order conj n))))))]
        (let [r (reduce #(visit %1 [] %2)
                        {:done #{}, :order []}
                        (map :name units))]
          (if (:cycle r)
            (refused :yang.python.antlr/import-cycle {:cycle (:cycle r)})
            {:status :ok, :order (:order r)}))))))


(defn publish-units!
  "Publish `units` (`[{:name :package? :packet}]`) into the content store
   `handle` over the host registry `registry`, `py` pinned at
   `py-address` and each import outside the set at its address in
   `published` ({python-name address}), in `publication-order`. Answers
   `{:status :ok :addresses {python-name address}}` for the units of the
   set, or the first refusal -- `publication-order`'s before anything is
   written, else `publish-module!`'s with the unit's `:unit` name."
  ([handle registry py-address units]
   (publish-units! handle registry py-address units {}))
  ([handle registry py-address units published]
   (let [plan (publication-order units published)
         by-name (into {} (map (juxt :name identity)) units)]
     (if (= :refused (:status plan))
       plan
       (reduce
         (fn [acc n]
           (let [{:keys [packet package?]} (get by-name n)
                 known (merge published (:addresses acc))
                 deps (select-keys known (unit-imports (get by-name n)))
                 res (publish/publish-module!
                       handle
                       (lower/module-spec registry packet
                                          {:name n,
                                           :package? package?,
                                           :py-address py-address,
                                           :deps deps}))]
             (if (= :refused (:status res))
               (reduced (assoc res :unit n))
               (assoc-in acc [:addresses n] (:address res)))))
         {:status :ok, :addresses {}}
         (:order plan))))))


(defn principal-admission
  "The admission a Python link source applies to each name it resolves
   (`yin.repl.link/composition`'s `:admit`), until L-e: a name resolved
   from signed assertions is admitted when one of the importer's own
   principals, the public keys `publics` (64 lowercase hex), asserts it
   -- it vouches for that very manifest address -- and a direct entry,
   the composition's own, always. Any other is refused
   `:yang.python.antlr/foreign-principal` with the `:name`, its
   `:asserters`, and `:requires` `:L-e`, before anything of it links."
  [publics]
  (let [own (set (map sign/principal publics))]
    (fn [name entry]
      (let [provenance (:yin.link/provenance entry)
            asserters (:yin.module/asserted-by provenance)]
        (when-not (or (= :composition provenance) (some own asserters))
          (refused :yang.python.antlr/foreign-principal
                   {:name name,
                    :asserters (vec asserters),
                    :requires :L-e}))))))
