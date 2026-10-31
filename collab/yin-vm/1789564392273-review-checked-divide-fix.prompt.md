Created-GMT: 2026-09-16 13:13:12 GMT
Created-Local: 2026-09-16 20:13:12 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: pending (provider-generated)

# Task: Independent review of the host-uniform division-by-zero fix

Role: Adversarial Review

Implementers:
- Model: gemini-3.1-pro-high | Assigned: 2026-09-16 20:13:12 +07 | Status: active | Rationale: cross-family review of a GLM-family (glm-5.3) fix; GPT review capacity was already spent tonight on a more foundational fix

## Context

`test/yin/repl_core_test.cljc`'s `a-failed-input-is-consumed-exactly-once`
deftest failed identically on ClojureDart and ClojureScript, passed on the
JVM. Root cause, directly confirmed on all three real hosts (not just
inferred): `src/cljc/yin/vm.cljc`'s VM primitive table mapped `/`
directly to the host's raw division operator. JVM's `/` throws
`ArithmeticException: Divide by zero` for *integral* division by zero;
JS and Dart both follow IEEE-754 double semantics and return `Infinity`
instead of throwing, for any division by zero (integer or float). So
`(/ 1 0)` silently evaluated to `##Inf` on CLJS/CLJD instead of raising,
and the REPL's error-catching path (`core/format-error`) never ran.

Fix: read the actual diff yourself —
```
git diff src/cljc/yin/vm.cljc test/yin/vm_test.cljc
```
A new `checked-divide` function replaces the bare `/` reference in
`yin.vm/primitives`. It explicitly checks `(zero? y)` and throws
`(ex-info "Divide by zero" {:divisor y})` before delegating to the real
`/`.

**The consequential decision to scrutinize**: this is a deliberate
behavior *change*, not purely a bug fix. On the JVM itself,
`(/ 1.0 0)` (float division by zero) previously returned `##Inf`
(JVM only throws for *integral* zero divisors) — it now throws too, since
`checked-divide` doesn't distinguish integer from float divisors. The
implementer's stated reasoning: JS and ClojureDart cannot reliably
distinguish `0` from `0.0` at the value level (confirmed:
`(integer? 0.0)` is `true` in ClojureScript; ClojureDart's `/` always
returns a double), so no rule can reproduce the JVM's original
integer-vs-float distinction uniformly across all three hosts — the only
self-consistent cross-host contract is "any zero divisor throws." The
implementer checked that no existing test relied on the old JVM-only
float-division-returns-Infinity behavior, and verified this by running
all three full test suites: `clj -M:test` (targeted namespaces, 0
failures), `bb test:cljs` (1387 tests, 0 failures), `bb test:cljd` (1344
tests — **all tests pass**, the first time this session the full CLJD
suite has been clean). Independently reconfirmed by the orchestrator.

`docs/design/yin.vm.divergence-register.md:319-322` documents that
"the error text for ... division by zero" was explicitly part of a REPL
corpus verified byte-identical between the semantic VM and ast-walker
evaluators — the implementer cites this as evidence that throwing on
divide-by-zero, with specific text, was always the intended, tested
contract (on whichever host that corpus check originally ran, presumably
JVM), making this fix a closed gap rather than a new constraint invented
to pass a test.

## Task

This is architectural/implementation review, read-only. Evaluate:

1. **Is the root-cause diagnosis actually correct and complete?** The
   implementer reports directly testing `(/ 1 0)`, `(/ 1.0 0)`, and
   `(/ 20 5)` on all three real hosts (not just JVM) before writing any
   fix. Does the diff match that stated diagnosis? Is there any other
   arithmetic primitive (`+`, `-`, `*`, or the comparison operators) with
   an analogous host-divergence risk the implementer might have missed
   despite checking? (The implementer states it checked and found none —
   verify independently, don't just trust the claim.)
2. **Is unifying to "any zero divisor throws" — rather than trying to
   preserve the JVM's original integer-vs-float distinction somehow, or
   choosing a different unification (e.g. always returning `##Inf` on
   every host instead, changing JVM to match JS/Dart rather than the
   reverse) — the right call?** Weigh against
   `docs/design/yin.vm.divergence-register.md`'s stated intent and the
   general principle that VM primitive semantics should be host-uniform
   given this codebase's portability invariants. Is there a real
   compatibility risk to existing v1/v2 programs or corpora that depend on
   `(/ 1.0 0)` yielding `##Inf` on the JVM specifically, that a test-suite
   pass wouldn't catch (i.e. anything outside the test suite — demos,
   docs examples, the REPL corpus mentioned in the divergence register
   itself)?
3. **Is `checked-divide`'s variadic-arity handling correct?** Check the
   actual implementation against Clojure's real `/` arity/reduction
   semantics (1-arg reciprocal, 2-arg division, variadic left-to-right
   reduction) — does the new wrapper preserve exact behavioral parity with
   `clojure.core//` for every non-zero-divisor case, on every host?
4. **Test coverage.** Does the new `division-by-zero-is-host-uniform`
   deftest actually exercise the fix meaningfully, including the variadic
   path? Is testing at the primitive level (rather than only through the
   REPL-level test that originally caught this) the right layer, per the
   brief that asked for exactly this?
5. **Anything else** — portability risk, any missed reader-conditional
   need, or interaction with other uncommitted/recently-committed work on
   this branch (the `dao.data`/`dao.jing` round-trip-law fixes committed
   earlier tonight also touch `yin.vm.cljc`, though a different
   function — confirm no overlap/conflict).

Do not edit any file.

## Deliverable

A findings list, most severe first: what's wrong (or confirmed correct),
file:line evidence, and severity (blocking / real gap / minor-nonblocking /
already correct). End with an overall verdict: ready to proceed toward
Architect sign-off as-is, or does something need fixing first.
