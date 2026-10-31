Completed-GMT: 2026-10-06 21:12:00 GMT
Completed-Local: 2026-10-07 04:12:00 +0700

**Ruling: add a public, complete export-recovery codec and fenced-only rehydration seam, then rebuild D14’s journal/recovery half around it. Correct the brief to the landed completion order. Do not wait for D10b’s version-2 handoff amendment.**

### 1. Complete-record persistence

The D9 statement “the record is then plain data” was incorrect. Re-keying `:served` removed handles from that table; it did not encode closures inside waits or capture the machine dependencies needed to restore them. This is a missing promised contract, not permission to persist `[:served :header]` alone.

Authorize these public operations on `holder/export`:

```clojure
(export/freeze machine prepared-record)
;; => {:status :ok :bytes canonical-bytes :address recovery-address
;;     :body-bytes handoff-bytes :body-address handoff-address}
;; or an existing data refusal

(export/rehydrate-fenced receiver recovery-bytes
                         {:address recovery-address
                          :fetch fetch-content
                          :attach attach-resource})
;; => {:status :ok :machine fenced-machine :record prepared-record}
;; or a data refusal; never a runnable machine
```

The names may follow repository conventions; the following behavior is binding.

**What is durable.** The journal’s `fenced` event references a versioned, content-addressed recovery object and the exact handoff body. Both objects must be durably stored before that event is acknowledged. The recovery object must contain, directly or through verified content references:

- Ordered waits, reachable parked records and retained operations, including operation IDs and issue ordering.
- The complete dependency closure needed to reconstruct them: values, cells and cursor positions, code, guest/module stores, install children, resource bindings and fresh-name state.
- The prepared descriptor table, preserving resource alias relationships.
- The exact header inputs and the prior gate needed to interpret the original export record.
- The exact body address and enough information to prove that reconstruction reproduces its bytes.

This is a **complete recovery representation**, not a dump of every host machine field. Derivable indexes, host handles, owner secrets and executable callbacks do not travel. The body may provide most of the guest-state representation; do not duplicate it unnecessarily.

**Closures and cells.** Reuse the handoff value/cell codec and its deterministic dependency census. Do not teach Jing to encode `Closure` as an opaque host object, invent a second closure format, or stringify it. Census all roots—including halted results and child/module dependencies—before finalizing cells, profiles and code. Rehydration creates authentic values and references owned by the fresh receiver, preserving aliases and distinct cells.

The stored enrolled set is local preparation evidence, not current authority evidence. It may be retained in the recovery object to reproduce the prepared export; it remains absent from the published handoff body and cannot authorize execution, enrollment or tenure.

**Fenced-only restoration.** Validate the recovery format, content addresses, embedded body and dependency consistency before attachment. Attachment is administrative reconstruction only: no reads, cursor minting, writes, closes, initialization or guest execution. Return the root and every child fenced; keep runnable waits out of scheduler queues. Retain the prior gate as data, never apply it during recovery.

A rehydrated record is **not permission to abort back into execution**. A restarted holder releases and re-enters candidacy; a recovered published source remains fenced and follows the offer/grant protocol. Do not expose a public `resume-task` option that bypasses grant admission. Any shared lower machinery must keep grantless fenced reconstruction distinct from runnable restoration.

**Versioning.** Give this recovery object its own explicit tag and format version, initially 1. It is not a UCF handoff body, cannot be offered as one, and cannot be passed to ordinary lower. Semantic handoff versions 0/1 and their bytes remain unchanged. D10b can later extend the recovery codec to its version-2 execution profiles through the same shared codec dispatch. This seam does not require or authorize premature implementation of that amendment.

Widen the permitted diff to `holder/export`, the necessary shared handoff codec/restoration internals, their tests and the governing documentation. Keep Jing, authority semantics and kernel code contracts unchanged.

### 2. Exit ordering

**The landed completion flow is the contract.** `completion/offer-refusal` requires the predecessor’s authoritative closure before admitting its successor; waiting for admission before reporting creates a cycle.

Replace brief point 5 with:

1. Persist the complete prepared recovery object and body, then the fenced event.
2. Journal and attempt the successor offer. Its admission may answer `:awaiting-completion`; admission is not a prerequisite for reporting.
3. Journal the resumed report naming that exact successor/body; obtain authenticated acceptance.
4. Journal and carry release of the matching origin lease.
5. Observe the authoritative closure and matching successor edge.
6. Retry the same successor offer until admitted.

For a halt, report the result body and observe the terminal edge; there is no successor continuation to offer. A release alone never proves completion. Unknown delivery remains unknown, and no authority refusal erases a possibly appended offer attempt.

At every stage, journal intent precedes the external action. Each retry has an attempt record, while the logical request, occurrence and body remain stable.

### 3. Disposition and remaining defects

**Rebuild the journal/recovery half from the new seam.** Preserve the landed D13 candidate behavior and salvage independently correct source/exit helpers, but remove projection-based persistence, `record-of`’s invented empty state and dependence on the original machine. Do not patch those into the recovery foundation.

The other audit defects are implementation corrections under existing contracts:

- **Enrollment:** persist target identity and progress; reconcile authenticated enrollment before retry. Unavailable evidence means wait, not another enrollment call.
- **Intent selection:** fold journal sequence order explicitly. Never derive recovery order from map iteration.
- **Exit recovery:** correlate intents and acknowledgments with the exact origin occurrence, lease and successor/body. An old release acknowledgment cannot discharge a new lease.
- **Stalled state:** all public mutating entries—including `abort` and `enroll`—must refuse progression after uncertain journal append until reopen reconciles it.
- **Journal reads:** distinguish authenticated end-of-history from transport failure, gaps and malformed frames. Only the journal’s documented reopen logic handles torn tails; the driver must not interpret arbitrary non-OK reads as EOF.
- **Tenure:** preserve D13’s bound-before-apply discipline and recheck before each exit action. Persisted clock readings do not re-establish tenure after restart.
- **Abort:** derive attempts from durable intents, including unmatched intents whose delivery is unknown. Use the current successful renewal basis for an uninterrupted holder; never revive a crashed holder from journaled tenure.

One API clarification is architectural: separate durable driver progress from the composition environment containing functions, handles and live machines. The journal contract requires plain canonical data; it does not make the runtime wrapper serializable.

For crashes before a complete fenced snapshot exists, recovery must identify the incomplete preparation and remain non-runnable. It must never manufacture missing waits, remint the successor, blindly repeat uncertain serving, or fall back to a supplied original machine. The implementation must explicitly journal/reconcile preparation progress where retry is supported.

**Acceptance delta:** require real closure-bearing waits, parked continuations, aliases, module closures and install children to survive canonical freeze and fresh-receiver rehydration; reproduce identical handoff bytes; make zero program-IO calls; and remain fenced. Add crash cuts around storage, fencing, every retry intent/send/ack, report, release and closure, plus the audit’s genuine divergence/conflict and enrollment-recovery rows. The complete-record seam must pass independently before D14 recovery is rebuilt atop it.

Read-only ruling; no files edited and no suites run.