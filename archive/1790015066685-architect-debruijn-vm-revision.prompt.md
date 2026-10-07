Created-GMT: 2026-09-21 18:24:26 GMT
Created-Local: 2026-09-22 01:24:26 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your linker-as-stream turn)
# Task: revise the de Bruijn VM design after the opus review, with an architectural reframe
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 01:24:26 +07 | Status: active | Rationale: you authored the design; an independent review returned NOT READY and the owner has supplied a reframing

Work in /Users/sto/workspace/datomworld. You MAY edit exactly ONE file:
`docs/design/yin.vm.debruijn-vm.md`. Give the complete answer now; do not wait
for approval and do not promise one.

## Input 1: the independent review (claude-opus-5, NOT READY)

Read it in full: collab/1790014395419-reviewer-debruijn-vm-design.claude-opus-5.findings.md
(5 P1, 10 P2, 7 P3; it cites sources by file and line, so verify each claim
yourself before accepting it). Its structural finding: the equivalence contract
in your section 1 cannot be met by any VM that runs the PROJECTED RECORDS,
because the projection deliberately erases what the named path uses at run time:
- canonical spelling changes literal values (integral double to long, NFC on
  strings, keyword and symbol parts, free names, store keys, ops), so
  `(/ 1.0 2)` and `(count "é")` would differ, a fingerprint-keyed image
  cache would serve one image for `(f 1)` and `(f 1.0)`, and values outside the
  canonical domain cannot be projected at all;
- `:tail?` is chosen by the front ends (yang/clojure.cljc compile-let,
  yang/python.cljc compile-suite are conservative), the linearizer only copies
  it, and the projection hashes tail and non-tail uses as one node;
- parameter names are gone, so closures compare only by arity, while the only
  existing comparison (test/yin/vm/parity_test.cljc `normalize`) compares
  closures by `:params`;
- the named path's "environment tier" is the VM's live `:env` register, which
  leaks between runs (semantic.cljc :return/:park write the callee's merged env;
  `vm-load-program` does not reset it; only `vm-eval` restores the initial env),
  so "free names resolve env, store, primitives, registry" is not a stable
  contract as written;
- scope validation can be bypassed at the loader (a shape-valid image with an
  out-of-range `[:load-bound [5 0]]`).

## Input 2: the owner's reframe (a decision for you to make precise)

The owner believed the problem was solved "because we have a bijection from the
universal AST to the de Bruijn projection". It is not bijective and cannot be:
the projection makes alpha-equivalent programs COLLIDE by design and drops binder
names, `:yin/tail?`, macro-name, source eids and tempids and canonicalizes
scalars; the named form is the bijective one (`ast->datoms` / `datoms->ast`).
The orchestrator told the owner that if a bijection is what they want, the
artifact the VM runs must be a DIFFERENT artifact from the projection: a
lossless de Bruijn ENCODING OF THE NAMED AST that keeps exact literal spelling,
front-end tail flags, a side table of binder and variable names, and provenance,
so that decoding it returns the original named AST. The projection then stays
what it is (the identity and fingerprint layer, computed from the same source),
and the executable form is produced FROM THE NAMED AST DATOMS (`:yin/*`) with the
de Bruijn indices resolved by the lowerer, not from the projected records. The
owner agreed to send that framing to you.

## Your job

Revise the design so that it (a) settles the architecture question and (b)
answers every finding.

1. **The architecture ruling.** Compare, honestly: (A) lower from the projected
   records (what your design does) with the restrictions that would be required
   to make it sound (the reviewer's smallest fixes: reject non-canonical
   programs, weaken the tail and continuation-shape guarantees, drop the
   fingerprint cache key), versus (B) a lossless, invertible de Bruijn encoding
   of the named AST datoms with a name table, lowered directly from the named
   AST, coexisting with the projection. State which you recommend and why. If
   B: define precisely (i) what is "bijective" (the invertibility invariant,
   stated as `decode(encode(ast))` equals the original named AST modulo which
   things, e.g. tempids) and make it an acceptance test; (ii) the relationship
   to the projection: what the projection still contributes (the fingerprint as
   the alpha-equivalence identity; may an image record it as metadata; why two
   programs with equal fingerprints may legitimately have different images); (iii)
   the code-image identity and cache key (a hash of the emitted image, not the
   fingerprint) and what the fingerprint may still be used for; (iv) reuse: the
   merged D1 scope resolver in src/cljc/yin/vm/debruijn.cljc already implements
   rightmost-wins resolution and `[frame-depth position]` pairs; say whether the
   lowerer reuses it or the projection's helpers, without changing them (the
   projection design section 9 still holds).
2. **Every P1, P2 and P3 of the review**: accepted (state how the revised design
   fixes it), rejected (say why, with evidence from the source), or an OWNER
   DECISION. Treat especially: the equivalence contract and what exactly is
   compared (define the normalizer as part of B0: closures, continuations,
   parked records, stream and cursor refs, the store, error ex-data);
   the live-`:env` leak (is it a named-VM defect to fix separately, or a case
   excluded from equivalence, and what is the stable definition of the free
   environment); tail position under a name-preserving encoding (copy the
   front end's flag, as the linearizer does); scope validation placement so it
   cannot be bypassed at the loader; the instruction set, which should mirror
   `code/vector-operand-table` except where lexical addressing differs; and the
   corrected claims about existing code (`bind-params`, `:tail?` only on
   `:application`, `load-vector` and `:code-aliases`, the completion adapter
   being named-VM-only, the real corpus for differential testing).
3. **Rewrite the affected sections and the phases B0-B6** so the design is
   internally consistent and each phase still has a file box, a must-not-change
   list, completion criteria and verification lanes. Keep section 7.1 (linker as
   a stream topology) and correct the review's precision points there. Keep the
   document's conventions (numbered sections, ASCII box tables, no em dashes, 80
   columns) and its status line ("design; not implemented"). Move operational
   routing (Claude pool, Gemini capacity) OUT of the document; that belongs in
   the orchestrator log. Do not edit any other file.
4. **List** the sections you changed, every finding you rejected with reason, and
   the OWNER DECISIONS that remain, ranked by how much they block B0.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then: the architecture ruling in two paragraphs; the finding-by-finding
disposition table (ASCII); the sections changed; the remaining owner decisions.
