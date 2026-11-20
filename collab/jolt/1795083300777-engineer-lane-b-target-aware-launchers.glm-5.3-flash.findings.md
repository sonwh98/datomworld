Created-GMT: 2026-11-19 (findings written ~13:30 ICT)
Created-Local: 2026-11-19 13:30:00 ICT (+07)
Coding-Agent: glm-5.3-flash
Lane: B — target-aware jolt probe/qualification launchers in datomworld
Branch: feat/lane-b-target-aware-launchers (worktree .claude/worktrees/lane-b-launchers, off fetched origin/master 3f8cb541)
Commits (local only, never pushed):
- 43ad9742 feat(jolt): target-aware launch seam and smoke:jolt-target (Lane B C5)
- 79baf0b0 feat(jolt-crypto): --target option and fail-closed identity gate in qualify.clj
- 7e33eb1d feat(jolt): child target identity in bootstrap smoke! and probe:jolt-j5

# Design

One seam, `src/dev/jolt_launch.clj` (ns `jolt-launch`, babashka-only like the
rest of src/dev/jolt_*):

- `resolve-target` / `parse-target-arg`: `--target <t>|--target=<t>`, default
  :chez; unknown targets throw.
- `launch-command`: :chez -> the exact existing string
  `. build/tools/jolt-env.sh && export JOLT_TARGET=chez && exec "$JOLT_HOME/bin/jolt" -Srepro -M:jolt[alias] <script> "$@"`;
  :gambit -> `. build/tools/jolt-env.sh && export JOLT_TARGET=gambit && exec "$JOLT_GAMBIT" ... "$@"`.
  `$JOLT_GAMBIT` is the mise-pinned gsi that `bb bootstrap:jolt` already
  writes into jolt-env.sh; the fork's own gates invoke the same binary
  directly (`sh host/gambit/ffi/watchdog.sh 120 "$GAMBIT_GSI" ...` under
  GAMBIT_PREFIX — host/gambit/ffi/README.md). The fork was read only; never
  modified.
- `assert-child-target!`: fail-closed gate — the child's combined output must
  contain its own exact `jolt-target: <target>` line; absent line, mismatched
  line -> throw, and no pass/fail may be trusted.
- `run-child` launches through the seam and returns the result map.

Driver `src/dev/jolt_target_smoke.clj` + bb task `smoke:jolt-target`: a
trivial child per target (chez: `bin/jolt -e` Clojure form; gambit:
`gsi -e` Scheme form), asserts the child identity line and the known answer 3,
exit 0/1. Note: `bin/jolt -e` evaluates Clojure (and prints the result via
prn) — the identity probes use `(println (str "jolt-target: " ...))` there and
`(begin (display ...) ...)` under gsi.

# Sweep list (every launcher site found; file:line at commit 7e33eb1d)

Changed this round (6 files + 2 new):
1. src/dev/jolt_launch.clj (NEW) — the seam.
2. src/dev/jolt_target_smoke.clj (NEW) — per-target smoke driver.
3. bb.edn:39 `smoke:jolt-target` (NEW task); :42-43 qualify:jolt-libcrypto and
   :57-58 probe:jolt-j5 now run `bb -cp src/dev` (needed to require the seam
   namespace; invocation otherwise unchanged).
4. native/jolt-crypto/qualify.clj:48 (--target/--dry-run parse), :61-73
   (gambit refusal / gambit dry-run seam exercise), :75-84 (fail-closed
   identity gate before the provenance child), :102 (:target in the record;
   :107 equality check dissocs :target so the chez answer is unchanged).
5. src/dev/jolt_bootstrap.clj:183 smoke! — identity probe child verified as
   "jolt-target: chez" (patch-fail! otherwise) BEFORE the existing smoke
   expectation; the existing smoke itself is untouched.
6. src/dev/jolt_j5_run.clj:63 cli parse (--target; unknown args exit 2),
   :80-95 target def (:gambit refused fail-closed — the Gambit tier carries no
   native parser wrapper, fork README: P2/P3 consumers remain Chez-only),
   :101-110 fail-closed identity preflight child; the child-spawning `jolt`
   helper's command string itself is byte-identical to master.

Swept and deliberately NOT changed this round (chez default preserved
byte-for-byte; wiring them to the seam is mechanical follow-up per file):
- bb.edn:30 smoke:jolt, :36 jolt, :88 probe:jolt-tcp, :102 probe:jolt-ws,
  :124 test:jolt-host, :186 test:jolt-carrier, :207 test:jolt-cbor-parity,
  :215 test:jolt-text-perf, :221 compare:jolt-host, :227 test:jolt-host-diag,
  :233 test:jolt-json, :239 test:jolt-runner-self, :245 jolt:run
- src/dev/jolt_workload_w_run.clj:138; src/dev/jolt_ws_probe.clj:1189;
  src/dev/jolt_shell_session.clj:59,195,208; src/dev/jolt_j2_lane.clj:466;
  src/dev/jolt_j5_calibrate.clj:128; src/dev/jolt_j5_acceptance.clj:64;
  src/dev/jolt_j0c_stack.clj:49; native/jolt-crypto/probes/run.clj:87;
  src/dev/jolt_udp_probe.sh / jolt_udp_failures_probe.sh (own jolt-binary
  resolution, no jolt-env.sh); usage-comment mentions in jolt_j2_probe.clj,
  jolt_profile_compare.cljc, jolt_j5_budget.clj, jolt_j0c_*.clj,
  jolt_test_runner.clj, jolt_parser_probe.clj, jolt_j5_measure.clj.
Rationale: focused-lane scope; the seam + identity gate + the two named
drivers (qualify.clj, jolt_j5_run.clj) + bootstrap cover the C5 mechanics;
per-child identity lines in every probe script require touching each child
(scripts like jolt_j5_budget.clj), queued as follow-up.

# Verification (all in the worktree, foreground, explicit timeouts)

Environment notes: the shared clone build/tools/jolt sits detached at
697b91ee (another lane's state, clean) — NOT touched. The worktree therefore
bootstrapped its own clone at the pinned b389f1a7 through
JOLT_BOOTSTRAP_TEST_DIR=<worktree>/build/bootstrap-test/jolt; verification
used the worktree-local pinned clone. build/native was symlinked from the
main checkout (wrapper built for layout 3; the master pin expects layout 4).

1. bb bootstrap:jolt — EXIT 0, green: chez 10.4.1 threaded, gambit v4.9.8,
   cmake 4.4.4, "jolt b389f1a7... with 7 submodules verified",
   "jolt-target: chez (child identity verified)", "jolt smoke (+ 1 2) => 3".
   Negative evidence: with the first (broken, Scheme-form) identity probe the
   bootstrap FAILED exactly as designed: "jolt child target identity
   mismatch: expected \"jolt-target: chez\", observed ... unresolved-symbol"
   — the fail-closed gate bites.
2. bb smoke:jolt — EXIT 0, all 6 checks PASS, SUMMARY ok (chez unchanged).
3. bb smoke:jolt-target / --target chez — EXIT 0: "jolt-target: chez", 3,
   "VERDICT target chez identity \"jolt-target: chez\" child answered 3: ok".
4. bb smoke:jolt-target --target gambit — EXIT 0: "jolt-target: gambit", 3,
   VERDICT ok (gsi 4.9.8 via JOLT_GAMBIT from jolt-env.sh). Re-verified green
   after the commit hook's formatter reformatted the files.
5. qualify.clj --target gambit --dry-run — EXIT 0: child identity line +
   "DRY-RUN gambit launch seam via $JOLT_GAMBIT ...; no qualification
   claimed". qualify.clj --target gambit (no dry-run) — EXIT 1 with the
   Chez-tier refusal (fail-closed). bb qualify:jolt-libcrypto (chez) — EXIT 0
   against the symlinked wrapper: "jolt-target: chez", full record with
   :target "chez", "equal to the current :qualified entry".
6. bb probe:jolt-j5 --target gambit — EXIT 1, the designed refusal.
   bb probe:jolt-j5 unavailable (chez) — identity gate PASS
   ("child reported jolt-target: chez"), children launch identically; the 5
   leg FAILs are the pre-existing layout-3-vs-4 wrapper divergence of the
   symlinked build/native artifact (environmental, not a launcher change;
   the child command string is byte-identical to master).

# Deliberately not done

- No suite lanes run (mise.toml/bb.edn wide-input rule); no push; no changes
  outside the owned files; the fork, ~/workspace/jolt*, build/tools/jolt
  contents and other worktrees untouched. src/cljc|clj untouched.
- Gambit qualification itself (running the fork's make gates) is not this
  round; the seam + smoke + dry-run exercise it as far as this machine's
  artifacts allow.
- jolt-env.sh content itself unchanged (JOLT_GAMBIT already exported by
  bootstrap on master; worktree file is gitignored build output).

DONE

# r2 — review fixes (gemini-4-argon ACCEPT-WITH-FIXES; commit c6023670)

P1-1 FIXED — qualify.clj is self-contained: before the ns form it adds
src/dev to bb's classpath itself (babashka.classpath/add-classpath) unless
the seam is already loadable, so the bare
`bb native/jolt-crypto/qualify.clj` that native/jolt-crypto/probes/run.clj
spawns works unchanged (reproduced by the reviewer at EXIT 1; now EXIT 0
both bare and through -cp). probes/run.clj got the same guard (it now
requires the seam). The bb probe:jolt-crypto task additionally gained
*command-line-args* passthrough so --target reaches run.clj. One seam, no
divergent copy.

P1-2 FIXED — remaining brief-named sites converted (23 files in commit
c6023670):
- bb.edn smoke:jolt and jolt now run through the NEW generic driver
  src/dev/jolt_target_run.clj: --script mode runs the repo child through the
  seam and ASSERTS the child's own identity line fail-closed before trusting
  its exit; passthrough mode (bb jolt, arbitrary args) runs the fail-closed
  identity preflight child first. --target gambit is refused fail-closed for
  script mode (a Clojure script cannot run on bare gsi); the Gambit
  host-scheme smoke is bb smoke:jolt-target.
- Drivers converted with --target (default :chez, byte-identical invocations)
  + require-chez! + jolt-launch/preflight! (fail-closed identity gate):
  native/jolt-crypto/probes/run.clj (probe:jolt-crypto), src/dev/
  jolt_j5_acceptance.clj (test:jolt-j5), src/dev/jolt_j5_calibrate.clj
  (calibrate:jolt-j5), src/dev/jolt_j2_lane.clj (probe:test:jolt-j2 modes),
  src/dev/jolt_workload_w_run.clj, src/dev/jolt_j0c_stack.clj,
  src/dev/jolt_shell_session.clj.
- Child-side identity (the seam's child-print-snippet: prints
  "jolt-target: <t>" only when JOLT_TARGET is set, so legacy invocations and
  JVM legs stay byte-identical): src/dev/jolt_smoke.clj, jolt_test_runner.clj,
  jolt_j2_probe.clj, jolt_workload_w.clj, jolt_j0c_stack_child.clj,
  jolt_profile_compare.cljc, jolt_ws_probe.clj, and the native/jolt-crypto
  probe children provenance.clj, ws_crypto.clj, faults.clj, collect.clj,
  random_faults.clj, identity.clj. j2_lane's run-jolt and workload_w_run's
  ProcessBuilder now export JOLT_TARGET into their children.
- jolt_shell_session's REPL children deliberately get NO JOLT_TARGET: the
  J3 transcript contract compares jolt vs JVM output byte-for-byte; an
  identity line would corrupt it (documented in-file).
- tools/jolt-autobahn/launch.sh (bb.edn autobahn:jolt) remains OUT of bounds
  per the brief's file ownership — now listed in the sweep (reviewer P3).

P3 FIXED — bare trailing --target raises a clean
"--target needs a value (chez or gambit)" ex-info (jolt_launch.clj
parse-target-arg), verified.

r2 verification (worktree, foreground, explicit timeouts; post-format
re-runs all green):
- bb bootstrap:jolt (with JOLT_BOOTSTRAP_TEST_DIR=worktree-local pinned
  clone, as in r1) EXIT 0 — "jolt b389f1a7... verified",
  "jolt-target: chez (child identity verified)", smoke => 3. Note: the bare
  task against build/tools/jolt still fails on the shared clone's detached
  697b91ee state (pre-existing divergence another lane left; untouched).
- bb smoke:jolt EXIT 0 (chez, child identity line printed, driver asserted).
- bb smoke:jolt-target EXIT 0 both targets; bb jolt -e '(+ 1 2)' EXIT 0
  (passthrough preflight + child).
- P1-1 repro: bb native/jolt-crypto/qualify.clj (bare) --target gambit
  --dry-run EXIT 0; bb qualify:jolt-libcrypto EXIT 0 ("equal to the current
  :qualified entry", :target "chez" in the record); run.clj's section-10
  qualify leg PASSed inside the full probe:jolt-crypto run.
- bb probe:jolt-crypto (full, after building a fresh layout-4 wrapper in the
  worktree: bb build:jolt-native green, incl. clj -M:antlr-gen --target cpp)
  — identity gate PASS; every gate PASS except ONE environmental FAIL:
  random fault injection (NullPointerException at the child's own
  `@cleanse-var` line) — reproduced WITHOUT the identity line by running the
  pristine HEAD version of the child, so it is the freshly built wrapper vs
  the pinned seed interaction, not this branch.
- bb probe:jolt-crypto --target gambit EXIT 1 (refusal), direct run.clj
  invocation same.
- bb probe:jolt-j5 --target gambit EXIT 1 (refusal); probe:jolt-j5
  unavailable leg: identity gate PASS, 3/5 gates PASS with the fresh
  layout-4 wrapper (up from 0/5 in r1); the two remaining FAILs are the
  child REPL's exit-code semantics in absent/wrong-library roots
  (environmental; child command strings byte-identical to master).
- probe:jolt-j2 manifest gate fails IDENTICALLY on the master main checkout
  (four UNLISTED test namespaces) — pre-existing, shown by a control run;
  the lane's identity gate and child export worked (checked before the
  manifest gate).
- bb probe:jolt-ws closure EXIT 0 (child identity line present in the
  sub-child environment).
- P3: parse-target-arg ["--target"] -> clean "--target needs a value".
- All 24 touched files parse-checked clean with clojure.tools.reader;
  formatter hook ran on commit; key gates re-run green after formatting.

Still deliberately NOT changed: tools/jolt-autobahn/launch.sh (out-of-bounds
owner file), the udp shell probes (own jolt-binary resolution, no
jolt-env.sh), jolt:run passthrough (raw binary access, unchanged by design),
and usage-comment-only mentions. jolt_j5_acceptance's REPL children inherit
the shell_session transcript reasoning only where they compare bytes; the
driver itself is gated and prefighted.

DONE

# r3 — argon confirmation round (commit 5b827231)

P1-A FIXED — jolt_target_run.clj relayed child output with bare print before
System/exit; babashka does not flush buffered stdout on exit, so the child's
output was lost (exit-code-only verification missed it). All relayed output
now goes through emit! (print + flush), stdout and stderr, both modes, plus
flush after every driver println. Verified with actual output below.

P1-B FIXED — the identity snippet sat INSIDE the ns form in
jolt_j0c_stack_child.clj (jolt: "Ill-formed binding list", every j0c child
lane broken at load) — and the same latent defect existed in
jolt_profile_compare.cljc, found in an audit of all nine snippet sites.
Both moved after the ns form, as the other child scripts have it.

OPTIONAL DONE — per-child fail-closed identity asserts added where the
driver already captures child output: jolt_j0c_stack.clj child() asserts
the child's line before reading INFO/OK/ERR; jolt_j2_lane.clj run-jolt
asserts the captured step log and folds a mismatch into a failing step
(exit 99), honoring the never-throw step contract. workload_w_run streams
its worker's output incrementally and keeps the driver preflight only
(documented).

r3 verification (the reviewer's prescribed checks, actual output lines):
- bb smoke:jolt — six PASS lines + RECORDs + "SUMMARY ok", EXIT 0:
    jolt-target: chez
    PASS the closure of yang.antlr.export is exactly it, ... (no transit) [...]
    PASS yang.antlr.packet/code-points "aé😀" = [97 233 128512] [...]
    PASS yang.antlr.packet/utf8-bytes "aé😀" = 7 7
    PASS yang.antlr.export/byte-offsets of [97 233 128128] = [0 1 3 7] [...]
    PASS defcfn :blocking usleep(200 ms) returns 0 after >= 200 ms {:result 0, :ms 201}
    PASS defcfn :blocking strlen ... {:result 7}
    SUMMARY ok
- bb jolt -e '(+ 1 2)' — prints header, preflight ("jolt-target: chez",
  PASS identity gate) AND the child's answer "3", EXIT 0.
- one j0c child end-to-end (with JOLT_TARGET=chez exported, exactly as the
  driver launches it): prints "jolt-target: chez", INFO {...} stack report,
  per-depth "ERR 256 ERR status -7" / "ERR 512 ...", then DONE, EXIT 0 —
  loads and completes; at :gambit the Clojure script fails to load (expected;
  the drivers refuse :gambit before ever launching it).
- bb smoke:jolt-target EXIT 0 chez; bb smoke:jolt-target --target gambit
  EXIT 0 ("VERDICT target gambit identity \"jolt-target: gambit\" child
  answered 3: ok").
- P1-1 repro: bb native/jolt-crypto/qualify.clj --target gambit --dry-run
  EXIT 0 (bare invocation, the run.clj spawn shape).
- Bonus: bb jolt --target gambit -e '(+ 1 2)' EXIT 0 — the passthrough's
  real child ran under gsi ("PASS identity gate: child reported
  jolt-target: gambit").
- Regression sweep after the r3 edits: bb probe:jolt-ws closure EXIT 0;
  bb test:jolt-runner-self EXIT 0 (runner child prints its identity line
  under -M:jolt:jolt-test); bb probe:jolt-crypto full run — every gate PASS
  except the one KNOWN environmental FAIL (random fault injection NPE,
  reproduced in r2 on the pristine HEAD child without any snippet: fresh
  layout-4 wrapper vs pinned seed, not this branch).
- Formatter hook ran on commit; bb jolt -e and bb smoke:jolt re-run green
  after formatting; worktree clean.

DONE

# r4 — shell-session driver identity exception

Completed-GMT: 2026-11-19 17:18:36 GMT
Completed-Local: 2026-11-20 00:18:36 ICT
Session-ID: 01a1f4c7-4bc2-7df1-99ed-d67381f23cb6

Commit `b5eef9b5` on `feat/lane-b-target-aware-launchers`.

- The failing test invokes the shell-session driver with `--host jvm`.
  The driver used to run a Jolt identity probe before resolving the host
  selection. It now passes `:identity-opt-out` only for the JVM-only run
  (`src/dev/jolt_shell_session.clj:251`). The seam skips the probe launch
  in that case and defaults to fail-closed identity verification
  (`src/dev/jolt_launch.clj:166`). Jolt runs still use a separate identity
  probe, and the REPL transcript process keeps its existing launch.
- Audit found no other identity assertion on a transcript child. The J5
  acceptance driver verifies a dedicated identity probe before its byte
  comparisons. Other preflights also verify dedicated probes, and the
  direct assertions receive output from identity-emitting children.

Local verification, foreground with explicit timeouts:

- `clojure -M:test -n yin.repl.shell-session-test` passed with 1 test,
  1 assertion, zero failures or errors, and 15 passing JVM transcript
  fixtures. The first run reached the JVM fixtures and exposed missing
  Java ANTLR classes. The rerun used local copies of the existing PHP,
  Python, and TypeScript classes after matching the parser inputs and
  generator source to the main checkout.
- `bb smoke:jolt` passed with six PASS lines, `jolt-target: chez`,
  and `SUMMARY ok`.
- `bb smoke:jolt-target --target chez` and
  `bb smoke:jolt-target --target gambit` passed. Each child reported its
  target and answered `3`. The first smoke runs exited 0 with Babashka
  cleanup diagnostics from sandbox process inspection. All three checks
  reran cleanly with process inspection enabled.

The focused rerun meets the fix-branch merge bar. The orchestrator owns
the quick tri-host landing gate on shiva. The prior gate already passed
`dao.stream.*` and identified 11 master reds (9 facade chain failures,
1 eval coordinator audit delta, and 1 hash registry lint failure).

DONE
