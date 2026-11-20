Created-GMT: 2026-11-20 15:17:03 GMT
Created-Local: 2026-11-20 22:17:03 ICT (+07)
Session-ID: pending (capture thread.started.thread_id)
# Task: P1d — uniform yin.repl launcher via bb (`bb yin-repl --backend ...`)

Role: VM Runtime Engineer (implementation)
Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-20 22:17:03 ICT | Status: active | Rationale: routing board: codex primary; assembly on Lane B's landed seam

## Goal (owner-approved design, shape fixed)

One entry point for launching yin.repl on every backend:

```sh
bb yin-repl --backend <jvm|node|dart|jolt-chez|jolt-gambit> [yin args...]
```

Default :jvm. Every backend delegates to its EXISTING launcher (zero behavior
change where a path already exists), builds its prerequisites automatically,
and every child prints the Lane B identity line (`jolt-target: <t>` for the
jolt backends) with the driver asserting it. `--help` lists the backends and
their capability notes (e.g. jolt-gambit: no :yang/python frontend; blocking
FFI refuses inline with the documented message; everything else works).

## Work dir

Worktree off current master: cd /Users/sto/workspace/datomworld && git worktree
add /Users/sto/workspace/datomworld/.claude/worktrees/p1d-uniform-launcher -b
feat/p1d-uniform-launcher origin/master. Commit there; never push. Do not touch
other worktrees, ~/workspace/jolt* (read-only reference allowed), or
build/tools/jolt contents.

## Per-backend contract

- **jvm**: delegate to the existing clj -M:clj-yin-repl path (check bb.edn and
  the README for the canonical invocation). Prereqs: none beyond the repo.
- **node**: ensure `bb build:yin-repl-node` ran, then launch target/yin-repl.js
  with the args.
- **dart**: ensure `bb build:yin-repl-peer` ran, then launch build/yin-repl-peer.
- **jolt-chez**: run bb bootstrap:jolt if build/tools/jolt-env.sh is missing,
  then `jolt -Srepro -M:jolt:jolt-yin-repl` with passthrough args (the README
  path). Child prints jolt-target: chez; driver asserts.
- **jolt-gambit**: run bb bootstrap:jolt (which now requires the mise gambit
  pin), build the compiled Yin boot ONCE via host/gambit/build-yin-boot.py
  (cache under build/gambit-yin/ with a digest stamp — rebuild on change),
  then run the REAL interactive session: the yin-repl-native driver with STDIN
  PASSED THROUGH from the terminal (this is the new piece — the gate uses
  scripted forms; interactive must attach the terminal). Child identity:
  jolt-target: gambit. Passthrough args (--index-store, --serve, etc.) reach
  Yin. Capability note on startup (the unavailable-parser frontend refusal
  already prints inline; do not suppress it).

## Implementation notes

- Use Lane B's seam (src/dev/jolt_launch.clj + jolt_target_run.clj patterns)
  for the two jolt backends — extend, don't fork it.
- Zero-change rule: the default :jvm path and every existing bb task's behavior
  must be unchanged (byte-identical child command construction; new tasks only
  ADD).
- New bb tasks: yin-repl (the entry) plus any internal helpers, style-matching
  bb.edn. bb.edn is a wide input — do NOT run suite lanes; your verification
  set is below.
- The interactive gambit passthrough: reuse the P1c driver's stdin-reader
  thread (host/gambit/yin-repl-driver.ss) — verify it works with a TTY (test
  with a pipe first, then a real interactive run via tmux send-keys if needed;
  document the verification method).

## Verification ladder (build-n-test.md)

- G0 per backend: launch each with a one-expression smoke ((+ 1 2) style) and
  capture the reply — all five backends, locally, foreground with timeouts.
- bb smoke:jolt + bb smoke:jolt-target (both targets) unchanged-green.
- bb bootstrap:jolt unchanged-green.
- NO suite lanes (wide input; the orchestrator runs the tri-host landing gate).

## Deliverables

1. Commits on feat/p1d-uniform-launcher; never push.
2. Findings: /Users/sto/workspace/datomworld/collab/jolt/1795187823308-engineer-p1d-uniform-launcher.gpt-6.2-sol.findings.md
   (standard header; per-backend launch line + verification output; the
   interactive-gambit verification method; ends DONE).
3. Final response: Completed-* header + per-backend status line.

Reference: Lane B findings (collab/jolt/1795083300777-*), P1c findings
(collab/jolt/1795110000000-* + p1c/P1c.findings.md in the fork — fork branches
pushed, read-only via origin or ~/workspace/jolt), yin.repl.md (the doc the
orchestrator updates after this lands).
