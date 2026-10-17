# L-d-2 Engineer findings

- Read the slice brief, architecture, malleability, and build/test guidelines.
- Binding scoping log is in the main checkout; read section 2.2 and acceptance row L-d-2 there.
- `receive-module` is shared by child inheritance and parent acts 3/4. Its guard must run before attachment/lowering to preserve the original task on refusal.
- Existing absent-pin expectations also occur in waiter retry, deterministic ordering, and before-lifting tests; change those fixtures to real present-and-unequal conflicts.
- Scope: pure registry-derived pin check, present-and-unequal install check, portable regression tests, and matching documentation. JVM-only verification per brief; no landing/merge requested.

## Validation

Pending implementation and local JVM tests/lints.

## Implementation and regression coverage

- Added `module/pin-conflict`, derived from linked manifests and incoming pins; conflicts sort by dependency name, pinning owner, then addresses for deterministic selection.
- `receive-module` guards before image attachment, resource lowering, store instantiation, and registry updates. Existing spawn/advance exception handlers retain refusal semantics at `:loading` / `:linked`.
- `link-install` now refuses present-and-unequal only. Updated both design documents, including the install protocol explanation as well as the refusal row.
- Tests cover absent installation, existing/incoming pin conflicts, deterministic selection, host entries, deferred wrong-address `try-require` data and `require` raise, and concurrent parent receives in both act 3 and act 4 with full registry/store rollback. Existing inherited/fresh mismatches, matching success, waiter retry, ordering, and before-lift refusal remain covered.
- Red phase: original implementation failed new/updated linker suite (15 failures, 6 errors). After implementation and fixture correction, focused JVM namespaces pass: 51 tests, 1,196 assertions, 0 failures/errors.
- `try-require` install refusals preserve the existing outcome shape (no extra correlation id); tests check that shape, excluding the existing host exception message.
- First `bb test:sub:clj yin.vm` attempt stopped in ANTLR generation on restricted-network DNS resolution. Retrying the same local command with network access.
- Repository-wide `cljstyle check` reports existing formatting issues outside changed files. No unrelated source reformatted; changed-file style verification pending.

## Verification update

- Explicit inconsistent-composition fixture proves child inheritance refuses at `:loading`; receiving task registry, module stores, and program store remain unchanged. Focused JVM suite now passes 52 tests / 1,211 assertions, 0 failures/errors.
- Network-enabled subsystem rerun successfully generated digest-checked pinned ANTLR sources and started all 82 selected JVM namespaces (ongoing).
- Repository-wide `clj -M:kondo --lint src test`: exit 3, 27 errors / 67 warnings, all in unchanged files (examples: `yin/repl/host.cljc` invalid require and unresolved Flutter/Dart symbols). No errors/warnings in the four changed Clojure files.
- Repository-wide `mise exec -- cljstyle check`: exit 2, 25 unchanged files formatted incorrectly (including bb/deps/shadow config, public content, encoder, and waitset/repl tests). These unrelated files are left untouched.
- Changed-file kondo: 0 errors / 0 warnings. Changed-file style rechecked after formatting the new inheritance fixture.

## Final result

Implemented L-d-2. No commits, merges, remote testing, or tri-host gates performed; the slice brief explicitly requests local JVM iteration only.

| Check | Result |
|---|---|
| Focused JVM (`clojure -M:test -n yin.vm.linker-require-test -n yin.vm.module-test`) | PASS: 52 tests, 1,211 assertions, 0 failures/errors |
| JVM subsystem (`bb test:sub:clj yin.vm`, network-enabled prerequisite retry) | PASS: 82 namespaces, 1,320 tests, 26,348 assertions, 0 failures/errors |
| Changed-file kondo | PASS: 0 errors/warnings |
| Changed-file cljstyle | PASS: exit 0 (mise cache warning outside workspace is harmless) |
| Repository kondo (`clj -M:kondo --lint src test`) | FAIL baseline outside slice: 27 errors, 67 warnings |
| Repository cljstyle (`mise exec -- cljstyle check`) | FAIL baseline outside slice: 25 unchanged files |
| `git diff --check` | PASS |

The subsystem run started before the final inheritance regression was added; the subsequent focused run includes that regression and passes. Runtime implementation was unchanged throughout the passing runs.

Logs: `target/profile/sub-clj.log` for the subsystem; `target/profile/ld2/` for focused red/green and repository/changed-file lint logs. No unrelated lint corrections made.

Safety coverage: deferred pins allow initial linking, later mismatches refuse as data/raise without registry or store changes, and conflicts at parent act 3, parent act 4, and child inheritance are rejected through existing exception-to-refusal handling. No pin table or language-specific branch was introduced.
