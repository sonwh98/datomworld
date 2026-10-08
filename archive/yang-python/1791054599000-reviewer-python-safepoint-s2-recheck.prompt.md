Created-GMT: 2026-10-03 19:09:59 GMT
Created-Local: 2026-10-04 02:09:59 +07 (+0700)
Coding-Agent: glm (resume 0f8f417d-ed1a-4f1c-a248-9ce6c6956871) and codex (gpt-6.1-sol, resume 01a10060-5a6d-7840-8e54-b6fd7de72287)
Session-ID: 0f8f417d-ed1a-4f1c-a248-9ce6c6956871 (glm); 01a10060-5a6d-7840-8e54-b6fd7de72287 (sol)

# Task: Python safepoint slice 2 gate re-check (after the fixes, on a rebased tree)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-10-04 02:10 +07 | Status: active | Rationale: resume of its static gate to confirm the fixes
- Model: gpt-6.1-sol | Assigned: 2026-10-04 02:10 +07 | Status: active | Rationale: resume of its gate thread to confirm the fixes

Resume your safepoint-s2 gate for the CURRENT tree in /Users/sto/workspace/datomworld-py-safepoint2 (branch yang-python-safepoint-s2), now REBASED onto master a4efc99a, which contains C2-S3
(`yield from`, `iter`), the range fast path, C3-S2 numeric keys, float-fix and the test-lane changes. The change is STAGED: use `git diff --cached` (9 files, +4207/-97; most of the bulk is the
new test/yang/python/antlr/safepoint_programs.cljc packets).

The orchestrator independently checked your original findings:
- Both of you (sol P1, glm P2): `py/gen-switch` never compared the effective depth with the limit when a generator is started or resumed: AGREE. The engineer proved it red-then-green (a failing
  `generator-admission-test` on all four VMs before the fix, passing after), moved the limit cell into the BASE prelude as `py.rt/limit` (default 1000; naive programs load only the base prelude,
  so the hook prelude's `py.sp/limit` was not nameable there), and `py/gen-switch` now compares `1 + (py/abs-depth ctx)` with the limit BEFORE any write to the generator or `py.rt/ctx`, on both
  start and resume; `throw`/`close` take the same path. The architect pair (fable-5.1 and gpt-6-astra, independently) APPROVED this placement: collab/1791053247000-architect-safepoint-limit-cell-placement.*
  (copies in the worktree's collab/). Naive runs now also refuse generator crossings past the limit, counting nested active generators only; that is a documented base-runtime amendment.
- sol P2 / glm P3 (missing tests): AGREE; added shallow-then-deep resume, nested active generators counted once each, `throw`/`close` with `finally` at the current base, a `try` exited by normal
  return across differing resume depths, and a rejected deep resume followed by a good shallow one; then, after C2-S3 landed, `admission-in-every-mode-test` (naive, no-op hooks and real hooks all refuse
  the same crossings) and `delegation-admission-test` (a `yield from` chain top->mid->leaf: one frame per active generator, a refusal at the limit leaves the whole chain suspended, `throw` and `close`
  refused at the limit; pinned output `97 77 refused 77 "throw refused" 97 thrown 97 "close refused" 97 100`). Verify them.
- glm P3 (the old depth paragraph said an escape restores "the whole record" one paragraph above the `:base` refinement): fixed.
- both P3 (line widths): fixed in safepoint.cljc, the packet data and the added test lines; the seven packet docstrings stay over 80 columns because wrapping would change the strings.
- The architects' required comment/doc fixes: the `RecursionError` class comment, the "three runtime cells" docstring and the `py.rt/out` alignment in prelude.cljc, and the merged ownership statement in
  yang.antlr.md 8.5.2.
- Goldens re-minted ONCE on this final base: six prelude-derived content-address goldens (base prelude root, HOOK prelude root, four program addresses) because both the prelude and the hook prelude changed.

Re-check, with file:line evidence:
1. The admission check itself: both start and resume paths, ordering (compare BEFORE any write to the generator or ctx), the exact comparison (`1 + abs-depth` against the limit, off-by-one), `throw`/`close` into
   a never-started or closed generator not consuming a frame, and the failure leaving the target `:created`/`:suspended` with its context uninstalled and the caller's context intact.
2. The base/hook split: no hook name appears in the base prelude; the hook prelude's setter, getter and `py.sp/enter` read and write the base cell; the setter's current-depth check still holds; the
   limit cell is outside what escapes restore. Any program (naive, no-op hooks, real hooks) whose behavior differs in an unintended way? Does the hook-prelude content that moved (data/str-concat added to host names)
   keep the hook prelude loadable in every composition?
3. The new tests: can each fail, are the pinned counts derivable from the code (recompute two by hand), is `delegation-admission-test` actually exercising the C2-S3 `py/delegate-step` entries, and does the
   parser check cover all nine packets? Is anything weakened relative to master (master's `tail-preservation-test` `^:slow` tag and `dao.test-slow/guard` wrapper intact)?
4. Merge: no conflict markers or duplicated definitions in prelude.cljc (C2-S3's `py/yield-from`, `py/stop-as-return`, C3-S2's `py/key`, `py/int-canon`, `py/hash`, the range fast path all present once and untouched
   except where the diff intends).
5. Goldens: only the six address goldens changed in float_address_test.cljc relative to master.
6. Cross-host portability: `:cljd` first in every conditional, no unary float minus, no keyword/identity pitfalls, no quoted-vector-literal evaluation mistakes (prelude vector literals are data).

Re-read only relevant design, source and test lines. Challenge these conclusions. Do not repeat resolved findings unless the fix is incomplete. Do not edit. Do NOT run suites (the orchestrator is running
the full JVM, Node and Dart lanes on this exact tree now).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Return: finding | final disposition | evidence | remaining action. Report new defects as P0-P3 | file:line | evidence | concrete fix.
Explicitly state whether the change is ready to commit.
