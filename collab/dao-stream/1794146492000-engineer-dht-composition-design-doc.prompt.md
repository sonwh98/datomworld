Created-GMT: 2026-11-08 16:1x GMT
Coding-Agent: muse
Session-ID: 05e41149-d044-4959-ae7b-98a4161eb059

# Task: draft docs/design/yin.repl.dht-composition.md — the refactor's governing spec

Role: Design-doc drafter (synthesis of two architect artifacts; NO code changes)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-08 | Status: active

## Sources (read fully, in order)

1. collab/dao-stream/1794062607000-architect-refactor-first-ruling-gpt6astra.findings.md
   (the charter: ownership boundary, minimum composition API, invariant pins,
   commit sequence, R-set adaptation, nightly protocol)
2. collab/dao-stream/1794109245000-architect-charter-supplement-gaps.gpt-6-astra.findings.md
   (the supplement: coupling inventory→dispositions, dao.jing signature contract,
   Gate A/B swappability gates, adapter dispositions, ETA)
3. Owner requirements stated in conversation (record verbatim where quoted):
   - "yin.repl depends only on dao.jing. It should not know anything about
     dao.jing.dht so that any implementation of dao.jing can be swapped out"
   - "dao.stream is framed as a stream of bytes" — dao.stream stays a byte
     stream; chunking/framing is transport-layer (the chunk-contract addendum
     was superseded by this ruling)
   - Frame budgets derive from the transport composition (never hardcoded by
     consumers); dao.space implements semantic page cuts.

## Deliverable

Write docs/design/yin.repl.dht-composition.md following the house style of
existing design docs (read one for tone: docs/design/yin.repl.dao.space-index.md):
- the dependency rule (engine ↔ dao.jing only) with the two gates as invariants;
- the ownership split (engine vs composition) with the ordering contract;
- the dao.jing surface signatures;
- the coupling inventory table with dispositions (the refactor checklist);
- the invariant pins and commit sequence with exit conditions;
- the swappability gates as acceptance criteria;
- the native-recursion family note (host helpers must not recurse over deep
  structures — 3 instances) as a related-debt section;
- the ETA table.
Ground everything in the two findings files; do not invent new design. Match
existing doc formatting conventions (numbered sections, prose over bullets
where the house style uses them).

Constraints: write ONLY docs/design/yin.repl.dht-composition.md. No code, no
commits. Begin the final response exactly with Completed-GMT / Completed-Local
and include the file's section list.
