(ns dao.star.signals
  "Decode a request's client signals from an `:http/request` event
   (docs/design/dao.star.md, Signals).

   Signals are the client's unverified claim, so on the JVM and ClojureScript
   `decode` is total: a missing, malformed or non-map input answers `{}` and
   nothing throws.  (The one exception is the ClojureDart seam, below.)  GET and DELETE
   carry them in the query parameter the spec names, every other method in the
   JSON body.  The JSON parse is the host's; a host without one decodes
   nothing.

   Scope: the JSON and URL-decoding seams are implemented for the JVM and
   ClojureScript only.  On ClojureDart they are unimplemented, and `decode`
   throws `ex-info` naming the seam (`:dao.star.signals/seam`) rather than
   answer `{}`, which would be indistinguishable from absent signals."
  (:require [clojure.string :as str]
            [dao.host.bytes :as bytes]
            [dao.host.text :as text]
            #?(:clj [clojure.data.json :as json])))


#_{:clj-kondo/ignore [:unused-private-var]}


(defn- unimplemented
  [seam]
  (throw (ex-info (str "dao.star.signals: no " (name seam) " seam on this host")
                  {:dao.star.signals/seam seam})))


(defn- parse-json
  [s]
  #?(:cljd (unimplemented :json)
     :clj (json/read-str s :key-fn keyword)
     :cljs (js->clj (js/JSON.parse s) :keywordize-keys true)))


(defn- url-decode
  [s]
  #?(:cljd (unimplemented :url-decode)
     :clj (java.net.URLDecoder/decode ^String s "UTF-8")
     :cljs (js/decodeURIComponent (str/replace s "+" " "))))


(defn- body-text
  [body]
  (cond (string? body) body
        (bytes/bytes? body) (text/utf8-decode body)
        :else nil))


(defn- query-param
  [query k]
  (when (string? query)
    (some (fn [pair]
            (let [[name value] (str/split pair #"=" 2)]
              (when (= k name) (or value ""))))
          (str/split query #"&"))))


(defn decode
  "The signals map of request `event`, or `{}`."
  [event]
  (try
    (let [raw (if (contains? #{:get :delete} (:http/method event))
                (some-> (query-param (:http/query event) "datastar") url-decode)
                (body-text (:http/body event)))
          parsed (when (and (string? raw) (not (empty? raw))) (parse-json raw))]
      (if (map? parsed) parsed {}))
    (catch #?(:cljd Object :clj Throwable :cljs :default) e
      (if (:dao.star.signals/seam (ex-data e))
        (throw e)
        {}))))
