# Stream Discovery: Decentralized Naming, Attention, and Ranking

**Status: proposal.** This document records a design conversation (2026-07) about how
agents find streams at internet scale. Nothing here is implemented; it exists to pin
down the mechanisms worth building, the mechanisms explicitly rejected, and why. The
implemented baseline it extends is the membership root (`:root/members`,
`src/cljc/dao/space/index.cljc`) — within-store enumeration, written at `open!` by the
`:dao-stream` transport and folded by `dao.space.query`.

**Related documents:**
- `docs/design/dao.space.md` — the tuple space; *Membership is intake*
- `docs/design/dao.jing.md` — the storage boundary; segment/root keyspace
- `docs/design/dao.jing.dht.md` — Kademlia backend; sortition and Sybil open questions
- `docs/dao.space.stigmergy.md` — coordination by traces, the founding thesis
- `docs/ideas/agent-web.md` — ShiBi and pay-for-truth (critiqued below)

## The Problem

`:root/members` is the degenerate single-store case of discovery, exactly as the old
shared `:root/datoms` was the degenerate single-stream case of storage. It hits three
ceilings at scale:

1. **The value grows O(N).** The member set lives inline in one v-map, rewritten
   wholesale per registration — an unbounded value traveling through `cas!`, the same
   disease the wholesale datoms root had.
2. **One cell = one write authority.** Over a DHT, one key means one neighborhood owns
   it and every join is a consensus round through it: the global transactor,
   reintroduced at the membership layer.
3. **Enumeration stops being meaningful.** "The list of all member streams" is not a
   thing any reader can fold at internet scale, and complete enumeration is only a
   sensible primitive when the space is small enough to fold.

So discovery must become what everything else in this architecture becomes:
decentralized, published, and read-side. The governing reduction, which every
mechanism below instantiates:

> **Discovery facts are datoms in single-writer streams. What varies is only whose
> streams a resolver chooses to read.**

## What DNS Teaches, and What It Gets Wrong

DNS is the scaling benchmark: ~370M names, hundreds of billions of lookups a day. Its
load-bearing properties:

1. **Hierarchical delegation, no global write point.** Zones each have one authority;
   registering a name touches only its zone. The root zone is tiny and nearly static.
2. **Read-side caching with bounded staleness** (TTLs; eventual consistency by
   contract).
3. **Resolution is an iterative walk** from a well-known root, not a broadcast.
4. **Write/read asymmetry**: registrations rare, lookups constant.

The key observation: **a DNS zone is exactly a single-writer stream, and a delegation
is exactly a datom.** DNS engineered zone transfer, secondaries, cache invalidation,
and DNSSEC to approximate properties datom.world gets by construction: immutable
content-addressed segments are infinitely cacheable with perfect invalidation (a
cached segment is never stale, only superseded), integrity is the hash, and staleness
confines itself to one thing per zone — the head. The whole TTL apparatus shrinks to
"how old may my copy of the head be."

What DNS gets wrong, for our purposes, is the singular root: ICANN and the root
signing key are a governance chokepoint. Keep the sharding; dissolve the root.

The constraint to design under is **Zooko's triangle**: names can be decentralized,
secure, and human-meaningful — pick two. DNS picks secure + memorable and pays with
the central root. The mechanisms below each pick a different pair, and they compose.

## The Mechanisms

### 1. Self-certifying identities (secure + decentralized)

The kickoff hash *is* the name. A stream announces its signed head into the DHT under
its identity hash; resolution is one lookup plus local signature verification against
the key the kickoff commits to. No authority exists because none is needed — the name
proves its own binding (git commit hashes, IPFS CIDs). This is the foundation layer:
every other mechanism resolves *to* these names. Its defect is that a hash is not a
name a human can hold.

A directory entry binds an *identity*, not a state, so it never goes stale when a
stream's head moves — directory churn stays at true membership churn.

### 2. Petname webs (memorable + decentralized)

Every agent publishes its own directory stream, mapping local names to identity
hashes:

```clojure
[e :dir/petname "bob"           t m]
[e :dir/stream  <bobs-kickoff>  t m]
;; delegation to another directory:
[e :dir/petname "clojure"       t m]
[e :dir/delegate <dir-kickoff>  t m]
```

Names are *paths from a chosen anchor*, not absolute strings: `bob/carol` means "the
stream my `bob` calls `carol`," resolved by folding along directory streams. This is
SDSI/SPKI linked local namespaces, Scuttlebutt's follow graph, the petname literature
made of datoms. There is no global `"carol"`, and that is the point: naming is
interpretation, chosen by the reader. Communities converge on well-known directory
streams — a "root" is a convention some readers adopt, forkable, never a structural
privilege.

Registration by strangers is generative communication, never a shared write: deposit
a registration-request datom in *your* stream; the directory owner observes it
(stigmergy) and appends the binding to its own.

### 3. Stigmergic membership (the native mechanism)

Dissolve registration entirely: an agent announces itself by depositing facts in its
own stream — `[me :stream/exists true]`, `[me :stream/follows <hash>]`,
`[me :stream/topic :work/scheduling]`. Membership is maintained nowhere; it is the
read-side transitive closure of announce/follow datoms from wherever a reader starts.
Announcements form a grow-set CRDT (datoms merge by union — convergent with no
coordination, by construction). Reach is bounded by the social graph, which doubles as
spam defense: an unfollowed announcer is not in anyone's closure. This is the endpoint
`:root/members` evolves toward: the cell dissolves into datoms, and discovery becomes
a query.

### 4. Rendezvous topics (discovery by interest)

For "who has streams about X" without privileged indexers: hash the topic (an
attribute, a schema name) to a DHT key; providers deposit small self-signed provider
records at the responsible neighborhood; seekers look up the topic key and verify.
(IPFS provider records, gossipsub topics.) The neighborhood stores but never authors.
This is also how you *find indexer agents* without anyone anointing them.

### 5. Consensus namespaces (globally unique + memorable — bought with consensus)

Namecoin/ENS-shaped first-writer-wins claims ordered by a consensus log. Honest
assessment: the expensive corner of the triangle, importing the same
sortition/Sybil-resistance research program `dao.jing.dht.md` already flags. Most
systems that thought they needed this needed (1)+(2). If ever, spec it as an optional
community service — a name-registry *agent* with an auditable claim log — never as
infrastructure.

### Synthesis

Identities are self-certifying (1); humans name identities through petname webs (2)
with community directories as adopted conventions; presence propagates stigmergically
(3); interest-based rendezvous (4) finds strangers and indexers; (5) only if a
community insists. No layer has a privileged writer. The namespace, like the tuple
space, is not an artifact anyone owns but a behavior readers reconstruct from whose
claims they choose to fold.

## Discovery as Attention, and Where Markets Belong

Discovery at internet scale is attention allocation: attention is the scarce
resource, spam is its theft, Sybil flooding is counterfeit demand for it. Economics is
the right lens for adversarial scarcity — and the mechanisms above are weakest exactly
where pricing is strong: cold start (nobody follows you yet) and spam (announcing is
free). But "attention market" blurs three transactions that must be separated:

- **Paying to be seen** — the speaker pays for reach. That is advertising,
  definitionally. **Rejected.** It ranks streams by willingness-to-pay (i.e., by
  capitalization, not relevance), and it contradicts the project's own pay-for-truth
  thesis (`agent-web.md`): pay-for-truth flows money *toward* the party being attended
  to, at the attendee's request; paid reach flows money *from* the party demanding
  attention. Opposite signs. It is also born Goodharted (below).
- **Paying to deposit** — a tiny flat cost to announce (Hashcash, postage).
  **Accepted.** This prices spam without ranking by wealth. Note it needs a *cost*,
  not a *market*: proof-of-work or burned stamps suffice; no exchange, no price
  discovery, no ledger.
- **Paying for discovery service** — buying an indexer's query answers.
  **Accepted.** That is pay-for-truth applied to discovery, already the doctrine.

### Goodhart's law

"When a measure becomes a target, it ceases to be a good measure" (Goodhart, 1975). A
metric is informative precisely because it is a side effect of the thing cared about;
attach rewards to the metric and participants optimize it directly, severing the
correlation (citation rings, teaching to the test, link farms vs. PageRank's
twenty-year SEO arms race). Consequences here:

- **Paid placement is born Goodharted**: the ranking metric is purchasable by
  construction — no attack required; degradation is the equilibrium.
- **Trace-evidence ranking (below) is Goodhartable too** the moment ranking depends on
  it. It starts honest and must be *defended*: the defense is making signal
  manufacture expensive by weighting references by the referencing stream's own earned
  standing — recursion, which is PageRank's move.

### The ShiBi precondition

ShiBi is currently a capability token in the README and a fungible currency in
`agent-web.md`. These are different objects with different physics: capabilities
(unforgeable authorization; composable, delegable, no global state) fit this
architecture immediately; a currency needs double-spend resolution, i.e. global
consensus — the exact machinery every layer here has avoided, and the hardest open
problem in the stack. **Before ShiBi is load-bearing in any design, force the
decision: capability or currency.** Discovery must not depend on solving decentralized
money.

## Ranking by Trace Evidence

The stigmergic answer to ranking, and the datom-native one: attention allocated by
recorded traces of use, not by payment. Ants do not auction trail placement — the
pheromone gradient *is* accumulated evidence. The evidence is already in the medium:
follow datoms, petname bindings, cross-stream references — and VAET is literally the
reverse-reference index ("who points at this").

The mechanism is **personalized PageRank** over the cross-stream reference graph
(PageRank itself was citation analysis — attention along traces of use — i.e.,
stigmergy's formal core; the substrate was built for it):

- **Not global PageRank.** One global ranking reintroduces a privileged universal
  view owned by whoever computes it. The personalized variant restarts the walk from a
  *seed the reader chooses* — your stream, your follow set, your community's
  directory. Ranking, like naming, becomes interpretation relative to a chosen anchor;
  different readers legitimately compute different rankings from the same substrate.
- **Sybil resistance falls out.** Personalized PageRank confines Sybil influence to
  the probability mass flowing through attack edges from the seed's neighborhood
  (SybilRank and kin are personalized-PageRank variants). An attacker no one you
  transitively weight references does not exist for you.
- **Computation decentralizes.** Nobody needs the whole graph: indexer agents compute
  rankings from their vantage and publish them as ordinary signed datoms under their
  own roots. A ranking is just another published, reader-verifiable view; competing
  indexers with different seeds coexist like competing directories. (Google-the-company
  is what you get when the link graph is proprietary reconstruction; here the graph is
  a commons and ranking is a service anyone can render.)

## Dependencies and Open Questions

Everything above rests on prerequisites already tracked elsewhere:

1. **Signed heads / kickoff-hash identity** (`dao.jing.md`, namespace stamping;
   `dao.jing.dht.md`) — mechanism (1) *is* this discipline; (2)–(4) resolve to it.
2. **Namespace stamping** (`dao.space.query.md`, Ruling 3) — sound cross-stream
   merges, prerequisite for folding strangers' directories.
3. **Postage design** — what a deposit costs and who verifies it (proof-of-work vs.
   stamps) is unspecified.
4. **`unregister-member!` / liveness** — even the interim membership root has no
   eviction story; the stigmergic mechanism (3) needs a liveness convention
   (e.g., announce TTLs as datoms) so dead streams fall out of closures.
5. **The `:dir/*` and `:stream/*` vocabularies** above are sketches; a resolver-walk
   spec (path syntax, delegation semantics, cycle handling) is unwritten.
