Created-GMT: 2026-11-16T17:33:16Z (ruled; filed by the seat — astra's session is read-only)
Coding-Agent: codex (gpt-6-astra, jupiter POOL 2, read-only)
Session-ID: 01a1e569-b991-70d2-b81a-b5bafb3a0501
Verdict: ADMIT bare global constant reads via the existing php/const-get-fq runtime resolver — delete the lower.cljc:546 bare-global rejection branch. Undefined constant = CATCHABLE Error (php-src zend_throw_error), NOT a guest-fatal (the dispatch's errno-8 mapping was wrong: errno 8 = E_NOTICE). define()/const redeclaration warns errno 2 and continues. Fix = the narrow lowering correction + the stale e2e FOO refusal row + semantic guards; no production prelude change. Subsystem-order green likely = the slow-tier selection gap (-e :slow vs --slow), unverified.

Completed-GMT: 2026-11-16 17:33:16 GMT  
Completed-Local: 2026-11-16 12:33:16 EST

Deliverable: `collab/yang-php/1794850396000-architect-constant-admission-ruling.findings.md`  
Role: Architect, read-only consult  
Evidence: dw-dynfix `4e2067b26bad086fe4b61e529afad99ca464bc44`, checked against master `04f98c48`.

**Ruling: admit bare global constant reads through the existing `php/const-get-fq` runtime resolver. Preserve catchable `Error` semantics. Reject the proposed “uncatchable fatal, errno 8” mapping.**

The four fixtures are within the intended P6 contract. Their expectations should remain unchanged. The dispatch mischaracterizes `:const-undefined-error`: it explicitly catches `Error` and prints its message.

| Severity | Locus | Evidence / invariant | Recommended correction |
|---|---|---|---|
| High | [lower.cljc:546](/home/sto/ci/dw-dynfix/src/cljc/yang/php/antlr/lower.cljc:546) | Bare globals are refused despite existing declaration and runtime lookup support. | Remove the bare-global rejection branch; use the existing final `php/const-get-fq` arm. |
| High | Dispatch’s proposed undefined-constant mapping | PHP throws a catchable `Error`; errno `8` means `E_NOTICE`. | Keep `php/throw-error "Error"`; do not invoke `php/guest-fatal`. |
| Medium | [e2e_test.clj:235](/home/sto/ci/dw-dynfix/test/yang/php/antlr/e2e_test.clj:235) | `echo FOO;` is still asserted to be unsupported. | Replace that obsolete refusal row with a runtime undefined-constant guard. Preserve the other refusal assertions. |

No files were changed. Existing working-tree modifications were left untouched. This is a source-based ruling, not a passing-test certification.

**1. Admission and PHP semantics**

Bare constant expressions are supported language constructs even when their names are not defined at compilation. Name qualification belongs to lowering; lookup of user-defined values belongs to execution against the request’s constant table.

PHP 8.3.35’s `ZEND_FETCH_CONSTANT` executes the lookup and checks for an exception. `_zend_quick_get_constant` searches `EG(zend_constants)` and calls `zend_throw_error(NULL, …)` when the name is absent. This establishes execution-time resolution and an undefined-name exception, rather than a syntax/admission refusal. [Fetch opcode](https://github.com/php/php-src/blob/php-8.3.35/Zend/zend_vm_def.h#L5557-L5569), [lookup implementation](https://github.com/php/php-src/blob/php-8.3.35/Zend/zend_execute.c#L4683-L4715).

For this execution-time path, `zend_throw_error` selects class `Error` and calls `zend_throw_exception`. Consequently, `catch (Error $e)` and `catch (Throwable $e)` can catch it. The throwable’s code is `0`; an error-reporting errno is a different field. [zend_throw_error](https://github.com/php/php-src/blob/php-8.3.35/Zend/zend.c#L1607-L1630).

The dispatch conflates three classifications:

- Undefined constant during execution: catchable `Error`.
- Unhandled `Error`: terminal uncaught-exception reporting.
- Duplicate-class binding: the uncatchable declaration fatal covered by the earlier ruling.

PHP defines `E_ERROR = 1`, `E_WARNING = 2`, and `E_NOTICE = 8`. Thus “errno 8 class Error” is not the PHP 8.3 undefined-constant contract. [Error constants](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_errors.h#L20-L27).

The [1794798003000 class-fatal ruling](/home/sto/ci/dw-dynfix/collab/yang-php/1794798003000-architect-class-fatal-ruling.findings.md) specifically adjudicates duplicate-class binding. It does not authorize converting unrelated runtime `Error` exceptions into guest fatals.

**2. Exact fix shape**

Use an ordinary existing UAST application. No new node class, opcode, runtime table, or host primitive is needed.

At [constant-node](/home/sto/ci/dw-dynfix/src/cljc/yang/php/antlr/lower.cljc:546), delete the comment and conditional arm currently rejecting bare global names. Preserve the remaining structure:

```clojure
(let [{:keys [primary fallback]} (resolve-global-name ctx :const q)]
  (cond
    fallback
    (app* 'php/const-get (u/lit primary) (u/lit fallback))

    (contains? builtins/constants primary)
    (builtin-constant-node (get builtins/constants primary))

    :else
    (app* 'php/const-get-fq (u/lit primary))))
```

This retains builtin folding, namespaced fallback, and imported/qualified-name resolution. It adds no compile-time requirement that a user constant already exist. Do not lower a user constant to a variable read, a string literal, or a guessed value.

The runtime already supplies everything required:

| Locus | Existing responsibility |
|---|---|
| [prelude_module.cljc:324](/home/sto/ci/dw-dynfix/src/cljc/yang/php/antlr/prelude_module.cljc:324) | `php/const-lookup` reads `php.rt/constants`. |
| [prelude_module.cljc:336](/home/sto/ci/dw-dynfix/src/cljc/yang/php/antlr/prelude_module.cljc:336) | `php/const-get-fq` returns the value or throws `Error` with `Undefined constant "NAME"`. |
| [prelude_module.cljc:342](/home/sto/ci/dw-dynfix/src/cljc/yang/php/antlr/prelude_module.cljc:342) | `php/const-declare!` inserts once and warns on collision. |
| [prelude.cljc:6188](/home/sto/ci/dw-dynfix/src/cljc/yang/php/antlr/prelude.cljc:6188) | Initialization allocates the request runtime’s constant cell and builtin seeds. |
| [lower.cljc:2888](/home/sto/ci/dw-dynfix/src/cljc/yang/php/antlr/lower.cljc:2888) | `lower-const` emits declarations at statement execution. |

**No production prelude change is indicated for this unit.**

The channels remain distinct:

- Unsupported syntax: the existing `:yang.php/unsupported` lowering diagnostic.
- Undefined constant: `php/throw-error` → guest exception handling; if unhandled, `:php/uncaught` and status `255`.
- Redeclaration warning: `php/warn 2` → error-handler handling → `php/warn-record`/`php/warn-sink`, subject to existing reporting rules.

For an unhandled missing constant, the current result contract is:

```clojure
{:php/uncaught {:class "Error"
                :message "Undefined constant \"NOPE\""}
 :php/status 255}
```

It does not require `:php/fatal` or an errno-8 warning. The existing request completion code preserves this distinction. [Exception creation](/home/sto/ci/dw-dynfix/src/cljc/yang/php/antlr/prelude.cljc:320), [request completion](/home/sto/ci/dw-dynfix/src/cljc/yang/php/antlr/prelude.cljc:5934).

**3. `define()` and redeclaration**

Both fixture expectations are correct. PHP’s `zend_register_constant` emits `E_WARNING` and returns failure on duplicate registration without replacing the existing binding. `define()` converts that failure to `false`. [Registration](https://github.com/php/php-src/blob/php-8.3.35/Zend/zend_constants.c#L524-L558), [define](https://github.com/php/php-src/blob/php-8.3.35/Zend/zend_builtin_functions.c#L486-L494).

A global `const` declaration executes `ZEND_DECLARE_CONST`, which also uses `zend_register_constant`. Duplicate global constants therefore warn and continue; they do not inherit duplicate-class fatal behavior. [Declaration opcode](https://github.com/php/php-src/blob/php-8.3.35/Zend/zend_vm_def.h#L7676-L7704).

The existing `php/const-declare!` correctly calls **`php/warn 2`**, then returns `false`. Keep this handler-aware route; directly invoking `warn-sink` would bypass user error-handler behavior. A user handler may itself throw, but the redeclaration operation does not intrinsically fatal.

**4. Four-row decision table**

These are the actual fixtures in [programs_p6.cljc:258](/home/sto/ci/dw-dynfix/test/yang/php/antlr/programs_p6.cljc:258). Output strings below use escaped newlines.

| Fixture | Expected output | Expected diagnostic pairs | Completion |
|---|---|---|---|
| `:const-declare-and-read` | `"abcabool(true)\nbool(false)\n"` | `[]` | Normal; `:php/uncaught nil` |
| `:const-redeclare-warns` | `"1"` | `[[2 "Constant A already defined"]]` | Normal; original `A = 1` retained |
| `:const-undefined-error` | `"Undefined constant \"NOPE\""` | `[]` | `Error` is caught; normal completion |
| `:const-define-redefinition` | `"bool(false)\n1"` | `[[2 "Constant X already defined"]]` | Normal; second `define` returns false; original `X = 1` retained |

All four must lower successfully and produce results on semantic, stack, and register backends. None should acquire `:php/fatal` or status `255`. Normal completion currently need not contain an explicit `:php/status 0`.

The fixture test presently compares output, diagnostics, and uncaught exception; it does **not** assert fatal/status fields. Strengthen those guards rather than assuming the existing comparison proves them. [include_test.clj:52](/home/sto/ci/dw-dynfix/test/yang/php/antlr/include_test.clj:52).

| Guard | Required result |
|---|---|
| `define('X', 7); echo X;` | Outputs `7`; no warning or exception. |
| `try { echo NOPE; } catch (Error $e) { … } finally { … }` | Catch and finally execute normally; no unconditional guest fatal. |
| `echo NOPE; echo 'after';` without an exception handler | `:php/uncaught` identifies `Error` and the exact message; status `255`; `after` absent; no `:php/fatal`. |
| Duplicate `define` or duplicate global `const` | Errno `2`, original binding retained, subsequent code executes. |
| Constant value `false`, `0`, or PHP `null` | A defined value remains distinguishable from absence. |
| Read before a later declaration | Lookup occurs when the read executes; no declaration hoisting to make it succeed. |
| Same name in a fresh request | No user-constant leakage between requests. |

**5. Subsystem-order claim**

The deterministic refusal is confirmed in both inspected revisions. Git history attributes its introduction to `bf8c9c207`, after the runtime lookup arm already existed.

I found alternative admitted **syntactic** paths, not a warm-state admission path: namespaced reads, qualified reads, imports, and builtin folding. They are the other arms of `constant-node`. They do not explain these unchanged bare-global fixtures passing after another test.

The linked harness unconditionally calls `lower/lower-program` for the entry packet before VM execution. Its cached `published` value contains runtime publication, not an alternative entry-lowering result. Lowering constructs fresh namespace context per packet. [linked_harness.cljc:151](/home/sto/ci/dw-dynfix/test/yang/php/antlr/linked_harness.cljc:151), [lower.cljc:3716](/home/sto/ci/dw-dynfix/src/cljc/yang/php/antlr/lower.cljc:3716).

There is a concrete coverage explanation to check: this test is `^:slow`, whereas the default JVM subsystem command passes `-e :slow`; `--slow` selects `-i :slow`. A default subsystem green therefore does not establish that these rows passed. [affected.clj:676](/home/sto/ci/dw-dynfix/src/dev/affected.clj:676).

**Whether this explains the reported run remains unverified without its exact command and row-level output.** Do not implement warmup or runtime-composition changes as the constant fix.

The cited findings file was absent from both worktrees. Its version available through commit `c7e076ee` discusses continuation corruption and incomplete runs, but contains no four-row constant payloads. The supplied spans are therefore dispatch evidence, not independently recovered measurements in this consult.

**6. Non-weakening conditions and acceptance**

Preserve site-set verification, signatures, linker discharge, admission-before-execution, continuation validation, and operational-refusal classification. Reusing an existing application node does not authorize bypassing any of those checks.

Preserve genuine guest-fatal behavior, including duplicate-class errno `64`, status `255`, shutdown behavior, and suppressed guest catch/finally continuation. Undefined-constant exceptions must remain on their separate throwable path.

If eval is exercised, use the caller’s request constant table and register constants as their statements execute. Do not add whole-eval preflight, rollback, or compiler access to caller tables. The dedicated [atomicity ruling](/home/sto/ci/dw-dynfix/collab/yang-php/1794615000000-architect-atomicity-ruling.findings.md) controls over the lifecycle note’s unsupported atomicity interpretation.

Authorized implementation scope: the narrow lowering correction, correction of the stale `FOO` refusal assertion, and focused semantic guards. Keep all four P6 expected outputs unchanged and retain refusals for genuinely unsupported constructs.

Before landing, require fresh-process four-row results on every linked backend, caught/uncaught undefined-name guards, redeclaration guards, builtin/namespace controls, unchanged admission and class-fatal regressions, and the applicable foundation gate. Record quick and slow coverage separately.