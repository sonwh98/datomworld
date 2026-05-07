(ns dao.flow.graphics
  "Scene graph producer for dao.flow.

  Maintains a camera-and-bodies scene state behind a command stream. Every
  mutation is a data command put onto a tx-stream; a scene interpreter task
  reads commands, applies them to the state atom, and puts a rendered frame
  onto the primitive-stream. Nothing writes the state atom directly — all
  change is mediated by the stream interpreter.

  Frames are vectors of dao.flow.frame op-datoms consumed by renderer backends
  (dao.flow.flutter, future GPU backends). The same frame vocabulary serves
  both 2D (orthographic camera, rect/circle/text/path bodies) and 3D
  (perspective camera, cube/sphere bodies with parent-child transforms)."
  (:require
    [dao.flow :as flow]
    [dao.flow.frame :as frame]
    [dao.flow.transform :as t]
    [dao.runtime :as rt]
    [dao.stream :as ds]))


;; =============================================================================
;; Command Interpreter
;; =============================================================================

(defn- apply-command
  [initial-state state [op id arg]]
  (case op
    ;; body mutations
    :body/translate (assoc-in state [:bodies id :translate] arg)
    :body/rotate (assoc-in state [:bodies id :rotate] arg)
    :body/color (assoc-in state [:bodies id :color] arg)
    :body/scale (assoc-in state [:bodies id :scale] arg)
    :body/visible (assoc-in state [:bodies id :visible] arg)
    ;; camera — value lands in id slot (scene commands, no entity id)
    :camera/translate (assoc-in state [:camera :translate] id)
    :camera/rotate (assoc-in state [:camera :rotate] id)
    :camera/fov (assoc-in state [:camera :fov] id)
    :camera/ortho (update state :camera merge id)
    ;; scene
    :scene/add-body (assoc-in state [:bodies id] arg)
    :scene/remove-body (update state :bodies dissoc id)
    :scene/add-light (assoc-in state [:lights id] arg)
    :scene/remove-light (update state :lights dissoc id)
    :scene/reset initial-state
    :scene/render state
    state))


;; =============================================================================
;; Render
;; =============================================================================

(defn- compose-world
  [bodies id]
  (let [body (get bodies id)
        local (t/compose-trs (:translate body) (:rotate body) (:scale body))]
    (if-let [parent-id (:parent body)]
      (t/mul-mat4 (compose-world bodies parent-id) local)
      local)))


(defn- projection-mat4
  [camera]
  (if (= :orthographic (:projection camera))
    (t/orthographic-mat4 (get camera :left -10.0)
                         (get camera :right 10.0)
                         (get camera :bottom -10.0)
                         (get camera :top 10.0)
                         (get camera :near -1.0)
                         (get camera :far 1.0))
    (t/perspective-mat4 (get camera :fov 55.0)
                        1.0
                        (get camera :near 0.1)
                        (get camera :far 200.0))))


(defn- render
  [{:keys [camera bodies lights]}]
  (let [view (t/invert-trs (:translate camera) (:rotate camera) nil)
        proj (projection-mat4 camera)
        cam-mat (t/mul-mat4 proj view)
        light-ops (mapv (fn [[_ light]] (frame/op-light light)) (or lights {}))
        geo-ops (for [[id body] bodies
                      :when (and (not= (:kind body) :group)
                                 (not (false? (:visible body))))]
                  (let [world (compose-world bodies id)
                        clip (t/mul-mat4 cam-mat world)
                        z (nth clip 14)
                        w (nth clip 15)
                        depth (if (zero? w) z (/ z w))]
                    (merge {:op/kind (:kind body),
                            :op/world world,
                            :op/projected cam-mat,
                            :op/depth depth,
                            :color (:color body)}
                           (select-keys body
                                        [:width :height :radius :text :font-size
                                         :path-data]))))]
    (conj (into light-ops (sort-by :op/depth > geo-ops))
          {:op/kind :end-frame})))


;; =============================================================================
;; Scene Interpreter Task
;; =============================================================================

(defn- make-scene-interpreter
  [initial-state state-atom primitive-stream]
  (letfn [(resume
            [rt entry cmd]
            (swap! state-atom (partial apply-command initial-state) cmd)
            (let [frame (render @state-atom)
                  {rt' :state} (rt/handle-write rt primitive-stream frame nil)
                  _ (ds/drain-one! (:stream entry))
                  next-cursor (update (:cursor entry) :position inc)
                  {rt'' :state} (rt/handle-read rt'
                                                (:stream entry)
                                                next-cursor
                                                {:resume resume})]
              rt''))]
    {:resume resume}))


(defn- put-command!
  [{:keys [tx-stream]} cmd]
  (flow/stream-put! tx-stream cmd))


;; =============================================================================
;; Public API — transforms
;; =============================================================================

(defn translate!
  [state id x y z]
  (put-command! state [:body/translate id [(double x) (double y) (double z)]]))


(defn rotate-body!
  [state id rx ry rz]
  (put-command! state [:body/rotate id [(double rx) (double ry) (double rz)]]))


(defn color!
  [state id r g b]
  (put-command! state [:body/color id [(double r) (double g) (double b) 1.0]]))


(defn scale!
  [state id s]
  (put-command! state [:body/scale id [(double s) (double s) (double s)]]))


(defn scale-xyz!
  [state id sx sy sz]
  (put-command! state [:body/scale id [(double sx) (double sy) (double sz)]]))


(defn body-visible!
  [state id visible?]
  (put-command! state [:body/visible id visible?]))


;; =============================================================================
;; Public API — camera
;; =============================================================================

(defn camera-translate!
  [state x y z]
  (put-command! state [:camera/translate [(double x) (double y) (double z)]]))


(defn camera-rotate!
  [state rx ry rz]
  (put-command! state [:camera/rotate [(double rx) (double ry) (double rz)]]))


(defn camera-fov!
  [state fov]
  (put-command! state [:camera/fov (double fov)]))


(defn camera-ortho!
  ([state left right bottom top]
   (camera-ortho! state left right bottom top -1.0 1.0))
  ([state left right bottom top near far]
   (put-command! state
                 [:camera/ortho
                  {:projection :orthographic,
                   :left (double left),
                   :right (double right),
                   :bottom (double bottom),
                   :top (double top),
                   :near (double near),
                   :far (double far)}])))


;; =============================================================================
;; Public API — body management
;; =============================================================================

(defn add-body!
  [state id x y z r g b]
  (put-command! state
                [:scene/add-body id
                 {:kind :cube,
                  :translate [(double x) (double y) (double z)],
                  :scale [0.5 0.5 0.5],
                  :color [(double r) (double g) (double b) 1.0]}]))


(defn add-sphere!
  [state id x y z r g b]
  (put-command! state
                [:scene/add-body id
                 {:kind :sphere,
                  :translate [(double x) (double y) (double z)],
                  :scale [1.0 1.0 1.0],
                  :color [(double r) (double g) (double b) 1.0]}]))


(defn remove-body!
  [state id]
  (put-command! state [:scene/remove-body id]))


;; =============================================================================
;; Public API — 2D shapes
;; =============================================================================

(defn add-rect!
  [state id x y w h r g b]
  (put-command! state
                [:scene/add-body id
                 {:kind :rect,
                  :translate [(double x) (double y) 0.0],
                  :width (double w),
                  :height (double h),
                  :color [(double r) (double g) (double b) 1.0]}]))


(defn add-circle!
  [state id x y radius r g b]
  (put-command! state
                [:scene/add-body id
                 {:kind :circle,
                  :translate [(double x) (double y) 0.0],
                  :radius (double radius),
                  :color [(double r) (double g) (double b) 1.0]}]))


(defn add-text!
  [state id x y text font-size r g b]
  (put-command! state
                [:scene/add-body id
                 {:kind :text,
                  :translate [(double x) (double y) 0.0],
                  :text text,
                  :font-size (double font-size),
                  :color [(double r) (double g) (double b) 1.0]}]))


(defn add-path!
  [state id x y path-data r g b]
  (put-command! state
                [:scene/add-body id
                 {:kind :path,
                  :translate [(double x) (double y) 0.0],
                  :path-data path-data,
                  :color [(double r) (double g) (double b) 1.0]}]))


;; =============================================================================
;; Public API — lights
;; =============================================================================

(defn add-light!
  [state id kind color intensity direction]
  (put-command! state
                [:scene/add-light id
                 {:kind kind,
                  :color color,
                  :intensity (double intensity),
                  :direction direction}]))


(defn remove-light!
  [state id]
  (put-command! state [:scene/remove-light id]))


;; =============================================================================
;; Public API — scene lifecycle
;; =============================================================================

(defn init-scene!
  [state]
  (put-command! state [:scene/render]))


(defn reset-scene!
  [state]
  (put-command! state [:scene/reset]) :reset)


(defn bodies
  [{:keys [state-atom]}]
  (->> (:bodies @state-atom)
       (remove (fn [[_ v]] (= (:kind v) :group)))
       (map first)
       sort
       vec))


(defn repl-primitives
  [state]
  {'bodies (fn [] (bodies state)),
   'translate! (fn [id x y z] (translate! state (keyword id) x y z)),
   'rotate-body! (fn [id rx ry rz] (rotate-body! state (keyword id) rx ry rz)),
   'color! (fn [id r g b] (color! state (keyword id) r g b)),
   'scale! (fn [id s] (scale! state (keyword id) s)),
   'scale-xyz! (fn [id sx sy sz] (scale-xyz! state (keyword id) sx sy sz)),
   'add-body! (fn [id x y z r g b] (add-body! state (keyword id) x y z r g b)),
   'add-sphere! (fn [id x y z r g b]
                  (add-sphere! state (keyword id) x y z r g b)),
   'add-rect! (fn [id x y w h r g b]
                (add-rect! state (keyword id) x y w h r g b)),
   'add-circle! (fn [id x y radius r g b]
                  (add-circle! state (keyword id) x y radius r g b)),
   'add-text! (fn [id x y text fs r g b]
                (add-text! state (keyword id) x y text fs r g b)),
   'add-path! (fn [id x y path r g b]
                (add-path! state (keyword id) x y path r g b)),
   'remove-body! (fn [id] (remove-body! state (keyword id))),
   'camera! (fn [x y z] (camera-translate! state x y z)),
   'camera-rotate! (fn [rx ry rz] (camera-rotate! state rx ry rz)),
   'camera-fov! (fn [fov] (camera-fov! state fov)),
   'camera-ortho! (fn [l r b top] (camera-ortho! state l r b top)),
   'add-light! (fn [id kind intensity]
                 (add-light! state
                             (keyword id)
                             (keyword kind)
                             [1.0 1.0 1.0 1.0]
                             intensity
                             [0.0 -1.0 0.0])),
   'remove-light! (fn [id] (remove-light! state (keyword id))),
   'reset! (fn [] (reset-scene! state))})


;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-scene
  [initial-state primitive-stream]
  (let [state-atom (atom initial-state)
        tx-stream (ds/open! {:type :ringbuffer,
                             :capacity 1024,
                             :eviction-policy :evict-oldest})
        interp-task
        (make-scene-interpreter initial-state state-atom primitive-stream)
        _ (rt/handle-read (rt/initial-state)
                          tx-stream
                          {:position 0}
                          interp-task)]
    {:state-atom state-atom,
     :tx-stream tx-stream,
     :primitive-stream primitive-stream}))
