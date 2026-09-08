(ns dao.jing-test
  "Contract tests for the DaoJing content-addressed storage observer
   (docs/design/dao.jing.md, target architecture).

   Covers the plain-data handle API (materialize!/get/close!), the explicit
   intake-pool observer (observer-state/observe-step!/adopt-cursor), and
   the content-addressing discipline. The transitional CAS/root/evaluator/
   file/mem compatibility contracts are gone: this namespace requires only
   dao.jing plus the dao.stream.v2 ringbuffer transport."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn])
            [clojure.string :as str]
            [dao.jing :as jing]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ringbuffer]))


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


(defn ring-buffer
  "A dao.stream.v2 ringbuffer creation spec."
  [capacity]
  {:dao.stream/type :dao.stream/ringbuffer
   :dao.stream.ringbuffer/capacity capacity})


(defn open-stream
  "A dao.stream.v2 ringbuffer reader handle pre-loaded with vals."
  [& vals]
  (let [{:dao.stream/keys [handle]} (ringbuffer/create! (ring-buffer 8))]
    (doseq [v vals] (stream/append! handle v))
    handle))


(defn- oldest-cursor
  "The :dao.stream/oldest cursor of a reader, minted the way a composition
   mints it before entering the stream in a pool."
  [s]
  (:dao.stream/cursor (stream/cursor s :dao.stream/oldest)))


(defn pool
  "Observer state over reader handles, each entered at its oldest cursor."
  [& streams]
  (jing/observer-state (mapv (fn [s] {:stream s, :cursor (oldest-cursor s)})
                             streams)))


(defrecord ScriptedResultStream
  [result]
  ;; Test double: a dao.stream.v2 reader whose every stream/next answer is
  ;; the configured result, used to feed scripted outcomes into the pool.
  stream/IDaoStreamReader

  (cursor
    [_ _anchor]
    {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor ::at})


  (next [_ _cursor] result))


(defn- scripted-answer
  "A contract-valid stream/next answer for outcome."
  [outcome]
  (case outcome
    :dao.stream/ok {:dao.stream/outcome :dao.stream/ok,
                    :dao.stream/value :payload,
                    :dao.stream/cursor ::successor}
    :dao.stream/gap {:dao.stream/outcome :dao.stream/gap,
                     :dao.stream/cursor ::recovery}
    {:dao.stream/outcome outcome}))


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
;; Observer: observer-state / observe-step! / adopt-cursor
;; ---------------------------------------------------------------------------

(deftest observer-state-is-plain-data
  (testing
    "observer-state returns immutable data with one member per pool entry,
            the cursor the composition handed in, an explicit status, and the
            next fair scheduling index"
    (let [a (open-stream)
          b (open-stream)
          st (jing/observer-state [{:stream a, :cursor (oldest-cursor a)}
                                   {:stream b, :cursor (oldest-cursor b)}])]
      (is (= [a b] (mapv :stream (:members st))))
      (is (= [(oldest-cursor a) (oldest-cursor b)]
             (mapv :cursor (:members st))))
      (is (every? #(= :pending (:status %)) (:members st)))
      (is (= 0 (:next st)))))
  (testing "members round-trip through observer-state"
    (let [a (open-stream)
          st (jing/observer-state [{:stream a, :cursor (oldest-cursor a)}])]
      (is (= st (jing/observer-state (:members st)))
          "the state is plain data: it rebuilds from its members"))))


(deftest observer-state-rejects-defective-members
  (testing
    "a member without a cursor, or whose stream lacks the reader surface,
            is a composition defect: observer-state throws before any
            operation"
    (let [s (open-stream)]
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/observer-state [{:stream s}]))
          "a member without :cursor is rejected")
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/observer-state [{:stream 42, :cursor (oldest-cursor s)}]))
          "a non-reader stream is rejected"))))


(deftest observer-empty-pool-is-blocked
  (let [h (mem-handle)
        state (jing/observer-state [])]
    (is (= {:state state, :signal :dao.stream/blocked}
           (jing/observe-step! h state)))))


(deftest observer-all-blocked
  (let [h (mem-handle)
        a (open-stream)
        b (open-stream)
        r (jing/observe-step! h (pool a b))]
    (is (= :dao.stream/blocked (:signal r)))
    (is (every? #(= :dao.stream/blocked (:status %)) (:members (:state r))))
    (is (= [(oldest-cursor a) (oldest-cursor b)]
           (mapv :cursor (:members (:state r)))))))


(deftest observer-automatic-hashing-and-retrieval
  (testing
    "a payload arriving through a pool stream is content-addressed
            without the caller minting any key"
    (let [h (mem-handle)
          payload {:hello "world"}
          s (open-stream payload)
          r (jing/observe-step! h (pool s))]
      (is (= :dao.stream/ok (:signal r)))
      (is (= (jing/segment-key payload) (:address r)))
      (is (= payload (jing/get h (:address r) ::missing)))
      (is (= (:dao.stream/cursor
               (stream/next s (oldest-cursor s)))
             (get-in (:state r) [:members 0 :cursor]))
          "the member advanced to the successor cursor the stream returned"))))


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
          r1 (jing/observe-step! h (pool a b))
          r2 (jing/observe-step! h (:state r1))
          r3 (jing/observe-step! h (:state r2))]
      (doseq [r [r1 r2 r3]]
        (is (= :dao.stream/ok (:signal r)))
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
        r (jing/observe-step! h (pool a b))]
    (is (= :dao.stream/ok (:signal r)))
    (is (= {:payload 1} (jing/get h (:address r) ::missing)))
    (is (= :dao.stream/blocked (get-in (:state r) [:members 0 :status]))
        "the blocked member is checked and marked, but does not block the scan")
    (is (= :dao.stream/ok (get-in (:state r) [:members 1 :status])))))


(deftest observer-ended-before-ready-does-not-starve-active-members
  (let [h (mem-handle)
        a (open-stream)
        _ (stream/close! a)
        b (open-stream {:payload 1})
        r (jing/observe-step! h (pool a b))]
    (is (= :dao.stream/ok (:signal r)))
    (is (= {:payload 1} (jing/get h (:address r) ::missing)))
    (is (= :dao.stream/end (get-in (:state r) [:members 0 :status]))
        "the closed member is explicitly :dao.stream/end and skipped")
    (is (= :dao.stream/ok (get-in (:state r) [:members 1 :status])))))


(deftest observer-all-ended
  (let [h (mem-handle)
        a (open-stream)
        b (open-stream)]
    (stream/close! a)
    (stream/close! b)
    (let [r (jing/observe-step! h (pool a b))]
      (is (= :dao.stream/end (:signal r)))
      (is (every? #(= :dao.stream/end (:status %)) (:members (:state r)))))))


(deftest observer-drains-then-reports-end
  (testing "a closed stream still yields its buffered payloads, then end"
    (let [h (mem-handle)
          s (open-stream {:last 1})
          _ (stream/close! s)
          r1 (jing/observe-step! h (pool s))
          r2 (jing/observe-step! h (:state r1))]
      (is (= :dao.stream/ok (:signal r1)))
      (is (= {:last 1} (jing/get h (:address r1) ::missing)))
      (is (= :dao.stream/end (:signal r2))))))


(deftest observer-fair-round-robin-and-independent-cursors
  (testing
    "round-robin alternates members and a continuously ready member
            cannot starve another"
    (let [h (mem-handle)
          a (open-stream {:who :a, :n 1} {:who :a, :n 2})
          b (open-stream {:who :b, :n 1})
          r1 (jing/observe-step! h (pool a b))
          r2 (jing/observe-step! h (:state r1))
          r3 (jing/observe-step! h (:state r2))]
      (is (= {:who :a, :n 1} (jing/get h (:address r1) ::missing)))
      (is (= {:who :b, :n 1} (jing/get h (:address r2) ::missing))
          "A is continuously ready, yet B gets its turn before A's second item")
      (is (= {:who :a, :n 2} (jing/get h (:address r3) ::missing)))
      (is (= 2 (get-in (:state r3)
                       [:members 0 :cursor :dao.stream.ringbuffer/position])))
      (is (= 1 (get-in (:state r3)
                       [:members 1 :cursor :dao.stream.ringbuffer/position]))))))


(deftest observer-independent-cursors-interleave
  (testing "two equal-length ready streams drain strictly A B A B"
    (let [h (mem-handle)
          a (open-stream :a1 :a2)
          b (open-stream :b1 :b2)
          [addrs final-state]
          (loop [st (pool a b)
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
      (is (= :dao.stream/blocked (:signal (jing/observe-step! h final-state)))
          "the pool is drained after four payloads"))))


(deftest observer-gap-is-reported-with-recovery-and-never-auto-resynced
  (testing
    "a cursor behind the retention boundary reports :dao.stream/gap with
            the step's recovery cursor, leaves the member cursor unchanged,
            never resyncs on its own, does not starve the rest of the pool,
            and is reported again on the member's next turn"
    (let [h (mem-handle)
          {:dao.stream/keys [handle]}
          (ringbuffer/create! (ring-buffer 2))
          cursor-a (:dao.stream/cursor
                     (stream/cursor handle :dao.stream/oldest))
          _ (doseq [v [{:evicted 1} {:retained 2} {:live 3}]]
              (stream/append! handle v))
          b (open-stream {:payload :ready})
          state (jing/observer-state [{:stream handle, :cursor cursor-a}
                                      {:stream b, :cursor (oldest-cursor b)}])
          r1 (jing/observe-step! h state)]
      (is (= :dao.stream/gap (:signal r1)))
      (is (= 0 (:member r1)))
      (is (= 1 (get-in r1 [:cursor :dao.stream.ringbuffer/position]))
          "the report carries the step's recovery cursor: the earliest
            retained position")
      (is (= cursor-a (get-in (:state r1) [:members 0 :cursor]))
          "the gap leaves the member cursor unchanged")
      (is (= :dao.stream/gap (get-in (:state r1) [:members 0 :status])))
      (let [r2 (jing/observe-step! h (:state r1))]
        (is (= :dao.stream/ok (:signal r2))
            "the other member still progresses")
        (is (= {:payload :ready} (jing/get h (:address r2) ::missing)))
        (let [r3 (jing/observe-step! h (:state r2))]
          (is (= :dao.stream/gap (:signal r3))
              "the gap is reported again on the member's next turn")
          (is (= cursor-a (get-in (:state r3) [:members 0 :cursor]))
              "the cursor is never auto-resynchronized")
          (testing
            "adopt-cursor is the caller's resync: the member resumes at the
                    recovery cursor and drains through to the live value"
            (let [r4 (jing/observe-step!
                       h (jing/adopt-cursor (:state r3) 0 (:cursor r3)))
                  r5 (jing/observe-step! h (:state r4))
                  r6 (jing/observe-step! h (:state r5))]
              (is (= :dao.stream/ok (:signal r4)))
              (is (= (jing/segment-key {:retained 2}) (:address r4))
                  "the first post-adopt read is the earliest retained value")
              (is (= (jing/segment-key {:live 3}) (:address r5)))
              (is (= {:live 3} (jing/get h (:address r5) ::missing)))
              (is (= :dao.stream/blocked (:signal r6))
                  "the adopted member drains and rejoins the quiet pool"))))))))


(deftest observer-defects-are-reported-as-data-with-the-raw-read
  (testing
    "the three declared read defects are reported immediately with :member
            and the raw read under :result, leave the member cursor
            unchanged, and are reported again on the next turn"
    (doseq [outcome [:dao.stream/cursor-mismatch
                     :dao.stream/invalid-cursor
                     :dao.stream/transport-error]]
      (let [h (mem-handle)
            s (->ScriptedResultStream (scripted-answer outcome))
            state (jing/observer-state [{:stream s, :cursor ::at}])
            r (jing/observe-step! h state)]
        (is (= outcome (:signal r)) (str outcome " is the signal"))
        (is (= 0 (:member r)))
        (is (= (scripted-answer outcome) (:result r))
            "the report carries the raw read exactly as it came")
        (is (= ::at (get-in (:state r) [:members 0 :cursor]))
            "the cursor is unchanged")
        (is (= outcome (get-in (:state r) [:members 0 :status])))
        (is (= outcome
               (:signal (jing/observe-step! h (:state r))))
            "the defect is reported again on the member's next turn"))))
  (testing
    "an answer outside the declared set is reported, not folded: it rides
            under :result through the step's transport-error classification"
    (doseq [answer [{:dao.stream/outcome :wholly-unexpected, :noise 1}
                    :not-a-map]]
      (let [h (mem-handle)
            s (->ScriptedResultStream answer)
            r (jing/observe-step!
                h (jing/observer-state [{:stream s, :cursor ::at}]))]
        (is (= :dao.stream/transport-error (:signal r))
            (str "unrecognized answer " (pr-str answer)))
        (is (= answer (get-in r [:result :dao.stream/answer]))
            "the raw answer is retained, exactly as it came")
        (is (= ::at (get-in (:state r) [:members 0 :cursor]))
            "no cursor moves on an answer nobody interpreted")))))


(deftest pool-signals-are-declared-total-over-outcomes-next
  (testing
    "every outcome dao.stream.v2 declares for next reports as exactly one
            pool signal; the table fails if the contract grows"
    (let [expected {:dao.stream/ok :dao.stream/ok,
                    :dao.stream/blocked :dao.stream/blocked,
                    :dao.stream/end :dao.stream/end,
                    :dao.stream/gap :dao.stream/gap,
                    :dao.stream/cursor-mismatch :dao.stream/cursor-mismatch,
                    :dao.stream/invalid-cursor :dao.stream/invalid-cursor,
                    :dao.stream/transport-error :dao.stream/transport-error}]
      (is (= stream/outcomes-next (set (keys expected)))
          "the table covers the declared set exactly, and fails if it grows")
      (doseq [[outcome signal] expected]
        (let [h (mem-handle)
              s (->ScriptedResultStream (scripted-answer outcome))
              r (jing/observe-step!
                  h (jing/observer-state [{:stream s, :cursor ::at}]))]
          (is (= signal (:signal r))
              (str outcome " must report as " signal))
          (if (= :dao.stream/ok outcome)
            (is (= (jing/segment-key :payload) (:address r)))
            (is (not (contains? r :address))
                (str "no address is minted on " outcome))))))))


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
          r1 (jing/observe-step! good (pool s))]
      (is (= :dao.stream/ok (:signal r1)))
      (is (= 1 (get-in (:state r1)
                       [:members 0 :cursor :dao.stream.ringbuffer/position])))
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/observe-step! failing (:state r1))))
      (is (= 1 (get-in (:state r1)
                       [:members 0 :cursor :dao.stream.ringbuffer/position]))
          "the caller's state must not advance past the failed payload")
      (let [r2 (jing/observe-step! good (:state r1))]
        (is (= :dao.stream/ok (:signal r2)))
        (is (= (jing/segment-key :poison) (:address r2)))
        (is (= 2 (get-in (:state r2)
                         [:members 0 :cursor :dao.stream.ringbuffer/position])))))))
