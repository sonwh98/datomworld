(ns dao.repl-main.node
  (:require
    [clojure.string :as str]
    [dao.repl :as repl]
    [dao.repl-args :as repl-args]))


(defn- configure-state
  [state opts]
  (let [p (cond
            (:telemetry-stream opts)
            (repl/eval-input state (str "(telemetry " (pr-str (:telemetry-stream opts)) ")"))

            (:telemetry? opts)
            (repl/eval-input state "(telemetry)")

            :else
            (js/Promise.resolve [state nil]))]
    (.then p (fn [[state telemetry-msg]]
               (when telemetry-msg
                 (js/console.log telemetry-msg))
               state))))


(defn- run-cli!
  [state-atom]
  (let [readline (js/require "readline")
        rl (.createInterface readline
                             #js {:input (.-stdin js/process)
                                  :output (.-stdout js/process)
                                  :prompt "dao> "})]
    (.prompt rl)
    (.on rl
         "line"
         (fn [line]
           (when-not (str/blank? line)
             (-> (repl/eval-input @state-atom line)
                 (.then (fn [[state result]]
                          (reset! state-atom state)
                          (when (some? result)
                            (js/console.log result))
                          (if (:running? @state-atom)
                            (.prompt rl)
                            (.close rl))))))))
    (.on rl "close" (fn [] (js/process.exit 0)))))


(defn -main
  [& args]
  (let [opts (repl-args/parse-args args)]
    (-> (configure-state (repl/create-state) opts)
        (.then (fn [state]
                 (let [state-atom (atom state)
                       server (when-let [port (:port opts)]
                                (js/console.log (str "Dao REPL server listening on ws://localhost:" port))
                                (repl/serve! state-atom port))]
                   (when (and (:headless? opts) (not server))
                     (js/console.error "Error: --headless requires --port")
                     (js/process.exit 1))
                   (when-not (:headless? opts)
                     (run-cli! state-atom))))))))
