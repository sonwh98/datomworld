Created-GMT: 2026-09-15 21:14:28 GMT
Created-Local: 2026-09-16 04:14:28 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a0a6df-fb45-7122-aab4-06049faa93ba
# Task: Re-review of yin.vm.code-as-tuples.md named-variables terminology cleanup (r2)
Role: Routine Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 04:14:28 +07 | Status: active | Rationale: same reviewer, resumed session, re-review after fixes

## What changed since your last review

The orchestrator (not a delegate — the interactive seat) fixed every item
your r1 review flagged:

1. §1's worked example (`[A :lambda [x] B]` / `[D :variable x]`, was
   `1`/`0`).
2. §2.5's exclusions bullet — removed the self-contradiction; parameter
   and variable names are now stated as retained, not excluded.
3. Removed the now-obsolete "variable names" side-table row (§4.2 area) —
   since names are inline in the canonical row, no side-table join is
   needed for a `:variable` row's display name.
4. The `yin/def` rewrite caveat — `:variable` indices → `:variable` names.
5. The alpha-equivalent sharing claim (§4.4) — rewritten to state the
   accurate, weaker claim: sharing now requires exact identity of names,
   not alpha-equivalence.
6. The vector-level `:var` bounds sentence — "index-bounds"/"out-of-range"
   → "binding rule"/"unbound ... name".
7. The `:global` extraction comment — "bound variables carry no name" →
   "carry a name too, but a bound one contributes no obligation".
8. Two "positional environment" mentions (§7.7.3 and the open-items list)
   describing the CESK context abstraction — corrected to "captured
   environment" (it's a named map, not positional).
9. `semantic/load-image`'s row — "positional frame binding" → spelled out
   as argument-to-named-parameter binding by position.
10. The open-items "positional binding rule" phrase — same clarification.
11. §3's Named Variables note — "string/symbol" → "symbol" (matches the
    §2 grammar table's `sym` typing).
12. The codec migration-notes row — restored the `:yin/root` and `:eid`
    removal facts your r1 flagged as unrelated scope creep that shouldn't
    have been dropped.

## Task

Re-run your same three checks (internal consistency, no scope creep, grid
table formatting) against the current file state. Confirm each of your 12
r1 findings above is actually resolved as described — don't take the
orchestrator's summary on faith, verify each file:line yourself. Also
sweep the whole file again for anything remaining (you may have missed
something in r1 too), including a fresh grep for "De Bruijn" and
"positional" to judge each hit on its own terms as you did before.

## Deliverable

Explicit verdict: safe to commit as-is, yes or no, with any remaining
citations. Produce the complete deliverable now.
