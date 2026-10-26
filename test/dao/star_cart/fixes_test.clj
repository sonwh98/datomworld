(ns dao.star-cart.fixes-test
  "Review round: symlink containment, collision-free tagory tempids, snapshot
   ordering, single image-url encoding, line terminators, and the default
   catalog path."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.star-cart.catalog :as catalog]
            [dao.star-cart.edge :as edge]
            [dao.star-cart.grid-effects :as fx]
            [dao.star-cart.grid-view :as grid-view]
            [dao.star-cart.main :as main]
            [dao.star-cart.store :as store]
            [dao.star-cart.support :as sup]
            [dao.star-cart.view :as v]
            [dao.star.signals :as signals])
  (:import [java.nio.file Files]))


(def asset-root "resources/dao_star_cart/public")


(defn- fixture
  [item-maps & [opts]]
  (let [dir (sup/temp-dir)
        path (str (io/file dir "catalog.edn"))]
    (spit path (pr-str {:store/name "T" :items item-maps}))
    (assoc (store/open (merge {:path path :seed path} opts)) :test-path path)))


(defn- item
  [slug & {:as more}]
  (merge {:item/slug slug :item/name slug :item/price 100
          :item/images [{:image/url "/images/7..png"}] :item/tagories []}
         more))


;; 1. symlinks

(deftest an-intermediate-symlink-cannot-escape-the-asset-root
  (let [dir (sup/temp-dir)
        root (.toPath (io/file dir "public"))
        outside (.toPath (io/file dir "outside"))]
    (Files/createDirectories (.resolve root "images") (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createDirectories outside (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (.toFile (.resolve outside "secret.txt")) "SECRET")
    (spit (.toFile (.resolve root "images/ok.png")) "PNG")
    (Files/createSymbolicLink (.resolve root "images/link") outside (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createSymbolicLink (.resolve root "images/file-link") (.resolve outside "secret.txt")
                              (make-array java.nio.file.attribute.FileAttribute 0))
    (let [root (.toAbsolutePath (.normalize root))]
      (is (some? (edge/static-file root "/images/ok.png")) "a plain file is served")
      (is (nil? (edge/static-file root "/images/link/secret.txt")) "an intermediate directory symlink")
      (is (nil? (edge/static-file root "/images/file-link")) "a symlinked file")
      (is (nil? (edge/static-file root "/images/link"))))))


;; 2. tempids

(deftest tagories-that-hash-alike-stay-distinct
  (is (= (hash "Aa") (hash "BB")) "the premise: these collide under hash")
  (let [st (fixture [(item "a" :item/tagories [{:tagory/name "Aa"}])
                     (item "b" :item/tagories [{:tagory/name "BB"}])])
        with (fn [s n] (catalog/items-with-tagory (store/db s) n))]
    (is (= #{"a"} (with st "Aa")))
    (is (= #{"b"} (with st "BB")))
    (fx/apply-edit! st asset-root {:item "b" :changes {:price 5}})
    (let [fresh (store/open {:seed (:test-path st)})]
      (is (= #{"a"} (with fresh "Aa")))
      (is (= #{"b"} (with fresh "BB")))
      (fx/apply-edit! fresh asset-root {:item "a" :changes {:remove-tagory "Aa"}})
      (is (= #{} (with fresh "Aa")))
      (is (= #{"b"} (with fresh "BB")) "removing one leaves the other"))))


;; 3. reconnect attribute is asserted in view-test

(deftest the-subscription-asks-for-unconditional-retry
  (is (str/includes? (v/shop-begin "R") "{retry:'always'}"))
  (is (str/includes? (grid-view/grid-begin "R") "{retry:'always'}")))


;; 4. snapshot order

(deftest snapshots-are-written-in-update-order
  (let [calls (atom 0)
        slow-first (fn [path content]
                     (when (= 1 (swap! calls inc)) (Thread/sleep 300))
                     (store/write-atomically! path content))
        st (fixture [(item "a") (item "b")] {:write-fn slow-first})
        t1 (Thread. (fn [] (fx/apply-edit! st asset-root {:item "a" :changes {:price 11}})))
        t2 (Thread. (fn [] (Thread/sleep 60) (fx/apply-edit! st asset-root {:item "b" :changes {:price 22}})))]
    (.start t1) (.start t2) (.join t1 5000) (.join t2 5000)
    (is (= 2 @calls))
    (let [fresh (store/open {:seed (:test-path st)})]
      (is (= [11 22] (mapv #(:price (store/item fresh %)) ["a" "b"]))
          "the file carries both edits, not an older snapshot"))))


;; 5. image urls

(deftest an-encoded-image-spelling-is-stored_decoded_and-served
  (let [st (fixture [(item "a")])
        real "/images/P-141  Green Camouflage.png"
        encoded "/images/P-141%20%20Green%20Camouflage.png"]
    (is (.isFile (io/file asset-root (subs real 1))) "the premise: this file exists")
    (is (= {:saved "a"} (fx/apply-edit! st asset-root {:item "a" :changes {:add-image encoded}})))
    (is (some #{real} (:images (store/item st "a"))) "stored as the canonical decoded path")
    (is (= encoded (v/enc-path real)) "encoded exactly once for the page")
    (let [site (edge/make-edge {:asset-root asset-root :shop identity :grid identity})]
      (is (= 200 (:status (site {:uri encoded :request-method :get})))))
    (testing "the other spellings of it are the same image"
      (is (= {:rejected "image already on the item"}
             (fx/apply-edit! st asset-root {:item "a" :changes {:add-image real}})))
      (is (= {:rejected "image already on the item"}
             (fx/apply-edit! st asset-root {:item "a" :changes {:add-image encoded}}))))
    (is (= {:saved "a"} (fx/apply-edit! st asset-root {:item "a" :changes {:remove-image real}})))
    (is (contains? (fx/apply-edit! st asset-root {:item "a" :changes {:add-image "/images/%zz.png"}}) :rejected))))

(deftest filesystem-alias-image-paths-are-rejected
  (let [st (fixture [(item "a")])]
    (doseq [alias ["/images/./P-141  Green Camouflage.png"
                   "/images/P-141  Green Camouflage.png/"
                   "/images//P-141  Green Camouflage.png"
                   "/images/sub/../P-141  Green Camouflage.png"]]
      (is (contains? (fx/apply-edit! st asset-root {:item "a" :changes {:add-image alias}})
                     :rejected)))
    (is (= {:saved "a"} (fx/apply-edit! st asset-root {:item "a" :changes {:add-image "/images/P-141  Green Camouflage.png"}}))
        "the plain spelling still works")))


;; 6. line terminators

(def nasty "a\r\nb\rc\nd e f'g\\h")


(deftest line-terminators-never-reach-the-sse-stream-raw
  (is (not (re-find #"[\r\n  ]" (v/js-lit nasty))))
  (let [catalog {:items (fn [] [{:key "k" :name nasty :price 5 :description nasty :images [] :tagories [nasty]}])
                 :item (fn [_] {:key "k" :name nasty :price 5 :description nasty :images [] :tagories [nasty]})}]
    (doseq [sse [(grid-view/grid-project catalog "T" {:sel "k" :status "" :msg ""})
                 (grid-view/grid-project catalog "T" {:sel "k" :status "rejected" :msg nasty})
                 (v/shop-project catalog "T" {:page "item" :item "k" :cart {}})
                 (v/shop-project catalog "T" {:page "cart" :cart {"k" 1}})]]
      (is (not (str/includes? sse "\r")) "no raw CR")
      (is (= 3 (count (re-seq #"\n" sse))) "only the framing newlines")
      (is (str/ends-with? sse "\n\n")))))


(deftest line-terminators-round-trip-through-select-and-save
  (let [st (fixture [(item "a")])]
    (testing "through the signals JSON the client sends"
      (let [s (signals/decode {:http/method :post
                               :http/body (json/write-str {:act "save" :desc nasty :name "n"})})]
        (is (= nasty (:desc s)))))
    (testing "through the transact and back"
      (is (= {:saved "a"} (fx/apply-edit! st asset-root {:item "a" :changes {:description nasty}})))
      (is (= nasty (:description (store/item st "a"))))
      (let [fresh (store/open {:seed (:test-path st)})]
        (is (= nasty (:description (store/item fresh "a"))) "and through the written file")))
    (testing "and the page that selects it carries entities, not raw terminators"
      (let [sse (grid-view/grid-project (store/catalog-fns st) "T" {:sel "a" :status "" :msg ""})]
        (is (str/includes? sse "&#13;&#10;"))
        (is (not (str/includes? sse "\r")))))))


;; 7. default catalog path

(deftest the-catalog-path-default-is-writable-and-loud-when-absent
  (is (= "target/cart-catalog.edn" main/default-catalog-path))
  (let [dir (sup/temp-dir)
        path (str (io/file dir "nested" "deeper" "c.edn"))]
    (store/write-atomically! path "{}")
    (is (= "{}" (slurp path)) "missing parent directories are created"))
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (store/open {:seed "resources/dao_star_cart/catalog.edn"}))
    (is (str/includes? (str err) "MEMORY ONLY"))))
