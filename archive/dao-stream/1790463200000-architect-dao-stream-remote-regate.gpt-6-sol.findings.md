Completed-GMT: 2026-09-26 20:39:08 GMT
Completed-Local: 2026-09-27 03:39:08 Asia/Ho_Chi_Minh

## Round-1 disposition

| Round-1 item | Disposition | Verification |
|---|---|---|
| MUST 1: protocol errors and surface mapping | FIXED | Errors use `:dao.stream.remote/error`; operation-to-surface mapping and descriptor surface are now specified. [remote.md:103–146, 170–183](docs/design/dao.stream.remote.md) |
| MUST 2: WebSocket projection | FIXED | `ws-project` projects per-attachment payloads and closes its ring buffer on terminal events. [remote.md:320–343](docs/design/dao.stream.remote.md) |
| MUST 3: anchor piggyback bypass | FIXED | Piggybacking is removed; all anchor calls use the middleware path. [remote.md:195–200](docs/design/dao.stream.remote.md) |
| MUST 4: middleware gate and ShiBi fit | PARTIALLY FIXED | Fact folding moved to the index interpreter, but the decision reader’s initialization, advancement, and concurrency rules remain incomplete; the ShiBi query-interpreter mapping is an adapter claim, not a defined execution path. [middleware.md:99–132](docs/design/dao.stream.middleware.md) [dao.shibi.md:25–34](docs/design/dao.shibi.md) |
| MUST 5: encryption caveat | FIXED | Channel encryption is deferred and relay visibility is explicit. [remote.md:46–49, 580–590](docs/design/dao.stream.remote.md) |
| MUST 6: OD-3 wording | FIXED | Stale cursor and missing stream are separate cases. [dao.stream.md:546–554](docs/design/dao.stream.md) |
| MUST 7: CBOR framing | FIXED | Channel framing and Base64 payload carriage are distinguished. [dao.jing.cbor.md:31–38, 437–442](docs/design/dao.jing.cbor.md) |
| MUST 8: amendment count | FIXED | Both sections enumerate five amendments. [remote.md:28–31, 666–675](docs/design/dao.stream.remote.md) |
| SHOULD 1: UDP budget/keying | FIXED | Budget includes the fragment envelope; reassembly key includes attachment identity. [remote.md:363–377](docs/design/dao.stream.remote.md) |
| SHOULD 2: NAT behavior | FIXED | Mapping/filtering behavior and outbound-path impossibility are stated. [remote.md:35–45, 414–432](docs/design/dao.stream.remote.md) |
| SHOULD 3: length | PARTIALLY FIXED | Reduced to 675 lines, still above the requested under-600 target. [remote.md:1–10, 623–675](docs/design/dao.stream.remote.md) |
| SHOULD 4: `refused` in source sets | FIXED AS A PLAN ITEM | Implementation slice 0 names the outcome-set update; source remains unchanged pending implementation. [implementation-plan.md:64–69](docs/design/dao.stream.remote.implementation-plan.md) |

## Findings

**P1 | `docs/design/dao.stream.middleware.md:102–124` | Decision reads are not fully specified.** The gate needs a starting cursor, but its configuration accepts only a decision handle. “One `next`” and “on gap, reads once more” do not say how the cursor and cached decision change for each result, or what happens if that second read is itself `gap`. Multiple calls can also race on the wrap-held cursor and decision. **Fix:** specify initial cursor acquisition, every outcome’s state transition, repeated-gap behavior, and serialization or concurrency semantics for a wrapped handle.

**P1 | `docs/design/dao.stream.middleware.md:103–120`; `docs/design/dao.shibi.md:25–34`; `docs/design/dao.stream.remote.md:608–618` | ShiBi’s two-interpreter fit is only partial.** An index interpreter can publish a derived decision, but the query interpreter is represented only as a pure `verify` function over that latest decision; the spec does not show how `dao.space.query` interprets a request against the tuple space or publishes a request-specific decision. Thus the seam is a plausible adapter, not a demonstrated fit for the owner’s index-and-query interpreter direction. **Fix:** define how the query interpreter consumes requests and returns decisions through ordinary streams, or explicitly make `verify` the query interpreter’s required adapter contract and state its limits.

**P2 | `docs/design/dao.stream.remote.md:623–675`; `docs/design/dao.stream.remote.implementation-plan.md:121–162` | The core spec is not right-sized against the under-600 target.** Its unification table and contract-amendment inventory duplicate migration material moved to the implementation plan. The target can be met without dropping a protocol rule. **Fix:** move or condense the fate table and amendment inventory, retaining only the essential successor pointer and any normative rule.

**P2 | `docs/dao.space.stigmergy.md:242–245` | A repository companion still prescribes the deprecated whole module.** The working plan says `dao.jing.remote` is deprecated, but this document still directs readers to `dao.jing.remote/default-handlers` and `connect-content!`. **Fix:** mark this passage historical or update it to point to the planned `dao.jing.content` successor.

**P2 | `docs/design/dao.stream.remote.md:406–410, 270–278` | Pair-channel gap-to-loss handling needs one explicit link transition.** The pair section calls an `in` gap channel loss and says resend absorbs it; the reflection only abandons outstanding requests when its reader reaches `end`. This leaves the path from gap to end—and thus append-unknown reporting—implicit. **Fix:** state that the pair adapter turns that gap into channel termination for the affected link, or define equivalent loss reporting and resend behavior.

## Gate checks

The single answer shape is now coherent: ordinary operation outcomes remain distinguishable from protocol errors, and the reflection translates those errors locally. The mirror avoids the earlier invalid-outcome and surface-bypass errors. [remote.md:103–146, 165–207, 257–282](docs/design/dao.stream.remote.md)

Lease subjects, holder/grantor roles, renewal streams, reclaim observation, and channel-loss distinction are stated consistently. The visible caveat that a descriptor holder may renew another peer’s lease until a gate is composed is appropriately explicit. [remote.md:503–562](docs/design/dao.stream.remote.md)

The P2P design has no protocol-level client/server role or privileged peer. Dial/accept and relay roles are transport or convention choices, and NAT impossibilities are stated plainly. The string walk-through now uses the same generic stream operations as other values. [remote.md:59–86, 313–343, 412–501](docs/design/dao.stream.remote.md)

`dao.jing.content` is clearly a planned successor with new entry points and a migration slice; the referenced coordinate and ingress API are identified as target work, not existing implementations. Companion framing edits are consistent. The stale `docs/dao.space.stigmergy.md` instruction remains the exception. [remote.md:623–653](docs/design/dao.stream.remote.md) [implementation-plan.md:34–54, 89–93](docs/design/dao.stream.remote.implementation-plan.md)

Verdict: REQUEST CHANGES