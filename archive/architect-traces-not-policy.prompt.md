Created-GMT: 2026-09-07 12:14:48 GMT
Created-Local: 2026-09-07 19:14:48 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
# Task: the observation core deposits traces; it interprets nothing
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-07 19:14:48 +07 | Status: active | Rationale: resumed author session; it wrote Decision 0 and the plan this question would amend

This is an **architecture question**, not a plan revision. It touches
`datom.world.md`'s axioms and ADR-0003's open exception, and it comes from
the user, whose framing it is. Answer it; do not rewrite the plan.

## Where this came from

P0 landed as `b2bf609`: `dao.stream.observe/step`, with `forward` and
`yin.vm.stream-observer` refactored onto it. Reviewing it, `glm-5.3`
established that every stream interpreter in the tree obeys one law — an
element's cursor advances exactly when its disposition has been durably
recorded — and that `rpc/poll!`, `apply/serve-once!` and `dao.runtime`'s
wait set stay off the step for dataflow reasons, not ordering. Its findings
are at `collab/architect-observe-generalization.glm-5.3.findings.md`.

The user then made a stronger claim, which I initially answered too narrowly:

> dao.stream.observer can be the core of all interpreters of datom.world
> because datom.world is built with a core philosophy of interpreters
> observing streams and transforming streams into its own semantics

and sharpened it:

> interpreters do not differ on how they observe dao.stream

I measured that. All six interpreters enumerate the seven declared `next`
outcomes in source, but only two — `dao.stream.observe` and
`dao.runtime` — have a test pinned to `dao.stream/outcomes-next` that
fails if the contract grows an outcome. The other four (`forward`, `rpc`,
`apply`, `yin.vm.stream-observer`) would route an eighth outcome into a
catch-all and keep passing. `dao.runtime`'s test docstring already states
the principle: a new contract outcome "fails the test instead of falling
silently into the classifier's default branch: a human decides".

I proposed adding four tests. The user said the fix is structural, not
test-based: a common core that handles every possibility, with a default when
a caller supplies no policy. I raised that a default in the core is still a
silent default, centralized. The user then supplied the resolution:

> the default behavior could be just to log or send an alert to an admin

> this also aligns with stigmergy. errors are just data traces in an
> environment that can be consumed and interpreted with different semantics.
> an error might trigger an email or an sms or a firealarm

## The question

That reframes the default from a behaviour into a **deposit**. The core does
not decide what an unrecognised outcome means; it leaves a trace and stops.
Meaning belongs to whoever reads the trace, plurally and in disagreement —
Axiom 2's "one truth, many perspectives", and the blackboard → Linda →
dao.space lineage made operational. It also explains why an `alert!` callback
parameter would be wrong in a way that is architectural rather than stylistic:
being told is direct coordination; a trace in a medium is stigmergic.

So:

**The core deposits a trace for anything it cannot interpret, and interprets
nothing itself. What is the smallest such core, where does the trace go today,
and what carries it into `dao.space` when ADR-0003's exception closes?**

Specifically:

1. **Is the reframing right** — is "deposit a trace and stop" the correct
   default for an outcome outside the contract, as against today's four
   catch-alls that classify it as `transport-error` and thereby invent a
   meaning? Say plainly if it is wrong.
2. **What does the trace contain**, such that readers with different semantics
   can each interpret it — and what must it *not* contain, so the core stays
   free of interpretation?
3. **Where does it go today?** `rpc` and `apply` already accumulate
   diagnostics in a local vector drained by one caller (`rpc/take-diagnostics`,
   `apply`'s `:diagnostics`). That is a private outbox, not a shared
   environment: exactly one reader, no plurality. Is that the same debt as the
   `dao.stream.implementation-plan.md` boundary note — "the slice's boundary
   deposits into a ring buffer rather than into the medium ADR 0003 names,
   under a time-boxed exception"? If so, say so; if not, distinguish them.
4. **The testable consequence**, if the reframing holds: no interpreter needs
   a policy parameter for the unknown case, because there is nothing to
   decide. Does that survive contact with the four interpreters, or does one
   of them genuinely need to act rather than deposit?
5. **Where this belongs.** The user's claim is that interpreters do not differ
   in how they observe. If that is true it is an axiom-level property, not a
   namespace detail, and belongs in `datom.world.md` beside the six
   invariants. Draft the sentence if you agree; argue against if you do not.
6. **What it costs.** Four interpreters, ADR-0003, and possibly the `step`
   signature. Say what changes, what does not, and what should wait.

I have been corrected twice on this family of judgement — once for proposing a
namespace beside `forward` on resemblance, once for a two-family taxonomy that
`serve-once!` refutes. Treat my framing as a hypothesis, not a brief.

Do not edit any file. Produce the complete response in this run.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
