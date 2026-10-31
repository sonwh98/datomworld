Completed-GMT: 2026-10-11 07:11:26 GMT
Completed-Local: 2026-10-11 14:11:26 Asia/Bangkok

# semantic-register-vm phase 6, slice 6a

Implementation is ready for non-author review. Acceptance items 1–10 pass on the executed lanes. Item 11's three-host gate is unmet: Dart is **NOT RUN**, blocked during compiler initialization by sandbox-denied Flutter cache writes. No review or sign-off is claimed; those remain assigned to claude-fable-5-1.

Worktree: `/Users/sto/workspace/datomworld-srvm-p6a`; branch: `srvm-phase6a`; HEAD remains `f11771e9`. No checkout switch, staging, or commit occurred. No governing design, phase-4b source, phase-5 source, or existing test was edited. No scanner, layout, `lift-task`, or `lower-task` was implemented.

## Changed files

All four implementation/test files are **new**, currently untracked:

| File | Work |
|---|---|
| `src/cljc/yin/vm/semantic_register/frame.cljc` | Pure ordered `defect` and once-per-body `task-defect`; structural checks precede dependent reads; saved-window and lexical/free-environment checks; contexts and tail bookkeeping. |
| `src/cljc/yin/vm/ucf/rows.cljc` | Declarative row/pending tables, frame bijection, content addresses, census, refinement and exhaustive individualization search, child cell scopes, bounded work, graph decoding, numbering before frame checks, and correspondence-based re-emission. |
| `test/yin/vm/semantic_register/frame_test.cljc` | Evaluator-minted reference frames, four wrapper shapes, empty-K root tail call, twenty-five tamper rows, nil membership, free-env shadowing, structural precedence, integral float carrier refusal, and tail bookkeeping. |
| `test/yin/vm/ucf/rows_test.cljc` | Bijection including full nested capture/closure structure, hashes, window canonicality, cell/stream renaming, unordered store containers, alias topology, census authentication, bounded search, child scopes/DAG sharing, full pending-envelope preservation, and task-root validation order. |

Modified tracked files: **none**. New supporting artifacts under `collab/` include this report, lane logs, nine mutation logs, `6a-mutations.py`, `6a-mutations.json`, and the verified source backups. The pre-existing task prompt/stdout files were present before work; they are not claimed as authored deliverables.

## Exact verification outcomes

| Lane/check | Outcome | Tests | Assertions | Failures | Errors | Evidence |
|---|---|---:|---:|---:|---:|---|
| G0, final frame namespace | PASS, exit 0 | 9 | 221 | 0 | 0 | `collab/6a-frame-g0-final.log` |
| G0, final rows namespace | PASS, exit 0 | 12 | 260 | 0 | 0 | `collab/6a-rows-g0-final.log` |
| JVM, selected 14 namespaces | PASS, exit 0 | 186 | 4701 | 0 | 0 | `collab/6a-jvm-final.log` |
| JVM, Node's additional parity dependency | PASS, exit 0 | 5 | 60 | 0 | 0 | `collab/6a-jvm-dependency-parity.log` |
| Node, selected namespaces plus required parity dependency | PASS, exit 0 | 191 | 4760 | 0 | 0 | `collab/6a-node-final.log` |
| Dart attempt | **NOT RUN**; compile command exit 1 | — | — | — | — | `collab/6a-dart.log` |
| kondo, four changed paths | PASS, exit 0; 0 errors, 0 warnings | — | — | — | — | `collab/6a-kondo-final.log` |
| cljstyle, four changed paths | PASS, exit 0 | — | — | — | — | `collab/6a-cljstyle-final.log`, final direct check |
| trailing whitespace, four changed paths | PASS; zero matches; `rg` exit 1 means no matches | — | — | — | — | `collab/6a-whitespace-final.log`, final direct scan |

Node additionally lists `Testing yin.vm.parity-test`, brought in by test dependencies. It also explicitly lists `Testing yin.vm.semantic-register.frame-test` and `Testing yin.vm.ucf.rows-test`. Its build finished with zero warnings. The JVM-only source-purity assertion accounts for one assertion absent on Node; the parity dependency was run separately on JVM so its outcome is explicit.

Exact final G0 commands:

```sh
clojure -M:test -n yin.vm.semantic-register.frame-test
clojure -M:test -n yin.vm.ucf.rows-test
```

Exact selected JVM command:

```sh
clojure -M:test -n yin.vm.semantic-register.frame-test -n yin.vm.ucf.rows-test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.link-test -n yin.vm.semantic-register.load-paths-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.ucf-test -n yin.vm.ucf.handoff-test -n yin.vm.ucf.safepoint-harness-test
clojure -M:test -n yin.vm.parity-test
```

Exact Node command (foreground, via the `:cljs` alias):

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^(yin\\.vm\\.semantic-register\\..*-test|yin\\.vm\\.ucf\\.(rows|handoff|safepoint-harness)-test|yin\\.vm\\.ucf-test)$"}'
```

Exact Dart attempt:

```sh
bb src/dev/cljd_agg.clj --only yin.vm.semantic-register.frame-test,yin.vm.ucf.rows-test,yin.vm.semantic-register.code-test,yin.vm.semantic-register.analysis-test,yin.vm.semantic-register.linearize-test,yin.vm.semantic-register.vm-test,yin.vm.semantic-register.effects-test,yin.vm.semantic-register.parity-test,yin.vm.semantic-register.link-test,yin.vm.semantic-register.load-paths-test,yin.vm.semantic-register.walker-trace-test,yin.vm.ucf-test,yin.vm.ucf.handoff-test,yin.vm.ucf.safepoint-harness-test
```

The Dart attempt produced these actual errors, before tests could execute:

```text
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.56585: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
Execution error at cljd.compiler/mk-live-analyzer-info (compiler.cljc:214).
EOF while reading
Exception in thread "Thread-0" java.lang.RuntimeException: Operation not permitted (sysctl failed)
cljd compile failed, exit 1
```

The log includes repeated denied cache writes with other PID suffixes. No Dart compile/test success or Dart assertion count is claimed. No sandbox escalation was requested. The orchestrator must re-run the lanes.

Exact lint/format/whitespace commands:

```sh
clj -M:kondo --lint src/cljc/yin/vm/semantic_register/frame.cljc src/cljc/yin/vm/ucf/rows.cljc test/yin/vm/semantic_register/frame_test.cljc test/yin/vm/ucf/rows_test.cljc
cljstyle check src/cljc/yin/vm/semantic_register/frame.cljc src/cljc/yin/vm/ucf/rows.cljc test/yin/vm/semantic_register/frame_test.cljc test/yin/vm/ucf/rows_test.cljc
rg -n '[[:blank:]]+$' src/cljc/yin/vm/semantic_register/frame.cljc src/cljc/yin/vm/ucf/rows.cljc test/yin/vm/semantic_register/frame_test.cljc test/yin/vm/ucf/rows_test.cljc
```

No full `bb test` or full `bb test:sub yin.vm` run occurred. The task's explicit scoped lane instructions were followed, with every `-n` written literally; no shell word-splitting variable or background execution was used.

## Numbered acceptance list (§8.1)

1. **PASS — `contract-is-pure`.** Public `defect` takes `[frame code-map context task]`. It reads plain task data and no running kernel or engine. The JVM test inspects the namespace form for forbidden engine/kernel dependencies; its reader conditional puts `:cljd` first.
2. **PASS on JVM/Node — `every-reference-frame-validates`.** Forty-nine runs: rows 1, 2, 5, 6, 13–16, 18, 19, 21, 22 across bare/operand/depth-3/captured wrappers, plus row 20 unwrapped. Waits, parked records, each K through its enclosing frame, and recursively reached captures validate. Row 18 carries a composition-created module-store snapshot with two cursors and a module-owned closure. Row 19 is checked as halted, with no invented frame. Row 20 asserts empty K. Fixture setup distinctions are recorded below.
3. **PASS — `each-rule-refuses-its-tamper` and `first-defect-is-structural`.** The table has 24 frame tamper rows (C2 contributes kind and bounds), plus X1 through `task-defect`, giving the prescribed 25 rows for 24 rules. Each frame result checks the expected kind and a vector path. Malformed K beats the bad window. Additional integral-float, delivery, bookkeeping, and nil-presence checks supplement the table.
4. **PASS — `nil-valued-register-is-a-member`.** An actual row-22 saved window has a present nil-valued key; deleting it yields `:window-missing`. The codec bijection retains the key as `[:lit nil]`.
5. **PASS — `worked-example-saved`.** `saved(4,2) = #{1}` is asserted against computed liveness. The valid `{1 :f}`, self `{1 :f 2 :x}`, missing `{}`, and extra `{1 :f 3 :x}` cases give the specified outcomes.
6. **PASS — `free-env-mismatch`.** Row 21 starts with `{x 99 y 7}`, and its parked activation binds lexical x to 1. Changing/removing unshadowed y fails, removing lexical x fails, changing lexical x to 98 passes, and reserved `yin/def` in the root environment fails X1.
7. **PASS — `bijection`.** Every reference fixture's frame is classified into portable codec values and round-trips exactly. Closures retain segment address, entry, params, lexical environment and store-of; captures retain full act/K structure, including nested captures. Native seals and host objects are classified by test helpers, not treated as portable literals. The converse re-emission retains the root id.
8. **PASS — `row-ids-are-jing-default`.** Every emitted content row equals `jing/segment-key` of its body and parses as algorithm `:blake3`. No legacy body reader or alternate identity scheme was added.
9. **PASS — `window-sorted-and-unique`.** Rehashed out-of-order and duplicate-key window tampers refuse as `:yin.k/undecodable` in canonical form; they are not merely hash mismatches or later frame refusals.
10. **PASS — `canonical-under-is-renaming-invariant`.** Distinct lane stream identities/source cell identities, explicitly permuted wire cell numbering, and a store payload containing a stream set and a cursor-keyed map re-emit to equal roots under explicit correspondence maps. Missing correspondence coverage fails. Alias topology, authenticated cell positions, symmetric search/multiplicity, work exhaustion, and child scopes are separately tested.
11. **FAIL / gate unmet — three-host verification.** Kondo is clean and the selected JVM/Node suites pass, with both required new namespaces printed on Node. Dart is NOT RUN because of the recorded sandbox block. A three-host green claim and sign-off would be false.

## Tests that can fail: actual source mutations

Backups were taken **before any mutation** into `collab/6a-verified-backups/`, and verified with `cmp`. The final source remains byte-identical to those backups:

```text
frame.cljc SHA-256 30cfb19446e8311b3d15c9f739e22d899ff918835df21f7092c89dc31c4b4ca0
rows.cljc  SHA-256 50560bddf8227efdb6c37eb55d8a845fc6de167c619cfbf7e3538fc46c98e2a6
```

These SHA-256 values are backup integrity checks, not code/row identities; all new code/row identities use Jing's default digest.

Every mutant was applied to one source file, run in the foreground, and restored from its verified backup in a `finally` block. After **each** restoration, `cmp <path> <backup>` and `git diff --quiet HEAD -- <path>` both exited 0. Since these are new untracked source files, the HEAD diff alone is not proof of restoration; the byte comparison and SHA checks provide that proof. No mutation survived.

| ID | Temporary source mutation | Named test with actual FAIL | Assertions | Failures/errors | Exit | Restore cmp / HEAD diff |
|---|---|---|---:|---|---:|---|
| M1 | Disable missing saved-window membership check | `frame-test/nil-valued-register-is-a-member` | 3 | 1 / 0 | 1 | 0 / 0 |
| M2 | Disable required lexical-key presence | `frame-test/free-env-mismatch` | 7 | 1 / 0 | 1 | 0 / 0 |
| M3 | Disable equality outside the lexical chain | `frame-test/free-env-mismatch` | 7 | 2 / 0 | 1 | 0 / 0 |
| M4 | Bypass destination equality | `frame-test/each-rule-refuses-its-tamper` | 50 | 1 / 0 | 1 | 0 / 0 |
| M5 | Use L(pc) instead of saved(pc,rd), retaining the virtual destination | `frame-test/worked-example-saved` | 5 | 1 / 0 | 1 | 0 / 0 |
| M6 | Suppress the cross-body resume-pc defect | `frame-test/each-rule-refuses-its-tamper` | 50 | 1 / 0 | 1 | 0 / 0 |
| M7 | Suppress canonical-numbering root comparison | `rows-test/numbering-precedes-frame-defect` | 2 | 1 / 0 | 1 | 0 / 0 |
| M8 | Bypass required stream-correspondence coverage | `rows-test/canonical-under-is-renaming-invariant` | 3 | 1 / 0 | 1 | 0 / 0 |
| M9 | Remove the search-node budget charge | `rows-test/refinement-search-preserves-unordered-multiplicity` | 4 | 2 / 0 | 1 | 0 / 0 |

Each ran exactly one named test using:

```sh
clojure -M:test -n <literal namespace> -v <fully qualified literal test var>
```

The exact argv, logs, exit codes, named-failure checks and restoration results are in `collab/6a-mutations.json`; the reproducible foreground runner is `collab/6a-mutations.py`; each test's actual FAIL output is in `collab/6a-M1.log` through `collab/6a-M9.log`. Test fixtures were subsequently strengthened with the explicit wire-number permutation/store payload and a full task-root check; the mutated production source itself was not subsequently changed.

## Ambiguities, findings, and smallest readings

- **Unspecified codec API details (§2.7/§8.1).** The design names the frame bijection and abstract-task procedure without concrete function signatures for their auxiliary representation. The implementation documents `{:kind k :slots [...]}` abstract rows, explicit decoded row/cell wrappers, and optional value encoder/decoder arguments. `frame->rows` returns a frame-rooted codec fragment in the version-3 envelope; `emit` accepts an already assembled abstract task. These are codec operations, not an exported running task or `lift-task`/`lower-task`. The full transport path still belongs to 6b.
- **Purity dependency list (§3.1).** E2 needs the set of names over A's lexical owner chain, rather than a positional name lookup. The validator directly walks `code/body-ranges` and closure params, so `debruijn/resolve-name` is unnecessary and is not required. This preserves the specified chain/presence semantics, including shadowing and duplicate names, while avoiding an unused dependency.
- **Resource refusal spelling (§8 diagnostics versus draft §10.10).** §10.10 expressly leaves work-bound limits and refusal mapping to implementation/composition and calls for a qualified unsupported/resource refusal. The codec uses `:yin.k/status :yin.k/unsupported`, kind `:resource-limit`, and a budget-category path. It never returns `:noncanonical-cells` for unfinished search and never attaches a resource. This specific spelling merits reviewer confirmation against the draft's general “no new top-level outcome” wording; no design was amended. Defaults are 10,000 rows, 64 cells, depth 256, 200,000 work charges, 10,000 search nodes and 16,777,216 encoded bytes; callers may tighten/override bounds without changing canonical bytes.
- **Reference fixture setup (§5.2 versus 6a's frame-only acceptance).** Streams/cursors are supplied by the test composition. Row 20 uses a preconstructed cursor stored under c and executes the same unwrapped root tail-call body, instead of replaying the design's two-batch setup; the test asserts empty K and the resulting minted tail frame. Row 18 mirrors the existing composition-created module-store snapshot shape using the register machine. These tests establish validator/codec facts, not full task transport or post-lower execution. The latter belongs to 6b.
- **Initial fixture errors were real failures, not implementation evidence.** Early iterations had reader errors, an unresolved `stream/next!` caused by a missing stream-module registry, and a numbering tamper that was out of order and correctly failed at the earlier canonical-form gate. Those were corrected. A preliminary frame-namespace invocation made before its test file existed ran zero tests and is not counted as a passing G0 check. An earlier native-value projection omitted closure/capture internals; the final bijection classifies and retains their complete portable structure instead.
- **Numbers were checked, not silently corrected.** The initial analysis baseline `clojure -M:test -n yin.vm.semantic-register.analysis-test` passed 8 tests/497 assertions, including the worked liveness facts. The new tamper-table count (24 frame rows plus X1) and `saved(4,2)=#{1}` passed on their first executable checks. The earliest whole-matrix attempt threw during fixture construction before reaching its 49-run count assertion; that count was established after fixing the fixture, and is not claimed as verified in that failed attempt. No wrong hand-derived saved-register or oracle number was found.
- **Clock discrepancy.** The completion timestamp is the clock tool's actual `2026-10-11 07:11:26 UTC`, converted to Bangkok. It precedes the supplied Created-GMT 07:30 marker; that supplied marker was not used to invent a later completion time.

## Unresolved concerns and incomplete work

- Dart compilation and execution remain unverified. The orchestrator must run the scoped Dart lane, and re-run all lanes before acceptance.
- Non-author review/sign-off remains outstanding. In particular, review the concrete resource-refusal spelling and the documented codec-fragment API boundary.
- The codec consumes classified portable values/abstract rows. It does not authenticate native seals, scan dependencies/trees, serve or attach streams, enforce custody admission, reconstruct a kernel, or perform full task lift/lower. Those are intentionally outside 6a and no end-to-end task-transport success is claimed.
- Full task transport (6b) and layouts (6c) remain unimplemented by this slice, as required.
- The implementation and executed checks are complete for the claimed scope; the three-host acceptance gate is incomplete. Nothing was staged or committed.
