(ns dao.stream.v2.ringbuffer-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.conformance :as conformance]
            [dao.stream.v2.ringbuffer :as ring]))


(def spec
  {:dao.stream/type ring/transport-type
   :dao.stream.ringbuffer/capacity 2})


(defn handle
  []
  (:dao.stream/handle (ring/create! spec)))


(defn cur
  [h a]
  (:dao.stream/cursor (stream/cursor h a)))


(deftest retention-and-gaps
  (let [h (handle) c (cur h :dao.stream/oldest)]
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! h :a))))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! h :b))))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! h :c))))
    (is (= :dao.stream/gap (:dao.stream/outcome (stream/next h c))))
    (let [r (stream/next h (:dao.stream/cursor (stream/next h c)))]
      (is (= :dao.stream/ok (:dao.stream/outcome r)))
      (is (= :b (:dao.stream/value r))))))


(deftest anchors-and-identity
  (let [h (handle) other (handle)
        newest (cur h :dao.stream/newest)]
    (is (= :dao.stream/blocked (:dao.stream/outcome (stream/next h newest))))
    (is (= :dao.stream/invalid-anchor
           (:dao.stream/outcome (stream/cursor h :bad))))
    (is (= :dao.stream/cursor-mismatch
           (:dao.stream/outcome
             (stream/next other newest))))))


(deftest close-freezes-attachment
  (let [owner (handle)
        d (:dao.stream/descriptor (stream/descriptor owner))
        attached (:dao.stream/handle
                   ((ring/make-attacher {(:dao.stream/identity d) owner}) d))
        c (cur attached :dao.stream/newest)]
    (stream/append! owner :before)
    (stream/close! attached)
    (is (= :dao.stream/closed (:dao.stream/outcome (stream/append! attached :x))))
    (stream/append! owner :after)
    (let [before (stream/next attached c)]
      (is (= :dao.stream/ok (:dao.stream/outcome before)))
      (is (= :before (:dao.stream/value before)))
      (is (= :dao.stream/end
             (:dao.stream/outcome
               (stream/next attached (:dao.stream/cursor before))))))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! owner :ok))))))


(deftest attachment-resolution
  (let [h (handle) d (:dao.stream/descriptor (stream/descriptor h))
        resolver {(:dao.stream/identity d) h}]
    (is (= :dao.stream/ok (:dao.stream/outcome (ring/attach! resolver d))))
    (is (= :dao.stream/not-found
           (:dao.stream/outcome (ring/attach! {} d))))
    (is (= :dao.stream/invalid-descriptor
           (:dao.stream/outcome (ring/attach! resolver {:dao.stream/type :other}))))))


(defn- attachment
  ([] (attachment spec))
  ([creation-spec]
   (let [owner (:dao.stream/handle (ring/create! creation-spec))
         descriptor (:dao.stream/descriptor (stream/descriptor owner))]
     {:owner owner
      :descriptor descriptor
      :attached (:dao.stream/handle
                  (ring/attach! {(:dao.stream/identity descriptor) owner}
                                descriptor))})))


(def ringbuffer-manifest
  "The reference transport's complete outcome declaration.  Every fixture
   operates a fresh real handle; it never fabricates an outcome map."
  {:dao.stream/type ring/transport-type
   :surfaces #{:reader :writer :closable}
   :handle-factory handle
   :operations
   {:create! {:produces #{:dao.stream/ok :dao.stream/invalid-spec}
              :exclusions {:dao.stream/not-found "host dispatch selects transport"
                           :dao.stream/transport-error "in-memory allocation has no operational failure channel"}}
    :attach! {:produces #{:dao.stream/ok :dao.stream/invalid-descriptor :dao.stream/not-found}
              :exclusions {:dao.stream/transport-error "captured in-memory resolver has no failure channel"}}
    :descriptor {:produces #{:dao.stream/ok} :exclusions {}}
    :cursor {:produces #{:dao.stream/ok :dao.stream/invalid-anchor :dao.stream/closed}
             :exclusions {:dao.stream/transport-error "one in-memory state deref cannot fail operationally"}}
    :next {:produces #{:dao.stream/ok :dao.stream/blocked :dao.stream/end
                       :dao.stream/gap :dao.stream/cursor-mismatch :dao.stream/invalid-cursor}
           :exclusions {:dao.stream/transport-error "coherent in-memory state has no failure channel"}}
    :append! {:produces #{:dao.stream/ok :dao.stream/closed}
              :exclusions {:dao.stream/full "evict-oldest retention never refuses append"
                           :dao.stream/invalid-value "reference transport accepts all host values"
                           :dao.stream/transport-error "coherent in-memory state has no failure channel"}}
    :close! {:produces #{:dao.stream/ok} :exclusions {}}}
   :fixtures
   {:create! {:dao.stream/ok #(ring/create! spec)
              :dao.stream/invalid-spec #(ring/create! {:dao.stream/type ring/transport-type})}
    :attach! {:dao.stream/ok #(let [{:keys [owner descriptor]} (attachment)]
                                (ring/attach! {(:dao.stream/identity descriptor) owner} descriptor))
              :dao.stream/invalid-descriptor #(ring/attach! {} {:dao.stream/type :other})
              :dao.stream/not-found #(let [h (handle)
                                           d (:dao.stream/descriptor (stream/descriptor h))]
                                       (ring/attach! {} d))}
    :descriptor {:dao.stream/ok #(stream/descriptor (handle))}
    :cursor {:dao.stream/ok #(stream/cursor (handle) :dao.stream/oldest)
             :dao.stream/invalid-anchor #(stream/cursor (handle) ::invalid-anchor)
             :dao.stream/closed #(let [{:keys [attached]} (attachment)]
                                   (stream/close! attached)
                                   (stream/cursor attached :dao.stream/oldest))}
    :next {:dao.stream/ok #(let [h (handle)
                                 c (cur h :dao.stream/oldest)]
                             (stream/append! h :value)
                             (stream/next h c))
           :dao.stream/blocked #(let [h (handle)]
                                  (stream/next h (cur h :dao.stream/newest)))
           :dao.stream/end #(let [h (handle)
                                  c (cur h :dao.stream/newest)]
                              (stream/close! h)
                              (stream/next h c))
           :dao.stream/gap #(let [h (:dao.stream/handle (ring/create! (assoc spec ring/capacity-key 1)))
                                  c (cur h :dao.stream/oldest)]
                              (stream/append! h :old)
                              (stream/append! h :new)
                              (stream/next h c))
           :dao.stream/cursor-mismatch #(let [left (handle) right (handle)]
                                          (stream/next right (cur left :dao.stream/oldest)))
           :dao.stream/invalid-cursor #(stream/next (handle) nil)}
    :append! {:dao.stream/ok #(stream/append! (handle) :value)
              :dao.stream/closed #(let [h (handle)]
                                    (stream/close! h)
                                    (stream/append! h :value))}
    :close! {:dao.stream/ok #(stream/close! (handle))}}})


(deftest ringbuffer-conformance-test
  (let [result (conformance/run-conformance-suite ringbuffer-manifest)]
    (is (:passed? result) (str "Conformance failures: " (:failures result)))))


(deftest attachment-lifecycle-regressions
  (testing "attachment close is idempotent and freezes its original tail"
    (let [{:keys [owner attached]} (attachment (assoc spec ring/capacity-key 4))
          cursor (cur attached :dao.stream/newest)]
      (stream/append! owner :before)
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/close! attached))))
      (stream/append! owner :after-first-close)
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/close! attached))))
      (stream/append! owner :after-second-close)
      (let [seen (stream/next attached cursor)]
        (is (= :before (:dao.stream/value seen)))
        (is (= :dao.stream/end
               (:dao.stream/outcome (stream/next attached (:dao.stream/cursor seen))))))))

  (testing "gap recovery on a frozen attachment never points past its tail"
    (let [owner (:dao.stream/handle (ring/create! (assoc spec ring/capacity-key 1)))
          descriptor (:dao.stream/descriptor (stream/descriptor owner))
          attached (:dao.stream/handle (ring/attach! {(:dao.stream/identity descriptor) owner} descriptor))
          cursor (cur attached :dao.stream/oldest)]
      (stream/append! owner :before)
      (stream/close! attached)
      (stream/append! owner :after)
      (let [gap (stream/next attached cursor)]
        (is (= :dao.stream/gap (:dao.stream/outcome gap)))
        (is (= :dao.stream/end
               (:dao.stream/outcome (stream/next attached (:dao.stream/cursor gap))))))))

  (testing "gap recovery from a frozen attachment whose history is fully evicted"
    ;; Eviction can pass a closed attachment's frozen tail entirely.  Recovery
    ;; must still name the earliest retained position, which is the only
    ;; position a later read can make progress from.
    (let [owner (:dao.stream/handle (ring/create! (assoc spec ring/capacity-key 1)))
          descriptor (:dao.stream/descriptor (stream/descriptor owner))
          attached (:dao.stream/handle (ring/attach! {(:dao.stream/identity descriptor) owner} descriptor))
          cursor (cur attached :dao.stream/oldest)]
      (stream/append! owner :one)
      (stream/close! attached)
      (stream/append! owner :two)
      (stream/append! owner :three)
      (let [gap (stream/next attached cursor)
            recovery (:dao.stream/cursor gap)]
        (is (= :dao.stream/gap (:dao.stream/outcome gap)))
        (is (= 2 (:dao.stream.ringbuffer/position recovery)))
        ;; The recovery cursor must make progress: reading it never re-gaps.
        (is (= :dao.stream/end
               (:dao.stream/outcome (stream/next attached recovery)))))))

  (testing "a mis-keyed host directory cannot attach to a different stream"
    (let [left (handle)
          right (handle)
          descriptor (:dao.stream/descriptor (stream/descriptor left))]
      (is (= :dao.stream/not-found
             (:dao.stream/outcome
               (ring/attach! {(:dao.stream/identity descriptor) right} descriptor)))))))


(deftest ringbuffer-projector-linearizability-test
  (let [h (handle)
        identity (:dao.stream/identity (stream/descriptor h))
        cursor (cur h :dao.stream/oldest)
        history [{:id 0 :op :append! :args [:value]
                  :result (stream/append! h :value) :start 1 :end 2}
                 {:id 1 :op :next :args [cursor]
                  :result (stream/next h cursor) :start 3 :end 4}]
        model (conformance/make-abstract-stream-model
                identity nil []
                {:cursor-projector
                 {:identity :dao.stream.ringbuffer/identity
                  :position :dao.stream.ringbuffer/position}})
        result (conformance/check-linearizability history model conformance/abstract-stream-step)]
    (is (:linearizable? result) (str "Expected ring history to linearize: " result))))


;; =============================================================================
;; Bounded concurrent linearizability histories
;; =============================================================================

(def ^:private ring-projector
  {:cursor-projector {:identity :dao.stream.ringbuffer/identity
                      :position :dao.stream.ringbuffer/position}})


(defn- ring-model
  "The abstract model for one ring buffer, told which attachments exist.
   Attachment entries are composition facts the model cannot observe."
  ([stream-identity capacity]
   (ring-model stream-identity capacity {}))
  ([stream-identity capacity attachments]
   (conformance/make-abstract-stream-model
     stream-identity capacity []
     (assoc ring-projector :attachments attachments))))


(defn- linearizes?
  [history model]
  (conformance/check-linearizability history model conformance/abstract-stream-step))


#?(:cljd nil
   :clj
   (defn- on-attachment
     "The oracle's invocation shape for an operation issued through an attachment
      handle rather than the logical-stream owner."
     [attachment args]
     {:dao.stream/args args :dao.stream/handle {:attachment attachment}}))


(deftest linearizability-oracle-rejects-an-invalid-history
  ;; The oracle is only evidence if it can fail.  Here `append!` completed
  ;; before `next` was invoked, so every legal linearization must observe the
  ;; appended value; a `blocked` read is a real ordering violation.
  (let [h (handle)
        stream-identity (:dao.stream/identity (stream/descriptor h))
        c (cur h :dao.stream/oldest)
        history [{:id 0 :op :append! :args [:a]
                  :result {:dao.stream/outcome :dao.stream/ok} :start 1 :end 2}
                 {:id 1 :op :next :args [c]
                  :result {:dao.stream/outcome :dao.stream/blocked} :start 3 :end 4}]
        result (linearizes? history (ring-model stream-identity 2))]
    (is (false? (:linearizable? result)))
    (is (= :no-legal-linearization (:reason result)))))


#?(:cljd nil
   :clj
   (defn- run-concurrently
     "Run each thunk on its own thread against one shared history and clock.
      Every thunk receives `[history clock]` and records its own invocation
      interval with `conformance/record-op!`.  A caller may supply a history
      already holding earlier, strictly ordered operations."
     ([thunks] (run-concurrently (atom []) (atom 0) thunks))
     ([history clock thunks]
      (let [start (promise)
            threads (mapv (fn [thunk]
                            (future (deref start 5000 :go)
                                    (thunk history clock)))
                          thunks)]
        (deliver start :go)
        (doseq [t threads]
          (is (not= :timeout (deref t 5000 :timeout)) "concurrent operation timed out"))
        @history))))


#?(:cljd nil
   :clj
   (deftest bounded-concurrent-linearizability-histories
     (testing "concurrent append/append"
       (let [h (handle)
             stream-identity (:dao.stream/identity (stream/descriptor h))
             history (run-concurrently
                       [(fn [hist clock]
                          (conformance/record-op! hist clock :append! [:a]
                                                  #(stream/append! h :a)))
                        (fn [hist clock]
                          (conformance/record-op! hist clock :append! [:b]
                                                  #(stream/append! h :b)))])
             result (linearizes? history (ring-model stream-identity 2))]
         (is (= 2 (count history)))
         (is (:linearizable? result) (str history " " result))))

     (testing "concurrent append/next"
       (let [h (handle)
             stream-identity (:dao.stream/identity (stream/descriptor h))
             c (cur h :dao.stream/oldest)
             history (run-concurrently
                       [(fn [hist clock]
                          (conformance/record-op! hist clock :append! [:a]
                                                  #(stream/append! h :a)))
                        (fn [hist clock]
                          (conformance/record-op! hist clock :next [c]
                                                  #(stream/next h c)))])
             result (linearizes? history (ring-model stream-identity 2))]
         (is (:linearizable? result) (str history " " result))))

     (testing "concurrent append/close"
       (let [h (handle)
             stream-identity (:dao.stream/identity (stream/descriptor h))
             history (run-concurrently
                       [(fn [hist clock]
                          (conformance/record-op! hist clock :append! [:a]
                                                  #(stream/append! h :a)))
                        (fn [hist clock]
                          (conformance/record-op! hist clock :close! []
                                                  #(stream/close! h)))])
             result (linearizes? history (ring-model stream-identity 2))]
         (is (:linearizable? result) (str history " " result))))

     (testing "concurrent cursor/close on one attachment"
       (let [owner (handle)
             stream-identity (:dao.stream/identity (stream/descriptor owner))
             descriptor (:dao.stream/descriptor (stream/descriptor owner))
             attached (ring/attach! {stream-identity owner} descriptor)
             reader (:dao.stream/handle attached)
             id (:dao.stream/attachment attached)
             history (run-concurrently
                       [(fn [hist clock]
                          (conformance/record-op! hist clock :cursor
                                                  (on-attachment id [:dao.stream/newest])
                                                  #(stream/cursor reader :dao.stream/newest)))
                        (fn [hist clock]
                          (conformance/record-op! hist clock :close!
                                                  (on-attachment id [])
                                                  #(stream/close! reader)))])
             result (linearizes? history (ring-model stream-identity 2 {id {:open? true}}))]
         (is (:linearizable? result) (str history " " result))))

     (testing "two independent readers observe one retained value"
       (let [owner (handle)
             stream-identity (:dao.stream/identity (stream/descriptor owner))
             descriptor (:dao.stream/descriptor (stream/descriptor owner))
             resolver {stream-identity owner}
             left (ring/attach! resolver descriptor)
             right (ring/attach! resolver descriptor)
             left-handle (:dao.stream/handle left)
             right-handle (:dao.stream/handle right)
             left-id (:dao.stream/attachment left)
             right-id (:dao.stream/attachment right)
             left-cursor (cur left-handle :dao.stream/oldest)
             right-cursor (cur right-handle :dao.stream/oldest)
             hist (atom [])
             clock (atom 0)
             ;; The append completes first; the two reads are concurrent with
             ;; each other, which is what reader independence must survive.
             _ (conformance/record-op! hist clock :append! [:v]
                                       #(stream/append! owner :v))
             history (run-concurrently
                       hist clock
                       [(fn [h c]
                          (conformance/record-op! h c :next
                                                  (on-attachment left-id [left-cursor])
                                                  #(stream/next left-handle left-cursor)))
                        (fn [h c]
                          (conformance/record-op! h c :next
                                                  (on-attachment right-id [right-cursor])
                                                  #(stream/next right-handle right-cursor)))])
             result (linearizes? history
                                 (ring-model stream-identity 2
                                             {left-id {:open? true}
                                              right-id {:open? true}}))]
         (is (:linearizable? result) (str history " " result))))))
