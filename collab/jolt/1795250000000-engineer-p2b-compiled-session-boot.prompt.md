Created-GMT: 2026-11-26 11:25:50 GMT
Session-ID: pending (new thread; continue the p1c/p2 lineage)
# Task: P2b — compiled-session boot for jolt-gambit: the Yin closure compiles into the cached boot (11 min interpreted -> seconds)

Role: VM Runtime Engineer (implementation; owner-directed)
Implementers:
- Model: gpt-6.2-sol | Assigned: see Created-Local

## Goal (owner directive: "ok do it")

Extend the jolt-gambit boot-build pipeline so the 139-namespace Yin closure
compiles into the cached boot — launches reach `yin>` in SECONDS instead of
the ~11-minute interpreted source load, with reply-for-reply equivalence to
the interpreted session proven by gate.

## Foundation (all verified live — build on these)

- P1c-r3's diagnostic PROVED the concept: a compiled copy of the compiler
  image loaded the same namespaces in seconds where interpreted loading took
  minutes (its round-3 log; the compile route was exercised on this exact
  tree).
- build/p1c-r3/chunks/ contains the staged compile artifacts (kernel.ss ->
  kernel.c, 36 MB) — the compile machinery works on this tree.
- The bridge cache pattern (build/gambit-ffi/, digest-stamped, per-install
  provenance) and the compiled-boot pattern (GAMBIT_YIN_BOOT env, staged
  atomically) are the established infrastructure.
- The interpreted path remains the FALLBACK and the correctness reference:
  the compiled session must match it reply-for-reply (the gambityinrepl
  harness's 12-step state machine is the equivalence oracle).

## The design problem to solve (this is the slice's core, not boilerplate)

The Yin closure is SOURCE-loaded today (host/gambit/source-loader.ss walks
the overlays -> datomworld source -> stdlib -> core, with the booted-
namespace shortcut and the r5 progress lines). Compiling it ahead of time
has three hard parts:

1. **The closure boundary**: only static namespace code compiles; runtime-
   generated closures and image-state refuse (the image writer refuses
   closures — the C4-era constraint). Audit the 139-namespace closure for
   what the compiled boot can carry: namespace definitions, macro expansions
   already expanded (the seeds carry expansion state — check how
   gambitseedcheck's byte-fixpoint works), and what must stay dynamic (the
   P1e session-eval path, defcfn declarations in session code). Define the
   boundary EXPLICITLY and make the loader fall back to source interpretation
   for anything outside it — the compiled boot is an acceleration of the
   interpreted path, never a different semantics.
2. **Staleness**: digest the entire Yin source closure (overlays + datomworld
   src/cljc + stdlib + core inputs) and rebuild when anything changes — the
   bridge-cache pattern generalized. The digest must cover emitter/version
   inputs too (the r2 docstring fix and the r3 errno selector both changed
   emitted code historically — those would have been silent stale-boot bugs
   without digests).
3. **The equivalence oracle**: the compiled session must produce IDENTICAL
   replies to the interpreted session for the gambityinrepl corpus (and the
   DHT probe corpus) — gate-held, not assumed.

## Work dir

Worktree off gambit-ffi-p1f: cd ~/workspace/jolt && git worktree add
/Users/sto/workspace/jolt-ffi-p2b -b gambit-ffi-p2b gambit-ffi-p1f. Commit
there; never push; nothing outside the worktree.

## Deliverables

1. The compiled-session boot: build-yin-boot.py (or a sibling) compiles the
   Yin closure into the cached boot; digests cover every input class; the
   launcher selects compiled-first with interpreted fallback on
   digest/compile mismatch (never silently stale).
2. Launch profile: warm launch reaches `yin>` in seconds (measure and record;
   the interpreted 11-min path remains available via an explicit env/opt-in
   for debugging — document the flag).
3. Gates: gambityinrepl passes against the COMPILED boot (reply-for-reply
   with the interpreted reference); a new equivalence gate runs both paths
   and diffs replies; the stage-1 matrix + gambitsourceload green; the r5
   progress lines still work on the interpreted fallback.
4. Findings: /Users/sto/workspace/datomworld/collab/jolt/1795250000000-engineer-p2b-compiled-session-boot.gpt-6.2-sol.findings.md
   (standard header; the closure-boundary audit — what compiles, what stays
   dynamic, with file:line; staleness design; measured timings cold/warm;
   the equivalence evidence; ends DONE). Final response: Completed-* header +
   the measured seconds-to-yin> + equivalence line.

## Rules

C4 constraints binding (fasl/image-state stays refused; the closure boundary
is not an end-run around it — compiled CODE only, no serialized closures or
image state). FOREGROUND with explicit timeouts; the closure compile is the
long pole (watchdog + poll); nothing outside the worktree; the interpreted
fallback must remain the correctness reference — if compiled and interpreted
replies ever differ, that is a finding and the compiled boot ships broken-
to-fallback, never wrong-but-fast.
