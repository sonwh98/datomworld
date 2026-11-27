Role: Architect (numeric semantics)
Coding-Agent: codex
Date: 2026-11-26
Target: `fix/float-kind-portability` at `6a7fb586ac1f1b35487c88b0fc6108a6aa440664`
Governing ruling: `1795693415000-architect-float-kind-portability-ruling.gpt-6-astra.findings.md`

**Verdict: REMAINING-BLOCKERS. Do not merge `6a7fb586` to master.** The selected token-time architecture is implemented, and substantial historical evidence supports the intended float repair. However, the landed D-2 edit breaks the acceptance-test namespace, and bounded execution probes expose reader data loss and incorrect guest equality. The independent APPROVE at `42b8cfc6` does not establish correctness of the final tip or cover these cases.

**Inspection identity and custody**

The requested darkstar mirror is NOT the landed tip: `/Users/sto/workspace/datomworld-floatkind` has HEAD and its locally cached origin branch at `8214a3ed8d1e6627cf9a4eb6fdb896076ba22316`, plus an uncommitted change to `test/yang/clojure_float_kind_test.cljc`. It lacks the engineer/reviewer findings and cited evidence directory. The named engineer findings also do not exist in the main darkstar checkout. `git cat-file -t 6a7fb586` there fails.

Read-only `git ls-remote origin` verified the requested branch at `6a7fb586ac1f1b35487c88b0fc6108a6aa440664` and master at `bab7640f0b162444285ab622469de1abbefbb668`. I therefore inspected the clean `/home/sto/ci/datomworld-floatkind` clone on `jupiter.stigmergy.systems`, whose HEAD matches the origin tip exactly. All landed-source line references below refer to that commit, not the stale darkstar worktree. No checkout, fetch, reset, commit, push, or implementation edit was performed.

**Required dispositions**

1. **B-1 / P1 — D-2 makes the seven-gate namespace unloadable.** `test/yang/clojure_float_kind_test.cljc:16` starts with `[clojure.test ...]` directly inside `ns`. The diff of `6a7fb586` deletes the entire line `(:require [clojure.string :as str]`, including the `:require` opener, while retaining its closing delimiter. Requiring the actual landed namespace fails with `Syntax error macroexpanding clojure.core/ns ... Call to clojure.core/ns did not conform to spec.` This is a new defect introduced after the independent review. Restore the require clause while removing only the unused dependency; run lint and freshly load/build/run the affected acceptance namespace on the applicable hosts at the corrected commit. Cached pre-D-2 binaries and logs cannot certify this source.

2. **B-2 / P1 — The source set reader silently drops false/nil and every later occurrence.** `src/cljc/yang/clojure/reader.cljc:374–384`, specifically `(if-let [x (first xs)] ...)`, treats a false/nil element as exhaustion. Live results: `#{false 1.0 2}` reads as `[#{}]`; `#{nil 1.0}` also reads as `[#{}]`; `#{1 false 2}` reads as `[#{1}]`. The standard catalog evaluates `#{false 1.0 2}` to `#{}`. This directly violates RULING 2 and Gate 2 occurrence preservation, including loss of the floating occurrence. Test sequence presence, not element truthiness; cover false/nil at all positions and duplicate checking after them on JVM and Node.

3. **B-3 / P1 — Scoped variadic equality returns true without comparing false/nil operands.** `src/cljc/yang/clojure/numeric.cljc:177–190`, specifically line 185, repeats the same truthiness/exhaustion error. Direct probes return true for `(clj-= 1 1 false)`, `(clj-= 1 1 nil 2)`, and `(clj-= 1.0 1.0 false)`. Actual standard-catalog source `(= 1 1 false)` and `(= 1.0 1.0 nil)` both return `true`. They must be false under the declared `data/equiv` contract. `clj-!=` inherits the wrong negation. Traverse the remaining sequence independently of its values and add variadic source-path tests for both predicates, including operands after false/nil.

4. **B-4 / P1 — A valid keyword collides with the reader's discard sentinel.** `reader.cljc:350–353`, `422–431`, `499–502`, and `697–700` identify discard by equality with ordinary keyword `::discard`. Therefore source `:yang.clojure.reader/discard` reads as `[]`, and `[1 :yang.clojure.reader/discard 2]` reads as `[[1 2]]`. The keyword is valid guest Data, not a reader directive. Represent discard as separate parser control state or another unforgeable parse-local mechanism, and test that all valid literal values remain representable in top-level, collection, and required-form positions.

5. **B-5 / P1 — Anonymous-function expansion captures user variables.** `reader.cljc:451–483` chooses fixed symbols `p__1`, `p__2`, and `p__rest` for `%` parameters. The standard catalog returns `4` for `(let [p__1 10] (#(+ p__1 %) 2))`; the equivalent explicit function `(let [p__1 10] ((fn [x] (+ p__1 x)) 2))` returns `12`. Upstream reading keeps the original `p__1` distinct from its generated parameter; the new reader rewrites both references to the same binding. Use capture-avoiding, parse-scoped names while preserving deterministic canonical output. Test positional and rest parameters against colliding user bindings. This finding concerns the newly advertised anonymous-function support; it does not assert that the old compiler accepted upstream `fn*` lowering.

6. **B-6 / P1 — The replacement reader rejects previously working, unaffected source.** `reader.cljc:107–125` incorrectly treats apostrophe inside a token as terminating; `reader.cljc:640–642` unconditionally refuses syntax quote. With the old `clojure.tools.reader` source stage and the same current compiler/VM, `(let [x' 10] x')` executes to `10`, and source backtick followed by `1` executes to `1`. The new reader respectively throws `Unmatched delimiter: )` and `Syntax-quote (\`) is not supported in portable source reading`. These are integer-only compatibility regressions, not consequences required by preserving floats. Restore the previously supported grammar, with differential lexical tests. Any intentional narrowing requires a separately explicit architectural disposition and public capability contract; the float ruling did not authorize it. In particular, merely documenting a refusal in the engineer's findings is insufficient.

7. **B-7 / required Gate 5 evidence — The claimed continuation test does not carry an already-produced integral float across the pause.** `gate5-parked-float-result-crosses-the-round` parks while `(+ <hole> 0.5)` is pending, appends serialized `1.5`, computes `2.0` after resume, and serializes that final result. It tests resumed arithmetic and value encoding, but no integral-valued floating result exists before the continuation boundary. Add a case that computes an integral float first, retains it in guest continuation/state across a pause/resume (and snapshot serialization if claiming snapshot coverage), then verifies its float kind, exact value/bits and canonical identity. Keep the existing test as useful complementary coverage.

**Ruling-by-ruling verification**

| Obligation | Observed landed implementation/evidence | Disposition |
| --- | --- | --- |
| RULING 1: semantic oracle | `gate4-data-oracle` calls exported Data operations and checks scalar/nested integer-vs-float inequivalence, float equivalence, admission, signed zero and NaN. `gate4-unboxed-control-distinguishes` executes boxed/unboxed forms and compares both semantic equivalence and roots. | Substantive oracle exists; B-1 prevents loading it as landed, and B-3 shows missing variadic source-equality coverage. |
| RULING 2: option b | `reader.cljc:648–654` materializes the original token through `numeric-token/materialize :clojure` before collection construction. No Number-value reconstruction, source-wide regex substitution, print round-trip, global reader replacement, or postwalk of lost numeric values is used. Lexical coverage is described in reader source and engineer findings; Gate 2 supplies actual tests. | Correct architectural layer; coverage is insufficient to certify the replacement grammar and occurrence preservation (B-2, B-4, B-5, B-6). |
| Source routing and execution | `yang.clojure/read-forms`, `yang.io`, catalog bindings and two demos use the new reader; the equation plotter is explicitly delimited as legacy raw-forms host interop. Forms compiler admits existing numeric carriers. Catalog installs scoped primitives; global VM primitives remain unchanged. | Float production/routing is real. Scoped equality needs B-3. The plain forms API still requires explicit intended-kind Data and suitable runtime bindings. |
| RULING 3: identity | `frontends.clojure-manifest` declares `c1-3`, `:yang.clojure/constructed-collections-float-kind-v1`, and `:yang.clojure/c1-float-kind`. Grammar identifies `yang.clojure.reader`, with `:source-text` on JVM/Node and `:forms-only` on Dart/Jolt. | Names and capability distinction match the landed contract; no c1-2 relabeling. |
| RULING 3: custody/history | Both archived c1-2 payloads match the recorded SHA-256 values; independent loading, execution and whole-payload decode/re-encode succeeded. Historical c1-1 and cc4 paths are absent from the change set. | Verified, with details below. |
| RULING 4: documentation | Codec cross-reference, `yang.antlr.md` §8.8.1, implementation documentation, prelude producer obligations, API/REPL docstrings, and collection-corpus commentary are present. They distinguish portable Data from source/forms and state the JS bare integral Number contract honestly. | Required locations substantially covered. Claims of completed source preservation cannot override the confirmed counterexamples; reconcile after fixes. |
| RULING 5: boundaries | No production codec, VM equality, base VM primitive table, C1, UAST or bytecode format change. Shared factory and narrow PHP/Python/TS adapters follow the owner's round-brief addition. `yang.io` is an additional actual source-ingestion route. Shared prelude/module publication sources remain unchanged. | No forbidden codec/global-reader shortcut. The 700-line replacement reader has demonstrated compatibility defects; its scope cannot be accepted merely because the path is authorized. |
| RULING 5: pins | Existing corpus reference rows remain; the integral-float row adds one common root/key/C1/instruction-provenance reference and an unmodified-source comparison. Shared runtime closure pins and other frontend revisions are not reminted. | Bounded pin changes are explained; preserve history and rerun relevant gates after fixes. Do not repin away these failures. |
| Independent review / D-1 / D-2 | Persisted deepseek findings say APPROVE at `42b8cfc6` over fix `3b850212`. D-1 adds a clean 157/1264 recapture; the original failing regression log remains. D-2 removes the unused require but also its clause opener. | D-1 supplies the stated rerun, not a new full regression run. D-2 is incorrectly applied and blocks merge. |

**Raw evidence accounting**

I read the logs under `collab/yang-clojure/1795702210000-floatkind-evidence/`, not just the engineer summary:

- `red-jvm-all7gates.log`: 21 tests / 228 assertions, 19 failures / 1 error; separate corpus run 2 tests / 22 assertions, 2 failures / 0 errors. `red-node-all7gates.log`: 22 tests / 214 assertions, 75 failures / 5 errors. Failure entries exercise the expected lost-kind, carrier-admission, semantic and identity surfaces.
- `green-jvm-all7gates.log`: 22/290 and 2/22, both clean (combined 24/312). `green-node-all7gates.log`: 24/310, clean.
- `green-jvm-regressions.log`: 311/3659 with **7 failures**, not globally green. `green-jvm-repl-clojure.log` and D-1's `green-jvm-regressions-recaptured.log`: 157/1264, clean. The claimed other 154/2395 is the residual of the earlier mixed run, not an independently captured clean full run.
- `green-node-regressions.log`: 178/2137, clean, with explicit slow-test/host-applicability skips. `green-demo-build.log`: 324 files, 323 compiled, 0 warnings. A build is not an interactive demo smoke test.
- `green-dart-gates.log`: all 63 tests pass. The forms-only capability and carrier/custody tests are the relevant claim; this is not native Dart source parsing.
- `green-jolt-gates.log`: float-kind 21 tests / 55 assertions, PHP float 45 assertions, vm.float 108 assertions, all with zero failures/errors. Its source-only test vars explicitly perform zero assertions. The combined run is **not wholly green**: vm.data has 527 passes and 3 unpaired-surrogate errors. Those are outside the changed numeric sources and are disclosed by the author/reviewer. The corpus tail is `JOLT-CORPUS-REF: nil`, not a separately counted corpus summary.
- `kondo.log`: 0 errors / 6 warnings, including the now-removed `clojure.string` require. This is visibly pre-D-2 evidence and cannot certify `6a7fb586`.

Thus the historical greens are meaningful evidence of the earlier float implementation, but the statement “seven gates present and green AS LANDED” is false because the final namespace cannot load. The new counterexamples also show why successful existing assertions are not sufficient for acceptance.

**Independent bounded execution evidence**

Probes ran on jupiter from its existing cached classpath, without dependency resolution or rebuilding:

```sh
cd ~/ci/datomworld-floatkind
java -XX:-UsePerfData -cp "$(cat .cpcache/1935970182.cp):test" clojure.main -
```

The source/semantic probes required the actual production namespaces and called `yang.clojure.reader/read-forms`, `yang.clojure.numeric/clj-=`, and `yin.repl/eval-input` over `yin.repl.frontends/create-state {:vm-type :semantic}`. The test-load probe required `yang.clojure-float-kind-test` and reported its namespace-spec failure. No in-memory repair of that namespace was used to manufacture a green result.

For each legacy binary I decoded the disk bytes, passed `[:root :rows]` into `ast-walker/vm-load-rows`, executed with `vm/run`, and compared the original whole payload bytes with `cbor/encode` of the decoded payload:

| File | SHA-256 | Loaded revision / execution / re-encoding |
| --- | --- | --- |
| `float-kind-legacy-jvm-float.cbor` | `71dc93298c26773dc8383f5d12e03d732840052692a272c16c4aaf4ec90783ec` | c1-2; float64 true, exact integer false, value 1.0; original bytes identical |
| `float-kind-legacy-node-integer.cbor` | `ffb9db68eb953ccadd681b916a8df6d262206baa055d6f91eda461d179174ef2` | c1-2; float64 false, exact integer true, value 1; original bytes identical |

Fresh source `((fn [] 1.0))` compiled to `:segment/blake3-7cdae9dfee7f1980ccdd9543f279d0d7d20e4a4a4a44dda14f88f54ba9002049`, matching the historical JVM float root and differing from historical Node integer root `:segment/blake3-541ba2ffc6b74327f70008d2069a35279612c2e96c82cc198d7b0b6782501e4c`. The two selected corpus tests independently returned `{:test 2, :pass 22, :fail 0, :error 0}`.

One subsequent baseline experiment in that same process encountered unsupported upstream `fn*` lowering and exited nonzero after those custody/corpus results. I do not describe that process as an all-green suite. A separate completed probe verified the apostrophe and scalar syntax-quote baseline, plus the explicit-function versus anonymous-function 12/4 counterexample. No Node/Dart/Jolt suite was freshly run in this sign-off; their recorded logs were inspected.

The review skill's generic analyzer could not decode the complete remote diff as UTF-8 (the Node custody fixture is presented as a text diff containing binary bytes). Direct source diffs, protected-path checks and executable probes supplied the review evidence instead. `git diff --check origin/master...HEAD -- src test docs` passed; this does not check Clojure namespace syntax.

**Landing and re-review instruction**

Keep fixes on `fix/float-kind-portability`; preserve the independent review and original evidence as history. Resolve B-1 through B-6 and add B-7's actual boundary case, then obtain review and fresh affected-host gate evidence for the corrected commit. Record the tested SHA, commands, host capabilities, counters and exit codes; compile from the corrected source rather than reusing pre-D-2 test artifacts. Re-pin only when a semantic/provenance change explains a necessary new value; preserve c1-1, c1-2 custody and historical cc4/r3 records.

At the observed master `bab7640f`, merge-base is `7f4898f474d02dc127e5e484254bc44ae7ff88f6`. Master's three later commits (`bce01bad`, `d49ff4b7`, `bab7640f`) do not change the production numeric/frontend files under review. No numeric-runtime prerequisite or competing pin migration from that master was identified. Preserve those master-side additions during integration. Master's earlier green declaration cannot certify this branch.

After the blockers are closed and a new architect sign-off is issued, the corrected branch should fold to master as **one merge**, retaining its implementation, custody and review chain together. Do not merge the current tip or cherry-pick only the nominal implementation while dropping evidence/dispositions. Recheck master and any overlapping frontend/runtime or pin changes at landing time; this report does not approve a future changed target automatically.

Only this findings file was written in the repository. Both existing worktrees and their unrelated changes were left intact.
