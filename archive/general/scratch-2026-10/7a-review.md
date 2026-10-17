Created-Local: 2026-10-13 +07
# Review: slice 7a, custody on rows and the rows inspector

Reviewed commit `b4d80e8d`, diff `23a8caef..HEAD`, against phase7-plan §§3.1, 3.3, 3.4 and slice 7a, the continuation and transport custody/staging rules, the old checkpoint inspector and `handoff/accept-grant!`. Non-author review. No blocking implementation defect found. Ready for the specified integration branch, with the known old-codec breakage held there until 7b–7d complete. This is not approval to land this slice alone on master.

## Findings

| ID | Severity | File:line | Finding / action |
|---|---|---|---|
| F1 | Low | `collab/1792420000000-engineer-7a-custody-on-rows.report.md:42` | The inventory is inaccurate: the committed corpus has **53 pins and 48 mutations**, not 55/50. Five mutations are valid variants/boundary cases, so **43**, not 50, require refusal. Correct the report/inventory; no missing required custody rule was identified from this count discrepancy. Definitions are at `test/yin/vm/ucf/rows_checkpoint_fixtures.cljc:226`; the resource has 53 blocks and three full-byte anchors. |
| F2 | Low | `collab/1792420000000-engineer-7a-custody-on-rows.report.md:145` | D3's doc amendment list is correct but incomplete as a comprehensive list. Add the transport lower-stage prose, pack/root identity and sorting scope, continuation's decision inventory, and fenced-mode boundaries (details below). These are deferred documentation work, not a reason to block integration. |
| N1 | Informational | `src/cljc/yin/vm/ucf/rows.cljc:1751` | D4 is real: the lift walks/serves before checking exhaustion and protection. Preserve this fact in 7b's export-record accounting. The plan does not impose a zero-serve lift law; the lower's zero-attach law holds. |

## Custody and lower staging

The implemented slot matches the owner's ruling and plan:

- Fork roots of blocked, parked and halted kinds: nil. Every install child: nil.
- Blocked/parked exclusive roots: policy, occurrence, arbitration and next-op-seq, plus optional origin.
- Halted exclusive roots: origin alone, required.
- Unknown top-level custody keys are rejected. Epoch, current lease, holder, enrolled set and protection never get serialized into the slot. The predecessor lease inside the specified origin is intentional and matches the old grammar; it is not current tenure evidence.

`check-root-custody!` preserves the old required-key, origin, UUID spelling, arbitration shape, integer-kind/range and first-export-zero rules. `check-op-id!` preserves exact two-key shape, origin requirement, not-own-occurrence, strict counter bound and duplicate detection across the tree. Task kind/role, install completeness and child nil-slot checks are present. Intent digests use BLAKE3 of canonical append intent with the wire payload slot; structural payload references are emitted to row ids.

The lift refuses exhaustion at 2^52−1 on non-halted exclusive roots, enrolled writes without ids, and ids on unenrolled streams, including children. It runs the grammar before emitting. Forks carrying ids are refused. Halted exclusive lowering requires no grant and ends the root; fork under `:exclusive` is refused.

`lower-task:2215–2219` completes stores, pendings and dependencies, each across the whole tree, then invokes custody **once**, before the single attachment operation. The tests check attachment counts on grammar, grant, protection and fork refusals. The moved-stage mutant produced 30 failed assertions, independently verifying the boundary.

## Grant port and restore

Compared `rows.cljc:1899–1976` directly with `handoff.cljc:3343–3420`. No effective grant check was dropped or weakened:

1. Grant/evidence shapes; missing evidence awaits a grant.
2. Ready evidence status, with awaiting/unsatisfied outcomes preserved.
3. Binding and tenure shapes; available input prefix.
4. Checkpoint address and binding occurrence.
5. Non-nil matching lease and holder, matching arbitration, exact epoch.
6. Live tenure with numeric now/bound and now strictly below bound.
7. Prefix occurrence/lease, exact frontier, vector records, count agreement and contiguous exact input-seq numbers.

Protection checks every stream in the tree: a class must exist and enrollment must agree in **both** directions. Retained writes additionally require op-id presence exactly when enrolled. The reader fixture with no retained write isolates the stream-class check from the later op-id check.

Two representation changes are legitimate: the old `(some? (:address opts))` guard disappears because lower computes a non-nil BLAKE3 pack address itself; key presence for old pending op-ids becomes non-nil row-slot presence. Nil is the row grammar's absence spelling. Missing marker/payload shape validation is provided by the row schema rather than removed from the contract.

Restore takes occurrence/lease/holder/epoch from evidence, restores next-op-seq from the slot exactly, and numbers put waits in carried order. Counter restoration is tested through the bound and killed by an off-by-one mutant. Children receive the root's execution gate and issue numbering, without independent custody.

`:fenced` is the explicitly minimal 7a mode: grammar still runs; grant/protection are skipped; tasks gate exporting and waits leave the scheduler for recovery-waits. It does not yet reconstruct recovery-cell-ids, the exact machine id counter, the recovery header comparison or the parked-record recovery representation. Those belong to 7b. The exporting-gate mutant is caught.

## Inspector and identity ruling (D2)

`inspect` verifies address versus bytes first, canonical single-item CBOR second, then calls `rows/decode`, requires a task root and runs custody grammar. Decode validates row hashes, structure/reachability, canonical internal ordering and cursor numbering, plus frames. `inspect-pack` is the already-decoded-wire entry and necessarily does not check a claimed byte address.

The pack address is the content-store key, offer id, grant checkpoint and report result. The root id is the baseline's `:yin.k/root`. **Recommend keeping root in the baseline and refusing a different-root re-emission as `:variant-conflict`.** An id-counter edit changes transported execution state, even if occurrence, op ids and their append intents stay equal. Admitting it under the same occurrence would let one grant select distinct execution states. The root's structural identity must constrain variants; an operation-only baseline does not prove equal continuations. Amend plan row-1 to use one structural root with different carrier order, as the implementation now does.

**Recommend continuing to accept unsorted pack row vectors.** They are canonical CBOR encodings of a differently ordered carrier containing the same verified row set. Internal row ordering and canonical numbering remain enforced. Canonical emission can sort without imposing that order on every accepted carrier. This preserves meaningful same-root variants without allowing different state roots. State the guarantee precisely: `emit` gives one canonical pack per structural root; decode may accept other pack orderings. Requiring sorted input instead would remove the current same-root variant and require an explicit redesign of row-1, not a silent codec tightening.

The baseline must exclude the pack address: including it would make every differently addressed variant conflict by definition. The grant must nevertheless bind to the actual pack address; both that test and an independently weakened address-check mutant are caught.

## Sorting and portability

`pack:712–719` sorts the row-map keys by their printed BLAKE3 segment keyword strings. All emitted ids have the same namespace/prefix and fixed-width lowercase hexadecimal suffix. Lexicographic order therefore equals unsigned digest-byte order: digits 0–9 precede a–f, and the shared prefix cannot affect comparisons. This is neither numeric cc-N sorting nor a locale collation. Cursor-cell census ordering remains its separate numeric digit-length ordering. The ordinary string comparator on JVM, JavaScript and Dart orders these ASCII characters consistently; no locale API is invoked.

The new production/support/fixture code uses mapv, reduce, into, mapcat and doseq, without new `for` comprehensions or cross-namespace private-var access. The `for` in ledger-fixture-test is pre-existing. Collection construction starts from maps, and exception reader conditionals put cljd Object first. Integers up through 2^52−1 and its immediate successor are representable on Node and Dart. `custody/exact?` checks canonical integer kind and the correct range. Integral Float64 version/counter fixtures are deliberately floating carriers: JavaScript retains the custom Float64 wrapper, Dart a double, and `code/reg?` rejects each via integer?. Resource reading is line/hex parsing rather than EDN numerical reading, through the existing per-host file reader.

This is a source portability review, **not a Node/Dart execution result**. Their corpus/fixture agreement remains a required integration gate under the plan; JVM byte agreement alone cannot substitute for it.

## Fixtures, harness and ledger

Independent source/resource count: 5 bases + 48 mutations = 53 pins; 10 accepted total (five bases plus five valid mutated variants/boundary cases), 43 refusal cases, three byte anchors. The claimed 55/50 count is not reproduced.

The passing tests reconstruct the whole fixture file, verify all declared refusals and their paths where specified, check changed pack identities, and compare the anchors' full bytes. Carrier-level row hash/version refusals intentionally do not declare a semantic custody path. Hash-mismatch and non-canonical fixtures intentionally reuse the base pack object: the former changes the claimed address, the latter changes the bytes. They are not vacuous mutations. The accepted variants are intentionally not refusal cases.

The ledger-v2 script renders the checked-in journal frames byte for byte and reproduces projection digest `2b26e75e8969428761c4f7813d1ffc886d30d160f2f3e8f80076bbe24ef1f227`, including memory and file replay. The one safepoint harness change is accepted: the new grammar classifies its deliberately invalid custody slot as undecodable/custody-key. The other ordering assertions remain, and the existing root pins pass unchanged.

## D3 documentation follow-up

Accept the engineer's proposed amendments: transport §5.5 fork=nil and five-key slot grammar; continuation §§2.5/2.6/7.1/7.2 replacing phase-6 nil-only custody with the header lift, whole-tree grammar/grant stage and current refusals; continuation Q4 partially closed for op-id grammar, with correlation agreement explicitly still open; UCF §7.11.1 inspector reference changed.

Complete that list with:

- Transport §§7–8 (including the nil-only lower prose around line 819): sorted **emission**, pack address versus structural root id, root in baseline, accepted unordered carrier variants, and the implemented grant/protection/counter contract.
- Transport fenced-lower text and continuation §7.2: distinguish the implemented minimal fenced mode from the recovery features 7b must supply.
- Continuation decision inventory D3/D8 around lines 2443/2448, which still describes the phase-6 custody behavior; retain historical inventories as historical, or explicitly mark the superseding rule.
- Plan §1.1 row-1: replace the different-root/equal-baseline example with a same-root/different-row-order example. Add an explicit different-root conflict case.
- At phase completion, the already-planned continuation §§8.4/10/11 inventory, transport recovery, UCF §7.7.4 recovery-v2 and §7.11.1 stage updates, and orchestrator entry. Do not mark install Q3 closed before 7b or correlation agreement closed merely because op-id grammar landed.

D1 is accepted under the orchestrator's integration-only landing decision. No version dispatch or compatibility shim is recommended. D4 needs accounting awareness, not a 7a semantic workaround.

## Independent mutation results

Each executable mutant used a copied source backup, a fresh foreground JVM test run of rows-custody-test, rows-checkpoint-test, ledger-fixture-test and authority.grant-test, and immediate restoration. These four namespaces normally run 50 tests / 449 assertions. Both mutated source files compare byte-identically with backups; `git diff --quiet -- <both source paths>` and the complete tracked `git diff --quiet` passed after restoration. Scripts/logs were kept under `/tmp`; no source/test edit remains.

| Mutant | Result | Failed assertions / errors |
|---|---|---|
| Custody header by kind swapped | Killed | 3 / 103 (fixture bases also fail to lift) |
| Holder equality grant check replaced with true | Killed | 2 / 0 |
| Enrolled evidence → enrolled class implication dropped | Killed | 2 / 0 |
| Restored counter incremented | Killed | 6 / 0 |
| Fork under exclusive accepted | Killed | 2 / 0 |
| Custody stage moved after attachment | Killed | 30 / 0 |
| Emit uses unsorted map vals | Killed | 81 / 0 |
| Inspector skips task-root check | Killed | 0 / 1 (frame-root becomes an exception instead of declared refusal) |
| Baseline excludes root id (additional D2 mutant) | Killed | 19 / 0 |
| rows/max-exact increased by one | Killed | 6 / 0 |
| Fenced restore omits exporting gate | Killed | 1 / 0 |
| Baseline explicitly dissocs pack address/id | Survives, equivalent mutation | 0 / 0; neither key is present by design |
| Grant checkpoint/address equality replaced with true (additional meaningful address mutant) | Killed | 2 / 0 |

Thus no behavior-changing mutant in this set survived. The requested pack-address-baseline exclusion is already the correct implementation, not a missing test. The first max-exact attempt was rejected by the driver's unique-replacement guard because the literal also appears elsewhere; the rerun targeted the definition specifically. Sources were restored in the guard's finally block before rerunning.

## Requested foreground verification

Ran exactly the requested seven-namespace command with `clojure -M:test -e :slow`, and again with `-i :slow`:

- Quick: **127 tests, 2,418 assertions, 0 failures, 0 errors**.
- Slow: **4 tests, 2,724 assertions, 0 failures, 0 errors**.

No background shell job was used. The only intended deliverable is this findings file; tracked source/test state is unchanged.

VERDICT: READY_TO_MERGE (into the integration branch)
