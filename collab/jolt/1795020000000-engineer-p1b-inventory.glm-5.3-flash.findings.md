Created-GMT: 2026-11-18 16:27:17 GMT
Created-Local: 2026-11-18 23:27:17 ICT (+07)
Coding-Agent: glm
Session-ID: zcode-subagent-p1b-inventory

# P1b-prep findings — FFI construction-site inventory + typed-shim registry

Role: Compiler & AST Engineer (glm-5.3-flash). Static analysis only on the jolt
fork worktree `~/workspace/jolt-ffi-inv`, branch `gambit-ffi-inv`, base
`a6852ef8`, HEAD now `98925c18` (adds ONLY `p1b-inv/`; not pushed).

## Artifacts delivered

Committed on `gambit-ffi-inv` @ `98925c18` (fork only, never pushed):

- `p1b-inv/ffi-call-sites.md` — every foreign-call construction site with
  file:line, capability tier, emitted Chez form, and shim/refuse/audit verdict.
- `p1b-inv/shim-registry.edn` — the initial typed-shim registry.
- `p1b-inv/CONSUMERS.md` — one row per binding site for the native consumers
  and the fork stdlib, linked to registry shape ids.

This file (not committed to the fork) is the findings report.

## Headline counts

- **Compiler construction sites: 22** sub-sites across 8 constructors in
  `jolt-core/jolt/backend_scheme/ffi.clj` (named×3, pointer-call×3,
  fixed-fn assembly, bare-varargs×3, callable×5, layout×1) + **13**
  compile-time refusal rows + 17 shadow-guard bare names (names.clj:175-194).
- **Runtime construction sites: 2** dynamic `eval` constructors
  (`ffi-varargs-compile` host/chez/java/ffi.ss:1373-1386;
  `sa-foreign-procedure-runtime` host/chez/scheme-adapter-runtime.ss:977-980)
  + 2 adapter-internal constructor macros (`jolt-foreign-proc-safe/-blocking`
  host/chez/rt.ss:83/126) feeding **~180** bootstrap binding sites across 16
  host files + **16** direct `foreign-procedure` sites inside the Chez adapter
  file + scoped-address chain (4 sites) + callable machinery (6 sites).
- **Registry: 44 registered ABI shapes** (21 consumer / 17 platform / 6
  fixture) + **9 deliberately-unregistered classes** that raise by design.
- **Consumer binding rows: 41** production (crypto 12, file 24, parser 5)
  + 7 parser dev-probe rows + **74** fork-stdlib rows.
- Sites astra named: 4. Sites this inventory adds: ~200 (of which the
  load-bearing new ones are listed under Surprises).

## Cross-check of astra's citations (fork is far past the inspected pin)

| Astra citation | Verdict on a6852ef8 | Where it is now |
|---|---|---|
| backend_scheme.clj:2134 scoped raw `foreign-procedure` | MOVED (file is 1714 lines; the back end split into `backend_scheme/`) | `jolt-core/jolt/backend_scheme/ffi.clj:488-491` |
| backend_scheme.clj:297 target-prims Chez-only entry | MOVED | `jolt-core/jolt/backend_scheme/session.clj:76-78` |
| host/chez/java/ffi.ss:1294 varargs eval construction | MOVED | `host/chez/java/ffi.ss:1373-1386` (`ffi-varargs-compile`) |
| gambit scheme-adapter-runtime.ss:321-459 FFI refusals | HOLDS (±2) | refusal tier 307-461, `sa-ffi-raise` at 313 |
| `sa-gc-collect` no-op at ~81 | HOLDS EXACTLY | 81-82, `(define (sa-gc-collect) #f)` |
| Gambit boot excludes the FFI runtime | HOLDS | gambit boot ##include list has no java/ffi.ss |
| Contract "four syntaxes" wording incomplete | HOLDS — and worse | see Surprise 1 |

## Surprises (sites astra did not name)

1. **The contract under-counts on both ends.**
   `host/scheme-adapter/TARGET-CONTRACT.md:21-22` still says "the four
   `sa-foreign-*` syntaxes"; the actual constructor surface is **7**
   (procedure, blocking, native-error, procedure-runtime, callable,
   callable-collect-safe, plus the target-internal
   `jolt-ffi-native-error-procedure` convention selector at rt.ss:359 /
   gambit adapter:339). Worse, `host/scheme-adapter/CONTRACT.txt`'s
   `capability-ffi` tier (lines 226-270) lists 18 names but **omits
   `sa-foreign-callable` and `sa-foreign-callable-collect-safe` entirely** —
   both adapters define them (chez:948/962, gambit:358/365) and the compiler
   emits them (ffi.clj:743-751), yet the machine-readable contract the
   gambitcheck contract-name pass audits does not carry them.
2. **The scoped-address raw emission has a THIRD constructor nobody cited:**
   the bare-varargs cache path compiles foreign procedures at RUN TIME via
   `eval` with a mutex-guarded per-shape cache and a one-entry inline cache
   (ffi.ss:1276-1464). Its shape space is open-ended by construction
   (integer-64/double/u8* carriers per tail position) — this is the concrete
   site where "a finite registry cannot claim arbitrary runtime signatures"
   bites, and the reason bare `:&` must raise on gambit.
3. **~180 adapter-internal libc bindings** flow through
   `jolt-foreign-proc-safe`/`-blocking` (process.ss 48 sites, io.ss 29,
   rt.ss 24, nio-file.ss 20, concurrency.ss 12, tz-primitives.ss 8, …). They
   are not consumer shapes, but a Gambit adapter must either supply every one
   or its boot manifest must exclude the files, exactly as the boot excludes
   java/ffi.ss today. This is a much larger "keep the contract whole" surface
   than the 18 CONTRACT names suggest.
4. **The compiler already refuses two gambit shapes deliberately**
   (pointer-call fns ffi.clj:625-628; by-value callables ffi.clj:763-771) —
   astra's "unregistered shapes raise" rule is partially implemented in the
   compiler already. But the guards are incomplete (see Open questions Q1/Q2).
5. **`jolt build --library` emits Chez-only FFI text** into its launcher
   (build.ss:4647-4653: `foreign-callable jolt-ffi-lookup-export` +
   `foreign-procedure "jolt_set_lookup_addr"`). Stays Chez-only with AOT, but
   it is a construction site outside the compiler/runtime inventory.
6. **Blocking+capture combinations are real production code, not exotic:**
   6 socket rows (connect, recv, send, close, accept, poll) declare
   `{:blocking true :capture-native-error true}` (stdlib/jolt/socket/native.clj:219-277),
   so the shim generator must build the `__collect_safe` + errno-capture
   variant from day one (registry S-25 family), not treat it as an edge.
7. **A Chez-ABI bug probe lives inside the FFI runtime**
   (`ffi-stack-args-correct?`, ffi.ss:1827-1874) — it `eval`s foreign-procedure/
   foreign-callable/define-ftype forms to detect the Chez #1091 stack-args bug.
   A Gambit port must refuse or re-probe under Gambit's ABI; its exported
   jolt.ffi surface (stdlib/jolt/ffi.clj:63-75) documents the semantics.

## Open questions for the P1b designer

- **Q1 (gap G-1):** `emit-ffi-node` guards `:gambit` only for pointer-call fns
  and by-value callables. A `:ffi-layout` on the gambit target emits
  `define-ftype`/`make-ftype-pointer` (unbound there) and raises
  unbound-variable at namespace load instead of the documented message.
  Refuse in the compiler, or commit to a Gambit layout engine?
- **Q2 (gap G-3):** named scalar/aggregate bindings on `:gambit` emit the same
  text as Chez; the scoped branch references `jolt-ffi-dlsym-native` and bare
  `foreign-procedure`, both unbound on the gambit boot — first call dies on an
  unbound identifier, violating astra's "raise deliberately, never reach an
  unbound Chez identifier". Needs a `:gambit` arm in
  `ffi-named-procedure-expr` (or an adapter-bound raising `foreign-procedure`).
- **Q3:** `jolt_parser_worker_free` — now pinned with evidence: the C entry
  spawns a parse thread and `pthread_join`s it (native/jolt-parser/jolt_parser.cpp:1176-1186,
  on_parse_thread 769-780) while the binding is not `:blocking`
  (src/jolt/yang/antlr/cst.jolt:66). Qualify the unmarked wait or flip the
  binding; the registry shape (:shape/s18-ptr-free-ordinary) carries
  `:open-question :worker-free-blocking-mark`.
- **Q4:** the `:gambit` target-prims entry (`{:unsafe-prefix ""}`,
  session.clj:76-78) astra asked for still does not exist; emission degrades
  via `(str nil)` by accident. P1 work, but the inventory confirms the miss.
- **Q5:** should `sa-foreign-callable`/`-collect-safe` be added to
  CONTRACT.txt's capability-ffi tier (Surprise 1) before the Gambit adapter
  work starts, so the contract-name gate holds the boundary?
- **Q6:** mvn_http's SSL bindings call blocking-capable C (SSL_connect,
  SSL_read) with no `:blocking` mark — a pre-existing Chez-only assumption.
  Does the Gambit lane inherit it, refuse libssl surfaces, or re-mark?

## Method note

Every claim carries file:line read on this SHA. The only executions were
`git`, `grep`/`sed`, and one `babashka` EDN-read of the registry artifact to
confirm it parses (no jolt build, no jolt test, no behavior change). The
consumers' C sources and `src/jolt/dao`/`src/jolt/yang` bindings were read
from the datomworld checkout read-only; nothing there was modified.

DONE
