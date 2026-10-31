Completed-GMT: 2026-09-26 18:05:00 GMT
Completed-Local: 2026-09-27 01:05:00 +0700
Coding-Agent: deepseek
Session-ID: 7aec5601-a998-45b8-a314-235fd90bcf23

## L1 — WHICH NETWORK RESOURCES NEED A LEASE

`dao.lease.md` requires a judge "composed **inside the boundary that possesses the resource**" (`:112-117`), a reclaim procedure per subject, and an attribution resolver; and it states "what is leasable — this contract gates nothing, and the domain decides" (`:295-296`). Applying that:

| resource | lease? | grantor/judge | holder | subject | why / why not |
|---|---|---|---|---|---|
| **served table entry** `{id → {handle surface}}` | **yes** (optional) | holding peer | the attaching peer (resumer/reader) | the served identity | lifetime is a *decision* ("keep serving while X needs it"); unbounded otherwise. Reclaim = un-serve. |
| **relay inbox pair** on meeting peer M | **yes** | M | the peer A whose inbox it is | the pair | M holds ring buffers on A's behalf; must retire when A stops renewing. Reclaim = retire pair. |
| reflection link state (cursor, outstanding ids, filed answers) | no | — | — | — | governed by **channel close**. |
| UDP fragment-reassembly buffers | no | — | — | — | governed by **bounded eviction** (`:max-partial-messages`). |
| NAT mappings / hole-punch state | no | — | — | — | governed by **liveness cadence** (ping keepalive). |
| meeting-board postings of reflexive addresses | no | — | — | — | governed by **board retention**. |
| channel connection (WS socket, UDP socket) | no | — | — | — | governed by **channel close**. |
| the mirror step itself | no | — | — | — | stateless; holds nothing to lease. |

The rule: **lease only what a channel close or a bounded buffer does not already govern.** That is exactly the two resources whose lifetime is a *decision* by a third party — the served entry and the relay pair — matching the retired spec's §15 lifetime decision ("lease-governed … served entry and the meeting inbox pair," `docs/design/dao.stream.serve.md:1104-1108`).

## L2 — THE MECHANISM

**RULING: zero new wire shape.** `dao.lease.md` "adds no operation to any contract and no key to any DaoStream result map" (`:8-10`); facts are "plain data on ordinary streams" (`:14`). So the lease negotiation is **two ordinary served streams** that the grantor exposes through its own table and that the holder reaches through ordinary reflections — the same primitive, no special casing:

- grantor serves a **lease-request stream** (surface `#{:writer}`) and a **lease-grant stream** (surface `#{:reader}`), both ordinary table entries with their own identities.
- holder attaches reflections to both, appends `{:dao.lease/status :dao.lease/proposed …}` to the request stream, reads `accepted`/`rejected` off the grant stream.
- the **judge** is the grantor's own interpreter over the request stream, inside the boundary (it possesses the resource), classifying and reclaiming.

**Where renewal travels.** A renewal is an event fact `{:dao.lease/event :dao.lease/renewal :dao.lease/lease …}` (`:36-37`) the holder appends through its request-stream reflection; it is an ordinary value carried by the existing `append!` request. Nothing new.

**How a remote holder learns of a reclaim.** `:lapsed` "does not cross; it is the grantor's record on the grantor's stream. A remote holder learns of a reclaim by observing it — for a served connection, an ordinary `:ws/closed`" (`dao.lease.md:286-289`). Through a reflection the holder observes the *consequence*: its next op on the un-served stream answers `not-found` (entry removed from the table), and/or the channel closes. The grant's own record (with `:lapsed`) is the grantor's local fact, never delivered.

**Expiry vs channel loss.** Expiry is a *decision*: the grantor's judge ends tenure, un-serves, and the stream is genuinely gone — a later `attach!` answers `not-found`. Channel loss is an *event*: the connection died, the reflection answers `blocked`→`end`/detached, and the stream is still served — `attach!` succeeds. Both are observable, but expiry is irreversible and grantor-authored; loss is transient.

**Verdict:** no new frame, no new op, no new descriptor field. The only additions are (a) two served streams the grantor chooses to run, and (b) a judge interpreter, both of which `dao.lease.md` already lists as composition duties (`:229-250`).

## L3 — THE BOUNDARY

| amendment from the converged design | belongs in | why |
|---|---|---|
| `blocked` handle-relative reword ("nothing observable at this position **through this handle** yet") | **dao.stream.md** (Reading `:545`) | `blocked` is already handle-relative for `closed`/`end` (`dao.stream.md:603-604`); the reword generalizes an existing fact, not a network fact. |
| "may initiate transport work whose answer arrives later" | **dao.stream.md** (Where the asynchrony goes) | already implied by `attach!` returning `ok` while establishment is pending (`:323`), `create!` (`:328-334`), `cursor` consulting the medium (`:488-491`); it makes explicit what is already there. |
| OD-1 `:dao.stream/retry?` | **dao.stream.md** | general result-map fact (open maps), host-independent. |
| OD-2 (append effect unknown) | **dao.stream.md** | general writer-surface fact ("what becomes of the value at the far end is reported there," `:570-576`). |
| OD-3 (cursor is serializable plain data) | **dao.stream.md** | general cursor/envelope fact, host-independent. |
| `:dao.stream/reason :oversize` / `:not-found` | **dao.stream.serve.md** | serve/transport vocabulary; the contract's op outcome sets have no `not-found` for `next`/`cursor`, so these are serve-layer answers, not contract outcomes. |
| "deferred remote observation" (the reflection's nature) | **dao.stream.serve.md** | a network concept; the reflection *declares* it, the contract stays transport-agnostic. |
| lease lifetime of served entries and inbox pairs | **dao.lease.md** (cited, not amended) | `dao.lease.md` already owns the vocabulary; serve.md cites it. |

**Q1 re-answered in this light.** With network concerns owned by serve, the `dao.stream.md` amendment is **narrowed but not zero**: only the five *general* rows above (blocked reword, transport-work sentence, OD-1/2/3), none of which names a network. **The gpt-6-sol objection still does not stand** — no "pending outcome" is needed; `blocked` + the transport-work sentence + OD-1's retry key are sufficient, because the objection's reading ("no operation may touch IO") is refuted by the contract's own `attach!`/`create!`/`cursor` deferral.

## L4 — DELTA

Diff against the fable CONVERGED DESIGN (≤400 words):

- **Tables.** No change to the mirror/table shape. A peer that chooses bounded serving *adds* two table entries (lease-request `#{:writer}`, lease-grant `#{:reader}`) and runs a judge interpreter over them; a peer that serves unconditionally adds nothing. Leases are optional per resource, never a protocol feature.
- **Descriptors.** Unchanged. The lease streams are ordinary identities; nothing names a lease in the descriptor.
- **Reflection.** Unchanged. Lease facts are ordinary values: a `proposal`/`renewal` is an `append!` argument; a `grant`/`rejection` is a `next` value. Reclaim is observed as `not-found` on the un-served stream and/or channel close.
- **Relay pair / meeting board.** The pair becomes a lease subject (grantor M, holder A); the board postings stay retention-governed, un-leased.
- **Boundary.** `dao.stream.md` receives only the five general amendments (L3); serve.md owns `:oversize`/`:not-found`/deferred-observation and cites `dao.lease.md` for lifetime.

**One genuine gap this introduces.** `dao.lease.md` requires an attribution resolver — "who authored a fact … since DaoStream supplies none" (`:238-241`) — and a renewal "counts only when its attributed author is the lease's holder" (`:78-80`). The converged design deliberately dropped the protocol peer id. Resolution (no protocol change): the holder mints a self-minted id and carries it in its lease facts (convention data, exactly the retired spec's meet/here pattern); the grantor's attribution resolver reads that payload value, or the mirror's channel attachment identity. This is a composition duty, not a wire shape.

**Owner-visible.**
1. **Leases are optional**, not required by the invariant; the owner's statement makes them "most likely" for bounded serving and third-peer relay pairs. A served stream is reclaimed = permanently un-reachable (`attach!` → `not-found`); this differs from channel loss (`attach!` succeeds). The owner must accept that a reclaimed resource is gone, not just disconnected.
2. **Attribution without authentication.** The holder id in lease facts is self-minted and spoofable until authentication exists — same plaintext/ungated caveat as the base design.
3. **Prerequisite blocker.** `dao.lease.md` is "proposed design target" (`dao.lease.md:3`). Its acceptance is a *prerequisite* for lease-governed serving; until then served entries and inbox pairs fall back to the weaker composition-retirement stopgap the retired §15 named. The base serve design does **not** depend on it.

**Verdict: READY TO SPECIFY**, with `dao.lease.md`'s "proposed" status as the one prerequisite to record — the serve spec may cite it for lifetime, but lease-governed resources are not claimable as working until it is accepted.
