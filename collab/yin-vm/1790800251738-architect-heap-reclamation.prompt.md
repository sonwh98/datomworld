Created-GMT: 2026-09-30 20:30:51 GMT
Created-Local: 2026-10-01 03:30:51 +0700
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c (resumed)
# Task: Heap reclamation design for task-heap cells (all four VMs)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-01 03:30:51 +0700 | Status: active | Rationale: owner directive "use fable ... as the architect"; author of the cell and mutable-object rulings

Read-only architecture design. Do not edit files. Work in /Users/sto/workspace/datomworld (master fe8bce4a).

OWNER (verbatim): "let's go with your recommendation" — accepting the orchestrator's plan, whose item 2 is heap
reclamation, which the owner had already ranked "first work after the spike, ahead of lift/lower" (verbatim decision:
"accept all recommendations").
Landed: cells (5e790683: :heap {id {:value :seal}}, sealed :cell-ref, cell/new|get|set!, fail-closed lift/completion),
host-typed effects D4 (9a69e58f), data module (fe8bce4a), continuations invocable (8f9f90b0). Python spike phase B
(worktree datomworld-py-spike, uncommitted) allocates a cell per local/param per activation, one per object, frame and
handler-stack cells, flag cells per capture point — so the heap grows without bound today.

Design questions:
Q1. Roots, per VM (AST walker control/env/k/value; semantic seg/pc/St/E/K/val; stack VM stack/frames/continuation;
    register VM registers/frames/continuation), plus shared state: store (all store-of stores incl. module stores),
    :parked continuations, ready-queue, wait-set, stream contents held in the task (refs appended to in-task streams),
    FFI in-flight state, :resources. Which are roots, which are values to trace through, and how refs nested inside
    persistent values and closures' envs are found.
Q2. Algorithm and trigger: stop-the-world mark-sweep over :heap vs incremental/budgeted marking; who triggers it (explicit
    caller-stepped engine function, allocation threshold recorded in state, or an effect). Must be explicit state, no
    hidden global state, no implicit control flow, bounded work per step if the heap is large.
Q3. Determinism and semantics: collection must not change program results; ids are never reused (or are they?); seal
    checks on a freed id; interaction with box semantics and multi-shot continuations (a continuation captured in a cell
    or parked keeps its cells alive); interaction with :id-counter and gensym.
Q4. Four-VM parity and portability (CLJ/CLJS/CLJD), and where the code lives (engine, shared) vs per-VM root
    enumeration; the ASTWalkerVM positional-record trap.
Q5. Interaction with later work: copy-on-lift (slice 2) reachability reuse; weakref/__del__ hooks (conform without them
    now); dependency completion's reachability; D7 host-typed closures/continuations (root enumeration must not depend
    on reading continuation maps if D7 makes them opaque — or should root enumeration be a per-VM method?).
Q6. The minimal first slice and its acceptance tests (e.g. a loop allocating cells stays bounded; cells reachable only
    from a parked continuation survive; a collected ref used later is refused, not silently reallocated; parity).
Distinguish decisions from implementation gaps; list owner decisions separately.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c
Then: one recommended design; answers Q1-Q6; owner decisions; severity | file:line | evidence | correction items.
