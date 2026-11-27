Completed-GMT: 2026-11-26 13:53:13 GMT
Completed-Local: 2026-11-26 20:53:13 ICT (+07)
Created-GMT: 2026-11-26 11:25:50 GMT
Created-Local: 2026-11-26 18:25:50 ICT (+07)
Coding-Agent: Codex (gpt-6.2-sol)
Session-ID: 01a2179b-8157-7a21-a433-adb815c465d5
Role: VM Runtime Engineer (implementation)
Resume-GMT: 2026-11-26 12:10:48 GMT

# P2b — compiled Yin session startup on Gambit

P2b is based on Jolt `bbd35983ee5d5f7c3816a3fcd7540039aecbb8c4`.
The acceptance input was Datomworld
`7f4898f474d02dc127e5e484254bc44ae7ff88f6` on Darwin arm64 with native
Gambit 4.9.8 and Chez 10.4.1.

## Exit-1 diagnosis

The first equivalence run's retained compiled stderr recorded
`Unbound variable: input`. The launcher passed Gambit's
`open-process` setting as an unquoted variable. Quoting `'input` in
`host/gambit/yin-repl-boot.ss:27` fixed the common entry point. The
intermediate six nonzero rows came from ending the superseded run while
its children waited for the kernel lock. Separately, the initial 600 s
kernel compilation expired on the shared host. The retry used a 1,800 s
watchdog and published all 25 kernel and seed objects. The shell harness
retains the optional explicit launcher and records stdout separately from
stderr. A later attempt had live DHT shell readers when I edited their
script. Those shell wrappers returned 2 even though the captured Gambit
sessions returned 0 and reached `Bye`. I restarted the full gate with
the script fixed and left it untouched during the run.

## Static closure and live session boundary

- `host/gambit/yin-capture.ss:11` captures the regular Gambit reader,
  analyzer and emitter output for `jolt.gambit.yin`, then follows
  `dao.space.dht` for the `dht:` startup path. The current tree resolved
  148 source namespaces for memory startup and 157 including DHT. The
  historical P1c count of 139 described an older Datomworld tree.
  `clojure.core` comes from the existing seed.
- `host/chez/compile-eval.ss:789` keeps the interleaved analyze and execute
  order during the build. The capture records macro-expanded Scheme,
  macro declarations and registration code. Checkpoints preserve reader
  and expander gensym consumption and restore source positions. The
  `gambitseedcheck` byte comparison at `Makefile:1928` checks the separately
  minted Gambit prelude and compiler seed.
- `host/gambit/source-loader.ss:55` resolves the source filename in the
  regular classpath order and invokes the matching native initializer in
  the same namespace and file context. `host/chez/compile-eval.ss:935`
  provides the shared file load frame. Vars, records, macro expanders,
  atoms and runtime closures come to life when their initializers execute.
- Store open, FFI handle creation, terminal input and Yin evaluation remain
  live. `host/gambit/yin-repl-driver.ss:33` drops the startup initializer
  registry when the input owner is created. The DHT probe's lazy calls at
  `test/gambit/fixtures/yin-dht-probe.jolt:15` still load from source.
  `stdlib/jolt/ffi.clj:1751` still provides the live `defcfn` macro,
  with lookups and wrappers created in the runtime. `host/gambit/scheme-adapter-runtime.ss:885`
  and `:891` retain C4 FASL refusal. The cache contains code and the
  artifact manifest. Session state and foreign handles live in each process.

## Staleness and fallback

`host/gambit/build-yin-session.py:36` hashes names and bytes in every
source search root, including shadowed files and the Datomworld overlays.
The key also covers the compiler source, seed emitter, runtime launcher,
FFI bridge and its inputs, and the selected kernel. The kernel key at
`host/gambit/build-yin-boot.py:77` covers the recursive Scheme includes,
both emitted seeds and native toolchain inputs. This covers a change of
string emission or platform-specific FFI selector. The builders recheck
their inputs before atomic publication and validate object digests on reuse.

`JOLT_GAMBIT_YIN_MODE=source JOLT_TRACE_SOURCE=1` selects the interpreted
closure with the existing timestamped load and ready progress.
Auto mode retries from source when compilation or compiled initialization
fails. The equivalence gate records a rejection against the exact native
key if the source reference succeeds and the compiled outcome disagrees.

On a small synthetic closure the two paths returned `yin> 43 seen__2`.
Changing one `.cljc` file produced a new cache key and `yin> 44 seen__2`.
Damaging one disposable cached object caused a rebuild. A rejected
digest selected source and preserved the original reply. A deliberately
throwing initializer in a verified disposable cache also relaunched
from source in a clean Gambit process and preserved the reply.

## Launch timings

Wall time is measured from starting `gsi` through the first visible `yin>`.
Cold includes a cache miss and artifact build. The equivalence processes
ran concurrently on the shared host.

| Run | Local memory | Stock DHT |
| --- | ---: | ---: |
| Cold compiled, including waiting for the kernel and closure | 2765.628 s | 2797.488 s |
| Warm compiled, direct default launcher with `(quit)` | 75.806 s | 91.697 s |
| Warm compiled, direct default launcher with `(quit)`, after the gate | 51.357 s | — |
| Warm compiled, final equivalence run | 90.202 s | 127.932 s |
| Source namespaces over the cached kernel, final equivalence run | 966.598 s | 1069.700 s |

The default launcher selected the verified native cache and exited 0 on
the post-gate launch. The matched REPL comparison is 10.7× faster to the
prompt. Live top-level
initialization still takes time after the cached objects load. The cold
figures came from the run with the later shell syntax failure. Both
compiled launchers had already reached `yin>` and returned their full
responses before that shell-side failure.

## Regression gates

`make -k gambitffi gambitcheck gambitkernel gambiteval gambitsourceload gambitseedcheck gambitgencheck` exited 0. Gambit FFI passed the qualified stage-1 checks, the source-loader gate passed, and generated records and seed output were current. Emitted-head and dead-host checks also passed. See `build/p2b/matrix-completion.log`.

## Completion and equivalence

Committed locally on `gambit-ffi-p2b` as
`4edb34606e0ffd5157027eba84df82d9ce3dc775`. The worktree is clean.

`make -o gambitffi-bridge gambityinequivalence` exited 0 with the
previously verified bridge. The compiled runs all selected key
`075306011ac575e8c80d76691bfb7eb28b7a83b62078a9b30d9e44d73afcd933`.
The source trace showed 148 ready namespaces for the REPL and 157 for
stock DHT. Both versions of the REPL assertion gate and both versions
of the DHT assertion gates passed. The comparisons start at the first
prompt and cover every reply byte through the final newline. The
false rejection from the superseded shell run was archived after the
new gate passed for the exact digest.

Verbatim output from `build/p2b/equivalence-completion.log` follows.

```text
gambityinequivalence receipts: /Users/sto/workspace/jolt-ffi-p2b/build/gambit-yin/equivalence-kq1_skyr
gambityinequivalence: compiled stock exited 0
gambityinequivalence: compiled repl exited 0
gambityinequivalence: source stock exited 0
gambityinequivalence: source repl exited 0
gambityinequivalence: compiled diagnostics exited 0
gambityinequivalence: source diagnostics exited 0
gambityinequivalence: repl replies match byte-for-byte
gambityinequivalence: stock replies match byte-for-byte
gambityinequivalence: diagnostics replies match byte-for-byte
```

DONE
