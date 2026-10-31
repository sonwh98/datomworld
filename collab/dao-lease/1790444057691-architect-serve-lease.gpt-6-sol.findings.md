Completed-GMT: 2026-09-26 17:36:50 GMT
Completed-Local: 2026-09-27 00:36:50 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0debb-6229-79e1-890d-4d1e0b7d8565

## L1. Which network resources need a lease

DaoLease’s judge must run inside the boundary that holds the resource, and leases cannot gate stream history or be consulted by DaoStream operations. Reclaim the serving or routing resource itself; preserve its records. [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:92) [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:111)

| Resource | Lease? Holder; grantor/judge; subject |
|---|---|
| Served table entry | **Yes** where a remote peer depends on its continued availability. Holder: attaching peer. Grantor/judge: peer holding the handle and table entry. Subject: `{stream-identity, entry-incarnation}`. Lapse removes the mapping, not the stream or its history. The earlier draft likewise proposed leasing served entries. [dao.stream.serve.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.serve.md:1009) |
| Relay inbox pair | **Yes.** Each using peer holds a lease for its assigned inbox side; relay peer M grants and judges it. Subject: `{pair-id, side, incarnation}`. Reclaim that side’s inbox; retire the pair when neither side remains leased. |
| Reflection link state | **No.** It belongs to the attaching peer and is freed on local `close!` or channel teardown; bound its outstanding requests and filed answers. |
| UDP reassembly buffers | **No.** They are channel-internal bounded transient state; size and partial-message limits govern memory, and incomplete messages expire. |
| NAT mapping and hole-punch state | **No.** Mapping expiry and punch attempts belong to the network/channel composition; they do not represent a separately granted application resource. |
| Meeting-board address posting | **Yes, for an active route claim.** Holder: peer publishing its reflexive address. Grantor/judge: board-owning peer. Subject: `{peer-claim, socket, address, epoch}`. Lapse removes the active routing claim/index, not the append-only board history. |
| Channel connection | **No by default.** Socket close and the channel’s liveness policy govern it; do not add a second lease solely to duplicate that lifecycle. If the composition chooses leases to reclaim half-open connections, the endpoint holding the socket grants, the remote attachment holds, and the subject is that connection incarnation. |

The grantor needs an attribution resolver for each holder; DaoStream itself supplies none. A channel attachment can attribute WebSocket facts, while UDP source-address attribution is not authentication and may change across NAT rebinding. [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:232)

## L2. The mechanism

**RULING:** Carry lease facts over ordinary streams using the same reflection and mirror mechanism. Add no serve request or answer shape and no DaoStream operation. DaoLease explicitly makes its facts ordinary stream payloads and adds no operation or result key. [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:8) [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:280)

For each resource owner, composition exposes a stable, process-scoped lease-facts stream (or stream pair for holder-to-grantor evidence). Holders reach it as they reach other streams. Proposals, grants, refusals, releases, and renewals travel as ordinary payloads; the holder appends renewals under its own control flow. The lease-facts stream and basic reachability needed to access it are bootstrap composition resources, not themselves leased by the leases they carry. Grantor-local `:lapsed` records stay on the grantor’s stream. [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:98) [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:282)

When a grantor lapses a served-entry lease, it removes the entry from the active table. A subsequent call for that identity returns a serve-defined `transport-error` outcome with a reason such as `:dao.serve/reclaimed`; the holder observes that ordinary result through its reflection. This uses the existing correlated outcome message, not a new frame. Relay-pair lapse closes or unserves the affected inbox; the user of that side observes channel closure or its reflected transport failure.

Lease expiry is not the same as channel loss. A dropped channel does not itself prove a lease lapsed; the grantor judges silence from lease evidence and its tick stream, with the specified tolerance and observation rules. Conversely, a lease may lapse while a shared channel remains open. [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:137) [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:266)

**RISK:** The lapsed `transport-error` reason is a serve-layer extension to an outcome map. Ensure the mirror’s “missing identity” behavior distinguishes a revoked/expired entry from a never-known identity without inventing outcomes for operations whose contract does not permit them.

## L3. The boundary

| Change or rule | Belongs in | Ruling |
|---|---|---|
| `blocked` means nothing observable at this position *through this handle* yet | **dao.stream.md** | Handle-relative result semantics are part of the generic operation contract, not networking. |
| Operations may initiate transport work but never wait for its answer | **dao.stream.md** | State this generically beside the no-waiting rule; the current text says no operation waits and answers what is true now. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:141) |
| `:dao.stream/retry?` for a `cursor` whose answer is pending | **dao.stream.serve.md** | Keep it a serve-specific optional result key, not a required DaoStream result key. Define its exact meaning and when it is valid there. |
| `:dao.stream/reason :oversize` / revoked-or-missing reason | **dao.stream.serve.md** | These explain channel/serve-layer transport outcomes; lease itself adds no keys to DaoStream results. |
| OD-2: append effect may be unknown; correlation/dedup rules | **dao.stream.md** for generic append semantics; **dao.stream.serve.md** for request IDs and retry policy | The core must define what `append!` can truthfully report; serve specifies how its remote path handles that. |
| OD-3: cursor portability across serialization | **dao.stream.md** | Cross-host cursor validity is a generic cursor promise if a reflection is to be another handle on the same logical stream. The current text leaves serialization transport-owned and TBD. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:521) |

**Q1, reconsidered:** The earlier objection does not stand once the contract explicitly permits an operation to initiate work and defines `blocked` as handle-relative. A reflection may return `blocked` while it has not yet received the source value, then return the filed source outcome on a later call; that operation does not wait. The current text’s “nothing at this position yet” gloss needs the handle-relative clarification, and `cursor` still needs a serve-specific retry signal because `cursor` has no `blocked` outcome. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:153) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:542)

**RISK:** `blocked` must mean no value is observable through this reflection yet, not assert that the source itself returned `blocked`. Preserve this distinction when documenting filed source outcomes.

## L4. Delta and readiness

**RULING: READY TO SPECIFY**, with lease integration as a composition over exposed lease streams, not a serve protocol feature.

**WHY:** Add optional leases to active served-entry and relay-inbox lifetimes, and to expiring rendezvous claims. Leave mirror dispatch stateless: its table reflects current composition state, while a separate owner-side judge removes resources on successful reclaim. Reflection state remains local and channel-bounded. The fable design’s request, outcome, ID, and UDP fragment shapes are unchanged.

**DELTA (under 400 words):** Add a process-scoped lease-facts stream to the owner’s composition and expose it through the same table/reflection path. The holder observes a grant before relying on the resource and renews by appending lease facts; the grantor judges renewals and removes the active served-entry mapping or relay inbox side on reclaim. A revoked identity remains in lease history but is no longer available for new operations. The reflection learns this through a serve-defined transport-error reason on its ordinary result path; no lease frame is added. Lapsed records remain grantor-local, consistent with DaoLease carriage, where remote holders learn reclaim from the resource’s observable closure rather than receiving the lapsed fact itself. [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:280)

For relays, use a separately attributable lease per participant/side so one peer’s lapse does not silently revoke the other’s active inbox. Meeting-board address claims can be leased as active routing state; their historical payloads remain readable, and interpreters ignore expired claims. No lease is needed for the reflection’s local request state, bounded UDP fragments, punch attempts, or channel lifecycle.

**RISK / OWNER-VISIBLE:** Leases do not guarantee continuity during partitions: false lapse is possible, and a partition alone decides neither survival nor expiry. [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:266) An expired served entry can become unavailable while its underlying stream and retained history remain intact. Direct NAT traversal is still impossible in some cases without a mutually reachable relay. [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:268)

DaoLease is marked “proposed design target,” though its text calls itself the operative contract. That is **not a blocker to writing the serve specification against it**, but DaoLease must be accepted as the governing target before claiming the lifetime integration is an implemented, normative system rule. [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:3)
