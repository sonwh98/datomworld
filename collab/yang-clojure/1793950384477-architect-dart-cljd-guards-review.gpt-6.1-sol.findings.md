Created-GMT: 2026-11-06 07:33:06 GMT
Created-Local: 2026-11-06 14:33:06 +07 (Asia/Ho_Chi_Minh)
Completed-GMT: 2026-11-06 07:40:29 GMT
Completed-Local: 2026-11-06 14:40:29 +07 (Asia/Ho_Chi_Minh)
# Task: Architecture Review of ClojureDart Reader Conditional Guards and Orchestrator Commit 873c8bcd
Role: Lead System Architect
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-06 14:33:06 +07 | Status: completed | Rationale: Architectural review of ClojureDart cross-compilation reader semantics, portability boundaries, and landed commit 873c8bcd per team.md.

**Disposition: recommend commit `873c8bcd` stand as approved of record architecturally.** No blocking architectural defect or design contradiction found. No required forward-fix or revert.

| Severity | File:line | Invariant/evidence | Recommended correction |
|---|---|---|---|
| Pass | `src/cljc/yang/clojure.cljc:31` | Host isolation: both ClojureDart passes select `nil`, preventing unavailable reader dependencies. Its namespace compiler explicitly skips nil require specs. JVM and CLJS retain their original require vectors. | None. |
| Pass | `src/cljc/yang/clojure.cljc:2905` | Explicit capability boundary: source reading fails with structured `ex-info`; form-based `compile` and `compile-program` remain available. The REPL independently reads forms and installs Clojure with `:input :forms`. | None. |
| Pass | `src/cljc/yang/php/antlr/prelude_p9.cljc:5` | Macro dependency ownership: `defquoted` emits `edn/read-string` into the calling namespace. The added alias supplies that dependency and resolves to ClojureDart’s EDN implementation. | None. |
| Pass | `src/cljc/yin/repl/frontends.cljc:14` | Composition isolates JVM PHP parser dependencies from ClojureDart while preserving JVM registration and existing CLJS/Jolt branch selection. | None. |
| P3 — documentation | `src/cljc/yang/clojure.cljc:2895` | The docstring explains Jolt’s source-reader limitation but omits the newly explicit ClojureDart limitation. “JVM/Node” also understates the retained CLJS/browser reader branch. | Optionally document JVM/CLJS source reading and Dart/Jolt form-based compilation. |

The important reader distinction is **compilation phase**. The pinned ClojureDart compiler reads emitted Dart code with `#{:cljd}` and macro-host code with `#{:cljd :cljd/clj-host :clj}`. First-match ordering therefore matters during macro hosting. The remaining `:clj` PHP registration inside `standard` is absent from Dart emission; its presence during macro hosting does not introduce unresolved Dart references.

Under the reader-conditional rules, unmatched conditionals contribute no form. Consequently, `:cljd nil` introduces **no adverse JVM or CLJS nil-libspec behavior**: those platforms select their existing vectors. Node and browser builds share the unchanged `:cljs` selection.

The foundational invariants passed review: this patch adds no hidden state, shared mutation, callbacks, implicit execution, assumed graphs, or AST loss. Parsing remains upstream of lowering and evaluation. Unsupported source reading produces an explicit refusal without changing form-compilation semantics.

Verification completed read-only:
- Nine normalized parent-versus-commit reader-form comparisons passed: three files across JVM, CLJS, and a Jolt feature model.
- The actual pinned ClojureDart reader verified both compilation phases.
- JVM source/form compilation smoke checks, both `defquoted` expansions, and the selected Dart refusal body evaluated on JVM passed.

Native Dart, Node/browser, and Jolt runtime suites were not executed in this review; the orchestrator’s reported remote passes were not independently credited. No files were edited.

This architectural approval does not retroactively waive the orchestrator log’s recorded departure from pre-commit review procedure.
