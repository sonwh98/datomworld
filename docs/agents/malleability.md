---
name: Malleability as evolutionary fitness
description: Code evolution through bounded complexity, loose coupling, and natural selection pressure
type: project
---

# MALLEABILITY: CODE THAT ADAPTS

**Malleability** = how cheaply can code adapt to changing requirements without breaking promises?

This is the only objective measure of code quality. Requirements always change. Code that adapts survives; code that resists change gets rewritten or abandoned.

## High Cohesion, Loose Coupling

- **High cohesion**: Things that change together should live together.
- **Loose coupling**: Things that change independently should be isolated.

Tightly coupled code requires understanding the entire dependency graph before changing anything. Loosely coupled but cohesive code allows local changes without affecting the rest of the system.

### Measuring Coupling: Dependency Graphs

This is objective and measurable:

```bash
clj ./bin/analyze-deps.clj
```

This script generates the dependency graph and analyzes it in one step:

**Generates:**
- `dep-graph.dot` — structured text format describing the dependency graph
- `dep-graph.svg` — visual SVG representation

**Understanding the Report:**

When a namespace is listed as `namespace-name (in:X, out:Y)`:
- **`in:X`** = X other namespaces depend ON this namespace (incoming edges)
  - High `in` is good for foundational modules (like `dao.stream`, `yin.vm`)
  - High `in` means this module is central/widely used
- **`out:Y`** = this namespace depends ON Y other namespaces (outgoing edges)
  - Low `out` is better (fewer dependencies = less coupling)
  - High `out` combined with high `in` is problematic: many things depend on it, but it has many dependencies itself

**Architectural vs. Accidental Coupling:**
A high `out` count is not automatically a failure. Distinguish between:
- **Architectural Coupling**: High `out` to *highly stable foundational modules* (e.g., `dao.stream`, `yin.vm`). If the dependencies are bedrock primitives that change rarely, the coupling is stable and necessary for a layered architecture (e.g., the VMs).
- **Accidental Coupling**: High `out` to *volatile leaf modules* or *unrelated feature sets*. This is a red flag indicating a lack of cohesion and is the primary target for refactoring.

**Red flags:**
- High `in` + high `out` = many things depend on it, but it's also tightly coupled to many other things. These are candidates for refactoring.
- High `out` for a leaf module = too many dependencies, extract shared concerns

**Reports:**
- **Circular dependencies** (A → B → A): bidirectional coupling that prevents modularization
- **High-degree nodes**: namespaces with 6+ combined incoming/outgoing edges
- **Strongly connected components**: tightly coupled clusters (candidates for extraction into new namespaces)
- **Recommendations**: specific actions to break cycles and reduce coupling

**Visual interpretation (in dep-graph.svg):**
- **Layered graph** (good): Clean separation between modules, minimal cross-connections, clear flow from low-level primitives to high-level features. Layers are visually distinct.
- **Structural Clustering**: When namespaces have the same outgoing dependencies (like the various VM implementations), they are visually at the same level in the dependency graph. This is another form of clustering: clustering namespaces that share the same dependency graph, indicating a horizontal architectural layer.
- **Spaghetti graph** (bad): Dense tangles, circular loops, everything connected to everything. Visual chaos reflects architectural decay.

The graph doesn't lie. Use it to identify where extraction and decoupling are needed.

## Bounded Complexity

If cohesion and coupling are the principles, **bounded complexity** is the goal.

Bounded complexity is a natural consequence of clustering: when you extract dependent code into a namespace and expose only what's necessary at the boundary, you've created a module with bounded complexity.

The bound is **the interface surface area**: the public API of the namespace.

A small, focused public API (2-5 entry points) bounds complexity. Count public functions (`defn`, not `defn-`). Multiple arities of the same function count as one. Large public APIs mean nothing is isolated. Bounded complexity lets programmers learn code quickly and keeps changes local.

### Public vs. Private: Minimize Interface Surface Area

Any function without external dependencies should be marked private.

**The rule:**
- Mark a function **private** (`defn-`) if it is called only from within its own namespace.
- Mark a function **public** (`defn`) only if it is called from other namespaces.

This keeps the public API surface minimal. Callers see only what they need; internal implementation details stay hidden. The public interface becomes self-documenting: what's exposed is what the namespace is *for*.

**How to verify external callers:**

For any public function, check if it's called from outside its namespace:

```bash
grep -r "namespace-name/function-name" src/
```

**Interpretation:**
- **0 results**: The function has no external callers. Mark it private (`defn-`) or delete it.
- **Only matches in its own file**: The function is called only internally. Mark it private (`defn-`).
- **Matches in other files**: The function has external callers. Keep it public.

**Example:**
```bash
grep -r "yin.vm/load-program" src/
# If this returns results outside yin/vm/*, the function is used externally
# If only results are in yin/vm/*, or if grep returns nothing, mark it private
```

**Measure:**
Count the public functions (see "Bounded Complexity" for the 2-5 entry point target). If the count is high, either more functions should be private, or the namespace is doing too many things and needs to be split.

## Workflow: Start Messy, Let Structure Emerge

1. Write everything in one namespace. Don't organize prematurely.
2. Explore behavior in REPL `(comment ...)` blocks.
3. Observe which functions depend on each other: call each other, share data shapes, appear together in tests, or are always imported as a unit.
4. Extract cohesive clusters into separate namespaces when boundaries become clear. Extract them together to keep related changes local.
5. Delete stale comment blocks ruthlessly.
6. Iterate as requirements change.

(See "Selection Pressure" for testing strategy.)

### When to Extract: Extraction Trigger and Signals

**Trigger**: When a namespace reaches ~12 functions, evaluate whether extraction is needed.

**Signals of a natural cluster**:
- Functions call each other (high internal coupling)
- Rarely called from outside (low external coupling)
- Appear together in tests
- Work with the same data structures
- Share the exact same set of external dependencies (structural clustering)

**Extract when**: The structure has already emerged in your code. You're just formalizing what exists, not designing from scratch.

**Naming**: Use problem domain names (`validation`, `auth`, `indexing`), not solution domain (`utils`, `helpers`). See docs/agents/vocabulary.md for vocabulary from biology, economics, physics, philosophy.
