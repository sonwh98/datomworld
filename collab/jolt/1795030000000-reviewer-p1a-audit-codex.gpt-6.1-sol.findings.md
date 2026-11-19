Created-GMT: 2026-11-18 16:58:56 GMT
Created-Local: 2026-11-18 23:58:56 ICT (+07)
Completed-GMT: 2026-11-18 16:58:56 GMT
Completed-Local: 2026-11-18 23:58:56 ICT (+07)
Coding-Agent: codex
Session-ID: 01a1ef93-bee4-7c00-9752-4e034b3efcd1
Created-GMT: 2026-11-18 16:53:07 GMT
Created-Local: 2026-11-18 23:53:07 ICT (+07)
Role: Review

**The provisioned build’s NO-GO for P2/P3 is correct, but the audit’s supporting claims require amendment.** W3 has an argument-signature defect that invalidates its crash attribution. W1 allocates before saving errno, violating the sequence it claims to prove. A multithreaded rebuild changes scheduling and processor-state access, but the inspected source does not establish GC-safe blocking or documented foreign-pthread attachment.

Reviewed jolt commit `dc6176dcc019772c879864a60906217f646b0b3e` and Gambit source `2228e4d163582733a3a67a5efcb22f8ebe93c02b`. Tracked P1a files and Gambit sources remain unchanged. No commit, push, runtime rebuild, or findings-file write was performed.

References below use:

- `S/`: `/Users/sto/workspace/gambit-v4.9.8-src/`
- `H`: `/Users/sto/.local/share/mise/installs/gambit/4.9.8/include/gambit.h`
- `W/`: `/Users/sto/workspace/jolt-ffi-p1/p1a/witnesses/`
- `Plan`: the supplied astra design findings.

**Q1 — Reproduction**

I ran `make clean && make all` using byte-identical copies of every witness input inside `W/build/`. This relocation was necessary: the supplied Makefile writes executables beside the sources and deletes/replaces tracked `results/` (`W/Makefile:26–41,45–101`), contrary to the review’s build-directory-only write allowance.

The make invocation completed successfully. Its process status is a recording result, not capability qualification.

| Recorded result | Reviewer rerun |
|---|---|
| `w0.out` | Exact match: single-threaded VMs, maximum/current processors 1, resize remains 1. |
| `w1.out` | Exact match: 4000 checks, zero failures, printed PASS. Evidence defect discussed below. |
| `w2-control.out` | Same behavior: real GC reports and printed PASS. |
| `w2-blocked.out` | Same behavior: approximately six-second block; all B progress and subsequent GC reports follow C’s return; printed FAIL. |
| `w3-phase1.out` | SIGSEGV, exit 139. Additional repetitions: 3/3 exit 139. |
| `w3-phase1-nobeat.out` | SIGSEGV, exit 139. Additional repetitions: 3/3 exit 139. |
| `w3-phase2-run1.out` | Exact match: exit 70. |
| `w3-phase2-run2.out` | **Mismatch: recorded exit 70; reviewer rerun SIGSEGV, exit 139.** |
| `w3-phase3.out` | SIGSEGV, exit 139. Additional repetitions: 3/3 exit 139. |
| `w4-mode1.out` | Same results: M1=40, M2=1107, M3 B result=90 and probe=0. |
| `w4-mode4-run1/2/3.out` | All three exact matches: callback result 1005; exit 0. |
| `w4-mode5.out` | Exact match: deliberate exception, exit 70. |

Except for `w3-phase2-run2.out`, differences were timestamps, GC statistics, or crash-report PIDs. The phase2 discrepancy agrees with the audit’s nondeterminism caveat; it does not change qualification.

Reviewer logs are under `W/build/results/`. Generated W3 C was retained there through a separate `make -B w3.exe` using `gsc -keep-temp`.

**Q2 — Evidence audit and findings**

**F1 — P1: W3 exports one argument to Scheme procedures requiring two; its crashes do not isolate foreign-thread entry.**

Both definitions specify `(int)` despite requiring Scheme formals `(x y)`:

- [w3-server.scm:29](/Users/sto/workspace/jolt-ffi-p1/p1a/witnesses/w3-server.scm:29)
- `W/w3-server.scm:35`

The C fixture declares two-argument functions and invokes them accordingly (`W/w3-fthread.c:34–35,60–65`).

Retained generated C confirms the mismatch:

- `W/build/w3-server.c:197–206`: `scheme_w3_add(int ___arg1)`, `___NARGS 1`.
- `W/build/w3-server.c:222–231`: the same one-argument entry for `scheme_w3_add_alloc`.
- Generated Scheme procedure descriptors require two arguments (`:1515–1520`).

The manual explicitly says the C argument types determine the arguments converted and passed to the Scheme procedure (`S/doc/gambit.txi:19399–19412`).

Consequently, the 3/3 crashes reproduce a malformed entry fixture. They cannot prove the audit’s specific explanation of foreign-thread failure, or its claimed W3/W4 frame-state discriminator (`p1a/AUDIT.md:287–312,355–368,483–490`).

Required correction: use `(int int)`, verify matching generated C declarations, and establish a successful owner-thread control before comparing serialized and concurrent foreign-thread entry. I did not alter sources or execute a corrected variant.

**F2 — P1: W1 violates capture-before-allocation and therefore does not qualify atomic errno capture.**

The shims read errno after `malloc`:

- [w1-fixture.c:35](/Users/sto/workspace/jolt-ffi-p1/p1a/witnesses/w1-fixture.c:35): target → errno assignment → `malloc` → errno read (`:35–40`).
- Shape B: target → `malloc` → errno read (`:51–55`).
- Shape C: target → errno assignment → `malloc` → errno read (`:65–70`).
- Shape E: errno assignment → `malloc` → errno read (`:87–90`).

Shapes A and C also manufacture the expected errno after their “target” returns, rather than preserving the target’s errno.

This contradicts Plan §1’s requirement to save errno “before conversion, cleanup, allocation, or runtime re-entry” and the immediate-save sequence in §2. Post-capture clobbers cannot detect interference before capture.

The observed 4000/4000 is reproducible. The stronger claims at `p1a/AUDIT.md:440–462,528–531` are not established. Save `int saved_errno = errno` immediately after the actual target invocation, before allocation; add an intervening-clobber negative control.

**F3 — P2: W2 lacks the advertised runnable contender and completion handshake; its verdict is unconditional.**

`W/w2-blocking.scm:95` says B is runnable before A enters C. Actual execution starts A, sleeps on the owner thread, and only then starts B (`:98–102`). On this build that owner thread cannot wake until C returns, so B has not been started during the blocked interval.

The C handshake release exists (`W/w2-fixture.c:49–54`) but Scheme never calls it. The blocked mode always prints FAIL (`W/w2-blocking.scm:106`). The control always prints PASS (`:91`); in both recorded and reviewer outputs, B’s GC rounds finish before A’s first nonblocking call.

W2 still demonstrates starvation of the owner’s scheduled continuation, and its post-return collections are real. Combined with the single-processor source evidence, the provisioned blocking refusal remains justified. It is not a reusable success/failure discriminator for another configuration.

Future qualification must start and synchronize both participants before blocking, release C only after B completes GC, and derive the verdict from observed state.

**F4 — P2: The all-configuration conclusion needs the multithreaded GC and scheduler analysis given in Q3.**

The audit correctly identifies the provisioned configuration, but extends that into categorical claims about all 4.9.8 implementations (`p1a/AUDIT.md:134–165,613–616`). A search for guessed leave/claim names and single-threaded witnesses is insufficient for that extension.

The source contains materially different TLS, processor startup, SMP scheduling, and cross-processor return paths. These do not themselves satisfy the contract, but they must be considered before declaring the build question closed.

**F5 — P3: Several doc-first assertions and precise citations do not hold as written.**

Spot-checks that hold include the installed threading configuration (`H:1469–1491`), C-interface nesting warning (`S/doc/gambit.txi:19983–19992`), GC verbosity (`:2230–2237`), `___garbage_collect` declaration (`H:14772–14775`), and `(##gc)` implementation (`S/lib/_kernel.scm:2051–2069`).

Corrections:

- `p1a/AUDIT.md:47–49` says the Threads chapter mentions no OS threads/processors. Its opening explicitly discusses both and says the runtime does not exploit multiprocessors (`S/doc/gambit.txi:9843–9847`). That manual text also omits the source’s optional SMP variant.
- The conversion-temporary guarantee cited at manual `19296–19300` is actually at `19305–19307` (`p1a/AUDIT.md:414–419`).
- The `___AT_END` guarantee cited at `19313–19319` is actually at `19328–19332` (`p1a/AUDIT.md:419–420`).
- The stack-marker rooting TODO cited at `S/lib/c_intf.c:6528–6529` is at `:6530`.
- The resize clamp cited at `S/lib/setup.c:1163–1166` is at `:1169–1170`. GC’s `on_all_processors` call is at `:1196`, outside the cited `1181–1194` interval.

Header declarations are useful evidence of available functions; they do not, by themselves, document a foreign-thread lifecycle protocol.

**Q3 — Adjudication of a differently configured 4.9.8 build**

**Configuration is consequential, but changing configuration alone does not establish the required capabilities.**

First, `--enable-single-host` is code generation, not the cause of single-threaded VMs. The mise plugin passes that option and omits the threading options (`~/.local/share/mise/plugins/gambit/lib/utils.bash:68`), leaving their defaults in effect.

The relevant 4.9.8 options are:

- `--enable-multiple-threaded-vms`: enables multiple OS threads per VM (`S/configure.ac:1267–1283`).
- `--enable-smp`: independently selects the SMP Scheme scheduler (`:1287–1299`).
- `--enable-max-processors=N`: raises the maximum only with multiple-threaded VMs (`:1303–1316`).

I found no `--enable-multiprocessor` option in this source.

**(a) Release/claim around foreign calls: no qualifying mechanism established in either configuration.**

The FFI macros save and restore Scheme registers around C execution (`H:10310–10334`); they do not mark that processor absent from GC participation or transfer execution ownership.

The multithreaded branch changes processor lookup from the fixed primordial state to TLS:

- Single-threaded: `S/include/gambit.h.in:8607–8612`.
- Multithreaded: TLS getter/setter or TLS variable at `:8673–8685`.

TLS selection supplies a processor-state address. It is not a release/claim protocol. Neither the manual nor the inspected alternate FFI paths supply the required leave/re-enter lifecycle.

**(b) GC completing while a normal foreign call remains blocked: the multithreaded source retains a decisive obstacle.**

The synchronization path is:

1. `___garbage_collect` requests `OP_GARBAGE_COLLECT` through `on_all_processors` (`S/lib/setup.c:1182–1196`).
2. `on_all_processors` interrupts the VM’s processors and enters the synchronization loop (`:1132–1149`).
3. The multithreaded barrier includes `vms->processor_count` participants and waits for their messages (`:500–515,554–580`).
4. Collection executes after barrier selection (`:1065–1068,1098–1099,1028–1029`); the collector itself also uses barriers (`S/lib/mem.c:7002`).

A processor stuck in an ordinary synchronous C call does not execute `service_sync_op`. The FFI macros do not exempt it. Thus additional processors may permit Scheme progress before a collection request, but do not establish collection completion while that C call remains blocked.

This is a source-based conclusion, not an executed multithreaded result. Any proposed bridge that changes this behavior must identify the exact transition and root-update rules it uses.

The separate SMP scheduler matters: `S/lib/_thread.scm:9–24` selects its implementation; `:3110–3125` starts scheduling on additional processors. The non-SMP additional-processor startup instead waits for interrupts (`:6866–6889`). Multiple-threaded VMs alone must not be equated with parallel scheduling of the witness’s ordinary Scheme threads.

**(c) Documented foreign-pthread entry: no.**

The manual’s server pattern requires `___setup` before calls (`S/doc/gambit.txi:19440–19452`), but provides no unrelated-pthread attach/claim/detach procedure.

The alternate runtime does contain ingredients:

- Runtime-created processors bind their OS thread to a pstate (`S/lib/setup.c:805–835`).
- Binding uses `___thread_set_pstate` (`S/lib/os_thread.c:697–708`).
- Processor creation includes state initialization, synchronized membership changes, and OS-thread creation (`S/lib/setup.c:940–964,987–996`).

These operations are not a documented recipe for attaching an arbitrary caller. An unregistered pthread does not acquire a valid processor merely by calling `c-define`. The multithreaded TLS path changes the failure mechanism from shared-state reuse; it does not automatically make entry valid. The cross-processor C-return guard (`S/lib/_kernel.c:11743–11755`) likewise protects return routing rather than attaching new threads.

**Version property or configuration property?**

The one-processor starvation is a **configuration property**. The missing documented attachment protocol and the inspected synchronous FFI/all-processor-GC behavior are limitations of the **stock 4.9.8 source**, including its multithreaded variant.

Accordingly, neither “W2/W3 prove every 4.9.8 configuration impossible” nor “enable SMP and the contract is satisfied” is warranted. Configuration-only adoption does not clear the gate.

**Experiment needed to challenge this conclusion**

Use a separate, pinned 4.9.8 build with `--enable-multiple-threaded-vms --enable-smp`, at least two processors, and explicit runtime parallelism such as `-:p2` (`S/lib/main.c:46–49`). Record compiler, configure arguments, header, library, and processor identities.

Then:

1. Use valid processor startup. Do not blindly reuse W0’s `(##current-vm-resize #f 4)`: new processors execute the supplied thunk (`S/lib/setup.c:810–835,992–994`). Use normal startup parallelism or the runtime’s scheduler startup path.
2. Repair W2’s synchronization. Confirm A is inside C and B is executing on a different processor; require B to finish a real collection and update live rooted objects **before** it signals C to return. Detect timeout independently.
3. Run both ordinary `c-lambda` and any specifically proposed release/claim bridge. Cite the latter’s exact implementation.
4. Repair W3’s signatures and establish the owner-thread control. Test serialized and concurrent foreign entry separately, then allocation/GC, nesting, cleanup, and repeated attachment. Any proposed attachment sequence must explain membership, ownership, TLS, catcher, roots, and teardown.
5. Run on macOS arm64 and Linux x86_64. A changed crash signature or extra Scheme progress alone is not a pass.

No multithreaded rebuild was executed in this review.

**Q4 — Verdict sanity against astra’s criteria**

The engineer’s **NO-GO for P2/P3 on the provisioned build is correct**. Blocking remains unqualified, and foreign-thread entry lacks a verified protocol. However, withdraw “W3 proves unrelated entry cannot be made safe”: F1 invalidates that experimental inference.

Plan §5 states verbatim:

> **Partial GO:** memory, loading, ordinary calls, and errno may land independently when qualified. Retain blocking/callback refusals and describe the result as partial FFI. This does **not** authorize P2/P3 parity claims.

W1’s qualification claim must be repaired under F2. Memory and loading were not qualified by P1a. This is authorization to pursue a bounded P1b, not acceptance of the complete basic tier.

Plan §5’s NO-GO criteria are:

> **NO-GO for P2/P3:** collection cannot complete while C is blocked; unrelated C-thread entry cannot be made safe where required; exact runtime ownership rules remain unknown; or the solution depends on silently moving generic calls to another native thread.

**Adopting multithreaded 4.9.8 does not change the track to GO.** P2/P3 remain NO-GO pending exact mechanisms and passing qualification. That adoption reopens configuration-specific P1 evidence; current single-threaded observations cannot qualify it.

The upgrade language remains applicable verbatim:

> If 4.9.8 cannot meet the necessary runtime transitions, the next decision is an explicit Gambit upgrade or runtime patch through the upgrade gate. The fallback remains honest refusal, with Chez retaining the affected capability.

An alternate configuration is not itself a runtime patch. It nevertheless requires explicit provisioning and qualification. If the proposed solution changes runtime transitions, it falls under that upgrade/patch decision.

Full GO additionally requires both platforms, target-identified public jolt execution, qualified callable modes/refusals, memory safety, patch/seed reproducibility, rollback, and preserved regression gates (`Plan:267–275`).

**Q5 — Additional material implications for P1b**

**F6 — P2: Static callbacks do not qualify dynamic closure-backed callables; the built-in dynamic trampoline generator lacks both target architectures.**

The manual permits procedure-to-function conversion (`S/doc/gambit.txi:19098–19101`). Its implementation distinguishes static `c-define` representatives from other procedures:

- Static representative lookup: `S/lib/c_intf.c:2914–2923`.
- Other procedures require `___make_c_closure`; failure becomes a conversion error (`:2925–2933`).
- `C_CLOSURE_CODE_SIZE` is supplied only for 32-bit x86, selected PowerPC, and SPARC cases (`S/lib/os_dyn.c:639–670`).
- Without it, `___make_c_closure` returns null (`:758–760`).

There is no corresponding arm64 or x86_64 generator here. Furthermore, the existing generator’s closure identity storage is global with a TODO to make it thread-local (`:744–745`).

W4’s static `c-define` functions therefore do not qualify arbitrary runtime closures. P1b needs independently qualified stable trampolines and rooted closure identity/lifetime, or explicit refusals.

Other decision-relevant limits:

- **Qualification must assert behavior.** W2/W4 print some verdicts unconditionally; Makefile recording recipes consume child statuses and continue (`W/Makefile:45–95`). Preserve these as diagnostic recorders, but make actual qualification reject wrong values, absent handshakes, timeouts, and unexpected exits.
- **Same-thread callbacks still have scheduling restrictions.** The manual warns about switching between nested Scheme→C→Scheme chains (`S/doc/gambit.txi:19983–19992`). W4’s simple nesting does not qualify callbacks that yield, block, or migrate.
- **Exception containment remains necessary.** W4-M5 reproduces fatal exit 70; the server example catches exceptions inside the callable (`S/tests/server.scm:13–17,32–34`).
- **Real collection remains separate work.** Gambit’s adapter still returns `#f` from `sa-gc-collect` (`host/gambit/scheme-adapter-runtime.ss:81–82`). Runtime `(##gc)` exists, but bridge-level reports do not qualify public jolt GC behavior.
- The inventory lane’s compiler refusal gaps, omitted callable contract entries, parser destruction wait, and unmarked SSL waits remain P1b follow-ups. A runtime rebuild does not resolve those construction and capability-boundary questions.

VERDICT-AMENDED — NO-GO for P2/P3 on the provisioned build; a multithreaded 4.9.8 configuration remains unqualified and does not clear that verdict. PARTIAL-GO permits bounded P1b work only as each capability is independently qualified; W1 atomicity and W3 foreign-entry claims require correction.
