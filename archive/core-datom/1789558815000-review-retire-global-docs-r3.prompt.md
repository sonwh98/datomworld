Created-GMT: 2026-09-16 04:40:15 GMT
Created-Local: 2026-09-16 11:40:15 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a0a6df-fb45-7122-aab4-06049faa93ba
# Task: r3 review — confirm the 4 remaining r2 findings are fixed
Role: Routine Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 11:40:15 +07 | Status: active | Rationale: same reviewer, resumed session, third and hopefully final pass

## What changed since r2

All four remaining findings addressed, each verified against source
before editing:

1. **The §7.5 `:var` resolution sentence** (was around old line 1163,
   now `docs/design/yin.vm.code-as-tuples.md:1166`) — verified
   `engine.cljc:46-65`'s `resolve-var` throws (via `fail`) when nothing
   resolves, never returns nil. Rewrote to say it throws, cite
   `engine.cljc:46-65`, and removed the incorrect `§7.4` cross-reference
   (this is §7.5 territory, the instruction-vector rules, not the AST row
   rules).
2. **The §4.4 sharing paragraph** — fixed "seq/list" to "vector/seq"
   (matching the actual §4.2 table, verified again), and added the
   ambient-print-binding qualifier: the injectivity guarantee holds for
   ordinary values under default print bindings, not unconditionally —
   a row whose slots include scalars hashed inside a non-default
   print-var binding isn't covered.
3. **The `linearize.cljc lower` claim** — verified it exists at
   `linearize.cljc:162`, operating on `[e a v t m]` datoms (the OLD
   representation). Rewrote to say the EXISTING datom-based `lower`
   exists, but the row-set-based lowering this design specifies (§9.1)
   is what's unverified/unadapted — not that `lower` doesn't exist at
   all.
4. **The occurrence-aware test's whole-grammar overclaim** — rewrote to
   explicitly separate what the test actually exercises (one
   single-root `:lambda`/`:application` fixture) from the REASONING for
   why the technique should generalize (path-prefix walking doesn't
   depend on which tag sits at a path, unlike the row-only rule's
   per-tag edge clauses) — labeled explicitly as reasoning, not tested
   coverage.

Also swept the whole file again for "seq/list" (only hit was the one
just fixed) and double-checked the §7.5 table's grid width wasn't
disturbed (unaffected — the edited sentence sits after the table, not
inside a row).

## Task

Re-verify these 4 fixes against the same citations you gave in r2. Then
do one more full sweep of both files — you've now found real issues on
every one of your first two passes, so don't assume this is clean by
default. If it's actually clean this time, say so plainly.

## Boundaries

Read-only. Do not edit anything.

## Deliverable

Explicit verdict: safe to commit as-is, yes or no, with citations for
anything still wrong. Produce the complete deliverable now.
