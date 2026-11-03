I’ll inspect the runner, architecture guidance, and available host evidence, then check shadow-cljs’s bundle and cache behavior. I’ll keep the investigation read-only and distinguish measurements from hypotheses.

===

I found a likely selection bug: the driver passes `"--test"` and the namespace list as separate arguments, while shadow-cljs 3.3.5’s runner accepts only `--test=<list>`. That would make each worker run the full suite. I’m checking the host artifacts to see whether the failed run used this form.

===

The failed run’s logs confirm it: all six workers printed `Unknown arg: --test`, then ran the full suite and reached the same failing Python linked-prelude tests. The recorded stall therefore did not occur under effective sharding. I’ll measure the cost of loading the bundle separately and inspect the failure path to assess the memory theory.

===

Loading all 272 runnable test namespaces used about 1.02 GiB RSS and 641 MiB of live V8 heap after GC. The measured heap limit is 4.19 GiB, so loading alone does not account for the stalled workers’ 3.2 GB RSS.

I also checked the 30,297-second run: its log has no `Ran … tests` summary and ends during a failed linked-prelude assertion dump before shadow reports “Build completed.” That does not establish that the suite finished.

===

A capped run of the single `install-child-defines-only-test`, with failure-value dumps suppressed, still exceeded 75 seconds. At interruption it was executing `dao.space.query/unify → dao.jing.cbor/content= → ClojureScript equality`, so the test has substantial computation beyond printing. I’m collecting a short in-memory CPU profile and GC trace to distinguish computation from GC overhead.

===

The profile is dominated by query execution and unification; GC accounts for 1,246 of 55,403 samples (2.25%). Late GC traces show mutator utilization around 0.989, with heap usage far below the limit. This reproduction is CPU dominated.

Using the correct selector, the existing full bundle also ran only `dao.postgraphics.math-test`: 42 tests, 110 assertions, all green, in 14.61 seconds. The proposed command has a separate problem: shadow accepts `:output-to` through `--config-merge`, but `:cache-root` is global and is not changed there.

===

**Verdict: the failed run was six concurrent full-suite runs, not six effective shards. The evidence does not establish heap-limit GC thrash. A focused reproduction instead showed expensive query computation with low GC overhead.**

I made no file changes on either host.

1. **The confirmed failure is argument parsing.**

The failed pin was `c4a8c1bed1b8a9726e9840b8945a8210574f59f0`. Its driver passed two arguments:

```clojure
"--test" (str/join "," (sel s))
```

Shadow-cljs 3.3.5 accepts only one argument, `--test=<list>`. Its parser prints warnings for unknown arguments, leaves the selection empty, and consequently calls `run-all-tests`.

This is confirmed by all six preserved logs under:

```text
shiva: ~/ci/datomworld-python-s3/target-ci/node-shards-20261031-1609/
```

For example, `dao.gui.log` begins:

```text
Unknown arg: --test
Unknown arg: dao.gui.compiler-cljs-test,dao.gui.event.arena-test,...
```

Even `dao.gui`, `dao.jing`, and `dao.postgraphics` subsequently reached:

```text
Testing yang.python.antlr.linked-prelude-test
FAIL in (manifest-address-test)
FAIL in (program-root-is-prelude-independent-test)
FAIL in (install-child-defines-only-test)
```

The six logs have exactly the reported total size, **753,664 bytes**: four at 122,880 bytes and two at 131,072 bytes. Thus the similar stopping points and memory footprints have a concrete explanation: every worker executed substantially the same workload.

I also replayed selection with execution replaced by stdout-only stubs:

```text
--test dao.gui.compiler-cljs-test   → WOULD_RUN_ALL
--test=dao.gui.compiler-cljs-test   → WOULD_RUN_SELECTED_VARS 1
```

The local driver acquired the corrected single-argument form during this investigation; the failed-run evidence above predates that correction.

2. **Loading the full suite is expensive, but it does not itself require 3.2 GB or approach the heap ceiling.**

Measurements from shiva’s existing artifact, using Node **v25.6.1**:

| Measurement | Observed |
|---|---:|
| `target/node-tests.js` | 75,798 bytes |
| Runtime JS files imported by that launcher | 617 |
| Imported JS bytes | 184,567,859 bytes, approximately 176 MiB |
| Files named `*_test.js` | 274 |
| Bytes in those test files | 148,508,012, approximately **80.5%** |
| Runnable test namespaces / vars | 272 / 4,535 |
| V8 heap limit | 4,496,293,888 bytes, approximately **4.19 GiB** |
| Heap used after loading and forced GC | approximately **641 MiB** |
| RSS after loading and forced GC, two runs | approximately **941–1,017 MiB** |

The remaining JS bytes include libraries, fixtures, and generated runner code; the percentage is a filename-based byte count, not a semantic classification.

This development artifact is a small launcher importing runtime files, rather than one self-contained JS bundle. The whole runtime directory occupies approximately 230 MiB.

`node --v8-options` reports `--max-old-space-size=0`, meaning automatic sizing; the measured `heap_size_limit` above is the useful limit here. RSS includes memory outside the JavaScript heap, so **3.2 GB RSS cannot be compared directly with the V8 heap ceiling**. [Node memory documentation](https://github.com/nodejs/learn/blob/main/pages/diagnostics/memory/understanding-and-tuning-memory.md)

As a control, the unchanged full artifact successfully ran:

```text
node target/node-tests.js --test=dao.postgraphics.math-test

Ran 42 tests containing 110 assertions.
0 failures, 0 errors.
```

Elapsed time was **14.61 seconds**, maximum RSS **1,079,920 KiB**, CPU **122%**, swaps **0**, filesystem outputs **0**. CPU above one core therefore does not, by itself, identify GC thrashing.

The six failed processes were already gone when I connected, so I could not remeasure their `/proc` counters or independently repeat your historical CPU-versus-log-growth sample.

3. **The focused slow-test reproduction was CPU dominated.**

I ran only:

```text
yang.python.antlr.linked-prelude-test/install-child-defines-only-test
```

with `DATOM_SLOW_TESTS=1`, replacing failure-value reporting with bounded metadata output. The inspected fixture publishes into an in-memory content store.

One capped run exceeded 75 seconds. At interruption its stack included:

```text
dao.space.query/unify
dao.jing.cbor/content=
cljs.core equality
```

A second run collected an in-memory CPU profile over 60 seconds:

```text
Total samples:             55,403
Garbage-collector samples:  1,246  (2.25%)
Heap used at end:         842,763,328 bytes
VmHWM:                     1,412,872 kB
VmSwap:                            0 kB
```

Top sampled functions included query execution `step`, `unify-slots`, `run-comp`, and execution-budget `charge`. Late GC traces showed:

```text
841.6 (908.5) -> 777.6 (908.5) MB
average mu = 0.989, current mu = 0.979
```

That is inconsistent with heap-ceiling GC thrash **during this reproduction**. It demonstrates substantial computation even before failure dumps are printed. It does not prove an infinite loop, nor reproduce the already-killed workers’ final hours.

The giant dumps and buffering are additional problems. The last logged failed assertion is `(is (vm/halted? child))`; the default reporter formats its actual value using `pr-str`, potentially traversing a huge VM state. Meanwhile, the driver uses buffered `io/writer`s. Both observed file sizes are multiples of 8,192 bytes. A mid-line tail therefore cannot reliably locate the currently executing expression.

4. **Per-shard builds are a possible optimization, but the proposed command is incorrect and does not address the confirmed failure.**

I checked the packaged **shadow-cljs 3.3.5 source**:

- **`:output-to` through `--config-merge`: yes.**
- **`:ns-regexp` through `--config-merge`: yes.**
- **`:cache-root` through that build-level merge: no.** The runtime’s global cache root and build cache directory are established before the CLI build-config merge. The official documentation explicitly makes `:cache-root` global. [Shadow configuration documentation](https://github.com/shadow-cljs/shadow-cljs.github.io/blob/master/docs/build-config.adoc)

Consequently, your proposed commands still use the same `test/dev` cache and default runtime output directory. Separate `node-tests-<s>.js` launchers would reference shared runtime files, including runner code that subsequent compiles can overwrite.

If smaller artifacts are subsequently justified, use **distinct build IDs**, for example:

```clojure
{:builds
 {:test-dao-jing
  {:target :node-test
   :namespaces [dao.jing-test dao.jing.cbor-test]
   :output-to "target/node-tests-dao-jing.js"
   :autorun false}}}
```

Distinct build IDs receive separate build caches and default runtime directories. The pinned source supports explicit `:namespaces`; this also avoids regex escaping and anchoring mistakes.

Two qualifications matter:

- Selection includes the chosen namespaces’ dependency closure. Tests that require other test namespaces can bring those tests into the artifact. The runner’s inventory macro collects test vars from analyzed namespaces.
- **`--test=<list>` still works** and remains useful to restrict execution to the intended shard.

I could not reproduce the alleged cache leakage without compiling, which would violate the read-only constraint. Source inspection shows that `:ns-regexp` regenerates test entries during resolution; previously compiled files remaining on disk alone do not prove that those namespaces are still imported.

For cost, preserved summaries repeatedly show **22–23 seconds for warm full compiles**. I did not measure a cold isolated build or fourteen per-shard builds. Fourteen comparable warm invocations would total roughly five minutes, but that is arithmetic, not a measured per-shard result.

My recommended order is:

1. Verify the corrected selector, including actual executed namespace names and completion summaries.
2. Keep one compile initially and measure genuine shard peaks and runtimes.
3. Investigate the Python fixture/publication/query path and bound failure formatting.
4. Introduce distinct per-shard builds if their measured startup or memory savings justify compilation costs.

Increasing `--max-old-space-size` has no demonstrated benefit from these measurements. Fewer workers or distributing work across both hosts can control aggregate load, but cannot repair ignored selection or accelerate one pathological test. Shadow’s Node output implementation also explicitly requires one module, making `:modules` an unsuitable direct fix here.

5. **The 30,297-second result is not reliable evidence of a completed suite.**

The preserved files are:

```text
~/ci/nightly-results/20261031-0223-summary.txt
~/ci/nightly-results/20261031-0223-cljs-full.log
```

The summary records `cljs-full rc=0 secs=30297`. However, the test log:

- contains numerous failures and errors;
- reaches the same linked-prelude tests;
- has **no `Ran … tests` summary**;
- ends mid-dump before shadow prints `Build completed … 30267.97s`.

Shadow’s autorun waits for Node and records its exit status internally; the compile command’s success is not reliable test-success evidence. The runner also calls `process.exit()` immediately at end-of-tests. Node documents that pending asynchronous stdout writes can be lost on exit, so missing output alone cannot prove where execution stopped. [Node process documentation](https://nodejs.org/download/release/v25.6.0/docs/api/process.html#process_exit_code)

Thus the 8.4-hour and 10-hour observations are plausibly related to the same expensive slow-test path, compounded by poor output visibility. I cannot establish that their terminal behavior was identical. **The confirmed causes are ineffective sharding and costly slow-test computation; late-stage GC thrash in the killed processes remains unverified.**