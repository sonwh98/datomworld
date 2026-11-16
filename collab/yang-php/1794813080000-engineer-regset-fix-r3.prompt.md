Created-GMT: 2026-11-16 (resume time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2 — RESUME of thread 01a1e212)

# regset-fix r3 — the astra ruling landed: apply the authorized class-test correction

The architect ruled (collab/yang-php/1794798003000-architect-class-fatal-ruling.findings.md,
filed on origin/master): duplicate-class declaration is an UNCATCHABLE GUEST-FATAL
(php-src zend_error_noreturn E_COMPILE_ERROR); 053ae9a8 is correct; the class_model_test.cljc:102
assertion is stale. A test-only correction is AUTHORIZED with the exact mechanical replacement
in the ruling (assert {:php/fatal message :php/status 255 :php/uncaught nil} + diag errno 64 via
:php/diag). Scope limits: only the first assertion under "redeclaration, a missing or final
parent, and a non-interface refuse"; keep the neighboring parent/interface assertions and the
uncaught-message helper unchanged.

Then validate per the ruling's validation list:
1. declare-and-inherit-test green; the full class-model namespace green.
2. The eval-collision regressions (repeated class-entry invocation; shutdown without guest
   unwinding) green.
3. bb test:sub:clj yang.php -> expect 0 failures, 0 errors (this is the closure gate).
Same contract as r2: no commits, no pushes (leave edits; the seat publishes); per-run log custody
(target/regset-r3/); findings file with gate numbers.
