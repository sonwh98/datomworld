(ns yin.vm.v2.module
  "Module system for Yin VM v2.

   The v1 module system kept two `defonce` atoms and mutated them at load
   time. A registry is a value here: an ordinary map carried in VM state and
   supplied by the composition that wires the VM.

     (-> (empty-registry)
         (register-module 'my.lib {'foo (fn [x] x)})
         (register-stream-module))

   Nothing in this namespace runs at load time, and nothing registers itself.
   A composition that wants Yin source to reach `(stream/...)` registers the
   stream module explicitly and supplies `:make-stream` beside it."
  (:require
    [clojure.string :as str]))


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


(defn register-module
  "Return registry with bindings merged into module-name.
   module-name is a dotted symbol; bindings is a map of symbol -> function."
  [registry module-name bindings]
  (update-in (or registry (empty-registry))
             (into [:modules] (symbol->path module-name))
             merge
             bindings))


(defn resolve-module
  "Get a value from a registry value by dotted symbol path.
   (resolve-module r 'io)                    → full 'io bindings map
   (resolve-module r 'io.file-output-stream) → the specific binding"
  [registry sym]
  (get-in (:modules registry) (symbol->path sym)))


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
;; interprets those. `take!` is deliberately absent — destructive read is one
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


(defn register-stream-module
  "Return registry with the v2 `stream` module registered.
   A composition that calls this must also supply `:make-stream`, or
   `(stream/make)` is unsupported and says so."
  [registry]
  (register-module registry 'stream stream-module))


;; =============================================================================
;; Built-in effect handlers
;; =============================================================================

(defn require-handler
  "Resolve `:module/require` against the registry value only.

   v1's clj branch called `clojure.core/require` from inside effect dispatch —
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
