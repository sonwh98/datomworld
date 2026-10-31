Created-GMT: 2026-09-26 06:17:31 GMT
Created-Local: 2026-09-26 13:17:31 +0700
Coding-Agent: glm
Session-ID: b8683d48-38fd-4418-8bf3-9815e82192e3

# Task: yin.vm.linker M4 slice S2, host-module registration and the registry value shape

Role: VM Runtime Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-26 13:17:31 +0700 | Status: active | Rationale: owner directive "use opus for S1 and glm for S2"

Repository: the worktree /Users/sto/workspace/datomworld-m4-s2 (branch m4-s2, from master
b3d0b5a1). Collab files: /Users/sto/workspace/datomworld/collab/ (absolute paths; the worktree
has no collab/). Do NOT touch any other worktree. A parallel worker (Opus) is doing slice S1
in ../datomworld-m4-s1; it edits the four backend files and the append functions in
src/cljc/yin/repl.cljc. You edit repl.cljc ONLY at the module-registry line near 436.

## Owner statement (verbatim quote)

"use opus for S1 and glm for S2"

## What to build (slice S2 of M4)
docs/design/yin.vm.linker.md section 8.3 (what yin.vm.module becomes; the table near line 1828,
the enforcement text near line 1842, and the coexistence note near line 2026) and section 10
(the module.cljc entry). Implement:
- register-host-module r name fns profiles in src/cljc/yin/vm/module.cljc, with UCF profile-class
  enforcement: refuse a :host-class profile, undeclared host state, or a missing profile (see the
  UCF design's primitive profiles, section 7.5.2, and yin.vm/primitive-profiles near line 398 of
  src/cljc/yin/vm.cljc);
- register-stream-module built over register-host-module;
- the registry value shape change to {name {:manifest m :address a :derivation d :slice ...
  :stores ...}}, a clean break with NO shim (the repo has no deployed stores and no legacy);
- resolve-module keeps its signature.
One commit must rewrite ALL callers (verified now): src/cljc/dao/await.cljc lines 87 to 89,
src/cljc/yin/repl.cljc line 436, src/cljs/datomworld/demo/compilation_pipeline.cljs line 87,
src/cljs/datomworld/demo/continuation_stream.cljs line 83, and the module and engine tests.
TRAP: every stream-module binding (make, put!, cursor, next!, close!) must carry a profile in
primitive-profiles or registration refuses; verify this BEFORE landing so nothing regresses.
Tests first: registration accepts a well-profiled binding; refuses a :host-class one, one with
undeclared host state, and one with no profile; the registry value shape; every existing consumer
still resolves its modules. No existing test may change behavior except where the registry shape
itself is asserted.
Out of scope: the require lowering and the install child (S3), the manifest records (S4), the
kernels (S1), any UCF doc edit (S5).

## References (unverified aids)
A read-only scoping report describes S2 in its section B: /Users/sto/workspace/datomworld/collab/1790355000000-architect-linker-m3-m5-scoping.glm-5.3.report.md. Check its claims against the tree.

## Verification
GLM's weekly budget is small (resets 2026-09-27 01:26): be efficient, do not re-read what you can
cite, keep the report tight. Run the JVM lane before your first edit for a baseline (master:
2,118 tests / 181,744 assertions) and after; the orchestrator runs Node and Dart. kondo and
cljstyle on every changed clj, cljc and cljs file.

Rules: TDD (failing test first, per docs/agents/build-n-test.md). mise for everything. Lint: mise exec -- clojure -M:kondo --lint <files>; mise exec -- cljstyle check <files>. Pure ASCII and <= 80 columns on every line you add or edit (Markdown grid rows excepted), no em dashes. Cross-host traps: #?(:clj ...) does NOT exclude code from the cljd build, use #?(:cljd nil :clj ...) with :cljd FIRST; #'ns/private-var cross-namespace reflection fails on CLJD, make helpers public; cljd ExceptionInfo, dart:core alias, cljs keyword identity and private mutable fields differ across hosts. Do NOT commit, stage, checkout, reset, stash or merge. If the spec conflicts with the tree or is ambiguous, STOP and report BLOCKED with the exact conflict; do not improvise a design. Rule R is in force: yin/def is syntax, never a name; only yin/def is reserved; no contract stamp is ever assigned to external input. The orchestrator reruns every lane independently and sends the diff to a non-author reviewer.

## Report
Write your final report to
/Users/sto/workspace/datomworld/collab/1790403451134-vm-engineer-linker-m4-s2-host-module.glm-5.3.report.md
(same header fields) and return it as your final response, with: the JVM counts before and after,
files changed, tests added, the callers you rewrote, deviations, and unrun checks.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
