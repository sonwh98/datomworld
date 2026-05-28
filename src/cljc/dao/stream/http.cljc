(ns dao.stream.http
  "Uniform cross-platform HTTP client integrated with DaoStream.
   
   Descriptor:
     {:type :http
      :url \"...\"
      :method :get | :post | :put | :delete
      :headers {...}
      :body \"...\"
      :timeout ms
      :follow-redirects bool}
   
   Returns a single-item ringbuffer stream that emits a normalized response:
     {:status int
      :body string | nil
      :headers {\"lowercase-string\" \"string\"}
      :error {:kind :timeout | :connection | :unknown, :message string}}"
  (:require
    #?@(:cljd [["package:http/http.dart" :as http]
               ["dart:async" :as async]]
        :cljs []
        :clj [[org.httpkit.client :as http]])
    [clojure.string :as str]
    [dao.stream :as ds]
    [dao.stream.ringbuffer :as ringbuffer]))


(defn- normalize-headers
  [headers]
  (into {} (map (fn [[k v]] [(str/lower-case (name k)) (str v)])) headers))


#?(:clj (defn- normalize-response
          [{:keys [status body headers error]}]
          (cond-> {:status status,
                   :body body,
                   :headers (normalize-headers headers)}
            error (assoc :error
                         (cond (instance? java.net.ConnectException error)
                               {:kind :connection, :message (.getMessage error)}
                               (or (instance? java.net.SocketTimeoutException error)
                                   (instance? org.httpkit.client.TimeoutException
                                              error))
                               {:kind :timeout, :message (.getMessage error)}
                               :else {:kind :unknown, :message (str error)})))))


#?(:cljd (defn- normalize-dart-response
           [resp]
           {:status (.-statusCode resp),
            :body (.-body resp),
            :headers (normalize-headers (into {}
                                              (map (fn [e] [(key e) (val e)]))
                                              (.-headers resp)))}))


#?(:cljd (do
           (deftype HttpOpenMethod
             []
             :type-only
             true

             cljd.core/IFn

             (-invoke
               [_ ^dynamic table]
               (assoc!
                 table
                 :http
                 (fn [descriptor]
                   (let [{:keys [url method body headers timeout
                                 follow-redirects],
                          :or {method :get}}
                         descriptor
                         out (ringbuffer/make-ring-buffer-stream 1)
                         uri (Uri.parse url)
                         client (http/Client.)
                         request (http/Request. (str/upper-case (name method))
                                                uri)]
                     (when headers
                       (doseq [[k v] headers]
                         (.putIfAbsent (.-headers request)
                                       (name k)
                                       (fn [] (str v)))))
                     (when (some? follow-redirects)
                       (set! (.-followRedirects request) follow-redirects))
                     (when body (set! (.-body request) body))
                     (let [fut (cond-> (.send client request)
                                 timeout (.timeout (Duration .milliseconds
                                                             timeout)))]
                       (->
                         fut
                         (.then (fn [streamed-resp]
                                  (http/Response.fromStream streamed-resp)))
                         (.then (fn [resp]
                                  (ds/put! out (normalize-dart-response resp))
                                  (ds/close! out)))
                         (.catchError
                           (fn [e]
                             (let [err (cond (instance? async/TimeoutException
                                                        e)
                                             {:kind :timeout,
                                              :message (.toString e)}
                                             :else {:kind :unknown,
                                                    :message (.toString e)})]
                               (ds/put! out {:status 0, :error err})
                               (ds/close! out))))
                         (.whenComplete (fn [] (.close client)))))
                     out)))))
           (contribute* :multi-method
                        dao.stream/open!
                        dao.stream.http/HttpOpenMethod
                        dao.stream.http/HttpOpenMethod))
   :default
   (defmethod ds/open! :http
     [descriptor]
     (let [{:keys [url method body headers timeout follow-redirects],
            :or {method :get}}
           descriptor
           out (ringbuffer/make-ring-buffer-stream 1)]
       #?(:clj (http/request (cond-> {:url url,
                                      :method method,
                                      :body body,
                                      :headers headers,
                                      :timeout timeout}
                               (some? follow-redirects)
                               (assoc :follow-redirects follow-redirects))
                             (fn [resp]
                               (ds/put! out (normalize-response resp))
                               (ds/close! out)))
          :cljs
          (let [controller (when timeout (js/AbortController.))
                timeout-id (when timeout
                             (js/setTimeout #(.abort controller) timeout))
                opts #js {:method (str/upper-case (name method)),
                          :headers (clj->js (or headers {})),
                          :body body,
                          :redirect (if (false? follow-redirects)
                                      "manual"
                                      "follow"),
                          :signal (some-> controller
                                          .-signal)}]
            (-> (js/fetch url opts)
                (.then
                  (fn [resp]
                    (when timeout-id (js/clearTimeout timeout-id))
                    (-> (.text resp)
                        (.then (fn [body-text]
                                 (let [headers-obj (.-headers resp)
                                       headers-map (atom {})]
                                   (.forEach
                                     headers-obj
                                     (fn [v k]
                                       (swap! headers-map assoc k v)))
                                   (ds/put! out
                                            {:status (.-status resp),
                                             :body body-text,
                                             :headers (normalize-headers
                                                        @headers-map)})
                                   (ds/close! out)))))))
                (.catch (fn [err]
                          (when timeout-id (js/clearTimeout timeout-id))
                          (let [error-map
                                (cond (= (.-name err) "AbortError")
                                      {:kind :timeout,
                                       :message "Request timed out"}
                                      :else {:kind :unknown,
                                             :message (.-message err)})]
                            (ds/put! out {:status 0, :error error-map})
                            (ds/close! out)))))))
       out)))
