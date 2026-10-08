Created-GMT: 2026-10-07 07:16:00 GMT
Created-Local: 2026-10-07 14:16:00 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: 7e2d9310-8b45-4293-9c88-e87f2e691234

# Task: ucf-d10b-b-kernel-lift-lower
Role: Compiler & VM Implementation Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 1791357500000 | Status: active | Rationale: Implement four-kernel UCF lift and lower under the published Version-2 Wire Grammar Amendment (docs/design/yin.vm.universal-continuation-format.v2-amendment.md).

## Instructions
You are the Compiler & VM Implementation Engineer for Datomworld.
Work exclusively inside the dedicated feature worktree:
`/Users/sto/workspace/datomworld-d10b` (branch `ucf-d10b-kernel-lift-lower` @ `9cea60dd`).
DO NOT TOUCH any other worktree.

### Governing Specifications
1. Read first:
   - `docs/design/yin.vm.universal-continuation-format.v2-amendment.md` (normative specification).
   - `collab/1791308000000-architect-d10b-grammar-ruling-astra-r2.gpt-6-astra.findings.md`.
   - `collab/1791356200000-architect-d10b-a-grammar-spec.gpt-6-astra.findings.md`.

### Core Requirements
1. **Scope of Diff**:
   - `src/cljc/yin/vm/ucf.cljc`, `src/cljc/yin/vm/ucf/handoff.cljc`, `src/cljc/yin/vm/ucf/checkpoint.cljc`.
   - Four kernels: `src/cljc/yin/vm/semantic.cljc`, `src/cljc/yin/vm/stack.cljc`, `src/cljc/yin/vm/debruijn_register_effects.cljc`, `src/cljc/yin/vm/ast_walker.cljc`.
   - Related tests in `test/yin/vm/ucf/` and corresponding kernel tests.
2. **Execution Profiles**:
   - Closed profile registry: `:semantic` (`"v3"`), `:stack` (`"b2"`), `:register` (`"r2"`), `:walker` (`"v3"`).
   - Outer body version is 2. (Versions 0 and 1 remain completely untouched and byte-identical for existing fixtures).
3. **Lift and Lower**:
   - Implement lift and lower for all four kernels under version 2 in both fork and exclusive modes per sections 4, 5, 6, 7, 8, 9 of `v2-amendment.md`.
   - Structural AST continuation grammar for walker; sparse register pairs for register VM; ordered image layout for stack/register relocations.
4. **Verification**:
   - Implement the 12 acceptance laws defined in Section 11 of `v2-amendment.md`.
   - Run `clojure -M:test -n yin.vm.ucf.handoff-test -n yin.vm.ucf.checkpoint-test`.
   - Ensure `mise exec -- cljstyle check` is clean.
   - Ensure `clj -M:kondo` has 0 errors.

Report your changes, decisions, and exact test results in:
`collab/1791357500000-compiler-engineer-ucf-d10b-b-kernel-lift-lower.claude-opus-5-5.findings.md`
