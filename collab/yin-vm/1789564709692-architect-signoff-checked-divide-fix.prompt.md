Created-GMT: 2026-09-16 13:18:29 GMT
Created-Local: 2026-09-16 20:18:29 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: c8b7c99f-1380-482a-91d2-bdb56a1723b9

# Task: Architect sign-off on the host-uniform division-by-zero fix

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-16 20:18:29 +07 | Status: active | Rationale: architecture sign-off gate before commit, per this branch's convention tonight

Perform a read-only architecture review of the current uncommitted
working-tree diff.

Read first:
- `docs/design/yin.vm.divergence-register.md` (the intent this fix
  claims to restore)
- `git diff src/cljc/yin/vm.cljc test/yin/vm_test.cljc`
- `collab/1789564392273-review-checked-divide-fix.gemini-3.1-pro-high.findings.md`
  (independent adversarial review, verdict: ready for sign-off, no
  modifications required — treat as a claim to verify, not authority)

## Context

`yin.vm/primitives`' `/` was a bare host reference. JVM throws
`ArithmeticException: Divide by zero` for integral zero divisors only;
JS and ClojureDart both follow IEEE-754 and return `Infinity` for *any*
zero divisor, integer or float — so `(/ 1 0)` silently evaluated to
`##Inf` on ClojureScript and ClojureDart instead of raising, breaking
`test/yin/repl_core_test.cljc`'s `a-failed-input-is-consumed-exactly-once`
on both non-JVM hosts (confirmed by direct testing on all three real hosts
before any fix was written, not inferred).

Fix: a new `checked-divide` replaces the primitive, throwing
`(ex-info "Divide by zero" {:divisor y})` for any zero divisor before
delegating to real `/`.

**The consequential decision**: this changes JVM behavior too — `(/ 1.0
0)` previously returned `##Inf` there (only integral zero-divisors threw
on the JVM); it now throws, since no rule can reproduce the JVM's
original integer-vs-float distinction cross-host (`(integer? 0.0)` is
`true` in ClojureScript; ClojureDart's `/` always yields a double). The
implementer's stated position, and the reviewer's independent
confirmation: `docs/design/yin.vm.divergence-register.md:319-322`
documents divide-by-zero error text as part of a verified REPL corpus,
meaning throwing (not `##Inf`) was always the intended contract — so this
is understood as *closing a host-portability gap*, not introducing a new
constraint that didn't exist before.

Verified locally by the orchestrator, independently at every stage: `clj
-M:kondo --lint` clean. `clj -M:test -n yin.vm-test -n
yin.repl.core-test` → 0 failures, 269 assertions. `bb test:cljs` (full
suite) → 1387 tests, 0 failures. `bb test:cljd` (full suite) → **1344
tests, ALL PASS** — the first time the entire CLJD suite has been clean
all session.

## Task

Evaluate foundational invariants, ownership boundaries, host isolation,
CLJ/CLJS/CLJD portability, migration risk, and design contradictions —
specifically:

1. Is unifying to "any zero divisor throws" (changing JVM behavior to
   match JS/Dart, rather than the reverse, or some other unification) the
   architecturally correct call? Does it risk breaking anything outside
   the test suite — demos, docs examples, or existing programs that might
   depend on JVM's `(/ 1.0 0)` → `##Inf` behavior specifically? Search
   beyond the test tree if you have reason to.
2. Does this interact at all with the `dao.jing`/`yin.vm` round-trip-law
   fix committed earlier tonight (same file, `yin.vm.cljc`, different
   function — confirm no overlap, migration-order issue, or shared-state
   concern)?
3. Anything else — is `checked-divide`'s placement and naming consistent
   with the file's existing primitive-wrapping conventions (the ns comment
   above `primitives` explains when wrapping is warranted; confirm this
   fix's rationale matches that stated bar)?

Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended
correction. Also confirm the requested properties that passed review. End
with an explicit APPROVE / APPROVE-WITH-FINDINGS / REJECT verdict — this
governs whether the orchestrator is authorized to stage and commit this
diff. Deliver the actual verdict text directly in this response now — do
not stop to ask permission, and do not reference a plan file or say the
review was delivered elsewhere.
