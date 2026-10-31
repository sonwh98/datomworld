Created-Date: 2026-10-21
Coding-Agent: codex
Role: Independent Architect & Reviewer (non-Claude review per §14)
Worktree: /Users/sto/workspace/datomworld/.claude/worktrees/host-h0
Branch: host-h0

# Slice H0 re-review following H0-R1..R4

**Verdict: NOT SIGNED OFF.** All four requested fixes are verified and their original findings are closed. The slice still has an independently reproduced effect-scanner false negative, disclosed as a known limitation in the fix report, and the prior design reconciliation remains outstanding. This report does not authorize H1 or sign off the complete r9 design.

## Reviewed snapshot

HEAD: `9f6fc27dde358735f9e31f01556807b499ff7818`, plus seven uncommitted modified files: three closure manifests, `src/dev/host_boundary/closure.clj`, `test/dao/host/gate_test.clj`, `src/dev/subsystems.edn`, and `docs/design/test.subsystems.md`.

- SHA-256 of `git diff --binary`: `5e64259201d93dba5685c8feee28d27c33f7d57dc32be92d90ba60f37e163615`.
- Gate source SHA-256: `70753f2ae053393c208f62c7ab3efc3fa45b16be4cd90c09a67859bf10997e36`.
- Governing absolute-path design SHA-256: `4abe683a90bc308baa9d7c35e9a825ea168b3cf407ff5940657a42c96695c0ec`.

These match the engineer's fix snapshot and the previous review's governing design. Read the repository foundations, build/test guidance, previous review, fix report, and relevant governing H0/§8.1/§14 requirements. No implementation or runtime source was changed in this review. Runtime-source diff from `eacaed90` remains empty.

## Disposition of requested fixes

| Finding | Disposition | Independent evidence |
|---|---|---|
| H0-R1: function-body vectors | CLOSED | `walk-arity` separates the parameter vector from every body expression. The original `(defn injected [] [(random-uuid)])` probe returns `:fail`, exactly one `:random-uuid` violation, count 1 / allowed 0. |
| H0-R2: per-arity and per-lane scope | CLOSED | Each arity starts from the enclosing scope; lane selection precedes `file-scope`. Original multi-arity and conditional-shadow probes each report the expected effect. The latter also reports its new host arm. Source-site deduplication retains distinct columns. Regression tests cover shadowing, lane selection and site counts. |
| H0-R3: IO read exceptions | CLOSED | The real `read-sources` path catches a thrown `java.io.IOException`. Independent injected read failure returns `:scan-failure`, error `[["src/cljc/dao/data.cljc" "cannot read file: injected read failure"]]`, and 154 successfully scanned files. |
| H0-R4: subsystem registration | CLOSED | `dao.host` is registered with prefix `dao.host`, layer 0. The exact requested runner selects `dao.host.gate-test` and passes. |

## Verification performed

- `bb test:sub:clj dao.host`: exit 0; selects 1 of 268 test namespaces; **11 tests, 98 assertions, 0 failures, 0 errors**, confirmed in `target/profile/sub-clj.log`. After the passing summary, Babashka's process cleanup shutdown hook printed `Operation not permitted (sysctl failed)` under the sandbox. This was a cleanup diagnostic, not a test failure; it is recorded rather than describing the console output as entirely clean.
- `clojure -Sdeps '{:aliases {:review {:extra-paths ["test"]}}}' -M:review target/h0-review/probe.clj`: exit 0. Baseline passes with 155 scanned files. Required arm/effect injections fail correctly. Vector-body and multi-arity injections each report one effect at `dao/data.cljc:130`; conditional-shadow reports both the arm and effect. Empty, missing and malformed inputs return `:scan-failure`.
- The original probe prints only escaping IO exceptions, not a successful IO-failure result. Its silence alone is insufficient evidence for R3; the additional reviewer probe explicitly prints and asserts the verdict, filename, diagnostic and scanned count.
- Closure tool `--check`: exit 0; clj 142, cljs 140, cljd 141; shared 133; lane differences 9/7/8. The gate suite's independent traversal also passes.
- `bb src/dev/host_boundary/corpus_hashes.clj --check`: exit 0; 18 files identical.

Full JVM, Node, Dart and slow suites were not rerun. This is a targeted re-review of the JVM scanner/dev-config fixes, not a substitute for repository landing gates. The fix report's affected-test self-test failure remains attributed evidence; it was not independently rerun here.

## Remaining blockers

### H0-R5 — High: `specify!` target expressions still bypass the effect scan

Location: `test/dao/host/gate_test.clj:198-199,411-418`, the shared `deftype-heads` handling in `lane-effect-calls`.

`specify` and `specify!` share a walker with type declarations. Its `(drop 2 form)` skips the target expression, even though that expression executes. The engineer disclosed this limitation; it is pre-existing, not introduced by R1..R4. Disclosure does not establish an exemption from the recursive effect freeze.

Append this form in memory to `src/cljc/dao/space/index.cljc` (already in list 1; no random-uuid allowance in list 2):

```clojure
#?(:cljs
   (defn injected []
     (specify! (do (random-uuid) (js-obj))
       Object
       (toString [this] "x"))))
```

Actual: `{:verdict :pass, :scanned 155, :errors [], :arm-violations [], :effect-violations []}`. The entropy call executes when the function is called on the cljs lane, before the object is extended. The conditional excludes this cljs-only operation on the other lanes.

Control, at the same source location:

```clojure
#?(:cljs (defn injected [] (do (random-uuid) (js-obj))))
```

Actual: `:fail`, exactly one `:random-uuid` violation, count 1 / allowed 0, line 1416, and no arm violations. Thus the exemption arises from traversal of `specify!`, not the file's allowlist or lane resolution.

Required correction: walk the executable target of `specify`/`specify!` with the enclosing lexical scope, separately from method signatures and bodies. Add a regression for the target expression and preserve method-local shadowing. The source-text probes do not compile or execute the injected source.

### Prior design reconciliation — still open

The fix report explicitly excludes this work; the governing design has not changed since the previous review.

- §8.1 and §14 require `:pending` for `yin/vm.cljc` while OQ11 remains open; code uses `:conditional-retention-oq11` (`gate_test.clj:584`). The explanatory reason preserves intent, but the vocabulary differs.
- §8.1's category-to-class table and `list-1-categories` (`gate_test.clj:493-516`) disagree: implementation omits `:consumer` for migration-source, adds `:deferred-timers` for runtime-prereq, adds `:runtime-prereq` for deferred-scope, and permits lane-specific seam exceptions absent from the table.

Reconcile implementation and governing table explicitly before claiming the retagged H0 matches §8.1. The stronger per-lane validator passing against its own mapping does not resolve that disagreement. OQ11 itself need not be decided to close this documentation/data mismatch.

## Reproduction and required disposition

Additional reviewer evidence is in the worktree's ignored `target/h0-review/re-review.clj` and `target/h0-review/re-review.log`. Run:

```sh
clojure -Sdeps '{:aliases {:review {:extra-paths ["test"]}}}' -M:review target/h0-review/re-review.clj
```

Close H0-R1..R4. Fix H0-R5, reconcile the outstanding §8.1 vocabulary/mapping, and rerun the gate plus regression probes for independent review. **H0 remains NOT SIGNED OFF.**
