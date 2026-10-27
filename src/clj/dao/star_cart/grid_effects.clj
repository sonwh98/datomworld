(ns dao.star-cart.grid-effects
  "The grid's stepped effects interpreter.  A peer observer of each grid
   session's view medium (its own cursor, beside the driver's): when a model
   carries `{:edit {:item slug :changes {...}}}` it validates the change, runs
   the transact through the store, and appends a `{:saved slug}` or
   `{:rejected reason}` confirmation to that session's routed input medium.  The
   program therefore renders `saving` until the transact has completed.

   Validation is here because signals are the client's unverified claim:
   name 1-200 characters; price an integer 0..100000000 (minor units);
   description at most 2000; an image URL an existing file under `/images/`;
   a tagory 1-80 characters."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [dao.star-cart.catalog :as catalog]
            [dao.star-cart.edge :as edge]
            [dao.star-cart.store :as store]
            [dao.star.driver :as driver]
            [dao.star.lease :as lease]
            [dao.stream :as stream])
  (:import [java.nio.file Paths]))


(def max-name 200)
(def max-description 2000)
(def max-tagory 80)
(def max-price 100000000)


(def ^:private retract-meta {:db/op :db/retract})


(defn- reject!
  [reason]
  (throw (ex-info "rejected" {::rejected reason})))


(defn- parse-price
  [p]
  (cond (and (integer? p) (not (neg? p))) p
        (and (string? p) (re-matches #"\d{1,12}" (str/trim p))) (Long/parseLong (str/trim p))
        :else (reject! "price must be a whole number of cents, zero or more")))


(defn- check-name
  [n]
  (when-not (and (string? n) (<= 1 (count (str/trim n)) max-name))
    (reject! (str "name must be 1-" max-name " characters")))
  (str/trim n))


(defn- check-image
  "The canonical DECODED image path.  An encoded spelling (`%20`) is decoded
   once here, so the catalog stores the file's real path and the view encodes it
   exactly once."
  [root url]
  (when-not (and (string? url) (<= (count url) 300) (str/starts-with? url "/images/"))
    (reject! "image must be an /images/ path"))
  (let [decoded (or (edge/decode-path url) (reject! "image path is malformed"))]
    (when-not (re-matches #"/images/[^/]+" decoded)
      ;; single segment under /images/, so dot-segment and repeated-separator
      ;; aliases (`/images/./x`, `/images/a/../b`, `/images//x`) are refused
      ;; before storage and duplicate comparison (review LOW, 2026-10-27)
      (reject! "image path aliases are not accepted"))
    (when-not (edge/static-file root decoded)
      (reject! "image file does not exist"))
    decoded))


(defn- check-tagory
  [n]
  (when-not (and (string? n) (<= 1 (count (str/trim n)) max-tagory))
    (reject! (str "tagory must be 1-" max-tagory " characters")))
  (str/trim n))


(defn- edit-tx
  "tx-data for `changes` on item `slug` under `db`, or a rejection."
  [root db slug changes]
  (let [eid (or (catalog/item-eid db slug) (reject! "no such item"))
        current (catalog/item db slug)
        ;; an unchanged value is not transacted: re-asserting a card-one
        ;; value would retract and assert the same datom in one tx
        changed (fn [k v] (when (not= v (get current k)) v))]
    (vec
      (concat
        (when (some? (:name changes))
          (when-some [n (changed :name (check-name (:name changes)))]
            [[:db/add eid :item/name n]]))
        (when (some? (:price changes))
          (let [p (parse-price (:price changes))]
            (when-not (<= 0 p max-price) (reject! "price out of range"))
            (when-some [p' (changed :price p)]
              [[:db/add eid :item/price p']])))
        (when (some? (:description changes))
          (let [d (:description changes)]
            (when-not (and (string? d) (<= (count d) max-description))
              (reject! (str "description must be at most " max-description " characters")))
            (when-some [d' (changed :description d)]
              [[:db/add eid :item/description d']])))
        (when (contains? changes :add-image)
          (let [u (check-image root (:add-image changes))]
            (when (catalog/image-eid db slug u) (reject! "image already on the item"))
            [{:db/id "tid_new_image" :image/url u}
             [:db/add eid :item/images "tid_new_image"]]))
        (when (contains? changes :remove-image)
          (let [i (or (catalog/image-eid db slug (:remove-image changes))
                      (reject! "image is not on the item"))]
            [[:db/retract eid :item/images i retract-meta]]))
        (when (contains? changes :add-tagory)
          (let [n (check-tagory (:add-tagory changes))]
            (when (catalog/tagory-linked? db slug n) (reject! "tagory already on the item"))
            (if-let [t (catalog/tagory-eid db n)]
              [[:db/add eid :item/tagories t]]
              [{:db/id "tid_new_tagory" :tagory/name n}
               [:db/add eid :item/tagories "tid_new_tagory"]])))
        (when (contains? changes :remove-tagory)
          (let [n (:remove-tagory changes)
                t (when (catalog/tagory-linked? db slug n) (catalog/tagory-eid db n))]
            (when-not t (reject! "tagory is not on the item"))
            [[:db/retract eid :item/tagories t retract-meta]]))))))


(defn apply-edit!
  "Validate and transact `edit` (`{:item slug :changes {...}}`).  Answers
   `{:saved slug}` or `{:rejected reason}`; a rejection leaves the db unchanged."
  [store' asset-root {:keys [item changes]}]
  (let [root (.toAbsolutePath (.normalize (Paths/get (str asset-root) (make-array String 0))))]
    (try
      (when-not (and (string? item) (map? changes))
        (reject! "malformed edit"))
      (store/transact-edit! store' (fn [db] (edit-tx root db item changes)))
      {:saved item}
      (catch clojure.lang.ExceptionInfo e
        (if-let [r (::rejected (ex-data e))]
          {:rejected r}
          {:rejected (str "edit failed: " (.getMessage e))}))
      (catch Exception e
        {:rejected (str "edit failed: " (.getMessage e))}))))

(defn- slugify
  "A lowercase dash slug of `name`; empty names become \"item\".  A taken
   slug gets -2, -3, … appended (reviewed convention: uniqueness here, not in
   the db)."
  [db name']
  (let [base (let [s (clojure.string/replace
                       (clojure.string/replace (clojure.string/lower-case (str name'))
                                               #"[^a-z0-9]+" "-")
                       #"^-+|-+$" "")]
               (if (clojure.string/blank? s) "item" s))]
    (loop [s base n 2]
      (if (catalog/item-eid db s) (recur (str base "-" n) (inc n)) s))))

(defn apply-new!
  "Validate and transact a new item (`{:new {:name .. :price .. :description ..}}`).
   The slug is derived here — the VM program cannot do string work — and is
   captured inside the transact function, so the confirmed slug is the one
   actually stored.  Answers `{:saved slug}` or `{:rejected reason}`."
  [store' {:keys [new]}]
  (try
    (when-not (map? new) (reject! "malformed new item"))
    (let [n (check-name (:name new))
          p (parse-price (:price new))
          d (let [d' (str (:description new))]
              (when (> (count d') max-description)
                (reject! (str "description must be at most " max-description " characters")))
              d')
          slug (volatile! nil)]
      (store/transact-edit!
        store'
        (fn [db]
          (let [s (slugify db n)]
            (vreset! slug s)
            [{:db/id "tid_new_item" :item/slug s :item/name n :item/price p :item/description d}])))
      {:saved @slug})
    (catch clojure.lang.ExceptionInfo e
      (if-let [r (::rejected (ex-data e))]
        {:rejected r}
        {:rejected (str "create failed: " (.getMessage e))}))
    (catch Exception e
      {:rejected (str "create failed: " (.getMessage e))})))


(defn make-effects
  "`{:driver grid-driver :store store :asset-root path}`."
  [config]
  (atom (assoc config :cursors {})))


(defn- confirm!
  [drv s result]
  (when-let [medium (driver/session-medium drv s)]
    (stream/append! medium
                    {:exchange nil :kind :input :lineage s
                     :token (:occurrence (driver/latest drv s))
                     :event {:http/method :post
                             :http/body (json/write-str
                                          (if-let [slug (:saved result)]
                                            {:saved slug}
                                            {:rejected (:rejected result)}))}})))


(defn effects-step!
  "One pass: read every grid session's view medium with this interpreter's own
   cursor, perform each `:edit`, confirm.  Answers the effects atom."
  [fx]
  (let [{:keys [driver store asset-root]} @fx]
    (doseq [s (driver/sessions driver)
            :let [out (get-in @driver [:sessions s :vm-out])]
            :when out]
      (let [cursor (or (get-in @fx [:cursors s]) (lease/oldest-cursor out))
            r (lease/drain out cursor)]
        (swap! fx assoc-in [:cursors s] (:cursor r))
        (doseq [model (:values r)
                :when (map? model)]
          ;; The grid emit's fourth argument lands on the model under :edit;
          ;; a create emits {:new {...}} as that argument, so unwrap it here.
          (let [effect (:edit model)]
            (cond
              (:new effect) (confirm! driver s (apply-new! store (:new effect)))
              (map? effect) (confirm! driver s (apply-edit! store asset-root effect)))))
        fx))))
