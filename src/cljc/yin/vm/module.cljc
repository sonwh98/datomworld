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
    [dao.jing :as jing]
    [dao.stream :as stream]
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
   that entry's task-local lowered `:bindings` when it holds them -- a
   linked module (yin.vm.linker.md section 8.3) -- and its `:slice`
   otherwise, which for a host module is the host values themselves, so
   a binding name resolves to its export."
  [node path]
  (if (seq path)
    (if (and (map? node) (contains? node :manifest))
      (get-in node (into [(if (contains? node :bindings) :bindings :slice)]
                         path))
      (when-let [child (get node (first path))]
        (walk-path child (rest path))))
    node))


(defn module-entry
  "The registry entry of module `module-name` in `modules` (a registry's
   `:modules` map), or nil when the dotted path reaches no module entry."
  [modules module-name]
  (let [node (reduce (fn [node seg] (when (map? node) (get node seg)))
                     modules
                     (symbol->path module-name))]
    (when (and (map? node) (contains? node :manifest)) node)))


(defn assoc-module
  "Return registry with `entry` installed as module `module-name`."
  [registry module-name entry]
  (assoc-in (or registry (empty-registry))
            (into [:modules] (symbol->path module-name))
            entry))


(defn link-module
  "The `linked` transition's registry update (yin.vm.linker.md sections
   7.3 and 8.3): `registry` gains the module `manifest` names as the
   portable encoding of acts 1 to 3 -- `{:manifest m :address a
   :derivation d :slice {sym encoded} :stores {address snapshot} :cells
   {cell-id cell} :images {identity image}}` -- never lowered values. `a`
   is the manifest's own address; `lifted` is the act-1 lift, `{:slice
   :stores :cells :images}`, the cells being the logical cursor cells its
   references name (r9) and the images the verified origin images a
   receiving task attaches.
   Each receiving task lowers the entry into its own `:bindings`."
  [registry manifest derivation lifted]
  (assoc-module registry
                (:yin.module/name manifest)
                {:manifest manifest,
                 :address (jing/segment-key manifest),
                 :derivation derivation,
                 :slice (:slice lifted),
                 :stores (:stores lifted),
                 :cells (:cells lifted),
                 :images (:images lifted)}))


(defn module-entries
  "Every `[name entry]` a registry value holds, the module name read back
   from each entry's manifest."
  [registry]
  (letfn [(walk
            [node]
            (when (map? node)
              (if (contains? node :manifest)
                [[(:yin.module/name (:manifest node)) node]]
                (mapcat walk (vals node)))))]
    (vec (walk (:modules registry)))))


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

;; =============================================================================
;; `require` lowers to the linker (yin.vm.linker.md section 7)
;; =============================================================================

(defprotocol IModuleKernel
  "What a kernel supplies the install child and the `linked` transition
   (yin.vm.linker.md section 7.3). Every method is a pure function of a
   VM value; the engine owns the phases, the kernel owns its coordinates."

  (link-format
    [vm]
    "`{:format kw :contract s}`: the format this kernel links, and the
     execution contract it runs.")

  (spawn-module
    [vm image opts]
    "A fresh VM of this backend with the verified `image` loaded by the
     ordinary loader, holding nothing of `vm`'s state but its composition
     values (primitives, stream constructor, link pair). `opts` carries
     `:modules` (the child's module view), `:origin`, and `:ancestry`.")

  (image-identity
    [vm image]
    "The identity this kernel's code space names `image` by: H, R, the
     vector's address, or the tree's root row id.")

  (image-holds?
    [vm image segment]
    "True when a closure marker's `:yin.k/segment` falls in `image`.")

  (attach-module
    [vm image]
    "`vm` with `image` attached by the kernel's `attach-image` under its
     own contract; an image already held is not attached twice.")

  (lift-closure
    [vm closure encode]
    "The `:yin.k/closure` marker of `closure` in `vm`'s coordinates,
     captured values encoded by `encode`, with `:yin.k/store-of` when the
     closure carries one. Throws the `:yin.k/non-portable` refusal as
     ex-data when the closure cannot be lifted.")

  (lower-closure
    [vm marker decode]
    "The closure `marker` denotes in `vm`'s coordinates, captured values
     decoded by `decode`, carrying the marker's `:yin.k/store-of`. A
     marker of another binding discipline or format is
     `:binding-mismatch`."))


(def link-request-resource
  "The private resource id of the link request stream (section 6.1): the
   writer the composition supplied. No store instruction reaches it."
  :yin.link/request)


(def link-response-resource
  "The private resource id of the link response stream."
  :yin.link/response)


(defn- installing?
  [state module-name]
  (contains? (:installs state) module-name))


(defn- block-on
  "The handler outcome that parks the continuation as `entry`."
  [state entry]
  {:state (-> state
              (update :wait-set (fnil conj []) entry)
              (assoc :value :yin/blocked
                     :blocked? true
                     :halted? false)),
   :value :yin/blocked,
   :blocked? true})


(defn- mint-link-id
  "The next link id `[origin counter]` of this task (section 7.2, step 3):
   the task's origin tag beside its own engine counter."
  [state]
  (let [counter (or (:id-counter state) 0)]
    [[(or (:origin state) :t0) counter]
     (assoc state :id-counter (inc counter))]))


(defn append-link-request
  "Append `entry`'s retained envelope on the link request stream. `ok`
   moves the entry to the `:link-response` state; `full` keeps it in
   `:link-request`, envelope verbatim, for the next poll; the terminal
   outcomes throw, naming the outcome and the link id."
  [resources entry]
  (let [writer (get resources link-request-resource)
        o (:dao.stream/outcome (stream/append! writer (:envelope entry)))]
    (case o
      :dao.stream/ok (-> entry
                         (dissoc :envelope)
                         (assoc :reason :link-response))
      :dao.stream/full entry
      (throw (ex-info "Link request could not be appended"
                      {:outcome o, :link-id (:link-id entry)})))))


(defn require-handler
  "Resolve `:module/require` (yin.vm.linker.md sections 7.2 and 8.3).

   A module already in the registry value -- a host module, or one linked
   earlier and lowered into this task -- answers now. A module installing
   in this scheduler (section 7.3) is neither linked nor absent: the
   requiring continuation joins that install's waiters as an `:install`
   entry. Anything else is a miss, and a miss never throws for absence:
   it mints a `:dao.stream/newest` cursor on the link response stream,
   THEN builds the `:link-request` wait entry -- cursor before append, so
   a response that lands before the entry is first polled is not skipped
   -- and appends the envelope under the link id `[origin counter]`. On
   `ok` the entry waits in `:link-response`; on `full` it stays in
   `:link-request` and the poll retries it. The entry is the kernel's
   registers plus the link fields: resource ids and plain data, never a
   handle.

   A module already on this task's install ancestry is `:require-cycle`
   naming the chain (section 7.4): the child that asked is refused. A VM
   composed without a link pair cannot link, so its miss fails as v1's
   did, naming the modules it has."
  [state effect {:keys [park-entry-fns]}]
  (let [module-name (:module effect)
        build (get park-entry-fns :module/require)
        registers #(if build (build state effect nil) {})
        resources (:resources state)
        response (get resources link-response-resource)]
    (cond
      (some? (resolve-module (:modules state) module-name))
      {:value module-name, :state state, :blocked? false}

      (some #{module-name} (:ancestry state))
      (throw (ex-info "Require cycle"
                      {:reason :require-cycle,
                       :module module-name,
                       :chain (conj (vec (:ancestry state)) module-name)}))

      (installing? state module-name)
      (block-on state (assoc (registers) :reason :install :name module-name))

      (not (and response (get resources link-request-resource)))
      (throw (ex-info "Module is not in this VM's registry"
                      {:module module-name,
                       :available (vec (list-modules (:modules state)))}))

      :else
      (let [minted (stream/cursor response stream/anchor-newest)
            _ (when-not (= :dao.stream/ok (:dao.stream/outcome minted))
                (throw (ex-info "Link response cursor mint failed"
                                {:outcome (:dao.stream/outcome minted),
                                 :module module-name})))
            [link-id state] (mint-link-id state)
            {:keys [format contract]} (link-format state)
            entry (assoc (registers)
                         :reason :link-request
                         :name module-name
                         :link-id link-id
                         :envelope {:yin.link/id link-id,
                                    :yin.link/name module-name,
                                    :yin.link/format format,
                                    :yin.link/contract contract}
                         :request link-request-resource
                         :response link-response-resource
                         :cursor (:dao.stream/cursor minted))]
        (block-on state (append-link-request resources entry))))))


(defn default-registry
  "A registry with the built-in effect handlers and no modules."
  []
  (-> (empty-registry)
      (register-effect-handler :module/require require-handler)))
