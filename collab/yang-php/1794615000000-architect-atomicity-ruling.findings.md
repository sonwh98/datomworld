Created-GMT: 2026-11-13 23:1x GMT
Created-Local: 2026-11-14 06:1x +0700
Coding-Agent: codex
Session-ID: n/a (jupiter /tmp/atomicity-ruling.out, one-shot consult)
Reviewer: gpt-6-astra
Role: Architect, read-only design consult — eval redeclaration ATOMICITY policy
Note: read-only jupiter session could not write files; promoted verbatim from the response stream.

---

**Ruling: choose (b)—incremental declaration registration with an uncatchable guest-fatal collision. Do not implement whole-eval preflight or rollback.**

I could not create `collab/yang-php/1794614682000-architect-atomicity-ruling.findings.md`: this session permits filesystem reads only. The findings text follows.

---

Architect atomicity ruling · PHP 8.3.35 · Read-only design review

**Evidence and interpretation**

PHP’s eval path calls `zend_compile_string` from `zend_include_or_eval`; it does not first compare a declaration inventory with the caller’s tables. [PHP 8.3.35, zend_execute.c, lines 4630–4634](https://github.com/php/php-src/blob/php-8.3.35/Zend/zend_execute.c#L4630-L4634)

The compiler registers each top-level function after compiling that function’s body: `zend_compile_func_decl` inserts directly into `CG(function_table)` and reports a collision immediately. This is per-function registration, not a commit after successful compilation of the entire eval. `do_bind_function_error` uses `E_COMPILE_ERROR` for compile-time collisions and `E_ERROR` for runtime collisions, both through `zend_error_noreturn`.

Classes have early and deferred binding paths. Eligible top-level classes can enter the class table during compilation; a conflicting simple class falls through to deferred declaration. `zend_bind_class_in_slot` reports a name collision with `E_COMPILE_ERROR`. Consequently, registration order cannot be reduced to executing every declaration in textual order. [PHP 8.3.35, zend_compile.c: binding routines and declaration compilation](https://github.com/php/php-src/blob/php-8.3.35/Zend/zend_compile.c)

Runtime function declarations execute `ZEND_DECLARE_FUNCTION`, which invokes `do_bind_function` for that declaration. Deferred class declarations likewise have individual binding handlers. [PHP 8.3.35, zend_vm_def.h, declaration handlers](https://github.com/php/php-src/blob/php-8.3.35/Zend/zend_vm_def.h#L7360-L7435)

The lifecycle record’s shutdown/destructor/status observations remain binding: shutdown runs, further destructor output is suppressed, status is 255, and the caller cannot catch the collision.

However, its statement “all declarations in one eval commit together” is unsupported by the recorded observations and conflicts with the source. “S3 never ran” establishes termination, not rollback. Prior successful evals remaining visible also does not distinguish atomic from incremental registration. **Supersede that interpretation and r12’s whole-unit preflight requirement; preserve their lifecycle observations.**

**Service-path decision table**

| Situation | Required policy |
|---|---|
| Declaration reaches its binding point; name is free | Install into the caller’s request table. |
| Name belongs to caller code or an earlier successful eval | Preserve the existing binding; emit the collision diagnostic and enter `php/guest-fatal`. |
| Earlier declarations from this eval were installed before collision | Retain them for shutdown observation; no rollback. |
| Declaration has not reached its binding point | Do not install merely because it appears in the source. Early-bound declarations require their own timing rules. |
| Conditional declaration is never reached | No runtime registration or collision for that declaration. |
| Same admitted artifact is invoked again | Repeat declaration semantics against current request tables. Artifact reuse does not authorize redeclaration. |
| Syntax error | Guest `ParseError`; distinguish from declaration compile-fatal. |
| Genuine declaration compile-fatal | Guest-fatal classification, not catchable `Error` or generic invalid-source/`ParseError`. |
| Transport, verification, derivation or linker admission failure | Existing operational refusal; do not execute the eval entry. |

“Guest compile-fatal terminal path” names the engine’s lifecycle policy here. Preserve diagnostic distinctions where represented: Zend runtime function redeclaration uses `E_ERROR`, whereas compile-time function redeclaration uses `E_COMPILE_ERROR`.

**Placement on this branch**

Collision checks belong to **caller-task declaration binding**, using the borrowed request runtime. No whole-unit preflight belongs in the coordinator, source admission, or linker admission.

The existing boundaries support this:

- [eval_coordinator.cljc:19](/home/sto/ci/datomworld-p7b-j/src/cljc/yang/php/antlr/eval_coordinator.cljc:19) explicitly compiles without caller runtime state.
- [siteset.cljc:235](/home/sto/ci/datomworld-p7b-j/src/cljc/yang/php/antlr/siteset.cljc:235) admits verified site metadata; its buffered publication protects derived artifacts, not PHP declaration tables.
- [lower.cljc:3808](/home/sto/ci/datomworld-p7b-j/src/cljc/yang/php/antlr/lower.cljc:3808) supplies an eval entry borrowing runtime, scope, context and hooks.
- [prelude.cljc:824](/home/sto/ci/datomworld-p7b-j/src/cljc/yang/php/antlr/prelude.cljc:824) supplies the required terminal mechanism.

Publication and admission must remain code-only. After correlation and admission, the caller invokes the entry with its existing runtime. Binding checks use current tables at each semantic binding point; no request-table snapshot travels to the compiler.

Three implementation consequences need explicit attention:

1. [php/declare-function!](/home/sto/ci/datomworld-p7b-j/src/cljc/yang/php/antlr/prelude.cljc:2767) currently throws catchable `Error`. Its collision branch must use the guest-fatal route, preserving the old binding.
2. [php/declare-late!](/home/sto/ci/datomworld-p7b-j/src/cljc/yang/php/antlr/prelude.cljc:3963) currently treats any existing class name as a successful prior hoist. It must distinguish **this invocation’s successful early binding** from a pre-existing declaration. Use invocation-owned state, never persistent state in the cached artifact.
3. [unit-body/check-hoisted!](/home/sto/ci/datomworld-p7b-j/src/cljc/yang/php/antlr/lower.cljc:3710) currently groups functions before classes and rejects duplicate top-level functions during lowering. Neither establishes correct partial-registration semantics. Preserve Zend’s observable early-binding order; a compiler-discovered declaration fatal must not discard an observable registration prefix by returning only a generic rejection. An ordered caller-side binding prefix followed by a fatal action is an acceptable design.

Changing only the exception type is insufficient.

**Required fixtures**

Run each fatal fixture in a separate request/process. Register shutdown observers **before** eval; inspect tables using `function_exists` and `class_exists($name, false)`. Record output, diagnostic category/location, status, catch/finally markers and destructor markers.

| Fixture | Assertion |
|---|---|
| Caller function collision: eval declares fresh `before`, existing `occupied`, then fresh `after` | Shutdown sees `before`, the original `occupied`, and no `after`; eval body does not run. Primary discriminator against preflight. |
| Earlier successful eval, then the same collision sequence | Earlier eval declarations and the failing eval’s installed prefix remain observable. |
| Deferred class collision with fresh simple classes before and after it | Pin early-binding visibility separately from runtime statement progress; do not infer that textually later declarations are absent. |
| Conditional function/class collision, condition false versus true | False branch succeeds; reached collision terminates. Preserve effects preceding the reached collision. |
| Mixed class/function declarations before a top-level function collision | Pin compiler traversal order; expose the current functions-first lowering mismatch. |
| Two same-name top-level functions within one eval, preceded by a fresh declaration | Pin installed prefix and compile-fatal classification; expose wholesale compiler rejection. |
| Class collision across two eval invocations | Existing name must not be mistaken for this invocation’s successful hoist. |
| Reinvoke the same admitted artifact | First invocation succeeds; second checks current tables and terminates on collision. |
| Case variants and namespaces | Enforce normalized names within the correct function/class table; allow independent namespaces. |
| Syntax error and ordinary runtime exception controls | Preserve `ParseError` and catchable exception behavior; prevent blanket fatal conversion. |
| Invalid site metadata/admission control | No caller declaration binding occurs. |

Every collision case must additionally assert shutdown execution, status 255, absence of catch/finally continuation and suppressed destructor output. Exercise both eval expression paths through **request → coordinator → publication → site/linker admission → caller execution → terminal**, not just prelude calls.

Validation: source inspection only. PHP is unavailable locally; no new oracle probes or tests were run. The reported **22 tests / 132 assertions** remains the inherited baseline, not evidence for these new fixtures.
