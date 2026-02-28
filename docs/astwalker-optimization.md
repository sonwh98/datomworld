# ASTWalkerVM Optimization

## Goal

Reduce CPU and allocation overhead in `ASTWalkerVM` without changing VM semantics.

Primary hot functions identified from JFR (post-optimization-1 baseline):

- `yin.vm.ast_walker.ASTWalkerVM.<init>` (record construction)
- `yin.vm.ast_walker.ASTWalkerVM.valAt` (destructuring/field access)
- `yin.vm.ast_walker$cesk_transition.invokeStatic` (CESK case dispatch)

## Completed: Optimization 1 — cesk-return (single-allocation record construction)

Files: `src/cljc/yin/vm/ast_walker.cljc`

Changes:

- Added `cesk-return` helper that constructs `ASTWalkerVM` via the positional `->ASTWalkerVM` constructor instead of chained `assoc` calls. Each CESK transition now creates exactly 1 record instead of 2-5 intermediates.
- Folded `:halted` derivation into `cesk-return` to eliminate the extra `assoc` in `vm-step`.
- Refactored `apply-function` to take `env` as a parameter, eliminating one intermediate record per function application.
- Simplified `vm-step` to a direct call to `cesk-transition`.

Effect:

- Fast-path mean at `n=100`: `1,343 us -> 905 us` (1.48x faster).
- Gap vs Register VM narrowed from 5.5x to 3.8x.
- ASTWalkerVM allocation samples: `2,542 -> 1,559` (now irreducible at 1 per step).

## Current Target: Optimization 2 — mutable hot loop

### Baseline (post-optimization-1)

Workload: tail-recursive countdown

- `clj -M:bench --ast-walker --fast-only 100`
  - Register: `237 us`, Stack: `389 us`, AST Walker: `905 us`
- `clj -M:bench --ast-walker-only --fast-only 10000`
  - AST Walker: `87.8 ms`
- `clj -M:profile-ast-walker 50000`
  - AST Walker slow path: `404 ms`, fast path: `387 ms`

Baseline JFR allocation attribution (`jdk.ObjectAllocationSample`, yin.vm-attributed, `n=50000`):

- `ASTWalkerVM` record: `1,559` samples (69.3%) — 1 per CESK step, irreducible without architecture change
- `Object[]` (continuation map construction): `439` samples (19.5%)
- `ArraySeq` (rest-arg sequences): `132` samples (5.9%)
- `PersistentArrayMap` (continuation maps): `63` samples (2.8%)
- `Long` boxing: `41` samples (1.8%)

Baseline JFR CPU attribution (`jdk.ExecutionSample`, yin-attributed, `n=50000`):

- `cesk_transition:118` (destructuring `{:keys [...]}`): `221` samples (21.8%)
- `ASTWalkerVM.valAt` (keyword field lookup): `148` samples (14.6%)
- `ASTWalkerVM.<init>` (cesk-return constructor): `93` samples (9.2%)
- `cesk_transition:122` (case cont-type dispatch): `45` samples (4.4%)
- `cesk_transition:137` (frame access): `42` samples (4.1%)
- `apply_function:103` (merge+zipmap): `22` samples (2.2%)

### Technique

The same staged approach as the Register VM hot loop (`reg-vm-run-active-continuation` in `register.cljc`):

1. Keep interpreter semantics stable, optimize representation in hot path.
2. Preserve explicit stream/effect boundaries by falling back to the immutable scheduler layer for effect-heavy operations.
3. `IVMStep/step` still uses `cesk-transition` (debug/single-step semantics unchanged).

### Design

Create `ast-walker-run-active-continuation`: a `loop/recur` hot loop that keeps CESK state in JVM locals instead of an immutable record.

**Loop bindings** (5 locals):

```
control  — current AST node or nil
env      — lexical environment map
cont     — continuation (linked list of maps)
val      — last computed value
vm       — ASTWalkerVM record (carried for slow-changing fields: store, primitives, id-counter, parked, run-queue, wait-set)
```

**Common CESK transitions** (inlined as direct `recur`, zero ASTWalkerVM allocation):

These cover the entire tail-countdown hot loop (22 steps/iteration, all handled inline):

```
:literal     → (recur nil env cont (:value node) vm)
:variable    → (recur nil env cont (resolve-var env (:store vm) (:primitives vm) name) vm)
:lambda      → (recur nil env cont {:type :closure ...} vm)
:application → (recur (:operator node) env {:frame node :parent cont :environment env :type :eval-operator} val vm)
:if          → (recur (:test node) env {:frame node :parent cont :environment env :type :eval-test} val vm)

:eval-operator (has operands)  → (recur (first operands) saved-env (assoc cont :type :eval-operand :frame updated-frame) nil vm)
:eval-operator (arity-0)       → inline apply
:eval-operand  (more operands) → (recur next-node saved-env (assoc cont :frame updated-frame) nil vm)
:eval-operand  (all done)      → inline apply
:eval-test                     → (recur branch saved-env (:parent cont) test-value vm)

primitive apply (no effect) → (recur nil env (:parent cont) result vm)
closure apply               → (recur body (merge closure-env (zipmap params args)) (:parent cont) val vm)
```

**Uncommon transitions** (fallback to record-based path):

For store ops, gensym, park/resume, stream ops, effects: materialize the record via `cesk-return`, delegate to existing `cesk-transition`, extract fields back into loop locals:

```clojure
(let [state (cesk-return vm control env cont val)
      next  (cesk-transition state nil)]
  (recur (:control next) (:environment next)
         (:continuation next) (:value next) next))
```

The returned `next` record becomes the new `vm` loop binding, carrying any updated slow fields (store, run-queue, etc.).

**Primitive apply with effect** (uncommon): construct record, call existing `apply-function`, extract fields:

```clojure
(let [state (cesk-return vm control env cont val)
      next  (apply-function state fn-val args parent-cont saved-env)]
  (recur (:control next) (:environment next)
         (:continuation next) (:value next) next))
```

**Scheduler integration** (at loop exit):

When the CESK loop exits (halted or blocked), the same `loop/recur` handles scheduler transitions without nesting:

```clojure
;; Loop exit condition: not (and (not blocked) (or control cont))
(let [result (cesk-return vm control env cont val)]
  (cond
    (:blocked result)
    (let [v' (check-wait-set result)]
      (if-let [resumed (resume-from-run-queue v')]
        (recur (:control resumed) (:environment resumed)
               (:continuation resumed) (:value resumed) resumed)
        v'))

    (seq (or (:run-queue result) []))
    (if-let [resumed (resume-from-run-queue result)]
      (recur (:control resumed) (:environment resumed)
             (:continuation resumed) (:value resumed) resumed)
      result)

    :else result))
```

**Entry dispatcher** (mirrors `reg-vm-run`):

```clojure
(defn- ast-walker-run
  [vm]
  (if (or (:blocked vm)
          (:halted vm)
          (seq (:run-queue vm))
          (seq (:wait-set vm))
          (nil? (:control vm)))
    (ast-walker-run-scheduler vm)
    (ast-walker-run-active-continuation vm)))
```

- `ast-walker-run-scheduler`: existing `engine/run-loop` with `vm-step` (slow path, for scheduler/effect situations)
- `ast-walker-run-active-continuation`: new hot loop (fast path)

**vm-eval wiring:**

```clojure
(defn- vm-eval [vm ast]
  (let [v (if ast (vm-load-program vm ast) vm)]
    (ast-walker-run v)))
```

### Changes

Files: `src/cljc/yin/vm/ast_walker.cljc`

1. Add `ast-walker-run-active-continuation` (hot loop with `loop/recur`, inlined common CESK transitions, fallback for uncommon ops).
2. Add `ast-walker-run-scheduler` (thin wrapper over `engine/run-loop` with `vm-step`).
3. Add `ast-walker-run` (dispatcher: fast vs slow based on VM state).
4. Update `vm-eval` to call `ast-walker-run` instead of `engine/run-loop`.
5. Keep `cesk-transition`, `apply-function`, `vm-step`, `cesk-return` unchanged (step-by-step API preserved).

No other files change. The benchmark harness already supports `--ast-walker-only` and `--fast-only`.

### Verification

1. `clj -M:test` — all 213 JVM tests pass
2. `npx shadow-cljs compile test && node target/node-tests.js` — all 154 CLJS tests pass
3. `clj -M:bench --ast-walker --fast-only 100` — compare fast-path timing to baseline (`905 us`)
4. `clj -M:bench --ast-walker-only 100` — verify slow path (step-by-step benchmark) still works and shows same or slightly worse timing than fast path
5. `clj -M:profile-ast-walker 50000` — verify ASTWalkerVM allocation samples drop from `1,559` to near zero in hot loop

### Expected outcome

- Common-path CESK transitions produce zero `ASTWalkerVM` allocations (only continuation maps remain).
- Destructuring overhead eliminated (fields are already in loop locals).
- One `ASTWalkerVM` allocation at loop exit (via `cesk-return`).
- Remaining allocation pressure shifts to continuation map construction (`Object[]`, `PersistentArrayMap`) and closure env `merge`+`zipmap` — candidates for a future optimization pass.

## How to Reproduce

Baseline:

```bash
clj -M:bench --ast-walker --fast-only 100
clj -M:bench --ast-walker-only --fast-only 10000
clj -M:profile-ast-walker 50000
jfr print --events jdk.ObjectAllocationSample /tmp/datomworld-ast-walker.jfr > /tmp/jfr-alloc.txt
jfr print --events jdk.ExecutionSample /tmp/datomworld-ast-walker.jfr > /tmp/jfr-cpu.txt
```

Step counts (for verifying correctness at `n=100`, 22 steps per iteration):

```
:no-control   1,007  (10 per iteration: continuation returns)
:variable       602  (6 per iteration: <, n, self, self, -, n)
:application    302  (3 per iteration: (< n 1), (self self ...), (- n 1))
:literal        203  (2 per iteration: the two 1 constants)
:if             101  (1 per iteration: conditional check)
:lambda           2  (initial setup only)
```
