Created-GMT: 2026-10-20 00:51:04 GMT
Created-Local: 2026-10-20 07:51:04 +07
Coding-Agent: claude
Session-ID: 8f45af4f-0557-4cfa-a375-78525ee6a40e
# Findings: J0a round 1 - the minimum jolt baseline (tracked load and toolchain)
Role: Stream & Network Engineer (build/toolchain implementation)
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-20 07:51:04 +07 | Status: done (round 1, macOS arm64) | Rationale: owner routing 2026-10-20: opus-5.5 implements; resumes the spike session

## Verdict

| Baseline item (§7.8 W8 start criterion) | Verdict |
|---|---|
| 1. Pinned jolt + Chez bootstrapped from a tracked declaration | **DONE** (macOS arm64) |
| 2. `:jolt` alias + `src/jolt` root, no overlays/stubs; spike overlays folded | **PARTIAL**: the alias and root are done. The overlay fold was NOT done because it conflicts with the brief (see 2b). |
| 3. `:jolt` loads `yang.antlr.export` and `yang.antlr.packet` without overlays | **DONE** |
| 4. One `jolt.ffi` defcfn `:blocking` smoke call passes | **DONE**. Collect-safety is NOT established (see 4). |

**What this means for J0c:** the §7.8 start criterion asks for three things: pinned bootstrap, the alias loading both namespaces, and the `:blocking` smoke. **All three are met on macOS arm64.** The overlay fold is not part of that criterion.

**Linux x86_64 (shiva): NOT RUN.** shiva also needs Chez 10.4.1 and CMake 4.4.4 provisioned through mise.

- Worktree: `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0a`, branch `jolt-j0a` from master `212c45dd9`. Changes are uncommitted and nothing is staged.
- Logs: `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0a/build/j0a-logs/` (untracked `build/`).

## Environment
- Darwin arm64, macOS 26.6.2, Apple clang 21.0.0, babashka v1.13.223.
- Chez 10.4.1 (threaded `#t`) at `~/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme`.
- CMake 4.4.4: `mise install cmake` was run this round. It went into the shared mise install dir at `~/.local/share/mise/installs/cmake/4.4.4/cmake-4.4.4-macos-universal/CMake.app/Contents/bin/cmake`. This is the only thing installed outside the worktree.

## 1. Pinned jolt and Chez from a tracked declaration: DONE

- **`jolt-toolchain.edn`** (new, tracked, repo root) records:
  - the jolt origin `https://github.com/jolt-lang/jolt`, full SHA `96d7b8662d7c491722445bf976b2e25e36ce5d63`, and commit date;
  - `:tag :none`, nearest release tag `v0.8.23` (`a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5`), describe string `vnightly-16-g96d7b866` (ruling 4);
  - all 7 recursive submodule SHAs (`git submodule status --recursive` on the pinned tree);
  - `:patches []` (default 2: none);
  - Chez `10.4.1` via mise, required threaded;
  - CMake `4.4.4` via mise;
  - the observed macOS paths and compiler.
  - Linux is `nil` (not run). `:native {}` (libblake3, ANTLR4 C++ and libcrypto are later rounds).
- **`mise.toml`** (worktree copy): `chezscheme = "10.4.1"` and `cmake = "4.4.4"`, exact pins. CMake 4.4.4 is what `mise latest cmake` resolved. The main tree's uncommitted `chezscheme = "latest"` was not touched.
  - The brief states the owner confirmed the exact-Chez default. §13.2 W6 also asks for that acceptance to be **recorded in §13.2 of the design doc**. I did not edit docs, so the orchestrator should record it there.
- **`bb bootstrap:jolt`** (new `bb.edn` task, running `src/dev/jolt_bootstrap.clj`):
  - **Toolchain check:** checks Chez and CMake through `mise exec <tool>@<pinned> -- which`. It verifies the exact versions and that Chez is threaded, and never installs anything.
  - **Clone:** if `build/tools/jolt` is absent, it clones the origin, checks out the full SHA detached, and initializes submodules recursively.
  - **Verify:** the HEAD SHA, a clean tree, and all submodule SHAs against the declaration.
  - **Env file:** writes `build/tools/jolt-env.sh` with `JOLT_CHEZ` set explicitly (plus `JOLT_HOME`).
  - **Smoke:** runs `jolt -e '(+ 1 2)'` and expects 3.
  - **Never pulls:** an existing checkout is only verified.
  - Nothing downloads during namespace loading.
- **Evidence:**

| Run | Result | Log |
|---|---|---|
| Cold (`rm -rf build/tools/jolt`, then `bb bootstrap:jolt`) | exit 0, real **153.71 s** | `bootstrap-cold.log` |
| Warm (existing checkout verified, no network) | exit 0, real **7.12 s** | `bootstrap-warm.log` |
| Negative: HEAD moved to `a0ac71c3` | exit 1, `…is at a0ac71c3…, declared 96d7b866…; ordinary runs never pull` | `bootstrap-neg-head.log` |
| Negative: one edit to the checkout's README | exit 1, `has local changes: M README.md` | `bootstrap-neg-dirty.log` |
| Restored | exit 0 | `bootstrap-restored.log` |

## 2. `:jolt` alias and `src/jolt` root: PARTIAL

**2a. Done.**
- `deps.edn` gains `:jolt {:replace-paths ["src/jolt" "src/cljc" "src/clj"] :replace-deps {}}`. It has no `build/antlr` Java classes, no JS/Dart roots, and no dependency coordinates.
- `jolt -Srepro -Spath -A:jolt` gives exactly `…/src/jolt:…/src/cljc:…/src/clj`, in that order (`spath.log`).
- `-Stree -A:jolt` prints nothing, so the resolved dependency set is empty. That was verified, not assumed.
- New tracked root `src/jolt/` holds only a README stating the §4.1 rules. No host override is needed for this closure.
- The alias only lists `src/clj`. The `:jolt` guards on `yin.repl.host` and `dao.space.dht` (§4.2) are not done; they matter once the closure reaches them.

**2b. NOT DONE: the spike overlay fold.**
- The four overlay files (`build/jolt-spike/overlay/`) all modify forbidden namespaces:
  - `dao/jing.cljc`
  - `dao/jing/cbor.cljc`
  - `dao/jing/cbor/boring.cljc`
  - `yin/vm/integer/host.cljc`
- The brief says "do NOT edit shared dao.stream / dao.data / dao.jing / yin.vm source (the exception: none this round)", and folding them needs exactly those edits, or `.jolt` copies of `dao.jing.cbor`, which §4.1 forbids. I followed the explicit prohibition and folded nothing.
- Two of the overlays must not be folded as they are anyway:
  - The `dao.jing` overlay stubs SHA-256 with a throw, and the brief allows no stubs.
  - The `integer/host` overlay's bigint `bit-and`/`bit-or`/`bit-xor` are knowingly wrong (§4.4, §5.1).
- None of the four is needed for the baseline closure (item 3).
- **The orchestrator must decide which slice may edit `dao.jing`/`yin.vm` for the fold (J0a round 2 or J1).** Attribution for that fold: the spike session (claude-opus-5-5, 2026-10-19), `collab/1792412960461-engineer-jolt-yin-repl-spike.claude-opus-5-5.findings.md` section 5.

## 3. `yang.antlr.export` and `yang.antlr.packet` load without overlays: DONE

- `bb smoke:jolt` runs `build/tools/jolt/bin/jolt -Srepro -M:jolt src/dev/jolt_smoke.clj` with the env file sourced. Every run exited 0 with `SUMMARY ok` (`smoke-1.log`, `smoke-2.log`).
- **Closure:** loading `yang.antlr.export` adds exactly `(yang.antlr.export yang.antlr.packet)` to `loaded-libs`. That is asserted.
- **Transit:** none. Nothing from `dao.data`, `dao.stream.transit` or `cognitect.transit` is reached. A separate check found no loaded lib matching `transit|cognitect`. So there is nothing to record as a runtime-loaded transit dependency for this closure.
- **Values:**
  - `packet/code-points "aé😀"` = `[97 233 128512]`. This runs the `:clj` branch, `.codePoints` via jolt's String shim, and passes unchanged.
  - `packet/utf8-bytes` gives 7.
  - `export/byte-offsets [97 233 128512]` gives `[0 1 3 7]`.

## 4. defcfn `:blocking` smoke: DONE (collect-safety not established)

- **Assertions** in `src/dev/jolt_smoke.clj`:
  - `usleep` bound `:blocking` returns 0 after at least 200 ms (observed 201 and 204 ms).
  - `strlen` bound `:blocking` on the UTF-8 C string "aé😀" returns 7.
  - Both PASS.
- **Collect timing, RECORD only (not asserted).** A full collection (`jolt.host/gc-full!`) ran on the main thread while another thread was inside `usleep(400 ms)`:

| Call binding | collect start | collect end | call end |
|---|---|---|---|
| `:blocking` | 50 ms | 118 ms | 400 ms |
| plain (no `:blocking`) | 57 ms | 212 ms | 410 ms |

- **Anomaly:** jolt's own documentation (`stdlib/jolt/ffi.clj:1502-1510`) says a thread in a non-`:blocking` call stays active and the collector waits until the call returns. The plain control did not wait for the call to return. I reproduced this with a raw `Thread` as well as `future`.
- So this observation cannot yet show that `:blocking` is what makes a call collect-safe. I removed my first version of the check, which asserted it, after the control falsified it.
- Possible explanations, none verified: the rendezvous path in `sa-gc-collect` (`host/chez/scheme-adapter-runtime.ss:61-80`), or Chez handling of a thread in `usleep`.
- The long-hash collect-safety gate (§6.3) is later J0a work and will need a mechanism that discriminates.

## Commands run (foreground, in the worktree)
```
git worktree add /Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0a -b jolt-j0a master   # from the repo root
mise trust
mise latest cmake            # 4.4.4
mise install cmake
bb bootstrap:jolt            # cold, warm, two negatives, restored (logs above)
bb smoke:jolt                # x2 (first with a fresh JOLT_CACHE_DIR; source mode keeps JOLT_AOT_CACHE=0, so no cache was written)
. build/tools/jolt-env.sh && "$JOLT_HOME"/bin/jolt -Srepro -Spath -A:jolt
"$JOLT_HOME"/bin/jolt -Srepro -Stree -A:jolt
mise exec -- cljstyle check/fix src/dev/jolt_bootstrap.clj src/dev/jolt_smoke.clj jolt-toolchain.edn deps.edn
```
- **Wall clock:** bootstrap cold 153.71 s, warm 7.12 s; smoke 7.77 s and then 7.45 s. jolt runs from source, so there is no AOT cache and cold and warm are similar.
- **cljstyle:** my new files pass. `deps.edn` and `bb.edn` already fail `cljstyle check` on master (I checked `git show master:deps.edn`), and none of the reported lines are mine.
- **npm ci:** not needed.

## J0a acceptance items NOT done this round (not started)
- Recording the owner's acceptance of the exact-Chez default in §13.2. The value is applied in `mise.toml` and the toolchain file, but this is a doc edit for the orchestrator.
- Folding the spike overlays (2b, blocked by the brief) and the rest of the §4.4 fold/audit sites, including the machine-readable branch inventory and the reached-`src/clj` guard list (`yin.repl.host`, `dao.space.dht`).
- Clean-cache core require of `yin.repl` without overlays. The standard/main dependency audit naming runtime-unexercised dependencies (`cognitect.transit` via `dao.data` and others).
- Reproducing the S arithmetic and the six hashes.
- Vendored native sources: libblake3 and ANTLR4 C++ revisions and hashes, `bb build:jolt-native`, output digests.
- Source/`:jolt/provides`/binary precedence probes.
- `.jolt` in `.cljstyle` `:extensions`, and the kondo `:jolt` feature lane.
- The jolt test runner (`test:jolt` family).
- libcrypto provenance and negative tests, and long-hash collect-safety (see the anomaly in 4).
- The Linux x86_64 platform record on shiva, including provisioning Chez and CMake there.
- Removing overlay paths from classpaths. They were never added here, but the main-tree spike scripts still reference them.

## Files changed (worktree, uncommitted)
- Modified: `bb.edn` (tasks `bootstrap:jolt`, `smoke:jolt`), `deps.edn` (alias `:jolt`), `mise.toml` (chezscheme 10.4.1, cmake 4.4.4).
- Added: `jolt-toolchain.edn`, `src/dev/jolt_bootstrap.clj`, `src/dev/jolt_smoke.clj`, `src/jolt/README.md`.
- Untracked build outputs, not for commit: `build/tools/jolt/` (the pinned clone), `build/tools/jolt-env.sh`, `build/j0a-logs/`.

Nothing in the main tree, `docs/`, `collab/` (except this report), or shared `dao.*`/`yin.vm` source was modified. Nothing was staged, committed or pushed.
