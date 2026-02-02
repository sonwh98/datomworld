(ns tasks
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]))


(defn- run-cljstyle
  []
  (let [{:keys [exit out err]} (sh/sh "mise" "exec" "--" "cljstyle" "fix")]
    (if (not= 0 exit)
      (do (binding [*out* *err*]
            (println "cljstyle error:")
            (println err)
            (println out))
          exit)
      0)))


(defn- run-zprint
  []
  (let [files (-> (sh/sh "find" "src" "test" "-name" "*.clj*")
                  :out
                  (str/split #"\s+"))
        valid-files (filter seq files)]
    (if (seq valid-files)
      (let [{:keys [exit out err]}
              (apply sh/sh "mise" "exec" "--" "zprint" "-w" valid-files)]
        (if (not= 0 exit)
          (do (binding [*out* *err*]
                (println "zprint error:")
                (println err)
                (println out))
              exit)
          0))
      0)))


(defn cljstyle-fix [_] (System/exit (run-cljstyle)))


(defn zprint-fix [_] (System/exit (run-zprint)))


(defn fix-all
  [_]
  (let [exit1 (run-cljstyle)
        exit2 (run-zprint)]
    (System/exit (if (or (not= 0 exit1) (not= 0 exit2)) 1 0))))
