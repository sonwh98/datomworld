(ns dao.space.dht
  "Code indexes over the DHT, for any Clojure program: join a
   `dao.jing.dht` node, load a published covered index from its manifest
   address, and query it with `dao.space.query/q` (DHT epic slice S5;
   docs/design/dao.jing.dht.md section 10, \"The plain Clojure path\").
   Portable to the JVM, Node and Dart.  `yin.repl` is one consumer: its
   `dht:<dir>` store is a node joined here, and its `dao.space.dht` host
   module calls these same functions.

   A node is a value, advanced only by `step`, which its one owner calls
   with a nondecreasing millisecond reading it owns — the node's only
   time (section 5.3).  Nothing here waits:

   * `join` composes the node over a local `dao.jing` store.  With no
     peers it is solo — no socket, no secret, nothing sent.  With peers
     it binds one datagram socket through a `dao.stream.datagram` host
     seam, on loopback unless `:bind-host` names another address, and
     mints the node's root secret (32 bytes of the host CSPRNG, per
     join, held only in the node) and a random node id.  Publication is
     `:publish?`, never implied by peers (owner decision 1).
   * `store` is the node's `dao.jing` byte-store handle: a put inserts
     locally and appends one replicate request (`dao.jing.dht/
     store-handle`); a get reads the local store.  A publisher writes a
     covered index through it and calls `announce!` with the manifest, so
     `step` reports that publication acknowledged or not.
   * `load-index` starts loading a manifest: `step` walks the index it
     names through the local store and fetches each blob the store lacks
     with a `:jing/get` (`dao.jing.content.step`), until all four covered
     indexes read back whole and cover the manifest's count.
   * `db` and `q` read a loaded index through `dao.space.index/
     read-manifest` and `restored-indexes` (`dao.space.query/
     published-db`), locally.

   `step` answers `[node events]`, each event a plain map under
   `:dao.space.dht/event`: `:bound`, `:bind-failed`, `:published`
   (`:acknowledged?`, with `:peers` sent to, or the `:reason` and the
   `:peers` it reached), `:publication-unknown` (the facts were lost),
   `:loaded`, and `:load-failed`."
  (:require #?@(:cljd [["dart:math" :as math]
                       ["dart:typed_data" :as typed]
                       [dao.stream.datagram.dart :as datagram.host]]
                :cljs [[dao.stream.datagram.node :as datagram.host]]
                :clj [[dao.stream.datagram.jvm :as datagram.host]])
            [clojure.string :as str]
            [dao.jing :as jing]
            [dao.jing.content.step :as content.step]
            [dao.jing.dht :as dht]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.space.store :as durable]
            [dao.stream :as stream]
            [dao.stream.datagram :as datagram]
            [dao.stream.ringbuffer :as ringbuffer]))


;; =============================================================================
;; Options and defaults
;; =============================================================================

(def default-max-inbound-bytes
  "The default inbound storage bound (`:dao.jing.dht/max-inbound-bytes`):
   64 MiB of peers' `:store` payloads a publishing node accepts before it
   refuses them explicitly."
  (* 64 1024 1024))


(def defaults
  "`join`'s option defaults: solo, publishing off, loopback on an
   ephemeral port, the default inbound bound."
  {:peers []
   :publish? false
   :bind-host "127.0.0.1"
   :bind-port 0
   :max-inbound-bytes default-max-inbound-bytes})


(def secret-length
  "Bytes of the root secret minted per join: section 8's floor."
  32)


(def ring-capacity
  "Capacity of each ring the node is composed over: requests, answers,
   facts, ticks, traffic, and the publication ledger."
  4096)


(def step-budget
  "The DHT's per-stage budget per step."
  64)


(def fetch-budget
  "Answers the load client reads per step."
  16)


(defn default-bind
  "This build's datagram host seam (dao.stream.datagram.md section 3): the
   JVM's, Node's `dgram` (required only when a socket binds, so a browser
   build compiles), or Dart's."
  []
  #?(:cljd datagram.host/bind!
     :cljs datagram.host/bind!
     :clj datagram.host/bind!))


(defn secure-random-bytes
  "`n` bytes from the host's cryptographically secure generator."
  [n]
  #?(:cljd (let [r (math/Random.secure)]
             (typed/Uint8List.fromList
               (vec (repeatedly n #(.nextInt r 256)))))
     :clj (let [bs (byte-array n)]
            (.nextBytes (java.security.SecureRandom.) bs)
            bs)
     :cljs (.getRandomValues js/crypto (js/Uint8Array. n))))


(defn- option-refusal
  "Why `opts` cannot join, or nil."
  [{:keys [local dir peers publish? bind-host bind-port max-inbound-bytes
           bind!]}]
  (cond
    (not (or (and (map? local) (ifn? (:put-bytes-fn local)))
             (and (string? dir) (not (str/blank? dir)))))
    "a node needs a :local dao.jing store or a :dir"

    (not (and (sequential? peers)
              (every? #(and (map? %)
                            (datagram/ip-literal? (:host %))
                            (datagram/valid-port? (:port %)))
                      peers)))
    "peers must be {:host <IP literal> :port <1 to 65535>}"

    (not (boolean? publish?))
    ":publish? must be true or false"

    (not (datagram/ip-literal? bind-host))
    ":bind-host must be an IP literal"

    (not (and (integer? bind-port) (<= 0 bind-port 65535)))
    ":bind-port must be 0 (ephemeral) or 1 to 65535"

    (not (and (integer? max-inbound-bytes) (<= 0 max-inbound-bytes)))
    ":max-inbound-bytes must be a nonnegative integer"

    (and (seq peers) (not (fn? bind!)))
    "a node with peers needs a datagram host seam (:bind!)"))


;; =============================================================================
;; join
;; =============================================================================

(defn- ring
  []
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key ring-capacity})))


(defn- oldest
  [handle]
  (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest)))


(defn- local-store
  "The node's local store: the caller's `:local`, or the durable
   directory store `dao.space.store/open` opens exclusively at `:dir`."
  [{:keys [local dir]}]
  (or local (durable/open dir)))


(defn- node-over
  "The node composed from checked `opts` over the opened `local` store."
  [opts local]
  (let [{:keys [peers publish? bind-host bind-port max-inbound-bytes bind!]} opts
        [requests answers facts ticks traffic ledger] (repeatedly 6 ring)
        socket? (boolean (seq peers))
        composition (cond-> {:local local
                             :requests requests
                             :answers answers
                             :facts facts
                             :ticks ticks
                             ::dht/id (dht/bytes->hex
                                        (secure-random-bytes dht/id-length))
                             ::dht/publish? publish?
                             ::dht/bootstrap (vec peers)
                             ::dht/bind-host bind-host}
                      socket? (assoc :traffic traffic
                                     ::dht/secret (secure-random-bytes
                                                    secret-length)
                                     ::dht/max-inbound-bytes max-inbound-bytes))
        seam (when socket?
               (bind! {:identity (str "dao.space.dht:"
                                      (subs (::dht/id composition) 0 16))
                       :deposit traffic
                       :bind-host bind-host
                       :bind-port bind-port
                       :max-bytes (::dht/max-datagram dht/defaults)}))
        front (dht/store-handle
                {:local local
                 :requests requests
                 :max-message-bytes (::dht/max-message-bytes dht/defaults)})
        closed? (volatile! false)]
    {:peers (vec peers)
     :publish? publish?
     :composition composition
     :dht (when-not socket? (dht/state composition))
     :seam seam
     :traffic-cursor (when socket? (oldest traffic))
     :facts (oldest facts)
     :ledger ledger
     :ledger-cursor (oldest ledger)
     :origin nil
     :reading nil
     :window []
     :publications []
     :client (content.step/client-state requests answers (oldest answers))
     :loads {}
     :refusal nil
     :handle {:put-bytes-fn (fn [address bs]
                              (let [verdict ((:put-bytes-fn front) address bs)]
                                (stream/append! ledger {::put address})
                                verdict))
              :get-bytes-fn (:get-bytes-fn local)
              :close-fn (fn []
                          (when-not @closed?
                            (vreset! closed? true)
                            (try (when seam ((:close! seam)))
                                 (finally
                                   (jing/close! local)))))}}))


(defn join
  "Join the DHT from `opts` and answer the node:

   * `:local` — the node's own `dao.jing` store, already opened (and, if
     it is a directory, already locked) by the caller, as `yin.repl` does
     — or `:dir`, a directory `join` opens as the durable directory store
     (`dao.space.store/open`): exclusively, under the directory lock, so
     a second owner in this process or another is refused naming it, and
     the lock is held until `close!`;
   * `:peers` — bootstrap contacts `[{:host ip :port p} ...]`, none for
     solo;
   * `:publish?` — whether everything in the local store is public;
   * `:bind-host`, `:bind-port` — the socket's address, loopback and
     ephemeral by default;
   * `:max-inbound-bytes` — the inbound storage bound;
   * `:bind!` — the datagram host seam, `default-bind` by default.

   Refuses an invalid option with its reason.  The node owns `:local`
   from here: `close!` closes it."
  [opts]
  (let [opts (merge defaults {:bind! (default-bind)} opts)]
    (when-some [why (option-refusal opts)]
      (throw (ex-info (str "dao.space.dht/join: " why)
                      (dissoc opts :bind! :local))))
    (let [local (local-store opts)]
      (try
        (node-over opts local)
        (catch #?(:cljd Object :clj Throwable :cljs :default) e
          ;; a directory join opened: release it, lock included
          (when-not (:local opts) (jing/close! local))
          (throw e))))))


(defn store
  "The node's `dao.jing` byte-store handle: put inserts into the local
   store and asks the node to replicate, answering the local verdict at
   once; get reads the local store; close closes the socket and the
   local store."
  [node]
  (:handle node))


(defn local
  "The node's own local `dao.jing` store."
  [node]
  (get-in node [:composition :local]))


(defn node-id
  "The node's id: 64 lowercase hex characters."
  [node]
  (get-in node [:composition ::dht/id]))


(defn announce!
  "Declare that the blobs put through `store` since the last announcement
   are the publication whose manifest is `manifest-address`: `step`
   reports it `:published`, acknowledged or not."
  [node manifest-address]
  (stream/append! (:ledger node) {::manifest manifest-address})
  node)


(defn close!
  "Close the node's socket and its local store.  Idempotent."
  [node]
  (jing/close! (:handle node)))


;; =============================================================================
;; step: ticks, binding, the DHT
;; =============================================================================

(defn- tick
  [node now]
  (let [origin (or (:origin node) now)
        reading (max (or (:reading node) 0) (- now origin))]
    (stream/append! (get-in node [:composition :ticks])
                    {:dao.lease/event :dao.lease/tick
                     :dao.lease/reading reading})
    (assoc node :origin origin :reading reading)))


(defn- bind-step
  "While the socket binds, read its lifecycle facts: bound completes the
   composition with the writer over the address actually bound; a failed
   bind refuses the node."
  [node]
  (if (or (:dht node) (:refusal node))
    [node []]
    (let [traffic (get-in node [:composition :traffic])]
      (loop [cursor (:traffic-cursor node)]
        (let [next (stream/next traffic cursor)
              v (:dao.stream/value next)]
          (case (:dao.stream/outcome next)
            :dao.stream/ok
            (case (:dao.stream.datagram/event v)
              :dao.stream.datagram/bound
              (let [{host :dao.stream.datagram/host
                     port :dao.stream.datagram/port} (:dao.stream.datagram/local v)
                    descriptor {:dao.stream/type datagram/transport-type
                                :dao.stream/identity (:dao.stream.datagram/socket v)
                                :dao.stream.datagram/bind-host host
                                :dao.stream.datagram/bind-port port}
                    composition (assoc (:composition node)
                                       :datagrams
                                       (datagram/writer (:seam node) descriptor
                                                        (::dht/max-datagram
                                                          dht/defaults)))]
                [(assoc node
                        :composition composition
                        :dht (dht/state composition)
                        :traffic-cursor nil)
                 [{::event :bound :host host :port port :id (node-id node)}]])

              :dao.stream.datagram/bind-failed
              (let [reason (str (:dao.stream.datagram/reason v))]
                [(assoc node :refusal (str "the socket could not bind "
                                           (get-in node [:composition
                                                         ::dht/bind-host])
                                           ": " reason))
                 [{::event :bind-failed :reason reason}]])

              (recur (:dao.stream/cursor next)))

            :dao.stream/gap (recur (:dao.stream/cursor next))
            [(assoc node :traffic-cursor cursor) []]))))))


;; =============================================================================
;; Publications: the ledger, and the facts that answer it
;; =============================================================================

(defn- read-all
  "Every value on `handle` after `cursor`: `[values cursor' gap?]`."
  [handle cursor]
  (loop [cursor cursor
         acc []
         gap? false]
    (let [next (stream/next handle cursor)]
      (case (:dao.stream/outcome next)
        :dao.stream/ok (recur (:dao.stream/cursor next)
                              (conj acc (:dao.stream/value next))
                              gap?)
        :dao.stream/gap (recur (:dao.stream/cursor next) acc true)
        [acc cursor gap?]))))


(defn- open-publications
  "Close the ledger's windows into publications: the blobs put since the
   last announcement, up to the manifest announced."
  [node]
  (let [[entries cursor gap?] (read-all (:ledger node) (:ledger-cursor node))]
    (reduce (fn [node entry]
              (if-some [m (::manifest entry)]
                (let [addresses (distinct (conj (:window node) m))]
                  (-> node
                      (update :publications conj
                              {:manifest m
                               :waiting (set addresses)
                               :blobs (count addresses)
                               :sent {}
                               :refused {}})
                      (assoc :window [])))
                (update node :window conj (::put entry))))
            (cond-> (assoc node :ledger-cursor cursor)
              gap? (assoc :window []))
            entries)))


(defn- apply-fact
  [publication {::dht/keys [fact address peers reason]}]
  (if (contains? (:waiting publication) address)
    (case fact
      ::dht/sent (-> publication
                     (update :waiting disj address)
                     (assoc-in [:sent address] peers))
      ::dht/unacknowledged (-> publication
                               (update :waiting disj address)
                               (assoc-in [:refused address]
                                         {:reason reason :peers peers}))
      publication)
    publication))


(defn- publication-event
  [node {:keys [manifest blobs sent refused]}]
  (if (empty? refused)
    {::event :published :manifest manifest :blobs blobs
     :acknowledged? true :peers (apply min (vals sent))}
    (let [[_ {:keys [reason peers]}] (first (sort-by (comp str key) refused))]
      {::event :published :manifest manifest :blobs blobs
       :acknowledged? false :reason reason :peers peers
       :ack-peers (::dht/ack-peers (:dht node) (::dht/ack-peers dht/defaults))})))


(defn- settle-publications
  "Report every publication all of whose blobs the DHT has settled, in
   publication order.  A lost fact reports the publications it may have
   belonged to as unknown, never as acknowledged."
  [node]
  (let [[facts cursor gap?] (read-all (get-in node [:composition :facts])
                                      (:facts node))
        pubs (reduce (fn [pubs f] (mapv #(apply-fact % f) pubs))
                     (:publications node)
                     facts)]
    (if gap?
      [(assoc node :facts cursor :publications [])
       (mapv #(hash-map ::event :publication-unknown :manifest (:manifest %))
             pubs)]
      [(assoc node
              :facts cursor
              :publications (filterv #(seq (:waiting %)) pubs))
       (mapv #(publication-event node %)
             (filterv #(empty? (:waiting %)) pubs))])))


;; =============================================================================
;; Loading a published index
;; =============================================================================

(defn- probing
  "`handle` whose miss throws naming the missing address, so an index
   walk says what to fetch next."
  [handle]
  (let [absent #?(:cljd (Object.) :clj (Object.) :cljs (js-obj))]
    {:get-bytes-fn (fn [address _not-found]
                     (let [v ((:get-bytes-fn handle) address absent)]
                       (if (identical? absent v)
                         (throw (ex-info "missing" {::missing address}))
                         v)))
     :put-bytes-fn (fn [_ _] (throw (ex-info "read only" {})))
     :close-fn (fn [] nil)}))


(defn- missing-in
  [e]
  (loop [e e]
    (when e
      (or (get (ex-data e) ::missing)
          (recur (ex-cause e))))))


(defn index-datoms
  "The EAVT datoms of the covered index `manifest-address` names in
   `handle`, walked whole: `dao.space.index/read-manifest`, then every
   index root it names, each of which must cover exactly the manifest's
   `:count` datoms.  Throws on a missing blob or an invalid index."
  [handle manifest-address]
  (let [manifest (index/read-manifest handle manifest-address)
        walked (into {}
                     (map (fn [[k root]]
                            [k (vec (index/walk-index-datoms handle root))]))
                     (:indexes manifest))]
    (doseq [[k datoms] walked]
      (when-not (= (:count manifest) (count datoms))
        (throw (ex-info (str "the " (name k) " index covers " (count datoms)
                             " datoms, not the manifest's " (:count manifest))
                        {:index k :manifest manifest-address}))))
    (or (:eavt walked) [])))


(defn load-index
  "Start loading the published index `manifest-address` names into the
   node's local store; `step` advances it and reports `:loaded` or
   `:load-failed`.  A load already started, loaded or failed is left as
   it is.  Answers the node."
  [node manifest-address]
  (when-not (jing/segment-address? manifest-address)
    (throw (ex-info "dao.space.dht/load-index takes a manifest address"
                    {:manifest manifest-address})))
  (if (contains? (:loads node) manifest-address)
    node
    (assoc-in node [:loads manifest-address]
              {:status :loading :fetching nil :fetched 0})))


(defn load-status
  "`{:status :loading | :loaded | :failed ...}` for `manifest-address`, or
   nil when no load was started: `:loaded` carries `:datoms` (the count)
   and `:fetched` (blobs fetched from peers), `:failed` its `:reason`."
  [node manifest-address]
  (when-let [{:keys [status fetched datoms reason]}
             (get-in node [:loads manifest-address])]
    (cond-> {:status status :fetched fetched}
      (= :loaded status) (assoc :datoms (count datoms))
      (= :failed status) (assoc :reason reason))))


(defn loaded-datoms
  "The EAVT datoms of a loaded index, or nil."
  [node manifest-address]
  (let [record (get-in node [:loads manifest-address])]
    (when (= :loaded (:status record))
      (:datoms record))))


(defn- fail-load
  [node m reason]
  [(update-in node [:loads m] assoc :status :failed :reason reason :fetching nil)
   [{::event :load-failed :manifest m :reason reason}]])


(defn- advance-load
  [node m done-by-id]
  (let [{:keys [fetching], :as record} (get-in node [:loads m])
        done (when fetching (get done-by-id (:id fetching)))]
    (cond
      (and fetching (nil? done)) [node []]

      (and done (not (:found? done)))
      (fail-load node m (str "no peer produced " (:address fetching)
                             (when-let [why (or (:lost done)
                                                (get-in done [:error :code]))]
                               (str " (" why ")"))))

      :else
      (let [fetched (cond-> (:fetched record) done inc)
            node (update-in node [:loads m] assoc :fetching nil :fetched fetched)
            walked (try {:datoms (index-datoms (probing (local node)) m)}
                        (catch #?(:cljd Object :clj Throwable :cljs :default) e
                          (if-some [address (missing-in e)]
                            {:missing address}
                            {:error e})))]
        (cond
          (contains? walked :datoms)
          [(update-in node [:loads m] assoc :status :loaded :datoms (:datoms walked))
           [{::event :loaded :manifest m :datoms (count (:datoms walked))
             :fetched fetched}]]

          (:missing walked)
          (let [{:keys [outcome id], :as asked}
                (content.step/request-get (:client node) (:missing walked))]
            (if (#{:requested :pending-request} outcome)
              [(-> node
                   (assoc :client (:state asked))
                   (assoc-in [:loads m :fetching]
                             {:id id :address (:missing walked)}))
               []]
              (fail-load node m (str "could not ask for " (:missing walked)
                                     " (" (name outcome) ")"))))

          :else
          (fail-load node m (str "the index is invalid: "
                                 (or (ex-message (:error walked))
                                     (str (:error walked))))))))))


(defn- advance-loads
  [node]
  (let [loading (sort-by str (keep (fn [[m r]] (when (= :loading (:status r)) m))
                                   (:loads node)))]
    (if (or (empty? loading) (nil? (:dht node)))
      [node []]
      (let [{:keys [state completions]} (content.step/step (:client node)
                                                           fetch-budget)
            done-by-id (into {} (map (juxt :id identity)) completions)]
        (reduce (fn [[node events] m]
                  (let [[node more] (advance-load node m done-by-id)]
                    [node (into events more)]))
                [(assoc node :client state) []]
                loading)))))


;; =============================================================================
;; The public step and reads
;; =============================================================================

(defn step
  "Advance the node once at the owner's millisecond reading `now`:
   append it as a tick, finish binding, step the DHT, advance loads, and
   settle publications.  Answers `[node' events]`.  A refused node (its
   socket could not bind) is answered unchanged."
  [node now]
  (if (:refusal node)
    [node []]
    (let [node (tick node now)
          [node bound] (bind-step node)
          node (cond-> node (:dht node) (update :dht dht/step step-budget))
          [node loaded] (advance-loads node)
          node (open-publications node)
          [node settled] (settle-publications node)]
      [node (-> bound (into loaded) (into settled))])))


(defn refusal
  "Why the node stopped — its socket could not bind — or nil."
  [node]
  (:refusal node))


(defn busy?
  "True while the node owes its owner an event: binding, a load, or an
   unsettled publication."
  [node]
  (boolean (and (nil? (:refusal node))
                (or (nil? (:dht node))
                    (some #(= :loading (:status %)) (vals (:loads node)))
                    (seq (:window node))
                    (seq (:publications node))))))


(defn db
  "The `dao.space.query` value over the loaded index `manifest-address`
   names, read from the node's local store.  Throws unless it is loaded."
  [node manifest-address]
  (when-not (= :loaded (:status (load-status node manifest-address)))
    (throw (ex-info (str "the index " manifest-address " is not loaded")
                    {:manifest manifest-address
                     :status (load-status node manifest-address)})))
  (query/published-db (local node) manifest-address))


(defn q
  "`dao.space.query/q` of `query` over the current view of the loaded
   index `manifest-address` names, with `inputs` after it."
  [node manifest-address query & inputs]
  (query/collect
    (apply query/q query (query/current (db node manifest-address)) inputs)))
