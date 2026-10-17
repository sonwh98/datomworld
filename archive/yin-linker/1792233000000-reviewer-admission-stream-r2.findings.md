Completed-GMT: 2026-10-12 05:47:18 GMT
Completed-Local: 2026-10-12 12:47:18 +07 (Asia/Ho_Chi_Minh)

**REJECT**

Two open in-scope P1 defects remain: the retirement predicate misses the first request of a run, and the host answer recheck can starve a valid answer behind irrelevant answers. Everything else below is non-blocking.

References to **R2** mean [the revised design](/Users/sto/workspace/datomworld/collab/1792232000000-architect-admission-stream-r2.claude-fable-5-1.findings.md).

**Round-1 disposition**

| Finding | Status | Mechanism, source evidence, and regression acceptance |
|---|---|---|
| **F1 — Resolution binding** | **FIXED** | R2:42–50 retains the question, prohibits re-resolution, and passes its address to `dht-attempt`. That function already accepts an explicit address ([link.cljc:330](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:330)); resolution supplies the address and asserter provenance ([authority.cljc:244](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker/authority.cljc:244)). This is sound, minimal, and implementable. The head-change test at R2:50, required by AD-1/AD-2, fails if an allow fetches the replacement head. Registry propagation still needs the concrete edit in finding 4. |
| **F2 — Abandonment releases the queue** | **NOT FIXED** | R2:54–58 adds the right retirement boundary, but its counter predicate is wrong for the source’s minting convention. The F2 test at R2:60/105 would fail for the first root request. See finding 1. |
| **F3 — Automatic resume** | **PARTIAL** | R2:66 correctly adds question publication and admission-interpreter progress to the existing continuation test ([repl.cljc:1340](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1340)). R2:67 adds the missing host hook beside the existing load/head hook ([repl.cljc:1943](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1943)). The single-answer acceptance at R2:72/105 covers the basic resume path, but the bounded, non-consuming peek can starve it. See finding 2. |
| **F4 — ShiBi contract** | **PARTIAL** | R2:76–80 fixes the three-argument signature, composition-owned context, absent/ended behavior, and “addition beside the gate” claim. These match [dao.shibi.md:40](/Users/sto/workspace/datomworld/docs/design/dao.shibi.md:40), [its synchronous limits](/Users/sto/workspace/datomworld/docs/design/dao.shibi.md:53), and [its non-goal](/Users/sto/workspace/datomworld/docs/design/dao.shibi.md:65). Pending on an absent snapshot grants nothing and is fail-closed for a waiting link. However, the composition’s verification-cost bound is omitted, and AD-0 checks documentation rather than executable behavior. See finding 5. |
| **F5 — Portable driver wiring** | **FIXED** | R2:93 explicitly changes `run-over-dht` to create, drive, and retain both interpreter states, replacing the current link-only loop ([dht_import_test.cljc:281](/Users/sto/workspace/datomworld/test/yang/python/antlr/dht_import_test.cljc:281)). R2:103–104 requires portable imports on every VM and both interpreters in the driver. The mechanism is minimal and implementable; those acceptances fail if admission is never driven. |

**Numbered findings**

1. **P1 — Retirement excludes the first root request. Owner: linker engineer and VM engineer.**

   R2:54 retires root ids when `k > :after`; R2:56 sets `:after` to the base VM’s `:id-counter`. But [mint-link-id](/Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc:492) emits the **current** counter and then increments it. With base counter zero, the first request is `[:t0 0]`, which the proposed predicate does not retire.

   Consequently, abandon leaves the unanswered question and link cursor in place. The next unrelated require remains blocked at that request, exactly the original F2 failure. The current server stops at the first pending request ([link.cljc:451](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:451)).

   **Exact fix:** Define the root interval as `base-counter <= k < parked-counter`, with missing counters normalized to zero. Include the upper bound in the run descriptor. Match descendant origins by exact origin or `origin + "."` prefix, rather than raw prefix matching. Keep the existing stop-at-first-unrelated-request rule.

   **Acceptance:** Start with an absent/zero counter; first require opens unanswered admission; abandon; unrelated trusted require completes on the same pair. Repeat with a nonzero base counter and nested install origins. Assert that an unrelated origin such as `:t0.10` does not match descendant root `:t0.1`.

2. **P1 — The answer recheck can permanently miss a valid retained answer. Owner: VM engineer.**

   R2:67 scans at most `serve-budget` answers **without retaining the successor cursor**, and invokes the consuming drive only if that window contains a pending id. Put 64 unknown or retired answers before a valid answer: every tick scans the same 64 irrelevant values and returns unchanged state. The valid answer never reaches the drain described at R2:46.

   This is realistic within the proposed duplicate/late-answer protocol: the existing budget is 64, while the link ring capacity is 1024 ([link.cljc:43](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:43), [link.cljc:74](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:74)). Stream reads advance only through returned successor cursors ([ringbuffer.cljc:99](/Users/sto/workspace/datomworld/src/cljc/dao/stream/ringbuffer.cljc:99)).

   **Exact fix:** Make the hook perform a bounded consuming drain, persist its cursor and matching answers, then recheck when the retained answer map contains a pending id. Irrelevant answers must advance the cursor without incrementing `:checks`. Specify gap recovery using the stream’s recovery cursor.

   **Acceptance:** After the initial drive stops, append `serve-budget` irrelevant answers and then the valid allow. Host ticks alone must complete the require. Add a gap-recovery case and assert no recheck-count increment for irrelevant traffic.

3. **P2, non-blocking — Admission successor state is omitted from error rollback. Owner: VM engineer.**

   R2 threads `:admission` through ordinary driving but does not update [link-raise](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1232) and [carry-served](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1249). Both error paths restore an earlier shell state while preserving the advanced link pair. Admission cleanup, cursors, answers, and serial allocation can therefore roll back independently, leaving stale retained entries or replaying answered questions.

   **Exact fix:** Carry the full admission successor through the exception data and restore it alongside the link pair. Add acceptance for allow → install error → subsequent require, asserting monotone serial allocation, no stale `:asked` entry, and no replay-generated progress.

4. **P2, non-blocking — Registry admission metadata needs an explicit installation edit. Owner: VM engineer.**

   R2:27/48 promises an admission record in the registry, and AD-1 requires it, but the edit list does not identify its propagation point. [engine/link-install](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1390) reconstructs entries through [module/link-module](/Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc:88), whose fixed map drops admission keys from the response.

   **Exact fix:** Specify copying the validated admission record from the successful response into the registry entry before `receive-module`. Retain it through child inheritance and dependency transfer. Extend AD-1 to inspect the installed entry and inherited entry.

   Also clarify R2:50: the response’s `:manifest` is a manifest value; compare its content address to M1, or compare it to M1’s manifest value.

5. **P2, non-blocking — Verification limits and snapshot behavior lack executable acceptance. Owner: admission interpreter engineer.**

   R2:78 prohibits opening, fetching, and waiting, but omits ShiBi’s composition-bounded verification cost. AD-0 requires prose about absent/ended snapshots; no acceptance exercises those branches or the three supplied arguments.

   **Exact fix:** State the composition-owned cost bound. Add portable assertions that `verify` receives the exact snapshot, context, and question; absent snapshot leaves admission unanswered; ended snapshot refuses without loading; and authorization context never enters linker question data.

The remaining adversarial checks support the architecture. Retaining the original address across head changes is deliberate authorization binding. `[link-id serial]` separates questions on a surviving pair, provided rollback preserves state; reset creates fresh media. Drive continuation remains bounded by the existing round budget. Neutral admission vocabulary stays outside capability semantics, and malformed/non-empty-condition refusals remain fail-closed.

Session-wide cache exposure is consistent with [module’s cache return](/Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc:552), [child inheritance](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1289), and [dependency transfer](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1396). `(lang ...)` preserves that registry ([repl.cljc:1799](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1799)). Trust does not weaken [profile/dependency discharge](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker.cljc:1020) or [export-origin validation](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1347).

Read-only review completed. No files edited or suites run.
