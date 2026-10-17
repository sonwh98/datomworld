I’ll verify the committed SHA, read the full design and governing rulings, then check its claims against the landed code. I’ll keep the review read-only and report findings with evidence.


The committed HEAD is `5f8963093631ab447840cd6a041d83fadccf3999`. The tree has untracked collaboration artifacts; I’ll use committed files as code evidence and the specified findings file as the review target. I’m applying the `code-review-pr` skill’s inspection and severity framework to this design review.


D1, D2 and the stale seam documentation are confirmed. I’m now checking whether the proposed contracts fit the implementation: erasure identity, declaration-file claim coordinates, module-cycle initialization, and PHP reference copying need particular scrutiny.


Two contract problems are confirmed: the proposed JS string representation can produce unpaired surrogates that Jing refuses, and `.d.ts` claims lack a defined mapping from declaration names to canonical code occurrences. I also found PHP semantic rules that disagree with the proposed reference runtime. I’m checking the slice dependencies so the findings identify the required changes.


The review supports requesting changes. L1-first is sound, but the slice plan misses dependencies: linked execution on the AST walker still needs L-b, and statement-level ticks need safepoint support that has not landed. I’m finishing the evidence checks and separating those blockers from smaller audit inaccuracies.


Completed-GMT: 2026-10-17 07:25:09 GMT
Completed-Local: 2026-10-17 14:25:09 +0700

Reviewed all 451 lines against committed `master` at `5f8963093631ab447840cd6a041d83fadccf3999`. The relevant implementation is unchanged from the design’s `7c143c01` baseline. No files were edited. This was an inspection review; tests were not rerun.

References below to “design” mean the [reviewed findings file](/Users/sto/workspace/datomworld/collab/1792197000000-architect-yang-ts-php-designs.claude-cloud-fable.findings.md).

1. **[P1] JS strings cannot use the proposed carrier across the existing codec.**  
   Design:130 represents strings as `{:js/str s}` containing native UTF-16 text. JS permits lone surrogates, including values produced by indexing a supplementary character. Jing explicitly refuses these in [cbor.cljc:938](/Users/sto/workspace/datomworld/src/cljc/dao/jing/cbor.cljc:938). Thus `"\uD800"` cannot become an addressed literal, and `"😀"[0]` cannot round-trip through Jing. Adding code-unit accessors does not solve storage. Rule a portable code-unit representation and test literals, slicing, property keys, publication and continuation encoding. [ECMAScript string specification](https://tc39.es/ecma262/2022/multipage/ecmascript-data-types-and-values.html#sec-ecmascript-language-types-string-type).

2. **[P1] CST-derived generated names violate the promised erasure identity.**  
   Design:101 adopts `%<prefix><cst-id>`, while design:83 and :218 require byte-identical rows after erasure. Adding an annotation or erased declaration changes subsequent preorder CST IDs. Generated lambda parameters and variable names consequently change, and those symbols are canonical row slots: [vm.cljc:998](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:998). The Python generator being lifted uses exactly this naming scheme. Define names from a normalized runtime tree, or specify canonical renaming before projection. Acceptance needs annotations inserted *before* loops, functions and generated temporaries.

3. **[P1] Ambient/imported `const enum` contradicts the optional `.d.ts` story.**  
   Design:74 promises literal inlining, but design:88 says an unchecked composition never fetches declaration units. An imported ambient `const enum` can have no runtime object; lowering needs its declaration values to generate executable code. This is a runtime compilation dependency even with checking absent. Rule explicit refusal, mandatory pinned declaration inputs, or another defined emit policy. Also pin the TypeScript compiler version and emit flags; a version alone does not determine import elision or enum preservation. [TypeScript isolated-module restrictions](https://www.typescriptlang.org/tsconfig/isolatedModules.html), [enum publication pitfalls](https://www.typescriptlang.org/docs/handbook/enums.html#const-enum-pitfalls).

4. **[P1] Declaration claims lack an implementable occurrence-resolution contract.**  
   Design:77–89 describes zero-program-row declaration units whose claims target consumed modules’ binders. The actual claim entity is `[canonical-root path]`, per [yin.vm.typecheck.md:135](/Users/sto/workspace/datomworld/docs/design/yin.vm.typecheck.md:135). A manifest address is not that root, and the proposed guest module exports only `spec` and `body`; guest exports are lexical cells inside `body`. Erased interfaces and aliases have no runtime binder occurrence at all. Specify the claims artifact, target-tree resolution, export-to-occurrence mapping, type-only declaration representation and `claims-t` coordination before T10 can have a testable contract.

5. **[P1] ES module delivery is described, but module instantiation is missing.**  
   Design:159–162 reuses Python’s `spec/body` shape and proposes dependency resolution followed by depth-first body execution. ES linking also creates bindings and initializes hoisted function declarations before evaluation. Allocating cells only when each body runs fails valid cyclic calls into an exported function of a module whose body has not started. Python’s landed SCC construction supplies lazy bodies, not this instantiation phase: [lower.cljc:2193](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc:2193). Define separate instantiation and evaluation operations, including failure/cache states. Add cyclic function-call and missing-export-before-side-effect fixtures. [ES2022 InitializeEnvironment](https://tc39.es/ecma262/2022/multipage/ecmascript-language-scripts-and-modules.html#sec-source-text-module-record-initialize-environment).

6. **[P1] PHP reference markers need ownership and rebinding rules beyond the stated algorithm.**  
   Design:266 always moves the source content into a fresh shared cell. Repeating `$b =& $a; $c =& $a;` would nest markers or disconnect aliases under one-level dereferencing unless an existing marker is reused. Unconditional marker survival also mishandles:
   ```php
   $a = [1]; $r =& $a[0]; unset($r);
   $b = $a; $b[0] = 2; echo $a[0]; // 1
   ```
   PHP unwraps an otherwise unshared reference during array duplication. Specify guest reference ownership/demotion and nested lvalue operations, or obtain an explicit departure ruling. The simple landed §8.11 sketch needs this refinement; O(1) persistent copying alone is insufficient. [PHP 8.3 array-duplication implementation](https://raw.githubusercontent.com/php/php-src/PHP-8.3/Zend/zend_hash.c).

7. **[P1] The verbatim TypeScript grammar choice does not establish the advertised syntax surface.**  
   Design:68–79 promises conditional/mapped types, `satisfies` and type-only imports. The inspected upstream grammar has no conditional-type production or type-only-import modifier, and its mapped-type surface does not implement the promised forms. No actual grammar revision is selected in the design. Choose a revision and prove representative parses before accepting the inventory; otherwise schedule grammar patches or narrow support. Pin fixtures such as `type X<T> = T extends string ? 1 : 2` and `import type {X} from "x"`. [Upstream TypeScript parser grammar](https://raw.githubusercontent.com/antlr/grammars-v4/master/javascript/typescript/TypeScriptParser.g4).

8. **[P1] The execution acceptance matrix contradicts declared nondependencies.**  
   Design:24 excludes dependence on L-b, yet :31, T1 and P1 promise linked execution on all four evaluators. The landed linked harness explicitly excludes the AST walker until L-b: [linked_harness.cljc:15](/Users/sto/workspace/datomworld/test/yang/python/antlr/linked_harness.cljc:15). This needs an explicit prerequisite or a qualified bundled/linked matrix. Likewise, PHP P7’s statement-level `:line` sites need additional shared machinery: [yang.safepoint.cljc:135](/Users/sto/workspace/datomworld/src/cljc/yang/safepoint.cljc:135) currently rejects every selected non-lambda site. Neither L1–L4 supplies that extension.

9. **[P2] Several PHP semantic rulings disagree with the proposed PHP 8.3 oracle.**  
   Design:268 evaluates static initializers at function definition; PHP 8.3 supports dynamic initializers evaluated when execution first reaches the declaration. Design:272 incorrectly assigns `ArithmeticError` to `PHP_INT_MIN % -1`; PHP returns `0`, while `intdiv(PHP_INT_MIN, -1)` throws. Its pairwise equality shorthand also wrongly groups `0`, `""` and `[]` as mutually equal. Design:309’s `@` suppression must preserve custom error-handler invocation. Replace these summaries with explicit oracle tables and fixtures. [Static initialization](https://www.php.net/manual/en/language.variables.scope.php), [modulo implementation](https://raw.githubusercontent.com/php/php-src/PHP-8.3/Zend/zend_operators.c), [comparison tables](https://www.php.net/manual/en/types.comparisons.php), [error-handler rules](https://www.php.net/manual/en/function.set-error-handler.php).

10. **[P2] The JS runtime inventory contains observable semantic departures that are not ruled as departures.**  
    Design:109 makes `for-in` own-properties-only and snapshots keys. ES enumeration includes inherited enumerable properties and skips properties deleted before visitation. D6 also describes all script globals as global-object properties, omitting the separate lexical bindings for top-level `let`/`const`. Finally, design:52 cannot preserve JS compatibility merely by refusing a TS interpretation: `a<b>(c)` is also valid JS relational syntax. Correct these rules and require dialect-specific disambiguation fixtures. [ES2022 enumeration rules](https://tc39.es/ecma262/2022/multipage/ecmascript-language-statements-and-declarations.html#sec-enumerate-object-properties).

11. **[P2] PHP’s parser-span acceptance assumes away upstream helper rewrites.**  
    Design:298 says heredoc tokens retain real spans without exporter changes. The upstream Java helper replaces heredoc-ending tokens with constructed `CommonToken` values and rewrites closing-tag token types. The landed exporter classifies mismatched text/index combinations as synthetic and gives them zero-width spans: [cst.clj:157](/Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj:157). P0 needs helper-specific provenance/span fixtures and any necessary pinned patches. The PHP helper also carries an explicit MIT notice, so inheriting Python’s “same unresolved-notice situation” is inaccurate. [Upstream PHP lexer helper](https://raw.githubusercontent.com/antlr/grammars-v4/master/php/Java/PhpLexerBase.java).

12. **[P2] Migration and REPL acceptance omit necessary lifecycle cases.**  
    P8 cannot simply retarget the browser demo: it currently compiles editable PHP source synchronously in CLJS at [compilation_pipeline.cljs:159](/Users/sto/workspace/datomworld/src/cljs/datomworld/demo/compilation_pipeline.cljs:159), whereas the replacement parser is JVM-only. Choose a service-backed flow, fixture-based demo or explicit removal. L4 also needs a second frontend fixture, language switching and reset tests: today `(lang …)` only changes `:lang`, while primitives and `:frontend-session` survive. Existing Python output remaining green does not prove selected-binding primitive installation or session isolation.

13. **[P2] Missing-include acceptance must distinguish publication absence from delivery failure.**  
    Design:381 and P6 promise warning/false versus `Error` for missing includes. Reusing L3 preserves [publication.cljc:78](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/publication.cljc:78), which refuses an unpublished static dependency before execution. The governing ruling explicitly requires concrete pins: [yang.antlr.md:2845](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:2845). State whether “missing” means unavailable content at an already pinned address, or introduce a separately ruled representation for statically absent units. Test both stages.

14. **[P3] Correct the audit’s smaller factual overstatements.**  
    D7 is substantially right about duplicated emitters, but `free-names` and `defined-keys` are already shared through `prelude/*` calls in [safepoint.cljc:269](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/safepoint.cljc:269). The legacy PHP test file contains nine `deftest` forms, not eleven. ADR 0004 is currently untracked and marked Proposed; I applied its doctrine because the task explicitly required it, rather than treating it as committed law.

D1–D4 and D5’s taxonomy omission are confirmed. The language-neutral catalog/stage seams, additive checker topology, distinct null sentinels, float64 carrier posture, task-local runtime allocation, jobs-to-completion rule and refusal of ambient filesystem resolution are sound. Replacing the legacy PHP frontend is justified.

L1-first is sound and its address-preservation gate is appropriate. The owner-decision lists remain incomplete until they cover the representation, compilation-profile, module-instantiation, reference-ownership and dependency decisions identified above.

Verdict: REQUEST CHANGES