(ns dao.jing.dht
  "DHT backend for the dao.jing storage boundary (docs/design/dao.jing.dht.md).
   Returns a handle map {:stream s :target a :get-fn f :cas-fn g :delete-fn h
   :close-fn i} that jing/cas!, jing/get, jing/delete!, and jing/close!
   dispatch through automatically.

   Key classes, recovered from the key alone:
     :segment/sha256-<hash>  content-addressed immutable segments.
     :root/<name>            caller-named mutable references.

   Segment fetch is pull-based (DHT lookup + cache), root access is owner-
   routed (single-writer, pushed via stream replication)."
  (:require [dao.jing :as jing]
            [dao.jing.dht.kad :as kad]))


;; =============================================================================
;; Key discipline
;; =============================================================================

(defn node-id
  "Deterministic node id: SHA-256 of host:port."
  [host port]
  (jing/sha256 (str host ":" port)))


(defn- key->target
  "The routing target for a key: segment keys carry their content hash;
   root names are hashed into the same id space."
  [k]
  (case (jing/key-class k)
    :segment (jing/segment-hash k)
    :root (jing/sha256 (str k))))


;; =============================================================================
;; Transport boundary
;; =============================================================================

(defprotocol IDhtNet
  "The per-peer RPC surface dao.jing.dht requires of a transport."

  (self-peer
    [net]
    "This node's own {:id :host :port} peer map.")

  (known-peers
    [net target-id n]
    "The n locally-known peers nearest target-id, nearest first. No IO.")

  (find-closer
    [net peer target-id]
    "Ask peer for the peers it knows nearest target-id.
     Returns a seq of peer maps, or nil when unreachable.")

  (store-segment!
    [net peer k v]
    "Ask peer to hold immutable segment k. Best-effort; returns a boolean.")

  (fetch-segment
    [net peer k]
    "Ask peer for segment k. Returns {:found? bool :value v}, or nil when
     the peer is unreachable.")

  (root-get
    [net peer k]
    "Read mutable root k from peer. Returns {:found? bool :value v}, or nil
     when the peer is unreachable.")

  (root-cas!
    [net peer k expected v]
    "CAS root k at peer, guarded on the expected previous value. Returns a
     boolean, or nil when unreachable.")

  (close-net!
    [net]
    "Release the transport's local resources (socket, threads)."))


;; =============================================================================
;; Iterative lookup
;; =============================================================================

(def ^:private alpha
  "Lookup concurrency width."
  3)


(defn lookup
  "Iteratively converge on the kad/k known peers nearest target-id."
  [net target-id]
  (let [self (self-peer net)]
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
  "The live peer nearest a root's key."
  [net k]
  (first (lookup net (key->target k))))


(defn- owner-here?
  "Is this node the root's owner?"
  [net own]
  (= (:id own) (:id (self-peer net))))


;; =============================================================================
;; Handle constructor
;; =============================================================================

(defn- make-dht-get
  [handle]
  (let [{:keys [net local]} handle]
    (fn dht-get
      [k not-found]
      (case (jing/key-class k)
        :segment
        (let [v (jing/get local k ::none)]
          (if (not= ::none v)
            v
            (let [self-id (:id (self-peer net))
                  fetched (some (fn [peer]
                                  (when (not= self-id (:id peer))
                                    (when-let [res (fetch-segment net peer k)]
                                      (when (:found? res)
                                        (let [v (:value res)]
                                          (when (= k (jing/segment-key v))
                                            [v]))))))
                                (lookup net (key->target k)))]
              (if fetched
                (let [v (nth fetched 0)]
                  (jing/cas! local k jing/absent v)
                  v)
                not-found))))
        :root (let [own (owner net k)]
                (if (owner-here? net own)
                  (jing/get local k not-found)
                  (if-let [res (root-get net own k)]
                    (if (:found? res) (:value res) not-found)
                    (throw (ex-info "root owner unreachable"
                                    {:k k, :owner own})))))))))


(defn- make-dht-cas
  [handle]
  (let [{:keys [net local]} handle]
    (fn dht-cas
      [k expected v]
      (case (jing/key-class k)
        :segment
        (let [minted (jing/segment-key v)]
          (when (not= k minted)
            (throw (ex-info
                     "a segment key must be the content hash of its value"
                     {:k k, :expected minted})))
          (let [res (jing/cas! local k expected v)]
            (when res
              (let [self-id (:id (self-peer net))
                    peers (remove #(= self-id (:id %))
                                  (lookup net (key->target k)))]
                #?(:clj (run! deref
                              (mapv (fn [peer]
                                      (future (store-segment! net peer k v)))
                                    peers))
                   :default (run! (fn [peer] (store-segment! net peer k v))
                                  peers))))
            res))
        :root (let [own (owner net k)]
                (if (owner-here? net own)
                  (jing/cas! local k expected v)
                  (let [res (root-cas! net own k expected v)]
                    (when (nil? res)
                      (throw (ex-info "root owner unreachable"
                                      {:k k, :owner own})))
                    (boolean res))))))))


(defn- make-dht-delete
  [handle]
  (let [{:keys [local]} handle]
    (fn dht-delete [k] (jing/key-class k) (jing/delete! local k))))


(defn create-kv-dht
  "Wrap an IDhtNet transport and a local stream-materialized handle as a
   DHT handle. The handle works with jing/cas!, jing/get, jing/delete!,
   and jing/close! — they dispatch to DHT-specific implementations via
   the :get-fn, :cas-fn, and :delete-fn keys."
  [{:keys [net local]}]
  (let [handle {:net net, :local local}]
    (assoc handle
           :get-fn (make-dht-get handle)
           :cas-fn (make-dht-cas handle)
           :delete-fn (make-dht-delete handle)
           :close-fn (fn [] (close-net! net) (jing/close! local)))))
