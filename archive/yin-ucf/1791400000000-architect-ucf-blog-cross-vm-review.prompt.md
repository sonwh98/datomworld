Created-GMT: 2026-10-09 04:40:00 GMT
Created-Local: 2026-10-09 11:40:00 Asia/Bangkok

# Task: Architecture review of the UCF parallel-transport essay, Part Three (cross-VM transport)

Role: Lead System Architect

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-09 11:40 +07 | Status: active | Rationale: independent non-Claude family; the text under review was drafted by a Claude orchestrator from an owner discussion, so a Claude model must not be the sole reviewer.

Perform a read-only architecture review of the public essay
`public/chp/blog/hygienic-parallel-transport-universal-continuation-format.blog`
(hiccup EDN; the prose is in the string literals), at commit `22dcbcc5`
on this worktree's branch. Parts One and Two were written by the owner.
Part Three ("Transport to a Different Machine") and one sentence added to
"The Lineage" were drafted today from an owner discussion and are the
primary object of review; the whole essay is in scope for consistency.

You are working in a git worktree. Use the paths below relative to the
current directory; do not look outside it.

Read first:
- docs/design/datom.world.md
- public/chp/blog/hygienic-parallel-transport-universal-continuation-format.blog
- docs/design/yin.vm.universal-continuation-format.md, at least the header
  blockers, §7.1, §7.2, §7.3 (code identity, the two load paths, the
  positional-vector ruling in §7.3.2), §7.4.2 (foreign-engine safepoint
  maps), §7.5 (the value grammar, all four subsections), §7.6.2 (store slice)
- docs/design/yin.vm.semantic.md §4.1 (the configuration, `St`)
- docs/design/yin.vm.code-as-tuples.md
- docs/design/yin.vm-in-dao.space.md
- docs/design/dao.jing.cbor.md (objective and invariant section)

## Owner rulings the essay encodes (quoted verbatim)

These are the owner's exact words from the discussion. The essay's Part
Three is the orchestrator's rendering of them; judge whether the rendering
is faithful, and say so if a plausible alternate reading exists.

> "The universal AST is the canonical code . The semantic vm bytecode as
> tuples is an isomorphic maping of the universal AST into flat tuples .
> Cross-VM safepoint should be at the semantic vm level not the AST node
> level"

> "I thought CESK model doesn't have a stack?"

> "if E,S are the main problem, then the problem is narrow: how to
> normalize E, S across different VMs?"

> "does CBOR solves the normalization problem of E,S, St?"

> "what if E, S, St are represented as tuples?"

> "this is only for parallel transport of the code and continuation state.
> once it lands on another vm, it will be a local representation to make
> execution native to the destination vm"

Orchestrator's paraphrase of the decisions (separate from the quotes
above): (1) cross-VM safepoints stay at `(segment-hash, pc)`, an AST-level
safepoint was proposed and rejected; (2) the cross-VM problem reduces to
the value-holding components E, S, St and captured envs in K; (3) CBOR
normalizes bytes, the §7.5 grammar normalizes references, and primitive
semantics on the receiving VM is a third layer neither covers; (4) the
transported state becomes a tuple set in which each entity is either
content (hashed) or identity (tempid, remapped), collapsing §7.5's value
table, cell table, cycle refusal and literal wrapping into one mechanism;
(5) the tuple set is the transport form only, the destination lowers into
a native representation and executes natively; (6) conformance for a VM is
lift, lower, and parity rows, with full safepoint coverage required and
effects still routed through the fenced writer.

## Evaluate

1. **Faithfulness.** Does Part Three say what the owner's quoted rulings
   say, no more and no less? Flag any sentence that extends a ruling into
   territory the owner did not rule on, and any place where the essay's
   justification differs from the spec's own stated reasons.
2. **The isomorphism claim.** The essay rests the safepoint decision on
   "the instruction vector is an isomorphic image of the Universal AST".
   Check this against `yin.vm.code-as-tuples.md` and §7.3. Is it an
   isomorphism in the sense the essay needs (every safepoint in the vector
   corresponds to exactly one AST point and vice versa), or only a
   deterministic projection? If only a projection, does the decision still
   hold and should the wording change?
3. **The `St` paragraph.** Is the account of the operand stack as the image
   of K's argument-holding half under linearization correct against
   `yin.vm.semantic.md` §4.1 and the linearizer, including the claim that
   frames keep only a `:stack-base`?
4. **The three-layer claim about CBOR.** Does §7.5 actually sit above Jing
   as described, and is "a third layer neither covers" a fair reading of
   §7.5.2 (a name is a binding, not a meaning) and the parity harness?
5. **The tuple-set decision.** Is it sound to say sharing, cycles, forgery
   and the value table collapse into entity references, with the residue
   being a per-entity content-or-identity mark? Specifically: (a) does
   content-addressing an entity set preserve the aliasing §7.5.3 protects
   for cursor cells, under the stated rule; (b) is the essay right that the
   §7.3.2 ruling (positional vector, not EAV, for code) does not conflict
   with EAV for state; (c) does any of the six non-negotiable invariants
   (no hidden global state, no implicit control flow, no raw callbacks, no
   shared mutable state, no layer collapsing, no assumed graphs) object to
   the tuple form, in particular "no assumed graphs" and "no layer
   collapsing" between dao.space and the VM.
6. **The transport-only boundary.** The essay reads
   `yin.vm-in-dao.space.md`'s "the transition function is Datalog" as the
   as-of view over the tuple set at rest, not the execution model. Is that
   reading consistent with that document's own persistence-boundary
   section, and does the essay misstate anything that document claims?
7. **Conformance.** Are lift, lower, and the three parity rows a complete
   and non-contradictory statement of what a foreign VM owes? Is requiring
   full safepoint coverage (every canonical safepoint a native safepoint)
   the right call, or does it over-constrain targets the project cares
   about (Dart isolates, Wasm)? Is "effects through the fenced writer"
   sufficient as stated?
8. **The geometry in Part Two**, owner-written, in light of Part Three. The
   essay calls the resumption race "holonomy" and the epoch "the gauge".
   Lift/lower is deterministic and path-independent; duplicate resumers are
   copies of one value because a stream read does not consume. Is
   "holonomy/curvature" a defensible reading, or would a gluing/sheaf
   reading (multiple sections over one point, the lease as the gluing
   condition) be more accurate? Report this as an essay-accuracy finding,
   not a defect; the owner has not ruled on it.
9. **Status.** Does the essay overclaim what is implemented? Stages D and E
   of the UCF implementation have landed; the tuple-set form has not. Check
   the status paragraph at the end of Part Three is sufficient and that
   nothing earlier in the essay reads as shipped when it is design.

Distinguish architectural defects (the decision is wrong or conflicts with
an invariant or a landed contract) from essay-accuracy issues (the decision
is fine but the text misstates it) from intentionally deferred work. Do not
edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then report: severity | file:line | invariant/evidence | recommended correction.
Also confirm the requested properties that passed review, and end with
one line: VERDICT: READY_TO_MERGE | CHANGES_REQUESTED, with the changes
listed in the order they should be applied.
