(ns dao.space.transactor-test
  "Contract tests for dao.space.transactor: a single-writer value over an
   explicit local dao.stream.v2 memory-log and an explicit DaoJing intake
   pool (docs/design/dao.jing.md, Publication from an agent).

   Every append!/transact! emits exactly ONE atomic transaction record
   {:dao.space/transaction {:t n :datoms [...]}} through exactly one local
   stream/append!; t is owned by the value and derived on create! from the
   retained history. publish! builds and enqueues the covered indexes into
   the intake pool."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.transactor :as transactor]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.memory-log :as memory-log]
            [dao.stream.v2.ringbuffer :as ringbuffer]))


;; ---------------------------------------------------------------------------
;; Test doubles
;; ---------------------------------------------------------------------------

(defrecord ReaderOnlyStream
  []

  stream/IDaoStreamReader

  (cursor [_this _anchor] {:dao.stream/outcome :dao.stream/invalid-anchor})


  (next [_this _cursor] {:dao.stream/outcome :dao.stream/blocked}))


(defrecord WriterOnlyStream
  []

  stream/IDaoStreamWriter

  (append! [_this _val] {:dao.stream/outcome :dao.stream/ok}))


(defrecord RecordingAppendStream
  [inner records]

  stream/IDaoStreamWriter

  (append! [_this val] (swap! records conj val) (stream/append! inner val))


  stream/IDaoStreamReader

  (cursor [_this anchor] (stream/cursor inner anchor))


  (next [_this cursor] (stream/next inner cursor)))


(defrecord FailingAppendStream
  [inner failures]

  stream/IDaoStreamWriter

  (append!
    [_this packet]
    (if (pos? (swap! failures dec))
      {:dao.stream/outcome :dao.stream/full}
      (stream/append! inner packet)))


  stream/IDaoStreamReader

  (cursor [_this anchor] (stream/cursor inner anchor))


  (next [_this cursor] (stream/next inner cursor)))


(defrecord ThrowingAppendStream
  [inner throw?]

  stream/IDaoStreamWriter

  (append!
    [_this val]
    (if @throw?
      (do (reset! throw? false)
          (throw (ex-info "simulated transport failure" {})))
      (stream/append! inner val)))


  stream/IDaoStreamReader

  (cursor [_this anchor] (stream/cursor inner anchor))


  (next [_this cursor] (stream/next inner cursor)))


(defrecord NonOutcomeAppendStream
  [inner garbage?]

  stream/IDaoStreamWriter

  (append!
    [_this val]
    (if @garbage?
      (do (reset! garbage? false)
          :boom)
      (stream/append! inner val)))


  stream/IDaoStreamReader

  (cursor [_this anchor] (stream/cursor inner anchor))


  (next [_this cursor] (stream/next inner cursor)))


;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- open-local
  "A complete-retention memory-log local (agent) stream."
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


(defn- open-intake
  "A dao.stream.v2 ringbuffer intake writer."
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity capacity})))


(defn- stream-values
  "Every value currently on a v2 stream, read from its oldest cursor."
  [s]
  (loop [cursor (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
         acc []]
    (let [r (stream/next s cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- intake-values
  "Every value currently on a v2 intake stream — the test's view of what
   publication enqueued."
  [s]
  (stream-values s))


(defn- open-with-intake
  ([local] (open-with-intake local (open-intake 4096)))
  ([local intake]
   (transactor/create! {:local-stream local,
                        :intake-pool [intake]})))


(defn- content-handle
  ([]
   (let [store (atom {})]
     {:store store,
      :put-content-fn (fn [address payload]
                        (if (contains? @store address)
                          :present
                          (do (swap! store assoc address payload) :inserted))),
      :get-content-fn (fn [address not-found]
                        (get @store address not-found))})))


(defn- materialize-through-observer
  "Drain v2 intake streams through a dao.jing observer into a fresh content
   store; returns the store. Draining runs until blocked or end; a gap or
   defect is fatal to this composition (E11)."
  [intakes]
  (let [h (content-handle)]
    (loop [st (jing/observer-state
                (mapv (fn [s]
                        {:stream s
                         :cursor (:dao.stream/cursor
                                   (stream/cursor s :dao.stream/oldest))})
                      intakes))]
      (let [r (jing/observe-step! h st)]
        (case (:signal r)
          :dao.stream/ok (recur (:state r))
          :dao.stream/blocked h
          :dao.stream/end h
          (throw (ex-info "test observer hit a gap or defect"
                          {:result r})))))))


(defn- tx-ts
  "The transaction times of every atomic record currently on a local stream."
  [local]
  (map (comp :t :dao.space/transaction) (stream-values local)))


(defn- local-still-open?
  "An open memory-log blocks at its tail; a closed one answers end. This is
   the observable difference now that v2 lists no closed? predicate."
  [local]
  (= :dao.stream/blocked
     (:dao.stream/outcome
       (stream/next local
                    (:dao.stream/cursor (stream/cursor local :dao.stream/newest))))))


;; ---------------------------------------------------------------------------
;; Spec validation
;; ---------------------------------------------------------------------------

(deftest spec-validation
  (let [intake (open-intake 4096)]
    (testing "a non-map spec throws the clean malformed-spec diagnostic"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"must be a map"
            (transactor/create! 42))))
    (testing "a missing or non-stream :local-stream throws"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"local-stream"
            (transactor/create! {:intake-pool [intake]})))
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"local-stream"
            (transactor/create! {:local-stream 42,
                                 :intake-pool [intake]})))
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"local-stream"
            (transactor/create! {:local-stream (->ReaderOnlyStream),
                                 :intake-pool [intake]})))
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"local-stream"
            (transactor/create! {:local-stream (->WriterOnlyStream),
                                 :intake-pool [intake]}))))
    (testing "the intake pool must be a non-empty collection of writers"
      (let [local (open-local)]
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"intake-pool"
              (transactor/create! {:local-stream local})))
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"non-empty"
              (transactor/create! {:local-stream local,
                                   :intake-pool []})))
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"intake-pool"
              (transactor/create! {:local-stream local,
                                   :intake-pool :nope})))
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"IDaoStreamWriter"
              (transactor/create! {:local-stream local,
                                   :intake-pool [(->ReaderOnlyStream)]})))
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"IDaoStreamWriter"
              (transactor/create! {:local-stream local,
                                   :intake-pool [42]})))))
    (testing ":name is optional and diagnostic only"
      (let [local (open-local)]
        (is (some? (transactor/create! {:local-stream local,
                                        :intake-pool [intake]})))
        (is (some? (transactor/create! {:local-stream local,
                                        :intake-pool [intake],
                                        :name "producer"})))))
    (testing ":next-t cannot override history-derived causality"
      (let [local (open-local)]
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"next-t"
              (transactor/create! {:local-stream local,
                                   :intake-pool [intake],
                                   :next-t 99})))))
    (testing "create! creates, registers, or closes nothing"
      (let [local (open-local)
            pool (open-intake 4096)]
        (transactor/create! {:local-stream local,
                             :intake-pool [pool]})
        (is (local-still-open? local)
            "create! did not close the supplied local stream")
        (is (empty? (stream-values local)))
        (is (empty? (intake-values pool)))
        (is (= :dao.stream/ok
               (:dao.stream/outcome (stream/append! pool ::probe)))
            "the intake pool was neither closed nor written by the open")))))


;; ---------------------------------------------------------------------------
;; Transaction time derivation
;; ---------------------------------------------------------------------------

(deftest reopen-derives-next-t-from-retained-history
  (let [local (open-local)
        intake (open-intake 4096)
        a (transactor/create! {:local-stream local,
                               :intake-pool [intake]})]
    (is (= {:dao.stream/outcome :dao.stream/ok,
            :dao.space/t 0,
            :dao.space/datoms [[1 :test/a 1 0 1]]}
           (transactor/append! a {:db/id 1, :test/a 1})))
    (transactor/transact! a [{:db/id 2, :test/b 2} {:db/id 3, :test/c 3}])
    (testing "a reopened wrapper derives 1 + max retained datom t"
      (let [b (transactor/create! {:local-stream local,
                                   :intake-pool [intake]})]
        (is (= {:dao.stream/outcome :dao.stream/ok,
                :dao.space/t 2,
                :dao.space/datoms [[4 :test/d 4 2 1]]}
               (transactor/append! b {:db/id 4, :test/d 4})))
        (is (= #{0 1 2} (set (tx-ts local)))
            "the scan read, and did not consume, the retained history")))
    (testing "an empty local stream derives 0"
      (let [fresh (open-local)
            c (transactor/create! {:local-stream fresh,
                                   :intake-pool [intake]})]
        (is (= {:dao.stream/outcome :dao.stream/ok,
                :dao.space/t 0,
                :dao.space/datoms [[1 :test/a 1 0 1]]}
               (transactor/append! c {:db/id 1, :test/a 1})))))))


(deftest malformed-retained-history-throws
  (let [intake (open-intake 4096)
        open-on (fn [local]
                  (transactor/create! {:local-stream local,
                                       :intake-pool [intake]}))]
    (testing "a malformed payload in the retained history throws on open"
      (let [local (open-local)]
        (stream/append! local 42)
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"payload"
              (open-on local)))))
    (testing "an out-of-shape transaction record throws on open"
      (let [local (open-local)]
        (stream/append! local {:dao.space/transaction {:t 1, :datoms :nope}})
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"transaction"
              (open-on local)))))
    (testing "a non-integer datom t in the retained history throws on open"
      (let [local (open-local)]
        (stream/append! local [1 :test/a :v :bogus 1])
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"integer"
              (open-on local)))))))


;; ---------------------------------------------------------------------------
;; Atomic packet shape
;; ---------------------------------------------------------------------------

(deftest each-transaction-is-exactly-one-append
  (let [local (open-local)
        recording (->RecordingAppendStream local (atom []))
        log (open-with-intake recording)]
    (transactor/append! log {:db/id 1, :test/a 1})
    (transactor/transact! log [{:db/id 2, :test/b 2} {:db/id 3, :test/c 3}])
    (is
      (= [{:dao.space/transaction {:t 0, :datoms [[1 :test/a 1 0 1]]}}
          {:dao.space/transaction
           {:t 1, :datoms [[2 :test/b 2 1 1] [3 :test/c 3 1 1]]}}]
         @(:records recording))
      "two transactions, exactly two underlying stream append calls — no torn
         packet, no extra calls")
    (is (= 2 (count @(:records recording))))))


(deftest readers-observe-atomic-transaction-records
  (let [local (open-local)
        log (open-with-intake local)]
    (transactor/append! log {:db/id 1, :test/a 1})
    (transactor/transact! log [{:db/id 2, :test/b 2}])
    (let [c0 (:dao.stream/cursor (stream/cursor local :dao.stream/oldest))
          r1 (stream/next local c0)
          r2 (stream/next local (:dao.stream/cursor r1))]
      (is (= {:dao.space/transaction {:t 0, :datoms [[1 :test/a 1 0 1]]}}
             (:dao.stream/value r1)))
      (is (= {:dao.space/transaction {:t 1, :datoms [[2 :test/b 2 1 1]]}}
             (:dao.stream/value r2)))
      (is (= :dao.stream/blocked
             (:dao.stream/outcome (stream/next local (:dao.stream/cursor r2)))))
      (is (= [{:dao.space/transaction {:t 0, :datoms [[1 :test/a 1 0 1]]}}
              {:dao.space/transaction {:t 1, :datoms [[2 :test/b 2 1 1]]}}]
             (stream-values local))
          "readers of the local stream see the same atomic records"))))


(deftest concurrent-calls-on-one-wrapper-serialize-transaction-time
  #?(:clj (let [inner (open-local)
                slow-local
                (reify
                  stream/IDaoStreamWriter
                  (append! [_ val] (Thread/sleep 5) (stream/append! inner val))


                  stream/IDaoStreamReader

                  (cursor [_ anchor] (stream/cursor inner anchor))

                  (next [_ cursor] (stream/next inner cursor)))
                log (open-with-intake slow-local)
                start (promise)
                calls (mapv (fn [i]
                              (future @start
                                      (transactor/append! log
                                                          {:db/id (+ 16 i),
                                                           :test/value i})))
                            (range 32))]
            (deliver start true)
            (run! deref calls)
            (is (= (set (range 32)) (set (tx-ts inner)))
                "one wrapper is one serialized writer even when callers race"))
     :default (is true
                  "shared-memory concurrency is a JVM-only execution mode")))


;; ---------------------------------------------------------------------------
;; Entity expansion
;; ---------------------------------------------------------------------------

(deftest append-expands-entities-and-datoms
  (let [local (open-local)
        log (open-with-intake local)]
    (testing "an entity map with :db/id expands to one datom per attribute"
      (let [{:keys [dao.stream/outcome dao.space/t dao.space/datoms]}
            (transactor/append! log {:db/id 100, :work/posted true, :work/task "x"})]
        (is (= :dao.stream/ok outcome))
        (is (= 0 t))
        (is (= 2 (count datoms)))
        (is (= #{[100 :work/posted true 0 1] [100 :work/task "x" 0 1]}
               (set datoms)))))
    (testing "an entity map without :db/id throws"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #":db/id"
            (transactor/append! log {:work/posted true}))))
    (testing "persistent datom coordinates and attributes are validated"
      (doseq [invalid [{:db/id :entity, :work/posted true}
                       {:db/id -16, :work/posted true}
                       {:db/id 100, :unqualified true}
                       {:db/id 100, "work/posted" true} [100 "work/posted" true]
                       [100 :work/posted true nil :db/assert]]]
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"datom"
              (transactor/append! log invalid))))
      (is (= 1 (count (stream-values local)))
          "invalid inputs append nothing and do not advance transaction time"))
    (testing
      "a [e a v] datom vector is padded and stamped, m defaults to assert"
      (is (= {:dao.stream/outcome :dao.stream/ok,
              :dao.space/t 1,
              :dao.space/datoms [[1 :test/a 1 1 1]]}
             (transactor/append! log [1 :test/a 1]))))
    (testing "an explicit m is preserved"
      (is (= {:dao.stream/outcome :dao.stream/ok,
              :dao.space/t 2,
              :dao.space/datoms [[2 :test/b 2 2 0]]}
             (transactor/append! log [2 :test/b 2 nil 0]))))
    (testing "an explicit datom t is rejected and does not advance t"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"t"
            (transactor/append! log [4 :test/d 4 9])))
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"t"
            (transactor/append! log [4 :test/d 4 9 1])))
      (is (= {:dao.stream/outcome :dao.stream/ok,
              :dao.space/t 3,
              :dao.space/datoms [[5 :test/e 5 3 1]]}
             (transactor/append! log [5 :test/e 5]))))
    (testing "a value that is neither entity map nor datom vector throws"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"entity map or datom"
            (transactor/append! log 42))))
    (testing "an input that produces zero datoms is rejected"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"no datoms"
            (transactor/append! log {:db/id 1}))))))


;; ---------------------------------------------------------------------------
;; Failure handling
;; ---------------------------------------------------------------------------

(deftest append-failure-does-not-advance-t-and-can-retry
  (let [local (open-local)
        flaky (->FailingAppendStream local (atom 2))
        log (open-with-intake flaky)]
    (is (= {:dao.stream/outcome :dao.stream/full}
           (transactor/append! log {:db/id 1, :test/a 1}))
        "a refused local append is returned as data, not thrown")
    (is (= {:dao.stream/outcome :dao.stream/ok,
            :dao.space/t 0,
            :dao.space/datoms [[1 :test/a 1 0 1]]}
           (transactor/append! log {:db/id 1, :test/a 1}))
        "retry lands at the same t: the failed append never advanced it")
    (is (= {:dao.stream/outcome :dao.stream/ok,
            :dao.space/t 1,
            :dao.space/datoms [[2 :test/b 2 1 1]]}
           (transactor/append! log {:db/id 2, :test/b 2})))))


(deftest append-throw-does-not-advance-t-and-can-retry
  (let [local (open-local)
        throwing (->ThrowingAppendStream local (atom true))
        log (open-with-intake throwing)]
    (is (thrown? #?(:cljd Object
                    :clj Exception
                    :cljs js/Error)
          (transactor/append! log {:db/id 1, :test/a 1})))
    (is (= {:dao.stream/outcome :dao.stream/ok,
            :dao.space/t 0,
            :dao.space/datoms [[1 :test/a 1 0 1]]}
           (transactor/append! log {:db/id 1, :test/a 1}))
        "a thrown underlying error leaves the watermark unchanged")))


(deftest append-folds-a-non-outcome-answer-to-transport-error
  (let [local (open-local)
        garbage (->NonOutcomeAppendStream local (atom true))
        log (open-with-intake garbage)]
    (is (= {:dao.stream/outcome :dao.stream/transport-error
            :dao.stream/answer :boom}
           (transactor/append! log {:db/id 1, :test/a 1}))
        "a local append answering a non-outcome is folded to transport-error
           with the raw answer retained")
    (is (= {:dao.stream/outcome :dao.stream/ok,
            :dao.space/t 0,
            :dao.space/datoms [[1 :test/a 1 0 1]]}
           (transactor/append! log {:db/id 1, :test/a 1}))
        "the fold did not advance the watermark: the retry commits at the
           original t")))


;; ---------------------------------------------------------------------------
;; transact!
;; ---------------------------------------------------------------------------

(deftest transact-commits-one-atomic-record
  (let [local (open-local)
        log (open-with-intake local)]
    (testing "several items commit under one t in one atomic record"
      (let [{:keys [dao.stream/outcome dao.space/t dao.space/datoms]}
            (transactor/transact! log
                                  [{:db/id 100, :test/a 1}
                                   {:db/id 101, :test/b 2} [102 :test/c 3]])]
        (is (= :dao.stream/ok outcome))
        (is (= 0 t))
        (is (= 3 (count datoms)))
        (is (apply = (map #(nth % 3) datoms))
            "every datom in the batch shares one t")
        (is (= [[100 :test/a 1 0 1] [101 :test/b 2 0 1] [102 :test/c 3 0 1]]
               datoms)))
      (is (= [{:dao.space/transaction {:t 0,
                                       :datoms [[100 :test/a 1 0 1]
                                                [101 :test/b 2 0 1]
                                                [102 :test/c 3 0 1]]}}]
             (stream-values local))
          "exactly one atomic packet on the stream"))
    (testing "a transact! after an append! gets a distinct t"
      (transactor/append! log {:db/id 1, :test/a 1})
      (is (= {:dao.stream/outcome :dao.stream/ok,
              :dao.space/t 2,
              :dao.space/datoms [[2 :test/b 2 2 1]]}
             (transactor/transact! log [{:db/id 2, :test/b 2}]))))
    (testing "an empty tx-data collection throws"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"at least one"
            (transactor/transact! log []))))
    (testing "an expansion producing zero datoms throws"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"no datoms"
            (transactor/transact! log [{:db/id 1}]))))
    (testing "an invalid item throws before any append — no partial prefix"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"entity map or datom"
            (transactor/transact! log
                                  [{:db/id 1, :test/a 1} 42])))
      (is (= 3 (count (stream-values local)))
          "the invalid batch appended nothing"))))


;; ---------------------------------------------------------------------------
;; Close ownership
;; ---------------------------------------------------------------------------

(deftest close-is-per-handle-and-does-not-touch-local-stream
  (let [local (open-local)
        intake (open-intake 4096)
        log (transactor/create! {:local-stream local,
                                 :intake-pool [intake]})]
    (transactor/append! log {:db/id 1, :test/a 1})
    (is (= {:dao.stream/outcome :dao.stream/ok} (transactor/close! log)))
    (is (= {:dao.stream/outcome :dao.stream/ok} (transactor/close! log))
        "close! is idempotent")
    (is (= {:dao.stream/outcome :dao.stream/closed}
           (transactor/append! log {:db/id 2, :test/a 2}))
        "append! after close answers closed as data, never throws")
    (is (= {:dao.stream/outcome :dao.stream/closed}
           (transactor/transact! log [{:db/id 2, :test/a 2}]))
        "transact! after close answers closed as data, never throws")
    (is (local-still-open? local)
        "closing the wrapper must not close the supplied local stream")
    (is (= :dao.stream/ok
           (:dao.stream/outcome (stream/append! intake ::probe)))
        "closing the wrapper must not close the supplied intake pool")
    (is (= [{:dao.space/transaction {:t 0, :datoms [[1 :test/a 1 0 1]]}}]
           (stream-values local))
        "closing neither closes nor erases the local stream, and reads of it
           still work after close — reads were always the caller's own")))


(deftest close-linearizes-after-an-in-flight-append
  #?(:clj (let [inner (open-local)
                entered (promise)
                release (promise)
                slow-local (reify
                             stream/IDaoStreamWriter
                             (append!
                               [_ packet]
                               (deliver entered true)
                               @release
                               (stream/append! inner packet))


                             stream/IDaoStreamReader

                             (cursor [_ anchor] (stream/cursor inner anchor))

                             (next [_ cursor] (stream/next inner cursor)))
                log (open-with-intake slow-local)
                writing (future (transactor/append! log {:db/id 1, :test/a 1}))]
            @entered
            (let [closing (future (transactor/close! log))]
              (try (is (= ::timeout (deref closing 50 ::timeout))
                       "close waits for the serialized write boundary")
                   (finally (deliver release true)))
              (is (= {:dao.stream/outcome :dao.stream/ok,
                      :dao.space/t 0,
                      :dao.space/datoms [[1 :test/a 1 0 1]]}
                     @writing))
              (is (= {:dao.stream/outcome :dao.stream/ok} @closing))
              (is (= {:dao.stream/outcome :dao.stream/closed}
                     (transactor/append! log {:db/id 2, :test/a 2})))
              (is (= 1 (count (stream-values inner)))
                  "no append crosses the point at which close returns")))
     :default (is true
                  "shared-memory close races are a JVM-only execution mode")))


;; ---------------------------------------------------------------------------
;; Single-writer
;; ---------------------------------------------------------------------------

(deftest single-writer-wrappers-are-not-coordinated
  (testing
    "two wrappers over the same local stream each derive the same
            next-t and silently write colliding records: documented hazard,
            no coordination is possible without shared mutable state"
    (let [local (open-local)
          intake (open-intake 4096)
          a (transactor/create! {:local-stream local,
                                 :intake-pool [intake]})
          b (transactor/create! {:local-stream local,
                                 :intake-pool [intake]})]
      (is (= {:dao.stream/outcome :dao.stream/ok,
              :dao.space/t 0,
              :dao.space/datoms [[1 :test/a 1 0 1]]}
             (transactor/append! a {:db/id 1, :test/a 1})))
      (is (= {:dao.stream/outcome :dao.stream/ok,
              :dao.space/t 0,
              :dao.space/datoms [[2 :test/b 2 0 1]]}
             (transactor/append! b {:db/id 2, :test/b 2}))
          "wrapper b independently derived t=0 from the same history")
      (is (= #{0} (set (tx-ts local)))
          "two colliding records at t=0, not a sequential log"))))


;; ---------------------------------------------------------------------------
;; publish!
;; ---------------------------------------------------------------------------

(deftest publish-enqueues-indexes-into-the-pool
  (let [local (open-local)
        a (open-intake 1024)
        b (open-intake 1024)
        log (transactor/create! {:local-stream local,
                                 :intake-pool [a b]})]
    (transactor/append! log {:db/id 1, :work/status :todo})
    (transactor/append! log {:db/id 2, :work/status :done})
    (testing "publish! returns the manifest and its content address"
      (let [{:keys [manifest-address manifest]} (transactor/publish! log)]
        (is (= "segment" (namespace manifest-address)))
        (is (= manifest-address (jing/segment-key manifest)))
        (is (= #{:indexes :count :branching-factor} (set (keys manifest))))
        (is (= 2 (:count manifest)))
        (is (seq (intake-values a)) "the default :select-stream is first")
        (is (empty? (intake-values b)))))
    (testing "publication touches no stream lifecycle"
      (is (local-still-open? local))
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! a ::probe)))
          "intake a was not closed")
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! b ::probe)))
          "intake b was not closed"))
    (testing "opts route through: :select-stream picks the pool member"
      (let [{:keys [manifest]} (transactor/publish! log
                                                    {:select-stream second})]
        (is (= 2 (:count manifest)))
        (is (seq (intake-values b)))))
    (testing "opts route through: :branching-factor"
      (let [{:keys [manifest]} (transactor/publish! log {:branching-factor 16})]
        (is (= 16 (:branching-factor manifest)))))
    (testing "publish! does not consume the local stream"
      (is (= 2 (count (stream-values local)))))))


(deftest publish-materializes-through-observer-and-reads-back
  (let [local (open-local)
        intake (open-intake 1024)
        log (open-with-intake local intake)]
    (transactor/append! log {:db/id 1, :work/status :todo})
    (transactor/append! log {:db/id 2, :work/status :done})
    (let [{:keys [manifest-address manifest]} (transactor/publish! log)
          store (materialize-through-observer [intake])]
      (is (= manifest (index/read-manifest store manifest-address)))
      (is (= (set [[1 :work/status :todo 0 1] [2 :work/status :done 1 1]])
             (set (index/read-datoms store manifest-address)))
          "observer materialization makes the published datoms readable"))
    (testing "an empty local stream publishes an empty manifest"
      (let [local2 (open-local)
            intake2 (open-intake 1024)
            log2 (open-with-intake local2 intake2)
            {:keys [manifest-address manifest]} (transactor/publish! log2)
            store (materialize-through-observer [intake2])]
        (is (zero? (:count manifest)))
        (is (= [] (index/read-datoms store manifest-address)))))))
