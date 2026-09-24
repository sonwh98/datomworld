# dao.agent.schema: The Stigmergic Coordination Board

Status: design specification (2026-09-24); not implemented

Parent: `docs/design/dao.agent.md`, section 7. This document specifies the
datom vocabulary through which agents coordinate: tasks, claims,
artifacts, reviews, and verdicts. Siblings: `dao.agent.mcp.server.md`,
`dao.agent.harness.md`, `dao.agent.mcp.client.md`.

## 1. Objective and position

Agents never address one another. They perceive the board by querying
`dao.space` and act by appending to their own streams. The board is
therefore nothing but a vocabulary: a set of attributes whose meaning
every participant agrees on, installed as `dao.space.schema` datoms so
that a joining agent can read the treaty with `q` at startup
(`dao.space.schema.md`, section 1: "a queryable vocabulary treaty").

This document defines:

1. The attribute dictionary (section 3), as `dao.space.schema` tx-data.
2. The task lifecycle as a state machine over datoms (section 4).
3. The linearization rule for concurrent claims (section 5).
4. The queries that derive every state fact (section 6), following
   "derive, don't persist".

What it does not define: who runs which query, when, or what to do with
the answer. That is `dao.agent.harness.md`.

### 1.1 One board, many streams

There is no single board stream. Every agent, and the governor, writes
only to its own stream (`dao.agent.md`, Invariant 2). The board is the
union of those streams as separate query db-values, joined by value
(`:task/id`, `:agent/id`) and never by stream-local entity id
(`dao.space.schema.md`, section 8, "Cross-stream refs are
interpreter-relative").

```text
  claude stream     codex stream      agy (governor) stream
  +-----------+     +-----------+     +---------------------+
  | claim     |     | review    |     | task                |
  | artifact  |     | verdict   |     | claim grant / lapse |
  | ...       |     | ...       |     | merge authorization |
  +-----------+     +-----------+     +---------------------+
        \                |                     /
         \               |                    /
          v              v                   v
        +------------------------------------------+
        |  q over [claude codex agy] as db-values  |
        |  joined on :task/id and :agent/id values |
        +------------------------------------------+
```

Consequence: a reader must name which streams it reads. The schema
carries no notion of "all streams"; a composition supplies the source
set, and the harness records it as a datom so the choice is auditable.

## 2. Namespace and file box

```text
New: src/cljc/dao/agent/schema.cljc      tx-data, predicates, queries
New: test/dao/agent/schema_test.cljc
Existing edits: none
Depends on: dao.space.schema (bootstrap, transactor, current),
            dao.space.query (q, match), dao.space.transactor
Must not change: dao.space.*, dao.stream.*, dao.jing.*, yin.vm.*
```

`dao.agent.schema` exports:

- `schema-tx`: the tx-data vector of section 3, installable after
  `(dao.space.schema/bootstrap)`.
- `transition?`: `(status-from status-to) -> boolean` from section 4.
- `task-status`, `live-claim`, `latest-verdict`, `merge-authorized?`:
  the queries of section 6, each a pure function of source values.
- `linearize-claims`: the pure function of section 5.

Nothing in the namespace holds a transactor or a stream. Callers pass
sources in and receive relations or facts out.

## 3. Attribute dictionary

All attributes are installed through the `dao.space.schema` wrapper in
strict mode by the governor at board genesis. Types are drawn from the
supported cross-host set: `string`, `keyword`, `boolean`, `long`, `ref`.
`instant` is deferred in `dao.space.schema`; timestamps are therefore
`long` milliseconds since the epoch, taken from the writer's injected
clock, and are diagnostic only. Ordering never depends on them
(section 5).

Entity ids are stream-local (`dao.space.schema.md`, section 2). Every
cross-stream reference below is by a unique value attribute, written as a
lookup ref `[:task/id "..."]` in tx-data and resolved by the writer's own
wrapper against its own stream. Where the target entity lives in another
stream, the writer copies the value into a plain attribute (`:claim/task`
holds the `:task/id` string), because a `:db.type/ref` cannot denote an
entity in a foreign stream.

### 3.1 `:agent/*`

```text
+------------------+-------------+------+--------+---------------------+
| Attribute        | valueType   | card | unique | Meaning             |
+------------------+-------------+------+--------+---------------------+
| :agent/id        | keyword     | one  | yes    | :agent/claude etc.  |
| :agent/model     | string      | one  |        | model identifier    |
| :agent/role      | keyword     | one  |        | :synthesizer |      |
|                  |             |      |        | :reviewer | :governor|
| :agent/stream    | string      | one  |        | logical stream id   |
+------------------+-------------+------+--------+---------------------+
```

Each agent asserts its own `:agent/*` entity in its own stream at
startup. The governor's roster is a query over every source for
`:agent/id`, not a list held anywhere.

### 3.2 `:task/*`

```text
+-------------------+-----------+------+--------+---------------------+
| Attribute         | valueType | card | unique | Meaning             |
+-------------------+-----------+------+--------+---------------------+
| :task/id          | string    | one  | yes    | uuid; the value key |
| :task/title       | string    | one  |        | human title         |
| :task/type        | keyword   | one  |        | capability class    |
| :task/status      | keyword   | one  |        | section 4 state     |
| :task/spec-address| string    | one  |        | jing key of spec    |
| :task/phase       | keyword   | one  |        | :implement |        |
|                   |           |      |        | :review | :merge    |
| :task/created-at  | long      | one  |        | ms, diagnostic      |
| :task/depends-on  | string    | many |        | :task/id values     |
+-------------------+-----------+------+--------+---------------------+
```

`:task/status` and `:task/phase` are asserted only by the governor (the
task's author stream). Other agents never assert `:task/*`; they assert
claims, artifacts, and reviews, and the governor derives the next status
from those (section 6) and asserts it. This keeps one writer per task
entity and makes the state machine a single stream's card-one history.

`:task/type` values are open. The initial vocabulary is
`:compiler-engineering`, `:review`, `:design`, `:test`; an agent's
capability match is a query over `:task/type` against the agent's own
declared `:agent/capabilities` (card-many keyword, added by an agent to
its own `:agent/*` entity when it needs it; not part of the initial
treaty).

### 3.3 `:claim/*`

```text
+--------------------+-----------+------+--------+--------------------+
| Attribute          | valueType | card | unique | Meaning            |
+--------------------+-----------+------+--------+--------------------+
| :claim/task        | string    | one  |        | :task/id value     |
| :claim/agent       | keyword   | one  |        | :agent/id value    |
| :claim/attempt     | long      | one  |        | 1, 2, ... per agent|
|                    |           |      |        | per task           |
| :claim/proposed-at | long      | one  |        | ms, diagnostic     |
| :claim/epoch       | long      | one  |        | governor stream t  |
|                    |           |      |        | of the grant       |
| :claim/lease-expiry| long      | one  |        | ms; grant + lease  |
| :claim/status      | keyword   | one  |        | :proposed |        |
|                    |           |      |        | :granted |         |
|                    |           |      |        | :refused | :lapsed |
|                    |           |      |        | :released          |
| :claim/proposal    | string    | one  | yes    | uuid minted by     |
|                    |           |      |        | the proposer       |
+--------------------+-----------+------+--------+--------------------+
```

A claim is two entities in two streams that share one `:claim/proposal`
value:

- The **proposal** in the proposer's stream carries `:claim/task`,
  `:claim/agent`, `:claim/attempt`, `:claim/proposed-at`,
  `:claim/proposal`, and `:claim/status :proposed`.
- The **answer** in the governor's stream carries `:claim/proposal`
  (the same value), `:claim/status` of `:granted` or `:refused`, and,
  when granted, `:claim/epoch` and `:claim/lease-expiry`.

Later lifecycle facts (`:lapsed` from the governor, `:released` from the
holder) are further entities carrying the same `:claim/proposal` value.
This is the `dao.lease.md` vocabulary specialized: proposal, grant,
refusal, release, reclaim, with the task as `:dao.lease/subject`.
Renewal is an artifact deposition (section 3.4); a holder that deposits
is alive.

`:claim/epoch` is the transaction `t` in the governor's stream at which
the grant was appended. It is the linearization point (section 5) and the
only ordinal that matters. A `long` timestamp never orders anything.

### 3.4 `:artifact/*`

```text
+---------------------+-----------+------+--------+-------------------+
| Attribute           | valueType | card | unique | Meaning           |
+---------------------+-----------+------+--------+-------------------+
| :artifact/id        | string    | one  | yes    | uuid              |
| :artifact/task      | string    | one  |        | :task/id value    |
| :artifact/claim     | string    | one  |        | :claim/proposal   |
| :artifact/format    | keyword   | one  |        | :debruijn-register|
|                     |           |      |        | | :debruijn-stack |
|                     |           |      |        | | :ast-datoms     |
|                     |           |      |        | | :document       |
| :artifact/image-r   | string    | one  |        | R (register)      |
| :artifact/image-h   | string    | one  |        | H (stack)         |
| :artifact/jing-key  | string    | one  |        | storage address   |
| :artifact/author    | keyword   | one  |        | :agent/id value   |
| :artifact/root      | string    | one  |        | named root id, if |
|                     |           |      |        | same-root pairing |
| :artifact/deposited-at | long   | one  |        | ms, diagnostic    |
+---------------------+-----------+------+--------+-------------------+
```

An artifact never carries code text. It carries R or H plus the jing key
under which the B6 index entry was minted, and the reviewer fetches by
identity through the linker. `:artifact/image-r` and `:artifact/image-h`
are both optional; an artifact with both is a same-root pairing claim
and must carry `:artifact/root` so a verifying receiver can re-lower
(`yin.vm.debruijn.linker.md`, section 7).

`:artifact/claim` binds the artifact to the granted claim under which it
was produced. An artifact whose claim is not `:granted` at the artifact's
observation time is a **stray**: it is visible, it is provenance, but it
does not advance the task (section 6.2).

### 3.5 `:review/*` and `:verdict/*`

```text
+-----------------------+-----------+------+--------+-----------------+
| Attribute             | valueType | card | unique | Meaning         |
+-----------------------+-----------+------+--------+-----------------+
| :review/id            | string    | one  | yes    | uuid            |
| :review/artifact      | string    | one  |        | :artifact/id    |
| :review/task          | string    | one  |        | :task/id        |
| :review/reviewer      | keyword   | one  |        | :agent/id       |
| :review/verdict       | keyword   | one  |        | :approved |     |
|                       |           |      |        | :defect         |
| :review/findings-count| long      | one  |        | number of       |
|                       |           |      |        | findings        |
| :review/findings-key  | string    | one  |        | jing key of the |
|                       |           |      |        | findings value  |
| :review/reviewed-at   | long      | one  |        | ms, diagnostic  |
+-----------------------+-----------+------+--------+-----------------+
```

Findings are a content-addressed value in `dao.jing`, not datoms, for the
same reason task specs are: they are documents the reviewer authored,
and their internal structure is the reviewer's. What the board needs is
the count and the key. A `:defect` verdict with `:review/findings-count
0` is a schema-structure violation and is refused by the reviewer's
strict wrapper through a `:fns` predicate the reviewer's composition
supplies; the board does not trust it if it appears from a lax writer,
and the governor's derivation treats it as `:defect` regardless.

`:verdict/*` in the parent document is folded into `:review/verdict`.
A separate verdict entity would duplicate authority over a fact the
review row already holds ("derive, don't persist").

### 3.6 `:merge/*`

```text
+----------------------+-----------+------+--------+------------------+
| Attribute            | valueType | card | unique | Meaning          |
+----------------------+-----------+------+--------+------------------+
| :merge/task          | string    | one  |        | :task/id         |
| :merge/artifact      | string    | one  |        | :artifact/id     |
| :merge/review        | string    | one  |        | :review/id       |
| :merge/authorized-by | keyword   | one  |        | :agent/id, must  |
|                      |           |      |        | be a :governor   |
| :merge/target        | string    | one  |        | canonical stream |
|                      |           |      |        | logical id       |
+----------------------+-----------+------+--------+------------------+
```

A merge authorization is the governor's sign-off; the reviewer's
`:approved` is the other. Dual sign-off (`dao.agent.harness.md`, section
5) is the conjunction of those two facts, derived by query, never
asserted as a third fact.

### 3.7 The tx-data

`schema-tx` is one vector of entity maps in the `dao.space.schema`
vocabulary, one per attribute above, with explicit `:db/id`s from 21
upward (the bootstrap occupies 16 to 20). The governor installs it as
the second transaction of its stream, after the bootstrap; every other
agent installs the same vector as the second transaction of its own
stream, so each wrapper can validate its own writes. Identical
vocabulary in every stream is the treaty; a stream whose schema differs
is read by a governor as a raw stream, with no card-one collapse, and
its claims are refused with `:claim/status :refused` and a
`:claim/refusal-cause :vocabulary`.

## 4. Task lifecycle

### 4.1 States and transitions

`:task/status` is a card-one attribute in the governor's stream. Its
history is the task's lifecycle. The allowed transitions form a directed
acyclic graph with one cycle-free retry edge:

```text
  :ready ---> :claimed ---> :implemented ---> :review ---> :approved
     ^            |               |              |
     |            |               |              +-------> :defect
     |            v               v                            |
     +-------- :lapsed <----------+                            |
     ^                                                         |
     +---------------------------------------------------------+
                      (re-open with a new :task/phase :implement,
                       counted in :claim/attempt)
```

```text
+---------------+---------------+--------------------------------------+
| From          | To            | Derived from (section 6)             |
+---------------+---------------+--------------------------------------+
| :ready        | :claimed      | a :granted claim exists              |
| :claimed      | :implemented  | an artifact bound to that claim      |
| :claimed      | :lapsed       | lease expired, no artifact           |
| :implemented  | :review       | governor advanced :task/phase        |
| :implemented  | :lapsed       | reviewer lease expired (rare)        |
| :review       | :approved     | latest review verdict :approved      |
| :review       | :defect       | latest review verdict :defect        |
| :defect       | :ready        | governor re-opens; attempt + 1       |
| :lapsed       | :ready        | governor re-opens; attempt + 1       |
+---------------+---------------+--------------------------------------+
```

`:approved` is terminal for the task's lifecycle; merge authorization is
a separate fact (section 3.6) and does not change `:task/status`.

`transition?` is the table above as a set of pairs. The governor's
wrapper is constructed with a `:fns` predicate that refuses a
`:task/status` assertion whose `(from to)` is not in the set, where
`from` is the live value in the wrapper's current-state index. A lax
foreign writer cannot corrupt the machine because only the governor's
stream is read for `:task/status` (section 1.1).

### 4.2 Phase versus status

`:task/phase` is which kind of work the task wants next (`:implement`,
`:review`, `:merge`); `:task/status` is where the task is in the
lifecycle. They are separate because a `:defect` outcome re-opens the
task at `:ready` with `:task/phase :implement`, while an `:approved`
outcome moves it to `:task/phase :merge` with `:task/status :approved`.
Agents match on `:task/phase` to find work; the governor advances
`:task/status` to record what happened. Neither is derivable from the
other, so both persist.

## 5. Linearization and conflict rules

### 5.1 The problem

Two agents may propose claims on the same task. Each proposal is in a
different stream; the streams share no clock and no `t` axis. There is
no CAS in the medium and no global transactor (`dao.space.schema.md`,
section 6: "Schema constrains streams, not fleets").

### 5.2 The rule

The governor is the single writer of grants. Its stream's `t` is the
linearization axis for claims, and its grant is the "lease acquisition"
of the parent document. The rule, as a pure function:

```clojure
(defn linearize-claims
  "proposals: relation of :proposed claims across all sources, each row
   tagged with its source name and source-local t.
   granted: relation of :granted claims from the governor source.
   Returns the vector of proposals to grant, in the order the governor
   must append them. Pure; no clock."
  [proposals granted] -> [proposal-row ...])
```

For each task with at least one unanswered proposal and no live grant:

1. Candidates are the proposals for that task whose `:claim/attempt`
   equals `1 + (count of that agent's prior lapsed or refused claims on
   that task)`. A proposal with a stale attempt number is refused with
   `:claim/refusal-cause :stale-attempt`; it was made against a board
   the proposer had not finished reading.
2. Among candidates, the winner is chosen by, in order:
   a. lowest `:claim/attempt` (a first attempt beats a retry);
   b. the proposal the governor observed first in its own ingress
      merge order (`dao.agent.harness.md`, section 3.3), which is a
      fact the governor recorded as `[obs :ingress/offset n]` when it
      read the proposal;
   c. least `:agent/id` under `dao.space.index/compare-vals`.
3. Every other candidate is refused with `:claim/refusal-cause :lost`.

Step 2b is the only place arrival order enters, and it enters as a datom
the governor wrote about its own observation, so replaying the governor's
stream reproduces the same grants. Step 2c exists so that a governor
restarted from a published index, with observation datoms lost in the
unpublished suffix, still decides deterministically from data alone.

A task with a live grant (`:granted`, not `:lapsed`, not `:released`)
accepts no new grant. Proposals against it are refused with
`:claim/refusal-cause :held`. They are not queued: the proposer re-reads
the board and proposes again when the task is `:ready`.

### 5.3 Same-offset ties within one stream

One agent cannot propose twice for the same task at the same `t`: the
proposer's strict wrapper refuses a second `:claim/proposal` for the same
`[:claim/task :claim/agent :claim/attempt]` triple through a `:fns`
predicate, in both modes as a schema-structure class violation, because
two proposals with one attempt number denote nothing the governor can
answer.

### 5.4 What the transactor gives and does not give

`dao.space.transactor` guarantees each governor append is one atomic
record with one `t` (T1, T2). It does not coordinate between governors
(T6). Exactly one governor stream is authoritative for a board; a second
governor is a second board. Governor failover is a harness concern
(`dao.agent.harness.md`, section 6.4) and is out of scope here.

## 6. Derivation queries

Every state fact below is computed, never stored, except the ones the
governor asserts back as `:task/status` for the sake of cheap matching.
All queries take a map `{name source}` of d5 sources passed through
`dao.space.schema/current` and are pure.

### 6.1 `task-status`

```clojure
'[:find ?status
  :in $gov ?task-id
  :where
  [$gov ?t :task/id ?task-id]
  [$gov ?t :task/status ?status]]
```

Card-one collapse in `schema/current` makes this a single row.

### 6.2 `live-claim`

The claim a task is currently held under, if any:

```clojure
'[:find ?proposal ?agent ?epoch ?expiry
  :in $gov [$agents ...] ?task-id
  :where
  [$gov ?g :claim/proposal ?proposal]
  [$gov ?g :claim/status :granted]
  [$gov ?g :claim/epoch ?epoch]
  [$gov ?g :claim/lease-expiry ?expiry]
  [$agents ?p :claim/proposal ?proposal]
  [$agents ?p :claim/task ?task-id]
  [$agents ?p :claim/agent ?agent]
  (not [$gov ?l :claim/proposal ?proposal]
       [$gov ?l :claim/status :lapsed])
  (not [$agents ?r :claim/proposal ?proposal]
       [$agents ?r :claim/status :released])]
```

Whether the lease has expired is not in this query: expiry is judged by
the harness against its tick stream (`dao.agent.harness.md`, section
6.1), because the query engine has no clock and must not acquire one.

### 6.3 `latest-verdict`

```clojure
'[:find ?verdict ?review-id ?t
  :in $reviewers ?artifact-id
  :where
  [$reviewers ?r :review/artifact ?artifact-id]
  [$reviewers ?r :review/id ?review-id]
  [$reviewers ?r :review/verdict ?verdict ?t]]
```

with the caller taking the row of greatest `?t` within one reviewer
source and, across reviewer sources, treating any `:defect` as the
verdict: reviews are conjunctive. One reviewer's `:defect` is a defect.

### 6.4 `merge-authorized?`

```clojure
'[:find ?merge
  :in $gov $reviewers ?task-id
  :where
  [$gov ?m :merge/task ?task-id]
  [$gov ?m :merge/review ?review-id]
  [$gov ?m :merge/authorized-by ?gov-agent]
  [$gov ?a :agent/id ?gov-agent]
  [$gov ?a :agent/role :governor]
  [$reviewers ?r :review/id ?review-id]
  [$reviewers ?r :review/verdict :approved]
  [(identity ?m) ?merge]]
```

Dual sign-off is exactly this join: a governor-authored merge fact that
names a reviewer-authored `:approved` review. Neither fact alone
authorizes.

### 6.5 Stray artifacts

An artifact whose `:artifact/claim` has no `:granted` answer, or whose
grant lapsed before the artifact's own source-local `t`, is a stray.
Strays are reported by the governor as
`[obs :board/stray-artifact artifact-id]` for audit and are otherwise
ignored by every transition.

## 7. Conflict cases, enumerated

```text
+----+----------------------------------+-------------------------------+
| #  | Situation                        | Resolution                    |
+----+----------------------------------+-------------------------------+
| C1 | Two first-attempt proposals,     | Governor ingress order (2b);  |
|    | same task                        | loser refused :lost           |
| C2 | Retry proposal vs first attempt  | First attempt wins (2a)       |
| C3 | Proposal against a held task     | Refused :held; no queue       |
| C4 | Proposal with stale attempt      | Refused :stale-attempt        |
| C5 | Two proposals, same agent, same  | Proposer's wrapper refuses    |
|    | attempt                          | the second before append      |
| C6 | Artifact after lease lapse       | Stray; task already :lapsed   |
| C7 | Two reviews, one :defect         | :defect (conjunctive)         |
| C8 | Governor observation datoms lost | Tie by least :agent/id (2c)   |
| C9 | Stream with different schema     | Read raw; claims refused      |
|    |                                  | :vocabulary                   |
+----+----------------------------------+-------------------------------+
```

## 8. Test contract

`test/dao/agent/schema_test.cljc` pins:

1. `schema-tx` installs after the bootstrap through a strict wrapper on
   a `memory-log` stream with no rejection; the same vector installs
   identically on three streams.
2. Every attribute has the declared type, cardinality, and uniqueness,
   read back through `schema/current`.
3. `transition?` accepts exactly the table of section 4.1 and the
   governor's wrapper refuses an out-of-table `:task/status` assertion.
4. `linearize-claims` over fixtures for C1 through C8 returns the
   documented winner and refusal causes, and is a pure function: the
   same relations in any row order give the same vector.
5. Each derivation query of section 6 answers its documented row over a
   three-stream fixture (governor, synthesizer, reviewer).
6. A stray artifact does not advance a `:lapsed` task.
7. Cross-stream references are values only: no `:db.type/ref` attribute
   in `schema-tx` is ever written with a foreign-stream entity id.

## 9. Non-goals

- A global task registry or a shared board stream. The board is a query.
- Timestamps as ordinals. Every `long` time attribute is diagnostic.
- Any attribute an existing query can derive. Candidates rejected on
  that ground: `:task/claimant`, `:task/artifact-count`,
  `:claim/expired?`, `:verdict/*` as a separate entity.
- Governor failover and multi-governor boards.
