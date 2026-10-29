# Slice 9 kernel differential gate — BLOCKED

This is a failing close-out gate, not a conformance certificate. No compiler or
VM source was changed. No commit was made.

The 54 fixtures in `kernel_gate.edn` invoke actual pinned upstream functions on A,
execute the explicit context source on host Clojure on B, and compile the guest
source for C-walker and C-semantic. Guest extension closures are evaluated by the
VM; the harness supplies no host callback primitive. The original proof dispatcher
is assembled, rather than substituting slice 8's restricted state-step dispatcher.
Every fixture, including every negative, has an A/B/C-walker/C-semantic outcome in
`slice9_evidence.edn`. Blocked fixtures are attempted and recorded independently;
no negative is skipped or counted as a successful guest rejection.

Result: **4 passing fixtures, 2 mismatches, 48 blocked fixtures**. Passing fixtures
are `:subst-capture`, `:subst-shadow`, `:subst-three-arity`, and
`:unparser-guest-closure`. The compile/admission blockers occur before either C
lane executes those blocked programs; both lane records explicitly say so.

## Mismatches with minimal repros

1. `:minimal-gensym-freshness`:

   ```clojure
   (let [a (gensym "fresh") b (gensym "fresh")] (= a b))
   ```

   A and B return `false`; both C lanes return `[:yin.k/ok true]`. The guest
   library's one-argument `gensym` constructs `G__fresh` repeatedly. This
   dependency is reachable from `latte-kernel.typing/rebuild-type`. No fresh-name
   correspondence can turn equality of two names into inequality.

2. `:free-vars-local-capture`:

   ```clojure
   (latte-kernel.syntax/free-vars
     (latte-kernel.defenv/register-definition env
       (latte-kernel.defenv/->Definition 'd [] 0 'x '#{x} 'A {}) true)
     '(d))
   ```

   A returns `#{x}`; B returns `#{}`; both C lanes return `[:yin.k/ok #{}]`.
   The upstream `free-vars` implementation uses `(second def-env)` to access
   local definitions. The inherited context adaptation passes a context map in
   that position. This is an A/B adaptation mismatch, independently of compilation.

## Blocked dependencies

- Parsing reaches `butlast`, `last`, bare `nth`, and `kind/var?`, which are not
  admitted by the supplied portable modules. Minimal independent fixtures for
  `last`, `nth`, and `var?` are included. The parser's valid and malformed cases
  all run on A/B, but their guest dependency closures fail admission.
- Normalization reaches `last` in `nbe/evaluation` and `with-meta` in
  `nbe/quotation-`. `:minimal-last` and `:minimal-with-meta` isolate these failures.
  Beta, transparent delta, opaque delta, and each required public normalization
  arity have named fixtures; no host normalization is substituted for C.
- Typing reaches the actual `typing/unfold-implicit` `try/catch`, including
  `.getMessage`, `ex-data`, and exception bindings. Slice 8's already-typed
  extension adapter does not replace this full typed-argument path. The two
  implicit fixtures use actual guest closure source and remain blocked.
- `typing/type-of-ascribe` is declared but has no definition in the pinned
  upstream source and no manifest entry. `:typing-ascription` also exposes the
  upstream unbound-function failure on A. It is never treated as a successful
  type rejection by C.
- Full proof elaboration/checking reaches `proof/+opacity-fn+`, an ambient atom
  initialized with a closure. Yang rejects this `def` initializer under the
  outcome convention. Each affected fixture records
  `:blocked-by [gate-proof-+opacity-fn+]` and the exact `:minimal-source`.
  The inherited partial proof-step context adapter cannot stand in for the real
  parsing/typing dispatcher. Proof compilation separately reaches the missing
  `string?` dependency; even `:compile-proof`'s single `:qed` script is explicitly
  blocked by this dependency rather than treated as a successful rejection.
- Upstream's error for steps after `qed` includes the host `clojure.core/meta`
  function in its `:meta` field. Evidence explicitly marks this nonportable host
  value; it is not normalized into an accepted portability difference. The
  malformed scalar proof-step fixture also records its native upstream exception.

The complete source dependency graph, per-fixture closure membership, missing
symbols and their owning definitions, compiler exception causes, and minimal
calls are retained in `slice9_evidence.edn`. Compiler/admission/VM exceptions are
blockers; only actual returned `:ko`, `false`, or corresponding domain exceptions
can satisfy negative fixtures. A test assertion checks that every completed A
negative really rejects its input.

## Manifest and coverage

The inherited manifest contains **155 bindings, 147 functions, and 175
function/arity entries**,
not the plan's earlier estimate of 143 definitions. It has no admitted/excluded
dispositions, and its source fields are nil. Consequently an exact admitted
function/arity denominator cannot be recovered honestly from that manifest.
`admitted-function-arity-count` is explicitly `:not-recorded-in-inherited-manifest`.
All 175 entries are retained as gate obligations; successful fixtures do not
narrow the denominator.

The conservative, proven direct A/B/C agreement count is **2/175 arities**:
`latte-kernel.syntax/subst` at arities 2 and 3. The successful unparser integration
also executes its dependencies, but this harness does not claim an exact dynamic
transitive arity count. It records `:transitive-execution-coverage :unproven`.
All **173 remaining unexercised-or-unproven entries** are individually listed
under `:coverage :unexercised`, alongside the complete function/arity inventory.
Dependency closure membership is recorded separately and does not count as
execution. This incomplete accounting itself blocks the close-out gate.

## Reproduction and verification

Pins: upstream LaTTe `86fca0d1d054af005cb37395eae04500d45e6279`; compiler/VM
`f74f2cecccfc54813671555b15e39d254debd2d9`; context adaptation v1, kernel assembly v1.
The recorded host uses Clojure 1.12.4 and Java 17.0.18. Each C execution has a
1,000,000-instruction budget. There is no continuation-depth claim for blocked
programs.

Run the gate:

```sh
clj -M:test -n yang.latte-oracle-test
```

**22 tests, 988 assertions, 1 failure, 0 errors, exit 1.** The single failed
assertion is `test-kernel-differential-gate`: the gate must remain red while the
above blockers or unproven coverage remain. The inherited 21 tests passed in the
baseline: 968 assertions, 0 failures, 0 errors. Do not remove the close-out
assertion to manufacture the requested zero-failure result.

Explicitly refresh evidence from fresh A/B/C runs (normal test runs do not rewrite
it):

```sh
clj -Sdeps '{:aliases {:gate {:main-opts ["-e" "(require (quote yang.latte-oracle-test)) (println (select-keys (yang.latte-oracle-test/write-kernel-gate-evidence!) [:gate-status :counts :fixture-count :manifest-bindings :function-arity-candidates]))"]}}}' -M:test:gate
```

This evidence-writing command returns normally even when the recorded gate is
blocked; only the test command above is the acceptance gate.

Unchanged regression suites:

```sh
clj -M:test -n yang.clojure-test -n yin.vm.macro-test -n yang.clojure.stream-eval-test -n yin.vm.store-write-audit-test
```

**Blocked at namespace loading**, `ClassNotFoundException:
yang.python.antlr.gen.Python3Lexer`; no green four-suite result. The stream/REPL
suite imports the frontend catalog that requires the generated Python parser.
The prerequisite attempt
`clj -M:antlr-gen --grammar python3 --target java` failed with
`java.nio.channels.UnresolvedAddressException` while retrieving required inputs.

The independent suites were then run separately:

```sh
clj -M:test -n yang.clojure-test -n yin.vm.macro-test -n yin.vm.store-write-audit-test
clj -M:test -n yin.vm.semantic.linearize-test -n yin.vm.semantic.analysis-test -n yin.vm.semantic.frame-test
```

Respectively **106 tests / 1,007 assertions** and **45 tests / 1,944 assertions**,
both with **0 failures and 0 errors**.

Cross-host regression availability is separate from the JVM kernel oracle:

- `bb test:changed:cljs --changed test/yang/latte_oracle_test.clj test/yang/latte_oracle/kernel_gate.edn`
  could not start: `bb` is absent (exit 127).
- Fallback `clj -M:cljs -m shadow.cljs.devtools.cli compile test` failed before any
  Node tests ran. `CompilerOptions` requires class-file version 65 (Java 21), but
  the active runtime supports version 61 (Java 17). Node itself is available.
- Dart/Flutter are absent from PATH. No Dart suite ran; that lane is unavailable,
  not green. Four-evaluator stream/REPL verification remains blocked as above.
- `git diff --check` passed.
