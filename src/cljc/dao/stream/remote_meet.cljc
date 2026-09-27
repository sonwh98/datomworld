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
                 [(assoc v :meet/from (:dao.stream.remote/channel ctx))]))
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
           duration ring! self fanout]}]
  (atom {:requests requests
         :requests-cursor (:dao.stream/cursor
                            (stream/cursor requests stream/anchor-oldest))
         :board board
         :table table
         :capacity capacity
         :max-pairs max-pairs
         :active-pairs 0
         :decision decision
         :judge-atom judge-atom
         :duration duration
         :ring! ring!
         :self self
         :fanout (or fanout default-fanout)
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
    (str prefix "-" n)))


(defn reclaim-fn
  "The idempotent reclaim procedure for a relay pair's lease: `subject`
   is `[in-id out-id]`, this namespace's own grant shape. Removes both
   identities from the table, and with them the holder's renewal
   medium entry the grant created (dao.stream.remote.md, section 6:
   the reclaim procedure removes the subject entries and the renewal
   entry together) -- absent already, a no-op, so a repeated call (the
   judge re-reaches a still-pending lease every pass) stays
   idempotent -- and decrements the meeting's own active-pairs count
   exactly once, guarded on the table still holding the subject. Hand
   this to `dao.lease/make-judge`'s `:reclaim`; this namespace never
   drives the judge itself."
  [m]
  (fn [subject]
    (let [[in-id out-id] subject
          table (:table @m)
          renewal-id (get (:renewals @m) in-id)
          had? (contains? @table in-id)]
      (swap! table dissoc in-id out-id renewal-id)
      (swap! m update :renewals dissoc in-id)
      (when had? (swap! m update :active-pairs dec))
      true)))


(defn- remote-descriptor
  [channel-id identity]
  {:dao.stream/type :dao.stream/remote
   :dao.stream/identity identity
   :dao.stream/channel {:dao.stream/type :dao.stream.remote-meet/served
                        :dao.stream/identity channel-id}})


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
  (let [{:keys [table capacity ring! judge-atom duration]} @m
        pair-id (mint-id! m "pair")
        in-id (str pair-id "-in")
        out-id (str pair-id "-out")
        renewal-id (str pair-id "-renewal")]
    (swap! table assoc
           in-id {:handle (ring! capacity) :surface #{:reader :writer}}
           out-id {:handle (ring! capacity) :surface #{:reader :writer}}
           renewal-id {:handle (ring! capacity) :surface #{:writer}})
    (let [renewal-handle (:handle (get @table renewal-id))
          grant (lease/grant pair-id [in-id out-id] holder duration)]
      (swap! judge-atom lease/wire-facts renewal-handle
             (:dao.stream/cursor
               (stream/cursor renewal-handle stream/anchor-oldest))
             holder)
      (swap! judge-atom lease/author-grant grant)
      (swap! m assoc-in [:renewals in-id] renewal-id)
      (swap! m update :active-pairs inc)
      {:pair-id pair-id
       :grant grant
       :in (remote-descriptor pair-id in-id)
       :out (remote-descriptor pair-id out-id)
       :renewal (remote-descriptor pair-id renewal-id)})))


(defn- handle-request!
  [m v]
  (cond
    (contains? v :meet/here)
    ;; A registration is a board record, not a lease: no lease id is
    ;; minted for it, nothing granted, nothing judged (section 6: a
    ;; board posting carries the lease id of the pair it belongs to,
    ;; and a registration belongs to no pair).
    (stream/append! (:board @m)
                    {:meet/seen (:meet/here v)
                     :meet/reflexive (:meet/from v)})

    (contains? v :meet/pair)
    (let [holder (or (:meet/from v) (:meet/pair v))
          {:keys [active-pairs max-pairs]} @m]
      ;; Capacity is enforced again here, at grant time: several
      ;; requests may have been accepted against one published count,
      ;; and the gate is never asked retroactively. The refusal is a
      ;; present answer -- a board posting the asker reads through its
      ;; own reflection, naming the ask and why -- never silence.
      (if (and (number? max-pairs) (>= active-pairs max-pairs))
        (stream/append! (:board @m)
                        {:meet/pair-for (:meet/pair v)
                         :meet/asker (:meet/from v)
                         :meet/refused :dao.stream.remote-meet/past-bound})
        (let [{:keys [pair-id grant in out renewal]}
              (grant-pair! m holder)]
          ;; The complete grant rides the posting: the holder observes
          ;; it before acting (dao.lease.md, The holder), the pair
          ;; descriptors beside it, and the renewal medium's
          ;; descriptor for the renewals the judge now counts.
          (stream/append! (:board @m)
                          {:meet/pair-for (:meet/pair v)
                           :meet/asker (:meet/from v)
                           :dao.lease/lease pair-id
                           :dao.lease/grant grant
                           :dao.stream.remote/in in
                           :dao.stream.remote/out out
                           :dao.stream.remote/renewal renewal}))))

    :else nil))


(defn step!
  "One pass of M's interpreter: publish the bound's current decision
   for `bound-gate` to read next, then drain `:meet-requests`,
   answering each `:meet/here` or `:meet/pair` fact on `:meet-board`;
   a gap adopts the reader's own recovery cursor, the lost asks lost,
   a resume costing one unit of the fanout budget like a handled
   request. At most `:fanout` requests are handled in one pass --
   section 4's bounded meeting work -- and the cursor is preserved
   after each, so the remainder waits for later passes. Returns `m`."
  [m]
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
              (handle-request! m (:dao.stream/value r))
              (recur (dec budget)))

          :dao.stream/gap
          (do (swap! m assoc :requests-cursor (:dao.stream/cursor r))
              (recur (dec budget)))

          nil))))
  m)
