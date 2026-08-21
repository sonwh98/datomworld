;; Core binding contract: canonical runtime inputs, validation, subscription
;; registry, teardown, and the fixture state projection. Specification:
;; docs/design/dao.gui.event.md sections Event Boundary, Binding Contract,
;; Canonical Runtime Input Order, Reducer State And Step Order, Subscriber
;; Model, Diagnostics, Executable Fixture Contract.
(ns dao.gui.event.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.gui.event :as event]
            [dao.gui.event.util :as u]))


(defn- step
  ([input] (event/step (event/initial-state) input))
  ([state input] (event/step state input)))


(defn- outputs-of
  [state inputs]
  (mapcat (fn [in] (:outputs (step state in))) inputs))


(defn- diagnostics
  [outputs]
  (filter :diagnostic/kind outputs))


;; ---------------------------------------------------------------------------
;; Initial state schema
;; ---------------------------------------------------------------------------

(deftest initial-state-matches-version-1-schema
  (let [state (event/initial-state)]
    (is (= 1 (:dao.gui.event/state-version state)))
    (is (= #{:dao.gui.event/state-version :generation-id :last-runtime-time-us
             :last-runtime-seq :active-coordinate-space-id :coordinate-spaces
             :geometry :profiles :subscriptions :subscription-order :pointers
             :arenas :next-arena-id :timers}
           (set (keys state))))
    (is (nil? (:generation-id state)))
    (is (nil? (:last-runtime-time-us state)))
    (is (= -1 (:last-runtime-seq state)))
    (is (nil? (:active-coordinate-space-id state)))
    (is (= {} (:coordinate-spaces state)))
    (is (= {:active nil} (:geometry state)))
    (is (= {} (:profiles state)))
    (is (= {} (:subscriptions state)))
    (is (= [] (:subscription-order state)))
    (is (= {} (:pointers state)))
    (is (= {} (:arenas state)))
    (is (= 0 (:next-arena-id state)))))


;; ---------------------------------------------------------------------------
;; Step contract shape
;; ---------------------------------------------------------------------------

(deftest step-returns-state-and-ordered-outputs
  (let [{:keys [state outputs]}
        (step (u/rt 0 u/t0 :subscription (u/sub-add "s1" ::save)))]
    (is (= #{:state :outputs}
           (set (keys (step (event/initial-state)
                            (u/rt 0 u/t0
                                  :subscription (u/sub-add "s1" ::save)))))))
    (is (= [] outputs))
    (is (= ["s1"] (:subscription-order state)))))


(deftest every-output-carries-causality-seqs
  (let [state (-> (event/initial-state)
                  (as-> s (assoc-in s [:dao.gui.event/state-version] 1)))
        {:keys [outputs]}
        (step state (u/rt 7 u/t0 :pointer {:input/kind :bogus-thing}))]
    (is (= 1 (count outputs)))
    (is (= 7 (:runtime/seq (first outputs))))
    (is (= 0 (:output/seq (first outputs))))))


;; ---------------------------------------------------------------------------
;; Subscription registry
;; ---------------------------------------------------------------------------

(deftest subscription-add-registers-interest-in-order
  (let [state
        (-> (event/initial-state)
            (as-> s
              (:state
                (step s (u/rt 0 u/t0 :subscription (u/sub-add "s1" ::save)))))
            (as-> s
              (:state (step s
                            (u/rt 1 u/t0
                                  :subscription
                                  (u/sub-add "s2" ::map :pan))))))
        s2 (get-in state [:subscriptions "s2"])]
    (is (= ["s1" "s2"] (:subscription-order state)))
    (is (= ::map (:node-id s2)))
    (is (= :pan (:event-kind s2)))
    (is (false? (:raw? s2)))))


(deftest subscription-remove-is-ordered-and-idempotent
  (let [state
        (-> (event/initial-state)
            (as-> s
              (:state
                (step s (u/rt 0 u/t0 :subscription (u/sub-add "s1" ::save)))))
            (as-> s
              (:state
                (step s (u/rt 1 u/t0 :subscription (u/sub-add "s2" ::map))))))
        after-remove
        (-> state
            (as-> s
              (:state
                (step s (u/rt 2 u/t0 :subscription (u/sub-remove "s1"))))))
        after-unknown
        (-> after-remove
            (as-> s
              (:state
                (step s (u/rt 3 u/t0 :subscription (u/sub-remove "nope"))))))]
    (is (= ["s2"] (:subscription-order after-remove)))
    (is (not (contains? (:subscriptions after-remove) "s1")))
    ;; idempotent: an unknown remove changes nothing observable
    (is (= (:subscriptions after-unknown) (:subscriptions after-remove)))
    (is (= (:subscription-order after-unknown)
           (:subscription-order after-remove)))
    (is (= []
           (outputs-of (event/initial-state)
                       [(u/rt 0 u/t0 :subscription (u/sub-remove "nope"))])))))


(deftest subscription-add-accepts-raw-pointer-and-unknown-nodes
  (let [state (-> (event/initial-state)
                  (as-> s
                    (:state (step s
                                  (u/rt 0 u/t0
                                        :subscription
                                        (-> (u/sub-add "raw1"
                                                       ::unknown-node
                                                       :pointer)
                                            (assoc :raw? true)))))))]
    (is (true? (get-in state [:subscriptions "raw1" :raw?])))
    (is (= :pointer (get-in state [:subscriptions "raw1" :event-kind])))))


(defn- rejected-add
  [command]
  (let [base (-> (event/initial-state)
                 (as-> s
                   (:state (step s
                                 (u/rt 0 u/t0
                                       :subscription (u/sub-add "s1"
                                                                ::save))))))
        {:keys [state outputs]} (step base (u/rt 1 u/t0 :subscription command))]
    [state (diagnostics outputs)]))


(deftest malformed-subscription-adds-emit-diagnostic-and-leave-registry
  (testing "duplicate subscription id"
    (let [[state diags] (rejected-add (u/sub-add "s1" ::save))]
      (is (= 1 (count diags)))
      (is (= :dao.gui.event/unrecognized-event-kind
             (:diagnostic/kind (first diags))))
      (is (= :error (:severity (first diags))))
      (is (= ["s1"] (:subscription-order state)))))
  (testing "missing node id"
    (let [[state diags] (rejected-add (dissoc (u/sub-add "s2" ::save)
                                              :node-id))]
      (is (= 1 (count diags)))
      (is (= ["s1"] (:subscription-order state)))))
  (testing "illegal event kind"
    (let [[state diags] (rejected-add (u/sub-add "s2" ::save :made-up))]
      (is (= 1 (count diags)))
      (is (= ["s1"] (:subscription-order state)))))
  (testing "illegal phases"
    (let [[state diags] (rejected-add (assoc (u/sub-add "s2" ::save)
                                             :gesture/phases #{:tapping}))]
      (is (= 1 (count diags)))
      (is (= ["s1"] (:subscription-order state)))))
  (testing "empty phases"
    (let [[state diags] (rejected-add (assoc (u/sub-add "s2" ::save)
                                             :gesture/phases #{}))]
      (is (= 1 (count diags)))
      (is (= ["s1"] (:subscription-order state))))))


;; ---------------------------------------------------------------------------
;; Source and kind validation
;; ---------------------------------------------------------------------------

(defn- invalid-input
  [source value]
  (let [base (event/initial-state)
        {:keys [state outputs]} (step base (u/rt 3 u/t0 source value))]
    [state outputs]))


(deftest illegal-source-and-kind-pairs-are-unrecognized-event-kind
  (testing "unknown runtime source"
    (let [[state outputs] (invalid-input :network {:input/kind :pointer})]
      (is (= 1 (count outputs)))
      (is (= :dao.gui.event/unrecognized-event-kind
             (:diagnostic/kind (first outputs))))
      (is (= state (event/initial-state)))))
  (testing "pointer source carrying a message kind"
    (let [[state outputs] (invalid-input :pointer
                                         {:message/kind
                                          :dao.terminal/presented-geometry})]
      (is (= :dao.gui.event/unrecognized-event-kind
             (:diagnostic/kind (first outputs))))
      (is (= state (event/initial-state)))))
  (testing "geometry source carrying a pointer packet"
    (let [[state outputs] (invalid-input :geometry
                                         {:input/kind :pointer,
                                          :generation-id u/generation,
                                          :frame-id u/frame-id,
                                          :coordinate-space-id u/space-id,
                                          :profile-id u/profile-id,
                                          :input-seq 9,
                                          :pointer {:id 1,
                                                    :type :touch,
                                                    :primary? true,
                                                    :buttons 1,
                                                    :modifiers #{}},
                                          :phase :down,
                                          :samples [{:time-us u/t0,
                                                     :position {:x 40, :y 20},
                                                     :sample/kind :actual}]})]
      (is (= :dao.gui.event/unrecognized-event-kind
             (:diagnostic/kind (first outputs))))
      (is (= state (event/initial-state)))))
  (testing "control source with non-teardown value"
    (let [[state outputs] (invalid-input :control {:input/kind :other})]
      (is (= :dao.gui.event/unrecognized-event-kind
             (:diagnostic/kind (first outputs))))
      (is (= state (event/initial-state)))))
  (testing "timer source with wrong input kind"
    (let [[state outputs] (invalid-input :timer {:input/kind :pointer})]
      (is (= :dao.gui.event/unrecognized-event-kind
             (:diagnostic/kind (first outputs))))
      (is (= state (event/initial-state))))))


;; ---------------------------------------------------------------------------
;; Timestamp ordering
;; ---------------------------------------------------------------------------

(deftest regressing-runtime-time-is-late-runtime-input
  (let [base
        (-> (event/initial-state)
            (as-> s
              (:state
                (step s (u/rt 0 100 :subscription (u/sub-add "s1" ::save))))))
        {:keys [state outputs]}
        (step base (u/rt 1 50 :subscription (u/sub-remove "s1")))]
    (is (= 1 (count outputs)))
    (is (= :dao.gui.event/late-runtime-input
           (:diagnostic/kind (first outputs))))
    (is (= 100 (:last-runtime-time-us state)))
    (is (= 0 (:last-runtime-seq state)))
    (is (= ["s1"] (:subscription-order state)))))


(deftest equal-runtime-time-is-accepted
  (let [base
        (-> (event/initial-state)
            (as-> s
              (:state
                (step s (u/rt 0 100 :subscription (u/sub-add "s1" ::save))))))
        {:keys [state outputs]}
        (step base (u/rt 1 100 :subscription (u/sub-add "s2" ::map)))]
    (is (= [] outputs))
    (is (= ["s1" "s2"] (:subscription-order state)))
    (is (= 1 (:last-runtime-seq state)))))


;; ---------------------------------------------------------------------------
;; Teardown control value
;; ---------------------------------------------------------------------------

(deftest teardown-closes-binding-outputs
  (let [base (-> (event/initial-state)
                 (as-> s
                   (:state (step s
                                 (u/rt 0 u/t0
                                       :subscription (u/sub-add "s1"
                                                                ::save))))))
        torn (step base (u/rt 1 u/t0 :control (u/teardown)))
        after (step (:state torn)
                    (u/rt 2 u/t0 :subscription (u/sub-add "s2" ::map)))]
    (is (= [] (:outputs torn)))
    (is (= [] (:outputs after)))
    ;; teardown releases the registry; later inputs are ignored
    (is (= [] (get-in after [:state :subscription-order])))
    (is (= (:state torn) (:state after)))))


(deftest malformed-teardown-kind-is-unrecognized
  (let [{:keys [outputs]}
        (step (event/initial-state)
              (u/rt 0 u/t0 :control {:input/kind :dao.gui.event/nope}))]
    (is (= [:dao.gui.event/unrecognized-event-kind]
           (mapv :diagnostic/kind outputs)))))


;; ---------------------------------------------------------------------------
;; Fixture state projection
;; ---------------------------------------------------------------------------

(deftest fixture-projection-is-eleven-keys
  (let [projection (event/fixture-projection (event/initial-state))]
    (is (= #{:generation-id :coordinate-space-id :active-frame-id :profile-ids
             :subscription-ids :active-pointer-ids :active-arena-ids
             :scheduled-timer-keys :last-runtime-seq :last-runtime-time-us
             :next-arena-id}
           (set (keys projection))))
    (is (= {:generation-id nil,
            :coordinate-space-id nil,
            :active-frame-id nil,
            :profile-ids [],
            :subscription-ids [],
            :active-pointer-ids [],
            :active-arena-ids [],
            :scheduled-timer-keys [],
            :last-runtime-seq -1,
            :last-runtime-time-us nil,
            :next-arena-id 0}
           projection))))


;; ---------------------------------------------------------------------------
;; bind: data oriented, no ambient singleton
;; ---------------------------------------------------------------------------

(deftest bind-returns-versioned-data-oriented-binding
  (let [inputs {:runtime-input (list)}
        outputs {:effects nil,
                 :pointer nil,
                 :gesture nil,
                 :dispatch nil,
                 :diagnostic nil}
        binding (event/bind {:inputs inputs, :outputs outputs})]
    (is (= 1 (:dao.gui.event/binding-version binding)))
    (is (= inputs (:inputs binding)))
    (is (= outputs (:outputs binding)))
    (is (ifn? (:offer binding)))))


(deftest bindings-are-independent-instances
  (let [b1 (event/bind {:inputs {}, :outputs {}})
        b2 (event/bind {:inputs {}, :outputs {}})
        {:keys [binding outputs]}
        ((:offer b1) (u/rt 0 u/t0 :subscription (u/sub-add "s1" ::save)))]
    (is (= [] outputs))
    (is (= ["s1"] (:subscription-ids (event/fixture-projection binding))))
    ;; b2 never saw the input
    (is (= [] (:subscription-ids (event/fixture-projection b2))))))


;; ---------------------------------------------------------------------------
;; replay helper
;; ---------------------------------------------------------------------------

(deftest replay-folds-outputs-in-input-order
  (let [inputs [(u/rt 0 u/t0 :subscription (u/sub-add "s1" ::save))
                (u/rt 1 u/t0 :control (u/teardown))
                (u/rt 2 u/t0 :subscription (u/sub-add "ignored" ::map))]
        {:keys [state outputs]} (event/replay (event/initial-state) inputs)]
    (is (= [] outputs))
    ;; teardown released the registry; the post-teardown add was ignored
    (is (= [] (:subscription-order state)))))


(deftest subscription-add-accepts-every-standard-declarable-kind
  ;; the subscription vocabulary agrees with valid standard declarations:
  ;; :drag, :scale, :pinch, and :rotation are all subscribable
  (let [kinds [:tap :long-press :pan :drag :swipe :fling :transform :scale
               :pinch :rotation :edge-pan :pressure-press]
        state (reduce (fn [s [i k]]
                        (:state (event/step
                                  s
                                  (u/rt i
                                        u/t0
                                        :subscription
                                        (u/sub-add (str "sub-" i) ::save k)))))
                      (event/initial-state)
                      (map-indexed vector kinds))]
    (is (= (count kinds) (count (:subscription-order state))))
    (is (= [] (remove #(contains? event/standard-gesture-kinds %) kinds))
        "every declarable standard kind is in the public vocabulary")))
