Created-GMT: 2026-11-15T20:37:25Z
Coding-Agent: codex (terra, laptop lane)
Branch: fix/include-ticks-scc-r2

# Include ticks SCC r2 findings

Status: **STOP: gate 5 is red.** The Dart PHP subsystem test
`yang.php.antlr.class-model-test/declare-and-inherit-test` fails in isolation
as well as in the subsystem run. Its class redeclaration assertion expects
`Cannot declare class a, because the name is already in use` but receives nil.
This test does not exercise the changed ticks stage. The JVM reproduction at
master `da3cb74c` has the identical failure (1 test, 7 assertions, 1 failure),
so that failure predates this branch; the Dart baseline was not tested. Escalate
the second stop to the owner per dispatch; do not merge as green.

Implementation: no linker change. Empty selections add no hook wrapper or
pin, but still derive dependencies and republish when their pins change. For
selected hooks, trailing `php.sp` placement is guarded by the original and
instrumented module shape: a wide application of literal/closure definitions
followed by `require php`. Other shapes retain the leading hook require. The
derived manifest pins the hook when selected and the ledger output is the
published final tree.

## Gates

1. `yang.php.antlr.include-test/mutual-include-ticks-on-every-vm-test`,
   inactive `:inc-mutual-once`, on implementation 49f70eed: semantic,
   stack, and register each returned `["ab" [] nil]`; zero sites.
2. Same test, active ticks in both members, on c77a047a: six sites; semantic,
   stack, and register each returned `["ab" [] nil]`. All three results and six
   sites passed again at 49f70eed with the final guard requiring the
   instrumented tree to retain the deferred shape (14 assertions, zero
   failures). `cyclic-module-initialization-does-not-run-bodies-test` at
   6a9cf6f9: semantic, stack, and register each returned the SCC module symbol;
   7 assertions, zero failures. The shape guard verifies that the instrumented
   initialization contains only literal/closure definitions and the PHP
   require before the trailing hook require. At 610f5f78,
   `cyclic-init-with-registered-tick-handler-test` registers a guest handler,
   requires the bundle and emits a marker: semantic, stack and register each
   returned `["M" [] nil]` (5 assertions, zero failures), showing no guest
   body output or tick-handler output during that initialization.
3. `yang.php.antlr.include-test/publication-order-test`: 6 assertions, zero
   failures, including `b < a < main` and one cyclic publication step. The
   initialization test checks both aliases pin the bundle; emitter specs put
   the bundle before the aliases.
4. `yin.vm.linker-test/{a-use-before-every-definition-is-use-before-definition,a-conditional-definition-discharges-nothing,a-definition-in-a-lambda-body-discharges-nothing,an-application-before-the-definition-retains-the-obligation}`:
   4 tests, 20 assertions, zero failures. Linker discharge is unchanged.
5. `yang.php.antlr.eval-bridge-test`: 2 tests, 9 assertions, zero failures.
   PHP subsystem tri-host run at 6a9cf6f9: Node exit 0 but runner reported
   no counts; Dart 46 tests, 1 failure as above; JVM could not start because
   the fresh checkout lacked the TypeScript parser. The Java parsers were
   then generated; a separate JVM subsystem run encountered the same class
   assertion and was stopped to reproduce that test alone. The isolated JVM
   reproduction failed (1 test, 7 assertions, 1 failure).
   The complete slow include namespace has not been run.

Other focused checks: `yang.php.antlr.siteset-test/trailing-hook-requires-deferred-initialization`
passed 3 assertions; `stage-publishes-derived-closure-and-record` at 49f70eed
passed 6 assertions, including derived child pin and no local hook pin.

## Log custody

- First remote setup attempt, mise trust failure:
  `/home/sto/ci/logs/include-fix-r2-yang-php-20261115-151439/`.
- Initial subsystem attempt, PHP parser missing, Node exit 0, Dart failure:
  `/home/sto/ci/logs/include-fix-r2-yang-php-20261115-151516/`.
- Isolated Dart reproduction (exit 1):
  `/home/sto/ci/logs/include-fix-r2-dart-repro-20261115-151822/`.
- Superseded remote focused attempt (terminated after inactive results):
  `/home/sto/ci/logs/include-fix-r2-focused-20261115-152008/`.
- Subsystem at 6a9cf6f9, TypeScript parser missing in JVM:
  `/home/sto/ci/logs/include-fix-r2-yang-php-final-20261115-152940/`.
- Java parser generation and JVM lane (terminated after the failure):
  `/home/sto/ci/logs/include-fix-r2-jvm-20261115-153222/`.
- Isolated JVM reproduction:
  `/home/sto/ci/logs/include-fix-r2-jvm-class-repro-20261115-154113/`.
- Isolated JVM baseline at `da3cb74c`:
  `/home/sto/ci/logs/include-fix-r2-baseline-class-20261115-154220/`.

Each subsystem attempt banked its own `sub-*.log` before another attempt.
