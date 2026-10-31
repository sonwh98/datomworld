Created-GMT: 2026-09-13 14:06:21 GMT
Created-Local: 2026-09-13 21:06:21 +07
Coding-Agent: claude
Session-ID: 781439cd-75b9-4f40-80e9-f9d063660905

# Task: vm-v2-codec-phase-0

Role: VM Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-13 21:06:21 +07 | Status: active | Rationale: deep AST/compiler expertise; Phase 0 is codec-only, no runtime

## Governing design

Read in full before writing a line of code:
- `docs/design/yin.vm.macro.md` — especially:
  - §0 Decisions (all 9)
  - §2 (expander state, macro definitions)
  - §3 (batch structure, element rules)
  - §4 (provenance — note the §4.1 split: :yin/source-call value, :yin/source-node ref)
  - §7 Phase 0 deliverables (lines 918–943) — this is your acceptance checklist
  - Appendix A (what is retired and why)
- `src/cljc/yin/vm.cljc` (current codec — read completely)
- `src/cljc/yin/vm/ast_walker.cljc` (read — confirm no macro branch to add)
- `test/yin/vm_test.cljc` (current tests — read completely; this file may not exist yet)
- `test/yin/repl_core_test.cljc` (read — confirm existing compile/eval tests still pass)

## File authority

Primary files (you own these completely):
- `src/cljc/yin/vm.cljc`
- `test/yin/vm_test.cljc` (create if absent)

Read but do NOT edit without prior stop-and-request:
- `src/cljc/yin/vm/ast_walker.cljc`
- Any file in `src/cljc/dao/`
- Any test file other than `v2_test.cljc`

If a required dependency demands a change in an out-of-scope file, stop and
describe what you need; do not edit it.

## Deliverables (§7 Phase 0)

### 1. Schema changes in `yin.vm/schema`

**Add:**
- `:yin/root` — a ref attribute. Every AST batch has exactly one root node;
  `ast->datoms-with-root` emits `[watermark :yin/root root-eid t default-op]`
  unconditionally (even for macro-free programs).
- `:yin/macro-name` — a value attribute (string or symbol) naming the macro
  operator when it was a `:variable` and the macro is not durable.

**Remove from schema** (event attributes move to the expander's emitter in Phase 1;
they do not belong in the universal AST schema):
- `:yin/phase-policy`
- `:yin/phase`
- `:yin/capability`
- `:yin/source-call` ← was declared a ref; now split: the value role is
  undeclared (no schema entry), the ref role moves to `macro/event-schema`
  as `:yin/source-node`. Remove from `yin.vm/schema` entirely.
- `:yin/macro`
- `:yin/expansion-root`
- `:yin/error`

### 2. `ast->datoms-with-root`

Rename (or add an alias for) `ast->datoms` to `ast->datoms-with-root`. This
function must emit one additional fact unconditionally:
```
[watermark :yin/root <root-eid> t default-op]
```
where `<root-eid>` is the eid of the AST's root node and `watermark` is the
batch's watermark entity (the entity that receives `:yin/type :batch` or
equivalent).

### 3. `index-datoms` honours `:yin/root`

`index-datoms` (if it exists in `v2.cljc`) must process the `:yin/root` fact:
last wins on multiple root facts in one batch (a defect, but not a crash);
a `:yin/root` pointing to an absent eid is a dangling error (record but
continue, do not throw).

### 4. Codec drops `:yin/macro-expand` arms

The codec (both `ast->datoms` direction and `datoms->ast` direction) must drop
any arms handling `:yin/macro-expand`. This node type is retired (Decision 2,
Appendix A). If a `:yin/macro-expand` datom is encountered in `datoms->ast`,
it is silently ignored (it names an entity whose type the evaluator does not
recognise — that is the loud failure path, not the codec's).

### 5. Drop `:phase-policy` handling

Any codec arm for `:yin/phase-policy` or `:yin/phase` is removed. Decision 3
(no phase distinction).

### 6. `ast_walker.cljc` — docstring only

Do NOT add a macro branch. The `:lambda` arm and `datoms->ast` continue to
ignore `:macro?`. Add or update the namespace docstring to state Decision 1
explicitly: "No macro branch: evaluators know nothing about macros (decision 1
of `yin.vm.macro.md`)."

### 7. Tests in `test/yin/vm_test.cljc`

Create this file (or extend if it exists) with tests covering:
- **Root fact wins:** a batch with a root fact produces a db where querying
  `[?e :yin/root ?r]` returns the correct root eid.
- **Dangling root:** a `:yin/root` pointing to a non-existent eid is recorded
  as a dangling error (not a throw).
- **Last wins:** two `:yin/root` facts in one batch — the later one wins.
- **Heuristic fallback unchanged:** `compile` output for a macro-free program
  is unchanged modulo the new root fact (the root fact is unconditional, so
  existing compile snapshots gain exactly one row; adjust assertions
  accordingly).
- **Compile output unchanged for macro-free programs** modulo the root fact:
  run `(compile some-program)` before and after; assert the only difference
  is the addition of the root fact.

### Invariants

- Decision 1: evaluators know nothing about macros. `ast_walker.cljc` gets no
  macro branch, no macro flag, no expansion ledger.
- Decision 3: no phase. `:yin/phase-policy` and `:yin/phase` are gone.
- Decision 2: `:yin/macro-expand` is retired.
- Do NOT break `yin.repl.core-test`. Run it as part of verification.
- Do not weaken any existing test.
- Preserve unrelated comments and docstrings.

### Verification

After implementation, run:
```
clj -M:test -n yin.vm-test -n yin.repl.core-test
clj -M:kondo --lint src/cljc/yin/vm.cljc src/cljc/yin/vm/ast_walker.cljc test/yin/vm_test.cljc
git diff --stat
```

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report: changed files, exact test counts, kondo result, unresolved concerns,
and any §7 Phase 0 items that are incomplete (with reason).
Do not claim edits or tests that did not occur.
