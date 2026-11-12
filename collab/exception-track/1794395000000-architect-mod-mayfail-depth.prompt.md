Created-GMT: 2026-11-11 08:30:00 GMT
Created-Local: 2026-11-11 16:30:00 +07 (+0700)
# Task: architect mod — the may-fail chain depth claim and its consequences
Role: Architect (independent mob — two architects ruling separately; do not read the other's verdict)
Implementers:
- Model: gpt-6-astra (codex) | Assigned
- Model: deepseek-flash (deepseek CLI) | Assigned

## The claim under examination (made by the steward in discussion with the owner)

> "May-fail chains: recursion through try or published entries" grows linearly under the
> continuation encoding, on ALL FOUR frontends — including the three that already use it
> (python, typescript, php) — and this is currently UNMEASURED for py/ts/php (no depth probe
> exists; the constant-depth pin exists only for clojure's outcome model).
>
> Claimed corollaries:
> (a) The depth growth is structural, not incidental: `(let [v (body)] (restore! saved) v)`
>     makes the body call an operand in every try implementation, and rule R1's entry bridge
>     (install-before-binding, restore-after) makes every published-entry hop an operand too.
> (b) It matches the source languages' native runtimes (Python recursion limit, JS/PHP stack
>     behavior on try), so it is "honest to the source language."
> (c) It is second-order in practice: loops (self-tail-calls) keep constant depth via native
>     `:tail?` TCO; the growth only bites deep recursion THROUGH handler scopes.
> (d) The steward proposes: after porting yang.clojure onto the continuation encoding (owner
>     decision pending, see below), add ONE uniform depth probe to the kit for all four
>     frontends pinning "iteration constant; handler-crossing recursion linear" identically.

## Context for your ruling

- Owner decisions of record today: (A) now, (B) if R4 proof fails (1794341000000); then a
  clarified veto — the divergence itself (clojure's outcome encoding) is the target; the VM
  seam was declined ("NO") on minimal-kernel grounds (1794372000000); the steward read this as
  "(B) triggers now" and the owner has been engaging with the port's costs without objection.
- The depth property is the outcome model's ONE genuine advantage (constant-depth may-fail
  chains, pinned by test-outcome-common-call-depth), vs the continuation encoding's ONE
  genuine advantage (no retention object — the forgery/replay hole cannot exist).
- The steward's proposed port acceptance criteria: kit green, LaTTe depth measurement
  (explicit, not just re-run), uniform four-frontend depth probe.

## Questions to rule on (answer each, with file:line or kit evidence)

1. **Is the structural claim (a) correct?** Verify in the three preludes that every try and
   every published entry makes its body call non-tail (operand). Any exception — a frontend
   where handler install/pop is idempotent under TCO, or where the entry cleanup is elidable?
2. **Is (b) fair?** Does native Python/JS/PHP really exhibit the same profile (try frames
   liven the stack during the call), or does any of them elide (e.g. CPython's zero-cost
   exceptions, V8's handler tables — no stack growth per se)?
3. **Is (c) second-order or first-order?** Construct the worst realistic guest workload:
   deep mutual recursion where failure is NORMAL control flow (search/backtracking-style).
   Can it be restructured into loop/recur without changing semantics? If not, is the linear
   growth acceptable, or is this a first-order regression for that workload class?
4. **The uniform probe (d)**: is pinning "iteration constant, handler-crossing linear" as a
   KIT contract (asserting the LINEAR case, not just the constant case) the right move — or
   should the kit assert only the constant case and leave the linear case unpinned (a
   property, not a guarantee)? What exact assertion do you propose, per frontend?
5. **The interaction with the owner's arc**: given the clarified veto (divergence is the
   target) and the declined VM seam, does the depth consideration CHANGE the (B) port
   decision — i.e. is there a workload class where keeping clojure's outcome encoding (or a
   VM seam after all) is justified despite the veto? If yes, name the concrete condition that
   would trigger it (e.g. "LaTTe search does X").

## Deliverable

Answers to 1-5 with evidence, then ONE paragraph: your overall verdict on the steward's
framing — CORRECT / CORRECT WITH AMENDMENTS (name them) / WRONG (name what and why).
Read-only. Begin with Completed-GMT/Completed-Local.

## Where to look

- src/cljc/yang/{python,typescript,php}/antlr/prelude.cljc — the try families, entry bridges,
  call-ec, unwind
- src/cljc/yang/clojure.cljc — outcome model, compile-loop/compile-recur (self-application,
  :tail? marks), test-outcome-common-call-depth in test/yang/clojure_test.clj
- src/cljc/yang/tails.cljc, src/cljc/yin/vm/semantic.cljc — the :tail? machinery
- docs/design/yang.antlr.md §8.10.7-§8.10.8 — entry bridge requirements
- collab/exception-track/1794374000000-acceptance-report-three-rounds.md and
  1794369000000-mob-record-r3-retention-design.md — today's record
