Created-GMT: 2026-10-08 07:05:00 GMT
Created-Local: 2026-10-08 14:05:00 ICT

You are the Independent Adversarial Reviewer for Track B Slice S4: "Beyond loopback, terminal causes, and ephemeral listeners".
Model: Codex (gpt-6.1-sol).
Repository root: /Users/sto/workspace/datomworld-stream-s3a
Branch: stream-crossmachine-s4 (diff against master @ 66756d20).

Context & Inputs:
- Architectural specification: `collab/1791403500000-architect-stream-s4-spec.claude-fable-5-1.findings.md`
- Implementation Engineer report: `collab/1791404000000-engineer-stream-s4.claude-opus-5-5.findings.md`

Your review task:
1. Conduct an adversarial defect-finding review of all working tree changes:
   - `src/cljc/dao/stream/ws.cljc`: D5 (`servable-descriptor?` port 0 admission, `make-endpoint`, `endpoint-bound!`). Check that `descriptor?` still undialable on port 0.
   - `src/cljc/dao/stream/ws_project.cljc`: D1 (cause recording on projection closure, `opened?`, accessors `cause`, `opened?`).
   - `src/cljc/dao/stream/remote_channel.cljc`: D2 (neutral cause mapping: `:ended`, `:dropped`, `:not-served`, `:unreachable`, `:expired`) and D4 (ephemeral port 0 admission, dynamic descriptor finalization on `:bind-succeeded`, refusal `::port-unreported`).
   - `src/cljc/yin/repl/connect.cljc`: D3 (`observe-terminal` refinement: refine only `detached`; `reattachable?` 2-arity `(connection client)`).
   - `src/cljc/yin/repl/driver.cljc`: D3 (`remote-routed?`, `connect-command` using 2-arity `reattachable?`).
   - `src/cljc/yin/repl/serve.cljc`: D6 (removal of `ephemeral-port-unsupported`, dynamic URL upon `:serving`).
   - `src/cljc/yin/repl/main.cljc` & `src/cljc/yin/repl/dht.cljc`: D7 (driver-paced stop machine for DHT board, `main/stop-tick`, banner).
   - `test/dao/stream/loopback_net.cljc`: D8 (in-process ephemeral port allocator).
   - Boundary gate D9: verify zero `:ws/` or `ws-project` tokens outside `test/yin/repl/host/`.
2. Multi-host test evidence:
   - JVM fast: 3818 tests, 241717 assertions, 0 unexpected failures (1 known baseline defect `handoff-v2.txt`).
   - JVM slow: `yin.repl.main-test` ^:slow fact 4 passed (2 tests, 16 assertions).
   - Node focused: 163 tests, 1033 assertions, 0 failures, 0 errors.
   - Dart fast: 3625 passed, 0 unexpected failures.
   - Cljstyle check clean, Kondo clean.

Write your review findings to:
`collab/1791404500000-reviewer-stream-s4.codex.findings.md`
Render an unambiguous verdict: ACCEPT or REVISE.
