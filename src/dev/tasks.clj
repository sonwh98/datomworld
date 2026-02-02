(ns tasks
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]))


(defn cljstyle-fix
  [_]
  (let [{:keys [exit out err]} (sh/sh "mise" "exec" "--" "cljstyle" "fix")]
    (when-not (str/blank? out) (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (System/exit exit)))


(defn zprint-fix
  [_]
  (let [files (-> (sh/sh "find" "src" "test" "-name" "*.clj*")
                  :out
                  (str/split #"\s+"))
        valid-files (filter seq files)]
    (if (seq valid-files)
      (let [{:keys [exit out err]}
              (apply sh/sh "mise" "exec" "--" "zprint" "-w" valid-files)]
        (when-not (str/blank? out) (println out))
        (when-not (str/blank? err) (binding [*out* *err*] (println err)))
        (System/exit exit))
      (println "No files found to format."))))
