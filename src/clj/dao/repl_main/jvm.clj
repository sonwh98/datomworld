(ns dao.repl-main.jvm
  (:require
    [clojure.string :as str]
    [dao.repl :as repl]
    [dao.repl-args :as repl-args]))


(defn- configure-state
  [state opts]
  (let [[state telemetry-msg]
        @(cond
           (:telemetry-stream opts)
           (repl/eval-input state (str "(telemetry " (pr-str (:telemetry-stream opts)) ")"))

           (:telemetry? opts)
           (repl/eval-input state "(telemetry)")

           :else
           (deliver (promise) [state nil]))]
    (when telemetry-msg
      (println telemetry-msg))
    state))


(defn- print-prompt!
  []
  (print "dao> ")
  (flush))


(defn- run-cli!
  [state-atom]
  (loop []
    (when (:running? @state-atom)
      (print-prompt!)
      (when-let [line (read-line)]
        (when-not (str/blank? line)
          (let [[state result] @(repl/eval-input @state-atom line)]
            (reset! state-atom state)
            (when (some? result)
              (println result))))
        (recur)))))


(defn -main
  [& args]
  (let [opts (repl-args/parse-args args)
        state-atom (atom (configure-state (repl/create-state) opts))
        server (when-let [port (:port opts)]
                 (println (str "Dao REPL server listening on ws://localhost:" port))
                 (repl/serve! state-atom port))]
    (try
      (if (:headless? opts)
        (when server
          @(promise))
        (run-cli! state-atom))
      (finally
        (when server
          ((:stop! server)))))
    (.halt (Runtime/getRuntime) 0)))
