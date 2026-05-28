(ns dao.stream.http-test
  (:require
    #?@(:cljd [["dart:io" :as io] ["dart:async" :as async]]
        :cljs []
        :clj [[org.httpkit.server :as http-server]])
    [clojure.test :refer [deftest is testing #?(:cljs async)]]
    [dao.stream :as ds]
    [dao.stream.http]))


(deftest jvm-http-uniformity-test
  #?(:clj (let [port 18999
                url (str "http://localhost:" port)
                stop-server (http-server/run-server
                              (fn [req]
                                (case (:uri req)
                                  "/slow" (do (Thread/sleep 500)
                                              {:status 200, :body "finally"})
                                  {:status 200,
                                   :headers {"X-Echo-Method"
                                             (name (:request-method req)),
                                             "Content-Type" "text/plain"},
                                   :body (when-let [b (:body req)] (slurp b))}))
                              {:port port})]
            (try
              (testing "GET request"
                (let [stream (ds/open! {:type :http, :url url, :method :get})
                      resp (ds/take!! stream)]
                  (is (= 200 (:status resp)) (pr-str resp))
                  (is (= "get" (get (:headers resp) "x-echo-method")))))
              (testing "POST request with body"
                (let [stream
                      (ds/open!
                        {:type :http, :url url, :method :post, :body "hello"})
                      resp (ds/take!! stream)]
                  (is (= 200 (:status resp)) (pr-str resp))
                  (is (= "hello" (:body resp)))
                  (is (= "post" (get (:headers resp) "x-echo-method")))))
              (testing "Header normalization (lowercase)"
                (let [stream (ds/open! {:type :http, :url url, :method :get})
                      resp (ds/take!! stream)]
                  (is (contains? (:headers resp) "content-type")
                      (pr-str (:headers resp)))
                  (is (not (contains? (:headers resp) "Content-Type")))))
              (testing "Timeout handling"
                (let [stream (ds/open! {:type :http,
                                        :url (str url "/slow"),
                                        :timeout 100})
                      resp (ds/take!! stream)]
                  (is (= :timeout (get-in resp [:error :kind])) (pr-str resp))))
              (finally (stop-server))))))


#?(:cljs (defn- start-node-server!
           [port]
           (let [http (js/require "http")
                 server (.createServer
                          http
                          (fn [req res]
                            (if (= (.-url req) "/redirect")
                              (do (.writeHead res 302 #js {"Location" "/"})
                                  (.end res))
                              (do (.writeHead res
                                              200
                                              #js {"Content-Type" "text/plain",
                                                   "X-Echo-Method" (.-method
                                                                     req)})
                                  (.end res "hello")))))]
             (.listen server port)
             server)))


(deftest cljs-http-async-test
  #?(:cljs (async
             done
             (let [port 18998
                   server (start-node-server! port)
                   url (str "http://localhost:" port)
                   stream (ds/open! {:type :http, :url url, :method :get})]
               (letfn
                 [(check
                    [cursor]
                    (let [res (ds/next stream cursor)]
                      (cond (map? res)
                            (let [resp (:ok res)]
                              (is (= 200 (:status resp)) (pr-str resp))
                              (is (= "GET"
                                     (get (:headers resp) "x-echo-method")))
                              (is (= "hello" (:body resp)))
                              (.close server done))
                            (= :end res)
                            (do (is false "Stream closed without response")
                                (.close server done))
                            :else (js/setTimeout #(check cursor) 10))))]
                 (check {:position 0}))))))


(deftest cljs-follow-redirects-test
  #?(:cljs (async
             done
             (let [port 18996
                   server (start-node-server! port)
                   url (str "http://localhost:" port "/redirect")
                   stream (ds/open! {:type :http,
                                     :url url,
                                     :method :get,
                                     :follow-redirects false})]
               (letfn
                 [(check
                    [cursor]
                    (let [res (ds/next stream cursor)]
                      (cond (map? res)
                            (let [resp (:ok res)]
                              (is (= 302 (:status resp)) (pr-str resp))
                              (is (= "/" (get (:headers resp) "location"))
                                  (pr-str resp))
                              (.close server done))
                            (= :end res)
                            (do (is false "Stream closed without response")
                                (.close server done))
                            :else (js/setTimeout #(check cursor) 10))))]
                 (check {:position 0}))))))


#?(:cljd
   (defn- start-dart-server!
     [port]
     (let [server-ref (atom nil)]
       (-> (io/HttpServer.bind "127.0.0.1" port)
           (.then
             (fn [server]
               (let [server ^io/HttpServer server]
                 (reset! server-ref server)
                 (.listen
                   server
                   (fn [request]
                     (let [request ^io/HttpRequest request
                           response (.-response request)]
                       (set! (.-statusCode response) 200)
                       (.add (.-headers response)
                             "X-Echo-Method"
                             (.-method request))
                       (.add (.-headers response)
                             "X-Echo-Custom"
                             (or (.value (.-headers request) "x-custom")
                                 "none"))
                       (.add (.-headers response) "Content-Type" "text/plain")
                       (.write response "hello")
                       (.close response)
                       nil)))))))
       {:stop-fn (fn [] (when-let [s @server-ref] (.close s)))})))


#?(:cljd (defn- !!-fut
           [stream cursor]
           (let [res (ds/next stream cursor)]
             (cond (map? res) (async/Future.value (:ok res))
                   (= :end res) (async/Future.value nil)
                   :else (-> (async/Future.delayed (Duration .milliseconds 10))
                             (.then (fn [_] (!!-fut stream cursor))))))))


(deftest cljd-http-test
  #?(:cljd (let [port 18997
                 server-handle (start-dart-server! port)
                 url (str "http://127.0.0.1:" port)]
             (-> (async/Future.delayed (Duration .milliseconds 100)) ; Wait
                 ;; for
                 ;; server
                 ;; to bind
                 (.then (fn [_]
                          (ds/open! {:type :http,
                                     :url url,
                                     :method :get,
                                     :headers {"X-Custom" "abc"}})))
                 (.then (fn [stream] (!!-fut stream {:position 0})))
                 (.then (fn [resp]
                          (is (= 200 (:status resp)) (pr-str resp))
                          (is (= "GET" (get (:headers resp) "x-echo-method")))
                          (is (= "abc" (get (:headers resp) "x-echo-custom"))
                              (pr-str resp))
                          (is (= "hello" (:body resp)))))
                 (.whenComplete (fn [] ((:stop-fn server-handle))))))))
