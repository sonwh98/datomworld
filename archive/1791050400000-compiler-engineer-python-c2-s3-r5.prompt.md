Created-GMT: 2026-10-03 18:00:00 GMT
Created-Local: 2026-10-04 01:00:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 8a064c70-5a78-4fc8-a2d8-1bcd34e27a0f (resume of the C2-S3 engineer session)

# Task: C2-S3 round 5 — two PEP-fidelity fixes and deeper delegation tests

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-04 01:00 +07 | Status: active | Rationale: resume of the C2-S3 engineer; applies the gate findings

Work in /Users/sto/workspace/datomworld-py-c2gen3 (branch yang-python-c2-s3, rebased onto master a932bb55; your S3 diff is staged). You cannot run git write commands (no stash,
checkout, rebase, reset, commit, or staging); the orchestrator does those. Edit files directly. Do NOT run the full lanes or Dart (the orchestrator runs them).

## Two independent gates disagree; the orchestrator adjudicated

- glm (collab/1791048979000-reviewer-python-c2s3-gate.glm-5.3-flash.stdout.log): PEP 380 fidelity conforming, five acceptance programs hand-verified against CPython 3.9.6, no P0-P2,
  READY; one P3 (dict-iterator invalidation is size-based, so `d['b']=2; del d['b']` keeps iterating; CPython raises on any mutation; inherited from the C1 loop walker: OUT of scope, do not fix).
  Use its list of conforming behaviors as a regression oracle: do not change them.
- sol (collab/1791048979000-reviewer-python-c2s3-gate.gpt-6.1-sol.final.md, three P2s, NOT READY). The orchestrator checked #1 against CPython 3.9.6 `_gen_throw`: after throwing into
  the delegate raises, CPython calls `_PyGen_FetchStopIterationValue`; if it is a StopIteration the throw is consumed as the delegation's COMPLETION with that value. So #1 and #2 are
  real fidelity defects; #3 is partly a design question (see item 3).

## Work items

1. (sol P2, prelude.cljc ~262/434/448) Thrown StopIteration into a CLOSED delegate. Today a closed delegate answers `throw(StopIteration(7))` with `[:raise e]` (line ~262) and the delegator
   re-raises it, producing PEP 479's RuntimeError. CPython 3.9.6 consumes it as delegation completion with value 7 (`outer.throw(StopIteration(7))` after the inner was independently closed
   returns/continues with the `yield from` expression's value 7). Translate a delegate's raised StopIteration, including subclasses (and its `.value`; `args[0]` / None as CPython
   does), into `[:return e.value]` at the delegation site, and PRESERVE the inner-BODY PEP 479 conversion (a StopIteration escaping the inner generator's own body is still RuntimeError). Add
   a four-VM regression test (all hosts) with a closed inner, `throw(StopIteration(7))`, and the value observed.

2. (sol P2, prelude.cljc ~1644 `py/iter-at` keys-iter arm) Dict iterator sticky invalidation. After the FIRST size-change error CPython makes the iterator permanently invalid
   (`di_used = -1`; every later `next` raises the same RuntimeError even if the size is restored). Today the error escapes before the iterator's state is updated, so restoring the
   original size lets it resume. Persist an invalidated state BEFORE raising and keep raising on later advancement. Add a parity regression: iterate a dict, grow it, catch the
   RuntimeError, restore the size, and assert the next advance still raises. Do NOT implement version-tag detection (glm's P3; out of scope).

3. (sol P2, tests; prelude_parity_test.cljc and e2e_c2_test.clj) The portable acceptance form covers two generators with one resuming caller; it must also cover, on all four evaluators across
   hosts: a delegation chain of depth >= 3; a CHANGING resuming caller (the outer generator resumed by a different caller each time, including through a delegate); and suspension
   DURING exception unwinding (a `finally`/`except` around a `yield from` that yields while an exception is propagating). Add them with CPython-3.9.6-accurate expected outputs.
   The JVM-only retained-state check (`suspended-resume-sizes`, `e2e_c2_test.clj` ~753/780) measures a shallow `:resume` size that excludes referenced contents; sol says 8.5.3 requires
   the complete reachable graph. 8.5.3 ruling 2 also puts "collection of suspended and dropped generators" in slice S5. TRY the cheap version first: if the existing test helpers allow
   measuring reachable continuation/heap (for example a reachable-graph size via the heap-trace utilities already used by other tests, forced collection after repeated suspension with
   changing callers), add it for the nested `yield from` case. If it is NOT cheap, do not invent infrastructure: say so in your report with the reason, and add one sentence to the 8.5.3 S3/S5 text
   recording that reachable-graph retention measurement is delivered with S5. The orchestrator will take the S5-deferral question to the architects.

4. Do NOT re-mint the five content-address goldens this round. The orchestrator will rebase this worktree onto master after C3-S2 lands (it changes the prelude too), so the final re-mint
   happens ONCE then. `float-address-test` is therefore expected to fail on those five goldens until then; leave them alone and say so in your report.

5. Note only (do not act): the new delegation entries perform no recursion-limit comparison or `:base` rebasing; safepoint-s2 (not landed) owns that, and its fix round will cover
   delegation. Do not touch safepoint code.

## Lanes and rules
- FOCUSED runs only: prelude-parity-test, e2e-c2-test (`-e :slow`), the generator slow tests you touch by name (`-i :slow -n yang.python.antlr.e2e-c2-test`, EXCLUDING long-loops-test), lower-test,
  e2e-test (if touched). Lanes after this are the orchestrator's.
- kondo 0 errors; `cljstyle fix` then `check` via mise (run directly, not through a piped loop).
- Allowed files: prelude.cljc, lower.cljc, prelude_parity_test.cljc, e2e_c2_test.clj, lower_test.clj, docs/design/yang.antlr.md (one sentence at most, per item 3). Ask before anything else.

Append a 'Round 5' section to your findings file (collab/1791045188000-compiler-engineer-python-c2-s3-r2.claude-opus-5-5.findings.md): per item what you changed, the CPython evidence you
used for expected outputs, focused counts, and the reachable-measure decision. Begin the final response with Completed-GMT / Completed-Local / Coding-Agent / Session-ID as before. Do not claim
edits or runs that did not occur.
