;; Executable fixture contract: EDN fixtures replay through the total
;; reducer; expected outputs compare with canonical 1e-6 rounding and the
;; eleven-key state projection must match exactly. Also: replay determinism
;; and a deterministic generated-trace invariant check. Specification:
;; docs/design/dao.gui.event.md sections Trace And Numeric Conformance and
;; Executable Fixture Contract.
(ns dao.gui.event.fixture-test
  (:require [clojure.test :refer [deftest is testing]]
            #?@(:clj [[clojure.edn :as edn] [clojure.string :as str]])
            [dao.gui.event :as event]
            [dao.gui.event.trace :as trace]
            [dao.gui.event.util :as u]))


#_:clj-kondo/ignore


(defn- load-fixture
  [path]
  #?(:clj (edn/read-string (slurp path))
     :cljs (throw (ex-info "fixture loading is JVM-only" {}))))


#?(:clj (defn- fixture-files
          []
          (->> (file-seq (java.io.File. "test/dao/gui/event/fixtures"))
               (filter #(str/ends-with? % ".edn"))
               (sort-by str))))


(deftest fixtures-replay-to-their-expectations
  #?(:clj (doseq [file (fixture-files)]
            (let [fixture (load-fixture (str file))
                  initial (if (nil? (:initial-state fixture))
                            (event/initial-state)
                            (:initial-state fixture))
                  {:keys [state outputs]} (event/replay initial
                                                        (:inputs fixture))
                  expected-outputs (get-in fixture [:expect :outputs])
                  expected-state (get-in fixture [:expect :state])]
              (testing (str (:fixture/id fixture) " outputs")
                (is (= (count expected-outputs) (count outputs))
                    (str "output count mismatch for " (:fixture/id fixture)))
                (doseq [[i [expected actual]]
                        (map-indexed vector
                                     (map vector expected-outputs outputs))]
                  (is (trace/matches? expected actual)
                      (str "output " i
                           " mismatch for " (:fixture/id fixture)
                           "\nexpected: " (pr-str expected)
                           "\nactual:   " (pr-str actual)))))
              (testing (str (:fixture/id fixture) " state")
                (is (= expected-state (event/fixture-projection state))))))
     :default nil))


(deftest fixture-replay-is-exactly-deterministic
  #?(:clj (doseq [file (fixture-files)]
            (let [fixture (load-fixture (str file))
                  run (fn []
                        (event/replay (event/initial-state) (:inputs fixture)))
                  run-1 (run)
                  run-2 (run)]
              (is (= (trace/round-deep (:outputs run-1))
                     (trace/round-deep (:outputs run-2))))
              (is (= (:state run-1) (:state run-2)))))
     :default nil))


;; ---------------------------------------------------------------------------
;; Deterministic generated trace invariants
;; ---------------------------------------------------------------------------

(defn- lcg
  "A deterministic linear congruential generator: (lcg seed) => [value
  next-seed]."
  [seed]
  (let [next (mod (+ (* seed 1103515245) 12345) 2147483648)]
    [(long (mod next 100)) next]))


(defn- generate-trace
  "A finite deterministic packet trace with up to five contacts: downs,
  moves within a bounded band, and terminal packets."
  [seed steps]
  (loop [seed seed
         step 0
         active []
         input-seq 100
         time-us u/t0
         packets []]
    (if (= step steps)
      {:packets packets, :final-active active}
      (let [[pick seed-1] (lcg seed)
            [dx seed-2] (lcg seed-1)
            [dy seed-3] (lcg seed-2)
            [phase-pick seed-4] (lcg seed-3)
            id (inc (mod pick 5))]
        (cond
          ;; down when the contact is not active, else move/up/cancel
          (and (not (some #{id} active)) (< phase-pick 40))
          (recur seed-4
                 (inc step)
                 (conj active id)
                 (inc input-seq)
                 (+ time-us 10000)
                 (conj packets
                       {:phase :down,
                        :id id,
                        :x (+ 30.0 (* 5 id) (mod dx 10)),
                        :y (+ 20.0 (mod dy 10)),
                        :input-seq input-seq,
                        :time-us (+ time-us 10000)}))
          (and (some #{id} active) (>= phase-pick 40) (< phase-pick 80))
          (recur seed-4
                 (inc step)
                 active
                 (inc input-seq)
                 (+ time-us 10000)
                 (conj packets
                       {:phase :move,
                        :id id,
                        :x (+ 30.0 (* 5 id) (mod dx 60)),
                        :y (+ 20.0 (mod dy 60)),
                        :input-seq input-seq,
                        :time-us (+ time-us 10000)}))
          (some #{id} active)
          (recur seed-4
                 (inc step)
                 (vec (remove #{id} active))
                 (inc input-seq)
                 (+ time-us 10000)
                 (conj packets
                       {:phase (if (even? phase-pick) :up :cancel),
                        :id id,
                        :x (+ 30.0 (* 5 id) (mod dx 60)),
                        :y (+ 20.0 (mod dy 60)),
                        :input-seq input-seq,
                        :time-us (+ time-us 10000)}))
          :else (recur seed-4
                       (inc step)
                       active
                       (inc input-seq)
                       time-us
                       packets))))))


(def pan-node :dao.gui.event.fixture-test/surface)


(defn- pan-boot
  []
  (let [decl {:recognizer/id [pan-node :pan],
              :gesture/kind :pan,
              :machine :dao.gui.event/pan,
              :config {:contacts {:min 1, :max 5},
                       :axis :free,
                       :start-at :slop,
                       :contact-loss :end},
              :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}
        geometry (u/presented-geometry {:node-id pan-node,
                                        :recognizers [decl],
                                        :x 0,
                                        :y 0,
                                        :width 390,
                                        :height 844})
        space (u/coordinate-space-change {:old-coordinate-space-id nil,
                                          :coordinate-space-id u/space-id,
                                          :width 390.0,
                                          :height 844.0})
        s1 (:state (event/step (event/initial-state)
                               (u/rt 0 u/t0 :terminal space)))
        s2 (:state (event/step s1 (u/rt 1 u/t0 :geometry geometry)))
        s3 (:state (event/step s2 (u/rt 2 u/t0 :profile (u/input-profile {}))))]
    s3))


(deftest generated-traces-satisfy-structural-invariants
  (doseq [seed (range 8)]
    (let [{:keys [packets final-active]} (generate-trace (+ seed 7) 120)
          inputs (map-indexed
                   (fn [i p]
                     (u/rt (+ i 10) (:time-us p) :pointer (u/pointer-packet p)))
                   packets)
          {:keys [state outputs]} (event/replay (pan-boot) inputs)
          gestures (filter #(= :gesture (:event/kind %)) outputs)
          starts (filter #(= :start (:phase %)) gestures)
          ends (filter #(contains? #{:end :cancel} (:phase %)) gestures)
          timer-fires (filter #(= :dao.gui.event/timer-fired (:input/kind %))
                              (map :runtime/value inputs))
          timer-starts (filter #(= :start (:timer/op %))
                               (filter :effect/kind outputs)
                               #_(filter :effect/kind outputs))]
      (testing (str "seed " seed)
        ;; every emitted gesture references an origin capture
        (is (every? #(int? (:arena-id %)) gestures))
        ;; every continuous start has exactly one later end or cancel when
        ;; every contact terminated within the trace
        (when (empty? final-active)
          (is (every? (fn [start]
                        (= 1
                           (count (filter #(and (= (:gesture/id %)
                                                   (:gesture/id start))
                                                (contains? #{:end :cancel}
                                                           (:phase %)))
                                          ends))))
                      starts)
              (str "unterminated start among " (count starts) " starts")))
        ;; every timer-fired value has a prior start of the same key
        (is (every? (fn [fired]
                      (some #(and (= (:arena-id fired) (:arena-id %))
                                  (= (:recognizer/id fired) (:recognizer/id %))
                                  (= (:timer-id fired) (:timer-id %))
                                  (= (:timer-seq fired) (:timer-seq %)))
                            timer-starts))
                    timer-fires))
        ;; no ended arena or pointer remains in the projected state once
        ;; every contact has terminated
        (when (empty? final-active)
          (is (= [] (:active-arena-ids (event/fixture-projection state)))))
        ;; replaying the same trace is exactly deterministic
        (let [again (event/replay (pan-boot) inputs)]
          (is (= (trace/round-deep (:outputs again))
                 (trace/round-deep outputs))))))))
