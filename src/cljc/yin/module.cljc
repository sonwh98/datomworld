(ns yin.module
  "Module system for Yin VM.

  Provides a registry for libraries that can be required by client code.
  Libraries export functions that may return effect descriptors, which
  the VM interprets to perform side effects (store access, parking, etc.)

  Usage:
    ;; Register a library
    (register-module! 'my.lib {'foo (fn [x] x)
                               'bar (fn [] {:effect :some-effect})})

    ;; In client code (compiled by Yang)
    (require '[my.lib :as m])
    (m/foo 42)"
  (:require
    [clojure.string :as str]))


;; Global module registry
(defonce ^:private module-registry (atom {}))
(defonce ^:private effect-registry (atom {}))


(defn- symbol->path
  "Split a dotted symbol into a get-in path of symbols.
   'yin.io.file-input-stream → ['yin 'io 'file-input-stream]"
  [sym]
  (mapv symbol (str/split (str sym) #"\.")))


(defn register-module!
  "Register bindings into a module. Merges with any existing bindings.
   module-name is a dotted symbol; bindings is a map of symbol -> function.
   (register-module! 'yin.io {'file-input-stream f}) registers under ['yin 'io]."
  [module-name bindings]
  (swap! module-registry update-in (symbol->path module-name) merge bindings)
  module-name)


(defn resolve-module
  "Get a value from the module registry by dotted symbol path.
   (resolve-module 'io)                      → full 'io bindings map
   (resolve-module 'io.file-output-stream)   → the specific binding"
  [sym]
  (get-in @module-registry (symbol->path sym)))


(defn register-effect-handler!
  "Register a global handler for a specific effect keyword.
   Used for effects like file I/O that the engine doesn't inline.
   Handler: (fn [state effect opts] -> {:state s :value v :blocked? bool})"
  [kw handler-fn]
  (swap! effect-registry assoc kw handler-fn)
  kw)


(defn get-effect-handler
  "Get the handler function for an effect keyword."
  [kw]
  (get @effect-registry kw))


(defn list-modules
  "List all registered module names."
  []
  (keys @module-registry))


(defn clear-modules!
  "Clear all registered modules and effect handlers. Useful for testing."
  []
  (reset! module-registry {})
  (reset! effect-registry {}))


;; ============================================================
;; Effect Descriptors
;; ============================================================

(defn effect?
  "Check if a value is an effect descriptor."
  [x]
  (and (map? x) (contains? x :effect)))


(defn make-effect
  "Create an effect descriptor."
  [effect-type & {:as params}]
  (assoc params :effect effect-type))


;; ============================================================
;; Built-in Effect Handlers
;; ============================================================

#?(:clj
   (register-effect-handler!
     :module/require
     (fn [state effect _opts]
       (clojure.core/require (:module effect))
       {:value (:module effect) :state state :blocked? false}))

   :cljs
   (register-effect-handler!
     :module/require
     (fn [state effect _opts]
       {:value (:module effect) :state state :blocked? false}))

   :cljd
   (register-effect-handler!
     :module/require
     (fn [state effect _opts]
       {:value (:module effect) :state state :blocked? false})))
