Created-GMT: 2026-10-08 05:45:00 GMT
Created-Local: 2026-10-08 12:45:00 ICT

# Task: Track B Slice S4 Architectural Specification

Role: Lead System Architect
Model: Claude 3.5 Sonnet / Fable 5.1 (claude-fable-5-1)
Repository root: /Users/sto/workspace/datomworld-stream-s3a
Branch: stream-crossmachine-s4 (based on master @ 66756d20)

Context & Background:
- Master Architecture: docs/design/datom.world.md
- Cross-Machine Stream Architecture: docs/design/dao.stream.remote.md
- WebSocket Transport: docs/design/dao.stream.ws.md
- Slice S3b Sign-Off Artifacts:
  archive/1791398000000-architect-stream-s3b-spec.claude-fable-5-1.findings.md (see §6 "Deferred, with reasons")
  archive/1791400500000-architect-stream-s3b-signoff.codex.findings.md
- Related files:
  src/cljc/dao/stream/remote_channel.cljc
  src/cljc/dao/stream/ws_project.cljc
  src/cljc/yin/repl/connect.cljc
  src/cljc/yin/repl/serve.cljc
  src/cljc/yin/repl/dht.cljc

Your architectural mission for Slice S4 ("Beyond loopback, terminal causes, and ephemeral listeners"):
1. Terminal Cause Propagation Above the Projection:
   - In `ws-project.cljc`, record which event caused ring closure (`:ws/ended`, `:ws/closed`, `:ws/not-found`, `:ws/transport-error`).
   - In `remote-channel.cljc`, map event outcomes to neutral causes (`:ended`, `:dropped`, `:not-served`, `:unreachable`, `:expired`) on the lost dial.
   - In `yin.repl.connect`, refine an RPC `:detached` to `:ended` when the source ended (code 4000) or to `:transport-error` when the connection never opened or host was unreachable.
2. Ephemeral Port Binding (`--port 0`):
   - Support ephemeral port binds (`--port 0`): compose or finalize the descriptor after `:bind-succeeded` event supplies the allocated port from the host seam.
   - Update `remote-channel/serve` to handle dynamic descriptor formatting upon successful bind.
   - Update `yin.repl.serve` to permit `--port 0` and update advertised URL dynamically once bound.
3. Clean process-exit path:
   - Address single-tick exit in `yin.repl.dht/close!`.
4. Structure the brief with:
   - §0 Decisions table
   - §1 Foundations, invariants, and layering (no transport leakage, driver-paced)
   - §2 Semantics of changes (projection cause recording, neutral cause mapping, ephemeral descriptor composition)
   - §3 Step-by-step implementation instructions for Implementation Engineer (Claude Opus 5.5)
   - §4 Acceptance criteria & test obligations across JVM, Node, Dart
   - §5 Rules, judgement calls permitted / prohibited
   - §6 Deferred items

Format requirements:
- No em dashes (—), no first-person pronouns, follow docs/agents/format.md.

Write your complete specification to:
collab/1791403500000-architect-stream-s4-spec.claude-fable-5-1.findings.md
