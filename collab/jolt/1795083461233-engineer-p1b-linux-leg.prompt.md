Created-GMT: 2026-11-19 10:17:41 GMT
Session-ID: 01a1f1f9-2d2b-71a2-b88a-1675b81dd975 (RESUME of your P1b implementation thread)
# Task: P1b-linux — platform-qualify the provenance pins for Linux x86_64

Role: VM Runtime Engineer (fix round for the Linux qualification leg)

## Finding (orchestrator, from the shiva Linux leg)

The gate matrix ran on Linux x86_64 (shiva, Ubuntu, mise gambit 4.9.8 from
source, branch gambit-ffi-p1b @ 1e5e6331, clone ~/ci/jolt-ffi-linux). Results:
gambitkernel PASSED on Linux; gambitffi and gambitcheck FAIL with rc=2 at
`make gambitffi-verify-digests` (Makefile:2087): host/gambit/ffi/
TOOLCHAIN.sha256 pins the macOS install's digests, and the Linux install's
7 paths (bin/gsc, bin/gambuild-C, include/gambit.h, lib/libgambit.a,
lib/libgambitgsi.a, lib/libgambitgsc.a, +1) all FAIL. The fail-closed check
is correct behavior; the pins are platform-scoped by accident.

## Task

1. Make the bridge provenance pins platform-qualified without weakening the
   check: e.g. TOOLCHAIN.<platform>.sha256 keyed by uname (darwin-arm64,
   linux-x86_64), a manifest header recording the platform, and verify-digests
   selecting the file for the running platform — still failing closed when the
   platform's file is missing. Keep digest recording deterministic and
   documented (how to record a new platform's pins).
2. You cannot run on Linux yourself (sandbox): make the change so that a
   recorded Linux manifest is accepted, provide the exact command the
   orchestrator runs on Linux to record the pins (and what it must print), and
   make verify-digests print WHICH manifest file it selected and the platform
   it computed.
3. macOS regression: after your change, the darwin-arm64 manifest must still
   verify green on this machine (run make gambitffi to prove it).
4. Commit on gambit-ffi-p1b (clear message). Never push; touch nothing outside
   /Users/sto/workspace/jolt-ffi-p1b. The orchestrator records the Linux pins
   on shiva and re-runs the Linux matrix; findings-file updates inside
   p1b-stage1/ as before.

Return: Completed-* header, what changed (file:line), the exact Linux
record-command, and the macOS regression gate line.
