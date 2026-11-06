**Formal verdict: H3-6 audit PASSED for the targeted corpus. Both P1 and P2 are resolved.**

- **Hash determinism:** All 155 fixture hashes matched both evidence files across fresh reads and two independent JVMs, including one with 10,007 prior reader operations.
- **Case 6:** Requires a dynamic site executed in both lanes and asserts refusal unconditionally. A mutation permitting dynamic-site attribution caused that assertion to fail.
- **Evidence closure:** Independently reconstructed **165/165 credited arities** and all **563 unique witness paths**. **56,994 dynamic-site probes** produced **zero cross-arity borrowing**.
- **Site integrity:** All **20,805 site rows** matched fresh compilation; stale-site diagnostics reproduced exactly.
- **Focused tests:** Four tests, 65 assertions, zero failures or errors.

The checked-in regression tests exercise fresh reads within one JVM; cross-process reproducibility was verified separately during this audit.

No repository files were edited.