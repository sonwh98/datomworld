# D1 continuation r7: per-fixture persisted static-gate pilot

Status: **incomplete; no corpus D1 record, companion, or verification result exists**. This continues the append-only r1–r6 findings. Worktree mirror only; not promoted to canonical collab. No commit, tagged run, verifier-result publication, or reliance claim.

## Implemented

`yang.correspondence-verifier/check-fixture!` checks a supplied compilation's source hash and site declarations independently, reconstructs its bindings, and compares the complete source-tree C1 marker set to application occurrences. Missing/extra/mutated declarations, bindings, hashes, and malformed or duplicate metadata refuse; failures in the hash, declaration, and binding implementations are annotated with fixture and stage. This is a **static gate**, not verification of admission authenticity, image derivations, trace completeness, or D1 reliance.

`fetch-tree!` fetches and validates the complete addressed row closure with an explicit row limit, rejecting missing/malformed rows and limit exceedance. A first attempt to store a nested AST snapshot failed honestly because `dao.jing.cbor` refuses nesting deeper than 128. The pilot now materializes pre-stamp and stamped trees as individually addressed rows and reconstructs them after reopening, rather than changing Jing's contract or raising that limit.

The temporary-store harness compiles each fixture under a fresh recorded admission, checks the independent reconstructed bindings against cc3's producer binding, materializes the **whole** v1 evidence, captured inputs and profile, both row closures, C1 metadata and reconstructed bindings, then closes and reopens the store. From fetched inputs it recompiles and checks the retained root; from fetched row closures and the frozen fixture's declarations/hash it runs the independent static gate. It mutates a declaration for the fixture and requires a `:site-declarations` refusal. This stores no second durable oracle table: it reads the fixture declaration from the fetched complete evidence map.

Observed per-fixture outcomes:

- `:beta-eq-false` — static pilot passed after close/reopen; its declaration mutation was refused. Focused test: 1 test / 4 assertions.
- `:minimal-last` — static pilot passed after close/reopen; its declaration mutation was refused. Focused test: 1 test / 3 assertions.
- Other **153 fixtures were not run** in this round. The per-fixture status enumeration is `1794422563000-engineer-d1-correspondence-r7.outcomes.edn`; `:not-run` is not a pass or a refusal.

The tests use separate fresh admissions and temporary file stores, which they delete. Their static passes do not establish that these admissions or their dependencies have been durably published for a relying consumer, and neither pass licenses another fixture. The unchanged v1 EDN has 155 fixture entries. Its byte SHA-256 remains `e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75`; the Jing address of the complete parsed evidence is `:segment/blake3-e4094211c705022e962908b6176cb26b1dca6112af4345addc7acf56b21baee1`.

## Retention location proposal and unresolved dependencies

Proposed eventual repository artifact: `test/yang/latte_oracle/slice9_correspondence_store/content.jing`, addressed from the companion and accompanied by an explicit closure/retention manifest. Archive the complete content log and its real recorded admission coordinates; tests copy it to a scratch directory before opening so lock/HEAD mutations do not alter the checked-in artifact. This offers a stable workspace-relative discovery location and permits reopen without inventing admissions. It is a **proposal only**: no store, manifest, retention owner or companion was created. Before choosing it for a full corpus, measure its size and agree the retention/distribution owner; a target directory deleted by a test is not corpus retention.

The complete versioned recipe with executable compiler/profile/dependency and expansion-attempt closure is still not pinned or retained. The required instruction-provenance side records and image derivation chains for these particular admitted oracle trees have not been produced or independently verified; the landed linker publisher is not, by itself, proof that its publication origin and this harness's `[:source :program ...]` admission are interchangeable. Fixture-to-admission scoping, the aggregate record's exact representation, artifact inventory and durable discovery still need completion. The output of `check-fixture!` cannot stand in for these. Do not create a placeholder D1 record or manufacture origin/expansion information to fill the gaps. The row-lane-only restriction remains.

## Verification

Red first: the per-fixture gate test initially failed because the gate did not exist; row-closure fetch and replay-root tests initially failed on missing functions; the first real persisted fixture test failed because its constructor was absent, then exposed the 128-level nested snapshot refusal. Malformed/duplicate marker tests and malformed-stamp stage diagnostics each exposed a missing refusal and were fixed. The focused verifier namespace passed 7 tests / 35 assertions. The standing correspondence, text-profile, independent-verifier and call-site battery passed 56 tests / 694 assertions. clj-kondo 2026.08.04 reported 0 errors and 0 warnings across all touched code/test files. Full oracle suite and all tagged runs were not run.
