Created-GMT: 2026-09-17 11:33:31 GMT
Created-Local: 2026-09-17 18:33:31 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: pending (caller-generated, see below)
Role: VM/Runtime Implementer

# Task: dao.stream v1-retirement, U1 — orphan deletions

Read `docs/design/dao.stream.v1-retirement.implementation-plan.md` in full
first, especially the "U1 — orphan deletions" section and D1/D6, and
`docs/design/dao.stream.md`'s invariants for context on why v1 is retiring.
This unit needs no design decision — every file here is dead code or
superseded by a live v2 twin, already verified by the orchestrator and an
independent adversarial reviewer.

## Scope — four independent groups, each its own commit-worthy change (do
## NOT commit yourself; leave the working tree with the changes made,
## unstaged, for the orchestrator to review and commit)

1. **Telemetry viewer (D1):** delete `src/cljs/yin/vm/telemetry_viewer.cljs`,
   `test/yin/vm/telemetry_viewer_test.cljs`, `public/telemetry-viewer.html`;
   remove the `:telemetry-viewer` shadow-cljs build; in
   `src/cljs/datomworld/demo.cljs` remove the `:13` require, the `:50-53`
   commented picker card, the `:71` hash-route branch, the `:86` branch, and
   the `:218` render branch (re-check exact current line numbers, the plan's
   are as of the 2026-09-17 sweep and the tree has moved since); add one line
   to `docs/design/telemetry-ui-design.md`'s existing status note saying the
   viewer file itself is now deleted too.
2. **Continuation transport v1:** delete
   `src/cljc/datomworld/continuation_transport.cljc` and
   `test/datomworld/continuation_transport_test.cljc`. Confirm first (grep)
   that `datomworld.demo.continuation-transport` is the only other
   continuation-transport file and needs no change.
3. **WebSocket demo pair (D6):** delete `src/clj/datomworld/ws_demo_server.clj`,
   `src/cljs/datomworld/ws_client_demo.cljs`, `bin/run-ws-demo.sh`,
   `bin/start-ws-server.sh`, `bin/run-ws-client.sh`, `docs/ws-demo.md`;
   remove the `:ws-client-demo` shadow-cljs build.
4. **Orphan tests:** delete `test/dao/test_utils.cljc`,
   `test/datomworld/demo/artifact_stream_test.cljs`,
   `test/dao/stream_bench.cljc`; remove the `:bench` shadow-cljs build.
   Before deleting `test_utils.cljc`, grep for `dao.test-utils` across
   `test/` and confirm nothing still requires it (R4 already removed its
   only production caller; the plan's own census says three v2 driver-test
   docstrings only *name* it as history, not a live require — verify this
   yourself).

## Acceptance criteria (yours to check before reporting done, the
## orchestrator re-verifies independently regardless)

- A grep for `register-(reader|writer)-waiter!|drain-one!|take!!|\bds/open!|
  defopen|closed\?|:daostream/gap|\{:position [0-9a-z(]|bind-stream!|
  put-frame!|tail-position|make-ring-buffer-stream|->seq|strict-vec|:woke`
  and for `telemetry-viewer|ws-client-demo|ws_demo|dao\.stream-bench` across
  `src test bin deps.edn bb.edn shadow-cljs.edn docs public README.md`
  loses exactly the rows this unit's deletions account for (some rows belong
  to U2, U3, U4, U6 — not yours to touch).
- `clj -M:test` passes.
- The shadow `:test` and `:demo` builds compile and pass (`bb test:cljs` or
  the shadow equivalent this repo uses — check `bb.edn`/`package.json` for
  the exact command).
- `clojure -M:cljd test` / `bb test:cljd` passes (clear `test/cljd-out`
  first per this repo's known stale-artifact trap before trusting a cljd run).
- `demo.cljs` compiles with no telemetry branch remaining.

Do not touch any file outside this unit's four groups — U2, U3, U4, U6 are
separate units with their own scope. Report back exactly what you deleted,
what you edited (with line numbers), and your own verification command
output.
