---
name: exception-track-reviewer-workflow
description: Structured review process for exception-track reviewer briefs — independent review of codex-implemented fixes with 5-question structure, verdict format, and findings-file append
source: auto-skill
extracted_at: '2026-11-13T14:42:29.164Z'
---

# Reviewing exception-track codex fixes

Reviewer briefs at `collab/exception-track/*-reviewer-*.prompt.md` ask for independent review of a codex-implemented fix (you are non-codex per the independence rule). These are **read-only** (git + file reads only, no test runs) with a structured deliverable appended to the findings file.

## The 5-question structure

Every reviewer brief asks these five questions in order. Answer each with evidence from the code and findings:

1. **Mechanism soundness**: Does the claimed mechanism explain ALL failure modes? Check the findings' evidence for red-phase repro and post-fix greens. Is the N→0 story consistent with per-scope results?

2. **Semantic neutrality on other hosts**: Is the fix identity/no-op on JVM/cljs (or other hosts)? Search update sites for the affected data structure. Confirm provenance/sibling preservation claims if applicable.

3. **Scope discipline**: One src line (or minimal), no test expectations touched, no goldens touched. Verify with `git diff <base>..<sha> -- test/`.

4. **Slow-tier verification**: Decide whether the fix's verification is sufficient WITHOUT a slow-tier rerun, or whether landing must be preceded by one (name the exact command). The merge bar (owner ruling 2026-11-07) is "previously-failing tests re-run green" — slow tier runs at G4 (release) or when slow tests are part of the fix scope. If the fix's mechanism is not timing/concurrency-dependent, slow-tier tests do not exercise a different failure mode.

5. **Contradictions**: Anything in the findings that contradicts the branch or the rc claims? Check recorded command lines, not just the summary. The findings should be honest about what is/isn't evidence (e.g., "rc 0 but 0 tests selected" is not evidence).

## Verdict format

End with `APPROVE` or `REQUEST CHANGES` (numbered items). Then:
- **One sentence**: strongest reason it is/isn't safe to land as-is.
- **One line**: overall capability impression of the model against the review precedent (if this is the model's first in-repo round, team.md records it as pending evaluation).

## Appending to the findings file

The brief specifies a heading like `## REVIEW (qwen4-max-preview)` (use your model tag). Append under this heading, after the existing findings content. Do not edit the findings' own content — only append your review.

Use `cat >>` with a heredoc to append:
```bash
cat >> /path/to/findings.md << 'REVIEW_EOF'

---

## REVIEW (<model-tag>)

<your review content>

— <model-tag>, <date>
REVIEW_EOF
```

## Key judgment calls

- **Slow-tier rerun**: If the fix's mechanism is allocation-shape, not timing/concurrency-dependent, and the previously-failing tests are green on all three hosts, the merge bar is satisfied without slow-tier. The nightly will re-prove everything within hours.
- **Honesty about evidence**: If the findings disclose that a test run selected 0 tests or that a mishap occurred, note this as honest disclosure, not a contradiction.
- **Mechanism completeness**: If the mechanism explains all failure modes and the per-scope results are consistent with the systemic nature of the bug, the N→0 story is credible even if not every namespace was individually verified.

## Example structure

```markdown
## REVIEW (qwen4-max-preview)

**Verdict: APPROVE**

### Answers to the five questions

**1. Mechanism soundness: YES.** <explanation with evidence>

**2. Semantic neutrality on JVM/cljs: CONFIRMED.** <search results, lifecycle analysis>

**3. Scope discipline: CONFIRMED.** <git diff evidence>

**4. Skipped slow guards: SUFFICIENT WITHOUT slow-tier rerun.** <merge bar reasoning>

**5. Contradictions in findings: NONE.** <evidence honesty assessment>

### Strongest reason it IS safe to land

<one sentence>

### Capability impression of <model-tag>

<one line comparing to precedent>

— <model-tag>, <date>
```
