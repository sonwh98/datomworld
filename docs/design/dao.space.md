# DaoSpace Design: Stigmergic Coordination via DaoStream

**Related documents:**
- `docs/design/dao.stream.md` — the stream transport foundation
- `docs/design/dao.stream.file.md` — the file-backed byte stream that member logs are built on
- `docs/agents/datom-spec.md` — datom moduli space, dimensions, gauge/content-hash framing
- `docs/design/dao.space.metaphors.md` — visual and biological metaphors for the space
- `docs/design/dao.space.discrete-to-continuous.md` — the spectral-triple realization (gauge groupoid, Dirac operator, curvature)
- `docs/design/dao.space.locality.md` — physics ↔ distributed-computing correspondence (relativity, descent, consistency models, CAP)
- `docs/design/dao.flow.md` — coordination workflow protocol

## Overview

**A DaoSpace is a catalog of tuples that enter via dao.stream member logs and are read by interpreters — a coordination medium whose contents are that catalog.** Structurally it is the accreting, content-addressed catalog of every tuple written into it; functionally it is the medium through which resources and interpreters coordinate. The two are one thing: agents coordinate *by* writing and querying the catalog, and its currency is the tuple (a datom). A **resource** is anything expressible as datoms — data, a dao.stream (its descriptor is a datom), yin.vm bytecode (AST datoms), schema, provenance, an agent's belief state — so the catalog of tuples is implicitly a catalog of resources. **Interpreters** read those tuples and realize them into meaning or behavior.

Concretely, a DaoSpace is **a collection of file-backed dao.streams**. `dao.space/open!` returns a **handle** to that collection; the handle plays two roles. As a **factory**, it opens member write logs — `ds/open!` delegates through the space to realize a `dao.stream.file` member, and `ds/put!` appends datom frames to it. As a **read target**, the handle answers questions over *all* member logs at once: `dao.space/q` (Datalog) and `dao.space/match` (datom pattern). The asymmetry is deliberate — **writing is streaming, reading is querying.** You never read the space *as* a stream; member streams carry datoms in, the space answers questions out.

This is datom.world's modern tuple space, generalized: not only streams-and-readers but *any resource* and interpreters, because everything reduces to tuples (CLAUDE.md: "everything is data, code is data, runtime state is data"; `datom-spec.md`: datoms are the universal format for DaoDB, AST, schema, provenance). The tuples are **named, n-dimensional** datoms (d1/d3/d5/d10), not Linda's positional, untyped arrays — so a tuple carries its own meaning, and coordination never depends on agents agreeing on field order. Unlike traditional message passing (explicit sender → receiver), DaoSpace enables **stigmergic coordination**: producers leave tuples (traces), consumers react to them, decoupled in time and identity. The space is a *passive* medium — it does not orchestrate, schedule, or decide, and it does not itself coordinate; it *enables* coordination by being the shared tuple substrate. (The medium is a logical rendezvous, which may itself be distributed; it provides a shared catalog, not global agreement — consensus, where needed, is layered on top.)

Membership is **dynamic**: opening a member log (`ds/open!` through the handle) *is* joining the space; closing it leaves. Every writer owns its **own** log, so writes never contend or need routing — there is no shared write cursor to merge. The space is heterogeneous along two axes: member logs differ in **dimension/type** (one carries only d5, another only d10), and in how strict they are (some enforce their type on write, some accept anything). Reads range over all member logs at once; an **interpreter** is the read-side projection that folds those logs into a queryable view — its materialization of a stream is a **slice**: the stream quotiented by an interpreter-defined equivalence relation.

The short answer to "typed or typeless" is: **typeless substrate, typed streams, typed views.** Dimension and slot-type are the stream's intrinsic write-side shape; equivalence-class slicing is the interpreter's read-side view. The two are orthogonal: one typed stream supports many slicings.

This design builds DaoSpace on top of DaoStream (append-only datom logs) and indexes those logs into its own native Datalog query engine. The result is:

- **Declarative coordination** — agents describe what they need, not who has it
- **Implicit messaging** — agents don't need to know about each other
- **Full queryability** — Datalog unlocks complex pattern matching
- **Persistent history** — the entire coordination log is available for replay, debugging, and auditing
- **Persistent and portable** — member logs are append-only files with portable datom framing; history survives restart and replays

## Core Concept: A Catalog of Tuples; a Medium for Resources and Interpreters

```
DaoSpace = a collection of file-backed dao.streams (member logs) whose union of
           tuples is a catalog, opened as a handle, written by ds/put! to a member
           log and read by dao.space/q / dao.space/match
```

The **unit of coordination is the tuple** (a datom): everything else is transport,
reading, or addressing *of tuples*. Structurally the space is the **collection** of
file-backed member logs whose union of tuples is the **catalog**; a **resource** is
any coherent bundle of those tuples — data, a dao.stream descriptor, yin.vm AST,
schema, provenance (see *Resources* below) — so the catalog of tuples is implicitly
a catalog of resources. Tuples enter the catalog over a **dynamic membership** of
member logs (`1..n`, each opened by `ds/open!` through the handle, joining and
leaving at runtime), and are read by **interpreters** (`1..m`) — `dao.space/q` and
`dao.space/match` are the built-in two — that fold the member logs back into
meaning or behavior.

Two things keep "catalog" precise: it is **accreting**, not static — tuples
accumulate append-only with history (`as-of`), added by inputs over time; and it is
**interpreted, not a single stored table** — there is no one materialized table,
the catalog is the union of the member logs' tuples realized on demand into
slices/indexes by interpreters (consistent with "interpretation is external"). The
catalog is content-addressed, so the geometry's base `B` is exactly "the catalog
indexed by tuple identity." Each
stream is typed by dimension (the tuple's shape) and optionally slot types, and
carries a per-stream conformance policy; each interpreter produces a slice (tuples
quotiented into equivalence classes) it reads against. The tuples are **named,
n-dimensional** datoms (d1/d3/d5/d10), so a tuple carries its own meaning —
coordination never depends on field order (unlike Linda's positional tuples; see
*Design Lessons from Linda*). Two heterogeneity axes:

- **Dimension/type** — a given stream is homogeneous in dimension (all d5, or all
  d10) and may further constrain slot types. Different streams in the same space
  may carry different dimensions.
- **Strictness** — some streams enforce their declared type on `put!`; others
  accept anything and leave interpretation to readers.

**Writing is appending to a member log.** Datoms enter a DaoSpace *only* by being
appended to one of its member dao.streams. You open a member log through the space
handle (`(ds/open! {:type :dao-stream :space space ...})`) and append with that
stream's own `put!`. Each `ds/open!` returns a **distinct** `dao.stream.file` with
its **own** append-only log, so writers never contend and there is nothing to
route. A strict member conforms on `put!`; an open member appends as-is. Throughout
this document the write is `(ds/put! log datom)`, where `log` is a member stream the
agent opened once (`ds` aliases `dao.stream`).

**Writing is streaming; reading is querying.** DaoSpace adds exactly two read verbs
of its own — `dao.space/q` (Datalog) and `dao.space/match` (datom pattern) — both
ranging over *all* member logs. There is no reading the space *as* a stream: no
space-level `next`, no cursor over the space, no query-as-stream. Member streams
(`dao.stream.file`, opened via `ds/open!`) carry datoms **in**; the space answers
questions **out**. See *API* below.

### What Agents See

```clojure
;; Agent perspective:
(let [space (dao.space/open! "work-queue")            ; handle to the collection
      log   (ds/open! {:type :dao-stream :space space})] ; my own member write log

  ;; 1. Query: "What work is available?" (?work is a stamped id when read back)
  (dao.space/q '[:find ?work ?task
                 :where [?work :a :work/posted]
                        [?work :work/task ?task]
                        (not [_ :work/claims ?work])]
    space)

  ;; 2. Claim work — append a NEW group in my log that REFERENCES the work.
  ;;    Entity ids are stream-local, so I never write the producer's :db/id;
  ;;    I carry ?work (the stamped id) as a value (see "Entity Identity Is
  ;;    Stream-Local").
  (ds/put! log
    {:db/id (random-id)            ; my own local handle
     :a :work/claim
     :work/claims work             ; stamped ref to the producer's entity
     :work/by my-id
     :work/at (now)})

  ;; 3. Complete work — append another group referencing the work.
  (ds/put! log
    {:db/id (random-id)
     :a :work/result
     :work/for work
     :work/result result}))
```

### What Happens Internally

```
Agent 1 writes → its member log appends a [e :a :work/claim ...] group (datom frames to file)
Agent 2 queries → dao.space/q stamps each log's e to [stream-ns offset], folds into a dao.db → returns matches
Agent 3 queries → re-folds the (now-larger) member logs → sees Agent 1's group
```

A write lands on the writer's own member log. If that log is strict, the append is
type-checked first; if it is open, the datom is appended as-is. A read folds the
datoms of *every* member log into a `dao.db` value and answers against it; because
folding is order-insensitive (transacting commutes; `t` carries time), no
cross-stream cursor merge is ever needed.

## Architecture

```
            dao.space/open! ──► SPACE HANDLE (collection of member logs)
                                   │            ▲
            ds/open! {:space …}    │            │  dao.space/q   (Datalog)
            opens a member log ◄───┘            │  dao.space/match (pattern)
                                                │  fold ALL member logs → dao.db
┌──────────────────┐  ┌──────────────────┐      │
│ member log A     │  │ member log B     │ ...  │   (1..n member logs; each its
│ d5, strict       │  │ d10, open        │──────┘    own append-only file log,
│ ds/put! frames → │  │ ds/put! frames → │           typed by dimension, strict?)
└────────┬─────────┘  └────────┬─────────┘
         │ datom frames (self-delimiting byte records)
┌────────▼─────────┐  ┌────────▼─────────┐
│ dao.stream.file  │  │ dao.stream.file  │  ...  (byte stream: async disk,
│ (byte substrate) │  │ (byte substrate) │       non-blocking — see dao.stream.file.md)
└──────────────────┘  └──────────────────┘
```

Three layers: **`dao.stream.file`** (a non-blocking byte stream over a file) →
**datom framing** (each datom serialized as a self-delimiting byte record, written
through `put!`) → **`dao.space`** (the collection, plus `q` / `match` folding the
framed datoms of every member log into a `dao.db`).

## Resources: Anything Expressible as Tuples

The medium does not privilege any particular kind of participant. A **resource** is
*anything that can be expressed as datoms*, and that is the only requirement to
live in a DaoSpace and be coordinated over. This is the operational form of
datom.world's core philosophy ("everything is data, code is data, runtime state is
data"; datoms are the universal format for DaoDB, AST, schema, provenance —
`datom-spec.md`).

Three roles, and "resource" is the general one:

- **Representation — the tuple (datom).** The universal unit; the only thing the
  medium actually holds.
- **Resource — a coherent bundle of tuples.** What is being coordinated: a dataset,
  a dao.stream descriptor, a yin.vm AST, a schema, a provenance record, an agent's
  belief state.
- **Realization / interpretation — tuples back into behavior.** An interpreter
  turns a resource's tuples into meaning or action.

Two canonical resources show the range:

- **A dao.stream is a resource.** Its descriptor is plain data, hence a datom, so a
  stream can live *in* the space (not only attach *to* it) — this is the
  channel-mobility property of `dao.stream.md` ("streams are values that can be sent
  through streams"). An interpreter *realizes* a stream-descriptor resource by
  `ds/open!` into a live transport.

  ```clojure
  ;; A stream descriptor is a datom-resource; realizing it = open!
  ;; (illustrative — resource->descriptor / read-resource are not yet implemented;
  ;;  see the implementation-status note under Geometry for the same caveat.)
  (ds/open! (resource->descriptor (read-resource space stream-eid)))
  ```

- **yin.vm bytecode is a resource.** Code is data: an AST reduces to content-addressed
  datoms (`ast->datoms`, `src/cljc/yin/vm.cljc`; see datom-spec's AST-as-datoms).
  So agents coordinate over *code* exactly as over data. An interpreter *realizes* an
  AST resource by evaluating it in `yin.vm` ("agents are functions, closures, or
  continuations in Yin.VM").

Because every resource is tuples, every resource is **content-addressed** (the
geometry's base `B` is the content-hash identity of *any* resource), so resources
have stable identity, dedup, and provenance for free — modulo the implementation
status noted under *Geometry*. The medium is thus **homoiconic and reflective**:
data, channels, and code coexist as tuples in one space, each realizable by some
interpreter. A dao.stream is special only in being *both* a transport for tuples
*and* a resource.

## Typed Streams and Conformance

Each member log declares its **dimension** and, optionally, its **slot types**.
This declaration lives in the stream's kickoff metadata: a stream is identified by
the hash of `(creator, schema, dimension, t-zero)` (see
`docs/agents/datom-spec.md`, CONTENT ADDRESSING > Streams), so dimension and
schema are already first-class properties of a stream's birth, not something
DaoSpace bolts on.

A stream is **homogeneous in dimension**: a given stream carries only d5, or only
d10, etc. It may further constrain the size/type of each slot. This is the
typed / fixed-size stream case from `datom-spec.md` (d5 > Sizing):

```clojure
;; A d5 stream with fixed slot types (cache-efficient, O(1) indexing, SIMD-friendly)
{:dimension :d5
 :slots {:e :int64 :a :keyword :v :hash :t :int64 :m :int64}}
```

Conformance is a **per-stream policy**, carried in the stream descriptor much like
`:eviction-policy` in `docs/design/dao.stream.md`. Proposed boolean field `:strict?`:

- `true` — the stream validates each datom against its declared dimension and
  slot types on `put!`, rejecting non-conforming datoms. `put!` becomes partial,
  but downstream interpreters get conformance for free, which is what unlocks
  fixed-size layouts, O(1) indexing, and SIMD.
- `false` — the stream accepts anything; `put!` stays total. The declared type is
  a contract in the kickoff metadata, not a gate. Each interpreter decides how to
  handle what it reads (validate, coerce, project, or skip).

```clojure
;; Strict: rejects datoms that are not conforming d5
(def ledger (ds/open! {:type :dao-stream :space space :name "ledger"
                       :dimension :d5 :slots {...} :strict? true}))

;; Open: takes anything; interpreters cope on read
(def ingest (ds/open! {:type :dao-stream :space space :name "ingest"
                       :dimension :d5 :strict? false}))
```

Both policies are first-class. A single space routinely mixes strict member logs (a
ledger, a schema-checked work queue) with open member logs (raw ingestion, untrusted
external feeds). The byte substrate (`dao.stream.file`) stays dumb either way:
conformance is an optional realization concern at the member-log boundary, not a
property of the transport.

## Interpreters and Equivalence-Class Slicing

A stream is the write-side shape. The read-side view belongs to **interpreters**,
and there can be many per stream. Each interpreter materializes the stream into a
**slice**: the stream quotiented by an interpreter-defined **equivalence
relation**. "Type" on the read side is just "which equivalence relation am I
looking through."

Because reads are non-destructive and cursors are independent, an interpreter's
slice does not consume or mutate the stream. The same datom belongs to many
equivalence classes at once, depending on who is observing — the
quantum-measurement metaphor from `datom-spec.md` made operational: same data,
different projections, simultaneously.

Two universal equivalence relations come for free and interpreters build on them
(see `datom-spec.md`):

- **d1 floor** — equal iff same content hash `hash(dimension-hash ‖ slots)`. The
  finest, dimension-aware identity.
- **d3 floor** — equal iff same projection to `(s, a, v)`. Coarser; collapses d5
  provenance and time.

Domain interpreters add their own relations: alpha-equivalence (via the derived
`:yin/alpha-hash` datom in `datom-spec.md`), "same customer," "same topic," a
projection to a coarser dimension, and so on. Each relation slices the same
typed stream a different way.

```clojure
;; Two interpreters slicing the SAME datoms differently — each is just an
;; equivalence relation applied while folding a member log's datoms:

;; Interpreter 1: slice by entity (classic EAVT view)
(group-by (fn [d] (:e d)) datoms)

;; Interpreter 2: slice by content identity (dedup distinct facts)
(group-by content-hash datoms)
```

(`dao.space/q` is the general read interpreter; a custom slice is any fold of the
member logs' datoms under a chosen equivalence relation.)

## Querying Across Streams

A `dao.space/q` ranges over all member logs. Because each member log is
dimension-homogeneous but the space is heterogeneous, cross-log joins happen on
**shared values, not entity IDs** — entity IDs are stream-local gauges (see
`datom-spec.md`, d5 > NAMESPACES > Cross-Namespace Queries). This reuses Datomic's
multiple-database pattern: each member log is an explicit input, joined on the
values they have in common.

```clojure
;; Join a d5 ledger log against a d10 sensor log on a shared value
(dao.space/q
  '[:find ?account ?reading
    :in $ledger $sensors
    :where [$ledger  ?a :account/id ?id]
           [$sensors ?s :sensor/account ?id]
           [$sensors ?s :sensor/reading ?reading]
           [$ledger  ?a :account/name ?account]]
  space {:inputs {'$ledger "ledger" '$sensors "sensors"}})  ; bind names → member logs
```

## Entity Identity Is Stream-Local

Entity IDs are **local to a member log, not to the space.** This is the gauge
picture taken literally: `e` is a fiber coordinate that never crosses a stream
boundary as a reference (see `datom-spec.md`, d5 > Components > `e`). The
consequences shape every coordination pattern.

- **The stream is the namespace.** A stream's identity (its kickoff hash) names
  its namespace. Inside a stream only the bare 64-bit offset is stored — cheap,
  fixed-size, no UUID — and the namespace is implied by *which* stream you read.
- **Stamp on crossing.** The instant an `e` leaves its authoring stream — folded
  for a `q`/`match`, or referenced as a `v` in another stream — it is stamped to
  `[stream-ns offset]`. You pay the namespace cost only at the boundary.
- **Fold stamps, never unifies.** `dao.space/q` stamps each member log's datoms
  with its stream namespace, then transacts. Two logs' bare offset `1025` become
  distinct entities, never merged. "Same logical entity across streams" is a
  **read-side** concern, not a storage one.
- **`:db/id` is intra-stream only.** Never coordinate by writing another stream's
  `:db/id`. Coordinate across streams by **shared value** — type matching
  (`[?e :a <type>]`), Datalog joins on common values, or a read-side equivalence
  relation (group-by a shared key). Cross-group *references* carry the stamped id.

Two idioms for advancing state across agents; **Style A is preferred** (it is the
`dao.flow` idiom, see `docs/design/dao.flow.md`):

- **Style A — append a new typed group that references the prior one.** No entity
  is mutated across streams; the state machine is a chain of immutable groups
  linked by stamped references, and "current state" is a read-side fold of the
  chain. Recommended.
- **Style B — tag each group with a shared value key and reconcile on read.**
  Both agents write their own entities carrying e.g. `:work/id "W1"`; a reader
  groups by `:work/id` and reconciles (latest-by-`t`, sum, conflict). Also valid.

The **recommended idiom** (not a hard rule) is that each entity has a single
authoring stream: evolution is either single-writer in one stream, or modeled as
new typed groups (Style A). Cross-stream stays reference-only and read-only.

## Geometry: Streams as Gauges over Content

### Mathematical summary

Stripped of metaphor, the structure is set-theoretic and categorical:

- Let `E` be the set of all datoms (immutable tuples) and `B` the set of content
  hashes. The **content projection** `π: E → B` sends each datom to its hash.
- A **stream** is a subset `S ⊆ E` equipped with a total order (append order) and
  a **gauge**: an assignment of local coordinates `(e, t, namespace)` to each
  element.
- Two datoms `x, y` with `π(x) = π(y)` are **gauge-equivalent representations** of
  the same invariant fact. A **migration** between streams is a base-preserving
  bijection `f: S₁ → S₂` with `π∘f = π` (relabels local coordinates, preserves
  invariant content).
- An **interpreter** defines an equivalence relation `~` on `E`; the **slice**
  `E/~` is the quotient set. A **DaoSpace** is a dynamic medium of attached
  streams, all sharing the base `B`, queried via Datalog joins on shared base
  values.
- **Typing is fiber uniformity.** `:strict? true` ⇒ the restriction `π|_S` is a
  fibered set with **constant fiber** (fixed arity and slot types); `:strict?
  false` ⇒ **variable fiber**.

This is the spec's own framing: datoms are "tuples in a moduli space, graded by
dimension n" (`datom-spec.md` line 7), with content hash as gauge-invariant
identity and entity IDs as local coordinates. Here "dimension n" is that moduli
grading (arity + encoding + morphisms), not a vector-space or manifold dimension.
The framing is not decorative: it is made concrete, with non-trivial content on
the discrete datom set, by the spectral-triple construction in
`docs/design/dao.space.discrete-to-continuous.md` (gauge groupoid → C*-algebra →
Gelfand-Naimark → Dirac operator).

### The gauge picture

The summary above is the gauge/fiber-bundle language `datom-spec.md` commits to:
"Entity ID is a local gauge", "the gauge-invariant identity is the content hash",
"migration is a gauge transformation". Made explicit:

| Bundle notion         | datom.world                                                                     |
|-----------------------|---------------------------------------------------------------------------------|
| Base space `B`        | gauge-invariant content (the d1 hash floor / d3 `(s,a,v)` projection)           |
| Total space `E`       | the actual datoms, carrying local coordinates `(e, t, namespace)`               |
| Projection `π: E → B` | "forget the local gauge" = take the content hash                                |
| Fiber `π⁻¹(b)`        | all local-coordinate representations of one invariant fact                      |
| A **dao.stream**      | a **partial section** (`s: U → E`, `π∘s = id` over `U ⊆ B`) plus order and time |
| Gauge group `G`       | base-preserving bijections `{f: E→E ∣ π∘f = π}`; concretely the entity-ID group |

So a dao.stream is a gauge choice over invariant content (the principal-bundle
picture), and **DaoSpace is the assembly of those partial sections glued along the
shared base**. That gluing is exactly why cross-stream joins happen on shared
values, not entity IDs (see *Querying Across Streams*): joins live in the base,
because entity IDs are fiber coordinates that do not commute across streams.

The gauge group `G` is concrete, not vague: per `datom-spec.md` an entity ID is a
128-bit `[namespace offset]` value, so `G` is generated by zero-basis shifts on
the 64-bit offset (migration rebases entity IDs) together with relabelings of the
64-bit namespace. Since the two components act independently, this is a direct
product (not semidirect). Its action on a fiber permutes the local-coordinate
representations of one invariant fact while fixing the content hash.

**Typing is the local-triviality condition.** It is a genuine fiber bundle
(constant fiber `F`, local triviality) only when the fiber is uniform, which is
exactly the `:strict? true` case:

- `:strict? true` (fixed slots) — every fiber has the same shape `F`, the slot-type
  tuple `{:e :int64 :a :keyword :v :hash …}`. Constant fiber, local triviality.
  "O(1) indexing / fixed-size layout / SIMD" is the statement that the
  trivialization is global and computable.
- `:strict? false` (open) — fibers vary in shape. Not a fiber bundle but a
  **fibered set with variable fibers** over the base. Still fibered, no constant
  fiber. (Not a sheaf: that would require a Grothendieck topology on the base and
  verified gluing axioms, which are not specified here. The spec's derived-datom
  hash-consistency is a genuine gluing-like condition, but its site is left
  implicit. Supplying the gluing metadata — the transition/cocycle data by which
  interpreters reassemble a consistent global view — is exactly what upgrades this
  fibered set to a sheaf; see *Descent* in `dao.space.discrete-to-continuous.md`.)

So typing is not incidental to this reading: it is the condition under which the
fibration becomes a locally trivial bundle.

**Interpreters re-fiber the same total space.** An interpreter's equivalence
relation is a different projection `π' : E → E/∼` on the same datoms. A stream
therefore carries many fibrations at once: the canonical gauge projection (to
content) plus one per interpreter (alpha-equivalence, "same customer", a coarser
dimension). These are different quotients of one total space, coexisting via
independent cursors: the same element lies in different equivalence classes under
different relations. Under the spectral-triple realization this is literally the
Heisenberg picture: a fixed state in `ℓ²(E)` read by different (possibly
non-commuting) observables, where incompatible interpreters cannot be applied
simultaneously (see `docs/design/dao.space.discrete-to-continuous.md`).

### Where the geometry gets its content: the Dirac operator

Curvature, holonomy, and spectral distance are not vacuous on a discrete set; they
become concrete once a **spectral triple** `(A, H, D)` is fixed, following Connes
non-commutative geometry. The construction is carried out in
`docs/design/dao.space.discrete-to-continuous.md`: `H = ℓ²(E)`, `A` is the
C*-algebra of the gauge groupoid (`e₁ → e₂` when `π(e₁) = π(e₂)`), and `D` is a
self-adjoint operator encoding which relationships matter. In that framework the
`:strict?` distinction is a theorem, not an analogy: strict streams give a
**free (globally trivial)** module, which admits the trivial flat connection
(`F = 0`, locally trivial bundle); open streams give a non-free module whose
curvature `F = [D,[D,·]]` measures the obstruction to a consistent global gauge
(you cannot assign entity IDs uniformly when the fiber shape varies). The
discriminating property is freeness, not mere projectivity: projective modules
admit a connection but are generally curved (the canonical NCG line bundles are
projective with `F ≠ 0`).

The one genuinely open design decision is **the choice of relationship graph**
(hence `L`, with the first-order Dirac `D` derived via `D² = L`). It sets the
scale of every geometric quantity: the tentative choice is the Laplacian `L` of
the relationship graph (edges connect datoms sharing entity IDs, provenance, or
interpreter equivalence), which yields spectral distance and spectral clustering
over the tuple space. Different graphs encode different "what relationships
matter" and so realize different geometries. Until the graph is committed here,
the geometric claims above are correct but their magnitudes are unset: the live
question is **what graph (what `D`)?**

**Implementation status (documentation debt).** This geometry takes the
content-hash as the base `B` of the fibration (the gauge-invariant identity).
That base is only **partially realized** in code today: `src/cljc/yin/content.cljc`
computes Merkle content hashes over `[a v]` pairs for AST/d5 entities, but (a) it
hashes via `pr-str`, not the canonical byte encoding `datom-spec.md` mandates, so
it is not yet the portable identity; and (b) `dao.db` still allocates entity IDs
sequentially, so the content hash is not yet load-bearing as the identity base.
The full geometric program assumes the canonical d1 content-addressing floor;
prioritizing that implementation is a prerequisite for treating these claims as
operational rather than design-level.

## Stigmergic Coordination Patterns

### Pattern 1: Work Queue

```clojure
;; Agents cooperate via a shared work queue. Entity IDs are stream-local, so an
;; agent never writes another stream's :db/id (see "Entity Identity Is
;; Stream-Local"). State advances by APPENDING a new typed group that references
;; the work (Style A); "current state" is a read-side fold over the groups that
;; reference it. No entity is mutated across streams.

(defn producer [space]
  (let [log (ds/open! {:type :dao-stream :space space :name "producer"})]
    ;; Post a work group. :db/id is the producer's own local handle; readers see
    ;; it stamped as [producer-ns offset].
    (ds/put! log
      {:db/id (random-id)
       :a :work/posted
       :work/task "process payment"})))

(defn worker [space worker-id]
  (let [log (ds/open! {:type :dao-stream :space space :name worker-id})]
    (loop []
      ;; Find posted work that nothing has claimed yet (folded space stamps ids).
      (let [work (dao.space/q '[:find ?work ?task
                                :where [?work :a :work/posted]
                                       [?work :work/task ?task]
                                       (not [_ :work/claims ?work])]
                   space)]
        (when (seq work)
          (let [[work task] (first work)]    ; ?work is the stamped [producer-ns offset]
            ;; Claim: append a NEW group in MY log that references the work.
            (ds/put! log {:db/id (random-id) ; my own local handle
                          :a :work/claim
                          :work/claims work  ; stamped ref to the producer's entity
                          :work/by worker-id
                          :work/at (now)})

            ;; Complete: append another group referencing the work.
            (let [result (process task)]
              (ds/put! log {:db/id (random-id)
                            :a :work/result
                            :work/for work
                            :work/result result})))
          (recur))))))

;; Read-side: "done" work = posted work that has a :work/result group referencing
;; it. The chain of groups is the state machine; no :db/id crossed a stream.
;; (Style B is also valid: tag each group with a shared :work/id key and reconcile
;;  by latest-by-t on read.)
```

### Pattern 2: Stigmergic Search (Ant Colony Optimization)

```clojure
;; Agents leave pheromone traces for others to follow

(defn ant [space colony-id]
  (let [log (ds/open! {:type :dao-stream :space space :name colony-id})]
    (loop [path [] node (random-node)]
      ;; 1. Emit pheromone: "I visited this node"
      (ds/put! log
        {:db/id (random-id)
         :pheromone/colony colony-id
         :pheromone/node node
         :pheromone/strength 1.0
         :pheromone/time (now)})

      ;; 2. Query pheromones: "Where should I go next?"
      (let [neighbors (dao.space/q '[:find ?next ?strength
                                     :where [?node :graph/neighbor ?next]
                                            [?p :pheromone/node ?next]
                                            [?p :pheromone/strength ?strength]]
                        space)]

        ;; 3. Follow high-pheromone paths with probability
        (if-let [next (choose-by-weight neighbors)]
          (recur (conj path next) next)
          ;; Solution found
          (when (is-food? node)
            ;; Emit success trace
            (ds/put! log
              {:db/id (random-id)
               :solution/path path
               :solution/cost (count path)})))))))
```

### Pattern 3: Watchdog / Exception Handling

```clojure
;; Agents monitor state and react to anomalies

(defn watchdog [space timeout-seconds]
  (let [log (ds/open! {:type :dao-stream :space space :name "watchdog"})]
    (loop []
      ;; Query: work claimed too long ago with no result group yet.
      ;; ?work is the stamped id of the posted work; ?claim is the claim group.
      (let [stuck (dao.space/q '[:find ?work ?claimed-at ?worker
                                 :where [?claim :a :work/claim]
                                        [?claim :work/claims ?work]
                                        [?claim :work/by ?worker]
                                        [?claim :work/at ?claimed-at]
                                        (not [_ :work/for ?work])       ; no result yet
                                        [(elapsed-seconds ?claimed-at) ?elapsed]
                                        [(> ?elapsed ?timeout)]]
                    space)]

        ;; Reassign — append a NEW group REFERENCING the work, never mutate the
        ;; producer's :db/id across logs (entity ids are stream-local).
        (doseq [[work claimed-at worker] stuck]
          (ds/put! log
            {:db/id (random-id)
             :a :work/reopened
             :work/reopens work             ; stamped ref to the posted work
             :work/reassigned-from worker
             :work/reason :timeout}))

        ;; A worker treats work whose latest lifecycle group is :work/posted or
        ;; :work/reopened as claimable again — reconciliation is the read-side
        ;; interpreter's job (see "Entity Identity Is Stream-Local").
        (Thread/sleep (* timeout-seconds 1000))
        (recur)))))
```

### Pattern 4: Collaborative Filtering

```clojure
;; Agents collectively build and improve models

(defn agent-observer [space agent-id]
  (let [log (ds/open! {:type :dao-stream :space space :name agent-id})]
    (loop [last-t 0]
      ;; 1. Observe recent events
      (let [events (dao.space/q '[:find ?e ?action ?time
                                  :where [?e :event/action ?action]
                                         [?e :event/time ?time]
                                         [(> ?time ?last-t)]]
                     space)]

        ;; 2. Update local belief
        (doseq [[e action time] events]
          (update-model! agent-id action))

        ;; 3. Emit shared insight if found
        (when-let [pattern (detect-pattern agent-id)]
          (ds/put! log
            {:db/id (random-id)
             :pattern/discovered-by agent-id
             :pattern/rule pattern
             :pattern/confidence (calculate-confidence agent-id)}))

        ;; 4. Read and apply patterns from other agents
        (let [patterns (dao.space/q '[:find ?rule ?author
                                      :where [?p :pattern/rule ?rule]
                                             [?p :pattern/discovered-by ?author]
                                             [(> ?author ?agent-id)]]
                         space)]
          (doseq [[rule author] patterns]
            (apply-pattern! agent-id rule)))

        (recur (+ 1 last-t))))))
```

## API

The surface is small and asymmetric: **`dao.space/open!`** returns a handle;
**`dao.stream/open!`** (through the handle) opens member write logs you `ds/put!`
into; **`dao.space/q`** and **`dao.space/match`** read across all member logs.
Member-log realization rides the same `dao.stream/open!` multimethod as every other
stream, so there is no second realization mechanism — a space *delegates* to
`ds/open!` and just tracks the result.

### Opening a space

```clojure
;; A space is a collection of file-backed member logs. The handle is both a
;; factory (for member logs) and the read target (for q / match). The path is
;; where the member logs live on disk; reopening rediscovers existing members.
(def space (dao.space/open! "work"))                 ; name → default location
(def space (dao.space/open! {:path "/data/work"}))   ; explicit location
```

### Writing — open a member log, `ds/put!` datom frames

A datom enters the space by being appended to a **member log** the agent opens
through the handle. Each `ds/open!` returns a **distinct** `dao.stream.file` with
its own append-only log; serialization to self-delimiting datom frames is handled
by the member log (see `docs/design/dao.stream.file.md` for the byte substrate).
Conformance lives at this boundary: a strict member (`:strict? true`) validates and
may reject; an open member appends as-is.

```clojure
;; Open your member log once, then append to it.
(def log (ds/open! {:type :dao-stream :space space :name "agent-1"}))
(ds/put! log {:db/id 42 :work/status :completed :work/result "OK"})

;; Retraction is also just an append — a datom with retract metadata (m = :db/retract)
(ds/put! log [:db/retract work-id :work/status])
```

### Reading — `dao.space/q` and `dao.space/match`

Reads never stream the space. Both verbs fold the datoms of **every** member log
into a `dao.db` value and answer against it — a point-in-time snapshot taken when
the read runs (each read re-folds, so it sees everything flushed so far).

```clojure
;; q — full Datomic-compatible Datalog (joins, aggregation, predicates).
(dao.space/q '[:find ?id ?task
               :where [?id :work/status :todo]
                      [?id :work/task ?task]]
  space)
;; => #{[id task] ...}   (a set, per dao.db/q)

;; match — a positional/value datom template (Linda-style), lighter than q.
;; A leading wildcard (_) is unbound; given components filter by position+value.
(dao.space/match space [_ :work/status :todo])   ; => matching datoms

;; as-of — a read bound, not a verb. `point` is a transaction t or an instant;
;; the read folds each member log only up to that point.
(dao.space/q query space {:as-of t})
(dao.space/match space pattern {:as-of (instant "2024-01-01")})
```

### Membership is open/close (no separate verbs)

Opening a member log through the handle *is* joining the space; closing it leaves.
There is no `attach`/`detach`: `ds/open! {:type :dao-stream :space space ...}` adds a
member, `ds/close!` on that stream removes it. `(dao.space/close! space)` closes the
whole collection (all member logs).

## Coordination Semantics

### Consistency

(In these snippets `log` is a member write stream the agent opened once via
`(ds/open! {:type :dao-stream :space space ...})`.)

**Read-your-writes within a single-threaded agent — with a durability caveat:**

```clojure
;; Agent's view
(ds/put! log {:db/id id :work/status :in-progress})
;; Visible in a subsequent query — once flushed to disk (see Eventual Durability).
(= :in-progress (:work/status (dao.space/q '[...] space)))
```

Because `dao.space/q` re-folds the member logs from disk, read-your-writes holds
only after the member log's async disk flush completes (`dao.stream.file` is
eventually durable; a future `flush!`/`sync!` would make this immediate).

**Eventual consistency across agents:**

```clojure
;; Agent 1 writes to its own log
(ds/put! log {:db/id id :work/status :in-progress})

;; Agent 2's view depends on whether that write is flushed when it queries
(if (write-flushed? id)
  (assert (= :in-progress (dao.space/q '[...] space)))
  (assert (not= :in-progress (dao.space/q '[...] space))))
```

### Ordering

**Within an agent's own log:**
- Writes to one member log are totally ordered (append-only)
- A `dao.space/q` snapshot folds all member logs consistently at read time
- Re-querying later includes later (flushed) writes

**Across agents (separate logs):**
- No global ordering across logs — each log orders only its own appends
- Reads fold all logs; the fold is order-insensitive, so a query result reflects
  the *set* of facts, not an interleaving
- Cross-log causality is preserved via timestamps in tuples (application's responsibility)

**Example: two agents asserting about the same logical entity (in separate logs)**

```clojure
;; Entity IDs are stream-local, so a shared :db/id does NOT name one entity across
;; logs. Coordinate on a shared VALUE key instead (Style B; see "Entity Identity
;; Is Stream-Local").

;; Agent A, in log-a, at t=100
(ds/put! log-a {:db/id (random-id) :counter/id "c1" :counter 1})

;; Agent B, in log-b, at t=101
(ds/put! log-b {:db/id (random-id) :counter/id "c1" :counter 2})

;; The fold stamps each log's ids, so these are two distinct entities that share
;; :counter/id "c1". A read-side interpreter groups by :counter/id and reconciles
;; (last-write-wins by t? sum? conflict?) — reconciliation is application-defined.
```

### Atomicity

**Single tuples are atomic:**
```clojure
(ds/put! log {:db/id id :work/status :in-progress :worker agent-id})
;; Appears atomically to other agents
```

**Multi-tuple transactions need application-level coordination:**
```clojure
;; NOT atomic across the space:
(ds/put! log {:db/id id :work/status :in-progress})
(ds/put! log {:db/id id :work/assigned-to agent-id})

;; Remedies:
;; 1. Single tuple with multiple attributes
(ds/put! log {:db/id id :work/status :in-progress :work/assigned-to agent-id})

;; 2. Transaction envelope (application-defined)
(ds/put! log
  {:db/id (random-id)
   :transaction/id tx-id
   :transaction/committed true
   :transaction/datoms [{:db/id id :work/status :in-progress}
                        {:db/id id :work/assigned-to agent-id}]})

;; 3. Causality tracking (application-defined)
(ds/put! log {:db/id id :version 1 :work/status :in-progress})
(ds/put! log {:db/id id :version 2 :work/assigned-to agent-id
              :depends-on 1})
```

## Implementation

### What's Already Available

- **DaoStream** (`src/cljc/dao/stream.cljc`): append-only datom log
- **IDaoStreamWaitable**: transport-local notifications when data arrives
- **Cursors**: independent read positions for multiple agents

### What Needs to Be Built

Three layers, bottom-up:

1. **`src/cljc/dao/stream/file.cljc`** — `dao.stream.file`, a non-blocking byte
   stream over a file (async disk writes, snapshot-on-open reads). Specified in
   `docs/design/dao.stream.file.md`. The byte substrate; carries no datom knowledge.

2. **Datom framing** — serialize each datom to a *self-delimiting byte record*
   (EDN form or length-prefixed transit, per the dao.stream complexity boundary:
   use a library, not a hand-rolled format) and `put!` it through `dao.stream.file`;
   on read, split the concatenated bytes back into datoms by the framing. The
   framing must not assume `chunk == datom` (a frame may straddle file chunks).

3. **`src/cljc/dao/space.cljc`** — `dao.space`, the collection + read verbs.

```clojure
(ns dao.space
  "DaoSpace: a collection of file-backed dao.stream member logs. open! returns a
   handle that opens member write logs (delegating to dao.stream/open!) and answers
   queries (q / match) by folding all member logs into a dao.db value."
  (:require [dao.stream :as ds]
            [dao.db :as db]
            [dao.db.in-memory :as in-mem]))

;; dao.space/open! — returns a DaoSpace handle (a collection of file-backed logs).
;; Delegates realization to dao.stream/open!; reopening a :path rediscovers members.
(defn open! [name-or-descriptor] ...)               ; => DaoSpace handle

;; :dao-stream — a member write log, realized through dao.stream/open!. Carries the
;; :space so the realized dao.stream.file (+ datom framing) registers as a member.
;; Returns a write stream; agents ds/put! datom frames to it.
(ds/defopen :dao-stream [{:keys [space name dimension slots strict?]}] ...)

;; Read verbs — fold every member log's datoms into a dao.db, then answer.
(defn q [query space & {:keys [as-of inputs]}])     ; Datomic Datalog → set of tuples
(defn match [space pattern & {:keys [as-of]}])      ; positional datom template → datoms

;; Lifecycle.
(defn close! [space])                               ; close all member logs
```

Member-log realization rides the same `dao.stream/open!` multimethod as every
stream, so there is no second realization mechanism — `dao.space/open!` and the
`:dao-stream` member both **delegate** to `ds/open!`. Conformance is the **member
log's** responsibility (a strict member conforms inside its own `put!`).

### Reference Implementation Pattern

```clojure
;; The space handle. NOT a stream: it is not read with next/cursor. It tracks its
;; member logs and answers q / match by folding them into a dao.db. members is a
;; mutable ref, grown as agents open member logs and shrunk as they close.
(deftype DaoSpace [path members]   ; members: atom of {name -> realized member log}
  ;; Bound only — close! tears down the whole collection. No reader, no writer.
  ds/IDaoStreamBound
  (close! [_] (run! ds/close! (vals @members)))
  (closed? [_] (every? ds/closed? (vals @members))))

;; A member log is opened through dao.stream/open!, which registers it in members.
(ds/defopen :dao-stream [{:keys [space] :as desc}]
  (let [log (open-file-member desc)]               ; dao.stream.file + datom framing
    (swap! (.-members space) assoc (:name desc) log)
    log))

;; q / match fold every member log's datoms into a fresh dao.db, then query it.
;; Entity ids are stream-local, so each log's datoms are STAMPED with the log's
;; stream namespace before transacting — otherwise two logs' bare offset 1025 would
;; collide. The fold stamps; it never unifies. "Same logical entity across streams"
;; is a read-side concern (type match / Datalog join / equivalence on shared
;; values), not something the fold reconciles. No global cursor, no merge:
;; transacting stamped datoms commutes, so order does not matter.
(defn- stream-ns [log] ...)   ; the log's namespace = its kickoff hash

(defn- stamp
  "Rewrite a datom's e (and any bare-offset ref in v) to [ns offset]. Schema-aware:
   ref-valued attrs are read from the stream's schema, so only ref v's are stamped.
   Already-stamped refs (values that point into another stream) are left as-is."
  [ns datom] ...)

(defn- fold-db [^DaoSpace space as-of]
  (let [datoms (mapcat (fn [log]
                         (let [ns (stream-ns log)]
                           (map #(stamp ns %) (ds/->seq nil log))))
                       (vals @(.-members space)))]
    (:db-after (db/transact (in-mem/empty-db) (cond->> datoms as-of (filter #(<= (:t %) as-of)))))))

(defn q [query space & {:keys [as-of inputs]}] (db/q query (fold-db space as-of)))
```

**Implementation status (open decision).** The spec commits to `[namespace offset]`
as the stamped form, but `dao.db` allocates integer eids only — so the stamped
(vector) eids `stamp` produces cannot be transacted as-is today. Two paths, not yet
decided: (a) teach `dao.db` namespaced eids as first-class in EAVT/AEVT indexing
(faithful, but a core change), or (b) have `stamp` map each `[namespace offset]` into
a deterministic ephemeral per-fold integer (same mapping in `e`- and `v`-positions so
equality joins hold; the folded `dao.db` is throwaway per read anyway). See *Deferred*.

## Use Cases

### 1. Distributed Work Queue
- **Agents**: workers
- **Tuples**: `{:work/id :work/status :work/assigned-to :work/result}`
- **Patterns**: "find todo items", "find my completed items", "find overdue items"

### 2. Agent Swarm Coordination
- **Agents**: independent agents (bots, particles, etc.)
- **Tuples**: `{:agent/id :agent/position :agent/state}`
- **Patterns**: "neighbors within radius", "highest-scoring agents", "consensus decisions"

### 3. Event Sourcing
- **Agents**: domain services, projections
- **Tuples**: domain events `{:event/type :event/aggregate-id :event/data}`
- **Patterns**: "events for aggregate X", "events since timestamp", "by event type"

### 4. Pub-Sub / Fan-Out
- **Agents**: subscribers
- **Tuples**: published messages `{:message/id :message/topic :message/data}`
- **Patterns**: "all messages on topic X", "unread messages for agent Y"

### 5. Audit Log
- **Agents**: services, compliance systems
- **Tuples**: audit events `{:audit/actor :audit/action :audit/resource :audit/timestamp}`
- **Patterns**: "all actions by user X", "all resource modifications", "compliance queries"

### 6. Collaborative Filtering
- **Agents**: recommendation engines, data collectors
- **Tuples**: observations, models, patterns
- **Patterns**: "recent observations", "high-confidence models", "novelty detection"

## Verification and Testing

### Unit Tests

Note: `dao.space/q` returns a **set** of tuples (per `dao.db/q`), not a vector.

```clojure
(deftest open-space-test
  (testing "open a space and a member log"
    (let [space (dao.space/open! "test")]
      (is (some? space))
      (is (some? (ds/open! {:type :dao-stream :space space :name "a"}))))))

(deftest put-query-test
  (testing "put to a member log, read back via q"
    (let [space (dao.space/open! "test")
          log   (ds/open! {:type :dao-stream :space space :name "a"})]
      ;; :db/id is the stream's own local handle; the fold stamps it, so queries
      ;; bind the entity by a value, never by a literal bare offset.
      (ds/put! log {:db/id (random-id) :work/id "w1" :work/status :todo})
      (is (= #{[:todo]}
             (dao.space/q '[:find ?status
                            :where [?e :work/id "w1"] [?e :work/status ?status]] space))))))

(deftest two-logs-aggregate-test
  (testing "q and match range over every member log; stamping keeps stream-local ids distinct"
    (let [space (dao.space/open! "test")
          a     (ds/open! {:type :dao-stream :space space :name "a"})
          b     (ds/open! {:type :dao-stream :space space :name "b"})]
      ;; Both logs use local offset 1025 (user range); the fold stamps each to
      ;; [stream-ns 1025], so they remain two distinct entities (no collision).
      (ds/put! a {:db/id 1025 :work/task "task-1"})
      (ds/put! b {:db/id 1025 :work/task "task-2"})
      (is (= #{["task-1"] ["task-2"]}
             (dao.space/q '[:find ?task :where [?id :work/task ?task]] space)))
      (is (= 2 (count (dao.space/q '[:find ?id :where [?id :work/task _]] space))))
      (is (= 1 (count (dao.space/match space [_ :work/task "task-1"])))))))

(deftest as-of-test
  (testing "historical query returns state at point in time"
    (let [space (dao.space/open! "test")
          log   (ds/open! {:type :dao-stream :space space :name "a"})]
      ;; In-place evolution is fine WITHIN one stream (same local handle, same log).
      ;; The query still binds by value, since the fold stamps the handle.
      (ds/put! log {:db/id 1025 :work/id "w1" :work/status :todo})         ; t0
      (ds/put! log {:db/id 1025 :work/id "w1" :work/status :in-progress})  ; t1
      (is (= #{[:todo]}
             (dao.space/q '[:find ?status
                            :where [?e :work/id "w1"] [?e :work/status ?status]]
               space {:as-of t0}))))))
```

### Integration Tests

```clojure
(deftest work-queue-pattern-test
  (testing "producer-worker coordination over separate member logs"
    (let [space (dao.space/open! "work-queue")
          plog  (ds/open! {:type :dao-stream :space space :name "producer"})]
      ;; Producer posts work to its own log (each :db/id is the producer's handle)
      (dotimes [i 10] (ds/put! plog {:db/id (random-id) :a :work/posted :work/n i}))

      ;; Worker reads posted work (stamped ids), appends a result group to ITS log
      ;; that REFERENCES the work — no :db/id crosses a log.
      (let [wlog   (ds/open! {:type :dao-stream :space space :name "worker"})
            posted (dao.space/q '[:find ?work :where [?work :a :work/posted]] space)]
        (doseq [[work] posted]
          (ds/put! wlog {:db/id (random-id) :a :work/result :work/for work}))
        (is (= 10 (count (dao.space/q '[:find ?work
                                        :where [?r :a :work/result]
                                               [?r :work/for ?work]] space))))))))
```

## Design Lessons from Linda and JavaSpace

Tuple spaces have a rich history (Linda 1986, JavaSpace 2000s). This design incorporates hard-won lessons from their successes and mistakes, creating a modernized approach that addresses their fundamental limitations.

### Quick Comparison

| Aspect | Linda | JavaSpace | Tuple-Space |
|--------|-------|-----------|-------------|
| **Data Model** | Untyped positional tuples | Java objects | Named attributes (datoms) |
| **Pattern Matching** | Wildcards only | Field-based matching | Full Datalog queries |
| **Read Operations** | Blocking `in()` / `rd()` | Blocking `read()` / `take()` | Non-blocking `dao.space/q` / `match` (no blocking reads) |
| **History** | Lost (destructive) | Lost | Immutable append-only file logs |
| **Observability** | None (opaque space) | Limited | Full Datalog queries over state |
| **Transport** | Tightly coupled | Java/Jini specific | File-backed member logs (`dao.stream.file`); byte substrate is swappable |
| **Expiration** | None | Leasing (complex, fragile) | Application-controlled |
| **Multi-tuple Atomicity** | Per-tuple only | Per-object only | Via envelopes or multi-attribute tuples |
| **Debugging** | Blind (no visibility) | Blind | Full queryable history |
| **Multi-reader Fan-Out** | Manual copying | Manual copying | Automatic (every read folds all member logs) |

---

### ✅ Good Ideas We Adopt

1. **Pattern matching as coordination primitive** — but improved with full Datalog instead of wildcards
2. **Non-destructive read** — agents can observe without consuming
3. **Asynchronous coordination** — no agent blocks another; coordination happens via shared state
4. **Declarative queries** — agents describe what they need, not how to get it

### ❌ Critical Problems We Avoid

**Problem 1: Blocking Operations Cause Deadlock**

Linda/JavaSpace mistake: `in(tuple)` and `take(tuple)` block agents, leading to circular waits and deadlock.

Tuple-space fix: There is no blocking read at all. `q`/`match` always return
immediately.
```clojure
;; Non-blocking query (always returns immediately)
(dao.space/q '[:find ?worker :where [?worker "ready"]] space)
;; → #{} if no match (no deadlock possible)
```

**Problem 2: Untyped/Loosely Structured Data Causes Confusion**

Linda mistake: Tuples are positional, untyped arrays. Agents can't coordinate reliably because schema is implicit.
```clojure
[42 "task" :status]  ;; What does 42 mean? What's the order?
["task" 42 :status]  ;; Same semantics? Different?
```

Tuple-space fix: Named attributes are the floor, and typing is **per-stream and
optional** on top of that floor. Datoms are always self-describing named facts,
not positional arrays:
```clojure
{:db/id task-id
 :work/status :todo
 :work/task "process payment"}
;; Clear, queryable, self-describing
```

Beyond that floor, each stream chooses its own rigor. A `:strict? true` stream
declares a dimension and slot types and rejects non-conforming datoms on `put!`;
a `:strict? false` stream accepts anything and leaves validation to interpreters (see *Typed
Streams and Conformance*). This avoids both Linda's failure (no structure at all)
and the opposite failure (one rigid global schema): strict and loose streams
coexist in the same space, and read-side "types" are interpreter-defined
equivalence classes rather than a write-time mandate.

**Problem 3: Limited Pattern Matching Restricts Coordination Logic**

Linda limitation: Can only match on specific values or wildcards. Can't express OR, arithmetic, joins, or aggregations.

Tuple-space fix: Full Datalog unlocks complex coordination patterns.
```clojure
;; Linda can't express this:
(dao.space/q '[:find ?id ?task
               :where (or [?id :work/status :todo]
                          [?id :work/status :in-progress])
                      [?id :work/timeout ?t]
                      [(> ?t 3600)]]
  space)
```

**Problem 4: No Causality / History**

Linda/JavaSpace mistake: Destructive read (take) means tuples vanish. No audit trail, no debugging visibility, no replay capability.

Tuple-space fix: Append-only immutable file logs preserve complete history.
```clojure
;; Time travel: see state at any point (as-of is a read bound on q / match)
(dao.space/q audit-query space {:as-of (instant "2024-01-01")})

;; Full audit trail
(dao.space/q '[:find ?who ?action ?when
               :where [?log :action/who ?who]
                      [?log :action/type ?action]
                      [?log :action/time ?when]]
  space)

;; Debugging: "what happened to task X?" Answer: see full history
```

**Problem 5: Tight Coupling to Runtime**

JavaSpace mistake: Tightly bound to Java serialization, Java Jini protocol, and complex leasing semantics. Hard to use from other languages, fragile on network failures.

Tuple-space fix: Built on the DaoStream abstraction. Member logs are file-backed
(`dao.stream.file`) for persistence; the datom framing is a portable serialization
(EDN/transit), not a language-specific protocol. The byte substrate beneath a member
log is swappable.
```clojure
;; One medium, many member logs — each opened through the handle, each its own
;; file-backed append-only log. The same q / match apply regardless.
(def space (dao.space/open! {:path "/data/work"}))

(ds/open! {:type :dao-stream :space space :name "agent-1"})  ;; one writer's log
(ds/open! {:type :dao-stream :space space :name "agent-2"})  ;; another writer's log
```

**Problem 6: Complex Leasing/Expiration**

JavaSpace mistake: Tuples auto-expire when leases expire. Agents must renew leases, and leases can expire while processing (losing work). Complex state machine, fragile behavior.

Tuple-space fix: Explicit application-controlled expiration.
```clojure
;; No implicit expiration. Agents explicitly manage work lifecycle.
(ds/put! log {:db/id task-id
              :work/status :in-progress
              :work/claimed-by agent-id
              :work/claimed-at (now)})

;; Watchdog agent explicitly detects timeouts (no risk of silent loss)
(dao.space/q '[:find ?id ?claimed-at
               :where [?id :work/status :in-progress]
                      [?id :work/claimed-at ?claimed-at]
                      [(> (elapsed ?claimed-at) 3600)]]
  space)
```

**Problem 7: No Transactional Semantics**

Linda/JavaSpace limitation: Coordination across multiple tuples is not atomic. Agent can crash between operations, losing work or leaving inconsistent state.

Tuple-space fix: Multiple options for atomic coordination.
```clojure
;; Option 1: Multi-attribute tuple (atomic write)
(ds/put! log {:db/id task-id
              :work/status :in-progress
              :work/assigned-to worker-id})

;; Option 2: Transaction envelope (application-defined)
(ds/put! log {:transaction/id tx-id
              :transaction/datoms [{:db/id task-id :status :in-progress}
                                   {:db/id worker-id :current-task task-id}]})

;; Option 3: Idempotent operations (can safely retry)
(ds/put! log {:db/id task-id :work/status :completed :timestamp (now)})
```

**Problem 8: Destructive Operations Limit Patterns**

Linda/JavaSpace mistake: `take()` is destructive. Only one agent can process a tuple. Fan-out requires manual copying. No re-processing capability.

Tuple-space fix: Reads are non-destructive folds — every read sees every member
log, so any number of agents process the same facts independently.
```clojure
;; All agents see all events, independently
;; No contention, no copying needed — each worker just re-queries
;; Multiple workers can process the same event

(defn worker-1 [space]
  ;; Processes events at its own pace, tracking how far it has consumed by ?t
  (loop [seen 0]
    (let [events (dao.space/q '[:find ?e ?t :in $ ?seen
                                :where [?e :event/time ?t] [(> ?t ?seen)]]
                   space seen)]
      ...)))

(defn worker-2 [space]
  ;; Processes the same events independently — its own progress, same data
  (loop [seen 0] ...))
```

**Problem 9: No Visibility into Coordination**

Linda/JavaSpace opacity: Can't query the space itself. No visibility into pending work, who's processing what, historical trends. Debugging is blind guessing.

Tuple-space fix: Datalog makes the coordination space itself observable.
```clojure
;; How much work is waiting?
(dao.space/q '[:find (count ?id) :where [?id :work/status :todo]] space)

;; Who is processing what?
(dao.space/q '[:find ?worker (count ?id)
               :where [?id :work/assigned-to ?worker]
                      [?id :work/status :in-progress]]
  space)

;; Historical trends
(dao.space/q '[:find (count ?id) :where [?id :work/status :completed]]
  space {:as-of one-hour-ago})

;; Debug: What happened to task X?
(dao.space/q '[:find ?who ?action ?when
               :where [?log :entity ?task-id]
                      [?log :action ?action]
                      [?log :who ?who]
                      [?log :when ?when]]
  space)
```

## Design Rationale

### Why Build on DaoStream + Native Datalog?

1. **Immutability** — coordination history is preserved, enabling replay and auditing
2. **Queryability** — full Datalog power instead of limited pattern matching (Linda)
3. **Scalability** — each writer owns its own log, so writes never contend; reads fold independently
4. **Persistence** — file-backed member logs give durable history and replay (`dao.stream.file`)
5. **Causality** — append-only log preserves causality naturally

### Why Stigmergic (Not Linda)?

Linda's tuple space suffers from fundamental design choices:
- **Untyped data** prevents schema-aware coordination
- **Blocking operations** (in, take) cause deadlocks
- **Destructive reads** prevent replay and multi-reader patterns
- **Limited pattern matching** restricts coordination logic
- **No history** makes debugging and auditing impossible

Stigmergic coordination fixes these issues:
- **Declarative queries** (Datalog) instead of imperative blocking
- **Non-destructive reads** — every `q`/`match` re-folds all member logs; reads never consume
- **Named attributes** (datoms) instead of positional tuples
- **Complete history** for audit, replay, and time-travel queries
- **Implicit coordination** through shared immutable log
- **Observable** state via full Datalog queries
- **Composable** patterns that can be layered and refined

### Relationship to Event Sourcing

Tuple spaces are **event sourcing + queryability**.

- **Event sourcing**: replay history to reconstruct state
- **Tuple space**: replay history + full query power at any point in time

A tuple space is essentially "event sourcing where the 'events' are datoms and the 'store' is queryable."

## Design Philosophy: Modern Tuple Spaces

This design is **Linda for the 2020s**: it preserves the elegant core idea (agents coordinate via shared facts) while fixing the fundamental problems that plagued Linda and JavaSpace.

### Core Principles

1. **Immutability First** — The tuple space is an append-only log, not a mutable heap. This prevents races, enables replay, and preserves history.

2. **Non-Blocking** — `q` and `match` never block; there is no blocking read primitive. This prevents deadlocks and makes failure modes clear.

3. **Declarative Coordination** — Agents describe what they need (via Datalog), not what to do (imperative blocking). This enables complex patterns without tight coupling.

4. **Full Queryability** — The space itself is queryable via Datalog. Observability, debugging, auditing, and historical analysis are built-in, not bolted on.

5. **File-Backed, Portable Framing** — Member logs persist as append-only files (`dao.stream.file`); datoms are framed with a portable serialization (EDN/transit), not a language- or protocol-specific encoding. The byte substrate beneath a member log is swappable.

6. **Schema-Aware** — Named attributes replace positional tuples. Agents can understand and validate the data they coordinate on.

7. **Explicit Over Implicit** — No hidden expiration, no automatic cleanup, no magic. Application logic drives coordination policy.

### Why This Matters

Linda and JavaSpace failed because they treated the tuple space as a **mutable shared heap**. This led to:
- **Blocking semantics** (one agent blocks another)
- **Opacity** (can't see what's in the space)
- **Fragility** (deadlocks, lost work, mysterious failures)
- **Limited expressiveness** (pattern matching is weak)

By building on **append-only streams + Datalog queries**, tuple-space achieves:
- **Safety** (no deadlocks, clear failure modes)
- **Clarity** (full observability and queryability)
- **Expressiveness** (full Datalog power)
- **Debuggability** (complete immutable history)
- **Auditability** (all coordination actions are recorded)

## Deferred

- Stamped-eid transactability: the fold produces `[namespace offset]` eids, but
  `dao.db` allocates integer eids only. Decide between teaching `dao.db` namespaced
  eids (first-class in EAVT/AEVT) or mapping each `[namespace offset]` to a
  deterministic ephemeral per-fold integer (see *Reference Implementation Pattern*).
- Explicit `flush!` / `sync!` on a member log for guaranteed read-your-writes
  (today `dao.stream.file` is eventually durable; a query may miss an unflushed write)
- Cross-process member discovery / live visibility: a reader sees another process's
  appends only by re-snapshotting; a shared membership index or re-scan policy is TBD
- Live / push reads (subscribe to `q`/`match` deltas) instead of re-folding per read;
  maintained incremental indexes keyed by an equivalence relation
- Retract/update semantics (currently append-only; retractions require new datoms)
- Distributed consensus for multi-agent decisions
- Conflict resolution strategies (last-write-wins by `t`, CRDTs, application-defined)
- Member-log garbage collection / archival / compaction
- `:strict? true` path: validate/coerce dimension, slot types, cardinality,
  and `:db/unique` on `put!` for strict member logs (`:strict? false` needs no change)
- Caching the folded `dao.db` across reads, with incremental fold past a cached point
- Full-text search integration
- Temporal query operators (before, after, during)
