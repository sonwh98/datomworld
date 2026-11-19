Created-GMT: 2026-11-18 18:59:06 GMT
Created-Local: 2026-11-19 01:59:06 ICT (+07)
Completed-Local: 2026-11-19 01:59:06 ICT (+07)
Coding-Agent: codex
Session-ID: 01a1ef93-bee4-7c00-9752-4e034b3efcd1
Role: Review

**The bounded P1b ruling is confirmed. F1–F3 are repaired for the provisioned-build audit. The MT blocked-GC failure also reproduces. However, the MT report overstates its teardown evidence, and its “183µs after return” timing claim needs correction.**

Reviewed commits:

- P1a-r2: `6507ec1ed5533504b22955aa20a37af9e1d1e155`.
- P1a-MT: `1b9b6ed09f966dfe2e5e065938be67ca16f17477`.
- Gambit: `2228e4d163582733a3a67a5efcb22f8ebe93c02b`.

I ran the r2 suite and generated-C verification, repeated repaired W3 rows, and reran existing MT configuration, blocked-GC, owner-control, and naive serialized-entry binaries. Writes were confined to `p1a/witnesses/build/`. Both worktrees’ tracked files remain unchanged.

Path abbreviations: `W/` = this worktree’s `p1a/witnesses/`; `MT/` = `/Users/sto/workspace/jolt-ffi-mt/p1a-mt/`; `S/` = `/Users/sto/workspace/gambit-v4.9.8-src/`.

**C1 — Repairs against F1–F3**

**F1 repair — CONFIRMED.**

The two add entries now specify `(int int)`; the zero-argument phase3 entry specifies `()` (`W/w3-server.scm:56–63,96–97`). Generated C contains two C arguments and `___NARGS 2` for both add entries (`W/build/w3-server.c:197–205,228–233`). `make verify-w3-c` passed, including its phase3 `___NARGS 0` check (`W/Makefile:88–100`).

Every provisioned-build phase runs the owner control first and aborts on control failure (`W/w3-fthread.c:74–101,267–275`).

My samples reproduced:

| Repaired W3 row | Reviewer result |
|---|---|
| Owner control | PASS: 42, 1072, two GC rounds |
| Serialized phase1 | 3/3 survived with correct recorded values |
| Serialized phase1, heartbeat disabled | 3/3 survived |
| Owner parked inside cfun, phase3 | 3/3 survived |
| Concurrent phase2 | 3/3 SIGSEGV, exit 139 |

The serialized workers reached the three `(##gc)` rounds and final result 2 (`W/w3-fthread.c:125–131`; `W/results/w3-phase3.out:10–20`). The original malformed-fixture crash attribution must remain withdrawn.

**F2 repair — CONFIRMED.**

The actual targets now set their own failure errno (`W/w1-fixture.c:53–55`). Shapes A/B/C save errno immediately after the target and before allocation (`:64–68,81–85,97–101`). Shape E saves before allocation and nested Scheme re-entry (`:120–125`).

The contrasting controls are effective:

- F saves before the intervening clobber (`:144–149`).
- G deliberately saves after it (`:163–168`).
- Scheme checks G’s clobbered result explicitly and exercises F/G in the loops (`W/w1-errno.scm:138–163`).

My rerun produced **5901 checks, zero failures, negative-control OK** (`W/build/results/w1.out:4–8`). This confirms the repaired standalone capture witness; it does not yet qualify jolt’s future shim generator or blocking-plus-errno path.

**F3 repair — CONFIRMED for the provisioned-build refusal witness.**

B executes and announces readiness before A enters C (`W/w2-blocking.scm:199–217`). B mutates a globally rooted object, performs real GC, and calls the C handshake only after a completed collection (`:111–145`). C records release cause and whether the handshake arrived during its active wait (`W/w2-fixture.c:47–83,91–104`). The qualification verdict derives from observed state (`W/w2-blocking.scm:223–263`).

My rerun reproduced:

```text
release-cause=deadline
rounds-during-block=0
rounds-after=3
handshake-called-from-scheme=#t
capi-handshake-calls=1
handshake-during-block=0
blocked-ms=6000
```

Evidence: `W/build/results/w2-qualify.out:22–25`.

One limitation remains: the control’s “overlap” predicate permits all B collections before A’s first call because it tests only `a-calls-done < 3` (`W/w2-blocking.scm:128–129,182–186`). Treat it as a working-GC control, not proof of collection overlapping C execution. This does not undermine the synchronized negative qualification result.

`make all` and `make verify-w3-c` passed. Build hygiene now preserves tracked results and existing `build/results/` through `clean` (`W/Makefile:239–258`). `VERIFY: PASS` means the expected audit records—including capability failures—were obtained.

The F5 citation corrections are also present, including the Threads introduction, conversion-temporary and `___AT_END` passages, stack-marker TODO, and corrected synchronization locations (`p1a/AUDIT.md:78–83,115–120,588–599`).

**C2 — Effect of serialized foreign-entry survival**

It narrows the empirical conclusion materially:

**On this provisioned single-threaded build, a foreign pthread can execute these valid entries, allocate, collect, and return while the owner is parked.** A categorical assertion that foreign entry necessarily crashes is false.

The qualification boundary remains unchanged. These samples do not establish a documented ownership-transfer protocol, exclusion against every runtime activity, lifecycle safety, nested-entry rules, or portability. Concurrent use of the shared pstate still crashes. The configured single-threaded processor accessor selects the same primordial pstate regardless of calling pthread (`S/include/gambit.h.in:8607–8612`).

P1b may qualify callbacks on the owning native thread under explicit nesting, scheduling, lifetime, and exception restrictions. It must retain refusal for generic unrelated-pthread entry, including an advertised “serialized foreign-thread” mode. Serialized survival is positive experimental evidence, not qualification of that mode.

**C3 — MT experiment and standard of proof**

**The MT blocking verdict is confirmed; the complete five-step qualification is not finished.**

| Experiment requirement | Assessment |
|---|---|
| Pinned, genuinely SMP-enabled build | Confirmed by configuration/bootstrap records and live behavior |
| Explicit processor startup | Confirmed: `-:p2`; server sets parallelism 2 |
| B executing elsewhere while A blocks; real GC before release | Implemented; failure reproduced |
| Valid entries, owner control, foreign-entry lifecycle investigation | Partly satisfied; attach stress has defects described below |
| macOS arm64 and Linux x86_64 | macOS executed; Linux pending |

The bootstrap distinction is substantive: regenerating the runtime modules with the SMP prelude is documented at `MT/BUILD-CONFIG.md:69–115`. The current generated scheduler contains the cited SMP implementation, and my W0MT rerun reports two processors, TLS pstate access, and `smp-feature=ENABLED`.

I also compared the ten restored source-tree runtime `.c` files’ actual Git object hashes against the pin; all match. This independently checks restoration after the disclosed regeneration incident.

**The decisive blocked-GC result reproduces.**

In my fresh MT run:

- A announces entry on processor 1.
- B observes A inside C and requests `(##gc)` on processor 0.
- C returns after its approximately six-second deadline.
- B’s first GC-round completion and release handshake occur afterward.

Evidence: `W/build/review-mt/w2mt-blocked.out:7–16,25–29`. The committed record shows the same sequence (`MT/witnesses/results/w2mt-blocked.out:8–17,26–30`).

The synchronization source supports the mechanism: `___garbage_collect` calls `on_all_processors`; that interrupts participating processors and enters the barrier (`S/lib/setup.c:1138–1149,1182–1196`). Additional Scheme progress does not make the blocked processor participate.

**Foreign-entry evidence supports refusal, with narrower conclusions.**

I reproduced the MT owner control PASS and naive serialized-entry SIGSEGV. Valid signatures are present (`MT/witnesses/w3mt-server.scm:16–23`); the committed concurrent naive run also segfaults (`MT/witnesses/results/w3mt-p2.out:4–6`).

The exploratory bridge initializes slot 2 while leaving `processor_count=2`, then binds TLS (`MT/witnesses/w3mt-main.c:194–229`). That omission is independently visible in source: VM interrupts cover only counted processors (`S/lib/setup.c:95–106`). TLS binding plus successful arithmetic is not a complete attachment protocol.

Two qualifications are necessary:

- **New P2 finding — teardown corruption is NOT CONFIRMED.** `keep_attached` is documented and passed as true, but never honored. The helper unconditionally clears TLS and cleans up the pstate (`MT/witnesses/w3mt-main.c:188–192,275–286`). Its caller then invokes Scheme in the purported “while attached” loop (`:325–331`). The recorded crash follows that cleanup (`MT/witnesses/results/w3mt-bridge-concurrent.out:12–15`). This is an invalid post-detachment entry, not an isolated demonstration that teardown corrupted a correctly attached runtime.
- **P2 evidence limitation — the bridge GC timeouts do not isolate membership as their sole cause.** In the serialized bridge and reattach tests, the owner is parked in `pthread_join` (`MT/witnesses/w3mt-main.c:434–440,470–472`). That counted processor cannot service GC synchronization either. The recorded 120/180-second timeouts establish failure of these attempted sequences; they do not prove that every possible attachment sequence must deadlock. Repeated attachment and later lifecycle checks were never reached.

**New P3 finding — “collection completed 183µs after C’s return” is NOT CONFIRMED.** The 183µs value is a GC-report duration, not a measured return-to-completion interval (`MT/witnesses/results/w2mt-blocked.out:13–17`). GC timing begins inside the collector (`S/lib/mem.c:6954–6961`). The record establishes completion after C returns and an approximately six-second request-to-round-completion wait. It does not establish that precise 183µs lag.

Thus, **MT-VERDICT’s operational conclusion is correct**: this tested configuration fails the blocking contract, and foreign-thread entry remains unqualified. Its teardown attribution and universal attachment-impossibility wording need amendment. The startup/heap crashes remain configuration-specific observations, not established root-cause diagnoses.

**C4 — Final track verdict under astra’s criteria**

Yes: the standing ruling is **PARTIAL-GO for bounded P1b work, conditional on qualification of each implemented capability; blocking and foreign-thread rows retain honest refusal; P2/P3 remain Chez-only.**

Astra §5 states verbatim:

> **Partial GO:** memory, loading, ordinary calls, and errno may land independently when qualified. Retain blocking/callback refusals and describe the result as partial FFI. This does **not** authorize P2/P3 parity claims.

Its NO-GO criteria are:

> **NO-GO for P2/P3:** collection cannot complete while C is blocked; unrelated C-thread entry cannot be made safe where required; exact runtime ownership rules remain unknown; or the solution depends on silently moving generic calls to another native thread.

The blocking failure independently sustains NO-GO on both tested configurations. No universal foreign-entry impossibility proof is required to reach that ruling.

The next-path language remains:

> If 4.9.8 cannot meet the necessary runtime transitions, the next decision is an explicit Gambit upgrade or runtime patch through the upgrade gate. The fallback remains honest refusal, with Chez retaining the affected capability.

P1b must not assert:

- GC-safe blocking or blocking-plus-errno qualification.
- Generic foreign-pthread callbacks, including serialized entry.
- Arbitrary dynamic closure-backed callables; F6’s architecture/trampoline constraint remains.
- Unrestricted yielding, migration, exception escape, or nested callback behavior.
- Qualified memory, loader, ABI, or public jolt behavior solely from these standalone fixtures.
- Linux qualification, full FFI parity, or qualification of AOT/FASL/subprocess capabilities.

Qualified callable modes can be included only with their own public-path behavioral gates and explicit restrictions.

**C5 — Readiness for a P1b brief**

**No further runtime experiment is necessary before writing the bounded brief.** It should:

1. Pin the exact runtime/configuration; MT is an unsuccessful experiment, not a qualified replacement.
2. Carry the inventory Q1–Q6, F6 trampoline constraint, real-GC replacement, and per-capability refusals.
3. Define callable ownership and lifetime rules, catch exceptions inside callbacks, and reject unsupported native-thread entry before executing Scheme.
4. Require target-identified public jolt tests, both platform legs for acceptance, negative controls, patch/seed reproducibility, rollback, and preserved regressions (`Plan:267–275`).
5. Correct or explicitly exclude the MT teardown and timing claims above.

Linux and remaining lifecycle experiments are prerequisites for future qualification claims, not blockers to documenting and implementing the bounded slice.

CONFIRMED-VERDICT — PARTIAL-GO for bounded P1b memory/loader/ordinary-call/errno work and independently qualified callable modes; blocking and foreign-thread rows remain refused, with P2/P3 Chez-only unless a separately chosen upgrade/runtime-patch path passes qualification.
