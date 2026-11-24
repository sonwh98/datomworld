(ns slow-inventory
  "Inventory JVM test vars without executing them, using the test runner's namespace discovery."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as find]))


(defn -main
  [& [manifest-file quick-file selected-file]]
  (let [selected (when selected-file (set (map symbol (str/split-lines (slurp selected-file)))))
        namespaces (->> (find/find-namespaces-in-dir (io/file "test"))
                        distinct
                        (filter #(str/ends-with? (name %) "-test"))
                        (filter #(or (nil? selected) (selected %)))
                        sort)]
    (when (and selected (not= selected (set namespaces)))
      (throw (ex-info "Selected namespaces missing from JVM inventory discovery"
                      {:missing (sort (set/difference selected (set namespaces)))})))
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
