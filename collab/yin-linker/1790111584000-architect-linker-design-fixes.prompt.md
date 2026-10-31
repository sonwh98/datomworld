Created-GMT: 2026-09-22 19:33:04 GMT
Created-Local: 2026-09-23 02:33:04 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 341722e7-dd66-4583-996a-da14eaaeb56d (resumed: your register-VM design session)
# Task: architect-linker-design-fixes — close the independent review's findings
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-23 02:33:04 +07 | Status: active | Rationale: closing an independent review's findings on your own committed design

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). The linker/B4/continuation-transport design is committed
(commit d2593c7a, bundled with an unrelated workflow-rule commit -- run
`git show d2593c7a -- docs/design/yin.vm.debruijn.stack.md
docs/design/yin.vm.debruijn.register.md` to see just the design portion).
Edit docs/design/yin.vm.debruijn.stack.md and
docs/design/yin.vm.debruijn.register.md only. If a finding below requires
touching docs/design/yin.vm.engine.md, note it in your report instead of
editing it -- that file is not in your box this turn.

## Review verdict: sound with changes. Four P2s, close all of them.

Full review at
collab/1790111320000-reviewer-linker-b4-continuation-design.glm-5.3.stdout.log
-- read it in full for exact wording; summarized findings below.

**P2-1.** B4's parked-payload contract is only amended in the register
document's new section 5.1 ("a decided rule for B4 and R4"), but
`yin.vm.debruijn.stack.md` section 4.1 item 3 still lists the payload as
exactly `{:segment :pc :frames :stack :continuation}` with no model/image
identity keys, and `yin.vm.engine.md`'s payload table (not in your box
this turn, note only) likewise. If B4 is implemented from stack.md alone,
the same-model-only refusal rule has no keys to enforce it against. Add
the `{:format ... :hash H/R}` keys and the refusal rule to stack.md
section 4.1 directly (not just by reference to the register document),
so the document that actually owns B4's payload states its own contract
completely.

**P2-2.** The "host may refuse R and request H instead for the same named
root" fallback (R5's box) has no verification: every OTHER pairing in the
design is hash-checked, but a stale or swapped [H, R]-for-the-same-root
pairing is silently wrong -- register.md's own section 1.1 explicitly
disclaims any H-to-R biconditional, so nothing catches a receiver
executing a different program than the R it asked for. Resolve this
explicitly: either state the same-root pairing is composition trust
deferred to B7's provenance work (and say so plainly, not as a silent
gap), or specify that a receiver requiring verification must fetch the
named datoms and re-lower locally to confirm the pairing, never trust it
bare. Pick one and write it into R5's box.

**P2-3.** Fetch step 4 ("refuse `:descriptor` unless the value's
descriptor matches") names a check with no specified operand: neither
B6's stored payload (the canonical instruction vector value) nor R5's
(the `{:bodies :instructions}` map) is specified to actually carry a
descriptor/version field to check against. As written this step is
untestable and, since the descriptor hash is already folded into H and R
(step 3 already covers it), likely redundant. Either specify the exact
descriptor/version field both stored payloads carry, or fold descriptor
agreement into step 3 and remove the separate `:descriptor` refusal --
your call, but resolve the contradiction.

**P2-4.** `yin.vm.debruijn.register.md` contradicts itself on R5's
dependencies: the R4 box's phase-order sentence still says "R0, R1, R2,
R4, R3, R5" (implying R5 waits for R2/R4/R3), while the new R5 box says
R5 depends only on R1 and B6's shared function and is built in parallel
with B6. The DEFERRED list also still asks "whether R5 is ever
commissioned," contradicting R5 having its own committed box now. Fix
the phase-order sentence to state the actual parallel-track order (e.g.
R0, R1, live-set; then R2 + R4-pure in parallel with B6+R5; then
R4-effects; then R3) and remove or update the stale DEFERRED line.

## Also fix: the B4 sequencing recommendation has no home (P3-5)

The recommended dispatch order (B4 now; register track and linker track
in parallel; R4-effects after B4+R2; continuation-format design last) was
stated only in a commit message that was later soft-reset and replaced --
it is not actually written into either design document. Add it as
explicit text: a short "Recommended sequencing" note in
yin.vm.debruijn.stack.md (near B4's box, since B4 is the pole this
sequencing turns on) stating the dispatch order plainly, so it survives
independent of any commit message.

## Also fix, if the fix is small (your judgment on which of these are

## worth this pass versus a later one -- state which you skipped and why)

- P3-1: step 2's claim that "the DHT verifies the payload against the
  address before returning it" is a property of a `create-content-dht`
  handle, not of bare `jing/get` -- the realistic JVM-to-Dart path
  (stepped client, the only cross-host `IDhtNet` path) does NOT verify
  address-to-payload on the receiver; step 3 (H/R check) is the sole
  content check on that path, which is sufficient, but the text
  overclaims. Name the handle topology the verification claim actually
  applies to.
- P3-2: section 7.2, D14, and D16 in the stack document still describe
  the dropped "responder holds an explicit H-to-bytes value, hashes
  received wire bytes before decoding" design; the actual Jing path
  hashes AFTER decode (`segment-key` over the decoded value). Same
  integrity guarantee, different mechanics -- reconcile the wording.
- P3-4: fetch step 5 (`:free-names-fn`) runs before step 6 (the format
  validator) with unspecified behavior on malformed tuples -- require it
  total over any value, or swap the step order so the validator runs
  first.
- P3-6: `yin.vm.engine.md` calling the register kernel "gated,
  hypothetical" is now stale (register.md DECIDED 1 authorized it
  unconditionally) -- not in your box to edit, just confirm in your
  report that this is a known, separate stale-doc item for a later pass.

## Also: re-pin golden values (P3-5's second half)

Confirm in your report (do not implement, this is a note for the next
code phase, not a doc edit): R5's eventual golden R pins must follow the
live-set contract-version bump to 2, same as R1's own re-pin -- flag this
so the implementer of R5 doesn't miss it.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: each finding, how you closed it and where, or why you deferred a
P3 to a later pass; anything still needing the owner's decision.
