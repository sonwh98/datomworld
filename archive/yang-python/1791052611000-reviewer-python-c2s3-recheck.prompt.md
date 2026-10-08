Created-GMT: 2026-10-03 18:36:51 GMT
Created-Local: 2026-10-04 01:36:51 +07 (+0700)
Coding-Agent: glm (resume 6b1675cd-907b-43df-9de6-8abdaf7904dc) and codex (gpt-6.1-sol, resume 01a102d7-1b4b-7d43-b88d-ee77a8329844)
Session-ID: 6b1675cd-907b-43df-9de6-8abdaf7904dc (glm); 01a102d7-1b4b-7d43-b88d-ee77a8329844 (sol)

# Task: Python C2-S3 gate re-check (after round 5, on the final base)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-10-04 01:37 +07 | Status: active | Rationale: resume of its static gate to confirm the fixes
- Model: gpt-6.1-sol | Assigned: 2026-10-04 01:37 +07 | Status: active | Rationale: resume of its gate thread to confirm the fixes

Resume your C2-S3 gate for the CURRENT tree in /Users/sto/workspace/datomworld-py-c2gen3 (branch yang-python-c2-s3), now REBASED onto master be1f8d06, which contains the landed
range fast path and the landed C3-S2 numeric dict/set keys. The change is STAGED: use `git diff --cached` (6 files).

The orchestrator independently checked your findings:
- sol P2 #1 (a closed delegate answers a thrown `StopIteration(7)` with a raise, which became PEP 479's RuntimeError; CPython 3.9.6 consumes it as delegation completion with value 7): AGREE,
  verified against CPython's `_gen_throw`/`_PyGen_FetchStopIterationValue`; the engineer added `py/stop-as-return` (a delegate's raised StopIteration incl. subclasses -> completion with its
  value; a StopIteration escaping the delegate's own body still RuntimeError) with a four-VM e2e test and a parity form. glm did not report this: please re-check PEP 380 `throw` delegation
  with fresh eyes.
- sol P2 #2 (dict iterator invalidation must be sticky after the first size-change error): AGREE; `py/iter-step` now marks the iterator invalid before raising.
- sol P2 #3 (tests): AGREE in part; the engineer added a depth>=3 chain resumed by different callers, a throw through three levels, yields inside `finally`/`except` during unwinding (parity form
  plus two e2e tests, CPython 3.9.6 expected outputs), and a JVM-only `^:slow` `reachable-heap-is-stable-under-delegation-test` that force-collects each VM's final state, measures reachable cells
  and their full content with continuations followed, and asserts equality after 10 vs 1000 items with alternating callers (a retaining program changes the measure, so it can fail). Judge it.
- glm P3 (size-based dict invalidation vs CPython's version tag): out of scope, unchanged.
- The depth/limit gap (`py/gen-switch` performs no limit comparison; the delegation entries inherit it): NOT in this slice; safepoint-s2 (landing after this) owns it and its fix round covers
  delegation entries because they funnel through `py/gen-switch`.
- Five prelude-derived content-address goldens re-minted ONCE on this final base (C3-S2's dict-keys golden and the hook-prelude golden unchanged).

Verify, with file:line evidence:
1. `py/stop-as-return` and the delegate `throw` path: exactly the cases above, no change to the cases glm confirmed conforming (send forwarding, close ordering, already-executing, PEP 479 on the body,
   non-generator delegates). Any case where it now swallows a StopIteration it must not (a StopIteration raised BY the outer's own handler code, a StopIteration thrown into a sequence delegate)?
2. Sticky invalidation: invalid state persists after the size is restored, for dicts and sets, and does not break the first legitimate exhaustion or an unrelated iterator over the same dict.
3. The new tests: can each fail, are expected values CPython 3.9.6's (hand-compute two), is the heap-measure test's claim honest about what it measures (final state reachable from the suspended
   generator, not mid-run), is `^:slow` on it and on `yield-from-long-range-test`, and do all master `^:slow` tags remain.
4. The merge: the `:require` conflict in e2e_c2_test.clj resolved with both `yin.vm.integer` and `yin.vm.engine`; goldens only the five prelude-derived lines changed in float_address_test.cljc
   relative to master; no conflict markers, no duplicate definitions (C3-S2's `py/key`, `py/hash`, `py/int-canon` intact and untouched by this diff).
5. Cross-host portability of the new code and parity forms (:cljd first in any conditional, no unary float minus, no keyword/identity pitfalls, no quoted-vector-literal evaluation mistakes: prelude
   vector literals are data, element forms are not evaluated).

Re-read only relevant design, source and test lines. Challenge these conclusions. Do not repeat resolved findings unless the fix is incomplete. Do not edit. Do NOT run suites (the orchestrator is
running the full JVM, Node and Dart lanes on this exact tree now).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Return: finding | final disposition | evidence | remaining action. Report new defects as P0-P3 | file:line | evidence | concrete fix.
Explicitly state whether the change is ready to commit.
