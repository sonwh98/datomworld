Created-GMT: 2026-11-20 15:17:03 GMT
Created-Local: 2026-11-20 22:17:03 ICT (+07)
Coding-Agent: codex (gpt-6.2-sol)
Session-ID: 01a1f988-e9dc-75b3-adff-847824c691c5
# Task: P1d — uniform yin.repl launcher via bb (`bb yin-repl --backend ...`)

Role: VM Runtime Engineer (implementation)
Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-20 22:17:03 ICT | Status: active | Rationale: routing board, assembly on Lane B's landed seam

Completed-GMT: 2026-11-20 15:50:21 GMT
Completed-Local: 2026-11-20 22:50:21 ICT (+07)
Tree: feat/p1d-uniform-launcher@43b1da29, committed locally
Base: origin/master@c24a4b1d
Worktree: /Users/sto/workspace/datomworld/.claude/worktrees/p1d-uniform-launcher

## Implementation

- `bb yin-repl` defaults to JVM and forwards Yin arguments to the selected
  launcher. `--help` lists all five backends and their capabilities. A leading
  `--` after backend selection allows `--help` to reach Yin itself.
- The Lane B seam still constructs the existing Jolt commands byte for byte.
  It also runs terminal children with inherited stdin and stdout. The child
  reports its startup identity on stderr, and the driver checks that line
  before accepting its exit status.
- A fresh JVM checkout generates its missing Java parser classes via the repo
  task. Chez triggers `bb build:jolt-native` when the crypto, file or parser
  library is absent. On the pinned Chez fork, the direct launcher reproduced
  `No such var: poller/c-read` in the waitset. The waitset now binds the same
  POSIX `read` locally and the direct launch passes.
- The Dart executable selects the shipped terminal shell on opt-in from
  `bb yin-repl`. Its direct Transit peer and `--serve` mode keep their existing
  entry points.
- Gambit uses the pinned `gsi` and P1c's `yin-repl-native.ss`, including its
  stdin-reader thread. P1c's `build-yin-boot.py` owns the content digest,
  lock and compiled kernel under `build/gambit-yin/`. The launcher stages and
  builds the qualified FFI bridge in that same worktree cache using the fork's
  build target. The native driver receives the absolute Datomworld source root
  and the caller's arguments.

## G0 on macOS arm64

Each launch below ran in the foreground under a timeout with
`--no-state --index-store mem`. The input was `(+ 1 2)` and `(quit)`, with the
additional Gambit forms recorded below.

| Launch | Effective session command | Child output | Exit |
|---|---|---|---|
| `mise exec -- bb yin-repl` | `clj -M:clj-yin-repl` | `yin-backend: jvm` / `yin> 3` / `Bye` | 0 |
| `mise exec -- bb yin-repl --backend node` | `node target/yin-repl.js` | `yin-backend: node` / `yin> 3` / `Bye` | 0 |
| `mise exec -- bb yin-repl --backend dart` | `build/yin-repl-peer` | `yin-backend: dart` / `yin> 3` / `Bye` | 0 |
| `mise exec -- bb yin-repl --backend jolt-chez` | `"$JOLT_HOME/bin/jolt" -Srepro -M:jolt:jolt-yin-repl` | `jolt-target: chez` / `yin> 3` / `Bye` | 0 |
| `mise exec -- bb yin-repl --backend jolt-gambit` | `"$JOLT_GAMBIT" -e <identity-form> "$JOLT_HOME/host/gambit/yin-repl-native.ss" "$DATOMWORLD_ROOT"` | `jolt-target: gambit` / `yin> 3` / `yin> Bye` | 0 |

The cold Gambit run published the kernel and digest stamp, then reached its
700-second deadline while loading Yin namespaces. The cached run used a
1,500-second deadline and completed in about 12 minutes. Its relevant output
was:

```text
jolt-target: gambit
boot: backend target -> :gambit
Host: jolt / Gambit v4.9.8
yin> 3
yin> Error: unavailable-parser: Yin REPL language :yang/python is not in the frontend catalog; installed: :clojure (ids :yang/clojure)
yin> 'jolt.ffi
yin> 4
yin> 'dao.jing.mem
yin> Error: blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)
yin> 43
yin> Bye
P1D_CHILD_EXIT=0
```

### Gambit terminal verification

An isolated tmux session ran the same launcher under `timeout --foreground`.
`lsof` showed that the native `gsi` child had both fd 0 and fd 1 on
`/dev/ttys010`. The harness waited for the prompt in `tmux capture-pane`,
then used `tmux send-keys -l` and `Enter` for each form, waiting for each
reply before sending the next form.

```text
/dev/ttys010
jolt-target: gambit
yin> (+ 1 2)
3
yin> (+ 19 23)
42
yin> (quit)
Bye
P1D_CHILD_EXIT=0
```

The digest stamp `92c6566ca0891d691d7323e518e689e3dc4697804dd5ff88876d3aa80ff967b2`
and its `kernel.o1` had identical modification times and sizes before and
after the terminal run.

## Regression checks

- `bb bootstrap:jolt` exited 0 with Gambit v4.9.8, Chez 10.4.1, the seven
  verified submodules at `f22df3c7`, and `jolt smoke (+ 1 2) => 3`.
  Bootstrap and both Jolt launchers used a worktree-local copy of the pinned
  checkout via `JOLT_BOOTSTRAP_TEST_DIR` and the generated env file.
- `bb smoke:jolt` exited 0 with six `PASS` lines and `SUMMARY ok`.
- `bb smoke:jolt-target` exited 0 with
  `VERDICT target chez identity "jolt-target: chez" child answered 3: ok`.
- `bb smoke:jolt-target --target gambit` exited 0 with
  `VERDICT target gambit identity "jolt-target: gambit" child answered 3: ok`.
- The direct JVM, Node, and Chez launchers each returned `yin> 3` without
  the opt-in marker. The direct Dart peer emitted both the Transit `:ready`
  message and an accepted `:line` reply and exited 0.
- A missing JVM parser class triggered generation and the explicit
  `--backend jvm` run still returned 3. Launcher help listed the five
  backends. An invalid backend exited 2.
- The terminal identity gate rejected a zero-exit child with a missing or
  mismatched target report and propagated exit 7 for a matching report.
  The original `bb.edn` task definitions and legacy Jolt child commands
  compared equal to the base.
- `cljstyle check` and `git diff --check` passed. Suite lanes were outside
  this verification scope.

DONE


## P1d-r2 — Gemini 3.8 Flash review fixes

Created-GMT: 2026-11-20 16:07:06 GMT
Created-Local: 2026-11-20 23:07:06 ICT (+07)
Session-ID: 01a1f988-e9dc-75b3-adff-847824c691c5
Completed-GMT: 2026-11-20 16:21:20 GMT
Completed-Local: 2026-11-20 23:21:20 ICT (+07)

### Changes

1. `src/cljc/yin/shell/main.cljc` matches master. JVM and Node use their
   shipped entry points. Chez uses the Lane B identity preflight and the
   canonical `-Srepro -M:jolt:jolt-yin-repl` command.
2. `e9fe4f10` contains only the Chez waitset `read(2)` fix and cites
   upstream Jolt `e20e2174bf4661dcb7b721437ff2847c25de429d`
   (Register poller waits directly with the kernel).
3. `e3a816d8` contains the launcher. Gambit only bootstraps with
   `build/tools/jolt-env.sh` missing. Node and Dart check for their
   launch artifacts before invoking their existing builds.
4. `test/yin/repl/slice_peer.cljc` matches master. The Dart launcher
   executes `build/yin-repl-peer` directly and uses its Transit protocol.

### Verification on macOS arm64

| Command | Observed result |
|---|---|
| `bb yin-repl --backend jvm` and default `bb yin-repl` | `yin> 3`, `Bye`, exit 0 |
| `bb yin-repl --backend node` | `yin> 3`, `Bye`, exit 0 on both launches |
| `bb yin-repl --backend dart` | Transit `:ready`, accepted `:line` for `(+ 1 2)`, `:yin.repl.driver/result` text `3`, exit 0 after `:quit` on both launches |
| `bb yin-repl --backend jolt-chez` | `PASS identity gate: child reported jolt-target: chez`, `yin> 3`, `Bye`, exit 0 |
| `bb yin-repl --backend jolt-gambit` | `jolt-target: gambit`, `3` and `42` at the tmux terminal, `Bye`, exit 0 |
| `bb bootstrap:jolt` | Pinned Jolt `f22df3c7` and seven submodules verified, `jolt smoke (+ 1 2) => 3`, exit 0 |
| `bb smoke:jolt` | Six passes, `SUMMARY ok`, exit 0 |
| `bb smoke:jolt-target --target chez` and `--target gambit` | Both target identities verified and both children answered `3`, exit 0 |

Node took **33.01 s** with its bundle temporarily absent and **2.90 s** on
the second launch. Dart took **75.54 s** with its executable temporarily
absent and **0.18 s** on the second launch. These are end-to-end smoke
times. Both second launches preserved the artifact inode, modification time
and size and also passed in the standard sandbox.

The Gambit launcher ran under `timeout --foreground` in an isolated tmux
session. `lsof` showed `gsi` fd 0 and fd 1 attached to `/dev/ttys010`.
After the prompt appeared, the harness sent `(+ 1 2)`, `(+ 35 7)` and
`(quit)` using `tmux send-keys`, waiting for each reply.

```text
/dev/ttys010
jolt-target: gambit
yin> (+ 1 2)
3
yin> (+ 35 7)
42
yin> (quit)
Bye
P1D_CHILD_EXIT=0
```

The Gambit session reused the bootstrap env and the cached kernel. The
boot digest stamp and `kernel.o1` had identical modification times and
sizes before and after. Bootstrap used the isolated worktree copy of
the pinned Jolt checkout.

The existing `bb.edn` task definitions compare equal to the base.
`cljstyle check` and `git diff --check` passed. The verification stayed
within the requested smoke checks.

DONE
