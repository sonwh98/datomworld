Created-GMT: 2026-10-03 16:37:57 GMT
Created-Local: 2026-10-03 23:37:57 +07 (+0700)
Coding-Agent: claude
Session-ID: af1f9774-0b7e-48d6-8e11-9690c65e0164

# Task: Python py/range-elem guarded fast path (undo a 6x per-iteration slowdown, keep JS exactness)

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-03 23:38 +07 | Status: active | Rationale: correctness-critical cross-host numeric prelude work; opus tier; the architect pair ruled and a different family will gate it

Implement in /Users/sto/workspace/datomworld-py-rangefix (branch yang-python-rangefix, master eaf7d6f0). You are a THIRD concurrent
worktree: C3-S2 (py/key, numeric-key) and C2-S3 (yield from) edit prelude.cljc elsewhere in other worktrees; keep your diff
confined to the range region so later rebases stay trivial. Do NOT commit, stage, stash, rebase or touch collab/ in the main
tree: this session cannot approve git write commands (read-only git diff/show/log/status are fine).

## The defect (measured)

Commit 93e83213 replaced the range element lookup `(+ start (* i step))` with a recursive `py/range-elem`
(src/cljc/yang/python/antlr/prelude.cljc ~1487-1510): it halves `i` and doubles `step` so `i*step` (up to 2^54) is never
formed, keeping results exact on JavaScript where products past 2^53 round. Each loop iteration over a range now makes
~log2(i) interpreted recursive calls through py/int-mod and py/int-floordiv. `yang.python.antlr.e2e-test/long-loops-test`
went 65 s -> 403 s in that one commit (834 s on master); restoring only the one-liner gives 66 s on the old commit and
132 s on master. Any Python program iterating a range pays this.

## Converged architect ruling (fable-5.1 and gpt-6-astra agree; implement it exactly)

Verdict: the exactness requirement is real (C1 integer domain [-2^53, 2^53], yang.antlr.md 8.5.4, and the prelude contract
"an integer is exact on every host or a guest OverflowError"; the huge-range case is pinned by prelude_parity_test.cljc
~154-168 and e2e_c1_test gate-round3-test), but the unconditional recursion is an over-reach. Add a guarded O(1) fast path
in the PRELUDE ONLY (no lowering change, no iterator state, no new range field):

- Use astra's conservative, division-free guard (a superset of fable's i<2^26, |step|<2^26): take the fast path
  `start + i*step` only when `0 <= i <= 2^26`, `-2^26 <= step <= 2^26` and `-2^52 <= start <= 2^52`, using literal
  comparisons (no py/int-mod, py/int-floordiv, or recursive bound calculation). Then |i*step| <= 2^52 and the sum is within
  +-2^53, exact on every host, INCLUDING the one-past candidate. Reuse the existing helper style (see the guard
  py/checked-mul already uses near prelude.cljc ~798).
- Outside the guard fall back to the EXISTING recursive helper, unchanged. Keep the recursive helper a separate
  function so its own recursive calls do not re-run the fast-path checks.
- Preserve the current exclusive-stop test and the C1 domain restriction. Do NOT precompute a checked length before
  iteration (a range whose len() overflows must still allow bounded consumption). Do NOT touch py/range-count, range
  membership or py/float-mod in this slice.
- Also correct the comment at ~1511 (range-len): CPython's len() limit is sys.maxsize; 2^53 is this C1 profile's restriction.

## Tests (required)

1. Parity rows, on every host (JVM, Node, Dart; all four VMs), at the guard boundary: i and |step| at the inclusive edge and
   one past it, both step signs, `start` at +-2^52 and just outside, a mixed-sign small range such as `range(-5, 5)`, and the
   one-past index returning :py/stop on both paths. Keep every existing range test and the huge-range pins as they are.
2. A DETERMINISTIC regression guard in the fast lane (no wall-clock budget): prefer rebinding/stubbing `py/range-elem` to a
   throwing function and iterating `range(3000)` (passes only if the fast path never enters the recursion); if the prelude
   harness cannot rebind it, instead assert a per-VM VM-transition ceiling for isolated lookups at indices 1, 3000, 100000
   that is independent of index magnitude. Say which you used and why; state any ceiling and its headroom.
3. Keep `long-loops-test` ^:slow. After your fix run it ONCE (about 2 minutes expected) and report the time.

## Lanes and shared-resource rules (important)

- Lane commands: `mise exec -- bb gen:python-antlr`; JVM `mise exec -- bb test:clj` (excludes ^:slow; ~5.5 min); Node
  `mise exec -- bb test:cljs` (~4 min). Foreground, single turn, no background processes; chunk anything that could pass 10 min.
- Until 23:45 local on 2026-10-03 run ONLY focused namespace tests (`clojure -M:test -n <ns>`): the orchestrator is running a
  verification on the main tree and two other engineers are running too, so extra load would distort timings. After 23:45
  you may run the full JVM and Node lanes.
- Dart: do NOT run the CLJD lane (one runner repo-wide; C3-S2 owns it first). The new parity rows must be written to run
  on Dart too; the orchestrator runs Dart for you later.

Allowed files: prelude.cljc (range region and the range-len comment only), prelude_parity_test.cljc and/or a new test
file under test/yang/python/antlr/ for the new rows and guard, and docs/design/yang.antlr.md ONLY for one sentence
recording the guard under the range text if such text exists (otherwise leave docs alone). Ask before editing anything else.
kondo 0 errors; cljstyle clean on changed files (`cljstyle fix` then `check`, run directly).

Record, but do NOT implement, these follow-ups in your report: range-count and range-membership interpreted cost
(division helpers); py/fmod-pos termination and bounded work on extreme exponent ratios and subnormals; whether
py/genexp-unsupported and the "generator expression (phase C2)" refusals are stale now that C2 S1/S2 landed.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <exact Session-ID>

Then report changed files, exact lane and test outcomes with counts and times, the long-loops-test time, which regression guard
you used, and anything unfinished. Write findings to
/Users/sto/workspace/datomworld-py-rangefix/collab/1791045477000-compiler-engineer-python-range-elem-fastpath.claude-opus-5-5.findings.md
Do not claim edits or runs that did not occur.
