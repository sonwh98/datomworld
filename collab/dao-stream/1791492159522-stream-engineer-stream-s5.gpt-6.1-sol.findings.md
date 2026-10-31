Created-GMT: 2026-10-09 00:00:00 GMT
Created-Local: 2026-10-09 ICT
Coding-Agent: codex
Session-ID: runtime session not exposed

# Track B Slice S5 implementation status

Workspace: `/Users/sto/workspace/datomworld-stream-s5`
Baseline revision: `3bca8761c57214387d26ce46c642725009cbb7dd`
Status: partial implementation; the focused remote routing JVM tests pass. This is not an S5 production sign-off.

## Changed files

- `docs/design/dao.stream.remote.md`
- `src/cljc/dao/stream/remote.cljc`
- `src/cljc/dao/stream/remote_channel.cljc`
- `src/cljc/dao/stream/remote_meet.cljc`
- `src/cljc/dao/stream/remote_pair.cljc`
- New `src/cljc/dao/stream/remote_route.cljc`
- `test/dao/stream/remote_meet_test.cljc`
- `test/dao/stream/remote_pair_test.cljc`
- New `test/dao/stream/remote_route_test.cljc`

The worktree also contains pre-existing untracked architect-spec and engineer-prompt files; they are not part of this implementation report.

## Implemented and locally exercised

The pair layer exposes stepped link/channel projections, carries policy bounds, rolls back the first attachment if the second fails, treats terminal initialization failures as terminal, supports independent observers, retires entries, and bounds its cache. Meeting work adds dynamic table snapshots, advertised channel descriptors, caller-provided incarnations/correlation identities, bounded admissions, lease lifecycle changes and cleanup/unwiring. The route helper provides descriptor preflight, bounded candidate/readiness/punch-resolution state transitions and a fair bounded driver over caller-composed route plans. Tests cover direct, one-relay and two-relay ring compositions with identity parity, fair scheduling, sibling failure, no append replay, and associated pair/meeting lifecycle boundaries.

Passing focused tests on the final tree:

- `clojure -M:test -n dao.stream.remote-route-test`: 11 tests, 91 assertions; 0 failures, 0 errors.
- `clojure -M:test -n dao.stream.remote-route-test -n dao.stream.remote-pair-test -n dao.stream.remote-meet-test -n dao.stream.remote-channel-test -n dao.stream.remote-test -n dao.stream.ws-project-test`: 165 tests, 1,207 assertions; 0 failures, 0 errors. This grouped run preceded a test-only redundant-binding cleanup; the focused route namespace was rerun successfully after that cleanup.
- `clj -M:kondo --lint` on changed implementation and test files: 0 errors, 0 warnings (final tree).
- `git diff --check`: passed (final tree).

Earlier broad lanes completed against an earlier snapshot, before the last route/test edits: `bb test:clj` reported 3,859 tests / 242,331 assertions, no failures/errors; `bb test:cljs` reported 3,734 tests / 106,700 assertions, no failures/errors. They are historical evidence only, not validation of the final snapshot. Node dependencies were restored with `npm ci` from the lockfile after the initial missing-dependency failure. No final broad lane is claimed here.

## Remaining acceptance gaps

A1–A10 are not all established. Portable route tests exercise the route-state logic and simulated direct/relay compositions, but there is no completed multi-host JVM/Node/Dart matrix, separate-machine two-relay WS run, browser endpoint run, or real UDP integration. The implementation does not yet provide the complete automatic board-driven discovery/grant/readiness orchestration and composed production route lifecycle described by the specification; route plans and descriptor/channel ownership are still supplied by the caller. Shared base-channel ownership across multiple plans is rejected rather than reference-counted/shared. Consequently full symmetric peer roles over the production driver and cross-machine claims remain open. Production byte-admission and lifetime resource accounting also need a complete audit and limit−1/limit/limit+1 coverage. Treat the changes as an implementation slice with passing focused evidence, not release-ready S5.

No direct changes were made to `ws_project.cljc` or the cross-process fixture suite. The two completed broad lanes above predate final edits; rerun `bb test:clj`, `bb test:cljs`, and required host matrix on the final commit before sign-off.
