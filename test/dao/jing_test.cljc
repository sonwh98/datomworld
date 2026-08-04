(ns dao.jing-test
  "Contract tests for the dao.jing storage boundary.

  Pins the IKVStore protocol's observable contract: values are opaque (stored
  and returned verbatim, any value legal including nil), put! is an
  unconditional replace, cas! guards on the expected previous value (jing/absent
  meaning \"must not exist\") and leaves the entry untouched when it loses,
  delete!, and, on the JVM only, cas! under contention with no lost updates."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn])
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.jing.file :as file]))


(defn run-with-stores
  [f]
  (doseq [make [(fn [] [(mem/create-kv-mem) nil])
                (fn []
                  (let [path (str "target/test-db-" (random-uuid) ".db")]
                    [(file/create-kv-file path) path]))]]
    (let [[store path] (make)]
      (try (f store)
           (finally (jing/close! store)
                    (when path
                      #?(:clj (.delete (java.io.File. path))
                         :cljs (.unlinkSync (js/require "fs") path)
                         :cljd nil)))))))


;; ---------------------------------------------------------------------------
;; cas! (creation) / get
;; ---------------------------------------------------------------------------

(deftest cas-absent-then-get-round-trips
  (testing "cas! with absent stores the value and get returns it unchanged"
    (run-with-stores (fn [store]
                       (is (true?
                             (jing/cas! store :a jing/absent {:bytes [1 2 3]})))
                       (is (= {:bytes [1 2 3]} (jing/get store :a nil))))))
  (testing
    "the value is opaque: any value round-trips = to what was written, with
    nothing added, removed, or reordered"
    (run-with-stores (fn [store]
                       (doseq [[k v] {:int 42,
                                      :string "hello",
                                      :keyword :kw,
                                      :vector [1 2 3],
                                      :nested {:a {:b [1 #{2} "3"]}},
                                      :empty-map {},
                                      :empty-vec [],
                                      :list '(1 2 3),
                                      :set #{:x :y}}]
                         (is (true? (jing/cas! store k jing/absent v)))
                         (is (= v (jing/get store k ::missing))
                             (str (pr-str v) " must round-trip unchanged")))))))


(deftest nil-is-a-legal-value-distinct-from-absence
  (testing
    "a stored nil is a value, not a hole: get must report it as present and
    absence must be observable only through not-found"
    (run-with-stores
      (fn [store]
        (is (true? (jing/cas! store :n jing/absent nil)))
        (is (nil? (jing/get store :n ::missing))
            "a stored nil comes back as nil, not as not-found")
        (is (= ::missing (jing/get store :never-written ::missing))
            "an absent key is distinguishable from a stored nil")
        (is (= jing/absent (jing/get store :never-written jing/absent))
            "jing/absent serves as the not-found sentinel")))))


(deftest get-absent-returns-not-found
  (testing "get returns the caller-supplied not-found value for an absent key"
    (run-with-stores (fn [store]
                       (is (= :sentinel (jing/get store :absent :sentinel)))
                       (jing/cas! store :a jing/absent {:x 1})
                       (is (= :sentinel (jing/get store :neighbor :sentinel))
                           "a present key leaves every other key absent")))))


(deftest cas-replaces-guarded
  (testing "cas! replaces the value when expected matches"
    (run-with-stores (fn [store]
                       (jing/cas! store :k jing/absent {:v 1})
                       (jing/cas! store :k {:v 1} {:v 2})
                       (is (= {:v 2} (jing/get store :k nil)))))))


;; ---------------------------------------------------------------------------
;; cas!
;; ---------------------------------------------------------------------------

(deftest cas-succeeds-when-expected-matches
  (testing
    "cas! quoting the current value returns true and installs the new one"
    (run-with-stores
      (fn [store]
        (jing/cas! store :root jing/absent {:pointer "a"})
        (is (true? (jing/cas! store :root {:pointer "a"} {:pointer "b"})))
        (is (= {:pointer "b"} (jing/get store :root nil)))))))


(deftest cas-fails-on-stale-expected
  (testing
    "cas! quoting a value that is no longer current returns false and leaves
    the entry untouched"
    (run-with-stores
      (fn [store]
        (jing/cas! store :root jing/absent {:pointer "a"})
        (is (false? (jing/cas! store :root {:pointer "stale"} {:pointer "b"})))
        (is (= {:pointer "a"} (jing/get store :root nil)))))))


(deftest cas-with-absent-creates-only-when-missing
  (testing "cas! against jing/absent creates a key that does not yet exist"
    (run-with-stores
      (fn [store]
        (is (true? (jing/cas! store :fresh jing/absent {:pointer "a"})))
        (is (= {:pointer "a"} (jing/get store :fresh nil))))))
  (testing "and is refused once the key exists"
    (run-with-stores
      (fn [store]
        (jing/cas! store :taken jing/absent {:pointer "a"})
        (is (false? (jing/cas! store :taken jing/absent {:pointer "b"})))
        (is (= {:pointer "a"} (jing/get store :taken nil))))))
  (testing "a present key whose value is nil is still present, not absent"
    (run-with-stores (fn [store]
                       (jing/cas! store :holds-nil jing/absent nil)
                       (is (false?
                             (jing/cas! store :holds-nil jing/absent {:v 1}))
                           "absent must not match a stored nil")
                       (is (true? (jing/cas! store :holds-nil nil {:v 1}))
                           "nil is quotable as the expected value")))))


(deftest cas-replaces-value-wholesale
  (testing "cas! stores the new value wholesale (replace, not merge)"
    (run-with-stores
      (fn [store]
        (jing/cas! store :k jing/absent {:keep "yes", :drop "yes"})
        (is (true?
              (jing/cas! store :k {:keep "yes", :drop "yes"} {:keep "yes"})))
        (is (= {:keep "yes"} (jing/get store :k nil)))))))


(deftest cas-chains-through-successive-values
  (testing "each cas! quotes the value the previous one installed"
    (run-with-stores (fn [store]
                       (jing/cas! store :k jing/absent {:n 0})
                       (doseq [n [1 2 3]]
                         (is (true? (jing/cas! store :k {:n (dec n)} {:n n})))
                         (is (= {:n n} (jing/get store :k nil))))))))


(deftest cas-compares-by-value-not-identity
  (testing
    "the guard is =, so an equal-but-not-identical expected value still wins"
    (run-with-stores
      (fn [store]
        (jing/cas! store :k jing/absent {:a [1 2 3]})
        (is (true? (jing/cas! store :k {:a (vec (range 1 4))} {:a :done}))
            "a freshly built equal value must match")))))


;; ---------------------------------------------------------------------------
;; delete! / close!
;; ---------------------------------------------------------------------------

(deftest delete-removes-key
  (testing "delete! removes the entry, so a later get returns not-found"
    (run-with-stores (fn [store]
                       (jing/cas! store :a jing/absent {:x 1})
                       (is (true? (jing/delete! store :a)))
                       (is (= :gone (jing/get store :a :gone)))))))


(deftest delete-absent-is-noop
  (testing "delete! on an absent key returns true and leaves nothing behind"
    (run-with-stores (fn [store]
                       (is (true? (jing/delete! store :ghost)))
                       (is (= :absent (jing/get store :ghost :absent)))))))


(deftest close-releases-without-throwing
  (testing "close! returns nil and does not throw"
    (run-with-stores (fn [store] (is (nil? (jing/close! store)))))))


;; ---------------------------------------------------------------------------
;; Crash Recovery (File Only)
;; ---------------------------------------------------------------------------

(deftest file-store-crash-recovery
  (testing
    "closing a file store and reopening it recovers the exact index and revisions"
    (let [path (str "target/crash-test-" (random-uuid) ".db")
          store1 (file/create-kv-file path)]
      (jing/cas! store1 :a jing/absent {:x 1})
      (jing/cas! store1 :root jing/absent {:pointer "p1"})
      (jing/cas! store1 :root {:pointer "p1"} {:pointer "p2"})
      (jing/delete! store1 :a)
      (jing/close! store1)
      (let [store2 (file/create-kv-file path)]
        (is (= :absent (jing/get store2 :a :absent)))
        (is (= {:pointer "p2"} (jing/get store2 :root nil)))
        (jing/close! store2))
      #?(:clj (.delete (java.io.File. path))
         :cljs (.unlinkSync (js/require "fs") path)
         :cljd nil))))


(deftest file-store-recovers-from-torn-tail
  #?(:clj
     (testing
       "a crash mid-append leaves a partial record; recovery skips it, keeps every complete record, and truncates the tail"
       (let [path (str "target/torn-test-" (random-uuid) ".db")
             store1 (file/create-kv-file path)]
         (jing/cas! store1 :a jing/absent {:x 1})
         (jing/cas! store1 :root jing/absent {:pointer "p1"})
         (jing/close! store1)
         (let [clean-len (.length (java.io.File. path))]
           ;; Simulate a torn write: a length header claiming 999 bytes
           ;; followed by only a few, exactly what a crash mid-append
           ;; would leave behind.
           (with-open [raf (java.io.RandomAccessFile. path "rw")]
             (.seek raf (.length raf))
             (.writeInt raf 999)
             (.write raf (.getBytes "partial" "UTF-8")))
           (is (> (.length (java.io.File. path)) clean-len)
               "the torn tail is on disk before recovery")
           (let [store2 (file/create-kv-file path)]
             (is (= {:x 1} (jing/get store2 :a nil))
                 "complete records before the tear survive")
             (is (= {:pointer "p1"} (jing/get store2 :root nil)))
             (jing/close! store2))
           (is
             (= clean-len (.length (java.io.File. path)))
             "recovery truncates the torn tail, restoring a clean append boundary")
           (.delete (java.io.File. path)))))))


(deftest file-store-recovers-from-corrupt-payload
  #?(:clj
     (testing
       "a length-consistent but unparseable record survives frame truncation; recovery stops the walk instead of crashing open"
       (let [path (str "target/corrupt-test-" (random-uuid) ".db")
             store1 (file/create-kv-file path)]
         (jing/cas! store1 :a jing/absent {:x 1})
         (jing/cas! store1 :root jing/absent {:pointer "p1"})
         (jing/close! store1)
         ;; Append a frame whose length header matches its payload
         ;; exactly (so the transport's boundary scan keeps it) but whose
         ;; bytes are not EDN, e.g. bit-rot on a complete record.
         (let [garbage (.getBytes "}}}not-edn[[[" "UTF-8")]
           (with-open [raf (java.io.RandomAccessFile. path "rw")]
             (.seek raf (.length raf))
             (.writeInt raf (alength garbage))
             (.write raf garbage)))
         (let [store2 (file/create-kv-file path)]
           (is
             (= {:x 1} (jing/get store2 :a nil))
             "records before the corrupt frame are recovered, not crashed on")
           (is (= {:pointer "p1"} (jing/get store2 :root nil)))
           (jing/close! store2))
         (.delete (java.io.File. path))))))


;; ---------------------------------------------------------------------------
;; Compaction (File Only)
;; ---------------------------------------------------------------------------

(deftest file-store-compaction
  #?(:clj
     (testing
       "compaction removes dead keys, shrinks the file, and leaves live keys intact"
       (let [path (str "target/compact-test-" (random-uuid) ".db")
             store (file/create-kv-file path)]
         ;; Write a mix of keys
         (jing/cas! store :live jing/absent {:x 1})
         (jing/cas! store :dead jing/absent {:x 2})
         ;; Update a key multiple times (creates dead space)
         (jing/cas! store :root jing/absent {:p "1"})
         (jing/cas! store :root {:p "1"} {:p "2"})
         (jing/cas! store :root {:p "2"} {:p "3"})
         ;; Delete a key (creates dead space and a tombstone)
         (jing/delete! store :dead)
         (let [pre-size (.length (java.io.File. path))]
           (is (true? (file/compact-store! store)))
           (let [post-size (.length (java.io.File. path))]
             (is (< post-size pre-size)
                 "the file size should shrink after compaction")
             ;; Verify live keys are intact
             (is (= {:x 1} (jing/get store :live nil)))
             (is (= {:p "3"} (jing/get store :root nil)))
             (is (= :gone (jing/get store :dead :gone)))
             ;; Write after compact to verify the new stream is writable
             (is (true? (jing/cas! store :post jing/absent {:x 3})))
             (is (= {:x 3} (jing/get store :post nil)))
             (jing/close! store)
             (.delete (java.io.File. path))))))))


;; ---------------------------------------------------------------------------
;; Concurrency (JVM only; cljs is single-threaded, so real contention is clj)
;; ---------------------------------------------------------------------------

(deftest cas-contention-loses-no-updates
  #?(:clj
     (testing
       "concurrent cas! retries apply exactly one increment per worker with no lost updates"
       (run-with-stores
         (fn [store]
           (let [n 200
                 _ (jing/cas! store :counter jing/absent {:n 0})
                 wins (atom 0)
                 workers (doall
                           (for [_ (range n)]
                             (future
                               (loop []
                                 ;; the value read *is* the guard:
                                 ;; nothing of the store's to strip back
                                 ;; out before proposing the successor,
                                 ;; and no revision to carry alongside
                                 (let [cur
                                       (jing/get store :counter jing/absent)
                                       proposed (update cur :n inc)]
                                   (if (jing/cas! store :counter cur proposed)
                                     (swap! wins inc)
                                     (recur)))))))]
             (run! deref workers)
             (let [final (jing/get store :counter nil)]
               (is (= n @wins) "every worker must win its cas exactly once")
               (is
                 (= n (:n final))
                 "the counter must equal the number of applied updates"))))))))


;; ---------------------------------------------------------------------------
;; Read-after-close and read/compact races (JVM only)
;; ---------------------------------------------------------------------------

(deftest get-after-close-terminates
  #?(:clj
     (testing
       "get on a closed store returns promptly instead of spinning in the ::closed retry loop"
       ;; After close! the stream is closed, so ds/next throws and get
       ;; catches it as ::closed. The retry re-reads the same closed
       ;; stream and loops, because it never checks the store's :closed
       ;; flag: the transient compaction-swap case and the
       ;; permanent-close case are indistinguishable to the retry. The
       ;; result is either an unbounded busy-wait (ds/next blocks) or a
       ;; StackOverflowError (ds/next throws and get self-recurses
       ;; without tail-call elimination). The deref timeout turns that
       ;; hang into a deterministic failure rather than wedging the whole
       ;; suite.
       (let [path (str "target/closed-get-test-" (random-uuid) ".db")
             store (file/create-kv-file path)]
         (try
           (jing/cas! store :k jing/absent {:x 1})
           (jing/close! store)
           (let [outcome (deref (future
                                  (try {:value (jing/get store :k :not-found)}
                                       (catch Throwable t
                                         {:threw (str (type t))})))
                                2000
                                {:timeout true})]
             (is
               (= {:value :not-found} outcome)
               (str
                 "get on a closed store should return the not-found sentinel "
                 "promptly; got "
                 outcome)))
           (finally (.delete (java.io.File. path))))))))


(deftest get-during-compaction-stays-correct
  #?(:clj
     (testing
       "a concurrent lock-free get never throws or observes a wrong/absent value for a live key while compact! swaps the stream"
       (let [path (str "target/compact-race-test-" (random-uuid) ".db")
             store (file/create-kv-file path)]
         (try
           ;; :root is written once and never changed, so every read must
           ;; see exactly this value; :churn is rewritten to create dead
           ;; space so each compact! actually rebuilds the log and swaps
           ;; the stream.
           (jing/cas! store :root jing/absent {:p "v"})
           (let [expected {:p "v"}
                 stop (atom false)
                 seen-wrong (atom [])
                 reader (future (while (not @stop)
                                  (let [v (try
                                            (jing/get store :root :not-found)
                                            (catch Throwable t
                                              {:threw (str (type t))}))]
                                    (when (and (not= expected v)
                                               (< (count @seen-wrong) 50))
                                      (swap! seen-wrong conj v)))))]
             (dotimes [_ 30]
               (jing/cas! store :churn jing/absent {:n (rand-int 1000)})
               (file/compact-store! store))
             (reset! stop true)
             (deref reader 2000 :reader-timeout)
             (is (empty? @seen-wrong)
                 (str
                   "concurrent reads during compaction returned wrong/absent "
                   "values for a live key: "
                   (distinct @seen-wrong))))
           (finally (jing/close! store) (.delete (java.io.File. path))))))))


;; ---------------------------------------------------------------------------
;; Content addressing (segment-key / content-hash / key-class)
;; ---------------------------------------------------------------------------
;; Pure-logic tests over dao.jing itself — no backend, no network. DHT-
;; specific enforcement of this discipline (KVDht's cas!/get actually
;; throwing on a violation) is tested separately in dao.jing.dht-test,
;; key-discipline.

(deftest sha256-known-answer
  ;; Every other test here pins only that hashing is *deterministic*, which
  ;; a wrong-but-consistent digest would satisfy. This one pins the actual
  ;; function, and it is load-bearing across hosts: :clj delegates to
  ;; MessageDigest and :cljs to goog.crypt, but :cljd runs a hand-rolled
  ;; SHA-256 in dao.jing. If that implementation drifts, cljd peers mint
  ;; different segment-keys than JVM/JS peers for identical values and
  ;; content addressing silently fractures — with no other test failing.
  ;; Vectors are the NIST/FIPS-180-4 published digests.
  (testing "published vectors"
    (doseq
      [[in want]
       {"" "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "abc"
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        "hello world"
        "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"}]
      (is (= want (jing/sha256 in)) (str "sha256 of " (pr-str in)))))
  (testing "a 65-byte input, which crosses the 64-byte block boundary"
    ;; the multi-chunk path: exercises padding into a second block
    (is (= "635361c48bb9eab14198e76ea8ab7f1a41685d6ad62aa9146d301d4f17eb0ae0"
           (jing/sha256 (apply str (repeat 65 "a"))))))
  (testing "and content-hash is that digest over the canonical print"
    (is (= (jing/sha256 (pr-str {:a 1})) (jing/content-hash {:a 1})))))


(deftest segment-key-is-content-addressed
  (testing "the key is deterministic and order-insensitive"
    (is (= (jing/segment-key {:a 1, :b 2}) (jing/segment-key {:b 2, :a 1})))
    (is (not= (jing/segment-key {:a 1}) (jing/segment-key {:a 2})))
    (is (= "segment" (namespace (jing/segment-key {:a 1})))))
  (testing "and is total over non-map values, which are now legal payloads"
    (doseq [v [42 "hello" :kw [1 2 3] nil #{:a}]]
      (is (= (jing/segment-key v) (jing/segment-key v))
          (str (pr-str v) " must hash deterministically"))
      (is (= "segment" (namespace (jing/segment-key v)))))
    (is (not= (jing/segment-key 42) (jing/segment-key "42"))
        "distinct values must not collide across types")))


(deftest segment-keys-are-readable-edn
  ;; An EDN keyword name must not start with a digit, but a bare sha-256
  ;; hex often does. A key that cannot survive print -> read poisons every
  ;; EDN boundary a root or segment crosses (the file backend's own
  ;; persistence, first of all).
  (testing "no minted key starts with a digit"
    (doseq [n (range 16)]
      (let [k (jing/segment-key {:n n})]
        (is (not (contains? (set "0123456789") (first (name k))))
            (str k " is not readable EDN")))))
  #?(:clj (testing "a minted key survives print -> read"
            (let [k (jing/segment-key {:a 1})]
              (is (= k (clojure.edn/read-string (pr-str k))))))))


(deftest segment-hash-recovers-the-content-hash
  (testing "the hash is extractable from the key and matches the content"
    (let [v {:a 1}]
      (is (= (jing/content-hash v) (jing/segment-hash (jing/segment-key v)))))))


(deftest key-class-dispatches-by-namespace
  (testing "a key's class is recoverable from the key alone"
    (is (= :segment (jing/key-class :segment/abc)))
    (is (= :root (jing/key-class :root/pointer)))
    (is (thrown? #?(:clj Exception
                    :cljs js/Error
                    :cljd Object)
          (jing/key-class :plain))
        "un-namespaced keys have no class")))


(deftest compaction-failure-leaves-store-usable
  #?(:clj
     (testing
       "a compact! that fails in the swap tail (after the old stream is closed) restores a live stream instead of wedging get"
       (let [path (str "target/compact-fail-test-" (random-uuid) ".db")
             store (file/create-kv-file path)]
         (try
           (jing/cas! store :live jing/absent {:x 1})
           (jing/cas! store :root jing/absent {:p "1"})
           (jing/cas! store :root {:p "1"} {:p "2"}) ; dead space so
           ;; compaction rewrites. Inject a failure in rename-file!,
           ;; which runs
           ;; AFTER compact! Has already closed the old stream — the
           ;; exact window that used to leave state-atom pointing at a
           ;; dead stream.
           (is (thrown? Exception
                 (with-redefs [dao.jing.file/rename-file!
                               (fn [_ _]
                                 (throw (ex-info
                                          "injected rename failure"
                                          {})))]
                   (file/compact-store! store))))
           ;; The store must stay readable, promptly (no ::closed retry
           ;; hang), and no data may be lost by the aborted compaction.
           (let [outcome
                 (deref (future
                          (try {:live (jing/get store :live :not-found),
                                :root (jing/get store :root :not-found)}
                               (catch Throwable t {:threw (str (type t))})))
                        2000
                        {:timeout true})]
             (is
               (= {:live {:x 1}, :root {:p "2"}} outcome)
               (str
                 "store should stay readable after a failed compaction; got "
                 outcome)))
           ;; And the restored stream must still accept writes.
           (is (true? (jing/cas! store :post jing/absent {:y 9})))
           (is (= {:y 9} (jing/get store :post nil)))
           (finally (jing/close! store) (.delete (java.io.File. path))))))))
