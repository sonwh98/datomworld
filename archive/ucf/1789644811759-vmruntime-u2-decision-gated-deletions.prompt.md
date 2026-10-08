Created-GMT: 2026-09-17 11:33:31 GMT
Created-Local: 2026-09-17 18:33:31 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: pending (caller-generated, see below)
Role: VM/Runtime Implementer

# Task: dao.stream v1-retirement, U2 — decision-gated deletions

Read `docs/design/dao.stream.v1-retirement.implementation-plan.md` in full
first, especially "U2 — decision-gated deletions" and D2/D3. The owner
answered both decisions on 2026-09-17: delete `agent.tzu`/`agent.tools`
outright, and delete the `yin.module`/`yin.io` family outright. Both are
recorded as answered in the document itself — this unit executes them, no
further decision needed.

A separate, independent implementer is concurrently working on U1 (orphan
deletions: telemetry viewer, continuation transport v1, WebSocket demo
pair, orphan tests) in the same working tree. Your files are disjoint from
theirs — do not touch `demo.cljs`, `test/dao/test_utils.cljc`,
`shadow-cljs.edn`'s `:telemetry-viewer`/`:ws-client-demo`/`:bench` builds,
or any file under `src/cljs/yin/vm/telemetry_viewer*`,
`datomworld/continuation_transport*`, `datomworld/ws_demo_server*`,
`datomworld/ws_client_demo*`, `bin/{run-ws-demo,start-ws-server,run-ws-client}.sh`,
`docs/ws-demo.md`, `test/datomworld/demo/artifact_stream_test.cljs`,
`test/dao/stream_bench.cljc`.

## Scope — two independent groups, each its own commit-worthy change (do
## NOT commit yourself; leave the working tree with the changes made,
## unstaged, for the orchestrator to review and commit)

1. **`agent.*` (D2):** delete `src/cljc/agent/tzu.cljc`,
   `src/cljc/agent/tools.cljc`, `test/agent/tzu_test.cljc`,
   `test/agent/tools_test.cljc`, and the two prose files
   `src/cljc/agent/llm-configuration.md`, `src/cljc/agent/env.example.sh`;
   remove the `:atzu` alias from `deps.edn`; drop the `agent.tzu` launch
   line from `README.md` (currently around `:156`, re-check); add a status
   note to `docs/design/agent.tzu.md`, `docs/design/agent.tzu.dao.stream.md`,
   and `docs/design/agent.tzu.yin.vm.md` saying the code is deleted
   (2026-09-17, this plan) and the Yin-native agent replacement is owed to
   `agent.harness.md`'s plan.
2. **`yin.module` family (D3):** delete `src/cljc/yin/module.cljc`,
   `src/cljc/yin/stream.cljc`, `src/cljc/yin/io/file.cljc`,
   `src/cljc/yin/io/file_input_stream.cljc`,
   `src/cljc/yin/io/file_output_stream.cljc`,
   `test/yin/module_test.cljc`; add a status note to
   `docs/design/dao.stream.file.md` (v1 design, implementation deleted, no
   v2 twin owed by this plan). **Important:** `src/cljc/dao/stream/ringbuffer.cljc`
   (currently around `:12` and `:458`) requires `yin.module` and calls
   `init-module!`/registers a `'stream` module into it — drop that require
   and the registration call in the same commit as this deletion (the
   plan's own text: "v1 ring buffer's module registration has had no
   reader since 2026-09-16 either"). `ringbuffer.cljc` itself is NOT
   deleted in this unit — it retires whole in U6, later — only this one
   require/registration is removed now so it keeps compiling.

## Acceptance criteria (yours to check before reporting done, the
## orchestrator re-verifies independently regardless)

- A grep for `\[(yin\.module|yin\.stream|yin\.io[a-z.-]*|agent\.tools|agent\.tzu)( |\]|$)`
  across `src test bin deps.edn bb.edn shadow-cljs.edn` returns nothing
  under `src/` (only `docs/`, `collab/`, this plan itself).
- `clj -M:test` passes.
- `bb test:cljs` passes, and `Testing yin.vm.module-test` (or whichever
  v2 test namespace covers the *v2* module registry — check
  `test/yin/vm/module_test.cljc`) still appears in the Node output,
  confirming this deletion did not take the unrelated v2 registry's tests
  with it.
- `bb test:cljd` passes (clear `test/cljd-out` first per this repo's known
  stale-artifact trap).
- `src/cljc/dao/stream/ringbuffer.cljc` still compiles and its own test
  suite (whichever covers it) still passes after the require is dropped.

Do not touch any file outside this unit's two groups. Report back exactly
what you deleted, what you edited (with line numbers), and your own
verification command output.
