I’ll check the revised numbering procedure for determinism and invariance under cell renaming, then verify shared-child restoration, FFI envelope coverage, and the reordered validation steps. This remains a read-only review.

Completed-GMT: 2026-10-09 17:40:58 GMT
Completed-Local: 2026-10-10 00:40:58 Asia/Bangkok

Reviewed `7e24fa3a7de016df2b4dcd00a9f74e30b1cf6cf8`, the requested diff and sections, plus the relevant envelope and addressing code. No files edited.

The revised numbering approach can define a canonical representation without claiming an unrestricted minimum. Its exact procedure still needs specification, and the FFI envelope and gate contain concrete remaining conflicts.

1. **HIGH — schema conflict | docs/design/yin.vm.ucf-transport-tuples.md:325 | Ordinary FFI arguments have no valid representation.** A retained request with arguments `[1 2]` must use a `:vec` row here, but §4 and validation step 5 forbid reference-free collection rows. Only empty arguments have a literal alternative. | Make the argument slot a `V` restricted to vectors: `[:lit [1 2]]` for reference-free arguments and `[:row B]` targeting `:vec` otherwise. This also removes the special empty-vector exception.

2. **HIGH — pending-state completeness | docs/design/yin.vm.ucf-transport-tuples.md:321 | Three fields cannot reconstruct every retained request verbatim.** `dao.stream.apply/request?` explicitly permits unknown envelope keys (`src/cljc/dao/stream/apply.cljc:38`). The existing handoff encoder encodes the complete retained envelope, and lower decodes it. A closed call-id/op/args row drops additional keys and envelope metadata. | Preserve the complete recursively encoded envelope, or add an explicit extension/metadata representation to the closed row. Do not silently narrow an open request protocol while claiming unchanged pending semantics.

3. **HIGH — contradictory validation rule | docs/design/yin.vm.ucf-transport-tuples.md:536 | Step 1 still rejects every revisit.** §3 correctly permits off-path shared children, but the binding validation sequence still says “with a visited set” and “a revisit … stops the gate.” Thus the round-3 shared-child correction is only partially applied. | Replace this wording with the active-path cycle rule from §3. Cache completed content validation separately; retain contextual validation and restoration per install occurrence.

4. **MEDIUM — canonicalization specification gap | docs/design/yin.vm.ucf-transport-tuples.md:455 | Not every input to the procedure is fixed.** Class encoding lacks a concrete structural grammar. Maps need an exact entry encoding and ordering rule; class substitution can make distinct keys/elements equal, so multiplicities must survive without constructing collapsing host maps/sets. Paths need explicit slot and key/value discriminants. “Fresh singleton class ordered before all others” does not specify its encoding or how that ordering survives bytewise signature ordering. “Root’s Merkle bytes” also leaves root-body bytes versus digest bytes ambiguous. | Publish exact encodings for classes, signatures, paths, individualization markers, and leaf keys. Use synthetic ordered structures that preserve repeated class encodings. Specify task-boundary handling and zero-cell behavior. With those choices fixed invariantly, exhaustive branching and minimum leaf selection establish canonical output; the present prose is not yet a reproducible byte contract.

5. **MEDIUM — canonicality-check ambiguity | docs/design/yin.vm.ucf-transport-tuples.md:489 | Equal commitments do not select a unique source-cell labeling.** Automorphic leaves may assign different numbers to individual input cells while producing exactly the same emitted rows. That makes equal leaf keys immaterial for the transported representation, but not for a check that literally compares “the carried numbering” with one arbitrarily selected labeling. | Define the output as the canonical rooted encoding. Validate by rebuilding and comparing canonical rows/root commitment, rather than comparing one source-cell-to-number bijection. Alternatively, accept every labeling yielding the selected commitment. No encounter-order tie-break should decide validity.

6. **MEDIUM — addressing/provenance | docs/design/yin.vm.ucf-transport-tuples.md:116 | The SHA-256 rationale and API expression disagree with current code.** `ucf/code-address` calls one-argument `jing/segment-key`, whose current default is BLAKE3. Therefore SHA-256 is not necessarily “the same digest” as semantic code, and the displayed one-argument row-address expression does not implement the new pin. | Spell row hashing as `segment-key body {:algorithm :sha256}`. Keep existing code addresses under their governing address function. Remove the claimed digest equivalence and add the carrier digest selection to §10’s proposed choices.

Properties that passed:

- The procedure no longer claims unrestricted minimization or equates refinement classes with automorphism orbits.
- Monotone refinement supports the stated `n−k` splitting-round bound plus a stability check. This correctly bounds partitions, not processing or search work.
- Exhaustive branching avoids reliance on unresolved cells being interchangeable.
- Equal leaf commitments make ties immaterial **for the emitted state**, under the digest assumption and complete census commitment.
- Hash-free structural expansion and full value-union tags remove dependence on incoming cell numbers and cell-dependent row hashes, provided finding 4 fixes their exact encoding.
- §3 and restoration step 12 correctly separate shared immutable child content from separate native task instances.
- Emitted-row ordering now has one rule; skeletons are confined to refinement.
- Metadata restrictions are explicit and correctly identified as proposed carrier limitations.
- Reference completeness and acyclicity now precede recursive canonical-form checks. Set-element uniqueness and receiver work bounds are included.
- The census, disjoint value union, task-local resources, isolated restoration, custody checks, and transport/native-execution boundary continue to satisfy the intended six-invariant architecture.

The validation phase order is now appropriate. I cannot certify step 12 for **every accepted input** until the executable schema and canonicality checks are made consistent, particularly findings 1, 4, and 5.

§10’s proposal list is otherwise adequate. Add the digest pin; any decision to reject or discard previously permitted FFI envelope extensions would also be a new protocol decision, not an inherited carrier requirement. Preserving those fields needs no such semantic change.

VERDICT: CHANGES_REQUESTED — fix findings 1–3 before publication; specify 4–5 before claiming canonicality; reconcile 6 and the proposal list. Merge publishes a Draft.
