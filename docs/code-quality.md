# Namespace Dependency Graph & Code Quality

## Usage

```bash
# Full report: prints metrics table, writes dep-graph.dot
clj -M:dep-graph

# Filter to specific namespaces (regex)
clj -M:dep-graph --filter "yin.*"
clj -M:dep-graph --filter "daodb.*"

# Render to SVG (requires Graphviz: brew install graphviz)
dot -Tsvg dep-graph.dot -o dep-graph.svg
```

## Reading the output

### Console table

```
Namespace                                  Ca   Ce        I Circular?
---------------------------------------------------------------------
datomworld.core                             0    8     1.00
yin.vm                                      6    0     0.00
```

| Column | Meaning |
|--------|---------|
| Ca | Afferent coupling (fan-in): how many project namespaces depend on this one |
| Ce | Efferent coupling (fan-out): how many project namespaces this one depends on |
| I | Instability: Ce / (Ca + Ce). Ranges from 0.0 (maximally stable) to 1.0 (maximally unstable) |
| Circular? | YES if the namespace participates in a dependency cycle |

### DOT graph

- **Node color**: green (I=0.0, stable) through yellow (I=0.5) to red (I=1.0, unstable)
- **Node label**: abbreviated namespace name, Ca, Ce, I
- **Grey edges**: normal dependencies
- **Red edges**: dependencies that form a cycle

## Judging code quality

The dependency graph reveals structural quality without reading a line of code.

### What to look for

**Layered (healthy):** edges flow in one direction, from unstable (red, top) to stable (green, bottom). Clear strata. Changes at the top don't ripple downward. Changes at the bottom are rare but affect many dependents, which is fine because stable modules have simple, well-tested interfaces.

**Spaghetti (rigid):** edges go in all directions. Circular dependencies. No clear layering. A change anywhere can cascade everywhere. High cost of modification.

**Clustered (healthy variant):** islands of tightly related namespaces connected by a few bridge edges. Each cluster is internally cohesive, clusters are loosely coupled to each other.

### Instability is role-dependent

There is no universally "good" value for I. What matters is whether the value matches the namespace's role:

| Role | Expected I | Why |
|------|-----------|-----|
| Foundation / primitives | 0.0 | Many dependents, no outward dependencies. Stable base. |
| Mid-level library | 0.3 - 0.7 | Some dependents, some dependencies. Balanced. |
| Application entry point | 1.0 | Depends on everything, nothing depends on it. Free to change. |

A foundation with I=1.0 is a problem: your primitives depend on the world. An entry point with I=0.0 and Ca=0 is likely dead code.

### The Stable Dependencies Principle

Dependencies should flow toward stability. High-I namespaces depend on low-I namespaces, not the other way around. If an unstable namespace depends on another unstable namespace, both are fragile. If a stable namespace depends on an unstable one, it inherits that instability.

Check: for every edge A -> B, is I(A) >= I(B)? Violations indicate structural risk.

### Warning signs

- **Circular dependencies**: always a problem. Two namespaces that depend on each other cannot change independently. Extract the shared concern into a third namespace.
- **Isolated namespaces** (Ca=0, Ce=0): either dead code or namespaces with only external dependencies. Investigate whether they're still needed.
- **God namespaces** (very high Ce): a namespace that depends on everything is doing too much. Split it into focused modules.
- **Fragile foundations** (high Ca and high Ce): a namespace that many others depend on but that itself depends on many things. Changes here cascade everywhere. Reduce its Ce by extracting dependencies.

### Using the graph over time

Run the tool periodically and compare. Healthy evolution looks like:

- Cycle count stays at zero
- New namespaces appear as clusters, not as new edges on existing tangles
- Stable foundations remain stable (their Ce stays low)
- The graph becomes more layered, not more tangled

If a PR adds edges that create cycles or flatten the layering, that's a structural regression regardless of whether the code "works."
