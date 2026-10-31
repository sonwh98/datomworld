Completed-GMT: 2026-09-19 18:09:28 GMT
Completed-Local: 2026-09-20 01:09:28 Indochina Time

## Part 1: Response to Fable's New Findings

### I-1 — W2 deliverable unsatisfiable

**AGREE — blocking.** I missed this contradiction.

The citations are correct: [semantic.cljc:476](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:476), [ast_walker.cljc:665](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ast_walker.cljc:665), and `engine_test.cljc:257,260,292,315,318,324` call the public `engine/check-wait-set`. Deleting it while forbidding test edits cannot satisfy W2.

Keep `engine/check-wait-set` as the VM integration function. Its implementation should invoke the extracted sweep, commit the returned store, and construct ready entries. Delete the two private polling helpers and the duplicated sweep body. This preserves the public boundary without preserving the implementation being extracted.

### I-2 — Entry schema mismatch

**AGREE — blocking when combined with the incomplete resolver contract.**

Tests construct readers with `:cursor-ref` and no `:stream-ref` (`engine_test.cljc:254`). Writer park sites use `:stream-id` and `:datom` (`ast_walker.cljc:111`). The existing resolver also honors an explicit `:stream` (`engine.cljc:275`).

Adopt Fable’s correction: the library interprets `:reason`; the remaining entry fields belong to the host. Resolve the whole entry:

```clojure
{:resolve (fn [store entry] ...)          ; => {:stream ... :cursor ... :value ...}
 :advance (fn [store entry cursor] ...)} ; => store'
```

Both functions are synchronous, with immutable store updates. The VM resolver understands `:cursor-ref`, `:stream-id`, and `:datom`; the library does not.

Preserving opaque VM registers does not couple the library to VM semantics. Inspecting or restoring them would. The handle rule should prohibit the library from adding resolved handles to returned entries; it cannot guarantee arbitrary opaque input or payloads contain no host objects.

### I-3 — Writer `closed` becomes `:end`

**AGREE — blocking.**

`engine.cljc:308` preserves writer `closed` as its qualified outcome. `terminal-resume-outcome` then identifies it as an error, and `throw-terminal-resume!` raises “Stream append failed.” Mapping it to `:end` bypasses that error path.

Keep `:dao.stream/closed` distinct. Only reader `:dao.stream/end` receives the VM’s normal end mapping.

### I-4 — W3 disposition function and shared state

**PARTIALLY AGREE — blocking for the proposed W3 contract.**

I accept Fable’s narrower diagnosis and withdraw the blanket prohibition on host-driven cadence. The remaining defects concern who owns interpreter state and who dispatches returned results.

An atom’s existence alone does not prove forbidden sharing. The plan fails to specify exclusive ownership while exposing external work submission and nudging through the container. That omission must be resolved before implementation. Detailed corrections appear below.

### I-5 — Nil resolver result unclassified

**AGREE — should-fix.**

The plan expressly permits nil at line 90 but gives it no meaning. Passing its missing handle to a protocol operation produces a host dispatch failure rather than the library’s promised classification.

Return a qualified library terminal status such as `:dao.stream.waitset/unresolved`, preserve the original entry, perform no stream operation, and leave the store unchanged. This is a waitset diagnostic, not an addition to DaoStream’s outcome sets.

### I-6 — Effectful `:put` breaks naive consumer adoption

**PARTIALLY AGREE — the defect is real; reader-only adoption is not a sufficient correction.**

The forwarder commits its source cursor only after destination acceptance ([forward.cljc:113](/Users/sto/workspace/datomworld/src/cljc/dao/stream/forward.cljc:113)). `serve-once!` retains its response and successor until its response append completes ([apply.cljc:252](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:252)). An independent waitset append followed by ordinary consumer retry can duplicate delivery.

However, assigning only the source read to the waitset also needs a handoff contract. If the waitset advances the forwarder’s authoritative cursor, the unchanged forwarder can skip the observed value. Keeping a separate observation cursor avoids that particular error, but introduces another observation whose value may be evicted before the consumer reads again.

The safe correction is to retain these compound steps under host cadence until an explicit result-consumption protocol preserves their commit points. Remove the census’s automatic “`:put` for a destination-full forwarder” migration. Reader-only migration is acceptable only where the consumer explicitly accepts the returned observation.

### I-7 — Shared-cursor gap write-back lacks a test

**AGREE — should-fix.**

`engine.cljc:304` returns the gap recovery cursor, and line 355 writes it back before resolving the next waiter. W1 must test that behavior explicitly: the first co-waiter receives `gap`; the next resolves against the recovery cursor and reads the earliest retained value.

The test should check wake order, returned statuses, and final store cursor.

### I-8 — Leftover draft text

**AGREE — note.**

The “callback… no” sentence exposes an unresolved API decision at the central store seam. Replace it with the agreed two-function resolver contract. Also replace “pure in, pure out”: the store updates are immutable, but `check` observes live streams and can append.

## Part 2: Response to Fable's Disagreements

### Finding 1 Dispute — scope of W3 invariant violation

**REVISE.** My original claim that host timers or blocking sleep primitives inherently violate Invariants 2–4 was too broad. My mandatory control-stream remedy was incomplete: depositing a cadence event does not itself arrange the next observation.

The governing contract explicitly assigns cadence to the runtime (`dao.stream.md:182`). The implementation evidence supports that distinction:

- `repl.cljc:194` gives the JVM poll loop exclusive ownership of REPL and endpoint state.
- `repl.cljc:267` uses a Node interval whose callback exclusively owns the state box.
- `repl.cljc:352` uses a Dart periodic timer to drive synchronous steps.

Existing code alone would not override an invariant. Here it corroborates the contract’s express placement of cadence. OD-5’s proposed language is supporting context, not a binding exception.

My revised position is:

- A composition root may arrange its own execution through host timers or sleep primitives.
- An event adapter must still deposit events without invoking interpreter work.
- The waitset must return results without accepting a retained disposition callback.
- Interpreter state must have one owner; external producers must not mutate it.
- Private timer bookkeeping is different from shared application state.

I accept Fable’s separation of cadence policy from host execution, with two qualifications.

First, `(round driver-state now)` cannot be called pure if it invokes `check`. A genuinely pure cadence function must consume polling results or progress facts supplied by the host and return scheduling decisions.

Second, I accept a host queue carrying contentless wake tokens as sleep machinery. I do **not** accept the proposed queue also carrying external park entries without a stream boundary. Those entries carry semantic work. Deposit them on a control stream; use the contentless signal solely to interrupt sleep.

Thus, the remaining blocking issue is the W3 ownership and dispatch contract. It does not require banning host loops.

### Finding 2 Dispute — nudge! severity

**REVISE — downgrade to should-fix.**

I concede that an optional, contentless scheduling hint need not itself be a semantic stream event. Polling remains authoritative, and the underlying append is already recorded. A hint need not carry application data or dispatch an entry.

Accept `nudge!` as host policy with these requirements:

- It carries no entry, outcome, continuation, or disposition function.
- It coalesces and requests a later host tick; it never runs interpreter work inline in the depositor.
- The composition wires it behind the supplied deposit operation. Adapters receive no driver reference.
- Periodic polling continues independently of hints.
- The plan describes hints for relevant transitions, including close, without requiring every transition to provide one.

The original “lost nudge costs one tick” statement should mean the currently scheduled interval, potentially the backoff ceiling—not necessarily `:poll-ms`.

Fable’s example that a consuming reader can release `full` needs qualification: DaoStream reads are non-destructive (`dao.stream.md:548`). Reading alone does not release capacity under this contract. A separate host or transport transition might.

### Finding 4 Dispute — rotation vs. delete budget

**CONCEDE on the proposed rotation remedy; MAINTAIN the starvation finding.**

With waiting order `[A B]`, both sharing a cursor, polling blocked `A` and rotating to `[B A]` can let `B` receive the next value first. That changes the order the plan promises to preserve.

Delete `:budget` from this extraction and retain the complete ordered pass. A future bounded traversal requires its own specified ordering and interleaving semantics. A scan-position field alone does not establish equivalence to the existing full sweep.

### Finding 6 Dispute — total check vs. throwing park

**REVISE.** I prefer Fable’s opaque entries, two-function resolver, and terminal data classification. A throwing `park` is unnecessary.

One integration detail remains: `engine.cljc:397` checks terminal status only for `:next` and `:put`. An unknown-reason entry returned as terminal could otherwise bypass that check and reach restoration.

The VM integration must handle waitset diagnostics before ordinary ready-queue restoration. Existing suites remaining green would not prove this new error path correct; add a focused test. Preserve existing valid-entry behavior while explicitly documenting the changed handling of malformed entries.

## Part 3: Proposed Consensus Findings List

These are proposed joint findings, not a claim that Fable has already accepted my qualifications. File references below are to `docs/design/dao.stream.waitset.implementation-plan.md` unless otherwise named.

1. **Blocking | :249 — Preserve the public VM integration function.**  
   Keep `engine/check-wait-set`; delete its inline sweep and the two private helpers. Amend the deletion checks while retaining unchanged existing VM suites. Production callers and direct test calls make the current deliverable impossible.

2. **Blocking | :81, :89, :102 — Complete the opaque-entry resolver contract.**  
   Use synchronous `:resolve` and pure `:advance` functions over whole entries. Resolve writer payloads through the host, preserve opaque fields, and apply both success and gap cursor updates before later entries resolve. Test a non-VM store representation. This combines the entry mismatch and missing write-back seam.

3. **Blocking | :115 — Preserve writer `closed` as a terminal failure.**  
   Retain its qualified outcome and the VM’s immediate/parked error equivalence. Never map it to reader `:end`.

4. **Blocking, W3 | :267 — Define exclusive state ownership and remove library disposition dispatch.**  
   The host owns interpreter state, invokes `check`, and consumes returned woken data. Cadence calculations return scheduling data; host primitives implement timing. Do not retain a consumer callback in the reusable waitset driver.  
   **Remaining qualification:** Fable proposes carrying external parks on the host queue. I accept only contentless wake signals there; semantic park commands should cross a stream boundary. A pure cadence function also cannot secretly invoke effectful `check`.

5. **Blocking, W3 | :150, :169 — Remove the entry budget.**  
   Retain a complete ordered sweep. Defer bounded traversal until its ordering semantics are specified. I withdraw rotation as a correction.

6. **Should-fix | :158 — Constrain nudges to optional host scheduling hints.**  
   Make them contentless, coalesced, non-reentrant, and composition-wired. Preserve polling fallback and describe relevant transition coverage and backoff latency. My original blocking severity is withdrawn.

7. **Should-fix | :90, :331 — Classify unresolved entries and unsupported reasons.**  
   Return qualified waitset diagnostics, remove these entries from waiting, and avoid protocol calls for unresolved handles. Ensure the VM reports diagnostics before restoring continuations. Add targeted tests.

8. **Should-fix | :76, :122 — Separate per-call results from threaded waiting state.**  
   Prefer `{:waitset {:waiting ...} :woken ... :store ...}`. Never accumulate prior woken results or inject resolved handles into entries. Test repeated checks and handle-free entries using serializable fixtures; do not promise arbitrary stream payloads are serializable.

9. **Should-fix | :207, :293 — Correct W4 and preserve compound consumer commit points.**  
   Enumerate actual adoption sites from W0 and explicitly retain single-stream consumers. Remove the automatic forwarder `:put` migration.  
   **Remaining disagreement:** Fable recommends reader-only adoption. I require an explicit observation handoff even for readers; otherwise retain the existing compound step under host cadence. Write ownership, pending responses, cursor commitment, and terminal outcomes must survive adoption.

10. **Should-fix | :240 — Test shared-cursor gap recovery.**  
    Prove that a gap recovery cursor becomes visible to the next co-waiter during the same ordered sweep.

11. **Should-fix | :235 — Pin classification separately for each operation.**  
    Use literal expected maps whose domains equal `outcomes-next` and `outcomes-append` respectively. A union-only domain check loses operation distinctions: reader `ok` carries a successor; writer `ok` does not. Test unknown outcomes as terminal.

12. **Should-fix | :305, :378 — Reconcile documentation scope with the end condition.**  
    Update all obsolete live-runtime claims in `dao.await.md`, including task wrapping and `:resume` invocation. Define the scope of the wider stale-reference check and permit the edits needed to satisfy it.

13. **Note | :105 — Replace draft wording and inaccurate purity claims.**  
    Describe `check` as a synchronous interpreter step with explicit stream operations and immutable store updates. Reserve “pure” for computation that does not observe or mutate live streams.

I retain **unsound as written**, with a narrower rationale. The extraction boundary is sound; the public API contradiction, incomplete resolver, incorrect `closed` mapping, and unresolved W3 contracts require revision. The existence of host timers and an optional contentless nudge is not itself a blocker.

No files were edited.