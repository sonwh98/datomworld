Created-GMT: 2026-11-16 (dispatch time)
Coding-Agent: codex
Session-ID: 01a1e24b-4b1f-73b2-b0fd-de9edd83ccc6 (codex astra thread, jupiter)

# Task: Architect ruling — duplicate-class declaration: guest-fatal contract vs the class_model_test assertion

Role: Architect (read-only consult). You ruled the ticks-SCC shape (1794772328000) and the
lifecycle oracle calls an eval collision uncatchable; this question borders that contract.

## The conflict (regset-fix r1 attribution, verified by targeted revert)
Commit 053ae9a8 (inside the a717da28..8a44b87a window) replaced the duplicate-class
`php/throw-error` (a catchable guest error carrying the message "Cannot declare class a,
because the name is already in use") with a diagnostic + `php/guest-fatal` — the fatal maps to
no `:php/uncaught` under php/run-main. The pre-existing test
yang.php.antlr.class-model-test/declare-and-inherit-test asserts the OLD catchable message and
now fails. An overlay restoring the old branch passes the test but, per r1, conflicts with the
eval-collision fatal semantics protected by the ticks-SCC ruling.

## Rule with (real PHP is the reference — cite php-src behavior in zend_compile.c for
duplicate class declarations: it is a fatal E_COMPILE_ERROR, uncatchable)
1. What is the INTENDED contract for a duplicate class declaration in admitted yang.php code:
   guest-fatal (uncatchable, matching php-src) or catchable error? If guest-fatal, is 053ae9a8
   correct and the TEST assertion stale (authorizing a test-expectation update to the fatal
   shape), or does the fatal need a diagnostic channel the test can observe?
2. Boundary check: does moving duplicate-class to fatal conflict with any protected invariant
   (§8.10.7 boundary transfer, the ticks-SCC ruling, the eval-collision atomicity record)?
3. The exact authorized change: the test expectation text/shape, or the implementation's
   diagnostic mapping — name the file and the assertion to change, so the engineer round is
   mechanical.
Deliverable: collab/yang-php/<ts>-architect-class-fatal-ruling.findings.md inline if write-blocked
(your session is read-only; the seat files it).
Evidence: ~/ci/dw-regfix at 8a44b87a (src/cljc/yang/php — the class admission + php/guest-fatal
mapping; test/yang/php/antlr/class_model_test.cljc:102), the r1 findings
collab/yang-php/1794794448000-engineer-regset-fix-r1.gpt-6.1-terra.findings.md (on origin,
branch fix/yangphp-regset), 053ae9a8's diff.
