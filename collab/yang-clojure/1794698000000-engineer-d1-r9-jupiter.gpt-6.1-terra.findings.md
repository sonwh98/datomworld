# D1 r9 Jupiter: incomplete preflight

Status: **incomplete; no bounded tagged run, corpus correspondence record, or verification result**. No declaration is finalized and no reliance is authorized. No commit was made.

## Findings and refusal stage

- Host `jupiter.stigmergy.systems`, Linux x86-64, OpenJDK 21.0.2, worktree base `747e80fce0428f6971299819b1623b99e01f4bb0`. The frozen site-table SHA-256 is `e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75`.
- **Preflight / retained inputs:** the r8 findings explicitly say the measurement store was deleted. No `target/d1-*` store was present here. The r8 outcome entries retain measurements and admission origins, but the batch driver discards the input and snapshot addresses returned internally by the per-fixture persistence operation. Thus its outcomes do not provide the persisted input closure or a corpus record for the requested run. Recreating it requires fresh admissions and recording the addresses; the historical origins must not be fabricated or substituted.
- **Preflight / bounds:** the draft's heap and lane quantities are undeclared. Design §6.3 also requires trace bytes and occupancy, row/occurrence/path bounds, phase time and peak memory, and baseline/added-work evidence. This session did not measure these quantities on Jupiter. The laptop ceilings and historical 4M/1M limits are not Jupiter measurements. Declaring the missing values from those figures would misrepresent their basis. No tagged lane was executed and there is no exceedance or passing verdict.
- **Verification / publication:** `check-fixture!` explicitly does not establish admission authenticity, image derivations, trace completeness, durable retrieval or reliance. `attributed?` assumes already-scoped complete lane inputs; it does not validate them. The existing record and recipe functions are structural storage preconditions, not a complete seven-step verifier. The r7/r8 findings also identify the unpinned executable recipe/dependency and expansion closure and missing image/provenance derivation checks. A result record claiming verification, or a companion purporting to name the absent corpus record, would therefore be unsupported. Neither was published. Retention location and ownership remain the owner's pending decision; no 700 MiB store was committed.

## Validation

On Jupiter, `test-d1-persisted-per-fixture-static-gate` passed 1 test / 4 assertions. This is one temporary-store static pilot, not a corpus or lane measurement. The standing `yang.correspondence-test`, `yang.correspondence-text-test`, `yang.correspondence-verifier-test`, `yang.correspondence-batch-test`, and `yang.clojure-callsite-test` battery passed 57 tests / 700 assertions, zero failures and errors. No code changed, so no new kondo run was made. The full oracle suite was not run.

## Remaining work

Rebuild and retain the corpus under fresh captured coordinates while recording all addresses; complete and validate its recipe and record closure; implement red-first bounded measurement and trace-completeness checks, measure the static and lane quantities on this host, then publish a finite pre-run declaration. Only after that declaration may the independent verifier execute both lanes under the fixed ceilings. Publish the separate result and companion only when their referenced records and outcomes actually exist. Preserve any exceedance as a failed run.
