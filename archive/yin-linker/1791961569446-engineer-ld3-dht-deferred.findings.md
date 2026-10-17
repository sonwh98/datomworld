# L-d-3 findings

- Read the slice brief, architecture foundations, build/test guide, and binding scoping design sections 2.1, 2.4 and acceptance row L-d-3. The scoping log is in the main worktree at `/Users/sto/workspace/datomworld/collab/1792341000000-architect-i4-scoping.claude-fable-5-1.stdout.log`.
- Initial working tree has only the supplied untracked prompt and agent stdout log; no existing implementation edits.
- `publish` already requires `closure`; directly requiring `publish` from the walker would create a cycle. Share the eagerness scan through a small helper, retaining `publish/requirements` as the public surface.
- Existing closure/DHT fixtures pin dependencies without explicit module-level `require`. Update eager fixtures to make their intent explicit, and add deferred fixtures and refusal-as-data coverage.
- Scope: JVM iteration only, with targeted linker tests, `bb test:sub:clj yin.vm`, kondo and cljstyle on changed files. No Node/Dart lane or merge requested.

## Validation

Complete: implementation, documentation, targeted acceptance tests, broad JVM regression suite, and changed-file lints all passed.

## Implementation progress

- Red phase: targeted JVM command failed compiling the new public API assertion because `publish/requirements` was private (expected).
- Shared pure scan implemented in `yin.vm.linker.requirements`; `publish/requirements` is public. The walker projects checked row bodies using `vm/semantic-bytecode-grammar`, avoiding full AST reconstruction or extra validation in the shape-only walker.
- Every visited module's pin now includes derived `:eager?`; deferred pins are neither read nor descended into. `dependency-bindings` filters to eager pins before producing binding outcomes.
- Eager regression fixtures explicitly require dependencies and declare the primitive profile. Deferred acceptance covers missing content, early absent/mismatched names, all four formats, and statement-time `try-require` on all four VMs with empty and undeclared-principal fold diagnostics.
- Initial kondo check: zero errors/warnings. Applied cljstyle formatting to changed sources/tests.
- JVM test loading required generated Python ANTLR classes. Initial generation was blocked by restricted networking (`UnresolvedAddressException`); retried the authorized build prerequisite with sandbox escalation.
- Updated DHT design sections 4.2 and 7.4 to distinguish eager closure/binding checks from statement-time imports.

## Validation progress

- ANTLR generation succeeded after escalation; no tracked generated artifacts changed.
- `clojure -M:test -n yin.vm.linker.closure-test -n yin.vm.linker.dht-test`: 28 tests, 531 assertions, zero failures/errors.
- `DATOM_SLOW_TESTS=1 clojure -M:test -n yin.vm.linker.dht-end-to-end-test`: 7 tests, 137 assertions, zero failures/errors (including existing slow scenarios and the new four-VM deferred-import scenario).
- Replaced recursive scanning with a pure worklist that visits each shared node/row once; this keeps classification bounded by the already checked tree's size.
- Final changed-file kondo: zero errors/warnings. Changed-file `mise exec -- cljstyle check`: exit 0. `git diff --check`: clean.
- `bb test:sub:clj yin.vm` is running over 82 namespaces; log `target/profile/sub-clj.log`. Its prerequisite runner emitted sandbox `sysctl` teardown warnings but continued into the JVM suite.
- Sandboxed subsystem run completed: 1325 tests, 26433 assertions, zero failures, one environment error in `yin.vm.linker.head-board-test/the-board-crosses-a-real-loopback-socket`: `java.net.SocketException: Operation not permitted` at the ServerSocket bind. Rerunning the same requested command with escalation for local loopback access.

- Final `bb test:sub:clj yin.vm` rerun with loopback access: 82 namespaces, 1325 tests, 26444 assertions, zero failures/errors; exit 0, 89 seconds. This run includes the final bounded worklist scanner.

## Outcome

L-d-3 is implemented and verified locally on the JVM. Manifest schema is unchanged; eagerness is derived from existing tree data. No `with-redefs`, external messaging, merge, or commit was performed. Node/Dart host lanes remain landing-time verification outside this JVM-only brief.
