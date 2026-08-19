(ns dao.space.index-test
  "Contract tests for dao.space.index: the agent-side covered-index publisher
   (docs/design/dao.jing.md, Publication from an agent).

   publish-index! snapshots an agent-local dao.stream, builds the four
   covered indexes as immutable content-addressed dao.data.btree node blobs,
   and appends them to one intake stream selected from an explicit pool. A
   DaoJing observer over the pool materializes the blobs; read-manifest /
   read-datoms / restored-indexes consume them. Everything runs on JVM,
   ClojureScript, and ClojureDart."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [dao.data.btree :as bt]
            [dao.jing :as jing]
            [dao.jing.coordinate :as jing-coordinate]
            [dao.jing.file :as jing-file]
            #?@(:clj [[dao.jing.remote :as jing-remote]])
            [dao.space.index :as index]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]
            #?@(:cljd [["dart:io" :as dart-io]])))


(defrecord MalformedResultStream
  [result]
  ;; Test double: a reader whose every ds/next answer is the configured
  ;; result, used to feed malformed responses into publish-index!'s
  ;; snapshot reading.
  ds/IDaoStreamReader

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
   harness for observing that the published open path fetches only the
   manifest (one get) and faults no tree nodes."
  [store]
  (let [gets (atom 0)
        get-fn (:get-content-fn store)]
    {:store (assoc store
                   :get-content-fn (fn [address not-found]
                                     (swap! gets inc)
                                     (get-fn address not-found))),
     :gets (fn [] @gets)}))


(defn- open-local
  "Open a ringbuffer local (agent) stream pre-loaded with datoms."
  [datoms]
  (let [s (ds/open! {:dao.stream/type :ringbuffer})]
    (doseq [d datoms] (ds/append! s d))
    s))


(defn- open-intake
  "Open a ringbuffer intake stream with capacity large enough for the
   multi-node tests."
  ([] (ds/open! {:dao.stream/type :ringbuffer, :capacity 4096})))


(defn- temp-content-path
  [prefix]
  (str "target/test-index-stream-" prefix "-" (random-uuid) ".log"))


(defn- cleanup-file
  [path]
  #?(:clj (let [f (java.io.File. path)] (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") path) (catch :default _))
     :cljd (try (let [f (dart-io/File path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch Object _ nil))))


(defn- stream-values
  [stream]
  (ds/strict-vec stream))


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
        (is (empty? (ds/->seq nil intake)))))))


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
        (is (empty? (ds/->seq nil intake)))))))


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
      (is (seq (ds/->seq nil a)))
      (is (empty? (ds/->seq nil b)))))
  (testing "a custom :select-stream receives the pool and may pick any member"
    (let [local (open-local (datoms 8))
          a (open-intake)
          b (open-intake)]
      (index/publish-index! local [a b] {:select-stream second})
      (is (empty? (ds/->seq nil a)))
      (is (seq (ds/->seq nil b))))))


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
      (is (empty? (ds/->seq nil a)))
      (is (empty? (ds/->seq nil b)))
      (let [emitted (vec (ds/->seq nil c))]
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
          emitted (vec (ds/->seq nil intake))]
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
          emitted (vec (ds/->seq nil intake))
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
          emitted (vec (ds/->seq nil intake))
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

(deftest publish-index-gap-local-stream-throws-before-emission
  (testing
    "a local stream whose position 0 has been evicted reports :daostream/gap:
          publish-index! throws and nothing reaches the intake stream"
    (let [gap (ds/open! {:dao.stream/type :ringbuffer,
                         :capacity 2,
                         :eviction-policy :evict-oldest})
          _ (doseq [i (range 3)] (ds/append! gap [i :test/a i 0 1]))
          intake (open-intake)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"gap"
            (index/publish-index! gap [intake])))
      (is (empty? (ds/->seq nil intake))
          "nothing reaches the intake stream before the snapshot completes"))))


(deftest publish-index-malformed-local-stream-throws-before-emission
  (testing "a map without :cursor is malformed"
    (let [bad (->MalformedResultStream {:ok [1 :test/a "x" 0 1]})
          intake (open-intake)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"malformed"
            (index/publish-index! bad [intake])))
      (is (empty? (ds/->seq nil intake)))))
  (testing "an unknown signal is malformed"
    (let [bad (->MalformedResultStream :bogus)
          intake (open-intake)]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"malformed"
            (index/publish-index! bad [intake])))
      (is (empty? (ds/->seq nil intake))))))


(deftest publish-index-full-intake-stream-throws
  (testing
    "a :full append throws with the result; only a partial immutable prefix
          of node blobs lands, never the manifest"
    (let [local (open-local (datoms 64))
          intake (ds/open! {:dao.stream/type :ringbuffer, :capacity 1})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"append failed"
            (index/publish-index! local [intake])))
      (let [emitted (vec (ds/->seq nil intake))]
        (is
          (every? node-blob? emitted)
          "only node blobs landed: the manifest is appended last, so it is
            never part of a :full prefix")))))


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


(defn- publish-into-file
  "Publish datoms through an in-memory intake and observe them into a fresh
   file-backed content store; return the live store, its temp path, the
   manifest address, and a published-index descriptor over that store. The
   caller must close (:store ...) and clean up (:path ...). On failure the
   store is closed and the temp file removed before rethrowing."
  ([datoms] (publish-into-file datoms "pub"))
  ([datoms prefix]
   (let [path (temp-content-path prefix)
         store (jing-file/create-content-file path)]
     (try (let [local (open-local datoms)
                intake (open-intake)
                {:keys [manifest-address]} (index/publish-index! local [intake])
                drain (fn drain
                        [state]
                        (let [r (jing/observe-step! store state)]
                          (when (= :ok (:signal r)) (drain (:state r)))))]
            (drain (jing/observer-state [intake]))
            {:store store,
             :path path,
             :manifest-address manifest-address,
             :descriptor (index/published-index {:dao.jing/type :dao.jing/file,
                                                 :path path}
                                                manifest-address)})
          (catch #?(:clj Throwable
                    :cljs :default
                    :cljd Object)
                 e
            (jing/close! store)
            (cleanup-file path)
            (throw e))))))


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


(deftest open-published-rejects-unresolvable-and-malformed-descriptors
  (let [empty-manifest-addr (jing/segment-key
                              {:indexes
                               {:eavt nil, :aevt nil, :avet nil, :vaet nil},
                               :count 0,
                               :branching-factor 512})]
    (testing "an unsupported coordinate type fails closed at open"
      (let [d (index/published-index {:dao.jing/type :dao.jing/missing,
                                      :path "x"}
                                     empty-manifest-addr)]
        (is (thrown-with-msg? #?(:cljs js/Error
                                 :cljd Object
                                 :default Exception)
                              #"unsupported DaoJing content-store coordinate"
              (ds/open! d))
            "the store coordinate is resolved explicitly, never inferred")))
    (testing "a malformed published-index descriptor is rejected at open"
      (let [bogus (assoc (index/published-index {:dao.jing/type :dao.jing/file,
                                                 :path "x"}
                                                empty-manifest-addr)
                         :extra/key :noise)]
        (is (thrown-with-msg? #?(:cljs js/Error
                                 :cljd Object
                                 :default Exception)
                              #"invalid published-index descriptor"
              (ds/open! bogus)))))))


(deftest remote-coordinate-allows-an-explicit-nil-options-entry
  #?(:clj (with-redefs [jing-remote/connect-content!
                        (fn [url options] {:url url, :options options})]
            (is (= {:url "ws://example.test/jing", :options {}}
                   (jing-coordinate/open! {:dao.jing/type :dao.jing/remote,
                                           :url "ws://example.test/jing",
                                           :options nil}))))
     :default (is true "the synchronous remote coordinate is JVM-only")))


(deftest open-published-rejects-missing-and-invalid-manifests
  (let [fx (publish-into-file (datoms 8))]
    (try (testing "a manifest address absent from the store throws at open"
           (let [d (index/published-index
                     {:dao.jing/type :dao.jing/file, :path (:path fx)}
                     (jing/segment-key
                       {:indexes {:eavt nil, :aevt nil, :avet nil, :vaet nil},
                        :count 0,
                        :branching-factor 512}))]
             (is (thrown-with-msg? #?(:cljs js/Error
                                      :cljd Object
                                      :default Exception)
                                   #"missing index manifest"
                   (ds/open! d)))))
         (testing "a stored non-manifest value throws at open"
           (let [bad-addr (jing/materialize! (:store fx) {:not :a-manifest})
                 d (index/published-index {:dao.jing/type :dao.jing/file,
                                           :path (:path fx)}
                                          bad-addr)]
             (is (thrown-with-msg? #?(:cljs js/Error
                                      :cljd Object
                                      :default Exception)
                                   #"invalid index manifest"
                   (ds/open! d)))))
         (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))


(deftest published-realization-is-read-only-with-a-stable-lifecycle
  (let [published-input (vec (reverse (datoms 12))) ; non-EAVT insertion
        ;; order
        expected-eavt (vec (sort index/eavt-cmp published-input))
        fx (publish-into-file published-input)]
    (try (let [published (ds/open! (:descriptor fx))]
           (testing "read-only: a reader and bound, never a writer"
             (is (satisfies? ds/IDaoStreamReader published))
             (is (satisfies? ds/IDaoStreamBound published))
             (is (not (satisfies? ds/IDaoStreamWriter published))))
           (testing "logical elements are canonical d5 in EAVT order"
             (is (= expected-eavt (stream-values published))))
           (testing
             "closed from construction; close is idempotent and non-erasing"
             (is (ds/closed? published))
             (is (= {:woke []} (ds/close! published)))
             (is (= {:woke []} (ds/close! published)) "close! is idempotent")
             (is (= expected-eavt (stream-values published))
                 "close does not erase retained elements"))
           (testing "two cursors advance independently over one realization"
             (let [r1 (ds/next published {:position 0})
                   r2 (ds/next published {:position 0})
                   r1' (ds/next published (:cursor r1))]
               (is (= (first expected-eavt) (:ok r1)))
               (is (= (:ok r1) (:ok r2)) "both cursors read position 0")
               (is (= (second expected-eavt) (:ok r1'))
                   "advancing one cursor reads the next element"))))
         (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))


(deftest published-empty-index-drains-to-end
  (let [fx (publish-into-file [])]
    (try (let [published (ds/open! (:descriptor fx))]
           (is (ds/closed? published))
           (is (= [] (stream-values published)))
           (is (= :end (ds/next published {:position 0}))))
         (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))


(deftest published-open-fetches-only-the-manifest
  (let [fx (publish-into-file (datoms 64))]
    (try
      (let [counter (counting-content-store (:store fx))]
        #?(:cljd
           (is
             true
             "the counting harness needs with-redefs, unavailable on ClojureDart")
           :default
           (with-redefs [jing-coordinate/open! (fn [_] (:store counter))]
             (let [published (ds/open! (:descriptor fx))]
               (is
                 (= 1 ((:gets counter)))
                 "opening a published descriptor performs exactly one content fetch: the manifest, with zero tree nodes faulted")
               (ds/close! published)))))
      (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))


(deftest covered-indexes-returns-the-four-covered-sets
  (let [fx (publish-into-file (datoms 8))]
    (try (let [published (ds/open! (:descriptor fx))]
           (try
             (testing "an opened published realization carries the four sets"
               (let [idx (index/covered-indexes published)]
                 (is (map? idx))
                 (is (= #{:eavt :aevt :avet :vaet} (set (keys idx))))
                 (doseq [order [:eavt :aevt :avet :vaet]]
                   (is (= (count (datoms 8)) (bt/count (order idx)))
                       (str order " covers the snapshot")))))
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
                   "a plain map with the four keys passes, no instance check"))
             (finally (ds/close! published))))
         (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))


(deftest published-next-yields-the-same-eavt-rows-as-the-eager-walk
  (let [datoms (vec (reverse (datoms 24))) ; non-EAVT insertion order
        fx (publish-into-file datoms)]
    (try
      (let [eager (index/read-datoms (:store fx) (:manifest-address fx))
            published (ds/open! (:descriptor fx))]
        (try
          (is (= eager (stream-values published))
              "strict-vec forces the deferred EAVT and yields the eager rows")
          (is (= eager
                 (loop [cursor {:position 0}
                        acc []]
                   (let [r (ds/next published cursor)]
                     (if (map? r) (recur (:cursor r) (conj acc (:ok r))) acc))))
              "stepwise next over the forced delay yields the eager rows")
          (finally (ds/close! published))))
      (finally (jing/close! (:store fx)) (cleanup-file (:path fx))))))


;; ---------------------------------------------------------------------------
;; Observer materialization, then read / restore parity
;; ---------------------------------------------------------------------------

(deftest published-index-is-a-transportable-bounded-stream
  (let [path (temp-content-path "descriptor")
        source-datoms [[2 :work/status :done 1 1] [1 :work/status :todo 0 1]]
        local (open-local source-datoms)
        intake (open-intake)
        store (jing-file/create-content-file path)]
    (try (let [{:keys [manifest-address]} (index/publish-index! local [intake])
               _ (loop [state (jing/observer-state [intake])]
                   (let [{:keys [signal state]} (jing/observe-step! store
                                                                    state)]
                     (when (= :ok signal) (recur state))))
               descriptor (index/published-index {:dao.jing/type :dao.jing/file,
                                                  :path path}
                                                 manifest-address)
               transported (edn/read-string (pr-str descriptor))
               carrier (ds/open! {:dao.stream/type :ringbuffer})]
           (is (= descriptor transported) "descriptor is plain EDN")
           (is (ds/exact-bound? (:dao.stream/bound descriptor)))
           (is (= :dao.space.index/eavt (:dao.stream/comparator descriptor)))
           (ds/append! carrier transported)
           (is (= descriptor (:ok (ds/next carrier {:position 0}))))
           (let [published (ds/open! transported)]
             (try (is (satisfies? ds/IDaoStreamReader published))
                  (is (satisfies? ds/IDaoStreamBound published))
                  (is (not (satisfies? ds/IDaoStreamWriter published)))
                  (is (ds/closed? published))
                  (is (= (sort index/eavt-cmp source-datoms)
                         (stream-values published)))
                  (finally (ds/close! published)))))
         (finally (jing/close! store) (cleanup-file path)))))


(deftest observer-materialization-read-restore-parity
  (testing
    "a DaoJing observer materializing the intake stream makes the published
          manifest readable: the eager walk and the lazy restore agree with
          each other and with the source snapshot, on every covered order"
    (let [datoms (datoms 600)
          local (open-local datoms)
          intake (ds/open! {:dao.stream/type :ringbuffer, :capacity 1024})
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
