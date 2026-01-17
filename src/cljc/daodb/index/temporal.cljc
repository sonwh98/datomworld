(ns daodb.index.temporal
  (:require [daodb.primitives :as p]
            [daodb.index :as index]))

(defprotocol ITemporalIndex
  (add-datom [this datom] "Appends a datom to the stream.")
  (range-seek [this criteria] "Returns datoms matching criteria (scan).")
  (as-of [this t] "Returns state as of time t."))

;; Simple Vector-based implementation
(defrecord VectorTemporalIndex [log]
  daodb.index/IIndex
  (index-tx [this tx-datoms]
    (assoc this :log (into log tx-datoms)))

  ITemporalIndex
  (add-datom [this datom]
    (assoc this :log (conj log datom)))

  (range-seek [this {:keys [e a v]}]
    ;; Naive scan for prototype
    (filter (fn [d]
              (and (or (nil? e) (= (:e d) e))
                   (or (nil? a) (= (:a d) a))
                   (or (nil? v) (= (:v d) v))))
            log))

  (as-of [this t]
    (filter #(<= (:t %) t) log)))

(defn create []
  (->VectorTemporalIndex []))
