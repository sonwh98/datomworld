(ns tasks.cljs
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [shadow.cljs.devtools.api :as shadow]
    [shadow.cljs.devtools.cli :as shadow-cli]))


(def ^:private test-output "target/node-tests.js")


(defn -main
  [& args]
  (if (= "test" (first args))
    (try
      ;; Clear stale output so a compile that fails without throwing can't
      ;; leave us running last build's tests and reporting a false pass.
      (io/delete-file test-output true)
      (shadow/compile :test)
      (when-not (.exists (io/file test-output))
        (throw (ex-info (str "shadow compile produced no " test-output) {})))
      (let [{:keys [exit out err]} (sh/sh "node" test-output)]
        (print out)
        (binding [*out* *err*] (print err))
        (flush)
        (System/exit exit))
      (catch Exception e
        (println "Compilation failed:" (.getMessage e))
        (System/exit 1)))
    (apply shadow-cli/-main args)))
