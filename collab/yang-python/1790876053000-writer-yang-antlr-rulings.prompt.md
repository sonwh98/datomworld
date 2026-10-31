Created-GMT: 2026-10-01 17:34:40 GMT
Created-Local: 2026-10-02 00:34:40 +07 (+0700)
Coding-Agent: claude
Session-ID: c9cabf75-804a-41c3-9da8-bf1efba15b76

# Task: Record the safepoint, C2 generators and C3 bignum rulings in yang.antlr.md

Role: Scoped Writer (docs)

Implementers:
- Model: claude opus (opus-5.5) | Assigned: 2026-10-02 00:34:40 +07 (+0700) | Status: active | Rationale: same writer line as the earlier yang.antlr.md ruling records; doc-only change

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-yang-doc (branch docs-yang-antlr-c2-c3-sp, based on master
1df123d1). Edit ONLY docs/design/yang.antlr.md. Do not touch /Users/sto/workspace/datomworld, any other worktree, or
any other file. Do not stage or commit.

SOURCE MATERIAL (all under this worktree's collab/):
1. 1790849347715-architect-safepoint-interpreter.claude-fable-5-1.findings.md — the safepoint-interpreter design; its
   eleven owner decisions were accepted verbatim on 2026-10-01 17:37 +07 ("accept all recommendations"): (5) a generic
   :stream/poll effect; (6) sites from frontend marks; (7) identity = canonical tree, evaluator runs the derived tree;
   (8) no signal-delivery journalling in slice 1; (9) count-based green-thread switches; (10) sys.settrace without a
   tracing profile raises an explicit unsupported error; (11) slice order signals, recursion, tracing, threads (with
   the engine/poll, marks, identity and journalling points likewise accepted). The design's own finding asks that
   yang.antlr.md's existing "attached interpreter" decision line be expanded to record marks, hooks, the poll effect
   and the identity rule — that is this task.
2. 1790874900000-architect-python-c2-generators-design.claude-fable-5-1.findings.md — the C2 generator design, and
   1790875890000-architect-c2-generators-crossruling.gpt-6-astra.findings.md — the architect-pair mob's converged
   rulings (binding; the owner delegated decision authority, verbatim: "If there are questions you need from me, then
   mob between gpt-6-astra and fable-5.1").
3. 1790874940000-architect-python-c3-bignum-design.gpt-6-astra.findings.md — the C3 bignum design, and
   1790875860000-architect-c3-bignum-crossruling.claude-fable-5-1.findings.md — the mob's 14 converged rulings
   (binding on the same authority).

TASK: integrate the three ruling sets into docs/design/yang.antlr.md, following the document's existing conventions
for recording rulings (see how the cell/mob, mutable-object and mappability rulings were recorded in §2/§8/§11 and how
phase scope is stated in §12). Requirements:
- Every recorded statement must trace to a finding or a converged ruling; do not invent semantics.
- Distinguish decided-and-recorded from not-yet-implemented: where the design or rulings say a slice is pending, say
  the ruling is recorded with implementation in its named slice, exactly as the document already does for earlier
  pending phases. Never describe unlanded code as landed.
- Record the safepoint decisions where the mappability/§8 lowering decisions live, including: marks from the frontend,
  the generic :stream/poll effect, hooks as ordinary applications, the per-language hook prelude, identity = canonical
  tree, no journalling in slice 1, count-based thread switches, the settrace unsupported error, and the slice order.
- Record the C2 generator design at the level of the document's other paradigm decisions: per-generator handler stack
  with boundary frame, two explicit continuation invocations, tagged outcomes and protocol-boundary translation,
  prelude + lowering arms with no VM change, the S1-S5 slice sequence, and the mob's strengthening conditions.
- Record the C3 rulings: exact-integer carriers and per-host boundaries, reuse of Jing's major types 0/1 and CBOR
  tags 2/3 with no new payload kind, the versioned :pure integer module, reduced-rational numeric keys, P=2^61-1
  guest hashing, value-based is, conversion requirements, the explicit numeric-limits profile data, no stream-codec
  widening, and the acceptance matrix (four VMs x three hosts, goldens, mutation evidence).
- ASCII only, lines <= 80 columns, table style consistent with the document.
- If a ruling genuinely cannot be stated without violating the document's structure, note it as an Open: question
  instead of forcing it.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Report the sections touched with a one-line summary each, the ruling-to-section mapping, and any Open: questions.
