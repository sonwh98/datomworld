(ns yin.vm.telemetry-server.node
  (:require
    [dao.repl :as repl]
    [dao.stream.transport.ws :as ws]))


(defn -main
  [& _]
  (let [telemetry-stream (ws/listen! 8091)
        state-atom       (atom (repl/create-state {:telemetry-stream telemetry-stream}))
        _                (repl/serve! state-atom 8090)]
    (js/console.log "Telemetry server on :8091, REPL on :8090")))
