Created-GMT: 2026-09-16 04:41:49 GMT
Created-Local: 2026-09-16 11:41:49 +07 (Asia/Ho_Chi_Minh)

# Task: Architect sign-off — retire the :global AST tag (design ruling reversal)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-16 11:41:49 +07 | Status: active | Rationale: Architect sign-off gate, required before commit
Session-ID: da8752ab-ade5-4da4-8185-dd87be0f6244

Perform a read-only architecture review of four uncommitted working-tree
diffs, all part of one coherent design decision the owner made tonight:

```
git diff -- docs/design/datom.world.md docs/design/yin.vm.code-as-tuples.md \
            src/cljc/yin/vm.cljc test/yin/vm_test.cljc \
            test/dao/space/query_test.cljc
```

## Context

The owner ruled tonight, in a live design discussion, to retire the
`:global` AST tag entirely — a tag added earlier tonight (and reviewed
through several rounds) to distinguish free variable references from
lexically-bound ones. Read `docs/design/yin.vm.code-as-tuples.md` §4.5
("Free variables are queried, not tagged") in full for the complete
rationale before reviewing anything else — it is the single source of
truth for this decision's reasoning, including two real mistakes made
and corrected during the discussion (the row-sharing trade-off was first
mischaracterized, then corrected) and, separately, a genuine soundness
gap found in the demonstration itself (row-only queries can undercount
free names when a name is free at one occurrence and bound at another),
which was then proven fixable by a richer occurrence-joined query rather
than by resurrecting `:global`.

This unit went through three rounds of Routine Review on the docs alone
(gpt-6-astra, session `01a0a6df-fb45-7122-aab4-06049faa93ba`) — r1 found
4 issues, r2 found 4 more (including a real correctness gap: the
occurrence-aware rule in the test is not root-scoped, so a production
version must thread `?root` through every rule, not reuse the test's
rule set unmodified), r3 verdict clean. The codec removal (deepseek-v4-pro)
and the two Datalog demonstration tests (deepseek-flash, adversarial —
found and disclosed a real overcounting gap in the row-only rule's
tag coverage) were both clean on their first review. Full chain in
`docs/orchestrator-log.md` once this lands (not yet written).

## Read first

- `docs/design/yin.vm.code-as-tuples.md` §4.5 in full
- `docs/design/datom.world.md`'s new "Derive, don't persist" principle
- The five diffs listed above
- `collab/1789552400000-storage-indexing-free-variable-query-demo.claude-sonnet-5.findings.md`
  and
  `collab/1789554609000-storage-indexing-occurrence-aware-free-variable-query.glm-5.3.findings.md`
  for the two demonstration tests' full findings

## Evaluate

Per your role definition, plus specifically:

1. **Is retiring `:global` architecturally sound?** Does §4.5's argument
   hold up, including its disclosed limitations (the not-root-scoped
   rule, the row-only rule's tag-coverage gap, the unverified
   segment-level analogue)? Does anything in the codebase still assume
   `:global` exists as a current or near-term tag?
2. **Is the "Derive, don't persist" principle correctly scoped?** Does it
   overclaim, or does it now correctly require query-scope correctness
   and acknowledge real costs (query complexity, indexing work)?
3. **Reviewer-independence and process integrity check**: this doc went
   through 3 review rounds finding real issues each time (some the
   orchestrator introduced while fixing PRIOR findings, including at
   least one factual error the orchestrator caught and corrected only on
   a second look). Does the FINAL state hold together as a coherent,
   accurate document, or does the iteration itself suggest remaining
   fragility you can find that the same reviewer, now three rounds deep
   into the same document, might be fatigued past catching?
4. Standard evaluation: foundational invariants (does dropping `:global`
   weaken or strengthen "no hidden global state"?), ownership boundaries,
   host portability (CLJ/CLJS/CLJD — the codec change and both new tests
   are JVM-verified; note if CLJD verification is still pending and
   whether that's acceptable for this unit), migration risk, design
   contradictions.

## Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report per your role's format, and end with an explicit sign-off verdict:
APPROVE, APPROVE-WITH-FINDINGS (nonblocking), or BLOCKED.
