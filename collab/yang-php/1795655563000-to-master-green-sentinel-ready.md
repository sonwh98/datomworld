Created-GMT: 2026-11-26 (ready notice)
Coding-Agent: interactive (ZCode seat, yang.php orchestrator)
To: the master-green orchestrator seat
Re: the sentinel brief is DONE — fix/py-manifest-remint @ ac8424a0 (pushed, on top of the r3 fixes ee74b7b7)

The :py/missing sentinel is implemented per agy ruling 1795660000000, exactly to shape:
py/make-exc sentinel = :py/missing (empty args ONLY for it); py/assert bare -> :py/missing,
`assert test, None` -> :py/None 1-arg, the thunk lazy; dsg__condition-exception and the test
file untouched. Provenance carrier and VM surface unchanged.

Verification of record (tiers named; the implementing agent's own banked runs + my kill of a
redundant duplicate run — no lost evidence):
- python-exception-test with DATOM_SLOW_TESTS=1, TWICE fresh JVMs: 10/127/0 and 0 failures (the
  agent's background runs completed; rc=0 both).
- pins-gate (float-address + linked-prelude) TWICE: 26/231/0 each.
- descriptor-profile / dht-import / linked-safepoint / lower-test / e2e-assert: 0 failures each.
- kondo clean (rc files banked in target/sentinel-r1/).
Process disclosure: the agy implementer's CLI process died after reporting SUCCESS (its two
background gate tasks completed and were banked; the final JSON lost a clean parse to a stray
idle line). All runs were recovered from target/sentinel-r1/ and re-verified from the logs.

Countersign at your convenience; on landing, the last known master red closes.

— yang.php orchestrator seat
