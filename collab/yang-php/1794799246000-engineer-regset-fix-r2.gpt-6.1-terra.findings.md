Created-GMT: 2026-11-16
Coding-Agent: codex (gpt-6.1-terra)
Branch: fix/yangphp-regset, starting at 3251ed12

## Shutdown ownership

Applied the requested `d9e8731e` ownership repair. Array literals are registered as temporaries and must also acquire a reference when assigned to a holder. Focused cleanup checks passed: finalization, p5b2-lifecycle, request-context and scope, 25 tests / 242 assertions / 0 failures / 0 errors. Log: `target/regset-r2/cleanup.log`.

Exact production diff:

```diff
@@
 (defn- fresh-value?
-  "True when expression `e` is syntactically a literal or constant, so the
-   value it answers is not another holder's (no ADDREF needed)."
+  "True when expression `e` is an uncounted literal or constant. Arrays are
+   registered as temporaries, so a holder must acquire its own reference."
   [ctx e]
   (let [ks (core/kids ctx e)]
     (and (= 1 (count ks))
          (p/rule? (first ks))
-         (contains? #{"constant" "string" "arrayCreation"} (:rule (first ks))))))
+         (contains? #{"constant" "string"} (:rule (first ks))))))
```

## Corpus

The 271-entry php-src selection and the 518-program permutation quick corpus are distinct tests. The php-src selection's 95 mismatches are a recorded known-gap set and are not themselves a failed assertion; the manifest is pinned to PHP 8.3.35. First three permutation cases (`[:flat "CC"]`, `[:flat "CU"]`, `[:flat "CW"]`) match the recorded oracle after the change; see `target/regset-r2/permutation-sample.log`. The full permutation quick test passed against the recorded oracle: 518 programs, 3 assertions, 0 failures, 0 errors (`target/regset-r2/permutation.log`). A baseline overlay using the unchanged HEAD lowerer reproduced mismatches for `[:flat "CL"]`, `[:flat "WL"]`, and `[:flat "XL"]`: the PHP record retains `&int(1)` reference markers while the engine prints `int(1)`. Each includes `$q = [&$r]; unset($r);`; the array temporary release prematurely drops the reference held by `$q`. The sole lowerer change repairs all 518 comparisons, attributing the observed mismatches to the same `d9e8731e` ownership interaction as shutdown. Baseline evidence: `target/regset-r2/permutation-baseline-probe.log` (stopped after three mismatches). The full gate php-src selection reports 271 entries: 119 pass, 57 unsupported, 95 known gaps, with no manifest assertion failure. The gate completed with its manifest assertions passing.

## Class

Skipped as directed; contract routed to architect. The gate reported exactly the expected class-model failure; it was the only failure.

## Gate and custody

The pre-existing `target/profile/sub-clj.log` was copied to `target/regset-r2/preexisting-sub-clj.log` before the gate. Full `bb test:sub:clj yang.php`: 29 namespaces, 181 tests, 5,034 assertions, 1 failure, 0 errors, 7,639 seconds; the only failure is the deferred class assertion. Log preserved as `target/regset-r2/final-sub-clj.log`. The standalone permutation run completed as reported above. The `zz-probe2` and `zz-probe3` diagnostics print informational `DIFF` lines for include and class-map scenarios; these probes assert `true` rather than their observations, and the dedicated include test reported no failure. A duplicate object-progress diagnostic was interrupted after the original gate moved on (`target/regset-r2/objects-progress.log`). The standalone php-src run was interrupted to reduce contention; the gate runs that same test. Generator was not rerun, as directed. No Dart or Node verification; seat to perform the PHP 8.3 oracle spot-check. No commits or pushes; edits remain in the worktree.
