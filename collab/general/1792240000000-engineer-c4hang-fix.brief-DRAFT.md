Created-GMT: <BY-ORCHESTRATOR-AT-DISPATCH>
Created-Local: <BY-ORCHESTRATOR-AT-DISPATCH>
Coding-Agent: codex
Session-ID: pending (capture thread.started)

# Task: Fix the live-session hang — the linker undischarged verdict spins over a lazy occurrence seq

Role: Yang Compiler and Universal AST Engineer

WORK TREE: /Users/sto/workspace/datomworld-c4fix (branch c4fix, clean at fa91caa3). FIRST: `git checkout -b
c4hang-fix` (or stay on c4fix and branch — your call, one branch, one fix). The worktree carries an
UNCOMMITTED failing regression test in test/yin/repl/dht_process_test.clj
(prompt-returns-after-filtered-comprehension-assignment-test, appended at the file's end). Do not stage or
commit until your fix makes it pass; the landing commit includes the test going green alongside the fix.

THE BUG (owner-reported, live-confirmed, thread-dumped twice):
- The owner's JVM REPL session (master fa91caa3 — the cbor fix IS in their build) hangs at the prompt after
  `(lang :yang.python/antlr)` and the submission
  `even_squares = [x**2 for x in range(1, 11) if x % 2 == 0]`.
- The owner's live thread dump: the session's background thread spins at ~100% CPU in
  `yin.vm.linker/undischarged`'s verdict fn — linker.cljc:1140, `(every? (fn [s] (precedes? (:at d) (:at
  s))) sites)` inside `(filter ... (unconditional defs))`, realizing a LAZY seq (LazySeq.force/realize
  frames) — 281 seconds of CPU and counting. A second dump layer showed dao.jing.cbor/blen frames earlier
  (that site is already hinted-fixed in fa91caa3; the current spin is the linker verdict).
- The ORCHESTRATOR'S EARLIER HYPOTHESIS (encoder realizing an infinite comprehension) was DISPROVEN by your
  own previous round — treat every hypothesis in the old brief as a guess to check, not a conclusion.

THE FAILING TEST (yours to make pass): the appended
`prompt-returns-after-filtered-comprehension-assignment-test` spawns the real JVM REPL (the
dht_process_test spawn infrastructure, --no-state), drives `(lang :yang.python/antlr)` + the two
comprehension submissions, closes stdin, and asserts the process EXITS within 90s. CONFIRMED RED: under
the bug the session spins and never exits (the test kills it at the deadline and fails with the hang
assertion). Your fix makes the session process the submissions and exit cleanly (the collected output then
asserts "Switched to", the "5", and the [4, 16, 36, 64, 100] lines — the assertions are already in the
test, gated on the exit).

DIAGNOSE first (the orchestrator does not know why the verdict's lazy seq is unbounded on this path — that
is your job): what feeds `undischarged` on the REPL's auto-index publication of this submission, and why
the occurrence/site seqs realize without bound (a cyclic row reference? an occurrence seq over a growing
index? a lazy interplay between the comprehension's lowering and the publication?). Then FIX at the real
defect: if the verdict's algorithm is wrong (e.g. it should not realize unbounded seqs, or precedes? is
O(n^2) over an effectively-unbounded set), fix the algorithm; if a producer feeds it something it should
not, fix the producer. Do not weaken the test; do not add a timeout band-aid in place of the fix.

ACCEPTANCE:
- The appended regression test PASSES (the process exits within 90s, all the output assertions green).
- The full dht-process namespace green (its other tests untouched).
- The focused JVM namespaces green: yang.python.antlr.e2e-test, e2e-c2-test, import-test
  (DATOM_SLOW_TESTS=1 clojure -M:test -i :slow -n ...).
- The bb test:clj quick tier green; kondo + cljstyle on touched files.
- CPython 3.9.6 output comparisons where applicable.

LANES: foreground, one at a time, waiting; over a cap, rerun in namespace groups and say so. No background
runs, no watchers, complete the turn. mise is trusted; run bb gen:python-antlr first if builds are stale.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Report the diagnosis with file:line, the fix, exact counts per lane, anything behavioral, incomplete work.
