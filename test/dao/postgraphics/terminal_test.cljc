(ns dao.postgraphics.terminal-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.postgraphics.terminal :as term]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as rb]))


(defn- ring
  ([] (ring 64))
  ([capacity]
   (:dao.stream/handle (rb/create! {:dao.stream/type rb/transport-type,
                                    rb/capacity-key capacity}))))


(defn- scripted-handle
  "A reader whose cursor mints ::c and whose `next` answers the outcomes in
   script, one per call, then :blocked."
  [script]
  (let [remaining (atom script)]
    (reify
      stream/IDaoStreamReader
      (cursor
        [_ _anchor]
        {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor ::c})

      (next
        [_ _cursor]
        (let [[outcome & more] @remaining]
          (reset! remaining more)
          (or outcome {:dao.stream/outcome :dao.stream/blocked}))))))


(defn- signals
  "Every value retained on handle, oldest first."
  [handle]
  (loop [cursor (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest))
         acc []]
    (let [read (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome read))
        (recur (:dao.stream/cursor read) (conj acc (:dao.stream/value read)))
        acc))))


(defn- validating-presenter
  [accepted]
  {:validate-frame!
   (fn [frame]
     (when (= :reject frame)
       (throw (ex-info "rejected"
                       {:dao.postgraphics/reason :validation-failure})))
     (when (= :unsupported frame)
       (throw (ex-info "unsupported"
                       {:dao.postgraphics/reason :unsupported-op})))),
   :present-frame! (fn [frame] (swap! accepted conj frame))})


(defn- statuses
  "Step binding n times, returning [binding' [status ...]]."
  [binding n]
  (loop [binding binding
         n n
         acc []]
    (if (zero? n)
      [binding acc]
      (let [{binding' :binding, status :status} (term/step binding)]
        (recur binding' (dec n) (conj acc status))))))


(deftest bind-appends-one-reset-and-mints-at-newest
  (let [frames (ring)
        accepted (atom [])
        sig (ring)]
    (is (= {:dao.stream/outcome :dao.stream/ok}
           (term/put-frame! frames [:frame/before])))
    (let [binding (term/bind frames
                             (merge (validating-presenter accepted)
                                    {:signal-handle sig,
                                     :generation-id "gen-a"}))]
      (is (= [{:message/kind :dao.terminal/reset, :generation-id "gen-a"}]
             (signals sig)))
      (is (= :blocked (:status (term/step binding)))
          "a frame appended before bind is not presented")
      (term/put-frame! frames [:frame/after])
      (is (empty? @accepted) "put-frame! wakes nothing")
      (let [{binding' :binding, status :status} (term/step binding)]
        (is (= :presented status))
        (is (= [[:frame/after]] @accepted))
        (is (= :blocked (:status (term/step binding'))))))))


(deftest rebinding-presents-only-frames-emitted-after-the-new-bind
  (testing "the host :on-bind shape: a remount rebinds at :newest, so the
            initial frame must be emitted after bind, not before"
    (let [frames (ring)
          accepted (atom [])
          first-mount (term/bind frames (validating-presenter accepted))]
      (term/put-frame! frames [:sample 1])
      (is (= :blocked (:status (term/step-until-blocked first-mount))))
      (is (= [[:sample 1]] @accepted))
      (term/close first-mount)
      (term/put-frame! frames [:emitted-before-remount])
      (let [remount (term/bind frames (validating-presenter accepted))]
        (term/put-frame! frames [:sample 2])
        (is (= :blocked (:status (term/step-until-blocked remount))))
        (is (= [[:sample 1] [:sample 2]] @accepted))))))


(deftest generation-id-fn-supplies-generation
  (let [sig (ring)
        binding (term/bind (ring) {:signal-handle sig,
                                   :generation-id-fn (fn [] "gen-b")})]
    (is (= "gen-b" (:generation-id binding)))
    (is (= "gen-b" (:generation-id (first (signals sig)))))))


(deftest rejected-frame-appends-rejection-and-calls-on-error
  (let [frames (ring)
        accepted (atom [])
        sig (ring)
        errors (atom [])
        binding (term/bind frames
                           (merge (validating-presenter accepted)
                                  {:signal-handle sig,
                                   :on-error #(swap! errors conj %)}))]
    (term/put-frame! frames [:frame/ok])
    (term/put-frame! frames :unsupported)
    (let [[_ ss] (statuses binding 3)]
      (is (= [:presented :rejected :blocked] ss)))
    (is (= [[:frame/ok]] @accepted))
    (is (= 1 (count @errors)))
    (is (= {:message/kind :dao.terminal/rejection,
            :submission-id 1,
            :reason :unsupported-op}
           (second (signals sig))))))


(deftest capacity-one-buffer-gaps-then-presents
  (let [frames (ring 1)
        accepted (atom [])
        sig (ring)
        binding (term/bind frames
                           (merge (validating-presenter accepted)
                                  {:signal-handle sig}))]
    (term/put-frame! frames [:frame/evicted])
    (term/put-frame! frames [:frame/presented])
    (let [[_ ss] (statuses binding 3)]
      (is (= [:gap :presented :blocked] ss)))
    (is (= [[:frame/presented]] @accepted))
    (is (= [:dao.terminal/reset :dao.terminal/frame-skipped]
           (map :message/kind (signals sig))))
    (is (= 0 (:submission-id (second (signals sig)))))))


(deftest end-yields-end-once-then-closed
  (let [frames (ring)
        sig (ring)
        binding (term/bind frames {:signal-handle sig})]
    (stream/close! frames)
    (let [[_ ss] (statuses binding 3)]
      (is (= [:end :closed :closed] ss)))
    (is (= [{:message/kind :dao.terminal/reset,
             :generation-id (:generation-id binding)}
            {:message/kind :dao.terminal/protocol-error,
             :error/kind :dao.terminal/transport-error,
             :dao.stream/outcome :dao.stream/end,
             :frame-id nil}]
           (signals sig))
        "no frame was presented, so :frame-id is nil")))


(deftest transport-error-frame-id-is-last-presented-not-submission
  (let [accepted (atom [])
        sig (ring)
        handle (scripted-handle
                 [{:dao.stream/outcome :dao.stream/ok,
                   :dao.stream/value [:frame/a],
                   :dao.stream/cursor ::c}
                  {:dao.stream/outcome :dao.stream/ok,
                   :dao.stream/value :reject,
                   :dao.stream/cursor ::c}
                  {:dao.stream/outcome :dao.stream/gap,
                   :dao.stream/cursor ::c}
                  {:dao.stream/outcome :dao.stream/ok,
                   :dao.stream/value [:frame/b],
                   :dao.stream/cursor ::c}
                  {:dao.stream/outcome :dao.stream/cursor-mismatch}])
        binding (term/bind handle
                           (merge (validating-presenter accepted)
                                  {:signal-handle sig}))
        [binding' ss] (statuses binding 5)]
    (is (= [:presented :rejected :gap :presented :error] ss))
    (is (= 4 (:submission-id binding')))
    (is (= 1 (:presented-frame-id binding')))
    (is (= {:message/kind :dao.terminal/protocol-error,
            :error/kind :dao.terminal/transport-error,
            :dao.stream/outcome :dao.stream/cursor-mismatch,
            :frame-id 1}
           (last (signals sig))))))


(deftest transport-error-yields-error-with-protocol-error-signal
  (let [sig (ring)
        handle (scripted-handle [{:dao.stream/outcome
                                  :dao.stream/transport-error}])
        binding (term/bind handle {:signal-handle sig})
        [_ ss] (statuses binding 2)]
    (is (= [:error :closed] ss) "a failed read is not retried")
    (is (= {:message/kind :dao.terminal/protocol-error,
            :error/kind :dao.terminal/transport-error,
            :dao.stream/outcome :dao.stream/transport-error,
            :frame-id nil}
           (second (signals sig))))))


(deftest gap-adopts-the-recovery-cursor
  (let [accepted (atom [])
        seen (atom [])
        handle (reify
                 stream/IDaoStreamReader
                 (cursor
                   [_ _]
                   {:dao.stream/outcome :dao.stream/ok,
                    :dao.stream/cursor :minted})

                 (next
                   [_ cursor]
                   (swap! seen conj cursor)
                   (case cursor
                     :minted {:dao.stream/outcome :dao.stream/gap,
                              :dao.stream/cursor :recovered}
                     :recovered {:dao.stream/outcome :dao.stream/ok,
                                 :dao.stream/value [:frame/x],
                                 :dao.stream/cursor :after}
                     {:dao.stream/outcome :dao.stream/blocked})))
        binding (term/bind handle (validating-presenter accepted))
        [_ ss] (statuses binding 3)]
    (is (= [:gap :presented :blocked] ss))
    (is (= [:minted :recovered :after] @seen))
    (is (= [[:frame/x]] @accepted))))


(deftest step-until-blocked-drains-a-burst-within-its-bound
  (let [frames (ring)
        accepted (atom [])
        binding (term/bind frames (validating-presenter accepted))]
    (dotimes [i 5] (term/put-frame! frames [i]))
    (testing "bound reached with frames pending"
      (let [{binding' :binding, status :status}
            (term/step-until-blocked binding 3)]
        (is (= :presented status))
        (is (= [[0] [1] [2]] @accepted))
        (testing "the next call drains the rest"
          (is (= :blocked (:status (term/step-until-blocked binding'))))
          (is (= [[0] [1] [2] [3] [4]] @accepted)))))))


(deftest closed-binding-stops-presenting-frames
  (let [frames (ring)
        accepted (atom [])
        binding (term/close (term/bind frames (validating-presenter accepted)))]
    (term/put-frame! frames [:frame/after-close])
    (is (= :closed (:status (term/step binding))))
    (is (empty? @accepted))))
