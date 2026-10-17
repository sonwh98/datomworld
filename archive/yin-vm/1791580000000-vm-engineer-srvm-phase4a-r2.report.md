Completed-GMT: 2026-10-10 15:31:11 GMT
Completed-Local: 2026-10-10 22:31:11 Asia/Bangkok


# Slice 4a round 2 — test-only correction

Completed the three requested test changes and experimentally caught M6 and
M7. The evaluator is byte-for-byte identical to HEAD. JVM and Node pass;
Dart is NOT RUN because the compile attempt failed on sandbox permissions.

## Files changed

- `test/yin/vm/semantic_register/vm_test.cljc`: strengthened
  `return-writes-rd-once` with the specified nested closure program,
  exact windows and register numbers, then final value `[nil 2]`.
- `test/yin/vm/semantic_register/walker_trace_test.cljc`: added the pinned
  `:main-tail-primitive` row; trace-only row count is now 4.
- `test/yin/vm/semantic_register/parity_test.cljc`: added the separate
  slice-4a `:parameter-shadowing` extra row and `extra-row-values`, checking
  raw values, normalized values, machine-to-machine equality and halting.
- This new report.

Read Fable's findings in full and the amended evaluator §5.1 item 8.
No design, phase-3 file, or other test file was changed. All work stayed in
`/Users/sto/workspace/datomworld-srvm-p3`, branch `srvm-phase4a`. No staging,
commit, background test lane, or delegation. The only source writes were the
two expressly authorized temporary mutants, each restored in a `finally`
block before the next mutation or verification lane.

## Confirmed windows and numbers — FIRST run

The first completed run after the test edits was:

```sh
clojure -M:test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test
```

PASS, exit 0: **25 tests / 435 assertions; 0 failures / 0 errors**. No fixture
number or pinned expected trace was adjusted. The actual assertions confirmed:

```clojure
;; f = vector, g = identity, x = nil, y = 2
(:window frame) => {1 vector}
(:rd frame)     => 2
result-reg      => 0
(:window before) => {1 identity, 2 nil, 0 nil}
v               => nil
(:window after) => {1 vector, 2 nil}
(vm/value (vm/run after)) => [nil 2]
```

The callee's register 2 holds parameter z's value, nil; register 0 holds
body result v, also nil. This is exactly the review's `{1 g 2 z 0 v}` after
substituting g, z and v. The frame has a saved register, the destination
2 differs from body result 0, and the callee has three keys. The test asserts
these preconditions and the exact post-return map, preserving nil-valued keys.

The main-level tail call derives the same whole trace on both machines:

```clojure
[[:value :host-fn] [:value 1] [:value 2] [:value 3] [:halt 3]]
```

Walker: resolving + produces the host-fn event, the two literals each produce
one event, applying + produces 3 and halts; the trace then records halt 3.
Register: operator and literal destinations produce the first three events;
the main tail host call has empty K0, produces 3 directly into the halt value,
and `extract` reads `(:value after)` for that event. No layout `:halt` event
is added. `trace-oracle-pinned-values` checks the entire pinned trace on both
machines, and the first run and final JVM/Node runs passed it.

Parameter shadowing `((fn [x] ((fn [x] x) 2)) 1)` produces raw and normalized
value 2 on both machines. This is a separate 4a row; original eligible corpus
counts 25, 21, 5 and trace corpus count 51 remain unchanged.

## Experimental mutant results

Each mutant changed ONLY the non-empty-K `:return` arm at source line 132,
not the tail-host return-completion arm. Each invocation ran exactly:

```sh
clojure -M:test -n yin.vm.semantic-register.vm-test
```

Captured outputs are `/private/tmp/srvm-4a-r2-M6.log` and
`/private/tmp/srvm-4a-r2-M7.log`. The Python wrapper completed with exit 0
because it restores in `finally`; the test process's exit was **1** in each
case, explicitly printed and recorded below.

### M6 — caught

Temporary replacement:

```clojure
(assoc (:window frame) (:rd frame) value)
;; becomes
(assoc W (:rd frame) value)
```

**18 tests / 158 assertions, 2 failures / 1 error; test exit 1.** Both failures
were in `return-writes-rd-once`:

```text
FAIL in (return-writes-rd-once) (vm_test.cljc:167)
expected: (= (assoc (:window frame) (:rd frame) v) (:window after))

FAIL in (return-writes-rd-once) (vm_test.cljc:168)
expected: (= (:window frame) (dissoc (:window after) (:rd frame)))
```

The exact-window assertion expected `{1 vector, 2 nil}` and obtained
`{1 identity, 2 nil, 0 nil}` (function objects printed by the runner).
The final-result assertion also errored:

```text
expected: (= [nil 2] (vm/value (vm/run after)))
actual: clojure.lang.ArityException: Wrong number of args (2) passed to: clojure.core/identity
```

Thus the strengthened window assertion catches M6 directly; detection does
not depend on the later arity error. Source restored byte-for-byte afterward.

### M7 — caught

Temporary replacement:

```clojure
(assoc (:window frame) (:rd frame) value)
;; becomes
(assoc (:window frame) (:rd frame) value (nth inst 1) value)
```

**18 tests / 158 assertions, 2 failures / 0 errors; test exit 1.** The failures
were the same two assertions in `return-writes-rd-once`, lines 167 and 168:

```text
expected: (= (assoc (:window frame) (:rd frame) v) (:window after))
expected: (= (:window frame) (dissoc (:window after) (:rd frame)))
```

The exact-window assertion expected `{1 vector, 2 nil}` and obtained
`{1 vector, 2 nil, 0 nil}`. Its extra body-result key 0 is now observable.
Final value still passed; the exact shape is what catches this mutant.
Source restored byte-for-byte afterward. Neither mutant survived.

## Source restoration evidence

Both mutation wrappers printed:

```text
M6 source restored byte-for-byte: True
M7 source restored byte-for-byte: True
SHA256: 492e4831224e2765ec85e5e8370079e8875da7a259a181f0584df454e55ddf48
```

After all mutations and test lanes, compared the evaluator bytes with
`git show HEAD:src/cljc/yin/vm/semantic_register.cljc`:

```text
Evaluator matches HEAD byte-for-byte: True
SHA256: 492e4831224e2765ec85e5e8370079e8875da7a259a181f0584df454e55ddf48
git diff -- src/ exit: 0 output bytes: 0
```

`git diff --stat` listed only the three test paths: 48 insertions,
5 deletions. Generated `out/test.js` was removed after the Node lane.
Existing untracked collab artifacts were left alone.

## Final verification, restored evaluator

### JVM — PASS

```sh
clojure -M:test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test
```

Exit 0, **58 tests / 1568 assertions, 0 failures / 0 errors**. All seven
namespace `Testing` lines appeared. This comprises new/current slice-4a
25 tests / 435 assertions plus unchanged phase-3 33 / 1133.

Also independently ran:

```sh
clojure -M:test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test
```

Exit 0, **33 tests / 1133 assertions, 0 failures / 0 errors**.

### Node — PASS

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.semantic-register\\.(code-test|linearize-test|analysis-test|load-paths-test|vm-test|walker-trace-test|parity-test)$" :output-to "out/test.js" :autorun false}'
node out/test.js
```

Compile exit 0: **144 files / 5 compiled / 0 warnings**.
Node exit 0: **63 tests / 1628 assertions, 0 failures / 0 errors**.
The log printed `Testing` for all seven requested namespaces, plus
`yin.vm.parity-test`, imported for B0 corpus data. Its five tests / 60
assertions account for the difference from JVM's selected suite. Phase-3
33 / 1133 remains included and unchanged. No full suite run attempted.

### Dart — NOT RUN

Attempted once, foreground:

```sh
bb src/dev/cljd_agg.clj --only yin.vm.semantic-register.code-test,yin.vm.semantic-register.linearize-test,yin.vm.semantic-register.analysis-test,yin.vm.semantic-register.load-paths-test,yin.vm.semantic-register.vm-test,yin.vm.semantic-register.walker-trace-test,yin.vm.semantic-register.parity-test
```

Exit 1 at compilation, before tests; no Dart test/assertion summary. Exact
substantive errors:

```text
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.70370: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.70393: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.70416: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.70439: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
Execution error at cljd.compiler/mk-live-analyzer-info (compiler.cljc:214).
EOF while reading

Full report at:
/var/folders/n0/xl3_1xkn0yd5trpq843l81840000gn/T/clojure-3560115019492989193.edn
Exception in thread "Thread-0" java.lang.RuntimeException: Operation not permitted (sysctl failed)
cljd compile failed, exit 1
```

Stopped this lane. No escalation, inferred pass, or Dart mutation run.

### Checks — PASS

```sh
clj -M:kondo --lint test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/semantic_register/parity_test.cljc
cljstyle check test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/semantic_register/parity_test.cljc
rg -n '[\t ]+$' test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/semantic_register/parity_test.cljc
```

Kondo exit 0: **0 errors / 0 warnings**. Cljstyle exit 0, no output.
Whitespace scan: no matches, rg exit 1 (normal no-match outcome).
`cljstyle fix` was run only on these three test paths before the first run.

## Corrections to the prior report and remaining work

The prior acceptance-item-8 PASS did not demonstrate the intended return
window property; the old fixture was vacuous. This round supplies direct
mutation evidence for that property. The prior item-12 `[5 7 8 9 6]` check
reads static tuple destinations from the golden vector; it does not observe
runtime write order. Its runtime check observes the key set and arm values.
That test and the prior report were not edited in this narrowly scoped round.

No expected number or trace differed from the brief. No ambiguity required
a design or source change. No additional mutants (M16/M17 or the others)
were applied; their new rows pass on the unmodified machines, but no claim
of experimental killing of those additional mutants is made.

All requested test work and M6/M7 proof is complete. Dart verification remains
for the orchestrator outside this sandbox; independent review/sign-off and
committing remain with the orchestrator. Report timestamps use the observed
host clock, which is earlier than the task's supplied Created timestamps.
