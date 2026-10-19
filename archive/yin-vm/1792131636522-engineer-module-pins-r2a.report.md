Completed-GMT: 2026-10-16 06:20:36 GMT
Completed-Local: 2026-10-16 13:20:36 +07
Coding-Agent: codex
Session-ID: unavailable (not exposed by the provider in this session)

# Module-pins round 2a: R4 only

## Scope and implementation

Completed only Part C steps 1–7. Preserved the existing 13-file R4 diff.
No carrier/module-profile pins, driver forwarding, fetch loop, fixture re-mint,
staging, or commit. No subagents were used.

- `install-node` strips the four sender admission keys before CBOR portability
  checking and row construction. Removed the restore-side stripping and comment.
- `check-install!` refuses any keyword key in namespace `yin.admit` as
  `:yin.k/undecodable :install-response`, before attachment.
- Renamed only the lower option to `:yin.k/admitted-modules` across both source
  files, all five test/support files, and the six amended design/ledger files.
  Grepped first; ledger fact kinds and driver not-holder fields retain
  `:yin.k/admitted` and their files are untouched.
- Kept every existing admission-test assertion. Added decoded root install-row
  slot-3 absence, equality of the carried install row after lower/relift, and
  spliced sender fields refusing with zero attaches. Added an explicit sender
  record to the new frozen parked-installer recovery test; recovery returns
  `:ok` and the published pack reproduces byte for byte.
- Retargeted the six doc amendments from restore-time dropping to lift omission
  and refusal of carried fields. Added the P2 holder-driver gap: the driver
  passes no admitted-modules map, so its install-child handoff refuses; closure
  belongs to the next slice. Added the flat name-keyed tree restriction to the
  lower-task docstring.

## Mutants (executed, then restored)

| Mutant | Named killer | Tests | Assertions | Failures | Errors |
|---|---|---:|---:|---:|---:|
| m1: lift retains sender keys | `rows-test/carried-install-requires-receiver-admission`; `holder.export-test/recovery-of-installer-with-sender-admission-is-reproducible-test` | 2 | 23 | 11 | 1 |
| m2: check-install! accepts yin.admit keys | `rows-test/carried-install-requires-receiver-admission` | 1 | 25 | 2 | 0 |
| m3: bypass install admission gate | `rows-test/carried-install-requires-receiver-admission` | 1 | 25 | 12 | 0 |

Every mutant exited 1. m1 directly fails the decoded-row absence assertion
and the recovery `:ok`/reproduction pins; the normal lower also refuses its
sender-bearing row. m2 fails both refusal and zero-attach assertions. m3
fails the existing admission refusal assertions. Each run restored the exact
original source bytes (Python try/finally plus byte equality); restored SHA-256:
`f8717c941e925098da9c5466025860a99f233ea30efbb7228b742faf5525badc`.

## Fixtures and boundaries

No fixture or pinned golden moved; no old/new addresses. No edits under
src/yang or test/yang. No ledger or driver edits. Node/Dart lanes are assigned
to the orchestrator by the preceding prompt and were not run here.

## File-to-commit mapping (review only; nothing staged)

Code: `src/cljc/yin/vm/ucf/rows.cljc`,
`src/cljc/yin/vm/ucf/holder/export.cljc`.

Tests/support: `test/yin/vm/ucf/rows_test.cljc`, `rows_support.cljc`,
`rows_custody_test.cljc`, `holder/export_test.cljc`, and
`test/yin/vm/semantic/safepoint_harness_test.cljc`.

Docs: `docs/agents/known-gaps.md`; design docs
`yin.vm.semantic.continuation.md`, `yin.vm.ucf-transport-tuples.md`,
`yin.vm.universal-continuation-format.md`, `yin.vm.linker.dht.md`, and
`yin.vm.ucf-revisions.md`.

The report is a local collab artifact, excluded from the proposed commit.

## Validation

- Initial rows + holder.export regression run: 80 tests, 1057 assertions,
  0 failures/errors.
- Focused command: the preceding task's ten explicit namespaces, plus
  `-r '^yin\.vm\.ucf\.holder\..*'` and `-r '^dao\.jing\..*'`, with
  `clojure -M:test -e :slow`. Initial sandbox run: 648 tests, 18431 assertions,
  0 failures, 1 error in Jing's real loopback socket test. Unrestricted rerun
  without code changes: **648 tests, 18432 assertions, 0 failures/errors**.
- Whole JVM quick tier, unrestricted `clojure -M:test -e :slow`:
  **4024 tests, 249899 assertions, 0 failures/errors**, exit 0.
- Quick tier reports missing Dart peer executable skips (WS-project and REPL
  peers). No peer binaries built or Node/Dart lanes run. The Python lexer was
  already available; no generator step was needed.
- Kondo on all seven changed Clojure files: **0 errors, 0 warnings**.
- Final `git diff --check`: clean. Index remains empty.
- Final diff audit: exactly the original 13 tracked files; no fixtures,
  pinned goldens, src/yang or test/yang changes. Restored source digest still
  equals the mutant-run restoration digest.

Logs (local): `/tmp/module-pins-r2a-initial.log`,
`/tmp/module-pins-r2a-focused.log`,
`/tmp/module-pins-r2a-focused-unrestricted.log`,
`/tmp/module-pins-r2a-quick-unrestricted.log`,
`/tmp/module-pins-r2a-kondo.log`, and
`/tmp/module-pins-r2a-m1.log`, `m2.log`, `m3.log` (same prefix).

Part C steps 8–16 were not started. Architect sign-off and any commit remain
outside this dispatch. No staging or commit performed.

READY
