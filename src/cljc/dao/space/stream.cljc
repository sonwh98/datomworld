(ns dao.space.stream
  "The `:dao-stream` transport: the tuple space's write path. Opening
  `{:type :dao-stream :store <jing> :name \"producer\"}` attaches a feeding
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
  documented open gap. A root already holding owner-built `{:indexes ...}`
  is folded back to the wholesale `{:datoms [...]}` shape on first append,
  so published indexes are never silently dropped.

  Reading (`ds/next` with a `{:position n}` cursor) walks the stream's own
  raw datom log in append order — the log, not current state; cross-stream
  reads and current-state resolution stay query-time concerns of
  `dao.space.query`."
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.stream :as ds])
  #?(:cljs (:require-macros [dao.stream])))


(defn- entity->datoms
  "One datom per k/v pair, all sharing transaction time `t`. `:db/id` is
  required: a durable multi-writer log cannot mint per-batch tempids
  without colliding across appends."
  [m t]
  (when-not (contains? m :db/id)
    (throw (ex-info "entity map requires :db/id" {:entity m})))
  (let [e (:db/id m)]
    (into []
          (keep (fn [[a v]] (when (not= a :db/id) [e a v t datom/default-op])))
          m)))


(defn- pad-datom
  "Pad [e a v] / [e a v t m] to the canonical 5-tuple; a nil t takes the
  log position, a nil m the default op (an explicit 0 stays a retraction)."
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


(defn- append-datoms!
  "Read-modify-cas! the new datoms onto the root, retrying on a lost CAS.
  `t` is the log position at append time (a monotone per-root watermark)."
  [store datoms-key val]
  (loop []
    (let [root (jing/get store datoms-key nil)
          rev (:rev root 0)
          existing (cond (nil? root) []
                         (:indexes root) (vec (index/read-datoms store
                                                                 datoms-key))
                         :else (vec (:datoms root)))
          new-datoms (val->datoms val (count existing))]
      (if (jing/cas! store datoms-key rev {:datoms (into existing new-datoms)})
        {:result :ok, :woke []}
        (recur)))))


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
    (let [datoms (index/read-datoms store datoms-key)
          pos (:position cursor 0)]
      (cond (< pos (count datoms)) {:ok (nth datoms pos),
                                    :cursor (assoc cursor
                                                   :position (inc pos))}
            (:closed @state) :end
            :else :blocked)))


  ds/IDaoStreamBound

  (close! [_this] (swap! state assoc :closed true) {:woke []})


  (closed? [_this] (:closed @state)))


(ds/defopen
  :dao-stream
  [descriptor]
  (let [{store :store, stream-name :name, datoms-key :key} descriptor]
    (when-not (and store (satisfies? jing/IKVStore store))
      (throw (ex-info ":dao-stream descriptor requires a :store dao.jing handle"
                      {:descriptor descriptor})))
    (when-not (or datoms-key stream-name)
      (throw
        (ex-info
          ":dao-stream descriptor requires a :name (or :key) — the stream's root is :root/<name>"
          {:descriptor descriptor})))
    (let [k (or datoms-key (keyword "root" (str stream-name)))]
      (index/register-member! store k)
      (->DaoStreamLog store k stream-name (atom {:closed false})))))
