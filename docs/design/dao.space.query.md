# Coordinate Addressing & Materialized Views — Design Discussion

Status: discussion record (2026-07-04). Decisions are tentative; open items marked below.
Origin: follow-on from the n-tuple query work (`docs/design/dao.space.md`, *Arity: n-tuples*) and
`docs/glm-review.md`.

## The question

Can `match`/`q` query across streams whose tuples have **different fixed arities** — one stream
of 3-tuples, another of 4-tuples, another of canonical 5-tuples — joining them on a variable that
sits at **different positions** in each shape?

```clojure
;; stream A (3-tuples)         stream B (4-tuples)
;; [:sonny :likes :pizza]      [:dominos :pizza 10 3]
;; [:sonny :likes :sushi]      [:dominos :sushi 10 3]
(query/q '[:find ?p
           :where [:sonny  :likes ?p]     ; ?p at slot 2 of A
                  [:dominos ?p 10 3]]     ; ?p at slot 1 of B
         [stream-a stream-b])
;; => #{[:pizza] [:sushi]}
```

This is already specced at the *matching* layer (unification by variable name across positions,
in `docs/design/dao.space.md`). The discussion below is about the *addressing* layer: how a query
says where the heterogeneous tuples live.

## The coordinate model (proposed)

A coordinate is a value that addresses into the space:

```clojure
[jing-storage [
  [dao.stream1 [3-tuple 3-tuple 3-tuple]]
  [dao.stream2 [5-tuple 5-tuple 5-tuple]]]]
```

A `dao.jing` storage hosts many named logs, each exposed through the `dao.stream` interface, and
each log may hold tuples of a different fixed dimension. A query names the storage and the logs to
fold; the engine folds them and joins by variable name.

## Reality check: this is proposed, not current

The coordinate does **not** describe today's code:

- `dao.jing` is a flat KV — each key a single mutable root updated via `cas!`, holding an opaque
  value. The query reads one `:root/datoms` per store (the doc itself flags this as "the simplest
  possible convention ahead of the target B-Tree-segment architecture").
- `dao.stream` is a separate abstraction (ringbuffer / append-log / file / http / ws / udp) that
  carries opaque **bytes** and does **not** persist into a jing store. The dependency is inverted —
  `dao.jing/file` *uses* `dao.stream.log` internally as its byte transport.
- There is no per-log addressing, no log manifest, no coordinate notion anywhere yet.

So the coordinate is the architecture the doc defers.

## Why it coheres

It gives every part of the existing design a concrete home:

- **Write side** (generative communication): a writer appends to its own named `dao.stream` log —
  the doc's "each writer owns a single-writer log."
- **Read side** (associative matching): the query folds a *set of named logs*; the coordinate is
  how a read says "these logs, please" — the doc's "membership is intake, not identity," made
  addressable.
- **Per-log fixed arity, cross-log heterogeneity**: within a log, tuples are homogeneous; across
  logs they differ, and the n-tuple matcher folds and joins by name. So the **coordinate is the
  addressing layer; the n-tuple engine is the matching layer — they compose.**
- **A coordinate is a pure value**: cacheable, replayable, sendable through a stream — "everything
  is data / streams are values" made literal.
- It **generalizes federation**: today `[store1 store2]` federates *stores*; the model federates
  *logs within a store* (and nests). One store is a small federation of its own single-writer logs.

## The structural-vs-semantic line

The crux: can the KV be "coordinate-aware" without violating "storage doesn't interpret"? Yes —
because the coordinate is **structural**, not semantic.

- *Structurally aware* — the KV knows "named logs, each an ordered collection of tuples with a
  comparator; here's a prefix seek / a per-log index." This is still **serving**, not matching: it
  returns tuples, never treats them as datoms, never binds a variable, never joins.
- *Semantically aware* — the KV knows position 1 is an attribute, or how to run Datalog. That
  collapses interpretation into storage. Forbidden.

The coordinate `[storage [[log tuples]…]]` — named logs of positional tuples — is a structural
shape (a named sorted multiset). So the KV can speak the coordinate protocol (order, seek, index)
without knowing what the tuples mean. Datomic does the same: opaque content-addressed segments
("dumb") plus a structural segment/index contract that the Peer interprets.

## Convention-over-KV + coordinate-aware (the synthesis)

- At the **semantic** layer: convention-over-KV. Datoms, e/a/v, Datalog, unification all stay in
  interpreters; storage never interprets.
- At the **structural** layer: the KV is coordinate-aware. It knows the named-log base shape and
  optionally serves materialized views (covered indexes, ordered seeks) over it.

Decomposition: the **coordinate is the base** (canonical facts, the medium); the **served indexes
are materialized views** derived from it. "Coordinate-aware KV" = the KV knows the base shape and
optionally serves views. The load-bearing half for performance is the views.

## It is the doc's own concept: materialized views

"A Family of Interpreters" already calls covered indexes and derived access structures
**materialized views**. So "coordinate-aware KV serving EAVT/AEVT/AVET and tuple-order indexes" is
exactly a materialized view — the only addition is *where* it gets materialized. Once named this
way, the open question reduces to **which layer materializes views**:

- *Interpreter-materialized* = the rebuild-per-query fold. Always fresh, slower.
- *Storage-materialized* = the coordinate-aware KV serves them. Faster. Datomic's move: storage
  holds opaque index segments; the Peer interprets.

The doc's deferred "Index Realization" (rebuild-per-query / incremental / owner-built-peers-merge)
*is* this decision. An opt-in capability protocol is the negotiation: backends that can materialize
views at the storage layer offer the capability; otherwise the interpreter materializes them
itself. Same views, different layer.

## Freshness

Calling them materialized views forces an explicit answer the "coordinate-aware KV" framing hid:

- A fold always reads canonical truth (the tuples directly).
- A served view can lag the log tip — it's a precomputed arrangement that must be maintained as
  tuples append.

In an append-only world this is benign: indexes only *grow* as tuples append (retractions are
semantic, above storage), so lag is monotone and bounded, and a served view is always a faithful
projection of some prefix of the log. And because any reader can reproduce a served view by
folding the canonical tuples, a served view stays "acceleration of the one medium," not a second
coordinating substrate — preserving the doc's "views are interpreter-local / not the medium" intent.

## Open decisions

- **Layering** — *converged*: convention-over-KV at the semantic layer; coordinate-aware at the
  structural layer.
- **Expose how** — *open*. Opt-in capability protocol (keep `IKVStore` minimal; add a separate
  `CoordinateStore`/`IndexedLogStore` protocol backends may implement; query fast-paths when
  present, folds when not) vs. grow `IKVStore` itself. Lean: opt-in capability.
- **Coordinate semantics** — *open*. Reference (lazy, reads live logs) vs. snapshot value (carries
  tuples). Lean: reference for a coordinating medium.
- **Provenance** — *open*. Retain each tuple's source-log identity (traceable; "same entity across
  logs" as a real join) vs. merge away boundaries. Lean: retain.
- **Freshness policy** — *open*. Served views fresh-to-tip vs. explicit (monotone) lag. Lean:
  explicit lag; default to interpreter-materialized fold for correctness, storage-materialized
  views as opt-in acceleration.

## What this does not decide

- Whether `dao.stream` and `dao.jing` get formally unified or the coordinate stays a convention
  layered over the dumb KV (the layering lean is the latter, entangled with "expose how").
- Index maintenance ownership (writer-built / incremental / rebuild-per-query) — the doc's
  "Index Realization," deferred.
- Whether the coordinate carries arity declarations (schema) or infers arity from contents.
