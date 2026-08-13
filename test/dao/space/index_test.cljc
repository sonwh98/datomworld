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
            [dao.data.btree :as bt]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]))


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


(defn- open-local
  "Open a ringbuffer local (agent) stream pre-loaded with datoms."
  [datoms]
  (let [s (ds/open! {:type :ringbuffer})]
    (doseq [d datoms] (ds/append! s d))
    s))


(defn- open-intake
  "Open a ringbuffer intake stream with capacity large enough for the
   multi-node tests."
  ([] (ds/open! {:type :ringbuffer, :capacity 4096})))


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
                    {:dao.space/transaction {:t 1, :datoms [[1 :a :v 2 1]]}}
                    {:dao.space/transaction
                     {:t 1, :datoms [[1 :a :v 1 1 :source/forbidden]]}}]]
      (let [local (open-local [packet])
            intake (open-intake)]
        (is (thrown-with-msg? #?(:cljs js/Error
                                 :cljd Object
                                 :default Exception)
                              #"transaction"
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
    (let [local (open-local [[1 :a "x" 0 1] [2 :a "y" 0 1]])
          intake (open-intake)
          {:keys [manifest]} (index/publish-index! local [intake])
          emitted (vec (ds/->seq nil intake))]
      (is (= 2 (count emitted)) "one shared node blob, then the manifest")
      (is (node-blob? (first emitted)))
      (is (= manifest (second emitted)))
      (is (= #{:eavt :aevt :avet :vaet} (set (keys (:indexes manifest)))))
      (is (apply = (vals (:indexes manifest)))
          "all four indexes resolve to the same shared root blob"))))


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
    (let [gap (ds/open! {:type :ringbuffer,
                         :capacity 2,
                         :eviction-policy :evict-oldest})
          _ (doseq [i (range 3)] (ds/append! gap [i :a i 0 1]))
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
    (let [bad (->MalformedResultStream {:ok [1 :a "x" 0 1]})
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
          intake (ds/open! {:type :ringbuffer, :capacity 1})]
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
          intake (ds/open! {:type :ringbuffer, :capacity 1024})
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
    (is (pos? (index/compare-vals :b "a")))
    (is (zero? (index/compare-vals 1 1.0))))
  (testing "index-datoms keeps every datom in every covered order"
    (let [d1 [1 :a "x" 0 1]
          d2 [2 :b "y" 0 1]
          stamped [1 :a "x" 0 1 :ns/alpha]
          idx (index/index-datoms [d2 d1 stamped])]
      (doseq [order [:eavt :aevt :avet :vaet]]
        (is (= #{d1 d2 stamped} (set (order idx)))
            (str order " holds all three datoms")))
      (is (not (zero? (index/eavt-cmp d1 stamped)))
          "the ns tiebreaker separates otherwise-identical datoms")))
  (testing "subseq-from slices a log-n descent from a sentinel"
    (let [d1 [1 :a "x" 0 1]
          d2 [2 :b "y" 0 1]
          d3 [3 :c "z" 0 1]
          idx (index/index-datoms [d1 d2 d3])]
      (is (= [d2 d3]
             (vec (index/subseq-from (:eavt idx) index/eavt-cmp d2)))))))
