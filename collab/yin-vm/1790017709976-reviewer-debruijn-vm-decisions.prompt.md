Created-GMT: 2026-09-21 19:08:29 GMT
Created-Local: 2026-09-22 02:08:29 +07 (Indochina Time)
Coding-Agent: cmd (qwen/qwen3.8-max) | deepseek (see the launch line; this brief is shared by two independent reviewers)
Session-ID: pending (provider-generated) for cmd; caller-generated for deepseek (see stdout log name)
# Task: debruijn-vm-decisions — independent review of the design's decisions against two invariants
Role: Adversarial / Routine Review (VM Runtime, Architecture)
Implementers:
- Model: qwen/qwen3.8-max (Qwen family, via cmd) and deepseek-v4-pro (DeepSeek family) | Assigned: 2026-09-22 02:08:29 +07 | Status: active | Rationale: the design was authored by gpt-5.6-sol and reviewed by the Claude family (opus-5 four passes, fable-5-1 once); the owner authorized these two other families (qwen3.8-max chosen over laguna-s-2.1) for an independent look at the final decisions

Read-only. Work in /Users/sto/workspace/datomworld. You can read files and run
read-only git commands; do not edit or create any file, and do not run tests.
Produce the complete review now as your final response; do not wait for approval
and do not promise a verdict.

## What you are reviewing

docs/design/yin.vm.debruijn-vm.md (untracked, ~500 lines): a design for a yin.vm that
executes a name-resolved de Bruijn instruction image derived from the NAMED AST
datoms, coexisting with the current semantic VM, so that code can be shared over
`dao.stream`. It was just updated to record decisions D1-D7 (the architect's
ruling and rationale are in
collab/1790017487131-architect-debruijn-vm-decisions.gpt-5.6-sol.findings.md).

The OWNER'S INVARIANT, verbatim: "I want de Bruijn projection so that a yin.vm can
easily share code over dao.stream linker." Reading adopted by the design: the LOSSY
projection (docs/design/yin.vm.debruijn-projection.md, merged) is the alpha-
equivalence identity layer; what a yin.vm executes and shares is an EXECUTABLE IMAGE
derived from the named datoms with the same de Bruijn resolution, whose content
identity is alpha-invariant for binders (binder names outside the hash), so
equivalent programs share one content address that any yin.vm can obtain, verify
and run through the stream linker (a process between streams; local or remote is
just a stream).

The SECOND SET of invariants: docs/design/datom.world.md (axioms and
non-negotiable invariants: no hidden global state, no implicit control flow, no
callbacks, no shared mutable state, do not collapse interpretation and execution,
no assumed graphs; "derive, don't persist"; a host boundary is a stream boundary;
an adapter takes no function to invoke).

## Read (in this order)

1. docs/design/datom.world.md
2. docs/design/yin.vm.debruijn-vm.md (in full)
3. collab/1790017487131-architect-debruijn-vm-decisions.gpt-5.6-sol.findings.md
4. Only if you need to check a claim: docs/design/yin.vm.debruijn-projection.md,
   docs/design/yin.vm.semantic.md sections 1-2, src/cljc/yin/vm/linearize.cljc,
   src/cljc/yin/vm/code.cljc, src/cljc/yin/vm/content.cljc.

## Answer (be concrete; quote the sentence you are judging)

1. **Invariant I.** Does the design, as decided, actually let a yin.vm easily share
   code over the dao.stream linker? Walk one concrete scenario end to end: host A
   lowers a program, publishes the image, host B (a different runtime, e.g. Dart)
   receives it over a dao.stream, verifies it against its hash, and runs it. Say
   what happens at each step, which design mechanism covers it, and where the
   chain has a gap or an unstated assumption (for example: what B does if the image
   uses a scalar class B lacks; how B obtains the image when it only knows H; what
   the free names inside the image resolve to on B).
2. **datom.world invariants.** For each non-negotiable invariant and "derive, don't
   persist", find any sentence or mechanism in the design that violates or strains
   it (loaded-image state, the frame environment, the diagnostic side table, the
   name environment, request emission, image caching). Check the design's own
   compliance table for dishonesty or omissions.
3. **The decisions D1-D7.** For each, is it consistent with the rest of the
   document (no leftover text contradicting it), and does it serve invariant I?
   Flag any decision that is under-specified enough to block B0, or that will
   surprise the owner later.
4. **Internal consistency and form.** Contradictions between sections, phases B0-B5
   that depend on something dropped, stale text, and form (80 columns, no em
   dashes, ASCII tables, status "design; not implemented", no model-routing text).
5. **Top three risks** to the goal "share code over dao.stream".

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <cmd|deepseek>
Session-ID: <your session id if visible, else pending>
then a verdict: SOUND, SOUND WITH CHANGES, or NOT SOUND; findings as P1 (the design
would fail the owner's invariant or a datom.world invariant), P2 (a significant
gap), P3 (minor), each with the section, the quoted sentence, a concrete failing
scenario, and the smallest fix; the answers to items 1-5; and what you checked and
found clean. Findings only; edit no file.
