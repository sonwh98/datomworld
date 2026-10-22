(ns check-node-discovery
  "Babashka-only preflight for shadow-cljs Node test namespace discovery.
   Reads configuration and source forms without loading source namespaces."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as readers]))


(defn cljs-namespace
  "Return the first ns form's name as seen by CLJS, including conditional ns
   forms and metadata. Stop at ns so later implementation forms are not read."
  [file]
  (with-open [input (io/reader (str file))]
    (let [input (readers/indexing-push-back-reader input)]
      (binding [*read-eval* false]
        (loop []
          (let [form (reader/read {:read-cond :allow :features #{:cljs}
                                   :eof ::eof} input)]
            (cond
              (= ::eof form) nil
              (and (seq? form) (= 'ns (first form)))
              (let [ns-name (second form)]
                (if (symbol? ns-name)
                  (str ns-name)
                  (throw (ex-info (str "Invalid ns name in " file) {}))))
              :else (recur))))))))


(defn check!
  "Check deps.edn :paths plus :cljs :extra-paths, excluding the test/ subtree.
   Missing classpath roots (for example generated sources) are harmless."
  [root]
  (let [deps (edn/read-string (slurp (str (fs/path root "deps.edn"))))
        shadow (edn/read-string (slurp (str (fs/path root "shadow-cljs.edn"))))
        ns-regexp (get-in shadow [:builds :test :ns-regexp] "-test$")
        pattern (re-pattern ns-regexp)
        test-root (fs/canonicalize (fs/path root "test"))
        paths (concat (:paths deps) (get-in deps [:aliases :cljs :extra-paths]))
        files (->> paths
                   (map #(fs/path root %))
                   (filter fs/directory?)
                   (mapcat #(file-seq (io/file (str %))))
                   (filter #(and (fs/regular-file? %)
                                 (#{"cljc" "cljs"} (fs/extension %))
                                 (not (fs/starts-with? (fs/canonicalize %) test-root))))
                   (map fs/canonicalize)
                   distinct
                   (sort-by str))
        violations (into []
                         (keep (fn [file]
                                 (when-let [ns-name (cljs-namespace file)]
                                   (when (re-find pattern ns-name)
                                     {:file (str (fs/relativize (fs/canonicalize root) file))
                                      :ns ns-name}))))
                         files)]
    (when (seq violations)
      (throw (ex-info
               (str/join "\n"
                         (concat
                           [(str "Node discovery FAILED (ns-regexp " (pr-str ns-regexp) "):")]
                           (map #(str "  " (:file %) " -- " (:ns %)) violations)
                           ["shadow-cljs :node-test auto-discovers matching namespaces across the :cljs classpath, outside test/ too; JVM-only requires can break the Node lane."
                            "Fix: rename the namespace so it does not end in -test (or otherwise match the configured ns-regexp), or guard the ns with reader conditionals so it is absent for :cljs."]))
               {:violations violations})))
    {:files (count files) :ns-regexp ns-regexp}))


(defn -main
  [& _args]
  (try
    (let [{:keys [files ns-regexp]} (check! ".")]
      (println "Node discovery OK:" files "source files outside test/; ns-regexp" (pr-str ns-regexp)))
    (catch Exception e
      (binding [*out* *err*]
        (println (ex-message e)))
      (System/exit 1))))


(when (= *file* (System/getProperty "babashka.file"))
  (-main))
