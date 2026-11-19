Created-GMT: 2026-11-18 17:05:00 GMT
Created-Local: 2026-11-19 00:05:00 ICT (+07)
Coding-Agent: glm
Session-ID: zcode-subagent-p1a-mt
# Task: P1a-MT — the multithreaded-4.9.8 experiment (codex spec, verbatim)

Role: VM Runtime Engineer
Implementers:
- Model: glm-5.3-flash | Assigned: 2026-11-19 00:4x:xx +07 | Status: active | Rationale: decisive experiment on the same lane; separate worktree from the r2 repair lane

## Mission

Execute the experiment the codex review specified to challenge (or confirm) its
source-based conclusion that NO stock 4.9.8 configuration provides GC-completing
blocking foreign calls or a documented foreign-pthread entry protocol. This may
overturn the NO-GO for P2/P3 — or seal it. Read BOTH:
- Review (spec is in its Q3 "Experiment needed" section and Q3 a/b/c analysis):
  /Users/sto/workspace/datomworld/collab/jolt/1795030000000-reviewer-p1a-audit-codex.gpt-6.1-sol.findings.md
- Original audit + witness sources: ~/workspace/jolt-ffi-p1 (READ-ONLY for you —
  another agent is repairing it right now; copy what you need, never modify).

Work dir: ~/workspace/jolt-ffi-mt (branch gambit-ffi-mt, base a6852ef8). Commit
everything you create under p1a-mt/ on that branch. Never push.

## The experiment (follow the review's five numbered steps exactly)

1. Build Gambit 4.9.8 OUT-OF-TREE from ~/workspace/gambit-v4.9.8-src with
   --enable-multiple-threaded-vms --enable-smp --enable-max-processors=<>=2
   (configure.ac:1267-1316 defines these; there is NO --enable-multiprocessor).
   Build dir: p1a-mt/gambit-builds/mt/ (never modify the source tree). Record the
   exact configure invocation, compiler, and the built gambit.h's threading macros
   (verify ___MULTIPLE_THREADED_VMS / SMP feature macros differ from the installed
   single-threaded build).
2. Start the runtime with EXPLICIT parallelism -:p2 (main.c:46-49). Do NOT reuse
   W0's (##current-vm-resize #f 4) — the review warns new processors execute a
   supplied thunk (setup.c:810-835,992-994); use normal startup parallelism.
3. Blocking-GC witness with CORRECT synchronization (do not wait for the r2 lane;
   implement the corrected protocol yourself): A and B synchronized BEFORE A
   enters C; B provably running on a DIFFERENT processor; C stays blocked until
   AFTER B completes a real (##gc) that mutates live rooted objects; verdict from
   observed state; watchdogged. Run BOTH an ordinary c-lambda blocking call AND —
   only if you implement a release/claim bridge — that bridge with its exact
   implementation cited. If you implement no bridge, say so explicitly; absence
   of a mechanism is itself the finding.
4. Foreign-pthread witness with CORRECT signatures ((int int) if two formals —
   the review's F1 defect) and an owner-thread control that passes first; then
   serialized/concurrent foreign entry, allocation/GC pressure, nesting, cleanup,
   repeated attachment. Any attach sequence you try must document membership,
   ownership, TLS, catcher, roots, teardown — from the review's cited ingredients
   (setup.c:805-835, os_thread.c:697-708, setup.c:940-964,987-996).
5. Platform: macOS arm64 now. The Linux leg is a follow-up — note it as pending;
   do not ssh anywhere.

## Standard of proof (review's words)

"A changed crash signature or extra Scheme progress alone is not a pass."
Qualification requires: collection COMPLETING while C stays blocked (not merely
another thread running), then return; or a documented protocol making foreign
entry valid. Anything less is NO-GO evidence for that row on the MT build.

## Deliverables

1. p1a-mt/ committed on gambit-ffi-mt: build config record, witnesses, Makefile,
   verbatim results/*.out.
2. Findings file:
   /Users/sto/workspace/datomworld/collab/jolt/1795034000000-engineer-p1a-mt-experiment.glm-5.3-flash.findings.md
   (standard header; the five steps with outcomes; explicit MT-VERDICT line:
   either "MT build qualifies blocking-GC / foreign-entry: <evidence>" or
   "MT build fails: <evidence>"; ends DONE).
3. Final response starts with Completed-* header lines and gives the MT-VERDICT
   in one line.

Rules: foreground with explicit timeouts; the Gambit build is the long pole
(build with -j, watchdog, poll progress with short Reads so you stay active);
never modify ~/workspace/gambit-v4.9.8-src, ~/workspace/jolt-ffi-p1, or
~/workspace/jolt.
