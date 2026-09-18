(ns dao.stream.memory-log-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.conformance :as conformance]
            [dao.stream.memory-log :as log]))


(def spec
  {:dao.stream/type log/transport-type})


(defn handle
  []
  (:dao.stream/handle (log/create! spec)))


(defn cur
  [h a]
  (:dao.stream/cursor (stream/cursor h a)))


(def ^:private retained-values
  "Every other value is nil.  nil is a legitimate stream value here, and
   a replay loop that treats it as end-of-input fails this suite's
   exactness check at element zero."
  (mapv #(when (odd? %) %) (range 1000)))


(defn- replay
  "Follow a cursor to its terminus, bounded so a defect cannot hang the
   suite.  Termination is the outcome, never the value: nil values are
   conj'd like any other."
  [h cursor limit]
  (loop [c cursor n 0 vs [] seen #{}]
    (if (> n limit)
      {:values vs :terminal ::did-not-terminate :outcomes seen}
      (let [r (stream/next h c)
            o (:dao.stream/outcome r)]
        (if (= o :dao.stream/ok)
          (recur (:dao.stream/cursor r)
                 (inc n)
                 (conj vs (:dao.stream/value r))
                 (conj seen o))
          {:values vs :terminal o :outcomes (conj seen o)})))))


(defn- replay-limit
  []
  (+ (count retained-values) 8))


(deftest creation-spec-rule
  (let [invalid-spec? #(= :dao.stream/invalid-spec
                          (:dao.stream/outcome (log/create! %)))]
    (testing "rejections"
      (is (invalid-spec? nil) "not a map")
      (is (invalid-spec? 42) "not a map")
      (is (invalid-spec? {}) "missing :dao.stream/type")
      (is (invalid-spec? {:dao.stream/type :dao.stream/ringbuffer})
          "wrong transport type")
      (is (invalid-spec? (assoc spec :dao.stream.memory-log/capacity 3))
          "a key in the transport's own namespace names a knob that does not exist")
      (is (invalid-spec? (assoc spec :capacity 3))
          "an unqualified key is a malformed spec, not a foreign extension")
      (is (invalid-spec? (assoc spec "capacity" 3))
          "a non-keyword key is a malformed spec"))
    (testing "foreign qualified keys are ignored per the open-map rule"
      (is (= :dao.stream/ok
             (:dao.stream/outcome
               (log/create! (assoc spec :my.host/label "x"))))))))


(deftest complete-retention
  (let [h (handle)
        ;; The origin cursor is minted BEFORE any append: on an evicting
        ;; transport this is the only cursor that converts silent loss
        ;; into a reported gap.  Here it must simply stay at the origin.
        origin (cur h :dao.stream/oldest)
        appends (mapv #(stream/append! h %) retained-values)]
    (is (= #{:dao.stream/ok} (set (map :dao.stream/outcome appends)))
        "every append on the unbounded log is accepted")
    (is (= 1000 (count retained-values)))
    (let [limit (replay-limit)
          kept (replay h origin limit)
          ;; A fresh :oldest is the origin on a complete-history
          ;; transport, however late the consumer arrives.  The
          ;; representation is ours, so structural equality is assertable
          ;; here.
          fresh-cursor (cur h :dao.stream/oldest)
          fresh (replay h fresh-cursor limit)]
      (is (= origin fresh-cursor)
          "a freshly minted :oldest is structurally the origin cursor")
      (is (= retained-values (:values kept))
          "the kept origin cursor replays the history exactly and in order")
      (is (= retained-values (:values fresh))
          "a fresh :oldest replays the whole history exactly and in order")
      (is (= :dao.stream/blocked (:terminal kept)) "the open tail blocks")
      (is (= :dao.stream/blocked (:terminal fresh)))
      (is (not (contains? (:outcomes kept) :dao.stream/gap)))
      (is (not (contains? (:outcomes fresh) :dao.stream/gap))))
    ;; Close changes future availability only: both replays still work,
    ;; now terminating with end, and neither observes gap.
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/close! h))))
    (let [limit (replay-limit)
          kept (replay h origin limit)
          fresh (replay h (cur h :dao.stream/oldest) limit)]
      (is (= retained-values (:values kept)))
      (is (= :dao.stream/end (:terminal kept)))
      (is (not (contains? (:outcomes kept) :dao.stream/gap)))
      (is (= retained-values (:values fresh)))
      (is (= :dao.stream/end (:terminal fresh)))
      (is (not (contains? (:outcomes fresh) :dao.stream/gap)))
      (is (= :dao.stream/closed
             (:dao.stream/outcome (stream/append! h :after)))))))


(deftest next-is-total
  ;; Every cursor below the fabricated ones is constructed by hand:
  ;; consumers never fabricate cursor internals, but totality must hold
  ;; anyway — a negative position reaching nth throws on the JVM rather
  ;; than answering.  No host conditional: all three hosts run this.
  (let [h (handle)
        c (cur h :dao.stream/oldest)
        id (:dao.stream.memory-log/identity c)
        fabricate (fn [position]
                    {:dao.stream.memory-log/identity id
                     :dao.stream.memory-log/position position})
        outcome (fn [cursor] (:dao.stream/outcome (stream/next h cursor)))
        invalid-cursor? #(= :dao.stream/invalid-cursor %)]
    (is (= :dao.stream/blocked (outcome c)) "empty tail blocks before any append")
    (stream/append! h :only)
    (testing "not a cursor at all"
      (is (invalid-cursor? (outcome nil)))
      (is (invalid-cursor? (outcome 42)))
      (is (invalid-cursor? (outcome "cursor"))))
    (testing "map-shaped but malformed"
      (is (invalid-cursor? (outcome {})) "no identity key")
      (is (invalid-cursor? (outcome {:dao.stream.memory-log/position 0}))
          "missing identity key"))
    (testing "fabricated positions with the right identity"
      (is (invalid-cursor? (outcome (fabricate nil))) "no position")
      (is (invalid-cursor? (outcome (fabricate -1))) "negative")
      (is (invalid-cursor? (outcome (fabricate 1.5))) "non-integer")
      (is (invalid-cursor? (outcome (fabricate :pos))) "non-integer")
      (is (invalid-cursor? (outcome (fabricate "1"))) "non-integer")
      (is (invalid-cursor? (outcome (fabricate 2))) "beyond the tail")
      (is (invalid-cursor? (outcome (fabricate 1000000))) "far beyond the tail"))
    (testing "positions the stream itself can mint"
      (is (= :dao.stream/ok (outcome c)) "position 0 holds the appended value")
      (is (= :dao.stream/blocked (outcome (fabricate 1)))
          "at the tail of an open stream")
      (is (= :dao.stream/blocked (outcome (cur h :dao.stream/newest)))))
    (testing "a foreign logical-stream identity"
      (is (= :dao.stream/cursor-mismatch
             (outcome (cur (handle) :dao.stream/oldest)))))
    (testing "after close the tail answers end and mints still work"
      (stream/close! h)
      (is (= :dao.stream/end (outcome (fabricate 1))))
      (is (= :dao.stream/end (outcome (cur h :dao.stream/newest))))
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/cursor h :dao.stream/oldest)))
          "an owner handle keeps minting cursors after close")
      (is (invalid-cursor? (outcome (fabricate 2)))
          "beyond-tail is still invalid after close"))))


(def memory-log-manifest
  "The complete-retention transport's outcome declaration.  Every fixture
   operates a fresh real handle; it never fabricates an outcome map.  The
   exclusions are proof obligations discharged in this namespace: gap by
   complete-retention (complete-retention and next-is-total never observe
   one), full by unboundedness (every append answers ok), transport-error
   by the completes-or-does-not-return shape of every operation, and the
   rest by the transport's structure."
  {:dao.stream/type log/transport-type
   :surfaces #{:reader :writer :closable}
   :handle-factory handle
   :operations
   {:create!
    {:produces #{:dao.stream/ok :dao.stream/invalid-spec}
     :exclusions
     {:dao.stream/not-found
      "host dispatch selects the transport; a call reaching this handler has already matched it"
      :dao.stream/transport-error
      "creation allocates one in-memory state value: it either completes and returns, or does not return"}}

    :descriptor {:produces #{:dao.stream/ok} :exclusions {}}

    :cursor
    {:produces #{:dao.stream/ok :dao.stream/invalid-anchor}
     :exclusions
     {:dao.stream/closed
      "there are no attachments: every handle is the logical stream's owner, and an owner mints cursors after close so retained history stays readable"
      :dao.stream/transport-error
      "minting reads one in-memory state value and has no failure it could observe and return from"}}

    :next
    {:produces #{:dao.stream/ok :dao.stream/blocked :dao.stream/end
                 :dao.stream/cursor-mismatch :dao.stream/invalid-cursor}
     :exclusions
     {:dao.stream/gap
      "retention is complete: no position is ever dropped, so no cursor can point at one that was"
      :dao.stream/transport-error
      "a read is one indexed lookup into retained state; there is no failure it could observe and return from"}}

    :append!
    {:produces #{:dao.stream/ok :dao.stream/closed}
     :exclusions
     {:dao.stream/full
      "no capacity is declared: the transport is logically unbounded and refuses nothing"
      :dao.stream/invalid-value
      "the log holds host values by reference and encodes nothing, so no value is uncarriable"
      :dao.stream/transport-error
      "the append either completes its one state transition and returns, or does not return"}}

    :close! {:produces #{:dao.stream/ok} :exclusions {}}}

   :fixtures
   {:create! {:dao.stream/ok #(log/create! spec)
              :dao.stream/invalid-spec
              ;; The transport-namespace-key case, which is the rule the
              ;; manifest cannot state on its own; the other rejection
              ;; cases are covered by creation-spec-rule.
              #(log/create! (assoc spec :dao.stream.memory-log/capacity 1024))}
    :descriptor {:dao.stream/ok #(stream/descriptor (handle))}
    :cursor {:dao.stream/ok #(stream/cursor (handle) :dao.stream/oldest)
             :dao.stream/invalid-anchor #(stream/cursor (handle) ::invalid-anchor)}
    :next {:dao.stream/ok #(let [h (handle) c (cur h :dao.stream/oldest)]
                             (stream/append! h :value)
                             (stream/next h c))
           :dao.stream/blocked #(let [h (handle)]
                                  (stream/next h (cur h :dao.stream/newest)))
           :dao.stream/end #(let [h (handle) c (cur h :dao.stream/newest)]
                              (stream/close! h)
                              (stream/next h c))
           :dao.stream/cursor-mismatch #(let [l (handle) r (handle)]
                                          (stream/next r (cur l :dao.stream/oldest)))
           :dao.stream/invalid-cursor #(stream/next (handle) nil)}
    :append! {:dao.stream/ok #(stream/append! (handle) :value)
              :dao.stream/closed #(let [h (handle)]
                                    (stream/close! h)
                                    (stream/append! h :value))}
    :close! {:dao.stream/ok #(stream/close! (handle))}}})


(deftest memory-log-manifest-is-valid
  (let [v (conformance/validate-manifest memory-log-manifest)]
    (is (:valid? v) (str "Manifest validation errors: " (:errors v)))))


(deftest memory-log-conformance-test
  (let [result (conformance/run-conformance-suite memory-log-manifest)]
    (is (:passed? result) (str "Conformance failures: " (:failures result)))))


;; =============================================================================
;; Bounded linearizability histories
;; =============================================================================

(def ^:private log-projector
  {:cursor-projector {:identity :dao.stream.memory-log/identity
                      :position :dao.stream.memory-log/position}})


(defn- log-model
  "The abstract model for one memory log: capacity nil, no attachments,
   every handle the logical stream's owner."
  [stream-identity]
  (conformance/make-abstract-stream-model
    stream-identity nil [] log-projector))


(defn- linearizes?
  [history model]
  (conformance/check-linearizability history model conformance/abstract-stream-step))


(deftest memory-log-projector-linearizability-test
  (let [h (handle)
        stream-identity (:dao.stream/identity (stream/descriptor h))
        cursor (cur h :dao.stream/oldest)
        history [{:id 0 :op :append! :args [:value]
                  :result (stream/append! h :value) :start 1 :end 2}
                 {:id 1 :op :next :args [cursor]
                  :result (stream/next h cursor) :start 3 :end 4}]
        result (linearizes? history (log-model stream-identity))]
    (is (:linearizable? result) (str "Expected memory-log history to linearize: " result))))


#?(:cljd nil
   :clj
   (defn- run-concurrently
     "Run each thunk on its own thread against one shared history and clock.
      Every thunk receives `[history clock]` and records its own invocation
      interval with `conformance/record-op!`.  A caller may supply a history
      already holding earlier, strictly ordered operations.  Duplicated from
      ringbuffer_test rather than promoted: consolidating the copies is a
      separate cleanup."
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
             result (linearizes? history (log-model stream-identity))]
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
             result (linearizes? history (log-model stream-identity))]
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
             result (linearizes? history (log-model stream-identity))]
         (is (:linearizable? result) (str history " " result))))

     (testing "two cursors read concurrently after one completed append"
       ;; Reader independence on one owner handle: the append completes
       ;; first; the two reads are concurrent with each other.
       (let [h (handle)
             stream-identity (:dao.stream/identity (stream/descriptor h))
             left (cur h :dao.stream/oldest)
             right (cur h :dao.stream/oldest)
             hist (atom [])
             clock (atom 0)
             _ (conformance/record-op! hist clock :append! [:v]
                                       #(stream/append! h :v))
             history (run-concurrently
                       hist clock
                       [(fn [hh cc]
                          (conformance/record-op! hh cc :next [left]
                                                  #(stream/next h left)))
                        (fn [hh cc]
                          (conformance/record-op! hh cc :next [right]
                                                  #(stream/next h right)))])
             result (linearizes? history (log-model stream-identity))]
         (is (:linearizable? result) (str history " " result))))))
