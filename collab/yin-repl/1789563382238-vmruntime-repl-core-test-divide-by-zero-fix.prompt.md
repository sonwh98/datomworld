Created-GMT: 2026-09-16 12:56:22 GMT
Created-Local: 2026-09-16 19:56:22 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: a1656d45-205c-4eb6-9f34-79b945b489c4

# Task: Investigate and fix the cross-host divide-by-zero test failure in yin.repl.core-test

Role: VM Runtime

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 19:56:22 +07 | Status: active | Rationale: VM primitive semantics + cross-host verification, matches glm-5.3's VM/portability strengths

## Context

`test/yin/repl_core_test.cljc`'s `a-failed-input-is-consumed-exactly-once`
deftest (line 114) fails identically on ClojureDart and ClojureScript, but
passes on the JVM. Confirmed via three full-suite runs tonight
(`bb test:cljd`: 1341 tests, this is the one remaining failure;
`bb test:cljs`: 1386 tests, this is the only failure, on both `:semantic`
and `:ast-walker` VM-type variants; `clj -M:test` never showed it failing).

The failing assertion (line 121): `(is (str/includes? failed "Error: "))`,
after evaluating `"(do (println \"before\") (/ 1 0))"` through the VM. The
prior assertion (`str/starts-with? failed "before\n"`, line 120) passes, so
`println` runs fine — only the expectation that the division produces an
error fails.

**Strong lead, not yet directly confirmed on a real CLJS/CLJD host — verify
before fixing.** `src/cljc/yin/vm.cljc:105`'s primitive table maps the
VM's `/` directly to the host language's own `/` operator (`'/ /,` — no
wrapping, no zero-check): "Arithmetic and comparison ops are direct
clojure.core references" (the ns comment above `primitives`, line 96).
JVM Clojure's `/` throws `ArithmeticException: Divide by zero` for integer
division by zero. ClojureScript's and ClojureDart's underlying numeric
division typically follow IEEE-754/JS/Dart double semantics instead,
returning `Infinity` rather than throwing — if that's what's actually
happening here, `(/ 1 0)` silently succeeds with the value `Infinity`
instead of raising an exception, so `eval-input`'s error path
(`core/format-error`, `core.cljc:123-128`, which is what prepends
`"Error: "`) never runs at all.

This is not incidental: `docs/design/yin.vm.divergence-register.md:319-322`
documents that "the error text for ... division by zero" was explicitly
part of a REPL corpus verified byte-identical between the semantic VM and
ast-walker evaluators — throwing on divide-by-zero, with specific error
text, has always been the intended, tested behavior. Both evaluators here
fail *identically* on CLJD/CLJS (this is not a semantic-vs-ast-walker
divergence the register already covers — it's a pure cross-host gap in
the primitive both evaluators share via `yin.vm/primitives`).

## Task

1. **Confirm the actual behavior directly** — don't take the above as
   settled. Evaluate `(/ 1 0)` on both ClojureScript (e.g. via a `bb
   test:cljs`-reachable scratch deftest, or the shadow-cljs Node REPL if
   available) and ClojureDart (a scratch `.cljd` snippet compiled and run,
   or reasoning from Dart's own documented numeric `/` semantics if you
   can verify it precisely) to see the exact actual value or exception
   each host produces. Report exactly what you find, including if my lead
   above turns out wrong or incomplete (e.g. if one host throws and the
   other doesn't, or if the actual issue is something else entirely, like
   an unrelated timing/ordering bug in `eval-input`'s error-catching path
   itself — check that path too, `yin.repl.core`'s handling of the
   VM's own exception propagation, before assuming the primitive is
   solely at fault).
2. **Decide the correct fix layer** based on what you actually find:
   - If the primitive genuinely doesn't throw on some hosts: give `/` (and
     consider whether `+`/`-`/`*` have any analogous host-divergence risk
     — check, don't assume they're fine just because this bug report is
     about `/`) host-appropriate zero-check wrapping so it throws
     consistently, matching JVM's behavior and the divergence register's
     documented intent. Keep the wrapping minimal and portable — no
     reader conditionals if a host-uniform Clojure-level check
     (`(when (zero? divisor) (throw ...))`) works identically on all
     three hosts; only reach for a reader conditional if the hosts
     genuinely need different exception types/messages and you can't
     avoid it.
   - If instead you find the primitive behavior is actually fine/intended
     and the *test* is wrong to assume JVM-specific exception semantics
     for a value the VM was never contracted to make host-uniform, argue
     that explicitly against the divergence-register's stated intent
     (cited above) before changing the test instead of the primitive —
     don't take the easier path without justifying it against that
     documented contract.
3. Add a regression test covering the actual fix, at the layer you fix
   (a portable `yin.vm`-level primitives test if you fix the primitive;
   don't just rely on the existing REPL-level test alone, which is an
   indirect signal three layers away from the actual primitive).
4. Do not touch anything outside what your diagnosis actually requires.
   Do not stage or commit.

## Verify

- `clj -M:kondo --lint` on every file you touch.
- `clj -M:test` on the relevant JVM namespaces — must stay green.
- `bb test:cljs` and `bb test:cljd` (full suites) — this specific failure
  must be gone on both, and report the full pass/fail count for each, not
  just this one test, to confirm nothing else regressed.

## Deliverable

Report back: what you actually observed on each host (not assumed), the
root cause with evidence, the exact diff, the new regression test, and the
exact verification commands with output for all three hosts. If this
turns out to be two separate issues (e.g. a genuine primitive gap plus
something unrelated in the error-catching path), say so explicitly rather
than forcing one fix to explain everything.
