Created-GMT: 2026-09-30 19:53:04 GMT
Created-Local: 2026-10-01 02:53:04 +0700
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c (resumed)
# Task: Confirm or correct — is there anything in Python 3 that cannot be mapped to the Universal AST?

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-01 02:53:04 +0700 | Status: active | Rationale: owner directive "use fable ... as the architect"; context of cell/mutable-object rulings retained. Note: the analysis under review was authored by the Claude orchestrator (same family); this is design confirmation, not code sign-off.

Read-only. Do not edit files. Work in /Users/sto/workspace/datomworld.

OWNER (verbatim): asked "is there anything in python3 that cannot be mapped to the Universal AST?", then, after the
orchestrator's answer below, "ask the architect to confirm that analysis".

Evidence available: the Python spike phase A report (worktree /Users/sto/workspace/datomworld-py-spike,
collab/1790795118566-compiler-engineer-python-antlr-spike.claude-opus-5-5.report.md — read it; you may need to read it
via that absolute path), your cell and mutable-object rulings, master 5e790683 (continuations invocable 8f9f90b0; D4
host-typed effects 9a69e58f; cell slice 1 5e790683), docs/design/yang.antlr.md (§5, §8.1, §8.5, §8.7, §8.10, §9).

ORCHESTRATOR'S ANALYSIS UNDER REVIEW (verbatim):
"No — nothing in the Python 3 *language* is fundamentally beyond the Universal AST. With first-class multi-shot
continuations (all control), cells (mutation/identity), closures, and effects (the host boundary), it can express Python
semantics. The real limits are cost, host coupling, and datom.world's own invariants.
1. Expressible in UAST + prelude, not built yet: yield/generators (continuation swapping + a cell for the resume point);
   async/await (a job-scheduler interpreter over continuations, per yang.antlr.md §8.7); finally, with (escape wrappers
   that run pending cleanup, dynamic-wind style); comprehensions, tuples, slices, **, %, //, bitwise, in; default/keyword/
   *args/**kwargs calling conventions; decorators, multiple inheritance (C3 MRO is prelude code), properties/descriptors,
   metaclasses, dunder protocols, del; arbitrary-precision ints (prelude arithmetic or a :pure host primitive with
   identical results on all hosts).
2. Expressible only through host effects: I/O, files, sockets, time, random, os, subprocess; import (module resolution as
   an effect with recorded inputs; the pure-Python stdlib is Layer 1 code); eval/exec/compile (a new compilation request
   on the stream, as yang.antlr.md §8.6 says for PHP eval).
3. Possible, but needs VM infrastructure that does not exist yet: frame introspection (sys._getframe, inspect, live
   locals(), tracebacks with lines) — continuations would need to be exposed as inspectable data plus source-position
   side tables; weakref, __del__, gc — need reclamation plus finalizer hooks; id() stability (accepted as unstable
   across lifts); recursion limits / RecursionError (no stack-depth semantics).
4. Not faithfully mappable: C extensions (numpy, ctypes, native modules) — only via an FFI effect to a real CPython, a
   separate compatibility service; shared-memory preemptive threading — conflicts with no-shared-mutable-state; the
   faithful-enough mapping is green threads on one heap-owning interpreter (GIL-like); true parallelism means tasks +
   message passing; CPython implementation details (refcount timing, sys.getrefcount, small-int/string interning seen by
   is, bytecode/dis/code objects)."

Rule on:
Q1. Is the headline claim correct: nothing in the Python 3 language reference is inexpressible? Name any construct that
    genuinely is (or that the four buckets misplace), with the reason.
Q2. Check each bucket for misplacements and omissions. Candidates to consider explicitly: generator send/throw/close and
    try/finally inside generators; async generators and async with/for; exceptions: __context__/__cause__ chaining,
    tracebacks, exception groups (except*), KeyboardInterrupt; the match statement; walrus and scoping of comprehension
    variables; class body execution and __set_name__/__init_subclass__/__class_getitem__; super() zero-argument form and
    __class__ cell; descriptors on classes vs instances; __slots__; operator fallback (__radd__ etc.); float semantics
    (IEEE, NaN, -0.0) and str/bytes/Unicode normalization; hash randomization; dict/set iteration order guarantees;
    globals()/locals() writes; exec with custom namespaces; importlib hooks; signals; the GIL's atomicity guarantees
    that some programs rely on.
Q3. Which items interact with existing decisions or invariants (continuation portability/serialization, D7 host-typed
    continuations and the spike's py/kont?, cells and copy-on-lift, reclamation, peer observers, derive-don't-persist)?
Q4. Anything in the spike's documented deviations that is actually a mapping limit rather than phase-A scope?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c
Then: verdict on the headline (CONFIRMED / CONFIRMED WITH CORRECTIONS / REJECTED), a corrected bucket table, answers
Q1-Q4, owner decisions if any.

NOTE (orchestrator, at dispatch): the spike report is also at collab/1790795118566-compiler-engineer-python-antlr-spike.claude-opus-5-5.report.md in this (main) tree; read that copy.
