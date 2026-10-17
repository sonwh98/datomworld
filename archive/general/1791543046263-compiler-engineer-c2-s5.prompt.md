Created-GMT: 2026-10-09 10:50:46 GMT
Created-Local: 2026-10-09 17:50:46 ICT
Coding-Agent: claude
Session-ID: 1593f17d-5b76-4b4f-a640-9a7aeb961c8b

# Task: yang.python Phase C2 Slice S5 (Generators: Heap collection, wire, and hosts)
Role: VM Runtime Engineer / Compiler Engineer
Implementers:
- Model: claude-sonnet-5-5 | Assigned: 2026-10-09 17:50:46 ICT | Status: active | Rationale: Lead Engineer for Track 3 (Slice C2-S5 Generator Snapshots & Heap Parity)

You are the Engineer implementing Track 3 (Phase C2 Slice S5) in the dedicated worktree:
`/Users/sto/workspace/datomworld/.claude/worktrees/yang-python-c2-s5`

## Governing Architecture & Rules
- Governing Specification: `docs/design/yang.antlr.md` (§8.5.3 Generators, Phase C2 S5; rulings 1, 2, 7, 8).
- Prior Landed Slices:
  - C2 S1: core generators (`7654c2d0`).
  - C2 S2: send, throw, close (`c3f2da8f`).
  - C2 S3: yield from, iter (`a4efc99a`).
  - C2 S4: genexps (`34c3986b`).
- Codebase files:
  - `src/cljc/yang/python/antlr/render.cljc` (Python snapshot `repr` and `py-str`).
  - `src/cljc/yang/python/antlr/prelude.cljc`.
  - `test/yang/python/antlr/e2e_c2_test.clj`.

## Scope of Slice C2-S5 (Section 8.5.3)
1. **Generator Snapshot Rendering in `render.cljc`**:
   - Format suspended, running, and closed generator objects consistently with Python semantics:
     e.g., `<generator object <name> at 0x...>` or deterministic representation.
   - User-defined iterator classes and iterator wrapper rendering.
2. **Heap Collection & Determinism across suspensions (Rulings 1, 2, 7)**:
   - Collection of suspended and dropped generators without reference leaks.
   - Ensure cell references in captured continuation frames do not keep garbage live across repeated suspension and re-entry.
   - Preserves behavioral parity and same-host determinism across all four VMs (:ast-walker, :semantic, :stack, :register).
3. **Cross-host Verification & Tests**:
   - Expand `test/yang/python/antlr/e2e_c2_test.clj` and create portable tests if applicable.
   - Verify on JVM (`bb test:clj -n yang.python.antlr.e2e-c2-test`) and Node (`bb test:cljs`).
   - Clean linter (`clj -M:kondo`).

## Reporting
Deliver the complete implementation, verify across test suites, and record your findings in:
`collab/1791543046263-compiler-engineer-c2-s5.claude-sonnet-5-5.findings.md`
