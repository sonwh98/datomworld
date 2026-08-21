;; Shared contact geometry and motion projections used by every recognizer
;; machine. Specification: docs/design/dao.gui.event.md section Recognizer
;; Machine Data (contact and motion projections).
(ns dao.gui.event.geom
  (:require #?(:cljd ["dart:math" :as math])
            [dao.gui.event.trace :as trace]))


(defn sqrt
  [x]
  #?(:clj (Math/sqrt x)
     :cljs (js/Math.sqrt x)
     :cljd (math/sqrt x)))


(defn atan2
  [y x]
  #?(:clj (Math/atan2 y x)
     :cljs (js/Math.atan2 y x)
     :cljd (math/atan2 y x)))


(def pi
  #?(:clj Math/PI
     :cljs js/Math.PI
     :cljd math/pi))


(defn contact-ids
  "Active contact ids in stable EDN order."
  [contacts]
  (vec (trace/edn-sort (keys contacts))))


(defn centroid
  "Arithmetic mean of current contact positions."
  [contacts]
  (let [n (count contacts)]
    (if (zero? n)
      {:x 0.0, :y 0.0}
      (let [xs (map (fn [[_id c]] (get-in c [:position :x])) contacts)
            ys (map (fn [[_id c]] (get-in c [:position :y])) contacts)]
        {:x (/ (reduce + 0.0 xs) n), :y (/ (reduce + 0.0 ys) n)}))))


(defn distance
  [p q]
  (let [dx (- (double (:x p)) (double (:x q)))
        dy (- (double (:y p)) (double (:y q)))]
    (sqrt (+ (* dx dx) (* dy dy)))))


(defn span
  "Mean distance from the centroid; zero for fewer than two contacts."
  [contacts]
  (if (< (count contacts) 2)
    0.0
    (let [c (centroid contacts)]
      (/ (reduce +
                 0.0
                 (map (fn [[_id contact]] (distance (:position contact) c)) contacts))
         (count contacts)))))


(defn angle
  "Angle from the lowest pointer id to the next-lowest pointer id,
  normalized to [-pi, pi)."
  [contacts]
  (let [ids (contact-ids contacts)]
    (if (< (count ids) 2)
      0.0
      (let [lo (get-in contacts [(nth ids 0) :position])
            hi (get-in contacts [(nth ids 1) :position])
            raw (atan2 (- (double (:y hi)) (double (:y lo)))
                       (- (double (:x hi)) (double (:x lo))))]
        (cond (< raw (- pi)) (+ raw (* 2 pi))
              (>= raw pi) (- raw (* 2 pi))
              :else raw)))))


(defn window-velocity
  "Ordinary-least-squares velocity over retained window entries. Entries
  are {:time-us :position {:x :y} :pointer-ids [...]}. Duplicate
  timestamps contribute positions but create no elapsed time; fewer than
  two distinct timestamps yields zero."
  [entries]
  (if (or (nil? entries) (empty? entries))
    {:x 0.0, :y 0.0}
    (let [data (vec (map (fn [e]
                           [(double (:time-us e))
                            (double (get-in e [:position :x]))
                            (double (get-in e [:position :y]))])
                         entries))]
      (if (< (count (set (map first data))) 2)
        {:x 0.0, :y 0.0}
        (let [t0 (first (map first data))
              seconds (map (fn [[t _x _y]] (/ (- t t0) 1e6)) data)
              xs (map second data)
              ys (map (fn [[_t _x y]] y) data)
              n (count data)
              mean (fn [vs] (/ (reduce + 0.0 vs) n))
              t-bar (mean seconds)
              x-bar (mean xs)
              y-bar (mean ys)
              denom (reduce +
                            0.0
                            (map (fn [t] (* (- t t-bar) (- t t-bar))) seconds))]
          (if (zero? denom)
            {:x 0.0, :y 0.0}
            {:x (/ (reduce +
                           0.0
                           (map (fn [[t x]] (* (- t t-bar) (- x x-bar)))
                                (map vector seconds xs)))
                   denom),
             :y (/ (reduce +
                           0.0
                           (map (fn [[t y]] (* (- t t-bar) (- y y-bar)))
                                (map vector seconds ys)))
                   denom)}))))))


(defn direction
  "Axis or compass keyword of a vector using the configured axis.
  abs(x) >= abs(y) selects horizontal; a zero vector yields nil."
  [{:keys [x y]} axis]
  (let [x (double (or x 0))
        y (double (or y 0))]
    (cond (and (zero? x) (zero? y)) nil
          (>= (abs x) (abs y)) (case axis
                                 :y nil
                                 (if (neg? x) :left :right))
          :else (case axis
                  :x nil
                  (if (neg? y) :down :up)))))


(defn clamp
  [v lo hi]
  (max lo (min hi v)))


(defn edge-distance
  "Non-negative distance from a position to a viewport edge."
  [{:keys [x y]} edge viewport]
  (case edge
    :left (max 0.0 (double x))
    :right (max 0.0 (- (double (:width viewport)) (double x)))
    :top (max 0.0 (- (double (:height viewport)) (double y)))
    :bottom (max 0.0 (double y))))
