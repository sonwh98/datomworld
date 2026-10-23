Created-Date: 2026-10-21
Coding-Agent: codex
Role: Independent Architect & Reviewer (non-Claude review per §14)
Worktree: /Users/sto/workspace/datomworld/.claude/worktrees/host-h0
Branch: host-h0

# Slice H0 independent architecture and code review

**Verdict: NOT SIGNED OFF.** The baseline artifacts and specified negative examples pass, but the effect scanner permits ordinary executable effect calls to escape detection. Actual file-read exceptions also bypass its promised scan-failure result. The requested subsystem command cannot run. No implementation fixes were made during this review.

## Reviewed snapshot and authority

Base: `eacaed90`; HEAD: `9f6fc27dde358735f9e31f01556807b499ff7818`. Review includes the five pre-existing uncommitted modifications: three closure manifests, `src/dev/host_boundary/closure.clj`, and `test/dao/host/gate_test.clj`. It is not a sign-off on HEAD alone.

SHA-256 of `git diff --binary` at review: `ffa51790aa041d35b158eec0f632d8102a00501c8b006fbbf423f169e257f14b`.
Gate source SHA-256: `4d424a86a6af4570861865f4c2379c3ef4133086f25451760d2ef89be3fafa76`.

Read the repository foundations and build/test guide, the supplied engineer report, and the supplied absolute design path. That design is no longer r6: its status heading says r8 and its body incorporates r9. Its SHA-256 is `4abe683a90bc308baa9d7c35e9a825ea168b3cf407ff5940657a42c96695c0ec`. The r6 report is historical evidence, not a description of all current edits. This review checks the requested H0 acceptance and flags later reconciliation differences; it does not independently sign off the whole r9 architecture or authorize H1.

## Acceptance checks

| Check | Independent result |
|---|---|
| Pure freeze | PASS for runtime source. `git diff eacaed90 -- src/clj src/cljs src/cljd src/cljc` is empty. Literal “no src/ change” is false: two new development tools live in `src/dev/host_boundary/`. The engineer disclosed this and the current design expressly adopts the location. |
| Change scope | Seven additions relative to base: three closure manifests, corpus manifest, two tools, one gate test. No codec-track runtime/test edits, no corpus-byte edits, no H1 or deferred-scope implementation. |
| Closure totals | PASS: clj 142, cljs 140, cljd 141, shared 133; lane-specific differences 9/7/8; union 155. |
| Reproducibility | PASS: closure tool `--check` exits 0. The independent reader-form traversal test agrees file-for-file for each lane. |
| Current gate | PASS: `:verdict :pass`, `:scanned 155`, errors and both violation lists empty. This is a scanner result, not proof of scanner completeness. |
| Required negative examples | PASS: injected host arm produces exactly one arm violation at `src/cljc/dao/data.cljc:130` and no effect violations. Injected `(random-uuid)` produces exactly one effect violation, count 1 / allowed 0, at the same line and no arm violations. Both scan 155 files and return `:fail`. |
| Scan failures | PARTIAL: empty input, nil/missing source, and malformed source return `:scan-failure`. Actual read exceptions escape; see H0-R3. |
| Corpus | PASS: all 18 entries match the file set and SHA-256 bytes; corpus tool `--check` exits 0; JVM corpus test independently checks digests. Both corpus trees have no diff from base. |
| Requested test command | FAIL: `bb test:sub:clj dao.host` exits 1, `unknown subsystem: dao.host`. |
| Direct namespace fallback | PASS: `clojure -M:test -n dao.host.gate-test` exits 0: **10 tests, 73 assertions, 0 failures, 0 errors**. The report's 65 assertions describe the older version. |

## Findings requiring correction

### H0-R1 — High: function-body vectors are skipped, allowing new effects to pass

Location: `test/dao/host/gate_test.clj:423-439`, `walk-fn`.

The scanner treats every top-level vector in a function tail as a parameter vector, then removes all those vectors from traversal. A vector expression in the body is executable and must be scanned. Appending this to the in-memory copy of `src/cljc/dao/data.cljc`, outside list 2, returns **`:pass` with no violations**:

```clojure
(defn injected [] [(random-uuid)])
```

Expected: `:fail` with one `:random-uuid` effect violation (allowed 0). Parse the function signature separately from body forms and walk every executable body expression. Add a regression that asserts the exact violation.

### H0-R2 — High: lexical scopes are merged across arities and host lanes

Location: `test/dao/host/gate_test.clj:423-439`; also `all-arms` / `file-scope` at lines 297-356.

`walk-fn` unions all arities' parameter names before scanning any body. This valid function therefore also returns **`:pass` with no violations** when appended to the same source:

```clojure
(defn injected
  ([random-uuid] random-uuid)
  ([] (random-uuid)))
```

The zero-arity body calls the core entropy function; the other arity's local parameter cannot shadow it. Keep bindings local to each arity.

There is an analogous lane-resolution problem: the scanner collects definitions from all reader arms into one shadow set. For this probe it reports the new arm but **no effect violation**, although the unqualified call resolves to core on the JVM:

```clojure
#?(:cljs (defn random-uuid [] 1))
(defn injected [] (random-uuid))
```

Resolve bindings/definitions/aliases per lane before combining observations, preserving the intended source-site counting convention. Add cross-arity and cross-lane regression cases. These defects undermine the list-2 freeze, even though the simple mandated injection passes.

### H0-R3 — Medium: actual file-read failure does not become `:scan-failure`

Location: `test/dao/host/gate_test.clj:714-722`, `read-sources`.

The `.isFile` check handles a missing path, but `slurp` runs outside `scan`'s exception handler. A permission or IO failure on an existing file throws before `gate` can return its result. Reproduced with `with-redefs` making `clojure.core/slurp` throw `java.io.IOException` while reading the existing `src/cljc/dao/data.cljc`: the exception escapes as `"injected read failure"`.

This fails closed as a test error, so it is not a false pass, but it violates the required distinct scan-failure result. Catch read exceptions per file, retain the filename and diagnostic, and test the actual read path in addition to nil input.

### H0-R4 — Medium: requested subsystem runner cannot select the gate

Location: `src/dev/subsystems.edn`.

There is no `dao.host` subsystem registration. The exact requested command exits before running tests. Register the intended subsystem and verify the command selects `dao.host.gate-test`, or explicitly revise the acceptance command to the direct namespace invocation. Do not report the failed command as a successful test run.

## Later design reconciliation observations

The retagged list contains no uncategorized entry, and tests now enforce category/class compatibility per lane, with negative checks. `ringbuffer.cljc` is retained as a seam; `yin/vm.cljc` retains its OQ11 condition. However, the current absolute design specifies `:pending`, while code uses `:conditional-retention-oq11`. Its category mapping also differs: code omits `:consumer` from migration-source, adds `:deferred-timers` to runtime-prereq and `:runtime-prereq` to deferred-scope, and adds lane-specific seam exceptions. These are explicit reconciliation items, not an exact implementation of the design's mapping table. Align the table and implementation before claiming the later retag is fully confirmed; the scanner defects above independently block H0 sign-off.

## Evidence and review limits

Reviewer probes and their full output are retained in the worktree's ignored `target/h0-review/probe.clj` and `probe.log`. They operate on in-memory source copies; no production source was modified. Reproduce with:

```sh
clojure -Sdeps '{:aliases {:review {:extra-paths ["test"]}}}' -M:review target/h0-review/probe.clj
clojure -M:test -n dao.host.gate-test
clojure -Sdeps '{:aliases {:hb {:extra-paths ["src/dev"] :extra-deps {org.clojure/tools.namespace {:mvn/version "1.5.0"}}}}}' -M:hb -m host-boundary.closure --check
bb src/dev/host_boundary/corpus_hashes.clj --check
```

Full JVM, Node, Dart, and slow suites were not rerun by this reviewer. The original engineer's per-lane counts remain attributed evidence. The current design permits the retag validation to use the gate namespace and closure `--check`; both were run. Existing passing tests cannot substitute for correcting the reproduced false negatives.

**Required disposition:** fix H0-R1/R2, normalize read failures for H0-R3, resolve the runner and design reconciliation items, then rerun the original acceptance plus exact regression probes. H0 remains **NOT SIGNED OFF** until re-reviewed.
