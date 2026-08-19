(ns dao.jing.dht-test
  "Contract tests for the dao.jing.dht content-store backend
   (docs/design/dao.jing.md): strict content addressing, best-effort
   replication, verified fetch-and-cache, and lifecycle over an in-memory
   full-mesh FakeNet grid of dao.jing.mem/create-content-mem locals.

   The DHT exposes no roots, no CAS records, and no deletes: a payload's
   only identity is its content address, and the DHT never records which
   stream or peer carried it."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.jing.dht :as dht]
            [dao.jing.dht.kad :as kad]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]))


;; ---------------------------------------------------------------------------
;; In-memory peer grid
;; ---------------------------------------------------------------------------

(defn- throws?
  "True when (f) throws on every supported host."
  [f]
  (try (f)
       false
       (catch #?(:clj Exception
                 :cljs :default
                 :cljd Object)
              _
         true)))


(defn- local-of
  [registry peer]
  (get-in @registry [(:id peer) :local]))


(def ^:private fake-missing
  "Opaque per-host not-found sentinel for FakeNet fetch-content, never a
   keyword: ::none is a legal stored keyword payload."
  #?(:cljd (Object.)
     :clj (Object.)
     :cljs (js-obj)))


(defrecord FakeNet
  [peer registry closes]

  dht/IDhtNet

  (self-peer [_] peer)


  (known-peers
    [_ target-id n]
    (->> (vals @registry)
         (map :peer)
         (remove #(= (:id %) (:id peer)))
         (sort-by #(kad/distance (:id %) target-id))
         (take n)
         vec))


  (find-closer
    [_ to target-id]
    (->> (vals @registry)
         (map :peer)
         (remove #(= (:id %) (:id to)))
         (sort-by #(kad/distance (:id %) target-id))
         (take kad/k)
         vec))


  (store-content!
    [_ to address payload]
    (cond (:store-throws? (get @registry (:id to)))
          (throw (ex-info "Simulated store transport error" {:peer to}))
          (:store-fails? (get @registry (:id to))) false
          :else (do ((:put-content-fn (local-of registry to)) address payload)
                    true)))


  (fetch-content
    [_ to address]
    (cond (:fetch-throws? (get @registry (:id to)))
          (throw (ex-info "Simulated fetch transport error" {:peer to}))
          :else
          (let [v (jing/get (local-of registry to) address fake-missing)]
            {:found? (not (identical? fake-missing v)),
             :value (when (not (identical? fake-missing v)) v)})))


  (close-net! [_] (swap! closes inc) nil))


(defn- grid
  "n DHT content stores over one simulated full-mesh network.
   Returns {:stores [store ...], :registry registry}."
  [n]
  (let [registry (atom {})]
    {:registry registry,
     :stores (mapv
               (fn [i]
                 (let [peer {:id (dht/node-id "fake" i), :host "fake", :port i}
                       local (mem/create-content-mem)]
                   (swap! registry assoc (:id peer) {:peer peer, :local local})
                   (dht/create-content-dht
                     {:net (->FakeNet peer registry (atom 0)), :local local})))
               (range n))}))


(defn- raw-handle
  "A non-validating in-memory content handle for test doubles. The state
   atom is exposed as :state so tests can plant forged payloads that bypass
   the address-payload contract."
  ([] (raw-handle {}))
  ([seed]
   (let [store (atom seed)]
     {:state store,
      :put-content-fn (fn [address payload]
                        (if (contains? @store address)
                          :present
                          (do (swap! store assoc address payload) :inserted))),
      :get-content-fn (fn [address not-found] (get @store address not-found)),
      :close-fn (fn [] (swap! store assoc ::closed true))})))


(defn- open-stream
  "Open a ringbuffer transport pre-loaded with vals."
  [& vals]
  (let [s (ds/open! {:dao.stream/type :ringbuffer, :capacity 8})]
    (doseq [v vals] (ds/append! s v))
    s))


;; ---------------------------------------------------------------------------
;; Handle shape and protocol surface
;; ---------------------------------------------------------------------------

(deftest handle-shape-and-protocol-surface
  (testing
    "the handle is plain data exposing exactly the three content-store
            effects, with no stream surface"
    (let [{:keys [stores]} (grid 2)
          [a] stores]
      (is (= #{:net :local :closed-atom :put-content-fn :get-content-fn
               :close-fn}
             (set (keys a))))
      (is (fn? (:put-content-fn a)))
      (is (fn? (:get-content-fn a)))
      (is (fn? (:close-fn a)))
      (is (false? @(:closed-atom a)))))
  (testing "FakeNet honors the six IDhtNet methods exactly"
    (let [{:keys [stores registry]} (grid 3)
          [a b c] stores
          net (:net a)
          id-b (dht/node-id "fake" 1)
          payload {:bytes [1]}
          address (jing/segment-key payload)
          nil-address (jing/materialize! (:local c) nil)]
      (is (= {:id (dht/node-id "fake" 0), :host "fake", :port 0}
             (dht/self-peer net)))
      (is (empty? (dht/known-peers net id-b 0)))
      (is (= [id-b] (map :id (dht/known-peers net id-b 1)))
          "known-peers return the n nearest, nearest first")
      (is (= [id-b (dht/node-id "fake" 2)]
             (map :id (dht/find-closer net (dht/self-peer net) id-b)))
          "find-closer excludes the requester, nearest first")
      (is (true?
            (dht/store-content! net (dht/self-peer (:net b)) address payload)))
      (is (= payload (jing/get (:local b) address ::miss))
          "store-content! writes the peer local")
      (is (= {:found? true, :value payload}
             (dht/fetch-content net (dht/self-peer (:net b)) address)))
      (is (= {:found? true, :value nil}
             (dht/fetch-content net (dht/self-peer (:net c)) nil-address))
          "fetch reports found for a stored nil")
      (swap! registry assoc-in [id-b :store-fails?] true)
      (is (false?
            (dht/store-content! net (dht/self-peer (:net b)) address payload))
          "a refusing peer reports false without storing")
      (dht/close-net! net)
      (is (= 1 @(:closes net)) "close-net! bumps the close counter"))))


;; ---------------------------------------------------------------------------
;; Routing identity
;; ---------------------------------------------------------------------------

(deftest node-id-is-deterministic
  (testing "node ids are pure functions of host and port"
    (is (= (dht/node-id "fake" 1) (dht/node-id "fake" 1)))
    (is (not= (dht/node-id "fake" 1) (dht/node-id "fake" 2)))
    (is (not= (dht/node-id "a" 1) (dht/node-id "b" 1)))
    (is (= 64 (count (dht/node-id "fake" 1))))))


(deftest lookup-returns-only-remote-peers
  (testing "the reference transport excludes self"
    (let [{:keys [stores]} (grid 3)
          [a] stores
          self-id (get-in a [:net :peer :id])
          result (dht/lookup (:net a) (dht/node-id "target" 9))]
      (is (= 2 (count result)))
      (is (not-any? #(= self-id (:id %)) result))))
  (testing
    "lookup enforces the non-self contract even when known-peers returns self"
    (let [self {:id (dht/node-id "self" 1), :host "self", :port 1}
          remote {:id (dht/node-id "remote" 2), :host "remote", :port 2}
          net
          #_{:clj-kondo/ignore [:missing-protocol-method]}
          (reify
            dht/IDhtNet
            (self-peer [_] self)

            (known-peers [_ _target-id _n] [self remote])

            (find-closer [_ _peer _target-id] []))]
      (is (= [remote] (dht/lookup net (dht/node-id "target" 9)))))))


(deftest lookup-deduplicates-candidates-by-id
  (testing
    "iterative lookup does not query the same node ID twice in the same round even with different metadata"
    (let [id-self (apply str "0" (repeat 63 "0"))
          id-a (apply str "a" (repeat 63 "0"))
          id-c (apply str "c" (repeat 63 "0"))
          queries (atom [])
          net
          #_{:clj-kondo/ignore [:missing-protocol-method]}
          (reify
            dht/IDhtNet
            (self-peer [_] {:id id-self, :host "self", :port 0})

            (known-peers
              [_ _target-id _n]
              [{:id id-a,
                :host "h1",
                :port 1}])

            (find-closer
              [_ peer _target-id]
              (swap! queries conj peer)
              (if (= (:id peer) id-a)
                [{:id id-c, :host "h3", :port 3}
                 {:id id-c, :host "h3", :port 3, :extra true}]
                [])))]
      (dht/lookup net (apply str "f" (repeat 63 "0")))
      (is (= [id-a id-c] (map :id @queries))))))


;; ---------------------------------------------------------------------------
;; Put: local durability plus best-effort replication
;; ---------------------------------------------------------------------------

(deftest materialize-replicates-opaque-payloads
  (testing
    "one DHT materializing an opaque payload replicates it into every
            peer's local content store"
    (let [{:keys [stores]} (grid 3)
          [a b c] stores
          v {:bytes [1 2 3]}
          address (jing/materialize! a v)]
      (is (= (jing/segment-key v) address))
      (doseq [local [(:local a) (:local b) (:local c)]]
        (is (= v (jing/get local address ::miss))
            "every peer local holds the payload after replication")))))


(deftest duplicate-direct-put-reports-present
  (testing "a second direct put of an already-stored address returns :present"
    (let [{[a] :stores} (grid 1)
          address (jing/segment-key {:bytes [4]})]
      (is (= :inserted ((:put-content-fn a) address {:bytes [4]})))
      (is (= :present ((:put-content-fn a) address {:bytes [4]}))))))


(deftest invalid-direct-puts-touch-no-store
  (testing
    "an invalid address or a hash mismatch throws before any local
            write or network store call"
    (let [{:keys [stores]} (grid 2)
          [a b] stores
          k (jing/segment-key {:x 1})]
      (is (throws? #((:put-content-fn a) :plain {:x 1})))
      (is (throws? #((:put-content-fn a) k {:y 2})))
      (is (throws? #((:put-content-fn a) :segment/sha256-short {:x 1})))
      (is (= ::miss (jing/get (:local a) k ::miss)) "no local write happened")
      (is (= ::miss (jing/get (:local b) k ::miss))
          "no network store call reached a peer"))))


(deftest invalid-local-put-result-throws
  (testing
    "a local backend answering outside :inserted/:present is a
            contract violation and throws before any replication"
    (let [registry (atom {})
          peer-a {:id (dht/node-id "fake" 0), :host "fake", :port 0}
          peer-b {:id (dht/node-id "fake" 1), :host "fake", :port 1}
          bad-local {:put-content-fn (fn [_ _] :bogus),
                     :get-content-fn (fn [_ not-found] not-found)}
          local-b (mem/create-content-mem)]
      (swap! registry assoc (:id peer-a) {:peer peer-a, :local bad-local})
      (swap! registry assoc (:id peer-b) {:peer peer-b, :local local-b})
      (let [a (dht/create-content-dht
                {:net (->FakeNet peer-a registry (atom 0)), :local bad-local})
            k (jing/segment-key {:x 1})]
        (is (throws? #((:put-content-fn a) k {:x 1})))
        (is (= ::miss (jing/get local-b k ::miss))
            "no replication reached the peer")))))


(deftest peer-store-failure-keeps-local-durability
  (testing
    "a false store acknowledgement is swallowed: the exact local
            :inserted/:present result is returned and the local backend keeps
            the payload"
    (let [{:keys [stores registry]} (grid 3)
          [a b c] stores
          id-b (dht/node-id "fake" 1)
          payload {:bytes [1]}
          address (jing/segment-key payload)]
      (swap! registry assoc-in [id-b :store-fails?] true)
      (is (= :inserted ((:put-content-fn a) address payload))
          "the local result is returned after all replication attempts")
      (is (= payload (jing/get (:local a) address ::miss))
          "local durability is intact")
      (is (= ::miss (jing/get (:local b) address ::miss))
          "the refusing peer stores nothing")
      (is (= payload (jing/get (:local c) address ::miss))
          "the reachable peer still replicated"))))


;; ---------------------------------------------------------------------------
;; Get: local read first, verified fetch-and-cache second
;; ---------------------------------------------------------------------------

(deftest absent-address-and-stored-nil
  (testing "absent reads report not-found; nil is a legal present payload"
    (let [{[a b] :stores} (grid 3)
          nil-address (jing/materialize! (:local b) nil)]
      (is (= :none (jing/get a (jing/segment-key {:ghost 1}) :none)))
      (is (= (jing/segment-key nil) nil-address))
      (is (nil? (jing/get a nil-address ::miss))
          "nil fetched over the wire is present, not missing"))))


(deftest fetched-content-is-verified-and-cached
  (testing
    "a value materialized only on a remote peer's local is fetched,
            verified, and cached into the reading store's local"
    (let [{[a b] :stores} (grid 3)
          v {:bytes [7]}
          address (jing/materialize! (:local b) v)]
      (is (= ::miss (jing/get (:local a) address ::miss))
          "a's local is empty before the read")
      (is (= v (jing/get a address ::miss)) "the read resolves over the grid")
      (is (= v (jing/get (:local a) address ::miss))
          "the fetch cached the verified value into a's local"))))


(deftest forged-payload-under-requested-address-is-ignored
  (testing
    "a peer returning content that does not hash to the requested
            address is ignored and the read keeps looking"
    (let [registry (atom {})
          k (jing/segment-key {:bytes [1]})
          forged {:bytes [:evil]}
          peer-good {:id (dht/node-id "fake" 0), :host "fake", :port 0}
          local-good (mem/create-content-mem)
          peer-bad {:id (dht/node-id "fake" 1), :host "fake", :port 1}
          local-bad (raw-handle {k forged})]
      (swap! registry assoc
             (:id peer-good)
             {:peer peer-good, :local local-good})
      (swap! registry assoc (:id peer-bad) {:peer peer-bad, :local local-bad})
      (let [a (dht/create-content-dht
                {:net (->FakeNet peer-good registry (atom 0)),
                 :local local-good})]
        (is
          (= :none (jing/get a k :none))
          "the forged segment does not verify against the requested address")))))


;; ---------------------------------------------------------------------------
;; Observer integration: intake pool converges on one entry
;; ---------------------------------------------------------------------------

(deftest observer-equal-payloads-converge-and-replicate
  (testing
    "equal payloads arriving through two ringbuffers materialize
            through the DHT into one local entry and replicate to the grid"
    (let [{:keys [stores]} (grid 2)
          [a b] stores
          local (:local a)
          payload {:nested {:v [1 2 3]}}
          address (jing/segment-key payload)
          s1 (open-stream payload)
          s2 (open-stream payload)
          r1 (jing/observe-step! a (jing/observer-state [s1 s2]))
          r2 (jing/observe-step! a (:state r1))]
      (is (= :ok (:signal r1)))
      (is (= :ok (:signal r2)))
      (is (= address (:address r1)))
      (is (= address (:address r2))
          "both intake streams converge on the same content address")
      (is (= {address payload} (:content @(:state local)))
          "exactly one local entry: no duplicates, no provenance")
      (is (= payload (jing/get (:local b) address ::miss))
          "DHT replication fanned the payload out to the peer's local"))))


;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(deftest close-is-idempotent-and-stops-ops
  (testing
    "close closes the net and the local backend exactly once, and
            put/get after close throw"
    (let [{:keys [stores]} (grid 1)
          [a] stores
          closes (:closes (:net a))
          address (jing/segment-key {:x 1})]
      (is (nil? ((:close-fn a))))
      (is (nil? ((:close-fn a))))
      (is (= 1 @closes) "close-net! ran exactly once")
      (is (true? (:closed? @(:state (:local a))))
          "the local backend was closed")
      (is (throws? #((:put-content-fn a) address {:x 1})))
      (is (throws? #(jing/get a address ::miss)))))
  (testing "net-close failure and local-close failure/retry behavior"
           ;; Covered by detailed test:
           ;; close-failures-attempt-both-and-retry
           ))


(deftest lookup-scale-bounds
  (testing
    "iterative lookup only queries peers within the closest-k shortlist and terminates when they are all queried"
    (let [target-id (apply str "0" (repeat 64 "0"))
          self-id (apply str "f" (repeat 64 "f"))
          make-id (fn [prefix idx]
                    (let [suffix (str idx)
                          pad-len (- 64 (count prefix) (count suffix))]
                      (str prefix (apply str (repeat pad-len "0")) suffix)))
          known (mapv (fn [i] {:id (make-id "2" i), :host "fake", :port i})
                      (range 1 21))
          closer-peer {:id (make-id "1" 999), :host "fake", :port 999}
          far-peer {:id (make-id "a" 999), :host "fake", :port 888}
          queried (atom #{})
          net
          #_{:clj-kondo/ignore [:missing-protocol-method]}
          (reify
            dht/IDhtNet
            (self-peer [_] {:id self-id, :host "self", :port 0})

            (known-peers [_ _target-id _n] known)

            (find-closer
              [_ peer _target-id]
              (swap! queried conj (:id peer))
              [closer-peer far-peer]))
          result (dht/lookup net target-id)]
      ;; The lookup must return the closest k (20) peers
      (is (= 20 (count result)))
      ;; The closest peer (closer-peer) must be in the result
      (is (some #(= (:id %) (:id closer-peer)) result))
      ;; The far-peer must not be in the result
      (is (not (some #(= (:id %) (:id far-peer)) result)))
      ;; The far-peer must never have been queried
      (is (not (contains? @queried (:id far-peer))))
      ;; The closer-peer must have been queried
      (is (contains? @queried (:id closer-peer)))
      ;; Verify that lookup terminated successfully
      (is (seq @queried)))))


(deftest peer-store-exception-keeps-local-durability
  (testing
    "a throwing store acknowledgement is swallowed: the exact local
            :inserted/:present result is returned and the local backend keeps
            the payload"
    (let [{:keys [stores registry]} (grid 3)
          [a b c] stores
          id-b (dht/node-id "fake" 1)
          payload {:bytes [1]}
          address (jing/segment-key payload)]
      (swap! registry assoc-in [id-b :store-throws?] true)
      (is
        (= :inserted ((:put-content-fn a) address payload))
        "the local result is returned after all replication attempts, even if a peer store throws")
      (is (= payload (jing/get (:local a) address ::miss))
          "local durability is intact")
      (is (= ::miss (jing/get (:local b) address ::miss))
          "the throwing peer stores nothing")
      (is (= payload (jing/get (:local c) address ::miss))
          "the reachable peer still replicated"))))


(deftest throwing-first-fetch-continues-to-later-valid-peer
  (testing
    "when a peer's fetch-content throws, the lookup continues to later peers and retrieves the content"
    (let [{:keys [stores registry]} (grid 3)
          [a b c] stores
          v {:bytes [7]}
          address (jing/segment-key v)
          target-id (jing/segment-hash address)
          ;; Determine which of b or c is closer to target-id
          dist-b (kad/distance (get-in b [:net :peer :id]) target-id)
          dist-c (kad/distance (get-in c [:net :peer :id]) target-id)
          b-closer? (neg? (compare dist-b dist-c))
          [closer-peer farther-peer] (if b-closer? [b c] [c b])]
      ;; The closer peer throws on fetch
      (swap! registry assoc-in
             [(get-in closer-peer [:net :peer :id]) :fetch-throws?]
             true)
      ;; The farther peer has the content
      (jing/materialize! (:local farther-peer) v)
      (is (= ::miss (jing/get (:local a) address ::miss))
          "a's local is empty before the read")
      (is
        (= v (jing/get a address ::miss))
        "the read resolves over the grid and retrieves the value from farther-peer despite closer-peer throwing")
      (is (= v (jing/get (:local a) address ::miss))
          "the fetched value is cached in a's local"))))


(deftest close-failures-attempt-both-and-retry
  (testing
    "close attempts both net and local closes even if either throws, propagates the first error, leaves closed-atom false, and can be retried"
    (let [net-closed (atom 0)
          local-closed (atom 0)
          net-should-throw (atom false)
          local-should-throw (atom false)
          net
          #_{:clj-kondo/ignore [:missing-protocol-method]}
          (reify
            dht/IDhtNet
            (close-net!
              [_]
              (swap! net-closed inc)
              (when @net-should-throw
                (throw (ex-info "Simulated net close failure" {})))))
          local {:put-content-fn (fn [_ _] :inserted),
                 :get-content-fn (fn [_ nf] nf),
                 :close-fn (fn []
                             (swap! local-closed inc)
                             (when @local-should-throw
                               (throw (ex-info "Simulated local close failure"
                                               {}))))}
          dht-store (dht/create-content-dht {:net net, :local local})]
      ;; Scenario 1: net close throws
      (reset! net-should-throw true)
      (reset! local-should-throw false)
      (is (throws? (:close-fn dht-store))
          "first close attempt throws because net close fails")
      (is (= 1 @net-closed) "net close was attempted")
      (is (= 1 @local-closed)
          "local close was also attempted despite net close throwing")
      (is (false? @(:closed-atom dht-store))
          "closed-atom remains false on failure")
      ;; Scenario 2: local close throws
      (reset! net-should-throw false)
      (reset! local-should-throw true)
      (is (throws? (:close-fn dht-store))
          "second close attempt throws because local close fails")
      (is (= 2 @net-closed) "net close was attempted again")
      (is (= 2 @local-closed) "local close was attempted again")
      (is (false? @(:closed-atom dht-store))
          "closed-atom remains false on failure")
      ;; Scenario 3: both succeed on retry
      (reset! net-should-throw false)
      (reset! local-should-throw false)
      (is (nil? ((:close-fn dht-store)))
          "close succeeds when both net and local closes succeed")
      (is (= 3 @net-closed) "net close was run again")
      (is (= 3 @local-closed) "local close was run again")
      (is (true? @(:closed-atom dht-store)) "closed-atom is now true")
      ;; Scenario 4: idempotent subsequent calls
      (is (nil? ((:close-fn dht-store)))
          "subsequent close calls do nothing and return nil")
      (is (= 3 @net-closed) "no new net close attempt")
      (is (= 3 @local-closed) "no new local close attempt"))))
