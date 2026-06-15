# DaoSpace Design: Stigmergic Coordination via DaoStream

**Related documents:**
- `docs/design/dao.stream.md` — the stream transport foundation
- `docs/agents/datom-spec.md` — datom moduli space, dimensions, gauge/content-hash framing
- `docs/design/dao.space.metaphors.md` — visual and biological metaphors for the space
- `docs/design/dao.space.discrete-to-continuous.md` — the spectral-triple realization (gauge groupoid, Dirac operator, curvature)
- `docs/design/dao.space.locality.md` — physics ↔ distributed-computing correspondence (relativity, descent, consistency models, CAP)

## Overview

**DaoSpace** is datom.world's implementation of a tuple space: a shared store of datoms (facts) that agents can collectively query and modify to coordinate work. Unlike traditional message passing (explicit sender → receiver), DaoSpace enables **stigmergic coordination**: agents read shared facts, modify them based on local logic, and implicitly coordinate through the effects of those modifications.

A DaoSpace is a **fan-in of many input DaoStreams**, not a single log. The space is heterogeneous along two axes: streams differ in **dimension/type** (one stream carries only d5, another only d10, etc.), and they differ in how strict they are (some enforce their type on write, some accept anything). Each stream is in turn materialized by **many interpreters**, and each interpreter's materialization is a **slice**: a view of the stream quotiented by an interpreter-defined equivalence relation. Independent cursors let those slices coexist non-destructively over the same stream.

The short answer to "typed or typeless" is: **typeless substrate, typed streams, typed views.** Dimension and slot-type are the stream's intrinsic write-side shape; equivalence-class slicing is the interpreter's read-side view. The two are orthogonal: one typed stream supports many slicings.

This design builds DaoSpace on top of DaoStream (append-only datom logs) and indexes those logs into its own native Datalog query engine. The result is:

- **Declarative coordination** — agents describe what they need, not who has it
- **Implicit messaging** — agents don't need to know about each other
- **Full queryability** — Datalog unlocks complex pattern matching
- **Persistent history** — the entire coordination log is available for replay, debugging, and auditing
- **Network-agnostic** — works in-process, across linked nodes, or over any DaoStream transport

## Core Concept: Tuple Space as Many Typed Streams + Slices

```
DaoSpace = many typed dao.streams + per-interpreter materialized slices
```

The space fans in `1..n` input streams. Each stream is typed by dimension (and
optionally by slot types), and carries a per-stream conformance policy. Each
stream is materialized by `1..m` interpreters; each interpreter produces a slice
(an equivalence-class quotient) it queries against. Two heterogeneity axes:

- **Dimension/type** — a given stream is homogeneous in dimension (all d5, or all
  d10) and may further constrain slot types. Different streams in the same space
  may carry different dimensions.
- **Strictness** — some streams enforce their declared type on `put!`; others
  accept anything and leave interpretation to readers.

### What Agents See

```clojure
;; Agent perspective:
(let [space (open-tuple-space "work-queue")]

  ;; 1. Query: "What work is available?"
  (q '[:find ?id ?task
       :where [?id :work/status :todo]
              [?id :work/task ?task]]
    space)

  ;; 2. Claim work (write a fact)
  (put-tuple! space
    {:db/id work-id
     :work/status :in-progress
     :work/worker my-id})

  ;; 3. Complete work (update a fact)
  (put-tuple! space
    {:db/id work-id
     :work/status :completed
     :work/result result}))
```

### What Happens Internally

```
Agent 1 writes → input stream appends [e :work/status :in-progress] → interpreters materialize it
Agent 2 queries → DaoSpace scans the materialized slices → returns matching results
Agent 3 reads → its own cursor advances → sees Agent 1's update
```

A write lands on one input stream. If that stream is strict, the append is
type-checked first; if it is open, the datom is appended as-is and each
interpreter decides how to handle it on read.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│ DaoSpace (User-Facing API + native Datalog index)       │
│  - query(pattern)            across all input streams    │
│  - put-tuple!(stream, fact)  routed to one input stream  │
│  - wait-for-pattern!(pattern)                            │
│  - run-query(query) / datoms(index, components)          │
│  - as-of(t) / pull(pattern, eid)                         │
└───────┬───────────────────────┬─────────────────────────┘
        │ interpreters slice     │
┌───────▼────────┐      ┌────────▼───────┐    (1..m interpreters
│ slice (quotient│ ...  │ slice (quotient│     per stream; each
│  by equiv-rel) │      │  by equiv-rel) │     a materialized view)
└───────┬────────┘      └────────┬───────┘
        │                        │
┌───────▼────────┐      ┌────────▼───────┐    (1..n input streams;
│ dao.stream A   │ ...  │ dao.stream B   │     each typed by dimension;
│ d5, strict     │      │ d10, open      │     strict? true/false)
└───────┬────────┘      └────────┬───────┘
        │                        │
┌───────▼────────────────────────▼────────────────────────┐
│ Transport (RingBuffer, WebSocket, File, Kafka, etc.)    │
└─────────────────────────────────────────────────────────┘
```

## Typed Streams and Conformance

Each input stream declares its **dimension** and, optionally, its **slot types**.
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
(open-input-stream space "ledger"
  {:dimension :d5 :slots {...} :strict? true})

;; Open: takes anything; interpreters cope on read
(open-input-stream space "ingest"
  {:dimension :d5 :strict? false})
```

Both policies are first-class. A single space routinely mixes strict streams (a
ledger, a schema-checked work queue) with open streams (raw ingestion, untrusted
external feeds). The transport beneath stays dumb either way: enforcement is an
optional realization concern at the stream boundary, not a property of the
transport.

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
;; Two interpreters slicing the SAME stream differently:

;; Interpreter 1: slice by entity (classic EAVT view)
(materialize stream :by (fn [d] (:e d)))

;; Interpreter 2: slice by content identity (dedup distinct facts)
(materialize stream :by content-hash)
```

## Querying Across Streams

A query against a DaoSpace ranges over the slices of all its input streams.
Because each stream is dimension-homogeneous but the space is heterogeneous,
cross-stream joins happen on **shared values, not entity IDs** — entity IDs are
stream-local gauges (see `datom-spec.md`, d5 > NAMESPACES > Cross-Namespace
Queries). This reuses Datomic's multiple-database pattern: each stream is an
explicit input, joined on the values they have in common.

```clojure
;; Join a d5 ledger stream against a d10 sensor stream on a shared value
(query space
  '[:find ?account ?reading
    :in $ledger $sensors
    :where [$ledger  ?a :account/id ?id]
           [$sensors ?s :sensor/account ?id]
           [$sensors ?s :sensor/reading ?reading]
           [$ledger  ?a :account/name ?account]])
```

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
  `E/~` is the quotient set. A **DaoSpace** is a collection of streams over the
  shared base `B`, queried via Datalog joins on shared base values.
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
128-bit `[namespace:offset]` value, so `G` is generated by zero-basis shifts on
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
;; Agents cooperate via a shared work queue

(defn producer [space]
  ;; Emit work
  (put-tuple! space
    {:db/id (random-id)
     :work/task "process payment"
     :work/status :todo}))

(defn worker [space worker-id]
  (loop []
    ;; Find available work
    (let [work (q '[:find ?id ?task
                   :where [?id :work/status :todo]
                          [?id :work/task ?task]]
               space)]
      (when (seq work)
        ;; Claim work (atomic-ish via transaction)
        (let [[id task] (first work)]
          (put-tuple! space
            {:db/id id
             :work/status :in-progress
             :work/assigned-to worker-id})

          ;; Do work
          (let [result (process task)]
            ;; Emit result
            (put-tuple! space
              {:db/id id
               :work/status :completed
               :work/result result})))

        ;; Repeat
        (recur)))))
```

### Pattern 2: Stigmergic Search (Ant Colony Optimization)

```clojure
;; Agents leave pheromone traces for others to follow

(defn ant [space colony-id]
  (loop [path [] node (random-node)]
    ;; 1. Emit pheromone: "I visited this node"
    (put-tuple! space
      {:db/id (random-id)
       :pheromone/colony colony-id
       :pheromone/node node
       :pheromone/strength 1.0
       :pheromone/time (now)})

    ;; 2. Query pheromones: "Where should I go next?"
    (let [neighbors (q '[:find ?next ?strength
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
          (put-tuple! space
            {:db/id (random-id)
             :solution/path path
             :solution/cost (count path)}))))))
```

### Pattern 3: Watchdog / Exception Handling

```clojure
;; Agents monitor state and react to anomalies

(defn watchdog [space timeout-seconds]
  (loop []
    ;; Query: "Find work that's been in-progress too long"
    (let [stuck (q '[:find ?id ?started ?worker
                    :where [?id :work/status :in-progress]
                           [?id :work/assigned-to ?worker]
                           [?id :work/started-at ?started]
                           [(elapsed-seconds ?started) ?elapsed]
                           [(> ?elapsed ?timeout)]]
                  space)]

      ;; Reassign stuck work
      (doseq [[id started worker] stuck]
        (put-tuple! space
          {:db/id id
           :work/status :todo
           :work/assigned-to nil
           :work/reassigned-from worker
           :work/reassignment-reason :timeout}))

      ;; Check again soon
      (Thread/sleep (* timeout-seconds 1000))
      (recur))))
```

### Pattern 4: Collaborative Filtering

```clojure
;; Agents collectively build and improve models

(defn agent-observer [space agent-id]
  (loop [last-t 0]
    ;; 1. Observe recent events
    (let [events (q '[:find ?e ?action ?time
                     :where [?e :event/action ?action]
                            [?e :event/time ?time]
                            [(> ?time ?last-t)]]
                   space)]

      ;; 2. Update local belief
      (doseq [[e action time] events]
        (update-model! agent-id action))

      ;; 3. Emit shared insight if found
      (when-let [pattern (detect-pattern agent-id)]
        (put-tuple! space
          {:db/id (random-id)
           :pattern/discovered-by agent-id
           :pattern/rule pattern
           :pattern/confidence (calculate-confidence agent-id)}))

      ;; 4. Read and apply patterns from other agents
      (let [patterns (q '[:find ?rule ?author
                         :where [?p :pattern/rule ?rule]
                                [?p :pattern/discovered-by ?author]
                                [(> ?author ?agent-id)]]
                       space)]
        (doseq [[rule author] patterns]
          (apply-pattern! agent-id rule)))

      (recur (+ 1 last-t)))))
```

## API

### Core Operations

#### `open-tuple-space [name & {:keys [transport]}]`

Open a new tuple space.

```clojure
;; In-process (default)
(def space (open-tuple-space "work-queue"))

;; File-backed
(def space (open-tuple-space "audit-log"
  :transport {:type :file
              :path "/var/log/tuples.log"}))

;; Network-backed (future)
(def space (open-tuple-space "distributed"
  :transport {:type :websocket
              :url "ws://broker:8000"}))
```

#### `query [space datalog-query & inputs]`

Query the current state of the tuple space.

```clojure
;; Simple pattern
(query space '[:find ?id ?task
              :where [?id :work/status :todo]
                     [?id :work/task ?task]])

;; With bindings
(query space '[:find ?result
              :where [?e :work/id ?id]
                     [?e :work/result ?result]]
       {:id work-id})

;; Aggregation
(query space '[:find (count ?id)
              :where [?id :work/status :completed]])
```

#### `put-tuple! [space stream fact]` (or `[space fact]`)

Append a tuple (datom) to a named input stream of the space. If the target
stream is strict (`:strict? true`), the datom is validated against the
stream's dimension and slot types first; an open stream appends as-is. When a
space has a single (or default) input stream, the `stream` argument may be
omitted, and the examples below use that shorthand.

```clojure
;; Single fact
(put-tuple! space
  {:db/id 42
   :work/status :completed
   :work/result "OK"})

;; Entity reference
(put-tuple! space
  {:db/id work-id
   :work/assigned-to agent-id})

;; Retractable fact (future)
(retract-tuple! space work-id :work/status)
```

#### `wait-for-pattern [space query & {:keys [timeout]}]`

Block until a pattern matches (convenience for polling).

```clojure
;; Wait for any completed work
(wait-for-pattern space
  '[:find ?id ?result
    :where [?id :work/status :completed]
           [?id :work/result ?result]])

;; With timeout
(wait-for-pattern space pattern :timeout 5000)
```

#### `bounded-stream->index [stream cursor]`

Materialize a stream prefix into a queryable index snapshot.

```clojure
;; Get queryable snapshot up to current position
(let [snapshot (bounded-stream->index (tuple-space->stream space) cursor)]
  (q '[:find ?e :where [?e :work/status :completed]] snapshot))
```

#### `as-of-time [space timestamp]`

Query the tuple space state at a specific historical point.

```clojure
;; "What was the work queue state at 2024-01-01?"
(query (as-of-time space (instant "2024-01-01"))
  '[:find ?id ?status
    :where [?id :work/status ?status]])
```

## Coordination Semantics

### Consistency

**Strong consistency within a single-threaded agent:**

```clojure
;; Agent's view
(put-tuple! space {:db/id id :work/status :in-progress})
;; Immediately visible in subsequent queries
(= :in-progress (:work/status (query space '[...])))
```

**Eventual consistency across agents:**

```clojure
;; Agent 1 writes
(put-tuple! space {:db/id id :work/status :in-progress})

;; Agent 2's view depends on stream position
(if (>= agent-2-cursor (position-of-write))
  ;; Agent 2 sees the write
  (assert (= :in-progress (query space '[...])))
  ;; Agent 2 hasn't caught up yet
  (assert (not= :in-progress (query space '[...]))))
```

### Ordering

**Within an agent's timeline:**
- Writes are totally ordered (append-only stream)
- Reads of a materialized index snapshot are consistent
- Re-querying at a later cursor position includes later writes

**Across agents:**
- No global ordering of operations
- Agents see facts in append order but may process them at different rates
- Causality is preserved via timestamps in tuples (application's responsibility)

**Example: two agents updating the same entity**

```clojure
;; Agent A at t=100
(put-tuple! space {:db/id 42 :counter 1})

;; Agent B at t=101
(put-tuple! space {:db/id 42 :counter 2})

;; Both updates are in the stream, in order
;; Agents see both; interpretation is application-defined
;; (Last-write-wins? Sum them? Conflict detection?)
```

### Atomicity

**Single tuples are atomic:**
```clojure
(put-tuple! space {:db/id id :work/status :in-progress :worker agent-id})
;; Appears atomically to other agents
```

**Multi-tuple transactions need application-level coordination:**
```clojure
;; NOT atomic across the space:
(put-tuple! space {:db/id id :work/status :in-progress})
(put-tuple! space {:db/id id :work/assigned-to agent-id})

;; Remedies:
;; 1. Single tuple with multiple attributes
(put-tuple! space {:db/id id :work/status :in-progress :work/assigned-to agent-id})

;; 2. Transaction envelope (application-defined)
(put-tuple! space
  {:db/id (random-id)
   :transaction/id tx-id
   :transaction/committed true
   :transaction/datoms [{:db/id id :work/status :in-progress}
                        {:db/id id :work/assigned-to agent-id}]})

;; 3. Causality tracking (application-defined)
(put-tuple! space {:db/id id :version 1 :work/status :in-progress})
(put-tuple! space {:db/id id :version 2 :work/assigned-to agent-id
                   :depends-on 1})
```

## Implementation

### What's Already Available

- **DaoStream** (`src/cljc/dao/stream.cljc`): append-only datom log
- **IDaoStreamWaitable**: transport-local notifications when data arrives
- **Cursors**: independent read positions for multiple agents

### What Needs to Be Built

New namespace: `src/cljc/dao/tuple-space.cljc`

```clojure
(ns dao.tuple-space
  "Tuple space: stigmergic coordination over many typed DaoStreams + native Datalog index")

;; Core functions (to implement)
(defn open-tuple-space [name & opts])
(defn open-input-stream [space name descriptor]) ; descriptor: :dimension, :slots, :strict?
(defn query [space datalog-query & inputs])       ; ranges over all input streams' slices
(defn put-tuple! [space stream fact])             ; routed to one input stream; strict streams validate
(defn materialize [stream & {:keys [by]}])        ; interpreter slice: quotient by equiv relation
(defn retract-tuple! [space stream id attr])
(defn wait-for-pattern [space query & opts])
(defn bounded-stream->index [stream cursor])
(defn as-of-time [space timestamp])

;; Internal helpers
(defn- stream-datoms [stream from-cursor to-cursor])
(defn- conform [descriptor fact])                 ; strict path; identity when :strict? false
(defn- materialize-index [datoms equiv-fn])       ; class-key -> members
(defn- cursor-position [stream])
(defn- apply-datoms-to-index [index datoms])
```

The fan-in space holds a set of input streams, each with its own descriptor and
cursor; `query` joins their materialized slices on shared values. `conform`
enforces a strict stream's dimension/slot types on `put!` and is the identity for
non-strict (`:strict? false`) streams. `materialize` builds an interpreter slice keyed by an equivalence
function (default the d1 content hash; d3 projection and domain relations are
supplied by interpreters).

### Reference Implementation Pattern

```clojure
;; A space holds many input streams. Each entry pairs a stream with its
;; descriptor (dimension, slots, conformance) and the interpreter slices
;; (each an index keyed by an equivalence function) maintained over it.
(defrecord TupleSpace [streams]   ; name -> {:stream :descriptor :cursor-ref :slices}

  ;; Query current state across every input stream's slices
  IQueryable
  (run-query [this query inputs]
    (let [slices (for [{:keys [stream descriptor cursor-ref slices]} (vals streams)]
                   ;; 1. Load new datoms since last query
                   (let [new-datoms (read-since cursor-ref stream)]
                     ;; 2. Update each interpreter slice with the new datoms
                     (refresh-slices slices new-datoms)))]
      ;; 3. Execute the query, joining the slices on shared values
      (run-query-over slices query inputs)))

  ;; Append to one named input stream. Strict streams validate; open streams pass through.
  (put-tuple! [this stream-name fact]
    (let [{:keys [stream descriptor]} (get streams stream-name)]
      (ds/put! stream (conform descriptor fact)))))
```

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

```clojure
(deftest open-tuple-space-test
  (testing "Open in-memory tuple space"
    (let [space (open-tuple-space "test")]
      (is (some? space)))))

(deftest put-query-test
  (testing "Put and query round trip"
    (let [space (open-tuple-space "test")]
      (put-tuple! space {:db/id 1 :work/status :todo})
      (let [result (query space '[:find ?status
                                 :where [1 :work/status ?status]])]
        (is (= [[:todo]] result))))))

(deftest multiple-agents-test
  (testing "Two agents coordinate via tuple space"
    (let [space (open-tuple-space "test")]
      ;; Agent 1 writes
      (put-tuple! space {:db/id 1 :work/task "task-1"})
      ;; Agent 2 reads
      (let [tasks (query space '[:find ?task :where [?id :work/task ?task]])]
        (is (= [["task-1"]] tasks))))))

(deftest as-of-time-test
  (testing "Historical query returns state at point in time"
    (let [space (open-tuple-space "test")
          t0 (now)]
      (put-tuple! space {:db/id 1 :work/status :todo})
      (let [t1 (now)]
        (put-tuple! space {:db/id 1 :work/status :in-progress})
        (let [past-state (as-of-time space t0)
              result (query past-state '[:find ?status
                                        :where [1 :work/status ?status]])]
          (is (= [[:todo]] result)))))))
```

### Integration Tests

```clojure
(deftest work-queue-pattern-test
  (testing "Producer-worker coordination"
    (let [space (open-tuple-space "work-queue")]
      ;; Producer emits work
      (future (dotimes [i 10]
                (put-tuple! space {:db/id i :work/status :todo})
                (Thread/sleep 10)))

      ;; Worker processes work
      (let [completed (atom [])]
        (dotimes [i 10]
          (wait-for-pattern space '[:find ?id :where [?id :work/status :todo]])
          (let [[[id]] (query space '[:find ?id :where [?id :work/status :todo]])]
            (put-tuple! space {:db/id id :work/status :completed})
            (swap! completed conj id)))

        ;; Verify all work completed
        (is (= 10 (count @completed)))))))
```

## Design Lessons from Linda and JavaSpace

Tuple spaces have a rich history (Linda 1986, JavaSpace 2000s). This design incorporates hard-won lessons from their successes and mistakes, creating a modernized approach that addresses their fundamental limitations.

### Quick Comparison

| Aspect | Linda | JavaSpace | Tuple-Space |
|--------|-------|-----------|-------------|
| **Data Model** | Untyped positional tuples | Java objects | Named attributes (datoms) |
| **Pattern Matching** | Wildcards only | Field-based matching | Full Datalog queries |
| **Read Operations** | Blocking `in()` / `rd()` | Blocking `read()` / `take()` | Non-blocking `query()` + optional `wait-for-pattern()` |
| **History** | Lost (destructive) | Lost | Immutable append-only log |
| **Observability** | None (opaque space) | Limited | Full Datalog queries over state |
| **Transport** | Tightly coupled | Java/Jini specific | DaoStream-agnostic (ringbuffer, WebSocket, Kafka, etc.) |
| **Expiration** | None | Leasing (complex, fragile) | Application-controlled |
| **Multi-tuple Atomicity** | Per-tuple only | Per-object only | Via envelopes or multi-attribute tuples |
| **Debugging** | Blind (no visibility) | Blind | Full queryable history |
| **Multi-reader Fan-Out** | Manual copying | Manual copying | Automatic (independent cursors) |

---

### ✅ Good Ideas We Adopt

1. **Pattern matching as coordination primitive** — but improved with full Datalog instead of wildcards
2. **Non-destructive read** — agents can observe without consuming
3. **Asynchronous coordination** — no agent blocks another; coordination happens via shared state
4. **Declarative queries** — agents describe what they need, not how to get it

### ❌ Critical Problems We Avoid

**Problem 1: Blocking Operations Cause Deadlock**

Linda/JavaSpace mistake: `in(tuple)` and `take(tuple)` block agents, leading to circular waits and deadlock.

Tuple-space fix: All operations are non-blocking.
```clojure
;; Non-blocking query (always returns immediately)
(query space '[:find ?worker :where [?worker "ready"]])
;; → [] if no match (no deadlock possible)

;; Optional blocking is explicit and interruptible
(wait-for-pattern space pattern :timeout 5000)
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
(query space '[:find ?id ?task
              :where (or [?id :work/status :todo]
                         [?id :work/status :in-progress])
                     [?id :work/timeout ?t]
                     [(> ?t 3600)]])
```

**Problem 4: No Causality / History**

Linda/JavaSpace mistake: Destructive read (take) means tuples vanish. No audit trail, no debugging visibility, no replay capability.

Tuple-space fix: Append-only immutable log preserves complete history.
```clojure
;; Time travel: see state at any point
(as-of-time space (instant "2024-01-01"))

;; Full audit trail
(query space '[:find ?who ?action ?when
              :where [?log :action/who ?who]
                     [?log :action/type ?action]
                     [?log :action/time ?when]])

;; Debugging: "what happened to task X?" Answer: see full history
```

**Problem 5: Tight Coupling to Runtime**

JavaSpace mistake: Tightly bound to Java serialization, Java Jini protocol, and complex leasing semantics. Hard to use from other languages, fragile on network failures.

Tuple-space fix: Transport-agnostic via DaoStream abstraction.
```clojure
;; Same API, different transport
(open-tuple-space "work" :transport {:type :ringbuffer})   ;; in-process
(open-tuple-space "work" :transport {:type :websocket :url "..."})  ;; remote
(open-tuple-space "work" :transport {:type :kafka :broker "..."})  ;; distributed

;; All use same query language, same semantics
```

**Problem 6: Complex Leasing/Expiration**

JavaSpace mistake: Tuples auto-expire when leases expire. Agents must renew leases, and leases can expire while processing (losing work). Complex state machine, fragile behavior.

Tuple-space fix: Explicit application-controlled expiration.
```clojure
;; No implicit expiration. Agents explicitly manage work lifecycle.
(put-tuple! space {:db/id task-id
                  :work/status :in-progress
                  :work/claimed-by agent-id
                  :work/claimed-at (now)})

;; Watchdog agent explicitly detects timeouts (no risk of silent loss)
(query space '[:find ?id ?claimed-at
              :where [?id :work/status :in-progress]
                     [?id :work/claimed-at ?claimed-at]
                     [(> (elapsed ?claimed-at) 3600)]])
```

**Problem 7: No Transactional Semantics**

Linda/JavaSpace limitation: Coordination across multiple tuples is not atomic. Agent can crash between operations, losing work or leaving inconsistent state.

Tuple-space fix: Multiple options for atomic coordination.
```clojure
;; Option 1: Multi-attribute tuple (atomic write)
(put-tuple! space {:db/id task-id
                   :work/status :in-progress
                   :work/assigned-to worker-id})

;; Option 2: Transaction envelope (application-defined)
(put-tuple! space {:transaction/id tx-id
                   :transaction/datoms [{:db/id task-id :status :in-progress}
                                        {:db/id worker-id :current-task task-id}]})

;; Option 3: Idempotent operations (can safely retry)
(put-tuple! space {:db/id task-id :work/status :completed :timestamp (now)})
```

**Problem 8: Destructive Operations Limit Patterns**

Linda/JavaSpace mistake: `take()` is destructive. Only one agent can process a tuple. Fan-out requires manual copying. No re-processing capability.

Tuple-space fix: Non-destructive reads with independent cursors enable multi-agent processing.
```clojure
;; All agents see all events, independently
;; No contention, no copying needed
;; Multiple workers can process same event

(defn worker-1 [space]
  ;; Processes events at its own pace
  (loop [cursor {:position 0}]
    ...))

(defn worker-2 [space]
  ;; Processes same events independently
  (loop [cursor {:position 0}]
    ...))
```

**Problem 9: No Visibility into Coordination**

Linda/JavaSpace opacity: Can't query the space itself. No visibility into pending work, who's processing what, historical trends. Debugging is blind guessing.

Tuple-space fix: Datalog makes the coordination space itself observable.
```clojure
;; How much work is waiting?
(query space '[:find (count ?id) :where [?id :work/status :todo]])

;; Who is processing what?
(query space '[:find ?worker (count ?id)
              :where [?id :work/assigned-to ?worker]
                     [?id :work/status :in-progress]])

;; Historical trends
(query (as-of-time space one-hour-ago)
  '[:find (count ?id) :where [?id :work/status :completed]])

;; Debug: What happened to task X?
(query space '[:find ?who ?action ?when
              :where [?log :entity ?task-id]
                     [?log :action ?action]
                     [?log :who ?who]
                     [?log :when ?when]])
```

## Design Rationale

### Why Build on DaoStream + Native Datalog?

1. **Immutability** — coordination history is preserved, enabling replay and auditing
2. **Queryability** — full Datalog power instead of limited pattern matching (Linda)
3. **Scalability** — cursor-based reads allow independent agent throughput
4. **Transport Agnosticity** — agents work in-process, across nodes, or over any DaoStream transport
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
- **Non-destructive reads** via independent cursors
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

2. **Non-Blocking by Default** — Queries never block. Blocking is optional, explicit, and interruptible. This prevents deadlocks and makes failure modes clear.

3. **Declarative Coordination** — Agents describe what they need (via Datalog), not what to do (imperative blocking). This enables complex patterns without tight coupling.

4. **Full Queryability** — The space itself is queryable via Datalog. Observability, debugging, auditing, and historical analysis are built-in, not bolted on.

5. **Transport Agnostic** — Built on DaoStream abstraction. Same API works in-process, cross-process, or over any transport. No binding to specific serialization, language, or protocol.

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

- Retract/update semantics (currently append-only; retractions require new datoms)
- Subscription filtering (notify agents only on matching patterns)
- Distributed consensus for multi-agent decisions
- Conflict resolution strategies (last-write-wins, CRDTs, application-defined)
- Tuple space garbage collection / archival
- `:strict? true` path: validate/coerce dimension, slot types, cardinality,
  and `:db/unique` on `put!` for strict streams (`:strict? false` streams need no change)
- Multi-stream fan-in: routing `put!` to a named input stream and joining many
  input streams' slices in a single `query`
- Interpreter slice index: maintained `class-key -> members` views keyed by an
  equivalence relation, including memoized recompute past a cached cursor
- Full-text search integration
- Temporal query operators (before, after, during)
