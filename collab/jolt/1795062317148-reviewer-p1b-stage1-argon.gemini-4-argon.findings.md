Created-GMT: 2026-11-19 04:48:00 GMT
Created-Local: 2026-11-19 11:48:00 ICT (+07)
Coding-Agent: agy
Session-ID: 060c0da6-659b-4804-a175-39b825bf731d
Completed-Local: 2026-11-19 11:43:05 ICT (+0700)
Coding-Agent: agy (Gemini 4 Argon High)
Session-ID: 060c0da6-659b-4804-a175-39b825bf731d
Created-GMT: 2026-11-19 04:25:17 GMT
Created-Local: 2026-11-19 11:25:17 ICT (+0700)
Role: Review (P1b-stage1 Gambit basic FFI tier: `b717f57d` + `8f9047aa` on `a6852ef8`)

### Executive Summary

The bounded P1b-stage1 Gambit FFI tier (`b717f57d93510aac027eeee157c34e3f4c432cbd` and `8f9047aaedf9a9d50893404170488a35b63e6ec9` on base `a6852ef8`) satisfies the governing contract ([1794976827310-architect-gambit-ffi-plan.gpt-6-astra.findings.md](file:///Users/sto/workspace/datomworld/collab/jolt/1794976827310-architect-gambit-ffi-plan.gpt-6-astra.findings.md) and C4 of [1795040000000-reviewer-p1a-confirm-codex.gpt-6.1-sol.findings.md](file:///Users/sto/workspace/datomworld/collab/jolt/1795040000000-reviewer-p1a-confirm-codex.gpt-6.1-sol.findings.md#L130-L158)) across all six review priorities. Every mandatory stage-1 gate, negative control, seed-convergence check, and Chez regression check was independently re-run and passed. No P0 or P1 defects were found. One P2 bug in the non-UTF-8 Latin-1 fallback of `jolt.ffi/read-bytes` and three P3 hygiene/parity items are detailed below.

---

### Findings (P0–P3)

- **P0 / P1**: None.

- **[P2 — F1] `joltffi-read-bytes` Latin-1 fallback calls unbound Chez/R6RS `bytevector->u8-list` on Gambit**
  - **Evidence**: [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L211-L215), [gambitffi-public.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/gambitffi-public.ss#L33).
  - **Detail**: `joltffi-read-bytes` copies `ffi-read-bytes` from [ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/chez/java/ffi.ss#L864-L867):
    ```scheme
    (guard (e (#t (list->string (map integer->char (bytevector->u8-list bv)))))
      (utf8->string bv))
    ```
    `bytevector->u8-list` is a Chez/R6RS procedure that is not shimmed in [prelude-shims.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/prelude-shims.ss) (Gambit's procedure is `u8vector->list`, used in [gambitffi-abi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/gambitffi-abi.ss#L59)). The public test at [gambitffi-public.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/gambitffi-public.ss#L33) only tests valid ASCII (`"abc"`), where `(utf8->string bv)` succeeds and the `guard` fallback is not entered. Evaluating `(let [p (jolt.ffi/alloc 1)] (try (jolt.ffi/write p :uint8 255) (jolt.ffi/read-bytes p 1) (finally (jolt.ffi/free p))))` through `jolt-compile-eval` on booted `gsi` enters the `guard` handler and fails with an uncaught `#<unbound-global-exception>`: `Unbound variable: bytevector->u8-list`. `gsc -warnings -link` also warns `"bytevector->u8-list" is not defined`.
  - **Fix**: Replace `(bytevector->u8-list bv)` with `(u8vector->list bv)` at [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L214) (and optionally alias `bytevector->u8-list` in [prelude-shims.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/prelude-shims.ss)), and add a non-UTF-8 byte sequence witness (`0xff`) to [gambitffi-public.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/gambitffi-public.ss).

- **[P3 — F2] `host-ffi.ss` omits `ffi-str-arg` `nil`-rejection on string-naming arguments**
  - **Evidence**: [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L83-L86), [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L216-L219), [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L256-L259), [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L319-L345), [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L361-L365) vs [ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/chez/java/ffi.ss#L14-L30).
  - **Detail**: On Chez, string-naming parameters (`ffi-type->chez`, `ffi-write-bytes`, `ffi-candidate-list`, `ffi-load-system-library`, `ffi-loaded?`, `ffi-find-symbol`, `ffi-defining-libraries`) pass through `(ffi-str-arg what x)`, which throws `IllegalArgumentException` (`"jolt.ffi: <what> must be a string, got nil"`) on `nil` so `jolt-str-render-one` does not coerce `nil` to `""`. In [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss), `jolt-str-render-one` is called directly without a `jolt-nil?` check at lines `259, 329, 335, 343, 365` (so `(jolt.ffi/find-symbol nil)` probes `dlsym("")` and `(jolt.ffi/load-system-library nil)` probes `"lib.dylib"`), while `joltffi-keyword-name` (`:83`) and `joltffi-write-bytes` (`:217`) pass raw arguments to `string=?` and `string->utf8`.
  - **Fix**: Define `joltffi-str-arg` mirroring `ffi-str-arg` in [ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/chez/java/ffi.ss#L26-L30) and use it at those sites.

- **[P3 — F3] `profiles.ss` and `unbound-allowlist.txt` do not account for `host-ffi.ss` and the 32 dynamic-bridge symbols**
  - **Evidence**: [boot.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/boot.ss#L129-L135), [profiles.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/profiles.ss#L44-L49), [unbound-allowlist.txt](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/unbound-allowlist.txt#L1-L63).
  - **Detail**: [boot.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/boot.ss#L134) splices `(##include "host-ffi.ss")` right after `host-vars.ss`, and [scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss) + [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss) reference 32 `joltffi-*` procedures (`joltffi-dlopen`, `joltffi-malloc`, `joltffi-shim-lookup`, `joltffi-ref-*`, `joltffi-set-*!`, etc.) that are loaded dynamically from `build/gambit-ffi/joltffi-bridge.o1` via `joltffi-ensure-bridge!`. First, [profiles.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/profiles.ss#L44-L49) lists `("seed/prelude.ss" "../chez/post-prelude.ss" "host-vars.ss")` in the `core` group without `"host-ffi.ss"`, so a `kernel` profile generated by [gen-boot.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/gen-boot.ss) would retain `host-ffi.ss`. Second, when `gsc -warnings -link` links the boot unit (as in `make gambitunbound`), those 32 dynamic-bridge `joltffi-*` symbols are reported as undefined in the static boot unit and are not listed in [unbound-allowlist.txt](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/unbound-allowlist.txt).
  - **Fix**: Add `"host-ffi.ss"` to the `core` group in [profiles.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/profiles.ss#L46-L48) and classify the 32 runtime-loaded `joltffi-*` bridge symbols in [unbound-allowlist.txt](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/unbound-allowlist.txt).

- **[P3 — F4] Minor Makefile prerequisite typo on `libjoltffi-unloadable.dylib` and global `dlsym` null-address check**
  - **Evidence**: [Makefile](file:///Users/sto/workspace/jolt-ffi-p1b/Makefile#L2126-L2133), [scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L457-L464) vs [scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L821-L825).
  - **Detail**:
    1. [Makefile](file:///Users/sto/workspace/jolt-ffi-p1b/Makefile#L2126-L2127) lists `$(GAMBIT-FFI-DIR)/joltffi-fixture2.c` as the C prerequisite of `$(GAMBIT-FFI-BUILD)/libjoltffi-unloadable.dylib` instead of `$(GAMBIT-FFI-DIR)/joltffi-dep.c $(GAMBIT-FFI-DIR)/joltffi-unloadable.c` (harmless at runtime because `gambitffi-force` is `.PHONY` and `gambitffi-verify-digests` verifies all nine input hashes in [DIGESTS.txt](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/DIGESTS.txt#L7-L8) before compiling).
    2. `sa-foreign-entry?` ([scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L825)) checks `(and a (not (= a 0)))`, whereas `joltffi-resolve-global` ([scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L460)) checks only `(if (joltffi-dlerror) ...)`. Checking `(if (or (joltffi-dlerror) (not a) (= a 0)) ...)` in `joltffi-resolve-global` keeps the two global-resolution predicates identical.

---

### Review Priority Audit (P1–P6)

1. **P1 — Contracts before claims**:
   - All stage-1 gates derive their verdicts from live fixture/runtime state under [watchdog.sh](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/watchdog.sh), fail closed when `$(GAMBIT_GSI)` or `build/gambit-ffi/joltffi-bridge.o1` is absent ([Makefile](file:///Users/sto/workspace/jolt-ffi-p1b/Makefile#L2146-L2178), [scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L357-L381)), and [gambitffi-public.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/gambitffi-public.ss#L13) explicitly asserts `(jolt.backend-scheme.session/target) => :gambit` on native `gsi`.
   - All C4 MUST-NOT-ASSERT rows remain message-carrying refusals:
     - GC-safe blocking (`sa-foreign-procedure-blocking`, `:blocking`) and blocking+errno (`(__collect_safe)` on `sa-foreign-procedure-native-error`, `{:blocking true :capture-native-error true}`) refuse ([scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L614), [scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L635-L640), [ffi.clj](file:///Users/sto/workspace/jolt-ffi-p1b/jolt-core/jolt/backend_scheme/ffi.clj#L466-L467)).
     - Dynamic closure callables (F6) and foreign-pthread callbacks (`sa-foreign-callable`, `sa-foreign-callable-collect-safe`, `sa-foreign-callable-entry-point`, `:ffi-callable`, `free-callable`, `register-export`, `__arena-callback`) refuse ([scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L647-L658), [scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L839-L840), [ffi.clj](file:///Users/sto/workspace/jolt-ffi-p1b/jolt-core/jolt/backend_scheme/ffi.clj#L788-L796), [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L393-L395)).
     - Object pinning (`sa-lock-object`, `sa-unlock-object`), runtime-signature construction (`sa-foreign-procedure-runtime`, bare `:&` varargs), and AOT/FASL/subprocess (`sa-compile-file`, `sa-make-boot-file`, `sa-fasl-write`, `sa-fasl-read`, `sa-run-process`) refuse ([scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L664-L666), [scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L781-L787), [gambitcheck.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/gambitcheck.ss#L462-L492)).
     - Linux, SMP/MT, Web/JS FFI, and P2/P3 consumer parity remain explicitly disclaimed ([README.md](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/README.md#L3-L6)).

2. **P2 — The errno sequence**:
   - Line-by-line audit of [generated-shims.scm](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/generated-shims.scm#L77-L172) and the Gambit-generated C in `build/gambit-ffi/joltffi-bridge.c:1493-1588,3097-3497` confirms that every capture shim (`joltffi_generated_11`..`18`) executes `int r = f(...); int saved = errno; *(int *)errout = saved;` immediately upon return from `f`, before returning to Gambit's `___BEGIN_CFUN_*_TO_SCMOBJ` result conversion and `___END_CFUN_SCMOBJ_TO_*` cleanup macros.
   - Each call allocates an invocation-local 4-byte cell before entering C, reads it in an ordered `let*` after the wrapper returns, and frees it in a `dynamic-wind` post-thunk ([scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L581-L588)). No global or TLS errno slot exists.
   - Both failure (`EACCES`, `EBADF`, `EAGAIN`) and unnormalized success (`7777` in `joltffi_fx_success_errno` and `joltffi-preserve-control`) are preserved. [gambitffi-errno.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/gambitffi-errno.ss#L6-L35) verifies that `-DJOLTFFI_CAPTURE_TEST` is active (`(joltffi-capture-test-enabled?) => #t`), that the wrong-order control observes `4242`, and that the real generated capture shim observes `EACCES`.

3. **P3 — Shim identity**:
   - [generate-shims.py](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/generate-shims.py#L26-L36) keys shims by `(tuple(args), ret, capture, varargs)` and emits `target_types[:var] + ['...']` in the C function pointer signature whenever `var is not None`.
   - Fixed `s26` (`int (*f)(int, int, int)`, `joltffi_generated_14`) and variadic `s22b` (`int (*f)(int, int, ...)`, `joltffi_generated_11`) are distinct in C and in `joltffi-shim-lookup` ([generated-shims.scm](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/generated-shims.scm#L351-L355), [generated-shims.scm](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/generated-shims.scm#L375-L382)).
   - Both [ffi.clj](file:///Users/sto/workspace/jolt-ffi-p1b/jolt-core/jolt/backend_scheme/ffi.clj#L463-L495) and [scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L600-L627) pass `variadic` and `capture?` through to `joltffi-shim-lookup`.

4. **P4 — Refusal-guard gaps from inventory Q1–Q6**:
   - **Q1/Q2**: `:ffi-layout`, by-value `emit-ffi-fn`, `:ffi-callable`, bare `:&` varargs, and both scoped and by-name `ffi-named-procedure-expr` arms are guarded on `:gambit` in [ffi.clj](file:///Users/sto/workspace/jolt-ffi-p1b/jolt-core/jolt/backend_scheme/ffi.clj#L463-L495), [ffi.clj](file:///Users/sto/workspace/jolt-ffi-p1b/jolt-core/jolt/backend_scheme/ffi.clj#L627-L649), and [ffi.clj](file:///Users/sto/workspace/jolt-ffi-p1b/jolt-core/jolt/backend_scheme/ffi.clj#L782-L796), raising catchable `UnsupportedOperationException` with descriptive messages instead of emitting unbound Chez `define-ftype` / `foreign-procedure` forms ([gambitffi-public.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/gambitffi-public.ss#L26-L32)).
   - **Q3/Q6**: `jolt_parser_worker_free`'s unmarked `pthread_join` (Q3) and `mvn_http.clj`'s unmarked SSL waits (Q6) are explicitly recorded as deferred consumer issues in [README.md](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/README.md#L107-L115), not silently dropped.
   - **Q4/Q5**: `:gambit {:unsafe-prefix ""}` is in [session.clj](file:///Users/sto/workspace/jolt-ffi-p1b/jolt-core/jolt/backend_scheme/session.clj#L77-L82); `sa-foreign-callable` and `sa-foreign-callable-collect-safe` are in [CONTRACT.txt](file:///Users/sto/workspace/jolt-ffi-p1b/host/scheme-adapter/CONTRACT.txt#L274-L275).

5. **P5 — Seeds/provenance**:
   - `make gambitseedcheck` regenerated `host/gambit/seed/{prelude,image}.ss` from `host/chez/seed/{prelude,image}.ss` via [gen-seed.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/gen-seed.ss) with 0 skipped forms, passed the `#3%` and `(foreign-procedure` op-use verification, and matched the checked-in Gambit seeds byte-for-byte.
   - Definition-by-definition comparison of [host/chez/seed/image.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/chez/seed/image.ss) and [host/gambit/seed/image.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/seed/image.ss) against `a6852ef8` (after normalizing gensym counters and line-number metadata) confirmed that the only semantic changes in both seeds are `jolt.backend-scheme.session/target-prims`, `jolt.backend-scheme.ffi/emit-ffi-fn`, `jolt.backend-scheme.ffi/emit-ffi-node`, `jolt.backend-scheme.ffi/ffi-named-procedure-expr`, and the added `jolt.backend-scheme.ffi/gambit-procedure-expr`.
   - The 2 socket-consumer failures (`39/41 passed`) in `test/chez/ffi-native-error-test.sh` were independently reproduced on the untouched `a6852ef8` base Chez seeds (caused by sandbox loopback `ServerSocket` bind restrictions at [ffi-native-error-test.ss](file:///Users/sto/workspace/jolt-ffi-p1b/test/chez/ffi-native-error-test.ss#L240-L292), not by the seed remint).

6. **P6 — Scope discipline**:
   - Commit `b717f57d` touches only the 32 declared implementation/seed/gate files; commit `8f9047aa` touches only `p1b-stage1/`.
   - [DIGESTS.txt](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/DIGESTS.txt) and [TOOLCHAIN.sha256](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/TOOLCHAIN.sha256) pin all nine bridge/fixture/generator/toolchain inputs and are verified before compilation; `gambitffi-force` rebuilds all native artifacts on every run.
   - `git status --short` is clean.

---

### Gates Re-Run and Outcomes

All commands were executed read-only (writes confined to `build/gambit-ffi/` and `/tmp` scratch directories cleaned after execution) using provisioned `/Users/sto/.local/share/mise/installs/gambit/4.9.8/bin/{gsi,gsc}` and `/Users/sto/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme`:

| Gate / Check | Command | Outcome |
|---|---|---|
| `gambitffi-abi` | `sh host/gambit/ffi/watchdog.sh 300 make gambitffi ...` | **PASS** (`78 check(s), 0 failure(s)`) |
| `gambitffi-public` | (via `gambitffi-abi` and `gambitcheck`) | **PASS** (`31 check(s), 0 failure(s) — :gambit compiler + native gsi`) |
| `gambitffi-errno-check` | (via `make gambitffi`) | **PASS** (`9 check(s), 0 failure(s)`) |
| `gambitffi-load-failure-smoke` | (via `make gambitffi`) | **PASS** (`14 check(s), 0 failure(s)`) |
| `gambitffi` (aggregate) | (via `make gambitffi`) | **PASS** (`all stage-1 qualified-tier gates green`) |
| `gambitcheck` | (via `make gambitcheck`) | **PASS** (`0 failure(s) — gambit adapter + shims verified on native gsi`) |
| `gambitkernel` | (via `make gambitkernel`) | **PASS** (`0 failure(s) — booted kernel + natives verified on native gsi`) |
| `gambiteval` | (via `make gambiteval`) | **PASS** (`0 failure(s) — jolt compiles and evaluates on native gsi`) |
| `gambitseedcheck` | `sh host/gambit/ffi/watchdog.sh 180 make gambitseedcheck ...` | **PASS** (`gambit seed is current with the sources`) |
| `gambitgencheck` | (via `make gambitgencheck`) | **PASS** (`records-gambit.ss is current with the records files`) |
| `gambittwins` | (via `make gambittwins`) | **PASS** (`13 call-position macro(s), every one twinned in eval-fns.ss`) |
| `adaptercheck` | (via `make adaptercheck`) | **PASS** (`149 contract names checked against the combined env`) |
| `gambitvars` | `gsi host/gambit/unbound-vars.ss < /dev/null` | **PASS** (`3625 var cell(s) after boot, 4 unbound (4 allowlisted)`) |
| `gambitstatics` | `sh host/gambit/seed-statics.sh` | **PASS** (`19 static(s) and 30 constructor(s); 11 unresolved (11 allowlisted)`) |
| Chez `ffi-binding-test.ss` | `scheme --script test/chez/ffi-binding-test.ss` | **PASS** (`74/74 passed`) |
| Chez `ffi-widths-test.sh` | `sh test/chez/ffi-widths-test.sh .../scheme` | **PASS** (`86/86 passed`) |
| Chez `ffi-native-error-test.sh` (current seeds) | `sh test/chez/ffi-native-error-test.sh .../scheme` | **39/41** (same 2 loopback-socket rows blocked by sandbox `bind`) |
| Chez `ffi-native-error-test.ss` (untouched `a6852ef8` base seeds) | `python3 p1b-stage1/evidence/prepare-base-seed-comparison.py` + `scheme --script /tmp/p1b-base-errno/errno.ss` | **39/41** (reproduces exact same 2 loopback-socket failures on base seeds) |
| Negative controls | `python3 p1b-stage1/evidence/negative-checks.py` and `gsi p1b-stage1/evidence/no-bridge.ss` in `/tmp` | **PASS** (corrupted digest rejected before compile; watchdog returns `124`; missing bridge fails closed with documented message) |

RECOMMENDATION: ACCEPT-WITH-FIXES (1. Fix P2 F1 in `host/gambit/host-ffi.ss:214` by replacing unbound `bytevector->u8-list` with `u8vector->list` in `joltffi-read-bytes` and adding a non-UTF-8 `read-bytes` check to `host/gambit/ffi/gambitffi-public.ss`; 2. Optionally address P3 F2–F4: `nil`-guard string-naming args in `host/gambit/host-ffi.ss`, add `"host-ffi.ss"` to `host/gambit/profiles.ss` + bridge symbols to `host/gambit/unbound-allowlist.txt`, and fix the `libjoltffi-unloadable.dylib` prerequisite list in `Makefile:2126-2127`).

