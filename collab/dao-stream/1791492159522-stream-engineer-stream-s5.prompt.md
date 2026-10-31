Created-GMT: 2026-10-08 20:42:39 GMT
Created-Local: 2026-10-09 03:42:39 ICT
Coding-Agent: codex
Session-ID: pending

# Task: Track B Slice S5 Implementation (Multi-Hop Routing, Peer Addressing & Identity Discovery)
Role: DaoStream and Distributed Protocol Engineer
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-09 03:42:39 ICT | Status: active | Rationale: Stream engineer for Track B Slice S5 implementation

You are the DaoStream and Distributed Protocol Engineer implementing Track B Slice S5 in `/Users/sto/workspace/datomworld-stream-s5`.

## Context & Authorities
- S5 Architecture Specification (MUST READ FIRST): `collab/1791489508540-architect-stream-s5-spec.gpt-6-astra.findings.md`
- Master design contracts: `docs/design/dao.stream.remote.md` (§§2–6), `docs/design/dao.stream.md`, `docs/design/datom.world.md`
- S4 baseline & sign-off: `archive/stream-crossmachine-s4/1791403500000-architect-stream-s4-spec.claude-fable-5-1.findings.md`, `archive/stream-crossmachine-s4/1791405000000-architect-stream-s4-signoff.codex.findings.md`
- Codebase files: `src/cljc/dao/stream/remote_channel.cljc`, `src/cljc/dao/stream/remote_pair.cljc`, `src/cljc/dao/stream/remote_meet.cljc`, `src/cljc/dao/stream/remote.cljc`, `test/dao/stream/remote_pair_test.cljc`, `test/dao/stream/remote_meet_test.cljc`

## Scope & Deliverables
1. Follow the implementation steps and decisions detailed in `collab/1791489508540-architect-stream-s5-spec.gpt-6-astra.findings.md`:
   - Symmetric pair stepping and cleanup in `remote_pair.cljc`: forward bounds, rollback on failed second attach, bounded cache.
   - Dynamic table & routable descriptors in `remote_meet.cljc`: publish actual reachability through the granting board's channel; caller-supplied fresh incarnation in pair/renewal/lease identities; close owned resources and unwire renewal medium upon reclaim.
   - Multi-hop peer addressing & resolution: two relay peers in one operational path; candidate derivation and observation deadlines.
2. Complete acceptance criteria A1 through A10 from §10 of the S5 specification:
   - Prove identity and descriptor parity through direct, 1-relay, and 2-relay paths.
   - Fair bounded nested work, isolated failure, and no append replay.
3. Verification:
   - Run focused JVM tests (`clojure -M:test -n ...`) and multi-host test suites (`bb test:clj`, `bb test:cljs`).
   - Run `clj -M:kondo` and `git diff --check`.

Report your changed files, exact test outcomes, assertion counts, and findings in:
`collab/1791492159522-stream-engineer-stream-s5.gpt-6.1-sol.findings.md`
