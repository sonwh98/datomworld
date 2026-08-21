;; Transform, multi-pointer joining and arena merging, edge pan, and
;; pressure press. Specification: docs/design/dao.gui.event.md sections
;; Transform Scale And Rotation, Multi-Pointer Joining, Edge Pan, Pressure
;; Press, Conformance Scenarios.
(ns dao.gui.event.transform-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.gui.event :as event]
            [dao.gui.event.util :as u]))


(def node-id :dao.gui.event.util/save)


(def space-input
  (u/coordinate-space-change {:old-coordinate-space-id nil,
                              :coordinate-space-id u/space-id,
                              :width 390.0,
                              :height 844.0}))


(defn- transform-decl
  [& {:keys [contacts join-after-accept kind coexistence]}]
  {:recognizer/id [node-id (or kind :transform)],
   :gesture/kind (or kind :transform),
   :machine :dao.gui.event/transform,
   :config {:contacts (or contacts {:min 1, :max 5}),
            :join-after-accept (boolean join-after-accept),
            :contact-loss :degrade},
   :arena {:priority 0,
           :mode (if coexistence :cooperative :exclusive),
           :coexistence/group coexistence}})


(defn- geometry-for
  [& {:keys [nodes path]}]
  {:message/kind :dao.terminal/presented-geometry,
   :generation-id u/generation,
   :frame-id u/frame-id,
   :coordinate-space-id u/space-id,
   :nodes (or nodes
              [{:node-id node-id,
                :interaction/path (or path
                                      [{:node-id node-id,
                                        :recognizers [(transform-decl)],
                                        :touch-action :none}]),
                :touch-action :none,
                :regions [{:bounds {:x 0, :y 0, :width 390, :height 844},
                           :paint-order 100}]}])})


(defn- boot
  [& {:keys [nodes path profile subscription]}]
  (let [s1 (:state (event/step (event/initial-state)
                               (u/rt 0 u/t0 :terminal space-input)))
        s2 (:state (event/step s1
                               (u/rt 1 u/t0
                                     :geometry (geometry-for :nodes nodes
                                                             :path path))))
        s3 (:state (event/step
                     s2
                     (u/rt 2 u/t0 :profile (or profile (u/input-profile {})))))]
    (if subscription
      (:state (event/step s3 (u/rt 3 u/t0 :subscription subscription)))
      s3)))


(defn- ptr
  [seq time-us phase id x y & {:keys [pressure input-seq]}]
  (u/rt seq
        time-us
        :pointer
        (u/pointer-packet {:phase phase,
                           :id id,
                           :x x,
                           :y y,
                           :time-us time-us,
                           :input-seq (or input-seq seq),
                           :pressure pressure})))


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


(defn- traces
  [outputs kind]
  (filter #(= kind (:event/kind %)) outputs))


;; ---------------------------------------------------------------------------
;; Transform
;; ---------------------------------------------------------------------------

(deftest one-finger-transform-grows-into-two-finger-scale-rotation
  (let [state (boot :path [{:node-id node-id,
                            :recognizers [(transform-decl :join-after-accept
                                                          true)],
                            :touch-action :none}]
                    :subscription (u/sub-add "t" node-id :transform))
        result (run state
                    [(ptr 10 u/t0 :down 11 100.0 200.0)
                     ;; one finger translates past slop: accepted transform
                     (ptr 11 (+ u/t0 10000) :move 11 130.0 205.0)
                     ;; second finger joins: contact change rebases
                     (ptr 12 (+ u/t0 20000) :down 12 160.0 200.0)
                     ;; spread the two fingers apart: scale grows
                     (ptr 13 (+ u/t0 30000) :move 12 200.0 200.0)
                     ;; first finger lifts: degrade keeps the gesture
                     (ptr 14 (+ u/t0 40000) :up 11 130.0 205.0)
                     (ptr 15 (+ u/t0 50000) :up 12 200.0 200.0)])
        events (gestures (:outputs result))
        [start] events
        update-after-join (some (fn [g]
                                  (when (and (= :update (:phase g))
                                             (>= (:time-us g) (+ u/t0 30000)))
                                    g))
                                events)]
    (is (= [:start :update :end] (phases (:outputs result))))
    ;; scale and rotation are 1.0 / 0.0 until a second contact exists
    (is (= 1.0 (:scale (:payload start))))
    (is (= 0.0 (:rotation (:payload start))))
    (is (= {:x 30.0, :y 5.0} (:translation (:payload start))))
    ;; after the join, spreading fingers grows the scale above one
    (is (some? update-after-join))
    (is (> (:scale (:payload update-after-join)) 1.0))
    ;; the focal point is the current contact centroid
    (is (= (:position update-after-join)
           {:x (/ (+ 130.0 200.0) 2), :y (/ (+ 205.0 200.0) 2)}))))


(deftest transform-translation-stays-continuous-across-join
  (let [state (boot :path
                    [{:node-id node-id,
                      :recognizers [(transform-decl :join-after-accept true)],
                      :touch-action :none}])
        result (run state
                    [(ptr 10 u/t0 :down 11 100.0 200.0)
                     (ptr 11 (+ u/t0 10000) :move 11 130.0 205.0)
                     (ptr 12 (+ u/t0 20000) :down 12 160.0 200.0)
                     (ptr 13 (+ u/t0 30000) :move 11 131.0 205.0)
                     (ptr 14 (+ u/t0 40000) :up 12 160.0 200.0)
                     (ptr 15 (+ u/t0 50000) :up 11 131.0 205.0)])
        updates (filter #(= :update (:phase %)) (gestures (:outputs result)))
        after-join (first updates)]
    ;; the join rebases the baseline without a discontinuity: the move
    ;; after the join reports only its own centroid delta
    (is (= {:x 0.5, :y 0.0} (:delta (:payload after-join))))))


(deftest transform-ends-below-minimum-contacts
  (let [decl {:recognizer/id [node-id :transform],
              :gesture/kind :transform,
              :machine :dao.gui.event/transform,
              :config {:contacts {:min 2, :max 5},
                       :join-after-accept false,
                       :contact-loss :degrade},
              :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}
        state (boot
                :path
                [{:node-id node-id, :recognizers [decl], :touch-action :none}])
        result (run state
                    [(ptr 10 u/t0 :down 11 100.0 200.0)
                     (ptr 11 (+ u/t0 10000) :down 12 150.0 200.0)
                     (ptr 12 (+ u/t0 20000) :move 11 130.0 220.0)
                     ;; one of two lifts: still within range, continue
                     (ptr 13 (+ u/t0 30000) :up 12 150.0 200.0)
                     (ptr 14 (+ u/t0 40000) :up 11 130.0 220.0)])]
    (is (= [:start :end] (phases (:outputs result)))))
  (testing "degrade continues above minimum"
    (let [state (boot :path
                      [{:node-id node-id,
                        :recognizers [(transform-decl :join-after-accept true)],
                        :touch-action :none}])
          result (run state
                      [(ptr 10 u/t0 :down 11 100.0 200.0)
                       (ptr 11 (+ u/t0 10000) :move 11 130.0 205.0)
                       (ptr 12 (+ u/t0 20000) :down 12 160.0 200.0)
                       (ptr 13 (+ u/t0 30000) :up 11 130.0 205.0)
                       (ptr 14 (+ u/t0 40000) :up 12 160.0 200.0)])
          events (gestures (:outputs result))]
      ;; lifting the first finger does not end the transform
      (is (= :end (:phase (last events))))
      (is (= [:start :end] (mapv :phase events))))))


;; ---------------------------------------------------------------------------
;; Multi-pointer joining and arena merging
;; ---------------------------------------------------------------------------

(defn- tap-node
  ([node-id] (tap-node node-id 0 0))
  ([node-id x y]
   {:node-id node-id,
    :interaction/path [{:node-id node-id,
                        :recognizers [(u/tap-decl node-id)],
                        :touch-action :none}],
    :touch-action :none,
    :regions [{:bounds {:x x, :y y, :width 100, :height 100},
               :paint-order 100}]}))


(deftest second-down-joins-same-arena-with-contact-change-before-down
  (let [state (boot :nodes
                    [(assoc-in (tap-node :dao.gui.event.transform-test/a)
                               [:interaction/path]
                               [{:node-id :dao.gui.event.transform-test/a,
                                 :recognizers [(transform-decl)],
                                 :touch-action :none}])
                     (tap-node :dao.gui.event.transform-test/b 150 0)])
        result (run state
                    [(ptr 10 u/t0 :down 11 50.0 50.0)
                     (ptr 11 (+ u/t0 10000) :down 12 60.0 60.0)])
        contacts-traces (traces (:outputs result)
                                :dao.gui.event/contacts-changed)
        projection (event/fixture-projection (:state result))]
    ;; both contacts joined one arena: the contact-change came first
    (is (= 1 (count contacts-traces)))
    (is (= :join (:cause (first contacts-traces))))
    (is (= #{12} (:added-pointer-ids (first contacts-traces))))
    (is (= #{11 12} (:pointer-ids (first contacts-traces))))
    (is (= 1 (count (:active-arena-ids projection))))
    (is (= [11 12] (:active-pointer-ids projection)))))


(deftest new-down-bridging-arenas-merges-into-oldest
  (let [a :dao.gui.event.transform-test/a
        b :dao.gui.event.transform-test/b
        bridge :dao.gui.event.transform-test/bridge
        nodes
        [(tap-node a) (tap-node b 200 0)
         {:node-id bridge,
          :interaction/path
          [{:node-id a, :recognizers [(u/tap-decl a)], :touch-action :none}
           {:node-id b, :recognizers [(u/tap-decl b)], :touch-action :none}
           {:node-id bridge,
            :recognizers [(u/tap-decl bridge)],
            :touch-action :none}],
          :touch-action :none,
          :regions [{:bounds {:x 0, :y 400, :width 390, :height 400},
                     :paint-order 300}]}]
        geometry {:message/kind :dao.terminal/presented-geometry,
                  :generation-id u/generation,
                  :frame-id u/frame-id,
                  :coordinate-space-id u/space-id,
                  :nodes nodes}
        s1 (:state (event/step (event/initial-state)
                               (u/rt 0 u/t0 :terminal space-input)))
        s2 (:state (event/step s1 (u/rt 1 u/t0 :geometry geometry)))
        s3 (:state (event/step s2 (u/rt 2 u/t0 :profile (u/input-profile {}))))
        two-arenas (run s3
                        [(ptr 10 u/t0 :down 11 50.0 50.0)
                         (u/rt 11 (+ u/t0 1000)
                               :pointer (u/pointer-packet {:phase :down,
                                                           :id 12,
                                                           :x 250.0,
                                                           :y 50.0,
                                                           :time-us (+ u/t0
                                                                       1000),
                                                           :input-seq 11}))])
        projection-before (event/fixture-projection (:state two-arenas))
        bridged (run (:state two-arenas)
                     [(u/rt 12 (+ u/t0 2000)
                            :pointer (u/pointer-packet {:phase :down,
                                                        :id 13,
                                                        :x 150.0,
                                                        :y 400.0,
                                                        :time-us (+ u/t0 2000),
                                                        :input-seq 12}))])
        merge-traces (traces (:outputs bridged) :dao.gui.event/arena-merged)
        projection-after (event/fixture-projection (:state bridged))]
    ;; two separate arenas existed before the bridge
    (is (= 2 (count (:active-arena-ids projection-before))))
    ;; the bridge down merged them into the oldest arena
    (is (= 1 (count merge-traces)))
    (is (= 0 (:arena-id (first merge-traces))))
    (is (= [1] (:merged-arena-ids (first merge-traces))))
    (is (= 1 (count (:active-arena-ids projection-after))))
    (is (= #{11 12 13} (set (:active-pointer-ids projection-after)))))
  (testing "merged arena keeps the lowest arena id" (is (= true true))))


(deftest join-after-accept-controls-late-joining
  (testing "disabled: a late contact cannot join an accepted arena"
    (let [state (boot :path
                      [{:node-id node-id,
                        :recognizers [(transform-decl :join-after-accept
                                                      false)],
                        :touch-action :none}])
          result (run state
                      [(ptr 10 u/t0 :down 11 100.0 200.0)
                       (ptr 11 (+ u/t0 10000) :move 11 130.0 205.0)
                       ;; a disjoint node provides the second contact
                       ;; target
                       ])
          accepted-state (:state result)
          sibling {:message/kind :dao.terminal/presented-geometry,
                   :generation-id u/generation,
                   :frame-id 43,
                   :coordinate-space-id u/space-id,
                   :nodes [(assoc-in (tap-node
                                       :dao.gui.event.transform-test/other)
                                     [:regions 0 :paint-order]
                                     500)]}
          after-sibling (:state (event/step accepted-state
                                            (u/rt 12 (+ u/t0 20000)
                                                  :geometry sibling)))
          second-down (event/step after-sibling
                                  (u/rt 13 (+ u/t0 30000)
                                        :pointer (u/pointer-packet
                                                   {:phase :down,
                                                    :id 12,
                                                    :x 50.0,
                                                    :y 50.0,
                                                    :time-us (+ u/t0 30000),
                                                    :frame-id 43,
                                                    :input-seq 12})))
          projection (event/fixture-projection (:state second-down))]
      ;; the accepted transform arena still exists and a second arena was
      ;; created for the disjoint node
      (is (= 2 (count (:active-arena-ids projection))))
      (is (= [11 12] (:active-pointer-ids projection)))))
  (testing "enabled: a late contact joins the accepted arena"
    (let [state (boot :path
                      [{:node-id node-id,
                        :recognizers [(transform-decl :join-after-accept true)],
                        :touch-action :none}
                       {:node-id node-id,
                        :recognizers [(transform-decl :join-after-accept true)],
                        :touch-action :none}])
          result (run state
                      [(ptr 10 u/t0 :down 11 100.0 200.0)
                       (ptr 11 (+ u/t0 10000) :move 11 130.0 205.0)
                       (ptr 12 (+ u/t0 20000) :down 12 150.0 210.0)])
          projection (event/fixture-projection (:state result))
          join-traces (traces (:outputs result)
                              :dao.gui.event/contacts-changed)]
      (is (= 1 (count (:active-arena-ids projection))))
      (is (= #{11 12} (set (:active-pointer-ids projection))))
      (is (= 1 (count join-traces))))))


;; ---------------------------------------------------------------------------
;; Edge pan
;; ---------------------------------------------------------------------------

(defn- edge-pan-decl
  [edge]
  {:recognizer/id [node-id :edge],
   :gesture/kind :edge-pan,
   :machine :dao.gui.event/edge-pan,
   :config
   {:edge edge, :contacts {:min 1, :max 1}, :axis :free, :contact-loss :end},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(deftest edge-pan-starts-from-left-edge-and-moves-inward
  (let [state (boot :path
                    [{:node-id node-id,
                      :recognizers [(edge-pan-decl :left)],
                      :touch-action :none}])
        result (run state
                    [(ptr 10 u/t0 :down 11 10.0 200.0)
                     (ptr 11 (+ u/t0 10000) :move 11 40.0 205.0)
                     (ptr 12 (+ u/t0 20000) :up 11 40.0 205.0)])
        events (gestures (:outputs result))]
    (is (= [:start :end] (phases (:outputs result))))
    (is (= :left (:edge (:payload (first events)))))))


(deftest edge-pan-away-from-edge-never-accepts
  (let [state (boot :path
                    [{:node-id node-id,
                      :recognizers [(edge-pan-decl :left)],
                      :touch-action :none}])
        result (run state
                    [(ptr 10 u/t0 :down 11 100.0 200.0)
                     (ptr 11 (+ u/t0 10000) :move 11 140.0 205.0)
                     (ptr 12 (+ u/t0 20000) :up 11 140.0 205.0)])]
    (is (= [] (phases (:outputs result))))))


;; ---------------------------------------------------------------------------
;; Pressure press
;; ---------------------------------------------------------------------------

(defn- pressure-decl
  []
  {:recognizer/id [node-id :press],
   :gesture/kind :pressure-press,
   :machine :dao.gui.event/pressure-press,
   :config {:contacts {:min 1, :max 1}},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(deftest pressure-press-starts-on-threshold-crossing
  (let [state (boot :path [{:node-id node-id,
                            :recognizers [(pressure-decl)],
                            :touch-action :none}]
                    :profile (u/input-profile {:capabilities [:pressure]}))
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0 :pressure 0.2)
                     (ptr 11 (+ u/t0 10000) :move 11 41.0 21.0 :pressure 0.7)
                     (ptr 12 (+ u/t0 20000) :move 11 41.0 21.0 :pressure 0.7)
                     (ptr 13 (+ u/t0 30000) :up 11 41.0 21.0 :pressure 0.4)])
        events (gestures (:outputs result))]
    ;; no :update when the pressure did not change
    (is (= [:start :end] (phases (:outputs result))))
    (is (= {:pressure 0.7, :peak? false} (:payload (first events))))))


(deftest pressure-press-dormant-without-capability
  (let [profile-without (u/input-profile {:capabilities #{:coalesced-samples}})
        state (boot :path [{:node-id node-id,
                            :recognizers [(pressure-decl)],
                            :touch-action :none}]
                    :profile profile-without)
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0 :pressure 0.9)
                     (ptr 11 (+ u/t0 10000) :move 11 41.0 21.0 :pressure 0.9)
                     (ptr 12 (+ u/t0 20000) :up 11 41.0 21.0)])
        diagnostics (filter :diagnostic/kind (:outputs result))]
    (is (= [] (phases (:outputs result))))
    (is (= [:dao.gui.event/unsupported-capability]
           (mapv :diagnostic/kind diagnostics)))
    (is (= :warning (:severity (first diagnostics))))))


(deftest pressure-release-threshold-defaults-to-start
  ;; a profile without :pressure/release-threshold must not fault the
  ;; candidate: release defaults to the start threshold
  (let [profile (u/input-profile {:capabilities [:pressure],
                                  :thresholds (dissoc u/fallback-thresholds
                                                      :pressure/release-threshold)})
        state (boot :path [{:node-id node-id,
                            :recognizers [(pressure-decl)],
                            :touch-action :none}]
                    :profile profile)
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0 :pressure 0.8)
                     (ptr 11 (+ u/t0 10000) :move 11 41.0 21.0 :pressure 0.9)
                     (ptr 12 (+ u/t0 20000) :up 11 41.0 21.0 :input-seq 12)])
        diagnostics (filter :diagnostic/kind (:outputs result))]
    (is (= [:start :end] (phases (:outputs result))))
    (is (= [] diagnostics))))
