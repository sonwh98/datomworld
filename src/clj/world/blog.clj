(ns world.blog
  (:require
   [clojure.java.io :as io]
   [clojure.string]
   [clojure.walk]
   [ring.util.response :as response]
   [hiccup.page :as hiccup]))

(defn render-blog [blog-data]
  "Render a blog post from .blog data using template"
  (let [template (load-file "public/chp/template.chp")
        content (:blog/content blog-data)
        ;; Unwrap content if it's wrapped in (list ...)
        ;; Content is like: (list [:section ...])
        unwrapped-content (if (and (seq? content)
                                   (= 'list (first content))
                                   (= 2 (count content)))
                            (second content)
                            (if (and (seq? content) (= 1 (count content)))
                              (first content)
                              content))
        ;; Format the date
        date-inst (:blog/date blog-data)
        date-str (if date-inst
                   (.format (java.text.SimpleDateFormat. "MMM d, yyyy") date-inst)
                   "Unknown date")
        ;; Replace placeholders in content
        replaced-content (clojure.walk/postwalk-replace
                          {:$blog-title [:h1 (:blog/title blog-data)]
                           :$blog-date [:p.blog-article-meta
                                        (str "Published " date-str " · TODO min read")]}
                          unwrapped-content)]
    ;; Replace in template
    (clojure.walk/postwalk-replace
     {:template/title (:blog/title blog-data)
      :template/content replaced-content}
     template)))

(defn blog-handler [req]
  "Handler for .blog files - renders blog data using template"
  (let [uri (:uri req)
        ;; uri is like /blog/semantics-structure-interpretation.blog
        blog-path (str "public/chp" uri)
        blog-file (io/file blog-path)]
    (if (.exists blog-file)
      (try
        (let [blog-data (read-string (slurp blog-file))
              rendered-hiccup (render-blog blog-data)
              html (hiccup/html5 rendered-hiccup)]
          (-> (response/response html)
              (response/content-type "text/html; charset=utf-8")))
        (catch Exception e
          (println "Error rendering blog:" blog-path)
          (.printStackTrace e)
          (-> (response/response (str "Error rendering blog: " (.getMessage e)))
              (response/status 500)
              (response/content-type "text/plain"))))
      ;; If .blog file doesn't exist, return 404
      (-> (response/response "Blog post not found")
          (response/status 404)
          (response/content-type "text/plain")))))
