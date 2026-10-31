Created-GMT: 2026-10-03 18:58:01 GMT
Created-Local: 2026-10-04 01:58:01 +07 (+0700)
Coding-Agent: claude
Session-ID: a80b326f-0efb-4ae5-ae0f-df0154076bab (resume of the safepoint-s2 engineer session)

# Task: safepoint-s2 round 5 — architect-approved design, rebased onto C2-S3, final tests and the one golden re-mint

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-04 01:58 +07 | Status: active | Rationale: resume of the safepoint-s2 engineer; the architect pair approved the limit-cell design and C2-S3 has landed

Work in /Users/sto/workspace/datomworld-py-safepoint2 (branch yang-python-safepoint-s2). The orchestrator REBASED your worktree onto master a4efc99a, which now contains C2-S3
(`yield from`, `iter`, sequence iterators), the range fast path, C3-S2 numeric keys, float-fix, linker Stage 1 and the ^:slow / dao.test-slow/guard test lanes. The rebase had NO
conflicts; your round-4 diff is re-applied. Backups: ../datomworld-py-safepoint2.prerebase2.patch, ../datomworld-py-safepoint2.safepoint_programs.cljc.bak2 and a git stash. You cannot run git
write commands (stash, checkout, rebase, reset, commit, stage): do not try. Edit files directly. Do NOT run the full lanes or Dart (the orchestrator runs them). NEVER run
`clojure -A:test -M -e` (it launches the full runner).

## Architect ruling on your design (fable-5.1 and gpt-6-astra, independently): APPROVED

Reports staged in this worktree's collab/: 1791053247000-architect-safepoint-limit-cell-placement.claude-fable-5-1.stdout.log and ...gpt-6-astra.final.md. Both accept your choice
(`py.rt/limit` in the BASE prelude, generator admission always on, hook prelude reads and writes that cell). Naive refusals are a subset of CPython's; hooked runs only add. Required follow-ups:

1. Stale comments in prelude.cljc (fable): the `RecursionError` class comment ("raised by the safepoint hook prelude's depth accounting") is now incomplete (the base raises it at generator
   admission too); the state-definitions docstring says "the two runtime cells" but there are three; the namespace docstring's `py.rt/out` line lost its column alignment. Fix all three.
2. docs/design/yang.antlr.md 8.5.2: add one clear ownership statement merging both architects' sentences, for example: "The base prelude owns the dynamic-context record, the limit cell and
   admission at generator crossings, because the crossing is prelude code that no site mark reaches, and enforces admission in every execution mode; the hook prelude owns counting function
   frames. Without recursion hooks the effective depth counts nested active generator frames only, which CPython also bounds; the recursion profile additionally counts ordinary Python function
   frames." Do not describe naive mode as complete CPython recursion accounting.
3. (astra) A test pinning naive versus no-op-hook equivalence at the ADMISSION boundary, next to your fully instrumented admission test: with the same program, a naive run and a no-op-hook run
   refuse the same crossing; a failed admission leaves the target generator `:created`/`:suspended` and does NOT install its context; the caller's own exception handling may unwind the
   caller; a later shallower admission succeeds. All four evaluators, across hosts where the existing admission test runs.
4. (both, since C2-S3 has landed) `yield from` boundary tests, all four evaluators: a delegation chain of k generators (use a small limit like your 100-pins) refused at the limit leaves EVERY
   generator in the chain suspended and the caller untouched; each ACTIVE delegating generator contributes exactly one frame to the effective depth (state the pinned counts, in your 79/99 style, and
   compute them from the code, then confirm by running); refusal followed by a successful shallower resume through the delegation; `throw` and `close` through a delegation at the limit. Delegation
   enters generators only through `py/gen-switch` (`py/delegate-step`, prelude.cljc ~517-533), so no extra check is needed; if a test shows otherwise, STOP and report.
5. Re-mint the content-address goldens LAST (and only once): the prelude changed twice (C2-S3 landed under you, and your own limit cell and admission), and the HOOK prelude changed too, so
   `float-address-test` should fail on the base-prelude root, the hook-prelude root and the program addresses derived from them (6 assertions last round). Run
   `clojure -M:test -n yang.python.antlr.float-address-test`, write the actual values in (same `:segment/blake3-...` form), and confirm with `git diff HEAD -- the file` that ONLY
   address-golden lines differ from master. Do not change any non-golden assertion.

## Lanes and rules
- FOCUSED runs only: both safepoint namespaces (`-n yang.python.antlr.safepoint-test -n yang.safepoint-test`, `-e :slow` plus run `tail-preservation-test` once with `-i :slow` by name),
  e2e-test and e2e-c2-test (`-e :slow`), prelude-parity-test, lower-test, float-address-test (after the re-mint), the new tests. Do not run long-loops-test.
- kondo 0 errors; `cljstyle fix` then `check` via mise on changed files (run directly, not through a piped loop).
- Allowed files: those in your diff plus safepoint_programs.cljc and docs/design/yang.antlr.md. Ask before anything else.

Append a 'Round 5' section to your findings file collab/1791051870000-compiler-engineer-python-safepoint-s2-r4.claude-opus-5-5.findings.md (or the file you used): per item what changed, the pinned
counts and how you derived them, the old to new goldens, focused counts. Begin the final response with Completed-GMT / Completed-Local / Coding-Agent / Session-ID as before. Do not claim edits or
runs that did not occur.
