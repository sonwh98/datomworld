Created-GMT: 2026-10-10 12:30:00 GMT
Created-Local: 2026-10-10 19:30:00 Asia/Bangkok

# Task: Complete the phase-4 design -- the register-shaped semantic evaluator

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-10 19:30 +07 | Status: active | Rationale: owner instruction ("delegate to fable-5.1 to complete phase 4 design"); Fable is the project's preferred Architect for design work (owner 2026-09-30). Review goes to a non-Claude Architect (codex gpt-6.1-sol) before any implementation brief; never Claude-only sign-off.

You are working in the git worktree `/Users/sto/workspace/datomworld-srvm-p4-design`
on branch `srvm-phase4-design` (from master d21ee43f). Work ONLY there.
Documentation only: you write one design document and one report. Do not
edit source, tests, or any existing design document. Do not attempt
`git add` or `git commit` (headless sessions cannot; the orchestrator
commits). Do not stage `collab/`.

## Why this task exists

The register-shaped semantic VM rebuild is at migration phase 3 done
(linearizer, validator, liveness analysis; merged f46935ad) and phase 4
next: the evaluator that RUNS register vectors, beside the old
stack-shaped `yin.vm.semantic`. The frozen design
`docs/design/yin.vm.semantic-register-vm.md` specifies the transitions
(§2.4) and the gate (§10 phase 4) but the orchestrator's readiness
review found four thin spots that an implementer would otherwise have to
invent. Your job is to close them as a design, grounded in the code.

## Owner rulings that bind this (verbatim)

> "The AST is the source of truth but the semantic vm is the canonical
> projection of it. the ast-walker and the semantic-vm are isopmorphic.
> we just need to rebuild the smantic-vm as register-shaped vm instead
> of a stack-shaped vm"

> "rebuilding the semantic-vm as as register shaped instead of
> stack-shaped is the right way to go, delaying this will only add tech
> debt in the future" (virtual registers; delete the stack VM and rename
> on cutover)

> "yes, A is the only code identity"

## Read first (all of it, in this order)

1. `docs/design/yin.vm.semantic-register-vm.md` -- FROZEN. §2.1-2.5
   (machine, transitions, delivery, correspondence), §3.4 (rules and the
   image shape), §4.1 (safepoint table), §4.2 (UCF frame), §5, §8.1
   (analysis), §10 (phase gates). Do not change it. If the engine's
   reality conflicts with it, record a FINDING with a proposed amendment;
   do not silently diverge.
2. `docs/design/datom.world.md` -- invariants.
3. `docs/design/yin.vm.semantic.md` §3.4-§3.6 (scheduler, park, resume,
   observation) and §4 (the stack machine being replaced).
4. `src/cljc/yin/vm/engine.cljc` -- `handle-effect`, `park-continuation`,
   `resume-continuation`, `check-wait-set`, `call-park-entries`,
   `store-of-key`, `active-store`, `put-active`, the wait-entry and
   `:parked` shapes, `restore-fn` and `park-entry-fns` contracts.
5. `src/cljc/yin/vm/semantic.cljc` -- `apply-call`, the `:continuation`
   arm, the effect restore paths, how instructions are dispatched.
6. `src/cljc/yin/vm/ast_walker.cljc` -- the oracle: application, `if`,
   `define`, stream and FFI frames (`eval-operator`, `eval-operand`,
   `eval-test`, `eval-stream-put-*`, `request-sent`, `eval-call`),
   `park-and-call`.
7. `src/cljc/yin/vm/ffi.cljc`, `src/cljc/yin/vm/completion.cljc`.
8. `docs/design/yin.vm.linker.md` §7.2-7.3 (the require flow, the three
   wait states, the install child as a scheduler child task) and the
   landed code in `src/cljc/yin/vm/linker*` that implements it.
9. `docs/design/yin.vm.universal-continuation-format.md` §7.4.1-7.4.3 and
   `docs/design/yin.vm.universal-continuation-format.v2-amendment.md`
   §5.1, §6 (frames and pending variants as the semantic profile carries
   them today).
10. The landed phase-3 code: `src/cljc/yin/vm/semantic_register/{code,
    linearize,analysis}.cljc` and `test/yin/vm/semantic_register/` --
    especially `load-vector`'s image `{:vector :address :bodies
    [{:start :end :owner :registers}]}` (`:end` exclusive) and
    `analysis/analyze`.
11. `collab/1791530000000-vm-engineer-srvm-phase3.report.md` (in the
    phase-3 worktree `/Users/sto/workspace/datomworld-srvm-p3/collab/`)
    -- the seven ambiguity readings, to avoid re-deciding them.

## Deliverable: `docs/design/yin.vm.semantic-register-vm.evaluator.md`

A companion design document (the frozen document stays frozen; this one
is cited from its §10 phase 4 by the orchestrator afterward). It must
contain, each section grounded in file:line references to the code you
read:

1. **Concrete in-VM state shapes.** The actual map keys and value shapes
   of: the evaluator's machine state (how `⟨seg pc W E S K⟩` sits in the
   engine's state map next to what `yin.vm.semantic` keeps), a `ret`
   frame, an `act` wait entry, a `tail` wait entry, the `deliver` record,
   a `:parked` record, a reified continuation value. For each, a
   side-by-side table with its stack-machine counterpart in the engine
   TODAY, naming what is reused unchanged, what is extended, and what is
   new. The goal: an implementer needs no further shape decisions.
2. **The semantic restore helper.** The exact algorithm
   `restore-fn(base, entry, value)` follows for an `act` entry and for a
   `tail` entry, per pending reason (`:next :put :ffi :ffi-request
   :link-request :link-response :install`), including immediate effect
   completion, terminal errors, FFI response decoding and parked
   bookkeeping removal, exactly-once delivery, and the empty-K halt case.
   Show, with code references, that `handle-effect` can deliver a `tail`
   completion through K with no current activation (the frozen design
   argues this; verify it against the engine, or record a finding).
3. **Link and install interplay.** How the three link wait phases and
   the install child task (a scheduler child, linker §7.3) compose with
   `act`/`tail` and the delivery record: which transitions are the
   evaluator's, which are the linker's and unchanged, where the delivery
   record is held while the child runs, what `:resume`-ing a parked
   child means over windows.
4. **The walker trace-test procedure.** A precise algorithm (not a
   description) for aligning walker steps with register-machine writes
   under §2.5's table: what is recorded on each side, how administrative
   steps are skipped, what counts as a mismatch, and the corpus it runs
   over (name the existing B0 parity corpus file(s)).
5. **Slices and gates.** Split phase 4 into 4a (core: const, var,
   closure, define, call incl. tail, return, branch, jump, halt, no
   engine waits) and 4b (effects: stream and FFI waits, park/resume,
   current-continuation and abortive continuation invoke, link/install,
   store-of). For each slice: the exact files to create, the exact test
   namespaces, the golden/parity rows by name, and a numbered acceptance
   list an implementer and a reviewer can check mechanically. 4a must be
   shippable alone.
6. **Findings and proposed amendments** to the frozen design, if any
   (each with the evidence, the proposed text, and which section it
   would amend). An empty list is acceptable only if you checked.
7. **Questions for the owner**, if any decision is genuinely theirs.
   Do not use this to avoid deciding what the invariants and the frozen
   design already decide.

Distinguish in the document what is DECIDED (follows from frozen text or
the code) from what is PROPOSED (your design choice, to be reviewed).

## Constraints

- Docs only. No code, no tests, no edits to existing documents.
- Portability: the evaluator must run on JVM, Node and Dart; cite the
  repo's known host traps where a proposed shape touches one (reader
  conditionals, unary minus on floats, `for` over long seqs, multi-key
  assoc on nil, protocol param names, EDN whitespace before closers).
- Foreground only; no `&`; no background lanes.
- Verify by reading and by `clojure -M:kondo`-free checks only: you may
  run read-only repo commands (grep, git log) but need not run tests.

## Report

Write `collab/1791540000000-architect-srvm-phase4-design.report.md` in
this worktree. Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: the path of the deliverable; a one-paragraph summary; the list of
findings against the frozen design (or "none, checked against: ..."); the
questions for the owner; anything you could not ground in code and why.
Do not claim reads or edits that did not occur.
