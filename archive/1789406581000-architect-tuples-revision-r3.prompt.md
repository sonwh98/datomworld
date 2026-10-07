Created-GMT: 2026-09-14 17:23:01 GMT
Created-Local: 2026-09-15 00:23:01 +07
Coding-Agent: claude
Session-ID: 8677b374-bb75-4103-afab-4b65727a76c1 (resume — your own drafting session for docs/design/yin.vm.tuples.md)

# Task: Revise the tuple code-representation design (round 3)

Role: Architect — design document only. No implementation, no staging, no commits.

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-15 00:23:01 +07 | Status: active | Rationale: you authored and revised the draft; the reviewer is gpt-6-astra (different family), independence preserved.

## Context

Your round-2 revision was re-reviewed by gpt-6-astra on its thread. **Verdict: REJECT, 8 P1s** — 5 of your 12 round-2 resolutions were accepted as resolved (frames, shared validator, ledger legality, reserved ops, as-of; the as-of fix was executed and passed), and the central representation decisions were again endorsed ("sound", "closer to implementable"). The full findings with line citations and fixes are at:

`collab/1789396807000-architect-tuples-rereview.gpt-6-astra.findings.md`

Read that file first. The orchestrator re-verified the reviewer's executed claims and they hold: numeric store keys lower today (`lower-ast` on `:key 42` succeeds and emits `:yin.code/key 42`); `{:effect :vm/store-put …}` is plain data AND satisfies `module/effect?` (`module.cljc:75-79`); the semantic VM maps `:stream-put` → `{:effect :stream/put …}` (`semantic.cljc:373`); `order-normalize` coerces every sequential to vector and `pr-str` drops metadata, so `(seq [1 2])` and metadata-carrying vectors collide.

## Task

Revise `docs/design/yin.vm.tuples.md` against all eight findings. The seven owner rulings stand unchanged. Direction per finding — follow the reviewer's fix text where more specific:

1. **Store-key domain (§2.2, §7 validators):** define portable store keys as the admitted data domain (`plain-data?`'s domain), subject to the canonical encoder's restrictions — uniformly across AST validation, instruction validation, dependency extraction, and store-slice encoding. Symbols-and-keywords was corpus coverage masquerading as the contract; numeric keys are supported today.
2. **Store-update rewrite condition (§3.1):** the result condition becomes "plain data **and not an effect descriptor under the applicable execution contract**" — effect descriptors are themselves plain data (`module/effect?` recognizes any map with `:effect`). The exclusion stays independently justified by the host-function-bearing syntax.
3. **Encoder gate (§4.2, §7.4, §10.1) — this is your regression; withdraw, do not patch.** Remove the restricted-domain claim entirely: no reject-lists blacklist makes address-based identity safe. State that **every identity and deduplication use is blocked until dao.jing's canonical encoding is fixed** — for tree data slots, flat projections, process-local caches, and ledger-record maps alike. Add the reviewer's counterexamples (`(seq [1 2])` vs `[1 2]`; same-vector-different-metadata) to the encoder conformance obligations.
4. **Occurrence identity (§2.5, §8.4):** structural paths `[root-address path]` separate positions within one tree; add source/batch occurrence identity to separate identical whole trees from different files or batches, and qualify generated positions with the **producing expansion event** so nested expansion ancestry is unambiguous (the macro contract links nested input to the enclosing expansion's output). Carry the qualifiers through instruction provenance and event links.
5. **Macro whole-batch admission (§8.5, migration adapter):** the old admission/harvest operates on **every entity in the batch, in batch order, including disconnected definitions** — a single rooted tree plus paths cannot represent that. Specify an ordered batch representation or admission-side catalogue preserving all definitions and their order (including those outside the execution root), and validate original macro marks **before** stripping, rejecting stray marks as `:stray-macro-lambda` (a flagged lambda outside `yin/def` must not silently become a plain lambda). If you intentionally narrow the old batch contract, say so explicitly rather than describing it as unchanged.
6. **Dependency completion (§7.7):** the any-carried-environment satisfaction rule is unsound — a saved continuation binding `x` cannot discharge the active activation's `x`, which may resolve to a primitive. Either associate resolution obligations with relevant code/environment contexts or conservatively retain all possible primitive/module requirements; when the analysis cannot establish completeness, report `:incomplete`. An unrelated carried binding never discharges an obligation. Note: this repeats UCF §7.6.1's still-open defect — state the rule identically here and record that UCF needs the same amendment.
7. **Effect normalization (§7.7):** the segment query returns mnemonics (`:stream-put`) while callable profiles and UCF §7.6 use effect identifiers (`:stream/put`). Specify a contract-versioned instruction→effect-footprint mapping (the reference machine's own mapping at `semantic.cljc:370-391` is the starting point), applied before unioning syntax-derived effects with callable-profile effects; include explicit mappings for instructions with no external effect and for FFI behavior; require that equivalent AST and segment inputs produce the same requirement sets.
8. **Lowering revision (§5.2, §8.2):** the derivation record's stamp reuses UCF's execution-contract stamp, whose declared scope (grammar, transitions, resolution, effects, scheduling — UCF §7.3.3) does **not** pin the lowering algorithm; two deterministic lowerers can target one execution contract and emit different valid vectors. Give the lowering function its own pinned revision/profile — input grammar, normalization, exact emission rules — either a dedicated stamp or an explicit extension of the governing stamp with mandated revision changes. Preserve the structured stamp exactly in the event projection (no undefined `"v2/0"` stringification). Separate object-hash verification from verification of the asserted derivation.

Finally, per the reviewer's closing note, bring §10's acceptance list up to date: the unresolved key domain, complete occurrence identity, macro batch/admission preservation, conservative dependency completion, effect normalization, lowering-profile definition — and the withdrawn safe-domain claim.

## Constraints

- Edit only `docs/design/yin.vm.tuples.md`. Stage and commit nothing.
- Rules stated as rules; calls made and stated; no option menus. Where the reviewer's fix and your judgment conflict, you may overrule with two lines of reasoning — the re-review will judge.
- Cite file:line for every load-bearing claim about current code; re-verify before citing. Where you cannot run something the reviewer ran, say so.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and named timezone>
Coding-Agent: claude
Session-ID: 8677b374-bb75-4103-afab-4b65727a76c1

Then: the new line count; a finding-by-finding table (1–8: fixed how / overruled why); sections touched; and anything you verified against source versus took from the review.
