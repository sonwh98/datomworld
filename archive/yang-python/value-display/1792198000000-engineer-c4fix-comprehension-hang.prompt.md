Created-GMT: 2026-10-16 19:55:00 GMT
Created-Local: 2026-10-17 02:55:00 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (capture thread.started; seat-assigned aa57295e-8f02-4496-9995-68b6aefc83f2)

# Task: C4 fix — the owner's comprehension hangs the REPL in an encoder spin (cbor/blen)

Role: Yang Compiler and Universal AST Engineer

WORK TREE: /Users/sto/workspace/datomworld-c4fix — create it: `git worktree add
/Users/sto/workspace/datomworld-c4fix -b c4fix origin/master` from this worktree or the main repo. Do not
touch /Users/sto/workspace/datomworld-c4-i6 (another round is using it) or the main tree's checked-out
branch. Do not stage or commit.

THE BUG (owner-reported, reproduced, thread-dumped): at the JVM REPL
(`clj -M:clj-yin-repl --name <fresh>`, then `(lang :yang.python/antlr)`), evaluating

    even_squares = [x**2 for x in range(1, 11) if x % 2 == 0]

HANGS the REPL: the submitting thread spins at ~100% CPU indefinitely. Reproduce exactly this way (a fresh
--name per run; note the interactive prompt echoes by TTY; piped stdin does not):

    (printf '(lang :yang.python/antlr)\neven_squares = [x**2 for x in range(1, 11) if x %% 2 == 0]\n'; sleep 90) | \
      clj -M:clj-yin-repl --name reprofix > /tmp/out.log 2>&1 &
    # after ~40s: ps aux | grep clojure.main → the pid; jstack <pid>

Thread dump at 50s (captured on the owner's machine, /tmp/repro5-stack.txt pattern): the spinning thread is
in `dao.jing.cbor$blen` (cbor.cljc:262-266, `(alength bs)` with a reflective classForName frame) inside an
encode loop — `blen` is innocent; its CALLER is realizing an unbounded structure. The working hypothesis:
the comprehension with an `if` filter mis-lowers into a loop where the induction variable never advances
(an infinite sequence), and the REPL's automatic code indexing on evaluation encodes the realized result —
spinning in the encoder. CROSS-CHECK first: the same program through the NON-REPL path (the e2e harness,
`every-vm=`) — does the program itself terminate and print `[4, 16, 36, 64, 100]`? Also bisect: the
comprehension WITHOUT the `if`, the comprehension WITHOUT `**`, and a plain `for` loop with the same body —
to isolate which piece mis-lowers (the if-filter in a comprehension is the prime suspect; the C1 corpus
must have covered comprehensions — find why this shape slipped through: missing corpus case?).

FIX: make the program terminate with Python's semantics ([4, 16, 36, 64, 100]) — fix the lowering (or the
prelude comprehension driver) at the real defect. If the encoder ALSO needs an unbounded-realization guard,
report it separately with evidence (do not scope-creep the encoder fix into this round unless the hang has
a second cause there).

ACCEPTANCE:
- The owner's exact line evaluates at the REPL prompt and prints/stores [4, 16, 36, 64, 100].
- The bisect variants all behave per CPython 3.9.
- The focused JVM namespaces green: DATOM_SLOW_TESTS=1 clojure -M:test -i :slow -n
  yang.python.antlr.e2e-test,yang.python.antlr.e2e-c2-test,yang.python.antlr.import-test (plus a NEW
  regression test: the owner's exact program at the REPL-facing lowering path, asserting termination and
  the value — added to the e2e corpus so the slow lanes cover it forever).
- kondo + cljstyle on touched files.
- The full bb test:clj quick tier green.

LANES: foreground, one at a time, waiting; over a cap, rerun in namespace groups and say so. No background
runs, no watchers, complete the turn. mise is trusted; run bb gen:python-antlr first.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
