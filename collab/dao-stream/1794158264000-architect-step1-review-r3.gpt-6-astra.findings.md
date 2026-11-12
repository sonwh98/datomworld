# Step 1 Confirmation Review r3 Findings — SIGN-OFF GRANTED

Created-GMT: 2026-11-08 17:20:10 GMT
Created-Local: 2026-11-09 00:20:10 +07
Coding-Agent: codex
Model: gpt-6-astra
Session-ID: not-applicable (headless codex read-only run)

Completed-GMT: 2026-11-08 17:19:30 GMT
Completed-Local: 2026-11-09 00:19:30 Asia/Ho_Chi_Minh

**Step 1 approved. Proceed to Step 2 — Engine/Composition Separation & Adapters.** The remaining r2 parity blocker is closed.

Reviewed branch `yin-repl-dht-composition` at `b88bad9dd93d233f1d78e5a111444f6c8d86c9aa`, including the uncommitted fixtures and parity test. No files edited; tests were not rerun.

1. **Cross-host parity — verified for the narrow deterministic pin.** The test uses fixed inputs, session identity, and branching factor. It checks shared expected manifests, addresses, and datoms; bulk publication additionally checks root-byte SHA-256 digests and both BLAKE3 and SHA-256 over all 41 stored address/byte pairs.
   - JVM: 1 test, 21 assertions; zero failures/errors (`/tmp/step1-evidence/cross-host/jvm.log`).
   - Dart: Named parity test executed; all tests passed (`/tmp/step1-evidence/cross-host/dart.log`).
   - Node.js: 30 tests, 650 assertions; only the known retry failures (`/tmp/step1-evidence/cross-host/node.log`).
2. **Node failures — exclusively the pinned shell-retry defect.** All 11 failure records belong to `shell-retry-after-refused-publication-promotes-the-whole-suffix`: five memory assertions and six file assertions. Their test/backend/assertion sequence matches the JVM baseline exactly. Node's error corresponds to the same `(name nil)` path in `notice`; the failed promotion and stale-frontier outcomes also match. No additional assertion failures or test errors appear.
3. **Step 1 exit criteria — satisfied under §§8–9.** The previously accepted fixture parameterization, explicit publication-result expectations, and narrower invariant evidence remain valid. Tri-host parity supplies the missing pre-relocation evidence. The known shell acceptance failure remains pinned as permitted by §8.

**This sign-off authorizes Step 2 only.** The real stream protocol, adapter extraction, closure of known failures, §10 Gates A/B, and affected host matrix remain required before boundary merge.
