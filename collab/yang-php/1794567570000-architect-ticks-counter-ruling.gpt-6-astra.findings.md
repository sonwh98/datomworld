Completed-GMT: 2026-11-13 11:04:20 GMT
Completed-Local: 2026-11-13 18:04:20 +0700
Coding-Agent: codex
Role: Architect, slice-3 design consult
Reviewed checkout: p7b-ticks @ 38375bf7
Status: counter ruling complete; implementation and formal corpus acceptance remain outstanding

**Ruling: one counter per PHP request, with the threshold supplied by each selected statement-completion hook.** There is no counter per declare occurrence, source file, function/op_array, closure, or generator. Lexical scope controls which completion sites exist and their thresholds; it does not partition the runtime count.

This is supported both by PHP-8.3 source inspection and direct execution of 21 small programs on shiva's PHP 8.3.35 oracle image. All five staged expectations match. This consult changes only this findings artifact: no source, test, design-document, fixture-provenance, or branch changes; no commit or push.

**Findings and reconciliation**

| Severity | Location | Evidence / required correction |
|---|---|---|
| P1 — contradictory implementation instruction | `collab/yang-php/1794567390000-engineer-p7b-ticks-c.prompt.md:20` | The explicit “a count is per declare-site, PHP semantics” instruction is incorrect. Replace it with the request-owned counter rule in this ruling. Part C was right to escalate the conflict instead of claiming parity. |
| Resolved — slice-3 ownership | `collab/orch-lane/1794700000000-architect-safepoint-slice3-design.gpt-6.1-sol.findings.md:103` | The text explicitly says “request-owned counter” and says state survives calls, includes, and generator switches. Its lifecycle table at lines 123–127 and implementation assignment at line 257 reinforce that contract. The implementer's request-wide reading is correct. |
| P2 — stale design status/timing | `docs/design/yang.php.md:1938` | §12 still says sites are before statements and the generic extension refuses non-lambdas. The implemented after-wrapper is at `src/cljc/yang/safepoint.cljc:153`; PHP's profile is at `src/cljc/yang/php/antlr/safepoint.cljc:13`. Reconcile §12 in the implementation round. |
| P1 — acceptance does not discriminate ownership | `test/yang/php/antlr/programs_p7b.cljc:10` | Every staged tick threshold is 1. All these outputs can pass with an incorrectly partitioned counter. Add N>1 boundary/threshold cases below before claiming counter parity. |
| P2 — oracle exit status is insufficient for pending rows | `src/dev/yang/php/oracle.clj:558` | Pending rows are excluded from failures, while `check-p7-fixture` at line 492 deliberately leaves their `:pass?` false. Read and compare actual records; exit 0 / “failures: 0” alone proves no parity for these rows. |

I own the reconciliation failure between the design, stale documentation, and implementation brief. The slice-3 counter paragraph itself was explicit, not ambiguous. Its omission of a compact lexical-versus-runtime decision table made the broader artifact set easier to misread. At this checkout, neither §12 nor the §15 P7-b row (`yang.php.md:2106`) actually specifies a per-declare counter; §15 requires PHP-equivalent tick counts. File-scoped compilation does not imply file-owned execution state.

**PHP 8.3 source evidence**

The links below refer to the PHP-8.3 branch inspected for this consult; line numbers are ordinary one-based file lines. They are source references, not a claim that the moving branch is the exact source hash of the oracle image.

- [Zend/zend_globals.h:187](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_globals.h#L187): `ticks_count` is a field of executor globals.
- [Zend/zend_execute_API.c:151](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_execute_API.c#L151): `init_executor()` initializes it to zero for execution/request startup.
- [Zend/zend_execute.c:5153](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_execute.c#L5153) includes generated `zend_vm_execute.h`. The tick handler's maintained definition is [Zend/zend_vm_def.h:7439–7452](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_vm_def.h#L7439). Its test is `(uint32_t)++EG(ticks_count) >= opline->extended_value`; it resets the count before calling the tick dispatcher. It counts even when no dispatcher is installed. This is an executor-global accumulator and an instruction-local threshold, not op_array-local state.
- Compiler mechanism: [Zend/zend_compile.c:344–361](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_compile.c#L344) initializes a new file's compile-time ticks setting to zero and restores the prior file context on exit. [Lines 5938–5994](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_compile.c#L5938) convert the directive literal to a long, update that setting, compile a block, and restore its prior setting; the semicolon form leaves it changed. [Lines 7065–7200](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_compile.c#L7065) switch function op_arrays without starting a fresh file context. [Lines 9784–9785](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_compile.c#L9784) emit ticks after selected statements when the setting is nonzero. [Lines 2110–2121](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_compile.c#L2110) place that setting in the instruction's extended operand and suppress adjacent duplicate tick emission. [Lines 2518–2522](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_compile.c#L2518) enumerate excluded statement categories; [lines 9659–9668](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_compile.c#L9659) separately handle top-level function/class declarations. A blanket “hook every AST statement” translation is insufficient.
- [Zend/zend_vm_def.h:7867–7874](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_vm_def.h#L7867): yield saves the next instruction and returns. [Zend/zend_generators.c:706–828](https://github.com/php/php-src/blob/PHP-8.3/Zend/zend_generators.c#L706) restores execution frames/JIT context around resume, not a saved tick count.
- [ext/standard/basic_functions.c:1549–1569](https://github.com/php/php-src/blob/PHP-8.3/ext/standard/basic_functions.c#L1549): user tick dispatch guards each executing registered entry against reentry. Do not replace this with a global “ticks busy” rule that stops counting all guest tick sites. Registered callback arguments are its registration arguments, not the line/unit/threshold operands.

Thus switching files or op_arrays changes the instructions being executed, and possibly their embedded thresholds, without selecting a different counter. The runtime probes below independently confirm the relevant observable consequences.

**Normative decision table for the implementer**

| Concern | Required behavior |
|---|---|
| Counter owner | Allocate one explicit guest request-owned counter, initially 0, shared by all PHP tick hooks in that request. Keep it in existing guest execution/store machinery; introduce no host global or shared process state. |
| Increment and firing | On each selected normal completion, increment once. If the new count is **greater than or equal to** that site's N, reset to 0 **before** dispatching registered handlers. Do not use equality, modulo, subtraction of N, or a threshold-global “current N.” |
| Reset points | Request initialization and threshold firing only. No reset at declare entry/exit, function call/return, include/return, registration/unregistration, generator switch, or exception/context restoration. A new independent request starts from zero. |
| Empty registry | Continue incrementing/resetting at selected sites. Registration changes dispatch membership, not counter phase. Confirmed by the empty-registry probe. |
| Lexical N | Each mark carries its own effective compile-time N. An inner block directive overrides the enclosing value for its body; exiting that block restores the outer value for subsequent emitted sites. Entering/leaving either block does not reset the accumulated count. |
| N=0 | That lexical region contributes no tick instructions for its own statements; it does not clear accumulated progress. An enclosing nonzero context may still select the completion of the declare construct itself. |
| Directive syntax | Preserve block versus semicolon behavior and Zend's statement-selection/adjacent-suppression behavior. A directive can itself lead to a tick: the measured semicolon case below produces ATB, not ABT. There is no extra generic “enter declare” runtime reset or hook. |
| N validation/range | Ordinary positive integer thresholds and zero are the cases established here. Do not invent “all N<=0 disable ticks”: Zend's literal conversion and unsigned instruction operand are separate concerns. Broader literal/range acceptance needs its own oracle cases or an explicit supported-subset refusal. |
| Functions and closures | Retain body-site thresholds from compilation. A call from ticked code does not dynamically instrument an unticked body. A ticked body contributes to the same request count even when called from unticked code or after its defining include has returned. |
| Generator yield/resume | Preserve the pending after-hook in the continuation. Suspension itself does not complete the yield statement. On eventual normal completion run that hook once using the then-current request counter. Repeated inspection of a suspended value does not replay it. Never snapshot/restore the count with generator context. |
| Nonlocal exit | A statement whose normal completion is bypassed contributes no after-hook. Already completed sites retain their count effects through throw/unwind. A callback exception cannot undo the reset that preceded dispatch. A pending hook is not a finally hook. |
| Include/include_once | Included code has its own lexical sites/N; the caller's pragma is not inherited dynamically. All executed sites share the request counter. Repeated includes revisit their sites; a skipped once body contributes none. A selected caller include statement still completes and can tick even when the once body is skipped. |
| Budget/profile separation | Statement ticks do not charge `php.sp/tick`, the existing execution-budget hook. Preserve independently selected `:call`/`:loop` sites and the shared VM semantics. |

The intended profile and operand contract remains exactly:

```clojure
{:line {:hook 'php.sp/line :operands true :after true}}

[:line n {:unit unit-path :ticks N}]
;; derives:
(php.sp/line n {:unit unit-path :ticks N})
```

Here `n` and `:unit` identify the source occurrence; neither is a counter key. `:ticks` is the effective instruction threshold, not a declaration identifier. The generic safepoint stage transports literals and performs value-preserving normal-completion placement; PHP lowering owns eligible-site selection and PHP runtime code owns counting/dispatch. Omitting `:line` from a derivation profile omits these hooks; canonical program rows remain unchanged.

**Include-site handoff remains a landing requirement**

The source-unit operand does not itself transport marks through publication. At `src/cljc/yang/php/antlr/publication.cljc:42`, `:site-set` is still nil. Preserve slice-3 lines 133–154: publish and retrieve verified occurrence site sets bound to the canonical root/source association; derive included executable images using the selected ticks profile; pin the hook dependency and derived images; handle SCC bundles and repin member aliases. Missing required metadata must fail closed, whereas a verified empty set for an unticked unit is legitimate.

Every derived module must resolve the same request's hook/state. Including a module must not reinitialize the tick registry or accumulator. A retained closure keeps its defining unit's operands and uses the active request's shared counter. No extra counter indexed by declaration, unit, or line is needed.

**Measured fixture evidence**

Executed on `shiva.stigmergy.systems`, 2026-11-13, in fresh disposable containers per program. Existing tag `php:8.3-cli` reports PHP 8.3.35 CLI NTS / Zend 4.3.35, built 2026-10-06. The shorter `php:8.3` tag was not installed; no image was pulled.

- Image ID: `sha256:7855de8f54c32ffc92720b20b550f2dbc1c82c2000c7a850ccf350f71c36293f`
- Repository digest: `php@sha256:aafe21201943a8a6e497ddfb471e2ce68ada76adede38c7d61fede8918b24319`

Each main program and included unit was placed in a temporary directory, mounted read-only at /w, with this invocation:

```sh
docker run --rm --pull=never --network=none --read-only \
  -v <temporary-directory>:/w:ro -w /w php:8.3-cli php \
  -d display_errors=1 -d html_errors=0 -d error_reporting=E_ALL \
  -d log_errors=0 -d auto_prepend_file=/w/pre.php main.php
```

The unticked pre.php was the exact staged prologue:

```php
<?php function tick_handler() { echo 'T'; } register_tick_function('tick_handler');
```

This matches the P7 oracle runner's prepend-unit arrangement. Every one of the 21 executions exited 0 with empty stderr and exactly the stdout recorded here, with no diagnostics. Temporary directories were removed. These are independent direct PHP oracle measurements, **not** a run of the Clojure `--p7` wrapper or of yang.php.

| Fixture and source location | Staged expected | Measured PHP 8.3.35 | Interpretation |
|---|---|---|---|
| sequence, `programs_p7b.cljc:10` | ATTBT | ATTBT | Three completions: echo A, assignment, echo B. |
| loop-body, line 12 | ATAT | ATAT | Only the inner declared body is ticked; loop machinery is outside its directive. |
| function-call, line 14 | FT | FT | f's body is unticked; the call statement's normal completion ticks. |
| generator-resume, line 16 | ATBT | ATBT | current() suspends at yield; A prints; next() completes yield and ticks, then B completes and ticks. |
| include-handoff, line 19 | LT | LT | Included lib.php owns a ticked echo site; the caller is unticked. |

The sequence variants also confirm the slice-3 proposals: N=2 gives ATB; N=0 gives AB.

**Oracle acceptance ruling**

There is no remaining choice between counter architectures: the source mechanism and the N>1 measurements settle it in favor of the request-owned count. PHP remains the authority for exact site selection and observable output if a lowering prediction disagrees.

The formal `--p7` pass on shiva MUST compare all five current IDs: `:sequence`, `:loop-body`, `:function-call`, `:generator-resume`, and `:include-handoff`, including output, absence of diagnostics/uncaught errors, and successful exit. Record actual version, image digest, host, and date. Do not infer a passing fixture from the current runner's pending-row exit status. This consult leaves all `:oracle :pending` fields untouched.

For counter-ownership acceptance, the implementer must add and oracle-confirm at least separate-declares, nested-threshold, cross-function, cross-include, and generator-shared from the appendix. Also retain the zero-gap, unticked-include, registration/empty-registry, retained-closure, exception, once-skip, and semicolon regressions. The original five N=1 rows cannot replace these discriminators. P7-b's remaining lowering/runtime/linking gates and the broader slice-3 callback/escape/cleanup cases remain required; this consult is not implementation sign-off.

**Reproducible additional probes**

Each source below is main.php after an opening PHP tag, with the same separate pre.php above. Where shown, lib.php is a separate file. Outputs are observations, not new hand-derived goldens.

**sequence-n2 — measured `ATB`.**

```php
declare(ticks=2) { echo 'A'; $x = 1; echo 'B'; }
```

**sequence-n0 — measured `AB`.**

```php
declare(ticks=0) { echo 'A'; $x = 1; echo 'B'; }
```

**separate-declares — measured `ABT`.**

```php
declare(ticks=2) { echo 'A'; } declare(ticks=2) { echo 'B'; }
```

**nested-threshold — measured `ABCTD`.**

```php
declare(ticks=5) { echo 'A'; $x=1; echo 'B'; declare(ticks=2) { echo 'C'; } echo 'D'; }
```

**zero-gap — measured `ABCT`.**

```php
declare(ticks=3) { echo 'A'; } declare(ticks=0) { echo 'B'; } declare(ticks=3) { echo 'C'; $x=1; }
```

**cross-function — measured `AFT`.**

```php
function f() { declare(ticks=2) { echo 'F'; } } declare(ticks=2) { echo 'A'; } f();
```

**repeat-function — measured `F|FT`.**

```php
function f() { declare(ticks=2) { echo 'F'; } } f(); echo '|'; f();
```

**cross-include — measured `ALT`.**

```php
declare(ticks=2) { echo 'A'; } include 'lib.php';
```
lib.php:
```php
<?php declare(ticks=2) { echo 'L'; }
```

**unticked-include — measured `ALTB`.**

```php
declare(ticks=2) { echo 'A'; include 'lib.php'; echo 'B'; }
```
lib.php:
```php
<?php echo 'L';
```

**generator-shared — measured `ABTC`.**

```php
function g() { declare(ticks=2) { yield 1; } } $a=g(); $b=g(); $a->current(); $b->current(); echo 'A'; $a->next(); echo 'B'; $b->next(); echo 'C';
```

**registration-no-reset — measured `ABT`.**

```php
declare(ticks=2) { echo 'A'; } unregister_tick_function('tick_handler'); register_tick_function('tick_handler'); declare(ticks=2) { echo 'B'; }
```

**empty-registry — measured `ABT`.**

```php
unregister_tick_function('tick_handler'); declare(ticks=2) { echo 'A'; } register_tick_function('tick_handler'); declare(ticks=2) { echo 'B'; }
```

**semicolon-directive — measured `ATB`.**

```php
declare(ticks=2); echo 'A'; echo 'B';
```

**include-closure — measured `AFBFT`.**

```php
include 'lib.php'; echo 'A'; $f(); echo 'B'; $f();
```
lib.php:
```php
<?php $f=function(){ declare(ticks=2) { echo 'F'; } };
```

**exception-no-reset — measured `ABT`.**

```php
declare(ticks=2) { echo 'A'; } try { throw new Exception(); } catch (Exception $e) {} declare(ticks=2) { echo 'B'; }
```

**once-skip — measured `LBT`.**

```php
include_once 'lib.php'; include_once 'lib.php'; declare(ticks=2) { echo 'B'; }
```
lib.php:
```php
<?php declare(ticks=2) { echo 'L'; }
```

**Review scope and properties confirmed**

Read the repository architecture rules, slice-3 design, §12/§15, part-C brief/findings, staged programs/tests, PHP profile and shared after-wrapper, publication seam, and P7 oracle wrapper. Inspected the cited PHP-8.3 compiler/executor/generator/callback sources. Confirmed request-owned explicit state, lexical operand ownership, budget separation, after-normal-completion placement, and unchanged generic VM semantics as the intended architecture. Ran only the 21 direct PHP probes; no Clojure/Node/Dart implementation gates or formal corpus fold were run. The profile declaration exists, but `php.sp/line`, lowering/registry integration, and linked site handoff remain the implementer's work.
