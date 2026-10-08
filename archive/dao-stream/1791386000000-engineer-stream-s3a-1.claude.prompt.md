You are the Lead Implementation Engineer for Track B Slice S3a-1.
Work in /Users/sto/workspace/datomworld-stream-s3a (branch stream-crossmachine-s3a).

Read your architectural specification and recipe firsthand:
`collab/1791384000000-architect-stream-s3a-spec.claude-fable-5-1.findings.md`

Your scope for Sub-slice S3a-1 (Commit 1 per §8 of the specification):
1. `src/cljc/dao/stream/ws.cljc`:
   - Implement `(endpoint-stop! endpoint)` per §4.1:
     Closes every pending pre-acknowledgement connection owned by the endpoint (sets phase `:closed`, `invoke-close!` 1001 `"dao.stream/endpoint-stopped"`, `terminal!` `:ws/closed` on the control medium, `release-slot!`). Idempotent. Answers endpoint.
2. `src/cljc/dao/stream/ws_project.cljc`:
   - Implement `(stop! acceptor)` per §4.2: marks `:stopping? true`. `adopt!` rejects every subsequent offer (handle closed, no ack, no media).
   - Implement `(close-sessions! acceptor)` per §4.2: runs `close-session-resources!` on every session and marks each `:closed?`.
3. New `src/cljc/dao/stream/remote_channel.cljc` per §1, §2.1, §4.3, §4.4, §5.1:
   - `production-bounds` map containing the exact 17 keys from §1 table.
   - `(descriptor-of spec)` formatting the concrete `:ws/...` descriptor.
   - `(loopback-literal? host-string)`.
   - `(serve {:spec spec :host host :table t :names n :bounds b})`.
   - `(serve-step server now)`.
   - `(stop! server)`.
   - `(sessions server)`.
   - `(dial {:spec spec :host host :name n :bounds b :events w})`.
   - `(dial-step d now)`.
   - `(handle d)`.
   - `(close! d)`.
4. Fixtures and Tests:
   - Move in-process loopback net fixtures from `test/yin/vm/linker/head_ws_test.cljc` to `test/dao/stream/loopback_net.cljc` (keep `loopback-net`, `enqueue!`, `pump!`, `listen-on`, `connect-on`, `close-conn!`, `unlisten!`; add `blackhole!` and `flood!` per §7).
   - Create `test/dao/stream/remote_channel_test.cljc` implementing all 15 test cases specified in §7.
   - Add endpoint-stop test to `test/dao/stream/ws_test.cljc` (§7).
   - Add acceptor stop tests to `test/dao/stream/ws_project_test.cljc` (§7).
5. Design documentation updates per §6:
   - Amend `docs/design/dao.stream.remote.md` §3.0 and §3.1.
   - Amend `docs/design/dao.stream.ws.md` Serving and Deferred sections.

Run tests:
- `clojure -M:test -n dao.stream.remote-channel-test -n dao.stream.ws-test -n dao.stream.ws-project-test`
- `clj -M:kondo --lint src/cljc/dao/stream/ remote_channel.cljc ws.cljc ws_project.cljc`
- Full JVM suite: `bb test:clj`

Write your completion report to `collab/1791386000000-engineer-stream-s3a-1.claude-opus-5-5.findings.md`.
