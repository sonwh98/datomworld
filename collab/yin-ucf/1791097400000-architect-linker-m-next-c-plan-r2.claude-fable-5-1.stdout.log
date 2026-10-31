[33mWarning: no stdin data received in 3s, proceeding without it. If piping from a slow command, redirect stdin explicitly: < /dev/null to skip, or wait longer.[39m
Completed-GMT: 2026-10-04 06:53:39 GMT
Completed-Local: 2026-10-04 13:53:39 +07

**Summary: the substrate stands (a `dao.space` transactor over a durable journal stream, enrolled targets as ledger projections), corrected on every point astra raised; 12 slices, C1 to C12.**

Read-only; no suites run and no repo file edited. A skeleton of this plan is saved at `/Users/sto/.claude/plans/read-users-sto-workspace-datomworld-coll-lucky-willow.md`. The system clock reads earlier than the brief's Created-GMT; the header above is the clock's value.

## 0. Review claims, checked against the tree

I accept every claim. None is rebutted in substance; two get a note.

| Claim | Evidence | Ruling |
|---|---|---|
| The journal is an amendment, not already permitted | `dao.space.transactor.md` 110-140 (T18 allows a future durable log) and 142-164 ("a durable *stream* transport is not the answer") | Accept; exception text in 1.1 |
| The transactor lock does not serialize a decision | `transactor.cljc` 108-114, 244-254: the lock covers `t` allocation and the append only | Accept; 1.2 |
| The reclaim callback is expected to act first | `lease.cljc` 1382-1413: mark pending, call `reclaim`, then append `:lapsed`, leave the ledger only on ok | Accept; 1.3. Note: the lease already leaves the judge ledger only after the append answers ok, so the adapter needs a contract, not a judge change. |
| Poison must cover the whole authority | Round 1 poisoned only `admit!` | Accept; 1.4 |
| Rebuild the judge, not just epochs | `lease.cljc` 699-718: `:seen`, `:answered`, `:ledger`, `:queue` are in-memory only | Accept; 1.5, owned by C6 |
| Jing file puts deduplicate | `jing/file.cljc` 356-362: an address already present answers `:present` and writes nothing | Accept; 1.6. Note: transaction records carry a unique `t`, so round 1 would not have lost one, but a general transport must not depend on that. |
| Attribute order is host-dependent | `transactor.cljc` 56-60: "unspecified across platforms" | Accept; 1.7 |
| `exclusive-capable?` hides the failure model | `store/fs.cljc` 35-38: Dart cannot sync a directory; 21-29: Node lock has a pid-reuse case | Accept; 1.8 |
| C2 must not admit variants before a baseline exists | Round 1 put baseline derivation in C6, after offers | Accept; new slice C4 precedes offers |
| C5 needs a verified successor | Round 1 asked only for a "readable valid body" | Accept; C8 |
| C7 needs a frontier and acknowledgment | Round 1 had dense `k` only | Accept; C10 |
| Remote admission protocol belongs to C | Round 1 excluded the front without assigning it | Accept; C11 |

One correction of my own: round 1 had an unreachable authority answer `:suspended`. An outcome counts only when attributed to the authority (UCF 7.9), so an authority that cannot be reached produces no outcome at all. The wait stays undischarged and the holder retries. `:suspended` is authored only by a reachable authority that cannot decide.

## 1. The substrate

### 1.1 The transactor exception (text to publish)

> An arbitration ledger may use a durable local stream as its sole authoritative transaction history. Its memory projection and any published indexes are derived views, not separately committed authorities. This exception applies to arbitration ledgers only; it does not change the ordinary publication pipeline's durability contract, where the durable record remains what publication puts in `dao.jing`.

The accepted-checkpoint content file is not a second authority. Immutable content is written before the ledger references it. An orphan blob is harmless; a ledger reference to missing content is a defect the authority reports as `:suspended`.

### 1.2 Whole-decision serialization

Every authority change goes through one function, `authority/transition!`, under one lock (a JVM `locking`; Node and Dart are synchronous in one isolate). Inside the lock, in order:

1. Refuse if poisoned.
2. Read the current projection.
3. Decide purely: tenure, scope, dedup, bounds.
4. Build the transaction's datoms.
5. Call `transactor/transact!` once.
6. Fold the committed record into the projection.
7. Return the reply.

Grants, refusals, lapses, completion, quarantine, enrollment, target close, input recording and admission all use it. A whole `judge-step` pass runs inside the same lock, so its `:answer` hook and its writer see one projection.

### 1.3 The reclaim adapter (a composition contract)

`judge-step` is unchanged. The composition supplies:

- **`:reclaim`:** a readiness check. It reports true only when the authority is unpoisoned and the ledger writable. It revokes nothing.
- **`:writer`:** a ledger adapter. An appended `:lapsed` becomes one transaction holding the lapse and the epoch increment (or the completion). That transaction is the revocation's linearization point.
- **Contract:** no state claiming a successful revocation leaves the authority before that transaction commits. If the writer answers non-ok, the lease stays pending in the judge and the projection is unchanged. No reader of the ledger can see a lapse without its epoch change.

This is stated in the docs as a composition of `dao.lease`, with its own tests, not as what the callback already means.

### 1.4 Whole-authority poison

An uncertain durable append, or any failure between persistence and projection install, poisons the authority value. A poisoned authority:

- grants nothing, reclaims nothing, completes nothing, records no input, admits no effect;
- serves no projection as authoritative (target readers and outcome readers answer `:dao.stream/transport-error`);
- answers `:suspended` to admission requests.

Only reopen clears it, by reconciling the persisted frames first.

### 1.5 Reopen

With admission disabled:

1. Take the lock on the directory.
2. Replay the journal; drop a torn tail; require the header and dense positions, else refuse to open.
3. Rebuild the memory log; `transactor/create!` derives the next `t`; require `t` equal to position.
4. Fold the projection.
5. Rebuild the judge from the ledger: `:seen` from every recorded grant, release and lapse; `:answered` from every recorded grant and refusal with its proposer and proposal id; an empty `:ledger` and `:queue`.
6. Reclaim every tenure the ledger shows live (epoch + 1, cause `:policy`), each as one transaction.
7. Enable service.

An old `:accepted` still on a medium reads as inadmissible. An old proposal already answered gets no second grant. Nothing is regranted automatically; `dao.lease/restart` is not used.

### 1.6 The journal transport

`dao.stream.journal`, a general complete-retention DaoStream.

- **Frames:** frame 0 is a header `{:dao.stream.journal/header {:version 1 :identity i}}`. Every later frame is `{:dao.stream.journal/position p :dao.stream.journal/value v}` in canonical CBOR, with `p` dense from 0. Two equal values at different positions are different frames, so repeated equal appends are preserved.
- **Identity:** persisted in the header; the same after reopen.
- **Cursors:** positions are the frame positions and are stable across reopen.
- **`append!`:** encode, write one durable frame, then make it visible. A value outside the portable domain answers `:dao.stream/invalid-value` and writes nothing. A backend failure or throw answers `:dao.stream/transport-error` and poisons the handle for appends.
- **Excluded outcomes:** `full` and `gap`.
- **Bound:** position at most 2^52-1; at the bound, appends answer `:dao.stream/full` is not used, the handle refuses with `transport-error` and the authority poisons.
- **Backends:** `memory` (a frame vector the caller holds, with crash cuts) and `file` (C2).

### 1.7 Deterministic transactions and bounds

- The authority passes datom vectors `[e a v]`, never entity maps. Order is fixed: facts in decision order, and within a fact the attributes in a published per-kind order.
- Entity ids are allocated from a counter derived from the ledger.
- Transaction time, entity ids, journal positions, target positions, input sequence, epoch and operation sequence are exact integers no greater than 2^52-1. A transition that would pass a bound commits nothing and answers `:suspended`. Nothing wraps.
- Result: one ledger has the same bytes on every host, which C12's cross-host fixture relies on.

### 1.8 Durability declaration

`authority/durability` returns data, not a boolean:

- backend (`:file` or `:memory`);
- failure model survived (`:process-crash` or `:power-loss`);
- lock kind (`:os-lock` on JVM and Dart, `:claim-file` on Node with its pid-reuse refusal);
- identity and content references persisted.

`exclusive-capable?` compares that declaration with the failure model the composition requires. Dart file backends declare `:process-crash`. Memory backends are never capable.

### 1.9 The exactly-once guarantee

> Exactly-once commitment applies to insertion into the enrolled ledger-projection stream. It does not extend automatically to effects performed by downstream readers.

Forwarding from that stream to an existing FFI or link service is at-least-once or fail-stop unless that service supplies its own transactional admission. This is the first realization of an enrolled boundary, not a rule: a future target that joins the same atomic resource may conform without being newly minted.

### 1.10 The target stream contract

- **Identity:** derived from the arbitration identity and the enrollment transaction's `t`. Stable across reopen and never reused.
- **Positions:** the dense count of committed appends to that target, behind opaque cursors that survive the canonical codec. Stable across reopen because they are derived from ledger order.
- **Ordering:** ledger `t` order; one effect per transaction.
- **Retention:** complete, the ledger's own. No `gap`, no `full`; `blocked` at the tail; `end` after an authority-authored close.
- **Surfaces:** reader only. No handle has a writer surface; admission is the only append.
- **Receipt:** the recorded result is exactly `{:dao.stream/outcome :dao.stream/ok}`. After a close, later admissions record `{:dao.stream/outcome :dao.stream/closed}` as a terminal result.

### 1.11 Unchanged from round 1

- No privileged node: an authority is per arbitration space, named in the value, opened by whichever peer mints the occurrences.
- Browser CLJS has no file backend and offers fork only.
- Rejected alternatives: a standalone file log, lease facts plus a second store, publication as commit, a DHT quorum, `prepare-tx` with `:db/unique`.

## 2. Slices

All new code is `.cljc` and requires no `yin.vm` engine namespace. Paths are under `src/cljc/` unless they begin with `test/`. Every slice's tests run on JVM, Node and Dart over the memory backend unless stated.

| Slice | Scope | Files | 7.11.1 | Depends on |
|---|---|---|---|---|
| C1 | Journal grammar, memory backend, poison, reopen | new `dao/stream/journal.cljc`; `test/dao/stream/journal_test.cljc` | substrate | none |
| C2 | File backend, identity and position persistence, lock, crash recovery, durability declaration | new `dao/stream/journal/file.cljc`; test beside it | substrate | C1 |
| C3 | Authority core: `transition!`, fold, bounds, poison, enrollment, target projection, internal dedup seam | new `yin/vm/ucf/ledger.cljc`, `yin/vm/ucf/authority.cljc`, `yin/vm/ucf/authority/seam.cljc`; tests | substrate | C1 |
| C4 | Pure checkpoint inspector and shared canonical fixtures | new `yin/vm/ucf/checkpoint.cljc`; test and fixture bytes | 4 (baseline) | none |
| C5 | Custody facts, occurrence form, offer admission with baseline, checkpoint content store, grant + binding, `binding-evidence` | new `yin/vm/ucf/custody.cljc`, `yin/vm/ucf/authority/grant.cljc` | 7 (first grant, invalid bindings), 8 (variant refused) | C3, C4 |
| C6 | Lapse + epoch, reclaim adapter contract, judge reconstruction, reopen reclaim, exhaustion | `authority/grant.cljc`, `ledger.cljc` | 7 (reclaim, reopen), 9 (epoch) | C5 |
| C7 | Admission order, five outcomes, diagnostics, quarantine, cross-target, outcome projection and redelivery | new `yin/vm/ucf/authority/admission.cljc` | 6 (consumer), 8 (consumer) | C6 |
| C8 | Completion with verified successor, closure, eligibility, orphans | new `yin/vm/ucf/authority/completion.cljc` | 4 (ancestry, orphan), 9 (completion blocked) | C6 |
| C9 | Inherited-id scope, child ids, unreadable checkpoint | `admission.cljc` | 4 (membership), 8 (inherited, unreadable) | C7, C8 |
| C10 | Input protocol | new `yin/vm/ucf/authority/input.cljc` | 3 (durable input replay) | C6 |
| C11 | Remote admission protocol and attribution contracts | new `yin/vm/ucf/authority/front.cljc` | 6 (unauthenticated), 8 (forged outcome) | C7, C10 |
| C12 | Substrate crash-cut gate, cross-host ledger fixture | tests | re-runs 6 to 9 on the file backend | all |

**Order and concurrency.** C4 runs alongside C1 to C3. C2 runs alongside C3. Then C5, then C6. C7, C8 and C10 run concurrently (separate namespaces; each adds one arm to the `ledger.cljc` fold). Then C9 and C11, then C12.

### C1, journal

- **Tests:** repeated equal appends keep separate positions; identity and positions survive reopen; a non-portable value is refused without a write.
- **Cuts:** before the frame (nothing persisted); after the frame and before visibility (reopen shows it); a torn frame (dropped at reopen). Each leaves the handle poisoned until reopen.
- **Refusals:** a missing header or a position gap refuses the open.

### C2, file backend

- **Mechanism:** each journal frame is one `dao.jing.file` content frame; replay order comes from `dao.jing.file/records`; the directory lock is `dao.space.store.fs/lock!`. No new framing or fsync code. `records` is documented today as a test view, so its docstring gains this use.
- **A `:present` answer from a put** means a frame with those bytes already exists; the backend treats it as a defect and poisons.
- **Tests on three hosts:** drop the handle without close and reopen the path; a hand-torn tail; a second opener is refused.
- **JVM only:** a subprocess killed mid-append. Node and Dart are covered by the in-process cuts and the torn-tail test; real kills on those hosts are E.
- **Delivers** the durability declaration of 1.8.

### C3, authority core

- `transition!` as in 1.2, the fold, the bounds of 1.7, poison as in 1.4.
- **Enrollment:** `{:yin.k/custody :yin.k/enrolled :yin.k/target i :yin.k/effect-kinds #{:yin.k/append}}`, authority-authored, add-only.
- **Target reader:** the full contract of 1.10, including positions stable across reopen and reader-only surface.
- **Internal seam:** `authority.seam/commit-effect!` commits effect, result and dedup record with no binding or tenure check. It is a public var only because ClojureDart cannot reach private vars; it is documented as a substrate test seam and is not an admission entry point. `admit!` does not exist until C7.
- **Tests:** crash at each cut gives zero or one commit; a retry after reopen finds the recorded result; two decisions cannot both act on one projection (JVM threads).

### C4, checkpoint inspector

- **Pure functions over bytes and a decoded body:** address check, tag, version gate on the integer kind, kind, root or child role, custody header rules, operation-id rules on `:put`, `:ffi-request` and `:link-request`, recursion into install children with the root's context.
- **Output:** `{occurrence, origin, arbitration, next-op-seq, ops {op-id encoded-intent}}`, the operation baseline.
- **Not checked:** code hashes, cells, registers. Those are restoration validity and belong to D. A body that passes here but cannot be restored harms only its own occurrence.
- **Shared with D:** D's version-1 `validate-body` calls this namespace and adds restoration. The canonical fixtures land here and D must pass the same bytes.

### C5, offers, grants, bindings

- **Occurrence id:** a UUID string minted once at park, compared by canonical bytes.
- **Offer admission:** inspect the body (C4), store it in `content.jing`, then commit the offer with its baseline. A duplicate is idempotent by occurrence and snapshot address. A variant with a different baseline, or a known occurrence with a different origin, is refused. An uninspectable body is never admitted.
- **Grant:** one transaction holds the `:dao.lease/accepted` fact (with its proposal id and holder) and the `:yin.k/bound` fact.
- **`binding-evidence`:** pure, over records read from the authority-attributed stream. Grant and binding must be in one transaction record; identity is `{arbitration identity, t}`.
- **Tests:** two candidates, one grant; a binding by another author, in another record, duplicated, or with a float epoch establishes nothing; the evidence survives canonical CBOR and a `dao.stream.remote` reflection on each host.

### C6, reclaim and reconstruction

- Epoch rules of UCF 7.7.8; the adapter contract of 1.3 with its two tests.
- Reopen steps 5 and 6 of 1.5, tested with an old `:accepted` and an old proposal left on a medium.
- At 2^52-1: the grant is valid; the next reclaim records the lapse, leaves the epoch, exhausts the occurrence. 2^52 and -1 are refused on the bytes.

### C7, admission and result delivery

- **Order:** authority, binding, tenure, scope (current-occurrence ids), dedup. Stale wins over a record.
- **Conflict:** `:intent-conflict` commits the quarantine fact. A second target with the same id conflicts through the one namespace.
- **Diagnostics:** a defective envelope commits nothing and appends one diagnostic. A failed diagnostic append is returned as data and admits nothing.
- **Result delivery:** every committed record yields its `:committed` outcome on an outcome projection of the ledger, a reader-only stream like a target. It is attributed because it is derived from the authority's ledger. A driver reads it with a kept cursor, so a crash after commitment needs no reply route. `:replayed`, `:stale` and `:suspended` are direct replies; a lost one is recovered by retrying.
- **Tests:** one fixture per check in order; redelivery of a committed result after reopen; commit-before-reclaim or refusal-after, never both.

### C8, completion

- A release completes only with resumed evidence from the holder and a successor that the inspector verifies: bytes match the address, version 1, root role, origin naming this predecessor and lease, the same arbitration identity, an occurrence the ledger has never seen, and a baseline that continues the predecessor's counter.
- Completion closes the occurrence and records one edge in the lapse's transaction. Otherwise the release is a plain reclaim and the occurrence returns to offered.
- An exhausted occurrence accepts no completion.
- **Crash cuts:** after the successor append, the resumed report, the release append, and closure.

### C9, inherited ids

- Scope check for inherited ids: membership in the granted checkpoint's baseline (children included) and ancestry through completion edges. Both are derived; nothing is indexed.
- An unreadable accepted checkpoint answers `:suspended` after the tenure check and changes nothing. A stale holder is still `:stale`.

### C10, input protocol

- **Request:** `{occurrence, lease, epoch, k, source, observed}`, attributed to the holder. `source` names the stream or call and the kind (read, FFI result, link result). `observed` is the outcome as data, gap successors included.
- **Acknowledgment:** `:recorded` only after the durable commit. Equal content at a known `k` answers `:replayed`; different content is refused; a stale tenure is `:stale`.
- **Rule for D:** the driver delivers an input to the task only after `:recorded` or `:replayed`.
- **Ordering:** one sequence per root occurrence. Install children draw from the root's sequence, in the order the driver delivered inputs.
- **Frontier:** the count of input records for the occurrence at the grant's transaction. A regranted holder replays records 0 to frontier - 1 in order, checks each source, and fails closed on a mismatch. At the frontier the prefix has ended and live observation resumes, recording from there.
- **Tests:** a scripted holder made of plain functions; recovery with inputs, without them, and with a divergent intent.

### C11, remote admission protocol

- **Shape:** request values on a per-holder inbound stream, replies on a per-holder reply stream, one bounded `front/step` function. No apply or transport vocabulary appears in it.
- **Requests:** offer, proposal carriage, resumed report, release, input record, admit.
- **Attribution in:** the composition's resolver over the inbound source. A request with no resolved author yields a `:wrong-author` diagnostic.
- **Attribution out:** replies and the outcome projection are authority-authored media. A forged `:committed` or `:intent-conflict` from another author discharges nothing; C tests the resolver rule, D the driver.
- **Not here:** the driver that correlates and discharges waits (D), and partitions and kills through the composition (E).

### C12, the gate

- For every transition and every cut, the reopened projection equals the pre-state or the post-state, and a retry converges. This proves the substrate; it does not close clause 10.
- A ledger file written on the JVM, checked in, reopens to the same projection on Node and Dart.

## 3. Boundary with D and E

- **C owns:** authority transitions, durable input and result records, binding evidence, enrollment, the checkpoint inspector, the remote admission protocol, and its own three-host authority and consumer seam tests.
- **D owns:** sequence assignment, retained pending state, the fenced writer, version-1 restoration, input delivery discipline, outcome correlation and wait discharge, routing writes to enrolled streams.
- **E owns:** the wired holder and candidate host matrices, and partition and crash execution through the composition.

## 4. Risks, riskiest first

1. **C8, completion through the judge.** The release path crosses the judge, the adapter and successor validation in one transaction.
2. **C4, fixture agreement with D.** If D's grammar drifts from the inspector, offers are refused. The shared fixtures are the control.
3. **C10, record-before-use.** The authority cannot see dependence; a driver that uses an unrecorded input is only caught later, by an intent conflict.
4. **C2, per-host durability.** Dart's missing directory sync and Node's pid-reuse lock are declared, not hidden.
5. **Cost.** One fsync per transition, O(history) reopen, the ledger in memory.

The cheapest substrate proof is C1 plus C3 on the memory backend.

## 5. Document amendments implementation will force (listed, not written)

- `dao.space.transactor.md`: the exception text of 1.1, in *Where durability lives* and T18.
- New `dao.stream.journal.md`: frame grammar, positions, identity, poison, excluded outcomes.
- `dao.jing.file` docstring: `records` as the journal's replay view.
- `dao.lease.md` or its rationale: the reclaim adapter contract of 1.3.
- UCF 7.7.5 and 7.7.8: the guarantee wording of 1.9; the target stream contract; the enrollment schema; that newly minted targets are one realization.
- UCF 7.7.8 binding evidence: transaction identity `{arbitration identity, t}`.
- UCF 7.2.1: the occurrence form.
- UCF 7.7.2: the authority-authored facts (enrolled, quarantined, completed, input, admitted offer, target close).
- UCF 7.7.7 and 7.7.8 *Restart*: reopen as in 1.5.
- UCF 7.9: `:suspended` is authority-authored only; an unreachable authority yields no outcome; the outcome projection.
- UCF 7.4.3 and 7.2.1: point to the shared inspector for custody grammar.
- Linker-dht 14.2.2: the input protocol, frontier and child ordering; 14.2.4 row 6 reworded for the remote front; 14.3: C owns the admission protocol, and the durability declaration replaces a bare capability claim.
