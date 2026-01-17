{:port 3002
 :nrepl true
 :chp-dir "public/chp"
 :public-dir "public"
 :bidi-routes ["/" [["" (fn [req] (stigmergy.chp/hiccup-page-handler (assoc req :uri "/datomworld.chp")))]
                    [#".*\.blog"  world.blog/blog-handler]
                    [#".*\.chp"  stigmergy.chp/hiccup-page-handler]]]}
