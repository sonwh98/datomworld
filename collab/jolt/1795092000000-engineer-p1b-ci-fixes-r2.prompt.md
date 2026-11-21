Created-GMT: 2026-11-19 13:27:34 GMT
Session-ID: 01a1f1f9-2d2b-71a2-b88a-1675b81dd975 (RESUME)
# Task: P1b-ci r2 — deadhost + gambitunbound on CI (run 44530323133)

Role: VM Runtime Engineer (continuation)

Progress: your r2 fixes worked where they applied — test 3/4, 4/4, 2/4 and
Android 1/3, 2/3 now PASS (emittedheads + provenance redesign verified live on
CI). macOS (runner capacity — "job was not acquired by hosted runner") and
Windows (MSYS2 ntldd PGP flake) are pure infrastructure, not ours. Two code
findings remain, from CI logs:

F-A (deadhost, Android 3/3 + test 1/4, Makefile:1494): "DEAD — defined and
never referenced: ffi-check-raise-exact, joltffi-convert-ret". On hosts
WITHOUT gambit, the FFI gates are skipped, so these definitions have no
reference sites and the deadhost gate fails. The gate's documented escape:
"if one is reached through a name built at emit time, make sure the stem
appears as a string literal (>= 8 chars)". Apply the correct one per name:
either the stems appear as literals at their emit-time reference sites, or
the definitions move to files only loaded in the Gambit-present path, or they
get allowlisted the way deadhost allows documented cases (check how
dead-host-check.ss allowlisting works and follow it — the bionic shard has no
gambit at all, so "loaded only when gambit present" is the honest fix if the
names are genuinely unreachable there).

F-B (gambitunbound, test 1/4, Makefile:1986): Error 1 on CI's fresh Linux
gambit (/opt/gambit per the workflow). Get the detail from the CI log via
`gh run view --repo sonwh98/jolt --job 135581591607 --log | grep -B10
"Makefile:1986"` — diagnose whether the bridge-symbol allowlist accounting
(r2's 32 entries) or the record-toolchain flow shifted the unbound set, and
fix consistently with how gambitvars passed (3625 cells, 4 allowlisted) on
our provisioned machines. NOTE: /opt/gambit on CI is a DIFFERENT install —
if the unbound count is install-sensitive, the gate needs the same
per-install treatment the provenance check got, or the allowlist must cover
the genuinely-install-dependent names.

Rules as before: macOS regression (make gambitffi + deadhost + gambitunbound
+ emittedheads locally all green), commit on gambit-ffi-p1b, never push, note
in p1b-stage1 findings. Return Completed-* header + per-finding status.
