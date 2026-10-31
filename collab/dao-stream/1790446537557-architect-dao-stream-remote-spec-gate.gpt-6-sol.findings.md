Completed-GMT: 2026-09-26 18:29:06 GMT
Completed-Local: 2026-09-27 01:29:06 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0debb-6229-79e1-890d-4d1e0b7d8565

## 1. INVARIANT

**Mostly sound framing; not yet implementable as written.** The protocol has no client/server wire role or peer ID, and treats a relay, meeting board, and lease grantor as peers performing conventions. The service example’s “server” is explicitly a convention, which the owner allows. The mirror table is local dispatch, not a privileged network registry. [remote.md:47–77, 130–147, 348–395, 420–430](docs/design/dao.stream.remote.md)

The NAT caveats correctly acknowledge that some peers need a third peer to relay, browsers cannot listen, and traffic is plaintext without channel encryption. But “symmetric NAT or CGNAT” is too broad as a single outcome: CGNAT describes address sharing, not one mapping/filtering behavior, and some CGNAT paths can hole-punch. The caveat should identify endpoint-dependent mapping/filtering as the problem and say when relay is required. Also state that peers need an outbound path to a reachable WebSocket peer or relay; if UDP is blocked and no such path is available, communication is impossible. [remote.md:31–45, 348–395](docs/design/dao.stream.remote.md)

The string example uses ordinary stream operations and no string-specific wire case, but it depends on a WebSocket channel projection that the document does not specify (see §3). Its descriptor also omits the surface while the reflection claims to learn it from the attach probe. [remote.md:113–128, 186–189, 237–249, 401–418](docs/design/dao.stream.remote.md)

## 2. CONTRACT

The `dao.stream.md` edits appear limited to OD-1/2/3, the generic `refused` outcome row on cursor/next/append, and the composed-handle sentence. The refusal is absent from descriptor and close, as intended; the contract remains network- and capability-free. [dao.stream.md:439–443](docs/design/dao.stream.md) The remote document’s claim that only “three accepted decisions” affect the contract contradicts its own list of refusal and composition edits. [remote.md:24–27, 700–706](docs/design/dao.stream.remote.md)

Leaving `blocked` un-reworded is sound: reflection `next` returns it when no value is yet observable through that handle, consistent with the contract’s handle-relative, immediate outcome rule. [remote.md:191–216](docs/design/dao.stream.remote.md) [dao.stream.md:565–573, 643–650](docs/design/dao.stream.md)

**Defect:** OD-3 says a cursor outliving its stream yields `not-found` at `attach!` or `cursor-mismatch` at `next`. `attach!` does not take a cursor; correct the wording to describe attachment to a missing stream separately. [dao.stream.md:546–552](docs/design/dao.stream.md)

The spec adds `:dao.stream/refused`, but the implementation’s closed outcome sets do not yet include it. This is acceptable as a design change only if implementation work updates them before relying on the new row. [dao.stream.md:??](docs/design/dao.stream.md) [src/cljc/dao/stream.cljc:67–93](src/cljc/dao/stream.cljc)

## 3. THE PROTOCOL

**Blocking protocol defects:**

- The mirror emits `:dao.stream/not-found` as the outcome for descriptor, cursor, next, and append when an identity is absent or an operation is unsupported. These operations do not all permit that outcome: descriptor permits only `ok`; cursor, next, and append have their own closed sets. Nor can `not-found` represent a missing surface: the contract says a handle cannot answer an operation it does not declare. Define a protocol-level error distinct from the source operation’s outcome map, and use it consistently for missing identity, gone resource, and unsupported surface. [remote.md:94–107, 135–151, 227–235](docs/design/dao.stream.remote.md) [src/cljc/dao/stream.cljc:67–93](src/cljc/dao/stream.cljc) [dao.stream.md:405–410](docs/design/dao.stream.md)
- The surface test compares operation keywords such as `:dao.stream/next` with declared surfaces `:reader`, `:writer`, and `:closable`. Define the mapping (`cursor`/`next` → reader; `append!` → writer; `descriptor` always available), and handle unsupported operations without fabricating a DaoStream outcome. [remote.md:135–147](docs/design/dao.stream.remote.md) [dao.stream.md:405–410](docs/design/dao.stream.md)
- The descriptor carries no surface, yet the reflection says the attach probe’s answer supplies it and uses it to determine local operations. Neither the descriptor nor the described answer contains that information. Include declared surface in the protocol envelope or remove surface discovery and define a sound local handle contract. [remote.md:94–99, 113–128, 186–189, 237–249](docs/design/dao.stream.remote.md)
- The WebSocket path is not connected to the specified raw channel values. The existing WebSocket writer encodes `{:ws/frame :ws/value :ws/value value}`, while the host adapter deposits `:ws/payload`, lifecycle, and resolution events onto a DaoStream. The mirror expects raw request maps. Specify the projection that unwraps values, correlates attachments, and handles lifecycle events, or define a distinct channel adapter. Without it, the toy and the promised WebSocket unification do not compose. [remote.md:268–300](docs/design/dao.stream.remote.md) [src/cljc/dao/stream/ws.cljc:203–214](src/cljc/dao/stream/ws.cljc) [dao.stream.ws.md:71–82, 98–108, 138–160](docs/design/dao.stream.ws.md)
- Anchor piggybacking calls `cursor` directly on the table handle, bypassing middleware, and does so regardless of reader surface. That can expose gated anchors or invoke an unsupported operation. Only obtain anchors through the declared reader path and its middleware, or omit the optimization. [remote.md:143–151](docs/design/dao.stream.remote.md) [middleware.md:58–75](docs/design/dao.stream.middleware.md)

UDP fragmentation has bounded message size and partial-message count, and replies to the source address. But the 1200-byte budget is not defined as applying to the *entire encoded fragment*, including metadata; the reassembly key also needs channel/socket identity if IDs are unique only per channel reader. Specify those bounds and keying. [remote.md:290–324](docs/design/dao.stream.remote.md)

The lease sections distinguish expiry from channel loss and use per-author renewal media for attribution, without leasing stateless reflection state, buffers, or NAT mappings. That is coherent. However, reporting reclaimed resources as a DaoStream `not-found` outcome inherits the invalid-outcome problem above; use the protocol-level error envelope instead. [remote.md:439–502](docs/design/dao.stream.remote.md) [dao.lease.md:283–289](docs/design/dao.lease.md)

## 4. MIDDLEWARE AND SEAM

The position-preserving handle wrapper, two attachment points, and distinction between a dropping filter and middleware fit the composed-handle contract. Pure transforms composed with explicit dependencies can respect the no-callback/no-hidden-global-state invariants. [middleware.md:11–75, 145–153](docs/design/dao.stream.middleware.md) [dao.stream.md:439–443](docs/design/dao.stream.md)

**Blocking contradiction:** the prohibitions say transforms never loop over a handle, then permit gate to drain fact streams inside a call. Gate does exactly what the prohibition forbids. Worse, its state and cursors have no persistence mechanism: each call cannot both remain pure and advance a retained index. A gate that re-reads fixed cursors can repeatedly evaluate stale facts. [middleware.md:77–83, 96–116](docs/design/dao.stream.middleware.md)

The claimed ShiBi fit therefore does **not** pass as written. ShiBi is required to emerge from separate index and query interpreters over streams; the gate collapses fact draining/folding and request verification into a synchronous middleware function. Preserve the seam by having index/query interpreters publish an explicit decision or snapshot stream, and let middleware read an already-composed decision input under a defined nonblocking contract. Do not claim the current gate can drain and fold arbitrary streams in a total, immediate transform. [dao.shibi.md:7–16, 20–32](docs/design/dao.shibi.md) [middleware.md:99–116](docs/design/dao.stream.middleware.md)

The remote spec also describes whole-channel encryption but middleware only defines operation-map/handle wrapping. Value-level encryption does not hide values from a relay carrying the operation result; a channel-level transform would need an explicit attachment interface and ordering relative to codec/framing. Until then, qualify the encryption claim: metadata and values remain visible to the relay absent a separately specified channel wrapper. [remote.md:504–530](docs/design/dao.stream.remote.md) [middleware.md:11–56, 132–139](docs/design/dao.stream.middleware.md)

## 5. UNIFICATION AND `dao.jing.remote`

The fate table covers the listed paths, and the proposed `dao.jing.content` module clearly marks its new functions and the move of `accept-bytes!`; the existing function is private in `dao.jing.remote`. The new coordinate form is also identified as a change from today’s URL form. [remote.md:565–642](docs/design/dao.stream.remote.md) [src/cljc/dao/jing/remote.cljc:35–42](src/cljc/dao/jing/remote.cljc) [src/cljc/dao/jing/coordinate.cljc:20–32](src/cljc/dao/jing/coordinate.cljc)

I found no omitted path among the requested categories in the table. **Misclassification/claim to correct:** describing `dao.jing.content` and `dao.jing.dht` framing as “Base64-inside-Transit” is false for the new remote design’s CBOR option. Say that message boundaries come from the channel codec, with payload bytes represented as Base64 in the application value. [dao.jing.cbor.md:31–35, 433–438](docs/design/dao.jing.cbor.md) [remote.md:290–300, 615–620](docs/design/dao.stream.remote.md)

`yin.repl.link-policy.md` exists, so the ShiBi stub’s reference to it resolves. [dao.shibi.md:34–37](docs/design/dao.shibi.md)

## 6. COMPANION EDITS

The WebSocket, UCF, Dao Jing, B-tree, DHT, UDP, lease, linker, stack, and query changes generally point consumers at the new remote path and are framed as migration work. The CBOR framing statement above is the concrete false neighbour. One documentation inconsistency also remains: the remote file says contract impact is only three decisions, while its amendment inventory names more. [remote.md:24–27, 700–725](docs/design/dao.stream.remote.md)

The `dao.stream.md` “outlives the stream” wording also needs the `attach!` correction from §2. The required refusal outcome will need implementation support in the closed outcome sets before code consumes it. [dao.stream.md:546–552](docs/design/dao.stream.md) [src/cljc/dao/stream.cljc:67–93](src/cljc/dao/stream.cljc)

## 7. LENGTH AND SIMPLICITY

The 725-line remote spec is over its stated target. Move the DAO Jing migration design (§8) into its implementation plan; move the completion criteria and companion-edit inventory (§9–10) into a migration checklist. Those sections are not wire or semantic rules. Consolidate repeated relay/reachability explanations across the invariant, NAT, pair, and lease sections. [remote.md:612–642, 644–725, 31–45, 326–395, 439–502](docs/design/dao.stream.remote.md)

No essential old-draft concern appears lost: the new draft retains NAT caveats, relay, buffer bounds, lease lifetime, and transport loss distinctions. [dao.stream.remote.md:31–45, 268–324, 326–395, 439–502](docs/design/dao.stream.remote.md)

## 8. CITATIONS

The new docs do not provide literal `file:line` citations to audit; they use section/title references. Spot-checks against at least twelve referenced targets found the following accurate: stream payload and host-boundary principles in `datom.world.md`; descriptor/reader rules in `dao.stream.md`; WebSocket attachment identity and event deposits in `dao.stream.ws.md`; the 1200-byte DHT datagram bound in `dao.jing.dht.node.cljc`; lease carriage and lapse semantics in `dao.lease.md`; and the current private `accept-bytes!` and URL-only coordinate validation in the Dao Jing source. The CBOR document’s Transit-only framing claim is inaccurate for the new codec-neutral/CBOR remote channel, as noted above. [remote.md:102–107, 121–128, 290–300, 612–642](docs/design/dao.stream.remote.md) [src/cljc/dao/jing/dht/node.cljc:46–50](src/cljc/dao/jing/dht/node.cljc) [dao.lease.md:236–250, 283–289](docs/design/dao.lease.md)

## VERDICT: REJECT

### MUST-FIX

1. `docs/design/dao.stream.remote.md:135–151, 186–189, 227–235` — Define protocol-level missing/gone/unsupported errors separately from DaoStream operation outcomes; fix surface-to-operation mapping and surface discovery. Current responses violate closed outcome sets and cannot provide the surface the reflection needs.
2. `docs/design/dao.stream.remote.md:268–300` — Specify how WebSocket payload/event envelopes become channel request/answer values, including attachment correlation and lifecycle. Current channel and mirror types do not compose.
3. `docs/design/dao.stream.remote.md:143–151` — Route anchor reads through declared reader surface and middleware, or remove the piggyback behavior.
4. `docs/design/dao.stream.middleware.md:77–83, 96–116` — Resolve the no-drain prohibition versus gate’s draining behavior, and provide a sound explicit state/cursor progression model compatible with separate ShiBi index/query interpreters.
5. `docs/design/dao.stream.remote.md:504–530` and `docs/design/dao.stream.middleware.md:11–56, 132–139` — Do not imply value middleware hides data from a relay. Specify channel-level encryption attachment and codec ordering, or state that remote encryption is deferred.
6. `docs/design/dao.stream.md:546–552` — Correct the impossible “cursor … at attach!” wording.
7. `docs/design/dao.jing.cbor.md:31–35, 433–438` — Correct the Transit-only framing claim for the new codec-neutral/CBOR path.
8. `docs/design/dao.stream.remote.md:24–27, 700–706` — Reconcile the stated number/scope of contract amendments with the actual edits.

### SHOULD-FIX

1. `docs/design/dao.stream.remote.md:290–324` — Define whether the UDP datagram budget includes fragmentation metadata and include channel/socket identity in reassembly keying.
2. `docs/design/dao.stream.remote.md:31–45, 348–395` — Describe NAT traversal by mapping/filtering behavior; state the reachable outbound WebSocket/relay requirement for UDP-blocked and browser peers.
3. `docs/design/dao.stream.remote.md:612–642, 644–725` — Move migration detail and completion/checklist material out of the core protocol document and remove repeated NAT/relay explanation.
4. `src/cljc/dao/stream.cljc:71–93` — Add the new `:dao.stream/refused` outcome to the implementation sets before implementing middleware that emits it.
