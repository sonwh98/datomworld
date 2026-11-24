(ns slow-inventory
  "Inventory JVM test vars without executing them, using the test runner's namespace discovery."
  (:require [clojure.java.io :as io]
            [clojure.tools.namespace.find :as find]))

(defn -main
  [& [manifest-file quick-file]]
  (let [namespaces (sort (distinct (find/find-namespaces-in-dir (io/file "test"))))]
    (doseq [n namespaces] (require n))
    (let [tests (for [n namespaces
                      [_ v] (ns-publics n)
                      :let [m (meta v)]
                      :when (:test m)]
                  [(symbol (str n) (str (:name m))) (boolean (:slow m))])
          slow (frequencies (map (comp symbol namespace first) (filter second tests)))
          quick (sort (map first (remove second tests)))]
      (spit manifest-file (pr-str (into (sorted-map) slow)))
      (when quick-file
        (spit quick-file (apply str (map #(str % "\n") quick)))))))
