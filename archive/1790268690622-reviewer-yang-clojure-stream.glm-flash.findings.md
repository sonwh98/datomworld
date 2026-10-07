# Review of yang.clojure stream-eval test delta (worktree yang-clojure-stream)

Reviewer: ZCode subagent, GLM-5.3-Flash (independent of claude-sonnet-5 author).
Artifacts: collab/1790268690622-reviewer-yang-clojure-stream.glm-flash.{prompt.md,findings.md}
Completed: 2026-09-25 ~00:55 +07. Read-only; hand-derived expectations verified.

Coverage verified: the file requires only yin.repl/dao.stream surfaces (zero
VM namespaces) and drives all four VMs through repl/create-state +
repl/eval-input, matching yin.repl/vm-constructors — no VM-internal
shortcutting. Five expected values hand-derived and confirmed (history
chain, eviction arithmetic, pump/drain termination, bracket depths,
and/or/if lowering). No tautologies; exact-value pins throughout; each
sensitivity check would fail on a plausibly broken VM/shell. Hygiene: 0
non-ASCII, 0 lines over 80 columns, 13 deftests.

Findings (all P3 polish, none blocking):

- P3 | stream_eval_test.cljc:25-27 | vm-types hardcodes the four VMs while
  its docstring claims to mirror yin.repl/vm-constructors; nothing asserts
  the match, so a fifth VM would be silently uncovered. | Derive it from
  (keys repl/vm-constructors) or add a guard assertion.
- P3 | :39-49,57-61 | evaluate/ring-handle/read-all fixtures duplicate
  test/yin/repl_test.cljc:12-30. | Extract a shared test-support namespace
  in a follow-up.
- P3 | :366 | (remove #(= "" %) results) would silently shift a destructure
  on a regression instead of failing at that round. | Drop the remove or add
  (is (every? seq results)) before destructuring.
- P3 | :78-82 + provenance | stream-ref? was revised for Dart map-key
  ordering but the QA log records the Dart lane was not re-run after that
  fix and the CLJS lane never ran (missing node_modules). | Orchestrator has
  since run CLJS fresh (1,917 tests, 0 failures); keep a CLJD run of this
  worktree on the merge checklist.

Verdict: READY
