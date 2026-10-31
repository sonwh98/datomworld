Created-GMT: 2026-10-18 09:55:00 GMT
Created-Local: 2026-10-18 16:55:00 +07 (+0700)
Coding-Agent: claude-opus-5-5

# Task: M3 — retire the legacy Python compiler and refactor its dependencies to the antlr frontend

WORK TREE: /Users/sto/workspace/datomworld-m3legacy (branch m3-legacy, HEAD 79db800e = master).
The owner's standing order: "remove the legacy python compiler and refactor any dependency on it to
the new python compiler" — held until the antlr frontend works on JS and Dart. That condition is now
MET: the multi-target M0-M2 landed at b0b49c11 (the antlr Python frontend parses on JVM, Node and
Dart; codex signed off; the migration gate in yang.antlr.md — "legacy retires at its own migration
gate" — is passed). This round executes the retirement.

THE SURFACE (surveyed; expand as you find more):
- src/cljc/yang/python.cljc — the legacy compiler (~532 lines). REMOVE.
- src/cljc/yin/repl/frontends.cljc:67 — the :yang.python/legacy frontend registration. REMOVE the
  registration; the catalog then carries Clojure + :yang.python/antlr (+ the PHP frontend if it is
  registered there — PHP is NOT in scope: it stays).
- src/cljc/yin/repl/adapter.cljc, src/cljc/yin/repl/main.cljc — any legacy dispatch/mentions. REFACTOR
  to the antlr frontend or drop dead branches.
- Tests: test/yin/repl/frontend_catalog_test.cljc, test/yin/repl/main_test.cljc,
  test/yin/repl/python_antlr_test.cljc — update the expectations (the catalog shrinks; any test that
  exercises the legacy frontend migrates to :yang.python/antlr or dies with it).
- Docs: the frontend catalog mentions (src/cljc/yin/vm/docs/yin.repl.md, the F2 sections in
  docs/design/yang.antlr.md around :3002 and :3308 — UPDATE to record the retirement and the new
  catalog; keep the docs self-contained). The lang command's help text if it enumerates frontends.

RULES:
- The antlr frontend (:yang.python/antlr) becomes THE Python frontend on every host. No fallback to
  legacy anywhere (the design: "No fallback between frontends").
- The prelude, the scanner goldens, the DHT fixtures: expected UNCHANGED (the frontends catalog is
  not a scanned surface — if ANY pin moves, STOP and report; that would be a finding, not a chase).
- The removal is a DELETION, not a deprecation: no compatibility shims, no dead flags.
- The JVM-only antlr note in the docs is STALE (the antlr frontend is now on every host) — fix any
  remaining "JVM-only" claims you encounter in the surfaces you touch.

VERIFY (one namespace per invocation): frontend_catalog_test, main_test, python_antlr_test,
yin.repl's remaining suites that reference frontends, e2e-test, the value-display acceptance rows
(they live in python_antlr_test), the JVM quick tier (mise exec -- bb test:clj) as the broad net, and
the Node + Dart quick lanes (bb test:cljs / test:cljd — the catalog change is portable; the lanes
must stay green; on "Patch does not apply", rm -rf build/antlr lib/antlr and rerun). kondo 0/0 on
every changed file (a deletion file disappears from linting — confirm nothing else requires it:
clj -M:kondo --lint on the repo src should show no unresolved requires of yang.python).
cljstyle check on the changed files (if denied, say so).

CONSTRAINTS: single turn, no approvals. Commits to branch m3-legacy (logical: the removal, the
refactor, the tests, the docs — or one commit). Do NOT push, do NOT touch master. collab/ stays
untracked. End with: the deletion list (files + line counts), the refactor map (what moved where),
the catalog's new shape, the lane results, and anything you found referencing legacy that you could
not remove.
