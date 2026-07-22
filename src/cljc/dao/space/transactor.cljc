(ns dao.space.transactor
  "The `:transactor` transport: the tuple space's write path. Opening
  `{:type :transactor :store <jing> :name \"producer\"}` attaches a feeding
  stream to the space (docs/design/dao.space.md, The Write Path):
  the stream owns its own root, `:root/<name>` (an explicit `:key`
  overrides), and `open!` registers that root in the store's membership
  root (`index/members-key`) so readers can enumerate the space.
  `ds/append!` deposits an entity map or datom vector as datoms into the
  stream's root, where `dao.space.query`'s `q`/`match` — which fold every
  member root — immediately see them. This is the generative-communication
  move: deposit, name no recipient; and the single-writer log: no shared
  write surface, no cross-agent contention.

  Appends go through a read-modify-`cas!` loop on the stream's own root;
  with one writer per root the CAS is uncontended and exists only to
  serialize same-name handles. Entity-id stamping across streams (the
  `[stream-ns offset]` global form of dao.space.query.md, Ruling 3) is
  still pending; until then cross-stream `:db/id` collision remains the
  documented open gap. A datom vector's own `t` slot, if supplied
  explicitly rather than left `nil` for auto-assignment, is trusted
  uncritically — this transport has no Datomic-style `:db/txInstant`/tx
  entity separate from `t`, so `t` is both the recency-resolution key
  `dao.space.query`'s `current-state-seq` sorts by *and* the only write
  identity there is. A far-future `t` on one datom permanently outranks
  every later auto-`t` write to the same `[e a v]` (proven by
  `raw-datom-put`'s far-future-`t` test). Callers should leave `t` `nil`
  unless they specifically intend to backdate or pin recency, and should
  not expect any auto-stamped write provenance — an agent that needs to
  know who wrote a datom stamps that itself as an ordinary attribute
  (e.g. `:work/by`, as `stigmergy-example-end-to-end` does). A root
  already holding owner-built `{:indexes ...}`
  is folded back to the wholesale `{:datoms [...]}` shape on first append,
  so published indexes are never silently dropped.

  `publish!` is the other direction: an agent calls it explicitly, at
  whatever cadence it chooses, to build the covered indexes over
  everything appended so far and advance its root to `{:indexes ...
  :count n}` (`dao.space.index/publish-index!` under the hood). There is
  no automatic trigger — no hook fires it on append, no scheduler runs it
  in the background; that would be exactly the implicit control flow this
  design avoids. An agent that never calls it just keeps writing wholesale.

  Reading (`ds/next` with a `{:position n}` cursor) walks the stream's own
  datom log — the log, not current state; cross-stream reads and
  current-state resolution stay query-time concerns of `dao.space.query`.
  Order is append order only while the root stays wholesale; `publish!`
  advances the root to an owner-built index, and index/read-datoms then
  yields eavt order instead — the one event that changes what position
  `n` refers to. `next` detects this with `dao.space.index/read-root`'s
  `:reorder-epoch` and returns `:daostream/gap` rather than silently
  handing a stale cursor a datom from a different position, but only when
  there is still unread data at that position — a cursor already caught
  up when the reorder happened has nothing to lose, so it keeps returning
  `:blocked`/`:end` rather than gapping on a no-op. A same-data republish
  (content-addressed: `publish-index!` compares against the existing
  root and skips the cas!) never reorders anything and so never gaps
  either. Fold-back on the next append does not re-gap, since it does
  not reorder anything already minted (see append-datoms!).

  `:daostream/gap` means something different here than on the
  eviction-based transports (ringbuffer, udp, file): there the cursor's
  position was evicted and the documented recovery is resync to tail
  (dao.stream.file.md); here nothing was evicted, the log was reordered
  in place, so resyncing to tail would silently skip data that is still
  present. The `:daostream/gap` sentinel carries no cause, so a
  transactor reader's gap handler must resync by re-reading from
  `{:position 0}`, not from the tail. A checkpointed cursor must persist
  the whole cursor map (`(:cursor result)`), including `:epoch` — a
  restored `{:position n}` with `:epoch` dropped silently bypasses gap
  detection and resumes into reordered data (dao.space.md, Fault
  Tolerance).

  Cost: every `next` call re-reads and (on a published root) eagerly
  walks the whole root — the full re-read is deliberate, it's what gives
  a blocked cursor liveness across handles (`open-put-query-roundtrip`
  and friends) without a separate wake channel — but it makes a cursor
  walk of n datoms O(n^2) store reads on backends where a read isn't
  free (file, DHT); invisible on dao.jing.mem."
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.stream :as ds])
  #?(:cljs (:require-macros [dao.stream])))


(defn- entity->datoms
  "One datom per k/v pair, all sharing transaction time `t`. `:db/id` is
  required: a durable multi-writer log cannot mint per-batch tempids
  without colliding across appends. Attribute order within one entity's
  own datoms is whatever the host's map iteration yields — unspecified
  across platforms (not stable past 8 entries on the JVM; insertion-ish
  but not contractual on cljs/cljd). \"Append order\" (ns docstring) is a
  per-append guarantee via `t`, not a per-attribute one."
  [m t]
  (when-not (contains? m :db/id)
    (throw (ex-info "entity map requires :db/id" {:entity m})))
  (let [e (:db/id m)]
    (into []
          (keep (fn [[a v]] (when (not= a :db/id) [e a v t datom/default-op])))
          m)))


(defn- pad-datom
  "Pad [e a v] / [e a v t m] to the canonical 5-tuple; a nil t takes the
  log position, a nil m the default op (an explicit 0 stays a retraction).
  An explicit t is trusted uncritically — a far-future t outranks later
  auto-t writes under recency resolution; sibling gap to the pending
  cross-stream entity-id stamping (ns docstring)."
  [d t]
  (let [[e a v dt dm] d]
    [e a v (if (nil? dt) t dt) (if (nil? dm) datom/default-op dm)]))


(defn- val->datoms
  [val t]
  (cond (map? val) (entity->datoms val t)
        (and (vector? val) (<= 3 (count val) 5)) [(pad-datom val t)]
        :else
        (throw
          (ex-info
            "put! takes an entity map or datom vector [e a v] / [e a v t m]"
            {:val val}))))


(def ^:private max-append-retries
  "Bound on append-datoms!'s CAS retry loop. With one writer per root,
  every retry means a same-name handle raced us — expected to be rare,
  but a busy-spin retry loop with no cap pegs a thread indefinitely if
  that assumption is ever wrong (two runaway handles on one name, a
  backend that fails CAS spuriously). 64 is generous headroom over the
  expected \"basically never\" contention, not a tuned figure."
  64)


(defn- append-datoms!
  "Read-modify-cas! the new datoms onto the root, retrying on a lost CAS
  up to `max-append-retries` times before throwing. `t` is the log
  position at append time (a monotone per-root watermark); it is a
  write-time identity, not a live cursor position — a fold-back does not
  renumber old datoms to match wherever they now sit in `next`'s order.
  `:reorder-epoch` is carried forward unchanged (index/read-root, §ns
  docstring): folding an indexed root back to wholesale does not reorder
  the positions already minted against it, only a fresh publish! does.
  `read-root` gives datoms/rev/epoch as one snapshot — reading them
  separately risked a rev that no longer matched the datoms a concurrent
  publish! had already reshaped, costing a spurious lost-CAS retry."
  [store datoms-key val]
  ;; attempt counts lost CAS attempts only — it advances in the failure
  ;; branch below, not on every loop iteration; a successful cas! returns
  ;; directly and never reaches recur.
  (loop [attempt 0]
    (when (>= attempt max-append-retries)
      (throw
        (ex-info
          "append! could not win the root cas! after max-append-retries attempts"
          {:key datoms-key, :attempts attempt})))
    (let [{:keys [datoms rev reorder-epoch]} (index/read-root store datoms-key)
          existing (vec datoms)
          new-datoms (val->datoms val (count existing))]
      (if (jing/cas! store
                     datoms-key
                     rev
                     {:datoms (into existing new-datoms),
                      :reorder-epoch reorder-epoch})
        {:result :ok, :woke []}
        (recur (inc attempt))))))


(deftype DaoStreamLog
  [store datoms-key stream-name state]

  ds/IDaoStreamWriter

  (append!
    [_this val]
    (when (:closed @state)
      (throw (ex-info "Cannot put to closed stream" {:name stream-name})))
    (append-datoms! store datoms-key val))


  ds/IDaoStreamReader

  (next
    [_this cursor]
    (let [{:keys [datoms reorder-epoch]} (index/read-root store datoms-key)
          pos (:position cursor 0)
          cursor-epoch (:epoch cursor reorder-epoch)
          reordered? (and (pos? pos) (not= cursor-epoch reorder-epoch))]
      (cond (and reordered? (< pos (count datoms))) :daostream/gap
            (< pos (count datoms)) {:ok (nth datoms pos),
                                    :cursor (assoc cursor
                                                   :position (inc pos)
                                                   :epoch reorder-epoch)}
            (:closed @state) :end
            :else :blocked)))


  ds/IDaoStreamBound
  ;; :closed is local to this handle's own `state` atom, not the shared
  ;; root — a second handle on the same name keeps reading normally after
  ;; this one closes (cursor-reading's ":closed is per-handle" test); it
  ;; models "this client is done," not "the stream is done."
  (close! [_this] (swap! state assoc :closed true) {:woke []})


  (closed? [_this] (:closed @state)))


(ds/defopen
  :transactor
  [descriptor]
  (let [{store :store, stream-name :name, datoms-key :key} descriptor]
    (when-not (and store (satisfies? jing/IKVStore store))
      (throw (ex-info ":transactor descriptor requires a :store dao.jing handle"
                      {:descriptor descriptor})))
    (when-not (or datoms-key stream-name)
      (throw
        (ex-info
          ":transactor descriptor requires a :name (or :key) — the stream's root is :root/<name>"
          {:descriptor descriptor})))
    (let [k (or datoms-key (keyword "root" (str stream-name)))]
      (index/register-member! store k)
      (->DaoStreamLog store k stream-name (atom {:closed false})))))


(defn publish!
  "Build and persist the covered indexes over everything appended to
  `log` so far, advancing its root to `{:indexes ... :count n}`. Runs on
  every platform (dao.data.btree, not psset — see
  docs/design/dao.data.btree.md §6). opts: as `dao.space.index/publish-index!`
  (e.g. `:branching-factor`); `:key`, `:rev`, and `:reorder-epoch` are
  supplied from `log`'s own atomic read (`index/read-root`) and cannot be
  overridden — the datoms this builds indexes from and the revision the
  final cas! targets must be the same snapshot, or a same-root append
  landing mid-build would be silently dropped instead of causing the
  loud cas! failure `publish-index!` promises.

  The next `ds/append!` on this (or any other handle sharing the same
  root) folds the published root back to wholesale, per this ns's own
  append-datoms! — publishing is not sticky across writes, by design
  (§ns docstring)."
  ([log] (publish! log nil))
  ([^DaoStreamLog log opts]
   (let [store (.-store log)
         datoms-key (.-datoms-key log)
         {:keys [datoms rev reorder-epoch]} (index/read-root store datoms-key)]
     (index/publish-index! store
                           datoms
                           (assoc opts
                                  :key datoms-key
                                  :rev rev
                                  :reorder-epoch reorder-epoch)))))
