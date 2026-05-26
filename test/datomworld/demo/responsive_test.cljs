(ns datomworld.demo.responsive-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datomworld.demo.responsive :as responsive]))


(deftest auto-fit-grid-test
  (testing "auto-fit grids cap each column by viewport width"
    (is (= "repeat(auto-fit, minmax(min(100%, 320px), 1fr))"
           (responsive/auto-fit-grid 320)))))


(deftest fluid-height-test
  (testing "fluid heights stay bounded by explicit minimum and maximum"
    (is (= "clamp(280px, 62vh, 720px)"
           (responsive/fluid-height 280 62 720)))))


(deftest canvas-frame-style-test
  (testing "canvas frames fill their column while clamping viewport height"
    (is (= {:width "100%",
            :max-width "900px",
            :height "clamp(300px, 60vh, 680px)",
            :display "block",
            :border "1px solid rgba(160,190,255,0.28)",
            :border-radius "18px",
            :background "#050711",
            :box-shadow "0 28px 90px rgba(0,0,0,0.48)"}
           (responsive/canvas-frame-style {:max-width 900,
                                           :min-height 300,
                                           :height-vh 60,
                                           :max-height 680,
                                           :border-color
                                           "rgba(160,190,255,0.28)",
                                           :border-radius 18,
                                           :background "#050711",
                                           :box-shadow
                                           "0 28px 90px rgba(0,0,0,0.48)"})))))
