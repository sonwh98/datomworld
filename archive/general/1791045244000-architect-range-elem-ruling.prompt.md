Created-GMT: 2026-10-03 16:34:04 GMT
Created-Local: 2026-10-03 23:34:04 +07 (+0700)
Coding-Agent: claude (fable-5-1, session c0ef9c80-5dfa-4954-a474-4ef5688e9fb7) and codex (gpt-6-astra, resume of thread 01a0f878-281b-7253-ac44-ff2402583d35)
Session-ID: c0ef9c80-5dfa-4954-a474-4ef5688e9fb7 (fable); 01a0f878-281b-7253-ac44-ff2402583d35 (astra)

# Task: Ruling on py/range-elem — a 6x slowdown bought for JavaScript exactness

Role: Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-03 23:34 +07 | Status: active | Rationale: standing mob (owner-decision questions go to the architect pair)
- Model: gpt-6-astra | Assigned: 2026-10-03 23:34 +07 | Status: active | Rationale: standing mob partner; independent opinion on the same brief

Read-only. Answer independently; do not edit, do not run suites.

## The finding (measured, in /Users/sto/workspace/datomworld on master eaf7d6f0)

`yang.python.antlr.e2e-test/long-loops-test` went from 65 s to 403 s in ONE commit, `93e83213`
("signed-zero preservation in float division, mod and unary ops (C1 gate r4)"), and is 834 s on master.
Bisect: bf6c5544 45 s, 34c3986b (C1) 65 s, 93e83213 403 s, then a 400 s plateau. Restoring ONLY the function below
to its old one-liner brought it back to 66 s on the old commit and from 834 s to 132 s on master.

In src/cljc/yang/python/antlr/prelude.cljc (~1487-1510), the commit replaced the range element lookup
`(+ start (* i step))` with a recursive `py/range-elem`:

    (fn [start step i]
      (if (= i 0) start
        (if (= (py/int-mod i 2) 1)
          (+ (py/range-elem start step (- i 1)) step)
          (py/range-elem start (+ step step) (py/int-floordiv i 2)))))

Its comment: it computes `start + i*step` "exactly on every host" by halving i and doubling step so every partial
sum is itself an element of the range (so within +-2^53) and `i*step` (up to 2^54) is never formed. Each loop
iteration over a `range` now makes about log2(i) interpreted recursive calls, each through `py/int-mod` and
`py/int-floordiv`. `py/range-at` (the iteration entry, ~1498-1510) calls it once per element, with the one-past
element allowed to exceed 2^53 on JS because "rounding is monotone, so the bound test still ends the range
exactly there". `py/range-count` (also in that commit) uses floor quotients and remainders for the same reason.

Context: JavaScript has one number type; integer arithmetic is exact only up to 2^53. The C3 exact-integer
module (yang.antlr.md 8.5.4; slice S1 landed as 54536317) gives Python ints exact carriers beyond that, but
the prelude's `range` still works on bare host numbers. The test loops `for k in range(100000)` and breaks at 3000,
across 4 VMs x 2 modes, so the per-iteration cost is multiplied heavily; any Python program iterating a range pays it.

## Questions (give a recommendation, not a survey; one-line verdict first)

1. Is exact `range` element arithmetic for huge |start|, |stop| or |step| (products beyond 2^53) a REQUIREMENT of
   the C1 or C3 rulings in docs/design/yang.antlr.md (cite the section), or an over-reach of that gate round?
   What does CPython do, and what must we match on JS, Node and Dart?
2. Which design should replace the recursion? Candidates, not exhaustive:
   (a) a guarded fast path: use `start + i*step` whenever `|i|` is provably small enough that the product stays
       within +-2^53 (decided in O(1) without forming the product, e.g. `|i| <= floor(2^53 / |step|)`), and keep the
       recursive form only for the rare huge case;
   (b) carry the previous element in the loop state and add `step` each iteration: O(1), and exact because each
       partial sum is itself an element of the range (so this removes the per-index lookup entirely);
   (c) route huge ranges through the C3 exact-integer module and keep bare numbers for the common case;
   (d) something else, or just revert to the one-liner and record the limit.
   For the one you choose: where does it live (prelude only, or lowering), what are the exact semantics at the
   boundaries, and which tests pin it on every host (a cross-host exactness test for a huge range, the existing
   range tests, a micro-benchmark or a step-count bound so this cannot silently regress again)?
3. After the fix, `long-loops-test` should cost about 130 s (the rest is the 4 VMs x 2 modes matrix). Should it
   stay ^:slow? What guard against a regression of this size (a step-count bound in a test, a time budget)?
4. Anything else in commit 93e83213 that you would apply the same scrutiny to (range-count, float-mod,
   genexp lowering, keyword-argument ordering were all in it)?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
