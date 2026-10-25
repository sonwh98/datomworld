(ns yang.python.antlr.publication
  "C4 slices I3 and I5 (docs/design/yang.antlr.md 8.5.6): a set of compiled Python
   units published as linker modules. The ordering, SCC bundling and publish
   loop are `yang.core.publication`'s; this namespace supplies the Python
   hooks: the imports `lower/imported-module-names` hoists, the
   `lower/module-spec` and `lower/scc-module-specs` emitters, the I7 site
   set, and no binder table.
   Name assertions stay the linker's (`yin.vm.linker.publish/assertion`
   and `name-envelopes`): this namespace publishes content, it signs
   nothing."
  (:require [yang.core.publication :as core]
            [yang.python.antlr.lower :as lower]
            [yang.python.antlr.siteset :as siteset]
            [yin.vm.linker.publish :as publish]))


(defn- unit-imports
  "The Python modules unit `u` statically imports."
  [{:keys [name package? packet]}]
  (lower/imported-module-names packet
                               {:name name, :package? (boolean package?)}))


(defn- config
  [publish-fn]
  {:imported-names unit-imports,
   :module-spec (fn [registry {:keys [name package? packet]} py-address deps]
                  (lower/module-spec registry packet
                                     {:name name, :package? package?,
                                      :py-address py-address, :deps deps})),
   :scc-module-specs lower/scc-module-specs,
   :site-set siteset/publish!,
   :binder-table nil,
   :refusal-ns "yang.python.antlr",
   :publish-fn publish-fn})


(defn publication-order
  "Dependency-first names, retaining the I3 order for acyclic units.
   Cyclic components appear as sorted member vectors in :order, one
   publication step per SCC. External imports must be in `published`."
  [units published]
  (core/publication-order (config publish/publish-module!) units published))


(defn publish-units!
  "Publish `units` (`[{:name :package? :packet}]`) into the content store
   `handle` over the host registry `registry`, `py` pinned at
   `py-address` and each import outside the set at its address in
   `published` ({python-name address}), in `publication-order`. Answers
   `{:status :ok :addresses {python-name address}
     :bundles {bundle-symbol address}
     :sites {python-name-or-bundle-symbol site-publication}}` for the units
   of the set (`siteset/publish!`'s answer for each module published beside
   its code image, I7; an SCC member alias carries no marks and so no
   entry, its bundle does). The publisher that asserts these names
   indexes `siteset/signed-entries` of `:sites` in the same index
   transaction; a module whose entry is missing is refused by every
   hook-profile reader. Or the
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
   (core/publish-units! (config publish-fn) handle registry py-address units published)))
