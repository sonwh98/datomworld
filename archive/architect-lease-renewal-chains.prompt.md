Created-GMT: 2026-09-01 16:43:15 GMT
Created-Local: 2026-09-01 23:43:15 Asia/Ho_Chi_Minh

# Task: renewal chains, delegated renewal, and whether leases pull Shibi forward

Role: Lead System Architect

## Context

You have agreed that `dao.lease` should follow the `jit-design.md` pattern:
evidence as facts, the possessor judges at its own points, status as data. All
five reviewers independently rejected Jini's `LeaseRenewalManager` as
manufacturing false liveness for a wedged holder.

The user now asks: **what if an agent has its own lease manager, which is
another agent?**

The orchestrator's analysis, for you to confirm or refute:

- Making the renewer an agent rather than a library thread changes its
  *visibility* — composed explicitly, its renewals are its own appends — but
  does not by itself change what a renewal *means*.
- The decisive question is whether the manager renews **unconditionally** or
  **on evidence**. Unconditionally reproduces Jini's defect in cleaner
  materials.
- On evidence, the manager needs its own liveness signal from the holder —
  which is a lease. So the holder leases from the manager and the manager
  leases from the grantor: a **renewal chain**, each link proving the next,
  terminating at whoever is actually doing the work.
- Proposed rule: **the chain's weakest link defines the guarantee.** One
  unconditional renewer anywhere and everything downstream of it proves
  nothing.
- What it genuinely buys: batching (Jini's `LeaseMap` — one renewal set for
  many leases) and cadence decoupling (a holder doing long synchronous work
  cannot renew tightly; a manager can, *if* it judges the holder).
- Native framing: a lease manager is not a component. It is an interpreter
  that reads lease facts and appends renewal evidence — the same shape as
  `forward!` and the JIT. `dao.lease.md` gains no component.

## The question that has no current answer

A holder emits evidence **about itself**. A manager emits evidence **about
someone else** — "A is still here" — and the grantor must decide whether to
believe it. That is delegation, and delegation needs authority.

`dao.stream.md` reserves `:dao.stream/shibi` for capability tokens and says
Shibi "is not yet designed or built." Two existing documents constrain what it
may become, and you must read both:

- `docs/design/dao.stream.discovery.md`, *The ShiBi precondition*: ShiBi is
  currently a capability token in one document and a fungible currency in
  another. "These are different objects with different physics… **Before ShiBi
  is load-bearing in any design, force the decision: capability or currency.**"
- `docs/design/dao.space.security.md`, *Capabilities Govern Interpreters*: a
  Macaroon-style design — attenuatable, offline-verifiable, revocable caveats
  bounding a function, its scope, and its time-to-live, where the token is
  cryptographically authenticated but the **substrate**, not the signature,
  enforces the predicate.

## Answer these

**1. Is the renewal-chain analysis correct?** Confirm or refute each step. In
particular, is "the chain's weakest link defines the guarantee" a real rule or
a slogan, and can it be stated so an implementer can check it?

**2. Is delegated renewal worth having at all?** Batching and cadence
decoupling are the claimed benefits. Weigh them against a chain in which
liveness can be faked at every link. If the answer is "not worth it in this
slice," say so.

**3. Does delegated renewal actually require Shibi, or is there a weaker
mechanism that suffices?** Candidates to consider rather than assume: the
grantor simply accepts any renewal naming the lease (no authority at all, and
say what that costs); the holder authorising a specific renewer in a
grantor-visible fact; or a full capability token. Name the weakest mechanism
that is sufficient.

**4. If leases would be Shibi's first real consumer, does that force the
capability-or-currency decision the discovery document demands?** A lease
caveat is time-bounded and attenuable, which reads as capability. Say whether
leases settle that question, merely add evidence, or must stay out of it.

**5. What should `dao.lease.md` say now?** It should not depend on an
undesigned mechanism. Propose the exact text covering delegated renewal —
including, if appropriate, an explicit denial deferring it — such that the
document stands today and does not have to be rewritten when Shibi lands.

## Scope

Read: `docs/design/dao.lease` material from your previous drafts,
`docs/design/jit-design.md`, `docs/design/datom.world.md`,
`docs/design/dao.stream.md`, `docs/design/dao.stream.discovery.md`,
`docs/design/dao.space.security.md`. **No source code.**

Propose only. **Make no edits.**

Begin your final response with:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
```
