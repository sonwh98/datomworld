Completed-GMT: 2026-10-25 12:48:38 GMT
Completed-Local: 2026-10-25 19:48:38 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9

**Accept F1–F6, with one correction: `[k nil nil nil nil]` is the EAVT entity sentinel, not a universal sentinel for every covered order.**

Drop occupancy validation; pin the comparator at cursor creation; add explicit numeric-tie fixtures and the documentation below. These revisions resolve the design-review prerequisites for dispatching 4b-1. No files were edited.

## §3 — replace the cursor signatures

```clojure
(start descriptor comparator)
(step cursor)
(supply cursor response)
```

Replacement text:

> `start` pins the comparator in the local cursor for its entire lifetime. `step`, `supply`, bound interpretation and node-order validation use that comparator; none accepts a replacement comparator. Cursor state is a local execution value and is not promised to be serializable.
>
> The descriptor, outcome and response shapes otherwise remain unchanged. Neither cursor creation nor comparator pinning performs I/O.

This resolves F3 without relying on portable function-identity assertions.

## §4 — replace the node-validation list

> Validate only the following structural and ordering properties on the traversed path:
>
> - A leaf has the persisted leaf shape with a vector of keys.
> - A branch has the persisted branch shape with a positive integer level, a vector of keys and a vector of child addresses.
> - A branch’s key and address vectors have equal lengths; every referenced child address is present.
> - Keys are ordered under the comparator pinned at cursor creation.
> - A fetched child has a strictly lower level than its parent; leaves are treated as level zero.
> - A fetched child’s maximum compares equal to the corresponding parent separator under that same comparator. Content identity is not the separator-equality test.
> - A supplied response matches the cursor’s pending address.
>
> **Do not validate minimum or maximum occupancy, fill ratio, balance policy, or branching-factor-derived key counts.** These are builder policies rather than established persisted-format acceptance rules. Root nodes, single-child spines and partial final chunks must not be rejected merely for their occupancy.
>
> The branching-factor metadata does not authorize a reader to reject an otherwise valid node on occupancy grounds. Any future resource-size limit requires its own explicit admission contract.
>
> Validation remains incremental and must not fetch siblings solely to validate the selected path. It does not certify the whole tree.

**F1 ruling:** choose removal, not an occupancy-proof prerequisite. Add parity fixtures for roots, single-child spines and partial final chunks to prevent occupancy restrictions from reappearing.

## §6 — append the sentinel and stopping rule

> DaoSpace range planning uses canonical datom slot positions `[e a v t m]`, regardless of comparator order. Under the existing nil-first comparison of sentinel fields, use:
>
> | Selected access path | Lower sentinel |
> |---|---|
> | EAVT, entity `e` | `[e nil nil nil nil]` |
> | AEVT, attribute `a` | `[nil a nil nil nil]` |
> | AVET, attribute `a` and value `v` | `[nil a v nil nil]` |
> | VAET, value `v` | `[nil nil v nil nil]` |
> | Full EAVT walk | No lower bound |
>
> These are seek keys, not stored datoms. Do not reorder their physical slots to match the comparator’s field priority.
>
> Slot/prefix scans stop using the query layer’s comparator-based prefix test, following `same-slot?`. They must continue across values that compare equal but differ in content identity. Apply content-strict matching after candidate production.
>
> Do not manufacture a generic upper key by replacing unspecified fields with nil: nil-first ordering makes that an incorrect upper boundary for a prefix range.

**Cursor docstring mandate:**

> Bounds and node ordering use the comparator pinned at `start`; content equality must never terminate a comparator-equal range. Prefix scans are a caller policy, not inferred by the cursor. For DaoSpace’s canonical d5 layout, the EAVT entity lower sentinel is `[e nil nil nil nil]` under nil-first sentinel ranking; other covered orders use their corresponding canonical-slot sentinels. Slot scans use caller-owned prefix stopping, followed by content-strict filtering.

This is explanatory usage documentation only. `cursor.cljc` must acquire no dependency on DaoSpace, its comparators or its datom representation.

## §11 — add mandatory kind-tie fixtures

> Generate persisted-tree fixtures containing comparator-tied, content-distinct values:
>
> - Integer `1` versus float `1.0`.
> - Decimal `1.0M` versus `1.00M`, using portable numeric constructors.
> - Positive versus negative floating-point zero.
>
> Exercise each pair at the lower seek boundary and the prefix-stop boundary for EAVT, AEVT, AVET and VAET.
>
> Construct rows with differing later comparator fields so that both members can survive the sorted-set representation. Do not require a publisher to retain two complete keys that its comparator treats as identical.
>
> Arrange mixed-kind ties across leaf boundaries and, where possible, subtree boundaries. Include a content-distinct tied candidate before a matching candidate, and matching candidates immediately before the first genuinely out-of-prefix key.
>
> Prove both:
>
> 1. Generic cursor output agrees in order with `bt/slice` under identical bounds.
> 2. Query prefix scanning includes the complete comparator-tied range, while content-strict filtering selects the intended values.
>
> Run inclusive/exclusive generic-bound cases as well as prefix-stop cases. Use explicit fixture expectations in addition to parity checks so two implementations cannot pass by making the same filtering mistake.

F2 is therefore resolved at both the generic cursor and query-integration layers.

## §5 and compatibility-doc step-unit section — append calibration guidance

> Checked published-index traversal adds `:tree-step` work and, for each actual fetch, `:node-read` work to the existing scan/candidate/pull accounting. Yielding and interpreting a candidate therefore costs more than its query-layer candidate unit alone. Seek descent, validation, ascent and advancement can add further transitions.
>
> There is no universal “two steps per datom” or fixed conversion from Phase 4a allowances. Applications must recalibrate `:max-steps` using representative checked-path workloads.
>
> Each new scan starts without reuse of the legacy restored-tree cache. Joins, pull projections and special forms that rescan an index may repeat seek-path reads and their associated work charges. Report these costs separately from result cardinality and backend cache latency.

This resolves F4 without promising a misleading twofold multiplier.

## §6 and slice 4b-3 — explicitly add pull-in-find

Add to the routed-path list:

> `(pull ?e pattern)` in `:find` is a separately tested entry path: find projection → pull implementation → checked candidate producer. Its nested and wildcard reads must not escape through legacy tree traversal or deferred rows.

Add to 4b-3 acceptance:

> Prove checked traversal for pull-in-find, including nested projection and wildcard patterns admitted by the adapter, with deadline injection during a later node fetch and no subsequent read.

This resolves F5 without expanding the admitted pull grammar.

## §7 — append the memoization clarification

> The unbounded path’s restored trees already memoize nodes through host-mutable references owned by the source implementation, not by query execution state. Leaving that machinery unchanged while the checked path deliberately does not consult it introduces neither shared mutable query-control state nor a requirement to remove existing memoization.

This resolves F6. Installing clocks, allowances or invocation progress in those references remains forbidden.

## Plan, estimate and transcription

- **Estimate unchanged:** 20–32 implementation agent-hours, plus 5–8 review/remediation hours; **25–40 total**.
- **Slice ordering unchanged:** reviewed design → pure cursor → descriptor/single-node reader → kernel integration → qualification.
- **4b-1 may now be dispatched** with the revised signatures, validation list and tie-fixture mandate.
- The admission fence, one-in-flight guarantee and unbounded `≤4`/`≤15` regression requirements remain unchanged.

For §13 amendment instructions, add:

> Transcribe these r8 replacements together with the r7 design. Replace every occurrence of `(step cursor comparator)` with `(step cursor)` and specify `(start descriptor comparator)`. Remove occupancy validation language. Include the canonical-slot sentinel table, kind-tie tests, calibration paragraph, pull-in-find route and source-memoization clarification. These revisions supersede conflicting r7 text; they do not expand Phase 4b admission or confer implementation qualification.