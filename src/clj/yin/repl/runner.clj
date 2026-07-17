(ns yin.repl.runner
  (:require [clojure.java.shell :as sh])
  (:import [java.lang ProcessBuilder]))


(defn -main
  [& args]
  (println "Compiling ClojureDart namespace...")
  (let [{:keys [exit out err]} (sh/sh "clj" "-M:cljd-yin-repl-build" "compile")]
    (if (zero? exit)
      (do (println "ClojureDart compilation successful. Starting Dart REPL...")
          (let [pb (ProcessBuilder.
                     (into ["dart" "run" "bin/yin_repl_main.dart"] args))
                _ (.inheritIO pb)
                process (.start pb)]
            (.addShutdownHook
              (Runtime/getRuntime)
              (Thread. (fn [] (when (.isAlive process) (.destroy process)))))
            (System/exit (.waitFor process))))
      (do (binding [*out* *err*]
            (println "ClojureDart compilation failed:")
            (println err)
            (println out))
          (System/exit exit)))))
