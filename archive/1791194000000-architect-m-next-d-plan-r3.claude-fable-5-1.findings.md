Completed-GMT: 2026-10-05 09:30:57 GMT
Completed-Local: 2026-10-05 16:30:57 +07

**r3 differs from r2 only by residuals 1 to 4 of the second architect's confirmation; each change is marked "(residual n)".**

Read-only; nothing in the repo edited, no suites run. For this round I read the confirmation and the REPL shutdown code it cites (`main.cljc` `moved?`, `stop-tick`, the three host loops). The session plan file carries a four-line note of the same residuals.

## 0. The 12 findings of the r2 review (unchanged)

All 12 were accepted in r2; the second architect confirmed them, four with residuals.

| # | Finding | Where answered |
|---|---|---|
| 1 | Gate coverage and child policy | 1.2, D4 to D6 |
| 2 | Held immediates | 1.4 (residual 1) |
| 3 | Portable replay identity | 1.4 (residual 1) |
| 4 | Correlation is not dedup | 1.3 |
| 5 | Remote renewal and replay-prefix paths | 1.5, D2, D3 |
| 6 | Run end and release | 1.6 (residual 2) |
| 7 | Version-aware reader | 1.7 (residual 3) |
| 8 | Pure lift | 1.8, D8, D9 |
| 9 | Abort rule | 1.9 |
| 10 | Journal recovery contract | 1.10, D14 |
| 11 | Exclusivity gate, exclusions, slicing | 1.11, section 2 |
| 12 | REPL insertion point | 1.12 (residual 4) |

## 1. The contract

### 1.1 A task under custody

A version-1 task's root machine value carries `:yin.k/custody`: occurrence, lease, epoch, arbitration identity, `:yin.k/next-op-seq`, the input state (next `k`, frontier, whether the prefix has ended), the protection class per stream identity, and a mode. A version-0 task has none and behaves as today.

### 1.2 The engine seam and the gate

**Split.** Every place the engine touches a stream becomes observe (handle IO to an outcome) and apply (outcome to machine value). Ungated behavior is `apply ∘ observe`, unchanged.

**Modes.** The gate reads one key, `:yin.k/gate`, on any machine value:

- absent: ungated.
- `:running`: internal computation proceeds; every observation parks; the driver observes and applies.
- `:exporting` and `:ended`: nothing is observed, no child is advanced, a direct resume is refused, and every public apply refuses a late result.

**Children.** The root owns the mode. The engine stamps `:yin.k/gate` on a child's machine value when it creates the child and again before each `vm/run` of it (`engine.cljc` 1232). A child carries the mode only: no counters, no lease, no input state.

**Every path gated before it observes:**

- immediate effects: `:stream/put`, `:stream/next`, `:stream/poll`, `:stream/cursor`, `:stream/close`, the FFI request append, the link request append;
- `check-wait-set`: link request retries and link-response scans, install-child advance, FFI response routing (shared response readers included), and the ordinary sweep;
- direct resume of a parked record;
- child creation, which is internal and allowed in `:running` only.

### 1.3 The fenced writer

1. **Assign** `n` and store the id on the entry, in one step, before any request is sent. At 2^52-1 nothing is assigned or sent.
2. **Send** an `:yin.k/admit` request carrying the five-key envelope.
3. **Retain** the id through a `full` inbound stream, an unknown append, no outcome, and `:suspended`.
4. **Discharge** on an authenticated `:committed` or `:replayed`, applying the recorded result.

**Request id.** The tagged vector `[:yin.k/admit lease op-id]` in canonical form. It is correlation only. The front does not deduplicate by it; a resent request is dispatched again, and effect dedup is the authority's op-id namespace. A regrant changes the request id, because the lease changes; the op id does not change.

**A reply counts** only when all of these hold: its author is the arbitration identity (`front/reply-evidence`), its `:yin.k/reply` kind is `:yin.k/admit`, its request id matches, and its answer's op id and incarnation match the entry.

**A projected outcome** has no request wrapper. It counts when read from the outcome stream the resolver attributes to the arbitration identity, and its op id and incarnation match the entry. A projected `:committed` under another incarnation is not this holder's answer; this holder's retry gets `:replayed` by reply.

**Input `k` is independent** of request ids and of the operation counter. A regrant replays its occurrence's prefix from 0; a successor occurrence starts its own input sequence at 0.

### 1.4 The recorded reader and replay

**Source.** `{:yin.k/kind kind :yin.k/name name}`, with the structure inside `:yin.k/name`:

- `:next` and `:poll` reads: `{:yin.k/op :next | :poll, :yin.k/task path, :yin.k/stream identity, :yin.k/position p}`, where `p` is the existing portable position the read is made at. **(residual 1)**
- `:cursor`: `{:yin.k/op :cursor, :yin.k/task path, :yin.k/stream identity, :yin.k/origin origin}`, where `origin` is the requested cursor origin, such as `:dao.stream/oldest`. The source never contains the resulting position. **(residual 1)**
- FFI result: `{:yin.k/op :ffi-result, :yin.k/task path, :yin.k/call-id id}`.
- Link result: `{:yin.k/op :link-result, :yin.k/task path, :yin.k/link-id id}`.

`path` is `[]` for the root and the install names down to a child. A portable position is in the form a body's `:yin.k/position` uses. No cell id, host cursor or sealed reference appears in a source or an observed value.

**Cursor observations (residual 1).** The resulting portable position belongs only to the recorded observation. Replay constructs the cursor from that observation without live minting; it never mints a cursor to discover which source to match.

**Selection.** Live and replay use one rule: among waiting entries, order by task path, then wait-set order, and take the first whose source matches. Aliasing is kept: the first acknowledged read advances the shared cell before the next entry's source is computed, so two waiters on one cell see successive positions.

**Four retained states**, held as data on the entry:

1. observed, not yet requested;
2. requested, acceptance unknown;
3. acknowledged, awaiting application;
4. applied (the input sequence has advanced and the held state is gone).

**Held observations.**

> A held observation remains local and unexportable until its exact portable observation is durably acknowledged and applied once. Recording retries neither repeat the observation nor change its source, sequence, or observed value.

This holds for `:stream/poll` and `:stream/cursor` and for every other read. Export refuses any task that holds one in states 1 to 3, children included, as `:yin.k/non-portable`, kind `:reason-mismatch`. No wire pending variant is added.

**Replay.** While `k` is below the frontier the driver observes nothing live; record `k` is applied to the selected entry.

**Divergence** ends the run only when all of these hold: the ready queues of the root and every running child are empty; no fenced write awaits an outcome; no input is in states 1 to 3; no control request is outstanding; and no waiting entry matches record `k`. Waiting for a fenced-write reply is never divergence.

### 1.5 Remote paths

The driver reaches the authority through exactly three things.

1. **The front's request and reply streams**, amended with one request: `{:yin.k/request :yin.k/renewal :yin.k/request-id r :dao.lease/lease l}`. The front carries it to the holder's lease-fact stream exactly as it carries a proposal and a release. This is a small change to landed C code (slice D2).
2. **The outcome projection**, as in 1.3.
3. **An authenticated ledger reader.** The holder reads the authority's ledger stream, attributed by the composition's resolver to the arbitration identity, and folds it with `ledger/fold-record` into its own reader projection. From that it derives binding evidence (`custody/binding-evidence`), enrollment, and the replay prefix (`input/inputs`).

**Complete history or nothing.** The fold must start at the stream's origin, with dense `t`, and reach the grant's record. A gap, a start past the origin, a transport error, or a fold defect makes the evidence unavailable. Unavailable evidence is `:yin.k/unsatisfied` or `:yin.k/awaiting-grant`; it is never an empty prefix and never a missing enrollment.

In-process tests use the same three paths. No holder test reads `authority/projection`.

### 1.6 Ending a run

A run ends on `:stale`, `:intent-conflict`, an `:input-conflict` refusal, a replay divergence, or the lease bound.

- The mode becomes `:ended`. All program IO stops, declared at-least-once effects included. No successor is published.
- The driver appends one diagnostic to the composition's diagnostic stream.
- If the lease may still be live, the driver releases. That is cleanup.
- **(residual 2)** Release never clears quarantine or completes an occurrence without accepted completion evidence. A quarantined occurrence remains ungrantable. For other failed runs, release follows the ordinary reclaim/regrant policy; the ending driver does not itself authorize recovery.
- An input conflict or a divergence is never reported or treated as `:intent-conflict`, and quarantines nothing.

### 1.7 The version-aware reader

Before any attachment, proposal or restoration, in this order:

1. **Decode (residual 3).** The two codecs are deliberately different profiles: `dao.stream.cbor` writes tag 39 identifiers and `dao.jing.cbor`'s decoder refuses tag 39. D7 has two decoding paths. If neither supported codec decodes the bytes, return `:yin.k/undecodable`. Retain which codec accepted the bytes and enforce its body-version contract before address and restoration checks.
   - Bytes the stream codec accepted may only be a version-0 body. The version-0 lower keeps the stream codec.
   - Bytes the Jing codec accepted may only be a version-1 body.
   - A version-0 decode is never re-encoded into version 1. A failure in version-1 structural validation never causes the bytes to be reinterpreted as version 0.
2. **Tag.**
3. **Version gate.** A version outside the accepting codec's contract, absent, or not of the integer kind is `:yin.k/profile-mismatch`. This precedes the address check, as 7.2.1 requires.
4. **Version 1 only:** `checkpoint/inspect address bytes`, which checks the body's address (`:yin.k/hash-mismatch`) and the custody grammar, including an entry for every install pending.
5. **Full recursive restoration validation:** code hashes, cells, registers, install responses, clause 5.

Version 0 skips step 4 and keeps its fork semantics. The inspector is baseline inspection, not full validation. The authority's offer admission keeps the inspector's own order (address first); the resumer's order above is the normative one for 7.2.1.

### 1.8 Export: prepare, then encode

> The export record retains every chosen resource identity, descriptor, occurrence, and remapping seed. Encoding that prepared record performs no resource allocation or publication and produces canonical bytes deterministically.

- **Prepare** calls `serve!`, once per stream, recursively for children, and stores the results in the export record.
- **Encode** is a pure function of that record.
- Maps and sets, and anything derived from them such as cell numbering, are ordered by canonical encoded key bytes. Wait order and alias identity are never reordered.
- The driver's transport envelope is never persisted as runtime wrapper state. A program value shaped like an envelope is payload and may appear in a body.

### 1.9 Exporting and abort

Entering exporting sets the mode, requires an empty ready queue, and moves waits and reachable parked records into the export record.

> Abort is permitted only before any possibly accepted offer attempt, or upon authoritative evidence that this occurrence has never been admitted and cannot still become admitted from an outstanding attempt. A refusal of one request is not such evidence.

In practice abort is legal when no offer-attempt intent has been persisted, or when every attempt's append answered an outcome that proves the value was not appended. Otherwise the source stays fenced and resends the offer; the only way back is a grant to itself.

A holder exporting a successor may abort only with current valid tenure, established through the ledger reader and its own lease bound. After the lease has ended its old local machine is never restored.

### 1.10 The progress journal

A `dao.stream.journal` the composition supplies, write-ahead.

- **Before `fenced`:** the prepared export record and the body bytes are put in the content store; the `fenced` record references them and carries the occurrence.
- **Each external action** is bracketed: an intent record is durable before the action (offer, proposal with its stable proposal id, resumed report, release, enrollment request), and an acknowledgment record follows authenticated evidence of it.
- **An uncertain journal append** stops everything, program steps and sends alike, until the journal is reopened and reconciled.
- **Recovery of a source:** fold the journal; stay fenced; rebuild waits, retained ids and descriptors from the stored prepared record; resend from the last intent.
- **Recovery of a holder:** a journaled grant never restores execution. The holder's machine died with its process, so it sends the pending release and re-enters as a candidate; a regrant replays from the checkpoint.
- **Enrollment retries** follow the identity discipline UCF 7.7.7 gained with C12. I have not read that sentence; D14 must.

### 1.11 Protection classes and the composition

Classes: `:enrolled` (writes go through the fenced writer), `:at-least-once` (the driver appends bare, with no id), `:fail-stop` (a write under custody ends the run). An undeclared stream is `:yin.k/unsatisfied` naming it. Enrollment is read from the ledger by both ends. Reads are recorded whatever the class.

- `yin.vm.ucf.compose` accepts an exclusive handoff only when `authority/exclusive-capable?` holds for the required failure model. It never bypasses the predicate.
- A caller that asks for exclusive without such an authority gets `:yin.k/unsatisfied`. Fork is offered only when the caller selects fork.
- Protocol tests on the memory substrate drive the holder namespaces directly, below `compose`.

### 1.12 The REPL

`yin.repl.core` is gone. The tick owner is `yin.repl.main/step-all` (line 804), whose order is `yin.repl.dht/step`, the hydration and refusal checks, `shell/recheck-on-load-events`, `driver/repl-step`, then `serve/step`.

- **Custody control-plane step:** immediately after `yin.repl.dht/step` and before the `refusal` and `admitting?` branches. It carries renewals, pending releases, request retries, the judge step and front steps. It runs during hydration.
- **Custody program step** (observe and apply for tasks): after `driver/repl-step`, before `serve/step`.
- **Shutdown (residual 4).** The callers change. Today the JVM loop leaves `step-all` when `:running?` turns false and then drains and closes (about line 981); Node and Dart switch to `stop-tick`, which does not call `step-all` (about lines 1124 and 1254); `moved?` tracks shell and server progress only (line 849). So:
  - The custody control-plane step continues on every tick of the existing bounded shutdown drain (the `stop-ticks` budget), on all three hosts. Program execution stays stopped: no custody program step runs once `:running?` is false.
  - `moved?` also reports true while custody owes a control-plane write, so cadence stays at the base interval.
  - Anything still outstanding when the drain's budget ends is already a durable intent in the progress journal (1.10). The journal is closed before exit, and restart resends from it.
  - This uses the existing tick owner on each host. No second timer, thread or DHT step owner.

## 2. Slices

All code is `.cljc`. JVM per iteration; three lanes once at each landing. Paths are under `src/cljc/` unless they begin with `test/`.

| Slice | Scope | Files | 7.11.1 evidence | Depends on |
|---|---|---|---|---|
| D1 | The three version-0 defects (in flight, unchanged) | `yin/vm/ucf/handoff.cljc` | 5 (version-0 half) | none |
| D2 | Front `:yin.k/renewal` request | `yin/vm/ucf/authority/front.cljc` | substrate | none |
| D3 | Authenticated ledger reader | new `yin/vm/ucf/holder/evidence.cljc` | 7 (holder evidence) | none |
| D4 | Engine round 1: immediate effects split, gate modes | `yin/vm/engine.cljc` | substrate | none |
| D5 | Engine round 2: ordinary sweep, FFI request and response routing | `engine.cljc` | substrate | D4 |
| D6 | Engine round 3: links, install children, direct resume, child stamping | `engine.cljc` | substrate | D5 |
| D7 | Version-aware reader with two decode paths, version-1 grammar | `handoff.cljc` | 1, 2, 4 (structural), 5 | D1 |
| D8 | Exporting state, prepare, abort rule | new `yin/vm/ucf/holder/export.cljc`; `handoff.cljc` (prepare extracted) | 7.11 exporting blocker | D6, D7 |
| D9 | Version-1 lift as pure encode; fixtures regenerated | `handoff.cljc`; `test/yin/vm/ucf/checkpoint_fixtures.cljc` | 4 (first exclusive export), 9 (sequence) | D8 |
| D10 | Version-1 lower | `handoff.cljc` | 3 (restoration), 4 (protection mismatch) | D6, D7 |
| D11 | Fenced writer | new `yin/vm/ucf/holder/writer.cljc` | 3, 6 (writer), 8 (driver), 9 | D6 |
| D12 | Recorded reader and replay | new `yin/vm/ucf/holder/reader.cljc` | 3 (delivery) | D3, D6 |
| D13 | Driver, candidate half | new `yin/vm/ucf/holder/driver.cljc` | 7 (holder) | D2, D3, D10, D11, D12 |
| D14 | Driver, source and exit half, journal | `holder/driver.cljc` | 14.2.4 rows 2, 7 | D8, D9, D13 |
| D15 | Composition and REPL wiring, shutdown included | new `yin/vm/ucf/compose.cljc`; `yin/repl.cljc`, `yin/repl/main.cljc` | 7.11 wiring | D14, C12 |
| D16 | Safepoint harness, 14.2.4 rows, stage-D gate | tests | safepoint block, 14.2.4 | D15 |

**Order and concurrency.**
- D1, D2, D3 and D4 start together.
- `engine.cljc` is serialized: D4, D5, D6.
- `handoff.cljc` is serialized: D1, D7, D10, D8's extraction, D9.
- D11 and D12 run concurrently after D6.
- Then D13, D14, D15, D16 in order.

### Test contracts

**D1.** Parked frames restore in order; an entry for every install pending; the address equals the digest of the emitted bytes.

**D2.** A renewal is carried to the holder's lease-fact stream and answers `:carried`; a poisoned authority answers `:suspended`; an unresolved author yields a `:wrong-author` diagnostic; the judge counts the carried renewal.

**D3.**
- A fold from the origin yields the same binding, enrollment and prefix as the authority's own projection.
- A gap, a start past the origin, a transport error and a fold defect each answer unavailable; none answers an empty prefix.
- Records from another author establish nothing.
- The evidence survives a `dao.stream.remote` reflection.

**D4 to D6.** Per round, the existing suites pass with no gate. With the gate, counting handles show zero calls for:
- each immediate effect (D4);
- the ordinary sweep, an FFI call, a retained FFI request, and a response reader shared by two waiters (D5);
- a link request, link-response scanning, child creation, child advance, and direct resume (D6).

In `:exporting` and `:ended`, additionally: no child runs, a resume is refused, and each apply refuses a late result. A child created under a gated root has the root's mode and no custody map.

**D7.**
- **(residual 3)** Two decode paths: stream-codec bytes are accepted only as version 0, Jing-codec bytes only as version 1; bytes neither codec decodes are `:yin.k/undecodable`.
- **(residual 3)** The accepting codec is retained with the decoded value. A version-1 body in stream-codec bytes and a version-0 body in Jing-codec bytes are each `:yin.k/profile-mismatch`. A version-1 structural failure is not retried as version 0.
- Version fixtures on bytes: 2, absent, float `1.0`, a version-0 child in a version-1 root.
- Unsupported version wins over a wrong address.
- A valid version-0 body still lowers as a fork and is refused when exclusive is required.
- Header and carried-id fixtures; clause 5 on both versions; halted forbids frames.
- Zero attach calls and zero proposals on every refusal.

**D8.**
- After exporting, polling, direct resume and child ticks append nothing.
- Prepare calls `serve!` once per stream; encode calls nothing.
- Abort: allowed with no persisted intent; allowed when every attempt was provably not appended; refused after an unknown append, and refused after a refusal that followed an unknown append.
- A holder past its lease bound cannot abort.

**D9.**
- Two encodes of one prepared record, on each host, give equal bytes.
- Fixtures include a float, signed zero, NaN, nested maps, shared cells and children.
- `:unprotected-pending` and `:op-seq-exhausted`.
- A task with a held observation, in the root or a child, refuses export.
- An envelope-shaped program value survives in the body as payload.
- The C4 accepted fixtures are regenerated here and their pinned addresses change in this commit.

**D10.** Counter restored exactly; carried ids intact on the three variants; protection mismatch in each direction; a restored child does not rerun initialization; receiver-local store bindings do not change resolution.

**D11.**
- Assign before send; one increment; children draw from the root.
- Retention across the four cases.
- A reply failing any one of the five match fields discharges nothing.
- A projected outcome of another incarnation is ignored.
- A resent request after a regrant has a new request id and the same op id.
- Unknown-effect transport error, cut before and after the commit.
- After `:intent-conflict`: an at-least-once write is not performed, the release is carried, and the occurrence stays quarantined, open and ungranted.

**D12.**
- Nothing is applied before its acknowledgment.
- A held poll survives ten failed recording attempts with one observation and one `k`.
- Unknown recording acceptance resends the identical request.
- **(residual 1)** A `:cursor` source carries the requested origin and no position. Replaying a recorded cursor creation makes zero mint calls on the handle (counted) and yields a cursor at the recorded position, including when the live stream's oldest position has since moved.
- **(residual 1)** `:next` and `:poll` sources carry the position read at; a `:poll` and a `:next` at one position do not match each other's records.
- Two waiters on one cell replay successive positions.
- Root and child with equal call ids replay to the right task.
- Divergence is not declared while a fenced write awaits its outcome.
- `:input-conflict` ends the run with no quarantine.
- 14.2.4 row 5: an evicted value, recovered with records and without.

**D13.**
- Two candidates over two encodings; `:yin.k/awaiting-grant` and `:yin.k/not-holder`.
- Each invalid binding answers `:yin.k/not-holder` and releases.
- Unavailable ledger history never activates.
- Renewal before half the duration; all IO stops at the bound.
- **(residual 2)** After a post-grant failure the driver releases, and the occurrence is granted again to a candidate that proposes: an ordinary failed run stays regrantable. The driver itself proposes nothing on behalf of recovery.

**D14.**
- A crash at each intent and acknowledgment boundary reopens fenced with the same occurrence, waits and ids.
- A crash between send and acknowledgment resends, and no second grant results.
- An uncertain journal append stops the driver.
- A restarted holder with a journaled grant does not run; it releases and re-proposes.
- Exit cuts at successor append, report, release and closure; a halt completes with a result body.
- **(residual 2)** A release without accepted completion evidence never closes the occurrence; after a replay divergence the released occurrence is regrantable; after `:intent-conflict` the released occurrence stays quarantined and ungrantable.

**D15.**
- Exclusive on a memory authority is refused, not forked.
- Fork runs when selected and is labelled fork.
- A whole exclusive handoff runs on a file-backed authority that passes the predicate, on each host.
- With the DHT store still hydrating, a renewal and a pending release are still sent.
- The plain composition test has no `yin.repl` namespace loaded.
- **(residual 4)** After `:running?` turns false, with a release held back by a full inbound stream: the control-plane step is called on every tick of the bounded drain, on each host's loop; the release is delivered once the stream accepts it; no program step runs.
- **(residual 4)** With the stream full for the whole drain: the process exits within the existing budget, the release intent is in the journal, and a restart sends it.
- **(residual 4)** `moved?` is true while a custody control-plane write is owed.

**D16.**
- Each corpus segment to each liftable row of 7.4.1, on both body versions, against the reference result and trace. Explicit park is checked through its parked record, not a pending. A held immediate is asserted to refuse.
- 14.2.4 rows 1 to 5, 7 and 8 through the composition, same host; row 6's retry logic as a unit test.

## 3. Boundary with E

- **D owns** same-host driver and composition behavior on three lanes: protocol tests on the memory substrate, composition acceptance on a file-backed authority.
- **E owns** the ordered cross-host pairs, real partitions and process kills, clause 10, the composition halves of clauses 3 and 8, and the per-host durability record.
- **D hands E** `yin.vm.ucf.compose` as the only entry point, the regenerated fixtures, and the journal's record format.

## 4. Risks, riskiest first

1. **D4 to D6.** A missed observation path is an unfenced effect. The three zero-call lists are the control.
2. **D12, replay identity.** The source depends on portable positions being equal across hosts and across a lower. If a transport's portable cursor is not stable, replay diverges falsely.
3. **D3, cost.** Each holder folds the whole ledger. This is O(history) per grant on top of one fsync per input.
4. **D14, the abort and recovery rules.** A wrong early abort, or execution restored from a journaled grant, creates two runners.
5. **D15.** C12 is not yet on master, and the shutdown change touches three host loops in `main.cljc`.

(r2's codec-dispatch risk is closed by residual 3.)

## 5. Document amendments implementation will force (listed, not written)

Carried from the C4 rulings:
- UCF 7.2.1: `jing/canonical-bytes`, the segment-address form, a halt with no origin, the required emitter.
- UCF 7.2.1 version gate: nested-body statuses.
- UCF 7.4.3: the install-entry rule as a baseline rule.
- UCF 7.7.8 *Snapshot variants*: comparison on digests.
- UCF 7.9: `:yin.k/hash-mismatch` over a body's own address.
- Linker-dht 14.3: the third version-0 defect.

From D:
- **UCF 7.2.1:** the reader pipeline's order; the two codecs and the body version each may carry (residual 3); the gate precedes the address check for a resumer.
- **UCF 7.4.1 and 7.4.3:** a task holding an unapplied observation is not at a liftable safepoint.
- **UCF 7.7.4:** the three gate modes, child stamping, the abort text of 1.9, the holder-tenure condition.
- **UCF 7.7.5 and 7.7.8:** the driver performs every effect of a task under custody; protection classes; run end stops all program IO; the release text of 1.6 (residual 2).
- **UCF 7.7.8:** the request-id form and that it is not dedup; the reply and projection match rules; the `:yin.k/renewal` request; the authenticated ledger reader and the complete-history rule.
- **UCF 7.5.3:** prepare and encode; ordering by canonical key bytes.
- **UCF 7.9:** input conflict and divergence are not `:intent-conflict`.
- **UCF 7.11 and linker-dht 14.3 item 4:** `yin.repl.main/step-all` replaces `yin.repl.core`; the two custody step positions; control-plane stepping through the shutdown drain (residual 4).
- **Linker-dht 14.2.2:** the source structure inside `:yin.k/name`, with the origin form for `:cursor` and replay without live minting (residual 1); the four input states; the selection rule; the divergence condition; the journal's write-ahead contract.
- **Linker-dht 14.2.1:** exclusive is refused, not downgraded, without a capable authority.
- **`dao.lease.md` *Carriage*:** a renewal carried by the front.
- **`yin.vm.ucf-revisions.md` section 6:** version 1 moves to implemented, slice by slice.
