(ns datomworld.demo.voxel-input-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [dao.gui.event :as event]
            [datomworld.demo.voxel-controls :as controls]
            [datomworld.demo.voxel-input :as input]))


(use-fixtures :each
  (fn [run]
    (input/stop!)
    (input/resize! 720.0 540.0)
    (run)
    (input/stop!)
    (input/resize! 720.0 540.0)))


(defn- down
  ([code] (down code {}))
  ([code more]
   (merge {:phase :down, :code code, :logical nil, :location :standard} more)))


(defn- up
  [code]
  {:phase :up, :code code, :logical nil, :location :standard})


(defn- phase-and-code
  [events]
  (mapv (juxt :event/phase (comp :code :key)) events))


(deftest browser-codes-become-portable-codes
  (is (= :key-w (input/browser-code "KeyW")))
  (is (= :space (input/browser-code "Space")))
  (is (= :shift-left (input/browser-code "ShiftLeft")))
  (is (= :arrow-left (input/browser-code "ArrowLeft"))))


(deftest logical-key-is-one-scalar-or-nil
  (is (= "w" (input/logical-key "w")))
  (is (= " " (input/logical-key " ")))
  (is (nil? (input/logical-key "Shift")))
  (is (nil? (input/logical-key "ArrowLeft")))
  (is (nil? (input/logical-key nil))))


(deftest held-codes-reduce-from-keyboard-events
  (let [held (reduce input/reduce-held
                     #{}
                     [{:event/phase :down, :key {:code :key-w}}
                      {:event/phase :down, :key {:code :space}}
                      {:event/phase :down, :key {:code :key-w}}
                      {:event/phase :up, :key {:code :space}}])]
    (is (= #{:key-w} held))
    (is (= #{:space}
           (input/reduce-held #{:key-w :space}
                              {:event/phase :cancel,
                               :released-key-codes #{:key-w}})))
    (is (= #{} (input/reduce-held #{:key-w} {:event/phase :cancel,
                                             :released-key-codes #{:key-w}})))))


(deftest held-codes-map-to-motion-actions
  (is (= #{:forward :up :look-left}
         (input/held-actions #{:key-w :space :arrow-left :key-q})))
  (is (= #{:down} (input/held-actions #{:shift-left :shift-right}))))


(deftest boot-values-are-accepted-without-diagnostics
  (let [values (input/boot-values {:generation-id 1})
        outputs (loop [state (event/initial-state)
                       values (map-indexed (fn [i v]
                                             {:runtime/seq i,
                                              :runtime/time-us i,
                                              :runtime/source (:runtime/source
                                                                v),
                                              :runtime/value (:runtime/value
                                                               v)})
                                           values)
                       outputs []]
                  (if-let [[input & more] (seq values)]
                    (let [{:keys [state outputs]} (event/step state input)]
                      (recur state more outputs))
                    outputs))]
    (is (= 12 (count values)))
    (is (empty? (filter :diagnostic/kind outputs)))))


(deftest keys-flow-through-the-event-stream
  (input/start!)
  (is (= [[:down :key-w]] (phase-and-code (input/key! (down :key-w)))))
  (is (= [[:down :space]] (phase-and-code (input/key! (down :space)))))
  (is (= [[:up :key-w]] (phase-and-code (input/key! (up :key-w))))))


(deftest repeat-of-a-held-key-is-delivered-but-a-duplicate-down-is-not
  (input/start!)
  (input/key! (down :key-w))
  (is (= [[:down :key-w]]
         (phase-and-code (input/key! (down :key-w {:repeat? true})))))
  (is (= [] (input/key! (down :key-w))))
  (is (= [] (input/key! (up :key-a)))))


(deftest focus-loss-cancels-held-keys
  (input/start!)
  (input/key! (down :key-w))
  (input/key! (down :space))
  (let [[cancel :as events] (input/focus-lost!)]
    (is (= 1 (count events)))
    (is (= :cancel (:event/phase cancel)))
    (is (= #{:key-w :space} (:released-key-codes cancel))))
  (is (= [] (input/key! (down :key-d))))
  (is (= [] (input/focus!)))
  (is (= [[:down :key-d]] (phase-and-code (input/key! (down :key-d))))))


(deftest input-is-inert-until-started-and-after-stopped
  (is (= [] (input/key! (down :key-w))))
  (is (= [] (input/focus-lost!)))
  (input/start!)
  (is (= [[:down :key-w]] (phase-and-code (input/key! (down :key-w)))))
  (input/stop!)
  (is (= [] (input/key! (down :key-s))))
  (input/start!)
  (is (= [[:down :key-s]] (phase-and-code (input/key! (down :key-s))))))


(defn- pointer
  ([phase x y] (pointer phase x y {}))
  ([phase x y more]
   (merge {:phase phase,
           :id 1,
           :type :mouse,
           :buttons (if (= :up phase) 0 1),
           :position {:x x, :y y}}
          more)))


(defn- drag
  "Feeds each pointer transition and returns every delivered event."
  [& pointers]
  (vec (mapcat input/pointer! pointers)))


(defn- look-total
  [events]
  (reduce (fn [total event]
            (if-let [{:keys [x y]} (input/look-delta event)]
              (-> total
                  (update :x + x)
                  (update :y + y))
              total))
          {:x 0.0, :y 0.0}
          events))


(deftest look-delta-reads-pan-gestures-only
  (is (= {:x 3.0, :y -4.0}
         (input/look-delta {:event/kind :gesture,
                            :gesture/kind :pan,
                            :payload {:delta {:x 3.0, :y -4.0}}})))
  (is (nil? (input/look-delta {:event/kind :gesture, :gesture/kind :tap})))
  (is (nil? (input/look-delta {:event/kind :keyboard, :event/phase :down}))))


(deftest dragging-the-surface-delivers-pan-deltas
  (input/start!)
  (let [events (drag (pointer :down 100.0 100.0)
                     (pointer :move 110.0 100.0)
                     (pointer :move 130.0 110.0)
                     (pointer :up 130.0 110.0))]
    (is (= :pan (:gesture/kind (first events))))
    (is (= {:x 20.0, :y 10.0} (look-total events)))))


(deftest a-drag-below-the-slop-is-not-a-look
  (input/start!)
  (is (= [] (drag (pointer :down 100.0 100.0)
                  (pointer :move 101.0 101.0)
                  (pointer :up 101.0 101.0)))))


(deftest pointer-input-without-a-press-is-ignored
  (input/start!)
  (is (= [] (drag (pointer :move 50.0 50.0 {:buttons 0})
                  (pointer :move 90.0 90.0 {:buttons 0}))))
  (drag (pointer :down 100.0 100.0) (pointer :up 100.0 100.0))
  (is (= [] (drag (pointer :move 300.0 300.0 {:buttons 0})))))


(deftest only-the-primary-mouse-button-starts-a-look
  (input/start!)
  (is (= [] (drag (pointer :down 100.0 100.0 {:buttons 2})
                  (pointer :move 140.0 140.0 {:buttons 2})
                  (pointer :up 140.0 140.0)))))


(deftest touch-and-mouse-both-look
  (input/start!)
  (is (= {:x 20.0, :y 0.0}
         (look-total (drag (pointer :down 10.0 10.0 {:type :touch, :buttons 0})
                           (pointer :move 30.0 10.0 {:type :touch, :buttons 0})
                           (pointer :move 50.0 10.0 {:type :touch, :buttons 0})
                           (pointer :up 50.0 10.0 {:type :touch}))))))


(deftest the-surface-follows-the-host-size
  (input/start!)
  ;; outside the default 720x540 surface: nothing is hit
  (is (= [] (drag (pointer :down 900.0 100.0)
                  (pointer :move 930.0 100.0)
                  (pointer :move 960.0 100.0)
                  (pointer :up 960.0 100.0))))
  (input/resize! 1000.0 800.0)
  (is (= {:x 30.0, :y 0.0}
         (look-total (drag (pointer :down 900.0 100.0)
                           (pointer :move 930.0 100.0)
                           (pointer :move 960.0 100.0)
                           (pointer :up 960.0 100.0))))))


(deftest the-surface-size-survives-a-restart
  (input/resize! 1000.0 800.0)
  (input/start!)
  (is (= {:x 30.0, :y 0.0}
         (look-total (drag (pointer :down 900.0 100.0)
                           (pointer :move 930.0 100.0)
                           (pointer :move 960.0 100.0)
                           (pointer :up 960.0 100.0))))))


(deftest keys-and-looking-share-one-stream
  (input/start!)
  (input/key! (down :key-w))
  (let [events (drag (pointer :down 100.0 100.0)
                     (pointer :move 120.0 100.0)
                     (pointer :move 140.0 100.0))]
    (is (= {:x 20.0, :y 0.0} (look-total events))))
  (is (= [[:up :key-w]] (phase-and-code (input/key! (up :key-w))))))


(defn- button-center
  [action size]
  (let [{:keys [x y width height]}
        (first (filter #(= action (:action %)) (controls/layout size)))]
    {:x (+ x (/ width 2.0)), :y (+ y (/ height 2.0))}))


(def ^:private default-size {:width 720.0, :height 540.0})


(defn- touch
  ([phase id position] (touch phase id position {}))
  ([phase id position more]
   (merge {:phase phase,
           :id id,
           :type :touch,
           :buttons (if (= :up phase) 0 1),
           :position position}
          more)))


(defn- pressed-after
  "Feeds pointers and returns the actions the on-screen buttons hold."
  [& pointers]
  (controls/pressed-actions
    (reduce controls/reduce-pressed
            {}
            (filter #(= :pointer (:event/kind %))
                    (mapcat input/pointer! pointers)))))


(deftest pressing-a-button-holds-its-action-until-lift
  (input/start!)
  (let [center (button-center :forward default-size)]
    (is (= #{:forward} (pressed-after (touch :down 1 center))))
    (is (= #{} (pressed-after (touch :down 2 center)
                              (touch :up 2 center))))))


(deftest a-button-stays-held-while-the-finger-slides-off
  (input/start!)
  (let [center (button-center :forward default-size)
        events (mapcat input/pointer!
                       [(touch :down 1 center)
                        (touch :move 1 {:x 600.0, :y 100.0})
                        (touch :move 1 {:x 650.0, :y 120.0})])]
    (is (= #{:forward}
           (controls/pressed-actions
             (reduce controls/reduce-pressed
                     {}
                     (filter #(= :pointer (:event/kind %)) events)))))
    (is (empty? (filter input/look-delta events)) "sliding is not a look")
    (is (= #{}
           (controls/pressed-actions
             (reduce controls/reduce-pressed
                     (reduce controls/reduce-pressed
                             {}
                             (filter #(= :pointer (:event/kind %)) events))
                     (filter #(= :pointer (:event/kind %))
                             (input/pointer! (touch :up 1 center)))))))))


(deftest a-button-press-and-a-look-drag-run-at-once
  (input/start!)
  (let [center (button-center :forward default-size)
        events (vec (mapcat input/pointer!
                            [(touch :down 1 center)
                             (touch :down 2 {:x 400.0, :y 100.0})
                             (touch :move 2 {:x 420.0, :y 100.0})
                             (touch :move 2 {:x 450.0, :y 100.0})]))]
    (is (= {:x 30.0, :y 0.0} (look-total events)))
    (is (= #{:forward}
           (controls/pressed-actions
             (reduce controls/reduce-pressed
                     {}
                     (filter #(= :pointer (:event/kind %)) events)))))))


(deftest buttons-follow-the-host-size
  (let [size {:width 375.0, :height 300.0}
        center (button-center :forward size)]
    (input/resize! (:width size) (:height size))
    (input/start!)
    (is (= #{:forward} (pressed-after (touch :down 1 center))))
    (input/stop!)
    (input/resize! 720.0 540.0)
    (input/start!)
    (let [moved (button-center :forward default-size)]
      (is (not= center moved))
      (is (= #{:forward} (pressed-after (touch :down 1 moved)))))))


(deftest a-mouse-click-on-a-button-needs-the-primary-button
  (input/start!)
  (let [center (button-center :back default-size)]
    (is (= #{} (pressed-after {:phase :down,
                               :id 1,
                               :type :mouse,
                               :buttons 2,
                               :position center})))
    (is (= #{:back} (pressed-after {:phase :down,
                                    :id 2,
                                    :type :mouse,
                                    :buttons 1,
                                    :position center})))))
