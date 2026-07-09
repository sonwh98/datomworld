(ns dao.jing.dht
  "DHT backend for the dao.jing storage boundary: IKVStore over a peer grid.
  Specification: docs/design/dao.jing.dht.md.

  The backend tightens the IKVStore contract with two key classes, each
  recoverable from the key alone (get sees only the key):

    :segment/<sha256>  content-addressed immutable segments, put!-only.
                       k = hash(v-map), so fetched bytes verify against the
                       key and any node may cache any segment forever.
    :root/<name>       caller-named mutable references, cas!-only, never
                       cached (a stale :rev would feed the caller's cas!
                       retry loop a wrong old-rev).

  Un-namespaced keys are rejected. delete! is advisory unpin (option 1 of
  the design doc): it drops this node's copy only; replicas elsewhere age
  out on their own, so a deleted segment may remain retrievable.

  cas! and get on a root are forwarded to the root's owner: the live peer
  nearest the key, which serializes writers through its local store. This
  is a deliberate placeholder for the sortition consensus (design doc,
  `cas!` over the network): it is not partition-safe and the owner is a
  single point of failure per root.

  Transport is behind the IDhtNet protocol below; dao.jing.dht.node is the
  UDP Kademlia implementation."
  (:require [dao.jing :as jing]
            [dao.jing.dht.kad :as kad]
            [datomworld :as dw]))


;; =============================================================================
;; Key discipline
;; =============================================================================
;; canonical/content-hash/segment-key/key-class are dao.jing's own
;; content-addressing discipline, not DHT-specific; they live in dao.jing
;; itself (see docs/design/dao.jing.md, "The Segment and Root Keyspace").
;; This backend just consumes them, the way any backend could.


(defn node-id
  "Deterministic node id: SHA-256 of host:port. Node ids share the key
  space with content hashes, so XOR routing treats peers and keys uniformly."
  [host port]
  (dw/sha256 (str host ":" port)))


(defn- key->target
  "The routing target for a key: segment names already are content hashes;
  root names are hashed into the same id space."
  [k]
  (case (jing/key-class k)
    :segment (name k)
    :root (dw/sha256 (str k))))


;; =============================================================================
;; Transport boundary
;; =============================================================================

(defprotocol IDhtNet
  "The per-peer RPC surface dao.jing.dht requires of a transport. All
  methods that cross the network return nil when the peer is unreachable;
  the caller decides what unreachability means per key class."

  (self-peer
    [net]
    "This node's own {:id :host :port} peer map.")

  (known-peers
    [net target-id n]
    "The n locally-known peers nearest target-id, nearest first. No IO.")

  (find-closer
    [net peer target-id]
    "Ask peer for the peers it knows nearest target-id.
     Returns a seq of peer maps, or nil when the peer is unreachable.")

  (store-segment!
    [net peer k v-map]
    "Ask peer to hold immutable segment k. Best-effort; returns a boolean.")

  (fetch-segment
    [net peer k]
    "Ask peer for segment k. Returns the v-map, or nil.")

  (root-get
    [net peer k]
    "Read mutable root k from peer. Returns {:value v-map-or-nil}, or nil
     when the peer is unreachable.")

  (root-cas!
    [net peer k old-rev v-map]
    "CAS root k at peer. Returns a boolean, or nil when unreachable.")

  (close-net!
    [net]
    "Release the transport's local resources (socket, threads)."))


;; =============================================================================
;; Iterative lookup
;; =============================================================================

(def ^:private alpha
  "Lookup concurrency width (queried per round, though rounds run
  sequentially today)."
  3)


(defn lookup
  "Iteratively converge on the kad/k known peers nearest target-id, self
  included, nearest first. Queries unvisited candidates in distance order
  until none remain: exhaustive on small networks; the classic
  no-closer-result early termination can layer on later."
  [net target-id]
  (let [self (self-peer net)]
    ;; the shortlist is keyed by :id, so the same node seen with
    ;; differing metadata (another advertised :host, an extra field)
    ;; occupies one slot and is queried at most once per lookup
    (loop [shortlist (into {(:id self) self}
                           (map (juxt :id identity))
                           (known-peers net target-id kad/k))
           queried-ids #{(:id self)}]
      (let [candidates (->> (vals shortlist)
                            (remove #(queried-ids (:id %)))
                            (sort-by #(kad/distance (:id %) target-id))
                            (take alpha))]
        (if (empty? candidates)
          (->> (vals shortlist)
               (sort-by #(kad/distance (:id %) target-id))
               (take kad/k)
               vec)
          (recur (into shortlist
                       (comp (mapcat #(find-closer net % target-id))
                             (map (juxt :id identity)))
                       candidates)
                 (into queried-ids (map :id) candidates)))))))


(defn- owner
  "The live peer nearest a root's key: the serialization point for cas!
  until a real consensus mechanism exists."
  [net k]
  (first (lookup net (key->target k))))


(defn- owner-here?
  "Is this node the root's owner? The one place the single-owner
  placeholder decision lives; the sortition swap replaces owner and this."
  [net own]
  (= (:id own) (:id (self-peer net))))


;; =============================================================================
;; The backend
;; =============================================================================

(defrecord KVDht
  [net local]

  jing/IKVStore

  (put!
    [_ k v-map]
    (when (not= :segment (jing/key-class k))
      (throw (ex-info
               "put! is for immutable segments only; roots are cas!-managed"
               {:k k})))
    (let [expected (jing/segment-key v-map)]
      (when (not= k expected)
        (throw (ex-info "a segment key must be the content hash of its value"
                        {:k k, :expected expected}))))
    (jing/put! local k v-map)
    (let [self-id (:id (self-peer net))
          peers (remove #(= self-id (:id %)) (lookup net (key->target k)))]
      ;; replication is best-effort and, on the JVM, concurrent: the call
      ;; blocks until the slowest peer answers or times out, not for the
      ;; sum of every peer's timeout. future's shared unbounded pool is
      ;; acceptable because deref bounds each put! to at most kad/k
      ;; in-flight threads; sustained write fan-out would deserve a
      ;; dedicated bounded executor instead
      #?(:clj (run! deref
                    (mapv (fn [peer]
                            (future (store-segment! net peer k v-map)))
                          peers))
         :default (run! (fn [peer] (store-segment! net peer k v-map)) peers)))
    true)


  (cas!
    [_ k old-rev v-map]
    (when (not= :root (jing/key-class k))
      (throw (ex-info "cas! is for mutable roots only; segments are immutable"
                      {:k k})))
    (let [own (owner net k)]
      (if (owner-here? net own)
        (jing/cas! local k old-rev v-map)
        (let [res (root-cas! net own k old-rev v-map)]
          (when (nil? res)
            ;; unreachable is not the same fact as a lost CAS: returning
            ;; false would send the caller's retry loop chasing a rev it
            ;; can never read
            (throw (ex-info "root owner unreachable" {:k k, :owner own})))
          (boolean res)))))


  (get
    [_ k not-found]
    (case (jing/key-class k)
      :segment
      (let [v (jing/get local k ::none)]
        (if (not= ::none v)
          v
          (let [self-id (:id (self-peer net))
                ;; sequential and nearest-first by design: stop
                ;; at the first peer whose bytes verify,
                ;; spending no traffic on the rest
                fetched (some (fn [peer]
                                (when (not= self-id (:id peer))
                                  (when-let [v (fetch-segment net peer k)]
                                    ;; integrity: received bytes must
                                    ;; hash back to k (peers are
                                    ;; untrusted)
                                    (when (= (name k) (jing/content-hash v))
                                      v))))
                              (lookup net (key->target k)))]
            (if (some? fetched)
              (do
                ;; immutable, so cache forever
                (jing/put! local k fetched)
                ;; normalize the stamp: the remote :rev is that
                ;; store's artifact, not content, and local put!
                ;; stamped 0
                (assoc fetched :rev 0))
              not-found))))
      :root
      ;; never cached: a root read must be fresh or fail loudly
      (let [own (owner net k)]
        (if (owner-here? net own)
          (jing/get local k not-found)
          (if-let [res (root-get net own k)]
            (if (some? (:value res)) (:value res) not-found)
            (throw (ex-info "root owner unreachable"
                            {:k k, :owner own})))))))


  (delete!
    [_ k]
    ;; advisory unpin (design doc, delete! option 1): drop this node's
    ;; copy only; replicas elsewhere are outside our control
    (jing/key-class k)
    (jing/delete! local k))


  (close! [_] (close-net! net) (jing/close! local) nil))


(defn create-kv-dht
  "Wrap an IDhtNet transport and a local IKVStore as an IKVStore over the
  peer grid. `local` is both this node's cache and its share of the
  keyspace, so the net must serve its incoming requests from the same
  store. dao.jing.dht.node/create-kv-dht-udp wires both ends for UDP."
  [{:keys [net local]}]
  (->KVDht net local))
