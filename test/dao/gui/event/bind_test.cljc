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
            [dao.gui.event.util :as u]
            [dao.stream :as ds]
            [dao.stream.ringbuffer :as rb]))


(def output-keys
  [:effects :trace :pointer :keyboard :gesture :dispatch :diagnostic])


(defn- streams
  [capacity]
  (zipmap output-keys
          (map (fn [_k] (rb/make-ring-buffer-stream capacity)) output-keys)))


(defn- drain
  "All currently retained values of one stream, non-destructively.
  Positions already consumed by drain-one! are skipped as gaps."
  [stream]
  (loop [pos 0
         acc []]
    (let [result (ds/next stream {:position pos})]
      (cond (map? result) (recur (inc pos) (conj acc (:ok result)))
            (= result :daostream/gap) (recur (inc pos) acc)
            :else acc))))


(defn- consume-count
  "Free n slots by destructively draining one stream."
  [stream n]
  (dotimes [_i n] (ds/drain-one! stream)))


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
  [& {:keys [output-capacities inputs]}]
  (let [caps (or output-capacities {})
        input-stream (rb/make-ring-buffer-stream 64)
        _ (doseq [input (or inputs (tap-inputs))]
            (ds/append! input-stream input))]
    {:binding (event/bind {:inputs {:runtime-input input-stream},
                           :outputs (zipmap output-keys
                                            (map (fn [k]
                                                   (rb/make-ring-buffer-stream
                                                     (get caps k 64)))
                                                 output-keys))}),
     :input input-stream}))


(defn- drive
  "Advance until status is in the terminal set or the call budget is spent.
  Returns [status advance-count final-binding]."
  ([binding] (drive binding #{:blocked :end :input-gap :closed :parked} 200))
  ([binding until] (drive binding until 200))
  ([binding until budget]
   (loop [binding binding
          n 0]
     (let [{next-binding :binding, status :status} (event/advance binding)]
       (if (or (contains? until status) (>= (inc n) budget))
         [status (inc n) next-binding]
         (recur next-binding (inc n)))))))


(deftest advance-routes-outputs-to-their-destination-streams
  (let [{:keys [binding input]} (make-binding)
        [status n final] (drive binding #{:blocked :end :input-gap :closed})
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
    ;; the input was consumed: the cursor is past every value
    (is (= :blocked (ds/next input {:position 7})))))


(deftest advance-consumes-at-most-one-input-per-call
  (let [{:keys [binding input]} (make-binding)
        result (event/advance binding)]
    (is (= :advanced (:status result)))
    ;; the second input is still unread at the cursor
    (is (= :geometry (:runtime/source (:ok (ds/next input {:position 1})))))))


(deftest pending-output-flushes-before-the-next-input-read
  (let [{:keys [binding]}
        (make-binding :output-capacities {:pointer 1}
                      :inputs (into
                                (boot-inputs)
                                [(ptr 10 u/t0 :down 11 40.0 20.0)
                                 (ptr 11 (+ u/t0 1000) :move 11 41.0 21.0)]))
        ;; five boot advances plus the down leave the capacity-one pointer
        ;; stream holding the down's raw event
        after-down (nth (iterate (fn [b] (:binding (event/advance b))) binding)
                        6)
        outputs (:outputs after-down)
        _ (is (= 1 (count (drain (:pointer outputs)))))
        ;; the move steps, but its raw event cannot append: the binding
        ;; parks rather than dropping or reordering
        parked (event/advance after-down)]
    (is (= :parked (:status parked)))
    (is (= 1 (count (drain (:pointer outputs)))))
    ;; space appears: the retained value flushes before any new input
    (consume-count (:pointer outputs) 1)
    (let [resumed (event/advance (:binding parked))]
      (is (contains? #{:advanced :blocked} (:status resumed)))
      (is (= [:move] (mapv :phase (drain (:pointer outputs))))))))


(deftest blocked-end-and-gap-are-reported-without-state-change
  (testing "an open empty input stream blocks"
    (let [{:keys [binding]} (make-binding :inputs [])
          result (event/advance binding)]
      (is (= :blocked (:status result)))))
  (testing "a closed empty input stream is :end, not teardown"
    (let [{:keys [binding input]} (make-binding :inputs [])
          _ (ds/close! input)
          result (event/advance binding)]
      (is (= :end (:status result)))
      (is (not (ds/closed? (get-in binding [:outputs :gesture]))))
      ;; still drivable: :end repeats and outputs stay open
      (is (= :end (:status (event/advance (:binding result)))))))
  (testing "a bare gap is a transport failure without cursor advance"
    (let [input (ds/open! {:dao.stream/type :ringbuffer,
                           :capacity 2,
                           :eviction-policy :evict-oldest})
          _ (doseq [i (range 4)]
              (ds/append! input
                          (u/rt i
                                u/t0
                                :subscription
                                (u/sub-add (str "s" i) ::node :tap))))
          binding (event/bind {:inputs {:runtime-input input},
                               :outputs (streams 64)})
          result (event/advance binding)]
      (is (= :input-gap (:status result)))
      ;; the cursor did not move past the evicted position
      (is (= 0 (get-in (:binding result) [:cursor :position]))))))


(deftest full-dispatch-parks-once-per-interval-with-one-diagnostic
  (let [{:keys [binding]} (make-binding :output-capacities {:dispatch 1}
                                        :inputs (tap-inputs))
        ;; five boot inputs plus the down advance cleanly; the up produces
        ;; two dispatch values: the first appends, the second parks
        [status _n parked] (drive binding)
        outputs (:outputs parked)]
    (is (= :parked status))
    ;; the cursor advanced past the already-stepped up input
    (is (= 7 (:position (:cursor parked))))
    ;; exactly one dispatch was appended before the stream filled
    (is (= 1 (count (drain (:dispatch outputs)))))
    ;; repeated advance on the same parked head does not duplicate output
    (is (= :parked (:status (event/advance parked))))
    (is (= 1 (count (drain (:dispatch outputs)))))
    ;; free one slot and drive: the pending dispatch flushes, the
    ;; backpressure diagnostic lands on the diagnostic stream
    (consume-count (:dispatch outputs) 1)
    (let [[final-status _n final]
          (drive parked #{:blocked :end :input-gap :closed :parked})]
      (is (= :blocked final-status))
      ;; the consumed first dispatch is gone; the retained queue flushed
      ;; the second exactly once
      (is (= ["s2"] (mapv :subscription/id (drain (:dispatch outputs)))))
      (is (= [:dao.gui.event/dispatch-backpressure]
             (mapv :diagnostic/kind (drain (:diagnostic outputs))))
          "exactly one diagnostic for the parked interval")
      (is (= 7 (:position (:cursor final)))))))


(deftest a-full-diagnostic-stream-retains-the-diagnostic-without-recursion
  (let [{:keys [binding]} (make-binding
                            ;; capacity zero: every append is full
                            :output-capacities {:dispatch 1, :diagnostic 0}
                            :inputs (tap-inputs))
        [status _n parked] (drive binding)
        outputs (:outputs parked)]
    (is (= :parked status))
    (is (= 1 (count (drain (:dispatch outputs)))))
    ;; the diagnostic cannot be appended either: the binding parks on it
    ;; without producing another backpressure diagnostic
    (consume-count (:dispatch outputs) 1)
    (let [[status-2] (drive parked)]
      (is (= :parked status-2))
      ;; the retained dispatch flushed once space existed; the backpressure
      ;; diagnostic itself could not append and parks without recursion
      (is (= ["s2"] (mapv :subscription/id (drain (:dispatch outputs))))))))


(deftest teardown-closes-all-seven-outputs-but-not-the-input
  (let [{:keys [binding input]}
        (make-binding :inputs
                      (into (boot-inputs)
                            [(ptr 10 u/t0 :down 11 40.0 20.0)
                             (u/rt 20 (+ u/t0 60000) :control (u/teardown))]))
        [status _n final] (drive binding)
        outputs (:outputs final)]
    (is (= :closed status))
    (doseq [k output-keys] (is (ds/closed? (get outputs k)) (str k " closed")))
    (is (not (ds/closed? input)))
    ;; teardown cancelled the live arena: its cancellation outputs flushed
    (is (pos? (count (drain (:trace outputs)))))
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
        {:keys [binding]} (make-binding :output-capacities {:dispatch 1}
                                        :inputs inputs)
        ;; the teardown cancels the accepted pan: the cancel gesture and
        ;; its two phase-filtered dispatches flush; the second dispatch
        ;; parks
        [status _n parked] (drive binding)
        outputs (:outputs parked)]
    (is (= :parked status))
    (is (not (ds/closed? (:dispatch outputs))))
    (consume-count (:dispatch outputs) 1)
    (let [[status-2] (drive parked)]
      (is (= :closed status-2))
      (is (ds/closed? (:dispatch outputs)))
      ;; the second cancellation dispatch flushed before the close
      (is (= ["c2"] (mapv :subscription/id (drain (:dispatch outputs))))))))


(deftest nil-destination-streams-are-permissive-sinks
  ;; an embedding may omit physical streams: the binding still advances
  (let [input (rb/make-ring-buffer-stream 16)
        _ (doseq [i (tap-inputs)] (ds/append! input i))
        binding (event/bind {:inputs {:runtime-input input},
                             :outputs {:effects nil,
                                       :trace nil,
                                       :pointer nil,
                                       :gesture nil,
                                       :dispatch nil,
                                       :diagnostic nil}})
        [status n] (drive binding #{:blocked :end :input-gap :closed})]
    (is (= :blocked status))
    (is (= 8 n))))


(deftest advance-routes-keyboard-outputs-to-their-destination-streams
  (let [{:keys [binding]} (make-binding :inputs (keyboard-inputs))
        [status n final] (drive binding #{:blocked :end :input-gap :closed})
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
  (let [{:keys [binding]} (make-binding :output-capacities {:keyboard 1}
                                        :inputs (keyboard-inputs))
        [status _n parked] (drive binding)
        outputs (:outputs parked)]
    (is (= :parked status))
    (is (= 1 (count (drain (:keyboard outputs)))))
    (consume-count (:keyboard outputs) 1)
    (let [[final-status] (drive parked
                                #{:blocked :end :input-gap :closed :parked})]
      (is (= :blocked final-status))
      (is (= [:up] (mapv :event/phase (drain (:keyboard outputs))))))))
