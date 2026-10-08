Completed-GMT: 2026-10-06 16:39:39 GMT
Completed-Local: 2026-10-06 23:39:39 Asia/Ho_Chi_Minh
Coding-Agent: codex (gpt-6-astra)
Session-ID: 59f057b9-5337-48da-ae6e-5fa9462d466b

# Architect mob: transport boundary, resource bounds, and cross-machine milestone

Architecture recommendation complete; implementation is not complete or signed off. Source was inspected, not edited; no runtime tests were run. Only task coordination artifacts were written. Existing unrelated working-tree changes were preserved.

## Consensus and provenance

Astra coordinated actual CLI reviews with Claude Fable (resumed session 642b5698-f626-4028-9068-0704d093d269) and DeepSeek V4 Pro (new session 0ab62ae7-5a5e-4caa-bc7b-7b09455efb3e). The DeepSeek session named in the incoming brief was not resumed; this is a separate review session. Fable received three rounds, DeepSeek two. Every invocation completed successfully. Findings files and prompts share this report's 1791304310868 prefix.

Review reports contain self-reported timestamps, including approximate/future timestamps; those are preserved as received and are not authoritative chronology. The coordinator's timestamp above was read from the actual clock. DeepSeek's round-one findings were copied from its plan artifact by the coordinator; no approval or source editing was required.

| Question | Initial difference | Evidence and reconciliation | Final position |
|---|---|---|---|
| Liveness ownership | Original Fable recommendation watched ws-project cursor in yin; DeepSeek R1 proposed a new health accessor watched by yin | Raw traffic is not correlated completion; both leak transport supervision upward. DeepSeek R2 retracts accessor; Fable R1 retracts prior placement | dao.stream supervisor expires channel; yin decides repair from ordinary loss |
| Session admission | Original Fable recommended evicting idlest to admit new | Fresh clients could churn healthy readers. Reject alone leaves dead sessions occupying cap | Reject at cap after reaping expired sessions; mandatory idle expiry |
| Outbound memory | Fable R1 would defer hard bound | Active non-reading peer never becomes idle; bounded rate still accumulates unlimited output | Actual byte bound plus effective teardown before gate; Fable R2 agrees |
| REPL migration | Fable R1 deferred REPL and kept descriptor builder in yin | Owner explicitly names REPL and linker; :ws descriptor construction still couples them | Move board and REPL plumbing; Fable R2/R3 and DeepSeek R2 agree |
| Timeout counter | Fable proposed reusing :asks, then waiting for drain-to-blocked | Retry resets :asks; observers inflate calls; continuous irrelevant traffic prevents blocked | Fixed per-request deadline from supplied now, bounded queued-response processing/grace |
| Wildcard advertisement | Original Fable required explicit bind literal | Common single-interface case can be resolved as host data | Auto-select unambiguous eligible address; explicit override for ambiguity/topology |

Astra accepts the reconciled positions. Fable R3 has no remaining architectural objection. DeepSeek R2 accepts all four reconciliation items and the D4 clarification. Numerical profiles remain implementation choices, not owner questions.

## D1 — Boundary

Generalize the generic portion of yin.vm.linker.head.ws below dao.stream into a stepped remote-channel composition. A neutral yin board composition retains only board naming, its read-only exposure table, and domain repair. The endpoint specification is portable data passed opaquely; dao.stream host assembly selects the transport and parses/formats its concrete descriptor.

Completed D1 also migrates yin.repl.serve, yin.repl.connect and transport-specific yin.repl.host glue. Renaming head.ws alone is insufficient. Existing explicit descriptor dispatch stays composition-owned: no ambient registry, new core stream operation, public closed? predicate, application callbacks, autonomous clocks or background retry loops. Effects/lifecycle results remain ordinary streams; the explicit driver owns transport state and cadence.

Evidence: head/ws.cljc constructs :ws descriptors and passes socket seams; repl/serve.cljc:216,281,288 constructs ws endpoints; repl/connect.cljc:30-31 imports ws and ws-project; repl/dht.cljc:715-750 drives and times out ws dialing. head.cljc's reader surface is already transport-independent.

## D2 — TCP now

Use WebSocket over TCP for this milestone. Existing UDP channel code does not supply the safe board-on-DHT-socket exposure: return-path proof before fragmentation/reassembly and amplification accounting remain work.

Pin unchanged handle operations/outcome sets, source identity and opaque cursors, name-to-identity resolution, declared surfaces, source gap/recovery semantics, correlation, append acceptance and append-unknown, local close, and generic loss. A network drop must not be invented as a source-retention gap. TCP reliability, descriptor fields, fragmentation and retries remain below the boundary.

Zero breakage means unchanged REPL/linker source and stream contract, not identical latency, delivery, value-size limits or unconditional exactly-once. Existing remote section 2.5 assigns append deduplication/correlation to payloads and never automatically resends append!. Writable REPL UDP must pass its declared failure semantics, including duplicate/lost traffic, rather than inherit assumptions from TCP. Swapping only a raw socket adapter is not a parity proof.

## D3 — Resource and liveness bounds

Not all fixes live in only ws-project and remote. Policy is composition data; enforcement follows ownership:

- Generic channel composition / ws-project: finite admission cap, idle/pending expiry, fair aggregate scheduling, per-session isolation, explicit stop, lifecycle loss handling. Reject newcomers at the active cap after reaping expired sessions. Bound pending and retiring resources too; release bookkeeping must not depend on a callback.
- remote: budget mirror-step, drain!, retry scans, chase/more expansion and retained outstanding/filed state. Clamp peer-requested work to local allowance. Count malformed reads/gaps too. Budget exhaustion yields preserved continuation state, not end or false progress.
- ws and host adapters: bound pre-adoption frames by count and bytes, inbound frame/reassembly/decode allocation and outbound queued bytes. A conservative cumulative encoded-byte quota with effective hard teardown is a fallback where completion accounting is unavailable. Rate plus idle timeout is not sufficient. Repeated admissions cannot evade the bound through accumulated closing sockets. A host without enforceable bounds stays gated.

Liveness supervision consumes correlated response progress inside dao.stream, with explicit supplied monotonic now and a per-request deadline fixed at first accepted send. No new head value is necessary: a correlated blocked answer completes the request. Local blocked, unrelated payloads, retries and arbitrary poll calls prove nothing. Do not expose ws-project/reading-cursor to yin or time out the board's own cursor.

Process a bounded snapshot of queued replies or allow bounded scheduling grace before expiry; never wait indefinitely for drain-to-blocked. Flood can cause a false channel loss; that costs repair, never invalidation of the installed head. Expire through existing channel-gone/transport-error semantics; yin observes source-lost and decides redial/backoff. Server idle expiry is separate, since a server may have no outstanding request.

Correct :answered reporting: local blocked cannot be described as remote acknowledgement. Keep poll status separate from actual protocol response facts; do not add transport-health inspection to the follower. This is attachment supervision, not a freshness deadline, withholding detector, fetch deadline or replacement for dao.lease.

## D4 — Address advertisement

Explicit advertised endpoint wins. Otherwise advertise a concrete bind address. For a wildcard, obtain host interface candidates through effect/result streams and select a unique eligible address of the correct family; a route hint toward an actual configured peer can refine the choice. Do not require a connected-UDP probe or external IP-discovery service.

If zero/multiple eligible candidates remain, keep the node usable but do not print a misleading join token; expose one advertised-address override. Automatic LAN selection is not proof of NAT/public reachability. The banner says advertising. Never publish 0.0.0.0 or ::. Bracket IPv6 only in endpoint textual formatting; preserve the representation required by each host API.

The join token keeps principal/domain meaning in yin, while concrete stream endpoint encoding belongs below dao.stream. Publish only after the required listeners are confirmed bound. The current shared host/port convention needs both transports reachable; divergent NAT mappings need a richer endpoint configuration and cannot be guessed. Address selection is not embedded in signed traces or heads.edn.

## D5 — Implementation slices and gates

S0: compact contract amendment, dependency boundary and conformance cases. No new registry or core operation.

S1: admission/session/pending/idle bounds, rejection policy, fair scheduling and session failure isolation.

S2: all nested remote/projection loops and retained state; pre-adoption/inbound/outbound byte bounds and effective abort. May split into smaller commits.

S3a: neutral board attachment/acceptance, stream-side liveness, explicit stop and lifecycle-gap recovery or terminal failure.

S3b: migrate REPL serve/connect/host to the same boundary. S3a alone does not complete D1.

S4: automatic advertisement and override, IPv6 formatting, validated bind/token lifecycle, all-host verification, design amendments, then loopback gate lift. S4 milestone sign-off waits for both S3a and S3b. Each host remains gated until its bounds and teardown are demonstrated.

Required acceptance evidence: unchanged consumers exercised over local paired streams and WebSocket plus lossy/reordered channel simulation; idle healthy board versus blackholed request; unrelated-traffic flood cannot defer expiry; continuous producer cannot defeat step budget; request-but-never-read peer cannot grow memory without bound; malformed/oversize traffic, session cap and expiry, missing close callback, restart identity/cursor mismatch, lifecycle gap, IPv6 and real JVM/Node/Dart sockets. Writable REPL append ambiguity is tested separately. Run focused checks and full required three-host tests when implementation lands. None were executed for this architecture review.

Amend head design sections 5.1, 6, 8.1-8.4, 10 and 13, and relevant stream composition contracts. Remove claims that existing bounds hold, half-opens necessarily produce retryable errors, and transport-aware yin is the final boundary. Record TCP choice as settled by this directive. Preserve separate read-only board and evaluation endpoints, signed trace validation, durable floor, source-independent persistence, and the existing no-freshness/no-progress guarantee.

Remaining risks: cleartext first-contact replay and withholding, no guaranteed progress under hostile signed-head alternation, NAT reachability, host-specific byte enforcement, and false channel loss under overload. These are stated limits or implementation gates, not evidence that any head safety rule changed.
