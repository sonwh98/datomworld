Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: P7-b part D — implement ticks TO THE ARCHITECT RULING (the counter-scope question is CLOSED)

Role: Engineer (implementation, yang.php). Branch p7b-ticks @ 38375bf7 (pushed; ANTLR pre-built here).

## THE SPEC — read these first, in order
1. collab/yang-php/1794567570000-architect-ticks-counter-ruling.gpt-6-astra.findings.md — **AUTHORITATIVE**. Its normative decision table governs every choice: ONE REQUEST-OWNED counter (guest state, no host global), increment-on-selected-normal-completion with `new-count >= site-N THEN reset-to-0-before-dispatch` (not equality/modulo), reset ONLY at request start + threshold firing (never at declare entry/exit, calls, includes, registration, generator switch, context restore), empty registry still counts, per-site lexical N carried by each mark (block directives scope-and-restore; semicolon form persists; a directive itself can tick: the ATB case), Zend's statement-selection + adjacent-suppression + excluded categories (NOT a blanket every-AST-statement rule), functions/closures keep body-site thresholds and contribute to the same counter from unticked callers, generator: pending after-hook runs on eventual normal completion with the then-current counter, NEVER snapshot/restore counts with generator context, per-callback re-entry guard (not a global busy rule).
2. CORRECTION OF RECORD: earlier briefs' phrase "a count is per declare-site" is WRONG (the ruling's P1 line names it). Anything in part C's ticks-profile that encodes the wrong ownership must be fixed per the table.
3. docs/design/yang.php.md §12 + §15 P7-b; slice-3 design for the mechanism you're consuming (already implemented in the shared core, part A).

## Scope for THIS session (tight — stop at any of these and report)
A. php.sp/line implementation + tick registry + firing per the table; declare(ticks) wiring through compilation (site selection, thresholds on marks, suppression rules); request counter in existing guest execution state; ticked code crossing calls/includes/generators per the table.
B. GREEN the four non-include staged fixtures (sequence, loop-body, function-call, generator-resume — expect ATTBT/ATAT/FT/ATBT).
C. ADD the N>1 discrimination fixtures the ruling requires (P1: staged ones are all N=1): at minimum a threshold-2 firing case, a block-vs-semicolon scoping case, a cross-call continuation case (ticked function body called from unticked code), and the semicolon directive self-tick ATB case. Red-first each; hand-derived per the table; keep :oracle :pending.
D. UPDATE docs/design/yang.php.md §12's stale sentences (hook-before sites; generic extension refuses non-lambdas) to the implemented reality — the ruling's P2 line; doc edit is in-round work, not scope creep.
E. The include site-set handoff and its fixture: EXPLICITLY OUT — part E slice. ticks_test include case may stay red/deferred; record its status verbatim.
F. Gates, SEQUENTIAL, generous timeouts (300-600s each): tick fixtures; yang.safepoint-test stays green; python trace-basic + linked-tracing-profile green; yang.php lowering/prelude focused; e2e fast; kondo 0/0 + cljstyle clean on touched files.

## Rules
- Conventional commits on p7b-ticks; push as you go. No merges. Design-table conflict -> stop + report.
- Findings: collab/yang-php/${TS}-engineer-p7b-ticks-d.gpt-6.1-terra.findings.md (per-fixture red->green chronology; file:line; which table rows each fixture discriminates; gate timings).
