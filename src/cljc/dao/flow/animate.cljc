(ns dao.flow.animate
  (:require
    [dao.stream :as ds]))


(defn emit-attr-change!
  "Emits a transaction to change an attribute on an entity onto the intent stream."
  [stream eid attr val]
  (ds/put! stream [[:db/add eid attr val]]))


(defn rotation-tick
  "A helper to emit a rotation mutation for a transform entity."
  [stream transform-eid new-rotation]
  (emit-attr-change! stream transform-eid :transform/rotate new-rotation))
