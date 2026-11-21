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

## P1d-r3 — Gambit FFI cache, progress, and watchdog

Created-GMT: 2026-11-21 06:04:54 GMT
Created-Local: 2026-11-21 13:04:54 ICT (+07)
Session-ID: 01a1f988-e9dc-75b3-adff-847824c691c5
Completed-GMT: 2026-11-21 06:27:55 GMT
Completed-Local: 2026-11-21 13:27:55 ICT (+07)
Tree: feat/p1d-uniform-launcher@e0ddd815, committed locally
Base: origin/master@41ab9b38

### Changes

1. `build/gambit-ffi/<digest>/` holds the compiled bridge and three dylibs.
   The key covers the FFI sources, build recipe and toolchain. The launcher
   verifies every artifact against the recorded SHA-256 values before reuse.
   A new boot runtime directory receives verified copies of those artifacts.
2. The bridge build reports Scheme-to-C translation, C compilation, dynamic
   linking and each fixture build on stderr before running the stage.
   Separate messages identify the compiled Yin boot and Yin namespace loading.
3. Each bridge and fixture compilation stage runs through the pinned
   watchdog with a 600-second deadline.

### macOS arm64 verification

Both cache directories were absent in the recreated feature worktree before
the cold run. Both invocations used the same pinned Jolt checkout and
`bb yin-repl --backend jolt-gambit --no-state --index-store mem`.
The harness waited for the literal `yin> ` prompt, submitted `(+ 1 2)`,
then `(quit)`, and required `yin> 3`, `yin> Bye` and exit 0.

| Run | Native child identity | First prompt | Clean exit |
|---|---|---|---|
| Empty caches | 311.050 s | 995.525 s | 998.414 s |
| Cached second invocation | 0.639 s | 684.043 s | 686.931 s |

The cold FFI phase finished at 4.282 s. The phase markers were 0.764 s for
Scheme-to-C, 1.632 s for C compilation, 3.490 s for linking, and 3.718 s
for the first fixture. The separate boot build took
306.749 s. On the second invocation, the FFI cache hit appeared at 0.492 s
and the native child reported `jolt-target: gambit` at 0.639 s.
No compiler stage ran on the second invocation. The second invocation began
after the first had published both caches and entered Yin namespace loading.

The warm launch still took 684.043 s to reach `yin>`. After the Scheme boot
and FFI caches are ready, the pinned `source-loader.ss` interprets the Yin
application namespaces in each process.

- Compared the inode, nanosecond mtime, size and SHA-256 of 35 files across
  both caches before and after the warm run. The snapshots matched.
- Staged the verified bridge for two distinct boot paths without recompiling
  it. Deliberately corrupting one staged copy triggered a verified replacement.
  Gambit loaded the replacement, and the bridge exports matched those from
  the original pinned recipe.
- `bb bootstrap:jolt` verified pinned Jolt `f22df3c7` and all seven submodules,
  printed `jolt smoke (+ 1 2) => 3`, and exited 0.
- Both `bb smoke:jolt-target` targets reported the expected child identity
  and result `3` and exited 0.
- `cljstyle check` and `git diff --check` passed. The shared launcher retains
  the pinned Linux fixture link flags. Runtime verification covered macOS.

DONE


## P1d-r4 — Dart peer startup note and Gambit startup timings

Created-GMT: 2026-11-21 06:29:28 GMT
Created-Local: 2026-11-21 13:29:28 ICT (+07)
Session-ID: 01a1f988-e9dc-75b3-adff-847824c691c5
Completed-GMT: 2026-11-21 06:33:17 GMT
Completed-Local: 2026-11-21 13:33:17 ICT (+07)
Tree: feat/p1d-uniform-launcher@7aa029eb, committed locally

- The launcher flushes Dart's ready frame to stdout, then writes the Transit
  input guidance to stderr. It forwards the remaining stdout bytes and
  inherits stdin. `bb yin-repl --help` displays the same Dart guidance.
- The Gambit capability notes and launcher help now report the measured
  macOS profile: about 16 minutes to `yin>` on first launch, cached native
  Gambit in 0.6 seconds, and about 11 minutes to the prompt while the pinned
  loader interprets Yin namespaces from source.

### Verification

- `bb yin-repl --backend dart` with empty stdin matched the direct peer's
  stdout bytes and exited 0. The merged capture printed the ready frame first
  and the diagnostic on the next line. Separate captures located the
  diagnostic on stderr.
- The live Transit pipe matched the direct peer's ready and acknowledgment
  bytes, evaluated `(+ 1 2)` to `3`, and exited 0 on `:quit`.
- `bb yin-repl --backend jolt-gambit` printed the timing notes after both
  capability notes and then reported `jolt-target: gambit`. The r4 check
  stopped deliberately at 10 seconds during namespace loading. R3 verified
  full cold and cached sessions with `yin> 3` and clean exits.
- `bb yin-repl --help` displayed the Dart message and both Gambit timing lines.
- `bb smoke:jolt` reported `SUMMARY ok`. Both `bb smoke:jolt-target` targets
  verified their identities and returned `3`.

DONE


## P1d-r5 — interactive Gambit namespace progress

Created-GMT: 2026-11-21 06:44:33 GMT
Created-Local: 2026-11-21 13:44:33 ICT (+07)
Session-ID: 01a1f988-e9dc-75b3-adff-847824c691c5
Completed-GMT: 2026-11-21 07:00:22 GMT
Completed-Local: 2026-11-21 14:00:22 ICT (+07)
Tree: feat/p1d-uniform-launcher@847d7cae, committed locally

The interactive launcher enables the pinned Gambit loader's trace and
forwards stderr line by line. It reports a running count of completed
namespaces, with elapsed times measured from observed load and ready
events. During a long load it reports the innermost active namespace about
every 15 seconds. The completion of `jolt.gambit.yin` marks the end of
startup namespace loading. An explicit `JOLT_TRACE_SOURCE=1` also forwards
the original epoch-stamped trace.

### Verification on macOS arm64

- `bb yin-repl --backend jolt-gambit --no-state --index-store mem` ran under
  a 1,500-second timeout with stdout and stderr captured separately. The
  first progress line arrived at 1.559 s. There were 139 numbered
  completions and 16 loading updates, with a longest measured interval of
  15.010 s between progress lines.
- The stderr summary arrived before the first stdout prompt at 715.563 s.
  The harness sent `(+ 1 2)` and `(quit)` after the prompt. Stdout
  contained `yin> 3` and `yin> Bye`. The process exited 0.
- A separate bounded `JOLT_TRACE_SOURCE=1` startup emitted both original
  epoch-stamped load/ready lines and the new progress lines on stderr.
- `bb smoke:jolt-target --target gambit` verified identity and result `3`.
  `bb smoke:jolt` reported `SUMMARY ok`. `bb bootstrap:jolt` verified the
  pinned checkout and printed `jolt smoke (+ 1 2) => 3`.

### Full stderr progress from the default run

```text
yin-load [0] jolt.gambit.yin loading
yin-load [1] clojure.string ready (1.7s)
yin-load [2] dao.host.bytes ready (1.3s)
yin-load [3] jolt.scheme ready (0.7s)
yin-load [4] dao.host.hash ready (0.9s)
yin-load [5] dao.host.text ready (2.3s)
yin-load [6] dao.stream ready (2.2s)
yin-load [7] dao.stream.base64 ready (0.9s)
yin-load [8] dao.stream.observe ready (0.6s)
yin-load [9] dao.host.integer ready (2.3s)
yin-load [10] dao.host.float ready (5.8s)
yin-load [11] dao.host.number ready (1.4s)
yin-load [12] dao.jing.cbor ready (17.6s)
yin-load [13] dao.jing ready (31.4s)
yin-load [14] yang.antlr.cst ready (0.5s)
yin-load [15] yang.stage ready (0.6s)
yin-load [16] yang.python.antlr.parser ready (1.9s)
yin-load [17] yin.repl.host.common ready (0.3s)
yin-load [18] dao.stream.waitset.cadence ready (0.7s)
yin-load [19] dao.stream.waitset.driver ready (0.3s)
yin-load [20] dao.pretty ready (1.0s)
yin-load [21] dao.stream.ringbuffer ready (1.9s)
yin-load [22] yang.frontend ready (1.7s)
yin-load [23] yang.php.antlr.prelude ready (0.1s)
yin-load [24] dao.stream.observer ready (1.7s)
yin-load [25] dao.datom ready (0.6s)
yin-load [26] dao.data.btree.cursor ready (3.1s)
yin-load [27] dao.data.arrays ready (1.0s)
yin-load [27] dao.data.btree loading (16.1s elapsed)
yin-load [28] dao.data.btree ready (24.5s)
yin-load [29] dao.data.btree.storage ready (28.7s)
yin-load [30] dao.jing.content.step ready (4.5s)
yin-load [31] dao.posix.file ready (0.9s)
yin-load [32] dao.jing.file ready (3.8s)
yin-load [33] dao.jing.content.driver ready (1.8s)
yin-load [34] dao.jing.coordinate ready (11.1s)
yin-load [35] dao.space.index ready (12.0s)
yin-load [36] dao.space.query.execution ready (1.8s)
yin-load [36] dao.space.query loading (71.8s elapsed)
yin-load [37] dao.space.query ready (86.7s)
yin-load [38] yin.vm.effect ready (0.5s)
yin-load [39] yin.vm.values ready (1.6s)
yin-load [39] yin.vm loading (104.5s elapsed)
yin-load [40] yin.vm ready (111.8s)
yin-load [41] yin.repl.ast-index ready (115.2s)
yin-load [42] dao.space.transactor ready (2.3s)
yin-load [43] dao.stream.memory-log ready (1.5s)
yin-load [44] yin.repl.publication ready (1.0s)
yin-load [45] dao.stream.apply ready (2.4s)
yin-load [46] clojure.set ready (1.6s)
yin-load [47] dao.stream.waitset ready (1.5s)
yin-load [48] dao.stream.codec ready (1.3s)
yin-load [49] dao.data ready (2.3s)
yin-load [50] yin.vm.telemetry ready (2.3s)
yin-load [51] yin.vm.ffi ready (6.8s)
yin-load [52] dao.jing.content ready (0.8s)
yin-load [53] yin.vm.semantic.code ready (9.6s)
yin-load [53] yin.vm.debruijn loading (14.9s elapsed)
yin-load [54] yin.vm.debruijn ready (22.5s)
yin-load [55] yin.vm.ucf ready (0.1s)
yin-load [56] yin.vm.semantic.scan ready (23.6s)
yin-load [56] yin.vm.debruijn-code loading (15.0s elapsed)
yin-load [57] yin.vm.debruijn-code ready (17.6s)
yin-load [57] yin.vm.semantic.linearize loading (15.0s elapsed)
yin-load [58] yin.vm.semantic.linearize ready (16.5s)
yin-load [58] yin.vm.debruijn-register-code loading (15.0s elapsed)
yin-load [59] yin.vm.debruijn-register-code ready (17.9s)
yin-load [60] yin.vm.semantic.analysis ready (1.1s)
yin-load [61] yin.vm.semantic.derive ready (5.3s)
yin-load [62] dao.space.transact ready (5.6s)
yin-load [63] yin.vm.ledger ready (7.4s)
yin-load [64] yin.vm.module ready (7.9s)
yin-load [64] yin.vm.linker loading (121.7s elapsed)
yin-load [65] yin.vm.linker ready (126.7s)
yin-load [65] yin.vm.engine loading (151.6s elapsed)
yin-load [66] yin.vm.engine ready (158.4s)
yin-load [67] yin.vm.ast-walker ready (170.4s)
yin-load [67] yin.vm.macro loading (185.5s elapsed)
yin-load [68] yin.vm.macro ready (192.0s)
yin-load [69] yin.repl.index ready (203.5s)
yin-load [70] yin.repl.query ready (5.1s)
yin-load [71] dao.jing.mem ready (1.1s)
yin-load [72] clojure.edn ready (1.0s)
yin-load [73] dao.space.store.fs ready (1.7s)
yin-load [74] dao.space.store ready (4.8s)
yin-load [75] yin.repl.store ready (6.9s)
yin-load [76] dao.host.sign ready (0.2s)
yin-load [77] dao.posix.random ready (0.0s)
yin-load [78] yin.vm.linker.random ready (0.1s)
yin-load [79] yin.vm.linker.sign ready (2.4s)
yin-load [80] yin.repl.state ready (5.8s)
yin-load [81] yin.vm.data ready (9.5s)
yin-load [82] yin.vm.debruijn-register-effects ready (4.7s)
yin-load [83] yin.vm.debruijn.register ready (12.9s)
yin-load [84] yin.vm.debruijn.stack ready (8.0s)
yin-load [85] yin.vm.encoder ready (4.6s)
yin-load [86] yin.vm.integer ready (9.0s)
yin-load [87] yin.vm.linker.admission ready (2.9s)
yin-load [88] yin.vm.linker.pair ready (7.4s)
yin-load [89] yin.vm.semantic ready (6.2s)
yin-load [89] yin.repl loading (414.2s elapsed)
yin-load [90] yin.repl ready (419.2s)
yin-load [91] dao.stream.cbor.boring ready (6.3s)
yin-load [92] dao.stream.cbor ready (6.7s)
yin-load [93] dao.stream.middleware ready (3.6s)
yin-load [94] dao.stream.remote ready (14.1s)
yin-load [94] dao.lease loading (14.9s elapsed)
yin-load [95] dao.lease ready (15.0s)
yin-load [96] dao.stream.remote-pair ready (2.9s)
yin-load [97] dao.stream.remote-route ready (25.3s)
yin-load [98] dao.stream.ws ready (8.2s)
yin-load [99] dao.stream.ws-project ready (5.9s)
yin-load [100] dao.stream.remote-channel ready (61.4s)
yin-load [101] dao.stream.rpc ready (3.9s)
yin-load [102] dao.stream.ws.upgrade ready (4.3s)
yin-load [103] yin.repl.connect ready (79.7s)
yin-load [104] clojure.walk ready (1.0s)
yin-load [105] yang.tails ready (0.5s)
yin-load [106] yang.core.uast ready (1.5s)
yin-load [107] yang.core.prelude ready (3.9s)
yin-load [108] yang.core.prelude-admission ready (0.7s)
yin-load [109] yang.core.call ready (5.5s)
yin-load [109] yang.clojure loading (20.7s elapsed)
yin-load [110] yang.clojure ready (29.9s)
yin-load [111] yin.repl.frontends ready (30.3s)
yin-load [112] yin.repl.adapter ready (1.6s)
yin-load [113] yin.repl.host ready (0.2s)
yin-load [114] yin.repl.driver ready (5.8s)
yin-load [115] yin.repl.serve ready (3.7s)
yin-load [116] dao.stream.chunks ready (0.9s)
yin-load [117] dao.stream.journal ready (3.4s)
yin-load [118] dao.stream.journal.file ready (0.8s)
yin-load [119] yin.vm.ucf.custody ready (1.7s)
yin-load [120] yin.vm.ucf.ledger ready (7.3s)
yin-load [121] yin.vm.ucf.authority ready (11.0s)
yin-load [122] yin.vm.semantic.frame ready (3.2s)
yin-load [123] yin.vm.ucf.remote ready (5.8s)
yin-load [123] yin.vm.ucf.rows loading (24.1s elapsed)
yin-load [123] yin.vm.ucf.rows loading (39.1s elapsed)
yin-load [124] yin.vm.ucf.rows ready (52.3s)
yin-load [125] yin.vm.ucf.rows.checkpoint ready (52.9s)
yin-load [126] yin.vm.ucf.authority.admission ready (56.4s)
yin-load [127] yin.vm.ucf.authority.completion ready (1.5s)
yin-load [128] yin.vm.ucf.authority.grant ready (3.2s)
yin-load [129] yin.vm.ucf.authority.input ready (1.9s)
yin-load [130] yin.vm.ucf.authority.front ready (8.7s)
yin-load [131] yin.vm.ucf.holder.evidence ready (0.8s)
yin-load [132] yin.vm.ucf.holder.export ready (4.6s)
yin-load [133] yin.vm.ucf.holder.inbox ready (1.3s)
yin-load [134] yin.vm.ucf.holder.reader ready (7.9s)
yin-load [135] yin.vm.ucf.holder.writer ready (3.9s)
yin-load [135] yin.vm.ucf.holder.driver loading (34.0s elapsed)
yin-load [136] yin.vm.ucf.holder.driver ready (43.2s)
yin-load [137] yin.vm.ucf.compose ready (130.2s)
yin-load [138] yin.repl.main ready (680.4s)
yin-load [139] jolt.gambit.yin ready (712.2s)
yin-load complete: 139 namespaces in 712.2s
```

DONE
