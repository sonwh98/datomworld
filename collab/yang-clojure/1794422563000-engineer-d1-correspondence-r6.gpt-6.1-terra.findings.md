# D1 continuation r6: independent static reconstruction pilot

Status: **partial; not verified and not authorized for reliance**. Continues r1–r5 findings, in particular the limitations in `1794422563000-engineer-d1-correspondence-r5.gpt-6.1-terra.findings.md`. Worktree mirror only, no canonical-collab promotion or commit. No tagged run was performed.

## Independent implementation and observed pilot

New namespace `yang.correspondence-verifier` independently interprets post-port stamped AST argument expressions and their lexical bindings to derive `:site`, `:callee`, `:arg-shapes`, `:def` and `:delegation?`; it does not invoke the oracle's stamping, classification or declaration helpers. The context constructor names are obtained from the supplied context forms. Its source-hash implementation separately applies the first-occurrence generated-symbol normalization and SHA-256 rule to supplied source inputs. Its independent paired-tree traversal translates the two inserted stamp applications and reconstructs site-to-persisted-occurrence bindings from the source tree and supplied admission origin; it does not call `cc3-oracle-binding` or `cc3-correspond`.

For the r5 pilot fixture `:beta-eq-false`, a fresh retained-coordinate static compilation was compared against the independent implementation. The source hash, all site declarations and the full binding map agreed with the producer; no substantive disagreement occurred. The real-fixture test also mutates one producer site's argument classification and observes `:verifier/site-declarations` refusal. This is one fixture; agreement does not extend to any other fixture. The test holds the producer's stamped tree in memory, not an independently fetched complete D1 record.

A separate pure `attributed?` query takes a pre-scoped fixture table, bindings, origin/root and two lane records. Synthetic tests require both lanes to report complete, reject missing lane events, wrong ambient shape, self-delegation, missing bindings, mismatched admission and mismatched root. These tests do not establish that a real lane's data is complete or validate the provenance of supplied inputs; the query's own documentation explicitly disclaims reliance. No real two-lane trace or record was used in this round.

## Remaining before verification publication

There is no complete persisted corpus D1 record, pinned executable profile/dependency/expansion closure, retrieval/discovery owner, or retained bounded complete two-lane trace set. Therefore the verifier has not fetched and checked the entire D1 closure, validated each image under its own execution and derivation contracts, reconstructed instruction-provenance mappings and multiplicities, run the query over actual record data, or published a separate verification result. A synthetic query success cannot authorize reliance. The namespace is a static reconstruction component, not the complete seven-step verifier. Other fixtures, indirect-call cases and malformed evidence still need full gates and adversarial coverage.

The frozen v1 table remains SHA-256 `e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75`. D1 continues to claim only the row lane.

## Validation

Red first: the mutated-declaration and source-hash tests initially failed because the independent namespace/functions did not exist. The synthetic binding test likewise first failed on its missing reconstruction function. Query tests first failed on the missing query; subsequent cross-admission and cross-root tests each exposed a missing scope check and were green only after it was added. The independent verifier-versus-producer pilot passed 1 test / 6 assertions. The standing combined test battery passed 53 tests / 674 assertions before the final root-scoping refinement; the final focused verifier test passed 4 tests / 16 assertions after that refinement. clj-kondo 2026.08.04 reported no errors or warnings. Full oracle suite, all tagged lanes, and the bounded verification run were not run.
