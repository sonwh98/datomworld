Created-GMT: 2026-11-19 17:09:43 GMT
Created-Local: 2026-11-20 00:09:43 ICT
Session-ID: pending (capture thread.started.thread_id)
# Task: P1c — run yin.repl on the jolt Gambit backend (the goal slice)

Role: VM Runtime Engineer (implementation)
Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-20 00:2x:xx ICT | Status: active | Rationale: routing board: codex primary; continuation of the stage-1 thread family

## Goal (owner objective, verbatim)

"yin.repl should run on jolt using gambit backend with FFI support."

Stage-1 (branch gambit-ffi-p1b, commits through 88630634) proved the runtime,
compiler/eval, and the basic FFI tier on gsi. What has NEVER run under Gambit:
yin.repl itself — the REPL entry point, its host wiring, frontends. This slice
closes that: a real yin.repl session (read-eval-print loop) executing under the
Gambit backend, with the qualified FFI tier available, and honest degradation
for the capabilities the Gambit target refuses.

## Context (verified)

- Chez path today: `bb jolt` / `smoke:jolt` run `jolt -Srepro -M:jolt` with
  yin.repl hosts on JVM/Node/Dart (src/cljc/yin/repl/main.cljc, frontends,
  host.cljs/clj). The Gambit host (host/gambit/) has boot/kernel/eval + the FFI
  dir; NO yin.repl wiring (grep negative). Gambit also has a JS-target browser
  REPL (host/gambit/repl-main.ss) — precedent that a REPL loop runs under
  Gambit schedulers.
- The Gambit target degrades: subprocess (sa-run-process raises), fasl, AOT.
  yin.repl's shell-session frontend needs processes — those legs must degrade
  with message-carrying refusals, not crash the REPL.
- The stage-1 gate matrix (gambitffi*, gambitcheck/kernel/eval, seeds) must
  stay green after your changes.

## Work dir

NEW branch off the stage-1 tip: cd ~/workspace/jolt-ffi-p1b && git worktree add
~/workspace/jolt-ffi-p1c -b gambit-ffi-p1c gambit-ffi-p1b. Commit there; never
push. Do NOT touch jolt-ffi-p1b/-inv/-mt or ~/workspace/jolt. Gambit binaries
via the absolute mise paths; gambit source read-only.

## Deliverables

1. **Audit first (P1 evidence):** what yin.repl needs from the host that the
   Gambit target must provide: the REPL main entry path (main.cljc heads),
   host-table/io rows, the frontends catalog (which frontends can run: core
   eval; which must refuse: shell sessions (subprocess), anything on fasl/AOT),
   and the FFI exposure to the REPL (jolt.ffi reachable in the session).
   Record the audit in the findings BEFORE implementing.
2. **Minimal REPL under gsi:** a scripted yin.repl session (stdin -> REPL ->
   stdout) through the Gambit backend: boot the runtime + yin.repl main heads,
   evaluate a representative expression corpus (yi expressions through the
   compiler, at least one FFI call via the qualified tier, error handling with
   the documented messages), print results. Subprocess-dependent frontends
   raise their documented unsupported-capability errors inline without killing
   the REPL loop.
3. **Gate:** a new make target (e.g. `gambityinrepl`) running the scripted
   session with assertive state-derived verdicts (output content checks, not
   exit codes — the Lane B lesson), watchdogged, added to the gambitffi
   aggregate or the meta gate list as appropriate.
4. **macOS regression:** the full stage-1 gate matrix (gambitffi, gambitcheck,
   gambitkernel, gambiteval, seed/gen checks) green after your changes.
5. Findings:
   /Users/sto/workspace/datomworld/collab/jolt/1795110000000-engineer-p1c-yin-repl-gambit.gpt-6.2-sol.findings.md
   (standard header; the audit, what runs vs what degrades with file:line,
   verbatim session output, ends DONE). Final response: Completed-* header +
   the goal-satisfaction line: does yin.repl now run on the Gambit backend
   with FFI support (yes/partial + what degrades)?

## Bounds

No datomworld changes; no Chez-path behavior changes; the C4 must-not-assert
list stands (no blocking/foreign-thread/dynamic-callable/Linux/AOT claims);
subprocess legs degrade honestly. FOREGROUND with explicit timeouts; watchdog
everything; poll long compiles with short Reads.

## Reference

- Stage-1 findings: /Users/sto/workspace/datomworld/collab/jolt/1795042000000-engineer-p1b-stage1-codex-gpt-6.1-sol.findings.md
- Astra design: /Users/sto/workspace/datomworld/collab/jolt/1794976827310-architect-gambit-ffi-plan.gpt-6-astra.findings.md
- Confirmation (C4 constraints): /Users/sto/workspace/datomworld/collab/jolt/1795040000000-reviewer-p1a-confirm-codex.gpt-6.1-sol.findings.md
- yin.repl docs: /Users/sto/workspace/datomworld/src/cljc/yin/vm/docs/yin.repl.md (read-only)
