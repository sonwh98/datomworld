(ns dao.stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as v2]
            [dao.stream.conformance :as conf]))


;; =============================================================================
;; Mock Handles for Surface Gating & Protocol Tests
;; =============================================================================

(defrecord MockReaderOnly
  [stream-id data-atom]

  v2/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor {:dao.stream/type :mock/stream
                             :dao.stream/identity stream-id
                             :mock/endpoint "ep-reader"}
     :dao.stream/identity stream-id})


  v2/IDaoStreamReader

  (cursor
    [_ anchor]
    (case anchor
      :dao.stream/oldest {:dao.stream/outcome :dao.stream/ok
                          :dao.stream/cursor {:stream-identity stream-id :position 0}}
      :dao.stream/newest {:dao.stream/outcome :dao.stream/ok
                          :dao.stream/cursor {:stream-identity stream-id :position (count @data-atom)}}
      {:dao.stream/outcome :dao.stream/invalid-anchor}))


  (next
    [_ cursor]
    (let [pos (:position cursor)]
      (if (< pos (count @data-atom))
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/value (nth @data-atom pos)
         :dao.stream/cursor {:stream-identity stream-id :position (inc pos)}}
        {:dao.stream/outcome :dao.stream/blocked}))))


(defrecord MockWriterOnly
  [stream-id data-atom]

  v2/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor {:dao.stream/type :mock/stream
                             :dao.stream/identity stream-id
                             :mock/endpoint "ep-writer"}
     :dao.stream/identity stream-id})


  v2/IDaoStreamWriter

  (append!
    [_ val]
    (swap! data-atom conj val)
    {:dao.stream/outcome :dao.stream/ok}))


(defrecord MockClosableOnly
  [stream-id closed-atom]

  v2/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor {:dao.stream/type :mock/stream
                             :dao.stream/identity stream-id}
     :dao.stream/identity stream-id})


  v2/IDaoStreamClosable

  (close!
    [_]
    (reset! closed-atom true)
    {:dao.stream/outcome :dao.stream/ok}))


(defrecord MockFullHandle
  [stream-id data-atom closed-atom]

  v2/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor {:dao.stream/type :mock/stream
                             :dao.stream/identity stream-id
                             :mock/endpoint "ep-full"}
     :dao.stream/identity stream-id})


  v2/IDaoStreamReader

  (cursor
    [_ anchor]
    (case anchor
      :dao.stream/oldest {:dao.stream/outcome :dao.stream/ok
                          :dao.stream/cursor {:stream-identity stream-id :position 0}}
      :dao.stream/newest {:dao.stream/outcome :dao.stream/ok
                          :dao.stream/cursor {:stream-identity stream-id :position (count @data-atom)}}
      {:dao.stream/outcome :dao.stream/invalid-anchor}))


  (next
    [_ cursor]
    (let [pos (:position cursor)]
      (cond
        (< pos (count @data-atom))
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/value (nth @data-atom pos)
         :dao.stream/cursor {:stream-identity stream-id :position (inc pos)}}

        @closed-atom
        {:dao.stream/outcome :dao.stream/end}

        :else
        {:dao.stream/outcome :dao.stream/blocked})))


  v2/IDaoStreamWriter

  (append!
    [_ val]
    (if @closed-atom
      {:dao.stream/outcome :dao.stream/closed}
      (do
        (swap! data-atom conj val)
        {:dao.stream/outcome :dao.stream/ok})))


  v2/IDaoStreamClosable

  (close!
    [_]
    (reset! closed-atom true)
    {:dao.stream/outcome :dao.stream/ok}))


;; =============================================================================
;; Tests
;; =============================================================================

(deftest result-shapes-and-open-maps-test
  (testing "result maps require :dao.stream/outcome qualified keyword"
    (is (true? (v2/outcome-map? {:dao.stream/outcome :dao.stream/ok})))
    (is (true? (v2/outcome-map? {:dao.stream/outcome :dao.stream/blocked})))
    (is (false? (v2/outcome-map? {:outcome :dao.stream/ok})))
    (is (false? (v2/outcome-map? {:dao.stream/outcome "ok"})))
    (is (false? (v2/outcome-map? {:dao.stream/outcome :ok})))
    (is (false? (v2/outcome-map? "not a map"))))

  (testing "result maps are open maps: extra keys are preserved and tolerated"
    (let [open-res {:dao.stream/outcome :dao.stream/ok
                    :dao.stream/handle :dummy-handle
                    :dao.stream/extra-info 123
                    :app/metric 42.0}]
      (is (true? (v2/valid-outcome? :create! open-res)))
      (is (= 42.0 (:app/metric open-res)))
      (is (= 123 (:dao.stream/extra-info open-res)))))

  (testing "required keys per operation outcome are strictly enforced"
    ;; create! requires :dao.stream/handle on :dao.stream/ok
    (is (true? (v2/valid-outcome? :create! {:dao.stream/outcome :dao.stream/ok
                                            :dao.stream/handle :h})))
    (is (false? (v2/valid-outcome? :create! {:dao.stream/outcome :dao.stream/ok})))

    ;; attach! requires :dao.stream/handle on :dao.stream/ok
    (is (true? (v2/valid-outcome? :attach! {:dao.stream/outcome :dao.stream/ok
                                            :dao.stream/handle :h})))
    (is (false? (v2/valid-outcome? :attach! {:dao.stream/outcome :dao.stream/ok})))

    ;; descriptor requires :dao.stream/descriptor and :dao.stream/identity on :dao.stream/ok
    (is (true? (v2/valid-outcome? :descriptor {:dao.stream/outcome :dao.stream/ok
                                               :dao.stream/descriptor {:dao.stream/type :test/stream
                                                                       :dao.stream/identity "id-1"}
                                               :dao.stream/identity "id-1"})))
    (is (false? (v2/valid-outcome? :descriptor {:dao.stream/outcome :dao.stream/ok
                                                :dao.stream/descriptor {}})))

    ;; cursor requires :dao.stream/cursor on :dao.stream/ok
    (is (true? (v2/valid-outcome? :cursor {:dao.stream/outcome :dao.stream/ok
                                           :dao.stream/cursor {:pos 0}})))
    (is (false? (v2/valid-outcome? :cursor {:dao.stream/outcome :dao.stream/ok})))

    ;; next requires :dao.stream/value and :dao.stream/cursor on :dao.stream/ok
    (is (true? (v2/valid-outcome? :next {:dao.stream/outcome :dao.stream/ok
                                         :dao.stream/value :v
                                         :dao.stream/cursor {:pos 1}})))
    (is (false? (v2/valid-outcome? :next {:dao.stream/outcome :dao.stream/ok
                                          :dao.stream/value :v})))
    (is (false? (v2/valid-outcome? :next {:dao.stream/outcome :dao.stream/ok
                                          :dao.stream/cursor {:pos 1}})))

    ;; next requires :dao.stream/cursor on :dao.stream/gap
    (is (true? (v2/valid-outcome? :next {:dao.stream/outcome :dao.stream/gap
                                         :dao.stream/cursor {:pos 5}})))
    (is (false? (v2/valid-outcome? :next {:dao.stream/outcome :dao.stream/gap})))

    ;; Outcomes without required keys (blocked, end, closed, full, etc.)
    (is (true? (v2/valid-outcome? :next {:dao.stream/outcome :dao.stream/blocked})))
    (is (true? (v2/valid-outcome? :next {:dao.stream/outcome :dao.stream/end})))
    (is (true? (v2/valid-outcome? :append! {:dao.stream/outcome :dao.stream/ok})))
    (is (true? (v2/valid-outcome? :append! {:dao.stream/outcome :dao.stream/full})))
    (is (true? (v2/valid-outcome? :append! {:dao.stream/outcome :dao.stream/closed})))
    (is (true? (v2/valid-outcome? :close! {:dao.stream/outcome :dao.stream/ok}))))

  (testing "result constructors"
    (is (= {:dao.stream/outcome :dao.stream/ok} (v2/ok-result)))
    (is (= {:dao.stream/outcome :dao.stream/ok :val 1} (v2/ok-result {:val 1})))
    (is (= {:dao.stream/outcome :dao.stream/blocked} (v2/outcome-result :dao.stream/blocked)))
    (is (= {:dao.stream/outcome :dao.stream/full :retry false}
           (v2/outcome-result :dao.stream/full {:retry false})))))


(deftest exhaustive-operation-outcome-declarations-test
  (testing "contract operation outcome sets match design specification exactly"
    (is (= #{:dao.stream/ok
             :dao.stream/invalid-spec
             :dao.stream/not-found
             :dao.stream/transport-error}
           (get v2/operation-outcomes :create!)))

    (is (= #{:dao.stream/ok
             :dao.stream/invalid-descriptor
             :dao.stream/not-found
             :dao.stream/transport-error}
           (get v2/operation-outcomes :attach!)))

    (is (= #{:dao.stream/ok}
           (get v2/operation-outcomes :descriptor)))

    (is (= #{:dao.stream/ok
             :dao.stream/invalid-anchor
             :dao.stream/closed
             :dao.stream/transport-error}
           (get v2/operation-outcomes :cursor)))

    (is (= #{:dao.stream/ok
             :dao.stream/blocked
             :dao.stream/end
             :dao.stream/gap
             :dao.stream/cursor-mismatch
             :dao.stream/invalid-cursor
             :dao.stream/transport-error}
           (get v2/operation-outcomes :next)))

    (is (= #{:dao.stream/ok
             :dao.stream/full
             :dao.stream/invalid-value
             :dao.stream/closed
             :dao.stream/transport-error}
           (get v2/operation-outcomes :append!)))

    (is (= #{:dao.stream/ok}
           (get v2/operation-outcomes :close!))))

  (testing "unauthorized outcomes are rejected by validator"
    (is (false? (v2/valid-outcome? :close! {:dao.stream/outcome :dao.stream/closed})))
    (is (false? (v2/valid-outcome? :descriptor {:dao.stream/outcome :dao.stream/not-found})))
    (is (false? (v2/valid-outcome? :append! {:dao.stream/outcome :dao.stream/blocked})))
    (is (false? (v2/valid-outcome? :next {:dao.stream/outcome :dao.stream/full})))))


(deftest descriptor-identity-equality-test
  (testing "descriptor result requires both descriptor and identity, and sibling matches envelope"
    (let [stream-id "logical-id-42"
          desc {:dao.stream/type :mock/ringbuffer
                :dao.stream/identity stream-id
                :mock/port 9000}
          valid-res {:dao.stream/outcome :dao.stream/ok
                     :dao.stream/descriptor desc
                     :dao.stream/identity stream-id}
          mismatched-res {:dao.stream/outcome :dao.stream/ok
                          :dao.stream/descriptor desc
                          :dao.stream/identity "different-id"}]
      (is (true? (v2/descriptor-identity-consistent? valid-res)))
      (is (false? (v2/descriptor-identity-consistent? mismatched-res)))
      (is (false? (v2/descriptor-identity-consistent? {:dao.stream/outcome :dao.stream/ok
                                                       :dao.stream/identity stream-id})))
      (is (false? (v2/descriptor-identity-consistent? {:dao.stream/outcome :dao.stream/ok
                                                       :dao.stream/descriptor desc})))))

  (testing "distinct descriptors for distinct endpoints of the same stream carry equal identity"
    (let [shared-id "stream-xyz"
          desc-ep1 {:dao.stream/type :ws/stream
                    :dao.stream/identity shared-id
                    :ws/host "endpoint-1.datom.world"}
          desc-ep2 {:dao.stream/type :ws/stream
                    :dao.stream/identity shared-id
                    :ws/host "endpoint-2.datom.world"}]
      (is (not= desc-ep1 desc-ep2) "descriptors differ in reachability")
      (is (= (:dao.stream/identity desc-ep1) (:dao.stream/identity desc-ep2))
          "identity projection is strictly equal across endpoints"))))


(deftest generic-envelope-validation-test
  (testing "generic envelope validation requires map with qualified keyword :dao.stream/type"
    (is (true? (v2/valid-envelope? {:dao.stream/type :ringbuffer/in-memory})))
    (is (true? (v2/valid-envelope? {:dao.stream/type :dao.stream/ringbuffer
                                    :ringbuffer/capacity 1024})))
    (is (false? (v2/valid-envelope? {:dao.stream/type :unqualified})))
    (is (false? (v2/valid-envelope? {:dao.stream/type "string-type"})))
    (is (false? (v2/valid-envelope? {:type :ringbuffer/in-memory})))
    (is (false? (v2/valid-envelope? nil)))
    (is (false? (v2/valid-envelope? [:dao.stream/type :ringbuffer/in-memory]))))

  (testing "creation specification validation"
    (is (true? (v2/valid-creation-spec? {:dao.stream/type :stream/ref, :ref/bound 10})))
    (is (false? (v2/valid-creation-spec? {:unqualified :kw}))))

  (testing "portable descriptor validation requires :dao.stream/identity"
    (is (true? (v2/valid-descriptor? {:dao.stream/type :ws/stream
                                      :dao.stream/identity "stream-123"})))
    (is (false? (v2/valid-descriptor? {:dao.stream/type :ws/stream}))
        "missing identity is not a valid descriptor")
    (is (false? (v2/valid-descriptor? {:dao.stream/identity "stream-123"}))
        "missing :dao.stream/type is not a valid descriptor")))


(deftest surface-gating-test
  (let [stream-id "gating-stream"
        data-atom (atom [])
        closed-atom (atom false)
        reader-handle (->MockReaderOnly stream-id data-atom)
        writer-handle (->MockWriterOnly stream-id data-atom)
        closable-handle (->MockClosableOnly stream-id closed-atom)
        full-handle (->MockFullHandle stream-id data-atom closed-atom)]

    (testing "surface predicate inspection"
      (is (true? (v2/reader? reader-handle)))
      (is (false? (v2/writer? reader-handle)))
      (is (false? (v2/closable? reader-handle)))
      (is (true? (v2/descriptor? reader-handle)))
      (is (= #{:reader} (v2/declared-surfaces reader-handle)))

      (is (false? (v2/reader? writer-handle)))
      (is (true? (v2/writer? writer-handle)))
      (is (false? (v2/closable? writer-handle)))
      (is (true? (v2/descriptor? writer-handle)))
      (is (= #{:writer} (v2/declared-surfaces writer-handle)))

      (is (false? (v2/reader? closable-handle)))
      (is (false? (v2/writer? closable-handle)))
      (is (true? (v2/closable? closable-handle)))
      (is (true? (v2/descriptor? closable-handle)))
      (is (= #{:closable} (v2/declared-surfaces closable-handle)))

      (is (true? (v2/reader? full-handle)))
      (is (true? (v2/writer? full-handle)))
      (is (true? (v2/closable? full-handle)))
      (is (true? (v2/descriptor? full-handle)))
      (is (= #{:reader :writer :closable} (v2/declared-surfaces full-handle))))

    (testing "descriptor is universal: callable on all handles regardless of surface"
      (is (= :dao.stream/ok (:dao.stream/outcome (v2/descriptor reader-handle))))
      (is (= :dao.stream/ok (:dao.stream/outcome (v2/descriptor writer-handle))))
      (is (= :dao.stream/ok (:dao.stream/outcome (v2/descriptor closable-handle))))
      (is (= :dao.stream/ok (:dao.stream/outcome (v2/descriptor full-handle)))))

    (testing "invoking undeclared surface operations fails at protocol dispatch"
      ;; Calling writer operation on reader-only handle throws protocol exception
      (is (thrown? #?(:clj IllegalArgumentException :cljs js/Error :cljd Object)
            (v2/append! reader-handle :val)))

      ;; Calling reader operation on writer-only handle throws protocol exception
      (is (thrown? #?(:clj IllegalArgumentException :cljs js/Error :cljd Object)
            (v2/next writer-handle {:position 0})))
      (is (thrown? #?(:clj IllegalArgumentException :cljs js/Error :cljd Object)
            (v2/cursor writer-handle :dao.stream/oldest)))

      ;; Calling closable operation on reader-only handle throws protocol exception
      (is (thrown? #?(:clj IllegalArgumentException :cljs js/Error :cljd Object)
            (v2/close! reader-handle))))))


(deftest host-dispatch-composition-test
  (testing "host dispatch is an ordinary host-composed map without ambient globals or registries"
    (let [created-handle :handle-created
          attached-handle :handle-attached
          dispatch-table {:test/ringbuffer
                          {:dao.stream/create (fn [spec]
                                                {:dao.stream/outcome :dao.stream/ok
                                                 :dao.stream/handle created-handle
                                                 :test/spec spec})
                           :dao.stream/attach (fn [desc]
                                                {:dao.stream/outcome :dao.stream/ok
                                                 :dao.stream/handle attached-handle
                                                 :test/desc desc})}
                          :test/writer-only
                          {:dao.stream/attach (fn [_desc]
                                                {:dao.stream/outcome :dao.stream/ok
                                                 :dao.stream/handle :writer-handle})}}]

      ;; Successful dispatch
      (let [res (v2/host-dispatch-create! dispatch-table {:dao.stream/type :test/ringbuffer
                                                          :ringbuffer/capacity 10})]
        (is (= :dao.stream/ok (:dao.stream/outcome res)))
        (is (= created-handle (:dao.stream/handle res))))

      (let [res (v2/host-dispatch-attach! dispatch-table {:dao.stream/type :test/ringbuffer
                                                          :dao.stream/identity "stream-1"})]
        (is (= :dao.stream/ok (:dao.stream/outcome res)))
        (is (= attached-handle (:dao.stream/handle res))))

      ;; Unknown transport returns :dao.stream/not-found
      (is (= {:dao.stream/outcome :dao.stream/not-found}
             (v2/host-dispatch-create! dispatch-table {:dao.stream/type :unknown/transport})))
      (is (= {:dao.stream/outcome :dao.stream/not-found}
             (v2/host-dispatch-attach! dispatch-table {:dao.stream/type :unknown/transport
                                                       :dao.stream/identity "id"})))

      ;; Missing create! on transport (e.g. writer-only attach transport) returns :dao.stream/not-found
      (is (= {:dao.stream/outcome :dao.stream/not-found}
             (v2/host-dispatch-create! dispatch-table {:dao.stream/type :test/writer-only})))

      ;; Malformed spec/descriptor returns :dao.stream/invalid-spec / :dao.stream/invalid-descriptor
      (is (= {:dao.stream/outcome :dao.stream/invalid-spec}
             (v2/host-dispatch-create! dispatch-table {:unqualified :kw})))
      (is (= {:dao.stream/outcome :dao.stream/invalid-descriptor}
             (v2/host-dispatch-attach! dispatch-table {:dao.stream/type :test/ringbuffer}))))))


(deftest conformance-manifest-validation-test
  (testing "valid manifest satisfies manifest validation"
    (let [valid-manifest
          {:dao.stream/type :test/transport
           :surfaces #{:reader :writer :closable}
           :operations
           {:create! {:produces #{:dao.stream/ok :dao.stream/invalid-spec}
                      :exclusions {:dao.stream/not-found "host dispatch handles absence"
                                   :dao.stream/transport-error "in-memory only"}}
            :attach! {:produces #{:dao.stream/ok :dao.stream/invalid-descriptor :dao.stream/not-found}
                      :exclusions {:dao.stream/transport-error "in-memory lookup has no failure"}}
            :descriptor {:produces #{:dao.stream/ok}
                         :exclusions {}}
            :cursor {:produces #{:dao.stream/ok :dao.stream/invalid-anchor :dao.stream/closed}
                     :exclusions {:dao.stream/transport-error "in-memory read only"}}
            :next {:produces #{:dao.stream/ok :dao.stream/blocked :dao.stream/end
                               :dao.stream/gap :dao.stream/cursor-mismatch :dao.stream/invalid-cursor}
                   :exclusions {:dao.stream/transport-error "in-memory read only"}}
            :append! {:produces #{:dao.stream/ok :dao.stream/closed}
                      :exclusions {:dao.stream/full "evicts oldest instead of refusing"
                                   :dao.stream/invalid-value "carries all host values"
                                   :dao.stream/transport-error "in-memory state has no failure"}}
            :close! {:produces #{:dao.stream/ok}
                     :exclusions {}}}}]
      (is (true? (:valid? (conf/validate-manifest valid-manifest))))))

  (testing "invalid manifest is rejected with detailed error records"
    ;; Missing descriptor declaration
    (let [m {:dao.stream/type :test/transport
             :surfaces #{:writer}
             :operations {:append! {:produces #{:dao.stream/ok :dao.stream/closed}
                                    :exclusions {:dao.stream/full "reason"
                                                 :dao.stream/invalid-value "reason"
                                                 :dao.stream/transport-error "reason"}}}}]
      (is (false? (:valid? (conf/validate-manifest m)))))

    ;; Missing exclusion reason
    (let [m {:dao.stream/type :test/transport
             :surfaces #{}
             :operations {:descriptor {:produces #{:dao.stream/ok} :exclusions {}}
                          :create! {:produces #{:dao.stream/ok}
                                    :exclusions {:dao.stream/invalid-spec "" ; Empty reason
                                                 :dao.stream/not-found "r"
                                                 :dao.stream/transport-error "r"}}}}]
      (is (false? (:valid? (conf/validate-manifest m)))))

    ;; Incomplete outcome partition
    (let [m {:dao.stream/type :test/transport
             :surfaces #{}
             :operations {:descriptor {:produces #{:dao.stream/ok} :exclusions {}}
                          :create! {:produces #{:dao.stream/ok}
                                    ;; missing :dao.stream/transport-error
                                    :exclusions {:dao.stream/invalid-spec "r"
                                                 :dao.stream/not-found "r"}}}}]
      (is (false? (:valid? (conf/validate-manifest m)))))))


(deftest conformance-harness-full-run-test
  (testing "conformance harness runs and passes all licensed law blocks on mock transport"
    (let [stream-id "conformance-stream"
          data (atom [:a :b])
          closed (atom false)
          handle (->MockFullHandle stream-id data closed)
          manifest
          {:dao.stream/type :mock/stream
           :surfaces #{:reader :writer :closable}
           :handle-factory (fn [] handle)
           :operations
           {:descriptor {:produces #{:dao.stream/ok} :exclusions {}}
            :cursor {:produces #{:dao.stream/ok :dao.stream/invalid-anchor}
                     :exclusions {:dao.stream/closed "not modeled in mock"
                                  :dao.stream/transport-error "in-memory only"}}
            :next {:produces #{:dao.stream/ok :dao.stream/blocked :dao.stream/end}
                   :exclusions {:dao.stream/gap "unbounded mock does not evict"
                                :dao.stream/cursor-mismatch "single stream mock"
                                :dao.stream/invalid-cursor "mock assumes valid cursors"
                                :dao.stream/transport-error "in-memory only"}}
            :append! {:produces #{:dao.stream/ok :dao.stream/closed}
                      :exclusions {:dao.stream/full "unbounded mock"
                                   :dao.stream/invalid-value "accepts all values"
                                   :dao.stream/transport-error "in-memory only"}}
            :close! {:produces #{:dao.stream/ok} :exclusions {}}}
           :fixtures
           {:descriptor
            {:dao.stream/ok (fn [] (v2/descriptor handle))}
            :cursor
            {:dao.stream/ok (fn [] (v2/cursor handle :dao.stream/oldest))
             :dao.stream/invalid-anchor (fn [] (v2/cursor handle :invalid-anchor))}
            :next
            {:dao.stream/ok (fn [] (v2/next handle {:stream-identity stream-id :position 0}))
             :dao.stream/blocked (fn [] (v2/next handle {:stream-identity stream-id :position 10}))
             :dao.stream/end (fn [] {:dao.stream/outcome :dao.stream/end})}
            :append!
            {:dao.stream/ok (fn [] (v2/append! handle :new-val))
             :dao.stream/closed (fn [] {:dao.stream/outcome :dao.stream/closed})}
            :close!
            {:dao.stream/ok (fn [] (v2/close! handle))}}}
          res (conf/run-conformance-suite manifest handle)]
      (is (true? (:passed? res)) (str "Conformance failed with: " (:failures res))))))


(deftest concurrency-oracle-linearizability-test
  (testing "linearizable history passes offline linearizability checking"
    (let [history [{:id 0 :op :append! :args [:val-1]
                    :result {:dao.stream/outcome :dao.stream/ok}
                    :start 1 :end 3}
                   {:id 1 :op :append! :args [:val-2]
                    :result {:dao.stream/outcome :dao.stream/ok}
                    :start 2 :end 4}
                   {:id 2 :op :next :args [{:stream-identity "stream-1" :position 0}]
                    :result {:dao.stream/outcome :dao.stream/ok
                             :dao.stream/value :val-1
                             :dao.stream/cursor {:stream-identity "stream-1" :position 1}}
                    :start 5 :end 7}]
          initial-model (conf/make-abstract-stream-model "stream-1")
          res (conf/check-linearizability history initial-model conf/abstract-stream-step)]
      (is (true? (:linearizable? res)))
      (is (= 3 (count (:linearization res))))))

  (testing "non-linearizable history (violating happens-before read-after-write) is rejected"
    ;; Here op0 is next observing :val-1, but op0 finished before op1 (which appends :val-1) was even invoked!
    (let [illegal-history [{:id 0 :op :next :args [{:stream-identity "stream-1" :position 0}]
                            :result {:dao.stream/outcome :dao.stream/ok
                                     :dao.stream/value :val-1
                                     :dao.stream/cursor {:stream-identity "stream-1" :position 1}}
                            :start 1 :end 2}
                           {:id 1 :op :append! :args [:val-1]
                            :result {:dao.stream/outcome :dao.stream/ok}
                            :start 3 :end 4}]
          initial-model (conf/make-abstract-stream-model "stream-1")
          res (conf/check-linearizability illegal-history initial-model conf/abstract-stream-step)]
      (is (false? (:linearizable? res)) "read observing future append cannot linearize")))

  (testing "non-linearizable history (append after close completed) is rejected"
    ;; op0 close! completed at t=2. op1 append! started at t=3 and returned :ok, which is illegal
    (let [illegal-history [{:id 0 :op :close! :args []
                            :result {:dao.stream/outcome :dao.stream/ok}
                            :start 1 :end 2}
                           {:id 1 :op :append! :args [:val]
                            :result {:dao.stream/outcome :dao.stream/ok}
                            :start 3 :end 4}]
          initial-model (conf/make-abstract-stream-model "stream-1")
          res (conf/check-linearizability illegal-history initial-model conf/abstract-stream-step)]
      (is (false? (:linearizable? res)) "append returning :ok after close cannot linearize"))))
