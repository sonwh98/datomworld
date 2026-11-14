# Admission review remediation

Preserved tick events through progress rounds and removed the fabricated pending notice. The admission fixture now carries transport state across calls, returns actual successor values, and accumulates output; the end-to-end fixture preserves output and uses the session directly. Refusal tests pin the second-content-source reason.

Note on assertion change (`admission_test.cljc`, 509 -> 510 assertions): replaced `(is (identical? state state'))` with `(is (= state state'))` alongside `(is (= (:pending-run state) (:pending-run state')))` because across the decoupled shell composition boundary `yin.shell.linker/install` returns a fresh successor value rather than the input reference (the identity reference cannot cross the boundary, while value equality of engine state, pending run, and nil output still holds).

Verification on jupiter after the code edits:

- `clj -M:test -n yin.vm.linker.admission-test`:
  `Ran 38 tests containing 510 assertions.`
  `0 failures, 0 errors.`
- `DATOM_SLOW_TESTS=1 clj -M:test -n yin.vm.linker.admission-test`:
  `Ran 38 tests containing 510 assertions.`
  `0 failures, 0 errors.`
- `clj -M:test -n yin.vm.linker.dht-end-to-end-test`:
  `Ran 7 tests containing 137 assertions.`
  `0 failures, 0 errors.`
- `DATOM_SLOW_TESTS=1 clj -M:test -n yin.vm.linker.dht-end-to-end-test`:
  `Ran 7 tests containing 137 assertions.`
  `0 failures, 0 errors.`
- `clj -M:test -n yin.repl.dht-test -n yin.repl.dht-head-test -n yin.repl.shell-session-test -n dao.host.gate-test`:
  `Ran 85 tests containing 877 assertions.`
  `0 failures, 0 errors.`
- `rg -n 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc`: zero matches.
