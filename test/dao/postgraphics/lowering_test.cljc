(ns dao.postgraphics.lowering-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.postgraphics.lowering :as l]))


(deftest validate-frame-rejects-empty-target-stack
  (is (thrown? #?(:clj Exception
                  :cljs js/Error)
        (l/validate-frame! [{:op/kind :target/pop}]))))


(deftest validate-frame-rejects-non-vector-frame
  (is (thrown? #?(:clj Exception
                  :cljs js/Error)
        (l/validate-frame! {:op/kind :frame/clear}))))


(deftest lower-frame-produces-canonical-clear
  (let [lowered (l/lower-frame [{:op/kind :frame/clear, :color [1 0 0 1]}]
                               {:viewport-width 100, :viewport-height 50})]
    (is (= {:width 100.0, :height 50.0} (:viewport lowered)))
    (is (= 1 (count (:passes lowered))))
    (is (= :default (:target-id (first (:passes lowered)))))
    (let [draw (first (get-in lowered [:passes 0 :draws]))]
      (is (= :clear (:pipeline draw)))
      (is (= [1 0 0 1] (:color draw))))))


(deftest lower-frame-produces-draw-2d-with-model-m
  (let [lowered (l/lower-frame [{:op/kind :draw/fill-rect,
                                 :rect [10 20 30 40],
                                 :color [1 0 0 1]}]
                               {:viewport-width 200, :viewport-height 100})
        draw (first (get-in lowered [:passes 0 :draws]))]
    (is (= :draw-2d (:pipeline draw)))
    (is (= [1 0 0 1] (get-in draw [:op :color])))
    (is (= 16 (count (:model-m draw))))))


(deftest lower-frame-produces-text-with-screen-position
  (let [lowered (l/lower-frame [{:op/kind :draw/text,
                                 :text "hello",
                                 :position [10 20],
                                 :color [0 0 0 1]}]
                               {:viewport-width 200, :viewport-height 100})
        draw (first (get-in lowered [:passes 0 :draws]))]
    (is (= :text (:pipeline draw)))
    (is (number? (:screen-x draw)))
    (is (number? (:screen-y draw)))))


(deftest lower-frame-produces-3d-draw-with-mvp
  (let [frame [{:op/kind :camera3d/set,
                :camera3d/projection :perspective,
                :camera3d/fov 45.0,
                :camera3d/near 0.1,
                :camera3d/far 10.0,
                :camera3d/position [0.0 0.0 5.0],
                :camera3d/rotation [0.0 0.0 0.0]}
               {:op/kind :state/depth-test, :enabled true}
               {:op/kind :state/depth-write, :enabled true}
               {:op/kind :state/lighting-enable, :enabled true}
               {:op/kind :light/ambient, :color [0.1 0.2 0.3]}
               {:op/kind :transform/push, :translate [1.0 2.0 3.0]}
               {:op/kind :draw3d/mesh,
                :vertices [[0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0]],
                :indices [[0 1 2]],
                :normals [[0.0 0.0 1.0] [0.0 0.0 1.0] [0.0 0.0 1.0]],
                :fill [1.0 1.0 1.0 1.0]} {:op/kind :transform/pop}]
        lowered (l/lower-frame frame {:viewport-width 100, :viewport-height 50})
        draws (get-in lowered [:passes 0 :draws])
        camera-reset (first draws)
        draw (second draws)]
    (is (= :camera-reset (:pipeline camera-reset)))
    (is (= :mesh-3d (:pipeline draw)))
    (is (= true (:depth-test draw)))
    (is (= true (:depth-write draw)))
    (is (= true (:lighting-enabled draw)))
    (is (= [{:kind :ambient, :color [0.1 0.2 0.3], :intensity 1.0}]
           (:lights draw)))
    (is (vector? (:mvp draw)))
    (is (= 16 (count (:mvp draw))))
    (is (= 16 (count (:model-m draw))))))


(deftest parameterized-validation-rejects-targets-when-unsupported
  (is (thrown? #?(:clj Exception
                  :cljs js/Error)
        (l/validate-frame!
          [{:op/kind :target/push, :target/id :a, :target/size [64 64]}]
          {:supports-render-targets? false}))))


(deftest parameterized-validation-accepts-targets-when-supported
  (is (= [{:op/kind :target/push, :target/id :a, :target/size [64 64]}
          {:op/kind :target/pop}]
         (l/validate-frame! [{:op/kind :target/push,
                              :target/id :a,
                              :target/size [64 64]} {:op/kind :target/pop}]
                            {:supports-render-targets? true}))))


(deftest parameterized-validation-rejects-image-when-unsupported
  (is (thrown? #?(:clj Exception
                  :cljs js/Error)
        (l/validate-frame! [{:op/kind :draw/image,
                             :image/source :some-image,
                             :rect [0 0 10 10]}]
                           {:supports-image? false}))))
