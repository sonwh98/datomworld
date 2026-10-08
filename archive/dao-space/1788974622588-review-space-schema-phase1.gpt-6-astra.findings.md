Completed-GMT: 2026-09-09 17:25:11 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review (Phase 1 implementation) | Model: gpt-6-astra

I’ll use the code-review-pr skill to inspect the diff against Phase 1’s contract, focusing on state installation, lifecycle behavior, and the two disclosed deviations. I won’t rerun the suites or write files.

The state transition still plans everything under the same atom lock and installs state only after an `ok` receipt. Both disclosed deviations are mechanical, and the new tests retain the direct inner-close assertion and all four precedence checks. I’m finishing the phase-boundary check.

**Phase 1 is clean and ready to commit. No blocking findings or additional improvements requested.**

- **T19 is preserved.** Planning and validation remain under the same state-atom lock, followed by one inner transaction. Only `ok` triggers the atomic `reset!`; refusal or a thrown append leaves wrapper state unchanged. The map makes internals easier to access, but the old type’s atom was already reachable through field access. Neither representation protects against deliberate mutation of internals; the map follows the approved ownership model.
- **Closed precedence matches the transactor.** Empty-input validation occurs above the lock; closedness precedes remaining validation. The new test pins all four legs—empty and malformed nonempty input, through both wrapper and inner—with no host-specific skip.
- **The close test is non-vacuous.** Removing `tx/close!` would make the direct inner transaction succeed instead of returning `closed`, failing the assertion.
- **Publication follows D7.** It uses the caller-owned local stream and intake pool, which wrapper close leaves intact. Removing the guard and lock introduces no use-after-close or loss of validation serialization.
- **Both disclosed deviations are justified.** There are 26 original receipt sites: two disappear with the race test and 24 are rewritten. W45 and W50 account for the two omitted from the plan. All preserve assertion meaning. T19’s additional edit changes only its message string; its state, refusal, retry timestamp, and datom assertions retain their meaning.
- **The design edits match Phase 1.** Receipt shapes, close behavior, precedence, publication-after-close, T20 retirement, and open-item deletion agree with the code. The stale Related-documents D10 reference is explicitly assigned to Phase 2.
- **The phase boundary holds.** The source before the wrapper declaration and from `published` onward is unchanged, as is `schema_fixtures`. Thus `current`, both openers, published-reader machinery, and v1 requires remain intact. Read-side tests receive only the scheduled wrapper-close edits.

Static review only; no suites rerun and no files written.
