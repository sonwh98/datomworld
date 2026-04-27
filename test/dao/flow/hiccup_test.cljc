(ns dao.flow.hiccup-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.flow.hiccup :as hiccup]))


(deftest round-trip-empty-scene
  (testing "Empty scene round-trips"
    (let [scene [:flow/scene]]
      (is (= scene (hiccup/datoms->hiccup (hiccup/hiccup->datoms scene)))))))


(deftest round-trip-cube-with-transform
  (testing "Cube with attributes and transform round-trips"
    (let [scene [:flow/scene
                 [:geom/cube
                  {:flow/id :spinner,
                   :transform {:rotate [0 1.5 0]},
                   :material {:color [1 0 0 1]}}]]]
      (is (= scene (hiccup/datoms->hiccup (hiccup/hiccup->datoms scene)))))))


(deftest parent-and-sort-key-assigned
  (testing "Datoms have correct internal shape (parent, sort-key)"
    (let [scene [:flow/scene [:geom/rect {:size [10 20]}]]
          datoms (hiccup/hiccup->datoms scene)
          ;; Find the rect entity ID
          rect-eid (->> datoms
                        (filter (fn [[e a v]]
                                  (and (= a :flow/tag) (= v :geom/rect))))
                        first
                        first)
          ;; Find its parent pointer
          parent-eid (->> datoms
                          (filter (fn [[e a v]]
                                    (and (= e rect-eid) (= a :flow/parent))))
                          first
                          last)
          ;; Find the root tag
          root-tag (->> datoms
                        (filter (fn [[e a v]]
                                  (and (= e parent-eid) (= a :flow/tag))))
                        first
                        last)]
      (is (= :flow/scene root-tag))
      (is (some (fn [[e a v]]
                  (and (= e rect-eid) (= a :flow/sort-key) (number? v)))
                datoms)))))
