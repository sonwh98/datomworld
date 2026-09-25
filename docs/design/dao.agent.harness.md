# dao.agent.harness: The Stigmergic Loop Governor

Status: design specification (2026-09-24); not implemented

Parent: `docs/design/dao.agent.md`, sections 4 and 6. This document
specifies the multi-agent coordination harness: the agent reasoning
cycle, stream cursor management, the triad consensus policy, and
liveness detection. Siblings: `dao.agent.mcp.server.md`,
`dao.agent.schema.md`, `dao.agent.mcp.client.md`.

## 1. Objective and position

The harness is the composition that turns a set of agent streams, a
governor stream, a Register VM kernel, and a clock adapter into a
running stigmergic loop. It is where prompts, roles, and consensus
policy live, and it is the only place they live (`dao.agent.md`,
section 2, Invariant 3).

The harness has two kinds of participant:

- **Seats**: one per agent (Claude, Codex, AGY). A seat is an explicit
  state map holding the agent's own transactor, its cursors over the
  streams it reads, its parked continuations, and its budget. A seat
  runs the three-beat cycle of section 3.
- **The governor**: one per board. The governor is itself a seat whose
  agent has `:agent/role :governor`, with the extra duties of sections
  4 through 6: granting claims, judging leases, authorizing merges, and
  waking parked seats.

Every participant communicates only by appending to its own stream and
reading the others. The harness introduces no channel between seats.

```text
   tick adapter          agent streams          jing (continuations,
   (timer -> datom)    (single-writer each)     images, specs)
        |                    |   |   |                 |
        v                    v   v   v                 v
  +-------------------------------------------------------------+
  |                        harness                              |
  |  seats: {agent-id seat-state}   governor: seat-state        |
  |  one scheduler-round per beat, pure state -> state          |
  +-------------------------------------------------------------+
        |                    |   |   |                 |
        v                    v   v   v                 v
   (no output;          own-stream appends        park snapshots
    ticks are input)     (claims, artifacts,      by content hash
                          reviews, grants)
```

## 2. Namespace and file box

```text
New: src/cljc/dao/agent/harness.cljc        seats, governor, round
New: src/cljc/dao/agent/harness/cursor.cljc ingress cursor management
New: src/cljc/dao/agent/harness/policy.cljc triad and liveness policy
New: test/dao/agent/harness_test.cljc
Existing edits: none
Depends on: dao.agent.schema, dao.agent.mcp.client, dao.space.schema,
            dao.space.query, dao.space.transactor, dao.stream,
            dao.lease, dao.jing, yin.vm.engine,
            yin.vm.debruijn.register (R4 kernel), yin.vm.linker
Must not change: yin.vm.*, dao.space.*, dao.stream.*, dao.lease,
                 dao.jing.*
```

`dao.agent.harness` exports:

- `seat`: constructs a seat state from an explicit spec.
- `beat`: `(seat) -> seat'`, one Perceive/Decide/Act cycle.
- `govern`: `(governor seats) -> governor'`, one governor round.
- `round`: `(harness) -> harness'`, one beat per seat then one govern.

The harness is a value. `(iterate round h)` is the running system;
whoever holds the sequence decides cadence. There is no `run!` that
loops forever inside the namespace.

## 3. The agent reasoning cycle

### 3.1 Three beats as VM effects

An agent's inner loop is a Register VM program (in Phase 4, pure Yin;
before that, a host-side driver that emits the same effects). Its three
beats map onto the coroutine models of `co-routines.md`:

```text
+----------+------------------------+----------------------------------+
| Beat     | VM effect              | Coroutine model                  |
+----------+------------------------+----------------------------------+
| Perceive | :stream-next on an     | Model 1: parks on                |
|          | ingress cursor         | :dao.stream/blocked; resumes     |
|          |                        | when a peer appends              |
| Decide   | :ffi-call :mcp/invoke  | Model 2: :park with sparse       |
|          | (llm-infer or a host   | snapshot; host resumes with the  |
|          | tool)                  | result via the client bridge     |
| Act      | :stream-put on the     | Model 1: parks on                |
|          | agent's own stream     | :dao.stream/full (backpressure)  |
+----------+------------------------+----------------------------------+
```

Model 1 is in-VM: the engine's wait set holds the parked continuation
and `engine/scheduler-round` promotes it when the stream condition
clears. Model 2 is host-driven: the VM halts with a parked descriptor
and the harness decides when to resume it. The harness never blocks a
thread on either.

### 3.2 Perceive

The seat holds one cursor per stream it reads (section 3.3). Perceive is
a bounded drain: the VM issues `:stream-next` on each cursor until the
engine answers `:dao.stream/blocked` or the seat's per-beat read budget
is spent. Each datom read is appended to the seat's **perception
relation**, a local d5 relation value the Decide beat queries with `q`.

The perception relation is per beat and is discarded after Act. It is
not a cache of the board; the board is the streams. A seat that wants
history re-reads from a kept origin cursor, or opens a published index
through `query/open-published!`.

If Perceive reads nothing and the seat has no pending work, the VM
executes `:park` with `{:reason :idle}`. The governor wakes it on the
next tick or when a stream it reads advances (section 3.4).

### 3.3 Stream cursor management

Cursors are the seat's only read state. The rule set:

1. **One cursor per source, held in the seat.** `:cursors` is a map
   `{stream-id cursor}`. Cursors are values returned by the engine; the
   seat stores the latest and never holds two for one stream.
2. **Origin cursors are kept.** At seat construction, every cursor is
   minted at `:oldest` on a complete-retention transport
   (`dao.space.transactor.md`, T18). A seat that must prove it saw the
   whole history keeps that origin cursor beside the advancing one as
   `:origin-cursors`; the pair is the completeness declaration the
   schema view cannot make itself (`dao.space.schema.md`, section 4,
   D4).
3. **No polling.** A blocked `:stream-next` parks in the engine wait
   set; the seat does not re-issue it on a timer. The wake comes from
   the stream's own waitable surface or from the governor's round,
   which runs `engine/scheduler-round` and lets the engine re-check.
4. **No locks.** Two seats reading one stream hold independent cursors.
   A stream has no privileged reader (`datom.world.md`, Streams). The
   writer never knows who reads.
5. **Observation datoms.** A governor seat records, for each proposal
   it reads, `[obs :ingress/stream s] [obs :ingress/offset n]
   [obs :ingress/proposal p]` in its own stream, where `n` is a
   counter the governor increments per datom read in that round. This
   is the arrival-order fact `dao.agent.schema.md` section 5.2 step 2b
   consumes. Ordinary seats do not record observations.
6. **Cursor persistence.** A seat's cursors are part of its parked
   snapshot when the seat parks (section 6.3), so a rehydrated seat
   resumes reading where it stopped. A cursor is plain data; it
   survives EDN.

### 3.4 Decide

Decide runs a Datalog program over the perception relation joined with
the seat's own history to answer one question per role:

```text
+--------------+-----------------------------------------------------+
| Role         | Question                                            |
+--------------+-----------------------------------------------------+
| :synthesizer | Is there a :ready task with :task/phase :implement  |
|              | matching my :agent/capabilities, with no live claim?|
| :reviewer    | Is there an :implemented task with :task/phase      |
|              | :review whose artifact I have not reviewed?         |
| :governor    | sections 4 through 6                                |
+--------------+-----------------------------------------------------+
```

If the answer names a task, the seat's next act is a claim proposal (or
for a reviewer, a review), and the model is invoked only after the claim
is granted. Inference is the expensive beat; the harness never spends it
speculatively on a task another seat may win.

When the seat holds a granted claim, Decide assembles a prompt from the
task spec (fetched by `:task/spec-address` through the jing handle), the
relevant AST datoms, and the seat's role prompt, and issues:

```clojure
[:ffi-call rd :mcp/invoke [r-params] live]
;; r-params holds {:tool "llm-infer" :args {...}}
```

The VM parks per `dao.agent.mcp.client.md`, section 4. The seat's beat
ends here; the resume is a later beat's first step.

### 3.5 Act

The resumed value is the model's response as data. Act validates it
against the role's expected shape (a synthesizer returns AST datoms or a
register image identity; a reviewer returns a verdict and a findings
value), stores any large value in `dao.jing`, and transacts the board
datoms through the seat's transactor:

- Synthesizer: `:artifact/*` entity bound to the granted claim.
- Reviewer: `:review/*` entity with `:review/findings-key`.

A response that does not validate is not appended as a board fact. It is
appended as a diagnostic `[d :seat/rejected-response key]` with the
response stored in jing, and the seat re-issues the inference with the
validation defect added to the prompt, up to `:max-reprompts`. After
that the seat releases the claim (`:claim/status :released`) and the
task returns to the governor.

`:stream-put` may answer `:dao.stream/full` on a bounded transport; the
VM parks as a writer (Model 1) and the append is retried when the stream
drains. Nothing is lost, and nothing is duplicated: the transactor's
watermark advances only on `:dao.stream/ok` (T5).

## 4. The governor round

`govern` is one pure step over the governor seat and the set of seats'
stream sources. In order:

1. **Drain ingress** over every agent stream, recording observation
   datoms (section 3.3, rule 5).
2. **Linearize claims** with `dao.agent.schema/linearize-claims` over
   the proposals read and the governor's own granted set. Append one
   grant or refusal per proposal. A grant carries `:claim/epoch` (the
   receipt's `:dao.space/t`) and `:claim/lease-expiry` computed from the
   latest tick reading plus the task type's lease duration
   (`:policy/lease {:compiler-engineering {:minutes 90} ...}`).
3. **Advance task status** for every task whose derived next state
   differs from its live status, using `transition?` and the queries of
   `dao.agent.schema.md` section 6. One `:task/status` assertion per
   changed task, through the governor's strict wrapper.
4. **Judge leases** (section 6.1) and append `:lapsed` reclaims.
5. **Authorize merges** (section 5) and append `:merge/*` facts.
6. **Wake parked seats** whose park reason has cleared (section 6.3).

Each step is a function of the previous step's state; the governor holds
no state across rounds except its transactor, its cursors, and its last
tick reading.

## 5. Triad consensus policy

### 5.1 Roles

```text
+-------------+---------------+--------------------------------------+
| Agent       | Role          | May assert                           |
+-------------+---------------+--------------------------------------+
| Claude      | :synthesizer  | :claim proposals, :artifact/*,       |
|             |               | :claim/status :released              |
| Codex       | :reviewer     | :claim proposals (on :review tasks), |
|             |               | :review/*                            |
| AGY         | :governor     | :task/*, claim answers, :lapsed,     |
|             |               | :merge/*, observation datoms         |
+-------------+---------------+--------------------------------------+
```

The table is enforced at each seat's own strict wrapper through a `:fns`
predicate over `:agent/role`, and again at the governor's read side: a
`:task/status` assertion in a non-governor stream is ignored by every
derivation query because they bind `$gov` explicitly
(`dao.agent.schema.md`, section 1.1).

A seat's role is not a property of the model behind it. Any model can
occupy any seat; the rationale for the default assignment is in
`dao.agent.md`, section 6, and is a composition choice.

### 5.2 Dual sign-off

A merge to a canonical stream requires two facts from two streams:

1. A `:review/verdict :approved` from a `:reviewer` seat on the
   artifact, with every reviewer that reviewed it agreeing
   (`latest-verdict`, conjunctive).
2. A `:merge/authorized-by` from the `:governor` seat naming that
   review.

The governor appends fact 2 only after deriving fact 1 through
`merge-authorized?`'s inner join, and only when the artifact's claim
was `:granted` and not lapsed at the artifact's `t` (no strays). The
synthesizer signs nothing; its artifact is the thing signed. The
governor's authorization is the second signature, not a third vote.

### 5.3 What "merge" means here

A merge authorization is a datom. The act of merging, meaning appending
the artifact's AST datoms or image-identity datoms to a canonical
program stream, is performed by whichever seat owns that canonical
stream (in the initial composition, the governor). It is an ordinary
`:stream-put` of datoms already stored in jing and already identified
by R or H; nothing is copied as text. A host-side git operation, if the
composition wants one, is a host tool reached through the inward client
with full provenance (`dao.agent.mcp.client.md`, section 6), and is
never a precondition of the board's `:approved` state.

### 5.4 Contradictory verdicts

Two reviewers disagreeing is not a tie to break; it is a `:defect`
(reviews are conjunctive). The governor re-opens the task with
`:task/phase :implement` and the synthesizer's next prompt includes both
findings keys. A reviewer that reverses its own verdict does so by a new
`:review/*` entity at a later `t`; `latest-verdict` takes the newest
within one source. The board never rewrites a review.

## 6. Liveness and deadlock detection

### 6.1 Clock as a stream

The harness has no clock. A **tick adapter** (`datom.world.md`, Host
Boundaries, "Events in") transforms a host timer firing into one datom
on a tick stream the governor reads:

```clojure
[tick :dao.lease/event :dao.lease/tick]
[tick :dao.lease/reading {:ms 1758700000000}]
```

The governor's latest reading is the only "now" any policy consults.
Lease expiry is `(dao.lease/expired? grant reading tolerance)` over the
grant's `:claim/lease-expiry` and the reading, with the composition's
unit table. A seat's own timestamps (`:claim/proposed-at` and friends)
never enter the judgment.

### 6.2 Stalled claims and expired leases

```text
+-----------------------------+----------------------------------------+
| Condition (at govern step 4)| Action                                 |
+-----------------------------+----------------------------------------+
| :granted claim, reading     | Append :claim/status :lapsed with      |
| past :claim/lease-expiry,   | :claim/cause :silence; advance task to |
| no artifact bound to it     | :lapsed then :ready (attempt + 1)      |
+-----------------------------+----------------------------------------+
| :granted claim, artifact    | Nothing; the artifact is the renewal   |
| present before expiry       | and the task is :implemented           |
+-----------------------------+----------------------------------------+
| :implemented task, no review| Append a :task/phase :review re-assert |
| within :policy/review-lease | at new t (a nudge visible to reviewers)|
| of the phase change         | then, after a second period, escalate  |
|                             | to :liveness/stalled-review for the    |
|                             | composition's operator                 |
+-----------------------------+----------------------------------------+
| Seat parked with            | Governor holds the park id; on a tick  |
| {:reason :quota             | with reading past :until, wake with    |
|  :until reading}            | :resume value {:quota :reset}          |
+-----------------------------+----------------------------------------+
```

A lapsed lease does not punish the holder; its next proposal on that
task carries `:claim/attempt 2`, which loses only to a first-attempt
rival (`dao.agent.schema.md`, section 5.2, step 2a).

### 6.3 Parked seats and quota exhaustion

When a seat's model answers a rate-limit refusal, the inward client's
response classifies it as
`{:dao.stream.apply/error {:kind :quota :until reading}}`
(`dao.agent.mcp.client.md`, section 5.3). The VM's FFI reader is parked
already; the seat records
`[park :seat/park-id id] [park :seat/park-reason :quota]
 [park :seat/park-until reading] [park :seat/snapshot-key jing-key]`
in its own stream, with the sparse continuation snapshot stored in jing
under `jing-key`. The governor reads those datoms and, at step 6 of each
round, resumes every park whose `:until` the latest reading has passed.

No other seat waits. Codex being quota-locked leaves its parked review
in jing and the task at `:implemented`; Claude's next beat finds another
`:ready` task. This is the parent document's "other agents continue
their independent work without blocking or polling", realized as a
datom the governor reads on tick.

### 6.4 Deadlock detection

The board can deadlock in exactly these ways, each detectable by query
over the governor's own stream and the tick stream, and each with one
governor action:

```text
+----+-----------------------------------+-----------------------------+
| D1 | Every :ready task depends on a    | Emit :liveness/dependency-  |
|    | task that is not :approved and    | cycle naming the cycle; the |
|    | the dependency graph has a cycle  | cycle is computed by a      |
|    | (constructed explicitly from      | bounded recursive rule over |
|    | :task/depends-on tuples)          | :task/depends-on, not       |
|    |                                   | assumed from any graph type |
+----+-----------------------------------+-----------------------------+
| D2 | All seats parked with :quota and  | Emit :liveness/all-quota    |
|    | no :until passed                  | with the earliest :until;   |
|    |                                   | no action until then        |
+----+-----------------------------------+-----------------------------+
| D3 | A task cycles :ready -> :claimed  | After :policy/max-attempts, |
|    | -> :lapsed more than N times      | set :task/status :defect    |
|    |                                   | with :task/defect-cause     |
|    |                                   | :unclaimable; operator      |
|    |                                   | review                      |
+----+-----------------------------------+-----------------------------+
| D4 | A task cycles :review -> :defect  | After :policy/max-reviews,  |
|    | -> :ready more than N times       | same as D3 with cause       |
|    |                                   | :unconvergent               |
+----+-----------------------------------+-----------------------------+
| D5 | Governor's own transactor answers | Governor cannot write; it   |
|    | :dao.stream/full repeatedly       | parks as a writer (Model 1) |
|    |                                   | and the composition's       |
|    |                                   | retention policy is at fault|
+----+-----------------------------------+-----------------------------+
```

`:liveness/*` facts are datoms in the governor's stream. An operator
console is just another observer of that stream.

### 6.5 Governor liveness

The governor is a seat and can park. Its own liveness is judged by the
composition that iterates `round`: if `round` is not called, nothing
happens, and that is correct. A governor that crashes mid-round has
appended zero or more atomic records, each one complete (T1). On
restart it rebuilds its cursors from origin, re-reads its own stream to
recover granted claims and observation datoms, and continues; grants it
was about to append but did not are simply not made, and the proposals
are still unanswered in the next round. There is no partial grant.

## 7. Seat and harness state

```clojure
;; one seat
{:agent          {:agent/id kw :agent/role kw :agent/model str}
 :transactor     transactor-value          ; single writer to own stream
 :own-stream     handle
 :cursors        {stream-id cursor}        ; advancing
 :origin-cursors {stream-id cursor}        ; kept for completeness
 :sources        {name d5-source}          ; read-only
 :vm             kernel-state-or-nil       ; R4 state when running
 :parked         {park-id {:reason kw :until reading :snapshot-key k}}
 :client         mcp-client-state          ; dao.agent.mcp.client
 :budgets        {:reads-per-beat n :max-reprompts n :steps n}
 :jing-handle    handle
 :prompt         {:role-prompt str}}       ; text, the only prose here

;; the harness
{:seats     {agent-id seat}
 :governor  seat                            ; role :governor
 :tick      {:cursor cursor :reading reading}
 :policy    {:lease {task-type duration}
             :review-lease duration
             :max-attempts n
             :max-reviews n
             :units {:ms 1 :minutes 60000}}
 :canonical {:stream handle :transactor transactor-value}}
```

Every field is supplied by the composition or derived by `round`. No
field is a host handle the harness created for itself; `seat` receives
handles, it does not open them.

## 8. Test contract

`test/dao/agent/harness_test.cljc` pins, over `memory-log` streams, a
mock model bridge that answers scripted responses, and a scripted tick
stream:

1. Two-seat loop: a synthesizer claims, is granted, deposits an
   artifact; a reviewer reads it, deposits `:approved`; the governor
   authorizes a merge; the canonical stream receives the identity
   datoms. Every intermediate `:task/status` appears once, in order.
2. Claim race: two synthesizers propose in one round; exactly one grant,
   one `:lost` refusal, and the loser's next beat finds another task.
3. Lease lapse: a granted seat whose scripted model never answers is
   lapsed at the first tick past expiry; the task returns to `:ready`
   with attempt 2 visible on the next proposal.
4. Quota park: a scripted quota refusal parks the seat with a jing
   snapshot; a tick past `:until` resumes it; the resumed continuation
   produces the artifact; no other seat's beat count changed while it
   was parked.
5. Backpressure: a synthesizer over a capacity-1 own stream parks as a
   writer on `:full` and completes the append after the governor drains;
   the transactor `t` is allocated once.
6. No polling: with no ticks and no appends, `round` called N times
   performs zero `:stream-next` reissues after the first block (counted
   by a stream double).
7. Deadlock D1: a two-task dependency cycle produces one
   `:liveness/dependency-cycle` fact naming both tasks.
8. Governor restart: after `k` rounds, rebuild the governor from its
   stream and cursors from origin; the next round's grants equal the
   grants a never-restarted governor would make over the same inputs.
9. Role enforcement: a synthesizer seat's wrapper refuses a
   `:task/status` assertion; a `:task/status` datom planted in a
   synthesizer stream is ignored by `task-status`.
10. Purity: `round` over equal harness values and equal stream contents
    yields equal harness values.

## 9. Non-goals

- Multi-governor boards and governor failover.
- Speculative execution or branch-and-commit (`agent-smith.md`, "Ask,
  Simulate, Commit"); a later phase.
- Prompt engineering. The role prompts are composition data.
- Any communication path between seats other than their streams.
