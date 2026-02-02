(ns world.server
  (:require [:reload]
            [bidi.ring :as bidi]
            [clojure.java.io :as io]
            [clojure.pprint :as pp :refer :all]
            [clojure.string :as str]
            [nrepl.cmdline :as nrepl]
            [org.httpkit.server :as httpkit]
            [ring.middleware.content-type]
            [ring.middleware.file]
            [ring.middleware.multipart-params]
            [ring.middleware.params]
            [ring.util.response :as response]
            [stigmergy.chp]
            [stigmergy.config :as c]
            [world.blog]))


(defn redirect-missing-chp
  [handler]
  (fn [req]
    (let [uri (:uri req)]
      (if (and uri (str/ends-with? uri ".chp"))
        (let [chp-path (io/file (str "public/chp" uri))]
          (if (.exists chp-path)
            (handler req)
            (let [blog-uri (str (subs uri 0 (- (count uri) 4)) ".blog")
                  blog-path (io/file (str "public/chp" blog-uri))]
              (if (.exists blog-path)
                (let [target (if-let [qs (:query-string req)]
                               (str blog-uri "?" qs)
                               blog-uri)]
                  (response/redirect target 302))
                (handler req)))))
        (handler req)))))


(defn create-app
  []
  (let [routes (c/config :bidi-routes)
        handler (bidi/make-handler routes)
        mime-types (merge {"chp" "text/html", nil "text/html"}
                          (c/config :mime-types))
        app (-> handler
                (ring.middleware.file/wrap-file "public")
                ring.middleware.params/wrap-params
                ring.middleware.multipart-params/wrap-multipart-params
                (ring.middleware.content-type/wrap-content-type {:mime-types
                                                                   mime-types}))
        redirecting-app (redirect-missing-chp app)]
    (fn [req] (redirecting-app req))))


(defonce server (atom nil))


(defn start-server
  []
  (println "Loading configuration in" (System/getProperty "config"))
  (c/reload)
  (let [port (c/config :port)
        s (httpkit/run-server (create-app) {:port port})]
    (reset! server s)
    (println (format "Server running on http://localhost:%s " port))
    (when (c/config :nrepl)
      (nrepl/-main "--middleware" "[cider.nrepl/cider-middleware]"))))


(defn -main [] (start-server))


(comment
  (require '[clojure.pprint :as pp :refer :all]
           '[taoensso.timbre.appenders.core :as appenders])
  (log/merge-config!
    {:min-level :debug,
     :middleware
       [(fn [data]
          (update data
                  :vargs
                  (partial mapv
                           #(if (string? %) % (with-out-str (pp/pprint %))))))],
     :appenders {:println {:enabled? false},
                 :catalog (merge (appenders/spit-appender
                                   {:fname (let [log-dir (or (System/getenv
                                                               "LOG_DIR")
                                                             ".")]
                                             (str log-dir "/world.log"))})
                                 {:min-level :debug,
                                  :level :debug,
                                  :ns-filter {:allow #{"world.core"}}})}}))
