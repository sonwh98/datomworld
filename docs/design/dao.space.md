# DaoSpace: The Tuple Space

`dao.space` is **not a thing you store**. It is the **tuple space that emerges** when agents
use Datalog (the `dao.space.query` library) to match tuples stored in
[`dao.jing`](dao.jing.md) and coordinate by leaving traces there. Storage holds facts at
rest; the tuple space is those facts *under interpretation*.

More precisely, "tuple space" names the **associative coordination surface** of a broader
family of interpreters over those facts (see [A Family of Interpreters](#a-family-of-interpreters)).
That surface — by-content matching over a shared medium of named tuples — is the privileged
default and the contract strangers coordinate through; it is what makes `dao.space` a tuple
space. The other surfaces the family admits (graph, tree, entity-centric, columnar views) are
local read ergonomics, not new coordination modes. The label holds as long as two things hold:
**coordination stays associative** (agents match by content, never address each other or
navigate each other's views by reference), and **views stay derived** (reconstructable from
the datoms, never primary state).

**Related documents:**
- `docs/design/dao.jing.md` — the storage boundary: the content-addressed datom repository this space reads
- `docs/design/dao.stream.md` — the append-only log primitive datoms are written through
- `docs/agents/datom-spec.md` — datoms, content-addressed identity, the gauge/base framing
- `docs/datomic.md` — the Datomic architecture the Transactor/Storage/Query split maps to
- `docs/design/adr/0001-dao-space-as-storage-boundary.md` — the decision this design records
- `docs/design/adr/0002-share-governed-computation-not-data.md` — the access-mode security model
- `docs/design/dao.space.security.md` — the controlled-mode / capability detail
- `docs/design/dao.space.v0.md` — superseded framing; still the reference for resources, typed streams, and the geometry/gauge material
- `docs/design/dao.space.locality.md`, `dao.space.metaphors.md`, `dao.space.discrete-to-continuous.md` — the geometry/locality cluster: theoretical justification (gauge, spectral, locality)

## Three Boundaries

Datomic's central strength is the strict separation of **Transactor**, **Storage**, and
**Query** into *abstraction boundaries* — interfaces, not deployment tiers. They can be
co-located in a single process with zero network overhead, or split across machines, without
changing the contracts. datom.world keeps that separation and maps it onto streams:

- **Transactor (write)** — **decentralized.** Every agent appending to its own `dao.stream`
  is its own transactor: it enforces local schema and appends datom frames, with no global
  contention and no commit step.
- **Storage** — **[`dao.jing`](dao.jing.md).** The decentralized, content-addressed
  repository of immutable datoms. It persists and serves datoms. It does **not** match or
  query.
- **Query (read)** — **the `dao.space.query` library any interpreter embeds.** It pulls
  datoms from `dao.jing`, builds an in-memory index, and runs pattern matching and Datalog.
  Pure, in-process, per-interpreter — Datomic's Peer model, as a library rather than a
  service.

**`dao.space` is the Query boundary in action plus the coordination it enables.** It is not a
fourth component beside the three; it is what the Query faculty and the agents *do* over
Storage. This document specifies that tuple space. Storage is specified separately in
[`dao.jing.md`](dao.jing.md).

## What Makes It a Tuple Space

A tuple space is defined by **associative (by-content) matching** and **generative
communication** (write into a shared medium, never address a receiver). Both are *read-side /
interpreter* behaviors, conferred by the query library and the agents — not by storage. A
store that matched would collapse interpretation into storage, which datom.world's invariants
forbid. So the "space-ness" lives here, above `dao.jing`:

- `dao.jing` holds the tuples.
- `dao.space.query` makes them matchable (associative Datalog over the whole store).
- Agents coordinate by what is there (stigmergy), never by addressing each other.

A tuple space is therefore not an artifact you instantiate; it is the behavior that appears
when interpreters match over shared storage. `dao.jing` is *where* the tuples live;
`dao.space` is *what agents do* there.

Associative matching is the **privileged** coordination surface, not the only thing
interpreters can do over the store. The same datoms support other read surfaces — graph, tree,
entity-centric, columnar views (see [A Family of Interpreters](#a-family-of-interpreters)). What
keeps `dao.space` a tuple space is that *cross-agent coordination* runs through matching;
those other surfaces are how an interpreter navigates its own derived views, not how strangers
find each other.

## The Query Library

Pattern matching and Datalog are a **library** (`dao.space.query`) that any interpreter
embeds and runs against a `dao.jing`. It is pure: pull datoms from storage, build an
in-memory index, answer. It owns no durable state.

```clojure
(require '[dao.space.query :as query])

;; q — full Datalog (joins, negation, aggregation, predicates) over all of storage
(query/q '[:find ?id ?task
           :where [?id :work/status :todo]
                  [?id :work/task ?task]]
  store)
;; => #{[id task] ...}

;; match — a positional datom template (Linda-style), lighter than q
(query/match store [_ :work/status :todo])   ; => matching datoms

;; as-of — a read bound: index storage only up to `point`
(query/q query-form store {:as-of t})
(query/match store pattern {:as-of (instant "2026-01-01")})
```

Within a single stream `as-of t` is exact (the stream's own append order). A **cross-stream**
`as-of` needs a time coordinate comparable across streams — a wall-clock instant (as shown)
rather than per-stream `t` — and its precise semantics are deferred (see ADR 0001, Open
Question 2).

Internally the library folds the store's datoms into an index and queries it. Each stream's
datoms are stamped with the stream's namespace before indexing, so two streams' local id
`1025` stay distinct; "same logical entity across streams" is a join on shared *values*,
never on a stream-local id.

```clojure
;; Sketch of the library, rebuild-per-query form (pedagogical; not the v1 path).
;; `read-datoms` parses B-Tree
;; segments pulled from dao.jing into datoms — storage has no datoms API of its own.
(ns dao.space.query
  (:require [dao.jing :as store]))   ; namespace currently dao.space; see dao.jing.md naming note

(defn- fold [store as-of]
  (index (read-datoms store {:as-of as-of})))   ; build EAVT/AEVT/AVET

(defn q     [query-form store & {:keys [as-of]}] (run-datalog query-form (fold store as-of)))
(defn match [store pattern    & {:keys [as-of]}] (scan-pattern  pattern    (fold store as-of)))
```

How the index is maintained (rebuild-per-query, incremental, or owner-built/peers-merge) is a
storage-layout concern realized on `tonsky/persistent-sorted-set`; see
[`dao.jing.md`](dao.jing.md), *Index Realization*. All variants answer identically.

### Global match and scoping

The library reads `dao.jing`, and `dao.jing` is the *global* repository, so any interpreter
that embeds the library matches over **everything** by default — global, associative,
addressed by content, never by producer. That global reach is the identity of a tuple space;
the per-interpreter *embedding* of the library does not scope it, because the *storage* it
reads is shared.

Two different things hide under "scoping," and only one is a security mechanism.

**Relevance / performance scoping** is a **predicate over content** inside the query — never
an enumeration of producers. A scoped view is "the datoms matching `P`," where `P` names
shapes (`[?e :work/* ?v]`), so the reader still surfaces tuples from writers it never heard
of. This is a *trusted* reader choosing to look at less; it is **not** security — choosing to
read less never stops you from reading more.

**Security and Access Modes**

datom.world's security model rests on the principle: **"share governed computation, not
data."** Sharing bits is losing control of those bits, and encryption only relocates the
problem to key-sharing. Plaintext result-filtering *does* need a mediator — but rather than
pretend to escape that, the model makes the mediator **generic and accountable**: control is
held by never emitting raw datoms, only the bounded result `f(X)` of an authorized
interpreter `f`.

This yields two distinct access modes (see [dao.space.security.md](dao.space.security.md) and
[ADR 0002](adr/0002-share-governed-computation-not-data.md) for full details):

- **Public (pull-to-reader):** the default mode. The reader embeds the library and pulls
  datom streams from `dao.jing` directly. There is no fine-grained control; the only
  security is coarse, per-stream access (POSIX-style filesystem permissions on the member
  logs). Use this when it is safe to ship the datoms.
- **Controlled (push-interpreter-to-data / confined):** when fine-grained per-datom control
  is needed, the topology inverts and the data never leaves its owner's control. The reader
  submits a governed interpreter (a `yin.vm` AST) wrapped in a capability; it runs in a
  confined environment scoped to the authorized datoms, and only the attenuated answer
  returns. The **capability token** is cryptographically authenticated; the **content
  predicate** (the `m` slot carries the policy) is enforced by the evaluation substrate —
  operationally by a confined CESK runtime, or cryptographically by an MPC/FHE circuit —
  which is distinct from authenticating the token. The owner is the mediator by default; MPC
  removes even that (see the security doc).

Trusted peers and public data are the common case: embedded library, direct access, global
match. When control is required, the architecture switches to controlled mode, where the unit
of sharing is the governed interpreter, backed by an immutable accountability log.

**v1 ships public mode only.** Controlled mode — the governed interpreter, capabilities,
`m`-policy — is specified but out of scope for v1 (see the security doc and ADR 0002).

## A Family of Interpreters

The query library above presents one surface: Datalog over the covered-index view
(EAVT/AEVT/AVET/VAET). That is the default and the v1 package, but it is not the definition of
`dao.space`. More generally, **`dao.space` is a family of interpreters over one canonical
datom history** — a *moduli space* of databases. The datoms in [`dao.jing`](dao.jing.md) are
the fixed substrate every member shares; a member is fixed by which **materialized views** it
constructs and which surface it exposes. One point looks relational (covered indexes plus
Datalog); another document-oriented (entity-centric maps); others columnar, graph-oriented, or
logic-oriented. The substrate stays the same; only the derived construction changes (see
[`dao.jing.md`](dao.jing.md), *Derived Views*, for how those views are stored as
reconstructable cache).

Two consequences follow, and both tighten the existing invariants rather than relax them:

- **Datalog is a capability, not the ontology.** It stays the relational front-end wherever
  relational views exist, but it is one front-end among several, not the essence. The essence
  is lower: datom streams plus *declared* structural interpretation.
- **Views are named and declared, not infinite magic.** An interpreter does not navigate
  every imaginable materialization; it exposes a sanctioned set and answers explicit
  questions: which views exist, which can be derived on demand, whether a view is
  relational / graph / tree / associative / sequential, what its legal traversals are, and
  what its `as-of`/`since` semantics are. A query may trigger on-demand derivation, but only
  through a declared interpreter with known semantics — explicit causality and explicit
  capability, nothing implicit. (*Do not assume graphs*: graph structure is one materialized
  interpretation constructed from datoms, never an implicit truth.)

This reframes querying as **compilation** rather than handing a sentence to a fixed engine: a
caller states intent, a planner inspects the declared view capabilities and rewrites that
intent into an access plan, and an executor runs the plan against a materialized view or
triggers a declared derivation. The same "find user by email" intent compiles to an indexed
`AVET` lookup against one interpreter and an entity scan against another — same family,
different database. The concrete compiler pipeline (a planner/optimizer front-end lowering
into a traversal-IR back-end) is a direction, not yet specified here.

## Coordination: Stigmergy

Agents coordinate by leaving datoms in `dao.jing` for others to query, decoupled in time and
identity. Because streams are append-only there is no destructive `take`: to "claim" work an
agent *appends a new datom* asserting the claim, and "current state" is a read-side query over
the accreted datoms. This is the tuple space working as designed — coordination with no
broker, no message-format negotiation, and no leader election.

```clojure
(defn producer [store]
  (let [log (ds/open! {:type :dao-stream :store store :name "producer"})]
    (ds/put! log {:db/id (random-id) :work/posted true :work/task "process payment"})))

(defn worker [store worker-id]
  (let [log (ds/open! {:type :dao-stream :store store :name worker-id})]
    (loop []
      ;; "posted work nothing has claimed" — negation + join over the whole store,
      ;; the query that justifies a tuple space (not a per-datom scan).
      (let [work (query/q '[:find ?w ?task
                            :where [?w :work/posted true]
                                   [?w :work/task ?task]
                                   (not [_ :work/claims ?w])]
                   store)]
        (when-let [[?w task] (first work)]
          (ds/put! log {:db/id (random-id) :work/claims ?w :work/by worker-id})
          (ds/put! log {:db/id (random-id) :work/result (process task)})
          (recur))))))
```

The naive version hides a familiar race: two workers can run the claim query before either
appends, and both then claim the same task. That is not a storage bug; it is check-then-act
over an append-only log. There is no lock and no `cas!` over a shared stream to force
exclusion — both claims are simply recorded in their own owners' logs. The conflict is
resolved **on the read side**: a downstream reader sees both claims, sorts by timestamp,
breaks ties by worker id, and deterministically yields one winner. Exclusion is a query rule
in the interpreters, not a guarantee the store enforces — which is exactly why the
tuple-space character belongs to `dao.space`, not `dao.jing`.

## Lineage

The tuple space is **Linda's** contribution: generative communication (write into a shared
medium, don't address a receiver), spatial and temporal decoupling, non-destructive
associative matching. The divergences are immutability (append, never `take`) and named
n-tuples (datoms) in place of untyped positional arrays. Matching stays global by default,
because a coordination medium for strangers must let any reader match the whole store, not
only what it bound.

The other two traditions live in the layers below and have their own docs:

- **Datomic** owns [`dao.jing`](dao.jing.md) — the dumb KV store of immutable segments and
  the Peer-as-library read model.
- **Plan 9** owns [`dao.stream`](dao.stream.md) — the independent, location-transparent,
  append-only log substrate.

The synthesis: **`dao.space` is the tuple space that emerges when the embeddable
`dao.space.query` Peer library matches Datalog over `dao.jing`, and agents coordinate by the
traces they leave there.**
