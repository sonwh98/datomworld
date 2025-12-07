(ns world.src-browser
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ring.util.response :as response]
   [hiccup.page :as hiccup]
   [world.blog :as blog]))

(defn file-listing [dir-path request-path]
  "Generate HTML for a directory listing"
  (let [dir (io/file dir-path)
        files (when (.exists dir)
                (->> (.listFiles dir)
                     (sort-by #(.getName %))
                     (sort-by #(.isDirectory %) #(compare %2 %1))))]
    [:div.directory-listing
     [:h1 (str "Source Browser: " request-path)]
     [:nav.breadcrumb
      (let [parts (str/split request-path #"/")
            paths (reductions #(str %1 "/" %2) parts)]
        (interpose " / "
                   (for [[part path] (map vector parts paths)]
                     [:a {:href (str "/src" path)} part])))]
     [:ul.file-list
      (when (not= request-path "/")
        [:li.dir [:a {:href (str "/src" (str/replace request-path #"/[^/]+$" ""))} "📁 .."]])
      (for [file files
            :let [name (.getName file)
                  is-dir (.isDirectory file)
                  link (str "/src" request-path "/" name)]]
        [:li {:class (if is-dir "dir" "file")}
         [:a {:href link}
          (if is-dir
            (str "📁 " name)
            (str "📄 " name))]])]]))

(defn render-source-page [content]
  "Wrap source code in a styled HTML page with syntax highlighting"
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Source Browser"]
    [:link {:rel "stylesheet"
            :href "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/night-owl.min.css"}]
    [:style "
      body {
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
        max-width: 1200px;
        margin: 0;
        padding: 0;
        line-height: 1.6;
        color: #333;
      }
      .header {
        background: #0066cc;
        color: white;
        padding: 20px 40px;
        margin-bottom: 0;
      }
      .header h1 {
        margin: 0;
        font-size: 1.5rem;
        border: none;
        padding: 0;
      }
      .breadcrumb {
        margin: 0;
        padding: 10px 40px;
        background: #f5f5f5;
        border-bottom: 1px solid #ddd;
      }
      .breadcrumb a {
        color: #0066cc;
        text-decoration: none;
      }
      .breadcrumb a:hover {
        text-decoration: underline;
      }
      pre {
        margin: 0;
        padding: 20px 40px;
        background: #011627 !important;
        overflow-x: auto;
      }
      pre code {
        font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
        font-size: 14px;
        line-height: 1.5;
      }
    "]]
   [:body content
    [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"}]
    [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/clojure.min.js"}]
    [:script "hljs.highlightAll();"]]])

(defn render-directory-page [content]
  "Wrap directory listing in a styled HTML page"
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Source Browser"]
    [:style "
      body {
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
        max-width: 900px;
        margin: 40px auto;
        padding: 0 20px;
        line-height: 1.6;
        color: #333;
      }
      h1 {
        border-bottom: 2px solid #0066cc;
        padding-bottom: 10px;
      }
      .breadcrumb {
        margin: 20px 0;
        padding: 10px;
        background: #f5f5f5;
        border-radius: 4px;
      }
      .breadcrumb a {
        color: #0066cc;
        text-decoration: none;
      }
      .breadcrumb a:hover {
        text-decoration: underline;
      }
      .file-list {
        list-style: none;
        padding: 0;
      }
      .file-list li {
        padding: 8px;
        border-bottom: 1px solid #eee;
      }
      .file-list li:hover {
        background: #f9f9f9;
      }
      .file-list a {
        text-decoration: none;
        color: #333;
        display: block;
      }
      .file-list a:hover {
        color: #0066cc;
      }
      .dir {
        font-weight: 500;
      }
    "]]
   [:body content]])

(defn clojure-file? [uri]
  "Check if the URI is a Clojure source file"
  (or (str/ends-with? uri ".clj")
      (str/ends-with? uri ".cljs")
      (str/ends-with? uri ".cljc")))

(defn src-handler [req]
  "Handler for /src directory browsing with .blog file support"
  (let [uri (:uri req)
        ;; Remove /src prefix to get relative path
        rel-path (str/replace-first uri #"^/src" "")
        ;; Handle empty path or just /
        rel-path (if (or (empty? rel-path) (= rel-path "/"))
                   ""
                   rel-path)
        ;; Build file system path
        fs-path (str "src" rel-path)
        file (io/file fs-path)]
    (cond
      ;; Handle .blog files with the blog handler
      (str/ends-with? uri ".blog")
      (if (.exists file)
        (try
          (let [blog-data (read-string (slurp file))
                rendered-hiccup (blog/render-blog blog-data)
                html (hiccup/html5 rendered-hiccup)]
            (-> (response/response html)
                (response/content-type "text/html; charset=utf-8")))
          (catch Exception e
            (println "Error rendering blog:" fs-path)
            (.printStackTrace e)
            (-> (response/response (str "Error rendering blog: " (.getMessage e)))
                (response/status 500)
                (response/content-type "text/plain"))))
        (-> (response/response "Blog post not found")
            (response/status 404)
            (response/content-type "text/plain")))

      ;; Handle directories
      (.isDirectory file)
      (let [content (file-listing fs-path (or rel-path "/"))
            html (hiccup/html5 (render-directory-page content))]
        (-> (response/response html)
            (response/content-type "text/html; charset=utf-8")))

      ;; Handle Clojure source files with syntax highlighting
      (and (.exists file) (clojure-file? uri))
      (let [source-code (slurp file)
            filename (.getName file)
            content [:div
                     [:div.header
                      [:h1 filename]]
                     [:nav.breadcrumb
                      (let [parts (str/split rel-path #"/")
                            paths (reductions #(str %1 "/" %2) parts)]
                        (interpose " / "
                                   (for [[part path] (map vector parts paths)]
                                     [:a {:href (str "/src" path)} part])))]
                     [:pre [:code.language-clojure source-code]]]
            html (hiccup/html5 (render-source-page content))]
        (-> (response/response html)
            (response/content-type "text/html; charset=utf-8")))

      ;; Handle regular files (show as plain text)
      (.exists file)
      (-> (response/file-response fs-path)
          (response/content-type "text/plain; charset=utf-8"))

      ;; File not found
      :else
      (-> (response/response "File or directory not found")
          (response/status 404)
          (response/content-type "text/plain")))))
