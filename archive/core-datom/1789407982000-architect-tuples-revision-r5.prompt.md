Created-GMT: 2026-09-14 17:46:22 GMT
Created-Local: 2026-09-15 00:46:22 +07
Coding-Agent: claude
Session-ID: 8677b374-bb75-4103-afab-4b65727a76c1 (resume — your own drafting session for docs/design/yin.vm.tuples.md)

# Task: Revise the tuple code-representation design (round 5 — two findings)

Role: Architect — design document only. No implementation, no staging, no commits.

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-15 00:46:22 +07 | Status: active | Rationale: you authored all revisions; the reviewer is gpt-6-astra (different family), independence preserved. The owner has directed iteration until agreement.

## Context

Your round-4 revision was re-reviewed: **REJECT, 2 P1s** — four of six round-3 findings resolved and verified sound (parent-event reference executed and passed; parameter discharge; [address context] convergence; FFI capability row), and the reviewer states the remaining implementation work in §10 no longer justifies rejection on its own. Only two identity defects block acceptance; both fixes are specified. Full findings at:

`collab/1789407733000-architect-tuples-rereview-r4.gpt-6-astra.findings.md`

Read that file first.

## Task

Revise `docs/design/yin.vm.tuples.md` against exactly these two findings:

1. **Portable attempt identity is scoped to no expander (§8.2, §8.4).** The attempt record's counter is per-expander without the expander's identity or incarnation, so two expanders — or one restarted expander replaying the same source batch — observing the same medium/batch/member/call-path with the same macro and output produce identical records, one record address, and conflated attempts. This is record-value equality; no better hash fixes it. Fix per the reviewer: give each logical attempt a portable identity scoped to an explicit **expander incarnation** — `[expander-incarnation counter]` — included in the addressed record; staged retries reuse the identity, a distinct execution receives another; the incarnation lives in explicit composition/state data (name what holds it and how it survives restart differently from a retry). The parent-record chain stays as is. Preserve the governing contract's one-record-per-attempt guarantee (`yin.vm.macro.md:666-689`).

2. **The harvest catalogue loses shared legacy definition identity (§8.5, §9.1 adapter).** A legacy batch may contain one definition entity shared through multiple references (the codec's `seen-eids` dedup — the reviewer exercised it: one `:yin/type` row, operand refs `[-100 -23 -100]`). Inline conversion turns it into several `[j path]` occurrences; the old harvest and declaration catalogue give the shared entity ONE declaration ordinal (`yin.vm.macro.md:247-265`), and fabricated stand-ins may select catalogue entries (`:255-275`), so duplicated entries are observably wrong. Fix per the reviewer: preserve admission identity outside canonical trees — a legacy catalogue entry represents **one original definition associated with all its `[j path]` occurrences**, harvested once, every associated occurrence carrying the same declaration ordinal; occurrence coverage validated through that mapping; tuple-native producers use singleton occurrence groups.

Touch whatever sections these require (§8.2, §8.4, §8.5, §9.1 adapter row, §10 as needed). Everything else stands as reviewed.

## Constraints

- Edit only `docs/design/yin.vm.tuples.md`. Stage and commit nothing.
- Rules stated as rules; calls made and stated; no option menus. Cite file:line for every load-bearing claim about current code; re-verify before citing.
- If you believe a finding cannot be fixed without an owner ruling, say that explicitly in the document and your report rather than inventing a ruling.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and named timezone>
Coding-Agent: claude
Session-ID: 8677b374-bb75-4103-afab-4b65727a76c1

Then: the new line count; a finding-by-finding note (2 rows: fixed how); sections touched; anything verified against source versus taken from the review.
