(ns yang.core.prelude-admission
  "Constructs declarations/specifications and checks registries; does not publish, link, or execute."
  (:require [yin.vm :as vm]
            [yin.vm.module :as module]))


(defn- refusal
  [refusal-ns kind data]
  (merge {(keyword refusal-ns "refusal") (keyword refusal-ns kind)} data))


(defn host-declaration
  "`[n declaration]` for free name `n`: a primitive by its `vm/primitives`
   profile, a host export `ns/name` by the profile address the registry's
   host module `ns` publishes and its `:callable-effects` effect set; nil
   when neither supplies it."
  [registry n]
  (if-let [profile (vm/profile-of vm/primitives n)]
    [n profile]
    (when-let [address (module/host-export-profile registry n)]
      (let [entry (module/module-entry (:modules registry) (symbol (namespace n)))
            f (get-in entry [:slice (symbol (name n))])]
        [n {:yin.k/profile address,
            :yin.k/effects (get (:callable-effects registry) f #{})}]))))


(defn module-spec
  "`yin.vm.linker.publish/publish-module!`'s spec for module `module-name`.
   `skip?` (n -> bool) marks free names a requirement already declares;
   `requires` is the requirement map. A free name nothing supplies is
   refused with `message` under `refusal-ns` before anything is published.
   This namespace emits; it never publishes."
  [{:keys [module-name ast exports requires free-names skip? registry message
           refusal-ns]}]
  {:name module-name,
   :ast ast,
   :exports exports,
   :requires requires,
   :primitives
   (into {}
         (keep (fn [n]
                 (when-not (and skip? (skip? n))
                   (or (host-declaration registry n)
                       (throw (ex-info message
                                       (refusal refusal-ns "undeclared-free"
                                                {:name n})))))))
         free-names)})


(defn admit
  "Return `registry` when it supplies every one of `host-names`, else refuse
   it before any program runs, naming each missing export in
   `:<refusal-ns>/missing`."
  [registry host-names message refusal-ns]
  (let [missing (vec (sort-by str
                              (remove #(some? (module/resolve-module
                                                registry
                                                (symbol (str (namespace %)
                                                             "."
                                                             (name %)))))
                                      host-names)))]
    (when (seq missing)
      (throw (ex-info message
                      (refusal refusal-ns "host-names"
                               {(keyword refusal-ns "missing") missing}))))
    registry))


