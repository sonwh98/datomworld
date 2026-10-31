I've read the four documents in scope. Below are my findings, most severe first, then the two requested sections. I found no issue at a "catastrophic/critical" severity — nothing that invalidates the core model — but several coherence defects, one internal contradiction in the WebSocket spec, and a cluster of things an implementer cannot resolve.

## Findings

### 1. `attach!` outcome prose contradicts the transport's own outcome table (and the plan claims it was fixed)
**Severity: High** | `docs/design/dao.stream.ws.md:315-316` vs `:55`, with `dao.stream.implementation-plan.md:44-49`

**Claim.** The outcome table declares `attach!` produces `ok`, `invalid-descriptor`, **and** `transport-error` ("no socket API on this host, a constructor that fails, local resources exhausted" — line 55). But the Operations prose says:

> "It answers what is decidable locally, at the moment it is called: `:dao.stream/invalid-descriptor` for a descriptor it cannot read, **otherwise `:dao.stream/ok`**…" (315-316)

**Correction.** "Otherwise `ok`" is exhaustive and wrong: a constructor that fails or a host with no socket API is decidable locally and must return `transport-error`, not `ok`. The plan (line 48) says the Operations section was "corrected to match" the three-way split — it wasn't. Rewrite 315-316 to enumerate `invalid-descriptor`, `transport-error`, and otherwise `ok`, matching line 55 and plan 44-49. This is exactly the kind of defect that survives convergence: the group agreed the correction existed and stopped looking.

### 2. The plan's async-`create!` rule has no home in the contract's `create!` outcome set
**Severity: High** | `dao.stream.implementation-plan.md:54-59` vs `dao.stream.md:281-288` (esp. `:285`)

**Claim.** The plan says a later transport whose creation needs resources it "cannot acquire synchronously … answers the same way `attach!` does: the logical stream's identity exists at once, the handle reports acquisition state through its own operations, and the result of the acquisition arrives as data."

**Correction.** `attach!`'s `ok` carries an explicit caveat ("Implies neither … establishment has completed," `dao.stream.md:294`). `create!`'s `ok` has no such caveat — it means "A new logical stream exists" (`dao.stream.md:285`), and the `create!` set is `{ok, invalid-spec, not-found, transport-error}`. There is no "pending/not-yet" outcome for creation, unlike `blocked` (read) and `full` (write). So "answers the same way `attach!` does" would return `ok` for a stream that does not yet exist, violating the table, while `transport-error` would misreport a pending acquisition as a failure. Either the contract's `create!` table needs a deferred-establishment caveat (a contract amendment), or the plan's rule is unmotivated. This is a load-bearing decision (outcomes-as-data with closed sets) applied asymmetrically — `next`, `append!`, and `attach!` all got a "not yet" slot; `create!` did not.

### 3. "Exactly one resolution event per attachment" is false for an attachment aborted before resolution
**Severity: Moderate** | `docs/design/dao.stream.ws.md:116` vs `:349-356`, `:339-341`

**Claim.** "Exactly one of these is deposited per attachment" (`:ws/opened` / `:ws/not-found` / `:ws/transport-error`, lines 113-122).

**Correction.** `attach!` returns `ok` with a handle while the connection is "still being established" (315-316), and `close!` on that handle "means disconnect from the server" (349-350). If `close!` is called during establishment, the connection never resolves, so zero resolution events are ever deposited — contradicting "exactly one." The spec never states whether a resolution (or `:ws/closed`) event is deposited for a locally aborted, never-established attachment. Say so: either deposit a terminal event on local abort, or narrow the invariant to "exactly one, for attachments that reach a resolution."

### 4. Server-side attachment identity has no specified source or channel
**Severity: Moderate** | `docs/design/dao.stream.md:253-260` vs `dao.stream.ws.md:174-176` vs `dao.stream.implementation-plan.md:232-236`, `:350-351`

**Claim.** The contract's correlation rule defines `:dao.stream/attachment` only in the `attach!` **success map** (`dao.stream.md:253-260`). But a server-side accepted-connection handle is "minted from a socket the runtime handed over, so neither `create!` nor `attach!` produces it" (plan 232-234), yet its inbound events are "enveloped with that connection's attachment identity, exactly as on the client" (ws 174-176).

**Correction.** There is no `attach!` success map on the server path, so nothing in any of the three documents says how the serving composition *obtains* the attachment identity it must write into each `:ws/attachment` envelope, or how it correlates a connection to the identity on its own deposited events. Plan 4c merely lists "how that identity reaches deposited events" (350-351) as something the deliverable "must define" — it doesn't define it. This blocks an implementer of 4a/4c at the exact point the correlation rule is load-bearing.

### 5. The pause/backpressure vocabulary is deferred, gated on nothing, and unspecified — 4d cannot be built from these documents
**Severity: Moderate** | `docs/design/dao.stream.ws.md:386-391` vs `dao.stream.implementation-plan.md:374-377`, `:386-395`, `:203-215`

**Claim.** 4d requires "a pause vocabulary both ends must recognize." The ws spec's Deferred list says it is "interoperable wire content, and it requires a durable home before it is built — the migration plan that currently describes it is consumed when its phases complete" (386-391). But the plan describes only semantics ("The request carries a duration — hold for *n* — and the reader renews," 386-395) and never the message shape.

**Correction.** The wire-contract gate (203-215) enumerates the deferred pieces it settles — handshake, disclaimer, close code, value codec, decode failure — and explicitly omits the pause vocabulary. So the one gate that could close this gap does not, and the ws spec's own "durable home" is the very document that consumes itself. An implementer of 4d has no wire format for pause/renew, and no assigned place to get one. This is a genuine "consensus missed it" item: backpressure was classified as "above the stream," so it escaped the wire gate even though the ws spec itself calls it interoperable wire content.

### 6. Plan 4d decides liveness parameters the ws spec explicitly defers
**Severity: Low-Moderate** | `dao.stream.implementation-plan.md:397-400` vs `docs/design/dao.stream.ws.md:379-381`

**Claim.** The ws spec defers "how many unanswered probes end a connection" and whether probes are ping/pong or deposited values (379-381). The plan then states "the serving side pings on an interval and drops a connection after **two** unanswered" (397-400), while also claiming "nothing here changes the ws transport" (372).

**Correction.** A subordinate, transient document is fixing a parameter its superior document left open, and still leaves the probe mechanism (protocol frame vs deposited value, interval value, which component answers) unspecified. Either the ws spec should settle liveness at the wire gate, or the plan should stop short of "two" and defer to the ws spec's own decision.

### 7. The descriptor decision gate claims evidence Phase 3 cannot produce
**Severity: Low** | `dao.stream.implementation-plan.md:196-199` vs `:182-194`

**Claim.** "The contract leaves the descriptor envelope TBD and this phase produces the evidence for settling it," and the gate settles `:ws/…` "against it."

**Correction.** Phase 3's round trip is in-memory, exercises a local ring-buffer descriptor (`:dao.stream/type` + stream identity), and never exercises the ws-specific entry data (`:ws/host`, `:ws/port`, `:ws/path`, TLS — `dao.stream.ws.md:284-287`) nor the actual wire codec. It can evidence the generic envelope shape; it cannot evidence the ws descriptor key set. Phrase the gate as "settle the generic envelope, then settle ws keys against that shape by decision," not as evidence-driven.

### 8. `lag` is undefined for an evicted cursor, which is precisely the cursor flow control holds
**Severity: Low** | `dao.stream.implementation-plan.md:153-157`, `:382-384`

**Claim.** `lag` "answers, as data, how many retained values lie between that cursor's position and the newest." The flow-control reader measures occupancy with this operation (382-384).

**Correction.** Between two `next` calls, bounded retention may evict the reader's cursor position. The definition says nothing about what `lag` returns when the cursor's position is no longer retained (the count is still definable, but "between that cursor's position and the newest" is not, and the reader will next see `gap`). State the evicted-cursor answer, or state that the reader must treat a following `gap` as authoritative and not act on a stale `lag`.

### 9. Resolution events are mislabeled as "lifecycle"
**Severity: Low (terminology)** | `docs/design/dao.stream.ws.md:347-348` vs `:113-122`

**Claim.** "an authoritative `not-found` and a transient failure both reduce to `closed` here, and the distinction survives only as the correlated **lifecycle** event on the deposit medium."

**Correction.** The distinguishing events are `:ws/not-found` and `:ws/transport-error`, which the spec classifies as **Resolution**, not Lifecycle (113-122). Say "correlated resolution event." Small, but it muddies the exact taxonomy an implementer of the retry policy relies on.

### 10. The contract's "a handle implements the subset of these operations" is imprecise about `create!`/`attach!`
**Severity: Low** | `docs/design/dao.stream.md:61` vs `:338-340`, `dao.stream.implementation-plan.md:83-93`

**Claim.** "A handle implements the subset of these operations its transport declares" — where "these" is the seven-operation table including `create!` and `attach!`, which consume no handle.

**Correction.** The contract later corrects itself (338-340: reader/writer/closable), and the plan makes the split explicit ("five handle operations … two transport entry functions," 83-93). But the authority document states it wrongly at line 61. Make line 61 say "the subset of the handle surface."

---

## What the group most likely got wrong by agreeing

- **They treated "no operation waits" as uniformly satisfiable by the existing closed outcome sets.** It is for `next` (`blocked`), `append!` (`full`), and `attach!` (`ok`-with-caveat), but not for `create!`, whose `ok` has no deferred caveat and whose set has no pending outcome. Finding 2 is the residue: everyone agreed on the polling model, so the plan extended the `attach!` caveat to `create!` without noticing the contract never authorized it.

- **They agreed "exactly one resolution event per attachment" without exercising the abort path.** The convergence process evidently reasoned forward from a successful or failed *connection attempt*, never from `close!` racing an in-flight `attach!`. Finding 3 is that blind spot.

- **They agreed the attachment-identity correlation rule for the client `attach!` path and stopped there.** The contract only places `:dao.stream/attachment` in an `attach!` success map; the server-side accepted connection has no `attach!`. Everyone knew server connections carry identity on deposits, but nobody noticed the missing *source* of that identity (finding 4).

- **They classified backpressure as "above the stream" and therefore exempt from the wire-contract gate**, even though the ws spec's own Deferred list calls it "interoperable wire content" needing "a durable home." The gate enumeration (finding 5) and the liveness "two unanswered" (finding 6) both flow from the same pattern: things adjacent to the transport were assumed to be settled by the transport's gate, or by nothing.

- **They accepted the plan's "corrected to match" self-report at face value** (finding 1). This is the most direct evidence that consensus substituted for re-reading.

## What an implementer cannot determine from these documents

- **How to mint and expose the server-side attachment identity.** The contract defines it only for `attach!` results; the ws spec says the server deposits it "exactly as on the client"; the plan says the server handle is minted "neither `create!` nor `attach!`" and leaves the mechanism to 4c. No document specifies the constructor's return shape or how the composition learns the identity to correlate with a connection.

- **The pause/backpressure wire format.** 4d requires both ends to recognize a pause vocabulary; the ws spec defers it; the plan gives only lease semantics; the wire gate doesn't cover it.

- **The liveness probe protocol.** Ping/pong vs deposited values, interval, answerer, and drop threshold are all deferred in the ws spec, yet 4d requires a drop ("after two unanswered") that presumes a mechanism.

- **What `lag` returns for a cursor whose position has been evicted**, and whether the flow-control reader must suppress acting on a stale measurement.

- **Which codec Phase 3's "transit codec" is**, and whether it is the same codec the wire-contract gate later settles for ws. The plan references a codec (182-183, 456-457) but never names it; the ws spec defers "the value codec" (376-378).

- **Who supplies the ring-buffer `attach!` used in Phase 3.** Phase 2 defines the ring buffer as reader+writer+closable and never mentions `attach!`; Phase 3 performs `attach!` "against that composition" via a directory that is "not … any transport." The contract's Host Dispatch section implies a host-composed closure, but no phase is assigned to produce it.

- **What, if anything, is deposited when `close!` aborts an establishing attachment** (finding 3) — i.e., whether the deposit medium receives any record of a locally aborted, never-resolved attachment.

- **The exact return shape of `forward-step`'s state/status** (plan 285-286), and the ring buffer's stated exclusion reason for `transport-error` on `next`/`cursor` (the manifest requires a reason for every exclusion, but the plan does not record it; the contract's template at 436-437 is the only source).
