Completed-GMT: 2026-09-10 07:32:32 GMT
Completed-Local: 2026-09-10 14:32:32 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: pending (not captured; single-shot exec, no resume needed)
# Task: adversarial review of the revised yin.vm-consumers.implementation-plan.md
Role: Adversarial Review

I’ll read the revised plan and repository rules, then independently check the census, replacement consumers, and completion gates. I’ll report concrete defects with file and line anchors without changing files.

The plan’s own sweep found a concrete omission: `runtime_regression_test.cljc`, listed as unchanged, directly requires three VMs slated for deletion. I’m checking for direct constructor calls too, and comparing the demos with their v2 twins.

I’m applying the code-review skill’s correctness checklist to the plan; diff analysis isn’t applicable because implementation hasn’t started.

The Flutter widget explicitly requests `:semantic`, so changing only the REPL’s default would make widget startup throw. I also found a real gap in the pipeline twin: v1 offers Python and PHP input, while v2 only accepts Clojure. The plan needs to distinguish intentional VM feature removals from features it promises to preserve.

**Request changes.** The plan has two definite breakages and several gaps in its deletion checks.

1. **[P1] The “unchanged” runtime regression suite will stop loading.**  
   [Plan:114](/Users/sto/workspace/datomworld/docs/design/yin.vm-consumers.implementation-plan.md:114) says changing `vtu/vm-factories` is sufficient. But [runtime_regression_test.cljc:8](/Users/sto/workspace/datomworld/test/yin/vm/runtime_regression_test.cljc:8) directly requires `register`, `semantic`, and `stack`. The plan’s own Phase 0 command finds these requires. They are unused beyond the namespace declaration, so add this file to the migration list and remove them. Otherwise the required test builds fail after deletion.

2. **[P1] The proposed REPL migration breaks the Flutter consumer it intends to preserve.**  
   [D1:147](/Users/sto/workspace/datomworld/docs/design/yin.vm-consumers.implementation-plan.md:147) limits migration to the REPL and its tests. However, [flutter.cljd:60](/Users/sto/workspace/datomworld/src/cljd/yin/repl/flutter.cljd:60) explicitly calls `(repl/create-state {:vm-type :semantic})`. Changing the default does not override that argument: [make-vm:164](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:164) will throw “Unknown Yin REPL VM type.” Both Flutter demos call this startup path. Migrate the widget’s explicit selection and require a startup check; compilation alone cannot catch this failure.

3. **[P2] The deletion census misses executable entry points into deleted consumers.**  
   [Plan:88](/Users/sto/workspace/datomworld/docs/design/yin.vm-consumers.implementation-plan.md:88) and its completion checklist specify **four** `bytecode-bench*` aliases. [deps.edn:17](/Users/sto/workspace/datomworld/deps.edn:17) actually has **five** aliases targeting `yin.vm.bytecode-bench`: `:bench`, `:profile`, `:profile-fast`, `:profile-cesk-space`, and `:profile-ast-walker`. Enumerate all five explicitly.  
   Separately, [bin/register_bench_cljd.dart:1](/Users/sto/workspace/datomworld/bin/register_bench_cljd.dart:1) imports the generated output of the deleted Dart benchmark but has no disposition. It will fail after clean regeneration, while stale output can conceal the omission. Delete that launcher too, or explicitly migrate it. Expand the sweep to the names and paths of **all deleted consumers**, including Dart launchers; the current VM-only expression catches neither problem.

4. **[P2] D3’s feature-completeness assumption is already false for the pipeline.**  
   [Plan:180](/Users/sto/workspace/datomworld/docs/design/yin.vm-consumers.implementation-plan.md:180) leaves all differences for implementation-time discovery. The v1 pipeline offers Clojure, Python, and PHP at [compilation_pipeline.cljs:1542](/Users/sto/workspace/datomworld/src/cljs/datomworld/demo/compilation_pipeline.cljs:1542), and [its compiler:858](/Users/sto/workspace/datomworld/src/cljs/datomworld/demo/compilation_pipeline.cljs:858) feeds those frontends into the walker. The [v2 compiler:124](/Users/sto/workspace/datomworld/src/cljs/datomworld/demo/compilation_pipeline.cljs:124) accepts only Clojure. This loss is independent of deleting bytecode evaluators.  
   Phase 0’s “close any gap” instruction is a meaningful gate, but the plan should now name this known migration and its acceptance checks. It must also distinguish preserved features from intentional removals such as backend comparison; otherwise “any gap” conflicts with the deletion’s purpose.

5. **[P2] Removing the old pipeline route breaks an existing website link.**  
   [Plan:95](/Users/sto/workspace/datomworld/docs/design/yin.vm-consumers.implementation-plan.md:95) removes `#pipeline`, but [public/chp/yin.chp:18](/Users/sto/workspace/datomworld/public/chp/yin.chp:18) still advertises `/demo.html#pipeline` as “Try the Live Demo.” After the prescribed edit, that link reaches the picker’s fallback instead of the pipeline. Update the link or retain a route alias to `:pipeline-v2`, and include it in verification.

6. **[P2] The successor’s stream-rename prerequisite is incomplete.**  
   [Boundary:322](/Users/sto/workspace/datomworld/docs/design/yin.vm-consumers.implementation-plan.md:322) bundles the `dao.stream` rename into a successor written when the three preceding VM-related obligations are clear. But [dao.stream.md:792](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:792) gates that rename on **every** legacy stream consumer migrating and legacy `dao.stream` being deleted. Its remaining-consumer list additionally names `yin.io`, GUI/terminal consumers, `agent.tools`, and other surfaces. Those obligations are not discharged by the three rows. Separate the VM/R4 gate from the global stream-deletion-and-rename gate.

The core dependency argument is sound. An atomic commit exposes no partially edited state to `git bisect`; intermediate working-tree breakage is not itself a bisectability defect. D1’s claimed Flutter/telemetry requires are real, and the named D2/D3 twins exist; all three browser twins are wired into the picker.

I ran the prescribed sweep plus broader namespace, dynamic-loading, macro-require, and generated-output checks. These did not establish another live source consumer beyond the findings above. Builds were not run because this was a read-only review. No files were edited.
