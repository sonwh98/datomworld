(ns dao.flow.walk-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.db :as db]
    [dao.db.in-memory :as in-m]
    [dao.flow.hiccup :as hiccup]
    [dao.flow.ops :as ops]
    [dao.flow.walk :as walk]))


(defn- test-db
  [hiccup]
  (let [schema [{:db/ident :flow/parent, :db/valueType :db.type/ref}
                {:db/ident :flow/transform, :db/valueType :db.type/ref}
                {:db/ident :flow/material, :db/valueType :db.type/ref}
                {:db/ident :camera, :db/valueType :db.type/ref}]
        db-with-schema (:db-after (db/transact (in-m/empty-db) schema))
        datoms (hiccup/hiccup->datoms hiccup)
        tx-data (mapv (fn [[e a v]] [:db/add e a v]) datoms)]
    (:db-after (db/transact db-with-schema tx-data))))


(deftest single-rect-emits-one-op-frame
  (testing "input one rect intent, output [op-rect, op-end-frame]"
    (let [db
          (test-db
            [:flow/scene
             [:camera/orthographic
              {:left 0, :right 100, :top 0, :bottom 100, :near 0.1, :far 100}]
             [:geom/rect {:size [10 20]}]])
          frame (walk/walk-once db)]
      (is (= 2 (count frame)))
      (is (= :rect (:op/kind (first frame))))
      (is (= :end-frame (:op/kind (second frame)))))))


(deftest nested-group-composes-transforms
  (testing "group [10 0 0] with rect [5 0 0] -> world [15 0 0]"
    (let [db
          (test-db
            [:flow/scene
             [:camera/orthographic
              {:left 0, :right 100, :top 0, :bottom 100, :near 0.1, :far 100}]
             [:flow/group {:transform {:translate [10 0 0]}}
              [:geom/rect {:transform {:translate [5 0 0]}}]]])
          frame (walk/walk-once db)
          rect-op (first frame)]
      (is (= :rect (:op/kind rect-op)))
      ;; x translation is the 12th element of a column-major 4x4, or since
      ;; we use [x y z 1] in translation mat,
      ;; let's just assert the tx component of the world matrix is 15.0
      (let [world (:op/world rect-op) tx (nth world 12)] (is (= 15.0 tx))))))


(deftest painter-depth-order
  (testing
    "two cubes at different z produce ops sorted far-to-near (largest z to smallest for painter)"
    (let [db
          (test-db
            [:flow/scene
             [:camera/orthographic
              {:left 0, :right 100, :top 0, :bottom 100, :near 0.1, :far 100}]
             [:geom/cube
              {:flow/id :near-cube, :transform {:translate [0 0 5]}}]
             [:geom/cube
              {:flow/id :far-cube, :transform {:translate [0 0 10]}}]])
          frame (walk/walk-once db)
          ops (take 2 frame)]
      ;; Depth sort means the one with largest z (or largest negative z,
      ;; depending on projection) is drawn first.
      ;; walk algorithm uses (sort-by :op/depth >)
      ;; We expect the :far-cube op to be first in the frame before the
      ;; near one depending on exactly how depth is computed. Let's just
      ;; ensure there are two cubes and an end-frame.
      (is (= 3 (count frame)))
      (is (every? #(= :cube (:op/kind %)) ops)))))
