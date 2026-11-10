(ns yin.shell.registry
  "The neutral projection of a VM module registry to the linked names it
   holds (docs/design/yin.repl.dht-composition.md section 6).

   A linked module's entry carries its manifest's address and a host
   module's carries none, so `{name manifest-address}` of a registry is
   pure data over `yin.vm.module` and belongs to no storage backend.  It
   lives here, apart from any composition, because the two sides that
   need it — a composition's signed-name service and the composition
   module that moves a followed head — must not reach it through each
   other."
  (:require [yin.vm.module :as module]))


(defn linked-registry
  "`{name manifest-address}` of the modules a VM module `registry` holds
   linked through the linker: a linked entry carries its manifest's
   address, a host module none."
  [registry]
  (into {}
        (keep (fn [[n e]] (when (some? (:address e)) [n (:address e)])))
        (module/module-entries registry)))


(defn host-module-names
  "The set of names of `registry`'s host modules: the entries that carry
   no manifest address, because a host module is a registration and not a
   linked image."
  [registry]
  (set (keep (fn [[n e]] (when (nil? (:address e)) n))
             (module/module-entries registry))))


(defn session-modules
  "`[linked hosts]` of the requesting VM's module `registry`: the modules
   it linked through the linker, and the names of its host modules.  The
   pair a signed-name publication declares beside the code it publishes."
  [registry]
  [(linked-registry registry) (host-module-names registry)])
