Created-GMT: 2026-09-14 17:37:38 GMT
Created-Local: 2026-09-15 00:37:38 +07
Coding-Agent: claude
Session-ID: 8677b374-bb75-4103-afab-4b65727a76c1 (resume — your own drafting session for docs/design/yin.vm.tuples.md)

# Task: Revise the tuple code-representation design (round 4)

Role: Architect — design document only. No implementation, no staging, no commits.

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-15 00:37:38 +07 | Status: active | Rationale: you authored all revisions; the reviewer is gpt-6-astra (different family), independence preserved.

## Context

Your round-3 revision was re-reviewed (verdict REJECT, 6 P1s) — 4 of your 8 round-2 resolutions were accepted (key domain, store-update condition, encoder withdrawal, lowering profile; the mnemonic normalization itself was verified correct). The full findings are at:

`collab/1789406986000-architect-tuples-rereview-r3.gpt-6-astra.findings.md`

Read that file first. The orchestrator re-verified the reviewer's executed claims and they hold: `apply-tempid-map` (`transact.cljc:168-185`) resolves a tempid in `:v` only when the whole value is one on a declared reference attribute; `apply-call` (`semantic.cljc:199-205`) binds parameters by `(zipmap params args)` with no arity check, so an under-arity call leaves parameters unbound and resolution falls through env→store→primitives→modules; UCF §7.6.2 excludes the FFI pair from the store slice.

## Task

Revise `docs/design/yin.vm.tuples.md` against all six findings. The seven owner rulings stand. Direction per finding — the reviewer's fix text is binding unless you can overrule it with better reasoning (two lines, stated):

1. **Batch member index in occurrence keys (§2.5, §8.5):** include the batch member index (or a stable admission-catalogue occurrence id) in every source occurrence key, carried uniformly through declarations, source positions, instruction provenance (§5.3), and initial expansion events. Two identical trees at different batch indices must be distinct occurrences everywhere, not just in declarations.
2. **Embedded event id is not a transactor-resolved reference (§8.4):** the local parent link becomes a separate declared reference attribute (whole-value tempid on a reference attribute — what `apply-tempid-map` actually resolves), with path and content address as ordinary values; the portable occurrence identity uses a qualified log/event identity or an addressed event identity, never a bare log-local number inside an addressed record. If embedded coordinates are materialized after allocation, specify that staging and hash the record only after resolution.
3. **Preorder ≠ admitted batch order (§8.5):** carry an explicit ordered admission/harvest catalogue derived from original batch order, referencing definition occurrences, and use that order for initial harvest — or explicitly amend and narrow the accepted legacy batch contract, saying so plainly. Keep post-expansion declaration order separate (the macro contract already distinguishes it from traversal order, `yin.vm.macro.md:298-304`). Do not claim exact preservation of a contract you are not preserving.
4. **Static parameter discharge assumes exact arity (§7.7.2):** discharge a parameter obligation only when call analysis establishes the binding actually exists — `zipmap` under-arity calls leave parameters unbound and resolution falls through to captured env, store, primitives, modules. Account for captured bindings and fallback resolution when arguments are missing; unknown call contexts retain requirements or yield `:incomplete`. Do not introduce mandatory exact arity (that would be an execution-contract amendment outside this design).
5. **Address-only convergence (§7.7.3):** define convergence over all dependency facts and obligations — code/context pairs, discovered values, store requirements, callable/module footprints — not over addresses alone. Two closures sharing one segment address with different captured environments are two analysis work items. Fetch and validate code once per address; analyze each newly discovered relevant context. If no finite conservative context abstraction is available, report `:incomplete` rather than terminating successfully on address stability. State that UCF §7.6.1 carries the same defect and needs this same amendment.
6. **FFI footprint vs UCF's slice exclusion (§7.7.1):** record required FFI operations and receiver-local pair availability as a capability requirement, separate from carried store keys. Preserve UCF §7.6.2's exclusion of the pair from the slice and §7.4.3's outstanding-call routing. The five stream mnemonic→effect mappings stay as verified.

Also bring §10's wording for occurrence identity, batch preservation, and conservative completion in line with whatever you specify — they are design work with stated mechanisms, not ready-to-implement items, until these six are closed.

## Constraints

- Edit only `docs/design/yin.vm.tuples.md`. Stage and commit nothing.
- Rules stated as rules; calls made and stated; no option menus. Cite file:line for every load-bearing claim about current code; re-verify before citing.
- The owner has directed the orchestrator to iterate author↔reviewer rounds until agreement without further owner input: when you believe a finding cannot be fixed without an owner ruling, say that explicitly in the document and your report rather than inventing a ruling — such an item is a legitimate blocker, not a failure.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and named timezone>
Coding-Agent: claude
Session-ID: 8677b374-bb75-4103-afab-4b65727a76c1

Then: the new line count; a finding-by-finding table (1–6: fixed how / overruled why); sections touched; anything verified against source versus taken from the review.
