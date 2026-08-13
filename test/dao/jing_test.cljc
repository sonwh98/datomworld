(ns dao.jing-test
  "Contract tests for the DaoJing content-addressed storage observer
   (docs/design/dao.jing.md, target architecture).

   Covers the plain-data handle API (materialize!/get/close!), the explicit
   intake-pool observer (observer-state/observe-step!), and the
   content-addressing discipline. The transitional CAS/root/evaluator/
   file/mem compatibility contracts are gone: this namespace requires only
   dao.jing plus the ringbuffer stream transport."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn])
            [clojure.string :as str]
            [dao.jing :as jing]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]))


(defn mem-handle
  "In-memory content backend for tests: a map keyed by content address. The
   store atom is exposed as :store so tests can assert on the exact contents
   of the backend. The seed accepts an address->payload map, used to force
   collisions."
  ([] (mem-handle {}))
  ([seed]
   (let [store (atom seed)]
     {:store store,
      :put-content-fn (fn [address payload]
                        (if (contains? @store address)
                          :present
                          (do (swap! store assoc address payload) :inserted))),
      :get-content-fn (fn [address not-found] (get @store address not-found)),
      :close-fn (fn [] (swap! store assoc ::closed true))})))


(defn open-stream
  "Open a ringbuffer transport pre-loaded with vals."
  [& vals]
  (let [s (ds/open! {:type :ringbuffer, :capacity 8})]
    (doseq [v vals] (ds/append! s v))
    s))


(defrecord MalformedResultStream
  [result]
  ;; Test double: a reader whose every ds/next answer is the configured
  ;; result, used to feed malformed maps into observe-step!.
  ds/IDaoStreamReader

  (next [_this _cursor] result))


;; ---------------------------------------------------------------------------
;; Content addressing
;; ---------------------------------------------------------------------------

(deftest sha256-known-answer
  ;; Pins the actual digest function; load-bearing across hosts: :clj
  ;; delegates to MessageDigest, :cljs to goog.crypt, :cljd to the
  ;; hand-rolled
  ;; SHA-256 in dao.jing. If that implementation drifts, cljd peers mint
  ;; different segment-keys than JVM/JS peers for identical values and
  ;; content addressing silently fractures. Vectors are the NIST/FIPS-180-4
  ;; digests.
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
    (is (= "635361c48bb9eab14198e76ea8ab7f1a41685d6ad62aa9146d301d4f17eb0ae0"
           (jing/sha256 (apply str (repeat 65 "a")))))))


(deftest segment-key-is-content-addressed
  (testing "the key is deterministic and order-insensitive"
    (is (= (jing/segment-key {:a 1, :b 2}) (jing/segment-key {:b 2, :a 1})))
    (is (not= (jing/segment-key {:a 1}) (jing/segment-key {:a 2})))
    (is (= "segment" (namespace (jing/segment-key {:a 1})))))
  (testing "and is total over non-map values, which are legal payloads"
    (doseq [v [42 "hello" :kw [1 2 3] nil #{:a}]]
      (is (= (jing/segment-key v) (jing/segment-key v))
          (str (pr-str v) " must hash deterministically"))
      (is (= "segment" (namespace (jing/segment-key v)))))
    (is (not= (jing/segment-key 42) (jing/segment-key "42"))
        "distinct values must not collide across types")))


(deftest segment-keys-are-readable-edn
  ;; An EDN keyword name must not start with a digit, but a bare sha-256
  ;; hex often does. A key that cannot survive print -> read poisons every
  ;; EDN boundary content addresses cross.
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


(deftest segment-hash-rejects-invalid-keys
  (testing
    "segment-hash extracts the hash only from a valid
            :segment/sha256-<64 lowercase hex> address; foreign namespaces,
            malformed hashes, non-hex characters, and wrong lengths throw
            instead of merely stripping a name prefix"
    (let [h64 (jing/segment-hash (jing/segment-key {:a 1}))]
      (is (= 64 (count h64)))
      (is (re-matches #"[0-9a-f]{64}" h64))
      (doseq [bad [(keyword "other" (str "sha256-" h64))
                   (keyword "segment" "sha256-")
                   (keyword "segment" (str "sha256-" (subs h64 0 63)))
                   (keyword "segment" (str "sha256-" h64 "0"))
                   (keyword "segment" (str "sha256-" (str/upper-case h64)))
                   (keyword "segment"
                            (str "sha256-" (str/replace h64 (first h64) \g)))
                   :plain :segment/not-a-hash :segment/sha256-xyz 42 "abc" nil
                   [1 2]]]
        (is (thrown? #?(:clj Exception
                        :cljs js/Error
                        :cljd Object)
              (jing/segment-hash bad))
            (str "segment-hash must reject " (pr-str bad)))))))


(deftest content-hash-is-order-insensitive
  (testing "equal maps print identically regardless of insertion order"
    (is (= (jing/content-hash {:a 1, :b 2}) (jing/content-hash {:b 2, :a 1})))
    (is (= (jing/content-hash #{:x :y}) (jing/content-hash #{:y :x}))))
  (testing "distinct values differ"
    (is (not= (jing/content-hash {:a 1}) (jing/content-hash {:a 2}))))
  (testing "content-hash drives the segment key"
    (is (= (jing/segment-hash (jing/segment-key {:a 1}))
           (jing/content-hash {:a 1})))))


;; ---------------------------------------------------------------------------
;; Handle API: materialize! / get / close!
;; ---------------------------------------------------------------------------

(deftest materialize-hashes-and-retrieves
  (testing
    "the address is derived automatically from the payload and the
            value round-trips unchanged"
    (let [h (mem-handle)
          payload {:a 1, :b [1 2 3]}
          address (jing/materialize! h payload)]
      (is (= (jing/segment-key payload) address))
      (is (= "segment" (namespace address)))
      (is (str/starts-with? (name address) "sha256-"))
      (is (= payload (jing/get h address ::missing))
          "the stored value is exactly the payload: nothing stamped in"))))


(deftest put-receives-the-derived-address-and-payload
  (testing "the backend effect is invoked as (put-content-fn address payload)"
    (let [seen (atom nil)
          payload {:v 1}
          h {:put-content-fn (fn [a p] (reset! seen [a p]) :inserted),
             :get-content-fn (fn [_ _] nil)}]
      (jing/materialize! h payload)
      (is (= [(jing/segment-key payload) payload] @seen)))))


(deftest materialize-is-idempotent
  (testing
    "duplicate materialization returns the same address, reports
            :present on the second write, and never overwrites"
    (let [results (atom [])
          store (atom {})
          payload {:x 42}
          h {:put-content-fn
             (fn [address p]
               (let [r (if (contains? @store address) :present :inserted)]
                 (swap! store assoc address p)
                 (swap! results conj r)
                 r)),
             :get-content-fn (fn [address nf] (get @store address nf))}
          a1 (jing/materialize! h payload)
          a2 (jing/materialize! h payload)]
      (is (= a1 a2))
      (is (= payload (jing/get h a1 ::missing)))
      (is (= [:inserted :present] @results)))))


(deftest get-absent-returns-not-found
  (testing
    "get returns the caller-supplied not-found for an absent content
            address and only for that address"
    (let [h (mem-handle)
          address (jing/segment-key {:never 1})]
      (is (= ::missing (jing/get h address ::missing)))
      (jing/materialize! h {:never 1})
      (is (= {:never 1} (jing/get h address ::missing)))
      (is (= ::missing (jing/get h (jing/segment-key {:other 2}) ::missing))
          "a present address leaves other addresses absent"))))


(deftest get-rejects-arbitrary-addresses
  (testing
    "only :segment/sha256-... content addresses are valid reads;
            arbitrary keys and mutable roots are outside DaoJing"
    (let [h (mem-handle)
          payload {:x 1}
          _ (jing/materialize! h payload)]
      (is (= payload (jing/get h (jing/segment-key payload) ::missing)))
      (doseq [bad [:root/pointer :plain :segment/not-a-hash :segment/sha256-xyz
                   42 "abc" nil [1 2] {:k :v}]]
        (is (thrown? #?(:clj Exception
                        :cljs js/Error
                        :cljd Object)
              (jing/get h bad ::missing))
            (str "must reject " (pr-str bad)))))))


(deftest get-rejects-arbitrary-addresses-before-touching-the-backend
  (testing "validation happens before any backend call"
    (let [h {:put-content-fn (fn [_ _] :inserted),
             :get-content-fn
             (fn [_ _] (throw (ex-info "backend must not be consulted" {})))}]
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/get h :root/pointer ::missing))))))


(deftest forced-collision-is-an-integrity-failure
  (testing
    "a different value already seated at the address is never
            overwritten and the mismatch is reported loudly"
    (let [address (jing/segment-key {:b 1})
          h (mem-handle {address {:a 1}})]
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/materialize! h {:b 1})))
      (is (= {:a 1} (jing/get h address ::missing))
          "the existing value is untouched"))))


(deftest present-without-readable-content-is-an-integrity-failure
  (testing
    "a backend that reports :present but cannot read the value back
            is inconsistent"
    (let [h {:put-content-fn (fn [_ _] :present),
             :get-content-fn (fn [_ _] ::missing)}]
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/materialize! h {:x 1}))))))


(deftest content-missing-keyword-is-a-legal-payload
  (testing
    "the former sentinel keyword :dao.jing/content-missing is a legal
            opaque payload: a backend that reports :present but then returns
            not-found for the content address must throw, not fake success by
            equating its not-found with the payload"
    (let [h {:put-content-fn (fn [_ _] :present),
             :get-content-fn (fn [_ not-found] not-found)}]
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/materialize! h :dao.jing/content-missing)))))
  (testing
    "a conforming backend stores the keyword as an ordinary payload,
            idempotently"
    (let [h (mem-handle)]
      (is (= (jing/segment-key :dao.jing/content-missing)
             (jing/materialize! h :dao.jing/content-missing)))
      (is (= (jing/segment-key :dao.jing/content-missing)
             (jing/materialize! h :dao.jing/content-missing)))
      (is (= :dao.jing/content-missing
             (jing/get h
                       (jing/segment-key :dao.jing/content-missing)
                       ::missing))))))


(deftest invalid-backend-result-is-rejected
  (testing "ambiguous truthiness is not a valid result vocabulary"
    (doseq [bad [true nil :ok "inserted" 1 {:status :ok}]]
      (let [h {:put-content-fn (fn [_ _] bad), :get-content-fn (fn [_ _] nil)}]
        (is (thrown? #?(:clj Exception
                        :cljs js/Error
                        :cljd Object)
              (jing/materialize! h {:x 1}))
            (str "backend result " (pr-str bad) " must be rejected"))))))


(deftest close-delegates-and-is-optional
  (testing
    "close! returns nil, delegates to :close-fn, and tolerates
            repeated calls and handles without a close-fn"
    (let [closed (atom 0)
          h (assoc (mem-handle) :close-fn (fn [] (swap! closed inc)))]
      (is (nil? (jing/close! h)))
      (is (nil? (jing/close! h)))
      (is (= 2 @closed) "close! delegates to :close-fn on every call"))
    (is (nil? (jing/close! (assoc (mem-handle) :close-fn nil)))
        "a handle without :close-fn has nothing to release")
    (is (nil? (jing/close! {:put-content-fn (fn [_ _] :inserted),
                            :get-content-fn (fn [_ _] nil)})))))


;; ---------------------------------------------------------------------------
;; Observer: observer-state / observe-step!
;; ---------------------------------------------------------------------------

(deftest observer-state-is-plain-data
  (testing
    "observer-state returns immutable data with one operational member
            per pool stream, a fresh cursor, an explicit status, and the next
            fair scheduling index"
    (let [a (open-stream)
          b (open-stream)
          st (jing/observer-state [a b])]
      (is (= [a b] (mapv :stream (:members st))))
      (is (every? #(= {:position 0} (:cursor %)) (:members st)))
      (is (every? #(= :pending (:status %)) (:members st)))
      (is (= 0 (:next st))))))


(deftest observer-empty-pool-is-blocked
  (let [h (mem-handle)
        state (jing/observer-state [])]
    (is (= {:state state, :signal :blocked} (jing/observe-step! h state)))))


(deftest observer-all-blocked
  (let [h (mem-handle)
        a (open-stream)
        b (open-stream)
        state (jing/observer-state [a b])
        r (jing/observe-step! h state)]
    (is (= :blocked (:signal r)))
    (is (every? #(= :blocked (:status %)) (:members (:state r))))
    (is (every? #(= {:position 0} (:cursor %)) (:members (:state r))))))


(deftest observer-automatic-hashing-and-retrieval
  (testing
    "a payload arriving through a pool stream is content-addressed
            without the caller minting any key"
    (let [h (mem-handle)
          payload {:hello "world"}
          s (open-stream payload)
          r (jing/observe-step! h (jing/observer-state [s]))]
      (is (= :ok (:signal r)))
      (is (= (jing/segment-key payload) (:address r)))
      (is (= payload (jing/get h (:address r) ::missing))))))


(deftest observer-equal-payloads-from-two-streams-converge
  (testing
    "identical content arriving through different pool streams lands
            on exactly one KV entry, with no provenance stamp"
    (let [h (mem-handle)
          store (:store h)
          payload {:nested {:v [1 2 3]}}
          address (jing/segment-key payload)
          a (open-stream payload payload)
          b (open-stream payload)
          state (jing/observer-state [a b])
          r1 (jing/observe-step! h state)
          r2 (jing/observe-step! h (:state r1))
          r3 (jing/observe-step! h (:state r2))]
      (doseq [r [r1 r2 r3]]
        (is (= :ok (:signal r)))
        (is (= address (:address r))
            "both streams converge on the same content address"))
      (is
        (= {address payload} @store)
        "exactly one KV entry exists, holding exactly the payload: no
          duplicate entries, no source identity, no provenance stamp"))))


(deftest observer-blocked-before-ready-does-not-prevent-later-members
  (let [h (mem-handle)
        a (open-stream)
        b (open-stream {:payload 1})
        r (jing/observe-step! h (jing/observer-state [a b]))]
    (is (= :ok (:signal r)))
    (is (= {:payload 1} (jing/get h (:address r) ::missing)))
    (is (= :blocked (get-in (:state r) [:members 0 :status]))
        "the blocked member is checked and marked, but does not block the scan")
    (is (= :ok (get-in (:state r) [:members 1 :status])))))


(deftest observer-ended-before-ready-does-not-starve-active-members
  (let [h (mem-handle)
        a (open-stream)
        _ (ds/close! a)
        b (open-stream {:payload 1})
        r (jing/observe-step! h (jing/observer-state [a b]))]
    (is (= :ok (:signal r)))
    (is (= {:payload 1} (jing/get h (:address r) ::missing)))
    (is (= :end (get-in (:state r) [:members 0 :status]))
        "the closed member is explicitly :end and skipped")
    (is (= :ok (get-in (:state r) [:members 1 :status])))))


(deftest observer-all-ended
  (let [h (mem-handle)
        a (open-stream)
        b (open-stream)]
    (ds/close! a)
    (ds/close! b)
    (let [r (jing/observe-step! h (jing/observer-state [a b]))]
      (is (= :end (:signal r)))
      (is (every? #(= :end (:status %)) (:members (:state r)))))))


(deftest observer-drains-then-reports-end
  (testing "a closed stream still yields its buffered payloads, then :end"
    (let [h (mem-handle)
          s (open-stream {:last 1})
          _ (ds/close! s)
          state (jing/observer-state [s])
          r1 (jing/observe-step! h state)
          r2 (jing/observe-step! h (:state r1))]
      (is (= :ok (:signal r1)))
      (is (= {:last 1} (jing/get h (:address r1) ::missing)))
      (is (= :end (:signal r2))))))


(deftest observer-fair-round-robin-and-independent-cursors
  (testing
    "round-robin alternates members and a continuously ready member
            cannot starve another"
    (let [h (mem-handle)
          a (open-stream {:who :a, :n 1} {:who :a, :n 2})
          b (open-stream {:who :b, :n 1})
          state (jing/observer-state [a b])
          r1 (jing/observe-step! h state)
          r2 (jing/observe-step! h (:state r1))
          r3 (jing/observe-step! h (:state r2))]
      (is (= {:who :a, :n 1} (jing/get h (:address r1) ::missing)))
      (is (= {:who :b, :n 1} (jing/get h (:address r2) ::missing))
          "A is continuously ready, yet B gets its turn before A's second item")
      (is (= {:who :a, :n 2} (jing/get h (:address r3) ::missing)))
      (is (= {:position 2} (get-in (:state r3) [:members 0 :cursor])))
      (is (= {:position 1} (get-in (:state r3) [:members 1 :cursor]))))))


(deftest observer-independent-cursors-interleave
  (testing "two equal-length ready streams drain strictly A B A B"
    (let [h (mem-handle)
          a (open-stream :a1 :a2)
          b (open-stream :b1 :b2)
          state (jing/observer-state [a b])
          [addrs final-state]
          (loop [st state
                 acc []
                 i 0]
            (if (= i 4)
              [acc st]
              (let [r (jing/observe-step! h st)]
                (recur (:state r) (conj acc (:address r)) (inc i)))))]
      (is (= [(jing/segment-key :a1) (jing/segment-key :b1)
              (jing/segment-key :a2) (jing/segment-key :b2)]
             addrs)
          "round-robin alternates A B A B, cursors advancing independently")
      (is (= :blocked (:signal (jing/observe-step! h final-state)))
          "the pool is drained after four payloads"))))


(deftest observer-gap-is-reported-and-never-auto-resynced
  (testing
    "a cursor behind the retention boundary reports :daostream/gap
            immediately, leaves its cursor unchanged, is never resynced, and
            does not starve the rest of the pool"
    (let [h (mem-handle)
          a (ds/open!
              {:type :ringbuffer, :capacity 2, :eviction-policy :evict-oldest})
          _ (ds/append! a {:evicted 1})
          _ (ds/append! a {:evicted 2})
          _ (ds/append! a {:live 3})
          b (open-stream {:payload :ready})
          state (jing/observer-state [a b])
          r1 (jing/observe-step! h state)]
      (is (= :daostream/gap (:signal r1)))
      (is (= 0 (:member r1)))
      (is (= {:position 0} (get-in (:state r1) [:members 0 :cursor]))
          "the gap leaves the cursor unchanged")
      (is (= :daostream/gap (get-in (:state r1) [:members 0 :status])))
      (let [r2 (jing/observe-step! h (:state r1))]
        (is (= :ok (:signal r2)))
        (is (= {:payload :ready} (jing/get h (:address r2) ::missing)))
        (let [r3 (jing/observe-step! h (:state r2))]
          (is (= :daostream/gap (:signal r3)))
          (is (= {:position 0} (get-in (:state r3) [:members 0 :cursor]))
              "the gap persists: the cursor is never auto-resynchronized"))))))


(deftest observer-rejects-malformed-stream-maps
  (testing
    "a map from ds/next is a successful read only when it explicitly
            carries both :ok and :cursor; malformed maps throw instead of
            content-addressing nil or installing a nil cursor"
    (let [h (mem-handle)]
      (doseq [bad [{} {:ok nil} {:ok :payload} {:cursor {:position 1}}]]
        (let [s (->MalformedResultStream bad)]
          (is (thrown? #?(:clj Exception
                          :cljs js/Error
                          :cljd Object)
                (jing/observe-step! h (jing/observer-state [s])))
              (str "must reject " (pr-str bad)))))))
  (testing
    "a well-formed {:ok ... :cursor ...} read through the same double
            still materializes"
    (let [h (mem-handle)
          s (->MalformedResultStream {:ok :payload, :cursor {:position 1}})
          r (jing/observe-step! h (jing/observer-state [s]))]
      (is (= :ok (:signal r)))
      (is (= (jing/segment-key :payload) (:address r)))
      (is (= :payload (jing/get h (:address r) ::missing)))
      (is (= {:position 1} (get-in (:state r) [:members 0 :cursor]))))))


(deftest observer-cursor-advances-only-after-successful-materialization
  (testing
    "a failed materialization propagates and leaves the caller-owned
            state untouched; the same payload is reprocessed from the same
            cursor once the backend succeeds"
    (let [good (mem-handle)
          failing {:put-content-fn (fn [address payload]
                                     (if (= payload :poison)
                                       (throw (ex-info "injected failure"
                                                       {:address address}))
                                       :inserted)),
                   :get-content-fn (fn [_ _] nil)}
          s (open-stream {:a 1} :poison {:a 2})
          state (jing/observer-state [s])
          r1 (jing/observe-step! good state)]
      (is (= :ok (:signal r1)))
      (is (= {:position 1} (get-in (:state r1) [:members 0 :cursor])))
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/observe-step! failing (:state r1))))
      (is (= {:position 1} (get-in (:state r1) [:members 0 :cursor]))
          "the caller's state must not advance past the failed payload")
      (let [r2 (jing/observe-step! good (:state r1))]
        (is (= :ok (:signal r2)))
        (is (= (jing/segment-key :poison) (:address r2)))
        (is (= {:position 2} (get-in (:state r2) [:members 0 :cursor])))))))
