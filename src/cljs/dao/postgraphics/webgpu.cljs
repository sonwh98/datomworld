(ns dao.postgraphics.webgpu
  (:require
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.validation :as v]
    [reagent.core :as r]))


(def ^:private target-formats #{:rgba8unorm :bgra8unorm :rgba16f :rgba32f})


(defn- positive-size?
  [s]
  (and (v/vec2? s) (> (double (nth s 0)) 0.0) (> (double (nth s 1)) 0.0)))


(defn- approx-zero?
  [x]
  (< (js/Math.abs (double x)) v/EPSILON))


(defn- approx-one?
  [x]
  (< (js/Math.abs (- (double x) 1.0)) v/EPSILON))


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


(defn- mat4
  [& xs]
  (vec (map double xs)))


(defn- identity-mat4
  []
  (mat4 1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1))


(defn- mat4-mul
  [a b]
  (vec (for [col (range 4)
             row (range 4)]
         (+ (* (nth a row) (nth b (* col 4)))
            (* (nth a (+ 4 row)) (nth b (+ (* col 4) 1)))
            (* (nth a (+ 8 row)) (nth b (+ (* col 4) 2)))
            (* (nth a (+ 12 row)) (nth b (+ (* col 4) 3)))))))


(defn- mat4-translation
  [[tx ty tz]]
  (mat4 1 0 0 0 0 1 0 0 0 0 1 0 (or tx 0.0) (or ty 0.0) (or tz 0.0) 1))


(defn- mat4-scale
  [[sx sy sz]]
  (mat4 (or sx 1.0) 0 0 0 0 (or sy 1.0) 0 0 0 0 (or sz 1.0) 0 0 0 0 1))


(defn- mat4-rotation-x
  [a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    (mat4 1 0 0 0 0 c s 0 0 (- s) c 0 0 0 0 1)))


(defn- mat4-rotation-y
  [a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    (mat4 c 0 (- s) 0 0 1 0 0 s 0 c 0 0 0 0 1)))


(defn- mat4-rotation-z
  [a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    (mat4 c s 0 0 (- s) c 0 0 0 0 1 0 0 0 0 1)))


(defn- matrix4-translation-only?
  [m]
  (and (approx-one? (nth m 0))
       (approx-zero? (nth m 1))
       (approx-zero? (nth m 2))
       (approx-zero? (nth m 3))
       (approx-zero? (nth m 4))
       (approx-one? (nth m 5))
       (approx-zero? (nth m 6))
       (approx-zero? (nth m 7))
       (approx-zero? (nth m 8))
       (approx-zero? (nth m 9))
       (approx-one? (nth m 10))
       (approx-zero? (nth m 11))
       (approx-zero? (nth m 14))
       (approx-one? (nth m 15))))


(defn- matrix4->affine
  [m]
  (when-not (and (approx-zero? (nth m 2))
                 (approx-zero? (nth m 3))
                 (approx-zero? (nth m 6))
                 (approx-zero? (nth m 7))
                 (approx-one? (nth m 10))
                 (approx-zero? (nth m 11))
                 (approx-zero? (nth m 14))
                 (approx-one? (nth m 15)))
    (v/reject! "4x4 matrix must be 2D-affine for 2D WebGPU lowering"))
  (affine (nth m 0) (nth m 4) (nth m 1) (nth m 5) (nth m 12) (nth m 13)))


(defn- matrix-valid-numbers?
  [m n]
  (and (vector? m) (= n (count m)) (every? v/finite-number? m)))


(defn- matrix-op->mat4
  [mat]
  (cond
    (matrix-valid-numbers? mat 16) (mapv double mat)
    (matrix-valid-numbers? mat 9)
    (let [[m00 m01 m02 m10 m11 m12 m20 m21 m22] mat]
      (mat4 m00 m10 0.0 m20 m01 m11 0.0 m21 0.0 0.0 1.0 0.0 m02 m12 0.0 m22))
    :else (v/reject! "matrix must be a 9- or 16-element numeric vector")))


(defn- op->mat4
  [op]
  (if-let [mat (:matrix op)]
    (matrix-op->mat4 mat)
    (let [translate (or (:translate op) [0.0 0.0 0.0])
          scale (or (:scale op) [1.0 1.0 1.0])
          rotate (let [r (:rotate op)]
                   (cond (nil? r) [0.0 0.0 0.0]
                         (v/finite-number? r) [0.0 0.0 r]
                         (v/vec3? r) r
                         :else (v/reject!
                                 "rotate must be a number or [rx ry rz]")))]
      (when-not (or (v/vec2? translate) (v/vec3? translate))
        (v/reject! "translate must be [x y] or [x y z]"))
      (when-not (or (v/vec2? scale) (v/vec3? scale))
        (v/reject! "scale must be [x y] or [x y z]"))
      (let [[rx ry rz] rotate]
        (-> (mat4-translation translate)
            (mat4-mul (mat4-rotation-z rz))
            (mat4-mul (mat4-rotation-y ry))
            (mat4-mul (mat4-rotation-x rx))
            (mat4-mul (mat4-scale scale)))))))


(defn- mat4-invert
  [m]
  (let [a00 (nth m 0)
        a01 (nth m 1)
        a02 (nth m 2)
        a03 (nth m 3)
        a10 (nth m 4)
        a11 (nth m 5)
        a12 (nth m 6)
        a13 (nth m 7)
        a20 (nth m 8)
        a21 (nth m 9)
        a22 (nth m 10)
        a23 (nth m 11)
        a30 (nth m 12)
        a31 (nth m 13)
        a32 (nth m 14)
        a33 (nth m 15)
        b00 (- (* a00 a11) (* a01 a10))
        b01 (- (* a00 a12) (* a02 a10))
        b02 (- (* a00 a13) (* a03 a10))
        b03 (- (* a01 a12) (* a02 a11))
        b04 (- (* a01 a13) (* a03 a11))
        b05 (- (* a02 a13) (* a03 a12))
        b06 (- (* a20 a31) (* a21 a30))
        b07 (- (* a20 a32) (* a22 a30))
        b08 (- (* a20 a33) (* a23 a30))
        b09 (- (* a21 a32) (* a22 a31))
        b10 (- (* a21 a33) (* a23 a31))
        b11 (- (* a22 a33) (* a23 a32))
        det (+ (* b00 b11)
               (- (* b01 b10))
               (* b02 b09)
               (* b03 b08)
               (- (* b04 b07))
               (* b05 b06))]
    (when (approx-zero? det) (v/reject! "4x4 matrix is not invertible"))
    (let [inv-det (/ 1.0 det)]
      (mat4 (* (- (* a11 b11) (* a12 b10) (- (* a13 b09))) inv-det)
            (* (+ (- (* a01 b11)) (* a02 b10) (- (* a03 b09))) inv-det)
            (* (- (* a31 b05) (* a32 b04) (- (* a33 b03))) inv-det)
            (* (+ (- (* a21 b05)) (* a22 b04) (- (* a23 b03))) inv-det)
            (* (+ (- (* a10 b11)) (* a12 b08) (- (* a13 b07))) inv-det)
            (* (- (* a00 b11) (* a02 b08) (- (* a03 b07))) inv-det)
            (* (+ (- (* a30 b05)) (* a32 b02) (- (* a33 b01))) inv-det)
            (* (- (* a20 b05) (* a22 b02) (- (* a23 b01))) inv-det)
            (* (- (* a10 b10) (* a11 b08) (- (* a13 b06))) inv-det)
            (* (+ (- (* a00 b10)) (* a01 b08) (- (* a03 b06))) inv-det)
            (* (- (* a30 b04) (* a31 b02) (- (* a33 b00))) inv-det)
            (* (+ (- (* a20 b04)) (* a21 b02) (- (* a23 b00))) inv-det)
            (* (+ (- (* a10 b09)) (* a11 b07) (- (* a12 b06))) inv-det)
            (* (- (* a00 b09) (* a01 b07) (- (* a02 b06))) inv-det)
            (* (+ (- (* a30 b03)) (* a31 b01) (- (* a32 b00))) inv-det)
            (* (- (* a20 b03) (* a21 b01) (- (* a22 b00))) inv-det)))))


(defn- camera-pos-from-view-matrix
  [view-matrix]
  (let [inv (mat4-invert (mapv double view-matrix))]
    [(nth inv 12) (nth inv 13) (nth inv 14)]))


(defn- build-camera
  [op width height]
  (let [projection
        (case (:camera3d/projection op)
          :perspective
          (let [fov (double (:camera3d/fov op))
                near (double (:camera3d/near op))
                far (double (:camera3d/far op))
                aspect (double (get op :camera3d/aspect (/ width height)))
                f (/ 1.0 (js/Math.tan (/ (* fov js/Math.PI) 360.0)))
                z-range (- near far)]
            (mat4 (/ f aspect)
                  0.0
                  0.0
                  0.0
                  0.0
                  f
                  0.0
                  0.0
                  0.0
                  0.0
                  (/ far z-range)
                  -1.0
                  0.0
                  0.0
                  (/ (* near far) z-range)
                  0.0))
          :orthographic (let [left (double (:camera3d/left op))
                              right (double (:camera3d/right op))
                              bottom (double (:camera3d/bottom op))
                              top (double (:camera3d/top op))
                              near (double (:camera3d/near op))
                              far (double (:camera3d/far op))]
                          (mat4 (/ 2.0 (- right left))
                                0.0
                                0.0
                                0.0
                                0.0
                                (/ 2.0 (- top bottom))
                                0.0
                                0.0
                                0.0
                                0.0
                                (/ 1.0 (- near far))
                                0.0
                                (- (/ (+ right left) (- right left)))
                                (- (/ (+ top bottom) (- top bottom)))
                                (/ near (- near far))
                                1.0)))
        view (if-let [vmat (:camera3d/view-matrix op)]
               (mapv double vmat)
               (let [pos (get op :camera3d/position [0.0 0.0 0.0])
                     rot (get op :camera3d/rotation [0.0 0.0 0.0])
                     [rx ry rz] rot
                     camera-world (-> (mat4-translation pos)
                                      (mat4-mul (mat4-rotation-z rz))
                                      (mat4-mul (mat4-rotation-y ry))
                                      (mat4-mul (mat4-rotation-x rx)))]
                 (mat4-invert camera-world)))]
    (mat4-mul projection view)))


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
    (v/reject! "clip/push-rect requires translate-only ancestry"))
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
        (v/reject! "A render target cannot sample from itself while active"))
      (when-not (contains? produced-targets target-id)
        (v/reject! "Texture source refers to a missing render target")))))


(defn- validate-target-push!
  [op produced-targets]
  (let [target-id (:target/id op)
        size (:target/size op)
        format (get op :target/format :rgba8unorm)]
    (when-not target-id (v/reject! "target/push missing :target/id"))
    (when (contains? produced-targets target-id)
      (v/reject! "target/push reused a target id in the same frame"))
    (when-not (positive-size? size)
      (v/reject! "target/push :target/size must be [w h] with w>0, h>0"))
    (when-not (contains? target-formats format)
      (v/reject! :unsupported-op "Unsupported WebGPU render-target format"))))


(defn- validate-camera!
  [op]
  (case (:camera3d/projection op)
    :perspective
    (let [fov (:camera3d/fov op)
          near (:camera3d/near op)
          far (:camera3d/far op)
          aspect (:camera3d/aspect op)]
      (when-not (and (v/finite-number? fov)
                     (v/finite-number? near)
                     (v/finite-number? far))
        (v/reject! "Perspective camera missing numeric fov, near, or far"))
      (when-not (and (> fov 0.0) (< fov 180.0))
        (v/reject! "FOV must be in (0, 180)"))
      (when-not (> near 0.0) (v/reject! "Near must be > 0"))
      (when-not (> (- far near) v/EPSILON) (v/reject! "Far must exceed near"))
      (when (and aspect (not (and (v/finite-number? aspect) (> aspect 0.0))))
        (v/reject! "Aspect must be positive")))
    :orthographic (let [l (:camera3d/left op)
                        r (:camera3d/right op)
                        b (:camera3d/bottom op)
                        t (:camera3d/top op)
                        near (:camera3d/near op)
                        far (:camera3d/far op)]
                    (when-not (every? v/finite-number? [l r b t near far])
                      (v/reject! "Orthographic camera missing numeric bounds"))
                    (when-not (> r l) (v/reject! "Right must be > left"))
                    (when-not (> t b) (v/reject! "Top must be > bottom")))
    (v/reject! "Unknown camera projection"))
  (when-let [pos (:camera3d/position op)]
    (when-not (v/vec3? pos) (v/reject! "camera3d/position must be vec3")))
  (when-let [rot (:camera3d/rotation op)]
    (when-not (v/vec3? rot) (v/reject! "camera3d/rotation must be vec3")))
  (when-let [vmat (:camera3d/view-matrix op)]
    (when-not (matrix-valid-numbers? vmat 16)
      (v/reject! "camera3d/view-matrix must be a 16-element numeric vector"))))


(defn- validate-2d-draw!
  [op model-stack target-stack produced-targets]
  (let [kind (:op/kind op)
        top (matrix4->affine (first model-stack))]
    (when-not (:affine-2d? top)
      (v/reject! (str "Invalid 2D affine matrix for " kind)))
    (case kind
      (:draw/fill-rect :draw/stroke-rect)
      (do (when-not (v/positive-rect? (:rect op))
            (v/reject! (str kind " :rect must be [x y w h] with w>0, h>0")))
          (v/check-color! op :color)
          (when-let [sw (:stroke-width op)]
            (when-not (and (v/finite-number? sw) (>= sw 0.0))
              (v/reject! ":stroke-width must be a non-negative number"))))
      (:draw/fill-circle :draw/stroke-circle)
      (do (when-not (v/vec2? (:center op))
            (v/reject! (str kind " :center must be [x y]")))
          (when-not (and (v/finite-number? (:radius op)) (> (:radius op) 0.0))
            (v/reject! (str kind " :radius must be > 0")))
          (v/check-color! op :color))
      :draw/path (do (when-not (vector? (:segments op))
                       (v/reject! "draw/path missing or invalid :segments"))
                     (v/check-color! op :fill)
                     (v/check-color! op :stroke))
      :draw/text (do
                   (when-not (string? (:text op))
                     (v/reject! "draw/text missing or invalid :text"))
                   (when-not (v/vec2? (:position op))
                     (v/reject! "draw/text :position must be [x y]"))
                   (v/check-color! op :color)
                   (when-let [fs (:font-size op)]
                     (when-not (and (v/finite-number? fs) (> fs 0.0))
                       (v/reject! "draw/text :font-size must be > 0")))
                   (when-let [ff (:font-family op)]
                     (when-not (string? ff)
                       (v/reject! "draw/text :font-family must be a string")))
                   (when (contains? op :align)
                     (when-not (#{:start :center :end} (:align op))
                       (v/reject!
                         "draw/text :align must be :start, :center, or :end"))))
      :draw/image
      (do
        (when-not (v/positive-rect? (:rect op))
          (v/reject! "draw/image :rect must be [x y w h] with w>0, h>0"))
        (when-not (contains? op :image/source)
          (v/reject! "draw/image missing :image/source"))
        (check-target-source! (:image/source op)
                              target-stack
                              produced-targets)
        (when-let [opacity (:opacity op)]
          (when-not (v/in-unit-interval? opacity)
            (v/reject! "draw/image :opacity must be in [0, 1]")))
        (when-let [fit (:image/fit op)]
          (when-not (#{:contain :cover :fill :none} fit)
            (v/reject!
              "draw/image :image/fit must be :contain, :cover, :fill, or :none")))))))


(defn- validate-3d-draw!
  [op has-camera? lighting-enabled? target-stack produced-targets]
  (when-not has-camera? (v/reject! "3D draw without prior camera3d/set"))
  (let [kind (:op/kind op)
        verts (:vertices op)
        vertex-count (count verts)]
    (when-not (and (vector? verts) (every? v/vec3? verts))
      (v/reject! "3D draw :vertices must be a vector of vec3"))
    (case kind
      :draw3d/lines (do (v/check-color! op :color)
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
                            (v/reject! "draw3d/lines :edges invalid"))))
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
            (v/reject! "draw3d/triangles :indices invalid"))
          (v/check-color! op :fill))
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
          (v/reject! "draw3d/mesh :indices invalid"))
        (v/check-color! op :fill)
        (when (and lighting-enabled? (not (:normals op)))
          (v/reject! "draw3d/mesh under lighting-enabled requires :normals"))
        (doseq [[k pred label] [[:normals v/vec3? "vec3"]
                                [:uvs v/vec2? "vec2"]
                                [:colors v/valid-color? "color"]]]
          (when-let [values (get op k)]
            (when-not (= vertex-count (count values))
              (v/reject!
                (str "draw3d/mesh " k " length must equal vertices length")))
            (when-not (every? pred values)
              (v/reject! (str "draw3d/mesh " k " entries must be " label)))))
        (when-let [source (:texture/source op)]
          (check-target-source! source target-stack produced-targets)
          (when-not (:uvs op)
            (v/reject! "draw3d/mesh with :texture/source must provide :uvs")))
        (when-let [wrap (:texture/wrap op)]
          (when-not (#{:clamp :repeat :mirror} wrap)
            (v/reject! "draw3d/mesh :texture/wrap invalid")))
        (when-let [filter (:texture/filter op)]
          (when-not (#{:linear :nearest} filter)
            (v/reject! "draw3d/mesh :texture/filter invalid")))
        (when-let [spec (:material/specular op)]
          (when-not (v/valid-light-color? spec)
            (v/reject! "draw3d/mesh :material/specular must be [r g b]")))
        (when-let [shininess (:material/shininess op)]
          (when-not (and (v/finite-number? shininess) (> shininess 0.0))
            (v/reject!
              "draw3d/mesh :material/shininess must be positive")))))))


(defn validate-frame!
  [frame]
  (when-not (vector? frame) (v/reject! "Frame must be a vector"))
  (loop [ops (seq frame)
         model-stack (list (identity-mat4))
         clip-depth 0
         target-stack (list {:id :default})
         produced-targets #{}
         has-camera? false
         lighting-enabled? false]
    (if-not ops
      (do (when (> (count model-stack) 1)
            (v/reject! "Frame ended with non-empty transform stack"))
          (when (pos? clip-depth)
            (v/reject! "Frame ended with non-empty clip stack"))
          (when (> (count target-stack) 1)
            (v/reject! "Frame ended with non-empty render-target stack"))
          frame)
      (let [op (first ops)
            kind (:op/kind op)]
        (when-not (map? op) (v/reject! "Op must be a map"))
        (when-not kind (v/reject! "Op missing :op/kind"))
        (case kind
          :frame/clear (do (v/check-color! op :color)
                           (recur (next ops)
                                  model-stack
                                  clip-depth
                                  target-stack
                                  produced-targets
                                  has-camera?
                                  lighting-enabled?))
          :transform/push (let [next-m (mat4-mul (first model-stack)
                                                 (op->mat4 op))]
                            (recur (next ops)
                                   (conj model-stack next-m)
                                   clip-depth
                                   target-stack
                                   produced-targets
                                   has-camera?
                                   lighting-enabled?))
          :transform/pop (if (<= (count model-stack) 1)
                           (v/reject! "Transform pop without push")
                           (recur (next ops)
                                  (rest model-stack)
                                  clip-depth
                                  target-stack
                                  produced-targets
                                  has-camera?
                                  lighting-enabled?))
          :clip/push-rect
          (do (when-not (v/positive-rect? (:rect op))
                (v/reject!
                  "clip/push-rect :rect must be [x y w h] with w>0, h>0"))
              (when-not (matrix4-translation-only? (first model-stack))
                (v/reject! "clip/push-rect requires translate-only ancestry"))
              (recur (next ops)
                     model-stack
                     (inc clip-depth)
                     target-stack
                     produced-targets
                     has-camera?
                     lighting-enabled?))
          :clip/pop (if (zero? clip-depth)
                      (v/reject! "Clip pop without push")
                      (recur (next ops)
                             model-stack
                             (dec clip-depth)
                             target-stack
                             produced-targets
                             has-camera?
                             lighting-enabled?))
          :meta/region
          (do (when-not (v/positive-rect? (:rect op))
                (v/reject!
                  "meta/region :rect must be [x y w h] with w>0, h>0"))
              (when-not (contains? op :op/meta)
                (v/reject! "meta/region missing :op/meta"))
              (when-not (matrix4-translation-only? (first model-stack))
                (v/reject! "meta/region requires translate-only ancestry"))
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
                (v/reject! "state/depth-test :enabled must be boolean"))
              (recur (next ops)
                     model-stack
                     clip-depth
                     target-stack
                     produced-targets
                     has-camera?
                     lighting-enabled?))
          :state/depth-write
          (do (when-not (boolean? (:enabled op))
                (v/reject! "state/depth-write :enabled must be boolean"))
              (recur (next ops)
                     model-stack
                     clip-depth
                     target-stack
                     produced-targets
                     has-camera?
                     lighting-enabled?))
          :state/lighting-enable
          (do (when-not (boolean? (:enabled op))
                (v/reject! "state/lighting-enable :enabled must be boolean"))
              (recur (next ops)
                     model-stack
                     clip-depth
                     target-stack
                     produced-targets
                     has-camera?
                     (:enabled op)))
          :light/ambient (do (when-not (v/valid-light-color? (:color op))
                               (v/reject!
                                 "light/ambient :color must be [r g b]"))
                             (recur (next ops)
                                    model-stack
                                    clip-depth
                                    target-stack
                                    produced-targets
                                    has-camera?
                                    lighting-enabled?))
          :light/directional
          (do (when-not (v/valid-light-color? (:color op))
                (v/reject! "light/directional :color must be [r g b]"))
              (when-not (v/vec3? (:direction op))
                (v/reject! "light/directional :direction must be [x y z]"))
              (recur (next ops)
                     model-stack
                     clip-depth
                     target-stack
                     produced-targets
                     has-camera?
                     lighting-enabled?))
          :light/point (do (when-not (v/valid-light-color? (:color op))
                             (v/reject! "light/point :color must be [r g b]"))
                           (when-not (v/vec3? (:position op))
                             (v/reject!
                               "light/point :position must be [x y z]"))
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
                        (v/reject! "target/pop without target/push")
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
          (v/reject! :unsupported-op (str "Unknown op kind: " kind)))))))


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
            (v/reject! "Texture source refers to a missing render target")))
        (:resolve-resource opts)
        (or ((:resolve-resource opts) source state)
            (v/reject! :unloadable-image
                       "Image source is not synchronously realizable"))
        :else (v/reject! :unloadable-image
                         "Image source is not synchronously realizable")))


(defn- lower-2d-rect
  [op state viewport-height pipeline]
  (let [top (matrix4->affine (first (:model-stack state)))
        screen-rect (cart-rect->screen (transformed-rect top (:rect op))
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
  (let [top (matrix4->affine (first (:model-stack state)))
        [x y] (transform-point top (:position op))
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
        top (matrix4->affine (first (:model-stack state)))
        screen-rect (cart-rect->screen (transformed-rect top (:rect op))
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
                   (resolve-source! source state opts))
        model-m (first (:model-stack state))
        mvp (mat4-mul (:camera-matrix state) model-m)
        op' (cond-> (dissoc op :op/meta)
              resource (assoc :texture/source resource))]
    (append-draw state
                 {:pipeline (case (:op/kind op)
                              :draw3d/lines :line-3d
                              :draw3d/triangles :draw-3d
                              :draw3d/mesh :mesh-3d),
                  :op/kind (:op/kind op),
                  :op op',
                  :mvp mvp,
                  :model-m model-m,
                  :camera (:camera-state state),
                  :depth-test (:depth-test state),
                  :depth-write (:depth-write state),
                  :lighting-enabled (:lighting-enabled state),
                  :lighting-state {:enabled (:lighting-enabled state),
                                   :lights (:lights state),
                                   :camera-pos (:camera-pos state)},
                  :lights (:lights state),
                  :camera-pos (:camera-pos state),
                  :texture resource,
                  :clips (vec (:clip-stack state)),
                  :payload op',
                  :op/meta (:op/meta op)})))


(defn lower-frame
  ([frame] (lower-frame frame {}))
  ([frame opts]
   (validate-frame! frame)
   (let [{:keys [width height]} (viewport opts)]
     (loop [ops (seq frame)
            state {:model-stack (list (identity-mat4)),
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
                                       (mat4-mul (first (:model-stack state))
                                                 (op->mat4 op)))
               :transform/pop (update state :model-stack rest)
               :clip/push-rect (update state
                                       :clip-stack
                                       conj
                                       (resolve-clip-rect (matrix4->affine
                                                            (first (:model-stack
                                                                     state)))
                                                          (:rect op)
                                                          height))
               :clip/pop (update state :clip-stack rest)
               :meta/region (update state
                                    :geometry-report
                                    (fnil conj [])
                                    {:screen-rect (resolve-clip-rect
                                                    (matrix4->affine
                                                      (first (:model-stack
                                                               state)))
                                                    (:rect op)
                                                    height),
                                     :op/meta (:op/meta op)})
               :camera3d/set
               (-> state
                   (assoc :camera-matrix (build-camera op width height)
                          :camera-state op
                          :camera-pos
                          (if-let [vmat (:camera3d/view-matrix op)]
                            (camera-pos-from-view-matrix vmat)
                            (mapv double
                                  (get op :camera3d/position [0.0 0.0 0.0]))))
                   (append-draw {:pipeline :camera-reset}))
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
  [_canvas _lowered]
  nil)


(def put-frame! terminal/put-frame!)


(defn- default-viewport-size
  [canvas]
  [(.-width canvas) (.-height canvas)])


(defn- bind-frame-stream!
  [canvas frame-stream
   {:keys [on-error signal-stream device-options viewport-size submit!
           resolve-resource generation-id generation-id-fn],
    :or {submit! default-submit!}}]
  (let [host {:canvas canvas, :device-options device-options}
        viewport-size (or viewport-size #(default-viewport-size canvas))
        binding
        (terminal/bind-stream!
          frame-stream
          {:validate-frame! validate-frame!,
           :present-frame!
           (fn [frame]
             (let [lowered (lower-frame frame
                                        {:viewport-size viewport-size,
                                         :resolve-resource resolve-resource,
                                         :host host})]
               (submit! canvas lowered))),
           :signal-stream signal-stream,
           :generation-id generation-id,
           :generation-id-fn (or generation-id-fn terminal/new-generation-id),
           :on-error on-error})]
    (merge host binding {:submit! submit!})))


(defn frame-stream-binding-test-hook
  [canvas frame-stream opts]
  (bind-frame-stream! canvas frame-stream opts))


(defn postgraphics-widget
  "Returns a browser canvas widget that renders frames emitted by frame-stream.

  Options:
  - :on-error      callback receiving the exception when a frame is rejected
  - :signal-stream dao.stream to which canonical terminal signals
                   (:dao.terminal/reset, :dao.terminal/rejection) are emitted;
                   required for participants in dao.gui.event

  Web options:
  - :canvas-attrs     Hiccup attrs merged onto the internal canvas
  - :submit!          host encoder called as (submit! canvas lowered-frame)
  - :viewport-size    function returning [width height]
  - :resolve-resource function resolving image/texture resources"
  [frame-stream & {:keys [canvas-attrs], :as opts}]
  (let [canvas-ref (atom nil)
        terminal-handle (atom nil)]
    (r/create-class {:display-name "dao-postgraphics-webgpu-widget",
                     :component-did-mount
                     (fn [_]
                       (when-let [canvas @canvas-ref]
                         (reset! terminal-handle
                                 (bind-frame-stream! canvas frame-stream opts)))),
                     :component-will-unmount
                     (fn [_]
                       (when-let [handle @terminal-handle]
                         (when-let [close! (:close! handle)] (close!)))
                       (reset! terminal-handle nil)
                       (reset! canvas-ref nil)),
                     :reagent-render (fn []
                                       [:canvas
                                        (assoc canvas-attrs
                                               :ref #(reset! canvas-ref
                                                             %))])})))
