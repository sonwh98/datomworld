(ns yin.test-util
  "Shared test utilities for Yin VM tests.")

(defn make-state
  "Create an initial CESK machine state with given environment."
  [env]
  {:control nil
   :environment env
   :store {}
   :continuation nil
   :value nil})
