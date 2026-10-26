(ns dao.star-cart.catalog-test
  "The catalog through dao.space: reads by q + collect, the checked-in seed,
   edits with validation, tagory and image add/remove, persistence, atomic
   write, concurrent saves."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dao.space.query :as query]
            [dao.space.transact :as transact]
            [dao.star-cart.catalog :as catalog]
            [dao.star-cart.grid-effects :as fx]
            [dao.star-cart.store :as store]
            [dao.star-cart.support :as sup]))


(def seed "resources/dao_star_cart/catalog.edn")
(def asset-root "resources/dao_star_cart/public")


(def three
  [{:item/slug "a" :item/name "Alpha" :item/price 100 :item/description "first"
    :item/images [{:image/url "/images/7..png"} {:image/url "/images/8..png"}]
    :item/tagories [{:tagory/name "one"} {:tagory/name "two"}]}
   {:item/slug "b" :item/name "Bravo" :item/price 250
    :item/images [{:image/url "/images/36.png"}] :item/tagories [{:tagory/name "two"}]}
   {:item/slug "c" :item/name "Charlie" :item/price 300
    :item/images [{:image/url "/images/37.png"}] :item/tagories []}])


(defn- fixture-store
  ([] (fixture-store {}))
  ([opts]
   (let [dir (sup/temp-dir)
         path (str (io/file dir "catalog.edn"))]
     (spit path (pr-str {:store/name "T" :items three}))
     (assoc (store/open (merge {:path path :seed path} opts)) :test-path path))))


(deftest catalog-reads-go-through-q-and-collect
  (let [ds (:datoms (transact/prepare-tx {:base-datoms []
                                          :tx-data (into catalog/schema-tx (catalog/items->tx three))}))
        calls (atom 0)
        real-q query/q
        real-collect query/collect
        collects (atom 0)]
    (with-redefs [query/q (fn [& a] (swap! calls inc) (apply real-q a))
                  query/collect (fn [r] (swap! collects inc) (real-collect r))]
      (let [items (catalog/items (catalog/db ds))]
        (is (= ["Alpha" "Bravo" "Charlie"] (map :name items)))
        (is (= ["/images/7..png" "/images/8..png"] (:images (first items))))
        (is (= ["one" "two"] (:tagories (first items))))
        (is (pos? @calls))
        (is (= @calls @collects) "every q result is collected")))))


(deftest the-checked-in-seed-boots
  (let [data (store/read-catalog seed)
        st (store/open {:seed seed})
        items (store/items st)
        named (remove #(sup/blank? (:name %)) items)]
    (is (= 249 (count (:items data))))
    (is (= 249 (count items)))
    (is (= 2 (- (count items) (count named))) "two empty placeholders sit in the source data")
    (is (= 247 (count named)))
    (is (every? #(and (integer? (:price %)) (seq (:images %))) named))
    (is (every? #(.isFile (io/file asset-root (subs (first (:images %)) 1))) (take 20 named))
        "image urls name files on disk")))


(defn- apply! [st slug changes] (fx/apply-edit! st asset-root {:item slug :changes changes}))


(deftest edits-transact-and-queries-see-them
  (let [st (fixture-store)]
    (is (= {:saved "a"} (apply! st "a" {:name " Alpha 2 " :price "199" :description "new"})))
    (let [a (store/item st "a")]
      (is (= "Alpha 2" (:name a)))
      (is (= 199 (:price a)))
      (is (= "new" (:description a))))))


(deftest tagory-add-and-remove
  (let [st (fixture-store)
        with (fn [n] (catalog/items-with-tagory (store/db st) n))]
    (is (= #{"a" "b"} (with "two")) "a multi-tagory item and a shared tagory")
    (is (= {:saved "c"} (apply! st "c" {:add-tagory "fresh"})))
    (is (= #{"c"} (with "fresh")))
    (is (= {:saved "c"} (apply! st "c" {:add-tagory "two"})))
    (is (= #{"a" "b" "c"} (with "two")))
    (is (= {:saved "a"} (apply! st "a" {:remove-tagory "two"})))
    (is (= #{"b" "c"} (with "two")))
    (is (= ["one"] (:tagories (store/item st "a"))))
    (is (= {:rejected "tagory is not on the item"} (apply! st "a" {:remove-tagory "two"})))))


(deftest image-add-and-remove
  (let [st (fixture-store)]
    (is (= {:saved "b"} (apply! st "b" {:add-image "/images/7..png"})))
    (is (= ["/images/36.png" "/images/7..png"] (:images (store/item st "b"))))
    (is (= {:saved "b"} (apply! st "b" {:remove-image "/images/36.png"})))
    (is (= ["/images/7..png"] (:images (store/item st "b"))))
    (testing "the other item sharing no entity is untouched"
      (is (= ["/images/7..png" "/images/8..png"] (:images (store/item st "a")))))))


(deftest validation-rejects-and-leaves-the-db-unchanged
  (let [st (fixture-store)
        before @(:datoms st)]
    (doseq [[label changes]
            {"negative price" {:price -5}
             "fractional price" {:price "1.5"}
             "huge price" {:price 1000000000000}
             "empty name" {:name "   "}
             "oversized name" {:name (apply str (repeat 201 "x"))}
             "oversized description" {:description (apply str (repeat 2001 "x"))}
             "image outside /images/" {:add-image "/etc/passwd"}
             "image traversal" {:add-image "/images/../catalog.edn"}
             "image not on disk" {:add-image "/images/nope.png"}
             "image not an image path" {:add-image "http://example.com/x.png"}
             "empty tagory" {:add-tagory ""}
             "oversized tagory" {:add-tagory (apply str (repeat 81 "t"))}}]
      (let [r (apply! st "a" changes)]
        (is (contains? r :rejected) label)))
    (is (contains? (fx/apply-edit! st asset-root {:item "zzz" :changes {:price 1}}) :rejected))
    (is (identical? before @(:datoms st)) "nothing was transacted")))


(deftest persistence-round-trips-through-the-written-file
  (let [st (fixture-store)]
    (apply! st "a" {:price 777})
    (apply! st "a" {:add-tagory "persisted"})
    (let [written (store/read-catalog (:test-path st))
          fresh (store/open {:seed (:test-path st)})]
      (is (= 3 (count (:items written))))
      (is (= 777 (:item/price (first (filter #(= "a" (:item/slug %)) (:items written))))))
      (is (= (store/items st) (store/items fresh)) "a fresh store sees the edit")
      (is (= (catalog/item-maps (store/db st)) (:items written)) "the file is the item maps"))))


(deftest a-failed-write-leaves-the-old-file-intact
  (let [st (fixture-store)
        old (slurp (:test-path st))
        failing (assoc st :write-fn (fn [_ _] (throw (ex-info "disk full" {}))))]
    (is (map? (apply! failing "a" {:price 5})) "the edit itself stands in memory")
    (is (= 5 (:price (store/item failing "a"))))
    (testing "the real writer never exposes a partial file"
      (let [target (str (:test-path st) ".x")]
        (spit target "OLD")
        (is (thrown? Exception (with-redefs [spit (fn [& _] (throw (java.io.IOException. "boom")))]
                                 (store/write-atomically! target "NEW"))))
        (is (= "OLD" (slurp target)))))
    (is (= old (slurp (:test-path st))) "the failing writer was injected, the file untouched")))


(deftest an-unwritable-path-logs-and-the-store-keeps-working
  (let [st (store/open {:path "/nonexistent-dir/catalog.edn" :seed seed
                        :write-fn store/write-atomically!})]
    (is (= 249 (count (store/items st))))
    (is (= {:saved "p4-pro-game-console"}
           (fx/apply-edit! st asset-root {:item "p4-pro-game-console" :changes {:price 1}})))
    (is (= 1 (:price (store/item st "p4-pro-game-console"))))))


(deftest concurrent-saves-both-survive
  (dotimes [_ 20]
    (let [st (fixture-store)
          go (java.util.concurrent.CountDownLatch. 1)
          t (fn [slug price]
              (doto (Thread. (fn [] (.await go) (apply! st slug {:price price}))) (.start)))
          ts [(t "a" 11) (t "b" 22) (t "c" 33)]]
      (.countDown go)
      (doseq [^Thread x ts] (.join x 5000))
      (is (= [11 22 33] (mapv #(:price (store/item st %)) ["a" "b" "c"]))))))
