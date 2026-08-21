;; Custom recognizer machines expressed as machine data: the total DSL
;; evaluator and validation rules. Specification: docs/design/dao.gui.event.md
;; section Recognizer Machine Data.
(ns dao.gui.event.machine-test
  (:require [clojure.test :refer [deftest is]]
            [dao.gui.event :as event]
            [dao.gui.event.machine :as machine]
            [dao.gui.event.util :as u]))


(def node-id :dao.gui.event.util/save)


(def space-input
  (u/coordinate-space-change {:old-coordinate-space-id nil,
                              :coordinate-space-id u/space-id,
                              :width 390.0,
                              :height 844.0}))


;; a DSL clone of the single tap: accept on up within duration and slop
(def tap-machine
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


(defn- boot
  [machine-data & {:keys [subscription]}]
  (let [decl {:recognizer/id [node-id :custom],
              :gesture/kind :tap,
              :machine machine-data,
              :config {},
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


(defn- gestures
  [outputs]
  (filter #(= :gesture (:event/kind %)) outputs))


;; ---------------------------------------------------------------------------
;; Evaluation
;; ---------------------------------------------------------------------------

(deftest custom-tap-machine-recognizes-through-the-dsl
  (let [state (boot tap-machine :subscription (u/sub-add "c" node-id :tap))
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 41.0 21.0)
                     (ptr 12 (+ u/t0 20000) :up 11 41.0 21.0)])
        [gesture] (gestures (:outputs result))]
    (is (some? gesture))
    (is (= :recognized (:phase gesture)))
    (is (= 1 (:count (:payload gesture))))
    (is (= 20000 (:duration-us (:payload gesture))))
    (is (= {:x 41.0, :y 21.0} (:position gesture)))
    (is (= "c"
           (:subscription/id (first (filter :dispatch/kind
                                            (:outputs result))))))))


(deftest custom-tap-machine-rejects-on-slop
  (let [state (boot tap-machine)
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 100.0 80.0)
                     (ptr 12 (+ u/t0 20000) :up 11 100.0 80.0)])]
    (is (= [] (gestures (:outputs result))))))


(deftest unmatched-legal-inputs-are-total-no-ops
  (let [state (boot tap-machine)
        ;; a hover-phase packet never reaches machines; contacts changes on
        ;; the possible state match no transition: total default
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 1000) :move 11 40.5 20.5)
                     (ptr 12 (+ u/t0 2000) :up 11 40.5 20.5)])
        [gesture] (gestures (:outputs result))]
    (is (some? gesture))))


(deftest division-by-zero-faults-only-that-candidate
  (let [dividing-machine
        (assoc-in tap-machine
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
                                :position [:contacts/centroid]}] [:goto :ended]]}
                   {:on :pointer/cancel,
                    :when true,
                    :actions [[:arena/reject] [:goto :rejected]]}])
        state (boot dividing-machine)
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 41.0 21.0)])]
    (is (= [] (gestures (:outputs result))))
    (is (= [:dao.gui.event/recognizer-fault]
           (mapv :diagnostic/kind
                 (filter :diagnostic/kind (:outputs result)))))))


(deftest timers-and-windows-work-through-the-dsl
  (let [window-machine
        {:machine/version 1,
         :initial :possible,
         :state {:taps 0},
         :windows {:motion {:capacity 2}},
         :states {:possible [{:on :pointer/down,
                              :when true,
                              :actions [[:window/push :motion
                                         [:contacts/centroid]]
                                        [:state/assoc :taps [:contacts/count]]
                                        [:arena/hold]]}
                             {:on :pointer/up,
                              :when [:= [:state/get :taps] 1],
                              :actions [[:window/push :motion
                                         [:contacts/centroid]] [:arena/accept]
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
                  :rejected []}}
        state (boot window-machine)
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 45.0 22.0)
                     (ptr 12 (+ u/t0 20000) :move 11 50.0 24.0)
                     (ptr 13 (+ u/t0 30000) :move 11 55.0 26.0)
                     (ptr 14 (+ u/t0 40000) :up 11 55.0 26.0)])
        [gesture] (gestures (:outputs result))]
    (is (some? gesture))))


;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn- reasons-for
  [machine-data]
  (set (map :reason (machine/validate machine-data))))


(deftest valid-machine-has-no-reasons (is (= #{} (reasons-for tap-machine))))


(deftest validation-rejects-unknown-operators
  (is (contains? (reasons-for (assoc-in tap-machine
                                        [:states :possible 0 :when 1]
                                        [:teleport [:contacts/count]]))
                 :unknown-operator)))


(deftest validation-rejects-unknown-actions
  (is (contains? (reasons-for (assoc-in tap-machine
                                        [:states :possible 0 :actions]
                                        [[:arena/fly-away]]))
                 :unknown-action)))


(deftest validation-rejects-goto-to-undeclared-state
  (is (contains? (reasons-for (assoc-in tap-machine
                                        [:states :possible 0 :actions 1]
                                        [:goto :nowhere]))
                 :unknown-state)))


(deftest validation-rejects-nonterminal-states-without-cancel
  (let [without-cancel (assoc-in tap-machine
                                 [:states :possible]
                                 (filter #(not= :pointer/cancel (:on %))
                                         (:possible (:states tap-machine))))]
    (is (contains? (reasons-for without-cancel) :missing-cancel-transition))))


(deftest validation-rejects-illegal-emission-phase
  (is (contains? (reasons-for (assoc-in tap-machine
                                        [:states :possible 2 :actions 1]
                                        [:emit :tapping {:count 1}]))
                 :illegal-emission-phase)))


(deftest validation-rejects-undeclared-windows
  (is (contains? (reasons-for (assoc-in tap-machine
                                        [:states :possible 0 :actions 0]
                                        [:window/push :nowhere [:contacts/centroid]]))
                 :undeclared-window)))


(deftest validation-rejects-nonpositive-window-capacity
  (is (contains? (reasons-for
                   (assoc-in tap-machine [:windows :motion :capacity] 0))
                 :invalid-window)))


(deftest validation-rejects-unknown-selector
  (is (contains? (reasons-for (assoc-in tap-machine
                                        [:states :possible 0 :on]
                                        :pointer/side-ways))
                 :unknown-selector)))


(deftest validation-rejects-unknown-operators-inside-actions
  (is (contains? (reasons-for (assoc-in tap-machine
                                        [:states :possible 0 :actions]
                                        [[:state/assoc :origin
                                          [:teleport [:contacts/count]]]
                                         [:arena/hold]]))
                 :unknown-operator)))


(deftest validation-covers-expressions-in-emit-payloads
  (is (contains? (reasons-for (assoc-in tap-machine
                                        [:states :possible 2 :actions 1]
                                        [:emit :recognized
                                         {:count [:diverge [:contacts/count]]}]))
                 :unknown-operator)))
