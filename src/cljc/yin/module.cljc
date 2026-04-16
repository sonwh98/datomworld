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
    (m/foo 42)")


;; Global module registry
(defonce ^:private module-registry (atom {}))
(defonce ^:private effect-registry (atom {}))


(defn register-module!
  "Register a module with its exported bindings.
   bindings is a map of symbol -> function."
  [module-name bindings]
  (swap! module-registry assoc module-name bindings)
  module-name)


(defn get-module
  "Get a registered module's bindings."
  [module-name]
  (get @module-registry module-name))


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


(defn resolve-symbol
  "Resolve a namespaced symbol to its value.
   E.g., 'stream/make -> the make function from stream module"
  [sym]
  (let [ns-str (namespace sym)
        name-str (name sym)]
    (when ns-str
      (let [module (get-module (symbol ns-str))]
        (get module (symbol name-str))))))


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
