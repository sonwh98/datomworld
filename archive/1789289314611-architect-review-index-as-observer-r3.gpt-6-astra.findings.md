I’ll check the revision against A1–A11, then probe checkpoint promotion, the `m` mode rule, and the proposed provenance attributes. I’ll use the code-review-pr skill again and keep the review read-only.
Most of A1–A11 are addressed. Checkpoint promotion can be verified independently through durable-store reads, and rejecting positive user `m` references is consistent with unresolved mode. I found remaining gaps: checkpoint capture needs observer fields absent from its proposed input, draining defects erases the only rejection record, and the reserved-id prose contradicts the matrix. I’m also checking empty-batch and shared-allocator recovery cases.
Completed-GMT: 2026-09-13 09:24:17 GMT
Completed-Local: 2026-09-13 16:24:17 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a099f1-87d6-7811-8a69-b3336b956fac
Role: Lead System Architect (review) | Model: gpt-6-astra

Reviewed HEAD `3d32eb4`. Read-only review; no files written. References below to “the note” mean `docs/design/dao.space.index.as-observer.md`.

| Finding | Resolution | Evidence / remaining gap |
|---|---|---|
| A1 — `m` resolution | **Resolved** | §2.2:199–211 covers all three identity slots, including `m`-only tempids; tests at 624 and 667. Reserved-id wording needs the correction below. |
| A2 — external versus local provenance | **Resolved, with explicit prerequisite** | §3.2:336–367 separates descriptive external coordinates from local refs. Phase 2 is correctly gated on the macro-document amendment. |
| A3 — checkpoint validity | **Partially resolved** | §4.2 establishes promotion and bound restoration. Capture still lacks access to observer fields; draining defects loses completeness information; shared-allocator recovery is unspecified. |
| A4 — publication progress | **Resolved** | §4.1:442–458 retains `:next` per accepted occurrence. Phase 0′ explicitly depends on the observer partial-session fix. |
| A5 — query value and “live” | **Partially resolved** | §5 correctly introduces a covered source and defines observation-relative freshness. Its promised gap/defect reporting still needs the state plumbing and cumulative information identified below. |
| A6 — mode validation | **Partially resolved** | The matrix closes positive user refs and negative metadata refs. Its reserved-id definition and `e` behavior contradict adjacent text and existing parity claims. |
| A7 — watermark recovery | **Partially resolved** | Checkpoint summary plus suffix and the three restart cases are correct. Empty-history initialization remains unspecified. |
| A8 — normalization and atomic rejection | **Partially resolved** | Outer grammar and rejected-batch transition are explicit. An admitted empty batch has no defined maximum `t`. |
| A9 — logical publication parity | **Resolved** | Phase 0′:611–620 requires logical equality and split-producing inputs, not identical manifests. |
| A10 — query clock semantics | **Resolved** | §2.3 states ordering, mixed-clock `as-of`, and resolved-identity qualifications. |
| A11 — allocator rationale and invariant wording | **Resolved** | §3.1 acknowledges partitions and advisory source-domain discipline; §6 fixes the stale entries. Shared-allocator recovery is a separate remaining issue. |

**[P2 — must address] R1. Checkpoint capture and completeness reporting need the whole session.**

Location: note:125–144,466–480,495–501,562–565.

`publish!`, `checkpoint`, and `db-value` take only `index-state`. That value contains neither `:cursor` nor `:ingress-gaps`; those belong to the `:observer` slot. `run-on-stream` updates them independently, especially on a gap (`src/cljc/dao/stream/observer.cljc:191–205`).

Consequently, the proposed functions cannot capture the candidate shown or report the promised gaps from their declared inputs. `restore` likewise returns only index-state while promising to reattach an observer at `c` and restore its gap count.

**Correction:** Define an explicit composition operation over `{:observer :consumer}` that captures publication coverage and observer metadata together. Alternatively, pass the observer snapshot explicitly. State the actual return shapes for publication, capture, restoration and query-value construction. Keep the generic loop ignorant of index fields.

Test a gap immediately before capture and verify that the candidate and query report contain the exact successor cursor and gap count.

**[P2 — must address] R2. Draining diagnostics must not erase known incompleteness.**

Location: note:134–141,471–478,495–500,562–565.

`:defects` is the list “since last drain,” and `drain` clears it. Yet the checkpoint describes that field as a defect count and relies on restoring it to preserve partial-index status.

Sequence:

1. Reject a batch and advance its cursor.
2. Drain diagnostics.
3. Publish and checkpoint.
4. Restore an index missing that batch, with no remaining rejection indication.

This also invalidates “`:defects` says which” for live values after a drain.

**Correction:** Separate drainable diagnostic events from cumulative coverage metadata. Preserve at least a monotonic rejected-batch count or partial-status flag. If identifying every omitted batch is promised, retain or explicitly reference that coverage record. Capture and restore the cumulative information regardless of diagnostic draining.

Add reject → drain → checkpoint → restore coverage.

**[P2 — must address] R3. Reserved-id rules overlap tempids and disagree about entity admission.**

Location: note:210–211,286–295.

“Reserved” is defined as any id below `first-user-id`; that includes every negative tempid. Step 2 also says reserved ids pass through in every slot, while the matrix rejects reserved `e` in both modes.

There is a compatibility consequence: `local-datom?` and the transactor currently admit non-negative `e`, including zero (`src/cljc/dao/datom.cljc:42–57`; `src/cljc/dao/space/transactor.cljc:64–69`). A resolved observer rejecting those rows cannot unconditionally satisfy Phase 0′ parity with the existing snapshot/index path.

**Correction:** Make the categories disjoint:

- tempid: integer `< 0`;
- reserved: integer `0 ≤ id < first-user-id`;
- user-positive: integer `≥ first-user-id`.

Then choose one authoritative rule for reserved `e`. Preserve existing resolved-path behavior, or explicitly identify the tightening and narrow the parity claim. Replace “in every slot” with the selected matrix rule.

**The user-positive `m` decision itself is correct.** In unresolved mode, an arbitrary positive user metadata reference could name an unrelated observer allocation. Rejecting it is consistent with rejecting positive user ref-valued `v`. The common `default-op` case passes because it is reserved. Negative event ids resolve normally; no macro knowledge is required.

**[P2 — must address] R4. Empty batches and empty-history watermarks remain undefined.**

Location: note:131,170–179,219–220,504–521.

The grammar admits an empty sequential batch. Step 3 then requires its greatest row `t`, which does not exist. Likewise, initial `:max-t` has no specified empty-history representation. Initializing it to zero would make the stated `max-t + 1` rule return one when reopening an empty stream, whereas the transactor requires zero.

**Correction:** Define an empty-history sentinel—such as `:max-t -1`—and leave it unchanged for empty or rejected batches. An empty admitted batch should either advance the ordinal once without changing trees/allocator/maximum, or be explicitly rejected as data. State the choice and test it, including an empty checkpoint reopened in-process.

Also specify the within-row encounter order, for example `e`, then declared-ref `v`, then `m`, to make “first occurrence” completely deterministic.

**[P2 — must address] R5. Per-session checkpoint restoration can rewind a shared allocator.**

Location: note:307–309,471–472,495–499,658–683.

Serial threading prevents concurrent allocation collisions, but it does not make an old per-session allocator snapshot safe to restore.

For example, session A checkpoints `next-eid = 20`; session B then allocates ids 20–29 from the shared domain. Restoring A’s checkpoint and reinstating its `:ids` can allocate 20 again while B’s identities remain in use. Replaying shared sessions in a different order can also change their previously assigned ids.

**Correction:** Specify ownership and recovery at the allocation-domain boundary. Either:

- restrict this checkpoint/restore contract to independently allocated sessions and explicitly defer coordinated recovery; or
- define a consistent composition checkpoint and recovery discipline for all sessions sharing the allocator.

Do not restore a stale session-local `:ids` as the authoritative shared allocator. Add a test where another session allocates after the checkpoint being restored.

This is a newly identified recovery gap, not a reason to replace the serially threaded allocator design.

**Checkpoint promotion: the chosen predicate is sound within the declared storage contract.**

Manifest validation plus successful resolution of every reachable blob establishes that the indexed snapshot can be reopened. It does not require cooperation from the asynchronous DaoJing observer: the composition can read the durable content store independently and retry verification later if content is absent. Immutable, retained content makes successful checks accumulate safely.

The implementation must:

- Validate the manifest using the existing manifest reader.
- Walk **all four roots**.
- Check each address in the `walk-addresses` visitor, including leaves. The walker does not itself restore every leaf (`src/cljc/dao/data/btree.cljc:2101–2118`).
- Treat missing content or read failure as verification incomplete, never success.
- Use the durable-store read path, not the recording handle or a cache whose presence does not establish durability.

No materialization acknowledgement channel is necessary for this synchronous-readable-store composition. Async-only stores need an explicit asynchronous verification mechanism; that remains a capability prerequisite. The verified checkpoint record must itself remain recoverable, and suffix retention remains necessary for replay. Promotion proves snapshot availability, not those separate conditions.

Add “manifest present, one leaf absent” to the tests; checking only an absent manifest would miss an incomplete traversal.

**The macro amendment has the right shape; a structured external coordinate is an optional strengthening.**

Keep two attributes because they have different resolution semantics:

- a declared-ref attribute for the log-local node;
- an undeclared value attribute for the external coordinate.

For a composition where one macro log unambiguously belongs to one source session, external tempid plus `:yin/source-batch` is sufficient. The composition supplies the source identity.

For portable events or merged logs, prefer a structured value containing an explicit source/session identity, producer batch ordinal and original entity id. Medium identity alone may be insufficient when producer ordinals restart. That value stays opaque to the indexer and does not replace the separate local-ref attribute.

The phase gate is appropriate; the macro specification need not be silently interpreted as already amended.

The publication retry design, strong-reference remedy, query-source extension, symmetric ignorance and revised phase prerequisites remain sound. The outstanding changes concern state ownership, completeness preservation and recovery edge cases.

**Verdict: REQUEST CHANGES.**
