Created-GMT: 2026-10-03 16:33:08 GMT
Created-Local: 2026-10-03 23:33:08 +07 (+0700)
Coding-Agent: claude
Session-ID: 65b24574-4b26-45d4-993a-cfccf0b2604a (resume of the C3-S2 engineer session)

# Task: Python C3-S2 round 4 — rebase onto master and reconcile with the landed float-fix

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-03 23:33 +07 | Status: active | Rationale: resume of the C3-S2 engineer (rounds 1-3 context); rebase and reconcile after float-fix landed

Work in /Users/sto/workspace/datomworld-py-c3key1 (branch yang-python-c3-s2). Your round-3 work (5 uncommitted files:
prelude.cljc, e2e_c2_test.clj, e2e_test.clj, prelude_parity_test.cljc, safepoint_test.cljc) is based on 69e58662,
which is now 13 commits behind master eaf7d6f0. Master has landed, since your base:
- float-fix (1f8b7e37): every Python float is Jing float64 content (`data/float64`, `data/float-value`); the
  interim `data/numeric-key` was added for dict/set keys. yang.antlr.md 8.5.5 records it, and line ~2288 says
  "C3-S2 replaces this numeric arm with ruling-6 decimal-string keys, pins one NaN-key behavior, and deletes
  `numeric-key`."
- ac9ecb8b: the JS Float64 carrier's `valueOf` throws :carrier-coercion (cbor.cljc); toString/pr-str/=/hash are unchanged.
- test lanes (8d338571, a6b62b7f, eaf7d6f0): 22 JVM tests are tagged ^:slow; `bb test:clj` now runs
  `clojure -M:test -e :slow`; 11 .cljc slow tests wrap their body in `(dao.test-slow/guard ...)`.
  Your edits to safepoint_test.cljc and e2e_c2_test.clj / e2e_test.clj WILL conflict with those tag/wrapper edits:
  keep master's `^:slow` tags and guard wrappers, and re-apply your own additions on top.

## What to do

1. Back up your diff outside the repo: `git diff > ../datomworld-py-c3key1.prerebase.patch` (plus a copy of any
   untracked file you added). Then `git stash push -u`, `git rebase master`,
   `git stash pop` and resolve conflicts. Do not commit and do not stage. Do not touch collab/ in the main tree.
2. Reconcile C3-S2 with float-fix per the ruling text: ruling-6 decimal-string keys WIN; DELETE `data/numeric-key`
   (data.cljc ~452-464, its export ~504, prelude.cljc ~89 and ~1260-1263, the tests that pin it); the float-key
   helpers must unwrap carriers via `data/float-value` before exact decomposition; pin ONE NaN-key behavior
   (state which, and why, in yang.antlr.md 8.4/8.5.4 where the ruling lives); keep `1`, `1.0` and `True` folding to
   one key as the ruling says. Note the new JS carrier `valueOf` throw: any code path that relied on implicit
   coercion of a carrier must read the payload explicitly.
3. Re-mint any address goldens that change because the prelude changed (float_address_test.cljc has the
   program/prelude address tests); say which changed and why.
4. Update docs/design/yang.antlr.md so the C3 status text matches what you landed in the tree (do not rewrite the
   landed 8.5.5 text; adjust only the sentence at ~2288 and the C3 slice status).

## Lanes, and the two shared-resource rules (important)

- NEW lane commands: `mise exec -- bb gen:python-antlr`, JVM `mise exec -- bb test:clj` (excludes ^:slow; about 5.5 min),
  Node `mise exec -- bb test:cljs` (about 4 min), Dart `mise exec -- bb test:cljd` (about 4.5 min). Foreground only,
  single turn; chunk any command that could pass the 10-minute cap.
- Until 23:45 local on 2026-10-03 run ONLY focused namespace tests (`clojure -M:test -n <ns>`): the orchestrator is running a
  full verification on the main tree and extra CPU load would distort it. After 23:45 run the full lanes.
- Dart: you are the only unit allowed to run the CLJD lane (one runner repo-wide). Kill orphan runners first.
- Because your change touches generated/prelude code, ALSO run the Python slow tests once on the JVM:
  `clojure -M:test -i :slow -n yang.python.antlr.safepoint-test -n yang.python.antlr.e2e-c2-test -n yang.python.antlr.e2e-test`
  EXCLUDING long-loops-test (it takes 14 minutes by itself because of a known range-elem slowdown that someone else
  is fixing; use `-v` var selection for the others if needed). Report which slow tests you ran.

Allowed files: those in your 5-file diff, src/cljc/yin/vm/data.cljc and its test (the numeric-key deletion),
docs/design/yang.antlr.md, test/yang/python/antlr/float_address_test.cljc (goldens), and anything the rebase conflicts
force. Ask before editing anything else. kondo 0 errors, cljstyle clean on changed files (`cljstyle fix` then
`check`; run them directly, not through a piped loop).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <exact Session-ID>

Then report changed files, exact lane outcomes with counts and times, the NaN-key decision, re-minted goldens,
what you deleted, and anything unfinished. Write findings to
/Users/sto/workspace/datomworld-py-c3key1/collab/1791045188000-compiler-engineer-python-c3-s2-r4.claude-opus-5-5.findings.md
Do not claim edits or runs that did not occur.
