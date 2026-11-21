# Dyn continuation cold-start investigation — incomplete, no fix

Code base: `156de2ae68ab8b953e7970cceac677327e7eb251` (origin/php-land-staging). No production or fixture changes. Do not land this as a fix.

A focused cold-JVM run of `:dyn-shares-locals` reproduces `Corrupt or tampered continuation payload {:rule :continuation-registers}`. Instrumenting `yin.vm/machine-data?` showed the actual rejected leaf is the host function `yin.vm.module/set-cell!`, not a missing registry or a validation initialization state. The failing register value's path is `[:host-payload [:frames :value] 9 0 :host-payload [:frames :value] 3 0 :host-payload [:continuation :value] 9 [:regs :value] 0 1]`; `:host-payload` unwraps a kernel-minted closure/continuation and `[:key :value]` denotes the corresponding map value. Register closures capture lexical `:frames` in `yin.vm.debruijn.register/step1`; an embedded captured continuation retains the primitive in a register. `continuation-defect` rejects it using `vm/machine-data?` as the existing contract requires. This identifies the immediate failure but does not establish why the reported subsystem ordering differs, nor whether that primitive is semantically live.

No validator exemption or frame pruning was applied: accepting host functions contradicts the contract, and deleting the value without proving it unused could alter resumed execution. A correct resolution needs a proven portable representation or a semantics-preserving capture/liveness change. The task's warm-vs-cold claim and fixture initialization hypothesis remain unproven here.

Verification so far, before any fix:

- Fresh generated parser prerequisite: `clojure -M:antlr-gen`, rc 0.
- Focused cold dyn fixture, instrumented probe, rc 0 (probe catches exception): throws the corruption exception above. It is not a passing test.
- `clojure -M:test -n yin.vm.provenance-test -n yang.call.python-exception-test`, rc 0; Ran 26 tests containing 273 assertions, 0 failures, 0 errors.
- `clojure -M:test -n yin.vm.debruijn-register-effects-test`, rc 0; Ran 12 tests containing 148 assertions, 0 failures, 0 errors. Existing mutation matrix exercises continuation defects.
- `bb test:sub:clj dao.stream` (Babashka 1.13.225 on PATH), rc 0; clj lane 41 namespaces, 589 tests, 0 failures.
- A separate fresh JVM first requiring `yang.php.antlr.e2e-test` and then running the same instrumented fixture, rc 0 (probe catches exception): identical `:continuation-registers` exception and identical path to `set-cell!`. Requiring that namespace alone does not reproduce the reported green subsystem behavior; executing the full preceding subsystem tests was not attempted.
- Full isolated `clojure -M:test -n yang.php.antlr.include-test` was interrupted (rc 130, no Ran line): inactive mutual include printed `ab` for all three backends; the entire namespace did not finish. The full run after requiring `yang.php.antlr.e2e-test` was likewise interrupted (rc 130, no Ran line). There were no post-fix isolated runs because no fix was made.

The diff consists only of this findings file. The code head tested is `156de2ae68ab8b953e7970cceac677327e7eb251`; the subsequent findings-only commit carries no code change. The requested two successful isolated runs, full warm run, and full root-cause explanation remain outstanding.
