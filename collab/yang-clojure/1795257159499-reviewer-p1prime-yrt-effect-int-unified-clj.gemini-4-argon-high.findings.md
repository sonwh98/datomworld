Created-GMT: 2026-11-21 10:40:00 GMT
Created-Local: 2026-11-21 17:40:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: e4fbf30d-58bb-4263-9981-af6c3994b3fb

# Task: Step-7 review — yang.preludes P1′ (yrt.effect + yrt.int + unified clj)

Role: Reviewer (independent, read-only)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-21 17:32 +07 | Status: active | Rationale: independent family from the codex/gpt-6.2-sol implementer; argon for careful design review

## Review Summary

Reviewed the uncommitted working-tree diff in `/Users/sto/workspace/dw-p1prime` on branch `feat/p1prime-yrt-effect` (base `f23728d3`, 17 files changed including 4 `git add -N` new files) against `docs/design/yang.preludes.md` r8 (§4.3, §4.4, §7.4, §8.1, §9 P1′) and the engineer brief.

### Verification by Focus Area

1. **§4.3 unwind semantics — one rule per frame kind (`src/cljc/yang/rt/effect.cljc:15-182`):**
   - `yrt.effect/raise` (`src/cljc/yang/rt/effect.cljc:36-49`) enforces one rule per frame kind: `:finally` pops the frame (`pop-frame` restores both `:handlers` and slot-4 `:depth`), runs `((data/nth hs 1) e)` once, and continues unwinding; `:effect` pops the frame and continues unwinding (never delivering to an `:effect` frame); `:handler`/boundary delivers `((data/nth hs 1) e)` and stops.
   - `yrt.effect/perform` (`src/cljc/yang/rt/effect.cljc:135-182`) walks `:handlers` via `_find-effect` without modifying `ctx-cell` or running `:finally` frames.
   - `_dispatch` (`src/cljc/yang/rt/effect.cljc:144-176`) transfers a thunk to `outer-k` in `yrt.effect/handle` (`src/cljc/yang/rt/effect.cljc:120-134`), which restores the outer context `saved` before running the handler under a temporary `try-finally` guard.
   - **Cannot double-run cleanup:**
     - On **one-shot resume** (`src/cljc/yang/rt/effect.cljc:162-167`), `pending?` is set to `false`, `ctx-cell` is restored to `saved` (the context at `perform`, which does not contain the temporary guard frame), and `(k v)` jumps directly to the `%capture` site in `_dispatch` (abandoning the temporary `try-finally` continuation). The temporary guard never runs, and the reinstated `:finally` frames inside `handle` run once when `body` subsequently exits.
     - On **value abort** (handler returns normally without calling `k`) or **raise/escape abort** (handler raises or escapes past `handle`), the temporary `try-finally` guard (`src/cljc/yang/rt/effect.cljc:168-174`) runs once, sets `pending?` to `false`, restores `saved`, calls `unwind-to` down to `(frame-depth (get outside :handlers))` (which pops each suspended `:finally` frame before invoking its cleanup closure), and restores `outside`. Even if a suspended `:finally` cleanup itself raises during `unwind-to`, the already-popped frames and the temporary guard are absent from `ctx-cell`, preventing any double execution.

2. **Continuation-portability envelope change (`test/yang/latte_oracle_test.clj:1385-1445`):**
   - `test-oracle-slice8-context-reconstruction` still verifies that code rows round-trip through CBOR and evaluate to `[:yin.k/ok 7]` on `:walker` and `:semantic` (`lines 1417-1421`), verifies that the paused task's registry holds an authentic `:cell-ref` whose heap entry is a closure (`lines 1435-1437`), asserts `rows/lift-task` refuses with `{:yin.k/status :yin.k/non-portable :yin.k/kind :cell}` (`lines 1439-1443`), and asserts task-local resumption yields `[:yin.k/ok 7]` (`line 1444`).
   - One net coverage gap in `assert-machine-data-within-budget!` is flagged as `F1` (LOW) below.

3. **§7.4 door enforcement (`src/cljc/yang/clojure/prelude.cljc:53-203`, `src/cljc/yin/vm/provenance_scope.cljc:1-151`, `src/cljc/yin/vm/engine.cljc:183-241`):**
   - Private provenance doors (`cell/dsg__new-provenance`, `cell/dsg__provenance`) exist only inside `yang.clojure.prelude/definitions` (shared by both linked `module-uast` and bundled `uast`/`bundled-uast`), are never exported in `module-exports` (`src/cljc/yang/clojure/prelude.cljc:190-191`), and are completely absent from `yrt.effect`, `yrt.int`, and `yang.clojure`.
   - `clj/make-callable` (`src/cljc/yang/clojure/prelude.cljc:103-130`) implements the two-step cell-backed constructor (`owner = (cell/new meta)`, then `cell/set!` with `:yang.call/entry`) and the exact 4-step §8.10.7 entry boundary:
     - Step A (`lines 108-110, 127-128`): checks `(data/map? invocation)` and `(data/callable? raise)`, failing operationally otherwise.
     - Step B (`lines 111-113, 122-126`): outside `try-value`, checks `(yang.call/valid-invocation? invocation)` and `(= (get invocation :yang.call/callee) owner)`, transferring `:yang.call/invalid-invocation` with evaluated `assoc` evidence on failure.
     - Step C (`lines 114-120`): outside `try-value`, rejects `:yang.call/binding-profile` with evaluated `assoc` evidence and transfers `:yang.call/unsupported-binding`.
     - Step D (`lines 72-102`, `clj/dsg__run-body`): runs argument binding and body inside `yrt.effect/try-value` on `(clj/rt-ctx)`, forwarding retained protocol conditions via `cell/dsg__provenance` or wrapping user escapes via `yang.call/thrown` (with `call-ec` releasing the entry frame before tail-call transfer when `:clj/tail-call?` is set).
   - Bundled-mode door enforcement (`yin.vm.provenance-scope` + `engine/check-bundled-provenance!`) verifies the content address of the closed `:clj/bundled-runtime` bundle (row root in `:walker`; normalized reachable closure-body graph in `:semantic`, `:stack`, `:register`) and grants `:cell/new-provenance` / `:cell/provenance` only at the 4 exact call sites inside `clj`. Forged co-bundled calls before or after `prelude/uast` fail with `{:reason :private-provenance}`.

4. **`data/nth` discipline (`src/cljc/yang/rt/effect.cljc:27-149`, `src/cljc/yang/rt/int.cljc:38-87`, `src/cljc/yang/clojure/prelude.cljc:37-164`):**
   - Every 5-slot frame vector read and `[tag handler-fn outer-k]` payload vector read in `yrt.effect` uses `data/nth`; `get` is used strictly on context maps (`:handlers`, `:depth`, `:base`).
   - All vector indexing in `yrt.int` (`qr` pair and codepoint slices `cs`) and `yang.clojure.prelude` (`prim-get-default` vector branch, `unwrap-outcome`) uses `data/nth`.

5. **Scope fences:**
   - `py`, `js`, and `php` preludes, tests, and goldens are completely untouched.
   - `dsg__call_token` is completely retired across `src/` and `test/`.
   - VM changes (`engine.cljc`, `provenance_scope.cljc`, `ast_walker.cljc`, `semantic.cljc`, `debruijn/stack.cljc`, `debruijn/register.cljc`) are minimal and scoped to §7.4 bundled-mode provenance-door enforcement and §4.3 bundled capture-safe primitive resolution (`dsg__primitive/{get,assoc,+,<=}`).

6. **§8.1 portability:**
   - Zero JVM-only reflection or host interop in `yang.rt.effect`, `yang.rt.int`, `yin.vm.provenance-scope`, or `yang.clojure.prelude`.
   - Zero raw host doubles introduced.
   - Canonical ordering and cross-host CBOR digests preserved (`replay-expectations` in `test/yang/clojure_callsite_test.cljc`).

---

## Findings

### F1 [LOW] — `assert-machine-data-within-budget!` in `test-oracle-slice8-context-reconstruction` no longer traverses the heap-resident callable closure (`test/yang/latte_oracle_test.clj:1435-1438`)

- **Evidence:** `test/yang/latte_oracle_test.clj:1434-1438`
  ```clojure
            k (get-in checkpoint [:env 'checkpoint])
            owner (get-in k [:unparser-registry :unparsers :captured])]
        (is (engine/authentic-ref? checkpoint :cell-ref owner))
        (is (values/closure? (get-in checkpoint [:heap (:id owner) :value :yang.call/entry])))
        (assert-machine-data-within-budget! k 500000)
  ```
- **Details:** Before P1′, the callable descriptor map `{:yang.call/entry <closure>, ...}` lived directly inside `k` at `[:unparser-registry :unparsers :captured]`, so `(assert-machine-data-within-budget! k 500000)` traversed `<closure>` and its captured lexical `:env` — verifying both that captured guest environments contain only portable machine data (no raw host functions) and that recursive descriptor-environment validation stays within the 500,000-visit budget. Under §7.4's cell-backed `clj/make-callable`, `k` now holds only the `:cell-ref` handle map `owner` (`{:type :cell-ref, :id ..., :seal ...}`), while the descriptor map and its closure live in `(:heap checkpoint)` at `(get-in checkpoint [:heap (:id owner) :value])`. Because `vm/machine-data?` does not dereference `:cell-ref` handles against the VM heap, calling `(assert-machine-data-within-budget! k 500000)` on `k` alone never inspects the captured closure or its `:env`.
- **Fix:** Pass `[k (get-in checkpoint [:heap (:id owner) :value])]` (or add `(assert-machine-data-within-budget! (get-in checkpoint [:heap (:id owner) :value]) 500000)`) so the heap-resident callable descriptor and its captured closure environment remain covered by the machine-data and visit-budget assertion.

### F2 [NOTE] — Synthetic `dsg__intra_call` helper in `test/yang/latte_oracle_test.clj:2177-2186`

- **Evidence:** `test/yang/latte_oracle_test.clj:2177-2186, 2337-2346, 4083, 4121`
- **Details:** `dsg__call_token` is completely retired across the repository, and `dsg__intra_call` is completely retired from `src/cljc/yang/clojure.cljc` (all compiled calls now emit `clj/invoke-callable` or `dsg__tail_invoke`, matched by `kernel-routed-call?` at `test/yang/latte_oracle_test.clj:2204-2225`). `kernel-intra-call?` in `test/yang/latte_oracle_test.clj` is retained only for the two synthetic tree-correspondence unit tests (`test-cc3-stamp-correspondence-nested` and `test-cc3-correspondence-verifies-grammar-data`) that hand-construct `(app (var* 'dsg__intra_call) ...)` AST nodes.

VERDICT: APPROVED-WITH-FIXES

---

## r2 Confirmation (2026-11-21 17:42:00 +07)

Inspected the updated diff hunk in `test/yang/latte_oracle_test.clj:1438-1442`:

```clojure
        ;; F1 (argon r1): the cell-backed descriptor no longer lives inside k;
        ;; traverse the heap-resident callable so machine-data and visit-budget
        ;; checks still cover the captured closure and its lexical :env.
        (assert-machine-data-within-budget!
          [k (get-in checkpoint [:heap (:id owner) :value])] 500000)
```

`assert-machine-data-within-budget!` now traverses both `k` and the heap-resident callable descriptor `(get-in checkpoint [:heap (:id owner) :value])` (including `:yang.call/entry` and its captured closure `:env`), restoring full machine-data and bounded-visit coverage. F1 is resolved.

VERDICT: CONFIRMED-CLOSED

---

## r3 Confirmation (2026-11-21 20:05:00 +07)

Reviewed `git diff f954d5ec..HEAD` (commit `cbb166e4`) covering the two G2 fixes across `src/cljc/yang/clojure.cljc`, `src/cljc/yang/clojure/prelude.cljc`, `test/yang/clojure/donor_link_test.clj`, `test/yang/clojure_callsite_test.cljc`, `test/yang/latte_oracle_test.clj`, and `test/yang/rt/effect_test.cljc`:

1. **Bundled lexical scope (`yang.clojure.prelude/with-bundled-runtime`, `src/cljc/yang/clojure/prelude.cljc:205-219`, `src/cljc/yang/clojure.cljc:2050-2055`):**
   - **§7.4 bundled door enforcement intact:** `prelude/uast` (`src/cljc/yang/clojure/prelude.cljc:194-202`) is unchanged; the `:clj/bundled-runtime` marker remains inside `prelude/uast`'s closed 0-arg lambda `(u/lam [] (u/then (u/lit :clj/bundled-runtime) install))`. In `with-bundled-runtime`, `body` (`helpers`) is sequenced as a sibling *after* `uast` and `(clj/init!)`, outside the `:clj/bundled-runtime` lambda. Consequently, neither `mark-bundled-calls` (`:walker`) nor `bundle-graph` (`:semantic`, `:stack`, `:register`) grants provenance-door authority to any node or PC in `body`.
   - **§4.3 capture-safety & machine-data portability intact:** The outer lexical scope binds only the reserved `dsg__*` names `[dsg__get dsg__equal dsg__assoc dsg__add dsg__lte]` to portable guest closures (`bundled-primitive-forms`), while bare `[get = assoc + <=]` remain scoped strictly to `shared` (`[call/uast effect/uast]`) and `uast`. Spliced test UAST helpers (such as `cell-entry-node` in `test-exception-r3-*` reading `dsg__get`) resolve `dsg__get` in scope without capturing host functions or preventing user definitions from shadowing bare `get`/`assoc`/`+`/`<=`.

2. **Intra-unit direct-call fast path (`local-call-runtime`, `src/cljc/yang/clojure.cljc:1111-1149, 1354-1362, 1916-1927, 2014-2058`):**
   - **§7.4 callable publication & admission preserved:** `dsg__make_local` still constructs and returns a genuine cell-backed callable via `clj/make-callable`, recording `[owner descriptor local-entry]` in the unit-local `dsg__local_fns` cell.
   - **Fail-closed fallback on redefinition or cell mutation:** `dsg__invoke_local` takes `local-entry` only when `registered` exists, `(data/content= (data/nth registered 0) callee)` (same cell reference `owner`), and `(data/content= (data/nth registered 1) (cell/get callee))` (unmodified cell descriptor); any redefinition or `(cell/set! f ...)` tampering (verified by `test/yang/rt/effect_test.cljc:196-197`) falls back to `dsg__invoke_dynamic` -> `clj/invoke-callable`.
   - **§4.3 unwind & `data/nth` discipline preserved:** Direct intra-unit calls execute on the shared `(clj/rt-ctx)` handler stack inside the enclosing unit/entry `try-value` boundary, use `data/nth` for all tuple reads and `clj/prim-get`/`clj/prim-assoc` for map accesses, and keep all helper bindings lexical to the unit so linked mode (`lower-unit`) introduces zero free names.

VERDICT: CONFIRMED-CLOSED
