(ns dao.star-cart.edge-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dao.star-cart.edge :as edge]))


(def root "resources/dao_star_cart/public")


(defn- site
  []
  (edge/make-edge {:asset-root root
                   :shop (fn [req] {:status 200 :body (str "shop:" (:uri req))})
                   :grid (fn [req] {:status 200 :body (str "grid:" (:uri req))})}))


(defn- get-uri [uri] ((site) {:uri uri :request-method :get}))


(deftest the-grid-prefix-is-stripped-and_does-not-reach-the-shop
  (is (= "grid:/" (:body (get-uri "/grid"))))
  (is (= "grid:/s/x" (:body (get-uri "/grid/s/x"))))
  (is (= "grid:/k/y" (:body (get-uri "/grid/k/y"))))
  (is (= "shop:/s/x" (:body (get-uri "/s/x"))))
  (is (= "shop:/" (:body (get-uri "/"))))
  (is (= "shop:/gridlock" (:body (get-uri "/gridlock"))) "only /grid and /grid/... are the grid"))


(deftest static-assets-are-served-from-disk-and-never-enter-a-boundary
  (let [css (get-uri "/css/cart.css")
        img (get-uri "/images/7..png")
        grid-img (get-uri "/grid/images/7..png")]
    (is (= 200 (:status css)))
    (is (= "text/css" (get-in css [:headers "content-type"])))
    (is (= 200 (:status img)))
    (is (= "image/png" (get-in img [:headers "content-type"])))
    (is (pos? (.length ^java.io.File (:body img))))
    (is (= 200 (:status grid-img)) "the grid page's own asset urls work")))


(deftest static-path-traversal-is-refused
  (doseq [uri ["/images/../deps.edn"
               "/images/%2e%2e/deps.edn"
               "/images/%2e%2e/%2e%2e/deps.edn"
               "/images/..%2fdeps.edn"
               "/images/..%5cdeps.edn"
               "/images//etc/passwd"
               "/images/%2fetc/passwd"
               "/images/..%2f..%2f..%2f..%2fetc%2fpasswd"
               "/css/../images/7..png"
               "/grid/images/../deps.edn"
               "/grid/images/%2e%2e/deps.edn"
               "/images/"
               "/images/nope.png"
               "/images/%00.png"
               "/js/../../deps.edn"]]
    (let [r (get-uri uri)]
      (is (= 404 (:status r)) uri)
      (is (string? (:body r)))
      (is (not (str/includes? (:body r) "paths")) "no file content"))))


(deftest a-directory-is-not-a-regular-file
  (is (= 404 (:status (get-uri "/images/.")))))
