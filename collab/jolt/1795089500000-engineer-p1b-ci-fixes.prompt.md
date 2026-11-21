Created-GMT: 2026-11-19 12:50:44 GMT
Session-ID: 01a1f1f9-2d2b-71a2-b88a-1675b81dd975 (RESUME)
# Task: P1b-ci — fix the failures jolt's own CI found on gambit-ffi-p1b

Role: VM Runtime Engineer (fix round; owner reported CI run 44509339647 red)

## Findings (from the CI logs; verify each in source before fixing)

F-A (P1, real stage-1 bug): the emittedheads gate (Makefile:1507 target,
backed by jolt-core/jolt/backend_scheme/names.clj rt-emitted-names) FAILS on
macOS and Linux CI shards: emitted heads "ftype", "joltffi-public-blocking-
raise", "joltffi-public-procedure", "per-call" are not renamed away from user
locals. Your emitter additions introduced unregistered top-level heads. Fix
in names.clj (register or rename per that gate's convention — read how
existing names are handled and follow it; if a head like `ftype` should never
be emitted at the top level, fix the emitter instead). Our local gate set
(gambitcheck/gambitffi) never ran emittedheads — ADD it to the local gate
aggregate (gambitffi target deps) so this class can't escape again.

F-B (P1, design flaw in the provenance check): gambitffi-verify-digests
Error 2 on Android/bionic CI shard 3 — TOOLCHAIN.<platform>.sha256 pins the
RECORDING machine's gambit binaries; CI's fresh Gambit build differs, so a
legitimate install is rejected. The digest check is valid for the BRIDGE
inputs (in-repo fixture/generator files) but invalid across toolchain
installs. Redesign: verify-digests checks in-repo artifact digests always;
for the toolchain binaries, record + compare a PROVENANCE RECORD (platform,
gambit version string, configure summary hash, install path) that CI
regenerates in its setup step via record-toolchain.sh — i.e. the CI workflow
records its own install's manifest before the gates run, and the gates fail
only if the manifest is absent/stale vs the current install, not if the
install differs from yours. Keep fail-closed semantics (missing manifest =
refuse with the record command in the message). Update the fork's GitHub
workflow (.github/workflows — the job that sets up Chez+Gambit) to run the
record step. Also re-check: does the Android/bionic platform even have a
manifest key (bionic is linux-aarch64-ish — uname handling must cover it or
refuse cleanly with a message)?

F-C: Android shard 2's other rows (ffi-load-failure smoke passed, 71 checks
0 failed etc.) looked green until emittedheads — confirm nothing else.

## Rules

macOS regression after each fix (make gambitffi + the emittedheads target
locally). Local gates must include emittedheads from now on. Commit on
gambit-ffi-p1b; never push (orchestrator pushes + watches the next CI run).
Note in p1b-stage1 findings. Return: Completed-* header, per-finding
fixed/not-fixed with file:line, macOS regression line.
