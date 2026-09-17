;; The DaoStream binding contract: advance consumes the canonical
;; runtime-input stream in order, routes reducer outputs to their seven
;; destination streams, parks on backpressure, and closes runtime-owned
;; outputs after teardown. Specification: docs/design/dao.gui.event.md
;; sections Event Boundary, Binding Contract, Transport Coalescing And
;; Backpressure.
(ns dao.gui.event.bind-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [dao.gui.event :as event]
            [dao.gui.event.scripted :as scripted]
            [dao.gui.event.util :as u]
            [dao.stream.v2 :as ds]
            [dao.stream.v2.ringbuffer :as rb]))


(def output-keys
  [:effects :trace :pointer :keyboard :gesture :dispatch :diagnostic])


(defn- ring
  "A v2 ring buffer handle of the given capacity."
  [capacity]
  (:dao.stream/handle
    (rb/create! {:dao.stream/type rb/transport-type,
                 rb/capacity-key capacity})))


(defn- streams
  [capacity]
  (zipmap output-keys
          (map (fn [_k] (ring capacity)) output-keys)))


(defn- oldest
  [stream]
  (:dao.stream/cursor (ds/cursor stream ds/anchor-oldest)))


(defn- newest
  [stream]
  (:dao.stream/cursor (ds/cursor stream ds/anchor-newest)))


(defn- drain
  "All currently retained values of one stream, non-destructively, from a
  freshly minted oldest cursor. A gap adopts the recovery cursor and keeps
  draining."
  [stream]
  (loop [cursor (oldest stream)
         acc []]
    (let [result (ds/next stream cursor)]
      (case (:dao.stream/outcome result)
        :dao.stream/ok (recur (:dao.stream/cursor result)
                              (conj acc (:dao.stream/value result)))
        :dao.stream/gap (recur (:dao.stream/cursor result) acc)
        acc))))


(def space-input
  (u/coordinate-space-change {:old-coordinate-space-id nil,
                              :coordinate-space-id u/space-id,
                              :width 390.0,
                              :height 844.0}))


(def tap-node ::binding-save)


(defn- boot-inputs
  "Coordinate space, presented geometry with one tap target, profile, and
  two tap subscriptions: one recognized gesture fans out to both."
  []
  [(u/rt 0 u/t0 :terminal space-input)
   (u/rt 1 u/t0 :geometry (u/presented-geometry {:node-id tap-node}))
   (u/rt 2 u/t0 :profile (u/input-profile {}))
   (u/rt 3 u/t0 :subscription (u/sub-add "s1" tap-node :tap))
   (u/rt 4 u/t0 :subscription (u/sub-add "s2" tap-node :tap))])


(defn- ptr
  [seq time-us phase id x y]
  (u/rt
    seq
    time-us
    :pointer
    (u/pointer-packet
      {:phase phase, :id id, :x x, :y y, :time-us time-us, :input-seq seq})))


(defn- tap-inputs
  []
  (into (boot-inputs)
        [(ptr 10 u/t0 :down 11 40.0 20.0)
         (ptr 11 (+ u/t0 50000) :up 11 41.0 21.0)]))


(defn- keyb
  [n k-seq time-us phase code focus-id]
  (let [logical (let [nm (name code)]
                  (if (string/starts-with? nm "key-") (subs nm 4) code))]
    (u/rt n
          time-us
          :keyboard
          {:input/kind :keyboard,
           :generation-id u/generation,
           :input-seq k-seq,
           :time-us time-us,
           :phase phase,
           :repeat? false,
           :modifiers #{},
           :key {:code code, :logical logical, :location :standard},
           :focus-id focus-id})))


(defn- keyboard-inputs
  []
  (into (boot-inputs)
        [(u/rt 10 u/t0 :subscription (u/sub-add "k1" tap-node :keyboard))
         (u/rt 11 (+ u/t0 1000)
               :subscription {:subscription/op :focus/set,
                              :focus/id ::editor,
                              :node-id tap-node})
         (keyb 12 0 (+ u/t0 2000) :down :key-a ::editor)
         (keyb 13 1 (+ u/t0 3000) :up :key-a ::editor)]))


(defn- make-binding
  [& {:keys [inputs outputs]}]
  (let [input-stream (ring 64)
        _ (doseq [input (or inputs (tap-inputs))]
            (ds/append! input-stream input))]
    {:binding (event/bind {:inputs {:runtime-input input-stream},
                           :outputs (or outputs (streams 64))}),
     :input input-stream}))


(defn- drive
  "Advance until status is in the terminal set or the call budget is spent.
  Returns [status advance-count final-binding]."
  ([binding]
   (drive binding #{:blocked :end :input-gap :transport-error :closed :parked}
          200))
  ([binding until] (drive binding until 200))
  ([binding until budget]
   (loop [binding binding
          n 0]
     (let [{next-binding :binding, status :status} (event/advance binding)]
       (if (or (contains? until status) (>= (inc n) budget))
         [status (inc n) next-binding]
         (recur next-binding (inc n)))))))


(defn- admit-first-only
  "The scripted admission that replaces a capacity-one ring buffer: the
  first append lands, every later one refuses until the script is
  released."
  []
  (fn [n] (if (< n 1) :dao.stream/ok :dao.stream/full)))


(deftest advance-routes-outputs-to-their-destination-streams
  (let [{:keys [binding input]} (make-binding)
        [status n final] (drive binding #{:blocked :end :input-gap
                                          :transport-error :closed})
        outputs (:outputs final)]
    (is (= :blocked status))
    ;; one advance per input plus the final blocking call
    (is (= 8 n))
    (is (= [:gesture] (mapv :event/kind (drain (:gesture outputs)))))
    (is (= [:recognized] (mapv :phase (drain (:gesture outputs)))))
    (is (= 2 (count (drain (:dispatch outputs)))))
    (is (= #{"s1" "s2"}
           (set (map :subscription/id (drain (:dispatch outputs))))))
    (is (= [:down :up] (mapv :phase (drain (:pointer outputs)))))
    (is (= [:dao.gui.event/contacts-changed :dao.gui.event/arena-decision]
           (mapv :event/kind (drain (:trace outputs)))))
    (is (= [] (drain (:effects outputs))))
    (is (= [] (drain (:diagnostic outputs))))
    ;; the input was consumed: the binding's cursor reads nothing new
    (is (= :dao.stream/blocked
           (:dao.stream/outcome (ds/next input (:cursor final)))))))


(deftest advance-consumes-at-most-one-input-per-call
  (let [{:keys [binding input]} (make-binding)
        result (event/advance binding)]
    (is (= :advanced (:status result)))
    ;; the second input is still unread at the cursor
    (is (= :geometry (:runtime/source
                       (:dao.stream/value
                         (ds/next input (:cursor (:binding result)))))))))


(deftest pending-output-flushes-before-the-next-input-read
  (let [pointer (scripted/scripted-output (admit-first-only))
        {:keys [binding]}
        (make-binding :outputs (assoc (streams 64)
                                      :pointer (:handle pointer))
                      :inputs (into
                                (boot-inputs)
                                [(ptr 10 u/t0 :down 11 40.0 20.0)
                                 (ptr 11 (+ u/t0 1000) :move 11 41.0 21.0)]))
        ;; five boot advances plus the down leave the scripted pointer
        ;; stream holding the down's raw event
        after-down (nth (iterate (fn [b] (:binding (event/advance b))) binding)
                        6)
        _ (is (= [:down] (mapv :phase (drain (:handle pointer)))))
        ;; the move steps, but its raw event cannot append: the binding
        ;; parks rather than dropping or reordering
        parked (event/advance after-down)]
    (is (= :parked (:status parked)))
    (is (= [:down] (mapv :phase (drain (:handle pointer)))))
    ;; the script releases: the retained value flushes before any new input
    (swap! (:script pointer) assoc :admit (constantly :dao.stream/ok))
    (let [resumed (event/advance (:binding parked))]
      (is (contains? #{:advanced :blocked} (:status resumed)))
      (is (= [:down :move] (mapv :phase (drain (:handle pointer))))))))


(deftest blocked-end-and-gap-are-reported-without-state-change
  (testing "an open empty input stream blocks"
    (let [{:keys [binding]} (make-binding :inputs [])
          result (event/advance binding)]
      (is (= :blocked (:status result)))))
  (testing "a closed empty input stream is :end, not teardown"
    (let [{:keys [binding input]} (make-binding :inputs [])
          _ (ds/close! input)
          result (event/advance binding)
          gesture (get-in binding [:outputs :gesture])]
      (is (= :end (:status result)))
      ;; outputs stay open: an open empty stream answers blocked, and
      ;; operation results are authoritative (there is no closed? ask)
      (is (= :dao.stream/blocked
             (:dao.stream/outcome (ds/next gesture (oldest gesture)))))
      ;; still drivable: :end repeats and outputs stay open
      (is (= :end (:status (event/advance (:binding result)))))))
  (testing "a bare gap is a transport failure without cursor advance"
    (let [input (ring 2)
          ;; the origin cursor is minted before any value exists, so the
          ;; binding observes from the stream's origin and the eviction of
          ;; the first two inputs is a reported gap
          origin (oldest input)
          _ (doseq [i (range 4)]
              (ds/append! input
                          (u/rt i
                                u/t0
                                :subscription
                                (u/sub-add (str "s" i) ::node :tap))))
          binding (event/bind {:inputs {:runtime-input input},
                               :outputs (streams 64),
                               :cursor origin})
          result (event/advance binding)]
      (is (= :input-gap (:status result)))
      ;; the result carries the transport's recovery cursor and the
      ;; binding's own cursor did not move past the evicted position
      (is (some? (:recovery-cursor result)))
      (is (= origin (get-in result [:binding :cursor])))))
  (testing "a transport-error on the input is reported without a re-read"
    (let [{:keys [handle reads]} (scripted/scripted-input
                                   :dao.stream/transport-error)
          binding (event/bind {:inputs {:runtime-input handle},
                               :outputs (streams 64)})
          result (event/advance binding)]
      (is (= :transport-error (:status result)))
      ;; one read, ever: the terminal outcome is not retried inside the
      ;; call, and the stopped binding does not read again
      (is (= 1 @reads))
      (let [again (event/advance (:binding result))]
        (is (= :transport-error (:status again))
            "a later advance surfaces the same terminal status")
        (is (= 1 @reads) "the stopped binding performs no further read")))))


(deftest a-late-binding-observes-the-retained-suffix-silently
  ;; the origin-cursor guarantee is a timing discipline: a binding whose
  ;; first advance happens after production and eviction has already
  ;; occurred mints oldest at the earliest retained position and observes
  ;; the suffix with no gap — nothing is wrong with that cursor
  (let [input (ring 1)
        _ (ds/append! input
                      (u/rt 0 u/t0 :subscription (u/sub-add "s0" ::node :tap)))
        _ (ds/append! input
                      (u/rt 1 u/t0 :subscription (u/sub-add "s1" ::node :tap)))
        binding (event/bind {:inputs {:runtime-input input},
                             :outputs (streams 64)})
        first-result (event/advance binding)
        [status _n final] (drive (:binding first-result))]
    (is (= :advanced (:status first-result))
        "no cursor exists to compare against yet, so no gap is reportable")
    (is (= :blocked status))
    ;; only the retained s1 was observed; the evicted s0 was missed
    ;; silently — pinning why the guarantee needs a pre-production cursor
    (is (= ["s1"] (get-in final [:state :subscription-order])))))


(deftest an-early-bind-still-misses-eviction-before-the-first-advance
  ;; the exact counterexample to "created before the input stream has any
  ;; value": binding early does not mint anything — only the first advance
  ;; mints, and it mints against whatever the stream holds at that moment
  (let [input (ring 1)
        binding (event/bind {:inputs {:runtime-input input},
                             :outputs (streams 64)})
        _ (ds/append! input
                      (u/rt 0 u/t0 :subscription (u/sub-add "s0" ::node :tap)))
        _ (ds/append! input
                      (u/rt 1 u/t0 :subscription (u/sub-add "s1" ::node :tap)))
        first-result (event/advance binding)
        [status _n final] (drive (:binding first-result))]
    (is (= :advanced (:status first-result))
        "no cursor exists to compare against yet, so no gap is reportable")
    (is (= :blocked status))
    (is (= ["s1"] (get-in final [:state :subscription-order]))
        "the early bind did not protect s0; only a pre-production advance would")))


(deftest recover-input-gap-resumes-at-the-next-retained-input
  (let [input (ring 2)
        origin (oldest input)
        _ (doseq [i (range 4)]
            (ds/append! input
                        (u/rt i u/t0 :subscription (u/sub-add (str "s" i)
                                                              ::node :tap))))
        binding (event/bind {:inputs {:runtime-input input},
                             :outputs (streams 64),
                             :cursor origin})
        gap-result (event/advance binding)
        recovered (event/recover-input-gap
                    (:binding gap-result)
                    (:recovery-cursor gap-result)
                    {:runtime/seq 4,
                     :runtime/time-us u/t0,
                     :runtime/source :terminal,
                     :runtime/value {:message/kind :dao.terminal/input-loss,
                                     :reason :stream-capacity}})]
    (is (= :input-gap (:status gap-result)))
    ;; the recovery cursor the outcome carried becomes the binding cursor
    (is (= (:recovery-cursor gap-result) (:cursor recovered)))
    (let [[status _n final] (drive recovered)]
      ;; s2 and s3 — the retained suffix — follow the envelope
      (is (= :blocked status))
      (is (= ["s2" "s3"] (get-in final [:state :subscription-order]))))))


(deftest full-dispatch-parks-once-per-interval-with-one-diagnostic
  (let [dispatch (scripted/scripted-output (admit-first-only))
        {:keys [binding]} (make-binding
                            :outputs (assoc (streams 64)
                                            :dispatch (:handle dispatch))
                            :inputs (tap-inputs))
        ;; five boot inputs plus the down advance cleanly; the up produces
        ;; two dispatch values: the first appends, the second parks
        [status _n parked] (drive binding)
        _ (is (= :parked status))
        ;; the cursor advanced past the already-stepped up input
        _ (is (= 7 (:dao.stream.ringbuffer/position (:cursor parked))))
        ;; exactly one dispatch was appended before the stream refused
        _ (is (= ["s1"] (mapv :subscription/id (drain (:handle dispatch)))))
        ;; repeated advance on the same parked head does not duplicate output
        _ (is (= :parked (:status (event/advance parked))))
        _ (is (= ["s1"] (mapv :subscription/id (drain (:handle dispatch)))))
        ;; release the script and drive: the pending dispatch flushes, the
        ;; backpressure diagnostic lands on the diagnostic stream
        _ (swap! (:script dispatch) assoc :admit (constantly :dao.stream/ok))
        [final-status _n final]
        (drive parked #{:blocked :end :input-gap :transport-error :closed
                        :parked})]
    (is (= :blocked final-status))
    ;; each dispatch value appears exactly once
    (is (= ["s1" "s2"] (mapv :subscription/id (drain (:handle dispatch)))))
    (is (= [:dao.gui.event/dispatch-backpressure]
           (mapv :diagnostic/kind
                 (drain (get-in final [:outputs :diagnostic]))))
        "exactly one diagnostic for the parked interval")
    (is (= 7 (:dao.stream.ringbuffer/position (:cursor final))))))


(deftest a-full-diagnostic-stream-retains-the-diagnostic-without-recursion
  (let [dispatch (scripted/scripted-output (admit-first-only))
        ;; a diagnostic lane that refuses everything replaces capacity zero
        diagnostic (scripted/scripted-output (constantly :dao.stream/full))
        {:keys [binding]}
        (make-binding :outputs (assoc (streams 64)
                                      :dispatch (:handle dispatch)
                                      :diagnostic (:handle diagnostic))
                      :inputs (tap-inputs))
        [status _n parked] (drive binding)]
    (is (= :parked status))
    (is (= ["s1"] (mapv :subscription/id (drain (:handle dispatch)))))
    ;; the diagnostic cannot be appended either: the binding parks on it
    ;; without producing another backpressure diagnostic
    (swap! (:script dispatch) assoc :admit (constantly :dao.stream/ok))
    (let [[status-2] (drive parked)]
      (is (= :parked status-2))
      ;; the refused dispatch flushed once the script released it; the
      ;; backpressure diagnostic itself could not append and parks
      ;; without recursion
      (is (= ["s1" "s2"] (mapv :subscription/id (drain (:handle dispatch)))))
      (is (= [] (drain (:handle diagnostic)))))))


(deftest a-gone-output-drops-its-pending-value-without-retry
  ;; closed, invalid-value, and transport-error on an output mean the
  ;; output is gone: the pending value is dropped with one diagnostic, not
  ;; parked on and not retried
  (let [gesture (scripted/scripted-output (constantly :dao.stream/closed))
        {:keys [binding]}
        (make-binding :outputs (assoc (streams 64)
                                      :gesture (:handle gesture))
                      :inputs (tap-inputs))
        [status _n final] (drive binding)]
    (is (= :blocked status))
    ;; the gesture never parked the binding and nothing was retained
    (is (= [] (drain (:handle gesture))))
    (is (= 7 (:dao.stream.ringbuffer/position (:cursor final))))))


(deftest teardown-closes-all-seven-outputs-but-not-the-input
  (let [{:keys [binding input]}
        (make-binding :inputs
                      (into (boot-inputs)
                            [(ptr 10 u/t0 :down 11 40.0 20.0)
                             (u/rt 20 (+ u/t0 60000) :control (u/teardown))]))
        [status _n final] (drive binding)
        outputs (:outputs final)]
    (is (= :closed status))
    ;; closed, not gone: every output answers end at its tail where an
    ;; open stream would answer blocked (there is no closed? predicate)
    (doseq [k output-keys]
      (is (= :dao.stream/end
             (:dao.stream/outcome
               (ds/next (get outputs k) (newest (get outputs k)))))
          (str k " closed")))
    ;; the input stays open: it answers blocked at its tail
    (is (= :dao.stream/blocked
           (:dao.stream/outcome (ds/next input (newest input)))))
    ;; teardown cancelled the live arena: its cancellation outputs flushed
    (is (pos? (count (drain (get outputs :trace)))))
    ;; post-close advance is :closed and a later input is ignored
    (ds/append! input
                (u/rt 21 (+ u/t0 70000)
                      :subscription (u/sub-add "late" tap-node :tap)))
    (is (= :closed (:status (event/advance final))))))


(deftest teardown-with-parked-output-closes-only-after-the-final-value
  (let [pan-decl
        (assoc-in (u/pan-decl tap-node) [:config :contacts] {:min 1, :max 1})
        cancel-sub (fn [id]
                     (assoc (u/sub-add id tap-node :pan)
                            :gesture/phases #{:cancel}))
        inputs (into [(u/rt 0 u/t0 :terminal space-input)
                      (u/rt 1 u/t0
                            :geometry (u/presented-geometry {:node-id tap-node,
                                                             :recognizers
                                                             [pan-decl]}))
                      (u/rt 2 u/t0 :profile (u/input-profile {}))
                      (u/rt 3 u/t0 :subscription (cancel-sub "c1"))
                      (u/rt 4 u/t0 :subscription (cancel-sub "c2"))
                      (ptr 10 u/t0 :down 11 40.0 20.0)
                      ;; pan accepts: its :start matches no subscription
                      (ptr 11 (+ u/t0 1000) :move 11 80.0 20.0)
                      (u/rt 20 (+ u/t0 60000) :control (u/teardown))]
                     [])
        dispatch (scripted/scripted-output (admit-first-only))
        {:keys [binding]} (make-binding :outputs (assoc (streams 64)
                                                        :dispatch
                                                        (:handle dispatch))
                                        :inputs inputs)
        ;; the teardown cancels the accepted pan: the cancel gesture and
        ;; its two phase-filtered dispatches flush; the second dispatch
        ;; parks
        [status _n parked] (drive binding)]
    (is (= :parked status))
    ;; the dispatch output is not closed while a value is still pending
    (is (= :dao.stream/blocked
           (:dao.stream/outcome
             (ds/next (:handle dispatch) (newest (:handle dispatch))))))
    (swap! (:script dispatch) assoc :admit (constantly :dao.stream/ok))
    (let [[status-2 _n final] (drive parked)]
      (is (= :closed status-2))
      (is (= :dao.stream/end
             (:dao.stream/outcome
               (ds/next (:handle dispatch) (newest (:handle dispatch))))))
      ;; both cancellation dispatches flushed before the close
      (is (= ["c1" "c2"]
             (mapv :subscription/id (drain (:handle dispatch))))))))


(deftest nil-destination-streams-are-permissive-sinks
  ;; an embedding may omit physical streams: the binding still advances
  (let [input (ring 16)
        _ (doseq [i (tap-inputs)] (ds/append! input i))
        binding (event/bind {:inputs {:runtime-input input},
                             :outputs {:effects nil,
                                       :trace nil,
                                       :pointer nil,
                                       :gesture nil,
                                       :dispatch nil,
                                       :keyboard nil,
                                       :diagnostic nil}})
        [status n] (drive binding #{:blocked :end :input-gap :transport-error
                                    :closed})]
    (is (= :blocked status))
    (is (= 8 n))))


(deftest advance-routes-keyboard-outputs-to-their-destination-streams
  (let [{:keys [binding]} (make-binding :inputs (keyboard-inputs))
        [status n final] (drive binding #{:blocked :end :input-gap
                                          :transport-error :closed})
        outputs (:outputs final)]
    (is (= :blocked status))
    (is (= 10 n)) ; 5 boot + 2 subs + 2 keyb = 9 inputs => 10 advances
    (is (= [:down :up] (mapv :event/phase (drain (:keyboard outputs)))))
    (is (= 2 (count (drain (:dispatch outputs)))))
    (is (= #{"k1"} (set (map :subscription/id (drain (:dispatch outputs))))))
    (is (= [{:effect/kind :dao.gui.event/focus-request,
             :operation :set,
             :focus-id ::editor,
             :node-id tap-node,
             :generation-id u/generation}]
           (map #(select-keys %
                              [:effect/kind :operation :focus-id :node-id
                               :generation-id])
                (drain (:effects outputs)))))
    (is (= [] (drain (:diagnostic outputs))))))


(deftest keyboard-backpressure-parks-stream
  (let [keyboard (scripted/scripted-output (admit-first-only))
        {:keys [binding]} (make-binding :outputs (assoc (streams 64)
                                                        :keyboard
                                                        (:handle keyboard))
                                        :inputs (keyboard-inputs))
        [status _n parked] (drive binding)]
    (is (= :parked status))
    (is (= [:down] (mapv :event/phase (drain (:handle keyboard)))))
    (swap! (:script keyboard) assoc :admit (constantly :dao.stream/ok))
    (let [[final-status] (drive parked)]
      (is (= :blocked final-status))
      (is (= [:down :up] (mapv :event/phase (drain (:handle keyboard))))))))
