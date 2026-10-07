Completed-GMT: 2026-10-02 20:52:02 GMT
Completed-Local: 2026-10-03 03:52:02 +0700

# Python phase C4 design: imports, the linked prelude, and the REPL frontend catalog/SPI

Read-only design against master `69e58662`. No file was edited and nothing was run; every claim about code comes from reading it, and the ones I inferred without a test are marked "inferred".

## 0. The ruling everything else follows from

**Install delivers code; instantiation is the importing task's own evaluation.** A linked Python-side module (base prelude, hook prelude, any Python module) exports only closures and plain data. Its install child defines lambdas and halts. Cells, class objects, runtime state and module namespaces are allocated later by an explicit call in the consuming task.

This is the landed ruling taken literally ("the linker delivers content-addressed code images, never Python namespaces", `yang.antlr.md:1408-1410`). Running a Python module body inside the install child would fail four ways against the landed linker:

- **The lift refuses.** A heap `:cell-ref` is `:yin.k/non-portable :cell` (`engine.cljc:742-745`). Every Python function, class, dict and module is a cell, so no body-executed module could reach `linked`.
- **Output vanishes.** `print` at import time would write the child's `py.rt/out`, which is never published.
- **Identities duplicate.** The child gets its own lowered copy of every dependency, so builtin classes arriving in the export slice would be fresh cells and `isinstance` would fail.
- **First link wins.** Mutations the child makes to a dependency's store are discarded by the parent (`linker.md:1428-1433`).

Consequence: this design does not need heap-slice lift. The sentence at `yang.antlr.md:1414-1417` ("a linked prelude's class objects are copied per receiving task") is superseded: nothing is copied, each task instantiates.

## 1. Python imports on the landed linker

**Module shape.** A Python unit compiled as module `a.b` becomes one linker module:

```clojure
(do (require 'py)            ; hoisted: code delivery only
    (require 'pym.c)         ; one per static import in the unit
    (yin/def spec {:name "a.b" :package? false})
    (yin/def body (fn [%globals %globals-fn] <lowered module body>)))
;; :yin.module/exports #{spec body}
```

- **Requires are hoisted and pinned.** Every static import becomes a module-level `(require 'pym.x)`. Installs are pure, so hoisting is invisible to Python; it puts free names like `pym.c/body` in the shape step 5a already discharges, and `:yin.module/requires` pins each dependency by manifest address. The pins are derivable from the rows, so no new manifest key.
- **Execution stays lazy.** `import c` lowers at the statement to `(py/import "c" pym.c/spec pym.c/body)` plus the binding. The body runs in the importing task, in statement order, under that task's declared effects.

**Resolution order.**

1. `sys.modules` (a heap dict in prelude state): a hit returns the module object.
2. Runtime-synthesized modules (`sys`, `builtins`), pre-seeded by `py/init!`.
3. The linker: task registry hit (the code cache, `module.cljc:530`), else name environment fold → manifest → verify → pure install → receive.
4. Refusal: see ImportError below.

There is no `sys.path` and there are no finders. The "search path" is the reader's snapshot set and declared principals (`linker.dht.md` 7.1-7.4), consistent with section 9.6: a dependency edge names a content address.

**`py/import` semantics (prelude code).** On a miss it creates the module object, sets `__name__` and `__package__`, inserts it into `sys.modules` before running the body, and runs the body. On an exception it removes the entry and re-raises. There is no park inside `py/import`, because code was delivered at link time.

**`__name__`, `__main__`, `__package__`.**

- The same code image serves both roles. `py/run-main` instantiates it as `"__main__"`; `py/import` instantiates it under its dotted name. `if __name__ == '__main__'` needs nothing else, and the code address does not depend on the role.
- `__package__` comes from `spec`. Package-ness is source layout, not derivable from rows, so `spec` is legitimate unit metadata.
- `__file__` is absent (as for CPython builtin modules); `__spec__` and `__loader__` are `None`.

**Relative imports.** Resolved statically at lowering from the unit's declared module name (section 5.1: source units are explicit). A relative import in a unit with no declared package lowers to code that raises `ImportError` at that statement, preserving `try/except` behaviour.

**Packages.**

- `import a.b.c` requires and imports `a`, `a.b`, `a.b.c` in order and sets each as an attribute on its parent.
- `from m import *` is a prelude loop over the ordered key vector into the importer's dict.
- Restriction: `from pkg import submodule` does not auto-import the submodule, because lowering cannot know it exists; the support profile records it.

**Names.** The registry nests dotted names (`module.cljc:33-74`, `engine.cljc:132-137`), so a module named `a` shadows `a.b` (inferred, untested). Until the linker seat fixes that, no linker module name may be a dotted prefix of another:

- base prelude: `py`
- hook prelude: `pysp`
- Python module `a.b.c`: `pym.a$b$c` (`$` is valid EDN and never in a Python identifier, so the mangling is injective)

**Circular imports.** Python-level cycle semantics fall out for free: insert-before-execute, and "cannot import name" on a partially initialized module. Code-delivery cycles cannot be pinned by content (a pins b pins a), and the linker refuses `:require-cycle` (`module.cljc:533-537`).

- First: refuse at publish with a diagnostic naming the cycle.
- Later: the publisher emits one linker module per strongly connected component exporting `body$a`, `body$b`, plus one thin alias module per member that re-exports its body. This uses only landed mechanisms (re-exported closures keep their origin and `:store-of`) and needs no SCC-identity ruling.

**ImportError.** A link refusal is raised as the effect's error (`engine.cljc:975-978, 1714`). The VM has no guest-catchable form of it, so `try: import x / except ImportError` cannot work for a module absent at link time. This is the one engine addition C4 needs: a `module/try-require` whose refusal resumes as plain data, the `stream/poll!` precedent (decision 5). Until it lands, an absent dependency refuses the dependent's link before any Python runs, and the support profile says so.

**`importlib`.**

- Supported: `import_module` with a literal name; `reload`, which re-runs the same image against the same dict (the registry hit answers forever, so new code needs a new name-environment snapshot).
- Refused with an explicit unsupported error: finders, loaders, `meta_path`, `path_hooks`, `__import__` override, non-literal names. A non-literal name would be an unpinned dependency and needs a dynamic binding read the engine does not have.

## 2. `sys.modules` and the module namespace

The linked module's store does not back the namespace dict.

- **Module store:** holds `spec` and `body`, immutable after install.
- **Module object:** a heap cell `{:py/type :module :name s :dict d}`, where `d` is the same dict the body receives as `%globals`.
- **`setattr`, `del`, `globals()` writes:** dict operations on the heap. The linker's store model (literal keys, no delete, first link wins) is untouched.

Consequences:

- Two tasks importing one module get independent namespaces, consistent with "one Python process is one task".
- The open "cross-task module stores" decision (`linker.md:2389`) is not needed.
- Module state migrates only when heap lift lands, as already true for any Python task.
- The link-time `:bindings` snapshot (likely stale after a store write; inferred) never matters, because consumers read only closures and plain data through it.

## 3. The linked prelude

**Shape.**

- **One source, two emitters.** The definition list in `prelude.cljc` stays the single source. The bundled emitter is today's. The module emitter strips the module's own namespace from keys and internal references, because export keys must be bare.
- **State behind `py/init!`.** The module-level `state-definitions` (`prelude.cljc:1729-1739`) move into `py/init!`, which writes one state slot in the active module store and is idempotent. Entry wrappers call it; install children never do.
- **Builtins become a namespace dict** built by `py/init!`. A global miss falls back to it, which is Python's own model.
- **Linked entry wrapper:** `(do (require 'py) (py/init!) (py/run-main (fn [%globals %globals-fn] ...)))`.

**What breaks.**

- **Addresses change once.** Every golden moves, because the wrapper and the builtin reads change.
- **Publish refuses the prelude today.** A free name bound by a host module is `:yin.link.publish/host-module` (`publish.cljc:294-295`), and the prelude calls `cell/*`, `data/*`, later `integer/*`. Linker-seat work is required.
- **The AST walker cannot link it.** A module whose exported lambda reads a sibling definition is refused `:undeclared-free` on the tree format (`linker.dht.md:969-975`). Python acceptance is four VMs, so this deferral must lift or the linked profile claims three.
- **Safepoint load order inverts.** Today the hook prelude loads first and may only allocate (`safepoint.cljc:18-23`). Linked, `pysp` requires `py`. Its cursor cannot be created at install, and a module closure cannot read the ambient `py.sp/signals` (no ambient fallback, `linker.md:1438-1447`). So the wrapper passes the stream to `(pysp/attach! signals)`.
- **Imported modules run uninstrumented.** Site marks live in the frontend-metadata side table, not in rows, so a published tree carries none. A `while True` in imported code is not interruptible until site sets are published.
- **Atomicity (8.11) holds, and gets stronger.** The stage never sees prelude rows, and `py/import` adds no park. Decision 6 ("revisit when linked") resolves to keep marks: structure still cannot tell a loop from a function.

**What gets simpler.**

- The lowering drops `builtin-names` and direct `py.b/*` reads; adding a builtin class no longer touches `lower.cljc`.
- A user program's address no longer depends on the prelude revision, so the "prelude grows, every unit's address changes" notes at `yang.antlr.md:1830` and `:2688` end.
- `isinstance` across units in one task works.
- The data and integer modules stay host modules (no host function arrives by linking), but the prelude manifest declares them under `:yin.module/primitives` by profile address and step 5b enforces it. That answers the C3-S1 note that `module-version` is inert data.
- The hand-kept `host-names` sets become derivable from the tree.

**Selectable.** The bundled profile stays selectable until the migration gate: the linked corpus green on four VMs and three hosts. Then delete it; no compat shim.

## 4. REPL frontend catalog/SPI

**Today.** `yin/repl.cljc` has a closed `case` over `:clojure`, `:python`, `:php` (`:1578-1584`), a `lang-labels` map (`:240`), `:clojure` special cases (`:1587-1596`, `:1693-1697`), and static requires of all three frontends (`:18-20`). Its `:python` is the legacy `yang.python`, not the ANTLR frontend. The ANTLR parser exists only on the JVM (`src/clj/yang/python/antlr/parser.clj`).

**Design.**

- **`yang.frontend` (new, pure cljc):** manifest validation, `install catalog manifest binding => catalog'`, selection by `[id revision]`. The catalog is a value. A manifest carrying a function or handle is rejected.
- **The REPL takes the catalog from its composition** (`create-state` option). Each host's `main` builds it; `yin.repl` requires no frontend. `(lang x)` selects from the session's pinned catalog snapshot. Shell commands remain the shell's own syntax.
- **The binding is a pair of stages over supplied streams** (parse, lower) plus an optional completeness probe. The REPL steps them to an outcome. A parser worker or remote service is the same shape, so swapping one in does not change the REPL; a slow service needs a pending-compile state like the existing pending-require.
- **No fallback between frontends.** `:yang.python/antlr` and `:yang.python/legacy` are distinct ids. On Node and Dart, where no ANTLR artifacts exist, selecting the ANTLR frontend answers a qualified unavailable-parser outcome.

**What Python's profile pins.**

| Part | Pin |
|---|---|
| Grammar | `grammar-id` (`parser.clj:16-19`), entry rules, export profile `:yang.cst/v1` |
| Lowering | declared revision plus golden-corpus digest (host code has no content address) |
| Runtime | `py` and `pysp` manifest addresses, safepoint profile map, host-module profile addresses (cell, data, integer with limits, stream poll), permitted effects |
| Support | derived from `unsupported-rules` (`lower.cljc:78`); reference runtime CPython 3.9.6 |

**A second language (JavaScript, parked)** installs as: generated parser artifacts, a lowering namespace, a published prelude module, a manifest, and one `install` call in the composition. No edit to `yin.repl`, Yang core, or the evaluator.

## 5. Slices and acceptance contracts

Hosts: JVM, Node, Dart. Node and Dart consume precompiled CST packets or rows, since the parser is JVM-only. VMs: all four unless stated.

**Linker seat (prerequisites, not this seat's to build).**

- **L-a:** host-module requirements in a published manifest, by profile address. Blocks P2.
- **L-b:** step 5a for module-level reads in the tree format. Blocks the walker under the linked profile.
- **L-c:** registry nesting fix. Optional; mangling covers it.
- **L-d:** `module/try-require`. Blocks I4.
- **L-e:** D7 slice B, the origin/store check. Blocks any foreign-principal import claim.

| Slice | Scope | Acceptance (setup → action → assertion) | Decision needed |
|---|---|---|---|
| F1 | `yang.frontend` catalog and manifest validation | Empty catalog → install two revisions of one id → both selectable, original catalog unchanged. Manifest holding a fn → rejected with a qualified outcome. All three hosts. | Follows from section 3 |
| F2 | REPL selects through the catalog; `yin.repl` drops frontend requires | A toy frontend defined in the test namespace → install, `(lang :toy)`, evaluate → value on every VM with no `src` edit. Live session, newer revision installed in the composition → session behaviour unchanged. Node/Dart: `(lang :yang.python/antlr)` → unavailable-parser text, no legacy fallback. | Follows from section 3; legacy id naming is owner's |
| P1 | Single-source prelude, `py/init!`, builtins dict; still bundled | Full corpus → run → output identical to before on every VM and host. `functions-uast` in a composition with no cell module → halts ok. `py/init!` twice → one set of class cells. Shadow then `del` a builtin name → builtin visible again. | Builtins-as-dict is owner's |
| P2 | Module emitter, publish, linked profile | Publish `py` on each host → manifest address equal to one pinned constant. Corpus under linked profile → output equals bundled (three VMs; walker after L-b). Same source under two prelude revisions → same program root. Two units in one task → exception from unit 1 is `isinstance` of `Exception` read in unit 2. Install child at `validated` → empty heap, lift has no `:cell` refusal. Wrong host-module profile → refusal naming it. No name-env entry → refused, no bundled fallback. | Section 0 ruling is owner's; needs L-a |
| P3 | `pysp` linked, `attach!` | Safepoint slice 1 acceptance list re-run under the linked profile on every VM. Stage input → contains no prelude row. Derived program with no `pysp` binding → unresolved hook name. | Follows |
| F3 | Python ANTLR frontend in the catalog (JVM); persistent `__main__` | REPL: `x = 1`, then `print(x)` on the next line → `1`. Multi-line `def` → probe answers incomplete until the block closes. | `__main__` persistence is owner's; needs P2 |
| I1 | Static absolute imports, single modules, `py/import`, module objects, `sys.modules`, `__name__` | Module `m` prints and defines `f` → importer runs `print("a"); import m; print("b")` → order `a`, m's output, `b`. Second `import m` → no re-execution. `m.x = 5; del m.y` → visible through `m.__dict__` and to `f`. Body raises → entry removed, same exception propagates. Function in `m` raises, importer catches → handler runs (continuation across `:store-of` contexts), every VM. Same image as main and as import → `__name__` differs. All three hosts, plain-map name env. | Follows once section 0 is accepted |
| I2 | Packages, mangling, relative imports, `as`, `*`, `__package__` | `import a.b.c` → three bodies run once, in order, attributes set. Relative import in a packageless unit → `ImportError` caught by `except`. `from pkg import sub` without explicit import → the documented error. | Auto-import restriction is owner's |
| I3 | Publish and DHT import | JVM publishes `pym.m` under a signed name → Node and Dart readers import it parserless → same output. Dependency republished → `:yin.link.dht/dependency-binding`, no install. Cyclic pair → publish refusal naming the cycle. | Needs L-e to claim foreign principals |
| I4 | Catchable `ImportError` | Absent module inside `try` → `except ModuleNotFoundError` branch runs; ambiguous name → `ImportError` carrying the refusal. | Owner decision plus L-d |
| I5 | SCC units and alias modules | `a` and `b` import each other → both import; `from b import x` during partial init → CPython's error text. | Owner decision |
| I6 | `importlib.import_module` (literal), `reload`; hooks refused | `reload(m)` → body re-runs against the same dict, identity of `m` unchanged. Finder registration → explicit unsupported error. | Owner decision |
| I7 | Site sets for imported modules | Imported `while True` with one signal → `KeyboardInterrupt`. | Owner decision |

## 6. Interactions and order

- **float64 carrier (queued, not landed):** hard prerequisite for P2. The prelude manifest must have one address on every host, and integral float literals currently hash differently on Node.
- **In-flight prelude churn (C2-S2, safepoint s2, C3-S2):** each adds builtin classes or reshapes runtime state in `prelude.cljc`. P1 lands serially after them, then later slices add definitions to the single source and are profile-agnostic.
- **C2-S3/S4/S5, C3-S3 onward, safepoint s3/s4:** independent of imports. They do not block I1 and I1 does not block them.
- **Imports require the linked prelude.** A module closure's free reads never see the ambient store, so a bundled importer's `py/*` definitions are invisible to an imported body. I-track starts after P2.
- **D7 slice B:** until it lands, isolation is incomplete (`orchestrator-log.md:9096`). A forged `:store-of` could reach the prelude's state. Gate for foreign-principal imports only.
- **D7 slice C (store context off the lexical env):** Python raises across module boundaries by continuation invoke on every exception. I1's cross-module raise test is the probe; if it fails on any VM, slice C blocks I1.
- **Linker hardening stages 1-5:** independent.

Order: F1, F2 now. Float fix and in-flight slices → P1 → (L-a) P2 → P3, F3, I1 → I2 → I3 → I4 to I7 as decided.

## Defects versus gaps

**Architectural defects**

- Registry nesting: modules `a` and `a.b` cannot coexist (inferred from `module.cljc:33-74`; needs a failing test first). It affects Clojure-named modules too.
- `yang.antlr.packet` (language-neutral) emits `:yang.python.antlr/malformed-cst` (`packet.cljc:11-12`). A second ANTLR language would report Python-named diagnostics.
- Link refusal has no guest-observable form.
- `yang.antlr.md:1414-1417` presumes heap copy for a linked prelude.

**Implementation gaps or deferred work**

- The REPL's closed dispatcher (a recorded Phase 0 item).
- Host-module dependencies in publish; tree-format 5a.
- No ANTLR artifacts on Node or Dart.
- Site sets for linked modules; SCC delivery; dynamic import.
- Heap-slice lift (not needed here).

## Owner decisions

1. **Pure install, instantiate in the importing task** (prelude, hooks, Python modules). Recommend: adopt, and amend `yang.antlr.md:1414-1417`.
2. **Builtins as a namespace dict** built by `py/init!` (costs a second lookup on a global miss). Recommend: adopt.
3. **Naming:** `py`, `pysp`, `pym.<a$b$c>` versus waiting for a registry fix. Recommend: mangle now and file the nesting defect with the linker seat.
4. **Hoisted, pinned requires for static imports** (eager code delivery, lazy execution). Recommend: adopt.
5. **Bundled profile lifetime.** Recommend: selectable until the linked corpus passes on four VMs and three hosts, then deleted.
6. **AST walker under the linked profile.** Recommend: require L-b before the migration gate rather than publish a three-VM claim.
7. **Host-module requirements by profile address in the manifest** (L-a), with integer limits inside the profile. Recommend: adopt; it settles the C3-S1 versioning note.
8. **`module/try-require`** (refusal as a value). Recommend: adopt for I4; until then record the restriction in the support profile.
9. **Circular imports.** Recommend: refuse at publish first; SCC unit plus alias modules in I5.
10. **Dynamic import names.** Recommend: refuse non-literal names in C4.
11. **`importlib` surface.** Recommend: `import_module` (literal) and `reload` of the same image; refuse finders, loaders, `sys.path`, `__import__` override.
12. **Relative imports resolved statically** from the unit's declared module name. Recommend: adopt.
13. **`from pkg import submodule` auto-import.** Recommend: restricted, with an explicit error.
14. **Safepoints in imported modules.** Recommend: publish site sets as datoms in the publisher's index (I7), with no manifest schema change; accept uninstrumented imports until then.
15. **Safepoint decision 6 revisit.** Recommend: keep frontend marks.
16. **REPL catalog.** Session pins a catalog snapshot; `:yang.python/legacy` and `:yang.python/antlr` are separate ids with no fallback. Recommend: adopt; retire legacy at its migration gate.
17. **Lowering revision pin.** Recommend: declared revision plus golden-corpus digest.
18. **Persistent `__main__` across REPL inputs.** Recommend: adopt; `(reset)` is a new Python process.
19. **Foreign-principal imports.** Recommend: not claimed until D7 slice B lands.
