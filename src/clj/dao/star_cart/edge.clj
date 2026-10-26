(ns dao.star-cart.edge
  "The host edge: one http-kit handler for the whole site.  Static assets
   (`/images/`, `/css/`, `/js/`) are served from disk and never enter a
   boundary; `/grid` and `/grid/...` go to the grid boundary with the prefix
   stripped from `:uri`; everything else goes to the shop boundary."
  (:require [clojure.string :as str])
  (:import [java.net URLDecoder]
           [java.nio.file Files LinkOption Path Paths]))


(def content-types
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif"
   "webp" "image/webp" "svg" "image/svg+xml" "css" "text/css" "js" "text/javascript"
   "woff2" "font/woff2" "ico" "image/x-icon"})


(def static-prefixes ["/images/" "/css/" "/js/"])


(defn decode-path
  "Percent-decode a request path (a `+` is a plus here, not a space); nil when
   malformed."
  [uri]
  (try (URLDecoder/decode (str/replace (str uri) "+" "%2B") "UTF-8")
       (catch Exception _ nil)))


(defn- text-response
  [status body]
  {:status status :headers {"content-type" "text/plain"} :body body})


(defn static-file
  "The regular file `decoded-path` names under `root`, or nil.  The path is
   resolved against the root and normalized; anything that leaves the root, a
   NUL, a backslash or a missing or non-regular file answers nil."
  [^Path root decoded-path]
  (when (and decoded-path
             (not (str/includes? decoded-path "\u0000"))
             (not (str/includes? decoded-path "\\"))
             (not (some #{".."} (str/split decoded-path #"/"))))
    (let [rel (str/replace-first decoded-path #"^/+" "")
          target (.normalize (.resolve root rel))]
      (when (.startsWith target root)
        ;; The lexical check above is not enough: any component of the path
        ;; may be a symlink.  Resolve both real paths and require containment.
        (try
          (let [real-root (.toRealPath root (make-array LinkOption 0))
                real (.toRealPath target (make-array LinkOption 0))]
            (when (and (.startsWith real real-root)
                       (Files/isRegularFile real (make-array LinkOption 0)))
              (.toFile real)))
          (catch java.io.IOException _ nil))))))


(defn serve-static
  [root path]
  (if-let [f (static-file root path)]
    (let [ext (str/lower-case (last (str/split (.getName f) #"\.")))]
      {:status 200
       :headers {"content-type" (get content-types ext "application/octet-stream")
                 "cache-control" "public, max-age=3600"}
       :body f})
    (text-response 404 "not found")))


(defn strip-grid
  "`/grid` -> `/`, `/grid/s/x` -> `/s/x`; nil when `uri` is not under /grid."
  [uri]
  (cond (= uri "/grid") "/"
        (str/starts-with? uri "/grid/") (subs uri 5)
        :else nil))


(defn static-path?
  [path]
  (some #(str/starts-with? path %) static-prefixes))


(defn make-edge
  "`{:asset-root path :shop handler :grid handler}`."
  [{:keys [asset-root shop grid]}]
  (let [root (.toAbsolutePath (.normalize (Paths/get (str asset-root) (make-array String 0))))]
    (fn [req]
      (let [raw (:uri req)
            decoded (decode-path raw)
            under-grid (strip-grid (or raw "/"))
            static-raw (or under-grid raw)
            ;; any raw path whose first segment is a static prefix, with or
            ;; without the /grid prefix, is static; the decode happens after
            ;; the prefix test so encoded separators cannot dodge it
            static? (static-path? (str static-raw))
            decoded-static (when static? (decode-path static-raw))]
        (cond
          (nil? decoded) (text-response 400 "bad request")
          static? (serve-static root decoded-static)
          under-grid (grid (assoc req :uri under-grid))
          :else (shop req))))))
