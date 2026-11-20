Created-GMT: 2026-11-20 10:27:52 GMT
Created-Local: 2026-11-20 17:27:52 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a1f878-9255-7591-a9d7-8c528b565999
# Task: Architecture review of docs/design/yang.preludes.md (configurable and composable Yang preludes)
Role: Lead System Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-20 17:19:53 +07 (+0700) | Status: completed | Rationale: Independent different-family Lead System Architect review (OpenAI GPT reviewing Gemini-authored design) per docs/agents/team.md and docs/agents/roles/architect.md

Completed-GMT: 2026-11-20 10:25:52 GMT
Completed-Local: 2026-11-20 17:25:52 ICT

**1. Verdict: REVISE**

The shared-runtime decomposition is architecturally viable. The proposed Python-to-Clojure profile, however, contains incompatible numeric, calling, binding, and publication contracts. Several P1 extraction assumptions also contradict live code.

These are design defects requiring reconciliation before implementation, not objections that proposed namespaces do not yet exist. P4’s deferral is reasonable, but P1 must preserve existing generators.

Review was read-only. Evidence comprises source inspection and a small, in-memory numerical reproduction; repository test suites were not run.

**2. Findings**

**HIGH | [docs/design/yang.preludes.md:287](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:287) | The Clojure profile’s numeric hooks violate four-host portability | Define a portable numeric contract and adapters.**

The descriptor emits float64 carriers but maps arithmetic and comparisons directly to bare `+`, `-`, `*`, `/`, and comparison primitives. `yang.antlr.md` §8.5.5 explicitly states that generic arithmetic on decoded carriers is not portable: Node refuses carrier coercion while JVM/Dart can compute. Thus even `2.0 + 1.0` contradicts P3’s portability claim.

The “exact integer” claim also needs more than `clj.py/int-lit`: subsequent operations must preserve exactness and obey integer limits. [yin.vm.integer:9](/Users/sto/workspace/datomworld/src/cljc/yin/vm/integer.cljc:9) requires guest integer arithmetic through its kernels.

Specify operand kinds, mixed numeric operations, equality, division results, overflow/refusal behavior, and float reboxing. Alternatively, explicitly refuse unsupported numeric operations in this profile.

**HIGH | [docs/design/yang.preludes.md:310](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:310) | Hook substitution does not define a compatible lowering ABI | Specify frontend-owned hook signatures and structural lowering rules.**

The proposed mappings are not interchangeable with current Python calls:

- Python global reads/writes pass a module dictionary and name; `clj/global-get` and `clj/define-global!` take only a name, or name/value.
- Python `try` takes body, handler, and `orelse`; `clj/try` takes two arguments. Direct replacement loses `try/except/else` behavior.
- Python’s unit body receives `%globals` and `%globals-fn`; `clj/run-main` invokes a zero-argument body and removes one `[:yin.k/ok value]` wrapper.
- Python function construction includes parameter specifications, definition-time defaults, keyword defaults, and an argument-vector body.

See [Python lowering:801](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc:801), [function-value:1836](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc:1836), and [Clojure runtime:136](/Users/sto/workspace/datomworld/src/cljc/yang/clojure/prelude.cljc:136).

The example also uses `%`, but the descriptor supplies no remainder hook. Comprehensions, exception matching, unpacking, and parameter-binding capabilities need explicit support or refusal.

Keep these contracts in the installed frontend implementation or a frontend-supplied contract description. Making Yang core understand Python constructs would undermine the open SPI.

**HIGH | [docs/design/yang.preludes.md:390](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:390) | Shared entry shape does not establish Clojure callable admission or exception bridging | Specify trusted constructor and call-boundary behavior.**

Existing Clojure functions are not merely maps with `:yang.call/entry`. The compiler constructs admission witnesses and private invocation tokens; dynamic calls validate admission. See [descriptor construction:1282](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:1282) and [admission checking:2409](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:2409). A new immutable `clj.py` descriptor does not automatically satisfy separately compiled `clojure.core` consumers.

Likewise, passing `clj/raise` directly as the common raise exit is insufficient. It accepts a local thrown value, whereas `yang.call` transfers a condition. Existing [compile-guest-call:1427](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:1427) unwraps ordinary thrown payloads and privately retains protocol-condition provenance.

Define admission across independently compiled units, public invocation-record handling, binding-before-body exception protection, and condition conversion. Keep provenance operations inside trusted generated Layer-2b boundaries. Require tests for forged descriptors, condition-shaped thrown data, retained protocol exceptions, and A→B→A cleanup.

**HIGH | [docs/design/yang.preludes.md:427](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:427) | Publishing the entire existing library is neither a complete export plan nor a portable module plan | Publish an explicit portable surface with task-instantiation rules.**

`yang.clojure/library` is quoted source currently selected transitively by `library-for`. Publishing all of it changes admission requirements: `resolve` references unavailable `kind/resolve`, and `var?` requires `kind/var?`, which the supplied registry installs only on CLJ. See [kind-module:2578](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:2578) and [library:2677](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:2677).

Several promised names also are not definitions in that library: `empty?` is a VM primitive, while `assoc-in`, `update-in`, `set`, and `hash-map` are not supplied by the cited library/primitive surfaces.

Specify the export inventory, primitive-to-callable projections, portable subset, qualified sibling references, initialization, and per-task callable construction. Preserve library-internal bindings against caller global redefinition. Linking a module must not implicitly project all its exports into a source namespace.

**HIGH | [docs/design/yang.preludes.md:135](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:135) | The escape-frame contract excludes existing generator boundaries | Preserve generator frames during P1.**

The proposed frame kind is restricted to `:handler` or `:finally`. Both [Python gen-start:466](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc:466) and [PHP gen-start:5309](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc:5309) install `:generator` frames today. Existing raise logic dispatches every non-finally frame to its payload.

Either retain that handler-equivalent behavior or explicitly migrate generator boundary construction to the new representation. Deferring generator extraction does not defer compatibility.

Also make the four-to-five-slot migration explicit: current JS and Clojure contexts contain only `:handlers`. Distinguish frame count `n` from activation depth. The P4 description should not claim existing PHP generator rebasing matches Python: PHP currently restores its saved context directly; Python recalculates the crossing base.

**HIGH | [docs/design/yang.preludes.md:305](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:305) | Descriptor hooks introduce lexical capture and insufficient authority validation | Make generated references hygienic and validate exact authorized exports.**

Current Python lowering deliberately emits protected qualified runtime names. Replacing collection construction with bare `conj` permits a valid Python parameter named `conj` to capture generated machinery. Alias resolution also needs a defined precedence relative to locals, `nonlocal`, globals, and ambient defaults.

Namespace coverage in §6.3 proves neither export existence nor permission to expose it. The `yrt.*` provenance prohibition does not itself stop a descriptor alias or source-visible projection from exposing reserved `cell/dsg__*` doors. Registry availability is not source-level authority.

Require hygienic generated references, scope-aware alias resolution, exact export/profile checks, and reserved-door restrictions for source-facing projections.

The bundled Clojure wrapper needs a precise helper plan too. The existing preamble supplies four named helpers, and source `conj`/`vec` shadowing is already refused; the document’s claim that all listed primitives have existing `dsg__*` replacements is inaccurate. New helpers must remain portable guest closures, not captured host functions.

**MEDIUM | [docs/design/yang.preludes.md:169](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:169) | Float extraction assumes equivalence that live implementations do not have | Preserve evaluation order or explicitly approve a semantic change.**

Python/JS exponentiation recursively computes a half-power and squares its result. PHP squares the base before recursion. These orders round differently.

An in-memory reproduction of the source algorithms for `1.1 ** 6` produced:

| Algorithm | Binary64 result |
|---|---|
| Python/JS | `0x1.c585058dde7acp+0` |
| PHP | `0x1.c585058dde7abp+0` |

See [Python fpow:1514](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc:1514) and [PHP pow-sq:1361](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc:1361). One shared implementation cannot preserve both results unchanged.

Specify signed underflow/overflow behavior, NaN and infinity preconditions, and the finite-input domain of `fmod-pos`. Reinstate the explicit no-capture/no-safepoint rule around unwrapped arithmetic, including policy-hook paths. Exported constants and retained values must preserve float64 kind.

**MEDIUM | [docs/design/yang.preludes.md:181](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:181) | The integer adapter misstates the refusal algebra | Preserve returned reasons separately from operational failures.**

Integer v4 returns keyword reasons for bit limits, digit limits, and float overflow. Other refusals throw operational failures; they are not structured result maps for a generic guest handler. This is explicit in [yin.vm.integer:81](/Users/sto/workspace/datomworld/src/cljc/yin/vm/integer.cljc:81).

The proposed signature omits a dedicated float-overflow policy and leaves `on-refusal` ambiguous. PHP’s `int-result` also performs int-range-to-float conversion rather than the same translation as Python/JS.

Define successful result shapes and all three returned reasons precisely. Do not catch and relabel wrong-type, arity, or invalid-kernel-operation defects as guest exceptions. Keep coercion and whitespace policy language-owned; a shared `skip-ws` needs an explicit character policy.

**MEDIUM | [docs/design/yang.preludes.md:196](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:196) | The profile schema does not yet establish the claimed complete, reproducible composition | Separate emission recipes from resolved runtime profiles.**

The illustrated profile pins component addresses but contains no address for the owning prelude’s code. Names, revision labels, and emitter options alone do not content-pin its definition vector.

Similarly, a list of component names/addresses is insufficient to validate transitive dependencies, exports, or cycles without the corresponding artifacts. Existing `prelude-admission` checks host availability and constructs specifications; it does not supply that artifact closure.

Specify an immutable, verified artifact set as validation input, pin the root runtime module, and reconcile component edges with dependency manifests. Check collisions by qualified bindings and explicit source projections; equal bare export names in distinct modules are legitimate.

Clarify whether `:effects` is an allowed-effect ceiling. If it merely repeats the footprint already derived by publication, omit the duplicate authority. A ceiling must be checked against the derived transitive footprint.

**MEDIUM | [docs/design/yang.preludes.md:459](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:459) | Migration promises and acceptance gates need reconciliation | Define address-neutral boundaries and phase-specific evidence.**

P3 promises zero changes to existing languages, while D6 proposes making `yang.clojure` itself consume the new standalone library. Replacing current `library-for` bundling changes code and dependency identities. Keep existing defaults intact or schedule that migration as an explicit additional profile change.

P0 must preserve existing sequencing, initialization placement, tail marking, and Clojure’s custom preamble—not only linear versus balanced emission. Section 6.3 also calls lowering parameterization P0, whereas the roadmap assigns it to P2.

The gates need these additions:

| Phase | Required clarification |
|---|---|
| P0 | Compare against frozen pre-change artifacts; test verified dependency closure, missing exports, privilege exclusions, and actual projected-name collisions. |
| P1 | Test existing generators, five-slot migration, terminal exits, cleanup replacement, numeric bit patterns, no-capture float seams, and independent importing tasks. |
| P2 | Cover every supported lowering path and capability mode, including `:cell` and `:ambient-only`; generator expressions must not evade generator refusal. |
| P3 | Test separate-unit admission, lexical shadowing, primitive projections, module/global isolation, outcome-shaped ordinary data, and the portable library subset. |
| P4 | Preserve PHP lifecycle policy and distinguish its context behavior from Python rebasing. |

**3. Confirmed passing**

The following architectural choices are sound, subject to the corrections above:

- **Explicit state ownership:** Shared escape code receiving `ctx-cell` avoids a shared runtime handler slot. Language/task-owned cells fit the existing heap model. This establishes the intended boundary, not yet tested isolation.
- **Explicit control and guest closures:** `%capture`, saved context, and ordinary guest continuations preserve explicit causality. Policy closures do not introduce host callbacks.
- **Appropriate extraction boundaries:** Keeping object systems, coercions, import lifecycles, PHP reference management, and PHP budget hooks outside generic shared machinery preserves cohesion.
- **Escape-policy separation:** `try-orelse` versus `try-value`, normal-finally arguments, empty-stack policy, and PHP’s abandon-without-finally path correctly reflect real differences.
- **Provenance placement:** Excluding mint/read privileges from `yrt.*` is correct. The VM’s immutable private provenance sibling and collector tracing support retained-condition lifetime without an ambient registry. Enforcement remains incomplete as described above.
- **Open catalog compatibility:** No `yang.frontend` schema change is inherently required. Its immutable catalog, explicit pins, and ambiguity refusal support this design. Distinct configurations must receive distinct immutable revisions: identical `[id revision]` with different profiles correctly refuses.
- **Content-addressing discipline:** Schema-1 manifests, external C1 tables, explicit emitter shape, canonical operand construction, and retaining source/UAST are appropriate. Different descriptors need not always produce different UAST addresses; provenance must retain that many-to-one possibility.
- **Portability preparation:** Consolidating `defquoted` is reasonable if CLJD EDN expansion, macro imports, and reader aliases remain equivalent. Avoiding integral float literals and constructing negative zero multiplicatively follow existing rules.
- **Malleability:** Separating pure composition, admission, publication, and guest runtimes is useful. Keep frontend-specific hook contracts outside Yang core and measure guest export surface as well as host `defn` counts. No new VM opcode or AST tag is needed.

**4. Assessment of open owner decisions**

| Decision | Assessment |
|---|---|
| **D1 — Local mutation** | **Choose A: cells.** It matches current Python scope mechanics and supports reassignment and captured locals. Specify unbound reads, parameter binding, `nonlocal`, and per-module globals. Single-assignment should be a separate restricted profile. |
| **D2 — Module prefix** | **Choose A: `yrt.*`.** Linker names and implementation namespaces may differ; this follows the existing design direction. |
| **D3 — Python float tagging** | **Choose A: retain tags.** Removing them changes type discrimination, equality/coercion paths, and observable values. It exceeds a runtime extraction. |
| **D4 — Batched bump** | **Choose A conditionally.** Validate each extraction independently, then activate one coordinated profile bump. Golden-refresh convenience must not justify unreviewed numerical changes. |
| **D5 — Identifier mapping** | **Choose A: curated aliases.** Require scope-aware resolution, exact authorized targets, primitive callable projections, and collision tests. Table uniqueness alone is insufficient. |
| **D6 — Standalone library** | **Choose a constrained A.** Publish a defined portable surface with explicit dependencies and per-task instantiation. Preserve existing default Clojure emission for P3’s zero-churn promise; migrate it separately. Copying library implementations into `clj.py` would create unnecessary maintenance divergence. |
