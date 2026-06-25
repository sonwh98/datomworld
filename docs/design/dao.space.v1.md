# DaoSpace: A Queryable Tuple Space

**Related documents:**
- `docs/design/dao.stream.md` — the stream transport that feeds the space
- `docs/design/dao.stream.file.md` — the file-backed byte stream member logs are built on
- `docs/design/dao.db.md` — the indexed-datom query kernel (DaoSpace *is* its read half)
- `docs/agents/datom-spec.md` — datoms, content-addressed identity, the gauge/base framing

## The Invariant

Everything in this document is in service of one fixed requirement:

> **Agents can match tuples and query tuples with Datalog.**

That is the whole definition of a tuple space, and the only thing that may not be
traded away. "Match" (`[?e :work/status :todo]`) is the single-clause case of Datalog;
both are **unification over a set of tuples**. So the invariant reduces to one thing:

> **A set of datoms you can query by unification — pattern up to full Datalog (joins,
> negation, aggregation, time).**

Notice what is *not* in the invariant: streams, producers, scoping, content hashes,
"dumb pipes." Those are realization concerns. They live *below* the invariant and exist
only to make it hold in a distributed, persistent, multi-writer world. They are not part
of what a tuple space *is*.

## What DaoSpace Is

Run the invariant to its fixed point. A queryable, indexed set of datoms supporting
unification and Datalog **is** a Datomic/DataScript-style in-memory database value.
Therefore:

> **DaoSpace ≡ an indexed set of datoms + Datalog. The tuple space *is* the query
> engine.**

`dao.space/q` (full Datalog) and `dao.space/match` (a positional datom pattern) are the
read surface, and they range over the whole tuple set by default — global, associative
matching, addressed by *content*, never by producer. That global reach is not a feature
bolted on; it is the identity of a tuple space. An agent never names who wrote a tuple;
it names the *shape* it wants, and the space answers from every tuple it holds.

## What the Invariant Forces

Datalog is not a per-tuple operation. `(not [_ :work/claims ?w])`, joins, and
aggregations all need the **whole relevant tuple set present and indexed at query
time**. You cannot answer them by tailing one tuple at a time.

So the invariant forces a single, non-negotiable consequence:

> **At query time the space must present a materialized, indexed set of datoms.**

There is no "dumb space with no index" that still answers Datalog — the two are
contradictory. The index has to live somewhere, and whatever holds it *is* the tuple
space. DaoSpace owns that index (it builds it by folding its member streams); it does
not push querying downstream, because querying *is* what it is for.

## Realization: Streams Below the Invariant

Streams are not in the definition, but they are how the definition is *fed and made
durable*. The space is the queryable datom set on top; an open, dynamic collection of
append-only `dao.stream`s is the ingestion and persistence substrate beneath.

```
   dao.space/q  /  dao.space/match           ← THE INVARIANT
        │  fold every member stream's datoms into one
        │  indexed datom set, then query it
        ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ member log A │  │ member log B │  │ member log C │   (1..n autonomous streams;
│ ds/put! →    │  │ ds/put! →    │  │ ds/put! →    │    each its own append-only file,
└──────┬───────┘  └──────┬───────┘  └──────┬───────┘    written without contention)
       │ datom frames (self-delimiting byte records)
┌──────▼───────┐  ┌──────▼───────┐  ┌──────▼───────┐
│ dao.stream.  │  │ dao.stream.  │  │ dao.stream.  │   (byte substrate: async disk,
│ file         │  │ file         │  │ file         │    non-blocking)
└──────────────┘  └──────────────┘  └──────────────┘
```

Each realization concern answers a question the invariant raises but does not itself
address:

- **Where do the tuples come from, and how do they persist?** Member streams. Each
  writer owns its own append-only log, so writes never contend and nothing routes.
  Durability and ordering are the stream's job (see `dao.stream.file.md`).
- **How do many writers contribute without a single transactor?** Many independent
  streams, folded at read time. There is no shared write cursor and no commit step;
  folding is order-insensitive, so it needs no merge.
- **How does "the same fact from two writers" unify in a join?** Content-addressed
  identity. Entity IDs are stream-local coordinates; what unifies across writers is the
  gauge-invariant content hash (the base `B` in `datom-spec.md`), not a producer's local
  ID. Joins live in shared *values*, never in stream-local entity IDs.

**Writing is streaming; reading is querying.** Tuples enter only by being appended to a
member stream; the space is never read *as* a stream (no space-level cursor). The two
verbs `q` and `match` are the entire read surface.

### Relationship to DaoDB

DaoDB's four-component design splits cleanly into a read half and a write half, and the
invariant tells you exactly what to do with each:

- **DaoDB's read half** — the indexes plus `d/q` and `pull` — **is** DaoSpace. The tuple
  space and the query kernel are the same object; DaoSpace builds a fresh indexed datom
  value from its folded streams and queries it with that kernel.
- **DaoDB's write half** — the single-writer `IDaoTransactor` and the durable
  `IDaoStorage` segment log — is **replaced by streams.** DaoSpace needs no transactor
  (each stream is its own writer) and no storage backend (each stream is its own durable
  log). It needs only to be *fed* an indexed datom set.

So "is DaoDB redundant?" resolves precisely: its query kernel is not redundant (it is
the tuple space itself), and its transactor/storage are (streams subsume them).

## Global Match and Scoping

Global associative match is the **default and the floor** — `q`/`match` see every tuple,
and an agent never has to know its producers. That is what makes this a tuple space
rather than a message bus.

Scoping, when needed for relevance, performance, or capability security, is an *opt-in
refinement expressed as a predicate over content* — never an enumeration of producers.
A scoped sub-space is "the tuples matching `P`," where `P` names shapes
(`[?e :work/* ?v]`), so the agent still surfaces tuples from writers it never heard of.
This bounds visibility without reintroducing the spatial coupling a tuple space exists
to abolish.

| Regime | Scope defined by | Generative? | Use |
|--------|------------------|-------------|-----|
| **Root** | nothing — query everything | yes | the default; a true tuple space |
| **Predicate sub-space** | a content pattern `P` | yes — names shapes, not producers | relevance / performance |
| **Capability sandbox** | result-filtering policy | yes — agent still writes a global query | confining untrusted agents |

The capability case is row-level-security, not producer-enumeration: an untrusted agent
writes a global query; a trusted boundary filters the *results* to what it may see. The
query stays global (no producers named); only the visible results narrow.

## API

The surface is small and asymmetric: `dao.space/open!` returns a handle; member streams
opened through it carry tuples *in* via `ds/put!`; `dao.space/q` and `dao.space/match`
read the whole tuple set *out*.

### Opening a space

```clojure
(require '[dao.space :as space]
         '[dao.stream :as ds])

(def s (space/open! "work"))                 ; name → default location
(def s (space/open! {:path "/data/work"}))   ; explicit location; reopening rediscovers members
```

### Writing — open a member stream, `ds/put!`

A datom enters the space by being appended to a member stream the agent opens through
the handle. Each open returns a distinct `dao.stream.file` with its own append-only log.

```clojure
(def log (ds/open! {:type :dao-stream :space s :name "agent-1"}))

(ds/put! log {:db/id (random-id) :work/id "w1" :work/status :todo})
(ds/put! log [:db/retract work-id :work/status])     ; retraction is also an append
```

### Reading — `dao.space/q` and `dao.space/match`

Both fold every member stream's datoms into one indexed datom set and query it — a
point-in-time snapshot taken when the read runs.

```clojure
;; q — full Datomic-compatible Datalog (joins, negation, aggregation, predicates)
(dao.space/q '[:find ?id ?task
               :where [?id :work/status :todo]
                      [?id :work/task ?task]]
  s)
;; => #{[id task] ...}

;; match — a positional datom template (Linda-style), lighter than q
(dao.space/match s [_ :work/status :todo])   ; => matching datoms

;; as-of — a read bound, not a verb: fold each stream only up to `point`
(dao.space/q query s {:as-of t})
(dao.space/match s pattern {:as-of (instant "2026-01-01")})
```

### Membership

Opening a member stream through the handle *is* joining; closing it leaves.
`space/join!` attaches an already-open autonomous stream (one written to offline, or
previously detached); `ds/open! {:space …}` is sugar for "open then `space/join!`."
`space/leave!` detaches without closing; `space/join!` and `space/leave!` are the
symmetric pair. `(dao.space/members s)` exposes the raw member streams for tools that
need direct stream access — it is a transport affordance, **not** the read API. The read
API is `q` / `match`.

## Realization Choice: Rebuild vs Incremental

The invariant fixes *what* DaoSpace is; it leaves one pure-realization choice open,
because both options satisfy it identically and differ only in cost:

- **Rebuild per query** — each `q`/`match` folds the member streams into a fresh indexed
  datom value and throws it away. Simple; correct; O(total datoms) per read.
- **Incremental index** — a long-lived index ingests new stream frames as they arrive
  (a cursor per stream, fold deltas), Datomic-*peer* style. More machinery; amortized
  reads. Still no transactor and no global clock — each stream advances its own cursor.

This is a performance fork, not a definitional one, and can be deferred without touching
the invariant or the API.

```clojure
;; Reference fold (rebuild-per-query form). Each stream's datoms are stamped with the
;; stream's namespace before indexing, so two streams' local id 1025 stay distinct;
;; "same logical entity across streams" is a join on shared values, never on a local id.
(defn- fold-db [s as-of]
  (let [datoms (mapcat (fn [log]
                         (let [ns (stream-ns log)]
                           (map #(stamp ns %) (ds/->seq log))))
                       (vals @(members s)))]
    (db/index (cond->> datoms as-of (filter #(<= (:t %) as-of))))))

(defn q [query s & {:keys [as-of]}] (db/q query (fold-db s as-of)))
```

**Implementation status.** Content-addressed identity (the base `B`) is the intended
basis for cross-writer unification, but is only partially realized: `yin/content.cljc`
hashes via `pr-str` rather than the canonical byte encoding `datom-spec.md` mandates, and
the indexed kernel still allocates integer entity IDs, so stamped `[namespace offset]`
references are not yet first-class join keys. Making the content hash load-bearing is a
prerequisite for treating cross-stream identity as gauge-invariant rather than stamped.

## Coordination: Stigmergy

Agents coordinate by leaving tuples for others to query, decoupled in time and identity.
Because streams are append-only there is no destructive `take`: to "claim" work an agent
*appends a new tuple* asserting the claim, and "current state" is a read-side query over
the accreted tuples.

```clojure
(defn producer [s]
  (let [log (ds/open! {:type :dao-stream :space s :name "producer"})]
    (ds/put! log {:db/id (random-id) :work/posted true :work/task "process payment"})))

(defn worker [s worker-id]
  (let [log (ds/open! {:type :dao-stream :space s :name worker-id})]
    (loop []
      ;; Datalog expresses "posted work nothing has claimed" — the query that justifies
      ;; a tuple space (negation + join over the whole set, not a per-tuple scan).
      (let [work (dao.space/q '[:find ?task
                                :where [?w :work/posted true]
                                       [?w :work/task ?task]
                                       (not [_ :work/claims ?w])]
                   s)]
        (when-let [[task] (first work)]
          (ds/put! log {:db/id (random-id) :work/claims task :work/by worker-id})
          (ds/put! log {:db/id (random-id) :work/result (process task)})
          (recur))))))
```

## Lineage

DaoSpace is the meeting point of three traditions:

- **Linda** gives the essence kept whole: generative communication (write into a shared
  medium, don't address a receiver), spatial and temporal decoupling, non-destructive
  associative matching. The divergences are immutability (append, never `take`) and
  named n-tuples (datoms) in place of untyped positional arrays.
- **Datomic** gives what Linda lacked: immutable datoms, Datalog, time (`as-of`), and
  content-addressed identity. The tuple space *is* an indexed datom value; that is the
  whole reason `q` can do joins, negation, and aggregation a template never could.
- **Plan 9** gives the realization stance: streams are mountable, autonomous,
  location-transparent resources, and a space is assembled from them — but, unlike a
  Plan 9 per-process namespace, the *default* is global match, because a coordination
  medium for strangers must let any agent match the whole space, not only what it bound.

The synthesis: **`dao.stream` is a 9P-style append-only log; `dao.space` is a Datomic
query value folded from a Linda-style global collection of them.**
