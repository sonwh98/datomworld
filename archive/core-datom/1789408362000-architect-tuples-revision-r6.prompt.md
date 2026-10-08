Created-GMT: 2026-09-14 17:52:42 GMT
Created-Local: 2026-09-15 00:52:42 +07
Coding-Agent: claude
Session-ID: 8677b374-bb75-4103-afab-4b65727a76c1 (resume — your own drafting session for docs/design/yin.vm.tuples.md)

# Task: Revise the tuple code-representation design (round 6 — two rule fixes)

Role: Architect — design document only. No implementation, no staging, no commits.

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-15 00:52:42 +07 | Status: active | Rationale: you authored all revisions; the reviewer is gpt-6-astra (different family), independence preserved. The owner has directed iteration until agreement.

## Context

Your round-5 revision was re-reviewed: **REJECT, 2 P1s** — but both shapes were endorsed ("the proposed shapes are appropriate"; occurrence groups "address the original aliasing problem"; staged-retry identity reuse "should" hold; the cross-doc dependency correctly recorded). Both findings are defects in specific rules inside the accepted shapes. Full findings at:

`collab/1789408162000-architect-tuples-rereview-r5.gpt-6-astra.findings.md`

Read that file first. The orchestrator verified the reviewer's executed claim: a memory-log `:dao.stream/newest` cursor is `(count (:values s))` — a pure observation (`memory_log.cljc:105-110`); two reads without an intervening append return identical positions, so the cursor-reading recipe cannot allocate uniqueness.

## Task

Revise `docs/design/yin.vm.tuples.md` against exactly these two findings:

1. **The incarnation allocator observes where it must allocate (§8.4.1, §10.3).** "Log medium identity + log cursor at construction" is unsound: cursor reads reserve nothing (two constructions — concurrent, sequential, or restart-before-first-append — obtain identical incarnations and mint the same attempt `[incarnation 0]`). Fix per the reviewer: replace the cursor-read recipe with an explicit **unique allocation mechanism** — e.g., a durable incarnation entity allocated through the log's transactor whose qualified identity is the incarnation, or a composition-minted unique token independent of any cursor. If log positions are used at all, they must be positions **allocated by a committed construction record**, never observed cursor values. Define retry behavior for the allocation itself (what a construction retry does to the incarnation — an idempotent re-read of the committed record vs a new allocation — say which and why). The staged-flush retry rule (same ctx ⇒ same attempt identity) stays unchanged. Keep §10.3's cross-document note accurate for whatever mechanism you choose.

2. **Harvest indices conflate two sequences (§8.5).** `:yin/harvest` lists every definition group and the rule made `:decl k` the group's index in that all-definition catalogue — but the governing contract numbers only **source macro definitions** into `:declared`; plain definitions remain executable `yin/def` nodes (`yin.vm.macro.md:255-278`). With a plain definition before macro `m`, legacy gives `m` ordinal 0 and your rule gives 1, so a fabricated stand-in (permitted by the contract) changes meaning; applying stand-in replacement to plain groups would also delete their runtime definition behavior. Fix per the reviewer: keep **two distinct sequences** — the harvest catalogue contains all original definition groups in admission order (harvest/redefinition), while a **declaration catalogue numbers only macro-declared groups**, preserving their relative order; all occurrences of one macro group receive that group's macro-only ordinal; plain groups participate in harvest/redefinition but remain ordinary `yin/def` syntax and are never replaced by stand-ins. Update the group validation, the adapter row, and any cross-references (§2.5, §9.1, §10) to match.

Touch whatever sections these require. Everything else stands as reviewed.

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
