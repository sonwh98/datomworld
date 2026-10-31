Created-GMT: 2026-10-04 06:40:45 GMT
Created-Local: 2026-10-04 13:40:45 +07 (+0700)
Coding-Agent: claude
Session-ID: 0f00061f-cf68-4097-8bb6-b07c7dd44e0c

# Task: Python C4 slice F1: the yang.frontend catalog and manifest validation

Role: Yang Compiler and Universal AST Engineer (frontend SPI)

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-04 13:41 +07 | Status: active | Rationale: owner asked to close the open engineering items; wave 1 of the sequenced plan

Work in /Users/sto/workspace/datomworld-c4f1 (branch yang-frontend-catalog-f1, master ff1e0195; a NEW worktree; node_modules installed).

## Spec (read first)
docs/design/yang.antlr.md: section 3 (the language frontend SPI: 3.1 portable declarations versus installed implementations, 3.2 protocol shape, 3.3 registration and selection, 3.4 support profiles, 3.5 lowering contract,
3.6 adding a language without core changes, 3.7 versioning) and section 8.5.6 (the C4 design and its slice table; the "F1" row and its acceptance bullet) plus the C4 cross-ruling it cites. F1 is the FIRST C4 slice:
the catalog and manifest validation ONLY. It does not touch the Python prelude, yin.repl (that is F2), or any VM, so it does not move any content-address golden.

Acceptance for F1 (8.5.6, each on all three hosts): two revisions of one frontend id installed into an empty catalog are both selectable and the original catalog is unchanged; a manifest holding a function is rejected
with a qualified outcome (a data outcome, the repo's usual `{:status ... :reason ...}` shape; read how neighboring SPI code and src/cljc/yin/vm/linker.cljc refuse).

## What to build
A new namespace (suggested src/cljc/yang/frontend.cljc, with tests under test/yang/frontend_test.cljc, both .cljc so they run on JVM, Node and Dart) implementing: a catalog as an explicit, immutable value (no global
registry, no hidden state); manifest validation (versioned, portable data only: no functions or host handles anywhere in a manifest; a support profile; the declared capabilities from 3.1 to 3.7); install (returns a NEW
catalog; revisions of one id coexist and each is selectable by id plus revision; installing never mutates the argument); select (by id, optionally by revision or profile; unknown, ambiguous and incompatible selections are
qualified data outcomes); and the versioning rules of 3.7. Follow the doc's contract exactly, quote the clause you implement in a comment only where it is non-obvious, and do NOT invent catalog features the doc does not
give. If the doc leaves a decision open, pick the smallest choice consistent with the invariants (explicit state, data outcomes, no global registry, derive don't persist) and list it in your findings as a question.
Also read src/cljc/yang/ for existing conventions (python.cljc, stage.cljc, clojure.cljc register or select anything today? F1 does not migrate them; F2 does).

Allowed files: the new frontend namespace and its test, and docs/design/yang.antlr.md (one sentence in the 8.5.6 F1 row text only, no commit hashes). Ask before anything else.
Focused runs: your new test namespace on the JVM, and `bb test:cljs` is the orchestrator's; make sure the test is host-neutral (no #?(:clj ...) logic in the code under test unless the doc requires a host seam; `:cljd` FIRST in any reader conditional).

## Rules (apply to every engineer round)
- You cannot run git write commands (stash, checkout, rebase, reset, commit, stage): do not try; the orchestrator does all git steps, including rebases. Edit files directly. Do not touch collab/ in the main tree.
- Run ONLY focused tests (`clojure -M:test -n <ns>`; add `-e :slow` to skip the slow ones). The orchestrator runs the full JVM, Node and Dart lanes. NEVER run `clojure -A:test -M -e` (the :test alias's runner launches the whole suite).
- Foreground only, single turn: no background processes; chunk anything that could pass 10 minutes. Kill nothing you did not start.
- kondo 0 errors; `mise exec -- cljstyle fix` then `check` on changed files (run directly, not through a piped loop). ASCII; keep added lines <= 80 columns where practical.
- Anything touching the Python prelude moves content-address goldens in test/yang/python/antlr/float_address_test.cljc: re-mint them LAST and only once (run `clojure -M:test -n yang.python.antlr.float-address-test`, write the actual values in the same :segment/blake3-... form; only address-golden lines may change). If master moves before landing, the orchestrator rebases you and you re-mint again.
- Write your findings to the path named below. Begin the final response exactly with: Completed-GMT / Completed-Local (named timezone) / Coding-Agent: claude / Session-ID: <exact id>. Then report changed files, exact test outcomes with counts, deviations, and anything unfinished. Do not claim edits or runs that did not occur.

Findings: /Users/sto/workspace/datomworld-c4f1/collab/1791096045000-compiler-engineer-yang-frontend-catalog-f1.claude-opus-5-5.findings.md
