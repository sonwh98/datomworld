Created-GMT: 2026-09-16 05:37:56 GMT
Created-Local: 2026-09-16 12:37:56 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 45bb2898-e527-4880-a59d-9a95e77a45d2
# Task: Fix zipmap-based param binding to nil-fill missing params (§7.7.2)
Role: VM Runtime
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-16 12:37:56 +07 | Status: complete

## Summary

Added `engine/bind-params` and used it at all four call sites named in the
prompt, replacing the bare `zipmap params args` / `zipmap params []` calls.
Per §7.7.2, an under-arity call now binds every declared param name, filling
any name with no corresponding arg with `nil`, instead of leaving that name
absent from the binding map (which previously let it fall through to
`closure-env`, and from there potentially further).

## Helper added

`src/cljc/yin/vm/engine.cljc` (placed just above `resolve-var`):

```clojure
(defn bind-params
  "Zips params with args, nil-filling any params beyond args' length.
   Extra args beyond params' length are dropped. §7.7.2: an under-arity
   call leaves missing parameter names bound to nil, not absent."
  [params args]
  (into {} (map vector params (concat args (repeat nil)))))
```

`(map vector params (concat args (repeat nil)))` pairs to the length of
`params` (the shorter of the two `map` inputs, since `(concat args (repeat
nil))` is infinite), so both properties fall out of the same expression: a
param past `args`' length pairs with `nil` from the infinite padding, and
extra `args` past `params`' length are simply never visited.

## Diff at the four call sites

```diff
--- a/src/cljc/yin/vm/ast_walker.cljc
+++ b/src/cljc/yin/vm/ast_walker.cljc
@@ apply-function (line ~191)
         (= :closure (:type fn-value))
         (let [{:keys [params body], closure-env :env} fn-value
-              extended-env (merge closure-env (zipmap params evaluated-operands))]
+              extended-env (merge closure-env
+                                  (engine/bind-params params evaluated-operands))]
           (cesk-return state body extended-env k (:value state)))

@@ hot loop, :eval-operator empty-operands branch (line ~511)
                 (cond (= :closure (:type fn-value))
                       (let [{:keys [params body], closure-env :env} fn-value
-                            extended-env (merge closure-env (zipmap params []))]
+                            extended-env (merge closure-env
+                                                (engine/bind-params params []))]
                         (recur body extended-env (:next k) val vm))

@@ hot loop, :eval-operand full-evaluation branch (line ~553)
                   (cond (= :closure (:type fn-value))
                         (let [{:keys [params body], closure-env :env} fn-value
                               extended-env (merge closure-env
-                                                  (zipmap params evaluated))]
+                                                  (engine/bind-params params
+                                                                     evaluated))]
                           (recur body extended-env (:next k) val vm))

--- a/src/cljc/yin/vm/semantic.cljc
+++ b/src/cljc/yin/vm/semantic.cljc
@@ apply-call closure branch (line ~200)
     (= :closure (:type f))
-    (let [E' (merge (:env f) (zipmap (:params f) args))
+    (let [E' (merge (:env f) (engine/bind-params (:params f) args))
           frame {:type :return, :segment seg, :pc (inc pc), :env E,
                  :stack-base (count St)}
```

No other lines at these sites changed. `semantic.cljc`'s `:var` opcode
(`resolve-var` call) was not touched, and `:variable`'s resolution logic in
`ast_walker.cljc` was not touched anywhere.

## Tests added

`test/yin/vm/engine_test.cljc` — direct unit test of the helper:
- `bind-params-nil-fills-missing-args-test`: under-arity (`[x y]` / `[1]` →
  `{x 1, y nil}`), zero-arg (`[x y]` / `[]` → all nil), over-arity truncation
  (`[x]` / `[1 2 3]` → `{x 1}`), and exact-arity pass-through.

`test/yin/vm/ast_walker_test.cljc` — behavior through the AST walker via
`compile-and-run`, which runs through `vm/run` → `ffi/maybe-run` →
`ast-walker-run-scheduler` → `engine/run-loop` with `vm-step` = `cesk-transition`
(this is `apply-function`'s call path, both the empty-operands and the
full-evaluation cases in `cesk-transition`'s `:eval-operator`/`:eval-operand`
arms):
- `under-arity-call-binds-missing-param-to-nil-test`: nested lambdas where
  the outer binds `y` to `:outer-y` and the inner (`[x y]`) is called with
  only one argument; asserts the inner body's `y` comes back `nil`, not
  `:outer-y` — this is exactly the fallthrough §7.7.2 forbids.
- `under-arity-zero-args-call-binds-param-to-nil-test`: a one-param closure
  called with zero arguments; exercises `apply-function`'s zero-operand path.
- `over-arity-call-drops-extra-args-test`: a one-param closure called with
  two arguments; asserts the extra argument is dropped (value is still the
  first arg), confirming no regression in existing truncation behavior.

**Verification the fix is load-bearing:** I temporarily reverted just the
`apply-function` site back to bare `zipmap`, reran
`yin.vm.ast-walker-test`, and both new under-arity tests failed exactly as
the bug predicts, then restored the fix and confirmed green again:
- `under-arity-call-binds-missing-param-to-nil-test` failed with
  `actual: (not (nil? :outer-y))` — the missing `y` fell through
  `closure-env` to the outer closure's `y`, the exact fallthrough §7.7.2
  forbids.
- `under-arity-zero-args-call-binds-param-to-nil-test` failed with
  `ExceptionInfo: Unable to resolve symbol: x in this context` — with no
  params bound at all under `(zipmap params [])`, `x` fell all the way
  through `env` → `store` → `primitives` → `modules` and was unresolvable.

**A note on the hot loop (ast-walker.cljc:511/553):** while auditing which
code path `compile-and-run` exercises, I found that
`ast-walker-run-active-continuation` (the "hot trampolined loop" the prompt
names, defined at `ast_walker.cljc:488`) is not currently called from
anywhere — `ast-walker-run-scheduler` drives `engine/run-loop` with `vm-step`
(`cesk-transition`) as its step function, not the hot-loop function. I still
applied the `bind-params` fix there per the prompt's explicit instruction
(and for source-level consistency with the now-fixed `apply-function`/
`apply-call` sites, since the same bug exists in that dead code verbatim),
but no test — mine or pre-existing — currently exercises those two branches,
because nothing currently calls into that function. Flagging this instead of
quietly asserting coverage I don't have.

`test/yin/vm/semantic_test.cljc` — same two properties through the
semantic VM's hand-assembled segments:
- `under-arity-call-binds-missing-param-to-nil-test`: outer closure binds
  `y` to `10`; inner closure (`[x y]`) is tail-called with only one pushed
  argument; asserts the inner body's `var y` comes back `nil`, not `10`.
- `over-arity-call-drops-extra-args-test`: a one-param closure called with
  two pushed arguments (`argc 2`); asserts the result is the first argument
  (`5`), not an error and not silently accepting the second.

## Test results

`clj -X:test :nses '[yin.vm.ast-walker-test yin.vm.engine-test yin.vm.semantic-test yin.vm-test]'`:

```
Testing yin.vm.ast-walker-test
Testing yin.vm.engine-test
Testing yin.vm.semantic-test
Testing yin.vm-test

Ran 84 tests containing 420 assertions.
0 failures, 0 errors.
```

Also ran the full CLJS suite (`npx shadow-cljs compile test && node
target/node-tests.js`) since the changed namespaces are `.cljc`:

```
Ran 1370 tests containing 35530 assertions.
2 failures, 0 errors.
```

Both failures are the same pre-existing, unrelated case, run twice under
`doseq [vm-type [:semantic :ast-walker]]` in
`yin/repl_core_test.cljc:113` (`a-failed-input-is-consumed-exactly-once`):
the test evaluates `(/ 1 0)` expecting an `"Error: "` message, but under
ClojureScript's JS-number semantics `1/0` evaluates to `##Inf` rather than
throwing — a numeric-division-by-zero platform difference between JVM
Clojure (throws `ArithmeticException`) and JS (produces `Infinity`), nothing
to do with parameter binding, `zipmap`, closures, or any of the four changed
call sites. No test in `yin.vm.*` or `yin.vm-test` failed under CLJS.

## No under-arity fallthrough tests needed updating

I searched the existing walker/engine/semantic suites (and `yin.vm-test`)
for any test that relied on the old truncation-fallthrough behavior (an
under-arity call resolving a missing param through `closure-env` or further).
None exist: the pre-existing corpus only exercises closures at exact arity
(`lambda-test`, `closure-call-test`, the `worked-segment` fixture, etc.), so
no existing assertion needed to change — every failure/pass above is from
newly added tests, not a contract-change fixup.

## Confirmation of boundaries respected

- No `:global` arm added anywhere; grepped the diff and the full
  `ast_walker.cljc`/`semantic.cljc` for `:global` — none present.
- `:variable`'s resolution in `ast_walker.cljc` (`engine/resolve-var env
  store primitives modules (:name node))`) is byte-for-byte unchanged, at
  both the `cesk-transition` site and the hot-loop site.
- `semantic.cljc`'s `:var` opcode (case `2`, `resolve-var` call) is
  unchanged.
- Only `ast_walker.cljc`, `engine.cljc`, `semantic.cljc`, and their three
  test files were touched. `linearize.cljc` and `code.cljc` were not
  touched.
- Nothing staged or committed.
