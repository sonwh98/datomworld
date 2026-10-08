Created-GMT: 2026-09-22 09:42:50 GMT
Created-Local: 2026-09-22 16:42:50 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed: your de Bruijn VM design thread)
# Task: architect-register-and-scheduler-signoff — sign off on three design revisions since your last pass
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 16:42:50 +07 | Status: active | Rationale: your own design thread's continuity; independent sign-off pass on work done by claude-fable-5-1 since your last turn on this thread, before your weekly budget resets

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master, uncommitted working-tree changes present -- read the actual files,
not just this prompt). This is a READ-ONLY SIGN-OFF review. Do not edit
any file.

## What happened since your last turn on this thread

You designed the original `docs/design/yin.vm.debruijn-register-vm.md`,
proposing the register VM as a compiler chained after the committed stack
image. The owner corrected that premise: the register VM and stack VM
must be PEER projections, both derived from a de Bruijn encoding of the
semantic tuples, neither derived from the other. claude-fable-5-1 then:

1. Rewrote that document (now `docs/design/yin.vm.debruijn.register.md`)
   under the corrected premise.
2. Edited `docs/design/yin.vm.debruijn.stack.md` section 3 (split into 3.1
   resolver / 3.2 stack lowerer) and the B2 phase box, to define the
   shared "resolved tuples" artifact both projections consume, and to
   specify B2 as two namespaces instead of one fused pass.
3. Investigated, at the owner's prompt, whether the stack VM (B3), the
   named semantic VM, and a future register VM should share more
   dispatch-loop/scheduling/continuation infrastructure. Found
   `src/cljc/yin/vm/engine.cljc` already IS a VM-agnostic shared
   scheduler (confirmed against a real historical precedent: a deleted v1
   generation of this codebase ran THREE VMs -- semantic, stack, register
   -- against one `yin.vm.engine`, on a bare-function `restore-fn`
   convention, no protocol, for real production code). Decided against a
   formal protocol/multimethod, and instead wrote a new
   `docs/design/yin.vm.engine.md` specifying three small additive fixes:
   moving the semantic VM's private `scheduler-round` into `engine.cljc`
   verbatim (parameterized), unifying the engine's `restore-fn` call
   arity (currently inconsistent across call sites), and a shared
   register-agnostic `ffi/response-wait-entry` builder. The owner approved
   folding these three edits into B4's (stack VM effects/continuations,
   not yet started) file box.

Meanwhile B2 itself was refactored to match the new split: the fused
implementation (`yin.vm.debruijn-linearize`) was split into
`src/cljc/yin/vm/debruijn_resolve.cljc` (new, the resolver) and a reduced
`src/cljc/yin/vm/debruijn_linearize.cljc` (now only `lower-stack`,
`adapt`, `lift`, `named-canonical-vector`). This refactor is implemented
in a worktree (/Users/sto/workspace/worktree-debruijn-b2, branch
debruijn-b2, NOT this working tree -- you do not need to visit it for this
sign-off; the design documents are what you are signing off on, not the
implementation, which has its own independent review in flight
separately) and is not yet committed.

## Read first, in full

- docs/design/yin.vm.debruijn.register.md (the corrected register design).
- docs/design/yin.vm.debruijn.stack.md section 3 (3.1 and 3.2) and the B2
  and B4 phase boxes in section 6 -- the B4 box now names the three
  additive engine/FFI edits.
- docs/design/yin.vm.engine.md (the new scheduler contract document).
- docs/agents/architecture.md's "COMPILATION AS STREAM PROCESSING" and
  AGENTS sections, for the general patterns these three documents claim
  to extend.

## What to evaluate

Standard architect checklist (foundational invariants, ownership
boundaries, explicit state and control flow, concurrency and
linearization, host isolation, CLJ/CLJS/CLJD portability, migration risk,
completion criteria, design contradictions), applied specifically to
these three documents together, as one coherent set of changes:

1. Does the peer-projection topology in yin.vm.debruijn.register.md
   actually resolve the identity questions cleanly -- is R truly
   independent of H now (no source-H in R's preimage), and does the
   "resolved tuples have no hash, no identity" decision hold up against
   invariant I and D9/D12/D2, or does it create an unaddressed sharing
   gap (e.g., can a receiver holding only R ever need the resolved
   tuples, and if so how does it get them without an identity to request
   them by)?
2. Does yin.vm.debruijn.stack.md's new section 3.1/3.2 split genuinely
   preserve every invariant the ORIGINAL fused B2 design established (the
   lift correctness law, the structural-comparison-with-named-vector
   test, B1's golden H stability), or does splitting scope validation
   from linearization introduce any soundness gap at the seam (e.g. could
   `lower-stack` ever run on a resolved-tuple tree the resolver's own
   validator would have refused, if some caller skips `resolve` and hand-
   builds resolved tuples directly)?
3. Is the yin.vm.engine.md decision -- three small additive edits instead
   of a protocol -- actually sound, or does it risk becoming exactly the
   "three copies of near-identical glue" problem it claims to avoid, just
   one edit short of solving it (i.e., is moving `scheduler-round` alone
   enough, or does leaving `park-entry-fns`/restore-fn construction
   entirely unformalized mean B4 and any future register-VM effects phase
   still hand-write the harder half of the duplication)? Give your own
   independent judgment, informed by the same v1 precedent (three VMs
   ran fine on the bare convention) fable already weighed -- do not just
   restate fable's conclusion, form your own view of whether it drew the
   line in the right place.
4. Any invariant violation, migration risk, or contradiction across the
   three documents you would not have signed off on if you were writing
   them fresh, versus something that is an acceptable, intentionally
   deferred decision.

Distinguish architectural defects from implementation gaps or
intentionally deferred work (R4's register kernel, B4's implementation
itself, and the semantic-VM/walker migration to `engine/scheduler-round`
are all explicitly deferred in these documents; that is not itself a
defect to flag).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended
correction, for each finding. Confirm the properties that passed review.
End with a plain verdict per document (sound as written / sound with
changes / not sound) and an overall sign-off status.
