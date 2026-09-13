(ns dao.stream.v2.observer-test
  "Generic observer tests: a fake unary attacher and a hand-rolled reader.

   No transport namespace is required here, because the observer's contract
   ends at `dao.stream.v2`. The ring-buffer realization is exercised by the
   ast-walker and REPL suites, which compose the observer over
   `dao.stream.v2.ringbuffer`."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.observer :as observer]))


(defn- throws-ex-data
  "Evaluate thunk; return its ex-data, or nil when it did not throw."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (ex-data e))))


(defn- thrown
  "Evaluate thunk; return the exception it threw, or nil when it did not."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         e)))


(defn- reader-medium
  "A reader-surface medium over an atom of values.

   `next` answers `ok` for positions at or above the eviction floor, `gap`
   below the floor with the floor as the recovery cursor, `end` at the tail
   once closed, and `blocked` at the tail while open. The writer and closable
   surfaces record their calls so a test can prove the observer never performs
   them. Returns `{:handle h :evict! f :close-medium! f :used atom}`."
  [& [values]]
  (let [data (atom {:values (vec (or values [])), :floor 0, :closed? false})
        used (atom [])]
    {:handle
     (reify
       stream/IDaoStreamReader
       (cursor
         [_ anchor]
         (if (= :dao.stream/oldest anchor)
           {:dao.stream/outcome :dao.stream/ok,
            :dao.stream/cursor {:pos (:floor @data)}}
           {:dao.stream/outcome :dao.stream/invalid-anchor}))

       (next
         [_ cursor]
         (let [{:keys [values floor closed?]} @data
               pos (:pos cursor)]
           (cond
             (not (map? cursor))
             {:dao.stream/outcome :dao.stream/invalid-cursor}

             (< pos floor)
             {:dao.stream/outcome :dao.stream/gap,
              :dao.stream/cursor {:pos floor}}

             (< pos (count values))
             {:dao.stream/outcome :dao.stream/ok,
              :dao.stream/value (nth values pos),
              :dao.stream/cursor {:pos (inc pos)}}

             closed? {:dao.stream/outcome :dao.stream/end}
             :else {:dao.stream/outcome :dao.stream/blocked})))


       stream/IDaoStreamWriter

       (append!
         [_ v]
         (swap! used conj [:append v])
         {:dao.stream/outcome :dao.stream/ok})


       stream/IDaoStreamClosable

       (close!
         [_]
         (swap! used conj [:close])
         {:dao.stream/outcome :dao.stream/ok}))
     :evict! (fn [floor] (swap! data assoc :floor floor))
     :close-medium! (fn [] (swap! data assoc :closed? true))
     :used used}))


(defn- recording-attacher
  "A unary attach capability over one handle, recording every call."
  [handle]
  (let [calls (atom [])]
    {:attach! (fn [descriptor]
                (swap! calls conj descriptor)
                {:dao.stream/outcome :dao.stream/ok,
                 :dao.stream/handle handle})
     :calls calls}))


(defn- attach-to
  "Attach one observer to `medium` through a fresh unary capability."
  [medium]
  (let [{:keys [attach!]} (recording-attacher (:handle medium))]
    (observer/attach attach! {:dao.stream/type :test/medium})))


;; =============================================================================
;; attach
;; =============================================================================

(deftest the-attachment-entry-is-descriptor-only-test
  (let [{:keys [handle]} (reader-medium)
        {:keys [attach! calls]} (recording-attacher handle)
        attach-observer (partial observer/attach attach!)
        observer (attach-observer {:dao.stream/type :test/medium,
                                   :dao.stream/identity "m-1"})]
    (testing "The composed entry receives only the descriptor"
      (is (= [{:dao.stream/type :test/medium, :dao.stream/identity "m-1"}]
             @calls)))
    (testing "The complete initial state is handle, cursor, and zero gaps"
      (is (= {:stream handle, :cursor {:pos 0}, :ingress-gaps 0} observer)))))


(deftest attach-calls-the-capability-exactly-once-test
  (let [{:keys [handle]} (reader-medium)
        {:keys [attach! calls]} (recording-attacher handle)]
    (observer/attach attach! {:dao.stream/type :test/medium})
    (is (= 1 (count @calls)))))


(deftest attach-preserves-failing-outcomes-test
  (doseq [outcome [:dao.stream/invalid-descriptor
                   :dao.stream/not-found
                   :dao.stream/transport-error]]
    (testing (str outcome)
      (let [attach! (fn [_descriptor] {:dao.stream/outcome outcome})
            data (throws-ex-data #(observer/attach attach!
                                                   {:dao.stream/type :test/medium}))]
        (is (some? data) "A failed attachment throws")
        (is (= outcome (:dao.stream/outcome data))
            "The original outcome is preserved")))))


(deftest attach-rejects-a-handle-without-the-reader-surface-test
  (let [writer-only (reify
                      stream/IDaoStreamWriter
                      (append! [_ _] {:dao.stream/outcome :dao.stream/ok}))
        descriptor {:dao.stream/type :test/medium, :dao.stream/identity "m-2"}
        {:keys [attach!]} (recording-attacher writer-only)
        data (throws-ex-data #(observer/attach attach! descriptor))]
    (testing "A host assembly error names the descriptor and declared surfaces"
      (is (some? data))
      (is (= descriptor (:descriptor data)))
      (is (= #{:writer} (:surfaces data)))
      (is (not (contains? data :dao.stream/outcome))
          "It does not invent a DaoStream outcome"))))


(deftest attach-preserves-a-cursor-construction-failure-test
  (let [refusing (reify
                   stream/IDaoStreamReader
                   (cursor [_ _] {:dao.stream/outcome :dao.stream/transport-error})

                   (next [_ _] {:dao.stream/outcome :dao.stream/blocked}))
        {:keys [attach!]} (recording-attacher refusing)
        data (throws-ex-data #(observer/attach attach!
                                               {:dao.stream/type :test/medium}))]
    (is (some? data))
    (is (= :dao.stream/transport-error (:dao.stream/outcome data))
        "The mint outcome is preserved and no partial observer exists")))


(deftest attach-keeps-a-supplied-cursor-and-gap-count-test
  (testing "The kept-cursor arity returns the kept values without minting"
    (let [mint-refusing (reify
                          stream/IDaoStreamReader
                          (cursor [_ _] {:dao.stream/outcome :dao.stream/transport-error})

                          (next [_ _] {:dao.stream/outcome :dao.stream/blocked}))
          {:keys [attach!]} (recording-attacher mint-refusing)
          observer (observer/attach attach!
                                    {:dao.stream/type :test/medium}
                                    {:cursor {:pos 4}, :ingress-gaps 3})]
      (is (= {:stream mint-refusing, :cursor {:pos 4}, :ingress-gaps 3} observer)
          "A handle that refuses cursor construction still attaches, because
              the kept cursor is returned without minting one")))
  (testing "Observation resumes at the kept cursor"
    (let [{:keys [handle]} (reader-medium [[:a] [:b] [:c]])
          {:keys [attach!]} (recording-attacher handle)
          observer (observer/attach attach!
                                    {:dao.stream/type :test/medium}
                                    {:cursor {:pos 2}, :ingress-gaps 5})
          r (observer/observe-next observer)]
      (is (= :ok (:status r)))
      (is (= [:c] (:batch r))
          "The first read starts where the kept cursor says, not at :oldest")
      (is (= 5 (:ingress-gaps (:observer r)))
          "The gap count is seeded from the checkpoint, not reset"))))


(deftest the-observer-never-writes-or-closes-the-medium-test
  (let [medium (reader-medium [[:batch]])
        observer (attach-to medium)]
    (observer/observe-next observer)
    (observer/observe-next observer)
    (is (empty? @(:used medium))
        "The observer reads only: it never appends, closes, or creates")))


;; =============================================================================
;; observe-next
;; =============================================================================

(deftest observe-next-ok-test
  (let [medium (reader-medium [[:one]])
        observer (attach-to medium)
        r (observer/observe-next observer)]
    (is (= :ok (:status r)))
    (is (= [:one] (:batch r)))
    (is (= {:pos 1} (:cursor (:observer r)))
        "The successor observer carries the exact returned cursor")))


(deftest observe-next-blocked-and-end-retain-the-cursor-test
  (testing "blocked retains the cursor"
    (let [medium (reader-medium)
          observer (attach-to medium)
          r (observer/observe-next observer)]
      (is (= :blocked (:status r)))
      (is (= (:cursor observer) (:cursor (:observer r))))))
  (testing "end retains the cursor"
    (let [medium (reader-medium)
          _ ((:close-medium! medium))
          observer (attach-to medium)
          r (observer/observe-next observer)]
      (is (= :end (:status r)))
      (is (= (:cursor observer) (:cursor (:observer r)))))))


(deftest observe-next-gap-recovers-and-counts-test
  (let [medium (reader-medium [[:one] [:two] [:three]])
        observer (attach-to medium)
        _ ((:evict! medium) 1)
        r (observer/observe-next observer)]
    (is (= :gap (:status r)) "The lost batch is reported, not hidden")
    (is (= {:pos 1} (:cursor (:observer r))) "The recovery cursor is committed")
    (is (= 1 (:ingress-gaps (:observer r))))
    (let [r2 (observer/observe-next (:observer r))]
      (is (= :ok (:status r2)) "Observation continues from the recovery cursor")
      (is (= [:two] (:batch r2))))))


(deftest observe-next-throws-on-terminal-and-unexpected-outcomes-test
  (doseq [outcome [:dao.stream/cursor-mismatch
                   :dao.stream/invalid-cursor
                   :dao.stream/transport-error
                   :dao.stream/wholly-unexpected]]
    (testing (str outcome)
      (let [handle (reify
                     stream/IDaoStreamReader
                     (cursor
                       [_ _]
                       {:dao.stream/outcome :dao.stream/ok,
                        :dao.stream/cursor {:pos 0}})

                     (next [_ _] {:dao.stream/outcome outcome}))
            {:keys [attach!]} (recording-attacher handle)
            observer (observer/attach attach! {:dao.stream/type :test/medium})
            data (throws-ex-data #(observer/observe-next observer))]
        (is (some? data))
        (is (= outcome (:dao.stream/outcome data))
            "The original outcome is preserved")))))


(deftest independent-observers-keep-independent-cursors-test
  (let [medium (reader-medium [[:a] [:b]])
        o1 (attach-to medium)
        o2 (attach-to medium)
        r1 (observer/observe-next o1)
        r2 (observer/observe-next o2)]
    (is (= (:batch r1) (:batch r2))
        "Both observers read the same history from their own oldest")
    (is (= [:b] (:batch (observer/observe-next (:observer r1))))
        "The first observer's progress does not move the second's cursor")
    (is (= [:b] (:batch (observer/observe-next (:observer r2)))))))


;; =============================================================================
;; run-on-stream
;; =============================================================================

(defn- scripted-consumer
  "A consumer with no evaluator fields: a plain map plus recording
   coordination functions, proving `run-on-stream` inspects no walker state.

   `ready-at` is the set of step numbers at which the consumer may accept
   another batch; `run` advances the step. `load` records each
   batch and throws on one carrying ::poison."
  [ready-at]
  (let [state (atom {:step 0, :events [], :batches []})]
    {:vm {:shape ::not-an-ast-walker}
     :state state
     :ready? (fn [_vm] (contains? ready-at (:step @state)))
     :load (fn [vm batch]
             (swap! state (fn [s]
                            (-> s
                                (update :events conj :load)
                                (update :batches conj batch))))
             (when (some #{::poison} batch)
               (throw (ex-info "poison batch" {:batch batch})))
             vm)
     :run (fn [vm]
            (swap! state (fn [s]
                           (-> s
                               (update :step inc)
                               (update :events conj :run))))
            vm)}))


(defn- coordinate
  "Run one session over `medium` with consumer `c`."
  [c medium]
  (observer/run-on-stream {:observer (attach-to medium), :consumer (:vm c)}
                          (:ready? c)
                          (:load c)
                          (:run c)))


(deftest run-on-stream-runs-before-observing-when-not-ready-test
  (let [c (scripted-consumer #{1})
        medium (reader-medium [[:one]])
        session (coordinate c medium)]
    (testing "The VM ran first, then observed, loaded, and ran again"
      (is (= [:run :load :run] (:events @(:state c)))))
    (testing "The observed batch reached the loader in order"
      (is (= [[:one]] (:batches @(:state c)))))
    (testing "The returned session carries the advanced observer and the VM"
      (is (= {:pos 1} (:cursor (:observer session))))
      (is (= {:shape ::not-an-ast-walker} (:consumer session))
          "A consumer that is not an ASTWalkerVM threads through untouched"))))


(deftest run-on-stream-returns-on-blocked-and-end-without-running-test
  (testing "A ready VM with an empty medium returns without running"
    (let [c (scripted-consumer #{0})
          medium (reader-medium)
          session (coordinate c medium)]
      (is (= [] (:events @(:state c))))
      (is (= {:pos 0} (:cursor (:observer session))))))
  (testing "A closed medium ends the round"
    (let [c (scripted-consumer #{0})
          medium (reader-medium)
          _ ((:close-medium! medium))
          session (coordinate c medium)]
      (is (= [] (:events @(:state c))))
      (is (= {:pos 0} (:cursor (:observer session)))))))


(deftest run-on-stream-recovers-across-a-gap-test
  (let [c (scripted-consumer (set (range 10)))
        medium (reader-medium [[:one] [:two] [:three]])
        observer (attach-to medium)
        _ ((:evict! medium) 1)
        session (observer/run-on-stream {:observer observer, :consumer (:vm c)}
                                        (:ready? c)
                                        (:load c)
                                        (:run c))]
    (is (= 1 (:ingress-gaps (:observer session))))
    (is (= [[:two] [:three]] (:batches @(:state c)))
        "The evicted batch is skipped at the recovery cursor and the retained
            batches load in order")))


(deftest run-on-stream-returns-when-execution-suspends-test
  (let [c (scripted-consumer #{0})
        medium (reader-medium [[:one] [:two]])
        session (coordinate c medium)]
    (is (= [[:one]] (:batches @(:state c)))
        "Only the first batch was read before the VM suspended")
    (is (= {:pos 1} (:cursor (:observer session))))))


(deftest a-failing-loader-leaves-the-old-cursor-for-a-retry-test
  (let [c (scripted-consumer (set (range 10)))
        medium (reader-medium [[::poison]])
        observer (attach-to medium)
        data (throws-ex-data #(observer/run-on-stream
                                {:observer observer, :consumer (:vm c)}
                                (:ready? c)
                                (:load c)
                                (:run c)))]
    (testing "The load failure propagates carrying the pre-batch session"
      (is (= {:batch [::poison]} (dissoc data :session)))
      (is (= {:pos 0} (:cursor observer))
          "The caller's observer cursor is unchanged, so the batch is retained")
      (is (= {:pos 0} (:cursor (:observer (:session data))))
          "The carried session names the cursor before the failing batch"))
    (testing "The retry observes the same malformed batch, not the next one"
      (let [recording-load (fn [vm batch]
                             (swap! (:state c) update :batches conj batch)
                             vm)
            session (observer/run-on-stream {:observer observer, :consumer (:vm c)}
                                            (:ready? c)
                                            recording-load
                                            (:run c))]
        (is (= [[::poison] [::poison]] (:batches @(:state c))))
        (is (= {:pos 1} (:cursor (:observer session))))))))


(deftest run-on-stream-inspects-no-evaluator-fields-test
  (testing "A consumer that is a plain value, not a map, threads through"
    (let [medium (reader-medium [[:one]])
          session (observer/run-on-stream
                    {:observer (attach-to medium), :consumer ::plain-value}
                    (constantly true)
                    (fn [vm batch] (if (= [:one] batch) [vm :loaded batch] vm))
                    identity)]
      (is (= [::plain-value :loaded [:one]] (:consumer session))))))


;; =============================================================================
;; Partial sessions on failure
;; =============================================================================

(deftest a-load-failure-carries-the-session-before-the-failing-batch-test
  (let [c (scripted-consumer (set (range 10)))
        medium (reader-medium [[:a] [::poison] [:c]])
        observer (attach-to medium)
        data (throws-ex-data #(observer/run-on-stream
                                {:observer observer, :consumer (:vm c)}
                                (:ready? c)
                                (:load c)
                                (:run c)))]
    (testing "The throw keeps its data and carries the partial session"
      (is (= {:batch [::poison]} (dissoc data :session)))
      (let [carried (:session data)]
        (is (= {:pos 1} (:cursor (:observer carried)))
            "A forwarded, then B's load threw: the carried cursor is after A
                and before the failing batch")
        (is (= (:vm c) (:consumer carried)))))
    (testing "A retry from the carried session re-reads B, not A"
      (let [recording-load (fn [vm batch]
                             (swap! (:state c) update :batches conj batch)
                             vm)
            session (observer/run-on-stream (:session data)
                                            (:ready? c)
                                            recording-load
                                            (:run c))]
        (is (= [[:a] [::poison] [::poison] [:c]] (:batches @(:state c)))
            "A loaded once, the failing B re-read on retry, C loaded: A is
                never repeated")
        (is (= {:pos 3} (:cursor (:observer session))))))))


(deftest a-run-failure-carries-the-session-after-the-loaded-batch-test
  (let [loaded (atom [])
        refused (atom false)
        medium (reader-medium [[:a] [:b] [:c]])
        observer (attach-to medium)
        load (fn [consumer batch]
               (swap! loaded conj batch)
               (assoc consumer :pending batch))
        run (fn [{:keys [pending] :as consumer}]
              (when (and (= [:b] pending) (not @refused))
                (reset! refused true)
                (throw (ex-info "run refused batch B" {:batch pending})))
              (-> consumer
                  (update :delivered conj pending)
                  (assoc :pending nil)))
        ready? (fn [consumer] (nil? (:pending consumer)))
        data (throws-ex-data #(observer/run-on-stream
                                {:observer observer,
                                 :consumer {:pending nil, :delivered []}}
                                ready?
                                load
                                run))]
    (testing "The throw keeps its data and carries the partial session"
      (is (= {:batch [:b]} (dissoc data :session)))
      (let [carried (:session data)]
        (is (= {:pos 2} (:cursor (:observer carried)))
            "The batch was observed and loaded before the run failed, so the
                carried cursor is after B")
        (is (= {:pending [:b], :delivered [[:a]]} (:consumer carried))
            "The consumer is the loaded state the failing run left in hand")))
    (testing "A retry from the carried session resumes, repeating nothing"
      (let [session (observer/run-on-stream (:session data)
                                            ready?
                                            load
                                            run)]
        (is (= [[:a] [:b] [:c]] @loaded)
            "Each batch loaded exactly once: B was not re-read")
        (is (= [[:a] [:b] [:c]] (:delivered (:consumer session)))
            "Each batch delivered exactly once: the resumed run delivered B
                and A was not re-delivered")
        (is (= {:pos 3} (:cursor (:observer session))))
        (is (nil? (:pending (:consumer session))))))))


(deftest a-run-failure-reports-partial-flush-progress-test
  (let [delivered (atom {:out [], :log []})
        refusals (atom 0)
        medium (reader-medium [[:a] [:b]])
        observer (attach-to medium)
        ;; The expander's load: one batch stages one payload per destination
        ;; medium.
        load (fn [consumer batch]
               (assoc consumer :out-staged batch, :log-staged [:log batch]))
        ;; The expander's run: deliver each staged payload to its own medium,
        ;; clearing that slot on ok. B's log append is refused twice, and the
        ;; refusal reports the partial state in the throw — out flushed, log
        ;; still staged — under :consumer.
        flush (fn [consumer]
                (let [consumer (if-some [out (:out-staged consumer)]
                                 (do (swap! delivered update :out conj out)
                                     (assoc consumer :out-staged nil))
                                 consumer)]
                  (if (and (= [:log [:b]] (:log-staged consumer))
                           (< @refusals 2))
                    (do (swap! refusals inc)
                        (throw (ex-info "log append refused"
                                        {:dao.stream/outcome :dao.stream/full,
                                         :consumer consumer})))
                    (if-some [log (:log-staged consumer)]
                      (do (swap! delivered update :log conj log)
                          (assoc consumer :log-staged nil))
                      consumer))))
        ready? (fn [consumer]
                 (and (nil? (:out-staged consumer))
                      (nil? (:log-staged consumer))))
        initial {:out-staged nil, :log-staged nil}
        data (throws-ex-data #(observer/run-on-stream
                                {:observer observer, :consumer initial}
                                ready?
                                load
                                flush))]
    (testing "The carried consumer is the reported partial state, not the
              pre-run one"
      (is (= :dao.stream/full (:dao.stream/outcome data))
          "The refusing run's own outcome is preserved")
      (is (= {:out-staged nil, :log-staged [:log [:b]]}
             (:consumer (:session data)))
          "B's output was delivered and its log was not: the slots say so,
              rather than re-staging the output the pre-run value held")
      (is (= {:out [[:a] [:b]], :log [[:log [:a]]]} @delivered)
          "A flushed both media; B flushed its output only"))
    (testing "A refusal during the not-ready retry carries the same report"
      (let [data' (throws-ex-data #(observer/run-on-stream
                                     (:session data)
                                     ready?
                                     load
                                     flush))]
        (is (= {:pos 2} (:cursor (:observer (:session data'))))
            "The not-ready retry never reads, so the cursor is unchanged")
        (is (= {:out-staged nil, :log-staged [:log [:b]]}
               (:consumer (:session data')))
            "The second refusal's report is honored on the not-ready path")))
    (testing "The resumed retry delivers only the remaining log"
      (let [session (observer/run-on-stream (:session data) ready? load flush)]
        (is (= {:out [[:a] [:b]], :log [[:log [:a]] [:log [:b]]]} @delivered)
            "B's output appears exactly once; the log is completed")
        (is (= initial (:consumer session)))
        (is (= {:pos 2} (:cursor (:observer session))))))))


(deftest a-terminal-read-after-a-processed-batch-carries-the-session-test
  (let [reads (atom [{:dao.stream/outcome :dao.stream/ok,
                      :dao.stream/value [:a],
                      :dao.stream/cursor {:pos 1}}
                     {:dao.stream/outcome :dao.stream/transport-error}])
        handle (reify
                 stream/IDaoStreamReader
                 (cursor
                   [_ _]
                   {:dao.stream/outcome :dao.stream/ok,
                    :dao.stream/cursor {:pos 0}})

                 (next
                   [_ _]
                   (let [answer (first @reads)]
                     (swap! reads rest)
                     answer)))
        {:keys [attach!]} (recording-attacher handle)
        c (scripted-consumer (set (range 10)))
        observer (observer/attach attach! {:dao.stream/type :test/medium})
        data (throws-ex-data #(observer/run-on-stream
                                {:observer observer, :consumer (:vm c)}
                                (:ready? c)
                                (:load c)
                                (:run c)))]
    (testing "The batch was processed before the terminal read"
      (is (= [[:a]] (:batches @(:state c)))))
    (testing "The terminal throw carries the post-batch session"
      (is (= :dao.stream/transport-error (:dao.stream/outcome data)))
      (let [carried (:session data)]
        (is (= {:pos 1} (:cursor (:observer carried))))
        (is (= (:vm c) (:consumer carried)))))))


(deftest the-carried-throw-preserves-the-original-as-cause-test
  (let [original (ex-info "run refused batch B" {:batch [:b]})
        medium (reader-medium [[:b]])
        observer (attach-to medium)
        caught (thrown #(observer/run-on-stream
                          {:observer observer, :consumer 0}
                          (constantly true)
                          (fn [consumer _batch] (inc consumer))
                          (fn [_consumer] (throw original))))]
    (testing "The rethrow is a new ExceptionInfo naming the original as cause"
      (is (identical? original (ex-cause caught))
          "A caller matching the original exception reaches it as the cause")
      (is (= "run refused batch B" (ex-message caught))
          "The original message is preserved"))
    (testing "The original data is preserved beside the carried session"
      (is (= {:batch [:b]} (dissoc (ex-data caught) :session)))
      (is (= 1 (:consumer (:session (ex-data caught))))
          "A throw reporting no :consumer falls back to the loaded value"))))
