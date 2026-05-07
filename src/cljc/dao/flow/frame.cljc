(ns dao.flow.frame
  "Vocabulary of datoms that compose a rendered frame.

  A frame is a vector of op-datoms put onto the primitive-stream by a graphics
  producer and consumed by a renderer backend (Flutter, WebGPU, SVG, etc.).
  Each op-datom carries an :op/kind keyword that the backend dispatches on.
  Every frame ends with an :end-frame sentinel.

  Producers (dao.flow.graphics) build frames from these constructors.
  Consumers (dao.flow.flutter, future GPU backends) read :op/kind and
  interpret the remaining keys.")


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
