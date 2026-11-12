# Step 1 Confirmation Review r2 Findings

Created-GMT: 2026-11-08 15:08:15 GMT
Created-Local: 2026-11-08 22:08:15 +07
Coding-Agent: codex
Model: gpt-6-astra
Session-ID: not-applicable (headless codex read-only run)

Completed-GMT: 2026-11-08 15:07:29 GMT
Completed-Local: 2026-11-08 22:07:29 Asia/Ho_Chi_Minh

**Hold Step 2 relocation. Findings 1 and 3 are addressed; finding 2 remains partially open.**

Reviewed `yin-repl-dht-composition` at `b88bad9dd93d233f1d78e5a111444f6c8d86c9aa`, including uncommitted changes and supplied execution logs. No files edited; tests were not rerun.

1. **Publication-result expectations — addressed.** `fixture.cljc:100` explicitly specifies all eight cases: matching success, refusal, delayed completion, wrong ID/round/manifest, duplicate, and stale delivery. Results are recorded after the HEAD outcome is known. `index_test.cljc:1079` correctly labels the model-based cases as Step 2 obligations, rather than claiming current engine coverage. This satisfies Step 1’s specification requirement.
2. **Invariant evidence — High, still incomplete.** The remediation report labels cross-host parity green, but its 84/4/13 assertions cover JVM bulk/incremental equivalence, insertion replay, and memory/file equivalence. These do not establish identical bytes and addresses across JVM, Node, and Dart as required by §8. The engineer’s stdout explicitly confirms Node and Dart were not run.
   - The additional JVM restore, repair, and lifecycle evidence materially improves the baseline. Classifying unexecuted lanes as unverified is correct under §11, but does not satisfy the missing pre-relocation parity pin.
   - Recommended correction: Supply a narrow deterministic publication comparison across the three runtimes, with revision, host/runtime, exact commands, executed identifiers, and complete logs. Full integration-suite green and completed §10 gates are not prerequisites here.
3. **Shell retry reproduction — addressed.** `index_test.cljc:1162` retains the failing acceptance test and documents the baseline revision, exact command, and signature (`shell-retry-after-refused-publication-promotes-the-whole-suffix`). Its log confirms 11 assertion failures across both backends: retry returns `Named.getName()` null error and leaves frontier behind. The indexer oracle remains green with 45 assertions.

**Step 1 sign-off remains blocked by the narrow cross-runtime evidence gap.**
