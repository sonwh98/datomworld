(ns tasks
  (:require
    [clojure.java.shell :as sh]))


(defn fix-all
  [_]
  (let [{:keys [exit out err]} (sh/sh "mise" "exec" "--" "cljstyle" "fix")]
    (when (not= 0 exit)
      (binding [*out* *err*]
        (println "cljstyle error:")
        (println err)
        (println out)))
    (System/exit exit)))
