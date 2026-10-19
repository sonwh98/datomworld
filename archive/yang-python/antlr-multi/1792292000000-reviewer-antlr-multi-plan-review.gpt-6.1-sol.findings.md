I’ll check the plan against the architecture rules, landed TS/PHP designs, parser code, and runtime APIs, then write severity-tagged findings with file-and-line evidence.

The existing packet validator checks tree links, but it does not enforce all the properties the plan attributes to it. I’m checking which gaps the proposed `conform` can close, plus the JS/Dart runtime APIs and how languages with missing targets would load.

I found three concrete problems: the parity test omits spans and synthetic flags; generation is not ordered before the REPL builds that will import its output; and the add-a-language recipe leaves unconditional imports pointing at omitted targets. The JS code-point stream is real: ANTLR 4.13.2’s `CharStreams.fromString` enables it.

Completed-GMT / Completed-Local
2026-10-17 12:52:02 GMT / 2026-10-17 19:52:02 +0700

The existing CST v1 is a sound choice for the shared IR. Sign-off is blocked by gaps in its conformance contract, the build dependency graph, and the promised handling of languages with missing targets.

1. **[P1] The conformance oracle does not establish the seven invariants.**  
   [Plan:82–103](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md:82) attributes preorder and tree enforcement to `validate!`. I reproduced two violations that it accepts: duplicate child edges `[1 1 2]`, and an index-ordered tree whose actual DFS preorder is `[0 1 3 2]`. The cause is visible in [packet.cljc:25–41](/Users/sto/workspace/datomworld-c4hang/src/cljc/yang/antlr/packet.cljc:25).

   The proposed source-only `conform` also cannot establish that rule/token names came from the grammar tables. Its listed checks omit real-token text equality and UTF-8 boundary alignment; byte bounds alone accept offsets inside a multibyte character. Meanwhile, `validate!` rejects legitimate error packets because they have no nodes.

   **Required:** define outcome-specific validation; enforce actual DFS preorder and unique edges; check real-token text against source bytes, UTF-8 boundaries, terminal ordering, and exact rule extents over real descendants. Supply grammar metadata for name checks. Specify terminal monotonicity separately from nested rule spans.

2. **[P1] The existing “precompiled packets” cannot satisfy M1 or prove M2 packet equality.**  
   [Plan:245–248,350](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md:245) says widening the existing test establishes packet parity and requires `conform` to pass every precompiled packet.

   However, [python_antlr_test.cljc:340–355](/Users/sto/workspace/datomworld-c4hang/test/yin/repl/python_antlr_test.cljc:340) compares only selected node fields, excluding spans and synthetic flags. Its packet builder deliberately assigns every span `[0 0]`, omits synthetic flags, and uses grammar `"hand"` ([lower_portable_test.cljc:11–37](/Users/sto/workspace/datomworld-c4hang/test/yang/python/antlr/lower_portable_test.cljc:11)). These are lowering fixtures, not parser-output goldens.

   **Required:** preserve those fixtures for lowering tests and add genuine JVM-exported packets with full metadata, edges, spans, and synthetic flags. Specify normalization or equality rules for error diagnostics too; checking only “error packet, no nodes” does not establish cross-target diagnostic parity.

3. **[P1] Omitting a manifest target produces missing imports, not `unavailable-parser`.**  
   [Plan:283–301](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md:283) promises that omitting a target is sufficient. But the prescribed parser namespace imports generated recognizers on all three hosts, and the catalog requires that namespace before selection occurs. The current composition is statically wired in [frontends.cljc:11–18,72–80](/Users/sto/workspace/datomworld-c4hang/src/cljc/yin/repl/frontends.cljc:11).

   **Required:** design the compile-time availability mechanism: conditional imports/profiles and installation, generated registration, or equivalent explicit composition. Demonstrate a Java-only language coexisting with Python in Node and Dart builds. An informational `:targets` set cannot resolve an import that fails before runtime.

4. **[P1] Generation is not ordered before the builds that consume it.**  
   [Plan:201–204](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md:201) adds generation dependencies to test tasks. The actual recognizer consumers run in prerequisite tasks: `build:yin-repl-node` and `build:yin-repl-peer` ([bb.edn:6–38](/Users/sto/workspace/datomworld-c4hang/bb.edn:6)). Neither build depends on generation. Making generation another sibling prerequisite does not express the required dependency.

   **Required:** attach generation to the consuming build tasks, update slow/all lanes and remaining task-name references, and cover both `:cljs` and the separate `:cljs-yin-repl` classpaths ([deps.edn:39,67–72](/Users/sto/workspace/datomworld-c4hang/deps.edn:39)). Test clean direct builds, not only test wrappers.

5. **[P1] The worker-isolation fallback contradicts the governing design.**  
   [Plan:129–133](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md:129) permits shared mutable caches when a host is single-threaded and caches are “read-mostly.” [yang.antlr.md:581–588](/Users/sto/workspace/datomworld-c4hang/docs/design/yang.antlr.md:581) instead requires isolation by worker, process, classloader, or instance-owned caches.

   Single-threaded execution prevents simultaneous access; it does not establish exclusive ownership or independence from previous requests.

   **Required:** remove that fallback. Record the exact cache replacement or isolation mechanism per runtime and include interleaved-worker verification.

6. **[P1] M3’s prerequisites omit a live consumer of the file it deletes.**  
   [Plan:337–343](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md:337) allows deletion after test-scenario disposition and REPL parity. The compilation demo still requires `yang.python` and calls `py/compile` ([compilation_pipeline.cljs:50,158](/Users/sto/workspace/datomworld-c4hang/src/cljs/datomworld/demo/compilation_pipeline.cljs:50)). Those prerequisites can all pass while deletion breaks the browser build.

   **Required:** add a consumer inventory and demo migration before retirement opens. PHP’s landed design already makes demo retargeting an explicit retirement prerequisite ([yang.php.md:1291–1306](/Users/sto/workspace/datomworld-c4hang/docs/design/yang.php.md:1291)).

7. **[P2] The browser bundle already reaches the catalog.**  
   [Plan:373–376](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md:373) assumes ANTLR enters Node/test builds only unless the browser requires it. That require path already exists: browser demo → `yin.repl.driver` → `yin.repl.frontends` ([yin_repl.cljs:38](/Users/sto/workspace/datomworld-c4hang/src/cljs/datomworld/demo/yin_repl.cljs:38), [driver.cljc:22](/Users/sto/workspace/datomworld-c4hang/src/cljc/yin/repl/driver.cljc:22)).

   **Required:** explicitly choose browser inclusion or a separate composition, then add browser compile acceptance and measure its bundle impact. Removing the catalog gate makes this an immediate M2 consequence.

8. **[P2] The Dart interoperability budget needs an executable acceptance gate.**  
   The small per-language profile remains plausible; dynamic warnings do not inherently require duplicating the exporter per language. But [Plan:213–229](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md:213) places `:cljd` after `:clj` in its examples despite its own ordering rule, and leaves the generated-parser call untyped.

   Also, the supposedly portable lexical scan contains Java string calls such as `.charAt` ([repl_probe.clj:59–68](/Users/sto/workspace/datomworld-c4hang/src/clj/yang/python/antlr/repl_probe.clj:59)). Purity does not make those calls portable.

   **Required:** make M0 compile/import/construct a recognizer through actual CLJS and CLJD, with an explicit policy for dynamic interop warnings. Include portable string access in the probe port. Give M1 its own fixture profile rather than leaving its dependency on the M2 parser migration implicit.

9. **[P2] Reconciliation and dependency provenance need corrections.**  
   The broad reconciliation is valid: section 17’s lifts concern lowering, publication, and REPL infrastructure; new host exporters complement them. But [Plan:274–277,319–330](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md:274) overstates the simplification:

   - TS specifies Java/C#/C++ helpers, plus dialect and prediction-mode requirements—not literally Java-only upstream helpers ([yang.typescript.md:504–518](/Users/sto/workspace/datomworld-c4hang/docs/design/yang.typescript.md:504)).
   - PHP explicitly vendors its MIT Java helper. Preserve that exception to “vendor `.g4` only” ([yang.php.md:1319–1331](/Users/sto/workspace/datomworld-c4hang/docs/design/yang.php.md:1319)).
   - The Dart runtime includes MIT notices alongside BSD text; “runtimes are BSD-3-Clause” is incomplete. Record the complete [published license](https://pub.dev/packages/antlr4/license).
   - Require lockfile updates and enforce manifest/runtime-pin consistency; descriptive runtime strings do not enforce it.

   The mechanical recipe must also state its eligibility condition: target-portable grammar actions and tested helpers. The governing design explicitly says grammars-v4 does not guarantee cross-target compatibility ([yang.antlr.md:535–540](/Users/sto/workspace/datomworld-c4hang/docs/design/yang.antlr.md:535)).

Two challenged risks are **not blockers by themselves**: JS code-point input is concretely supported by [ANTLR 4.13.2 `CharStreams.fromString`](https://raw.githubusercontent.com/antlr/antlr4/4.13.2/runtime/JavaScript/src/antlr4/CharStreams.js), and Dart documents compatibility resolution for null-safe packages with a `<3.0.0` SDK ceiling ([migration guide](https://dart.dev/resources/dart-3-migration#dart-3-backwards-compatibility)). Retain the Unicode fixture and actual project-resolution checks.

Review was read-only. The validator counterexamples were executed; proposed JS/Dart builds were not.

Verdict: REQUEST CHANGES