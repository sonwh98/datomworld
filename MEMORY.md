# Datomworld Rules and Guides

## Core Philosophy
- [CLAUDE.md](./CLAUDE.md) — Development rules, core philosophy, build & test commands
- [@.claude/rules/datom-spec.md](./.claude/rules/datom-spec.md) — Datom 5-tuples, entity IDs, content addressing, namespaces
- [@.claude/rules/architecture.md](./.claude/rules/architecture.md) — Streams, agents, Yin.VM CESK machine, compilation pipeline, runtime macros

## Design and Development
- [@.claude/rules/malleability.md](./.claude/rules/malleability.md) — Code evolution through bounded complexity, clustering, loose coupling, testing strategy
- [@.claude/rules/vocabulary.md](./.claude/rules/vocabulary.md) — Domain vocabulary from biology, economics, physics, philosophy for naming and stigmergic emergence
- [@.claude/rules/advanced-concepts.md](./.claude/rules/advanced-concepts.md) — Parallel transport across DaoDB nodes, capability tokens, entanglement

## Project State
- [Macro system](./docs/macros.md) — Spec for compile-time and runtime macros
- VM implementations:
  - `src/cljc/yin/vm.cljc` — Core protocols and primitives
  - `src/cljc/yin/vm/semantic.cljc` — Semantic interpreter for AST datoms
  - `src/cljc/yin/vm/ast_walker.cljc` — AST walker interpreter for in-memory maps
  - `src/cljc/yin/vm/register.cljc` — Register-based bytecode VM
  - `src/cljc/yin/vm/stack.cljc` — Stack-based bytecode VM
  - `src/cljc/yin/vm/macro.cljc` — Macro expander
  - `src/cljc/yin/vm/engine.cljc` — Shared engine: resolution, effects, scheduling

## Analyzing Dependency Coupling

**Namespace-level analysis:**
```bash
clj ./bin/analyze-deps.clj
```

Reports:
- Circular dependencies (A → B → A)
- High-degree nodes (over-coupled namespaces with 6+ connections)
- Strongly connected components (tightly coupled clusters needing extraction)
- Recommendations for breaking cycles and reducing coupling

**Function-level analysis (visibility):**

For any public function, check if it's called from outside its namespace:
```bash
grep -r "namespace-name/function-name" src/
```

If 0 results or only matches in the same file → mark the function private (defn-) or delete it.

See @.claude/rules/malleability.md for full guidance.

## Collaborating with Claude Code

**Development process:**
- Write tests before implementing features (TDD)
- See CLAUDE.md "TESTING" section for the three-phase approach

**When asking Claude Code to work on code:**
1. Reference the relevant rule: malleability for refactoring, vocabulary for naming, architecture for structural changes
2. Check CLAUDE.md for non-negotiable invariants and testing guidelines
3. Use vocabulary.md when proposing new namespace names
4. Use malleability.md when discussing extraction, clustering, or interface design
5. Run `./bin/deps-graph.sh` and share output for coupling analysis
