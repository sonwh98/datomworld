Created-GMT: 2026-11-18 16:49:55 GMT
Created-Local: 2026-11-18 23:49:55 ICT (+07)
Coding-Agent: glm
Session-ID: zcode-subagent-p1a-runtime-audit

# P1a findings — Gambit 4.9.8 runtime audit + decisive FFI witnesses (go/no-go)

Role: VM Runtime Engineer, P1a slice (Gambit FFI track). Method: DOC-FIRST
(owner ruling 2026-11-18) — manual chapter/section first, installed gambit.h
declarations second, runtime `file:line` only where the docs underdetermine
the jolt contract's pinned semantics, with the doc gap named. Witnesses are
the decisive evidence; docs/source explain what they show.

Deliverables:
- `~/workspace/jolt-ffi-p1/p1a/AUDIT.md` — committed on `gambit-ffi-p1`
  (commit dc6176dc, branch a6852ef8 + 1 commit; NOT pushed).
- Witnesses + Makefile + watchdog: `~/workspace/jolt-ffi-p1/p1a/witnesses/`
  (same commit); recorded verbatim outputs under `witnesses/results/*.out`.
- This findings file (untracked in datomworld, per instructions).

VERDICT (astra §5 criteria, verbatim application): **NO-GO for P2/P3** —
"collection cannot complete while C is blocked" (W2, proven) and "unrelated
C-thread entry cannot be made safe where required" (W3, proven). **PARTIAL-GO
remains available for the basic tier** (memory, loading, ordinary calls,
errno) per astra's fallback ruling; blocking and foreign-thread constructors
must keep their message-carrying refusals.

---

## Sources cited

- `[txi §...]` = official manual in the source clone
  `~/workspace/gambit-v4.9.8-src/doc/gambit.txi` (tag v4.9.8, 2228e4d).
- `[gambit.h:N]` = installed configured header
  `~/.local/share/mise/installs/gambit/4.9.8/include/gambit.h`.
- `[lib/file.c:N]` = `~/workspace/gambit-v4.9.8-src/lib/...` (implementation
  tier; cited only to resolve a documented gap, gap named each time).
- Provisioned toolchain: `~/.local/share/mise/installs/gambit/4.9.8/bin/{gsi,gsc}`
  (absolute paths; bare `gsc` on PATH is Ghostscript). mise plugin
  (`~/.local/share/mise/plugins/gambit/lib/utils.bash:68`) configures with
  exactly `./configure --enable-single-host --prefix=...` — nothing else.

---

## Q1 — Runtime build configuration and threading model

**Single VM, single OS-threaded (`___SINGLE_THREADED_VMS`), one processor;
green threads multiplexed over exactly one OS thread that runs all Scheme
execution; `--enable-single-host` codegen. jolt CI's `f8,-8,t8` are pure I/O
encoding defaults (UTF-8 for file/stdio/terminal ports), no threading effect;
the provisioned runtime matches CI's threading configuration exactly.**

- Manual: threads are "a virtual processor which shares object space with all
  other threads" (txi:9876); execution starts on the primordial thread and
  all threads die with it (txi:10105-10114). No OS-thread/processor concept is
  documented in the Threads chapter.
- Headers: `#define ___SINGLE_THREADED_VMS` is the CONFIGURED substitution in
  the installed header (gambit.h:1469-1472; from include/gambit.h.in:1471
  `@CONF_SINGLE_MULTIPLE_THREADED_VMS@`, configure:6348-6357, default no —
  configure.ac:1271-1283). `___MAX_PROCESSORS` = 1 (gambit.h:1489-1493;
  configure:6394-6398). POSIX thread system selected for primitives only
  (gambit.h:1498-1505). `--enable-single-host` = codegen only
  (configure:1778-1784, `-D___SINGLE_HOST` configure:5116-5119; installed
  bin/gambuild-C:27 passes it to every module).
- Implementation (gap: startup processor count not in docs): VM starts with
  `processor_count = 1` (setup.c:4791-4795); documented `___current_vm_resize`
  clamps to `___MAX_PROCESSORS` (gambit.h:14767-14770; setup.c:1163-1166;
  resize body compiled out under `___SINGLE_THREADED_VMS`, setup.c:866-1013).
- CI's `--enable-default-runtime-options=f8,-8,t8` → `___DEFAULT_RUNTIME_OPTIONS`
  baked at configure (configure:5412-5424), consumed first among option
  sources before GAMBOPT/script/`-:` (main.c:24-25, 505-620). Manual meaning:
  `f` = file-settings (txi:2106), `-` = stdio-settings (txi:2109), `t` =
  terminal-settings (txi:2121); code `8` = UTF-8 character encoding
  (txi:2356-2357).

**Witness W0 — PASS** (`witnesses/results/w0.out`, verbatim):

```
=== W0 build-configuration witness ===
___VERSION=409008
___SINGLE_VM=defined
___SINGLE_THREADED_VMS=defined
___MAX_PROCESSORS=1
___USE_POSIX_THREAD_SYSTEM=defined
___SINGLE_HOST=defined (-D by gambuild-C)
___SINGLE_HOST_LIMIT=30000
___USE_RC_ALLOCATION_FOR_FOREIGN=defined
min-heap-bytes=2097152
max-heap-bytes=0
live-percent=50
parallelism-level=1
##cpu-count=8
##core-count=8
##current-vm-processor-count=1
after ##current-vm-resize(4): processor-count=1 (before: 1)
W0-VERDICT: PASS (configuration recorded)
```

8 hardware cores, ONE processor, documented resize clamped back to 1 —
empirical proof on the provisioned binaries.

## Q2 — The exact leave/re-enter (release/claim) mechanism

**No such mechanism exists in 4.9.8 — not documented in the manual, not in
gambit.h, not in the implementation.** The only exported "release" entry
points are OBJECT releases (`___release_rc`, `___release_scmobj`,
`___release_pointer`, … — reference counts for foreign data, gambit.h:9840-9886,
10179-10268), not execution ownership. The exported runtime-control API is
exactly `___setup_params_reset`/`___setup`/`___cleanup`/
`___cleanup_and_exit_process`/`___current_vm_resize`/`___garbage_collect`/
`___setup_vmstate`/`___cleanup_vmstate`/`___setup_pstate`/`___cleanup_pstate`
(gambit.h:14757-14788). Generated c-lambda/c-define bodies run inline on the
owning processor for their whole duration (gambit.h:10028-10044
`___BEGIN_SFUN_*`; gambit.h:10327-10360 `___BEGIN_CFUN_*`). Implementation
grep for `___leave/___claim/LEAVE_GAMBIT/ENTER_GAMBIT/___reclaim` in lib/*.c:
negative (gap justified: docs don't determine whether an undocumented
mechanism exists). astra §2 steps 4/6 ("Leave runtime execution through the
verified mechanism" / "Re-enter ... through its required claim mechanism")
have no verified mechanism to use.

## Q3 — Does a released foreign frame permit (a) another Gambit thread to run and (b) collection to complete?

**The premise cannot be constructed (no release, Q2); and while C is blocked,
no other Gambit thread runs and no collection completes.** One OS thread
executes all green threads (Q1); the scheduler only runs at Scheme safe
points, never inside a foreign call. GC is driven by the running thread — at
allocation points or via `(##gc)` → documented `___garbage_collect`
(gambit.h:14772-14775; lib/_kernel.scm:2032-2069), a synchronous barrier over
all processors (setup.c:1181-1194) — so nothing can collect while the only
thread is in C. The manual corroborates the serialization: only ONE Scheme
thread may do Scheme→C→Scheme calls; others only simple Scheme→C calls
(txi:19980-19991). Mechanism that makes it false: `___GET_REAL_PSTATE()` =
the ONE fixed pstate and `___SET_REAL_PSTATE(ps)` is a NO-OP under
`___SINGLE_THREADED_VMS` (gambit.h:8606-8612).

**Witness W2 — control PASS, blocked FAIL (decisive).** Control: real `***
GC:` report lines (runtime's own, enabled by `-:d5`, txi:2236-2238) while
another thread makes foreign calls — the adapter's no-op `sa-gc-collect`
cannot produce these. Blocked: thread B runnable 0.3 s after A enters a 6 s
blocking foreign call — B produces ZERO lines and ZERO GC reports during the
entire window; every B round appears after `W2C: foreign call RETURNING`
(measured 6.0 s starvation):

```
W2C: foreign call ENTERED, blocking on handshake (deadline 6s)
W2C: foreign call RETURNING after blocked interval
W2-B: round 1 t=1795020335.854959 allocating + ##gc (runtime primitive)
*** GC: 834us, 93K alloc, 6.5M heap, 1.6M live (24% 69472+1607840)
...
W2: blocked window from t=1795020329.854498 to t=1795020335.862667
W2-VERDICT: FAIL (blocked: no collection and no other-thread progress while the foreign call was blocked — every W2-B line above appears after 'W2C: foreign call RETURNING'; the VM has a single processor and 4.9.8 has no foreign-release mechanism, so the blocking row cannot be satisfied on this build)
```

A Scheme-internal watchdog cannot fire here (the whole VM is frozen), so every
run is under the external `watchdog.sh`; `exit=0` is the witness's own
structured verdict, not a gate pass.

## Q4 — Rules for a previously unknown pthread entering Scheme

**No documented protocol exists, and no usable mechanism exists. Naive entry
crashes.** The manual documents only the single-threaded "server" pattern
(`___setup` from the driving thread; txi:19448-19451; tests/server.scm) and
restricts multi-thread nested calls (txi:19980-19991). Under
`___SINGLE_THREADED_VMS` a foreign thread cannot even be bound to a processor
state (`___SET_REAL_PSTATE` no-op, gambit.h:8606-8612) and no second pstate
can be created (resize compiled out, setup.c:866-1013). The c-define entry
macros silently share the primordial's pstate with no ownership check
(gambit.h:10028-10044). Crash mechanism (implementation tier, gap justified):
`___call` arms a setjmp catcher on the CALLING thread's C stack
(gambit.h:9966-9971); the return-to-C handler jumps back "inside ___call" via
`___throw_error(___UNWIND_C_STACK)` (lib/_kernel.c:11772) targeting the SHARED
pstate's catcher chain, whose cross-processor guard is compiled out
(lib/_kernel.c:11742-11755); after `___setup` returns the outermost catcher is
NULL (`___ps->catcher = 0` at setup.c:4755; `___run`'s catcher is a stack
local, setup.c:3389-3415). macOS crash report (verbatim):

```
exception: EXC_BAD_ACCESS, SIGSEGV, KERN_INVALID_ADDRESS at 0x00000000000000b0
  libsystem_platform.dylib  longjmp
  w3.exe  ___call
  w3.exe  scheme_w3_add
  w3.exe  w3_worker_serialized
  libsystem_pthread.dylib  _pthread_start
```

**Witness W3 — FAIL on every mode** (results/w3-phase1.out,
w3-phase1-nobeat.out, w3-phase2-run1.out, w3-phase2-run2.out, w3-phase3.out):
- phase1 (serialized, heartbeat on): SIGSEGV exit 139, 3/3 deterministic,
  before the worker's first Scheme call completes.
- phase1-nobeat (heartbeat disabled via undocumented `___set_heartbeat_interval(0)`,
  os_time.c:937-948, used only to isolate the cause): still SIGSEGV 139 —
  not the heartbeat.
- phase2 (concurrent entry): nondeterministic UB — SIGSEGV 139 in one
  session, silent exit 70 twice in the recorded session.
- phase3 (owner Gambit thread parked inside a cfun call while worker enters):
  still SIGSEGV 139 — even the allocating discriminator entry never completes.

Verdict: an unrelated C thread may NOT enter Scheme on this build; the
refusal row stays.

## Q5 — Root and processor-state obligations

**No leave/re-enter transitions exist (Q2). The obligations are the two
documented crossing directions:**
- Processor-state propagation through conversions via `___PSD`/`___PSP`:
  "necessary to propagate the current processor state to that function"
  (txi:19726-19731); the sfun entry pins `___processor_state ___ps=___PSTATE;`
  (gambit.h:10028-10044).
- Rooting: C→Scheme results are still objects, refcount 1, decremented by
  `___release_scmobj` in `___END_CFUN_*`/`___END_SFUN_*`; zero-count objects
  are reclaimed at collection when unreferenced from Scheme (txi:19706-19724;
  `___alloc_rc`/`___release_rc`, gambit.h:9814-9876). The C→Scheme entry roots
  the called procedure via the sfun stack marker
  (`___make_sfun_stack_marker`/`___kill_sfun_stack_marker`,
  gambit.h:10028-10044; c_intf.c:6511-6556; the TODO at c_intf.c:6528-6529
  documents the sharp edge). Scheme→C: conversion temporaries are reclaimed
  even on error, and `___AT_END` is the post-conversion cleanup point
  (txi §c-lambda:19296-19300, 19313-19319); the foreign-type release-function
  runs at most once, also from the collector (txi:19022-19036). Pointers to
  Scheme objects passed into C are valid only for the call's duration; there
  is NO documented pinning API (relevant to `sa-lock-object`: the non-moving
  collector exemption does not transfer).
- Save/restore: the generated macros do all of it (`___BEGIN_CFUN_CTX`
  `___SET_STK`+`___W_ALL` / `___END_CFUN_CTX` `___R_ALL`, gambit.h:10310-10311);
  nothing manual is required — and nothing manual is POSSIBLE for a foreign
  thread, because the catcher/stack context is per-thread C-stack state held
  in the shared pstate (gambit.h:9966-9972; setup.c:4755).

**Witness W1 — PASS (4000/4000)**: typed shims capture (result,errno)
immediately after the target; success preserves errno=42 (no normalization);
post-capture clobber (7777) in every shim; `___AT_END` clobber after
conversion; nested re-entry capture; 2 Scheme threads × 400 interleaved
iterations. A global "last errno" cell or a deferred re-read fails these;
per-invocation capture passes. (macOS errno: EAGAIN=35, EACCES=13, EIO=5 —
probed at runtime so the assertions hold on Linux too.)

```
W1-CHECKS=4000
W1-FAILURES=0
W1-VERDICT: PASS (result+errno pair captured atomically at the shim; capture order proven by post-capture and ___AT_END clobbers; per-invocation capture proven by nesting and threads)
```

## Witness W4 (callables) — which modes work under which entry protocol

- M1 same-thread callback: PASS (40 = expected; documented c-define path).
- M2 nested C→Scheme→C→Scheme on one thread: PASS (1107 = expected;
  documented nesting, txi:19936-19991).
- M3 callback from a second Gambit thread during a foreign call:
  structurally impossible — demonstrated; B's callback runs only AFTER A's
  foreign call returns (probe = 0). Serialized PASS only; the jolt
  requirement (progress during the call) is W2's FAIL.
- M4 callback from a raw foreign pthread: PASS-QUALIFIED, NOT supported —
  returned correctly 3/3 with the owner parked in a cfun call, but the
  structurally identical W3 phase3 SIGSEGVs; survival is an undocumented
  artifact of exact frame state, not a contract. P1b must not advertise it.
- M5 exception inside a callback: the process DIES (prints the error, exit
  70); the C caller gets no error channel. Callback error handling must be
  INSIDE the callback body (the manual's own example does this:
  `catch-all-errors`, tests/server.scm:15-19) — make that part of the
  callable contract in P1b.

## Other contract-relevant findings

1. `(##gc)` (lib/_kernel.scm:2051-2069 → documented `___garbage_collect`) is
   the real mechanism to replace the adapter's no-op `sa-gc-collect`
   (host/gambit/scheme-adapter-runtime.ss:81-82). Safe within one processor's
   Scheme execution; does NOT solve blocking.
2. `___setup_pstate`/`___setup_vmstate` are exported but unusable for foreign
   threads on this build (no second pstate creatable, no TLS binding).
3. "Variadic C functions are not supported" by c-lambda (txi §c-lambda,
   gambit.txi:19297) — relevant to the jolt varargs-specialization path.
4. Every jolt-level driver that calls foreign code must be watchdogged
   EXTERNALLY: a blocked foreign call freezes the whole VM, so no in-process
   timeout can ever fire (W2 blocked).

## Commands run (for the reviewer to re-run)

```
# toolchain under test (absolute paths; bare gsc is Ghostscript)
/Users/sto/.local/share/mise/installs/gambit/4.9.8/bin/gsc -exe ...
# build + run ALL witnesses under the external watchdog, record verbatim:
cd ~/workspace/jolt-ffi-p1/p1a/witnesses
make clean && make all
# individual runs (all watchdogged by the Makefile):
./watchdog.sh 60  ./w0-config.exe
./watchdog.sh 120 ./w1.exe
./watchdog.sh 60  ./w2.exe -:d5 control
./watchdog.sh 60  ./w2.exe -:d5 blocked
./watchdog.sh 60  ./w3.exe phase1
./watchdog.sh 60  ./w3.exe phase1-nobeat
./watchdog.sh 60  ./w3.exe phase2        # x2 (nondeterministic)
./watchdog.sh 60  ./w3.exe phase3
./watchdog.sh 60  ./w4.exe mode1
./watchdog.sh 60  ./w4.exe mode4         # x3
./watchdog.sh 60  ./w4.exe mode5
```

Audit/inspection commands used (no writes to the source tree):
grep over `~/workspace/gambit-v4.9.8-src/doc/gambit.txi`, `lib/*.c`,
`lib/*.scm`, `configure`, `include/gambit.h.in`, and the installed
`include/gambit.h`; `nm` on the installed `lib/libgambit.a` (heartbeat
symbols); macOS crash reports parsed from
`~/Library/Logs/DiagnosticReports/w3.exe-*.ips`.

## Open risks / limitations

- macOS arm64 only. astra requires macOS arm64 AND Linux x86_64 for the
  pinned rows; the Linux leg is unexecuted. The structural evidence
  (configured header, compiled-out resize, shared-pstate catcher chain) is
  platform-independent, but the crash signatures (SIGSEGV in longjmp, exit
  70) should be re-recorded on Linux before any document cites them.
- W3-phase2 and W4-M4 are nondeterministic/fragile by nature; recorded
  outputs are one sample each. The deterministic rows (phase1, phase1-nobeat,
  phase3) were reproduced 3/3, 1/1, 1/1 respectively (phase1 also 1/1 in an
  earlier exploratory run — 4/4 total).
- No out-of-tree Gambit rebuild was needed (p1a/gambit-builds/ unused): the
  verdicts follow from the provisioned configuration itself; a
  `--enable-multiple-threaded-vms` build would still need a verified
  foreign-thread attach protocol, which upstream does not document for that
  build either.
- Nothing outside p1a/ was touched in the jolt worktree; the commit contains
  sources + Makefile + recorded outputs only (no binaries, no generated .c).
- Next decision on the blocked rows (per astra): explicit Gambit upgrade or
  runtime patch through the upgrade gate; otherwise keep honest refusals and
  describe the partial mode in CONTRACT.txt / TARGET-CONTRACT.md.

DONE
