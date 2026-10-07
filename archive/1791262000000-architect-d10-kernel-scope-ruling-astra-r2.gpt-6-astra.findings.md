Completed-GMT: 2026-10-05 23:24:46 GMT
Completed-Local: 2026-10-06 06:24:46 +0700

**Ruling: option (c)—create an explicit D10b prerequisite to D16, without blocking D11/D12 on it. Amend D10 to semantic-kernel restoration.**

The limitation predates D10. Generalizing code addressing and restoration is substantial implementation work and deserves its own slice; assigning it only to a final gate obscures that work. D10b may proceed alongside D11/D12 once their shared-file dependencies permit. D16 cannot pass until D10b lands.

**D10 landing condition for finding 3:** record this scope amendment in the slice brief and acceptance ledger, retain the three pre-existing refusal regressions as limitation tests, and name D10b as the owner of successful four-kernel restoration. Finding 3 then ceases to block D10. It does not close findings 1, 2 or 4, or establish that their corrections pass.

**D10b owns both lift and lower generalization**, including addressed code, kernel-specific continuation/register restoration, and any necessary code-profile validation. Its acceptance contract is:

1. **Successful round trips:** on JVM, Node and Dart, produce bodies from real semantic, walker, stack and register machines. Restore into fresh receivers using the supported kernel/profile pairing. Exercise version 0 and version 1; version-1 runnable roots require valid grants. Refusal assertions do not count as restoration coverage.
2. **Continuation correctness:** cover every applicable liftable safepoint row, including explicit park, ordered retained waits, install children and halted results. After restoration and controlled delivery of pending outcomes, match the uninterrupted execution’s result and observable trace. Initialization must not rerun.
3. **State fidelity:** preserve cursor positions, aliasing and distinctness, closures, module stores, carried operation IDs, exact operation counters and restored issue ordering. Receiver-local guest state must not affect resolution.
4. **Custody enforcement:** repeat the grant/protection refusal checks and gate checks across all four kernels, including zero observation calls while fenced and child gate propagation.
5. **Addressing and compatibility:** identical prepared state produces identical bytes and addresses across hosts. Preserve existing valid semantic version-0 fixtures and D9 pins. Address mismatch and unsupported code profiles refuse before restoration; no silent profile conversion.

The register constraint remains binding: **do not add boundary opcodes or change liveness/register layout to make cursor or close exportable.** Their existing deferred-observation design stays intact. Generalize addressing around the existing code format and continuation representation. If investigation demonstrates that a code-format change is necessary, obtain a separate architectural ruling with explicit stamp/version and compatibility consequences before implementing it.

D16 retains the integrated corpus and four-kernel gate; D10b supplies the missing implementation prerequisite. D11/D12 may advance on the semantic path, but stage D must not be declared complete while D10b remains outstanding.

Read-only; no files edited or suites run.