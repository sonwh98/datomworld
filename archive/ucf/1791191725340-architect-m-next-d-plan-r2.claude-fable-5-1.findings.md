Completed-GMT: 2026-10-05 09:17:37 GMT
Completed-Local: 2026-10-05 16:17:37 +07

**Summary: all 12 findings are accepted and folded in; r2 adds two remote paths (a renewal request on the front and an authenticated ledger reader), three gate modes, a portable replay source, a prepare/encode split for export, and a write-ahead driver journal; 16 slices, D1 to D16.**

Read-only; nothing in the repo edited, no suites run. I checked every line the review cites. I did not read `yin.repl.main`'s callers of `step-all`, the C12 reports, or the two CBOR codecs' internals; the two places that depend on those are marked. A skeleton of this plan is in the session plan file.

## 0. The 12 findings

| # | Finding | Evidence checked | Ruling |
|---|---|---|---|
| 1 | Gate does not cover every observation path; children have no policy | `engine.cljc` 1232 runs the child with a bare `vm/run`; `check-wait-set` 1630-1651 polls links, FFI responses, then the sweep | Accept: 1.2 |
| 2 | "Held one step" is wrong; source lacks an operation discriminator | `input.cljc` 83-88 accepts exactly `{:yin.k/kind :yin.k/name}` | Accept: 1.4 |
| 3 | Replay source is not portable; application states unnamed | Cells are reminted at lower (`handoff.cljc`, `fresh-key`) | Accept: 1.4 |
| 4 | Request id is correlation, not dedup | `front.cljc` `handle!` dispatches every request again | Accept: 1.3 |
| 5 | No remote renewal or replay-prefix path | `front.cljc` `answer` has six kinds, no renewal; `input/inputs` takes a projection | Accept: 1.5 |
| 6 | Release is cleanup; run end stops all IO; input conflict is not intent conflict | `input.cljc` docstring: an input conflict commits nothing | Accept: 1.6 |
| 7 | Inspector speaks version 1 only | `checkpoint.cljc` 396-417 checks the address, then version 1 grammar | Accept: 1.7 |
| 8 | Lift is not pure today | `export-task` calls `serve!` while encoding | Accept: 1.8 |
| 9 | Abort rule unsafe | A refused retry does not prove an earlier offer was not admitted | Accept: 1.9 |
| 10 | Journal needs a recovery contract | r1 listed record names only | Accept: 1.10 |
| 11 | Memory seam versus exclusivity gate; D12 exclusions; D2 too broad | C12's predicate rejects memory authorities | Accept: 1.11, slices |
| 12 | REPL insertion point | `main.cljc` 804-848: `step-all` returns before the shell step while not `admitting?` | Accept: 1.12 |

## 1. The contract

### 1.1 A task under custody (unchanged from r1)

A version-1 task's root machine value carries `:yin.k/custody`: occurrence, lease, epoch, arbitration identity, `:yin.k/next-op-seq`, the input state (next `k`, frontier, whether the prefix has ended), the protection class per stream identity, and a mode. A version-0 task has none and behaves as today.

### 1.2 The engine seam and the gate (changed)

**Split.** Every place the engine touches a stream becomes observe (handle IO to an outcome) and apply (outcome to machine value). Ungated behavior is `apply ∘ observe`, unchanged.

**Modes.** The gate reads one key, `:yin.k/gate`, on any machine value:

- absent: ungated.
- `:running`: internal computation proceeds; every observation parks; the driver observes and applies.
- `:exporting` and `:ended`: nothing is observed, no child is advanced, a direct resume is refused, and every public apply refuses a late result.

**Children.** The root owns the mode. The engine stamps `:yin.k/gate` on a child's machine value when it creates the child and again before each `vm/run` of it (line 1232). A child carries the mode only: no counters, no lease, no input state.

**Every path gated before it observes:**

- immediate effects: `:stream/put`, `:stream/next`, `:stream/poll`, `:stream/cursor`, `:stream/close`, the FFI request append, the link request append;
- `check-wait-set`: link request retries and link-response scans, install-child advance, FFI response routing (shared response readers included), and the ordinary sweep;
- direct resume of a parked record;
- child creation, which is internal and allowed in `:running` only.

### 1.3 The fenced writer (changed in the correlation rules)

1. **Assign** `n` and store the id on the entry, in one step, before any request is sent. At 2^52-1 nothing is assigned or sent.
2. **Send** an `:yin.k/admit` request carrying the five-key envelope.
3. **Retain** the id through a `full` inbound stream, an unknown append, no outcome, and `:suspended`.
4. **Discharge** on an authenticated `:committed` or `:replayed`, applying the recorded result.

**Request id.** The tagged vector `[:yin.k/admit lease op-id]` in canonical form. It is correlation only. The front does not deduplicate by it; a resent request is dispatched again, and effect dedup is the authority's op-id namespace. A regrant changes the request id, because the lease changes; the op id does not change.

**A reply counts** only when all of these hold: its author is the arbitration identity (`front/reply-evidence`), its `:yin.k/reply` kind is `:yin.k/admit`, its request id matches, and its answer's op id and incarnation match the entry.

**A projected outcome** has no request wrapper. It counts when read from the outcome stream the resolver attributes to the arbitration identity, and its op id and incarnation match the entry. A projected `:committed` under another incarnation is not this holder's answer; this holder's retry gets `:replayed` by reply.

**Input `k` is independent** of request ids and of the operation counter. A regrant replays its occurrence's prefix from 0; a successor occurrence starts its own input sequence at 0.

### 1.4 The recorded reader and replay (changed)

**Source.** `{:yin.k/kind kind :yin.k/name name}`, with the structure inside `:yin.k/name`:

- read kinds: `{:yin.k/op :next | :poll | :cursor, :yin.k/task path, :yin.k/stream identity, :yin.k/position p}`;
- FFI result: `{:yin.k/op :ffi-result, :yin.k/task path, :yin.k/call-id id}`;
- link result: `{:yin.k/op :link-result, :yin.k/task path, :yin.k/link-id id}`.

`path` is `[]` for the root and the install names down to a child. `p` is the portable cursor the read was made at, in the form a body's `:yin.k/position` uses. No cell id, host cursor or sealed reference appears in a source or an observed value. A minted cursor is observed as its portable position.

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

### 1.5 Remote paths (new)

The driver reaches the authority through exactly three things.

1. **The front's request and reply streams**, amended with one request: `{:yin.k/request :yin.k/renewal :yin.k/request-id r :dao.lease/lease l}`. The front carries it to the holder's lease-fact stream exactly as it carries a proposal and a release. This is a small change to landed C code (slice D2).
2. **The outcome projection**, as in 1.3.
3. **An authenticated ledger reader.** The holder reads the authority's ledger stream, attributed by the composition's resolver to the arbitration identity, and folds it with `ledger/fold-record` into its own reader projection. From that it derives binding evidence (`custody/binding-evidence`), enrollment, and the replay prefix (`input/inputs`).

**Complete history or nothing.** The fold must start at the stream's origin, with dense `t`, and reach the grant's record. A gap, a start past the origin, a transport error, or a fold defect makes the evidence unavailable. Unavailable evidence is `:yin.k/unsatisfied` or `:yin.k/awaiting-grant`; it is never an empty prefix and never a missing enrollment.

In-process tests use the same three paths. No holder test reads `authority/projection`.

### 1.6 Ending a run (changed)

A run ends on `:stale`, `:intent-conflict`, an `:input-conflict` refusal, a replay divergence, or the lease bound.

- The mode becomes `:ended`. All program IO stops, declared at-least-once effects included. No successor is published.
- The driver appends one diagnostic to the composition's diagnostic stream.
- If the lease may still be live, the driver releases. That is cleanup, not recovery: a release cannot clear a quarantine, complete the occurrence, or make it regrantable.
- An input conflict or a divergence is never reported or treated as `:intent-conflict`, and quarantines nothing.

### 1.7 The version-aware reader (changed)

Before any attachment, proposal or restoration, in this order:

1. **Decode.** A body is version 1 only in `dao.jing.cbor` canonical bytes and version 0 only in the stage-1 codec. Neither decoding is `:yin.k/undecodable`.
2. **Tag.**
3. **Version gate.** A version not spoken in the codec that decoded it, absent, or not of the integer kind is `:yin.k/profile-mismatch`. This precedes the address check, as 7.2.1 requires.
4. **Version 1 only:** `checkpoint/inspect address bytes`, which checks the body's address (`:yin.k/hash-mismatch`) and the custody grammar, including an entry for every install pending.
5. **Full recursive restoration validation:** code hashes, cells, registers, install responses, clause 5.

Version 0 skips step 4 and keeps its fork semantics. The inspector is baseline inspection, not full validation. The authority's offer admission keeps the inspector's own order (address first); the resumer's order above is the normative one for 7.2.1.

I did not verify whether one decoder reads both codecs' bytes. D7's first test pins that; the rule in step 1 holds either way.

### 1.8 Export: prepare, then encode (changed)

> The export record retains every chosen resource identity, descriptor, occurrence, and remapping seed. Encoding that prepared record performs no resource allocation or publication and produces canonical bytes deterministically.

- **Prepare** calls `serve!`, once per stream, recursively for children, and stores the results in the export record.
- **Encode** is a pure function of that record.
- Maps and sets, and anything derived from them such as cell numbering, are ordered by canonical encoded key bytes. Wait order and alias identity are never reordered.
- The driver's transport envelope is never persisted as runtime wrapper state. A program value shaped like an envelope is payload and may appear in a body.

### 1.9 Exporting and abort (changed)

Entering exporting sets the mode, requires an empty ready queue, and moves waits and reachable parked records into the export record.

> Abort is permitted only before any possibly accepted offer attempt, or upon authoritative evidence that this occurrence has never been admitted and cannot still become admitted from an outstanding attempt. A refusal of one request is not such evidence.

In practice abort is legal when no offer-attempt intent has been persisted, or when every attempt's append answered an outcome that proves the value was not appended. Otherwise the source stays fenced and resends the offer; the only way back is a grant to itself.

A holder exporting a successor may abort only with current valid tenure, established through the ledger reader and its own lease bound. After the lease has ended its old local machine is never restored.

### 1.10 The progress journal (changed)

A `dao.stream.journal` the composition supplies, write-ahead.

- **Before `fenced`:** the prepared export record and the body bytes are put in the content store; the `fenced` record references them and carries the occurrence.
- **Each external action** is bracketed: an intent record is durable before the action (offer, proposal with its stable proposal id, resumed report, release, enrollment request), and an acknowledgment record follows authenticated evidence of it.
- **An uncertain journal append** stops everything, program steps and sends alike, until the journal is reopened and reconciled.
- **Recovery of a source:** fold the journal; stay fenced; rebuild waits, retained ids and descriptors from the stored prepared record; resend from the last intent.
- **Recovery of a holder:** a journaled grant never restores execution. The holder's machine died with its process, so it sends the pending release and re-enters as a candidate; a regrant replays from the checkpoint.
- **Enrollment retries** follow the identity discipline UCF 7.7.7 gained with C12. I have not read that sentence; D14 must.

### 1.11 Protection classes and the composition (changed in the gate rule)

Classes are as in r1: `:enrolled`, `:at-least-once`, `:fail-stop`; an undeclared stream is `:yin.k/unsatisfied` naming it.

- `yin.vm.ucf.compose` accepts an exclusive handoff only when `authority/exclusive-capable?` holds for the required failure model. It never bypasses the predicate.
- A caller that asks for exclusive without such an authority gets `:yin.k/unsatisfied`. Fork is offered only when the caller selects fork.
- Protocol tests on the memory substrate drive the holder namespaces directly, below `compose`.

### 1.12 The REPL (changed)

`yin.repl.core` is gone. The tick owner is `yin.repl.main/step-all` (line 804), whose order is `yin.repl.dht/step`, the hydration and refusal checks, `shell/recheck-on-load-events`, `driver/repl-step`, then `serve/step`.

- **Custody control-plane step:** immediately after `yin.repl.dht/step` and before the `refusal` and `admitting?` branches. It carries renewals, pending releases, request retries, the judge step and front steps. It runs during hydration, and once more on the tick that stops the shell.
- **Custody program step** (observe and apply for tasks): after `driver/repl-step`, before `serve/step`.
- No new DHT step owner, thread or timer. I did not read `step-all`'s callers; D15 confirms they need no change.

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
| D7 | Version-aware reader, version-1 grammar | `handoff.cljc` | 1, 2, 4 (structural), 5 | D1 |
| D8 | Exporting state, prepare, abort rule | new `yin/vm/ucf/holder/export.cljc`; `handoff.cljc` (prepare extracted) | 7.11 exporting blocker | D6, D7 |
| D9 | Version-1 lift as pure encode; fixtures regenerated | `handoff.cljc`; `test/yin/vm/ucf/checkpoint_fixtures.cljc` | 4 (first exclusive export), 9 (sequence) | D8 |
| D10 | Version-1 lower | `handoff.cljc` | 3 (restoration), 4 (protection mismatch) | D6, D7 |
| D11 | Fenced writer | new `yin/vm/ucf/holder/writer.cljc` | 3, 6 (writer), 8 (driver), 9 | D6 |
| D12 | Recorded reader and replay | new `yin/vm/ucf/holder/reader.cljc` | 3 (delivery) | D3, D6 |
| D13 | Driver, candidate half | new `yin/vm/ucf/holder/driver.cljc` | 7 (holder) | D2, D3, D10, D11, D12 |
| D14 | Driver, source and exit half, journal | `holder/driver.cljc` | 14.2.4 rows 2, 7 | D8, D9, D13 |
| D15 | Composition and REPL wiring | new `yin/vm/ucf/compose.cljc`; `yin/repl.cljc`, `yin/repl/main.cljc` | 7.11 wiring | D14, C12 |
| D16 | Safepoint harness, 14.2.4 rows, stage-D gate | tests | safepoint block, 14.2.4 | D15 |

**Order and concurrency.**
- D1, D2, D3 and D4 start together.
- `engine.cljc` is serialized: D4, D5, D6.
- `handoff.cljc` is serialized: D1, D7, D10, D8's extraction, D9.
- D11 and D12 run concurrently after D6.
- Then D13, D14, D15, D16 in order.

### Test contracts

**D1.** Unchanged: parked frames restore in order; an entry for every install pending; the address equals the digest of the emitted bytes.

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
- Version fixtures on bytes: 2, absent, float `1.0`, a version-0 child in a version-1 root.
- Unsupported version wins over a wrong address.
- A valid version-0 body still lowers as a fork and is refused when exclusive is required.
- Header and carried-id fixtures as in r1; clause 5 on both versions; halted forbids frames.
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
- Two waiters on one cell replay successive positions.
- Root and child with equal call ids replay to the right task.
- Divergence is not declared while a fenced write awaits its outcome.
- `:input-conflict` ends the run with no quarantine.
- 14.2.4 row 5: an evicted value, recovered with records and without.

**D13.** Two candidates over two encodings; `:yin.k/awaiting-grant` and `:yin.k/not-holder`; each invalid binding answers `:yin.k/not-holder` and releases; unavailable ledger history never activates; post-grant failure releases; renewal before half the duration; all IO stops at the bound.

**D14.**
- A crash at each intent and acknowledgment boundary reopens fenced with the same occurrence, waits and ids.
- A crash between send and acknowledgment resends, and no second grant results.
- An uncertain journal append stops the driver.
- A restarted holder with a journaled grant does not run; it releases and re-proposes.
- Exit cuts at successor append, report, release and closure; a halt completes with a result body.

**D15.**
- Exclusive on a memory authority is refused, not forked.
- Fork runs when selected and is labelled fork.
- A whole exclusive handoff runs on a file-backed authority that passes the predicate, on each host.
- With the DHT store still hydrating, a renewal and a pending release are still sent.
- The plain composition test has no `yin.repl` namespace loaded.

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
5. **D7, codec dispatch.** Unverified whether the two decoders overlap.
6. **D15.** C12 is not yet on master, and `step-all`'s callers are unread.

## 5. Document amendments implementation will force (listed, not written)

Carried from the C4 rulings:
- UCF 7.2.1: `jing/canonical-bytes`, the segment-address form, a halt with no origin, the required emitter.
- UCF 7.2.1 version gate: nested-body statuses.
- UCF 7.4.3: the install-entry rule as a baseline rule.
- UCF 7.7.8 *Snapshot variants*: comparison on digests.
- UCF 7.9: `:yin.k/hash-mismatch` over a body's own address.
- Linker-dht 14.3: the third version-0 defect.

New or changed in r2:
- **UCF 7.2.1:** the reader pipeline's order, and that the gate precedes the address check for a resumer.
- **UCF 7.4.1 and 7.4.3:** a task holding an unapplied observation is not at a liftable safepoint.
- **UCF 7.7.4:** the three gate modes, child stamping, the abort text of 1.9, the holder-tenure condition.
- **UCF 7.7.5 and 7.7.8:** the driver performs every effect of a task under custody; protection classes; run end stops all program IO; release as cleanup.
- **UCF 7.7.8:** the request-id form and that it is not dedup; the reply and projection match rules; the `:yin.k/renewal` request; the authenticated ledger reader and the complete-history rule.
- **UCF 7.5.3:** prepare and encode; ordering by canonical key bytes.
- **UCF 7.9:** input conflict and divergence are not `:intent-conflict`.
- **UCF 7.11 and linker-dht 14.3 item 4:** `yin.repl.main/step-all` replaces `yin.repl.core`; the two custody step positions.
- **Linker-dht 14.2.2:** the source structure inside `:yin.k/name`, the four input states, the selection rule, the divergence condition, and the journal's write-ahead contract.
- **Linker-dht 14.2.1:** exclusive is refused, not downgraded, without a capable authority.
- **`dao.lease.md` *Carriage*:** a renewal carried by the front.
- **`yin.vm.ucf-revisions.md` section 6:** version 1 moves to implemented, slice by slice.
