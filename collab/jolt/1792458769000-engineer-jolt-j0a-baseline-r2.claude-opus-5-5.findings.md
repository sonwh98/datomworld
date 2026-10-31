Created-GMT: 2026-10-20 01:12:49 GMT
Created-Local: 2026-10-20 08:12:49 +07
Coding-Agent: claude
Session-ID: 8f45af4f-0557-4cfa-a375-78525ee6a40e
# Findings: J0a round 1 fixes after review (F1, F2, F4, F5)
Role: Stream & Network Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-20 08:12:49 +07 | Status: done | Rationale: fix accepted review findings

Review: `collab/1792458308000-reviewer-jolt-j0a-baseline.qwen3.8-max.findings.md`.
Worktree: `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0a`. Logs are in its `build/j0a-logs/`.
Changes are uncommitted and nothing was staged.

## Finding -> change

| finding | change |
|---|---|
| F1: wrong `:cmake-path` | `jolt-toolchain.edn:44` now records the observed path `~/.local/share/mise/installs/cmake/4.4.4/cmake-4.4.4-macos-universal/CMake.app/Contents/bin/cmake`. This matches `bootstrap-warm-r2.log:2` and the round-1 report's Environment section. |
| F2: origin URL not verified | `verify!` in `src/dev/jolt_bootstrap.clj` now fails unless `git -C dir remote get-url origin` equals the declared `:origin`. **Negative run:** I set origin to `https://github.com/example/not-jolt` and got exit 1, `… has origin https://github.com/example/not-jolt, declared https://github.com/jolt-lang/jolt` (`bootstrap-neg-origin.log`). I then restored origin to `https://github.com/jolt-lang/jolt`. |
| F4: missing `-Stree` log; latency quotes | `stree.log` holds the output of `jolt -Srepro -Stree -A:jolt`: nothing printed, exit 0 (the file is just `exit 0`), so the dependency tree is empty. The round-1 latencies come from the logs: `usleep(200 ms)` measured **205 ms** (`smoke-1.log`) and **200 ms** (`smoke-2.log`). I had reported 201/204, which came from runs whose output I did not save. This round's run measured 210 ms (`smoke-r2.log`). |
| F5: dirty check blind to gitignored files | Comment added above the dirty check in `verify!`: "Known gap (input to the §6.4 gate hardening): `status --porcelain` does not see changes to gitignored files inside the clone." Behaviour is unchanged. |
| F3 (orchestrator action) | Not mine to fix. Recording the owner's §13.2 disposition of the exact-Chez pin in the design doc is still an orchestrator action. |

## Commands and exit codes (foreground, in the worktree)

| command | exit | log |
|---|---|---|
| `git -C build/tools/jolt remote set-url origin https://github.com/example/not-jolt` then `bb bootstrap:jolt` | **1** (expected) | `bootstrap-neg-origin.log` |
| `git -C build/tools/jolt remote set-url origin https://github.com/jolt-lang/jolt` (restore) | 0 | n/a |
| `bb bootstrap:jolt` (warm) | **0**, real 7.18 s | `bootstrap-warm-r2.log` |
| `bb smoke:jolt` | **0**, `SUMMARY ok`, 6 PASS + 2 RECORD, real 7.71 s | `smoke-r2.log` |
| `. build/tools/jolt-env.sh && "$JOLT_HOME"/bin/jolt -Srepro -Stree -A:jolt` | 0, empty tree | `stree.log` |
| `mise exec -- cljstyle check src/dev/jolt_bootstrap.clj jolt-toolchain.edn` | 0 | n/a |

## Files changed this round
- `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0a/jolt-toolchain.edn` (F1)
- `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0a/src/dev/jolt_bootstrap.clj` (F2, F5)
- New untracked logs: `build/j0a-logs/bootstrap-neg-origin.log`, `bootstrap-warm-r2.log`, `smoke-r2.log`, `stree.log`
