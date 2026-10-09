(ns dao.stream.remote-meet
  "The meeting board convention of dao.stream.remote.md (section 4):
   none of this has a frame -- M is peers using sections 2 and 3, a
   request and response service (section 5) over two ordinary table
   entries.

   M serves `:meet-requests`, surface `#{:writer}`, and `:meet-board`,
   surface `#{:reader}`. A peer appends `{:meet/here <name>}` over a
   channel whose attachment identity is its own reflexive address
   (UDP) or a stand-in the composition supplies for any other channel;
   M's interpreter answers with a `:meet/seen` posting on the board
   naming the address it observed, never one a payload claimed. A
   peer appends `{:meet/pair <name>}` to ask for a relay pair; M
   creates two ring buffers, enters them in its table under a lease
   (docs/design/dao.lease.md: the two identities are the subject, the
   requesting peer the holder), and posts their descriptors, from the
   asker's own side, on the board -- the asked-for peer mirrors `in`
   and `out` to build its own pair descriptor over the same two
   buffers (`dao.stream.remote-pair`).

   `reflexive-tag` and `bound-gate` are the two middlewares
   `:meet-requests`' table entry wraps: the first stamps every
   append! with the channel attachment identity it arrived on, under
   `:meet/from`, before the raw handle ever sees it; the second
   refuses an append! past the bound with `:dao.stream/refused`, a
   present answer, never silence (section 4, Bounded meeting work).
   `meeting` composes M's own state; `step!` drains one bounded pass
   of `:meet-requests` and answers on `:meet-board`, publishing the
   bound's own latest decision for the gate to read. Capacity is
   enforced a second time at grant time -- requests queued against
   one published count cannot exceed it, each excess refused on the
   board, a present answer the asker reads through its own
   reflection -- and one pass handles at most `:fanout` requests, the
   cursor preserved so the remainder waits for later passes. A grant
   rides the judge: the complete grant fact is carried to the holder
   on the board beside the pair descriptors, and the holder's
   renewal medium enters the judge as a fact medium of its own, so
   renewal is a convention path, never an injection. `reclaim-fn` is
   the idempotent reclaim procedure a composition hands
   `dao.lease/make-judge` for the relay-pair subject this namespace
   grants."
  (:require [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.middleware :as middleware]))


;; =============================================================================
;; The two middlewares meet-requests wraps
;; =============================================================================

(defn reflexive-tag
  "Middleware: an append! to meet-requests is stamped, before the raw
   handle ever sees it, with `:meet/from` naming the channel
   attachment identity it arrived on -- for UDP the reflexive address,
   for any other channel whatever attachment identity the composition
   wires. A payload's own claim about its address is never trusted:
   the mirror step's own channel context is the source of this key,
   never the value the asker sent."
  []
  {:dao.stream.middleware/in
   (fn [ctx req]
     (if (= :dao.stream/append! (:dao.stream.remote/op req))
       (update req :dao.stream.remote/args
               (fn [[v]]
                 [(if (map? v)
                    (assoc v :meet/from (:dao.stream.remote/channel ctx))
                    v)]))
       req))
   :dao.stream.middleware/out
   (fn [_ctx _req outcome] outcome)})


(defn bound-gate
  "Middleware: `dao.stream.middleware/gate` reading `decision`, a
   capacity-1 medium `step!` publishes `{:meet/active-pairs n
   :meet/max-pairs m}` onto every pass. An append! is refused,
   present and never silent, once `n` has reached `m` -- the fanout
   and active-pair bound of section 4's Bounded meeting work. A read
   is never gated: the board's own retention is the only bound on
   reading it."
  [decision]
  (middleware/gate
    {:dao.stream.middleware/decision decision
     :dao.stream.middleware/verify
     (fn [d _ctx req]
       (when (and (= :dao.stream/append! (:dao.stream.remote/op req))
                  (map? (first (:dao.stream.remote/args req)))
                  (contains? (first (:dao.stream.remote/args req)) :meet/pair)
                  (number? (:meet/max-pairs d))
                  (>= (:meet/active-pairs d 0) (:meet/max-pairs d)))
         :dao.stream.remote-meet/past-bound))}))


(defn entries
  "M's own table entries for the meeting board: `:meet-requests`,
   `#{:writer}`, wrapped with `reflexive-tag` then `bound-gate`
   (outermost first, so the tag runs before the gate reads the tagged
   request -- the gate's own verify never needs the tag, but a future
   verify might, and the position rule lets either order run; this
   one keeps the wire-visible transform first); `:meet-board`,
   `#{:reader}`, unwrapped. Returns `{:meet-requests entry :meet-board
   entry}` for a table this namespace's caller merges with whatever
   else it serves."
  [{:keys [requests board decision]}]
  {:meet-requests {:handle (middleware/wrap
                             requests
                             [(reflexive-tag) (bound-gate decision)])
                   :surface #{:writer}}
   :meet-board {:handle board :surface #{:reader}}})


;; =============================================================================
;; M's own state and interpreter
;; =============================================================================

(def default-fanout
  "The per-step fanout bound of section 4's Bounded meeting work: how
   many requests one `step!` pass handles, a gap resume counted like
   a handled request, before the remainder is left queued for later
   passes. `meeting` takes `:fanout` to configure its own."
  16)


(defn- bounded-medium
  [h encoded-size limit]
  (if-not encoded-size h
          (reify
            stream/IDaoStreamDescriptor
            (descriptor [_] (stream/descriptor h))


            stream/IDaoStreamReader

            (cursor [_ anchor] (stream/cursor h anchor))

            (next [_ c] (stream/next h c))


            stream/IDaoStreamWriter

            (append!
              [_ v]
              (try
                (if (> (encoded-size v) limit)
                  {:dao.stream/outcome :dao.stream/invalid-value}
                  (stream/append! h v))
                (catch #?(:cljd dynamic :clj Throwable :cljs :default) _error
                  {:dao.stream/outcome :dao.stream/invalid-value})))


            stream/IDaoStreamClosable

            (close! [_] (stream/close! h)))))


(defn meeting
  "M's own state for the meeting board's interpreter (never the
   mirror's own table entries, which read and write the same raw
   `requests`/`board`/`table` handles by the composition's separate
   wiring through `entries`). `capacity` sizes every relay ring buffer
   this namespace creates; `max-pairs` is the bound `bound-gate`
   enforces once `step!` has published it, and which grant time
   enforces again against the meeting's own live count; `fanout` is
   the per-step request bound of section 4's Bounded meeting work
   (`default-fanout` when not configured); `judge-atom` is the
   composition's own `dao.lease/make-judge` state, `duration` the
   lease this namespace grants every relay pair; `ring!` is `(fn
   [capacity] -> handle)`, the composition's own ring buffer
   constructor (`dao.stream.ringbuffer/create!`'s own handle, or a
   test double)."
  [{:keys [requests board table capacity max-pairs decision judge-atom
           duration ring! self fanout advertised-channel incarnation max-grants
           encoded-size fact-bytes value-bytes lapses]}]
  (when-not (and (some? incarnation) (stream/valid-descriptor? advertised-channel)
                 (not= :dao.stream.remote-meet/served (:dao.stream/type advertised-channel)))
    (throw (ex-info "meeting requires fresh incarnation and advertised channel"
                    {:incarnation incarnation :advertised-channel advertised-channel})))
  (when-not (every? #(and (integer? %) (pos? %))
                    [capacity (or max-pairs 32) (or fanout default-fanout) (or max-grants 1024)
                     (or fact-bytes 16384) (or value-bytes 65536)])
    (throw (ex-info "invalid meeting bounds" {:capacity capacity :max-pairs max-pairs
                                              :fanout fanout :max-grants max-grants})))
  (when-not (and (lease/duration? duration) (or (nil? encoded-size) (fn? encoded-size)))
    (throw (ex-info "invalid meeting duration or codec measurement" {:refused :profile})))
  (atom {:requests (bounded-medium requests encoded-size (or fact-bytes 16384))
         :requests-cursor (:dao.stream/cursor
                            (stream/cursor requests stream/anchor-oldest))
         :board (bounded-medium board encoded-size (or fact-bytes 16384))
         :table table
         :capacity capacity
         :max-pairs max-pairs
         :active-pairs 0
         :decision decision
         :judge-atom judge-atom
         :duration duration
         :ring! ring!
         :encoded-size encoded-size :value-bytes (or value-bytes 65536)
         :self self
         :fanout (or fanout default-fanout)
         :advertised-channel advertised-channel
         :incarnation incarnation
         :max-grants (or max-grants 1024)
         :grants 0
         :correlations {}
         :pending-publications {}
         :pending-unwire []
         :diagnostics []
         :now nil
         :lapses lapses
         :renewals {}
         :next-id 0}))


(defn active-pairs
  "The meeting's own count of live relay pairs -- reclaim-fn's own
   bookkeeping, read here for tests and diagnostics."
  [m]
  (:active-pairs @m))


(defn- mint-id!
  [m prefix]
  (let [n (:next-id @m)]
    (swap! m update :next-id inc)
    (str (:incarnation @m) "-" prefix "-" n)))


(defn reclaim-fn
  "Close owned relay/renewal rings once and queue post-judge unwiring.
   The driver calls cleanup! after retaining the returned judge state."
  [m]
  (fn [[in-id out-id]]
    (let [{:keys [table renewals]} @m
          renewal-id (get renewals in-id)
          owned (select-keys @table [in-id out-id renewal-id])]
      (when (seq owned)
        (doseq [[_ {:keys [handle]}] owned]
          (when (stream/closable? handle) (stream/close! handle)))
        (when-some [h (:handle (get owned renewal-id))]
          (swap! m update :pending-unwire conj h))
        (swap! table dissoc in-id out-id renewal-id)
        (swap! m (fn [state]
                   (-> state
                       (update :active-pairs dec)
                       (update :renewals dissoc in-id)
                       (update :pending-publications
                               (fn [records]
                                 (into {} (remove (fn [[_ record]]
                                                    (= in-id (get-in record [:post :dao.stream.remote/in :dao.stream/identity])))
                                                  records))))
                       (update :correlations
                               (fn [records]
                                 (into {} (remove (fn [[_ post]]
                                                    (= in-id (get-in post [:dao.stream.remote/in :dao.stream/identity])))
                                                  records))))))))
      true)))


(defn cleanup!
  "Apply queued unwiring after the judge's returned state is installed."
  [m]
  (let [{:keys [judge-atom pending-unwire]} @m]
    (swap! judge-atom #(reduce lease/unwire-facts % pending-unwire))
    (swap! m assoc :pending-unwire []))
  m)


(defn snapshot
  "Current explicit table snapshot for the driver's channel update seam."
  [m]
  @(:table @m))


(defn- remote-descriptor
  [channel identity]
  {:dao.stream/type :dao.stream/remote
   :dao.stream/identity identity :dao.stream/channel channel})


(defn- grant-pair!
  "Create one relay pair: two fresh ring buffers, entered in the
   table under a fresh id pair, both surfaces declared #{:reader
   :writer} -- the two holders tell the buffers apart by which
   descriptor names which `in`/`out`, not by a surface difference at
   M, which runs nothing but mirror steps over them and never reads
   the values inside. A third entry, one more ring buffer with
   surface #{:writer}, is the holder's renewal medium
   (dao.stream.remote.md, section 6): wired into the judge here as a
   fact medium of its own, attributed to the holder, so a renewal the
   holder appends through its reflection is evidence the judge
   counts, and removed by `reclaim-fn` together with the subject.
   Grants a lease over the pair's own two identities as one subject,
   the requesting peer the holder -- the complete grant fact returned
   here for carriage to the holder on the board. Returns the asker's
   own pair descriptor's two remote descriptors, the renewal
   medium's, and the grant."
  [m holder]
  (let [{:keys [table capacity ring! judge-atom duration advertised-channel encoded-size value-bytes]} @m
        pair-id (mint-id! m "pair")
        in-id (str pair-id "-in")
        out-id (str pair-id "-out")
        renewal-id (str pair-id "-renewal")
        acquired (atom [])
        original-judge @judge-atom]
    (when-not (contains? @m :base-fact-count)
      (swap! m assoc :base-fact-count (count (:facts original-judge))))
    (try
      (doseq [id [in-id out-id renewal-id]]
        (let [h (ring! capacity)]
          (when-not (and (stream/reader? h) (stream/writer? h) (stream/closable? h))
            (throw (ex-info "invalid owned relay medium" {:identity id})))
          (swap! acquired conj (bounded-medium h encoded-size value-bytes))))
      (swap! table assoc
             in-id {:handle (nth @acquired 0) :surface #{:reader :writer} :dao.lease/lease pair-id}
             out-id {:handle (nth @acquired 1) :surface #{:reader :writer} :dao.lease/lease pair-id}
             renewal-id {:handle (nth @acquired 2) :surface #{:writer} :dao.lease/lease pair-id})
      (let [renewal-handle (:handle (get @table renewal-id))
            grant (lease/grant pair-id [in-id out-id] holder duration)]
        (swap! judge-atom lease/wire-declared-facts
               {:handle renewal-handle
                :cursor (:dao.stream/cursor (stream/cursor renewal-handle stream/anchor-oldest))
                :source holder
                :medium {:retention :evict-oldest :capacity capacity
                         :value-domain :portable-values :attribution :per-author-media}})
        (swap! judge-atom lease/author-grant grant)
        (swap! m assoc-in [:renewals in-id] renewal-id)
        (swap! m update :active-pairs inc)
        (swap! m update :grants inc)
        {:pair-id pair-id
         :grant grant
         :in (remote-descriptor advertised-channel in-id)
         :out (remote-descriptor advertised-channel out-id)
         :renewal (remote-descriptor advertised-channel renewal-id)})
      (catch #?(:cljd dynamic :clj Throwable :cljs :default) error
        (doseq [h @acquired] (stream/close! h))
        (swap! table dissoc in-id out-id renewal-id)
        (reset! judge-atom original-judge)
        (throw error)))))


(defn- diagnostic!
  [m reason]
  (swap! m update :diagnostics #(vec (take-last 64 (conj % {:reason reason})))))


(defn- publish!
  [m key post]
  (let [r (stream/append! (:board @m) post)]
    (when (= :dao.stream/full (:dao.stream/outcome r))
      (swap! m update :pending-publications
             #(if (contains? % key) % (assoc % key {:post post :since (:now @m)}))))))


(defn- handle-request!
  [m v]
  (let [operations (when (map? v) (filter #(contains? v %) [:meet/here :meet/pair :meet/ready]))
        correlation (when (map? v) (select-keys v [:meet/request :meet/peer :meet/incarnation]))]
    (if-not (and (= 1 (count operations))
                 (some? (get v (first operations)))
                 (or (not (contains? v :meet/request))
                     (and (some? (:meet/request v)) (some? (:meet/incarnation v)))))
      (diagnostic! m :malformed)
      (case (first operations)
        :meet/here
        (stream/append! (:board @m)
                        (merge correlation {:meet/seen (:meet/here v)
                                            :meet/reflexive (:meet/from v)}
                               (when-some [channel (:meet/channel v)] {:meet/channel channel})))
        :meet/ready
        (if (and (contains? #{:in :out} (:meet/side v))
                 (some? (:meet/request v)) (some? (:meet/incarnation v)))
          (stream/append! (:board @m) v)
          (diagnostic! m :malformed-ready))
        :meet/pair
        (let [key [(:meet/from v) (:meet/incarnation v) (:meet/request v)]
              correlated? (some? (:meet/request v))
              existing (when correlated? (get-in @m [:correlations key]))
              {:keys [active-pairs max-pairs grants max-grants]} @m]
          (cond
            existing (publish! m (:dao.lease/lease existing) existing)
            (or (>= grants max-grants)
                (and (number? max-pairs) (>= active-pairs max-pairs)))
            (stream/append! (:board @m)
                            (merge correlation {:meet/pair-for (:meet/pair v)
                                                :meet/asker (:meet/from v)
                                                :meet/refused :dao.stream.remote-meet/past-bound}))
            :else
            (let [{:keys [pair-id grant in out renewal]}
                  (grant-pair! m (or (:meet/from v) (:meet/pair v)))
                  post (merge correlation
                              {:meet/pair-for (:meet/pair v) :meet/asker (:meet/from v)
                               :meet/grantor (:self @m) :meet/grantor-incarnation (:incarnation @m)
                               :dao.lease/lease pair-id :dao.lease/grant grant
                               :dao.stream.remote/in in :dao.stream.remote/out out
                               :dao.stream.remote/renewal renewal})]
              (when correlated? (swap! m assoc-in [:correlations key] post))
              (publish! m pair-id post))))))))


(defn- observe-lapses!
  [m]
  (when (:lapses @m)
    (loop [remaining (:fanout @m)]
      (when (pos? remaining)
        (let [{:keys [handle cursor]} (:lapses @m)
              r (stream/next handle cursor)]
          (case (:dao.stream/outcome r)
            :dao.stream/ok
            (do (swap! m assoc-in [:lapses :cursor] (:dao.stream/cursor r))
                (let [fact (:dao.stream/value r)]
                  (when (= :dao.lease/lapsed (:dao.lease/status fact))
                    (publish! m [:lapse (:dao.lease/lease fact)]
                              (assoc fact :meet/lapsed (:dao.lease/lease fact)
                                     :meet/grantor (:self @m)
                                     :meet/grantor-incarnation (:incarnation @m)))))
                (recur (dec remaining)))
            :dao.stream/gap
            (do (swap! m assoc-in [:lapses :cursor] (:dao.stream/cursor r))
                (diagnostic! m :lapse-observation-gap)
                (recur (dec remaining)))
            nil))))))


(defn step!
  "One pass of M's interpreter: publish the bound's current decision
   for `bound-gate` to read next, then drain `:meet-requests`,
   answering each `:meet/here` or `:meet/pair` fact on `:meet-board`;
   a gap adopts the reader's own recovery cursor, the lost asks lost,
   a resume costing one unit of the fanout budget like a handled
   request. At most `:fanout` requests are handled in one pass --
   section 4's bounded meeting work -- and the cursor is preserved
   after each, so the remainder waits for later passes. Returns `m`."
  ([m] (step! m (:now @m)))
  ([m now]
   (swap! m assoc :now now)
   (cleanup! m)
   (observe-lapses! m)
   (doseq [[key {:keys [post since]}] (:pending-publications @m)]
     (let [expired? (and now since (>= (- now since) 30000))
           r (when-not expired? (stream/append! (:board @m) post))]
       (when (or expired? (not= :dao.stream/full (:dao.stream/outcome r)))
         (swap! m update :pending-publications dissoc key))))
   (let [{:keys [active-pairs max-pairs decision]} @m]
     (stream/append! decision {:meet/active-pairs active-pairs
                               :meet/max-pairs max-pairs}))
   (loop [budget (:fanout @m)]
     (when (pos? budget)
       (let [cursor (:requests-cursor @m)
             r (stream/next (:requests @m) cursor)]
         (case (:dao.stream/outcome r)
           :dao.stream/ok
           (do (swap! m assoc :requests-cursor (:dao.stream/cursor r))
               (try (handle-request! m (:dao.stream/value r))
                    (catch #?(:cljd dynamic :clj Throwable :cljs :default) _error
                      (diagnostic! m :allocation-or-input)))
               (recur (dec budget)))

           :dao.stream/gap
           (do (swap! m assoc :requests-cursor (:dao.stream/cursor r))
               (recur (dec budget)))

           nil))))
   m))


(defn judge-step!
  "Step the explicitly wired judge, retain its result, then unwind reclaimed
   renewal readers. The driver deposits lease ticks before calling this."
  [m]
  (swap! (:judge-atom @m) lease/judge-step)
  (cleanup! m))


(defn rotate!
  "Install a caller-assembled fresh epoch only when old leases, output and
   renewal readers are quiescent. Historical judge state is never pruned.
   A long-lived lease keeps admissions refused at the epoch cap."
  [m incarnation judge]
  (let [state @m old @(:judge-atom state)]
    (if-not (and (some? incarnation) (not= incarnation (:incarnation state))
                 (zero? (:active-pairs state)) (empty? (:renewals state))
                 (empty? (:pending-unwire state)) (empty? (:pending-publications state))
                 (empty? (:queue old)) (empty? (:ledger old))
                 (= (count (:facts old)) (:base-fact-count state))
                 (map? judge) (set? (:resolver-bindings judge))
                 (empty? (:queue judge)) (empty? (:ledger judge)))
      {:dao.stream/outcome :dao.stream/refused :reason ::epoch-live}
      (do (reset! (:judge-atom state) judge)
          (swap! m assoc :incarnation incarnation :next-id 0 :grants 0
                 :correlations {} :base-fact-count (count (:facts judge)))
          {:dao.stream/outcome :dao.stream/ok}))))


(defn production-meeting
  "Finite S5 meeting profile. encoded-size measures negotiated codec bytes.
   Pass (entries @meeting) and explicit snapshot to the channel composition."
  [opts]
  (when-not (fn? (:encoded-size opts))
    (throw (ex-info "production meeting requires negotiated encoded byte measurement"
                    {:refused :encoded-size})))
  (meeting (merge {:capacity 64 :max-pairs 32 :max-grants 1024
                   :fanout 16 :fact-bytes 16384 :value-bytes 65536
                   :duration {:ms 120000}} opts)))
