(ns dao.space.dht
  "Code over the DHT, for any Clojure program: join a `dao.jing.dht`
   node, publish through its store, load content from a manifest address
   by any walk, and query a loaded covered index with `dao.space.query/q`
   (DHT epic slice S5, docs/design/dao.jing.dht.md section 10; linker
   slice L1, docs/design/yin.vm.linker.dht.md sections 4.3 and 5.5).
   Portable to the JVM, Node and Dart.  `yin.repl` is one consumer: its
   `dht:<dir>` store is a node joined here, and its `dao.space.dht` host
   module calls these same functions.  Nothing here knows `yin.*`: a
   load's walk is an argument.

   A node is a value, advanced only by `step`, which its one owner calls
   with a nondecreasing millisecond reading it owns, the node's only
   time.  Nothing here waits:

   * `join` composes the node over a local `dao.jing` store.  With no
     peers it is solo: no socket, no secret, nothing sent.  With peers
     it binds one datagram socket through a `dao.stream.datagram` host
     seam, on loopback unless `:bind-host` names another address, and
     mints the node's root secret (32 bytes of the host CSPRNG, per
     join, held only in the node) and a random node id.  Publication is
     `:publish?`, never implied by peers.
   * `store` is the node's `dao.jing` byte-store handle: a put inserts
     locally, records the address on the node's ledger ring and answers
     the local verdict.  It asks the DHT for nothing: `step` admits each
     put to a bounded backlog and releases replicate requests below the
     DHT's pending-write bound, so the node's own writes never draw
     `/busy`.  `announce!` closes the puts since the last announcement
     into one publication, whose ledger `step` reports once every blob
     has an outcome (`:published`), and again whenever its result
     changes or its automatic repair ends (`:republished`).
   * `load` starts loading content from an address with a walk over the
     local store; `step` fetches each address the walk answers
     `:missing` with a `:jing/get` (`dao.jing.content.step`), one at a
     time, until the walk answers `:complete` or `:invalid`.
     `load-index` is `load` with the covered-index walk.  Failure
     reasons are data.  An address is loaded under one kind: a load
     under another is refused.  `forget` clears a terminal record and
     `abandon` a loading one.
   * `db` and `q` read a loaded index through `dao.space.index/
     read-manifest` and `restored-indexes` (`dao.space.query/
     published-db`), locally.

   `step` answers `[node events]`, each event a plain map under
   `:dao.space.dht/event`: `:bound`, `:bind-failed`, `:published`,
   `:republished`, `:publication-unknown` (the facts were lost),
   `:loaded`, and `:load-failed`.

   The publication ledger, the backlog and repair are process state:
   `close!` discards them, and a node joined again repairs nothing from
   before.  Every blob stays local."
  (:refer-clojure :exclude [load])
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


(def max-pending-writes
  "Replicate requests the node holds outstanding at most: the DHT's own
   pending-write bound, so the node never draws `/busy`."
  (::dht/max-pending-writes dht/defaults))


(def defaults
  "`join`'s option defaults: solo, publishing off, loopback on an
   ephemeral port, the default inbound bound, and the backlog and repair
   bounds of yin.vm.linker.dht.md section 11."
  {:peers []
   :publish? false
   :bind-host "127.0.0.1"
   :bind-port 0
   :max-inbound-bytes default-max-inbound-bytes
   :max-backlog 4096
   :repair-batch 64
   :max-open 16
   :repair-slots 16
   :repair-ticks 30000
   :repair-max-ticks 600000
   :max-repairing 16})


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


(def retryable
  "Failure reasons repair retries (yin.vm.linker.dht.md 5.5.4).  Every
   other reason is terminal."
  #{::dht/too-few-peers ::dht/busy ::backlog-full ::publications-full})


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


(defn- refused
  "A refused call: the error, returned for the caller to throw.  Its data
   names the closed `code` under `:dao.space.dht/refused`."
  [code message data]
  (ex-info (str "dao.space.dht: " message) (assoc data ::refused code)))


(defn- count-option?
  [n]
  (and (integer? n) (pos? n)))


(defn- option-refusal
  "Why `opts` cannot join, or nil."
  [{:keys [local dir peers publish? bind-host bind-port max-inbound-bytes
           bind! max-backlog repair-batch max-open repair-slots repair-ticks
           repair-max-ticks max-repairing]}]
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

    (not (every? count-option? [max-backlog repair-batch max-open
                                max-repairing]))
    (str
      ":max-backlog, :repair-batch, :max-open"
      " and :max-repairing must be positive integers")

    (not (and (count-option? repair-slots) (< repair-slots max-pending-writes)))
    (str ":repair-slots must be a positive integer below " max-pending-writes)

    (not (and (integer? repair-ticks) (<= 0 repair-ticks)
              (integer? repair-max-ticks) (<= repair-ticks repair-max-ticks)))
    (str
      ":repair-ticks must be a nonnegative integer"
      " no greater than :repair-max-ticks")

    (and (seq peers) (not (fn? bind!)))
    "a node with peers needs a datagram host seam (:bind!)"))


;; =============================================================================
;; join
;; =============================================================================

(defn- ring
  ([] (ring ring-capacity))
  ([capacity]
   (:dao.stream/handle
     (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                          ringbuffer/capacity-key capacity}))))


(defn- oldest
  [handle]
  (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest)))


(defn- local-store
  "The node's local store: the caller's `:local`, or the durable
   directory store `dao.space.store/open` opens exclusively at `:dir`."
  [{:keys [local dir]}]
  (or local (durable/open dir)))


(defn- new-ledger
  "An empty publication ledger: every address in order, its entry, and
   the counts its result reads."
  []
  {:order []
   :entries {}
   :waiting 0
   :sent 0
   :retryable 0
   :min-peers nil})


(defn- node-over
  "The node composed from checked `opts` over the opened `local` store."
  [opts local]
  (let [{:keys [peers publish? bind-host bind-port max-inbound-bytes bind!]}
        opts
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
        ;; the DHT handle's put checks (address, oversize) over :local; its
        ;; own replicate request goes to a sink, since the node's step
        ;; owns every request (yin.vm.linker.dht.md 5.5)
        front (dht/store-handle
                {:local local
                 :requests (ring 1)
                 :max-message-bytes (::dht/max-message-bytes dht/defaults)})
        closed? (volatile! false)]
    {:peers (vec peers)
     :publish? publish?
     :limits (select-keys opts [:max-backlog :repair-batch :max-open
                                :repair-slots :repair-ticks :repair-max-ticks
                                :max-repairing])
     :composition composition
     :dht (when-not socket? (dht/state composition))
     :seam seam
     :traffic-cursor (when socket? (oldest traffic))
     :facts (oldest facts)
     :ledger ledger
     :ledger-cursor (oldest ledger)
     :origin nil
     :reading nil
     :window (new-ledger)
     :window-lost? false
     :pubs []
     :next-id 0
     :fresh []
     :repair []
     :queued {}
     :repair-owner {}
     :inflight {}
     :misses {}
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

   * `:local`: the node's own `dao.jing` store, already opened (and, if
     it is a directory, already locked) by the caller, as `yin.repl` does;
     or `:dir`, a directory `join` opens as the durable directory store
     (`dao.space.store/open`): exclusively, under the directory lock, so
     a second owner in this process or another is refused naming it, and
     the lock is held until `close!`;
   * `:peers`: bootstrap contacts `[{:host ip :port p} ...]`, none for
     solo;
   * `:publish?`: whether everything in the local store is public;
   * `:bind-host`, `:bind-port`: the socket's address, loopback and
     ephemeral by default;
   * `:max-inbound-bytes`: the inbound storage bound;
   * `:bind!`: the datagram host seam, `default-bind` by default;
   * `:max-backlog`, `:repair-batch`, `:max-open`, `:repair-slots`,
     `:repair-ticks`, `:repair-max-ticks`, `:max-repairing`: the
     backlog and repair bounds (`defaults`).

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
   store and records the address for the node's next publication,
   answering the local verdict at once; get reads the local store; close
   closes the socket and the local store."
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


(defn ack-peers
  "The distinct peers a blob must be handed to before it is `sent`."
  [node]
  (::dht/ack-peers (or (:dht node) dht/defaults)))


(defn announce!
  "Declare that the blobs put through `store` since the last announcement
   are the publication whose manifest is `manifest-address`: `step`
   reports it `:published` once every blob has an outcome."
  [node manifest-address]
  (stream/append! (:ledger node) {::manifest manifest-address})
  node)


(defn close!
  "Close the node's socket and its local store, discarding its ledgers
   and queues unreported.  Idempotent; answers nil."
  [node]
  (jing/close! (:handle node))
  nil)


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
                     port :dao.stream.datagram/port}
                    (:dao.stream.datagram/local v)
                    descriptor {:dao.stream/type datagram/transport-type
                                :dao.stream/identity
                                (:dao.stream.datagram/socket v)
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


;; =============================================================================
;; The ledger: entries and the counts a result reads (5.5.1)
;; =============================================================================

(defn- retryable?
  [entry]
  (and (= :failed (:state entry)) (contains? retryable (:reason entry))))


(defn- tally
  [ledger entry sign]
  (cond-> ledger
    (= :waiting (:state entry)) (update :waiting + sign)
    (= :sent (:state entry)) (update :sent + sign)
    (retryable? entry) (update :retryable + sign)))


(defn- put-entry
  "`ledger` with `address`'s entry replaced by `entry`, its counts kept."
  [ledger address entry]
  (let [old (get-in ledger [:entries address])]
    (-> (cond-> ledger
          (nil? old) (update :order conj address)
          old (tally old -1))
        (tally entry 1)
        (assoc-in [:entries address] entry)
        (cond-> (= :sent (:state entry))
          (-> (update :min-peers #(if % (min % (:peers entry)) (:peers entry)))
              (cond-> (:cycle ledger) (assoc-in [:cycle :sent?] true)))))))


(defn- open-entry?
  "Whether an entry still takes an outcome: waiting, or failed."
  [entry]
  (contains? #{:waiting :failed} (:state entry)))


(defn- result
  [pub]
  (cond
    (= (:sent pub) (:blobs pub)) :acknowledged
    (= :sent (get-in pub [:entries (:manifest pub) :state])) :partial
    :else :unacknowledged))


(defn- outstanding?
  [node address]
  (when-let [request (get-in node [:inflight address])]
    (not (:settled? request))))


(defn- attempting?
  "Whether `address` has a queue entry or an outstanding request."
  [node address]
  (or (contains? (:queued node) address) (outstanding? node address)))


(defn- needed?
  "Whether the window or a live publication other than `except` (an id)
   still takes an outcome for `address`."
  [node address except]
  (or (open-entry? (get-in node [:window :entries address]))
      (some #(and (not= except (:id %))
                  (not (:cancelled? %))
                  (open-entry? (get-in % [:entries address])))
            (:pubs node))))


(defn- dequeue
  "`node` without `address`'s queue entry."
  [node address]
  (case (get-in node [:queued address])
    :fresh (-> node
               (update :queued dissoc address)
               (update :fresh (fn [q] (filterv #(not= address %) q))))
    :repair (-> node
                (update :queued dissoc address)
                (update :repair-owner dissoc address)
                (update :repair (fn [q] (filterv #(not= address %) q))))
    node))


(defn- release-entries
  "Remove every queue entry of `addresses` no other live holder needs."
  [node addresses except]
  (reduce (fn [node a]
            (if (and (contains? (:queued node) a) (not (needed? node a except)))
              (dequeue node a)
              node))
          node
          addresses))


(defn- write-outcome
  "Write one request's outcome, `entry`, to the window and to every live
   publication holding `address` waiting or failed (5.5.1)."
  [node address entry]
  (-> node
      (update :window (fn [w]
                        (if (open-entry? (get-in w [:entries address]))
                          (put-entry w address entry)
                          w)))
      (update :pubs (fn [pubs]
                      (mapv (fn [p]
                              (if (and (not (:cancelled? p))
                                       (open-entry? (get-in p [:entries
                                                               address])))
                                (put-entry p address entry)
                                p))
                            pubs)))))


;; =============================================================================
;; Admission (5.5.2)
;; =============================================================================

(defn- limit
  [node k]
  (get-in node [:limits k]))


(defn- admit
  "Admit `address` to the window: its entry, and a fresh queue entry
   when nothing attempts it yet."
  [node address]
  (let [failed (fn [reason] {:state :failed :reason reason :peers 0})]
    (cond
      (contains? (get-in node [:window :entries]) address) node

      (empty? (:peers node))
      (update node :window put-entry address (failed ::dht/solo))

      (not (:publish? node))
      (update node :window put-entry address (failed ::dht/unpublished))

      (attempting? node address)
      (update node :window put-entry address {:state :waiting})

      (>= (count (:fresh node)) (limit node :max-backlog))
      (update node :window put-entry address (failed ::backlog-full))

      :else
      (-> node
          (update :window put-entry address {:state :waiting})
          (update :fresh conj address)
          (assoc-in [:queued address] :fresh)))))


(defn- open-count
  [node]
  (count (remove :reported? (:pubs node))))


(defn- announce
  "Close the window into the publication of `manifest`.  Beyond
   `:max-open` publications awaiting a first report, every blob not
   already sent is failed `:publications-full` at once."
  [node manifest]
  (let [node (admit node manifest)
        window (:window node)
        overflow? (>= (open-count node) (limit node :max-open))
        pub (merge window
                   {:id (:next-id node)
                    :manifest manifest
                    :blobs (count (:order window))
                    :reported? false
                    :last nil
                    :ended nil
                    :cancelled? false
                    :delay (limit node :repair-ticks)
                    :due nil
                    :retry? false
                    :cycle nil
                    :cycles 0})
        pub (if overflow?
              (reduce (fn [p a]
                        (let [e (get-in p [:entries a])]
                          (if (= :sent (:state e))
                            p
                            (put-entry p a {:state :failed
                                            :reason ::publications-full
                                            :peers (or (:peers e) 0)}))))
                      pub
                      (:order pub))
              pub)
        node (-> node
                 (assoc :window (new-ledger))
                 (update :next-id inc)
                 (update :pubs conj pub))]
    (cond-> node
      overflow? (release-entries (:order pub) (:id pub)))))


(defn- admit-ledger
  "Read the ledger ring: admit each put to the window and close it at each
   announcement.  A lost entry loses its window: that publication is
   reported unknown and is not live."
  [node]
  (let [ledger (:ledger node)]
    (loop [node node
           cursor (:ledger-cursor node)
           events []]
      (let [next (stream/next ledger cursor)
            v (:dao.stream/value next)]
        (case (:dao.stream/outcome next)
          :dao.stream/ok
          (let [cursor (:dao.stream/cursor next)]
            (cond
              (and (::manifest v) (:window-lost? node))
              (recur (assoc node :window (new-ledger) :window-lost? false)
                     cursor
                     (conj events {::event :publication-unknown
                                   :manifest (::manifest v)}))

              (::manifest v) (recur (announce node (::manifest v)) cursor
                                    events)
              (:window-lost? node) (recur node cursor events)
              :else (recur (admit node (::put v)) cursor events)))

          :dao.stream/gap
          (let [lost (:order (:window node))]
            (recur (-> node
                       (assoc :window (new-ledger) :window-lost? true)
                       (release-entries lost nil))
                   (:dao.stream/cursor next)
                   events))

          [(assoc node :ledger-cursor cursor) events])))))


;; =============================================================================
;; Repair admission (5.5.4): before fresh admission, oldest first
;; =============================================================================

(defn- repairing?
  [pub]
  (and (:reported? pub) (not (:cancelled? pub)) (nil? (:ended pub))
       (not= :acknowledged (result pub))))


(defn- held-in-repair
  [node id]
  (count (filter #(= id %) (vals (:repair-owner node)))))


(defn- open-cycle
  [node pub]
  (if
    (and (repairing? pub) (nil? (:cycle pub))
         (>= (:reading node) (:due pub)))
    (assoc
      pub
      :cycle {:offered (vec
                         (sort-by
                           str (filter
                                 #(retryable?
                                    (get-in
                                      pub
                                      [:entries %]))
                                 (:order pub))))
              :next 0
              :sent? false}
      :cycles (inc (:cycles pub)))
    pub))


(defn- fill-batch
  "Offer the publication's open cycle to the repair queue until it holds
   `:repair-batch` addresses there."
  [node i]
  (let [{:keys [id cycle] :as pub} (get-in node [:pubs i])
        offered (:offered cycle)
        batch (limit node :repair-batch)]
    (loop [node node
           k (:next cycle)
           held (held-in-repair node id)]
      (if (or (nil? cycle) (>= k (count offered)) (>= held batch))
        (cond-> node cycle (assoc-in [:pubs i :cycle :next] k))
        (let [a (nth offered k)]
          (cond
            (= :sent (get-in pub [:entries a :state])) (recur node (inc k) held)
            (attempting? node a) (recur node (inc k) held)
            :else (recur (-> node
                             (update :repair conj a)
                             (assoc-in [:queued a] :repair)
                             (assoc-in [:repair-owner a] id))
                         (inc k)
                         (inc held))))))))


(defn- admit-repairs
  [node]
  (reduce (fn [node i]
            (-> node
                (update-in [:pubs i] #(open-cycle node %))
                (fill-batch i)))
          node
          (range (count (:pubs node)))))


;; =============================================================================
;; Release (5.5.2): below the DHT's bound, repair slots reserved
;; =============================================================================

(defn- take-issuable
  "Up to `n` addresses of `queue`, in order, with no request in flight."
  [node queue n]
  (loop [q queue
         acc []]
    (if (or (empty? q) (>= (count acc) n))
      acc
      (let [a (first q)]
        (recur (rest q)
               (if (contains? (:inflight node) a) acc (conj acc a)))))))


(defn- issue
  [node kind addresses]
  (if (empty? addresses)
    node
    (let [requests (get-in node [:composition :requests])
          issued (set addresses)]
      (doseq [a addresses]
        (stream/append! requests {::dht/replicate a}))
      (-> (reduce (fn [node a]
                    (-> node
                        (update :queued dissoc a)
                        (update :repair-owner dissoc a)
                        (assoc-in [:inflight a] {:kind kind :settled? false})))
                  node
                  addresses)
          (update kind (fn [q] (filterv #(not (contains? issued %)) q)))))))


(defn- in-flight
  [node kind]
  (count (filter #(= kind (:kind %)) (vals (:inflight node)))))


(defn- release
  [node]
  (if (nil? (:dht node))
    node
    (let [slots (limit node :repair-slots)
          room #(- max-pending-writes (count (:inflight %)))
          node (issue node :repair
                      (take-issuable node (:repair node)
                                     (min (room node)
                                          (- slots (in-flight node :repair)))))
          fresh-bound (if (seq (:repair node))
                        (- max-pending-writes slots)
                        max-pending-writes)]
      (issue node :fresh
             (take-issuable node (:fresh node)
                            (min (room node)
                                 (- fresh-bound (in-flight node :fresh))))))))


;; =============================================================================
;; Facts (5.5.1): outcomes, written to every holder
;; =============================================================================

(defn- fetching
  [node]
  (set (keep #(get-in % [:fetching :address]) (vals (:loads node)))))


(defn- apply-fact
  [node {::dht/keys [fact address peers reason]} wanted]
  (case fact
    ::dht/sent
    (if (outstanding? node address)
      (-> node
          (assoc-in [:inflight address :settled?] true)
          (write-outcome address {:state :sent :peers peers}))
      node)

    ::dht/unacknowledged
    (if (outstanding? node address)
      (-> node
          (update :inflight dissoc address)
          (write-outcome address {:state :failed :reason reason :peers peers}))
      node)

    ::dht/replicated
    (update node :inflight dissoc address)

    ::dht/miss
    (if (contains? wanted address)
      (assoc-in node [:misses address] reason)
      node)

    node))


(defn- read-facts
  "Apply every fact the DHT appended.  A lost fact reports every live
   publication unknown, never acknowledged, and ends it."
  [node]
  (let [[facts cursor gap?] (read-all (get-in node [:composition :facts])
                                      (:facts node))
        wanted (fetching node)
        node (reduce #(apply-fact %1 %2 wanted) (assoc node :facts cursor)
                     facts)]
    (if gap?
      [(assoc node :pubs [] :fresh [] :repair [] :queued {} :repair-owner {}
              :inflight {})
       (mapv #(hash-map ::event :publication-unknown :manifest (:manifest %))
             (:pubs node))]
      [node []])))


;; =============================================================================
;; Reports (5.5.3): the first report, every change, and the ends
;; =============================================================================

(defn- report
  [kind pub]
  (let [r (result pub)
        failed (->> (:order pub)
                    (keep (fn [a]
                            (let [e (get-in pub [:entries a])]
                              (when-not (= :sent (:state e))
                                (cond-> {:address a :reason (:reason e)
                                         :peers (or (:peers e) 0)}
                                  (:was e) (assoc :was (:was e)))))))
                    (sort-by (comp str :address))
                    vec)]
    (cond-> {::event kind
             :manifest (:manifest pub)
             :result r
             :blobs (:blobs pub)
             :sent (:sent pub)
             :failed failed
             :repairing? (and (not= :acknowledged r) (nil? (:ended pub)))}
      (pos? (:sent pub)) (assoc :peers (:min-peers pub))
      (:ended pub) (assoc :ended (:ended pub)))))


(defn- close-cycle
  [node pub]
  (let [{:keys [offered next sent?] :as cycle} (:cycle pub)]
    (if (and cycle
             (>= next (count offered))
             (not-any? #(attempting? node %) offered))
      (let [delay (if sent?
                    (limit node :repair-ticks)
                    (min (* 2 (:delay pub)) (limit node :repair-max-ticks)))]
        (assoc pub
               :cycle nil
               :delay delay
               :due (if (:retry? pub) (:reading node) (+ (:reading node) delay))
               :retry? false))
      pub)))


(defn- ending
  [pub]
  (or (:ended pub)
      (when (and (not= :acknowledged (result pub)) (zero? (:retryable pub)))
        ::terminal)))


(defn- evaluate
  "One publication's report this step, if any: `[pub' event-or-nil
   retire?]`."
  [node pub]
  (cond
    (:cancelled? pub)
    (let [pub (assoc pub :ended ::cancelled)]
      [pub (report (if (:reported? pub) :republished :published) pub) true])

    (not (:reported? pub))
    (if (pos? (:waiting pub))
      [pub nil false]
      (let [pub (assoc pub :ended (ending pub))
            pub (assoc pub
                       :reported? true
                       :last [(result pub) (:ended pub)]
                       :due (+ (:reading node) (:delay pub)))]
        [pub (report :published pub)
         (or (= :acknowledged (result pub)) (some? (:ended pub)))]))

    :else
    (let [pub (close-cycle node pub)
          pub (assoc pub :ended (ending pub))
          now [(result pub) (:ended pub)]]
      (if (= now (:last pub))
        [pub nil false]
        [(assoc pub :last now) (report :republished pub)
         (or (= :acknowledged (result pub)) (some? (:ended pub)))]))))


(defn- retire
  "`node` without the publication `pub`; its queue entries no other live
   holder needs are dropped."
  [node pub]
  (-> node
      (update :pubs (fn [pubs] (filterv #(not= (:id pub) (:id %)) pubs)))
      (release-entries (:order pub) (:id pub))))


(defn- displace
  "Beyond `:max-repairing`, retire the oldest repairing publications."
  [node]
  (loop [node node
         events []]
    (let [repairing (filter repairing? (:pubs node))]
      (if (<= (count repairing) (limit node :max-repairing))
        [node events]
        (let [oldest (assoc (first repairing) :ended ::displaced)]
          (recur (retire node oldest)
                 (conj events (report :republished oldest))))))))


(defn- settle-publications
  [node]
  (let [[node events retired]
        (reduce (fn [[node events retired] i]
                  (let [[pub event retire?] (evaluate node (get-in node [:pubs
                                                                         i]))]
                    [(assoc-in node [:pubs i] pub)
                     (cond-> events event (conj event))
                     (cond-> retired retire? (conj pub))]))
                [node [] []]
                (range (count (:pubs node))))
        node (reduce retire node retired)
        [node displaced] (displace node)]
    [node (into events displaced)]))


;; =============================================================================
;; retry! and cancel! (5.5.4, 5.5.5)
;; =============================================================================

(defn- update-pubs
  [node manifest f]
  (update node :pubs (fn [pubs]
                       (mapv #(if (= manifest (:manifest %)) (f %) %)
                             pubs))))


(defn retry!
  "Make the next repair cycle of the publication `manifest-address` due
   now, its delay reset.  Reports nothing itself.  Refused
   (`:dao.space.dht/not-repairing`) unless it is repairing.  Answers the
   node."
  [node manifest-address]
  (when-not (some #(and (= manifest-address (:manifest %)) (repairing? %))
                  (:pubs node))
    (throw (refused ::not-repairing
                    (str "no publication of " manifest-address " is repairing")
                    {:manifest manifest-address})))
  (update-pubs node manifest-address
               (fn [pub]
                 (if (repairing? pub)
                   (cond-> (assoc pub :delay (limit node :repair-ticks))
                     (:cycle pub) (assoc :retry? true)
                     (nil? (:cycle pub)) (assoc :due (or (:reading node) 0)))
                   pub))))


(defn- cancel-entry
  [entry]
  (case (:state entry)
    :sent entry
    :failed (assoc entry :reason ::cancelled :was (:reason entry))
    {:state :failed :reason ::cancelled :peers 0}))


(defn cancel!
  "End the live publication `manifest-address` now: every entry not sent
   is failed `:dao.space.dht/cancelled`, its queue entries no other live
   publication needs are dropped, and the next step reports it with
   `:ended :dao.space.dht/cancelled`.  Refused (`:dao.space.dht/not-live`)
   for a manifest with no live publication.  Answers the node."
  [node manifest-address]
  (let
    [live (filterv
            #(and
               (= manifest-address (:manifest %)) (not
                                                    (:cancelled? %)))
            (:pubs node))]
    (when (empty? live)
      (throw (refused ::not-live
                      (str "no publication of " manifest-address " is live")
                      {:manifest manifest-address})))
    (let
      [node (update-pubs
              node manifest-address
              (fn [pub]
                (if
                  (:cancelled? pub)
                  pub
                  (->
                    (reduce
                      (fn [p a]
                        (put-entry
                          p a (cancel-entry
                                (get-in
                                  p
                                  [:entries a]))))
                      pub
                      (:order pub))
                    (assoc :cancelled? true :cycle nil)))))]
      (reduce (fn [node pub] (release-entries node (:order pub) (:id pub)))
              node
              live))))


;; =============================================================================
;; Loads (4.3): a walk over the local store, one fetch at a time
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


(defn index-walk
  "The covered-index walk of `manifest-address` (4.3): `:complete` with
   its EAVT datoms, `:missing` naming the first absent blob, or
   `:invalid` with the code `:index-invalid`."
  [manifest-address]
  (fn [handle]
    (try {::walk :complete :value (index-datoms (probing handle)
                                                manifest-address)}
         (catch #?(:cljd Object :clj Throwable :cljs :default) e
           (if-some [address (missing-in e)]
             {::walk :missing :address address}
             {::walk :invalid :address manifest-address
              :defect {:code :index-invalid
                       :text (or (ex-message e) (str e))}})))))


(defn load
  "Start loading `address` with `{:kind k :walk f}`: `f` is `(fn [handle]
   outcome)` over the node's local store, answering
   `{:dao.space.dht/walk :missing :address a}`, `{... :complete :value v}`
   or `{... :invalid :address a-or-nil :defect {:code c ...}}`.  `step`
   fetches each missing address and walks again, and reports `:loaded`
   or `:load-failed` once.  A load of the same kind already started,
   loaded or failed is left as it is; one recorded under another kind is
   refused (`:dao.space.dht/kind-conflict`).  Answers the node."
  [node address {:keys [kind walk]}]
  (when-not (jing/segment-address? address)
    (throw (ex-info "dao.space.dht/load takes a segment address"
                    {:address address})))
  (when-not (ifn? walk)
    (throw (ex-info "dao.space.dht/load takes a :walk function" {:kind kind})))
  (if-some [record (get-in node [:loads address])]
    (if (= kind (:kind record))
      node
      (throw (refused ::kind-conflict
                      (str address " is recorded as a " (pr-str (:kind record))
                           " load, not a " (pr-str kind) " load")
                      {:address address :kind kind :recorded (:kind record)})))
    (assoc-in node [:loads address]
              {:status :loading :kind kind :walk walk :fetching nil :fetched
               0})))


(def index-kind
  "The `:kind` of a covered-index load."
  ::index)


(defn load-index
  "`load` of the published index `manifest-address` names, with the
   covered-index walk: its `:loaded` value is the index's EAVT datoms."
  [node manifest-address]
  (when-not (jing/segment-address? manifest-address)
    (throw (ex-info "dao.space.dht/load-index takes a manifest address"
                    {:manifest manifest-address})))
  (load node manifest-address {:kind index-kind
                               :walk (index-walk manifest-address)}))


(defn load-status
  "`address`'s load, or nil when none was started:
   `{:status :loading :kind k :fetched n :fetching address-or-nil}`,
   `{:status :loaded :kind k :fetched n :value v}` or
   `{:status :failed :kind k :fetched n :reason reason}`."
  [node address]
  (when-let [{:keys [status kind fetched fetching value reason]}
             (get-in node [:loads address])]
    (case status
      :loading {:status status :kind kind :fetched fetched
                :fetching (:address fetching)}
      :loaded {:status status :kind kind :fetched fetched :value value}
      :failed {:status status :kind kind :fetched fetched :reason reason})))


(defn forget
  "`node` without `address`'s terminal load record, so a new `load`
   starts over.  Refused (`:dao.space.dht/loading`) while it loads."
  [node address]
  (when (= :loading (get-in node [:loads address :status]))
    (throw (refused ::loading (str address " is still loading")
                    {:address address})))
  (update node :loads dissoc address))


(defn abandon
  "`node` without `address`'s `:loading` record, reporting nothing.  Its
   interest in the fetch client is retired: the retained unsent request,
   when it is this record's, by `dao.jing.content.step/abandon`; its
   outstanding request otherwise, by `dao.jing.content.step/retire`, so
   a late answer is unsolicited and dropped.  Another load fetching the
   same address has its own request and is untouched.  Refused
   (`:dao.space.dht/not-loading`) for a record that is not loading."
  [node address]
  (let [record (get-in node [:loads address])]
    (when-not (= :loading (:status record))
      (throw (refused ::not-loading (str address " is not loading")
                      {:address address
                       :status (:status record)})))
    (let [id (get-in record [:fetching :id])
          client (:client node)
          client (cond
                   (nil? id) client
                   (= id (get-in client [:unsent :id])) (content.step/abandon
                                                          client)
                   :else (content.step/retire client id))]
      (-> node
          (assoc :client client)
          (update :loads dissoc address)))))


(defn loaded-datoms
  "The EAVT datoms of a loaded index, or nil."
  [node manifest-address]
  (let [record (get-in node [:loads manifest-address])]
    (when (and (= :loaded (:status record)) (= index-kind (:kind record)))
      (:value record))))


(defn loaded-indexes
  "The covered-index manifests whose load is `:loaded` on `node`, as a
   vector sorted by address text: a load of another kind, one still
   loading and a failed one are not listed."
  [node]
  (vec (sort-by str (filter #(loaded-datoms node %) (keys (:loads node))))))


(defn- walk-outcome
  "The walk's answer, held to the three shapes: a throw is `walk-threw`,
   any other shape `walk-shape`."
  [walk handle]
  (let [v (try (walk handle)
               (catch #?(:cljd Object :clj Throwable :cljs :default) e
                 {::walk :invalid :address nil
                  :defect {:code ::walk-threw :text (or (ex-message e) (str
                                                                         e))}}))
        defect (when (map? v) (:defect v))]
    (if (and (map? v)
             (case (::walk v)
               :missing (jing/segment-address? (:address v))
               :complete (contains? v :value)
               :invalid (and (map? defect) (keyword? (:code defect))
                             (or (nil? (:address v)) (jing/segment-address?
                                                       (:address v))))
               false))
      v
      {::walk :invalid :address nil :defect {:code ::walk-shape}})))


(defn- fail-load
  [node m reason]
  (let [{:keys [kind]} (get-in node [:loads m])]
    [(update-in node [:loads m] #(-> % (assoc :status :failed :reason reason
                                              :fetching nil)
                                     (dissoc :walk)))
     [{::event :load-failed :manifest m :kind kind :reason reason}]]))


(defn- advance-load
  [node m done-by-id]
  (let [{:keys [fetching kind walk], :as record} (get-in node [:loads m])
        done (when fetching (get done-by-id (:id fetching)))]
    (cond
      (and fetching (nil? done)) [node []]

      ;; the miss cause stays: another load may wait on the same address;
      ;; advance-loads drops it once none does
      (and done (not (:found? done)))
      (let [a (:address fetching)]
        (fail-load node m {::failure :miss :address a
                           :cause (get-in node [:misses a] ::dht/gap)}))

      :else
      (let [fetched (cond-> (:fetched record) done inc)
            node (update-in node [:loads m] assoc :fetching nil :fetched
                            fetched)
            walked (walk-outcome walk (local node))]
        (case (::walk walked)
          :complete
          [(update-in node [:loads m] #(-> % (assoc :status :loaded :value
                                                    (:value walked))
                                           (dissoc :walk)))
           [(cond-> {::event :loaded :manifest m :kind kind :fetched fetched}
              (= index-kind kind) (assoc :datoms (count (:value walked))))]]

          :invalid
          (fail-load node m {::failure :invalid :address (:address walked)
                             :defect (:defect walked)})

          :missing
          (let [a (:address walked)
                {:keys [outcome id], :as asked} (content.step/request-get
                                                  (:client node) a)]
            (case outcome
              (:requested :pending-request)
              [(-> node
                   (assoc :client (:state asked))
                   (assoc-in [:loads m :fetching] {:id id :address a}))
               []]

              ;; the client still owes a request: ask again next step
              :busy [(assoc node :client (:state asked)) []]

              (fail-load (assoc node :client (:state asked)) m
                         {::failure :unaskable :address a :outcome
                          outcome}))))))))


(defn- client-holds?
  "Whether the load client holds an unsent request, an outstanding id or
   an undelivered completion: what an abandoned load leaves to drain."
  [client]
  (boolean (or (:unsent client)
               (seq (:outstanding client))
               (seq (:completed client)))))


(defn- advance-loads
  [node]
  (let [loading (sort-by str (keep (fn [[m r]]
                                     (when (= :loading (:status r))
                                       m))
                                   (:loads node)))]
    ;; the client is stepped while it holds anything, so what an abandon
    ;; leaves drains with no load active
    (if (or (and (empty? loading) (not (client-holds? (:client node))))
            (nil? (:dht node)))
      [node []]
      (let [{:keys [state completions]} (content.step/step (:client node)
                                                           fetch-budget)
            done-by-id (into {} (map (juxt :id identity)) completions)
            [node events] (reduce (fn [[node events] m]
                                    (let [[node more] (advance-load node m
                                                                    done-by-id)]
                                      [node (into events more)]))
                                  [(assoc node :client state) []]
                                  loading)]
        ;; every load waiting on an address has read its miss cause: keep
        ;; only the causes of addresses still being fetched
        [(update node :misses select-keys (fetching node)) events]))))


;; =============================================================================
;; The public step and reads
;; =============================================================================

(defn step
  "Advance the node once at the owner's millisecond reading `now`:
   append it as a tick; finish binding; admit repairs, then the puts and
   announcements on the ledger ring; release replicate requests; step
   the DHT; apply its facts; advance loads; and report publications.
   Answers `[node' events]`.  A refused node (its socket could not bind)
   is answered unchanged."
  [node now]
  (if (:refusal node)
    [node []]
    (let [node (tick node now)
          [node bound] (bind-step node)
          node (admit-repairs node)
          [node unknown] (admit-ledger node)
          node (release node)
          node (cond-> node (:dht node) (update :dht dht/step step-budget))
          [node lost] (read-facts node)
          [node loaded] (advance-loads node)
          [node reported] (settle-publications node)]
      [node (-> bound (into unknown) (into lost) (into loaded) (into
                                                                 reported))])))


(defn refusal
  "Why the node stopped (its socket could not bind), or nil."
  [node]
  (:refusal node))


(defn backlog
  "What the node retains now: queue entries (`:fresh`, `:repair`),
   requests outstanding (`:outstanding`, split `:outstanding-fresh` and
   `:outstanding-repair`), live publications (`:live`), those awaiting a
   first report (`:open`) and repairing (`:repairing`), and the ledger
   entries they hold (`:ledger-entries`)."
  [node]
  {:fresh (count (:fresh node))
   :repair (count (:repair node))
   :outstanding (count (:inflight node))
   :outstanding-fresh (in-flight node :fresh)
   :outstanding-repair (in-flight node :repair)
   :live (count (:pubs node))
   :open (open-count node)
   :repairing (count (filter repairing? (:pubs node)))
   :ledger-entries (reduce + 0 (map :blobs (:pubs node)))})


(defn- summary
  [node pub]
  {:manifest (:manifest pub)
   :blobs (:blobs pub)
   :sent (:sent pub)
   :result (result pub)
   :reported? (:reported? pub)
   :repairing? (repairing? pub)
   :delay (:delay pub)
   :due (:due pub)
   :cycles (:cycles pub)
   :cycle-open? (some? (:cycle pub))
   :repair-queued (held-in-repair node (:id pub))
   :entries (:entries pub)})


(defn publications
  "Every live publication, oldest first, as data: its result, counts,
   repair state and ledger entries."
  [node]
  (mapv #(summary node %) (:pubs node)))


(defn publication
  "The oldest live publication of `manifest-address`, as `publications`
   answers it, or nil."
  [node manifest-address]
  (some #(when (= manifest-address (:manifest %)) (summary node %)) (:pubs
                                                                      node)))


(defn busy?
  "True while the node owes its owner an event or holds work: binding, a
   load, unread puts, a first report owed, or an address queued or
   outstanding.  A publication waiting for its next repair cycle does
   not make the node busy."
  [node]
  (boolean
    (and (nil? (:refusal node))
         (or (nil? (:dht node))
             (some #(= :loading (:status %)) (vals (:loads node)))
             (= :dao.stream/ok (:dao.stream/outcome
                                 (stream/next (:ledger node) (:ledger-cursor
                                                               node))))
             (seq (:order (:window node)))
             (some #(or (not (:reported? %)) (:cancelled? %)) (:pubs node))
             (seq (:fresh node))
             (seq (:repair node))
             (seq (:inflight node))))))


(defn db
  "The `dao.space.query` value over the loaded index `manifest-address`
   names, read from the node's local store.  Throws unless it is loaded."
  [node manifest-address]
  (when-not (loaded-datoms node manifest-address)
    (throw (ex-info (str "the index " manifest-address " is not loaded")
                    {:manifest manifest-address
                     :status (dissoc (load-status node manifest-address)
                                     :value)})))
  (query/published-db (local node) manifest-address))


(defn q
  "`dao.space.query/q` of `query` over the current view of the loaded
   index `manifest-address` names, with `inputs` after it."
  [node manifest-address query & inputs]
  (query/collect
    (apply query/q query (query/current (db node manifest-address)) inputs)))
