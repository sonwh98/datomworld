Created-GMT: 2026-11-18 15:28:51 GMT
Created-Local: 2026-11-18 22:28:51 ICT (+07)
Coding-Agent: glm
Session-ID: zcode-subagent-p1a-runtime-audit
# Task: P1a — Gambit 4.9.8 runtime audit + decisive FFI witnesses (go/no-go)

Role: VM Runtime Engineer
Implementers:
- Model: glm-5.3-flash | Assigned: 2026-11-18 22:3x:xx +07 | Status: active | Rationale: measured, checkable systems-audit work on the flat lane

## Mission

Execute the astra plan's P1 prerequisite (slice 1 of the Gambit FFI track): audit the
pinned Gambit 4.9.8 runtime source for the mechanisms the jolt FFI contract needs, and
prove or refute them with decisive executable witnesses. This unit ends with a
GO/NO-GO recommendation for P1b — it writes NO jolt adapter code.

## Paths

- Work dir (yours): ~/workspace/jolt-ffi-p1 (jolt fork, branch gambit-ffi-p1 @
  a6852ef8, submodules checked out). Commit your witnesses + AUDIT notes on this
  branch (git add/commit ONLY files you create under p1a/ in that worktree). DO NOT
  push; DO NOT touch other branches.
- Gambit 4.9.8 runtime source (read): ~/workspace/gambit-v4.9.8-src (tag v4.9.8,
  2228e4d). Read-only for you.
- Gambit binaries: use the absolute mise path
  /Users/sto/.local/share/mise/installs/gambit/4.9.8/bin/gsi and gsc. NEVER bare
  gsi/gsc from PATH (gsc is Ghostscript there).
- Design ruling (read first): /Users/sto/workspace/datomworld/collab/jolt/1794976827310-architect-gambit-ffi-plan.gpt-6-astra.findings.md
  — especially the "Five things the implementer must record" list in §1, the
  blocking-transition sequence in §2, and the gambitffi-blocking-gc gate evidence
  requirements in §3.
- jolt contract context (read-only): /Users/sto/workspace/datomworld/build/tools/jolt
  — host/scheme-adapter/TARGET-CONTRACT.md + CONTRACT.txt (the pinned semantics),
  host/gambit/scheme-adapter-runtime.ss (today's refusals; sa-gc-collect no-op at
  line ~81).

## Method (owner ruling 2026-11-18: DOC-FIRST)

Gambit's FFI is well documented — start there. Evidence hierarchy, in order:
1. The official manual shipped in the source clone (~/workspace/gambit-v4.9.8-src/doc/
   — FFI chapter: c-declare/c-lambda/c-define, foreign types, calling Scheme from C;
   threads/runtime chapters) — cite chapter/section.
2. The documented C API in the installed headers
   (~/.local/share/mise/installs/gambit/4.9.8/include/gambit.h and
   gambit-not409008.h — heavily commented; ___setup*, threading/processor params,
   gc entry points) — cite declaration.
3. The runtime C implementation (~/workspace/gambit-v4.9.8-src/lib/) ONLY where the
   docs underdetermine the jolt contract's pinned semantics — cite file:line and say
   explicitly which doc row the implementation reading resolves.
Every audit answer must cite (1) or (2) first when they cover it; a bare
implementation citation without a doc gap justification is a defect in the audit.
The witnesses remain the decisive evidence; docs/source explain what they show.

## The five audit questions (astra's list — answer each with doc/API/file:line evidence)

1. Runtime build configuration and threading model of the provisioned 4.9.8
   (multi-processor? green threads over pthreads? how many OS threads run Scheme
   execution?). Confirm against the actual configure options the mise plugin used
   (plugin: ~/.local/share/mise/plugins/gambit or asdf-joeljuca-asdf-gambit — read
   its bin/install; it passes --enable-single-host; jolt CI additionally used
   --enable-default-runtime-options=f8,-8,t8 — state what f8/-8/t8 mean from the
   Gambit source and whether the provisioned runtime matches CI's configuration).
2. The exact leave/re-enter (release/claim) mechanism: find its declaration and
   implementation in the runtime C source; record entry-point names, semantics, and
   constraints.
3. Does a released foreign frame permit (a) another Gambit thread to run and
   (b) collection to complete? Name the mechanism that makes this true or the one
   that makes it false.
4. Rules for a previously unknown pthread entering Scheme: what protocol must a
   foreign thread follow before it may touch Scheme objects or run Scheme code?
5. Root and processor-state obligations across leave/re-enter transitions: what must
   be rooted, what must be saved/restored, and where do the current 4.9.8 macros
   enforce this?

## Witnesses (decisive, not illustrative — astra §3 standards)

Build under ~/workspace/jolt-ffi-p1/p1a/witnesses/ with a Makefile. C fixtures +
gsc/gsi programs, each with a watchdog timeout so a deadlock is a recorded FAILURE:

- W1 errno-atomicity: typed C shim capturing result + errno immediately after the
  call (astra's essential sequence), C fixture returning distinct errnos per call
  shape, concurrent calls, and a deliberate post-capture errno clobber proving
  capture order. Assert the Scheme side sees the right pair every time.
- W2 blocking-GC: C fixture announces entry, blocks on a handshake; a second Gambit
  thread performs a REAL collection (not the adapter's no-op sa-gc-collect — find
  the real mechanism, e.g. a gc request through the runtime API) and allocates
  until collection demonstrably completes while C is blocked; then C returns.
  Deadlock or failure = NO-GO evidence.
- W3 foreign-thread entry: a raw pthread (not spawned by Gambit) follows the
  documented entry protocol, runs a trivial Scheme computation, exits. Unknown
  pthread + crash = NO-GO evidence for that row.
- W4 callables: Scheme closure called from C (same-thread), from a Gambit thread
  during a released blocking call, and nested callbacks; document which modes work
  under which entry protocol.
- W0 build-config witness: print ___VERSION, runtime options, processor count, and
  the configured feature macros, recorded verbatim in the audit.

Each witness: PASS/FAIL + the mechanism it exercised, in the audit report.

## Deliverables

1. ~/workspace/jolt-ffi-p1/p1a/AUDIT.md — the five answers with file:line citations
   into ~/workspace/gambit-v4.9.8-src, per-witness results, and the GO/NO-GO /
   PARTIAL-GO recommendation using astra's criteria verbatim.
2. The witnesses + Makefile committed on gambit-ffi-p1 (one or more commits, clear
   messages; nothing outside p1a/).
3. Findings file: /Users/sto/workspace/datomworld/collab/jolt/1795016000000-engineer-p1a-gambit-runtime-audit.glm-5.3-flash.findings.md
   (standard header; same content as AUDIT.md plus commands run and open risks).
   End with DONE on its own line.

## Rules

- Foreground commands with explicit timeouts; witness runs must be watchdogged.
- Do not modify ~/workspace/gambit-v4.9.8-src, ~/workspace/jolt (the main fork
  clone), the datomworld checkout, or build/tools/jolt. You may build gambit
  programs; if you need a differently-configured gambit build to test a mechanism,
  build it OUT-OF-TREE under p1a/gambit-builds/ (configure from the v4.9.8 source,
  never modifying the source tree) and say so in the audit.
- Final response starts with Completed-GMT / Completed-Local / Coding-Agent /
  Session-ID lines and states GO / PARTIAL-GO / NO-GO plus the one-line reason.
