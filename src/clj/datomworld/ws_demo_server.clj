(ns datomworld.ws-demo-server
  "Demo: CLJ WebSocket server handling dao.stream.apply requests from CLJS clients."
  (:require
    [clojure.string]
    [dao.stream :as ds]
    [dao.stream.apply :as dao-apply]
    [dao.stream.ringbuffer]
    [dao.stream.ws]))


(defn start-server!
  [port]
  (let [handlers {:op/add +
                  :op/multiply *
                  :op/uppercase clojure.string/upper-case}
        endpoint (ds/open! {:transport {:type :websocket, :mode :listen, :port port}})
        stop-atom (atom false)]

    (println (str "WebSocket server listening on ws://localhost:" port))
    (println "Handlers: :op/add :op/multiply :op/uppercase")

    (future
      (loop [cursor {:position 0}]
        (if @stop-atom
          (do (ds/close! endpoint)
              (println "Server stopped"))
          (do
            (Thread/sleep 10)
            (if-let [req-result (dao-apply/next-request endpoint cursor)]
              (if (map? req-result)
                (let [request (:ok req-result)
                      id (dao-apply/request-id request)
                      op (dao-apply/request-op request)
                      args (dao-apply/request-args request)]
                  (try
                    (let [response (dao-apply/dispatch-request handlers request)]
                      (println (str "Handled " op " " args " → " (dao-apply/response-value response)))
                      (dao-apply/put-response! endpoint response))
                    (catch Exception e
                      (println (str "Error handling " op ": " (ex-message e)))
                      (dao-apply/put-response! endpoint
                                               (dao-apply/response id {:error (ex-message e)}))))
                  (recur (:cursor req-result)))
                (recur cursor))
              (recur cursor)))))

      (fn [] (reset! stop-atom true)))))


(defn -main
  [& args]
  (let [port (Integer/parseInt (or (first args) "8080"))]
    (start-server! port)
    @(promise)))
