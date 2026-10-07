Created-GMT: 2026-09-04 05:05:39 GMT
Created-Local: 2026-09-04 12:05:39 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: none (prior non-interactive Opus run exposed no resumable id)

# Task: Phase 5 R3/R4 Fable blocker corrections

Role: Stream & Network / VM Runtime Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-04 12:05:39 Asia/Ho_Chi_Minh | Status: active | Rationale: resume ownership of its Phase 5 R3/R4 fix delta and correct the architect's residual findings

Correct the findings in
`collab/architect-phase5-r3-r4-opus-fixes-verification.fable.stdout.log` in
`/Users/sto/workspace/datomworld`. Work autonomously and finish the complete
implementation. Do not stage or commit.

Read first:
- docs/design/datom.world.md
- docs/design/dao.stream.implementation-plan.md
- docs/design/yin.repl.implementation-plan.md
- docs/agents/team/TEAM.md
- collab/architect-phase5-r3-r4-opus-fixes-verification.fable.stdout.log
- collab/stream-phase5-r3-r4-fable-fixes.claude-opus-5.stdout.log
- `git diff` and `git diff --cached`

Required blockers:

1. Fix the JVM interactive lifecycle regression: a typed `(quit)` must stop the
   endpoint and exit without waiting for stdin EOF. Keep the step owner in
   control and preserve headless shutdown behavior.
2. Fix operator-disconnect reconnect trapping after `/ended`, `/not-found`,
   `/transport-error`, and other non-reattachable terminals. The temporary
   "Still disconnecting" response may apply only before a terminal lands; a
   later `(connect ...)` must be able to compose a fresh boundary. Preserve
   useful terminal status.
3. Eliminate stale RPC `:unsent` work across operator disconnect and reattach.
   Abandon it explicitly with a visible completion/reason rather than silently
   losing or replaying it. Ensure a newly typed line cannot be replaced by the
   stale request. Make driver and RPC retry ownership coherent. Add the missing
   regressions, including an actually-unsent request rather than only an
   outstanding request.
4. Make shutdown of a failed or never-bound endpoint immediate and honest; it
   must not consume the timeout waiting for a `:stopped` fact no host can
   produce. Fix the JVM shutdown-budget off-by-one while touching this path.

Address these closely related LOW findings when locally safe:

- Route ordinary input locally after terminal outcomes that cannot reattach;
  retain queueing only where a meaningful retry/reattach decision exists.
- Remove or make safe the clock-omitting two-arity of `serve/accept!`.
- Use a serve-owned incomplete-input error code rather than claiming the RPC
  envelope is malformed, and update tests.
- Document the required two-argument generated callback arity at Node
  `verifyClient`.

Authorized implementation/test scope:
- src/cljc/yin/repl.cljc
- src/cljc/yin/repl/connect.cljc
- src/cljc/yin/repl/driver.cljc
- src/cljc/yin/repl/serve.cljc
- src/cljs/dao/stream/ws/node.cljs
- test/yin/repl_test.cljc
- test/yin/repl_connect_test.cljc
- test/yin/repl_driver_test.cljc
- test/yin/repl_serve_test.cljc
- test/dao/stream/ws/node_test.cljs

You may additionally edit `src/cljc/dao/stream/rpc.cljc` and its focused
tests only if an explicit RPC operation is necessary to abandon `:unsent`
without violating ownership. Prefer a narrow public state transition over
driver knowledge of RPC internals. Do not edit documentation, build files,
collab artifacts, v1 namespaces, or unrelated files.

Use TDD for each behavioral correction. Run the focused JVM tests first, then
the relevant Node target if Node code changes, kondo on every touched source
and test file, and CLJD compile plus Dart analysis for `.cljc` host changes.
The previous suites passed; do not rerun unrelated full suites.

Begin the final response exactly with:
Completed-GMT: <actual timestamp>
Completed-Local: <actual timestamp and timezone>
Coding-Agent: claude
Session-ID: <exact id or none>

Report each resolved finding, exact files changed, focused test/assertion
counts, lint/compile outcomes, and remaining risks. Leave all edits unstaged on
top of the existing staged baseline.
