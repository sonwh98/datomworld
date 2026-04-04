(ns dao.repl-main
  (:require
    [clojure.string :as str]
    [dao.repl :as repl]))


(defn- parse-args
  [args]
  (loop [args args
         opts {:headless? false
               :port nil
               :telemetry? false
               :telemetry-stream nil}]
    (if-let [arg (first args)]
      (case arg
        "--port" (recur (nnext args) (assoc opts :port (Long/parseLong (second args))))
        "--telemetry" (recur (next args) (assoc opts :telemetry? true))
        "--telemetry-stream" (recur (nnext args) (assoc opts :telemetry-stream (second args)))
        "--headless" (recur (next args) (assoc opts :headless? true))
        (recur (next args) (update opts :extra (fnil conj []) arg)))
      opts)))


(defn- configure-state
  [state opts]
  (let [[state telemetry-msg]
        (cond
          (:telemetry-stream opts)
          (repl/eval-input state (str "(telemetry " (pr-str (:telemetry-stream opts)) ")"))

          (:telemetry? opts)
          (repl/eval-input state "(telemetry)")

          :else
          [state nil])]
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
          (let [[state result] (repl/eval-input @state-atom line)]
            (reset! state-atom state)
            (when (some? result)
              (println result))))
        (recur)))))


(defn -main
  [& args]
  (let [opts (parse-args args)
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
