Created-GMT: 2026-11-16 (ruled; filed by the seat — astra's session is read-only)
Coding-Agent: codex (gpt-6-astra, jupiter POOL 2, read-only)
Session-ID: 01a1e24b-4b1f-73b2-b0fd-de9edd83ccc6
Verdict: duplicate-class = uncatchable guest-fatal (php-src E_COMPILE_ERROR); 053ae9a8 correct; the class_model_test assertion is STALE; a test-only correction is authorized (mechanical replacement supplied). Documentary note: the dedicated atomicity ruling controls registration policy over the ticks-SCC ruling's atomicity repetition.

**Ruling: duplicate-class declaration is an uncatchable guest-fatal. The duplicate-class change in `053ae9a8` is correct; the test assertion is stale. Authorize a test-only correction.**

Read-only deliverable for the seat to file as:
`collab/yang-php/1794798003000-architect-class-fatal-ruling.findings.md`

Created-GMT: 2026-11-16  
Coding-Agent: codex  
Role: Architect, read-only consult  
Evidence baseline: `8a44b87a`

The current checkout is `3251ed12`, with an existing modification to `lower.cljc`. I inspected the requested baseline through `git show 8a44b87a:…`, the `053ae9a8` diff, origin’s r1 findings, and its preserved probe logs. No files were changed and no new tests were run.

**1. Intended contract and diagnostic observability**

A duplicate class name encountered at its semantic binding point must emit the collision diagnostic and terminate through `php/guest-fatal`. It must not become a catchable `Error`.

PHP 8.3.35’s `zend_compile.c` calls `zend_error_noreturn(E_COMPILE_ERROR, …)` on duplicate-class binding in both `zend_bind_class_in_slot` and `do_bind_class`. This applies even when the declaration reaches binding during execution. [php-src, class binding routines](https://github.com/php/php-src/blob/php-8.3.35/Zend/zend_compile.c#L1153-L1208)

The PHP 8.3 error machinery bypasses the user error handler for `E_COMPILE_ERROR`; its fatal path sets status 255, marks objects destructed, and calls `zend_bailout()`. This is fatal termination, not a thrown `Throwable` that guest code can catch. [zend.c](https://github.com/php/php-src/blob/php-8.3.0/Zend/zend.c#L1339-L1353), [main.c](https://github.com/php/php-src/blob/php-8.3.0/main/main.c#L1307-L1337)

The implementation already provides the required observable channels:

- [Duplicate-class branch](/home/sto/ci/dw-regfix/src/cljc/yang/php/antlr/prelude.cljc:3859): emits `php/warn-record` with errno `64`, then invokes `php/guest-fatal`.
- [Diagnostic sink](/home/sto/ci/dw-regfix/src/cljc/yang/php/antlr/prelude.cljc:328): records diagnostics in the request’s diagnostic cell or supplied stream, subject to the reporting mask.
- [Request completion](/home/sto/ci/dw-regfix/src/cljc/yang/php/antlr/prelude.cljc:5939): returns the fatal message as `:php/fatal`, with status `255`; this fixture’s `:php/uncaught` is **present with value `nil`**.

Therefore, no diagnostic-mapping change is needed. The test currently reads the wrong channel. The historical `php.diag/warning` field does not make errno `64` recoverable.

**2. Protected boundaries**

This correction conflicts with none of the protected behavioral invariants.

- **§8.10.7:** specifies transfer and identity of escaping source exceptions. A declaration fatal is request termination through the existing terminal continuation. Do not manufacture `:yang.call/thrown`, `ForeignException`, or `:php/uncaught` merely to expose its message. Ordinary exceptions and operational failures retain their existing classifications.
- **Ticks-SCC ruling:** preserve linker discharge, admission ordering, and the eval terminal lifecycle. A test-only correction changes none of those mechanisms.
- **Eval registration and lifecycle:** retain the existing binding, preserve declarations already installed at their binding points, run shutdown, suppress further destructor output, and prevent catch/finally continuation.

There is a documentary inconsistency requiring explicit interpretation: the [ticks-SCC ruling](/home/sto/ci/dw-regfix/collab/yang-php/1794772328000-architect-ticks-scc-ruling.findings.md) repeats the lifecycle record’s claim that an eval commits atomically. The [dedicated atomicity ruling](/home/sto/ci/dw-regfix/collab/yang-php/1794615000000-architect-atomicity-ruling.findings.md) expressly rejects that inference and requires incremental registration without whole-eval preflight or rollback.

**For this ruling, the dedicated atomicity decision controls registration policy; the recorded shutdown/destructor/uncatchability observations remain binding.** The ticks-SCC preservation clause does not reinstate whole-eval atomicity.

**3. Exact authorized change**

In [class_model_test.cljc:102](/home/sto/ci/dw-regfix/test/yang/php/antlr/class_model_test.cljc:102), inside `declare-and-inherit-test`, replace only the first assertion under:

```clojure
(testing "redeclaration, a missing or final parent, and a non-interface refuse"
```

Replace the duplicate-class `uncaught-message` assertion with:

```clojure
(let [message "Cannot declare class a, because the name is already in use"
      result (run-raw
               (seq-forms
                 (declare-form {:name "A"})
                 (declare-form {:name "a"})))]
  (is (= {:php/fatal message
          :php/status 255
          :php/uncaught nil}
         (select-keys result
                      [:php/fatal :php/status :php/uncaught])))
  (is (= [[64 message]]
         (mapv (juxt :errno :message) (:php/diag result)))))
```

This preserves the exact message assertion while checking the fatal classification, terminal status, and diagnostic category. Checking only that `uncaught-message` returns `nil` would be insufficient.

Keep the other assertions and `uncaught-message` helper unchanged. This authorization does not adjudicate the neighboring parent/interface error classifications or approve all changes in `053ae9a8`.

Engineer validation should run `declare-and-inherit-test`, the full class-model namespace, and the existing eval collision regressions—particularly repeated class-entry invocation and shutdown without guest unwinding—followed by the required regression gate. This consult supplies the contract decision and mechanical replacement, not a green-test certification.