I’ll verify the byte grammar and re-emission check, then confirm the envelope, gate, and digest fixes against the source contracts. This remains read-only.

Completed-GMT: 2026-10-09 17:45:07 GMT
Completed-Local: 2026-10-10 00:45:07 Asia/Bangkok

Reviewed `9d6c1cec9ae090eb227bf5bdf152516b00aac625`, the requested diff and sections, and the existing envelope handling. No files edited.

The envelope, gate, digest, and re-emission corrections pass. One substantive omission remains in the numbering grammar.

**HIGH — canonicalization specification gap | docs/design/yin.vm.ucf-transport-tuples.md:472 | CE does not define bare cell identities.** It replaces `[:cell C]`, but pending rows carry bare `cell-C` and `response-cell-C` slots, and the `:cells` census has bare C keys. The structural-datum list deliberately enumerates other kinds but omits C. Thus CE is undefined for ordinary blocked reads and census entries. Treating these identities “as themselves” would instead leak incoming names into signatures and violate renaming invariance. | Define CE for **every identity-bearing position**, including pending slots and census keys, using the current class label rather than the incoming C. Specify whether census declaration occurrences contribute to reference-position multisets; either choice must be explicit and consistent.

**MEDIUM — byte-contract gap | docs/design/yin.vm.ucf-transport-tuples.md:489 | Path construction still allows more than one spelling.** The grammar lists permissible steps but does not specify precisely when a sorted-row element step replaces an ordinary slot-index step, or how keyed-row pair steps compose with enclosing slot steps. Different implementations could produce different position bytes while following the listed vocabulary. | Define path construction per slot category: fixed slots, ordered repeated entries, unordered elements, keyed pairs, waits, and task boundaries. Unordered-entry paths must never include source enumeration indices. Pin search depth’s initial value and specify individualization labels explicitly as CBOR byte strings.

Properties that passed:

- Complete `envelope-V` preserves ordinary arguments, unknown keys, and recursively encoded guest values. It matches the existing handoff behavior, subject to the declared carrier refusals.
- The active-path gate now permits shared children consistently with §3. Separate native restoration per install entry remains correct.
- Explicit SHA-256 row hashing is consistent throughout; code addresses retain their governing function. The digest choice is correctly labelled a proposal.
- Synthetic sorted vectors retain multiplicity after class substitution; full value-union tags preserve literal/reference distinctions.
- The monotone partition bound and exhaustive search claims no longer overstate refinement.
- Root **body** bytes are now an unambiguous leaf key. Equal keys imply equal complete commitments and emitted rows under the digest assumption.
- Re-emission/root-id comparison correctly accepts equivalent automorphic labelings. It is consistent with §9’s fresh-receiver round-trip context once the procedure’s remaining inputs are fixed.
- Numbering children first and treating their canonical root ids as opaque parent inputs respects task-local cells. This must run bottom-up during step 6, including when the parent has zero cells; it requires no native restoration.
- Validation establishes reference completeness and acyclicity before recursive checks, preserves contextual checks, and delays attachment/restoration until validation succeeds.
- The proposal list and the six-invariant architectural boundary remain sound.

The structure of step 12 is now appropriate. A universal safe-lowering claim still depends on a reproducible canonicality check, so findings above must be resolved first. I found no further architectural conflict in the requested corrections.

VERDICT: CHANGES_REQUESTED — define bare-C class encoding and exact path construction, then recheck the resulting canonical byte procedure; merge publishes a Draft.
