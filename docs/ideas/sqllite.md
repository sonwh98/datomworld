# Yin.VM as VDBE: Discussion Summary

Status: discussion record, 2026-07-10. This captures a design conversation and the
conclusions reached. Nothing described here is implemented; where the conversation
touched already-specified mechanisms, those are cited by name so the record connects
back to the docs that own them.

## The Question

SQLite compiles SQL into VDBE bytecode, a dedicated virtual machine that executes
against the B-tree/pager layer. Does datom.world have, or need, an equivalent role for
`Yin.VM` relative to `dao.space`, `dao.space.query`, and `dao.jing`?

Laid side by side: `dao.jing` maps to SQLite's pager (a dumb, opaque byte store).
`dao.space.query` today fuses what SQLite splits into a B-tree module and an executor:
it builds the EAVT/AEVT/AVET/VAET indexes and runs `q`/`match` directly as native
Clojure, with no intermediate program in between. `Yin.VM` is already shaped like a
VDBE in general: its compilation pipeline (`docs/agents/architecture.md`) goes source
text -> Universal AST -> AST datoms -> {semantic, stack, register} bytecode -> VM
execution, the same move SQL-to-VDBE makes, just not yet aimed at queries. `dao.space.query.md`
names this gap explicitly: "a planner/optimizer front-end lowering into a
traversal-IR back-end... a direction, not yet specified here."

There's a second, independent sense in which `Yin.VM` already plays a VDBE-shaped
role by design: `dao.space.security.md`'s Controlled access mode specifies that a
governed query is pushed to the data owner as a `Yin.VM` AST and run there under
capability, only the attenuated answer returns. That's not about speed, it's about
confinement, but it's the same "computation compiles to a program executed next to
storage" shape.

## Why Yin.VM Can't Just Be VDBE

Pushed further: VDBE is purpose-built for B-tree traversal (mutable cursor structs,
zero-allocation dispatch loop, direct pointer arithmetic over pages). `Yin.VM` is built
around mobile continuations, and that's a genuinely opposed design goal, not just an
unoptimized one. `src/cljc/yin/vm/docs/state.md` states the CESK model's core property
directly: "every state is immutable, each step creates a new state," with continuations
threaded as persistent, parent-linked frames rather than a native call stack. That
immutability is exactly what makes a continuation parkable, serializable, and mobile
across nodes; it is in direct tension with the mutate-in-place, allocation-free
execution that makes VDBE fast. A second, independent gap sits underneath: the actual
index (`tonsky/persistent-sorted-set`) is itself immutable and content-addressed,
unlike SQLite's mutable page cache, regardless of which VM drives the traversal.

Conclusion: `Yin.VM` should not try to be the traversal engine. It should sequence
calls into one, the same way VDBE's opcodes are calls into `btree.c` rather than the
traversal logic itself. Those calls should be ordinary synchronous primitive
resolution (the same mechanism `state.md` shows for `+`, resolved from the
environment and invoked inline during `eval`), not the `dao.stream.apply` FFI bridge,
which carries real per-call overhead (request allocation, stream `put!`, park,
scheduler hop) appropriate for crossing trust or process boundaries, not for a tight
per-node loop.

## Where the Traversal Primitives Live

We considered a new namespace (`dao.mai`, meridian/channel, paired conceptually with
`dao.field`'s field metaphor) for these sequencing primitives, then decided against
creating it yet. The primitives (`seek`, `slice`, and similar) reuse `dao.space.query`'s
existing psset/`fold`/`read-datoms` machinery directly; there is no new concern here,
just a new caller. CLAUDE.md's own "Evolution, Not Design" and "High Cohesion, Loose
Coupling" principles argue for keeping them in `dao.space.query`: things that change
together should live together, and the index-realization work they depend on is still
actively evolving (segment GC, non-JVM laziness, and k-way federated merge are all
open per `dao.space.query.md`). `dao.mai` stays a reserved name if this surface later
earns real independence (multiple VM consumers, or cursor-mutability concerns
`dao.space.query` shouldn't have to carry).

One efficiency refinement matters here: the primitives need to be coarse-grained
(a whole `seek`/`slice` operation per call), not fine-grained (one call per tree node).
Fine-grained primitives would reintroduce the CESK per-step allocation tax on every
node touched, which defeats the point; a coarse-grained call pays that tax once per
query operation, the same way one VDBE opcode dispatch can internally walk several
B-tree levels without the dispatch loop being involved in that internal work. It's also
worth being precise that `dao.jing` itself stores opaque bytes, not a B-tree; the tree
is the interpretation `dao.space.query`'s psset layer already projects onto those
bytes (`dao.jing` "storage never interprets"), so these primitives read as `dao.space.query`
functions calling `dao.space.query`'s own index code, not as something reaching into
`dao.jing`.

## The Peer/Storage Network Boundary

`dao.space.query` and `dao.jing` are a logical boundary, not a physical one:
`dao.jing.dht` is an `IKVStore` backend that puts real network distance between a peer
running `dao.space.query` and the storage it reads, exactly Datomic's Peer/Storage
split. Initial instinct was that this would require `dao.stream.apply` to cross the
network boundary generally. That's not quite right, and the correction matters: `IKVStore`'s
`get`/`put!`/`cas!` are uniform across local and remote backends by construction.
`dao.jing.dht.md` states the intent outright: "the engine will simply ask `dao.jing`
for nodes; it will not know the index was fetched from a peer." The network transport
lives entirely inside the DHT backend's own implementation of `get`, not as a different
calling convention exposed to `dao.space.query`.

The real hazard is narrower: a remote `get` can block synchronously for an unbounded
network round trip (the DHT doc: "the client must own timeouts and retries"), and a
`Yin.VM` primitive that calls straight through to one would block the run-loop for
that duration, undermining the whole point of the mobile-continuation model. But the
same doc gives the seam: segment reads are cache-forever ("any node may cache any
segment forever, with no invalidation protocol") because segments are immutable and
content-addressed, so most traversal steps hit local cache and stay fast. Root reads
are the opposite, never cacheable, always requiring a fresh read. So the network-aware
path is narrow: the root read at the start of a query, and any cold segment fetch
during descent, not the traversal loop as a whole.

## Mobile Continuations: Push the Computation, Not the Bytes

The cleanest resolution to the network-boundary question turned out to be a mechanism
already named in the codebase rather than something new: **continuation parallel
transport** (`docs/agents/advanced-concepts.md`). Instead of `dao.space.query` pulling
bytes across the network for each root read or cache miss, it can serialize its
in-flight continuation (environment, partial results, frames; AST references resolved
by content hash) and ship it, via `dao.stream.apply`, to the node where `dao.jing`
physically lives. There it resumes and runs entirely locally: every node touch,
comparison, and descent step is same-process, same-memory, at full synchronous speed.
Only the final result crosses the wire, once.

This is the same move `dao.space.security.md`'s Controlled access mode already
specifies for query push-down ("`f` is pushed to where the data lives as datoms...
this is continuation parallel transport"), just applied here for locality/performance
rather than confinement. The security doc's version additionally requires the pushed
`f` to be restricted to a bounded-output/aggregation sub-language, so a governed
continuation can't just return the raw datoms; that constraint is about data
sovereignty and isn't necessarily needed when both nodes are inside the same trust
boundary and the only goal is avoiding network chatter. Same transport primitive,
lighter policy, depending on why the continuation is being relocated.

Architecturally this also simplifies the earlier per-primitive park/resume design:
rather than every `dao.space.query` primitive independently deciding whether a given
call might hit the network, the decision moves to one coarse point, whether to
relocate the continuation before running a query at all. Once relocated, the
traversal primitives never need network-awareness in their own bodies, because by the
time they execute they are, in fact, local.

## Status

Everything above is design-level, matching the honesty convention the rest of this
doc cluster uses. The compiler pipeline from query plan to `Yin.VM` bytecode does not
exist. The Controlled access mode is "specified... not yet realized"
(`dao.space.security.md`, Honest Limits): capability-gated effect handlers aren't
built, and `dao.jing.dht`'s own network backend is a partial first cut
(`dao.jing.dht.md`, Status table). The one thing that is solid across all of it: the
layering holds together without contradiction, `dao.jing` stays dumb, `dao.space.query`
stays the interpreter, `Yin.VM` sequences rather than replaces the traversal engine,
and continuation parallel transport is the single mechanism that serves both the
confinement story and the locality story.

## Related documents

- `docs/agents/architecture.md` — the `Yin.VM` CESK machine, the AST-to-bytecode
  compilation pipeline
- `docs/design/dao.space.md` — the tuple space, the Three Boundaries framing, Public
  vs. Controlled access modes
- `docs/design/dao.space.query.md` — index realization, the deferred
  planner/traversal-IR compiler direction
- `docs/design/dao.jing.md` — the storage boundary, the segment/root keyspace
- `docs/design/dao.jing.dht.md` — the distributed `IKVStore` backend, cache-forever
  segments vs. non-cacheable roots
- `docs/design/dao.space.security.md` — Controlled mode, capabilities, the
  bounded-output sub-language
- `docs/design/dao.stream.apply.md` — the standalone async request/response protocol
  `Yin.VM` FFI is one consumer of
- `docs/design/yin.vm.ffi.md` — how `Yin.VM` uses `dao.stream.apply` for host calls
- `docs/agents/advanced-concepts.md` — parallel transport, including continuation
  parallel transport
- `src/cljc/yin/vm/docs/state.md` — the CESK state shape and its immutability
  guarantee
