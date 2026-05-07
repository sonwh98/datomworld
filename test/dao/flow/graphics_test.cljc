(ns dao.flow.graphics-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.flow.graphics :as graphics]
    [dao.stream :as ds]
    [dao.stream.ringbuffer :as ring]))


(def ^:private initial-state
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
            :pivot {:kind :group, :parent :sun, :rotate [0 0 0]},
            :earth {:kind :cube,
                    :translate [11.0 0 0],
                    :scale [0.7 0.7 0.7],
                    :color [0.2 0.5 0.9 1.0]}}})


(defn- make-scene
  []
  (graphics/create-scene initial-state (ring/make-ring-buffer-stream nil) {}))


(defn- init-frame!
  [s prim]
  (graphics/init-scene! s)
  (:ok (ds/next prim {:position 0})))


;; ── existing contract
;; ─────────────────────────────────────────────────────────

(deftest init-scene-puts-frame-on-primitive-stream
  (testing "init-scene! produces a frame at position 0"
    (let [prim (ring/make-ring-buffer-stream nil)
          s (graphics/create-scene initial-state prim {})]
      (graphics/init-scene! s)
      (is (map? (ds/next prim {:position 0}))))))


(deftest translate-puts-second-frame
  (testing "translate! after init produces a second frame"
    (let [prim (ring/make-ring-buffer-stream nil)
          s (graphics/create-scene initial-state prim {})]
      (graphics/init-scene! s)
      (graphics/translate! s :earth 5.0 0.0 0.0)
      (is (map? (ds/next prim {:position 1}))))))


(deftest create-scene-uses-a-bounded-tx-stream
  (testing
    "create-scene drains its internal tx stream while interpreting commands"
    (let [prim (ring/make-ring-buffer-stream 0)
          s (graphics/create-scene initial-state prim {})]
      (dotimes [_ 1024] (graphics/init-scene! s))
      (is (= 0 (count (:tx-stream s))))
      (graphics/init-scene! s)
      (is (= 0 (count (:tx-stream s)))))))


(deftest translate-updates-state-atom
  (testing "translate! mutates the state atom via the interpreter"
    (let [s (make-scene)]
      (graphics/translate! s :earth 5.0 0.0 0.0)
      (is (= [5.0 0.0 0.0]
             (get-in @(:state-atom s) [:bodies :earth :translate]))))))


(deftest frame-has-geometry-and-end-frame
  (testing "rendered frame contains geometry ops and a trailing :end-frame"
    (let [prim (ring/make-ring-buffer-stream nil)
          s (graphics/create-scene initial-state prim {})]
      (graphics/init-scene! s)
      (let [frame (:ok (ds/next prim {:position 0}))]
        (is (some #(#{:cube :sphere} (:op/kind %)) frame))
        (is (= :end-frame (:op/kind (last frame))))))))


(deftest reset-restores-initial-state
  (testing "reset-scene! restores state atom to initial-state"
    (let [s (make-scene)]
      (graphics/translate! s :earth 99.0 0.0 0.0)
      (graphics/reset-scene! s)
      (is (= initial-state @(:state-atom s))))))


(deftest bodies-excludes-groups
  (testing "bodies does not include :group kind entries"
    (let [s (make-scene)]
      (is (not (some #{:pivot} (graphics/bodies s))))
      (is (some #{:earth :sun} (graphics/bodies s))))))


;; ── camera
;; ────────────────────────────────────────────────────────────────────

(deftest camera-rotate-updates-rotation
  (let [s (make-scene)]
    (graphics/camera-rotate! s 0.1 0.2 0.3)
    (is (= [0.1 0.2 0.3] (get-in @(:state-atom s) [:camera :rotate])))))


(deftest camera-fov-updates-fov
  (let [s (make-scene)]
    (graphics/camera-fov! s 75.0)
    (is (= 75.0 (get-in @(:state-atom s) [:camera :fov])))))


(deftest camera-ortho-sets-orthographic-projection
  (let [s (make-scene)]
    (graphics/camera-ortho! s -5 5 -5 5)
    (let [cam (get @(:state-atom s) :camera)]
      (is (= :orthographic (:projection cam)))
      (is (= -5.0 (:left cam)))
      (is (= 5.0 (:right cam))))))


;; ── body management
;; ───────────────────────────────────────────────────────────

(deftest remove-body-removes-from-bodies
  (let [s (make-scene)]
    (graphics/remove-body! s :earth)
    (is (not (contains? (:bodies @(:state-atom s)) :earth)))))


(deftest body-visible-false-excludes-from-frame
  (let [prim (ring/make-ring-buffer-stream nil)
        s (graphics/create-scene initial-state prim {})]
    (graphics/body-visible! s :sun false)
    (let [frame (init-frame! s prim)]
      (is (not (some #(= :sphere (:op/kind %)) frame))))))


(deftest scale-xyz-applies-non-uniform-scale
  (let [s (make-scene)]
    (graphics/scale-xyz! s :earth 1.0 2.0 3.0)
    (is (= [1.0 2.0 3.0] (get-in @(:state-atom s) [:bodies :earth :scale])))))


(deftest add-sphere-adds-sphere-body
  (let [s (make-scene)]
    (graphics/add-sphere! s :star 3 0 0 1.0 0.8 0.0)
    (is (= :sphere (get-in @(:state-atom s) [:bodies :star :kind])))))


;; ── 2D shapes
;; ─────────────────────────────────────────────────────────────────

(deftest add-rect-adds-rect-body-with-dimensions
  (let [s (make-scene)]
    (graphics/add-rect! s :panel 0 0 100 50 0.5 0.5 0.5)
    (let [b (get-in @(:state-atom s) [:bodies :panel])]
      (is (= :rect (:kind b)))
      (is (= 100.0 (:width b)))
      (is (= 50.0 (:height b))))))


(deftest add-circle-adds-circle-body-with-radius
  (let [s (make-scene)]
    (graphics/add-circle! s :dot 5 5 2.5 1 0 0)
    (let [b (get-in @(:state-atom s) [:bodies :dot])]
      (is (= :circle (:kind b)))
      (is (= 2.5 (:radius b))))))


(deftest add-text-adds-text-body-with-content
  (let [s (make-scene)]
    (graphics/add-text! s :label 10 10 "hello" 14 0 0 0)
    (let [b (get-in @(:state-atom s) [:bodies :label])]
      (is (= :text (:kind b)))
      (is (= "hello" (:text b)))
      (is (= 14.0 (:font-size b))))))


(deftest add-path-adds-path-body-with-data
  (let [s (make-scene)]
    (graphics/add-path! s :stroke 0 0 "M0,0 L10,10" 1 0 0)
    (let [b (get-in @(:state-atom s) [:bodies :stroke])]
      (is (= :path (:kind b)))
      (is (= "M0,0 L10,10" (:path-data b))))))


(deftest rect-appears-in-frame
  (let [prim (ring/make-ring-buffer-stream nil)
        s (graphics/create-scene initial-state prim {})]
    (graphics/add-rect! s :panel 0 0 100 50 0.5 0.5 0.5)
    (let [frame (init-frame! s prim)]
      (is (some #(= :rect (:op/kind %)) frame)))))


(deftest rect-op-carries-width-and-height
  (let [prim (ring/make-ring-buffer-stream nil)
        s (graphics/create-scene initial-state prim {})]
    (graphics/add-rect! s :panel 0 0 100 50 0.5 0.5 0.5)
    (let [frame (init-frame! s prim)
          rect-op (first (filter #(= :rect (:op/kind %)) frame))]
      (is (= 100.0 (:width rect-op)))
      (is (= 50.0 (:height rect-op))))))


;; ── lights
;; ────────────────────────────────────────────────────────────────────

(deftest add-light-adds-to-lights
  (let [s (make-scene)]
    (graphics/add-light! s :key :directional [1 1 1 1] 0.8 [0 -1 0])
    (is (= :directional (get-in @(:state-atom s) [:lights :key :kind])))))


(deftest remove-light-removes-from-lights
  (let [s (make-scene)]
    (graphics/add-light! s :key :directional [1 1 1 1] 0.8 [0 -1 0])
    (graphics/remove-light! s :key)
    (is (not (contains? (:lights @(:state-atom s)) :key)))))


(deftest frame-contains-light-ops-when-lights-present
  (let [prim (ring/make-ring-buffer-stream nil)
        s (graphics/create-scene initial-state prim {})]
    (graphics/add-light! s :key :directional [1 1 1 1] 1.0 [0 -1 0])
    (let [frame (init-frame! s prim)]
      (is (some #(= :light (:op/kind %)) frame)))))
