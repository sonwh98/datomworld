(ns world.server
  (:require
   [clojure.pprint :as pp :refer :all]
   [mount.core :as mount :refer [defstate]]
   [org.httpkit.server :as httpkit]
   [ring.middleware.params :as params]
   [ring.middleware.keyword-params :as kparams]
   [ring.util.request :as req-util]
   [stigmergy.wocket.server :as ws :refer [process-msg]]
   [taoensso.timbre :as log]))

;;curl "https://beta.datom.world/api/save-location?timestamp=0&lat=1&lon=2&alt=3"
;;curl -X POST -d "timestamp=0&lat=1&lon=2&alt=3" "https://beta.datom.world/api/save-location"

(def uri->handler {"/api/save-location" (fn [req]
                                          ;;(pprint req)
                                          (let [params (:params req)
                                                {:keys [timestamp lon lat alt]} params]
                                            (spit "location.log" (str [timestamp lat lon alt] "\n") :append true)
                                            (ws/broadcast! [:move-me [lon lat alt]])
                                            {:status  200
                                             :headers {"Content-Type" "text/html"}
                                             :body    (str [timestamp lat lon alt])}))
                   "/api/ws" ws/listen-for-client-websocket-connections})
(defn app [req]
  (let [uri (:uri req)
        handler (-> uri uri->handler kparams/wrap-keyword-params params/wrap-params)]
    (if handler
      (handler  req)
      {:status  404
       :headers {"Content-Type" "text/html"}
       :body    (format "'%s' no handler" uri)})))

(defstate server
  :start (httpkit/run-server app {:port 8090})
  :stop (server :timeout 100))


(defmethod process-msg :location [ [client-websocket-channel [msg-tag msg-payload]] ]
    ;;do something with the msg-payload from cljs
    (log/info "msg-payload from cljs " msg-payload)

    ;;send something back to cljs using the core.async channel client-socket-channel
    (ws/send! client-websocket-channel [:location-respond "I hear you"])
)

(comment
  (mount/start)
  (mount/stop)
  (httpkit/run-server app {:port 8090})

  (require '[clojure.pprint :as pp :refer :all]
           '[taoensso.timbre.appenders.core :as appenders])
  (log/merge-config! {:min-level :debug
                      :middleware [(fn [data]
                                     (update data :vargs (partial mapv #(if (string? %)
                                                                          %
                                                                          (with-out-str (pp/pprint %))))))]
                      :appenders {:println {:enabled? false}
                                  :catalog (merge (appenders/spit-appender {:fname (let [log-dir (or (System/getenv "LOG_DIR") ".")]
                                                                                     (str  log-dir "/world.log"))})
                                                  {:min-level :debug
                                                   :level :debug
                                                   :ns-filter {:allow #{"world.core"}}})}})



  (ws/broadcast! [:move-me [1 16.8815912 105.3536929  3]])
  (ws/broadcast! [:location-response [1 108.49399836341877 16.938692193860614 3]])


  )

