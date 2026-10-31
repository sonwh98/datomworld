Completed-GMT: 2026-10-04 06:48:53 GMT
Completed-Local: 2026-10-04 13:48:53 +07

**Summary: the arbitration space is a `dao.space` transactor over a new durable, complete-retention journal stream, with enrolled targets as projections of that same ledger; 8 slices, C1 to C8.**

Read-only; no suites run and no repo file edited. The same text is saved at `/Users/sto/.claude/plans/read-users-sto-workspace-datomworld-coll-lucky-willow.md`, because the session was in plan mode.

## 1. The substrate

### What exists today (verified in the tree)

- **Transactor:** `dao.space.transactor/transact!` writes exactly one record `{:dao.space/transaction {:t n :datoms [...]}}` through one local append (T1) and advances `t` only on `:dao.stream/ok` (T5). Its local stream is `dao.stream.memory-log`: complete retention, process lifetime, a new identity per process.
- **Durable frames:** `dao.jing.file/create-content-file` is a framed append-only file with an fsync per put and torn-tail truncation at open, on JVM, Node and Dart. `dao.jing.file/records` returns every validated frame in order.
- **Lock:** `dao.space.store.fs` gives an exclusive directory lock on the three hosts. Dart cannot fsync a directory.
- **Lease judge:** `dao.lease/judge-step` commits a grant or a `:lapsed` only when its `:writer` answers ok. `make-judge` already demands `:durable-judge`, `:incarnation-rule` and `:fencing` for `:durable? true`.
- **No custody code yet:** a grep for occurrence, custody and lease in `src/cljc/yin/vm/ucf/` and `yin/repl/` finds only docstrings. `handoff-version` is 0.

### Recommendation

**The authority is a `dao.space` transactor whose local stream is a durable journal, plus one pure interpreter over that stream.**

- **Journal (`dao.stream.journal`, new transport).** A complete-retention DaoStream. `append!` canonical-CBOR-encodes the value, writes it as one durable frame, and only then makes it visible through an inner `memory-log`. Frame 0 persists the stream identity, so the identity in `:yin.k/arbitration` survives restart. The backend is a plain-data seam with two implementations:
  - `memory`: a byte-frame vector the caller holds, with named crash cuts. This is the portable transactional seam 14.2.4 demands; it runs on all three hosts.
  - `file`: a `dao.jing.file` content file in a directory locked by `dao.space.store.fs/lock!`. Each transaction record is one content frame. No new framing or fsync code is written.
- **Atomic admission.** One transition is one pure decision over the projection, then exactly one `transactor/transact!`, so one frame. Effect, result and dedup record are datoms of that one record. Durability precedes visibility. Any non-ok or thrown durable write poisons the handle, and every later admission answers `:suspended` until reopen.
- **The effect is inside the boundary.** An enrolled target is a stream minted by the authority at enrollment and read as a projection of the ledger: the committed `:yin.k/append` values for that target identity, in `t` order. This is the only way "effect, result and dedup record commit together" holds without a two-phase protocol. Anything downstream of the projection is at-least-once and says so.
- **Lease judge unchanged.** `judge-step` runs as is. Its `:writer` is a ledger adapter:
  - an `:accepted` becomes one transaction holding the grant and its `:yin.k/bound` fact;
  - a `:lapsed` becomes one transaction holding the lapse and the epoch increment, or the completion;
  - `:reclaim` reports whether the ledger is writable, so the act and the record are the same transaction.
- **Reopen, per host.**
  1. Take the directory lock.
  2. Replay validated frames, with the torn tail truncated.
  3. Require contiguous `t` from 0, or refuse to open.
  4. Rebuild the memory-log; `transactor/create!` derives the next `t`.
  5. Fold the projection.
  6. Reclaim every live tenure (epoch + 1, cause `:policy`) before any grant or admission.
  
  Browser CLJS has no file backend, so it is process-scoped and offers fork only.
- **No privileged node.** An authority is per arbitration space and is named in the value. Any peer may open one for the occurrences it mints, and holders read it through an ordinary served stream. Nothing is global and there is no server role.
- **Cost.** One fsync per transition, O(history) replay at open (the transactor's existing open item), and the whole ledger held in memory.

### Alternatives rejected

| Candidate | Why rejected |
|---|---|
| File log with compare-and-append, outside dao.space | Violates UCF 7.7.2 and 7.7.3 (facts are datoms; the grantor is the transactor) and duplicates `t` allocation. Kept only as the transport under the transactor. |
| dao.lease facts plus a separate authority store | Two durable things: a grant and its binding cannot share a transaction, and the judge ledger is not rebuildable from a stream. |
| dao.space publication (covered index into dao.jing, HEAD rename as commit) | Rebuilds the index per admission; two fsyncs and a rename per commit; the rename is not durable on Dart; unpublished state is lost, which reuses an epoch. |
| DHT-replicated or quorum ledger | Availability is not authority; 14.3 rules it out. |
| `dao.space.transact/prepare-tx` with `:db/unique` for dedup | Throws instead of answering data, mints gensym tempids, and scans the base several times per transaction. |

### Owner question (does not block the plan)

Is it acceptable that an enrolled target is, by construction, a stream minted by and projected from the authority's ledger, so existing streams get exactly-once only through a forwarder that is at-least-once past the boundary?

Recommendation: yes. It is what UCF 7.7.8 already implies with "a consumer that cannot join that atomic resource".

## 2. Slices

All new code is `.cljc` and requires no `yin.vm` engine namespace. Tests are `.cljc` on the memory backend, so each runs on JVM, Node and Dart; file-backend and process-kill coverage is stated per slice. All paths are under `src/cljc/` unless they start with `test/`.

| Slice | Scope | Files | 7.11.1 clauses | Depends on |
|---|---|---|---|---|
| C1 | Journal transport with both backends; authority open, close and reopen; projection fold; `enroll!`; dedup-only `admit!`; enrolled-target reader | new `dao/stream/journal.cljc`, `yin/vm/ucf/ledger.cljc`, `yin/vm/ucf/authority.cljc`; tests `test/dao/stream/journal_test.cljc`, `test/yin/vm/ucf/authority_test.cljc` | 8 (equal intent, different intent, fresh commit) | none |
| C2 | Custody fact constructors and validators; occurrence id form; offer admission; judge writer adapter (grant and bound in one transaction); reader-side `binding-evidence` | new `yin/vm/ucf/custody.cljc`, `yin/vm/ucf/authority/grant.cljc`; tests beside them | 7 (first grant binds 0; invalid bindings) | C1 |
| C3 | Lapse and epoch in one transaction; reopen reclaims before grant; exhaustion at 2^52-1 | `authority/grant.cljc`, `ledger.cljc` | 7 (reclaim, reopen), 9 (epoch) | C2 |
| C4 | Full admission order; the five outcomes; defective-envelope diagnostics; quarantine; cross-target conflict; outcome and diagnostic delivery | new `yin/vm/ucf/authority/admission.cljc` | 6 (consumer), 8 (consumer, minus inherited ids and variants) | C3 |
| C5 | Resumed evidence; release as completion; closed occurrences; successor eligibility; orphans | new `yin/vm/ucf/authority/completion.cljc` | 4 (ancestry, orphan) | C3 |
| C6 | Accepted-checkpoint content store; `carried-ops`; scope check; unreadable checkpoint suspends; snapshot-variant baseline | `custody.cljc`, `admission.cljc`, `authority.cljc` | 4 (membership), 8 (inherited, unreadable, variant) | C4, C5 |
| C7 | Durable input records and ordered replay | new `yin/vm/ucf/authority/input.cljc` | 3 (durable input replay) | C3 |
| C8 | Generated crash-cut matrix over every transition; file-backend reopen on three hosts; cross-host ledger fixture; `exclusive-capable?` gate | tests, `authority.cljc` | re-runs 6 to 9 on the file backend | C1 to C7 |

Order: C1, C2, C3, then C4 and C5 (separate namespaces, may run concurrently), C7 (may run with them), C6, C8.

### C1, the thin vertical

This is also the cheapest proof of the substrate, end to end.

- **Flow:** open an authority, enroll one target, admit one envelope, crash at each cut, reopen.
- **Cuts:**
  - before the durable frame: zero commits, and a retry commits once;
  - after the frame and before visibility: reopen shows one commit, and a retry answers `:replayed` with the recorded result;
  - after visibility and before the outcome is returned: same.
- **Also asserted:** the journal identity and next `t` survive reopen; a torn tail is dropped; a gap in `t` refuses the open; a poisoned handle answers `:suspended`.
- **File backend:** the same test on JVM, Node and Dart, by dropping the handle without close and reopening the path, plus a hand-torn tail. A kill -9 of a real process is JVM-only here; otherwise it is E.
- **Size:** if production code passes about 400 lines, split the file backend into its own round.

### C2, attributed grant and binding

- **Fact shape:** facts are entity maps with an authority-allocated `:db/id`, one entity per fact.
- **Authorship:** holder-authored facts (proposal, release, renewal, offer, resumed) arrive on per-author inbound media and are admitted with the resolver's author recorded. The ledger stream has one writer, the transactor.
- **Occurrence id (Q9):** a UUID string minted once at park, compared by canonical bytes. The authority refuses a known occurrence offered with a different origin or operation baseline. A duplicate offer is idempotent by occurrence and snapshot address.
- **Reader-side evidence (Q7):** `binding-evidence` is a pure function over records read from the stream the resolver attributes to the authority. The grant and the binding must sit in the same transaction record, with the same lease, holder and subject, one binding per lease, and an integer epoch in range. The transaction identity is `{arbitration identity, t}` of that record.
- **Tests:**
  - two candidates, one grant;
  - a binding by another author, in a different record, duplicated, or with a float epoch establishes nothing;
  - the evidence survives canonical CBOR and a `dao.stream.remote` reflection on each host.

### C3, reclaim epochs

- Initial epoch is 0. Every lapse raises it by one in the lapse's transaction. A grant binds the current epoch. A successor starts at 0.
- Reopen recovers the epoch and raises it before granting; it is never reset.
- At 2^52-1 the grant is valid and its effects commit. The next reclaim records the lapse, leaves the epoch unchanged, and exhausts the occurrence.
- The values 2^52 and -1 are refused on the bytes.

### C4, admission

- **Order:** exactly as 7.7.8: authority, binding, tenure, scope, dedup. This slice handles current-occurrence ids only. Stale wins over an existing record.
- **Enrollment schema (Q3):** `{:yin.k/custody :yin.k/enrolled :yin.k/target i :yin.k/effect-kinds #{:yin.k/append}}`, authority-authored and add-only. The target identity is minted by the enrollment transaction, so a stream never exists unenrolled and protection cannot change under a retained id.
- **Dedup namespace:** one, keyed by op id, with target identity in the intent. A second target with the same id answers `:intent-conflict` and quarantines.
- **Conflict and defect:** an intent conflict commits one transaction, the quarantine fact. A defective envelope commits nothing and appends one diagnostic to the composition-supplied stream. A failed diagnostic append is returned as data and admits nothing.
- **Serialization:** admission and reclaim share one lock on the JVM. The test asserts commit-before-reclaim or refusal-after, never both.

### C5, completion

- A release closes the occurrence and records one edge, in the lapse's transaction, when it comes with accepted resumed evidence from the holder and a readable, valid successor body. A release without that returns the occurrence to offered through reclaim.
- The chain allows one successor per predecessor and one predecessor per successor, with the same arbitration identity and an origin naming the predecessor and its lease. A successor offer is admitted only after that completion.
- Crash cuts: after the successor append, after the resumed report, after the release append, after closure. Before closure no successor is eligible and the last checkpoint is regranted. After closure the occurrence never grants again.

### C6, inherited ids and variants

- The authority stores each admitted body in a `content.jing` beside the ledger, before the offer fact references it.
- `custody/carried-ops` reads a version-1 body map (frames and install children) and returns the retained op ids and their encoded intents. Membership and ancestry are derived at admission; no index is stored.
- An unreadable accepted checkpoint answers `:suspended` after the tenure check and changes nothing.
- A variant that keeps an id and changes its intent or the root counter is refused.

### C7, input records

- `record-input!` admits `{occurrence, lease, k, input}` under a tenure check, dense in `k` per occurrence. Equal content at a known `k` replays; different content is refused.
- `inputs` returns them in order for a regranted holder.
- The test uses a scripted holder made of plain functions. Recovery with inputs reproduces intent and result; without them the script fails closed; a divergent intent at the same id is `:intent-conflict`.

### C8, the gate

- For every transition and every cut, the reopened projection equals either the pre-state or the post-state, and a retry converges.
- A ledger file written on the JVM and checked in as a fixture reopens to the same projection on Node and Dart.
- `exclusive-capable?` is true only for a file-backed, lock-held, cleanly reopened authority.

## 3. Boundary with D and E

**C must not build:**
- the handoff driver;
- the fenced writer (sequence assign and retain, envelope construction);
- version-1 body validation in `handoff.cljc` (`handoff-version` stays 0);
- the REPL composition;
- a remote front for admission over `dao.stream.apply`;
- forwarders from enrolled streams to external IO;
- the host matrices and the crash/partition suite.

**C needs from D:**
- the version-1 body grammar, implemented against the same byte fixtures `carried-ops` reads;
- a driver that records each input before any effect that depends on it, and that persists the occurrence and export progress;
- holder-side `:yin.k/not-holder` and release on an invalid binding, using C's `binding-evidence`;
- routing of every protected write to an enrolled stream, or an `:unprotected-pending` refusal.

**C needs from E:** both host matrices and real process kills through the wired composition, and a per-host durability record.

Every C function is a plain function over an authority value and streams. None requires the REPL.

## 4. Risks, riskiest first

1. **C4 and C1: the effect lives in the ledger.** Programs' real targets (FFI call-out, link streams, string-backed streams) are not ledger projections. If the owner expects exactly-once into those, this design does not give it. This is the owner question above.
2. **C5: completion through the judge.** The reclaim hook only reports writability and the writer does the act. The judge's `:seen` and `:answered` must be rebuilt at reopen so an old `:accepted` cannot reseed tenure.
3. **C6: fixture drift against D.** C reads a body grammar D has not implemented yet. Shared canonical byte fixtures land in C6 and D must pass them.
4. **Dart durability.** With no directory fsync, a newly created ledger file is safe against process death but not proven against power loss. Record it per host and claim no more.
5. **Cost.** An fsync per admission and O(history) reopen. Acceptable for C; checkpointing the ledger is a later item.

## 5. Document text implementation will force (listed, not written)

- **`dao.space.transactor.md`, *Where durability lives* and T18:** admit the journal as a complete-retention durable local stream for authority ledgers, and scope "a durable stream transport is not the answer" to the code index.
- **New `dao.stream.journal.md`:** the transport's spec, excluded outcomes, poison rule and identity persistence.
- **UCF 7.7.8 *Enrollment*:** the schema, the add-only rule, and that an enrolled target is minted by and projected from the ledger.
- **UCF 7.7.8 binding evidence:** the transaction identity is the record `{arbitration identity, t}` on the authority-attributed stream.
- **UCF 7.2.1:** the concrete occurrence form.
- **UCF 7.7.2 fact table:** the authority-authored facts C adds (enrolled, quarantined, completed, input, admitted offer), and how holder facts carry their resolved author.
- **UCF 7.7.7 and 7.7.8 *Restart*:** reopen reclaims with cause `:policy` and does not regrant automatically; `dao.lease/restart` is not used.
- **UCF 7.9:** `:suspended` for a poisoned ledger, and where outcomes are delivered and how they are attributed.
- **Linker-dht 14.2.2:** the input record schema.
- **Linker-dht 14.2.4 row 6:** "partition the consumer from authority" means the remote admission front, which is D.
- **`dao.lease.md` or its rationale:** a composition whose reclaim act and `:lapsed` record are one transaction.
