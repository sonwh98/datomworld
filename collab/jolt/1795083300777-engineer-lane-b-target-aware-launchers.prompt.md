Created-GMT: 2026-11-19 05:15:00 GMT
Created-Local: 2026-11-19 12:15:00 ICT (+07)
Coding-Agent: glm
Session-ID: zcode-subagent-lane-b
# Task: Lane B — target-aware jolt probe/qualification launchers in datomworld

Role: QA & Verification Engineer
Implementers:
- Model: glm-5.3-flash | Assigned: 2026-11-19 12:15:00 +07 | Status: active | Rationale: mechanical harness work on the flat lane; codex is the FFI implementation lane

## Mission

Make every jolt probe/qualification launcher in datomworld TARGET-AWARE per the
astra plan's delivery mechanics and the codex confirmation C5: every child
process reports which host scheme executed it, drivers assert the expected
target, and a Gambit launcher path exists wherever a Chez one does. A Chez
result must never be able to qualify a Gambit claim.

Work dir: the MAIN datomworld checkout is shared — create a worktree:
git worktree add /Users/sto/workspace/datomworld/.claude/worktrees/lane-b-launchers -b feat/lane-b-target-aware-launchers master
(from a freshly fetched origin/master). Commit there; never push; do not touch
other worktrees or ~/workspace/jolt*.

## Context (verified facts you can rely on; re-verify details in code)

- bootstrap (src/dev/jolt_bootstrap.clj) exports JOLT_CHEZ and JOLT_GAMBIT in
  build/tools/jolt-env.sh (landed master 36cac7aa); JOLT_GAMBIT currently has NO
  consumer.
- jolt's fork now has a working Gambit FFI tier on branch gambit-ffi-p1b
  (github sonwh98/jolt) with make targets: gambitffi, gambitffi-abi,
  gambitffi-errno-check, gambitffi-load-failure-smoke, gambitcheck,
  gambitkernel, gambiteval — driven with GAMBIT_PREFIX=<mise prefix>.
- Hardcoded-Chez sites named by the astra plan (verify each; there may be more —
  sweep with grep for the launcher pattern): bootstrap smoke!, bb.edn tasks
  smoke:jolt / jolt / probe:jolt-crypto / test:jolt-j5 / probe:jolt-j5 /
  calibrate:jolt-j5, native/jolt-crypto/qualify.clj, src/dev/jolt_j5_run.clj,
  src/dev/jolt_test_runner.clj, and any child-spawning code in
  src/dev/jolt_*.clj that shells out to "$JOLT_HOME/bin/jolt".

## Deliverables (all in the worktree)

1. A target-selection seam: one helper (style-match src/dev conventions) that
   resolves the launch command from an explicit target argument/default, e.g.
   :chez -> "$JOLT_HOME/bin/jolt -Srepro -M:jolt" and :gambit -> the Gambit
   host invocation documented by the fork's own gates (READ-ONLY reference:
   /Users/sto/workspace/jolt-ffi-p1b Makefile gambit* targets and
   host/gambit/ffi/README.md — copy the invocation pattern, do not modify the
   fork). Default stays :chez (zero behavior change for existing callers).
2. Every child-spawning probe/qualifier above takes an explicit target option,
   embeds a target-identity line in its output (e.g. "jolt-target: chez" /
   "jolt-target: gambit" printed by the CHILD, not just the driver), and
   ASSERTS the observed child target equals the requested target before
   trusting any pass/fail (fail-closed on mismatch or absent identity line).
3. qualify.clj: --target option; when :gambit, run the same protocol through
   the Gambit path; the qualification record includes :target. (Running the
   Gambit qualification itself is NOT this round — the seam must exist and be
   exercised by at least one smoke-level invocation per target on THIS machine
   using the existing pinned tools; use --dry-run style invocations if a full
   run needs artifacts not present.)
4. bb.edn: only if a new task is genuinely needed (style-match; mise.toml/bb.edn
   are wide inputs — do NOT run suite lanes).
5. Focused verification: run bb bootstrap:jolt (should be unchanged-green),
   smoke:jolt (chez, unchanged), and the new gambit-target invocation in
   smoke form; capture outputs.
6. Findings: /Users/sto/workspace/datomworld/collab/jolt/1795083300777-engineer-lane-b-target-aware-launchers.glm-5.3-flash.findings.md
   (standard header; the full sweep list of launcher sites with file:line and
   what changed at each; verification outputs; anything deliberately NOT
   changed; ends DONE).

## Bounds & rules

- Files owned: src/dev/jolt_*.clj probe/runner files, native/jolt-crypto/
  qualify.clj, bb.edn (only if needed). NOT: src/cljc/**, src/clj/**,
  mise.toml, jolt-toolchain.edn, anything under ~/workspace/jolt*, the fork,
  build/tools/jolt contents.
- Zero behavior change on existing :chez paths (default target preserves
  today's invocations byte-for-byte where possible).
- Foreground commands with explicit timeouts; no suite lanes; focused checks
  only. Final response: Completed-* header + sweep-count + verification line.
