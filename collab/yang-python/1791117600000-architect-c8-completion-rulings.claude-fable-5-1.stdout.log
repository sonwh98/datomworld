Completed-GMT: 2026-10-04 21:32:32 GMT
Completed-Local: 2026-10-05 04:32:32 +07

**Summary: C8 must admit a halted result as a completion (the engineer's refusal is wrong), must refuse an origin-bearing body whose predecessor the ledger never saw, and must not complete a quarantined occurrence; the recorded `:yin.k/resumed` fact is accepted.**

Read-only. I read the engineer's report, `completion.cljc`, the C8 parts of `ledger.cljc` and `offer-decision` in `grant.cljc` on branch `ucf-c8-completion` (c6373817). I did not read the tests or the C7 admission code, so the quarantine ruling in section 4 names the rule, not the projection key. The clock reads earlier than the brief's Created-GMT; the header is the clock's value.

## 1. Halted successors: a result completes the occurrence

**The UCF text is explicit that a halt completes.**
- 7.7.2: the report's `:yin.k/result` is "the address of the successor's occurrence value or of a `:yin.k/result`".
- 7.7.6: the holder "either halts or parks again. Either way it produces a successor value".
- Linker-dht 14.2.3 step 6: "At the next safepoint or halt, publish the successor, report resumed … then release", and the authority's transaction "closes O, and records completion".
- 7.2.1: a halted root "is not a lease subject, and nothing remains to admit".

Refusing it leaves a finished computation grantable forever, which contradicts "a closed occurrence receives no further grant".

**The rule.**
- A release whose recorded report names a halted result commits, in the lapse's transaction: the lapse, the closure, one terminal edge, the epoch change. Same order as today.
- The terminal edge is the existing `:yin.k/succeeded` kind with `:yin.k/result address` in place of `:yin.k/successor`. An edge carries exactly one of the two.
- The result is not an occurrence. It is never offered, never admitted, never granted. `offer-decision` already answers `:not-offerable` for a halted body; keep that.
- Checks on a halted body at report time: the inspector's halted rules (address, version 1, root role, origin required, no other header key), plus the origin naming exactly this occurrence and this lease. No arbitration, fresh-occurrence, or counter check applies, because a halted root carries none of those.
- Exhaustion blocks it like any completion (7.7.8, "accepts no completion").
- The closed occurrence is never granted again. Its chain ends there: no ancestry runs through a result, and a continuation whose origin names it is an `:orphan` (the current `offer-refusal` already answers that, since the successor does not match).
- Acyclicity holds trivially: a result address is not an occurrence, so no edge can point back.

**Code delta, `completion.cljc`.**
- `successor-defect`: for kind `:halted`, check only the origin (`:wrong-origin`) and answer nil. Remove `:not-a-continuation`.
- `report-decision`: for a halted body, record the report without `:yin.k/successor`.
- `closure`: complete when the lease entry has `:yin.k/result`. With `:yin.k/successor` emit `(succeeded o s)`; without it emit the terminal edge `{:yin.k/custody :yin.k/succeeded :yin.k/occurrence o :yin.k/result address}`. Apply the "successor still unseen" test only when there is a successor.
- Add a constructor for the terminal edge; update the docstrings.

**Code delta, `ledger.cljc`.**
- `attribute-order` for `:yin.k/succeeded`: `[custody occurrence successor result]`. Absent attributes are already skipped.
- `report-defect`: `:yin.k/successor` is optional; when present it must be an occurrence different from O and unseen. `:duplicate-report` tests for `:yin.k/result` on the lease, not `:yin.k/successor`.
- `closure-defect`: `:unreported` tests for `:yin.k/result`.
- `edge-defect`: require exactly one of successor and result (`:malformed-fact` otherwise). The result branch requires it to equal the lease's recorded result and the lease to have no successor (`:successor-mismatch`).
- The `:yin.k/succeeded` arm: store `:yin.k/result` under `:yin.k/closed` for a terminal edge.

**Tests.**
1. A release with a halted report commits exactly lapse, closure, terminal edge, epoch change.
2. After it, a grant on the occurrence is `:closed-occurrence`, through the writer and the hook.
3. A halted body with a wrong origin occurrence, or a wrong lease, is refused and writes nothing.
4. An offer of the halted body is `:not-offerable`; a continuation naming the closed occurrence as origin is `:orphan`.
5. A halted report at the epoch bound is recorded; its release does not complete.
6. Crash cuts on the halted completion transaction: closure and terminal edge persist together or not at all; reopen replays.
7. Fold: an edge with both targets, with neither, or with a result that differs from the report, is a defect.
8. A second report on the same lease, halted after continuation or the reverse, is `:report-conflict`.
9. Remove the "halted" refusal case from `the-report-verifies-its-successor`.

## 2. The recorded `:yin.k/resumed` fact: accepted

- It is needed: the report must survive a crash between report and release.
- The author is not recorded. It must equal the lease's holder, which the ledger already holds.
- The recorded copy adds `:yin.k/successor` for a continuation and nothing for a result.
- **UCF 7.7.2:** add a second table, "as recorded by the authority", listing `:yin.k/resumed` (with the added key), `:yin.k/completed`, and `:yin.k/succeeded` (with its two target forms). State that the recorded report is still evidence the authority verified; completion is the closure.

## 3. A predecessor this ledger never saw: refuse

- 7.7.8 says the successor's arbitration identity "is the predecessor's, unchanged". So in version 1 a predecessor never lives on another authority; there is no cross-authority chain to accommodate.
- 7.2.1 defines a first export as a body with no origin. Linker-dht 14.2.3 step 6 says a successor offer "is eligible only after that predecessor completion".
- So a body with an origin and no known predecessor is an orphan, not a first offer.
- **Delta:** in `offer-refusal`, when the body has an origin and the predecessor is unknown, answer `:orphan`. A body with no origin is unconstrained, as now.
- **Tests:** move the C5 and C6 tests that offer the `successor` fixture directly onto `first-park`, or complete a predecessor first. Invert `a-successor-of-a-predecessor-this-ledger-never-saw-is-a-first-offer`.
- A ledger that lost its history is a governance case, outside the protocol.

## 4. Other items that change behavior

- **Quarantine blocks completion (new).** UCF 7.9: after an intent conflict the run "publishes no successor", and 7.7.8 keeps a quarantined occurrence open. `closure` must return no facts for a quarantined occurrence, so the release is a plain reclaim, and `report!` should refuse with `:quarantined`. Add one test for each.
- **Open question 6, confirmed.** A lapse of cause `:release` puts `:dao.lease/released` in `:seen` at rebuild. That is what plan step 1.5.5 means.
- **Open question 4.** No separate reason; `:orphan` stands.
- **Open question 5.** No index. Record the O(occurrences) scan as a C12 cost note.
- **Deviations 2 to 8** are accepted as built, including the epoch change on completion and recording a report at the epoch bound.
