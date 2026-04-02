(ns datomworld.ws-client-demo
  "Demo: CLJS client in Node.js calling CLJ functions via WebSocket and dao.stream.apply."
  (:require
    [cljs.core.async :as async :refer [<!]]
    [dao.stream :as ds]
    [dao.stream.apply :as dao-apply]
    [dao.stream.transport.ringbuffer]
    [dao.stream.transport.ws]))


(defn call-remote
  "Send a request and wait for the matching response."
  [stream call-id op args]
  ;; Send request on the same stream
  (dao-apply/put-request! stream call-id op args)
  ;; Wait for matching response
  (async/go-loop [cursor {:position 0}, attempts 0]
    (if (> attempts 500)  ; ~5 second timeout with 10ms polling
      (throw (js/Error. "Response timeout"))
      (let [result (ds/next stream cursor)]
        (cond
          (map? result)
          (let [resp (:ok result)]
            (if (= (dao-apply/response-id resp) call-id)
              (dao-apply/response-value resp)
              (recur (:cursor result) (inc attempts))))

          (= result :blocked)
          (do (<! (async/timeout 10))
              (recur cursor (inc attempts)))

          :else
          (throw (js/Error. (str "Stream error: " result))))))))


(defn -main
  [& args]
  (let [url (or (first args) "ws://localhost:8080")]
    (println (str "Connecting to " url "..."))
    (async/go
      (try
        (let [stream (dao.stream.transport.ws/connect! url)]
          (<! (async/timeout 1000))  ; Give WebSocket time to fully connect

          (println "Connected. Sending requests...")

          ;; Test 1: add
          (println "\n[1] Calling :op/add [5 3]")
          (let [result (<! (call-remote stream :call-1 :op/add [5 3]))]
            (println (str "Result: " result)))

          ;; Test 2: multiply
          (println "\n[2] Calling :op/multiply [6 7]")
          (let [result (<! (call-remote stream :call-2 :op/multiply [6 7]))]
            (println (str "Result: " result)))

          ;; Test 3: uppercase
          (println "\n[3] Calling :op/uppercase [\"hello\"]")
          (let [result (<! (call-remote stream :call-3 :op/uppercase ["hello"]))]
            (println (str "Result: " result)))

          ;; Test 4: non-existent op (error)
          (println "\n[4] Calling non-existent :op/divide [10 2] (should error)")
          (let [result (<! (call-remote stream :call-4 :op/divide [10 2]))]
            (println (str "Result: " result)))

          (println "\nAll tests complete. Closing...")
          (ds/close! stream)
          (<! (async/timeout 500))
          (js/process.exit 0))

        (catch js/Error e
          (println (str "Error: " (.-message e)))
          (println (.-stack e))
          (js/process.exit 1))))))
