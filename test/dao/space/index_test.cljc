(ns dao.space.index-test
  "Contract tests for dao.space.index: the agent-side covered-index publisher
   (docs/design/dao.jing.md, Publication from an agent) and the stateful
   dao.stream observer session of docs/design/dao.space.index.as-observer.md
   (fold-batch, publish!, flush-staged, drain, db-value, checkpoint,
   restore — the §7 Phase 0′ acceptance checklist).

   publish-index! snapshots an agent-local dao.stream, builds the four
   covered indexes as immutable content-addressed dao.data.btree node blobs,
   and appends them to one intake stream selected from an explicit pool. A
   DaoJing observer over the pool materializes the blobs; read-manifest /
   read-datoms / restored-indexes consume them. Everything runs on JVM,
   ClojureScript, and ClojureDart."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [dao.data.btree :as bt]
            [dao.datom :as datom]
            [dao.jing :as jing]
            #?@(:clj [[dao.jing.coordinate :as jing-coordinate]
                      [dao.jing.remote :as jing-remote]])
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.memory-log :as memory-log]
            [dao.stream.v2.observer :as observer]
            [dao.stream.v2.ringbuffer :as ringbuffer]))


(defrecord MalformedResultStream
  [result]
  ;; Test double: a v2 reader whose cursor answers a conforming ok mint and
  ;; whose every next answer is the configured result, used to feed
  ;; malformed responses into publish-index!'s snapshot reading.
  stream/IDaoStreamReader

  (cursor
    [_this _anchor]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/cursor {:position 0}})


  (next [_this _cursor] result))


(defn- content-handle
  "In-memory content store for tests: a map keyed by content address. The
   store atom is exposed as :store so tests can assert on the exact contents
   of the backend."
  ([]
   (let [store (atom {})]
     {:store store,
      :put-content-fn (fn [address payload]
                        (if (contains? @store address)
                          :present
                          (do (swap! store assoc address payload) :inserted))),
      :get-content-fn (fn [address not-found]
                        (get @store address not-found))})))


(defn- counting-content-store
  "Wrap a content-store handle so every :get-content-fn invocation is counted.
   Returns {:store wrapped-handle, :gets (fn [] count)} — a minimal counting
   harness for fetch-count assertions (restoration faults no nodes at
   construction, a seek loads only its path)."
  [store]
  (let [gets (atom 0)
        get-fn (:get-content-fn store)]
    {:store (assoc store
                   :get-content-fn (fn [address not-found]
                                     (swap! gets inc)
                                     (get-fn address not-found))),
     :gets (fn [] @gets)}))


(defn- open-local
  "Open a memory-log local (agent) stream pre-loaded with datoms."
  [datoms]
  (let [s (:dao.stream/handle
            (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))]
    (doseq [d datoms] (stream/append! s d))
    s))


(defn- open-intake
  "A dao.stream.v2 ringbuffer intake writer with capacity large enough for
   the multi-node tests."
  ([] (open-intake 4096))
  ([capacity]
   (:dao.stream/handle
     (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                          :dao.stream.ringbuffer/capacity capacity}))))


(defn- intake-values
  "Every value currently on a v2 intake stream, read from its oldest
   cursor — the test's view of what publication enqueued."
  [s]
  (loop [cursor (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
         acc []]
    (let [r (stream/next s cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- pool-state
  "Observer state over v2 intake handles, each entered at its oldest
   cursor."
  [intakes]
  (jing/observer-state
    (mapv (fn [s]
            {:stream s
             :cursor (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))})
          intakes)))


(defn- materialize-through-observer
  "Drain v2 intake streams through a dao.jing observer into a fresh content
   store; returns the store. Draining runs until blocked or end; a gap or
   defect is fatal to this composition (E11)."
  [intakes]
  (let [h (content-handle)]
    (loop [st (pool-state intakes)]
      (let [r (jing/observe-step! h st)]
        (case (:signal r)
          :dao.stream/ok (recur (:state r))
          :dao.stream/blocked h
          :dao.stream/end h
          (throw (ex-info "test observer hit a gap or defect"
                          {:result r})))))))


(defn- datoms
  "n distinct datoms whose four covered orders all differ."
  [n]
  (mapv (fn [i] [i (keyword "work" (str "a" (mod i 7))) (str "task-" i) 0 1])
        (range n)))


(defn- node-blob?
  "A persisted node blob is a plain EDN map carrying :keys (leaf and branch
   blobs both carry it; only branches add :level/:addresses)."
  [x]
  (and (map? x) (contains? x :keys)))


;; ---------------------------------------------------------------------------
;; Snapshot reading and read-back
;; ---------------------------------------------------------------------------

(deftest publish-index-snapshot-reads-local-stream-and-reads-back
  (testing
    "publish-index! walks the agent's local stream from {:position 0}, and
          the published manifest reads back the exact datoms through
          read-manifest / read-datoms after observer materialization"
    (let [datoms (datoms 300)
          local (open-local datoms)
          intake (open-intake)
          {:keys [manifest-address manifest]} (index/publish-index! local
                                                                    [intake])
          store (materialize-through-observer [intake])]
      (is (= (count datoms) (:count manifest)))
      (is (= 512 (:branching-factor manifest)))
      (is (= manifest (index/read-manifest store manifest-address)))
      (is (= (set datoms) (set (index/read-datoms store manifest-address)))
          "the EAVT walk returns exactly the snapshot datoms"))))


(deftest publish-index-flattens-atomic-transaction-records
  (testing
    "one local-stream element may carry an atomic transaction whose
            datoms are flattened before the covered indexes are built"
    (let [tx-datoms [[1 :person/name "Ada" 7 1] [1 :person/role :architect 7 1]]
          local (open-local [{:dao.space/transaction {:t 7,
                                                      :datoms tx-datoms}}])
          intake (open-intake)
          {:keys [manifest-address manifest]} (index/publish-index! local
                                                                    [intake])
          store (materialize-through-observer [intake])]
      (is (= tx-datoms (index/snapshot-datoms local)))
      (is (= 2 (:count manifest)))
      (is (= (set tx-datoms)
             (set (index/read-datoms store manifest-address))))))
  (testing "a malformed transaction packet fails before intake emission"
    (doseq [packet [{:dao.space/transaction {:t 1, :datoms :not-a-vector}}
                    {:dao.space/transaction {:t 1, :datoms []}}
                    {:dao.space/transaction {:t 1,
                                             :datoms [[1 :test/a :v 2 1]]}}
                    {:dao.space/transaction
                     {:t 1, :datoms [[1 :test/a :v 1 1 :source/forbidden]]}}]]
      (let [local (open-local [packet])
            intake (open-intake)]
        (is (thrown-with-msg? #?(:cljs js/Error
                                 :cljd Object
                                 :default Exception)
                              #"transaction"
              (index/publish-index! local [intake])))
        (is (empty? (intake-values intake)))))))


(deftest publish-index-rejects-noncanonical-local-datom-slots
  (testing
    "persisted d5 datoms require integer entity/time/metadata coordinates and
          a keyword attribute before any intake payload is emitted"
    (doseq [bad-datom [[:entity :test/a :v 0 1] [-16 :test/a :v 0 1]
                       [1 :unqualified :v 0 1] [1 "test/a" :v 0 1]
                       [1 :test/a :v -1 1] [1 :test/a :v 0 :db/assert]]]
      (let [local (open-local [bad-datom])
            intake (open-intake)]
        (is (thrown-with-msg? #?(:cljs js/Error
                                 :cljd Object
                                 :default Exception)
                              #"datom"
              (index/publish-index! local [intake])))
        (is (empty? (intake-values intake)))))))


(deftest datoms-from-elements-is-the-local-stream-vocabulary
  (testing
    "an element is one canonical d5 datom vector or one atomic transaction
          record; the seq-level spelling flattens both, in stream order"
    (let [d1 [1 :person/name "Ada" 0 1]
          d2 [1 :person/role :architect 7 1]
          d3 [2 :work/status :todo 7 1]]
      (is (= [] (index/datoms-from-elements [])))
      (is (= [d1] (index/datoms-from-elements [d1])))
      (is (= [d1 d2 d3]
             (index/datoms-from-elements
               [d1 {:dao.space/transaction {:t 7, :datoms [d2 d3]}}])))))
  (testing "a transaction record is exactly {:t n :datoms [canonical d5]}"
    (let [tx-datoms [[1 :person/name "Ada" 7 1] [1 :person/role :architect 7 1]]]
      (is (= tx-datoms
             (index/datoms-from-elements
               [{:dao.space/transaction {:t 7, :datoms tx-datoms}}]))))
    (doseq [packet [{:dao.space/transaction {:t 1, :datoms :not-a-vector}}
                    {:dao.space/transaction {:t 1, :datoms []}}
                    {:dao.space/transaction {:t 1,
                                             :datoms [[1 :test/a :v 2 1]]}}
                    {:dao.space/transaction
                     {:t 1, :datoms [[1 :test/a :v 1 1 :source/forbidden]]}}
                    {:dao.space/transaction {:t -1,
                                             :datoms [[1 :test/a :v -1 1]]}}
                    {:dao.space/transaction
                     {:t 1, :datoms [[1 :test/a :v 1 1]], :extra :key}}]]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"malformed dao\.space transaction record"
            (index/datoms-from-elements [packet]))
          (str "must reject " (pr-str packet)))))
  (testing
    "a malformed element throws its distinct diagnostic — a malformed datom
          is not a malformed transaction record, and a non-element is neither"
    (doseq [bad-datom [[:entity :test/a :v 0 1] [-16 :test/a :v 0 1]
                       [1 :unqualified :v 0 1] [1 "test/a" :v 0 1]
                       [1 :test/a :v -1 1] [1 :test/a :v 0 :db/assert]]]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"malformed local datom"
            (index/datoms-from-elements [bad-datom]))
          (str "must reject datom " (pr-str bad-datom))))
    (doseq [bad ["not-an-element" [1 :test/a :v] {:not :a-transaction}]]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"local stream payload must be"
            (index/datoms-from-elements [bad]))
          (str "must reject " (pr-str bad)))))
  (testing
    "the public spelling and the snapshot read agree on the same elements:
          both go through the same per-element rule"
    (let [d1 [1 :person/name "Ada" 0 1]
          d2 [1 :person/role :architect 7 1]
          d3 [2 :work/status :todo 7 1]
          elements [d1 {:dao.space/transaction {:t 7, :datoms [d2 d3]}}]]
      (is (= (index/datoms-from-elements elements)
             (index/snapshot-datoms (open-local elements)))))))


(deftest publish-index-address-is-content-derived-and-stream-invariant
  (testing
    "the manifest address derives from the manifest alone: identical local
          data published through different intake streams and pools converges
          on the same address"
    (let [datoms (datoms 200)
          local-a (open-local datoms)
          local-b (open-local datoms)
          ra (index/publish-index! local-a [(open-intake) (open-intake)])
          rb (index/publish-index! local-b [(open-intake) (open-intake)])]
      (is (= (:manifest ra) (:manifest rb)))
      (is (= (jing/segment-key (:manifest ra)) (:manifest-address ra)))
      (is (= (:manifest-address ra) (:manifest-address rb)))
      (is (= "segment" (namespace (:manifest-address ra)))))))


;; ---------------------------------------------------------------------------
;; Pool selection and emission
;; ---------------------------------------------------------------------------

(deftest publish-index-default-and-custom-stream-selection
  (testing "the default :select-stream is first"
    (let [local (open-local (datoms 8))
          a (open-intake)
          b (open-intake)]
      (index/publish-index! local [a b])
      (is (seq (intake-values a)))
      (is (empty? (intake-values b)))))
  (testing "a custom :select-stream receives the pool and may pick any member"
    (let [local (open-local (datoms 8))
          a (open-intake)
          b (open-intake)]
      (index/publish-index! local [a b] {:select-stream second})
      (is (empty? (intake-values a)))
      (is (seq (intake-values b))))))


(deftest publish-index-emits-only-to-the-selected-stream
  (testing
    "every node blob and the manifest land on exactly the selected
            intake stream; pool members that were not selected stay empty"
    (let [local (open-local (datoms 8))
          a (open-intake)
          b (open-intake)
          c (open-intake)
          {:keys [manifest]}
          (index/publish-index! local [a b c] {:select-stream (fn [_] c)})]
      (is (empty? (intake-values a)))
      (is (empty? (intake-values b)))
      (let [emitted (vec (intake-values c))]
        (is (seq emitted))
        (is (= manifest (last emitted)))))))


;; ---------------------------------------------------------------------------
;; Emission ordering and deduplication
;; ---------------------------------------------------------------------------

(deftest publish-index-deduplicates-equal-blobs
  (testing
    "the four single-leaf indexes over datoms that sort identically share one
          node blob: equal blobs are recorded and emitted once"
    (let [local (open-local [[1 :test/a "x" 0 1] [2 :test/a "y" 0 1]])
          intake (open-intake)
          {:keys [manifest]} (index/publish-index! local [intake])
          emitted (vec (intake-values intake))]
      (is (= 2 (count emitted)) "one shared node blob, then the manifest")
      (is (node-blob? (first emitted)))
      (is (= manifest (second emitted)))
      (is (= #{:eavt :aevt :avet :vaet} (set (keys (:indexes manifest)))))
      (is (apply = (vals (:indexes manifest)))
          "all four indexes resolve to the same shared root blob"))))


(deftest publish-index-counts-distinct-indexed-datoms
  (testing
    "the manifest count describes the set stored by every covered index,
          not the number of duplicate stream occurrences presented to it"
    (let [datom [1 :person/name "Ada" 0 1]
          local (open-local [datom datom])
          intake (open-intake)
          {:keys [manifest-address manifest]} (index/publish-index! local
                                                                    [intake])
          store (materialize-through-observer [intake])
          restored (index/restored-indexes store manifest)]
      (is (= 1 (:count manifest)))
      (is (= [datom] (index/read-datoms store manifest-address)))
      (doseq [order [:eavt :aevt :avet :vaet]]
        (is (= 1 (bt/count (get restored order))))
        (is (= 1 (count (bt/seq (get restored order)))))))))


(deftest publish-index-emits-children-before-parents-and-manifest-last
  (testing
    "the intake stream receives every unique node blob in children-before-
          parent order — Merkle by construction — and the manifest last"
    (let [local (open-local (datoms 64))
          intake (open-intake)
          {:keys [manifest]}
          (index/publish-index! local [intake] {:branching-factor 4})
          emitted (vec (intake-values intake))
          blobs (vec (butlast emitted))
          by-address
          (into {} (map-indexed (fn [i b] [(jing/segment-key b) i]) blobs))]
      (is (seq emitted))
      (is (= manifest (last emitted)) "the manifest is appended last")
      (is (every? node-blob? blobs))
      (is (= (count blobs) (count (into #{} (map jing/segment-key) blobs)))
          "equal blobs are emitted once, in first-insertion order")
      (doseq [b blobs]
        (doseq [child (:addresses b)]
          (let [parent-idx (by-address (jing/segment-key b))
                child-idx (by-address child)]
            (is (and child-idx (< child-idx parent-idx))
                (str "children before parents: child "
                     child
                     " must be emitted "
                     "before its parent "
                     (jing/segment-key b)))))))))


;; ---------------------------------------------------------------------------
;; Empty input
;; ---------------------------------------------------------------------------

(deftest publish-index-of-empty-input-is-readable
  (testing
    "publishing an empty local stream yields nil index roots that read back
          as no datoms, not an error"
    (let [local (open-local [])
          intake (open-intake)
          {:keys [manifest-address manifest]} (index/publish-index! local
                                                                    [intake])
          emitted (vec (intake-values intake))
          store (materialize-through-observer [intake])]
      (is (= [manifest] emitted) "only the manifest is appended")
      (is (every? nil? (vals (:indexes manifest))))
      (is (zero? (:count manifest)))
      (is (= [] (index/read-datoms store manifest-address)))
      (let [restored (index/restored-indexes store manifest)]
        (is (zero? (bt/count (:eavt restored))))
        (is (nil? (bt/seq (:eavt restored))))
        (is (nil? (bt/seq (:vaet restored))))))))


;; ---------------------------------------------------------------------------
;; Failure before emission
;; ---------------------------------------------------------------------------

(deftest publish-index-malformed-local-stream-throws-before-emission
  (testing "a map that is not an outcome map is malformed"
    (let [bad (->MalformedResultStream {:ok [1 :test/a "x" 0 1]})
          intake (open-intake)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"malformed"
            (index/publish-index! bad [intake])))
      (is (empty? (intake-values intake)))))
  (testing "an unknown signal is malformed"
    (let [bad (->MalformedResultStream :bogus)
          intake (open-intake)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"malformed"
            (index/publish-index! bad [intake])))
      (is (empty? (intake-values intake)))))
  (testing
    "a repeated ok carrying :dao.stream/value but no :dao.stream/cursor
          terminates by throwing, not by recurring on a nil cursor"
    (let [bad (->MalformedResultStream {:dao.stream/outcome :dao.stream/ok,
                                        :dao.stream/value [1 :test/a "x" 0 1]})
          intake (open-intake)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"malformed"
            (index/publish-index! bad [intake])))
      (is (empty? (intake-values intake)))))
  (testing
    "a conforming gap — outcome plus its required :dao.stream/cursor — is a
          violated precondition: the local stream is not the
          complete-retention transport the composition owes (S10)"
    (let [bad (->MalformedResultStream {:dao.stream/outcome :dao.stream/gap,
                                        :dao.stream/cursor {:position 99}})
          intake (open-intake)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"complete-retention"
            (index/publish-index! bad [intake])))
      (is (empty? (intake-values intake)))))
  (testing
    "a conforming cursor-mismatch is the same violated precondition"
    (let [bad (->MalformedResultStream {:dao.stream/outcome
                                        :dao.stream/cursor-mismatch})
          intake (open-intake)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"complete-retention"
            (index/publish-index! bad [intake])))
      (is (empty? (intake-values intake))))))


(deftest publish-index-full-intake-stream-throws
  (testing
    "an intake append answering :dao.stream/full throws with the result;
          only a partial immutable prefix of node blobs lands, never the
          manifest"
    (let [local (open-local (datoms 64))
          ;; the v2 ringbuffer always evicts rather than answering full, so
          ;; a full intake is scripted: a writer acknowledging exactly one
          ;; append, recording what it accepted
          accepted (atom [])
          intake (reify
                   stream/IDaoStreamWriter
                   (append!
                     [_ payload]
                     (if (empty? @accepted)
                       (do (swap! accepted conj payload)
                           {:dao.stream/outcome :dao.stream/ok})
                       {:dao.stream/outcome :dao.stream/full})))]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"append failed"
            (index/publish-index! local [intake] {:branching-factor 4})))
      (is (= 1 (count @accepted))
          "exactly one append was acknowledged before the full answer")
      (is (node-blob? (first @accepted))
          "only a node blob landed: the manifest is appended last, so it is
            never part of a full prefix"))))


;; ---------------------------------------------------------------------------
;; Pool and option validation
;; ---------------------------------------------------------------------------

(deftest publish-index-rejects-invalid-pool-and-selector
  (testing "the intake pool must be a non-empty collection"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"non-empty"
          (index/publish-index! (open-local (datoms 2)) [])))
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"collection"
          (index/publish-index! (open-local (datoms 2))
                                :not-a-pool))))
  (testing "the selected stream must be a member of the pool"
    (let [a (open-intake)
          b (open-intake)
          foreign (open-intake)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"outside the intake pool"
            (index/publish-index! (open-local (datoms 2))
                                  [a b]
                                  {:select-stream
                                   (fn [_] foreign)})))))
  (testing ":select-stream must be a function of the pool"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"must be a function"
          (index/publish-index! (open-local (datoms 2))
                                [(open-intake)]
                                {:select-stream :first}))))
  (testing ":branching-factor must be an integer of at least two"
    (doseq [bad [1 0 -1 3.5 "32"]]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"branching-factor"
            (index/publish-index! (open-local [])
                                  [(open-intake)]
                                  {:branching-factor bad}))
          (str "must reject branching factor " (pr-str bad))))))


;; ---------------------------------------------------------------------------
;; Manifest shape and source identity
;; ---------------------------------------------------------------------------

(deftest publish-index-manifest-carries-no-source-identity
  (testing
    "the manifest is exactly {:indexes {:eavt ... :aevt ... :avet ... :vaet
          ...} :count n :branching-factor n} and the materialized store holds
          only node blobs and the manifest"
    (let [local (open-local (datoms 16))
          intake (open-intake)
          {:keys [manifest]} (index/publish-index! local [intake])
          store (materialize-through-observer [intake])]
      (is (= #{:indexes :count :branching-factor} (set (keys manifest))))
      (is (= #{:eavt :aevt :avet :vaet} (set (keys (:indexes manifest)))))
      (is (not (contains? manifest :stream)))
      (is (not (contains? manifest :pool)))
      (is (not (contains? manifest :reorder-epoch)))
      (is (not (contains? manifest :manifest-address)))
      (doseq [[address payload] @(:store store)]
        (is (= "segment" (namespace address))
            (str "only content addresses are stored, got " address))
        (is (or (node-blob? payload) (= payload manifest))
            (str "stored payloads are node blobs or the manifest, got "
                 (pr-str payload)))))
    (testing "a non-empty manifest's index addresses are segment keys"
      (let [local (open-local (datoms 16))
            intake (open-intake)
            {:keys [manifest]} (index/publish-index! local [intake])]
        (is (every? #(= "segment" (namespace %))
                    (vals (:indexes manifest))))))))


(deftest published-index-constructor-validates-its-arguments
  (testing "the content-store coordinate must name a DaoJing backend type"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"DaoJing store coordinate"
          (index/published-index :not-a-map :segment/sha256-x))
        "a non-map coordinate is rejected")
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"DaoJing store coordinate"
          (index/published-index {:no :type} :segment/sha256-x))
        "a coordinate without :dao.jing/type is rejected"))
  (testing "the manifest address must be a content address"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"manifest content address"
          (index/published-index {:dao.jing/type :dao.jing/file,
                                  :path "x"}
                                 :not/a-segment)))))


(deftest remote-coordinate-allows-an-explicit-nil-options-entry
  #?(:clj (with-redefs [jing-remote/connect-content!
                        (fn [url options] {:url url, :options options})]
            (is (= {:url "ws://example.test/jing", :options {}}
                   (jing-coordinate/open! {:dao.jing/type :dao.jing/remote,
                                           :url "ws://example.test/jing",
                                           :options nil}))))
     :default (is true "the synchronous remote coordinate is JVM-only")))


(deftest covered-indexes-returns-the-four-covered-sets
  (testing "nil when the realization carries no covered sets"
    (is (nil? (index/covered-indexes nil)))
    (is (nil? (index/covered-indexes {})))
    (is (nil? (index/covered-indexes :not-a-realization)))
    (is (nil? (index/covered-indexes {:indexes :not-a-map})))
    (is (nil? (index/covered-indexes {:indexes {:eavt 1, :aevt 2}})))
    (is (nil? (index/covered-indexes
                {:indexes {:eavt 1, :aevt 2, :avet 3}}))))
  (testing "a structural check, never a type check"
    (is (= {:eavt :a, :aevt :b, :avet :c, :vaet :d}
           (index/covered-indexes
             {:indexes {:eavt :a, :aevt :b, :avet :c, :vaet :d}}))
        "a plain map with the four keys passes, no instance check")))


;; ---------------------------------------------------------------------------
;; Observer materialization, then read / restore parity
;; ---------------------------------------------------------------------------

(deftest observer-materialization-read-restore-parity
  (testing
    "a DaoJing observer materializing the intake stream makes the published
          manifest readable: the eager walk and the lazy restore agree with
          each other and with the source snapshot, on every covered order"
    (let [datoms (datoms 600)
          local (open-local datoms)
          intake (open-intake 1024)
          {:keys [manifest-address manifest]}
          (index/publish-index! local [intake] {:branching-factor 32})
          store (materialize-through-observer [intake])
          eager (index/read-datoms store manifest-address)
          restored (index/restored-indexes store manifest)]
      (is (= (count datoms) (count eager)))
      (is (= (set datoms) (set eager)))
      (is (= manifest (index/read-manifest store manifest-address)))
      (doseq [order [:eavt :aevt :avet :vaet]]
        (let [tree (order restored)]
          (is (= (count datoms) (bt/count tree)) (str order " count"))
          (is (= (set datoms) (set (bt/seq tree)))
              (str order " lazy restore covers the snapshot"))
          (is (= (set eager) (set (bt/seq tree)))
              (str order " eager walk and lazy restore agree")))))))


;; ---------------------------------------------------------------------------
;; Manifest guards
;; ---------------------------------------------------------------------------

(deftest read-manifest-guards-missing-and-invalid
  (testing "a missing manifest address throws for read-manifest and read-datoms"
    (let [store (content-handle)
          address (jing/segment-key
                    {:indexes {:eavt nil, :aevt nil, :avet nil, :vaet nil},
                     :count 0,
                     :branching-factor 512})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"missing index manifest"
            (index/read-manifest store address)))
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"missing index manifest"
            (index/read-datoms store address)))))
  (testing "a stored value that is not a manifest throws"
    (let [store (content-handle)
          address (jing/materialize! store {:not :a-manifest})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"invalid index manifest"
            (index/read-manifest store address)))))
  (testing "a manifest whose index address is not a segment address throws"
    (let [store (content-handle)
          address (jing/materialize! store
                                     {:indexes {:eavt :root/not-a-segment,
                                                :aevt nil,
                                                :avet nil,
                                                :vaet nil},
                                      :count 0,
                                      :branching-factor 512})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"invalid index manifest"
            (index/read-manifest store address)))))
  (testing
    "manifest count and roots must describe the same empty/non-empty state"
    (doseq [manifest [{:indexes {:eavt nil, :aevt nil, :avet nil, :vaet nil},
                       :count 1,
                       :branching-factor 512}
                      {:indexes {:eavt (jing/segment-key {:keys []}),
                                 :aevt nil,
                                 :avet nil,
                                 :vaet nil},
                       :count 0,
                       :branching-factor 512}
                      {:indexes {:eavt nil, :aevt nil, :avet nil, :vaet nil},
                       :count 0,
                       :branching-factor 1}]]
      (let [store (content-handle)
            address (jing/materialize! store manifest)]
        (is (thrown-with-msg? #?(:cljs js/Error
                                 :cljd Object
                                 :default Exception)
                              #"invalid index manifest"
              (index/read-manifest store address))))))
  (testing "the payload at a manifest address must hash back to that address"
    (let [store (content-handle)
          expected {:indexes {:eavt nil, :aevt nil, :avet nil, :vaet nil},
                    :count 0,
                    :branching-factor 512}
          address (jing/segment-key expected)
          forged {:indexes {:eavt nil, :aevt nil, :avet nil, :vaet nil},
                  :count 0,
                  :branching-factor 32}]
      (swap! (:store store) assoc address forged)
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"address"
            (index/read-manifest store address))))))


;; ---------------------------------------------------------------------------
;; Preserved public pure functions
;; ---------------------------------------------------------------------------

(deftest comparators-index-datoms-and-subseq-from
  (testing "heterogeneous value comparison is type-ranked and total"
    (is (neg? (index/compare-vals nil 0)))
    (is (neg? (index/compare-vals 1 "a")))
    (is (pos? (index/compare-vals :test/b "a")))
    (is (zero? (index/compare-vals 1 1.0))))
  (testing "index-datoms keeps every datom in every covered order"
    (let [d1 [1 :test/a "x" 0 1]
          d2 [2 :test/b "y" 0 1]
          idx (index/index-datoms [d2 d1])]
      (doseq [order [:eavt :aevt :avet :vaet]]
        (is (= #{d1 d2} (set (order idx)))
            (str order " holds both canonical d5 datoms")))))
  (testing "subseq-from slices a log-n descent from a sentinel"
    (let [d1 [1 :test/a "x" 0 1]
          d2 [2 :test/b "y" 0 1]
          d3 [3 :test/c "z" 0 1]
          idx (index/index-datoms [d1 d2 d3])]
      (is (= [d2 d3]
             (vec (index/subseq-from (:eavt idx) index/eavt-cmp d2)))))))


;; ---------------------------------------------------------------------------
;; Laziness observation (Step 1 — tests only, no src changes)
;; ---------------------------------------------------------------------------


(defn- ceil-div
  "Integer ceiling division: smallest integer >= n/d."
  [n d]
  (let [q (quot n d)] (if (zero? (mod n d)) q (inc q))))


(defn- expected-tree-height
  "Height of a B-tree (number of branch levels above the leaves).
   height 0 means the root itself is a leaf."
  [n bf]
  (if (<= n bf)
    0
    (loop [leaves (ceil-div n bf)
           h 1]
      (if (<= leaves bf) h (recur (ceil-div leaves bf) (inc h))))))


(defn- total-tree-nodes
  "Total number of internal + leaf nodes in a B-tree with n elements and
   branching factor bf."
  [n bf]
  (if (zero? n)
    0
    (loop [level-n (ceil-div n bf)
           total 0]
      (let [total' (+ total level-n)]
        (if (<= level-n 1) total' (recur (ceil-div level-n bf) total'))))))


(deftest restored-indexes-construction-fetches-nothing
  (testing
    "restored-indexes constructs four lazy BTSet trees without fetching
     any nodes from the content store; bt/count stays O(1) via the
     manifest-threaded cnt"
    (let [n 300
          datoms (datoms n)
          local (open-local datoms)
          intake (open-intake)
          {:keys [manifest]} (index/publish-index! local [intake])
          store (materialize-through-observer [intake])
          counter (counting-content-store store)
          restored (index/restored-indexes (:store counter) manifest)]
      (is (zero? ((:gets counter))) "construction triggers zero gets")
      (doseq [order [:eavt :aevt :avet :vaet]]
        (is (= n (bt/count (get restored order)))
            (str order " count is O(1) via manifest"))
        (is (zero? ((:gets counter)))
            (str order " bt/count does not fault any nodes"))))))


(deftest subseq-from-loads-only-seek-path-plus-range
  (testing
    "subseq-from on a restored tree fetches only the seek path plus
     consumed leaves; gets are strictly less than total node count and
     bounded by height + matching leaves + 1"
    (let [bf 4
          n 100
          datoms (datoms n)
          local (open-local datoms)
          intake (open-intake)
          {:keys [manifest]}
          (index/publish-index! local [intake] {:branching-factor bf})
          store (materialize-through-observer [intake])
          counter (counting-content-store store)
          restored (index/restored-indexes (:store counter) manifest)
          tree (:eavt restored)
          ;; snapshot counter baseline before the seek-path observation
          baseline ((:gets counter))
          sentinel (nth (sort index/eavt-cmp datoms) 50)
          result (vec (take 1 (index/subseq-from tree index/eavt-cmp sentinel)))
          observed (- ((:gets counter)) baseline)
          height (expected-tree-height n bf)
          total-nodes (total-tree-nodes n bf)]
      (is (= 1 (count result)) "take 1 returns exactly one datom")
      (is (= (first result) sentinel) "returned datom matches the sentinel")
      (is (<= observed (+ height 2))
          (str "gets " observed " <= height " height " + matching leaves + 1"))
      (is (< observed total-nodes)
          (str "gets " observed " < total node count " total-nodes)))))


(deftest lazy-eager-parity-per-order
  (testing
    "for each covered order, elements from subseq-from over a restored set
     equal the corresponding slice over the eager in-memory index built by
     index/index-datoms from index/read-datoms"
    (let [datoms (datoms 200)
          local (open-local datoms)
          intake (open-intake)
          {:keys [manifest-address manifest]} (index/publish-index! local
                                                                    [intake])
          store (materialize-through-observer [intake])
          eager (index/index-datoms (index/read-datoms store manifest-address))
          restored (index/restored-indexes store manifest)
          cmps {:eavt index/eavt-cmp,
                :aevt index/aevt-cmp,
                :avet index/avet-cmp,
                :vaet index/vaet-cmp}]
      (doseq [order [:eavt :aevt :avet :vaet]]
        (let [cmp (get cmps order)
              sentinel [nil nil nil nil nil]
              eager-seq (vec (index/subseq-from (order eager) cmp sentinel))
              restored-seq (vec
                             (index/subseq-from (order restored) cmp sentinel))]
          (is (= (count datoms) (count eager-seq))
              (str order " eager slice returns all datoms"))
          (is (= eager-seq restored-seq) (str order " lazy-eager parity")))))))


;; ---------------------------------------------------------------------------
;; The index session (docs/design/dao.space.index.as-observer.md, §7 Phase 0′)
;; ---------------------------------------------------------------------------


(defn- ready?
  "§2.1's readiness predicate: a consumer with a staged publication is not
   ready, and its run resumes the publication."
  [x]
  (nil? (get-in x [:publish :staged])))


(defn- folded-rows
  "The EAVT tree of an index state as a set — the fold's whole content."
  [st]
  (set (bt/seq (:eavt (:indexes st)))))


(defn- resolved-session
  "A :resolved session over an intake, defaulting to branching factor 4 so
   modest row counts force splits."
  ([intake]
   (resolved-session intake {:branching-factor 4}))
  ([intake opts]
   (index/session (merge {:mode :resolved, :schema {}, :intake intake} opts))))


(defn- unresolved-session
  "An :unresolved session over the ref schema the mode-matrix cases use."
  ([intake]
   (unresolved-session intake nil))
  ([intake opts]
   (index/session (merge {:mode :unresolved,
                          :schema {:test/ref {:db/valueType :db.type/ref}},
                          :intake intake,
                          :branching-factor 4}
                         opts))))


(defn- attach-to
  "Attach an observer to a reader handle through a unary capability that
   always hands that handle back."
  [handle]
  (observer/attach (fn [_] {:dao.stream/outcome :dao.stream/ok, :dao.stream/handle handle})
                   {:dao.stream/type :test/local}))


(defn- driven
  "One composition step: attach at :oldest and run the session to blocked or
   end, folding every observed batch."
  [handle st]
  (observer/run-on-stream {:observer (attach-to handle), :consumer st}
                          ready? index/fold-batch index/flush-staged))


(defn- resumed
  "Continue an existing session's observer half over its consumer."
  [session]
  (observer/run-on-stream {:observer (:observer session), :consumer (:consumer session)}
                          ready? index/fold-batch index/flush-staged))


(defn- scripted-intake
  "A v2 writer whose append! answers through behaviour, a function of the
   count of payloads already accepted and the payload: :ok, another append
   outcome keyword (:full, :closed, ...), or a full result map (including
   malformed non-outcome answers, exercised by passing a map without
   :dao.stream/outcome). Accepted payloads are recorded in order."
  [behaviour]
  (let [accepted (atom [])]
    {:handle (reify stream/IDaoStreamWriter
               (append!
                 [_ payload]
                 (let [n (count @accepted)
                       r (behaviour n payload)
                       result (if (map? r)
                                r
                                {:dao.stream/outcome (keyword "dao.stream" (name r))})]
                   (when (= :dao.stream/ok (:dao.stream/outcome result))
                     (swap! accepted conj payload))
                   result)))
     :accepted accepted}))


(defn- evicting-medium
  "A reader medium over an atom of values with a settable eviction floor:
   reads below the floor answer gap with the floor as the recovery cursor,
   at the tail blocked. :evict! sets the floor; :append! extends the values."
  [& [values]]
  (let [data (atom {:values (vec (or values [])), :floor 0})]
    {:handle (reify stream/IDaoStreamReader
               (cursor
                 [_ _]
                 {:dao.stream/outcome :dao.stream/ok,
                  :dao.stream/cursor {:pos (:floor @data)}})

               (next
                 [_ cursor]
                 (let [{:keys [values floor]} @data
                       pos (:pos cursor)]
                   (cond
                     (< pos floor)
                     {:dao.stream/outcome :dao.stream/gap,
                      :dao.stream/cursor {:pos floor}}

                     (< pos (count values))
                     {:dao.stream/outcome :dao.stream/ok,
                      :dao.stream/value (nth values pos),
                      :dao.stream/cursor {:pos (inc pos)}}

                     :else {:dao.stream/outcome :dao.stream/blocked}))))
     :evict! (fn [floor] (swap! data assoc :floor floor))
     :append! (fn [v] (swap! data update :values conj v))}))


(defn- gated-medium
  "A reader medium that serves `values` and answers blocked at the tail
   until fail! is called, after which a read at the tail answers
   transport-error — the seam for a terminal read after processed batches."
  [values]
  (let [data (atom {:values (vec values), :fail? false})]
    {:handle (reify stream/IDaoStreamReader
               (cursor
                 [_ _]
                 {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor {:pos 0}})

               (next
                 [_ cursor]
                 (let [{:keys [values fail?]} @data
                       pos (:pos cursor)]
                   (cond
                     (and fail? (>= pos (count values)))
                     {:dao.stream/outcome :dao.stream/transport-error}

                     (< pos (count values))
                     {:dao.stream/outcome :dao.stream/ok,
                      :dao.stream/value (nth values pos),
                      :dao.stream/cursor {:pos (inc pos)}}

                     :else {:dao.stream/outcome :dao.stream/blocked}))))
     :fail! (fn [] (swap! data assoc :fail? true))}))


(defn- leaf-addresses
  "Every leaf blob address reachable from a manifest's EAVT root in a
   materialized store — for removing exactly one leaf."
  [store manifest]
  (let [leaves (atom [])
        walk (fn walk
               [address]
               (let [blob (jing/get store address nil)]
                 (if-some [children (:addresses blob)]
                   (doseq [c children] (walk c))
                   (swap! leaves conj address))))]
    (walk (:eavt (:indexes manifest)))
    @leaves))


;; ---------------------------------------------------------------------------
;; fold-batch: the outer grammar (§2.2 step 0)
;; ---------------------------------------------------------------------------

(deftest fold-batch-outer-grammar
  (let [intake (open-intake)
        row [16 :test/a "x" 0 1]
        record {:dao.space/transaction {:t 7, :datoms [[17 :test/b "y" 7 1]]}}]
    (testing "a bare transaction record is one admitted element"
      (let [st (index/fold-batch (resolved-session intake) record)]
        (is (zero? (:rejected st)))
        (is (= 1 (:batch st)))
        (is (= #{[17 :test/b "y" 7 1]} (folded-rows st)))
        (is (= 7 (:max-t st)))))
    (testing "a bare d5 row is one admitted element"
      (let [st (index/fold-batch (resolved-session intake) row)]
        (is (= #{row} (folded-rows st)))))
    (testing "a vector of both element shapes is one batch"
      (let [st (index/fold-batch (resolved-session intake) [row record])]
        (is (zero? (:rejected st)))
        (is (= 1 (:batch st)))
        (is (= #{row [17 :test/b "y" 7 1]} (folded-rows st)))))
    (testing "a five-row batch is a batch, not one row: its first slot is a vector"
      (let [rows (mapv (fn [i] [16 :test/a (str "v" i) 0 1]) (range 5))
            st (index/fold-batch (resolved-session intake) rows)]
        (is (zero? (:rejected st)))
        (is (= (set rows) (folded-rows st)))))
    (testing "any other sequential is a batch of elements and nothing else — no nesting"
      (let [st (index/fold-batch (resolved-session intake)
                                 (list row record))]
        (is (zero? (:rejected st)))
        (is (= #{row [17 :test/b "y" 7 1]} (folded-rows st)))))))


(deftest fold-batch-rejects-malformed-outer-values
  (doseq [outer ["not-a-batch" 42 nil {:not :a-transaction} #{[16 :test/a "x" 0 1]}]]
    (let [intake (open-intake)
          st (index/fold-batch (resolved-session intake) outer)]
      (is (= 1 (:rejected st)) (str "must reject " (pr-str outer)))
      (is (= 1 (:batch st)) "the ordinal advances exactly once, never zero")
      (is (empty? (folded-rows st)) "no tree is touched")
      (is (= 1 (count (:defects st))) "exactly one defect event")
      (is (nil? (:position (first (:defects st))))
          (str "a whole-batch defect sits at position nil: " (pr-str outer))))))


(deftest fold-batch-rejects-a-malformed-nested-record
  (let [intake (open-intake)
        good [16 :test/a "x" 0 1]
        bad {:dao.space/transaction {:t 1, :datoms :not-a-vector}}
        st (index/fold-batch (resolved-session intake) [good bad])]
    (is (= 1 (:rejected st)))
    (is (= 1 (:batch st)))
    (is (empty? (folded-rows st)) "a valid prefix never lands: the fold is atomic per batch")
    (is (= [{:position 1,
             :reason :admission,
             :element bad,
             :batch 0}]
           (:defects st)))))


(deftest fold-batch-rejects-a-batch-whose-element-is-not-an-element
  (testing "a four-slot vector is a batch of elements, not one short row"
    (let [intake (open-intake)
          st (index/fold-batch (resolved-session intake) [16 :test/a "x" 0])]
      (is (= 1 (:rejected st)))
      (is (= {:position 0, :reason :element, :element 16, :batch 0}
             (first (:defects st))))))
  (testing "a non-element among elements defects the whole batch"
    (let [intake (open-intake)
          st (index/fold-batch (resolved-session intake) [[16 :test/a "x" 0 1] "garbage"])]
      (is (= 1 (:rejected st)))
      (is (= {:position 1, :reason :element, :element "garbage", :batch 0}
             (first (:defects st)))))))


(deftest fold-batch-valid-invalid-valid-batches
  (let [intake (open-intake)
        st (-> (resolved-session intake)
               (index/fold-batch [[16 :test/a "x" 0 1]])
               (index/fold-batch "garbage")
               (index/fold-batch [[17 :test/b "y" 0 1]]))]
    (is (= 3 (:batch st)))
    (is (= 1 (:rejected st)))
    (is (= #{[16 :test/a "x" 0 1] [17 :test/b "y" 0 1]} (folded-rows st))
        "the session continues past a rejected batch; only it is missing")))


(deftest fold-batch-empty-batch-advances-the-ordinal-only
  (let [intake (open-intake)
        before (index/fold-batch (resolved-session intake) [[16 :test/a "x" 3 1]])
        after (index/fold-batch before [])]
    (is (= 1 (:batch before)))
    (is (= 2 (:batch after)) "an empty admitted batch advances :batch once")
    (is (= (:indexes before) (:indexes after)) "trees unchanged")
    (is (= (:ids before) (:ids after)) ":ids unchanged")
    (is (= (:max-t before) (:max-t after)) ":max-t unchanged (nil until a row folds)")
    (is (= (:rejected before) (:rejected after)) ":rejected unchanged")))


(deftest fold-batch-rejection-leaves-ids-untouched
  (testing "a planned allocation that the batch never commits advances nothing"
    (let [intake (open-intake)
          ;; e tempid plans an allocation; the m slot's user-positive id
          ;; defects the batch after it — :ids must not move
          st (index/fold-batch (unresolved-session intake)
                               [[-16 :test/a "v" 0 99]])]
      (is (= 1 (:rejected st)))
      (is (= {:next-eid datom/first-user-id, :ownership :owned} (:ids st))))))


;; ---------------------------------------------------------------------------
;; fold-batch: the §3.1 mode matrix
;; ---------------------------------------------------------------------------

(deftest fold-batch-mode-matrix-cell-by-cell
  (testing "every cell of the matrix, both modes; reserved e passes on :resolved"
    (let [cases
          [;; slot, id class, mode, row, expected
           [:e :tempid :resolved [-16 :test/a "x" 0 1] :reject]
           [:e :reserved :resolved [1 :test/a "x" 0 1] :accept]
           [:e :user-positive :resolved [16 :test/a "x" 0 1] :accept]
           [:ref-v :tempid :resolved [20 :test/ref -16 0 1] :reject]
           [:ref-v :reserved :resolved [20 :test/ref 2 0 1] :accept]
           [:ref-v :user-positive :resolved [20 :test/ref 30 0 1] :accept]
           [:ref-v :non-integer :resolved [20 :test/ref "not-an-id" 0 1] :reject]
           [:m :tempid :resolved [20 :test/a "x" 0 -16] :reject]
           [:m :reserved :resolved [20 :test/a "x" 0 2] :accept]
           [:m :user-positive :resolved [20 :test/a "x" 0 99] :accept]
           [:undeclared-v :any :resolved [20 :test/plain -16 0 1] :accept]
           [:e :tempid :unresolved [-16 :test/a "x" 0 1] :accept]
           [:e :reserved :unresolved [1 :test/a "x" 0 1] :reject]
           [:e :user-positive :unresolved [16 :test/a "x" 0 1] :reject]
           [:ref-v :tempid :unresolved [-16 :test/ref -17 0 1] :accept]
           [:ref-v :reserved :unresolved [-16 :test/ref 2 0 1] :accept]
           [:ref-v :user-positive :unresolved [-16 :test/ref 30 0 1] :reject]
           [:ref-v :non-integer :unresolved [-16 :test/ref "not-an-id" 0 1] :reject]
           [:m :tempid :unresolved [-16 :test/a "x" 0 -17] :accept]
           [:m :reserved :unresolved [-16 :test/a "x" 0 2] :accept]
           [:m :user-positive :unresolved [-16 :test/a "x" 0 30] :reject]
           [:undeclared-v :any :unresolved [-16 :test/plain -9 0 1] :accept]]]
      (doseq [[slot class mode row expected] cases]
        (let [;; both modes need the ref schema: an undeclared :test/ref is a
              ;; value slot the matrix never inspects
              st (index/fold-batch (index/session
                                     {:mode mode,
                                      :schema {:test/ref {:db/valueType :db.type/ref}},
                                      :intake (open-intake),
                                      :branching-factor 4})
                                   row)]
          (if (= :accept expected)
            (is (zero? (:rejected st))
                (str mode " " slot " " class " must pass: " (pr-str row)))
            (is (= 1 (:rejected st))
                (str mode " " slot " " class " must defect: " (pr-str row))))))))
  (testing "the passes really pass through untouched where the matrix says so"
    (let [intake (open-intake)
          st (index/fold-batch (resolved-session intake) [20 :test/ref 30 0 1])]
      (is (contains? (folded-rows st) [20 :test/ref 30 0 1])
          "a user-positive declared-ref v passes on :resolved"))
    (let [intake (open-intake)
          st (index/fold-batch (unresolved-session intake) [-16 :test/plain -9 0 1])]
      (is (contains? (folded-rows st) [16 :test/plain -9 0 1])
          "an undeclared negative v is a value, not a ref: untouched, e resolved"))))


;; ---------------------------------------------------------------------------
;; fold-batch: tempid resolution (§3.2)
;; ---------------------------------------------------------------------------

(deftest fold-batch-allocates-deterministically-in-first-occurrence-order
  (let [intake (open-intake)
        st (index/fold-batch (unresolved-session intake)
                             [[-10 :test/ref -20 0 1] [-30 :test/ref -10 0 -40]])]
    (is (zero? (:rejected st)))
    (is (= {:next-eid 20, :ownership :owned} (:ids st)))
    (testing "within a row e, then declared-ref v, then m; across rows in batch order"
      (is (contains? (folded-rows st) [16 :test/ref 17 0 1]))
      (is (contains? (folded-rows st) [18 :test/ref 16 0 19])
          "-10 in the second row reuses the first row's durable id"))
    (testing "a resolution fact pair per allocated tempid, at the batch ordinal"
      (let [rows (folded-rows st)]
        (is (contains? rows [16 index/batch-attr 0 0 datom/default-op]))
        (is (contains? rows [16 index/tempid-attr -10 0 datom/default-op]))
        (is (contains? rows [17 index/batch-attr 0 0 datom/default-op]))
        (is (contains? rows [17 index/tempid-attr -20 0 datom/default-op]))
        (is (contains? rows [18 index/batch-attr 0 0 datom/default-op]))
        (is (contains? rows [18 index/tempid-attr -30 0 datom/default-op]))
        (is (contains? rows [19 index/batch-attr 0 0 datom/default-op]))
        (is (contains? rows [19 index/tempid-attr -40 0 datom/default-op]))))))


(deftest fold-batch-resolves-and-joins-m-tempids
  (testing "an m tempid joins e's id through the shared batch-local table"
    (let [intake (open-intake)
          st (index/fold-batch (unresolved-session intake)
                               [[-16 :test/a "v" 0 -16]])]
      (is (contains? (folded-rows st) [16 :test/a "v" 0 16])
          "the same tempid in e and m is one durable id")))
  (testing "a distinct m tempid gets its own id and facts"
    (let [intake (open-intake)
          st (index/fold-batch (unresolved-session intake)
                               [[-16 :test/a "v" 0 -17]])]
      (is (contains? (folded-rows st) [16 :test/a "v" 0 17]))
      (is (contains? (folded-rows st) [17 index/tempid-attr -17 0 datom/default-op])))))


(deftest fold-batch-resolves-v-only-and-m-only-tempids
  (testing "a tempid that appears only as a declared-ref v, or only as m, still gets an id"
    (let [intake (open-intake)
          ;; -17 appears only as v and -18 only as m: neither is ever an e
          st (index/fold-batch (unresolved-session intake)
                               [[-16 :test/ref -17 0 -18]])
          rows (folded-rows st)
          tempid-facts (set (filter #(= index/tempid-attr (index/datom-a %)) rows))]
      (is (zero? (:rejected st)))
      (is (contains? tempid-facts [17 index/tempid-attr -17 0 datom/default-op])
          "the v-only tempid got an id and a fact")
      (is (contains? tempid-facts [18 index/tempid-attr -18 0 datom/default-op])
          "the m-only tempid got an id and a fact")
      (is (contains? rows [16 :test/ref 17 0 18])))))


;; ---------------------------------------------------------------------------
;; run-on-stream: blocked and end (§7)
;; ---------------------------------------------------------------------------

(deftest run-on-stream-folds-a-local-medium-to-blocked
  (let [intake (open-intake)
        batches [[16 :test/a "x" 0 1]
                 {:dao.space/transaction {:t 2, :datoms [[17 :test/b "y" 2 1]]}}
                 [[18 :test/c "z" 0 1] [19 :test/c "w" 0 1]]]
        local (open-local batches)
        session (driven local (resolved-session intake))]
    (is (= 3 (:batch (:consumer session))) "every appended value folded as one batch")
    (is (= 2 (:max-t (:consumer session))))
    (is (= 4 (count (folded-rows (:consumer session)))))
    (is (= 3 (get-in session [:observer :cursor :dao.stream.memory-log/position]))
        "blocked at the open tail")))


(deftest run-on-stream-folds-a-closed-medium-to-end
  (let [intake (open-intake)
        local (open-local [[16 :test/a "x" 0 1] [17 :test/b "y" 0 1]])]
    (stream/close! local)
    (let [session (driven local (resolved-session intake))]
      (is (= 2 (:batch (:consumer session))))
      (is (= 2 (count (folded-rows (:consumer session))))))))


;; ---------------------------------------------------------------------------
;; Parity over the transactor's medium (§7 Phase 0′ head)
;; ---------------------------------------------------------------------------

(deftest resolved-session-parity-with-index-datoms
  (let [rows (datoms 600)
        ;; the transactor's medium: one transaction record per append on an
        ;; own memory-log, interleaved with bare rows; insertion order is
        ;; stream order, never a covered order
        local (open-local (mapcat (fn [r]
                                    (if (zero? (mod (index/datom-t r) 3))
                                      [{:dao.space/transaction
                                        {:t (index/datom-t r), :datoms [r]}}]
                                      [r]))
                                  rows))
        intake (open-intake 2048)
        session (driven local (resolved-session intake))
        st (:consumer session)
        baseline (index/index-datoms (index/snapshot-datoms local))]
    (testing "trees logically equal to index-datoms over snapshot-datoms, every order"
      (doseq [order [:eavt :aevt :avet :vaet]]
        (is (= (bt/count (order baseline)) (bt/count (order (:indexes st))))
            (str order " counts equal — splits included, not identical manifests"))
        (is (= (set (bt/seq (order baseline))) (set (bt/seq (order (:indexes st)))))
            (str order " rows equal"))))
    (testing "query results over db-value agree with the one-shot path"
      (let [find '[:find ?e ?v :where [?e :work/a0 ?v]]
            over-session (query/collect (apply query/q find
                                               [(query/current (index/db-value st))]))
            over-baseline (query/collect (apply query/q find
                                                [(query/current
                                                   (query/relation
                                                     (index/snapshot-datoms local)))]))]
        (is (= over-baseline over-session))
        (is (= 86 (count over-session)) "the slice is non-trivial")))
    (testing "a db-value taken before a fold is unchanged by it"
      (let [frozen (index/db-value st)
            frozen-rows (set (deref (:rows frozen)))
            st' (index/fold-batch st [[9000 :work/a "later" 0 1]])]
        (is (= (set (bt/seq (:eavt (:indexes st)))) frozen-rows)
            "the deferred rows resolve against the trees the value captured")
        (is (= frozen-rows (set (deref (:rows frozen))))
            "forcing again after the fold still sees the old trees")
        (is (contains? (folded-rows st') [9000 :work/a "later" 0 1]))))
    (testing "and a valid restore of what it publishes"
      (let [published (:state (index/publish! st))
            store (materialize-through-observer [intake])
            candidate (index/checkpoint {:observer (:observer session),
                                         :consumer published})
            restored (index/restore candidate
                                    {:content-store store,
                                     :intake (open-intake),
                                     :mode :resolved,
                                     :schema {}})]
        (is (some? (index/verify-candidate candidate store)))
        (is (= (count rows) (:count (get-in published [:publish :manifest]))))
        (doseq [order [:eavt :aevt :avet :vaet]]
          (is (= (set (bt/seq (order (:indexes st))))
                 (set (bt/seq (order (:indexes restored)))))
              (str order " restored equals live")))
        (is (= (:batch st) (:batch restored)))
        (is (= (:max-t st) (:max-t restored)))
        (is (= (:rejected st) (:rejected restored)))))))


;; ---------------------------------------------------------------------------
;; drain / checkpoint / restore round the rejection bookkeeping
;; ---------------------------------------------------------------------------

(deftest reject-drain-checkpoint-restore-keeps-rejected
  (let [intake (open-intake)
        st (-> (unresolved-session intake)
               (index/fold-batch [[-16 :test/a "x" 0 1]])
               (index/fold-batch "garbage"))
        [drained defects] (index/drain st)]
    (is (= 1 (count defects)) "the diagnostic is the drained event")
    (is (= :outer (:reason (first defects))))
    (is (empty? (:defects drained)))
    (is (= 1 (:rejected drained)) ":rejected is never drained")
    (let [published (:state (index/publish! drained))
          store (materialize-through-observer [intake])
          observer (attach-to (open-local []))
          candidate (index/checkpoint {:observer observer, :consumer published})
          restored (index/restore candidate
                                  {:content-store store,
                                   :intake (open-intake),
                                   :mode :unresolved,
                                   :schema {:test/ref {:db/valueType :db.type/ref}}})]
      (is (some? (index/verify-candidate candidate store)))
      (is (= 1 (:rejected restored))
          "completeness survives a drain, a checkpoint, and a restore")
      (is (empty? (:defects restored)) "drained diagnostics are not state")
      (is (= (:ids drained) (:ids restored))))))


(deftest empty-stream-checkpoint-derives-watermark-zero
  (let [intake (open-intake)
        local (open-local [])
        session (driven local (resolved-session intake))
        st (:consumer session)]
    (is (nil? (:max-t st)) ":max-t is nil until a row is folded")
    (is (zero? (:batch st)) "an empty stream folds no batch at all")
    (let [published (:state (index/publish! st))
          store (materialize-through-observer [intake])
          candidate (index/checkpoint {:observer (:observer session),
                                       :consumer published})
          restored (index/restore candidate
                                  {:content-store store,
                                   :intake (open-intake),
                                   :mode :resolved,
                                   :schema {}})]
      (is (some? (index/verify-candidate candidate store)))
      (is (zero? (index/watermark st)) "the empty stream's own watermark is 0")
      (is (zero? (index/watermark restored))
          "a reopened empty checkpoint derives 0, not 1 — nil stays nil"))))


;; ---------------------------------------------------------------------------
;; Gap accounting at the checkpoint boundary (§7)
;; ---------------------------------------------------------------------------

(deftest gap-immediately-before-checkpoint-carries-recovery-cursor-and-count
  (let [medium (evicting-medium [[16 :test/a "x" 0 1] [17 :test/a "y" 0 1]])
        intake (open-intake)
        session (driven (:handle medium) (resolved-session intake))]
    (is (zero? (:ingress-gaps (:observer session))))
    ;; evict the middle of the log and extend past it: the next round adopts
    ;; the recovery cursor and counts one gap, then folds the retained tail
    ((:evict! medium) 3)
    ((:append! medium) [18 :test/a "z" 0 1])
    ((:append! medium) [19 :test/a "w" 0 1])
    (let [session' (resumed session)
          report (index/coverage session')]
      (is (= 1 (:ingress-gaps report)))
      (is (= {:pos 4} (:cursor report))
          "the cursor came from the gap's recovery cursor, then advanced over the retained tail")
      (is (= 3 (:batch report)))
      (is (= #{[16 :test/a "x" 0 1] [17 :test/a "y" 0 1] [19 :test/a "w" 0 1]}
             (folded-rows (:consumer session')))
          "the evicted batch is absent from the value and the gap is counted")
      (let [published (:state (index/publish! (:consumer session')))
            candidate (index/checkpoint {:observer (:observer session'),
                                         :consumer published})]
        (is (= {:pos 4} (:cursor candidate))
            "the candidate carries the recovery-derived cursor")
        (is (= 1 (:ingress-gaps candidate))
            "and the incremented gap count — a partial index says so")
        (is (= 3 (:batch candidate)))))))


;; ---------------------------------------------------------------------------
;; The publication state machine (§4.1)
;; ---------------------------------------------------------------------------

(defn- many-row-state
  "A :resolved consumer over 40 folded rows at branching factor 4: enough
   blobs that a publication has a body, not just a manifest."
  [intake]
  (reduce index/fold-batch
          (resolved-session intake)
          (map vector (mapv (fn [i] [i :test/a (str "v" i) 0 1]) (range 40)))))


(deftest publication-ok-full-full-ok
  (let [budget (atom 2)
        intake (scripted-intake (fn [n _]
                                  (if (and (pos? @budget) (>= n 3))
                                    (do (swap! budget dec) :full)
                                    :ok)))
        fresh (many-row-state (:handle intake))]
    (is (= fresh (index/flush-staged fresh))
        "run is total: a ready consumer's flush-staged is identity")
    (let [p (index/publish! fresh)]
      (is (= :full (:status p)) "the first attempt stops at payload 3")
      (is (= 3 (:next p)))
      (is (= 3 (count @(:accepted intake))))
      (let [f1 (index/flush-staged (:state p))]
        (is (some? (get-in f1 [:publish :staged])) "still staged: the consumer is not ready")
        (is (= 3 (get-in f1 [:publish :staged :next])) "no further payload accepted")
        (let [f2 (index/flush-staged f1)]
          (is (nil? (get-in f2 [:publish :staged]))
              "the third attempt completes: the manifest was accepted")
          (is (= (count (get-in (:state p) [:publish :staged :payloads]))
                 (count @(:accepted intake)))
              "every payload appended exactly once across the three attempts")
          (is (= (jing/segment-key (get-in f2 [:publish :manifest]))
                 (jing/segment-key (last @(:accepted intake))))
              "the manifest is the last accepted payload"))))))


(deftest publication-refusal-from-the-first-payload-resumes-once-each
  (let [intake (scripted-intake (fn [_ _] :full))
        p (index/publish! (many-row-state (:handle intake)))
        staged (get-in (:state p) [:publish :staged])]
    (is (= :full (:status p)))
    (is (zero? (:next p)))
    (is (= (jing/segment-key (get-in (:state p) [:publish :manifest]))
           (jing/segment-key (last (:payloads staged))))
        "manifest-last: a partial prefix is never a publication")
    (let [repaired (scripted-intake (fn [_ _] :ok))
          done (index/flush-staged (assoc-in (:state p)
                                             [:publish :intake]
                                             (:handle repaired)))]
      (is (nil? (get-in done [:publish :staged])))
      (is (= (count (:payloads staged)) (count @(:accepted repaired)))
          "the repaired intake received every payload, exactly once, from :next 0"))))


(deftest publication-refusal-exactly-at-the-manifest-resumes-with-one-append
  (let [;; size the refusal to this state's own payload list: accept every
        ;; blob, refuse the manifest
        probe (scripted-intake (fn [_ _] :full))
        probe-p (index/publish! (many-row-state (:handle probe)))
        payload-count (count (get-in probe-p [:state :publish :staged :payloads]))
        manifest-idx (dec payload-count)
        at-manifest (scripted-intake (fn [n _] (if (< n manifest-idx) :ok :full)))
        p (index/publish! (many-row-state (:handle at-manifest)))]
    (is (= :full (:status p)))
    (is (= manifest-idx (:next p))
        "every blob accepted; :next sits exactly at the manifest")
    (is (= manifest-idx (count @(:accepted at-manifest))))
    (let [repaired (scripted-intake (fn [_ _] :ok))
          done (index/flush-staged (assoc-in (:state p)
                                             [:publish :intake]
                                             (:handle repaired)))]
      (is (nil? (get-in done [:publish :staged])))
      (is (= 1 (count @(:accepted repaired)))
          "resumption appends only the manifest, never a blob again"))))


(deftest publication-terminal-outcome-preserves-next-and-resumes
  (let [terminal (fn []
                   (scripted-intake (fn [n _]
                                      (if (zero? n)
                                        :ok
                                        {:dao.stream/outcome :dao.stream/closed}))))]
    (is (thrown-with-msg? #?(:cljs js/Error :cljd Object :default Exception)
                          #"cannot continue"
          (index/publish! (many-row-state (:handle (terminal))))))
    (let [intake (terminal)
          carried (try
                    (index/publish! (many-row-state (:handle intake)))
                    nil
                    (catch #?(:clj Exception :cljs js/Error :cljd Object) e
                      (:consumer (ex-data e))))
          payload-count (count (get-in carried [:publish :staged :payloads]))
          repaired (scripted-intake (fn [_ _] :ok))
          done (index/flush-staged (assoc-in carried [:publish :intake]
                                             (:handle repaired)))]
      (is (= 1 (get-in carried [:publish :staged :next]))
          "the throw preserves :next at the one accepted blob")
      (is (= 1 (count @(:accepted intake))))
      (is (nil? (get-in done [:publish :staged])) "the retry completes")
      (is (= (dec payload-count) (count @(:accepted repaired)))
          "the repaired intake appended only the remaining payloads"))))


(deftest publication-while-staged-is-a-caller-error
  (let [intake (scripted-intake (fn [_ _] :full))
        p (index/publish! (many-row-state (:handle intake)))]
    (is (thrown-with-msg? #?(:cljs js/Error :cljd Object :default Exception)
                          #"still staged"
          (index/publish! (:state p))))))


(deftest a-resumed-publication-then-read-failure-carries-the-completed-session
  (let [medium (gated-medium [[16 :test/a "x" 0 1] [17 :test/a "y" 0 1]])
        refusing (scripted-intake (fn [_ _] :full))
        session (driven (:handle medium) (resolved-session (:handle refusing)))]
    (is (= 2 (:batch (:consumer session))))
    ;; the composition's publication step: the intake refuses, the consumer
    ;; is not ready
    (let [staged (index/publish! (:consumer session))]
      (is (= :full (:status staged)))
      (is (some? (get-in (:state staged) [:publish :staged])))
      ;; now the intake is repaired and the medium's reads start failing: the
      ;; round finishes the publication, then throws on the read
      ((:fail! medium))
      (let [repaired (scripted-intake (fn [_ _] :ok))
            carried (try
                      (observer/run-on-stream
                        (-> {:observer (:observer session), :consumer (:state staged)}
                            (assoc-in [:consumer :publish :intake] (:handle repaired)))
                        ready? index/fold-batch index/flush-staged)
                      nil
                      (catch #?(:clj Exception :cljs js/Error :cljd Object) e
                        (:session (ex-data e))))]
        (is (some? carried) "the terminal read threw and carried the session")
        (is (nil? (get-in carried [:consumer :publish :staged]))
            "the publication completed inside the failing round")
        (is (= 2 (count @(:accepted repaired)))
            "one blob and the manifest were appended — the publication is done")
        ;; a retry against a repaired reader repeats nothing
        (let [quiet (reify stream/IDaoStreamReader
                      (cursor
                        [_ _]
                        {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor {:pos 2}})

                      (next [_ _] {:dao.stream/outcome :dao.stream/blocked}))
              _retried (observer/run-on-stream (assoc-in carried [:observer :stream] quiet)
                                               ready? index/fold-batch index/flush-staged)]
          (is (= 2 (count @(:accepted repaired)))
              "the retry did not re-append the accepted publication"))))))


(deftest second-publish-after-more-batches-appends-only-new-blobs
  (let [intake (open-intake 8192)
        local (open-local (mapv (fn [i] [i :work/a (str "t" i) 0 1]) (range 300)))
        session (driven local (resolved-session intake))
        pub-a (index/publish! (:consumer session))
        first-values (intake-values intake)]
    (is (= :ok (:status pub-a)))
    (is (> (count first-values) 4) "the first publication carried real blobs")
    (let [pub-b (index/publish! (index/fold-batch (:state pub-a)
                                                  [[999 :work/a "new1" 0 1]
                                                   [998 :work/a "new2" 0 1]]))
          new-values (drop (count first-values) (intake-values intake))
          old-by-address (into {} (map (fn [p] [(jing/segment-key p) p])) first-values)
          overlap (set/intersection
                    (set (keys old-by-address))
                    (set (map jing/segment-key new-values)))]
      (is (= :ok (:status pub-b)))
      (is (< (count new-values) 20)
          "the second publication appends only the blobs stored since the first — a delta, not the tree")
      (is (< (count new-values) (count first-values)))
      (is (= (jing/segment-key (get-in (:state pub-b) [:publish :manifest]))
             (jing/segment-key (last new-values)))
          "manifest-last holds for the delta publication too")
      (is (every? (fn [address]
                    ;; a delta node may coincidentally equal an old blob of
                    ;; another covered order; content addressing makes the
                    ;; re-append harmless (§4.1: materialize!'s dedup is the
                    ;; second line of defence, not the mechanism)
                    (let [re-appended (some (fn [p] (when (= address (jing/segment-key p)) p))
                                            new-values)]
                      (= (get old-by-address address) re-appended)))
                  overlap)
          "any address repeated across publications carries identical content"))))


;; ---------------------------------------------------------------------------
;; The checkpoint candidate (§4.2)
;; ---------------------------------------------------------------------------

(deftest checkpoint-candidate-round-trips-through-print
  (let [intake (open-intake)
        local (open-local [[16 :test/a "x" 2 1]])
        session (driven local (unresolved-session intake))
        published (:state (index/publish! (:consumer session)))
        candidate (index/checkpoint {:observer (:observer session),
                                     :consumer published})]
    (is (map? candidate))
    (is (= candidate (edn/read-string (pr-str candidate)))
        "a candidate, unlike a live session, is plain data")))


(deftest checkpoint-requires-a-publication
  (let [intake (open-intake)
        observer (attach-to (open-local []))]
    (is (thrown-with-msg? #?(:cljs js/Error :cljd Object :default Exception)
                          #"requires a publication"
          (index/checkpoint {:observer observer,
                             :consumer (resolved-session intake)})))))


(deftest candidate-not-promoted-when-the-manifest-is-missing
  (let [intake (open-intake)
        local (open-local (datoms 40))
        session (driven local (resolved-session intake {:branching-factor 4}))
        published (:state (index/publish! (:consumer session)))
        candidate (index/checkpoint {:observer (:observer session),
                                     :consumer published})]
    (is (nil? (index/verify-candidate candidate (content-handle)))
        "enqueued is not materialized: verification incomplete, never success")))


(deftest candidate-not-promoted-when-a-leaf-is-absent
  (let [intake (open-intake)
        local (open-local (datoms 40))
        session (driven local (resolved-session intake {:branching-factor 4}))
        published (:state (index/publish! (:consumer session)))
        store (materialize-through-observer [intake])
        candidate (index/checkpoint {:observer (:observer session),
                                     :consumer published})
        manifest (get-in published [:publish :manifest])
        leaves (leaf-addresses store manifest)]
    (is (some? (index/verify-candidate candidate store))
        "the intact store verifies")
    (is (seq leaves))
    (let [hole (content-handle)
          without-leaf (dissoc @(:store store) (first leaves))]
      (swap! (:store hole) merge without-leaf)
      (is (nil? (index/verify-candidate candidate hole))
          "the manifest present but one leaf absent is not promoted: the walk must check every address, not only the manifest"))))


(deftest restore-refuses-mismatched-mode-schema-and-shared-allocator
  (let [intake (open-intake)
        local (open-local [[16 :test/a "x" 0 1]])
        session (driven local (resolved-session intake))
        published (:state (index/publish! (:consumer session)))
        store (materialize-through-observer [intake])
        candidate (index/checkpoint {:observer (:observer session),
                                     :consumer published})
        opts {:content-store store, :intake (open-intake), :schema {}}]
    (is (thrown-with-msg? #?(:cljs js/Error :cljd Object :default Exception)
                          #"mode"
          (index/restore candidate (assoc opts :mode :unresolved))))
    (is (thrown-with-msg? #?(:cljs js/Error :cljd Object :default Exception)
                          #"schema"
          (index/restore candidate (assoc opts
                                          :mode :resolved
                                          :schema {:test/ref {:db/valueType :db.type/ref}}))))
    (testing "a :shared allocator is a snapshot, never a checkpoint"
      (let [shared-intake (open-intake)
            shared-local (open-local [[-16 :test/a "x" 0 1]])
            shared-session (driven shared-local
                                   (unresolved-session shared-intake
                                                       {:ids {:ownership :shared}}))
            shared-published (:state (index/publish! (:consumer shared-session)))
            shared-store (materialize-through-observer [shared-intake])
            shared-candidate (index/checkpoint {:observer (:observer shared-session),
                                                :consumer shared-published})]
        (is (some? (index/verify-candidate shared-candidate shared-store))
            "it may be published and captured for inspection")
        (is (= :shared (get-in shared-candidate [:ids :ownership])))
        (is (thrown-with-msg? #?(:cljs js/Error :cljd Object :default Exception)
                              #":shared"
              (index/restore shared-candidate
                             {:content-store shared-store,
                              :intake (open-intake),
                              :mode :unresolved,
                              :schema {:test/ref {:db/valueType :db.type/ref}}})))))))


;; ---------------------------------------------------------------------------
;; The F2 refault hazard, pinned both ways (§4.1)
;; ---------------------------------------------------------------------------

(deftest f2-refault-hazard-through-the-test-ref-seam
  (let [;; round 1: a session that publishes something restorable
        intake (open-intake)
        local (open-local (datoms 40))
        session (driven local (resolved-session intake {:branching-factor 4}))
        published (:state (index/publish! (:consumer session)))
        store (materialize-through-observer [intake])
        candidate (index/checkpoint {:observer (:observer session),
                                     :consumer published})
        restore-opts {:content-store store, :mode :resolved, :schema {}}]
    (is (some? (index/verify-candidate candidate store)))
    (testing "a session restored through :test refs, published, drained, cleared: the query throws"
      (let [resumed (index/restore candidate
                                   (assoc restore-opts
                                          :intake (open-intake)
                                          :ref-type :test))
            folded (index/fold-batch resumed [[500 :test/a "new" 0 1]])
            published-again (:state (index/publish! folded))]
        ;; the recording handle is drained; every :test ref is cleared; the
        ;; next query refaults the root through the drained handle
        (bt/clear-test-refs! (:eavt (:indexes published-again)))
        (is (thrown-with-msg? #?(:cljs js/Error :cljd Object :default Exception)
                              #"missing index segment"
              (bt/seq (:eavt (:indexes published-again)))))))
    (testing "the same sequence through the session-constructed :strong storage: no throw"
      (let [resumed (index/restore candidate
                                   (assoc restore-opts :intake (open-intake)))
            _ (is (= :strong (:ref-type (bt/settings (:eavt (:indexes resumed)))))
                  "a restored session pins its roots :strong, never the host default")
            folded (index/fold-batch resumed [[500 :test/a "new" 0 1]])
            published-again (:state (index/publish! folded))]
        (bt/clear-test-refs! (:eavt (:indexes published-again)))
        (is (= 41 (count (bt/seq (:eavt (:indexes published-again)))))
            "refaults resolve against durable content, never the handle")))))


(deftest read-path-restored-indexes-carries-the-host-default-ref-type
  (let [intake (open-intake)
        local (open-local (datoms 8))
        session (driven local (resolved-session intake {:branching-factor 4}))
        published (:state (index/publish! (:consumer session)))
        store (materialize-through-observer [intake])
        manifest (get-in published [:publish :manifest])
        read-path-ref-type (:ref-type
                             (bt/settings
                               (:eavt (index/restored-indexes store manifest))))]
    ;; The wrong-construction demonstration is JVM-only, where the read path
    ;; is not already :strong; off it the same restoration is :strong anyway
    ;; (§7, N3).
    #?(:clj (is (= (bt/default-ref-type*) read-path-ref-type)
                "the query read path takes the host default — why restore never uses it")
       :default (is (= :strong read-path-ref-type)
                    "off the JVM the read path already pins :strong"))))
