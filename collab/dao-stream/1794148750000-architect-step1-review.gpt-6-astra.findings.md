# Step 1 Architecture Review Findings

Created-GMT: 2026-11-08 14:43:10 GMT
Created-Local: 2026-11-08 21:43:10 +07
Coding-Agent: codex
Model: gpt-6-astra
Session-ID: not-applicable (headless codex read-only run)

Completed-GMT: 2026-11-08 14:42:31 GMT
Completed-Local: 2026-11-08 21:42:31 Asia/Ho_Chi_Minh

**Step 1 does not yet meet its exit criteria. Hold Step 2 relocation pending the corrections below.**

Reviewed branch `yin-repl-dht-composition`, HEAD `b88bad9dd93d233f1d78e5a111444f6c8d86c9aa`, including the uncommitted changes. No files edited; tests were not rerun.

| Severity | File:line | Invariant/evidence | Recommended correction |
|---|---|---|---|
| High | `index_test.cljc:926`, `fixture.cljc:160` | Publication-result expectations are incomplete. The fixture logs attempts containing only manifest and assigned durability, before HEAD succeeds. Correlation is described as emission order. Re-stepping an idle indexer does not test duplicate, delayed, or wrong-round result delivery. | Explicitly specify expected outcomes for matching success, refusal, delayed completion, wrong ID/round/manifest, and duplicate/stale results. Distinguish these pending protocol cases from existing synchronous pins; connect them to the real seam during Step 2. |
| High | `engineer report:19` | “Existing invariants green” is broader than the supplied evidence. Four focused JVM summaries and one slow query case do not establish §8’s restore-Settings, repair-cadence, cross-host parity, and composition-lifecycle baseline. Node/Dart execution is explicitly absent. | Supply revision-specific commands, executed test identifiers, complete outcomes, and evidence paths for the narrow pre-relocation pins. Classify known integration reds by signature under §11; full integration green is not required before extraction. |
| Medium | `index_test.cljc:967` | The known shell retry failure is documented but deliberately left unpinned. Indexer-level retry success cannot detect the shell’s erroneous rollback. The existing `notice` implementation supports the reported failure mechanism. | Retain a failing shell-level acceptance reproduction and record its exact baseline revision, signature, and command. Keep the narrower green indexer oracle; defer the engine fix to Step 2. |

Properties that passed review:
- Existing behavioral assertions are preserved or strengthened; no assertion weakening or widened exclusions found.
- Frontier agreements explicitly cover manifest identity/schema, published and materialized transaction counts, count-cap compatibility, cursor ownership, exact suffix length, reconstruction, and failed-publication preservation.
- The fixture is an authentic **non-DHT foundation**: real memory storage and real file storage with locking, HEAD persistence, and recovery. Its normal paths preserve fundamental storage contracts; failure wrappers inject targeted faults.
- `src/` is untouched relative to HEAD, with no untracked source additions.
- Engine dependency isolation and complete Gate B coverage remain later obligations; the fixture acknowledges the existing indirect DHT load.
