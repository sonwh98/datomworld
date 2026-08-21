;; Terminal-side stream values: presented geometry installation, input
;; profiles, coordinate-space changes, and terminal reset. Specification:
;; docs/design/dao.gui.event.md sections Presented Geometry, Terminal Input
;; Profile, Coordinate-Space Changes, Terminal Signals, Frame And Input
;; Causality, Diagnostics.
(ns dao.gui.event.terminal-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.gui.event :as event]
            [dao.gui.event.util :as u]))


(defn- step*
  ([input] (event/step (event/initial-state) input))
  ([state input] (event/step state input)))


(defn- project
  [state]
  (event/fixture-projection state))


(def space-input
  (u/coordinate-space-change {:old-coordinate-space-id nil,
                              :coordinate-space-id u/space-id,
                              :width 390.0,
                              :height 844.0}))


(defn- boot
  "Space-change then geometry then profile, the minimal installed state a
  down packet can refer to."
  ([] (boot (u/presented-geometry {}) (u/input-profile {})))
  ([geometry profile]
   (as-> (event/initial-state) s
         (:state (step* s (u/rt 0 u/t0 :terminal space-input)))
         (:state (step* s (u/rt 1 (+ u/t0 1) :geometry geometry)))
         (:state (step* s (u/rt 2 (+ u/t0 2) :profile profile))))))


(defn- diag-kinds
  [outputs]
  (mapv :diagnostic/kind (filter :diagnostic/kind outputs)))


;; ---------------------------------------------------------------------------
;; Coordinate-space change
;; ---------------------------------------------------------------------------

(deftest initial-coordinate-space-change-installs-space-and-generation
  (let [{:keys [state outputs]} (step* (u/rt 0 u/t0 :terminal space-input))]
    (is (= [] outputs))
    (is (= {:generation-id u/generation,
            :coordinate-space-id u/space-id,
            :active-frame-id nil,
            :profile-ids [],
            :subscription-ids [],
            :active-pointer-ids [],
            :active-arena-ids [],
            :scheduled-timer-keys [],
            :last-runtime-seq 0,
            :last-runtime-time-us u/t0,
            :next-arena-id 0}
           (project state)))
    (is (= {:width 390.0, :height 844.0}
           (get-in state [:coordinate-spaces u/space-id :viewport])))))


(deftest coordinate-space-change-clears-geometry-and-requires-old-match
  (let [booted (boot)
        change (u/coordinate-space-change {:old-coordinate-space-id u/space-id,
                                           :coordinate-space-id 8})
        {:keys [state outputs]} (step* booted
                                       (u/rt 3 (+ u/t0 3) :terminal change))]
    (is (= [] outputs))
    (is (= 8 (:coordinate-space-id (project state))))
    (is (nil? (:active-frame-id (project state))))
    ;; profiles survive a space change; geometry does not
    (is (= [u/profile-id] (:profile-ids (project state))))
    ;; a space change whose old id does not match the active space is a
    ;; mismatch
    (let [bad (u/coordinate-space-change {:old-coordinate-space-id 999,
                                          :coordinate-space-id 9})
          {:keys [outputs]} (step* state (u/rt 4 (+ u/t0 4) :terminal bad))]
      (is (= [:dao.gui.event/coordinate-space-mismatch]
             (diag-kinds outputs))))))


(deftest coordinate-space-change-validates-viewport
  (let [bad (u/coordinate-space-change {:width 0, :height 100})
        {:keys [state outputs]} (step* (u/rt 0 u/t0 :terminal bad))]
    (is (= [:dao.gui.event/coordinate-space-mismatch] (diag-kinds outputs)))
    (is (nil? (:coordinate-space-id (project state))))))


(deftest coordinate-space-change-rejects-stale-generation
  (let [booted (boot)
        change (u/coordinate-space-change {:generation-id "other-generation",
                                           :old-coordinate-space-id u/space-id,
                                           :coordinate-space-id 8})
        {:keys [state outputs]} (step* booted
                                       (u/rt 3 (+ u/t0 3) :terminal change))]
    (is (= [:dao.gui.event/stale-generation-input] (diag-kinds outputs)))
    (is (= u/space-id (:coordinate-space-id (project state))))))


;; ---------------------------------------------------------------------------
;; Presented geometry
;; ---------------------------------------------------------------------------

(deftest geometry-installs-active-frame
  (let [booted (boot)
        geometry (u/presented-geometry {:frame-id 43})
        {:keys [state outputs]} (step* booted
                                       (u/rt 3 (+ u/t0 3) :geometry geometry))]
    (is (= [] outputs))
    (is (= 43 (:active-frame-id (project state))))))


(deftest geometry-requires-an-installed-coordinate-space
  (let [{:keys [state outputs]}
        (step* (u/rt 0 u/t0 :geometry (u/presented-geometry {})))]
    (is (= [:dao.gui.event/coordinate-space-mismatch] (diag-kinds outputs)))
    (is (nil? (:active-frame-id (project state))))))


(deftest geometry-rejects-stale-and-regressing-frames
  (let [booted (boot)]
    (testing "same frame id again is stale"
      (let [{:keys [state outputs]} (step* booted
                                           (u/rt 3 (+ u/t0 3)
                                                 :geometry (u/presented-geometry
                                                             {:frame-id
                                                              u/frame-id})))]
        (is (= [:dao.gui.event/stale-frame-input] (diag-kinds outputs)))
        (is (= u/frame-id (:active-frame-id (project state))))))
    (testing "lower frame id is stale"
      (let [{:keys [outputs]} (step* booted
                                     (u/rt 4 (+ u/t0 4)
                                           :geometry (u/presented-geometry
                                                       {:frame-id 41})))]
        (is (= [:dao.gui.event/stale-frame-input] (diag-kinds outputs)))))))


(deftest empty-geometry-replaces-the-hit-index
  (let [booted (boot)
        empty-frame (assoc (u/presented-geometry {:frame-id 50}) :nodes [])
        {:keys [outputs]} (step* booted
                                 (u/rt 3 (+ u/t0 3) :geometry empty-frame))]
    (is (= [] outputs))
    ;; the pointer milestone asserts behaviour against the empty index;
    ;; here the frame is installed without diagnostic
    (is (= [] (diag-kinds outputs)))))


(deftest geometry-rejects-stale-generation-and-space
  (let [booted (boot)]
    (testing "generation mismatch"
      (let [{:keys [outputs]} (step* booted
                                     (u/rt 3 (+ u/t0 3)
                                           :geometry (u/presented-geometry
                                                       {:generation-id "other",
                                                        :frame-id 43})))]
        (is (= [:dao.gui.event/stale-generation-input] (diag-kinds outputs)))))
    (testing "coordinate space mismatch"
      (let [{:keys [outputs]} (step* booted
                                     (u/rt 4 (+ u/t0 4)
                                           :geometry (u/presented-geometry
                                                       {:coordinate-space-id 8,
                                                        :frame-id 43})))]
        (is (= [:dao.gui.event/coordinate-space-mismatch]
               (diag-kinds outputs)))))))


(deftest invalid-region-geometry-diagnoses-unsupported-region
  (let [geometry (update (u/presented-geometry {:frame-id 43})
                         :nodes
                         (fn [[node]]
                           [(update
                              node
                              :regions
                              into
                              [{:bounds {:x 0, :y 0, :width -5, :height 10},
                                :paint-order 101}
                               {:bounds {:x "left", :y 0, :width 5, :height 10},
                                :paint-order 102}])]))
        booted (boot)
        {:keys [outputs]} (step* booted (u/rt 3 (+ u/t0 3) :geometry geometry))]
    ;; both malformed regions are omitted with one diagnostic each
    (is (= [:dao.gui.event/unsupported-region :dao.gui.event/unsupported-region]
           (diag-kinds outputs)))))


(deftest invalid-recognizer-declaration-diagnoses-and-omits
  (let [bad-machine
        (assoc-in (u/tap-decl ::save) [:machine] :dao.gui.event/nope)
        bad-contacts
        (assoc-in (u/tap-decl ::save) [:config :contacts] {:min 2, :max 1})
        geometry (u/presented-geometry
                   {:frame-id 43,
                    :node-id ::multi,
                    :recognizers
                    [(assoc-in (u/tap-decl ::multi) [:arena :mode] :weird)
                     bad-machine bad-contacts (u/pan-decl ::multi)]})
        booted (boot)
        {:keys [outputs]} (step* booted (u/rt 3 (+ u/t0 3) :geometry geometry))]
    (is (= [:dao.gui.event/invalid-recognizer :dao.gui.event/invalid-recognizer
            :dao.gui.event/invalid-recognizer]
           (diag-kinds outputs)))))


(deftest cooperative-declaration-without-group-is-rejected
  (let [cooperative-nil-group (-> (u/tap-decl ::save)
                                  (assoc-in [:arena :mode] :cooperative))
        geometry (u/presented-geometry {:frame-id 43,
                                        :node-id ::coop,
                                        :recognizers [cooperative-nil-group
                                                      (u/tap-decl ::coop)]})
        booted (boot)
        {:keys [outputs state]} (step* booted
                                       (u/rt 3 (+ u/t0 3) :geometry geometry))
        diagnostic (first (filter :diagnostic/kind outputs))]
    ;; exactly one invalid-recognizer diagnostic for the offending
    ;; declaration
    (is (= [:dao.gui.event/invalid-recognizer] (diag-kinds outputs)))
    (is (= :cooperative-without-group (:reason diagnostic)))
    (is (= ::coop (:node-id diagnostic)))
    ;; the valid sibling declaration is still installed
    (is (pos? (count (get-in state [:geometry :regions]))))))


(deftest repeated-ancestor-entries-across-paths-keep-their-recognizers
  (let [ancestor {:node-id ::scroll,
                  :recognizers [(u/pan-decl ::scroll)],
                  :touch-action :none}
        geometry {:message/kind :dao.terminal/presented-geometry,
                  :generation-id u/generation,
                  :frame-id 43,
                  :coordinate-space-id u/space-id,
                  :nodes
                  [{:node-id ::row-a,
                    :interaction/path [ancestor
                                       {:node-id ::row-a,
                                        :recognizers [(u/tap-decl ::row-a)],
                                        :touch-action :manipulation}],
                    :touch-action :manipulation,
                    :regions [{:bounds {:x 0, :y 0, :width 100, :height 50},
                               :paint-order 10}]}
                   {:node-id ::row-b,
                    :interaction/path [ancestor
                                       {:node-id ::row-b,
                                        :recognizers [(u/tap-decl ::row-b)],
                                        :touch-action :manipulation}],
                    :touch-action :manipulation,
                    :regions [{:bounds {:x 0, :y 60, :width 100, :height 50},
                               :paint-order 11}]}]}
        booted (boot)
        {:keys [outputs state]} (step* booted
                                       (u/rt 3 (+ u/t0 3) :geometry geometry))]
    ;; the same logical ancestor in two explicit root-to-target paths is
    ;; intentional repetition, not a duplicate declaration
    (is (= [] (diag-kinds outputs)))
    ;; both targets and the ancestor recognizers survive into the hit index
    (is (= 2 (count (get-in state [:geometry :regions]))))
    (is (= #{::row-a ::row-b}
           (set (map :node-id (get-in state [:geometry :regions])))))
    (is (every? #(= 2 (count (:interaction/path %)))
                (get-in state [:geometry :regions])))
    ;; every region keeps the shared ancestor's recognizer
    (is (every? #(= 1 (count (get-in % [:recognizers-by-node ::scroll])))
                (get-in state [:geometry :regions])))))


(deftest duplicate-target-identity-across-nodes-diagnoses
  (let [dup-target (fn []
                     {:node-id ::dupe,
                      :recognizers [(u/tap-decl ::dupe)],
                      :touch-action :none})
        geometry {:message/kind :dao.terminal/presented-geometry,
                  :generation-id u/generation,
                  :frame-id 43,
                  :coordinate-space-id u/space-id,
                  :nodes
                  [{:node-id ::dupe,
                    :interaction/path [(dup-target)],
                    :touch-action :none,
                    :regions [{:bounds {:x 0, :y 0, :width 50, :height 50},
                               :paint-order 10}]}
                   {:node-id ::dupe,
                    :interaction/path [(dup-target)],
                    :touch-action :none,
                    :regions [{:bounds {:x 60, :y 0, :width 50, :height 50},
                               :paint-order 11}]}]}
        booted (boot)
        {:keys [outputs]} (step* booted (u/rt 3 (+ u/t0 3) :geometry geometry))]
    ;; the same logical target declared by two nodes is a duplicate
    (is (= [:dao.gui.event/invalid-recognizer] (diag-kinds outputs)))
    (is (= :duplicate-candidate
           (:reason (first (filter :diagnostic/kind outputs)))))))


(deftest duplicate-candidate-identity-in-one-frame-diagnoses
  (let [dup (u/tap-decl ::save [::save :tap])
        geometry (u/presented-geometry {:frame-id 43,
                                        :recognizers [(u/tap-decl ::save) dup],
                                        :node-id ::dupe})
        booted (boot)
        {:keys [outputs]} (step* booted (u/rt 3 (+ u/t0 3) :geometry geometry))]
    (is (= [:dao.gui.event/invalid-recognizer] (diag-kinds outputs)))))


;; ---------------------------------------------------------------------------
;; Input profiles
;; ---------------------------------------------------------------------------

(deftest profile-installs-and-projects-in-numeric-order
  (let [booted (boot)
        {:keys [state outputs]} (step* booted
                                       (u/rt 3 (+ u/t0 3)
                                             :profile (u/input-profile
                                                        {:profile-id 4})))]
    (is (= [] outputs))
    (is (= [u/profile-id 4] (:profile-ids (project state))))))


(deftest duplicate-profile-id-diagnoses
  (let [booted (boot)
        {:keys [state outputs]}
        (step* booted
               (u/rt 3 (+ u/t0 3)
                     :profile (u/input-profile {:profile-id u/profile-id})))]
    (is (= [:dao.gui.event/profile-mismatch] (diag-kinds outputs)))
    (is (= [u/profile-id] (:profile-ids (project state))))))


(deftest regressing-profile-id-diagnoses-and-leaves-profiles-unchanged
  (let [booted (boot)
        ;; profile-id 3 is installed; 2 regresses below the latest id
        {:keys [state outputs]} (step* booted
                                       (u/rt 3 (+ u/t0 3)
                                             :profile (u/input-profile
                                                        {:profile-id 2})))]
    (is (= [:dao.gui.event/profile-mismatch] (diag-kinds outputs)))
    (is (= [u/profile-id] (:profile-ids (project state))))
    (is (= u/profile-id (:latest-profile-id state)))))


(deftest profile-boundaries-equal-duplicate-and-next
  (let [booted (boot)]
    (testing "equal id is the duplicate case"
      (let [{:keys [state outputs]} (step* booted
                                           (u/rt 3 (+ u/t0 3)
                                                 :profile (u/input-profile
                                                            {:profile-id
                                                             u/profile-id})))]
        (is (= [:dao.gui.event/profile-mismatch]
               (mapv :diagnostic/kind outputs)))
        (is (= [u/profile-id] (:profile-ids (project state))))))
    (testing "the next integer id installs and becomes latest"
      (let [{:keys [state outputs]}
            (step* booted
                   (u/rt 3 (+ u/t0 3)
                         :profile (u/input-profile {:profile-id
                                                    (inc u/profile-id)})))]
        (is (= [] outputs))
        (is (= [u/profile-id (inc u/profile-id)]
               (:profile-ids (project state))))
        (is (= (inc u/profile-id) (:latest-profile-id state)))))))


(deftest profile-rejects-stale-generation
  (let [booted (boot)
        {:keys [outputs]} (step* booted
                                 (u/rt 3 (+ u/t0 3)
                                       :profile (u/input-profile
                                                  {:generation-id "other",
                                                   :profile-id 4})))]
    (is (= [:dao.gui.event/stale-generation-input] (diag-kinds outputs)))))


;; ---------------------------------------------------------------------------
;; Terminal reset
;; ---------------------------------------------------------------------------

(deftest reset-clears-generation-scoped-state-and-keeps-subscriptions
  (let [booted (:state (step* (boot)
                              (u/rt 3 (+ u/t0 3)
                                    :subscription (u/sub-add "s1" ::save))))
        reset {:message/kind :dao.terminal/reset,
               :generation-id "fresh-generation"}
        {:keys [state outputs]} (step* booted
                                       (u/rt 4 (+ u/t0 4) :terminal reset))]
    (is (= [] outputs))
    (is (= {:generation-id "fresh-generation",
            :coordinate-space-id nil,
            :active-frame-id nil,
            :profile-ids [],
            :subscription-ids ["s1"],
            :active-pointer-ids [],
            :active-arena-ids [],
            :scheduled-timer-keys [],
            :last-runtime-seq 4,
            :last-runtime-time-us (+ u/t0 4),
            :next-arena-id 0}
           (project state)))
    (is (= {} (:coordinate-spaces state)))))


(deftest post-reset-old-generation-input-diagnoses-stale-generation
  (let [booted (boot)
        reset {:message/kind :dao.terminal/reset,
               :generation-id "fresh-generation"}
        after-reset (:state (step* booted (u/rt 4 (+ u/t0 4) :terminal reset)))
        {:keys [outputs]} (step* after-reset
                                 (u/rt 5 (+ u/t0 5)
                                       :profile (u/input-profile {:profile-id
                                                                  9})))]
    (is (= [:dao.gui.event/stale-generation-input] (diag-kinds outputs)))))


;; ---------------------------------------------------------------------------
;; Ignored terminal accounting signals
;; ---------------------------------------------------------------------------

(deftest rejection-and-skip-signals-leave-state-intact
  (let [booted (boot)
        durable (fn [p] (dissoc p :last-runtime-seq :last-runtime-time-us))
        before (durable (project booted))
        signals [{:message/kind :dao.terminal/rejection,
                  :submission-id 7,
                  :reason :bad}
                 {:message/kind :dao.terminal/frame-skipped, :submission-id 8}]
        after (reduce (fn [state [i signal]]
                        (:state
                          (step* state
                                 (u/rt (+ 3 i) (+ u/t0 3 i) :terminal signal))))
                      booted
                      (map-indexed vector signals))]
    (is (= before (durable (project after))))))
