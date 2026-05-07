(ns datomworld.demo.flow-scene-state-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.ringbuffer :as ring]
    [datomworld.demo.flow-scene-state :as scene :refer
     [initial-state]]))


(defn- make-prim
  []
  (ring/make-ring-buffer-stream nil))


(deftest init-scene-puts-frame-on-primitive-stream
  (testing "init-scene! produces a frame at position 0"
    (let [prim (make-prim)
          s (scene/create-demo-state prim {})]
      (scene/init-scene! s)
      (is (map? (ds/next prim {:position 0}))))))


(deftest translate-puts-second-frame
  (testing "translate! after init produces a second frame"
    (let [prim (make-prim)
          s (scene/create-demo-state prim {})]
      (scene/init-scene! s)
      (scene/translate! s :earth 5.0 0.0 0.0)
      (is (map? (ds/next prim {:position 1}))))))


(deftest translate-updates-state-atom
  (testing "translate! mutates the state atom via the interpreter"
    (let [prim (make-prim)
          s (scene/create-demo-state prim {})]
      (scene/translate! s :earth 5.0 0.0 0.0)
      (is (= [5.0 0.0 0.0]
             (get-in @(:state-atom s) [:bodies :earth :translate]))))))


(deftest frame-has-geometry-and-end-frame
  (testing "rendered frame contains geometry ops and a trailing :end-frame"
    (let [prim (make-prim)
          s (scene/create-demo-state prim {})]
      (scene/init-scene! s)
      (let [result (ds/next prim {:position 0})
            frame (:ok result)]
        (is (some #(#{:cube :sphere} (:op/kind %)) frame))
        (is (= :end-frame (:op/kind (last frame))))))))


(deftest reset-restores-initial-state
  (testing "reset-scene! restores state atom to initial-state"
    (let [prim (make-prim)
          s (scene/create-demo-state prim {})]
      (scene/translate! s :earth 99.0 0.0 0.0)
      (scene/reset-scene! s)
      (is (= initial-state @(:state-atom s))))))


(deftest bodies-excludes-groups
  (testing "bodies does not include :group kind entries"
    (let [prim (make-prim)
          s (scene/create-demo-state prim {})]
      (is (not (some #{:moon-pivot} (scene/bodies s))))
      (is (some #{:earth :moon :sun} (scene/bodies s))))))
