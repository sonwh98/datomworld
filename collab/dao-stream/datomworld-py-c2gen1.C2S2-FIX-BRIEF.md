# Task: C2-S2 Gate Fixes (two findings)

The C2-S2 gate returned REQUEST CHANGES. Apply both fixes in this
worktree (branch yang-python-c2-s2, uncommitted C2-S2 delta present):

1. P1 — prelude.cljc:346-350: gen.throw normalizes any class BEFORE
   validating BaseException ancestry, so it.throw(C, 7) invokes
   C.__init__ for a non-exception C (reproduced on all four JVM
   evaluators: a constructor side effect [7] fires; CPython checks
   eligibility first — genobject.c L456-482). FIX: in py/throw-exc,
   validate exception-class ancestry BEFORE calling typ or
   normalization: if typ is a class not deriving from BaseException,
   raise TypeError("exceptions must be classes or instances deriving
   from BaseException, not ...") immediately, without calling typ and
   without switching the generator into the exception path. Only
   classes passing the check get the existing value handling (called
   with it, tuple spread, instance used as is).
   REGRESSION: a non-exception class whose __init__ appends to a log,
   thrown into a suspended generator: the caller gets TypeError, the
   log stays [], and the next next(it) still returns the generator's
   next value. As e2e tests on all four evaluators plus a prelude
   parity case (Node and Dart cover it).
2. P2 — 11 added lines exceed 80 columns (prelude.cljc:338, 340, 350,
   353, 359, 362, 1779; e2e_c2_test.clj:160, 354;
   prelude_parity_test.cljc:244, 266). Wrap them.

Then the tri-host lanes, one at a time, each logged under target/:
bb test:clj, bb test:cljs, bb test:cljd — 0 failures required; report
exact counts. kondo and cljstyle clean on touched files.

Constraints: touch only prelude.cljc, e2e_c2_test.clj,
prelude_parity_test.cljc (plus the three C2-S2 files already modified:
the delta is uncommitted); ASCII, <= 80 columns on added/edited lines;
no commit/stage; no checkout/reset/stash; no leftover diagnostics;
exclude C2S2-FIX-BRIEF.md from any staging.

End your final response with exactly one line:
"Status: COMPLETE" or "Status: BLOCKED - <reason>".
