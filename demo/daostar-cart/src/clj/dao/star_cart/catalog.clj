(ns dao.star-cart.catalog
  "Catalog reads and the catalog's datom shape.  Every read is
   `dao.space.query/q` + `collect` over a db value (a `current` view over a
   `relation`); nothing here touches a store or an atom."
  (:require [clojure.string :as str]
            [dao.space.query :as query]))


(def schema-tx
  "Schema first: images and tagories are many-valued refs."
  [{:db/ident :item/images :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   {:db/ident :item/tagories :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}])


(defn db
  "The db value over accumulated datoms."
  [datoms]
  (query/current (query/relation datoms)))


(defn items->tx
  "tx-data for plain item maps (the on-disk shape): one entity per item, one
   per image, one per distinct tagory name, refs by tempid."
  [item-maps]
  (let [indexed (map-indexed vector item-maps)
        tagories (distinct (for [[_ m] indexed t (:item/tagories m)] (:tagory/name t)))
        ;; one tempid per distinct name, allocated by position: collision-free
        ;; where a hash of the name is not ("Aa" and "BB" hash alike)
        tagory-tid (zipmap tagories (map #(str "tid_tag_" %) (range)))]
    (vec
      (concat
        (for [n tagories] {:db/id (tagory-tid n) :tagory/name n})
        (mapcat
          (fn [[i m]]
            (let [img-tids (map-indexed (fn [j _] (str "tid_img_" i "_" j)) (:item/images m))]
              (concat
                (map (fn [tid img] {:db/id tid :image/url (:image/url img)}) img-tids (:item/images m))
                [(cond-> {:db/id (str "tid_item_" i)
                          :item/slug (:item/slug m)
                          :item/name (:item/name m)
                          :item/price (:item/price m)
                          :item/images (vec img-tids)
                          :item/tagories (mapv (comp tagory-tid :tagory/name) (:item/tagories m))}
                   (:item/description m) (assoc :item/description (:item/description m)))])))
          indexed)))))


(defn- rows
  [db' q-form]
  (query/collect (query/q q-form db')))


(defn- by-slug
  [db' q-form]
  (into {} (rows db' q-form)))


(defn items
  "Every item as `{:key :name :price :description :images [url] :tagories [name]}`,
   sorted by trimmed lower-case name.  Image order is the order they were added."
  [db']
  (let [names (by-slug db' '[:find ?s ?n :where [?e :item/slug ?s] [?e :item/name ?n]])
        prices (by-slug db' '[:find ?s ?p :where [?e :item/slug ?s] [?e :item/price ?p]])
        descs (by-slug db' '[:find ?s ?d :where [?e :item/slug ?s] [?e :item/description ?d]])
        imgs (group-by first
                       (sort-by second
                                (rows db' '[:find ?s ?i ?u :where [?e :item/slug ?s]
                                            [?e :item/images ?i] [?i :image/url ?u]])))
        tags (group-by first
                       (rows db' '[:find ?s ?n :where [?e :item/slug ?s]
                                   [?e :item/tagories ?t] [?t :tagory/name ?n]]))]
    (->> names
         (map (fn [[slug n]]
                {:key slug :name n :price (get prices slug) :description (get descs slug "")
                 :images (mapv #(nth % 2) (get imgs slug))
                 :tagories (vec (sort (map second (get tags slug))))}))
         (sort-by #(str/lower-case (str/trim (:name %))))
         vec)))


(defn item
  [db' slug]
  (first (filter #(= slug (:key %)) (items db'))))


(defn tagories
  "The distinct tagory names, sorted."
  [db']
  (vec (sort (map first
                  (query/collect (query/q '[:find ?n :where [?t :tagory/name ?n]] db'))))))


(defn item-eid
  "The entity id of the item with `slug`, or nil."
  [db' slug]
  (ffirst (query/collect (query/q '[:find ?e :in $ ?s :where [?e :item/slug ?s]] db' slug))))


(defn image-eid
  "The image entity of `url` on item `slug`, or nil."
  [db' slug url]
  (ffirst (query/collect
            (query/q '[:find ?i :in $ ?s ?u :where [?e :item/slug ?s]
                       [?e :item/images ?i] [?i :image/url ?u]]
                     db' slug url))))


(defn tagory-eid
  "The tagory entity named `name`, or nil."
  [db' name]
  (ffirst (query/collect (query/q '[:find ?t :in $ ?n :where [?t :tagory/name ?n]] db' name))))


(defn tagory-linked?
  [db' slug name]
  (boolean (seq (query/collect
                  (query/q '[:find ?t :in $ ?s ?n :where [?e :item/slug ?s]
                             [?e :item/tagories ?t] [?t :tagory/name ?n]]
                           db' slug name)))))


(defn items-with-tagory
  "Slugs of the items carrying tagory `name`."
  [db' name]
  (set (map first (query/collect
                    (query/q '[:find ?s :in $ ?n :where [?t :tagory/name ?n]
                               [?e :item/tagories ?t] [?e :item/slug ?s]]
                             db' name)))))


(defn item-maps
  "The on-disk shape: plain item maps with nested image and tagory maps."
  [db']
  (mapv (fn [i]
          (cond-> {:item/slug (:key i) :item/name (:name i) :item/price (:price i)
                   :item/images (mapv (fn [u] {:image/url u}) (:images i))
                   :item/tagories (mapv (fn [n] {:tagory/name n}) (:tagories i))}
            (seq (:description i)) (assoc :item/description (:description i))))
        (items db')))
