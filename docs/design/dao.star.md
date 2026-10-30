# dao.star: Hypermedia Web Applications in datom.world

Status: design; Mode 1 ratified by owner ruling (2026-10-24). Mode 1 is
specified and buildable on [`dao.stream.http.md`](./dao.stream.http.md) and
[`dao.lease.md`](./dao.lease.md).
Mode 2 is proposed and waits on fenced custody, version 1 of
[`yin.vm.universal-continuation-format.md`](./yin.vm.universal-continuation-format.md)
(owner ruling 2026-10-07). The published framing is
[`daostar-vs-datastar-vs-electric-clojure.blog`](../../public/chp/blog/daostar-vs-datastar-vs-electric-clojure.blog).

## Core Philosophy

`dao.star` is not a library or an adapter; it is the architectural pattern for building web applications natively in `datom.world`. It emerges from the intersection of two aligned philosophies:
1. **datom.world**: Systems emerge from interpreters of pure data flowing over explicit, append-only streams (Axioms 1 and 2). It avoids compiler magic, instead exposing the network explicitly via data.
2. **Datastar**: The browser should be a dumb, stateless interpreter of hypermedia (HTML) delivered over Server-Sent Events (SSE).

Datastar interprets the SSE projection of an egress `dao.stream`. That is enough to eliminate frontend state management, reactive DAG compilers (like Electric Clojure), and proprietary RPC adapters: standard HTTP in, standard SSE out, and every piece in between is an ordinary stream or an ordinary interpreter.

`dao.star` provides two operational modes under one programming model: **The Thin Client** (Arc-style sequential UI) and **The Fat Client** (mobile continuations).

**Roles, not nodes.** "Server" below means the peer whose composition holds the HTTP boundary; "browser" means a peer that can only dial. Neither is a kind of node, and no protocol privilege attaches to either (`datom.world.md`, Design Principles). The HTTP boundary's two media are ordinary streams, so the agent answering requests may run on a peer other than the one that listens, over `dao.stream.remote.md` reflections.

---

## Mode 1: The Thin Client (Arc-Style Sequential UI)

In the thin client model the browser holds zero business logic and zero shared mutable state. Datastar acts as a dumb terminal rendering HTML patches, while `yin.vm` executes linear, sequential code on the server.

### Flow

1. **Events In**: A user clicks a button (`data-on-click="@post('/k/<token>')"`). Datastar fires a standard HTTP request. The generic HTTP boundary (`dao.stream.http.md`) mints an exchange id, deposits the request as an unjudged `:http/request` event into the ingress `dao.stream` the composition wired, and holds the socket open. It reads nothing inside the request.
2. **Routing**: A router step in the `dao.star` composition reads the ingress with its own cursor, classifies each request by its path (*Sessions and request kinds*), and forwards it to the medium of the session its token names, together with every later terminal event of that exchange. The boundary does not route.
3. **Resuming**: The session's driver, on its next poll, observes the input at its own cursor and steps the parked continuation. Nothing wakes anything; a stream invokes nothing.
4. **Semantics and State**: The `yin.vm` agent evaluates business logic linearly. The UI is a derivation of the continuation's state, never stored: the agent projects it into Datastar's SSE wire format (`event: datastar-patch-elements\ndata: <div>...</div>`) through one pure projection function the composition supplies.
5. **Effects Out**: The agent appends to the egress `dao.stream`, tagged with the exchange id: an `:http/head` effect (status, `Content-Type: text/event-stream`), one `:http/chunk` per SSE event with the fully constructed string, and `:http/end` when the response is complete. A subscription exchange simply never ends.
6. **Dumb Socket Routing**: The boundary's `egress-step` observes the egress stream, matches the exchange id to the open socket, and flushes the unmodified payload. Datastar morphs the DOM. The agent parks again, waiting for the next input, for as long as it takes.

### Sessions and request kinds

A **lineage** is one session's chain of occurrences: a root and every successor a consumed input produced. **The token is the occurrence id**, minted unguessable by the driver; the lineage's identity is its root's id. Every projected UI carries the id of the occurrence that rendered it. The lineage's **current** occurrence is the one with no successor.

The driver appends one fact per transition to the **session-facts** medium, and appends it with `:dao.stream/ok` before any UI carrying the new id is projected:

```clojure
{:dao.star/lineage S :dao.star/occurrence S}                            ; root
{:dao.star/lineage S :dao.star/occurrence O2 :dao.star/predecessor O1}  ; consumption
{:dao.star/lineage S :dao.star/ended :halted}                           ; the program finished
```

The router materializes occurrence → lineage → current from these facts and from the lease medium's `:lapsed` records (*Liveness*), as `dao.space.index` materializes a covered index, never from a table it keeps. The session-facts medium is a complete-history transport (`dao.stream.md`, Complete history); its growth is bounded only by OD-4's resolution, an open dependency.

**Observation order.** A fact's `ok` is publication, not the router's observation of it, so the router never classifies from a projection that may lag. Each router pass reads the ingress first, then drains its session-facts and lineage-lease cursors to `blocked`, then classifies what it read. A token reaches the ingress only through a UI projected after its fact was accepted, so a fact precedes every request that names it, and a pass that has read the request has the fact to drain. The same holds for successor ids.

The drain proves this only on a direct handle: a reflection answers `blocked` while its request to the source is pending (`dao.stream.remote.md`, 2.4), which says nothing about the source. So **the router is composed on the peer that holds the session-facts medium, the lineage judge and the drivers**, and reads those two classification media directly; any other medium may be a reflection, which is the topology *Roles, not nodes* permits, so a driver separated from the HTTP boundary observes exchange grants and lapses over a reflection of the boundary's lease medium. A reflected ingress delays the read of a request, never its fact, so the proof stands. A router that read its facts through a reflection could not classify unknown at all; that composition is not permitted.

The router classifies by path; the boundary reads neither path nor token.

| Path | Kind | What it does |
|---|---|---|
| `/` (no token) | begin | The root program starts a fresh lineage and answers a complete `text/html` document: `datastar.js`, actions carrying the root id, and `data-init="@get('/s/<token>')"`. |
| `/s/<token>` | subscribe | Opens the session's long-lived SSE exchange. Consumes nothing and advances nothing: the driver answers the head and a projection of the current occurrence, and every later patch goes here too. Reconnecting with the original URL is a new subscribe, resolved to the current occurrence however many inputs have passed. |
| `/k/<token>`, token current | input | Consumed by the current occurrence (*Inputs, order and replay*). |
| `/k/<token>`, token consumed | stale | Runs nothing. Answered by a projection of the current occurrence, then `:http/end`. |
| any token of an ended or unknown lineage | begin | Halted, lapsed, or lost to a restart. `/s/<token>`: answered as an event stream the composition projects, never as `/`'s document (*Stale subscribe* below). `/k/<token>`: answered as `/`. Unknown is judged only after the drain above. |

**Stale subscribe.** A subscribe is an event-stream exchange, so a subscribe naming an ended or unknown lineage is answered as one: `:http/head` with `Content-Type: text/event-stream`, one or more events the composition's projection supplies for this case, and `:http/end`. It consumes nothing, starts no lineage and renews nothing. The projection's output must give the client a way to reach `/` (a page reload or a link); a reply the client cannot consume or cannot recover from is a composition defect, because a client that retries on a failed stream reader would retry the dead token without end. The subscribe-expired projection is supplied beside the begin and projection functions and is pure. (Architect ruling 2026-10-27, `collab/1793113510518-architect-daostar-stale-sse-question`: answering begin's document to an SSE fetch is a response-kind mismatch the table mandated — a spec defect, observed live as an endless client retry loop.)

Subscribe and stale requests are **re-projections**: the driver applies the projection function to the parked state without stepping it.

### Signals

A Datastar request carries the client's signals: in the `datastar` query parameter for GET and DELETE, in the JSON body otherwise. The boundary deposits them where HTTP put them, inside `:http/query` or `:http/body`, unread.

Decoding is interpretation. `dao.star` supplies one pure projection from a request event to a signals map. The ingress tuple gains no slot for it, and signals become datoms only when an application interpreter derives them from the retained ingress stream (`datom.world.md`, Derive, don't persist).

Signals are client-held state that moves, not shared state: each request carries an immutable snapshot, and the agent changes them only by appending a `datastar-patch-signals` effect. They are also the client's unverified claim. A signal is input to a continuation, never its state and never authority.

### Inputs, order and replay

**The token is the gate.** It is the request's only routing and idempotence key, and until ShiBi is load-bearing it is the sole gate on Mode 1 input: a pre-shared value in `dao.shibi.md`'s sense (owner ruling 2026-10-07).

**Order is the ingress position.** Two inputs for one occurrence are not concurrent once deposited: one is earlier. Custody decides who consumes it. In a one-process composition the session's single driver is the only resumer; that exclusion is process-scoped and says so. Where more than one peer observes the ingress, resumption is a take, and a take is a lease on the occurrence (UCF §7.7).

**One input, one successor.** Consuming an input advances occurrence `O` to its successor `O'` and appends the lineage fact. The reply goes to the exchange that carried the consumed input; patches for the session go to its open subscription exchanges, which the driver holds as its own interpreter state, built from the subscribe requests and terminal events routed to it.

**Stale input is one-shot** (owner ruling 2026-10-07). A double click, a retry, a replayed URL, a second tab or the back button names an `O` that already has a successor. It runs nothing and is re-projected. The UI is a derivation, so answering twice is harmless, and a retry whose first reply was lost still converges. Mode 1 continuations are one-shot; multi-shot forks are deferred.

**Effects.** Exactly-once needs a fenced writer into an enrolled consumer (UCF §7.7.5). Every other stream is at-least-once and declared so.

### Liveness

Two leased resources, kept apart. Neither adds a clock to the wait for input: a parked continuation's `next` on its input is `blocked` until a request arrives, on a Tuesday or a Thursday.

**The open exchange is leased.** The judge runs inside the peer that holds the HTTP boundary and is composed with the boundary's `close-exchange` as reclaim procedure and `open-exchanges` as its inventory (`dao.stream.http.md`, Inventory). For each `:http/request` it observes it grants unsolicited on a lease medium it writes: subject `{:http/exchange x}`, holder the egress medium's identity, a duration in `{:ms 1 :s 1000}`, and a `:dao.lease/max` cap that subscription exchanges are required to carry, so no subscription is uncapped (architect ruling 2026-10-24, `collab/1792841840885-architect-dao-star-m1-reconcile-r2`). Reclaim closes the exchange; the adapter deposits `:http/closed`, then the judge records `:lapsed`. A responder that keeps an exchange open observes its grant and appends `{:dao.lease/event :dao.lease/renewal :dao.lease/lease L}` to the egress medium at under half the duration, from its own control flow, against its own tick stream. The judge and `egress-step` are peer observers of that medium; each ignores the other's facts. A one-shot responder answers inside the granted duration and renews nothing. An exchange nobody answers lapses by `:silence`; Datastar's reconnect opens a new one.

**A gap is loss, and nothing is forwarded about it.** The judge, on an ingress `gap`, reconciles both ways against the inventory: an open exchange without a lease is granted afresh, and a leased exchange absent from the inventory is recorded `:lapsed` with cause `:policy`, so nothing is held unleased and nothing closed stays leased (inventory reconciliation; `dao.lease.md`'s reclaim-and-regrant is reserved for a lost ledger). The router forwards nothing about a gap: a request or terminal event lost in the evicted window is unrouted, and a forward whose `append!` answers anything but `ok` is the same loss; an unrouted request's exchange lapses by `:silence`. Drivers learn that an exchange is gone from the lease medium they already read for their grants: a `:lapsed` record for one of their exchanges drops it (`dao.lease.md`, Carriage). A driver whose cursor on any medium it reads answers `gap` forgets every subscription it holds and renews none; they lapse by `:silence` and close, and Datastar's reconnect subscribes again. Session media are declared at assembly like any deposit destination, evict-oldest with a capacity, and sized against the driver's lag.

**Keep-alive is the responder's.** On its tick it may append an `:http/chunk` carrying an SSE comment line. That chunk is bytes for the wire, so a host that reports write failure discovers a dead peer by writing; the renewal is evidence for the judge. Neither stands in for the other. A peer's close or a reported write failure is deposited as `:http/closed`; the responder reads it as data and drops the exchange, and the judge ends the exchange lease with cause `:policy`. A host whose send buffers and whose close notification is silent for an idle exchange (http-kit is one) reports neither, so a chunk written to a dead peer is not evidence of anything, and a responder that keeps renewing a subscription it believes open keeps the lease alive. Such an exchange ends at its lease's `:dao.lease/max`, not before (architect ruling 2026-10-24, `collab/1792841840885-architect-dao-star-m1-reconcile-r2`).

**The lineage is leased** (owner ruling 2026-10-07; the holder choice below is the architect's). A lineage nobody returns to is reclaimed through `dao.lease.md`, not by a timer. The peer holding the parked record is the grantor. When a lineage begins, its judge grants unsolicited on the lease medium: subject and holder `{:dao.star/lineage S}`, duration the composition's idle allowance, `:dao.lease/max` optional. Attribution is by envelope key: the session-facts medium is written only by session drivers, each stamping `:dao.star/lineage` on its facts, renewals and release, and the judge's resolver reads that key; until ShiBi, any writer of that medium can renew, the same exposure the exchange lease has. The driver observes its grant before consuming or projecting, renews at under half the duration from its own control flow, on each consumed input and on its tick while it holds an open subscription, and **stops at its bound**: past it, it consumes nothing and projects nothing, and an input arriving then is handed to the root program as begin. So a connected session is retained for as long as it stays connected, by design, bounded only by the cap; a disconnected one lapses after the idle allowance. The lease ends with the lineage: released by the driver on halt, lapsed by the judge on silence. Reclaim retires the runnable resource, the current parked record; the `:lapsed` record on the lease medium is the judge's own and is what ends the lineage for the router. History, successor facts and lease records are kept (`dao.lease.md`: a reclaim frees the resource, never the record). The subject is distinct from Mode 2's custody subject `{:yin.k/occurrence O}` on the arbitration medium. Leases are process-scoped until the composition settles the three durable duties `dao.lease.md` names.

Sizing: duration exceeds twice the renewal interval; the keep-alive interval stays under the shortest proxy or NAT idle timeout on the path; the ingress window exceeds router and judge lag and each session medium's window exceeds its driver's; a boundary restart loses sockets and ledger together, so its exchange inventory to reclaim is empty. The duration bounds silence, not death. The detection bound for a peer that disconnects while an exchange is idle is the exchange lease's `:dao.lease/max` on a host that does not report the close, and the host's own close notification, when it gives one, on a host that does. The composition sizes `:dao.lease/max` against the longest it will tolerate a dead subscription holding a socket and a driver slot, and accepts that every healthy subscription reconnects at that interval. A false lapse is possible (`dao.lease.md`, Limits).

---

## Mode 2: The Fat Client (Mobile Continuations via UCF)

Status: proposed. Everything in this section waits on UCF version 1, fenced custody with a `dao.space` transactor as arbitration authority (`yin.vm.universal-continuation-format.md` §7.7, 7.7.8). Version 0 is fork-only and cannot offer the exclusivity below.

While `dao.star` champions the dumb terminal, it is not locked into it. Because `yin.vm` continuations are pure data, they can cross the network boundary to act as SPA-style fat clients for low-latency local interaction.

### Flow

1. **Exporting**: A `yin.vm` continuation on the server parks and enters the exporting state (UCF §7.7.4): its wait set is detached, so no poll or local resume can perform an effect while the value is in other hands. The lift produces a UCF handoff body, canonical and content-addressed, publishes it (*Handoff carrier*), and the driver records the offer on the arbitration medium.
2. **Handoff**: The SSE patch carries the notice, not the body. A browser-side adapter deposits the notice on a browser-local stream that the browser's `yin.vm` reads; Datastar hands nothing to anyone.
3. **Custody**: The browser `yin.vm` fetches the body by address over its WebSocket channel, proposes on the arbitration medium, and resumes only on observing its grant. Custody is exclusive because the lease is: the state moves to the browser rather than being shared with it, and the server's copy stays fenced.
4. **Transparent Remote Access**: A continuation that needs a server-held stream uses the portable descriptors it carried and `attach!`es a reflection over WebSockets. Descriptors carry no authorization: the served entries are gated and leased for the migrated continuation (`dao.stream.remote.md`, sections 6 and 7). On channel loss a reflection answers `channel-gone` and reattachment is the continuation's own step on the same descriptor; a reclaimed entry answers `not-found` and is gone, not disconnected.
5. **Migration Back**: The continuation re-serializes itself as a successor and offers it to the server, which admits it under *Admitting a continuation back*.

### Handoff carrier

**Architect ruling, not yet confirmed by the owner (2026-10-07).** The SSE patch carries a notice and the body travels by address over the browser's WebSocket channel. The notice is the body's content address, the arbitration descriptor, and a content coordinate `{:dao.jing/type :dao.jing/remote :dao.jing/requests <remote descriptor> :dao.jing/answers <remote descriptor>}` (`dao.stream.remote.implementation-plan.md`, section 1).

The path: before the notice is appended, the lift publishes the body to the server's content store and appends the address to the session-facts medium as the lineage's export. The `requests` and `answers` entries are a pair served per lineage, so answers are not readable by other lineages, and gated with the lineage token as the pre-ShiBi credential; the gate's `verify` admits a `get` only for an address the lineage's own export facts name. The browser's fetch is the stepped content client, clock-free; it verifies the body by re-hashing (UCF §7.8, lower step 2). Three absences are distinct and all arrive as data: missing content is the service's `:jing/found? false`, a missing service is the remote `not-found` error, and a disallowed address is `:dao.stream/refused` at the gate. In each the browser holds nothing, and the exporter, a candidate like any other, proposes for itself under its own policy (UCF §7.7.4), so an unfetched offer strands nothing. Confidentiality is the gate's and the pair's: an address is a name, not an authorization.

Reasons: the body is canonical CBOR and SSE is text, so inlining means a second encoding; the egress medium is shared, and a body on it discloses the store slice to every observer, where a notice discloses a name; large values travel as content addresses (`dao.stream.md`, Granularity); and the browser `yin.vm` needs the channel anyway for custody and remote access, so the carrier adds no path.

Rejected: the body base64-encoded inside a `datastar-patch-signals` or element attribute (double encoding, disclosure on egress, Datastar's payload size); a second HTTP GET by address (a second boundary path for what the channel already carries).

### Admitting a continuation back

A continuation returning from the browser is admitted as untrusted code and state. It ran where nothing can attest to it, so it is treated as the controlled mode of `dao.space.security.md` treats any pushed interpreter.

1. **Custody first** (UCF §7.7, unchanged). The successor must name, in `:yin.k/origin`, the lease the ledger holds live for that holder, and arrive on the holder's own medium. Otherwise `:yin.k/not-holder`.
2. **Validate as hostile bytes** (UCF §7.8, lower steps 1 to 4), under a size bound and a step budget the composition declares.
3. **Rebind authority; never read it from the value.** Lower into an isolated store. Primitives and FFI ops resolve from the receiver's registry, restricted to the set the composition delegated to this lineage at export. A stream marker attaches only if its identity is in that delegated set, or its channel is the returning peer's own. Anything else is `:yin.k/unsatisfied` naming it.
4. **No check survives the trip.** A decision the program made before export may have been skipped by a forged frame. Whatever must hold after return is enforced by a gate on a stream, or stays home in a continuation that never left and answers the migrated one over streams.

The test: a returned continuation can do nothing its principal could not do directly through the gated streams it was given.

**Export is disclosure.** The lifted store slice and values are readable by the browser and by every peer the content gate admits. The composition inspects the computed closure before publishing.

**Until effect handlers honor a delegated set** (`dao.space.security.md`, Honest Limits), migration back is admitted from allow-listed peers only (owner ruling 2026-10-07). ShiBi later plugs in at the same gates.

---

## Design Invariants Maintained

- **No Shared Mutable State**: The UI is a pure projection (Mode 1), or custody of the continuation is held under an exclusive lease (Mode 2). State is never shared across the boundary; it moves, and the mover is fenced.
- **Multiplexed Correlation**: Connections are not mapped 1:1 to streams. The HTTP boundary observes a shared egress stream and routes by exchange id. The cost is shared: an egress `gap` closes every open exchange, and a composition under that pressure shards the egress.
- **Host Isolation**: The HTTP boundary is a dumb pipe that knows nothing of Datastar or `yin.vm` syntax. Datastar is a dumb renderer that knows nothing of backend state. Under Mode 2 a `yin.vm` runs beside it, not inside it.
- **Data > Macros**: Compile-time slicing (Electric Clojure) is rejected. Network boundaries are crossed dynamically at runtime via reified data on explicit streams.
- **No callbacks, no implicit control flow**: every arrival is a deposit, every departure is an effect, and every wait is a `blocked` answer at a cursor.
- **No hidden state**: a driver's open subscriptions and the router's token index are interpreter state derived from media each reads with its own cursor, rebuilt from those media or honestly lost on a gap.

## Deferred

- **Multi-shot forks** (Arc's back button resuming a sibling branch): branch identity and subscription routing with a non-unique current are unspecified.
- **Local projection** in Mode 2: the browser `yin.vm` appends the same Datastar-format effects to a browser-local egress stream read by a browser-side boundary adapter; same vocabulary, different boundary.
- **Compaction of the session-facts medium**: waits on `dao.stream.md` OD-4.
- **The content service as rules**: `dao.jing.content` is a plan section, not a spec; the notice above depends on it.
- **An HTTP channel for peers without WebSocket**: `dao.stream.http.md`, Deferred.

## Completion Criteria

1. **Thin Client**: A `yin.vm` agent receives a Datastar input through the HTTP boundary's ingress stream, answers with `:http/head`, SSE-formatted `:http/chunk`s and `:http/end` on the egress stream, parks, and resumes on the next input for its current token. Also: a request with no token begins a lineage; a subscribe consumes nothing and a re-subscribe after several inputs receives the current projection; a stale token is re-projected; a subscribe naming an ended or unknown lineage is answered with an event-stream response the client can render, ended, and never with the begin document; a closed socket is observed as `:http/closed`; a silent exchange lapses and closes; a lineage nobody returns to lapses, its parked record is retired and its facts remain; a request naming a just-published token is never answered as begin; after an ingress gap reconciliation leaves no exchange unleased and no closed exchange leased, and a driver stops renewing an exchange once it observes the lapse or its own gap; a renewal in flight may still count while the lease is live, and one observed after retirement does not revive it.
2. **Fat Client Handoff**, conditional on UCF version 1 landing: a `yin.vm` agent exports under exclusive custody, the SSE patch carries the notice, a CLJS `yin.vm` in the browser fetches the body through the lineage's gated content pair, verifies it, obtains the grant, resumes, and reaches a server-held stream through a gated, leased reflection. Migration back from an allow-listed peer is admitted under *Admitting a continuation back*. The carrier shape is an architect ruling pending the owner's confirmation.
