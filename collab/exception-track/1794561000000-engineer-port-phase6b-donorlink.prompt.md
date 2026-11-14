Created-GMT: 2026-11-12 23:15:00 GMT
Created-Local: 2026-11-13 06:15:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a1d1ee-523c-7be2-b321-8eb8aae65cb7 (resumed thread)
# Task: engineer — PORT Phase 6b: fix the clj.rt/ctx donor-link rejection (18 python-suite failures)
Role: Engineer (continuation of the port; you reproduced this at f15423c2 yourself — it is
PRE-EXISTING, not the carrier's doing)
Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 06:15 +0700 | Status: active | Rationale: you hold
  the reproduction; the port owns the surface.

## The problem

`clojure -M:test -n yang.call.python-exception-test` → 18 failures: `clj.rt/ctx` is rejected as an
**undeclared free name when linking the Clojure donor**. Reproduced at untouched `f15423c2`
(Phase 5 baseline) — introduced somewhere in Phases 1-2 when the `clj/` continuation family
landed and its handler-stack cell (`clj.rt/ctx`) became part of clojure lowering; it breaks the
cross-language donor link path the kit uses (the python forgery pins run a clojure `forge` donor).

## What to do

1. **Compare the working pattern**: python/ts/php donors ALL expose an `rt/ctx` cell
   (`py.rt/ctx`, `js.rt/ctx`, `php.rt/ctx`) and their donors link fine across languages (the
   kit's python/js rows are green with them). Find how those fronts' donor modules DECLARE/
   export their ctx cell vs how the clojure donor module (`linker-manifest`/module surface,
   the `dsg__`-protected primitives emitted) currently fails to, in the LINKED composition
   path (`yin.shell.query/link-answer` era APIs — the shell refactor is on master; the donor
   link goes through `yin.repl`/`yin.shell` composition).
2. **Fix at the right layer**: most likely the clojure donor's emitted module must declare the
   ctx cell in its public surface (like `py/rt-ctx` does — check python's prelude `rt-ctx`
   export site, `prelude.cljc` near its door exports) so the linker resolves it as declared,
   NOT by making the linker permissive (never weaken the undeclared-free-name rule — that's
   an admission boundary).
3. **Pins**: the failing 18 must go green; add a cross-donor pin for the ctx declaration
   specifically (the port's own surfaces — NOT the kit; kit stays untouched); the whole
   `yang.call.python-exception-test` + `yang.clojure-test` + shared host suites green;
   contract remote-only (orchestrator runs).
4. If the root cause is deeper (the port's prelude emission genuinely missing a module-surface
   declaration that py/ts/php have), fix the emission to mirror the siblings — that is the
   whole track's spirit (one encoding, one shape).

## Constraints

No kit files; `src/cljc/yin/vm/` empty-diff; worktree `/Users/sto/workspace/datomworld-r3-orch`,
branch `exception-r3-impl` @ `f2e82ffc`. Commit + push.

End with Completed-GMT/Completed-Local and: **DONOR-LINK FIXED: <sha>** or **FINDING: <deeper
root cause for the orchestrator>**.
