Completed-GMT: 2026-10-08 19:46:31 GMT
Completed-Local: 2026-10-09 02:46:31 Asia/Ho_Chi_Minh

**Ruling: choose (c), with narrowly defined (a) validation support. A policy reclaim of a live, durably reported lease must atomically close its occurrence and record the matching edge.** Do not create a second lapse, and do not let the driver infer closure from a report plus a historical policy lapse.

The defect is architectural: `grant/lapse-decision` already builds lapse, optional closure and epoch advancement as one transaction, but `completion/closure` generates closure only for `:release`, and `ledger/closure-defect` accepts only that cause. Reopen’s `:policy` path therefore discards the opportunity to complete an otherwise committed report.

### Exact authority and ledger semantics

For a **currently live lease** undergoing a `:release` or `:policy` lapse, the authority must generate this single transaction when completion is eligible:

```clojure
[lapse
 occurrence-completed
 successor-edge-or-terminal-edge
 occurrence-reclaimed]
```

Eligibility requires all existing completion conditions:

- The lease is the occurrence’s current live binding.
- Its accepted report already supplies `:yin.k/result`.
- A continuation report supplies its exact `:yin.k/successor`; a terminal report supplies no successor.
- The occurrence is neither closed nor quarantined.
- Epoch advancement does not exceed the supported limit.
- The successor, if any, satisfies the existing uniqueness constraints.

Use this rule for policy lapses generally, including reopen. An accepted report commits the completion identity; a subsequent policy reclaim must not turn that reported completion into abandonment merely because the holder disappeared.

`closure-defect` must accept cause **`:release` or `:policy`**, while retaining the crucial `::unepoched` requirement: the matching lapse must have occurred **earlier in this same transaction**. Preserve every other check. `edge-defect` continues requiring the exact reported successor or terminal result and the same-transaction closure.

The fold must retain:

- the lease’s actual lapse cause—`:policy` stays `:policy`;
- its accepted result/successor report;
- the occurrence’s closure naming the exact origin lease;
- exactly one matching successor or terminal edge;
- the existing epoch advancement and removal of the live binding.

Do not rewrite policy history as release, add a second lapse, or synthesize release carriage. Repeated reopen after a committed transaction adds neither another edge nor another epoch increment for that already-reclaimed lease. Unknown transaction acceptance must be resolved through the existing journal reopen/fold discipline before any successor becomes eligible.

A **policy-reclaimed lease without an accepted report produces no closure or edge**. Its occurrence follows ordinary reclaim/regrant policy, and a speculative successor remains ineligible. Report intent, carrier acceptance, a local acknowledgment record or a stored body is not a substitute for the authority’s accepted report.

### Successor eligibility and driver recovery

Keep `completion/offer-refusal`’s authoritative closure requirement. Admission must still match the predecessor occurrence, closing lease, reported body address and successor occurrence under the same arbitration authority. **A report alone, a carried release alone, or a policy lapse alone is insufficient.**

`exit-closure-step` must retain its exact closure/body/successor comparisons. Its success predicate needs no weakening: the new authority transaction supplies precisely the closure it already waits for. Update its contract text to include release and policy-reclaim completion.

Recovery must inspect authoritative closure **before requiring a release retry or release acknowledgment**. When the exact closure already stands, finish the terminal exit or retry the same successor offer until admitted. The recovered driver need not manufacture or obtain a release acknowledgment for a lease already closed by policy reclaim. Preserve unmatched journal history as history; do not falsely mark a release carried.

If closure is absent:

- unavailable or incomplete ledger evidence suspends;
- an unreported, reclaimed origin follows the existing abandonment/re-candidacy path;
- a complete projection proving closure permanently impossible—such as quarantine, exhaustion, or a historical policy lapse already committed without closure—must not produce an endless successful “waiting for closure” state.

For that last historical shape, return a terminal diagnostic explaining the inconsistent/incomplete completion history and keep the successor ineligible. **This repair is prospective; it does not authorize retroactive standalone closure transactions over old ledgers.** Supporting such repair would require a separate migration ruling.

### Implementation ownership and acceptance

Land a named production repair prerequisite, **D16-completion-recovery**, before finishing the D16 gate. Its scope includes `authority/completion.cljc`, `ledger.cljc`, grant/reopen integration, the driver’s recovery dispatch where necessary, normative completion text and their tests. This is a documented extension of completion semantics, not a test-only workaround.

Pin these rows through the real composition on JVM, Node and Dart:

1. **Report committed, crash before release:** fresh file-backed reopen atomically records policy lapse, closure, exact edge and epoch advancement; recovery admits the same successor and exits.
2. **Release appended/carried, crash before authoritative lapse:** the same result, with no fabricated release acknowledgment or duplicate closure.
3. **Crash after closure or unknown reclaim-transaction acceptance:** reopen observes either the complete transaction or its absence; eventual completion occurs once, without duplicate edge or epoch advancement.
4. **Terminal report:** policy reclaim records the exact terminal result edge and recovery exits without a successor offer.
5. **Abandonment:** no report, report intent only, or an uncommitted report produces no closure; the speculative successor remains ineligible.
6. **Validation negatives:** standalone closure after an earlier transaction’s policy lapse, wrong lease/body/successor, duplicate edge, quarantine and exhaustion cannot gain successor eligibility.
7. **Ordinary release completion:** existing behavior remains unchanged.
8. **Recovery isolation:** no guest execution or program IO occurs while finishing these recovered exits; missing ledger evidence cannot be treated as completion.

The two existing red completion-crash rows must become green without loosening their exit or successor-admission assertions. Other incomplete audit rows remain owed.

**The D14 exit-order correction already covers offer admission:** attempt the successor offer before reporting; `:awaiting-completion` is permitted; successful admission follows authoritative closure. Replace the stale brief wording rather than requiring admission before the report. This ruling adds the crash-recovery alternative to release-driven closure; it does not reverse that ordering.

No files edited or suites run.