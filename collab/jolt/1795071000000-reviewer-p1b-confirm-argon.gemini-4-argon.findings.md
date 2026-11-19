Created-GMT: 2026-11-19 05:20:00 GMT
Created-Local: 2026-11-19 12:20:00 ICT (+07)
Coding-Agent: agy
Session-ID: 060c0da6-659b-4804-a175-39b825bf731d

Completed-GMT: 2026-11-19 09:57:46 GMT
Completed-Local: 2026-11-19 16:57:46 ICT (+0700)
Coding-Agent: agy (Gemini 4 Argon High)
Session-ID: 060c0da6-659b-4804-a175-39b825bf731d
Role: Review Confirmation (P1b-stage1-r2: `e91f26de` + `1e5e6331` on `8f9047aa`)

### Per-Finding Confirmation (F1–F4)

- **F1 (P2 — `joltffi-read-bytes` Latin-1 fallback unbound `bytevector->u8-list`) — CONFIRMED.**
  - [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L217-L221) now uses `(u8vector->list bv)` in the `guard` fallback around `(utf8->string bv)`.
  - [gambitffi-public.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/gambitffi-public.ss#L45-L46) exercises the non-UTF-8 fallback end-to-end through `jolt-compile-eval` on booted `gsi` by writing byte `255` (`0xff`) and asserting `(= (jolt.ffi/read-bytes p 1) (str (char 255))) => true`.
  - Independent `gsc -warnings -link` check on `prelude-shims.ss` + `scheme-adapter-runtime.ss` + `host-ffi.ss` confirms zero `bytevector->u8-list` warnings.

- **F2 (P3 — `nil`-rejection on string-naming arguments in `host-ffi.ss`) — CONFIRMED.**
  - [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L31-L35) defines `joltffi-str-arg` (throwing `IllegalArgumentException` with `"jolt.ffi: <what> must be a string, got nil"`) and applies it at [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L89), [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L223), [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L264-L265), [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L327-L336), [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L342-L350), and [host-ffi.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/host-ffi.ss#L372-L378).
  - [gambitffi-public.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/ffi/gambitffi-public.ss#L48-L60) adds 10 exact-message `IllegalArgumentException` checks plus 2 preserved `nil`-as-absence checks (`(jolt.ffi/load-library nil) => nil`, `(jolt.ffi/load-failure-note nil) => nil`).

- **F3 (P3 — `profiles.ss` `core` group and `unbound-allowlist.txt` bridge symbol classification) — CONFIRMED.**
  - [profiles.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/profiles.ss#L45-L49) includes `"host-ffi.ss"` in the `core` group alongside `"host-vars.ss"`.
  - [unbound-allowlist.txt](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/unbound-allowlist.txt#L65-L100) classifies all 32 runtime-loaded `joltffi-*` native bridge exports. Independent C-linker check confirms exact 32/32 set equality with the allowlist.

- **F4 (P3 — `libjoltffi-unloadable.dylib` prerequisites and `joltffi-resolve-global` null-address guard) — CONFIRMED.**
  - [Makefile](file:///Users/sto/workspace/jolt-ffi-p1b/Makefile#L2126-L2127) lists `$(GAMBIT-FFI-DIR)/joltffi-dep.c $(GAMBIT-FFI-DIR)/joltffi-unloadable.c`.
  - [scheme-adapter-runtime.ss](file:///Users/sto/workspace/jolt-ffi-p1b/host/gambit/scheme-adapter-runtime.ss#L460) checks `(if (or (joltffi-dlerror) (not a) (= a 0)) ...)`.

---

### Gates Re-Run in Confirmation Round

All commands were executed read-only (`git status --short` clean before and after):

| Gate / Check | Command | Outcome |
|---|---|---|
| `gambitffi-abi` | `sh host/gambit/ffi/watchdog.sh 300 make gambitffi ...` | **PASS** (`78 check(s), 0 failure(s)`) |
| `gambitffi-public` (incl. `0xff` & `nil` guards) | (via `gambitffi-abi` and `gambitcheck`) | **PASS** (`44 check(s), 0 failure(s) — :gambit compiler + native gsi`) |
| `gambitffi-errno-check` | (via `make gambitffi`) | **PASS** (`9 check(s), 0 failure(s)`) |
| `gambitffi-load-failure-smoke` | (via `make gambitffi`) | **PASS** (`14 check(s), 0 failure(s)`) |
| `gambitffi` (aggregate) | (via `make gambitffi`) | **PASS** (`all stage-1 qualified-tier gates green`) |
| `gambitcheck` | (via `make gambitcheck`) | **PASS** (`0 failure(s)`) |
| `gambitvars` | (via `make gambitvars`) | **PASS** (`3625 var cell(s) after boot, 4 unbound (4 allowlisted)`) |
| `gambitstatics` | (via `make gambitstatics`) | **PASS** (`19 static(s) and 30 constructor(s); 11 unresolved (11 allowlisted)`) |
| `adaptercheck` | (via `make adaptercheck`) | **PASS** (`149 contract names checked against the combined env`) |
| C-linker bridge-symbol & `profiles.ss` check | `gsc -c` / `gsc -warnings -link` in `/tmp` + `scheme` profile check | **PASS** (`0 bytevector->u8-list warnings, 32/32 joltffi-* exports match unbound-allowlist.txt`; `"host-ffi.ss"` in `core` group) |

RECOMMENDATION: ACCEPT
CONFIRMED-CLOSED

