Completed-GMT: 2026-09-19 18:13:13 GMT
Completed-Local: 2026-09-20 01:13:13 +07 (Indochina Time)

I sign off on all 13 items: 10 accepted outright, and items 4, 7 and 9 accepted with caveats. I concede Dispute A in full. On Dispute B I concede the defect and propose a probe-entry compromise, which gpt-5.6-sol has not yet agreed to.

## Consensus Sign-Off

### Item 1 — Preserve the public VM integration function
ACCEPT

### Item 2 — Complete the opaque-entry resolver contract
ACCEPT

I also accept gpt-5.6-sol's refinement from its I-2 response. The handle rule only prohibits the library from adding resolved handles to returned entries. It cannot vouch for the contents of opaque host fields.

### Item 3 — Preserve writer `closed` as a terminal failure
ACCEPT

### Item 4 — Exclusive state ownership; no library disposition dispatch
ACCEPT WITH CAVEAT. The item stands with both of gpt-5.6-sol's qualifications adopted, as resolved under Dispute A below. The cadence function is pure and does not call `check`, and the host queue carries only contentless wake tokens.

### Item 5 — Remove the entry budget
ACCEPT

### Item 6 — Constrain nudges to optional host scheduling hints
ACCEPT

I withdraw my R1 claim that a consuming reader releases `full`. `dao.stream.md:548-552` says "Reading never mutates the stream… no drain, no take", so a read cannot free capacity. Only a transport-level or host-level transition could.

I also accept the latency correction. A lost nudge costs up to the currently armed interval, which may be the backoff ceiling and is not necessarily `:poll-ms`.

### Item 7 — Classify unresolved entries and unsupported reasons
ACCEPT WITH CAVEAT. gpt-5.6-sol's integration point is correct and I missed it.
- `terminal-resume-outcome` checks only `#{:next :put}` (`engine.cljc:397`). An unknown-reason diagnostic would therefore fall through to `restore-fn`.
- The engine's `check-wait-set` must raise waitset diagnostics before restoration, and a focused test must cover that path.
- The divergence-register row "Unknown wait `:reason` kept waiting → Unchanged" (plan:331) must be rewritten as an intentional divergence with its reason. It must not be silently dropped.
- I grepped `test/yin` and `test/dao/await_test.cljc`. No test parks an entry with a reason other than `:next` or `:put`, so the unchanged-suite condition is unaffected.

### Item 8 — Separate per-call results from threaded waiting state
ACCEPT

This includes the narrower promise: returned entries carry no handles, but the library makes no claim that arbitrary payloads are serializable.

### Item 9 — Correct W4; preserve compound consumer commit points
ACCEPT WITH CAVEAT, as resolved under Dispute B below. Naive reader-only adoption is withdrawn. The only form I would sanction is a probe entry whose `:advance` is the identity function. Otherwise the compound step stays under host cadence.

### Item 10 — Test shared-cursor gap recovery
ACCEPT

### Item 11 — Pin classification separately per operation
ACCEPT

Per-operation literal maps are better than my single union-domain check. `ok` has a different result shape for a reader than for a writer, and `closed` belongs only to the append set. A union map would hide both facts.

### Item 12 — Reconcile documentation scope with the end condition
ACCEPT

### Item 13 — Replace draft wording and purity claims
ACCEPT

## Dispute Resolution

### Dispute A — park entries on the queue
**CONCEDE, and go further: delete external parks from W3.**

gpt-5.6-sol is right on both counts.

- **Parks on the queue.**
  - A park entry is semantic work. A `LinkedBlockingQueue` that carries entries is a second medium with destructive take. Nothing else can observe it, and `dao.stream.md:548-552` excludes that shape. It also has no cljs or cljd equivalent, so the three drivers would differ in kind.
  - A contentless token is sleep machinery. An entry is not.
  - The W0 census found no consumer that parks into someone else's waitset from another thread. The VM parks into its own state, and the serving tick owner parks its own slots. The "external entries" clause at plan:269-270 came over from the deleted R2 drivers and has no user.
  - W3 should drop it. If a composition ever needs cross-owner submission, no new mechanism is required:
    1. The submitter appends the park command to a control stream.
    2. The owner holds an ordinary `:next` entry on that stream in its own waitset.
    3. The submitter's composition may `nudge!`.
- **Purity of `round`.**
  - I called `(round driver-state now)` pure while it invoked `check`. That was wrong, and it was the same imprecision I criticised in the plan.
  - The agreed shape is this: the host loop calls `check` and consumes `:woken`.
  - It then calls a pure cadence function, roughly `(cadence-step cadence-state moved?) → {:cadence-state … :sleep-ms …}`.
  - That function covers backoff, reset-on-wake and the ceiling. It is testable on all hosts with no streams involved.

### Dispute B — reader-only vs. explicit handoff
**COMPROMISE. I concede the defect. The remedy is a named probe contract, which qualifies as gpt-5.6-sol's "explicit observation handoff".**

- **Verified.**
  - `forward.cljc:113-116` advances the cursor only on `:advance`, that is, only after `observe/step`'s append answered `ok`. `:retry` returns the unmoved cursor (`forward.cljc:118-121`).
  - `apply.cljc:252-264` commits `:request-cursor` from `:pending-successor` only when the response append answers `ok`.
- **Why my R1 wording was unsafe.**
  - "Reader-side only" was underspecified. Under item 2's resolver, an `ok` on a `:next` entry invokes `:advance`. If that store is the consumer's authoritative cursor, the waitset commits the read before the destination accepts the value.
  - gpt-5.6-sol is right that this breaks the commit point.
  - gpt-5.6-sol is also right that a separate observation cursor introduces a second position that can be evicted.
- **Probe entry.**
  - Reads are non-destructive (`dao.stream.md:548`). A `:next` entry whose resolver supplies the consumer's own current cursor and an `:advance` that returns the store unchanged is therefore a pure readiness probe.
  - The probe adds no second cursor. The waitset's `next` looks at the position the compound step will read next, and commits nothing.
  - The handoff rule is explicit. The woken `:value` and `:cursor` are advisory and are discarded. The consumer's own compound step (`forward-step` or `serve-once!`) is the sole authority. It re-reads from its own cursor and keeps its own append, pending-response and commit logic.
  - An eviction between the probe and the step produces a `gap` that the consumer's step already handles. This adds no new hazard.
  - The cost is one redundant `next` per wake. The benefit is the driver's "anything moved" bit without touching commit points.
- **Two constraints the plan must state.**
  - A probe entry is re-parked by its owner after each step.
  - Probe entries must not share a cursor-ref with advancing entries. An identity `:advance` would wake co-waiters on the same value.
- **Scope.**
  - Writer-side `:put` migration for forwarders and for serve-once is removed outright.
  - Where a consumer adopts neither the probe nor a future result-consumption protocol, it stays a compound step under host cadence, as in gpt-5.6-sol's fallback.
  - Item 9 should enumerate, for each W4 site, which of the two applies.

## Joint Verdict

The plan is **unsound as written**, and the fix is a revision, not a redesign. With the modifications above, the 13-item list is a sound and sufficient basis for that revision. Both reviewers reach this verdict on the same four blockers:
1. The W2 deliverable cannot be met while `engine/check-wait-set` is public.
2. The entry and resolver seam is incomplete.
3. The mapping of writer `closed` to `:end` changes VM behaviour.
4. The W3 ownership, dispatch and budget contract is unsettled.

Both reviewers endorse the W0–W2 extraction, the `dao.stream.waitset` name, the refusal of `:resume`, queues and tasks, and the zero-diff substrate condition.

No disagreement remains open on my side. Dispute A is conceded in full, and gpt-5.6-sol's integration points on items 6, 7 and 11 improve on my R1. Dispute B's probe compromise has not been reviewed by gpt-5.6-sol; if it is rejected, I accept the fallback of keeping compound steps under host cadence.

The orchestrator can hand items 1–13 to the plan author as a single revision brief. Items 1–3 and the W1 tests in items 7, 8, 10 and 11 gate W1. Items 4–6 gate W3. Item 9 gates W4. Item 12 gates W5.

No files were edited.
