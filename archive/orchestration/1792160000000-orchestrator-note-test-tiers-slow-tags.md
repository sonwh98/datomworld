Created-GMT: 2026-10-12 14:00:00 GMT
Created-Local: 2026-10-12 21:00:00 Asia/Bangkok

# Note to the C4 / yang.python orchestrator: test tiers and ^:slow tags (owner-approved 2026-10-12)

From: the semantic-register-vm orchestrator (claude, session e77b6b18). Relayed by the owner.

## What is changing

The owner asked for quick tests to be separated from slow tests so slow ones run less often. I measured every JVM test namespace and every deftest in the slow candidates on a 48-core Linux box (jupiter), and tagged the **      63 deftests that take 5 s or more run alone** with `^:slow` (and, in `.cljc` files, wrapped their bodies in `(dao.test-slow/guard "name" (fn [] ...))`, the repo's existing idiom from `docs/agents/build-n-test.md`). **      35 of the       63 are in your track's files** (`yang.python.antlr.*` and `yin.repl.python-antlr-test`).

- Branch: `tiers-slow-tags` (commit f107670d, pushed to origin). It changes 22 test files only: no source, no assertion, no test logic. It merges to master once the slow tier has passed on a server (the owner approved: "merge the test tiers when the slow tier passes").
- After the merge: `bb test:clj` and `clojure -M:test -e :slow` SKIP those tests; `bb test:slow` / `clojure -M:test -i :slow` run them. Quick tier measured at 5 min 3 s sharded by subsystem on jupiter (13 shards); the biggest single saving is `yin.repl.python-antlr-test`, 10 tests worth about 26 to 39 minutes.

## What you need to know

1. **Merge conflicts are possible.** Inside every guarded test the body is re-indented by 4 columns, so any uncommitted or unmerged edit you have in these files will conflict textually. Rebase on master after the merge; the guard is mechanical (the same shape as `int_conv_test.cljc` already used). Your worktrees to check: anything touching the 22 files below.
2. **Cadence (owner decision):** quick tier at every checkpoint and landing; the slow tier before merging a big change and at each phase boundary of a multi-phase track. For a Python slice landing, `bb test:sub:clj yang.python --slow` is still the right slow check, as the doc says.
3. **New tests:** the rule is "tag a deftest slow when it takes 5 s or more on any lane". If your next slices add Python e2e tests above that, tag them the same way (JVM-only `.clj` files: `^:slow` alone; `.cljc`: `^:slow` plus the guard).
4. **Several namespaces still have a quick part of 25 to 69 s** (e2e-c2, prelude-parity, int-ops, holder.driver, compose-rows): many tests each just under 5 s (the Python e2e per-test floor of about 2 to 3 s). Not tagged. Say so if you want a lower threshold for them.
5. **Where to run:** two Linux servers (shiva, 24 cores; jupiter, 48 cores) are documented in `docs/agents/build-n-test.md` ("Remote test servers") with a recipe: push your branch, fetch it into a scratch clone under `~/ci/` on the server, run the sharded quick tier. Never test in `~/workspace/datomworld` there (it is the owner's working copy).

## The       35 tagged tests in your track's files (seconds when run alone on jupiter)

  - yang.python.antlr.e2e-c1-test :: raise-in-finally-and-handler-stack-test (102.9s)
  - yang.python.antlr.e2e-c1-test :: tuples-are-values-and-unhashable-lists-test (31.7s)
  - yang.python.antlr.e2e-c2-test :: dropped-generators-after-repeated-suspension-test (85.5s)
  - yang.python.antlr.e2e-c2-test :: dropped-generators-are-collected-test (17.0s)
  - yang.python.antlr.e2e-c2-test :: genexp-nesting-and-call-arguments-test (5.0s)
  - yang.python.antlr.e2e-test :: zero-division-test (102.2s)
  - yang.python.antlr.e2e-test :: recursion-error-is-a-builtin-test (30.9s)
  - yang.python.antlr.e2e-test :: uncaught-exception-test (30.9s)
  - yang.python.antlr.e2e-test :: boolean-operators-test (30.7s)
  - yang.python.antlr.e2e-test :: class-attributes-and-identity-test (30.7s)
  - yang.python.antlr.e2e-test :: lists-alias-and-index-test (30.4s)
  - yang.python.antlr.e2e-test :: strings-test (30.4s)
  - yang.python.antlr.e2e-test :: dict-order-and-key-normalization-test (30.0s)
  - yang.python.antlr.e2e-test :: sys-is-refused-test (5.8s)
  - yang.python.antlr.int-conv-test :: digit-limit-test (53.9s)
  - yang.python.antlr.int-conv-test :: departure-pins-test (5.9s)
  - yang.python.antlr.int-literal-test :: huge-hex-test (6.7s)
  - yang.python.antlr.int-ops-parser-test :: augmented-index-and-comparison-test (91.1s)
  - yang.python.antlr.int-ops-parser-test :: conversion-builtins-source-test (30.0s)
  - yang.python.antlr.int-ops-parser-test :: augmented-division-power-test (28.5s)
  - yang.python.antlr.int-ops-parser-test :: divmod-builtin-test (28.1s)
  - yang.python.antlr.int-ops-parser-test :: augmented-bitwise-shifts-test (27.9s)
  - yang.python.antlr.int-ops-test :: float-power-exponent-limit-test (5.2s)
  - yang.python.antlr.prelude-parity-test :: init-is-idempotent-test (5.4s)
  - yang.python.antlr.safepoint-test :: insertion-is-deterministic-test (5.2s)
  - yin.repl.python-antlr-test :: probe-reports-irreparable-errors-at-once-test (461.1s)
  - yin.repl.python-antlr-test :: a-failed-first-round-keeps-the-runtime-uninitialized-test (386.9s)
  - yin.repl.python-antlr-test :: vm-rebuild-is-a-new-process-test (316.1s)
  - yin.repl.python-antlr-test :: an-exception-keeps-the-session-test (261.3s)
  - yin.repl.python-antlr-test :: reset-is-a-new-process-test (240.5s)
  - yin.repl.python-antlr-test :: probe-buffers-across-lines-test (193.4s)
  - yin.repl.python-antlr-test :: assignment-then-print-test (184.9s)
  - yin.repl.python-antlr-test :: a-failed-later-round-keeps-the-previous-marker-test (115.2s)
  - yin.repl.python-antlr-test :: probed-lines-keep-their-whitespace-test (114.1s)
  - yin.repl.python-antlr-test :: probe-buffers-an-open-block-test (77.0s)

## The other tagged tests

  - dao.space.dht-test :: sustained-rounds-are-each-reported-once-in-order-and-drain (11.1s)
  - yang.clojure.stream-eval-test :: a-stream-is-ingested-transformed-and-emitted-across-rounds (8.8s)
  - yin.repl.dht-head-test :: the-moved-line-names-the-principal-that-moved-each-name (15.4s)
  - yin.repl.dht-head-test :: a-simulated-crash-restarts-at-the-head-heads-edn-holds (13.1s)
  - yin.repl.dht-head-test :: a-move-is-reported-once-and-each-name-its-own (7.8s)
  - yin.repl.dht-head-test :: a-reader-follows-requires-moves-and-restarts-from-heads-edn (6.3s)
  - yin.repl.dht-test :: with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles (6.0s)
  - yin.repl.main-test :: reattaching-resumes-the-served-stream-and-the-deposit-medium (13.1s)
  - yin.repl.main-test :: killing-the-connection-is-observable-and-requests-are-lost (12.4s)
  - yin.repl.main-test :: a-release-held-back-for-the-whole-drain-stays-durable-and-a-restart-sends-it-test (9.5s)
  - yin.repl.main-test :: every-vm-is-selectable-and-answers-through-the-streams (5.4s)
  - yin.repl.query-test :: ast-and-occ-are-supplied-when-named-in-in (5.5s)
  - yin.repl.query-test :: scalar-in-inputs-bind-beside-the-implicit-index (5.2s)
  - yin.vm.linker.dht-end-to-end-test :: a-reader-requires-a-published-module-by-name-on-all-four-vms (12.0s)
  - yin.vm.linker.dht-end-to-end-test :: an-empty-export-list-publishes-the-no-op-module-at-the-prompt (7.1s)
  - yin.vm.ucf.compose-rows-test :: completion-recovery-unknown-policy-transaction-is-reconciled-once-test (5.7s)
  - yin.vm.ucf.holder.driver-test :: exit-journal-cuts-never-restore-execution-test (29.4s)
  - yin.vm.ucf.safepoint-harness-test :: every-corpus-segment-to-every-row-matches-the-reference (43.9s)
  - yin.vm.ucf.safepoint-harness-test :: one-pc-reached-at-two-depths-hands-off-at-each (5.2s)
  - yin.vm.ucf.stage-e-process-test :: the-cross-host-handoff-across-processes-test (25.4s)
  - yin.vm.ucf.stage-e-rows-test :: row-7-kills-around-completion-test (28.0s)
  - yin.vm.ucf.stage-e-rows-test :: inbox-and-journal-cuts-test (10.7s)
  - yin.vm.ucf.stage-e-rows-test :: row-2-export-phases-test (9.0s)
  - yin.vm.ucf.stage-e-rows-test :: clause-8-admission-order-test (8.6s)
  - yin.vm.ucf.stage-e-rows-test :: row-2-carrier-failures-test (7.2s)
  - yin.vm.ucf.stage-e-rows-test :: clause-3-sequence-and-regrant-test (6.8s)
  - yin.vm.ucf.stage-e-rows-test :: row-6-partition-test (6.7s)
  - yin.vm.ucf.stage-e-rows-test :: row-4-kills-around-result-delivery-test (6.1s)

Full per-test data: `collab/slow-deftests-5s.tsv` in the `datomworld-srvm-tiers` worktree. The orchestrator log entry for 2026-10-12 has the context.
