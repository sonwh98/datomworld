# The published head trace: follow a publisher's index HEAD by principal

Status: **design proposed 2026-10-05, revision 3 (the simple core);
nothing here is implemented.** Author seat: claude-fable-5-1 (Lead System
Architect). Revision 2 rewrote the design to the scope the owner chose:
one configured source per principal, one candidate at a time, durable
installed heads, loopback serving. Relaying, many sources and serving
off loopback are section 12's, each its own later design; section 8
shows that none of them changes the core. Revision 3 closes the third
review (the closing notes say how). It awaits the reviewer's sign-off
and the owner's answers to section 13. Subordinate to
[`datom.world.md`](./datom.world.md),
[`dao.stream.md`](./dao.stream.md),
[`dao.stream.remote.md`](./dao.stream.remote.md),
[`dao.stream.ws.md`](./dao.stream.ws.md),
[`dao.jing.dht.md`](./dao.jing.dht.md) and
[`yin.vm.linker.dht.md`](./yin.vm.linker.dht.md). In sections 5 to 7
every sentence is a rule.

## 1. Objective

A publisher deposits its index HEAD as a signed trace on a stream it alone
writes. A reader that declares the publisher's principal follows that
trace, loads each index it names, and resolves `(require 'alib)` against
the newest head it has installed. The reader is handed no manifest
address: the join token is `yin:<host:port>/<principal>`.

This is the "latest-root discovery" that `yin.vm.linker.dht.md` sections 1
and 13 and `dao.jing.dht.md` section 1 (owner decision 3) left to a later
epic. It changes nothing in how a name is resolved once an index is
loaded, and nothing in the DHT.

## 2. The owner's words

Verbatim, 2026-10-05:

> is it possible to start the yin.repl to always reference whatever the HEAD
> is? (require 'alib) should always use the index manifest of the HEAD

> publishing a's head is a what stigmergy is for. a can publish its head and
> c can read it

On the scope, after two reviews:

> option 1 for now, but cross-machine is necessary once option 1 is done.
> however,if the abstraction is dao.stream, cross-machine would not require
> a complete redesign, right?

The orchestrator's readings (paraphrases, not the owner's words): the
head is a trace A deposits in a shared medium and C perceives, not a
mutable-pointer service C calls; and "option 1" is one configured source
per principal, one candidate load at a time with latest-wins, durable
installed-head persistence, and loopback following.

Both readings hold. Section 8 answers the question in the third quote.

## 3. The invariants it rests on

- **A host boundary is a stream boundary; a stream has no privileged
  reader and its meaning is not in it** (`datom.world.md`, Streams and
  Host Boundaries). The head is a value on a ring. The follower reads it
  with `cursor` and `next` and nothing else.
- **Derive, don't persist** (`datom.world.md`, Design Principles). The
  trace's sequence is the greatest transaction `t` of the index it names.
- **Sources stay separate db-values; union is explicit**
  (`datom.world.md`, Tuples and Datoms). A followed index is never merged
  into the reader's own.
- **`dao.jing` is syntax, agents are semantics.** The DHT learns nothing
  about roots (`dao.jing.dht.md` section 1).
- **Any stream may be exposed over WebSocket or UDP, peer to peer; no
  server, no client, no privileged node** (`dao.stream.remote.md`
  section 1, owner, 2026-09-25). The board is one mirror-table entry.
- **A fetch has no deadline; liveness is the drive's, through
  `dao.lease`; the linker is clock-free** (owner, 2026-09-26). No rule
  below ends anything by time. Delays are counted in the node's ticks.
- **`dao.stream.apply` is independent of rpc** (owner, 2026-09-29). No
  apply word is added.
- **Plain Clojure first** (owner, 2026-10-01). Section 6 is that path.
- **No backward compatibility** (owner). The three-part token goes.
- **Single-writer provenance; a trace is deposited, never addressed**
  (`dao.agent.md` invariant 2, section 4.3).
- **Authority is the reader's composition** (`yin.vm.linker.dht.md`
  7.1). A trace is honoured only for a declared principal.

## 4. The starting tree

Read at `ee3498de`.

**Defects in the current design.**

- **D1. Joining by hydration forks the publisher's index.** `dht join`
  installs the token's manifest as the reader's own HEAD
  (`src/cljc/yin/repl/dht.cljc:202-212`); the reader's next round
  appends to it, and `refused-head` (`dht.cljc:46-53`) then refuses any
  later head. Correction: `dht join` stops hydrating; a followed head is
  a snapshot beside the reader's HEAD (5.5). Hydration itself is left
  alone (5.8).
- **D2. `yin.vm.linker.dht.md` 7.2** has no place for a snapshot that
  follows a principal. It gains one member (5.5), and sections 1 and 13
  drop the latest-root deferral (slice H1).
- **D3. `dao.space.dht/load` is silent across kinds.** A load of an
  address already recorded under another `:kind` is "left as it is"
  (`src/cljc/dao/space/dht.cljc:1166-1170`), so the caller gets a record
  that is not what it asked for. One consumer per address hid it. 5.5
  makes it a refusal.

**Contradictions, stated instead of designed around.**

- **C1.** The signature cannot live in `m`: `datom.md` (line 168) fixes
  `m` as an integer reference to a metadata entity.
- **C2.** A head under a DHT key contradicts `dao.jing.dht.md` section 1.
- **C3. A well-known name may not stand in for a stream's identity.**
  `dao.stream.md` (Envelopes, lines 277-285) makes
  `:dao.stream/identity` the identity of the sequence one transport
  instance owns; `dao.stream.remote.md` 2.2 keeps that. A board is a new
  ring in every process, so a fixed string used as its identity would
  name a different logical stream after each restart, and the reflection
  would report that string as its identity
  (`src/cljc/dao/stream/remote.cljc:509-519`). Section 5.1 therefore
  separates the two: a **name** is lookup data, resolved once to the
  ring's real descriptor, and the reader then attaches normally. This
  adds one request shape to `dao.stream.remote` and amends
  `dao.stream.remote.md`; it does not amend `dao.stream.md`. Slice H2
  carries it.
- **C4. `yin.repl` already has the alias this design refuses.** It
  serves two per-process rings under the fixed identities
  `"yin.repl/requests"` and `"yin.repl/answers"`
  (`src/cljc/yin/repl/connect.cljc:72-81`,
  `src/cljc/yin/repl/serve.cljc:224-225`). That is the same
  inconsistency, landed; it is not permission. It is outside this
  design and is named in section 12 as a defect with its fix available:
  the name resolution H2 adds.

**Gaps.** A loaded index does not survive a restart (the node's
`:loads` are process state). A name that moved under a live session is
silent. `announce!` is private and unsigned, correctly (5.4).

## 5. The design

### 5.1 Medium (decision 1)

**The head trace lives on a ring the publisher alone writes. A reader
reaches it as a `dao.stream` reader handle. That is the whole coupling.**

- The **head board** is a `dao.stream.ringbuffer` of capacity 1.
- **The follower takes a reader handle per principal and calls only
  `cursor` and `next` on it.** It does not know whether the handle is the
  ring itself, a reflection over WebSocket, or anything else. Section 8
  rests on this sentence.
- **Exposure in the core: `dao.stream.ws`, on loopback.** The publisher's
  node composes one WebSocket acceptor (`dao.stream.ws-project`) whose
  mirror table has one entry, the board, surface `#{:reader}`, **under
  the ring's own identity**, the random identity every ring is created
  with. It listens on TCP at **the same port number as the node's UDP
  socket**, path `/head`, so one `host:port` names both and the token
  needs no second address.
- **A name is looked up; it is never an identity (C3).** The **board
  name** of a principal is `"yin.head/"` followed by the principal id
  (`yin.vm.linker.dht.md` 6.2). It appears in no descriptor, no cursor
  and no `:dao.stream/identity`.
  - The serving peer's composition holds, beside its table, a **name
    map** `{name identity}`, each value a key of its table.
  - One request shape is added to `dao.stream.remote.md` 2.1. It carries
    `:dao.stream.remote/name n` in place of `:dao.stream/identity`, with
    op `:dao.stream/descriptor`, the only op a name may ride. The mirror
    answers the `descriptor` answer of the entry the name maps to, with
    that entry's true `:dao.stream/identity` and surface and the name
    echoed. An unmapped name is the `not-found` error with the name. A
    named request with any other op is malformed and dropped.
  - The asking link gains `resolve`: it sends that request and answers
    `transport-error` with `:dao.stream/retry? true` until the answer is
    filed, as `cursor` does (2.4). Its `ok` carries the remote
    descriptor of the real stream over the same channel.
  - The reader's ws composition resolves, then calls `attach!` on the
    descriptor answered. From there the contract is unamended: the
    reflection's identity is the ring's, and its cursors are the ring's.
  - A restarted publisher has a new ring and a new identity. The reader
    learns it by resolving again after `:source-lost` (5.3). Two nodes
    that share a key serve two rings with two identities; a reader's
    one source reaches one of them and never confuses their cursors.
- The board has its own endpoint. It is never entered in the REPL
  service's table: that table carries `yin.repl/requests`, which is
  evaluation.
- **Loopback only.** The endpoint is composed only when the node's bind
  host is a loopback literal. On any other bind the node serves no
  board, prints no token and says so. Section 8 says what lifts this.
- **One source per principal**: the address the reader was given. Nothing
  is put in the DHT, which is used as today to fetch the blobs.

**Availability.** Publisher running: the reader asks once per poll
interval and observes a new head at the first poll that is answered.
Publisher stopped: the reader keeps the head it installed and everything
it linked. Nothing times out. A reader that never reached its source has
no head; 5.6 says what a `require` does then.

Rejected: UDP on the DHT socket as the first exposure (section 8.3); a
DHT key (C2).

### 5.2 Trace schema (decision 2)

```clojure
{:yin.head/envelope {:yin.head/principal "ed25519:<64 lowercase hex>"
                     :yin.head/manifest  :segment/...
                     :yin.head/seq       1042}
 :yin.head/proof    {:yin.head/signature "<128 lowercase hex>"}}
```

- The key sets are closed; a trace carrying another key is malformed.
- No time and no address are written. A trace says nothing about where
  it was read, which is what lets any transport, and later any relay,
  carry it unchanged.
- The signed message is
  `UTF-8("yin.head/trace:v1\n") || dao.jing/canonical-bytes(envelope)`.
  The prefix is domain separation from name envelopes
  (`yin.vm.linker.dht.md` 6.3). The primitive is `yin.vm.linker.sign`'s
  Ed25519, unchanged. The principal is its own key.
- **The sequence is derived.** It is the greatest transaction `t` among
  the datoms of the index the manifest names: a nonnegative integer, and
  **0 is valid**, since the first transaction is `t = 0`
  (`src/cljc/dao/space/transactor.cljc:117-131`). An index of no datoms
  has no trace. The publisher stores no counter.
- **A reader confirms it.** A loaded index whose greatest `t` is not the
  trace's sequence refuses the trace, `:yin.head/seq-mismatch`. A loaded
  index with a malformed datom (a row that is not a vector, or a `t`
  that is not a nonnegative integer) is refused as data too,
  `:yin.head/index-invalid`, at confirmation and again when a persisted
  head is restored; the follower never throws on what an index holds.
- **Proven for the REPL path, enforced for every caller.** The HEAD
  writes of one directory carry strictly increasing sequences, across
  restart and `(reset)`. It holds for `yin.repl.index` because every
  publication commits a transaction first (`index.cljc:189-233,
  378-435`), and H0 pins it.
- `deposit!` takes the datoms, never a sequence, and refuses within one
  process a sequence equal to the last it signed with another manifest
  (`:yin.head/seq-collision`) or below it (`:yin.head/seq-regression`).
- **Hydration is the one HEAD write that commits no transaction, and it
  is inside the claim.** It is refused when the directory's HEAD names
  another manifest, and it writes nothing when HEAD already names the
  same one (`yin/repl/dht.cljc:46-53, 79-82`). So it is only ever a
  directory's first HEAD write. Its sequence is the hydrated index's own
  greatest `t`, as a restart's recovery has, and the next publication is
  one above it.
- **The claim is per directory, not per key.** A key that signs from two
  directories can sign two manifests at one sequence; readers refuse
  them (`:stale`, `:yin.head/equivocation`) and install neither wrongly.
- **The key kept and the directory lost** restarts the sequence; readers
  refuse the new traces as stale. The remedy is
  `yin.vm.linker.dht.md` 6.5's, unchanged and still available (5.8):
  hydrate the directory from the last published manifest before
  publishing, or take a new key. Only the last published manifest is the
  remedy: its trace is the one already signed, a reader judges it
  `:duplicate`, and the next round is a candidate. Hydrating an older
  manifest, or another publisher's under a key that has published, is the
  lost-directory case again.

Rejected: the `m` slot (C1); a stored counter; a `:prev` chain.

### 5.3 Retention (decision 3)

**Capacity 1. The latest head wins and the ring coalesces.**

- **First contact.** The reader mints `:dao.stream/oldest`; on a
  capacity-1 ring that is the current head. An empty ring is `blocked`.
- **A slow reader** is answered `gap` with the recovery cursor, the
  current head's position, and adopts it.
- **Anything else is a lost source.** On `cursor-mismatch`, `end`,
  `not-found`, or a `transport-error` that is not retryable, the
  follower drops the handle and its cursor and emits `:source-lost` with
  the outcome. Its owner attaches a fresh handle; the follower mints
  `:oldest` on it. A restarted publisher is this case, whatever the new
  ring's identity is and however the transport reports it.

### 5.4 Publisher behaviour (decision 4)

- One trace is deposited at every HEAD move: after the HEAD write, in
  the same call, before any network result, as HEAD itself moves
  (`yin.vm.linker.dht.md` 5.5.6). The deposit is a local append.
- The publisher does not batch; the ring does.
- At startup a node with a key and a recovered HEAD deposits that HEAD's
  trace, so a reader's first contact needs no new round. A completed
  hydration counts as that startup: it writes HEAD through the base
  store's `:head-fn`, not through the wrapper that calls `announce!`
  (`yin/repl/dht.cljc:208`), so the "deposit after `announce!`" rule
  below does not fire for it. The composition deposits the hydrated
  HEAD's trace once, when hydration completes.
- A node deposits when it has a key, a socket and `:publish?`.
- **`announce!` and the ledger ring stay as they are, beside the
  board.** The ledger ring is the node's private work queue of put
  addresses; `dao.space.dht` holds no key and knows nothing of `yin.*`.
  The composition that calls `announce!` calls `deposit!` after it.

### 5.5 Reader behaviour (decision 5)

**Following** is composition: `--dht-follow <64 hex>@<host:port>`, or
`:follow` to the plain API. A followed principal must be declared
(`--dht-principal`); otherwise startup is refused.

**Per principal the follower keeps three things, each with one job.**

<table>
<tr><th>Thing</th><th>What it is</th><th>What it decides</th></tr>
<tr><td>The <strong>floor</strong></td>
<td>The installed head's sequence, or <code>nil</code>: none was ever
installed. Read from the persisted trace (5.7).</td>
<td>Replay and rollback, and nothing else.</td></tr>
<tr><td>The <strong>candidate</strong></td>
<td>At most one verified trace above the floor. Process state.</td>
<td>What is loading. It proves nothing and raises nothing.</td></tr>
<tr><td>The <strong>installed head</strong></td>
<td>One trace: verified, loaded, confirmed by its index, persisted.</td>
<td>The snapshot set and the floor.</td></tr>
</table>

**The rule for one observed trace** is `judge`, a pure function of the
followed principal, the floor, the installed manifest and the trace:

<table>
<tr><th>Case</th><th>Outcome</th></tr>
<tr><td>Not the closed shape of 5.2, or a sequence that is not an
integer from 0 to 2^53 - 1</td>
<td><code>:yin.head/malformed</code></td></tr>
<tr><td>Principal is not the followed one</td>
<td><code>:yin.head/wrong-principal</code></td></tr>
<tr><td>Signature does not verify</td>
<td><code>:yin.head/bad-proof</code></td></tr>
<tr><td>The floor is <code>nil</code></td>
<td><code>:candidate</code>, whatever the sequence, 0 included</td></tr>
<tr><td>Sequence below the floor</td>
<td><code>:yin.head/stale</code></td></tr>
<tr><td>Sequence at the floor, the installed manifest</td>
<td><code>:duplicate</code></td></tr>
<tr><td>Sequence at the floor, another manifest</td>
<td><code>:yin.head/equivocation</code></td></tr>
<tr><td>Sequence above the floor</td>
<td><code>:candidate</code></td></tr>
</table>

- **`judge` has no source argument and no candidate argument.** Its
  answer for a trace is the same whoever carried it, in whatever order,
  and whatever is loading. One source is the degenerate case of many.
- **The floor is confirmed evidence only.** It moves at installation
  and at no other time. A candidate's sequence is a claim and is never
  compared against a later trace.

**The candidate: latest observed wins.**

- A trace judged `:candidate` **replaces** the current candidate, unless
  it is the same trace or the one last rejected (below). It replaces a
  candidate of a higher sequence too: the source's newest element is its
  current claim, and one slot has no capacity to exhaust.
- **A replaced candidate is released at once, and the new one starts
  loading in the same step.** The follower does not wait for the old
  load. So a newer head never waits on an older one, and no deadline is
  added.
- The candidate's load is `dao.space.dht/load` with the covered-index
  walk under the kind `:yin.head/candidate`. `loaded-indexes` lists only
  index-kind loads (`dao/space/dht.cljc:1214-1227`), so **a candidate is
  in no snapshot set and no name resolves through it**, with no change
  to that function.
- **Candidate records have owners.** Loads are keyed by address
  (`dao/space/dht.cljc:1166-1170`) and two followed principals may name
  one manifest, so the follower keeps, for the candidate-kind records
  it started, a map `{address #{principal}}`. It is follower state; the
  node learns nothing of it.
  - A principal whose candidate names an address **acquires** it: the
    principal joins the set, and the load is started if no record
    exists.
  - A principal **releases** an address when its candidate is replaced,
    when it is rejected, and **when it is installed**. Release removes
    the principal from the set. Only when the set is empty is the
    record removed: abandoned if `:loading`, forgotten if terminal.
  - So one principal's replacement or rejection never touches a load
    another principal still wants, and a sequence mismatch is one
    principal's verdict on its own trace, not on the record.
  - Release at installation is the cleanup that makes a later
    `load-index` by hand succeed: the candidate record is gone, the
    blobs are local, and the index-kind load completes without a fetch.
    The installed head does not need the record; the snapshot set reads
    the local store.
- **Kinds do not mix (D3).** `load` of an address already recorded under
  another kind is refused, `:dao.space.dht/kind-conflict`, instead of
  silently left. If the candidate's manifest is already recorded by
  hand under the index kind, the follower starts nothing, owns nothing,
  and reads that record. It never abandons a record that is not of the
  candidate kind, and it forgets one in a single case, the Unloadable
  rule below: a `:failed` index-kind record at its candidate's address.
  That record is the user's throughout: once `:loaded` it is in the
  snapshot set as any index load made by hand is, whatever the
  follower's verdict on the trace, and no release removes it. A record
  of any other kind at the candidate's address is neither read,
  forgotten nor restarted, whatever its status (`:loading`, `:loaded`
  or `:failed`): the candidate is reported `:yin.head/unloadable` once,
  with that record's failure or, for a record that has not failed, its
  kind and status, and waits until the record's owner removes it, and
  then loads as a candidate. The follower never reads such a record's
  datoms.
  A `load-index` by hand of a manifest that is loading
  as a candidate is refused with that code as data (H1 carries the
  host module's translation), and succeeds once the head is installed.
- **`abandon` leaves nothing behind.**
  `(dao.space.dht/abandon node address)` removes a `:loading` record,
  emits no event, and retires that record's interest in the fetch
  client: the retained unsent request, if it is this record's, by
  `dao.jing.content.step/abandon`; otherwise its outstanding id by
  `dao.jing.content.step/retire`, after which a late answer is
  unsolicited and dropped. Both functions exist. Each load has its own
  request id, so another load fetching the same address is untouched.
  `advance-loads` today steps the client only while a record is
  `:loading` (`dao/space/dht.cljc:1313-1321`); it changes to step it
  whenever a record is loading **or the client holds an unsent request,
  an outstanding id or an undelivered completion**, so what an abandon
  leaves is drained with no load active. What remains outside the node
  is one get inside the DHT, which ends by the DHT's own `get-ticks`
  and whose answer nobody claims.
- **Confirmed.** The load is `:loaded` and the greatest `t` of its
  datoms equals the sequence. The follower emits `:confirmed` with the
  trace and changes nothing else (5.7).
- **Rejected.** The load is `:loaded` and the check fails:
  `:yin.head/seq-mismatch`. The candidate is dropped, its address
  released, and its envelope id kept as the principal's **last
  rejected**, one value, so the same trace shown at every poll is not
  loaded again. The floor is where it was. Any other candidate trace
  takes the slot as usual. One value remembers one trace: two wrong
  traces shown in alternation are each walked again each time. After
  the first time their blobs are local, so that is a local walk, not a
  fetch.
- **What one slot does not give: progress under replay.** Every signed
  trace above the floor stays eligible. A source, or whoever sits on a
  cleartext channel to it, that alternates two eligible traces makes the
  reader release one load and start the other at each turn. Fetched
  blobs stay local, so a trace shown long enough to fetch one blob makes
  progress, and a true head whose load completes is installed and raises
  the floor past the older ones. But if no load completes within a turn,
  no head is installed for as long as the alternation lasts. The
  signature gives integrity and rollback protection; it does not give
  liveness. The work this can cause is bounded: one load per principal
  in the node, and in the DHT at most one orphaned get per turn, each
  ended by `get-ticks` and all under the DHT's pending-get bound. A
  stronger guarantee needs an authenticated channel or a reviewed
  scheduling policy, and both are section 12's.
- **Unloadable.** The load failed: `:yin.head/unloadable` with the
  failure as data. The candidate stays. After a delay in node ticks,
  doubling from `:repair-ticks` to `:repair-max-ticks`, the failed
  record is forgotten and the load started again in the same step
  **under the kind the record had, which is the candidate kind or the
  index kind and no other**, so a failed load made by hand at that
  address is restarted as an index load and stays the user's. A failed
  record of any other kind is left to its owner. No deadline ends the
  retries; a newer observed trace replaces the candidate.

**The snapshot set** (`yin.vm.linker.dht.md` 7.2, amended) is: the
manifest the directory's HEAD names; every index-kind load that is
`:loaded`; and **the installed head of each followed principal**, read
from the node's local store, where every blob of it already is. The
follower records the installed manifests on the node value;
`yin.vm.linker.dht/snapshots` reads them. Resolution (7.3) is otherwise
unchanged.

- The reader's own HEAD is never written by following.
- Installation replaces the principal's installed manifest in one step.
  No fold sees two heads of one principal, or none.

**A silent publisher** changes nothing. The follower records the node
tick reading at which its source last answered, as data for a drive
that wants to judge liveness with `dao.lease`. Nothing here reads it.

### 5.6 When `(require 'alib)` refreshes (decision 6)

**On a head event.** A require never asks the network for a newer head.

- A `require` that links resolves against the snapshot set as it stands.
- **A name may change meaning between two requires**: in two sessions,
  or before and after `(reset)`. The require's provenance carries the
  snapshot vector.
- **Dependency mismatches surface sooner.** When `base` is republished
  and a reader follows both `base` and `app`, `app`'s require refuses
  `:yin.link.dht/dependency-binding` with `:mismatch` (7.4) until `app`
  is republished. Following does not create this; it stops hiding it.
- **First contact.** A require whose name is `:absent` while some
  followed principal has no installed head parks pending on that
  principal (R10) and is re-checked when its head is installed (R11's
  shape). `(abandon)` and the link policy end it.
- `(yin.head/heads)` answers, per principal, what is installed, the
  candidate and its state, and the last refusal.

**Pinning.** `--dht-manifest <address>` is unchanged: it hydrates an
empty directory from that manifest, one-shot and never saved. A run
given it follows nothing: with a saved `--dht-follow`, the pin wins for
that run and the banner says following is suspended. A direct entry
(`--link-name my.lib=<address>`) still pins one name.

### 5.7 Persistence and relink (decision 7)

**Persistence.** The follower's durable state is the last installed
trace of each followed principal, verbatim, in `heads.edn` in the DHT
store directory (the `dht:<dir>` store; for the `dht` subcommands that
is the node directory):

```clojure
{:version 1
 :heads {"ed25519:<hex>" {:yin.head/envelope {...} :yin.head/proof {...}}}}
```

- The whole signed trace is kept, not a manifest and a number: the floor
  and the manifest are read from evidence that verifies again.
- **Durable before used.** A confirmed head is installed in this order:
  1. The follower emits `:confirmed` with the trace.
  2. The owner replaces `heads.edn` atomically with that trace in its
     principal's place.
  3. Only when the write has returned does the owner call
     `(head/install follower node principal trace)`: the head enters the
     snapshot set, the floor moves, `:installed` is emitted.
- Nothing reads a confirmed head before step 3.
- A crash before step 2 restarts at the previous head, which is the last
  one ever used. A crash after it restarts at the new head, whose blobs
  are local. No crash restarts below a head that was exposed.
- **A write that fails installs nothing.** The owner reports
  `:yin.head/unpersisted` with the host's reason, the previous head
  stays installed, and the write is tried again after the node's repair
  delay. A newer confirmed head replaces the waiting one.
- A composition with no durable directory declares `:heads :volatile`
  and calls `install` on `:confirmed`. Its floor does not survive a
  restart, by its own declaration.
- At startup each record is verified again, checked against the followed
  set, and its index walked in the local store. A record that fails
  refuses startup naming it; nothing falls back to no floor.
- An index loaded by hand stays process state, as now.

Rejected: recording observed traces as datoms in the reader's own index
(two nodes following each other would move each other's HEAD forever).

**Relink: a session keeps what it linked; `(reset)` leads to the new
module.** `yin.vm` learns nothing, and a repeat `(require 'alib)` of a
linked name is what it is today. When an installed head makes a linked
name resolve elsewhere, the shell prints one line: the name, both
addresses, the principal and sequence, and that `(reset)` then
`(require ...)` links it. The set is derived (the registry against the
fold) and stored nowhere; `(yin.head/moved)` answers it.

### 5.8 Command line and saved state (decision 8)

- **The token** is `yin:<host:port>/<principal>`, printed once when both
  the socket and the board endpoint are bound. A token with a third part
  is refused: a manifest is a pin and belongs to `--dht-manifest`.
- **`dht join <token>`** becomes `--index-store dht:<dir> --dht-peer
  <host:port> --dht-principal <hex> --dht-follow <hex>@<host:port>`. It
  passes no `--dht-manifest`: its first manifest is the first trace it
  installs. The shell admits evaluation at once and says, per followed
  principal, whether a head is installed.
- **`state.edn`** keeps flags, and gains `--dht-follow` (repeatable).
  It never holds a head. `--dht-manifest` stays one-shot.
- **Hydration is not removed and `--dht-recover` is not added.** The
  defect was joining by hydration (D1), and join no longer does. What
  `--dht-manifest` does is still right for its two remaining uses: a
  reproducible run in a fresh directory, and a publisher restoring a
  lost directory under a kept key, which is `yin.vm.linker.dht.md` 6.5's
  documented remedy. Leaving it alone keeps that remedy with no
  amendment and no new flag.
- A directory hydrated in an earlier run and following in a later one
  folds both snapshots. That is correct (the fold deduplicates
  envelopes and honours retractions) and is stated, not prevented.
- If the TCP port of the same number cannot be bound, the node serves
  no board, prints no token and names the port; the operator chooses
  another `--dht-port`.

### 5.9 Failure and security (decision 9)

- A trace is honoured only when it verifies under a declared key. Where
  it was read is never evidence.
- A head is installed only after its index is local, its sequence is
  confirmed by that index, and its trace is durable.
- The floor never moves down and moves only at installation.
- No failure retracts an installed head, and none waits on a clock.
- **These are safety rules. None promises that a newer head is ever
  installed.** A source can withhold, and a source or a path attacker
  can churn the candidate with eligible old traces (5.5).
- **Nothing above mentions loopback.** Loopback bounds who can connect.
  No rule trusts a source for being local (section 8.4).

Section 10 is the table.

### 5.10 Portability (decision 10)

Portable `.cljc`: the trace, `judge`, the follower, the board, the
rendering of `heads.edn`, and the ws composition (`dao.stream.ws`,
`dao.stream.ws-project`, `dao.stream.remote`). Host seams, all existing:
Ed25519 (`yin.vm.linker.sign`), the WebSocket listener and dialer
(`src/clj/dao/stream/ws/jvm.clj`, `src/cljs/dao/stream/ws/node.cljs`,
`src/cljd/dao/stream/ws/dart.cljd`), the atomic file replace
(`dao.space.store.fs`). A browser can dial but has no Ed25519 here: it
follows nothing yet.

ClojureDart traps: `heads.edn` is rendered with no whitespace before a
closing bracket; host branches put `:cljd` first; no protocol method
takes `[_ _]`; no test reaches a private var by `#'`; the sequence is at
most 2^53 - 1, the greatest integer exact on every host, and a greater
one is malformed (5.5), and the vectors include one above 2^32; a
manifest address from the wire is compared with `=`; a trace decoded
from the channel codec is verified over `dao.jing/canonical-bytes`,
asserted on all three hosts.

## 6. The plain Clojure API

```text
join -> publish! -> assert! -> announce! -> deposit!        (publisher)
join -> follow -> attach -> step ... -> names -> link       (reader)
```

<table>
<tr><th>Function</th><th>Meaning</th></tr>
<tr><td><code>(yin.vm.linker.head/verify trace)</code></td>
<td>True or false; never throws.</td></tr>
<tr><td><code>(head/seq-of datoms)</code></td>
<td>The greatest <code>t</code>; <code>nil</code> for no datoms.</td></tr>
<tr><td><code>(head/judge principal floor installed trace)</code></td>
<td>The rule of 5.5, as data.</td></tr>
<tr><td><code>(head/board)</code>,
<code>(head/deposit! board key manifest datoms)</code></td>
<td>The capacity-1 ring, and sign-and-append; refuses
<code>:yin.head/seq-collision</code>, <code>:seq-regression</code>,
<code>:empty</code>, <code>:no-key</code>.</td></tr>
<tr><td><code>(head/follow node opts)</code></td>
<td>A follower: <code>:follow</code> principals, <code>:heads</code> the
persisted records or <code>:volatile</code>.</td></tr>
<tr><td><code>(head/attach follower principal reader)</code></td>
<td>Give the follower a <code>dao.stream</code> reader handle for that
principal's board. Any handle.</td></tr>
<tr><td><code>(head/step follower node now)</code></td>
<td>One pass after <code>dao.space.dht/step</code>:
<code>[follower node events]</code>. It confirms; it never installs.
</td></tr>
<tr><td><code>(head/install follower node principal trace)</code></td>
<td>Step 3 of 5.7. Refused unless that trace is the confirmed one.</td>
</tr>
<tr><td><code>(head/heads follower)</code>,
<code>(head/records follower principal trace)</code></td>
<td>The status of 5.6, and the value <code>heads.edn</code> holds once
that trace is written.</td></tr>
<tr><td><code>(head/moved names registry)</code></td>
<td>The linked names whose resolved address differs.</td></tr>
<tr><td><code>yin.vm.linker.head.ws/serve</code>, <code>serve-step</code>,
<code>dial</code>, <code>dial-step</code></td>
<td>The board's acceptor (a one-entry table and a one-entry name map)
and the reader's dial (resolve the name, then attach), thin
compositions of <code>dao.stream.ws-project</code>; the listener and
the host's connect seam are arguments (a ws attacher is tied to its own
traffic medium, and every redial needs a fresh one). <code>serve</code>
requires a positive bind port and refuses otherwise as data: the board
binds at the node's UDP port number (5.1), so the number is always
known. The only namespace here that knows a transport.</td></tr>
</table>

`dao.space.dht` gains `abandon`, the kind-conflict refusal and the
wider client step of 5.5, and learns nothing about heads.
`dao.stream.remote` gains the named `descriptor` request and the link's
`resolve`, and `dao.stream.ws-project` passes a name map to the mirror
and exposes `resolve` on a dial; neither learns what a head is.

Events are maps under `:yin.head/event`:
`:confirmed`, `:installed`, `:refused` (a reason and the trace),
`:unloadable`, `:source-lost`. `yin.repl` adds a `yin.head` host module,
`heads` and `moved`, each one call into the functions above; the shell
owns the file, the lines, the flags, and the dial.

## 7. Limits and defaults

<table>
<tr><th>Datum</th><th>Default</th><th>Meaning</th></tr>
<tr><td>Board capacity</td><td>1</td><td>Fixed.</td></tr>
<tr><td>Candidates per principal</td><td>1</td><td>Fixed (5.5).</td></tr>
<tr><td><code>:head-poll-ticks</code></td><td>5000</td>
<td>Node ticks between two <code>next</code> calls on one source. It
bounds how often a reader asks, not how late it learns.</td></tr>
<tr><td>Retry delays (load, write, re-dial)</td>
<td><code>:repair-ticks</code> to <code>:repair-max-ticks</code></td>
<td>Doubling, the node's existing bounds.</td></tr>
<tr><td>Followed principals</td><td>at most 64</td>
<td>More refuses startup.</td></tr>
<tr><td>Board endpoint slots</td><td>8</td>
<td>The acceptor's handoff slots, as the REPL endpoint's.</td></tr>
</table>

Following alone never makes the node `busy?`: a poll, a judged trace, a
loaded candidate awaiting installation, a failed one waiting out its
delay and what an abandoned load leaves in the client are not work the
node reports. A candidate that is `:loading` is a load like any other
and makes the node `busy?` until it ends, so the tick owner holds its
base interval while a head is fetched and returns to its idle curve
afterwards. Under the alternating replay of 5.5 a load is always
active, and the node stays at the base interval for as long as the
alternation lasts.

## 8. Cross-machine: the owner's question

> if the abstraction is dao.stream, cross-machine would not require a
> complete redesign, right?

**Right.** The core is built so that it is true, and the one place it
would have been false is removed. Following across machines changes how
a reader handle is obtained and who may connect to the board. It changes
no trace, no rule, no file and no reader semantics.

### 8.1 What is transport-agnostic, and stays so

<table>
<tr><th>Part</th><th>Why it cannot depend on a transport</th></tr>
<tr><td>The trace and its signature (5.2)</td>
<td>No address, no time, no channel datum is signed.</td></tr>
<tr><td><code>judge</code> (5.5)</td>
<td>No source argument. One source is the degenerate case.</td></tr>
<tr><td>Confirmation (5.2, 5.5)</td>
<td>The index is content-addressed and fetched by the DHT, which
verifies every blob against its address.</td></tr>
<tr><td>Persistence (5.7)</td>
<td><code>heads.edn</code> holds signed traces and no source.</td></tr>
<tr><td>The follower (5.3, 5.5)</td>
<td>It holds a reader handle and calls <code>cursor</code> and
<code>next</code>. A lost source is one event, whatever lost it.</td>
</tr>
<tr><td>Reader semantics (5.6, 5.7)</td>
<td>They start at the snapshot set.</td></tr>
</table>

Only `yin.vm.linker.head.ws` and the shell's flags know a transport.
Slice H1 proves the claim by test: the follower is driven to completion
over a plain local ring, with no channel at all.

### 8.2 What each later step adds and amends

<table>
<tr><th>Step</th><th>Adds</th><th>Amends</th>
<th>Changes the core's trace, rule or persistence?</th></tr>
<tr><td><strong>Serving off loopback</strong> (8.3)</td>
<td>A bind that is not loopback; a bound on concurrent sessions; a
banner line.</td>
<td>This document 5.1 and 5.8; <code>yin.vm.linker.head.ws</code>;
<code>yin.repl.main</code>. Nothing in <code>dao.stream.*</code> if the
unverified items of 8.3 hold.</td>
<td><strong>No.</strong></td></tr>
<tr><td><strong>Relaying</strong></td>
<td>A second ring per followed principal holding the installed trace
verbatim, read from <code>heads.edn</code>; a flag.</td>
<td>Nothing in <code>dao.stream.*</code>: name resolution is in the
core (5.1), and a relay is one more peer mapping the same name to its
own ring. This document 5.1 and 5.5; the shell.</td>
<td><strong>No.</strong> It is why the trace carries no address and
why <code>heads.edn</code> keeps the whole signed trace.</td></tr>
<tr><td><strong>Many sources per principal</strong></td>
<td>A cursor per source; a scheduling policy for which candidate loads
when sources disagree.</td>
<td>This document 5.5 "The candidate" and section 7; the follower's
process state and the shape <code>heads</code> answers.</td>
<td><strong>No</strong> for the trace, <code>judge</code>, the floor
and <code>heads.edn</code>. <strong>Yes for one plumbing rule:</strong>
"latest observed wins" is sound only for one writer's ring. Many
sources need "greatest verified sequence, with a fair turn for an
unloadable one", which is where the two scheduling defects of the
second review live. That design starts from those findings.</td></tr>
<tr><td><strong>UDP on the DHT socket</strong></td>
<td>A mirror on the node's datagram socket; a return-path proof.</td>
<td><code>dao.jing.dht.md</code> 3 and 8 (frozen);
<code>dao.stream.remote.md</code> 3.2; <code>dao.stream.udp</code>:
the proof must ride the outer fragment envelope and be checked before
reassembly, because a fragment carries no request key
(<code>src/cljc/dao/stream/udp.cljc:135-149, 312-325</code>);
<code>dao.space.dht</code>.</td>
<td><strong>No.</strong> It is the costliest step and touches the core
nowhere.</td></tr>
</table>

**The redesign risks, found now.** Three:

1. **Identity.** Revision 2 said the name-against-identity question
   blocked relaying only. That was wrong: a fixed name as an identity is
   already false across one publisher's restart. It is settled in the
   core (C3, 5.1) and no later step reopens it.
2. The single-candidate rule does not generalise to many sources, and
   it gives no progress guarantee under replay even with one (5.5). It
   is plumbing, labelled as such; a scheduling policy replaces it
   without touching the trace, `judge`, the floor or `heads.edn`.
3. **The token assumes one host and one port number for both sockets.**
   That needs two independent binds to succeed, and off loopback two
   forwarded ports. A third token part of the form `ws:<port>` is
   reserved for a differing port number; it is additive. It does
   **not** cover a board advertised at another host, a reverse proxy
   that changes the path, or two peers that are both unreachable. Each
   of those needs the token to carry a full ws address, or a relay
   pair; the step off loopback decides the grammar, and the trace does
   not depend on it.

### 8.3 The first cross-machine transport: WebSocket

<table>
<tr><th></th><th><code>dao.stream.ws</code></th>
<th>UDP on the DHT socket, plus a return-path proof</th></tr>
<tr><td>Reflection of traffic at a spoofed source</td>
<td>None: a WebSocket rides TCP, whose handshake is the return-path
proof.</td>
<td>The problem itself. Needs a new proof, on fragments too.</td></tr>
<tr><td>Serving an arbitrary ring with existing code</td>
<td><strong>Yes.</strong> <code>ws-project/make-acceptor</code> takes
any mirror table and <code>accept-step!</code> runs
<code>remote/mirror-step</code> per accepted connection
(<code>ws_project.cljc:161-190, 248-276</code>);
<code>dial</code>, <code>dial-attach!</code> and
<code>dial-step!</code> give the reader a reflection
(<code>305-420</code>). <code>yin.repl.serve</code> does exactly this
today with a two-entry table (<code>serve.cljc:224-225, 291</code>).
</td>
<td>No. The node has no mirror on its socket; that is new code in
<code>dao.space.dht</code>.</td></tr>
<tr><td>Contracts amended</td>
<td>None beyond the core's own name resolution (5.1), which either
transport needs.</td>
<td>Two, one of them frozen.</td></tr>
<tr><td>NAT</td>
<td>One side must accept a TCP connection: a public address or a
forwarded port. No hole punching. Either side may be the one that
dials, so no node is privileged, but two peers that are both behind
NATs need a relay pair
(<code>dao.stream.remote.md</code> 3.3, 4).</td>
<td>Hole punching is possible in principle, through a meeting peer.
The DHT has none today (<code>dao.jing.dht.md</code> section 6: "NAT
meeting is not in this epic").</td></tr>
<tr><td>What the reader must already reach</td>
<td colspan="2">The publisher's UDP socket, to fetch blobs. With no NAT
meeting in the DHT, cross-machine use today already needs the publisher
directly reachable. WebSocket asks for the same host on TCP as
well.</td></tr>
<tr><td>Token</td>
<td><code>yin:&lt;host:port&gt;/&lt;principal&gt;</code>, the port
number shared (5.1); <code>ws:&lt;port&gt;</code> reserved.</td>
<td>The same two parts, one socket.</td></tr>
<tr><td>Confidentiality</td>
<td>None: <code>wss://</code> "has no settled descriptor form"
(<code>src/cljc/yin/repl/connect.cljc:259</code>). The head is public
and its integrity is the signature's.</td>
<td>None either.</td></tr>
</table>

**Recommendation: WebSocket, for the core and for the first step off
loopback.** It is the transport with a return path, it serves the ring
with code that exists, and it amends no contract. UDP on the DHT socket
stays the deferred alternative, wanted when hole-punched peers must
follow each other without a relay.

**What that step is.** Remove the loopback condition of 5.1, and land
the checks below. No design beyond this section is expected.

**Unverified, and to be verified by that step before it lands:**

- That the acceptor bounds concurrent sessions. The handoff has 8
  slots; whether `:sessions` is bounded under many held connections was
  not checked.
- That one connection cannot make a step long: `mirror-step` loops to
  `blocked`, and the spec's "composition budget" was not found in it.
- That the listener seams, written for `yin.repl.host`, compose outside
  `yin.repl.serve` on all three hosts without moving code.
- That a TCP listener binds at the UDP socket's number on each host,
  including after an ephemeral UDP bind.
- That a lost request on an attached reflection is detected. On
  loopback a dead peer closes its connection and the follower reports
  `:source-lost` on its next poll (an attached dial's own status does
  not change; only a dial still resolving becomes `:lost`); a live but
  silent source is "withholding" (section 10) and its liveness is
  deferred to `dao.lease`. Off loopback a half-open connection is real
  and the follower would see retryable errors until TCP gives up.

### 8.4 The assumption cross-machine adds: locality is not trust

On loopback, whoever connects is on the machine. Off it, anyone is.
Nothing in the core relied on the first:

- **Reading the board** was never restricted. A head is public wherever
  its node is reachable, as its index is under `--dht-publish`.
- **A hostile or intercepted source** can do four things: show a
  forged trace (`:bad-proof`); show one below the floor (`:stale`);
  show nothing; or alternate signed traces above the floor so that no
  load completes (5.5). The first two are refused. The last two deny
  progress and nothing else: no wrong head is installed and the floor
  never moves down. `judge` treats every source that way; it has no
  notion of a local one.
- **First contact has no floor**, on loopback or off. Off loopback an
  interceptor on a cleartext channel can show a first-time reader an
  old valid head, or withhold. That limit was always in section 10; the
  network makes it reachable. `wss` or a second source narrows it;
  neither is in the core.
- **The index** is fetched by content address and confirmed against the
  trace.
- **What does change** is resource exposure: connections, sessions,
  step time. Those are the unverified items of 8.3, and they are the
  whole of the work.

## 9. What is rejected

- **A head under a DHT key.** C2; it is the mutable-pointer service.
- **The signature in `m`.** C1.
- **UDP on the DHT socket first.** 8.3.
- **A candidate set.** With one source it buys nothing and its capacity
  is a way to starve a true head.
- **A floor that includes unconfirmed claims.** A replayed, validly
  signed, wrong trace would shut out every true head below it.
- **Install, then persist.** A reader that used head 20 could restart
  at 19.
- **Load holders in `dao.space.dht`** (revision 1). A distinct load
  kind keeps candidates out of the snapshot set with no new bookkeeping,
  and `forget` keeps its contract.
- **Removing hydration, and `--dht-recover`** (revision 1). 5.8.
- **The board on the REPL endpoint's table.** It would publish the
  evaluation service with it.
- **The board name as a mirror identity** (revisions 0 and 2), and
  **amending `dao.stream.md` to make a service name a legal identity.**
  The second would need cursor semantics for a name whose stream is
  replaced under it, in the governing contract, to save one request.
  Resolution keeps that contract as it is.
- **A head operation on `dao.stream.apply`, a DHT wire op, a hash
  chain, push.** As before: each adds a mechanism where a ring served as
  itself needs none.

## 10. Failure modes

<table>
<tr><th>Event</th><th>What the reader does</th><th>Limit</th></tr>
<tr><td>A forged trace</td>
<td><code>:yin.head/bad-proof</code>.</td><td>-</td></tr>
<tr><td>An old trace shown again</td>
<td><code>:yin.head/stale</code> against the durable floor.</td>
<td><strong>First contact has no floor</strong>: an old valid head is
installed. No trace says how new it is.</td></tr>
<tr><td>A signed trace whose sequence is wrong</td>
<td>Loaded, <code>:yin.head/seq-mismatch</code>, dropped, remembered.
The floor does not move; its index is never resolvable.</td>
<td>Only the last rejected trace is remembered. Shown again after
another trace, it is walked again, locally.</td></tr>
<tr><td>Signed traces above the floor shown in alternation</td>
<td>Each turn releases one load and starts the other. A load that
completes installs and raises the floor.</td>
<td><strong>No progress guarantee</strong>: if no load completes
within a turn, no head is installed while it lasts (5.5). Bounded
work; no wrong head.</td></tr>
<tr><td>A publisher that rewinds</td>
<td>A lower <code>t</code> is <code>:stale</code>.</td>
<td>A key holder can sign a new index that omits anything. Declaring a
principal is trusting it for its own head.</td></tr>
<tr><td>Two manifests at one sequence</td>
<td><code>:yin.head/equivocation</code>; the installed head stays.</td>
<td>A greater sequence ends it.</td></tr>
<tr><td>The source shows nothing</td>
<td>Nothing. The installed head stays.</td>
<td><strong>Withholding is undetectable</strong>: silence and "no new
head" look the same, and no timeout may tell them apart.</td></tr>
<tr><td>A manifest nobody holds</td>
<td><code>:yin.head/unloadable</code>; retried at the bounded delay;
the next observed trace replaces it at once.</td><td>-</td></tr>
<tr><td>An older load never finishes</td>
<td>Abandoned the moment a newer trace is observed; the newer one
loads at once (5.5).</td><td>-</td></tr>
<tr><td>A crash while installing</td>
<td>Before the write: the previous head. After: the new one.</td>
<td>-</td></tr>
<tr><td><code>heads.edn</code> cannot be written</td>
<td><code>:yin.head/unpersisted</code>; nothing installed; retried.</td>
<td>No progress while the directory refuses writes.</td></tr>
<tr><td>The publisher restarts, or the connection drops</td>
<td><code>:source-lost</code>; a fresh handle; <code>:oldest</code>
again.</td><td>-</td></tr>
<tr><td>The publisher's directory is lost, the key kept</td>
<td>Traces from an empty directory are <code>:stale</code>.</td>
<td>The publisher's remedy is 6.5's (5.2).</td></tr>
<tr><td>The key is disclosed</td>
<td>Whoever holds it moves the head. Remove the declaration.</td>
<td>-</td></tr>
</table>

## 11. Implementation slices

Order: **H0 -> H1 -> H2 -> H3.** Each lands alone, green on the JVM,
Node and Dart. H0 and H1 own disjoint source files.

**H0 -- the trace and its rule.** Pure. Files: new
`src/cljc/yin/vm/linker/head.cljc` (`trace`, `verify`, `seq-of`,
`judge`), `src/cljc/yin/vm/linker/sign.cljc` (the head prefix),
`test/yin/vm/linker/sign_vectors.edn`, new
`test/yin/vm/linker/head_test.cljc`, and `test/yin/repl/dht_test.cljc`
(the hydration cases; H1 owns that file later and H0 lands first).
Complete when:
- One trace under RFC 8032 TEST 1's seed is in the vectors with its
  canonical bytes, signed message and signature, byte for byte on all
  three hosts; one has a sequence above 2^32.
- Tampering with the principal, manifest or sequence, a flipped
  signature bit, another key, an extra key in either map, and a
  name-envelope signature offered as a head proof each fail.
- A trace through `dao.stream.cbor` verifies on each host.
- **Sequence zero.** With a `nil` floor a trace of sequence 0 is a
  candidate. With floor 0: sequence 1 a candidate, sequence 0 with the
  installed manifest a duplicate, with another `:equivocation`. A
  negative or non-integer sequence, or one above 2^53 - 1, is
  `:malformed`. `seq-of` of one transaction is 0, asserted against
  `dao.space.transactor`.
- `judge` produces every row of 5.5, and its arity admits no source and
  no candidate.
- **Neither `judge` nor `verify` ever throws.** A trace comes off a
  channel, so any shape is possible: an envelope or a proof that is a
  list of any length, a vector, a number, a string or `nil`, and a
  non-map trace, each answer `:yin.head/malformed` (`judge`) and `false`
  (`verify`) on every host. The cases are in the tests, odd-length lists
  included.
- `seq-of` over a rehydrated index equals `seq-of` before the restart;
  a further round raises it; the HEAD writes of one directory carry
  strictly increasing sequences across rounds, name transactions,
  `(reset)` and the unwritten-row recovery of `yin.vm.linker.dht.md`
  5.1.
- **Hydration** (5.2), in `test/yin/repl/dht_test.cljc`: into a
  directory with no HEAD it writes HEAD once, at the hydrated index's own
  sequence, and the reader's next round is one above it; given the
  manifest HEAD already names it writes nothing; given another it is
  refused and HEAD is unchanged.
- **The stop rule.** If any path is found that writes a directory's HEAD
  to a manifest whose sequence is not above that directory's previous
  HEAD write, the slice stops and 5.2 is reopened. A HEAD write that
  commits no transaction but is a directory's first is not that case.

**H1 -- the follower, over any reader.** No transport. Files:
`src/cljc/yin/vm/linker/head.cljc` (`board`, `deposit!`, `follow`,
`attach`, `step`, `install`, `heads`, `records`, `moved`),
`src/cljc/dao/space/dht.cljc` (`abandon`; the kind-conflict refusal;
the wider client step), `src/cljc/yin/vm/linker/dht.cljc` (`snapshots`
adds installed heads), `src/cljc/yin/repl/query.cljc` (**refusal
translation for the two host load operations**),
`test/dao/space/dht_test.cljc`, `test/yin/repl/dht_test.cljc`, new
`test/yin/vm/linker/head_follow_test.cljc`. Amendments carried:
`yin.vm.linker.dht.md` 1, 4.3, 7.2, 9, 10 and 13 (D2; `abandon`; the
refusal and its failure-vocabulary row); `dao.jing.dht.md` section 1 (a
pointer here). In every case the reader is handed the publisher's board
ring directly as its reader handle, and blobs travel over the mesh
seam. Complete when:
- **Existing tests.** `forget` is untouched, and
  `forget-clears-a-terminal-record-and-is-refused-while-loading` passes
  as written. Any existing test that loads one address under two kinds
  and relies on the silent no-op is migrated, and the slice names it.
- **The host module translates the refusal.** `dht-answer`
  (`yin/repl/query.cljc:665-674`) calls `load-index` and `load-module`
  bare today; only the publication operations catch a
  `:dao.space.dht/refused` (`625-633`). Both load operations now answer
  a refusal as the call's error value, under its own code, and the
  interpreter never throws. Tested through the host module: a
  `(dao.space.dht/load-index m)` while `m` loads as a candidate, and a
  `load-module` of an address recorded under another kind, each answer
  `:dao.space.dht/kind-conflict` as data, the serve round continues,
  and the next request is answered.
- **`abandon` and the fetch client.** `abandon` removes a `:loading`
  record, emits no event, and is refused for a record that is not
  loading. Four cases, each on the last and only load, each asserting
  afterwards that the client holds no unsent request, no outstanding id
  and no completion, **with no load active**:
  - abandoned before submission, the request ring full, so the request
    was retained unsent;
  - abandoned after submission, the answer not yet arrived;
  - the same, then the late answer arrives: it is dropped, no blob is
    stored through it, and no event is produced;
  - the same address loaded again afterwards: it gets a new request id
    and completes on its own answer.
  A fifth: two loads fetching one address, one abandoned; the other
  completes.
- **Shared candidates.** Principals P and Q whose current traces name
  one manifest: one record, both owners. P's candidate is replaced
  while it loads: the record is still loading and Q's head is then
  installed from it. P's trace is rejected `:seq-mismatch` while Q's is
  right: Q installs; the record is removed only at the second release.
  After both are installed the record is gone, and a `load-index` of
  that manifest by hand is `:loaded` with zero `:jing/get`.
- A publishes `alib` and deposits; B, given the board and A's principal
  and no manifest, installs A's head and resolves `alib`. A republishes;
  B resolves the new address.
- **First transaction.** A first index deposits sequence 0; a
  first-contact reader installs it; sequence 1 replaces it.
- Ten deposits between two polls install one head, the last.
- **A candidate is not resolvable.** While the candidate is `:loaded`
  and not installed, and between `:confirmed` and `install`,
  `snapshots` does not list it and a name asserted only in it is
  `:absent`.
- **A wrong-sequence trace X** (sequence 1000000 over an index at 10,
  holding a newer assertion of `alib`), from floor 10 and from a `nil`
  floor, with true heads 11 and 12, in each order: X, 11, 12; 11, X, 12;
  11, 12, X; X while 11 is loading. Each run ends with the last true
  head shown installed, the floor equal to its sequence, `alib` never
  resolved through X, and X not loaded a second time when shown again.
- **An unfinished load does not block.** The mesh never answers for a
  blob of head 11, so its load stays `:loading` for the whole test.
  Head 12, whose blobs are all local to the mesh, is deposited: 12 is
  installed, 11's record is gone in the step 12 was observed, and no
  tick count is asserted to have ended 11.
- **Alternating replay, the stated limit.** From floor 10 the source
  alternates signed 11 and 13 at every poll, and the mesh answers no
  fetch within a turn: nothing is installed while it lasts, the floor
  stays 10, loading records never exceed one, and the client holds
  nothing between turns. The same alternation with fetches answered:
  11 installs, then 13, and 11 is `:stale` from then on. Two
  wrong-sequence traces alternating are each walked each turn with zero
  `:jing/get` after the first, and neither is ever resolvable.
- **An unloadable candidate** keeps the previous head, is started again
  only after the delay, and is replaced at once by the next trace.
- **A failed load made by hand.** The test calls `load-index` on a
  manifest no peer holds; it fails. The publisher's head names that
  manifest and the blobs then appear. The follower restarts the load as
  an index load after the delay; it loads; the head is installed; the
  record is still of the index kind and still listed by
  `loaded-indexes`. A `load-index` by hand while a candidate of that
  address is loading is `:dao.space.dht/kind-conflict`.
- **Durable before used.** After `:confirmed` and before `install`: the
  floor, the snapshot set and `heads` under `:installed` are unchanged.
  A follower composed again from the old records is at the old head. One
  composed again from the new records, `install` never called, is at
  the new head with zero `:jing/get` on the request ring, and refuses
  the older trace as `:stale`. `install` of any other trace is refused.
- **A malformed index.** A signed, verified trace naming a hash-valid
  index whose rows hold a `t` that is not a nonnegative integer (for
  example `[101 :x/y 1 "bad-t" 0]`) is refused `:yin.head/index-invalid`
  as a `:refused` event, from the step and from a restore through
  `follow` (a startup refusal naming the principal): no throw, the
  floor unchanged, nothing installed, and not loaded again when shown
  again.
- **A foreign record.** A `:failed` record of another kind at the
  candidate's address (a module-kind load) is left in place after the
  retry delay, the candidate is `:yin.head/unloadable` once, and after
  its owner forgets it the candidate loads and installs.
- **A lost source.** A reader handle answering `cursor-mismatch`, and
  one answering `end`, each produce `:source-lost` once; after `attach`
  of a fresh handle the current head is read again and judged a
  duplicate.
- `deposit!` refuses `:seq-collision`, `:seq-regression`, `:empty` and
  `:no-key`, each appending nothing.
- Two nodes following each other reach quiescence; neither HEAD moves.
- `moved` names exactly the linked names whose address changed.

**H2 -- name resolution, and the board over WebSocket on loopback.**
Files: `src/cljc/dao/stream/remote.cljc` (the named `descriptor`
request in the mirror; the link's `resolve`),
`src/cljc/dao/stream/ws_project.cljc` (a name map on the acceptor and
the dial; `resolve` on a dial before any reflection),
`test/dao/stream/remote_test.cljc`, new
`src/cljc/yin/vm/linker/head/ws.cljc`, new
`test/yin/vm/linker/head_ws_test.cljc`. Amendments carried, all in
`dao.stream.remote.md`: section 2, the name map as composition data
beside the table; 2.1, the named request shape, and `not-found`
carrying the name; 2.3, a step 0 that resolves a name or answers
`not-found`, and a named request with another op as malformed; 2.4, the
link's `resolve`; section 5, the head board as a convention and the
sentence that a name is lookup data and never a `:dao.stream/identity`;
and its status line, which says "design target" of code that exists
(so does `dao.stream.ws.md`'s). **`dao.stream.md` is not amended.** If
this slice's review finds it must be, the slice stops and that becomes
an owner question; no fallback is designed, because none is expected.
Complete when:
- **Resolution.** A mirror with a name map answers a named `descriptor`
  with the entry's own identity and surface; an unmapped name, and a
  name mapped to an identity the table lacks, are `not-found` with the
  name; a named `next` is dropped. `resolve` answers retry, then `ok`
  with a remote descriptor that `attach!` accepts. Every existing
  `dao.stream.remote` test passes unchanged: an identity request is
  answered as before.
- **Identity.** The reader's reflection reports the ring's own
  identity, never the name. No value on the wire carries the name under
  `:dao.stream/identity`.
- A serving node and a dialing node on loopback: the reader's handle is
  a reflection; H1's first case passes over it unchanged.
- **Restart.** The publisher's endpoint is stopped and started: the
  reader reports `:source-lost`, dials and resolves again, receives a
  different identity, and reads the head, judged a duplicate. A cursor
  kept from the old ring is `cursor-mismatch` on the new one.
- **A shared key.** Two serving nodes with one key answer two
  identities for the one name; a reader of each reads its own ring.
- The table holds the board and nothing else; a writer op on it is
  `no-surface`; another identity is `not-found`.
- A bind host that is not a loopback literal composes no endpoint, for
  IPv4 and IPv6 literals.
- A TCP port already in use is a refusal as data, and the node keeps
  running without a board.
- The same cases pass on Node and on Dart.

**H3 -- the REPL.** Files: `src/cljc/yin/repl/dht.cljc`,
`src/cljc/yin/repl/main.cljc`, `src/cljc/yin/repl/state.cljc`,
`src/cljc/yin/repl/query.cljc` (the `yin.head` host module),
`src/cljc/yin/repl/link.cljc`, `src/cljc/yin/repl.cljc` (the
first-contact pending), `test/yin/repl/dht_process_test.clj`, the tests
beside each, and the `yin.repl` documents. Complete when:
- The token prints once, as `yin:<host:port>/<principal>`, only when
  the board endpoint is bound; `parse-token` refuses a third part with
  the pin message.
- `dht join <token>` passes no `--dht-manifest` and saves
  `--dht-follow`; a bare restart resolves from `heads.edn` before any
  connection is made.
- `--dht-follow` of an undeclared principal refuses startup.
  `--dht-manifest` with a saved follow suspends following for that run.
- A `(require 'alib)` typed before the first head parks, and completes
  on the install with no typed line; `(abandon)` ends it.
- **The write precedes the install.** A seam that fails the write
  prints `:yin.head/unpersisted`, installs nothing, and installs after
  the retry. A process killed between write and install restarts at the
  new head; one killed before the write restarts at the old.
- An installed head that moves a linked name prints the line of 5.7;
  the repeat require is unchanged; `(reset)` then `require` links the
  new address.
- `heads.edn` cases: absent, malformed, a record that fails
  verification, a record for an unfollowed principal; each read on Dart.
- **End to end, both processes on loopback.** A publishes `alib`; B
  joins with the token and requires it on each of the four VMs, on the
  JVM and on Node. A republishes; B, after `(reset)`, links the new
  module. B restarted with A stopped still requires it.
- The host module holds no rule: no fold, verification or file write in
  `yin.repl.query`.

## 12. Deferred

Each of the first four is its own later design; section 8.2 says what
it adds and amends.

- **Serving off loopback**, over WebSocket (8.3). First after the core.
- **Relaying.** Decided as a deferral: no node re-serves a head in the
  core.
- **Many sources per principal.**
- **UDP on the DHT socket, with a return-path proof**, fragments
  included.
- **`wss`**, and with it a narrower first-contact limit and no path
  attacker to churn the candidate.
- **A progress guarantee under replay**: a reviewed scheduling policy
  for the candidate. Not promised by the core (5.5).
- **The `yin.repl` service's fixed identities** (C4). A landed defect
  against `dao.stream.md`, outside this design. Its fix is to resolve
  `yin.repl/requests` and `yin.repl/answers` as names, with H2's
  mechanism. Named here so it is not lost; it needs its own task.
- **Finding sources** beyond the address given (rendezvous).
- **Liveness** by a `dao.lease` composition over the last-answer reading.
- **Relink in place.**
- **Resolving a transitive require by its pinned address**
  (`yin.vm.linker.dht.md` section 13); following makes its absence felt.
- **Collecting superseded indexes; key rotation; a head history; a
  durable last-rejected; a browser follower; push.**

## 13. Questions only the owner can answer

1. **Does "always" reach into a live session?** The design keeps a
   session on the module it linked, reports the move, and links the new
   one after `(reset)`. If a repeat `(require 'alib)` must switch a
   running session, relink in place needs its own design first. No
   ruling settles this.
2. **What should a bare `q` see after `dht join`?** Already ruled: the
   publisher's facts are a separate db-value and union is explicit.
   Open: the default. Today join hydrates, so a bare `q` answers the
   publisher's facts. Here join does not, so it answers the reader's own
   index, and the publisher's is `(dao.space.dht/q <manifest> ...)` with
   the manifest from `(yin.head/heads)`, after an explicit
   `(dao.space.dht/load-index <manifest>)`: installation releases the
   candidate record, and `q` answers only for an index-kind load (a
   snapshot kept until forgotten). Choose: (a) that; (b) a bare
   `q` unions the installed heads by default; (c) a named view.
   Recommendation: (a) now, (c) on request.
3. **Is every HEAD move the head you mean?** A follower loads a new
   index for each round the publisher evaluates, not only for each
   `yin.link/publish`. "Whatever the HEAD is" reads that way and the
   design follows it; it was said before this cost was stated. The
   alternative, a head only at each publish, is quieter and leaves a
   follower's view of the publisher's facts behind its names.
   Recommendation: every HEAD move.
4. **Is WebSocket acceptable as the first cross-machine transport?** It
   needs one side to accept a TCP connection, at the same port number
   as its UDP socket, and it does not hole-punch: two peers both behind
   NATs would need a relay pair, or the deferred UDP design. It is
   cleartext until `wss`. It is also the step that needs no new proof
   (8.3). Recommendation: yes.
5. **Is safety without a progress guarantee acceptable for the core?**
   The core never installs a wrong or older head. It does not promise a
   newer one is ever installed: a source can withhold, and on a
   cleartext channel a path attacker can alternate old signed heads so
   that no load completes (5.5). On loopback the attacker is someone
   already on the machine. Off loopback it is anyone on the path, so
   this question is really about question 4's step: either accept that
   limit there, or require `wss` or a scheduling policy first.
   Recommendation: accept it for the core; decide again at the step
   off loopback.

**None of the five blocks adoption of the core.** Questions 1 to 3
choose behaviour the slices can change late (a notice, a default view,
when `deposit!` is called). Questions 4 and 5 gate the step after the
core, not the core.

**One named blocker that is not the owner's.** Slice H2 amends
`dao.stream.remote.md`, a contract whose every sentence is a rule, with
the name map and the named `descriptor` request. That amendment needs
the Architect's and the independent reviewer's sign-off on the contract
text itself before H2 lands. H0 and H1 do not depend on it.

No longer questions: whether a node relays by default, and the order of
the return-path proof, are deferred with their designs; publisher
recovery needs no decision, since its remedy is untouched; the board's
identity is decided (C3) without amending the identity contract.

## Revision 1 (2026-10-05), in brief

The first review's eight findings were all accepted. What survives of
that revision here: the `nil` floor and sequence 0; a snapshot set that
never holds a candidate; a floor of confirmed evidence only; confirm,
persist, install; loopback-only serving; `deposit!` taking datoms; the
publisher's recovery remedy kept. What it added and this revision
removes: load holders, a candidate set, the named `descriptor` request,
relay rings, `--dht-recover`, the removal of hydration, and slice H5.

## Revision 2 (2026-10-05)

The owner chose the simple core and asked whether cross-machine use
would then need a redesign. The document was rewritten, not patched,
and section 8 is the answer. The second review's five findings
(<code>collab/1791205158504-reviewer-architect-head-trace-r2.gpt-6.1-sol<!--
-->.findings.md</code>):

1. **Candidate eviction starvation (P1): eliminated.** There is one
   candidate slot and no eviction. The latest observed trace takes it,
   even over a higher claimed sequence, so four unloadable high traces
   cannot shut out a true head (5.5).
2. **An unfinished load blocking newer heads (P1): still applicable,
   fixed.** A replaced candidate's load is abandoned in the step the
   newer trace is observed and the newer load starts at once;
   `abandon` is a new function, no deadline is added, and the one
   outstanding fetch ends by the DHT's own bound (5.5; H1's
   "unfinished load" case).
3. **Retry of a failed shared load (P2): still applicable in a smaller
   form, fixed.** Kinds no longer mix, and a failed record at the
   candidate's address is forgotten and started again under the kind it
   had (5.5; H1's "failed load made by hand" case).
4. **H1's "existing tests pass unchanged" (P2): eliminated.** `forget`
   keeps its refusal while loading; the cited test passes as written;
   H1 names any test the kind-conflict refusal migrates.
5. **The cookie cannot gate fragments (P2): eliminated from the core,
   recorded for the deferred design.** No UDP mirror is built. Section
   8.2 makes pre-reassembly proof on the outer fragment envelope a
   stated requirement of that later design, with `dao.stream.udp` and
   `dao.stream.remote.md` in its scope.

The first review's finding 4, left "partly" resolved: re-checked against
the one-slot rule and resolved. A wrong or unloadable signed trace can
occupy the slot only while it is the newest thing the source shows; the
next trace replaces it, and the floor never moved. A source that keeps
showing it is withholding, which no rule can beat (section 10).

Found while rewriting:

- **N5. The tree already uses well-known names as mirror-table
  identities** (`yin.repl/requests`, `yin.repl/answers`). The first
  review's finding 3 was argued from the contract alone; the landed
  REPL service has the same shape. Revision 2 followed that shape for
  the board; revision 3 withdraws that (C3, C4).
- **N6. The REPL's `--port` endpoint cannot carry the board.** Its table
  holds the evaluation service; exposing the head there would expose
  evaluation with it. The board has its own endpoint (5.1).
- **N7. `dao.space.dht/load` is silent across kinds** (D3).
- **N8. Revision 1 removed hydration to fix D1 and then had to add
  `--dht-recover` to restore what that broke.** The defect was only
  that join used it. Revision 2 stops join using it and touches nothing
  else (5.8).

## Revision 3 (2026-10-05)

The third review
(<code>collab/1791207836775-reviewer-architect-head-trace-r3.gpt-6.1-sol<!--
-->.findings.md</code>)
found five new defects. Each citation was checked; all five are
accepted.

1. **The board name as an identity (P1, accepted).** Choice (a): a name
   is lookup data, resolved to the ring's real descriptor by a named
   `descriptor` request, and the reader then attaches normally.
   `dao.stream.md` is untouched, so no owner decision is needed. Choice
   (b), a name as a legal identity, would amend the governing contract
   and need cursor semantics for a stream replaced under its name.
   Choice (c), an endpoint whose address is the identity, does not
   exist: the address is not the ring's identity, and any fixed entry
   key is the alias again. Revision 2's note N5 read the landed
   `yin.repl` aliases as a convention to follow; they are a defect
   (C4). Its claim that the question blocked only relaying is withdrawn
   (8.2). Changed: C3, C4, 5.1, section 6, 8.2, 8.3, section 9, H2,
   section 12.
2. **Shared candidate ownership (P1, accepted).** The follower keeps an
   owner set per candidate record and removes the record only at the
   last release; release happens at replacement, rejection and
   installation. Changed: 5.5, H1.
3. **Host refusal translation (P1, accepted).** Both host load
   operations answer a DHT refusal as data; in H1 with a test through
   the host module. Changed: 5.5, H1.
4. **Abandon and the fetch client (P2, accepted).** `abandon` retires
   the record's client interest with two functions that exist
   (`content.step/abandon`, `retire`), and the client is stepped
   whenever it holds anything, not only while a load is active.
   Changed: 5.5, section 6, H1. This also finishes the second review's
   partly resolved items: its #2 by this and by item 2, its #3 by
   item 2.
5. **Replay and load frequency overstated (P2, accepted).** The core
   states that it gives safety and no progress guarantee, bounds the
   work an alternation can cause, and withdraws "loaded once per
   process". Changed: 5.5, 5.9, 8.4, section 10, H1, section 12,
   question 5.

Small corrections applied: the mirror-call citation now runs to line
276, and 8.2 says what the reserved `ws:<port>` does not cover.

Found while revising: **N9.** `dht-answer` lets any refusal thrown by
`load-index` or `load-module` through the host interpreter today, not
only the new one; H1's translation covers every
`:dao.space.dht/refused` code on both operations.

## Lineage

The tuple space made stigmergy precise; this makes one trace of it
durable enough to follow. `announce!` was already the deposit, kept
private. A head board is the same act with an author and an audience: a
signed value on a ring one agent writes and any agent may read, carried
by whatever carries a stream. Datomic's connection hands a peer the
newest database value; here no connection is the abstraction, only the
trace, and each reader decides whose traces are worth following.
