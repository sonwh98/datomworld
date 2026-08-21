;; Touch-policy normalization, intersection, and CSS serialization, plus
;; input loss and subscriber dispatch details. Specification:
;; docs/design/dao.gui.event.md sections Mobile Web Touch Policy,
;; Transport Coalescing And Backpressure, Subscriber Model.
(ns dao.gui.event.policy-test
  (:require [clojure.test :refer [are deftest is]]
            [dao.gui.event :as event]
            [dao.gui.event.policy :as policy]
            [dao.gui.event.util :as u]))


;; ---------------------------------------------------------------------------
;; Policy normalization and serialization
;; ---------------------------------------------------------------------------

(deftest normalize-expands-directional-policies
  (is (= #{:pan-left :pan-right} (policy/normalize :pan-x)))
  (is (= #{:pan-up :pan-down} (policy/normalize :pan-y)))
  (is (= #{:pan-left :pan-right :pan-up :pan-down :pinch-zoom}
         (policy/normalize :manipulation)))
  (is (= #{} (policy/normalize :none)))
  (is (= policy/unconstrained (policy/normalize :auto))))


(deftest intersection-is-root-to-target
  (is (= #{} (policy/intersect [:auto :none])))
  (is (= policy/unconstrained (policy/intersect [:auto :auto])))
  (is (= #{:pan-up :pan-down} (policy/intersect [:auto :manipulation :pan-y])))
  ;; none dominates
  (is (= #{} (policy/intersect [:manipulation :none])))
  ;; pan-x and pan-y keep only shared nothing: horizontal and vertical
  (is (= #{} (policy/intersect [:pan-x :pan-y]))))


(deftest canonical-serialization-table
  (are [edn css] (= css (policy/serialize edn))
    :auto "auto"
    :none "none"
    :manipulation "manipulation"
    #{:pan-left :pan-right} "pan-x"
    #{:pan-left} "pan-left"
    #{:pan-right} "pan-right"
    #{:pan-up :pan-down} "pan-y"
    #{:pan-up} "pan-up"
    #{:pan-down} "pan-down"
    #{:pinch-zoom} "pinch-zoom"
    #{:pan-left :pan-right :pan-up :pan-down} "pan-x pan-y"
    #{:pan-left :pan-right :pinch-zoom} "pan-x pinch-zoom"))


(deftest intersection-then-serialization-roundtrip
  (is (= "pan-y"
         (policy/serialize (policy/intersect [:auto :manipulation :pan-y]))))
  (is (= "none" (policy/serialize (policy/intersect [:auto :pan-x :pan-y])))))


(deftest invalid-authored-policies-are-rejected
  (is (nil? (policy/normalize :spin)))
  (is (nil? (policy/normalize #{:pan-left :weird}))))


;; ---------------------------------------------------------------------------
;; Input loss
;; ---------------------------------------------------------------------------

(def node-id :dao.gui.event.util/save)


(def space-input
  (u/coordinate-space-change {:old-coordinate-space-id nil,
                              :coordinate-space-id u/space-id,
                              :width 390.0,
                              :height 844.0}))


(defn- boot-pan
  [& {:keys [subscription]}]
  (let [decl {:recognizer/id [node-id :pan],
              :gesture/kind :pan,
              :machine :dao.gui.event/pan,
              :config {:contacts {:min 1, :max 1},
                       :axis :free,
                       :start-at :slop,
                       :contact-loss :end},
              :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}
        geometry (u/presented-geometry {:node-id node-id, :recognizers [decl]})
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


(defn- pan-started
  [state id]
  (:state (run state
               [(ptr 10 u/t0 :down id 40.0 20.0)
                (ptr 11 (+ u/t0 10000) :move id 60.0 25.0)])))


(deftest input-loss-cancels-affected-arena-with-cancel-phase
  (let [state (pan-started (boot-pan :subscription (u/sub-add "p" node-id :pan))
                           11)
        loss {:message/kind :dao.terminal/input-loss,
              :generation-id u/generation,
              :after-input-seq 10,
              :before-input-seq 13,
              :affected-pointer-ids #{11},
              :reason :stream-capacity}
        result (event/step state (u/rt 20 (+ u/t0 50000) :terminal loss))
        cancel-event (first (filter #(and (= :gesture (:event/kind %))
                                          (= :cancel (:phase %)))
                                    (:outputs result)))]
    (is (some? cancel-event))
    (is (= :input-loss (:reason (:payload cancel-event))))
    (is (= "p"
           (:subscription/id
             (first (filter #(and (:dispatch/kind %)
                                  (= :cancel (get-in % [:event :phase])))
                            (:outputs result))))))
    (is (= {:active-arena-ids [], :active-pointer-ids []}
           (select-keys (event/fixture-projection (:state result))
                        [:active-arena-ids :active-pointer-ids])))))


(deftest input-loss-without-pointer-set-cancels-every-arena
  (let [base (boot-pan)
        s1 (pan-started base 11)
        ;; a second, disjoint pointer arena via a second down on same node
        s2 (:state (run s1 [(ptr 12 (+ u/t0 11000) :down 12 200.0 20.0)]))
        loss {:message/kind :dao.terminal/input-loss,
              :generation-id u/generation,
              :after-input-seq 11,
              :before-input-seq 15,
              :reason :stream-capacity}
        result (event/step s2 (u/rt 20 (+ u/t0 50000) :terminal loss))]
    ;; every arena is cancelled; the uncaptured pointer record is not an
    ;; arena and survives until its own terminal packet
    (is (= {:active-arena-ids [], :active-pointer-ids [12]}
           (select-keys (event/fixture-projection (:state result))
                        [:active-arena-ids :active-pointer-ids])))))


;; ---------------------------------------------------------------------------
;; Subscriber dispatch details
;; ---------------------------------------------------------------------------

(deftest late-subscription-does-not-affect-active-arena
  (let [state (boot-pan)
        started (:state (run state [(ptr 10 u/t0 :down 11 40.0 20.0)]))
        after-sub (:state (event/step started
                                      (u/rt 12 (+ u/t0 11000)
                                            :subscription
                                            (u/sub-add "late" node-id :pan))))
        result (run after-sub
                    [(ptr 13 (+ u/t0 20000) :move 11 60.0 25.0)
                     (ptr 14 (+ u/t0 30000) :up 11 60.0 25.0)])]
    ;; the arena snapshotted registrations at creation: none existed
    (is (= [] (filter :dispatch/kind (:outputs result))))))


(deftest duplicate-interests-each-receive-dispatch
  (let [state (boot-pan :subscription nil)
        s1 (:state (event/step
                     state
                     (u/rt 3 u/t0 :subscription (u/sub-add "a" node-id :pan))))
        s2 (:state (event/step
                     s1
                     (u/rt 4 u/t0 :subscription (u/sub-add "b" node-id :pan))))
        result (run s2
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 60.0 25.0)
                     (ptr 12 (+ u/t0 20000) :up 11 60.0 25.0)])
        dispatches (filter :dispatch/kind (:outputs result))]
    ;; start and end each fan out to both interests
    (is (= 4 (count dispatches)))
    (is (= ["a" "b" "a" "b"] (mapv :subscription/id dispatches)))))


(deftest phase-filter-receives-only-matching-phases
  (let [state (boot-pan)
        s1 (:state (event/step state
                               (u/rt 3 u/t0
                                     :subscription
                                     (assoc (u/sub-add "starts" node-id :pan)
                                            :gesture/phases #{:start}))))
        result (run s1
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 60.0 25.0)
                     (ptr 12 (+ u/t0 20000) :up 11 60.0 25.0)])
        dispatches (filter :dispatch/kind (:outputs result))]
    (is (= [:start] (mapv (comp :phase :event) dispatches)))))


(deftest teardown-cancels-active-arenas
  (let [state (pan-started (boot-pan :subscription (u/sub-add "p" node-id :pan))
                           11)
        result (event/step state (u/rt 20 (+ u/t0 50000) :control (u/teardown)))
        cancels (filter #(and (= :gesture (:event/kind %))
                              (= :cancel (:phase %)))
                        (:outputs result))]
    (is (= 1 (count cancels)))
    (is (= :teardown (:reason (:payload (first cancels)))))
    (is (= "p"
           (:subscription/id
             (first (filter #(and (:dispatch/kind %)
                                  (= :cancel (get-in % [:event :phase])))
                            (:outputs result))))))))


(deftest serialization-covers-coexisting-permissions
  ;; horizontal, vertical, pinch serialize in that order, space separated
  (are [edn css] (= css (policy/serialize edn))
    #{:pan-left :pan-up} "pan-left pan-up"
    #{:pan-up :pinch-zoom} "pan-up pinch-zoom"
    #{:pan-left :pan-up :pinch-zoom} "pan-left pan-up pinch-zoom"
    #{:pan-right :pan-down :pinch-zoom} "pan-right pan-down pinch-zoom"
    #{:pan-left :pan-right :pan-up :pinch-zoom} "pan-x pan-up pinch-zoom"))


(deftest policy-sets-may-not-contain-reserved-keywords
  (is (nil? (policy/normalize #{:auto :none})))
  (is (nil? (policy/normalize #{:manipulation :pan-x})))
  (is (policy/normalize #{:pan-left :pinch-zoom})))
