(ns yin.vm.key
  "Normalize exact integer carriers in collection keys."
  (:require [dao.host.integer :as integer]
            [dao.jing.cbor :as cbor]))

(defn normalize [x]
  (cond
    (integer/exact-integer? x) (integer/demote (integer/promote x))
    (or (cbor/float64? x)
        (and (map? x) (contains? #{:stream-ref :cursor-ref :cell-ref} (:type x)))) x
    (vector? x) (mapv normalize x)
    (map? x) (into (empty x) (map (fn [[k v]] [(normalize k) (normalize v)])) x)
    (set? x) (into (empty x) (map normalize) x)
    :else x))
