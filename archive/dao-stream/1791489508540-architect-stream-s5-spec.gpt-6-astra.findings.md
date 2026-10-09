Created-GMT: 2026-10-08 19:58:30 GMT
Created-Local: 2026-10-09 02:58:30 ICT
Coding-Agent: codex
Session-ID: not exposed by runtime

# Track B Slice S5 — Multi-Hop Routing, Peer Addressing & Identity Discovery

Role: Lead System Architect. Specification and implementation recipe.
Status: proposed implementation contract; no S5 implementation or test success is claimed.
Requested artifact label: gpt-6-astra; runtime model identity is not independently asserted.
Workspace: `/Users/sto/workspace/datomworld-stream-s5`.

## 1. Authority, scope and decisions

Read together, in precedence order:

1. [Foundations](../docs/design/datom.world.md) and [stream contract](../docs/design/dao.stream.md).
2. [Remote architecture](../docs/design/dao.stream.remote.md), especially §§2–6.
3. [S4 specification](../archive/stream-crossmachine-s4/1791403500000-architect-stream-s4-spec.claude-fable-5-1.findings.md) and [S4 sign-off](../archive/stream-crossmachine-s4/1791405000000-architect-stream-s4-signoff.codex.findings.md).
4. Current `remote_channel.cljc`, `remote_pair.cljc`, `remote_meet.cljc`, their tests, `remote.cljc`, `ws_project.cljc`, and the lease implementation.

S5 delivers a bounded, driver-stepped composition for reaching a stream through
zero, one, or multiple relay peers, discovering candidates through explicitly
chosen meeting boards, and resolving a stream name at the destination. Two
relay peers in one operational path are the minimum multi-hop acceptance case.
Every peer may publish, request, answer, meet, or relay. These are local roles;
no peer receives protocol privilege. A relay serves ordinary media and does not
inspect the inner request/answer values stored in them.

**Decisions:** retain the four remote wire operations and current descriptors;
route by finite composition of channels; keep peer names in convention payloads;
expose pair stepping and cleanup; complete real meeting-descriptor publication;
make discovery, liveness, lease ownership and all admission limits explicit.
No route opcode, global directory, transparent append replay, cursor translation,
authentication protocol, new cryptographic identity format, WebRTC, or automatic
internet-wide path search enters S5.

The discovery design is marked v1/proposal with its implementation retired.
Its kickoff-hash discussion is conceptual authority for naming, not evidence of
an available signed discovery service. S5 must not revive its retired APIs.

## 2. Baseline findings that determine the work

| Area | Existing behavior | S5 requirement |
|---|---|---|
| Direct channel | `remote-channel` exposes WS serve/dial, neutral loss causes, supplied `now`, finite production profile | Preserve S4 behavior; extend composition without exposing WS above this seam |
| Pair | Attacher caches per descriptor; input gap/end or terminal read failure ends pair; fresh attach resumes at newest | Preserve; expose both ends for symmetric mirror composition and stepped links |
| Pair policy | Only events, resend-after and budget are forwarded | Forward drain/outstanding/filed/deadline bounds and supply time before probes |
| Pair ownership | Second attach failure leaves first handle unclosed; cached ends have no public release/eviction | Explicit ownership, rollback, terminal detection and bounded cache |
| Pair initialization | Failed cursor mint loses the failure classification; terminal failure before first cursor can keep retrying | Preserve terminal result and end the binding even during initialization |
| Meeting | Board/request media, observed-address middleware, capacity gate, fanout, pair leases exist | Correlation, freshness, routable descriptors and bounded lifecycle composition |
| Publication | Pair descriptors contain `:dao.stream.remote-meet/served` placeholder channels | Publish actual reachability through the granting board's channel; no placeholder escapes production |
| Dynamic table | Meeting mutates a table atom; existing WS acceptor holds a map snapshot | Supply current table/name values explicitly each tick; no hidden registry or table lookup callback |
| Reclaim | Removes three entries but does not close their owned rings or unwire renewal medium | Close owned resources and unwire after judging; retain lease contract semantics |
| Incarnation | Meeting counter starts at zero and produces `pair-0` etc. | Include caller-supplied fresh incarnation in pair, renewal and lease identities |
| Bounds over lifetime | Active pair count is bounded; judge historical ledger/seen state can grow across churn | Bound lifetime admissions per judge epoch and rotate only when quiescent |

Existing pair tests cover one relay, gap, terminal input failure, reattachment and
shared links; meeting tests simulate restricted and symmetric NAT and carry lease
renewals. They do not establish real multi-host multi-relay operation. S4 accepted
its scoped architecture, not unconditional success of all repository test lanes.

## 3. Names, identities and resolution

Keep four distinct values:

- **Peer name:** caller-selected self-certifying key/kickoff hash, opaque to the
  remote protocol. It labels convention facts. It is never substituted for a
  stream identity or a channel attachment identity.
- **Local alias:** composition-owned map such as `{"bob" peer-name}`. No global
  uniqueness, registration or hierarchy is implied.
- **Stream name:** lookup key in the destination's name map, resolved using the
  existing named descriptor request on an established channel.
- **Stream identity:** the exact identity returned by the source descriptor;
  identical through direct and relayed handles. Channel identity describes
  reachability and is a separate value.

The initial input is a target peer name (or locally resolved alias), a target
stream name OR exact stream identity, and an ordered finite vector of bootstrap
board descriptors or already known routes. A hash alone supplies no network
location. Empty bootstrap data yields a local `:no-route` convention result.
A name collision on different boards is retained as conflicting candidates,
scoped by board identity and incarnation; it is never silently merged into an
identity claim. Candidate selection follows caller order, then board observation
order. Alternatives are tried sequentially within the attempt/deadline bound.

S5 connects to a *claimed* peer name. Hash/key equality, when public key material
is available through an existing canonical encoding, checks that material's
binding only; it does not prove possession, authenticate the channel, or authorize
access. Do not invent a key serialization or label a board answer authenticated.
Signature/challenge verification remains a later middleware composition. Every
result retains discovery provenance so a future verifier can apply policy.

Resolution pipeline:

1. Attach only explicitly supplied boards. Mint the board cursor before sending
   an announcement or pair request; keep that cursor through the exchange.
2. Read bounded facts, validate convention shapes, correlate replies, and derive
   a bounded local candidate map with local observation deadlines.
3. Establish the chosen direct or pair channel and confirm it with an ordinary
   correlated descriptor request. Deferred `attach!` success is not confirmation.
4. On the final peer's channel, call existing remote `resolve` for a stream name,
   then attach the returned identity. Never resolve that name against the relay's
   table merely because the relay carried the request.
5. On path loss, rediscover/re-resolve names. Exact identity requests continue to
   name that identity; they must not silently select a replacement stream.

No unbounded follow of board references. S5 accepts caller-composed routes and
bounded board candidates, not a distributed routing algorithm or DHT crawler.

## 4. Multi-hop topology and channel composition

For one relay M, two media X and Y form the pair. A reads X and writes Y; B reads Y
and writes X. M exposes both through its table; A and B each run their own mirror
and link over their channel ends. Inner descriptors name B's or A's source stream,
never X or Y in place of that source identity. Envelopes remain unchanged.

A concrete two-relay path is `A ↔ M1 ↔ M2 ↔ B`:

1. A and M2 reach M1 using base channels. M1 grants X1/Y1. A and M2 acquire
   opposite ends, giving A a channel to M2 through M1.
2. M2 and B use a second pair X2/Y2 served by M2. A reaches X2/Y2 through the
   first pair; B reaches them through its base channel to M2.
3. A's final pair descriptor has `in` and `out` remote descriptors for X2/Y2
   whose channels are the first pair. The inner remote descriptor names B's
   requested stream over this final pair. B holds the reversed final pair end.
4. M1 handles only X1/Y1 operations. M2 handles only X2/Y2 operations on the
   A-facing channel. B interprets requests for B's application stream. Each peer
   may simultaneously serve its own streams in the other direction.

The descriptor tree is plain finite data; repeated structural subtrees are
allowed and shared locally. Preflight bounds encoded descriptor bytes, node
count and maximum nested pair depth before allocating sockets or handles.
Reject recursive local route references and revisiting an active route dependency;
no recursive attach that can run without consuming a finite depth budget. A route
plan explicitly records dependency edges and ownership, rather than assuming a
global peer graph. Route metadata lives in composition state, not extra remote
request keys or canonical source descriptors.

### 4.1 Pair composition API

Add `remote-pair/links` as the full assembly entry, keeping `attacher` as the
compatibility projection of its `:attach` operation. The full assembly provides
`:attach`, `:resolve`, `:step`, `:release!` and a channel-end accessor for the
composition's mirror. Exact private helper names are discretionary; these
capabilities and semantics are required:

- Key live entries by the complete pair descriptor within one assembly. Share
  one inner link for all identities on that entry. Retain `remote/links`, not
  just its `:attach` function. Forward all supported link policy keys.
- `:step` takes the channel descriptor and driver `now`; it advances initialization,
  link deadlines and cleanup within supplied budgets. Lower channels are advanced
  by their owning route driver once per scheduled visit, not recursively drained
  to quiescence. Stamp `now` before issuing probes.
- Expose one reader and writer for mirror/link observers. The reader must obey
  independent opaque cursor semantics: the existing single hidden cursor must not
  let a link drain consume requests before the mirror sees them. Project the input
  reflection into a bounded local ring, with independent link and mirror cursors.
  Initialize the outer input cursor once at newest per fresh binding. A gap in
  that channel ring, as in the relay input, terminates the binding.
- Read and write source operations preserve their ordinary outcomes. A local
  reflection or channel failure must not be turned into an invented source gap.
- Closing one application reflection does not close shared pair dependencies.
  Route owner release retires the entry, accounts for pending appends once, closes
  its owned reflections/ring, and releases explicit dependency references. Do not
  close a shared base connection still used by another route, or a source handle.
- Failure attaching `out` closes the newly owned `in` reflection; any later failure
  unwinds only resources acquired by that construction. Failure before the initial
  cursor, write-side terminal failure, and deadline expiry all retire an entry.
- No automatic append retry. A retryable cursor failure remains live; `not-found`
  or `channel-gone` during cursor initialization is terminal, not an eternal retry.
- Retired cache entries are removed. A fresh attach on the same descriptor starts
  at newest with new request state. Prior outstanding appends remain unknown.

Both input cursors must exist before peers publish readiness. Add correlated
`:meet/ready` convention facts on the board for the two sides of a granted pair;
only after both have been observed may the route issue inner application probes.
Readiness is a scheduling barrier, not authentication. This avoids losing early
probes when the opposite end later mints newest. A lost readiness fact times out;
there is no assumption that ring retention makes establishment reliable.

### 4.2 Remote-channel remains the upper boundary

Add a descriptor/route branch to `remote-channel` (or a private helper namespace
it delegates to), preserving existing direct endpoint-spec arities and results.
Upper consumers use its neutral dial/step/handle/stop vocabulary. They do not
import `ws`, `ws-project`, UDP host adapters or pair internals.

Both accepting and dialing compositions must accept caller-supplied table and
name snapshots before their next mirror pass, including channels through pairs.
Use an explicit update operation and thread returned composition state; do not
make the table a globally dereferenced atom or a callback that fetches application
state. Validate entry surfaces on updates. Meeting publishes a snapshot after
allocation/reclaim, and the driver installs it before answering that tick.
This also removes the present assumption that the dialing side serves `{}`.

S4's terminal causes stay stable. Route results may add qualified convention
provenance identifying failed dependency and stage; they do not rewrite RPC
terminality. `:opened?` for a route means final-channel confirmation, while each
base dependency retains its own S4 connection history. A failed shared dependency
necessarily loses all dependent routes; unrelated routes and listener sessions
remain usable.

## 5. Meeting facts and leases

Retain existing `:meet/here`, `:meet/pair`, `:meet/seen`, `:meet/pair-for`,
`:meet/asker` and carried grant fields. Add open convention fields, not wire ops:

```clojure
;; Example pair request; all placeholders are portable values.
{:meet/pair target-peer-name
 :meet/request request-id
 :meet/peer requester-peer-name
 :meet/incarnation requester-incarnation}
;; Board reply carries the same :meet/request, :meet/peer and incarnation,
;; plus existing pair-for/asker/grant/in/out/renewal fields.
;; Ready is appended through meet-requests and republished by its interpreter:
{:meet/ready lease-id :meet/request request-id :meet/side :in
 :meet/incarnation requester-incarnation}
```

Request IDs are fresh caller-minted portable values supplied by the composition;
peer/incarnation claims do not override mirror-observed `:meet/from`. Define sides
as `:in` for the original asker's orientation and `:out` for the reversed one.
Validate exactly one convention operation per value; malformed values consume
one fanout unit and yield a bounded diagnostic, without throwing across sessions.
Correlate duplicate requests by observed requester context, incarnation and request
ID. A repeated live request returns its existing grant; it never allocates a
second pair. Correlation retention has a finite bound and deadline; after it has
expired, unknown old requests are not replayed by the client. A client needing
another pair starts a new request and accepts that an unobserved old grant may
live until expiry.

The meeting constructor receives an advertised channel descriptor and a fresh
incarnation. Grants publish real remote descriptors using that channel. For a
meeting peer itself reachable by several routes, the receiving composition may
substitute its already confirmed channel to that same grantor; preserve stream
identities and provenance. Never derive a publicly dialable address from a WS
attachment token. A UDP reflexive address comes only from transport-observed
source data, never a payload's claimed address.

Registrations are unleased observations, matching current implementation. S5
amends remote architecture §6's ambiguous reference to registration leases:
only pair postings carry pair leases. Registration freshness is reader-local
observation age, not a grant or remote wall-clock deadline. Refresh publication
on the driver's schedule. A board gap invalidates pending discovery/readiness
certainty and the candidate snapshot; resume at its recovery cursor and request
fresh facts. Previously confirmed routes remain independently live until their
own deadline/lease fails. A stale record can suggest a candidate, never prove
current reachability or extend a lease.

The grantor judges resources it owns. Pair identities, renewal identity and lease
ID include incarnation and never repeat after restart. Enter `:dao.lease/lease`
on leased table entries. A grant is complete before publication; holder renewal
uses the existing lease API and source-confirmed append events, never outbound
acceptance as evidence. Renewal facts, grants, judge ticks and lapse records
travel through explicitly wired media. Forward lapse observations to the board
with provenance so readers can invalidate pair candidates.

Reclaim closes the two owned relay rings and renewal ring, removes their table
entries, decrements live count once, and queues removal of the renewal fact reader.
Apply `lease/unwire-facts` to the returned judge state after its step: do not mutate
the same judge atom inside a reclaim callback whose result will then overwrite
that mutation. A repeated reclaim succeeds idempotently. Removal from a table
alone is not proof that memory or judge work has been released.

The existing judge intentionally retains historical lease state. Do not prune
its `:seen`, ledger or answered records ad hoc. Bound total admissions in one
meeting epoch; once reached, refuse new grants while servicing/renewing existing
ones. Rotate to a fresh incarnation/judge only after all leases are reclaimed,
all pending lease output is delivered or explicitly ended, all renewal readers
are unwired, and old tables are retired. Long-lived holders may prevent rotation;
new admissions then remain explicitly refused. This trades availability under
churn for a real finite memory bound, without changing lease semantics.

Grant publication failure must not leak capacity silently. Keep at most one
bounded pending publication per allocated pair; retry only local `full`, never
reallocate. At publication deadline, stop offering that pair and let its judge
reclaim by composed policy. A board with evict-oldest retention may accept and
later lose the posting; the requester times out, and the unrenewed lease expires.
Capacity gates restrict pair allocation, not renewal, readiness or existing-pair
maintenance. Recheck capacity at grant time despite an earlier gate acceptance.

## 6. Time, NAT and bounded execution

All portable work advances only in explicit steps. `now` is one driver-supplied
monotonic reading per tick; the driver deposits corresponding lease ticks using
`{:ms 1 :s 1000}` units. No portable clock reads, sleeps, futures, timer callbacks,
daemon loops or schedule-on-attach behavior. Existing host socket event machinery
may only deposit data. A stopped driver causes no autonomous renewal or cleanup;
when it resumes, deadlines are evaluated before new application admission.

Driver order for each tick:

1. Supply time; process bounded base-channel lifecycle and projection work.
2. Drain bounded renewal evidence and tick media; step grantor judges and perform
   post-step cleanup. Publish current table snapshots.
3. Process bounded meeting requests, candidate/readiness observations and route
   transitions. Publish resulting table snapshots before mirrors use them.
4. Advance pair projections and mirrors in dependency order, with a persistent
   round-robin position and a global work budget. Each level gets a bounded visit;
   never spin until a remote operation succeeds.
5. Poll links and holder state, emit due keep-alive/renewal requests, and advance
   application work only on ready routes. Close expired/lost dependencies and
   emit bounded diagnostics. Retain returned state for the next tick.

Keep-alive is an ordinary descriptor exchange (an observed correlated answer
counts), not a new ping frame or a lease renewal. Maintain independent schedules
for base paths, final routes, announcements and holder renewals. Sending traffic
is not evidence that a peer answered. Request give-up bounds also cover probes
kept unsent because the writer is full, via a whole-phase establishment deadline.
Do not produce catch-up bursts after a delayed tick: at most one due action per
schedule on that visit. Lease renewal cadence comes from `dao.lease` holder state.

Proposed defaults below assume a runner scheduling lag budget of 1 second and
maximal four nested pair levels. They are composition choices, not internet
facts. Require `keepalive + lag < configured NAT idle bound`, and
`keepalive + response deadline + lag < session idle timeout`. If a deployment
cannot supply that margin it must lower cadence or use another path. Unknown
NAT lifetime cannot be guaranteed. No clock synchronization between peers is used.

UDP punching remains the existing convention: both peers send ordinary fresh-id
descriptor requests from the same socket observed by the meeting peer. Confirm
only an outstanding-id answer from the expected source address; correlated
`not-found` proves the path, not the target stream or the peer's key. A bounded
punch attempt falls back to a pair. Endpoint-dependent mapping/filtering failures,
UDP blocked, two browsers, or lack of unsolicited reachability must have explicit
relay/impossible outcomes. `remote-channel` currently supports WS only: do not
advertise production UDP composition until its stepped host branch is implemented
and tested. S5 requires the portable punch/fallback state machine; real UDP host
integration is a separately reported matrix gate, never inferred from simulation.

## 7. Production resource profile

Finite positive bounds are mandatory in the production S5 constructor. Nil/unbounded
variants may remain in low-level test APIs. Reject invalid profiles before resources
are allocated, including inconsistent timing or encoded envelope budgets.

| Resource | Initial S5 profile | Enforcement |
|---|---:|---|
| Nested pair depth / descriptor nodes / encoded descriptor bytes | 4 / 64 / 16 KiB | Preflight before recursive composition |
| Active routes / cached pair entries per assembly | 32 / 64 | Count dependencies, pending and live entries; reap before admission |
| Board candidates / attempts per resolution | 128 / 8 | Bounded fold; deterministic eviction; sequential attempts |
| Meeting active pairs / lifetime grants per epoch | 32 / 1024 | Gate plus allocation check; rotate only quiescent |
| Pending convention requests / dedup entries | 64 / 1024 | Refuse new work; never evict a live grant's correlation |
| Meeting request, board and relay ring capacities | 64 / 256 / 64 values | Every retained value also byte-bounded |
| Convention fact bytes / relay value bytes | 16 KiB / 64 KiB | Encoded value admission before retention |
| Meeting fanout / pair projection visit | 16 / 64 | Count malformed input and recovery work |
| Global route work units per driver tick | 4096 | Persistent fair scheduling; each read/write/mirror operation charged |
| Mirror request / chase budget | 64 / 8 | Per-channel cap plus remaining global allowance |
| Link drain / outstanding / filed | 64 / 64 / 128 | On every inner and outer link |
| Base sessions / idle / stop grace | 64 / 60 s / 2 s | Preserve S4 caps and neutral lifecycle |
| Keep-alive / response give-up / total establishment | 10 s / 30 s / 60 s | Explicit supplied time, across selected attempts |
| Announcement refresh / candidate freshness | 10 s / 30 s | Local observation age |
| Pair lease duration / judge tolerance | 120 s / 10 s | Lease holder/judge API, finite confirmed renewal cadence |
| Diagnostics / pending board publication deadline | 64 records / 30 s | Bounded sink; no unbounded retry queue |

S4's direct profile remains unchanged; S5 composes the longer deadline for nested
routes. Validate actual scheduling work against the lag budget under load. Counting
visits alone is insufficient when each visit can recursively perform many lower
operations: charge nested handle work to the same tick allowance and suspend at
stream boundaries. An operation whose bounded atomic cost exceeds the allowance
must be refused at assembly or supplied a sufficient allowance.

Message bytes mean negotiated codec bytes, including envelopes at each layer.
For every hop j require `encoded-size(envelope-j(value)) <= frame-limit-j` and
all retained values within that medium's byte limit. Do not estimate by character
count or subtract a fixed envelope constant. Nested identifiers, cursors, encoding
expansion and batched `more` answers consume budget. Enforce before transmission
and at each relay admission. No fragmentation is added to WS; oversized values
use Jing addresses. A remote answer too large uses existing `oversize` semantics;
an outbound value too large uses the writer's `invalid-value`. No cursor skips it.
The UDP datagram envelope remains at most 1200 bytes, complete message at most
64 KiB; fragment/reassembly limits are additional to route limits.

A resource accounting test must include rings, outstanding/filed outcomes, descriptor
caches, partially constructed paths, pending publication, event sinks, renewal
readers and judge historical state. For example the two data rings for 32 pairs
alone can retain at most `32 × 2 × 64 × 64 KiB = 256 MiB` of encoded data, before
renewal/control rings and object overhead. This profile is a starting ceiling,
not a small-memory claim; lower capacities or add a shared byte admission budget
for constrained hosts. Host bounds listed in WS design remain separate gates.

## 8. Failure and recovery contract

| Observation | Required behavior |
|---|---|
| Source `gap` in an inner answer | Preserve source outcome and recovery cursor verbatim |
| Pair input/projection gap | End channel; abandon pending IDs; append-unknown once; never synthesize source gap |
| Board gap | Lose discovery certainty, refresh candidates/readiness; do not pretend a request was refused |
| Pair/renewal lease reclaimed | Healthy grantor answers not-found; re-propose, never resurrect old identities |
| Relay channel lost | Dependent routes lost; shared source media and unrelated routes remain open |
| Append accepted, answer lost | Effect unknown; no replay after alternate-route selection |
| Writer full before acceptance | Retain bounded unsent intent; retry on a later driver visit |
| Invalid/malformed convention value | Bounded diagnostic/refusal; no whole-meeting crash or allocation |
| Exception in one projection/mirror | Isolate that channel and its dependent routes; continue fair visits |
| Both candidates fail / deadline reached | Terminal local convention result with attempted-path provenance |
| Peer/meeting restarts | New incarnation; invalidate old candidates and grants; old IDs never reach new rings |
| Stop | Refuse new route/grant work; stop holders, release owned dependencies once; drain within grace |

Recovery is a new attachment, not continuation of uncertain appends. A consumer
may reuse a source cursor through a confirmed route to the same source identity;
only that source determines whether the cursor is still valid or has a gap.
Pair transport cursors always restart at newest. Deduplication of application
effects remains the application's correlated request convention.

## 9. Implementation sequence and review boundaries

1. **Contract tests first.** Add `remote-route-test.cljc` for route composition and
   extend pair/meeting tests with initial-cursor terminal failure, rollback,
   policy propagation, independent observers and restart identity collision cases.
   Use explicit state/network fixtures and injected time; no sleeps in portable tests.
2. **Pair ownership and stepping.** Implement §4.1 using existing remote links,
   bounded projection rings and explicit cleanup. Keep old attacher tests passing.
   Validate depth/bytes before attachment; prove two identity reflections share
   one link and one close cannot kill its sibling.
3. **Channel composition.** Add the descriptor/pair branch and explicit table/name
   update seam. Make dialing and accepting sides symmetric. Preserve S4 direct
   serve/dial results and cause precedence; do not reinterpret terminal RPC state.
4. **Meeting correctness.** Add incarnation, request correlation, advertised channel,
   ready publication, current snapshots, byte/shape admission, bounded publication,
   proper renewal wiring and post-judge cleanup. Use `wire-declared-facts` for new
   renewal media so production assembly declarations are validated.
5. **Discovery and route driver.** Implement a small portable interpreter over the
   existing board/request streams, preferably `dao.stream.remote-route`, privately
   composed by `remote-channel`. State phases: `:discovering`, `:establishing`,
   `:ready`, `:lost`, `:stopping`, `:closed` (and initial `:refused`). Each phase
   owns an explicit deadline, pending correlation and resource set. No hidden
   scheduler, route registry, service callback or arbitrary recursive lookup.
6. **Leases and fairness.** Wire explicit tick and outcome media, holder/judge passes,
   keep-alive cadence, global budget, quiescent epoch rotation and stopped-driver
   recovery. Audit all caches for finite admission and all owned handles for release.
7. **Integration and docs.** Exercise real hosts, then update remote architecture
   §§3.0/3.3/4/5/6 and API docstrings in the implementation change. Clarify unleased
   registrations, proposed defaults, pair stepping and the distinction between
   claimed peer names and authentication. Do not silently modify stream algebra.

Expected files: `src/cljc/dao/stream/remote_{pair,meet,channel}.cljc`, the new
route helper if needed, `ws_project.cljc` only for explicit table/name update,
matching portable tests, host/process fixture entry points, and remote design.
`rpc.cljc`, `apply.cljc`, REPL semantic terminal handling and core `dao.stream`
remain unchanged. `remote.cljc` changes require a specific failing composition
test and narrowly reviewed seam; no fifth wire op or cursor rewriting. Reuse
existing host adapters. Any real UDP addition needs its own explicit host coverage.

## 10. Acceptance criteria and tests

Every criterion requires assertions on observations and released resources, not
only a final successful read.

- **A1 Identity and names:** same source identity/descriptor outcome through direct,
  one-relay and two-relay paths; name resolves at final peer; local alias remains
  local; conflicting board claims retain provenance; hash-only/no-bootstrap is
  no-route; no authentication claim follows mere lookup.
- **A2 Symmetry/topology:** A and B both mirror and reflect over one two-relay path.
  M1/M2 see only operations on their own relay media. Distinct peer role choices
  produce identical stream semantics. In-flight traffic survives fair interleaving
  of mirror and link observers without either consuming the other's input.
- **A3 Establishment:** both newest cursors precede readiness; early or lost ready
  posts cannot falsely confirm. Deferred attach, unsent full and missing target
  terminate appropriately by the establishment deadline.
- **A4 Stream semantics:** append, next, cursor, descriptor, no-surface, refused,
  source gap/end and oversize match direct outcomes; cursors remain opaque. Lost
  append answers emit unknown exactly once and are never replayed on fallback.
- **A5 Lifecycle:** input gap, failure before cursor initialization, out failure,
  relay crash and partition end only dependent paths. Reattach same pair creates
  fresh transport cursor. Reconnecting within a live lease finds the same media;
  expiry removes them; restart allocates noncolliding identities.
- **A6 Lease evidence:** outbound renewal acceptance alone does not extend holder
  bound; source-confirmed event does. Renewal-reader count returns to baseline.
  Repeated reclaim closes resources once; post-step unwiring survives returned
  judge state. Epoch grant cap prevents historical growth across repeated churn.
- **A7 Bounds:** test limit−1, limit and limit+1 for bytes, depth, nodes, candidates,
  sessions, pairs, queues and link counts. Multibyte/codec-expanding values and
  nested envelopes fit or refuse at the correct boundary. Full board publication
  and startup allocation failure never leak unbounded resources.
- **A8 Execution:** advancing wall time without driver ticks produces no portable
  work. Supplied large time jump expires state before new work; no catch-up burst.
  Flood one route with malformed values and continuous valid traffic; a healthy
  route is visited within the bounded round-robin sweep, and total charged work
  stays within budget. No sleep, future or private timer is introduced.
- **A9 NAT/discovery:** deterministic mapping/filtering simulator covers punching,
  wrong-source and wrong-ID answers, symmetric failure, relay fallback, blocked
  UDP, browser dial-only and no outbound route. Refresh and board gap behavior
  are asserted, not inferred from successful later attachment.
- **A10 S4 regression:** direct neutral causes, opened history, port-zero
  finalization, stop/drain and source ownership remain unchanged. Consumers above
  remote-channel contain no WS event/code/adapter knowledge.

### Multi-host matrix and evidence

| Lane/topology | Required evidence |
|---|---|
| JVM, Node, Dart independently | All portable pair/meeting/route tests, deterministic time and NAT fixture |
| A=JVM, M1=Node, M2=Dart, B=JVM | Real WS sockets, two relays, bidirectional operations, names and lease renewal |
| A=Node, M1=Dart, M2=JVM, B=Node | Same, including relay failure and sibling-route survival |
| A=Dart, M1=JVM, M2=Node, B=Dart | Same, including stop and restart with no stale identity reuse |
| Two browser endpoints, native relay(s) | Dial-only WS path, no browser listener/UDP assumption; record browser version |
| Separate machines/containers with blocked direct endpoint path | Demonstrate relay necessity, disable relay to prove dependency; record actual topology |
| Actual UDP hosts if integrated | JVM/Node/Dart same-socket punch and fallback with controlled mapping/filtering; otherwise explicitly unsupported in production S5 |

Loopback subprocesses prove host interop, not NAT traversal. Simulation proves
state-machine rules, not a particular network device. Cross-machine WS relay is a
required acceptance run; real punching evidence is conditional on shipping the
UDP branch. Browser execution is a distinct gate and cannot be claimed from Node.

Use repository `docs/agents/build-n-test.md`. Iterate focused JVM namespaces;
run the portable tests on Node and Dart, then one foreground `bb test` lane set
before landing. Build process peer fixtures using the repository build tasks;
run actual selected slow/process tests explicitly and record fixture build hashes.
Run changed-file Kondo, Cljstyle and `git diff --check`. Preserve CLJD-first reader
conditionals, portable exception data and distinct protocol argument names.

Evidence records must identify revision, host/runtime, test counts, commands,
topology, effective bounds, simulated versus real traffic, and every skipped or
blocked matrix row. Report baseline failures separately. No S5 production sign-off
until required rows pass and host byte/admission limitations are either closed or
explicitly excluded from a narrower claim. No implementation tests were run for
this specification-only task.

## 11. Architectural review checklist

The landing review must establish: unchanged four-op protocol; unchanged source
identity/cursors/outcomes; no privileged peer; discovery provenance without false
authentication; no hidden clocks or scheduler; fair bounded nested work; no source
closure by route cleanup; finite memory across churn; real routable meeting
descriptors; confirmed lease renewal; and isolated failure with no append replay.
These are release conditions, not optional performance refinements.
