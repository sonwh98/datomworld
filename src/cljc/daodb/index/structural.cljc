(ns daodb.index.structural
  (:require
    [daodb.index :as index]
    [daodb.primitives :as p]))


(defprotocol IStructuralLattice

  (put-fact
    [this e a v]
    "Stores content, returns hash.")

  (put-meta
    [this h meta-map]
    "Attaches metadata to a fact hash.")

  (get-content
    [this h]
    "Retrieves [e a v] by hash.")

  (get-meta
    [this h]
    "Retrieves all metadata maps for hash."))


(defrecord MemoryStructuralLattice
  [content store-meta]

  daodb.index/IIndex

  (index-tx
    [this tx-datoms]
    (reduce (fn [idx {:keys [e a v t m]}]
              (let [h (p/hash-datom-content e a v)
                    ;; 1. Update content if new
                    idx-with-content (if (get (:content idx) h)
                                       idx
                                       (assoc-in idx [:content h] [e a v]))
                    ;; 2. Update metadata
                    current-metas
                    (get-in idx-with-content [:store-meta h] #{})
                    new-meta-map {:t t, :m m}]
                (assoc-in idx-with-content
                          [:store-meta h]
                          (conj current-metas new-meta-map))))
            this
            tx-datoms))


  IStructuralLattice

  (put-fact
    [this e a v]
    (let [h (p/hash-datom-content e a v)]
      (if (get content h)
        ;; Dedup hit
        [this h false]
        ;; New fact
        [(assoc this :content (assoc content h [e a v])) h true])))


  (put-meta
    [this h meta-map]
    (let [current-metas (get store-meta h #{})]
      (assoc this
             :store-meta (assoc store-meta h (conj current-metas meta-map)))))


  (get-content [this h] (get content h))


  (get-meta [this h] (get store-meta h)))


(defn create
  []
  (->MemoryStructuralLattice {} {}))
