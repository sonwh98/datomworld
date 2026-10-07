Completed-GMT: 2026-10-03 19:46:05 GMT
Completed-Local: 2026-10-04 02:46:05 +07

**ACCEPT-WITH-CHANGES — constrain inherited IDs to authoritative carried pendings; define cross-target dedup scope; require authenticated admission outcomes; resolve exhaustion precedence; make child validation context-sensitive; specify defective-envelope diagnostics; reconcile the outcome algebra; add version-0 regression obligations. Implementation should wait for these edits.**

### 1. Q1: retain occurrence-based IDs, but strengthen the ancestry rule

**Accept the principle, not the present scope check alone.** Checking tenure against the envelope’s binding correctly permits a successor to finish a predecessor’s pending operation. Changing IDs to a chain-root identifier would not fix the remaining authorization, dedup, or replay questions.

The current rule admits *any* ID naming an ancestor ([UCF §7.7.8, lines 1906–1915](/Users/sto/workspace/datomworld-ucf-b/docs/design/yin.vm.universal-continuation-format.md:1906)). Consequently, a current holder can submit an unrecorded ancestor ID that its checkpoint never carried; scope passes and the effect commits.

Amend scope as follows:

> An operation assigned during the current occurrence names that occurrence. An inherited operation must occur among the retained pending operations of the authoritative checkpoint granted to the holder, including its install children. Its occurrence must be an ancestor through authoritative completion records.

Derive that membership from the accepted checkpoint; do not persist a redundant ancestry or pending-ID index. Existing dedup intent comparison still decides whether a previously recorded operation is replayed or conflicts.

Require completion to establish a **single, acyclic successor chain**: one accepted successor per predecessor, one predecessor per successor, fresh successor occurrence, matching origin, and unchanged arbitration identity. An orphan report is not an edge.

The requested interleavings then resolve as follows:

| Case | Required result |
|---|---|
| Retained write retried under successor’s grant | Current successor tenure passes; carried ancestor ID passes scope; replay or first commit follows. |
| Two candidates race for successor | Only the granted candidate can commit; the loser cannot acquire tenure through the inherited ID. |
| Fenced source retains a pending write | Its old binding fails tenure before dedup, even if its ID has a result. |
| Two successor publications | Only the authority’s accepted completion creates an edge; the other publication remains orphaned. |
| Authority restarts | Recover bindings, completion edges, dedup/results and replay evidence; reclaim before regrant. Missing recoverable state means fail-stop. |
| Same ID, different intent | Current tenure and valid scope lead to `:intent-conflict`; stale tenure still wins first. |

**Dedup scope is a separate blocking ambiguity.** DHT §14.2.2 says each consumer owns records ([lines 3174–3178](/Users/sto/workspace/datomworld-ucf-b/docs/design/yin.vm.linker.dht.md:3174)). If target A and target B use independent dedup tables, replay divergence can send the same ID to B and commit again: B never sees A’s record.

Specify **one logical dedup namespace per arbitration admission resource across all enrolled targets participating in the guarantee**, keyed by the existing operation ID. Target identity belongs in intent, not in the dedup key. Consumers unable to participate in that atomic resource cannot claim this cross-target guarantee.

### 2. Exactly-once: the atomic boundary is right, but several edges need correction

The effect/result/dedup atomic transition at [UCF lines 1911–1915](/Users/sto/workspace/datomworld-ucf-b/docs/design/yin.vm.universal-continuation-format.md:1911) rules out a conforming “effect committed, result record absent” state. C must prove this with durable reopen—not simulate three independent writes.

With the strengthened scope and dedup contract:

- Crash before commitment permits a subsequent first commit.
- Crash after commitment but before delivery permits recorded-result redelivery.
- Unknown envelope acceptance retains the same ID; transport acceptance never discharges the wait.
- Regrant of the same checkpoint restores the same sequence and replays durable inputs.
- A successor retains pending IDs and continues the counter; it must not renumber inherited operations.
- Reclaim and admission must serialize against the same authority state: commit before reclaim or refusal after it.

Four concrete changes are necessary:

**Authenticate outcomes.** Section 7.9 currently specifies correlation by ID and incarnation but not trusted authorship. Anyone able to append a forged `:committed` could cause the driver to discard an uncommitted pending write. Require attribution to the enrolled consumer/admission authority for that target; correlation alone is insufficient.

**Separate definitive failure from uncertainty.** A terminal refusal may be recorded, but an unknown-effect `:dao.stream/transport-error` is not a definitive effect result. DaoStream explicitly permits such uncertainty ([dao.stream.md:599](/Users/sto/workspace/datomworld-ucf-b/docs/design/dao.stream.md:599)). Record a refusal only when the atomic admission boundary establishes that terminal result; retain/reconcile uncertain transport delivery.

**Resolve epoch exhaustion precedence.** Lines 1880–1883 say admissions become suspended, while line 1883 also says the lease check refuses the old holder. But authority-first admission at lines 1895–1898 returns `:suspended` before reaching tenure. Specify:

> Epoch value MAX is usable until reclaim would increment it. That reclaim ends tenure and permanently exhausts the occurrence. Thereafter admission returns `:suspended` before tenure checking; no effect commits.

Do not classify a valid grant at epoch MAX as already exhausted. Exhaustion is derivable from the terminal lapse/grant history.

**Align export exhaustion.** Sequence exhaustion explicitly refuses export; epoch exhaustion currently specifies only grants/admissions. State that an exhausted tenure cannot produce an eligible exclusive successor. Carrier bytes may remain historical evidence, but publication cannot reopen custody. Update DHT’s broad “overflow suspends admission/export” wording to distinguish these cases.

### 3. Consistency and dispatch

Several reconciliations are sound: flattened scheduler fields, a separate grant-binding fact, unchanged lease maps, body/code-stamp version separation, and explicit supersession of illustrative pending variants.

Three inconsistencies need direct editorial repair:

- **Outcome algebra:** §7.9 opens “Every UCF operation” dispatches on `:yin.k/status` ([line 1991](/Users/sto/workspace/datomworld-ucf-b/docs/design/yin.vm.universal-continuation-format.md:1991)). Narrow that to lift/lower operations and define admission outcomes as the second disjoint family. Admission values are neither new statuses nor `:yin.k/kind` values.
- **Nested stream result:** retaining an unchanged historical DaoStream map under `:yin.k/effect-result` is appropriate. Explicitly scope the earlier “never nests” statement to direct lift/lower failures. The broad statement at §7.2 otherwise remains contradictory.
- **Explicit park:** “waits on nothing” must refer to the explicit parked activation, not the entire task. Other carried frames remain ordered waits. This matters directly to the reported implementation bug.

No proposed key must be added to a DaoLease fact or DaoStream outcome map. A separate custody binding and an outer admission record satisfy that constraint.

Also qualify §7.7.6’s rollback/re-offer language: closed occurrences cannot automatically regain tenure under DHT §14.2.2. Governance compensation is outside automatic successor admission.

The epoch-publication rationale should not claim that durable lapse history inherently fails to survive restart. The defensible distinction is that a remote holder cannot infer authoritative tenure from its partial observation; the binding publishes the grant’s authoritative epoch.

### 4. Version gate

**Keep both `:yin.k/version` keys.** Their paths distinguish body schema from code contract. No stamp or opcode change is needed.

The landed reader checks top-level version before restoration ([handoff.cljc:942](/Users/sto/workspace/datomworld-ucf-b/src/cljc/yin/vm/ucf/handoff.cljc:942)); therefore it rejects integer version 1 rather than treating it as fork data. A version-1 reader must preserve explicit version-0 fork semantics and refuse version 0 when exclusive custody is required.

Strengthen recursive validation:

> Validate every nested body’s version and structural role before attachment, proposal, or restoration; a supported but mixed-version tree is undecodable.

The integer-kind check is essential on Node: numeric equality alone cannot validate a decoded integral-float version.

### 5. The ten acceptance clauses

The matrix is implementable after the missing contracts above are supplied. Its stage ownership needs these refinements:

| Clause | Ruling |
|---|---|
| 1, version | D; include old-reader rejection of v1 and recursive zero-side-effect validation. |
| 2, header | D; distinguish root from embedded child, including halted children. |
| 3, sequence | D for assignment/restoration; C for durable input replay; E for crash/regrant integration. |
| 4, carried IDs | D for structural checks; C for authoritative inherited-ID membership and ancestry. |
| 5, park/install | D, **both versions**, not v1 alone. |
| 6, envelope | C consumer/D writer; assert structured diagnostics instead of “answer nothing.” |
| 7, binding | C authority facts and atomic provenance; D holder refusal/release behavior. |
| 8, admission | C/D as assigned; add cross-target conflict and forged-outcome rejection. |
| 9, bounds | C/D as assigned; test MAX while valid separately from terminal exhaustion. |
| 10, composition | E; include crash cuts around completion and delivery, plus both host matrices. |

Canonical fixtures prove grammar parity. They do not prove atomicity, attribution, or recovery. Those require the durable transactional seam already demanded by DHT §14.2.4. None of this reopens or relocates M-next A’s kept-cursor evidence.

### 6. Install completeness

**The new entry has the necessary categories of state; the name-only explanation is correct.** A foreign resumer needs the verified response plus the child’s complete machine state, not a module name or a repeated link request.

However, its grammar is not yet fully consistent:

- §7.2.1 requires origin on a halted v1 body, then forbids all header keys on install children. A halted install child cannot satisfy both. Define root-only custody requirements and context-sensitive recursive validation.
- Children carry no `next-op-seq`, but their pending IDs are validated against the root counter and origin/chain. Validation must receive that root context explicitly.
- Require allowed phase values and phase/child-state compatibility, and validate the response’s module identity and image/manifest relationship. Canonical encodability alone does not make an arbitrary response verified.
- Restore the child’s saved state before scheduling it; creating a receiver template must not rerun initialization or replay the link request.

The relevant incomplete recursive contract is at [UCF lines 879–896](/Users/sto/workspace/datomworld-ucf-b/docs/design/yin.vm.universal-continuation-format.md:879).

### 7. The two landed-code findings are real

**(a) Parked frames:** export chooses `:parked` before considering nonempty waits and serializes those waits ([handoff.cljc:641](/Users/sto/workspace/datomworld-ucf-b/src/cljc/yin/vm/ucf/handoff.cljc:641)). Lower constructs their entries but then assigns `:wait-set []` for `:parked` ([line 1386](/Users/sto/workspace/datomworld-ucf-b/src/cljc/yin/vm/ucf/handoff.cljc:1386)). Thus an admitted body with both loses its waits. This is a version-0 preservation defect, not a new fenced-custody requirement.

**(b) Missing install entry:** lift checks membership ([line 497](/Users/sto/workspace/datomworld-ucf-b/src/cljc/yin/vm/ucf/handoff.cljc:497)); `validate-body` validates entries that exist but does not require one for every install pending ([line 996](/Users/sto/workspace/datomworld-ucf-b/src/cljc/yin/vm/ucf/handoff.cljc:996)). A foreign malformed body bypasses the lift check. This is likewise an existing completeness-validation defect.

Track both as explicit post-A defect fixes with version-0 tests, optionally delivered in D. Do not call them v1-only gaps, invalidate unrelated M-next A evidence, or silently reassign the M4 gate.

### 8. Q2–Q9 rulings

- **Q2 — settle now:** quarantine an occurrence after an authenticated, authoritative intent conflict; no automatic regrant. Governance decides recovery or compensation. Do not quarantine from an unauthenticated outcome claim.
- **Q3 — settle placement now:** enrollment is an attributed authority fact on the arbitration medium, keyed by target stream identity. C must define its schema and prevent enrollment changes from changing protection underneath a retained operation. No body field is needed.
- **Q4 — settle now:** keep both version keys, with their different paths and meanings.
- **Q5 — settle now:** v1 is exclusive-only; v0 remains fork-only.
- **Q6 — change recommendation:** defective input commits no effect and creates no dedup record, but produces a structured diagnostic identifying the defect and available provenance. It must not fabricate an admission outcome attributed to a legitimate holder. Publish that diagnostic contract before C; “a conforming writer cannot do this” is insufficient for adversarial input.
- **Q7 — settle requirement now, implement in C:** readers need authenticated evidence of the grant and binding’s common authority transaction. Bare `t` equality across arbitrary media is insufficient; use transaction identity within the named authority’s provenance domain. C must demonstrate the reflection preserves that evidence.
- **Q8 — settle now:** a definitive terminal refusal is a recorded result, replayed unchanged. Unknown-effect transport failure is not such a result.
- **Q9 — delegate encoding to C, settle invariants now:** occurrence IDs must be stable across publication retry/restart, collision-resistant or authoritatively unique, distinct for distinct parks, and compared by canonical bytes. Reject conflicting occurrence reuse. Their concrete form need not be an owner decision.

No owner question blocks these safety rulings. Governance recovery policy remains outside the automatic protocol.

### 9. Revision record

Update [ucf-revisions.md §6](/Users/sto/workspace/datomworld-ucf-b/docs/design/yin.vm.ucf-revisions.md:317) after the amendment passes review: record r5’s sections, reconciliation, review, and eventual commit; distinguish **published design** from **implemented v1 support**. Keep C–E and implementation evidence pending, retain the unchanged code stamps, and record the two version-0 defects separately.

Read-only review; no files changed or suites executed.