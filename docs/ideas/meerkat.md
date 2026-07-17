# Lessons from Meerkat

**Source:** [Introducing Meerkat: an experiment in global consensus](https://blog.cloudflare.com/meerkat-introduction/)
(Cloudflare Research, 2026-07-08)
**Analyzed:** 2026-07-09
**Status:** Synthesis note. Surfaces architectural decisions for `dao.jing`,
`dao.jing.dht`, and `dao.space`; does not change any code or API.

Meerkat is Cloudflare Research's global consensus service, built on **QuePaxa**
(a leaderless consensus algorithm, EPFL 2023). They layer applications (a
transactional KV store, a leasing system) atop a replicated log of *slots*.
This note records what `datom.world` can take from that design.

The headline is not "a new idea to import." Meerkat is **independent, at-scale
validation of `datom.world`'s central thesis**, and it shows a more developed
instance of the *one* part this project has left as an open sketch: the
consensus mechanism itself.

---

## 1. The thesis you share: collapse the system to one dumb layer + smart readers

Meerkat's core principle, stated almost verbatim in the post:

> Meerkat's core doesn't care what [log events] are. Meerkat *applications*
> care... applications read the log events and construct state.

That is `dao.jing`'s "pure syntax, it holds form, never meaning... semantics an
interpreter projects onto it" (`design/dao.jing.md`, *What DaoJing Is*). And
Meerkat's log of *slots* is this project's `t` axis: the `t` in `(e a v t m)` is
a monotonic log position, and the indexes are built by folding the log in `t`
order (the `log` field in `design/dao.db.md`, *In-Process Implementation*).
Meerkat's KV application "constructs an in-memory key-value store from the log
events"; the Peer/index does the identical thing for datoms.

**Lesson.** Take the validation seriously. The discipline of keeping the
storage/consensus layer dumb and projecting all meaning from readers above it is
the same bet Cloudflare Research is making for global control-plane data. Where
the two differ, Meerkat suggests this project is on the right side of it: it has
kept the layer *dumber* (opaque bytes, no slot semantics at all).

---

## 2. The architectural fork: register-consensus vs. log-consensus

This is the most important architectural lesson, and it lands directly on
`design/dao.jing.dht.md`'s open section *`cas!` over the network*.

`datom.world` isolates *all* mutability to one CAS-register per stream root:
"`cas!` is the only mutation, therefore the only consensus"
(`design/dao.jing.dht.md`, *`cas!`*). That is **register consensus**: agree on
the latest value of one mutable reference.

Meerkat is **log consensus**: agree on a *sequence* of slots, then every replica
replays deterministically. These are different problems with different costs:

| | `datom.world` `cas!` (register) | Meerkat (log) |
|---|---|---|
| Abstraction | one mutable root per stream | global sequence of slots |
| Per-stream writes | single-writer, no contention | any replica proposes any slot |
| Retry on conflict | re-read rev, re-contend the same key | failed proposal gets a *later* slot |
| Cross-stream consistency | no single order (tuple space) | one total order |

**Lesson.** This project's model makes each stream a single-writer log and the
whole space a *merge of independent logs*. That is genuinely more decentralized
than Meerkat's one global log; it is the Linda / tuple-space lineage, not the
state-machine-replication lineage. That is a real virtue, but it carries a price
Meerkat does not pay (see §6). The honest question to settle in a design doc is:
**for the state that needs a *globally agreed* sequence** (membership: "which
streams count as members of the space"; schema agreement; placement /
leadership), a per-stream CAS-register is the wrong primitive; routing it through
CAS would be rebuilding consensus-per-slot, i.e. rebuilding Meerkat. Decide
explicitly which state is tuple-space-merged versus which needs a single agreed
log, and do not smuggle the second through the first.

---

## 3. QuePaxa and the permissioned model resolve most of the open `cas!` questions

`design/dao.jing.dht.md` (*`cas!` over the network*) proposes Algorand-style
cryptographic sortition and candidly lists five unsolved problems: Sybil
resistance, committee discovery, seed circularity, finality model (FLP), and
lost-CAS retry. Meerkat is a worked answer to that *same* set of problems from a
different point in the design space: **permissioned, leaderless, log-based**.

- **Sybil resistance.** Meerkat runs on a *trusted* cluster the developer controls
  (`2f + 1` replicas placed in chosen data centers). There is no Sybil problem
  because it is not an open network.
- **Seed circularity / committee discovery.** No VRF, no committee to discover.
  All replicas participate; a client contacts *any* replica and fans out to
  several *concurrently*.
- **Finality model.** Deterministic finality (a decided slot is final), traded for
  one to three-plus round trips, not Algorand's probabilistic finality with
  fallback rounds.
- **Lost-CAS retry.** In a log model a failed proposal does not re-contend; it
  simply lands in a later slot.

`design/dao.jing.dht.md`'s own *Lineage* section already concedes the direction:
"A network `IKVStore` can be delivered today over the transports `datom.world`
already has (`dao.stream.http`, `dao.stream.ws`, both TCP)." That TCP-backended,
trusted-backend path *is* the permissioned model. Meerkat is evidence that the
permissioned model is enough for a great deal of real global infrastructure, and
it dodges all five sortition open-questions.

**Lesson.** Treat the permissioned model as the realistic first target for
`cas!`-over-the-network, and keep permissionless Sybil-resistant sortition as the
explicit research program it already is, rather than blocking distributed `cas!`
on solving all five problems at once. (Caveat: this is in tension with a stated
project invariant; see §5.)

---

## 4. The read model is already the right shape

Meerkat squeezes performance by separating reads:

> Not all reads must trigger a consensus round. If a developer is OK with reading
> *stale* (but never inconsistent) data, they can read from *any* replica's local
> data.

That is the Datomic Peer model this project inherited: peers read from a local
cached index at a `basis-t` / `as-of` snapshot, stale but never internally
inconsistent (`design/dao.db.md`, *Database as a Value*; `as-of` / `since`).
Writes go to the serialized path; reads are local. **Keep this.** Meerkat across
330+ data centers is proof that the stale-but-consistent local read is the
correct scaling axis.

Two Meerkat performance tactics also map onto things this project already half
has; finish them:

- **Batching** ("10 writes in 10ms become one proposal") is the transaction /
  segment model (a tx is already a batch of datoms). Do not let a future wire path
  break a transaction into per-datom round trips.
- **Bundling into one consensus round (transactions / compare-and-swap)** is
  Datomic-style atomic transactions, already present. Meerkat cites this as a
  *strength*; preserve it.

---

## 5. The project's own invariants are the real adjudicator

The above gets sharper once `agents/advanced-concepts.md` is in view. Its
ENTANGLEMENT section states four invariants (`agents/advanced-concepts.md:41-44`):

> One leader establishes event ordering.
> Failover must be explicit and observable.
> No hidden consensus mechanisms.
> Entanglement does not imply global truth: only shared causality.

### 5.1 Same disease, different cure: observable-leader vs. leaderless

The invariant is *not* "no leader"; it is "no *hidden, timeout-driven* leader
failover" (line 2). That is exactly Meerkat's indictment of Raft: "the tyranny of
timeouts," "these timeout values are hard to configure," "replicas will
constantly be timing out and thus blocking writes." This project and Cloudflare
Research independently named the same defect.

Where they diverge is the prescription:

- **This project (lines 1 + 2):** keep a single leader for ordering, but make
  failover an *explicit, stream-observable* event, not a silent timeout election.
- **Meerkat (QuePaxa):** remove the required leader entirely, so there is nothing
  to fail over.

This is a real fork, not an oversight, and it qualifies §3: adopting QuePaxa
would *contradict* the "one leader establishes event ordering" invariant. The
honest framing is that this project has chosen the **observable-leader** point in
the design space, and Meerkat demonstrates the **leaderless** alternative is now
industrially viable. A design doc should state *why* observable leader failover
is preferred: it is simpler to reason about, and "failover as a stream event"
fits the everything-is-a-stream discipline better than QuePaxa's concurrent
multi-replica proposals.

### 5.2 "No hidden consensus" favors QuePaxa over Raft, *if* the project ever goes leaderless

Line 3 is a selection criterion. Among consensus protocols, Raft's leader
election is the most *hidden*: opaque, destructively-interfering campaigns.
QuePaxa is arguably the *least* hidden: "concurrent proposals do not destructively
interfere; replicas work together to decide." So if `datom.world` ever moves off
the explicit-leader model, QuePaxa is the principled fit under its own invariant,
not generic Multi-Paxos, and not the Algorand sortition sketch (whose five open
problems in `design/dao.jing.dht.md` are precisely about hiding, or making
explicit, the consensus machinery).

---

## 6. "No linearizable reads" is a deliberate invariant, not a gap

This inverts a first reading. For linearizability, Meerkat makes **reads also go
through the log**: a `get` becomes a log event so it linearizes *after* pending
writes (the post's slot-3 / slot-4 example). A `cas!`-only-consensus model has no
equivalent: a reader doing `get` on a root gets whatever its local replica holds,
with no read-barrier forcing it behind in-flight `cas!`es.

But line 4 of the invariants, "Entanglement does not imply global truth: only
shared causality," means this project has **explicitly rejected** the global
linearizable truth Meerkat maximizes. So the absent linearizable read path is not
a missing feature; it is the *consequence* of a stated position. Meerkat is the
linearizability maximalist (even reads cost a consensus round); this project is
the deliberate causal minimalist.

That inverts the lesson. The real open work is not "add linearizable reads"; it
is: **if the commitment is "only shared causality," there must be an actual
causality mechanism to deliver on it.** Today a local `get` on a stream root
returns "stale," full stop. To honestly promise "stale but *causally
consistent*" requires causal tracking across entangled streams (version vectors,
happened-before, causal barriers over the member logs), so a reader can tell
"this snapshot does not yet reflect write W" rather than silently returning an
older value.

Meerkat spends a consensus round to *guarantee* a read observes all prior writes.
This project has deliberately declined to spend that, which is defensible, but it
means the causality machinery must carry the weight the consensus round otherwise
would. That is the concrete thing to design, and it lives at the `dao.space`
(tuple-space) layer, not at `dao.jing`.

Within a single stream, single-writer already gives a natural total order, so
reads are consistent with respect to that stream's own writes for free. The gap
is only *cross-stream*: a read spanning many member streams that needs a
consistent cut. That is the distributed-snapshot problem, harder for a many-log
tuple space than for Meerkat's single log (which never has to solve it). This is
the real tax of being more decentralized than Meerkat.

---

## 7. Performance and operational lessons

- **Latency is proportional to inter-replica distance.** For the DHT backend,
  peers far apart mean slow `cas!`. This argues for locality-aware placement,
  which `design/dao.jing.dht.md` gestures at but does not solve.
- **Batch writes.** Covered in §4; the transaction model is the mechanism.
- **Bundle operations into one consensus round.** Datomic-style transactions
  already do this. Do not break transactions into per-datom consensus rounds.
- **Formal verification + deterministic simulation testing.** Cloudflare is
  *formally verifying* the Rust QuePaxa implementation and using *deterministic
  simulation testing*, and reports that "leaders constantly fail, and the cluster
  keeps operating with no increase in error-rate." `design/dao.jing.dht.md` lists
  five subtle, partly-FLP-bound open problems in the one piece of code where a bug
  is catastrophic and nearly impossible to reach with unit tests. `CLAUDE.md`
  frames testing as "selection pressure," but consensus warrants more: this is the
  layer where simulation testing (run the whole replica fleet under injected
  faults, deterministically, replayably) earns its keep. Worth filing as a design
  intent for `dao.jing.dht` before the consensus code is written, not after.

---

## 8. The sweet spot: control-plane data

Meerkat is explicit that it is *not* a general-purpose database. Its consensus
costs (many round trips, latency tied to replica geography) make it "perfect, in
the short term, for control plane information that is written infrequently but
must remain consistent."

The analogue inside `datom.world` is the small, slowly-changing state that
coordinates the tuple space: stream membership, schema agreement, placement and
leadership, capability/registry roots. These are the natural candidates for
whatever consensus `cas!`-over-the-network eventually becomes, and they are
exactly the state that (per §2) probably should *not* be modeled as per-stream
CAS-registers if it needs a globally-agreed order.

---

## 9. Action items and open questions

1. **register-vs-log decision.** Write an ADR adjudicating, for each class of
   coordinated state (membership, schema, placement, leadership), whether it is
   tuple-space-merged (per-stream `cas!`) or single-agreed-log. Do not smuggle the
   second through the first.
2. **observable-leader vs. leaderless.** Record *why* the observable-leader
   invariant (`agents/advanced-concepts.md:41-42`) is preferred over the QuePaxa
   leaderless alternative Meerkat now proves viable. If the answer is "failover
   as a stream event fits the stream discipline," say so explicitly.
3. **causal-consistency machinery.** Design the version-vector / causal-barrier
   layer over member streams at `dao.space`, so "stale but causally consistent"
   is a real promise rather than a slogan. This is the load-bearing consequence
   of invariant line 4.
4. **first consensus target.** Treat the permissioned, TCP-backended
   `IKVStore`-over-network path as the first real `cas!`-over-the-network target;
   keep permissionless sortition as research.
5. **testing posture.** Add formal-verification and deterministic-simulation
   testing to the design intent for `dao.jing.dht` consensus code.

---

## References

- Source post: [Introducing Meerkat: an experiment in global consensus](https://blog.cloudflare.com/meerkat-introduction/)
- `design/dao.jing.md` (the storage boundary; dumb-KV thesis; pure syntax)
- `design/dao.jing.dht.md` (`cas!` over the network; the five sortition open-questions; *Lineage*)
- `design/dao.db.md` (four-component model; indexes; `as-of` / `basis-t`; read/write split)
- `design/dao.space.md` (the tuple space of member streams)
- `agents/advanced-concepts.md` (the four ENTANGLEMENT invariants, lines 41-44)
