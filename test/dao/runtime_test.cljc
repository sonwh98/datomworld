(ns dao.runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.runtime :as rt]
            [dao.stream :as stream]
            [yin.vm.test-utils :as tu]))


(deftest initial-state-test
  (testing "A fresh runtime has no work and is not blocked"
    (is (= {:ready-queue [], :wait-set [], :blocked? false}
           (rt/initial-state)))))


(deftest queue-discipline-test
  (testing "Ready entries pop in order"
    (let [rt (rt/enqueue-ready (rt/initial-state) [{:id 1} {:id 2}])
          [entry rt'] (rt/pop-ready rt)]
      (is (= 1 (:id entry)))
      (is (= [{:id 2}] (:ready-queue rt')))
      (is (nil? (rt/pop-ready (rt/initial-state)))))))


(def read-expectations
  "The expected classification of every declared `next` outcome, given the
   outcome map `{:dao.stream/outcome o, :dao.stream/value :v,
   :dao.stream/cursor :c}`. A future contract outcome added to
   `dao.stream/outcomes-next` has no entry here, so it fails the test
   instead of falling silently into the classifier's default branch: a human
   decides whether the new outcome waits or resolves."
  {:dao.stream/ok [:ready {:value :v, :status :ok, :cursor :c}]
   :dao.stream/blocked [:wait]
   :dao.stream/end [:ready {:value nil, :status :end}]
   :dao.stream/gap [:ready {:value :dao.stream/gap,
                            :status :dao.stream/gap,
                            :cursor :c}]
   :dao.stream/cursor-mismatch [:ready {:value :dao.stream/cursor-mismatch,
                                        :status :dao.stream/cursor-mismatch}]
   :dao.stream/invalid-cursor [:ready {:value :dao.stream/invalid-cursor,
                                       :status :dao.stream/invalid-cursor}]
   :dao.stream/transport-error [:ready {:value :dao.stream/transport-error,
                                        :status :dao.stream/transport-error}]})


(def write-expectations
  "The expected classification of every declared `append!` outcome, given
   the outcome map `{:dao.stream/outcome o}` and the appended value `:v`.
   Same rule as `read-expectations`: a new declared outcome fails here
   rather than resolving silently as terminal."
  {:dao.stream/ok [:ready {:value :v, :status :ok}]
   :dao.stream/full [:wait]
   :dao.stream/invalid-value [:ready {:value :dao.stream/invalid-value,
                                      :status :dao.stream/invalid-value}]
   :dao.stream/closed [:ready {:value nil, :status :end}]
   :dao.stream/transport-error [:ready {:value :dao.stream/transport-error,
                                        :status :dao.stream/transport-error}]})


(defn- waiting-outcomes
  "The outcomes in `outcomes` whose classification by `classify` is [:wait]."
  [outcomes classify]
  (set (filter #(= :wait (first (classify {:dao.stream/outcome %})))
               outcomes)))


(deftest read-outcome-classification-test
  (testing "Every declared next outcome classifies as the table says"
    (is (= stream/outcomes-next (set (keys read-expectations)))
        "outcomes-next and the expectation table cover the same outcomes")
    (doseq [o stream/outcomes-next]
      (is (= (get read-expectations o)
             (rt/read-outcome->task {:dao.stream/outcome o,
                                     :dao.stream/value :v,
                                     :dao.stream/cursor :c}))
          (str o))))
  (testing "Exactly blocked waits — the only read outcome that can change
            on its own"
    (is (= #{:dao.stream/blocked} (waiting-outcomes stream/outcomes-next
                                                    rt/read-outcome->task))))
  (testing "ok carries the value and the opaque successor cursor"
    (is (= [:ready {:value 7, :status :ok, :cursor :c}]
           (rt/read-outcome->task {:dao.stream/outcome :dao.stream/ok,
                                   :dao.stream/value 7,
                                   :dao.stream/cursor :c}))))
  (testing "end resolves the task with nil"
    (is (= [:ready {:value nil, :status :end}]
           (rt/read-outcome->task {:dao.stream/outcome :dao.stream/end}))))
  (testing "gap carries the transport's recovery cursor to the task"
    (is (= [:ready {:value :dao.stream/gap,
                    :status :dao.stream/gap,
                    :cursor :recovery}]
           (rt/read-outcome->task {:dao.stream/outcome :dao.stream/gap,
                                   :dao.stream/cursor :recovery}))))
  (testing "An outcome outside the declared set is terminal, not a wait"
    (is (= [:ready {:value :dao.stream/undeclared,
                    :status :dao.stream/undeclared}]
           (rt/read-outcome->task {:dao.stream/outcome
                                   :dao.stream/undeclared})))))


(deftest write-outcome-classification-test
  (testing "Every declared append outcome classifies as the table says"
    (is (= stream/outcomes-append (set (keys write-expectations)))
        "outcomes-append and the expectation table cover the same outcomes")
    (doseq [o stream/outcomes-append]
      (is (= (get write-expectations o)
             (rt/write-outcome->task {:dao.stream/outcome o} :v))
          (str o))))
  (testing "Exactly full waits — the only append outcome that can change on
            its own"
    (is (= #{:dao.stream/full} (waiting-outcomes stream/outcomes-append
                                                 #(rt/write-outcome->task %
                                                                          :v)))))
  (testing "ok resolves with the appended value"
    (is (= [:ready {:value :v, :status :ok}]
           (rt/write-outcome->task {:dao.stream/outcome :dao.stream/ok}
                                   :v))))
  (testing "A writer's closed resolves as end, the writer-side twin of a
            reader's end"
    (is (= [:ready {:value nil, :status :end}]
           (rt/write-outcome->task {:dao.stream/outcome :dao.stream/closed}
                                   :v))))
  (testing "An outcome outside the declared set is terminal, not a wait"
    (is (= [:ready {:value :dao.stream/undeclared,
                    :status :dao.stream/undeclared}]
           (rt/write-outcome->task {:dao.stream/outcome
                                    :dao.stream/undeclared}
                                   :v)))))


(deftest polling-wait-set-is-the-mechanism-test
  (testing "A reader parked on an empty stream wakes when a value arrives"
    (let [handle (tu/new-stream 4)
          cursor (:dao.stream/cursor (stream/cursor handle
                                                    stream/anchor-oldest))
          {:keys [state result]} (rt/handle-read (rt/initial-state)
                                                 handle
                                                 cursor
                                                 {:id :reader})]
      (is (= :blocked result))
      (is (true? (:blocked? state)))
      (is (= 1 (count (:wait-set state))))
      (testing "No value yet: it stays in the wait set"
        (is (= 1 (count (:wait-set (rt/check-wait-set state))))))
      (stream/append! handle :hello)
      (let [state' (rt/check-wait-set state)]
        (is (empty? (:wait-set state')))
        (is (= 1 (count (:ready-queue state'))))
        (let [entry (first (:ready-queue state'))]
          (is (= :hello (:value entry)))
          (is (= :ok (:status entry)))
          (is (some? (:cursor entry))))))))


(deftest close-does-not-wake-a-reader-directly-test
  (testing "A closed stream is reported by the reader's own next"
    (let [handle (tu/new-stream 4)
          cursor (:dao.stream/cursor (stream/cursor handle
                                                    stream/anchor-oldest))
          {:keys [state]} (rt/handle-read (rt/initial-state)
                                          handle
                                          cursor
                                          {:id :reader})]
      (is (= {:state state, :result :ok} (rt/handle-close state handle)))
      (let [state' (rt/check-wait-set state)]
        (is (empty? (:wait-set state')))
        (is (= :end (:status (first (:ready-queue state')))))))))


(deftest run-once-resumes-a-ready-task-test
  (testing "An entry with a :resume fn is popped and invoked"
    (let [rt (rt/enqueue-ready (rt/initial-state)
                               [{:value 5,
                                 :resume (fn [state _entry value]
                                           (assoc state :done value))}])]
      (is (= 5 (:done (rt/run-once rt))))))
  (testing "An entry without :resume is left for the host"
    (is (nil? (rt/run-once (rt/enqueue-ready (rt/initial-state)
                                             [{:value 5}]))))))


(deftest run-loop-returns-a-polled-state-behind-a-host-owned-head-test
  (testing "A writer resolved by a poll behind a host-owned head is
            returned with it, appended exactly once"
    (let [writes (atom 0)
          refuse-once? (atom true)
          ;; A fake writer that answers full once — the refused park attempt,
          ;; which writes nothing — and ok thereafter, counting the appends
          ;; that actually wrote.
          handle (reify
                   stream/IDaoStreamWriter
                   (append!
                     [_ _val]
                     (if (true? @refuse-once?)
                       (do (reset! refuse-once? false)
                           {:dao.stream/outcome :dao.stream/full})
                       (do (swap! writes inc)
                           {:dao.stream/outcome :dao.stream/ok}))))
          {:keys [state result]} (rt/handle-write (rt/initial-state)
                                                  handle :v
                                                  {:id :writer,
                                                   :resume (fn [state _ _]
                                                             state)})
          _ (is (= :full result))
          ;; A host-owned entry (no :resume) at the head of the ready queue.
          with-host (rt/enqueue-ready state [{:id :host}])]
      (testing "run-once alone never polls: the writer stays parked, unwritten"
        (is (nil? (rt/run-once with-host)))
        (is (= 0 @writes))
        (is (= 1 (count (:wait-set with-host)))))
      (let [final (rt/run-loop with-host)]
        (testing "Exactly one append: the value reached the stream once"
          (is (= 1 @writes)))
        (testing "The writer is resolved in the ready queue behind the
                  host-owned entry"
          (let [[host woken] (:ready-queue final)]
            (is (= :host (:id host)))
            (is (= :writer (:id woken)))
            (is (= :v (:value woken)))
            (is (= :ok (:status woken)))
            (is (fn? (:resume woken))))
          (is (empty? (:wait-set final))))
        (testing "The returned state contains both entries"
          (is (= 2 (count (:ready-queue final)))))
        (testing "A re-drive appends nothing more: no duplicate write"
          (rt/run-loop final)
          (is (= 1 @writes)))))))


(deftest run-loop-drains-and-polls-until-quiescent-test
  (testing "Ready entries run in order, and the loop stops when nothing moves"
    (let [ran (atom [])
          entry (fn [id]
                  {:id id,
                   :resume (fn [state task _value]
                             (swap! ran conj (:id task))
                             state)})
          ;; A reader parked on an empty stream: every poll answers blocked,
          ;; so it can never resolve within this drive.
          handle (tu/new-stream 4)
          cursor (:dao.stream/cursor (stream/cursor handle
                                                    stream/anchor-oldest))
          {:keys [state]} (rt/handle-read (rt/initial-state)
                                          handle
                                          cursor
                                          (entry :reader))
          with-ready (rt/enqueue-ready state [(entry :a) (entry :b)])
          final (rt/run-loop with-ready)]
      (is (= [:a :b] @ran))
      (is (= [:reader] (map :id (:wait-set final)))
          "An unresolvable reader stays parked; the loop returns, it does
           not spin"))))
