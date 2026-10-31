Created-Date: 2026-10-21
Coding-Agent: codex
Role: Independent Architect & Reviewer (non-Claude sign-off per §14)
Worktree: /Users/sto/workspace/datomworld/.claude/worktrees/host-h0
Branch: host-h0

# Slice H0 final verification: H0-R5 and §8.1 reconciliation

**Verdict: NOT SIGNED OFF.** H0-R5 and the OQ11 vocabulary mismatch are CLOSED. The category map now reproduces §8.1, but the effective validator passes only with an extra exception ledger that the governing design does not yet authorize. The prior design-reconciliation blocker remains OPEN. This review does not authorize H1 or sign off the complete r9 design.

## Reviewed snapshot

HEAD: `9f6fc27dde358735f9e31f01556807b499ff7818`, plus the same seven uncommitted modified files identified in the previous review: three closure manifests, the closure tool, gate test, subsystem registry and subsystem design documentation.

- SHA-256 of `git diff --binary`: `63601da9a8000d6d22d3261e3ad7c7566d9db862d7d1d74d19017aa59c3ce635`.
- Gate source SHA-256: `a10b75e70690c72b1dbea7f9dc01889f2bec263a9d405ed6053422377137d41a`.
- Governing design, main checkout, SHA-256: `4abe683a90bc308baa9d7c35e9a825ea168b3cf407ff5940657a42c96695c0ec`.

These match the engineer's fix report. Reviewed repository foundations, build/test guidance, the preceding review, engineer report, and relevant governing §3.1/§8/§8.1/§14 requirements. No implementation or governing-document changes were made during this review. Runtime-source diff from `eacaed90` is empty for `src/clj`, `src/cljc`, `src/cljs`, and `src/cljd`.

## Findings and disposition

### H0-R5 — CLOSED

At `test/dao/host/gate_test.clj:411-421`, `specify` and `specify!` explicitly walk `(second form)` using enclosing `bound`, before walking method declarations. The target does not inherit method parameter bindings. The regression cases at lines 924-931 and 997-1002 cover target effects, enclosing-local shadowing, and method-local shadowing.

Independently reran the exact reviewer probe. The valid cljs-conditional injection in `src/cljc/dao/space/index.cljc` now returns:

```clojure
{:verdict :fail, :scanned 155, :errors [], :arm-violations [],
 :effect-violations [{:file "src/cljc/dao/space/index.cljc",
                      :kind :random-uuid, :count 1, :allowed 0,
                      :lines [1416]}]}
```

The control returns the same violation. The `dao/data.cljc` target probe also fails with `:random-uuid` 1/0 at line 130. These are source-scanner probes; injected source is not compiled or executed. The probe prints the effect results rather than asserting them; the output was inspected directly, and the gate suite separately asserts the regression.

### OQ11 vocabulary — CLOSED

`yin/vm.cljc` uses `:migration-source :pending` at gate line 608, with the OQ11 retention reason intact. The migration-source `:clears` vocabulary includes `:pending` at line 495. This matches §8.1 and §14 without deciding OQ11 or changing the public encoding arity.

### §8.1 category reconciliation — OPEN, blocks sign-off

`list-1-categories` at gate lines 486-515 now matches all seven rows of the governing category-to-class table. However, `list-1-problems` defaults to `table-gaps` and adds its file-specific class to the permitted set (lines 518-561). Thus matching the table data is not the same as enforcing the table.

Independent evaluation of `(g/list-1-problems g/list-1 (g/committed-lane-classes) {})` produces **12 `:class-not-in-category` rows across eight files**. The default call with the ledger produces `[]`:

| Files | List-1 category | Extra class admitted | Lane rows |
|---|---|---|---:|
| `src/cljc/dao/space/transactor.cljc`, `src/cljc/yin/vm/ucf/authority.cljc` | `:deferred-scope` | `:runtime-prereq` | 6 |
| `src/clj/dao/jing/content/driver.clj` | `:seam-driver` | `:deferred-timers` | 1 |
| `src/{clj,cljs,cljd}/yang/antlr/cst.*` | `:seam-driver` | `:deferred-stage` | 3 |
| `src/cljd/dao/stream/{cbor,transit}/cljd.cljd` | `:seam-driver` | `:codec-track` | 2 |

The ledger accurately exposes an internal design conflict: §8.1's file rows prescribe these categories, whereas its mapping table excludes their closure classes. The tests at lines 847-853 establish that every current exception is needed and that their file set matches the bare-table failures. They do not establish governing authorization for those exceptions. The ledger's own docstring correctly says the disagreement is recorded, not resolved.

**Architect disposition:** accept the ledger as diagnostic evidence, not as completed reconciliation. Preserve the explicitly named baseline categories and the §3.1 closure classes. Amend §8.1 to state the permitted exceptions explicitly, preferably as the eight bounded file/category/class entries above, then make the validator enforce that documented mapping. If a ledger remains, bind each exception to its intended category as well as its file and class; the current lookup widens whichever category the file is assigned. Do not adopt an unrestricted “any class for any lane-specific file” exemption merely to obtain a pass. Preserve the explicit exclusion of the Dart Jing writer from seams and its H4 deletion obligation.

This is a concrete correction path, not an amendment applied by this review. After the design and validator agree, rerun the gate and obtain final review of that snapshot. OQ11 need not be resolved first.

## Verification

- `bb test:sub:clj dao.host`: **exit 0; 11 tests, 113 assertions, 0 failures, 0 errors**; selected `dao.host.gate-test`, 1 of 268 namespaces. Confirmed in `target/profile/sub-clj.log`. After success, Babashka's shutdown hook printed `Operation not permitted (sysctl failed)` under the sandbox; this was a cleanup diagnostic, not a test failure.
- `clojure -Sdeps '{:aliases {:review {:extra-paths ["test"]}}}' -M:review target/h0-review/re-review.clj`: **exit 0**. Effect outcomes are recorded above. Injected IOException returns `:scan-failure`, 154 scanned files, and the expected filename/diagnostic.
- Closure tool, using its documented `:hb` alias and `-m host-boundary.closure --check`: **exit 0**; clj 142, cljs 140, cljd 141; shared 133; lane-specific 9/7/8. The passing gate suite also includes independent-traversal agreement. An initial invocation without the required source-path alias failed to locate the namespace; the corrected documented command passed.
- `bb src/dev/host_boundary/corpus_hashes.clj --check`: **exit 0**, 18 files identical.

Full JVM, Node, Dart and slow suites were not rerun. This is the targeted H0 retag/scanner re-review permitted by §8.1, not repository landing verification. H0-R1..R4 remain closed; the requested suite and reviewer probe show no regression in those checks. The sole remaining sign-off blocker is the unresolved governing category/class reconciliation described above.
