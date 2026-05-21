(ns dao.postgraphics.webgpu
  (:require
    [dao.postgraphics.terminal :as terminal]))


(def ^:private epsilon 1.0e-6)

(def ^:private target-formats #{:rgba8unorm :bgra8unorm :rgba16f :rgba32f})


(defn- finite-number?
  [x]
  (and (number? x) (js/isFinite x)))


(defn- vecn?
  [n v]
  (and (vector? v) (= n (count v)) (every? finite-number? v)))


(defn- vec2?
  [v]
  (vecn? 2 v))


(defn- vec3?
  [v]
  (vecn? 3 v))


(defn- vec4?
  [v]
  (vecn? 4 v))


(defn- in-unit-interval?
  [x]
  (and (finite-number? x) (<= 0.0 x 1.0)))


(defn- valid-color?
  [c]
  (and (vec4? c) (every? in-unit-interval? c)))


(defn- valid-light-color?
  [c]
  (and (vec3? c) (every? in-unit-interval? c)))


(defn- positive-rect?
  [r]
  (and (vec4? r) (> (double (nth r 2)) 0.0) (> (double (nth r 3)) 0.0)))


(defn- positive-size?
  [s]
  (and (vec2? s) (> (double (nth s 0)) 0.0) (> (double (nth s 1)) 0.0)))


(defn- reject!
  ([message] (reject! :validation-failure message))
  ([reason message]
   (throw (ex-info (str "[" (name reason) "] " message)
                   {:dao.postgraphics/reason reason}))))


(defn- check-color!
  [op k]
  (when-let [c (get op k)]
    (when-not (valid-color? c)
      (reject! (str k " must be [r g b a] with each component in [0, 1]")))))


(defn- identity-affine
  []
  {:m00 1.0,
   :m01 0.0,
   :m10 0.0,
   :m11 1.0,
   :tx 0.0,
   :ty 0.0,
   :affine-2d? true,
   :translate-only? true})


(defn- approx-zero?
  [x]
  (< (js/Math.abs (double x)) epsilon))


(defn- approx-one?
  [x]
  (< (js/Math.abs (- (double x) 1.0)) epsilon))


(defn- affine-translate-only?
  [{:keys [m00 m01 m10 m11]}]
  (and (approx-one? m00)
       (approx-zero? m01)
       (approx-zero? m10)
       (approx-one? m11)))


(defn- affine
  [m00 m01 m10 m11 tx ty]
  (let [m {:m00 (double m00),
           :m01 (double m01),
           :m10 (double m10),
           :m11 (double m11),
           :tx (double tx),
           :ty (double ty),
           :affine-2d? true}]
    (assoc m :translate-only? (affine-translate-only? m))))


(defn- matrix-valid-numbers?
  [m n]
  (and (vector? m) (= n (count m)) (every? finite-number? m)))


(defn- matrix-op->affine
  [mat]
  (cond (matrix-valid-numbers? mat 9) (let [[m00 m01 tx m10 m11 ty m20 m21 m22]
                                            mat]
                                        (when-not (and (approx-zero? m20)
                                                       (approx-zero? m21)
                                                       (approx-one? m22))
                                          (reject! "2D matrix must be affine"))
                                        (affine m00 m01 m10 m11 tx ty))
        (matrix-valid-numbers? mat 16)
        (let [m00 (nth mat 0)
              m10 (nth mat 1)
              z0 (nth mat 2)
              w0 (nth mat 3)
              m01 (nth mat 4)
              m11 (nth mat 5)
              z1 (nth mat 6)
              w1 (nth mat 7)
              z2 (nth mat 10)
              tx (nth mat 12)
              ty (nth mat 13)
              zt (nth mat 14)
              wt (nth mat 15)]
          (when-not (and (approx-zero? z0)
                         (approx-zero? w0)
                         (approx-zero? z1)
                         (approx-zero? w1)
                         (approx-one? z2)
                         (approx-zero? zt)
                         (approx-one? wt))
            (reject! "4x4 matrix must be 2D-affine for 2D WebGPU lowering"))
          (affine m00 m01 m10 m11 tx ty))
        :else (reject! "matrix must be a 9- or 16-element numeric vector")))


(defn- op->affine
  [op]
  (if-let [mat (:matrix op)]
    (matrix-op->affine mat)
    (let [[sx sy] (or (:scale op) [1.0 1.0])
          [tx ty] (or (:translate op) [0.0 0.0])
          rz (let [r (:rotate op)]
               (cond (nil? r) 0.0
                     (finite-number? r) r
                     (vec3? r) (nth r 2)
                     :else (reject! "rotate must be a number or [rx ry rz]")))
          c (js/Math.cos rz)
          s (js/Math.sin rz)]
      (when-not (and (vec2? [sx sy]) (vec2? [tx ty]))
        (reject! "translate and scale must be numeric vectors"))
      (affine (* c sx) (* (- s) sy) (* s sx) (* c sy) tx ty))))


(defn- compose-affine
  [a b]
  (affine (+ (* (:m00 a) (:m00 b)) (* (:m01 a) (:m10 b)))
          (+ (* (:m00 a) (:m01 b)) (* (:m01 a) (:m11 b)))
          (+ (* (:m10 a) (:m00 b)) (* (:m11 a) (:m10 b)))
          (+ (* (:m10 a) (:m01 b)) (* (:m11 a) (:m11 b)))
          (+ (* (:m00 a) (:tx b)) (* (:m01 a) (:ty b)) (:tx a))
          (+ (* (:m10 a) (:tx b)) (* (:m11 a) (:ty b)) (:ty a))))


(defn- transform-point
  [{:keys [m00 m01 m10 m11 tx ty]} [x y]]
  [(+ (* m00 x) (* m01 y) tx) (+ (* m10 x) (* m11 y) ty)])


(defn- transformed-rect
  [m [x y w h]]
  (let [points [(transform-point m [x y]) (transform-point m [(+ x w) y])
                (transform-point m [(+ x w) (+ y h)])
                (transform-point m [x (+ y h)])]
        xs (map first points)
        ys (map second points)
        min-x (apply min xs)
        max-x (apply max xs)
        min-y (apply min ys)
        max-y (apply max ys)]
    [min-x min-y (- max-x min-x) (- max-y min-y)]))


(defn- cart-rect->screen
  [[x y w h] viewport-height]
  [x (- viewport-height (+ y h)) w h])


(defn- resolve-clip-rect
  [m rect viewport-height]
  (when-not (:translate-only? m)
    (reject! "clip/push-rect requires translate-only ancestry"))
  (let [[x y w h] rect]
    [(+ x (:tx m)) (- viewport-height (+ y (:ty m) h)) w h]))


(defn- target-source?
  [source]
  (and (vector? source)
       (= [:target/id] (subvec source 0 1))
       (= 2 (count source))))


(defn- target-source-id
  [source]
  (nth source 1))


(defn- check-target-source!
  [source target-stack produced-targets]
  (when (target-source? source)
    (let [target-id (target-source-id source)
          active-id (:id (first target-stack))]
      (when (= active-id target-id)
        (reject! "A render target cannot sample from itself while active"))
      (when-not (contains? produced-targets target-id)
        (reject! "Texture source refers to a missing render target")))))


(defn- validate-target-push!
  [op produced-targets]
  (let [target-id (:target/id op)
        size (:target/size op)
        format (get op :target/format :rgba8unorm)]
    (when-not target-id (reject! "target/push missing :target/id"))
    (when (contains? produced-targets target-id)
      (reject! "target/push reused a target id in the same frame"))
    (when-not (positive-size? size)
      (reject! "target/push :target/size must be [w h] with w>0, h>0"))
    (when-not (contains? target-formats format)
      (reject! :unsupported-op "Unsupported WebGPU render-target format"))))


(defn- validate-camera!
  [op]
  (case (:camera3d/projection op)
    :perspective
    (let [fov (:camera3d/fov op)
          near (:camera3d/near op)
          far (:camera3d/far op)
          aspect (:camera3d/aspect op)]
      (when-not (and (finite-number? fov)
                     (finite-number? near)
                     (finite-number? far))
        (reject! "Perspective camera missing numeric fov, near, or far"))
      (when-not (and (> fov 0.0) (< fov 180.0))
        (reject! "FOV must be in (0, 180)"))
      (when-not (> near 0.0) (reject! "Near must be > 0"))
      (when-not (> (- far near) epsilon) (reject! "Far must exceed near"))
      (when (and aspect (not (and (finite-number? aspect) (> aspect 0.0))))
        (reject! "Aspect must be positive")))
    :orthographic (let [l (:camera3d/left op)
                        r (:camera3d/right op)
                        b (:camera3d/bottom op)
                        t (:camera3d/top op)
                        near (:camera3d/near op)
                        far (:camera3d/far op)]
                    (when-not (every? finite-number? [l r b t near far])
                      (reject! "Orthographic camera missing numeric bounds"))
                    (when-not (> r l) (reject! "Right must be > left"))
                    (when-not (> t b) (reject! "Top must be > bottom")))
    (reject! "Unknown camera projection"))
  (when-let [pos (:camera3d/position op)]
    (when-not (vec3? pos) (reject! "camera3d/position must be vec3")))
  (when-let [rot (:camera3d/rotation op)]
    (when-not (vec3? rot) (reject! "camera3d/rotation must be vec3")))
  (when-let [vmat (:camera3d/view-matrix op)]
    (when-not (matrix-valid-numbers? vmat 16)
      (reject! "camera3d/view-matrix must be a 16-element numeric vector"))))


(defn- validate-2d-draw!
  [op model-stack target-stack produced-targets]
  (let [kind (:op/kind op)
        top (first model-stack)]
    (when-not (:affine-2d? top)
      (reject! (str "Invalid 2D affine matrix for " kind)))
    (case kind
      (:draw/fill-rect :draw/stroke-rect)
      (do (when-not (positive-rect? (:rect op))
            (reject! (str kind " :rect must be [x y w h] with w>0, h>0")))
          (check-color! op :color)
          (when-let [sw (:stroke-width op)]
            (when-not (and (finite-number? sw) (>= sw 0.0))
              (reject! ":stroke-width must be a non-negative number"))))
      (:draw/fill-circle :draw/stroke-circle)
      (do (when-not (vec2? (:center op))
            (reject! (str kind " :center must be [x y]")))
          (when-not (and (finite-number? (:radius op)) (> (:radius op) 0.0))
            (reject! (str kind " :radius must be > 0")))
          (check-color! op :color))
      :draw/path (do (when-not (vector? (:segments op))
                       (reject! "draw/path missing or invalid :segments"))
                     (check-color! op :fill)
                     (check-color! op :stroke))
      :draw/text
      (do (when-not (string? (:text op))
            (reject! "draw/text missing or invalid :text"))
          (when-not (vec2? (:position op))
            (reject! "draw/text :position must be [x y]"))
          (check-color! op :color)
          (when-let [fs (:font-size op)]
            (when-not (and (finite-number? fs) (> fs 0.0))
              (reject! "draw/text :font-size must be > 0")))
          (when-let [ff (:font-family op)]
            (when-not (string? ff)
              (reject! "draw/text :font-family must be a string")))
          (when (contains? op :align)
            (when-not (#{:start :center :end} (:align op))
              (reject! "draw/text :align must be :start, :center, or :end"))))
      :draw/image
      (do
        (when-not (positive-rect? (:rect op))
          (reject! "draw/image :rect must be [x y w h] with w>0, h>0"))
        (when-not (contains? op :image/source)
          (reject! "draw/image missing :image/source"))
        (check-target-source! (:image/source op)
                              target-stack
                              produced-targets)
        (when-let [opacity (:opacity op)]
          (when-not (in-unit-interval? opacity)
            (reject! "draw/image :opacity must be in [0, 1]")))
        (when-let [fit (:image/fit op)]
          (when-not (#{:contain :cover :fill :none} fit)
            (reject!
              "draw/image :image/fit must be :contain, :cover, :fill, or :none")))))))


(defn- validate-3d-draw!
  [op has-camera? lighting-enabled? target-stack produced-targets]
  (when-not has-camera? (reject! "3D draw without prior camera3d/set"))
  (let [kind (:op/kind op)
        verts (:vertices op)
        vertex-count (count verts)]
    (when-not (and (vector? verts) (every? vec3? verts))
      (reject! "3D draw :vertices must be a vector of vec3"))
    (case kind
      :draw3d/lines (do (check-color! op :color)
                        (when-let [edges (:edges op)]
                          (when-not (every? (fn [edge]
                                              (and (vector? edge)
                                                   (= 2 (count edge))
                                                   (every?
                                                     #(and (integer? %)
                                                           (<= 0 %)
                                                           (< % vertex-count))
                                                     edge)))
                                            edges)
                            (reject! "draw3d/lines :edges invalid"))))
      :draw3d/triangles
      (do (when-not (and (vector? (:indices op))
                         (every? (fn [tri]
                                   (and (vector? tri)
                                        (= 3 (count tri))
                                        (every? #(and (integer? %)
                                                      (<= 0 %)
                                                      (< % vertex-count))
                                                tri)))
                                 (:indices op)))
            (reject! "draw3d/triangles :indices invalid"))
          (check-color! op :fill))
      :draw3d/mesh
      (do
        (when-not (and (vector? (:indices op))
                       (every? (fn [tri]
                                 (and (vector? tri)
                                      (= 3 (count tri))
                                      (every? #(and (integer? %)
                                                    (<= 0 %)
                                                    (< % vertex-count))
                                              tri)))
                               (:indices op)))
          (reject! "draw3d/mesh :indices invalid"))
        (check-color! op :fill)
        (when (and lighting-enabled? (not (:normals op)))
          (reject! "draw3d/mesh under lighting-enabled requires :normals"))
        (doseq [[k pred label] [[:normals vec3? "vec3"] [:uvs vec2? "vec2"]
                                [:colors valid-color? "color"]]]
          (when-let [values (get op k)]
            (when-not (= vertex-count (count values))
              (reject!
                (str "draw3d/mesh " k " length must equal vertices length")))
            (when-not (every? pred values)
              (reject! (str "draw3d/mesh " k " entries must be " label)))))
        (when-let [source (:texture/source op)]
          (check-target-source! source target-stack produced-targets)
          (when-not (:uvs op)
            (reject! "draw3d/mesh with :texture/source must provide :uvs")))
        (when-let [wrap (:texture/wrap op)]
          (when-not (#{:clamp :repeat :mirror} wrap)
            (reject! "draw3d/mesh :texture/wrap invalid")))
        (when-let [filter (:texture/filter op)]
          (when-not (#{:linear :nearest} filter)
            (reject! "draw3d/mesh :texture/filter invalid")))
        (when-let [spec (:material/specular op)]
          (when-not (valid-light-color? spec)
            (reject! "draw3d/mesh :material/specular must be [r g b]")))
        (when-let [shininess (:material/shininess op)]
          (when-not (and (finite-number? shininess) (> shininess 0.0))
            (reject! "draw3d/mesh :material/shininess must be positive")))))))


(defn validate-frame!
  [frame]
  (when-not (vector? frame) (reject! "Frame must be a vector"))
  (loop [ops (seq frame)
         model-stack (list (identity-affine))
         clip-depth 0
         target-stack (list {:id :default})
         produced-targets #{}
         has-camera? false
         lighting-enabled? false]
    (if-not ops
      (do (when (> (count model-stack) 1)
            (reject! "Frame ended with non-empty transform stack"))
          (when (pos? clip-depth)
            (reject! "Frame ended with non-empty clip stack"))
          (when (> (count target-stack) 1)
            (reject! "Frame ended with non-empty render-target stack"))
          frame)
      (let [op (first ops)
            kind (:op/kind op)]
        (when-not (map? op) (reject! "Op must be a map"))
        (when-not kind (reject! "Op missing :op/kind"))
        (case kind
          :frame/clear (do (check-color! op :color)
                           (recur (next ops)
                                  model-stack
                                  clip-depth
                                  target-stack
                                  produced-targets
                                  has-camera?
                                  lighting-enabled?))
          :transform/push (let [next-m (compose-affine (first model-stack)
                                                       (op->affine op))]
                            (recur (next ops)
                                   (conj model-stack next-m)
                                   clip-depth
                                   target-stack
                                   produced-targets
                                   has-camera?
                                   lighting-enabled?))
          :transform/pop (if (<= (count model-stack) 1)
                           (reject! "Transform pop without push")
                           (recur (next ops)
                                  (rest model-stack)
                                  clip-depth
                                  target-stack
                                  produced-targets
                                  has-camera?
                                  lighting-enabled?))
          :clip/push-rect
          (do (when-not (positive-rect? (:rect op))
                (reject!
                  "clip/push-rect :rect must be [x y w h] with w>0, h>0"))
              (when-not (:translate-only? (first model-stack))
                (reject! "clip/push-rect requires translate-only ancestry"))
              (recur (next ops)
                     model-stack
                     (inc clip-depth)
                     target-stack
                     produced-targets
                     has-camera?
                     lighting-enabled?))
          :clip/pop (if (zero? clip-depth)
                      (reject! "Clip pop without push")
                      (recur (next ops)
                             model-stack
                             (dec clip-depth)
                             target-stack
                             produced-targets
                             has-camera?
                             lighting-enabled?))
          :meta/region
          (do (when-not (positive-rect? (:rect op))
                (reject! "meta/region :rect must be [x y w h] with w>0, h>0"))
              (when-not (contains? op :op/meta)
                (reject! "meta/region missing :op/meta"))
              (when-not (:translate-only? (first model-stack))
                (reject! "meta/region requires translate-only ancestry"))
              (recur (next ops)
                     model-stack
                     clip-depth
                     target-stack
                     produced-targets
                     has-camera?
                     lighting-enabled?))
          :camera3d/set (do (validate-camera! op)
                            (recur (next ops)
                                   model-stack
                                   clip-depth
                                   target-stack
                                   produced-targets
                                   true
                                   lighting-enabled?))
          :state/depth-test
          (do (when-not (boolean? (:enabled op))
                (reject! "state/depth-test :enabled must be boolean"))
              (recur (next ops)
                     model-stack
                     clip-depth
                     target-stack
                     produced-targets
                     has-camera?
                     lighting-enabled?))
          :state/depth-write
          (do (when-not (boolean? (:enabled op))
                (reject! "state/depth-write :enabled must be boolean"))
              (recur (next ops)
                     model-stack
                     clip-depth
                     target-stack
                     produced-targets
                     has-camera?
                     lighting-enabled?))
          :state/lighting-enable
          (do (when-not (boolean? (:enabled op))
                (reject! "state/lighting-enable :enabled must be boolean"))
              (recur (next ops)
                     model-stack
                     clip-depth
                     target-stack
                     produced-targets
                     has-camera?
                     (:enabled op)))
          :light/ambient (do (when-not (valid-light-color? (:color op))
                               (reject! "light/ambient :color must be [r g b]"))
                             (recur (next ops)
                                    model-stack
                                    clip-depth
                                    target-stack
                                    produced-targets
                                    has-camera?
                                    lighting-enabled?))
          :light/directional
          (do (when-not (valid-light-color? (:color op))
                (reject! "light/directional :color must be [r g b]"))
              (when-not (vec3? (:direction op))
                (reject! "light/directional :direction must be [x y z]"))
              (recur (next ops)
                     model-stack
                     clip-depth
                     target-stack
                     produced-targets
                     has-camera?
                     lighting-enabled?))
          :light/point (do (when-not (valid-light-color? (:color op))
                             (reject! "light/point :color must be [r g b]"))
                           (when-not (vec3? (:position op))
                             (reject! "light/point :position must be [x y z]"))
                           (recur (next ops)
                                  model-stack
                                  clip-depth
                                  target-stack
                                  produced-targets
                                  has-camera?
                                  lighting-enabled?))
          :light/clear (recur (next ops)
                              model-stack
                              clip-depth
                              target-stack
                              produced-targets
                              has-camera?
                              lighting-enabled?)
          :target/push (do (validate-target-push! op produced-targets)
                           (recur (next ops)
                                  model-stack
                                  clip-depth
                                  (conj target-stack {:id (:target/id op)})
                                  (conj produced-targets (:target/id op))
                                  has-camera?
                                  lighting-enabled?))
          :target/pop (if (<= (count target-stack) 1)
                        (reject! "target/pop without target/push")
                        (recur (next ops)
                               model-stack
                               clip-depth
                               (rest target-stack)
                               produced-targets
                               has-camera?
                               lighting-enabled?))
          (:draw/fill-rect :draw/stroke-rect :draw/fill-circle
                           :draw/stroke-circle :draw/path
                           :draw/text :draw/image)
          (do (validate-2d-draw! op model-stack target-stack produced-targets)
              (recur (next ops)
                     model-stack
                     clip-depth
                     target-stack
                     produced-targets
                     has-camera?
                     lighting-enabled?))
          (:draw3d/lines :draw3d/triangles :draw3d/mesh)
          (do (validate-3d-draw! op
                                 has-camera?
                                 lighting-enabled?
                                 target-stack
                                 produced-targets)
              (recur (next ops)
                     model-stack
                     clip-depth
                     target-stack
                     produced-targets
                     has-camera?
                     lighting-enabled?))
          (reject! :unsupported-op (str "Unknown op kind: " kind)))))))


(defn- viewport
  [{:keys [viewport-width viewport-height viewport-size]}]
  (let [[w h] (cond (and viewport-width viewport-height) [viewport-width
                                                          viewport-height]
                    (fn? viewport-size) (viewport-size)
                    (vector? viewport-size) viewport-size
                    :else [0 0])]
    {:width (double w), :height (double h)}))


(defn- current-pass-index
  [state]
  (:pass-index (first (:target-stack state))))


(defn- append-draw
  [state draw]
  (update-in state [:passes (current-pass-index state) :draws] conj draw))


(defn- resolve-source!
  [source state opts]
  (cond (= source :image/placeholder) {:resource/kind :placeholder}
        (target-source? source)
        (let [target-id (target-source-id source)]
          (if (contains? (:target-registry state) target-id)
            {:resource/kind :target-texture, :target/id target-id}
            (reject! "Texture source refers to a missing render target")))
        (:resolve-resource opts)
        (or ((:resolve-resource opts) source state)
            (reject! :unloadable-image
                     "Image source is not synchronously realizable"))
        :else (reject! :unloadable-image
                       "Image source is not synchronously realizable")))


(defn- lower-2d-rect
  [op state viewport-height pipeline]
  (let [screen-rect (cart-rect->screen
                      (transformed-rect (first (:model-stack state)) (:rect op))
                      viewport-height)]
    (append-draw state
                 {:pipeline pipeline,
                  :op/kind (:op/kind op),
                  :screen-rect screen-rect,
                  :color (:color op),
                  :stroke-width (:stroke-width op),
                  :clips (vec (:clip-stack state)),
                  :op/meta (:op/meta op)})))


(defn- lower-text
  [op state viewport-height]
  (let [[x y] (transform-point (first (:model-stack state)) (:position op))
        font-size (double (get op :font-size 14.0))
        width (* (count (:text op)) font-size 0.6)
        x-off (case (get op :align :start)
                :center (- (/ width 2.0))
                :end (- width)
                :start 0.0)
        screen-position [(+ x x-off) (- viewport-height y)]]
    (append-draw state
                 {:pipeline :text,
                  :op/kind :draw/text,
                  :text (:text op),
                  :screen-position screen-position,
                  :font-size font-size,
                  :font-family (:font-family op),
                  :color (:color op [0 0 0 1]),
                  :clips (vec (:clip-stack state)),
                  :glyphs-transformed? false,
                  :op/meta (:op/meta op)})))


(defn- lower-image
  [op state opts viewport-height]
  (let [resource (resolve-source! (:image/source op) state opts)
        screen-rect (cart-rect->screen
                      (transformed-rect (first (:model-stack state)) (:rect op))
                      viewport-height)]
    (append-draw state
                 {:pipeline (if (= :placeholder (:resource/kind resource))
                              :solid-2d
                              :texture-2d),
                  :op/kind :draw/image,
                  :screen-rect screen-rect,
                  :resource resource,
                  :opacity (get op :opacity 1.0),
                  :fit (get op :image/fit :fill),
                  :clips (vec (:clip-stack state)),
                  :color (when (= :placeholder (:resource/kind resource))
                           [1 1 1 (get op :opacity 1.0)]),
                  :op/meta (:op/meta op)})))


(defn- lower-3d
  [op state opts]
  (let [resource (when-let [source (:texture/source op)]
                   (resolve-source! source state opts))]
    (append-draw state
                 {:pipeline (case (:op/kind op)
                              :draw3d/lines :line-3d
                              :draw3d/triangles :flat-3d
                              :draw3d/mesh
                              (if resource :mesh-textured-3d :mesh-3d)),
                  :op/kind (:op/kind op),
                  :mvp [:camera-matrix :mul :model-stack-top],
                  :model-transform (first (:model-stack state)),
                  :camera (:camera-state state),
                  :depth-test (:depth-test state),
                  :depth-write (:depth-write state),
                  :lighting-enabled (:lighting-enabled state),
                  :lights (:lights state),
                  :camera-pos (:camera-pos state),
                  :texture resource,
                  :clips (vec (:clip-stack state)),
                  :payload (dissoc op :op/meta),
                  :op/meta (:op/meta op)})))


(defn lower-frame
  ([frame] (lower-frame frame {}))
  ([frame opts]
   (validate-frame! frame)
   (let [{:keys [width height]} (viewport opts)]
     (loop [ops (seq frame)
            state {:model-stack (list (identity-affine)),
                   :clip-stack (),
                   :target-stack (list {:id :default, :pass-index 0}),
                   :target-registry {},
                   :passes [{:target-id :default, :draws []}],
                   :camera-matrix nil,
                   :camera-pos nil,
                   :depth-test false,
                   :depth-write false,
                   :lighting-enabled false,
                   :lights []}]
       (if-not ops
         {:viewport {:width width, :height height},
          :passes (:passes state),
          :target-registry (:target-registry state),
          :geometry-report (vec (:geometry-report state))}
         (let [op (first ops)
               kind (:op/kind op)]
           (recur
             (next ops)
             (case kind
               :frame/clear (append-draw state
                                         {:pipeline :clear,
                                          :op/kind :frame/clear,
                                          :color (get op :color [0 0 0 1]),
                                          :clear-depth 1.0})
               :transform/push (update state
                                       :model-stack
                                       conj
                                       (compose-affine (first (:model-stack
                                                                state))
                                                       (op->affine op)))
               :transform/pop (update state :model-stack rest)
               :clip/push-rect (update state
                                       :clip-stack
                                       conj
                                       (resolve-clip-rect (first (:model-stack
                                                                   state))
                                                          (:rect op)
                                                          height))
               :clip/pop (update state :clip-stack rest)
               :meta/region (update state
                                    :geometry-report
                                    (fnil conj [])
                                    {:screen-rect (resolve-clip-rect
                                                    (first (:model-stack state))
                                                    (:rect op)
                                                    height),
                                     :op/meta (:op/meta op)})
               :camera3d/set (assoc state
                                    :camera-matrix (or (:camera3d/view-matrix op)
                                                       {:projection
                                                        (:camera3d/projection op)})
                                    :camera-state op
                                    :camera-pos (if-let [pos (:camera3d/position op)]
                                                  (mapv double pos)
                                                  [0.0 0.0 0.0]))
               :state/depth-test (assoc state :depth-test (:enabled op))
               :state/depth-write (assoc state :depth-write (:enabled op))
               :state/lighting-enable (assoc state
                                             :lighting-enabled (:enabled op))
               :light/ambient (update state
                                      :lights
                                      conj
                                      {:kind :ambient, :color (:color op)})
               :light/directional (update state
                                          :lights
                                          conj
                                          {:kind :directional,
                                           :color (:color op),
                                           :direction (:direction op),
                                           :intensity (get op :intensity 1.0)})
               :light/point (update state
                                    :lights
                                    conj
                                    {:kind :point,
                                     :color (:color op),
                                     :position (:position op),
                                     :intensity (get op :intensity 1.0),
                                     :range (:range op)})
               :light/clear (assoc state :lights [])
               :target/push
               (let [pass-index (count (:passes state))
                     target {:id (:target/id op),
                             :size (:target/size op),
                             :format (get op :target/format :rgba8unorm)}]
                 (-> state
                     (update
                       :passes
                       conj
                       {:target-id (:id target), :target target, :draws []})
                     (update :target-stack
                             conj
                             {:id (:id target), :pass-index pass-index})
                     (assoc-in [:target-registry (:id target)] target)))
               :target/pop (update state :target-stack rest)
               :draw/fill-rect (lower-2d-rect op state height :solid-2d)
               :draw/stroke-rect (lower-2d-rect op state height :stroke-2d)
               :draw/fill-circle
               (let [[cx cy] (transform-point (first (:model-stack state))
                                              (:center op))]
                 (append-draw state
                              {:pipeline :circle-2d,
                               :op/kind kind,
                               :screen-center [cx (- height cy)],
                               :radius (:radius op),
                               :color (:color op),
                               :filled? true,
                               :clips (vec (:clip-stack state)),
                               :op/meta (:op/meta op)}))
               :draw/stroke-circle
               (append-draw state
                            (let [[cx cy] (transform-point
                                            (first (:model-stack state))
                                            (:center op))]
                              {:pipeline :circle-2d,
                               :op/kind kind,
                               :screen-center [cx (- height cy)],
                               :radius (:radius op),
                               :stroke-width (:stroke-width op 1.0),
                               :color (:color op),
                               :filled? false,
                               :clips (vec (:clip-stack state)),
                               :op/meta (:op/meta op)}))
               :draw/path (append-draw state
                                       {:pipeline :path-2d,
                                        :op/kind :draw/path,
                                        :transform (first (:model-stack state)),
                                        :segments (:segments op),
                                        :fill (:fill op),
                                        :stroke (:stroke op),
                                        :stroke-width (:stroke-width op),
                                        :clips (vec (:clip-stack state)),
                                        :op/meta (:op/meta op)})
               :draw/text (lower-text op state height)
               :draw/image (lower-image op state opts height)
               (:draw3d/lines :draw3d/triangles :draw3d/mesh)
               (lower-3d op state opts)
               state))))))))


(defn- default-submit!
  [_lowered]
  nil)


(def put-frame! terminal/put-frame!)


(defn postgraphics-canvas
  "Binds a browser canvas-oriented WebGPU terminal to a dao.stream of complete
  postgraphics frames.

  The host-specific encoder is deliberately a seam: pass `:submit!` to turn the
  lowered pass/draw data into real WebGPU commands. Without it, the terminal
  still validates, lowers, and accounts for frames, which keeps VM semantics
  independent from browser device availability."
  [gpu-canvas frame-stream &
   {:keys [on-error signal-stream device-options viewport-size submit!
           resolve-resource generation-id generation-id-fn],
    :or {submit! default-submit!}}]
  (let [host {:canvas gpu-canvas, :device-options device-options}
        binding
        (terminal/bind-stream!
          frame-stream
          {:validate-frame! validate-frame!,
           :present-frame! (fn [frame]
                             (submit! (lower-frame
                                        frame
                                        {:viewport-size viewport-size,
                                         :resolve-resource resolve-resource,
                                         :host host}))),
           :signal-stream signal-stream,
           :generation-id generation-id,
           :generation-id-fn (or generation-id-fn terminal/new-generation-id),
           :on-error on-error})]
    (merge host binding {:submit! submit!})))
