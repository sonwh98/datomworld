Created-GMT: 2026-11-14 (dispatch time)
Created-Local: 2026-11-14 ~08:0x +0700
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 13 — implement the redeclaration semantics TO THE ATOMICITY RULING (11-fixture matrix)

Role: Engineer. Branch eval-bridge (pushed). THE RULING IS THE SPEC, read first:
collab/yang-php/1794615000000-architect-atomicity-ruling.findings.md — incremental declaration
registration with an uncatchable guest-fatal collision (NO preflight, NO rollback); collision
checks belong to CALLER-TASK declaration binding; publication/admission stay code-only.

## Key implementation consequences (from the ruling, verbatim obligations)
1. php/declare-function! collision branch (prelude.cljc:2767): catchable Error -> the guest-fatal
   route (preserve the old binding).
2. php/declare-late! (:3963) + hoist-class!/declare-class! distinction: distinguish THIS
   invocation's successful early binding from a pre-existing declaration — invocation-owned
   state, never persistent state in the cached artifact.
3. unit-body/check-hoisted! (lower.cljc:3710): ordered caller-side binding prefix followed by a
   fatal action is acceptable — a compiler-discovered fatal must NOT discard the observable
   registration prefix (no wholesale rejection).
4. Preserve Zend's early-binding order and the diagnostic distinctions (E_ERROR runtime vs
   E_COMPILE_ERROR compile-time).

## The 11-fixture matrix (the ruling's Required-fixtures table — implement ALL, red-first where
## hand-derived, run each fatal fixture conceptually as a separate request)
Caller-collision with before/occupied/after; earlier-successful-eval + same collision; deferred
class collision with fresh classes around it; conditional collision false/true; mixed
class/function before a top-level function collision; two same-name functions in one eval after
a fresh declaration; class collision across two eval invocations; reinvoke the same admitted
artifact; case variants and namespaces; syntax-error and ordinary-runtime-exception controls;
invalid site metadata/admission control. Every collision case asserts: shutdown execution, 255,
absence of catch/finally continuation, function_exists/class_exists(...,false) visibility.

## Definition of done
The 11-fixture matrix green against the ruling semantics (some rows red-first then green);
the existing eval suites (service/transport/admission/coordinator/emitter/publication/
scope-cleanup/retention) stay green; kondo/cljstyle clean; push per green.
Findings: collab/yang-php/${TS}-engineer-eval-bridge-r13.gpt-6.1-terra.findings.md.
php/eval-run in caller task + expression-path conversion: next slice after this matrix lands.
