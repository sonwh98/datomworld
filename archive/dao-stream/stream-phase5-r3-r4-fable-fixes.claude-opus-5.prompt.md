Created-GMT: 2026-09-04 04:36:18 GMT
Created-Local: 2026-09-04 11:36:18 ICT
Coding-Agent: claude
Session-ID: none (new fix round)

# Task: Resolve Fable findings in staged Phase 5 R3/R4 implementation

Role: Stream & Network Engineer / VM Runtime Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-04 11:36:18 ICT | Status: active | Rationale: owns the Yin R3/R4 implementation and can reconcile coupled REPL/Node boundary fixes without concurrent conflicts

Work in /Users/sto/workspace/datomworld. Read the complete architect report:
- collab/architect-phase5-r3-r4-signoff.fable.stdout.log

Read governing plans/contracts and the current staged diff. The user authorizes
fixes to Fable's findings. Edit only these staged implementation/test files:
- src/cljc/yin/repl.cljc
- src/cljc/yin/repl/connect.cljc
- src/cljc/yin/repl/driver.cljc
- src/cljc/yin/repl/host.cljc
- src/cljc/yin/repl/serve.cljc
- src/cljc/yin/repl_adapter.cljc
- src/cljs/dao/stream/ws/node.cljs
- test/dao/stream/ws/node_test.cljs
- test/yin/repl_connect_test.cljc
- test/yin/repl_driver_test.cljc
- test/yin/repl_serve_test.cljc
- test/yin/repl_test.cljc

Required TDD fixes:
1. An operator `(disconnect)` must return subsequent ordinary input to local
   evaluation and clear/drop remote queued input; an uninvited disconnect may
   remain reconnectable and queue according to the plan.
2. A remote request that leaves `:pending-input` must return a correlated
   malformed/incomplete-input error and reset pending input, so continuation
   fragments cannot cross attachment boundaries.
3. Node `listen!` must pass an explicit host clock reading to
   `ws/accept-connection!`, making configured pending-slot expiry effective.
4. Quit/EOF must initiate `serve/stop!` and keep stepping until lifecycle
   completion before host exit, with a bounded failure policy; headless mode
   needs an explicit stop trigger suitable for tests/host signals.

Address Fable's LOW findings where local and safe: unify/fix canonical path
trailing-slash behavior; treat query-only URLs as empty path -> `/repl`;
accurately align the Node adapter with the `yin.repl.host` seam; eliminate
the connect/drop same-tick false “already connected”; refuse missing
subprotocol before upgrade if supported by the existing Node `ws` API; clarify
or safely support port zero; keep binary handling explicit without broadening
committed transport APIs unless necessary. If any low item requires a design or
committed-core expansion, leave it deferred and explain why.

Do not edit docs, v1 namespaces, committed DaoStream core files outside the
list, collab artifacts, build configuration, or unrelated files. Do not stage
or commit. Preserve current staged content; your edits will appear unstaged on
top for orchestration review.

Run focused JVM tests, Node/CLJS tests covering changed behavior, scoped lint,
and targeted CLJD compilation. Do not rerun unrelated full suites.

Final report begins with actual Completed-GMT/Completed-Local, Coding-Agent:
claude, Session-ID, then findings resolved/deferred, files, exact tests, and
remaining risks.
