# Admission shell composition findings

The admission fixture retained the pre-refactor engine-owned transport and drove the engine without the shell linker. The shell tick also stopped after one serving round, leaving answer-only progress and bounded continuation unfinished. The end-to-end linker fixture likewise opened a DHT through the engine. The admission fixture now drives session serving and keeps its node outside the engine calls; the end-to-end fixture opens through the shell composition. Session ticks carry each successor through bounded serving and resume rounds.

Modified files: `src/cljc/yin/shell/session.cljc`, `test/yin/vm/linker/admission_test.cljc`, `test/yin/vm/linker/dht_end_to_end_test.cljc`, and the pre-existing `test/yin/repl/dht_test.cljc` change.

Commit SHA: unavailable. `git add` failed: `fatal: Unable to create '/home/sto/ci/datomworld-master/.git/worktrees/fix-master-dht/index.lock': Read-only file system`.

## `clj -M:test -n yin.vm.linker.admission-test`

```
mise WARN  tracking config: failed to ln -sf /home/sto/work/fix-master-dht/mise.toml /home/sto/.local/state/mise/tracked-configs/43ecb662b32b10ce: Read-only file system (os error 30)
Loading initial Timbre config from: :default

Running tests in #{"test"}
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load

Testing yin.vm.linker.admission-test

Ran 38 tests containing 509 assertions.
0 failures, 0 errors.
```

## `DATOM_SLOW_TESTS=1 clj -M:test -n yin.vm.linker.admission-test`

```
mise WARN  tracking config: failed to ln -sf /home/sto/work/fix-master-dht/mise.toml /home/sto/.local/state/mise/tracked-configs/43ecb662b32b10ce: Read-only file system (os error 30)
[0.001s][warning][perf,memops] Cannot use file /tmp/hsperfdata_sto/3 because it is locked by another process (errno = 11)
Loading initial Timbre config from: :default

Running tests in #{"test"}
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load

Testing yin.vm.linker.admission-test

Ran 38 tests containing 509 assertions.
0 failures, 0 errors.
```

## `clj -M:test -n dao.host.gate-test -n yin.repl.dht-test -n yin.repl.dht-head-test -n yin.repl.shell-session-test`

```
mise WARN  tracking config: failed to ln -sf /home/sto/work/fix-master-dht/mise.toml /home/sto/.local/state/mise/tracked-configs/43ecb662b32b10ce: Read-only file system (os error 30)
[0.002s][warning][perf,memops] Cannot use file /tmp/hsperfdata_sto/3 because it is locked by another process (errno = 11)
Loading initial Timbre config from: :default

Running tests in #{"test"}
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load

Testing dao.host.gate-test

Testing yin.repl.dht-head-test

Testing yin.repl.dht-test

Testing yin.repl.shell-session-test

Ran 85 tests containing 877 assertions.
0 failures, 0 errors.
```

## `clj -M:test -n yin.vm.linker-require-test -n yin.vm.linker.dht-test -n yin.vm.linker.dht-end-to-end-test -n yin.vm.linker.publish-test -n yin.vm.linker.head-test`

```
mise WARN  tracking config: failed to ln -sf /home/sto/work/fix-master-dht/mise.toml /home/sto/.local/state/mise/tracked-configs/43ecb662b32b10ce: Read-only file system (os error 30)
[0.001s][warning][perf,memops] Cannot use file /tmp/hsperfdata_sto/3 because it is locked by another process (errno = 11)
Loading initial Timbre config from: :default

Running tests in #{"test"}
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load

Testing yin.vm.linker.dht-end-to-end-test

Testing yin.vm.linker.dht-test

Testing yin.vm.linker.head-test

Testing yin.vm.linker.publish-test

Testing yin.vm.linker-require-test

Ran 91 tests containing 1564 assertions.
0 failures, 0 errors.
```

## Portability search

`rg -n 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc` produced no output and exited 1.
