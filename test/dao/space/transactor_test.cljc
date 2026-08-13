(ns dao.space.transactor-test
  "Contract tests for the :transactor stream (dao.space.transactor): a
   single-writer wrapper over an explicit local dao.stream and an explicit
   DaoJing intake pool (docs/design/dao.jing.md, Publication from an agent).

   Every append!/transact! emits exactly ONE atomic transaction record
   {:dao.space/transaction {:t n :datoms [...]}} through exactly one
   ds/append!; t is owned by the wrapper and derived on open from the
   retained history. publish! builds and enqueues the covered indexes into
   the intake pool."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.transactor :as transactor]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]))


;; ---------------------------------------------------------------------------
;; Test doubles
;; ---------------------------------------------------------------------------

(defrecord ReaderOnlyStream
  []

  ds/IDaoStreamReader

  (next [_this _cursor] :blocked))


(defrecord WriterOnlyStream
  []

  ds/IDaoStreamWriter

  (append! [_this _val] {:result :ok}))


(defrecord RecordingAppendStream
  [inner records]

  ds/IDaoStreamWriter

  (append! [_this val] (swap! records conj val) (ds/append! inner val))


  ds/IDaoStreamReader

  (next [_this cursor] (ds/next inner cursor))


  ds/IDaoStreamBound

  (close! [_this] (ds/close! inner))


  (closed? [_this] (ds/closed? inner)))


(defrecord FailingAppendStream
  [inner failures]

  ds/IDaoStreamWriter

  (append!
    [_this packet]
    (if (pos? (swap! failures dec))
      {:result :full}
      (ds/append! inner packet)))


  ds/IDaoStreamReader

  (next [_this cursor] (ds/next inner cursor))


  ds/IDaoStreamBound

  (close! [_this] (ds/close! inner))


  (closed? [_this] (ds/closed? inner)))


(defrecord ThrowingAppendStream
  [inner throw?]

  ds/IDaoStreamWriter

  (append!
    [_this _val]
    (if @throw?
      (do (reset! throw? false)
          (throw (ex-info "simulated transport failure" {})))
      (ds/append! inner val)))


  ds/IDaoStreamReader

  (next [_this cursor] (ds/next inner cursor))


  ds/IDaoStreamBound

  (close! [_this] (ds/close! inner))


  (closed? [_this] (ds/closed? inner)))


;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- open-with-intake
  ([local] (open-with-intake local (ds/open! {:type :ringbuffer})))
  ([local intake]
   (ds/open! {:type :transactor, :local-stream local, :intake-pool [intake]})))


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
  "Drain intake streams through a dao.jing observer into a fresh content
   store; returns the store."
  [intakes]
  (let [h (content-handle)]
    (loop [st (jing/observer-state intakes)]
      (let [r (jing/observe-step! h st)]
        (case (:signal r)
          :ok (recur (:state r))
          :blocked h
          :end h
          :daostream/gap (throw (ex-info "test observer hit a gap"
                                         {:result r})))))))


(defn- tx-ts
  "The transaction times of every atomic record currently on a local stream."
  [local]
  (map (comp :t :dao.space/transaction) (ds/->seq nil local)))


;; ---------------------------------------------------------------------------
;; Descriptor validation
;; ---------------------------------------------------------------------------

(deftest descriptor-validation
  (let [intake (ds/open! {:type :ringbuffer})]
    (testing "a missing or non-stream :local-stream throws"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"local-stream"
            (ds/open! {:type :transactor,
                       :intake-pool [intake]})))
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"local-stream"
            (ds/open! {:type :transactor,
                       :local-stream 42,
                       :intake-pool [intake]})))
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"local-stream"
            (ds/open! {:type :transactor,
                       :local-stream (->ReaderOnlyStream),
                       :intake-pool [intake]})))
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"local-stream"
            (ds/open! {:type :transactor,
                       :local-stream (->WriterOnlyStream),
                       :intake-pool [intake]}))))
    (testing "the intake pool must be a non-empty collection of writers"
      (let [local (ds/open! {:type :ringbuffer})]
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"intake-pool"
              (ds/open! {:type :transactor,
                         :local-stream local})))
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"non-empty"
              (ds/open! {:type :transactor,
                         :local-stream local,
                         :intake-pool []})))
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"intake-pool"
              (ds/open! {:type :transactor,
                         :local-stream local,
                         :intake-pool :nope})))
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"IDaoStreamWriter"
              (ds/open! {:type :transactor,
                         :local-stream local,
                         :intake-pool [(->ReaderOnlyStream)]})))
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"IDaoStreamWriter"
              (ds/open! {:type :transactor,
                         :local-stream local,
                         :intake-pool [42]})))))
    (testing ":name is optional and diagnostic only"
      (let [local (ds/open! {:type :ringbuffer})]
        (is (some? (ds/open! {:type :transactor,
                              :local-stream local,
                              :intake-pool [intake]})))
        (is (some? (ds/open! {:type :transactor,
                              :local-stream local,
                              :intake-pool [intake],
                              :name "producer"})))))
    (testing ":next-t cannot override history-derived causality"
      (let [local (ds/open! {:type :ringbuffer})]
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"next-t"
              (ds/open! {:type :transactor,
                         :local-stream local,
                         :intake-pool [intake],
                         :next-t 99})))))
    (testing "opening creates, registers, or closes nothing"
      (let [local (ds/open! {:type :ringbuffer})
            pool (ds/open! {:type :ringbuffer})]
        (ds/open! {:type :transactor, :local-stream local, :intake-pool [pool]})
        (is (false? (ds/closed? local)))
        (is (false? (ds/closed? pool)))
        (is (empty? (ds/->seq nil local)))
        (is (empty? (ds/->seq nil pool)))))))


;; ---------------------------------------------------------------------------
;; Transaction time derivation
;; ---------------------------------------------------------------------------

(deftest reopen-derives-next-t-from-retained-history
  (let [local (ds/open! {:type :ringbuffer})
        intake (ds/open! {:type :ringbuffer})
        a (ds/open!
            {:type :transactor, :local-stream local, :intake-pool [intake]})]
    (is (= {:result :ok, :t 0, :datoms [[1 :a 1 0 1]]}
           (ds/append! a {:db/id 1, :a 1})))
    (transactor/transact! a [{:db/id 2, :b 2} {:db/id 3, :c 3}])
    (testing "a reopened wrapper derives 1 + max retained datom t"
      (let [b (ds/open! {:type :transactor,
                         :local-stream local,
                         :intake-pool [intake]})]
        (is (= {:result :ok, :t 2, :datoms [[4 :d 4 2 1]]}
               (ds/append! b {:db/id 4, :d 4})))
        (is (= #{0 1 2} (set (tx-ts local)))
            "the scan read, and did not consume, the retained history")))
    (testing "an empty local stream derives 0"
      (let [fresh (ds/open! {:type :ringbuffer})
            c (ds/open! {:type :transactor,
                         :local-stream fresh,
                         :intake-pool [intake]})]
        (is (= {:result :ok, :t 0, :datoms [[1 :a 1 0 1]]}
               (ds/append! c {:db/id 1, :a 1})))))))


(deftest malformed-or-gapped-retained-history-throws
  (let [intake (ds/open! {:type :ringbuffer})
        open-on (fn [local]
                  (ds/open! {:type :transactor,
                             :local-stream local,
                             :intake-pool [intake]}))]
    (testing "a retention gap at cursor zero throws on open"
      (let [local (ds/open! {:type :ringbuffer,
                             :capacity 2,
                             :eviction-policy :evict-oldest})]
        (doseq [i (range 3)]
          (ds/append! local
                      {:dao.space/transaction {:t i, :datoms [[i :a i i 1]]}}))
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"gap"
              (open-on local)))))
    (testing "a malformed payload in the retained history throws on open"
      (let [local (ds/open! {:type :ringbuffer})]
        (ds/append! local 42)
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"payload"
              (open-on local)))))
    (testing "an out-of-shape transaction record throws on open"
      (let [local (ds/open! {:type :ringbuffer})]
        (ds/append! local {:dao.space/transaction {:t 1, :datoms :nope}})
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"transaction"
              (open-on local)))))
    (testing "a non-integer datom t in the retained history throws on open"
      (let [local (ds/open! {:type :ringbuffer})]
        (ds/append! local [1 :a :v :bogus 1])
        (is (thrown-with-msg? #?(:cljd Object
                                 :clj Exception
                                 :cljs js/Error)
                              #"integer"
              (open-on local)))))))


;; ---------------------------------------------------------------------------
;; Atomic packet shape
;; ---------------------------------------------------------------------------

(deftest each-transaction-is-exactly-one-append
  (let [local (ds/open! {:type :ringbuffer})
        recording (->RecordingAppendStream local (atom []))
        log (open-with-intake recording)]
    (ds/append! log {:db/id 1, :a 1})
    (transactor/transact! log [{:db/id 2, :b 2} {:db/id 3, :c 3}])
    (is
      (= [{:dao.space/transaction {:t 0, :datoms [[1 :a 1 0 1]]}}
          {:dao.space/transaction {:t 1,
                                   :datoms [[2 :b 2 1 1] [3 :c 3 1 1]]}}]
         @(:records recording))
      "two transactions, exactly two underlying ds/append! calls — no torn
         packet, no extra calls")
    (is (= 2 (count @(:records recording))))))


(deftest readers-observe-atomic-transaction-records
  (let [local (ds/open! {:type :ringbuffer})
        log (open-with-intake local)]
    (ds/append! log {:db/id 1, :a 1})
    (transactor/transact! log [{:db/id 2, :b 2}])
    (let [r1 (ds/next log {:position 0})
          r2 (ds/next log (:cursor r1))]
      (is (= {:dao.space/transaction {:t 0, :datoms [[1 :a 1 0 1]]}} (:ok r1)))
      (is (= {:dao.space/transaction {:t 1, :datoms [[2 :b 2 1 1]]}} (:ok r2)))
      (is (= :blocked (ds/next log (:cursor r2))))
      (is (= [{:dao.space/transaction {:t 0, :datoms [[1 :a 1 0 1]]}}
              {:dao.space/transaction {:t 1, :datoms [[2 :b 2 1 1]]}}]
             (vec (ds/->seq nil local)))
          "readers of the local stream see the same atomic records"))))


(deftest concurrent-calls-on-one-wrapper-serialize-transaction-time
  #?(:clj (let [inner (ds/open! {:type :ringbuffer})
                slow-local
                (reify
                  ds/IDaoStreamWriter
                  (append! [_ val] (Thread/sleep 5) (ds/append! inner val))


                  ds/IDaoStreamReader

                  (next [_ cursor] (ds/next inner cursor)))
                log (open-with-intake slow-local)
                start (promise)
                calls (mapv (fn [i]
                              (future @start
                                      (ds/append! log {:db/id i, :value i})))
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
  (let [local (ds/open! {:type :ringbuffer})
        log (open-with-intake local)]
    (testing "an entity map with :db/id expands to one datom per attribute"
      (let [{:keys [result t datoms]}
            (ds/append! log {:db/id 100, :work/posted true, :work/task "x"})]
        (is (= :ok result))
        (is (= 0 t))
        (is (= 2 (count datoms)))
        (is (= #{[100 :work/posted true 0 1] [100 :work/task "x" 0 1]}
               (set datoms)))))
    (testing "an entity map without :db/id throws"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #":db/id"
            (ds/append! log {:work/posted true}))))
    (testing
      "a [e a v] datom vector is padded and stamped, m defaults to assert"
      (is (= {:result :ok, :t 1, :datoms [[1 :a 1 1 1]]}
             (ds/append! log [1 :a 1]))))
    (testing "an explicit m is preserved"
      (is (= {:result :ok, :t 2, :datoms [[2 :b 2 2 0]]}
             (ds/append! log [2 :b 2 nil 0]))))
    (testing "an explicit datom t is rejected and does not advance t"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"t"
            (ds/append! log [4 :d 4 9])))
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"t"
            (ds/append! log [4 :d 4 9 1])))
      (is (= {:result :ok, :t 3, :datoms [[5 :e 5 3 1]]}
             (ds/append! log [5 :e 5]))))
    (testing "a value that is neither entity map nor datom vector throws"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"entity map or datom"
            (ds/append! log 42))))
    (testing "an input that produces zero datoms is rejected"
      (is (thrown-with-msg? #?(:cljd Object
                               :clj Exception
                               :cljs js/Error)
                            #"no datoms"
            (ds/append! log {:db/id 1}))))))


;; ---------------------------------------------------------------------------
;; Failure handling
;; ---------------------------------------------------------------------------

(deftest append-failure-does-not-advance-t-and-can-retry
  (let [local (ds/open! {:type :ringbuffer})
        flaky (->FailingAppendStream local (atom 2))
        log (open-with-intake flaky)]
    (is (thrown-with-msg? #?(:cljd Object
                             :clj Exception
                             :cljs js/Error)
                          #"failed"
          (ds/append! log {:db/id 1, :a 1})))
    (is (= {:result :ok, :t 0, :datoms [[1 :a 1 0 1]]}
           (ds/append! log {:db/id 1, :a 1}))
        "retry lands at the same t: the failed append never advanced it")
    (is (= {:result :ok, :t 1, :datoms [[2 :b 2 1 1]]}
           (ds/append! log {:db/id 2, :b 2})))))


(deftest append-throw-does-not-advance-t-and-can-retry
  (let [local (ds/open! {:type :ringbuffer})
        throwing (->ThrowingAppendStream local (atom true))
        log (open-with-intake throwing)]
    (is (thrown? #?(:cljd Object
                    :clj Exception
                    :cljs js/Error)
          (ds/append! log {:db/id 1, :a 1})))
    (is (= {:result :ok, :t 0, :datoms [[1 :a 1 0 1]]}
           (ds/append! log {:db/id 1, :a 1}))
        "a thrown underlying error leaves the watermark unchanged")))


;; ---------------------------------------------------------------------------
;; transact!
;; ---------------------------------------------------------------------------

(deftest transact-commits-one-atomic-record
  (let [local (ds/open! {:type :ringbuffer})
        log (open-with-intake local)]
    (testing "several items commit under one t in one atomic record"
      (let [{:keys [result t datoms]} (transactor/transact! log
                                                            [{:db/id 100, :a 1}
                                                             {:db/id 101, :b 2}
                                                             [102 :c 3]])]
        (is (= :ok result))
        (is (= 0 t))
        (is (= 3 (count datoms)))
        (is (apply = (map #(nth % 3) datoms))
            "every datom in the batch shares one t")
        (is (= [[100 :a 1 0 1] [101 :b 2 0 1] [102 :c 3 0 1]] datoms)))
      (is (= [{:dao.space/transaction {:t 0,
                                       :datoms [[100 :a 1 0 1] [101 :b 2 0 1]
                                                [102 :c 3 0 1]]}}]
             (vec (ds/->seq nil local)))
          "exactly one atomic packet on the stream"))
    (testing "a transact! after an append! gets a distinct t"
      (ds/append! log {:db/id 1, :a 1})
      (is (= {:result :ok, :t 2, :datoms [[2 :b 2 2 1]]}
             (transactor/transact! log [{:db/id 2, :b 2}]))))
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
            (transactor/transact! log [{:db/id 1, :a 1} 42])))
      (is (= 3 (count (ds/->seq nil local)))
          "the invalid batch appended nothing"))))


;; ---------------------------------------------------------------------------
;; Close ownership
;; ---------------------------------------------------------------------------

(deftest close-is-per-handle-and-does-not-touch-local-stream
  (let [local (ds/open! {:type :ringbuffer})
        intake (ds/open! {:type :ringbuffer})
        log (ds/open!
              {:type :transactor, :local-stream local, :intake-pool [intake]})]
    (ds/append! log {:db/id 1, :a 1})
    (is (false? (ds/closed? log)))
    (is (= {:woke []} (ds/close! log)))
    (is (true? (ds/closed? log)))
    (is (false? (ds/closed? local))
        "closing the wrapper must not close the supplied local stream")
    (is (false? (ds/closed? intake))
        "closing the wrapper must not close the supplied intake pool")
    (is (thrown-with-msg? #?(:cljd Object
                             :clj Exception
                             :cljs js/Error)
                          #"closed"
          (ds/append! log {:db/id 2, :a 2})))
    (is (thrown-with-msg? #?(:cljd Object
                             :clj Exception
                             :cljs js/Error)
                          #"closed"
          (transactor/transact! log [{:db/id 2, :a 2}])))
    (is (map? (ds/next log {:position 0}))
        "the wrapper still delegates reads after close")
    (is (= [{:dao.space/transaction {:t 0, :datoms [[1 :a 1 0 1]]}}]
           (vec (ds/->seq nil local)))
        "closing neither closes nor erases the local stream")))


;; ---------------------------------------------------------------------------
;; Single-writer
;; ---------------------------------------------------------------------------

(deftest single-writer-wrappers-are-not-coordinated
  (testing
    "two wrappers over the same local stream each derive the same
            next-t and silently write colliding records: documented hazard,
            no coordination is possible without shared mutable state"
    (let [local (ds/open! {:type :ringbuffer})
          intake (ds/open! {:type :ringbuffer})
          a (ds/open!
              {:type :transactor, :local-stream local, :intake-pool [intake]})
          b (ds/open!
              {:type :transactor, :local-stream local, :intake-pool [intake]})]
      (is (= {:result :ok, :t 0, :datoms [[1 :a 1 0 1]]}
             (ds/append! a {:db/id 1, :a 1})))
      (is (= {:result :ok, :t 0, :datoms [[2 :b 2 0 1]]}
             (ds/append! b {:db/id 2, :b 2}))
          "wrapper b independently derived t=0 from the same history")
      (is (= #{0} (set (tx-ts local)))
          "two colliding records at t=0, not a sequential log"))))


;; ---------------------------------------------------------------------------
;; publish!
;; ---------------------------------------------------------------------------

(deftest publish-enqueues-indexes-into-the-pool
  (let [local (ds/open! {:type :ringbuffer})
        a (ds/open! {:type :ringbuffer, :capacity 1024})
        b (ds/open! {:type :ringbuffer, :capacity 1024})
        log (ds/open!
              {:type :transactor, :local-stream local, :intake-pool [a b]})]
    (ds/append! log {:db/id 1, :work/status :todo})
    (ds/append! log {:db/id 2, :work/status :done})
    (testing "publish! returns the manifest and its content address"
      (let [{:keys [manifest-address manifest]} (transactor/publish! log)]
        (is (= "segment" (namespace manifest-address)))
        (is (= manifest-address (jing/segment-key manifest)))
        (is (= #{:indexes :count :branching-factor} (set (keys manifest))))
        (is (= 2 (:count manifest)))
        (is (seq (ds/->seq nil a)) "the default :select-stream is first")
        (is (empty? (ds/->seq nil b)))))
    (testing "publication touches no stream lifecycle"
      (is (false? (ds/closed? local)))
      (is (false? (ds/closed? a)))
      (is (false? (ds/closed? b))))
    (testing "opts route through: :select-stream picks the pool member"
      (let [{:keys [manifest]} (transactor/publish! log
                                                    {:select-stream second})]
        (is (= 2 (:count manifest)))
        (is (seq (ds/->seq nil b)))))
    (testing "opts route through: :branching-factor"
      (let [{:keys [manifest]} (transactor/publish! log {:branching-factor 16})]
        (is (= 16 (:branching-factor manifest)))))
    (testing "publish! does not consume the local stream"
      (is (= 2 (count (ds/->seq nil local)))))))


(deftest publish-materializes-through-observer-and-reads-back
  (let [local (ds/open! {:type :ringbuffer})
        intake (ds/open! {:type :ringbuffer, :capacity 1024})
        log (open-with-intake local intake)]
    (ds/append! log {:db/id 1, :work/status :todo})
    (ds/append! log {:db/id 2, :work/status :done})
    (let [{:keys [manifest-address manifest]} (transactor/publish! log)
          store (materialize-through-observer [intake])]
      (is (= manifest (index/read-manifest store manifest-address)))
      (is (= (set [[1 :work/status :todo 0 1] [2 :work/status :done 1 1]])
             (set (index/read-datoms store manifest-address)))
          "observer materialization makes the published datoms readable"))
    (testing "an empty local stream publishes an empty manifest"
      (let [local2 (ds/open! {:type :ringbuffer})
            intake2 (ds/open! {:type :ringbuffer, :capacity 1024})
            log2 (open-with-intake local2 intake2)
            {:keys [manifest-address manifest]} (transactor/publish! log2)
            store (materialize-through-observer [intake2])]
        (is (zero? (:count manifest)))
        (is (= [] (index/read-datoms store manifest-address)))))))
