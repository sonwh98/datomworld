Created-GMT: 2026-11-18 18:52:24 GMT
Created-Local: 2026-11-19 01:52:24 ICT (+07)
Completed-GMT: 2026-11-18 18:52:24 GMT
Completed-Local: 2026-11-19 01:52:24 ICT (+07)
Coding-Agent: glm
Session-ID: zcode-subagent-p1a-mt
Role: Engineer (VM Runtime Engineer, P1a-MT multithreaded-4.9.8 experiment)

Executed the codex review's five-step experiment (review findings
`1795030000000-reviewer-p1a-audit-codex.gpt-6.1-sol.findings.md`, Q3
"Experiment needed") on macOS arm64. Worktree `/Users/sto/workspace/jolt-ffi-mt`
branch `gambit-ffi-mt` at commit `1b9b6ed0` (base `a6852ef8`); everything under
`p1a-mt/` is committed there, never pushed. Gambit source pin
`/Users/sto/workspace/gambit-v4.9.8-src` at `2228e4d163582733a3a67a5efcb22f8ebe93c02b`
(git tag `v4.9.8` exactly); `~/workspace/jolt-ffi-p1` used READ-ONLY (only
`watchdog.sh` copied verbatim); `~/workspace/gambit-v4.9.8-src` never modified
final state verified: `git status --porcelain` = 0 entries at the pin (see
step 1 caveat for a repaired mid-experiment accident).

**Bottom line: the multithreaded 4.9.8 build does NOT clear the P2/P3 gate.**
With the SMP scheduler genuinely running on 2 processors, an ordinary blocking
c-lambda call still blocks all VM-wide collection until it returns (the
review's source-based conclusion (b) is confirmed empirically, not just on
paper), and no documented foreign-pthread entry protocol exists (c). The
review's "A changed crash signature or extra Scheme progress alone is not a
pass" standard is applied: B made real Scheme progress on a different
processor while C was blocked — the qualification still fails because no
collection completed during the block.

## Step 1 — build Gambit 4.9.8 out-of-tree with the threading options

Configure (run in `p1a-mt/gambit-builds/mt`, from the pinned source):

```
/Users/sto/workspace/gambit-v4.9.8-src/configure \
  --enable-single-host --enable-multiple-threaded-vms \
  --enable-smp --enable-max-processors=8
```

Compiler Apple clang 21.0.0, macOS 26.6.2 (Darwin 25.6.0) arm64, 8 CPUs;
`make -j8` under a 3600s watchdog. There is no `--enable-multiprocessor`
option (verified; configure.ac:1267-1316 defines the three used options).
`--enable-single-host` mirrors the provisioned mise build (its plugin passes
exactly that flag), so threading is the only intended variable.

Generated `gambit.h` (build tree): `___MULTIPLE_THREADED_VMS` defined,
`___MAX_PROCESSORS 8`, `___THREAD_LOCAL_STORAGE_CLASS __thread` with direct
TLS-class pstate access (`___tls_ptr`, os_thread.c:802). The provisioned
single-threaded installed header has `___SINGLE_THREADED_VMS` and
`___MAX_PROCESSORS 1` instead — the macros differ exactly as required. W0MT
records the same at runtime (`witnesses/results/w0mt.out`):
`___MULTIPLE_THREADED_VMS=defined`, `___MAX_PROCESSORS=8`,
`parallelism-level=2`, `##current-vm-processor-count=2`, `smp-feature=ENABLED`.

**Four out-of-tree build defects had to be fixed (all fixes inside the build
tree only; full detail in `p1a-mt/BUILD-CONFIG.md`):** the `dummy-gsc-boot`
rule copies by bare relative path; `GAMBITDIR_INCLUDE`/`GAMBITDIR_LIB` and
the include dependency lines resolve to the source tree (where the generated
`gambit.h` is not); `_kernel.c` needs `stamp-release.h` from the source
include dir; `gsc -exe` needs `_gambit.c` in `~~lib`. Verbatim evidence in
`p1a-mt/gambit-builds/logs/` and `make.log`/`make-core.log`.

**The prebuilt lib .c files carry the NON-SMP scheduler.** The repo's checked-in
`lib/_thread.c` was generated with default features; `--enable-smp` only
injects `enable-smp` into `PRELUDE_OPT` (lib/makefile.in:173), consumed only
when the .scm sources are regenerated — which the dummy gsc-boot deliberately
skips. The first build therefore had multiple-threaded VMS but the cooperative
NON-SMP scheduler; its intermediate results are preserved verbatim in
`witnesses/results-nonsmp/` (B was never even scheduled during the block —
both threads pinned to pid 0). Getting the review's REQUIRED configuration
(`--enable-smp` actually in effect) needed a full bootstrap: a plain boot
compiler built from a `git archive` copy of the pin inside `boot/` (the
top-level `make bootstrap` cannot work here — Gambit's git detection resolves
the enclosing jolt-ffi-mt repository, not Gambit's), then regeneration of the
10 runtime modules with the boot compiler + the SMP prelude into the build
tree (explicit `-o`), manual .o compilation (the generated .o rules hard-code
the SOURCE .c as input via `BUILD_OBJ_INPUT_FILENAMES_PARAM`), re-archive,
relink of gsi/gsc. Verification: regenerated `_thread.c` has 91
`schedule_2d_with_2d_acquired` occurrences; a compiled probe shows two busy
threads simultaneously on processor ids 0 and 1; W0MT reports
`smp-feature=ENABLED`.

**Caveat, disclosed in full:** Gambit's `gsc -c` writes the generated .c NEXT
TO THE INPUT FILE, and the out-of-tree .c rules pass the source-tree input
path — my first (heap-fatal) regeneration attempt therefore truncated 6
checked-in `lib/*.c` files in the source tree. They were restored immediately
with `git checkout --` and the tree was re-verified clean (`git status
--porcelain` = 0, HEAD `2228e4d1...`) before any further step; the final
state is byte-identical to the pin. All later regenerations used explicit
`-o` into the build tree.

## Step 2 — explicit runtime parallelism, no resize thunk

W0MT/W2MT are started with `-:p2` (gsi/gsc driver parses it, main.c:46-49);
W2MT records additionally `d5` (verbatim `*** GC:` collection reports) and
explicit heap bounds. W3MT (the C-driven server pattern) sets
`setup_params.parallelism_level = 2` directly — the library-program equivalent
(the same consumed field; `___setup_params_reset` would default it to 0).
`(##current-vm-resize #f 4)` is NOT used anywhere (review warning: new
processors execute the supplied thunk, setup.c:810-835,992-994). Every run is
under the external `watchdog.sh` (copied verbatim from p1a) with explicit
timeouts; W2MT/W3MT additionally carry C-side deadlines and state-based
verdicts.

## Step 3 — blocking-GC witness with correct synchronization (W2MT)

Protocol (the review's F3 correction, implemented exactly; source
`p1a-mt/witnesses/w2mt.scm` + `w2mt-fixture.c`, records
`witnesses/results/w2mt-control.out`, `w2mt-blocked.out`):

1. A and B synchronized BEFORE A enters C: A waits for B's ready flag.
2. A enters the blocking foreign call `w2mt_block(6)` (ordinary c-lambda);
   the C fixture sets an atomic "inside" flag and logs ENTER before blocking.
3. B busy-spins (NO `thread-sleep!` — a sleeping thread's timeout is serviced
   by the device loop of the processor it slept on; with B sleeping on the
   blocked processor it never woke, observed in the first run — so the spin
   keeps B runnable and the scheduler places it on the other processor) until
   it observes A inside C, then runs 3 GC rounds: each round updates LIVE
   ROOTED objects (global vector cells replaced with populated 1000-slot
   vectors + a global counter), allocates fresh garbage, and performs a REAL
   collection — `(##gc)` plus the documented C API `___garbage_collect`
   (gambit.h) — and logs its processor id on every line.
4. Only AFTER all rounds complete may B call `w2mt_release_handshake`.
5. The C fixture returns HOW the block ended (0 = handshake-released,
   1 = C-side deadline) — independent timeout detection; the verdict is
   computed from observed state (return reason + B's processor ids vs A's +
   live-root integrity), never printed unconditionally.

**Control (`results/w2mt-control.out`): PASS** — 3 real collections completed
(`*** GC:` runtime reports) with another thread making non-blocking foreign
calls on the same build; live rooted objects intact. The MT+SMP GC machinery
itself works.

**Blocked (`results/w2mt-blocked.out`): FAIL — collection did NOT complete
while C was blocked.** The decisive record: A entered C on processor id 1
(pid=1 logged at entry) and blocked 6.005s until its C-side deadline; B
executed on processor id 0 DURING the block (spin log lines with advancing
timestamps, pid=0 ≠ A's pid=1 — the review's "B provably running on a
DIFFERENT processor" precondition is met); B observed A inside C and called
`(##gc)` at t=...884.495346 WHILE C was blocked — the collection waited the
full 6.33s and its `*** GC: 183us` report appears interleaved with C's
RETURNING line; every round-done line is after RETURNING; C returned by
DEADLINE (reason=1), not handshake; live roots intact after the
post-return collections. So the SMP build allows extra Scheme progress
(allocation/round-setup on the free processor) but the collection barrier
(setup.c:1182-1196 `___garbage_collect` → `on_all_processors` → tree barrier
over `___vms->processor_count`, setup.c:500-580) waits for the processor
parked inside the foreign call, freezing the whole VM until C returns.

**Release/claim bridge: NONE implemented — and none exists to implement.**
Searches over the stock 4.9.8 source and headers for release/claim/suspend/
detach/absence/leave/reclaim mechanisms found nothing: the FFI macros only
save/restore Scheme registers around C execution (gambit.h:10310-10334); no
processor is ever marked absent from GC participation; `OP_GARBAGE_COLLECT`
is `COMBINING_ADD` over every processor (`processor_count` participants).
Absence of a mechanism is itself the finding (review step 3).

## Step 4 — foreign-pthread witness with correct signatures (W3MT)

Source `p1a-mt/witnesses/w3mt-main.c` + `w3mt-server.scm` (manual server
pattern build, records `witnesses/results/w3mt-*.out`).

**F1 correction verified in generated C:** `results/w3mt-generated-signatures.txt`
shows `scheme_w3mt_add ___P((int ___arg1,int ___arg2)` with `___NARGS 2` (and
same for `scheme_w3mt_add_alloc`) — the two-formal defect of the P1a W3 is
fixed; runtime values confirm (add(20,22)=42, add_alloc(50,22)=1072).

**Owner-thread control (review: "establish a successful owner-thread control
before comparing"): PASS** (`results/w3mt-owner.out`). The OS thread that ran
`___setup` (whose TLS is bound to processor 0 by `___setup` itself,
setup.c:6383-6406) round-trips C→Scheme→C through the same entry points used
later by the foreign pthread: trivial add, allocating add, and 2 real GC
rounds — all correct. The documented server pattern WORKS on the MT+SMP build
from the owner thread (this is new, positive evidence vs the single-threaded
build's heartbeat-sensitive phase1).

**Naive foreign-pthread entry (no registration): SIGSEGV, exit 139, in BOTH
serialized and concurrent modes** (`results/w3mt-p1.out`, `w3mt-p2.out`). The
raw pthread's TLS pstate is NULL (`___GET_REAL_PSTATE()` reads thread-local
`___tls_ptr`, bound only by `___thread_set_pstate` at setup.c:6402 for the
primordial and setup.c:819 for runtime-created processors) — first Scheme
entry faults. On this MT build the crash is IMMEDIATE and deterministic
(different signature from the single-threaded build's heartbeat-dependent
serialized reuse), and it is not caused by my P1a signature defect (F1) —
the entry is still invalid without registration.

**Best-effort attach bridge (review step 4: "only if you implement a
release/claim bridge — that bridge with its exact implementation cited";
here the closest analog — the attach bridge assembled from the
exported-but-undocumented surface — WAS implemented and is cited exactly):
entry survives; GC and teardown do not** (`results/w3mt-bridge.out`,
`w3mt-bridge-concurrent.out`, `w3mt-reattach.out`). Exact sequence, from the
worker pthread (all six ingredients documented, cited, and observed):

- membership: never established. `vms->processor_count` is read (2) and the
  pstate is initialized at array slot `aligned_pstate[processor_count]` = 2
  (identity IS array position, gambit.h:11966-11973,11934-11936) — but
  `processor_count` is NOT updated (`___setup_pstate` does not touch it;
  membership is only changed via the OP_SET_PROCESSOR_COUNT barrier,
  setup.c:940-963) — the attached processor is invisible to every
  VM-wide operation (`___raise_interrupt_vmstate` iterates slots
  `0..processor_count-1`, setup.c:95-104; GC scan work-stealing covers the
  same range, mem.c:6269-6280, 6678-6702).
- ownership: the pstate storage is the VM's own pre-allocated
  `aligned_pstate[]` slot; its initialization is a direct call to the
  EXPORTED but UNDOCUMENTED `___setup_pstate` (gambit.h:14782; sets up
  os+mem, the Scheme processor object, registers, handlers, catcher —
  setup.c:4675-4760). No API contract; runtime-internal machinery invoked by
  foreign code.
- TLS: bound via `___tls_ptr = ps` (direct TLS class mode on this build;
  `___set_tls_ptr` in getter/setter mode) — observed bound value printed.
- catcher: handlers copied by `___setup_pstate` (handler_return_to_c etc.),
  catcher=0; the foreign thread's REAL C stack is never registered — the
  Scheme stack used is the pstate's own allocated stack.
- roots: none for the attached processor — its stack/heap are NOT scanned by
  collections run by the other processors (citations above), and it never
  receives interrupts, so it also never stops mutating during collection.
- teardown: `___tls_ptr = 0` + `___cleanup_pstate` (gambit.h:14786;
  setup.c:4665-4672) — returns, but the next Scheme entry after teardown
  SIGSEGVs and the process dies shortly after cleanup with the owner still
  allocating (observed in bridge-concurrent) — no detach protocol exists.

Observed bridge results: `___setup_pstate` returns 0; TLS binds; FIRST
Scheme entry on the attached pstate EXECUTES (pid=2 — the array-slot id);
`scheme_w3mt_add(20,22)`=42 and the allocating `add_alloc`=1072 return
correct values. Then REAL collections FROM the attached pstate
(`scheme_w3mt_gc_rounds`) HANG THE ENTIRE VM — watchdog SIGKILL at 120s/180s
in both `bridge` and `reattach` (repeated attachment never reached: cycle 1
hangs). With an initiator id >= `processor_count`, the tree barrier
(setup.c:500-580) has undefined structure: deadlock is the observed
resolution. This is precisely the review's predicted membership hole,
demonstrated live.

**Qualification: FAIL.** Even the best case (undocumented bridge, entries
returning correct values) is not a documented protocol, does not establish
GC membership, deadlocks on collection, and corrupts on teardown. The review's
(c) stands with richer evidence.

## Step 5 — platform

macOS arm64 executed (this record). Linux x86_64 leg: PENDING — follow-up,
not run here (no ssh per brief).

## Side findings (recorded verbatim, tangential to the verdict)

1. Stock DEFAULT parallelism (0 → 50% of 8 CPUs = 4) fails to boot on this
   platform: `*** FATAL ERROR -- Heap overflow`, exit 70 — recorded in
   `results/mt-default-parallelism.out` for both gsi and compiled programs.
   Only explicit smaller parallelism (or explicit heap) boots. The review's
   insistence on explicit `-:p` is also a correctness requirement here.
2. Heap GROWTH under -:p2 crashed the compiler deterministically during
   `gsc -exe` of a multi-module program (SIGSEGV in `___H___gvm`, data abort
   writing near-NULL far=0x51 on the primordial thread while processor 1
   idles in the scheduler; crash report preserved at
   `p1a-mt/gambit-builds/logs/gsc-crash-report.ips`). A large initial
   min-heap (256M for the compiler, 64M for witnesses) avoids growth entirely
   and the crash disappears. This suggests the MT build's heap-growth path
   (msection pool growth with multiple processors) is fragile on macOS arm64.
3. The intermediate NON-SMP-scheduler run (same configure, prebuilt lib .c)
   is preserved in `witnesses/results-nonsmp/` — with multiple-threaded VMS
   but the cooperative scheduler, thread B was never scheduled at all during
   the block (both threads' pids = 0). Consistent with the review's warning
   that "Multiple-threaded VMs alone must not be equated with parallel
   scheduling".

## Q4-style verdict sanity

The one-processor starvation was already a configuration property; this
experiment shows the SAME structural obstacle (all-processor GC barrier +
no absence marking + no documented attach protocol) in the explicitly
SMP-scheduled multithreaded configuration, now with executed evidence
instead of source reading alone. Per the review's NO-GO criteria:
"collection cannot complete while C is blocked" — CONFIRMED on the MT build;
"unrelated C-thread entry cannot be made safe where required" — confirmed
(naive entry crashes; the only working entry shape is an undocumented,
membership-less bridge that deadlocks on GC and corrupts on teardown).

MT-VERDICT: MT build fails: on macOS arm64 with --enable-single-host
--enable-multiple-threaded-vms --enable-smp --enable-max-processors=8, -:p2,
SMP scheduler verified live (threads on processors 0 and 1), W2MT blocked
mode FAILED qualification — A blocked 6.005s in an ordinary c-lambda on
processor 1, B executed on processor 0, B's (##gc) request waited the full
block (collection completed 183us after C's deadline return; C returned by
deadline, never by handshake; live rooted objects only intact post-return);
no release/claim bridge exists in the stock source; W3MT owner control PASSED
but naive foreign-pthread entry SIGSEGVs (NULL TLS) in serialized and
concurrent modes, and the best-effort undocumented attach bridge
(___setup_pstate at aligned_pstate[processor_count] + ___tls_ptr bind)
executes Scheme and returns correct values yet deadlocks the VM on any
collection from the attached pstate (processor_count membership never
established) and corrupts on teardown — P2/P3 remain NO-GO on the
multithreaded 4.9.8 build; Linux leg pending.

DONE
