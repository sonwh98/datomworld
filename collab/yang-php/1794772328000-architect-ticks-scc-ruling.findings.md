Created-GMT: 2026-11-14 (ruled ~2026-11-14T2x:xxZ; filed by the seat — astra's session was read-only)
Coding-Agent: codex (gpt-6-astra, jupiter, ~/ci/datomworld-p7b-j @ 5ae34f5c, read-only)
Session-ID: /tmp/ticks-scc.out thread (jupiter codex exec)
Verdict: (b) — change the SCC wrapper insertion shape; preserve the linker discharge rule; NO instrumentation-origin admission exemption. Fixture bar ["ab" [] nil] stands.

**Ruling: choose (b)—change instrumentation placement; preserve the linker’s discharge rule.** The expected `["ab" [] nil]` remains the include gate’s acceptance bar.

I could not save `collab/yang-php/1794772328000-architect-ticks-scc-ruling.findings.md`: this session’s filesystem policy permits reads only. The findings follow inline.

**Evidence and scope.** I inspected checkout `5ae34f5c`, the linker contract and implementation, SCC emission, shared instrumentation, include fixtures, and the [lifecycle oracle](/home/sto/workspace/datomworld/collab/orch-lane/p7-lifecycle-probes.txt). Commit `797c72b4` and its findings file were unavailable in the local repositories searched; I used your supplied diagnosis without claiming to have read that artifact. This is a design ruling with in-memory scanner verification, not a master-green certification.

**1. Admission contract**

Compiler-inserted applications count as applications. Their origin does not change “unconditional definition precedes every application site.”

The [discharge implementation](/home/sto/ci/datomworld-p7b-j/src/cljc/yin/vm/linker.cljc:1086) deliberately lacks value-flow analysis. Exempting a call because instrumentation inserted it would replace a structural proof with an origin-based assumption. That is particularly unsafe for ticks: `php.sp/line` delegates to `php/tick-line`, which can dispatch registered guest handlers.

Keep these requirements:

- Deferred free references discharge only against definitions satisfying the existing dominance test.
- Missing or unusable position information remains conservative.
- Required modules and primitives retain their pinned identity checks.
- Publication success remains distinct from successful linking and module initialization.

Do not declare internal `m*-body` names as ambient dependencies, install dummy bindings, or execute bodies to satisfy admission.

**2. Cause and approved fix shape**

The current [derivation path](/home/sto/ci/datomworld-p7b-j/src/cljc/yang/php/antlr/siteset.cljc:256) constructs:

```clojure
(u/then (require-node 'php.sp) derived-module)
```

This causes two independent problems:

1. The require application precedes the mutual body definitions.
2. `u/then` places `derived-module` inside a lambda body, making its definitions conditional to the scanners.

Replacing that with a wide application that still puts the require first fixes only the second problem.

For the [deferred SCC emitter](/home/sto/ci/datomworld-p7b-j/src/cljc/yang/php/antlr/lower.cljc:4000), retain this initialization order:

```text
member spec literals
all member body closures
require php
require php.sp, when selected hooks need it
module initialization completes
exports become available
include processing marks the path and invokes its body
```

A concrete admissible candidate for this SCC shape is:

```clojure
(core-module/module-tree
  [derived-scc-module
   (core-module/require-node 'php.sp)])
```

The existing wide-application machinery leaves definitions in operand evaluation, outside deferred wrapper bodies. Alternatively, append the hook require directly to the SCC’s existing operand vector.

**Guard:** trailing placement is valid only where module initialization does not execute the instrumented guest bodies or expose them for invocation before the hook dependency is available. Do not apply this placement indiscriminately to modules with eager instrumented execution. Those require an explicit definitions/dependencies/execution arrangement satisfying both admission and hook availability.

For **zero selected hooks**, omit the new hook require and sequencing wrapper. Continue validating the signed site publication and deriving dependencies. An unchanged module AST may still need republication if dependency pins change; “no local hooks” does not authorize bypassing dependency derivation.

The final manifest must pin `php.sp` whenever its hook is referenced. Derivation records and occurrence mappings must describe the final transformed tree, including the dependency insertion.

**3. Verification performed**

I ran read-only, in-memory probes through the actual scanners and `undischarged` function.

| Shape | Semantic | Stack | Register |
|---|---|---|---|
| Original deferred SCC | Internal references discharged | Discharged | Discharged |
| Current `u/then` prefix | Both body references retained | Retained | Retained |
| Wide application, require first | Both body references retained | Retained | Retained |
| Wide application, require last | Internal references discharged | Discharged | Discharged |

I also constructed the actual mutual-include SCC through the PHP parser and emitter:

- Original fixture: **zero published/selected tick sites**.
- Variant with `declare(ticks=1)` in both members: **six selected sites**.
- For both variants, the current wrapper retained `m1-body` and `m0-body` on all three vector formats; trailing placement discharged them.

Thus the original fixture’s regression does not require an executed tick hook: the unconditional dependency wrapper alone suffices.

These probes establish discharge behavior. They do not establish complete linked execution or AST-walker support.

**4. Decision table and implementation gate**

| Row | Required result / guard |
|---|---|
| Mutual `include_once`, no ticks | Preserve `["ab" [] nil]`; introduce no unnecessary hook wrapper. |
| Mutual include with active ticks | Establish every mutual body binding before applications; make the hook available before guest execution; preserve statement-completion timing. |
| Dependency ordering | Preserve dependency-first publication, including `b < a < main` for the existing acyclic ordering row. |
| SCC ordering | Keep the cyclic pair as one publication step, bundle before aliases. Sorted emission order must not become guest execution order. |
| Eager instrumented initializer | Do not use trailing placement unless hook availability before its first execution is proven. |
| Early application or conditional definition | Retain the obligation; refuse when undeclared or unresolved. Instrumentation provenance grants no exemption. |
| Missing/tampered site evidence or dependency | Preserve the existing refusal; no fallback to ambient bindings. |
| Eval collision / compile fatal | Preserve the existing eval-bridge admission, atomicity, and terminal lifecycle behavior. |

The implementation gate should exercise the original fixture and an active-ticks cyclic variant on every scheduled vector backend, with explicit backend-result coverage so an empty result map cannot pass. Retain the linker’s early-application negative tests. Verify that linking/initialization produces no guest output or tick-handler execution.

**5. Include and eval semantics remain unchanged**

The [include runtime](/home/sto/ci/datomworld-p7b-j/src/cljc/yang/php/antlr/prelude_module.cljc:84) marks a successfully delivered path before invoking its body. Consequently, `a` prints `a`, includes `b`, and `b`’s include-once of `a` returns without re-entering it. Binding closures earlier does not execute them.

The lifecycle oracle remains controlling: an eval collision is uncatchable, shutdown runs, destructors are suppressed, and declarations within that eval commit atomically while prior eval declarations persist. This ruling authorizes no eval-bridge change and no guest execution during instrumentation or admission.

**Disposition:** approve the guarded insertion-shape repair; reject an instrumentation-specific admission exemption. Preserve `["ab" [] nil]`; full execution verification remains the implementation gate.