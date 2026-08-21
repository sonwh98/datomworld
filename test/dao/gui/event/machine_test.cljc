;; Custom recognizer machines expressed as machine data: the total DSL
;; evaluator and validation rules. Specification: docs/design/dao.gui.event.md
;; section Recognizer Machine Data.
(ns dao.gui.event.machine-test
  (:require [clojure.test :refer [deftest is testing]]
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


;; ---------------------------------------------------------------------------
;; Installation-time validation of custom machines
;; ---------------------------------------------------------------------------

(deftest invalid-custom-machine-is-rejected-at-declaration
  (let [bad-machine
        (assoc-in tap-machine [:states :possible 0 :on] :pointer/side-ways)
        decl {:recognizer/id [node-id :custom],
              :gesture/kind :tap,
              :machine bad-machine,
              :config {},
              :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}
        geometry (u/presented-geometry {:node-id node-id, :recognizers [decl]})
        s1 (:state (event/step (event/initial-state)
                               (u/rt 0 u/t0 :terminal space-input)))
        {:keys [state outputs]} (event/step s1 (u/rt 1 u/t0 :geometry geometry))
        diagnostic (first (filter :diagnostic/kind outputs))]
    (is (= [:dao.gui.event/invalid-recognizer] (mapv :diagnostic/kind outputs)))
    (is (= :unknown-selector (:reason diagnostic)))
    (is (= node-id (:node-id diagnostic)))
    (is (= [node-id :custom] (:recognizer/id diagnostic)))
    ;; the invalid candidate is omitted: a down creates no arena
    (let [after-down (:state (event/step state
                                         (u/rt 2 (+ u/t0 1)
                                               :pointer (u/pointer-packet
                                                          {:phase :down,
                                                           :id 11,
                                                           :x 40.0,
                                                           :y 20.0,
                                                           :time-us (+ u/t0 1),
                                                           :input-seq 2}))))]
      (is (= [] (:active-arena-ids (event/fixture-projection after-down)))))))


(deftest non-numeric-comparison-is-total-through-the-reducer
  (let [string-guard-machine
        (assoc-in tap-machine
                  [:states :possible]
                  [{:on :pointer/move,
                    ;; "a" is a legal EDN literal operand: the comparison is
                    ;; false, never a throw
                    :when [:< "a" 1],
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
        state (boot string-guard-machine)
        result (run state
                    [(ptr 10 u/t0 :down 11 40.0 20.0)
                     (ptr 11 (+ u/t0 10000) :move 11 100.0 80.0)
                     (ptr 12 (+ u/t0 20000) :up 11 100.0 80.0)])
        [gesture] (gestures (:outputs result))]
    ;; no throw, no fault: the guard was false so the move was a no-op and
    ;; the up still recognizes
    (is (some? gesture))
    (is (= [] (filter :diagnostic/kind (:outputs result))))))


(deftest and-or-short-circuit-propagates-evaluated-faults
  (let [machine-with-guard
        (fn [guard]
          (assoc-in tap-machine
                    [:states :possible]
                    [{:on :pointer/move,
                      :when guard,
                      :actions [[:arena/reject] [:goto :rejected]]}
                     {:on :pointer/down, :when true, :actions [[:arena/hold]]}
                     {:on :pointer/up,
                      :when true,
                      :actions [[:arena/accept] [:goto :ended]]}
                     {:on :pointer/cancel,
                      :when true,
                      :actions [[:arena/reject] [:goto :rejected]]}]))
        step-move (fn [guard]
                    (machine/step (machine-with-guard guard)
                                  {:time-us 100, :contacts {}}
                                  {:machine/input :pointer/move}
                                  (machine/initial-state tap-machine)
                                  {}))
        faulting [:/ [:elapsed-us] 0]]
    (testing "a false first operand short-circuits before the fault"
      (let [result (step-move [:and false faulting])]
        (is (not (:fault? result)))
        (is (= :hold (:decision result))
            "the guard is false: the transition does not run")))
    (testing "a faulting evaluated operand faults the logic"
      (let [result (step-move [:and faulting false])]
        (is (true? (:fault? result)))))
    (testing "a true first operand short-circuits :or before the fault"
      (let [result (step-move [:or true faulting])]
        (is (false? (:fault? result)))
        (is (= :reject (:decision result))
            "the guard is true: the reject transition runs")))
    (testing "a faulting :or operand that is actually evaluated faults"
      (let [result (step-move [:or faulting true])]
        (is (true? (:fault? result)))))))


(deftest unknown-machine-input-faults-the-candidate
  (let [state (machine/initial-state tap-machine)
        result (machine/step tap-machine
                             {:time-us 100, :contacts {}}
                             {:machine/input :pointer/side-ways}
                             state
                             {})]
    (is (true? (:fault? result)))
    (is (= :reject (:decision result)))))


;; ---------------------------------------------------------------------------
;; Additional validation rules
;; ---------------------------------------------------------------------------

(deftest validation-rejects-non-keyword-state-ids
  (is (contains? (reasons-for (-> tap-machine
                                  (update :states dissoc :ended)
                                  (assoc-in [:states "ended"] [])))
                 :non-keyword-state)))


(deftest validation-rejects-non-finite-literals
  (is (contains? (reasons-for
                   (assoc-in tap-machine [:states :possible 2 :when 1] ##Inf))
                 :non-finite-literal))
  (is (contains? (reasons-for (assoc-in tap-machine
                                        [:states :possible 0 :actions 0 2 0]
                                        ##NaN))
                 :non-finite-literal)))


(deftest validation-rejects-undeclared-settings
  (is (contains? (reasons-for (assoc-in tap-machine
                                        [:states :possible 1 :when 1]
                                        [:setting :motion/top-speed]))
                 :undeclared-setting)))


(deftest validation-rejects-duplicate-transitions
  (is (contains? (reasons-for (update-in tap-machine
                                         [:states :possible]
                                         conj
                                         ;; same :on and :when as the first
                                         ;; transition: dead code
                                         {:on :pointer/down,
                                          :when [:= [:contacts/count] 1],
                                          :actions [[:arena/hold]]}))
                 :duplicate-transition)))


(deftest validation-rejects-emit-before-accept
  (is (contains? (reasons-for (assoc-in tap-machine
                                        [:states :possible 2 :actions]
                                        [[:emit :recognized {:count 1}] [:arena/accept]
                                         [:goto :ended]]))
                 :emit-before-accept)))


(deftest validation-rejects-wrong-expression-arities
  (testing "binary comparison with one operand"
    (is (contains? (reasons-for (assoc-in tap-machine
                                          [:states :possible 0 :when]
                                          [:< [:contacts/count]]))
                   :invalid-arity)))
  (testing "setting with no key"
    (is (contains? (reasons-for (assoc-in tap-machine
                                          [:states :possible 1 :when 1]
                                          [:setting]))
                   :invalid-arity)))
  (testing "clamp with two operands"
    (is (contains? (reasons-for (assoc-in tap-machine
                                          [:states :possible 1 :when]
                                          [:clamp [:elapsed-us] 10]))
                   :invalid-arity))))


(deftest validation-rejects-malformed-transitions
  (is (contains? (reasons-for (update tap-machine
                                      :states assoc
                                      :possible (conj (:possible (:states
                                                                   tap-machine))
                                                      :not-a-map)))
                 :malformed-transition)))


(deftest validation-rejects-non-finite-initial-state-values
  (is (contains? (reasons-for (assoc-in tap-machine [:state :origin] ##Inf))
                 :non-finite-literal)))


;; ---------------------------------------------------------------------------
;; Timer restart ordering
;; ---------------------------------------------------------------------------

(def restarting-machine
  {:machine/version 1,
   :initial :possible,
   :state {},
   :windows {},
   :states {:possible [{:on :pointer/down, :when true, :actions [[:arena/hold]]}
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
            :rejected []}})


(deftest timer-restart-cancels-the-prior-sequence-before-the-new-start
  (let [state (boot restarting-machine)
        after-first (run state
                         [(ptr 10 u/t0 :down 11 40.0 20.0)
                          (ptr 11 (+ u/t0 10000) :move 11 45.0 20.0)])
        first-effects (filter :effect/kind (:outputs after-first))
        second (run (:state after-first)
                    [(ptr 12 (+ u/t0 20000) :move 11 50.0 20.0)])
        second-effects (filter :effect/kind (:outputs second))
        [first-start] first-effects
        [cancel new-start] second-effects]
    ;; the first move starts sequence 1
    (is (= [:start] (mapv :timer/op first-effects)))
    (is (= 1 (:timer-seq first-start)))
    ;; the second move restarts: cancel of sequence 1 precedes start of 2
    (is (= [:cancel :start] (mapv :timer/op second-effects)))
    (is (= 1 (:timer-seq cancel)))
    (is (= 2 (:timer-seq new-start)))
    (is (< (:output/seq cancel) (:output/seq new-start)))
    ;; only the new correlation key remains scheduled
    (is (= [[u/generation u/space-id 0 [node-id :custom] :tick 2]]
           (:scheduled-timer-keys (event/fixture-projection (:state
                                                              second)))))))


(deftest late-fire-of-a-restarted-sequence-diagnoses-late-timer
  (let [state (boot restarting-machine)
        after-moves (:state (run state
                                 [(ptr 10 u/t0 :down 11 40.0 20.0)
                                  (ptr 11 (+ u/t0 10000) :move 11 45.0 20.0)
                                  (ptr 12 (+ u/t0 20000) :move 11 50.0 20.0)]))
        stale-fire (u/rt 13 (+ u/t0 30000)
                         :timer (u/timer-fired {:arena-id 0,
                                                :recognizer/id [node-id
                                                                :custom],
                                                :timer-id :tick,
                                                :timer-seq 1,
                                                :time-us (+ u/t0 30000)}))
        {:keys [outputs state]} (event/step after-moves stale-fire)]
    (is (= [:dao.gui.event/late-timer]
           (mapv :diagnostic/kind (filter :diagnostic/kind outputs)))
        "the superseded sequence is late")
    ;; sequence 2 is still scheduled
    (is (= [[u/generation u/space-id 0 [node-id :custom] :tick 2]]
           (:scheduled-timer-keys (event/fixture-projection state))))))


(deftest failed-restart-leaves-the-prior-timer-unchanged
  (let [faulting-restart
        ;; the first move starts :tick cleanly; the second move's restart
        ;; expression divides by zero
        {:machine/version 1,
         :initial :possible,
         :state {:n 10},
         :windows {},
         :states {:possible
                  [{:on :pointer/down,
                    :when true,
                    :actions [[:state/assoc :n 10] [:arena/hold]]}
                   {:on :pointer/move,
                    :when [:= [:state/get :n] 10],
                    :actions [[:state/assoc :n 0] [:timer/start :tick 1000]
                              [:arena/hold]]}
                   {:on :pointer/move,
                    :when true,
                    :actions [[:timer/start :tick [:/ 1000 [:state/get :n]]]
                              [:arena/hold]]}
                   {:on :pointer/up,
                    :when true,
                    :actions [[:arena/accept] [:goto :ended]]}
                   {:on :pointer/cancel,
                    :when true,
                    :actions [[:arena/reject] [:goto :rejected]]}],
                  :ended [],
                  :rejected []}}
        state (boot faulting-restart)
        after-first (run state
                         [(ptr 10 u/t0 :down 11 40.0 20.0)
                          (ptr 11 (+ u/t0 10000) :move 11 45.0 20.0)])
        second (run (:state after-first)
                    [(ptr 12 (+ u/t0 20000) :move 11 50.0 20.0)])]
    ;; sequence 1 started on the first move
    (is (= [[u/generation u/space-id 0 [node-id :custom] :tick 1]]
           (:scheduled-timer-keys (event/fixture-projection (:state
                                                              after-first)))))
    ;; the restart expression faulted: no partial cancellation or start
    (is (= [] (filter :effect/kind (:outputs second))))
    (is (= [:dao.gui.event/recognizer-fault]
           (mapv :diagnostic/kind (filter :diagnostic/kind (:outputs second)))))
    (is (= [[u/generation u/space-id 0 [node-id :custom] :tick 1]]
           (:scheduled-timer-keys (event/fixture-projection (:state
                                                              second)))))))
