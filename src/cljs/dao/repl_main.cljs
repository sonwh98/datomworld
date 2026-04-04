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
        "--port" (recur (nnext args) (assoc opts :port (js/parseInt (second args) 10)))
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
      (js/console.log telemetry-msg))
    state))


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
             (let [[state result] (repl/eval-input @state-atom line)]
               (reset! state-atom state)
               (when (some? result)
                 (js/console.log result))))
           (if (:running? @state-atom)
             (.prompt rl)
             (.close rl))))
    (.on rl "close" (fn [] (js/process.exit 0)))))


(defn -main
  [& args]
  (let [opts (parse-args args)]
    (when (:port opts)
      (js/console.error "Dao REPL server mode is not supported on Node.js in this build")
      (js/process.exit 1))
    (when (:headless? opts)
      (js/process.exit 0))
    (run-cli! (atom (configure-state (repl/create-state) opts)))))


(set! *main-cli-fn* -main)
