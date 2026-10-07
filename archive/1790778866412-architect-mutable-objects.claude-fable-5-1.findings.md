Completed-GMT: 2026-09-30 14:36:11 GMT
Completed-Local: 2026-09-30 21:36:11 +07
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c

# Architect ruling: mutable guest objects and collections

Read-only review; no file edited, nothing executed this round. Claims about cells are design claims, since cell slice 1 is not yet on master.

## Recommended design

**A mutable guest object is one cell holding one persistent value; the object's identity is the cell.** The orchestrator's reading is right, with four refinements.

1. **The guest reference is the bare `:cell-ref`.** The type tag and payload live inside the cell's content, so `type(x)` answers the same through every alias.
2. **Immutable guest values get no cell.** Numbers, strings, tuples, `None` and the like are plain tagged persistent values.
3. **A slot gets its own cell only when the language lets that slot itself be aliased.** PHP reference slots are the case. Go interior pointers need no extra cell: they are a `(cell, path)` pair.
4. **All of it is prelude code in Universal AST over `cell/new`, `cell/get`, `cell/set!` and pure data primitives.** No new effect, no grammar change, no change to cell slice 1's operations.

A mutation is `cell/get` → pure update → `cell/set!`. Persistent structures make the "copy" a path copy, not a whole-value copy.

## Q1. Representation

**One cell per object.**

| Option | Ruling | Why |
|---|---|---|
| One cell per object, persistent value inside | **Adopt** | `assoc` and `conj` are path copies, so a mutation costs two effect dispatches plus one pure call regardless of size. |
| One cell per field or element | Reject as default | A list of n elements means n allocations, n seals and n heap entries. Dicts with dynamic keys need a container anyway. |
| Host mutable objects | Forbidden | Violates "no shared mutable state" and `yang.antlr.md:1003`. |

- **Content shape:** a tagged header plus payload, for example `{type-tag, items}` for a list or `{class-ref, attrs}` for an instance. The exact encoding belongs to each language's runtime profile.
- **Classes are objects too.** Class attributes are assignable, so a class is a cell, and an instance holds a ref to it.
- **No atomic update form is needed.** A task switches only at park points, and the prelude evaluates operands before the get/set pair, so nothing interleaves between them.
- **Cost, as a risk:** `obj.method()` is roughly three or more effect dispatches (instance, class, bases), each on the slow effect path. Acceptable for the spike; record it as a measurement.

## Q2. Aliasing and identity

- **Aliasing falls out.** `a = []; b = a; b.append(1)`: both variable cells hold the same ref, so both see the update.
- **Identity comparison is `=` on two refs.** That is true iff same cell, because a ref is data (`id` plus a seal derived from it). It is portable across CLJ, CLJS and CLJD because it never touches host object identity. Python `is`, JS `===` on objects and Java `==` on references all lower to it.
- **This becomes a stated invariant.** If refs later become host types (mob D7), `=` on refs must still mean same-cell on every host.
- **Guest value equality is prelude code.** Python `==` on lists recurses through cells. It must never be host `=` on a value containing refs, which would compare elements by identity.
- **Immutables:** `is` on immutable values is defined as same type and value. Python's language reference permits this. Java boxed objects and `new String` require distinct identity, so a Java profile gives them cells.
- **Identity as a dict key:** the ref itself is a valid host map key. No id extraction is needed for default instance hashing.
- **`id()`:** not needed for the spike. Cell ids are task-local and re-minted on lift, so a number obtained before a lift is stale after it. Document as a limitation.
- **Functions:** a closure compared with `=` compares structurally, so two closures from one lambda with equal environments are equal. JS and Python require them distinct, and both allow attributes on functions. Full fidelity wraps a function as an object in a cell. The spike uses bare closures and leaves `is` on functions unsupported.

## Q3. Value-semantics languages

| Language feature | Mapping |
|---|---|
| PHP array | Plain persistent value stored directly in the variable's cell. Assignment copies in O(1); the observable-copy rule is free. |
| PHP object | Handle = cell ref. |
| PHP `&$x`, `use (&$x)`, `f(&$x)` | A variable cell holds either a value or a reference marker pointing at a shared cell; reads dereference one level. `$b = &$a` moves `$a`'s content into a fresh shared cell and sets both variables to the marker. |
| PHP reference to an array element | The same marker sits in the array slot. It survives array copy, which is PHP's real behaviour. |
| Go struct, array | Plain persistent value; assignment copies. |
| Go `&x` | The variable's existing cell ref, since every local is boxed. |
| Go `&s.f`, `&a[i]` | A `(cell, path)` pair: read is a nested get, write is a nested update plus `cell/set!`. |
| Go slice | Header by value (`backing-ref`, offset, length, capacity); the backing array is in a cell. |
| Go map, channel | Cell. |
| Java object, array | Cell each. |

PHP reference binding cannot be a lexical rebinding of the name: a continuation captured earlier would still see the old binding. That is why the marker lives in the variable's cell.

## Q4. Cycles and graphs

- **Persistent values stay acyclic.** `a.append(a)` stores a's ref inside a's content. The cycle exists only through heap ids, so host printing, hashing and equality never loop. Guest `repr` and `==` need their own guards, as in Python.
- **Copy-on-lift:** the heap slice is pulled by reachability, with the portable id reserved before contents are traversed (the correction I accepted from astra). Aliases and cycles inside one lift are preserved.
- **Lift size:** one reference to one object pulls its whole reachable graph, including its class and the class's methods.
- **Reclamation:** objects raise its priority. Every temporary list is a heap entry for the life of the task. A collector must trace refs nested inside cell contents, the way `completion/abstract-value` already walks collections (`completion.cljc:112-114`). Finalizers and weak references are out of scope.
- **Dependency completion:** cell contents hold closures (methods, bound methods), so each pulled cell is abstracted and contributes work items. Context finiteness still holds.

## Q5. Where the semantics lives

- **Prelude (Universal AST):** object layout, attribute lookup, method resolution, guest equality and hashing, list, dict and set operations, reference markers.
- **Cell module:** only allocation, read, write.
- **Runtime profile per language:** value encoding. Each language's null is its own tagged sentinel, never host `nil` shared across languages (`yang.antlr.md:1236-1238`).
- **Cross-language calls:** a cell ref crosses as an opaque object handle under the §8.10 interop profile. The receiver may hold it and pass it back but not read its content without an adapter.
- **Grammar:** unchanged. Every operation is an ordinary `:application`.
- **Pipeline fit:** the lowering emits naive prelude calls. Inline caches, field-slot layouts or un-boxing are separately attached interpreters writing their own stream, per the owner's direction.

Two constraints on the prelude:

- **Never iterate a host map.** Python dicts and PHP arrays are ordered; host maps are not, and iteration order differs by host. An ordered dict is an index map plus an order vector.
- **Normalize dict keys in the profile.** Host `=` on numbers differs by host: `1` and `1.0` are distinct on the JVM and identical on JS and Dart, while Python requires `1`, `1.0` and `True` to be one key.

## Q6. Effect on cell slice 1

**The operations do not change.** No `cell/swap!`, no identity operation, no `cell/id`.

- `cell/swap!` is rejected: applying a guest closure inside `handle-effect` would need continuation frames the engine does not build there.

**Additions to slice 1, tests only:**
- `=` on two refs is true iff same cell, on all four VMs and three hosts.
- A ref works as a map key and inside nested values.
- A cell whose content contains its own ref.

**A separate small prerequisite for the spike:** the standard registry has no `count`, `dissoc`, `pop`, `subvec`, `contains?`, or any string operation (`vm.cljc:351-391`). List deletion, dict removal and length cannot be written without them. Add them as a `:pure` host module through `register-host-module` (`module.cljc:190`), named in the Python runtime profile. This is a registry addition, not a grammar change.

**Spike scope:** list, dict with insertion order and normalized scalar keys, simple classes with single inheritance, instances with identity hashing. User-defined `__hash__`/`__eq__` keys, `id()`, function identity and attributes on functions wait.

## Owner decisions

1. **Identity of immutables.** Define `is` on immutable values as same type and value. Python allows it; a Java profile cannot use it. I recommend confirming it per language profile.
2. **`id()` stability.** Accept that a guest-visible id is not stable across a lift, or forbid exposing it. I recommend accepting and documenting.
3. **Where the pure data primitives live.** A host module named by the runtime profile (my recommendation) or additions to the standard `vm/primitives` registry, which changes every composition.
4. **Reclamation priority.** With objects in the heap, I recommend it moves ahead of lift/lower as the first work after the spike.

## Findings

| Severity | File:line | Invariant / evidence | Recommended correction |
|---|---|---|---|
| medium, implementation gap | `src/cljc/yin/vm.cljc:351-391` | The standard registry cannot express list deletion, dict removal, length, or strings, so the prelude cannot be written over it. | Add a `:pure` host data module before the spike's prelude. |
| medium, portability risk | `docs/design/yang.antlr.md:989-990` | "Do not inherit Clojure equality" is stated but no profile defines dict-key normalization; host number equality differs across JVM, JS and Dart. | The Python runtime profile defines key normalization and guest equality explicitly; add a cross-host parity test. |
| medium, doc gap | `docs/design/yang.antlr.md:1003-1005`, `:1308-1314` | "Logical identity" and "store cells" are named but the representation is never stated. | Record this ruling in §8.1 alongside the D3 amendment: one cell per object, refs as identity, slots get cells only when aliasable. |
| low, design constraint | mob D7 (host-typed refs) | Identity comparison relies on `=` over ref data. | Any D7 design must keep `=` on refs as same-cell and keep refs usable as map keys, on every host. |
| low, performance risk | `src/cljc/yin/vm/engine.cljc:1814-1820` | Every effect emits a telemetry snapshot; attribute access is several effects. | Measure in the spike; promotion to tags or opcodes stays the later remedy. |
