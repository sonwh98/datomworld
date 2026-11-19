Created-GMT: 2026-11-18 17:03:19 GMT
Created-Local: 2026-11-19 00:03:19 ICT (+07)
Completed-GMT: 2026-11-18 17:52:30 GMT
Completed-Local: 2026-11-19 00:52:30 ICT (+07)
Coding-Agent: glm-5.3-flash
Session-ID: zcode-subagent-p1a-r2
Role: Engineer (VM Runtime) — P1a-r2 witness repair per the ACCEPTED codex review
Reviewed inputs:
- Brief: collab/jolt/1795033000000-engineer-p1a-r2-witness-repair.prompt.md
- Review findings: collab/jolt/1795030000000-reviewer-p1a-audit-codex.gpt-6.1-sol.findings.md (F1-F5 + Q3 adjudication, all ACCEPTED)
Work dir: ~/workspace/jolt-ffi-p1 (branch gambit-ffi-p1)
Commit: 6507ec1e "p1a-r2: repair W1/W2/W3 per codex review F1-F3, correct AUDIT.md per F4/F5" (p1a/ only; not pushed)
Bases reviewed: jolt dc6176dc; gambit-v4.9.8-src 2228e4d (READ-ONLY — never modified); ~/workspace/jolt-ffi-mt untouched.

## What changed, per review finding

### F1 (W3 argument-signature defect) — FIXED
- `p1a/witnesses/w3-server.scm`: both exported entries now declare `(int int)`
  matching their two Scheme formals `(x y)` (`scheme-w3-add`, `scheme-w3-add-alloc`).
  A third same-class defect found and fixed during repair: `scheme_w3_phase3` was
  declared `(int)` against ZERO formals while the C fixture calls it with no
  arguments (generated C was `___NARGS 1` with a garbage argument); now `()` and
  the generated C shows `___NARGS 0`.
- Generated-C verification BEFORE any run: the w3 build regenerates with
  `gsc -keep-temp` and the Makefile's `verify-w3-c` target asserts
  `extern int scheme_w3_add ___P((int ___arg1,int ___arg2)` /
  `scheme_w3_add_alloc ...` plus `#define ___NARGS 2` in both generated entry
  bodies (and `___NARGS 0` for phase3). Build output: `VERIFY-W3-C: OK`.
- Owner-thread control BEFORE any foreign-thread phase: every mode first runs
  `w3_control()` — the Gambit thread that ran `___setup` drives the SAME
  c-define entries (`scheme_w3_add(20,22)=42`, `scheme_w3_add_alloc(50,22)=1072`,
  `scheme_w3_gc_rounds(2)=2`); a wrong value aborts the run (exit 1). A
  control-only mode is recorded as `results/w3-control.out`.
- Verdict semantics per the review's nondeterminism caveat: fatal exits
  (70 = Gambit fatal, 134/138/139 = SIGABRT/SIGBUS/SIGSEGV) are ALL the
  documented refusal-failure and never a success; survival is recorded as
  REFUSAL-NOT-REPRODUCED — the undocumented serialized-reuse artifact, also
  never a success; wrong values are UNSAFE-ENTRY-OBSERVED; timeouts (watchdog
  exit 124) and control failures are failures.
- Empirical outcome of the repair (macOS arm64, provisioned 4.9.8): with VALID
  two-argument entries the serialized owner-parked phases now SURVIVE with
  correct values (phase1 3/3, phase1-nobeat 3/3, phase3 3/3 across repetitions;
  each recorded run survived, one driving 3 real GC rounds from the foreign
  pthread) — confirming the reviewer's inference that the P1a 3/3 SIGSEGVs
  reproduced the malformed entry fixture, not foreign-thread entry itself.
  CONCURRENT entry (worker enters while the primordial runs an allocating
  Scheme loop) crashes with fatal signals: 139 in both recorded runs, 3/3
  across repetitions (139, 138 SIGBUS, 139). W3 no longer proves
  "unrelated entry cannot be made safe" — that claim is withdrawn; the refusal
  row stands on protocol grounds (no documented attach protocol, no
  release/claim mechanism).

### F2 (W1 capture-after-allocation / manufactured errno) — FIXED
- `p1a/witnesses/w1-fixture.c`: EVERY shim now executes target call ->
  `int saved = errno;` IMMEDIATELY, before ANY malloc/conversion/cleanup;
  the pair travels out as one malloc'd struct built from the saved values.
- Shapes A/C preserve the TARGET's real errno: the targets now set it
  themselves like real syscalls (`w1_target_a` sets EAGAIN and returns -1;
  `w1_target_c` sets EACCES and returns -2); the shims never manufacture an
  errno after the fact. Shape B keeps the success-preserves-errno=42 contract.
- Intervening-clobber negative controls (the reviewer's required addition):
  shape F writes `errno = 4242` (C-declared `W1_INTERVENING_CLOBBER`, probed
  by Scheme so the assertion compares against the fixture's own constant)
  BETWEEN the target call and the use of the captured value — the immediate
  capture must defeat it; shape G is a deliberately WRONG shim that captures
  AFTER the clobber — the witness asserts it reports the CLOBBERED value
  (checked first, before any loop), proving the assertions can distinguish
  capture order at all, i.e. the immediate-capture shapes are load-bearing.
- `p1a/witnesses/w1-errno.scm`: F/G added to the sequential loops and to both
  concurrent workers (6 shapes per iteration now); verdict derived from
  failures as before plus the negative-control gate. Fresh run:
  5901/5901 assertions correct, `W1-NEGATIVE-CONTROL: OK`, verdict PASS.

### F3 (W2 missing contender/handshake, unconditional verdict) — FIXED
- New `qualify` mode in `p1a/witnesses/w2-blocking.scm` is THE qualification
  witness for `sa-foreign-procedure-blocking`:
  - Both participants STARTED AND SYNCHRONIZED before the foreign call
    blocks: thread B's FIRST action sets `w2-b-executing` (proving B is
    executing on the runtime) and B then waits for A's entry signal; A (the
    primordial thread) observes B executing, logs it, then enters C.
  - B runs real collection rounds that MUTATE A LIVE ROOTED OBJECT
    (`w2-witness-obj`, a global vector; slots asserted after the run prove the
    mutations survived real collections) via `(##gc)` plus the documented C
    API `___garbage_collect` (`w2-real-gc`).
  - The C release handshake is actually CALLED FROM SCHEME (c-lambda to
    `w2_release_handshake`) and only AFTER B's first real collection
    completed — `p1a/witnesses/w2-fixture.c` records the observed release
    cause (`0 = handshake`, `1 = deadline`), the handshake call count, and
    whether any handshake arrived while the wait was active.
  - Verdict DERIVED from observed state, never printed unconditionally: PASS
    requires `release-cause=handshake`, `rounds-during-block>=1` (a round
    STARTED while the C fixture's own `w2_in_block` flag was set),
    `handshake-during-block=1`, and intact witness-object mutations.
    Internal-consistency guards (round totals, exactly one handshake call,
    witness slots, cause<->handshake agreement) turn any incoherent
    observation into FAIL-INCONSISTENT with exit 3. Independent timeout
    detection stays with the external watchdog (exit 124 = recorded failure).
  - Provisioned-build result (expected refusal evidence): FAIL (blocking
    unqualified) — B was synchronized and provably executing before A entered
    C, but completed all rounds only after the foreign call returned;
    `release-cause=deadline`, `rounds-during-block=0`, `handshake-during-block=0`,
    handshake called from Scheme exactly once, after C returned.
- The P1a `blocked` mode is kept as a documented STARVATION DEMONSTRATION
  variant with a derived verdict (`W2-STARVATION-DEMO-VERDICT:
  STARVATION-DEMONSTRATED` from the same observed-state counters; an
  unexpected B progression during the block would print UNEXPECTED and exit 2).
- The `control` mode's verdict is also derived now (B executed, >=1 real-GC
  round started while A had completed <3 calls, witness mutations intact).

### F4 (categorical all-configuration claims) — WITHDRAWN AND RE-SCOPED
- AUDIT.md Q2 answer restated configuration-scoped: no release/claim mechanism
  documented or present in the PROVISIONED configuration; the all-4.9.8
  claim is withdrawn and replaced by a source-level analysis of the
  multiple-threaded-VMs/SMP variants (verified line numbers): configure
  selects them independently (configure.ac:1271-1283 multiple-threaded-vms,
  :1289-1299 smp, :1306-1316 max-processors with `CONF_MAX_PROCESSORS=1`
  otherwise at :1313); processor lookup moves from the fixed primordial
  pstate to TLS (include/gambit.h.in:8607-8612 vs :8673-8686) — which supplies
  a pstate ADDRESS, not a release/claim protocol; the FFI macros still only
  save/restore Scheme registers (gambit.h:10310-10334); collection still goes
  through `on_all_processors` (setup.c:1132-1149, call at :1196) whose
  multithreaded barrier waits for ALL `vms->processor_count` participants
  (setup.c:498-513, :554-580, :1028-1068, :1098-1099; collector barrier
  mem.c:7002) — a processor inside an ordinary synchronous C call does not
  execute `service_sync_op`, so collection COMPLETION while blocked remains
  unestablished; the SMP scheduler is separate (_thread.scm:9-24,
  ##startup-processor! :3110-3125); runtime-created processors bind via
  `___thread_set_pstate` (os_thread.c:697-708; setup.c:805-835, :940-996) but
  that is not a documented attach recipe; the cross-processor C-return guard
  (_kernel.c:11743-11755) protects return routing, not attachment.
- The Q2 bottom line, Q4 verdict, GO/NO-GO section, and Open-risks item on
  `p1a/gambit-builds/` were reworded accordingly (configuration-only adoption
  reopens configuration-specific P1 evidence and does not clear the gate).
- The "W3 proves unrelated entry cannot be made safe" claim is WITHDRAWN
  everywhere it appeared (Q4 verdict paragraph, GO/NO-GO bullet, W4-M4
  wording); the refusal row is now argued from protocol absence + the repaired
  witness's fresh evidence.

### F5 (citation corrections) — FIXED (all six)
- AUDIT.md Threads-chapter claim (was "Nothing in the Threads chapter mentions
  OS-level threads or processors"): corrected — the chapter's opening
  explicitly discusses OS threads and multiprocessors ("not related to the
  host operating system's threads"; "does not currently take advantage of
  multiprocessors (i.e. at most one thread is running)") at
  gambit.txi:9843-9847, and the manual's omission of the source's optional
  multiple-threaded-VMs/SMP variants is now named.
- Conversion-temporary guarantee: gambit.txi:19296-19300 -> 19305-19307.
- `___AT_END` guarantee: gambit.txi:19313-19319 -> 19328-19332.
- Stack-marker rooting TODO: lib/c_intf.c:6528-6529 -> :6530 (verified:
  `/*TODO: proc_or_false may have been GC'd at this point! protect it some way*/`
  is on line 6530).
- Resize clamp: lib/setup.c:1163-1166 -> :1169-1170 (both citations, Q1 item 3
  and Q4 doc-tier-2).
- GC's `on_all_processors` call: the cited 1181-1194 interval corrected to
  lib/setup.c:1182-1199 with the call at :1196 (Q1 item 3 and Q3 item 3).

### Makefile/hygiene — FIXED
- ALL build artifacts under `p1a/witnesses/build/`: the Makefile copies the
  sources byte-identically into `build/`, builds there (`gsc` generated .c/.o
  stay in build/), runs everything from `build/` under `build/watchdog.sh`.
- Tracked `results/*.out` are overwritten ONLY by `make record-tracked`, which
  depends on a full verified PASS run. `make clean` deletes ONLY build
  artifacts (exe/o/generated C/copied sources/watchdog copy) — never
  `build/results/`, never tracked `results/`.
- Scratch recordings live in `build/results/` (one file per run, `### exit=`
  plus an outcome-classification line per run). The reviewer-comparison
  copies previously in `build/results/` are addressed under "Incident" below.
- `make verify` is ASSERTIVE: it greps every recorded file for its required
  verdict marker (W0 PASS; W1 PASS + negative-control OK + 0 failures; W2
  control PASS / qualify expected derived FAIL with rounds-during-block=0 and
  handshake-during-block=0 / starvation demonstrated; W3 control PASS + every
  phase classified into {UNSAFE-ENTRY-REPRODUCED, UNSAFE-ENTRY-OBSERVED,
  REFUSAL-NOT-REPRODUCED}; W4 markers; exit codes). Any missing or wrong
  marker fails `make all` with `VERIFY: FAIL`, refusing to record.
- The reviewer's "recording recipes consume child statuses" defect: each
  recording recipe now also appends an outcome-classification line derived
  from the child exit status (documented classes only), so a wrong exit cannot
  silently pass into the record.

### AUDIT.md verdict — AMENDED per the reviewer's wording
- New VERDICT-AMENDED block in the GO/NO-GO section: NO-GO for P2/P3 on the
  provisioned build CONFIRMED; multithreaded 4.9.8 configurations remain
  unqualified and do not clear the verdict; the foreign-entry-impossible claim
  WITHDRAWN; PARTIAL-GO per capability (bounded P1b only, each capability
  independently qualified). The Reproducing section documents the new
  `build/` + `record-tracked` + `verify-w3-c` flow.

## Incident disclosure (reviewer rerun artifacts)

The first p1a-r2 Makefile draft had `clean: rm -rf build`, and one
`make clean` executed before the hygiene fix removed the reviewer's UNTRACKED
rerun artifacts that lived under `p1a/witnesses/build/results/`
(review-phase1-1/2/3.out, review-phase1-nobeat-1/2/3.out, review-phase3-1/2/3.out,
plus the reviewer's comparison copies of the w*.out recordings and the retained
malformed generated C). I could not recover them (no user-volume APFS snapshot
covers the interval; git never tracked build/). Mitigations:
- The substance of those artifacts is durably recorded in the reviewer's own
  findings file: the Q1 rerun table (exact-match determinations per run; 3/3
  exit 139 for phase1/nobeat/phase3; the phase2-run2 139 mismatch) and the
  generated-C quotes (`scheme_w3_add(int ___arg1)`, `___NARGS 1` at
  W/build/w3-server.c:197-206, 222-231).
- This repair independently re-established the same facts before the loss: I
  inspected the retained malformed generated C (___NARGS 1 for both add
  entries, and the phase3 one-arg-vs-zero-formals mismatch) before editing,
  and the repaired pipeline now VERIFIES the opposite (___NARGS 2/0) at build
  time, so the F1 evidence is reproducible from committed sources alone.
- `make clean` is fixed to preserve `build/results/` permanently (the commit
  message records this). Fresh full-run scratch recordings now populate
  `build/results/` as the new comparison baseline.

## Verbatim fresh recorded outputs (tracked results/, commit 6507ec1e)

All runs watchdogged (`./watchdog.sh`); every repaired witness recorded after
a full PASS-verified run via `make record-tracked` (the promotion itself
re-ran the complete pipeline: build -> record -> verify -> copy).

```
--- results/w1.out ---
### cmd: ./watchdog.sh 120 ./w1.exe
=== W1 errno-atomicity witness (p1a-r2: capture-before-allocation + negative controls) ===
EAGAIN=35 EACCES=13 EIO=5 intervening-clobber=4242
W1-NEGATIVE-CONTROL: OK (shape G captured AFTER the intervening clobber and reported it — the assertions distinguish capture order, so the immediate-capture shapes are load-bearing)
W1-CHECKS=5901
W1-FAILURES=0
W1-VERDICT: PASS (every shim captured (result,errno) immediately after the target, before ANY allocation/conversion/re-entry: shapes A/C preserve the target's own errno, shape B preserves errno=42 on success, shape E captures before the nested Scheme re-entry, shape F defeats an intervening clobber written between target and capture; shape G proves the assertions distinguish capture order; the ___AT_END clobber (shape D) and the two-thread interleave add conversion-boundary and scheduling pressure)
### exit=0

--- results/w2-control.out ---
### cmd: ./watchdog.sh 60 ./w2.exe -:d5 control
*** GC: 38us, .6K alloc, 6.0M heap, .6K live (0% 592+56)
=== W2 blocking-GC witness (p1a-r2): mode=control ===
W2: t=1795023506.574088 start
W2-B: executing t=1795023506.574148
W2-B: round 1 t=1795023506.574176 start (c-in-block=0 a-calls-done=0)
*** GC: 543us, 99K alloc, 6.5M heap, 1.6M live (24% 70016+1607864)
W2-B: round 1 t=1795023506.575742 done (capi-gc-return=0)
W2-B: round 2 t=1795023506.575825 start (c-in-block=0 a-calls-done=0)
*** GC: 602us, 121K alloc, 6.5M heap, 1.6M live (24% 70016+1607864)
W2-B: round 2 t=1795023506.57737 done (capi-gc-return=0)
W2-B: round 3 t=1795023506.577442 start (c-in-block=0 a-calls-done=0)
*** GC: 663us, 143K alloc, 6.5M heap, 1.6M live (24% 70016+1607864)
W2-B: round 3 t=1795023506.578989 done (capi-gc-return=0)
W2-A: non-blocking foreign call 1 t=1795023506.579131
W2C: non-blocking foreign call entered+returned
W2-A: non-blocking foreign call 2 t=1795023506.884257
W2C: non-blocking foreign call entered+returned
W2-A: non-blocking foreign call 3 t=1795023507.187917
W2C: non-blocking foreign call entered+returned
W2-A: all non-blocking calls returned
W2: observed state: b-executed=#t rounds-overlapping-a-calls=3 witness-object=(last-round=3 round-count=3)
W2-CONTROL-VERDICT: PASS (derived from observed state: B executed, 3 of 3 real-GC rounds started while A had completed <3 foreign calls, witness-object mutations intact after real collections; the *** GC: report lines above are the runtime's own collection evidence)
### exit=0
### w2-outcome: run completed (verdict derived inside; see the W2-CONTROL-VERDICT line)

--- results/w2-qualify.out ---
### cmd: ./watchdog.sh 60 ./w2.exe -:d5 qualify
*** GC: 38us, .6K alloc, 6.0M heap, .6K live (0% 592+56)
=== W2 blocking-GC witness (p1a-r2): mode=qualify ===
W2: t=1795023507.521935 start
W2-B: EXECUTING on the runtime t=1795023507.521979
W2-A: OBSERVED B executing on the runtime (synchronized) t=1795023507.534523
W2-A: entering blocking foreign call (B synchronized and executing) t=1795023507.534609
W2C: foreign call ENTERED, blocking on handshake (deadline 6s)
W2C: foreign call RETURNING (cause=deadline, blocked 6000ms)
W2-B: observed A's entry signal; starting real GC rounds t=1795023513.535334
W2-B: round 1 t=1795023513.535408 start (c-in-block=0 a-calls-done=0)
*** GC: 497us, 112K alloc, 6.5M heap, 1.6M live (24% 68784+1607864)
W2-B: round 1 t=1795023513.536893 done (capi-gc-return=0)
W2-B: calling the C release handshake FROM SCHEME after round 1 (real collection completed) t=1795023513.536965
W2-B: round 2 t=1795023513.537042 start (c-in-block=0 a-calls-done=0)
*** GC: 514us, 140K alloc, 6.5M heap, 1.6M live (24% 68784+1607864)
W2-B: round 2 t=1795023513.538448 done (capi-gc-return=0)
W2-B: round 3 t=1795023513.538541 start (c-in-block=0 a-calls-done=0)
*** GC: 489us, 162K alloc, 6.5M heap, 1.6M live (24% 68784+1607864)
W2-B: round 3 t=1795023513.53974 done (capi-gc-return=0)
W2-A: foreign call returned t=1795023513.539801 release-cause=deadline
W2: observed state: release-cause=deadline rounds-during-block=0 rounds-after=3 handshake-called-from-scheme=#t capi-handshake-calls=1 handshake-during-block=0 blocked-ms=6000 witness-object=(last-round=3 round-count=3)
W2-QUALIFY-VERDICT: FAIL (blocking unqualified on this build: release-cause=deadline, rounds-during-block=0, handshake-during-block=0; B was synchronized and provably executing BEFORE A entered C, but completed all rounds only AFTER the foreign call returned — the handshake was called from Scheme only after B's real collections, which on this build can only happen after C returned; astra §2 'actual collection and another Scheme thread's progress while C remains blocked' is not satisfied)
### exit=0
### w2-outcome: run completed (verdict derived from observed state; see the W2-QUALIFY-VERDICT line)

--- results/w2-blocked.out ---
### cmd: ./watchdog.sh 60 ./w2.exe -:d5 blocked
*** GC: 41us, .6K alloc, 6.0M heap, .6K live (0% 592+56)
=== W2 blocking-GC witness (p1a-r2): mode=blocked ===
W2: t=1795023513.575466 start
W2-A: entering blocking foreign call t=1795023513.575508
W2C: foreign call ENTERED, blocking on handshake (deadline 6s)
W2C: foreign call RETURNING (cause=deadline, blocked 6000ms)
W2-A: foreign call returned t=1795023519.576507 release-cause=deadline
W2-B: executing (started AFTER A entered — starvation demo) t=1795023519.576941
W2-B: round 1 t=1795023519.576974 start (c-in-block=0 a-calls-done=0)
*** GC: 558us, 110K alloc, 6.5M heap, 1.6M live (24% 69552+1607864)
W2-B: round 1 t=1795023519.582701 done (capi-gc-return=0)
W2-B: round 2 t=1795023519.582798 start (c-in-block=0 a-calls-done=0)
*** GC: 489us, 132K alloc, 6.5M heap, 1.6M live (24% 69552+1607864)
W2-B: round 2 t=1795023519.584126 done (capi-gc-return=0)
W2-B: round 3 t=1795023519.584189 start (c-in-block=0 a-calls-done=0)
*** GC: 494us, 154K alloc, 6.5M heap, 1.6M live (24% 69552+1607864)
W2-B: round 3 t=1795023519.58541 done (capi-gc-return=0)
W2: blocked window from t=1795023513.575506 to t=1795023519.585484
W2: observed state: rounds-during-block=0 rounds-after=3 blocked-ms=6000 witness-object=(last-round=3 round-count=3)
W2-STARVATION-DEMO-VERDICT: STARVATION-DEMONSTRATED (derived: 0 rounds started while the C fixture's in-block flag was set; blocked 6000ms — the whole deadline; every W2-B line above appears after 'W2C: foreign call RETURNING'; the qualification witness is the w2-qualify mode, not this diagnostic)
### exit=0
### w2-outcome: run completed (starvation demonstration; verdict derived inside)

--- results/w3-control.out ---
### cmd: ./watchdog.sh 60 ./w3.exe control
W3: calling ___setup from the primordial thread
W3: ___setup returned; runtime live (___VERSION=409008)
W3-CONTROL: owner-thread callbacks through the SAME c-define entries the worker will use
W3-CONTROL: scheme_w3_add(20,22) = 42 (expected 42)
W3-CONTROL: scheme_w3_add_alloc(50,22) = 1072 (expected 1072)
W3-CONTROL: scheme_w3_gc_rounds(2) = 2 (expected 2; real collections on the owner thread)
W3-CONTROL-VERDICT: PASS (owner Gambit thread drives the same c-define entries with correct values before any foreign-thread phase)
W3: calling ___cleanup
W3: ___cleanup returned
### exit=0
### w3-outcome: CONTROL-PASS (owner-thread callbacks through the same c-define entries succeeded before any foreign-thread phase)

--- results/w3-phase1.out ---
### cmd: ./watchdog.sh 60 ./w3.exe phase1
W3: calling ___setup from the primordial thread
W3: ___setup returned; runtime live (___VERSION=409008)
W3-CONTROL: owner-thread callbacks through the SAME c-define entries the worker will use
W3-CONTROL: scheme_w3_add(20,22) = 42 (expected 42)
W3-CONTROL: scheme_w3_add_alloc(50,22) = 1072 (expected 1072)
W3-CONTROL: scheme_w3_gc_rounds(2) = 2 (expected 2; real collections on the owner thread)
W3-CONTROL-VERDICT: PASS (owner Gambit thread drives the same c-define entries with correct values before any foreign-thread phase)
W3-PHASE1: spawning raw pthread (primordial will park in pthread_join)
W3-PHASE1: heartbeat interval = 0.010000
W3-WORKER: scheme_w3_add_alloc(50,22) = 1072 (expected 1072)
W3-WORKER: scheme_w3_add(20,22) = 42
W3-WORKER: scheme_w3_add(21,22) = 43
W3-WORKER: scheme_w3_add(22,22) = 44
W3-WORKER: drove 3 real gc rounds (runtime *** GC: reports below with -:d5)
W3-WORKER: final scheme_w3_add(1,1) = 2
W3-PHASE1: worker joined, exit=0
W3-PHASE1-VERDICT: REFUSAL-NOT-REPRODUCED (worker survived with correct values — undocumented serialized reuse of the single pstate; a nondeterminism caveat, NEVER qualification of foreign-thread entry)
W3: calling ___cleanup
W3: ___cleanup returned
### exit=0
### w3-outcome: REFUSAL-NOT-REPRODUCED (worker survived — undocumented serialized reuse of the single pstate, never qualification of foreign-thread entry)

--- results/w3-phase1-nobeat.out --- (same shape as phase1 with heartbeat disabled; identical values; REFUSAL-NOT-REPRODUCED; ### exit=0 — see the tracked file for the verbatim text)

--- results/w3-phase2-run1.out ---
### cmd: ./watchdog.sh 60 ./w3.exe phase2
W3: calling ___setup from the primordial thread
W3: ___setup returned; runtime live (___VERSION=409008)
W3-CONTROL: owner-thread callbacks through the SAME c-define entries the worker will use
W3-CONTROL: scheme_w3_add(20,22) = 42 (expected 42)
W3-CONTROL: scheme_w3_add_alloc(50,22) = 1072 (expected 1072)
W3-CONTROL: scheme_w3_gc_rounds(2) = 2 (expected 2; real collections on the owner thread)
W3-CONTROL-VERDICT: PASS (owner Gambit thread drives the same c-define entries with correct values before any foreign-thread phase)
W3-PHASE2: spawning raw pthread WHILE primordial runs scheme_w3_spin
./watchdog.sh: line 21: 35996 Segmentation fault: 11  "$@"
### exit=139
### w3-outcome: UNSAFE-ENTRY-REPRODUCED (documented refusal-failure: exit 139 = the foreign pthread could not safely enter Scheme; never a success)

--- results/w3-phase2-run2.out --- (identical shape; PID 36015; ### exit=139; UNSAFE-ENTRY-REPRODUCED — see the tracked file)

--- results/w3-phase3.out ---
### cmd: ./watchdog.sh 60 ./w3.exe phase3
W3: calling ___setup from the primordial thread
W3: ___setup returned; runtime live (___VERSION=409008)
W3-CONTROL: owner-thread callbacks through the SAME c-define entries the worker will use
W3-CONTROL: scheme_w3_add(20,22) = 42 (expected 42)
W3-CONTROL: scheme_w3_add_alloc(50,22) = 1072 (expected 1072)
W3-CONTROL: scheme_w3_gc_rounds(2) = 2 (expected 2; real collections on the owner thread)
W3-CONTROL-VERDICT: PASS (owner Gambit thread drives the same c-define entries with correct values before any foreign-thread phase)
W3-PHASE3: owner Gambit thread parks INSIDE a cfun call while the worker pthread enters Scheme
W3-WORKER: scheme_w3_add_alloc(50,22) = 1072 (expected 1072)
W3-WORKER: scheme_w3_add(20,22) = 42
W3-WORKER: scheme_w3_add(21,22) = 43
W3-WORKER: scheme_w3_add(22,22) = 44
W3-WORKER: drove 3 real gc rounds (runtime *** GC: reports below with -:d5)
W3-WORKER: final scheme_w3_add(1,1) = 2
W3-PHASE3: worker joined exit=0
W3-PHASE3-VERDICT: REFUSAL-NOT-REPRODUCED (worker entry survived with owner parked in a cfun frame — undocumented serialized reuse; a nondeterminism caveat, NEVER qualification)
W3: calling ___cleanup
W3: ___cleanup returned
### exit=0
### w3-outcome: REFUSAL-NOT-REPRODUCED (worker survived — undocumented serialized reuse of the single pstate, never qualification of foreign-thread entry)
```

Repetition evidence (manual, not part of the recorded set): phase1 3/3 exit 0,
phase1-nobeat 3/3 exit 0, phase3 3/3 exit 0, phase2 3/3 fatal (139, 138, 139)
— the recorded outputs above are one verified sample; the nondeterminism
caveat semantics make survival a caveat and fatal exits the refusal-failure,
never successes.

W0 and W4: sources unchanged in this round; their tracked P1a recordings
(`results/w0.out`, `results/w4-*.out`) remain (reviewer-verified exact match);
fresh equivalents recorded into `build/results/` during the verified passes
(W0 PASS; W4 mode1 PASS, mode4 3x PASS-QUALIFIED, mode5 exit 70) — W4-M4's
AUDIT.md wording was updated to match the repaired W3 evidence.

## Per-finding status

- F1 (W3 signatures + control + generated-C verification): FIXED
- F2 (W1 capture-before-allocation + negative controls): FIXED
- F3 (W2 qualification witness + derived verdicts): FIXED
- F4 (withdraw categorical all-configuration claims): FIXED (withdrawn and
  re-scoped with cited barrier analysis)
- F5 (citation corrections: manual 19305-19307, 19328-19332, c_intf.c:6530,
  setup.c:1169-1170 and :1196, Threads-chapter AUDIT.md:47-49): FIXED (all six)
- Makefile/hygiene: FIXED
- Incident: reviewer's untracked rerun artifacts under build/results/ deleted
  by the first p1a-r2 Makefile draft's `clean` — NOT recoverable; substance
  survives in the review findings file; disclosed here and in the commit
  message; clean fixed to preserve build/results/ permanently.

DONE
