I’m applying the repository’s review checklist to the three-file Phase 1 diff. I’ll compare the old and new `snapshot-datoms` expression directly, then audit every assertion in the additive vocabulary test and the documentation surface independently of Phase 2.

The implementation is genuinely neutral: `snapshot-datoms` retains the same branch structure, validates and flattens each successful element before the next read, and only the private callee symbol changed. I’m doing a final pass on whether the new test’s individual assertions are strong enough to justify each claimed invariant.

Completed-GMT: 2026-09-09 11:18:52 GMT  
Completed-Local: 2026-09-09 18:18:52 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Routine Review — Phase 1

## Verdict

**No review findings. Phase 1 is statically approved.**

### Behaviour neutrality

The change to [snapshot-datoms](/Users/sto/workspace/datomworld/src/cljc/dao/space/index.cljc:453) is genuinely neutral:

- `ds/next` is called at the same point.
- Successful-result shape validation is unchanged.
- `element-datoms` is evaluated inside the same `recur` argument, before another read can occur.
- Malformed elements therefore throw at the same position and with the same messages.
- `blocked`, `end`, gap, and malformed-signal branches are byte-for-byte unchanged.
- Only the private callee’s name changed.

S8 remains intact: the first malformed element wins and no subsequent read occurs.

### `datoms-from-elements`

[datoms-from-elements](/Users/sto/workspace/datomworld/src/cljc/dao/space/index.cljc:442) is correct:

- `into` eagerly realizes the complete transformation.
- `mapcat element-datoms` preserves element and transaction-datom order.
- The empty input yields an empty vector.
- Validation and diagnostics are inherited from the same private function used by `snapshot-datoms`.
- Its docstring accurately describes its accepted vocabulary, eager flattening, validation, and result.
- Having no production caller is intentional and is clearly recorded in the design.

### Test quality

The new test in [index_test.cljc](/Users/sto/workspace/datomworld/test/dao/space/index_test.cljc:226) makes substantive assertions:

- S2: empty, bare-datom, transaction-record, mixed-element, and ordering results are compared exactly.
- S3: transaction-map shape, non-empty vector requirement, canonical datoms, matching transaction time, non-negative time, and rejection of extra fields are exercised.
- S6: malformed records, malformed d5 vectors, and non-elements must throw.
- S7: each malformed category is matched against its distinct diagnostic.
- The equivalence assertion is a useful behavioral parity sentinel for a mixed sequence. It cannot alone prove shared implementation, but static inspection confirms both paths call `element-datoms`; the unchanged snapshot tests provide the other half of that evidence.

The new test does not independently prove S8, nor does it claim to. S8 is established here by the unchanged loop structure and unchanged existing tests.

### Phase independence

Nothing depends on Phase 2:

- The private rename is complete.
- The new public function compiles and operates independently.
- Existing v1 snapshot behavior and documentation remain truthful.
- The new public surface is documented in this phase.
- No stale `stream-payload-datoms` reference remains.
- `git diff --check` is clean.

The automated TODO warning is a false positive on the data keyword `:todo`.

**Phase 1 is ready to commit once the owner’s still-running CLJD lane completes successfully.** No code or documentation correction is required.
