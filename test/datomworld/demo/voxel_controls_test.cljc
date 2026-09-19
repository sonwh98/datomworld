(ns datomworld.demo.voxel-controls-test
  (:require [clojure.test :refer [deftest is]]
            [datomworld.demo.voxel-controls :as controls]))


(defn- right
  [{:keys [x width]}]
  (+ x width))


(defn- bottom
  [{:keys [y height]}]
  (+ y height))


(defn- overlap?
  [a b]
  (and (< (:x a) (right b))
       (< (:x b) (right a))
       (< (:y a) (bottom b))
       (< (:y b) (bottom a))))


(deftest layout-offers-every-motion-action-once
  (let [layout (controls/layout {:width 720.0, :height 540.0})]
    (is (= #{:forward :back :left :right :up :down}
           (set (map :action layout))))
    (is (= 6 (count layout)))
    (is (= 6 (count (set (map :node-id layout)))))))


(deftest layout-fits-the-viewport-without-overlap
  (doseq [size [{:width 720.0, :height 540.0}
                {:width 375.0, :height 300.0}
                {:width 860.0, :height 720.0}]
          :let [layout (controls/layout size)]]
    (doseq [button layout]
      (is (<= 0.0 (:x button)))
      (is (<= 0.0 (:y button)))
      (is (<= (right button) (:width size)))
      (is (<= (bottom button) (:height size)))
      (is (<= 44.0 (:width button)) "tap targets stay finger-sized"))
    (doseq [a layout
            b layout
            :when (not= (:action a) (:action b))]
      (is (not (overlap? a b)) (str (:action a) " overlaps " (:action b))))))


(deftest layout-keeps-the-dpad-left-and-flight-right
  (let [by-action (into {}
                        (map (juxt :action identity))
                        (controls/layout {:width 720.0, :height 540.0}))]
    (is (< (right (:right by-action)) 360.0))
    (is (< (:y (:forward by-action)) (:y (:back by-action))))
    (is (< (:x (:left by-action)) (:x (:right by-action))))
    (is (> (:x (:up by-action)) 360.0))
    (is (< (:y (:up by-action)) (:y (:down by-action))))))


(deftest node-ids-map-back-to-actions
  (doseq [{:keys [action node-id]} (controls/layout {:width 720.0,
                                                     :height 540.0})]
    (is (= action (controls/action-of node-id))))
  (is (nil? (controls/action-of :somewhere/else))))


(defn- press
  [phase id action]
  {:event/kind :pointer,
   :phase phase,
   :node-id (controls/node-id action),
   :pointer {:id id}})


(deftest a-pressed-button-holds-its-action-until-release
  (let [pressed (reduce controls/reduce-pressed
                        {}
                        [(press :down 1 :forward)
                         (press :move 1 :forward)
                         (press :down 2 :right)])]
    (is (= #{:forward :right} (controls/pressed-actions pressed)))
    (is (= #{:right}
           (controls/pressed-actions
             (controls/reduce-pressed pressed (press :up 1 :forward)))))
    (is (= #{:forward}
           (controls/pressed-actions
             (controls/reduce-pressed pressed (press :cancel 2 :right)))))))


(deftest two-fingers-on-one-button-release-independently
  (let [pressed (reduce controls/reduce-pressed
                        {}
                        [(press :down 1 :up) (press :down 2 :up)
                         (press :up 1 :up)])]
    (is (= #{:up} (controls/pressed-actions pressed)))))


(deftest pointers-outside-the-buttons-do-nothing
  (is (= {}
         (controls/reduce-pressed {}
                                  {:event/kind :pointer,
                                   :phase :down,
                                   :node-id :voxel-input/surface,
                                   :pointer {:id 1}}))))
