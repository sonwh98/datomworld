(ns dao.star-cart.support
  "Loopback helpers: a raw socket HTTP client (one request per connection, or a
   held SSE subscription read by a thread) and bounded waits."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dao.star-cart.main :as main])
  (:import [java.io ByteArrayOutputStream]
           [java.net Socket]))


(defn wait-for
  "Poll `f` for up to `ms` milliseconds; its first truthy value."
  [ms f]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))


(defn temp-dir
  []
  (.toFile (java.nio.file.Files/createTempDirectory "star-cart" (make-array java.nio.file.attribute.FileAttribute 0))))


(defn start-site!
  "A site on an ephemeral port with its own catalog path in a temp dir."
  ([] (start-site! {}))
  ([opts]
   (let [dir (temp-dir)
         path (str (io/file dir "catalog.edn"))]
     (assoc (main/start! (merge {:port 0 :catalog-path path} opts)) :catalog-path path))))


(defn- request-text
  [method path body keep-alive?]
  (let [bs (.getBytes ^String (or body "") "UTF-8")]
    (str method " " path " HTTP/1.1\r\nHost: localhost\r\n"
         (when body (str "Content-Length: " (count bs) "\r\nContent-Type: application/json\r\n"))
         (if keep-alive? "\r\n" "Connection: close\r\n\r\n")
         (or body ""))))


(defn- read-all
  [^Socket s]
  (let [out (ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (try
      (loop []
        (let [n (.read (.getInputStream s) buf)]
          (when (pos? n) (.write out buf 0 n) (recur))))
      (catch java.net.SocketTimeoutException _ nil))
    (.toByteArray out)))


(defn http
  "One request on its own connection; `{:status :text :bytes}` of the response."
  ([port method path] (http port method path nil))
  ([port method path body]
   (with-open [s (doto (Socket. "127.0.0.1" (int port)) (.setSoTimeout 8000))]
     (doto (.getOutputStream s)
       (.write (.getBytes ^String (request-text method path body false) "UTF-8"))
       (.flush))
     (let [bs (read-all s)
           text (String. bs "UTF-8")]
       {:status (some-> (re-find #"^HTTP/1\.\d (\d+)" text) second Long/parseLong)
        :text text :bytes bs}))))


(defn open-sse
  "A held GET: the accumulated response text is in the returned `:text` atom."
  [port path]
  (let [s (doto (Socket. "127.0.0.1" (int port)) (.setSoTimeout 30000))
        text (atom "")
        t (doto (Thread.
                  (fn []
                    (let [buf (byte-array 8192)]
                      (try
                        (loop []
                          (let [n (.read (.getInputStream s) buf)]
                            (when (pos? n)
                              (swap! text str (String. buf 0 n "UTF-8"))
                              (recur))))
                        (catch Exception _ nil)))))
            (.setDaemon true) (.start))]
    (doto (.getOutputStream s)
      (.write (.getBytes ^String (request-text "GET" path nil true) "UTF-8"))
      (.flush))
    {:socket s :text text :thread t}))


(defn close-sse
  [{:keys [^Socket socket]}]
  (.close socket))


(defn root-of
  [page]
  (second (re-find #"@get\('(?:/grid)?/s/([^']+)'" page)))


(defn token-of
  "The newest `/k/<token>` in `text` (the last action URL projected)."
  [text]
  (last (map second (re-seq #"/k/([0-9a-f-]{36})" text))))


(defn begin!
  "GET the root of `prefix` (\"\" or \"/grid\"); `{:root :page}`."
  [port prefix]
  (let [page (:text (http port "GET" (if (empty? prefix) "/" prefix)))]
    {:page page :root (root-of page)}))


(defn input!
  "POST signals JSON to `/k/<token>` under `prefix`; the response text."
  [port prefix token signals]
  (:text (http port "POST" (str prefix "/k/" token) (json/write-str signals))))


(defn count-matches
  [re text]
  (count (re-seq re text)))


(defn blank?
  [s]
  (str/blank? s))
