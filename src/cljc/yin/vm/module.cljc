(ns yin.vm.module
  "Module system for Yin VM v2.

   The v1 module system kept two `defonce` atoms and mutated them at load
   time. A registry is a value here: an ordinary map carried in VM state and
   supplied by the composition that wires the VM.

     (-> (empty-registry)
         (register-host-module 'my.lib {'foo (fn [x] x)} profiles)
         (register-stream-module))

   Nothing in this namespace runs at load time, and nothing registers itself.
   A composition that wants Yin source to reach `(stream/...)` registers the
   stream module explicitly and supplies `:make-stream` beside it."
  (:require
    [clojure.string :as str]
    [yin.vm :as vm]))


;; =============================================================================
;; Registry values
;; =============================================================================

(defn empty-registry
  "An empty module registry value."
  []
  {:modules {}, :effect-handlers {}})


(defn- symbol->path
  "Split a dotted symbol into a get-in path of symbols.
   'yin.io.file-input-stream → ['yin 'io 'file-input-stream]"
  [sym]
  (mapv symbol (str/split (str sym) #"\.")))


(defn- walk-path
  "Descend a registry's :modules map along path, a sequence of symbols.

   Namespace segments step through plain maps until the path reaches a
   module entry (a map carrying :manifest); the remaining segments read
   that entry's :slice, so a binding name resolves to its export."
  [node path]
  (if (seq path)
    (if (and (map? node) (contains? node :manifest))
      (get-in node (into [:slice] path))
      (when-let [child (get node (first path))]
        (walk-path child (rest path))))
    node))


(defn resolve-module
  "Get a value from a registry value by dotted symbol path.
   (resolve-module r 'io)                    → the 'io registry entry
   (resolve-module r 'io.file-output-stream) → the specific export"
  [registry sym]
  (walk-path (:modules registry) (symbol->path sym)))


(defn register-effect-handler
  "Return registry with handler-fn installed for effect keyword kw.
   Handler: (fn [state effect opts] -> {:state s :value v :blocked? bool})"
  [registry kw handler-fn]
  (assoc-in (or registry (empty-registry)) [:effect-handlers kw] handler-fn))


(defn get-effect-handler
  "Get the handler function for an effect keyword."
  [registry kw]
  (get (:effect-handlers registry) kw))


(defn list-modules
  "List the top-level module names in a registry value."
  [registry]
  (keys (:modules registry)))


;; =============================================================================
;; Host modules
;; =============================================================================
;;
;; A host module is the trusted composition boundary (yin.vm.linker.md
;; section 8.3): the host is where the code already is, so no code is
;; fetched. Registration checks the declaration, not the code -- a host
;; function is opaque, and its profile is the warranty the composition
;; publishes with it and answers for as trusted, reviewed code.

(def ^:private profile-keys
  "The five keys of a UCF 7.5.2 profile record, the shape
   `yin.vm/primitive-profiles` publishes."
  [:yin.k/profile :yin.k/class :yin.k/arities :yin.k/effects
   :yin.k/host-state])


(defn- profile-rule
  "The registration rule a binding's profile breaks, or nil when it is a
   UCF 7.5.2 record of a class a host module may export: `:pure`, or
   `:effectful` with a declared non-empty effect set, and no declared
   host state."
  [profile]
  (cond
    (not (and (map? profile)
              (every? #(contains? profile %) profile-keys)))
    :malformed-profile

    (not= :none (:yin.k/host-state profile))
    :host-state

    :else
    (case (:yin.k/class profile)
      :pure nil
      :effectful (if (seq (:yin.k/effects profile))
                   nil
                   :undeclared-effects)
      :host-class)))


(defn- check-binding!
  "Refuse, as a host assembly defect detected before any operation runs,
   a binding whose profile breaks a registration rule."
  [module-name sym profile]
  (when-let [rule (if (nil? profile)
                    :missing-profile
                    (profile-rule profile))]
    (throw (ex-info "Host module registration refused"
                    {:rule rule, :module module-name, :name sym}))))


(defn register-host-module
  "Return registry with host module `module-name` installed from `fns`
   under `profiles`.

   module-name is a dotted symbol; fns maps each export symbol to the host
   value it publishes; profiles maps each export symbol to its UCF 7.5.2
   profile record in the shape of `yin.vm/primitive-profiles`. Every
   binding must carry a profile, and the profile's class must be `:pure`,
   or `:effectful` with a declared, non-empty `:yin.k/effects` set, with
   `:yin.k/host-state` `:none`. An `:effectful` export returns plain
   effect data for the engine to interpret and performs no IO itself;
   `stream/make` is exactly such a constructor.

   The module is entered as an already-linked manifest with
   `:yin.module/tree` absent, `:yin.module/derivations {}`, and its
   exports listed under `:yin.module/primitives` by profile address; the
   exports themselves are its `:slice`, and its `:address` and
   `:derivation` are nil because no content was fetched or derived: the
   host is the boundary."
  [registry module-name fns profiles]
  (doseq [sym (sort-by str (keys fns))]
    (check-binding! module-name sym (get profiles sym)))
  (assoc-in (or registry (empty-registry))
            (into [:modules] (symbol->path module-name))
            {:manifest {:yin.module/name module-name,
                        :yin.module/derivations {},
                        :yin.module/primitives
                        (into {} (map (fn [[sym _f]]
                                        [sym (:yin.k/profile
                                               (get profiles sym))]))
                              fns)},
             :address nil,
             :derivation nil,
             :slice fns,
             :stores {}}))


;; =============================================================================
;; Effect descriptors
;; =============================================================================

(defn effect?
  "Check if a value is an effect descriptor."
  [x]
  (and (map? x) (contains? x :effect)))


(defn make-effect
  "Create an effect descriptor."
  [effect-type & {:as params}]
  (assoc params :effect effect-type))


;; =============================================================================
;; The `stream` module
;; =============================================================================
;;
;; These are pure data constructors. They touch no stream: Yin source calls
;; them through the module system, they return effect maps, and the engine
;; interprets those. `take!` is deliberately absent: destructive read is one
;; reader's progress and every other reader's data loss, and a v2 `take!` would
;; need the reader-position-in-the-medium the contract retired.

(defn make
  ([] (make nil))
  ([cap] {:effect :stream/make, :capacity cap}))


(defn put!
  [s v]
  {:effect :stream/put, :stream s, :val v})


(defn cursor
  [s]
  {:effect :stream/cursor, :stream s})


(defn next!
  [c]
  {:effect :stream/next, :cursor c})


(defn close!
  [s]
  {:effect :stream/close, :stream s})


(def stream-module
  "The v2 `stream` module definition. Registering it is a composition step."
  {'make make, 'put! put!, 'cursor cursor, 'next! next!, 'close! close!})


(def stream-profiles
  "UCF 7.5.2 profiles for the v2 `stream` module's bindings. Each binding
   is an `:effectful` pure effect constructor: it returns plain effect
   data declaring the one effect kind the engine interprets, and touches
   no stream itself."
  {'make (vm/primitive-profile 'make :effectful [0 1] #{:stream/make}
                               :none)
   'put! (vm/primitive-profile 'put! :effectful [2] #{:stream/put} :none)
   'cursor (vm/primitive-profile 'cursor :effectful [1] #{:stream/cursor}
                                 :none)
   'next! (vm/primitive-profile 'next! :effectful [1] #{:stream/next} :none)
   'close! (vm/primitive-profile 'close! :effectful [1] #{:stream/close}
                                 :none)})


(defn register-stream-module
  "Return registry with the v2 `stream` module registered.
   A composition that calls this must also supply `:make-stream`, or
   `(stream/make)` is unsupported and says so."
  [registry]
  (register-host-module registry 'stream stream-module stream-profiles))


;; =============================================================================
;; Built-in effect handlers
;; =============================================================================

(defn require-handler
  "Resolve `:module/require` against the registry value only.

   v1's clj branch called `clojure.core/require` from inside effect dispatch,
   a host call in the middle of interpretation. It does not survive: a module
   is either in the registry the composition supplied or it is not."
  [state effect _opts]
  (let [module-name (:module effect)]
    (if (some? (resolve-module (:modules state) module-name))
      {:value module-name, :state state, :blocked? false}
      (throw (ex-info "Module is not in this VM's registry"
                      {:module module-name,
                       :available (vec (list-modules (:modules state)))})))))


(defn default-registry
  "A registry with the built-in effect handlers and no modules."
  []
  (-> (empty-registry)
      (register-effect-handler :module/require require-handler)))
