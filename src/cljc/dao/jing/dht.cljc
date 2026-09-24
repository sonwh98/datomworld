(ns dao.jing.dht
  "DHT content-store backend for the dao.jing storage boundary
   (docs/design/dao.jing.md).

   create-content-dht wraps an IDhtNet transport and a local content handle
   (e.g. dao.jing.mem/create-content-mem) into a content-store handle map:

     {:net net, :local local, :closed-atom a,
      :put-bytes-fn f, :get-bytes-fn g, :close-fn c}

   consumed by dao.jing/materialize!, dao.jing/get, and dao.jing/close!.

   Put validates the address-payload pair, writes the local backend, then
   best-effort replicates to the k nearest non-self peers. Get reads the
   local backend first and falls back to the grid, verifying every fetched
   payload against its requested content address and caching verified values
   through the local backend.

   There are no roots, no CAS records, no deletes, and no intake streams
   here: the DHT routes segment content addresses (:segment/<algo>-...),
   derives its routing target solely from the content digest, and never
   records which stream or peer carried a payload."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.dht.kad :as kad]))


;; =============================================================================
;; Routing identity
;; =============================================================================

(defn node-id
  "Deterministic node id: SHA-256 of host:port."
  [host port]
  (jing/sha256 (str host ":" port)))


(defn- content-target
  "The routing target for a strict segment content address: its
   content digest. Non-segment addresses are outside the DHT and throw; there
   is no root class, so nothing is ever hashed by key name."
  [address]
  (when-not (jing/segment-address? address)
    (throw (ex-info "dao.jing.dht: not a segment content address"
                    {:address address})))
  (jing/segment-hash address))


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

  (store-content!
    [net peer address payload]
    "Ask peer to hold content address under payload. Best effort; returns a
     boolean acknowledgement (false or nil when the peer is unreachable or
     refuses).
     Implementations must own bounded transport timeouts and return false/nil
     when unreachable.")

  (fetch-content
    [net peer address]
    "Ask peer for the content stored at address. Returns
     {:found? bool :value v} when reachable, or nil when unreachable.
     Implementations must own bounded transport timeouts and return nil
     when unreachable.")

  (close-net!
    [net]
    "Release the transport's local resources (socket, threads)."))


(def ^:private content-missing
  "Internal not-found sentinel for local read-backs, never exposed. An
   opaque per-host identity object, never a keyword: a keyword sentinel
   could be confused with a genuinely stored payload."
  #?(:cljd (Object.)
     :clj (Object.)
     :cljs (js-obj)))


;; =============================================================================
;; Iterative lookup
;; =============================================================================

(def ^:private alpha
  "Lookup concurrency width."
  3)


(defn lookup
  "Iteratively converge on the kad/k known non-self peers nearest target-id. Queried
   peer ids are deduplicated, so a peer is never asked twice in the same
   lookup even when different routes return it with different metadata."
  [net target-id]
  (let [self (self-peer net)]
    (loop [discovered (into {}
                            (comp (remove #(= (:id self) (:id %)))
                                  (map (juxt :id identity)))
                            (known-peers net target-id kad/k))
           queried-ids #{(:id self)}]
      (let [closest-k (->> (vals discovered)
                           (sort-by #(kad/distance (:id %) target-id))
                           (take kad/k))
            candidates (->> closest-k
                            (remove #(contains? queried-ids (:id %)))
                            (take alpha))]
        (if (empty? candidates)
          (vec closest-k)
          (recur (into discovered
                       (comp (mapcat #(find-closer net % target-id))
                             (remove #(= (:id self) (:id %)))
                             (map (juxt :id identity)))
                       candidates)
                 (into queried-ids (map :id) candidates)))))))


;; =============================================================================
;; Content-store effects
;; =============================================================================

#_{:clj-kondo/ignore [:unused-binding]}


(defn- with-lock
  "Run f under lock on hosts with a monitor primitive; direct call elsewhere."
  [lock f]
  #?(:clj (locking lock (f))
     :default (f)))


(defn- ensure-open
  [{:keys [closed-atom]}]
  (when @closed-atom
    (throw (ex-info "dao.jing.dht: DHT content store is closed"
                    {:closed true}))))


(defn- validate-address-bytes!
  "Reject a put before any local or network action: the address must be a
   valid segment content address whose digest is the hash of the bytes."
  [address bs]
  (when-not (jing/segment-address? address)
    (throw (ex-info "dao.jing.dht: not a segment content address"
                    {:address address})))
  (when-not (jing/segment-bytes-match? address bs)
    (throw (ex-info "dao.jing.dht: content address does not match the bytes"
                    {:address address}))))


(defn- accept-bytes
  "The canonical bytes of the Base64 text a peer answered for address, or
   nil when the text, the digest, or the payload's canonicality fails: a
   peer is untrusted, so this is the one ingress check before caching."
  [address b64]
  (try (let [bs (jing/base64->bytes b64)]
         (when (jing/segment-bytes-match? address bs)
           (cbor/decode bs)
           bs))
       (catch #?(:clj Throwable
                 :cljs :default
                 :cljd Object)
              _
         nil)))


(defn- safe-store-content!
  [net peer address payload]
  (try (store-content! net peer address payload)
       (catch #?(:clj Throwable
                 :cljs :default
                 :cljd Object)
              _
         false)))


(defn- safe-fetch-content
  [net peer address]
  (try (fetch-content net peer address)
       (catch #?(:clj Throwable
                 :cljs :default
                 :cljd Object)
              _
         nil)))


(defn- make-put
  [{:keys [net local], :as handle}]
  (fn [address bs]
    (validate-address-bytes! address bs)
    (ensure-open handle)
    (let [result ((:put-bytes-fn local) address bs)]
      (when-not (#{:inserted :present} result)
        (throw (ex-info "dao.jing.dht: invalid local put result"
                        {:result result, :address address})))
      (let [self-id (:id (self-peer net))
            b64 (jing/bytes->base64 bs)
            peers (remove #(= self-id (:id %))
                          (lookup net (content-target address)))]
        #?(:clj (run! deref
                      (mapv (fn [peer]
                              (future
                                (safe-store-content! net peer address b64)))
                            peers))
           :default (run! (fn [peer]
                            (safe-store-content! net peer address b64))
                          peers)))
      result)))


(defn- make-get
  [{:keys [net local], :as handle}]
  (fn [address not-found]
    (ensure-open handle)
    (let [v ((:get-bytes-fn local) address content-missing)]
      (if (not (identical? v content-missing))
        v
        (let [self-id (:id (self-peer net))
              fetched
              (some (fn [peer]
                      (when (not= self-id (:id peer))
                        (when-let [res (safe-fetch-content net peer address)]
                          (when (:found? res)
                            (when-let [bs (accept-bytes address (:value res))]
                              [bs])))))
                    (lookup net (content-target address)))]
          (if fetched
            (let [bs (first fetched)
                  result ((:put-bytes-fn local) address bs)]
              (when-not (#{:inserted :present} result)
                (throw
                  (ex-info
                    "dao.jing.dht: fetched content could not be cached"
                    {:address address, :result result})))
              bs)
            not-found))))))


(defn- make-close
  [{:keys [net local closed-atom]}]
  (fn []
    (with-lock closed-atom
      (fn []
        (when-not @closed-atom
          (let [err-net (try (close-net! net)
                             nil
                             (catch #?(:clj Throwable
                                       :cljs :default
                                       :cljd Object)
                                    e
                               e))
                err-local (try (jing/close! local)
                               nil
                               (catch #?(:clj Throwable
                                         :cljs :default
                                         :cljd Object)
                                      e
                                 e))]
            (if (or err-net err-local)
              (throw (or err-net err-local))
              (reset! closed-atom true))))
        nil))))


;; =============================================================================
;; Handle constructor
;; =============================================================================

(defn create-content-dht
  "Wrap an IDhtNet transport and a local content handle as a DHT
   content-store handle.

   The returned handle is plain data:
     {:net net, :local local, :closed-atom a,
      :put-bytes-fn f, :get-bytes-fn g, :close-fn c}

   and works with dao.jing/materialize!, dao.jing/get, and dao.jing/close!.
   :net is an IDhtNet transport; :local is a content handle carrying
   :put-bytes-fn and :get-bytes-fn (dao.jing.mem/create-content-mem or
   equivalent); :closed-atom is the store's explicit private state and close
   lock.

   The DHT routes only registered :segment/<algorithm>-... content addresses
   and records no source identity: there are no roots, CAS records, deletes,
   or intake streams."
  [{:keys [net local]}]
  (when-not (and net local)
    (throw (ex-info "dao.jing.dht requires :net and :local"
                    {:net net, :local local})))
  (when-not (fn? (:put-bytes-fn local))
    (throw (ex-info "dao.jing.dht local requires :put-bytes-fn"
                    {:local local})))
  (when-not (fn? (:get-bytes-fn local))
    (throw (ex-info "dao.jing.dht local requires :get-bytes-fn"
                    {:local local})))
  (let [closed-atom (atom false)
        handle {:net net, :local local, :closed-atom closed-atom}]
    (assoc handle
           :put-bytes-fn (make-put handle)
           :get-bytes-fn (make-get handle)
           :close-fn (make-close handle))))
