Created-GMT: 2026-10-14 08:25:00 GMT
Created-Local: 2026-10-14 15:25:00 +07
Coding-Agent: codex (gpt-6.1-sol)
Session-ID: not-applicable (prompt brief)

# Task: I4-a, statement-time Python imports and `ModuleNotFoundError`

Role: Engineer (implementation). Worktree: `/Users/sto/workspace/datomworld-i4a` (branch `yang-python-i4a`, from master ca13f667; already created and trusted with `mise trust`). Stage this brief and your report INSIDE the worktree's `collab/` (acceptEdits sandbox cannot reach main `collab/`).

BINDING spec:
- Scoping design: `collab/1792341000000-architect-i4-scoping.claude-fable-5-1.stdout.log` section 2.6 ("What yang.python's lowering emits (outside the linker)") and section 3 acceptance table row I4-a.
- Mob consolidation: `collab/1791950575268-mob-consolidation-i4.md` question 3 ruling:
  - Unresolved Name (`:reason :absent` with `:name` key): raise `ModuleNotFoundError`.
  - Content miss at address (`:reason :absent` with `:address` key): raise `ImportError`.
  - All other refusals (`:untrusted-principal`, `:dependency-pin-mismatch`, `:require-cycle`, etc.): raise `ImportError` carrying the structured refusal map.
- Code to edit:
  - `src/cljc/yang/python/antlr/lower.cljc`: `lower-packet`, `module-packet`, `import-chain`, `lower-import`, `lower-import-from`, `import-spec-body`.
  - `src/cljc/yang/python/antlr/prelude.cljc`: `builtin-classes` (`ModuleNotFoundError` as subclass of `ImportError`), `py/import`, `py/import-sub`, `py/import-refused`.
  - Documentation: `docs/design/yang.antlr.md` 8.5.6.
  - Tests: `test/yang/python/antlr/import_test.cljc`, `test/yang/python/antlr/golden_test.cljc` (or matching suites).

## Context & The Change

Previously, `lower-packet` and `module-packet` hoisted unconditional `(require 'pym.<i>)` for each statically imported module into the module-level form. An absent or unresolvable optional dependency would abort module installation up front before any Python statement (such as `try: import x / except ImportError:`) could run.
Slices L-d-1, L-d-2, and L-d-3 landed `try-require`, the registry pin invariant, and deferred pin handling in the DHT source. Now `yang.python` emits statement-time imports:

1. **Lowering (`src/cljc/yang/python/antlr/lower.cljc`)**:
   - `lower-packet` (`:linked`) and `module-packet`: drop the hoisted module-level `(require 'pym.<i>)` applications. `(require 'py)` remains eager.
   - `module-spec` still pins every static import in `:requires` and covers `pym.<i>/*`. `try-require` is declared by its primitive profile through the existing `declare-free-name` path without special changes.
   - `import-chain`: emits per link a delivery thunk that executes `try-require` first and reads exports only on `:ok`, lexically after testing the status:
     ```clojure
     (py/import (u/lit {:py/str full})
                (u/lam []
                       (let1 'r (app* 'try-require (u/lit (module-symbol full)))
                             (u/if-node (app* '= (u/lit :ok) (app* 'get (u/v 'r) (u/lit :status)))
                                        (u/vec [(u/v (symbol mod "spec")) (u/v (symbol mod "body"))])
                                        (u/v 'r)))))
     ```
     For pre-seeded modules (`sys`, `builtins`) and the unit itself, pass `none` (or a thunk returning `[none none]`), which `py/import` handles as today.
     Ensure `py/import-sub` receives the delivery thunk similarly.

2. **Prelude (`src/cljc/yang/python/antlr/prelude.cljc`)**:
   - Add builtin exception `ModuleNotFoundError` as a subclass of `ImportError`:
     `["ModuleNotFoundError" 'py.b/ModuleNotFoundError 'py.b/ImportError]`
     Add it to `builtin-classes` and export it in `builtins`.
   - Update `py/import` and `py/import-sub`:
     - `sys.modules` is checked first. If present, return the cached module object immediately without running the thunk.
     - If miss: call the delivery thunk to obtain `[spec body]` or refusal map `r`.
     - If `body` is `:py/None` (e.g. for pre-seeded miss): `(py/no-module name)`.
     - If refusal map `r` is returned (status is not `:ok`): call helper `(py/import-refused name r)`.
     - `py/import-refused`:
       - If `(= :absent (get r :reason))` and `(contains? r :name)`: raise `ModuleNotFoundError` (`No module named '<name>'`).
       - If `(= :absent (get r :reason))` and `(contains? r :address)`: raise `ImportError` (`Cannot load module '<name>' at address ...`).
       - Otherwise (e.g. `:untrusted-principal`, `:dependency-pin-mismatch`, `:require-cycle`, `:ambiguous-name`): raise `ImportError`, attaching the refusal map or diagnostics to the exception.
     - A caught import failure leaves NO entry in `sys.modules`, so subsequent import attempts retry.

3. **Documentation (`docs/design/yang.antlr.md` 8.5.6)**:
   - Amend ruling 8 and acceptance text to state statement-time import execution, catchable `ModuleNotFoundError` / `ImportError`, and `try-require` delivery thunks.

4. **Acceptance Tests**:
   - In `test/yang/python/antlr/import_test.cljc` (or new test section):
     - `except ModuleNotFoundError` catches when a module is absent (`:reason :absent` with `:name`).
     - `except ImportError` also catches `ModuleNotFoundError`.
     - Mismatched pin or untrusted principal raises `ImportError` (not `ModuleNotFoundError`).
     - Retry after a caught failure re-imports.
     - Unchanged behavior for I1/I2 programs.
     - Root golden (import-free) unchanged; importing goldens updated if ASTs moved.

## Test Process & Constraints
- Iteration: JVM only on laptop (`clojure -M:test -n yang.python.antlr.import-test` and `bb test:sub:clj yang.python`).
- Portability: no `with-redefs`, start maps from `{}`, cljd first.
- Lints: `clj -M:kondo` clean, `mise exec -- cljstyle check` clean.
- Write `collab/1791966252213-engineer-i4a-statement-import.findings.md` in the worktree early and keep it updated.
