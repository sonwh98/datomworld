(ns dao.star-cart.store
  "The mutable catalog db: an atom holding the accumulated datom vector.  Boot
   loads `catalog.edn` through `transact/prepare-tx` (schema block first); an
   edit runs `prepare-tx` inside a `swap!`, so concurrent editors cannot lose
   each other's updates.  Every save snapshots the catalog back to its file as
   plain item maps (temp file + atomic rename).  A path that cannot be written
   logs and the store carries on in memory."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dao.space.transact :as transact]
            [dao.star-cart.catalog :as catalog])
  (:import [java.nio.file Files StandardCopyOption Paths]))


(defn- log
  [& xs]
  (binding [*out* *err*] (println (apply str "star-cart: " xs))))


(defn read-catalog
  "The catalog file: `{:store/name .. :items [item-map ...]}`."
  [path]
  (edn/read-string (slurp path)))


(defn- boot-datoms
  [item-maps]
  (:datoms (transact/prepare-tx
             {:base-datoms []
              :tx-data (into catalog/schema-tx (catalog/items->tx item-maps))})))


(defn write-atomically!
  "Write `content` to `path` through a sibling temp file and an atomic rename.
   The old file is untouched if the write fails before the rename."
  [path content]
  (io/make-parents (io/file path))
  (let [target (Paths/get path (make-array String 0))
        dir (or (.getParent target) (Paths/get "." (make-array String 0)))
        tmp (Files/createTempFile dir "catalog" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (.toFile tmp) content)
      (Files/move tmp target (into-array java.nio.file.CopyOption
                                         [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (catch Throwable t
        (Files/deleteIfExists tmp)
        (throw t)))))


(defn open
  "Open the store.  `path` is where saves go (the seed is copied there on first
   boot when the path is empty and writable), `seed` the baked catalog file.
   `:write-fn` (default `write-atomically!`) is injectable for tests."
  [{:keys [path seed write-fn] :or {write-fn write-atomically!}}]
  (let [source (cond (and path (.exists (io/file path))) path
                     :else seed)
        data (read-catalog source)
        store {:path path :write-fn write-fn :store-name (:store/name data)
               :lock (Object.)
               :datoms (atom (boot-datoms (:items data)))
               :cache (atom nil)}]
    (when-not path
      (log "no catalog path: edits are held in MEMORY ONLY and are lost on exit"))
    (when (and path (not= source path))
      (try (write-fn path (slurp seed))
           (catch Throwable t (log "cannot seed " path ": " (.getMessage t) "; continuing in memory"))))
    store))


(defn db
  [store]
  (catalog/db @(:datoms store)))


(defn items
  "All items, cached per datom value: a read after an edit recomputes once."
  [store]
  (let [ds @(:datoms store)
        [cached-ds cached] @(:cache store)]
    (if (identical? ds cached-ds)
      cached
      (let [result (catalog/items (catalog/db ds))]
        (reset! (:cache store) [ds result])
        result))))


(defn item
  [store slug]
  (first (filter #(= slug (:key %)) (items store))))


(defn catalog-fns
  "The pure view's catalog: `:items` and `:item`, each reading the store fresh."
  [store]
  {:items (fn [] (items store))
   :item (fn [slug] (item store slug))
   :tagories (fn [] (catalog/tagories (db store)))})


(defn snapshot!
  "Rewrite the catalog file from a fresh query.  Failure logs and is swallowed:
   the edit stands in memory."
  [store]
  (when-let [path (:path store)]
    (try
      ((:write-fn store) path
                         (pr-str {:store/name (:store-name store)
                                  :items (catalog/item-maps (db store))}))
      true
      (catch Throwable t
        (log "cannot save " path ": " (.getMessage t) "; continuing in memory")
        false))))


#_{:clj-kondo/ignore [:locking-suspicious-lock]}


(defn transact-edit!
  "Apply an edit.  `tx-fn` receives the current db value and answers tx-data (or
   throws, aborting the swap).  `prepare-tx` runs inside the `swap!`.  On
   success the snapshot is saved and the new datom vector answered."
  [store tx-fn]
  ;; The update and its snapshot are one critical section: snapshots are then
  ;; written in the order the updates were applied, so a later rename can
  ;; never carry an older state.
  (locking (:lock store)
    (let [new-ds (swap! (:datoms store)
                        (fn [ds]
                          (let [tx-data (tx-fn (catalog/db ds))]
                            (if (empty? tx-data)
                              ds
                              (into ds (:datoms (transact/prepare-tx
                                                  {:base-datoms ds :tx-data tx-data})))))))]
      (snapshot! store)
      new-ds)))
