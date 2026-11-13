Created-GMT: 2026-11-13 10:59:30 GMT
Created-Local: 2026-11-13 17:59:30 +0700 (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: Architect ruling — the P7-b tick counter scope (request-wide vs per-declare-site)

Role: Architect (read-only design consult; owner of the slice-3 design this round implements)

The P7-b ticks implementation stalled on a genuine ambiguity between two of YOUR OWN artifacts:

1. slice-3 design (collab/orch-lane/1794700000000-architect-safepoint-slice3-design.gpt-6.1-sol.findings.md): per the implementer's reading, describes a REQUEST-WIDE counter with the threshold carried per hook instruction.
2. docs/design/yang.php.md §12/§15 P7-b row + the implementation brief: per-declare-site counting (declare(ticks=N) is file-scoped in PHP; statements in declaring files tick).

The implementer (part C, 1794567390000 findings, branch p7b-ticks @ 38375bf7) declined to choose: the acceptance bar is "tick counts equal PHP's for the statement fixture", so the parity question is EMPIRICAL, not taste.

Rule with evidence:
1. What PHP 8.3's Zend executor ACTUALLY does — counter scope (per-op_array? per-current-file? reset semantics across includes/functions), citing php-src PHP-8.3 zend_execute.c / compile_time handling (quote file:line or the mechanism precisely).
2. Which reading of the slice-3 design is correct (re-read your own text; if it was ambiguous, say so plainly and re-state the intended profile semantics for ticks-profile + php.sp/line operands).
3. The decision table the implementer must follow: counter ownership, reset points, N semantics under nested declares, generator-resume interplay (already ruled in slice-3: pending after-hook on eventual normal completion), include-site handoff implications.
4. Whether the staged fixtures' hand-derived expectations (programs_p7b.cljc: sequence ATTBT, loop ATAT, call FT, generator ATBT) match PHP 8.3 under your ruling — and if the oracle pass (php:8.3, shiva, --p7 group) would be the tie-breaker, say which fixture rows the oracle MUST confirm.

Deliverable: findings to collab/yang-php/1794567570000-architect-ticks-counter-ruling.gpt-6-astra.findings.md (or sol thread — pick the available lane; you are the consult author). Read-only, no code.
