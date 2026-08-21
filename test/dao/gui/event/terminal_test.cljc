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
  (let [geometry (update
                   (u/presented-geometry {:frame-id 43})
                   :nodes
                   (fn [[node]]
                     [(update node
                              :regions
                              conj
                              {:bounds {:x 0, :y 0, :width -5, :height 10},
                               :paint-order 101})
                      (update node
                              :regions
                              conj
                              {:bounds {:x "left", :y 0, :width 5, :height 10},
                               :paint-order 102})]))
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
