Created-GMT: 2026-11-18 04:44:48 GMT
Created-Local: 2026-11-17 23:44:48 EST (-0500)
Coding-Agent: codex
Session-ID: 01a1ecf5-dc51-7210-8e9e-79f452f849ae


The supplied checkout has no `build/tools/jolt`, and its toolchain declaration still has an empty patch list. I’ll look for an existing local clone without bootstrapping or fetching. The native sources also show that `jolt-file` is the POSIX file wrapper; the ANTLR wrapper is a separate component that needs its own P3 scope.

I found a clean sibling jolt clone at the exact pinned SHA, so I can inspect the contracts and gates while keeping that source provenance explicit. Two design constraints are clear: Gambit’s boot excludes the FFI runtime, and jolt constructs some foreign procedures from runtime signatures. A `c-lambda` macro mapping alone would not cover the existing API.

The critical GC gate needs strengthening: Gambit currently implements `sa-gc-collect` as a no-op, so a quick return from `gc-full!` cannot prove that collection completed during a native call. P1 must demonstrate both real collection and progress by another Scheme thread. I also found direct Chez `foreign-procedure` emission in scoped-symbol and varargs paths that must be routed through the adapter.

I haven’t found Gambit 4.9.8 headers or runtime source locally. I’ll distinguish the feasible errno shim design from the release/claim and foreign-thread entry mechanisms that remain unverified, rather than certify the full tier from memory.

The plan will gate each FFI capability separately, retaining refusal tests for every unsupported operation. P2 and P3 will require explicit Gambit launchers: the current qualification scripts hardcode the Chez launcher. I’ll also call out two delivery prerequisites: `qualify:jolt-upgrade` is documented but absent from `bb.edn`, and the patch verifier needs final-tree validation before successive patches can safely touch the same files.

Completed-GMT: 2026-11-18 04:44:48 GMT
Completed-Local: 2026-11-17 23:44:48 EST (-0500)
Coding-Agent: codex
Session-ID: 01a1ecf5-dc51-7210-8e9e-79f452f849ae

Intended artifact: `collab/jolt/1794976827310-architect-gambit-ffi-plan.gpt-6-astra.findings.md`

The plan follows. **No file was written:** the task explicitly prohibits modifications, and this session’s filesystem is read-only. No builds, gates, tests, or network requests were run.

**Evidence and scope.** This checkout, at `65155a9dd2b5b165b1c1a10009f0d944dbba842c`, has no `build/tools/jolt`. I inspected the existing sibling `/home/sto/ci/datomworld-j6ev/build/tools/jolt`, whose HEAD is the declared pin `b389f1a7fb553f63a3494d71f8fe3251790bf4d3` and whose working tree is clean.

The supplied checkout also lacks a Gambit entry in `mise.toml`. I found no local Gambit 4.9.8 headers or runtime source. Consequently, the task’s provisioning and historical gate results are supplied context, not independently verified findings.

Several source findings materially affect the plan:

- `TARGET-CONTRACT.md` requires atomic native-error capture and GC-safe blocking independently.
- `host/gambit/scheme-adapter-runtime.ss:321–459` refuses FFI; its `sa-gc-collect` at line 81 is a no-op.
- `backend_scheme.clj:297` contains only the Chez primitive-table entry. Gambit currently obtains checked operations through the missing-entry fallback.
- Scoped symbol calls still emit raw Chez `foreign-procedure` at `backend_scheme.clj:2134`; runtime varargs construction does likewise in `host/chez/java/ffi.ss:1294`.
- Gambit’s boot omits the FFI runtime. Adapter changes alone cannot enable `jolt.ffi`.
- ANTLR lives in `native/jolt-parser`, not `native/jolt-file`. The latter is the POSIX wrapper supporting J4a–J4d.
- `qualify:jolt-upgrade` is described as proposed in design §6.4 and is absent from `bb.edn`.

**1. Feasibility ruling**

**Proceed with a bounded P1 feasibility and adapter slice. Do not yet promise complete Chez-equivalent FFI on Gambit 4.9.8.** Atomic errno capture has a concrete implementation path. Generic GC-safe blocking and foreign-thread callbacks need a runtime-source proof and executable witnesses before their refusal rows change.

| Contract surface | Ruling |
|---|---|
| Scalar calls, native memory, `dlopen`/symbol lookup | Feasible through a compiled native bridge exposed with Gambit `c-lambda`, with declarations/helpers supplied through `c-declare`. |
| Atomic C result plus errno | Feasible through typed C wrappers that save errno immediately after the target returns, before conversion, cleanup, allocation, or runtime re-entry. |
| Blocking calls allowing other threads’ GC | Plain `c-lambda` is insufficient evidence. Enable only after proving the exact 4.9.8 runtime transition and independent Scheme-thread progress. |
| Same-thread Scheme callbacks | `c-define` supplies an entry mechanism, but dynamic closure identity, rooting, lifetime, exceptions, and re-entry still require implementation. |
| Callbacks from an unrelated C thread | Unqualified. Requires verified attachment/claim, Scheme entry, and release/detachment rules for the exact runtime build. |
| Arbitrary runtime signatures | Not provided merely by expanding to compile-time `c-lambda`. Needs a runtime ABI engine or explicitly bounded compiled signature support. |
| Native compilation, FASL, subprocess | Separate capabilities; remain refused unless independently implemented and qualified. |

There is an important evidence limit: **I cannot name a verified 4.9.8 release/claim API or declare these rows impossible on every 4.9.8 configuration from the available source.** Supplying exact function names or signatures here would be speculation. This is an unresolved P1 prerequisite, not an assertion that later Gambit APIs exist in 4.9.8.

The implementer must inspect the exact provisioned 4.9.8 source and record:

1. Runtime build configuration and threading model.
2. Exact declarations and implementations of any leave/re-enter or release/claim mechanism.
3. Whether a released foreign frame permits collection and whether another Scheme thread can actually run.
4. Rules for a previously unknown pthread entering Scheme.
5. Root and processor-state obligations across those transitions.

Neither `___ATOMIC` nor a C atomic variable establishes these properties. Object-reference release is also distinct from releasing runtime execution ownership. Do not translate Chez `__collect_safe` to a similarly named Gambit mechanism without proving its semantics.

A single processor blocked inside C cannot run another green thread merely because its arguments are rooted. Conversely, enabling multiple native execution contexts does not establish that a foreign frame participates correctly in collection.

**Fallback ruling:** permit explicitly documented FFI sub-capabilities. If blocking or foreign-thread entry cannot be qualified on the pinned runtime, those constructors continue to raise message-carrying unsupported errors. The basic memory/loader/call tier need not remain entirely disabled. Update `CONTRACT.txt` and `TARGET-CONTRACT.md` to describe this partial mode; do not silently redefine “FFI supported” to mean full support.

A worker pool is **not** a transparent generic fallback. Moving calls changes native-thread identity, TLS, callback context, and potentially library behavior. A separately qualified worker interface for specific libraries is possible, but cannot masquerade as unrestricted `sa-foreign-procedure-blocking`.

**2. Adapter and compiler design**

Add an explicit `:gambit` entry with `:unsafe-prefix ""` to `target-prims`. Keep generated code expressed through the adapter boundary; Gambit C forms belong in target-owned bridge compilation units.

P1 must cover all foreign-call construction paths:

- Ordinary and blocking `sa-foreign-procedure`.
- `sa-foreign-procedure-native-error`, including combinations with supported conventions.
- Both callable forms.
- Runtime signature construction.
- Scoped-address calls.
- Varargs specialization.

Replace the raw Chez scoped-address emission and runtime `eval` of `foreign-procedure` with adapter operations. Audit aggregate/ftype emission separately. Unsupported ABI forms must raise deliberately, rather than reach an unbound Chez identifier.

The contract’s “four syntaxes” wording is now incomplete: native-error capture adds another entry. Inventory actual bindings and call sites rather than relying on that count.

**Compilation boundary.** Gambit’s C FFI forms are compiled facilities. An interpreted REPL must not depend on evaluating new `c-lambda` forms successfully. Build a native bridge ahead of time; Scheme adapter macros expand to calls into that bridge. Loading or linking this bridge does not require implementing jolt’s general AOT capability.

For the first implementation, use **typed, generated C shims per call shape**, shared across symbols with identical ABI shapes. Each accepts the resolved function address. Signature identity includes argument/result types, calling convention, variadic boundary, and relevant ABI layout.

This gives auditable errno capture and avoids a hand-written universal C ABI dispatcher. It also imposes an explicit limitation: a finite precompiled registry cannot claim arbitrary runtime-signature support. Unregistered shapes must raise. Generate the initial registry from the native consumer inventory and test fixtures.

A later generic engine may use a pinned dependency such as libffi, but requires separate approval of its dependency and ABI surface. In particular, reading errno after `ffi_call` is insufficient unless its intervening return path is proved errno-preserving. Calling a typed shim that saves errno internally avoids that dependency.

**Native-error wrapper.** The essential sequence is:

```c
result_type result = target(arg0, arg1);
int saved_errno = errno;
/* Only now: store the pair, clean up, or re-enter Scheme. */
```

Use invocation-local storage, including for nested/reentrant calls. A global “last errno” cell is forbidden; a single TLS cell also fails nested-call ownership.

Return the two Scheme values expected by the existing lowering. Preserve the target’s errno value on both success and failure; do not normalize success to zero unless the existing contract explicitly requires it. For blocking calls, save errno before reclaiming runtime ownership. Any cleanup that changes errno occurs afterward.

**Blocking transition.** Once the runtime mechanism is verified, the adapter must perform this sequence:

1. Resolve and retain the symbol/library.
2. Convert arguments and establish ownership while Scheme execution is active.
3. Ensure C will access only stable native memory or specifically supported rooted representations.
4. Leave runtime execution through the verified mechanism.
5. Invoke the target and capture result/errno.
6. Re-enter the runtime through its required claim mechanism.
7. Convert results, release temporary resources, and return.

No Scheme allocation, Scheme-object access, or callback may occur in the released interval without the corresponding entry protocol. Failure and cancellation paths must preserve ownership and reclaim exactly once.

**Memory.** Keep the consumers’ existing copy-in/copy-out design. `dao.posix.file` already documents that caller arrays are not shared with C.

Implement allocation, free, typed access, sizes, and bulk copies with correct widths and pointer representation. Cover `size_t`, signed/unsigned 64-bit values, floating types, null pointers, and byte offsets.

Gambit must not inherit the non-moving-collector exemption for `sa-lock-object`. Rooting prevents reclamation; it does not necessarily prevent relocation. For callback objects, a rooted Scheme handle plus a stable C trampoline may satisfy the required callable lifetime. For operations requiring an actual pinned Scheme address, provide a verified pinning mechanism or refuse that operation.

Bulk bytevector copies remain short, non-collect-safe operations with no callbacks or allocations while borrowing an address, as the contract requires.

**Loading and resolution.** Preserve the current loader policy:

- Declared libraries use scoped handles and declared-order symbol resolution.
- Scoped lookup precedes process-global lookup.
- Missing files differ from present-but-unloadable files.
- Preserve the loader’s reason, including missing dependencies.
- Duplicate-symbol detection compares distinct addresses, not handle counts.
- Handles remain alive while procedures or callable machinery can reference their code.

Expose `sa-load-shared-object`, process-symbol lookup, entry predicates, and addresses through this implementation. Do not replace scoped lookup with global linking merely because that makes `c-lambda` easier.

**Callbacks.** A `c-define` entry alone does not create one independently owned native function pointer for every runtime Scheme closure. Use qualified trampolines/closures with explicit rooting and release, or document a bounded implementation that refuses exhaustion.

Separate three entry cases: active Scheme-origin calls, re-entry during a released blocking call, and previously unknown C threads. The last two require verified runtime ownership rules before Scheme executes.

Preserve existing callback exception/error handling; do not allow Scheme escapes or C++ exceptions to unwind across an unsupported C frame. Test nested callbacks and callbacks that allocate.

These callbacks remain host-local runtime machinery. They must not introduce portable yin.vm callbacks or migratable native handles. Application-visible asynchronous effects still enter the stream boundary. A queued event is not an equivalent substitute for a synchronous C callback requiring a return value.

**3. Gate strategy**

Maintain explicit profiles for native FFI and FFI-disabled execution, including Gambit’s JavaScript target.

Split G1 assertions by individual capability. In the enabled profile, replace each implemented refusal with a behavioral witness using real fixture symbols. In the disabled profile, preserve its refusal. Unimplemented signatures and sub-capabilities retain negative tests in both relevant profiles.

Do not select expectations by attempting an operation and accepting either success or failure. The qualified profile determines the expected result.

Retain native-compilation, FASL, and subprocess refusal rows. A native bridge does not justify flipping them.

Proposed targets:

| Target | Required evidence |
|---|---|
| `gambitffi` | Aggregate of the mandatory enabled-profile checks; missing prerequisites fail qualification. |
| `gambitffi-refusals` | Disabled profile, unsupported shapes, conventions, callback modes, and separate capability refusals. |
| `gambitffi-abi` | Scalar widths, pointers, byte copies, return conversion, runtime registered signatures, symbol/address parity. |
| `gambitffi-errno-check` | Existing source audit plus executable atomic-capture tests. |
| `gambitffi-blocking-gc` | Actual collection and another Scheme thread’s progress while C remains blocked. |
| `gambitffi-callables` | Same-thread, released-thread, foreign-thread, nested, GC, and lifetime cases for every advertised mode. |
| `gambitffi-load-failure-smoke` | Missing dependency versus missing candidate, preserved diagnostics, direct and project-native paths. |
| `gambitffi-duplicate-symbol-smoke` | Two addresses detected; shared dependency counted once; declared shadowing preserved; warning policy unchanged. |
| `gambitffi-error-kinds-check` | Shared diagnostic registry audit plus emitted Gambit error-kind tests. |
| `gambitffi-zlib-native-smoke` | Declared fake zlib cannot replace the runtime’s qualified/private zlib bindings. |
| `gambitffi-static-native-smoke` | Explicitly scoped static-link qualification, described below. |

The existing `errno-check.sh` is a structural audit. It does not prove the native boundary. Add C fixtures returning distinct results and errno values, concurrent calls with distinct errors, nested callbacks, and deliberate post-capture errno clobbering during conversion/cleanup. Include combined blocking-plus-error capture.

For blocking, use a C-side handshake: announce entry, remain blocked until another Scheme thread completes a real GC witness, then permit return. Run under an external watchdog so deadlock becomes a recorded failure. Include a deliberately incorrect blocking implementation as a negative control.

**The current no-op `sa-gc-collect` cannot qualify this gate.** Supply a real Gambit collection operation and an observable collection witness. Preserve and strengthen the existing crypto/file timeline probes; elapsed timestamps around a no-op are inadequate evidence.

The zlib and static-native scripts currently exercise **built jolt applications**. If jolt AOT remains unavailable, a `gsc`-linked fixture can qualify bridge-level linking, but cannot count as the original application-build smoke. Record that distinction. Full `jolt build` parity remains Chez-only until separately delivered; its unsupported path stays tested.

Run enabled-profile gates on macOS arm64 and Linux x86_64. Qualification must require Gambit explicitly—using the existing `JOLT_REQUIRE_GAMBIT` convention—and reject silent skips. Keep existing Gambit boot/kernel/eval and seed-generation checks, plus relevant Chez regression checks.

**4. Delivery mechanics and slice boundaries**

Before P1 implementation, reconcile provisioning with this checkout: declare Gambit 4.9.8, its source/build configuration, resolved binaries, and any bridge dependencies. Do not rely on ambient `gsi`/`gsc`.

Every jolt change must land as a declared patch under `patches/jolt/<base-sha>/`, with its `:id`, `:base`, patch digest, file before/after digests, generated outputs, and behavioral gate. Changes to datom.world’s wrappers, launchers, and qualification drivers remain ordinary tracked repository changes accompanying that patch.

Two bootstrap changes are necessary:

- `smoke!` currently invokes the Chez launcher. Extend qualification to run a target-specific Gambit witness and verify reported target identity.
- `apply-patches!` determines a live stack by comparing every patch’s `:after` hashes with the final tree. Successive patches changing the same file cannot satisfy that rule. Validate ordered patch transitions during application, then compare the live tree with the final expected digest for each path. Add negative checks for partial application, wrong order, corruption, and an omitted patch.

The existing remint hook runs Chez’s remint script. Backend changes also require deliberate Gambit seed regeneration and recorded digests; it must not be assumed to happen automatically.

Implement `qualify:jolt-upgrade`, or use the documented equivalent checklist with retained evidence until that command exists. Record both the upstream base and complete patch-stack identity. A patch change is a toolchain change even when the base SHA stays fixed.

**P1 — Adapter FFI, boot integration, and qualification**

Suggested patch: `0001-gambit-ffi-adapter.patch`.

Internally sequence the work:

1. Runtime-source audit and minimal errno/GC/callback witnesses.
2. Native bridge, explicit target entry, loader and memory operations.
3. Adapter routing for all construction paths and native FFI boot inclusion.
4. Target-aware launchers, G1 flips, refusal coverage, and new gates.

Acceptance requires real calls through jolt’s emitted code and interpreted runtime, correct memory and resolution semantics, atomic errno, and the exact published capability matrix. GC-safe blocking and foreign-thread callbacks require their own passing witnesses before being advertised.

Keep AOT, images, subprocesses, unsupported aggregates/varargs, and unqualified callback modes refused.

Principal risks are runtime ownership semantics, green-thread starvation, ABI mistakes on arm64, generated-seed drift, and a boot that accidentally imports Chez-only FFI facilities.

**P2 — jolt-crypto and J1b requalification**

Suggested patch: `0002-gambit-crypto-qualification.patch`, containing the jolt-side integration/gates; accompany it with target-aware datom.world qualification drivers.

Use the existing C wrapper and ABI wherever possible. Requalify the actual loaded library, even when its path and digest match the Chez record.

Required coverage includes:

- Hash/sign known answers and cross-host fixture equality.
- Canonical path, exact version, digest, version floor, repointed paths, replacements, and missing libraries.
- Wrong-library-first and Linux preload/interposition behavior.
- Current layout-4 exports and all twenty audited libcrypto entries, including RAND and SHA-1.
- Acquisition-failure cleanup, zeroization, and no allocation during cleanse.
- Real GC during the blocking hash operation.

Parameterize `qualify.clj` and probe launchers; their present commands hardcode Chez. Every child must report its target so a successful Chez invocation cannot qualify Gambit.

**P2 remains Chez-only if P1 blocking is unqualified.** Removing `:blocking` from crypto calls is not an acceptable workaround.

**P3 — POSIX file/J4d and ANTLR/J5 qualification**

Suggested patch: `0003-gambit-native-consumers.patch`, with separate file and parser acceptance gates.

This slice must cover both components to meet the requested corpus bar.

For `native/jolt-file`, require J4a–J4d behavior: byte-identical frames, short/EINTR/error transfers, poison/recovery semantics, descriptor/native-memory lifetime, collection during blocked I/O, wrapper/layout refusal before mutation, locking, close-on-exec, exclusive mode-0600 key creation, saved-state behavior, and cross-host reopen.

For `native/jolt-parser`, preserve its C ABI, owned buffers, exception containment, worker isolation, budgets, and dedicated parse-thread stack. Run the complete J5 family: budget boundaries, below-bound JVM packet equality, historical crash inputs, injected exceptions, abort-is-failure control, unavailable/wrong-layout recovery, worker reuse, GC/lifetime/cache checks, and REPL transcript equality.

The parser already creates a native parse thread and then joins it. That join can still block Gambit’s execution context; the parser’s own pthread does not solve the FFI blocking requirement.

Also audit `jolt_parser_worker_free`: it waits for destruction on a parse thread, yet its current Scheme binding is not marked blocking. Qualify or correct that binding; do not limit the audit to `jolt_parser_export`.

External Babashka probe drivers may launch children while Gambit’s subprocess capability remains unsupported. Any probe that actually invokes subprocess creation inside the target requires that capability or remains explicitly unqualified.

**Corpus acceptance means identical semantic results and refusal behavior, not identical timing.** Preserve platform-specific applicability and mark missing legs PARTIAL. Do not label a partial corpus pass as Gambit parity.

**5. Effort and P1 decision**

These estimates include implementation, fixtures, platform qualification, patch packaging, and review. The approximately 11,000-line adapter is the reference unit; generated seeds and vendored dependencies are excluded.

| Slice | Relative effort | Indicative engineering time |
|---|---:|---:|
| P1 runtime audit and decisive witnesses | 0.10–0.20 adapter units | 3–5 days |
| P1 bridge, compiler/boot integration, gates, patch tooling | 0.45–0.80 | 3–6 weeks |
| P2 crypto qualification | 0.10–0.20 | 1–2 weeks |
| P3 file plus parser qualification | 0.25–0.45 | 2–4 weeks |
| General runtime signatures, unrestricted callbacks, or AOT beyond the bounded tier | Additional 0.30–0.70 | Separate estimate after P1 |

These are engineering-effort estimates, not a prediction of generated line count. Runtime changes could exceed the upper range; they require a new decision rather than being absorbed silently into P1.

**GO after P1** requires:

- Exact runtime mechanisms and configuration recorded from 4.9.8 source.
- Both pinned rows demonstrated on both target platforms.
- Target-identified public jolt execution, not only standalone C/Scheme fixtures.
- Qualified callable modes and explicit refusals for every remaining mode.
- No moving-memory exposure across released calls.
- Reproducible patch application, seeds, negative gates, and rollback.
- Existing Gambit and relevant Chez gates preserved.

**Partial GO:** memory, loading, ordinary calls, and errno may land independently when qualified. Retain blocking/callback refusals and describe the result as partial FFI. This does **not** authorize P2/P3 parity claims.

**NO-GO for P2/P3:** collection cannot complete while C is blocked; unrelated C-thread entry cannot be made safe where required; exact runtime ownership rules remain unknown; or the solution depends on silently moving generic calls to another native thread.

If 4.9.8 cannot meet the necessary runtime transitions, the next decision is an explicit Gambit upgrade or runtime patch through the upgrade gate. The fallback remains honest refusal, with Chez retaining the affected capability.

DONE
