Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: P7-b part G — production derivation + include handoff + statement-selection completeness

Role: Engineer. Branch p7b-ticks @ 35205c7f (pushed; 8/8 fixtures green, verified by the
orchestrator). Read FIRST: collab/yang-php/1794568768000-...-ticks-f findings' Remaining-gaps
section (in this worktree as 1794567519000/1794568768000 file — locate via ls) + the astra
decision table (1794567570000). Work mode as before: edit against the map, push per green,
sequential gates, NO re-analysis budget.

## Scope (the F-gap list, ordered)
1. PRODUCTION DERIVATION: tick profile applied to production execution paths (linked/bundled
   publication) not just e2e_harness — the design's site-set publication (canonical published
   rows carry no marks; include site-set handoff with the unit explicit).
2. INCLUDE HANDOFF + the deferred linked include fixture (ticks_test include-handoff-test):
   make it green with generous timeout (sequential, 600s; if the dao.jing-class stall recurs
   on this lane, record and the orchestrator reruns remote).
3. STATEMENT SELECTION completeness: Zend excluded categories, compound statements, adjacent
   suppression (decision table rows) — add fixtures per these (red-first, :oracle :pending):
   compound (if/while body selection), adjacent-suppression, hoisted-function threshold
   persistence after a semicolon directive, nested N=0 region (accumulates, no clear).
4. Registry semantics edge: unregister during execution, membership change mid-request (per
   table's reentry row) — fixture each.
5. Gates: FULL fixture set green (tick-fixtures + new + include), additivity lines, kondo/cljstyle.

## Rules
Push per green commit (you have origin access this session — the F block was the delegate
approval layer, commits ride the orchestrator's push when needed; say so if blocked). Fixtures
stay :oracle :pending — NO docker. Findings: collab/yang-php/${TS}-engineer-p7b-ticks-g.gpt-6.1-terra.findings.md.
