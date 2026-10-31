Created-GMT: 2026-09-21 18:13:15 GMT
Created-Local: 2026-09-22 01:13:15 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: bbeae799-313d-4447-8264-714c09a5f1ca
# Task: debruijn-vm-design — adversarial review of the de Bruijn VM design
Role: Adversarial Review (VM Runtime)
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-22 01:13:15 +07 | Status: active | Rationale: cross-family reviewer — the design was authored by gpt-5.6-sol (GPT family); the Claude pool refreshes 2026-09-22 04:00 +07 so this spends expiring budget

Read-only review, plan mode. Work in /Users/sto/workspace/datomworld (the launch
directory; everything below is inside it). Produce the complete review now as
your final response; do not wait for approval and do not promise a verdict.
You can only Read files and run `git diff` / `git status`; you cannot search, so
Read the files named here directly, in the order given.

## Subject

docs/design/yin.vm.debruijn-vm.md (untracked, ~412 lines): a design for a VM that
executes a LINEARIZED DE BRUIJN instruction stream, coexisting with the current
semantic VM that executes `:yin.code/*` lowered from the NAMED AST. The owner's
goal: "a VM that runs the linearized de Bruijn encoding too." It builds on the
merged de Bruijn projection (docs/design/yin.vm.debruijn-projection.md and
src/cljc/yin/vm/debruijn.cljc).

## Read, in this order

1. docs/design/yin.vm.debruijn-vm.md (the subject, in full).
2. docs/design/yin.vm.debruijn-projection.md (the projection it builds on; the
   status paragraph and sections 2-5 and 9).
3. The named-path reality the design claims to be equivalent to:
   - src/cljc/yin/vm/engine.cljc (bind-params, resolve-var, and how calls,
     closures and the environment work),
   - src/cljc/yin/vm/semantic.cljc (the CESK machine: `:var`, `:closure`, `:call`,
     `:return`, tail calls, park/resume, continuation capture, stream ops),
   - src/cljc/yin/vm/linearize.cljc (how AST datoms lower to `:yin.code/*`:
     order, out-of-line lambda bodies, labels, tail flags, `:yin.code/source`),
   - src/cljc/yin/vm/code.cljc (the instruction set, per-op operand tables, the
     validator).
4. docs/design/yin.vm.semantic.md sections 2-5 (the instruction and CESK
   contract), and src/cljc/yin/vm/debruijn.cljc for the projected record shapes,
   the scope pair convention and `project-datoms`.
5. As needed: docs/design/yin.vm.universal-continuation-format.md,
   docs/design/yin.vm.code-as-tuples.md, docs/design/dao.stream.md,
   docs/design/datom.world.md (axioms and non-negotiable invariants).

## What to attack (rank findings by severity)

1. **The equivalence contract.** The design promises the same results, errors,
   effects, tail calls, continuations, park/resume, gensym, macros, and
   free-variable resolution as the named path. Find every place where a frame
   stack with `[depth pos]` addressing could diverge from the named path's
   NAME-keyed merged environment `(merge (:env closure) (bind-params ...))`:
   shadowing, duplicate parameters, under- and over-arity, a bound name whose
   value is nil (present) versus absent (fall through to store/primitives),
   closures capturing an environment that is later extended, names bound in the
   VM's own initial/global environment (are those "free" in the projection but
   found in env at run time, and does the design keep the env, store,
   primitives, registry order?), `:macro?` lambdas, `:vm/store-get`/`store-put`
   keys that collide with variable names, and gensym.
2. **Lowering a hash-consed DAG.** The design decides how to lower shared
   subterms. Check that `[depth pos]` stays correct where the same subterm
   record is shared under different lexical contexts, that duplicating at each
   use cannot blow up exponentially (the projection had exactly this bug class
   in its memo, fixed in the merged code), that tail position is recomputed
   correctly per occurrence, and that out-of-line lambda bodies get correct
   frames.
3. **Scope validation** of `{:bound [d p]}` (the projected reader has none): is
   the specified validation complete, placed where it cannot be bypassed, and
   consistent with arity?
4. **The VM design**: frame representation, closure capture, call/return, tail
   calls, continuation reification and resume, park/resume, stream and FFI ops,
   and the claim that it needs no change to existing protocols or code. Check
   every claim the design makes about existing code (names, functions, line
   ranges, op tables, behaviour) against the actual sources and list each that
   is WRONG or unverifiable.
5. **The new instruction dimension** (`:yin.debruijn.code/*`): its descriptor,
   its relationship to `:yin.code/*` and the validator in code.cljc, content
   addressing, and whether B0-B6 are independently reviewable with real
   completion criteria and a differential-test backbone that would actually
   catch divergence.
6. **Section 7.1** (reference-by-hash as a stream topology, a later epic): is the
   stream framing consistent with the axioms and invariants, and does anything in
   B0-B6 wrongly foreclose it or wrongly assume it?
7. **Form and honesty**: the document should follow the projection design's
   conventions (numbered sections, invariants, phases with completion criteria,
   test matrix; ASCII box tables, no markdown pipe tables, no em dashes, 80
   columns). Flag violations, and flag any claim that outruns what is implemented.

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: bbeae799-313d-4447-8264-714c09a5f1ca
then a verdict line: READY FOR OWNER DECISIONS or NOT READY, then findings as
P1 (the design is wrong or would produce a divergent or unsound VM), P2 (a
significant gap or an unverifiable or false claim about existing code), P3
(wording, form, minor). For each: the section and quoted sentence, the failing
scenario (a concrete program where the two VMs would differ, when you can
build one), and the smallest fix. Say explicitly what you checked and found
clean. Findings only; do not edit any file.
