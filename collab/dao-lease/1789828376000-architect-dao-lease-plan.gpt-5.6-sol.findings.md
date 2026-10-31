Created-GMT: 2026-09-19 14:32:56 GMT
Session-ID: 01a0ba16-23a8-7462-8bfd-70eecd6f8388 (provider-generated codex thread)
Coding-Agent: codex / gpt-5.6-sol
Task: adversarial architecture review of docs/design/dao.lease.implementation-plan.md
Verdict: unsound — findings 1-5 blocking, 6-18 should-fix, 19-20 note
Orchestrator spot-check 2026-09-19: observe.cljc effect-outcome set, stream.cljc
outcomes-next, forward.cljc batch budget, ringbuffer.cljc atom state — the four
load-bearing citations verified against source; findings accepted to triage.

---

## Verdict

The plan is unsound as an implementation specification. Its core lease model is plausible, but it cannot support its §7 completeness claim, leaves an operative-contract contradiction unresolved, directly violates the timer prohibition, and specifies several mechanics that do not fit the cited `dao.stream` surface.

1. **Blocking — §0 correction 1 / D1: the two operative contracts still contradict each other.**

   `dao.stream.md:752-755` requires lease facts to be “datoms on a medium (`dao.space`)”; `dao.lease.md:14-17` specifies plain data on ordinary streams using two map dispatch keys. Datoms are plain data, so the latter does not logically repeal the former, and `dao.lease.md:8-10` explicitly says it leaves `dao.stream.md` unchanged.

   The plan simply declares the stream wording stale. A subordinate implementation plan has no authority to do that. Either `dao.stream.md` must be amended, or the lease contract must explicitly supersede that sentence. Until then, building plain maps directly on `dao.stream` chooses one side of an unresolved operative contradiction.

2. **Blocking — §4.4 / C5: the reference tick source violates the contract and the plan’s own invariant.**

   `dao.lease.md:82` says, without qualification, “No timer holds a callback.” C5 says no lease code “holds a callback [or] installs a timer.” Yet Phase 4 builds `js/setInterval` and `async/Timer.periodic` inside `src/cljc/dao/lease.cljc` (`plan:378-381`). Both install timers holding callbacks.

   Calling that subsection “host policy” does not move it outside the namespace or repeal the prohibition. The §7 grep is artificially scoped to exclude the knowingly violating section. A compliant reference source needs a host-driven step/deposit function, not a timer installed by `dao.lease`.

3. **Blocking — D2 / Phase 2: `judge-step` is not pure as specified.**

   The plan repeatedly calls it a “pure step” over immutable state (`plan:185-197,309-310`), but the same call must:

   - invoke `stream/next` on mutable transport handles;
   - invoke the resource-reclaim effect;
   - append `:lapsed` to a writer.

   The actual ring buffer uses an atom and mutating `append!` (`ringbuffer.cljc:23-33,112-132`). `forward-step` is host-neutral and explicitly state-threaded, but not pure; it calls `observe/step`, which reads and appends (`forward.cljc:107-116`). The implementation can be a single effectful interpreter step, but not the pure function the plan promises.

4. **Blocking — D5 / J8: `observe/step` does not provide the claimed reclaim→record transaction.**

   `observe/step` performs one source read and one writer-shaped effect. Its effect must return a valid `append!` outcome (`observe.cljc:58-105`). It has no operation for:

   - iterating ledger entries;
   - storing `pending` before reclaim;
   - retaining the classified cause and reclaim-success bit;
   - sequencing one arbitrary reclaim effect followed by a separate append;
   - removing a ledger entry only after that append succeeds.

   The plan says Phase 2 first reclaims in step 5 and then uses `observe/step` for step 6, while D5 calls it a “per-lease reclaim/record pair.” No source stream or cursor for such a pair is identified. A boolean-returning `(reclaim subject)` is not even a valid `append!` outcome for `observe/step`.

   Record-after-act is realizable with explicit ledger state and direct append outcome handling, but not via the cited primitive in the manner specified.

5. **Blocking — §7 accounting: several operative rules are neither invariant-pinned nor deferred in §6.**

   The completeness claim at `plan:442-445` is false. Uncovered contract rules include:

   - lease IDs are minted by the grantor and never reused; proposal IDs are minted by the holder and echoed by an answer (`dao.lease.md:34-37`);
   - a grantor owes no proposal answer and an unanswered wait has no vocabulary bound (`69-71`);
   - unsolicited grants are permitted and delivery identifies the holder (`72-73`);
   - `:policy` may not be implemented by declining eligible evidence (`74-76`);
   - no notification marks a lapse (`82`);
   - no absolute time appears in a fact (`83`);
   - reclaim frees the resource, never its record (`84`);
   - persisted-ledger readings remain comparable across restart (`89-92`);
   - a tick-stream gap makes a pass late, not wrong (`94-95`);
   - the holder’s bound limits attention, not access (`195-196`);
   - all three sizing relations for renewal interval, tolerance, and retention window (`225-228`);
   - `:dao.lease/max` binds only within one ledger lifetime (`238`);
   - repeated gaps can cause a healthy continuous leak and per-attachment media provide isolation (`239-241`);
   - the stated reclamation bound (`242-243`);
   - carriage is delivery, not second authoring (`247-249`);
   - `:lapsed` remains grantor-local and a remote holder observes the resource event instead (`251-254`, apart from the close-code deferral);
   - transfer and delegated renewal remain out of scope (`256-260`).

   §6 defers persistence machinery, durable-resource prerequisites, a close-code design, finished use cases, and a generic unit table. It does not defer the rules above.

6. **Should-fix — J7: the policy rule is weakened.**

   J7 preserves cause ordering, but it does not preserve `dao.lease.md:74-76`: policy may end a lease, yet may not do so by refusing to count eligible evidence. An implementation could pass every listed policy test while making its policy predicate suppress renewals.

   Eligibility must be applied before policy classification, and tests must prove a renewal is recorded even when `:policy` wins the same pass.

7. **Should-fix — J9: incomplete-evidence semantics are incomplete.**

   J9 covers gap→unknown, restart→unknown, delayed silence, and non-silence conditions. It omits:

   - absence is evidence only over an observed window;
   - a surviving older renewal is not inferred to be the newest (`dao.lease.md:166-170`);
   - a tick-cursor gap is not a lease-evidence gap (`94-95`).

   The implementation could therefore treat a tick gap like a fact gap, infer retained evidence as complete, or apply tolerance to the unknown recovery window and still satisfy the written invariant/tests.

8. **Should-fix — J6/J10: draining every cursor to `blocked` is not guaranteed to terminate.**

   `stream/next` is non-blocking, but a concurrently written stream may keep returning `ok`; DaoStream exposes no snapshot tail or batch boundary. `forward-step` avoids this with a batch budget (`forward.cljc:67-89,102-121`). The proposed judge has no budget and insists that one step drain every cursor to `blocked`.

   Thus a continuously active tick or fact stream can prevent a pass from completing, invalidating the declared-cadence duty. The contract itself demands the drain, so the composition needs a quiescent/snapshot medium guarantee or an explicit design resolution—not an unbounded loop hidden in `judge-step`.

9. **Should-fix — D4: the resolver signature cannot support all claimed attribution modes.**

   D4 defines `(resolver fact) -> author`, then claims it supports per-author media identity and transport attachment identity. `stream/next` returns only value and successor cursor; attachment identity is returned at attach time, not embedded in each value. A plain fact alone cannot reveal which reader or attachment supplied it.

   Attribution must be bound per cursor/medium, or receive explicit source context such as `(resolver source fact)`. The current signature only reliably supports authorship embedded in an envelope.

10. **Should-fix — C2/C3: assembly compatibility is underspecified and partly uninspectable.**

    DaoStream handles expose surfaces, not retention policy or authorship. `dao.stream.md:784-788` explicitly says retention is composition configuration, not a runtime handle predicate. Ringbuffer descriptors expose type and identity, not an “evict-oldest” declaration (`ringbuffer.cljc:44-50`).

    `make-judge` therefore cannot reject an evict-arbitrary medium from the listed handles alone. It needs an explicit, validated medium declaration in its config. Likewise, `fn?` can establish that a resolver exists, but cannot establish semantic compatibility with a medium. The plan never defines the evidence or validation protocol.

11. **Should-fix — Phase 1 tests do not prove V1–V5.**

    The malformed corpus omits several required cases:

    - neither dispatch key is ignored by a reader;
    - both dispatch keys are defective;
    - invalid or non-positive cap and reading;
    - invalid tolerance, including permitted zero;
    - repeat `:released` and repeat `:lapsed`;
    - use of a unit not in the composition’s fixed table;
    - composition-wide agreement on one unit table.

    V5’s absence of ambient state is not established by example inputs. The proposed tests could pass with a validator that reads a clock, stream, or global atom.

12. **Should-fix — Phase 2 leaves major J invariants untested.**

    No scripted test proves:

    - J1: a non-grantor `:accepted` establishes nothing;
    - J1: a delayed eligible renewal still counts;
    - J3: proposals create no ledger state, unanswered proposals remain unbounded, and renegotiation requires a new ID;
    - J5: every required ledger field is present and tenure start is immutable;
    - J6: no classification before the first tick;
    - J6: all cursors drain before classification and every fact receives the same pass `now`;
    - J6: authored-between-passes grants seed at the next pass’s `now`;
    - J7: cause precedence when release, cap, policy, and silence coincide;
    - J7: a pending lease keeps its original cause;
    - J9: recovery from a persisted ledger begins unknown;
    - J9: release and policy apply unchanged while unknown;
    - J10: `end` retires only that cursor;
    - J10: `transport-error` aborts the whole pass before classification.

    Ringbuffer cannot naturally produce `transport-error`, so J10 needs a scripted fake reader, not only the stated test medium.

13. **Should-fix — Phase 2’s seed-from-grant test is too weak.**

    The test begins with “grant seeded at reading 0.” If it constructs or pre-seeds the ledger directly, it can pass without proving that a drained or authored grant is stamped with the current pass’s `now`. It must enter through the real grant path and assert both tenure start and last relevant observation equal that pass-wide stamp.

14. **Should-fix — Phase 3 appears to invert the renewal rule.**

    The contract requires renewal intervals to be strictly less than half the duration (`dao.lease.md:187-189,225`). The proposed test says `should-renew?` is true below half and false at or above half (`plan:361-362`). That permits arbitrary immediate renewal, then disables renewal once the deadline has been missed.

    A schedule predicate needs a defined trigger that guarantees a successful next renewal before half-duration, and must treat equality as already violating the strict sizing relation. Merely testing whether renewal is “allowed below half” does not prove H2.

15. **Should-fix — Phase 3 does not prove H1 or H4.**

    “False until the grant is observed” does not test grantor attribution. There is no test that a forged/non-grantor grant establishes nothing. There is also no scripted release test proving the holder emits `:released`, stops its own activity, and does not itself reclaim or author `:lapsed`.

16. **Should-fix — Phase 4 tests cover only a fraction of C1–C5.**

    The constructor tests omit refusal for:

    - missing runtime/cadence declaration;
    - missing judge or holder tick stream;
    - missing or invalid tolerance;
    - missing holder-side resolver;
    - missing grant/`lapsed` writer;
    - missing recipient fact medium;
    - incompatible resolver rather than merely absent resolver;
    - durable mode without all three C4 prerequisites.

    The end-to-end cycle also does not establish media carriage, attribution by attachment, grantor-local `:lapsed`, process scoping, or declared maximum interval between completed passes.

17. **Should-fix — C4 is declared but neither built nor proved.**

    §6 defers the durable mechanisms to resource owners, which is legitimate, but C4 additionally requires an unsettled composition to keep leases process-scoped. Nothing in `make-judge`, the phases, or the tests specifies or enforces that fallback. A configuration could claim durable use without fencing and still pass all proposed tests.

18. **Should-fix — repeated-fact validity is stateful but Phase 1 presents it as a fact validator.**

    “Both answers,” repeated `:accepted`, repeated `:released`, repeated `:lapsed`, and stale readings cannot be decided from “given a fact” alone. Phase 1 acknowledges prior reading but does not define the required history for answers and repeats. V5’s signature description and the `valid?` build item are therefore incomplete.

    The plan must separate structural per-fact validity from ledger/history-dependent admissibility and state exactly where each check runs.

19. **Note — the plan does not classify all reader outcomes.**

    The actual reader surface also returns `gap`, `cursor-mismatch`, and `invalid-cursor`, besides `blocked`, `end`, and `transport-error` (`stream.cljc:78-85`). Gap has lease semantics, but the plan does not say what happens on cursor mismatch or invalid cursor. They should at least abort classification as defects; silently treating either as a completed drain would manufacture absence evidence.

20. **Note — several tests can pass while their named invariant is false.**

    Concrete examples:

    - individual cause tests pass even if cause precedence is wrong;
    - a single-gap test passes if the judge marks leases on every medium unknown;
    - reclaim retry passes even if the cause is reclassified on retry;
    - grant→renew→lapse passes with different timestamps per fact instead of one pass-wide `now`;
    - an `end` result test can pass even if the cursor is retried next pass unless later calls are asserted;
    - a transport-error result test can pass even if earlier leases were already reclaimed during the aborted pass;
    - non-`ok` renewal testing only final expiry can pass if the holder incorrectly advances some other internal renewal timestamp;
    - “records exactly once” on ringbuffer does not prove behavior after `full`, `closed`, `invalid-value`, or `transport-error`.

VERDICT: unsound
