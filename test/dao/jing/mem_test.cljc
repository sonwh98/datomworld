(ns dao.jing.mem-test
  "Contract tests for dao.jing.mem/create-content-mem: the ephemeral
   content-addressed in-memory DaoJing byte store
   (docs/design/dao.jing.md, Materialization rule and Reads;
   docs/design/dao.jing.cbor.md, Memory and files).

   The handle is plain data carrying :put-bytes-fn, :get-bytes-fn,
   :close-fn and the test-facing :entries-fn, and is consumed by
   dao.jing/materialize!, dao.jing/get, and dao.jing/close!. It embeds no
   intake stream, no source identity, and no reachable mutable state:
   addresses are derived solely from payloads, equal payloads from a pool
   of dao.stream intake streams converge on exactly one entry, unequal
   bytes at an existing address are an integrity failure, never an
   overwrite, and every byte array crossing the handle is copied so no
   caller can change a stored snapshot. The store's content is observed
   through mem/entries and mem/entry-bytes -- the private state's shape is
   pinned nowhere."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]))


(defn throws?
  "True when (f) throws on every supported host."
  [f]
  (try (f)
       false
       (catch #?(:clj Exception
                 :cljs :default
                 :cljd Object)
              _
         true)))


(defn open-stream
  "A dao.stream ringbuffer reader handle pre-loaded with vals."
  [& vals]
  (let [{:dao.stream/keys [handle]}
        (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                             :dao.stream.ringbuffer/capacity 8})]
    (doseq [v vals] (stream/append! handle v))
    handle))


(defn- bytes-of
  [v]
  (jing/canonical-bytes v))


(defn- set-byte!
  "Overwrite index i of host byte array bs with b, in place."
  [bs i b]
  #?(:clj (aset ^bytes bs i (unchecked-byte b))
     :cljs (aset bs i b)
     :cljd (. bs "[]=" i b)))


;; ---------------------------------------------------------------------------
;; Handle shape
;; ---------------------------------------------------------------------------

(deftest create-content-mem-returns-a-stream-free-byte-store-handle
  (testing
    "the handle is plain data carrying the three backend effects and the
            test-facing snapshot, and nothing mutable"
    (let [h (mem/create-content-mem)]
      (is (map? h))
      (is (fn? (:put-bytes-fn h)))
      (is (fn? (:get-bytes-fn h)))
      (is (fn? (:close-fn h)))
      (is (fn? (:entries-fn h)))
      (is (every? fn? (vals h))
          "no atom, array or state reaches a caller through the handle")))
  (testing "a fresh store holds nothing and identifies no source"
    (let [h (mem/create-content-mem)]
      (is (empty? (mem/entries h))
          "a content store starts with no entries and no provenance"))))


;; ---------------------------------------------------------------------------
;; Opaque-payload contract
;; ---------------------------------------------------------------------------

(deftest materialize-mints-the-address-automatically
  (testing
    "jing/materialize! derives the address from the payload alone and
            the value round-trips unchanged"
    (let [h (mem/create-content-mem)
          payload {:a 1, :b [1 2 3]}
          address (jing/materialize! h payload)]
      (is (= (jing/segment-key payload) address))
      (is (= "segment" (namespace address)))
      (is (= payload (jing/get h address ::missing)))
      (is (= {address payload} (mem/entries h))
          "exactly the payload is stored: no provenance, no source identity")
      (is (cbor/bytes= (bytes-of payload)
                       (get (mem/entry-bytes h) address))
          "what the store holds is the payload's canonical bytes"))))


(deftest opaque-payloads-round-trip-including-nil
  (testing
    "any payload value is stored and retrieved unchanged; nil is a
            legal opaque payload"
    (doseq [p [nil 42 "hello" :kw [1 2 3] {:a 1} #{:x :y} [:a [:b]]]]
      (let [h (mem/create-content-mem)
            address (jing/materialize! h p)]
        (is (= (jing/segment-key p) address) (str "address of " (pr-str p)))
        (is (= p (jing/get h address ::missing))
            (str "round trip of " (pr-str p)))))))


(deftest insert-is-idempotent
  (testing
    "the first put reports :inserted; equal bytes report :present and
            are never overwritten"
    (let [h (mem/create-content-mem)
          payload {:x 42}
          address (jing/segment-key payload)]
      (is (= :inserted ((:put-bytes-fn h) address (bytes-of payload))))
      (is (= :present ((:put-bytes-fn h) address (bytes-of payload))))
      (is (= payload (jing/get h address ::missing)))
      (is (= {address payload} (mem/entries h)))))
  (testing "materialize! is idempotent end to end"
    (let [h (mem/create-content-mem)
          payload {:x 42}]
      (is (= (jing/materialize! h payload) (jing/materialize! h payload)))
      (is (= 1 (count (mem/entries h)))))))


(deftest put-rejects-invalid-content-addresses
  (testing
    "only :segment/<algorithm>-... content addresses are insertable;
            arbitrary keys, roots, and malformed hashes throw before any
            write"
    (let [h (mem/create-content-mem)]
      (doseq [bad [:root/pointer :plain :segment/not-a-hash :segment/sha256-xyz
                   42 "abc" nil [1 2] {:k :v}]]
        (is (throws? #((:put-bytes-fn h) bad (bytes-of {:x 1})))
            (str "must reject " (pr-str bad))))
      (is (empty? (mem/entries h))))))


(deftest put-rejects-address-bytes-hash-mismatch
  (testing
    "a well-formed address whose digest is not the hash of the bytes is
            rejected before any write, as is a non-byte payload"
    (let [h (mem/create-content-mem)
          address (jing/segment-key {:a 1})]
      (is (throws? #((:put-bytes-fn h) address (bytes-of {:a 2}))))
      (is (throws? #((:put-bytes-fn h) address {:a 1}))
          "a value is not bytes: the backend takes canonical bytes only")
      (is (empty? (mem/entries h)))))
  (testing "an address that does hash to the bytes is accepted"
    (let [h (mem/create-content-mem)]
      (is (= :inserted
             ((:put-bytes-fn h) (jing/segment-key {:a 1})
                                (bytes-of {:a 1})))))))


(deftest collision-preserves-the-existing-bytes
  (testing
    "unequal bytes already seated at an address are never overwritten
            and the mismatch is reported loudly"
    (let [payload {:b 1}
          address (jing/segment-key payload)
          h (mem/create-content-mem {address (bytes-of {:a 1})})]
      (is (throws? #((:put-bytes-fn h) address (bytes-of payload))))
      (is (cbor/bytes= (bytes-of {:a 1}) (get (mem/entry-bytes h) address))
          "the existing bytes are untouched")
      (is (throws? #(jing/get h address ::missing))
          "a read of bytes that do not hash to their address is refused")))
  (testing
    "the collision is visible through materialize! as an integrity
            failure"
    (let [payload {:b 1}
          address (jing/segment-key payload)
          h (mem/create-content-mem {address (bytes-of {:a 1})})]
      (is (throws? #(jing/materialize! h payload)))
      (is (cbor/bytes= (bytes-of {:a 1}) (get (mem/entry-bytes h) address))
          "materialize! never overwrites"))))


(deftest close-is-idempotent-and-throws-after
  (testing "close! and :close-fn are idempotent and return nil"
    (let [h (mem/create-content-mem)]
      (is (nil? (jing/close! h)))
      (is (nil? (jing/close! h)))
      (is (nil? ((:close-fn h))))
      (is (throws? #((:put-bytes-fn h)
                     (jing/segment-key {:probe 1})
                     (bytes-of {:probe 1})))
          "a closed store accepts no puts")))
  (testing
    "get/put through every entry point throw after close and never
            mutate content"
    (let [h (mem/create-content-mem)
          payload {:a 1}
          address (jing/materialize! h payload)
          content-before (mem/entries h)]
      (jing/close! h)
      (is (throws? #(jing/materialize! h payload)))
      (is (throws? #(jing/get h address ::missing)))
      (is (throws? #((:put-bytes-fn h) address (bytes-of payload))))
      (is (throws? #((:get-bytes-fn h) address ::missing)))
      (is (= content-before (mem/entries h))
          "close neither clears nor rewrites stored content"))))


(deftest get-distinguishes-absence-from-stored-nil
  (testing
    "the caller-supplied not-found is returned only for absent
            addresses; stored nil is returned as nil, never conflated with
            absence"
    (let [h (mem/create-content-mem)
          nil-address (jing/materialize! h nil)
          absent-address (jing/segment-key {:never 1})]
      (is (nil? (jing/get h nil-address ::missing))
          "stored nil round-trips as nil")
      (is (= ::missing (jing/get h absent-address ::missing))
          "absence returns the caller-supplied not-found")
      (is (cbor/bytes= (bytes-of nil) ((:get-bytes-fn h) nil-address ::missing))
          "the backend answers the bytes of CBOR nil, not the sentinel")
      (is (= ::missing ((:get-bytes-fn h) absent-address ::missing))))))


;; ---------------------------------------------------------------------------
;; Mutation isolation: bytes are copied on the way in and on the way out
;; ---------------------------------------------------------------------------

(deftest a-caller-cannot-mutate-a-snapshot-through-its-input-array
  (testing
    "changing the byte array handed to put after the put changes nothing
            the store holds"
    (let [h (mem/create-content-mem)
          payload {:isolated "input"}
          address (jing/segment-key payload)
          bs (bytes-of payload)]
      (is (= :inserted ((:put-bytes-fn h) address bs)))
      (set-byte! bs (dec (count (vec bs))) 0)
      (is (= payload (jing/get h address ::missing))
          "the stored snapshot still decodes to the payload")
      (is (cbor/bytes= (bytes-of payload) ((:get-bytes-fn h) address ::missing))
          "the stored bytes are the original canonical bytes"))))


(deftest a-caller-cannot-mutate-a-snapshot-through-an-output-array
  (testing
    "changing the byte array a get answered, or one the snapshot view
            answered, changes nothing the store holds"
    (let [h (mem/create-content-mem)
          payload {:isolated "output"}
          address (jing/materialize! h payload)
          out ((:get-bytes-fn h) address ::missing)
          view (get (mem/entry-bytes h) address)]
      (set-byte! out 0 0)
      (set-byte! view 0 0)
      (is (= payload (jing/get h address ::missing))
          "a later read still verifies and decodes")
      (is (cbor/bytes= (bytes-of payload) ((:get-bytes-fn h) address ::missing))
          "a later read answers the original bytes")
      (is (= {address payload} (mem/entries h))
          "the snapshot view is unaffected by mutation of an earlier view"))))


(deftest entries-verify-before-decoding
  (testing
    "the test-facing entries view hash-verifies every snapshot against its
            address, so seeded forged bytes surface as a refusal, never as
            a value"
    (let [address (jing/segment-key {:claimed 1})
          h (mem/create-content-mem {address (bytes-of {:forged true})})]
      (is (throws? #(mem/entries h)))
      (is (cbor/bytes= (bytes-of {:forged true})
                       (get (mem/entry-bytes h) address))
          "the raw snapshot view still shows what was planted"))))


;; ---------------------------------------------------------------------------
;; Observer pool integration
;; ---------------------------------------------------------------------------

(deftest observer-pool-equal-payloads-converge-to-one-entry
  (testing
    "equal payloads arriving through two dao.stream intake streams
            land on exactly one stored entry carrying no source identity"
    (let [h (mem/create-content-mem)
          payload {:nested {:v [1 2 3]}}
          address (jing/segment-key payload)
          a (open-stream payload)
          b (open-stream payload)
          enter (fn [s]
                  {:stream s
                   :cursor (:dao.stream/cursor
                             (stream/cursor s :dao.stream/oldest))})
          state (jing/observer-state (mapv enter [a b]))
          r1 (jing/observe-step! h state)
          r2 (jing/observe-step! h (:state r1))]
      (is (= :dao.stream/ok (:signal r1)))
      (is (= :dao.stream/ok (:signal r2)))
      (is (= address (:address r1)))
      (is (= address (:address r2))
          "both intake streams converge on the same content address")
      (is (= {address payload} (mem/entries h))
          "exactly one entry: no duplicates, no provenance, no source"))))


;; ---------------------------------------------------------------------------
;; Concurrency (JVM only)
;; ---------------------------------------------------------------------------

(deftest concurrent-inserts-report-exactly-one-inserted
  #?(:clj
     (testing
       "under JVM thread contention exactly one put wins with
               :inserted, every other thread observes :present, and the
               store holds exactly one entry"
       (let [h (mem/create-content-mem)
             payload {:contended :payload}
             address (jing/segment-key payload)
             n 16
             results (java.util.concurrent.ConcurrentLinkedQueue.)
             barrier (java.util.concurrent.CyclicBarrier. n)]
         (let [threads (mapv (fn [_]
                               (Thread. (fn []
                                          (.await barrier)
                                          (.add results
                                                ((:put-bytes-fn h)
                                                 address
                                                 (bytes-of payload))))))
                             (range n))]
           (doseq [t threads] (.start t))
           (doseq [t threads] (.join t)))
         (let [rs (into [] results)]
           (is (= 1 (count (filter #(= :inserted %) rs))))
           (is (= (dec n) (count (filter #(= :present %) rs))))
           (is (= 1 (count (mem/entries h))))
           (is (= {address payload} (mem/entries h))))))
     :cljs (is true)
     :cljd (is true)))


(deftest multi-algorithm-memory-store-coexistence-test
  (testing (str "same payload can be materialized and retrieved under "
                "both algorithms")
    (let [h           (mem/create-content-mem)
          payload     {:entity 42, :data "multi-algorithm payload"}
          addr-blake3 (jing/materialize! h payload)
          addr-sha256 (jing/materialize! h payload {:algorithm :sha256})]
      (is (= :blake3 (jing/segment-algorithm addr-blake3)))
      (is (= :sha256 (jing/segment-algorithm addr-sha256)))
      (is (not= addr-blake3 addr-sha256))
      (is (= 2 (count (mem/entries h))))
      (is (= payload (jing/get h addr-blake3 ::absent)))
      (is (= payload (jing/get h addr-sha256 ::absent)))
      (is (jing/segment-matches? addr-blake3
                                 (jing/get h addr-blake3 ::absent)))
      (is (jing/segment-matches? addr-sha256
                                 (jing/get h addr-sha256 ::absent)))))
  (testing "cross-algorithm mismatch fails segment-matches?"
    (let [payload       {:hello "world"}
          addr-blake3   (jing/segment-key payload)
          addr-sha256   (jing/segment-key payload {:algorithm :sha256})
          other-payload {:hello "universe"}]
      (is (not (jing/segment-matches? addr-blake3 other-payload)))
      (is (not (jing/segment-matches? addr-sha256 other-payload)))
      (let [forged-b3 (keyword "segment"
                               (str "blake3-" (subs (name addr-sha256) 7)))]
        (is (not (jing/segment-matches? forged-b3 payload)))))))
