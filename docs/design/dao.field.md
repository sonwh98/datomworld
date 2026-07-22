# DaoField: The Vector Field

`dao.field` is **not a thing you store**. [`dao.jing`](dao.jing.md) holds the n-tuples;
`dao.field` is the **vector field that emerges** when interpreters read those same n-tuples as
**coordinates** rather than as templates. Where [`dao.space`](dao.space.md) makes the tuples a
*tuple space* by exact associative matching (Linda unification), `dao.field` makes the **same**
tuples a *vector space* by metric proximity — cosine similarity, dot products, and other geometric operators.
Storage holds the tuples at rest; `dao.field` is those tuples *under a metric
interpretation*.

`dao.field` is named directly for what it is: a **field** in the physics sense — an assignment
of a value to every point in a space, with **gradients** and **concentrations** you can follow
rather than exact matches you unify. That is precisely the structure laid over the datom set: a
vector assigned to each fact, compared by angle and distance rather than by shape.

## Is it a Vector Database?

On its own, no: `dao.field` is a pure read-surface that owns no storage. However, **`dao.field` combined with `dao.jing` functions exactly as a vector database.**

If you bundle the two together, you have durable storage (`dao.jing`), an ingestion path (`dao.stream`), and an index/query layer that performs similarity search (`dao.field`). To a consumer, it fulfills all the requirements and use cases of a vector database. The architectural distinction is composability: instead of a monolithic silo that only understands vectors, it is a vector database constructed dynamically over a universal tuple store—meaning those exact same vectors can simultaneously be queried as a discrete tuple space via `dao.space`.

## Siblings in the moduli space

`dao.space` and `dao.field` are **two points in the moduli space of databases** that
`dao.space.md` names (see [A Family of Interpreters](dao.space.md#a-family-of-interpreters)):
one canonical datom history in `dao.jing`, many interpreters over it, each fixed by which
**materialized construction** it lays down and which surface it exposes. `dao.space` and
`dao.field` are two such members — **siblings, not layers.** Neither sits under the other; both
floor on `dao.jing`, and `dao.jing` privileges neither. The substrate stays the same n-tuples;
only the construction laid over them changes.

| | [`dao.space`](dao.space.md) — the tuple space | `dao.field` — the vector field |
|---|---|---|
| Reading | discrete / structural | continuous / metric |
| Locate a tuple by | its **form** (template) | its **position** (coordinates) |
| Match operation | Exact unification (Linda match, `q`) | Cosine similarity, dot product, L2 distance (geometric matching, `near`) |
| Coordination | template stigmergy | gradient stigmergy (follow the concentration) |
| Formal home | EAVT/AEVT/AVET/VAET covered indexes | `H = ℓ²(E)`, inner-product geometry |

Both readings are **associative** in the sense `dao.space.md` requires of a coordination
surface: an agent finds tuples by *describing content* (an exact template, or a query vector),
never by naming an address or navigating another agent's views. `dao.space` matches content by
shape; `dao.field` matches content by proximity. Neither is traversal, so both stay on the
by-content axis — they are two coordination surfaces over one medium, not one plus a private
view.

**Related documents:**
- `docs/design/dao.space.md` — the sibling point: the tuple space, exact associative matching, and the moduli-space framing both share
- `docs/design/dao.jing.md` — the storage boundary both read: the content-addressed store of opaque bytes served as datoms
- `docs/agents/datom-spec.md` — tuples of any dimension/size in an open moduli space; the `d_k` dimension a vector inhabits
- `docs/design/dao.space.discrete-to-continuous.md` — why the datom set carries a metric at all: `ℓ²(E)`, spectral decomposition, the discrete→continuous correspondence this doc rests on
- `docs/design/dao.space.locality.md`, `dao.space.metaphors.md` — the geometry/locality cluster

## Two shadows of one algebra

The relationship is **one datom algebra with two commutative shadows**, in the
precise Gelfand-Naimark sense developed in
[`dao.space.discrete-to-continuous.md`](dao.space.discrete-to-continuous.md): the algebra comes
first, and each commutative subalgebra provides one classical space. `dao.space` is the shadow
whose natural basis is **structural** — the quotient by exact form, where `match` and `q`
live. `dao.field` is the shadow whose natural basis is **metric** — the ℓ²(E) geometry, where
angle and distance live. Same `E`; different quotient. In the project's Taoist vocabulary this
is 陰陽: `dao.space` is the **form** of the essence, `dao.field` its **flow**.

## A vector *is* a tuple (the `d_k` floor)

`dao.field` needs no new storage primitive because a vector is already a tuple.
[`datom-spec.md`](../agents/datom-spec.md) grades the moduli space by dimension `n` — "a tuple at dimension `n` is a tuple-shaped event in **n coordinates**" — and the space is **open**:
applications declare new dimensions as needed, no dimension canonical. A `k`-dimensional
embedding is therefore a **`d_k` tuple**: its `k` slots *are* the coordinates.

This dissolves the usual "where do the vectors live" question:

- **No compound-by-hash detour.** The "a slot value is always small" rule
  (`datom-spec.md`, Canonical Encoding) is about a single slot. Here each slot is one
  coordinate — an inline float, ≤ 32 bytes — so the whole vector rides as a wide `d_k` tuple of
  small slots, not a blob referenced by hash.
- **Fixed-size typed streams give the layout for free.** A `d_k` typed stream
  (`{:v0 :float64 ... :v_{k-1} :float64}`) yields the flat, cache-efficient, SIMD-friendly,
  O(1)-indexed representation a vector index wants — exactly the "fixed-size streams" case the
  datom spec already describes.
- **Cosine similarity is the inner product.** The `d_k` coordinate space is an inner-product space
  analogous to the wave-mechanics side. A query vector induces an attention vector in `ℓ²(E)` via 
  `e ↦ cos(q, embed(e))`, so geometric matching is mathematically native to the algebra, not a 
  foreign operation grafted on.
- **Embeddings are derived.** A vector is *computed* from a fact by a model, so it must be
  excluded from the canonical content-hash computation. The vector itself is just a `d_k` coordinate tuple,
  identified by its hash. The association datom that binds the vector back to what it embeds, e.g.
  `[e :field/embeds <hash-of-the-d_k-tuple> t m=2]`, is a `:db/derived` (m=2) `d5` datom; the coordinates
  live at `d_k`, while the derivation marker lives on the association at the `d5` floor.

The `d_k` dimension is declared through the ordinary meta-protocol (`:dim/arity`,
`:dim/slots`, `:dim/encoding`); the content hash of that declaration is the dimension's
identity, so vectors produced under one embedding scheme never collide with another's.

## Geometric Matching: the read surface

Like `dao.space.query`, `dao.field` is a **library** any interpreter embeds and runs against a
`dao.jing`. It is pure: pull the `d_k` tuples, build a proximity index, answer. It owns no
durable state, and it reads the *global* store by default — a query vector surfaces geometrically close
tuples from writers the reader never heard of, associative by content.

```clojure
(require '[dao.field :as field])

;; near — the top-n tuples geometrically nearest a query vector, by cosine similarity.
;; The metric sibling of dao.space.query/match: match returns tuples that unify exactly,
;; near returns tuples that are geometrically close, ranked by descending similarity.
(field/near store query-vector {:top-n 8})
;; => [[tuple similarity] ...]

;; near also accepts a d_k tuple already in the store as the probe
(field/near store probe-tuple {:top-n 8 :threshold 0.75})

;; embed — project a value into a d_k tuple under an explicitly named model,
;; then append the association (a :db/derived datom) to the caller's own stream.
(field/embed store value {:model model-ref})
```

`near` is to `dao.field` what `match` is to `dao.space`: the light, single-probe surface. Richer
field queries (geometric proximity joined with structural predicates — "the todo work items nearest this
description") compile across both siblings, `q` supplying the structural constraint and `near`
the metric one, because they read the same `dao.jing`.

## What carries over, and the one thing that does not

`dao.field` inherits the whole substrate discipline unchanged. Writes are **append-only**: a
vector enters by being appended to a `dao.stream` member log, exactly as any tuple does (see
[The Write Path](dao.space.md#the-write-path)); there is no destructive update. Reads are
**global and associative**, scoped only by content predicates, governed by the same public /
controlled access modes as `dao.space` (see
[`dao.space.security.md`](dao.space.security.md)) — an embedding is just a tuple, so per-stream
permissions and confined interpreters apply without change (though v1 ships public mode only, see `dao.space.md`). Coordination is **stigmergy**:
agents leave `d_k` traces and others follow the gradient, no broker, no addressing.

The one thing that does **not** carry over for free is **replayability**. A `d_k` tuple's
coordinates are self-contained data — its hash pins the vector — but *which* `d_k` a given
value maps to is the embedding **model's** doing, and content hashes are "derived by
interpreters, not intrinsic to the tuple." So a geometric query result only replays identically if
the **model is pinned**: same model, same version, same normalization, ideally itself
content-addressed and referenced from the derived embedding datoms. Unpinned, the vectors are
interpreter-local scratch — useful, but *outside* the guarantees the datom log confers, the
"opting out" case `dao.space.md` describes. Pin the model and `dao.field` is a full peer of
`dao.space`: same `dao.jing`, a different space made from the same n-tuples.

## Lineage

The tuple space is **Linda's** contribution and lives in [`dao.space`](dao.space.md); the
storage and log traditions (Datomic, Plan 9) live in [`dao.jing`](dao.jing.md) and
[`dao.stream`](dao.stream.md). `dao.field` adds the **metric** tradition: cosine similarity
and geometric proximity as the coordination
verb, and the matrix→wave / discrete→continuous correspondence
([`dao.space.discrete-to-continuous.md`](dao.space.discrete-to-continuous.md)) as the
justification that the tuple set carries a real geometry, not a metaphorical one.

The synthesis: **`dao.field` is the vector field that emerges when the embeddable `dao.field` library
reads the same `dao.jing` tuples as `d_k` coordinates and agents coordinate by the geometric proximity
between the traces they leave there — the metric sibling of the tuple space
`dao.space`, both points in one moduli space of databases over n-tuples.**
