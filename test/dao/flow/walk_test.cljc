(ns dao.flow.walk-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.db :as db]
    [dao.db.in-memory :as in-m]
    [dao.flow.hiccup :as hiccup]
    [dao.flow.transform :as t]
    [dao.flow.walk :as walk]))


(def ^:private flow-schema
  [{:db/ident :flow/parent, :db/valueType :db.type/ref}
   {:db/ident :flow/transform, :db/valueType :db.type/ref}
   {:db/ident :flow/material, :db/valueType :db.type/ref}
   {:db/ident :camera, :db/valueType :db.type/ref}])


(defn- empty-flow-db
  []
  (:db-after (db/transact (in-m/empty-db) flow-schema)))


(defn- test-db
  [hiccup]
  (let [db-with-schema (empty-flow-db)
        datoms (hiccup/hiccup->datoms hiccup)
        tx-data (mapv (fn [[e a v]] [:db/add e a v]) datoms)]
    (:db-after (db/transact db-with-schema tx-data))))


(defn- tx-db
  [tx-data]
  (:db-after (db/transact (empty-flow-db) tx-data)))


(defn- translation-of
  [world]
  [(nth world 12) (nth world 13) (nth world 14)])


(def ^:private children-query
  '[:find ?e :in $ ?parent :where [?e :flow/parent ?parent]])


(defn- walk-once-with-child-order
  [db parent-eid child-eids]
  (let [q-original db/q]
    (with-redefs [db/q (fn [query db* & args]
                         (if (and (= query children-query)
                                  (= args (list parent-eid)))
                           (mapv vector child-eids)
                           (apply q-original query db* args)))]
      (walk/walk-once db))))


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
      (is (= [15.0 0.0 0.0] (translation-of (:op/world rect-op)))))))


(deftest geometry-ops-are-sorted-by-descending-depth
  (testing
    "geometry ops are sorted by descending :op/depth before the end-frame"
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
          [first-cube second-cube end-op] frame]
      (is (= 3 (count frame)))
      (is (= :cube (:op/kind first-cube)))
      (is (= :cube (:op/kind second-cube)))
      (is (> (:op/depth first-cube) (:op/depth second-cube)))
      (is (= [0.0 0.0 5.0] (translation-of (:op/world first-cube))))
      (is (= [0.0 0.0 10.0] (translation-of (:op/world second-cube))))
      (is (= :end-frame (:op/kind end-op))))))


(deftest walk-once-ignores-cameras-outside-the-active-scene
  (testing "an unrelated camera in the db must not replace the scene camera"
    (let [db
          (test-db
            [:flow/scene
             [:camera/orthographic
              {:left 0, :right 100, :top 0, :bottom 100, :near 0.1, :far 100}]
             [:geom/rect {:size [10 20]}]])
          ;; Insert an auxiliary camera that is not attached to the scene
          ;; root. The walker should still use the camera under the active
          ;; scene.
          db-with-extra-camera
          (:db-after (db/transact db
                                  [[:db/add 100 :camera/kind :perspective]
                                   [:db/add 100 :camera/fov 45.0]
                                   [:db/add 100 :camera/aspect 1.5]
                                   [:db/add 100 :camera/near 0.5]
                                   [:db/add 100 :camera/far 250.0]]))
          frame (walk/walk-once db-with-extra-camera)
          rect-op (first frame)
          expected-proj (t/orthographic-mat4 0 100 100 0 0.1 100)]
      (is (= :rect (:op/kind rect-op)))
      (is (= expected-proj (:op/projected rect-op))))))


(deftest walk-once-finds-camera-nested-under-scene-group
  (testing
    "a camera nested under a scene descendant group still drives projection"
    (let [db (test-db [:flow/scene
                       [:flow/group
                        [:camera/orthographic
                         {:left 0,
                          :right 100,
                          :top 0,
                          :bottom 100,
                          :near 0.1,
                          :far 100}]] [:geom/rect {:size [10 20]}]])
          frame (walk/walk-once db)
          rect-op (first frame)
          expected-proj (t/orthographic-mat4 0 100 100 0 0.1 100)]
      (is (= :rect (:op/kind rect-op)))
      (is (= expected-proj (:op/projected rect-op))))))


(deftest walk-once-applies-ancestor-transforms-to-nested-camera
  (testing
    "a nested camera inherits ancestor transforms when building clip-from-world"
    (let [db (test-db [:flow/scene
                       [:flow/group {:transform {:translate [10 0 0]}}
                        [:camera/orthographic
                         {:left 0,
                          :right 100,
                          :top 0,
                          :bottom 100,
                          :near 0.1,
                          :far 100}]] [:geom/rect {:size [10 20]}]])
          frame (walk/walk-once db)
          rect-op (first frame)
          proj (t/orthographic-mat4 0 100 100 0 0.1 100)
          view (t/invert-trs [10 0 0] nil nil)
          expected-proj (t/mul-mat4 proj view)]
      (is (= :rect (:op/kind rect-op)))
      (is (= expected-proj (:op/projected rect-op))))))


(deftest walk-once-prefers-earlier-grouped-camera-over-later-sibling-camera
  (testing
    "camera selection follows scene traversal order, not direct-child preference"
    (let [db (test-db [:flow/scene
                       [:flow/group
                        [:camera/orthographic
                         {:left 0,
                          :right 100,
                          :top 0,
                          :bottom 100,
                          :near 0.1,
                          :far 100}]]
                       [:camera/perspective
                        {:fov 45.0, :aspect 1.5, :near 0.5, :far 250.0}]
                       [:geom/rect {:size [10 20]}]])
          frame (walk/walk-once db)
          rect-op (first frame)
          expected-proj (t/orthographic-mat4 0 100 100 0 0.1 100)
          later-camera-proj (t/perspective-mat4 45.0 1.5 0.5 250.0)]
      (is (= :rect (:op/kind rect-op)))
      (is (= expected-proj (:op/projected rect-op)))
      (is (not= later-camera-proj (:op/projected rect-op))))))


(deftest walk-once-emits-lights-nested-under-scene-group
  (testing "a light nested under a scene descendant group remains in the frame"
    (let [db
          (test-db
            [:flow/scene
             [:camera/orthographic
              {:left 0, :right 100, :top 0, :bottom 100, :near 0.1, :far 100}]
             [:flow/group
              [:light/directional
               {:color [1 1 1 1], :intensity 0.75, :direction [0 -1 0]}]]
             [:geom/rect {:size [10 20]}]])
          frame (walk/walk-once db)
          [light-op rect-op end-op] frame]
      (is (= 3 (count frame)))
      (is (= :light (:op/kind light-op)))
      (is (= :directional (:op/light-kind light-op)))
      (is (= [1 1 1 1] (:op/color light-op)))
      (is (= 0.75 (:op/intensity light-op)))
      (is (= [0 -1 0] (:op/direction light-op)))
      (is (= :rect (:op/kind rect-op)))
      (is (= :end-frame (:op/kind end-op))))))


(deftest walk-once-camera-selection-is-stable-when-sibling-sort-keys-tie
  (testing "tied sibling sort keys resolve by eid, not by db/q result order"
    ;; Hiccup assigns unique sibling sort keys, so this fixture is
    ;; hand-written to exercise the explicit tie-break contract.
    (let [db (tx-db
               [[:db/add 1 :flow/scene-root true]
                [:db/add 1 :flow/tag :flow/scene] [:db/add 2 :flow/parent 1]
                [:db/add 2 :flow/tag :camera/orthographic]
                [:db/add 2 :camera/kind :orthographic]
                [:db/add 2 :camera/left 0] [:db/add 2 :camera/right 100]
                [:db/add 2 :camera/top 0] [:db/add 2 :camera/bottom 100]
                [:db/add 2 :camera/near 0.1] [:db/add 2 :camera/far 100]
                [:db/add 3 :flow/parent 1]
                [:db/add 3 :flow/tag :camera/perspective]
                [:db/add 3 :camera/kind :perspective]
                [:db/add 3 :camera/fov 45.0] [:db/add 3 :camera/aspect 1.5]
                [:db/add 3 :camera/near 0.5] [:db/add 3 :camera/far 250.0]
                [:db/add 4 :flow/parent 1] [:db/add 4 :flow/tag :geom/rect]
                [:db/add 4 :geom/kind :rect] [:db/add 4 :rect/size [10 20]]])
          expected-proj (t/orthographic-mat4 0 100 100 0 0.1 100)
          frame-a (walk-once-with-child-order db 1 [2 3 4])
          frame-b (walk-once-with-child-order db 1 [3 2 4])]
      (is (= expected-proj (:op/projected (first frame-a))))
      (is (= expected-proj (:op/projected (first frame-b))))
      (is (= (:op/projected (first frame-a))
             (:op/projected (first frame-b)))))))


(deftest walk-once-light-order-is-stable-when-sibling-sort-keys-tie
  (testing "tied sibling light order resolves by eid, not by db/q result order"
    ;; Hiccup assigns unique sibling sort keys, so this fixture is
    ;; hand-written to exercise the explicit tie-break contract.
    (let [db (tx-db
               [[:db/add 1 :flow/scene-root true]
                [:db/add 1 :flow/tag :flow/scene] [:db/add 2 :flow/parent 1]
                [:db/add 2 :flow/tag :camera/orthographic]
                [:db/add 2 :camera/kind :orthographic]
                [:db/add 2 :camera/left 0] [:db/add 2 :camera/right 100]
                [:db/add 2 :camera/top 0] [:db/add 2 :camera/bottom 100]
                [:db/add 2 :camera/near 0.1] [:db/add 2 :camera/far 100]
                [:db/add 3 :flow/parent 1]
                [:db/add 3 :flow/tag :light/directional]
                [:db/add 3 :light/kind :directional]
                [:db/add 3 :light/color [1 0 0 1]]
                [:db/add 3 :light/intensity 0.25] [:db/add 4 :flow/parent 1]
                [:db/add 4 :flow/tag :light/directional]
                [:db/add 4 :light/kind :directional]
                [:db/add 4 :light/color [0 0 1 1]]
                [:db/add 4 :light/intensity 0.75] [:db/add 5 :flow/parent 1]
                [:db/add 5 :flow/tag :geom/rect] [:db/add 5 :geom/kind :rect]
                [:db/add 5 :rect/size [10 20]]])
          frame-a (walk-once-with-child-order db 1 [2 3 4 5])
          frame-b (walk-once-with-child-order db 1 [2 4 3 5])
          light-order-a (mapv :op/color
                              (take-while #(= :light (:op/kind %)) frame-a))
          light-order-b (mapv :op/color
                              (take-while #(= :light (:op/kind %)) frame-b))]
      (is (= [[1 0 0 1] [0 0 1 1]] light-order-a))
      (is (= [[1 0 0 1] [0 0 1 1]] light-order-b))
      (is (= light-order-a light-order-b)))))
