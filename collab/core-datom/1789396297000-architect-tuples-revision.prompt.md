Created-GMT: 2026-09-14 14:31:37 GMT
Created-Local: 2026-09-14 21:31:37 +07
Coding-Agent: claude
Session-ID: 8677b374-bb75-4103-afab-4b65727a76c1 (resume — your own drafting session for docs/design/yin.vm.tuples.md)

# Task: Revise the tuple code-representation design against the review

Role: Architect — design document only. No implementation, no staging, no commits.

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-14 21:31:37 +07 | Status: active | Rationale: you authored the draft; the reviewer is gpt-6-astra (different family), independence preserved.

## Context

Your draft `docs/design/yin.vm.tuples.md` (780 lines) was reviewed by gpt-6-astra on its own thread. **Verdict: REJECT, 12 P1s** — but convergent: "the tuple representation is viable", "the draft's central tuple decision remains sound", the grammar inventory, both boundary calls, the addressing grain, the query mechanics, and the migration counts were all verified sound. Every finding is an integration-protocol correction, not a challenge to a ruling. The full findings, with line citations and fixes, are at:

`collab/1789395574000-architect-tuples-review.gpt-6-astra.findings.md`

Read that file first. The orchestrator independently verified the three most surprising claims and they hold: `parity_test.cljc:99` uses the keyword `:parity/k` as a store key; `local-datom?` (`src/cljc/dao/datom.cljc:42-58`) requires non-negative integer `e`, so your ledger rows with addresses in `e` cannot be committed; the walker's `:eval-operator` arm writes `:operator-evaluated?`/`:fn` into the AST node held under `:frame` (`ast_walker.cljc:213-217`, hot path `:532`), so runtime bookkeeping cannot stay inside an immutable tuple.

## Task

Revise `docs/design/yin.vm.tuples.md` against all twelve findings. The seven owner rulings stand unchanged. Direction per finding, from the orchestrator's reconciliation — follow the reviewer's fix text where it is more specific:

1. **Store-key kind (§2.3):** widen `key` from `sym` to the portable data the store contract actually accepts (the parity corpus binds keyword keys); add the kind to §2.2 and keyword-key round trips to the both-path conformance obligations.
2. **`store-update` exclusion (§3.1):** keep the exclusion on its sound grounds — host function in syntax, absent from the codec and the semantic corpus. Drop the "second address for one program" argument (this design hashes syntax, not equivalence classes) and do not claim the `yin/def`-over-`store-get` rewrite is a general semantics-preserving migration; state its binding and result-behavior conditions.
3. **Runtime frame bookkeeping (§2.1, §9.1):** specify revised continuation-frame schemas: the canonical tuple stays immutable under `:frame`; `:fn`, `:operator-evaluated?`, `:evaluated`, and evaluation position move into the surrounding runtime map. Cover the cold and hot continuation arms.
4. **Dependency queries (§7.4):** publish executable extraction queries/rules for every declared category — including `:vm/resume` parked-id operands, which the current four queries miss — and specify their composition with callable profiles and the value/module fixed point; state when discovery is incomplete; use segment-qualified rows for unioned segment relations.
5. **Shared validator (§7.2–§7.3):** fold the segment structural constraints (nonempty, target/body bounds, final terminator, operand constraints — semantic §2.6's translated requirements) into the instruction grammar's validation rules; require both paths to invoke the shared validator with matching failure outcomes; test malformed vectors, not only the valid corpus; explicitly supersede UCF §7.3.4's projection-primary wording per the owner's direct-primary ruling.
6. **Ledger datom legality (§8.1–§8.3):** keep the address-to-address rows as a **logical relation**; define the persistent datom representation with local event/ref entities (non-negative integer `e`, addresses in `v`, integer `m`); clearly distinguish logical notation from transactor input.
7. **Open ledger operations (§8.2, §10.3):** use the established metadata-entity mechanism (ids ≥ 16 carrying `:db/op` and provenance) for open domain operations instead of allocating reserved ids 3/4 — or fully specify globally standardized markers including validity, hashing, and compatibility; separate event operation from fact validity; fix the derive row's direction; adjust §10.3 accordingly.
8. **As-of semantics (§8.5):** latest-event-wins per `[e a v]` (route through `query/current` with its as-of argument, or select the latest event before interpreting it); fix §6.4's unconstrained `?e`.
9. **Occurrence identity (§2.5, §4.4, §8.3):** content addresses serve sharing and cross-media correspondence, never occurrence identity — preserve source/batch identity, root address, structural path, and expansion-event identity outside canonical objects; link macro events to input/output/macro addresses rather than replacing the event link.
10. **Macro declaration carriage (§2.5, §8.3):** specify the frontend-output/expander-input composition — canonical trees plus occurrence-bound declaration facts, with ordering, missing-fact behavior, lexical scope, and plain redefinition — while keeping the flag outside canonical code as ruled. Flag this replacement protocol as the blocker; do not require building the expander.
11. **Hash chain realized (§5.2, §8.1):** the chain lives in ledger objects, not in the code objects (which rightly exclude predecessor addresses); a derivation record must name source object, output object, and the lowering contract/profile/revision, so a recomputing consumer does not misreport a legitimate revision difference as corruption.
12. **Encoder blocker scope (§4.2, §10.1):** gate every identity/dedup use — including process-local caches and flat-projection merges — on a type-preserving codec or an explicitly validated restricted value domain; process lifetime is irrelevant; keep it an inherited blocker.

## Constraints

- Edit only `docs/design/yin.vm.tuples.md`. Stage and commit nothing.
- Rules stated as rules; calls made and stated; no option menus. Where the reviewer's fix and your design judgment conflict, you may overrule with two lines of reasoning — the re-review will judge.
- Cite file:line for every load-bearing claim about current code; re-verify before citing.
- Where you cannot run something the reviewer ran, say so in your report.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and named timezone>
Coding-Agent: claude
Session-ID: 8677b374-bb75-4103-afab-4b65727a76c1

Then: the new line count; a finding-by-finding table (1–12: fixed how / overruled why); any new sections; and anything you verified against source versus took from the review.
