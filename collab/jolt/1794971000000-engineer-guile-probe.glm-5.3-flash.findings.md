# Guile target probe: mise installability + Guile porting estimate (probe only)

Status: probe report, 2026-11-18; NO Guile port implemented, per the owner's scope ruling. Only this findings file was written; no repo file (including mise.toml) was edited.

Created-GMT: 2026-11-17 18:30:00
Created-Local: 2026-11-18 01:30:00 +07
Coding-Agent: glm-5.3-flash
Session-ID: zcode-subagent-guile-probe
Role: Compiler & AST Engineer

Result in one line: **Guile 3.0.11 installs and runs on this machine (Homebrew build; the mise asdf plugin `mise-plugins/mise-guile` builds from source but needs 4 env tweaks the plugin does not set, and raw from-source builds segfault on this macOS even with `--disable-jit`); the guile.ss stub's SRFI-18 annotations are all WRONG for modern Guile (SRFI-18 removed since 3.0.x — the real surface is `(ice-9 threads)` + fluids); a Guile port is feasible at roughly 1.0–1.3x the Gambit adapter's effort, ~8–10k handwritten Scheme lines plus ~10 Makefile gates and a `:guile` entry in backend_scheme.clj's `target-prims`.**

## 0. Scope
Probe only, per the owner ruling: (1) can we mise-install Guile and what pin; (2) classify CONTRACT.txt tiers by expected Guile difficulty and flag must-verify rows; (3) produce the porting estimate; (4) Guile-specific risks. Nothing implemented.

## 1. Environment
- jolt clone: `/Users/sto/workspace/datomworld/build/tools/jolt` (worktree `jolt-gambit-host`). Reference materials: `host/scheme-adapter/{CONTRACT.txt,TARGET-CONTRACT.md,THREADS.md,chez.ss,guile.ss}`, `host/gambit/` (completed second target), `Makefile`, `jolt-core/jolt/backend_scheme.clj`.
- Host: macOS (Darwin 25.6.0, arm64), mise 2026.2.19 macos-arm64.
- Guile versions on ftp.gnu.org today: latest 3.0.x = **3.0.11** (then 3.0.10, 3.0.9, ...). Note the lexical trap: `sort -u | tail` shows 3.0.9 last even though 3.0.10/3.0.11 exist.

## 2. Task 1 — mise backend + pin

### 2.1 Guile is not in mise's registry
`mise registry | grep -i guile` → nothing (only chezscheme/gambit variants). Same situation as gambit before it was pinned: a `[plugins]` entry with an asdf plugin at a full-SHA pin is the established pattern (`mise.toml` gambit entry, plugin `joeljuca/asdf-gambit#b8a901e7af845c8b0a0ee1adc5b67687ba4c582b`).

### 2.2 Trustworthy plugin: mise-plugins/mise-guile
- `https://github.com/mise-plugins/mise-guile` — an asdf-compatible plugin under the **mise-plugins org** (the same org that forks the other asdf plugins; it is a fork of indiebrain/asdf-guile which carries a CI badge).
- HEAD at probe time (master): commit **`fc5bbf3e48f5ae76045b99d99c096cf72d8db20a`**. That is the pin SHA I used.
- It downloads the tarball **and the GPG signature** from ftp.gnu.org and verifies before building — same supply-chain shape as the gambit pin.

### 2.3 Probe results (scratch config at /tmp/guile-probe/mise.toml — NOT the repo's)
```toml
[tools]
guile = "3.0.9"   # later 3.0.10

[plugins]
guile = "https://github.com/mise-plugins/mise-guile#fc5bbf3e48f5ae76045b99d99c096cf72d8db20a"
```
`mise trust` then `mise install guile@<v>`. What happened, in order:

1. First failure: `checking for libgmp... no` → needs Homebrew `gmp` etc. passed via the plugin's `GUILE_CONFIG_OPTIONS` env (the plugin runs `./configure --prefix=... ${GUILE_CONFIG_OPTIONS:-""}` then plain `make` — **serial, no -j**).
2. With `GUILE_CONFIG_OPTIONS="--with-libgmp-prefix=... --with-libunistring-prefix=..."`: next failure `configure: error: GNU libunistring is required` → brew `libunistring`.
3. Next failure: `checking for libffi... no` → **pkg-config was missing entirely**; `brew install pkg-config` fixed the libffi probe (brew `libffi` is keg-only).
4. Next failure: `Package requirements (bdw-gc >= 7.2) were not met` — Homebrew `bdw-gc` ships no .pc file, so configure needs explicit **`BDW_GC_CFLAGS="-I/opt/homebrew/opt/bdw-gc/include" BDW_GC_LIBS="-L/opt/homebrew/opt/bdw-gc/lib -lgc"`** env vars.
5. For 3.0.10: GPG verification failed — signer key `4FD4D288D445934E0A14F9A5A8803732E4436885` (Andy Wingo) not auto-retrievable from keys.openpgp.org; `gpg --keyserver keyserver.ubuntu.com --recv-keys 4FD4D288D445934E0A14F9A5A8803732E4436885` imports it once and thereafter `auto-key-retrieve` finds it locally. (3.0.9's key, Ludovic Courtès, auto-retrieved fine.)
6. **3.0.9 cannot build here at all**: `fatal error: 'gc/gc.h' file not found` with an absent bdw-gc, and once bdw-gc 8.2.12 was installed, `static declaration of 'GC_is_heap_ptr' follows non-static declaration` — Guile 3.0.9 is incompatible with libgc 8.2.x (fixed in 3.0.10). **The pin must be 3.0.10 or 3.0.11, never 3.0.9.**
7. With all of the above set, the plugin's serial from-source build of 3.0.10 was still mid-build at the 10-minute mark (configure alone ≈ 3–4 min; full build 15–25+ min serial). The gambit pin comment in mise.toml documents the same from-source latency (~15–20 min) as an accepted cost.
8. **Raw from-source builds of the resulting guile binary segfault silently on this macOS** (even `./libguile/guile --version`), including a rebuild with `--disable-jit`. Homebrew's `guile` 3.0.11 (patched build) runs perfectly. This needs investigation before CI trusts the mise-plugin build on macOS; Linux will likely behave differently and was not probed here.

### 2.4 Working config to record (for the orchestrator to decide)
```toml
# [tools]
guile = "3.0.11"   # NOT 3.0.9 (libgc 8.2.x incompatibility); 3.0.10 also viable
# [plugins] must stay the LAST table (mise.toml comment: a mid-file table broke mise validation)
guile = "https://github.com/mise-plugins/mise-guile#fc5bbf3e48f5ae76045b99d99c096cf72d8db20a"
```
Plus environment the plugin requires but does not set itself (this is a real friction point vs the gambit plugin, which needed none):
```
GUILE_CONFIG_OPTIONS="--with-libgmp-prefix=/opt/homebrew/opt/gmp --with-libunistring-prefix=/opt/homebrew/opt/libunistring"
BDW_GC_CFLAGS="-I/opt/homebrew/opt/bdw-gc/include"
BDW_GC_LIBS="-L/opt/homebrew/opt/bdw-gc/lib -lgc"
PKG_CONFIG_PATH="/opt/homebrew/opt/libffi/lib/pkgconfig:..."   # + pkg-config installed
```
and a one-time `gpg --recv-keys` of the signer key. **Prerequisite brew packages: pkg-config, gmp, libunistring, libffi, bdw-gc.**

Verified working runtime probe (Homebrew guile 3.0.11, since the raw mise build crashes — see risk R1):
```
$ guile --version
guile (GNU Guile) 3.0.11
$ guile -c '(display (+ 1 2))'
3
```
A `[tools]`-level pin could equally point mise at the brew prefix, but the established lane convention is from-source via plugin; the orchestrator should decide between "plugin pin + env prerequisites" vs "brew as a system dependency like GAMBIT_PREFIX does" (the Makefile already takes `GAMBIT_PREFIX ?= $(shell brew --prefix gambit-scheme ...)` — a `GUILE_PREFIX ?= $(shell brew --prefix guile ...)` would be the lowest-friction analogue).

## 3. Task 2 — contract tiers by expected Guile difficulty

Sources: `host/scheme-adapter/guile.ss` (305-line stub, "DESIGN ARTIFACT — NOT LOADABLE CODE", every entry UNIMPLEMENTED), `host/scheme-adapter/CONTRACT.txt` (543 lines, tiers threads → capability-terminal), `TARGET-CONTRACT.md` (pinned semantics narrative).

**Headline finding first: the stub's annotations are stale.** They were written against "Guile: SRFI-18 / (rnrs)" names. Probes on Guile 3.0.11 show:
- `(srfi srfi-18)` is **gone** (deprecated then removed): `(use-modules (srfi srfi-18))` fails; `fork-thread`, `make-thread-parameter`, `mutex-acquire`, `condition-wait` do not exist.
- The real surface is **`(ice-9 threads)`**: `make-mutex`, `lock-mutex`/`unlock-mutex`/`try-mutex`, `make-recursive-mutex`, `make-condition-variable`, `wait-condition-variable`, `signal-condition-variable`, `broadcast-condition-variable`, `call-with-new-thread`, `join-thread`, `all-threads`, `current-thread`, `yield`, `with-mutex`.
- There is **no `make-thread-parameter`**; parameters are fluid-based (`(srfi srfi-39)`/R6RS `parameterize` over `make-fluid`).
- The adapter must therefore implement `fork-thread`, `make-thread-parameter`, and `mutex-acquire`/`condition-wait` spellings as wrappers — which is exactly what an adapter is for, but the stub's "Guile: SRFI-18 fork-thread" one-liners do not describe today's Guile and must not be trusted as-is.

Tier classification (order = implementation difficulty for Guile):

### Easy — direct mappings, low risk
- **system**: `getenv` (posix, string-or-#f), `system` (posix exact exit status), `current-time` (srfi-19 zero-arg — contract pins zero-arg shape, matches), `sleep` (SRFI-18 shape gone; R6RS time-object shape must be wrapped — small).
- **capability-unchecked**: degrade to checked `(rnrs arithmetic fixnums)` ops per contract, or SRFI-4/R7RS accessors; all mechanical. `target-prims :unsafe-prefix ""` degrades emitted code the same way (backend_scheme.clj documents that mapping-to-empty is "safe and slower, never wrong").
- **misc**: mostly native (`gensym`, srfi-111 boxes, srfi-1 iota/list-head, bytevector-ieee for flonum-bits, `sort` — probed: Guile's core `sort` is `(sort lst less?)`, SAME order as Chez's, so even the stub's "must verify ARGUMENT ORDER" worry resolves in favor of a direct wrap; `format` via (ice-9 format) full superset; `hashtable-values/cells` build from (rnrs) hashtable-keys).
- **capability-profile / capability-sample / capability-terminal**: contract degrades all three (#f / '() / 0 answers); stub already marks them degrade. statprof/no-GC-threshold caveats are documented degradations, not work.
- **capability-image**: `sa-fasl-write/read` may RAISE (message-carrying) → `jolt.image` reports unsupported. Cheap (a raise), same as Gambit's demo mode.
- **capability-native-compile**: `compile-file` exists (emits .go); mapping profile-alist is small; `sa-make-boot-file` raises (Guile has no boot-file model — stub says raise, must verify, agrees with TARGET-CONTRACT.md's "cache disables silently" path).

### Medium — real work, bounded
- **threads**: implement the contract names over `(ice-9 threads)` + fluids:
  - `fork-thread`: `call-with-new-thread` + wrapper. **Fork-inheritance probed OK**: Guile fluids carry the creator's current value into a new thread (`child fluid-ref: 77 (parent=77)` with `fluid-set!` before fork). `parameterize` is fluid-based, so a `make-thread-parameter` implemented as "fluid + per-parameter registry, captured at fork" reproduces the pinned inheritance. This is the load-bearing semantics (CONTRACT.txt: agent-worker forks, tap delivery, async callbacks rely on inheritance ALONE) and it appears to come for free — but per-parameter capture must be explicit because Guile's make-parameter documents fresh-per-thread defaults via `%default-fluids`? — the observed inheritance is of the *fluid value*, which the probe confirms; still flag **must verify under `parameterize` layers** (a child forked inside a parameterize must see the bound value, not the base).
  - `get-thread-id`: `current-thread` returns a thread object, **not a number** (probe: `#<thread ...>`). Contract pins a NUMBER distinct per live thread (rt.ss:967 multiplies it into the random seed; lazy-bridge.ss keys an eqv?-hashtable). Adapter: object→fixnum map (e.g. eqv?-hashtable of thread objects to a counter). `sa-thread-id-of` (#f permitted if unreadable, Gambit answers #f) has the same mapping.
  - `mutex-acquire`/`condition-wait`: SRFI-18 spellings over lock-mutex/wait-condition-variable; non-recursive mutexes pinned — Guile offers BOTH `make-mutex` (non-recursive) and `make-recursive-mutex` (distinct), so `make-mutex` maps directly; re-acquire semantics **must verify** (SRFI-18-style same-thread re-acquire blocks; Guile's `lock-mutex` on a held non-recursive mutex blocks — matches, but verify timeout/default args).
  - `memory-order-acquire/release`: Guile pthreads on one OS-thread-per-Scheme-thread share memory, so a fence or no-op decision must be made deliberately (contract permits no-op only for shared-no-memory targets — Guile does NOT qualify; must be real or argued).
- **capability-system sa-* surface** (~14 entries): mostly direct posix (`environ` split, `uname`, `stat:mtim`), GC entries degrade under Boehm (non-moving, no generations — `sa-gc-max-generation` 0, `sa-gc-trip-bytes!` no-op, `sa-lock-object`/`sa-unlock-object` BOTH no-op which is now *sound* unlike Gambit because Boehm is non-moving). `sa-run-process` needs open-process/pipe work.

### Hard — the two real risk centers
- **capability-ffi**: Guile HAS native FFI (`(system foreign)`): probed `dynamic-link` → `<foreign-library>` handle, `pointer->procedure`, `procedure->pointer`, `dynamic-func`, `sizeof`. So a Guile port is the first second-target with a REAL ffi tier (Gambit's demo raises). Work: all ~15 sa-foreign entries, the four `sa-foreign-*` syntax lowerings in backend_scheme.clj, and **`sa-foreign-procedure-native-error` must capture errno atomically at the call boundary** (pinned semantics; Guile's foreign-call error surface must be verified — "must verify" row, and the stub itself says a later read is racy). `sa-foreign-procedure-blocking`: Guile's collector stops the whole process? Boehm stop-the-world — "must verify collect-safety" is load-bearing; may NOT collapse to plain unless proven.
- **capability-continuations / coroutines**: `sa-call-with-escape-continuation`. **Probed: Guile call/cc is genuinely multi-shot** — the probe re-entered a continuation AFTER the capturing call had returned (entered body n=2, r=42). TARGET-CONTRACT.md anticipates exactly this: a spent-flag wrapper is mandatory, and the escape must unwind dynamic-wind (Guile's call/cc DOES honor dynamic-wind on re-entry/unwind — verify with a `finally`-riding test). Fibers tier: same machinery with a scheduler; contract permits the "every name raises → go falls back to OS thread" degradation if continuation discipline can't be guaranteed.

### "must verify" rows flagged from guile.ss + CONTRACT.txt (the rows where Guile semantics plausibly differ from pinned Chez)
1. thread-parameter fork-inheritance (fluid-based; probed favorably at top level, parameterize layers unverified)
2. non-recursive `make-mutex` re-acquire semantics
3. spurious wakeup tolerance of `condition-wait` (contract permits; must not LOSE wakeups)
4. numeric `get-thread-id` mapping (probed: object)
5. sleep shape: R6RS time-object vs Guile seconds
6. errno capture inside the foreign-call boundary
7. `sa-foreign-procedure-blocking` collect-safety under Boehm
8. one-shot escape continuation enforcement + dynamic-wind unwind on escape (multi-shot call/cc probed)
9. `interaction-environment` sees adapter-defined top level — probed OK (`eval '(define probe-var 99)'` then reads it back), but must verify it sees the *adapter's* boot-strapped top level in a script context
10. `void` (recent Guile has it; trivial fallback), `sort` argument order (probed: matches Chez), `sa-file-mtime-ms` units (stat:mtim → ns → ms)

## 4. Task 3 — porting estimate

### 4.1 Calibration: what the Gambit adapter actually was
`host/gambit/` = **11,244 lines total**, of which generated/minted artifacts are `records-gambit.ss` (3,954, generated by gen-records.ss) and `seed/` prelude.ss+image.ss (4,992, minted). **Handwritten ≈ 7,300 lines**:
- kernel/runtime: `rt-core.ss` 1552 (port of chez rt.ss kernel surface), `prelude-shims.ss` 928 (R6RS/Chez spellings Gambit lacks), `scheme-adapter-runtime.ss` 751 (sa-* surface), `hasheq.ss` 546 (safe-op reimplementation), `host-vars.ss` 405, `host-statics.ss` 241, `eval-fns.ss` 144, `boot.ss` 139
- gates/harnesses: `gambitcheck.ss` 603, `kernel-test.ss` 329, `eval-test.ss` 282, `unbound-check.ss` 277 (+ .sh), `gen-boot.ss` 335, `gen-seed.ss` 169, `seed-statics.ss` 159 (+ .sh), `unbound-vars.ss` 130, `profile-test.ss` 79, `gen-records.ss` 77, `boot-probe.scm` 8, plus 3 allowlists
- Makefile: 14 gate targets (`gambitcheck gambitkernel gambiteval gambitseed gambitweb gambitprofile gambitgen gambitgencheck gambitseedcheck gambitunbound gambitvars gambitstatics gambittwins grenadinecheck`-adjacent; per Makefile lines 133–135 and 1778–1930), all detection-gated via `GAMBIT_GSI`/`GAMBIT-SKIP`, wired into `ci` (Makefile line 192).
- backend_scheme.clj: `target-prims {:chez {:unsafe-prefix "#3%"}}` (line 297) is THE table a target swaps; `:gambit` special-cases live at ~6 sites: line 88 comment (Chez-only string natives excluded from Gambit boot), 2790 `once-cleared "#!bwp" vs "jolt-nil"`, 2872 `gambit-merge` (two-clause fixed+variadic λ merge Gambit needs), 2904 `jolt-once-ref` vs vector-ref, 3963 `chez?` direct host-call emit. A `:guile` target takes the generic path like `:gambit` (no `#3%`, no `#!bwp`), so no gambit-merge-equivalent is expected — the sites just need `:gambit`-style generic guards.

### 4.2 Guile estimate
Scheme lines to write: **~8–10k handwritten** (Gambit's ~7.3k handwritten plus a REAL ffi tier (+300–600 lines the Gambit demo raised away), a real `fork-thread`/thread-parameter wrapper layer (+150–250), minus some shims Guile doesn't need; image/profile/sample/terminal still degrade). If seed-minting mirrors gambitgen/gambitseed the generated/minted artifacts add ~5k generated lines (not hand-written).

Gate suite that must exist (the Makefile `guile` targets, mirroring the gambit list, all `GUILE_GUILE`/`GUILE-SKIP` detection-gated like GAMBIT_PREFIX):
- `guilegen` / `guilegencheck` — records generation + staleness check (runs on Chez; gates in CI without guile)
- `guileseed` / `guileseedcheck` — seed mint + byte-for-byte check (runs on Chez)
- `guilecheck` — the guile.ss gate-time assertion pass (contract names probed in `(guile)` + sa-* bindings; the stub's §"gate-time half" describes it; chez.ss helpers port verbatim)
- `guilekernel` — full booted manifest on guile
- `guileeval` — jolt-compile-eval end to end on the guile boot (the "only gate that runs emitted code" lesson from gambiteval: it must be IN ci from day one)
- `guileboot` — full-profile boot reaches BOOT-OK (the "web REPL hung for a week with every gate green" lesson)
- `guileunbound` (+ regen), `guilevars` (+ regen), `guilestatics` (+ regen), `guiletwins`, `guileprofile`
- No `guileweb` (that is Gambit's JS backend; Guile has no analog — the web REPL lane stays Gambit-only)
- `portcheck`/`adaptercheck`/`mirrordrift`/`degradedbacktrace` are target-neutral already and need the allowlist extended, not new gates.

Slice plan (matches how the Gambit lane was staged):
- **P1 — adapter runtime** (fill guile.ss + write `host/guile/{prelude-shims, scheme-adapter-runtime, hasheq, host-vars, host-statics}.ss` equivalents): ~3.5–4.5k lines. Milestone: `adaptercheck` passes against the Guile adapter; the gate-time assertion pass loads on guile. The ffi tier can land in P1 as real (Guile-native) or degrade-then-upgrade; recommend degrade-first to decouple (same message-carrying-raise discipline), then a P1.5.
- **P2 — gates**: guilecheck/guilekernel/guileeval/guileboot + the unbound/vars/statics/twins harnesses + seed mint: ~2.5–3k lines + Makefile/CI wiring. This is where the real debugging time went on Gambit (boot reaching BOOT-OK, eval rows).
- **P3 — lane wiring**: `:guile` in backend_scheme.clj `target-prims` (`unsafe-prefix ""`) + the 6 generic-path site audits; jolt-toolchain.edn/mise.toml pin; CI job with `GUILE_PREFIX` detection; AOT-cache mapping via compile-file.
- **Effort ratio vs the Gambit adapter: ~1.0–1.3x** the Gambit adapter's total effort. Slightly more code (real ffi + threads wrappers) but several offsets: Guile's R6RS/R7RS coverage is broader than Gambit's (fewer prelude-shims), the CONTRACT/gates/emit machinery is now target-shaped (the stub + TARGET-CONTRACT.md + the four mirrordrift/portcheck structural gates exist because Gambit taught them), and fluid-based fork-inheritance may come near-free (probed). The extra 0.3x upper bound covers the one-shot-continuation wrapper, the atomic-errno FFI boundary, and the macOS build fragility (R1/R6).

## 5. Task 4 — Guile-specific risks

- **R1 (highest): from-source Guile segfaults on this macOS.** Both plugin-built and manually-built 3.0.10 guile binaries crash before printing `--version`, including `--disable-jit` rebuilds. Homebrew guile 3.0.11 works. If CI cannot trust the plugin build on darwin-arm64, the mise pin must be paired with a brew dependency or the build patched (as the gambit pin's "known divergence" comment anticipates for toolchains). Linux unprobed.
- **R2: the guile.ss stub's annotations are factually wrong for Guile ≥ 3.0.10** (SRFI-18 removed). Anyone starting the port from the stub's one-liners would chase `(srfi srfi-18)` names that do not exist. The stub must be re-annotated over `(ice-9 threads)` + fluids before P1. Verified probe evidence in §3.
- **R3: version churn.** 3.0.9 does not build against libgc 8.2.x (GC_is_heap_ptr conflict); SRFI-18 was dropped mid-3.0.x; libgc/Boehm interaction has changed across 8.2.x. Pin 3.0.11 and route any bump through the jolt upgrade gate, like chezscheme/gambit.
- **R4: numeric thread ids.** Contract pins numbers; Guile gives objects. The adapter's mapping must be stable for the lifetime of a live thread (eqv?-hashtable) and is used in the random seed — a mis-mapping silently changes RNG behavior.
- **R5: multi-shot call/cc.** Probed re-entry after return. The one-shot wrapper with a spent flag is mandatory and must be exercised by a dedicated gate row (the contract's wording "the target enforces it" is not met by Guile natively).
- **R6: performance.** Guile's VM+JIT is materially slower than Chez native (and on macOS we may be forced to `--disable-jit`, making it worse). jolt's host lane boots a big manifest and compiles the seed; gate wall-times like gambitunbound's "~75s" may grow. Not blocking, but budget CI time.
- **R7: `(guile)` module conventions.** Emitted code + adapter must share the interaction environment; probed that `eval` defines and reads through `interaction-environment`. Residual risk: script-mode bootstrapping (rt.ss owns the load; the Guile equivalent must load the adapter first in every boot path, as TARGET-CONTRACT.md requires).
- **R8: backend_scheme.clj needs a `:guile` target.** At minimum `{:guile {:unsafe-prefix ""}}` in `target-prims` (checked ops everywhere — safe and slower, the documented degradation), plus auditing the 6 `:gambit`/`:chez` conditionals so `:guile` takes the generic path; and the four `sa-foreign-*` syntaxes need Guile `(system foreign)` lowerings if the ffi tier is real (that is the P1.5 decision).
- **R9: GPG/keyservers.** The plugin verifies signatures via auto-key-retrieve from keys.openpgp.org; one signer key (Andy Wingo) had to be fetched from keyserver.ubuntu.com. CI needs the key pre-imported or verification must be made deterministic (pinned keyring) — otherwise fresh CI runners fail mid-install like our first 3.0.10 attempt.
- **R10: plugin build env.** Unlike the gambit plugin (zero env setup), the guile plugin needs `GUILE_CONFIG_OPTIONS` + `BDW_GC_CFLAGS/LIBS` + `PKG_CONFIG_PATH` and five brew packages. That setup script is part of the port's cost and belongs in docs/CI, not in mise.toml comments alone.

## 6. Evidence index (commands + verbatim key outputs)
- `mise registry | grep -i -E 'guile|chez|gambit'` → only chezscheme/gambit rows; no guile.
- Plugin repo: `curl -s https://api.github.com/repos/mise-plugins/mise-guile` → fork of indiebrain/asdf-guile, HEAD master `fc5bbf3e48f5ae76045b99d99c096cf72d8db20a`, README documents `GUILE_CONFIG_OPTIONS`.
- Install failures (verbatim fragments): `checking for libgmp... no`; `configure: error: GNU libunistring is required, please install it.`; `checking for libffi... no` (pre-pkg-config); `configure: error: Package requirements (bdw-gc >= 7.2) were not met`; `gpg: Can't check signature: No public key` (3.0.10, fixed by keyserver.ubuntu.com import of 4FD4D288D445934E0A14F9A5A8803732E4436885); 3.0.9: `static declaration of 'GC_is_heap_ptr' follows non-static declaration`.
- Runtime probes (Guile 3.0.11, Homebrew; script `/tmp/guile-src/probe3.scm`):
  - fork-inheritance: `child fluid-ref: 77 (parent=77)`
  - multi-shot call/cc: `entered body n=1 r=2` then `entered body n=2 r=42` (k invoked after the capturing call returned)
  - `sort '(3 1 2) < : (1 2 3)` (Chez argument order)
  - `eval top: 99` (define through interaction-environment, then read back)
  - `dynamic-link("") → #<<foreign-library> filename: #f handle: #<pointer ...>>` (native FFI present)
  - `(ice-9 threads)` exports (verbatim): `%thread-handler all-threads begin-thread broadcast-condition-variable call-with-new-thread cancel-thread condition-variable? current-processor-count current-thread join-thread letpar lock-mutex make-condition-variable make-mutex make-recursive-mutex make-thread monitor mutex-level mutex-locked? mutex-owner mutex? n-for-each-par-map n-par-for-each n-par-map par-for-each par-map parallel signal-condition-variable thread-exited? thread? total-processor-count try-mutex unlock-mutex wait-condition-variable with-mutex yield`
  - `(srfi srfi-18)` load: `Unbound variable: fork-thread`; `(defined? 'make-thread-parameter)` → `#f`
- Line counts: `wc -l host/gambit/*.ss` → 11244 total; `host/gambit/seed/*` → 4992; `records-gambit.ss` 3954; `host/chez/scheme-adapter-runtime.ss` 2089, `hasheq.ss` 891.
- backend_scheme.clj: line 297–299 `target-prims {:chez {:unsafe-prefix "#3%"}}`; `:gambit` special-case sites at lines ~88, 2790, 2872, 2904, 3963.
- Makefile: gambit gate targets at lines 1713–1930; `ci` list line 192 includes `gambitcheck gambitkernel gambitgencheck gambitseedcheck gambitboot gambiteval gambitunbound gambitvars gambitstatics gambittwins gambitprofile`.

DONE
