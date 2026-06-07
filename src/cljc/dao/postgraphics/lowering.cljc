(ns dao.postgraphics.lowering
  "Shared validate-frame! and lower-frame for all dao.postgraphics
   backends.  validate-frame! is parameterized so platform divergences
   (render-target support, image ops, texture-source type) are injected
   rather than forked.  lower-frame emits the canonical lowered frame
   with passes + target-registry."
  (:require
    [dao.postgraphics.math :refer
     [build-camera camera-pos-from-view-matrix identity-mat4 mat4-mul
      matrix-valid-numbers? matrix4-affine? matrix4->affine
      matrix4-translation-only? op->mat4 resolve-clip-rect
      transform-point]]
    [dao.postgraphics.validation :as v]))


;; ---------------------------------------------------------------------------
;; Validation helpers
;; ---------------------------------------------------------------------------

(defn validate-camera!
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


(defn- check-target-source!
  [source target-stack produced-targets]
  (when (and (vector? source)
             (= 2 (count source))
             (= [:target/id] (subvec source 0 1)))
    (let [target-id (nth source 1)
          active-id (:id (first target-stack))]
      (when (= active-id target-id)
        (v/reject! "A render target cannot sample from itself while active"))
      (when-not (contains? produced-targets target-id)
        (v/reject! "Texture source refers to a missing render target")))))


(defn- validate-2d!
  [op model-stack target-stack produced-targets {:keys [supports-image?]}]
  (when-not (matrix4-affine? (first model-stack))
    (v/reject! (str "Invalid 2D affine matrix for " (:op/kind op))))
  (case (:op/kind op)
    (:draw/fill-rect :draw/stroke-rect)
    (do (when-not (v/positive-rect? (:rect op))
          (v/reject! (str (:op/kind op)
                          " :rect must be [x y w h] with w>0, h>0")))
        (v/check-color! op :color)
        (when-let [sw (:stroke-width op)]
          (when-not (and (v/finite-number? sw) (>= sw 0.0))
            (v/reject! ":stroke-width must be a non-negative number"))))
    (:draw/fill-circle :draw/stroke-circle)
    (do (when-not (v/vec2? (:center op))
          (v/reject! (str (:op/kind op) " :center must be [x y]")))
        (when-not (and (v/finite-number? (:radius op)) (> (:radius op) 0.0))
          (v/reject! (str (:op/kind op) " :radius must be > 0")))
        (v/check-color! op :color))
    :draw/path (do (when-not (vector? (:segments op))
                     (v/reject! "draw/path missing or invalid :segments"))
                   (v/check-color! op :fill)
                   (v/check-color! op :stroke))
    :draw/text (do (when-not (string? (:text op))
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
    (if-not supports-image?
      (v/reject! :unsupported-op "draw/image is not supported")
      (do
        (when-not (v/positive-rect? (:rect op))
          (v/reject! "draw/image :rect must be [x y w h] with w>0, h>0"))
        (when-not (contains? op :image/source)
          (v/reject! "draw/image missing :image/source"))
        (when-let [source (:image/source op)]
          (check-target-source! source target-stack produced-targets))
        (when-let [opacity (:opacity op)]
          (when-not (v/in-unit-interval? opacity)
            (v/reject! "draw/image :opacity must be in [0, 1]")))
        (when-let [fit (:image/fit op)]
          (when-not (#{:contain :cover :fill :none} fit)
            (v/reject!
              "draw/image :image/fit must be :contain, :cover, :fill, or :none")))))))


(defn- validate-3d!
  [op has-camera? lighting-enabled? target-stack produced-targets
   {:keys [texture-source-valid?],
    :or {texture-source-valid? (constantly true)}}]
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
          (when-not (or (nil? source) (texture-source-valid? source))
            (v/reject! :unloadable-image
                       "Texture source is not valid for this backend"))
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


;; ---------------------------------------------------------------------------
;; Parameterized validate-frame!
;;
;; Options:
;;   :supports-render-targets?  (default false)
;;   :supports-image?           (default false)
;;   :texture-source-valid?     (default (constantly true))
;;                              fn taking [source], returns truthy if valid.
;; ---------------------------------------------------------------------------

(defn validate-frame!
  ([frame] (validate-frame! frame {}))
  ([frame opts]
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
                 (v/reject!
                   "clip/push-rect requires translate-only ancestry"))
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
           :target/push
           (if-not (:supports-render-targets? opts)
             (v/reject! :unsupported-op
                        "target/push not supported by this backend")
             (do
               (let [{:keys [target/id target/size]} op]
                 (when-not id (v/reject! "target/push missing :target/id"))
                 (when (contains? produced-targets id)
                   (v/reject!
                     "target/push reused a target id in the same frame"))
                 (let [[w h] size]
                   (when-not (and (number? w)
                                  (> (double w) 0.0)
                                  (number? h)
                                  (> (double h) 0.0))
                     (v/reject!
                       "target/push :target/size must be [w h] with w>0, h>0"))))
               (recur (next ops)
                      model-stack
                      clip-depth
                      (conj target-stack {:id (:target/id op)})
                      (conj produced-targets (:target/id op))
                      has-camera?
                      lighting-enabled?)))
           :target/pop (if-not (:supports-render-targets? opts)
                         (v/reject! :unsupported-op
                                    "target/pop not supported by this backend")
                         (if (<= (count target-stack) 1)
                           (v/reject! "target/pop without target/push")
                           (recur (next ops)
                                  model-stack
                                  clip-depth
                                  (rest target-stack)
                                  produced-targets
                                  has-camera?
                                  lighting-enabled?)))
           (:draw/fill-rect :draw/stroke-rect :draw/fill-circle
                            :draw/stroke-circle :draw/path
                            :draw/text :draw/image)
           (do
             (validate-2d! op model-stack target-stack produced-targets opts)
             (recur (next ops)
                    model-stack
                    clip-depth
                    target-stack
                    produced-targets
                    has-camera?
                    lighting-enabled?))
           (:draw3d/lines :draw3d/triangles :draw3d/mesh)
           (do (validate-3d! op
                             has-camera?
                             lighting-enabled?
                             target-stack
                             produced-targets
                             opts)
               (recur (next ops)
                      model-stack
                      clip-depth
                      target-stack
                      produced-targets
                      has-camera?
                      lighting-enabled?))
           (v/reject! :unsupported-op (str "Unknown op kind: " kind))))))))


;; ---------------------------------------------------------------------------
;; Shared lower-frame
;; ---------------------------------------------------------------------------

(defn- viewport
  [{:keys [viewport-width viewport-height viewport-size]}]
  (let [[w h] (cond (and viewport-width viewport-height) [viewport-width
                                                          viewport-height]
                    (fn? viewport-size) (viewport-size)
                    (vector? viewport-size) viewport-size
                    :else [0 0])]
    {:width (double w), :height (double h)}))


(defn- resolve-source!
  [source state {:keys [resolve-resource]}]
  (cond (= source :image/placeholder) {:resource/kind :placeholder}
        (and (vector? source)
             (= 2 (count source))
             (= [:target/id] (subvec source 0 1)))
        (let [target-id (nth source 1)]
          (if (contains? (:target-registry state) target-id)
            {:resource/kind :target-texture, :target/id target-id}
            (v/reject! "Texture source refers to a missing render target")))
        resolve-resource (or (resolve-resource source state)
                             (v/reject!
                               :unloadable-image
                               "Image source is not synchronously realizable"))
        :else source))


(defn- current-pass-index
  [state]
  (:pass-index (first (:target-stack state))))


(defn- append-draw
  [state draw]
  (update-in state [:passes (current-pass-index state) :draws] conj draw))


(defn lower-frame
  ([frame] (lower-frame frame {}))
  ([frame opts]
   (validate-frame! frame opts)
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
               :draw/fill-rect (append-draw state
                                            {:pipeline :draw-2d,
                                             :op/kind kind,
                                             :op op,
                                             :model-m (first (:model-stack
                                                               state)),
                                             :clips (vec (:clip-stack state)),
                                             :op/meta (:op/meta op)})
               :draw/stroke-rect (append-draw state
                                              {:pipeline :draw-2d,
                                               :op/kind kind,
                                               :op op,
                                               :model-m (first (:model-stack
                                                                 state)),
                                               :clips (vec (:clip-stack state)),
                                               :op/meta (:op/meta op)})
               :draw/fill-circle (append-draw state
                                              {:pipeline :draw-2d,
                                               :op/kind kind,
                                               :op op,
                                               :model-m (first (:model-stack
                                                                 state)),
                                               :clips (vec (:clip-stack state)),
                                               :op/meta (:op/meta op)})
               :draw/stroke-circle (append-draw
                                     state
                                     {:pipeline :draw-2d,
                                      :op/kind kind,
                                      :op op,
                                      :model-m (first (:model-stack state)),
                                      :clips (vec (:clip-stack state)),
                                      :op/meta (:op/meta op)})
               :draw/path (append-draw state
                                       {:pipeline :draw-2d,
                                        :op/kind :draw/path,
                                        :op op,
                                        :model-m (first (:model-stack state)),
                                        :clips (vec (:clip-stack state)),
                                        :op/meta (:op/meta op)})
               :draw/text (let [top (matrix4->affine (first (:model-stack
                                                              state)))
                                [x y] (transform-point top (:position op))
                                font-size (double (get op :font-size 14.0))
                                tw (* (count (:text op)) font-size 0.6)
                                x-off (case (get op :align :start)
                                        :center (- (/ tw 2.0))
                                        :end (- tw)
                                        :start 0.0)]
                            (append-draw state
                                         {:pipeline :text,
                                          :op/kind :draw/text,
                                          :text (:text op),
                                          :screen-x (+ x x-off),
                                          :screen-y (- height y),
                                          :font-size font-size,
                                          :font-family (:font-family op),
                                          :color (:color op [0 0 0 1]),
                                          :clips (vec (:clip-stack state)),
                                          :glyphs-transformed? false,
                                          :op/meta (:op/meta op)}))
               :draw/image
               (let [source (:image/source op)
                     resource (when source
                                (resolve-source! source state opts))]
                 (when (and resource (not (map? resource)))
                   (v/reject! :unloadable-image
                              "Image source is not synchronously realizable"))
                 (append-draw state
                              {:pipeline :draw-2d,
                               :op/kind :draw/image,
                               :op (cond-> op
                                     resource (assoc :resource resource)),
                               :model-m (first (:model-stack state)),
                               :clips (vec (:clip-stack state)),
                               :op/meta (:op/meta op)}))
               (:draw3d/lines :draw3d/triangles :draw3d/mesh)
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
                               :lighting-state
                               {:enabled (:lighting-enabled state),
                                :lights (:lights state),
                                :camera-pos (:camera-pos state)},
                               :lights (:lights state),
                               :camera-pos (:camera-pos state),
                               :texture resource,
                               :clips (vec (:clip-stack state)),
                               :payload op',
                               :op/meta (:op/meta op)}))
               state))))))))
