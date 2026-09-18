(ns yin.repl.runner
  "Compile the ClojureDart namespace `yin.repl` and run the resulting Dart
   REPL.  This is the cljd host entry point named by the `:cljd-yin-repl`
   alias.  (v1's `yin.repl.runner` was deleted with v1 `yin.repl`.)"
  (:require [clojure.java.shell :as sh])
  (:import [java.lang ProcessBuilder]))


(def build-alias ":cljd-yin-repl-build")


(def dart-entry "bin/yin_repl_main.dart")


(defn -main
  [& args]
  (println "Compiling ClojureDart namespace...")
  (let [{:keys [exit out err]} (sh/sh "clj" (str "-M" build-alias) "compile")]
    (if (zero? exit)
      (do (println "ClojureDart compilation successful. Starting Dart REPL...")
          (let [pb (ProcessBuilder. (into ["dart" "run" dart-entry] args))
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
