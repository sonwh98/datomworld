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
   :item (fn [slug] (first (filter #(= slug (:key %)) items)))})


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
    (is (str/includes? page "href=\"/css/cart.css\""))
    (is (str/includes? page "data-init=\"@get('/s/ROOT',{retry:'always'})\""))
    (is (str/includes? (grid-view/grid-begin "ROOT") "data-init=\"@get('/grid/s/ROOT',{retry:'always'})\""))))


(deftest the-shop-projects-catalog-detail-and-cart
  (testing "catalog: escaped names, encoded image urls, blank-name items hidden"
    (let [sse (v/shop-project catalog "TOK" {:page "catalog" :cart {}})]
      (is (str/starts-with? sse "event: datastar-patch-elements\ndata: elements <div id=\"app\">"))
      (is (str/ends-with? sse "\n\n"))
      (is (str/includes? sse "Alpha &lt;b&gt;"))
      (is (str/includes? sse "<img src=\"/images/a%20b.png\""))
      (is (str/includes? sse "@post('/k/TOK')"))
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
          urls (map second (re-seq #"@(?:get|post)\('([^']+)'\)" sse))]
      (is (seq urls))
      (is (every? #(str/starts-with? % "/grid/") urls) (pr-str urls))
      (is (not (str/includes? sse "bad <price>")) "escaped"))))
