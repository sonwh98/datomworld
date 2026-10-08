Created-GMT: 2026-09-30 14:34:26 GMT
Created-Local: 2026-09-30 21:34:26 +0700
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c (resumed)
# Task: Mutable guest objects and collections — identity, aliasing, and copy semantics over cells

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-30 21:34:26 +0700 | Status: active | Rationale: owner directive "use fable ... as the architect"; author of the cell ruling and mob seat (context retained)

Perform a read-only architecture design review. Do not edit files. Work in /Users/sto/workspace/datomworld.

OWNER (verbatim): asked "have we decided on how mutations will work?"; after the orchestrator reported variables decided
but objects/collections not explicitly ruled, the owner said "yes, ask the architect".
Owner direction (verbatim, binding): "compilers have a pipeline of transformation. yang/yin.vm compilation pipeline is
dynamic where interpreters read from dao.stream and make transformation onto another dao.stream. any number of
interpreters can attach to those dao.stream to do more transformation of its own" and "if yin.vm universal AST has
continuations, all control flow can be mapped to continuations".

Settled (your cell ruling + mob D1-D10; do not reopen without new evidence): variables — globals via yin/def; every
function-local binding and parameter boxed in a task-heap cell (cell/new|get|set!, sealed :cell-ref, :heap);
copy-on-lift across tasks; box semantics under multi-shot continuations; un-boxing is an optional attached interpreter;
D4 host-typed effects in progress (collab/1790778658842-vm-engineer-host-typed-effects.prompt.md).

Open question: how do mutable guest OBJECTS and COLLECTIONS work? Orchestrator's reading (a paraphrase; challenge it): a
mutable object is a cell holding a persistent value (logical identity = the cell), per yang.antlr.md §8.1 "Aliased guest
objects share logical identity, not host mutable objects" and §9.4 "records, store cells, and closures".

Rule on:
Q1. Representation of a mutable object/collection with identity (Python list/dict/set/instance, JS object/array,
    Java object/array): one cell per object holding a persistent value? one cell per field/slot? something else?
    Cost of copy-on-write of the whole value per mutation vs per-slot cells.
Q2. Aliasing and identity operations: a = []; b = a; b.append(1) -> a sees [1]; Python "is", JS ===, Java == on
    references, id()/hash identity. What is compared, and is it portable across CLJ/CLJS/CLJD?
Q3. Value-semantics languages: PHP arrays copy on assignment (observable copy), PHP &$x references and objects by
    handle; Go structs copy, slices alias a backing array. How does each map onto cells vs plain persistent values?
Q4. Cycles and object graphs: self-referencing objects (a.append(a), parent/child links) through cells; interaction with
    copy-on-lift (aliases/cycles preserved within one lift, per D1), reclamation (none in slice 1), and dependency
    completion.
Q5. Where the semantics lives: prelude (Universal AST) vs cell module vs new effects; per-language value encoding and
    the portable-value interop profile (yang.antlr.md §8.10: None/null/undefined not conflated). Keep the Universal AST
    grammar unchanged unless you show it cannot be avoided.
Q6. Does anything here change cell slice 1's scope (e.g. cell identity/equality ops, a cell/swap! or update form, cell
    holding host-typed values), or can it all be prelude code over slice 1? Minimal additions for a Python spike that
    needs list, dict, and simple class instances.

Evaluate invariants (no hidden global/shared mutable state, derive-don't-persist, peer observers), four-VM parity,
CLJ/CLJS/CLJD portability, performance risk, and contradictions. Distinguish decisions from implementation gaps.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c
Then: one recommended design; answers Q1-Q6; owner decisions listed separately; any
severity | file:line | invariant/evidence | recommended correction items.
