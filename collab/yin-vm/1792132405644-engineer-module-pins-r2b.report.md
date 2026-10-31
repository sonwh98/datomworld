Created-GMT: 2026-10-16 06:33:25 GMT
Created-Local: 2026-10-16 13:33:25 +07
Coding-Agent: codex
Session-ID: unavailable (provider not exposed)
Role: VM Runtime
Branch: srvm-module-pins
Base: 62121c7e
Brief: collab/1793590000000-engineer-module-pins-r2b.prompt.md

# Module pins r2b: partial implementation, carrier stopped on ambiguity

## Mandatory stop

The brief explicitly says to STOP the affected sub-part if the ruling is
ambiguous, specifically including what `requires` emits for empty maps.
Architect Part B.1.3 says "Absent maps are not emitted when empty", but
immediately mentions that empty trees are still emitted; step 8 says "empty
maps handled as the existing requires keys handle empties". The existing lift
unconditionally emits empty trees, segments and cursor profiles. These produce
different canonical bytes and fixture addresses. I did not choose between
omitting empty pin keys and always emitting both keys with `{}`. An asynchronous
clarification asks for exactly this choice; no answer received at report creation.

## Per-step results

8. STOPPED the carrier collector/emission pending the empty-map decision.
   Independent P3-1 cleanup implemented: lift strips every top-level keyword
   key whose namespace is `yin.admit`, matching the lower's refusal predicate.
   Regression adds `:yin.admit/other` to the sender record and retains the
   decoded-row, refusal-before-attach, relift and installed-entry assertions.
9. Pending step 8: no new pin encoder/decoder/validator or malformed cases.
10. Pending carrier: existing install-admission gate remains; no claim of the
    new collected module/host-export refusal shape.
11. IMPLEMENTED: `bare-state` retains only the admitted-modules composition
    setting under `:config` (there was no `:config` field previously).
    `lowered` and the fenced-recovery call forward
    `(:yin.k/admitted-modules (:config state))`. `initial` documents the setting.
    `parked-installer-needs-composition-admission-test` drives a real checkpoint
    through the public driver and real authority/judge, with and without config.
    Absent config refuses with the expected missing manifest and zero attaches;
    admitted config reaches running with the carried install and attaches.
12. T5 existing coverage retained and exercised; new driver regression added.
    T1-T4/T8 new pin tests pending carrier, T9/T10 additions pending slice.
    No fetch, clock or lease code added to rows/lower. T6/T7 remain out of scope.
13. All five requested pin mutants NOT RUN: the carrier and pin lower are
    stopped, so running these against absent implementations would be meaningless.
    No mutant code left in the worktree.
14. NOT RE-MINTED. No fixtures or addresses changed; old -> new: none.
    I-8 explicitly remains partial, with its stale schema-decision text fixed.
15. Partial docs: known-gaps records R4 landed at origin/master ce76c846,
    driver seam implemented uncommitted, Q5 host-export carrier ruled but empty
    emission unresolved. Continuation Q5, UCF blocker and I-8 no longer claim
    the host-profile slot/schema lacks a ruling. No docs claim pin lower landed.
    Remaining carrier grammar/behavior amendments await implementation.
16. Verification below. No Node/Dart lane invoked; assigned to orchestrator.

Additional P3-4: moved admission prose out of the middle of the lower-task opts
enumeration. P3-5: removed only the explicitly identified trivially true fresh
receiver assertion. All substantive pre-existing assertions retained.

## Verification

- Red regression run (rows-test + holder.driver-test): 159 tests, 2246
  assertions, 12 failures, 1 error. Driver config was not forwarded; fifth
  admission key survived lift and caused downstream refusal. Log:
  `/tmp/module-pins-independent-red.log`.
- Sandboxed focused run: 628 tests, 15822 assertions, 0 failures, 1 error
  in `dao.jing.dht.socket-test/three-real-loopback-sockets-exchange-chunked-store`
  (bind returned nil port). This initial command accidentally named frame/layout
  under ucf rather than semantic; corrected in the unrestricted run.
- Corrected unrestricted focused run: 654 tests, 18548 assertions,
  **0 failures, 0 errors**. Includes requested rows, custody, checkpoint,
  remote, holder.*, safepoint harness, semantic frame/layout, Stage E, linker,
  linker-manifest, mint and `^dao\.jing\..*`. Log:
  `/tmp/module-pins-independent-focused-unrestricted.log`.
- Kondo on all four changed Clojure files: **0 errors, 0 warnings**.
- `git diff --check`: clean.
- Whole unrestricted JVM quick tier (`clojure -M:test -e :slow`):
  **4025 tests, 249902 assertions, 0 failures, 0 errors**, exit 0. Log:
  `/tmp/module-pins-independent-quick-unrestricted.log`.

## Proposed split after completion

1. Carrier/lift/lower code; tests plus fixture re-mint must accompany it to
   produce a coherent passing commit. Docs complete I-8 with old/new addresses.
2. Driver seam and regression are separable from the carrier; this partial work
   verifies that seam against commit 1's existing admission gate.
3. Documentation may be reviewed separately, but must describe actual code and
   fixture state at landing. No staging or commit performed here.

No `src/yang` or `test/yang` edits. No pinned A address, descriptor hash or golden
changed. Existing untracked collab files preserved. No collab commits.

BLOCKED
