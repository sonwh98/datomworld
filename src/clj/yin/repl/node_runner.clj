(ns yin.repl.node-runner
  "Compile the shadow-cljs build `:yin-repl` and run the resulting Node
   REPL.  This is the cljs host entry point named by the `:cljs-yin-repl`
   alias — the alias's earlier main-opts ran `shadow.cljs.devtools.cli
   run yin.repl.main/-main`, which booted the JVM main, not a Node REPL;
   a deps alias can only supply a JVM classpath, so the Node exec has to
   happen from a JVM-side driver, the same shape as `yin.repl.runner` for
   Dart."
  (:require [clojure.java.shell :as sh])
  (:import [java.lang ProcessBuilder]
           [java.nio.file Files Path]))


(def build-cmd ["clj" "-M:cljs" "-m" "shadow.cljs.devtools.cli" "compile" "yin-repl"])

(def node-script "target/yin-repl.js")


(defn built?
  "The compiled Node REPL and the generated JavaScript parser it loads."
  []
  (and (Files/exists (Path/of node-script (into-array String []))
                     (into-array java.nio.file.LinkOption []))
       (->> (java.io.File. "build/antlr/python3/js")
            (file-seq)
            (some #(and (.isFile %)
                        (.endsWith (.getName %) ".js"))))))


(defn build
  []
  (println "Generating the JavaScript parser...")
  (let [{:keys [exit err out]} (sh/sh "clj" "-M:antlr-gen")]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (println "Parser generation failed:")
        (println err)
        (println out))
      (System/exit (if (pos? exit) exit 1))))
  (println "Compiling the Node REPL (shadow-cljs build :yin-repl)...")
  (let [{:keys [exit err out]} (apply sh/sh build-cmd)]
    (if (zero? exit)
      (println "Node REPL compiled. Starting Node...")
      (do (binding [*out* *err*]
            (println "Node REPL compilation failed:")
            (println err)
            (println out))
          (System/exit exit)))))


(defn -main
  [& args]
  (when-not (built?)
    (build))
  (let [pb (ProcessBuilder. (into ["node" node-script] args))
        _ (.inheritIO pb)
        process (.start pb)]
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread. (fn [] (when (.isAlive process) (.destroy process)))))
    (System/exit (.waitFor process))))
