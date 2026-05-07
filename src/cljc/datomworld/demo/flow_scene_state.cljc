(ns datomworld.demo.flow-scene-state
  (:require
    [dao.flow.transform :as t]
    [dao.runtime :as rt]
    [dao.stream.ringbuffer :as ring]))


;; =============================================================================
;; State
;; =============================================================================

(def initial-state
  {:camera {:fov 55.0,
            :near 0.1,
            :far 200.0,
            :translate [0 16 34],
            :rotate [-0.44 0 0]},
   :bodies {:sun {:kind :sphere,
                  :translate [0 0 0],
                  :rotate [0 0 0],
                  :scale [3 3 3],
                  :color [1.0 0.75 0.1 1.0]},
            :mercury {:kind :cube,
                      :translate [5.5 0 0],
                      :scale [0.35 0.35 0.35],
                      :color [0.55 0.55 0.55 1.0]},
            :venus {:kind :cube,
                    :translate [8.0 0 0],
                    :scale [0.65 0.65 0.65],
                    :color [0.9 0.75 0.4 1.0]},
            :earth {:kind :cube,
                    :translate [11.0 0 0],
                    :scale [0.7 0.7 0.7],
                    :color [0.2 0.5 0.9 1.0]},
            :moon-pivot {:kind :group, :parent :earth, :rotate [0 0 0]},
            :moon {:kind :cube,
                   :parent :moon-pivot,
                   :translate [1.5 0 0],
                   :scale [0.25 0.25 0.25],
                   :color [0.75 0.75 0.78 1.0]},
            :mars {:kind :cube,
                   :translate [14.5 0 0],
                   :scale [0.5 0.5 0.5],
                   :color [0.75 0.25 0.1 1.0]}}})


;; =============================================================================
;; Command Interpreter
;; =============================================================================

(defn- apply-command
  [state [op id arg]]
  (case op
    :body/translate (assoc-in state [:bodies id :translate] arg)
    :body/rotate (assoc-in state [:bodies id :rotate] arg)
    :body/color (assoc-in state [:bodies id :color] arg)
    :body/scale (assoc-in state [:bodies id :scale] arg)
    :camera/translate (assoc-in state [:camera :translate] id) ; scene cmd:
    ;; value in slot
    ;; 1
    :scene/add-body (assoc-in state [:bodies id] arg)
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


(defn- render
  [{:keys [camera bodies]}]
  (let [view (t/invert-trs (:translate camera) (:rotate camera) nil)
        proj (t/perspective-mat4 (:fov camera) 1.0 (:near camera) (:far camera))
        cam-mat (t/mul-mat4 proj view)
        ops (for [[id body] bodies
                  :when (not= (:kind body) :group)]
              (let [world (compose-world bodies id)
                    clip (t/mul-mat4 cam-mat world)
                    z (nth clip 14)
                    w (nth clip 15)
                    depth (if (zero? w) z (/ z w))]
                {:op/kind (:kind body),
                 :op/world world,
                 :op/projected cam-mat,
                 :op/depth depth,
                 :color (:color body)}))]
    (conj (vec (sort-by :op/depth > ops)) {:op/kind :end-frame})))


;; =============================================================================
;; Scene Interpreter Task
;; =============================================================================

(defn- make-scene-interpreter
  [state-atom primitive-stream]
  (letfn [(resume
            [rt entry cmd]
            (swap! state-atom apply-command cmd)
            (let [frame (render @state-atom)
                  {rt' :state} (rt/handle-write rt primitive-stream frame nil)
                  next-cursor (update (:cursor entry) :position inc)
                  {rt'' :state} (rt/handle-read rt'
                                                (:stream entry)
                                                next-cursor
                                                {:resume resume})]
              rt''))]
    {:resume resume}))


(defn- put-command!
  [{:keys [tx-stream]} cmd]
  (let [{rt' :state} (rt/handle-write (rt/initial-state) tx-stream cmd nil)]
    (rt/run-loop rt')))


;; =============================================================================
;; Public API
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


(defn camera-translate!
  [state x y z]
  (put-command! state [:camera/translate [(double x) (double y) (double z)]]))


(defn add-body!
  [state id x y z r g b]
  (put-command! state
                [:scene/add-body id
                 {:kind :cube,
                  :translate [(double x) (double y) (double z)],
                  :scale [0.5 0.5 0.5],
                  :color [(double r) (double g) (double b) 1.0]}]))


(defn stop-all!
  [{:keys [animations-atom]}]
  (doseq [[_ stop-fn] @animations-atom] (stop-fn))
  (reset! animations-atom {})
  :stopped)


(defn init-scene!
  [state]
  (put-command! state [:scene/render]))


(defn reset-scene!
  [state]
  (stop-all! state)
  (put-command! state [:scene/reset])
  :reset)


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
   'color! (fn [id r g b] (color! state (keyword id) r g b)),
   'scale! (fn [id s] (scale! state (keyword id) s)),
   'add-body! (fn [id x y z r g b] (add-body! state (keyword id) x y z r g b)),
   'camera! (fn [x y z] (camera-translate! state x y z)),
   'stop! (fn [] (stop-all! state)),
   'reset! (fn [] (reset-scene! state))})


(defn create-demo-state
  [primitive-stream {:keys [schedule-every!]}]
  (let [state-atom (atom initial-state)
        tx-stream (ring/make-ring-buffer-stream nil)
        interp-task (make-scene-interpreter state-atom primitive-stream)
        _ (rt/handle-read (rt/initial-state)
                          tx-stream
                          {:position 0}
                          interp-task)]
    {:state-atom state-atom,
     :tx-stream tx-stream,
     :primitive-stream primitive-stream,
     :animations-atom (atom {}),
     :schedule-every! schedule-every!}))
