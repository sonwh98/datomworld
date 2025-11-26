{:port 3002
 :chp-dir "public/chp"
 :public-dir "public"
 :bidi-routes ["/" [["" (fn [req] (stigmergy.chp/hiccup-page-handler (assoc req :uri "/datomworld.chp")))]
                    ["src" {true world.src-browser/src-handler}]
                    [#".*\.blog"  world.blog/blog-handler]
                    [#".*\.chp"  stigmergy.chp/hiccup-page-handler]]]}
