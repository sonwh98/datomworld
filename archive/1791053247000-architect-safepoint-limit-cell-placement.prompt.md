Created-GMT: 2026-10-03 18:47:27 GMT
Created-Local: 2026-10-04 01:47:27 +07 (+0700)
Coding-Agent: claude (fable-5-1, session 655c00a9-6a86-49cf-a222-dc4ab99614bc) and codex (gpt-6-astra, resume of thread 01a0f878-281b-7253-ac44-ff2402583d35)
Session-ID: 655c00a9-6a86-49cf-a222-dc4ab99614bc (fable); 01a0f878-281b-7253-ac44-ff2402583d35 (astra)

# Task: safepoint-s2 — where the recursion-limit cell lives (base prelude vs hook prelude)

Role: Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-04 01:47 +07 | Status: active | Rationale: standing mob (design tradeoffs go to the architect pair)
- Model: gpt-6-astra | Assigned: 2026-10-04 01:47 +07 | Status: active | Rationale: standing mob partner; independent opinion on the same brief

Read-only. Answer independently; do not edit, do not run suites.

## Background

Two independent gates found that `py/gen-switch` (the generator crossing, in the BASE prelude) never compares the effective recursion depth with the limit when a generator is started or
resumed; the only check is `py.sp/enter`, at function entry. Your converged generator-depth ruling (collab/1790982600000-architect-generator-depth-ruling.{claude-fable-5-1,gpt-6-astra}.findings.md; gpt-6-astra's
text: "Check the effective depth when resuming, even if execution reaches another yield without an intervening function call. An admission failure must leave the suspended generator and caller context
intact.") requires it. The engineer implemented the check in the uncommitted tree at /Users/sto/workspace/datomworld-py-safepoint2 (run `git diff HEAD` there; prelude.cljc, safepoint.cljc, yang/safepoint.cljc,
docs/design/yang.antlr.md 8.5.2). Red-then-green proof: `generator-admission-test` failed on all four VMs before the fix and passes after.

## The design choice to review

The gate's suggested fix compared against `py.sp/limit`, a cell defined in the HOOK prelude. But naive programs (no hooks, the default `every-vm=` first pass) load only the BASE prelude, so `py/gen-switch`
cannot name `py.sp/limit` there: any naive program using a generator would fail on an unresolved name. The engineer therefore MOVED the limit cell into the base prelude as `py.rt/limit` (default 1000, outside
what escapes restore, like `py.rt/ctx`), and made the hook prelude's setter, getter and `py.sp/enter` read and write that cell. Consequence: naive runs now also refuse generator crossings past the limit,
counting nested active generators only (function-call depth is still counted only where the hook prelude's `py.sp/enter` marks are installed).

## Questions (give a recommendation, not a survey; one-line verdict first)

1. Is putting the limit cell in the base prelude acceptable? Consider the invariants (yin.vm and the base prelude know nothing about hooks; safepoint insertion is a universal AST stage whose hook prelude is
   opt-in; "derive, don't persist"; same address on every host). Alternatives: (a) the engineer's choice (limit in the base prelude, always on for generator crossings); (b) leave the base prelude untouched and
   have the universal stage / hook prelude install the generator admission check (for example by rebinding `py/gen-switch` or wrapping it in the hook prelude) so naive programs keep unbounded generator
   crossings; (c) a base-prelude hook point: `py/gen-switch` calls a base-prelude no-op function `py/admit` that the hook prelude replaces; (d) something else.
2. A naive program now raises RecursionError for a generator crossing past 1000 nested active generators where it previously did not. Is that behavior change acceptable (CPython always has a recursion
   limit), or does it break a standing rule that naive programs are the reference semantics and hooks only ADD observable behavior (signals, KeyboardInterrupt, recursion accounting)? If it breaks
   the rule, which alternative do you choose?
3. Whatever you choose: does the base prelude hash move in a way that matters beyond the golden re-mint (the base prelude address is part of every program address)? Is that acceptable for a limit that
   is only a counter and a comparison?
4. The delegation entries added by C2-S3 (`yield from`, landing before this unit) all funnel through `py/gen-switch`; confirm your choice covers them without extra code.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
