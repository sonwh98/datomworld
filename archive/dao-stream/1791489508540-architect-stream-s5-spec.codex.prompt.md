Created-GMT: 2026-10-08 19:58:30 GMT
Created-Local: 2026-10-09 02:58:30 ICT
Coding-Agent: codex
Session-ID: pending

# Task: Track B Slice S5 (Multi-Hop Routing, Peer Addressing & Identity Discovery)
Role: Lead System Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-09 02:58:30 ICT | Status: active | Rationale: Lead System Architect for Track B Slice S5 specification

You are the Lead System Architect for the datom.world project.
Define the architectural specification and implementation recipe for Track B Slice S5 in `/Users/sto/workspace/datomworld-stream-s5`.

## Context & Authorities
- Master architecture: `docs/design/dao.stream.remote.md` (§2 Wire shapes, §3 Channels, §3.3 Pair, §4 Reachability and NAT, §5 Conventions)
- S4 implementation and sign-off: `archive/stream-crossmachine-s4/` (`1791403500000-architect-stream-s4-spec.claude-fable-5-1.findings.md`, `1791405000000-architect-stream-s4-signoff.codex.findings.md`)
- Existing remote channel & pair: `src/cljc/dao/stream/remote_channel.cljc`, `src/cljc/dao/stream/remote_pair.cljc`, `src/cljc/dao/stream/remote_meet.cljc`

## Scope of Slice S5 (Multi-Hop Routing & Identity Discovery)
1. Multi-hop peer addressing & conventions:
   - Addressing peer identities across intermediate meeting / relay peers without introducing global server/client privilege.
   - Stream pair composition over relay channels (`dao.stream.remote-pair`) and meeting convention (`dao.stream.remote-meet`).
2. Discovery & Name resolution:
   - Connecting via self-certifying hash / peer names or meet boards.
   - Peer lifecycle, keep-alive cadence, and failure isolation under NAT traverse / relay.
3. Production bounds & invariants:
   - Strict driver-paced execution (no background clocks or ambient threads).
   - Resource limits (message bytes, session bounds, lease expiration).
4. Concrete acceptance criteria, test plan, and multi-host matrix.

Write your specification and implementation recipe to:
`collab/1791489508540-architect-stream-s5-spec.gpt-6-astra.findings.md`
