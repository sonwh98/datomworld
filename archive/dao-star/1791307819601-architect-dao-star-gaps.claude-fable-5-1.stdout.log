Completed-GMT: 2026-10-06 17:40:00 GMT
Completed-Local: 2026-10-07 00:40:00 +07
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027

# dao.star design gaps: rulings

Read-only pass; no files edited. The timestamps above are estimates from a `date` call at the start of the pass. The brief's `Created-GMT` is a day ahead of its own `Created-Local`.

Verdict: the thin-client shape is sound and matches the blog. The doc has four architectural defects: the response head is unspecified, exclusive custody is asserted rather than obtained, migration back has no admission rule, and nothing routes or orders requests. The rest is wording or deferred work.

## Gap 1: HTTP boundary

**Ruling:** it needs its own doc, `docs/design/dao.stream.http.md`, subordinate to `dao.stream.md` and sibling to `dao.stream.ws.md`. `dao.star.md` cites it and specifies only the composition. The boundary is generic; specifying it inside `dao.star.md` would make a stream-level contract depend on a document above it.

**Rejected:**
- **ws-style per-exchange writer handle through a handoff slot.** A live handle forces `:host-values` media and pins the agent to the boundary's process. Correlated effects keep both media `:portable-values`, so the agent can sit on any peer.
- **Boundary writes the SSE headers itself.** It would then know SSE.
- **One egress medium per exchange.** It contradicts the doc's and blog's shared-egress framing; a composition may still shard.

Paste-ready (seed of the new doc; `dao.star.md` keeps the first paragraph plus a link):

````markdown
## The HTTP boundary

The HTTP boundary is not a transport and owns no stream. It is an adapter
(events in) and a host effect interpreter (effects out) over two ordinary
media the composition supplies. It has no reader surface, no `create!`, and
hands no handle upward. It never reads a request body, a query string or a
response chunk.

**Exchange.** One request and its response are one attachment; a connection
is not. The boundary mints `:http/exchange` under the contract's
server-minted attachment rule: plain data, never reused for the lifetime of
the egress medium (it carries the boundary's incarnation), so a stale effect
can never match a new socket.

**Events in.** Framing, header parsing and size limits happen below the
transform; an over-limit or malformed request is answered there (400, 413,
503) and is not an event. Every valid request is deposited unjudged:

```clojure
{:http/exchange x :http/event :http/request
 :http/method :get :http/path "/action" :http/query "…"
 :http/headers {…} :http/body <bytes>}
{:http/exchange x :http/event :http/ended}    ; an :http/end effect completed
{:http/exchange x :http/event :http/closed}   ; every other end
```

Exactly one terminal event is deposited per exchange.

**Admission.** The constructor takes the deposit destination and its
declaration as data: evict-oldest, a declared capacity, `:portable-values`,
a lifetime longer than the boundary. A reject-mode destination is a host
assembly defect. `:http/max-open` and `:http/max-body-bytes` are composition
data.

**Effects out.** `egress-step` is a step the composition drives. It holds
one cursor on the egress medium, minted at `:dao.stream/newest` before the
listener starts, and performs, in stream order per exchange:

```clojure
{:http/exchange x :http/effect :http/head :http/status 200 :http/headers {…}}
{:http/exchange x :http/effect :http/chunk :http/body <string-or-bytes>}
{:http/exchange x :http/effect :http/end}
```

A value that is not an effect for an open exchange is not this
interpreter's and is ignored; the egress medium has no privileged reader.
The step appends no per-effect outcome. The writer learns an effect was not
performed from the exchange's terminal event on the ingress medium. An
effect out of order for an open exchange (a chunk before the head, a second
head) closes that exchange.

**Observably gone.** A non-`ok` deposit closes that exchange's socket with
no synthesized response. An egress `gap` closes every open exchange, since
the step cannot say whose effects were lost, and adopts the recovery cursor.
An egress `end` closes every exchange and the listener. A failed socket
write closes the exchange. Each is the same action, closing a resource the
boundary itself holds.

**No privileged node.** Which end accepted is an establishment fact. Both
media are ordinary streams, so a peer that cannot listen can run the agent
against reflections of them (`dao.stream.remote.md`) while a reachable peer
runs only this boundary.
````

## Gap 2: Datastar signals

**Ruling:** opaque payload at the boundary, derived by an interpreter above it. They are never datoms in the ingress tuple and never discarded.

**Rejected:**
- **Datoms in the ingress tuple.** The boundary would have to know the `datastar` parameter and its JSON, and it would persist structure a projection derives.
- **Discarded.** That is filtering at the depositing layer, which Host Boundaries forbids.

````markdown
### Signals

A Datastar request carries the client's signals: in the `datastar` query
parameter for GET and DELETE, in the JSON body otherwise. The boundary
deposits them where HTTP put them, inside `:http/query` or `:http/body`,
unread.

Decoding is interpretation. `dao.star` supplies one pure projection from a
request event to a signals map. The ingress tuple gains no slot for it, and
signals become datoms only when an application interpreter derives them from
the retained ingress stream.

Signals are client-held state that moves, not shared state: each request
carries an immutable snapshot, and the agent changes them only by appending
a `datastar-patch-signals` effect. They are also the client's unverified
claim. A signal is input to a continuation, never its state and never
authority.
````

## Gap 3: Liveness

**Ruling:** the open exchange is a leased resource. The judge is composed inside the peer that holds the HTTP boundary and reclaims by closing the socket. A parked continuation's wait for input has no deadline.

**Rejected:**
- **A boundary idle timeout.** It is a switch, not a lease; a long-lived SSE stream is legitimately idle.
- **Counting egress chunks as renewals.** `dao.lease.md`: no machinery renews on the holder's behalf.
- **Boundary-authored keep-alive bytes.** The boundary would know SSE.

````markdown
### Liveness

Three leases-or-not, kept apart:

- **The wait for input has no deadline.** A parked continuation's `next` on
  its input is `blocked` until a request arrives, on a Tuesday or a
  Thursday. Nothing here adds a clock to it.
- **The open exchange is leased.** The judge runs inside the peer that holds
  the HTTP boundary. For each `:http/request` it observes, it grants
  unsolicited on a lease medium it writes: subject `{:http/exchange x}`,
  holder the egress medium's identity, a duration in `{:ms 1 :s 1000}`.
  Reclaim is the boundary's own close of that exchange, idempotent; the
  adapter deposits `:http/closed`, then the judge records `:lapsed`.
- **Custody of a migrated continuation** is the UCF lease on its occurrence
  (Mode 2). A lapsed socket never ends it.

**Renewal.** A responder that keeps an exchange open observes its grant and
appends `{:dao.lease/event :dao.lease/renewal :dao.lease/lease L}` to the
egress medium at under half the duration, from its own control flow, against
its own tick stream. The judge and `egress-step` are peer observers of that
medium: each ignores the other's facts. A one-shot responder answers inside
the granted duration and renews nothing.

**Closed socket.** A peer's FIN or a failed write is deposited as
`:http/closed`. The responder reads it as data and stops; the judge ends the
lease with cause `:policy`. A dead peer the host has not noticed is
discovered only by writing.

**Keep-alive** is therefore the responder's: on its tick it may append a
`:http/chunk` carrying an SSE comment line. That chunk is bytes for the
wire; the renewal is evidence for the judge. Neither stands in for the
other.

**No reply.** An exchange nobody answers lapses by `:silence` and its socket
closes. Datastar's reconnect opens a new exchange. A false lapse is
possible (`dao.lease.md`, Limits).

Sizing: duration exceeds twice the renewal interval, and the keep-alive
interval stays under the shortest proxy or NAT idle timeout on the path. A
boundary restart loses sockets and ledger together, so the inventory to
reclaim is empty.
````

## Gap 4: Fat-client trust

**Ruling:** confinement with no amplification. A continuation returning from the browser is an untrusted program submitted by the browser's principal. Custody proves who returned it and that it is the only one; it proves nothing about what the browser did.

**Rejected:**
- **A signature or MAC over the exported value.** The successor is a different value.
- **Replaying the browser's steps to verify.** It defeats the mode.
- **Pinning code to the exported segments as the safety argument.** A forged frame can resume at any safepoint past a check.

````markdown
### Admitting a continuation back

A continuation returning from the browser is admitted as untrusted code and
state. It ran where nothing can attest to it, so it is treated as the
controlled mode of `dao.space.security.md` treats any pushed interpreter.

1. **Custody first** (UCF §7.7, unchanged). The exporting peer names an
   arbitration medium it trusts in the value. The successor must name, in
   `:yin.k/origin`, the lease the ledger holds live for that holder, and
   arrive on the holder's own medium. Otherwise `:yin.k/not-holder`.
2. **Validate as hostile bytes** (UCF §7.8 lower, steps 1 to 4), under a
   size bound and a step budget the composition declares.
3. **Rebind authority; never read it from the value.** Lower into an
   isolated store. Primitives and FFI ops resolve from the receiver's
   registry, restricted to the set the composition delegated to this
   lineage at export. A stream marker attaches only if its identity is in
   that delegated set, or its channel is the returning peer's own. Anything
   else is `:yin.k/unsatisfied` naming it.
4. **No check survives the trip.** A decision the program made before export
   may have been skipped by a forged frame. Whatever must hold after return
   is enforced by a gate on a stream, or stays home in a continuation that
   never left and answers the migrated one over streams.

The test: a returned continuation can do nothing its principal could not do
directly through the gated streams it was given.

**Export is disclosure.** The lifted store slice and values are readable by
the browser and by every observer of the carrier. The composition inspects
the computed closure before publishing.

**Until ShiBi is load-bearing**, the principal is a channel attachment
identity on an allow-list or a pre-shared value (`dao.shibi.md`). ShiBi
later plugs in at the same gates.

**Limit.** Effect handlers that honor a delegated set are not built
(`dao.space.security.md`, Honest Limits). Until they are, migration back is
enabled only from allow-listed peers.
````

## Gap 5: Ordering and idempotence

**Ruling:** a request is an input to an occurrence, not a resumption. The ingress position orders inputs, one input advances one occurrence, and a stale input gets a re-projection.

**Rejected:**
- **Deduplicating on the exchange id.** A retry is a new exchange.
- **A lock or session table in the boundary.** That is hidden state, and it is interpretation in the pipe.
- **Queueing inputs for an occurrence held elsewhere.** It is a silent wait.

````markdown
### Inputs, order and replay

**A request names its occurrence.** Each projected UI carries, in its action
path, the token of the parked occurrence that rendered it. The token is
unguessable and is the request's only routing and idempotence key. The
boundary does not read it. A router step in the `dao.star` composition does,
forwarding each request to the medium its continuation reads.

**Order is the ingress position.** Two requests for one occurrence are not
concurrent once deposited: one is earlier. Custody decides who consumes it.
In a one-process composition the session's single driver is the only
resumer, and that exclusion is process-scoped and says so. Where more than
one peer observes the ingress, resumption is a take, and a take is a lease
on the occurrence (UCF §7.7).

**One input, one successor.** Consuming an input advances occurrence `O` to
its successor `O'`. The reply goes to the exchange that carried the consumed
request.

**Stale input.** A double click, a retry, a replayed URL, a second tab or
the back button names an `O` that already has a successor. It runs nothing.
It is answered by projecting the current occurrence again and ending the
exchange. The UI is a derivation, so answering twice is harmless, and a
retry whose first reply was lost still converges.

**Multi-shot is opt-in.** A composition may declare a continuation
`:yin.k/fork`, Arc's behaviour: a stale input then resumes a sibling branch,
and its effects are at-least-once.

**Effects.** Exactly-once needs a fenced writer into an enrolled consumer
(UCF §7.7.5). Every other stream is at-least-once and declared so.

**Held elsewhere.** While a browser holds custody of `O`, the exporting
peer's copy is fenced. A request naming `O` is stale. If the browser's lease
lapses, the epoch advances, the last recorded occurrence may be granted
again, and the browser's late effects are refused by epoch.
````

## Defects in the existing text

Class A is an architectural defect; class D is deferred work or wording.

| Severity | File:line | Invariant / evidence | Correction |
|---|---|---|---|
| High, A | `docs/design/dao.star.md:23-24`, `:45` | Host Isolation as the doc states it. No effect carries status or `Content-Type`, and nothing ends the response. Either the boundary knows SSE or the browser gets no event stream. | Add `:http/head` and `:http/end` effects (Gap 1). The agent authors the head. |
| High, A | `docs/design/dao.star.md:35`, `:43` | UCF §7.1: "emission transfers nothing". §7.7.3: without a `dao.space` transactor as authority a composition is fork-only. As written, the exporter's parked record and the browser are both runnable (§7.7.1). | "Custody is obtained by a lease on the occurrence; the exporter enters the exporting state before publishing." Keep "state moves, not shared". |
| High, A | `docs/design/dao.star.md:37` | `dao.space.security.md` confinement. The receiver would lower attacker-controlled frames with its ambient authority. | Gap 4 section. |
| High, A | `docs/design/dao.star.md:21` | Nothing says which continuation a request reaches or what a duplicate does. "Wakes" is implicit control flow: a stream invokes nothing. | Gap 5 section. "The driver's next poll observes it at the agent's cursor." |
| Medium, A | `docs/design/dao.star.md:20` | `dao.lease.md`: an unbounded held socket is a possessed resource with no reclaim. Deposit destination and admission are undeclared. | Gap 3 section; cite the boundary doc. The correlation id is the attachment identity. |
| Medium, A | `docs/design/dao.star.md:34` | The shared egress medium discloses the store slice to every observer. "Instructs the browser to hand the payload" is a call across a host boundary. | The patch carries the body's content address and descriptors; the body travels by address. A browser adapter deposits the notice on a browser-local stream that `yin.vm` reads. |
| Medium, A | `docs/design/dao.star.md:36` | `dao.stream.remote.md` 2.4 and §6: channel loss is `channel-gone` and reattachment is the caller's; a reclaimed lease is gone, not disconnected. "Parks until the connection returns" implies an implicit wait. | State both outcomes. Entries served for a migrated continuation are leased and gated. |
| Medium, D | `docs/design/dao.star.md:6-7`, `:17`, `:36`, `:51` | `datom.world.md`: client and server are roles, not kinds of node. "datom.world (Backend)" is also false under Mode 2. No protocol privilege is introduced. | Say once: "server" means the peer whose composition holds the HTTP boundary; "browser" means a peer that can only dial. |
| Medium, D | `docs/design/dao.star.md:9` | Datastar holds no handle or cursor and cannot re-observe. It interprets an SSE projection of an egress stream. | "Datastar interprets the SSE projection of an egress stream." Drop "speaks the universal language of streams". |
| Medium, D | `docs/design/dao.star.md:30`, `:51` | UCF is "Proposed / Deferred"; the r5 amendment states no version-1 code is landed. | Cite the status; make criterion 2 conditional on it. |
| Low, D | `docs/design/dao.star.md:33-34` | A UCF value is a content-addressed handoff body, not an `[e a v t m]` datom. | "UCF value". The blog's looser wording can stand. |
| Low, D | `docs/design/dao.star.md:45` | Under Mode 2 the browser runs `yin.vm`. | "Datastar is a dumb renderer." |
| Low, D | `docs/design/dao.star.md:44` | A shared egress `gap` closes every open exchange. | State the consequence; allow sharding. |
| Low, D | `docs/design/dao.star.md:50` | The order "receives, suspends, responds" is wrong, and there is no negative criterion. | Add: a closed socket is observed as data; a stale request re-projects; a silent exchange lapses. |

The six invariants hold once these are applied. Nothing in the blog is contradicted: the head effect, the lease and the token are additions it is silent on.

## Open questions for the owner

1. **Mode 2 before UCF version 1?** Exclusive custody needs the fenced-custody grammar and a `dao.space` transactor. May Mode 2 ship first as a fork-only demo with the wording changed, or does it wait?
2. **Migration back before confinement exists?** I recommend allow-listed peers only until effect handlers honor a delegated set. The alternative is to leave it out of scope entirely.
3. **Default for a stale request.** I recommend one-shot with re-projection. The blog invokes Arc, whose continuations were multi-shot, so the back button would re-run them. Which is the default?
4. **Abandoned parked continuations.** The blog promises weeks. Who reclaims them and when? This is OD-4 in `dao.stream.md`, still open.
5. **Browser principal before ShiBi.** Is the unguessable occurrence token, as a pre-shared value, an acceptable sole gate for Mode 1 input?
6. **The handoff body's carrier.** I ruled that the patch carries an address and the body travels over the browser's WebSocket channel. Completion criterion 2 says the state is serialized "into a Datastar SSE patch". Confirm that the criterion may change.
