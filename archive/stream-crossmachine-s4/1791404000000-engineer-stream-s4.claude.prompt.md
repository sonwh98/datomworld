Created-GMT: 2026-10-08 05:58:00 GMT
Created-Local: 2026-10-08 12:58:00 ICT

You are the Implementation Engineer for Track B Slice S4: "Beyond loopback, terminal causes, and ephemeral listeners".
Model: Claude Opus 5.5.
Repository root: /Users/sto/workspace/datomworld-stream-s3a
Branch: stream-crossmachine-s4 (based on master @ 66756d20)

Read carefully the Lead System Architect's specification and follow all instructions verbatim:
`collab/1791403500000-architect-stream-s4-spec.claude-fable-5-1.findings.md`

Follow §3 "Instructions for the Implementation Engineer" step by step:
- Step 1: Tests first (red phase: additions to `loopback_net_test`, `remote_channel_test`, `connect_test`, `serve_test`, `dht_head_test`, `main_test`).
- Step 2: `dao.stream.ws`: D5 (`servable-descriptor?`, `make-endpoint`, `endpoint-bound!`).
- Step 3: `dao.stream.ws-project`: D1 (`cause`, `opened?`, recording in projection).
- Step 4: `dao.stream.remote-channel`: D2 (cause mapping, `opened?`, `cause` accessor) and D4 (ephemeral port binding, dynamic descriptor finalization on `:bind-succeeded`).
- Step 5: `yin.repl.connect`: D3 (`observe-terminal` refinement, `reattachable?`).
- Step 6: `yin.repl.serve`: D6 (drop `ephemeral-port-unsupported`, dynamic URL upon `:serving`).
- Step 7: `yin.repl.dht` & `yin.repl.main`: D7 (driver-paced DHT stop machine, `main/stop-tick`, banner).
- Step 8: Multi-host test lanes: JVM, Node, Dart (`bb test:clj`, `bb test:cljs`, `bb test:cljd`, `main_test` ^:slow fact 4).
- Step 9: Gate verification: D9 grep gates (zero `:ws/` outside `test/yin/repl/host/`), `clj -M:kondo`, `cljstyle check`.
- Step 10: Update documentation: `docs/design/dao.stream.remote.md`.
- Step 11: Write comprehensive report to:
  `collab/1791404000000-engineer-stream-s4.claude-opus-5-5.findings.md`

Rules:
- Do not touch `rpc.cljc`, `apply.cljc`, `remote.cljc`.
- Follow format.md: no em dashes, no first-person pronouns.
- Do not commit. Await adversarial review and architectural sign-off.
