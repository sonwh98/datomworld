;; Authoring aid for the dao.gui.event conformance fixture corpus: the
;; hand-written scenario inputs and expectation derivation. The committed
;; artifacts, not this generator's current output, are the conformance
;; surface: expected values are derived once, reviewed, and committed.
;; Tests never invoke this namespace. File writing is JVM-only and lives
;; in dao.gui.event.fixtures-write. Specification:
;; docs/design/dao.gui.event.md section Executable Fixture Contract.
(ns dao.gui.event.fixtures-gen
  (:require [dao.gui.event :as event]
            [dao.gui.event.trace :as trace]
            [dao.gui.event.util :as u]))


;; ---------------------------------------------------------------------------
;; Input construction helpers
;; ---------------------------------------------------------------------------

(def node ::fixture)


(defn- space
  ([] (space u/space-id))
  ([id]
   (u/coordinate-space-change {:old-coordinate-space-id nil,
                               :coordinate-space-id id,
                               :width 390.0,
                               :height 844.0})))


(defn- tap-decl
  ([id] (tap-decl node id))
  ([_node id]
   {:recognizer/id id,
    :gesture/kind :tap,
    :machine :dao.gui.event/tap,
    :config {:count 1,
             :contacts {:min 1, :max 1},
             :join-after-accept false,
             :contact-loss :end},
    :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}))


(defn- double-tap-decl
  [id]
  {:recognizer/id id,
   :gesture/kind :tap,
   :machine :dao.gui.event/tap,
   :config {:count 2,
            :contacts {:min 1, :max 1},
            :join-after-accept false,
            :contact-loss :end},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(defn- multi-tap-decl
  [id count]
  {:recognizer/id id,
   :gesture/kind :tap,
   :machine :dao.gui.event/tap,
   :config {:count count,
            :contacts {:min 1, :max 1},
            :join-after-accept false,
            :contact-loss :end},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(defn- contacts-tap-decl
  [id n]
  {:recognizer/id id,
   :gesture/kind :tap,
   :machine :dao.gui.event/tap,
   :config {:count 1,
            :contacts {:min n, :max n},
            :join-after-accept false,
            :contact-loss :end},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(defn- pan-decl
  ([id] (pan-decl node id {}))
  ([id opts] (pan-decl node id opts))
  ([_node id
    {:keys [axis contacts contact-loss coexistence kind join-after-accept
            priority]}]
   {:recognizer/id id,
    :gesture/kind (or kind :pan),
    :machine :dao.gui.event/pan,
    :config {:contacts (or contacts {:min 1, :max 1}),
             :axis (or axis :free),
             :start-at :slop,
             :contact-loss (or contact-loss :end),
             :join-after-accept (boolean join-after-accept)},
    :arena {:priority (or priority 0),
            :mode (if coexistence :cooperative :exclusive),
            :coexistence/group coexistence}}))


(defn- fling-decl
  ([id] (fling-decl node id))
  ([_node id]
   {:recognizer/id id,
    :gesture/kind :fling,
    :machine :dao.gui.event/fling,
    :config {:contacts {:min 1, :max 1}},
    :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}))


(defn- swipe-decl
  [id]
  {:recognizer/id id,
   :gesture/kind :swipe,
   :machine :dao.gui.event/swipe,
   :config {:contacts {:min 1, :max 1}, :direction :right},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(defn- long-press-decl
  [id]
  {:recognizer/id id,
   :gesture/kind :long-press,
   :machine :dao.gui.event/long-press,
   :config {:contacts {:min 1, :max 1}},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(defn- transform-decl
  ([id] (transform-decl node id))
  ([_node id]
   {:recognizer/id id,
    :gesture/kind :transform,
    :machine :dao.gui.event/transform,
    :config {:contacts {:min 1, :max 5}, :contact-loss :degrade},
    :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}))


(defn- scale-decl
  [id group]
  {:recognizer/id id,
   :gesture/kind :scale,
   :machine :dao.gui.event/transform,
   :config {:contacts {:min 2, :max 5}, :contact-loss :degrade},
   :arena {:priority 0, :mode :cooperative, :coexistence/group group}})


(defn- rotation-decl
  [id group]
  {:recognizer/id id,
   :gesture/kind :rotation,
   :machine :dao.gui.event/transform,
   :config {:contacts {:min 2, :max 5}, :contact-loss :degrade},
   :arena {:priority 0, :mode :cooperative, :coexistence/group group}})


(defn- edge-pan-decl
  [id]
  {:recognizer/id id,
   :gesture/kind :edge-pan,
   :machine :dao.gui.event/edge-pan,
   :config {:edge :left, :contacts {:min 1, :max 1}},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(defn- pressure-decl
  [id]
  {:recognizer/id id,
   :gesture/kind :pressure-press,
   :machine :dao.gui.event/pressure-press,
   :config {:contacts {:min 1, :max 1}},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(defn- geometry
  [& {:keys [frame-id nodes node-id recognizers x y width height paint-order]}]
  (let [node-id (or node-id node)
        recognizers (or recognizers [(tap-decl [node-id :tap])])
        entry {:node-id node-id,
               :recognizers recognizers,
               :touch-action :manipulation}
        node {:node-id node-id,
              :interaction/path [entry],
              :touch-action :manipulation,
              :regions [{:bounds {:x (or x 24),
                                  :y (or y 16),
                                  :width (or width 200),
                                  :height (or height 200)},
                         :paint-order (or paint-order 100)}]}]
    {:message/kind :dao.terminal/presented-geometry,
     :generation-id u/generation,
     :frame-id (or frame-id u/frame-id),
     :coordinate-space-id u/space-id,
     :nodes (cond nodes nodes
                  :else [node])}))


(defn- path-geometry
  "Geometry for one node with an explicit multi-entry path."
  [& {:keys [frame-id path node-id x y width height]}]
  {:message/kind :dao.terminal/presented-geometry,
   :generation-id u/generation,
   :frame-id (or frame-id u/frame-id),
   :coordinate-space-id u/space-id,
   :nodes [{:node-id (or node-id node),
            :interaction/path path,
            :touch-action :none,
            :regions [{:bounds {:x (or x 0),
                                :y (or y 0),
                                :width (or width 390),
                                :height (or height 844)},
                       :paint-order 100}]}]})


(defn- rt
  ([n t source value] (u/rt n t source value)))


(defn- ptr
  [n t phase id x y & {:keys [frame-id samples pressure]}]
  (u/rt n
        t
        :pointer
        (u/pointer-packet {:phase phase,
                           :id id,
                           :x x,
                           :y y,
                           :time-us t,
                           :input-seq n,
                           :frame-id (or frame-id u/frame-id),
                           :pressure pressure,
                           :samples samples})))


(defn- sub
  ([id kind] (sub id node kind))
  ([id node kind]
   {:subscription/op :add,
    :subscription/id id,
    :subscriber/id ::controller,
    :node-id node,
    :event-kind kind}))


(defn- timer-fires
  "A timer-fired input built from one emitted timer-request output."
  [n t request]
  (u/rt n
        t
        :timer
        {:input/kind :dao.gui.event/timer-fired,
         :generation-id (:generation-id request),
         :coordinate-space-id (:coordinate-space-id request),
         :arena-id (:arena-id request),
         :recognizer/id (:recognizer/id request),
         :timer-id (:timer-id request),
         :timer-seq (:timer-seq request),
         :time-us t}))


(defn- outputs-of
  [inputs]
  (:outputs (event/replay (event/initial-state) inputs)))


(defn- effect-requests
  [outputs op]
  (filter #(= op (:timer/op %)) (filter :effect/kind outputs)))


;; ---------------------------------------------------------------------------
;; Scenario inputs
;; ---------------------------------------------------------------------------

(def ^:private t u/t0)


(defn- boot
  [& {:keys [geometry subs profile profile-id]}]
  (vec (concat
         [(rt 0 t :terminal (space))]
         (when geometry [(rt 1 t :geometry geometry)])
         [(rt 2 t
              :profile (if (or profile profile-id)
                         (u/input-profile (merge {:profile-id (or profile-id
                                                                  u/profile-id)}
                                                 profile))
                         (u/input-profile {})))]
         (map-indexed (fn [i s] (rt (+ 3 i) t :subscription s)) (or subs [])))))


(defn- scenario-tap-single
  []
  (into (boot :geometry (geometry :recognizers [(tap-decl [node :tap])])
              :subs [(sub "save-handler-1" :tap)])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 50000) :up 11 41.0 21.0)]))


(defn- scenario-tap-two-contact
  []
  (into (boot :geometry (geometry :recognizers
                                  [(contacts-tap-decl [node :tap] 2)])
              :subs [(sub "two" :tap)])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 1000) :down 12 60.0 25.0)
         (ptr 12 (+ t 5000) :up 11 41.0 21.0)
         (ptr 13 (+ t 6000) :up 12 61.0 26.0)]))


(defn- scenario-tap-three-contact
  []
  (into
    (boot :geometry (geometry :recognizers [(contacts-tap-decl [node :tap] 3)])
          :subs [(sub "three" :tap)])
    [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 1000) :down 12 60.0 25.0)
     (ptr 12 (+ t 2000) :down 13 80.0 30.0) (ptr 13 (+ t 5000) :up 11 41.0 21.0)
     (ptr 14 (+ t 6000) :up 12 61.0 26.0)
     (ptr 15 (+ t 7000) :up 13 81.0 31.0)]))


(defn- scenario-tap-double
  []
  (into (boot :geometry (geometry :recognizers [(double-tap-decl [node :tap])])
              :subs [(sub "d" :tap)])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 10000) :up 11 40.0 20.0)
         (ptr 12 (+ t 50000) :down 11 41.0 20.5)
         (ptr 13 (+ t 60000) :up 11 41.0 20.5)]))


(defn- scenario-tap-repeated
  []
  (into (boot :geometry (geometry :recognizers [(multi-tap-decl [node :tap] 3)])
              :subs [(sub "r" :tap)])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 10000) :up 11 40.0 20.0)
         (ptr 12 (+ t 50000) :down 11 41.0 20.0)
         (ptr 13 (+ t 60000) :up 11 41.0 20.0)
         (ptr 14 (+ t 110000) :down 11 42.0 20.0)
         (ptr 15 (+ t 120000) :up 11 42.0 20.0)]))


(defn- scenario-tap-deferred-revival
  []
  (let [inputs (into (boot :geometry (geometry :recognizers
                                               [(tap-decl [node :one])
                                                (double-tap-decl [node
                                                                  :tapx2])])
                           :subs [(sub "s" :tap)])
                     [(ptr 10 t :down 11 40.0 20.0)
                      (ptr 11 (+ t 10000) :up 11 40.0 20.0)])
        request (first (effect-requests (outputs-of inputs) :start))]
    (into inputs [(timer-fires 12 (+ t 320000) request)])))


(defn- scenario-longpress-accept
  []
  (let [inputs (into (boot :geometry (geometry :recognizers
                                               [(long-press-decl [node :lp])])
                           :subs [(sub "lp" :long-press)])
                     [(ptr 10 t :down 11 40.0 20.0)])
        request (first (effect-requests (outputs-of inputs) :start))]
    (into inputs
          [(timer-fires 11 (+ t 500000) request)
           (ptr 12 (+ t 600000) :up 11 41.0 21.0)])))


(defn- scenario-longpress-early-up
  []
  (into (boot :geometry (geometry :recognizers [(long-press-decl [node :lp])]))
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 100000) :up 11 41.0 21.0)]))


(defn- scenario-longpress-move-reject
  []
  (into (boot :geometry (geometry :recognizers [(long-press-decl [node :lp])]))
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 100000) :move 11 100.0 80.0)
         (ptr 12 (+ t 110000) :up 11 100.0 80.0)]))


(defn- scenario-longpress-cancel
  []
  (let [inputs (into (boot :geometry
                           (geometry :recognizers
                                     [(long-press-decl [node :lp])]))
                     [(ptr 10 t :down 11 40.0 20.0)])
        request (first (effect-requests (outputs-of inputs) :start))]
    (into inputs
          [(timer-fires 11 (+ t 500000) request)
           (ptr 12 (+ t 600000) :cancel 11 41.0 21.0)])))


;; A stationary long press progresses through the explicit timer tuple
;; alone: no heartbeat packets arrive between down and the timer.
(defn- scenario-longpress-stationary
  []
  (let [inputs (into (boot :geometry (geometry :recognizers
                                               [(long-press-decl [node :lp])])
                           :subs [(sub "lp" :long-press)])
                     [(ptr 10 t :down 11 40.0 20.0)])
        request (first (effect-requests (outputs-of inputs) :start))]
    (into inputs
          [(timer-fires 11 (+ t 500000) request)
           (ptr 12 (+ t 800000) :up 11 40.0 20.0)])))


(defn- scenario-child-tap-ancestor-pan
  []
  (into (boot :geometry (path-geometry
                          :path [{:node-id ::scroll,
                                  :recognizers [(pan-decl ::scroll
                                                          [::scroll :pan]
                                                          {:axis :y})],
                                  :touch-action :none}
                                 {:node-id node,
                                  :recognizers [(tap-decl node [node :tap])],
                                  :touch-action :manipulation}]
                          :node-id node
                          :x 0
                          :y 0
                          :width 300
                          :height 600)
              :subs [(sub "scroll" ::scroll :pan) (sub "tap" node :tap)])
        [(ptr 10 t :down 11 40.0 20.0)
         ;; movement past pan slop on the y axis accepts the ancestor pan
         (ptr 11 (+ t 10000) :move 11 45.0 80.0)
         (ptr 12 (+ t 20000) :up 11 45.0 80.0)]))


(defn- scenario-pan-axis-y
  []
  (into (boot :geometry (geometry :recognizers
                                  [(pan-decl [node :pan] {:axis :y})])
              :subs [(sub "p" :pan)])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 10000) :move 11 42.0 80.0)
         (ptr 12 (+ t 20000) :move 11 44.0 140.0)
         (ptr 13 (+ t 30000) :up 11 44.0 140.0)]))


(defn- scenario-pan-free-leaving-rect
  []
  (into (boot :geometry (geometry :recognizers [(pan-decl [node :pan]
                                                          {:axis :free})]
                                  :width 40
                                  :height 40)
              :subs [(sub "p" :pan)])
        [(ptr 10 t :down 11 30.0 20.0)
         ;; captured movement far outside the original rectangle
         (ptr 11 (+ t 10000) :move 11 300.0 400.0)
         (ptr 12 (+ t 20000) :up 11 300.0 400.0)]))


(defn- scenario-pan-end-with-fling
  []
  (into (boot :geometry (geometry :recognizers
                                  [(pan-decl [node :pan] {:coexistence :scroll})
                                   (fling-decl [node :fling])])
              :subs [])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 20000) :move 11 70.0 20.0)
         (ptr 12 (+ t 40000) :up 11 140.0 20.0)]))


(defn- scenario-pan-end-without-fling
  []
  (into (boot :geometry
              (geometry :recognizers
                        [(pan-decl [node :pan] {:coexistence :scroll})
                         (fling-decl [node :fling])]))
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 20000) :move 11 70.0 20.0)
         ;; a slow terminal up: below fling velocity
         (ptr 12 (+ t 400000) :up 11 80.0 20.0)]))


;; Distance and duration exactly at their inclusive limits: 48 pixels in
;; 500000 microseconds, with terminal velocity above the minimum.
(defn- scenario-swipe-boundaries
  []
  (into (boot :geometry (geometry :recognizers [(swipe-decl [node :swipe])])
              :subs [(sub "sw" :swipe)])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 496000) :move 11 64.0 20.0)
         ;; distance exactly 48, duration exactly 500000
         (ptr 12 (+ t 500000) :up 11 88.0 20.0)]))


(defn- scenario-transform-grow
  []
  (into
    (boot :geometry (geometry :recognizers [(transform-decl [node :transform])])
          :subs [(sub "tr" :transform)])
    [(ptr 10 t :down 11 100.0 200.0) (ptr 11 (+ t 10000) :move 11 130.0 205.0)
     (ptr 12 (+ t 20000) :down 12 160.0 200.0)
     (ptr 13 (+ t 30000) :move 12 200.0 200.0)
     (ptr 14 (+ t 40000) :up 11 130.0 205.0)
     (ptr 15 (+ t 50000) :up 12 200.0 200.0)]))


(defn- scenario-cooperative-scale-rotation
  []
  (into
    (boot :geometry (geometry :recognizers
                              [(scale-decl [node :scale] ::pinch-rotate)
                               (rotation-decl [node :rotation] ::pinch-rotate)])
          :subs [(sub "sc" :scale) (sub "ro" :rotation)])
    [(ptr 10 t :down 11 100.0 200.0) (ptr 11 (+ t 10000) :down 12 160.0 200.0)
     (ptr 12 (+ t 20000) :move 12 200.0 240.0)
     (ptr 13 (+ t 30000) :move 12 250.0 290.0)
     (ptr 14 (+ t 40000) :up 11 100.0 200.0)
     (ptr 15 (+ t 50000) :up 12 250.0 290.0)]))


(defn- scenario-join-contacts-changed-order
  []
  (into (boot :geometry (geometry :recognizers
                                  [(pan-decl [node :pan]
                                             {:contacts {:min 1, :max 5},
                                              :join-after-accept true})])
              :subs [(sub "p" :pan)])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 10000) :move 11 80.0 20.0)
         ;; a second contact joins the accepted pan arena
         (ptr 12 (+ t 20000) :down 12 100.0 60.0)
         (ptr 13 (+ t 30000) :move 11 90.0 20.0)
         (ptr 14 (+ t 40000) :up 11 90.0 20.0)
         (ptr 15 (+ t 50000) :up 12 100.0 60.0)]))


;; A bridging down whose path intersects two unresolved arenas merges them
;; into the oldest arena and adds its own remaining path candidates.
(defn- scenario-bridge-merge
  []
  (let [x-entry (fn []
                  {:node-id ::x,
                   :recognizers
                   [(pan-decl ::x [::x :pan] {:contacts {:min 1, :max 3}})],
                   :touch-action :none})
        y-entry (fn []
                  {:node-id ::y,
                   :recognizers
                   [(pan-decl ::y [::y :pan] {:contacts {:min 1, :max 3}})],
                   :touch-action :none})]
    (into (boot :geometry
                (geometry
                  :nodes
                  [{:node-id ::x,
                    :interaction/path [(x-entry)],
                    :touch-action :none,
                    :regions [{:bounds {:x 0, :y 0, :width 100, :height 100},
                               :paint-order 10}]}
                   {:node-id ::y,
                    :interaction/path [(y-entry)],
                    :touch-action :none,
                    :regions [{:bounds {:x 200, :y 0, :width 100, :height 100},
                               :paint-order 11}]}
                   {:node-id ::bridge,
                    :interaction/path [(x-entry) (y-entry)
                                       {:node-id ::bridge,
                                        :recognizers
                                        [(tap-decl ::bridge [::bridge :tap])],
                                        :touch-action :none}],
                    :touch-action :none,
                    :regions [{:bounds {:x 0, :y 200, :width 390, :height 200},
                               :paint-order 12}]}]))
          [(ptr 10 t :down 11 50.0 50.0)
           (ptr 11 (+ t 10000) :down 12 250.0 50.0)
           ;; the bridging down merges both unresolved arenas
           (ptr 12 (+ t 20000) :down 13 60.0 250.0)
           (ptr 13 (+ t 30000) :up 11 50.0 50.0)
           (ptr 14 (+ t 40000) :up 12 250.0 50.0)
           (ptr 15 (+ t 50000) :up 13 60.0 250.0)])))


(defn- scenario-late-join-after-accept
  []
  (into
    (boot :geometry (geometry :recognizers [(transform-decl [node :transform])])
          :subs [(sub "tr" :transform)])
    [(ptr 10 t :down 11 100.0 200.0) (ptr 11 (+ t 10000) :move 11 130.0 205.0)
     ;; the accepted transform admits the joining contact
     (ptr 12 (+ t 20000) :down 12 160.0 200.0)
     (ptr 13 (+ t 30000) :up 11 130.0 205.0)
     (ptr 14 (+ t 40000) :up 12 160.0 200.0)]))


;; A one-contact pan with join-after-accept false: the second down creates
;; an independent arena instead.
(defn- scenario-late-join-without-accept
  []
  (into (boot :geometry
              (geometry :recognizers
                        [(pan-decl [node :pan] {:contacts {:min 1, :max 2}})]))
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 10000) :move 11 80.0 20.0)
         (ptr 12 (+ t 20000) :down 12 100.0 60.0)
         (ptr 13 (+ t 30000) :up 11 80.0 20.0)
         (ptr 14 (+ t 40000) :up 12 100.0 60.0)]))


(defn- scenario-path-disjoint-concurrent
  []
  (into (boot
          :geometry
          (geometry
            :nodes
            [{:node-id ::a,
              :interaction/path [{:node-id ::a,
                                  :recognizers [(tap-decl ::a [::a :tap])],
                                  :touch-action :none}],
              :touch-action :none,
              :regions [{:bounds {:x 0, :y 0, :width 100, :height 100},
                         :paint-order 10}]}
             {:node-id ::b,
              :interaction/path [{:node-id ::b,
                                  :recognizers [(tap-decl ::b [::b :tap])],
                                  :touch-action :none}],
              :touch-action :none,
              :regions [{:bounds {:x 200, :y 0, :width 100, :height 100},
                         :paint-order 11}]}])
          :subs [(sub "a" ::a :tap) (sub "b" ::b :tap)])
        [(ptr 10 t :down 11 50.0 50.0) (ptr 11 (+ t 1000) :down 12 250.0 50.0)
         (ptr 12 (+ t 2000) :up 11 51.0 51.0)
         (ptr 13 (+ t 3000) :up 12 251.0 51.0)]))


(defn- scenario-lift-end
  []
  (into (boot :geometry (geometry :recognizers
                                  [(pan-decl [node :pan] {:contact-loss :end})])
              :subs [(sub "p" :pan)])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 10000) :move 11 80.0 20.0)
         (ptr 12 (+ t 20000) :up 11 80.0 20.0)]))


(defn- scenario-lift-hold
  []
  (into (boot :geometry (geometry :recognizers
                                  [(pan-decl [node :pan]
                                             {:contacts {:min 2, :max 2},
                                              :contact-loss :hold})])
              :subs [(sub "p" :pan)])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 1000) :down 12 60.0 20.0)
         (ptr 12 (+ t 10000) :move 11 40.0 80.0)
         ;; one contact lifts: updates pause below the minimum
         (ptr 13 (+ t 20000) :up 11 40.0 80.0)
         ;; a move while paused emits no update
         (ptr 14 (+ t 30000) :move 12 61.0 20.0)
         ;; the held gesture is finally cancelled by a terminal reset
         (rt 15 (+ t 40000)
             :terminal {:message/kind :dao.terminal/reset,
                        :generation-id "fresh-generation"})]))


(defn- scenario-lift-degrade
  []
  (into
    (boot :geometry (geometry :recognizers [(transform-decl [node :transform])])
          :subs [(sub "tr" :transform)])
    [(ptr 10 t :down 11 100.0 200.0) (ptr 11 (+ t 1000) :down 12 160.0 200.0)
     (ptr 12 (+ t 10000) :move 11 140.0 240.0)
     ;; degrade keeps the gesture with one remaining contact
     (ptr 13 (+ t 20000) :up 12 160.0 200.0)
     (ptr 14 (+ t 30000) :move 11 150.0 250.0)
     (ptr 15 (+ t 40000) :up 11 150.0 250.0)]))


(defn- scenario-contact-overflow
  []
  (into
    (boot :geometry (geometry :recognizers [(contacts-tap-decl [node :tap] 1)]))
    [(ptr 10 t :down 11 40.0 20.0)
     ;; a down beyond the contact maximum rejects the tap
     (ptr 11 (+ t 1000) :down 12 60.0 25.0) (ptr 12 (+ t 2000) :up 11 40.0 20.0)
     (ptr 13 (+ t 3000) :up 12 60.0 25.0)]))


;; A down whose path intersects only an accepted arena that does not admit
;; joins creates an independent arena omitting the owned exclusive
;; candidate; the other path candidates remain eligible.
(defn- scenario-owned-candidate-omitted
  []
  (into
    (boot :geometry
          (geometry
            :nodes
            [{:node-id ::held,
              :interaction/path
              [{:node-id ::held,
                :recognizers [(transform-decl ::held [::held :transform])],
                :touch-action :none}],
              :touch-action :none,
              :regions [{:bounds {:x 0, :y 0, :width 390, :height 300},
                         :paint-order 10}]}
             {:node-id ::later,
              :interaction/path
              [{:node-id ::held,
                :recognizers [(transform-decl ::held [::held :transform])],
                :touch-action :none}
               {:node-id ::later,
                :recognizers [(tap-decl ::later [::later :tap])],
                :touch-action :none}],
              :touch-action :none,
              :regions [{:bounds {:x 0, :y 300, :width 390, :height 300},
                         :paint-order 11}]}]))
    [(ptr 10 t :down 11 100.0 100.0)
     ;; the transform accepts on translation slop
     (ptr 11 (+ t 10000) :move 11 140.0 100.0)
     ;; the second down intersects the accepted arena, which does not
     ;; admit joins: an independent arena is created without the owned
     ;; transform candidate but with the later tap
     (ptr 12 (+ t 20000) :down 12 100.0 400.0)
     (ptr 13 (+ t 30000) :up 12 101.0 401.0)
     (ptr 14 (+ t 40000) :up 11 140.0 100.0)]))


(defn- scenario-capture-across-region
  []
  (into (boot :geometry
              (geometry
                :nodes
                [{:node-id ::under,
                  :interaction/path [{:node-id ::under,
                                      :recognizers
                                      [(tap-decl ::under [::under :tap])],
                                      :touch-action :none}],
                  :touch-action :none,
                  :regions [{:bounds
                             {:x 100, :y 100, :width 100, :height 100},
                             :paint-order 10}]}
                 {:node-id ::over,
                  :interaction/path
                  [{:node-id ::over,
                    :recognizers [(pan-decl ::over [::over :pan] {})],
                    :touch-action :none}],
                  :touch-action :none,
                  :regions [{:bounds {:x 0, :y 0, :width 300, :height 300},
                             :paint-order 20}]}]
                :node-id ::over)
              :subs [(sub "pan" ::over :pan)])
        [(ptr 10 t :down 11 150.0 150.0)
         ;; captured movement crosses the under region: no retarget
         (ptr 11 (+ t 10000) :move 11 280.0 150.0)
         (ptr 12 (+ t 20000) :up 11 280.0 150.0)]))


(defn- scenario-edge-pan-delivered
  []
  (into (boot :geometry (geometry :recognizers [(edge-pan-decl [node :edge])]
                                  :x 0
                                  :width 60)
              :subs [(sub "e" :edge-pan)])
        [(ptr 10 t :down 11 10.0 200.0)
         ;; inward displacement past slop accepts the edge pan
         (ptr 11 (+ t 10000) :move 11 80.0 200.0)
         (ptr 12 (+ t 20000) :up 11 80.0 200.0)]))


(defn- scenario-edge-pan-cancelled
  []
  (let [inputs (into (boot :geometry (geometry :recognizers [(edge-pan-decl
                                                               [node :edge])]
                                               :x 0
                                               :width 60)
                           :subs [(sub "e" :edge-pan)])
                     [(ptr 10 t :down 11 10.0 200.0)
                      (ptr 11 (+ t 10000) :move 11 80.0 200.0)])]
    (into inputs [(ptr 12 (+ t 20000) :cancel 11 80.0 200.0)])))


;; A down outside the edge strip never starts the recognizer: an
;; application must not assume every configured edge is interceptable.
(defn- scenario-edge-pan-never-qualifies
  []
  (into
    (boot :geometry
          (geometry :recognizers [(edge-pan-decl [node :edge])] :x 0 :width 60))
    [(ptr 10 t :down 11 200.0 200.0) (ptr 11 (+ t 10000) :move 11 300.0 200.0)
     (ptr 12 (+ t 20000) :up 11 300.0 200.0)]))


(defn- scenario-pressure-with
  []
  (into (boot :geometry (geometry :recognizers [(pressure-decl [node :press])])
              :profile {:capabilities #{:pressure}}
              :subs [(sub "pr" :pressure-press)])
        [(ptr 10 t :down 11 40.0 20.0 :pressure 0.2)
         (ptr 11 (+ t 10000) :move 11 41.0 21.0 :pressure 0.7)
         (ptr 12 (+ t 20000) :move 11 41.0 22.0 :pressure 0.75)
         (ptr 13 (+ t 30000) :up 11 41.0 22.0 :pressure 0.4)]))


(defn- scenario-pressure-without
  []
  (into (boot :geometry (geometry :recognizers [(pressure-decl [node :press])])
              :profile {:capabilities #{}})
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 10000) :move 11 41.0 21.0)
         (ptr 12 (+ t 20000) :up 11 41.0 21.0)]))


(defn- scenario-capture-across-frames
  []
  (let [base (boot :geometry (geometry :recognizers [(pan-decl [node :pan])])
                   :subs [(sub "p" :pan)])]
    (into base
          [(ptr 10 t :down 11 40.0 20.0)
           ;; a new frame presents and moves the target: capture persists
           (rt 11 (+ t 5000) :geometry (geometry :frame-id 43 :x 200 :y 300))
           (ptr 12 (+ t 10000) :move 11 80.0 20.0)
           ;; the target disappears entirely: capture still persists
           (rt 13 (+ t 15000)
               :geometry {:message/kind :dao.terminal/presented-geometry,
                          :generation-id u/generation,
                          :frame-id 44,
                          :coordinate-space-id u/space-id,
                          :nodes []}) (ptr 14 (+ t 20000) :move 11 120.0 20.0)
           (ptr 15 (+ t 30000) :up 11 120.0 20.0)])))


(defn- scenario-frames-rejected-skipped
  []
  (into (boot :geometry (geometry :recognizers [(pan-decl [node :pan])]))
        [(ptr 10 t :down 11 40.0 20.0)
         (rt 11 (+ t 5000)
             :terminal {:message/kind :dao.terminal/rejection,
                        :submission-id 9,
                        :reason :invalid})
         (rt 12 (+ t 6000)
             :terminal {:message/kind :dao.terminal/frame-skipped,
                        :submission-id 10})
         (ptr 13 (+ t 10000) :move 11 80.0 20.0)
         (ptr 14 (+ t 20000) :up 11 80.0 20.0)]))


(defn- scenario-terminal-reset
  []
  (into (boot :geometry (geometry :recognizers [(pan-decl [node :pan])])
              :subs [(sub "p" :pan)])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 10000) :move 11 80.0 20.0)
         (rt 12 (+ t 15000)
             :terminal {:message/kind :dao.terminal/reset,
                        :generation-id "fresh-generation"})
         ;; a new generation boot follows the reset
         (rt 13 (+ t 20000)
             :terminal (u/coordinate-space-change {:generation-id
                                                   "fresh-generation",
                                                   :old-coordinate-space-id nil,
                                                   :coordinate-space-id 9,
                                                   :width 390.0,
                                                   :height 844.0}))]))


(defn- scenario-coordinate-space-mismatch
  []
  (into (boot :geometry (geometry :recognizers [(tap-decl [node :tap])]))
        [(u/rt 10 t
               :pointer (u/pointer-packet {:phase :down,
                                           :id 11,
                                           :x 40.0,
                                           :y 20.0,
                                           :time-us t,
                                           :input-seq 10,
                                           :coordinate-space-id 8}))]))


(defn- scenario-stale-generation
  []
  (into (boot :geometry (geometry :recognizers [(tap-decl [node :tap])]))
        [(rt 10 t
             :geometry (u/presented-geometry {:generation-id "other-generation",
                                              :frame-id 50}))]))


(defn- scenario-profile-mismatch
  []
  (into (boot :geometry (geometry :recognizers [(tap-decl [node :tap])]))
        [(ptr 10 t :down 11 40.0 20.0 :frame-id 9)]))


(defn- scenario-duplicate-pointer-down
  []
  (into (boot :geometry (geometry :recognizers [(tap-decl [node :tap])]))
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 1000) :down 11 50.0 30.0)
         (ptr 12 (+ t 2000) :up 11 50.0 30.0)]))


(defn- scenario-orphan-pointer-packet
  []
  (into (boot :geometry (geometry :recognizers [(tap-decl [node :tap])]))
        [(ptr 10 t :move 11 40.0 20.0) (ptr 11 (+ t 1000) :up 11 40.0 20.0)]))


(defn- scenario-input-sequence-gap
  []
  (into (boot :geometry (geometry :recognizers [(pan-decl [node :pan])]))
        [(ptr 10 t :down 11 40.0 20.0)
         ;; input-seq jumps from 10 to 14: the observed gap is explicit
         (u/rt 11 (+ t 10000)
               :pointer (u/pointer-packet {:phase :move,
                                           :id 11,
                                           :x 80.0,
                                           :y 20.0,
                                           :time-us (+ t 10000),
                                           :input-seq 14}))]))


(defn- scenario-no-active-frame
  []
  (into (boot nil) [(ptr 10 t :down 11 40.0 20.0)]))


(defn- scenario-future-frame
  []
  (into (boot :geometry (geometry :recognizers [(tap-decl [node :tap])]))
        [(ptr 10 t :down 11 40.0 20.0 :frame-id 99)]))


(defn- scenario-stale-frame
  []
  (into (boot :geometry (geometry :recognizers [(tap-decl [node :tap])]))
        [(rt 10 t :geometry (geometry :frame-id 41))]))


(defn- scenario-unsupported-region
  []
  (into (boot :geometry
              (u/presented-geometry {:node-id node,
                                     :recognizers [(tap-decl node [node :tap])],
                                     :frame-id 43}))
        ;; the invalid region rides the same geometry value
        [(u/rt 10 t
               :geometry (update-in
                           (u/presented-geometry
                             {:node-id node,
                              :recognizers [(tap-decl node [node :tap])],
                              :frame-id 43})
                           [:nodes 0 :regions]
                           conj
                           {:bounds {:x 0, :y 0, :width -5, :height 10},
                            :paint-order 101}))]))


;; Local scroll and animation present new frames without minting a new
;; coordinate space: capture and recognition continue.
(defn- scenario-no-remint
  []
  (let [base (boot :geometry (geometry :recognizers [(pan-decl [node :pan])])
                   :subs [(sub "p" :pan)])]
    (into base
          [(ptr 10 t :down 11 40.0 20.0)
           (rt 11 (+ t 5000) :geometry (geometry :frame-id 43 :x 10 :y 10))
           (ptr 12 (+ t 10000) :move 11 80.0 20.0)
           (ptr 13 (+ t 20000) :up 11 80.0 20.0)])))


;; The timer result carries exactly the pointer's timestamp: canonical
;; source order steps the pointer first, so the up rejects the long press
;; before the timer could accept it, and the later timer result is late.
(defn- scenario-timer-deadline-tie
  []
  (let [inputs (into (boot :geometry
                           (geometry :recognizers
                                     [(long-press-decl [node :lp])]))
                     [(ptr 10 t :down 11 40.0 20.0)])
        request (first (effect-requests (outputs-of inputs) :start))]
    (into inputs
          [(ptr 11 (+ t 500000) :up 11 41.0 21.0)
           (timer-fires 12 (+ t 500000) request)])))


(defn- scenario-timer-stale-sequence
  []
  (let [inputs (into (boot :geometry
                           (geometry :recognizers
                                     [(long-press-decl [node :lp])]))
                     [(ptr 10 t :down 11 40.0 20.0)])
        request (first (effect-requests (outputs-of inputs) :start))]
    (into inputs
          [(u/rt 11 (+ t 500000)
                 :timer (assoc
                          (dissoc request :effect/kind :timer/op :deadline-us)
                          :input/kind :dao.gui.event/timer-fired
                          :timer-seq 7
                          :time-us (+ t 500000)))])))


(defn- scenario-late-runtime-input
  []
  (into (boot :geometry (geometry :recognizers [(tap-decl [node :tap])]))
        [(rt 10 (+ t 1000) :subscription (sub "s1" :tap))
         (rt 11 (+ t 500) :subscription (u/sub-remove "s1"))]))


(defn- scenario-predicted-miscorrection
  []
  (into (boot :geometry (geometry :recognizers [(tap-decl [node :tap])])
              :subs [(sub "s" :tap)])
        [(ptr 10 t :down 11 40.0 20.0)
         (ptr 11 (+ t 10000)
              :move 11
              41.0 21.0
              :samples [(u/sample (+ t 9999) 400.0 400.0 :kind :predicted)])
         (ptr 12 (+ t 50000) :up 11 41.0 21.0)]))


(defn- scenario-input-loss-multi-arena
  []
  (into (boot
          :geometry
          (geometry
            :nodes
            [{:node-id ::a,
              :interaction/path [{:node-id ::a,
                                  :recognizers [(pan-decl ::a [::a :pan] {})],
                                  :touch-action :none}],
              :touch-action :none,
              :regions [{:bounds {:x 0, :y 0, :width 100, :height 100},
                         :paint-order 10}]}
             {:node-id ::b,
              :interaction/path [{:node-id ::b,
                                  :recognizers [(pan-decl ::b [::b :pan] {})],
                                  :touch-action :none}],
              :touch-action :none,
              :regions [{:bounds {:x 200, :y 0, :width 100, :height 100},
                         :paint-order 11}]}]))
        [(ptr 10 t :down 11 50.0 50.0) (ptr 11 (+ t 1000) :down 12 250.0 50.0)
         ;; one retention gap cancels both active arenas
         (rt 12 (+ t 20000)
             :terminal {:message/kind :dao.terminal/input-loss,
                        :generation-id u/generation,
                        :after-input-seq 10,
                        :before-input-seq 12,
                        :affected-pointer-ids #{11 12},
                        :reason :stream-capacity})]))


(defn- scenario-unrecognized-event-kind
  []
  (into (boot :geometry (geometry :recognizers [(tap-decl [node :tap])]))
        [(u/rt 10 t :network {:input/kind :pointer})]))


;; ---------------------------------------------------------------------------
;; Custom machine scenarios
;; ---------------------------------------------------------------------------

(def custom-tap-machine
  {:machine/version 1,
   :initial :possible,
   :state {:origin nil},
   :windows {:motion {:capacity 8}},
   :states {:possible
            [{:on :pointer/down,
              :when [:= [:contacts/count] 1],
              :actions [[:state/assoc :origin [:contacts/centroid]]
                        [:arena/hold]]}
             {:on :pointer/move,
              :when [:> [:distance [:state/get :origin] [:contacts/centroid]]
                     [:setting :motion/slop]],
              :actions [[:arena/reject] [:goto :rejected]]}
             {:on :pointer/up,
              :when [:<= [:elapsed-us] [:setting :tap/max-duration-us]],
              :actions [[:arena/accept]
                        [:emit :recognized
                         {:count 1,
                          :contacts 1,
                          :duration-us [:elapsed-us],
                          :position [:contacts/centroid]}] [:goto :ended]]}
             {:on :pointer/cancel,
              :when true,
              :actions [[:arena/reject] [:goto :rejected]]}],
            :ended [],
            :rejected []}})


(defn- custom-decl
  ([id] (custom-decl node id custom-tap-machine))
  ([id machine-data] (custom-decl node id machine-data))
  ([_node id machine-data]
   {:recognizer/id id,
    :gesture/kind :tap,
    :machine machine-data,
    :config {},
    :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}))


(defn- scenario-machine-custom-tap
  []
  (into (boot :geometry (geometry :recognizers [(custom-decl [node :custom])])
              :subs [(sub "c" :tap)])
        [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 10000) :move 11 41.0 21.0)
         (ptr 12 (+ t 20000) :up 11 41.0 21.0)]))


;; Ordered guards: the first true transition runs, later ones never do.
(defn- scenario-machine-transition-order
  []
  (let [ordered {:machine/version 1,
                 :initial :possible,
                 :state {:seen :none},
                 :windows {},
                 :states
                 {:possible
                  [{:on :pointer/move,
                    :when [:= [:state/get :seen] :none],
                    :actions [[:state/assoc :seen :first] [:arena/hold]]}
                   {:on :pointer/move,
                    :when true,
                    :actions [[:state/assoc :seen :second] [:arena/hold]]}
                   {:on :pointer/up,
                    :when [:= [:state/get :seen] :first],
                    :actions [[:arena/accept]
                              [:emit :recognized
                               {:count [:contacts/count],
                                :contacts [:contacts/count],
                                :duration-us [:elapsed-us],
                                :position [:contacts/centroid]}]
                              [:goto :ended]]}
                   {:on :pointer/cancel,
                    :when true,
                    :actions [[:arena/reject] [:goto :rejected]]}],
                  :ended [],
                  :rejected []}}]
    (into (boot :geometry
                (geometry :recognizers [(custom-decl [node :ordered] ordered)]))
          [(ptr 10 t :down 11 40.0 20.0) (ptr 11 (+ t 10000) :move 11 41.0 21.0)
           ;; the second move matches both guards: only the first runs
           (ptr 12 (+ t 20000) :move 11 42.0 22.0)
           (ptr 13 (+ t 30000) :up 11 42.0 22.0)])))


(defn- scenario-machine-timers
  []
  (let [ticking {:machine/version 1,
                 :initial :possible,
                 :state {},
                 :windows {},
                 :states
                 {:possible
                  [{:on :pointer/down, :when true, :actions [[:arena/hold]]}
                   {:on :pointer/move,
                    :when true,
                    :actions [[:timer/start :tick [:elapsed-us]]
                              [:arena/hold]]}
                   {:on :pointer/up,
                    :when true,
                    :actions [[:arena/accept] [:goto :ended]]}
                   {:on :pointer/cancel,
                    :when true,
                    :actions [[:arena/reject] [:goto :rejected]]}],
                  :ended [],
                  :rejected []}}
        inputs (into (boot :geometry
                           (geometry :recognizers
                                     [(custom-decl [node :ticking] ticking)]))
                     [(ptr 10 t :down 11 40.0 20.0)
                      (ptr 11 (+ t 10000) :move 11 45.0 20.0)
                      ;; the restart cancels sequence 1 before sequence 2
                      (ptr 12 (+ t 20000) :move 11 50.0 20.0)])
        [fire] (effect-requests (outputs-of inputs) :start)]
    (into inputs [(timer-fires 13 (+ t 30000) fire)])))


(defn- scenario-machine-fault-isolation
  []
  (let [dividing (assoc-in custom-tap-machine
                           [:states :possible]
                           [{:on :pointer/move,
                             :when [:> [:/ [:elapsed-us] 0] 1],
                             :actions [[:arena/reject] [:goto :rejected]]}
                            {:on :pointer/down, :when true, :actions [[:arena/hold]]}
                            {:on :pointer/up,
                             :when true,
                             :actions [[:arena/accept]
                                       [:emit :recognized
                                        {:count 1,
                                         :contacts 1,
                                         :duration-us [:elapsed-us],
                                         :position [:contacts/centroid]}]
                                       [:goto :ended]]}
                            {:on :pointer/cancel,
                             :when true,
                             :actions [[:arena/reject] [:goto :rejected]]}])]
    (into (boot :geometry
                (geometry :recognizers
                          [(custom-decl node [node :dividing] dividing)]))
          [(ptr 10 t :down 11 40.0 20.0)
           ;; division by zero faults only this candidate
           (ptr 11 (+ t 10000) :move 11 41.0 21.0)
           (ptr 12 (+ t 20000) :up 11 41.0 21.0)])))


(defn- invalid-machine-fixture
  "One minimal geometry presenting a machine that violates one validation
  rule: the candidate is omitted with one invalid-recognizer diagnostic."
  [_id mutate]
  (fn []
    (into (boot nil)
          [(u/rt 10 t
                 :geometry (u/presented-geometry
                             {:node-id node,
                              :recognizers [(custom-decl
                                              node
                                              [node :bad]
                                              (mutate custom-tap-machine))],
                              :frame-id 43}))])))


(def ^:private invalid-machine-mutations
  {:invalid-version (fn [m] (assoc m :machine/version 2)),
   :unknown-initial-state (fn [m] (assoc m :initial :nowhere)),
   :invalid-window (fn [m] (assoc-in m [:windows :motion :capacity] 0)),
   :non-keyword-state (fn [m]
                        (-> m
                            (update :states dissoc :ended)
                            (assoc-in [:states "ended"] []))),
   :invalid-transitions (fn [m] (assoc-in m [:states :ended] :not-a-list)),
   :missing-cancel-transition
   (fn [m]
     (update-in m
                [:states :possible]
                (fn [ts]
                  (vec (filter #(not= :pointer/cancel (:on %)) (vec ts)))))),
   :duplicate-transition (fn [m]
                           (update-in m
                                      [:states :possible]
                                      conj
                                      {:on :pointer/down,
                                       :when [:= [:contacts/count] 1],
                                       :actions [[:arena/hold]]})),
   :unknown-selector
   (fn [m] (assoc-in m [:states :possible 0 :on] :pointer/side-ways)),
   :unknown-operator (fn [m]
                       (assoc-in m
                                 [:states :possible 0 :when 1]
                                 [:teleport [:contacts/count]])),
   :unknown-action
   (fn [m] (assoc-in m [:states :possible 0 :actions] [[:arena/fly-away]])),
   :invalid-arity
   (fn [m] (assoc-in m [:states :possible 0 :when] [:< [:contacts/count]])),
   :undeclared-setting
   (fn [m]
     (assoc-in m [:states :possible 1 :when 1] [:setting :motion/top-speed])),
   :non-finite-literal (fn [m] (assoc-in m [:state :origin] ##Inf)),
   :unknown-state
   (fn [m] (assoc-in m [:states :possible 0 :actions 1] [:goto :nowhere])),
   :undeclared-window (fn [m]
                        (assoc-in m
                                  [:states :possible 0 :actions 0]
                                  [:window/push :nowhere [:contacts/centroid]])),
   :illegal-emission-phase
   (fn [m] (assoc-in m [:states :possible 2 :actions 1 1] :tapping)),
   :duplicate-decision (fn [m]
                         (assoc-in m
                                   [:states :possible 2 :actions]
                                   [[:arena/accept] [:arena/reject] [:goto :ended]])),
   :emit-before-accept (fn [m]
                         (assoc-in m
                                   [:states :possible 2 :actions]
                                   [[:emit :recognized {:count 1}] [:arena/accept]
                                    [:goto :ended]])),
   :malformed-transition (fn [m]
                           (update-in m [:states :possible] conj :not-a-map))})


;; ---------------------------------------------------------------------------
;; Fixture registry
;; ---------------------------------------------------------------------------

(def ^:private scenarios
  (into
    {}
    (concat
      [[:tap/single scenario-tap-single]
       [:tap/two-contact scenario-tap-two-contact]
       [:tap/three-contact scenario-tap-three-contact]
       [:tap/double scenario-tap-double] [:tap/repeated scenario-tap-repeated]
       [:tap/deferred-revival scenario-tap-deferred-revival]
       [:longpress/accept scenario-longpress-accept]
       [:longpress/early-up scenario-longpress-early-up]
       [:longpress/move-reject scenario-longpress-move-reject]
       [:longpress/cancel scenario-longpress-cancel]
       [:longpress/stationary scenario-longpress-stationary]
       [:arena/child-tap-ancestor-pan scenario-child-tap-ancestor-pan]
       [:pan/axis-y scenario-pan-axis-y]
       [:pan/free-leaving-rect scenario-pan-free-leaving-rect]
       [:pan/end-with-fling scenario-pan-end-with-fling]
       [:pan/end-without-fling scenario-pan-end-without-fling]
       [:swipe/boundaries scenario-swipe-boundaries]
       [:transform/grow scenario-transform-grow]
       [:transform/cooperative-scale-rotation
        scenario-cooperative-scale-rotation]
       [:join/contacts-changed-order scenario-join-contacts-changed-order]
       [:join/bridge-merge scenario-bridge-merge]
       [:join/after-accept-on scenario-late-join-after-accept]
       [:join/after-accept-off scenario-late-join-without-accept]
       [:arena/path-disjoint-concurrent scenario-path-disjoint-concurrent]
       [:pan/lift-end scenario-lift-end] [:pan/lift-hold scenario-lift-hold]
       [:transform/lift-degrade scenario-lift-degrade]
       [:arena/contact-overflow scenario-contact-overflow]
       [:arena/owned-candidate-omitted scenario-owned-candidate-omitted]
       [:capture/across-region scenario-capture-across-region]
       [:edge-pan/delivered scenario-edge-pan-delivered]
       [:edge-pan/cancelled scenario-edge-pan-cancelled]
       [:edge-pan/never-qualifies scenario-edge-pan-never-qualifies]
       [:pressure/with scenario-pressure-with]
       [:pressure/without scenario-pressure-without]
       [:capture/across-frames scenario-capture-across-frames]
       [:terminal/frames-rejected-skipped scenario-frames-rejected-skipped]
       [:terminal/reset scenario-terminal-reset]
       [:diagnostic/coordinate-space-mismatch
        scenario-coordinate-space-mismatch]
       [:diagnostic/stale-generation scenario-stale-generation]
       [:diagnostic/profile-mismatch scenario-profile-mismatch]
       [:diagnostic/duplicate-pointer-down scenario-duplicate-pointer-down]
       [:diagnostic/orphan-pointer-packet scenario-orphan-pointer-packet]
       [:diagnostic/input-sequence-gap scenario-input-sequence-gap]
       [:diagnostic/no-active-frame scenario-no-active-frame]
       [:diagnostic/future-frame scenario-future-frame]
       [:diagnostic/stale-frame scenario-stale-frame]
       [:diagnostic/unsupported-region scenario-unsupported-region]
       [:geometry/no-remint scenario-no-remint]
       [:timer/deadline-tie scenario-timer-deadline-tie]
       [:timer/stale-sequence scenario-timer-stale-sequence]
       [:diagnostic/late-runtime-input scenario-late-runtime-input]
       [:predicted/miscorrection scenario-predicted-miscorrection]
       [:input-loss/multi-arena scenario-input-loss-multi-arena]
       [:diagnostic/unrecognized-event-kind scenario-unrecognized-event-kind]
       [:machine/custom-tap scenario-machine-custom-tap]
       [:machine/transition-order scenario-machine-transition-order]
       [:machine/timers scenario-machine-timers]
       [:machine/fault-isolation scenario-machine-fault-isolation]]
      (map (fn [[rule mutate]]
             [(keyword "machine" (str "invalid-" (name rule)))
              (invalid-machine-fixture rule mutate)])
           invalid-machine-mutations))))


(defn build-fixture
  "Derive the committed fixture value for one scenario id."
  [scenario-id]
  (let [inputs ((get scenarios scenario-id))
        {:keys [state outputs]} (event/replay (event/initial-state) inputs)]
    {:fixture/id scenario-id,
     :initial-state nil,
     :inputs (vec inputs),
     :expect {:outputs (trace/round-deep outputs),
              :state (event/fixture-projection state)}}))


(defn corpus
  "The complete fixture corpus keyed by fixture id."
  []
  (into {} (map (fn [[id _f]] [id (build-fixture id)]) scenarios)))
