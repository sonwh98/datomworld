(ns yang.php.antlr.publication
  "P6 (docs/design/yang.php.md section 11): a set of compiled PHP units
   published as linker modules. The ordering, SCC bundling and publish loop
   are `yang.core.publication`'s; this namespace supplies the PHP hooks: the
   includes `lower/imported-unit-names` folds, the `lower/module-spec` and
   `lower/scc-module-specs` emitters, no site set and no binder table (the
   PHP hook profile is P7).

   A unit is `{:name path :packet p :strict-types b}`, `path` its normalized
   relative path. Name assertions stay the linker's: this namespace publishes
   content, it signs nothing."
  (:require
    [yang.core.publication :as core]
    [yang.php.antlr.lower :as lower]
    [yin.vm.linker.publish :as publish]))


(defn- config
  [publish-fn]
  {:imported-names (fn [{:keys [name packet]}]
                     (lower/imported-unit-names packet {:path name})),
   :module-spec (fn [registry {:keys [name packet strict-types]} php-address deps]
                  (lower/module-spec registry packet
                                     {:path name,
                                      :strict-types strict-types,
                                      :php-address php-address,
                                      :deps deps})),
   :scc-module-specs lower/scc-module-specs,
   :site-set nil,
   :binder-table nil,
   :refusal-ns "yang.php",
   :publish-fn publish-fn})


(defn publication-order
  "Dependency-first names, retaining the input order for acyclic units.
   Cyclic components appear as sorted member vectors in :order, one
   publication step per SCC. An include edge no unit and no `published`
   entry supplies is refused `:yang.php/unpublished-import` by name."
  [units published]
  (core/publication-order (config publish/publish-module!) units published))


(defn publish-units!
  "Publish `units` into the content store `handle` over the host registry
   `registry`, `php` pinned at `php-address` and each include outside the
   set at its address in `published` ({path address}), in
   `publication-order`. Answers `{:status :ok :addresses {path address}
   :bundles {bundle-symbol address}}`, or the first refusal:
   `publication-order`'s before anything is written, else
   `publish-module!`'s with a string `:unit`."
  ([handle registry php-address units]
   (publish-units! handle registry php-address units {}))
  ([handle registry php-address units published]
   (publish-units! handle registry php-address units published publish/publish-module!))
  ([handle registry php-address units published publish-fn]
   (core/publish-units! (config publish-fn) handle registry php-address units published)))
