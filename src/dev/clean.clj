(ns clean
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]))


(defn clean
  [args]
  (let [paths [".cpcache" "target" ".shadow-cljs" "public/js" "lib/cljd-out"
               ".clojuredart"]
        existing-paths (filter #(.exists (io/file %)) paths)]
    (if (seq existing-paths)
      (do (println "Cleaning:" (clojure.string/join ", " existing-paths))
          (apply sh/sh "rm" "-rf" existing-paths)
          (println "Clean complete."))
      (println "Nothing to clean."))))
