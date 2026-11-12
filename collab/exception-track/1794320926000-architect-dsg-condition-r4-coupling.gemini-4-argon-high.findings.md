## Ruling: **(B) Load-bearing** (as a provenance distinction between retained protocol conditions and guest-thrown condition-shaped data; **defective in `98519957`** because `compile-program` leaves stale `:tail? true` marks on the operand of `strip-top-outcome`, causing `:semantic` to tail-skip the top-level stripper, and because literal `:dsg__condition true` is guest-forgeable under R5)

---

### 1. Complete Inventory of `:dsg__condition` on `exception-r3-impl` (`98519957`)

`git grep -n 'dsg__condition' 98519957` finds **11 lines across 9 sites**, all in [`src/cljc/yang/clojure.cljc`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc) (`0` occurrences on `master` @ `8cd9fc92`, `0` occurrences in `test/`):

#### A. 7 Writer Sites (all in [`src/cljc/yang/clojure.cljc`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc) at `98519957`)

1. **[`src/cljc/yang/clojure.cljc:1543-1544`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1543-L1544)** — [`condition->outcome`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1530-L1549) inside [`compile-guest-call`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1517-L1658) (consequent branch when `reason` is one of `:yang.call/{thrown, not-callable, invalid-invocation, unsupported-binding}`):
   ```clojure
   (outcome-node error-tag
                 (u/app (u/v 'dsg__assoc) (u/v condition) (u/lit :dsg__condition) (u/lit true)))
   ```
2. **[`src/cljc/yang/clojure.cljc:1546-1548`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1546-L1548)** — [`condition->outcome`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1530-L1549) inside [`compile-guest-call`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1517-L1658) (alternate branch when `condition` has no recognized `:yang.call/reason`):
   ```clojure
   (outcome-node error-tag
                 (u/app (u/v 'dsg__assoc)
                        (u/app (u/v 'yang.call/thrown) (u/v condition))
                        (u/lit :dsg__condition) (u/lit true)))
   ```
3. **[`src/cljc/yang/clojure.cljc:1560-1567`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1560-L1567)** — `refused` inside [`compile-guest-call`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1517-L1658) (when `(dsg__admitted? callee)` is false):
   stamps `(u/lit :dsg__condition) (u/lit true)` onto `(yang.call/condition :yang.call/not-callable {:yang.call/callee callee})`.
4. **[`src/cljc/yang/clojure.cljc:1580-1586`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1580-L1586)** — `activation` inside [`compile-guest-call`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1517-L1658) (when `escape` fails `:clj/escape-token` check):
   stamps `(u/lit :dsg__condition) (u/lit true)` onto `(yang.call/condition :yang.call/invalid-invocation {:yang.call/reason :missing-raise-exit})`.
5. **[`src/cljc/yang/clojure.cljc:1630-1636`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1630-L1636)** — `value-call` inside [`compile-guest-call`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1517-L1658) (when `dsg__raise_escape` fails `:clj/escape-token` check):
   stamps `(u/lit :dsg__condition) (u/lit true)` onto `(yang.call/condition :yang.call/invalid-invocation {:yang.call/reason :missing-raise-exit})`.
6. **[`src/cljc/yang/clojure.cljc:2612-2617`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2612-L2617)** — `dsg__intra_call` inside [`compile-program`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2398-L2678) (non-map `dsg__callee` branch):
   ```clojure
   '(conj (conj [] :yin.k/error)
          (dsg__assoc (yang.call/condition :yang.call/not-callable
                                           (dsg__assoc {} :yang.call/callee dsg__callee))
                      :dsg__condition true))
   ```
7. **[`src/cljc/yang/clojure.cljc:2794-2795`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2794-L2795)** — `invoke-value` in [`library`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2680-L2900) (`:else` branch for non-callable `f`):
   ```clojure
   :else
   (throw (assoc (yang.call/condition :yang.call/not-callable {:yang.call/callee f})
                 :dsg__condition true))
   ```

#### B. 2 Reader + Stripper Sites (4 grep lines, all in [`src/cljc/yang/clojure.cljc`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc) at `98519957`)

1. **[`src/cljc/yang/clojure.cljc:1309-1317`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1309-L1317) (lines `:1313` and `:1315`)** — `entry-body` tail inside [`call-entry`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1239-L1331):
   When a callable activation finishes with `outcome = [:yin.k/error err-val]`, tests `(if (data/map? err-val) (dsg__get err-val :dsg__condition) false)`:
   - **Truthy**: calls `(u/app raise (u/app (u/v 'data/dissoc) (u/v err-val) (u/lit :dsg__condition)))` — strips `:dsg__condition` and forwards `err-val` directly as the condition to `raise`.
   - **Falsy**: calls `(u/app raise (u/app (u/v 'yang.call/thrown) (u/v err-val)))` — wraps `err-val` as the `:yang.call/value` of a fresh `:yang.call/thrown` condition.
2. **[`src/cljc/yang/clojure.cljc:2419-2435`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2419-L2435) (lines `:2431` and `:2433`, invoked at `:2507`)** — `strip-top-outcome` inside [`compile-program`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2398-L2678):
   Wraps `(go forms initial-macro-env)` in `(u/let1 top-res ast ...)`; when `(= (data/nth top-res 0) :yin.k/error)` and `(dsg__get top-err :dsg__condition)` is truthy, returns `[:yin.k/error (data/dissoc top-err :dsg__condition)]`.

---

### 2. Why `test-value-invocation-refusals-are-terminating` (`test/yang/clojure_test.clj:2598`) and the R3 Tests Failed with `:dsg__condition true`

In [`test-value-invocation-refusals-are-terminating`](file:///Users/sto/workspace/datomworld/test/yang/clojure_test.clj#L2594-L2637) (`clojure_test.clj:2598`) and the R3 tests (`clojure_test.clj:1486, 1501, 1510, 2735, 2765`), **every failure occurs on `:semantic` while `:walker` passes**:

1. In [`test-value-invocation-refusals-are-terminating`](file:///Users/sto/workspace/datomworld/test/yang/clojure_test.clj#L2598), `(let [five 5 v [five]] (five 1))` compiles `(five 1)` via `value-call` ([`clojure.cljc:1607-1637`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1607-L1637)) into `(yang.call/invoke invoke-value [five 1] {:yang.call/raise ...})`.
2. `invoke-value` ([`clojure.cljc:2794-2795`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2794-L2795)) throws `{:yang.call/reason :yang.call/not-callable, :yang.call/data {:yang.call/callee 5}, :dsg__condition true}`.
3. `invoke-value`'s [`call-entry`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1312-L1315) strips `:dsg__condition` and passes `{:yang.call/reason :yang.call/not-callable, :yang.call/data {:yang.call/callee 5}}` to `value-call`'s `:yang.call/raise` handler ([`clojure.cljc:1617-1622`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1617-L1622)).
4. `value-call`'s `:yang.call/raise` handler invokes [`condition->outcome`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1543-L1544), which **re-stamps `:dsg__condition true`** and escapes via `dsg__raise_escape` with `[:yin.k/error {:yang.call/reason :yang.call/not-callable, :yang.call/data {:yang.call/callee 5}, :dsg__condition true}]`.
5. Because `(let [five 5 v [five]] (five 1))` is a top-level program expression (not inside a `defn`'s `call-entry`), only `strip-top-outcome` ([`clojure.cljc:2419-2435`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2419-L2435), threaded at [`:2507`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2507)) sits between that outcome and the caller.
6. **Why `:semantic` skips `strip-top-outcome` completely**:
   - Inside [`compile-program`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2446), `go` is initialized with `tail? = true` (`:2446`). As `go` lowers top-level forms ([`:2458-2504`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2458-L2504)), it explicitly attaches `:tail? true` to every top-level sequencing application (`:2479`, `:2492`, `:2504`) and passes `tail? = true` into the final form (`:2495` -> [`compile-guest-call:1656`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1656)).
   - Next, [`strip-top-outcome`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2507) wraps `(go forms initial-macro-env)` as the **operand** (`ast`) of `(u/let1 top-res ast ...)` (`((fn [top-res] ...) ast)`).
   - At [`clojure.cljc:2674`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2674), `compile-program` runs `(u/mark-tails ...)` ([`yang.tails/mark-tails`](file:///Users/sto/workspace/datomworld/src/cljc/yang/tails.cljc#L36-L70)), which walks application operands with `tail? = false` ([`tails.cljc:47`](file:///Users/sto/workspace/datomworld/src/cljc/yang/tails.cljc#L47)) **without stripping pre-existing `:tail? true` marks** (violating the invariant documented at [`src/cljc/yang/tails.cljc:1-6`](file:///Users/sto/workspace/datomworld/src/cljc/yang/tails.cljc#L1-L6): *"a stage that wraps a body must strip every mark and recompute them over the whole derived tree, or a wrapped body's old tail calls would skip the wrapper"*).
   - On `:walker` (which ignores `:tail?`), `ast` returns normally into `strip-top-outcome`, which strips `:dsg__condition`. On `:semantic` ([`yin.vm.semantic.linearize:116`](file:///Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic/linearize.cljc#L116)), the stale `:tail? true` on `ast` emits a tail `:call` (`tail? = true`) that returns directly out of the enclosing frame, **bypassing `strip-top-outcome` completely**.

---

### 3. Why **(B) Load-bearing** Holds (Why Simply Deleting `:dsg__condition` Fails R3 + R5)

In `yang.clojure`, both:
- a caught-and-rethrown protocol condition — e.g., `(defn receive [f] (try (f) (catch Exception e (throw e))))` or across separate activations `(defn retain [f] (try (f) (catch Exception e (cell/set! saved e) e)))` followed by `(defn later [] (throw (cell/get saved)))` ([`test/yang/call/exception_contract.clj:77-79`](file:///Users/sto/workspace/datomworld/test/yang/call/exception_contract.clj#L77-L79), probed at [`test/yang/call/exception_probes.clj:112-135`](file:///Users/sto/workspace/datomworld/test/yang/call/exception_probes.clj#L112-L135)) — and
- a guest-thrown condition-shaped data map — e.g., `(defn raise_value [e] (throw e))` with `{:yang.call/reason :yang.call/not-callable, :yang.call/data {:nested [:ordinary :data]}}` ([`test/yang/call/exception_contract.clj:80`](file:///Users/sto/workspace/datomworld/test/yang/call/exception_contract.clj#L80), probed by `:clojure/shape-throw` at [`test/yang/call/exception_probes.clj:101-110`](file:///Users/sto/workspace/datomworld/test/yang/call/exception_probes.clj#L101-L110) and `test-exception-r3-thrown-shape-stays-data` at [`test/yang/clojure_test.clj:1521-1560`](file:///Users/sto/workspace/datomworld/test/yang/clojure_test.clj#L1521-L1560))

enter the outcome channel through the **exact same `(throw x)` lowering** at [`src/cljc/yang/clojure.cljc:1125`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1125) (`[error-tag v]`) and arrive at [`call-entry`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1309-L1317).

- If [`call-entry`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1309-L1317) wraps every `err-val` in `(yang.call/thrown err-val)` (as `master` @ `8cd9fc92` does), rethrowing a caught `:yang.call/not-callable` condition double-wraps it into `:yang.call/thrown`, violating **Ruling v2 §R3**.
- If [`call-entry`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1309-L1317) inspects only structural keys (`:yang.call/reason`), guest `(throw {:yang.call/reason :yang.call/not-callable, :yang.call/data ...})` is forwarded as a raw `:yang.call/not-callable` refusal instead of `:yang.call/thrown`, violating **Ruling v2 §R2 & §R5**.
- Furthermore, because [`check-program-symbols!`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2370-L2394) rejects reserved-prefix **symbols** (`symbol?` at `:2377`, `:2386`) and **not keywords**, guest source can legally write `{:yang.call/reason :yang.call/not-callable, :dsg__condition true}`. Checking `(dsg__get err-val :dsg__condition)` for literal `true` allows guest code to forge a retained condition, violating **Ruling v2 §R5** (*"forged or source-writable attributes on a wrapper-looking value never authenticate a retained condition"*).

---

### 4. Minimal Fix Instruction for the Implementation Round (R3 vs R4 Ownership)

#### What R3 Owns and Must Change Now (in [`src/cljc/yang/clojure.cljc`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc))

1. **Fix the stale `:tail? true` mark in [`compile-program`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2446-L2674) so `strip-top-outcome` is never tail-skipped on `:semantic`**:
   - At [`src/cljc/yang/clojure.cljc:2674`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2674), replace `u/mark-tails` with [`yang.tails/remark-tails`](file:///Users/sto/workspace/datomworld/src/cljc/yang/tails.cljc#L72-L77) (or pass `(if (outcomes? env) false tail?)` to `go` at [`:2446`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2446) in addition to `remark-tails`), so pre-existing `:tail? true` marks inside `(go forms initial-macro-env)` are stripped and recomputed after `strip-top-outcome` wraps the body at [`:2507`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2507).
2. **Authenticate `:dsg__condition` with the compiler-held closure token `dsg__call_token` instead of literal `true` (satisfying R5)**:
   - `dsg__call_token` ([`clojure.cljc:2632`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2632)) is already an unforgeable per-unit closure value (`(fn [x] x)`) in scope of `call-entry`, `compile-guest-call`, `dsg__intra_call`, and `strip-top-outcome`, and its symbol name `dsg__call_token` is blocked in user forms by [`check-program-symbols!`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2370-L2394).
   - At writer sites **#1–#6** ([`clojure.cljc:1544, 1548, 1567, 1586, 1636, 2617`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1543-L1636)), stamp `:dsg__condition` with `(u/v 'dsg__call_token)` (and `dsg__call_token` in quoted `dsg__intra_call` at `:2617`) instead of `true`.
   - At writer site **#7** (`invoke-value` in `library`, [`clojure.cljc:2794-2795`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2794-L2795)), remove `(throw (assoc ... :dsg__condition true))` and instead trigger a genuine `not-callable` refusal that stamps `dsg__call_token` via compiler-generated code (or invoke `(f)` / a non-callable refusal helper lowered by the compiler), because `library` forms pass through `check-program-symbols!` when spliced in `run-library` tests.
   - At the two reader/stripper sites ([`call-entry` at `clojure.cljc:1312-1315`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L1312-L1315) and [`strip-top-outcome` at `clojure.cljc:2430-2433`](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L2430-L2433)), replace the truthiness check `(dsg__get err-val :dsg__condition)` with token equality `(dsg__equal (dsg__get err-val :dsg__condition) dsg__call_token)`.
   - Thus, the external wire condition map leaving `call-entry` (`raise`) or leaving the top-level program (`strip-top-outcome`) is strictly `{:yang.call/reason <kw>, :yang.call/data <map>}` (or `{:yang.call/reason :yang.call/thrown, :yang.call/value <val>}`) with `:dsg__condition` stripped, while inside `try`/`catch` the retained condition carries `:dsg__condition dsg__call_token` so an unchanged `(throw e)` (immediate or delayed via `retain`/`later`) is recognized by `call-entry`, stripped of `:dsg__condition`, and forwarded unchanged.

#### What R3 vs R4 Owns

- **R3 (Clojure `call-entry` / `compile-guest-call` / `dsg__intra_call` / `strip-top-outcome`)**: Owns the Clojure-side lowering of `yang.call/invoke` outcomes into `[:yin.k/error ...]`, the unforgeable `dsg__call_token` provenance tag on retained conditions, the stripping of `:dsg__condition` at `call-entry` and `strip-top-outcome`, and the `remark-tails` fix in `compile-program`.
- **R4 (`test/yang/call/exception_contract.clj`, `test/yang/call/exception_probes.clj`, `test/yang/call/conformance.clj`)**: Owns the cross-language 6-family probe matrix (`:thrown-user-value`, `:thrown-condition-shaped-value`, `:call-refusal-not-callable`, `:call-refusal-invalid-invocation`, `:call-refusal-unsupported-binding`, `:caught-and-rethrown`), including `:clojure/shape-throw` ([`exception_probes.clj:101-110`](file:///Users/sto/workspace/datomworld/test/yang/call/exception_probes.clj#L101-L110)) and `:clojure/rethrow-unchanged` (`receive` and `retain`+`later` at [`exception_probes.clj:112-135`](file:///Users/sto/workspace/datomworld/test/yang/call/exception_probes.clj#L112-L135)), asserting that external conditions never leak `:dsg__condition` and that guest-thrown condition-shaped maps never forge a retained condition.
