You are the Lead System Architect for the datom.world project.
Produce the formal architectural specification and implementation brief for Track B Slice S3b (Remote REPL over `dao.stream.remote-channel`).

Context:
- Repository root: /Users/sto/workspace/datomworld-stream-s3a
- Branch: stream-crossmachine-s3b (based on master @ 802d9ee2)
- Reference architecture:
  - `docs/design/dao.stream.remote.md` (§3.0, §3.1, §5.1, §5.2)
  - `docs/design/dao.stream.ws.md`
  - `docs/design/yin.vm.linker.dht.head.md`
  - Landed in S3a: `src/cljc/dao/stream/remote_channel.cljc`, `src/cljc/yin/vm/linker/head/board.cljc`
  - S3b target files: `src/cljc/yin/repl/serve.cljc`, `src/cljc/yin/repl/connect.cljc`, `src/cljc/dao/stream/remote_channel.cljc`, `test/yin/repl/connect_test.cljc`, `test/yin/repl/serve_test.cljc`.

Scope of Slice S3b:
1. `dao.stream.remote-channel` extensions:
   - Serving bidirectional tables with writer surfaces (`#{:reader :writer}` or table with both request writer and answer reader).
   - Writable REPL append ambiguity resolution.
   - Ended-media stop grace for graceful connection draining.
2. `yin.repl.serve` migration:
   - Migrate server-side driver from raw `ws-project`/`ws` plumbing to `dao.stream.remote-channel/serve` and `serve-step`.
   - Maintain lifecycle observation, shared evaluation shell, and RPC protocol parity over the neutral stepped channel.
3. `yin.repl.connect` migration:
   - Migrate client-side connector to `dao.stream.remote-channel/dial` and `dial-step`.
   - Reconnection semantics, RPC rebind, terminal reason observation (`:detached`, `:ended`, `:not-found`, `:transport-error`).
4. Invariants & Boundaries:
   - Eliminate direct `:ws/*` and `ws-project` couplings from `yin.repl.serve` and `yin.repl.connect` above the `remote-channel` boundary.

Provide a complete, rigorous architectural specification and implementation brief covering:
- §1: Foundations, Invariants & Layering
- §2: Semantics of `remote-channel` additions (writer-surface table entries, ended-media stop grace, writable append)
- §3: Concrete migration plans for `yin.repl.serve` and `yin.repl.connect`
- §4: Acceptance criteria & test obligations across JVM, Node, and Dart
- §5: Concrete instructions for the Implementation Engineer (Claude Opus 5.5)

Write your specification to:
`collab/1791398000000-architect-stream-s3b-spec.claude-fable-5-1.findings.md`
