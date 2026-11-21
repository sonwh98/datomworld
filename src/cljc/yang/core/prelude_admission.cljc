(ns yang.core.prelude-admission
  "Constructs declarations/specifications and checks registries; does not publish, link, or execute."
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [yin.vm :as vm]
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




(defn- reject-profile [kind data]
  (throw (ex-info "Prelude profile refused" (assoc data :yang.prelude/refusal kind))))


(defn- portable-data? [x]
  (cond
    (or (nil? x) (boolean? x) (string? x) (keyword? x) (symbol? x)
        (number? x) (char? x)) true
    (record? x) false
    (map? x) (every? (fn [[k v]] (and (portable-data? k) (portable-data? v))) x)
    (or (vector? x) (set? x)) (every? portable-data? x)
    :else false))


(defn- module-definitions
  "Collect definitions in evaluated expressions, including immediately invoked
   lambda bodies, without descending into literals or function values."
  [ast]
  (loop [todo [ast] defs []]
    (if-let [node (peek todo)]
      (let [todo (pop todo)]
        (case (:type node)
          :application
          (let [op (:operator node)
                definition? (and (= :variable (:type op)) (= 'yin/def (:name op)))
                todo (into todo (:operands node))
                todo (conj todo (if (= :lambda (:type op)) (:body op) op))]
            (recur todo (if definition? (conj defs (get-in node [:operands 0 :value])) defs)))
          :if (recur (into todo [(:test node) (:consequent node) (:alternate node)]) defs)
          (recur todo defs)))
      defs)))


(defn- profile-closure [profile store registry]
  (let [required #{:yang.prelude/schema :yang.prelude/id :yang.prelude/revision
                   :yang.prelude/module :yang.prelude/root-address
                   :yang.prelude/components :yang.prelude/host-modules
                   :yang.prelude/max-effects}
        components (:yang.prelude/components profile)]
    (when-not (and (map? profile) (portable-data? profile) (= required (set (keys profile)))
                   (= 1 (:yang.prelude/schema profile))
                   (keyword? (:yang.prelude/id profile))
                   (string? (:yang.prelude/revision profile))
                   (symbol? (:yang.prelude/module profile))
                   (some? (:yang.prelude/root-address profile))
                   (vector? components)
                   (every? #(and (vector? %) (= 2 (count %))
                                 (symbol? (first %)) (some? (second %))) components)
                   (map? (:yang.prelude/host-modules profile))
                   (every? (fn [[host spec]]
                             (and (keyword? host) (map? spec)
                                  (boolean? (:required spec))
                                  (set/subset? (set (keys spec))
                                               #{:required :min-bits :min-digits :min-max-items})
                                  (every? (fn [[k v]]
                                            (or (= k :required)
                                                (and (integer? v) (not (neg? v))))) spec)))
                           (:yang.prelude/host-modules profile))
                   (or (nil? (:yang.prelude/max-effects profile))
                       (set? (:yang.prelude/max-effects profile)))
                   (map? store))
      (reject-profile :schema {}))
    (when-not (= (count components) (count (set (map first components))))
      (reject-profile :duplicate-binding {}))
    (loop [todo [[:enter (:yang.prelude/module profile) (:yang.prelude/root-address profile)]]
           active #{} bindings {} manifests []]
      (if-let [[op name addr] (peek todo)]
        (let [todo (pop todo)]
          (if (= op :leave)
            (recur todo (disj active addr) bindings manifests)
            (do
              (when (contains? active addr)
                (reject-profile :cycle {:address addr}))
              (when (and (contains? bindings name) (not= (get bindings name) addr))
                (reject-profile :duplicate-binding {:module name}))
              (if (contains? bindings name)
                (recur todo active bindings manifests)
                (let [m (get store addr) tree (get store (:yin.module/tree m))]
                  (when-not (map? m) (reject-profile :missing-manifest {:address addr}))
                  (when-not (and (= name (:yin.module/name m))
                                 (map? (:yin.module/requires m))
                                 (set? (:yin.module/exports m))
                                 (every? symbol? (:yin.module/exports m))
                                 (every? symbol? (keys (:yin.module/requires m)))
                                 (every? symbol? (keys (:yin.module/primitives m)))
                                 (map? (:yin.module/primitives m))
                                 (set? (get-in m [:yin.module/footprint :effects]))
                                 (map? tree)
                                 (or (not (contains? tree :root))
                                     (= (:yin.module/tree m) (:root tree))))
                    (reject-profile :artifact {:module name}))
                  (when (and (= addr (:yang.prelude/root-address profile))
                             (not= (into {} components) (:yin.module/requires m)))
                    (reject-profile :root-pins {:module name}))
                  ;; Ambient projection aliases require a descriptor, absent here;
                  ;; validate-descriptor checks those collisions in P3-prime.
                  (let [ast (if (and (:root tree) (:rows tree))
                              (vm/semantic-bytecode->ast tree) tree)
                        defs (module-definitions ast)]
                    (when-not (and (= :application (:type ast))
                                   (= (count defs) (count (set defs))))
                      (reject-profile :export-collision {:module name})))
                  (when (some #(string/starts-with? (clojure.core/name %) "dsg__")
                              (:yin.module/exports m))
                    (reject-profile :provenance-export {:module name}))
                  (when (and (not= addr (get-in registry [:trusted-prelude-modules name]))
                             (some #(contains? (:yin.module/primitives m) %)
                                   ['cell/dsg__new-provenance 'cell/dsg__provenance]))
                    (reject-profile :provenance-primitive {:module name}))
                  (recur (into (conj todo [:leave name addr])
                               (map (fn [[n a]] [:enter n a]) (:yin.module/requires m)))
                         (conj active addr) (assoc bindings name addr) (conj manifests m)))))))
        manifests))))


(defn validate-profile
  "Validate a profile against an immutable map of verified manifests and trees.
   Ambient projections belong to a semantics descriptor, which this API does
   not receive; their aliases must also be checked at descriptor admission.
   Trusted runtime identities are pinned by `:trusted-prelude-modules` in
   the registry, as module symbol to manifest address. Returns the profile,
   or throws a refusal before any linking or execution."
  [profile artifact-store registry]
  (let [manifests (profile-closure profile artifact-store registry)
        effects (reduce set/union #{} (map #(get-in % [:yin.module/footprint :effects]) manifests))
        ceiling (:yang.prelude/max-effects profile)]
    (when (and ceiling (not (set/subset? effects ceiling)))
      (reject-profile :effects {:effects effects :max-effects ceiling}))
    profile))


(defn admit-profile
  "Validate the closure, required host exports, and declared numeric floors."
  [profile artifact-store registry host-limits]
  (validate-profile profile artifact-store registry)
  (let [manifests (profile-closure profile artifact-store registry)
        hosts (:yang.prelude/host-modules profile)
        names (into #{} (comp (mapcat #(keys (:yin.module/primitives %)))
                              (filter #(and (namespace %) (not (vm/profile-of vm/primitives %)))))
                    manifests)]
    (doseq [n names]
      (when-not (true? (get-in hosts [(keyword (namespace n)) :required]))
        (reject-profile :host-module {:name n})))
    (doseq [[host spec] hosts]
      (when-not (and (map? spec) (boolean? (:required spec)))
        (reject-profile :schema {:host host}))
      (when (:required spec)
        (when-not (module/module-entry (:modules registry) (symbol (name host)))
          (reject-profile :host-module {:host host})))
      (doseq [floor [:min-bits :min-digits :min-max-items]
              :when (contains? spec floor)]
        (let [minimum (get spec floor) actual (get-in host-limits [host (keyword (subs (name floor) 4))])]
          (when-not (and (integer? minimum) (not (neg? minimum)) (number? actual) (<= minimum actual))
            (reject-profile :host-limits {:host host :floor floor})))))
    (admit registry names "Prelude host export missing" "yang.prelude")
    registry))
