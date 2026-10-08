Completed-GMT: 2026-09-26 17:08:04 GMT
Completed-Local: 2026-09-27 00:08:04 +07
Coding-Agent: deepseek
Session-ID: 4db87028-780f-417e-9b6d-f579716f86a3

# Architect deliverable — dao.stream.serve section 15 decisions under the owner invariant

## 1. INVARIANT FEASIBILITY

The invariant has four clauses. Each is assessed against the spec (cited) and against the physics of NAT.

| Clause | Verdict |
|---|---|
| "any implementation of dao.stream can mechanically be exposed via websocket or udp" | **Achievable with one caveat.** The derivation is over the surface, not the transport (serve.md:193-196, 3.3). The caveat is the spec's own precondition: cursors and outcome maps must lie in the channel's portable domain; a transport whose cursor holds a host object cannot be served and the exporter refuses it (serve.md:257-262, 13). "Any" is therefore "any whose cursor is plain data," which is exactly OD-3(a)/(2). |
| "communicate p2p … no concept of server or client" | **Achievable.** The frame set names no peer kind (serve.md:162-166); roles are per-session, not per-peer (serve.md:122-126, 3.1); two peers each serve the other over one channel (serve.md:819-824, 7.5). |
| "client-server is just one stigmergic behavior interpreters implement" | **Achievable.** Meeting (7.3) and service (8) conventions are named as conventions, no protocol frame for them (serve.md:703-706, 860-863). |
| "able to traverse NAT" | **Achievable with a stated caveat — and one hard impossibility.** See below. |

**NAT analysis (concrete).**

UDP direct P2P by hole punching (serve.md:795-815, 7.4):

| NAT type | Direct UDP P2P | Via meeting peer M (relay) |
|---|---|---|
| Full cone | Yes — any outsider may send once mapping exists | Yes |
| Restricted cone | Yes — needs same-socket outbound probe; the spec's same-socket rule exists for exactly this (serve.md:812-814, 6.3) | Yes |
| Port-restricted cone | Yes — both probe the other's exact reflexive `ip:port` | Yes |
| Symmetric NAT | **No** — fresh port per destination defeats punching; spec falls to inbox pair and reports it as a failed candidate (serve.md:809-812) | Yes |
| CGNAT (nested/hairpin) | Usually no — stacked mappings, no inbound | Yes |
| UDP-blocking network | **No UDP at all** | Yes (WebSocket/TCP) |

WebSocket (TCP over HTTP): there is no practical TCP hole punch. A browser **cannot accept/listen** (serve.md:570-571, 6.2; 912-916, 9). So direct browser↔browser WebSocket P2P is **impossible**; a browser is reachable only through a peer that can accept — the meeting peer M over WebSocket (serve.md:617-619, 6.3), or WebRTC DataChannel later (deferred, serve.md:909, 12).

**The honesty the owner must be told.** Direct P2P traversal is *not* universal. Two cases are physically impossible without a third peer: (1) symmetric NAT and most CGNAT for UDP, and (2) any two peers where neither can accept (two browsers) for WebSocket. In both, a **relay peer** is required. The protocol keeps "no privileged node" honest — M runs the ordinary `serve-step` over two ring buffers and no frame names it (serve.md:621-629, 766-769, 6.4/7.3), and any peer may run it — but a relay is a **required third party** for those cases, and its availability is a route dependency. This does not reintroduce a *privileged* server (no special role, no authority), but it does mean the literal phrase "traverse NAT" is "traverse NAT where physics permits, otherwise relay through a peer running a convention." That is a property of the network, correctly reported as a failed candidate (serve.md:812-814), not a protocol defect. The owner's "if my invariants are technically impossible, let me know" is answered: **direct P2P through symmetric NAT / CGNAT / between two accept-incapable peers is impossible; a relay is the only honest answer, and the relay is a convention, not a server.**

The dialer/acceptor asymmetry is acceptable per owner Q2: establishment direction is a fact, not authority (serve.md:560-562, 6.2; 701-702, 7.2).

## 2. TOY EXAMPLE WALKTHROUGH (string handle, WS, remote read)

The spec supports this **mechanically, with no "string" special case**. Trace:

1. Holder has a string-backed handle `h` (surface `#{:reader}`), cursors plain data (`{identity … position n}`). Precondition met (serve.md:257-262).
2. Exporter adds `identity → {handle, surface}` to its serve table and writes a serve descriptor: `{:dao.stream/type :dao.stream/serve :dao.stream/identity <string-id> :serve/peer <peer> :serve/surface #{:reader} :serve/candidates [<ws channel descriptor>]}` (serve.md:481-498, 7.1, 13 Lift).
3. Remote interpreter `attach!` → proxy handle (serve.md:370-376, 4).
4. Proxy opens a WebSocket channel to the acceptor address and sends `:serve/open` (serve.md:138-139, 3.2). Serving side answers `:serve/opened` with the attachment, declared surface, and `:oldest`/`:newest` anchors (serve.md:139-142, 3.6).
5. Remote calls `cursor` → `ok` with last observed anchor (or `transport-error` `:retry? true` before `:served/opened`) (serve.md:395-397, 3.6).
6. Remote calls `next` → proxy records demand, answers `blocked`; `proxy-step` issues `:serve/request` `:dao.stream/next [cursor budget]` (serve.md:383-388, 3.3). Serving side computes `next` from the string source and returns outcome maps **verbatim**, cursors included (serve.md:205-209, 224-226).
7. Remote observes the string value as `ok` with the **source's own successor cursor** — not a copy with new positions (serve.md:35-38). `gap`/`end` arrive verbatim (serve.md:441-443).

Only the derivation table runs, over the surface, "a string-backed stream, a ring buffer and a durable log are served by the same five entries" (serve.md:193-196). **No step fails.** The only step that *could* fail is if the cursor weren't plain data (serve.md:257-262), which a string identity + integer position satisfies by construction.

## 3. THE EIGHT DECISIONS

**D1 — Held reads (`:serve/hold`).** DECISION: accept — optional, additive v1, off by default, per-deployment, never a condition of reachability/acceptance. WHY: reads are idempotent by cursor; `hold` is a latency optimization only, and `no operation waits` forbids the serving side from blocking (serve.md:350-367). RISK: read as "never implement," inbound-delivery latency through a meeting peer is bounded by poll cadence and a mis-tuned poller burns round trips. INVARIANT-CRITICAL? **No.**

**D2 — Accept OD-1/2/3 + two 4.1 definitions.** DECISION: accept as drafted — OD-3(a)+(2), OD-2, OD-1 with write fallback "effect unknown; no automatic retry", plus the `blocked`/anchors definitions and the deferred-remote-observation declaration. WHY: OD-3(a) is the whole basis of "served stream is the original, not a copy"; OD-2 makes append-over-UDP honest; OD-1's `:retry?` distinguishes browser-proxy `blocked` from failure. RISK: highest-dependency decision — the design is "not implementable without OD-3 and dishonest without OD-2" (serve.md:1102-1103); a later reversal toward OD-3(b) breaks the proxy and the single cursor profile. INVARIANT-CRITICAL? **Yes** — OD-3(a) is the line between "as itself" and "as a copy."

**D3 — Lifetime of served entry / inbox pair.** DECISION: accept — lease-governed via `dao.lease.md`, composition retirement as a weaker pre-grant stopgap, migration acceptance pins both. WHY: without it, exporter table entries and M's pairs leak or are retired under a migrating task; the lease adds no operation (dao.lease.md:8-10). RISK: `dao.lease.md` is "proposed design target," not implemented; pinning is a stopgap every exporter/resumer/M must honor or a resumer gets `not-found` (serve.md:1016-1017). INVARIANT-CRITICAL? **No** — liveness detail, not stream identity.

**D4 — Rename `dao.stream.serving`.** DECISION: accept — this layer is `dao.stream.serve`; the forwarder becomes `serving.copy`/broadcast, docstring says client/server convention. WHY: keeps the copy model from being mistaken for the original-stream model section 1 removes. RISK: call sites/docs must be updated or they silently keep the copy model. INVARIANT-CRITICAL? **No** (naming), though it serves the "convention, not transport" clarity.

**D5 — Admission to a meeting/service door.** DECISION: accept — composition policy, open in trusted deployments, 7.3 bounds always; postage/capability is the intended public-door answer, an interpreter rule not a frame. WHY: the descriptor carries no authorization (dao.stream.md:286-292) and the contract gates nothing. RISK: open-by-default public door is a footgun unless the owner writes policy; refusal is observable so it's bounded. INVARIANT-CRITICAL? **No** — authorization is out of contract scope.

**D6 — Peer id format.** DECISION: accept — self-minted ≥128 random bits now, pk-hash as intended, verification additive. WHY: ids need only unauthenticated uniqueness for session/meeting correlation (serve.md:732-738); pk-hash is discovery.md mechanism 1. RISK: relying on peer-id for trust before key-control exists is a false sense of security (serve.md:737-738). INVARIANT-CRITICAL? **No.**

**D7 — Append through a proxy.** DECISION: accept — keep outbound-path `ok`, source outcome on the event medium (`:serve/appended`), amend UCF put-resume; do not hold-and-retry at the proxy. WHY: `no operation waits`; holding/retrying would duplicate under OD-2's unknown state. RISK: the UCF put-resume amendment (serve.md:1000-1004) is required, or a `:put` wait discharges on outbound `ok` before the source has the value. INVARIANT-CRITICAL? **Yes** — it is OD-2's "no silent retry" honesty applied to writes.

**D8 — Retire `dao.jing.remote`'s transport half.** DECISION: accept — retire WS descriptor, `connect-content!`, `serve-content!`, composition; keep `remote.step`; unify the ingress check. WHY: the service door (serve.md:846-858) replaces the transport; the stepped client is OD-5's portable interface. RISK: must be sequenced after the door exists — `yin.repl` and linker M3/M4 depend on the JVM blocking driver over `connect-content!` today (remote.cljc:730, 932; linker.md:1958-1960). INVARIANT-CRITICAL? **No** — superseded-transport cleanup.

## 4. CROSS-DECISION CONFLICTS

- **D2 is the load-bearing gate**, not a conflict: every other decision assumes OD-1/2/3 and the two 4.1 definitions (serve.md:956-962, 12).
- **D7 depends on D2** (OD-2). Reject OD-2 and D7's "no silent retry / effect unknown" is dishonest.
- **D8 depends on D5** (the door must exist) and on D2/D7; it must be sequenced last.
- **D3 depends on `dao.lease.md` being implemented.** Its "pinning stopgap" obligation spans exporter + resumer + M; section 16 describes the `dao.lease.md` carriage edit but does not name a specific edit pinning the enforcement point. Minor: pinning's enforcement point is left to the UCF facade acceptance matrix (serve.md:1150-1152, 16), which *is* listed.
- No two decisions directly contradict.

## 5. VERDICT

**READY AFTER the doc edits in section 16 are applied.**

Specific blocker: the contract amendments in D2 have not been accepted — OD-1 (corrected write fallback), OD-2, OD-3(a)+(2), and the two section-4.1 definitions (`blocked`; anchors) — and the design is not implementable without OD-3 and dishonest without OD-2 (serve.md:1102-1103, 956-962). The spec itself is internally consistent, mechanically supports the toy example with no string special case, and honors the owner invariant with the single honest caveat that direct P2P through symmetric NAT/CGNAT/between accept-incapable peers requires a relay peer, which the protocol correctly models as an unprivileged convention, not a server.
