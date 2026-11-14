Created-GMT: 2026-11-14 (staged pre-landing; the landing push is escalated to the owner/next coordinating seat)
Coding-Agent: interactive (ZCode / GLM seat, yang.php orchestrator)
Session-ID: not-applicable (interactive seat)

# Round record: eval-bridge — eval through a composition-supplied compiler service

## What landed (17 sessions, r1-r16 + the hand-off chain)
- Strict UTF-8 admission + classified refusal (per the astra byte-domain ruling 1794605712000):
  Latin-1 carrier preserved, decode-once at the worker boundary with UTF8(U)=B verification via
  the existing code-point offset table, no parser fork.
- Scope analysis: the Eval token requires dynamic scope (r4).
- The compiler coordinator + PHP-mode source goal + byte/revision identity keys (r5).
- Eval-unit emission, php/eval-run in the caller task, both lowering expression paths converted,
  atomic redeclaration per the astra atomicity ruling 1794615000000 (incremental registration,
  uncatchable guest-fatal collision, no preflight; the 11-fixture matrix).
- Site-set publication/verifier/discovery/derive-verified + the PHP linker stage + composition
  wiring (the include consumer chain, P7-b lineage).
- 22-fixture corpus oracle-recorded against php:8.3.35 (shiva docker); include gate green
  end-to-end through the wired stage.

## Verification
Acceptance fixtures green x4 JVM evaluators at every tip; all eval suites (22 tests/132
assertions baseline + additions) green; additivity lines (shared safepoint, python safepoint
+ linked) green; kondo/cljstyle clean. Post-merge full quick suite on jupiter at the landing
tip: pending (running concurrently with this record).

## Known residuals
- Dart-leg acceptance gated on the VM cljd forged-ref fix (now MERGED to master — the dart
  re-run is queued concurrently).
- The lifecyle record's line-8 item (undefined-class runtime probe) left unconfirmed by the
  earlier malformed probe input; re-probed this round (caught-in-caller confirmed as a
  catchable Error via the probe file).
- The extract-ordering fix (temporary registration) has one behavioral probe (AD vs DA)
  flagged for investigation if it resurfaces.

## Process trail (for the record)
- 3 provider quota walls, 1 content-filter wall, 2 read-only-sandbox stops, 1 shell-less lane:
  every stop honored, every hand-off precise, every increment verified before banking.
- The duplicate-dispatch incident (r9 glm + r10 qwen raced the same tree) is disclosed in the
  r9 findings; corrected process: re-check artifact movement at dispatch moment.
- The known_hosts rewrite incident: restored + fingerprint-verified.

## Merge authority
deepseek-v4-pro round-7-equivalent sign-off (byte domain), astra rulings (atomicity, byte
domain), seat-verified gates, the merge landed 8c438161 on the shared landing branch.
