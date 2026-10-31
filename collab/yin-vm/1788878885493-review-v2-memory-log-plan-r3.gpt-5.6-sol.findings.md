I’ll inspect the r3 law specification end to end, especially population termination, restricted-domain behavior, and whether post-close replay reuses a handle whose state still corresponds exactly to the accepted-value set.

I’m applying the repository’s `code-review-pr` checklist for this final plan pass, with the review scoped to the generalized retention law and the implementer handoff.

I’m applying the repository’s code-review checklist to the revised law, focusing on whether each admitted transport shape is genuinely exercised rather than passing vacuously. I’ll keep this read-only and won’t rerun tests.

Completed-GMT: 2026-09-08 14:50:23 GMT  
Completed-Local: 2026-09-08 21:50:23 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Architect Review — generalized retention law r3

## Verdict

**Not quite ready to implement. One P1 remains in the generalized law.**

The earlier generalization defect is conceptually closed: the law now distinguishes accepted values from attempted values, handles a conforming `full`, and supports reader-only fixtures. However, population does not verify that observed append outcomes agree with the manifest. That permits the law to overlook precisely the false exclusions it is intended to police.

## Findings

### P1 — Population silently accepts outcomes the manifest excludes

**Location:** `collab/1788878712118-architect-v2-memory-log-r3.claude-fable-5-1.findings.md:186-190, 223-232`  
**Blocks implementation:** Yes

The population logic interprets every `full` as a legitimate stopping point and every `invalid-value` as a legitimate skip without first checking whether that outcome appears in the manifest’s `append! :produces`.

A concrete false-positive:

1. An allegedly unbounded transport excludes `full`.
2. Its faulty implementation accepts one value and then returns `full`.
3. Population records one accepted value and stops.
4. Both replays return that one value and `blocked`.
5. The retention law passes, despite directly observing an outcome the manifest excludes.

The same problem applies when a transport excludes `invalid-value` but unexpectedly rejects one of the supplied values. Existing induction coverage does not save this: it observes only the manifest’s fixtures, not the append calls performed by the retention law.

Concrete correction:

- Before interpreting any append outcome, require it to be in `[:operations :append! :produces]`.
- An outcome outside that set must produce an `:undeclared-population-outcome` or equivalent violation.
- A declared `full` may stop population and proceed to replay.
- A declared `invalid-value` may be skipped if the manifest field represents candidate values.
- `closed` or `transport-error` during fresh-handle population remains a setup/conformance failure even when declared, because the law could not establish its required populated history.

There is also a terminology choice to settle:

- If `:retention-values` means, as line 184 says, “values this transport accepts,” then `invalid-value` should be a fixture defect.
- If skipping `invalid-value` is intentional, call them candidate or probe values and document that meaning.

The population helper should return structured failure information, including the offending result, rather than only attaching `::defect true` metadata to the accepted vector.

### P2 — Reader-only fixtures can still make the behavioral law vacuous

**Location:** `collab/1788878712118-architect-v2-memory-log-r3.claude-fable-5-1.findings.md:194`  
**Blocks implementation:** No, but should be settled in the implementer brief

The writer path explicitly requires at least one accepted value, while the reader-only path does not explicitly require `:expected` to be a non-empty vector. An empty fixture can therefore pass without exercising retained history.

If `run-retention-laws` is intended to provide behavioral evidence rather than merely validate the declaration’s shape, require a non-empty `:expected` vector for reader-only fixtures too. If intentionally allowing an immutable empty history, state that this is a valid but behaviorally vacuous case whose completeness remains a structural proof obligation.

## Confirmed closed

The revision otherwise closes the previous finding:

- The origin cursor is minted before population, preserving the observation needed to expose eviction.
- `expected` contains only values whose append returned `ok`.
- A declared finite complete writer may return `full`; the accepted prefix is replayed and the open terminal remains `blocked`.
- A restricted value domain is representable without harness-invented values.
- Reader-only histories supply their handle, expected sequence, and explicit `blocked`/`end` terminal.
- Kept-origin and fresh-`:oldest` replays compare observations, not transport-owned cursor representations.
- Owned writer fixtures are replayed after close; externally supplied reader fixtures are not closed.
- The falsified ring-buffer manifest still exercises the original blind spot: its pre-eviction cursor observes `gap`.
- `pos > tail → invalid-cursor` remains contract-legitimate for this transport. Cursor validity is transport-owned, and the plan accurately records the shared-oracle limitation.
- Recording exclusions as proof obligations in the conformance namespace is appropriate; it does not require a new general fixture mechanism.
- The contract amendment concerning observable exhaustion versus fatal no-result host failure remains consistent.

## Required implementer-brief addition

Before implementation, add the manifest-outcome gate above and require tests demonstrating all four cases:

- excluded `full` observed during population fails;
- excluded `invalid-value` observed during population fails;
- declared `full` stops population and the accepted prefix passes;
- declared `invalid-value` permits restricted-domain population under the chosen field semantics.

After that correction, the plan is ready to implement.
