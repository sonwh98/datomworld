Created-GMT: 2026-09-25 11:32:00 GMT
Created-Local: 2026-09-25 18:32:00 +0700
Coding-Agent: claude
Session-ID: 05ce85cc-7cbb-407f-b445-1e9756ad2e35

# Task: dao.stream network-serving spec (design authorship)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-25 18:32 +0700 | Status: active | Rationale: owner directive to use Fable as Architect

Design a transport-neutral specification for mechanically making any
dao.stream implementation network-accessible over WebSocket and UDP,
including behind NAT and for P2P use. Read-only: do not edit files. Return
the complete design, including draft spec text ready to become a docs/design
file, in your final response (it is promoted to .findings.md).

## Owner requirements (verbatim quotes; kept separate from the orchestrator's framing)

1. "dao.stream is an abstraction boundary. it does not assume a network. a
   string can have a dao.stream interface. dao.stream that are not network
   accessible needs to be network accessible. for a UCF to work well it
   needs a way to mechanically make any dao.stream implementation
   accessible when a continuation is migrated and resume on a network node"
2. "there should be a dao.stream spec on how to mechanically make any
   dao.stream implementation available over websocket or UDP"
3. "this spec must also have a solution to work behind a NAT"
4. "the spec should allow P2P use cases via websocket and UDP behind NAT too"

## Orchestrator framing (my reading, not the owner's words; challenge it)

- The UCF spec (§7.4.3, §7.5.3) already promises a "standard
  stream-over-network facade derived from the dao.stream operation and
  outcome contract" but does not define it. The requested spec is what that
  section should cite.
- dao.stream.ws.md already states "serving is what makes any stream
  remotely attachable" but assumes the serving host accepts inbound
  connections, which fails for a NATed source and, in P2P, for both peers.
- The mechanism choice (outbound-only serving, relay/rendezvous,
  hole-punching, peer-symmetric serving, and how UDP reliability
  reconciles with kept cursors and gap) is deliberately left to you. No
  mechanism is pre-selected.

## Read first
- docs/design/datom.world.md
- docs/design/dao.stream.md (the contract; it wins over any transport doc)
- docs/design/dao.stream.ws.md
- docs/design/daostream-udp-design.md (v1, implementation deleted; predates
  the v2 contract; treat as prior art, not authority; its line ~470 lists
  NAT traversal as a non-goal)
- docs/design/dao.stream.discovery.md (rendezvous topics)
- docs/design/yin.vm.universal-continuation-format.md (§7.4.3, §7.5.3)
- src/cljc/dao/stream/serving.cljc, forward.cljc, ws.cljc, rpc.cljc

## Constraints
- Preserve: transport-independent logical-stream identity, exhaustive
  outcomes, kept cursors, and gap semantics across the hop.
- Reachability lives in the transport-owned descriptor, not the contract.
- Honor the six invariants (no hidden global state, implicit control flow,
  raw callbacks, shared mutable state, layer collapsing, assumed graphs).
- Failure surfaces as existing outcomes (UCF: :yin.k/unsatisfied); no
  silent new failure modes.
- CLJ/CLJS/CLJD portability; state which parts are host-specific (e.g.
  raw UDP sockets are unavailable in browsers).
- No backward compatibility is needed (dev-only repo).
- Distinguish what the contract requires, what is transport-owned, and
  what is deliberately deferred. Say where your design contradicts an
  existing doc and which side should change.

Evaluate the foundational invariants, ownership boundaries, explicit state
and control flow, concurrency and linearization, host isolation, migration
risk, and completion criteria for the design.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: the design (draft spec text), the open questions that need an
owner decision, and any contradictions found, as
severity | file:line | evidence | recommended correction.
