(ns dao.flow.ops)


(defn op-end-frame
  []
  {:op/kind :end-frame})


(defn op-light
  [light-ent]
  (let [{:keys [light/kind light/color light/intensity light/direction]}
        light-ent]
    {:op/kind :light,
     :op/light-kind kind,
     :op/color color,
     :op/intensity intensity,
     :op/direction direction}))


(defn op-viewport-push
  [rect camera-ent]
  {:op/kind :viewport-push, :op/rect rect, :op/camera camera-ent})


(defn op-viewport-pop
  []
  {:op/kind :viewport-pop})


(defn geom->op
  [kind attrs world proj depth source-eid]
  (merge {:op/kind kind,
          :op/world world,
          :op/projected proj,
          :op/depth depth,
          :op/source-eid source-eid}
         attrs))
