Created-GMT: 2026-10-09 18:10:00 GMT
Created-Local: 2026-10-10 01:10:00 Asia/Bangkok

# Task: Architecture review of the UCF transported-state row schema (draft)

Role: Lead System Architect

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-10 01:10 +07 | Status: active | Rationale: independent non-Claude family; the draft was written by a Claude orchestrator from owner rulings, so a Claude model must not be the sole reviewer. Fresh thread; the essay review thread (01a12162-1813-7152-947a-0f5b3c71e7eb) holds the prior context if you need its findings, which are also on disk.

Perform a read-only architecture review of
`docs/design/yin.vm.ucf-transport-tuples.md` at commit `7b47557a` on this
worktree's branch `ucf-transport-tuples`. You are working in a git
worktree; use paths relative to the current directory.

The owner has ruled: "send it through codex, merge to master". The
document is marked Draft / proposed / not landed and will stay so after
merge; merging publishes the proposal, it does not accept it. Your review
decides whether the proposal is sound enough to publish as the design to
build against, and answers its open questions where you can.

Read first:
- docs/design/datom.world.md
- docs/design/yin.vm.ucf-transport-tuples.md (the object of review)
- docs/design/yin.vm.universal-continuation-format.md: §7.2, §7.3.2–7.3.4,
  §7.4.1, §7.4.3, §7.5 (all four subsections, including the M4 amendment
  paragraphs), §7.6.1–7.6.5, §7.11
- docs/design/yin.vm.code-as-tuples.md: §2.1–2.3, §4.1, §4.4, §7
- docs/design/yin.vm.semantic.md §4.1 (configuration, frames)
- docs/design/yin.vm.linker.md 7.3 (private resources table, module stores)
- docs/design/dao.jing.cbor.md (canonical bytes, what Jing sorts)
- collab/1791400000000-architect-ucf-blog-cross-vm-review.gpt-6.1-sol.findings.md
  (your round-1 findings on the essay; the draft cites findings 1, 2, 6, 7)
- public/chp/blog/hygienic-parallel-transport-universal-continuation-format.blog
  Part Three (the owner-approved narrative the draft formalizes)

## Owner rulings the draft encodes (quoted verbatim)

> "The universal AST is the canonical code . The semantic vm bytecode as
> tuples is an isomorphic maping of the universal AST into flat tuples .
> Cross-VM safepoint should be at the semantic vm level not the AST node
> level"

> "what if E, S, St are represented as tuples?"

> "this is only for parallel transport of the code and continuation state.
> once it lands on another vm, it will be a local representation to make
> execution native to the destination vm"

Orchestrator's paraphrase, kept separate: positional rows with merkle
ids in the code-as-tuples style; two id spaces decided per row kind;
closed row table; canonical cell numbering; validation on lower; the
three parity laws from the reviewed essay. The choice of positional rows
over EAV, the cell-numbering rule, the inside-the-hash occurrence, and
the specific row table are the orchestrator's design proposals, not owner
rulings, and should be judged as such.

## Evaluate

1. **Invariants.** The six non-negotiable invariants against the schema
   as specified, with particular attention to "no assumed graphs"
   (references are explicit ids; reachability is validated) and "no
   layer collapsing" (transport rows vs native execution; dao.space query
   over rows at rest vs the VM).
2. **Faithfulness to UCF §7.** Does every fact the nested grammar carries
   have a row or slot, with the same semantics? Walk §7.2's envelope keys,
   §7.4.1's frame, §7.4.3's pending variants, §7.5.1's production table,
   §7.5.3's cells and sharing, §7.6's store slice / module stores /
   scheduler. Name anything dropped or weakened.
3. **The two id spaces (§2).** Is "content row includes the cell id in its
   hash" the right alias-preservation rule? Does canonical cell numbering
   (§5) make `:yin.k/id` deterministic as claimed, and is the traversal
   order fully specified (every container's iteration order fixed)? Is
   the DAG-by-construction argument correct, including for `:reified` and
   `:parked-ref` rows and for module-store snapshots that reference
   closures that reference module stores?
4. **Slot kinds (§3).** Is "plain = scalar-only collection inline"
   well-defined under Jing's canonical encoding, and does it interact
   correctly with the §4.2 encoder-injectivity residuals code-as-tuples
   discloses (scalar metadata, byte arrays, print bindings)?
5. **Row table (§4).** Arity and slot correctness against the semantic
   VM's actual frame shapes; whether `:kframe`'s nil-padded slots for
   unused frame types are acceptable or whether frame types should be
   distinct row kinds; whether `:pending` should be one kind with a
   reason slot or one kind per reason; whether `:env` sorted-by-symbol
   loses anything (shadowing is not representable in a map E, confirm).
6. **Equality and hashing (§6).** Root-id equality as the equality of
   transported states; the claim that no tuple-set ordering needs
   canonicalizing; the claim about occurrence being inside today's hash.
7. **Validation (§7).** Complete enough to make lowering safe? Anything
   that must be checked before allocation that the list omits?
8. **The parity laws (§8)** restated here against your round-3/4 wording.
9. **Open questions (§9).** Answer each where the spec and invariants
   decide it; where it is an owner decision, say so and give a
   recommendation. In particular §9.1 (occurrence in or beside the hash)
   and §9.3 (fate of the nested grammar) shape the implementation phase.
10. **Positional vs EAV.** The draft's §1 reasoning for positional rows.
    Confirm or contest.

Distinguish architectural defects (must change before publishing) from
specification gaps (should be filled before an implementation brief) from
editorial issues. Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then report: severity | file:line | invariant/evidence | recommended correction.
Then your answers to §9's six questions, numbered.
Also confirm the requested properties that passed review, and end with
one line: VERDICT: READY_TO_MERGE | CHANGES_REQUESTED, with the changes
listed in the order they should be applied.
