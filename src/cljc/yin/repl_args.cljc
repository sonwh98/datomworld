(ns yin.repl-args)


(defn parse-args
  [args]
  (loop [args (seq args)
         opts {:headless? false
               :port nil
               :telemetry? false
               :telemetry-stream nil}]
    (if-let [arg (first args)]
      (case arg
        "--port"
        (let [port-str (second args)]
          (recur (nnext args)
                 (assoc opts :port (if port-str
                                     #?(:clj (Long/parseLong port-str)
                                        :cljs (js/parseInt port-str 10)
                                        :cljd (int/parse port-str))
                                     nil))))

        "--telemetry"
        (recur (next args) (assoc opts :telemetry? true))

        "--telemetry-stream"
        (recur (nnext args) (assoc opts :telemetry-stream (second args)))

        "--headless"
        (recur (next args) (assoc opts :headless? true))

        (recur (next args) (update opts :extra (fnil conj []) arg)))
      opts)))
