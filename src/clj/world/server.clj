(ns world.server
  (:require
   [bidi.ring :as bidi] :reload
   [clojure.pprint :as pp :refer :all]
   [org.httpkit.server :as httpkit]
   [ring.middleware.file]
   [ring.middleware.params]
   [ring.middleware.multipart-params]
   [ring.middleware.content-type]
   [stigmergy.chp]
   [stigmergy.config :as c]))

(defn create-app []
  (let [routes (c/config :bidi-routes)
        handler (bidi/make-handler routes)
        mime-types (merge {"chp" "text/html"
                           nil "text/html"}
                          (c/config :mime-types))
        app (-> handler
                (ring.middleware.file/wrap-file "public")
                ring.middleware.params/wrap-params
                ring.middleware.multipart-params/wrap-multipart-params
                (ring.middleware.content-type/wrap-content-type {:mime-types mime-types}))]
    (fn [req]
      (app req))))

(defonce server (atom nil))

(defn start-server []
  (println "Loading configuration in" (System/getProperty "config"))
  (c/reload)
  (let [port (c/config :port)
        s (httpkit/run-server (create-app) {:port port})]
    (reset! server s)
    (println (format "Server running on http://localhost:%s " port))))

(defn -main []
  (start-server))

(comment
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
                                                   :ns-filter {:allow #{"world.core"}}})}}))

