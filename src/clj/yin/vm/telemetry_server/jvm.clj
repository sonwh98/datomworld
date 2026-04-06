(ns yin.vm.telemetry-server.jvm
  (:require
    [dao.repl :as repl]
    [dao.stream :as ds]
    [dao.stream.transport.ws :as ws]))


(defn -main
  [& args]
  (let [telemetry-stream (ws/listen! 8091)
        state-atom       (atom (repl/create-state {:telemetry-stream telemetry-stream}))
        repl-server      (repl/serve! state-atom 8090)]
    (println "JVM Telemetry server on :8091, REPL on :8090")
    ;; Wait forever to keep the process alive
    @(promise)))
