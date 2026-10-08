Completed-GMT: 2026-09-26 17:35:07 GMT
Completed-Local: 2026-09-27 00:35:07 +07
Coding-Agent: claude
Session-ID: 506ecf77-3b05-43cf-9579-ad73759f7aa6

# dao.lease in the converged serve design

## L1. Which network resources need a lease

The test, from `dao.lease.md:113-117`: a lease exists only where a judge inside the possessing boundary can reclaim what it holds. Anything a channel close or a bounded buffer already governs gets none.

| Resource | Lease? | Holder | Grantor / judge | Subject | Why |
|---|---|---|---|---|---|
| Table entry the peer serves by its own policy (the toy string, a public door) | **No** | | | | The peer possesses it and retires it when it likes; nobody else's liveness bears on it. |
| Table entry served **for a remote party** (a stream a migrated continuation still reads, UCF §13 of the retired draft, `dao.stream.serve.md:1004-1013`) | **Yes** | the remote peer | the holding peer | the identity | The holding peer cannot otherwise know when the remote party is gone; today it pins forever. |
| Relay inbox pair on a third peer M | **Yes** | the requesting peer | M | the two pair identities | The archetype: a resource M creates for someone else, whose only liveness signal is that someone's renewals. |
| Reflection link state (channel cursor, outstanding ids, filed answers) | **No** | | | | Local to the attaching peer; bounded by outstanding requests; freed by the reflection's local `close!` or the channel's close. |
| UDP reassembly buffers | **No** | | | | Bounded by `:max-partial-messages`, evict-oldest. A bounded buffer is the governance. |
| NAT mappings and punch state | **No, and cannot be** | | | | The NAT box possesses the mapping, not either peer, so no judge can exist under `dao.lease.md:113-117`. Keep-alive probes are the peer's own cadence policy; nonce state is bounded link state. |
| Meeting-board postings (`:meet/seen`) | **No separate lease** | | | | A posting is a record, and "a reclaim frees the resource, never the record" (`dao.lease.md:98`). The posting carries the lease id of the registration or pair it belongs to; readers treat it as stale once that lease's `:lapsed` appears on the board. |
| Channel connections (WebSocket sockets, UDP sockets) | **No** | | | | Close already governs them, and `dao.lease.md:286-289` already names `:ws/closed` as how a reclaim is observed. Idle-close is each end's own policy; no end is privileged to keep the other open. |
| The stateless mirror | **No** | | | | It holds nothing between calls. |

**RULING.** Two leased subjects only: a table entry served for a remote party, and a relay pair. Both reduce to one thing, "an entry in my table that exists for you", so one reclaim procedure serves both: dissoc the entries and drop the local handles. **RISK.** Low. A peer that leases nothing behaves exactly as the converged design already does.

## L2. The mechanism

**RULING.** The lease negotiation rides the served-stream primitive unchanged. Nothing is added to the request or answer shapes; no new wire shape exists. Lease facts are values appended through reflections and read through reflections.

**Streams.** A grantor puts two conventional entries in its table: `lease-proposals`, surface `#{:writer}`, and `lease-grants`, surface `#{:reader}`, published as ordinary remote descriptors beside its meeting descriptors. A holder appends a proposal through a reflection on the first, and reads the grant through a reflection on the second. **Attribution** is the one place the stateless mirror bites: it stamps nothing, so a shared many-writer proposal stream cannot say who renewed, and `dao.lease.md:78-80` counts only the holder's renewals. The lease contract's own answer is "per-author media" (`dao.lease.md:237-241`, and `:274-276` "per-attachment media are the isolation"). So the grant creates one more table entry, a ring buffer with surface `#{:writer}`, and names its identity as `:dao.lease/holder`. That stream is the holder's renewal medium; whatever lands on it is attributed to the holder by the medium. The judge reads it with a local cursor as one wired fact source, exactly as `make-judge` expects (`src/cljc/dao/lease.cljc:2114`, resolver at `:629-642`). The renewal stream lives and dies with its lease; the reclaim procedure removes subject entries and renewal entry together.

**Where renewal travels.** `append!` on the holder's reflection of its renewal identity, value `{:dao.lease/event :dao.lease/renewal :dao.lease/lease L}`. Through a reflection, `append!`'s `ok` is outbound acceptance only (`dao.stream.md:570-576`); the source's outcome arrives on the link's event writer correlated by request id. `dao.lease.md:207-209` says a renewal counts for the holder's bound only on an append returning `ok`; the serve convention reads that as the **source's** `ok`, so a holder wires the event writer and advances its bound only on it. A holder that skips the event writer stops at its bound early and safely, never late.

**How a remote holder learns of reclaim.** Three ways, all existing observations. Operationally: the mirror answers `not-found` for a reclaimed identity, and the reflection, on any `not-found` for its identity, marks itself gone and answers every later op `transport-error` with `:dao.stream.serve/reason :not-found`. By record: the holder's reflection on `lease-grants` reads the grantor's own `:lapsed` fact with its cause. By discipline: the holder stops at its own bound regardless (`dao.lease.md:210-212`). The second way needs one clarifying sentence in `dao.lease.md:286-289`: `:lapsed` is not carried, but a remote holder reading the grantor's stream through a served handle is observing the grantor's stream, which is what the serve design's central claim makes true.

**Expiry versus channel loss.** Channel loss is the absence of answers: `:resend-after` exhausts, or the channel deposits its closed event; the table entry persists, so a holder that reconnects within duration plus tolerance finds its entry, its cursors and its lease intact. Expiry is a present answer, `not-found`, on a healthy channel. The two are distinguishable at the reflection, and only expiry frees anything.

**Units and ticks.** Grantor and holders must share a unit table (`dao.lease.md:60-63`); the serve conventions fix `{:ms 1 :s 1000}` (`src/cljc/dao/lease.cljc:68-77`). Each side reads its own tick stream; rate skew across hosts is what the judge's tolerance covers (`dao.lease.md:257-258`).

**RISK.** Renewal streams are unauthenticated like every descriptor: anyone who learns the identity can renew on the holder's behalf. This is the same caveat the whole design already carries, not a new one. One ring buffer per lease is the cost.

## L3. The boundary

Rule: `dao.stream.md` may say things true of every transport, including a file. Anything that presupposes a far end, a request in flight, or a datagram belongs to `dao.stream.serve.md`.

| Amendment | Home | Why |
|---|---|---|
| `blocked` is handle-relative | **serve.md** declares it as the reflection's nature; **dao.stream.md** optionally adds one network-free sentence | The contract already says each outcome is "one perspective... at one moment" and that a reader seeing `blocked` then a value "has not caught the stream in a contradiction" (`dao.stream.md:641-649`), and makes `closed` and `end` handle-relative (`:603-604`). Serve declares its reading under the existing rule that a transport declares its outcomes and why (`:445-453`). |
| "an operation may initiate transport work" | **serve.md** | Precedent already in the contract for `attach!` (`:323`, `:344-346`), `create!` (`:328-334`), `cursor` (`:488-491`); serve cites it, the contract need not restate it. |
| OD-1 `:dao.stream/retry?` and the unrecognized-outcome rule | **dao.stream.md** | About outcome-set evolution for any transport; already drafted there (`:811-849`). Serve's use for pre-anchor `cursor` is declared in serve.md. |
| `:reason :oversize` and `:not-found` on `transport-error` | **serve.md**, renamed `:dao.stream.serve/reason` | A transport-owned key under the transport's namespace (`dao.stream.md:253-256`; `datom.world.md:96-98`). |
| OD-2, append effect unknown, dedup is the payload's | **dao.stream.md** | It is about a durable file whose sync fails and a quorum log as much as a network (`:865-868`). |
| OD-3 decision 2, cursors survive the codec | **dao.stream.md** | "A file as much as a network socket is a serialization boundary" (`:230-232`). |
| Lease vocabulary | **dao.lease.md unchanged**, plus the one Carriage sentence above; the two conventional streams, the per-lease renewal medium, and the reclaim procedure in **serve.md** | `dao.lease.md:8-10` promises to add nothing to `dao.stream.md`, and serve keeps that promise. |

**Q1 re-answered.** With the network owned by serve, **no amendment to `dao.stream.md` is required for the reflection to conform.** `:641-649` already makes `blocked` a perspective of one handle at one moment, and Surfaces already lets a transport declare which outcomes it produces and on what condition. The optional sentence is hygiene. The gpt-6-sol objection does not stand, and its remedy is now ruled out on principle: a `pending` outcome and a standard result channel would write "a request is in flight" into the abstraction contract, which is precisely the network concept the owner's statement keeps out of it. **RISK.** A contract-generic consumer such as the VM engine polls a reflection's `blocked` exactly as it polls a ring buffer's; that is the intended behavior, and it is the only behavior the contract's IO model permits.

## L4. Delta against the Fable CONVERGED DESIGN

- **Table.** An entry may carry `:dao.lease/lease L`. Entries created for a remote party (relay pairs, exported stream cells) always do; entries a peer serves by its own policy never do. Mirror step unchanged and still stateless.
- **Grantor conventions.** Two conventional entries, `lease-proposals` `#{:writer}` and `lease-grants` `#{:reader}`, plus one `#{:writer}` ring buffer per live lease as the holder's renewal medium, its identity carried in the grant as `:dao.lease/holder`. The reclaim procedure, idempotent, removes subject entries and the renewal entry and reports success. Judge and holder are `make-judge` and `make-holder` (`src/cljc/dao/lease.cljc:2114`, `:2215`), each with its own tick stream; unit table `{:ms 1 :s 1000}`.
- **Reflection.** Any `not-found` answer for its identity, not only the attach probe, marks it reclaimed; later ops answer `transport-error` with `:dao.stream.serve/reason :not-found`. The reason key is renamed from `:dao.stream/reason`. A lease holder advances its renewal bound only on the source's `append!` `ok` observed on the link's event writer.
- **Relay pair.** Created only by a grant, never by a bare request; the pair identities are the subject; pair descriptors posted on the board carry `:dao.lease/lease`. M's restart loses its table and ledger, so every pair is gone and holders see `not-found` and re-propose; this is `dao.lease.md:192-200`'s reclaim-and-regrant with an empty inventory, and it is correct because the table is process-scoped.
- **Meeting board.** `:meet/seen` postings carry the lease id of the registration or pair they belong to; readers treat a posting whose lease has a `:lapsed` on the board as stale. No lease of their own.
- **Descriptors, request and answer shapes, channels, fragmentation.** Unchanged.
- **Amendments.** Placed per L3; `dao.stream.md` receives only OD-1, OD-2, OD-3 decision 2 and an optional one-sentence `blocked` clarification; `dao.lease.md` receives one Carriage sentence.

**Verdict: READY TO SPECIFY.** No blocker. `dao.lease.md`'s "proposed design target" status is not a prerequisite blocker: serve uses only its fact vocabulary, holder rules and judge, all of which exist in `src/cljc/dao/lease.cljc` (validity `:366-402`, holder `:1739-1910`, judge `:2114`), and every lease here is process-scoped, which `dao.lease.md:247-250` explicitly permits without the durable-resource trio.

**Owner-visible.** A relay pair and any stream served for a remote party now lapse when the remote party stops renewing; a peer that goes silent longer than duration plus tolerance loses its relay path and must re-propose. NAT mappings cannot be leased by anyone, so keeping them alive stays a probe cadence the peer runs itself. Renewal streams are unauthenticated like all descriptors until authentication exists.
