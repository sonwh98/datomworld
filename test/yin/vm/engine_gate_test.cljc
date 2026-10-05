(ns yin.vm.engine-gate-test
  "D4: the gate on the engine's immediate stream effects (put, next, poll)
   and the observe/apply split. Counting handles show that a gated machine
   makes zero stream calls."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.module :as module]
            [yin.vm.test-utils :as tu]))


(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


(defn- counting-handle
  "A handle answering `read-outcome` to every `next`, with one counter for
   every call that reaches it."
  [calls read-outcome]
  (reify
    stream/IDaoStreamReader

    (cursor
      [_ _]
      (swap! calls inc)
      {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor ::at})

    (next
      [_ _]
      (swap! calls inc)
      read-outcome)


    stream/IDaoStreamWriter

    (append!
      [_ _]
      (swap! calls inc)
      {:dao.stream/outcome :dao.stream/ok})


    stream/IDaoStreamClosable

    (close!
      [_]
      (swap! calls inc)
      {:dao.stream/outcome :dao.stream/ok})))


(def ^:private park-entry-fns
  {:stream/put (fn [_s _e r] {:reason :put, :stream-id (:stream-id r)}),
   :stream/next (fn [_s _e r]
                  {:reason :next,
                   :cursor-ref (:cursor-ref r),
                   :stream-id (:stream-id r)})})


(def ^:private opts {:park-entry-fns park-entry-fns})


(defn- setup
  "[state stream-ref cursor-ref calls] over a counting handle; the cursor
   is minted before the counter is zeroed, so `calls` counts only what
   the test does."
  [read-outcome]
  (let [calls (atom 0)
        [sref s0] (engine/attach-resource
                    (vm/empty-state {:make-stream tu/make-stream,
                                     :capability-secret tu/secret})
                    (counting-handle calls read-outcome))
        [cref s1] (engine/handle-cursor s0 {:stream sref} :cursor-0)]
    (reset! calls 0)
    [s1 sref cref calls]))


(defn- effect
  [kind m]
  (module/make-effect kind m))


(defn- effects
  [sref cref]
  {:put (effect :stream/put {:stream sref, :val 1}),
   :next (effect :stream/next {:cursor cref}),
   :poll (effect :stream/poll {:cursor cref}),
   :close (effect :stream/close {:stream sref})})


(deftest gate-mode-reads-the-one-key-test
  (is (nil? (vm/gate-mode {})))
  (is (= :running (vm/gate-mode {:yin.k/gate :running})))
  (is (= :exporting (vm/gate-mode {:yin.k/gate :exporting})))
  (is (= :ended (vm/gate-mode {:yin.k/gate :ended}))))


(deftest running-gate-parks-every-immediate-with-zero-calls-test
  (let [[s sref cref calls] (setup {:dao.stream/outcome :dao.stream/ok,
                                    :dao.stream/value :v,
                                    :dao.stream/cursor ::next})
        s (assoc s :yin.k/gate :running)
        e (effects sref cref)]
    (testing "put parks as the writer it would be on full"
      (let [r (engine/handle-effect s (:put e) opts)]
        (is (true? (:blocked? r)))
        (is (= :put (:reason (first (:wait-set (:state r))))))
        (is (= 1 (:datom (first (:wait-set (:state r))))))))
    (testing "next parks as the reader it would be on blocked"
      (let [r (engine/handle-effect s (:next e) opts)
            entry (first (:wait-set (:state r)))]
        (is (true? (:blocked? r)))
        (is (= :next (:reason entry)))
        (is (= cref (:cursor-ref entry)))))
    (testing "poll parks as a machine-only :observe entry"
      (let [r (engine/handle-effect s (:poll e) opts)
            entry (first (:wait-set (:state r)))]
        (is (true? (:blocked? r)))
        (is (= :observe (:reason entry)))
        (is (= :poll (:op entry)))
        (is (= cref (:cursor-ref entry)))
        (is (= :stream-0 (:stream-id entry)))
        (is (not (contains? entry :yin.k/held)) "nothing held until observed")))
    (is (zero? @calls) "no stream call was made")))


(deftest closed-gates-observe-nothing-test
  (doseq [mode [:exporting :ended]]
    (let [[s sref cref calls] (setup {:dao.stream/outcome :dao.stream/ok,
                                      :dao.stream/value :v,
                                      :dao.stream/cursor ::next})
          s (assoc s :yin.k/gate mode)]
      (doseq [[kind eff] (effects sref cref)]
        (is (throws? #(engine/handle-effect s eff opts))
            (str kind " is refused in " mode)))
      (is (zero? @calls) (str "no stream call in " mode)))))


(deftest the-sweep-skips-observe-entries-test
  (let [[s sref cref calls] (setup {:dao.stream/outcome :dao.stream/ok,
                                    :dao.stream/value :v,
                                    :dao.stream/cursor ::next})
        parked (:state (engine/handle-effect (assoc s :yin.k/gate :running)
                                             ((effects sref cref) :poll)
                                             opts))
        swept (engine/check-wait-set (dissoc parked :yin.k/gate))]
    (is (zero? @calls) "even an ungated sweep never reads an :observe entry")
    (is (= 1 (count (:wait-set swept))))
    (is (empty? (:ready-queue swept)))))


(deftest apply-observation-matches-the-ungated-poll-test
  (doseq [[label outcome]
          [["ok" {:dao.stream/outcome :dao.stream/ok,
                  :dao.stream/value :v,
                  :dao.stream/cursor ::next}]
           ["blocked" {:dao.stream/outcome :dao.stream/blocked}]
           ["gap" {:dao.stream/outcome :dao.stream/gap,
                   :dao.stream/cursor ::recovered}]]]
    (testing label
      (let [[s sref cref _] (setup outcome)
            poll ((effects sref cref) :poll)
            ungated (engine/handle-effect s poll opts)
            parked (:state (engine/handle-effect (assoc s :yin.k/gate :running)
                                                 poll
                                                 opts))
            entry (first (:wait-set parked))
            applied (engine/apply-observation parked entry outcome)
            ready (first (:ready-queue applied))]
        (is (= (:value ungated) (:value ready)))
        (is (= (:resources (:state ungated)) (:resources applied))
            "the cursor cell advances exactly as the ungated path does")
        (is (empty? (:wait-set applied)))
        (is (= (:reason entry) :observe))))))


(deftest apply-observation-makes-no-stream-call-and-refuses-late-test
  (let [outcome {:dao.stream/outcome :dao.stream/ok,
                 :dao.stream/value :v,
                 :dao.stream/cursor ::next}
        [s sref cref calls] (setup outcome)
        parked (:state (engine/handle-effect (assoc s :yin.k/gate :running)
                                             ((effects sref cref) :poll)
                                             opts))
        entry (first (:wait-set parked))]
    (engine/apply-observation parked entry outcome)
    (is (zero? @calls) "apply performs no IO")
    (doseq [mode [:exporting :ended]]
      (is (throws? #(engine/apply-observation (assoc parked :yin.k/gate mode)
                                              entry
                                              outcome))
          (str "a late result is refused in " mode)))
    (is (throws? #(engine/apply-observation parked {:reason :next} outcome))
        "only an :observe entry is applied")))
