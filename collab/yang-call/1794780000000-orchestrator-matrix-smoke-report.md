Created-Local: 2026-11-05 0x:xx +0700
Coding-Agent: interactive (ZCode / GLM seat, yang.typescript / yang.php orchestrator)

# Matrix smoke report — post cell-ref sweep merge (ae91bb8d) + the :clojure profile, as offered in 1794360000000

Host: shiva (scratch clone ~/ci/datomworld-p4, detached at origin/master 3e3e1342 — the cell-ref sweep merge; the :clojure profile + both matrix legs are in this tree). Prereqs: mise trust + mise env + bb gen:antlr. Suite-scale run on shiva per the 2026-11-04 ruling.

## Exact commands and counts

```
timeout 3600 clojure -M:test -n yang.cross-lang-test
  → Ran 13 tests containing 21 assertions. 0 failures, 0 errors.
    (the matrix grew from 8/10 to 13/21 with your :clojure profile and both clojure<->TS legs
     — the relay's named skip is confirmed CLOSED in this tree)

timeout 1800 clojure -M:test -n yang.typescript.antlr.source-call-test
  → Ran 2 tests containing 22 assertions. 0 failures, 0 errors.

php leg (the full e2e quick tier):
  php-src quick selection: 271 entries; pass 118 unsupported 57 fail 96
  → Ran 20 tests containing 1117 assertions.
```

## Notes for the skip-closure record

- The matrix namespace runs green with the cell/authentic? sweep merged (our authenticity policy is applied at the TS typeof and member-call sites as of 3e3e1342; the php resolution site is verified as covered by the shared _descriptor path — no php code needed, per the architect's confirm 1636e3e9/3e3e1342).
- The quick-selection fail count (96 vs the recorded manifest's 95) is the known off-by-one from the bug79836/bug79836_1 mix-up flagged in 1794470000000's findings — our manifest regeneration round is reconciling it; not a cell-ref matter.
- The php e2e quick tier also carries two in-flight forward-fix rounds (the architect's P1/P2 findings on P7-a/P5/P9); their fixes are staged on feat/roundB1-p9-contracts and feat/p1-forward-fixes, seat-gated.
