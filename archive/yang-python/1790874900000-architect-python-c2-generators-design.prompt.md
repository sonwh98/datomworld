Created-GMT: 2026-10-01 17:15:10 GMT
Created-Local: 2026-10-02 00:15:10 +07 (+0700)
Coding-Agent: claude
Session-ID: e9543cd5-12e4-4812-8367-617982d54ea5

# Task: Design Python generators (phase C2) over the landed D7 continuations

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-02 00:15:10 +07 (+0700) | Status: active | Rationale: architecture authorship; author of the mappability and D6/D7 rulings this design builds on

Perform a read-only architecture design for Python phase C2: generators. Author the full design in your response;
findings only — do not edit any file.

Read first (main tree at /Users/sto/workspace/datomworld, master 111a9823; read-only — another seat's uncommitted work
is present there, judge committed state and say so if a working-tree file matters):
- docs/design/datom.world.md (the six non-negotiable invariants)
- docs/design/yang.antlr.md (§1.1 stream discipline, §8 lowering, §11 determinism and isolation, §12 roadmap)
- docs/design/yin.vm.universal-continuation-format.md (continuations, escapes, dynamic context)
- /Users/sto/workspace/datomworld/collab/1790797984227-architect-python3-mappability.claude-fable-5-1.findings.md
  (your own ruling: Python needs one-shot escapes/generator resume)
- /Users/sto/workspace/datomworld/collab/1790849347441-architect-d7-host-typed-closures-continuations.claude-fable-5-1.findings.md
  (host-typed Closure/Continuation, owner tag, qualified refusals, the flag-cell pattern)
- src/cljc/yang/python/antlr/lower.cljc and src/cljc/yang/python/antlr/prelude.cljc (post-C1 state: finally/with
  escape wrappers, py/call-ec flag-cell captures, handler-stack restore)
- src/cljc/yin/vm/values.cljc (the landed D7 host types)

Questions the design must answer:
1. Representation: what a generator object is — cells, the suspended continuation, frame state; the iterator protocol
   (__iter__/__next__), StopIteration and its value payload, exhaustion.
2. yield as a crossing: explicit continuation invoke at an explicit program point versus the py/call-ec flag-cell
   pattern; what the dynamic context (handler stack, with/finally unwind state) does across suspend and resume.
3. yield from delegation, send/throw/close, GeneratorExit, and interaction with the C1 finally/with machinery when a
   yield sits inside a try/finally or with body.
4. Generator expressions versus the C1 comprehensions (eager or lazy today); scoping of the first iterable; laziness.
5. Heap and reclamation: a suspended generator's state must be traced (gc-children); what happens to a generator
   dropped mid-suspension; cell pinning rules.
6. Identity and wire: does the canonical tree change, is any new ledger record needed, UCF impact; how a guest-visible
   generator reports identity.
7. Portability: JVM/JS/Dart specifics; tail-mark interaction (a resumed generator loop must not grow continuations);
   what sys.settrace sees (or explicitly does not see, deferring to the safepoint design).
8. Implementation sequencing: the slices for the engineer, each with acceptance test contracts (setup/action/assertion
   per host), following the test style of yang.antlr.md §13 and the C1 corpus.

Distinguish architectural defects from implementation gaps or intentionally deferred work. Do not edit files.

End with an "Owner decisions" section enumerating every open decision with your recommendation. The owner has delegated
tonight's decision authority to the architect pair: each recommendation will be cross-ruled by gpt-6-astra, and
converged rulings are recorded as architect rulings in the orchestrator log.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
