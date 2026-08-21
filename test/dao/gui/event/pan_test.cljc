;; Pan, swipe, and fling machines: slop acceptance, axis qualification,
;; start-at modes, contact-loss policies, velocity windows, and the child
;; tap versus ancestor scroll competition. Specification:
;; docs/design/dao.gui.event.md sections Pan / Drag, Swipe And Fling,
;; Conformance Scenarios.
(ns dao.gui.event.pan-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.gui.event :as event]
            [dao.gui.event.util :as u]))


(def node-id :dao.gui.event.util/save)


(def space-input
  (u/coordinate-space-change {:old-coordinate-space-id nil,
                              :coordinate-space-id u/space-id,
                              :width 390.0,
                              :height 844.0}))


(defn- pan-decl
  [& {:keys [axis start-at contacts contact-loss coexistence priority kind]}]
  {:recognizer/id [node-id :pan],
   :gesture/kind (or kind :pan),
   :machine :dao.gui.event/pan,
   :config {:contacts (or contacts {:min 1, :max 1}),
            :axis (or axis :free),
            :start-at (or start-at :slop),
            :contact-loss (or contact-loss :end)},
   :arena {:priority (or priority 0),
           :mode (if coexistence :cooperative :exclusive),
           :coexistence/group coexistence}})


(defn- swipe-decl
  [& {:keys [direction coexistence]}]
  {:recognizer/id [node-id :swipe],
   :gesture/kind :swipe,
   :machine :dao.gui.event/swipe,
   :config {:contacts {:min 1, :max 1}, :direction (or direction :any)},
   :arena {:priority 0,
           :mode (if coexistence :cooperative :exclusive),
           :coexistence/group coexistence}})


(defn- fling-decl
  [& {:keys [coexistence]}]
  {:recognizer/id [node-id :fling],
   :gesture/kind :fling,
   :machine :dao.gui.event/fling,
   :config {:contacts {:min 1, :max 1}},
   :arena {:priority 0,
           :mode (if coexistence :cooperative :exclusive),
           :coexistence/group coexistence}})


(defn- boot
  [& {:keys [recognizers subscription path]}]
  (let [geometry (u/presented-geometry {:node-id node-id,
                                        :recognizers (or recognizers
                                                         [(pan-decl)])})
        geometry (if path
                   (assoc geometry
                          :nodes [{:node-id node-id,
                                   :interaction/path path,
                                   :touch-action :none,
                                   :regions
                                   [{:bounds
                                     {:x 24, :y 16, :width 200, :height 200},
                                     :paint-order 100}]}])
                   geometry)
        s1 (:state (event/step (event/initial-state)
                               (u/rt 0 u/t0 :terminal space-input)))
        s2 (:state (event/step s1 (u/rt 1 u/t0 :geometry geometry)))
        s3 (:state (event/step s2 (u/rt 2 u/t0 :profile (u/input-profile {}))))]
    (if subscription
      (:state (event/step s3 (u/rt 3 u/t0 :subscription subscription)))
      s3)))


(defn- ptr
  [seq time-us phase id x y]
  (u/rt
    seq
    time-us
    :pointer
    (u/pointer-packet
      {:phase phase, :id id, :x x, :y y, :time-us time-us, :input-seq seq})))


(defn- run
  [state inputs]
  (loop [state state
         inputs inputs
         outputs []]
    (if (empty? inputs)
      {:state state, :outputs outputs}
      (let [{next-state :state, out :outputs} (event/step state (first inputs))]
        (recur next-state (rest inputs) (into outputs out))))))


(defn- gestures
  [outputs]
  (filter #(= :gesture (:event/kind %)) outputs))


(defn- phases
  [outputs]
  (mapv :phase (gestures outputs)))


(defn- payloads
  [outputs]
  (mapv :payload (gestures outputs)))


;; ---------------------------------------------------------------------------
;; Pan
;; ---------------------------------------------------------------------------

(deftest pan-accepts-on-slop-and-ends-on-up
  (let [state (boot :recognizers [(pan-decl)]
                    :subscription (u/sub-add "p" node-id :pan))
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 60.0 25.0)
                     (ptr 12 (+ u/t0 20000) :move 11 75.0 25.0)
                     (ptr 13 (+ u/t0 30000) :up 11 75.0 25.0)])]
    (is (= [:start :update :end] (phases (:outputs result))))
    (let [[start update end] (payloads (:outputs result))]
      ;; start-at :slop, the acceptance point anchors translation
      (is (= {:translation {:x 0.0, :y 0.0}, :delta {:x 0.0, :y 0.0}}
             (dissoc start :velocity)))
      (is (= {:x 15.0, :y 0.0} (:translation update)))
      (is (= {:x 15.0, :y 0.0} (:translation end))))
    (is (= {:active-arena-ids [], :active-pointer-ids []}
           (select-keys (event/fixture-projection (:state result))
                        [:active-arena-ids :active-pointer-ids])))))


(deftest pan-below-slop-never-accepts
  (let [state (boot :recognizers [(pan-decl)])
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 50.0 25.0)
                     (ptr 12 (+ u/t0 20000) :up 11 50.0 25.0)])]
    (is (= [] (phases (:outputs result))))))


(deftest y-axis-pan-qualification-and-orthogonal-zeroing
  (let [state (boot :recognizers [(pan-decl :axis :y)])
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     ;; diagonal move: y exceeds slop, x does not
                     (ptr 11 (+ u/t0 10000) :move 11 55.0 45.0)
                     (ptr 12 (+ u/t0 20000) :move 11 60.0 60.0)
                     (ptr 13 (+ u/t0 30000) :up 11 60.0 60.0)])
        updates (filter #(= :update (:phase %)) (gestures (:outputs result)))]
    (is (seq updates))
    ;; once accepted the axis pan zeros the orthogonal translation
    (doseq [g updates]
      (is (zero? (get-in g [:payload :translation :x])))
      (is (zero? (get-in g [:payload :delta :x]))))))


(deftest x-axis-pan-rejects-pure-y-movement-before-acceptance
  ;; axis qualification: orthogonal component must not exceed slop first
  (let [state (boot :recognizers [(pan-decl :axis :x)])
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 45.0 60.0)
                     (ptr 12 (+ u/t0 20000) :up 11 45.0 60.0)])]
    (is (= [] (phases (:outputs result)))))
  (testing "x movement exceeding slop with y below slop accepts"
    (let [state (boot :recognizers [(pan-decl :axis :x)])
          result (run state
                      [(ptr 10 u/t0 :down 11 40.0 20.0)
                       (ptr 11 (+ u/t0 10000) :move 11 60.0 25.0)
                       (ptr 12 (+ u/t0 20000) :up 11 60.0 25.0)])]
      (is (= [:start :end] (phases (:outputs result)))))))


(deftest pan-start-at-down-includes-pre-acceptance-displacement
  (let [state (boot :recognizers [(pan-decl :start-at :down)])
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 60.0 25.0)
                     (ptr 12 (+ u/t0 20000) :up 11 60.0 25.0)])
        [start] (payloads (:outputs result))]
    (is (= {:x 20.0, :y 5.0} (:translation start)))))


(deftest pan-cancel-emits-cancel-with-last-payload
  (let [state (boot :recognizers [(pan-decl)])
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 60.0 25.0)
                     (ptr 12 (+ u/t0 20000) :cancel 11 60.0 25.0)])
        [start-event cancel-event] (gestures (:outputs result))]
    (is (= :start (:phase start-event)))
    (is (= [:start :cancel] (phases (:outputs result))))
    (is (= :pointer-cancel (:reason (:payload cancel-event))))))


(deftest pan-captured-movement-crosses-another-region-without-retarget
  ;; the pan continues even after the pointer crosses a sibling region;
  ;; captured movement never re-hit-tests
  (let [sibling {:message/kind :dao.terminal/presented-geometry,
                 :generation-id u/generation,
                 :frame-id 43,
                 :coordinate-space-id u/space-id,
                 :nodes [{:node-id :dao.gui.event.pan-test/other,
                          :interaction/path
                          [{:node-id :dao.gui.event.pan-test/other,
                            :recognizers [(u/tap-decl
                                            :dao.gui.event.pan-test/other)],
                            :touch-action :none}],
                          :touch-action :none,
                          :regions [{:bounds
                                     {:x 150, :y 16, :width 60, :height 60},
                                     :paint-order 200}]}]}
        state (boot :recognizers [(pan-decl)])
        crossed (run state
                     [(ptr 10 u/t0 :down 11 40.0 20.0)
                      (ptr 11 (+ u/t0 10000) :move 11 60.0 25.0)
                      (u/rt 12 (+ u/t0 20000) :geometry sibling)])
        result (run (:state crossed)
                    [(u/rt 13 (+ u/t0 30000)
                           :pointer (u/pointer-packet {:phase :move,
                                                       :id 11,
                                                       :x 170.0,
                                                       :y 40.0,
                                                       :time-us (+ u/t0 30000),
                                                       :input-seq 12}))
                     (u/rt 14 (+ u/t0 40000)
                           :pointer (u/pointer-packet {:phase :up,
                                                       :id 11,
                                                       :x 170.0,
                                                       :y 40.0,
                                                       :time-us (+ u/t0 40000),
                                                       :input-seq 13,
                                                       :buttons 0}))])]
    ;; still the original pan target, one continuous gesture
    (is (= [:update :end] (phases (:outputs result))))
    (is (every? #(= node-id (:node-id %)) (gestures (:outputs result))))))


;; ---------------------------------------------------------------------------
;; Child tap versus ancestor scroll pan
;; ---------------------------------------------------------------------------

(deftest ancestor-pan-beats-child-tap-on-slop
  (let [scroll :dao.gui.event.pan-test/scroll
        button :dao.gui.event.pan-test/button
        path [{:node-id scroll,
               :recognizers [{:recognizer/id [scroll :pan],
                              :gesture/kind :pan,
                              :machine :dao.gui.event/pan,
                              :config {:contacts {:min 1, :max 1},
                                       :axis :y,
                                       :start-at :slop,
                                       :contact-loss :end},
                              :arena {:priority 0,
                                      :mode :exclusive,
                                      :coexistence/group nil}}],
               :touch-action :none}
              {:node-id button,
               :recognizers [(u/tap-decl button)],
               :touch-action :manipulation}]
        state (boot :path path :subscription (u/sub-add "p" scroll :pan))
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     ;; vertical movement past the pan slop
                     (ptr 11 (+ u/t0 10000) :move 11 42.0 60.0)
                     (ptr 12 (+ u/t0 20000) :up 11 42.0 60.0)])]
    (is (= [:start :end] (phases (:outputs result))))
    (is (every? #(= scroll (:node-id %)) (gestures (:outputs result))))
    ;; the tap was rejected by arena resolution, no tap gesture emitted
    (is (= []
           (filter #(= :tap (:gesture/kind %)) (gestures (:outputs result)))))))


(deftest child-tap-wins-when-pan-never-accepts
  (let [scroll :dao.gui.event.pan-test/scroll
        button :dao.gui.event.pan-test/button
        path [{:node-id scroll,
               :recognizers [{:recognizer/id [scroll :pan],
                              :gesture/kind :pan,
                              :machine :dao.gui.event/pan,
                              :config {:contacts {:min 1, :max 1},
                                       :axis :y,
                                       :start-at :slop,
                                       :contact-loss :end},
                              :arena {:priority 0,
                                      :mode :exclusive,
                                      :coexistence/group nil}}],
               :touch-action :none}
              {:node-id button,
               :recognizers [(u/tap-decl button)],
               :touch-action :manipulation}]
        state (boot :path path)
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     ;; small movement inside both slops
                     (ptr 11 (+ u/t0 10000) :move 11 41.0 22.0)
                     (ptr 12 (+ u/t0 20000) :up 11 41.0 22.0)])]
    (is (= [:recognized] (phases (:outputs result))))
    (is (= button (:node-id (first (gestures (:outputs result))))))))


;; ---------------------------------------------------------------------------
;; Swipe and fling
;; ---------------------------------------------------------------------------

(deftest swipe-recognizes-on-qualified-up
  (let [state (boot :recognizers [(swipe-decl :direction :right)])
        ;; 100 logical px in 40ms = 2500 px/s
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 20000) :move 11 70.0 20.0)
                     (ptr 12 (+ u/t0 40000) :up 11 140.0 20.0)])
        [gesture] (gestures (:outputs result))]
    (is (some? gesture))
    (is (= :right (:direction (:payload gesture))))
    (is (= 100.0 (:distance (:payload gesture))))
    (is (= 40000 (:duration-us (:payload gesture))))
    (is (= {:x 100.0, :y 0.0} (:displacement (:payload gesture))))
    (is (pos? (:x (:velocity (:payload gesture)))))))


(deftest swipe-wrong-direction-rejects
  (let [state (boot :recognizers [(swipe-decl :direction :right)])
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 20000) :move 11 20.0 20.0)
                     (ptr 12 (+ u/t0 40000) :up 11 -60.0 20.0)])]
    (is (= [] (phases (:outputs result))))))


(deftest fling-recognizes-on-velocity-alone
  (let [state (boot :recognizers [(fling-decl)])
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 20000) :move 11 60.0 20.0)
                     (ptr 12 (+ u/t0 40000) :up 11 100.0 20.0)])
        [gesture] (gestures (:outputs result))]
    (is (some? gesture))
    (is (= :right (:direction (:payload gesture))))
    (is (pos? (:speed (:payload gesture))))
    (is (pos? (:x (:velocity (:payload gesture)))))))


(deftest fling-below-min-velocity-rejects
  (let [state (boot :recognizers [(fling-decl)])
        ;; a slow drift: 2px over 100ms = 20 px/s, below the 50 default
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 50000) :move 11 41.0 20.0)
                     (ptr 12 (+ u/t0 100000) :up 11 42.0 20.0)])]
    (is (= [] (phases (:outputs result))))))


(deftest cooperative-pan-and-fling-coexist
  (let [state (boot :recognizers
                    [(pan-decl :coexistence :scroll-group)
                     (fling-decl :coexistence :scroll-group)])
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 20000) :move 11 70.0 20.0)
                     (ptr 12 (+ u/t0 40000) :up 11 140.0 20.0)])
        events (gestures (:outputs result))]
    ;; pan end (and its dispatches) precedes the fling recognized
    (is (= [:pan :pan :fling] (mapv :gesture/kind events)))
    (let [phases-by-kind
          (into {} (map (fn [g] [(:gesture/kind g) (:phase g)]) events))]
      (is (= :end (:pan phases-by-kind)))
      (is (= :recognized (:fling phases-by-kind))))
    ;; the pan :end event and its dispatches come before the fling event
    (let [kinds (mapv (fn [o]
                        (cond (:gesture/kind o) [:gesture (:gesture/kind o)]
                              (:dispatch/kind o) [:dispatch (:event-kind o)]
                              :else [:other o]))
                      (:outputs result))]
      (is (< (first (keep-indexed (fn [i [t k]]
                                    (when (and (= t :gesture) (= k :pan)) i))
                                  kinds))
             (first (keep-indexed (fn [i [t k]]
                                    (when (and (= t :gesture) (= k :fling)) i))
                                  kinds)))))))


;; ---------------------------------------------------------------------------
;; Cooperative group arbitration
;; ---------------------------------------------------------------------------

(defn- swipe-fling-inputs
  "Down, one qualifying move, and a fast terminal up: both a swipe and a
  fling candidate accept on the up tuple."
  []
  [(ptr 10 u/t0 :down 11 40.0 20.0) (ptr 11 (+ u/t0 20000) :move 11 70.0 20.0)
   (ptr 12 (+ u/t0 40000) :up 11 140.0 20.0)])


(deftest different-coexistence-groups-compete-deterministically
  (let [state (boot :recognizers
                    [(swipe-decl :coexistence ::group-a)
                     (fling-decl :coexistence ::group-b)])
        result (run state (swipe-fling-inputs))
        events (gestures (:outputs result))]
    ;; both accept on the same up, but different groups never coexist: the
    ;; higher-ranked swipe (earlier declaration) wins alone
    (is (= [:swipe] (mapv :gesture/kind events)))
    (is
      (= [[:rejected :arena-lost] [:accepted :arena-winner]]
         (mapv (juxt :decision :cause)
               (filter #(= :dao.gui.event/arena-decision (:event/kind %))
                       (:outputs result))))
      "the losing fling is rejected before the winner commits: it never
         accepted, so it emits no cancel gesture")))


(deftest rank-not-mode-decides-between-exclusive-and-cooperative
  (testing "a higher-ranked exclusive accepter defeats cooperative accepters"
    (let [;; swipe is exclusive with the greater priority
          state (boot :recognizers
                      [(fling-decl :coexistence ::group-b)
                       (assoc-in (swipe-decl) [:arena :priority] 1)])
          result (run state (swipe-fling-inputs))
          events (gestures (:outputs result))]
      (is (= [:swipe] (mapv :gesture/kind events)))))
  (testing "a higher-ranked cooperative accepter defeats an exclusive one"
    (let [state (boot :recognizers
                      [(fling-decl :coexistence ::group-b) (swipe-decl)])
          result (run state (swipe-fling-inputs))
          events (gestures (:outputs result))]
      ;; equal priority: the earlier declaration ranks first
      (is (= [:fling] (mapv :gesture/kind events))))))


(deftest shared-coexistence-group-accepts-together-in-rank-order
  (let [state (boot :recognizers
                    [(swipe-decl :coexistence ::group-a)
                     (fling-decl :coexistence ::group-a)])
        result (run state (swipe-fling-inputs))
        events (gestures (:outputs result))]
    ;; one shared non-nil group: both discrete winners are emitted in
    ;; candidate rank order
    (is (= [:swipe :fling] (mapv :gesture/kind events)))
    ;; semantic events keep complete order: the swipe event and any
    ;; dispatch fan-out precede the fling event and its fan-out
    (let [semantic (mapv (fn [o]
                           (cond (:gesture/kind o) [:gesture (:gesture/kind o)]
                                 (:dispatch/kind o) [:dispatch (:event-kind o)]
                                 :else nil))
                         (:outputs result))]
      (is (= [[:gesture :swipe] [:gesture :fling]]
             (vec (remove nil? semantic)))))))
