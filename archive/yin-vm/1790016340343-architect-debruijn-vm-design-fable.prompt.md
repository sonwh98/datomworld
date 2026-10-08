Created-GMT: 2026-09-21 18:45:40 GMT
Created-Local: 2026-09-22 01:45:40 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 50b423bc-219f-40c0-8e56-2b6d9c3d9658
# Task: debruijn-vm-design-fable — architectural review of the de Bruijn VM design
Role: Architect (independent architectural review)
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-22 01:45:40 +07 | Status: active | Rationale: cross-family ARCHITECTURAL reviewer (the design was authored by gpt-5.6-sol); fable is reserved for architectural review and its budget resets 2026-09-22 04:00 +07, so it must be spent now, with focus

Read-only, plan mode. Work in /Users/sto/workspace/datomworld (your launch
directory). You can only Read files and run `git diff` / `git status`. Give the
complete review now as your final response; do not wait for approval and do not
promise a verdict. BUDGET IS TIGHT (fable has about 11 percent left): read only
the files named here, do not chase source line ranges, and do not repeat the
line-level source verification a prior reviewer (claude-opus-5, four passes) has
already done. Your value here is the ARCHITECTURAL view.

## Subject

docs/design/yin.vm.debruijn-vm.md (untracked, ~463 lines): a design for a VM that
executes a lowered, name-resolved (de Bruijn) instruction form, coexisting with
the current semantic VM. Architecture as it now stands: a lossless invertible
tree/DAG encoding of the NAMED `:yin/*` datoms (with a name table); an executable
`:yin.debruijn.code/*` image DERIVED by adapting `yin.vm.linearize/lower`'s output
(`:var` rewritten via the public `resolve-name`, `:closure` params replaced by
arity); a frame-indexed sibling VM; the merged projection
(docs/design/yin.vm.debruijn-projection.md) kept ONLY as the alpha-equivalence
identity layer; phases B0-B6; section 7.1 Unison prior art; section 7.2 a future
reference-by-hash linker as a stream topology; owner rulings applied (no Unison
runtime interop goal; the linker's boundary is dao.stream, local or remote is
just a stream; lambda lifting/ANF an optional upstream AST-to-AST stage).

## Read (in this order, and only these)

1. docs/design/datom.world.md (axioms, non-negotiable invariants).
2. docs/design/yin.vm.debruijn-vm.md (the subject, in full).
3. docs/design/datom.md (only the dimension-protocol part: descriptor bundle,
   content hash as identity, projection and lift morphisms).
4. docs/design/yin.vm.debruijn-projection.md (status paragraph, sections 1, 4, 9).
5. docs/design/yin.vm.semantic.md sections 1-2 (axioms compliance and the linear
   executable datom spec), and skim docs/design/yin.vm.code-as-tuples.md section 2
   and docs/design/yin.vm.universal-continuation-format.md status/overview.

## Judge as ARCHITECT

1. **Axiom and invariant compliance.** Does the design honour: everything is a
   stream; interpretation creates semantics (one truth, many perspectives); code
   and state are datoms; everything is a continuation; no callbacks, no hidden
   global state, no shared mutable state, no collapsing interpretation and
   execution, no assumed graphs? Point at any sentence or mechanism that violates
   or strains one (for example the VM's frame environment, the cache, the free
   environment, image persistence, the name table as a side structure).
2. **Is the new instruction dimension legitimate?** Under datom.md's dimension
   protocol (descriptor bundle, content hash identity, projection/lift morphisms)
   does `:yin.debruijn.code/*` satisfy or evade the protocol? Note the projection's
   earlier lesson: its descriptor was "defined and exported", not persisted or
   published, and the design doc was corrected to say so; make sure this design
   does not repeat an overclaim.
3. **Boundaries and coexistence.** Two executable forms and two VMs will now exist.
   Is that the right long-term shape, or should it be one instruction dimension
   with lexical addressing as an optional operand encoding? What is the
   maintenance, divergence and verification cost, and does the differential-test
   backbone plus derived-by-adaptation (not a second traversal) sufficiently bound
   it? What is the real benefit that justifies a second VM (frame-indexed
   environment, fingerprint/image caching, path to hash linking)? Say plainly
   whether the design states that benefit honestly.
4. **The lossless-encoding-plus-projection split.** The owner first believed the
   projection was a bijection; it is not (many-to-one by design). The design now
   keeps three artifacts: the named datoms (bijective source), the lossy projection
   (identity), and the derived executable image. Is that decomposition coherent
   and minimal, or is one artifact redundant? Is the invertibility invariant on
   the lossless DAG worth its cost, and is its named home (B0) right?
5. **Linker and streams (7.2).** Is the stream-topology framing of the future
   linker consistent with the axioms, and does anything in B0-B6 foreclose or
   assume it? Are the three things streams do not decide (authoritative ledger,
   SCC identity, retry policy) correctly left as owner decisions?
6. **Phasing and reversibility.** Are B0-B6 the right cuts for independent review
   and rollback? Is any owner decision missing that would surprise the owner later?
7. **Top three risks** to the project if this design is implemented as written.

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 50b423bc-219f-40c0-8e56-2b6d9c3d9658
then a verdict: SOUND, SOUND WITH CHANGES, or NOT SOUND; findings as P1 (an
architectural violation or a wrong boundary), P2 (a significant gap or overclaim),
P3 (minor), each with the section, the quoted sentence and the smallest fix; the
answers to items 1-7; and what you checked and found clean. Findings only; edit
no file.
