I’ll compare the revised plan and contract amendment against the existing cursor provenance rules and conformance model. I’ll also inspect whether the shared abstract model currently treats beyond-tail positions as valid, since that determines whether memory-log’s stricter classification needs a model option rather than being merely transport-owned.

I’m using the repository’s `code-review-pr` checklist for this revision review, focusing on contract consistency, falsifiable exclusions, and whether the plan is executable without weakening cross-transport conformance.

Completed-GMT: 2026-09-08 14:42:30 GMT  
Completed-Local: 2026-09-08 21:42:30 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Architect review: memory-log plan r2

## Verdict

**One revision remains before implementation.** All six original findings are closed for `memory-log` itself, but the generalized `run-retention-laws` would incorrectly reject valid finite-capacity complete-history writers and is underspecified for reader-only terminal behavior.

## Finding

### P1 — The generic retention law assumes every writable complete history accepts all 256 values

File: [plan](/Users/sto/workspace/datomworld/collab/1788878129246-architect-v2-memory-log-r2.claude-fable-5-1.findings.md:179)

**Blocks implementation: yes.**

The contract explicitly permits a complete-history writer with declared finite capacity to return permanent `full`. The proposed generic law says to append 256 values and then compare the replay with `expected`, apparently all 256 values. That would fail a conforming capacity-2 complete log after its first two successful appends.

A generic transport may also restrict its value domain. Its test values could legitimately receive `invalid-value`; completeness promises retention of accepted values, not acceptance of arbitrary conformance tokens.

The reader-only fixture has a related ambiguity: `{:handle h :expected [...]}` does not say whether the populated immutable reader should terminate with `blocked` or `end`. Either lifecycle is contract-valid.

**Concrete change:**

- For writer manifests, collect only values whose append answered `ok`.
- Stop population on `full`; do not treat permanent `full` as a retention failure.
- Supply transport-valid population values through the manifest or a retention fixture rather than assuming every transport accepts arbitrary keywords.
- Require at least one accepted value, so the law cannot pass vacuously.
- For reader-only fixtures, include the expected terminal outcome, for example:
  `{:handle h :expected [...] :terminal :dao.stream/end}`.
- Continue requiring post-close `end` where the law itself closes an owner handle.
- The falsifying ring buffer still works: it accepts every append, so the kept origin cursor observes `gap` after capacity two.

With that correction, the conformance law covers unbounded memory-log, finite complete writers, restricted value domains, and immutable readers without over-certifying any of them.

## Specific judgments

### `pos > tail`

**Memory-log’s `invalid-cursor` result is contract-legitimate.**

Cursor representations and validity are transport-owned. On memory-log:

- Positions never disappear.
- The tail never retreats.
- There are no frozen attachment tails.
- Therefore no cursor minted by that logical stream can contain a position above its current tail.

Rejecting such a value as `invalid-cursor` is sound. The ring buffer’s more permissive `blocked` result does not create a cross-transport law: the cursor keys and representations differ, and conforming consumers never fabricate either value.

The current abstract model treats all `pos >= tail` as `blocked`/`end`, but none of the proposed linearizability histories supplies a fabricated future cursor. The shared suite therefore does not rely on that classification. If invalid-cursor histories are later added to the oracle, the model will need a transport option or stricter range rule.

### Exclusions as unchecked assertions

The observation is correct and worth preserving, but primarily in the **conformance harness documentation**, not as a new universal executable law.

Induction can prove that declared produced outcomes are inducible. It cannot prove an excluded outcome impossible merely by omitting its fixture. Each exclusion is therefore a proof obligation supported by:

- A structural argument,
- A property-specific law such as complete retention,
- Or a transport-specific falsification test.

Add that principle to `conformance.cljc`’s namespace documentation or manifest-validation commentary so it survives deletion of this plan. Do not require fixtures for excluded outcomes; such fixtures would contradict the exclusion.

## Original findings confirmed closed

- **`transport-error`:** the plan’s ruling is sound, and the contract amendment correctly distinguishes reportable operational exhaustion from fatal host failure producing no result.
- **Total `next`:** negative, non-integer, and beyond-tail positions are checked before `nth`; all branches return contract outcomes.
- **Retention falsification:** the adversarial manifest now lies consistently, retains a pre-eviction cursor, and asserts the specific `gap-on-complete-retention` failure in a committed test.
- **Cursor comparison:** the shared law compares observations; structural equality is confined to memory-log’s own representation test.
- **Post-close retention:** both kept-origin and fresh-owner-origin replays are included.
- **Reader-only histories:** a fixture is required rather than allowing a vacuous pass; only its terminal declaration remains to be added.
- **`full`:** its exclusion is now justified by migration parity, without claiming permanent `full` is invalid.
- **Rotation:** correctly named as unavailable future work requiring causality and query/index composition.
- **Ownership handoff:** correctly says the host supplies the local handle; `dao.space` does not create it.
- **Creation-spec policy:** own-namespace knobs are rejected, foreign qualified metadata is ignored, and the cases have explicit tests.
- **Manifest:** its declared operation partitions are complete and disjoint. `attach!` may be absent because validation requires only `descriptor` and surface-licensed operations.
- **Scope and portability:** in-memory process-lifetime retention remains the faithful v1 replacement, with no new reader-conditional hazard.

**Final judgment: request one plan correction.** After the retention-law population and terminal rules are generalized as above, the plan is ready to implement.
