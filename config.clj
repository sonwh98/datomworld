{:port 3002
 :chp-dir "public/chp"
 :public-dir "public"
 :bidi-routes ["/" [["" (fn [req] (stigmergy.chp/hiccup-page-handler (assoc req :uri "/overview.chp")))]
                    [#".*\.chp"  stigmergy.chp/hiccup-page-handler]]]}
