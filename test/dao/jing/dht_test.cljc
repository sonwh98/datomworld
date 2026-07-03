(ns dao.jing.dht-test
  "Contract tests for the dao.jing.dht backend semantics: key discipline,
  content addressing, and IKVStore behavior over a peer grid. The grid here
  is an in-memory IDhtNet fake (a full mesh of KVMem-backed peers) so the
  semantics stay cross-platform; the real UDP transport is exercised in
  dao.jing.dht.node-test."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.jing :as kv]
    [dao.jing.dht :as dht]
    [dao.jing.dht.kad :as kad]))


;; ---------------------------------------------------------------------------
;; In-memory peer grid
;; ---------------------------------------------------------------------------

(defn- local-of
  [registry peer]
  (get-in @registry [(:id peer) :local]))


(defrecord FakeNet
  [peer registry]

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


  (store-segment! [_ to k v-map] (kv/put! (local-of registry to) k v-map))


  (fetch-segment
    [_ to k]
    (let [v (kv/get (local-of registry to) k ::none)]
      (when (not= ::none v) v)))


  (root-get
    [_ to k]
    (let [v (kv/get (local-of registry to) k ::none)]
      {:value (when (not= ::none v) v)}))


  (root-cas!
    [_ to k old-rev v-map]
    (kv/cas! (local-of registry to) k old-rev v-map))


  (close-net! [_] nil))


(defn- grid
  "n KVDht stores over one simulated full-mesh network.
  Returns {:stores [store ...], :registry registry}."
  [n]
  (let [registry (atom {})]
    {:registry registry,
     :stores (mapv
               (fn [i]
                 (let [peer {:id (dht/node-id "fake" i), :host "fake", :port i}
                       local (kv/create-kv-mem)]
                   (swap! registry assoc (:id peer) {:peer peer, :local local})
                   (dht/create-kv-dht {:net (->FakeNet peer registry),
                                       :local local})))
               (range n))}))


;; ---------------------------------------------------------------------------
;; Key discipline
;; ---------------------------------------------------------------------------

(deftest key-discipline
  (testing "keys must be :segment/<hash> or :root/<name>, used by class"
    (let [{[a] :stores} (grid 1)]
      (is (thrown? #?(:cljs js/Error
                      :default Exception)
            (kv/get a :plain nil))
          "un-namespaced keys are rejected")
      (is (thrown? #?(:cljs js/Error
                      :default Exception)
            (kv/put! a :plain {:x 1})))
      (is (thrown? #?(:cljs js/Error
                      :default Exception)
            (kv/put! a :root/r {:x 1}))
          "roots are cas!-only")
      (is (thrown? #?(:cljs js/Error
                      :default Exception)
            (kv/cas! a (dht/segment-key {:x 1}) 0 {:x 1}))
          "segments are immutable")
      (is (thrown? #?(:cljs js/Error
                      :default Exception)
            (kv/put! a :segment/not-the-hash {:x 1}))
          "a segment key must be the content hash of its value"))))


(deftest segment-key-is-content-addressed
  (testing "the key is deterministic, order-insensitive, and excludes :rev"
    (is (= (dht/segment-key {:a 1, :b 2}) (dht/segment-key {:b 2, :a 1})))
    (is (= (dht/segment-key {:a 1}) (dht/segment-key {:a 1, :rev 7}))
        ":rev is the backend's stamp, not content")
    (is (not= (dht/segment-key {:a 1}) (dht/segment-key {:a 2})))
    (is (= "segment" (namespace (dht/segment-key {:a 1}))))))


;; ---------------------------------------------------------------------------
;; Segments: put! / get / delete!
;; ---------------------------------------------------------------------------

(deftest put-then-get-across-the-grid
  (testing "a segment put! on one node is readable from every node"
    (let [{[a b c] :stores} (grid 3)
          v {:bytes [1 2 3]}
          k (dht/segment-key v)]
      (is (true? (kv/put! a k v)))
      (is (= {:bytes [1 2 3], :rev 0} (kv/get b k nil)))
      (is (= {:bytes [1 2 3], :rev 0} (kv/get c k nil))))))


(deftest get-absent-returns-not-found
  (let [{[a] :stores} (grid 2)]
    (is (= :none (kv/get a (dht/segment-key {:ghost 1}) :none)))))


(deftest put-is-idempotent
  (testing "re-put! of the same content is a no-op that still returns true"
    (let [{[a] :stores} (grid 2)
          v {:bytes [4]}
          k (dht/segment-key v)]
      (is (true? (kv/put! a k v)))
      (is (true? (kv/put! a k v)))
      (is (= {:bytes [4], :rev 0} (kv/get a k nil))))))


(deftest delete-is-advisory-unpin
  (testing "delete! drops only the local copy; get refetches from the grid"
    (let [{[a] :stores} (grid 3)
          v {:bytes [9]}
          k (dht/segment-key v)]
      (kv/put! a k v)
      (is (true? (kv/delete! a k)))
      (is (= {:bytes [9], :rev 0} (kv/get a k nil))
          "the segment survives on other peers"))))


(deftest fetched-segments-are-cached-locally
  (testing "a segment fetched from the grid is cached forever locally"
    (let [{[a b] :stores} (grid 3)
          v {:bytes [7]}
          k (dht/segment-key v)]
      (kv/put! a k v)
      (kv/delete! b k)
      (is (= :miss (kv/get (:local b) k :miss)))
      (is (= {:bytes [7], :rev 0} (kv/get b k nil)))
      (is (= {:bytes [7], :rev 0} (kv/get (:local b) k :miss))
          "the fetch populated b's local cache"))))


(deftest fetch-verifies-content-hash
  (testing "a peer returning bytes that do not hash to k is ignored"
    (let [{[a b] :stores} (grid 2)
          v {:bytes [1]}
          k (dht/segment-key v)]
      ;; poison b's local copy directly, bypassing the contract checks
      (kv/put! (:local b) k {:bytes [:evil]})
      (is (= :none (kv/get a k :none))
          "the forged segment does not verify against the key"))))


;; ---------------------------------------------------------------------------
;; Roots: cas! / get
;; ---------------------------------------------------------------------------

(deftest root-cas-serializes-across-nodes
  (testing "cas! routes to the root's owner: revs advance globally"
    (let [{[a b] :stores} (grid 3)
          k :root/pointer]
      (is (true? (kv/cas! a k 0 {:p "1"})))
      (is (= {:p "1", :rev 1} (kv/get b k nil))
          "roots read fresh from the owner, never from a cache")
      (is (false? (kv/cas! b k 0 {:p "2"})) "a stale rev fails")
      (is (true? (kv/cas! b k 1 {:p "2"})))
      (is (= {:p "2", :rev 2} (kv/get a k nil))))))


(deftest root-get-absent-returns-not-found
  (let [{[a] :stores} (grid 2)] (is (= :none (kv/get a :root/ghost :none)))))


;; ---------------------------------------------------------------------------
;; Single node / close!
;; ---------------------------------------------------------------------------

(deftest single-node-grid-works-standalone
  (testing "a grid of one peer degenerates to a local store"
    (let [{[a] :stores} (grid 1)
          v {:bytes [5]}
          k (dht/segment-key v)]
      (is (true? (kv/put! a k v)))
      (is (= {:bytes [5], :rev 0} (kv/get a k nil)))
      (is (true? (kv/cas! a :root/r 0 {:p "x"})))
      (is (= {:p "x", :rev 1} (kv/get a :root/r nil))))))


(deftest close-returns-nil
  (let [{[a] :stores} (grid 1)] (is (nil? (kv/close! a)))))


(deftest lookup-deduplicates-candidates-by-id
  (testing
    "iterative lookup does not query the same node ID twice in the same round even with different metadata"
    (let [id-self (apply str "0" (repeat 63 "0"))
          id-a (apply str "a" (repeat 63 "0"))
          id-c (apply str "c" (repeat 63 "0"))
          queries (atom [])
          net
          (reify
            dht/IDhtNet
            (self-peer [_] {:id id-self, :host "self", :port 0})

            (known-peers [_ target-id n] [{:id id-a, :host "h1", :port 1}])

            (find-closer
              [_ peer target-id]
              (swap! queries conj peer)
              (if (= (:id peer) id-a)
                [{:id id-c, :host "h3", :port 3}
                 {:id id-c, :host "h3", :port 3, :extra true}]
                [])))]
      (dht/lookup net (apply str "f" (repeat 63 "0")))
      (is (= [id-a id-c] (map :id @queries))))))
