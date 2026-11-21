Completed-GMT: 2026-11-19 17:35:07 GMT
Completed-Local: 2026-11-20 00:35:07 ICT (+07)
Created-GMT: 2026-11-19 17:09:43 GMT
Created-Local: 2026-11-20 00:09:43 ICT
Coding-Agent: Codex (gpt-6.2-sol)
Session-ID: 01a1f4c9-2302-78f0-b33d-90033750687a
Role: VM Runtime Engineer (implementation)

## P1 audit before implementation

Base: `88630634435b090f4b91e2897c8d1c23205e4c81` (`gambit-ffi-p1b`). References below use paths relative to either datomworld (`src/`) or Jolt (`host/`).

- The product alias `deps.edn:95–97` invokes `yin.shell.main/-main`, which hands its storage composition to `yin.repl.main/main!` (`src/cljc/yin/shell/main.cljc:91–97`). The engine has its own `yin.repl.main/-main` calling `main!` with a nil composition (`src/cljc/yin/repl/main.cljc:1204–1257`). The engine entry is the appropriate local-memory slice. `startup` builds state, `boot` constructs the frontend catalog and attaches a driver (`:255–310`, `:588–681`), and `run-owner!` steps it, prints output and flushes prompts (`:1078–1103`). `repl/driver.cljc:172–179` dispatches local input to the Yin engine. `repl.cljc:1343–1350` uses the host reader. The code at `:942–1008` constructs the actual Yin VM, expander, stream and index state. The acceptance witness must reach these entry points and execute on the Yin VM.
- The Jolt owner needs epoch-clock, output/flush, exit, stdin/EOF and a wake seam, plus its host adapter. The current Jolt branch pumps stdin through `wake/sleep-fds!` and uses `System/currentTimeMillis` and `System/exit` (`main.cljc:1224–1250`). The Jolt host shadows the portable `yin.repl.host` and supplies native WebSocket connect/bind/unbind, pump and close rows (`src/jolt/yin/repl/host.jolt:16–34`). The Jolt waitset shadow is also native (`src/jolt/dao/stream/waitset/driver.jolt:163–212`). Gambit already boots the shared host table, reader, printer and compiler (`host/gambit/boot.ss:64–137`), owns real output ports (`host/gambit/rt-core.ss:604`), and exports clock, flush and stdin line/EOF (`host/gambit/host-vars.ss:37–58`). At the base revision, Gambit binds `require`/`use` to refusals (`host/gambit/host-vars.ss:407–408`) even though `host/chez/ns.ss:348–395` has a reusable source-loader hook. The local-memory witness needs a stdin wake seam. Frontends and APIs outside the qualified profile should refuse by capability.
- `src/cljc/yin/repl/frontends.cljc:88–145` installs the hand-written forms-based Clojure frontend. The existing Jolt catalog omits PHP and installs Python only when its native C++ parser is available. For Gambit, only the core frontend is targeted here. The unavailable language diagnostic comes from frontend selection (`src/cljc/yin/repl.cljc:2342–2365`, `:2559–2600`). `src/dev/jolt_shell_session.clj:55–124` is the external subprocess harness for testing shell sessions. Any in-session request for subprocesses must get the adapter's documented refusal. FASL and native compilation remain separate refusals (`host/gambit/scheme-adapter-runtime.ss:33–41`, `:852–881`). The gate's external watchdog can still launch gsi from the controlling shell.
- `jolt.ffi` is booted as native Jolt vars and compiler special forms (`host/gambit/boot.ss:129–137`, `host/gambit/host-ffi.ss`, `host/gambit/ffi/gambitffi-public.ss`). The Yin session has its own primitive/module registry (`src/cljc/yin/repl.cljc:812–871`, `:982–991`), so Yin must explicitly admit its FFI exports. Any bridge must use an explicit Yin host primitive backed by the qualified Jolt API, with a visible result and error-path witness. Stage-1 qualification and C4 bounds govern everything beyond that witness.

## Implementation and qualification

- Native Gambit source loading is opt-in. The launcher puts the companion checkout's Jolt shadows before its portable roots and uses Jolt's existing `require` seam and Gambit compiler (`host/gambit/yin-repl-main.ss:24–41`, `host/gambit/source-loader.ss:19–69`). It opens the qualified bridge from the Jolt checkout before moving the working directory to datomworld. Loaded namespaces, cycles and failed loads have distinct states. `:as-alias` cannot satisfy a subsequent ordinary `require` by itself.
- Large source strings compile on this path. The emitter's `apply str` over a namespace docstring exhausted a Gambit argument frame. The Gambit streaming path preserves rendering and surrogate-pair handling (`host/gambit/host-apply.ss:1–29`). `gambitsourceload` exercised a real `.cljc`/`.jolt` dependency pair, alias resolution, caller namespace, reload and missing-source refusals, paired surrogates, and a 9,000-character source literal (`host/gambit/source-loader-test.ss:14–31`). Gambit's array predicates report false while its Jolt array constructors continue to refuse (`host/gambit/host-vars.ss:238–246`, `:391–397`).
- `make gambityinrepl` is an opt-in acceptance attempt against the external checkout. It feeds the real entry point, caps runtime at 240 seconds, and requires ordered prompt replies for arithmetic, a lambda, a definition used on the next line, language refusal, an FFI result, further evaluation and `Bye` (`Makefile:1985–1987`, `test/gambit/yin-repl-session.sh:10–46`). A zero-exit control returning three replies was rejected. The gate remains outside the existing `gambitffi` aggregate while the Yin path is red.

## What remains blocked

The native attempt reaches the startup closure but never reaches a `yin>` prompt. The first remaining refusal is the top-level `(delay …)` in the stock `jolt.ffi` library (`stdlib/jolt/ffi.clj:1956–1968`, `host/gambit/host-vars.ss:404`). That delay initializes the legacy errno accessor selection. The real Make target exited 2 after the session script reported status 1. Two initial blockers were independently isolated. Stock Gambit first exhausted an argument frame while compiling the `jolt.ffi` namespace documentation. With that path repaired or bypassed for diagnosis, the closure encountered the missing array predicates `clojure.core/bytes?` (`src/jolt/dao/host/bytes.jolt:24–28`) and `array?` (`stdlib/jolt/ffi.clj:1117`). The committed fixes allow the predicates to compile. Actual Jolt byte storage, required by the existing DaoJing byte and CBOR adapters, still needs Gambit host wiring.

A diagnostic probe loaded only `cfn-form`, `foreign-fn`, `cfn`, `def-cfn-form` and `defcfn` from the stock FFI source over the host-provided FFI vars. The same Yin entry then failed with `no top-level Scheme binding: string-length`. The Jolt text shadow binds it eagerly (`src/jolt/dao/host/text.jolt:41–48`), while the Gambit adapter supplies no top-level reflection (`host/gambit/scheme-adapter-runtime.ss:842–850`). Native gsi evaluated `string-length` as a procedure directly and had no `string-normalize-nfc` procedure under the spelling the shadow requires. This isolated probe ran outside the committed launcher.

A static :jolt/:clj `ns`-declaration traversal using the Jolt shadow order reached 164 namespaces. That count describes the require closure. The local default path constructs a DaoJing index store (`src/cljc/yin/repl.cljc:1173–1184`, `src/cljc/yin/repl/store.cljc:140–154`), and DaoJing loads the hash adapter (`src/cljc/dao/jing.cljc:26–34`, `:91–94`). The Jolt hash adapter eagerly loads its native library and declares BLAKE3 and SHA-256 as `:blocking` (`src/jolt/dao/host/hash.jolt:62–78`, `:189–200`). The component probe below verified the exact BLAKE3 declaration's refusal. The store namespace also requires the durable filesystem path eagerly, which reaches an eager `java.io.File` and native wrapper check (`src/cljc/dao/space/store.cljc:21–30`, `src/cljc/dao/space/store/fs.cljc:51–54`, `src/jolt/dao/posix/file.jolt:50–67`). The existing terminal owner expects Jolt's native waitset and WebSocket adapter (`src/cljc/yin/repl/main.cljc:1218–1250`). Python requires the native parser's blocking export (`src/jolt/yang/antlr/cst.jolt:60–74`). A full local session needs Gambit-backed host implementations for the required storage and terminal seams, plus explicit admission of the bounded `jolt.ffi` module into the Yin VM. The direct Jolt FFI result below leaves that guest-module work open.

The original user objective is partial. Jolt source/eval and the stage‑1 FFI tier run under native Gambit 4.9.8 on macOS arm64. `yin.repl` has not started there. The component probes demonstrate the existing subprocess, FASL, native compilation and blocking FFI refusals. Inline Yin refusal and continuation, a native Yin frontend result, and FFI through a Yin guest expression remain unverified. C4's platform, blocking and callable boundaries apply.

## Verbatim Yin gate output

```text
sh test/gambit/yin-repl-session.sh "/Users/sto/.local/share/mise/installs/gambit/4.9.8/bin/gsi" "/Users/sto/workspace/datomworld"
yin.repl startup failed: clojure.core/make-delay is unsupported on the gambit target: delays are not wired up on this target
boot: backend target -> :gambit
gambityinrepl: a complete Yin session was not observed (status 1)
make: *** [gambityinrepl] Error 1
```

The diagnostic launcher with only the FFI declaration heads reached this separate refusal.

```text
yin.repl startup failed: no top-level Scheme binding: string-length
boot: backend target -> :gambit
```

The gate rejected this incomplete control, whose producer returned zero.

```text
boot: backend target -> :gambit
yin> 3
yin> 49
yin> 100
gambityinrepl: the Yin session did not produce the expected replies
```

## Verbatim native Jolt component probe

This used the booted Jolt compiler and native adapter directly.

```text
boot: backend target -> :gambit
FFI fixture load => "build/gambit-ffi/libjoltffi-fixture.dylib"
FFI layout version => 42
DaoJing BLAKE3 declaration => blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)
subprocess => subprocess support is unsupported on the gambit target #object[:object 0x53075d44]
FASL => fasl serialization is unsupported on the gambit target
native compilation => native compilation is unsupported on the gambit target "fixture.ss"
subsequent compiler eval => 43
```

## macOS arm64 regression

The stage‑1 matrix and `gambitsourceload` exited 0 after the P1c changes. The new Yin acceptance target's red verdict is above.

```sh
sh host/gambit/ffi/watchdog.sh 600 make \
  gambitffi gambitcheck gambitkernel gambiteval gambitseedcheck \
  gambitgencheck gambittwins adaptercheck gambitsourceload \
  GAMBIT_PREFIX=/Users/sto/.local/share/mise/installs/gambit/4.9.8 \
  CHEZ=/Users/sto/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme \
  JOLT_REQUIRE_GAMBIT=1
```

```text
gambitffi-abi: 79 check(s), 0 failure(s)
gambitffi-abi: PASS
gambitffi-public: 44 check(s), 0 failure(s)
gambitffi-public: PASS — :gambit compiler + native gsi
gambitffi-errno-check: 9 check(s), 0 failure(s)
gambitffi-errno-check: PASS
gambitffi-load-failure-smoke: 14 check(s), 0 failure(s)
gambitffi-load-failure-smoke: PASS
gambitffi: PASS — all stage-1 qualified-tier gates green
gambitcheck: 0 failure(s)
gambitcheck: PASS — gambit adapter + shims verified on native gsi
gambitffi-public: 44 check(s), 0 failure(s)
gambitffi-public: PASS — :gambit compiler + native gsi
kernel-test: 0 failure(s)
kernel-test: PASS — booted kernel + natives verified on native gsi
eval-test: 0 failure(s)
eval-test: PASS — jolt compiles and evaluates on native gsi
gambitseedcheck: gambit seed is current with the sources
gambitgencheck: records-gambit.ss is current with the records files
gambit eval twins: 13 call-position macro(s), every one twinned in eval-fns.ss
adaptercheck: 149 contract names checked against the combined env
adaptercheck: chez adapter contract satisfied
gambitsourceload: PASS — native source loading and compiler string emission
```

## Delivery

The local `gambit-ffi-p1c` branch in `/Users/sto/workspace/jolt-ffi-p1c` is at `f8741162ed46d9852b42c886c01cc86ababe6e2e` with a clean working tree. No push. The datomworld source checkout was read at `ff274a4eb56340110f0e338ed237ffcd1aad5656`. This findings file is the only datomworld write. Gambit came from `/Users/sto/.local/share/mise/installs/gambit/4.9.8` and the runtime source was read-only.

DONE
