Completed-GMT: 2026-09-02 19:16:46 GMT
Completed-Local: 2026-09-03 02:16:46 Asia/Ho_Chi_Minh

No files were modified.

## A1 — Server-side attachment identity

VERDICT: CONCEDE

Identity origin is contract material. Although `dao.stream.ws.md:38–42` already treats accepted server connections as attachments, the generic authorization for transport-minted server identities belongs in `dao.stream.md`; the ws specification should only define how this transport realizes it.

### Contract amendment

Document: `docs/design/dao.stream.md`  
Anchor: Creation and Attachment → Envelopes, replace the attachment-identity bullet at lines 254–261.

Exact replacement:

> - A transport whose attachments are distinguishable assigns each attachment an opaque, structurally serializable value, unique among the attachments that transport can tell apart. Every attachment handle the transport mints has such an identity, whether the handle is returned by `attach!` or minted from a host connection accepted without an `attach!` call. An `attach!` success map carries the value under `:dao.stream/attachment`. When no `attach!` call produces the handle, the transport specification names the deposited event or other stream-native mechanism by which the composition receives the identity. Wherever an answer or event for that attachment is displaced onto another channel, the same value correlates it there. The key and correlation rule are the contract’s; minting, representation, and the server-side delivery mechanism are the transport’s. Transports that cannot distinguish attachments omit it.

### WebSocket detail

Document: `docs/design/dao.stream.ws.md`  
Anchor: `## The Duplex Model`, after lines 87–100.

Insert:

> **Server-minted attachment identity.** Before offering a server-side accepted-connection handle to the serving composition, the transport mints its attachment identity under the contract’s server-minted-handle rule. The value is unique among attachments the boundary can distinguish and is not reused during that boundary’s lifetime.
>
> The acceptance-offer event carries the value under `:ws/attachment`, and every later deposited event for the connection carries the same value. The offer, its acknowledgement, the handle, and all later events therefore denote one attachment. Client-side `attach!` continues to return the corresponding client-local identity under `:dao.stream/attachment`.

### Invalidated elsewhere

- `dao.stream.implementation-plan.md:240–241` universally equates `:ws/attachment` with a value returned by `attach!`. Replace that fragment as specified under the Phase 4a ripple below.
- The parallel REPL plan’s current server-identity open-item statement at `yin.repl.implementation-plan.md:434–436` becomes stale. This round proposes no edit to that file.
- No other contradiction found in the five-document cluster.

---

## A2 — Accept notification and capability delivery

VERDICT: CONCEDE

The round-1 deposit-result gate was insufficient. `append! => ok` proves only that the offer entered an evict-oldest sequence, not that the composition observed and retained the sole writer capability.

The revised design uses a bounded set of capacity-one offer/acknowledgement slots. This is the separate guaranteed-delivery channel Gemini suggested, but implemented entirely with bounded evict-oldest DaoStreams.

### Event vocabulary

Document: `docs/design/dao.stream.ws.md`  
Anchor: replace the deposited-event example at lines 102–111.

```clojure
{:ws/attachment <id>
 :ws/event      :ws/payload  ; or :ws/accepted :ws/opened :ws/closed
                             ;    :ws/ended :ws/error :ws/not-found
                             ;    :ws/transport-error
 :ws/value      <decoded>    ; payload events only
 :ws/handle     <handle>}    ; accepted offers only; host-local
```

Replace the event-group paragraph at lines 113–122 with:

> The event kinds divide into four groups. **Acceptance offer** — `:ws/accepted` is deposited once into a serving handoff slot after the requested stream resolves and a server-side attachment handle is constructed. It carries that writer+closable handle under `:ws/handle`. **Resolution** — `:ws/opened`, `:ws/not-found`, or `:ws/transport-error` says how a client attachment resolved. **Lifecycle** — `:ws/closed` and `:ws/ended`. **Traffic** — `:ws/payload`, carrying `:ws/value`, and `:ws/error`.
>
> An accepted offer is host-local and never crosses a serialization boundary. It does not share the traffic medium’s retention window; Serving defines its bounded handoff medium and acknowledgement.

### Distinguish traffic from handoff media

Document: `docs/design/dao.stream.ws.md`  
Anchor: replace the paragraph at lines 79–85.

> The traffic deposit destination is wired once when the host composes the boundary; it is not an argument of `attach!`. A serving endpoint additionally receives the fixed acceptance-handoff slots defined under Serving. Both are host-composed media, but they serve different retention duties: the traffic medium records replayable events and may report gaps, while a handoff slot holds one capability offer until that specific offer is acknowledged.

Replace the granularity paragraph at lines 138–143 with:

> **Traffic granularity is the composition’s choice.** One traffic medium per boundary multiplexes attachments onto one retention window; one per attachment isolates them. This choice does not alter acceptance handoff: server writer capabilities always travel through the bounded handoff slots below, never through an evictable shared traffic history.

### Guaranteed acceptance handoff

Document: `docs/design/dao.stream.ws.md`  
Anchor: `### Serving`, after lines 173–176.

Insert:

> **Acceptance is a bounded, acknowledged stream handoff.** At endpoint construction, the host composition supplies a fixed, non-empty collection of handoff slots. Each slot contains:
>
> - a host-local offer medium with evict-oldest retention and capacity one, written by the transport and read by the composition; and
> - a host-local acknowledgement medium with evict-oldest retention and capacity one, written by the composition and polled by the endpoint driver.
>
> The transport has at most one outstanding attachment in a slot. After resolving the requested stream, it assigns a free slot, mints the attachment identity and handle, retains the connection in the endpoint’s bounded pending-accept state, and deposits:
>
> ```clojure
> {:ws/attachment <id>
>  :ws/event      :ws/accepted
>  :ws/handle     <server-side-writer-handle>}
> ```
>
> into that slot’s offer medium. It does not send the wire `:ws/accept` frame or enable value delivery yet.
>
> The serving composition polls the known offer slots. After it reads an offer and retains the handle in its own session state, it appends:
>
> ```clojure
> {:ws/attachment <id>
>  :ws/command    :ws/accept}
> ```
>
> to that slot’s acknowledgement medium. The endpoint driver accepts only an acknowledgement whose identity matches the slot’s outstanding offer. It then sends the wire `:ws/accept` frame, enables value delivery, and releases the slot for reuse. Stale acknowledgements are ignored; attachment identities are not reused.
>
> An outstanding accepted offer cannot be evicted: it is the slot’s sole value, and the transport does not append another offer to that slot before acknowledgement. Reuse may later evict the acknowledged offer, but by then the composition has both read the event and retained its handle. A reader lagging before acknowledgement prevents slot reuse rather than losing the capability. Thus eviction of an unacknowledged acceptance event is impossible by construction.
>
> The handoff remains bounded. When no slot is free, the transport admits no additional connection; it closes the new socket before logical acceptance, and the client resolves it as `:ws/transport-error`. It neither overwrites an outstanding offer nor grows an unbounded pending queue.
>
> Ownership remains explicit in every failure case:
>
> - if the offer deposit is non-`ok`, the endpoint closes the pending connection;
> - before acknowledgement, the endpoint retains the handle and can close it on peer loss, endpoint stop, or composition-selected admission expiry;
> - after reading the offer, the composition also holds the handle and closes it if acknowledgement cannot be deposited;
> - after acknowledgement, the composition owns the session handle, while the endpoint continues to own its host connection for endpoint-wide stop;
> - any non-`ok` traffic deposit after acceptance tears down the connection under Deposit Admission.
>
> No callback reaches the composition. Offer and acknowledgement are ordinary stream values, and the endpoint driver polls acknowledgements without waiting or installing a waiter.

### Clarify retained transport state

Document: `docs/design/dao.stream.ws.md`  
Anchor: replace lines 160–163.

> No inbound-event inbox accumulates inside the transport. Before acceptance acknowledgement, the endpoint retains only the bounded pending connection associated with each configured handoff slot. After acknowledgement, retention of traffic lives exclusively in the host-composed traffic medium.

### Deposit Admission addition

Document: `docs/design/dao.stream.ws.md`  
Anchor: `### Deposit Admission`, after its introductory paragraph.

Insert:

> Acceptance handoff media obey the same declared-admission rule: both media in every slot are host-local, evict-oldest, capacity one, able to carry their respective envelope, and outlive the endpoint. Their additional one-outstanding-offer discipline—not a different retention mode—is what prevents capability loss.

### Invalidated elsewhere

- `dao.stream.ws.md:79–85` speaks of one undifferentiated deposit destination; replace as above.
- `dao.stream.ws.md:113–122` has no acceptance group; replace as above.
- `dao.stream.ws.md:138–143` treats boundary announcement as part of the ordinary granularity choice; qualify it as traffic granularity.
- `dao.stream.ws.md:160–163` says nothing accumulates inside the transport; bounded pre-accept state is now required.
- `dao.stream.implementation-plan.md:228–243` gives the constructor only one deposit destination and gives the boundary adapter only one medium; amend under Phase 4a ripple.
- The parallel REPL plan’s current accept-notification open item at `yin.repl.implementation-plan.md:437–439` becomes stale. Its serving lifecycle around lines 528 onward must consume the accepted handoff, but this round proposes no wording for that document.
- B1–B4 are not contradicted.

---

## A3 rename — wire `accept` and `disclaim`

VERDICT: CONCEDE

`:ws/accepted` should remain the local serving event containing a live handle. The wire verbs should be `:ws/accept` and `:ws/disclaim`; they are clearer and avoid conflating an offer event with a protocol decision.

### Handshake replacements

In the round-1 A3 handshake amendment, replace:

```clojure
{:ws/frame :ws/accepted}
```

with:

```clojure
{:ws/frame :ws/accept}
```

Replace:

```clojure
{:ws/frame :ws/not-found}
```

with:

```clojure
{:ws/frame :ws/disclaim}
```

Replace the explanatory text with:

> `:ws/accept` authoritatively accepts the presented path and causes the client boundary to deposit `:ws/opened`. `:ws/disclaim` authoritatively disclaims it and causes the client boundary to deposit `:ws/not-found`; the server then closes with code `4004` and reason `dao.stream/not-found`. The client does not deposit `:ws/opened` merely because the HTTP upgrade completed.
>
> No value frame may be sent before `:ws/accept`. A frame of any other shape in the first position is a protocol failure. If the connection closes before either `:ws/accept` or `:ws/disclaim` is received, the client deposits `:ws/transport-error` as that attachment’s resolution.

### Ended-stream sentence

Document: `docs/design/dao.stream.ws.md`  
Anchor: replace the parenthetical at lines 202–204 with Gemini’s drafted wording:

> (The exact code is `4000`, mapping to `:ws/ended`, while `4002` is protocol-error and `4004` is the authoritative not-found disclaimer; see Elements and Serialization.)

### A2 ordering reference

Every round-1 or revised A2 reference to “the wire acceptance frame” becomes:

> the wire `:ws/accept` frame

The serving-side deposited event remains:

```clojure
{:ws/event :ws/accepted}
```

### Invalidated elsewhere

- The round-1 proposal’s wire `:ws/accepted` and `:ws/not-found` frame vocabulary is replaced.
- `dao.stream.ws.md:202–204` says the exact ended-stream close code is unsettled; replace as above.
- `dao.stream.ws.md:302–304` and Deferred lines 376–382 remain invalidated by the full A3 amendment.
- `dao.stream.implementation-plan.md:194–205` still needs the round-1 replacement marking the wire gate settled.
- The parallel REPL plan still names the wire contract as open at `yin.repl.implementation-plan.md:423` and `:440–441`; those become cross-document consequences only. No edit is proposed here.

---

## B3 — Conformance concurrency oracle

VERDICT: DEFEND

Real-time precedence for non-overlapping operations does not add cross-writer coordination. It records an already-observable fact: operation A returned before operation B was invoked.

The contract says each outcome reflects its sequence “at one moment” (`dao.stream.md:580–586`), while the IO model says an operation returns what is true when called. If A has completed before B begins, A’s linearization moment must already have occurred. Permitting B to precede A would allow an operation’s claimed moment to fall outside its invocation/return interval. The disclaimer about concurrent operations removes ordering guarantees between overlapping operations; it does not make completed effects retroactively reorderable.

Sequential consistency would therefore be too weak. It could accept this invalid same-sequence history:

1. append A returns `ok`;
2. only afterward, append B is invoked and returns `ok`;
3. readers observe B before A.

No writer coordination or meaning is imposed by rejecting that history: A and B did not overlap.

### Precision revision to the round-1 oracle

Document: `docs/design/dao.stream.implementation-plan.md`  
Anchor: Phase 1, proposed Concurrency oracle.

Use this clarified opening:

> **Concurrency oracle.** The conformance harness uses bounded, offline linearizability checking separately for each sequence a handle surface is on. It records invocation and completion boundaries, arguments, and outcomes; appended test values are unique tokens. The checker searches for a sequential history accepted by the transport’s pure abstract model.
>
> The only real-time edges it preserves are harness-observable happens-before edges: when one invocation has completed before another is begun, the first precedes the second. Overlapping operations receive no real-time order and may linearize in any model-valid order. Operations on distinct logical streams or distinct ordered outbound paths are checked independently; the oracle asserts no global order across them.

The remainder of the round-1 B3 amendment stands.

### Invalidated elsewhere

None found.

- `dao.stream.md:580–599` remains the source of the oracle’s strength.
- `dao.stream.implementation-plan.md:152–153` remains an implementation strategy, not the oracle.
- No B1, B2, or B4 wording changes.

## Phase 4a ripple from revised A2

Document: `docs/design/dao.stream.implementation-plan.md`

### Replace the Transport constructor bullet at lines 228–231

> - **Transport constructor**: the host supplies the traffic deposit destination and admission declaration. A serving endpoint additionally receives a fixed collection of capacity-one acceptance-handoff slots—offer and acknowledgement media plus their declarations—and the composition’s policy for expiring unacknowledged offers. These are composition-supplied streams, not namespace-global state.

### Replace the Boundary adapter bullet at lines 236–243

> - **Boundary adapter and acceptance handoff**: client resolution, payload, and lifecycle events are deposited into the ordinary host-composed traffic medium. A server-side accepted handle is first offered through a free capacity-one handoff slot under `:ws/event :ws/accepted`; the transport retains the connection in bounded pre-accept state and sends no wire `:ws/accept` until the endpoint driver observes the matching stream acknowledgement. Every deposited event carries the client-side identity returned by `attach!`, or the server-side identity carried by the accepted offer. Every non-`ok` deposit follows `dao.stream.ws.md`’s teardown rule.

### Extend the dependency-check bullet at lines 246–251

> The offer, acknowledgement, traffic, and served-stream media are all composition partners. `v2/ws.cljc` depends only on `dao.stream` protocols and must not require the ring-buffer namespace; the composition supplies the concrete capacity-one and traffic streams.

### Add Phase 4a tests

> Acceptance-handoff tests must demonstrate:
>
> 1. no wire `:ws/accept` or value delivery before matching acknowledgement;
> 2. an unread offer survives arbitrary traffic-medium eviction;
> 3. a slot is not reused before acknowledgement;
> 4. slot exhaustion closes the new connection without overwriting an offer;
> 5. stale or wrong-identity acknowledgements do not accept a connection;
> 6. offer-deposit failure, acknowledgement-deposit failure, endpoint stop, peer loss, and admission expiry each leave an identified owner able to close the connection.

### B-item ripples

- B1: none.
- B2: none; offer and acknowledgement events are host-local and never use Transit.
- B3: no model change. The handoff protocol receives Phase 4a state-machine tests, not ring-buffer contract-conformance rules.
- B4: no exclusion change. Capacity-one offer/ack media remain evict-oldest ring buffers; loss prevention comes from one outstanding value per slot, not reject mode.

## Updated summary

| Item | Settles | Blocks until applied |
|---|---|---|
| A1 | Contract authorization and ws realization of server-minted attachment identities | Server-side transport and REPL R4–R5 |
| A2 | Bounded, acknowledged, non-evictable delivery of accepted writer capabilities | Transport Phase 4a and server compositions |
| A3 | `:ws/accept`/`:ws/disclaim`, exact close codes, framing, codec, and decode behavior | Transport Phase 4a and cross-host wire tests |
| B1 | Phase 2 owns ring-buffer attachment; Phase 3 supplies its directory | Transport Phases 2–3 |
| B2 | One restricted Transit-JSON value codec in two framing contexts | Transport Phases 3–4 |
| B3 | Per-sequence bounded linearizability with only observable non-overlap precedence | Phase 1 harness and Phase 2 concurrency sign-off |
| B4 | Complete ring-buffer exclusions; reject mode remains intentionally absent | Phase 2 conformance sign-off |
