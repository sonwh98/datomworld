(ns dao.jing.mem-test
  "Contract tests for dao.jing.mem/create-content-mem: the ephemeral
   content-addressed in-memory DaoJing content store
   (docs/design/dao.jing.md, Materialization rule and Reads).

   The handle is plain data carrying explicit private state plus
   :put-content-fn, :get-content-fn, and :close-fn, and is consumed by
   dao.jing/materialize!, dao.jing/get, and dao.jing/close!. It embeds no
   intake stream and no source identity: addresses are derived solely from
   payloads, equal payloads from a pool of intake streams converge on
   exactly one entry, and an unequal payload at an existing address is an
   integrity failure, never an overwrite."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]))


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
  "Open a ringbuffer transport pre-loaded with vals."
  [& vals]
  (let [s (ds/open! {:dao.stream/type :ringbuffer, :capacity 8})]
    (doseq [v vals] (ds/append! s v))
    s))


;; ---------------------------------------------------------------------------
;; Handle shape
;; ---------------------------------------------------------------------------

(deftest create-content-mem-returns-a-stream-free-content-handle
  (testing
    "the handle is plain data with explicit private state and the
            three backend effects"
    (let [h (mem/create-content-mem)]
      (is (map? h))
      (is (fn? (:put-content-fn h)))
      (is (fn? (:get-content-fn h)))
      (is (fn? (:close-fn h)))
      (is (= {:closed? false, :content {}} @(:state h)))))
  (testing "the handle embeds no intake stream and stores no source identity"
    (let [h (mem/create-content-mem)]
      (is (not (contains? h :stream))
          "a content store carries no intake stream")
      (is (empty? (:content @(:state h)))))))


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
      (is
        (= {address payload} (:content @(:state h)))
        "exactly the payload is stored: no provenance stamp, no source identity"))))


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
    "the first put reports :inserted; an equal payload reports
            :present and is never overwritten"
    (let [h (mem/create-content-mem)
          payload {:x 42}
          address (jing/segment-key payload)]
      (is (= :inserted ((:put-content-fn h) address payload)))
      (is (= :present ((:put-content-fn h) address payload)))
      (is (= payload (jing/get h address ::missing)))
      (is (= {address payload} (:content @(:state h))))))
  (testing "materialize! is idempotent end to end"
    (let [h (mem/create-content-mem)
          payload {:x 42}]
      (is (= (jing/materialize! h payload) (jing/materialize! h payload)))
      (is (= 1 (count (:content @(:state h))))))))


(deftest put-rejects-invalid-content-addresses
  (testing
    "only :segment/sha256-... content addresses are insertable;
            arbitrary keys, roots, and malformed hashes throw before any
            write"
    (let [h (mem/create-content-mem)]
      (doseq [bad [:root/pointer :plain :segment/not-a-hash :segment/sha256-xyz
                   42 "abc" nil [1 2] {:k :v}]]
        (is (throws? #((:put-content-fn h) bad {:x 1}))
            (str "must reject " (pr-str bad))))
      (is (= {} (:content @(:state h)))))))


(deftest put-rejects-address-payload-hash-mismatch
  (testing
    "a well-formed address whose hash does not match the payload is
            rejected before any write"
    (let [h (mem/create-content-mem)
          address (jing/segment-key {:a 1})]
      (is (throws? #((:put-content-fn h) address {:a 2})))
      (is (= {} (:content @(:state h))))))
  (testing "an address that does hash to the payload is accepted"
    (let [h (mem/create-content-mem)]
      (is (= :inserted
             ((:put-content-fn h) (jing/segment-key {:a 1}) {:a 1}))))))


(deftest collision-preserves-the-existing-value
  (testing
    "an unequal payload already seated at an address is never
            overwritten and the mismatch is reported loudly"
    (let [h (mem/create-content-mem)
          payload {:b 1}
          address (jing/segment-key payload)]
      (swap! (:state h) assoc-in [:content address] {:a 1})
      (is (throws? #((:put-content-fn h) address payload)))
      (is (= {:a 1} (get-in @(:state h) [:content address]))
          "the existing value is untouched")))
  (testing
    "the collision is visible through materialize! as an integrity
            failure"
    (let [h (mem/create-content-mem)
          payload {:b 1}
          address (jing/segment-key payload)]
      (swap! (:state h) assoc-in [:content address] {:a 1})
      (is (throws? #(jing/materialize! h payload)))
      (is (= {:a 1} (get-in @(:state h) [:content address]))
          "materialize! never overwrites"))))


(deftest close-is-idempotent-and-throws-after
  (testing "close! and :close-fn are idempotent and return nil"
    (let [h (mem/create-content-mem)]
      (is (nil? (jing/close! h)))
      (is (nil? (jing/close! h)))
      (is (nil? ((:close-fn h))))
      (is (true? (:closed? @(:state h))))))
  (testing
    "get/put through every entry point throw after close and never
            mutate content"
    (let [h (mem/create-content-mem)
          payload {:a 1}
          address (jing/materialize! h payload)
          content-before (:content @(:state h))]
      (jing/close! h)
      (is (throws? #(jing/materialize! h payload)))
      (is (throws? #(jing/get h address ::missing)))
      (is (throws? #((:put-content-fn h) address payload)))
      (is (throws? #((:get-content-fn h) address ::missing)))
      (is (= content-before (:content @(:state h)))
          "close neither clears nor rewrites stored content")
      (is (true? (:closed? @(:state h)))))))


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
      (is (nil? ((:get-content-fn h) nil-address ::missing)))
      (is (= ::missing ((:get-content-fn h) absent-address ::missing))))))


;; ---------------------------------------------------------------------------
;; Observer pool integration
;; ---------------------------------------------------------------------------

(deftest observer-pool-equal-payloads-converge-to-one-entry
  (testing
    "equal payloads arriving through two intake streams land on
            exactly one stored entry carrying no source identity"
    (let [h (mem/create-content-mem)
          payload {:nested {:v [1 2 3]}}
          address (jing/segment-key payload)
          a (open-stream payload)
          b (open-stream payload)
          state (jing/observer-state [a b])
          r1 (jing/observe-step! h state)
          r2 (jing/observe-step! h (:state r1))]
      (is (= :ok (:signal r1)))
      (is (= :ok (:signal r2)))
      (is (= address (:address r1)))
      (is (= address (:address r2))
          "both intake streams converge on the same content address")
      (is
        (= {address payload} (:content @(:state h)))
        "exactly one entry: no duplicates, no provenance, no source identity"))))


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
                                                ((:put-content-fn h)
                                                 address
                                                 payload)))))
                             (range n))]
           (doseq [t threads] (.start t))
           (doseq [t threads] (.join t)))
         (let [rs (into [] results)]
           (is (= 1 (count (filter #(= :inserted %) rs))))
           (is (= (dec n) (count (filter #(= :present %) rs))))
           (is (= 1 (count (:content @(:state h)))))
           (is (= {address payload} (:content @(:state h)))))))
     :cljs (is true)
     :cljd (is true)))
