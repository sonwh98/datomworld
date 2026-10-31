Created-GMT: 2026-10-09 09:46:26 GMT
Created-Local: 2026-10-09 16:46:26 ICT
Coding-Agent: glm
Session-ID: a4ec4fbb-2d96-4161-8566-83aaaef79de0

# Task: yang.python Phase C4 Slice I1 (Static absolute imports, py/import, sys.modules, __name__)
Role: Compiler Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-10-09 16:46:26 ICT | Status: active | Rationale: Lead Compiler Engineer for Track A Phase C4 Slice I1

You are the Compiler Engineer implementing Track A Phase C4 Slice I1 in the dedicated worktree:
`/Users/sto/workspace/datomworld/.claude/worktrees/yang-python-c4-i1`

## Governing Architecture & Rules
- Governing Specification: `docs/design/yang.antlr.md` (§8.5.6 Imports, the linked prelude, and the frontend catalog).
- Foundation Invariants: `docs/design/datom.world.md`.
- Prior Landed Slices:
  - P1: Single-source prelude (`py/init!`, builtins dict).
  - P2: Module emitter, publication, linked profile (`prelude.cljc`, `test/yang/python/antlr/linked_prelude_test.cljc`).
  - P3: `pysp` linked & safepoint hooks (`safepoint.cljc`).

## Scope of Slice I1 (Section 8.5.6)
1. **Module Shape & Emitter**:
   - A compiled Python unit `a` becomes linker module `pym.a`:
     ```clojure
     (do (require 'py)
         (require 'pym.c) ;; for each static import in the unit
         (yin/def spec {:name "a" :package? false})
         (yin/def body (fn [%globals %globals-fn] <lowered module body>)))
     ;; :yin.module/exports #{spec body}
     ```
   - Ensure `spec` and `body` are exported, immutable after install.
   - The module object is a heap cell: `{:py/type :module :name s :dict d}` where `d` is the dict `%globals`.
2. **Runtime `py/import` in Prelude (`src/cljc/yang/python/antlr/prelude.cljc`)**:
   - Resolution order per §8.5.6:
     1. `sys.modules` dict in prelude/task state: hit returns existing module object.
     2. Runtime-synthesized modules: `"sys"` and `"builtins"` pre-seeded by `py/init!`.
     3. Linker resolution: retrieve `spec` and `body` from `pym.<name>`.
   - On cache miss:
     - Create module object cell `{:py/type :module :name s :dict d}`.
     - Set `__name__` to module name (and `__package__` from spec).
     - Insert into `sys.modules` **before** executing `body` (to support basic cycle detection/self-reference).
     - Run `(body d globals-fn)`.
     - On exception during body execution: remove entry from `sys.modules` and re-raise.
3. **AST Lowering for Imports (`src/cljc/yang/python/antlr/lower.cljc`)**:
   - Lower `import c`:
     - Hoist linker require `(require 'pym.c)`.
     - Lower statement to `(py/import "c" pym.c/spec pym.c/body)` and bind `c` in current scope `%globals`.
   - Maintain entry wrapper for `__main__` via `py/run-main` instantiating the main code image with `__name__ = "__main__"`.
4. **Verification & Tests (`test/yang/python/antlr/import_test.cljc`)**:
   - Multi-module static import tests:
     - Importing module `b` from `a` (`import b; x = b.val`).
     - Verifying `sys.modules["b"]` presence and identity (`import b; import sys; assert sys.modules["b"] is b`).
     - Second import of `b` does not re-evaluate `b`'s body (idempotency / cache hit).
     - Failure during imported body cleans up `sys.modules` and propagates exception.
     - Cross-module exception identity: exception raised in imported module catches as expected type in importer.
   - Run tests across JVM (`bb test:clj -n yang.python.antlr.import-test`), Node (`bb test:cljs -n yang.python.antlr.import-test`), and Dart.
   - Run Kondo linter (`clj -M:kondo`).

## Reporting
Deliver the complete implementation, run local verification, and write your findings report to:
`collab/1791539186673-compiler-engineer-c4-i1.glm-5-3.findings.md`
