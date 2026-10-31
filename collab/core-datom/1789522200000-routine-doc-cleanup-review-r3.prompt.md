Created-GMT: 2026-09-15 21:30:00 GMT
Created-Local: 2026-09-16 04:30:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a0a6df-fb45-7122-aab4-06049faa93ba
# Task: Re-review of yin.vm.code-as-tuples.md named-variables terminology cleanup (r3)
Role: Routine Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 04:30:00 +07 | Status: active | Rationale: same reviewer, resumed session, re-review after fixes

## What changed since your r2 review

Fixed both remaining findings:
1. §7.7.2 (line ~1191): now explicitly distinguishes `:variable` lexical
   lookup (closure's captured environment) from `:global` resolution
   (`resolve-var` precedence, store → primitives → modules).
2. §2 grammar table's `:lambda` row: slot kind for `params` changed from
   `data` to `syms`. §7.4's `:slot-kind` row rule now explicitly defines
   `sym` and `syms` slot kinds (a `sym` slot must be a symbol, a `syms`
   slot must be a vector of symbols) — previously the rule table only
   covered `node`/`nodes`/`data`/`key`/`bool`.

## Task

Re-run your same three checks one more time. Confirm both fixes above
land correctly at their cited locations, and do a final full-document
sweep (De Bruijn, positional, environment/lookup, params typing) to make
sure nothing else is inconsistent. Also verify the §7.4 grid table's
edited rows (the `:slot-kind` row, now three physical lines) are still
padded to match their sibling rows' width.

## Deliverable

Explicit verdict: safe to commit as-is, yes or no, with any remaining
citations. This is a bounded terminology-and-consistency cleanup, not an
open-ended search — if you find nothing further wrong, say so plainly
rather than searching for additional unrelated concerns.
