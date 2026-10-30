(ns dao.star-cart.view-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.star-cart.grid-view :as grid-view]
            [dao.star-cart.view :as v]))


(def items
  [{:key "a" :name "Alpha <b>" :price 2400 :description "d" :images ["/images/a b.png"] :tagories ["t"]}
   {:key "b" :name "Bravo" :price 5 :description "" :images [] :tagories []}])


(def catalog
  {:items (fn [] items)
   :item (fn [slug] (first (filter #(= slug (:key %)) items)))
   :tagories (fn [] ["t" "other"])})


(deftest datastar-sse-bytes-match-the-pinned-vocabulary
  (is (= "1.0.0-RC.7" v/datastar-version))
  (is (= "event: datastar-patch-elements\ndata: elements <div id=\"app\">x</div>\n\n"
         (v/sse-elements "<div id=\"app\">x</div>")))
  (is (= "event: datastar-patch-elements\ndata: elements <p>a</p> <p>b</p>\n\n"
         (v/sse-elements "<p>a</p>\n<p>b</p>"))
      "a newline never ends a data line")
  (is (= "event: datastar-patch-signals\ndata: signals {\"act\":\"\"}\n\n"
         (v/sse-signals "{\"act\":\"\"}"))))


(deftest money-is-minor-units
  (is (= "$24.00" (v/money 2400)))
  (is (= "$0.05" (v/money 5)))
  (is (= "$0.00" (v/money nil))))


(deftest the-begin-page-is-a-complete-document
  (let [page (v/shop-begin "ROOT")]
    (is (str/starts-with? page "<!doctype html>"))
    (is (str/includes? page "src=\"/js/datastar.js\""))
    (is (str/includes? page "href=\"/css/cart.css?v=6\""))
    (is (str/includes? page "data-init=\"@get('/s/ROOT',{retry:'always'})\""))
    (is (str/includes? (grid-view/grid-begin "ROOT") "data-init=\"@get('/grid/s/ROOT',{retry:'always'})\""))
    (is (str/includes? page "type=\"search\""))
    (is (str/includes? page "data-on:input__debounce.150ms"))
    (is (str/includes? page "id=\"app-body\""))))


(deftest the-shop-projects-catalog-detail-and-cart
  (testing "catalog: escaped names, encoded image urls, blank-name items hidden"
    (let [sse (v/shop-project catalog "TOK" {:page "catalog" :cart {}})]
      (is (str/starts-with? sse "event: datastar-patch-signals\ndata: signals {\"tok\":\"TOK\"}\n\n"))
      (is (str/includes? sse "event: datastar-patch-elements\ndata: elements <div id=\"app-body\">"))
      (is (not (str/includes? sse "type=\"search\"")) "the search input is never patched")
      (is (str/ends-with? sse "\n\n"))
      (is (str/includes? sse "Alpha &lt;b&gt;"))
      (is (str/includes? sse "<img src=\"/images/a%20b.png\""))
      (is (str/includes? sse "@post('/k/'+$tok)"))
      (is (str/includes? sse "Catalog as of last page load."))))
  (testing "cart: names, unit prices, quantities and a computed total; zero lines dropped"
    (let [sse (v/shop-project catalog "TOK" {:page "cart" :cart {"a" 2 "b" 3 "gone" 4 "x" 0}})]
      (is (str/includes? sse "Alpha &lt;b&gt;"))
      (is (str/includes? sse "$24.00"))
      (is (str/includes? sse "<span class=\"qty\">2</span>"))
      (is (str/includes? sse "Total: <strong>$48.15</strong>") "2*2400 + 3*5")
      (is (str/includes? sse "Cart (9)")))))


(deftest every-grid-action-carries-the-grid-prefix
  (doseq [model [{:sel nil :status "" :msg ""}
                 {:sel "a" :status "saving" :msg ""}
                 {:sel "a" :status "rejected" :msg "bad <price>"}]]
    (let [sse (grid-view/grid-project catalog "TOK" model)
          urls (map second (re-seq #"@(?:get|post)\('([^']*)'" sse))]
      (is (seq urls))
      (is (every? #(str/starts-with? % "/grid/") urls) (pr-str urls))
      (is (str/includes? sse "@post('/grid/k/'+$tok)") "the actions post to the live token")
      (is (not (str/includes? sse "bad <price>")) "escaped"))))


(deftest the-catalog-search-field-filters-items
  (testing "the search input is a begin-page sibling of the patched body"
    (let [page (v/shop-begin "ROOT")]
      (is (str/includes? page "type=\"search\""))
      (is (str/includes? page "data-bind:search"))
      (is (str/includes? page "$act='search'; $arg=$search; @post('/k/'+$tok)"))
      (is (str/includes? page "tok:") "the begin page seeds the token signal")
      (is (str/includes? page "search:"))
      (is (not (str/includes? page "value=\"")))
      (is (not (str/includes? page "event.target")))))
  (testing "the query filters the grid; the patch carries no input field"
    (let [sse (v/shop-project catalog "TOK" {:page "catalog" :cart {} :search "alp"})]
      (is (str/includes? sse "Alpha &lt;b&gt;"))
      (is (not (str/includes? sse "Bravo")))
      (is (not (str/includes? sse "type=\"search\"")) "the search input is never patched"))
    (let [sse (v/shop-project catalog "TOK" {:page "catalog" :cart {} :search "ALP"})]
      (is (str/includes? sse "Alpha &lt;b&gt;"))
      (is (not (str/includes? sse "Bravo")))))
  (testing "whitespace-only and empty queries match everything"
    (let [sse (v/shop-project catalog "TOK" {:page "catalog" :cart {} :search "   "})]
      (is (str/includes? sse "Alpha &lt;b&gt;"))
      (is (str/includes? sse "Bravo")))
    (let [sse (v/shop-project catalog "TOK" {:page "catalog" :cart {}})]
      (is (str/includes? sse "Bravo")))))


(deftest the-catalog-organizes-items-by-tagories
  (testing "the tagory row renders All and one chip per tagory"
    (let [sse (v/shop-project catalog "TOK" {:page "catalog" :cart {} :search "" :tagory ""})]
      (is (str/includes? sse "class=\"tagories\""))
      (is (str/includes? sse "$act=&#39;tagory&#39;; $arg=&#39;&#39;; @post('/k/'+$tok)"))
      (is (str/includes? sse ">t</button>"))
      (is (str/includes? sse ">other</button>"))))
  (testing "a selected tagory is highlighted and filters the grid"
    (let [sse (v/shop-project catalog "TOK" {:page "catalog" :cart {} :search "" :tagory "t"})]
      (is (str/includes? sse "class=\"chip on\""))
      (is (str/includes? sse "Alpha &lt;b&gt;"))
      (is (not (str/includes? sse "&gt;Bravo&lt;"))))
    (let [sse (v/shop-project catalog "TOK" {:page "catalog" :cart {} :search "" :tagory "other"})]
      (is (str/includes? sse "class=\"chip on\""))
      (is (not (str/includes? sse "&gt;Alpha &lt;b&gt;&lt;/h3&gt;")))
      (is (not (str/includes? sse "&gt;Bravo&lt;")))))
  (testing "the tagory filter combines with the search text"
    (let [sse (v/shop-project catalog "TOK" {:page "catalog" :cart {}
                                             :search "bravo" :tagory "t"})]
      (is (not (str/includes? sse "&gt;Bravo&lt;")))
      (is (not (str/includes? sse "&gt;Alpha &lt;b&gt;&lt;/h3&gt;")))))
  (testing "the section title tracks the filters"
    (is (str/includes? (v/shop-project catalog "TOK" {:page "catalog" :cart {} :search "" :tagory ""})
                       "All Products"))
    (is (str/includes? (v/shop-project catalog "TOK" {:page "catalog" :cart {} :search "" :tagory "t"})
                       "<h2>t</h2>"))
    (is (str/includes? (v/shop-project catalog "TOK" {:page "catalog" :cart {} :search "alp" :tagory ""})
                       "<h2>Search: &quot;alp&quot;</h2>"))
    (is (not (str/includes? (v/shop-project catalog "TOK" {:page "catalog" :cart {} :search "alp" :tagory ""})
                            "<h2>All Products</h2>")))))
