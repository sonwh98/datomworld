(ns dao.jing.dht-test
  "Contract tests for the dao.jing.dht backend semantics: key discipline,
  content addressing, and IKVStore behavior over a peer grid. The grid here
  is an in-memory IDhtNet fake (a full mesh of KVMem-backed peers) so the
  semantics stay cross-platform; the real UDP transport is exercised in
  dao.jing.dht.node-test."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
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


  (store-segment!
    [_ to k v]
    (jing/cas! (local-of registry to) k jing/absent v))


  (fetch-segment
    [_ to k]
    (let [v (jing/get (local-of registry to) k ::none)]
      {:found? (not= ::none v), :value (when (not= ::none v) v)}))


  (root-get
    [_ to k]
    (let [v (jing/get (local-of registry to) k ::none)]
      {:found? (not= ::none v), :value (when (not= ::none v) v)}))


  (root-cas!
    [_ to k expected v]
    (jing/cas! (local-of registry to) k expected v))


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
                       local (mem/create-kv-mem)]
                   (swap! registry assoc (:id peer) {:peer peer, :local local})
                   (dht/create-kv-dht {:net (->FakeNet peer registry),
                                       :local local})))
               (range n))}))


;; ---------------------------------------------------------------------------
;; Key discipline (DHT enforcement)
;; ---------------------------------------------------------------------------
;; canonical/content-hash/segment-key/key-class as pure functions are tested
;; against dao.jing directly in test/dao/jing_test.cljc. This test is
;; DHT-specific: it exercises KVDht's put!/cas!/get actually enforcing the
;; discipline over the grid — dao.jing.mem's own KVMem enforces none of this.

(deftest key-discipline
  (testing "keys must be :segment/<hash> or :root/<name>, used by class"
    (let [{[a] :stores} (grid 1)]
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/get a :plain nil))
          "un-namespaced keys are rejected")
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/cas! a :plain jing/absent {:x 1})))
      (is (true? (jing/cas! a :root/r jing/absent {:x 1}))
          "roots are cas!-managed")
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/cas! a (jing/segment-key {:x 1}) {:x 1} {:x 2}))
          "segments are immutable and key must hash to value")
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Object)
            (jing/cas! a :segment/not-the-hash jing/absent {:x 1}))
          "a segment key must be the content hash of its value"))))


;; ---------------------------------------------------------------------------
;; Segments: put! / get / delete!
;; ---------------------------------------------------------------------------

(deftest put-then-get-across-the-grid
  (testing "a segment put! on one node is readable from every node"
    (let [{[a b c] :stores} (grid 3)
          v {:bytes [1 2 3]}
          k (jing/segment-key v)]
      (is (true? (jing/cas! a k jing/absent v)))
      (is (= v (jing/get b k nil)))
      (is (= v (jing/get c k nil))))))


(deftest get-absent-returns-not-found
  (let [{[a] :stores} (grid 2)]
    (is (= :none (jing/get a (jing/segment-key {:ghost 1}) :none)))))


(deftest opaque-values-cross-the-grid-intact
  (testing "a segment may hold any value, and it survives the network path"
    (let [{[a b] :stores} (grid 3)]
      (doseq [v [42 "s" :kw [1 2 3] #{:x} {:m 1}]]
        (let [k (jing/segment-key v)]
          (is (true? (jing/cas! a k jing/absent v)))
          ;; drop b's replica so the read must go over the wire and
          ;; re-verify the content hash on arrival
          (jing/delete! b k)
          (is (= v (jing/get b k ::miss))
              (str (pr-str v) " must survive the fetch path")))))))


(deftest stored-nil-is-not-absence-across-the-grid
  (testing
    "nil is a legal segment value: a remote fetch must report it as found
    rather than collapsing it into not-found"
    (let [{[a b] :stores} (grid 3)
          k (jing/segment-key nil)]
      (is (true? (jing/cas! a k jing/absent nil)))
      (jing/delete! b k)
      (is (nil? (jing/get b k ::miss))
          "a nil segment fetched over the wire is present, not missing")
      (is (= ::miss (jing/get b (jing/segment-key {:never 1}) ::miss))
          "a genuinely absent segment still reports not-found")))
  (testing "and the same holds for a root, whose read is never cached"
    (let [{[a b] :stores} (grid 3)]
      (is (true? (jing/cas! a :root/n jing/absent nil)))
      (is (nil? (jing/get b :root/n ::miss))
          "a nil root read from a non-owner is present, not missing")
      (is (= ::miss (jing/get b :root/never ::miss)))
      (is (true? (jing/cas! b :root/n nil {:now "set"}))
          "nil is quotable as the expected value over the wire"))))


(deftest put-is-idempotent
  (testing "re-put! of the same content is a no-op that still returns true"
    (let [{[a] :stores} (grid 2)
          v {:bytes [4]}
          k (jing/segment-key v)]
      (is (true? (jing/cas! a k jing/absent v)))
      (is (true? (jing/cas! a k jing/absent v)))
      (is (= v (jing/get a k nil))))))


(deftest delete-is-advisory-unpin
  (testing "delete! drops only the local copy; get refetches from the grid"
    (let [{[a] :stores} (grid 3)
          v {:bytes [9]}
          k (jing/segment-key v)]
      (jing/cas! a k jing/absent v)
      (is (true? (jing/delete! a k)))
      (is (= v (jing/get a k nil)) "the segment survives on other peers"))))


(deftest fetched-segments-are-cached-locally
  (testing "a segment fetched from the grid is cached forever locally"
    (let [{[a b] :stores} (grid 3)
          v {:bytes [7]}
          k (jing/segment-key v)]
      (jing/cas! a k jing/absent v)
      (jing/delete! b k)
      (is (= :miss (jing/get (:local b) k :miss)))
      (is (= v (jing/get b k nil)))
      (is (= v (jing/get (:local b) k :miss))
          "the fetch populated b's local cache"))))


(deftest fetch-verifies-content-hash
  (testing "a peer returning bytes that do not hash to k is ignored"
    (let [{[a b] :stores} (grid 2)
          v {:bytes [1]}
          k (jing/segment-key v)]
      ;; poison b's local copy directly, bypassing the contract checks
      (jing/cas! (:local b) k jing/absent {:bytes [:evil]})
      (is (= :none (jing/get a k :none))
          "the forged segment does not verify against the key"))))


;; ---------------------------------------------------------------------------
;; Roots: cas! / get
;; ---------------------------------------------------------------------------

(deftest root-cas-serializes-across-nodes
  (testing "cas! routes to the root's owner: the guard is global"
    (let [{[a b] :stores} (grid 3)
          k :root/pointer]
      (is (true? (jing/cas! a k jing/absent {:p "1"})))
      (is (= {:p "1"} (jing/get b k nil))
          "roots read fresh from the owner, never from a cache")
      (is (false? (jing/cas! b k jing/absent {:p "2"}))
          "a stale expected value fails")
      (is (true? (jing/cas! b k {:p "1"} {:p "2"})))
      (is (= {:p "2"} (jing/get a k nil))))))


(deftest root-get-absent-returns-not-found
  (let [{[a] :stores} (grid 2)] (is (= :none (jing/get a :root/ghost :none)))))


;; ---------------------------------------------------------------------------
;; Single node / close!
;; ---------------------------------------------------------------------------

(deftest single-node-grid-works-standalone
  (testing "a grid of one peer degenerates to a local store"
    (let [{[a] :stores} (grid 1)
          v {:bytes [5]}
          k (jing/segment-key v)]
      (is (true? (jing/cas! a k jing/absent v)))
      (is (= v (jing/get a k nil)))
      (is (true? (jing/cas! a :root/r jing/absent {:p "x"})))
      (is (= {:p "x"} (jing/get a :root/r nil))))))


(deftest close-returns-nil
  (let [{[a] :stores} (grid 1)] (is (nil? (jing/close! a)))))


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
