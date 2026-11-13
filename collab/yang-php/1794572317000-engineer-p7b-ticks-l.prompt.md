Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: P7-b part L — statement-selection completeness + registry semantics (final implementation slice before the oracle pass)

Role: Engineer. Branch p7b-ticks @ f79f3554 (origin; include gate green, verified).
SPEC: the astra decision table (1794567570000 findings) — rows on Zend excluded statement
categories, adjacent suppression, compound-statement selection, N=0 regions, hoisted function
threshold persistence, registry membership change + unregister-while-executing per-entry
reentry. Reference: php-src PHP-8.3 citations therein (zend_compile.c:2518-2522 exclusion list,
:2110-2121 adjacent suppression, :9659-9668 top-level decls).

## Implement (fixtures red-first, :oracle :pending; code to green them where PHP behavior is
## already settled by the table; where a case needs real-PHP adjudication, implement per the
## table's explicit ruling and keep the fixture pending the oracle rather than guessing)
1. Excluded categories: the marked statement set matches Zend's list (top-level function/class
   declarations excluded; echo/expression/yield/loop-body selected; do/while, for, foreach,
   switch selection per the table).
2. Adjacent suppression: no duplicate tick emission for adjacent marked sites (table row).
3. Hoisted function keeps the preceding semicolon directive's lexical threshold.
4. Nested N=0 region: contributes no sites of its own, does not clear accumulated count;
   enclosing nonzero context still selects the declare-construct completion (ATB family).
5. Registry: unregister during execution; membership change mid-request; per-entry reentry
   guard (callback re-entering via a call that itself ticks — its own site counts run).
## Gates: tick-fixtures + include-handoff + siteset + safepoint all stay green; kondo/cljstyle.
Push per green. Findings: collab/yang-php/${TS}-engineer-p7b-ticks-l.gpt-6.1-terra.findings.md
(table-row -> fixture mapping; which rows the ORACLE must adjudicate — that list drives my
shiva corpus).
