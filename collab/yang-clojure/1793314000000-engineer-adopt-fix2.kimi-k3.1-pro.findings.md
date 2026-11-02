Completed-GMT: 2026-10-30 14:59:56 GMT  
  Completed-Local: 2026-10-30 21:59:56 +07

  Implemented and verified all five fix-round items:

  - Generated entry/bridge code uses private `dsg__` aliases, including shadowed-helper and raise-path coverage.
  - Source-resolved calls require admitted descriptors; scalars, ordinary maps, malformed descriptors, and foreign values return `:yang.call/not-callable`.
  - Constructor/profile descriptors use closure-captured admission witnesses; copied or `assoc`-forged descriptors remain data.
  - Named and mutual tail calls maintain constant stack depth on walker and semantic lanes at 100/1000 iterations.
  - Invalid continuation captures produce an explicit `:yang.call/invalid-invocation` failure.

  Retained the prior eligibility work: profile admission now gates `yang.call/eligible?`, with true/false coverage on both execution lanes. The arbitration ledger dispositions are present in `docs/agents/known-gaps.md:180`.

  Verification:

  - Requested four namespaces: **127 tests, 1273 assertions, 0 failures/errors**.
  - Accumulated LaTTe oracle: **21 tests, 971 assertions, 0 failures/errors**.
  - Kondo on changed files: **0 errors, 0 warnings**.
  - Including the oracle test: 0 errors and only its two pre-existing warnings.
  - Formatting and `git diff --check`: passed.
  - No commit created.

To resume this session: kimi -r session_de70ca2a-6838-42fe-bcb1-46e260490c3d
