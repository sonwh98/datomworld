Created-GMT: 2026-11-17 17:13:00 GMT
Created-Local: 2026-11-17 23:13:00 ICT (+07)
Coding-Agent: glm
Session-ID: d19d82a2-28fb-464b-966c-68e35a9b4848
# Task: yin.repl jolt host lane on Gambit Scheme, gambit via mise

Role: Compiler & AST Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-11-17 23:13:00 +07 | Status: active | Rationale: flat-plan lane; toolchain/build wiring work with checkable claims

Work in the worktree `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-gambit-host` (branch `fix/jolt-gambit-host`, base master@2687ab0c). All paths below are relative to that worktree. Do NOT touch the main checkout or any other worktree.

## Background (verified by the orchestrator; re-check anything you rely on)

- yin.repl runs on jolt with Chez today: `bb bootstrap:jolt` materializes
  `jolt-toolchain.edn` pins (Chez 10.4.1, CMake via mise) into build/tools/jolt and
  writes `build/tools/jolt-env.sh` (sets `JOLT_CHEZ` explicitly); the lane runs
  `jolt -Srepro -M:jolt ...` (bb.edn tasks `smoke:jolt`, `jolt`).
- The pinned jolt clone (build/tools/jolt) ALREADY contains a full Gambit host:
  `host/gambit/` (boot, prelude-shims, scheme-adapter-runtime, records-gambit,
  gambitcheck G1 gate, kernel/seed/twins/unbound gates) and
  `host/scheme-adapter/{CONTRACT.txt,TARGET-CONTRACT.md,chez.ss,guile.ss}`.
  On the Gambit target, ffi / native compilation / fasl / subprocess RAISE
  unsupported-capability errors BY DESIGN (see gambitcheck.ss). FFI-degraded is the
  accepted scope: the native ANTLR parser and libcrypto legs are NOT expected to run.
- mise has NO `gambit` in its registry (verified live with `mise registry` and
  `mise ls-remote gambit`). You must wire Gambit through a mise custom backend and
  pin an exact version. jolt's own CI notes say its Gambit gates target 4.9.8 built
  from source ("tests.yml builds 4.9.8 from source into /opt/gambit", Makefile
  comments). NEVER invoke bare `gsi`/`gsc` from PATH (`gsc` on PATH is Ghostscript) —
  always the mise-resolved absolute binary path.
- jolt's Makefile gates locate Gambit via `GAMBIT_PREFIX ?= $(brew --prefix
  gambit-scheme)` and pass `$(GAMBIT_PREFIX)/bin/gsi`.

## Deliverables

1. **mise-managed Gambit.** Add an exact-pinned `gambit` to the worktree's
   `mise.toml` (style-match the existing chezscheme pin and its comment block; a
   change goes through the jolt upgrade gate — so the pin must be exact, never
   "latest"). Choose the backend yourself after probing what actually works on this
   machine (mise 2026.2.19, macOS arm64): candidates are the git backend building
   from source (gambitscheme/gambit; heavy build — allowed, use a -j flag and a
   generous timeout), an aqua/ubi backend if a binary exists, or a vfox/asdf plugin
   if trustworthy. Record in your findings which backend you chose, the exact pin,
   and why. `mise install` must succeed and `mise exec gambit@<pin> -- which gsi`
   must resolve.
2. **jolt-toolchain.edn**: add a `:gambit` entry mirroring `:chez`'s shape
   (version, provider :mise, mise-tool, binary). Re-pin nothing else.
3. **src/dev/jolt_bootstrap.clj**: add a `check-gambit` analogous to `check-chez`
   (version probe: `gsi --version` output must equal the pin; smoke the interpreter),
   include it in the bootstrap flow, and write `JOLT_GAMBIT` (absolute path to gsi)
   into `build/tools/jolt-env.sh` alongside `JOLT_CHEZ`. Bootstrap must still
   succeed on a host without gambit ONLY if you make gambit's absence a
   non-fatal warning UNLESS it is cleaner to require it — decide, and record why.
4. **Prove the lane.** In the worktree run, in order: `bb bootstrap:jolt`, then the
   jolt Gambit G1 gate through mise-resolved binaries, e.g.
   `make gambitcheck GAMBIT_PREFIX=<mise gambit prefix>` (run from build/tools/jolt
   after bootstrap) — capture its pass/fail verbatim. Then run the kernel gate
   (`make gambitkernel ...`) if it exists in the Makefile. Then attempt a yin.repl
   smoke under Gambit: explore host/gambit/boot*.ss / gen-boot.ss to find how the
   jolt runtime is loaded under gsi and run at least `(+ 1 2)` through the jolt
   runtime; if a full `jolt -Srepro -M:jolt` equivalent under gsi exists or the
   smoke.clj path can run, run it; if not reachable, document exactly what blocks
   it (with file:line evidence) and stop there — do NOT modify anything inside
   build/tools/jolt (the pinned clone): a change there would need a declared patch
   and is out of scope for this slice.
5. Report the FFI legs explicitly: one attempted ffi call under Gambit raising the
   documented unsupported-capability error is a PASS for this slice; capture it.

## Bounds

- Files you own: `mise.toml`, `jolt-toolchain.edn`, `src/dev/jolt_bootstrap.clj`,
  `build/tools/jolt-env.sh` (generated), and bb.edn ONLY if a new task is genuinely
  needed (style-match existing tasks). Nothing else. Do not commit.
- mise.toml / bb.edn are wide-change inputs: do NOT run the full test suite;
  the orchestrator owns gate runs. Focused checks you run are fine.
- FOREGROUND only, explicit timeouts on every long command (a Gambit source build
  can take many minutes — run it with a timeout and `-j`).
- Do not run `bb test`, `bb test:all`, or any suite-scale lane.

## Deliverable

Write your complete findings to
`collab/jolt/1794934400000-engineer-gambit-host-lane.glm-5.3.findings.md` in the
worktree (create the folder if needed; it may not exist there). The findings file
starts with the same header fields above and reports: what you changed (files +
why), the mise backend chosen and pin, exact commands run with outcomes (bootstrap,
gambitcheck, kernel gate, smoke, ffi probe) including verbatim key output lines,
what did NOT run and why, and any open risks. End with `DONE` on its own line when
complete.

Begin the final response exactly with:
Completed-GMT: <actual time GMT>
Completed-Local: <actual local time>
Coding-Agent: glm
Session-ID: d19d82a2-28fb-464b-966c-68e35a9b4848
