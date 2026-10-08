Completed-GMT: 2026-09-19 18:05:47 GMT
Completed-Local: 2026-09-20 01:05:47 +07 (Indochina Time)

**Verdict: sound core, unsound as written.** I count 4 blocking, 5 should-fix and 1 note. The W0–W2 extraction is right. The blockers are the writer `closed` → `:end` mapping, a W2 deliverable that cannot be met, an incomplete resolver seam, and the W3 driver shape including `:budget`. Two of gpt-5.6-sol's blocking findings — `nudge!`, and W3 as an invariant violation — are overstated, and one of its remedies (rotation) introduces a bug.

## Part 1: Independent Assessment

This was a read-only review. I verified findings against the engine, the stream contract and the tests.

**I-1 (blocking) — W2's deliverable cannot be met as stated.**
- The plan at :249-258 requires all three inline functions to be gone (checked by grep) and the `yin.vm` suite to pass without test edits.
- `check-wait-set` is a public `defn` at `engine.cljc:316`. It is called from `semantic.cljc:476` and `ast_walker.cljc:665`, and directly by tests at `engine_test.cljc:257,260,292,315,318,324`.
- Deleting it therefore forces test edits, which the plan itself calls "a defect in W1".
- Correction: delete only the two private functions, `augment-wait-entry` and `poll-wait-entry`. Keep `engine/check-wait-set` as the engine's public state→state consumer, with a body of `waitset/check` plus `make-woken-run-queue-entries`. Narrow the grep end condition to match.

**I-2 (blocking) — the entry schema does not match the entries the suites use.**
- The plan's entry is `{:reason :stream-ref :cursor-ref}` (plan:82).
- The VM's entries are readers with `:cursor-ref {:id}` only, where the stream is found through the cursor's store record (`engine.cljc:272-277`), and writers with `:stream-id` plus `:datom` (`engine.cljc:278-279,308`).
- An optional pre-resolved `:stream` is also honoured (`engine.cljc:275`).
- Tests build these shapes by hand (`engine_test.cljc:254,273`). Every walker parks them (`semantic.cljc:112,166`, `ast_walker.cljc:114`, `ffi.cljc:61`).
- A library that requires `:stream-ref` forces either renames across the VM or a translation layer on each side of every round.
- Correction: the library reads only `:reason`. Everything else in the entry is opaque and preserved. The resolver takes the whole entry: `(resolve store entry) → {:stream :cursor :value}`.
- This also fixes the missing writer value in gpt's Finding 6. The host names its own key (`:datom`), and the resolver surfaces it.

**I-3 (blocking) — writer `closed` → `:end` silently changes VM semantics.** This is the same defect as gpt's Finding 5, verified independently; evidence is under Finding 5 below.

**I-4 (blocking, scoped to W3 only) — the driver takes a disposition function and holds state in a shared atom.** See Finding 1 below. My objection is narrower than gpt's.

**I-5 (should-fix) — a nil result from the resolver is not classified.**
- The plan allows `resolve-ref … or nil` (plan:90) but never says what `check` does with nil.
- Today the engine passes a nil handle into `stream/next` and gets a host exception.
- The plan's own rule is that "an outcome the library cannot interpret is terminal, never a wait".
- So an unresolvable entry must wake with a qualified terminal status, for example `:dao.stream.waitset/unresolved`. Add a test.

**I-6 (should-fix) — the `:put` entry is an effect, not a readiness probe, and W0 and W4 do not account for it.**
- There is no non-mutating "would append succeed" check, so the library performs the append itself. That suits the VM.
- For `dao.stream.serving`'s forwarder, `forward-step` owns read, append and cursor advance as one step (`forward.cljc:67-89`), and `:retry` re-reads because the cursor did not move.
- Handing the append to the waitset splits that step across two layers.
- `dao.stream.apply/serve-once!` keeps its pending response in its own state precisely so the handler is not invoked twice.
- W4 should say that forwarder and serve-once adoption is reader-side only (`:next` entries on the source or request medium). The consumer keeps its own write retry, or the plan specifies the handoff.
- The census row's "`:put` for a destination-full forwarder" should be corrected.

**I-7 (should-fix) — gap write-back on a shared cursor is not pinned by a test.**
- The engine writes back any returned `:cursor`, including the recovery cursor for `gap` (`engine.cljc:304-306,355`).
- The plan's wording, "successor `:cursor` for a moved reader", invites an implementation that handles only `ok`.
- Add a W1 test: two readers share a cursor-ref over an evicting ring buffer. The first wakes with `gap`, and the second reads from the recovery cursor.

**I-8 (note) — leftover draft text.** Plan:105-108 still reads "takes a store-updating callback… no: it takes…". The plan rewrites its own design mid-sentence, exactly at the seam gpt's Finding 3 attacks.

**Passed.**
- Invariants 1, 5 and 6 hold for W1 and W2: state is a value, the substrate sees zero diff, and no graph is assumed.
- The name `dao.stream.waitset`, the refusal of `:resume`, ready queues and tasks, the W0 census quality, and the W0/W1/W2 decomposition are all sound.
- The "unchanged suites" standard for W2 is the right one; the deliverable just contradicts it.

## Part 2: Response to gpt-5.6-sol Findings

### Finding 1 — W3 callback/shared-state violations
**PARTIALLY AGREE — real defects, wrong diagnosis, and a remedy that cannot work.**

What I reject:
- I reject the claim that a blocking queue or timer in a per-host driver file violates Invariants 2–4 and "requires an explicit revision of the foundational invariants".
- The contract places this cost at this layer: "DaoStream schedules nothing, so cadence belongs to the runtime driving the interpreters, and a readiness mechanism belongs there too" (`dao.stream.md:182-184`).
- The tree already contains accepted tick owners of this kind:
  - `yin.repl`'s `poll-loop!` with `Thread/sleep` (`repl.cljc:194-215`);
  - the `setInterval` and `Timer.periodic` owners (`repl.cljc:267,352`);
  - `dao.jing.remote`'s daemon thread, commented "The ticker is host policy" (`remote.cljc:977-988`).
- OD-5's proposed language, which is not yet binding, says the same: "A waiting call or a callback may wrap that step as **host policy**, named as such."
- On cljs and cljd, a timer callback is the only thing that can make anything run.
- gpt's remedy is that timer callbacks "only append cadence events to a control stream". That regresses: something must then poll the control stream, and that poller is the sleeping driver. The bottom of the stack is always a function invoked by the host loop.
- The Host Boundaries rule governs how events reach portable interpreters. It does not forbid a composition root from having a loop.

What I accept:
- The plan contradicts itself. W3 "dispatches `:woken` through the host's disposition" (plan:271), while plan:122-126 says woken entries are "returned, never dispatched".
- A library container that takes a function to invoke is, verbatim, the non-adapter of `datom.world.md:117-118`: "has relocated the callback".
- "Held in an atom the host creates" combined with cross-thread `nudge!` and external parks is shared mutable state. It is also unnecessary. `poll-loop!` already shows the right shape: "sole owner… nothing is shared with the reader".

My correction:
- Split W3. A pure `.cljc` cadence step, `(round driver-state now) → {:state … :woken … :sleep-ms …}`, computes backoff, reset-on-wake and the due time as data.
- The host files shrink to one primitive each: sleep up to `:sleep-ms`, or until nudged.
  - On clj this is `queue.poll(timeout)`. The loop thread is the sole owner of state as loop locals, and external parks arrive as data on the same queue.
  - On cljs and cljd this is arm-one-timer, where the timer calls the *host composition's own tick*, exactly as `run-node!` does today.
- The host loop calls `round` and consumes `:woken` itself. The library never receives a disposition function and never holds an atom.
- Label the host files "host policy" in their docstrings.

### Finding 2 — nudge! out-of-band bypass
**PARTIALLY AGREE — should-fix, not blocking.**

- The nudge carries no information, and correctness does not depend on it. The cause of any wake it triggers, the append, *is* on the stream.
- The claim that a cadence change must itself appear on a stream proves too much. The `:poll-ms` timer expiring is an equally off-stream cadence cause.
- By that standard every existing tick owner would violate Invariants 1–3.
- The proposed remedy, nudges as events on a control stream, defeats itself. The nudge exists to end the sleep of the one entity that would have to poll that stream.
- `LinkedBlockingQueue.offer` of a contentless token is the host sleep primitive, not a second event path.

Two sub-points stand:
- **Coupling.** "The ws inbound path after `deposit!`" is wrong if it means the adapter calls `nudge!`. The adapter "is handed only the deposit operation and its own host resource" (`datom.world.md:88-89`). The compliant form is that the *composition* wraps the deposit operation it hands the adapter, so the adapter stays ignorant of the driver. The plan should say so.
- **Transition coverage.** A close turns `blocked` into `end`, and a consuming reader can turn `full` into `ok`. The plan names only appenders.

My correction:
- Define `nudge!` as idempotent and contentless. It may be called by any composition after any transition it caused.
- Never require it. A lost nudge still costs one tick.
- Keep it in the host driver files, not in the pure namespace.

### Finding 3 — resolver algebra incomplete
**AGREE** (blocking by the plan's own standard, "settled here so no phase decides them alone").

- `engine.cljc:355-359` does `(assoc store cursor-id (assoc (get store cursor-id) :cursor …))`, which is VM store structure. A generic library cannot do that to an opaque store.
- I-8's leftover sentence shows the seam was unsettled when it was written.
- Of gpt's two options I take the first, in its simplest form. The resolver is a map of two pure functions, invoked synchronously and never retained: `{:resolve (fn [store entry]) :advance (fn [store entry cursor])}`.
- Synchronous higher-order use (the shape of `reduce`'s `f`) is not a callback in the sense of Invariant 3, and gpt concedes this.
- I reject the waitset-owned cursor overlay. It creates a second authority over cursor state that the host must reconcile, in violation of "derive, don't persist".
- gpt's non-map-store test is a good idea; adopt it.

### Finding 4 — budget starvation
**AGREE on the defect; DISAGREE with the rotation remedy.**

- The starvation is real. There is also a second incoherence: `:budget` lives on the W3 container, yet `check` is defined as one complete pass, so the driver has no way to enforce it.
- gpt's rotation option breaks a preserved law. Wait-set order *is* the wake order among co-waiters on a shared cursor-ref (`engine.cljc:325-331`). Rotating still-waiting entries behind unpolled ones reorders those waiters between rounds.
- Correction: delete `:budget`.
  - The plan itself says consumers park "dozens of entries, not thousands" (plan:154-156).
  - A complete O(n) pass is what *What it costs* accepts.
  - The house rule is "do not optimize prematurely".
- If a bound is ever needed, it must be a scan position that preserves relative order, and it must be specified then.
- Severity: blocking for W3 only. It does not touch W1 or W2.

### Finding 5 — writer `closed` → `:end`
**AGREE, fully; verified.**

- `poll-wait-entry` resolves a parked writer's `closed` as `{:value o :status o}` (`engine.cljc:308-312`).
- `terminal-resume-outcome` treats only `#{nil :ok :end :dao.stream/gap}` as non-terminal (`engine.cljc:397-399`). Everything else raises "Stream append failed" (`engine.cljc:410`).
- This matches `handle-put`'s documented immediate path (`engine.cljc:178-179,191`).
- Mapping `closed` to `:end` would turn a raise into a silent resume, and it depends on whether the first attempt happened to park. That is the exact asymmetry the engine docstring forbids (`engine.cljc:393-394`).
- Correction as gpt states: `closed` keeps its own keyword, and only reader `end` maps to `:end`.

### Finding 6 — entry schema / unknown :reason
**PARTIALLY AGREE.**

- The missing writer value is real (`:datom`, `engine.cljc:308`). My I-2 is the broader fix: entries stay opaque and the resolver surfaces the value, rather than the library standardising a generic key.
- On unknown `:reason`, gpt is right that permanent polling is a liveness bug. The register row "Unchanged" preserves an accident, not a suite-dependent property.
- A grep confirms that every VM park site uses only `:next` or `:put`.
- I would not make `park` throw, given the house preference for total functions over partial ones.
- Instead, `check` wakes the entry with a qualified terminal status, consistent with I-5 and with the plan's own rule for uninterpretable outcomes.
- The engine's `terminal-resume-outcome` ignores reasons outside `#{:next :put}`, so the unchanged W2 suite is unaffected.

### Finding 7 — `:woken` lifecycle
**AGREE.**

- The engine gives the answers: `woken` is loop-local per round (`engine.cljc:339`), and `:stream` is dropped before the ready entry is built (`engine.cljc:150-154`).
- The plan's wording that state is threaded as `{:waiting [] :woken []}` invites accumulation.
- Better: keep `:woken` out of the threaded state entirely. `check` returns `{:waitset {:waiting …} :woken [...] :store store'}`, and the next `check` takes `:waitset`.
- Resolved handles never appear in returned entries.
- gpt's tests for a repeated check and for serializability of woken entries should both be added.

### Finding 8 — W4 list contradicts W0
**AGREE.**

- The census resolves the GUI pump to "nothing should change" for `advance`, and `rpc/poll!` to single-stream.
- It names `dao.stream.serving/step!` and the `yin.repl` tick owners as the multiplexed sites.
- W4 still carries the list guessed before the census. Replace it with the enumerated census decisions, plus the I-6 caveat that forwarder and serve-once adoption is reader-side only.

### Finding 9 — W5 scope
**AGREE; verified.**

- `dao.await.md` names `dao.runtime` as live at about twenty sites, including :17, :36, :47, :58, :62, :235, :259, :366, :421-432, :448 and :497-500 (the last being "task maps with `:resume`").
- The end condition (plan:378-379) cannot be met by editing one sentence while "no other prose [is] touched".
- `dao/await.cljc:232` already names `yin.vm.engine/check-wait-set` correctly.
- Addition: the generated `test/cljd-out/dao/runtime-test_test.dart` is a stale artifact to clear when the cljd lane regenerates.

### Finding 10 — outcome-set iteration doesn't fail on new outcomes
**AGREE.**

- With "everything except blocked/full is terminal" as the default, iterating the declared sets passes vacuously for a new outcome.
- Pin a literal expected-classification map, and assert that its key set equals `outcomes-next ∪ outcomes-append`. A new outcome then fails on domain inequality.

### Finding 11 — `check` is not pure
**AGREE** (note).

- `append!` is an effect, and the plan admits it at :109.
- "Pure in, pure out" should read: a state-threaded synchronous interpreter step, effectful only through the two handle operations, with no retained references.

## Summary

**Agreement and disagreement.**

| Finding | Position |
| --- | --- |
| 3, 5, 7, 8, 9, 10, 11 | Agree fully |
| 4 | Agree on the defect; reject the rotation remedy |
| 6 | Agree on the defect; prefer a total `check` classification to a throwing `park` |
| 1, 2 | Substantive disagreement |

- gpt-5.6-sol reads the Host Boundaries adapter rule as a ban on any host-driven loop.
- That reading contradicts `dao.stream.md:182-184`, three tick owners already in the tree, and the direction of OD-5.
- Its control-stream remedy regresses: something must still be called by the host to poll that stream.
- The defects in W3 that survive are narrower: the library must not accept a disposition function, and must not hold a shared atom. Reshaping the driver fixes both; no revision of the invariants is needed.
- `nudge!` is sound once the *composition* wires it and the adapter does not.

**What gpt-5.6-sol missed.**
- I-1: the W2 deliverable is unsatisfiable, because `check-wait-set` is public and tests call it directly.
- I-2: the library entry schema does not match the entries the "unchanged" suites construct.
- I-5: a nil result from the resolver is unclassified.
- I-6: `:put` is an effect, and that breaks forwarder and serve-once adoption.
- I-7: gap write-back on a shared cursor has no test.

**Recommended path.**

Revise the plan before W1, in one pass:
1. Make entries opaque, with a two-function resolver `{:resolve :advance}`.
2. Keep `closed` as its own keyword.
3. Make `check` total over unresolved entries and unknown reasons.
4. Return `:woken` per round, outside the threaded state.
5. Pin the classification with a literal map.
6. Keep `engine/check-wait-set` as the engine's public consumer function, and narrow the grep end condition.
7. Delete `:budget`.
8. Reshape W3 as a pure cadence `round` plus per-host sleep-or-nudge primitives, labelled host policy, with no disposition argument and no atom.
9. State the composition-wiring rule for `nudge!` and its transition coverage.
10. Rewrite W4 from the census, with reader-side-only adoption for forwarders.
11. Widen W5 to cover all of `dao.await.md`.

W0 needs only the forwarder `:put` correction.

This review made no edits. Writing the findings file into `collab/` is left to the orchestrator; I did not write it.
