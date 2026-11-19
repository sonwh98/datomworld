Completed-GMT: 2026-11-19 04:23:45 GMT
Completed-Local: 2026-11-19 11:23:45 ICT (+07)
Coding-Agent: Codex (implementation continuation)
Session-ID: 01a1f1f9-2d2b-71a2-b88a-1675b81dd975
Created-GMT: 2026-11-19 04:03:03 GMT
Created-Local: 2026-11-19 11:03:03 ICT (+07)

| Capability | Implementation / qualification | Still refused or unqualified |
|---|---|---|
| Native bridge | Ahead-of-time typed generated shims; pinned source/runtime inputs; build/output digests recorded | Other runtime configurations/platforms |
| Loader and resolution | Scoped RTLD_NOW/LOCAL handles; declaration order; address-based duplicate detection; global fallback; ABI/public/load-failure gates | Pending module-native discovery and full dependency loader integration |
| Memory | Allocation/free, scalar typed access/sizes, zeroing, u8vector bulk copies, public string-byte copies; ABI/public/G1 witnesses | Jolt-array moves and object pinning |
| Ordinary calls | Finite registered fixed/declared-tail shapes, proper variadic ABI identity, exact widths, nullable strings, float/bool conversion; ABI/public gates | Unknown signatures, string arguments, pointer-call form, aggregates, runtime signature construction |
| Native error | Invocation-local two-value adapter return, public jolt vector; immediate C capture, generated-shim clobber test, wrong-order control, preserving success and post-capture controls | Blocking+errno; native concurrency/reentrant callback qualification |
| Collection | Real ##gc and unreachable weak-key clearance in gambitcheck | Collection during C calls / GC-safe blocking |
| Compiler routing | Explicit :gambit primitive prefix; eval-visible adapter constructors; regenerated seeds; Q1/Q2 public refusal tests | AOT/FASL/subprocess parity |
| Callbacks | All constructors deliberately refuse; public and adapter refusal witnesses | Dynamic closures, foreign pthreads including serialized mode, yielding/migration/exception escape |
| Platforms / consumers | Provisioned single-threaded Gambit 4.9.8 on macOS arm64 only | Linux/MT/Web; P2/P3 consumer parity remains Chez-only |

Gate summary: PASS — gambitffi, gambitffi-abi, gambitffi-errno-check,
gambitffi-load-failure-smoke, gambitcheck, gambitkernel, gambiteval,
gambitseedcheck, gambitgencheck, gambittwins and adaptercheck. Additional Chez
binding/width gates pass. The additional Chez native-error suite is 39/41 on
both changed and untouched-base compiler seeds (same two socket-consumer rows).

## State and delivery

Base: a6852ef8 on gambit-ffi-p1b. Implementation commit:
b717f57d93510aac027eeee157c34e3f4c432cbd. Nothing pushed. Session identity was
captured from CODEX_THREAD_ID / CODEX_SESSION_ID in the running environment.

Intended promotion path:
`/Users/sto/workspace/datomworld/collab/jolt/1795042000000-engineer-p1b-stage1-codex-gpt-6.1-sol.findings.md`.
That destination is outside this session's writable roots and was designated
read-only by the continuation request. This local file and the final response
supply the findings for orchestrator promotion; datomworld was not modified.

The inherited diff was audited and corrected, not accepted as qualification.
Removed scratch float experiments and replaced the hand-maintained shim list
with a generated registry. Fixed fixed/variadic ABI conflation, wrong Chez type
tokens, lost scoped blocking/varargs conventions, unspecified errno read order,
cell cleanup, and an accidental host/bridge free-name collision. Replaced
malformed tests, double frees and freeing an integer result. Corrected the gsc
options and added an assertion that errno instrumentation actually compiled.

## Runtime and bootstrap

Absolute runtime prefix:
`/Users/sto/.local/share/mise/installs/gambit/4.9.8`.
Runtime header version is 409008 and the installed driver/header select the
single-threaded configuration. Host: Darwin arm64. Apple clang 21.0.0
(clang-2100.4.1.1). No Gambit runtime source/build was modified.

`host/gambit/ffi/TOOLCHAIN.sha256` records gsi/gsc, gambuild-C, gambit.h and the
three runtime/compiler static libraries. `DIGESTS.txt` pins nine source,
generated and toolchain-manifest inputs. Generated shims must match their
manifest on every build. FFI prerequisites force rebuilding artifacts, so a
fresh timestamp on an ambient .o1 cannot satisfy a gate. The missing dependency
fixture is built from pinned C sources and its dependency is removed before
loading. Output hashes and generated-C hashes are in evidence/; binary
byte-identity across linkers/machines is not claimed.

Every gate is externally watchdogged. The watchdog kills the entire process
group and returns 124. Missing tools or a missing bridge cannot produce an FFI
PASS. Digest corruption and deadline rejection were independently exercised.

## Public-path proof and deviations

`gambitffi-public.ss` uses the fork's actual boot + cross-minted seed and
`jolt-compile-eval`, explicitly asserting backend target :gambit. Its 31 jolt
source checks exercise the host-owned jolt.ffi functions and compiler __cfn
special form, plus actual catchable refusals. The ABI target and gambitcheck
both run it. This is the brief's permitted compiler/eval gate path: the build
CLI is Chez-hosted; no Gambit `bin/jolt build`, AOT, complete stdlib namespace
loading or dependency-native discovery is claimed. No Chez subprocess executes
these Gambit public witnesses.

Gambit eval cannot see unit-local sa-* macros. Rather than emit unbound names,
the :gambit emitter calls the host's public error boundary, then the adapter's
registered-shape constructor with literal type data. Same-unit Scheme callers
still have the sa-* syntaxes. All C FFI forms are compiled ahead of time.

The checked-in JSON manifest is an explicit projection of inventory shapes:
20 unique ordinary ABI identities including S32, with S19/S20 shared, plus 13
scalar identity fixtures. It rejects all omitted modes and unknown shapes.
There is no libffi dependency or unrestricted runtime ABI engine.

Errno is saved immediately after the typed target call, before conversion or
cleanup. Each invocation owns a malloc'd cell, allocated before calling C and
freed with dynamic-wind even on conversion failure. Ordered let* ensures the
cell is read after the call. The test build uses exactly the generated capture
bodies, adding errno=4242 after saving; it asserts that flag is enabled. The
wrong-order control calls the same target but saves after its clobber and must
see 4242; the real shim must see EACCES. Success returning errno 7777,
preset-and-preserving C-frame control, post-capture overwrite and 1000
alternating pairs also pass. Alternating stress is not advertised as native
concurrency or nesting qualification.

The original proposal to preset errno in a separate Scheme call was discarded:
allocation/conversion before the next call can affect errno. Targets own their
failure/success errnos; the preserving-success preset and call share a C frame.

## Q1–Q6

- Q1: :ffi-layout and named by-value forms refuse before Chez ftype emission;
  public jolt catches UnsupportedOperationException. No unbound fallback.
- Q2: scoped and global named calls use the finite registry, preserve variadic
  boundaries and blocking refusals; public unknown-shape and missing-symbol
  tests assert messages, so an unrelated unbound error cannot pass.
- Q3: worker destruction's unmarked pthread_join remains a P3 consumer issue.
  S18's nonblocking fixture qualifies only its ABI. Do not enable parser
  workers on this evidence; re-mark or separately qualify the consumer wait.
- Q4: explicit :gambit {:unsafe-prefix ""} entry implemented and reminted.
- Q5: both callable syntaxes added to CONTRACT.txt, with refusal assertions.
- Q6: unmarked SSL waits remain unqualified; no libssl or HTTP consumer parity
  is claimed. Address their marks in the consumer lane before adoption.

The architecture/trampoline constraint and all C4 must-not-assert restrictions
remain binding. There is no claim of universal foreign-entry impossibility,
MT teardown corruption, or a precise post-return GC delay.

## Commands and verbatim verdicts

Required run (exit 0, watchdog 300s):

```sh
sh host/gambit/ffi/watchdog.sh 300 make gambitffi gambitcheck gambitkernel gambiteval GAMBIT_PREFIX=/Users/sto/.local/share/mise/installs/gambit/4.9.8
```

```text
gambitffi-abi: 78 check(s), 0 failure(s)
gambitffi-abi: PASS
gambitffi-public: 31 check(s), 0 failure(s)
gambitffi-public: PASS — :gambit compiler + native gsi
gambitffi-errno-check: 9 check(s), 0 failure(s)
gambitffi-errno-check: PASS
gambitffi-load-failure-smoke: 14 check(s), 0 failure(s)
gambitffi-load-failure-smoke: PASS
gambitffi: PASS — all stage-1 qualified-tier gates green
gambitcheck: 0 failure(s)
gambitcheck: PASS — gambit adapter + shims verified on native gsi
gambitffi-public: 31 check(s), 0 failure(s)
gambitffi-public: PASS — :gambit compiler + native gsi
kernel-test: 0 failure(s)
kernel-test: PASS — booted kernel + natives verified on native gsi
eval-test: 0 failure(s)
eval-test: PASS — jolt compiles and evaluates on native gsi
```

Seeds reminted with the provisioned Chez bin/scheme using host/chez/remint.sh;
converged after two final passes, zero skipped forms, Gambit grep verification
passed. make gambitseedcheck / gambitgencheck / gambittwins (watchdog 180s) and
adaptercheck (30s) passed. Chez ffi-binding-test.ss (90s): 74/74; widths shell
gate (120s): 86/86.

Additional Chez native-error shell gate (120s): 39/41. A comparison loading
both untouched a6852ef8 seed files, forcing the source boot and using the same
native helper, reproduces exactly:

```text
FAIL: socket recv keeps EAGAIN across an intervening errno clobber
FAIL: socket accept keeps EAGAIN across an intervening errno clobber
39/41 passed
```

This is a baseline limitation, not a green regression claim. All lower-level
native-error rows passed in both runs; the two socket-consumer rows were not
changed as part of this bounded Gambit slice. The full Chez FFI suite was not
claimed/run.

Full unedited gate output, seed checks, Chez comparison, negative controls,
compiler identity and hashes are retained in `evidence/`. See
`host/gambit/ffi/README.md` for the capability boundary and rollback. Revert the
stage-1 implementation commit to roll back; delete build/gambit-ffi outputs.
No installed runtime state needs restoration.

DONE
