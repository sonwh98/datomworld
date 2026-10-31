Created-GMT: 2026-10-14 08:52:00 GMT
Created-Local: 2026-10-14 15:52:00 +07
Coding-Agent: claude (claude-fable-5-1, read-only)
Session-ID: pending (provider-generated)

# Task: Adversarial Code Review of Slice I4-a (statement-time Python imports, delivery thunk with `try-require`, `ModuleNotFoundError` hierarchy and refusal handling)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-14 15:52:00 +07 | Status: active | Rationale: Independent model family reviewer (Anthropic family reviewing OpenAI Codex author)

Worktree to review: `/Users/sto/workspace/datomworld-i4a` (uncommitted diff vs `master@ca13f667` on branch `yang-python-i4a`).

Review the `git diff` read-only per the Q6 review rule (`docs/agents/known-gaps.md`):
- Blocking = in-scope P0/P1, or P2 in claimed acceptance or newly introduced behavior.
- Findings in code the slice should not have written mean revert.

Context & Brief to inspect:
- Brief: `collab/1791966252213-engineer-i4a-statement-import.prompt.md`
- Engineer report: `/Users/sto/workspace/datomworld-i4a/collab/1791966252213-engineer-i4a-statement-import.findings.md`
- Scoping design: `collab/1792341000000-architect-i4-scoping.claude-fable-5-1.stdout.log` (§2.6, §3 acceptance row I4-a)
- Mob consolidation: `collab/1791950575268-mob-consolidation-i4.md`
- Modified source, doc, and test files:
  - `src/cljc/yang/python/antlr/lower.cljc`
  - `src/cljc/yang/python/antlr/prelude.cljc`
  - `docs/design/yang.antlr.md`
  - `test/yang/python/antlr/import_test.cljc`
  - `test/yang/python/antlr/float_address_test.cljc`
  - `test/yang/python/antlr/linked_prelude_test.cljc`
  - `test/yang/python/antlr/linked_safepoint_test.cljc`

Checklist:
1. Lowering (`lower.cljc`):
   - Module-level unconditional `(require 'pym.<i>)` dropped in `lower-packet` and `module-packet`.
   - `(require 'py)` remains eager at module top level.
   - `import-chain` / `import-spec-body`: emits delivery thunk wrapping `try-require`.
   - Delivery thunk checks `(= :ok (get r :status))` before reading exports `pym.<i>/spec` and `pym.<i>/body`. Lexical order guarantees no read on failure.
   - Pre-seeded modules (`sys`, `builtins`) and root/self handled correctly without invalid thunks.
   - `py/import-sub` receives and propagates the delivery thunk.
2. Prelude (`prelude.cljc`):
   - `ModuleNotFoundError` added as subclass of `ImportError`:
     `["ModuleNotFoundError" 'py.b/ModuleNotFoundError 'py.b/ImportError]`
   - Exported in `builtins` and included in `builtin-classes`.
   - `py/import` / `py/import-sub`:
     - Checks `sys.modules` cache first; cache hit returns immediately without invoking thunk.
     - On cache miss: invokes delivery thunk.
     - Handles `none` / preseeded miss with `py/no-module`.
     - Non-`:ok` refusal handled by `py/import-refused`:
       - `(= :absent (:reason r))` with `:name`: raises `ModuleNotFoundError`.
       - `(= :absent (:reason r))` with `:address`: raises `ImportError`.
       - Policy/integrity refusals (`:untrusted-principal`, `:dependency-pin-mismatch`, `:require-cycle`, etc.): raises `ImportError` carrying structured refusal map.
     - Failed import does NOT poison `sys.modules`, permitting retry.
3. Acceptance Tests:
   - `import_test.cljc` tests verify statement-level `try: import ... except ModuleNotFoundError:` and `except ImportError:`.
   - Refusals tested across linked backends (semantic, stack, register).
   - Golden manifest updates for prelude / safepoint: verified that changes are only attributable to updated prelude/lowering addresses.
4. Invariants & Portability:
   - Portability: no host-specific reflection, no `with-redefs`, start maps from `{}`, cljd/cross-platform compliance.
   - Preserves AST walker gate / limitations cleanly without regression.

Begin your response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <session-id>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
Give an explicit verdict: APPROVE, APPROVE-WITH-NITS, or CHANGES_REQUESTED.
Do not edit files.
