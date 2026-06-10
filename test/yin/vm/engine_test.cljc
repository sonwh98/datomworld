(ns yin.vm.engine-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.apply :as dao.stream.apply]
    [dao.stream.ringbuffer :as stream]
    [dao.test-utils :as tu]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]))


(deftest run-loop-resumes-when-blocked-with-preexisting-run-queue-test
  (testing "Blocked VM still resumes from an already non-empty run-queue"
    (let [resume-calls (atom 0)
          state {:blocked? true,
                 :halted? false,
                 :ready-queue [{:value 42}],
                 :wait-set []}
          result (engine/run-loop state
                                  (fn [_] false)
                                  (fn [v] v)
                                  (fn [v]
                                    (swap! resume-calls inc)
                                    (when-let [entry (first (:ready-queue v))]
                                      (assoc v
                                             :value (:value entry)
                                             :ready-queue (vec (rest (:ready-queue
                                                                       v)))
                                             :blocked? false
                                             :halted? true)))
                                  nil)]
      (is (= 1 @resume-calls))
      (is (= 42 (:value result)))
      (is (false? (:blocked? result)))
      (is (true? (:halted? result)))
      (is (empty? (:ready-queue result))))))


(deftest run-loop-noop-when-blocked-and-no-runnable-work-test
  (testing
    "Blocked VM remains blocked if scheduler cannot wake and run-queue is empty"
    (let [state {:blocked? true, :halted? false, :ready-queue [], :wait-set []}
          result
          (engine/run-loop state (fn [_] false) (fn [v] v) (fn [_] nil) nil)]
      (is (true? (:blocked? result)))
      (is (false? (:halted? result)))
      (is (empty? (:ready-queue result))))))


(deftest restore-initial-env-test
  (testing "Halted computations restore the caller env"
    (let [initial-env {'outer 1}
          result {:halted? true, :ready-queue [], :env {'inner 2}}]
      (is (= initial-env
             (:env (engine/restore-initial-env initial-env result))))))
  (testing "Blocked and queued computations keep the active env for resumption"
    (let [initial-env {'outer 1}
          blocked-result
          {:blocked? true, :halted? false, :ready-queue [], :env {'inner 2}}
          queued-result {:halted? true,
                         :ready-queue [{:continuation {:id :k}}],
                         :env {'inner 2}}]
      (is (= {'inner 2}
             (:env (engine/restore-initial-env initial-env blocked-result))))
      (is (= {'inner 2}
             (:env (engine/restore-initial-env initial-env queued-result)))))))


(defn- make-stream
  []
  (ds/open! {:type :ringbuffer, :capacity nil}))


(deftest handle-effect-emits-telemetry-snapshot-test
  (testing "handle-effect emits :effect snapshots when telemetry is enabled"
    (let [telemetry-stream (make-stream)
          state {:blocked? false,
                 :halted? false,
                 :parked {},
                 :ready-queue [],
                 :wait-set [],
                 :store {},
                 :telemetry {:stream telemetry-stream, :vm-id :engine/effect},
                 :telemetry-step 0,
                 :telemetry-t 0,
                 :vm-model :engine/test}
          result (engine/handle-effect
                   state
                   {:effect :vm/store-put, :key :answer, :val 42}
                   {})
          datoms (tu/stream-values telemetry-stream)]
      (is (= 42 (get-in result [:state :store :answer])))
      (is (tu/fact? datoms :vm/phase :effect))
      (is (tu/fact? datoms :vm/effect-type :vm/store-put))
      (is (tu/fact? datoms :vm/value 42)))))


(deftest park-and-resume-emit-telemetry-snapshots-test
  (testing
    "park-continuation and resume-continuation emit phase-tagged telemetry"
    (let [telemetry-stream (make-stream)
          state {:blocked? false,
                 :halted? false,
                 :parked {},
                 :ready-queue [],
                 :wait-set [],
                 :store {},
                 :telemetry {:stream telemetry-stream, :vm-id :engine/park},
                 :telemetry-step 0,
                 :telemetry-t 0,
                 :vm-model :engine/test}
          parked-state (engine/park-continuation state {:continuation {:id :k}})
          parked-id (-> parked-state
                        :value
                        :id)
          resumed-state (engine/resume-continuation parked-state
                                                    parked-id
                                                    :resumed
                                                    (fn [base _parked
                                                         resume-val]
                                                      (assoc base
                                                             :value resume-val
                                                             :halted? true
                                                             :blocked? false)))
          datoms (tu/stream-values telemetry-stream)]
      (is (= :resumed (:value resumed-state)))
      (is (tu/fact? datoms :vm/phase :park))
      (is (tu/fact? datoms :vm/phase :resume))
      (is (tu/fact? datoms :vm/parked-id parked-id)))))


(deftest run-loop-emits-terminal-telemetry-test
  (testing "run-loop emits :blocked? terminal snapshots"
    (let [telemetry-stream (make-stream)
          state {:blocked? true,
                 :halted? false,
                 :parked {},
                 :ready-queue [],
                 :wait-set [],
                 :store {},
                 :telemetry {:stream telemetry-stream, :vm-id :engine/blocked},
                 :telemetry-step 0,
                 :telemetry-t 0,
                 :vm-model :engine/test}
          _ (engine/run-loop state (fn [_] false) identity (fn [_] nil) nil)
          datoms (tu/stream-values telemetry-stream)]
      (is (tu/fact? datoms :vm/phase :blocked))))
  (testing "run-loop emits :halt terminal snapshots"
    (let [telemetry-stream (make-stream)
          state {:blocked? false,
                 :halted? true,
                 :parked {},
                 :ready-queue [],
                 :wait-set [],
                 :store {},
                 :telemetry {:stream telemetry-stream, :vm-id :engine/halt},
                 :telemetry-step 0,
                 :telemetry-t 0,
                 :vm-model :engine/test}
          _ (engine/run-loop state (fn [_] false) identity (fn [_] nil) nil)
          datoms (tu/stream-values telemetry-stream)]
      (is (tu/fact? datoms :vm/phase :halt)))))


;; =============================================================================
;; Fallback Scheduler Tests (Non-waitable streams)
;; =============================================================================


#?(:cljd (deftest check-wait-set-fallback-test
           (testing
             "CLJD skips non-waitable stream fallback scheduler test doubles"
             (is true)))
   :default
   (deftest check-wait-set-fallback-test
     (testing
       "check-wait-set still polls non-waitable streams in the scheduler"
       (let [stream (tu/make-non-waitable-stream)
             state {:store {:s1 stream, :c1 {:stream-id :s1, :position 0}},
                    :id-counter 0}
             ;; 1. Park reader (not waitable, so it goes to wait-set)
             reader-entry {:reason :next,
                           :cursor-ref {:type :cursor-ref, :id :c1}}
             state (assoc state :wait-set [reader-entry])
             ;; 2. check-wait-set should NOT wake it (stream empty)
             state-still-blocked (#'yin.vm.engine/check-wait-set state)
             _ (is (= 1 (count (:wait-set state-still-blocked))))
             ;; 3. Put data manually
             _ (ds/put! stream 42)
             ;; 4. check-wait-set should now wake it
             state-runnable (#'yin.vm.engine/check-wait-set
                             state-still-blocked)]
         (is (empty? (:wait-set state-runnable)))
         (is (= 1 (count (:ready-queue state-runnable))))
         (is (= 42 (:value (first (:ready-queue state-runnable)))))))))


#?(:cljd (deftest check-wait-set-put-fallback-test
           (testing
             "CLJD skips non-waitable stream fallback scheduler test doubles"
             (is true)))
   :default (deftest check-wait-set-put-fallback-test
              (testing "check-wait-set still polls :put on non-waitable streams"
                (let [stream (tu/make-non-waitable-stream)
                      state {:store {:s1 stream}, :id-counter 0}
                      ;; Park writer
                      writer-entry {:reason :put, :stream-id :s1, :datom :val}
                      state (assoc state :wait-set [writer-entry])
                      ;; In this simple mock put! always succeeds, so first
                      ;; poll should wake it
                      state-runnable (#'yin.vm.engine/check-wait-set state)]
                  (is (empty? (:wait-set state-runnable)))
                  (is (= 1 (count (:ready-queue state-runnable))))
                  (is (= :val (get-in @(:state-atom stream) [:buffer 0]))
                      "Datom should be written")))))


#?(:cljd (deftest check-wait-set-resumes-closed-put-fallback-test
           (testing
             "CLJD skips non-waitable stream fallback scheduler test doubles"
             (is true)))
   :default
   (deftest check-wait-set-resumes-closed-put-fallback-test
     (testing
       "check-wait-set resumes closed non-waitable :put waiters with nil"
       (let [stream (tu/make-non-waitable-stream)
             state {:store {:s1 stream}, :id-counter 0}
             writer-entry {:reason :put, :stream-id :s1, :datom :val}
             state (assoc state :wait-set [writer-entry])
             _ (ds/close! stream)
             checked (#'yin.vm.engine/check-wait-set state)]
         (is (empty? (:wait-set checked)))
         (is (= 1 (count (:ready-queue checked))))
         (is (nil? (:value (first (:ready-queue checked)))))
         (is (true? (ds/closed? stream)))
         (is (empty? (:buffer @(:state-atom stream))))
         (is (= 0 (:tail @(:state-atom stream))))))))


(defrecord NonWaitableRingBufferStream
  [state-atom]

  ds/IDaoStreamReader

  (next
    [_this cursor]
    (let [s @state-atom
          head (:head s)
          pos (:position cursor)]
      (cond (>= pos (:tail s)) :blocked
            (< pos head) :daostream/gap
            :else {:ok (get-in s [:buffer pos]),
                   :cursor {:position (inc pos)}})))


  ds/IDaoStreamWriter

  (put!
    [_this val]
    (let [s @state-atom
          capacity 1] ; fixed capacity for test
      (if (>= (- (:tail s) (:head s)) capacity)
        {:result :full}
        (do (swap! state-atom (fn [s]
                                (-> s
                                    (assoc-in [:buffer (:tail s)] val)
                                    (update :tail inc))))
            {:result :ok}))))


  ds/IDaoStreamBound

  (close! [_this] (swap! state-atom assoc :closed true) {:woke []})


  (closed? [_this] (:closed @state-atom)))


(defrecord WaitableRetryStream
  [state-atom]

  ds/IDaoStreamReader

  (next
    [_this cursor]
    (let [call-count (:next-calls (swap! state-atom update :next-calls inc))
          pos (:position cursor)]
      (if (= 1 call-count)
        :blocked
        {:ok (get-in @state-atom [:buffer pos]),
         :cursor {:position (inc pos)}})))


  ds/IDaoStreamWaitable

  (register-reader-waiter!
    [_this position entry]
    (swap! state-atom update :reader-waiters conj [position entry]))


  (register-writer-waiter!
    [_this entry]
    (swap! state-atom update :writer-waiters conj entry))


  ds/IDaoStreamBound

  (close! [_this] (swap! state-atom assoc :closed true) {:woke []})


  (closed? [_this] (:closed @state-atom)))


(defn- ringbuffer-state-atom
  [stream]
  #?(:clj (.-state-atom ^dao.stream.ringbuffer.RingBufferStream stream)
     :cljs (.-state-atom ^dao.stream.ringbuffer/RingBufferStream stream)
     :cljd (.-state-atom ^dao.stream.ringbuffer/RingBufferStream stream)))


(deftest waitable-stream-next-retry-preserves-cursor-advance-test
  (testing
    "waitable stream/next should advance the VM cursor when the runtime retry succeeds immediately"
    (let [state-atom (atom {:buffer {0 :payload},
                            :next-calls 0,
                            :reader-waiters [],
                            :writer-waiters [],
                            :closed false})
          stream (->WaitableRetryStream state-atom)
          state {:store {:s1 stream, :c1 {:stream-id :s1, :position 0}},
                 :id-counter 0}
          result (engine/handle-effect
                   state
                   {:effect :stream/next, :cursor {:type :cursor-ref, :id :c1}}
                   {:park-entry-fns {:stream/next (fn [_s _e r]
                                                    {:reason :next,
                                                     :cursor-ref (:cursor-ref
                                                                   r),
                                                     :stream-id (:stream-id r),
                                                     :k {:id :reader}})},
                    :restore-fn (fn [base _entry value]
                                  (assoc base
                                         :value value
                                         :blocked? false
                                         :halted? false))})]
      (is (= :payload (:value result)))
      (is (false? (:blocked? result)))
      (is (= {:stream-id :s1, :position 1} (get-in result [:state :store :c1])))
      (is (= 2 (:next-calls @state-atom)))
      (is (empty? (:reader-waiters @state-atom))))))


#?(:cljd (deftest check-wait-set-put-fallback-after-capacity-freed-test
           (testing
             "CLJD skips non-waitable stream fallback scheduler test doubles"
             (is true)))
   :default
   (deftest check-wait-set-put-fallback-after-capacity-freed-test
     (testing
       "check-wait-set still polls :put on non-waitable streams after capacity is freed"
       (let [state-atom (atom
                          {:buffer {0 :a}, :tail 1, :head 0, :closed false})
             stream (->NonWaitableRingBufferStream state-atom)
             state {:store {:s1 stream}, :id-counter 0}
             ;; 1. Park writer on :put because it's full (capacity 1)
             writer-entry {:reason :put, :stream-id :s1, :datom :b}
             state (assoc state :wait-set [writer-entry])
             ;; 2. check-wait-set should NOT wake it
             state-blocked (#'yin.vm.engine/check-wait-set state)
             _ (is (= 1 (count (:wait-set state-blocked))))
             ;; 3. Manually drain (advance head)
             _ (swap! state-atom (fn [s]
                                   (-> s
                                       (update :buffer dissoc 0)
                                       (update :head inc))))
             ;; 4. check-wait-set should now wake it via polling
             state-runnable (#'yin.vm.engine/check-wait-set state-blocked)]
         (is (empty? (:wait-set state-runnable)))
         (is (= 1 (count (:ready-queue state-runnable))))
         (is (= :b (get-in @state-atom [:buffer 1]))
             "Writer should have written :b")))))


(deftest close-enqueues-flattened-transport-writer-entry-test
  (testing
    "stream close should enqueue the parked writer entry itself, not a nested {:entry ...} wrapper"
    (let [state {:store {}, :id-counter 0, :ready-queue []}
          [id s'] (engine/gensym state "stream")
          [stream-ref state] (engine/handle-make s' {:capacity 1} id)
          state (:state (engine/handle-put state {:stream stream-ref, :val 1}))
          put-effect {:effect :stream/put, :stream stream-ref, :val 2}
          put-result (engine/handle-effect state
                                           put-effect
                                           {:park-entry-fns
                                            {:stream/put
                                             (fn [_s _e r]
                                               {:reason :put,
                                                :stream-id (:stream-id r),
                                                :datom 2,
                                                :k {:id :writer-1}})}})
          blocked-state (:state put-result)
          close-result (engine/handle-effect blocked-state
                                             {:effect :stream/close,
                                              :stream stream-ref}
                                             {})
          run-entry (first (get-in close-result [:state :ready-queue]))]
      (is (:blocked? put-result))
      (is (= 1 (count (get-in close-result [:state :ready-queue]))))
      (is
        (= {:id :writer-1} (:k run-entry))
        "Queued writer should keep its original continuation fields at top level")
      (is (false? (contains? run-entry :entry))
          "Run-queue entry should be flattened before resumption")
      (is (nil? (:value run-entry))))))


(deftest take-enqueues-reader-cursor-advance-when-waking-reader-and-writer-test
  (testing
    "stream take should preserve cursor advance metadata when it wakes a parked reader and writer together"
    (let [state {:store {}, :id-counter 0, :ready-queue []}
          [stream-id s'] (engine/gensym state "stream")
          [stream-ref state] (engine/handle-make s' {:capacity 1} stream-id)
          state (:state (engine/handle-put state {:stream stream-ref, :val :a}))
          [cursor-id s''] (engine/gensym state "cursor")
          [cursor-ref state]
          (engine/handle-cursor s'' {:stream stream-ref} cursor-id)
          state (:state (engine/handle-next state {:cursor cursor-ref}))
          next-result (engine/handle-effect
                        state
                        {:effect :stream/next, :cursor cursor-ref}
                        {:park-entry-fns {:stream/next
                                          (fn [_s _e r]
                                            {:reason :next,
                                             :cursor-ref (:cursor-ref r),
                                             :stream-id (:stream-id r),
                                             :k {:id :reader}})}})
          put-result (engine/handle-effect
                       (:state next-result)
                       {:effect :stream/put, :stream stream-ref, :val :b}
                       {:park-entry-fns {:stream/put (fn [_s _e r]
                                                       {:reason :put,
                                                        :stream-id (:stream-id
                                                                     r),
                                                        :datom :b,
                                                        :k {:id :writer}})}})
          stream-after-next (get-in next-result [:state :store stream-id])
          stream-after-put (get-in put-result [:state :store stream-id])
          reader-waiter-count-after-next
          (count (:reader-waiters @(ringbuffer-state-atom stream-after-next)))
          writer-waiter-count-after-put
          (count (:writer-waiters @(ringbuffer-state-atom stream-after-put)))
          take-result (engine/handle-effect (:state put-result)
                                            {:effect :stream/take,
                                             :stream stream-ref}
                                            {})
          run-queue (get-in take-result [:state :ready-queue])
          reader-entry (some #(when (= {:id :reader} (:k %)) %) run-queue)
          writer-entry (some #(when (= {:id :writer} (:k %)) %) run-queue)]
      (is (:blocked? next-result))
      (is (:blocked? put-result))
      (is (empty? (or (get-in next-result [:state :wait-set]) [])))
      (is (empty? (or (get-in put-result [:state :wait-set]) [])))
      (is (= 1 reader-waiter-count-after-next))
      (is (= 1 writer-waiter-count-after-put))
      (is (= :a (:value take-result)))
      (is (= 2 (count run-queue)))
      (is (= :b (:value writer-entry)))
      (is (= :b (:value reader-entry)))
      (is
        (= {cursor-id {:stream-id stream-id, :position 2}}
           (:store-updates reader-entry))
        "Reader wake from take! must advance the parked cursor past the appended datom"))))


(deftest daocall-dispatch-and-wake-test
  (testing "dao.stream.apply response wakes caller and advances cursor"
    (let [call-out (ds/open! {:type :ringbuffer, :capacity nil})
          state {:store {vm/call-out-stream-key call-out,
                         vm/call-out-cursor-key
                         {:stream-id vm/call-out-stream-key, :position 0}},
                 :parked {:parked-0 {:id :parked-0,
                                     :type :parked-continuation,
                                     :k {:id :cont-0},
                                     :env {}}},
                 :ready-queue [],
                 :wait-set [],
                 :blocked? true,
                 :halted? false}
          ;; 1. Register reader-waiter on call-out (happens during
          ;; dao-call)
          waiter-entry {:cursor-ref {:type :cursor-ref,
                                     :id vm/call-out-cursor-key},
                        :reason :next,
                        :stream-id vm/call-out-stream-key,
                        :k {:id :cont-0}}
          _ (ds/register-reader-waiter! call-out 0 waiter-entry)
          ;; 2. Bridge puts response to call-out
          response (dao.stream.apply/response :parked-0 42)
          put-result (ds/put! call-out response)
          woke (:woke put-result)
          _ (is (= 1 (count woke)))
          ;; 3. Use engine to process woken entries
          entries (engine/make-woken-run-queue-entries state woke)
          next-state (update state :ready-queue into entries)
          run-entry (first (:ready-queue next-state))]
      (is (= 1 (count (:ready-queue next-state))))
      (is (= response (:value run-entry)))
      (is (= {vm/call-out-cursor-key {:stream-id vm/call-out-stream-key,
                                      :position 1}}
             (:store-updates run-entry))))))


;; =============================================================================
;; Program Cache Machinery Tests
;; =============================================================================

(deftest build-program-index-test
  (testing "Groups datoms by entity EID"
    (let [datoms [[1 :yin/type :foo] [2 :yin/type :bar] [1 :yin/name "one"]]]
      (is (= {1 [[1 :yin/type :foo] [1 :yin/name "one"]],
              2 [[2 :yin/type :bar]]}
             (engine/build-program-index datoms))))))


(deftest executable-program-datom?-test
  (testing "Identifies yin-namespaced datoms that are not derived metadata"
    (let [derived-eid @#'engine/derived-metadata-eid]
      (is (true? (engine/executable-program-datom? [1 :yin/opcode :halt 0 0])))
      (is (false? (engine/executable-program-datom? [1 :not-yin/opcode :halt 0
                                                     0])))
      (is (false? (engine/executable-program-datom? [1 :yin/opcode :halt 0
                                                     derived-eid]))))))


(deftest frame-versions-test
  (testing "Collects versions from a deep :next chain (stack-safety)"
    (let [chain (reduce (fn [acc i] {:compiled-version i, :next acc})
                        {:compiled-version 0}
                        (range 1 1000))]
      (is (= (set (range 1000)) (engine/frame-versions chain))))))


(deftest collect-frame-versions-test
  (testing "Nil-safe version collection"
    (is (= #{} (engine/collect-frame-versions nil)))
    (is (= #{1 2}
           (engine/collect-frame-versions [{:compiled-version 1}
                                           {:compiled-version 2}])))))


(deftest pinned-compiled-versions-test
  (testing "Unions all active and queued versions"
    (let [vm {:active-compiled-version 1,
              :k {:compiled-version 2},
              :parked {:p1 {:compiled-version 3}},
              :ready-queue [{:compiled-version 4}],
              :wait-set [{:compiled-version 5}]}]
      (is (= #{1 2 3 4 5} (engine/pinned-compiled-versions vm))))))


(deftest trim-compiled-cache-test
  (testing "Keeps newest-N and pinned versions"
    (let [limit @#'engine/default-compiled-cache-limit
          ;; Create a cache with limit + 5 versions, version 0 is oldest
          cache
          (into {} (map (fn [i] [i (str "artifact-" i)]) (range (+ limit 5))))
          vm {:compiled-by-version cache,
              ;; Pin the oldest version
              :active-compiled-version 0}
          trimmed (engine/trim-compiled-cache vm)]
      (is (contains? (:compiled-by-version trimmed) 0)
          "Oldest pinned version should be kept")
      (is (= (inc limit) (count (:compiled-by-version trimmed)))
          "Should keep limit + 1 (pinned)"))))


(deftest cache-compiled-artifact-test
  (testing "Stores artifact, clears dirty flag, and trims"
    (let [vm {:compile-dirty? true, :compiled-by-version {}}
          artifact {:code :ops}
          result (engine/cache-compiled-artifact vm 1 artifact {})]
      (is (= artifact (get-in result [:compiled-by-version 1])))
      (is (false? (:compile-dirty? result)))
      (is (= {} (:datom-index result))))))


(deftest ensure-compiled-version-test
  (let [compile-fn (fn [root _datoms _idx] {:stub-artifact true, :root root})
        vm {:program-root-eid :root,
            :datoms [[:root :yin/opcode :halt]],
            :program-version 1}]
    (testing "Compiles and caches when missing"
      (let [[vm' artifact] (engine/ensure-compiled-version vm 1 compile-fn)]
        (is (= {:stub-artifact true, :root :root} artifact))
        (is (= artifact (get-in vm' [:compiled-by-version 1])))))
    (testing "Throws when program is not loaded"
      (is (thrown? #?(:clj Exception
                      :cljs js/Error)
            (engine/ensure-compiled-version {} 1 compile-fn))))))


(deftest maybe-recompile-at-boundary-test
  (let [compile-fn (fn [_root _datoms _idx] {:stub-artifact true})
        vm {:program-root-eid :root,
            :datoms [[:root :yin/opcode :halt]],
            :program-version 1,
            :compile-dirty? true}]
    (testing "Recompiles when dirty"
      (let [[vm' artifact] (engine/maybe-recompile-at-boundary vm compile-fn)]
        (is (some? artifact))
        (is (false? (:compile-dirty? vm')))))
    (testing "No-op when not dirty"
      (let [clean-vm (assoc vm :compile-dirty? false)
            [vm' artifact] (engine/maybe-recompile-at-boundary clean-vm
                                                               compile-fn)]
        (is (nil? artifact))
        (is (identical? clean-vm vm'))))))


(deftest append-program-datoms-test
  (testing "Increments version and sets dirty flag on executable datoms"
    (let [vm {:program-root-eid :root, :program-version 1, :datoms []}
          new-datoms [[:root :yin/opcode :halt]]
          result (engine/append-program-datoms vm new-datoms)]
      (is (= 2 (:program-version result)))
      (is (true? (:compile-dirty? result)))
      (is (= new-datoms (:datoms result)))))
  (testing "Supports updating root eid"
    (let [vm {:program-root-eid :old-root, :program-version 1, :datoms []}
          result (engine/append-program-datoms vm [] :new-root)]
      (is (= 2 (:program-version result)))
      (is (= :new-root (:program-root-eid result)))
      (is (true? (:compile-dirty? result)))))
  (testing "Throws without root"
    (is (thrown? #?(:clj Exception
                    :cljs js/Error)
          (engine/append-program-datoms {} [[:foo :bar :baz]])))))
