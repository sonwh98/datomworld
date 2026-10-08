Completed-GMT: 2026-10-07 12:44:17 GMT
Completed-Local: 2026-10-07 19:44:17 Asia/Ho_Chi_Minh

**Ruling: land a prerequisite holder-driver split, D15a, before D15 composition.** Expose `control-step`, `program-step`, `stop`, and `owed-control-write?`. D15 then composes those public functions without modifying holder internals.

The blocker is real. `driver/step` dispatches active holders through `step-active`, whose path includes `run-cycle`: guest execution, replay application, writer emission and live reads (`driver.cljc:1890–1919`, `1944–2047`, `2134`). Neither disabling observation nor selecting phases externally implements a control-only step.

### 1. Public machine-facing contracts

```clojure
(driver/control-step state)       ; -> state
(driver/program-step state)       ; -> state
(driver/stop state)               ; -> state
(driver/owed-control-write? state) ; -> boolean
```

These functions operate on the existing driver state and retain its public `:phase`, `:status`, `:detail` and `:machine` conventions. They add no handoff, lease or DaoStream wire fields.

**`control-step` owns custody protocol progress.** It may read authenticated control replies and complete ledger history, validate binding and tenure, renew, retry existing requests, publish control diagnostics, and advance release/report/offer acknowledgment and closure handling. All existing request identities, intent-before-attempt brackets, authenticated acknowledgment rules and uncertain-append stalls remain binding.

It must never:

- call `vm/run`, program replay, `writer/emit`, live reader observation or program stream operations;
- attach program resources or lower a newly granted checkpoint;
- prepare an export by acquiring program cursor positions or serving program resources;
- apply program outcomes in a way that executes a continuation or invokes a program effect.

This last distinction matters: the current `drain` combines custody replies with `writer/discharge` and `reader/settle` (`driver.cljc:1804–1836`). It cannot simply be renamed control drain. Route custody replies immediately; retain program replies/outcomes for the program step, preserving attribution and arrival order. Such buffering must not introduce a crash-loss window: recovery must reproduce an unconsumed observation from its durable source or a durable retained record before advancing an unrecoverable reader position. No silent discard or “read dry” loss is permitted.

Control handling may accept and retain authenticated grant evidence without lowering or activating its program. Split the current `accept` tail accordingly: today it journals acceptance, calls `lower`, and creates a running machine in the same operation (`driver.cljc:1578–1597`). Grant observation does not authorize execution, and its original tenure basis must not be refreshed when lowering eventually occurs.

**`program-step` owns checkpoint activation and program advancement.** It performs validation/restoration work requiring program resources, pending program-result application, execution, replay, emission, live observation, and source/export preparation. It also performs the program-side work needed to reach or capture a handoff boundary. It may advance only a driver whose local stop latch is clear.

Before activation, execution or applying a program result, it must independently establish:

1. complete authenticated arbitration evidence for the relevant binding;
2. the same live occurrence, lease and epoch;
3. `lease/holding?` under a fresh clock reading, including the maximum cap and latches.

An earlier control step is not an authorization token. Unavailable evidence suspends progress; contradictory binding or expired tenure follows the existing run-end/cleanup contract. Preserve the existing checks immediately before external program IO as well: a successful entry check does not cover time elapsed during execution.

The two paths must share holder-owned helpers rather than duplicate custody algorithms. An armed exit is split by action, not merely by `:phase`: export preparation belongs to the program side; retries and completion of an already established control bracket belong to the control side. Preserve the landed successor-attempt → report → release/closure → successor-admission order.

**Judge and front steps remain composition-owned.** `driver/control-step` does not secretly step the authority or every front. The composition’s control step explicitly advances the judge, relevant fronts/readers and each holder’s control step, with deterministic ordering and one owner for each component. No second DHT loader, timer or scheduler is introduced.

Keep public `driver/step` as the normal-operation convenience entry. Implement it through shared split machinery and preserve existing protocol ordering and tests; do not accidentally renew or execute twice by composing two independently complete copies of the old active step.

### 2. Owed-control-write predicate

`owed-control-write?` is a pure, total Boolean query over driver state. It performs no reads, clock sampling, journal writes or sends.

It is true when the driver has a control request, acknowledgment-dependent retry, diagnostic or required cleanup write that remains eligible to be attempted. Include proposals, renewals, releases, offers and reports—not merely the present private predicate’s renewal/release subset (`driver.cljc:1764–1771`). An inbound append returning `:ok` does not clear an obligation whose protocol requires authenticated carriage or admission.

A due renewal is discovered by `control-step`; its resulting obligation is then visible to the predicate. A future renewal deadline alone does not make the predicate true. Waiting solely for ledger visibility, with no retry owed, is false.

A driver stalled by an uncertain journal append does not advertise an executable write until explicit reconciliation makes one eligible. It must not retry through this predicate. The predicate is derived from protocol state, not an independently persisted “busy” flag.

The composition combines this predicate with its authority/front owed-write predicates. `moved?` uses that aggregate; custody phase names and private request maps must not leak into REPL cadence logic.

### 3. Shutdown and hydration

**`stop` establishes an irreversible local stop latch for that driver instance.** It is idempotent, executes no guest code and does not masquerade as a custody-machine gate or an export abort.

After it is set:

- `program-step` returns without activation, attachment, program-result application, execution or program IO.
- Control steps continue during every tick of the existing bounded drain.
- No fresh candidacy, replacement proposal or recovery re-proposal begins.
- An already outstanding proposal remains subject to reconciliation. A grant arriving during shutdown is authenticated and cleaned up through a journaled release, without lowering its checkpoint.
- Existing release and exit brackets retain their journal and acknowledgment discipline. An established report/offer bracket may finish; shutdown does not invent successful completion for an unfinished program or bypass quarantine.
- A live holder enters cleanup rather than renewing indefinitely merely to keep a stopped program alive. Where an already established exit action still requires tenure, preserve that action’s existing tenure checks; never restart guest work to finish it.

Every new cleanup send must have its durable intent first. If the bounded drain ends while the inbound stream stays full, shutdown still terminates within the existing budget; reopening the journal must recover the pending cleanup obligation. An uncertain journal append stalls rather than authorizing a speculative release or execution.

Hydration differs from shutdown: it temporarily omits `program-step` but does not latch `stop`. Existing holders continue control renewal and cleanup. Newly observed grants remain unactivated until hydration permits the program step, which then rechecks tenure and evidence.

In `step-all`, the composition control step remains immediately after DHT progress and before admission/refusal branching. If the shell stops during that tick, set the stop latch before any custody program call. Also set it at shutdown entry on every host, including paths that enter the bounded drain without another normal `step-all`. No custody program call is allowed after `:running?` becomes false.

### 4. Disposition and acceptance

**D15a is a separately reviewed and landed prerequisite**, touching `holder/driver.cljc`, its tests and the relevant contract documentation. A small holder-internal helper namespace is permitted if needed for separation; widening engine, authority or wire contracts requires a further ruling. D15’s original composition-only scope resumes after this seam lands.

D15a must pin these rows on JVM, Node and Dart:

1. **Control isolation:** a runnable machine with ready guest work and pending program reads/writes receives repeated control steps. Renewal/release progress; guest execution, attachment, replay application and program IO counters remain zero.
2. **Mixed inbox:** interleave custody acknowledgments and program records. Control acknowledgments progress without losing, reordering or applying program records; the later program step processes them exactly once under valid tenure. Include restart before application.
3. **Grant while paused:** observe a grant during hydration without attachment or activation. Resume before expiry successfully; resume after expiry refuses execution and performs cleanup.
4. **Tenure changes between steps:** expire the clock, replace the binding, or make complete evidence unavailable after control progress and before program progress. Assert zero unauthorized execution and IO.
5. **Shutdown race:** stop before a pending grant arrives. Authenticate and release it without lower; suppress recovery re-proposal and all program calls.
6. **Full-stream drain:** release retries preserve identity and durable intent. Acceptance during the drain completes cleanup; continuous fullness ends within budget and restart recovers the release.
7. **Journal uncertainty:** inject uncertain append at each newly split bracket. Both entry points preserve the stall; neither sends past it nor activates the program.
8. **Exit continuity:** split ticks preserve the existing offer/report/release/closure ordering, terminal-report cleanup and quarantine behavior.
9. **Cadence:** pin each owed request family, authenticated acknowledgment clearing, passive ledger waits and stalled-state behavior.
10. **Compatibility:** existing driver acceptance and recovery tests remain valid through `step`; split scheduling produces the same program effects and custody outcomes without duplicate cycles.

D15 retains its eight composition/REPL acceptance rows, particularly hydration and all three bounded shutdown loops. The prerequisite supplies the missing capability; it does not replace those integration proofs.

No files were edited and no suites were run.