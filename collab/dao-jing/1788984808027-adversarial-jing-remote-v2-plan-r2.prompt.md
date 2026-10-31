Created-GMT: 2026-09-09 20:13:55 GMT
Created-Local: 2026-09-10 03:13:55 +0700 (Asia/Bangkok)
Coding-Agent: deepseek
Session-ID: 7bf6a403-74d3-4a94-91a7-4b7eb55a7e72 (resumed)
# Task: adversarial confirm — the dao.jing.remote plan at r2
Role: Adversarial Review

**Read-only. Print to stdout. Write nothing — not to the repo, not to a plan
file, not to your harness plan directory.** You obeyed that last round;
thank you. A routine confirm runs in parallel; do not coordinate with it.

You reviewed this plan at r1 and raised six findings. All six were accepted
and none disputed. Plan: `collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md` (956 lines, header marked r2 + Reconciled).

## How your six were settled

1. **The unwritable timeout test.** Rebuilt around a latch the test owns;
   late correlation pinned separately at step level with scripted media —
   your point that Phase 1's foreign-id test already pins the discard is
   taken.
2. **Retained timed-out payloads.** A portable `retire-call` now drops the
   outstanding entry and abandons a still-unsent envelope.
3. **`close!` outside the call lock.** N10 states the scope and pins it.
4. **§9's homeless item / missing item.** The ClojureDart fact is resolved to
   the project memory (which already records it); D2's
   cursor-before-`attach!` ordering is added with a home.
5. **"Seed of the stepped client" overstated.** Reworded to the blocking
   driver's loop.
6. **D8's DHT split.** Now says the real work is inbound decode policy under
   the portable-domain gate, not a require swap.

## What changed underneath the plan since you read it

**Phase 0 is implemented and committed (`3228d0e`).** The routine reviewer
found the `.join` you did not, and then found two defects in the repair
across three rounds. The shipped seam differs from the draft in two ways, and
I have rewritten §2.0's invariants and §4.0 to describe the commit rather
than the draft: failure reporting is now **once per connection and outside
the submission monitor** (J5), and a failed connection **stays terminal and
answers `closed` rather than the retryable `full`** (J6).

## Hunt these

1. **The reconciliation itself.** I wrote §4.0's rewrite, not the Architect.
   Does it describe `3228d0e` faithfully, or does it flatter the commit?
   Read the code — `src/clj/dao/stream/ws/jvm.clj`,
   `test/dao/stream/ws/jvm_test.clj` — and say where the text and the
   code disagree.
2. **What J5 and J6 break upstream.** You traced the interleavings at r1
   assuming the old seam. A failed connection now answers `closed` where it
   would have answered `full`, and reporting happens outside the lock.
   Re-run your interleavings: socket drops mid-call, response after timeout,
   `close!` during a call, two threads on one handle. Does anything you
   cleared at r1 stop holding?
3. **`retire-call` as specified.** You showed retention was unnecessary
   because ids are monotonic and an unknown-id response classifies as
   `:unsolicited-response`. Does the *specified* `retire-call` actually
   achieve that, or can it drop an entry whose response then misroutes — or
   abandon an `:unsent` envelope that was already on the wire?
4. **The inbound-step error substitution.** A correlated
   `:dao.jing.remote/non-portable-result` is returned when a handler's result
   will not encode. Can that error itself fail to encode? Can it be
   correlated to the wrong request?
5. **Anything Phase 1 or Phase 2 now assumes about a seam that changed.**

## Report

Print to stdout, ordered by severity, each finding with the interleaving,
input, or omission that makes it real. Say plainly where the plan is right,
and whether it is ready to promote.
