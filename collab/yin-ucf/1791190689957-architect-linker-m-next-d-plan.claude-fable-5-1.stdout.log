Completed-GMT: 2026-10-05 09:00:52 GMT
Completed-Local: 2026-10-05 16:00:52 +07

**Summary: stage D makes the driver, not the engine, perform every stream effect of a task under custody, through one engine seam (observe/apply split plus a gate), a fenced writer, a recorded reader and a handoff driver that speaks only the C11 front; 12 slices, D1 to D12.**

Read-only; nothing edited, no suites run. I read the brief, the landed authority namespaces' docstrings and signatures, the engine's put, park and wait-poll paths, `handoff.cljc`'s lift of pendings, and the REPL's step loop. I did not read the C12 reports, `yin-repl-design.md`, or the linker's sections 5.2 and 9 in full; where the plan leans on those it says so.

## 0. What the tree says today

- **The engine does the IO itself.** `handle-put` calls `stream/append!` at once (`engine.cljc` 480-503); `handle-next` and `handle-poll` read at once; `check-wait-set` retries appends and reads on every sweep (1592-1651). Nothing assigns an id, records an input, or asks an authority.
- **No exporting state exists in the handoff.** `export-task` is a pure lift; a grep of `handoff.cljc` for an export record, a detach or an abort finds none. UCF 7.7.4's transition is still owed.
- **`handoff-version` is 0.** Export encodes with `dao.stream.cbor` (line 719) and addresses with `jing/content-hash` (line 725).
- **The authority side is complete as plain functions.** Holders reach it through `authority.front` (requests `:yin.k/offer`, `:yin.k/proposal`, `:yin.k/resumed`, `:yin.k/release`, `:yin.k/input`, `:yin.k/admit`; replies carry `:yin.k/request-id`). Committed outcomes are also on `admission/outcome-reader`. `custody/binding-evidence`, `front/reply-evidence`, `input/frontier`, `input/inputs` and `input/replay-input` are the reader-side helpers. Input source kinds are closed: read, FFI result, link result.
- **`yin.repl.core` no longer exists.** The composition root is `yin.repl.main/step-all`, the single step owner, over the `yin.repl` shell, `yin.repl.driver`, `yin.repl.link` and `yin.repl.dht`.
- **C12 is on branch `ucf-c12-gate`, not yet on master.** `authority/durability` and `exclusive-capable?` are not in master's `authority.cljc`. D11 depends on C12 landing.

## 1. The contract

### 1.1 A task under custody

A version-1 task carries one plain-data map on its root machine value, `:yin.k/custody`:

- the occurrence its grant names, the lease, the epoch from the binding, the arbitration identity;
- `:yin.k/next-op-seq`;
- the input state: next `k`, the lease's frontier, and whether the replay prefix has ended;
- the protection class of each reachable stream identity (1.5);
- a run state: `:running`, `:ended` (after `:stale`, `:intent-conflict`, replay divergence, or the lease bound).

Install children carry none of this. They are found through the root and draw from its counters. A version-0 task has no such map and behaves exactly as today.

### 1.2 The engine seam (the one engine change)

Every place the engine touches a stream is split into two functions:

- **observe:** handle IO, returning an outcome as data;
- **apply:** outcome to machine value (wake, park, convert a retained request into a sent one, advance a cell).

Today's behavior is `apply ∘ observe`, unchanged, and the existing parity suites are the proof.

The gate: when the root has `:yin.k/custody`, the engine never observes for that task or its install children. An immediate effect parks as if the outcome were `full` or `blocked`. `check-wait-set` skips gated entries, as it already skips link and FFI-response entries. The driver observes, then calls the public `apply`.

This covers every pending path the brief names: `:stream/put`, `:stream/next`, the FFI request append and its response routing, the link request and its response, and the stepping of install children's entries.

Immediate effects with no wait variant:
- **`:stream/poll` and `:stream/cursor`** are observations. Under custody they are held for one driver step, recorded as `:yin.k/read` inputs (a poll's `blocked` and a minted cursor are the observed values), then applied.
- **`:stream/close`** on an enrolled target answers the shaped refusal value; closing a target is the authority's act. On an unenrolled stream the driver performs it.
- **`:stream/make`** creates a task-local stream; its class is the composition's declared default (1.5).
- **Lift waits for held immediates.** A task with a held poll or cursor entry is not at a liftable safepoint; export refuses `:yin.k/non-portable`, kind `:reason-mismatch`, until the driver has applied it. No pending variant is added to the wire.

### 1.3 The fenced writer

Pure functions over the machine value and data; the driver does the appends.

1. **Assign.** For a retained write whose target is enrolled and which has no id: take `n`, store `{:yin.k/occurrence P :yin.k/seq n}` on the entry, set the counter to `n+1`. One step, before any request is sent. At 2^52-1 nothing is assigned and nothing sent.
2. **Send.** Build the five-key envelope from the entry and the custody map, and append an `:yin.k/admit` request to the holder's inbound stream. The request id is derived from the lease and the op id, so a retry is the same request.
3. **Retain.** The id stays through a `full` inbound stream, an append of unknown effect, no outcome yet, and `:suspended`.
4. **Discharge.** Only an outcome that passes `front/reply-evidence`, and matches the op id and the incarnation, counts. It is read from the reply stream or from the outcome projection at a kept cursor.
   - `:committed` and `:replayed`: apply the recorded result to the entry. `ok` wakes a `:put`, turns an `:ffi-request` into a sent `:ffi`, and a `:link-request` into a `:link-response`. A recorded `closed` applies as `closed`.
   - `:suspended`: nothing; retry.
   - `:stale` and `:intent-conflict`: the run ends. No further fenced emission, no successor. On a conflict the driver still releases, so the judge does not wait out silence.
5. **A duplicate outcome** for a discharged wait is skipped.

A diagnostic is never an outcome. An authority that cannot be reached yields no outcome; the wait stays and the driver retries.

### 1.4 The recorded reader

- **Live:** observe the handle, send `:yin.k/input` with the source and the observed outcome (value, successor cursor, `end`, or `gap` with its successor), and apply only after `:recorded` or `:replayed`.
- **Replay:** while `k` is below the frontier the driver observes nothing live. It takes record `k` and applies it to the waiting entry whose source it names. If the task is quiescent and no entry matches, the run ends as a replay divergence and the driver releases.
- **Sources:** a read is named by stream identity and cell; an FFI result by call id; a link result by link id. Because replay is directed by source, the order across root and children is the recorded order, whatever order the driver would have polled.
- **End of prefix:** at `k` equal to the frontier, live observation resumes and is recorded from there.

### 1.5 Protection classes

The composition declares, per stream identity, one of:

- `:enrolled`: writes go through the fenced writer.
- `:at-least-once`: the driver appends bare, with no id; a regrant may repeat it.
- `:fail-stop`: a write under custody ends the run.

An undeclared stream refuses activation and export as `:yin.k/unsatisfied` naming the stream. Enrollment is read from the arbitration ledger by both ends; a declaration of `:enrolled` the ledger does not confirm is the same refusal. Reads are recorded whatever the class.

### 1.6 The version-1 wire

- **Bytes and address:** `jing/canonical-bytes` of the body; the address is the segment address of those bytes.
- **Reader:** `handoff-version` becomes the set `#{0 1}`. Validation runs `checkpoint/inspect` first (one implementation of the custody grammar), then the restoration grammar: code, cells, registers, clause 5.
- **Lift:** adds the custody header, carries each id on its pending, carries phase and parent on install entries, and refuses `:unprotected-pending` and `:op-seq-exhausted`.
- **Lower:** restores the counter exactly, installs the custody map only from a grant with valid binding evidence, and checks protection in both directions.
- **Determinism:** lift is a pure function of the export record. Cell ids and value-table order come from a fixed traversal, so two lifts of one record, on any host, give the same bytes and so the same baseline digests.
- **Version 0 is frozen.** Its three defects are fixed as version-0 behavior with version-0 tests.

### 1.7 The handoff driver

One explicit step state, plain data, stepped by the composition. It reaches the authority only through the front's streams, in process or remote.

**Source (first export, or a holder at its next safepoint):**
1. Enter exporting (7.7.4): require an empty ready queue, move the wait set and reachable parked records into the export record, refuse local resume.
2. Mint the occurrence once and persist it.
3. Lift, append code and body to the carrier, then send the offer.
4. A holder exiting then sends the resumed report and the release. A halt sends its result body the same way.
5. Retry each phase from retained progress; never mint a second occurrence for one park.

**Abort** is legal before any offer request was appended, or on an authority-attributed refusal of the offer. Otherwise the source stays fenced and resends the offer, which is idempotent; after an admitted offer the only way back is a grant to itself.

**Candidate:**
1. Fetch the bytes; validate fully with zero side effects.
2. Check the authority attachment; missing is `:yin.k/unsatisfied`.
3. Propose. Unanswered is `:yin.k/awaiting-grant`; a grant to another is `:yin.k/not-holder`.
4. Accept only a grant with `binding-evidence`. An invalid binding is `:yin.k/not-holder`, followed by a release.
5. Read the frontier and inputs, lower into a private task, run under the gate.
6. Any failure after the grant releases first; a pending release is retried.

**Liveness:** the driver renews through `dao.lease`'s holder functions on its own tick stream, and stops all IO at the bound.

**Progress:** the driver appends its progress records (occurrence, publication, offer, proposal, grant, release) to a `dao.stream.journal` the composition supplies, and reopens by folding it.

### 1.8 Composition and the REPL

- `yin.vm.ucf.compose` holds plain functions: an authority side (open, judge step, one front step per holder, target and outcome readers) and a holder side (the driver step). Nothing in it requires `yin.repl`.
- Exclusive is offered only when `authority/exclusive-capable?` holds for the composition's required failure model. Otherwise the composition offers fork and labels it fork.
- The carrier for bodies and code is the node's existing content store and the landed staged module load. No second loader; the DHT node stays the only DHT step owner.
- The REPL wiring is one more step inside `yin.repl.main/step-all`, after the shell step, plus shell commands to export, resume and show custody state.

## 2. Slices

All code is `.cljc`. Each slice runs JVM during iteration and the three lanes once at landing. Paths are under `src/cljc/` unless they begin with `test/`.

| Slice | Scope | Files | 7.11.1 evidence | Depends on |
|---|---|---|---|---|
| D1 | The three version-0 defects, each with a version-0 test | `yin/vm/ucf/handoff.cljc`; `test/yin/vm/ucf/handoff_test.cljc` | 5 (version-0 half) | none |
| D2 | Engine observe/apply split and the custody gate | `yin/vm/engine.cljc`; new `test/yin/vm/engine_gate_test.cljc` | substrate | none |
| D3 | Version-1 reader: gate, header, carried ids, clause 5, inspector as first stage | `handoff.cljc`; `test/yin/vm/ucf/handoff_v1_test.cljc` | 1, 2, 4 (structural), 5 | none |
| D4 | Version-1 lift: header, ids, phase and parent, the two new refusals, determinism, fixture regeneration | `handoff.cljc`; `test/yin/vm/ucf/checkpoint_fixtures.cljc`, `checkpoint-v1.txt` | 4 (first exclusive export), 9 (sequence at lift) | D1, D3 |
| D5 | Version-1 lower: counter restore, custody map, protection match | `handoff.cljc` | 3 (restoration), 4 (protection mismatch) | D2, D3 |
| D6 | Fenced writer | new `yin/vm/ucf/holder/writer.cljc` | 3 (assignment), 6 (writer), 8 (driver), 9 (sequence) | D2 |
| D7 | Recorded reader and replay | new `yin/vm/ucf/holder/reader.cljc` | 3 (delivery discipline) | D2 |
| D8 | Exporting state, export record, abort | new `yin/vm/ucf/holder/export.cljc` | 7.11 exporting-state blocker | D2 |
| D9 | Driver, candidate half | new `yin/vm/ucf/holder/driver.cljc` | 7 (holder) | D5, D6, D7 |
| D10 | Driver, source and exit half, progress journal | `holder/driver.cljc` | 14.2.4 rows 2 and 7 at driver level | D4, D8, D9 |
| D11 | Plain composition, protection declarations, exclusive gate; REPL wiring | new `yin/vm/ucf/compose.cljc`; `yin/repl.cljc`, `yin/repl/main.cljc` | 7.11 authority grounding and wiring | D10, C12 |
| D12 | Safepoint harness on both versions; 14.2.4 rows through the plain composition; the stage-D gate | tests | safepoint block, 14.2.4 | D11 |

**Order and concurrency.** D1, D2 and D3 start together. Then D4 and D5 (both edit `handoff.cljc`; run them in sequence, D5 first if D2 lands first). D6, D7 and D8 run concurrently after D2, in separate namespaces. Then D9, D10, D11, D12 in order.

### D1, version-0 defects

- A `:parked` body with frames restores those waits in order.
- `validate-body` requires an entry for every `:install` pending, `:yin.k/undecodable`.
- The returned address is the digest of the bytes export actually emits. The body's bytes do not change. The test asserts address and bytes agree on each host.

### D2, engine seam

- Split every observe from its apply; expose the applies.
- Add the gate and the held-immediate rule of 1.2.
- **Tests:** the full existing engine, FFI, link and handoff suites pass unchanged (no custody map means no behavior change). With a custody map, a task run against counting handles makes zero handle calls across put, next, poll, cursor, FFI call, link require, and an install child; each parks. Applying a supplied outcome produces the same machine value as the ungated path given that outcome.

### D3, version-1 reader

- Fixtures on canonical bytes, per clause: version 2, absent, float `1.0`, a version-0 child in a version-1 root; each missing header key, fork policy, nil occurrence, origin equal to self, header keys on a child, halted root and halted child forms; an id on `:next`, at or above the counter, duplicated, without an origin, naming the body's own occurrence.
- Clause 5 on both versions: `:park` and `:call-effect` reasons, missing install entry, phase outside `:running` and `:parked`, each phase with each child kind, a runnable child, phase and parent required under version 1, halted forbids frames.
- Assert zero attach calls, zero proposals and no machine on every refusal.
- A reader that speaks both lowers a version-0 body as a fork and refuses it when exclusive is required.

### D4, version-1 lift

- Header on blocked and parked roots, origin only on a halted root, none on children.
- `:unprotected-pending` for a first exclusive export over an attempted write to an enrolled stream; `:op-seq-exhausted` at 2^52-1.
- Two lifts of one export record give equal addresses; the accepted C4 fixtures are regenerated through this lift and their pinned addresses change in this commit. The refusal fixtures stay mutations of those bases.
- No envelope key appears anywhere in a body.

### D5, version-1 lower

- The counter is set to the carried value exactly; carried ids survive on `:put`, `:ffi-request` and `:link-request`.
- Protection mismatch in each direction is `:yin.k/unsatisfied` naming the stream.
- A restored child continues from its saved state without rerunning initialization or replaying its link request.
- Different receiver-local store bindings do not change resolution (14.2.4 row 8).

### D6, fenced writer

- Assign-before-send, one increment, children drawing from the root.
- Retention across `full`, unknown effect, no outcome, `:suspended`.
- Each of the five outcomes; a forged `:committed` and a forged `:intent-conflict` discharge nothing; an outcome for another incarnation is ignored; a duplicate is skipped.
- Unknown-effect transport error cut before and after the commit: one commit, or a replay of the result held.
- The nested envelope-shaped program value arrives as payload.

### D7, recorded reader

- No input is applied before its acknowledgment (counted).
- Regrant with a frontier of 3: three replays, zero live observations, then live.
- Evicted value: with records the old intent and result are reproduced; without them the run fails closed; a divergent intent at the same id is `:intent-conflict` (14.2.4 row 5).
- Root and child inputs interleaved; replay follows the recorded order.

### D8, exporting

- After entering exporting, polling the source, a direct resume, and ticking its install child append nothing (the blocked-writer hazard of 7.7.4).
- Abort reinstates waits and parked records exactly.
- A non-empty ready queue refuses `:yin.k/not-quiescent`.

### D9, candidate

- Two candidates over two encodings of one occurrence: one activates, the other gets `:yin.k/awaiting-grant` then `:yin.k/not-holder` (14.2.4 row 1, same host).
- A binding by another author, in another record, duplicated, or with a float epoch: `:yin.k/not-holder` and a release.
- Post-grant attachment failure releases, or retains release progress.
- The source itself resumes only after its own grant.

### D10, source and exit

- Each carrier and offer append failed in turn, unknown acceptance included; the driver reopened after each phase: one occurrence, retained waits and ids, no source effect, no second grant.
- Abort only before a proven unadmitted offer.
- Exit cuts: after the successor append, the resumed report, the release append and closure. The driver resends idempotently; it never publishes a second successor.
- A halt completes with a result body.

### D11, composition and REPL

- The plain composition runs a whole handoff in one process on the memory seam, with no `yin.repl` on the classpath of that test.
- Without an exclusive-capable authority it offers fork and says so.
- REPL: the custody step is inside `step-all`; no new thread, timer or step owner.

### D12, harness and gate

- Every corpus segment runs to each row of the 7.4.1 table, lifts and lowers into a fresh VM on both body versions, and matches the reference result and effect trace, including `lift(lower(frame))`.
- 14.2.4 rows 1 to 5, 7 and 8 through the plain composition, same host, three lanes. Row 6's driver logic (suspended retries, no inference from absent lapse) is unit-tested here; the partition itself is E.

## 3. Boundary with E

- **D owns:** everything above, tested in one process per host over the memory seam and the file-backed authority.
- **E owns:** the nine ordered host pairs through the wired composition; separate processes on JVM and Node and isolated runtimes on Dart; real partitions and process kills; clause 10 and the composition halves of clauses 3 and 8; the per-host durability record.
- **D hands E:** `yin.vm.ucf.compose` as the only entry point E drives, the regenerated byte fixtures, and the driver's progress-journal format.

## 4. Risks, riskiest first

1. **D2, the engine split.** It touches every IO path of a 2,254-line engine. A missed path is an unfenced effect. The zero-handle-call test over every effect kind is the control.
2. **D7, read gating cost and coverage.** Every read under custody becomes a durable authority transition. That is one fsync per input, and it may be too slow for chatty tasks. It is the price of the replay guarantee; say so rather than weaken it.
3. **D4, deterministic encoding.** If lift depends on map iteration order anywhere, variants conflict at the authority and hosts disagree on fixtures.
4. **D10, the abort rule.** An unknown offer append must leave the source fenced; a wrong early abort creates two runners.
5. **D11, REPL shape.** The UCF and linker text name `yin.repl.core`, which is gone. The wiring target above is my reading of the current tree, not of `yin-repl-design.md`.
6. **C12 not yet on master.** D11 cannot gate on `exclusive-capable?` until it lands.

## 5. Document amendments implementation will force (listed, not written)

Carried from the C4 rulings, still unplaced:
- **UCF 7.2.1:** name `jing/canonical-bytes` and the segment-address form; a halt with no origin is a version-0 result; the emitter is required in an origin.
- **UCF 7.2.1 version gate:** the statuses for a nested body (a version-0 child is `:yin.k/undecodable`; an unpublished child version is `:yin.k/profile-mismatch` with a path).
- **UCF 7.4.3:** the entry-for-every-install rule is also a custody-baseline rule.
- **UCF 7.7.8 *Snapshot variants*:** comparison is on digests of the carried encoding.
- **UCF 7.9:** `:yin.k/hash-mismatch` covers a body's own address.
- **Linker-dht 14.3:** the third version-0 defect.

New with D:
- **UCF 7.7.4:** the exporting state as built, and the abort rule in terms of offer evidence.
- **UCF 7.7.5 and 7.7.8:** the engine gate (the driver performs every effect of a task under custody); the three protection classes; held immediates (`poll`, `cursor`) as recorded reads; `close` on an enrolled target.
- **UCF 7.7.8 *Operation sequence state*:** the request id derived from lease and op id.
- **UCF 7.4.1 and 7.4.3:** a task with a held immediate is not at a liftable safepoint.
- **UCF 7.9:** the driver's behavior after `:intent-conflict` includes a release; replay divergence ends a run.
- **UCF 7.5.3:** the fixed traversal that makes lift deterministic.
- **UCF 7.11 and linker-dht 14.3 item 4:** replace `yin.repl.core` with the current composition root.
- **Linker-dht 14.2.2:** the driver's progress journal and its record kinds; source-directed replay.
- **`yin.vm.ucf-revisions.md` section 6:** version 1 moves from published design to implemented, slice by slice.
- **`yin.vm.semantic.md` or the engine's state doc:** the observe/apply split and the `:yin.k/custody` key on a machine value.
