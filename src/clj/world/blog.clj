(ns world.blog
  (:require [clojure.java.io :as io]
            [clojure.string]
            [clojure.walk]
            [hiccup.page :as hiccup]
            [ring.util.response :as response]))


(defn render-blog
  "Render a blog post from .blog data using template"
  [blog-data]
  (let [template (load-file "public/chp/template.chp")
        content (:blog/content blog-data)
        ;; Format the date
        date-inst (:blog/date blog-data)
        date-str (if date-inst
                   (.format (java.text.SimpleDateFormat. "MMM d, yyyy")
                            date-inst)
                   "Unknown date")
        ;; Replace placeholders in content
        replaced-content (clojure.walk/postwalk-replace
                           {:$blog-title [:h1 (:blog/title blog-data)],
                            :$blog-date [:p.blog-article-meta
                                         (str "Published " date-str)]}
                           content)]
    ;; Replace in template
    (clojure.walk/postwalk-replace {:template/title (:blog/title blog-data),
                                    :template/content replaced-content}
                                   template)))


(defn blog-handler
  "Handler for .blog files - renders blog data using template"
  [req]
  (let [uri (:uri req)
        ;; uri is like /blog/semantics-structure-interpretation.blog
        blog-path (str "public/chp" uri)
        blog-file (io/file blog-path)]
    (if (.exists blog-file)
      (try (let [blog-data (read-string (slurp blog-file))
                 rendered-hiccup (render-blog blog-data)
                 html (hiccup/html5 rendered-hiccup)]
             (-> (response/response html)
                 (response/content-type "text/html; charset=utf-8")))
           (catch Exception e
             (println "Error rendering blog:" blog-path)
             (.printStackTrace e)
             (-> (response/response (str "Error rendering blog: "
                                         (.getMessage e)))
                 (response/status 500)
                 (response/content-type "text/plain"))))
      ;; If .blog file doesn't exist, return 404
      (-> (response/response "Blog post not found")
          (response/status 404)
          (response/content-type "text/plain")))))
