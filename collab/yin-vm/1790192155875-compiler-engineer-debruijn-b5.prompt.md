Created-GMT: 2026-09-23 19:35:55 GMT
Created-Local: 2026-09-24 02:35:55 +0700
Coding-Agent: claude
Session-ID: 44fde443-afd8-4e01-a7ce-ca3454427940

# Task: Implement Phase B5 — Differential Integration (test/yin/vm/debruijn/stack_parity_test.cljc)

Role: Compiler Engineer

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-24 02:35:55 +0700 | Status: active | Rationale: Implement Phase B5 differential integration test suite for de Bruijn stack VM

## Working Directory

You must work strictly inside the dedicated worktree:
  `/Users/sto/workspace/worktree-debruijn-b5`

Do not touch or modify the main repository tree `/Users/sto/workspace/datomworld`.

## What to Read First

1. `/Users/sto/workspace/datomworld/docs/design/datom.world.md` -- governing invariants
2. `/Users/sto/workspace/datomworld/docs/design/yin.vm.debruijn.stack.md` -- Section "### B5: differential integration" (approx line 660) and Section 3.2, Section 5
3. Existing reference test suites:
   - `test/yin/vm/parity_test.cljc` -- the `corpus` of programs and binop helpers
   - `test/yin/vm/debruijn/stack_test.cljc` -- stack VM execution harness and B0 normalizer usage
   - `test/yin/vm/debruijn/stack_effects_test.cljc` -- stack effects and stream harness
   - `test/yin/vm/debruijn_linearize_test.cljc` -- `adapt` usage and AST fixtures
   - `test/yin/vm/debruijn_vm_contract_test.cljc` -- `normalize` function
4. `docs/agents/build-n-test.md` -- build and test commands

## Phase B5 Specification (from `docs/design/yin.vm.debruijn.stack.md`)

```text
### B5: differential integration

    New: test/yin/vm/debruijn/stack_parity_test.cljc
    Existing edits: none
    Must not change: named storage and the existing linearizer pipeline

Compare `ast->datoms` -> B2 -> de Bruijn VM against
`ast->datoms` -> `linearize/lower` -> semantic VM. Use the actual parity and
content/completion corpora, not helper-only tests as execution fixtures.
Completion requires programs differing only in binder names to produce the
same H on every host lane for the common scalar domain. Programs differing in
exact scalar spelling, front-end tail flags, or free names must produce
different H values. An image sent over a `dao.stream` from one host lane must
load, validate, and execute on another with the same normalized result as
local execution. B5 uses committed golden bytes for cross-runtime identity;
the real cross-process stream transfer is a B6 acceptance test. All three host
lanes must agree under the normalizer.
```

## Deliverables

Create `test/yin/vm/debruijn/stack_parity_test.cljc`:

### 1. Differential Execution Parity
- Run programs through both execution pipelines:
  - Pipeline A (de Bruijn Stack VM):
    `ast` -> `(yin.vm/ast->datoms-with-root ast)` -> `(yin.vm.debruijn-linearize/adapt datoms)` -> run `:image` in fresh `yin.vm.debruijn.stack/create-vm` -> `(yin.vm/value vm)`
  - Pipeline B (Named Semantic VM):
    `ast` -> `(yin.vm/eval (yin.vm.test-utils/create-vm) ast)` -> `(yin.vm/value vm)`
- Compare normalized values using `yin.vm.debruijn-vm-contract-test/normalize`.
- Corpus: Use the comprehensive `yin.vm.parity-test/corpus` (literals, arithmetic, booleans, comparisons, nested applications, conditionals, lambda invocations, recursion, tail calls).

### 2. Alpha-Equivalence Laws for H
- Programs differing ONLY in binder parameter names (e.g. `(fn [x] (+ x 1))` vs `(fn [y] (+ y 1))`) must lower via `adapt` to the EXACT SAME canonical instruction vector and identical $H$ (`image-hash`) on every host lane.
- Test across multiple nested closure depths and duplicate parameter patterns.

### 3. Non-Alpha Distinctions (Distinction Preservation)
- Programs differing in:
  - Exact scalar spelling (e.g. integer `1` vs double `1.0`, following contract version 1 type preservation).
  - Front-end tail flags (`:tail? true` vs `:tail? false`).
  - Free names (e.g. `foo` vs `bar`).
  must produce DIFFERENT $H$ values.

### 4. Stream Transport Simulation & Validation
- Simulate transmitting an image over `dao.stream` (serialize image to EDN/datoms/transit or stream format, write to in-memory `dao.stream`, read from stream cursor, validate via `yin.vm.debruijn-code/image-defect`, and execute on fresh stack VM).
- Confirm that the result under `normalize` equals local execution.
- Include golden vector and $H$ fixtures across hosts.

### 5. Multi-Host Parity
- Test must run cleanly on JVM, Node/CLJS, and ClojureDart without host-specific failures.

## Verification Standards

- Line length strictly $\le 80$ columns.
- 100% pure ASCII.
- `cljstyle check test/yin/vm/debruijn/stack_parity_test.cljc` must be clean.
- `clj -M:kondo --lint test/yin/vm/debruijn/stack_parity_test.cljc` must return 0 errors, 0 warnings.
- Run tests:
  - JVM: `clojure -M:test -n yin.vm.debruijn.stack-parity-test`
  - CLJS: `bb test:cljs`
  - CLJD: `bb test:cljd`

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700 ICT>
Session-ID: 44fde443-afd8-4e01-a7ce-ca3454427940

Then summarize the tests created, assertions count, test execution results on all three hosts, and confirm all invariants.
