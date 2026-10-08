Created-GMT: 2026-09-07 10:15:07 GMT
Created-Local: 2026-09-07 17:15:07 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
# Task: dao.jing plan — revision 9, Decision 0 reconsidered
Role: Lead System Architect (author seat)
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-07 17:15:07 +07 | Status: active | Rationale: resumed author session; owns Decision 0, which is challenged here

Revision 8 is at
`collab/architect-dao-jing-shared-observer-r8.claude-fable-5-1.findings.md`.
Everything in it stands except **Decision 0**, which the user has challenged
and which I think is wrong in one specific way.

Produce revision 9 complete, as the body of your final response. Do not edit
files. **Rejecting this proposal is an acceptable outcome** — see the last
section.

## The challenge

Decision 0 cites `dao.stream.forward` as "the precedent for a
transport-agnostic interpreter step living under the contract's namespace and
owned by neither caller." That is a claim about *where a file may live*. It
justifies the address and never asks the prior question: **does `forward`
make the new namespace unnecessary?**

The same fact reads both ways — "a file like this already lives here, so mine
belongs here" and "a file like this already lives here, so why am I writing a
second one" — and Decision 0 took the first reading without arguing against
the second.

Worse, Decision 0 does not cite the *stronger* support sitting in the same
file. `forward-step` already does the three things Decision 0 argues for:
outcomes returned as data with a `:status` (`forward.cljc:135-138, 163-166`),
gap policy as a caller-supplied parameter — `:resume`, `:terminate`, or a
function (`86-92`, `61-67`) — and the cursor advanced only after the effect
succeeds (`recur next-cursor` appears only in the write-`ok` branch, line
130). `yin.vm.stream-observer` is the one that hardcoded its policies;
`forward` is the one that got the design right.

## The shape the two already share

```
read one value at cursor
  ok       → do something with the value
               it succeeded  → advance cursor to the successor
               it can't yet  → keep cursor, retry later
               it failed     → stop
  blocked  → keep cursor
  end      → keep cursor
  gap      → decide: resume from the recovery cursor, or stop
  defects  → stop
```

`forward` fills "do something" with `append!` to a writer and answers every
failure as data. `stream-observer` fills it with a loader function and throws.
DaoJing would fill it with `materialize!`, which also throws.

## The proposal to evaluate

Not a new namespace beside `forward`, but **a core step with `forward`
refactored onto it as its first caller**. Sketch:

- The core is one step, stateless, no loop. Parameters: `source`, `cursor`,
  `effect`. It returns data — `:advance` with the successor, `:retry`,
  `:failed`, `:ended`, `:gap` with the recovery cursor, `:defect` with the raw
  result — and throws only on an outcome outside `dao.stream/outcomes-next`.
- It decides no policy. A caller reads `:gap` and `:recovery` and decides;
  the core takes no `:gap-policy`.
- **A throwing effect gets the ordering for free**: "advance only after the
  effect returns success" means a throw propagates before the advance can
  happen, so the VM's loader and DaoJing's `materialize!` inherit E5 without
  the core knowing anything about exceptions.
- `:retry` is produced only by an effect that has a "not yet" state — today
  only `forward`'s `full`. The other two never return it and the branch costs
  them nothing.

Each caller then adds its own layer: `forward` the budget loop, the resume
allowance, gap fixed-point detection and `terminal-status` naming; the VM
resume-and-count, terminal throws, and `run-on-stream`; DaoJing the pool
round-robin, report-don't-resync, and defects as data with `:member`.

## What to decide, and what it costs

1. **Is the core real, or is this over-abstraction?** The test I would apply:
   the core stays parameter-light. If serving three callers means it needs
   `:gap-policy` **and** `:batch-budget` **and** a terminal-naming function,
   the factoring has failed and three clear implementations were right. Apply
   that test honestly and say which side it lands on.
2. **The exact core signature and return shape**, if it survives.
3. **Does `forward` refactor onto it with no behavioural change?** Its
   subtleties must all survive: the budget loop, the separately-bounded resume
   allowance, the fixed-point rule that a recovery cursor equal to the cursor
   just read terminates as `:source-gap` (`145-151`), terminal-state
   re-stepping as a no-op (`108-111`), and the `malformed-result` guard. Its
   5 tests are `forwards-a-bounded-batch`,
   `blocked-and-full-do-not-advance-cursor`, `gap-policy-is-explicit`,
   `gap-resume-is-bounded-and-total`,
   `terminal-outcomes-are-explicit-and-no-close-is-implied`; none may change
   an assertion. Its one production consumer is `dao.stream.serving`.
4. **Does the VM refactor onto it with no behavioural change?** Same standard
   as revision 8 set: exports and shapes unchanged, none of its 17 tests
   changes an assertion.
5. **Where the core lives**, and whether `forward` keeps its own namespace as
   a thin layer or absorbs the core.
6. **What proves the refactor changed nothing observable**, per host.
7. **Phase placement.** Revision 8 put the shared step in P0. If the core now
   also refactors `forward` and `serving`'s dependency on it, say whether that
   is still one phase and what it must land with.

## Say no if the answer is no

I have now pushed you toward this twice, and an architect that agrees because
the orchestrator proposed it is worth nothing. If `forward` genuinely cannot
serve — because its effect is a writer, its `full` retry has no analogue for a
function sink, or the refactor would bend behaviour its tests pin — then say
so plainly, keep revision 8's Decision 0 or drop the shared step entirely, and
state which of the three implementations should exist. A reasoned rejection is
a better outcome than a factoring that costs two shipped namespaces and buys
thirty lines.

End with a short list of what changed from revision 8 and why.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
